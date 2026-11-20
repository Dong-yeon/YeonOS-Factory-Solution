package io.smarthappy.platform.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.config.I18nConfig;
import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.log.AccessLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** 내 정보 화면(sys-my-info) API — 화면 권한 예시: 저장은 SAVE 권한 필요 */
@RestController
@RequiredArgsConstructor
public class MyInfoApiController {

    private final MyInfoService service;
    private final AccessLogService accessLog;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public record LocaleRequest(String localeCd) {
    }

    public record PasswordRequest(String currentPassword, String newPassword) {
    }

    @PostMapping("/api/platform/my-info/password")
    @ProgAuth(prog = "sys-my-info", action = Action.SAVE)
    public ApiResponse<String> changePassword(@RequestBody PasswordRequest req) {
        if (req == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        LoginUser user = CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
        service.changePassword(user, req.currentPassword(), req.newPassword());
        accessLog.recordCurrent(AccessEvent.PASSWORD_CHANGED, "sys-my-info", Action.SAVE.name(),
                AccessLogService.Result.OK, null);
        return ApiResponse.ok("OK");
    }

    @PostMapping("/api/platform/my-info/locale")
    @ProgAuth(prog = "sys-my-info", action = Action.SAVE)
    public ApiResponse<String> changeLocale(@RequestBody LocaleRequest req, HttpServletRequest request,
            HttpServletResponse response) {
        String localeCd = req == null ? null : req.localeCd();
        if (!I18nConfig.isSupported(localeCd)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        LoginUser user = CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
        service.changeLocale(user.userId(), localeCd);

        // 세션의 principal 도 바꿔야 다음 요청부터 새 언어가 적용된다
        LoginUser updated = user.withLocale(localeCd);   // 유형·소속 협력사를 그대로 (새로 만들면 빠진다)
        Authentication old = SecurityContextHolder.getContext().getAuthentication();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(updated, null, old.getAuthorities()));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        return ApiResponse.ok(localeCd);
    }
}
