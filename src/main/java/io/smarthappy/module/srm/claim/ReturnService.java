package io.smarthappy.module.srm.claim;

import static io.smarthappy.module.srm.SrmChecks.blank;
import static io.smarthappy.module.srm.SrmChecks.code;
import static io.smarthappy.module.srm.SrmChecks.date;
import static io.smarthappy.module.srm.SrmChecks.decimal;
import static io.smarthappy.module.srm.SrmChecks.fieldError;
import static io.smarthappy.module.srm.SrmChecks.me;
import static io.smarthappy.module.srm.SrmChecks.text;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.qms.link.SupplierQualityQuery;
import io.smarthappy.module.qms.link.SupplierQualityQuery.NcrDetail;
import io.smarthappy.module.qms.link.SupplierQualityQuery.SupplierReturn;
import io.smarthappy.module.srm.SrmChecks;
import io.smarthappy.module.srm.SrmNumbers;
import io.smarthappy.module.srm.claim.ReturnDtos.ActionForm;
import io.smarthappy.module.srm.claim.ReturnDtos.Candidate;
import io.smarthappy.module.srm.claim.ReturnDtos.CandidatePage;
import io.smarthappy.module.srm.claim.ReturnDtos.PoLineOption;
import io.smarthappy.module.srm.claim.ReturnDtos.PoLineRef;
import io.smarthappy.module.srm.claim.ReturnDtos.ReturnCrit;
import io.smarthappy.module.srm.claim.ReturnDtos.ReturnForm;
import io.smarthappy.module.srm.claim.ReturnDtos.ReturnPage;
import io.smarthappy.module.srm.claim.ReturnDtos.ReturnRow;
import io.smarthappy.module.srm.claim.ReturnDtos.ReturnSort;
import io.smarthappy.module.srm.claim.ReturnDtos.ReturnValue;
import io.smarthappy.module.srm.claim.ReturnDtos.SupplierRef;
import io.smarthappy.module.srm.link.ReturnUnits;
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
 * 반품 지시 (srm-return) — SRM 2부 R4c, docs/31 4.12.4.
 * 근거 = QMS 닫힌 NCR 의 RETURN 처리 줄(공개 계약 {@link SupplierQualityQuery}, QMS 표는 읽지 않음) → 만들기(작성 중) → 수정 →
 * 확정(APPROVE) / 취소(사유). 출고(SHIPPED)는 WMS 반품 출고 게이트(R4-4, 나중).
 * 수량 ≤ 그 처리 줄 반품 수량 — 처리 줄마다 살아 있는 반품은 하나(uq_srm_return_ncr_line)라 "이미 반품한 양" 은 이 반품뿐.
 * 만들기·수정·확정 때마다 계약을 다시 읽는다(NCR 이 다시 열리거나 처리 줄이 바뀌었으면 막힘).
 */
@Service
@RequiredArgsConstructor
public class ReturnService {

    public static final String PROG = "srm-return";
    static final int SEARCH_MAX_DAYS = 366;
    static final int QTY_INT_DIGITS = 12;
    static final Set<String> STATUS = Set.of("DRAFT", "CONFIRMED", "SHIPPED", "CANCELLED");

    private final ReturnMapper mapper;
    private final SrmNumbers numbers;
    private final InboxService inbox;
    private final DateRanges ranges;
    private final BizClock clock;
    private final PolicyService policies;
    private final ObjectProvider<SupplierQualityQuery> quality;

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public List<ReturnRow> list(String from, String to, String status, String keyword) {
        return mapper.findReturns(criteria(from, to, status, keyword));
    }

    @Transactional(readOnly = true)
    public ReturnPage grid(String from, String to, String status, String keyword, Integer page, Integer size, List<String> sort,
            Boolean total) {
        ReturnCrit c = criteria(from, to, status, keyword);
        PageQuery pq = PageQuery.parse(page, size, sort, total, ReturnSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("createdAt,desc"));
        return new ReturnPage(mapper.findReturnPage(c, pq), pq.isWithTotal() ? mapper.countReturns(c) : null);
    }

    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("returnNo", "srm.return.col.returnNo", 120).withSort().withPriority(1),
            ColumnSpec.text("custNm", "srm.return.col.supplier", 140).withSort(),
            ColumnSpec.text("ncrNo", "srm.return.col.ncrNo", 120).withSort().withPriority(3),
            ColumnSpec.text("lotNo", "srm.return.col.lotNo", 120).withPriority(3),
            ColumnSpec.text("itemCd", "srm.return.col.item", 110).withSort(),
            ColumnSpec.dec("qty", "srm.return.col.qty", 6, 90).withMinScale(0),
            ColumnSpec.text("unitCd", "srm.return.col.unit", 60).withPriority(3),
            ColumnSpec.of("createdAt", "srm.return.col.createdAt", ColumnType.DATETIME, 130).withSort().withPriority(3),
            ColumnSpec.code("status", "srm.return.col.status", "RETURN_STATUS", 90).withSort().withPriority(1));

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    private ReturnCrit criteria(String from, String to, String status, String keyword) {
        FieldErrors e = FieldErrors.create();
        LocalDate f = date(from, "from", true, e);
        LocalDate t = date(to, "to", true, e);
        e.throwIfAny();
        ranges.check(f, t, SEARCH_MAX_DAYS);
        String st = code(status);
        if (st != null && !STATUS.contains(st) || keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String k = blank(keyword) ? null : "%" + SrmChecks.like(keyword.strip()) + "%";
        return new ReturnCrit(f, t, clock.zone().getId(), st, k);
    }

    @Transactional(readOnly = true)
    public ReturnRow get(long returnId) {
        ReturnRow r = mapper.findReturn(returnId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        r.setClaims(mapper.claimsOfNcr(r.getNcrId(), r.getCustId(), clock.zone().getId()));
        return r;
    }

    /**
     * 반품 대상 — 협력사 LOT 의 닫힌 NCR 반품 처리 줄(종결 시각이 기간 안) + 줄마다 살아 있는 반품 번호.
     * 목록 표시용(합하지 않음 — QQ-3), truncated 면 화면이 기간을 줄이라고 알린다
     */
    @Transactional(readOnly = true)
    public CandidatePage candidates(String custCd, String from, String to) {
        SupplierRef s = supplier(custCd);
        FieldErrors e = FieldErrors.create();
        LocalDate f = date(from, "from", true, e);
        LocalDate t = date(to, "to", true, e);
        e.throwIfAny();
        ranges.check(f, t, SEARCH_MAX_DAYS);
        SupplierQualityQuery.ReturnPage page = SupplierQualityQuery.of(quality).returnsToSupplier(s.getCustId(), f, t);
        Set<Long> ncrIds = new LinkedHashSet<>();
        page.rows().forEach(r -> ncrIds.add(r.ncrId()));
        Map<String, String> live = new HashMap<>();
        if (!ncrIds.isEmpty()) {
            mapper.liveReturns(s.getCustId(), ncrIds).forEach(m -> live.put(String.valueOf(m.get("k")), String.valueOf(m.get("no"))));
        }
        List<Candidate> rows = page.rows().stream().map(r -> new Candidate(r.ncrId(), r.ncrNo(), r.lineNo(), r.lotId(), r.lotNo(),
                r.supplierLotNo(), r.itemId(), r.itemCd(), plain(r.qty()), r.unitCd(), r.defectCd(), r.defectNm(), r.closedAt(),
                live.get(r.ncrId() + ":" + r.lineNo()))).toList();
        return new CandidatePage(rows, page.truncated());
    }

    /** 교체 요구가 고를 발주 행 — 그 협력사·품목의 받은 것이 있는 SRM 발주 행 */
    @Transactional(readOnly = true)
    public List<PoLineOption> poLines(String custCd, Long itemId) {
        SupplierRef s = supplier(custCd);
        if (itemId == null) {
            throw fieldError("itemId", "REQUIRED");
        }
        return mapper.poLineOptions(s.getCustId(), itemId);
    }

    private SupplierRef supplier(String custCd) {
        String cd = code(custCd);
        SupplierRef s = cd == null || cd.length() > 30 ? null : mapper.findSupplier(cd);
        if (s == null) {
            throw fieldError("custCd", cd == null ? "REQUIRED" : "NO_SUCH_CODE");
        }
        return s;
    }

    // ------------------------------------------------------------------ 저장

    @Transactional
    public long create(ReturnForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("SRM_RETURN_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        SupplierRef s = null;
        String cd = code(in.custCd());
        if (cd == null) {
            e.add("custCd", "REQUIRED");
        } else {
            s = cd.length() > 30 ? null : mapper.findSupplier(cd);
            if (s == null) {
                e.add("custCd", "NO_SUCH_CODE");
            }
        }
        if (in.ncrId() == null) {
            e.add("ncrId", "REQUIRED");
        }
        if (in.lineNo() == null) {
            e.add("lineNo", "REQUIRED");
        }
        SupplierReturn line = s == null || in.ncrId() == null || in.lineNo() == null ? null : basis(s.getCustId(), in.ncrId(), in.lineNo(), e);
        ReturnValue v = values(in, s == null ? 0 : s.getCustId(), line, null, e);
        e.throwIfAny();
        try {
            return mapper.insertReturn(numbers.next(SrmNumbers.RETURN, me.loginId()), v, me.loginId());
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    @Transactional
    public void update(long returnId, ReturnForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        ReturnRow cur = start(returnId, in.rowVer(), "DRAFT");
        FieldErrors e = FieldErrors.create();
        SupplierReturn line = basis(cur.getCustId(), cur.getNcrId(), cur.getNcrLineNo(), e);   // 근거는 고정 — 다시 읽어 한도만
        ReturnValue v = values(in, cur.getCustId(), line, cur, e);
        e.throwIfAny();
        try {
            if (mapper.updateReturn(returnId, v, in.rowVer(), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /**
     * 근거 처리 줄 — 계약으로 NCR(닫힘·이 협력사)을 읽고, 그 종결 날(회사 날짜)의 반품 줄 중 이 줄.
     * 없거나 다른 회사 id 면 NO_SUCH_CODE(있는지조차 알려주지 않음)
     */
    private SupplierReturn basis(long custId, long ncrId, int lineNo, FieldErrors e) {
        SupplierQualityQuery q = SupplierQualityQuery.of(quality);
        NcrDetail n = q.ncr(ncrId).orElse(null);
        if (n == null) {
            e.add("ncrId", "NO_SUCH_CODE");
            return null;
        }
        if (n.supplierCustId() == null || n.supplierCustId() != custId) {
            e.add("ncrId", "SRM_RTN_NCR_SUPPLIER");
            return null;
        }
        if (!"CLOSED".equals(n.status()) || n.closedAt() == null) {
            e.add("ncrId", "SRM_RTN_NCR_STATE");
            return null;
        }
        LocalDate day = n.closedAt().atZoneSameInstant(clock.zone()).toLocalDate();
        SupplierReturn line = q.returnsToSupplier(custId, day, day).rows().stream()
                .filter(r -> r.ncrId() == ncrId && r.lineNo() == lineNo).findFirst().orElse(null);
        if (line == null) {
            e.add("lineNo", "NO_SUCH_CODE");
        }
        return line;
    }

    /** 입력 검사 — 수량 ≤ 처리 줄 반품 수량, 교체 요구면 발주 행(같은 협력사·품목, 단위가 다르면 환산 — R4c-단위) */
    private ReturnValue values(ReturnForm in, long custId, SupplierReturn line, ReturnRow cur, FieldErrors e) {
        String plant = code(in.plantCd());
        if (plant == null) {
            e.add("plantCd", "REQUIRED");
        } else {
            String use = plant.length() > 20 ? null : mapper.plantUseYn(plant);
            if (use == null) {
                e.add("plantCd", "NO_SUCH_CODE");
            } else if (!"Y".equals(use) && (cur == null || !plant.equals(cur.getPlantCd()))) {
                e.add("plantCd", "INACTIVE");
            }
        }
        BigDecimal qty = decimal(in.qty(), "qty", 6, QTY_INT_DIGITS, true, e);
        if (qty != null && line != null && qty.compareTo(line.qty()) > 0) {
            e.add("qty", "SRM_RTN_OVER", plain(line.qty()), line.unitCd());
        }
        String replace = blank(in.replaceYn()) ? "N" : code(in.replaceYn());
        if (!"Y".equals(replace) && !"N".equals(replace)) {
            e.add("replaceYn", "CODE");
        }
        Long poLineId = in.poLineId();
        if ("Y".equals(replace) && poLineId == null) {
            e.add("poLineId", "REQUIRED");          // 교체품을 받을 발주(입고 되돌림 대상 — R4-3)
        }
        long itemId = line != null ? line.itemId() : cur != null ? cur.getItemId() : 0;
        String unit = line != null ? line.unitCd() : cur != null ? cur.getUnitCd() : null;
        if (poLineId != null) {
            PoLineRef po = mapper.findPoLine(poLineId, unit);
            if (po == null) {
                e.add("poLineId", "NO_SUCH_CODE");
            } else if (po.getCustId() != custId) {
                e.add("poLineId", "SRM_OTHER_SUPPLIER");
            } else if (po.getItemId() != itemId) {
                e.add("poLineId", "SRM_RTN_PO_ITEM");
            } else if ("Y".equals(replace) && unit != null) {
                poUnit(qty, unit, po, e);
            }
        }
        String remark = text(in.remark(), "remark", 1000, false, e);
        if (line == null) {
            return null;
        }
        return new ReturnValue(custId, plant, line.ncrId(), line.ncrNo(), line.lineNo(), line.lotId(), line.lotNo(), line.itemId(),
                line.unitCd(), qty, replace, poLineId, remark);
    }

    /**
     * 교체 요구의 발주 단위 — 반품 단위와 다르면 품목 단위 환산이 있어야 하고, 반품 수량을 발주 단위로 바꾼 값이 나누어떨어져
     * 발주 단위 자릿수 안이어야 한다(출고 게이트가 그 양만큼 입고를 되돌린다 — 게이트도 그때의 환산표로 다시 본다)
     */
    private static void poUnit(BigDecimal qty, String unit, PoLineRef po, FieldErrors e) {
        if (unit.equals(po.getUnitCd())) {
            return;
        }
        ReturnUnits.PoQty q = ReturnUnits.poQty(qty == null ? BigDecimal.ONE : qty, po.getRetFactor(), po.getPoFactor());
        if (q == null) {
            e.add("poLineId", "SRM_RTN_PO_CONV", unit, po.getUnitCd());
        } else if (qty != null && !q.fits(po.getPoScale())) {
            e.add("qty", "SRM_RTN_PO_SCALE", plain(qty), unit, q.show(po.getPoScale()), po.getUnitCd(), ReturnUnits.scale(po.getPoScale()));
        }
    }

    // ------------------------------------------------------------------ 상태

    /** 확정(APPROVE) — 계약을 다시 읽어 수량 한도를 본다(NCR 이 다시 열렸으면 막힘) */
    @Transactional
    public void confirm(long returnId, ActionForm in) {
        LoginUser me = me();
        ReturnRow cur = start(returnId, rowVer(in), "DRAFT");
        FieldErrors e = FieldErrors.create();
        SupplierReturn line = basis(cur.getCustId(), cur.getNcrId(), cur.getNcrLineNo(), e);
        ReturnRow full = mapper.findReturn(returnId);
        if (line != null && new BigDecimal(full.getQty()).compareTo(line.qty()) > 0) {
            e.add("qty", "SRM_RTN_OVER", plain(line.qty()), line.unitCd());
        }
        if ("Y".equals(full.getReplaceYn()) && full.getPoLineId() != null) {
            // 만든 뒤 환산표가 바뀌었을 수 있다 — 확정 때 다시(출고 게이트도 다시 본다)
            PoLineRef po = mapper.findPoLine(full.getPoLineId(), full.getUnitCd());
            if (po != null) {
                poUnit(new BigDecimal(full.getQty()), full.getUnitCd(), po, e);
            }
        }
        e.throwIfAny();
        if (mapper.confirm(returnId, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 취소(사유 필수) — 작성 중·확정에서. 출고된 반품은 취소 불가(트리거 ck_srm_return_transition) */
    @Transactional
    public void cancel(long returnId, ActionForm in) {
        LoginUser me = me();
        start(returnId, rowVer(in), "DRAFT", "CONFIRMED");
        FieldErrors e = FieldErrors.create();
        String reason = text(in.reason(), "reason", 500, true, e);
        e.throwIfAny();
        if (mapper.cancel(returnId, in.rowVer(), reason, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    private static int rowVer(ActionForm in) {
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return in.rowVer();
    }

    private ReturnRow start(long returnId, int rowVer, String... from) {
        ReturnRow cur = mapper.lockReturn(returnId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!List.of(from).contains(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.SRM_STATE);
        }
        if (cur.getRowVer() != rowVer) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return cur;
    }

    private static String plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "uq_srm_return_ncr_line" -> fieldError("lineNo", "SRM_RTN_DUP");
            case "fk_srm_return_plant" -> fieldError("plantCd", "NO_SUCH_CODE");
            case "fk_srm_return_po_line_comp" -> fieldError("poLineId", "NO_SUCH_CODE");
            case "ck_srm_return_po_item" -> fieldError("poLineId", "SRM_RTN_PO_ITEM");
            default -> e;
        };
    }
}
