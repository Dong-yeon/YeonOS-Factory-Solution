package io.smarthappy.module.cms;

import java.io.IOException;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.filter.OncePerRequestFilter;

import io.smarthappy.module.pop.PopAuthService;
import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.platform.common.web.ErrorMessages;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.SecurityConfig;
import io.smarthappy.platform.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 외부 기기 API 체인 (/ext/**) — 수집 장치(sys_terminal 유형 COLLECTOR)의 Bearer API 키 → ROLE_DEVICE (docs/25 4.8).
 * 쿠키·세션을 쓰지 않으므로 CSRF 없음. 사람 로그인·POP 단말 쿠키로는 들어올 수 없다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class ExtSecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain extFilterChain(HttpSecurity http, PopAuthService auth, ErrorMessages errors) throws Exception {
        http
            .securityMatcher("/ext/**")
            .authorizeHttpRequests(a -> a.anyRequest().hasRole("DEVICE"))
            .securityContext(c -> c.securityContextRepository(new RequestAttributeSecurityContextRepository()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(r -> r.disable())
            .csrf(c -> c.disable())
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable())
            .anonymous(a -> a.disable())
            .headers(h -> h
                .frameOptions(fo -> fo.deny())
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                .contentSecurityPolicy(csp -> csp.policyDirectives(SecurityConfig.CONTENT_SECURITY_POLICY))
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> errors.write(req, res, HbErrorCode.UNAUTHENTICATED))
                .accessDeniedHandler((req, res, ex) -> errors.write(req, res, HbErrorCode.UNAUTHENTICATED)))
            .addFilterBefore(new DeviceAuthFilter(auth, new DeniedLogBudget(DeniedLogBudget.PER_MINUTE)), AuthorizationFilter.class);
        return http.build();
    }

    /**
     * 잘못된 키 기록 상한 — 같은 출처(IP, IPv6 는 /64)에서 1분에 {@link #PER_MINUTE}건까지만 접속 로그에 남긴다(docs/67 F9).
     * 키는 32바이트 난수라 추측은 막을 필요가 없고, 막아야 할 것은 <b>틀린 키를 계속 보내 로그 테이블을 부풀리는 것</b>이다.
     * 요청 자체는 막지 않는다 — 공장 NAT 뒤 고장 난 장치 하나 때문에 같은 IP 의 정상 장치까지 끊기지 않게(응답은 늘 401).
     * 메모리는 키 수 상한으로 묶고 넘으면 비운다(기록이 조금 더 남을 뿐 — 막는 쪽이 아니라서 안전).
     */
    static final class DeniedLogBudget {
        static final int PER_MINUTE = 30;
        static final int MAX_KEYS = 10_000;
        private final int perMinute;
        private final java.util.concurrent.ConcurrentHashMap<String, long[]> buckets = new java.util.concurrent.ConcurrentHashMap<>();
        private java.time.Clock clock = java.time.Clock.systemUTC();

        DeniedLogBudget(int perMinute) {
            this.perMinute = perMinute;
        }

        void setClock(java.time.Clock clock) {
            this.clock = clock;
        }

        /** 이번 거부를 기록해도 되면 true(1분 창 안 perMinute 건까지) */
        boolean tryLog(String remoteAddr) {
            if (buckets.size() > MAX_KEYS) {
                buckets.clear();
            }
            long minute = clock.millis() / 60_000;
            long[] b = buckets.compute(io.smarthappy.platform.security.LoginAttemptLimiter.source(remoteAddr),
                    (k, v) -> v == null || v[0] != minute ? new long[] {minute, 1} : new long[] {minute, v[1] + 1});
            return b[1] <= perMinute;
        }
    }

    /** Authorization: Bearer 회사.id.키 → 수집 장치 + 그 회사 요청 범위(행위자 d{id}:{코드}) */
    static final class DeviceAuthFilter extends OncePerRequestFilter {
        private final PopAuthService auth;
        private final DeniedLogBudget budget;

        DeviceAuthFilter(PopAuthService auth, DeniedLogBudget budget) {
            this.auth = auth;
            this.budget = budget;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String h = request.getHeader("Authorization");
            String value = h != null && h.regionMatches(true, 0, "Bearer ", 0, 7) ? h.substring(7).strip() : null;
            TerminalPrincipal d = value == null ? null : auth.authenticateDevice(value).orElse(null);
            if (d == null) {
                if (value != null && budget.tryLog(request.getRemoteAddr())) {
                    auth.recordDenied(value, "BEARER");
                }
                chain.doFilter(request, response);
                return;
            }
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(d, null,
                    List.of(new SimpleGrantedAuthority("ROLE_DEVICE"))));
            SecurityContextHolder.setContext(ctx);
            try (TenantContext.Scope ignored = TenantContext.openRequestScope(d.compCd(), "d" + d.terminalId() + ":" + d.terminalCd())) {
                chain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }
}
