package io.smarthappy.platform.grid;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.auth.ProgAuthExempt;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.grid.AuditViewService.AuditEntry;
import io.smarthappy.platform.grid.GridStateService.SaveRequest;
import lombok.RequiredArgsConstructor;

/** 표준 화면 공용 API: 그리드 화면 설정, 변경 이력 보기, 화면 피드백. 권한은 대상 화면(progCd) 기준으로 서비스가 판정 */
@RestController
@RequiredArgsConstructor
public class GridApiController {

    private static final String BY_TARGET_PROG = "대상 화면(progCd·테이블 소유 화면)의 SEARCH 권한을 서비스가 판정";

    private final GridStateService gridState;
    private final AuditViewService audit;
    private final FeedbackService feedback;

    public record FeedbackRequest(String progCd, String content) {
    }

    @GetMapping("/api/platform/grid-state/{progCd}/{gridId}")
    @ProgAuthExempt(reason = BY_TARGET_PROG)
    public ApiResponse<Map<String, Object>> loadState(@PathVariable String progCd, @PathVariable String gridId) {
        return ApiResponse.ok(gridState.load(progCd, gridId));
    }

    @PutMapping("/api/platform/grid-state/{progCd}/{gridId}")
    @ProgAuthExempt(reason = BY_TARGET_PROG)
    public ApiResponse<String> saveState(@PathVariable String progCd, @PathVariable String gridId,
            @RequestBody SaveRequest req) {
        gridState.save(progCd, gridId, req);
        return ApiResponse.ok("OK");
    }

    @DeleteMapping("/api/platform/grid-state/{progCd}/{gridId}")
    @ProgAuthExempt(reason = BY_TARGET_PROG)
    public ApiResponse<String> resetState(@PathVariable String progCd, @PathVariable String gridId) {
        gridState.reset(progCd, gridId);
        return ApiResponse.ok("OK");
    }

    @GetMapping("/api/platform/audit")
    @ProgAuthExempt(reason = BY_TARGET_PROG)
    public ApiResponse<Void> auditHistory(@RequestParam String table, @RequestParam String key) {
        List<AuditEntry> list = audit.history(table, key);
        return ApiResponse.list(list, list.size());
    }

    @PostMapping("/api/platform/feedback")
    @ProgAuthExempt(reason = BY_TARGET_PROG)
    public ApiResponse<Long> feedback(@RequestBody FeedbackRequest req) {
        return ApiResponse.ok(feedback.submit(req == null ? null : req.progCd(), req == null ? null : req.content()));
    }
}
