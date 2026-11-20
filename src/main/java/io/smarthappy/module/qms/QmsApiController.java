package io.smarthappy.module.qms;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.qms.QmsDtos.ActionRequest;
import io.smarthappy.module.qms.QmsDtos.AqlView;
import io.smarthappy.module.qms.QmsDtos.CharChoice;
import io.smarthappy.module.qms.QmsDtos.DocChoice;
import io.smarthappy.module.qms.QmsDtos.EffectiveResult;
import io.smarthappy.module.qms.QmsDtos.HeaderForm;
import io.smarthappy.module.qms.QmsDtos.ItemLookup;
import io.smarthappy.module.qms.QmsDtos.LinesForm;
import io.smarthappy.module.qms.QmsDtos.LinkDocForm;
import io.smarthappy.module.qms.QmsDtos.PlanDetail;
import io.smarthappy.module.qms.QmsDtos.PlanExportRequest;
import io.smarthappy.module.qms.QmsDtos.PreviewRequest;
import io.smarthappy.module.qms.QmsDtos.PreviewResult;
import io.smarthappy.module.qms.QmsDtos.SamplingExportRequest;
import io.smarthappy.module.qms.QmsDtos.SamplingForm;
import io.smarthappy.module.qms.QmsDtos.SamplingRow;
import io.smarthappy.module.qms.QmsDtos.SaveResult;
import io.smarthappy.module.qms.QmsDtos.TargetLookup;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** QMS 검사 기준 API (P3 Q1, docs/29) — 샘플링 계획·AQL 표(qms-sampling), 검사계획(qms-insp-plan: 확정 = APPROVE, 폐기 = DELETE) */
@RestController
@RequiredArgsConstructor
public class QmsApiController {

    private static final String SP = "/api/qms/sampling-plans";
    private static final String AQL = "/api/qms/aql";
    private static final String IP = "/api/qms/insp-plans";

    private final SamplingService sampling;
    private final InspPlanService plans;
    private final MessageSource messages;

    // ------------------------------------------------------------------ 샘플링 계획

    @GetMapping(SP)
    @ProgAuth(prog = SamplingService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> samplingList(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String method, @RequestParam(required = false) Boolean withUnused,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        SamplingService.Page p = sampling.list(keyword, method, withUnused, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(SP + "/columns")
    @ProgAuth(prog = SamplingService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> samplingColumns(Locale locale) {
        List<Map<String, Object>> cols = sampling.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(SP + "/{id}")
    @ProgAuth(prog = SamplingService.PROG, action = Action.SEARCH)
    public ApiResponse<SamplingRow> samplingDetail(@PathVariable long id) {
        return ApiResponse.ok(sampling.detail(id));
    }

    @PostMapping(SP)
    @ProgAuth(prog = SamplingService.PROG, action = Action.SAVE)
    public ApiResponse<Long> samplingCreate(@RequestBody SamplingForm form) {
        return ApiResponse.ok(sampling.create(form));
    }

    @PutMapping(SP + "/{id}")
    @ProgAuth(prog = SamplingService.PROG, action = Action.SAVE)
    public ApiResponse<String> samplingUpdate(@PathVariable long id, @RequestBody SamplingForm form) {
        sampling.update(id, form);
        return ApiResponse.ok("OK");
    }

    /** 판정 미리보기 — 계산만(저장 없음) */
    @PostMapping(SP + "/preview")
    @ProgAuth(prog = SamplingService.PROG, action = Action.SEARCH)
    public ApiResponse<PreviewResult> samplingPreview(@RequestBody PreviewRequest req) {
        return ApiResponse.ok(sampling.preview(req));
    }

    @PostMapping(SP + "/export")
    @ProgAuth(prog = SamplingService.PROG, action = Action.EXCEL)
    public void samplingExport(@RequestBody(required = false) SamplingExportRequest req, HttpServletResponse response)
            throws IOException {
        sampling.export(req, response);
    }

    // ------------------------------------------------------------------ AQL 표 (보기 — 올리기는 /api/platform/import)

    @GetMapping(AQL + "/stds")
    @ProgAuth(prog = SamplingService.PROG, action = Action.SEARCH)
    public ApiResponse<List<String>> aqlStds() {
        return ApiResponse.ok(sampling.stds());
    }

    @GetMapping(AQL)
    @ProgAuth(prog = SamplingService.PROG, action = Action.SEARCH)
    public ApiResponse<AqlView> aql(@RequestParam String stdCd) {
        return ApiResponse.ok(sampling.aql(stdCd));
    }

    // ------------------------------------------------------------------ 검사계획

    @GetMapping(IP)
    @ProgAuth(prog = InspPlanService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> planList(@RequestParam(required = false) String inspType,
            @RequestParam(required = false) String target, @RequestParam(required = false) String procCd,
            @RequestParam(required = false) String status, @RequestParam(required = false) Boolean withObsolete,
            @RequestParam(required = false) Boolean specChangedOnly, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total,
            HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        InspPlanService.Page p = plans.list(inspType, target, procCd, status, withObsolete, specChangedOnly, page, size,
                sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(IP + "/columns")
    @ProgAuth(prog = InspPlanService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> planColumns(Locale locale) {
        List<Map<String, Object>> cols = plans.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(IP + "/item")
    @ProgAuth(prog = InspPlanService.PROG, action = Action.SEARCH)
    public ApiResponse<ItemLookup> planItem(@RequestParam String itemCd) {
        return ApiResponse.ok(plans.item(itemCd));
    }

    @GetMapping(IP + "/classes")
    @ProgAuth(prog = InspPlanService.PROG, action = Action.SEARCH)
    public ApiResponse<List<TargetLookup>> planClasses() {
        return ApiResponse.ok(plans.classes());
    }

    @GetMapping(IP + "/procs")
    @ProgAuth(prog = InspPlanService.PROG, action = Action.SEARCH)
    public ApiResponse<List<TargetLookup>> planProcs() {
        return ApiResponse.ok(plans.procs());
    }

    /** 행 샘플링 선택지 — 샘플링 화면 권한 없이도 사용 중 계획은 보인다 */
    @GetMapping(IP + "/sampling-plans")
    @ProgAuth(prog = InspPlanService.PROG, action = Action.SEARCH)
    public ApiResponse<List<SamplingRow>> planSamplingChoices() {
        return ApiResponse.ok(plans.samplingChoices());
    }

    @GetMapping(IP + "/effective")
    @ProgAuth(prog = InspPlanService.PROG, action = Action.SEARCH)
    public ApiResponse<EffectiveResult> planEffective(@RequestParam String itemCd, @RequestParam String inspType,
            @RequestParam(required = false) String procCd, @RequestParam(required = false) String date) {
        return ApiResponse.ok(plans.effective(itemCd, inspType, procCd, date));
    }

    @GetMapping(IP + "/{planId}")
    @ProgAuth(prog = InspPlanService.PROG, action = Action.SEARCH)
    public ApiResponse<PlanDetail> planDetail(@PathVariable long planId) {
        return ApiResponse.ok(plans.detail(planId));
    }

    @GetMapping(IP + "/{planId}/chars")
    @ProgAuth(prog = InspPlanService.PROG, action = Action.SEARCH)
    public ApiResponse<List<CharChoice>> planChars(@PathVariable long planId) {
        return ApiResponse.ok(plans.charChoices(planId));
    }

    @PostMapping(IP)
    @ProgAuth(prog = InspPlanService.PROG, action = Action.SAVE)
    public ApiResponse<Long> planCreate(@RequestBody HeaderForm form) {
        return ApiResponse.ok(plans.create(form));
    }

    @PutMapping(IP + "/{planId}")
    @ProgAuth(prog = InspPlanService.PROG, action = Action.SAVE)
    public ApiResponse<String> planUpdate(@PathVariable long planId, @RequestBody HeaderForm form) {
        plans.update(planId, form);
        return ApiResponse.ok("OK");
    }

    @PutMapping(IP + "/{planId}/lines")
    @ProgAuth(prog = InspPlanService.PROG, action = Action.SAVE)
    public ApiResponse<SaveResult> planLines(@PathVariable long planId, @RequestParam int rowVer, @RequestBody LinesForm form) {
        return ApiResponse.ok(plans.saveLines(planId, rowVer, form));
    }

    @PostMapping(IP + "/{planId}/release")
    @ProgAuth(prog = InspPlanService.PROG, action = Action.APPROVE)
    public ApiResponse<String> planRelease(@PathVariable long planId, @RequestBody ActionRequest req) {
        plans.release(planId, req);
        return ApiResponse.ok("OK");
    }

    @PostMapping(IP + "/{planId}/obsolete")
    @ProgAuth(prog = InspPlanService.PROG, action = Action.DELETE)
    public ApiResponse<String> planObsolete(@PathVariable long planId, @RequestBody ActionRequest req) {
        plans.obsolete(planId, req);
        return ApiResponse.ok("OK");
    }

    @PostMapping(IP + "/{planId}/new-revision")
    @ProgAuth(prog = InspPlanService.PROG, action = Action.SAVE)
    public ApiResponse<Long> planNewRevision(@PathVariable long planId, @RequestBody ActionRequest req) {
        return ApiResponse.ok(plans.newRevision(planId, req));
    }

    /** 검사기준서 연결 선택지 — 관리문서 화면 권한 없이도 검사기준서 리비전은 보인다 */
    @GetMapping(IP + "/docs")
    @ProgAuth(prog = InspPlanService.PROG, action = Action.SEARCH)
    public ApiResponse<List<DocChoice>> planDocChoices() {
        return ApiResponse.ok(plans.docChoices());
    }

    @PutMapping(IP + "/{planId}/doc")
    @ProgAuth(prog = InspPlanService.PROG, action = Action.SAVE)
    public ApiResponse<String> planLinkDoc(@PathVariable long planId, @RequestBody LinkDocForm form) {
        plans.linkDoc(planId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(IP + "/export")
    @ProgAuth(prog = InspPlanService.PROG, action = Action.EXCEL)
    public void planExport(@RequestBody(required = false) PlanExportRequest req, HttpServletResponse response)
            throws IOException {
        plans.export(req, response);
    }
}
