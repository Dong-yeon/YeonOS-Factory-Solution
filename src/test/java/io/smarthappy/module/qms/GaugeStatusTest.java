package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.smarthappy.module.qms.GaugeStatus.Status;

/** GaugeStatus 판정 표 (P3 QMS Q2a, docs/29 D5) — 우선순위·경계(오늘 = 기한일, 임박 일수 끝) */
class GaugeStatusTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 21);

    @ParameterizedTest(name = "{0}: active={1} result={2} 기한 오늘+({3}) 임박{4} → {5} ({6})")
    @CsvSource(nullValues = "-", value = {
            "사용 중지가 먼저(NG 여도),        false, NG,      -5,  30, INACTIVE,  -5",
            "사용 중지 + 기록 없음,           false, -,       -,   30, INACTIVE,  -",
            "기록 없음,                      true,  -,       -,   30, NO_RECORD, -",
            "NG 는 기한과 무관,               true,  NG,      400, 30, NG,        400",
            "어제가 기한 = 초과,              true,  OK,      -1,  30, OVERDUE,   -1",
            "오늘이 기한 = 아직 유효(임박),     true,  OK,      0,   30, DUE_SOON,  0",
            "임박 일수 0 이어도 당일은 임박,    true,  OK,      0,   0,  DUE_SOON,  0",
            "임박 일수 0 이면 내일 기한은 OK,   true,  OK,      1,   0,  OK,        1",
            "임박 끝(30일 뒤) = 임박,          true,  OK,      30,  30, DUE_SOON,  30",
            "임박 밖(31일 뒤) = OK,           true,  OK,      31,  30, OK,        31",
            "제한 사용도 기한으로,             true,  LIMITED, 100, 30, OK,        100",
            "제한 사용 초과,                  true,  LIMITED, -3,  30, OVERDUE,   -3"})
    void 판정(String name, boolean active, String result, Integer offset, int dueSoon, Status expected, Long daysLeft) {
        LocalDate due = offset == null ? null : TODAY.plusDays(offset);
        GaugeStatus.Result r = GaugeStatus.of(active, result, due, TODAY, dueSoon);
        assertThat(r.status()).as(name).isEqualTo(expected);
        assertThat(r.daysLeft()).as(name).isEqualTo(daysLeft);
    }

    @ParameterizedTest
    @CsvSource({"OK, true", "DUE_SOON, true", "OVERDUE, false", "NG, false", "NO_RECORD, false", "INACTIVE, false"})
    void 측정에_쓸수_있는_상태(Status s, boolean usable) {
        assertThat(s.usable()).isEqualTo(usable);
    }
}
