package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** PmDue 표 기반 검산 (docs/33 4.3) — DB 없음 */
class PmDueTest {

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    private static PmDue.Rule fixed(String unit, int n, String anchor) {
        return new PmDue.Rule("FIXED", unit, n, d(anchor));
    }

    @Test
    void 고정_월말은_첫예정일에서_다시_더해_날이_밀리지_않는다() {
        PmDue.Rule r = fixed("MONTH", 1, "2026-01-31");
        assertThat(PmDue.preview(r, r.anchor(), 4)).containsExactly(d("2026-01-31"), d("2026-02-28"), d("2026-03-31"), d("2026-04-30"));
        assertThat(PmDue.fixedAfter(r, d("2026-02-28"))).isEqualTo(d("2026-03-31"));
        assertThat(PmDue.fixedAfter(r, d("2026-02-27"))).isEqualTo(d("2026-02-28"));
        assertThat(PmDue.fixedAfter(r, d("2025-12-01"))).isEqualTo(d("2026-01-31"));   // 첫 예정일 앞이면 첫 예정일
        // 3개월마다, 1년 뒤
        assertThat(PmDue.fixedAfter(fixed("MONTH", 3, "2026-01-31"), d("2027-01-30"))).isEqualTo(d("2027-01-31"));
    }

    @Test
    void 윤년_2월29일_매년은_평년_28일_윤년_29일() {
        PmDue.Rule r = fixed("YEAR", 1, "2028-02-29");
        assertThat(PmDue.preview(r, r.anchor(), 5))
                .containsExactly(d("2028-02-29"), d("2029-02-28"), d("2030-02-28"), d("2031-02-28"), d("2032-02-29"));
    }

    @Test
    void 일_주_간격() {
        assertThat(PmDue.fixedAfter(fixed("DAY", 3, "2026-10-01"), d("2026-10-07"))).isEqualTo(d("2026-10-10"));
        assertThat(PmDue.fixedAfter(fixed("DAY", 3, "2026-10-01"), d("2026-10-06"))).isEqualTo(d("2026-10-07"));
        assertThat(PmDue.fixedAfter(fixed("WEEK", 2, "2026-10-05"), d("2026-10-19"))).isEqualTo(d("2026-11-02"));
    }

    @Test
    void 완료뒤_다음은_고정이면_늦게한만큼_놓친회차_건너뛰고_완료기준이면_완료일부터() {
        PmDue.Rule f = fixed("MONTH", 1, "2026-01-01");
        assertThat(PmDue.next(f, d("2026-01-01"), d("2025-12-28"))).isEqualTo(d("2026-02-01"));   // 일찍 함
        assertThat(PmDue.next(f, d("2026-01-01"), d("2026-02-10"))).isEqualTo(d("2026-03-01"));   // 늦게 함 → 2/1 회차 건너뜀
        assertThat(PmDue.next(f, d("2026-01-01"), null)).isEqualTo(d("2026-02-01"));              // 건너뛰기
        PmDue.Rule fl = new PmDue.Rule("FLOAT", "DAY", 30, d("2026-01-01"));
        assertThat(PmDue.next(fl, d("2026-01-01"), d("2026-01-10"))).isEqualTo(d("2026-02-09"));
        assertThat(PmDue.next(fl, d("2026-01-01"), null)).isEqualTo(d("2026-01-31"));
        assertThat(PmDue.preview(fl, d("2026-01-01"), 3)).containsExactly(d("2026-01-01"), d("2026-01-31"), d("2026-03-02"));
    }

    @Test
    void 처음_예정과_규칙변경_뒤_예정() {
        PmDue.Rule f = fixed("WEEK", 1, "2026-10-05");
        assertThat(PmDue.initial(f, null)).isEqualTo(d("2026-10-05"));
        assertThat(PmDue.initial(f, d("2026-10-14"))).isEqualTo(d("2026-10-19"));
        PmDue.Rule fl = new PmDue.Rule("FLOAT", "MONTH", 1, d("2026-12-01"));
        assertThat(PmDue.initial(fl, d("2026-10-14"))).isEqualTo(d("2026-12-01"));   // 완료 + 간격이 첫 예정일 앞이면 첫 예정일
        assertThat(PmDue.initial(fl, d("2026-11-14"))).isEqualTo(d("2026-12-14"));
    }

    @Test
    void 상태_경계와_기한준수() {
        LocalDate due = d("2026-10-20");
        assertThat(PmDue.state(due, d("2026-10-12"), 7)).isEqualTo(PmDue.State.UPCOMING);
        assertThat(PmDue.state(due, d("2026-10-13"), 7)).isEqualTo(PmDue.State.DUE);       // 예정 − 선행 = 오늘
        assertThat(PmDue.state(due, d("2026-10-20"), 7)).isEqualTo(PmDue.State.DUE);       // 오늘 = 예정
        assertThat(PmDue.state(due, d("2026-10-21"), 7)).isEqualTo(PmDue.State.OVERDUE);
        assertThat(PmDue.state(due, d("2026-10-20"), 0)).isEqualTo(PmDue.State.DUE);
        assertThat(PmDue.onTime(due, d("2026-10-20"), 0)).isTrue();
        assertThat(PmDue.onTime(due, d("2026-10-21"), 0)).isFalse();
        assertThat(PmDue.onTime(due, d("2026-10-22"), 2)).isTrue();
    }
}
