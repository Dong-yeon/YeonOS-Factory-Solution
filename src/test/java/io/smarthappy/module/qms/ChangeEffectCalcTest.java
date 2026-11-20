package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.qms.ChangeEffectCalc.VarStats;

/** 변경 전후 비교 계산 손 검산 (docs/29_P3 51.3 EF-2·EF-4) — DB 없음 */
class ChangeEffectCalcTest {

    static final BigDecimal LSL = new BigDecimal("9.95");
    static final BigDecimal USL = new BigDecimal("10.05");

    static List<BigDecimal> v(String... s) {
        return Stream.of(s).map(BigDecimal::new).toList();
    }

    @Test
    void 계량_손검산_평균_표본표준편차_Ppk_규격밖() {
        // 10.00 10.02 10.04 10.06 10.03 → 평균 10.03, 편차 제곱합 0.002, 분산 0.002/4 = 0.0005, σ = 0.022360…
        // Ppk = min((10.05 − 10.03)/(3σ), (10.03 − 9.95)/(3σ)) = 0.02/0.067082 = 0.29814, 규격 밖 = 10.06 하나
        VarStats s = ChangeEffectCalc.variable(v("10.00", "10.02", "10.04", "10.06", "10.03"), LSL, USL);
        assertThat(s.n()).isEqualTo(5);
        assertThat(s.mean()).isCloseTo(10.03, within(1e-9));
        assertThat(s.sd()).isCloseTo(Math.sqrt(0.0005), within(1e-9));
        assertThat(s.ppk()).isCloseTo(0.02 / (3 * Math.sqrt(0.0005)), within(1e-9));
        assertThat(s.outOfSpec()).isEqualTo(1);
    }

    @Test
    void 규격_경계는_안쪽_한쪽_규격만() {
        // 경계 값 9.95·10.05 는 규격 안(밖 = 0)
        assertThat(ChangeEffectCalc.variable(v("9.95", "10.05"), LSL, USL).outOfSpec()).isZero();
        // 상한만: Ppk = (USL − 평균)/(3σ)
        VarStats up = ChangeEffectCalc.variable(v("10.00", "10.02"), null, USL);
        assertThat(up.ppk()).isCloseTo((10.05 - 10.01) / (3 * up.sd()), within(1e-9));
        VarStats none = ChangeEffectCalc.variable(v("10.00", "10.02"), null, null);
        assertThat(none.ppk()).as("규격 없음 = 모름").isNull();
    }

    @Test
    void 모름은_0이_아니라_null() {
        VarStats empty = ChangeEffectCalc.variable(List.of(), LSL, USL);
        assertThat(empty.n()).isZero();
        assertThat(empty.mean()).isNull();
        assertThat(empty.sd()).isNull();
        assertThat(empty.ppk()).isNull();
        VarStats one = ChangeEffectCalc.variable(v("10.00"), LSL, USL);
        assertThat(one.mean()).isEqualTo(10.0);
        assertThat(one.sd()).as("n < 2 이면 σ 모름").isNull();
        assertThat(one.ppk()).isNull();
        VarStats flat = ChangeEffectCalc.variable(v("10.00", "10.00", "10.00"), LSL, USL);
        assertThat(flat.sd()).isZero();
        assertThat(flat.ppk()).as("σ = 0 이면 Ppk 를 무한대로 내지 않는다").isNull();
        assertThat(ChangeEffectCalc.attribute(0, 0).rate()).isNull();
        assertThat(ChangeEffectCalc.rate(0, 0)).isNull();
    }

    @Test
    void 계수_NG율() {
        assertThat(ChangeEffectCalc.attribute(5, 1).rate()).isEqualTo(0.2);
        assertThat(ChangeEffectCalc.attribute(5, 0).rate()).isZero();
        assertThat(ChangeEffectCalc.rate(1, 2)).isEqualTo(0.5);
    }
}
