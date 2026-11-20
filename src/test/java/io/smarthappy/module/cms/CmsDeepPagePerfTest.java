package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;

import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 깊은 offset 실측 (docs/qa/2026-10-24_offset-deep-page.md H2·H4·M1, docs/33 13.13) — 평소 건너뜀.
 * {@code ./gradlew test --tests '*CmsDeepPagePerfTest*' -Dhb.perf=true}, 행 수는 환경 변수 HB_PERF_ROWS(기본 10만, 100만 = 1000000).
 * 행을 SQL 로 한 번에 만들고(트리거·감사 그대로), grid 서비스의 첫 쪽·가운데 쪽·끝 쪽(50행)을 세 번씩 재서 가장 짧은 값을 적는다.
 */
@EnabledIfSystemProperty(named = "hb.perf", matches = "true")
class CmsDeepPagePerfTest extends PostgresTestBase {

    private static final int ROWS = Integer.parseInt(System.getenv().getOrDefault("HB_PERF_ROWS", "100000"));   // 시험 JVM 에 넘어가는 환경 변수
    private static final int SIZE = 50;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    AlarmService alarms;

    @Autowired
    ChkRunService runs;

    @Autowired
    CounterService counters;

    @Autowired
    PmPlanService plans;

    /** 통계 갱신은 표 소유자만 — 소유자 연결로(실측 계획이 실제와 같게) */
    private static void analyze(String... tables) {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword());
                java.sql.Statement st = c.createStatement()) {
            for (String t : tables) {
                st.execute("analyze " + t);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** generate_series(?, ?) 문장을 2만 행씩 나눠 — 한 문장이 문장 시간 제한(앱 계정)을 넘지 않게. 앞 인자들 + (시작, 끝) */
    private void chunked(String comp, int rows, String sql, Object... head) {
        for (int from = 1; from <= rows; from += 20_000) {
            Object[] args = java.util.Arrays.copyOf(head, head.length + 2);
            args[head.length] = from;
            args[head.length + 1] = Math.min(rows, from + 19_999);
            accounts.exec(comp, sql, args);
        }
    }

    private String setUpComp(String prefix) {
        String comp = accounts.newComp(prefix);
        accounts.addPlant(comp, "P1", "1공장");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        accounts.exec(comp, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                select ?, 'P1', 'E' || lpad(g::text, 3, '0'), '설비 ' || g, ?, 't', 't' from generate_series(1, 50) g""", comp, cls);
        return comp;
    }

    /** 첫·가운데·끝 쪽, 세 번 중 가장 짧은 ms */
    private List<String> measure(String comp, long total, IntFunction<Integer> call) {
        int last = (int) Math.max(1, (total + SIZE - 1) / SIZE);
        List<String> out = new ArrayList<>();
        for (int page : new int[] {1, Math.max(1, last / 2), last}) {
            long best = Long.MAX_VALUE;
            boolean timedOut = false;
            for (int i = 0; i < 3 && !timedOut; i++) {
                long t0 = System.nanoTime();
                try {
                    int n = runner.call(comp, () -> call.apply(page));
                    assertThat(n).isPositive();
                    best = Math.min(best, (System.nanoTime() - t0) / 1_000_000);
                } catch (org.springframework.dao.QueryTimeoutException e) {
                    timedOut = true;   // 문장 시간 제한 — 이것도 결과
                }
            }
            out.add("page " + page + " = " + (timedOut ? "시간 초과" : best + " ms"));
        }
        return out;
    }

    private static void report(String what, long seedMs, long total, List<String> m) {
        System.out.println("[perf] " + what + " rows=" + total + " seed=" + seedMs / 1000 + "s " + String.join(", ", m));
    }

    @Test
    void 알람이력_grid_깊은쪽() {
        String comp = setUpComp("DA");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        long t0 = System.currentTimeMillis();
        // 설비 50 × 코드 5, 89일에 고르게(반복 창 24시간 안에 같은 설비·코드가 여러 번 — 실제 반복 알람처럼)
        chunked(comp, ROWS, """
                insert into cms_alarm_log (comp_cd, equip_id, alarm_cd, raised_at, recorded_by)
                select ?, e.equip_id, 'A' || (g % 5), now() - make_interval(secs => (g::double precision * 89 * 86400 / ?)), 'perf'
                  from generate_series(?, ?) g
                  join mst_equip e on e.equip_cd = 'E' || lpad(((g / 5) % 50 + 1)::text, 3, '0')""", comp, ROWS);
        analyze("cms_alarm_log");
        long seed = System.currentTimeMillis() - t0;
        long total = ROWS;   // 전체 건수(count)는 따로 — 여기서는 쪽 시간만(첫 쪽 total=true 가 시간 초과면 쪽 계산을 못 한다)
        report("H2 알람 이력", seed, total, measure(comp, total, p -> alarms.alarmGrid("P1", today.minusDays(91).toString(), today.toString(), null, false, p, SIZE,
                null, false).list().size()));
    }

    @Test
    void 점검실행_grid_깊은쪽() {
        String comp = setUpComp("DR");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        long t0 = System.currentTimeMillis();
        int planCount = (ROWS + 299) / 300;
        long tmpl = accounts.query(comp, "insert into cms_chk_tmpl (comp_cd, tmpl_cd, tmpl_nm, created_by, updated_by) values (?, 'T', 't', 't', 't') returning tmpl_id",
                Long.class, comp);
        // 계수기 50(설비마다) + 판독 ROWS/5(계수기 total 부질의가 읽는 양)
        accounts.exec(comp, """
                insert into cms_counter (comp_cd, counter_cd, counter_nm, target_type, equip_id, counter_kind, created_by, updated_by)
                select ?, 'C-' || e.equip_cd, 'c', 'EQUIP', e.equip_id, 'SHOTS', 't', 't' from mst_equip e""", comp);
        chunked(comp, ROWS / 5, """
                insert into cms_counter_reading (comp_cd, counter_id, read_at, delta_qty, recorded_by)
                select ?, c.counter_id, now() - interval '400 days' + make_interval(secs => g), 1, 'perf'
                  from generate_series(?, ?) g
                  join cms_counter c on c.counter_cd = 'C-E' || lpad((g % 50 + 1)::text, 3, '0')""", comp);
        accounts.exec(comp, """
                insert into cms_pm_plan (comp_cd, plan_cd, plan_nm, target_type, equip_id, tmpl_id, cycle_type, counter_id, usage_interval,
                                         anchor_usage, next_due_usage, created_by, updated_by)
                select ?, 'PL' || g, 'p', 'EQUIP', c.equip_id, ?, 'USAGE', c.counter_id, 1000, 1000, 1000, 't', 't'
                  from generate_series(1, ?) g
                  join cms_counter c on c.counter_cd = 'C-E' || lpad((g % 50 + 1)::text, 3, '0')""", comp, tmpl, planCount);
        chunked(comp, ROWS, """
                insert into cms_chk_run (comp_cd, plan_id, target_type, equip_id, tmpl_id, due_date, due_usage, created_by, updated_by)
                select ?, p.plan_id, 'EQUIP', p.equip_id, p.tmpl_id, ?::date - (g % 300), 1000 * (g % 300 + 1), 't', 't'
                  from generate_series(?, ?) g
                  join cms_pm_plan p on p.plan_cd = 'PL' || ((g - 1) / 300 + 1)""", comp, today.toString());
        analyze("cms_chk_run", "cms_counter_reading");
        long seed = System.currentTimeMillis() - t0;
        String from = today.minusDays(365).toString();
        long total = ROWS;
        report("H4 점검 실행", seed, total, measure(comp, total, p -> runs.grid(from, today.toString(), null, null, null, null, p, SIZE, null, false)
                .list().size()));
        // M6 예방점검 계획 grid — 계획마다 계수기 판독 전체 합(같은 자료)
        long plansTotal = runner.call(comp, () -> plans.grid(null, null, null, 1, SIZE, null, true).total());
        report("M6 예방점검 계획", seed, plansTotal, measure(comp, plansTotal, p -> plans.grid(null, null, null, p, SIZE, null, false).list().size()));
    }

    @Test
    void 카운터_grid_판독많을때() {
        String comp = setUpComp("DC");
        long t0 = System.currentTimeMillis();
        // 계수기 200(설비 50 × 4) + 판독 ROWS
        accounts.exec(comp, """
                insert into cms_counter (comp_cd, counter_cd, counter_nm, target_type, equip_id, counter_kind, created_by, updated_by)
                select ?, 'C-' || e.equip_cd || '-' || k, 'c', 'EQUIP', e.equip_id, 'SHOTS', 't', 't'
                  from mst_equip e cross join generate_series(1, 4) k""", comp);
        chunked(comp, ROWS, """
                insert into cms_counter_reading (comp_cd, counter_id, read_at, delta_qty, recorded_by)
                select ?, c.counter_id, now() - interval '400 days' + make_interval(secs => g), 1, 'perf'
                  from generate_series(?, ?) g
                  join cms_counter c on c.counter_cd = 'C-E' || lpad((g % 50 + 1)::text, 3, '0') || '-' || (g % 4 + 1)""", comp);
        analyze("cms_counter_reading");
        long seed = System.currentTimeMillis() - t0;
        long total = runner.call(comp, () -> counters.grid(null, null, null, 1, SIZE, null, true).total());
        report("M1 카운터", seed, total, measure(comp, total, p -> counters.grid(null, null, null, p, SIZE, null, false).list().size()));
    }
}
