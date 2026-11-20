package io.smarthappy.platform.ops;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.ops.SupportGrantService.GrantRequest;
import lombok.RequiredArgsConstructor;

/** 회사 관리자의 지원 세션 허락 화면(sys-support) API */
@RestController
@RequiredArgsConstructor
public class SupportApiController {

    private final SupportGrantService grants;

    @GetMapping("/api/platform/support/grants")
    @ProgAuth(prog = "sys-support", action = Action.SEARCH)
    public ApiResponse<Void> list() {
        List<Map<String, Object>> list = grants.list();
        return ApiResponse.list(list, list.size());
    }

    @PostMapping("/api/platform/support/grants")
    @ProgAuth(prog = "sys-support", action = Action.SAVE)
    public ApiResponse<Long> grant(@RequestBody GrantRequest req) {
        return ApiResponse.ok(grants.grant(req));
    }

    @PostMapping("/api/platform/support/grants/{grantId}/revoke")
    @ProgAuth(prog = "sys-support", action = Action.SAVE)
    public ApiResponse<String> revoke(@PathVariable long grantId) {
        grants.revoke(grantId);
        return ApiResponse.ok("OK");
    }

    @GetMapping("/api/platform/support/ops-log")
    @ProgAuth(prog = "sys-support", action = Action.SEARCH)
    public ApiResponse<Void> opsLog() {
        List<Map<String, Object>> list = grants.opsLog();
        return ApiResponse.list(list, list.size());
    }
}
