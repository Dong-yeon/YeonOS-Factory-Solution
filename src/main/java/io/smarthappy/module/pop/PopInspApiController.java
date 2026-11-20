package io.smarthappy.module.pop;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.pop.PopInspService.ConfirmBody;
import io.smarthappy.module.pop.PopInspService.DueView;
import io.smarthappy.module.pop.PopInspService.MeasureBody;
import io.smarthappy.module.pop.PopInspService.OpenBody;
import io.smarthappy.module.pop.PopInspService.Opened;
import io.smarthappy.module.qms.link.FloorInspPort.FloorInsp;
import io.smarthappy.module.qms.link.FloorInspPort.Gauge;
import io.smarthappy.module.qms.link.FloorInspPort.MeasureResult;
import io.smarthappy.platform.auth.ProgAuthExempt;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/** POP 검사 입력 API (docs/25 4.57, /api/pop/procs/{공정}/insp/** = 생산 단말(POP)만 — 공정 경로 아래라 PDA 는 403) */
@RestController
@RequiredArgsConstructor
public class PopInspApiController {

    private static final String EXEMPT = "POP 단말 체인(ROLE_TERMINAL_POP) — 단말 작업장 공정 + 지금 작업자를 서비스가 확인(docs/25 4.57)";
    private static final String BASE = "/api/pop/procs/{procId}/insp";

    private final PopAuthService auth;
    private final PopInspService insp;

    @GetMapping(BASE)
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<DueView> due(@AuthenticationPrincipal TerminalPrincipal t, @PathVariable long procId, HttpServletRequest request) {
        return ApiResponse.ok(insp.due(t, auth.currentWorker(request, t), procId));
    }

    @PostMapping(BASE + "/open")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Opened> open(@AuthenticationPrincipal TerminalPrincipal t, @PathVariable long procId,
            @RequestBody(required = false) OpenBody body, HttpServletRequest request) {
        return ApiResponse.ok(insp.open(t, auth.currentWorker(request, t), procId, body));
    }

    @GetMapping(BASE + "/gauges")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<List<Gauge>> gauges(@AuthenticationPrincipal TerminalPrincipal t, @PathVariable long procId,
            @RequestParam(required = false) String gaugeClass, HttpServletRequest request) {
        return ApiResponse.ok(insp.gauges(t, auth.currentWorker(request, t), procId, gaugeClass));
    }

    @GetMapping(BASE + "/{inspId}")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<FloorInsp> view(@AuthenticationPrincipal TerminalPrincipal t, @PathVariable long procId, @PathVariable long inspId,
            HttpServletRequest request) {
        return ApiResponse.ok(insp.view(t, auth.currentWorker(request, t), procId, inspId));
    }

    @PostMapping(BASE + "/{inspId}/meas")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<MeasureResult> measure(@AuthenticationPrincipal TerminalPrincipal t, @PathVariable long procId,
            @PathVariable long inspId, @RequestBody(required = false) MeasureBody body, HttpServletRequest request) {
        return ApiResponse.ok(insp.measure(t, auth.currentWorker(request, t), procId, inspId, body));
    }

    /** 불합격 확정 — 모든 행이 판정되고 미리보기가 NG 일 때만(QMS 가 판정). 전부 OK 는 측정 때 자동 확정이라 여기 오지 않는다 */
    @PostMapping(BASE + "/{inspId}/confirm-ng")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<FloorInsp> confirmNg(@AuthenticationPrincipal TerminalPrincipal t, @PathVariable long procId,
            @PathVariable long inspId, @RequestBody(required = false) ConfirmBody body, HttpServletRequest request) {
        return ApiResponse.ok(insp.confirmNg(t, auth.currentWorker(request, t), procId, inspId, body));
    }
}
