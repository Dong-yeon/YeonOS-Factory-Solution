package io.smarthappy.module.srm.po;

import static io.smarthappy.module.srm.SrmChecks.blank;
import static io.smarthappy.module.srm.SrmChecks.code;
import static io.smarthappy.module.srm.SrmChecks.date;
import static io.smarthappy.module.srm.SrmChecks.decimal;
import static io.smarthappy.module.srm.SrmChecks.fieldError;
import static io.smarthappy.module.srm.SrmChecks.me;
import static io.smarthappy.module.srm.SrmChecks.text;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.srm.SrmChecks;
import io.smarthappy.module.srm.SrmNumbers;
import io.smarthappy.module.srm.po.PoDtos.ActionForm;
import io.smarthappy.module.srm.po.PoDtos.ItemRef;
import io.smarthappy.module.srm.po.PoDtos.PoCrit;
import io.smarthappy.module.srm.po.PoDtos.PoDetail;
import io.smarthappy.module.srm.po.PoDtos.PoForm;
import io.smarthappy.module.srm.po.PoDtos.PoLineForm;
import io.smarthappy.module.srm.po.PoDtos.PoLineRow;
import io.smarthappy.module.srm.po.PoDtos.PoLineValue;
import io.smarthappy.module.srm.po.PoDtos.PoPage;
import io.smarthappy.module.srm.po.PoDtos.PoRow;
import io.smarthappy.module.srm.po.PoDtos.PoSort;
import io.smarthappy.module.srm.po.PoDtos.PoValue;
import io.smarthappy.module.srm.po.PoDtos.PrLineRef;
import io.smarthappy.module.srm.po.PoDtos.SubmitForm;
import io.smarthappy.module.srm.po.PoDtos.SupplierRef;
import io.smarthappy.module.srm.supplier.SourcePriceService;
import io.smarthappy.platform.approval.ApprovalService;
import io.smarthappy.platform.approval.ApprovalService.LineSpec;
import io.smarthappy.platform.approval.ApprovalService.LineType;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.module.srm.notify.PoCancelledMailHandler;
import io.smarthappy.module.srm.notify.PoIssuedMailHandler;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.messaging.OutboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import lombok.RequiredArgsConstructor;

/**
 * 발주 (srm-po) — SRM S3, docs/31 D1·D3·D6.
 * 머리 + 행 한 벌 저장(작성 중만), PR 행 연결(남은 수량 초과 금지 — PR 행 잠금), 단가 기본값 = 발주일 단가표,
 * 결재 SRM_PO(정책·금액 기준) 또는 결재 없는 확정, 발행 = 스냅샷, 발행 뒤 변경 = 개정(rev + 1), 취소·마감.
 * 정책 SRM_PO_SOURCE=ERP 이면 발주를 여기서 만들거나 행을 바꿀 수 없다(D1).
 */
@Service
@RequiredArgsConstructor
public class PoService {

    public static final String PROG = "srm-po";
    public static final String DOC_TYPE = "SRM_PO";
    static final int MAX_LINES = 200;
    static final int SEARCH_MAX_DAYS = 366;
    static final int QTY_INT_DIGITS = 12;
    static final int PRICE_SCALE = 4;
    static final int PRICE_INT_DIGITS = 14;
    static final Set<String> STATUS = Set.of("DRAFT", "IN_APPROVAL", "APPROVED", "ISSUED", "ACKED", "PARTIAL_RECEIVED", "RECEIVED",
            "CLOSED", "CANCELLED");

    private final PoMapper mapper;
    private final UserDirectory users;
    private final SrmNumbers numbers;
    private final SourcePriceService prices;
    private final ApprovalService approvals;
    private final InboxService inbox;
    private final OutboxService outbox;
    private final PolicyService policies;
    private final DateRanges ranges;
    private final BizClock clock;

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public List<PoRow> list(String from, String to, String status, String keyword, String custCd) {
        List<PoRow> rows = mapper.findPos(criteria(from, to, status, keyword, custCd));
        rows.forEach(r -> r.setApprovalRequired(approvalRequired(r.getCurrencyCd(), new BigDecimal(r.getTotalAmt()))));
        return rows;
    }

    /**
     * grid(UI 단계 3 ①, docs/31 5.2) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link PoSort}, 최대 3개, 모르는 열 400).
     * 기간 필수·상한도 목록과 같다(행 수 상한 대신 페이지)
     */
    @Transactional(readOnly = true)
    public PoPage grid(String from, String to, String status, String keyword, String custCd, Integer page, Integer size,
            List<String> sort, Boolean total) {
        PoCrit c = criteria(from, to, status, keyword, custCd);
        PageQuery pq = PageQuery.parse(page, size, sort, total, PoSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("poDate,desc"));
        List<PoRow> rows = mapper.findPoPage(c, pq);
        rows.forEach(r -> r.setApprovalRequired(approvalRequired(r.getCurrencyCd(), new BigDecimal(r.getTotalAmt()))));
        return new PoPage(rows, pq.isWithTotal() ? mapper.countPos(c) : null);
    }

    /** grid 열(화면 목록과 같은 칸 + 개정·통화를 따로) — 정렬 가능 = {@link PoSort} 에 있는 열 */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("poNo", "srm.po.col.poNo", 120).withSort().withPriority(1),
            ColumnSpec.of("rev", "srm.po.col.rev", ColumnType.INT, 50).withPriority(3),
            ColumnSpec.of("poDate", "srm.po.col.poDate", ColumnType.DATE, 100).withSort(),
            ColumnSpec.text("custNm", "srm.po.col.supplier", 140).withSort(),
            ColumnSpec.dec("totalAmt", "srm.po.col.total", 4, 110).withMinScale(0).withSort(),
            ColumnSpec.text("currencyCd", "srm.po.col.currency", 60).withPriority(3),
            ColumnSpec.of("lineCnt", "srm.po.col.lineCnt", ColumnType.INT, 70).withSort().withPriority(3),
            ColumnSpec.code("status", "srm.po.col.status", "PO_STATUS", 100).withSort().withPriority(1));

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    /** 목록·grid 공통 조건 검증 */
    private PoCrit criteria(String from, String to, String status, String keyword, String custCd) {
        FieldErrors e = FieldErrors.create();
        LocalDate f = date(from, "from", false, e);
        LocalDate t = date(to, "to", false, e);
        e.throwIfAny();
        ranges.check(f, t, SEARCH_MAX_DAYS);
        String st = code(status);
        if (st != null && !STATUS.contains(st) || keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String k = blank(keyword) ? null : "%" + SrmChecks.like(keyword.strip()) + "%";
        return new PoCrit(f, t, st, k, code(custCd));
    }

    @Transactional(readOnly = true)
    public PoDetail get(long poId) {
        PoRow po = mapper.findPo(poId);
        if (po == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        po.setApprovalRequired(approvalRequired(po.getCurrencyCd(), new BigDecimal(po.getTotalAmt())));
        if ("IN_APPROVAL".equals(po.getStatus()) && po.getAprRequestId() != null) {
            Map<String, Object> t = mapper.aprTurn(po.getAprRequestId());
            long myId = me().userId();
            if (t != null) {
                po.setMyTurn(t.get("current_id") != null && ((Number) t.get("current_id")).longValue() == myId);
                po.setMyRequest(((Number) t.get("requester_id")).longValue() == myId);
            }
        }
        return new PoDetail(po, mapper.findLines(poId), mapper.findRevs(poId));
    }

    @Transactional(readOnly = true)
    public List<PrLineRef> openPrLines(String itemCd, String keyword) {
        if (keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return mapper.findOpenPrLines(code(itemCd), blank(keyword) ? null : "%" + SrmChecks.like(keyword.strip()) + "%");
    }

    /** 결재가 필요한가 — 정책 Y 이고, 원화가 아니거나(기준 금액은 원화) 합계 ≥ 기준 금액 */
    boolean approvalRequired(String currencyCd, BigDecimal total) {
        if (!policies.bool("SRM_PO_APPROVAL")) {
            return false;
        }
        if (!"KRW".equals(currencyCd)) {
            return true;
        }
        return total.compareTo(BigDecimal.valueOf(policies.intValue("SRM_PO_APPROVAL_MIN_AMT"))) >= 0;
    }

    private void srmOwned() {
        if ("ERP".equals(policies.value("SRM_PO_SOURCE"))) {
            throw new BusinessException(HbErrorCode.SRM_ERP_OWNED);
        }
    }

    // ------------------------------------------------------------------ 저장(한 벌)

    @Transactional
    public long create(PoForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("SRM_PO_CREATE");
        srmOwned();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        SupplierRef sup = null;
        String custCd = code(in.custCd());
        if (custCd == null) {
            e.add("custCd", "REQUIRED");
        } else {
            sup = custCd.length() > 30 ? null : mapper.findSupplier(custCd);
            if (sup == null) {
                e.add("custCd", "NO_SUCH_CODE");
            } else if ("BLOCKED".equals(sup.getTradeStatus())) {
                e.add("custCd", "SUPPLIER_BLOCKED");
            }
        }
        if (!e.isEmpty()) {
            e.throwIfAny();
        }
        LocalDate poDate = clock.today();
        String pay = blank(in.payTermsCd()) ? sup.getPayTermsCd() : in.payTermsCd();
        Header h = header(in, pay, null, e);
        List<PoLineValue> lines = lines(in.lines(), Map.of(), sup.getCustId(), sup.getCurrencyCd(), poDate, null, e);
        e.throwIfAny();
        BigDecimal total = total(lines);
        String poNo = numbers.next(SrmNumbers.PO, me.loginId());
        try {
            long id = mapper.insertPo(poNo, poDate, sup.getCustId(), sup.getCurrencyCd(), me.userId(),
                    new PoValue(h.plantCd, h.whId, h.payTermsCd, h.remark, total, null), me.loginId());
            int no = 1;
            for (PoLineValue l : lines) {
                mapper.insertLine(id, no++, l, me.loginId());
            }
            return id;
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    @Transactional
    public void update(long poId, PoForm in) {
        LoginUser me = me();
        srmOwned();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        PoRow cur = lock(poId);
        if (!"DRAFT".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.SRM_STATE);
        }
        if (cur.getRowVer() != in.rowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        PoRow full = mapper.findPo(poId);
        FieldErrors e = FieldErrors.create();
        String custCd = code(in.custCd());
        if (custCd != null && !custCd.equals(full.getCustCd())) {
            e.add("custCd", "FIXED");
        }
        Map<Long, PoLineRow> existing = new HashMap<>();
        mapper.findLines(poId).forEach(l -> existing.put(l.getPoLineId(), l));
        Header h = header(in, in.payTermsCd(), full, e);
        List<PoLineValue> lines = lines(in.lines(), existing, full.getCustId(), full.getCurrencyCd(), full.getPoDate(), poId, e);
        e.throwIfAny();
        BigDecimal total = total(lines);
        try {
            if (mapper.updatePo(poId, new PoValue(h.plantCd, h.whId, h.payTermsCd, h.remark, total, in.rowVer()),
                    me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
            Set<Long> kept = new HashSet<>();
            lines.forEach(l -> {
                if (l.poLineId() != null) {
                    kept.add(l.poLineId());
                }
            });
            for (Long id : existing.keySet()) {
                if (!kept.contains(id)) {
                    mapper.deleteLine(id);
                }
            }
            int next = mapper.nextLineNo(poId);
            for (PoLineValue l : lines) {
                if (l.poLineId() == null) {
                    mapper.insertLine(poId, next++, l, me.loginId());
                } else {
                    mapper.updateLine(l, me.loginId());
                }
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    private record Header(String plantCd, Long whId, String payTermsCd, String remark) {
    }

    private Header header(PoForm in, String payRaw, PoRow cur, FieldErrors e) {
        String plant = code(in.plantCd());
        if (plant == null) {
            e.add("plantCd", "REQUIRED");
        } else {
            String use = plant.length() > 20 ? null : mapper.plantUseYn(plant);
            boolean same = cur != null && plant.equals(cur.getPlantCd());
            if (use == null) {
                e.add("plantCd", "NO_SUCH_CODE");
            } else if (!same && !"Y".equals(use)) {
                e.add("plantCd", "INACTIVE");
            }
        }
        Long wh = null;
        String whCd = code(in.whCd());
        if (whCd != null && plant != null) {
            boolean same = cur != null && whCd.equals(cur.getWhCd()) && plant.equals(cur.getPlantCd());
            wh = same ? cur.getWhId() : whCd.length() > 30 ? null : mapper.findWh(plant, whCd);
            if (wh == null) {
                e.add("whCd", "NO_SUCH_CODE");
            }
        }
        String pay = code(payRaw);
        if (pay != null && !(cur != null && pay.equals(cur.getPayTermsCd())) && (pay.length() > 30 || !mapper.codeUsable("PAY_TERMS", pay))) {
            e.add("payTermsCd", "NO_SUCH_CODE");
        }
        String remark = text(in.remark(), "remark", 1000, false, e);
        return new Header(plant, wh, pay, remark);
    }

    /**
     * 행 검사 — 행 오류 키 = 보낸 배열 순번. 새 행·품목을 바꾼 행은 사용 중 품목만. 단가를 비우면 발주일 단가표 값(없으면 NO_PRICE).
     * PR 행 연결은 PR 행을 잠그고(번호 순) 남은 수량 = PR 수량 − 다른 살아 있는 발주 행 합 으로 본다(이 발주의 기존 행은 빼고).
     */
    private List<PoLineValue> lines(List<PoLineForm> in, Map<Long, PoLineRow> existing, long custId, String currencyCd,
            LocalDate poDate, Long poId, FieldErrors e) {
        List<PoLineValue> out = new ArrayList<>();
        if (in == null || in.isEmpty()) {
            return out;
        }
        if (in.size() > MAX_LINES) {
            e.add("lines", "TOO_MANY_LINES", MAX_LINES);
            return out;
        }
        boolean prRequired = policies.bool("SRM_PR_REQUIRED");
        int amtScale = currencyScale(currencyCd);
        Set<Long> seen = new HashSet<>();
        Map<String, ItemRef> items = new HashMap<>();
        Map<Long, List<Integer>> byPr = new LinkedHashMap<>();
        List<PoLineValue> staged = new ArrayList<>();
        List<FieldErrors> lineErrors = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            PoLineForm f = in.get(i);
            FieldErrors le = FieldErrors.create();
            lineErrors.add(le);
            staged.add(null);
            if (f == null) {
                le.add("itemCd", "REQUIRED");
                continue;
            }
            PoLineRow old = null;
            if (f.poLineId() != null) {
                old = existing.get(f.poLineId());
                if (old == null || !seen.add(f.poLineId())) {
                    throw new BusinessException(HbErrorCode.INVALID_VALUE);    // 다른 발주의 행 id·같은 id 두 번
                }
            }
            ItemRef item = null;
            String cd = code(f.itemCd());
            if (cd == null) {
                le.add("itemCd", "REQUIRED");
            } else {
                item = items.computeIfAbsent(cd, k -> k.length() > 40 ? null : mapper.findItem(k, custId));
                if (item == null) {
                    le.add("itemCd", "NO_SUCH_CODE");
                } else if (!"Y".equals(item.getUseYn()) && (old == null || old.getItemId() != item.getItemId())) {
                    le.add("itemCd", "INACTIVE");
                }
            }
            boolean sameItem = old != null && item != null && old.getItemId() == item.getItemId();
            String unit = item == null ? null : sameItem ? old.getUnitCd() : item.getUnitCd();
            BigDecimal qty = decimal(f.qty(), "qty", unit == null ? 6 : scaleOf(unit), QTY_INT_DIGITS, true, le);
            BigDecimal price = decimal(f.unitPrice(), "unitPrice", PRICE_SCALE, PRICE_INT_DIGITS, false, le);
            if (price == null && blank(f.unitPrice()) && item != null) {
                price = prices.effectivePrice(custId, item.getItemId(), currencyCd, poDate).orElse(null);
                if (price == null) {
                    le.add("unitPrice", "NO_PRICE");
                }
            }
            LocalDate due = date(f.dueDate(), "dueDate", true, le);
            if (due != null && due.getYear() < 2000) {
                le.add("dueDate", "FORMAT");
            }
            if (f.prLineId() != null) {
                byPr.computeIfAbsent(f.prLineId(), k -> new ArrayList<>()).add(i);
            } else if (prRequired) {
                le.add("prLineId", "REQUIRED");
            }
            String remark = text(f.remark(), "remark", 500, false, le);
            if (le.isEmpty()) {
                BigDecimal amt = qty.multiply(price).setScale(amtScale, RoundingMode.HALF_UP);
                String itemNm = sameItem ? old.getItemNm() : item.getItemNm();
                String supCd = sameItem && old.getSupplierItemCd() != null ? old.getSupplierItemCd() : item.getSupplierItemCd();
                staged.set(i, new PoLineValue(f.poLineId(), item.getItemId(), itemNm, supCd, unit, qty, price, amt, due,
                        f.prLineId(), remark));
            }
        }
        // PR 행: 잠그고 상태·품목·남은 수량(이 발주가 같은 PR 행에 여러 줄이면 합으로)
        if (!byPr.isEmpty()) {
            List<Long> ids = new ArrayList<>(byPr.keySet());
            ids.sort(null);
            Map<Long, PrLineRef> refs = new HashMap<>();
            mapper.lockPrLines(ids);
            mapper.findPrLineRefs(ids, poId).forEach(r -> refs.put(r.getPrLineId(), r));
            for (Map.Entry<Long, List<Integer>> en : byPr.entrySet()) {
                PrLineRef r = refs.get(en.getKey());
                BigDecimal sum = BigDecimal.ZERO;
                for (int i : en.getValue()) {
                    PoLineValue v = staged.get(i);
                    if (r == null) {
                        lineErrors.get(i).add("prLineId", "NO_SUCH_CODE");
                    } else if (!"APPROVED".equals(r.getPrStatus()) || !"OPEN".equals(r.getLineStatus())) {
                        lineErrors.get(i).add("prLineId", "PR_NOT_OPEN");
                    } else if (v != null && (v.itemId() != r.getItemId() || !v.unitCd().equals(r.getUnitCd()))) {
                        lineErrors.get(i).add("prLineId", "PR_ITEM_MISMATCH");
                    } else if (v != null) {
                        sum = sum.add(v.qty());
                    }
                }
                if (r != null && sum.compareTo(new BigDecimal(r.getRemainQty())) > 0) {
                    for (int i : en.getValue()) {
                        lineErrors.get(i).add("qty", "REMAIN_EXCEEDED", r.getRemainQty());
                    }
                }
            }
        }
        for (int i = 0; i < staged.size(); i++) {
            String key = String.valueOf(i);
            FieldErrors le = lineErrors.get(i);
            le.list().forEach(x -> e.row(key).add(x.field(), x.code(), x.args()));
            if (le.isEmpty() && staged.get(i) != null) {
                out.add(staged.get(i));
            }
        }
        return out;
    }

    private static BigDecimal total(List<PoLineValue> lines) {
        return lines.stream().map(PoLineValue::amt).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private int scaleOf(String unitCd) {
        Integer s = mapper.unitScale(unitCd);
        return s == null ? 6 : Math.min(s, 6);
    }

    private int currencyScale(String currencyCd) {
        Integer s = mapper.currencyScale(currencyCd);
        return s == null ? 2 : s;
    }

    // ------------------------------------------------------------------ 상태 전이

    /** 결재 상신(결재가 필요 없어도 상신할 수 있다) — 결재자는 로그인 아이디 순서대로 APPROVE 줄 */
    @Transactional
    public long submit(long poId, SubmitForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        PoRow cur = start(poId, in.rowVer(), "DRAFT");
        PoRow full = readyCheck(poId);
        List<LineSpec> lines = new ArrayList<>();
        FieldErrors e = FieldErrors.create();
        List<String> ids = in.approvers() == null ? List.of() : in.approvers();
        if (ids.isEmpty()) {
            e.add("approvers", "REQUIRED");
        }
        for (String login : ids) {
            UserOption u = users.activeByLogin(login);   // 사용 중 회사 사용자만(공통 사용자 고르기)
            Long uid = u == null ? null : u.getUserId();
            if (uid == null) {
                e.add("approvers", "NO_SUCH_CODE");
            } else {
                lines.add(new LineSpec(uid, LineType.APPROVE));
            }
        }
        e.throwIfAny();
        String title = "발주 " + full.getPoNo() + (full.getRev() > 0 ? " 개정" + full.getRev() : "") + " · " + full.getCustNm()
                + " · " + full.getTotalAmt() + " " + full.getCurrencyCd();
        long aprId = approvals.submit(DOC_TYPE, bizKey(poId, cur.getRev()), title.length() > 200 ? title.substring(0, 200) : title,
                lines);
        if (mapper.changeStatus(poId, "DRAFT", "IN_APPROVAL", in.rowVer(), aprId, null, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return aprId;
    }

    /** 결재 없이 확정 — 결재가 필요 없는 발주만(정책 N 또는 기준 금액 미만), APPROVE 권한 */
    @Transactional
    public void confirm(long poId, ActionForm in) {
        LoginUser me = me();
        PoRow cur = start(poId, rowVer(in), "DRAFT");
        PoRow full = readyCheck(poId);
        if (approvalRequired(full.getCurrencyCd(), new BigDecimal(full.getTotalAmt()))) {
            throw new BusinessException(HbErrorCode.SRM_APPROVAL_REQUIRED);
        }
        change(cur, "APPROVED", in.rowVer(), null, me);
    }

    /** 발행 — 발행본 스냅샷을 남긴다(협력사에게 간 것). 통지·발주서는 S4 */
    @Transactional
    public void issue(long poId, ActionForm in) {
        LoginUser me = me();
        start(poId, rowVer(in), "APPROVED");
        PoRow full = mapper.findPo(poId);
        if ("BLOCKED".equals(full.getTradeStatus())) {
            throw fieldError("custCd", "SUPPLIER_BLOCKED");
        }
        if (mapper.issue(poId, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        mapper.insertRevSnapshot(poId, me.loginId());
        notifySupplier(poId, PoIssuedMailHandler.TOPIC, null);
    }

    /**
     * 통지 다시 보내기(통지 이력에서, 실패한 건) — 지금 상태가 그 통지와 맞을 때만: 발행 통지는 ISSUED·ACKED, 취소 통지는 CANCELLED.
     * 받는 주소는 지금 거래처·담당자 메일로 다시 읽는다(주소를 고친 뒤 다시 보내는 것이 보통이라).
     */
    @Transactional
    public void renotify(long poId, String topic) {
        PoRow cur = lock(poId);
        boolean ok = PoIssuedMailHandler.TOPIC.equals(topic) ? "ISSUED".equals(cur.getStatus()) || "ACKED".equals(cur.getStatus())
                : PoCancelledMailHandler.TOPIC.equals(topic) && "CANCELLED".equals(cur.getStatus());
        if (!ok) {
            throw new BusinessException(HbErrorCode.SRM_STATE);
        }
        notifySupplier(poId, topic, PoCancelledMailHandler.TOPIC.equals(topic) ? mapper.findPo(poId).getCancelReason() : null);
    }

    /** 협력사 통지 — 아웃박스(같은 트랜잭션, 실제 송신은 릴레이). bizKey = PO:id:rev, 본문에 메일 재료를 싣는다(송신기는 DB 를 못 쓴다) */
    private void notifySupplier(long poId, String topic, String reason) {
        Map<String, Object> i = mapper.notifyInfo(poId);
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("kind", topic);
        p.put("poId", poId);
        p.put("poNo", i.get("po_no"));
        p.put("rev", i.get("rev"));
        p.put("custNm", i.get("cust_nm"));
        p.put("compNm", i.get("comp_nm"));
        p.put("to", i.get("email"));
        p.put("lineCnt", i.get("line_cnt"));
        p.put("totalAmt", i.get("total_amt"));
        p.put("currencyCd", i.get("currency_cd"));
        p.put("firstDue", i.get("first_due") == null ? null : i.get("first_due").toString());
        if (reason != null) {
            p.put("reason", reason);
        }
        outbox.publish(topic, "PO:" + poId + ":" + i.get("rev"), p);
    }

    /** 다시 작성 — 승인됨(발행 전)은 개정 번호 그대로, 발행·회신된 발주는 개정(rev + 1) */
    @Transactional
    public void revise(long poId, ActionForm in) {
        LoginUser me = me();
        srmOwned();
        PoRow cur = start(poId, rowVer(in), "APPROVED", "ISSUED", "ACKED");
        if (mapper.revise(poId, cur.getStatus(), in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 취소(사유 필수) — 결재 중이면 먼저 결재를 회수한다 */
    @Transactional
    public void cancel(long poId, ActionForm in) {
        LoginUser me = me();
        PoRow cur = start(poId, rowVer(in), "DRAFT", "APPROVED", "ISSUED", "ACKED");
        FieldErrors e = FieldErrors.create();
        String reason = text(in.reason(), "reason", 500, true, e);
        e.throwIfAny();
        if (mapper.changeStatus(poId, cur.getStatus(), "CANCELLED", in.rowVer(), null, reason, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if ("ISSUED".equals(cur.getStatus()) || "ACKED".equals(cur.getStatus())) {
            notifySupplier(poId, PoCancelledMailHandler.TOPIC, reason);    // 협력사가 이미 받은 발주만 취소를 알린다
        }
    }

    /** 마감 — 남은 수량을 더 받지 않음. 입고 중·다 받은 발주도 사람이 마감한다(허용 초과율 안의 추가 입고를 위해 자동 마감 없음) */
    @Transactional
    public void close(long poId, ActionForm in) {
        LoginUser me = me();
        PoRow cur = start(poId, rowVer(in), "ISSUED", "ACKED", "PARTIAL_RECEIVED", "RECEIVED");
        change(cur, "CLOSED", in.rowVer(), null, me);
    }

    // ------------------------------------------------------------------ ERP 발주 받기 (S3b)

    /** ERP 발주 머리 — 엑셀 검증이 정한 값 */
    record ErpHead(long custId, String currencyCd, String payTermsCd, String plantCd, Long whId, LocalDate poDate, String remark) {
    }

    /**
     * ERP 발주 하나를 작성 중으로 넣고 ERP 번호를 매핑한다(엑셀 실행 트랜잭션 안). 시스템 안 같은 번호는 매핑 부분 유일이 막는다
     * (동시에 같은 파일 실행 → 뒤쪽 실행 전체 롤백). 확정·발행은 행을 다 넣은 뒤 {@link #releaseErp}.
     */
    long insertErp(long systemId, String erpPoNo, ErpHead h, String by) {
        if (!"ERP".equals(policies.value("SRM_PO_SOURCE"))) {
            throw new BusinessException(HbErrorCode.SRM_STATE);      // 검증 뒤 정책이 SRM 으로 바뀜
        }
        String poNo = numbers.next(SrmNumbers.PO, by);
        long id = mapper.insertErpPo(poNo, h.poDate(), h.custId(), h.currencyCd(), me().userId(),
                new PoValue(h.plantCd(), h.whId(), h.payTermsCd(), h.remark(), BigDecimal.ZERO, null), by);
        mapper.insertExtMap(systemId, id, erpPoNo, by);
        return id;
    }

    /** ERP 행 — 행 번호는 ERP 행 번호 그대로, 금액 = round(수량 × 단가, 통화 자릿수) */
    void insertErpLine(long poId, int lineNo, PoLineValue v, String by) {
        mapper.insertLine(poId, lineNo, v, by);
    }

    int erpAmountScale(String currencyCd) {
        return currencyScale(currencyCd);
    }

    /** 행을 다 넣은 뒤: 합계 → 확정(ERP 에서 이미 승인된 발주 — 결재 없음) → 발행(발행본 스냅샷 + 협력사 통지, D1 통지는 SRM) */
    void releaseErp(long poId, String by) {
        PoRow cur = lock(poId);
        PoRow full = mapper.findPo(poId);
        int v = cur.getRowVer();
        if (mapper.updatePo(poId, new PoValue(full.getPlantCd(), full.getWhId(), full.getPayTermsCd(), full.getRemark(),
                mapper.sumAmt(poId), v), by) == 0
                || mapper.changeStatus(poId, "DRAFT", "APPROVED", v + 1, null, null, by) == 0
                || mapper.issue(poId, v + 2, by) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        mapper.insertRevSnapshot(poId, by);
        notifySupplier(poId, PoIssuedMailHandler.TOPIC, null);
    }

    static String bizKey(long poId, int rev) {
        return poId + ":" + rev;
    }

    /** 확정·상신 때 필수(작성 중엔 강제하지 않음): 행 1+, 요구 납기 ≥ 오늘, 사용 중 품목, 거래 정지 아님 */
    private PoRow readyCheck(long poId) {
        PoRow full = mapper.findPo(poId);
        List<PoLineRow> lines = mapper.findLines(poId);
        LocalDate today = clock.today();
        FieldErrors e = FieldErrors.create();
        if ("BLOCKED".equals(full.getTradeStatus())) {
            e.add("custCd", "SUPPLIER_BLOCKED");
        }
        if (lines.isEmpty()) {
            e.add("lines", "LINES_REQUIRED");
        }
        for (int i = 0; i < lines.size(); i++) {
            PoLineRow l = lines.get(i);
            if (l.getDueDate().isBefore(today)) {
                e.row(String.valueOf(i)).add("dueDate", "PAST");
            }
            if (!"Y".equals(l.getItemUseYn())) {
                e.row(String.valueOf(i)).add("itemCd", "INACTIVE");
            }
        }
        e.throwIfAny();
        return full;
    }

    private PoRow lock(long poId) {
        PoRow cur = mapper.lockPo(poId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return cur;
    }

    private PoRow start(long poId, Integer rowVer, String... from) {
        if (rowVer == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        PoRow cur = lock(poId);
        if (!List.of(from).contains(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.SRM_STATE);
        }
        if (cur.getRowVer() != rowVer) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return cur;
    }

    private static Integer rowVer(ActionForm in) {
        return in == null ? null : in.rowVer();
    }

    private void change(PoRow cur, String to, int rowVer, String reason, LoginUser me) {
        if (mapper.changeStatus(cur.getPoId(), cur.getStatus(), to, rowVer, null, reason, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "fk_srm_po_plant" -> fieldError("plantCd", "NO_SUCH_CODE");
            case "fk_srm_po_wh_plant", "fk_srm_po_wh" -> fieldError("whCd", "NO_SUCH_CODE");
            case "fk_srm_po_pay_terms" -> fieldError("payTermsCd", "NO_SUCH_CODE");
            default -> e;
        };
    }
}
