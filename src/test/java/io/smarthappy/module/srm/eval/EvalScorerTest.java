package io.smarthappy.module.srm.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** EvalScorer 손 검산 (docs/31 S6) — DB 없이 */
class EvalScorerTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static final List<EvalScorer.Item> ITEMS = List.of(
            new EvalScorer.Item(1, d("10"), d("40"), d("5")),      // 품질 10점 만점, 가중 40, 5 미만 과락
            new EvalScorer.Item(2, d("5"), d("35"), null),         // 납기 5점 만점, 가중 35
            new EvalScorer.Item(3, d("3"), d("25"), null));        // 3점 만점(1/3 이 나누어떨어지지 않는다)

    private static final List<EvalScorer.Grade> GRADES = List.of(
            new EvalScorer.Grade("A", d("90"), false), new EvalScorer.Grade("B", d("80"), false),
            new EvalScorer.Grade("C", d("60"), false), new EvalScorer.Grade("D", d("0"), true));

    private static Map<Long, BigDecimal> s(String a, String b, String c) {
        Map<Long, BigDecimal> m = new HashMap<>();
        if (a != null) {
            m.put(1L, d(a));
        }
        if (b != null) {
            m.put(2L, d(b));
        }
        if (c != null) {
            m.put(3L, d(c));
        }
        return m;
    }

    @Test
    void 총점은_점수_나누기_배점_곱하기_가중치의_합을_2자리_반올림() {
        // 7/10×40 = 28, 2.5/5×35 = 17.5, 2/3×25 = 16.666… → 62.1666… → 62.17, C
        EvalScorer.Result r = EvalScorer.score(ITEMS, s("7", "2.5", "2"), GRADES);
        assertThat(r.total()).isEqualByComparingTo("62.17");
        assertThat(r.gradeCd()).isEqualTo("C");
        assertThat(r.fail()).isFalse();
        assertThat(r.suggestHold()).isFalse();
        assertThat(r.complete()).isTrue();
        // 만점 = 100.00, A
        assertThat(EvalScorer.score(ITEMS, s("10", "5", "3"), GRADES).total()).isEqualByComparingTo("100");
        // 0 점 = 0, D(HOLD 제안)
        EvalScorer.Result zero = EvalScorer.score(ITEMS, s("5", "0", "0"), GRADES);
        assertThat(zero.total()).isEqualByComparingTo("20");
        assertThat(zero.gradeCd()).isEqualTo("D");
        assertThat(zero.suggestHold()).isTrue();
    }

    @Test
    void 등급_경계는_최소점수_포함_반올림한_총점으로() {
        // 9/10×40 + 4.5/5×35 + 2.5/3×25 = 36 + 31.5 + 20.8333… = 88.33 → B
        assertThat(EvalScorer.score(ITEMS, s("9", "4.5", "2.5"), GRADES).gradeCd()).isEqualTo("B");
        // 40 + 17.5 + 2.88/3×25(24) = 81.5
        assertThat(EvalScorer.score(ITEMS, s("10", "2.5", "2.88"), GRADES).total()).isEqualByComparingTo("81.5");
        EvalScorer.Result b = EvalScorer.score(ITEMS, s("8.75", "5", "1.8"), GRADES);      // 35 + 35 + 15 = 85
        assertThat(b.total()).isEqualByComparingTo("85");
        EvalScorer.Result edge = EvalScorer.score(ITEMS, s("10", "2.5", "1.8"), GRADES);    // 40 + 17.5 + 15 = 72.5
        assertThat(edge.gradeCd()).isEqualTo("C");
        EvalScorer.Result eighty = EvalScorer.score(ITEMS, s("10", "3.5", "1.8"), GRADES);  // 40 + 24.5 + 15 = 79.5 → C
        assertThat(eighty.gradeCd()).isEqualTo("C");
        EvalScorer.Result exact = EvalScorer.score(ITEMS, s("10", "5", "0.6"), GRADES);     // 40 + 35 + 5 = 80 → B
        assertThat(exact.total()).isEqualByComparingTo("80");
        assertThat(exact.gradeCd()).isEqualTo("B");
        // 0.01/8×100 = 0.125 → HALF_UP 0.13
        EvalScorer.Result half = EvalScorer.score(List.of(new EvalScorer.Item(1, d("8"), d("100"), null)), Map.of(1L, d("0.01")), GRADES);
        assertThat(half.total()).isEqualByComparingTo("0.13");      // 0.125 → 0.13
    }

    @Test
    void 과락은_등급과_관계없이_HOLD_제안_점수가_빠지면_총점없음() {
        EvalScorer.Result r = EvalScorer.score(ITEMS, s("4.99", "5", "3"), GRADES);     // 19.96 + 35 + 25 = 79.96
        assertThat(r.fail()).isTrue();
        assertThat(r.failedItems()).containsExactly(1L);
        assertThat(r.gradeCd()).isEqualTo("C");
        assertThat(r.suggestHold()).isTrue();
        assertThat(EvalScorer.score(ITEMS, s("5", "5", "3"), GRADES).fail()).isFalse();   // 과락 점수와 같으면 통과
        EvalScorer.Result part = EvalScorer.score(ITEMS, s("1", null, "3"), GRADES);
        assertThat(part.complete()).isFalse();
        assertThat(part.total()).isNull();
        assertThat(part.gradeCd()).isNull();
        assertThat(part.fail()).isTrue();      // 넣은 항목까지로 과락은 본다
        assertThat(EvalScorer.weightSum(ITEMS)).isEqualByComparingTo("100");
    }

    @Test
    void 입고PPM_구간_경계포함_가장낮은상한_끝없는구간_구간없으면_null() {
        // 0 이하 100%, 500 이하 80%, 2000 이하 50%, 그 위 0% — 순서를 섞어 넣어도 같다
        List<EvalScorer.Band> bands = List.of(new EvalScorer.Band(d("2000"), d("50")), new EvalScorer.Band(null, d("0")),
                new EvalScorer.Band(d("0"), d("100")), new EvalScorer.Band(d("500"), d("80")));
        assertThat(EvalScorer.ppmScore(bands, d("20"), d("0"))).isEqualByComparingTo("20");
        assertThat(EvalScorer.ppmScore(bands, d("20"), d("0.01"))).isEqualByComparingTo("16");
        assertThat(EvalScorer.ppmScore(bands, d("20"), d("500"))).isEqualByComparingTo("16");      // 경계 포함
        assertThat(EvalScorer.ppmScore(bands, d("20"), d("500.01"))).isEqualByComparingTo("10");
        assertThat(EvalScorer.ppmScore(bands, d("20"), d("999999"))).isEqualByComparingTo("0");
        // 7점 × 33.33% = 2.3331 → 2.33
        assertThat(EvalScorer.ppmScore(List.of(new EvalScorer.Band(null, d("33.33"))), d("7"), d("1"))).isEqualByComparingTo("2.33");
        // 끝 없는 구간이 없으면 넘는 PPM 은 점수 없음
        assertThat(EvalScorer.ppmScore(List.of(new EvalScorer.Band(d("100"), d("100"))), d("10"), d("101"))).isNull();
        assertThat(EvalScorer.ppmScore(List.of(), d("10"), d("0"))).isNull();
    }
}
