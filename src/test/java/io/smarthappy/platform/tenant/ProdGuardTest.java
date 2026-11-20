package io.smarthappy.platform.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;

import io.smarthappy.support.PostgresTestBase;

/** 운영 기동 가드·소유자 롤 멤버 검사·status.json·HSTS·헬스 분리 (P0 9단계) */
@AutoConfigureMockMvc
class ProdGuardTest extends PostgresTestBase {

    private static final Path STATUS = Path.of("build/test-status.json");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanup() throws Exception {
        Files.deleteIfExists(STATUS);
    }

    private static MockEnvironment okEnv() {
        return new MockEnvironment()
                .withProperty("spring.flyway.enabled", "false")
                .withProperty("spring.datasource.password", "a-real-secret-value")
                .withProperty("happyboot.ops.secret-key", java.util.Base64.getEncoder().encodeToString(new byte[32]))
                .withProperty("server.servlet.session.cookie.secure", "true");
    }

    @Test
    void 올바른_운영_설정은_통과() {
        assertThat(ProdStartupGuard.check(okEnv(), 9, 9)).isEmpty();
    }

    @Test
    void 운영_설정_실수는_모두_잡는다() {
        MockEnvironment bad = new MockEnvironment()
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("spring.flyway.password", "owner-secret")
                .withProperty("spring.datasource.password", "change-me-app")
                .withProperty("happyboot.dev.admin-password", "x")
                .withProperty("happyboot.dev.login-prefill", "true")
                .withProperty("server.servlet.session.cookie.secure", "false");
        List<String> v = ProdStartupGuard.check(bad, 9, 7);
        assertThat(v).hasSize(8)
                .anyMatch(s -> s.contains("flyway.enabled"))
                .anyMatch(s -> s.contains("소유자 비밀번호"))
                .anyMatch(s -> s.contains("v9") && s.contains("v7"))
                .anyMatch(s -> s.contains("예시"))
                .anyMatch(s -> s.contains("개발 계정"))
                .anyMatch(s -> s.contains("미리 채우기"))
                .anyMatch(s -> s.contains("secure"))
                .anyMatch(s -> s.contains("MFA 키"));
        // MFA 키 형식이 틀림 / MFA 전용 키가 있으면 ops 키 없이도 통과
        assertThat(ProdStartupGuard.check(okEnv().withProperty("happyboot.ops.secret-key", "short"), 9, 9))
                .anyMatch(s -> s.contains("형식"));
        assertThat(ProdStartupGuard.check(okEnv().withProperty("happyboot.ops.secret-key", "")
                .withProperty("happyboot.mfa.secret-key", java.util.Base64.getEncoder().encodeToString(new byte[32])), 9, 9)).isEmpty();
        // flyway.enabled 를 아예 안 적은 경우도 기본값 true 로 보고 막는다
        assertThat(ProdStartupGuard.check(new MockEnvironment()
                .withProperty("spring.datasource.password", "a-real-secret-value")
                .withProperty("server.servlet.session.cookie.secure", "true"), 9, 9))
                .anyMatch(s -> s.contains("flyway.enabled"));
        // 빈 비밀번호
        assertThat(ProdStartupGuard.check(okEnv().withProperty("spring.datasource.password", " "), 9, 9))
                .anyMatch(s -> s.contains("예시"));
    }

    @Test
    void 운영_지표를_열면_관리_포트_분리_내부_주소_토큰_수집기_IP_를_본다() {
        // 닫혀 있으면(기본 none) 아무것도 요구하지 않는다
        assertThat(ProdStartupGuard.check(okEnv().withProperty("management.endpoint.prometheus.access", "none"), 9, 9)).isEmpty();
        MockEnvironment ok = okEnv().withProperty("management.endpoint.prometheus.access", "read-only")
                .withProperty("server.port", "8090").withProperty("management.server.port", "9464")
                .withProperty("management.server.address", "10.0.0.5")
                .withProperty("happyboot.metrics.token", "m".repeat(40))
                .withProperty("happyboot.metrics.allowed-ips", "10.0.0.0/24");
        assertThat(ProdStartupGuard.check(ok, 9, 9)).isEmpty();
        // 열었는데 아무것도 안 함 → 포트·주소·토큰 3건(허용 IP 기본값은 루프백이라 통과)
        assertThat(ProdStartupGuard.check(okEnv().withProperty("management.endpoint.prometheus.access", "read-only")
                .withProperty("happyboot.metrics.allowed-ips", "127.0.0.1/32"), 9, 9)).hasSize(3)
                .anyMatch(s -> s.contains("MANAGEMENT_SERVER_PORT")).anyMatch(s -> s.contains("MANAGEMENT_SERVER_ADDRESS"))
                .anyMatch(s -> s.contains("METRICS_TOKEN"));
        // 같은 포트 · 모든 주소(0.0.0.0) · 공인 주소 · 호스트 이름 · 짧은 토큰 · 공인 수집기 대역
        assertThat(ProdStartupGuard.check(withProp(ok, "management.server.port", "8090"), 9, 9)).anyMatch(s -> s.contains("MANAGEMENT_SERVER_PORT"));
        for (String bad : new String[] {"0.0.0.0", "::", "203.0.113.4", "metrics.example.com", ""}) {
            assertThat(ProdStartupGuard.check(withProp(ok, "management.server.address", bad), 9, 9)).as(bad)
                    .anyMatch(s -> s.contains("MANAGEMENT_SERVER_ADDRESS"));
        }
        for (String good : new String[] {"127.0.0.1", "192.168.10.2", "172.16.0.9", "::1", "fd00::5"}) {
            assertThat(ProdStartupGuard.check(withProp(ok, "management.server.address", good), 9, 9)).as(good).isEmpty();
        }
        assertThat(ProdStartupGuard.check(withProp(ok, "happyboot.metrics.token", "short"), 9, 9)).anyMatch(s -> s.contains("METRICS_TOKEN"));
        for (String wide : new String[] {"0.0.0.0/0", "10.0.0.0/8,8.8.8.0/24", "::/0"}) {
            assertThat(ProdStartupGuard.check(withProp(ok, "happyboot.metrics.allowed-ips", wide), 9, 9)).as(wide)
                    .anyMatch(s -> s.contains("METRICS_ALLOWED_IPS"));
        }
    }

    private static MockEnvironment withProp(MockEnvironment base, String key, String value) {
        MockEnvironment e = new MockEnvironment();
        for (String k : new String[] {"spring.flyway.enabled", "spring.datasource.password", "happyboot.ops.secret-key",
                "server.servlet.session.cookie.secure", "management.endpoint.prometheus.access", "server.port",
                "management.server.port", "management.server.address", "happyboot.metrics.token", "happyboot.metrics.allowed-ips"}) {
            if (base.getProperty(k) != null) {
                e.setProperty(k, base.getProperty(k));
            }
        }
        e.setProperty(key, value);
        return e;
    }

    @Test
    void 신뢰_프록시를_공인_IP까지_넓히면_기동_중단() {
        for (String tooWide : new String[] {".*", "\\d+\\.\\d+\\.\\d+\\.\\d+", "203\\..*", "(", "2001:.*",
                "0.0.0.0/0", "172.18.0.0/16, 8.0.0.0/8", "::/0"}) {
            assertThat(ProdStartupGuard.check(okEnv().withProperty("server.tomcat.remoteip.internal-proxies", tooWide), 9, 9))
                    .as(tooWide).anyMatch(s -> s.contains("TRUSTED_PROXIES"));
        }
        // 비워 두면(아무도 안 믿음) · 사설 대역 한 곳 · 특정 주소는 통과
        for (String ok : new String[] {"", "172\\.18\\.0\\.\\d+", "10\\.0\\.0\\.5|127\\.0\\.0\\.1", "172.16.0.0/12",
                "10.0.0.0/8, 192.168.0.0/16"}) {
            assertThat(ProdStartupGuard.check(okEnv().withProperty("server.tomcat.remoteip.internal-proxies", ok), 9, 9))
                    .as(ok).isEmpty();
        }
    }

    @Test
    void jar_가_기대하는_버전은_마이그레이션_파일의_최대_번호이고_DB_와_같다() throws Exception {
        int expected = ProdStartupGuard.expectedVersion();
        assertThat(expected).isGreaterThanOrEqualTo(9);
        assertThat(new ProdStartupGuard(jdbc, okEnv()).dbVersion()).isEqualTo(expected);
    }

    @Test
    void 앱_계정이_소유자_롤의_멤버면_기동_중단() throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword())) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("grant " + OWNER_USER + " to " + APP_USER);
                st.execute("set role " + APP_USER);
            }
            JdbcTemplate asApp = new JdbcTemplate(new SingleConnectionDataSource(c, true));
            assertThatThrownBy(() -> new DatabaseRoleGuard(asApp).run(null))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("소유자 롤");
            c.rollback();
        }
        new DatabaseRoleGuard(jdbc).run(null); // 원래 상태는 통과
    }

    @Test
    void 상태_공지는_허용_항목만_다시_만들어_내보낸다() throws Exception {
        mvc.perform(get("/status.json")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        Files.writeString(STATUS, "{\"status\":\"maintenance\",\"messageKo\":\"" + "가".repeat(600)
                + "\",\"evil\":\"<script>\",\"updatedAt\":\"2026-10-15T22:00+09:00\"}");
        mvc.perform(get("/status.json"))
                .andExpect(jsonPath("$.status").value("maintenance"))
                .andExpect(jsonPath("$.evil").doesNotExist())
                .andExpect(jsonPath("$.messageKo").value("가".repeat(500)))
                .andExpect(header().string("Cache-Control", "no-store"));
        Files.writeString(STATUS, "{\"status\":\"<img src=x>\"}");
        mvc.perform(get("/status.json")).andExpect(jsonPath("$.status").value("ok"));
        Files.writeString(STATUS, "not json at all");
        mvc.perform(get("/status.json")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        Files.writeString(STATUS, "{\"status\":\"outage\",\"messageKo\":\"" + "x".repeat(5000) + "\"}");
        mvc.perform(get("/status.json")).andExpect(jsonPath("$.status").value("ok")); // 4KB 초과 파일은 무시
    }

    @Test
    void HSTS_는_HTTPS_응답에만() throws Exception {
        mvc.perform(get("/login").secure(true))
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"));
        mvc.perform(get("/login")).andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void 헬스체크는_로그인_없이_생존_준비를_따로_상세는_숨김() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(jsonPath("$.components").doesNotExist());
    }
}
