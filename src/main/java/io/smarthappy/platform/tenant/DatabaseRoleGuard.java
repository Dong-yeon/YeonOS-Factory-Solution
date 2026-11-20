package io.smarthappy.platform.tenant;

import java.util.List;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 기동 시 앱 DB 계정 검사. 아래 경우 기동을 실패시킨다 (조용히 틀리는 것 방지).
 * - 접속 불가 (비밀번호 오류 등) → 첫 요청이 아니라 기동 시점에 드러나게
 * - 슈퍼유저 / BYPASSRLS / 업무 테이블 소유자 → 회사별 행 격리(RLS)가 무력화됨
 */
@Slf4j
@Component
@org.springframework.context.annotation.Profile("!migrate")
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class DatabaseRoleGuard implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        Map<String, Object> role = jdbc.queryForMap(
                "select current_user as name, rolsuper, rolbypassrls from pg_roles where rolname = current_user");
        String name = (String) role.get("name");

        if (Boolean.TRUE.equals(role.get("rolsuper")) || Boolean.TRUE.equals(role.get("rolbypassrls"))) {
            throw new IllegalStateException("앱 DB 계정 [" + name + "] 이 슈퍼유저 또는 BYPASSRLS 입니다. "
                    + "회사별 데이터 격리가 동작하지 않으므로 기동을 중단합니다. 앱 전용 계정(DB_APP_USER)을 사용하세요.");
        }

        List<String> owned = jdbc.queryForList(
                "select tablename from pg_tables where schemaname = 'public' and tableowner = current_user",
                String.class);
        if (!owned.isEmpty()) {
            throw new IllegalStateException("앱 DB 계정 [" + name + "] 이 테이블 소유자입니다 " + owned
                    + ". 소유자는 RLS 를 끌 수 있으므로 마이그레이션 계정과 앱 계정을 분리하세요.");
        }
        // 소유자 롤의 멤버면 SET ROLE 로 소유자가 되어 RLS 를 끌 수 있다 (01 7.1, 9단계)
        List<String> memberOf = jdbc.queryForList("""
                select r.rolname from pg_roles r
                 where r.rolname <> current_user and pg_has_role(current_user, r.oid, 'MEMBER')
                   and exists (select 1 from pg_tables t where t.schemaname = 'public' and t.tableowner = r.rolname)""",
                String.class);
        if (!memberOf.isEmpty()) {
            throw new IllegalStateException("앱 DB 계정 [" + name + "] 이 테이블 소유자 롤 " + memberOf
                    + " 의 멤버입니다. SET ROLE 로 RLS 를 우회할 수 있으므로 기동을 중단합니다.");
        }
        log.info("DB role check OK: app user [{}] (no superuser, no bypassrls, owns no tables, not owner member)", name);
    }
}
