package io.smarthappy.module.pop;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import io.smarthappy.platform.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * POP 체인 전용: 단말 쿠키 → ROLE_TERMINAL + 유형 권한(ROLE_TERMINAL_POP·ROLE_TERMINAL_PDA) + 그 회사 요청 범위(행위자 t{id}:{코드}).
 * 유형 권한으로 체인이 경로를 나눈다(POP 실적 API = POP, 창고 /pop/wh·/api/pop/wh = PDA — PopSecurityConfig).
 * 쿠키가 무효(폐기·다시 발급·형식)면 쿠키를 지우고 인증 없이 넘긴다 → 진입점이 401(API)·활성화 화면(화면).
 * 인증은 세션에 저장하지 않는다 — 폐기하면 다음 요청에서 바로 끊긴다.
 *
 * 빈으로 등록하지 않는다(서블릿 필터 이중 등록 방지) — PopSecurityConfig 에서 new 로 만든다.
 */
public class TerminalAuthFilter extends OncePerRequestFilter {

    private final PopAuthService auth;

    public TerminalAuthFilter(PopAuthService auth) {
        this.auth = auth;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String value = cookie(request);
        if (value == null) {
            chain.doFilter(request, response);
            return;
        }
        TerminalPrincipal t = auth.authenticate(value).orElse(null);
        if (t == null) {
            auth.recordDenied(value);
            response.addHeader("Set-Cookie", PopCookies.clear(request.isSecure()));
            chain.doFilter(request, response);
            return;
        }
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(t, null,
                List.of(new SimpleGrantedAuthority("ROLE_TERMINAL"), new SimpleGrantedAuthority("ROLE_TERMINAL_" + t.terminalType()))));
        SecurityContextHolder.setContext(ctx);
        try (TenantContext.Scope ignored = TenantContext.openRequestScope(t.compCd(), t.actor())) {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static String cookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (PopAuthService.COOKIE.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
