package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.aps.FiniteScheduler.OpIn;
import io.smarthappy.module.aps.FiniteScheduler.OpOut;
import io.smarthappy.module.aps.FiniteScheduler.Pin;
import io.smarthappy.module.aps.FiniteScheduler.Res;
import io.smarthappy.module.aps.FiniteScheduler.WoIn;
import io.smarthappy.module.aps.FiniteScheduler.WoOut;
import io.smarthappy.module.aps.ShiftCalendar.Shift;

/**
 * APS 3단계 계산 (docs/38 A21·A23) — DB 없이: 고정 먼저 자리 잡기·다른 공정이 피해 가기, 근무 외·겹침 → PIN_MOVED,
 * 후보 아닌 자원·기간 밖 → PIN_IGNORED(일반 계산), 앞 공정보다 이르면 PIN_PREV(그대로), 외주 고정, 수주 납기로 순서·늦음.
 */
class FiniteSchedulerPinTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate D1 = LocalDate.of(2026, 11, 2);
    private static final Res M1 = new Res("EQUIP", 1L, 100L, "M1");
    private static final Res M2 = new Res("EQUIP", 2L, 100L, "M2");
    private static final Res M9 = new Res("EQUIP", 9L, 100L, "M9");

    private static Instant at(LocalDate d, int h, int m) {
        return LocalDateTime.of(d, LocalTime.of(h, m)).atZone(SEOUL).toInstant();
    }

    /** 주간 08~17, 3일 */
    private static FiniteScheduler scheduler(List<Res> res) {
        ShiftCalendar c = ShiftCalendar.build(List.of(new Shift(LocalTime.of(8, 0), LocalTime.of(17, 0), 0)), SEOUL, D1, 3,
                at(D1, 0, 0), at(D1.plusDays(3), 0, 0));
        return new FiniteScheduler(c, SEOUL, at(D1, 0, 0), at(D1.plusDays(3), 0, 0), 24 * 3600L, o -> res);
    }

    /** 가공 = 수량 시간(CT 3600초) */
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

    private static OpIn pinned(OpIn o, Res r, Instant start) {
        o.pin = new Pin(77L + o.opSeq, r, start);
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

    private static OpOut op(List<WoOut> out, long woId, int seq) {
        return out.stream().filter(w -> w.wo.woId == woId).findFirst().orElseThrow().ops.stream()
                .filter(o -> o.op.opSeq == seq).findFirst().orElseThrow();
    }

    @Test
    void 고정은_우선순위가_낮아도_먼저_자리_잡고_다른_공정은_피해_간다() {
        // 우선 9 지시를 M1 08~10 에 고정, 우선 1 지시(3시간)는 M1 만 후보 → 10시 뒤
        List<WoOut> out = scheduler(List.of(M1)).schedule(List.of(
                wo(1, 1, op(10, 3)),
                wo(2, 9, pinned(op(10, 2), M1, at(D1, 8, 0)))));
        OpOut p = op(out, 2, 10);
        assertThat(p.start).isEqualTo(at(D1, 8, 0));
        assertThat(p.pinId).isEqualTo(87L);
        assertThat(p.warn).isNull();
        assertThat(op(out, 1, 10).start).isEqualTo(at(D1, 10, 0));
    }

    @Test
    void 근무_외나_다른_고정과_겹치면_다음_빈자리로_밀고_알린다() {
        List<WoOut> out = scheduler(List.of(M1)).schedule(List.of(
                wo(1, 5, pinned(op(10, 2), M1, at(D1, 6, 0))),        // 근무 전 → 08시
                wo(2, 5, pinned(op(10, 2), M1, at(D1, 9, 0)))));      // 1번(08~10)과 겹침 → 10시
        assertThat(op(out, 1, 10).start).isEqualTo(at(D1, 8, 0));
        assertThat(op(out, 1, 10).warn).isEqualTo(FiniteScheduler.PIN_MOVED);
        assertThat(op(out, 2, 10).start).isEqualTo(at(D1, 10, 0));
        assertThat(op(out, 2, 10).warn).isEqualTo(FiniteScheduler.PIN_MOVED);
    }

    @Test
    void 후보가_아닌_자원이나_기간_밖_고정은_무시하고_일반_계산() {
        List<WoOut> out = scheduler(List.of(M1, M2)).schedule(List.of(
                wo(1, 5, pinned(op(10, 2), M9, at(D1, 13, 0))),
                wo(2, 5, pinned(op(10, 2), M2, at(D1.plusDays(5), 8, 0)))));
        OpOut a = op(out, 1, 10);
        assertThat(a.warn).isEqualTo(FiniteScheduler.PIN_IGNORED);
        assertThat(a.pinId).isEqualTo(87L);
        assertThat(a.start).isEqualTo(at(D1, 8, 0));
        assertThat(op(out, 2, 10).warn).isEqualTo(FiniteScheduler.PIN_IGNORED);
        assertThat(op(out, 2, 10).exc).isNull();
    }

    @Test
    void 앞_공정보다_이른_고정은_그대로_두고_PIN_PREV_뒤_공정은_고정_끝_뒤() {
        // 10 공정 3시간(08~11), 20 공정을 09시에 고정(앞 공정보다 이르다), 30 공정은 20 끝(10시)과 10 끝(11시) 중 늦은 뒤
        List<WoOut> out = scheduler(List.of(M1, M2)).schedule(List.of(
                wo(1, 5, op(10, 3), pinned(op(20, 1), M2, at(D1, 9, 0)), op(30, 1))));
        assertThat(op(out, 1, 20).start).isEqualTo(at(D1, 9, 0));
        assertThat(op(out, 1, 20).warn).isEqualTo(FiniteScheduler.PIN_PREV);
        assertThat(op(out, 1, 30).start).isEqualTo(at(D1, 11, 0));
        WoOut w = out.get(0);
        assertThat(w.start).isEqualTo(at(D1, 8, 0));
        assertThat(w.end).isEqualTo(op(out, 1, 30).end);
    }

    @Test
    void 외주_공정_고정은_시각만_쓴다() {
        OpIn plate = op(20, 0);
        plate.outsrc = true;
        List<WoOut> out = scheduler(List.of(M1)).schedule(List.of(
                wo(1, 5, op(10, 1), pinned(plate, new Res("OUTSRC", null, null, ""), at(D1, 20, 0)))));
        assertThat(op(out, 1, 20).start).isEqualTo(at(D1, 20, 0));
        assertThat(op(out, 1, 20).end).isEqualTo(at(D1.plusDays(1), 20, 0));
        assertThat(op(out, 1, 20).warn).isNull();
        // 외주 공정을 설비에 고정 → 무시
        OpIn plate2 = op(20, 0);
        plate2.outsrc = true;
        out = scheduler(List.of(M1)).schedule(List.of(wo(1, 5, op(10, 1), pinned(plate2, M1, at(D1, 20, 0)))));
        assertThat(op(out, 1, 20).warn).isEqualTo(FiniteScheduler.PIN_IGNORED);
        assertThat(op(out, 1, 20).start).isEqualTo(at(D1, 9, 0));
    }

    @Test
    void 납기가_있으면_순서와_늦음_모두_납기로() {
        WoIn a = wo(1, 5, op(10, 4));     // 지시 완료일 셋째 날, 수주 납기 첫날 → 먼저·늦음 판정도 납기
        a.due = D1.minusDays(1);
        WoIn b = wo(2, 5, op(10, 4));
        b.due = D1.plusDays(1);
        List<WoOut> out = scheduler(List.of(M1)).schedule(List.of(b, a));
        assertThat(out.get(0).wo.woId).isEqualTo(1L);
        assertThat(out.get(0).late).isTrue();         // 납기 전날 → 첫날 일은 늦음
        assertThat(out.get(1).late).isFalse();
    }
}
