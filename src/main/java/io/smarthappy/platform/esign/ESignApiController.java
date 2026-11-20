package io.smarthappy.platform.esign;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.auth.ProgAuthExempt;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.esign.ESignService.IssueForm;
import io.smarthappy.platform.esign.ESignService.Issued;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.security.MfaService;
import io.smarthappy.platform.tenant.TenantRunner;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * 전자서명 재인증 API (docs/36 16장). 토큰을 받는 것만 — 서명 행은 업무 API 가 자기 화면 권한으로 판정한 뒤
 * {@link ESignService#consume} 로 만든다. 그래서 여기는 화면 권한 대신 본인 한정.
 */
@RestController
@RequiredArgsConstructor
public class ESignApiController {

    static final String API = "/api/platform/esign";
    private static final String EXEMPT = "본인 재인증(서명 토큰 발급) — 서명할 권한은 토큰을 쓰는 업무 API 의 화면 권한이 판정";

    private final ESignService service;
    private final MfaService mfa;
    private final TenantRunner runner;

    /** 대화상자가 인증 코드 칸을 보일지 */
    @GetMapping(API + "/requirement")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Map<String, Boolean>> requirement() {
        LoginUser me = me();
        return ApiResponse.ok(Map.of("mfa", runner.call(me.compCd(), () -> mfa.isEnrolled(me.userId()))));
    }

    @PostMapping(API + "/token")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Issued> token(@RequestBody IssueForm form, HttpServletRequest request) {
        return ApiResponse.ok(service.issue(me(), request.getSession(false), form));
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
