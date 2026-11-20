package io.smarthappy.module.master.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.master.workforce.WorkDayResolver.WorkDay;
import io.smarthappy.module.master.workforce.WorkforceDtos.ShiftRow;
import io.smarthappy.platform.time.BizClock;

/** 작업일·근무조 계산(순수) + 현지 시각 변환의 DST 경계 (docs/25 4.2) */
class WorkDayResolverTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId NY = ZoneId.of("America/New_York");

    private static ShiftRow shift(String cd, String start, String end, int offset) {
        ShiftRow s = new ShiftRow();
        s.setShiftCd(cd);
        s.setShiftNm(cd + "조");
        s.setStartTime(LocalTime.parse(start));
        s.setEndTime(LocalTime.parse(end));
        s.setDayOffset(offset);
        return s;
    }

    private static WorkDay at(String local, ZoneId zone, List<ShiftRow> shifts) {
        return WorkDayResolver.resolve(LocalDateTime.parse(local).atZone(zone).toInstant(), zone, shifts);
    }

    @Test
    void 주간_야간_2교대_자정_넘는_야간은_시작한_날() {
        List<ShiftRow> two = List.of(shift("D", "08:00", "20:00", 0), shift("N", "20:00", "08:00", 0));
        assertThat(at("2026-10-20T08:00", SEOUL, two)).extracting(WorkDay::workDay, WorkDay::shiftCd)
                .containsExactly(LocalDate.parse("2026-10-20"), "D");
        assertThat(at("2026-10-20T19:59", SEOUL, two).shiftCd()).isEqualTo("D");
        assertThat(at("2026-10-20T20:00", SEOUL, two)).extracting(WorkDay::workDay, WorkDay::shiftCd)
                .containsExactly(LocalDate.parse("2026-10-20"), "N");
        // 다음 날 새벽 03:00 = 전날 시작한 야간
        assertThat(at("2026-10-21T03:00", SEOUL, two)).extracting(WorkDay::workDay, WorkDay::shiftCd)
                .containsExactly(LocalDate.parse("2026-10-20"), "N");
        assertThat(at("2026-10-21T07:59", SEOUL, two).workDay()).isEqualTo(LocalDate.parse("2026-10-20"));
        // 월말·연말 경계
        assertThat(at("2027-01-01T02:00", SEOUL, two).workDay()).isEqualTo(LocalDate.parse("2026-12-31"));
        assertThat(at("2028-03-01T02:00", SEOUL, two).workDay()).isEqualTo(LocalDate.parse("2028-02-29"));
    }

    @Test
    void 삼교대_3조는_자정_뒤에_시작해도_오프셋으로_전날_작업일() {
        List<ShiftRow> three = List.of(shift("A", "08:00", "16:00", 0), shift("B", "16:00", "00:00", 0),
                shift("C", "00:00", "08:00", -1));
        assertThat(at("2026-10-20T23:59", SEOUL, three)).extracting(WorkDay::workDay, WorkDay::shiftCd)
                .containsExactly(LocalDate.parse("2026-10-20"), "B");
        assertThat(at("2026-10-21T00:00", SEOUL, three)).extracting(WorkDay::workDay, WorkDay::shiftCd)
                .containsExactly(LocalDate.parse("2026-10-20"), "C");
        assertThat(at("2026-10-21T08:00", SEOUL, three)).extracting(WorkDay::workDay, WorkDay::shiftCd)
                .containsExactly(LocalDate.parse("2026-10-21"), "A");
    }

    @Test
    void 근무조_밖_시각은_근무조_없음_작업일은_현지_날짜() {
        List<ShiftRow> day = List.of(shift("D", "08:00", "17:00", 0));
        WorkDay w = at("2026-10-20T22:00", SEOUL, day);
        assertThat(w.shiftCd()).isNull();
        assertThat(w.workDay()).isEqualTo(LocalDate.parse("2026-10-20"));
        assertThat(at("2026-10-20T03:00", SEOUL, List.of()).workDay()).isEqualTo(LocalDate.parse("2026-10-20"));
    }

    @Test
    void 같은_시각도_공장_시간대가_다르면_작업일이_다르다() {
        Instant t = Instant.parse("2026-10-20T13:00:00Z");           // 서울 22:00, 뉴욕 09:00
        List<ShiftRow> two = List.of(shift("D", "08:00", "20:00", 0), shift("N", "20:00", "08:00", 0));
        assertThat(WorkDayResolver.resolve(t, SEOUL, two)).extracting(WorkDay::workDay, WorkDay::shiftCd)
                .containsExactly(LocalDate.parse("2026-10-20"), "N");
        assertThat(WorkDayResolver.resolve(t, NY, two)).extracting(WorkDay::workDay, WorkDay::shiftCd)
                .containsExactly(LocalDate.parse("2026-10-20"), "D");
    }

    @Test
    void DST_없는_시각은_null_겹치는_시각은_앞쪽_서머타임() {
        // 2026-03-08 02:00 → 03:00 (뉴욕 봄): 02:30 은 없다
        assertThat(BizClock.toInstant(LocalDateTime.parse("2026-03-08T02:30"), NY)).isNull();
        assertThat(BizClock.toInstant(LocalDateTime.parse("2026-03-08T03:00"), NY)).isEqualTo(Instant.parse("2026-03-08T07:00:00Z"));
        // 2026-11-01 01:30 은 두 번(EDT -4, EST -5) → 앞쪽 EDT
        assertThat(BizClock.toInstant(LocalDateTime.parse("2026-11-01T01:30"), NY)).isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
        // 서울은 DST 없음
        assertThat(BizClock.toInstant(LocalDateTime.parse("2026-03-08T02:30"), SEOUL)).isNotNull();
        // 가을 겹침 시간의 야간 조: 같은 01:30 두 번 모두 그 야간(작업일 = 전날)
        List<ShiftRow> two = List.of(shift("D", "08:00", "20:00", 0), shift("N", "20:00", "08:00", 0));
        assertThat(WorkDayResolver.resolve(Instant.parse("2026-11-01T05:30:00Z"), NY, two).workDay())
                .isEqualTo(LocalDate.parse("2026-10-31"));
        assertThat(WorkDayResolver.resolve(Instant.parse("2026-11-01T06:30:00Z"), NY, two).workDay())
                .isEqualTo(LocalDate.parse("2026-10-31"));
    }

    @Test
    void 겹침_판정은_자정_넘김을_포함한다() {
        LocalTime t8 = LocalTime.parse("08:00");
        LocalTime t20 = LocalTime.parse("20:00");
        assertThat(ShiftService.overlaps(t8, t20, t20, t8)).isFalse();                                       // 맞닿음
        assertThat(ShiftService.overlaps(t20, t8, LocalTime.parse("06:00"), LocalTime.parse("14:00"))).isTrue();   // 새벽 06~08
        assertThat(ShiftService.overlaps(t20, t8, LocalTime.parse("22:00"), LocalTime.parse("02:00"))).isTrue();
        assertThat(ShiftService.overlaps(LocalTime.parse("16:00"), LocalTime.parse("00:00"), LocalTime.parse("00:00"),
                t8)).isFalse();
        assertThat(ShiftService.length(t20, t8)).isEqualTo(720);
        assertThat(ShiftService.length(LocalTime.parse("16:00"), LocalTime.parse("00:00"))).isEqualTo(480);
    }
}
