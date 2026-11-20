package io.smarthappy.module.cms;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 예방점검 예정일 계산 (docs/33 4.3) — 순수 함수. 화면 미리보기·정기 작업·완료 처리가 모두 이것만 부른다.
 *
 * FIXED: k 번째 예정 = 첫 예정일 + k×간격. 월·년은 첫 예정일에서 다시 더한다(1/31 → 2/28 → 3/31 — 날이 밀리지 않음).
 * FLOAT: 다음 예정 = 완료일(없으면 예정일) + 간격.
 * 사용량 주기(C3)도 같은 규칙을 누적 사용량에 적용한다 — FIXED 의 k 번째 = 첫 예정 사용량 + k×간격, FLOAT = 완료 때 누적 + 간격.
 */
public final class PmDue {

    public enum State { UPCOMING, DUE, OVERDUE }

    /** 주기 규칙 */
    public record Rule(String basis, String unit, int interval, LocalDate anchor) {
    }

    /** 사용량 주기 규칙 — 간격 > 0, 첫 예정 사용량 ≥ 0 (누적 기준) */
    public record UsageRule(String basis, BigDecimal interval, BigDecimal anchor) {

        /** BigDecimal 은 equals 가 자릿수까지 보므로(5.0 ≠ 5.00) 규칙 비교는 값으로 */
        public boolean sameAs(UsageRule o) {
            return o != null && basis.equals(o.basis) && interval.compareTo(o.interval) == 0 && anchor.compareTo(o.anchor) == 0;
        }
    }

    private PmDue() {
    }

    static LocalDate plus(LocalDate d, String unit, long n) {
        return switch (unit) {
            case "DAY" -> d.plusDays(n);
            case "WEEK" -> d.plusWeeks(n);
            case "MONTH" -> d.plusMonths(n);
            case "YEAR" -> d.plusYears(n);
            default -> throw new IllegalArgumentException("unit " + unit);
        };
    }

    /** FIXED 의 k 번째 예정 */
    static LocalDate fixedAt(Rule r, long k) {
        return plus(r.anchor(), r.unit(), k * r.interval());
    }

    /** after 보다 뒤인 첫 FIXED 예정 (after 가 첫 예정일 앞이면 첫 예정일) */
    public static LocalDate fixedAfter(Rule r, LocalDate after) {
        if (after.isBefore(r.anchor())) {
            return r.anchor();
        }
        long span = switch (r.unit()) {
            case "DAY" -> ChronoUnit.DAYS.between(r.anchor(), after);
            case "WEEK" -> ChronoUnit.WEEKS.between(r.anchor(), after);
            case "MONTH" -> ChronoUnit.MONTHS.between(r.anchor(), after);
            case "YEAR" -> ChronoUnit.YEARS.between(r.anchor(), after);
            default -> throw new IllegalArgumentException("unit " + r.unit());
        };
        long k = Math.max(0, span / r.interval() - 1);   // 어림값에서 한 칸 앞부터 — 월말 보정으로 생기는 오차를 넘어간다
        LocalDate d = fixedAt(r, k);
        while (!d.isAfter(after)) {
            d = fixedAt(r, ++k);
        }
        return d;
    }

    /**
     * 예정 하나가 끝난 뒤(완료 또는 건너뛰기) 다음 예정.
     * FIXED: max(예정일, 완료일)보다 뒤인 첫 고정 예정 — 늦게 하면 그 사이 놓친 회차는 건너뛴다.
     * FLOAT: 완료일(건너뛰면 예정일) + 간격.
     */
    public static LocalDate next(Rule r, LocalDate due, LocalDate doneDate) {
        if ("FIXED".equals(r.basis())) {
            LocalDate base = doneDate != null && doneDate.isAfter(due) ? doneDate : due;
            return fixedAfter(r, base);
        }
        return plus(doneDate != null ? doneDate : due, r.unit(), r.interval());
    }

    /** 새 계획이거나 규칙을 바꿨을 때 다음 예정: 한 적 없으면 첫 예정일, 있으면 마지막 완료일 기준 */
    public static LocalDate initial(Rule r, LocalDate lastDone) {
        if (lastDone == null) {
            return r.anchor();
        }
        if ("FIXED".equals(r.basis())) {
            return fixedAfter(r, lastDone);
        }
        LocalDate d = plus(lastDone, r.unit(), r.interval());
        return d.isBefore(r.anchor()) ? r.anchor() : d;
    }

    /** 앞으로 n 회 예정(미리보기) — FLOAT 는 매번 예정일에 했다고 보고 */
    public static List<LocalDate> preview(Rule r, LocalDate from, int n) {
        List<LocalDate> out = new ArrayList<>(n);
        LocalDate d = from;
        for (int i = 0; i < n; i++) {
            out.add(d);
            d = next(r, d, null);
        }
        return out;
    }

    /** 오늘 < 예정 − 선행 UPCOMING, ≤ 예정 DUE, 그 뒤 OVERDUE */
    public static State state(LocalDate due, LocalDate today, int leadDays) {
        if (today.isAfter(due)) {
            return State.OVERDUE;
        }
        return today.isBefore(due.minusDays(leadDays)) ? State.UPCOMING : State.DUE;
    }

    /** 기한 준수: 완료일 ≤ 예정 + 허용 일수 */
    public static boolean onTime(LocalDate due, LocalDate done, int graceDays) {
        return !done.isAfter(due.plusDays(graceDays));
    }

    // ------------------------------------------------------------------ 사용량 주기 (C3)

    /** after 보다 큰 첫 FIXED 예정 사용량 (after 가 첫 예정 앞이면 첫 예정) */
    public static BigDecimal usageFixedAfter(UsageRule r, BigDecimal after) {
        if (after.compareTo(r.anchor()) < 0) {
            return r.anchor();
        }
        BigDecimal k = after.subtract(r.anchor()).divide(r.interval(), 0, RoundingMode.FLOOR).add(BigDecimal.ONE);
        return r.anchor().add(r.interval().multiply(k));
    }

    /**
     * 사용량 예정 하나가 끝난 뒤 다음 예정. FIXED: max(예정, 완료 누적)보다 큰 첫 고정 예정(넘겨 쓴 회차는 건너뜀).
     * FLOAT: 완료 누적(건너뛰면 예정) + 간격
     */
    public static BigDecimal nextUsage(UsageRule r, BigDecimal due, BigDecimal doneUsage) {
        if ("FIXED".equals(r.basis())) {
            BigDecimal base = doneUsage != null && doneUsage.compareTo(due) > 0 ? doneUsage : due;
            return usageFixedAfter(r, base);
        }
        return (doneUsage != null ? doneUsage : due).add(r.interval());
    }

    /** 새 계획·규칙 변경 때 다음 예정 사용량: 한 적 없으면 첫 예정, 있으면 마지막 완료 누적 기준 */
    public static BigDecimal initialUsage(UsageRule r, BigDecimal lastDoneUsage) {
        if (lastDoneUsage == null) {
            return r.anchor();
        }
        if ("FIXED".equals(r.basis())) {
            return usageFixedAfter(r, lastDoneUsage);
        }
        BigDecimal d = lastDoneUsage.add(r.interval());
        return d.compareTo(r.anchor()) < 0 ? r.anchor() : d;
    }

    /** 앞으로 n 회 예정 사용량(미리보기) — FLOAT 는 매번 예정에 맞춰 했다고 보고 */
    public static List<BigDecimal> previewUsage(UsageRule r, BigDecimal from, int n) {
        List<BigDecimal> out = new ArrayList<>(n);
        BigDecimal d = from;
        for (int i = 0; i < n; i++) {
            out.add(d);
            d = nextUsage(r, d, null);
        }
        return out;
    }

    /** 누적 < 예정 − 선행 UPCOMING, ≤ 예정 DUE, 넘으면 OVERDUE (선행 없으면 0) */
    public static State usageState(BigDecimal due, BigDecimal total, BigDecimal lead) {
        if (total.compareTo(due) > 0) {
            return State.OVERDUE;
        }
        return total.compareTo(due.subtract(lead == null ? BigDecimal.ZERO : lead)) < 0 ? State.UPCOMING : State.DUE;
    }

    /** 예정을 만들 때인가: 누적 ≥ 예정 − 선행 */
    public static boolean usageReached(BigDecimal due, BigDecimal total, BigDecimal lead) {
        return usageState(due, total, lead) != State.UPCOMING;
    }

    /** 사용량 기한 준수: 완료 때 누적 ≤ 예정 사용량 (날짜 허용 일수는 쓰지 않는다 — docs/33 4.4) */
    public static boolean usageOnTime(BigDecimal due, BigDecimal doneUsage) {
        return doneUsage.compareTo(due) <= 0;
    }
}
