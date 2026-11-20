package io.smarthappy.module.sales;

import static io.smarthappy.platform.common.text.VisibleText.isBlank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.sales.SalesDtos.CloseForm;
import io.smarthappy.module.sales.SalesDtos.CustOption;
import io.smarthappy.module.sales.SalesDtos.CustRef;
import io.smarthappy.module.sales.SalesDtos.ItemOption;
import io.smarthappy.module.sales.SalesDtos.LineForm;
import io.smarthappy.module.sales.SalesDtos.LineRow;
import io.smarthappy.module.sales.SalesDtos.LineValue;
import io.smarthappy.module.sales.SalesDtos.OrderDetail;
import io.smarthappy.module.sales.SalesDtos.OrderForm;
import io.smarthappy.module.sales.SalesDtos.OrderList;
import io.smarthappy.module.sales.SalesDtos.OrderRow;
import io.smarthappy.module.sales.SalesDtos.OrderSort;
import io.smarthappy.module.sales.SalesDtos.OrderValue;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 수주 관리 (sal-order) — P2 2단계, docs/25 4.3.
 *
 * - 머리: 거래처·번호는 고정, 고객 발주 번호는 거래처 안 유일. 상태는 행에서 계산(열린 행 → OPEN, 모두 취소 → CANCELLED, 그 밖 CLOSED)
 * - 행: 품목·행 번호 고정, 열린 행만 수량·납기 변경, 끝내기는 마감(DONE)·취소(CANCELLED, 사유 필수) — 되돌리지 않는다
 * - 행을 바꾸는 모든 동작은 먼저 머리를 잠근다(FOR UPDATE) — 마지막 두 행을 동시에 마감해도 머리 상태가 맞게, 행 번호가 겹치지 않게
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    static final String PROG = "sal-order";
    static final String RULE = "SAL_ORDER";
    static final int MAX_LINES = 500;
    static final int LIST_LIMIT = 500;
    static final int QTY_INT_DIGITS = 12;
    static final int QTY_MAX_SCALE = 6;
    static final int MAX_PERIOD_DAYS = 366;
    /** 고객 발주 번호: 출력 가능한 ASCII, 앞뒤 공백 없음(DB CHECK 와 같게) */
    public static final Pattern PO_NO = Pattern.compile("^[\\x21-\\x7E]([\\x20-\\x7E]*[\\x21-\\x7E])?$");
    static final Pattern DECIMAL = Pattern.compile("^[+-]?\\d+(\\.\\d+)?$");
    static final Set<String> ORDER_STATUS = Set.of("OPEN", "CLOSED", "CANCELLED");

    private final SalesMapper mapper;
    private final NumberingService numbering;
    private final InboxService inbox;
    private final CodeService codes;
    private final BizClock clock;
    private final PolicyService policies;

    /** 그리드 기본 정렬 = 기존 목록 순서(수주일 최신, 끝 키 = 수주 id) */
    static final List<String> DEFAULT_SORT = List.of("orderDate,desc");

    /** 거래처 칸 = 코드 · 이름, 행 칸 = 진행/전체 — 화면이 붙인다(format). 상태는 상세가 열려 목록이 좁아도 보이게 앞쪽(MES 4.29 와 같게) */
    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("orderNo", "salOrder.col.orderNo", 130).withSort().withPriority(1),
            ColumnSpec.text("custNm", "salOrder.col.cust", 170).withSort().withPriority(1).withFlex().withMinWidth(110),
            ColumnSpec.code("status", "salOrder.col.status", "SAL_ORDER_STATUS", 80).withSort().withPriority(1),
            ColumnSpec.of("orderDate", "salOrder.col.orderDate", ColumnSpec.ColumnType.DATE, 100).withSort().withPriority(1),
            ColumnSpec.of("nextDue", "salOrder.col.nextDue", ColumnSpec.ColumnType.DATE, 100).withSort().withPriority(2),
            ColumnSpec.text("openCnt", "salOrder.col.lines", 90).withPriority(3),
            ColumnSpec.text("custPoNo", "salOrder.col.custPoNo", 130).withPriority(3));

    public record Page(List<OrderRow> list, Long total) {
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public List<CustOption> custs() {
        return mapper.findSalesCusts(true);
    }

    @Transactional(readOnly = true)
    public List<ItemOption> items(String q) {
        if (isBlank(q)) {
            return List.of();
        }
        String s = q.strip();
        if (s.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return mapper.searchItems(like(s), 20);
    }

    /** 기간 필수(최대 366일). 500건을 넘으면 more = true(조건을 좁히라고) */
    @Transactional(readOnly = true)
    public OrderList list(String custCd, String from, String to, String status, String keyword, String itemCd, String dueFrom,
            String dueTo) {
        OrderFilter q = filter(custCd, from, to, status, keyword, itemCd, dueFrom, dueTo);
        if (q == null) {
            return new OrderList(List.of(), false, null);
        }
        List<OrderRow> rows = mapper.findOrders(q, LIST_LIMIT + 1);
        boolean more = rows.size() > LIST_LIMIT;
        Long dueLines = q.dueFrom() == null && q.dueTo() == null ? null : mapper.countDueLines(q);
        return new OrderList(more ? rows.subList(0, LIST_LIMIT) : rows, more, dueLines);
    }

    /** 그리드(UI 단계 3 ①, 결정 docs/29 #45) — 기존 목록과 같은 조건 + 페이지·정렬·건수(기존 목록의 500건 상한 대신 페이지) */
    @Transactional(readOnly = true)
    public Page grid(String custCd, String from, String to, String status, String keyword, String itemCd, String dueFrom,
            String dueTo, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, OrderSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        OrderFilter q = filter(custCd, from, to, status, keyword, itemCd, dueFrom, dueTo);
        if (q == null) {
            return new Page(List.of(), pq.isWithTotal() ? 0L : null);
        }
        return new Page(mapper.findOrderPage(q, pq), pq.isWithTotal() ? mapper.countOrders(q) : null);
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    /** 목록·그리드 공용 조건 검사 — 없는 거래처·품목 코드면 null(결과 0건) */
    private OrderFilter filter(String custCd, String from, String to, String status, String keyword, String itemCd,
            String dueFrom, String dueTo) {
        FieldErrors e = FieldErrors.create();
        // 수주일·납기 모두 열린 기간 — 한쪽을 비우면 그쪽 끝이 없다(홈 링크 "납기 ≤ 오늘+7", docs/25 4.13)
        LocalDate f = date(from, "from", false, e);
        LocalDate t = date(to, "to", false, e);
        range(f, t, "to", e);
        LocalDate df = date(dueFrom, "dueFrom", false, e);
        LocalDate dt = date(dueTo, "dueTo", false, e);
        range(df, dt, "dueTo", e);
        if (!isBlank(status) && !ORDER_STATUS.contains(status)) {
            e.add("status", "CODE");
        }
        if (!isBlank(keyword) && keyword.strip().length() > 50) {
            e.add("keyword", "TOO_LONG", 50);
        }
        e.throwIfAny();
        Long custId = null;
        if (!isBlank(custCd)) {
            CustRef c = mapper.findCust(custCd.strip().toUpperCase(Locale.ROOT));
            if (c == null) {
                return null;
            }
            custId = c.getCustId();
        }
        Long itemId = null;
        if (!isBlank(itemCd)) {
            ItemOption i = mapper.findItem(itemCd.strip().toUpperCase(Locale.ROOT));
            if (i == null) {
                return null;
            }
            itemId = i.getItemId();
        }
        return new OrderFilter(custId, f, t, isBlank(status) ? null : status, isBlank(keyword) ? null : like(keyword.strip()),
                itemId, df, dt);
    }

    /** 목록 조건 — 목록과 납기 행 수가 같은 조건 조각(SalesMapper orderWhere)을 쓴다 */
    public record OrderFilter(Long custId, LocalDate from, LocalDate to, String status, String keyword, Long itemId, LocalDate dueFrom,
            LocalDate dueTo) {
    }

    private static void range(LocalDate f, LocalDate t, String field, FieldErrors e) {
        if (f != null && t != null) {
            if (f.isAfter(t)) {
                e.add(field, "INVALID_RANGE");
            } else if (ChronoUnit.DAYS.between(f, t) + 1 > MAX_PERIOD_DAYS) {
                e.add(field, "RANGE_TOO_LONG", ChronoUnit.DAYS.between(f, t) + 1, MAX_PERIOD_DAYS);
            }
        }
    }

    @Transactional(readOnly = true)
    public OrderDetail detail(long orderId) {
        OrderRow o = mapper.findOrder(orderId);
        if (o == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return new OrderDetail(o, mapper.findLines(orderId));
    }

    // ------------------------------------------------------------------ 머리

    /** 새 수주 = 머리 + 행(1개 이상). 같은 요청 키 두 번은 DUPLICATE_REQUEST */
    @Transactional
    public long create(OrderForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("SAL_ORDER_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        CustRef cust = cust(in.custCd(), e);
        OrderValue head = header(cust == null ? null : cust.getCustId(), in, e);
        List<LineForm> forms = in.lines() == null ? List.of() : in.lines();
        if (forms.isEmpty()) {
            e.add("lines", "NO_LINES");
        } else if (forms.size() > MAX_LINES) {
            e.add("lines", "RANGE", 1, MAX_LINES);
        }
        e.throwIfAny();
        List<LineValue> lines = new ArrayList<>();
        Map<String, Integer> scales = unitScales();
        Set<Integer> nos = new HashSet<>();
        int next = 10;
        for (int i = 0; i < forms.size(); i++) {
            LineForm lf = forms.get(i);
            if (lf == null) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            LineValue v = line(lf, null, "lines[" + i + "].", scales, e);
            if (v == null) {
                continue;
            }
            int no = v.lineNo() != null ? v.lineNo() : next;
            if (!nos.add(no)) {
                e.add("lines[" + i + "].lineNo", "DUPLICATE");
            }
            next = Math.max(next, (no / 10 + 1) * 10);
            lines.add(new LineValue(no, v.itemId(), v.qty(), v.dueDate(), v.remark()));
        }
        e.throwIfAny();
        long id;
        try {
            if (head.custPoNo() != null && mapper.findOrderIdByPo(head.custId(), head.custPoNo()) != null) {
                throw fieldError("custPoNo", "DUPLICATE");
            }
            String no = numbering.nextOrDefault(RULE, "수주 번호", "SO{yyMM}-{seq:4}", "MONTH");
            id = mapper.insertOrder(no, head, me.loginId());
            for (LineValue l : lines) {
                mapper.insertLine(id, l, me.loginId());
            }
        } catch (DuplicateKeyException ex) {
            // 같은 발주 번호를 동시에 넣은 경우(부분 유일 인덱스)
            throw fieldError("custPoNo", "DUPLICATE");
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
        return id;
    }

    /** 머리 변경(고객 발주 번호·수주일·비고) — 열린 수주만 */
    @Transactional
    public void update(long orderId, OrderForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        OrderRow cur = mapper.findOrder(orderId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        FieldErrors e = FieldErrors.create();
        if (!isBlank(in.custCd()) && !cur.getCustCd().equals(in.custCd().strip().toUpperCase(Locale.ROOT))) {
            e.add("custCd", "FIXED");
        }
        OrderValue head = header(cur.getCustId(), in, e);
        e.throwIfAny();
        if (!"OPEN".equals(cur.getStatus())) {
            throw fieldError("status", "CLOSED");
        }
        try {
            if (head.custPoNo() != null && !head.custPoNo().equals(cur.getCustPoNo())) {
                Long other = mapper.findOrderIdByPo(cur.getCustId(), head.custPoNo());
                if (other != null && other != orderId) {
                    throw fieldError("custPoNo", "DUPLICATE");
                }
            }
            if (mapper.updateOrder(orderId, head, in.rowVer(), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DuplicateKeyException ex) {
            throw fieldError("custPoNo", "DUPLICATE");
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /** 수주 취소 = 열린 행 모두 취소(사유 필수). 이미 끝난 행(마감)은 그대로 — 그래서 머리는 CLOSED 가 될 수도 있다 */
    @Transactional
    public void cancel(long orderId, CloseForm in) {
        LoginUser me = me();
        String reason = reason(in);
        if (mapper.findOrder(orderId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        lockOpen(orderId);
        OrderRow cur = mapper.findOrder(orderId);
        if (in.rowVer() == null || in.rowVer() != cur.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (mapper.countShippedOpenLines(orderId) > 0) {
            throw fieldError("status", "SHIPPED");
        }
        mapper.cancelOpenLines(orderId, reason, me.loginId());
        mapper.refreshOrderStatus(orderId, me.loginId());
    }

    // ------------------------------------------------------------------ 행

    @Transactional
    public long addLine(long orderId, LineForm in) {
        LoginUser me = me();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (mapper.findOrder(orderId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        FieldErrors e = FieldErrors.create();
        LineValue v = line(in, null, "", unitScales(), e);
        e.throwIfAny();
        lockOpen(orderId);
        int no = v.lineNo() != null ? v.lineNo() : mapper.nextLineNo(orderId);
        if (no > 99999) {
            throw fieldError("lineNo", "RANGE");
        }
        try {
            return mapper.insertLine(orderId, new LineValue(no, v.itemId(), v.qty(), v.dueDate(), v.remark()), me.loginId());
        } catch (DuplicateKeyException ex) {
            throw fieldError("lineNo", "DUPLICATE");
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /** 열린 행의 수량·납기·비고. 품목은 고정(바꾸려면 취소 + 새 행) */
    @Transactional
    public void updateLine(long lineId, LineForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        LineRow cur = mapper.findLine(lineId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        FieldErrors e = FieldErrors.create();
        LineValue v = line(in, cur, "", unitScales(), e);
        e.throwIfAny();
        lockOpen(cur.getOrderId());
        LineRow locked = mapper.findLine(lineId);
        if (!"OPEN".equals(locked.getStatus())) {
            throw fieldError("status", "CLOSED");
        }
        // 출하된 수량 아래로는 못 줄인다(docs/25 4.22)
        if (v.qty().compareTo(new java.math.BigDecimal(locked.getShippedQty())) < 0) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("qty", "BELOW_SHIPPED", locked.getShippedQty()).list());
        }
        try {
            if (mapper.updateLine(lineId, v, in.rowVer(), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /** 행 마감(DONE) — 2단계에서는 사람이 닫는다(3단계 이후 입고·출하가 닫는 자리) */
    @Transactional
    public void closeLine(long lineId, CloseForm in) {
        finish(lineId, in, "DONE", null);
    }

    @Transactional
    public void cancelLine(long lineId, CloseForm in) {
        finish(lineId, in, "CANCELLED", reason(in));
    }

    private void finish(long lineId, CloseForm in, String status, String reason) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        LineRow cur = mapper.findLine(lineId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        lockOpen(cur.getOrderId());
        LineRow now = mapper.findLine(lineId);
        if (!"OPEN".equals(now.getStatus())) {
            throw fieldError("status", "CLOSED");
        }
        // 출하가 있는 행은 취소 못 한다(출하 취소가 먼저). 사람 마감(부분 출하 뒤 짧게 닫기)은 된다
        if ("CANCELLED".equals(status) && new java.math.BigDecimal(now.getShippedQty()).signum() > 0) {
            throw fieldError("status", "SHIPPED");
        }
        if (mapper.closeLine(lineId, status, reason, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        mapper.refreshOrderStatus(cur.getOrderId(), me.loginId());
    }

    // ------------------------------------------------------------------ 엑셀 올리기가 쓰는 것

    /** 올리기 실행: (거래처, 발주) 수주가 없으면 만들고 id. 있으면 그 id(열린 수주만) */
    long orderForImport(long custId, String custPoNo, LocalDate orderDate, String by) {
        Long id = mapper.findOrderIdByPo(custId, custPoNo);
        if (id != null) {
            return id;
        }
        String no = numbering.nextOrDefault(RULE, "수주 번호", "SO{yyMM}-{seq:4}", "MONTH");
        return mapper.insertOrder(no, new OrderValue(custId, custPoNo, orderDate == null ? clock.today() : orderDate, null), by);
    }

    /** 올리기 실행: 머리 잠금 확인(열린 수주) — 끝난 수주에 행을 붙이려 하면 BusinessException */
    void lockOpen(long orderId) {
        String st = mapper.lockOrder(orderId);
        if (st == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"OPEN".equals(st)) {
            throw fieldError("status", "CLOSED");
        }
    }

    // ------------------------------------------------------------------ 검증

    private OrderValue header(Long custId, OrderForm in, FieldErrors e) {
        String po = null;
        if (!isBlank(in.custPoNo())) {
            po = in.custPoNo().strip();
            if (po.length() > 50) {
                e.add("custPoNo", "TOO_LONG", 50);
            } else if (!PO_NO.matcher(po).matches()) {
                e.add("custPoNo", "FORMAT");
            }
        }
        LocalDate d = isBlank(in.orderDate()) ? clock.today() : date(in.orderDate(), "orderDate", true, e);
        String remark = text(in.remark(), "remark", 1000, e);
        return new OrderValue(custId, po, d, remark);
    }

    /** 판매 거래처: 없음 NO_SUCH_CODE, 사용 중지 INACTIVE, 구매 거래처 NOT_SALES */
    CustRef cust(String custCd, FieldErrors e) {
        if (isBlank(custCd)) {
            e.add("custCd", "REQUIRED");
            return null;
        }
        String cd = custCd.strip().toUpperCase(Locale.ROOT);
        CustRef c = cd.length() > 30 ? null : mapper.findCust(cd);
        if (c == null) {
            e.add("custCd", "NO_SUCH_CODE");
            return null;
        }
        if (!"Y".equals(c.getUseYn())) {
            e.add("custCd", "INACTIVE");
            return null;
        }
        if (!"SALES".equals(c.getCustType()) && !"BOTH".equals(c.getCustType())) {
            e.add("custCd", "NOT_SALES");
            return null;
        }
        return c;
    }

    /** 행 검증. cur 가 있으면 변경(품목 고정 — 다른 품목 코드면 FIXED) */
    LineValue line(LineForm in, LineRow cur, String prefix, Map<String, Integer> scales, FieldErrors e) {
        int before = e.list().size();
        Integer no = in.lineNo();
        if (cur != null) {
            if (no != null && no != cur.getLineNo()) {
                e.add(prefix + "lineNo", "FIXED");
            }
            no = cur.getLineNo();
        } else if (no != null && (no < 1 || no > 99999)) {
            e.add(prefix + "lineNo", "RANGE", 1, 99999);
        }
        ItemOption item = null;
        if (cur != null) {
            if (!isBlank(in.itemCd()) && !cur.getItemCd().equals(in.itemCd().strip().toUpperCase(Locale.ROOT))) {
                e.add(prefix + "itemCd", "FIXED");
            }
        } else {
            item = item(in.itemCd(), prefix + "itemCd", e);
        }
        String unit = cur != null ? cur.getUnitCd() : item == null ? null : item.getUnitCd();
        BigDecimal qty = qty(in.qty(), prefix + "qty", scaleOf(scales, unit), true, e);
        if (qty == null && isBlank(in.qty())) {
            e.add(prefix + "qty", "REQUIRED");
        }
        LocalDate due = date(in.dueDate(), prefix + "dueDate", true, e);
        String remark = text(in.remark(), prefix + "remark", 1000, e);
        if (e.list().size() > before) {
            return null;
        }
        return new LineValue(no, cur != null ? cur.getItemId() : item.getItemId(), qty, due, remark);
    }

    public ItemOption item(String itemCd, String field, FieldErrors e) {
        if (isBlank(itemCd)) {
            e.add(field, "REQUIRED");
            return null;
        }
        String cd = itemCd.strip().toUpperCase(Locale.ROOT);
        ItemOption i = cd.length() > 40 ? null : mapper.findItem(cd);
        if (i == null) {
            e.add(field, "NO_SUCH_CODE");
            return null;
        }
        if (!"Y".equals(i.getUseYn())) {
            e.add(field, "INACTIVE");
            return null;
        }
        return i;
    }

    /** 단위별 자릿수(공통코드 UNIT 의 attrs.scale, 없으면 6) — 요청·올리기 세션마다 한 번 읽는다. 넘치면 반올림하지 않고 SCALE */
    public Map<String, Integer> unitScales() {
        Map<String, Integer> m = new HashMap<>();
        codes.codes("UNIT", Locale.KOREAN).forEach(c -> {
            Object v = c.attrs().get("scale");
            if (v instanceof Number n) {
                m.put(c.code(), Math.max(0, Math.min(n.intValue(), QTY_MAX_SCALE)));
            }
        });
        return m;
    }

    public static int scaleOf(Map<String, Integer> scales, String unitCd) {
        return unitCd == null ? QTY_MAX_SCALE : scales.getOrDefault(unitCd, QTY_MAX_SCALE);
    }

    /** 10진 글자 → BigDecimal (쉼표·지수 거부, 반올림 없음). positive = 0 초과, 아니면 0 이상 */
    public static BigDecimal qty(String raw, String field, int scale, boolean positive, FieldErrors e) {
        if (isBlank(raw)) {
            return null;
        }
        String s = raw.strip();
        if (s.length() > 40 || !DECIMAL.matcher(s).matches()) {
            e.add(field, "FORMAT");
            return null;
        }
        BigDecimal v = new BigDecimal(s).stripTrailingZeros();
        if (positive ? v.signum() <= 0 : v.signum() < 0) {
            e.add(field, positive ? "POSITIVE" : "NOT_NEGATIVE");
            return null;
        }
        if (v.scale() > scale) {
            e.add(field, "SCALE", scale);
            return null;
        }
        if (v.precision() - v.scale() > QTY_INT_DIGITS) {
            e.add(field, "INT_DIGITS", QTY_INT_DIGITS);
            return null;
        }
        return v.scale() < 0 ? v.setScale(0) : v;
    }

    public static LocalDate date(String v, String field, boolean required, FieldErrors e) {
        if (isBlank(v)) {
            if (required) {
                e.add(field, "REQUIRED");
            }
            return null;
        }
        try {
            LocalDate d = LocalDate.parse(v.strip());
            if (d.getYear() < 2000 || d.getYear() > 2999) {
                e.add(field, "FORMAT");
                return null;
            }
            return d;
        } catch (DateTimeParseException ex) {
            e.add(field, "FORMAT");
            return null;
        }
    }

    /**
     * 글 칸 정리·검사 — 보이지 않는 글자뿐이면 null(필수 칸은 호출한 쪽이 REQUIRED), 가운데 제어 문자 FORMAT, 글자 수(코드 포인트) 넘으면 TOO_LONG.
     * 판정은 플랫폼 {@link io.smarthappy.platform.common.text.VisibleText#optional} 하나(QA R4-a 2단계 — U+200B·NBSP 만으로 사유가 통과하지 않게).
     * MES·영업 밖(APS·PLM)도 이 입구를 불러 이름을 남긴다 — 그 모듈 2단계에서 VisibleText 로 바꾸면 지운다
     */
    public static String text(String v, String field, int max, FieldErrors e) {
        return io.smarthappy.platform.common.text.VisibleText.optional(v, field, max, e);
    }

    private static String reason(CloseForm in) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String r = text(in.reason(), "reason", 500, e);
        if (r == null) {
            e.add("reason", "REQUIRED");
        }
        e.throwIfAny();
        return r;
    }

    static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "fk_sal_order_cust_comp", "fk_sal_order_cust" -> fieldError("custCd", "INACTIVE");
            case "fk_sal_order_line_item_comp", "fk_sal_order_line_item" -> fieldError("itemCd", "INACTIVE");
            case "ck_sal_order_closed", "ck_sal_order_line_closed" -> fieldError("status", "CLOSED");
            case "ck_sal_order_line_cancel_shipped" -> fieldError("status", "SHIPPED");
            case "ck_sal_order_line_qty_shipped" -> fieldError("qty", "BELOW_SHIPPED");
            default -> e;
        };
    }

    public static BusinessException fieldError(String field, String code) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code).list());
    }

    static String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
