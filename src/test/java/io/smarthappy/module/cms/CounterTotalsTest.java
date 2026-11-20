package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** C3 순수 함수 표 기반 검산 (docs/33 4.4) — 카운터 증가량·금형 수명, 사용량 주기 예정. DB 없음 */
class CounterTotalsTest {

    private static BigDecimal n(String s) {
        return new BigDecimal(s);
    }

    private static PmDue.UsageRule usage(String basis, String interval, String anchor) {
        return new PmDue.UsageRule(basis, n(interval), n(anchor));
    }

    // ---------- 증가량 ----------

    @Test
    void 증가량_첫값은_기준0_늘면_차이_줄면_리셋없이_거부_리셋이면_이번값() {
        assertThat(CounterTotals.delta(null, n("12000"), false)).isEqualByComparingTo("0");     // 첫 계기 값 = 기준
        assertThat(CounterTotals.delta(n("12000"), n("12500"), false)).isEqualByComparingTo("500");
        assertThat(CounterTotals.delta(n("12500"), n("12500"), false)).isEqualByComparingTo("0");   // 같은 값 = 0
        assertThat(CounterTotals.delta(n("12500"), n("120"), false)).isNull();                  // 줄었는데 리셋 아님
        assertThat(CounterTotals.delta(n("12500"), n("120"), true)).isEqualByComparingTo("120"); // 리셋 = 0 부터
        assertThat(CounterTotals.delta(null, n("40"), true)).isEqualByComparingTo("40");         // 첫 값이어도 리셋이면 그 값
        // 소수 자릿수(가동 시간 0.1h)는 반올림 없이
        assertThat(CounterTotals.delta(n("1234.5"), n("1240.1"), false)).isEqualByComparingTo("5.6");
    }

    @Test
    void 금형_수명_경계는_90퍼센트와_100퍼센트_포함() {
        assertThat(CounterTotals.life(n("1"), null)).isEqualTo(CounterTotals.Life.NONE);
        assertThat(CounterTotals.life(n("89999"), 100000L)).isEqualTo(CounterTotals.Life.OK);
        assertThat(CounterTotals.life(n("90000"), 100000L)).isEqualTo(CounterTotals.Life.NEAR);
        assertThat(CounterTotals.life(n("99999.9"), 100000L)).isEqualTo(CounterTotals.Life.NEAR);
        assertThat(CounterTotals.life(n("100000"), 100000L)).isEqualTo(CounterTotals.Life.OVER);
    }

    // ---------- 사용량 주기 ----------

    @Test
    void 사용량_고정은_첫예정에서_간격씩_넘겨쓴_회차는_건너뜀() {
        PmDue.UsageRule r = usage("FIXED", "50000", "50000");
        assertThat(PmDue.previewUsage(r, r.anchor(), 3)).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(n("50000"), n("100000"), n("150000"));
        assertThat(PmDue.nextUsage(r, n("50000"), n("49000"))).isEqualByComparingTo("100000");    // 일찍 해도 고정 자리
        assertThat(PmDue.nextUsage(r, n("50000"), n("100000"))).isEqualByComparingTo("150000");   // 딱 다음 자리에서 하면 그 다음
        assertThat(PmDue.nextUsage(r, n("50000"), n("123456"))).isEqualByComparingTo("150000");   // 100000 은 놓침 → 건너뜀
        assertThat(PmDue.nextUsage(r, n("50000"), null)).isEqualByComparingTo("100000");          // 건너뛰기 = 예정에 한 것으로
        assertThat(PmDue.usageFixedAfter(r, n("10"))).isEqualByComparingTo("50000");              // 첫 예정 앞이면 첫 예정
        assertThat(PmDue.initialUsage(r, n("120000"))).isEqualByComparingTo("150000");
    }

    @Test
    void 사용량_완료기준은_완료때_누적부터_간격() {
        PmDue.UsageRule r = usage("FLOAT", "250.5", "1000");
        assertThat(PmDue.nextUsage(r, n("1000"), n("1012.3"))).isEqualByComparingTo("1262.8");
        assertThat(PmDue.nextUsage(r, n("1000"), null)).isEqualByComparingTo("1250.5");
        assertThat(PmDue.initialUsage(r, null)).isEqualByComparingTo("1000");
        assertThat(PmDue.initialUsage(r, n("500"))).isEqualByComparingTo("1000");                 // 첫 예정보다 앞이면 첫 예정
        assertThat(PmDue.initialUsage(r, n("900"))).isEqualByComparingTo("1150.5");
    }

    @Test
    void 사용량_상태_선행과_경계_기한준수() {
        // 예정 50000, 선행 2000: 47999 예정 / 48000 할 때(경계 포함) / 50000 할 때 / 50001 지남
        assertThat(PmDue.usageState(n("50000"), n("47999"), n("2000"))).isEqualTo(PmDue.State.UPCOMING);
        assertThat(PmDue.usageState(n("50000"), n("48000"), n("2000"))).isEqualTo(PmDue.State.DUE);
        assertThat(PmDue.usageState(n("50000"), n("50000"), n("2000"))).isEqualTo(PmDue.State.DUE);
        assertThat(PmDue.usageState(n("50000"), n("50001"), n("2000"))).isEqualTo(PmDue.State.OVERDUE);
        assertThat(PmDue.usageReached(n("50000"), n("49999"), null)).isFalse();                   // 선행 없음 = 0
        assertThat(PmDue.usageReached(n("50000"), n("50000"), null)).isTrue();
        assertThat(PmDue.usageOnTime(n("50000"), n("50000"))).isTrue();
        assertThat(PmDue.usageOnTime(n("50000"), n("50000.1"))).isFalse();
        // 규칙 비교는 값으로(5.0 과 5.00 은 같은 규칙)
        assertThat(usage("FIXED", "5.0", "10").sameAs(usage("FIXED", "5.00", "10.0"))).isTrue();
        assertThat(usage("FIXED", "5", "10").sameAs(usage("FLOAT", "5", "10"))).isFalse();
    }
}
