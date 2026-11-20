package io.smarthappy.module.master.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.master.workforce.WorkCalDtos.CalRow;
import io.smarthappy.module.master.workforce.WorkCalendar.DayPlan;
import io.smarthappy.module.master.workforce.WorkCalendar.ShiftDay;
import io.smarthappy.module.master.workforce.WorkforceDtos.ShiftRow;

/**
 * 공장 휴일 달력 판정 (docs/25 4.15) — 순수 계산. 우선순위: 공장·근무조 &gt; 공장 하루 &gt; 회사 &gt; 주간 쉬는 날 &gt; 일한다.
 * 2026-11-02 = 월요일, 11-07 = 토, 11-08 = 일.
 */
class WorkCalendarTest {

    private static final LocalDate MON = LocalDate.parse("2026-11-02");
    private static final LocalDate SAT = LocalDate.parse("2026-11-07");
    private static final LocalDate SUN = LocalDate.parse("2026-11-08");

    private static ShiftRow shift(String cd, String start, String end) {
        ShiftRow s = new ShiftRow();
        s.setShiftCd(cd);
        s.setShiftNm(cd + "조");
        s.setStartTime(LocalTime.parse(start));
        s.setEndTime(LocalTime.parse(end));
        return s;
    }

    private static final List<ShiftRow> SHIFTS = List.of(shift("D", "08:00", "20:00"), shift("N", "20:00", "08:00"));

    private static CalRow row(String plant, LocalDate day, String shift, String type, String reason) {
        CalRow r = new CalRow();
        r.setPlantCd(plant);
        r.setCalDate(day);
        r.setShiftCd(shift);
        r.setDayType(type);
        r.setReason(reason);
        return r;
    }

    private static Map<LocalDate, DayPlan> plan(String plant, String weekly, List<CalRow> rows) {
        return WorkCalendar.plan(plant, MON, SUN, weekly, plant == null ? List.of() : SHIFTS, rows);
    }

    private static List<String> on(DayPlan d) {
        return d.shifts().stream().map(ShiftDay::shiftCd).toList();
    }

    @Test
    void 기본은_매일_일하고_주간_쉬는_날은_정책대로() {
        Map<LocalDate, DayPlan> none = plan("P1", null, List.of());
        assertThat(none.values()).allSatisfy(d -> assertThat(d.workday()).isTrue());
        assertThat(on(none.get(SUN))).containsExactly("D", "N");
        assertThat(none.get(MON).source()).isEqualTo("DEFAULT");

        Map<LocalDate, DayPlan> sun = plan("P1", "SUN", List.of());
        assertThat(sun.get(SAT).workday()).isTrue();
        assertThat(sun.get(SUN).workday()).isFalse();
        assertThat(sun.get(SUN).source()).isEqualTo("WEEKLY");
        assertThat(on(sun.get(SUN))).isEmpty();

        Map<LocalDate, DayPlan> five = plan("P1", "SAT_SUN", List.of());
        assertThat(five.get(SAT).workday()).isFalse();
        assertThat(five.get(SUN).workday()).isFalse();
        assertThat(five.get(MON).workday()).isTrue();
        // 모르는 값은 없음으로(정책 서비스가 저장 때 막지만 읽기는 안전하게)
        assertThat(plan("P1", "XYZ", List.of()).get(SUN).workday()).isTrue();
    }

    @Test
    void 회사_휴일_위에_공장_하루_공장_근무조_순으로_이긴다() {
        List<CalRow> rows = List.of(
                row(null, MON, null, "OFF", "회사 창립일"),          // 회사: 월 쉼
                row("P1", MON, null, "WORK", "대체 근무"),            // 공장 P1: 월은 일함(회사를 이김)
                row(null, LocalDate.parse("2026-11-03"), null, "OFF", "회사 휴일"),
                row("P1", SUN, "D", "WORK", "주말 특근"),              // 주간 쉼(일요일) 위에 주간조만 특근
                row("P1", SAT, null, "WORK", "토요 전체 근무"),        // 주간 쉼(토) 위에 하루 전체 근무
                row("P1", LocalDate.parse("2026-11-04"), "N", "OFF", "야간 휴무"), // 평일 야간조만 쉼
                row("P2", LocalDate.parse("2026-11-05"), null, "OFF", "다른 공장"));
        Map<LocalDate, DayPlan> p = plan("P1", "SAT_SUN", rows);

        assertThat(p.get(MON).workday()).isTrue();
        assertThat(p.get(MON).source()).isEqualTo("WORK_CAL");
        assertThat(p.get(MON).reason()).isEqualTo("대체 근무");
        assertThat(on(p.get(MON))).containsExactly("D", "N");

        DayPlan tue = p.get(LocalDate.parse("2026-11-03"));
        assertThat(tue.workday()).isFalse();
        assertThat(tue.source()).isEqualTo("COMP_CAL");

        DayPlan wed = p.get(LocalDate.parse("2026-11-04"));
        assertThat(wed.workday()).isTrue();
        assertThat(on(wed)).containsExactly("D");

        // 다른 공장 행은 보지 않는다
        assertThat(p.get(LocalDate.parse("2026-11-05")).workday()).isTrue();

        assertThat(p.get(SAT).workday()).isTrue();
        assertThat(on(p.get(SAT))).containsExactly("D", "N");

        // 하루는 쉬어도(주간) 근무조 특근이 있으면 그날은 일하는 날 — 그 근무조만
        DayPlan sun = p.get(SUN);
        assertThat(sun.workday()).isTrue();
        assertThat(sun.source()).isEqualTo("WEEKLY");
        assertThat(on(sun)).containsExactly("D");
    }

    @Test
    void 회사_층만_읽으면_공장_행은_무시하고_근무조는_없다() {
        List<CalRow> rows = List.of(row(null, MON, null, "OFF", "회사 휴일"), row("P1", SAT, null, "WORK", "공장 근무"));
        Map<LocalDate, DayPlan> c = plan(null, "SAT_SUN", rows);
        assertThat(c.get(MON).workday()).isFalse();
        assertThat(c.get(SAT).workday()).isFalse();
        assertThat(c.get(MON).shifts()).isEmpty();
        assertThat(c.get(LocalDate.parse("2026-11-03")).workday()).isTrue();
    }
}
