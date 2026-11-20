package io.smarthappy.module.qms;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.ibatis.cursor.Cursor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.module.qms.QmsDtos.AqlView;
import io.smarthappy.module.qms.QmsDtos.PreviewRequest;
import io.smarthappy.module.qms.QmsDtos.PreviewResult;
import io.smarthappy.module.qms.QmsDtos.SamplingExportRequest;
import io.smarthappy.module.qms.QmsDtos.SamplingForm;
import io.smarthappy.module.qms.QmsDtos.SamplingRow;
import io.smarthappy.module.qms.QmsDtos.SamplingSearch;
import io.smarthappy.module.qms.QmsDtos.SamplingSort;
import io.smarthappy.module.qms.QmsDtos.SamplingValue;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import jakarta.servlet.http.HttpServletResponse;
import io.smarthappy.platform.common.text.VisibleText;
import lombok.RequiredArgsConstructor;

/**
 * 샘플링 계획 · AQL 표 (qms-sampling) — P3 QMS Q1, docs/29.
 *
 * - 방식 FIXED·ALL·PERCENT 는 Ac 를 직접(Re = Ac + 1), AQL 은 표에서 n·Ac·Re. 방식에 안 쓰는 칸은 저장하지 않는다(무시)
 * - 확정·폐기 검사계획이 쓰는 계획은 방식·수치를 바꾸지 않는다(그 계획의 "그때 기준" 이 바뀜) — 이름·순서·사용 여부만. 트리거가 최종
 * - AQL 계획은 저장 때 그 표준·검사 수준의 표 1, 엄격도·AQL 의 표 2 줄이 있어야 한다(오타 방지). 판정은 SamplingPlan(순수 함수)
 * - AQL 표 자체는 엑셀 올리기(AqlImportTemplates)로만 — 시드 없음(D3)
 */
@Service
@RequiredArgsConstructor
public class SamplingService {

    static final String PROG = "qms-sampling";
    static final Pattern PLAN_CD = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,19}$");
    static final Pattern STD_CD = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,19}$");
    static final Set<String> METHODS = Set.of("FIXED", "ALL", "PERCENT", "AQL");
    static final Set<String> LEVELS = Set.of("I", "II", "III", "S1", "S2", "S3", "S4");
    static final Set<String> SEVERITIES = Set.of("NORMAL", "TIGHTENED", "REDUCED");
    static final List<String> DEFAULT_SORT = List.of("sortNo,asc", "planCd,asc");
    static final int MAX_N = 1_000_000;

    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("planCd", "qms.sampling.col.planCd", 110).withSort().withPriority(1),
            ColumnSpec.text("planNm", "qms.sampling.col.planNm", 180).withSort().withPriority(1).withFlex().withMinWidth(120),
            ColumnSpec.code("method", "qms.sampling.col.method", "SAMPLING_METHOD", 90).withSort().withPriority(1),
            ColumnSpec.of("fixedN", "qms.sampling.col.fixedN", ColumnSpec.ColumnType.INT, 70).withPriority(2),
            ColumnSpec.dec("pct", "qms.sampling.col.pct", 2, 70).withPriority(2).withMinScale(0),
            ColumnSpec.text("stdCd", "qms.sampling.col.stdCd", 100).withPriority(2),
            ColumnSpec.code("inspLevel", "qms.sampling.col.inspLevel", "INSP_LEVEL", 70).withPriority(3),
            ColumnSpec.code("severity", "qms.sampling.col.severity", "AQL_SEVERITY", 80).withPriority(3),
            ColumnSpec.dec("aql", "qms.sampling.col.aql", 3, 70).withPriority(2).withMinScale(0),
            ColumnSpec.of("ac", "qms.sampling.col.ac", ColumnSpec.ColumnType.INT, 50).withPriority(2),
            ColumnSpec.of("lockedCnt", "qms.sampling.col.lockedCnt", ColumnSpec.ColumnType.INT, 80).withPriority(3),
            ColumnSpec.of("useYn", "qms.sampling.col.useYn", ColumnSpec.ColumnType.YN, 60).withPriority(2),
            ColumnSpec.of("updatedAt", "qms.sampling.col.updatedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(3));

    private final SamplingMapper mapper;
    private final PolicyService policies;
    private final InboxService inbox;
    private final ExcelExportService excel;

    public record Page(List<SamplingRow> list, Long total) {
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public Page list(String keyword, String method, Boolean withUnused, Integer page, Integer size, List<String> sort,
            Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, SamplingSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        SamplingSearch q = search(keyword, method, withUnused, pq);
        return new Page(mapper.findPage(q), pq.isWithTotal() ? mapper.count(q) : null);
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    @Transactional(readOnly = true)
    public SamplingRow detail(long id) {
        SamplingRow r = mapper.findById(id);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return r;
    }

    @Transactional(readOnly = true)
    public List<String> stds() {
        return mapper.findStds();
    }

    @Transactional(readOnly = true)
    public AqlView aql(String stdCd) {
        String std = VisibleText.isBlank(stdCd) ? null : stdCd.strip().toUpperCase(Locale.ROOT);
        if (std == null || !STD_CD.matcher(std).matches()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return new AqlView(std, mapper.findLetters(std, false), mapper.findPlanRows(std, false));
    }

    // ------------------------------------------------------------------ 저장

    @Transactional
    public long create(SamplingForm form) {
        LoginUser me = me();
        inbox.claimRequestKey("QMS_SAMPLING_CREATE");
        SamplingValue v = validate(form, null);
        try {
            return mapper.insert(v, me.loginId());
        } catch (DuplicateKeyException e) {
            throw fieldError("planCd", "DUPLICATE");
        }
    }

    @Transactional
    public void update(long id, SamplingForm form) {
        LoginUser me = me();
        if (form == null || form.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        SamplingRow cur = detail(id);
        SamplingValue v = validate(form, cur);
        try {
            if (mapper.update(id, v, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException e) {
            // 검사 뒤 다른 트랜잭션이 이 계획을 쓰는 검사계획을 확정한 경우 — 트리거가 막는다
            if (PgErrors.constraint(e).filter("ck_qms_sampling_plan_in_use"::equals).isPresent()) {
                throw fieldError("method", "SAMPLING_LOCKED");
            }
            throw e;
        }
    }

    /**
     * 화면 입력 검증 — 신규(cur = null) 또는 수정. 방식에 안 쓰는 칸은 버린다.
     * 확정 검사계획이 쓰는 계획(lockedCnt > 0)이면 방식·수치 변경을 칸 오류로 먼저 알린다
     */
    SamplingValue validate(SamplingForm in, SamplingRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String cd = VisibleText.isBlank(in.planCd()) ? null : in.planCd().strip().toUpperCase(Locale.ROOT);
        if (cur != null) {
            if (cd != null && !cd.equals(cur.getPlanCd())) {
                e.add("planCd", "FIXED");
            }
            cd = cur.getPlanCd();
        } else if (cd == null) {
            e.add("planCd", "REQUIRED");
        } else if (!PLAN_CD.matcher(cd).matches()) {
            e.add("planCd", "FORMAT");
        }
        String nm = VisibleText.reason(in.planNm(), "planNm", 1, 100, e);
        String method = VisibleText.isBlank(in.method()) ? null : in.method().strip();
        if (method == null) {
            e.add("method", "REQUIRED");
        } else if (!METHODS.contains(method)) {
            e.add("method", "CODE");
            method = null;
        }
        Integer fixedN = null;
        BigDecimal pct = null;
        String std = null;
        String level = null;
        String severity = null;
        BigDecimal aql = null;
        Integer ac = null;
        if ("AQL".equals(method)) {
            std = VisibleText.isBlank(in.stdCd()) ? null : in.stdCd().strip().toUpperCase(Locale.ROOT);
            if (std == null) {
                e.add("stdCd", "REQUIRED");
            } else if (!STD_CD.matcher(std).matches()) {
                e.add("stdCd", "FORMAT");
                std = null;
            }
            level = code(in.inspLevel(), "inspLevel", LEVELS, e);
            severity = code(in.severity(), "severity", SEVERITIES, e);
            aql = BomService.decimal(in.aql(), "aql", 3, 4, true, e);
            if (VisibleText.isBlank(in.aql())) {
                e.add("aql", "REQUIRED");
            } else if (aql != null && aql.compareTo(BigDecimal.valueOf(1000)) > 0) {
                e.add("aql", "RANGE", "0.001", "1000");
                aql = null;
            }
            // 오타 방지: 그 표준의 표가 실제로 있어야 한다(판정 때 "표에 없음" 으로 늦게 알게 되지 않게)
            if (std != null && level != null && !mapper.hasLetters(std, level)) {
                e.add("inspLevel", "NO_AQL_LETTER");
            }
            if (std != null && severity != null && aql != null && !mapper.hasPlanRows(std, severity, aql)) {
                e.add("aql", "NO_AQL_PLAN");
            }
        } else if (method != null) {
            ac = in.ac() == null ? 0 : in.ac();
            if (ac < 0 || ac > MAX_N - 1) {
                e.add("ac", "RANGE", 0, MAX_N - 1);
                ac = null;
            }
            if ("FIXED".equals(method)) {
                fixedN = in.fixedN();
                if (fixedN == null) {
                    e.add("fixedN", "REQUIRED");
                } else if (fixedN < 1 || fixedN > MAX_N) {
                    e.add("fixedN", "RANGE", 1, MAX_N);
                    fixedN = null;
                } else if (ac != null && ac + 1 > fixedN) {
                    e.add("ac", "AC_TOO_BIG");
                }
            } else if ("PERCENT".equals(method)) {
                pct = BomService.decimal(in.pct(), "pct", 2, 3, true, e);
                if (VisibleText.isBlank(in.pct())) {
                    e.add("pct", "REQUIRED");
                } else if (pct != null && pct.compareTo(BigDecimal.valueOf(100)) > 0) {
                    e.add("pct", "RANGE", "0.01", "100");
                    pct = null;
                }
            }
        }
        int sort = in.sortNo() == null ? 0 : in.sortNo();
        if (sort < 0 || sort > 99999) {
            e.add("sortNo", "CODE");
        }
        String useYn = VisibleText.isBlank(in.useYn()) ? "Y" : in.useYn().strip();
        if (!"Y".equals(useYn) && !"N".equals(useYn)) {
            e.add("useYn", "CODE");
        }
        String remark = VisibleText.optional(in.remark(), "remark", 500, e);
        e.throwIfAny();
        SamplingValue v = new SamplingValue(cd, nm, method, fixedN, pct, std, level, severity, aql, ac,
                ac == null ? null : ac + 1, sort, useYn, remark, in.rowVer());
        if (cur != null && cur.getLockedCnt() > 0 && rulesChanged(cur, v)) {
            throw fieldError("method", "SAMPLING_LOCKED");
        }
        return v;
    }

    private static boolean rulesChanged(SamplingRow o, SamplingValue v) {
        return !Objects.equals(o.getMethod(), v.method()) || !Objects.equals(o.getFixedN(), v.fixedN())
                || !sameNum(o.getPct(), v.pct()) || !Objects.equals(o.getStdCd(), v.stdCd())
                || !Objects.equals(o.getInspLevel(), v.inspLevel()) || !Objects.equals(o.getSeverity(), v.severity())
                || !sameNum(o.getAql(), v.aql()) || !Objects.equals(o.getAc(), v.ac());
    }

    // ------------------------------------------------------------------ 판정 미리보기

    /** 로트 크기(+ 불량 수) → n·Ac·Re(+ 판정). 저장된 계획 또는 화면에서 고치는 중인 값 */
    @Transactional(readOnly = true)
    public PreviewResult preview(PreviewRequest req) {
        if (req == null || (req.samplingPlanId() == null) == (req.form() == null)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        if (req.lotQty() == null) {
            e.add("lotQty", "REQUIRED");
        }
        e.throwIfAny();
        SamplingPlan plan;
        if (req.samplingPlanId() != null) {
            plan = plan(detail(req.samplingPlanId()));
        } else {
            SamplingValue v = validatePreviewForm(req.form());
            plan = SamplingPlan.of(v.method(), v.fixedN(), v.pct(), v.ac(), v.inspLevel(), v.severity(), v.aql(),
                    "AQL".equals(v.method()) ? table(v.stdCd()) : null);
        }
        try {
            SamplingPlan.Sample s = plan.sampleSize(req.lotQty());
            String verdict = req.defects() == null ? null : s.judge(req.defects()).name();
            return new PreviewResult(s.lotQty(), s.sampleSize(), s.ac(), s.re(), s.codeLetter(), s.all(), req.defects(), verdict);
        } catch (SamplingPlan.SamplingException ex) {
            throw fieldError("DEFECTS".equals(ex.code()) ? "defects" : "lotQty", ex.code());
        }
    }

    /** 미리보기용 검증 — 저장 검증과 같되 코드·이름은 묻지 않는다 */
    private SamplingValue validatePreviewForm(SamplingForm f) {
        return validate(new SamplingForm("PREVIEW", "preview", f.method(), f.fixedN(), f.pct(), f.stdCd(), f.inspLevel(),
                f.severity(), f.aql(), f.ac(), null, "Y", null, null), null);
    }

    /** 저장된 계획 → 순수 판정 객체 (검사계획·2부가 같은 경로) */
    SamplingPlan plan(SamplingRow r) {
        return SamplingPlan.of(r.getMethod(), r.getFixedN(), r.getPct(), r.getAc(), r.getInspLevel(), r.getSeverity(), r.getAql(),
                "AQL".equals(r.getMethod()) ? table(r.getStdCd()) : null);
    }

    /** 표준 하나의 사용 중 표 */
    AqlTable table(String stdCd) {
        List<AqlTable.Letter> letters = new ArrayList<>();
        mapper.findLetters(stdCd, true).forEach(l -> letters.add(new AqlTable.Letter(l.getInspLevel(), l.getLotFrom(), l.getLotTo(),
                l.getCodeLetter())));
        List<AqlTable.PlanRow> rows = new ArrayList<>();
        mapper.findPlanRows(stdCd, true).forEach(r -> rows.add(new AqlTable.PlanRow(r.getSeverity(), r.getCodeLetter(), r.getAql(),
                r.getSampleSize(), r.getAc(), r.getRe())));
        return new AqlTable(stdCd, letters, rows);
    }

    // ------------------------------------------------------------------ 엑셀

    @Transactional(readOnly = true)
    public long export(SamplingExportRequest req, HttpServletResponse response) throws IOException {
        SamplingExportRequest r = req == null ? new SamplingExportRequest(null, null, null, null, null) : req;
        List<ColumnSpec> cols = ColumnSpec.selectExportable(COLUMNS, r.columns());
        SamplingSearch q = search(r.keyword(), r.method(), r.withUnused(),
                PageQuery.sortOnly(r.sort(), SamplingSort.values(), DEFAULT_SORT));
        List<String> keys = new ArrayList<>();
        if (q.getKeywordLike() != null) {
            keys.add("keyword");
        }
        if (q.getMethod() != null) {
            keys.add("method");
        }
        return excel.export(response, new ExportJob(PROG, "prog.qmsSampling", cols, mapper.count(q), keys), w -> {
            try (Cursor<SamplingRow> c = mapper.cursor(q)) {
                c.forEach(w);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    // ------------------------------------------------------------------ 공통

    private SamplingSearch search(String keyword, String method, Boolean withUnused, PageQuery pq) {
        String kw = VisibleText.isBlank(keyword) ? null : keyword.strip();
        if ((kw != null && kw.length() > 100) || (!VisibleText.isBlank(method) && !METHODS.contains(method))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return new SamplingSearch(kw == null ? null : "%" + BomService.likeEscape(kw) + "%", VisibleText.isBlank(method) ? null : method,
                Boolean.TRUE.equals(withUnused), pq);
    }

    private static String code(String v, String field, Set<String> allowed, FieldErrors e) {
        if (VisibleText.isBlank(v)) {
            e.add(field, "REQUIRED");
            return null;
        }
        String s = v.strip();
        if (!allowed.contains(s)) {
            e.add(field, "CODE");
            return null;
        }
        return s;
    }

    static boolean sameNum(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    static BusinessException fieldError(String field, String code) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code).list());
    }

    static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
