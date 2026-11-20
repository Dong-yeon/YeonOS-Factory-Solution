package io.smarthappy.module.cms;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.cms.CmsDtos.Created;
import io.smarthappy.module.cms.CmsDtos.IdOption;
import io.smarthappy.module.cms.CmsDtos.Option;
import io.smarthappy.module.cms.CmsDtos.TargetRow;
import io.smarthappy.module.cms.CmsPmDtos.BulkForm;
import io.smarthappy.module.cms.CmsPmDtos.BulkResult;
import io.smarthappy.module.cms.CmsPmDtos.GenResult;
import io.smarthappy.module.cms.CmsPmDtos.ItemForm;
import io.smarthappy.module.cms.CmsPmDtos.ItemRow;
import io.smarthappy.module.cms.CmsPmDtos.PlanExport;
import io.smarthappy.module.cms.CmsPmDtos.PlanForm;
import io.smarthappy.module.cms.CmsPmDtos.PlanRow;
import io.smarthappy.module.cms.CmsPmDtos.PreviewForm;
import io.smarthappy.module.cms.CmsPmDtos.ResultsForm;
import io.smarthappy.module.cms.CmsPmDtos.RunAction;
import io.smarthappy.module.cms.CmsPmDtos.RunExport;
import io.smarthappy.module.cms.CmsPmDtos.RunRow;
import io.smarthappy.module.cms.CmsPmDtos.TmplForm;
import io.smarthappy.module.cms.CmsPmDtos.TmplRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** CMS 1부 C2 API — 점검표·예방점검 계획·점검 실행 (docs/33 4.3) */
@RestController
@RequiredArgsConstructor
public class CmsPmApiController {

    private static final String TMPL = "/api/cms/chk-tmpls";
    private static final String PLAN = "/api/cms/pm-plans";
    private static final String RUN = "/api/cms/chk-runs";
    private static final String MWO = "/api/cms/mwos";

    private final ChkTmplService tmpls;
    private final PmPlanService plans;
    private final ChkRunService runs;
    private final PmGenerator generator;
    private final MwoService mwos;
    private final CmsPmMapper pmMapper;
    private final CmsMapper mapper;
    private final UserDirectory users;
    private final MessageSource messages;

    // ------------------------------------------------------------------ 점검표

    @GetMapping(TMPL)
    @ProgAuth(prog = ChkTmplService.PROG, action = Action.SEARCH)
    public ApiResponse<List<TmplRow>> tmpls(@RequestParam(required = false) String useYn, @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(tmpls.list(useYn, keyword));
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(TMPL + "/grid")
    @ProgAuth(prog = ChkTmplService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> tmplGrid(@RequestParam(required = false) String useYn, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");   // 쉼표로 쪼개지 않게 원래 값 그대로
        var p = tmpls.grid(useYn, keyword, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(TMPL + "/columns")
    @ProgAuth(prog = ChkTmplService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> tmplColumns(Locale locale) {
        List<Map<String, Object>> cols = tmpls.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** 엑셀(C23) — grid 와 같은 조건·정렬·보이는 열 */
    @PostMapping(TMPL + "/export")
    @ProgAuth(prog = ChkTmplService.PROG, action = Action.EXCEL)
    public void tmplExport(@RequestBody(required = false) CmsPmDtos.TmplExport req, HttpServletResponse response) throws IOException {
        tmpls.export(req, response);
    }

    @GetMapping(TMPL + "/classes")
    @ProgAuth(prog = ChkTmplService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<IdOption>> tmplClasses() {
        return ApiResponse.ok(mapper.findEquipClasses());
    }

    @GetMapping(TMPL + "/{tmplId}")
    @ProgAuth(prog = ChkTmplService.PROG, action = Action.SEARCH)
    public ApiResponse<TmplRow> tmpl(@PathVariable long tmplId) {
        return ApiResponse.ok(tmpls.get(tmplId));
    }

    @GetMapping(TMPL + "/{tmplId}/items")
    @ProgAuth(prog = ChkTmplService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ItemRow>> items(@PathVariable long tmplId) {
        return ApiResponse.ok(tmpls.items(tmplId));
    }

    @PostMapping(TMPL)
    @ProgAuth(prog = ChkTmplService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createTmpl(@RequestBody TmplForm form) {
        return ApiResponse.ok(tmpls.create(form));
    }

    @PutMapping(TMPL + "/{tmplId}")
    @ProgAuth(prog = ChkTmplService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateTmpl(@PathVariable long tmplId, @RequestBody TmplForm form) {
        tmpls.update(tmplId, form);
        return ApiResponse.ok("OK");
    }

    /** 항목 한 벌 저장 */
    @PutMapping(TMPL + "/{tmplId}/items")
    @ProgAuth(prog = ChkTmplService.PROG, action = Action.SAVE)
    public ApiResponse<String> saveItems(@PathVariable long tmplId, @RequestParam(required = false) Integer rowVer,
            @RequestBody List<ItemForm> items) {
        tmpls.saveItems(tmplId, rowVer, items);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 계획

    public record PlanOptions(List<Option> plants, List<UserOption> users, List<TmplRow> tmpls, List<IdOption> classes) {
    }

    @GetMapping(PLAN + "/options")
    @ProgAuth(prog = PmPlanService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<PlanOptions> planOptions() {
        return ApiResponse.ok(new PlanOptions(mapper.findPlants(), users.activeUsers(), pmMapper.findTmpls("Y", null),
                mapper.findEquipClasses()));
    }

    @GetMapping(PLAN + "/targets")
    @ProgAuth(prog = PmPlanService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<TargetRow>> planTargets(@RequestParam(required = false) String keyword) {
        if (keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String k = keyword == null || keyword.isBlank() ? null : CmsSupport.like(keyword.strip().toUpperCase(Locale.ROOT));
        return ApiResponse.ok(mapper.findTargets(k, null, true, 50));
    }

    @GetMapping(PLAN)
    @ProgAuth(prog = PmPlanService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PlanRow>> plans(@RequestParam(required = false) String plantCd,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String useYn) {
        return ApiResponse.ok(plans.list(plantCd, keyword, useYn, null));
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(PLAN + "/grid")
    @ProgAuth(prog = PmPlanService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> planGrid(@RequestParam(required = false) String plantCd,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String useYn,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");   // 쉼표로 쪼개지 않게 원래 값 그대로
        var p = plans.grid(plantCd, keyword, useYn, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(PLAN + "/columns")
    @ProgAuth(prog = PmPlanService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> planColumns(Locale locale) {
        List<Map<String, Object>> cols = plans.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** 엑셀(C16) — grid 와 같은 조건·정렬·보이는 열 */
    @PostMapping(PLAN + "/export")
    @ProgAuth(prog = PmPlanService.PROG, action = Action.EXCEL)
    public void planExport(@RequestBody(required = false) PlanExport req, HttpServletResponse response) throws IOException {
        plans.export(req, response);
    }

    @GetMapping(PLAN + "/{planId}")
    @ProgAuth(prog = PmPlanService.PROG, action = Action.SEARCH)
    public ApiResponse<PlanRow> plan(@PathVariable long planId) {
        return ApiResponse.ok(plans.get(planId));
    }

    @PostMapping(PLAN)
    @ProgAuth(prog = PmPlanService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createPlan(@RequestBody PlanForm form) {
        return ApiResponse.ok(plans.create(form));
    }

    @PutMapping(PLAN + "/{planId}")
    @ProgAuth(prog = PmPlanService.PROG, action = Action.SAVE)
    public ApiResponse<String> updatePlan(@PathVariable long planId, @RequestBody PlanForm form) {
        plans.update(planId, form);
        return ApiResponse.ok("OK");
    }

    /** 규칙 → 앞으로 12 회(저장 전에도) */
    /** 달력 보기(C27, docs/33 13.14) — 공장 × 월, 읽기 전용 */
    @GetMapping(PLAN + "/calendar")
    @ProgAuth(prog = PmPlanService.PROG, action = Action.SEARCH)
    public ApiResponse<CmsPmDtos.CalView> calendar(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String month) {
        return ApiResponse.ok(plans.calendar(plantCd, month));
    }

    @PostMapping(PLAN + "/preview")
    @ProgAuth(prog = PmPlanService.PROG, action = Action.SEARCH)
    public ApiResponse<List<String>> preview(@RequestBody PreviewForm form) {
        return ApiResponse.ok(plans.preview(form));
    }

    @PostMapping(PLAN + "/bulk")
    @ProgAuth(prog = PmPlanService.PROG, action = Action.SAVE)
    public ApiResponse<BulkResult> bulk(@RequestBody BulkForm form) {
        return ApiResponse.ok(plans.bulk(form));
    }

    /** 지금 예정 생성 — 정기 작업과 같은 함수 */
    @PostMapping(PLAN + "/generate")
    @ProgAuth(prog = PmPlanService.PROG, action = Action.SAVE)
    @Transactional
    public ApiResponse<GenResult> generate() {
        return ApiResponse.ok(generator.generate(CmsSupport.me().loginId()));
    }

    // ------------------------------------------------------------------ 점검 실행

    @GetMapping(RUN)
    @ProgAuth(prog = ChkRunService.PROG, action = Action.SEARCH)
    public ApiResponse<List<RunRow>> runList(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String execMode,
            @RequestParam(required = false) String plantCd, @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(runs.list(from, to, status, execMode, plantCd, keyword, null, null));
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(RUN + "/grid")
    @ProgAuth(prog = ChkRunService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> runGrid(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String status, @RequestParam(required = false) String execMode,
            @RequestParam(required = false) String plantCd, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");   // 쉼표로 쪼개지 않게 원래 값 그대로
        var p = runs.grid(from, to, status, execMode, plantCd, keyword, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(RUN + "/columns")
    @ProgAuth(prog = ChkRunService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> runColumns(Locale locale) {
        List<Map<String, Object>> cols = runs.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** 엑셀(C21) — grid 와 같은 조건·정렬·보이는 열 */
    @PostMapping(RUN + "/export")
    @ProgAuth(prog = ChkRunService.PROG, action = Action.EXCEL)
    public void runExport(@RequestBody(required = false) RunExport req, HttpServletResponse response) throws IOException {
        runs.export(req, response);
    }

    @GetMapping(RUN + "/plants")
    @ProgAuth(prog = ChkRunService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<Option>> runPlants() {
        return ApiResponse.ok(mapper.findPlants());
    }

    @GetMapping(RUN + "/{runId}")
    @ProgAuth(prog = ChkRunService.PROG, action = Action.SEARCH)
    public ApiResponse<RunRow> run(@PathVariable long runId) {
        return ApiResponse.ok(runs.get(runId));
    }

    @PutMapping(RUN + "/{runId}/results")
    @ProgAuth(prog = ChkRunService.PROG, action = Action.SAVE)
    public ApiResponse<String> results(@PathVariable long runId, @RequestBody ResultsForm form) {
        runs.saveResults(runId, null, form == null ? null : form.rowVer(), form == null ? null : form.results());
        return ApiResponse.ok("OK");
    }

    @PostMapping(RUN + "/{runId}/complete")
    @ProgAuth(prog = ChkRunService.PROG, action = Action.SAVE)
    public ApiResponse<String> complete(@PathVariable long runId, @RequestBody RunAction form) {
        runs.complete(runId, form == null ? null : form.rowVer());
        return ApiResponse.ok("OK");
    }

    @PostMapping(RUN + "/{runId}/skip")
    @ProgAuth(prog = ChkRunService.PROG, action = Action.DELETE)
    public ApiResponse<String> skip(@PathVariable long runId, @RequestBody RunAction form) {
        runs.skip(runId, form == null ? null : form.rowVer(), form == null ? null : form.reasonCd(), form == null ? null : form.reason());
        return ApiResponse.ok("OK");
    }

    /** NG 항목 → 후속 고장 수리 작업지시 */
    @PostMapping(RUN + "/{runId}/follow-up")
    @ProgAuth(prog = ChkRunService.PROG, action = Action.SAVE)
    public ApiResponse<Created> followUp(@PathVariable long runId, @RequestBody RunAction form) {
        return ApiResponse.ok(mwos.followUp(runId, form == null ? null : form.seq()));
    }

    // ------------------------------------------------------------------ 작업지시 화면의 점검(PM)

    @GetMapping(MWO + "/{mwoId}/run")
    @ProgAuth(prog = MwoService.PROG, action = Action.SEARCH)
    public ApiResponse<RunRow> mwoRun(@PathVariable long mwoId) {
        mwos.get(mwoId);
        return ApiResponse.ok(runs.getByMwo(mwoId));
    }

    @PutMapping(MWO + "/{mwoId}/run/results")
    @ProgAuth(prog = MwoService.PROG, action = Action.SAVE)
    public ApiResponse<String> mwoRunResults(@PathVariable long mwoId, @RequestBody ResultsForm form) {
        RunRow r = runs.getByMwo(mwos.get(mwoId).getMwoId());
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        runs.saveResults(r.getRunId(), mwoId, form == null ? null : form.rowVer(), form == null ? null : form.results());
        return ApiResponse.ok("OK");
    }

    @PostMapping(MWO + "/{mwoId}/run/follow-up")
    @ProgAuth(prog = MwoService.PROG, action = Action.SAVE)
    public ApiResponse<Created> mwoFollowUp(@PathVariable long mwoId, @RequestBody RunAction form) {
        RunRow r = runs.getByMwo(mwos.get(mwoId).getMwoId());
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return ApiResponse.ok(mwos.followUp(r.getRunId(), form == null ? null : form.seq()));
    }
}
