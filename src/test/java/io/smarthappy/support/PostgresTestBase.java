package io.smarthappy.support;

import java.nio.file.Path;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 통합 테스트 공통 기반.
 * 로컬 개발 DB(docker compose)와 같은 이미지·같은 init 스크립트로 일회용 PostgreSQL 을 띄운다.
 * - Flyway: 소유자 계정 / 애플리케이션 DataSource: 앱 계정 (RLS 가 실제로 적용되는 구성)
 * 컨테이너는 JVM 당 1개를 재사용한다.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class PostgresTestBase {

    protected static final String OWNER_USER = "yeonos_owner";
    protected static final String APP_USER = "yeonos_app";
    private static final String TEST_PASSWORD = "test-only-pw";

    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("yeonos")
                    .withUsername(OWNER_USER)
                    .withPassword(TEST_PASSWORD)
                    // 시험 컨텍스트마다 연결 풀(최대 10)이 생긴다 — @MockitoBean 등으로 컨텍스트가 늘면 기본 100 이 모자라
                    // "remaining connection slots are reserved" 로 엉뚱한 시험이 실패했다(2026-10-22)
                    .withCommand("postgres", "-c", "max_connections=300")
                    .withEnv("APP_DB_USER", APP_USER)
                    .withEnv("APP_DB_PASSWORD", TEST_PASSWORD)
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(Path.of("docker/postgres/init/01-create-app-role.sh"), 0755),
                            "/docker-entrypoint-initdb.d/01-create-app-role.sh");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> TEST_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> OWNER_USER);
        registry.add("spring.flyway.password", () -> TEST_PASSWORD);
        registry.add("spring.flyway.placeholders.app_role", () -> APP_USER);
    }

    protected static String jdbcUrl() {
        return POSTGRES.getJdbcUrl();
    }

    protected static String testPassword() {
        return TEST_PASSWORD;
    }
}
