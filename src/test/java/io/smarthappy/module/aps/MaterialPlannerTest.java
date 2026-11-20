package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.aps.FiniteScheduler.OpIn;
import io.smarthappy.module.aps.FiniteScheduler.OpOut;
import io.smarthappy.module.aps.FiniteScheduler.Res;
import io.smarthappy.module.aps.FiniteScheduler.WoIn;
import io.smarthappy.module.aps.FiniteScheduler.WoOut;
import io.smarthappy.module.aps.MaterialPlanner.MatOut;
import io.smarthappy.module.aps.MaterialPlanner.Mode;
import io.smarthappy.module.aps.MaterialPlanner.Need;
import io.smarthappy.module.aps.MaterialPlanner.Supply;
import io.smarthappy.module.aps.ShiftCalendar.Shift;

/**
 * APS 5단계 자재 판정 (docs/38 A27·A32·A33) — DB 없이: WARN 은 시각 그대로 부족 표시, DELAY 는 입고 시각 뒤로, 기간 안 안 덮이면 MAT,
 * 규칙 순서대로 앞 지시가 먼저 가짐, 투입 공정 시작에 필요(없는 순번은 첫 공정), 계획 못 한 공정은 판정 없음, 자재 없으면 1~4단계 그대로.
 */
class MaterialPlannerTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate D1 = LocalDate.of(2026, 11, 2);
    private static final Res M1 = new Res("EQUIP", 1L, 100L, "M1");
    private static final long RM = 900L;

    private static Instant at(LocalDate d, int h) {
        return LocalDateTime.of(d, LocalTime.of(h, 0)).atZone(SEOUL).toInstant();
    }

    private static OpIn op(int seq, int hours) {
        OpIn o = new OpIn();
        o.opSeq = seq;
        o.procId = seq;
        o.procCd = "P" + seq;
        o.procNm = "P" + seq;
        o.nodeId = 100L;
        o.stdCtSec = new BigDecimal(3600);
        o.unitsPerCycle = 1;
        o.setupMin = BigDecimal.ZERO;
        o.qty = BigDecimal.valueOf(hours);
        return o;
    }

    private static WoIn wo(long id, int prio, OpIn... ops) {
        WoIn w = new WoIn();
        w.woId = id;
        w.woNo = "WO-" + id;
        w.priority = prio;
        w.planStart = D1;
        w.planEnd = D1.plusDays(2);
        w.ops.addAll(List.of(ops));
        return w;
    }

    /** 주간 08~17, 3일, 설비 하나 */
    private static List<WoOut> run(MaterialPlanner mat, WoIn... wos) {
        ShiftCalendar c = ShiftCalendar.build(List.of(new Shift(LocalTime.of(8, 0), LocalTime.of(17, 0), 0)), SEOUL, D1, 3,
                at(D1, 0), at(D1.plusDays(3), 0));
        return new FiniteScheduler(c, SEOUL, at(D1, 0), at(D1.plusDays(3), 0), 24 * 3600L, o -> List.of(M1), mat).schedule(List.of(wos));
    }

    private static Need need(Integer opSeq, String qty) {
        return new Need(RM, opSeq, new BigDecimal(qty), "EA");
    }

    private static OpOut op(List<WoOut> out, long woId, int seq) {
        return out.stream().filter(w -> w.wo.woId == woId).findFirst().orElseThrow().ops.stream()
                .filter(o -> o.op.opSeq == seq).findFirst().orElseThrow();
    }

    private static MatOut mat(MaterialPlanner p, long woId) {
        return p.outs().stream().filter(m -> m.woId == woId).findFirst().orElseThrow();
    }

    @Test
    void WARN_은_시각을_바꾸지_않고_앞_지시가_먼저_가지며_모자란_만큼_부족() {
        // 재고 10(계산 시작), 둘째 날 08시 입고 10. 지시1(우선 1) 8개, 지시2(우선 5) 8개 — 지시2 는 첫날 10시에 2개만
        MaterialPlanner p = new MaterialPlanner(Mode.WARN,
                List.of(new Supply(RM, at(D1, 0), new BigDecimal("10")), new Supply(RM, at(D1.plusDays(1), 8), new BigDecimal("10"))),
                Map.of(1L, List.of(need(10, "8")), 2L, List.of(need(10, "8"))));
        List<WoOut> out = run(p, wo(2, 5, op(10, 2)), wo(1, 1, op(10, 2)));
        assertThat(op(out, 1, 10).start).isEqualTo(at(D1, 8));
        assertThat(op(out, 2, 10).start).isEqualTo(at(D1, 10));       // 시각은 자재와 무관
        assertThat(mat(p, 1).shortQty).isEqualByComparingTo("0");
        assertThat(mat(p, 2).allocQty).isEqualByComparingTo("2");
        assertThat(mat(p, 2).shortQty).isEqualByComparingTo("6");
        assertThat(mat(p, 2).coveredAt).isEqualTo(at(D1.plusDays(1), 8));
        assertThat(p.isShort(2)).isTrue();
        assertThat(p.isShort(1)).isFalse();
    }

    @Test
    void DELAY_는_입고_시각_뒤로_시작하고_기간_안에_안_덮이면_MAT_뒤_공정은_PREV() {
        MaterialPlanner p = new MaterialPlanner(Mode.DELAY,
                List.of(new Supply(RM, at(D1, 0), new BigDecimal("10")), new Supply(RM, at(D1.plusDays(1), 8), new BigDecimal("10"))),
                Map.of(1L, List.of(need(10, "8")), 2L, List.of(need(10, "8")), 3L, List.of(need(20, "100"))));
        List<WoOut> out = run(p, wo(1, 1, op(10, 2)), wo(2, 5, op(10, 2)), wo(3, 9, op(10, 1), op(20, 1), op(30, 1)));
        assertThat(op(out, 2, 10).start).isEqualTo(at(D1.plusDays(1), 8));
        assertThat(mat(p, 2).shortQty).isEqualByComparingTo("0");
        assertThat(op(out, 3, 10).exc).isNull();                       // 소요 없는 공정은 그대로
        assertThat(op(out, 3, 20).exc).isEqualTo(FiniteScheduler.MAT);
        assertThat(op(out, 3, 30).exc).isEqualTo(FiniteScheduler.PREV);
        assertThat(out.stream().filter(w -> w.wo.woId == 3).findFirst().orElseThrow().exc).isEqualTo(FiniteScheduler.MAT);
        MatOut m3 = mat(p, 3);
        assertThat(m3.needAt).isNull();                                // 계획 못 한 공정은 판정 없음
        assertThat(m3.allocQty).isEqualByComparingTo("0");
        assertThat(m3.shortQty).isEqualByComparingTo("0");
    }

    @Test
    void 소요는_투입_공정_시작에_필요하고_없는_순번은_첫_공정에_붙는다() {
        // 재고 0, 첫날 11시 입고 5. 10 공정(08~10) → 20 공정(10~12). 20 공정 소요 5 는 10시에 필요 → 부족, 순번 없는 소요는 10 공정에
        MaterialPlanner p = new MaterialPlanner(Mode.WARN, List.of(new Supply(RM, at(D1, 11), new BigDecimal("5"))),
                Map.of(1L, List.of(need(20, "5"), new Need(901L, null, new BigDecimal("1"), "EA"), new Need(902L, 99, BigDecimal.ONE, "EA"))));
        run(p, wo(1, 1, op(10, 2), op(20, 2)));
        MatOut m = p.outs().stream().filter(x -> x.itemId == RM).findFirst().orElseThrow();
        assertThat(m.opSeq).isEqualTo(20);
        assertThat(m.needAt).isEqualTo(at(D1, 10));
        assertThat(m.shortQty).isEqualByComparingTo("5");
        assertThat(m.coveredAt).isEqualTo(at(D1, 11));
        assertThat(p.outs().stream().filter(x -> x.itemId == 901L).findFirst().orElseThrow().opSeq).isEqualTo(10);
        MatOut odd = p.outs().stream().filter(x -> x.itemId == 902L).findFirst().orElseThrow();
        assertThat(odd.opSeq).isEqualTo(10);
        assertThat(odd.srcOpSeq).isEqualTo(99);
    }

    @Test
    void 자재가_없으면_1단계와_같은_결과() {
        List<WoOut> plain = run(null, wo(1, 1, op(10, 3)), wo(2, 5, op(10, 2)));
        MaterialPlanner p = new MaterialPlanner(Mode.DELAY, List.of(), Map.of());
        List<WoOut> withMat = run(p, wo(1, 1, op(10, 3)), wo(2, 5, op(10, 2)));
        assertThat(withMat).extracting(w -> w.end).isEqualTo(plain.stream().map(w -> w.end).toList());
        assertThat(p.outs()).isEmpty();
    }
}
