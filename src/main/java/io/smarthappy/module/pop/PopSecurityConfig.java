package io.smarthappy.module.pop;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import io.smarthappy.platform.common.web.ErrorMessages;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.SecurityConfig;
import jakarta.servlet.http.HttpServletRequest;

/**
 * POP 단말 보안 체인 (/pop, /pop/**, /api/pop/**) — 운영자·회사 체인보다 먼저(@Order(0)), docs/25 4.6.
 * 단말 유형별 경로(docs/25 4.33): 창고 화면·API(/pop/wh, /api/pop/wh — WMS PDA) = PDA 만, 공정 할 일·실적 API = POP 만,
 * 세션·배지·작업자 로그아웃·언어 = 둘 다. 다른 유형이 부르면 API = 403, 화면 = 자기 홈(/pop → PDA 는 /pop/wh 로).
 *
 * - 사람 로그인이 아니라 등록된 단말(쿠키) → ROLE_TERMINAL. 활성화 화면·API 만 permitAll
 * - 회사 사용자(ROLE_USER) 세션은 이 체인에서 읽지 않는다(인증 저장소가 요청 속성) → 사무 사용자는 POP API 를 못 쓴다.
 *   단말은 사무 화면·API(회사 체인)를 못 쓴다(그 체인은 단말 쿠키를 모른다)
 * - CSRF(쿠키 → 헤더)·CSP·보안 헤더는 회사 체인과 같다
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class PopSecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain popFilterChain(HttpSecurity http, PopAuthService auth, ErrorMessages errors) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);
        http
            .securityMatcher("/pop", "/pop/**", "/api/pop/**")
            .authorizeHttpRequests(a -> a
                .requestMatchers("/pop/activate", "/api/pop/activate").permitAll()
                .requestMatchers(PDA_PATHS).hasRole("TERMINAL_PDA")
                .requestMatchers(POP_PATHS).hasRole("TERMINAL_POP")
                .anyRequest().hasRole("TERMINAL"))
            .securityContext(c -> c.securityContextRepository(new RequestAttributeSecurityContextRepository()))
            .requestCache(r -> r.disable())
            .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()).csrfTokenRequestHandler(csrfHandler))
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable())
            .headers(h -> h
                .frameOptions(fo -> fo.sameOrigin())
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                .contentSecurityPolicy(csp -> csp.policyDirectives(SecurityConfig.CONTENT_SECURITY_POLICY))
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                .permissionsPolicyHeader(p -> p.policy("camera=(), microphone=(), geolocation=(), payment=(), usb=()")))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> {
                    if (isApi(req)) {
                        errors.write(req, res, HbErrorCode.TERMINAL_UNAUTHENTICATED);
                    } else {
                        res.sendRedirect(req.getContextPath() + "/pop/activate");
                    }
                })
                .accessDeniedHandler((req, res, ex) -> {
                    if (isApi(req)) {
                        errors.write(req, res, HbErrorCode.FORBIDDEN);
                    } else {
                        res.sendRedirect(req.getContextPath() + "/pop");   // 다른 유형 화면 → 자기 홈(PopPageController 가 유형별로)
                    }
                }))
            .addFilterBefore(new TerminalAuthFilter(auth), AuthorizationFilter.class);
        return http.build();
    }

    /** 창고 단말(PDA, WMS 화면·API) — 경로 주인은 WMS(module/wms/pda), 보안 체인은 여기 하나 */
    static final String[] PDA_PATHS = {"/pop/wh", "/pop/wh/**", "/api/pop/wh/**"};

    /** 공정 단말(POP) — 할 일·공정·실적·투입·파라미터·정정·고장 신고·설비 점검 */
    static final String[] POP_PATHS = {"/api/pop/home", "/api/pop/procs/**", "/api/pop/lots", "/api/pop/results/**", "/api/pop/params/**",
        "/api/pop/breakdown", "/api/pop/breakdown/**", "/api/pop/approvals", "/api/pop/pm/**"};

    static boolean isApi(HttpServletRequest req) {
        return req.getRequestURI().substring(req.getContextPath().length()).startsWith("/api/");
    }
}
