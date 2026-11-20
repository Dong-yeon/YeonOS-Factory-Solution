package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.aps.MrpEngine.Child;
import io.smarthappy.module.aps.MrpEngine.Demand;
import io.smarthappy.module.aps.MrpEngine.Elem;
import io.smarthappy.module.aps.MrpEngine.Exc;
import io.smarthappy.module.aps.MrpEngine.Item;
import io.smarthappy.module.aps.MrpEngine.MpsPlan;
import io.smarthappy.module.aps.MrpEngine.Peg;
import io.smarthappy.module.aps.MrpEngine.Result;
import io.smarthappy.module.aps.MrpEngine.Supply;

/**
 * MRP 엔진 손 검산 (docs/38 19.10) — DB 없이. 로트 올림·리드타임 역산·다단 3단계·안전재고·재일정 가정·페깅·예외(G7)·저차 코드.
 * 날짜는 D0 = 첫날 = 공장 오늘(2026-11-10)
 */
class MrpEngineTest {

    static final LocalDate D0 = LocalDate.of(2026, 11, 10);

    static LocalDate d(int n) {
        return D0.plusDays(n);
    }

    static BigDecimal q(String v) {
        return new BigDecimal(v);
    }

    /** 품목 만들기 도우미 — 기본: EX, 재고·안전 0, 자릿수 0 */
    static final class B {
        final Map<Long, Item> items = new LinkedHashMap<>();
        final Map<Long, Integer> llc = new HashMap<>();
        final Map<Long, Map<Long, BigDecimal>> bom = new HashMap<>();
        final List<MpsPlan> mps = new ArrayList<>();
        final List<Demand> demands = new ArrayList<>();
        final List<Supply> supplies = new ArrayList<>();
        int days = 28;
        int outDays = 3;

        B item(long id, String cd, String proc, int lv, String stock, String safety, String rule, String lot, String mult, String moq,
                String omult, Integer lead, boolean mps) {
            items.put(id, new Item(id, cd, proc, 0, mps, q(stock), q(safety), rule, lot == null ? null : q(lot), mult == null ? null : q(mult),
                    moq == null ? null : q(moq), omult == null ? null : q(omult), lead));
            llc.put(id, lv);
            return this;
        }

        B bom(long parent, long child, String per) {
            bom.computeIfAbsent(parent, k -> new LinkedHashMap<>()).put(child, q(per));
            return this;
        }

        Result run() {
            return MrpEngine.run(new MrpEngine.Input(D0, days, D0, outDays, items, llc, mps, demands, supplies, 50000, 10000),
                    (itemId, qty, on) -> bom.getOrDefault(itemId, Map.of()).entrySet().stream()
                            .map(e -> new Child(e.getKey(), e.getValue().multiply(qty))).toList());
        }
    }

    static List<Elem> of(Result r, long itemId, String kind) {
        return r.elems().stream().filter(e -> e.itemId() == itemId && e.kind().equals(kind)).toList();
    }

    static List<Exc> exc(Result r, String code) {
        return r.excs().stream().filter(x -> x.code().equals(code)).toList();
    }

    static BigDecimal pegged(Result r, int demandSeq, int supplySeq) {
        return r.pegs().stream().filter(p -> p.demandSeq() == demandSeq && p.supplySeq() == supplySeq).map(Peg::qty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ------------------------------------------------------------------ 19.10 손 검산 표 그대로

    /** A(MPS) = B 2 + D 1, B = C 3 — 3단계 전개·FX 올림·MIN+배수·안전재고·리드타임 역산·늦음·공급원 없음 */
    B sample() {
        return new B()
                .item(1, "A", "MAKE", 0, "0", "0", "EX", null, null, null, null, 1, true)
                .item(2, "B", "MAKE", 1, "10", "0", "FX", "50", null, null, null, 2, false)
                .item(3, "C", "BUY", 2, "30", "20", "EX", null, null, "100", "50", 3, false)
                .item(4, "D", "BUY", 1, "0", "0", "EX", null, null, null, null, null, false)
                .bom(1, 2, "2").bom(1, 4, "1").bom(2, 3, "3");
    }

    @Test
    void 손검산_3단계_로트_리드타임_안전재고() {
        B b = sample();
        b.mps.add(new MpsPlan(1, d(5), q("40"), 901));
        Result r = b.run();

        Elem a = of(r, 1, "MPS").get(0);
        assertThat(a.qty()).isEqualByComparingTo("40");
        assertThat(a.date()).isEqualTo(d(5));
        assertThat(a.start()).isEqualTo(d(4));               // 완료 D5 − 1일
        assertThat(a.refId()).isEqualTo(901L);

        // B: 종속 80 @D4 − 재고 10 = 70 → FX 50 → 100, 완료 D4·시작 D2
        Elem bDep = of(r, 2, "DEP").get(0);
        assertThat(bDep.qty()).isEqualByComparingTo("80");
        assertThat(bDep.date()).isEqualTo(d(4));
        assertThat(bDep.parentSeq()).isEqualTo(a.seq());
        Elem bPlan = of(r, 2, "PLAN").get(0);
        assertThat(bPlan.qty()).isEqualByComparingTo("100");
        assertThat(bPlan.date()).isEqualTo(d(4));
        assertThat(bPlan.start()).isEqualTo(d(2));
        assertThat(bPlan.late()).isFalse();
        assertThat(pegged(r, bDep.seq(), of(r, 2, "STOCK").get(0).seq())).isEqualByComparingTo("10");
        assertThat(pegged(r, bDep.seq(), bPlan.seq())).isEqualByComparingTo("70");

        // C: 안전 20 @D0 + 종속 300 @D2(= B 100 × 3). 재고 30 → 안전 20·D2 에 10 → 순소요 290 → MIN 100 → 배수 50 → 300, 시작 D-1 늦음
        Elem cDep = of(r, 3, "DEP").get(0);
        assertThat(cDep.qty()).isEqualByComparingTo("300");
        assertThat(cDep.date()).isEqualTo(d(2));
        assertThat(cDep.parentSeq()).isEqualTo(bPlan.seq());
        Elem cSafe = of(r, 3, "SAFE").get(0);
        Elem cStock = of(r, 3, "STOCK").get(0);
        assertThat(pegged(r, cSafe.seq(), cStock.seq())).isEqualByComparingTo("20");
        assertThat(pegged(r, cDep.seq(), cStock.seq())).isEqualByComparingTo("10");
        Elem cPlan = of(r, 3, "PLAN").get(0);
        assertThat(cPlan.qty()).isEqualByComparingTo("300");
        assertThat(cPlan.date()).isEqualTo(d(2));
        assertThat(cPlan.start()).isEqualTo(d(-1));          // 리드 3일 역산
        assertThat(cPlan.late()).isTrue();
        assertThat(pegged(r, cDep.seq(), cPlan.seq())).isEqualByComparingTo("290");
        Exc late = exc(r, "START_PAST").get(0);
        assertThat(late.itemId()).isEqualTo(3L);
        assertThat(late.elemSeq()).isEqualTo(cPlan.seq());
        assertThat(late.curDate()).isEqualTo(d(-1));
        assertThat(late.sugDate()).isEqualTo(D0);
        // 끝 예상 재고 = 30 + 300 − 20 − 300 = 10 (안전재고 20 은 수요로 셌으니 실제 남는 재고는 30)
        List<Elem> cs = r.elems().stream().filter(e -> e.itemId() == 3).sorted(MrpEngine.LIST_ORDER).toList();
        assertThat(MrpEngine.pab(cs).get(cs.size() - 1)).isEqualByComparingTo("10");

        // D: 40 @D4, 공급원 없음 → 리드 0, 시작 D4 + NO_SOURCE
        Elem dPlan = of(r, 4, "PLAN").get(0);
        assertThat(dPlan.qty()).isEqualByComparingTo("40");
        assertThat(dPlan.start()).isEqualTo(d(4));
        assertThat(exc(r, "NO_SOURCE")).extracting(Exc::itemId).containsExactly(4L);
        assertThat(exc(r, "START_PAST")).hasSize(1);
        assertThat(r.orderCount()).isEqualTo(4);
        // 구매 품목은 전개하지 않는다
        assertThat(r.elems().stream().filter(e -> DEPOf(e) && e.parentSeq().equals(cPlan.seq()))).isEmpty();
    }

    private static boolean DEPOf(Elem e) {
        return "DEP".equals(e.kind());
    }

    @Test
    void 같은_입력이면_같은_결과() {
        B b1 = sample();
        b1.mps.add(new MpsPlan(1, d(5), q("40"), 901));
        b1.mps.add(new MpsPlan(1, d(9), q("15"), 902));
        B b2 = sample();
        b2.mps.add(new MpsPlan(1, d(9), q("15"), 902));
        b2.mps.add(new MpsPlan(1, d(5), q("40"), 901));
        Result r1 = b1.run();
        Result r2 = b2.run();
        assertThat(r2.elems()).isEqualTo(r1.elems());
        assertThat(r2.pegs()).isEqualTo(r1.pegs());
        assertThat(r2.excs()).isEqualTo(r1.excs());
    }

    // ------------------------------------------------------------------ 로트

    @Test
    void FX_로트_남는양은_다음_수요가_먼저_쓴다() {
        B b = new B().item(10, "L", "MAKE", 0, "0", "0", "FX", "50", null, null, null, 0, false);
        b.demands.add(new Demand(10, "WODEP", d(1), q("30"), 1L, "W1"));
        b.demands.add(new Demand(10, "WODEP", d(2), q("30"), 2L, "W2"));
        b.demands.add(new Demand(10, "WODEP", d(3), q("30"), 3L, "W3"));
        Result r = b.run();
        // D1: 30 → 50(남 20) / D2: 20 + 새 10 → 50(남 40) / D3: 남은 40 에서 30 → 계획 없음
        assertThat(of(r, 10, "PLAN")).extracting(Elem::date).containsExactly(d(1), d(2));
        assertThat(of(r, 10, "PLAN")).extracting(Elem::qty).allSatisfy(x -> assertThat(x).isEqualByComparingTo("50"));
    }

    @Test
    void MIN_뒤_배수_올림_그리고_같은날_수요는_계획_주문_하나() {
        B b = new B().item(11, "M", "MAKE", 0, "0", "0", "MIN", "100", "30", null, null, 0, false);
        b.demands.add(new Demand(11, "WODEP", d(4), q("60"), 1L, "W1"));
        b.demands.add(new Demand(11, "WODEP", d(4), q("50"), 2L, "W2"));
        Result r = b.run();
        // 같은 날 60 + 50 = 110 → max(110, 100) = 110 → 30 의 배수 120 — 한 건
        assertThat(of(r, 11, "PLAN")).hasSize(1);
        assertThat(of(r, 11, "PLAN").get(0).qty()).isEqualByComparingTo("120");
    }

    @Test
    void 구매_로트_기준정보_뒤_MOQ_배수() {
        Item it = new Item(1, "X", "BUY", 0, false, q("0"), q("0"), "EX", null, null, q("100"), q("50"), 3);
        assertThat(MrpEngine.lot(it, q("130"))).isEqualByComparingTo("150");   // 19.4 예
        assertThat(MrpEngine.lot(it, q("40"))).isEqualByComparingTo("100");    // MOQ
        Item fx = new Item(1, "X", "BUY", 2, false, q("0"), q("0"), "FX", q("70"), null, q("100"), q("50"), 3);
        // FX 70 → 140 → MOQ 100 넘음 → 배수 50 → 150
        assertThat(MrpEngine.lot(fx, q("75"))).isEqualByComparingTo("150");
        Item dec = new Item(1, "X", "BUY", 2, false, q("0"), q("0"), "EX", null, null, null, null, 3);
        assertThat(MrpEngine.lot(dec, q("1.234"))).isEqualByComparingTo("1.24"); // 자릿수 올림
    }

    // ------------------------------------------------------------------ 재일정 가정·예외(G7)

    @Test
    void 늦은_발주는_당겨_쓰고_당김_메시지_남으면_줄임() {
        B b = new B().item(20, "P", "BUY", 0, "0", "0", "EX", null, null, null, null, 2, false);
        b.demands.add(new Demand(20, "WODEP", d(3), q("50"), 1L, "W1"));
        b.demands.add(new Demand(20, "WODEP", d(10), q("20"), 2L, "W2"));
        b.supplies.add(new Supply(20, "PO", d(6), q("40"), 101L, "PO1-1"));
        b.supplies.add(new Supply(20, "PO", d(20), q("100"), 102L, "PO2-1"));
        Result r = b.run();
        assertThat(of(r, 20, "PLAN")).isEmpty();             // 기존 공급을 다 쓰기 전에는 계획 주문 없음
        List<Exc> in = exc(r, "RESCHED_IN");
        assertThat(in).extracting(Exc::refNo).containsExactly("PO1-1", "PO2-1");
        assertThat(in).extracting(Exc::sugDate).containsExactly(d(3), d(3));
        assertThat(in).extracting(Exc::curDate).containsExactly(d(6), d(20));
        Exc red = exc(r, "REDUCE").get(0);
        assertThat(red.refNo()).isEqualTo("PO2-1");
        assertThat(red.qty()).isEqualByComparingTo("70");    // 100 − 10 − 20
        assertThat(exc(r, "CANCEL")).isEmpty();
    }

    @Test
    void 너무_이른_공급은_밀기_쓸곳없으면_취소_안전재고만_덮으면_날짜_예외없음() {
        B b = new B()
                .item(21, "Y", "BUY", 0, "0", "0", "EX", null, null, null, null, 0, false)
                .item(22, "Z", "BUY", 0, "0", "0", "EX", null, null, null, null, 0, false)
                .item(23, "S", "BUY", 0, "0", "10", "EX", null, null, null, null, 0, false)
                .item(24, "T", "MAKE", 0, "0", "0", "EX", null, null, null, null, 1, false);
        b.demands.add(new Demand(21, "WODEP", d(12), q("30"), 1L, "W1"));
        b.supplies.add(new Supply(21, "PO", d(2), q("30"), 201L, "PO3-1"));
        b.supplies.add(new Supply(22, "PO", d(5), q("25"), 202L, "PO4-1"));
        b.supplies.add(new Supply(23, "PO", d(4), q("10"), 203L, "PO5-1"));
        b.demands.add(new Demand(24, "WODEP", d(6), q("5"), 2L, "W2"));
        b.supplies.add(new Supply(24, "WO", d(4), q("5"), 301L, "WO-1"));       // D6 − D4 = 2 ≤ 허용 3 → 밀기 아님
        Result r = b.run();
        Exc out = exc(r, "RESCHED_OUT").get(0);
        assertThat(out.refNo()).isEqualTo("PO3-1");
        assertThat(out.sugDate()).isEqualTo(d(12));
        assertThat(exc(r, "RESCHED_OUT")).hasSize(1);
        Exc cancel = exc(r, "CANCEL").get(0);
        assertThat(cancel.refNo()).isEqualTo("PO4-1");
        assertThat(cancel.qty()).isEqualByComparingTo("25");
        assertThat(r.excs()).noneMatch(x -> "PO5-1".equals(x.refNo()));   // 안전재고만 덮는 발주 — 예외 없음
        assertThat(r.excs()).noneMatch(x -> "WO-1".equals(x.refNo()));
        assertThat(exc(r, "RESCHED_IN")).isEmpty();
    }

    @Test
    void 지난_날짜는_첫날로_밀림_표시_부모_시작이_과거면_자품목도_첫날() {
        B b = new B()
                .item(30, "F", "MAKE", 0, "0", "0", "EX", null, null, null, null, 5, false)
                .item(31, "G", "BUY", 1, "0", "0", "EX", null, null, null, null, 0, false)
                .bom(30, 31, "2");
        b.demands.add(new Demand(30, "WODEP", d(-2), q("4"), 1L, "W-OLD"));
        Result r = b.run();
        Elem dem = of(r, 30, "WODEP").get(0);
        assertThat(dem.date()).isEqualTo(D0);
        assertThat(dem.pastDue()).isTrue();
        Elem plan = of(r, 30, "PLAN").get(0);
        assertThat(plan.start()).isEqualTo(d(-5));
        Elem child = of(r, 31, "DEP").get(0);
        assertThat(child.date()).isEqualTo(D0);
        assertThat(child.pastDue()).isTrue();
        assertThat(child.qty()).isEqualByComparingTo("8");
    }

    @Test
    void 리드타임_미정과_전개_실패는_예외로() {
        B b = new B().item(40, "N", "MAKE", 0, "0", "0", "EX", null, null, null, null, null, false);
        b.demands.add(new Demand(40, "WODEP", d(3), q("7"), 1L, "W1"));
        Result r = MrpEngine.run(new MrpEngine.Input(D0, 28, D0, 3, b.items, b.llc, b.mps, b.demands, b.supplies, 50000, 10000),
                (itemId, qty, on) -> null);
        assertThat(exc(r, "NO_LEAD")).extracting(Exc::itemId).containsExactly(40L);
        assertThat(exc(r, "NO_BOM")).extracting(Exc::itemId).containsExactly(40L);
        assertThat(of(r, 40, "PLAN").get(0).start()).isEqualTo(d(3));   // 0일로 계산
    }

    // ------------------------------------------------------------------ MPS 품목: 페깅만

    @Test
    void MPS_품목은_다시_순소요하지_않고_수주_내시와_페깅_모자라면_MPS_SHORT() {
        B b = new B().item(50, "FG", "MAKE", 0, "10", "5", "EX", null, null, null, null, 0, true);
        b.mps.add(new MpsPlan(50, d(2), q("25"), 1));
        b.mps.add(new MpsPlan(50, d(6), q("40"), 2));
        b.mps.add(new MpsPlan(50, d(-1), q("9"), 3));
        b.demands.add(new Demand(50, "SO", d(2), q("30"), 11L, "SO1-1"));
        b.demands.add(new Demand(50, "SO", d(6), q("20"), 12L, "SO1-2"));
        b.demands.add(new Demand(50, "FC", d(7), q("15"), 7L, "C1 r1 " + d(7)));
        b.demands.add(new Demand(50, "SO", d(8), q("20"), 13L, "SO2-1"));
        Result r = b.run();
        assertThat(of(r, 50, "PLAN")).isEmpty();
        assertThat(of(r, 50, "SAFE")).isEmpty();             // 안전재고는 MPS 가 이미 셌다
        List<Elem> m = of(r, 50, "MPS");
        assertThat(m).extracting(Elem::qty).usingElementComparator(BigDecimal::compareTo).containsExactly(q("25"), q("40"));
        Elem so1 = of(r, 50, "SO").get(0);
        Elem stock = of(r, 50, "STOCK").get(0);
        assertThat(pegged(r, so1.seq(), stock.seq())).isEqualByComparingTo("10");
        assertThat(pegged(r, so1.seq(), m.get(0).seq())).isEqualByComparingTo("20");
        Elem fc = of(r, 50, "FC").get(0);
        assertThat(pegged(r, fc.seq(), m.get(1).seq())).isEqualByComparingTo("15");
        // 10 + 25 + 40 = 75, 수요 30 + 20 + 15 + 20 = 85 → 10 모자람(SO2-1 의 D8)
        Exc s = exc(r, "MPS_SHORT").get(0);
        assertThat(s.qty()).isEqualByComparingTo("10");
        assertThat(s.curDate()).isEqualTo(d(8));
        assertThat(exc(r, "MPS_PAST").get(0).qty()).isEqualByComparingTo("9");
    }

    @Test
    void MPS_품목이_자품목으로_쓰이면_계획하지_않고_MPS_DEP() {
        B b = new B()
                .item(60, "KIT", "MAKE", 0, "0", "0", "EX", null, null, null, null, 0, false)
                .item(61, "FG2", "MAKE", 1, "0", "0", "EX", null, null, null, null, 0, true)
                .bom(60, 61, "1");
        b.demands.add(new Demand(60, "WODEP", d(3), q("4"), 1L, "W1"));
        Result r = b.run();
        assertThat(of(r, 61, "DEP")).hasSize(1);
        assertThat(of(r, 61, "PLAN")).isEmpty();
        assertThat(exc(r, "MPS_DEP").get(0).qty()).isEqualByComparingTo("4");
    }

    // ------------------------------------------------------------------ 저차 코드

    @Test
    void 저차_코드는_가장_깊은_단계_순환은_Loop() {
        // A → B → C, A → C : C 는 2
        Map<Long, Set<Long>> e = new HashMap<>();
        e.put(1L, Set.of(2L, 3L));
        e.put(2L, Set.of(3L));
        Map<Long, Integer> lv = MrpEngine.levels(Set.of(1L, 2L, 3L), e);
        assertThat(lv).containsEntry(1L, 0).containsEntry(2L, 1).containsEntry(3L, 2);
        Map<Long, Set<Long>> loop = new HashMap<>();
        loop.put(1L, Set.of(2L));
        loop.put(2L, Set.of(1L));
        assertThatThrownBy(() -> MrpEngine.levels(Set.of(1L, 2L), loop)).isInstanceOf(MrpEngine.Loop.class);
    }

    @Test
    void 다른_날짜_BOM_의_새_자품목이나_더_깊은_연결은_Restart() {
        B b = new B()
                .item(70, "P", "MAKE", 0, "0", "0", "EX", null, null, null, null, 0, false)
                .item(71, "Q", "MAKE", 1, "0", "0", "EX", null, null, null, null, 0, false)
                .item(72, "R", "MAKE", 1, "0", "0", "EX", null, null, null, null, 0, false)
                .bom(70, 71, "1").bom(70, 99, "1").bom(71, 72, "1");     // 99 = 모르는 품목, Q → R 은 같은 단계
        b.demands.add(new Demand(70, "WODEP", d(3), q("1"), 1L, "W1"));
        assertThatThrownBy(b::run).isInstanceOfSatisfying(MrpEngine.Restart.class, ex -> {
            assertThat(ex.unknown).containsExactly(99L);
            assertThat(ex.edges).containsKeys(70L, 71L);
            assertThat(ex.edges.get(71L)).containsExactly(72L);
        });
    }

    @Test
    void 상한을_넘으면_TooLarge() {
        B b = new B().item(80, "U", "BUY", 0, "0", "0", "EX", null, null, null, null, 0, false);
        for (int i = 0; i < 5; i++) {
            b.demands.add(new Demand(80, "WODEP", d(i), q("1"), (long) i, "W" + i));
        }
        assertThatThrownBy(() -> MrpEngine.run(new MrpEngine.Input(D0, 28, D0, 3, b.items, b.llc, b.mps, b.demands, b.supplies, 50000, 3),
                (itemId, qty, on) -> List.of())).isInstanceOf(MrpEngine.TooLarge.class);
    }
}
