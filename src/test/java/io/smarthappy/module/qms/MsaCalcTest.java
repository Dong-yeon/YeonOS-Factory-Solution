package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.qms.MsaCalc.AnovaResult;
import io.smarthappy.module.qms.MsaCalc.AttributeResult;
import io.smarthappy.module.qms.MsaCalc.MsaException;
import io.smarthappy.module.qms.MsaCalc.VariableResult;
import io.smarthappy.module.qms.MsaCalc.Verdict;

/**
 * GRR 손 검산 (P3 QMS Q2b, docs/29 D9). 예제 1 은 손으로 푼 값(아래 주석), 예제 2·3 은 정의대로 따로 짠 계산(scratch 스크립트)과 대조.
 * 표준 원문의 예제 수치는 쓰지 않는다(직접 만든 데이터).
 */
class MsaCalcTest {

    /**
     * 예제 1 (2 측정자 × 5 부품 × 2 반복) 손 계산:
     * 범위 A = .1 .1 0 .2 .1 → R̄a = .1, B = .1 .1 .2 0 .1 → R̄b = .1, R̿ = .1, EV = .1 × .8862 = .08862
     * 평균 A = 30.1/10 = 3.01, B = 30.7/10 = 3.07 → X̄diff = .06, AV = √((.06 × .7071)² − .08862²/10) = √(.00179992 − .00078535) = .031853
     * GRR = √(.08862² + .031853²) = .094171, 부품 평균 2.1 … 4.1 → Rp = 2.0, PV = 2.0 × .4030 = .806, TV = √(GRR² + PV²) = .811483
     * %GRR = 11.60, ndc = ⌊1.41 × .806 / .094171⌋ = ⌊12.07⌋ = 12
     */
    static final double[][][] EX1 = {
            {{2.0, 2.1}, {3.0, 2.9}, {2.5, 2.5}, {4.0, 4.2}, {3.5, 3.4}},
            {{2.1, 2.2}, {3.1, 3.0}, {2.6, 2.4}, {4.1, 4.1}, {3.6, 3.5}}};

    /** 예제 2 (3 × 10 × 3) */
    static final double[][][] EX2 = {
            {{0.7, 0.65, 0.75}, {0.95, 0.95, 0.9}, {0.8, 0.9, 0.75}, {0.8, 0.75, 0.85}, {0.5, 0.5, 0.65}, {1.1, 0.95, 1.0},
                    {0.9, 0.85, 1.05}, {0.8, 0.75, 0.8}, {1.0, 0.9, 0.95}, {0.5, 0.5, 0.7}},
            {{0.54, 0.59, 0.54}, {0.84, 0.94, 0.99}, {0.89, 0.79, 0.69}, {0.74, 0.69, 0.84}, {0.39, 0.49, 0.44}, {0.84, 0.84, 0.94},
                    {0.94, 0.84, 0.79}, {0.74, 0.69, 0.74}, {0.84, 0.94, 1.04}, {0.44, 0.64, 0.59}},
            {{0.78, 0.58, 0.78}, {1.08, 1.08, 1.03}, {0.88, 0.88, 0.83}, {0.78, 0.93, 0.78}, {0.68, 0.63, 0.68}, {1.08, 0.93, 0.98},
                    {0.93, 0.88, 1.08}, {0.88, 0.93, 0.88}, {1.13, 1.13, 0.98}, {0.58, 0.53, 0.53}}};

    static void close(BigDecimal actual, double expected) {
        assertThat(actual.doubleValue()).isCloseTo(expected, org.assertj.core.data.Offset.offset(0.000002));
    }

    static void closePct(BigDecimal actual, double expected) {
        assertThat(actual.doubleValue()).isCloseTo(expected, org.assertj.core.data.Offset.offset(0.0051));
    }

    @Test
    void 예제1_공정변동_기준_손계산과_일치() {
        VariableResult v = MsaCalc.averageRange(EX1, "PROCESS", null, null, 10, 30);
        close(v.rbar(), 0.1);
        close(v.xdiff(), 0.06);
        close(v.rp(), 2.0);
        close(v.ev(), 0.08862);
        close(v.av(), 0.031853);
        close(v.grr(), 0.094171);
        close(v.pv(), 0.806);
        close(v.tv(), 0.811483);
        closePct(v.pctEv(), 10.920751);
        closePct(v.pctAv(), 3.925287);
        closePct(v.pctGrr(), 11.60477);
        closePct(v.pctPv(), 99.324364);
        assertThat(v.ndc()).isEqualTo(12);
        assertThat(v.verdict()).isEqualTo(Verdict.CONDITIONAL);
        assertThat(v.appraiserAvg()).extracting(BigDecimal::doubleValue).containsExactly(3.01, 3.07);
        assertThat(v.partAvg().get(0).doubleValue()).isEqualTo(2.1);
        assertThat(v.tolerance()).isNull();
    }

    @Test
    void 예제1_공차_기준은_공차의_6분의1_로_나눈다() {
        VariableResult v = MsaCalc.averageRange(EX1, "TOLERANCE", new BigDecimal("4.0"), null, 10, 30);
        closePct(v.pctGrr(), 14.125604);
        closePct(v.pctEv(), 13.293);
        closePct(v.pctPv(), 120.9);
        assertThat(v.ndc()).isEqualTo(12);
        assertThat(v.verdict()).isEqualTo(Verdict.CONDITIONAL);
        // 판정 구간 경계: %GRR ≤ ok 면 합격, ≤ limit 면 조건부
        assertThat(MsaCalc.averageRange(EX1, "TOLERANCE", new BigDecimal("4.0"), null, 15, 30).verdict()).isEqualTo(Verdict.ACCEPT);
        assertThat(MsaCalc.averageRange(EX1, "TOLERANCE", new BigDecimal("4.0"), null, 5, 14).verdict()).isEqualTo(Verdict.REJECT);
    }

    @Test
    void 예제2_3x10x3_과_공정_시그마() {
        VariableResult v = MsaCalc.averageRange(EX2, "PROCESS", null, null, 10, 30);
        close(v.rbar(), 0.123333);
        close(v.xdiff(), 0.12);
        close(v.rp(), 0.438889);
        close(v.ev(), 0.072865);
        close(v.av(), 0.061346);
        close(v.grr(), 0.095251);
        close(v.pv(), 0.138074);
        close(v.tv(), 0.167742);
        closePct(v.pctGrr(), 56.784195);
        assertThat(v.ndc()).isEqualTo(2);
        assertThat(v.verdict()).isEqualTo(Verdict.REJECT);
        // 공정 σ 를 주면 TV = σ, PV = √(TV² − GRR²)
        // 공정 기준에 공차를 넘겨도 쓰지 않는다
        assertThat(MsaCalc.averageRange(EX2, "PROCESS", new BigDecimal("0.2"), null, 10, 30).tolerance()).isNull();
        VariableResult s2 = MsaCalc.averageRange(EX2, "PROCESS", null, new BigDecimal("0.2"), 10, 30);
        close(s2.tv(), 0.2);
        close(s2.pv(), 0.175862);
        closePct(s2.pctGrr(), 47.625367);
        assertThat(s2.processSigma()).isEqualByComparingTo("0.2");
        // σ 가 GRR 보다 작으면 PV = 0, ndc 최소 1
        VariableResult tiny = MsaCalc.averageRange(EX2, "PROCESS", null, new BigDecimal("0.05"), 10, 30);
        close(tiny.pv(), 0);
        assertThat(tiny.ndc()).isEqualTo(1);
    }

    @Test
    void 예제3_측정자_평균이_같으면_AV는_0() {
        double[][][] ex3 = {{{1.0, 1.2}, {2.0, 2.2}}, {{1.2, 1.0}, {2.2, 2.0}}};
        VariableResult v = MsaCalc.averageRange(ex3, "PROCESS", null, null, 10, 30);
        close(v.av(), 0);
        close(v.grr(), 0.17724);
        close(v.pv(), 0.7071);
        closePct(v.pctGrr(), 24.313594);
        assertThat(v.ndc()).isEqualTo(5);
    }

    @Test
    void 모양_공차_시그마_변동없음_오류() {
        assertThatThrownBy(() -> MsaCalc.averageRange(new double[][][] {{{1, 2}}}, "PROCESS", null, null, 10, 30))
                .isInstanceOf(MsaException.class).hasMessage("MSA_SHAPE");
        double[][][] ragged = {{{1, 2}, {1, 2}}, {{1, 2}, {1, 2, 3}}};
        assertThatThrownBy(() -> MsaCalc.averageRange(ragged, "PROCESS", null, null, 10, 30)).hasMessage("MSA_SHAPE");
        double[][][] fourTrials = {{{1, 2, 3, 4}, {1, 2, 3, 4}}, {{1, 2, 3, 4}, {1, 2, 3, 4}}};
        assertThatThrownBy(() -> MsaCalc.averageRange(fourTrials, "PROCESS", null, null, 10, 30)).hasMessage("MSA_SHAPE");
        assertThatThrownBy(() -> MsaCalc.averageRange(EX1, "TOLERANCE", null, null, 10, 30)).hasMessage("MSA_NO_TOLERANCE");
        assertThatThrownBy(() -> MsaCalc.averageRange(EX1, "TOLERANCE", BigDecimal.ZERO, null, 10, 30)).hasMessage("MSA_NO_TOLERANCE");
        assertThatThrownBy(() -> MsaCalc.averageRange(EX1, "PROCESS", null, new BigDecimal("-1"), 10, 30)).hasMessage("MSA_SIGMA");
        double[][][] flat = {{{1, 1}, {1, 1}}, {{1, 1}, {1, 1}}};
        assertThatThrownBy(() -> MsaCalc.averageRange(flat, "PROCESS", null, null, 10, 30)).hasMessage("MSA_NO_VARIATION");
    }

    @Test
    void 계수형_일치율_측정자안_측정자간_기준대비() {
        // 측정자 2 × 부품 4 × 반복 2. 부품 3 은 A 가 흔들림, 부품 4 는 둘이 다르게 일관
        String[][][] d = {
                {{"OK", "OK"}, {"NG", "NG"}, {"OK", "NG"}, {"OK", "OK"}},
                {{"OK", "OK"}, {"NG", "NG"}, {"OK", "OK"}, {"NG", "NG"}}};
        String[] ref = {"OK", "NG", "OK", "NG"};
        AttributeResult a = MsaCalc.attribute(d, ref);
        assertThat(a.withinPct()).extracting(BigDecimal::doubleValue).containsExactly(75.0, 100.0);
        assertThat(a.betweenPct().doubleValue()).isEqualTo(50.0);
        assertThat(a.vsRefPct()).extracting(BigDecimal::doubleValue).containsExactly(50.0, 100.0);
        assertThat(a.allVsRefPct().doubleValue()).isEqualTo(50.0);
        AttributeResult noRef = MsaCalc.attribute(d, null);
        assertThat(noRef.vsRefPct()).isNull();
        assertThat(noRef.allVsRefPct()).isNull();
        assertThatThrownBy(() -> MsaCalc.attribute(d, new String[] {"OK"})).hasMessage("MSA_SHAPE");
    }

    // ---------- ANOVA (D9 2차, docs/29_P3 45장) — 기대값은 정의대로 따로 짠 계산(분산분석 손계산 + F 꼬리 확률은 scipy.stats.f.sf)과 대조 ----------

    /** 예제 3 (2 × 3 × 2) — 측정자 B 가 부품 1·2 를 반대로 읽어 상호작용이 크다 */
    static final double[][][] EX3_INT = {{{1.0, 1.1}, {2.0, 2.1}, {3.0, 2.9}}, {{1.5, 1.4}, {1.6, 1.7}, {3.4, 3.5}}};

    @Test
    void ANOVA_예제1_상호작용_풀링_분산성분과_표() {
        AnovaResult r = MsaCalc.anova(EX1, "PROCESS", null, null, 10, 30);
        assertThat(r.pooled()).isTrue();                          // F(4,10) = .428571, p = .785047 > .25
        close(r.table().get(2).f(), 0.428571);
        close(r.table().get(2).p(), 0.785047);
        close(r.table().get(0).ss(), 10.048);                     // 부품
        close(r.table().get(1).ss(), 0.018);                      // 측정자
        close(r.table().get(2).ss(), 0.012);                      // 상호작용
        close(r.table().get(3).ss(), 0.07);                       // 반복
        close(r.table().get(4).ss(), 10.148);                     // 합계
        assertThat(r.table()).extracting(MsaCalc.AnovaRow::df).containsExactly(4, 1, 4, 10, 19);
        close(r.table().get(1).f(), 3.073171);                    // 풀링 뒤 분모 = (SSint + SSe)/(4 + 10)
        close(r.table().get(1).p(), 0.101453);
        close(r.core().ev(), 0.076532);
        close(r.core().av(), 0.034847);
        close(r.iv(), 0);
        close(r.core().grr(), 0.084092);
        close(r.core().pv(), 0.79154);
        close(r.core().tv(), 0.795994);
        closePct(r.core().pctGrr(), 10.564369);
        closePct(r.core().pctEv(), 9.614637);
        assertThat(r.core().ndc()).isEqualTo(13);
        assertThat(r.core().verdict()).isEqualTo(Verdict.CONDITIONAL);
        // 기술 통계는 평균·범위법과 같은 원자료 값
        close(r.core().rbar(), 0.1);
        assertThat(r.core().appraiserAvg()).extracting(BigDecimal::doubleValue).containsExactly(3.01, 3.07);
        // 공차 기준
        closePct(MsaCalc.anova(EX1, "TOLERANCE", new BigDecimal("4.0"), null, 10, 30).core().pctGrr(), 12.613768);
    }

    @Test
    void ANOVA_예제2_3x10x3_과_공정_시그마() {
        AnovaResult r = MsaCalc.anova(EX2, "PROCESS", null, null, 10, 30);
        assertThat(r.pooled()).isTrue();                          // p = .468414
        close(r.table().get(2).p(), 0.468414);
        close(r.core().ev(), 0.072491);
        close(r.core().av(), 0.058554);
        close(r.core().grr(), 0.093185);
        close(r.core().pv(), 0.164407);
        close(r.core().tv(), 0.188979);
        closePct(r.core().pctGrr(), 49.309877);
        assertThat(r.core().ndc()).isEqualTo(2);
        assertThat(r.core().verdict()).isEqualTo(Verdict.REJECT);
        AnovaResult s = MsaCalc.anova(EX2, "PROCESS", null, new BigDecimal("0.2"), 10, 30);
        close(s.core().tv(), 0.2);
        close(s.core().pv(), 0.176965);
        closePct(s.core().pctGrr(), 46.592712);
    }

    @Test
    void ANOVA_예제3_상호작용이_유의하면_따로_센다() {
        AnovaResult r = MsaCalc.anova(EX3_INT, "PROCESS", null, null, 10, 30);
        assertThat(r.pooled()).isFalse();                         // F(2,6) = 48.6667, p = .000196
        close(r.table().get(2).f(), 48.666667);
        close(r.table().get(2).p(), 0.000196);
        close(r.table().get(1).f(), 0.342466);                    // 측정자 F 의 분모 = MS상호작용, df (1, 2)
        close(r.table().get(1).p(), 0.61764);
        close(r.table().get(0).p(), 0.05748);                     // 부품 F(2, 2) = 16.3973
        close(r.core().ev(), 0.070711);
        close(r.core().av(), 0);                                  // MS측정자 < MS상호작용 → 음수 → 0
        close(r.iv(), 0.345205);
        close(r.core().grr(), 0.352373);
        close(r.core().pv(), 0.967815);
        closePct(r.pctIv(), 33.516126);
        closePct(r.core().pctGrr(), 34.212037);
        assertThat(r.core().ndc()).isEqualTo(3);
        assertThat(r.core().verdict()).isEqualTo(Verdict.REJECT);
    }

    @Test
    void ANOVA_반복_오차_0_이면_F_는_비우고_p_는_0_또는_1() {
        // 반복이 모두 같다 — 상호작용 있음 → F 무한대(빈칸)·p 0·따로 셈
        double[][][] noErr = {{{1, 1}, {2, 2}}, {{1.5, 1.5}, {2, 2}}};
        AnovaResult r = MsaCalc.anova(noErr, "PROCESS", null, null, 10, 30);
        assertThat(r.table().get(2).f()).isNull();
        close(r.table().get(2).p(), 0);
        assertThat(r.pooled()).isFalse();
        close(r.core().ev(), 0);
        // 반복·상호작용 모두 0 → F 0, p 1, 풀링
        double[][][] clean = {{{1, 1}, {2, 2}}, {{1.5, 1.5}, {2.5, 2.5}}};
        AnovaResult c = MsaCalc.anova(clean, "PROCESS", null, null, 10, 30);
        assertThat(c.pooled()).isTrue();
        close(c.table().get(2).p(), 1);
        // 모양·기준·변동 없음 오류는 평균·범위법과 같은 코드
        assertThatThrownBy(() -> MsaCalc.anova(new double[][][] {{{1, 2}}}, "PROCESS", null, null, 10, 30)).hasMessage("MSA_SHAPE");
        assertThatThrownBy(() -> MsaCalc.anova(EX1, "TOLERANCE", null, null, 10, 30)).hasMessage("MSA_NO_TOLERANCE");
        assertThatThrownBy(() -> MsaCalc.anova(new double[][][] {{{1, 1}, {1, 1}}, {{1, 1}, {1, 1}}}, "PROCESS", null, null, 10, 30))
                .hasMessage("MSA_NO_VARIATION");
    }

    @Test
    void 같은_연구를_두_방법으로_나란히_기술통계는_같고_상호작용이_있으면_ANOVA_가_더_크다() {
        // 예제 1 — 상호작용 없음: 기술 통계는 같고 GRR 은 비슷(평균·범위 .094171 / ANOVA .084092), 판정 같음
        VariableResult ar = MsaCalc.averageRange(EX1, "PROCESS", null, null, 10, 30);
        AnovaResult an = MsaCalc.anova(EX1, "PROCESS", null, null, 10, 30);
        assertThat(an.core().rbar()).isEqualByComparingTo(ar.rbar());
        assertThat(an.core().xdiff()).isEqualByComparingTo(ar.xdiff());
        assertThat(an.core().appraiserAvg()).isEqualTo(ar.appraiserAvg());
        assertThat(an.core().partAvg()).isEqualTo(ar.partAvg());
        assertThat(an.core().verdict()).isEqualTo(ar.verdict());
        assertThat(Math.abs(an.core().pctGrr().doubleValue() - ar.pctGrr().doubleValue())).isLessThan(1.5);
        // 예제 3 — 측정자마다 부품을 다르게 읽음: 평균·범위법은 측정자 평균 차와 반복 범위만 봐 상호작용을 놓친다
        VariableResult ar3 = MsaCalc.averageRange(EX3_INT, "PROCESS", null, null, 10, 30);
        AnovaResult an3 = MsaCalc.anova(EX3_INT, "PROCESS", null, null, 10, 30);
        assertThat(an3.core().grr()).isGreaterThan(ar3.grr());
        assertThat(an3.core().pctGrr().doubleValue()).isGreaterThan(30);   // ANOVA = 불합격
        assertThat(an3.core().verdict()).isEqualTo(Verdict.REJECT);
        assertThat(ar3.verdict()).isNotEqualTo(Verdict.REJECT);            // 평균·범위법은 같은 원자료를 덜 나쁘게 본다
    }

    @Test
    void F_꼬리_확률은_scipy_값과_일치() {
        assertThat(MsaCalc.fSurvival(0.4285714285715135, 4, 10)).isCloseTo(0.7850465875701875, org.assertj.core.data.Offset.offset(1e-10));
        assertThat(MsaCalc.fSurvival(48.66666666666643, 2, 6)).isCloseTo(0.00019576382128831123, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(MsaCalc.fSurvival(3.0731707317071826, 1, 14)).isCloseTo(0.10145285477405887, org.assertj.core.data.Offset.offset(1e-10));
        assertThat(MsaCalc.fSurvival(428.87804878047444, 4, 14)).isCloseTo(1.8089802139005003e-14, org.assertj.core.data.Offset.offset(1e-16));
        assertThat(MsaCalc.fSurvival(16.39726027397267, 2, 2)).isCloseTo(0.0574803149606297, org.assertj.core.data.Offset.offset(1e-10));
        assertThat(MsaCalc.fSurvival(0, 3, 5)).isEqualTo(1);
        assertThat(MsaCalc.fSurvival(Double.POSITIVE_INFINITY, 3, 5)).isEqualTo(0);
    }
}
