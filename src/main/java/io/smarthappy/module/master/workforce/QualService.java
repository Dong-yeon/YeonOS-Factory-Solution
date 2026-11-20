package io.smarthappy.module.master.workforce;

import static io.smarthappy.module.master.workforce.ShiftService.blank;
import static io.smarthappy.module.master.workforce.ShiftService.fieldError;
import static io.smarthappy.module.master.workforce.ShiftService.me;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.workforce.WorkforceDtos.QualForm;
import io.smarthappy.module.master.workforce.WorkforceDtos.QualRow;
import io.smarthappy.module.master.workforce.WorkforceDtos.QualSort;
import io.smarthappy.module.master.workforce.WorkforceDtos.ReqForm;
import io.smarthappy.module.master.workforce.WorkforceDtos.ReqRow;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/** 자격 관리 (mst-qual) — 자격 + 공정별 필요 자격(P2 1단계, docs/25 4.2) */
@Service
@RequiredArgsConstructor
public class QualService {

    static final String PROG = "mst-qual";
    static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,19}$");
    static final Set<String> GATE_MODES = Set.of("WARN", "BLOCK");

    private final WorkforceMapper mapper;
    private final InboxService inbox;
    private final BizClock clock;
    private final PolicyService policies;

    /** 그리드 기본 정렬 = 기존 목록 순서 */
    static final List<String> DEFAULT_SORT = List.of("sortNo,asc", "qualCd,asc");

    /** 유효 개월이 없으면 "만료 없음" — 글자는 화면이 붙인다(format, DTO getValidText) */
    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("qualCd", "qual.col.qualCd", 110).withSort().withPriority(1),
            ColumnSpec.text("qualNm", "qual.col.qualNm", 160).withSort().withPriority(1).withFlex().withMinWidth(100),
            ColumnSpec.text("validText", "qual.col.validMonths", 90).withSort().withPriority(2),
            ColumnSpec.of("holderCnt", "qual.col.holderCnt", ColumnSpec.ColumnType.INT, 70).withSort().withPriority(2),
            ColumnSpec.of("procCnt", "qual.col.procCnt", ColumnSpec.ColumnType.INT, 80).withSort().withPriority(3),
            ColumnSpec.of("useYn", "qual.col.useYn", ColumnSpec.ColumnType.YN, 70).withPriority(2));

    public record Page(List<QualRow> list, Long total) {
    }

    /** 그리드(UI 단계 3 ①, 결정 docs/29 #45) — 기존 목록과 같은 조건 + 페이지·정렬·건수 */
    @Transactional(readOnly = true)
    public Page grid(String useYn, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, QualSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        if (useYn != null && !useYn.isBlank() && !"Y".equals(useYn) && !"N".equals(useYn)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String yn = blank(useYn) ? null : useYn;
        return new Page(mapper.findQualPage(yn, clock.today(), pq), pq.isWithTotal() ? mapper.countQuals(yn) : null);
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    @Transactional(readOnly = true)
    public List<QualRow> list(String useYn) {
        if (useYn != null && !useYn.isBlank() && !"Y".equals(useYn) && !"N".equals(useYn)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return mapper.findQuals(blank(useYn) ? null : useYn, clock.today());
    }

    @Transactional
    public long create(QualForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_QUAL_CREATE");
        QualForm f = validate(in, null);
        try {
            return mapper.insertQual(f, me.loginId());
        } catch (DuplicateKeyException e) {
            throw fieldError("qualCd", "DUPLICATE");
        }
    }

    @Transactional
    public void update(long qualId, QualForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        QualRow cur = mapper.findQual(qualId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (mapper.updateQual(qualId, validate(in, cur), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    // ------------------------------------------------------------------ 공정별 필요 자격

    @Transactional(readOnly = true)
    public List<ReqRow> reqs(Long qualId, String procCd) {
        Long procId = null;
        if (!blank(procCd)) {
            procId = mapper.findProcId(procCd.strip().toUpperCase(Locale.ROOT), false);
            if (procId == null) {
                throw new BusinessException(HbErrorCode.NOT_FOUND);
            }
        }
        if (qualId != null && mapper.findQual(qualId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return mapper.findReqs(qualId, procId);
    }

    /** 새 요구 — 같은 공정·자격이 사용 중지로 있으면 그 행을 다시 쓰라고(DUPLICATE) */
    @Transactional
    public long createReq(ReqForm in) {
        LoginUser me = me();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        Long procId = null;
        if (blank(in.procCd())) {
            e.add("procCd", "REQUIRED");
        } else {
            String cd = in.procCd().strip().toUpperCase(Locale.ROOT);
            procId = cd.length() > 20 ? null : mapper.findProcId(cd, false);
            if (procId == null) {
                e.add("procCd", "NO_SUCH_CODE");
            } else if (mapper.findProcId(cd, true) == null) {
                e.add("procCd", "INACTIVE");
            }
        }
        QualRow q = null;
        if (blank(in.qualCd())) {
            e.add("qualCd", "REQUIRED");
        } else {
            String cd = in.qualCd().strip().toUpperCase(Locale.ROOT);
            q = cd.length() > 20 ? null : mapper.findQualByCd(cd);
            if (q == null) {
                e.add("qualCd", "NO_SUCH_CODE");
            } else if (!"Y".equals(q.getUseYn())) {
                e.add("qualCd", "INACTIVE");
            }
        }
        String mode = blank(in.gateMode()) ? "WARN" : in.gateMode();
        if (!GATE_MODES.contains(mode)) {
            e.add("gateMode", "CODE");
        }
        e.throwIfAny();
        try {
            return mapper.insertReq(procId, q.getQualId(), mode, me.loginId());
        } catch (DuplicateKeyException ex) {
            throw fieldError("qualCd", "DUPLICATE");
        }
    }

    @Transactional
    public void updateReq(long reqId, ReqForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        ReqRow cur = mapper.findReq(reqId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        FieldErrors e = FieldErrors.create();
        String mode = blank(in.gateMode()) ? cur.getGateMode() : in.gateMode();
        if (!GATE_MODES.contains(mode)) {
            e.add("gateMode", "CODE");
        }
        String use = blank(in.useYn()) ? cur.getUseYn() : in.useYn();
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        }
        e.throwIfAny();
        try {
            if (mapper.updateReq(reqId, mode, use, in.rowVer(), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            // 다시 사용(N→Y)인데 그 사이 자격이 사용 중지됨
            if (PgErrors.constraint(ex).filter("fk_mst_proc_qual_req_qual_comp"::equals).isPresent()) {
                throw fieldError("qualCd", "INACTIVE");
            }
            throw ex;
        }
    }

    private QualForm validate(QualForm in, QualRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String cd = cur != null ? cur.getQualCd() : blank(in.qualCd()) ? null : in.qualCd().strip().toUpperCase(Locale.ROOT);
        if (cur != null && !blank(in.qualCd()) && !cur.getQualCd().equals(in.qualCd().strip().toUpperCase(Locale.ROOT))) {
            e.add("qualCd", "FIXED");
        } else if (cd == null) {
            e.add("qualCd", "REQUIRED");
        } else if (!CODE.matcher(cd).matches()) {
            e.add("qualCd", "FORMAT");
        }
        String nm = WorkerService.text(in.qualNm(), "qualNm", 100, true, e);
        Integer months = in.validMonths();
        if (months != null && (months < 1 || months > 600)) {
            e.add("validMonths", "RANGE", 1, 600);
        }
        int sort = in.sortNo() == null ? 0 : in.sortNo();
        if (sort < 0 || sort > 99999) {
            e.add("sortNo", "CODE");
        }
        String use = blank(in.useYn()) ? "Y" : in.useYn();
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        }
        String remark = WorkerService.text(in.remark(), "remark", 500, false, e);
        e.throwIfAny();
        return new QualForm(cd, nm, months, sort, use, remark, cur == null ? null : in.rowVer());
    }
}
