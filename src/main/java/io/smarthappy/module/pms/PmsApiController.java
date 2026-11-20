package io.smarthappy.module.pms;

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

import io.smarthappy.module.pms.PmsDtos.EcoLinkForm;
import io.smarthappy.module.pms.PmsDtos.MoveForm;
import io.smarthappy.module.pms.PmsDtos.ProjectForm;
import io.smarthappy.module.pms.PmsDtos.ProjectRow;
import io.smarthappy.module.pms.PmsDtos.StatusForm;
import io.smarthappy.module.pms.PmsDtos.TaskForm;
import io.smarthappy.module.pms.PmsDtos.TaskView;
import io.smarthappy.module.pms.PmsDtos.TemplateForm;
import io.smarthappy.module.pms.PmsDtos.TemplateRow;
import io.smarthappy.module.pms.PmsDtos.TmplTaskForm;
import io.smarthappy.module.pms.PmsDtos.TmplTaskView;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import java.io.IOException;
import io.smarthappy.platform.excel.GridExcel;
import jakarta.servlet.http.HttpServletResponse;

/** PMS 프로젝트·WBS·템플릿 API (docs/32 PM1). 행 권한(PM·담당자)은 서비스가 확인 */
@RestController
@RequiredArgsConstructor
public class PmsApiController {

    private static final String TMPL = "/api/pms/templates";
    private static final String PROJ = "/api/pms/projects";
    private static final String TASK = "/api/pms/tasks";

    private final TemplateService templates;
    private final ProjectService projects;
    private final TaskService tasks;
    private final ScheduleService schedule;
    private final PmsMapper mapper;
    private final UserDirectory users;
    private final MessageSource messages;
    private final WorkdayService workdays;
    private final GridExcel gridExcel;

    // ------------------------------------------------------------------ 근무일 입력 보조(docs/32 4.25)

    /** start + days(근무일 수) | hours(계획 공수) | end — 회사 휴일 달력으로 종료일·근무일 수. 계산만(저장 없음) */
    @GetMapping("/api/pms/workdays")
    @ProgAuth(prog = TaskService.PROG, action = Action.SEARCH)
    public ApiResponse<WorkdayService.WorkdayView> workdays(@RequestParam(required = false) String start,
            @RequestParam(required = false) Integer days, @RequestParam(required = false) java.math.BigDecimal hours,
            @RequestParam(required = false) String end) {
        return ApiResponse.ok(workdays.calc(start, days, hours, end));
    }

    // ------------------------------------------------------------------ 템플릿

    @GetMapping(TMPL)
    @ProgAuth(prog = TemplateService.PROG, action = Action.SEARCH)
    public ApiResponse<List<TemplateRow>> templates(@RequestParam(required = false) String useYn) {
        return ApiResponse.ok(templates.list(useYn));
    }

    /** grid(UI 단계 3 ②, docs/32 4.11) — 사용 여부·검색어 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(TMPL + "/grid")
    @ProgAuth(prog = TemplateService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> templateGrid(@RequestParam(required = false) String keyword, @RequestParam(required = false) String useYn,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        var p = templates.grid(keyword, useYn, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(TMPL + "/columns")
    @ProgAuth(prog = TemplateService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> templateColumns(Locale locale) {
        List<Map<String, Object>> cols = templates.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(TMPL + "/export")
    @ProgAuth(prog = TemplateService.PROG, action = Action.EXCEL)
    public void templateExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, TemplateService.PROG, templates.gridColumns(), q -> {
            var p = templates.grid(q.str("keyword"), q.str("useYn"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(TMPL + "/{tmplId}")
    @ProgAuth(prog = TemplateService.PROG, action = Action.SEARCH)
    public ApiResponse<TmplTaskView> template(@PathVariable long tmplId) {
        return ApiResponse.ok(templates.get(tmplId));
    }

    @PostMapping(TMPL)
    @ProgAuth(prog = TemplateService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createTemplate(@RequestBody TemplateForm form) {
        return ApiResponse.ok(templates.create(form));
    }

    @PutMapping(TMPL + "/{tmplId}")
    @ProgAuth(prog = TemplateService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateTemplate(@PathVariable long tmplId, @RequestBody TemplateForm form) {
        templates.update(tmplId, form);
        return ApiResponse.ok("OK");
    }

    /** 기본 APQP 템플릿 만들기(이미 있으면 그것) — docs/32 D7 */
    @PostMapping(TMPL + "/default-apqp")
    @ProgAuth(prog = TemplateService.PROG, action = Action.SAVE)
    public ApiResponse<Long> defaultApqp() {
        return ApiResponse.ok(templates.createDefaultApqp());
    }

    @PostMapping(TMPL + "/{tmplId}/tasks")
    @ProgAuth(prog = TemplateService.PROG, action = Action.SAVE)
    public ApiResponse<Long> addTmplTask(@PathVariable long tmplId, @RequestBody TmplTaskForm form) {
        return ApiResponse.ok(templates.addTask(tmplId, form));
    }

    @PutMapping(TMPL + "/tasks/{ttId}")
    @ProgAuth(prog = TemplateService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateTmplTask(@PathVariable long ttId, @RequestBody TmplTaskForm form) {
        templates.updateTask(ttId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(TMPL + "/tasks/{ttId}/move")
    @ProgAuth(prog = TemplateService.PROG, action = Action.SAVE)
    public ApiResponse<String> moveTmplTask(@PathVariable long ttId, @RequestBody MoveForm form) {
        templates.moveTask(ttId, form);
        return ApiResponse.ok("OK");
    }

    @DeleteMapping(TMPL + "/tasks/{ttId}")
    @ProgAuth(prog = TemplateService.PROG, action = Action.SAVE)
    public ApiResponse<String> deleteTmplTask(@PathVariable long ttId) {
        templates.deleteTask(ttId);
        return ApiResponse.ok("OK");
    }

    /** 템플릿 선후행(docs/32 4.18) */
    @PostMapping(TMPL + "/{tmplId}/deps")
    @ProgAuth(prog = TemplateService.PROG, action = Action.SAVE)
    public ApiResponse<Long> addTmplDep(@PathVariable long tmplId, @RequestBody PmsDtos.TmplDepForm form) {
        return ApiResponse.ok(templates.addDep(tmplId, form));
    }

    @DeleteMapping(TMPL + "/deps/{tdepId}")
    @ProgAuth(prog = TemplateService.PROG, action = Action.SAVE)
    public ApiResponse<String> deleteTmplDep(@PathVariable long tdepId) {
        templates.deleteDep(tdepId);
        return ApiResponse.ok("OK");
    }

    /** 프로젝트 → 새 템플릿(docs/32 4.18, P-7) — 템플릿 저장 권한 + 서비스가 pms-project SEARCH 도 확인 */
    @PostMapping(TMPL + "/from-project/{projId}")
    @ProgAuth(prog = TemplateService.PROG, action = Action.SAVE)
    public ApiResponse<Long> templateFromProject(@PathVariable long projId, @RequestBody PmsDtos.TmplFromProjectForm form) {
        return ApiResponse.ok(templates.fromProject(projId, form));
    }

    // ------------------------------------------------------------------ 프로젝트

    @GetMapping(PROJ)
    @ProgAuth(prog = TaskService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ProjectRow>> projects(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String projType, @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "false") boolean mine) {
        return ApiResponse.ok(projects.list(keyword, projType, status, mine));
    }

    /** grid(UI 단계 3 ②, docs/32 4.11) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(PROJ + "/grid")
    @ProgAuth(prog = TaskService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> projectGrid(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String projType, @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "false") boolean mine,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로
        String[] sort = request.getParameterValues("sort");
        var p = projects.grid(keyword, projType, status, mine, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(PROJ + "/columns")
    @ProgAuth(prog = TaskService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> projectColumns(Locale locale) {
        List<Map<String, Object>> cols = projects.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(PROJ + "/export")
    @ProgAuth(prog = TaskService.PROG, action = Action.EXCEL)
    public void projectExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, TaskService.PROG, projects.gridColumns(), q -> {
            var p = projects.grid(q.str("keyword"), q.str("projType"), q.str("status"), Boolean.TRUE.equals(q.bool("mine")), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(PROJ + "/{projId}")
    @ProgAuth(prog = TaskService.PROG, action = Action.SEARCH)
    public ApiResponse<ProjectRow> project(@PathVariable long projId) {
        return ApiResponse.ok(projects.get(projId));
    }

    @PostMapping(PROJ)
    @ProgAuth(prog = TaskService.PROG, action = Action.SAVE)
    public ApiResponse<Long> createProject(@RequestBody ProjectForm form) {
        return ApiResponse.ok(projects.create(form));
    }

    @PutMapping(PROJ + "/{projId}")
    @ProgAuth(prog = TaskService.PROG, action = Action.SAVE)
    public ApiResponse<String> updateProject(@PathVariable long projId, @RequestBody ProjectForm form) {
        projects.update(projId, form);
        return ApiResponse.ok("OK");
    }

    /** PLM ECO 연결·해제(PM7, D17) — plm-eco SEARCH 도 서비스에서 확인 */
    @PutMapping(PROJ + "/{projId}/eco")
    @ProgAuth(prog = TaskService.PROG, action = Action.SAVE)
    public ApiResponse<String> linkEco(@PathVariable long projId, @RequestBody EcoLinkForm form) {
        projects.linkEco(projId, form);
        return ApiResponse.ok("OK");
    }

    /** 착수·종료 상신(docs/32 4.16) — 정책 PMS_PROJ_APPROVAL 켬일 때, 반환 = 결재 id */
    @PostMapping(PROJ + "/{projId}/copy")
    @ProgAuth(prog = TaskService.PROG, action = Action.SAVE)
    public ApiResponse<Long> copyProject(@PathVariable long projId, @RequestBody ProjectForm form) {
        return ApiResponse.ok(projects.copy(projId, form));
    }

    @PostMapping(PROJ + "/{projId}/approval")
    @ProgAuth(prog = TaskService.PROG, action = Action.SAVE)
    public ApiResponse<Long> submitApproval(@PathVariable long projId, @RequestBody ProjectService.ProjAprForm form) {
        return ApiResponse.ok(projects.submitApproval(projId, form));
    }

    @PostMapping(PROJ + "/{projId}/status")
    @ProgAuth(prog = TaskService.PROG, action = Action.SAVE)
    public ApiResponse<String> changeStatus(@PathVariable long projId, @RequestBody StatusForm form) {
        projects.changeStatus(projId, form);
        return ApiResponse.ok("OK");
    }

    /** 템플릿 고르기(신규 프로젝트) — 템플릿 화면 권한 없이도 프로젝트 화면에서 고를 수 있게 */
    @GetMapping(PROJ + "/templates")
    @ProgAuth(prog = TaskService.PROG, action = Action.SEARCH)
    public ApiResponse<List<TemplateRow>> projectTemplates() {
        return ApiResponse.ok(templates.list("Y"));
    }

    /** PM·담당자 고르기 — 같은 회사 사용 중 계정(이름·아이디만) */
    @GetMapping(PROJ + "/users")
    @ProgAuth(prog = TaskService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<UserOption>> users() {
        return ApiResponse.ok(users.activeUsers());
    }

    @GetMapping(PROJ + "/plants")
    @ProgAuth(prog = TaskService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<PmsDtos.CodeOption>> plants() {
        return ApiResponse.ok(mapper.findPlants());
    }

    // ------------------------------------------------------------------ 작업

    @GetMapping(PROJ + "/{projId}/tasks")
    @ProgAuth(prog = TaskService.PROG, action = Action.SEARCH)
    public ApiResponse<TaskView> tasks(@PathVariable long projId) {
        return ApiResponse.ok(tasks.view(projId));
    }

    @PostMapping(PROJ + "/{projId}/tasks")
    @ProgAuth(prog = TaskService.PROG, action = Action.SAVE)
    public ApiResponse<Long> addTask(@PathVariable long projId, @RequestBody TaskForm form) {
        return ApiResponse.ok(tasks.add(projId, form));
    }

    /** 선행 미완 통과 기록(docs/32 4.21) */
    @GetMapping(TASK + "/{taskId}/dep-waivers")
    @ProgAuth(prog = TaskService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PmsDtos.DepWaiveRow>> depWaivers(@PathVariable long taskId) {
        return ApiResponse.ok(tasks.depWaivers(taskId));
    }

    /** 내 작업 줄 편집(docs/32 4.19) — 실적 시작·종료·진척만, 반환 = 경고(update 와 같음) */
    @PostMapping(TASK + "/{taskId}/actual")
    @ProgAuth(prog = TaskService.PROG, action = Action.SAVE)
    public ApiResponse<List<String>> updateTaskActual(@PathVariable long taskId, @RequestBody TaskService.ActualForm form) {
        return ApiResponse.ok(tasks.updateActual(taskId, form));
    }

    @PutMapping(TASK + "/{taskId}")
    @ProgAuth(prog = TaskService.PROG, action = Action.SAVE)
    /** 반환 = 경고(통과 전 게이트 이름, 정책 PMS_GATE_ENFORCE=WARN) */
    public ApiResponse<List<String>> updateTask(@PathVariable long taskId, @RequestBody TaskForm form) {
        return ApiResponse.ok(tasks.update(taskId, form));
    }

    @PostMapping(TASK + "/{taskId}/move")
    @ProgAuth(prog = TaskService.PROG, action = Action.SAVE)
    public ApiResponse<String> moveTask(@PathVariable long taskId, @RequestBody MoveForm form) {
        tasks.move(taskId, form);
        return ApiResponse.ok("OK");
    }

    @DeleteMapping(TASK + "/{taskId}")
    @ProgAuth(prog = TaskService.PROG, action = Action.SAVE)
    public ApiResponse<String> deleteTask(@PathVariable long taskId) {
        tasks.delete(taskId);
        return ApiResponse.ok("OK");
    }

    // ------------------------------------------------------------------ PM2 의존·기준선·후행 밀기

    @PostMapping(PROJ + "/{projId}/deps")
    @ProgAuth(prog = TaskService.PROG, action = Action.SAVE)
    public ApiResponse<Long> addDep(@PathVariable long projId, @RequestBody PmsDtos.DepForm form) {
        return ApiResponse.ok(schedule.addDep(projId, form));
    }

    @DeleteMapping("/api/pms/deps/{depId}")
    @ProgAuth(prog = TaskService.PROG, action = Action.SAVE)
    public ApiResponse<String> deleteDep(@PathVariable long depId) {
        schedule.deleteDep(depId);
        return ApiResponse.ok("OK");
    }

    @GetMapping(PROJ + "/{projId}/baselines")
    @ProgAuth(prog = TaskService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PmsDtos.BaselineRow>> baselines(@PathVariable long projId) {
        return ApiResponse.ok(schedule.baselines(projId));
    }

    /** 기준선 확정 — 반환 = 새 기준선 번호 */
    @PostMapping(PROJ + "/{projId}/baselines")
    @ProgAuth(prog = TaskService.PROG, action = Action.SAVE)
    public ApiResponse<Integer> setBaseline(@PathVariable long projId, @RequestBody PmsDtos.BaselineForm form) {
        return ApiResponse.ok(schedule.setBaseline(projId, form));
    }

    @GetMapping(PROJ + "/{projId}/push-preview")
    @ProgAuth(prog = TaskService.PROG, action = Action.SEARCH)
    public ApiResponse<PmsDtos.PushPlan> pushPreview(@PathVariable long projId) {
        return ApiResponse.ok(schedule.previewPush(projId));
    }

    /** 후행 밀기 확정 — 반환 = 옮긴 작업 수 */
    @PostMapping(PROJ + "/{projId}/push")
    @ProgAuth(prog = TaskService.PROG, action = Action.SAVE)
    public ApiResponse<Integer> push(@PathVariable long projId, @RequestBody PmsDtos.PushForm form) {
        return ApiResponse.ok(schedule.applyPush(projId, form));
    }

    @GetMapping(TASK + "/{taskId}/plan-changes")
    @ProgAuth(prog = TaskService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PmsDtos.PlanChangeRow>> planChanges(@PathVariable long taskId) {
        return ApiResponse.ok(schedule.planChanges(taskId));
    }
}
