package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * 로트 크기 (docs/38 17.3) — 손으로 검산한 값과 대조. 순서: 로트 규칙 → 배수 올림 → 단위 자릿수 올림.
 * G2 구매 수량 = MIN(MOQ) + 배수(발주 배수).
 */
class LotSizingTest {

    private static BigDecimal n(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    private static String size(String need, String rule, String lot, String mult, int scale) {
        return LotSizing.size(n(need), rule, n(lot), n(mult), scale).stripTrailingZeros().toPlainString();
    }

    @Test
    void 부족이_없으면_0() {
        assertThat(size("0", "MIN", "100", "10", 0)).isEqualTo("0");
        assertThat(size("-5", "EX", null, null, 0)).isEqualTo("0");
        assertThat(LotSizing.size(null, "EX", null, null, 0)).isEqualByComparingTo("0");
    }

    @Test
    void 그대로_EX_는_단위_자릿수로만_올린다() {
        assertThat(size("1234.5", "EX", null, null, 0)).isEqualTo("1235");      // EA(0자리) — 모자라게 만들지 않는다
        assertThat(size("1234.5", "EX", null, null, 3)).isEqualTo("1234.5");
        assertThat(size("0.0004", "EX", null, null, 3)).isEqualTo("0.001");
    }

    @Test
    void 고정_FX_는_로트_수량의_배수로_올린다() {
        assertThat(size("1", "FX", "600", null, 0)).isEqualTo("600");
        assertThat(size("600", "FX", "600", null, 0)).isEqualTo("600");
        assertThat(size("601", "FX", "600", null, 0)).isEqualTo("1200");          // ⌈601/600⌉ = 2 → 1200
        assertThat(size("2.5", "FX", "1.2", null, 1)).isEqualTo("3.6");          // ⌈2.5/1.2⌉ = 3 → 3.6
    }

    @Test
    void 최소_MIN_은_로트_수량보다_적으면_로트_수량() {
        assertThat(size("4", "MIN", "10", null, 0)).isEqualTo("10");
        assertThat(size("15", "MIN", "10", null, 0)).isEqualTo("15");
    }

    @Test
    void 배수는_로트_규칙_뒤에_올린다() {
        // 문서 17.3 예: 부족 1,234.5 KG · MOQ 2,000 · 배수 500 → 2,000 / 부족 2,100 → 2,100 → 2,500
        assertThat(size("1234.5", "MIN", "2000", "500", 3)).isEqualTo("2000");
        assertThat(size("2100", "MIN", "2000", "500", 3)).isEqualTo("2500");
        // MOQ 10, 배수 4: 부족 8 → 10 → ⌈10/4⌉×4 = 12 (MOQ 이 배수가 아니면 배수가 이긴다 — 모자라지 않게)
        assertThat(size("8", "MIN", "10", "4", 0)).isEqualTo("12");
        // EX + 배수 25(포장): 부족 51 → 75
        assertThat(size("51", "EX", null, "25", 0)).isEqualTo("75");
        // 배수가 소수: 부족 1.01 → 배수 0.25 → 1.25
        assertThat(size("1.01", "EX", null, "0.25", 2)).isEqualTo("1.25");
    }

    @Test
    void 로트_수량이_없으면_EX_처럼() {
        assertThat(size("7", "MIN", null, null, 0)).isEqualTo("7");     // G2: 공급원 MOQ 없음
        assertThat(size("7", "FX", null, null, 0)).isEqualTo("7");
        assertThat(size("7", null, null, "5", 0)).isEqualTo("10");
    }

    @Test
    void 규칙_값은_시스템_고정_세_개뿐() {
        assertThat(LotSizing.isRule("EX")).isTrue();
        assertThat(LotSizing.isRule("FX")).isTrue();
        assertThat(LotSizing.isRule("MIN")).isTrue();
        assertThat(LotSizing.isRule("POQ")).isFalse();
        assertThat(LotSizing.isRule(null)).isFalse();
    }
}
