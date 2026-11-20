package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.smarthappy.module.qms.SamplingPlan.Sample;
import io.smarthappy.module.qms.SamplingPlan.SamplingException;
import io.smarthappy.module.qms.SamplingPlan.Verdict;

/**
 * 샘플링 판정 순수 함수 (docs/29 Q1 "AQL 판정 함수 손 검산") — 표 기반. AQL 은 손으로 만든 **가짜 작은 표**(D3: 표준 원문을 쓰지 않는다).
 *
 * 가짜 표 FAKE: 수준 II = 1~8 → A, 9~50 → B, 51~500 → C, 501~ → D / 수준 S1 = 1~1000 → A, 1001~ → B
 *              보통 1.0: A n2 0/1, B n8 0/1, C n32 1/2, D n80 2/3 · 까다로운 1.0: C n32 0/1 · 수월한 1.0: C n13 0/2(간격)
 */
class SamplingPlanTest {

    static final AqlTable FAKE = new AqlTable("FAKE",
            List.of(new AqlTable.Letter("II", 1, 8L, "A"), new AqlTable.Letter("II", 9, 50L, "B"),
                    new AqlTable.Letter("II", 51, 500L, "C"), new AqlTable.Letter("II", 501, null, "D"),
                    new AqlTable.Letter("S1", 1, 1000L, "A"), new AqlTable.Letter("S1", 1001, null, "B")),
            List.of(new AqlTable.PlanRow("NORMAL", "A", new BigDecimal("1.0"), 2, 0, 1),
                    new AqlTable.PlanRow("NORMAL", "B", new BigDecimal("1.0"), 8, 0, 1),
                    new AqlTable.PlanRow("NORMAL", "C", new BigDecimal("1.0"), 32, 1, 2),
                    new AqlTable.PlanRow("NORMAL", "D", new BigDecimal("1.0"), 80, 2, 3),
                    new AqlTable.PlanRow("TIGHTENED", "C", new BigDecimal("1.0"), 32, 0, 1),
                    new AqlTable.PlanRow("REDUCED", "C", new BigDecimal("1.0"), 13, 0, 2)));

    /** 로트 → 시료 문자·n·Ac·Re·전수 — 구간 경계마다 손으로 셈 */
    @ParameterizedTest(name = "AQL II 보통 1.0, 로트 {0} → {1} n={2} Ac{3} Re{4} 전수={5}")
    @CsvSource({
            "1, A, 1, 0, 1, true",          // n 2 ≥ 로트 1 → 전수
            "2, A, 2, 0, 1, true",          // n = 로트 → 전수
            "3, A, 2, 0, 1, false",
            "8, A, 2, 0, 1, false",         // 구간 끝(포함)
            "9, B, 8, 0, 1, false",         // 다음 구간 시작
            "50, B, 8, 0, 1, false",
            "51, C, 32, 1, 2, false",
            "500, C, 32, 1, 2, false",
            "501, D, 80, 2, 3, false",
            "1000000000, D, 80, 2, 3, false"})   // 상한 로트, 끝 없는 구간
    void AQL_로트_구간별_시료(long lot, String letter, int n, int ac, int re, boolean all) {
        Sample s = SamplingPlan.aql(FAKE, "II", "NORMAL", new BigDecimal("1.0")).sampleSize(lot);
        assertThat(s.codeLetter()).isEqualTo(letter);
        assertThat(s.sampleSize()).isEqualTo(n);
        assertThat(s.ac()).isEqualTo(ac);
        assertThat(s.re()).isEqualTo(re);
        assertThat(s.all()).isEqualTo(all);
        assertThat(s.lotQty()).isEqualTo(lot);
    }

    @ParameterizedTest(name = "엄격도 {0} 로트 200 불량 {1} → {2}")
    @CsvSource({
            "NORMAL, 0, ACCEPT", "NORMAL, 1, ACCEPT", "NORMAL, 2, REJECT", "NORMAL, 32, REJECT",
            "TIGHTENED, 0, ACCEPT", "TIGHTENED, 1, REJECT",
            "REDUCED, 0, ACCEPT", "REDUCED, 1, ACCEPT_TO_NORMAL", "REDUCED, 2, REJECT", "REDUCED, 13, REJECT"})
    void AQL_판정_Ac_이하_합격_Re_이상_불합격_수월한_간격은_복귀(String severity, int defects, Verdict v) {
        assertThat(SamplingPlan.aql(FAKE, "II", severity, new BigDecimal("1.0")).judge(200, defects)).isEqualTo(v);
    }

    @Test
    void AQL_수치는_자릿수와_관계없이_같다_1_0_과_1_000() {
        assertThat(SamplingPlan.aql(FAKE, "II", "NORMAL", new BigDecimal("1.000")).sampleSize(100).sampleSize()).isEqualTo(32);
    }

    @Test
    void AQL_특별_수준_S1_은_별도_구간() {
        assertThat(SamplingPlan.aql(FAKE, "S1", "NORMAL", BigDecimal.ONE).sampleSize(1000).codeLetter()).isEqualTo("A");
        assertThat(SamplingPlan.aql(FAKE, "S1", "NORMAL", BigDecimal.ONE).sampleSize(1001).sampleSize()).isEqualTo(8);
    }

    @ParameterizedTest(name = "표에 없음: 수준 {0} 엄격도 {1} AQL {2} 로트 {3} → {4}")
    @CsvSource({
            "III, NORMAL, 1.0, 100, NO_AQL_LETTER",       // 표 1 에 없는 수준
            "II, NORMAL, 0.65, 100, NO_AQL_PLAN",         // 표 2 에 없는 AQL
            "II, TIGHTENED, 1.0, 5, NO_AQL_PLAN",         // 까다로운 A 줄 없음
            "S1, REDUCED, 1.0, 5, NO_AQL_PLAN"})
    void AQL_표에_없는_줄은_오류_코드(String level, String severity, String aql, long lot, String code) {
        SamplingPlan p = SamplingPlan.aql(FAKE, level, severity, new BigDecimal(aql));
        assertThatThrownBy(() -> p.sampleSize(lot)).isInstanceOf(SamplingException.class)
                .extracting(e -> ((SamplingException) e).code()).isEqualTo(code);
    }

    @Test
    void 빈_표면_시료_문자_없음() {
        SamplingPlan p = SamplingPlan.aql(new AqlTable("EMPTY", null, null), "II", "NORMAL", BigDecimal.ONE);
        assertThatThrownBy(() -> p.sampleSize(10)).extracting(e -> ((SamplingException) e).code()).isEqualTo("NO_AQL_LETTER");
    }

    @ParameterizedTest(name = "고정 n={0} Ac{1}, 로트 {2} → n={3} 전수={4}")
    @CsvSource({"5, 0, 100, 5, false", "5, 0, 6, 5, false", "5, 0, 5, 5, true", "5, 0, 3, 3, true", "5, 2, 1, 1, true"})
    void 고정_수량은_로트보다_크면_로트_전체(int n, int ac, long lot, int expect, boolean all) {
        Sample s = SamplingPlan.fixed(n, ac).sampleSize(lot);
        assertThat(s.sampleSize()).isEqualTo(expect);
        assertThat(s.all()).isEqualTo(all);
        assertThat(s.re()).isEqualTo(ac + 1);
    }

    @ParameterizedTest(name = "비율 {0}%, 로트 {1} → n={2}")
    @CsvSource({
            "10, 1, 1", "10, 9, 1", "10, 10, 1", "10, 11, 2", "10, 1000, 100", "10, 1001, 101",
            "100, 7, 7", "0.01, 1000000000, 100000", "33.33, 3, 1", "33.34, 3, 2", "50, 1, 1"})
    void 비율은_올림_최소1_최대_로트(String pct, long lot, int n) {
        assertThat(SamplingPlan.percent(new BigDecimal(pct), 0).sampleSize(lot).sampleSize()).isEqualTo(n);
    }

    @Test
    void 전수는_로트_전체_판정은_Ac() {
        Sample s = SamplingPlan.all(0).sampleSize(7);
        assertThat(s.sampleSize()).isEqualTo(7);
        assertThat(s.all()).isTrue();
        assertThat(s.judge(0)).isEqualTo(Verdict.ACCEPT);
        assertThat(s.judge(1)).isEqualTo(Verdict.REJECT);
        assertThat(SamplingPlan.all(2).judge(10, 2)).isEqualTo(Verdict.ACCEPT);
        assertThat(SamplingPlan.all(2).judge(10, 3)).isEqualTo(Verdict.REJECT);
    }

    @ParameterizedTest(name = "로트 {0} 는 LOT_QTY")
    @CsvSource({"0", "-1", "1000000001", "9223372036854775807"})
    void 로트_범위_밖(long lot) {
        assertThatThrownBy(() -> SamplingPlan.fixed(5, 0).sampleSize(lot)).extracting(e -> ((SamplingException) e).code())
                .isEqualTo("LOT_QTY");
        assertThatThrownBy(() -> SamplingPlan.all(0).sampleSize(lot)).isInstanceOf(SamplingException.class);
    }

    @ParameterizedTest(name = "시료 5개에 불량 {0} 은 DEFECTS")
    @CsvSource({"-1", "6", "2147483647"})
    void 불량_수는_0_이상_시료_이하(int defects) {
        Sample s = SamplingPlan.fixed(5, 0).sampleSize(100);
        assertThatThrownBy(() -> s.judge(defects)).extracting(e -> ((SamplingException) e).code()).isEqualTo("DEFECTS");
    }

    @Test
    void 저장값으로_만들기_of_와_모르는_방식() {
        assertThat(SamplingPlan.of("FIXED", 5, null, 0, null, null, null, null).method()).isEqualTo("FIXED");
        assertThat(SamplingPlan.of("AQL", null, null, null, "II", "NORMAL", BigDecimal.ONE, FAKE).sampleSize(9).codeLetter())
                .isEqualTo("B");
        assertThatThrownBy(() -> SamplingPlan.of("SKIP", null, null, 0, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
