package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 부하 계산 순수 함수 (docs/32 4.26) — 손 검산. 2026-03-02 = 월요일. 달력: 토·일 쉬고 3/4(수) 회사 휴일.
 */
class PmsLoadCalcTest {

    static final LocalDate MON = LocalDate.of(2026, 3, 2);

    /** MON-14 부터 120일 — 주말·3/4 쉼 */
    static PmsLoad.Cal cal() {
        LocalDate from = MON.minusDays(14);
        boolean[] wd = new boolean[120];
        for (int i = 0; i < wd.length; i++) {
            LocalDate d = from.plusDays(i);
            wd[i] = d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !d.equals(LocalDate.of(2026, 3, 4));
        }
        return new PmsLoad.Cal(from, wd);
    }

    static PmsLoad.Task t(Long owner, LocalDate s, LocalDate e, String h) {
        return new PmsLoad.Task(1, owner, s, e, new BigDecimal(h));
    }

    @Test
    void 근무일에_고르게_나누고_휴일은_뺀다() {
        // 3/2(월)~3/13(금): 근무일 = 1주 4일(수 휴일) + 2주 5일 = 9 → 36시간 = 하루 4시간 → 16 / 20
        PmsLoad.Task x = t(7L, MON, MON.plusDays(11), "36");
        assertThat(PmsLoad.share(x, MON, cal())).isEqualByComparingTo("16");
        assertThat(PmsLoad.share(x, MON.plusWeeks(1), cal())).isEqualByComparingTo("20");
        assertThat(PmsLoad.share(x, MON.plusWeeks(2), cal())).isEqualByComparingTo("0");
        assertThat(PmsLoad.share(x, MON.minusWeeks(1), cal())).isEqualByComparingTo("0");
    }

    @Test
    void 주말에_걸친_작업과_주_중간_시작() {
        // 3/5(목)~3/10(화): 근무일 목·금·월·화 = 4 → 10시간: 1주 5, 2주 5
        PmsLoad.Task x = t(7L, MON.plusDays(3), MON.plusDays(8), "10");
        assertThat(PmsLoad.share(x, MON, cal())).isEqualByComparingTo("5");
        assertThat(PmsLoad.share(x, MON.plusWeeks(1), cal())).isEqualByComparingTo("5");
    }

    @Test
    void 쉬는_날뿐인_작업은_달력일로_나눠_사라지지_않는다() {
        // 3/7(토)~3/8(일) 8시간 → 근무일 0 → 달력일 2일, 둘 다 1주(3/2~3/8)
        PmsLoad.Task x = t(7L, MON.plusDays(5), MON.plusDays(6), "8");
        assertThat(PmsLoad.share(x, MON, cal())).isEqualByComparingTo("8");
        // 3/4(휴일) 하루짜리
        assertThat(PmsLoad.share(t(7L, MON.plusDays(2), MON.plusDays(2), "3"), MON, cal())).isEqualByComparingTo("3");
    }

    @Test
    void 달력_창_밖으로_나가는_긴_작업은_달력일로() {
        // 창(MON-14 ~ +119) 밖에서 시작 → 달력일: 2026-01-01~2026-03-31 = 90일, 1주(7일) 몫 = 90 × 7 / 90 = 7
        PmsLoad.Task x = t(7L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), "90");
        assertThat(PmsLoad.share(x, MON, cal())).isEqualByComparingTo("7");
    }

    @Test
    void 반올림은_주마다_소수_둘째자리() {
        // 3/2~3/13 근무일 9, 10시간 → 1주 40/9 = 4.44, 2주 50/9 = 5.56 (합 10.00)
        PmsLoad.Task x = t(7L, MON, MON.plusDays(11), "10");
        assertThat(PmsLoad.share(x, MON, cal())).isEqualByComparingTo("4.44");
        assertThat(PmsLoad.share(x, MON.plusWeeks(1), cal())).isEqualByComparingTo("5.56");
    }

    @Test
    void 사람별_합과_담당없음_null_키() {
        Map<Long, BigDecimal[]> m = PmsLoad.plan(List.of(
                t(7L, MON, MON.plusDays(1), "8"),          // 월·화 → 1주 8
                t(7L, MON.plusDays(7), MON.plusDays(7), "4"),   // 2주 월 4
                t(9L, MON, MON.plusDays(4), "12"),         // 근무일 4 → 1주 12
                t(null, MON, MON, "2")), MON, 3, cal());
        assertThat(m.get(7L)).extracting(BigDecimal::toPlainString).containsExactly("8.00", "4.00", "0");
        assertThat(m.get(9L)[0]).isEqualByComparingTo("12");
        assertThat(m.get(null)[0]).isEqualByComparingTo("2");
        assertThat(PmsLoad.monday(LocalDate.of(2026, 3, 8))).isEqualTo(MON);   // 일요일 → 그 주 월요일
        assertThat(PmsLoad.monday(MON)).isEqualTo(MON);
    }
}
