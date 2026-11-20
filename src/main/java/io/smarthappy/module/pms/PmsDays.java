package io.smarthappy.module.pms;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.smarthappy.module.master.workforce.WorkCalendar;
import io.smarthappy.module.master.workforce.WorkCalendar.DayPlan;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 지연 판정 기준일 (docs/32 PM6·D16) — 오늘 이전(포함) 가장 가까운 근무일(회사 휴일 달력, 공장 없음).
 * 지연 = 끝나지 않았고 계획 종료(기한) &lt; 기준일 = "계획 종료 다음 근무일이 지나야 지연".
 * 주간 쉬는 날 NONE + 회사 휴일 없음이면 기준일 = 오늘 — PM5 까지와 같다. 다른 날짜 계산(SOP 까지·기준선 대비·임박)은 달력일 그대로
 */
@Component
@RequiredArgsConstructor
public class PmsDays {

    /** 이만큼 거슬러 근무일이 없으면(긴 휴무) 오늘을 쓴다 */
    static final int LOOKBACK = 31;

    private final WorkCalendar calendar;
    private final BizClock clock;

    public LocalDate today() {
        return clock.today();
    }

    /** 오늘(회사 시간대) 기준 지연 기준일 — 트랜잭션 안에서 */
    public LocalDate lateRef() {
        return lateRef(clock.today());
    }

    public LocalDate lateRef(LocalDate today) {
        Map<LocalDate, DayPlan> plan = calendar.range(null, today.minusDays(LOOKBACK), today);
        for (LocalDate d = today; !d.isBefore(today.minusDays(LOOKBACK)); d = d.minusDays(1)) {
            if (plan.get(d).workday()) {
                return d;
            }
        }
        return today;
    }

    // ------------------------------------------------------------------ 근무일 입력 보조(docs/32 4.25, 사용자 결정 P-13)

    /** 달력을 한 번에 읽는 날 수 — WorkCalendar.MAX_DAYS(400) 안 */
    static final int CHUNK = 366;
    /** 근무일 수 상한(입력 보조) — 몇 년짜리 작업은 없다 */
    static final int MAX_WORKDAYS = 1000;
    /** 이만큼 지나도 n 번째 근무일이 없으면(달력이 거의 다 휴일) 오류 */
    static final int MAX_SPAN_DAYS = 3650;

    /**
     * start 부터 근무일로 n 번째 날(start 가 근무일이면 그날이 1번째). 회사 휴일 달력(공장 없음) — 입력 보조 전용,
     * 저장·지연 외 계산(lag·밀기·임박·기준선)은 달력일 그대로(D4·D16). 트랜잭션 안에서
     */
    public LocalDate endAfterWorkdays(LocalDate start, int n) {
        int left = n;
        LocalDate from = start;
        while (ChronoUnit.DAYS.between(start, from) < MAX_SPAN_DAYS) {
            LocalDate to = from.plusDays(CHUNK - 1);
            Map<LocalDate, DayPlan> plan = calendar.range(null, from, to);
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (plan.get(d).workday() && --left == 0) {
                    return d;
                }
            }
            from = to.plusDays(1);
        }
        return null;
    }

    /** [start, end] 안의 근무일 수(양 끝 포함) — end ≥ start, 기간 ≤ MAX_SPAN_DAYS 는 부르는 쪽이 확인 */
    public int workdaysBetween(LocalDate start, LocalDate end) {
        int n = 0;
        for (LocalDate from = start; !from.isAfter(end); from = from.plusDays(CHUNK)) {
            LocalDate to = from.plusDays(CHUNK - 1).isAfter(end) ? end : from.plusDays(CHUNK - 1);
            for (DayPlan p : calendar.range(null, from, to).values()) {
                if (p.workday()) {
                    n++;
                }
            }
        }
        return n;
    }
}
