package io.smarthappy.module.aps;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.smarthappy.module.master.workforce.WorkCalendar;
import io.smarthappy.module.master.workforce.WorkCalendar.DayPlan;
import io.smarthappy.module.master.workforce.WorkCalendar.ShiftDay;

/**
 * 근무 달력 (docs/38 3.3) — 공장 근무조를 계획 기간의 매일 펼친 근무 구간 [시작, 끝).
 *
 * - 끝 ≤ 시작이면 다음 날 끝(자정 넘김). 첫날 **전날** 시작한 야간조의 뒷부분도 넣는다
 * - 휴식(break_min)은 위치가 없으므로 근무조 끝에서 뺀다(A3)
 * - DST: 현지 시각 → 순간은 {@link ZonedDateTime#of}(없는 시각은 뒤로, 겹치는 시각은 앞쪽) — 달력은 사람이 입력한 시각이 아니라 오류로 막지 않는다
 * - 구간은 [from, to) 로 자르고, 맞닿거나 겹치는 구간은 합친다(주간 08~20 + 야간 20~08 이면 끊김 없이 이어 일한다)
 * - 4단계(docs/38 13장): 날마다 일하는 근무조는 MES 공장 휴일 달력({@link WorkCalendar})이 정한다 — {@link #byStartDay} 로
 *   작업일 → 실제 시작일(작업일 − day_offset)로 옮겨 펼친다. 휴일·예외가 없으면 매일 같은 근무조(1~3단계와 같은 구간)
 */
final class ShiftCalendar {

    record Shift(LocalTime start, LocalTime end, int breakMin) {
    }

    record Window(Instant start, Instant end) {
    }

    private final List<Window> windows;

    private ShiftCalendar(List<Window> windows) {
        this.windows = windows;
    }

    /** 매일 같은 근무조(달력 없음) — 첫날 전날부터 */
    static ShiftCalendar build(List<Shift> shifts, ZoneId zone, LocalDate first, int days, Instant from, Instant to) {
        Map<LocalDate, List<Shift>> byStart = new TreeMap<>();
        for (int i = -1; i < days; i++) {
            byStart.put(first.plusDays(i), shifts);
        }
        return build(byStart, zone, from, to);
    }

    /**
     * 공장 휴일 달력의 작업일별 근무조 → 실제 시작일별 근무조. 근무조 실제 시작일 = 작업일 − day_offset
     * (야간 3조 00~08, day_offset −1 은 작업일 다음 날 시작). 시작일이 [첫날 − 1, 마지막 날] 인 것만 — 첫날 전날 시작한 야간조 뒷부분 포함
     */
    static Map<LocalDate, List<Shift>> byStartDay(Collection<DayPlan> days, LocalDate first, LocalDate last) {
        Map<LocalDate, List<Shift>> out = new TreeMap<>();
        for (DayPlan dp : days) {
            for (ShiftDay s : dp.shifts()) {
                LocalDate start = dp.day().minusDays(s.dayOffset());
                if (start.isBefore(first.minusDays(1)) || start.isAfter(last)) {
                    continue;
                }
                out.computeIfAbsent(start, k -> new ArrayList<>()).add(new Shift(s.start(), s.end(), s.breakMin()));
            }
        }
        return out;
    }

    /** 실제 시작일별 근무조 → 근무 구간 */
    static ShiftCalendar build(Map<LocalDate, List<Shift>> byStart, ZoneId zone, Instant from, Instant to) {
        List<Window> raw = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Shift>> e : byStart.entrySet()) {
            LocalDate d = e.getKey();
            for (Shift s : e.getValue()) {
                Instant a = ZonedDateTime.of(d, s.start(), zone).toInstant();
                LocalDate endDay = s.end().isAfter(s.start()) ? d : d.plusDays(1);
                Instant b = ZonedDateTime.of(endDay, s.end(), zone).toInstant().minus(Duration.ofMinutes(s.breakMin()));
                if (a.isBefore(from)) {
                    a = from;
                }
                if (b.isAfter(to)) {
                    b = to;
                }
                if (a.isBefore(b)) {
                    raw.add(new Window(a, b));
                }
            }
        }
        raw.sort(Comparator.comparing(Window::start));
        List<Window> merged = new ArrayList<>();
        for (Window w : raw) {
            Window last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && !w.start().isAfter(last.end())) {
                if (w.end().isAfter(last.end())) {
                    merged.set(merged.size() - 1, new Window(last.start(), w.end()));
                }
            } else {
                merged.add(w);
            }
        }
        return new ShiftCalendar(List.copyOf(merged));
    }

    List<Window> windows() {
        return windows;
    }

    /** t 이후(같거나) 가장 이른 근무 시각, 없으면 null */
    Instant nextWorking(Instant t) {
        int i = indexFrom(t);
        if (i >= windows.size()) {
            return null;
        }
        Window w = windows.get(i);
        return t.isBefore(w.start()) ? w.start() : t;
    }

    /** start(근무 시각)부터 근무 시간 workSec 를 쓰고 끝나는 시각 — 근무 외 시간은 건너뛴다. 계획 끝을 넘으면 null */
    Instant finish(Instant start, long workSec) {
        if (workSec <= 0) {
            return start;
        }
        long left = workSec;
        for (int i = indexFrom(start); i < windows.size(); i++) {
            Window w = windows.get(i);
            Instant cur = start.isAfter(w.start()) ? start : w.start();
            long avail = Duration.between(cur, w.end()).getSeconds();
            if (avail >= left) {
                return cur.plusSeconds(left);
            }
            left -= avail;
        }
        return null;
    }

    /** t 를 품거나(끝 > t) t 뒤에 오는 첫 구간의 위치 — 이진 탐색 */
    private int indexFrom(Instant t) {
        int lo = 0;
        int hi = windows.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (windows.get(mid).end().isAfter(t)) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }
}
