package io.smarthappy.platform.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import io.smarthappy.support.PostgresTestBase;

/**
 * 회사별 행 격리(RLS) — 정상 흐름 + 이상 사용.
 * 앱 계정 DataSource 로 실행하므로 운영과 같은 조건이다.
 */
class TenantIsolationTest extends PostgresTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    String compA;
    String compB;

    @BeforeEach
    void setUp() {
        int n = SEQ.incrementAndGet();
        compA = "TA" + n;
        compB = "TB" + n;
        createComp(compA, "에이회사");
        createComp(compB, "비회사");
        inTenant(compA, () -> insertPlant(compA, "P1", "A사 1공장"));
        inTenant(compB, () -> insertPlant(compB, "P1", "B사 1공장"));
    }

    // ---------- 정상 흐름 ----------

    @Test
    void 자기_회사_데이터만_보인다() {
        Integer aSees = inTenant(compA, () -> countPlants());
        String aName = inTenant(compA, () -> jdbc.queryForObject("select plant_nm from sys_plant", String.class));
        assertThat(aSees).isEqualTo(1);
        assertThat(aName).isEqualTo("A사 1공장");

        Integer compRows = inTenant(compA, () -> jdbc.queryForObject("select count(*) from sys_comp", Integer.class));
        assertThat(compRows).isEqualTo(1);
    }

    // ---------- 이상 사용 ----------

    @Test
    void 회사_미설정이면_아무것도_안보인다() {
        Integer rows = tx.execute(s -> countPlants());
        assertThat(rows).isZero();
    }

    @Test
    void 빈문자열_공백_회사코드로는_아무것도_안보인다() {
        assertThat(inTenant("", () -> countPlants())).isZero();
        assertThat(inTenant(" ", () -> countPlants())).isZero();
        assertThat(inTenant(compA + " ", () -> countPlants())).isZero();
    }

    @Test
    void 회사코드_대소문자가_다르면_다른_회사다() {
        assertThat(inTenant(compA.toLowerCase(), () -> countPlants())).isZero();
    }

    @Test
    void 다른_회사_코드로_insert하면_거부된다() {
        assertThatThrownBy(() -> inTenant(compA, () -> insertPlant(compB, "P9", "몰래 넣기")))
                .isInstanceOf(DataAccessException.class)
                // Spring 은 RLS 위반(SQLState 42501)을 BadSqlGrammarException 으로 분류한다 → 2단계 오류처리에서 403 으로 매핑
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void 다른_회사_행은_update_delete_되지_않는다() {
        Integer updated = inTenant(compA, () ->
                jdbc.update("update sys_plant set plant_nm = '덮어쓰기' where comp_cd = ?", compB));
        Integer deleted = inTenant(compA, () ->
                jdbc.update("delete from sys_plant where comp_cd = ?", compB));
        assertThat(updated).isZero();
        assertThat(deleted).isZero();
        assertThat(inTenant(compB, () -> jdbc.queryForObject("select plant_nm from sys_plant", String.class)))
                .isEqualTo("B사 1공장");
    }

    @Test
    void 자기_행의_회사코드를_다른_회사로_바꿀_수_없다() {
        assertThatThrownBy(() -> inTenant(compA, () ->
                jdbc.update("update sys_plant set comp_cd = ? where plant_cd = 'P1'", compB)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void 트랜잭션이_끝나면_회사설정이_남지_않는다_커넥션풀_재사용() throws SQLException {
        // 같은 물리 커넥션에서: 트랜잭션 로컬 설정 → 커밋 → 다음 트랜잭션은 설정 없음
        try (Connection c = DriverManager.getConnection(jdbcUrl(), APP_USER, testPassword())) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("select set_config('app.comp_cd', '" + compA + "', true)");
                assertThat(count(st)).isEqualTo(1);
                c.commit();
                // 설정 후 끝난 세션에서는 NULL 이 아니라 '' 가 된다 → hb_current_comp() 가 NULL 로 바꿔야 함
                assertThat(count(st)).isZero();
                c.commit();
            }
        }
    }

    @Test
    void 앱계정은_RLS를_끄거나_테이블을_지울_수_없다() {
        assertThatThrownBy(() -> jdbc.execute("alter table sys_plant disable row level security"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("drop policy tenant_isolation on sys_plant"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("drop table sys_plant"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("create table hack_tmp (id int)"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void 회사코드_형식이_틀리면_거부된다() {
        for (String bad : new String[] {"a1", "한글회사", " AB", "AB ", "A", "AB'; drop table sys_comp;--",
                "ABCDEFGHIJKLMNOPQRSTU"}) {
            assertThatThrownBy(() -> createComp(bad, "형식오류"))
                    .as("comp_cd=[%s]", bad)
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void 회사명이_공백만이면_거부된다() {
        assertThatThrownBy(() -> createComp("BLANK" + SEQ.incrementAndGet(), "   "))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void 이모지_특수문자_이름은_그대로_저장된다() {
        String name = "😀 <script>alert('x')</script> 100% _test_ \"q\"";
        inTenant(compA, () -> insertPlant(compA, "P2", name));
        String saved = inTenant(compA, () ->
                jdbc.queryForObject("select plant_nm from sys_plant where plant_cd = 'P2'", String.class));
        assertThat(saved).isEqualTo(name);
    }

    // ---------- helpers ----------

    private void createComp(String compCd, String compNm) {
        inTenant(compCd, () -> jdbc.update(
                "insert into sys_comp (comp_cd, comp_nm, created_by, updated_by) values (?, ?, 'test', 'test')",
                compCd, compNm));
    }

    private int insertPlant(String compCd, String plantCd, String plantNm) {
        return jdbc.update(
                "insert into sys_plant (comp_cd, plant_cd, plant_nm, created_by, updated_by) values (?, ?, ?, 'test', 'test')",
                compCd, plantCd, plantNm);
    }

    private Integer countPlants() {
        return jdbc.queryForObject("select count(*) from sys_plant", Integer.class);
    }

    /** 앱에서 TenantContext 가 할 일(2단계에서 AOP 로 구현)을 테스트에서 직접 수행 */
    private <T> T inTenant(String compCd, java.util.function.Supplier<T> work) {
        return tx.execute(status -> {
            jdbc.queryForObject("select set_config('app.comp_cd', ?, true)", String.class, compCd);
            return work.get();
        });
    }

    private static int count(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery("select count(*) from sys_plant")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
