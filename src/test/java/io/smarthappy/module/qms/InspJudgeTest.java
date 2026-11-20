package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.qms.InspJudge.LineResult;
import io.smarthappy.module.qms.InspJudge.Meas;
import io.smarthappy.module.qms.InspJudge.Spec;
import io.smarthappy.module.qms.InspJudge.Verdict;

/** 검사 판정 순수 함수 — 경계값·Ac/Re·미정·계수·한쪽 규격 (docs/29_P3 Q6-6) */
class InspJudgeTest {

    static Spec hole(int n, int ac, int re) {
        return new Spec("VARIABLE", new BigDecimal("9.95"), new BigDecimal("10.05"), n, ac, re);
    }

    static List<Meas> values(String... v) {
        List<Meas> out = new ArrayList<>();
        for (String s : v) {
            out.add(new Meas(new BigDecimal(s), null));
        }
        return out;
    }

    @Test
    void 경계값은_합격_규격_밖만_불합격() {
        Spec s = hole(3, 0, 1);
        assertThat(InspJudge.nonconforming(s, new Meas(new BigDecimal("9.95"), null))).isFalse();
        assertThat(InspJudge.nonconforming(s, new Meas(new BigDecimal("10.05"), null))).isFalse();
        assertThat(InspJudge.nonconforming(s, new Meas(new BigDecimal("9.949999"), null))).isTrue();
        assertThat(InspJudge.nonconforming(s, new Meas(new BigDecimal("10.050001"), null))).isTrue();
    }

    @Test
    void 다_재고_Ac_이하면_OK_덜_쟀으면_미정() {
        assertThat(InspJudge.line(hole(3, 0, 1), values("10.00", "10.01"))).isEqualTo(new LineResult(2, 0, Verdict.OPEN));
        assertThat(InspJudge.line(hole(3, 0, 1), values("10.00", "10.01", "9.99"))).isEqualTo(new LineResult(3, 0, Verdict.OK));
    }

    @Test
    void Re_에_닿으면_다_재지_않아도_NG() {
        assertThat(InspJudge.line(hole(5, 0, 1), values("10.10"))).isEqualTo(new LineResult(1, 1, Verdict.NG));
        // Ac 1·Re 2: 하나는 넘어가고, 다 재면 OK
        assertThat(InspJudge.line(hole(3, 1, 2), values("10.10", "10.00"))).isEqualTo(new LineResult(2, 1, Verdict.OPEN));
        assertThat(InspJudge.line(hole(3, 1, 2), values("10.10", "10.00", "10.02")).verdict()).isEqualTo(Verdict.OK);
        assertThat(InspJudge.line(hole(3, 1, 2), values("10.10", "9.90")).verdict()).isEqualTo(Verdict.NG);
    }

    @Test
    void 계수는_NG_가_불합격_한쪽_규격은_있는_쪽만() {
        Spec attr = new Spec("ATTRIBUTE", null, null, 2, 0, 1);
        assertThat(InspJudge.line(attr, List.of(new Meas(null, true), new Meas(null, true))).verdict()).isEqualTo(Verdict.OK);
        assertThat(InspJudge.line(attr, List.of(new Meas(null, false))).verdict()).isEqualTo(Verdict.NG);
        Spec minOnly = new Spec("VARIABLE", new BigDecimal("1.0"), null, 1, 0, 1);
        assertThat(InspJudge.line(minOnly, values("999")).verdict()).isEqualTo(Verdict.OK);
        assertThat(InspJudge.line(minOnly, values("0.9")).verdict()).isEqualTo(Verdict.NG);
    }

    @Test
    void 검사는_미정이_있으면_미정_NG_하나면_NG() {
        assertThat(InspJudge.head(List.of(Verdict.OK, Verdict.OK))).isEqualTo(Verdict.OK);
        assertThat(InspJudge.head(List.of(Verdict.OK, Verdict.NG))).isEqualTo(Verdict.NG);
        assertThat(InspJudge.head(List.of(Verdict.NG, Verdict.OPEN))).isEqualTo(Verdict.OPEN);
        assertThat(InspJudge.head(List.of())).isEqualTo(Verdict.OPEN);
        assertThatThrownBy(() -> new Spec("VARIABLE", null, null, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
