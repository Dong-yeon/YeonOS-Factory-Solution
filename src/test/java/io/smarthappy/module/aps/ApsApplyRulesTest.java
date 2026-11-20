package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.aps.ApsDtos.CmpOp;

/** APS 2단계 순수 규칙 (docs/38 A14·A18) — 계획 시각 → 지시 날짜, 비교 구분. DB 없음 */
class ApsApplyRulesTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final LocalDate D = LocalDate.of(2026, 11, 2);

    private static OffsetDateTime t(LocalDate d, int h, int m, ZoneId z) {
        return ZonedDateTime.of(d.atTime(h, m), z).toOffsetDateTime();
    }

    @Test
    void 시작은_공장_날짜_끝은_1초_전_공장_날짜() {
        LocalDate[] r = ApsApplyService.targetDates(t(D, 8, 0, SEOUL), t(D.plusDays(1), 0, 0, SEOUL), SEOUL, false, D);
        assertThat(r).containsExactly(D, D);                                  // 자정 정각 끝 = 전날
        r = ApsApplyService.targetDates(t(D, 8, 0, SEOUL), t(D.plusDays(1), 0, 1, SEOUL), SEOUL, false, D);
        assertThat(r).containsExactly(D, D.plusDays(1));
        r = ApsApplyService.targetDates(t(D, 0, 0, SEOUL), t(D, 0, 0, SEOUL), SEOUL, false, D);   // 길이 0(준비·가공 0)
        assertThat(r).containsExactly(D, D);
    }

    @Test
    void UTC_로_보면_전날이어도_공장_시간대_날짜로() {
        // 서울 02:00 = UTC 전날 17:00
        LocalDate[] r = ApsApplyService.targetDates(t(D, 2, 0, SEOUL).withOffsetSameInstant(java.time.ZoneOffset.UTC),
                t(D, 5, 0, SEOUL).withOffsetSameInstant(java.time.ZoneOffset.UTC), SEOUL, false, D);
        assertThat(r).containsExactly(D, D);
        // DST 끝나는 날(뉴욕 2026-11-01) 늦은 밤
        LocalDate dst = LocalDate.of(2026, 11, 1);
        r = ApsApplyService.targetDates(t(dst, 1, 30, NY), t(dst, 23, 59, NY), NY, false, dst);
        assertThat(r).containsExactly(dst, dst);
    }

    @Test
    void 진행_중_지시는_완료일만_바꾸고_시작일이_뒤면_완료일로_당긴다() {
        LocalDate[] r = ApsApplyService.targetDates(t(D.plusDays(2), 8, 0, SEOUL), t(D.plusDays(3), 10, 0, SEOUL), SEOUL, true, D);
        assertThat(r).containsExactly(D, D.plusDays(3));
        r = ApsApplyService.targetDates(t(D, 8, 0, SEOUL), t(D, 10, 0, SEOUL), SEOUL, true, D.plusDays(5));
        assertThat(r).containsExactly(D, D);
    }

    private static CmpOp op(String res, OffsetDateTime s, OffsetDateTime e, String exc) {
        CmpOp o = new CmpOp();
        o.setResKind(res == null ? null : "OUTSRC".equals(res) ? "OUTSRC" : "EQUIP");
        o.setResCd(res);
        o.setStartAt(s);
        o.setEndAt(e);
        o.setExcCd(exc);
        return o;
    }

    @Test
    void 비교_구분() {
        OffsetDateTime a = t(D, 8, 0, SEOUL);
        OffsetDateTime b = t(D, 9, 0, SEOUL);
        assertThat(ApsCompareService.kind(op("M1", a, b, null), op("M1", a, b, null))).isEqualTo("SAME");
        // 같은 순간을 다른 오프셋으로 받아도 같음
        assertThat(ApsCompareService.kind(op("M1", a, b, null), op("M1", a.withOffsetSameInstant(java.time.ZoneOffset.UTC), b, null)))
                .isEqualTo("SAME");
        assertThat(ApsCompareService.kind(op("M1", a, b, null), op("M1", b, t(D, 10, 0, SEOUL), null))).isEqualTo("MOVED");
        assertThat(ApsCompareService.kind(op("M1", a, b, null), op("M2", a, b, null))).isEqualTo("RES");
        assertThat(ApsCompareService.kind(op(null, null, null, "HORIZON"), op("M1", a, b, null))).isEqualTo("SCHEDULED");
        assertThat(ApsCompareService.kind(op("M1", a, b, null), op(null, null, null, "HORIZON"))).isEqualTo("UNSCHEDULED");
        assertThat(ApsCompareService.kind(op(null, null, null, "HORIZON"), op(null, null, null, "PREV"))).isEqualTo("SAME");
        assertThat(ApsCompareService.kind(null, op("M1", a, b, null))).isEqualTo("ONLY_B");
        assertThat(ApsCompareService.kind(op("M1", a, b, null), null)).isEqualTo("ONLY_A");
    }
}
