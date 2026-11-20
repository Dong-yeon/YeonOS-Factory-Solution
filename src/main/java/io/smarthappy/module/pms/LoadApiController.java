package io.smarthappy.module.pms;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.pms.LoadService.LoadTask;
import io.smarthappy.module.pms.LoadService.LoadView;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/** 사람 × 주 부하 보기 (docs/32 4.26, 사용자 결정 P-14) — 보는 권한 = pms-project APPROVE(PMO·관리자) */
@RestController
@RequiredArgsConstructor
public class LoadApiController {

    private final LoadService service;

    @GetMapping("/api/pms/load")
    @ProgAuth(prog = TaskService.PROG, action = Action.APPROVE)
    public ApiResponse<LoadView> view(@RequestParam(required = false) String from, @RequestParam(required = false) Integer weeks,
            @RequestParam(required = false) Long projId) {
        return ApiResponse.ok(service.view(from, weeks, projId));
    }

    @GetMapping("/api/pms/load/cell")
    @ProgAuth(prog = TaskService.PROG, action = Action.APPROVE)
    public ApiResponse<List<LoadTask>> cell(@RequestParam(required = false) Long userId,
            @RequestParam(required = false, defaultValue = "false") boolean unassigned, @RequestParam(required = false) String week,
            @RequestParam(required = false) Long projId) {
        return ApiResponse.ok(service.cell(userId, unassigned, week, projId));
    }
}
