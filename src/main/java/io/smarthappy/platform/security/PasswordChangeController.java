package io.smarthappy.platform.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import io.smarthappy.platform.auth.ProgAuthExempt;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.log.AccessLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 다음 로그인 때 비밀번호 변경 (docs/36 D1·4.2) — 관리자 초기화·초기 계정의 임시 비밀번호를 바꾸게 한다.
 * 변경이 걸린 로그인은 TenantRequestFilter 가 이 화면·API 만 열어 준다. 권한그룹이 아직 없는 신규 사용자도 바꿀 수 있어야
 * 하므로 화면 권한(@ProgAuth) 대신 대상 = 로그인 사용자 자신으로만 한정한다. 검사는 내 정보와 같은 MyInfoService.
 */
@Controller
@RequiredArgsConstructor
public class PasswordChangeController {

    static final String PAGE = "/password-change";
    static final String API = "/api/platform/password-change";

    private final MyInfoService service;
    private final AccessLogService accessLog;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public record PasswordRequest(String currentPassword, String newPassword) {
    }

    @GetMapping(PAGE)
    public String page(@AuthenticationPrincipal LoginUser user, Model model) {
        if (user == null || !user.pwdChangeRequired()) {
            return "redirect:/main";
        }
        model.addAttribute("user", user);
        return "platform/password-change";
    }

    @PostMapping(API)
    @ResponseBody
    @ProgAuthExempt(reason = "본인 비밀번호 변경 — 대상은 로그인 사용자 자신뿐, 변경이 걸린(권한그룹 없는) 신규 사용자도 열려야 한다")
    public ApiResponse<String> change(@RequestBody PasswordRequest req, HttpServletRequest request,
            HttpServletResponse response) {
        if (req == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        LoginUser user = CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
        service.changePassword(user, req.currentPassword(), req.newPassword());
        accessLog.recordCurrent(AccessEvent.PASSWORD_CHANGED, null, null, AccessLogService.Result.OK, "required");

        // 세션 principal 의 변경 요구를 내려야 다음 요청부터 다른 화면이 열린다
        LoginUser updated = user.withPwdChangeRequired(false);   // 유형·소속 협력사를 그대로
        Authentication old = SecurityContextHolder.getContext().getAuthentication();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(updated, null, old.getAuthorities()));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        return ApiResponse.ok("OK");
    }
}
