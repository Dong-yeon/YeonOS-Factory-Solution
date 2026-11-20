package io.smarthappy.module.pms;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.pms.TaskChgDtos.ChgDetail;
import io.smarthappy.module.pms.TaskChgDtos.ChgForm;
import io.smarthappy.module.pms.TaskChgDtos.ChgInfo;
import io.smarthappy.module.pms.TaskChgDtos.ChgRow;
import io.smarthappy.module.pms.TaskChgDtos.DecideForm;
import io.smarthappy.module.pms.TaskChgDtos.WithdrawForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/** 담당자 일정변경 요청 API (docs/32 4.13). 화면 권한은 pms-project, 행 권한(담당자·PM·요청자)은 서비스가 확인 */
@RestController
@RequiredArgsConstructor
public class TaskChgApiController {

    private static final String REQ = "/api/pms/chg-reqs";

    private final TaskChgService service;

    @GetMapping("/api/pms/tasks/{taskId}/chg-info")
    @ProgAuth(prog = TaskService.PROG, action = Action.SEARCH)
    public ApiResponse<ChgInfo> info(@PathVariable long taskId) {
        return ApiResponse.ok(service.info(taskId));
    }

    /** 요청 — 반환 = 요청 id */
    @PostMapping("/api/pms/tasks/{taskId}/chg-reqs")
    @ProgAuth(prog = TaskService.PROG, action = Action.SAVE)
    public ApiResponse<Long> request(@PathVariable long taskId, @RequestBody ChgForm form) {
        return ApiResponse.ok(service.request(taskId, form));
    }

    @GetMapping("/api/pms/projects/{projId}/chg-reqs")
    @ProgAuth(prog = TaskService.PROG, action = Action.SEARCH)
    public ApiResponse<List<ChgRow>> list(@PathVariable long projId) {
        return ApiResponse.ok(service.list(projId));
    }

    @GetMapping(REQ + "/{reqId}")
    @ProgAuth(prog = TaskService.PROG, action = Action.SEARCH)
    public ApiResponse<ChgDetail> detail(@PathVariable long reqId) {
        return ApiResponse.ok(service.detail(reqId));
    }

    @PostMapping(REQ + "/{reqId}/decide")
    @ProgAuth(prog = TaskService.PROG, action = Action.SAVE)
    public ApiResponse<String> decide(@PathVariable long reqId, @RequestBody DecideForm form) {
        service.decide(reqId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(REQ + "/{reqId}/withdraw")
    @ProgAuth(prog = TaskService.PROG, action = Action.SAVE)
    public ApiResponse<String> withdraw(@PathVariable long reqId, @RequestBody WithdrawForm form) {
        service.withdraw(reqId, form);
        return ApiResponse.ok("OK");
    }
}
