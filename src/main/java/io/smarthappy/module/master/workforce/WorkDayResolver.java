package io.smarthappy.module.master.workforce;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.workforce.WorkforceDtos.ShiftRow;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 발생 시각 → 공장 기준 작업일·근무조 (docs/25 4.2, 04 5번 결정).
 *
 * 공장 현지 시각의 분이 어느 사용 중 근무조 [시작, 끝) 에 드는지 본다. 자정을 넘는 조는 다음 날 앞부분도 그 조다.
 * 작업일 = 근무조가 **시작한 날짜 + day_offset**. 어느 조에도 안 들면 근무조 없음, 작업일 = 현지 날짜(근무 외 실적도 잃지 않는다).
 * 원장은 저장할 때 이 결과를 행에 남긴다 — 근무조 정의가 나중에 바뀌어도 과거 행의 작업일은 그대로.
 */
@Component
@RequiredArgsConstructor
public class WorkDayResolver {

    private final WorkforceMapper mapper;
    private final BizClock clock;

    public record WorkDay(LocalDate workDay, String shiftCd, String shiftNm, ZoneId zone, LocalDateTime local) {
    }

    @Transactional(readOnly = true)
    public WorkDay resolve(String plantCd, Instant at) {
        ZoneId zone = clock.zone(plantCd);
        return resolve(at, zone, mapper.findShifts(plantCd, true));
    }

    /** 순수 계산(시험용) */
    static WorkDay resolve(Instant at, ZoneId zone, List<ShiftRow> shifts) {
        LocalDateTime local = LocalDateTime.ofInstant(at, zone);
        int m = local.getHour() * 60 + local.getMinute();
        for (ShiftRow s : shifts) {
            int start = minutes(s.getStartTime());
            int end = minutes(s.getEndTime());
            boolean crosses = end <= start;
            LocalDate startDate;
            if (!crosses && m >= start && m < end) {
                startDate = local.toLocalDate();
            } else if (crosses && m >= start) {
                startDate = local.toLocalDate();          // 22:00~ 그날 저녁 부분
            } else if (crosses && m < end) {
                startDate = local.toLocalDate().minusDays(1);   // 다음 날 새벽 부분 — 전날 시작한 조
            } else {
                continue;
            }
            return new WorkDay(startDate.plusDays(s.getDayOffset()), s.getShiftCd(), s.getShiftNm(), zone, local);
        }
        return new WorkDay(local.toLocalDate(), null, null, zone, local);
    }

    private static int minutes(LocalTime t) {
        return t.getHour() * 60 + t.getMinute();
    }
}
