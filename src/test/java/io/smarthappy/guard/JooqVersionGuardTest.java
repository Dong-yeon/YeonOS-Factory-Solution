package io.smarthappy.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DatabaseMetaData;

import javax.sql.DataSource;

import org.jooq.Constants;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.smarthappy.support.PostgresTestBase;

/**
 * 가드: jOOQ 판이 우리 PostgreSQL 을 OSS 로 지원한다 (docs/43 J1·2장, 결정 #52 — 3.19.x 고정).
 * Boot 를 올리면서 jOOQ 기본판(3.20+ = PG 17/18 이상)이 조용히 따라 올라오면 여기서 실패한다.
 * PostgreSQL 을 18 로 올릴 때 jOOQ 도 3.21+ 로 함께 올리고 이 시험의 기대값을 고친다
 */
class JooqVersionGuardTest extends PostgresTestBase {

    @Autowired
    DataSource dataSource;

    @Test
    void jOOQ_는_3_19_줄이고_지금_DB_버전을_OSS_로_지원한다() throws Exception {
        assertThat(Constants.VERSION).as("jOOQ 판(build.gradle ext['jooq.version'])").startsWith("3.19.");
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            assertThat(SQLDialect.POSTGRES.supportsDatabaseVersion(md.getDatabaseMajorVersion(), md.getDatabaseMinorVersion(),
                    md.getDatabaseProductVersion()))
                    .as("jOOQ " + Constants.VERSION + " OSS 가 PostgreSQL " + md.getDatabaseProductVersion() + " 을 지원하지 않는다 — docs/43 2장")
                    .isTrue();
        }
    }
}
