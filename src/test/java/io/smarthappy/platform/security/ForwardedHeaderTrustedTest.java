package io.smarthappy.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 신뢰 프록시(TRUSTED_PROXIES)를 거친 요청 — 프록시가 넘긴 실제 접속 IP·https 를 쓴다 (docs/23 E6).
 * 테스트 클라이언트(127.0.0.1)를 프록시로 신뢰하도록 설정.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1",
        "happyboot.security.ip-comp-max-fail=2",
        "happyboot.security.ip-max-fail=1000"})
class ForwardedHeaderTrustedTest extends ForwardedHeaderTestBase {

    @Test
    void 접속_로그에_프록시가_넘긴_실제_IP가_남는다() throws Exception {
        String comp = accounts.newComp("FH");
        accounts.newUser(comp, "kim", PW);
        assertThat(loginError(comp, "wrong-1", "198.51.100.7")).isEqualTo("bad");
        assertThat(failIps(comp)).containsExactly("198.51.100.7");
    }

    @Test
    void 시도_제한은_실제_IP별로_센다_한_사람이_모두를_막지_못한다() throws Exception {
        String comp = accounts.newComp("FH");
        accounts.newUser(comp, "kim", PW);
        loginError(comp, "wrong-1", "198.51.100.21");
        loginError(comp, "wrong-2", "198.51.100.21");
        assertThat(loginError(comp, "wrong-3", "198.51.100.21")).as("같은 IP 는 한도에서 차단").isEqualTo("throttled");
        assertThat(loginError(comp, "wrong-4", "198.51.100.22")).as("다른 IP 는 영향 없음").isEqualTo("bad");
    }

    @Test
    void 클라이언트가_앞에_끼워_넣은_XFF는_쓰지_않는다() throws Exception {
        // 프록시는 받은 XFF 뒤에 실제 IP 를 덧붙인다 → "위조값, 실제IP". 신뢰 프록시가 아닌 가장 오른쪽 값이 실제 IP.
        String comp = accounts.newComp("FH");
        accounts.newUser(comp, "kim", PW);
        loginError(comp, "wrong-1", "10.0.0.99, 198.51.100.31");
        assertThat(failIps(comp)).containsExactly("198.51.100.31");
    }

    @Test
    void 프록시가_https라고_알리면_HSTS를_보낸다() throws Exception {
        assertThat(getLogin(Map.of("X-Forwarded-Proto", "https")).headers()
                .firstValue("Strict-Transport-Security")).isPresent();
    }
}
