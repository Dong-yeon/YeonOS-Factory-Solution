package io.smarthappy.module.pms;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.smarthappy.module.pms.GateDtos.DecideForm;
import io.smarthappy.module.pms.GateDtos.DelivForm;
import io.smarthappy.module.pms.GateDtos.DelivView;
import io.smarthappy.module.pms.GateDtos.DocLinkForm;
import io.smarthappy.module.pms.GateDtos.DocLinkResult;
import io.smarthappy.module.pms.GateDtos.PpapLinkForm;
import io.smarthappy.module.pms.GateDtos.GateRow;
import io.smarthappy.module.pms.GateDtos.HistRow;
import io.smarthappy.module.pms.GateDtos.ReviewDetail;
import io.smarthappy.module.pms.GateDtos.ReviewForm;
import io.smarthappy.module.pms.GateDtos.ReviewRow;
import io.smarthappy.module.pms.GateDtos.SubmitForm;
import io.smarthappy.module.pms.GateDtos.TemplateItemForm;
import io.smarthappy.module.pms.GateDtos.TemplateItemRow;
import io.smarthappy.module.pms.PmsDtos.ProjectRow;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** PMS 산출물·게이트 심사·템플릿 산출물 칸 API (docs/32 PM3). 행 권한(PM·담당·APPROVE)은 서비스가 확인 */
@RestController
@RequiredArgsConstructor
public class GateApiController {

    private static final String DELIV = "/api/pms/deliverables";
    private static final String REVIEW = "/api/pms/gate-reviews";

    private final DeliverableService delivs;
    private final GateService gates;
    private final TemplateService templates;
    private final ProjectService projects;
    private final PmsMapper mapper;
    private final UserDirectory users;
    private final MessageSource messages;

    // ------------------------------------------------------------------ 템플릿 산출물 칸·점검 문구 (pms-template)

    @GetMapping("/api/pms/templates/{tmplId}/items")
    @ProgAuth(prog = TemplateService.PROG, action = Action.SEARCH)
    public ApiResponse<List<TemplateItemRow>> templateItems(@PathVariable long tmplId) {
        return ApiResponse.ok(templates.items(tmplId));
    }

    @PostMapping("/api/pms/templates/tasks/{ttId}/items")
    @ProgAuth(prog = TemplateService.PROG, action = Action.SAVE)
    public ApiResponse<Long> addTemplateItem(@PathVariable long ttId, @RequestBody TemplateItemForm form) {
        return ApiResponse.ok(templates.addItem(ttId, form));
    }

    @DeleteMapping("/api/pms/templates/items/{tiId}")
    @ProgAuth(prog = TemplateService.PROG, action = Action.SAVE)
    public ApiResponse<String> deleteTemplateItem(@PathVariable long tiId) {
        templates.deleteItem(tiId);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ 프로젝트 고르기 (산출물·게이트 화면 — 프로젝트 화면 권한 없이도)

    @GetMapping("/api/pms/deliverable-projects")
    @ProgAuth(prog = DeliverableService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ProjectRow>> delivProjects(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(projects.list(keyword, null, status, false));
    }

    @GetMapping("/api/pms/gate-projects")
    @ProgAuth(prog = GateService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ProjectRow>> gateProjects(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(projects.list(keyword, null, status, false));
    }

    /*
     * 프로젝트 고르기 grid(docs/40 §10.2 D1) — 위 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로.
     * 서비스·열은 하나(ProjectService.pickGrid)이고, 화면 권한만 다르므로 사용자 고르기(gate-users·deliverable-users)처럼 화면별 경로.
     * sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로
     */

    @GetMapping("/api/pms/deliverable-projects/grid")
    @ProgAuth(prog = DeliverableService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> delivProjectGrid(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        return pickGrid(keyword, status, page, size, total, request);
    }

    @GetMapping("/api/pms/deliverable-projects/columns")
    @ProgAuth(prog = DeliverableService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> delivProjectColumns(Locale locale) {
        return pickColumns(locale);
    }

    @GetMapping("/api/pms/gate-projects/grid")
    @ProgAuth(prog = GateService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> gateProjectGrid(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        return pickGrid(keyword, status, page, size, total, request);
    }

    @GetMapping("/api/pms/gate-projects/columns")
    @ProgAuth(prog = GateService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> gateProjectColumns(Locale locale) {
        return pickColumns(locale);
    }

    private ApiResponse<Void> pickGrid(String keyword, String status, Integer page, Integer size, Boolean total,
            HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        var p = projects.pickGrid(keyword, status, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    private ApiResponse<Void> pickColumns(Locale locale) {
        List<Map<String, Object>> cols = projects.pickGridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** 담당·조건 담당·결재자 고르기 — 같은 회사 사용 중 계정(이름·아이디만) */
    @GetMapping("/api/pms/deliverable-users")
    @ProgAuth(prog = DeliverableService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<UserOption>> delivUsers() {
        return ApiResponse.ok(users.activeUsers());
    }

    @GetMapping("/api/pms/gate-users")
    @ProgAuth(prog = GateService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<UserOption>> gateUsers() {
        return ApiResponse.ok(users.activeUsers());
    }

    // ------------------------------------------------------------------ 산출물 (pms-deliverable)

    @GetMapping("/api/pms/projects/{projId}/deliverables")
    @ProgAuth(prog = DeliverableService.PROG, action = Action.SEARCH)
    public ApiResponse<DelivView> deliverables(@PathVariable long projId) {
        return ApiResponse.ok(delivs.view(projId));
    }

    @PostMapping("/api/pms/projects/{projId}/deliverables/export")
    @ProgAuth(prog = DeliverableService.PROG, action = Action.EXCEL)
    public void export(@PathVariable long projId, @RequestBody(required = false) ExportRequest req, HttpServletResponse response)
            throws IOException {
        delivs.export(projId, req == null ? null : req.columns(), response);
    }

    public record ExportRequest(List<String> columns) {
    }

    @PostMapping("/api/pms/projects/{projId}/deliverables")
    @ProgAuth(prog = DeliverableService.PROG, action = Action.SAVE)
    public ApiResponse<Long> addDeliverable(@PathVariable long projId, @RequestBody DelivForm form) {
        return ApiResponse.ok(delivs.create(projId, form));
    }

    @PutMapping(DELIV + "/{delivId}")
    @ProgAuth(prog = DeliverableService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateDeliverable(@PathVariable long delivId, @RequestBody DelivForm form) {
        delivs.update(delivId, form);
        return ApiResponse.ok("OK");
    }

    @DeleteMapping(DELIV + "/{delivId}")
    @ProgAuth(prog = DeliverableService.PROG, action = Action.SAVE)
    public ApiResponse<String> deleteDeliverable(@PathVariable long delivId) {
        delivs.delete(delivId);
        return ApiResponse.ok("OK");
    }

    /** 제출 — 파일 1개(multipart), 반환 = 새 파일 id */
    @PostMapping(DELIV + "/{delivId}/submit")
    @ProgAuth(prog = DeliverableService.PROG, action = Action.SAVE)
    public ApiResponse<Long> submit(@PathVariable long delivId, @RequestParam(required = false) MultipartFile file,
            @RequestParam(required = false) Integer rowVer, @RequestParam(required = false) String note) {
        return ApiResponse.ok(delivs.submit(delivId, file, rowVer, note));
    }

    @PostMapping(DELIV + "/{delivId}/decide")
    @ProgAuth(prog = DeliverableService.PROG, action = Action.APPROVE)
    public ApiResponse<String> decide(@PathVariable long delivId, @RequestBody DecideForm form) {
        delivs.decide(delivId, form);
        return ApiResponse.ok("OK");
    }

    /** QMS 문서 리비전 연결(PM6, D6) — qms-doc SEARCH 도 서비스에서 확인 */
    @PostMapping(DELIV + "/{delivId}/doc-link")
    @ProgAuth(prog = DeliverableService.PROG, action = Action.SAVE)
    public ApiResponse<DocLinkResult> linkDoc(@PathVariable long delivId, @RequestBody DocLinkForm form) {
        return ApiResponse.ok(delivs.linkDoc(delivId, form));
    }

    /** PFMEA·관리계획서 리비전 연결(docs/41 A3-1) — apqp-fmea·apqp-cp SEARCH 도 서비스에서 확인 */
    @PostMapping(DELIV + "/{delivId}/apqp-link")
    @ProgAuth(prog = DeliverableService.PROG, action = Action.SAVE)
    public ApiResponse<DocLinkResult> linkApqp(@PathVariable long delivId, @RequestBody GateDtos.ApqpLinkForm form) {
        return ApiResponse.ok(delivs.linkApqp(delivId, form));
    }

    /** 프로젝트 ECO 의 PPAP 연결(PM7 ②, D18) — plm-eco SEARCH 도 서비스에서 확인 */
    @PostMapping(DELIV + "/{delivId}/ppap-link")
    @ProgAuth(prog = DeliverableService.PROG, action = Action.SAVE)
    public ApiResponse<DocLinkResult> linkPpap(@PathVariable long delivId, @RequestBody PpapLinkForm form) {
        return ApiResponse.ok(delivs.linkPpap(delivId, form));
    }

    /** [QMS 상태 반영] — 반환 = 승인으로 바뀐 칸 수 */
    @PostMapping("/api/pms/projects/{projId}/deliverables/sync-docs")
    @ProgAuth(prog = DeliverableService.PROG, action = Action.SAVE)
    public ApiResponse<Integer> syncDocs(@PathVariable long projId) {
        return ApiResponse.ok(delivs.syncDocs(projId));
    }

    @GetMapping(DELIV + "/{delivId}/history")
    @ProgAuth(prog = DeliverableService.PROG, action = Action.SEARCH)
    public ApiResponse<List<HistRow>> history(@PathVariable long delivId) {
        return ApiResponse.ok(delivs.history(delivId));
    }

    // ------------------------------------------------------------------ 게이트 심사 (pms-gate)

    @GetMapping("/api/pms/projects/{projId}/gates")
    @ProgAuth(prog = GateService.PROG, action = Action.SEARCH)
    public ApiResponse<List<GateRow>> gates(@PathVariable long projId) {
        return ApiResponse.ok(gates.gates(projId));
    }

    @GetMapping("/api/pms/gates/{gateTaskId}/reviews")
    @ProgAuth(prog = GateService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ReviewRow>> reviews(@PathVariable long gateTaskId) {
        return ApiResponse.ok(gates.reviewsOfGate(gateTaskId));
    }

    @PostMapping("/api/pms/gates/{gateTaskId}/reviews")
    @ProgAuth(prog = GateService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createReview(@PathVariable long gateTaskId) {
        return ApiResponse.ok(gates.create(gateTaskId));
    }

    @GetMapping(REVIEW + "/{reviewId}")
    @ProgAuth(prog = GateService.PROG, action = Action.SEARCH)
    public ApiResponse<ReviewDetail> review(@PathVariable long reviewId) {
        return ApiResponse.ok(gates.detail(reviewId));
    }

    @PutMapping(REVIEW + "/{reviewId}")
    @ProgAuth(prog = GateService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateReview(@PathVariable long reviewId, @RequestBody ReviewForm form) {
        gates.update(reviewId, form);
        return ApiResponse.ok("OK");
    }

    /** 상신(결재) 또는 바로 결정 — 반환 = 결재 id(바로 결정이면 0) */
    @PostMapping(REVIEW + "/{reviewId}/submit")
    @ProgAuth(prog = GateService.PROG, action = Action.SAVE)
    public ApiResponse<Long> submitReview(@PathVariable long reviewId, @RequestBody SubmitForm form) {
        return ApiResponse.ok(gates.submit(reviewId, form));
    }

    @PostMapping(REVIEW + "/{reviewId}/cancel")
    @ProgAuth(prog = GateService.PROG, action = Action.SAVE)
    public ApiResponse<String> cancelReview(@PathVariable long reviewId, @RequestBody SubmitForm form) {
        gates.cancel(reviewId, form == null ? null : form.rowVer());
        return ApiResponse.ok("OK");
    }
}
