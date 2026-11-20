package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** P3 QMS 2부 Q10 SPC 계산 — 손 검산(docs/29_P3 12.6) */
class SpcCalcTest {

    static List<BigDecimal> g(double... v) {
        return Arrays.stream(v).mapToObj(BigDecimal::valueOf).toList();
    }

    static final double EPS = 1e-4;

    @Test
    void 같은_n_이면_교과서식_A2_D4_와_같고_공정능력도_손계산과_같다() {
        // n = 3: x̄ = 11, 10, 12, 10 / R = 2, 2, 2, 0 → X̿ = 129/12 = 10.75, R̄ = 1.5, σ̂ = R̄/d2 = 1.5/1.693 = 0.886001
        var r = SpcCalc.chart(List.of(g(10, 11, 12), g(9, 10, 11), g(11, 12, 13), g(10, 10, 10)), null, SpcCalc.DEFAULT_RULES,
                BigDecimal.valueOf(8), BigDecimal.valueOf(14), true);
        assertThat(r.limits().center()).isCloseTo(10.75, within(EPS));
        assertThat(r.limits().sigma()).isCloseTo(0.886001, within(EPS));
        var p = r.points().get(0);
        // 표 A2(3) = 1.023 → UCL = 10.75 + 1.023·1.5 = 12.2845, LCL = 9.2155 / 표 D4(3) = 2.574 → R UCL = 3.861, D3 = 0
        assertThat(p.xUcl()).isCloseTo(12.2846, within(5e-4));
        assertThat(p.xLcl()).isCloseTo(9.2154, within(5e-4));
        assertThat(p.rUcl()).isCloseTo(3.861, within(1e-3));
        assertThat(p.rLcl()).isZero();
        assertThat(p.rCl()).isCloseTo(1.5, within(EPS));
        assertThat(r.points()).allSatisfy(x -> assertThat(x.rules()).isEmpty());
        // 공정능력: Cp = 6/(6·0.886001) = 1.12867, Cpk = (10.75 − 8)/(3·0.886001) = 1.03461
        // 전체 s = √(14.25/11) = 1.138180 → Pp = 0.878595, Ppk = 2.75/(3·1.138180) = 0.805379
        var c = r.capability();
        assertThat(c.n()).isEqualTo(12);
        assertThat(c.cp()).isCloseTo(1.12867, within(EPS));
        assertThat(c.cpk()).isCloseTo(1.03461, within(EPS));
        assertThat(c.sigmaOverall()).isCloseTo(1.138180, within(EPS));
        assertThat(c.pp()).isCloseTo(0.878595, within(EPS));
        assertThat(c.ppk()).isCloseTo(0.805379, within(EPS));
    }

    @Test
    void n_이_달라도_부분군마다_한계가_다르고_범위밖_크기는_뺀다() {
        // G1 n=2 (1,3): x̄ 2, R 2, R/d2 = 2/1.128 = 1.773050 / G2 n=4 (2,2,2,2): R 0 → σ̂ = 0.886525, X̿ = 12/6 = 2
        var r = SpcCalc.chart(List.of(g(5), g(1, 3), g(2, 2, 2, 2), g(new double[26])), null, SpcCalc.DEFAULT_RULES, null, null, true);
        assertThat(r.excluded()).isEqualTo(2);
        assertThat(r.points()).extracting(SpcCalc.Point::index).containsExactly(1, 2);
        assertThat(r.limits().sigma()).isCloseTo(0.886525, within(EPS));
        assertThat(r.limits().center()).isCloseTo(2, within(EPS));
        // X̄ UCL: G1 = 2 + 3·0.886525/√2 = 3.880603, G2 = 2 + 3·0.886525/2 = 3.329787
        assertThat(r.points().get(0).xUcl()).isCloseTo(3.880603, within(EPS));
        assertThat(r.points().get(1).xUcl()).isCloseTo(3.329787, within(EPS));
        // R UCL(G1) = (d2 + 3d3)σ̂ = (1.128 + 2.559)·0.886525 = 3.268617
        assertThat(r.points().get(0).rUcl()).isCloseTo(3.268617, within(EPS));
        // 규격 없음 → 지수 없음, 값은 있음
        assertThat(r.capability().cp()).isNull();
        assertThat(r.capability().cpk()).isNull();
    }

    @Test
    void 고정_한계로_그리면_계산_한계와_별도로_규칙을_판정한다() {
        var r = SpcCalc.chart(List.of(g(10, 10.2), g(13, 14)), new SpcCalc.Limits(10, 1), SpcCalc.DEFAULT_RULES, null, null, true);
        assertThat(r.limits()).isEqualTo(new SpcCalc.Limits(10, 1));
        assertThat(r.calcLimits().center()).isCloseTo(11.8, within(EPS));
        // z = (13.5 − 10)/(1/√2) = 4.95 → WE1, R = 1 < R UCL(3.267) 정상
        assertThat(r.points().get(1).z()).isCloseTo(4.9497, within(EPS));
        assertThat(r.points().get(1).rules()).containsExactly("WE1");
        assertThat(r.points().get(0).rules()).isEmpty();
        // R 한계 밖 → R1 (고정 σ 1, n 2: R UCL = 3.687)
        var r2 = SpcCalc.chart(List.of(g(6, 10)), new SpcCalc.Limits(8, 1), SpcCalc.DEFAULT_RULES, null, null, true);
        assertThat(r2.points().get(0).rules()).containsExactly("R1");
    }

    @Test
    void 런_규칙_WE1_4_는_조건을_채운_마지막_점에_같은_쪽일_때만() {
        Set<String> all = SpcCalc.ALL_RULES;
        // WE1: 3 초과만(정확히 3 은 아님)
        assertThat(SpcCalc.runRules(new Double[] {0.0, 3.1, -3.0}, all).get(1)).contains("WE1");
        assertThat(SpcCalc.runRules(new Double[] {0.0, 3.1, -3.0}, all).get(2)).doesNotContain("WE1");
        // WE2: 3점 중 2점이 같은 쪽 2 초과, 마지막 점도 그쪽
        assertThat(SpcCalc.runRules(new Double[] {2.5, 0.0, 2.1}, all).get(2)).contains("WE2");
        assertThat(SpcCalc.runRules(new Double[] {2.5, 2.1, 0.0}, all)).allSatisfy(f -> assertThat(f).doesNotContain("WE2"));
        assertThat(SpcCalc.runRules(new Double[] {2.5, -2.5, 2.1}, all).get(2)).contains("WE2");
        assertThat(SpcCalc.runRules(new Double[] {-2.5, 2.5, -2.1}, all).get(2)).contains("WE2");
        // WE3: 5점 중 4점이 같은 쪽 1 초과
        assertThat(SpcCalc.runRules(new Double[] {1.5, 1.5, 0.0, 1.5, 1.5}, all).get(4)).contains("WE3");
        assertThat(SpcCalc.runRules(new Double[] {1.5, 0.0, 0.0, 1.5, 1.5}, all).get(4)).doesNotContain("WE3");
        // WE4: 8점 연속 같은 쪽(중심선 위 0 은 어느 쪽도 아님)
        Double[] eight = {0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1, 0.1};
        assertThat(SpcCalc.runRules(eight, all).get(7)).contains("WE4");
        assertThat(SpcCalc.runRules(eight, all).get(6)).doesNotContain("WE4");
        Double[] broken = {0.1, 0.1, 0.1, 0.0, 0.1, 0.1, 0.1, 0.1};
        assertThat(SpcCalc.runRules(broken, all).get(7)).doesNotContain("WE4");
        // 활성 규칙만: 기본(1·2)이면 WE3·WE4 는 안 나온다
        assertThat(SpcCalc.runRules(eight, SpcCalc.DEFAULT_RULES)).allSatisfy(f -> assertThat(f).isEmpty());
        // z 없음(σ̂ = 0)은 규칙 밖
        assertThat(SpcCalc.runRules(new Double[] {null, 5.0, null}, all).get(1)).contains("WE1");
    }

    @Test
    void 변동_0_단측_규격_규격_섞임() {
        // 모든 값이 같음 → σ̂ = 0: z·지수 없음, 규칙 없음
        var r = SpcCalc.chart(List.of(g(5, 5), g(5, 5)), null, SpcCalc.ALL_RULES, BigDecimal.ONE, BigDecimal.TEN, true);
        assertThat(r.points()).allSatisfy(p -> {
            assertThat(p.z()).isNull();
            assertThat(p.rules()).isEmpty();
        });
        assertThat(r.capability().cp()).isNull();
        assertThat(r.capability().ppk()).isNull();
        // 상한만: Cp·Pp 없음, Cpk = (14 − 10.75)/(3·0.886001) = 1.222722
        var one = SpcCalc.chart(List.of(g(10, 11, 12), g(9, 10, 11), g(11, 12, 13), g(10, 10, 10)), null, SpcCalc.DEFAULT_RULES, null,
                BigDecimal.valueOf(14), true);
        assertThat(one.capability().cp()).isNull();
        assertThat(one.capability().pp()).isNull();
        assertThat(one.capability().cpk()).isCloseTo(1.222722, within(EPS));
        // 규격이 섞이면 공정능력 없음
        var mixed = SpcCalc.chart(List.of(g(10, 11)), null, SpcCalc.DEFAULT_RULES, null, BigDecimal.valueOf(14), false);
        assertThat(mixed.capability()).isNull();
        // 쓸 수 있는 부분군 없음
        var none = SpcCalc.chart(List.of(g(1)), null, SpcCalc.DEFAULT_RULES, null, null, true);
        assertThat(none.points()).isEmpty();
        assertThat(none.calcLimits()).isNull();
        assertThat(none.excluded()).isEqualTo(1);
    }

    /**
     * p 관리도 손 검산(docs/29_P3 28장) — 부분군 {n, d}: (50,2)·(50,3)·(100,4)·(25,6) → p̄ = 15/225 = 0.0666667, √(p̄(1-p̄)) = 0.2494438.
     * n=50: 3σ/√n = 0.1058301 → UCL 0.1724968, LCL 0(음수 자름) · n=100: UCL 0.1414998 · n=25: UCL 0.2163330, 4번째 p = 0.24 > UCL → WE1,
     * z = (0.24 - 0.0666667)/0.0498888 = 3.4744
     */
    @Test
    void p_관리도는_부분군마다_한계가_다르고_손계산과_같다() {
        SpcCalc.PResult r = SpcCalc.pChart(List.of(new int[] {50, 2}, new int[] {50, 3}, new int[] {100, 4}, new int[] {25, 6}), null, Set.of("WE1"));
        assertThat(r.calcPbar()).isCloseTo(15.0 / 225, within(1e-12));
        assertThat(r.pbar()).isEqualTo(r.calcPbar());
        assertThat(SpcCalc.pSigma(r.pbar())).isCloseTo(0.2494438, within(1e-6));
        assertThat(r.points()).hasSize(4);
        SpcCalc.PPoint a = r.points().get(0);
        assertThat(a.p()).isCloseTo(0.04, within(1e-12));
        assertThat(a.ucl()).isCloseTo(0.1724968, within(1e-6));
        assertThat(a.lcl()).isZero();
        assertThat(r.points().get(2).ucl()).isCloseTo(0.1414998, within(1e-6));
        SpcCalc.PPoint d = r.points().get(3);
        assertThat(d.ucl()).isCloseTo(0.2163330, within(1e-6));
        assertThat(d.z()).isCloseTo(3.4744, within(1e-4));
        assertThat(d.rules()).containsExactly("WE1");
        assertThat(r.points().subList(0, 3)).allMatch(x -> x.rules().isEmpty());
    }

    /** 고정 p̄ 로 그림 · n = 0 부분군은 뺌 · 규칙은 WE1·WE4 만(WE2·WE3 를 줘도 안 봄) · WE4 = 중심 같은 쪽 8점 · p̄ 0 이면 z 없음 */
    @Test
    void p_관리도_고정_한계_n0_제외_규칙은_WE1_WE4_만() {
        java.util.List<int[]> g = new java.util.ArrayList<>();
        g.add(new int[] {0, 0});
        for (int i = 0; i < 8; i++) {
            g.add(new int[] {100, 8});    // p = 0.08 > 고정 p̄ 0.05, 한계(n=100: 0.05 + 3·0.0217945 = 0.1153836) 안
        }
        SpcCalc.PResult r = SpcCalc.pChart(g, 0.05, Set.of("WE1", "WE2", "WE3", "WE4"));
        assertThat(r.excluded()).isEqualTo(1);
        assertThat(r.pbar()).isEqualTo(0.05);
        assertThat(r.calcPbar()).isCloseTo(0.08, within(1e-12));
        assertThat(r.points()).hasSize(8);
        assertThat(r.points().get(0).index()).isEqualTo(1);
        assertThat(r.points().get(0).ucl()).isCloseTo(0.1153836, within(1e-6));
        // z = (0.08-0.05)/0.0217945 = 1.3765 — WE2(2σ)·WE3(1σ 4/5) 는 조건이 되지만 p 에선 안 봄, 8번째 점에 WE4
        assertThat(r.points().subList(0, 7)).allMatch(x -> x.rules().isEmpty());
        assertThat(r.points().get(7).rules()).containsExactly("WE4");
        // p̄ = 0(불량 없음) → σ 0 → z 없음, 규칙 없음, 한계 0
        SpcCalc.PResult zero = SpcCalc.pChart(List.of(new int[] {10, 0}, new int[] {20, 0}), null, Set.of("WE1"));
        assertThat(zero.calcPbar()).isZero();
        assertThat(zero.points()).allMatch(x -> x.z() == null && x.rules().isEmpty() && x.ucl() == 0);
    }

}
