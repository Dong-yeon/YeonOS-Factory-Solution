package io.smarthappy.platform.tenant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import io.smarthappy.support.PostgresTestBase;

/** 이상 설정: 앱 계정 자리에 소유자/잘못된 비밀번호를 넣으면 기동 단계에서 막혀야 한다 */
class DatabaseRoleGuardTest extends PostgresTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 앱계정이면_통과() {
        assertThatCode(() -> new DatabaseRoleGuard(jdbc).run(null)).doesNotThrowAnyException();
    }

    @Test
    void 소유자_슈퍼유저_계정을_앱계정으로_쓰면_기동_중단() {
        JdbcTemplate ownerJdbc = new JdbcTemplate(
                new DriverManagerDataSource(jdbcUrl(), OWNER_USER, testPassword()));
        assertThatThrownBy(() -> new DatabaseRoleGuard(ownerJdbc).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("격리");
    }

    @Test
    void 비밀번호가_틀리면_기동_중단() {
        JdbcTemplate badJdbc = new JdbcTemplate(
                new DriverManagerDataSource(jdbcUrl(), APP_USER, "wrong-password"));
        assertThatThrownBy(() -> new DatabaseRoleGuard(badJdbc).run(null))
                .hasRootCauseMessage("FATAL: password authentication failed for user \"" + APP_USER + "\"");
    }
}
