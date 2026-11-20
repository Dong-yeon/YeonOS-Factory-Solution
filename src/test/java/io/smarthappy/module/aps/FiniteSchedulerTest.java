package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.aps.FiniteScheduler.OpIn;
import io.smarthappy.module.aps.FiniteScheduler.OpOut;
import io.smarthappy.module.aps.FiniteScheduler.Res;
import io.smarthappy.module.aps.FiniteScheduler.WoIn;
import io.smarthappy.module.aps.FiniteScheduler.WoOut;
import io.smarthappy.module.aps.ShiftCalendar.Shift;

/**
 * APS 계산 핵심 (docs/38 3장) — DB 없이: 근무 달력(자정 넘김·휴식·DST), 근무 외 건너뛰기, 규칙 순서, 빈자리 끼워 넣기,
 * 가장 빨리 끝나는 설비, 외주 리드타임, 계획 기간 넘침 → HORIZON·PREV, 늦음, 결정적 결과.
 */
class FiniteSchedulerTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate D1 = LocalDate.of(2026, 11, 2);
    private static final Res M1 = new Res("EQUIP", 1L, 100L, "M1");
    private static final Res M2 = new Res("EQUIP", 2L, 100L, "M2");

    private static Instant at(LocalDate d, int h, int m) {
        return LocalDateTime.of(d, LocalTime.of(h, m)).atZone(SEOUL).toInstant();
    }

    private static ShiftCalendar cal(List<Shift> shifts, int days) {
        return ShiftCalendar.build(shifts, SEOUL, D1, days, at(D1, 0, 0), at(D1.plusDays(days), 0, 0));
    }

    private static Shift shift(int sh, int eh, int breakMin) {
        return new Shift(LocalTime.of(sh, 0), LocalTime.of(eh, 0), breakMin);
    }

    /** 가공 시간 = 수량 × CT(초), 준비 0, 사이클당 1 */
    private static OpIn op(int seq, String qty, String ctSec) {
        OpIn o = new OpIn();
        o.opSeq = seq;
        o.procId = seq;
        o.procCd = "P" + seq;
        o.procNm = "P" + seq;
        o.nodeId = 100L;
        o.stdCtSec = ctSec == null ? null : new BigDecimal(ctSec);
        o.unitsPerCycle = 1;
        o.setupMin = BigDecimal.ZERO;
        o.qty = new BigDecimal(qty);
        return o;
    }

    private static WoIn wo(long id, int prio, LocalDate start, LocalDate end, OpIn... ops) {
        WoIn w = new WoIn();
        w.woId = id;
        w.woNo = "WO-" + id;
        w.priority = prio;
        w.planStart = start;
        w.planEnd = end;
        w.ops.addAll(List.of(ops));
        return w;
    }

    private static FiniteScheduler scheduler(ShiftCalendar c, int days, List<Res> res) {
        Function<OpIn, List<Res>> cand = o -> res;
        return new FiniteScheduler(c, SEOUL, at(D1, 0, 0), at(D1.plusDays(days), 0, 0), 24 * 3600L, cand);
    }

    private static OpOut only(List<WoOut> out, long woId) {
        return out.stream().filter(w -> w.wo.woId == woId).findFirst().orElseThrow().ops.get(0);
    }

    // ---------- 달력 ----------

    @Test
    void 자정_넘는_조는_다음_날_끝나고_맞닿은_조는_합치며_휴식은_조_끝에서_뺀다() {
        ShiftCalendar c = cal(List.of(shift(8, 20, 0), shift(20, 8, 60)), 2);
        // 전날 야간(20~07시, 휴식 60분 → 07시)의 뒷부분 00~07, 주간 08~20 + 야간 20~다음 날 07 은 하나로, 둘째 날 주간·야간은 계획 끝(24시)에서 잘림
        assertThat(c.windows()).extracting(ShiftCalendar.Window::start)
                .containsExactly(at(D1, 0, 0), at(D1, 8, 0), at(D1.plusDays(1), 8, 0));
        assertThat(c.windows()).extracting(ShiftCalendar.Window::end)
                .containsExactly(at(D1, 7, 0), at(D1.plusDays(1), 7, 0), at(D1.plusDays(2), 0, 0));
    }

    @Test
    void DST_봄_전환일은_근무조가_한_시간_짧다() {
        ZoneId ny = ZoneId.of("America/New_York");
        LocalDate dst = LocalDate.of(2026, 3, 8);   // 02:00 → 03:00
        ShiftCalendar c = ShiftCalendar.build(List.of(shift(0, 8, 0)), ny, dst, 1, dst.atStartOfDay(ny).toInstant(),
                dst.plusDays(1).atStartOfDay(ny).toInstant());
        assertThat(c.windows()).hasSize(1);
        ShiftCalendar.Window w = c.windows().get(0);
        assertThat(w.end().getEpochSecond() - w.start().getEpochSecond()).isEqualTo(7 * 3600L);
        // 8시간 일은 그날 안에 끝나지 않는다(기간 1일)
        assertThat(c.finish(w.start(), 8 * 3600L)).isNull();
    }

    @Test
    void 공정은_근무_외_시간을_건너뛰어_다음_근무조에_이어진다() {
        ShiftCalendar c = cal(List.of(shift(8, 17, 0)), 3);
        // 10시간 일: 첫날 08~17(9시간) + 다음 날 08~09
        assertThat(c.finish(at(D1, 8, 0), 10 * 3600L)).isEqualTo(at(D1.plusDays(1), 9, 0));
        assertThat(c.nextWorking(at(D1, 18, 0))).isEqualTo(at(D1.plusDays(1), 8, 0));
        assertThat(c.nextWorking(at(D1.plusDays(3), 0, 0))).isNull();
    }

    // ---------- 스케줄 ----------

    @Test
    void 우선순위가_먼저_자리를_잡고_뒤_지시는_앞의_빈자리를_쓴다() {
        ShiftCalendar c = cal(List.of(shift(8, 17, 0)), 3);
        // A: 우선 1, 둘째 날부터, 4시간 / B: 우선 5, 첫날부터, 2시간 → B 는 A 뒤가 아니라 첫날 빈자리
        WoIn a = wo(1, 1, D1.plusDays(1), D1.plusDays(2), op(10, "4", "3600"));
        WoIn b = wo(2, 5, D1, D1.plusDays(2), op(10, "2", "3600"));
        List<WoOut> out = scheduler(c, 3, List.of(M1)).schedule(List.of(b, a));
        assertThat(out).extracting(w -> w.wo.woId).containsExactly(1L, 2L);   // 규칙 순서
        assertThat(only(out, 1).start).isEqualTo(at(D1.plusDays(1), 8, 0));
        assertThat(only(out, 2).start).isEqualTo(at(D1, 8, 0));
        assertThat(only(out, 2).end).isEqualTo(at(D1, 10, 0));
    }

    @Test
    void 빈자리가_모자라면_잡힌_일_뒤로_간다() {
        ShiftCalendar c = cal(List.of(shift(8, 17, 0)), 3);
        WoIn a = wo(1, 1, D1, D1.plusDays(2), op(10, "1", "3600"));      // 08~09
        WoIn b = wo(2, 2, D1, D1.plusDays(2), op(10, "1", "3600"));      // 09~10
        WoIn x = wo(3, 1, D1, D1.plusDays(2), op(10, "1", "1800"));      // 같은 우선순위·납기 → 번호 순(WO-1, WO-3)
        List<WoOut> out = scheduler(c, 3, List.of(M1)).schedule(List.of(b, x, a));
        assertThat(only(out, 1).start).isEqualTo(at(D1, 8, 0));
        assertThat(only(out, 3).start).isEqualTo(at(D1, 9, 0));
        assertThat(only(out, 2).start).isEqualTo(at(D1, 9, 30));
    }

    @Test
    void 공정_순서_앞_공정이_끝나야_뒤_공정이_시작하고_가장_빨리_끝나는_설비를_고른다() {
        ShiftCalendar c = cal(List.of(shift(8, 17, 0)), 3);
        WoIn busy = wo(1, 1, D1, D1.plusDays(2), op(10, "3", "3600"));                 // M1 08~11
        WoIn w = wo(2, 2, D1, D1.plusDays(2), op(10, "1", "3600"), op(20, "1", "1800"));
        List<WoOut> out = scheduler(c, 3, List.of(M1, M2)).schedule(List.of(busy, w));
        WoOut r = out.get(1);
        assertThat(r.ops.get(0).res).isEqualTo(M2);                                     // M1 은 11시까지 바쁨
        assertThat(r.ops.get(0).start).isEqualTo(at(D1, 8, 0));
        assertThat(r.ops.get(1).start).isEqualTo(at(D1, 9, 0));                          // 앞 공정 끝
        assertThat(r.ops.get(1).res).isEqualTo(M2);                                     // 09시에 M1 은 아직 바쁨
        assertThat(r.end).isEqualTo(at(D1, 9, 30));
        assertThat(r.late).isFalse();
    }

    @Test
    void 준비_시간은_사이클_올림_가공과_더하고_진행_중_공정은_준비를_빼며_외주는_리드타임() {
        ShiftCalendar c = cal(List.of(shift(8, 17, 0)), 3);
        OpIn a = op(10, "5", "30");
        a.unitsPerCycle = 2;                 // 사이클 3 × 30초 = 90초
        a.setupMin = new BigDecimal("10.5"); // 630초
        OpIn ext = op(20, "5", null);
        ext.outsrc = true;
        ext.nodeId = null;
        OpIn run = op(10, "5", "30");
        run.setupMin = BigDecimal.TEN;
        run.running = true;
        List<WoOut> out = scheduler(c, 3, List.of(M1)).schedule(List.of(wo(1, 1, D1, D1.plusDays(2), a, ext),
                wo(2, 2, D1, D1.plusDays(2), run)));
        OpOut o1 = out.get(0).ops.get(0);
        assertThat(o1.setupSec).isEqualTo(630);
        assertThat(o1.runSec).isEqualTo(90);
        assertThat(o1.end).isEqualTo(at(D1, 8, 0).plusSeconds(720));
        OpOut o2 = out.get(0).ops.get(1);
        assertThat(o2.res.kind()).isEqualTo("OUTSRC");
        assertThat(o2.end).isEqualTo(o1.end.plusSeconds(24 * 3600L));                   // 근무조와 무관한 달력 시간
        assertThat(out.get(1).ops.get(0).setupSec).isZero();
    }

    @Test
    void 계획_기간_안에_안_끝나면_HORIZON_이고_뒤_공정은_PREV_이며_CT_없으면_NO_CT() {
        ShiftCalendar c = cal(List.of(shift(8, 17, 0)), 1);
        WoIn big = wo(1, 1, D1, D1, op(10, "10", "3600"), op(20, "1", "60"));
        WoIn noCt = wo(2, 2, D1, D1, op(10, "1", null), op(20, "1", "60"));
        List<WoOut> out = scheduler(c, 1, List.of(M1)).schedule(List.of(big, noCt));
        assertThat(out.get(0).ops).extracting(o -> o.exc).containsExactly("HORIZON", "PREV");
        assertThat(out.get(0).exc).isEqualTo("HORIZON");
        assertThat(out.get(0).start).isNull();
        assertThat(out.get(1).ops).extracting(o -> o.exc).containsExactly("NO_CT", "PREV");
    }

    @Test
    void 납기일_다음_날_00시를_넘으면_늦음이고_같은_입력은_같은_결과() {
        ShiftCalendar c = cal(List.of(shift(8, 17, 0)), 3);
        List<WoIn> in = new ArrayList<>();
        in.add(wo(1, 1, D1, D1, op(10, "10", "3600")));            // 첫날 9시간 + 둘째 날 1시간 → 늦음
        in.add(wo(2, 1, D1, D1.plusDays(2), op(10, "1", "3600")));
        List<WoOut> a = scheduler(c, 3, List.of(M1, M2)).schedule(in);
        List<WoOut> b = scheduler(c, 3, List.of(M1, M2)).schedule(in);
        assertThat(a.get(0).late).isTrue();
        assertThat(a.get(1).late).isFalse();
        Map<Long, Instant> ea = Map.of(a.get(0).wo.woId, a.get(0).end, a.get(1).wo.woId, a.get(1).end);
        Map<Long, Instant> eb = Map.of(b.get(0).wo.woId, b.get(0).end, b.get(1).wo.woId, b.get(1).end);
        assertThat(ea).isEqualTo(eb);
        assertThat(a.get(1).ops.get(0).res).isEqualTo(b.get(1).ops.get(0).res);
    }
}
