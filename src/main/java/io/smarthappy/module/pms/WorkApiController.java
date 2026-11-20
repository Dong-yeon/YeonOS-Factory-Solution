package io.smarthappy.module.pms;

import java.io.IOException;
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

import io.smarthappy.module.pms.GateDtos.TaskOption;
import io.smarthappy.module.pms.WorkDtos.ApqpIssueForm;
import io.smarthappy.module.pms.WorkDtos.ApqpIssueRow;
import io.smarthappy.module.pms.WorkDtos.DayAction;
import io.smarthappy.module.pms.WorkDtos.DayForm;
import io.smarthappy.module.pms.WorkDtos.IssueForm;
import io.smarthappy.module.pms.WorkDtos.IssueRow;
import io.smarthappy.module.pms.WorkDtos.IssueSearch;
import io.smarthappy.module.pms.WorkDtos.EscalateForm;
import io.smarthappy.module.pms.WorkDtos.IssueStatusForm;
import io.smarthappy.module.pms.WorkDtos.ProjOption;
import io.smarthappy.module.pms.WorkDtos.SummaryRow;
import io.smarthappy.module.pms.WorkDtos.WeekView;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** PMS 공수·이슈 API (docs/32 PM4). 공수는 늘 본인, 이슈 행 권한은 서비스가 확인 */
@RestController
@RequiredArgsConstructor
public class WorkApiController {

    private static final String WL = "/api/pms/worklogs";
    private static final String IS = "/api/pms/issues";

    private final WorkLogService logs;
    private final IssueService issues;
    private final WorkMapper wm;
    private final PmsMapper mapper;
    private final UserDirectory users;
    private final TaskService tasks;
    private final MessageSource messages;

    // ------------------------------------------------------------------ 공수 (pms-worklog)

    @GetMapping(WL)
    @ProgAuth(prog = WorkLogService.PROG, action = Action.SEARCH)
    public ApiResponse<WeekView> week(@RequestParam(required = false) String from) {
        return ApiResponse.ok(logs.week(from));
    }

    @PutMapping(WL + "/day")
    @ProgAuth(prog = WorkLogService.PROG, action = Action.SAVE)
    public ApiResponse<String> saveDay(@RequestBody DayForm form) {
        logs.saveDay(form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(WL + "/prefill")
    @ProgAuth(prog = WorkLogService.PROG, action = Action.SAVE)
    public ApiResponse<Integer> prefill(@RequestBody DayAction form) {
        return ApiResponse.ok(logs.prefill(form));
    }

    @PostMapping(WL + "/confirm")
    @ProgAuth(prog = WorkLogService.PROG, action = Action.SAVE)
    public ApiResponse<Integer> confirm(@RequestBody DayAction form) {
        return ApiResponse.ok(logs.confirm(form));
    }

    @PostMapping(WL + "/reopen")
    @ProgAuth(prog = WorkLogService.PROG, action = Action.SAVE)
    public ApiResponse<Integer> reopen(@RequestBody DayAction form) {
        return ApiResponse.ok(logs.reopen(form));
    }

    /** 공수에 고를 작업(WBS 순서) — 열린 프로젝트만 */
    @GetMapping(WL + "/projects/{projId}/tasks")
    @ProgAuth(prog = WorkLogService.PROG, action = Action.SEARCH)
    public ApiResponse<List<TaskOption>> logTasks(@PathVariable long projId) {
        return ApiResponse.ok(taskOptions(projId));
    }

    /** 프로젝트 공수 요약 — 프로젝트 화면 조회 권한(PM 은 조회만, 고칠 길 없음) */
    @GetMapping("/api/pms/projects/{projId}/worklog-summary")
    @ProgAuth(prog = TaskService.PROG, action = Action.SEARCH)
    public ApiResponse<List<SummaryRow>> summary(@PathVariable long projId) {
        return ApiResponse.ok(logs.summary(projId));
    }

    // ------------------------------------------------------------------ 이슈 (pms-issue)

    @GetMapping(IS)
    @ProgAuth(prog = IssueService.PROG, action = Action.SEARCH)
    public ApiResponse<List<IssueRow>> issues(@RequestParam(required = false) Long projId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String severity,
            @RequestParam(required = false) Boolean mine, @RequestParam(required = false) String objType,
            @RequestParam(required = false) String objCd, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String issueType) {
        return ApiResponse.ok(issues.list(new IssueSearch(projId, status, severity, mine, objType, objCd, keyword, null, issueType)));
    }

    /** grid(UI 단계 3 ②, docs/32 4.11) — 목록과 같은 조건 + page·size·sort(허용 목록)·total. 기존 목록 API 는 그대로 */
    @GetMapping(IS + "/grid")
    @ProgAuth(prog = IssueService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> issueGrid(@RequestParam(required = false) Long projId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String severity,
            @RequestParam(required = false) Boolean mine, @RequestParam(required = false) String objType,
            @RequestParam(required = false) String objCd, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, @RequestParam(required = false) String issueType, HttpServletRequest request) {
        // sort=필드,방향 — List 로 받으면 스프링이 쉼표로 쪼개므로 원래 값 그대로
        String[] sort = request.getParameterValues("sort");
        var p = issues.grid(new IssueSearch(projId, status, severity, mine, objType, objCd, keyword, null, issueType), page, size,
                sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(IS + "/columns")
    @ProgAuth(prog = IssueService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> issueColumns(Locale locale) {
        List<Map<String, Object>> cols = issues.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @PostMapping(IS + "/export")
    @ProgAuth(prog = IssueService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) IssueSearch req, HttpServletResponse response) throws IOException {
        issues.export(req, response);
    }

    @GetMapping(IS + "/{id}")
    @ProgAuth(prog = IssueService.PROG, action = Action.SEARCH)
    public ApiResponse<IssueRow> issue(@PathVariable long id) {
        return ApiResponse.ok(issues.get(id));
    }

    @PostMapping(IS)
    @ProgAuth(prog = IssueService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody IssueForm form) {
        return ApiResponse.ok(issues.create(form));
    }

    @PutMapping(IS + "/{id}")
    @ProgAuth(prog = IssueService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long id, @RequestBody IssueForm form) {
        issues.update(id, form);
        return ApiResponse.ok("OK");
    }

    /** PFMEA 조치 → 이슈(A3-5) — apqp-fmea SEARCH 도 서비스에서 확인. 반환 = 이슈 id */
    @PostMapping(IS + "/from-apqp/{actionId}")
    @ProgAuth(prog = IssueService.PROG, action = Action.SAVE)
    public ApiResponse<Long> fromApqp(@PathVariable long actionId, @RequestBody(required = false) ApqpIssueForm form) {
        return ApiResponse.ok(issues.fromApqp(actionId, form));
    }

    /** 조치 id 들에 연결된 이슈(PFMEA 화면 표시) */
    @GetMapping(IS + "/by-apqp-actions")
    @ProgAuth(prog = IssueService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ApqpIssueRow>> apqpIssues(@RequestParam(required = false) List<Long> ids) {
        return ApiResponse.ok(issues.apqpIssues(ids));
    }

    /** 시정조치로 올리기(PM6, D10) — qms-capa SAVE 도 서비스에서 확인. 반환 = 시정조치 id */
    @PostMapping(IS + "/{id}/escalate")
    @ProgAuth(prog = IssueService.PROG, action = Action.SAVE)
    public ApiResponse<Long> escalate(@PathVariable long id, @RequestBody EscalateForm form) {
        return ApiResponse.ok(issues.escalate(id, form));
    }

    @PostMapping(IS + "/{id}/status")
    @ProgAuth(prog = IssueService.PROG, action = Action.SAVE)
    public ApiResponse<String> status(@PathVariable long id, @RequestBody IssueStatusForm form) {
        issues.changeStatus(id, form);
        return ApiResponse.ok("OK");
    }

    @GetMapping("/api/pms/issue-projects")
    @ProgAuth(prog = IssueService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<ProjOption>> issueProjects() {
        return ApiResponse.ok(wm.findOpenProjects());
    }

    @GetMapping("/api/pms/issue-projects/{projId}/tasks")
    @ProgAuth(prog = IssueService.PROG, action = Action.SEARCH)
    public ApiResponse<List<TaskOption>> issueTasks(@PathVariable long projId) {
        return ApiResponse.ok(taskOptions(projId));
    }

    @GetMapping("/api/pms/issue-users")
    @ProgAuth(prog = IssueService.PROG, action = Action.SEARCH)
    @Transactional(readOnly = true)
    public ApiResponse<List<UserOption>> issueUsers() {
        return ApiResponse.ok(users.activeUsers());
    }

    private List<TaskOption> taskOptions(long projId) {
        return tasks.options(projId);
    }
}
