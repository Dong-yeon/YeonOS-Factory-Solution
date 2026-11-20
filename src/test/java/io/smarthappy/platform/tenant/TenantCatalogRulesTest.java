package io.smarthappy.platform.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import io.smarthappy.support.PostgresTestBase;

/** RLS 우회 경로 규칙 (01 설계 1.4) */
class TenantCatalogRulesTest extends PostgresTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 현재_스키마는_규칙을_모두_지킨다() {
        assertThat(new TenantCatalogInspector(jdbc).inspect()).isEmpty();
    }

    /** 규칙이 실제로 위반을 잡는지: 소유자 계정으로 나쁜 객체를 만들고 검사한 뒤 롤백 */
    @Test
    void 우회_경로를_만들면_모두_잡아낸다() throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword())) {
            c.setAutoCommit(false);
            JdbcTemplate owner = new JdbcTemplate(new SingleConnectionDataSource(c, true));
            try {
                owner.execute("create view bad_view as select comp_cd, plant_nm from sys_plant");
                owner.execute("create view good_view with (security_invoker = true) as select comp_cd from sys_plant");
                owner.execute("create materialized view bad_mview as select comp_cd from sys_plant");
                owner.execute("""
                        create function bad_definer() returns bigint language sql security definer
                        as $$ select count(*) from sys_plant $$""");
                owner.execute("create table bad_no_rls (comp_cd varchar(20), v int)");
                owner.execute("create table bad_weak_policy (comp_cd varchar(20), v int)");
                owner.execute("alter table bad_weak_policy enable row level security");
                owner.execute("alter table bad_weak_policy force row level security");
                owner.execute("create policy tenant_isolation on bad_weak_policy using (true)");
                owner.execute("create policy open_read on sys_plant for select using (true)");
                owner.execute("create table bad_global (code varchar(10))");
                // 원장 규약: reversal_of_id 가 있으면 수정·삭제 차단 트리거 + 앱 UPDATE/DELETE 없음
                owner.execute("create table bad_ledger (comp_cd varchar(20), id int, reversal_of_id int)");
                owner.execute("alter table bad_ledger enable row level security");
                owner.execute("alter table bad_ledger force row level security");
                owner.execute("create policy tenant_isolation on bad_ledger using (comp_cd = hb_current_comp()) with check (comp_cd = hb_current_comp())");
                owner.execute("create table good_ledger (comp_cd varchar(20), id int, reversal_of_id int, "
                        + "occurred_at timestamptz, recorded_at timestamptz, entry_method varchar(10))");
                owner.execute("alter table good_ledger enable row level security");
                owner.execute("alter table good_ledger force row level security");
                owner.execute("create policy tenant_isolation on good_ledger using (comp_cd = hb_current_comp()) with check (comp_cd = hb_current_comp())");
                owner.execute("create trigger tg_good_ledger before update or delete on good_ledger for each row execute function hb_forbid_update_delete()");
                owner.execute("revoke update, delete on good_ledger from yeonos_app");
                owner.execute("grant insert on sys_prog to yeonos_app");

                List<String> violations = new TenantCatalogInspector(owner, APP_USER).inspect();

                assertThat(violations)
                        .anyMatch(v -> v.contains("[bad_view]"))
                        .noneMatch(v -> v.contains("[good_view]"))
                        .anyMatch(v -> v.contains("[bad_mview]"))
                        .anyMatch(v -> v.contains("[bad_definer]"))
                        .anyMatch(v -> v.contains("[bad_no_rls]") && v.contains("FORCE"))
                        .anyMatch(v -> v.contains("[bad_no_rls]") && v.contains("tenant_isolation"))
                        .anyMatch(v -> v.contains("[bad_weak_policy]") && v.contains("tenant_isolation"))
                        .anyMatch(v -> v.contains("[sys_plant.open_read]"))
                        .anyMatch(v -> v.contains("[bad_global]") && v.contains("comp_cd"))
                        .anyMatch(v -> v.contains("[sys_prog]") && v.contains("쓰기 권한"))
                        .anyMatch(v -> v.contains("[bad_ledger]") && v.contains("hb_forbid_update_delete"))
                        .anyMatch(v -> v.contains("[bad_ledger]") && v.contains("UPDATE/DELETE"))
                        .anyMatch(v -> v.contains("[bad_ledger]") && v.contains("entry_method"))
                        .noneMatch(v -> v.contains("[good_ledger]"));
            } finally {
                c.rollback();
            }
        }
    }
}
