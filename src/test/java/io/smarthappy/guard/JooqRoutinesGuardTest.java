package io.smarthappy.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.smarthappy.module.apqp.jooq.Routines;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import io.smarthappy.support.jooq.JooqCodegen;

/**
 * 가드: jOOQ 로 부르는 DB 함수는 허용 목록(JooqCodegen.ROUTINES)의 **판정용 순수 함수**만 (docs/43 3장).
 * - 생성된 routines 가 허용 목록과 같다(목록 밖 함수가 생성물에 들어오면 실패)
 * - 허용 목록의 함수는 STABLE·IMMUTABLE(PostgreSQL 이 쓰기를 막는다), SECURITY DEFINER 아님, 트리거 함수 아님,
 *   회사 범위·소속 검사 함수(hb_current_*·hb_check_*) 아님
 */
class JooqRoutinesGuardTest extends PostgresTestBase {

    private static final Path ROUTINES_DIR = Path.of(JooqCodegen.TARGET_DIR, JooqCodegen.PACKAGE.replace('.', '/'), "routines");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DSLContext dsl;

    @Autowired
    TenantRunner runner;

    @Autowired
    TestAccounts accounts;

    /** hb_code_usable → HbCodeUsable */
    private static String className(String fn) {
        StringBuilder b = new StringBuilder();
        for (String part : fn.split("_")) {
            b.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return b.toString();
    }

    @Test
    void 생성된_routines_는_허용_목록과_같다() throws IOException {
        List<String> generated;
        try (Stream<Path> files = Files.list(ROUTINES_DIR)) {
            generated = files.map(f -> f.getFileName().toString().replace(".java", "")).sorted().toList();
        }
        assertThat(generated).as("JooqCodegen.ROUTINES 밖 함수가 생성물에 있다 — 허용 목록·INCLUDES 확인")
                .containsExactlyInAnyOrderElementsOf(JooqCodegen.ROUTINES.stream().map(JooqRoutinesGuardTest::className).toList());
    }

    @Test
    void 허용_목록의_함수는_판정용_순수_함수다() {
        for (String fn : JooqCodegen.ROUTINES) {
            assertThat(fn).as(fn).doesNotStartWith("hb_current").doesNotStartWith("hb_check");
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select p.provolatile::text as vol, p.prosecdef as secdef, t.typname as ret
                      from pg_proc p join pg_namespace n on n.oid = p.pronamespace join pg_type t on t.oid = p.prorettype
                     where n.nspname = 'public' and p.proname = ?""", fn);
            assertThat(rows).as(fn + " 이 DB 에 없다").isNotEmpty();
            for (Map<String, Object> r : rows) {
                assertThat((String) r.get("vol")).as(fn + " — STABLE·IMMUTABLE 만(VOLATILE 은 쓰기 가능)").isIn("s", "i");
                assertThat(r.get("secdef")).as(fn + " — SECURITY DEFINER 금지(RLS 우회)").isEqualTo(false);
                assertThat((String) r.get("ret")).as(fn + " — 트리거 함수 금지").isNotIn("trigger", "event_trigger");
            }
        }
    }

    @Test
    void 생성된_함수를_SQL_안에서_부르면_회사_범위가_그대로() {
        String comp = accounts.newComp("JQR");
        // 전역 코드(UNIT·EA)는 어느 회사에서나 사용 가능, 없는 코드는 아님
        assertThat(runner.call(comp, () -> dsl.select(Routines.hbCodeUsable(org.jooq.impl.DSL.val(comp), org.jooq.impl.DSL.val("UNIT"),
                org.jooq.impl.DSL.val("EA"))).fetchOne(0, Boolean.class))).isTrue();
        assertThat(runner.call(comp, () -> dsl.select(Routines.hbCodeUsable(org.jooq.impl.DSL.val(comp), org.jooq.impl.DSL.val("UNIT"),
                org.jooq.impl.DSL.val("ZT-NONE"))).fetchOne(0, Boolean.class))).isFalse();
    }
}
