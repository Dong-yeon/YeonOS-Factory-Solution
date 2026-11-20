package io.smarthappy.module.pms;

import static io.smarthappy.module.pms.PmsInputs.date;
import static io.smarthappy.module.pms.PmsInputs.fieldError;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import lombok.RequiredArgsConstructor;

/**
 * 근무일 기준 기간 입력 보조 (docs/32 4.25, 사용자 결정 P-13 = docs/48 ④ A) — 화면이 "근무일 N일" 또는 계획 공수로 종료일을 채울 때 부른다.
 * 회사 휴일 달력(WorkCalendar, 공장 없음 — 지연 판정과 같은 층)으로 계산만 하고 아무것도 저장하지 않는다. 저장은 날짜, lag·후행 밀기·임박·
 * 기준선 대비 일수는 달력일 그대로(D4·D16 은 "입력 보조만 근무일" 로 좁게 바뀜).
 */
@Service
@RequiredArgsConstructor
public class WorkdayService {

    private final PmsDays days;
    private final PolicyService policy;

    /** start·end = 양 끝(근무일 아니어도 그대로), workdays = [start, end] 근무일 수, days = 공수에서 구한 근무일 수(공수로 물었을 때) */
    public record WorkdayView(LocalDate start, LocalDate end, int workdays, Integer days, int hoursPerDay) {
    }

    /**
     * start + 하나만: days(근무일 수 → 종료일) / hours(계획 공수 ÷ 하루 시간, 올림 → 근무일 수 → 종료일) / end(두 날짜 사이 근무일 수).
     * 둘 이상이거나 하나도 없으면 칸 오류
     */
    @Transactional(readOnly = true)
    public WorkdayView calc(String start, Integer workdays, BigDecimal hours, String end) {
        FieldErrors e = FieldErrors.create();
        LocalDate s = date(start, "start", true, e);
        LocalDate en = date(end, "end", false, e);
        int given = (workdays == null ? 0 : 1) + (hours == null ? 0 : 1) + (end == null || end.isBlank() ? 0 : 1);
        if (given != 1) {
            e.add("days", "PMS_WORKDAY_ONE_OF");
        }
        if (workdays != null && (workdays < 1 || workdays > PmsDays.MAX_WORKDAYS)) {
            e.add("days", "RANGE", 1, PmsDays.MAX_WORKDAYS);
        }
        if (hours != null && (hours.signum() <= 0 || hours.compareTo(PmsInputs.MAX_HOURS) > 0)) {
            e.add("hours", "RANGE", 0, PmsInputs.MAX_HOURS.intValue());
        }
        if (s != null && en != null && en.isBefore(s)) {
            e.add("end", "PMS_END_BEFORE_START");
        } else if (s != null && en != null && ChronoUnit.DAYS.between(s, en) >= PmsDays.MAX_SPAN_DAYS) {
            e.add("end", "RANGE_TOO_LONG", ChronoUnit.DAYS.between(s, en) + 1, PmsDays.MAX_SPAN_DAYS);
        }
        e.throwIfAny();
        int hpd = policy.intValue(Policies.PMS_HOURS_PER_DAY);
        if (en != null) {
            return new WorkdayView(s, en, days.workdaysBetween(s, en), null, hpd);
        }
        int n = workdays != null ? workdays
                : Math.max(1, hours.divide(BigDecimal.valueOf(hpd), 0, RoundingMode.CEILING).intValueExact());
        if (n > PmsDays.MAX_WORKDAYS) {
            throw fieldError("hours", "RANGE", 0, (long) PmsDays.MAX_WORKDAYS * hpd);
        }
        LocalDate end2 = days.endAfterWorkdays(s, n);
        if (end2 == null) {
            throw fieldError("days", "PMS_WORKDAY_NONE", PmsDays.MAX_SPAN_DAYS);
        }
        return new WorkdayView(s, end2, n, workdays == null ? n : null, hpd);
    }
}
