package io.smarthappy.platform.metrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import io.smarthappy.platform.common.net.IpMatcher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 지표 수집 끝점 보안 (docs/52 Y2) — /actuator/prometheus 는 회사 사용자 체인보다 먼저(@Order(0)) 이 체인이 맡는다.
 *
 * <ul>
 * <li>허용 IP(happyboot.metrics.allowed-ips, 기본 이 PC 만) 밖이면 404 — 끝점이 있는지도 알리지 않는다(운영자 콘솔과 같은 방식)</li>
 * <li>토큰(happyboot.metrics.token)이 있으면 Authorization: Bearer 가 맞아야 한다(상수 시간 비교). 틀려도 404</li>
 * <li>회사 사용자 로그인과 무관하다 — 관리자라도 밖에서는 못 본다. 지표는 회사를 가로지르는 운영 정보라 회사 화면 권한(@ProgAuth)으로 열지 않는다</li>
 * <li>세션·CSRF 없음(GET 만, 수집기는 쿠키가 없다)</li>
 * </ul>
 * 운영(prod)에서는 관리 포트 분리·내부 주소 바인딩·토큰을 {@code ProdStartupGuard} 가 확인한다.
 */
@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
public class MetricsSecurityConfig {

    public static final String PATH = "/actuator/prometheus";

    @Bean
    @Order(0)
    public SecurityFilterChain metricsFilterChain(HttpSecurity http, MetricsProperties props) throws Exception {
        http
            .securityMatcher(PATH, PATH + "/**")
            .authorizeHttpRequests(a -> a.anyRequest().permitAll())
            .csrf(c -> c.disable())
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable())
            .requestCache(r -> r.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new GateFilter(props), SecurityContextHolderFilter.class);
        return http.build();
    }

    /** 허용 IP + (있으면) 토큰 — 아니면 404 */
    static final class GateFilter extends OncePerRequestFilter {
        private final IpMatcher allowed;
        private final byte[] token;

        GateFilter(MetricsProperties props) {
            this.allowed = IpMatcher.parse(props.allowedIps()).orElse(null);
            this.token = props.token().isEmpty() ? null : props.token().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
            if (!"GET".equals(req.getMethod()) || allowed == null || !allowed.matches(req.getRemoteAddr()) || !tokenOk(req)) {
                res.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            chain.doFilter(req, res);
        }

        private boolean tokenOk(HttpServletRequest req) {
            if (token == null) {
                return true;
            }
            String h = req.getHeader(HttpHeaders.AUTHORIZATION);
            if (h == null || !h.startsWith("Bearer ")) {
                return false;
            }
            return MessageDigest.isEqual(token, h.substring(7).trim().getBytes(StandardCharsets.UTF_8));
        }
    }
}
