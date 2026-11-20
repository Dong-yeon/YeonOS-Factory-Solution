package io.smarthappy.module.apqp;

import static io.smarthappy.module.apqp.jooq.Tables.MST_ITEM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * jOOQ 기반 (docs/43, 결정 #52) — DSLContext 가 같은 DataSource·Spring 트랜잭션을 타서
 * TenantAwareTransactionManager 의 app.comp_cd(RLS)가 그대로 걸리는지, 오류가 MyBatis 와 같은 Spring 예외·PgErrors 로 오는지.
 * 회사별 표는 아직 apqp_ 가 없어 기준정보 mst_item 으로 본다.
 */
class JooqTenantTest extends PostgresTestBase {

    @Autowired
    DSLContext dsl;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TestAccounts accounts;

    String compA;
    String compB;

    @BeforeEach
    void setUp() {
        compA = accounts.newComp("JQA");
        compB = accounts.newComp("JQB");
        accounts.exec(compA, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'ZT-JQ1', '품목1', 'FG', 'MAKE', 'EA', 't', 't'), (?, 'ZT-JQ2', '품목2', 'FG', 'MAKE', 'EA', 't', 't')""",
                compA, compA);
    }

    private List<String> codes() {
        return dsl.select(MST_ITEM.ITEM_CD).from(MST_ITEM).where(MST_ITEM.ITEM_CD.like("ZT-JQ%")).orderBy(MST_ITEM.ITEM_CD)
                .fetch(MST_ITEM.ITEM_CD);
    }

    @Test
    void 트랜잭션_밖은_0건_회사_범위_안은_그_회사_행만() {
        assertThat(codes()).isEmpty();                                    // 회사 범위 없음 → RLS 로 0건(MyBatis 와 같음)
        assertThat(runner.call(compA, this::codes)).containsExactly("ZT-JQ1", "ZT-JQ2");
        assertThat(runner.call(compB, this::codes)).isEmpty();            // A사로 B사: B사 범위에서는 A사 행이 안 보인다
        // B사 범위에서 A사 행을 고치거나 지우려 해도 0건
        assertThat(runner.call(compB, () -> dsl.update(MST_ITEM).set(MST_ITEM.ITEM_NM, "x").where(MST_ITEM.COMP_CD.eq(compA))
                .execute())).isZero();
        // 지우기는 앱 계정에 권한 자체가 없다(기준정보는 사용 중지) — jOOQ 도 같은 계정이라 42501
        Throwable del = catchThrowable(() -> runner.run(compA, () -> dsl.deleteFrom(MST_ITEM).where(MST_ITEM.ITEM_CD.eq("ZT-JQ1")).execute()));
        assertThat(del).isInstanceOf(DataAccessException.class);
        assertThat(PgErrors.sqlState(del)).contains("42501");
        assertThat(PgErrors.isRlsViolation(del)).isFalse();
        assertThat(runner.call(compA, () -> dsl.fetchValue(MST_ITEM.ITEM_NM, MST_ITEM.ITEM_CD.eq("ZT-JQ1")))).isEqualTo("품목1");
    }

    @Test
    void MyBatis_JdbcTemplate_와_한_트랜잭션_같은_연결_같이_롤백() {
        Throwable t = catchThrowable(() -> runner.run(compA, () -> {
            jdbc.update("""
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                    values (?, 'ZT-JQ3', '품목3', 'FG', 'MAKE', 'EA', 't', 't')""", compA);
            assertThat(codes()).contains("ZT-JQ3");                       // JdbcTemplate 이 쓴 것을 jOOQ 가 같은 트랜잭션에서 본다
            dsl.insertInto(MST_ITEM, MST_ITEM.COMP_CD, MST_ITEM.ITEM_CD, MST_ITEM.ITEM_NM, MST_ITEM.ITEM_TYPE, MST_ITEM.PROC_TYPE,
                    MST_ITEM.UNIT_CD, MST_ITEM.CREATED_BY, MST_ITEM.UPDATED_BY)
                    .values(compA, "ZT-JQ4", "품목4", "FG", "MAKE", "EA", "t", "t").execute();
            assertThat(jdbc.queryForObject("select count(*) from mst_item where item_cd = 'ZT-JQ4'", Integer.class)).isEqualTo(1);
            throw new IllegalStateException("롤백 시험");
        }));
        assertThat(t).hasMessage("롤백 시험");
        assertThat(runner.call(compA, this::codes)).containsExactly("ZT-JQ1", "ZT-JQ2");   // 둘 다 롤백
    }

    @Test
    void 다른_회사_comp_cd_로_쓰면_RLS_위반이_Spring_예외와_PgErrors_로_온다() {
        Throwable t = catchThrowable(() -> runner.run(compA, () -> dsl.insertInto(MST_ITEM, MST_ITEM.COMP_CD, MST_ITEM.ITEM_CD,
                MST_ITEM.ITEM_NM, MST_ITEM.ITEM_TYPE, MST_ITEM.PROC_TYPE, MST_ITEM.UNIT_CD, MST_ITEM.CREATED_BY, MST_ITEM.UPDATED_BY)
                .values(compB, "ZT-JQ9", "남의 품목", "FG", "MAKE", "EA", "t", "t").execute()));
        assertThat(t).isInstanceOf(DataAccessException.class);               // ApiExceptionHandler 가 받는 형태
        assertThat(PgErrors.isRlsViolation(t)).isTrue();                     // → 403 TENANT_FORBIDDEN
        assertThat(runner.call(compB, this::codes)).isEmpty();
    }

    @Test
    void 제약_위반은_DataIntegrityViolationException_과_제약_이름() {
        Throwable dup = catchThrowable(() -> runner.run(compA, () -> dsl.insertInto(MST_ITEM, MST_ITEM.COMP_CD, MST_ITEM.ITEM_CD,
                MST_ITEM.ITEM_NM, MST_ITEM.ITEM_TYPE, MST_ITEM.PROC_TYPE, MST_ITEM.UNIT_CD, MST_ITEM.CREATED_BY, MST_ITEM.UPDATED_BY)
                .values(compA, "ZT-JQ1", "같은 코드", "FG", "MAKE", "EA", "t", "t").execute()));
        assertThat(dup).isInstanceOf(DataIntegrityViolationException.class);   // 서비스의 칸 오류 변환(CONSTRAINT_FIELD)이 그대로
        assertThat(PgErrors.sqlState(dup)).contains(PgErrors.UNIQUE_VIOLATION);
        assertThat(PgErrors.constraint(dup)).contains("uq_mst_item_cd");
        Throwable bad = catchThrowable(() -> runner.run(compA, () -> dsl.update(MST_ITEM).set(MST_ITEM.ITEM_NM, "  ")
                .where(MST_ITEM.ITEM_CD.eq("ZT-JQ1")).execute()));
        assertThat(bad).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(PgErrors.constraint(bad)).contains("ck_mst_item_nm");
    }
}
