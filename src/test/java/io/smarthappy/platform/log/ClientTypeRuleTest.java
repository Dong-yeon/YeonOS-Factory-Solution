package io.smarthappy.platform.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import io.smarthappy.platform.security.LoginUser;

/**
 * 접속 로그 단말 종류 규칙 (docs/50 Q9, QA D-2 2026-10-27) — 헤더는 단말 경로에서 단말 주체가 아직 없을 때만,
 * 사무·포털 세션은 늘 WEB, 단말 주체는 등록 유형, 로그인 전 사무 요청은 API 만 인정.
 */
class ClientTypeRuleTest {

    record Terminal(String clientType) implements ClientTyped {
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    static MockHttpServletRequest req(String uri, String header) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", uri);
        if (header != null) {
            r.addHeader(AccessLogService.CLIENT_HEADER, header);
        }
        return r;
    }

    static void as(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    void 사무_세션은_어떤_헤더든_WEB() {
        as(new LoginUser(1, "A", "kim", "kim", "ko", false));
        for (String h : new String[] {"PDA", "POP", "API", "SYSTEM", null}) {
            assertThat(AccessLogService.clientTypeOf(req("/api/admin/access-logs", h))).as(h).isEqualTo("WEB");
        }
        // 단말 경로를 사무 세션으로 불러도(체인이 막지만) 헤더로 단말이 되지 않는다
        assertThat(AccessLogService.clientTypeOf(req("/api/pop/home", "PDA"))).isEqualTo("WEB");
    }

    @Test
    void 단말_주체는_등록_유형_헤더_무시() {
        as(new Terminal("PDA"));
        assertThat(AccessLogService.clientTypeOf(req("/api/pop/wh/scan", "POP"))).isEqualTo("PDA");
        as(new Terminal("POP"));
        assertThat(AccessLogService.clientTypeOf(req("/api/pop/home", "PDA"))).isEqualTo("POP");
    }

    @Test
    void 단말_경로_로그인_전은_헤더_D1() {
        assertThat(AccessLogService.clientTypeOf(req("/api/pop/activate", "POP"))).isEqualTo("POP");
        assertThat(AccessLogService.clientTypeOf(req("/pop/activate", "pda"))).isEqualTo("PDA");
        assertThat(AccessLogService.clientTypeOf(req("/pop", null))).isEqualTo("WEB");
        assertThat(AccessLogService.clientTypeOf(req("/api/pop/activate", "x' or 1=1"))).isEqualTo("WEB");
    }

    @Test
    void 로그인_전_사무_요청은_API_만_인정() {
        assertThat(AccessLogService.clientTypeOf(req("/login", "PDA"))).isEqualTo("WEB");
        assertThat(AccessLogService.clientTypeOf(req("/login", "POP"))).isEqualTo("WEB");
        assertThat(AccessLogService.clientTypeOf(req("/login", "api"))).isEqualTo("API");
        assertThat(AccessLogService.clientTypeOf(req("/popx", "PDA"))).isEqualTo("WEB");   // 접두어만 같은 경로
        assertThat(AccessLogService.clientTypeOf(req("/api/popular", "POP"))).isEqualTo("WEB");
    }
}
