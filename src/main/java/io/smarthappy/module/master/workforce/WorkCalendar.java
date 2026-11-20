package io.smarthappy.module.master.workforce;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.workforce.WorkCalDtos.CalRow;
import io.smarthappy.module.master.workforce.WorkforceDtos.ShiftRow;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.PolicyService;
import lombok.RequiredArgsConstructor;

/**
 * 공장 휴일 달력 읽기 (docs/25 4.15) — APS·CMS·PMS 가 읽는 유일한 입구. 각 모듈은 달력 표를 따로 만들지 않는다(docs/38 A4).
 *
 * 우선순위(위가 이긴다): 공장·근무조 예외 &gt; 공장 하루 예외 &gt; 회사 예외 &gt; 주간 쉬는 날(정책 WEEKLY_OFF) &gt; 일한다.
 * 날짜는 작업일(WorkDayResolver 와 같은 뜻 — 야간조는 시작한 날 + day_offset 의 행으로 판정).
 * 원장·작업일 판정은 바꾸지 않는다 — 계획·주기·지연 계산용(D24).
 */
@Component
@RequiredArgsConstructor
public class WorkCalendar {

    /** 한 번에 펼치는 최대 일수(APS 계획 기간·CMS 주기 넉넉히) */
    public static final int MAX_DAYS = 400;
    static final String POLICY = "WEEKLY_OFF";

    private final WorkCalMapper mapper;
    private final WorkforceMapper workforce;
    private final PolicyService policies;

    /** 근무조 하나의 그날 — 작업일 기준. 시작·끝은 공장 현지 시각(끝 ≤ 시작이면 다음 날 끝) */
    public record ShiftDay(String shiftCd, String shiftNm, LocalTime start, LocalTime end, int dayOffset,
            int breakMin) {
    }

    /** 하루 — 일하는가(하루 판정)·그날 일하는 근무조·판정 근거(WORK_CAL·COMP_CAL·WEEKLY·DEFAULT) */
    public record DayPlan(LocalDate day, boolean workday, String source, String reason, List<ShiftDay> shifts) {
    }

    /** 그날 일하는가 — plantCd null 이면 회사 층만(공장이 없는 모듈, PMS) */
    @Transactional(readOnly = true)
    public boolean isWorkday(String plantCd, LocalDate day) {
        return range(plantCd, day, day).get(day).workday();
    }

    /** 그날 일하는 사용 중 근무조 — 공장 필수 */
    @Transactional(readOnly = true)
    public List<ShiftDay> shiftsOn(String plantCd, LocalDate day) {
        if (plantCd == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add("plantCd", "REQUIRED").list());
        }
        return range(plantCd, day, day).get(day).shifts();
    }

    /** 기간 [from, to] 의 날마다 — 쿼리 3번(예외·근무조·정책), 최대 {@link #MAX_DAYS}일 */
    @Transactional(readOnly = true)
    public Map<LocalDate, DayPlan> range(String plantCd, LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("to", "RANGE_TOO_LONG", MAX_DAYS).list());
        }
        String weekly = plantCd == null ? policies.value(POLICY) : policies.value(POLICY, plantCd);
        List<ShiftRow> shifts = plantCd == null ? List.of() : workforce.findShifts(plantCd, true);
        return plan(plantCd, from, to, weekly, shifts, mapper.findActive(plantCd, from, to));
    }

    /** 순수 계산(시험용) — rows 는 그 기간의 사용 중 예외(회사 + 그 공장) */
    static Map<LocalDate, DayPlan> plan(String plantCd, LocalDate from, LocalDate to, String weeklyOff, List<ShiftRow> shifts,
            List<CalRow> rows) {
        Map<LocalDate, CalRow> comp = new HashMap<>();
        Map<LocalDate, CalRow> plantDay = new HashMap<>();
        Map<String, CalRow> plantShift = new HashMap<>();
        for (CalRow r : rows) {
            if (r.getPlantCd() == null) {
                comp.put(r.getCalDate(), r);
            } else if (r.getPlantCd().equals(plantCd)) {
                if (r.getShiftCd() == null) {
                    plantDay.put(r.getCalDate(), r);
                } else {
                    plantShift.put(r.getCalDate() + "/" + r.getShiftCd(), r);
                }
            }
        }
        Map<LocalDate, DayPlan> out = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            CalRow hit = plantDay.containsKey(d) ? plantDay.get(d) : comp.get(d);
            boolean work;
            String source;
            String reason = null;
            if (hit != null) {
                work = "WORK".equals(hit.getDayType());
                source = hit.getPlantCd() == null ? "COMP_CAL" : "WORK_CAL";
                reason = hit.getReason();
            } else if (weeklyOff(weeklyOff, d.getDayOfWeek())) {
                work = false;
                source = "WEEKLY";
            } else {
                work = true;
                source = "DEFAULT";
            }
            List<ShiftDay> on = new ArrayList<>();
            for (ShiftRow s : shifts) {
                CalRow x = plantShift.get(d + "/" + s.getShiftCd());
                if (x == null ? work : "WORK".equals(x.getDayType())) {
                    on.add(shiftDay(s));
                }
            }
            // 하루는 쉬어도 근무조 특근이 있으면 그날은 일하는 날(그 근무조만)
            out.put(d, new DayPlan(d, work || !on.isEmpty(), source, reason, List.copyOf(on)));
        }
        return out;
    }

    static ShiftDay shiftDay(ShiftRow s) {
        return new ShiftDay(s.getShiftCd(), s.getShiftNm(), s.getStartTime(), s.getEndTime(), s.getDayOffset(), s.getBreakMin());
    }

    static boolean weeklyOff(String policy, DayOfWeek dow) {
        return switch (policy == null ? "NONE" : policy) {
            case "SUN" -> dow == DayOfWeek.SUNDAY;
            case "SAT_SUN" -> dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
            default -> false;
        };
    }
}
