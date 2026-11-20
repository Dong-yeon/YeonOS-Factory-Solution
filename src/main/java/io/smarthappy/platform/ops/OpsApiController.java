package io.smarthappy.platform.ops;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.auth.ProgAuthExempt;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.ops.OpsConsoleService.CreateCompany;
import io.smarthappy.platform.ops.OpsConsoleService.Created;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/** 운영자 콘솔 API — ROLE_OPS(허용 IP + 비밀번호 + TOTP)만, 동작은 모두 운영 기록에 */
@RestController
@RequiredArgsConstructor
public class OpsApiController {

    private static final String EXEMPT = OpsAuthController.EXEMPT;

    private final OpsConsoleService console;
    private final BackupHealthService backup;

    @GetMapping("/api/ops/backup-health")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<BackupHealthService.Health> backupHealth() {
        return ApiResponse.ok(backup.check(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)));
    }

    public record StatusRequest(String status) {
    }

    public record UnlockRequest(String loginId) {
    }

    @GetMapping("/api/ops/companies")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Void> companies() {
        List<Map<String, Object>> list = console.companies();
        return ApiResponse.list(list, list.size());
    }

    @PostMapping("/api/ops/companies")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Created> create(@RequestBody CreateCompany req, HttpServletRequest request) {
        return ApiResponse.ok(console.createCompany(req, request.getRemoteAddr()));
    }

    @PostMapping("/api/ops/companies/{compCd}/status")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<String> status(@PathVariable String compCd, @RequestBody StatusRequest req,
            HttpServletRequest request) {
        console.setStatus(compCd, req == null ? null : req.status(), request.getRemoteAddr());
        return ApiResponse.ok("OK");
    }

    @PostMapping("/api/ops/companies/{compCd}/unlock")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<String> unlock(@PathVariable String compCd, @RequestBody UnlockRequest req,
            HttpServletRequest request) {
        console.unlockUser(compCd, req == null ? null : req.loginId(), request.getRemoteAddr());
        return ApiResponse.ok("OK");
    }

    @PostMapping("/api/ops/companies/{compCd}/reset-mfa")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<String> resetMfa(@PathVariable String compCd, @RequestBody UnlockRequest req,
            HttpServletRequest request) {
        console.resetUserMfa(compCd, req == null ? null : req.loginId(), request.getRemoteAddr());
        return ApiResponse.ok("OK");
    }

    @GetMapping("/api/ops/companies/{compCd}/usage")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Map<String, Object>> usage(@PathVariable String compCd,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, HttpServletRequest request) {
        return ApiResponse.ok(console.usage(compCd, from, to, request.getRemoteAddr()));
    }

    @GetMapping("/api/ops/companies/{compCd}/support/{scope}")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Void> support(@PathVariable String compCd, @PathVariable String scope,
            HttpServletRequest request) {
        List<Map<String, Object>> list = console.supportView(compCd, scope, request.getRemoteAddr());
        return ApiResponse.list(list, list.size());
    }

    @GetMapping("/api/ops/log")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Void> myLog() {
        List<Map<String, Object>> list = console.myLog();
        return ApiResponse.list(list, list.size());
    }
}
