package io.smarthappy.module.wms.alloc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.smarthappy.module.wms.alloc.AllocationStrategy.Candidate;
import io.smarthappy.module.wms.alloc.AllocationStrategy.Options;
import io.smarthappy.module.wms.alloc.AllocationStrategy.Result;
import io.smarthappy.module.wms.alloc.AllocationStrategy.SkipReason;
import io.smarthappy.module.wms.alloc.AllocationStrategy.Strategy;

/**
 * 차감 전략 순수 함수 (docs/30 W4) — 표 기반. DB 없음.
 * 결과 표기 "위치:수량" 을 쉼표로 이은 글자로 비교한다(LOT 이 있으면 "위치/LOT:수량").
 */
class AllocationStrategyTest {

    static final LocalDate D1 = LocalDate.of(2026, 10, 1);
    static final LocalDate D2 = LocalDate.of(2026, 10, 2);
    static final LocalDate D3 = LocalDate.of(2026, 10, 3);
    static final LocalDate TODAY = LocalDate.of(2026, 10, 21);

    static Candidate c(long node, int seq, Long lot, LocalDate fifo, LocalDate exp, String status, String qty) {
        return new Candidate(node, seq, lot, lot == null ? null : "L" + lot, fifo, exp, status, new BigDecimal(qty));
    }

    static Candidate av(long node, int seq, LocalDate fifo, String qty) {
        return c(node, seq, null, fifo, null, "AVAILABLE", qty);
    }

    static String picks(Result r) {
        return r.picks().stream().map(p -> p.from().nodeId() + (p.from().lotId() == null ? "" : "/" + p.from().lotId()) + ":"
                + p.qty().stripTrailingZeros().toPlainString()).collect(Collectors.joining(","));
    }

    static Result run(List<Candidate> cs, String qty, Options o) {
        Result r = AllocationStrategy.allocate(cs, new BigDecimal(qty), o);
        // 불변식: 꺼낸 합 + 부족량 = 요청, 어느 후보에서도 가진 것보다 많이 꺼내지 않음
        assertThat(r.allocated().add(r.shortage())).isEqualByComparingTo(qty);
        r.picks().forEach(p -> assertThat(p.qty()).isPositive().isLessThanOrEqualTo(p.from().qty()));
        return r;
    }

    // ------------------------------------------------------------------ 전략별 순서

    @ParameterizedTest(name = "{0} {1} → {2} / 부족 {3}")
    @CsvSource(delimiter = '|', textBlock = """
            FIFO    | 5   | 3:5             | 0
            FIFO    | 15  | 3:10,1:5        | 0
            FIFO    | 30  | 3:10,1:10,2:5   | 5
            LOC_SEQ | 15  | 2:5,1:10        | 0
            FEFO    | 12  | 2:5,1:7         | 0
            """)
    void 전략별_순서(Strategy s, String qty, String expect, String shortage) {
        // 위치 1: 입고 D2·피킹 20·기한 D3 / 위치 2: 입고 D3·피킹 10·기한 D1 / 위치 3: 입고 D1·피킹 30·기한 없음
        List<Candidate> cs = List.of(
                c(1, 20, null, D2, D3, "AVAILABLE", "10"),
                c(2, 10, null, D3, D1, "AVAILABLE", "5"),
                c(3, 30, null, D1, null, "AVAILABLE", "10"));
        Result r = run(cs, qty, new Options(s, null, true, null, true));
        assertThat(picks(r)).isEqualTo(expect);
        assertThat(r.shortage()).isEqualByComparingTo(shortage);
    }

    @Test
    void 동률은_위치id_다음_LOT_없음_먼저_입력_순서와_무관() {
        List<Candidate> cs = new ArrayList<>(List.of(
                c(7, 10, 2L, D1, null, "AVAILABLE", "1"),
                c(7, 10, null, D1, null, "AVAILABLE", "1"),
                c(5, 10, 9L, D1, null, "AVAILABLE", "1"),
                c(7, 10, 1L, D1, null, "AVAILABLE", "1")));
        String first = picks(run(cs, "4", Options.of(Strategy.FIFO)));
        assertThat(first).isEqualTo("5/9:1,7:1,7/1:1,7/2:1");
        for (int i = 0; i < 5; i++) {
            Collections.shuffle(cs);
            assertThat(picks(run(cs, "4", Options.of(Strategy.FIFO)))).isEqualTo(first);
        }
    }

    @Test
    void 입고일_없는_후보는_FIFO_맨뒤_FEFO는_기한없음이_맨뒤() {
        List<Candidate> cs = List.of(av(1, 0, null, "5"), av(2, 0, D3, "5"),
                c(3, 0, null, D1, null, "AVAILABLE", "5"), c(4, 0, null, D2, D3, "AVAILABLE", "5"));
        assertThat(picks(run(cs, "20", Options.of(Strategy.FIFO)))).isEqualTo("3:5,4:5,2:5,1:5");
        assertThat(picks(run(cs, "20", Options.of(Strategy.FEFO)))).isEqualTo("4:5,3:5,2:5,1:5");
    }

    // ------------------------------------------------------------------ 지정 LOT·상태·유효기한

    @Test
    void 지정_LOT_이_먼저_목록_순서대로_없는_LOT_은_알림() {
        List<Candidate> cs = List.of(
                c(1, 0, 11L, D1, null, "AVAILABLE", "5"),
                c(2, 0, 12L, D2, null, "AVAILABLE", "5"),
                c(3, 0, 13L, D3, null, "AVAILABLE", "5"));
        Result r = run(cs, "12", new Options(Strategy.FIFO, List.of(13L, 99L, 12L), true, null, true));
        assertThat(picks(r)).isEqualTo("3/13:5,2/12:5,1/11:2");
        assertThat(r.missingLots()).containsExactly(99L);            // 다른 품목의 LOT·없는 LOT
    }

    @Test
    void 상태_합격분만_또는_QC_포함_보류_불량은_항상_제외() {
        List<Candidate> cs = List.of(
                c(1, 0, null, D1, null, "QC", "5"),
                c(2, 0, null, D1, null, "HOLD", "5"),
                c(3, 0, null, D1, null, "BLOCKED", "5"),
                c(4, 0, null, D2, null, "AVAILABLE", "5"));
        Result only = run(cs, "8", Options.of(Strategy.FIFO));
        assertThat(picks(only)).isEqualTo("4:5");
        assertThat(only.shortage()).isEqualByComparingTo("3");
        assertThat(only.skipped()).extracting(s -> s.reason()).containsOnly(SkipReason.STATUS).hasSize(3);
        Result withQc = run(cs, "8", new Options(Strategy.FIFO, null, false, null, true));
        assertThat(picks(withQc)).isEqualTo("1:5,4:3");
    }

    @ParameterizedTest(name = "기한 {0} → 쓸 수 있음 {1}")
    @CsvSource({"2026-10-20, false", "2026-10-21, true", "2026-10-22, true"})
    void 유효기한_오늘_경계(LocalDate exp, boolean usable) {
        Result r = run(List.of(c(1, 0, 5L, D1, exp, "AVAILABLE", "3")), "3", new Options(Strategy.FEFO, null, true, TODAY, true));
        assertThat(r.complete()).isEqualTo(usable);
        if (!usable) {
            assertThat(r.skipped()).singleElement().extracting(s -> s.reason()).isEqualTo(SkipReason.EXPIRED);
        }
        // 기한 검사를 끄면(excludeExpiredOn = null) 지난 것도 꺼낸다 — 판단은 부르는 쪽 정책
        assertThat(run(List.of(c(1, 0, 5L, D1, exp, "AVAILABLE", "3")), "3", Options.of(Strategy.FEFO)).complete()).isTrue();
    }

    // ------------------------------------------------------------------ 수량 경계

    @Test
    void 부족_0_재고_한_위치에_여러_LOT() {
        assertThat(run(List.of(), "5", Options.of(Strategy.FIFO)).shortage()).isEqualByComparingTo("5");
        assertThat(run(null, "5", Options.of(Strategy.FIFO)).picks()).isEmpty();
        Result zero = run(List.of(av(1, 0, D1, "0"), av(2, 0, D1, "-1")), "1", Options.of(Strategy.FIFO));
        assertThat(zero.skipped()).extracting(s -> s.reason()).containsOnly(SkipReason.EMPTY);
        // 한 위치(1)에 LOT 3개 + 소수 수량
        List<Candidate> cs = List.of(
                c(1, 0, 3L, D3, null, "AVAILABLE", "0.000001"),
                c(1, 0, 1L, D1, null, "AVAILABLE", "1.5"),
                c(1, 0, 2L, D2, null, "AVAILABLE", "2.25"));
        Result r = run(cs, "3.000001", Options.of(Strategy.FIFO));
        assertThat(picks(r)).isEqualTo("1/1:1.5,1/2:1.500001");
    }

    @Test
    void 통째로만_LOT_은_남은_요청보다_크면_건너뛴다_LOT_없는_후보는_나눈다() {
        List<Candidate> cs = List.of(
                c(1, 0, 1L, D1, null, "AVAILABLE", "10"),
                c(2, 0, 2L, D2, null, "AVAILABLE", "4"),
                c(3, 0, null, D3, null, "AVAILABLE", "100"));
        Result r = run(cs, "7", new Options(Strategy.FIFO, null, true, null, false));
        assertThat(picks(r)).isEqualTo("2/2:4,3:3");
        assertThat(r.skipped()).extracting(s -> s.reason()).containsExactly(SkipReason.WHOLE_LOT);
    }

    @Test
    void 요청_수량이_0_음수_없음이면_거부() {
        for (String q : new String[] {"0", "-1", "0.000"}) {
            assertThatThrownBy(() -> AllocationStrategy.allocate(List.of(), new BigDecimal(q), Options.of(Strategy.FIFO)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> AllocationStrategy.allocate(List.of(), null, Options.of(Strategy.FIFO)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Options(null, null, true, null, true)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void 입력_목록은_바꾸지_않는다() {
        List<Candidate> cs = new ArrayList<>(List.of(av(2, 0, D2, "1"), av(1, 0, D1, "1")));
        List<Candidate> before = List.copyOf(cs);
        run(cs, "2", Options.of(Strategy.FIFO));
        assertThat(cs).isEqualTo(before);
    }
}
