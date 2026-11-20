package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.aps.MpsCalculator.Cell;
import io.smarthappy.module.aps.MpsCalculator.Firmed;
import io.smarthappy.module.aps.MpsCalculator.Forecast;
import io.smarthappy.module.aps.MpsCalculator.ItemIn;
import io.smarthappy.module.aps.MpsCalculator.Order;
import io.smarthappy.module.aps.MpsCalculator.Supply;

/** MPS 계산 본체 (docs/38 16.3) — 소진·지난 납기·로트 규칙·안전재고·확정 구간 이월. DB 없음 */
class MpsCalculatorTest {

    static final LocalDate D0 = LocalDate.of(2026, 11, 2);   // 월요일

    static BigDecimal n(String s) {
        return new BigDecimal(s);
    }

    static ItemIn item(String stock, String safety, String rule, String lot, List<Order> o, List<Forecast> f, List<Supply> s, Firmed fm) {
        return new ItemIn(1, 0, n(stock), n(safety), rule, lot == null ? null : n(lot), o, f, s, fm);
    }

    static List<String> col(List<Cell> cells, java.util.function.Function<Cell, BigDecimal> f) {
        return cells.stream().map(c -> f.apply(c).stripTrailingZeros().toPlainString()).toList();
    }

    @Test
    void 수주는_납기일에_남은_예측은_기간_첫날에_거래처마다_큰_쪽() {
        List<Order> orders = List.of(new Order(1, D0.plusDays(2), n("4")), new Order(2, D0.plusDays(2), n("30")));
        // 거래처1 주 예측 10(월~일) → 수주 4 소진, 남은 6 을 월요일(첫날)에. 거래처2 주 예측 20 → 수주 30 이 더 커서 0
        List<Forecast> fc = List.of(new Forecast(1, D0, D0.plusDays(7), n("10")), new Forecast(2, D0, D0.plusDays(7), n("20")));
        List<Cell> c = MpsCalculator.calculate(item("0", "0", "EX", null, orders, fc, List.of(), null), D0, 7, D0);
        assertThat(col(c, Cell::demandOrder)).containsExactly("0", "0", "34", "0", "0", "0", "0");
        assertThat(col(c, Cell::demandFcst)).containsExactly("6", "0", "0", "0", "0", "0", "0");
        assertThat(col(c, Cell::prop)).containsExactly("6", "0", "34", "0", "0", "0", "0");
    }

    @Test
    void 지난_납기와_진행_중_예측_기간은_첫날로_기간_밖_예측은_뺀다() {
        List<Order> orders = List.of(new Order(1, D0.minusDays(1), n("5")), new Order(1, D0.plusDays(30), n("9")));
        List<Forecast> fc = List.of(new Forecast(1, D0.minusDays(14), D0.minusDays(7), n("99")),   // 끝난 기간
                new Forecast(1, D0.minusDays(2), D0.plusDays(5), n("8")));                         // 진행 중 — 지난 납기 5 가 소진
        List<Cell> c = MpsCalculator.calculate(item("0", "0", "EX", null, orders, fc, List.of(), null), D0, 7, D0);
        assertThat(c.get(0).pastDue()).isEqualByComparingTo("5");
        assertThat(c.get(0).demandOrder()).isEqualByComparingTo("5");
        assertThat(c.get(0).demandFcst()).isEqualByComparingTo("3");
        assertThat(col(c, Cell::demandOrder).subList(1, 7)).containsOnly("0");   // 기간 밖 납기는 뺀다
    }

    @Test
    void 재고와_지시_공급을_먼저_쓰고_안전재고_미달분만_제안한다() {
        List<Order> orders = List.of(new Order(1, D0, n("8")), new Order(1, D0.plusDays(1), n("6")));
        List<Supply> sup = List.of(new Supply(D0.minusDays(1), n("2")), new Supply(D0.plusDays(1), n("1")));   // 늦은 지시도 첫날 공급
        List<Cell> c = MpsCalculator.calculate(item("5", "3", "EX", null, orders, List.of(), sup, null), D0, 7, D0);
        // 첫날: 5 + 2 − 8 = −1 → 안전 3 까지 4. 둘째: 3 + 1 − 6 = −2 → 5
        assertThat(col(c, Cell::prop).subList(0, 3)).containsExactly("4", "5", "0");
        assertThat(MpsCalculator.pab(n("5"), c.stream().map(Cell::supplyWo).toList(),
                c.stream().map(x -> x.demandOrder().add(x.demandFcst())).toList(), c.stream().map(Cell::base).toList()).get(1))
                .isEqualByComparingTo("3");
    }

    @Test
    void 로트_규칙_FX_는_배수로_MIN_은_최소로_자릿수는_올림() {
        assertThat(MpsCalculator.lot(n("5"), "FX", n("10"), 0)).isEqualByComparingTo("10");
        assertThat(MpsCalculator.lot(n("21"), "FX", n("10"), 0)).isEqualByComparingTo("30");
        assertThat(MpsCalculator.lot(n("5"), "MIN", n("12"), 0)).isEqualByComparingTo("12");
        assertThat(MpsCalculator.lot(n("15"), "MIN", n("12"), 0)).isEqualByComparingTo("15");
        assertThat(MpsCalculator.lot(n("1.234"), "EX", null, 2)).isEqualByComparingTo("1.24");
    }

    @Test
    void 확정_구간_안은_현행_확정_값을_이어받고_그_기간_밖이면_제안() {
        List<Order> orders = List.of(new Order(1, D0, n("5")), new Order(1, D0.plusDays(1), n("5")), new Order(1, D0.plusDays(4), n("5")));
        // 현행 확정 MPS 는 D0~D0+2 기간, D0 에 7, D0+1 은 칸 없음(0). 구간 끝 = D0+3 → D0~D0+2 구간
        Firmed fm = new Firmed(D0.minusDays(1), D0.plusDays(2), Map.of(D0, n("7")));
        List<Cell> c = MpsCalculator.calculate(item("0", "0", "EX", null, orders, List.of(), List.of(), fm), D0, 7, D0.plusDays(3));
        assertThat(c.stream().map(Cell::fenced).toList()).containsExactly(true, true, true, false, false, false, false);
        assertThat(col(c, Cell::base)).containsExactly("7", "0", "0", "3", "5", "0", "0");
        // 제안은 참고로 남는다(구간 안에서도 계산)
        assertThat(col(c, Cell::prop).subList(0, 2)).containsExactly("5", "3");
    }
}
