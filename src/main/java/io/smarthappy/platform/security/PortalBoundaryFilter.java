package io.smarthappy.platform.security;

import java.io.IOException;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import io.smarthappy.platform.common.web.ErrorMessages;
import io.smarthappy.platform.common.web.HbErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 협력사 포털 경계 (docs/42 3장). TenantRequestFilter 바로 뒤 — 회사 범위·계정 유효 확인·MFA·비밀번호 변경 강제가 먼저다.
 *
 * - 협력사 사용자(SUPPLIER)는 허용 목록만: 포털 화면(/portal, /portal/**)·포털 API(/api/portal/**)·세션·문구·비밀번호 변경·MFA·/error.
 *   그 밖은 API 403 PORTAL_FORBIDDEN, 화면 → /portal. (로그아웃은 이 필터 앞 LogoutFilter 가 처리)
 * - 회사 사용자는 포털 경로를 쓰지 않는다: /api/portal/** 403 PORTAL_FORBIDDEN, /portal 화면 → 403.
 * - 두 겹: 이 필터를 빠져나가도 @ProgAuth 는 협력사 사용자를 늘 거부하고(PermissionService), @PortalApi 는 회사 사용자를 거부한다.
 *
 * 빈으로 등록하지 않는다(서블릿 필터로 이중 등록 방지) — SecurityConfig 에서 new 로 만든다.
 */
public class PortalBoundaryFilter extends OncePerRequestFilter {

    public static final String PAGE = "/portal";
    public static final String API = "/api/portal";

    /** 포털 밖에서 협력사 사용자에게 열리는 경로 — 로그인한 누구나 쓰는 자기 계정 기능만 */
    static final Set<String> SUPPLIER_PATHS = Set.of("/api/platform/session", "/api/platform/messages", "/error",
            PasswordChangeController.PAGE, PasswordChangeController.API,
            MfaController.PAGE, MfaController.API + "/verify", MfaController.API + "/enroll/start",
            MfaController.API + "/enroll/confirm", MfaController.API + "/status");

    private final ErrorMessages errors;

    public PortalBoundaryFilter(ErrorMessages errors) {
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = path(request);
        return uri.startsWith("/css/") || uri.startsWith("/js/") || uri.startsWith("/images/")
                || uri.startsWith("/vendor/") || uri.equals("/favicon.ico");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof LoginUser user)) {
            chain.doFilter(request, response);
            return;
        }
        String path = path(request);
        boolean portal = isPortal(path);
        if (user.supplier() && !portal && !SUPPLIER_PATHS.contains(path)) {
            if (path.startsWith("/api/")) {
                errors.write(request, response, HbErrorCode.PORTAL_FORBIDDEN);
            } else {
                response.sendRedirect(request.getContextPath() + PAGE);
            }
            return;
        }
        if (!user.supplier() && portal) {
            if (path.startsWith("/api/")) {
                errors.write(request, response, HbErrorCode.PORTAL_FORBIDDEN);
            } else {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
            }
            return;
        }
        chain.doFilter(request, response);
    }

    /** 포털 경로 — 접두어만으로 보지 않는다(/portalX, /api/portalX 는 포털이 아니다) */
    static boolean isPortal(String path) {
        return path.equals(PAGE) || path.startsWith(PAGE + "/") || path.equals(API) || path.startsWith(API + "/");
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
