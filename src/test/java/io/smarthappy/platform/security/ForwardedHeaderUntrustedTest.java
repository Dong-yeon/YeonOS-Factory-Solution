package io.smarthappy.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 기본 설정(TRUSTED_PROXIES 비어 있음) — 아무도 프록시로 믿지 않는다. 클라이언트가 보낸 X-Forwarded-* 로
 * IP 를 바꿔 시도 제한을 피하거나, 로그를 속이거나, https 인 척할 수 없어야 한다 (docs/23 E6, 이상 사용 T3).
 * 127.0.0.1 은 Tomcat 기본 신뢰 대역에 들어 있으므로, 비워 둔 설정이 기본 대역으로 되돌아가면 이 테스트가 잡는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "happyboot.security.ip-comp-max-fail=2",
        "happyboot.security.ip-max-fail=1000"})
class ForwardedHeaderUntrustedTest extends ForwardedHeaderTestBase {

    @Test
    void XFF를_바꿔가며_보내도_실제_접속_주소로_센다() throws Exception {
        String comp = accounts.newComp("FU");
        accounts.newUser(comp, "kim", PW);
        loginError(comp, "wrong-1", "198.51.100.41");
        loginError(comp, "wrong-2", "198.51.100.42");
        assertThat(loginError(comp, "wrong-3", "198.51.100.43")).isEqualTo("throttled");
    }

    @Test
    void 접속_로그의_IP를_XFF로_속일_수_없다() throws Exception {
        String comp = accounts.newComp("FU");
        accounts.newUser(comp, "kim", PW);
        loginError(comp, "wrong-1", "203.0.113.66");
        assertThat(failIps(comp)).containsExactly("127.0.0.1");
    }

    @Test
    void https인_척하는_헤더는_무시한다() throws Exception {
        assertThat(getLogin(Map.of("X-Forwarded-Proto", "https")).headers()
                .firstValue("Strict-Transport-Security")).isEmpty();
    }
}
