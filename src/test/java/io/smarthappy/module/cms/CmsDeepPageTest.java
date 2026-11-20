package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.smarthappy.module.cms.CmsAlarmDtos.AlarmRow;
import io.smarthappy.module.cms.CmsCounterDtos.CounterRow;
import io.smarthappy.module.cms.CmsPmDtos.PlanRow;
import io.smarthappy.module.cms.CmsPmDtos.RunRow;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * C26 지연 조인 (docs/33 13.13) — 알람 이력·점검 실행·카운터·예방점검 계획 grid 가 안쪽에서 쪽(id)만 고르고 바깥에서 계산해도
 * 2행씩 이어 붙인 쪽 = 한 쪽 전체(같은 순서, 겹침·빠짐 없음 — 같은 정렬 값이 많아도 PK 로 경계 고정), 계산 칸(반복 창 수·판독 합)은 그대로,
 * 다른 정렬·조건에서도 같은 규칙, 건수(count)도 같다.
 */
class CmsDeepPageTest extends PostgresTestBase {

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

    String comp;
    LocalDate today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("DP");
        accounts.addPlant(comp, "P1", "1공장");
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        accounts.exec(comp, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                select ?, 'P1', 'E' || g, '설비 ' || g, ?, 't', 't' from generate_series(1, 3) g""", comp, cls);
    }

    /** 2행씩 쪽을 끝까지 이어 붙인 것 */
    private static <T> List<T> stitched(int total, IntFunction<List<T>> page) {
        List<T> out = new ArrayList<>();
        for (int p = 1; p <= (total + 1) / 2; p++) {
            out.addAll(page.apply(p));
        }
        return out;
    }

    @Test
    void 알람이력_쪽을_이어붙이면_한쪽과_같고_반복창수도_같다() {
        // 같은 시각 3건(정렬 값 겹침) + 다른 시각, 반복 창 안 같은 설비·코드
        String at = today.minusDays(1) + " 09:00:00+09";
        accounts.exec(comp, """
                insert into cms_alarm_log (comp_cd, equip_id, alarm_cd, raised_at, recorded_by)
                select ?, e.equip_id, 'A1', ?::timestamptz + make_interval(mins => (g % 4) * 10), 'perf'
                  from generate_series(1, 9) g join mst_equip e on e.equip_cd = 'E' || (g % 3 + 1)""", comp, at);
        String from = today.minusDays(2).toString();
        String to = today.toString();
        for (List<String> sort : List.of(List.<String>of(), List.of("equipCd,asc"), List.of("raisedAt,asc"))) {
            List<AlarmRow> all = runner.call(comp, () -> alarms.alarmGrid("P1", from, to, null, false, 1, 50, sort, true).list());
            long total = runner.call(comp, () -> alarms.alarmGrid("P1", from, to, null, false, 1, 2, sort, true).total());
            assertThat(total).isEqualTo(9);
            List<AlarmRow> pages = stitched(9, p -> runner.call(comp, () -> alarms.alarmGrid("P1", from, to, null, false, p, 2, sort, false).list()));
            assertThat(pages).extracting(AlarmRow::getAlarmId).containsExactlyElementsOf(all.stream().map(AlarmRow::getAlarmId).toList());
            assertThat(pages).extracting(AlarmRow::getWindowCount).containsExactlyElementsOf(all.stream().map(AlarmRow::getWindowCount).toList());
        }
        // 반복 창 수는 바깥에서 계산해도 같은 설비·코드의 앞선 발생 수(그 행 포함) — 설비별 3건이면 1·2·3
        List<AlarmRow> asc = runner.call(comp, () -> alarms.alarmGrid("P1", from, to, "E1", false, 1, 50, List.of("raisedAt,asc"), false).list());
        assertThat(asc).extracting(AlarmRow::getWindowCount).containsExactly(1, 2, 3);
    }

    @Test
    void 점검실행과_예방점검계획_쪽을_이어붙이면_한쪽과_같고_계수기누계도_같다() {
        long tmpl = accounts.query(comp, "insert into cms_chk_tmpl (comp_cd, tmpl_cd, tmpl_nm, created_by, updated_by) values (?, 'T', 't', 't', 't') returning tmpl_id",
                Long.class, comp);
        long counter = accounts.query(comp, """
                insert into cms_counter (comp_cd, counter_cd, counter_nm, target_type, equip_id, counter_kind, created_by, updated_by)
                select ?, 'C-E1', 'c', 'EQUIP', equip_id, 'SHOTS', 't', 't' from mst_equip where equip_cd = 'E1' returning counter_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into cms_counter_reading (comp_cd, counter_id, read_at, delta_qty, recorded_by)
                select ?, ?, now() - interval '10 days' + make_interval(hours => g), g, 'perf' from generate_series(1, 4) g""", comp, counter);
        accounts.exec(comp, "update cms_counter_reading set void_yn = 'Y', void_reason = 'x', voided_by = 't', voided_at = now() where counter_id = ? and delta_qty = 4",
                counter);   // 무효 판독은 누계에서 빠진다(1+2+3 = 6)
        accounts.exec(comp, """
                insert into cms_pm_plan (comp_cd, plan_cd, plan_nm, target_type, equip_id, tmpl_id, cycle_type, counter_id, usage_interval,
                                         anchor_usage, next_due_usage, created_by, updated_by)
                select ?, 'PL' || g, 'p', 'EQUIP', e.equip_id, ?, 'USAGE', ?, 100, 100, 100, 't', 't'
                  from generate_series(1, 3) g join mst_equip e on e.equip_cd = 'E1'""", comp, tmpl, counter);
        accounts.exec(comp, """
                insert into cms_chk_run (comp_cd, plan_id, target_type, equip_id, tmpl_id, due_date, due_usage, created_by, updated_by)
                select ?, p.plan_id, 'EQUIP', p.equip_id, p.tmpl_id, ?::date - (d % 2), 100 * (d + 1), 't', 't'
                  from cms_pm_plan p cross join generate_series(0, 2) d""", comp, today.toString());   // 같은 예정일이 많다(정렬 값 겹침)
        String from = today.minusDays(5).toString();
        String to = today.toString();
        for (List<String> sort : List.of(List.<String>of(), List.of("planCd,desc"), List.of("status,asc"))) {
            List<RunRow> all = runner.call(comp, () -> runs.grid(from, to, null, null, null, null, 1, 50, sort, true).list());
            assertThat(all).hasSize(9);
            List<RunRow> pages = stitched(9, p -> runner.call(comp, () -> runs.grid(from, to, null, null, null, null, p, 2, sort, false).list()));
            assertThat(pages).extracting(RunRow::getRunId).containsExactlyElementsOf(all.stream().map(RunRow::getRunId).toList());
        }
        assertThat(runner.call(comp, () -> runs.grid(from, to, null, null, null, null, 1, 2, null, true).total())).isEqualTo(9);
        assertThat(runner.call(comp, () -> runs.grid(from, to, null, null, "P1", null, 1, 50, null, false).list()))
                .extracting(RunRow::getCounterTotal).allSatisfy(t -> assertThat(t).isEqualByComparingTo(new BigDecimal("6")));
        List<PlanRow> allPlans = runner.call(comp, () -> plans.grid(null, null, null, 1, 50, null, true).list());
        List<PlanRow> planPages = stitched(3, p -> runner.call(comp, () -> plans.grid(null, null, null, p, 2, null, false).list()));
        assertThat(planPages).extracting(PlanRow::getPlanId).containsExactlyElementsOf(allPlans.stream().map(PlanRow::getPlanId).toList());
        assertThat(allPlans).extracting(PlanRow::getCounterTotal).allSatisfy(t -> assertThat(t).isEqualByComparingTo(new BigDecimal("6")));
        assertThat(runner.call(comp, () -> plans.grid(null, null, null, 1, 2, null, true).total())).isEqualTo(3);
    }

    @Test
    void 카운터_쪽을_이어붙이면_한쪽과_같고_누계는_무효제외() {
        accounts.exec(comp, """
                insert into cms_counter (comp_cd, counter_cd, counter_nm, target_type, equip_id, counter_kind, created_by, updated_by)
                select ?, 'C-' || e.equip_cd || '-' || k, 'c', 'EQUIP', e.equip_id, 'SHOTS', 't', 't'
                  from mst_equip e cross join generate_series(1, 2) k""", comp);   // 6개
        accounts.exec(comp, """
                insert into cms_counter_reading (comp_cd, counter_id, read_at, delta_qty, recorded_by)
                select ?, c.counter_id, now() - interval '1 day' + make_interval(mins => g), 5, 'perf'
                  from cms_counter c cross join generate_series(1, 3) g""", comp);
        accounts.exec(comp, "update cms_counter_reading set void_yn = 'Y', void_reason = 'x', voided_by = 't', voided_at = now() where reading_id in (select max(reading_id) from cms_counter_reading group by counter_id)");
        for (List<String> sort : List.of(List.<String>of(), List.of("lastReadAt,desc"), List.of("targetCd,asc"))) {
            List<CounterRow> all = runner.call(comp, () -> counters.grid(null, null, null, 1, 50, sort, true).list());
            assertThat(all).hasSize(6);
            List<CounterRow> pages = stitched(6, p -> runner.call(comp, () -> counters.grid(null, null, null, p, 2, sort, false).list()));
            assertThat(pages).extracting(CounterRow::getCounterId).containsExactlyElementsOf(all.stream().map(CounterRow::getCounterId).toList());
        }
        assertThat(runner.call(comp, () -> counters.grid(null, null, null, 1, 50, null, false).list()))
                .extracting(CounterRow::getTotal).allSatisfy(t -> assertThat(t).isEqualByComparingTo(new BigDecimal("10")));   // 5 × 3 − 무효 1건
    }
}
