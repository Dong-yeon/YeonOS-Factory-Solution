package io.smarthappy.module.srm.asn;

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
import io.smarthappy.module.srm.asn.AsnDtos.ActionForm;
import io.smarthappy.module.srm.asn.AsnDtos.AsnCrit;
import io.smarthappy.module.srm.asn.AsnDtos.AsnDetail;
import io.smarthappy.module.srm.asn.AsnDtos.AsnForm;
import io.smarthappy.module.srm.asn.AsnDtos.AsnLineForm;
import io.smarthappy.module.srm.asn.AsnDtos.AsnLineRow;
import io.smarthappy.module.srm.asn.AsnDtos.AsnLineValue;
import io.smarthappy.module.srm.asn.AsnDtos.AsnPage;
import io.smarthappy.module.srm.asn.AsnDtos.AsnRow;
import io.smarthappy.module.srm.asn.AsnDtos.AsnSort;
import io.smarthappy.module.srm.asn.AsnDtos.AsnValue;
import io.smarthappy.module.srm.asn.AsnDtos.LimitRow;
import io.smarthappy.module.srm.asn.AsnDtos.PoLineOption;
import io.smarthappy.module.srm.asn.AsnDtos.PoLineRef;
import io.smarthappy.module.srm.asn.AsnDtos.SupplierRef;
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
 * 사전 납품 통지 (srm-asn) — SRM 2부 R3a, docs/31 4.11.
 * 머리 + 행 한 벌 저장(작성 중만) → 확정(SENT, 한도 R3-4) → 입고는 WMS 가 게이트로(asnLineId) → 입고 중·다 받음은 게이트가 바꿈 →
 * 마감(사람). 취소는 받은 것이 없을 때만. 전이 표·한도·작성 중만 편집은 트리거가 최종.
 */
@Service
@RequiredArgsConstructor
public class AsnService {

    public static final String PROG = "srm-asn";
    static final int MAX_LINES = 200;
    static final int SEARCH_MAX_DAYS = 366;
    static final int QTY_INT_DIGITS = 12;
    static final Set<String> STATUS = Set.of("DRAFT", "SENT", "PARTIAL_RECEIVED", "RECEIVED", "CLOSED", "CANCELLED");
    static final Set<String> PO_OPEN = Set.of("ISSUED", "ACKED", "PARTIAL_RECEIVED");

    private final AsnMapper mapper;
    private final SrmNumbers numbers;
    private final InboxService inbox;
    private final DateRanges ranges;
    private final BizClock clock;
    private final PolicyService policies;

    // ------------------------------------------------------------------ 조회

    /** 기간 = 출하일~도착 예정일 구간이 겹치는 ASN(도착은 대개 미래라 "이번 달(오늘까지)" 기본 기간에도 보이게). late = 늦은 ASN(예정일 지남 + 남은 행, 확정·입고 중) */
    @Transactional(readOnly = true)
    public List<AsnRow> list(String from, String to, String status, String keyword, boolean late) {
        return mapper.findAsns(criteria(from, to, status, keyword, late));
    }

    /**
     * grid(UI 단계 3 ①, docs/31 5.2) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link AsnSort}, 최대 3개, 모르는 열 400).
     * 기간 필수·상한도 목록과 같다(행 수 상한 대신 페이지)
     */
    @Transactional(readOnly = true)
    public AsnPage grid(String from, String to, String status, String keyword, boolean late, Integer page, Integer size,
            List<String> sort, Boolean total) {
        AsnCrit c = criteria(from, to, status, keyword, late);
        PageQuery pq = PageQuery.parse(page, size, sort, total, AsnSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("etaDate,desc"));
        return new AsnPage(mapper.findAsnPage(c, pq), pq.isWithTotal() ? mapper.countAsns(c) : null);
    }

    /** grid 열(화면 목록과 같은 칸, 남은 행·행 수를 따로) — 정렬 가능 = {@link AsnSort} 에 있는 열 */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("asnNo", "srm.asn.col.asnNo", 120).withSort().withPriority(1),
            ColumnSpec.text("supplierRef", "srm.asn.col.supplierRef", 120).withPriority(3),
            ColumnSpec.text("custNm", "srm.asn.col.supplier", 140).withSort(),
            ColumnSpec.text("plantCd", "srm.asn.col.plant", 70).withSort().withPriority(3),
            ColumnSpec.of("shipDate", "srm.asn.col.shipDate", ColumnType.DATE, 100).withSort(),
            ColumnSpec.of("etaDate", "srm.asn.col.etaDate", ColumnType.DATE, 110).withSort(),
            ColumnSpec.of("openLineCnt", "srm.asn.col.openLines", ColumnType.INT, 70).withSort().withPriority(3),
            ColumnSpec.of("lineCnt", "srm.asn.col.lineCnt", ColumnType.INT, 60).withPriority(3),
            ColumnSpec.code("status", "srm.asn.col.status", "ASN_STATUS", 100).withSort().withPriority(1));

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    /** 목록·grid 공통 조건 검증 */
    private AsnCrit criteria(String from, String to, String status, String keyword, boolean late) {
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
        return new AsnCrit(f, t, st, k, late, clock.today());
    }

    @Transactional(readOnly = true)
    public AsnDetail get(long asnId) {
        AsnRow a = mapper.findAsn(asnId, clock.today());
        if (a == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return new AsnDetail(a, mapper.findLines(asnId));
    }

    /** 고를 수 있는 발주 행(그 협력사·공장) — 고치는 중이면 그 ASN 자신은 "다른 ASN" 에서 뺀다 */
    @Transactional(readOnly = true)
    public List<PoLineOption> poLines(String custCd, String plantCd, Long asnId) {
        SupplierRef s = supplier(custCd);
        String plant = code(plantCd);
        if (plant == null || plant.length() > 20) {
            throw fieldError("plantCd", "REQUIRED");
        }
        return mapper.poLineOptions(s.getCustId(), plant, asnId);
    }

    private SupplierRef supplier(String custCd) {
        String cd = code(custCd);
        SupplierRef s = cd == null || cd.length() > 30 ? null : mapper.findSupplier(cd);
        if (s == null) {
            throw fieldError("custCd", cd == null ? "REQUIRED" : "NO_SUCH_CODE");
        }
        return s;
    }

    // ------------------------------------------------------------------ 저장(한 벌)

    @Transactional
    public long create(AsnForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("SRM_ASN_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return insert(in, "MANUAL", null, me.loginId());
    }

    /**
     * 협력사 포털 ASN(docs/42 7장 3, 출처 PORTAL) — 검사는 대리 입력과 같다(발주 행은 머리 협력사·공장의 것만).
     * 부르는 쪽(module.srm.portal)이 협력사 코드를 로그인 소속으로 채워 넘긴다(요청 값을 쓰지 않는다).
     */
    @Transactional
    public long createFromPortal(AsnForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("SRM_ASN_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return insert(in, "PORTAL", null, me.loginId());
    }

    /** 엑셀 올리기도 이 길로(출처 IMPORT) — 검사·행 오류 모양이 같다 */
    long insert(AsnForm in, String source, Long jobId, String by) {
        FieldErrors e = FieldErrors.create();
        AsnValue head = header(in, null, e);
        List<AsnLineValue> lines = head == null ? List.of() : lines(in.lines(), head, e);
        e.throwIfAny();
        String asnNo = numbers.next(SrmNumbers.ASN, by);
        try {
            long id = mapper.insertAsn(asnNo, head, source, jobId, by);
            int no = 1;
            for (AsnLineValue l : lines) {
                mapper.insertLine(id, no++, l, by);
            }
            return id;
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    @Transactional
    public void update(long asnId, AsnForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        AsnRow cur = start(asnId, in.rowVer(), "DRAFT");
        FieldErrors e = FieldErrors.create();
        AsnValue head = header(in, cur, e);
        List<AsnLineValue> lines = head == null ? List.of() : lines(in.lines(), head, e);
        e.throwIfAny();
        try {
            if (mapper.updateAsn(asnId, head, in.rowVer(), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
            mapper.deleteLines(asnId);          // 작성 중 행은 통째로 다시(행 번호는 화면 순서)
            int no = 1;
            for (AsnLineValue l : lines) {
                mapper.insertLine(asnId, no++, l, me.loginId());
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /** 머리 검사 — 협력사는 만들 때만 정한다(고칠 때 바꾸면 행이 모두 틀어짐) */
    private AsnValue header(AsnForm in, AsnRow cur, FieldErrors e) {
        Long custId = null;
        String cd = code(in.custCd());
        if (cur != null) {
            custId = cur.getCustId();
        } else if (cd == null) {
            e.add("custCd", "REQUIRED");
        } else {
            SupplierRef s = cd.length() > 30 ? null : mapper.findSupplier(cd);
            if (s == null) {
                e.add("custCd", "NO_SUCH_CODE");
            } else {
                custId = s.getCustId();
            }
        }
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
        LocalDate ship = date(in.shipDate(), "shipDate", true, e);
        LocalDate eta = date(in.etaDate(), "etaDate", true, e);
        if (ship != null && eta != null && eta.isBefore(ship)) {
            e.add("etaDate", "SRM_ASN_ETA");
        }
        String ref = text(in.supplierRef(), "supplierRef", 60, false, e);
        String memo = text(in.carrierMemo(), "carrierMemo", 200, false, e);
        String remark = text(in.remark(), "remark", 1000, false, e);
        return custId == null ? null : new AsnValue(custId, plant, ship, eta, ref, memo, remark);
    }

    /** 행 검사 — 행 오류는 행 키(배열 순번)로. 발주 행은 같은 협력사·공장, 발행된 원천 SRM 발주의 열린 행 */
    private List<AsnLineValue> lines(List<AsnLineForm> in, AsnValue head, FieldErrors e) {
        List<AsnLineValue> out = new ArrayList<>();
        if (in == null || in.isEmpty()) {
            return out;
        }
        if (in.size() > MAX_LINES) {
            e.add("lines", "TOO_MANY_LINES", MAX_LINES);
            return out;
        }
        Map<Long, PoLineRef> refs = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < in.size(); i++) {
            AsnLineForm f = in.get(i);
            FieldErrors le = FieldErrors.create();
            PoLineRef po = null;
            if (f == null || f.poLineId() == null) {
                le.add("poLineId", "REQUIRED");
            } else {
                po = refs.computeIfAbsent(f.poLineId(), mapper::findPoLine);
                if (po == null) {
                    le.add("poLineId", "NO_SUCH_CODE");
                } else if (po.getCustId() != head.custId()) {
                    le.add("poLineId", "SRM_OTHER_SUPPLIER");
                } else if (head.plantCd() != null && !head.plantCd().equals(po.getPlantCd())) {
                    le.add("poLineId", "SRM_ASN_OTHER_PLANT");
                } else if (!"SRM".equals(po.getSource()) || !PO_OPEN.contains(po.getStatus()) || !"OPEN".equals(po.getLineStatus())) {
                    le.add("poLineId", "SRM_ASN_PO_STATE");
                }
            }
            int scale = po == null || po.getUnitScale() == null ? 6 : Math.min(po.getUnitScale(), 6);
            BigDecimal qty = f == null ? null : decimal(f.qty(), "qty", scale, QTY_INT_DIGITS, true, le);
            String lot = f == null ? null : text(f.supplierLotNo(), "supplierLotNo", 60, false, le);
            LocalDate mfg = f == null ? null : date(f.mfgDate(), "mfgDate", false, le);
            LocalDate exp = f == null ? null : date(f.expiryDate(), "expiryDate", false, le);
            if (mfg != null && exp != null && exp.isBefore(mfg)) {
                le.add("expiryDate", "SRM_ASN_EXPIRY");
            }
            Integer pack = null;
            if (f != null && !blank(f.packCnt())) {
                BigDecimal p = decimal(f.packCnt(), "packCnt", 0, 5, false, le);
                pack = p == null ? null : p.intValueExact();
            }
            String remark = f == null ? null : text(f.remark(), "remark", 500, false, le);
            if (po != null && le.isEmpty() && !seen.add(po.getPoLineId() + "|" + (lot == null ? "" : lot))) {
                le.add("supplierLotNo", "SRM_ASN_DUP_LINE");      // 같은 발주 행·같은 공급사 LOT 은 한 줄(uq_srm_asn_line_lot)
            }
            String key = String.valueOf(i);
            le.list().forEach(x -> e.row(key).add(x.field(), x.code(), x.args()));
            if (le.isEmpty()) {
                out.add(new AsnLineValue(po.getPoLineId(), po.getItemId(), po.getUnitCd(), qty, lot, mfg, exp, pack, remark));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 엑셀 (AsnImportTemplate — 검사는 템플릿이 끝냄)

    long insertImported(AsnValue head, Long jobId, String by) {
        return mapper.insertAsn(numbers.next(SrmNumbers.ASN, by), head, "IMPORT", jobId, by);
    }

    void insertImportedLine(long asnId, int lineNo, AsnLineValue line, String by) {
        mapper.insertLine(asnId, lineNo, line, by);
    }

    /** 엑셀 ASN 확정 — 커밋 직전(행을 다 넣은 뒤). 한도는 트리거 ck_srm_asn_over 가 최종(파일 검사가 먼저 막는다) */
    void sendImported(long asnId, String by) {
        AsnRow cur = mapper.lockAsn(asnId);
        if (mapper.send(asnId, cur.getRowVer(), by) != 1) {
            throw new BusinessException(HbErrorCode.SRM_STATE);
        }
    }

    // ------------------------------------------------------------------ 상태 전이

    /** 확정(SENT) — 행 1개 이상 + 한도(R3-4): 발주 행마다 이 ASN 합 ≤ 받을 수 있는 양 − 다른 살아 있는 ASN 남은 양 */
    @Transactional
    public void send(long asnId, ActionForm in) {
        LoginUser me = me();
        AsnRow cur = start(asnId, rowVer(in), "DRAFT");
        List<AsnLineRow> lines = mapper.findLines(asnId);
        FieldErrors e = FieldErrors.create();
        if (lines.isEmpty()) {
            e.add("lines", "LINES_REQUIRED");
        }
        Map<Long, LimitRow> limit = new HashMap<>();
        mapper.limits(asnId, cur.getPlantCd()).forEach(l -> limit.put(l.getPoLineId(), l));
        for (int i = 0; i < lines.size(); i++) {      // 행 키 = 행 번호 순서의 순번(화면 행 순서와 같다)
            LimitRow l = limit.get(lines.get(i).getPoLineId());
            if (l != null && l.getMine().compareTo(l.getAllowed()) > 0) {
                e.row(String.valueOf(i)).add("qty", "SRM_ASN_OVER", plain(l.getAllowed().max(BigDecimal.ZERO)));
            }
        }
        e.throwIfAny();
        try {
            if (mapper.send(asnId, in.rowVer(), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /** 취소(사유 필수) — 작성 중·확정에서, 받은 것이 없을 때만(트리거 ck_srm_asn_cancel_received 가 최종) */
    @Transactional
    public void cancel(long asnId, ActionForm in) {
        LoginUser me = me();
        AsnRow cur = start(asnId, rowVer(in), "DRAFT", "SENT");
        FieldErrors e = FieldErrors.create();
        String reason = text(in.reason(), "reason", 500, true, e);
        e.throwIfAny();
        change(cur, "CANCELLED", in, reason, me);
    }

    /** 마감 — 덜 왔지만 끝(남은 양은 더 기다리지 않음). 마감 뒤 입고 연결은 게이트가 거부, 되돌림은 받는다 */
    @Transactional
    public void close(long asnId, ActionForm in) {
        LoginUser me = me();
        AsnRow cur = start(asnId, rowVer(in), "SENT", "PARTIAL_RECEIVED", "RECEIVED");
        change(cur, "CLOSED", in, null, me);
    }

    private static int rowVer(ActionForm in) {
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return in.rowVer();
    }

    private AsnRow start(long asnId, int rowVer, String... from) {
        AsnRow cur = mapper.lockAsn(asnId);
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

    private void change(AsnRow cur, String to, ActionForm in, String cancelReason, LoginUser me) {
        try {
            if (mapper.changeStatus(cur.getAsnId(), cur.getStatus(), to, in.rowVer(), cancelReason, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        } catch (RuntimeException ex) {
            if ("ck_srm_asn_cancel_received".equals(PgErrors.constraint(ex).orElse(""))) {
                throw new BusinessException(HbErrorCode.SRM_STATE);
            }
            throw ex;
        }
    }

    private static String plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "uq_srm_asn_supplier_ref" -> fieldError("supplierRef", "DUPLICATE");
            case "uq_srm_asn_line_lot" -> fieldError("lines", "SRM_ASN_DUP_LINE");
            case "fk_srm_asn_plant" -> fieldError("plantCd", "NO_SUCH_CODE");
            case "ck_srm_asn_over" -> fieldError("lines", "SRM_ASN_OVER", "-");
            default -> e;
        };
    }
}
