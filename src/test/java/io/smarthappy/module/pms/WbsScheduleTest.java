package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.pms.PmsDtos.DepRow;
import io.smarthappy.module.pms.PmsDtos.PushMove;
import io.smarthappy.module.pms.PmsDtos.TaskRow;

/** FS 의존 위반·후행 밀기(위상 순서, 필요한 만큼만, 완료는 고정)·순환 판정 — 표 기반 손 검산 (docs/32 PM2) */
class WbsScheduleTest {

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

    private static DepRow d(long pred, long succ, int lag) {
        DepRow r = new DepRow();
        r.setPredTaskId(pred);
        r.setSuccTaskId(succ);
        r.setLagDays(lag);
        return r;
    }

    private static Map<Long, TaskRow> map(TaskRow... rows) {
        Map<Long, TaskRow> m = new HashMap<>();
        for (TaskRow r : rows) {
            m.put(r.getTaskId(), r);
        }
        return m;
    }

    @Test
    void 위반은_후행시작이_선행종료_다음날_더하기_lag_보다_이를때() {
        Map<Long, TaskRow> m = map(t(1, "2026-03-01", "2026-03-10", "NOT_STARTED"), t(2, "2026-03-11", "2026-03-12", "NOT_STARTED"),
                t(3, "2026-03-12", "2026-03-12", "NOT_STARTED"));
        List<DepRow> deps = List.of(d(1, 2, 0), d(1, 3, 2));
        assertThat(WbsSchedule.markViolations(m, deps)).containsExactly(3L);   // 3 은 3-13 이후여야
        assertThat(deps.get(0).isViolated()).isFalse();
        // 음수 lag(겹쳐 시작 허용): 선행 종료 3일 전부터
        assertThat(WbsSchedule.violated(m.get(1L), t(9, "2026-03-08", "2026-03-09", "NOT_STARTED"), -3)).isFalse();
        assertThat(WbsSchedule.violated(m.get(1L), t(9, "2026-03-07", "2026-03-09", "NOT_STARTED"), -3)).isTrue();
    }

    @Test
    void 밀기는_사슬을_따라_필요한_만큼만_기간유지_여러선행이면_늦은쪽() {
        // 1(3-01~03-10) → 2(3-05~03-06, 2일) → 4 ; 3(3-01~03-20) → 4(3-15~03-15 마일스톤) ; 5 는 이미 충분히 늦음
        Map<Long, TaskRow> m = map(t(1, "2026-03-01", "2026-03-10", "IN_PROGRESS"), t(2, "2026-03-05", "2026-03-06", "NOT_STARTED"),
                t(3, "2026-03-01", "2026-03-20", "NOT_STARTED"), t(4, "2026-03-15", "2026-03-15", "NOT_STARTED"),
                t(5, "2026-04-01", "2026-04-02", "NOT_STARTED"));
        WbsSchedule.Plan p = WbsSchedule.push(m, List.of(d(1, 2, 0), d(2, 4, 1), d(3, 4, 0), d(4, 5, 0)));
        assertThat(p.moves()).extracting(PushMove::taskId).containsExactly(2L, 4L);
        PushMove m2 = p.moves().get(0);
        assertThat(m2.newStart()).isEqualTo("2026-03-11");
        assertThat(m2.newEnd()).isEqualTo("2026-03-12");       // 2일 유지
        assertThat(m2.shiftDays()).isEqualTo(6);
        PushMove m4 = p.moves().get(1);
        // 2 의 새 종료 3-12 + 1 + lag1 = 3-14, 3 의 종료 3-20 + 1 = 3-21 → 늦은 쪽
        assertThat(m4.newStart()).isEqualTo("2026-03-21");
        assertThat(m4.newEnd()).isEqualTo("2026-03-21");
        assertThat(p.fixed()).isEmpty();
    }

    @Test
    void 완료_취소_후행은_움직이지_않고_fixed_로_그_뒤는_지금계획_기준() {
        Map<Long, TaskRow> m = map(t(1, "2026-03-01", "2026-03-10", "NOT_STARTED"), t(2, "2026-03-05", "2026-03-06", "DONE"),
                t(3, "2026-03-07", "2026-03-07", "NOT_STARTED"));
        WbsSchedule.Plan p = WbsSchedule.push(m, List.of(d(1, 2, 0), d(2, 3, 0)));
        assertThat(p.fixed()).extracting(PushMove::taskId).containsExactly(2L);
        assertThat(p.moves()).isEmpty();                         // 3 은 2 의 지금 종료(3-06) 다음 날 이후 — 이미 맞음
    }

    @Test
    void 순환_판정과_순환이_있는_데이터() {
        List<DepRow> deps = List.of(d(1, 2, 0), d(2, 3, 0));
        assertThat(WbsSchedule.makesCycle(deps, 3, 1)).isTrue();
        assertThat(WbsSchedule.makesCycle(deps, 1, 3)).isFalse();
        // 마름모(1→2→4, 1→3→4)는 순환 아님
        assertThat(WbsSchedule.makesCycle(List.of(d(1, 2, 0), d(1, 3, 0), d(2, 4, 0), d(3, 4, 0)), 1, 4)).isFalse();
        Map<Long, TaskRow> m = map(t(1, "2026-03-01", "2026-03-01", "NOT_STARTED"), t(2, "2026-03-01", "2026-03-01", "NOT_STARTED"));
        assertThatThrownBy(() -> WbsSchedule.push(m, List.of(d(1, 2, 0), d(2, 1, 0)))).isInstanceOf(IllegalStateException.class);
    }
}
