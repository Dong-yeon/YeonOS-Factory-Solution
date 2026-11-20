package io.smarthappy.module.pms;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 사람 × 주 부하 계산 (docs/32 4.26, 사용자 결정 P-14 = docs/48 ⑦ B) — DB 를 모르는 순수 함수.
 *
 * <p>계획 공수는 작업 계획 기간 [시작, 종료] 의 <b>근무일에 고르게</b> 나눈다(회사 휴일 달력, 공장 없음 — 지연 판정·근무일 보조와 같은 층).
 * 한 주의 몫 = 공수 × (그 주에 든 근무일 수) ÷ (기간 전체 근무일 수). 주는 월요일 시작.
 * <ul>
 *   <li>기간 전체가 쉬는 날뿐(근무일 0)이면 공수가 조용히 사라지지 않게 <b>달력일로</b> 고르게 나눈다</li>
 *   <li>달력을 읽은 창(calFrom~) 밖으로 나가는 아주 긴 작업도 달력일로 나눈다 — 창은 부르는 쪽이 조회 범위 ± 10년으로 묶는다</li>
 * </ul>
 * 반올림은 주 몫마다 소수 둘째 자리(HALF_UP) — 표시는 MD 한 자리라 합과 0.01 차이는 보이지 않는다.
 */
final class PmsLoad {

    /** 주 몫 소수 자리 */
    static final int SCALE = 2;

    private PmsLoad() {
    }

    /** 나눌 작업 — owner null = 담당 없음 */
    record Task(long taskId, Long ownerId, LocalDate start, LocalDate end, BigDecimal hours) {
    }

    /**
     * 근무일 표: calFrom 부터 하루씩 근무일이면 true. 누적 합으로 구간 근무일 수를 O(1) 로 센다
     */
    static final class Cal {
        final LocalDate from;
        final int[] prefix;          // prefix[i] = [from, from+i) 의 근무일 수

        Cal(LocalDate from, boolean[] workday) {
            this.from = from;
            this.prefix = new int[workday.length + 1];
            for (int i = 0; i < workday.length; i++) {
                prefix[i + 1] = prefix[i] + (workday[i] ? 1 : 0);
            }
        }

        LocalDate to() {
            return from.plusDays(prefix.length - 2L);
        }

        boolean covers(LocalDate a, LocalDate b) {
            return !a.isBefore(from) && !b.isAfter(to());
        }

        /** [a, b] 근무일 수 — covers(a, b) 일 때만 */
        int workdays(LocalDate a, LocalDate b) {
            if (b.isBefore(a)) {
                return 0;
            }
            int i = (int) ChronoUnit.DAYS.between(from, a);
            int j = (int) ChronoUnit.DAYS.between(from, b) + 1;
            return prefix[j] - prefix[i];
        }
    }

    static LocalDate monday(LocalDate d) {
        return d.minusDays(d.getDayOfWeek().getValue() - 1L);
    }

    /** 작업 하나가 주 w(월요일 weekStart)에 갖는 몫. 겹치지 않으면 0 */
    static BigDecimal share(Task t, LocalDate weekStart, Cal cal) {
        LocalDate ws = weekStart.isBefore(t.start()) ? t.start() : weekStart;
        LocalDate we = weekStart.plusDays(6).isAfter(t.end()) ? t.end() : weekStart.plusDays(6);
        if (we.isBefore(ws) || t.hours() == null || t.hours().signum() <= 0) {
            return BigDecimal.ZERO;
        }
        long part;
        long whole;
        if (cal != null && cal.covers(t.start(), t.end()) && cal.workdays(t.start(), t.end()) > 0) {
            whole = cal.workdays(t.start(), t.end());
            part = cal.workdays(ws, we);
        } else {
            whole = ChronoUnit.DAYS.between(t.start(), t.end()) + 1;
            part = ChronoUnit.DAYS.between(ws, we) + 1;
        }
        if (part == 0) {
            return BigDecimal.ZERO;
        }
        return t.hours().multiply(BigDecimal.valueOf(part)).divide(BigDecimal.valueOf(whole), SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 사람(담당 없음 = null 키)마다 주별 계획 몫. weeks = 주 수, firstMonday = 첫 주 월요일
     */
    static Map<Long, BigDecimal[]> plan(List<Task> tasks, LocalDate firstMonday, int weeks, Cal cal) {
        Map<Long, BigDecimal[]> out = new HashMap<>();
        for (Task t : tasks) {
            BigDecimal[] row = out.computeIfAbsent(t.ownerId(), k -> zeros(weeks));
            for (int w = 0; w < weeks; w++) {
                row[w] = row[w].add(share(t, firstMonday.plusWeeks(w), cal));
            }
        }
        return out;
    }

    static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }
}
