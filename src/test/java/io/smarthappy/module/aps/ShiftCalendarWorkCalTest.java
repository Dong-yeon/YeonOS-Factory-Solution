package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.aps.ShiftCalendar.Shift;
import io.smarthappy.module.master.workforce.WorkCalendar.DayPlan;
import io.smarthappy.module.master.workforce.WorkCalendar.ShiftDay;

/**
 * APS 4단계 근무 구간 (docs/38 13장) — DB 없이: 휴일 없는 달력 = 1~3단계 구간(회귀), 작업일 − day_offset(야간 3조),
 * 쉬는 날, 휴일 특근 근무조, 전날 야간조가 휴일 새벽에 이어지는 것.
 */
class ShiftCalendarWorkCalTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate D1 = LocalDate.of(2026, 11, 2);
    private static final ShiftDay DAY = new ShiftDay("D", "주간", LocalTime.of(8, 0), LocalTime.of(16, 0), 0, 0);
    private static final ShiftDay EVE = new ShiftDay("E", "야간", LocalTime.of(16, 0), LocalTime.of(0, 0), 0, 30);
    /** 3조 00~08 — 작업일은 전날(day_offset −1) */
    private static final ShiftDay NIGHT = new ShiftDay("N", "3조", LocalTime.of(0, 0), LocalTime.of(8, 0), -1, 0);

    private static Instant at(LocalDate d, int h, int m) {
        return LocalDateTime.of(d, LocalTime.of(h, m)).atZone(SEOUL).toInstant();
    }

    /** 작업일 [first − 2, last] — workday 가 false 인 날은 off 에, 그날 근무조는 special */
    private static List<DayPlan> days(int n, List<ShiftDay> all, LocalDate off, List<ShiftDay> special) {
        List<DayPlan> out = new ArrayList<>();
        for (LocalDate d = D1.minusDays(2); !d.isAfter(D1.plusDays(n - 1)); d = d.plusDays(1)) {
            boolean isOff = d.equals(off);
            List<ShiftDay> on = isOff ? special : all;
            out.add(new DayPlan(d, !isOff || !on.isEmpty(), isOff ? "WORK_CAL" : "DEFAULT", isOff ? "ZT 휴일" : null, on));
        }
        return out;
    }

    private static ShiftCalendar build(List<DayPlan> days, int n) {
        return ShiftCalendar.build(ShiftCalendar.byStartDay(days, D1, D1.plusDays(n - 1)), SEOUL, at(D1, 0, 0), at(D1.plusDays(n), 0, 0));
    }

    @Test
    void 휴일이_없으면_1단계와_같은_근무_구간이다() {
        List<ShiftDay> all = List.of(DAY, EVE, NIGHT);
        ShiftCalendar now = build(days(5, all, null, List.of()), 5);
        ShiftCalendar old = ShiftCalendar.build(all.stream().map(s -> new Shift(s.start(), s.end(), s.breakMin())).toList(),
                SEOUL, D1, 5, at(D1, 0, 0), at(D1.plusDays(5), 0, 0));
        assertThat(now.windows()).isEqualTo(old.windows());
        assertThat(now.windows()).isNotEmpty();
    }

    @Test
    void 쉬는_날은_구간이_없고_3조는_작업일_다음_날_새벽이다() {
        // 둘째 날(D1+1) 공장 휴일: 주간·야간 없음, 그 작업일의 3조(D1+2 00~08)도 없음. 전날 작업일의 3조(D1+1 00~08)는 있다
        ShiftCalendar c = build(days(3, List.of(DAY, EVE, NIGHT), D1.plusDays(1), List.of()), 3);
        assertThat(c.windows()).extracting(ShiftCalendar.Window::start)
                .containsExactly(at(D1, 0, 0), at(D1.plusDays(1), 0, 0), at(D1.plusDays(2), 8, 0));
        assertThat(c.windows()).extracting(ShiftCalendar.Window::end)
                .containsExactly(at(D1, 23, 30), at(D1.plusDays(1), 8, 0), at(D1.plusDays(2), 23, 30));
        assertThat(c.nextWorking(at(D1.plusDays(1), 9, 0))).isEqualTo(at(D1.plusDays(2), 8, 0));
    }

    @Test
    void 휴일_특근_근무조만_일한다() {
        ShiftCalendar c = build(days(3, List.of(DAY), D1.plusDays(1), List.of(DAY)), 3);
        assertThat(c.windows()).hasSize(3);
        ShiftCalendar off = build(days(3, List.of(DAY), D1.plusDays(1), List.of()), 3);
        assertThat(off.windows()).extracting(ShiftCalendar.Window::start).containsExactly(at(D1, 8, 0), at(D1.plusDays(2), 8, 0));
    }
}
