package io.smarthappy.module.pop;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.cms.link.PmCheckPort.Done;
import io.smarthappy.module.cms.link.PmCheckPort.DueRun;
import io.smarthappy.module.pop.PopPmService.CompleteForm;
import io.smarthappy.module.pop.PopPmService.PmView;
import io.smarthappy.module.pop.PopPmService.SkipForm;
import io.smarthappy.platform.auth.ProgAuthExempt;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/** POP 설비 점검 API (docs/25 4.58, /api/pop/pm/** = 생산 단말(POP)만 — PDA 는 403) */
@RestController
@RequiredArgsConstructor
public class PopPmApiController {

    private static final String EXEMPT = "POP 단말 체인(ROLE_TERMINAL_POP) — 단말 공장·작업장 + 지금 작업자를 서비스가 확인(docs/25 4.58)";

    private final PopAuthService auth;
    private final PopPmService pm;

    @GetMapping("/api/pop/pm/runs")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<List<DueRun>> runs(@AuthenticationPrincipal TerminalPrincipal t, HttpServletRequest request) {
        return ApiResponse.ok(pm.due(t, auth.currentWorker(request, t)));
    }

    @GetMapping("/api/pop/pm/runs/{id}")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<PmView> run(@AuthenticationPrincipal TerminalPrincipal t, @PathVariable long id, HttpServletRequest request) {
        return ApiResponse.ok(pm.view(t, auth.currentWorker(request, t), id));
    }

    @PostMapping("/api/pop/pm/runs/{id}/complete")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Done> complete(@AuthenticationPrincipal TerminalPrincipal t, @PathVariable long id,
            @RequestBody(required = false) CompleteForm form, HttpServletRequest request) {
        return ApiResponse.ok(pm.complete(t, auth.currentWorker(request, t), id, form));
    }

    @PostMapping("/api/pop/pm/runs/{id}/skip")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Done> skip(@AuthenticationPrincipal TerminalPrincipal t, @PathVariable long id,
            @RequestBody(required = false) SkipForm form, HttpServletRequest request) {
        return ApiResponse.ok(pm.skip(t, auth.currentWorker(request, t), id, form));
    }
}
