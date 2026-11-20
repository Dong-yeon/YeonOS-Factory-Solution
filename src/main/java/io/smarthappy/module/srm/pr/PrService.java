package io.smarthappy.module.srm.pr;

import static io.smarthappy.module.srm.SrmChecks.blank;
import static io.smarthappy.module.srm.SrmChecks.code;
import static io.smarthappy.module.srm.SrmChecks.date;
import static io.smarthappy.module.srm.SrmChecks.decimal;
import static io.smarthappy.module.srm.SrmChecks.fieldError;
import static io.smarthappy.module.srm.SrmChecks.me;
import static io.smarthappy.module.srm.SrmChecks.text;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.srm.SrmChecks;
import io.smarthappy.module.srm.SrmNumbers;
import io.smarthappy.module.srm.pr.PrDtos.ActionForm;
import io.smarthappy.module.srm.pr.PrDtos.ItemRef;
import io.smarthappy.module.srm.pr.PrDtos.PrCrit;
import io.smarthappy.module.srm.pr.PrDtos.PrDetail;
import io.smarthappy.module.srm.pr.PrDtos.PrForm;
import io.smarthappy.module.srm.pr.PrDtos.PrLineForm;
import io.smarthappy.module.srm.pr.PrDtos.PrLineRow;
import io.smarthappy.module.srm.pr.PrDtos.PrLineValue;
import io.smarthappy.module.srm.pr.PrDtos.PrPage;
import io.smarthappy.module.srm.pr.PrDtos.PrRow;
import io.smarthappy.module.srm.pr.PrDtos.PrSort;
import io.smarthappy.module.srm.pr.PrDtos.PrValue;
import io.smarthappy.module.srm.pr.PrDtos.SupplierRef;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 구매요청 (srm-pr) — SRM S2, docs/31 D5.
 * 머리 + 행 "한 벌" 저장(작성 중만), 상태 전이 DRAFT → REQUESTED → APPROVED / REJECTED → CLOSED · CANCELLED.
 * 승인은 APPROVE 권한 단순 승인(결재 아님), 요청자 본인 승인은 정책 SRM_PR_SELF_APPROVE. 전이 표·작성 중만 편집은 트리거가 최종.
 */
@Service
@RequiredArgsConstructor
public class PrService {

    public static final String PROG = "srm-pr";
    static final String SELF_APPROVE = "SRM_PR_SELF_APPROVE";
    static final int MAX_LINES = 200;
    static final int SEARCH_MAX_DAYS = 366;
    static final int QTY_INT_DIGITS = 12;
    static final Set<String> STATUS = Set.of("DRAFT", "REQUESTED", "APPROVED", "REJECTED", "CLOSED", "CANCELLED");

    private final PrMapper mapper;
    private final SrmNumbers numbers;
    private final InboxService inbox;
    private final PolicyService policies;
    private final DateRanges ranges;
    private final BizClock clock;

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public List<PrRow> list(String from, String to, String status, String keyword, String plantCd) {
        return mapper.findPrs(criteria(from, to, status, keyword, plantCd));
    }

    /**
     * grid(UI 단계 3 ①, docs/31 5.2) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link PrSort}, 최대 3개, 모르는 열 400).
     * 기간 필수·상한도 목록과 같다(행 수 상한 대신 페이지)
     */
    @Transactional(readOnly = true)
    public PrPage grid(String from, String to, String status, String keyword, String plantCd, Integer page, Integer size,
            List<String> sort, Boolean total) {
        PrCrit c = criteria(from, to, status, keyword, plantCd);
        PageQuery pq = PageQuery.parse(page, size, sort, total, PrSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("prDate,desc"));
        return new PrPage(mapper.findPrPage(c, pq), pq.isWithTotal() ? mapper.countPrs(c) : null);
    }

    /** grid 열(화면 목록과 같은 칸) — 정렬 가능 = {@link PrSort} 에 있는 열 */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("prNo", "srm.pr.col.prNo", 120).withSort().withPriority(1),
            ColumnSpec.of("prDate", "srm.pr.col.prDate", ColumnType.DATE, 100).withSort(),
            ColumnSpec.text("plantNm", "srm.pr.col.plant", 110).withSort(),
            ColumnSpec.text("reqUserNm", "srm.pr.col.requester", 100).withSort(),
            ColumnSpec.of("needDate", "srm.pr.col.needDate", ColumnType.DATE, 100).withSort(),
            ColumnSpec.of("lineCnt", "srm.pr.col.lineCnt", ColumnType.INT, 70).withSort().withPriority(3),
            ColumnSpec.code("status", "srm.pr.col.status", "PR_STATUS", 90).withSort().withPriority(1));

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    /** 목록·grid 공통 조건 검증 */
    private PrCrit criteria(String from, String to, String status, String keyword, String plantCd) {
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
        return new PrCrit(f, t, st, k, code(plantCd));
    }

    @Transactional(readOnly = true)
    public PrDetail get(long prId) {
        PrRow pr = mapper.findPr(prId);
        if (pr == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return new PrDetail(pr, mapper.findLines(prId));
    }

    // ------------------------------------------------------------------ 저장(한 벌)

    @Transactional
    public long create(PrForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("SRM_PR_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        PrValue head = header(in, null, e);
        List<PrLineValue> lines = lines(in.lines(), Map.of(), e);
        e.throwIfAny();
        String prNo = numbers.next(SrmNumbers.PR, me.loginId());
        try {
            long id = mapper.insertPr(prNo, clock.today(), me.userId(), head, me.loginId());
            int no = 1;
            for (PrLineValue l : lines) {
                mapper.insertLine(id, no++, l, me.loginId());
            }
            return id;
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    @Transactional
    public void update(long prId, PrForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        PrRow cur = lock(prId);
        if (!"DRAFT".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.SRM_STATE);
        }
        if (cur.getRowVer() != in.rowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        Map<Long, PrLineRow> existing = new HashMap<>();
        mapper.findLines(prId).forEach(l -> existing.put(l.getPrLineId(), l));
        FieldErrors e = FieldErrors.create();
        PrRow full = mapper.findPr(prId);
        PrValue head = header(in, full, e);
        List<PrLineValue> lines = lines(in.lines(), existing, e);
        e.throwIfAny();
        try {
            if (mapper.updatePr(prId, head, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
            Set<Long> kept = new HashSet<>();
            for (PrLineValue l : lines) {
                if (l.prLineId() != null) {
                    kept.add(l.prLineId());
                }
            }
            for (Long id : existing.keySet()) {
                if (!kept.contains(id)) {
                    mapper.deleteLine(id);
                }
            }
            int next = mapper.nextLineNo(prId);
            for (PrLineValue l : lines) {
                if (l.prLineId() == null) {
                    mapper.insertLine(prId, next++, l, me.loginId());
                } else {
                    mapper.updateLine(l, me.loginId());
                }
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    private PrValue header(PrForm in, PrRow cur, FieldErrors e) {
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
        String dept = text(in.deptNm(), "deptNm", 50, false, e);
        LocalDate need = date(in.needDate(), "needDate", false, e);
        String reason = text(in.reason(), "reason", 500, false, e);
        return new PrValue(plant, dept, need, reason, in.rowVer());
    }

    /** 행 검사 — 행 오류는 행 키(배열 순번)로. 새 행·품목을 바꾼 행은 사용 중 품목만, 그대로 둔 행은 사용 중지 품목이어도 통과 */
    private List<PrLineValue> lines(List<PrLineForm> in, Map<Long, PrLineRow> existing, FieldErrors e) {
        List<PrLineValue> out = new ArrayList<>();
        if (in == null || in.isEmpty()) {
            return out;
        }
        if (in.size() > MAX_LINES) {
            e.add("lines", "TOO_MANY_LINES", MAX_LINES);
            return out;
        }
        Set<Long> seen = new HashSet<>();
        Map<String, ItemRef> items = new HashMap<>();
        Map<String, SupplierRef> sups = new HashMap<>();
        for (int i = 0; i < in.size(); i++) {
            PrLineForm f = in.get(i);
            FieldErrors le = FieldErrors.create();
            if (f == null) {
                e.row(String.valueOf(i)).add("itemCd", "REQUIRED");
                continue;
            }
            PrLineRow old = null;
            if (f.prLineId() != null) {
                old = existing.get(f.prLineId());
                if (old == null || !seen.add(f.prLineId())) {
                    throw new BusinessException(HbErrorCode.INVALID_VALUE);    // 다른 요청의 행 id·같은 id 두 번
                }
            }
            ItemRef item = null;
            String cd = code(f.itemCd());
            if (cd == null) {
                le.add("itemCd", "REQUIRED");
            } else {
                item = items.computeIfAbsent(cd, k -> k.length() > 40 ? null : mapper.findItem(k));
                if (item == null) {
                    le.add("itemCd", "NO_SUCH_CODE");
                } else if (!"Y".equals(item.getUseYn()) && (old == null || old.getItemId() != item.getItemId())) {
                    le.add("itemCd", "INACTIVE");
                }
            }
            // 그대로 둔 행은 저장 때의 단위를 유지(품목 단위가 바뀌어도 요청 수량의 뜻이 바뀌지 않게)
            String unit = item == null ? null
                    : old != null && old.getItemId() == item.getItemId() ? old.getUnitCd() : item.getUnitCd();
            int scale = unit == null ? 6 : scaleOf(unit);
            BigDecimal qty = decimal(f.qty(), "qty", scale, QTY_INT_DIGITS, true, le);
            LocalDate need = date(f.needDate(), "needDate", false, le);
            Long pref = null;
            String pc = code(f.prefCustCd());
            if (pc != null) {
                SupplierRef s = sups.computeIfAbsent(pc, k -> k.length() > 30 ? null : mapper.findSupplier(k));
                if (s == null) {
                    le.add("prefCustCd", "NO_SUCH_CODE");
                } else if ("BLOCKED".equals(s.getTradeStatus())
                        && (old == null || old.getPrefCustId() == null || old.getPrefCustId() != s.getCustId())) {
                    le.add("prefCustCd", "SUPPLIER_BLOCKED");
                } else {
                    pref = s.getCustId();
                }
            }
            String remark = text(f.remark(), "remark", 500, false, le);
            String key = String.valueOf(i);
            le.list().forEach(x -> e.row(key).add(x.field(), x.code(), x.args()));
            if (le.isEmpty()) {
                out.add(new PrLineValue(f.prLineId(), item.getItemId(), qty, unit, need, pref, remark));
            }
        }
        return out;
    }

    private int scaleOf(String unitCd) {
        Integer s = mapper.unitScale(unitCd);
        return s == null ? 6 : Math.min(s, 6);
    }

    // ------------------------------------------------------------------ 상태 전이

    /** 요청: 행 1개 이상, 필요일(머리·행)은 오늘 이후 */
    @Transactional
    public void request(long prId, ActionForm in) {
        LoginUser me = me();
        PrRow cur = transitionStart(prId, in, "DRAFT");
        PrRow full = mapper.findPr(prId);
        List<PrLineRow> lines = mapper.findLines(prId);
        LocalDate today = clock.today();
        FieldErrors e = FieldErrors.create();
        if (lines.isEmpty()) {
            e.add("lines", "LINES_REQUIRED");
        }
        if (full.getNeedDate() != null && full.getNeedDate().isBefore(today)) {
            e.add("needDate", "PAST");
        }
        for (int i = 0; i < lines.size(); i++) {     // 행 키 = 행 번호 순서의 순번(화면 행 순서와 같다)
            PrLineRow l = lines.get(i);
            String key = String.valueOf(i);
            LocalDate need = l.getNeedDate() != null ? l.getNeedDate() : full.getNeedDate();
            if (need == null) {
                e.row(key).add("needDate", "REQUIRED");
            } else if (need.isBefore(today)) {
                e.row(key).add("needDate", "PAST");
            }
            if (!"Y".equals(l.getItemUseYn())) {
                e.row(key).add("itemCd", "INACTIVE");
            }
        }
        e.throwIfAny();
        change(cur, "REQUESTED", in, null, null, null, me);
    }

    /** 회수(요청 → 작성 중) — 결정 전까지만 */
    @Transactional
    public void withdraw(long prId, ActionForm in) {
        LoginUser me = me();
        change(transitionStart(prId, in, "REQUESTED"), "DRAFT", in, null, null, null, me);
    }

    @Transactional
    public void approve(long prId, ActionForm in) {
        LoginUser me = me();
        PrRow cur = transitionStart(prId, in, "REQUESTED");
        if (cur.getReqUserId() == me.userId() && !policies.bool(SELF_APPROVE)) {
            throw new BusinessException(HbErrorCode.SRM_SELF_APPROVE);
        }
        change(cur, "APPROVED", in, me.userId(), null, null, me);
    }

    @Transactional
    public void reject(long prId, ActionForm in) {
        LoginUser me = me();
        PrRow cur = transitionStart(prId, in, "REQUESTED");
        String reason = requiredReason(in);
        change(cur, "REJECTED", in, me.userId(), reason, null, me);
    }

    /** 반려된 요청을 고쳐 다시 내기 위해 작성 중으로 */
    @Transactional
    public void reopen(long prId, ActionForm in) {
        LoginUser me = me();
        change(transitionStart(prId, in, "REJECTED"), "DRAFT", in, null, null, null, me);
    }

    /** 작성 중·요청·승인 상태에서 취소(사유 필수). 승인된 요청은 살아 있는 발주 행이 없을 때만 */
    @Transactional
    public void cancel(long prId, ActionForm in) {
        LoginUser me = me();
        PrRow cur = transitionStart(prId, in, "DRAFT", "REQUESTED", "APPROVED");
        String reason = requiredReason(in);
        if ("APPROVED".equals(cur.getStatus()) && mapper.countLivePoLines(prId) > 0) {
            throw new BusinessException(HbErrorCode.SRM_PR_HAS_PO);     // 트리거 ck_srm_pr_has_po 가 최종
        }
        change(cur, "CANCELLED", in, null, null, reason, me);
    }

    /** 승인된 요청 마감(남은 수량을 더 사지 않음) */
    @Transactional
    public void close(long prId, ActionForm in) {
        LoginUser me = me();
        change(transitionStart(prId, in, "APPROVED"), "CLOSED", in, null, null, null, me);
    }

    private PrRow lock(long prId) {
        PrRow cur = mapper.lockPr(prId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return cur;
    }

    private PrRow transitionStart(long prId, ActionForm in, String... from) {
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        PrRow cur = lock(prId);
        if (!List.of(from).contains(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.SRM_STATE);
        }
        if (cur.getRowVer() != in.rowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return cur;
    }

    private static String requiredReason(ActionForm in) {
        FieldErrors e = FieldErrors.create();
        String r = text(in.reason(), "reason", 500, true, e);
        e.throwIfAny();
        return r;
    }

    private void change(PrRow cur, String to, ActionForm in, Long decidedBy, String rejectReason, String cancelReason,
            LoginUser me) {
        if (mapper.changeStatus(cur.getPrId(), cur.getStatus(), to, in.rowVer(), decidedBy, rejectReason, cancelReason,
                me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "fk_srm_pr_plant" -> fieldError("plantCd", "NO_SUCH_CODE");
            case "fk_srm_pr_line_unit" -> fieldError("itemCd", "NO_SUCH_CODE");
            default -> e;
        };
    }
}
