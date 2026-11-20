package io.smarthappy.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import io.smarthappy.platform.common.web.ErrorMessages;
import io.smarthappy.platform.config.HappyBootProperties;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.tenant.TenantRunner;

/**
 * 인증·세션·CSRF (Spring Security 7).
 *
 * - 화면: 폼 로그인(/login), 비로그인 접근은 /login 으로 이동
 * - API(/api/**): 비로그인은 302 가 아니라 401 JSON (화면 JS 가 전역으로 잡아 로그인으로 보냄)
 * - CSRF: 화면·API 모두 적용. 토큰은 XSRF-TOKEN 쿠키 → JS 는 X-XSRF-TOKEN 헤더로 보낸다.
 *   (/api/** 도 CSRF 예외로 두지 않는다.) 외부 장비·PDA 용 /ext/** 는 API 키 방식이라 제외(P2 이후)
 * - 세션 고정 방지: 로그인 시 세션 ID 변경
 * - 로그인 후에는 항상 /main 으로 (저장된 요청으로 돌아가지 않음 → API URL·외부 주소로 튕기는 것 방지).
 *   협력사 포털 사용자는 /portal 로 (docs/42 3장)
 */
@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
public class SecurityConfig {

    public static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; "
            + "frame-ancestors 'self'; base-uri 'self'; form-action 'self'";

    /** 모바일 웹앱(docs/74) — 플랫폼 mobile 패키지의 경로와 같은 값(security 가 mobile 을 참조하지 않게 글자로 둔다, MobilePageTest 가 맞는지 본다) */
    public static final String MOBILE_HOME = "/m";
    public static final String MOBILE_MANIFEST = "/m/manifest.webmanifest";

    @Bean
    @org.springframework.core.annotation.Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http, HappyAuthenticationProvider provider,
            TenantRunner runner, UserAccountMapper mapper, ErrorMessages errors, HappyBootProperties props,
            AccessLogService accessLog, MfaService mfa) throws Exception {

        RequestMatcher api = PathPatternRequestMatcher.withDefaults().matcher("/api/**");
        LoginUrlAuthenticationEntryPoint loginPage = new LoginUrlAuthenticationEntryPoint("/login");
        AccessDeniedHandlerImpl pageDenied = new AccessDeniedHandlerImpl();

        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null); // 매 요청 토큰을 읽어 쿠키를 항상 내려준다

        SimpleUrlAuthenticationSuccessHandler success = new SimpleUrlAuthenticationSuccessHandler("/main") {
            @Override
            public void onAuthenticationSuccess(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response,
                    org.springframework.security.core.Authentication authentication)
                    throws java.io.IOException, jakarta.servlet.ServletException {
                // 세션 절대 수명의 기준 (세션 ID 변경 뒤라 새 세션에 기록된다)
                request.getSession().setAttribute(TenantRequestFilter.LOGIN_AT, System.currentTimeMillis());
                // 두 번째 인증이 필요하면 세션에 표시 — 필터가 /mfa 만 연다 (docs/36 14장)
                if (authentication.getPrincipal() instanceof LoginUser u) {
                    mfa.onLogin(u, request.getSession());
                    if (u.supplier()) {
                        clearAuthenticationAttributes(request);
                        getRedirectStrategy().sendRedirect(request, response, PortalBoundaryFilter.PAGE);
                        return;
                    }
                }
                // 모바일 웹앱(docs/74 M4): 로그인 전에 열려던 곳이 /m 아래였으면 고정 주소 /m 으로 — 저장된 주소 자체로는 가지 않는다
                if (wantedMobile(request, response)) {
                    clearAuthenticationAttributes(request);
                    getRedirectStrategy().sendRedirect(request, response, MOBILE_HOME);
                    return;
                }
                super.onAuthenticationSuccess(request, response, authentication);
            }
        };
        success.setAlwaysUseDefaultTargetUrl(true);

        http
            .authorizeHttpRequests(a -> a
                .requestMatchers("/login", "/error", "/css/**", "/js/**", "/images/**", "/vendor/**", "/favicon.ico",
                        "/actuator/health", "/actuator/health/**", "/livez", "/readyz", "/status.json",
                        "/pda/version.json",   // 현장 단말 APK 최소 버전 — 껍데기가 로그인 전에 읽는다(docs/50 V1, 세 칸만)
                        "/sw.js", "/manifest.webmanifest",   // PWA 셸(docs/45 M-1) — 로그인 화면에서 등록·설치, 업무 데이터 없음
                        MOBILE_MANIFEST,   // 모바일 웹앱 manifest(docs/74 M5) — 앱 이름·아이콘뿐, 브라우저가 쿠키 없이 받는다
                        "/api/platform/messages").permitAll()   // 화면 문구 — 공개 정보(로그인 전 화면·운영자 로그인에서도 씀)
                // 회사 사용자(ROLE_USER)만 — 운영자(ROLE_OPS)는 회사 화면·API 를 쓸 수 없다 (/ops 는 OpsSecurityConfig)
                .anyRequest().hasRole("USER"))
            .formLogin(f -> f
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .usernameParameter("loginId")
                .passwordParameter("password")
                .authenticationDetailsSource(LoginDetails::from)
                .successHandler(success)
                .failureHandler(failureHandler())
                .permitAll())
            .logout(l -> l
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .addLogoutHandler((req, res, auth) -> {
                    if (auth != null && auth.getPrincipal() instanceof LoginUser u) {
                        accessLog.record(AccessEvent.LOGOUT, u.compCd(), u.userId(), u.loginId(), null, null,
                                AccessLogService.Result.OK, null);
                    }
                })
                // 공용 PC: 로그아웃 때 브라우저 HTTP 캐시를 비운다(HTTPS 응답에만 붙음, docs/67 F11).
                // "storage" 는 넣지 않는다 — 현장 단말의 오프라인 대기열(IndexedDB)·서비스 워커를 지우면 안 되므로
                .addLogoutHandler(new org.springframework.security.web.authentication.logout.HeaderWriterLogoutHandler(
                        new org.springframework.security.web.header.writers.ClearSiteDataHeaderWriter(
                                org.springframework.security.web.header.writers.ClearSiteDataHeaderWriter.Directive.CACHE)))
                .deleteCookies("JSESSIONID"))
            // 인증 관리자를 명시한다. 전역 AuthenticationManager 를 부모로 두면 실패 때 같은 provider 가 한 번 더 불려
            // 실패 횟수가 2배로 쌓였다(5회 잠금이 3회에 걸림, 2026-10-15 테스트로 발견)
            .authenticationManager(new ProviderManager(provider))
            .csrf(c -> c
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfHandler)
                .ignoringRequestMatchers("/ext/**"))
            .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()))
            // 보안 헤더 (docs/05 4.4). 인라인 스크립트·스타일·이벤트 속성 금지 → TemplateSecurityGuardTest 가 템플릿을 검사
            // Kendo 를 붙일 때(8단계) eval 계열이 필요한지 확인하고, 필요하면 그 예외만 근거와 함께 추가한다(D11)
            .headers(h -> h
                .frameOptions(fo -> fo.sameOrigin())
                // HSTS: HTTPS 응답에만 실린다(로컬 http 에는 영향 없음), 1년 + 하위 도메인
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                // 다른 출처 창이 우리 창을 window.opener 로 잡지 못하게(docs/67 F11) — 같은 출처 팝업(인쇄·도움말)은 그대로
                .crossOriginOpenerPolicy(c -> c.policy(
                        org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN))
                .permissionsPolicyHeader(p -> p.policy("camera=(), microphone=(), geolocation=(), payment=(), usb=()")))
            // 경로로만 나눈다(/api/** → JSON, 그 외 → 로그인 화면). Accept 헤더에 기대면 헤더 없는 요청이 엉뚱한 쪽으로 간다
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> {
                    if (api.matches(req)) {
                        errors.write(req, res, HbErrorCode.UNAUTHENTICATED);
                    } else {
                        loginPage.commence(req, res, ex);
                    }
                })
                .accessDeniedHandler((req, res, ex) -> {
                    if (api.matches(req)) {
                        errors.write(req, res, HbErrorCode.FORBIDDEN);
                    } else {
                        pageDenied.handle(req, res, ex);
                    }
                }))
            .addFilterBefore(new TenantRequestFilter(runner, mapper, errors, props.security().sessionAbsoluteHours(),
                    accessLog), AuthorizationFilter.class)
            // 협력사 포털 경계 — 회사 범위·계정 확인·MFA·비밀번호 변경 강제 뒤 (docs/42 3장)
            .addFilterAfter(new PortalBoundaryFilter(errors), TenantRequestFilter.class);

        return http.build();
    }

    /**
     * 로그인 화면으로 보내기 전에 열려던 요청(세션의 저장된 요청)이 모바일 웹앱 화면이었는가 — 경로만 본다.
     * 맞으면 저장된 요청을 지우고 true(이동할 곳은 늘 고정 {@link #MOBILE_HOME} — 주소·쿼리를 그대로 쓰지 않는다)
     */
    static boolean wantedMobile(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
        var cache = new org.springframework.security.web.savedrequest.HttpSessionRequestCache();
        var saved = cache.getRequest(request, response);
        if (saved == null) {
            return false;
        }
        String path;
        try {
            path = java.net.URI.create(saved.getRedirectUrl()).getPath();
        } catch (IllegalArgumentException e) {
            return false;
        }
        String ctx = request.getContextPath();
        if (path != null && !ctx.isEmpty() && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
        }
        boolean mobile = MOBILE_HOME.equals(path) || (path != null && path.startsWith(MOBILE_HOME + "/"));
        if (mobile) {
            cache.removeRequest(request, response);
        }
        return mobile;
    }

    /** 실패 사유를 코드로만 넘긴다 (화면 문구는 messages.properties) */
    private static AuthenticationFailureHandler failureHandler() {
        return (request, response, exception) -> {
            String code = exception instanceof LoginThrottledException ? "throttled"
                    : exception instanceof LockedException ? "locked"
                    : exception instanceof DisabledException ? "inactive" : "bad";
            response.sendRedirect(request.getContextPath() + "/login?error=" + code);
        };
    }
}
