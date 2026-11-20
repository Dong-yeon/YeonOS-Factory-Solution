package io.smarthappy.module.qms;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 계측기 사용 가능 판정 (P3 QMS Q2a, docs/29 D5) — 순수 함수. 교정 화면 배지·목록과 2부 측정값 입력·품질 게이트가 같은 함수를 쓴다.
 *
 * 우선순위: 사용 중지(INACTIVE) → 유효 기록 없음(NO_RECORD) → 최근 결과 NG → 기한 초과(OVERDUE) → 임박(DUE_SOON) → OK.
 * 다음 교정일 당일까지 유효(그날은 DUE_SOON), 남은 날이 임박 일수 이하면 DUE_SOON. 제한 사용(LIMITED)도 기한으로 판정하고
 * 제한 내용은 화면이 따로 보인다. 목록 SQL(GaugeMapper status 조각)이 같은 규칙 — GaugeCalibTest 가 둘을 맞춰 본다
 */
public final class GaugeStatus {

    public enum Status {
        OK, DUE_SOON, OVERDUE, NG, NO_RECORD, INACTIVE;

        /** 측정에 써도 되는가 — 2부 측정값 입력이 막을 상태는 false (임박은 쓸 수 있다) */
        public boolean usable() {
            return this == OK || this == DUE_SOON;
        }
    }

    /** 판정 + 다음 교정일까지 남은 날(기록 없으면 null, 지났으면 음수) */
    public record Result(Status status, Long daysLeft) {
    }

    private GaugeStatus() {
    }

    /**
     * @param active    물리 자산 사용 여부
     * @param result    최근 유효 교정 결과(OK·LIMITED·NG), 기록 없으면 null
     * @param nextDue   최근 유효 교정의 다음 교정일, 기록 없으면 null
     * @param today     회사 날짜(BizClock)
     * @param dueSoonDays 임박 일수(정책 QMS_CALIB_DUE_SOON_DAYS)
     */
    public static Result of(boolean active, String result, LocalDate nextDue, LocalDate today, int dueSoonDays) {
        Long left = nextDue == null ? null : ChronoUnit.DAYS.between(today, nextDue);
        if (!active) {
            return new Result(Status.INACTIVE, left);
        }
        if (result == null || nextDue == null) {
            return new Result(Status.NO_RECORD, null);
        }
        if ("NG".equals(result)) {
            return new Result(Status.NG, left);
        }
        if (nextDue.isBefore(today)) {
            return new Result(Status.OVERDUE, left);
        }
        return new Result(nextDue.isAfter(today.plusDays(dueSoonDays)) ? Status.OK : Status.DUE_SOON, left);
    }
}
