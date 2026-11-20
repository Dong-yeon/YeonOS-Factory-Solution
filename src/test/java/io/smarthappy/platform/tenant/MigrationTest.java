package io.smarthappy.platform.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.smarthappy.support.PostgresTestBase;

/** 빈 DB → Flyway V001 적용 → 앱 계정 구성이 설계대로인지 확인 */
class MigrationTest extends PostgresTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 앱은_앱계정으로_접속한다() {
        assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo(APP_USER);
    }

    @Test
    void V001_테이블이_만들어지고_RLS가_강제된다() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select relname, relrowsecurity, relforcerowsecurity
                  from pg_class
                 where relname in ('sys_comp', 'sys_plant') and relkind = 'r'
                 order by relname
                """);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("relrowsecurity")).isEqualTo(true);
            assertThat(r.get("relforcerowsecurity")).isEqualTo(true);
        });
    }

    @Test
    void 앱계정은_RLS를_우회할_수_없다() {
        Map<String, Object> role = jdbc.queryForMap(
                "select rolsuper, rolbypassrls, rolcreaterole, rolcreatedb from pg_roles where rolname = current_user");
        assertThat(role).containsEntry("rolsuper", false)
                .containsEntry("rolbypassrls", false)
                .containsEntry("rolcreaterole", false)
                .containsEntry("rolcreatedb", false);

        // 앱 계정이 업무 테이블 소유자이면 RLS 를 끌 수 있으므로 소유자가 아니어야 한다
        List<String> owned = jdbc.queryForList(
                "select tablename from pg_tables where schemaname = 'public' and tableowner = current_user",
                String.class);
        assertThat(owned).isEmpty();
    }
}
