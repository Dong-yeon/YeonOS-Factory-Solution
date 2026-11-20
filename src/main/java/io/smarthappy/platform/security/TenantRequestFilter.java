package io.smarthappy.platform.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import io.smarthappy.platform.common.web.ErrorMessages;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.tenant.TenantRunner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * 로그인 사용자의 회사로 요청 범위를 연다 (Spring Security 필터 체인 안, 인가 직전).
 *
 * 매 요청마다 계정·회사가 아직 사용 가능한지 확인한다. 사용중지·잠금·회사 정지가 되면
 * 이미 로그인된 세션도 다음 요청에서 끊긴다(이상 사용 E: "사용 안 함 처리 후 기존 세션으로 처리되지 않는지").
 * 정적 리소스는 검사하지 않는다.
 *
 * 다음 로그인 때 비밀번호 변경(pwd_change_req_yn, 관리자 초기화·초기 계정)이 걸린 로그인은 변경 화면·API 와
 * 세션·문구만 열린다 — 그 밖은 API 403 PASSWORD_CHANGE_REQUIRED, 화면 → /password-change (docs/36 D1).
 *
 * 두 번째 인증(MFA, docs/36 14장)이 걸린 로그인은 그보다 먼저 MFA 화면·API 만 열린다(API 403 MFA_REQUIRED, 화면 → /mfa).
 *
 * 빈으로 등록하지 않는다(서블릿 필터로 이중 등록 방지) — SecurityConfig 에서 new 로 만든다.
 */
public class TenantRequestFilter extends OncePerRequestFilter {

    /** 세션 속성: 로그인 시각(epoch ms). 로그인 성공 처리기가 넣는다 */
    public static final String LOGIN_AT = TenantRequestFilter.class.getName() + ".loginAt";
    /** 로그 MDC: 로그인 사용자 id(숫자) */
    public static final String MDC_USER = "userId";

    /** 비밀번호 변경이 걸린 로그인에도 열리는 경로 (로그아웃은 이 필터 앞 LogoutFilter 가 처리) */
    /** 두 번째 인증(MFA) 대기 중에 열리는 경로 (docs/36 14장) — 비밀번호 변경보다 먼저 */
    static final Set<String> MFA_PATHS = Set.of(MfaController.PAGE, MfaController.API + "/verify",
            MfaController.API + "/enroll/start", MfaController.API + "/enroll/confirm", MfaController.API + "/status",
            "/api/platform/session", "/api/platform/messages", "/error");

    static final Set<String> PASSWORD_CHANGE_PATHS = Set.of(PasswordChangeController.PAGE, PasswordChangeController.API,
            "/api/platform/session", "/api/platform/messages", "/error");

    private final TenantRunner runner;
    private final UserAccountMapper mapper;
    private final ErrorMessages errors;
    private final long absoluteMillis;
    private final AccessLogService accessLog;
    private Clock clock = Clock.systemUTC();

    public TenantRequestFilter(TenantRunner runner, UserAccountMapper mapper, ErrorMessages errors,
            int sessionAbsoluteHours, AccessLogService accessLog) {
        this.runner = runner;
        this.mapper = mapper;
        this.errors = errors;
        this.absoluteMillis = Duration.ofHours(sessionAbsoluteHours).toMillis();
        this.accessLog = accessLog;
    }

    /** 테스트용 시계 교체 */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI().substring(request.getContextPath().length());
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

        if (expired(request)) {
            accessLog.record(AccessEvent.SESSION_EXPIRED, user.compCd(), user.userId(), user.loginId(), null, null,
                    AccessLogService.Result.DENIED, null);
            end(request, response, HbErrorCode.SESSION_EXPIRED, "/login?expired");
            return;
        }
        OffsetDateTime loginAt = loginAt(request);
        boolean active = runner.call(user.compCd(), () -> mapper.countActive(user.userId(), loginAt) > 0);
        if (!active) {
            accessLog.record(AccessEvent.SESSION_REVOKED, user.compCd(), user.userId(), user.loginId(), null, null,
                    AccessLogService.Result.DENIED, null);
            end(request, response, HbErrorCode.SESSION_REVOKED, "/login?error=inactive");
            return;
        }

        String path = request.getRequestURI().substring(request.getContextPath().length());
        HttpSession session = request.getSession(false);
        boolean mfaPending = session != null && session.getAttribute(MfaService.SESSION_STATE) != null;
        if (mfaPending && !MFA_PATHS.contains(path)) {
            if (path.startsWith("/api/")) {
                errors.write(request, response, HbErrorCode.MFA_REQUIRED);
            } else {
                response.sendRedirect(request.getContextPath() + MfaController.PAGE);
            }
            return;
        }

        // MFA 대기 중의 MFA 경로는 비밀번호 변경 강제보다 먼저 통과(두 번째 요소 → 비밀번호 순서)
        if (!mfaPending && user.pwdChangeRequired()
                && !PASSWORD_CHANGE_PATHS.contains(request.getRequestURI().substring(request.getContextPath().length()))) {
            if (request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/")) {
                errors.write(request, response, HbErrorCode.PASSWORD_CHANGE_REQUIRED);
            } else {
                response.sendRedirect(request.getContextPath() + PasswordChangeController.PAGE);
            }
            return;
        }

        // 로그 줄의 사용자(MDC userId, docs/52 Y2) — 숫자 id 만. 로그인 아이디·세션 ID 는 넣지 않는다
        org.slf4j.MDC.put(MDC_USER, String.valueOf(user.userId()));
        try (TenantContext.Scope ignored = TenantContext.openRequestScope(user.compCd(),
                "u" + user.userId() + ":" + user.loginId())) {
            chain.doFilter(request, response);
        } finally {
            org.slf4j.MDC.remove(MDC_USER);
        }
    }

    /**
     * 절대 수명: 활동과 관계없이 로그인 후 일정 시간이 지나면 끝낸다 (docs/05 G7, 공장 공용 PC).
     * 로그인 시각이 없는 세션(이 기능 이전 세션 등)은 지금을 로그인 시각으로 기록한다.
     */
    private boolean expired(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }
        long now = clock.millis();
        if (!(session.getAttribute(LOGIN_AT) instanceof Long loginAt)) {
            session.setAttribute(LOGIN_AT, now);
            return false;
        }
        return now - loginAt > absoluteMillis;
    }

    /** 세션의 로그인 시각 (세션이 없으면 null — 무효 기준을 보지 않는다) */
    private static OffsetDateTime loginAt(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(LOGIN_AT) instanceof Long ms
                ? OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC) : null;
    }

    private void end(HttpServletRequest request, HttpServletResponse response, HbErrorCode code, String pagePath)
            throws IOException {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        if (request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/")) {
            errors.write(request, response, code);
        } else {
            response.sendRedirect(request.getContextPath() + pagePath);
        }
    }
}
