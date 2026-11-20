package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.pms.PmsDtos.DepRow;
import io.smarthappy.module.pms.PmsDtos.PushMove;
import io.smarthappy.module.pms.PmsDtos.TaskRow;

/**
 * 선후행 FS·SS·FF 위반·밀기·실적 판정 — 손 검산 (docs/32 4.27, 사용자 결정 P-15). FS 만의 기존 표는 WbsScheduleTest.
 * 선행 1 = 3/1~3/10.
 */
class WbsScheduleDepTypeTest {

    private static TaskRow t(long id, String start, String end, String status) {
        TaskRow r = new TaskRow();
        r.setTaskId(id);
        r.setWbsNo(String.valueOf(id));
        r.setTaskNm("t" + id);
        r.setPlanStart(LocalDate.parse(start));
        r.setPlanEnd(LocalDate.parse(end));
        r.setStatus(status);
        return r;
    }

    private static DepRow d(long pred, long succ, int lag, String type) {
        DepRow r = new DepRow();
        r.setPredTaskId(pred);
        r.setSuccTaskId(succ);
        r.setLagDays(lag);
        r.setDepType(type);
        return r;
    }

    private static Map<Long, TaskRow> map(TaskRow... rows) {
        Map<Long, TaskRow> m = new HashMap<>();
        for (TaskRow r : rows) {
            m.put(r.getTaskId(), r);
        }
        return m;
    }

    static final TaskRow PRED = t(1, "2026-03-01", "2026-03-10", "NOT_STARTED");

    @Test
    void SS_는_후행시작이_선행시작_더하기_lag_보다_이르면_위반() {
        assertThat(WbsSchedule.violated("SS", PRED, t(2, "2026-03-01", "2026-03-02", "NOT_STARTED"), 0)).isFalse();   // 같은 날 시작 OK
        assertThat(WbsSchedule.violated("SS", PRED, t(2, "2026-02-28", "2026-03-02", "NOT_STARTED"), 0)).isTrue();
        assertThat(WbsSchedule.violated("SS", PRED, t(2, "2026-03-03", "2026-03-04", "NOT_STARTED"), 2)).isFalse();   // 3/1 + 2
        assertThat(WbsSchedule.violated("SS", PRED, t(2, "2026-03-02", "2026-03-04", "NOT_STARTED"), 2)).isTrue();
    }

    @Test
    void FF_는_후행종료가_선행종료_더하기_lag_보다_이르면_위반() {
        assertThat(WbsSchedule.violated("FF", PRED, t(2, "2026-03-05", "2026-03-10", "NOT_STARTED"), 0)).isFalse();   // 같은 날 끝 OK
        assertThat(WbsSchedule.violated("FF", PRED, t(2, "2026-03-05", "2026-03-09", "NOT_STARTED"), 0)).isTrue();
        assertThat(WbsSchedule.violated("FF", PRED, t(2, "2026-02-20", "2026-03-12", "NOT_STARTED"), 2)).isFalse();   // 시작은 상관없음
        assertThat(WbsSchedule.violated("FF", PRED, t(2, "2026-03-05", "2026-03-08", "NOT_STARTED"), -2)).isFalse();  // 음수 lag
        // FS 는 지금 그대로(3/11 부터)
        assertThat(WbsSchedule.violated("FS", PRED, t(2, "2026-03-10", "2026-03-12", "NOT_STARTED"), 0)).isTrue();
        assertThat(WbsSchedule.violated(null, PRED, t(2, "2026-03-11", "2026-03-12", "NOT_STARTED"), 0)).isFalse();   // null = FS
    }

    @Test
    void 밀기는_종류별_기준점까지_기간유지() {
        // 1(3/1~3/10) → 2 SS lag 3 (3/2~3/5, 4일) → 3/4~3/7 로 2일
        //            → 3 FF lag 0 (3/1~3/6, 6일)  → 종료 3/10 이 되게 3/5~3/10 으로 4일
        //            → 4 FS lag 0 (3/9~3/9)      → 3/11
        Map<Long, TaskRow> m = map(PRED, t(2, "2026-03-02", "2026-03-05", "NOT_STARTED"), t(3, "2026-03-01", "2026-03-06", "NOT_STARTED"),
                t(4, "2026-03-09", "2026-03-09", "NOT_STARTED"));
        WbsSchedule.Plan p = WbsSchedule.push(m, List.of(d(1, 2, 3, "SS"), d(1, 3, 0, "FF"), d(1, 4, 0, "FS")));
        Map<Long, PushMove> by = new HashMap<>();
        p.moves().forEach(x -> by.put(x.taskId(), x));
        assertThat(by.get(2L).newStart()).isEqualTo("2026-03-04");
        assertThat(by.get(2L).newEnd()).isEqualTo("2026-03-07");
        assertThat(by.get(2L).shiftDays()).isEqualTo(2);
        assertThat(by.get(3L).newStart()).isEqualTo("2026-03-05");
        assertThat(by.get(3L).newEnd()).isEqualTo("2026-03-10");
        assertThat(by.get(4L).newStart()).isEqualTo("2026-03-11");
        assertThat(p.fixed()).isEmpty();
    }

    @Test
    void 밀기는_사슬에서_SS_뒤_FF_로_이어진다_이미_늦으면_그대로() {
        // 1 → 2 FS (2 = 3/5~3/6 → 3/11~3/12), 2 → 3 SS lag 1 (3 = 3/7~3/8 → 3/12~3/13), 3 → 4 FF (4 = 3/1~3/20 이미 늦음 → 그대로)
        Map<Long, TaskRow> m = map(PRED, t(2, "2026-03-05", "2026-03-06", "NOT_STARTED"), t(3, "2026-03-07", "2026-03-08", "NOT_STARTED"),
                t(4, "2026-03-01", "2026-03-20", "NOT_STARTED"));
        WbsSchedule.Plan p = WbsSchedule.push(m, List.of(d(1, 2, 0, "FS"), d(2, 3, 1, "SS"), d(3, 4, 0, "FF")));
        assertThat(p.moves()).extracting(PushMove::taskId).containsExactly(2L, 3L);
        assertThat(p.moves().get(1).newStart()).isEqualTo("2026-03-12");
        // 완료된 후행은 움직이지 않고 fixed 로
        Map<Long, TaskRow> m2 = map(PRED, t(5, "2026-03-01", "2026-03-03", "DONE"));
        WbsSchedule.Plan p2 = WbsSchedule.push(m2, List.of(d(1, 5, 0, "FF")));
        assertThat(p2.moves()).isEmpty();
        assertThat(p2.fixed()).extracting(PushMove::taskId).containsExactly(5L);
    }

    @Test
    void 순환은_종류와_무관() {
        assertThat(WbsSchedule.makesCycle(List.of(d(1, 2, 0, "SS")), 2, 1)).isTrue();
        assertThat(WbsSchedule.makesCycle(List.of(d(1, 2, 0, "FF"), d(2, 3, 0, "SS")), 3, 1)).isTrue();
    }

    @Test
    void 실적_판정_시작순간_FS_SS_완료순간_FF() {
        TaskRow open = t(1, "2026-03-01", "2026-03-10", "NOT_STARTED");
        TaskRow started = t(1, "2026-03-01", "2026-03-10", "IN_PROGRESS");
        started.setActStart(LocalDate.parse("2026-03-01"));
        TaskRow done = t(1, "2026-03-01", "2026-03-10", "DONE");
        TaskRow cancelled = t(1, "2026-03-01", "2026-03-10", "CANCELLED");
        // 시작 순간
        assertThat(TaskService.unmet("FS", open, false)).isTrue();
        assertThat(TaskService.unmet("FS", started, false)).isTrue();
        assertThat(TaskService.unmet("FS", done, false)).isFalse();
        assertThat(TaskService.unmet("SS", open, false)).isTrue();
        assertThat(TaskService.unmet("SS", started, false)).isFalse();
        assertThat(TaskService.unmet("SS", cancelled, false)).isFalse();
        assertThat(TaskService.unmet("FF", open, false)).isFalse();     // FF 는 시작 때 보지 않는다
        // 완료 순간
        assertThat(TaskService.unmet("FF", started, true)).isTrue();
        assertThat(TaskService.unmet("FF", done, true)).isFalse();
        assertThat(TaskService.unmet("FS", open, true)).isFalse();      // FS·SS 는 완료 때 다시 보지 않는다(Q-S2)
        assertThat(TaskService.unmet("SS", open, true)).isFalse();
        assertThat(TaskService.unmet("FS", null, false)).isFalse();
    }
}
