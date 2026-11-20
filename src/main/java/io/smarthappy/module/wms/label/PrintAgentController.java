package io.smarthappy.module.wms.label;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.module.wms.label.PrintTrackDtos.AgentHeartbeat;
import io.smarthappy.module.wms.label.PrintTrackDtos.AgentHeartbeatView;
import io.smarthappy.module.wms.label.PrintTrackDtos.AgentResult;
import io.smarthappy.module.wms.label.PrintTrackDtos.AgentResultView;
import io.smarthappy.module.wms.label.PrintTrackDtos.ClaimedJob;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/**
 * pull 인쇄 에이전트 API (docs/65 19장) — `/ext/**` 체인(ExtSecurityConfig: 장치 Bearer API 키 → 그 회사 범위, ROLE_DEVICE).
 * 키는 시스템관리 sys-terminal 화면이 발급하는 수집 장치(COLLECTOR) 키 — 엣지와 같은 길.
 *
 * - POST /ext/v1/print-agent/claim {max?, waitSec?} → 그 장치 프린터의 기다리는 작업(lease). 없으면 waitSec 동안 1초 간격 롱폴링
 * - POST /ext/v1/print-agent/jobs/{jobId}/result {state, reasonCd?, detail?, occurredAt?} → 200 {stateCd, outcome MOVED|DUP|STALE}
 * - POST /ext/v1/print-agent/heartbeat {bootId?, printers:[{printerId, online, errors, warnings, odometer}]} → {queued, printers, unknown}
 */
@RestController
@RequiredArgsConstructor
public class PrintAgentController {

    static final String BASE = "/ext/v1/print-agent";
    static final long POLL_MS = 1_000;

    private final PrintAgentService agents;

    public record ClaimForm(Integer max, Integer waitSec) {
    }

    @PostMapping(BASE + "/claim")
    public ApiResponse<List<ClaimedJob>> claim(@AuthenticationPrincipal TerminalPrincipal d, @RequestBody(required = false) ClaimForm in)
            throws InterruptedException {
        int max = in == null || in.max() == null ? 5 : in.max();
        int wait = in == null || in.waitSec() == null ? 0 : Math.max(0, Math.min(in.waitSec(), agents.waitCap()));
        long deadline = System.nanoTime() + wait * 1_000_000_000L;
        while (true) {
            // 한 번 가져가기 = 트랜잭션 하나(잠금은 짧게). 기다리는 동안은 트랜잭션·연결을 잡지 않는다
            List<ClaimedJob> got = agents.claim(d, max);
            if (!got.isEmpty() || System.nanoTime() >= deadline) {
                return ApiResponse.ok(got);
            }
            Thread.sleep(POLL_MS);
        }
    }

    @PostMapping(BASE + "/jobs/{jobId}/result")
    public ApiResponse<AgentResultView> result(@AuthenticationPrincipal TerminalPrincipal d, @PathVariable long jobId,
            @RequestBody AgentResult in) {
        return ApiResponse.ok(agents.result(d, jobId, in));
    }

    @PostMapping(BASE + "/heartbeat")
    public ApiResponse<AgentHeartbeatView> heartbeat(@AuthenticationPrincipal TerminalPrincipal d,
            @RequestBody(required = false) AgentHeartbeat in) {
        return ApiResponse.ok(agents.heartbeat(d, in));
    }
}
