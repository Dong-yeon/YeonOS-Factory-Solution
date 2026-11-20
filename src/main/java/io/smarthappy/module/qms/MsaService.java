package io.smarthappy.module.qms;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.ibatis.cursor.Cursor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.module.qms.MsaDtos.ActionForm;
import io.smarthappy.module.qms.MsaDtos.Cell;
import io.smarthappy.module.qms.MsaDtos.CellForm;
import io.smarthappy.module.qms.MsaDtos.CellValue;
import io.smarthappy.module.qms.MsaDtos.Choice;
import io.smarthappy.module.qms.MsaDtos.StudyDetail;
import io.smarthappy.module.qms.MsaDtos.StudyExportRequest;
import io.smarthappy.module.qms.MsaDtos.StudyForm;
import io.smarthappy.module.qms.MsaDtos.StudyRow;
import io.smarthappy.module.qms.MsaDtos.StudySearch;
import io.smarthappy.module.qms.MsaDtos.StudySort;
import io.smarthappy.module.qms.MsaDtos.StudyValue;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import io.smarthappy.platform.common.text.VisibleText;
import tools.jackson.databind.json.JsonMapper;

/**
 * MSA GRR 연구 (qms-msa) — P3 QMS Q2b, docs/29 D9.
 *
 * - 작성 중(DRAFT)에는 머리 + 원자료 한 벌을 함께 저장(rowVer). 빈 칸은 저장하지 않는다 — 확정 때 다 차야 한다
 * - 확정(APPROVE) = 머리 잠금 → rowVer·상태 → 원자료가 모양대로 다 찼는지 → MsaCalc 계산 → 결과 스냅샷(jsonb) + %GRR·ndc·판정 칸.
 *   공차 기준은 그 순간 특성 상한 − 하한을 tolerance 에 남긴다. 확정·취소 뒤 불변(트리거)
 * - 상세는 작성 중이라도 다 찼으면 지금 값으로 계산해 보여 준다(preview, 저장 안 함)
 */
@Service
@RequiredArgsConstructor
public class MsaService {

    static final String PROG = "qms-msa";
    static final String RULE = "QMS_MSA";
    static final Set<String> METHODS = Set.of("AVG_RANGE", "ANOVA", "ATTRIBUTE");
    static final Set<String> BASES = Set.of("PROCESS", "TOLERANCE");
    static final Set<String> STATUSES = Set.of("DRAFT", "DONE", "CANCELLED");
    static final List<String> DEFAULT_SORT = List.of("studyDate,desc");
    static final String SEP = "\u001f";
    static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("studyNo", "qms.msa.col.studyNo", 120).withSort().withPriority(1),
            ColumnSpec.of("studyDate", "qms.msa.col.studyDate", ColumnSpec.ColumnType.DATE, 100).withSort().withPriority(1),
            ColumnSpec.text("assetCd", "qms.msa.col.assetCd", 110).withSort().withPriority(1),
            ColumnSpec.text("assetNm", "qms.msa.col.assetNm", 150).withPriority(3).withFlex().withMinWidth(100),
            ColumnSpec.text("charLabel", "qms.msa.col.charLabel", 160).withPriority(2).withFlex().withMinWidth(100),
            ColumnSpec.code("method", "qms.msa.col.method", "MSA_METHOD", 100).withPriority(2),
            ColumnSpec.code("status", "qms.msa.col.status", "MSA_STATUS", 80).withSort().withPriority(1),
            ColumnSpec.dec("grrPct", "qms.msa.col.grrPct", 2, 80).withSort().withPriority(1),
            ColumnSpec.of("ndc", "qms.msa.col.ndc", ColumnSpec.ColumnType.INT, 60).withPriority(2),
            ColumnSpec.code("verdict", "qms.msa.col.verdict", "MSA_VERDICT", 90).withPriority(1));

    private final MsaMapper mapper;
    private final PolicyService policies;
    private final InboxService inbox;
    private final ExcelExportService excel;
    private final NumberingService numbering;
    private final BizClock clock;

    public record Page(List<StudyRow> list, Long total) {
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public Page list(String keyword, String method, String status, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, StudySort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        StudySearch q = search(keyword, method, status, pq);
        List<StudyRow> rows = mapper.findPage(q);
        rows.forEach(MsaService::names);
        return new Page(rows, pq.isWithTotal() ? mapper.count(q) : null);
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    @Transactional(readOnly = true)
    public StudyDetail detail(long id) {
        StudyRow s = find(id);
        List<Cell> cells = mapper.findCells(id);
        int ok = policies.intValue(Policies.QMS_GRR_OK_PCT);
        int limit = policies.intValue(Policies.QMS_GRR_LIMIT_PCT);
        Map<String, Object> result = s.getResultJson() == null ? null : JSON.readValue(s.getResultJson(), new TypeReference<>() { });
        Map<String, Object> preview = null;
        String previewError = null;
        if ("DRAFT".equals(s.getStatus())) {
            if (complete(s, cells)) {
                try {
                    preview = calc(s, cells, ok, limit).map();
                } catch (MsaCalc.MsaException e) {
                    previewError = e.code();
                }
            } else {
                previewError = "MSA_INCOMPLETE";
            }
        }
        s.setResultJson(null);
        return new StudyDetail(s, cells, result, preview, previewError, ok, limit);
    }

    @Transactional(readOnly = true)
    public List<Choice> gauges() {
        return mapper.gaugeChoices();
    }

    @Transactional(readOnly = true)
    public List<Choice> chars() {
        return mapper.charChoices();
    }

    // ------------------------------------------------------------------ 저장

    @Transactional
    public long create(StudyForm form) {
        LoginUser me = SamplingService.me();
        inbox.claimRequestKey("QMS_MSA_CREATE");
        StudyValue v = validate(form, null);
        List<CellValue> cells = cells(form, v);
        String no = numbering.nextOrDefault(RULE, "MSA 연구 번호", "MSA{yyMM}-{seq:3}", "MONTH");
        long id;
        try {
            id = mapper.insert(v, no, me.loginId());
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
        if (!cells.isEmpty()) {
            mapper.insertCells(id, cells, me.loginId());
        }
        return id;
    }

    /** 머리 + 원자료 한 벌 — 원자료는 지우고 다시 넣는다(작성 중만, 트리거가 최종) */
    @Transactional
    public void update(long id, StudyForm form) {
        LoginUser me = SamplingService.me();
        if (form == null || form.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        StudyRow cur = lockDraft(id, form.rowVer());
        StudyValue v = validate(form, cur);
        List<CellValue> cells = cells(form, v);
        try {
            if (mapper.update(id, v, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
        mapper.deleteCells(id);
        if (!cells.isEmpty()) {
            mapper.insertCells(id, cells, me.loginId());
        }
    }

    /** 확정 — 계산 결과를 스냅샷하고 불변 */
    @Transactional
    public void complete(long id, ActionForm req) {
        LoginUser me = SamplingService.me();
        if (req == null || req.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        StudyRow s = lockDraft(id, req.rowVer());
        List<Cell> cells = mapper.findCells(id);
        if (!complete(s, cells)) {
            throw new BusinessException(HbErrorCode.QMS_MSA_INCOMPLETE);
        }
        Calc c;
        try {
            c = calc(s, cells, policies.intValue(Policies.QMS_GRR_OK_PCT), policies.intValue(Policies.QMS_GRR_LIMIT_PCT));
        } catch (MsaCalc.MsaException e) {
            throw SamplingService.fieldError("MSA_NO_TOLERANCE".equals(e.code()) ? "charId" : "cells", e.code());
        }
        int n = mapper.complete(id, req.rowVer(), c.tolerance(), JSON.writeValueAsString(c.map()), c.grrPct(), c.ndc(), c.verdict(),
                me.loginId());
        if (n == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    @Transactional
    public void cancel(long id, ActionForm req) {
        LoginUser me = SamplingService.me();
        if (req == null || req.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        lockDraft(id, req.rowVer());
        if (mapper.cancel(id, req.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    // ------------------------------------------------------------------ 계산

    record Calc(Map<String, Object> map, BigDecimal tolerance, BigDecimal grrPct, Integer ndc, String verdict) {
    }

    /** 저장된 머리·원자료 → MsaCalc. 공차 기준이면 지금 특성 상한 − 하한 */
    Calc calc(StudyRow s, List<Cell> cells, int ok, int limit) {
        int k = s.getAppraiserCnt();
        int n = s.getPartCnt();
        int r = s.getTrialCnt();
        if ("ATTRIBUTE".equals(s.getMethod())) {
            String[][][] d = new String[k][n][r];
            String[] ref = new String[n];
            int refCnt = 0;
            for (Cell c : cells) {
                if (c.getA() == 0) {
                    ref[c.getP() - 1] = c.getV();
                    refCnt++;
                } else {
                    d[c.getA() - 1][c.getP() - 1][c.getT() - 1] = c.getV();
                }
            }
            MsaCalc.AttributeResult a = MsaCalc.attribute(d, refCnt == n ? ref : null);
            return new Calc(MsaCalc.toMap(a), null, null, null, null);
        }
        double[][][] d = new double[k][n][r];
        for (Cell c : cells) {
            d[c.getA() - 1][c.getP() - 1][c.getT() - 1] = new BigDecimal(c.getV()).doubleValue();
        }
        BigDecimal tol = null;
        if ("TOLERANCE".equals(s.getBasis())) {
            if (s.getCharLsl() == null || s.getCharUsl() == null) {
                throw new MsaCalc.MsaException("MSA_NO_TOLERANCE");
            }
            tol = s.getCharUsl().subtract(s.getCharLsl());
        }
        if ("ANOVA".equals(s.getMethod())) { // D9 2차 — 같은 원자료, 분산분석(docs/29_P3 45장)
            MsaCalc.AnovaResult an = MsaCalc.anova(d, s.getBasis(), tol, s.getProcessSigma(), ok, limit);
            return new Calc(MsaCalc.toMap(an), tol, an.core().pctGrr(), an.core().ndc(), an.core().verdict().name());
        }
        MsaCalc.VariableResult v = MsaCalc.averageRange(d, s.getBasis(), tol, s.getProcessSigma(), ok, limit);
        return new Calc(MsaCalc.toMap(v), tol, v.pctGrr(), v.ndc(), v.verdict().name());
    }

    /** 모양대로 다 찼는지 — 계수형 기준값은 전부 있거나 전부 없거나 */
    static boolean complete(StudyRow s, List<Cell> cells) {
        int need = s.getAppraiserCnt() * s.getPartCnt() * s.getTrialCnt();
        long filled = cells.stream().filter(c -> c.getA() > 0 && c.getA() <= s.getAppraiserCnt() && c.getP() <= s.getPartCnt()
                && c.getT() <= s.getTrialCnt()).count();
        long refs = cells.stream().filter(c -> c.getA() == 0).count();
        long outside = cells.size() - filled - refs;
        return filled == need && outside == 0 && (refs == 0 || refs == s.getPartCnt());
    }

    // ------------------------------------------------------------------ 검증

    StudyValue validate(StudyForm in, StudyRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        Long assetId = null;
        String acd = VisibleText.isBlank(in.assetCd()) ? null : in.assetCd().strip().toUpperCase(Locale.ROOT);
        if (acd == null) {
            e.add("assetCd", "REQUIRED");
        } else {
            assetId = mapper.findGaugeId(acd);
            if (assetId == null && cur != null && acd.equals(cur.getAssetCd())) {
                assetId = cur.getAssetId();        // 연구 중 사용 중지된 계측기 — 그대로 두면 저장된다
            } else if (assetId == null) {
                e.add("assetCd", "NO_SUCH_CODE");
            }
        }
        Long charId = in.charId();
        if (charId != null && !(cur != null && charId.equals(cur.getCharId()))) {
            String use = mapper.charUseYn(charId);
            if (use == null) {
                e.add("charId", "NOT_FOUND");
            } else if (!"Y".equals(use)) {
                e.add("charId", "INACTIVE");
            }
        }
        String method = code(in.method(), "method", METHODS, e);
        int k = count(in.appraiserCnt(), "appraiserCnt", MsaCalc.MIN_APPRAISER, MsaCalc.MAX_APPRAISER, e);
        int maxPart = "ATTRIBUTE".equals(method) ? MsaCalc.MAX_PART_ATTRIBUTE : MsaCalc.MAX_PART_VARIABLE;
        int n = count(in.partCnt(), "partCnt", MsaCalc.MIN_PART, maxPart, e);
        int r = count(in.trialCnt(), "trialCnt", MsaCalc.MIN_TRIAL, MsaCalc.MAX_TRIAL, e);
        String basis = null;
        BigDecimal sigma = null;
        if (method != null && !"ATTRIBUTE".equals(method)) { // 계량형(평균·범위법·ANOVA)만 기준·공정 σ
            basis = VisibleText.isBlank(in.basis()) ? "PROCESS" : code(in.basis(), "basis", BASES, e);
            if ("PROCESS".equals(basis) && !VisibleText.isBlank(in.processSigma())) {
                sigma = BomService.decimal(in.processSigma(), "processSigma", 6, 12, true, e);
            }
        }
        List<String> names = in.appraisers() == null ? List.of() : in.appraisers();
        String namesText = null;
        if (names.stream().anyMatch(x -> !VisibleText.isBlank(x))) {
            if (names.size() != k) {
                e.add("appraisers", "MSA_NAMES");
            } else {
                List<String> clean = new ArrayList<>();
                for (String nm : names) {
                    String t = VisibleText.isBlank(nm) ? "" : nm.strip().replace(SEP, "");
                    if (t.length() > 50) {
                        e.add("appraisers", "TOO_LONG", 50);
                    }
                    clean.add(t);
                }
                namesText = String.join(SEP, clean);
            }
        }
        LocalDate date = BomService.date(in.studyDate(), "studyDate", e);
        if (VisibleText.isBlank(in.studyDate())) {
            date = clock.today();
        } else if (date != null && date.isAfter(clock.today())) {
            e.add("studyDate", "FUTURE");
        }
        String remark = VisibleText.optional(in.remark(), "remark", 500, e);
        e.throwIfAny();
        return new StudyValue(assetId, charId, method, k, n, r, basis, sigma, namesText, date, remark, in.rowVer());
    }

    /** 원자료 칸 — 모양 안, 같은 칸 두 번 금지, 방법에 맞는 값. 빈 칸은 버린다. 칸 오류의 행 키 = "a-p-t" */
    List<CellValue> cells(StudyForm in, StudyValue v) {
        List<CellForm> raw = in.cells() == null ? List.of() : in.cells();
        if (raw.size() > 3 * 50 * 3 + 50 || raw.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        boolean attr = "ATTRIBUTE".equals(v.method());
        FieldErrors e = FieldErrors.create();
        Set<String> seen = new HashSet<>();
        List<CellValue> out = new ArrayList<>();
        for (CellForm c : raw) {
            if (c.a() == null || c.p() == null || c.t() == null) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            String key = c.a() + "-" + c.p() + "-" + c.t();
            boolean ref = c.a() == 0;
            if (c.a() < 0 || c.a() > v.appraiserCnt() || c.p() < 1 || c.p() > v.partCnt() || c.t() < 1 || c.t() > v.trialCnt()
                    || (ref && (!attr || c.t() != 1))) {
                e.row(key).add("v", "MSA_CELL");
                continue;
            }
            if (!seen.add(key)) {
                e.row(key).add("v", "DUPLICATE");
                continue;
            }
            if (VisibleText.isBlank(c.v())) {
                continue;
            }
            String s = c.v().strip();
            if (attr) {
                String a = s.toUpperCase(Locale.ROOT);
                if (!"OK".equals(a) && !"NG".equals(a)) {
                    e.row(key).add("v", "CODE");
                } else {
                    out.add(new CellValue(c.a(), c.p(), c.t(), null, a));
                }
            } else {
                FieldErrors one = FieldErrors.create();
                BigDecimal num = BomService.decimal(s, "v", 6, 12, false, one);
                if (!one.isEmpty() || num == null) {
                    e.row(key).add("v", "MSA_NUMBER");
                } else {
                    out.add(new CellValue(c.a(), c.p(), c.t(), num, null));
                }
            }
        }
        e.throwIfAny();
        return out;
    }

    // ------------------------------------------------------------------ 엑셀

    @Transactional(readOnly = true)
    public long export(StudyExportRequest req, HttpServletResponse response) throws IOException {
        StudyExportRequest r = req == null ? new StudyExportRequest(null, null, null, null, null) : req;
        List<ColumnSpec> cols = ColumnSpec.selectExportable(COLUMNS, r.columns());
        StudySearch q = search(r.keyword(), r.method(), r.status(), PageQuery.sortOnly(r.sort(), StudySort.values(), DEFAULT_SORT));
        List<String> keys = new ArrayList<>();
        if (q.getKeywordLike() != null) {
            keys.add("keyword");
        }
        if (q.getStatus() != null) {
            keys.add("status");
        }
        return excel.export(response, new ExportJob(PROG, "prog.qmsMsa", cols, mapper.count(q), keys), w -> {
            try (Cursor<StudyRow> c = mapper.cursor(q)) {
                c.forEach(w);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    // ------------------------------------------------------------------ 공통

    private StudyRow find(long id) {
        StudyRow s = mapper.findById(id);
        if (s == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return names(s);
    }

    /** 머리 잠금 → 다시 읽기 → rowVer → 작성 중 */
    private StudyRow lockDraft(long id, int rowVer) {
        if (mapper.lockStudy(id) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        StudyRow s = find(id);
        if (s.getRowVer() != rowVer) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (!"DRAFT".equals(s.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_MSA_NOT_DRAFT);
        }
        return s;
    }

    private static StudyRow names(StudyRow s) {
        String t = s.getAppraiserNmsText();
        s.setAppraisers(t == null || t.isEmpty() ? List.of() : List.of(t.split(SEP, -1)));
        s.setAppraiserNmsText(null);
        return s;
    }

    private StudySearch search(String keyword, String method, String status, PageQuery pq) {
        String kw = VisibleText.isBlank(keyword) ? null : keyword.strip();
        String m = VisibleText.isBlank(method) ? null : method.strip();
        String st = VisibleText.isBlank(status) ? null : status.strip();
        if ((kw != null && kw.length() > 100) || (m != null && !METHODS.contains(m)) || (st != null && !STATUSES.contains(st))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return new StudySearch(kw == null ? null : "%" + BomService.likeEscape(kw) + "%", m, st, pq);
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        if ("fk_qms_msa_study_asset_comp".equals(c)) {
            return SamplingService.fieldError("assetCd", "NO_SUCH_CODE");
        }
        if ("fk_qms_msa_study_char_comp".equals(c)) {
            return SamplingService.fieldError("charId", "INACTIVE");
        }
        return e;
    }

    private static int count(Integer v, String field, int min, int max, FieldErrors e) {
        if (v == null) {
            e.add(field, "REQUIRED");
            return min;
        }
        if (v < min || v > max) {
            e.add(field, "RANGE", min, max);
            return min;
        }
        return v;
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
}
