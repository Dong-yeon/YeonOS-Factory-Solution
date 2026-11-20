package io.smarthappy.platform.ops;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.auth.ProgAuthExempt;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.ops.OpsAuthService.Operator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

/**
 * 운영자 로그인 (2단계). 1단계 비밀번호가 맞으면 세션에 "MFA 대기"만 남기고(5분), 2단계 TOTP 가 맞아야 ROLE_OPS.
 * TOTP 미등록 운영자는 1단계 응답으로 등록 비밀을 받고, 첫 코드 확인으로 등록과 로그인을 함께 끝낸다.
 */
@RestController
@RequiredArgsConstructor
public class OpsAuthController {

    static final String EXEMPT = "운영자 콘솔 — 별도 보안 체인(허용 IP·비밀번호·TOTP, OpsSecurityConfig)";
    static final String PENDING = OpsAuthController.class.getName() + ".pending";
    static final String PENDING_AT = OpsAuthController.class.getName() + ".pendingAt";
    static final String ENROLL_SECRET = OpsAuthController.class.getName() + ".enrollSecret";
    static final long PENDING_MILLIS = 5 * 60 * 1000L;

    private final OpsAuthService auth;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public record LoginRequest(String loginId, String password) {
    }

    public record CodeRequest(String code) {
    }

    @PostMapping("/api/ops/auth/login")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Map<String, Object>> login(@RequestBody LoginRequest req, HttpServletRequest request) {
        Operator op = auth.checkPassword(req == null ? null : req.loginId(), req == null ? null : req.password(),
                request.getRemoteAddr());
        // 세션 고정 방지: 새 세션을 만들지 않고 ID 만 바꾼다(이전 운영자 상태는 지움)
        HttpSession session = request.getSession(true);
        session.removeAttribute(ENROLL_SECRET);
        request.changeSessionId();
        session.setAttribute(PENDING, op.opId());
        session.setAttribute(PENDING_AT, System.currentTimeMillis());
        Map<String, Object> out = new LinkedHashMap<>();
        if (op.totpEnabled()) {
            out.put("mfa", "VERIFY");
        } else {
            String secret = auth.newEnrollSecret();
            session.setAttribute(ENROLL_SECRET, secret);
            out.put("mfa", "ENROLL");
            out.put("secret", secret);
            out.put("otpauthUri", auth.otpauthUri(op.loginId(), secret));
        }
        return ApiResponse.ok(out);
    }

    @PostMapping("/api/ops/auth/totp")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<OpsUser> totp(@RequestBody CodeRequest req, HttpServletRequest request,
            HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(PENDING) instanceof Long opId)
                || !(session.getAttribute(PENDING_AT) instanceof Long at)
                || System.currentTimeMillis() - at > PENDING_MILLIS) {
            throw new BusinessException(HbErrorCode.OPS_AUTH_FAILED);
        }
        String code = req == null ? null : req.code();
        Operator op = session.getAttribute(ENROLL_SECRET) instanceof String secret
                ? auth.confirmEnroll(opId, secret, code, request.getRemoteAddr())
                : auth.verifyTotp(opId, code, request.getRemoteAddr());
        session.removeAttribute(PENDING);
        session.removeAttribute(PENDING_AT);
        session.removeAttribute(ENROLL_SECRET);
        request.changeSessionId();

        OpsUser user = new OpsUser(op.opId(), op.loginId(), op.opNm());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_OPS"))));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        request.getSession().setAttribute(OpsSecurityConfig.LAST_SEEN, System.currentTimeMillis());
        return ApiResponse.ok(user);
    }

    @GetMapping("/api/ops/auth/me")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<OpsUser> me() {
        return ApiResponse.ok(OpsUser.current().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED)));
    }

    @PostMapping("/api/ops/auth/logout")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<String> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ApiResponse.ok("OK");
    }
}
