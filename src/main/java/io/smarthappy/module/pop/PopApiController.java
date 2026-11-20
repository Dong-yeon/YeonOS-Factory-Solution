package io.smarthappy.module.pop;

import java.util.Locale;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.pop.PopDtos.ActivateForm;
import io.smarthappy.module.pop.PopDtos.BadgeForm;
import io.smarthappy.module.pop.PopDtos.BadgeResult;
import io.smarthappy.module.pop.PopDtos.HomeView;
import io.smarthappy.module.pop.PopDtos.LangForm;
import io.smarthappy.module.pop.PopDtos.PopSession;
import io.smarthappy.platform.auth.ProgAuthExempt;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.config.I18nConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * POP API (/api/pop/**) — 단말 체인(PopSecurityConfig)이 ROLE_TERMINAL 로 지킨다. 업무 권한은 "단말 + 작업자" 로 서비스가 본다.
 * 사무 권한(@ProgAuth, 사용자·역할)과 별개라 모두 @ProgAuthExempt.
 */
@RestController
@RequiredArgsConstructor
public class PopApiController {

    private static final String EXEMPT = "POP 단말 체인(ROLE_TERMINAL) — 사람 로그인·화면 권한 대상이 아님(docs/25 4.6)";

    private final PopAuthService auth;
    private final PopService pop;

    @PostMapping("/api/pop/activate")
    @ProgAuthExempt(reason = "POP 단말 활성화 — 로그인 전, 등록 코드 + IP 시도 제한(docs/25 4.6)")
    public ApiResponse<String> activate(@RequestBody ActivateForm form, HttpServletRequest request,
            HttpServletResponse response) {
        String cookie = auth.activate(form, request.getRemoteAddr());
        response.addHeader("Set-Cookie", PopCookies.issue(cookie, request.isSecure()));
        // 다른 단말·작업자로 쓰던 세션이 있으면 비운다
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        return ApiResponse.ok("OK");
    }

    @GetMapping("/api/pop/session")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<PopSession> session(@AuthenticationPrincipal TerminalPrincipal t, HttpServletRequest request) {
        return ApiResponse.ok(pop.session(t, auth.currentWorker(request, t)));
    }

    @GetMapping("/api/pop/home")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<HomeView> home(@AuthenticationPrincipal TerminalPrincipal t, HttpServletRequest request) {
        return ApiResponse.ok(pop.home(t, auth.currentWorker(request, t)));
    }

    @PostMapping("/api/pop/badge")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<BadgeResult> badge(@AuthenticationPrincipal TerminalPrincipal t, @RequestBody BadgeForm form,
            HttpServletRequest request) {
        return ApiResponse.ok(auth.badge(request, t, form));
    }

    @PostMapping("/api/pop/worker/logout")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<String> workerLogout(HttpServletRequest request) {
        auth.workerLogout(request);
        return ApiResponse.ok("OK");
    }

    /** 단말 화면 언어 1탭 전환(ko/en) — 단말 세션에만 */
    @PostMapping("/api/pop/lang")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<String> lang(@RequestBody LangForm form, HttpServletRequest request) {
        String lang = form == null || form.lang() == null ? "" : form.lang().strip().toLowerCase(Locale.ROOT);
        if (!I18nConfig.isSupported(lang)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        request.getSession(true).setAttribute(I18nConfig.SESSION_LOCALE, lang);
        return ApiResponse.ok(lang);
    }
}
