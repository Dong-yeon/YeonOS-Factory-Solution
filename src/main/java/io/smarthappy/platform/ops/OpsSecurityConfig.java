package io.smarthappy.platform.ops;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.filter.OncePerRequestFilter;

import io.smarthappy.platform.common.net.IpMatcher;
import io.smarthappy.platform.common.web.ErrorMessages;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.config.HappyBootProperties;
import io.smarthappy.platform.security.SecurityConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * 운영자 콘솔 보안 체인 (/ops, /api/ops/**) — 회사 사용자 체인보다 먼저(@Order(1)).
 *
 * - 허용 IP 밖이거나 콘솔이 설정되지 않았으면 **404**(콘솔이 있다는 것도 알리지 않는다)
 * - 비밀번호 + TOTP 를 모두 통과해야 ROLE_OPS. 회사 화면(ROLE_USER)과 서로 넘나들 수 없다
 * - 비활동 {@code happyboot.ops.idle-minutes}(기본 15분) 지나면 세션 끝
 * - CSRF·CSP·보안 헤더는 회사 체인과 같다
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class OpsSecurityConfig {

    public static final String LAST_SEEN = OpsSecurityConfig.class.getName() + ".lastSeen";

    @Bean
    @Order(1)
    public SecurityFilterChain opsFilterChain(HttpSecurity http, HappyBootProperties props, ErrorMessages errors)
            throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);
        http
            .securityMatcher("/ops", "/ops/**", "/api/ops/**")
            .authorizeHttpRequests(a -> a
                .requestMatchers("/ops/login", "/api/ops/auth/login", "/api/ops/auth/totp").permitAll()
                .anyRequest().hasRole("OPS"))
            .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()).csrfTokenRequestHandler(csrfHandler))
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable())
            .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()))
            .headers(h -> h
                .frameOptions(fo -> fo.deny())
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                .contentSecurityPolicy(csp -> csp.policyDirectives(SecurityConfig.CONTENT_SECURITY_POLICY))
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                .crossOriginOpenerPolicy(c -> c.policy(   // docs/67 F11
                        org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN)))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> {
                    if (isApi(req)) {
                        errors.write(req, res, HbErrorCode.UNAUTHENTICATED);
                    } else {
                        res.sendRedirect(req.getContextPath() + "/ops/login");
                    }
                })
                .accessDeniedHandler((req, res, ex) -> {
                    if (isApi(req)) {
                        errors.write(req, res, HbErrorCode.FORBIDDEN);
                    } else {
                        res.sendRedirect(req.getContextPath() + "/ops/login");
                    }
                }))
            .addFilterBefore(new GateFilter(props), SecurityContextHolderFilter.class)
            .addFilterBefore(new IdleFilter(Duration.ofMinutes(props.ops().idleMinutes()), errors), AuthorizationFilter.class);
        return http.build();
    }

    static boolean isApi(HttpServletRequest req) {
        return req.getRequestURI().substring(req.getContextPath().length()).startsWith("/api/");
    }

    /** 설정·허용 IP 확인 — 밖이면 404 (콘솔 존재를 숨김) */
    static final class GateFilter extends OncePerRequestFilter {
        private final HappyBootProperties props;
        private final IpMatcher allowed;

        GateFilter(HappyBootProperties props) {
            this.props = props;
            this.allowed = props.ops().enabled() ? IpMatcher.parse(props.ops().allowedIps()).orElse(null) : null;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            if (!props.ops().enabled() || allowed == null || !allowed.matches(req.getRemoteAddr())) {
                res.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            chain.doFilter(req, res);
        }
    }

    /** 운영자 세션 비활동 만료 */
    static final class IdleFilter extends OncePerRequestFilter {
        private final long idleMillis;
        private final ErrorMessages errors;
        private final Clock clock = Clock.systemUTC();

        IdleFilter(Duration idle, ErrorMessages errors) {
            this.idleMillis = idle.toMillis();
            this.errors = errors;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            HttpSession session = req.getSession(false);
            if (a != null && a.getPrincipal() instanceof OpsUser && session != null) {
                long now = clock.millis();
                if (session.getAttribute(LAST_SEEN) instanceof Long last && now - last > idleMillis) {
                    session.invalidate();
                    SecurityContextHolder.clearContext();
                    if (isApi(req)) {
                        errors.write(req, res, HbErrorCode.SESSION_EXPIRED);
                    } else {
                        res.sendRedirect(req.getContextPath() + "/ops/login");
                    }
                    return;
                }
                session.setAttribute(LAST_SEEN, now);
            }
            chain.doFilter(req, res);
        }
    }
}
