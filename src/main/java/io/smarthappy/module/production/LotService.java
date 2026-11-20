package io.smarthappy.module.production;

import static io.smarthappy.platform.common.text.VisibleText.isBlank;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.workforce.ShiftService;
import io.smarthappy.module.plm.link.ChangeMarker;
import io.smarthappy.module.production.LotDtos.EdgeRow;
import io.smarthappy.module.production.LotDtos.HoldForm;
import io.smarthappy.module.production.LotDtos.HoldReleaseForm;
import io.smarthappy.module.production.LotHoldMapper.HoldLot;
import io.smarthappy.module.production.LotHoldMapper.HoldRow;
import io.smarthappy.module.production.LotDtos.LockedLot;
import io.smarthappy.module.production.LotDtos.LotDetail;
import io.smarthappy.module.production.LotDtos.LotList;
import io.smarthappy.module.production.LotDtos.LotPage;
import io.smarthappy.module.production.LotDtos.LotRow;
import io.smarthappy.module.production.LotDtos.LotSort;
import io.smarthappy.module.production.LotDtos.MergeForm;
import io.smarthappy.module.production.LotDtos.MergePart;
import io.smarthappy.module.production.LotDtos.NewEdge;
import io.smarthappy.module.production.LotDtos.NewLot;
import io.smarthappy.module.production.LotDtos.ReceiveForm;
import io.smarthappy.module.production.LotDtos.SplitForm;
import io.smarthappy.module.production.LotDtos.TraceRow;
import io.smarthappy.module.production.LotDtos.TraceView;
import io.smarthappy.module.production.WoDtos.WoRow;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.module.sales.SalesDtos.CustRef;
import io.smarthappy.module.sales.SalesDtos.ItemOption;
import io.smarthappy.module.sales.SalesMapper;
import io.smarthappy.module.wms.link.LotStockLink;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.platform.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/**
 * LOT 추적 (prd-lot) — P2 4단계, docs/25 4.5.
 *
 * - LOT 번호: 정책 LOT_NO_MODE (SEQ = 채번 LOT, ITEM_SEQ = 품목 코드-채번, WO = 생산 LOT 은 지시 번호)
 * - 계보는 원장: 부모 LOT 을 잠그고 잔량 안에서만 내보낸다(트리거도 같은 검사 — 직접 SQL·동시 요청)
 * - 분할·병합·라벨 바꿈·보류는 이 화면, 투입(CONSUME)·생산 LOT·역분개는 5b 실적이 부르는 서비스 메서드
 * - 새 LOT 을 만드는 곳(5b 실적·7a 처리·7b LOT 재작업)은 {@link #mark} 로 설계 변경 표시(3c, docs/25 4.12) — 투입 계보 change_id 는 그 사본
 */
@Service
@RequiredArgsConstructor
public class LotService {

    static final String PROG = "prd-lot";
    static final String RULE = "LOT";
    static final int LIST_LIMIT = 500;
    static final int TRACE_LIMIT = 5000;
    static final int MAX_PERIOD_DAYS = 366;
    /** 수동 보류 사유 코드(docs/68 Q1) */
    static final String HOLD_REASON_GRP = "LOT_HOLD_REASON";
    /** 단말 요청 행위자 — TerminalPrincipal.actor() 모양 {@code t{단말 id}:{단말 코드}} (W-6, docs/25 4.51) */
    static final Pattern TERMINAL_ACTOR = Pattern.compile("^t\\d+:\\S{1,40}$");
    static final int MAX_SPLIT = 100;
    static final Set<String> ORIGINS = Set.of("RECEIVED", "PRODUCED", "SPLIT", "MERGE", "RELABEL", "REWORK", "REPAIR", "UNIT");
    static final Set<String> METHODS = Set.of("KEY", "SCAN", "AUTO", "IMPORT");

    private final LotMapper mapper;
    private final WoMapper woMapper;
    private final SalesMapper salesMapper;
    private final OrderService orders;
    private final NumberingService numbering;
    private final PolicyService policies;
    private final InboxService inbox;
    private final BizClock clock;
    private final ObjectProvider<ChangeMarker> changeMarker;
    private final ObjectProvider<LotStockLink> stockLink;
    private final LotHoldService holds;
    private final LotHoldMapper holdMapper;
    private final io.smarthappy.platform.code.CodeService codes;
    private final io.smarthappy.platform.excel.ExcelExportService excel;
    private final org.springframework.context.MessageSource messages;
    /** 생산 LOT 확장점(link.UnitLotSource) — 없으면 지금 그대로 */
    private final ObjectProvider<io.smarthappy.module.production.link.UnitLotSource> unitLots;

    // ------------------------------------------------------------------ 조회

    /** holdOnly = Y: 보류 중이고 잔량 있는 LOT 만(홈 카드 prd-lot-hold 와 같은 조건). 기간은 열린 기간(docs/25 4.13) */
    @Transactional(readOnly = true)
    public LotList list(String from, String to, String itemCd, String keyword, String origin, String holdOnly) {
        ListCond c = listCond(from, to, itemCd, keyword, origin, holdOnly);
        if (c.noItem()) {
            return new LotList(List.of(), false);
        }
        List<LotRow> rows = mapper.findLots(c.from(), c.to(), clock.zone().getId(), c.itemId(), c.keyword(), c.origin(), null, c.holdOnly(),
                LIST_LIMIT + 1);
        boolean more = rows.size() > LIST_LIMIT;
        return new LotList(more ? rows.subList(0, LIST_LIMIT) : rows, more);
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link LotSort}). 기본 = 목록과 같은 등록 최신 */
    @Transactional(readOnly = true)
    public LotPage grid(String from, String to, String itemCd, String keyword, String origin, String holdOnly,
            Integer page, Integer size, List<String> sort, Boolean total) {
        ListCond c = listCond(from, to, itemCd, keyword, origin, holdOnly);
        PageQuery pq = PageQuery.parse(page, size, sort, total, LotSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("createdAt,desc"));
        if (c.noItem()) {
            return new LotPage(List.of(), pq.isWithTotal() ? 0L : null);
        }
        String zone = clock.zone().getId();
        List<LotRow> rows = mapper.findLotPage(c.from(), c.to(), zone, c.itemId(), c.keyword(), c.origin(), null, c.holdOnly(), pq);
        return new LotPage(rows, pq.isWithTotal()
                ? mapper.countLots(c.from(), c.to(), zone, c.itemId(), c.keyword(), c.origin(), null, c.holdOnly()) : null);
    }

    /** grid 열(화면 LOT 표와 같은 칸) — 상태는 화면이 이름·배지로(GridOptions.format·status) */
    static final java.util.Map<String, String> STATE_KEYS = java.util.Map.of("HELD", "js.prdLot.held", "EMPTY", "js.prdLot.empty",
            "ACTIVE", "js.prdLot.active");

    /** LOT grid 엑셀(docs/25 4.40) — grid 와 같은 조건·정렬·보이는 열, 상태는 화면과 같은 이름 */
    @Transactional(readOnly = true)
    public long export(LotDtos.LotExport req, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        LotDtos.LotExport r = req == null ? new LotDtos.LotExport(null, null, null, null, null, null, null, null) : req;
        ListCond c = listCond(r.from(), r.to(), r.itemCd(), r.keyword(), r.origin(), r.holdOnly());
        List<ColumnSpec> cols = MesExport.named(ColumnSpec.selectExportable(GRID_COLUMNS, r.columns()), java.util.Map.of("state", "stateNm",
                "balance", "balanceNum", "qtyCreated", "qtyCreatedNum"));
        PageQuery pq = PageQuery.sortOnly(r.sort(), LotSort.values(), List.of("createdAt,desc"));
        String zone = clock.zone().getId();
        List<String> keys = new ArrayList<>();
        for (String[] x : new String[][] {{"from", r.from()}, {"to", r.to()}, {"itemCd", r.itemCd()}, {"keyword", r.keyword()},
                {"origin", r.origin()}, {"holdOnly", "Y".equals(r.holdOnly()) ? "Y" : null}}) {
            if (x[1] != null && !x[1].isBlank()) {
                keys.add(x[0]);
            }
        }
        long count = c.noItem() ? 0 : mapper.countLots(c.from(), c.to(), zone, c.itemId(), c.keyword(), c.origin(), null, c.holdOnly());
        return excel.export(response, new io.smarthappy.platform.excel.ExcelExportService.ExportJob(PROG, "prog.prdLot", cols, count, keys),
                w -> {
                    if (!c.noItem()) {
                        mapper.findLotPage(c.from(), c.to(), zone, c.itemId(), c.keyword(), c.origin(), null, c.holdOnly(), pq).forEach(l -> {
                            l.setStateNm(MesExport.name(messages, STATE_KEYS, l.getState()));
                            w.accept(l);
                        });
                    }
                });
    }

    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("lotNo", "prdLot.col.lotNo", 140).withSort(),
            ColumnSpec.text("itemCd", "prdLot.col.itemCd", 120).withSort(),
            ColumnSpec.text("itemNm", "prdWo.col.itemNm", 160).withFlex().withPriority(3),
            ColumnSpec.text("state", "prdLot.col.state", 80),   // 상세가 열려 목록이 좁아도 보이게 앞쪽
            ColumnSpec.dec("balance", "prdLot.col.balanceOnly", 6, 90).withMinScale(0),
            ColumnSpec.dec("qtyCreated", "prdLot.col.qtyCreated", 6, 90).withMinScale(0).withPriority(3),
            ColumnSpec.text("unitCd", "prdWo.col.unit", 60).withPriority(3),
            ColumnSpec.code("origin", "prdLot.col.origin", "LOT_ORIGIN", 90).withSort(),
            ColumnSpec.text("source", "prdLot.col.source", 140).withPriority(3),
            ColumnSpec.of("createdAt", "prdLot.col.createdAt", ColumnType.DATETIME, 130).withSort().withPriority(3));

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    /** 목록·grid 공통 조건 검사 — 품목 코드가 없는 품목이면 noItem(빈 결과) */
    private ListCond listCond(String from, String to, String itemCd, String keyword, String origin, String holdOnly) {
        FieldErrors e = FieldErrors.create();
        LocalDate f = OrderService.date(from, "from", false, e);
        LocalDate t = OrderService.date(to, "to", false, e);
        if (!isBlank(holdOnly) && !"Y".equals(holdOnly) && !"N".equals(holdOnly)) {
            e.add("holdOnly", "CODE");
        }
        if (f != null && t != null) {
            if (f.isAfter(t)) {
                e.add("to", "INVALID_RANGE");
            } else if (ChronoUnit.DAYS.between(f, t) + 1 > MAX_PERIOD_DAYS) {
                e.add("to", "RANGE_TOO_LONG", ChronoUnit.DAYS.between(f, t) + 1, MAX_PERIOD_DAYS);
            }
        }
        if (!isBlank(origin) && !ORIGINS.contains(origin)) {
            e.add("origin", "CODE");
        }
        if (!isBlank(keyword) && keyword.strip().length() > 60) {
            e.add("keyword", "TOO_LONG", 60);
        }
        e.throwIfAny();
        Long itemId = null;
        if (!isBlank(itemCd)) {
            ItemOption i = salesMapper.findItem(itemCd.strip().toUpperCase(Locale.ROOT));
            if (i == null) {
                return new ListCond(f, t, null, null, null, false, true);
            }
            itemId = i.getItemId();
        }
        return new ListCond(f, t, itemId, isBlank(keyword) ? null : like(keyword.strip()), isBlank(origin) ? null : origin, "Y".equals(holdOnly), false);
    }

    private record ListCond(LocalDate from, LocalDate to, Long itemId, String keyword, String origin, boolean holdOnly, boolean noItem) {
    }

    @Transactional(readOnly = true)
    public LotDetail detail(long lotId) {
        LotRow l = mapper.findLot(lotId);
        if (l == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return new LotDetail(l, mapper.parentsOf(lotId), mapper.childrenOf(lotId), holdMapper.liveHolds(List.of(lotId)));
    }

    /** LOT 번호로 찾기(라벨 스캔·입력) — 없으면 404 */
    @Transactional(readOnly = true)
    public LotRow byNo(String lotNo) {
        LotRow l = isBlank(lotNo) || lotNo.strip().length() > 60 ? null : mapper.findLotByNo(lotNo.strip());
        if (l == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return l;
    }

    /** 추적 — BACK(어디서 왔나)·FWD(어디로 갔나). 깊이 상한 = 정책 LOT_TRACE_DEPTH, 행 상한 5,000 */
    @Transactional(readOnly = true)
    public TraceView trace(long lotId, String dir) {
        LotRow root = mapper.findLot(lotId);
        if (root == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        boolean back = isBlank(dir) || "BACK".equals(dir);
        if (!back && !"FWD".equals(dir)) {
            throw OrderService.fieldError("dir", "CODE");
        }
        int depth = policies.intValue("LOT_TRACE_DEPTH");
        List<TraceRow> rows = mapper.trace(lotId, back, depth, TRACE_LIMIT + 1);
        boolean truncated = rows.size() > TRACE_LIMIT;
        return new TraceView(root, back ? "BACK" : "FWD", truncated ? rows.subList(0, TRACE_LIMIT) : rows, truncated, depth);
    }

    // ------------------------------------------------------------------ 자재 LOT 간이 등록 (D2)

    /**
     * 자재 LOT 등록. 기록자 = 로그인 사용자, 또는 단말 요청이면 단말 행위자(W-6 — WMS PDA 입고가 MaterialReceiptService 를 거쳐 부른다).
     * 배지 작업자(실제 작업자)는 부르는 쪽 원장(WMS 입고·재고 원장 performed_by)에 남는다 — lot_master 에는 작업자 칸이 없다(docs/25 4.51)
     */
    @Transactional
    public long receive(ReceiveForm in) {
        LoginUser me = receiver();
        inbox.claimRequestKey("PRD_LOT_RECEIVE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        ItemOption item = orders.item(in.itemCd(), "itemCd", e);
        BigDecimal qty = OrderService.qty(in.qty(), "qty", item == null ? 6 : OrderService.scaleOf(orders.unitScales(),
                item.getUnitCd()), true, e);
        if (qty == null && isBlank(in.qty())) {
            e.add("qty", "REQUIRED");
        }
        Long supplierId = null;
        if (!isBlank(in.supplierCd())) {
            CustRef c = salesMapper.findCust(in.supplierCd().strip().toUpperCase(Locale.ROOT));
            if (c == null) {
                e.add("supplierCd", "NO_SUCH_CODE");
            } else if (!"Y".equals(c.getUseYn())) {
                e.add("supplierCd", "INACTIVE");
            } else if (!"PURCHASE".equals(c.getCustType()) && !"BOTH".equals(c.getCustType())) {
                e.add("supplierCd", "NOT_SUPPLIER_LOT");
            } else {
                supplierId = c.getCustId();
            }
        }
        String supLot = null;
        if (!isBlank(in.supplierLotNo())) {
            supLot = in.supplierLotNo().strip();
            if (supLot.length() > 60) {
                e.add("supplierLotNo", "TOO_LONG", 60);
            } else if (!OrderService.PO_NO.matcher(supLot).matches()) {
                e.add("supplierLotNo", "FORMAT");
            }
        }
        Instant at = occurredAt(in.occurredAt(), e);
        String remark = OrderService.text(in.remark(), "remark", 1000, e);
        e.throwIfAny();
        // 같은 공급사 LOT 을 다시 받아도 새 내부 LOT(결정 #43, V135) — 공급사 LOT 은 속성, 유일하지 않다.
        // 여기서 남는 유일 위반은 LOT 번호(uq_lot_master_no)뿐이고 채번 결함이라 칸 오류로 바꾸지 않는다(500)
        try {
            return mapper.insertLot(new NewLot(lotNo(item.getItemCd(), null), item.getItemId(), "RECEIVED", qty, item.getUnitCd(),
                    null, supplierId, supLot, at, remark), me.loginId());
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    // ------------------------------------------------------------------ 분할·병합·라벨 바꿈

    /** 분할: 한 LOT → 자식 n 개(수량 합 ≤ 잔량). 자식 LOT 번호를 돌려준다 */
    @Transactional
    public List<String> split(long lotId, SplitForm in) {
        LoginUser me = me();
        if (in == null || in.qtys() == null || in.qtys().isEmpty()) {
            throw OrderService.fieldError("qtys", "REQUIRED");
        }
        if (in.qtys().size() > MAX_SPLIT) {
            throw OrderService.fieldError("qtys", "RANGE");
        }
        LockedLot p = lockUsable(lotId, "lotId");
        FieldErrors e = FieldErrors.create();
        int scale = OrderService.scaleOf(orders.unitScales(), p.getUnitCd());
        List<BigDecimal> qtys = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < in.qtys().size(); i++) {
            BigDecimal q = OrderService.qty(in.qtys().get(i), "qtys[" + i + "]", scale, true, e);
            if (q == null && isBlank(in.qtys().get(i))) {
                e.add("qtys[" + i + "]", "REQUIRED");
            }
            if (q != null) {
                qtys.add(q);
                sum = sum.add(q);
            }
        }
        String reason = OrderService.text(in.reason(), "reason", 500, e);
        Instant at = occurredAt(in.occurredAt(), e);
        e.throwIfAny();
        if (sum.compareTo(p.getBalance()) > 0) {
            throw OrderService.fieldError("qtys", "OVER_BALANCE");
        }
        List<String> out = new ArrayList<>();
        for (BigDecimal q : qtys) {
            String no = lotNo(p.getItemCd(), null);
            long child = mapper.insertLot(new NewLot(no, p.getItemId(), "SPLIT", q, p.getUnitCd(), null, null, null, at, null),
                    me.loginId());
            edge(new NewEdge(p.getLotId(), child, "SPLIT", q, p.getUnitCd(), null, null, null, reason, at, "KEY", null, null, null),
                    me.loginId());
            out.add(no);
        }
        return out;
    }

    /** 병합: 같은 품목 LOT 여러 개 → 새 LOT(각 부모의 지정량 — 비우면 잔량 전부). 새 LOT id */
    @Transactional
    public long merge(MergeForm in) {
        LoginUser me = me();
        if (in == null || in.parts() == null || in.parts().size() < 2) {
            throw OrderService.fieldError("parts", "MERGE_MIN");
        }
        if (in.parts().size() > MAX_SPLIT) {
            throw OrderService.fieldError("parts", "RANGE");
        }
        FieldErrors e = FieldErrors.create();
        Set<Long> seen = new HashSet<>();
        // 잠금 순서를 id 순으로 — 두 병합이 같은 LOT 들을 반대 순서로 잡아 교착되지 않게
        List<MergePart> parts = new ArrayList<>(in.parts());
        parts.sort((a, b) -> Long.compare(a.lotId() == null ? 0 : a.lotId(), b.lotId() == null ? 0 : b.lotId()));
        List<LockedLot> lots = new ArrayList<>();
        List<BigDecimal> qtys = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            MergePart part = parts.get(i);
            if (part == null || part.lotId() == null || !seen.add(part.lotId())) {
                throw OrderService.fieldError("parts", "DUPLICATE");
            }
            LockedLot l = lockUsable(part.lotId(), "parts");
            if (!lots.isEmpty() && lots.get(0).getItemId() != l.getItemId()) {
                throw OrderService.fieldError("parts", "SAME_ITEM_LOT");
            }
            BigDecimal q = isBlank(part.qty()) ? l.getBalance()
                    : OrderService.qty(part.qty(), "parts[" + i + "].qty", OrderService.scaleOf(orders.unitScales(), l.getUnitCd()),
                            true, e);
            if (q != null && q.compareTo(l.getBalance()) > 0) {
                e.add("parts[" + i + "].qty", "OVER_BALANCE");
            }
            if (q != null && q.signum() <= 0) {
                e.add("parts[" + i + "].qty", "EMPTY_LOT");
            }
            lots.add(l);
            qtys.add(q);
        }
        String reason = OrderService.text(in.reason(), "reason", 500, e);
        Instant at = occurredAt(in.occurredAt(), e);
        e.throwIfAny();
        BigDecimal total = qtys.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        LockedLot first = lots.get(0);
        long child = mapper.insertLot(new NewLot(lotNo(first.getItemCd(), null), first.getItemId(), "MERGE", total,
                first.getUnitCd(), null, null, null, at, null), me.loginId());
        for (int i = 0; i < lots.size(); i++) {
            edge(new NewEdge(lots.get(i).getLotId(), child, "MERGE", qtys.get(i), lots.get(i).getUnitCd(), null, null, null,
                    reason, at, "KEY", null, null, null), me.loginId());
        }
        return child;
    }

    /** 라벨 바꿈: 잔량 전부를 새 번호 LOT 으로(라벨 훼손·재포장). 새 LOT id */
    @Transactional
    public long relabel(long lotId, LotDtos.ReasonForm in) {
        LoginUser me = me();
        FieldErrors e = FieldErrors.create();
        String reason = OrderService.text(in == null ? null : in.reason(), "reason", 500, e);
        if (reason == null) {
            e.add("reason", "REQUIRED");
        }
        e.throwIfAny();
        LockedLot p = lockUsable(lotId, "lotId");
        Instant at = clock.instant();
        long child = mapper.insertLot(new NewLot(lotNo(p.getItemCd(), null), p.getItemId(), "RELABEL", p.getBalance(),
                p.getUnitCd(), null, null, null, at, null), me.loginId());
        edge(new NewEdge(p.getLotId(), child, "RELABEL", p.getBalance(), p.getUnitCd(), null, null, null, reason, at, "KEY", null,
                null, null), me.loginId());
        return child;
    }

    /**
     * LOT 단위 재작업·수리(7b, docs/25 4.10, D18): 부모 LOT(보류여도 됨)의 수량 → 새 LOT(출처 = 종류, 보류 아님) + 계보 같은 유형.
     * 보류의 정상 출구 — 분할·투입은 여전히 보류 LOT 을 못 쓴다(트리거). 새 LOT id
     */
    @Transactional
    public long rework(long lotId, LotDtos.ReworkLotForm in) {
        LoginUser me = me();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        LockedLot p = mapper.lockLot(lotId);
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        FieldErrors e = FieldErrors.create();
        String kind = isBlank(in.kind()) ? null : in.kind().strip().toUpperCase(java.util.Locale.ROOT);
        if (!"REWORK".equals(kind) && !"REPAIR".equals(kind)) {
            e.add("kind", "CODE");
        }
        BigDecimal qty = OrderService.qty(in.qty(), "qty", OrderService.scaleOf(orders.unitScales(), p.getUnitCd()), true, e);
        String reason = OrderService.text(in.reason(), "reason", 500, e);
        if (reason == null) {
            e.add("reason", "REQUIRED");
        }
        Instant at = occurredAt(in.occurredAt(), e);
        e.throwIfAny();
        if (p.getBalance().signum() <= 0) {
            throw OrderService.fieldError("lotId", "EMPTY_LOT");
        }
        BigDecimal q = qty == null ? p.getBalance() : qty;
        if (q.compareTo(p.getBalance()) > 0) {
            throw OrderService.fieldError("qty", "OVER_BALANCE");
        }
        long child = mapper.insertLot(new NewLot(lotNo(p.getItemCd(), null), p.getItemId(), kind, q, p.getUnitCd(), null, null, null, at,
                null), me.loginId());
        // 부모 LOT 을 먼저 잠갔다(위 lockLot) → 표시(ECO 행) 순서 — 잠금 순서 MES → ECO
        Long changeId = mark(child, null, null, at);
        edge(new NewEdge(p.getLotId(), child, kind, q, p.getUnitCd(), null, null, null, reason, at, "KEY", null, null, changeId),
                me.loginId());
        return child;
    }

    /**
     * 수동 보류·해제(사유 필수, docs/25 4.19) — 보류 = MANUAL 근거 추가, 해제 = MANUAL 근거 해제. 다른 근거(정정·QMS)가 살아 있으면
     * 해제해도 LOT 은 보류 그대로. 보류 LOT 은 분할·병합·투입의 부모가 될 수 없다(재작업·수리는 된다 — 7b)
     */
    @Transactional
    public void hold(long lotId, HoldForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null || (!"Y".equals(in.holdYn()) && !"N".equals(in.holdYn()))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        boolean on = "Y".equals(in.holdYn());
        // 보류 = 사유 코드(LOT_HOLD_REASON) + 메모(docs/68 Q1). 코드가 있으면 메모는 선택(비면 코드 이름), 없으면 글자 필수(미분류 — 옛 API)
        String code = on && !isBlank(in.reasonCd()) ? in.reasonCd().strip() : null;
        String reason;
        if (code != null) {
            FieldErrors e = FieldErrors.create();
            if (!codes.usable(HOLD_REASON_GRP, code)) {
                e.add("reasonCd", "CODE");
            }
            String memo = OrderService.text(in.reason(), "reason", 500, e);
            e.throwIfAny();
            reason = memo != null ? memo : codes.names(HOLD_REASON_GRP, org.springframework.context.i18n.LocaleContextHolder.getLocale()).getOrDefault(code, code);
        } else {
            reason = holdReason(in.reason());
        }
        HoldLot cur = lockForHold(lotId, in.rowVer());
        boolean done = on
                ? holds.addLocked(cur.getLotId(), LotHoldService.MANUAL, null, code, reason, me.loginId())
                : holds.releaseLocked(cur.getLotId(), LotHoldService.MANUAL, null, reason, me.loginId());
        if (!done) {
            throw OrderService.fieldError("holdYn", "STATE");
        }
    }

    /** 근거 하나 해제(사유 필수) — 화면은 MANUAL·MES_CORRECT 만, QMS 근거는 QMS 처분으로만(D34) */
    @Transactional
    public void releaseHold(long lotId, long holdId, HoldReleaseForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String reason = holdReason(in.reason());
        HoldLot cur = lockForHold(lotId, in.rowVer());
        HoldRow h = holdMapper.findHold(holdId);
        if (h == null || h.getLotId() != cur.getLotId()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!LotHoldService.SCREEN_SOURCES.contains(h.getSource())) {
            throw OrderService.fieldError("holdId", "HOLD_SOURCE");
        }
        if (h.getReleasedAt() != null || !holds.releaseLocked(cur.getLotId(), h.getSource(), h.getRefId(), reason, me.loginId())) {
            throw OrderService.fieldError("holdId", "STATE");
        }
    }

    private static String holdReason(String raw) {
        FieldErrors e = FieldErrors.create();
        String reason = OrderService.text(raw, "reason", 500, e);
        if (reason == null) {
            e.add("reason", "REQUIRED");
        }
        e.throwIfAny();
        return reason;
    }

    /** LOT 잠금 + 화면 낙관적 락(rowVer) */
    private HoldLot lockForHold(long lotId, int rowVer) {
        HoldLot cur = holds.lock(lotId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (cur.getRowVer() != rowVer) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return cur;
    }

    // ------------------------------------------------------------------ 5b 실적이 부르는 것(요청 밖도)

    /** 요청 밖(데모 시더)에서 자재 LOT 등록 — 품목·공급사는 코드로, 검증은 트리거가 마지막 방어 */
    public long receiveAs(String itemCd, BigDecimal qty, String supplierCd, String supplierLotNo, String by) {
        ItemOption item = salesMapper.findItem(itemCd);
        CustRef sup = supplierCd == null ? null : salesMapper.findCust(supplierCd);
        if (item == null || (supplierCd != null && sup == null)) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return mapper.insertLot(new NewLot(lotNo(item.getItemCd(), null), item.getItemId(), "RECEIVED", qty, item.getUnitCd(), null,
                sup == null ? null : sup.getCustId(), supplierLotNo, clock.instant(), null), by);
    }

    /**
     * 생산 LOT — 지시 산출. LOT_NO_MODE = WO 면 지시 번호(두 번째부터 -2 …).
     * 확장점 link.UnitLotSource 가 이 지시의 LOT 을 정하면(시리얼 단위 생산 등, docs/72 2.2) 새 LOT 대신 그것
     */
    public long createProduced(long woId, BigDecimal qty, Instant occurredAt, String by) {
        WoRow w = woMapper.findWo(woId);
        if (w == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        var fixed = io.smarthappy.module.production.link.UnitLotSource.of(unitLots).producedLot(woId, qty, occurredAt, by);
        if (fixed.isPresent()) {
            return fixed.get();
        }
        try {
            return mapper.insertLot(new NewLot(lotNo(w.getItemCd(), w), w.getItemId(), "PRODUCED", qty, w.getUnitCd(), woId, null,
                    null, occurredAt, null), by);
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /**
     * 설계 변경 표시(3c) — 새 LOT 을 만든 트랜잭션 안에서. 표시한 ECO id(투입 계보 change_id 에 적을 값), 없으면 null.
     * 부르기 전에 이 트랜잭션이 잠글 MES 행(지시·공정·부모·자재 LOT)을 모두 잠가 둔다 — 표시가 ECO 행을 잠그므로(MES → ECO)
     */
    public Long mark(long lotId, Long woId, Long equipId, Instant occurredAt) {
        return ChangeMarker.of(changeMarker).mark(lotId, woId, equipId, occurredAt.atOffset(ZoneOffset.UTC)).orElse(null);
    }

    /** 투입 자재 LOT 들을 id 순으로 먼저 잠근다 — 뒤의 표시(ECO 잠금)보다 앞에(잠금 순서 MES → ECO, 동시 투입끼리도 같은 순서) */
    public void lockForConsume(Collection<Long> parentLotIds) {
        parentLotIds.stream().distinct().sorted().forEach(mapper::lockLot);
    }

    /** 투입 — 자재 LOT 에서 생산 LOT 으로(부모 단위 수량). 계보 id */
    public long consume(long parentLotId, long childLotId, BigDecimal qty, Long woId, Long woProcId, Long equipId,
            Long workerId, String entryMethod, Instant occurredAt, String by) {
        return consume(parentLotId, childLotId, qty, woId, woProcId, equipId, workerId, entryMethod, occurredAt, null, by);
    }

    /** 투입 + 설계 변경 표시 사본(자식 LOT 의 {@link #mark} 결과) */
    public long consume(long parentLotId, long childLotId, BigDecimal qty, Long woId, Long woProcId, Long equipId,
            Long workerId, String entryMethod, Instant occurredAt, Long changeId, String by) {
        if (!METHODS.contains(entryMethod)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        LockedLot p = lockUsable(parentLotId, "lotId");
        if (qty.compareTo(p.getBalance()) > 0) {
            throw OrderService.fieldError("qty", "OVER_BALANCE");
        }
        return edge(new NewEdge(parentLotId, childLotId, "CONSUME", qty, p.getUnitCd(), woId, woProcId, equipId, null,
                occurredAt, entryMethod, workerId, null, changeId), by);
    }

    /** 역분개 — 투입(CONSUME)만, 한 행 한 번. 분할·병합은 반대 동작(병합·분할)으로 되돌린다 */
    public long reverse(long genId, String reason, String by) {
        EdgeRow g = mapper.findEdge(genId);
        if (g == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"CONSUME".equals(g.getRelType()) || g.getReversalOfId() != null) {
            throw OrderService.fieldError("genId", "NOT_REVERSIBLE");
        }
        if (g.isReversed()) {
            throw OrderService.fieldError("genId", "STATE");
        }
        FieldErrors e = FieldErrors.create();
        String r = OrderService.text(reason, "reason", 500, e);
        if (r == null) {
            e.add("reason", "REQUIRED");
        }
        e.throwIfAny();
        long rev;
        try {
            rev = mapper.insertEdge(new NewEdge(g.getParentLotId(), g.getChildLotId(), "CONSUME", new BigDecimal(g.getQty()).negate(),
                    g.getUnitCd(), g.getWoId(), g.getWoProcId(), g.getEquipId(), r, clock.instant(), "CORRECT", null, genId, g.getChangeId()), by);
        } catch (DuplicateKeyException ex) {
            throw OrderService.fieldError("genId", "STATE");
        }
        // 재고 원장 반영(WMS B5, docs/30 12.4) — 같은 트랜잭션, 409 는 그대로 위로
        LotStockLink.of(stockLink).onReverse(rev);
        return rev;
    }

    // ------------------------------------------------------------------ 도우미

    private LockedLot lockUsable(long lotId, String field) {
        LockedLot l = mapper.lockLot(lotId);
        if (l == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if ("Y".equals(l.getHoldYn())) {
            throw OrderService.fieldError(field, "LOT_HOLD");
        }
        if (l.getBalance().signum() <= 0) {
            throw OrderService.fieldError(field, "EMPTY_LOT");
        }
        return l;
    }

    /** 계보 행은 모두 여기서(역분개만 reverse) — 만든 직후 재고 원장 반영(WMS B5, docs/30 12.4·docs/25 4.20 ④) */
    private long edge(NewEdge e, String by) {
        long gen;
        try {
            gen = mapper.insertEdge(e, by);
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
        // 같은 트랜잭션 — 재고 부족·마감(409)은 칸 오류 없는 BusinessException 으로 그대로 올라가 업무 전체가 되돌아간다
        LotStockLink.of(stockLink).onEdge(gen);
        return gen;
    }

    /** LOT 번호 — 정책 LOT_NO_MODE. 같은 번호가 이미 있으면(수동 등록 등) 다음 번호로 */
    String lotNo(String itemCd, WoRow wo) {
        String mode = policies.value("LOT_NO_MODE");
        if ("WO".equals(mode) && wo != null) {
            int n = mapper.countWoLots(wo.getWoId());
            String no = n == 0 ? wo.getWoNo() : wo.getWoNo() + "-" + (n + 1);
            while (mapper.lotNoExists(no)) {
                n++;
                no = wo.getWoNo() + "-" + (n + 1);
            }
            return no;
        }
        for (int i = 0; i < 100; i++) {
            String seq = numbering.nextOrDefault(RULE, "LOT 번호", "L{yyMMdd}-{seq:4}", "DAY");
            String no = "ITEM_SEQ".equals(mode) ? itemCd + "-" + seq : seq;
            if (!mapper.lotNoExists(no)) {
                return no;
            }
        }
        throw new BusinessException(HbErrorCode.NUMBERING_EXHAUSTED);
    }

    private Instant occurredAt(String raw, FieldErrors e) {
        if (isBlank(raw)) {
            return clock.instant();
        }
        Instant at = ShiftService.localInstant(raw, clock.zone(), "occurredAt", e);
        if (at != null && at.isAfter(clock.instant().plusSeconds(300))) {
            e.add("occurredAt", "FUTURE");
            return null;
        }
        return at;
    }

    static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "ck_lot_genealogy_balance" -> OrderService.fieldError("qty", "OVER_BALANCE");
            case "ck_lot_genealogy_hold" -> OrderService.fieldError("lotId", "LOT_HOLD");
            case "ck_lot_genealogy_item" -> OrderService.fieldError("parts", "SAME_ITEM_LOT");
            case "fk_lot_master_item_comp" -> OrderService.fieldError("itemCd", "INACTIVE");
            case "fk_lot_master_supplier_comp" -> OrderService.fieldError("supplierCd", "NOT_SUPPLIER_LOT");
            case "fk_lot_master_wo_item" -> OrderService.fieldError("woId", "STATE");
            default -> e;
        };
    }

    private static String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }

    /**
     * 입고 기록자 — 로그인 사용자, 없으면 단말 요청의 행위자({@code t{id}:{코드}}, TerminalAuthFilter 가 연 회사 범위). 그 밖(비로그인·요청 밖 SYSTEM)은 401.
     * 분할·병합 등 다른 LOT 작업은 지금처럼 로그인만(me)
     */
    private static LoginUser receiver() {
        return CurrentUser.get().orElseGet(() -> {
            String actor = TenantContext.currentActor();
            if (actor == null || !TERMINAL_ACTOR.matcher(actor).matches()) {
                throw new BusinessException(HbErrorCode.UNAUTHENTICATED);
            }
            return new LoginUser(0, TenantContext.requireCompCd(), actor.length() > 50 ? actor.substring(0, 50) : actor, actor, "ko", false);
        });
    }
}
