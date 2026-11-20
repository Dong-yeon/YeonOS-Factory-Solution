package io.smarthappy.module.aps;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 유한 용량 순방향 목록 스케줄 (docs/38 3.4) — DB·스프링 없는 계산만(시험이 그대로 부른다).
 *
 * - 지시를 규칙 PRIO_DUE(우선순위 → 납기 → 계획 시작일 → 지시 번호)로 줄 세우고, 지시마다 공정 순번 순서로 넣는다
 * - 공정은 후보 자원 중 **가장 빨리 끝나는** 자원(같으면 시작이 이른, 그다음 정렬 키)의 근무 시간 안 **빈자리**에 넣는다(앞 일을 밀지 않음)
 * - 외주 공정은 무한 용량, 기간 = 리드타임(달력 시간). 앞 공정을 못 넣으면 뒤 공정은 PREV
 * - 같은 입력이면 같은 결과(결정적) — 정렬 키를 끝까지 준다
 * - 3단계(docs/38 A23): 수동 고정 공정이 **먼저** 자리 잡는다(시작 순). 근무 외·다른 고정과 겹치면 다음 빈자리(PIN_MOVED),
 *   후보 자원이 아니거나 기간 안에 못 들어가면 무시하고 일반 계산(PIN_IGNORED), 앞 공정보다 이르면 그대로 두고 PIN_PREV
 * - 5단계(docs/38 14.3): {@link MaterialPlanner} 가 있으면 공정을 넣을 때마다 자재를 할당한다. DELAY 면 충족 시각을 가장 이른 시작에 더하고,
 *   기간 안에 안 덮이면 MAT 예외. 고정 공정은 사람 판단이라 미루지 않고 판정만(WARN 처럼)
 */
final class FiniteScheduler {

    static final String NO_CT = "NO_CT";
    static final String NO_NODE = "NO_NODE";
    static final String HORIZON = "HORIZON";
    static final String PREV = "PREV";
    /** DELAY 에서 계획 기간 안에 자재가 덮이지 않음(5단계 A27) */
    static final String MAT = "MAT";
    static final String PIN_MOVED = "PIN_MOVED";
    static final String PIN_IGNORED = "PIN_IGNORED";
    static final String PIN_PREV = "PIN_PREV";

    /** 자원 — kind EQUIP(설비) / NODE(설비 없는 작업장) / OUTSRC(외주, 무한 용량) */
    record Res(String kind, Long equipId, Long nodeId, String sortKey) {
        String key() {
            return kind + ":" + ("EQUIP".equals(kind) ? equipId : nodeId);
        }
    }

    /** 수동 고정 — 자원(외주면 kind OUTSRC) + 시작 시각 */
    record Pin(long pinId, Res res, Instant start) {
    }

    static final class OpIn {
        int opSeq;
        Long woProcId;
        long procId;
        String procCd;
        String procNm;
        Long nodeId;
        Long equipId;
        boolean outsrc;
        BigDecimal stdCtSec;
        int unitsPerCycle;
        BigDecimal setupMin;
        BigDecimal qty;
        boolean running;
        Pin pin;
    }

    static final class WoIn {
        long woId;
        String woNo;
        int priority;
        LocalDate planStart;
        LocalDate planEnd;
        /** 납기(수주 행 납기, 없으면 지시 완료일 — A21). 비우면 planEnd */
        LocalDate due;
        final List<OpIn> ops = new ArrayList<>();

        LocalDate dueOrEnd() {
            return due == null ? planEnd : due;
        }
    }

    static final class OpOut {
        final WoIn wo;
        final OpIn op;
        Res res;
        int setupSec;
        int runSec;
        Instant start;
        Instant end;
        String exc;
        Long pinId;
        String warn;
        /** 고정으로 먼저 자리 잡음 */
        boolean pinned;

        OpOut(WoIn wo, OpIn op) {
            this.wo = wo;
            this.op = op;
        }
    }

    static final class WoOut {
        final WoIn wo;
        final int seq;
        final List<OpOut> ops = new ArrayList<>();
        Instant start;
        Instant end;
        boolean late;
        String exc;

        WoOut(WoIn wo, int seq) {
            this.wo = wo;
            this.seq = seq;
        }
    }

    static final Comparator<WoIn> PRIO_DUE = Comparator.<WoIn>comparingInt(w -> w.priority)
            .thenComparing(WoIn::dueOrEnd).thenComparing(w -> w.planStart).thenComparing(w -> w.woNo);

    private final ShiftCalendar cal;
    private final ZoneId zone;
    private final Instant from;
    private final Instant to;
    private final long outsrcLeadSec;
    private final Function<OpIn, List<Res>> candidates;
    /** 자재 판정(없으면 자재를 보지 않음 — 1~4단계 그대로) */
    private final MaterialPlanner mat;
    /** 자원별 잡힌 구간(시작 순) */
    private final Map<String, List<Instant[]>> busy = new HashMap<>();

    FiniteScheduler(ShiftCalendar cal, ZoneId zone, Instant from, Instant to, long outsrcLeadSec,
            Function<OpIn, List<Res>> candidates) {
        this(cal, zone, from, to, outsrcLeadSec, candidates, null);
    }

    FiniteScheduler(ShiftCalendar cal, ZoneId zone, Instant from, Instant to, long outsrcLeadSec,
            Function<OpIn, List<Res>> candidates, MaterialPlanner mat) {
        this.mat = mat;
        this.cal = cal;
        this.zone = zone;
        this.from = from;
        this.to = to;
        this.outsrcLeadSec = outsrcLeadSec;
        this.candidates = candidates;
    }

    List<WoOut> schedule(List<WoIn> wos) {
        Map<OpIn, OpOut> pinned = new IdentityHashMap<>();
        Map<OpIn, Long> ignored = new IdentityHashMap<>();
        pinFirst(wos, pinned, ignored);
        List<WoIn> order = new ArrayList<>(wos);
        order.sort(PRIO_DUE);
        List<WoOut> out = new ArrayList<>();
        int seq = 0;
        for (WoIn w : order) {
            WoOut wo = new WoOut(w, ++seq);
            Instant ready = max(from, ZonedDateTime.of(w.planStart.atStartOfDay(), zone).toInstant());
            List<OpIn> ops = new ArrayList<>(w.ops);
            ops.sort(Comparator.comparingInt(o -> o.opSeq));
            String failed = null;
            for (OpIn op : ops) {
                OpOut o = pinned.get(op);
                if (o != null) {
                    // 사람이 정한 자리 — 앞 공정과 어긋나도(못 했거나 더 늦게 끝남) 그대로 두고 알린다
                    if (failed != null || o.start.isBefore(ready)) {
                        o.warn = PIN_PREV;
                    }
                    if (failed == null) {
                        ready = max(ready, o.end);
                    }
                    if (mat != null) {
                        if (failed == null) {
                            mat.commit(w, op, o.start);
                        } else {
                            mat.skip(w, op);
                        }
                    }
                    wo.ops.add(o);
                    continue;
                }
                o = new OpOut(w, op);
                durations(o);
                if (ignored.containsKey(op)) {
                    o.pinId = ignored.get(op);
                    o.warn = PIN_IGNORED;
                }
                if (failed != null) {
                    o.exc = PREV;
                } else {
                    Instant earliest = ready;
                    if (mat != null && mat.mode == MaterialPlanner.Mode.DELAY) {
                        Instant c = mat.coveredAt(w, op);
                        if (c == MaterialPlanner.NEVER) {
                            o.exc = MAT;
                        } else if (c != null && c.isAfter(earliest)) {
                            earliest = c;
                        }
                    }
                    if (o.exc == null) {
                        place(o, earliest);
                    }
                    if (o.exc != null) {
                        failed = o.exc;
                    } else {
                        ready = o.end;
                    }
                }
                if (mat != null) {
                    if (o.exc == null) {
                        mat.commit(w, op, o.start);
                    } else {
                        mat.skip(w, op);
                    }
                }
                wo.ops.add(o);
            }
            if (failed != null) {
                wo.exc = failed;
            } else if (!wo.ops.isEmpty()) {
                // 고정이 앞 공정보다 이를 수 있어(PIN_PREV) 처음·마지막 공정이 아니라 가장 이른 시작·가장 늦은 끝
                wo.start = wo.ops.stream().map(x -> x.start).min(Comparator.naturalOrder()).orElseThrow();
                wo.end = wo.ops.stream().map(x -> x.end).max(Comparator.naturalOrder()).orElseThrow();
                wo.late = wo.end.isAfter(ZonedDateTime.of(w.dueOrEnd().plusDays(1).atStartOfDay(), zone).toInstant());
            }
            out.add(wo);
        }
        return out;
    }

    /** 고정 공정을 시작 순(→ 지시 번호 → 순번)으로 먼저 자원에 넣는다. 못 넣는 고정은 ignored(일반 계산) */
    private void pinFirst(List<WoIn> wos, Map<OpIn, OpOut> pinned, Map<OpIn, Long> ignored) {
        List<OpOut> pins = new ArrayList<>();
        for (WoIn w : wos) {
            for (OpIn op : w.ops) {
                if (op.pin != null) {
                    OpOut o = new OpOut(w, op);
                    durations(o);
                    o.pinId = op.pin.pinId();
                    pins.add(o);
                }
            }
        }
        pins.sort(Comparator.<OpOut, Instant>comparing(o -> o.op.pin.start()).thenComparing(o -> o.wo.woNo)
                .thenComparingInt(o -> o.op.opSeq));
        for (OpOut o : pins) {
            OpIn op = o.op;
            Pin pin = op.pin;
            Instant want = max(pin.start(), from);
            Res res = null;
            Instant[] slot = null;
            if (op.outsrc) {
                if ("OUTSRC".equals(pin.res().kind()) && !want.plusSeconds(o.runSec).isAfter(to)) {
                    res = new Res("OUTSRC", null, op.nodeId, "");
                    slot = new Instant[] {want, want.plusSeconds(o.runSec)};
                }
            } else if (op.stdCtSec != null && op.nodeId != null && !"OUTSRC".equals(pin.res().kind())) {
                res = candidates.apply(op).stream().filter(c -> c.key().equals(pin.res().key())).findFirst().orElse(null);
                slot = res == null ? null : slot(res, want, (long) o.setupSec + o.runSec);
            }
            if (slot == null) {
                ignored.put(op, pin.pinId());       // 후보 자원 아님·기간 밖 → 일반 계산
                continue;
            }
            o.res = res;
            o.start = slot[0];
            o.end = slot[1];
            o.pinned = true;
            if (!slot[0].equals(pin.start())) {
                o.warn = PIN_MOVED;                 // 근무 외·지난 시각·다른 고정과 겹침 → 다음 빈자리
            }
            if (!op.outsrc) {
                book(res, slot[0], slot[1]);
            }
            pinned.put(op, o);
        }
    }

    /** 준비 = ceil(분 × 60)(진행 중 공정은 0), 가공 = ceil(ceil(잔량 ÷ 사이클당 수량) × CT). 외주는 리드타임 */
    private void durations(OpOut o) {
        OpIn op = o.op;
        if (op.outsrc) {
            o.setupSec = 0;
            o.runSec = clamp(BigDecimal.valueOf(outsrcLeadSec));
            return;
        }
        o.setupSec = op.running || op.setupMin == null ? 0
                : clamp(op.setupMin.multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.CEILING));
        if (op.stdCtSec == null) {
            o.runSec = 0;
            return;
        }
        BigDecimal cycles = op.qty.divide(BigDecimal.valueOf(Math.max(1, op.unitsPerCycle)), 0, RoundingMode.CEILING);
        o.runSec = clamp(cycles.multiply(op.stdCtSec).setScale(0, RoundingMode.CEILING));
    }

    private void place(OpOut o, Instant ready) {
        OpIn op = o.op;
        if (op.outsrc) {
            Instant end = ready.plusSeconds(o.runSec);
            if (end.isAfter(to)) {
                o.exc = HORIZON;
                return;
            }
            o.res = new Res("OUTSRC", null, op.nodeId, "");
            o.start = ready;
            o.end = end;
            return;
        }
        if (op.stdCtSec == null) {
            o.exc = NO_CT;
            return;
        }
        if (op.nodeId == null) {
            o.exc = NO_NODE;
            return;
        }
        long work = (long) o.setupSec + o.runSec;
        Res best = null;
        Instant bestStart = null;
        Instant bestEnd = null;
        for (Res r : candidates.apply(op)) {
            Instant[] slot = slot(r, ready, work);
            if (slot == null) {
                continue;
            }
            int c = best == null ? -1 : slot[1].compareTo(bestEnd);
            if (c == 0) {
                c = slot[0].compareTo(bestStart);
            }
            if (c == 0) {
                c = r.sortKey().compareTo(best.sortKey());
            }
            if (c < 0) {
                best = r;
                bestStart = slot[0];
                bestEnd = slot[1];
            }
        }
        if (best == null) {
            o.exc = HORIZON;
            return;
        }
        o.res = best;
        o.start = bestStart;
        o.end = bestEnd;
        book(best, bestStart, bestEnd);
    }

    /** 자원에 [start, end) 를 잡는다(시작 순 유지) */
    private void book(Res r, Instant start, Instant end) {
        List<Instant[]> list = busy.computeIfAbsent(r.key(), k -> new ArrayList<>());
        int i = 0;
        while (i < list.size() && !list.get(i)[0].isAfter(start)) {
            i++;
        }
        list.add(i, new Instant[] {start, end});
    }

    /** 자원 r 에서 ready 이후 work(≥ 1)초를 겹침 없이 넣을 수 있는 가장 이른 [시작, 끝], 계획 끝 안에 없으면 null */
    private Instant[] slot(Res r, Instant ready, long work) {
        List<Instant[]> list = busy.getOrDefault(r.key(), List.of());
        Instant t = cal.nextWorking(ready);
        while (t != null) {
            Instant end = cal.finish(t, work);
            if (end == null) {
                return null;
            }
            Instant[] hit = null;
            // 잡힌 구간은 시작 순 — 시작이 end 이상이면 뒤는 볼 필요 없다. [b0, b1) 과 [t, end) 가 겹치면 그 끝 뒤로
            for (Instant[] b : list) {
                if (!b[0].isBefore(end)) {
                    break;
                }
                if (b[1].isAfter(t)) {
                    hit = b;
                    break;
                }
            }
            if (hit == null) {
                return new Instant[] {t, end};
            }
            t = cal.nextWorking(hit[1]);
        }
        return null;
    }

    private static int clamp(BigDecimal v) {
        return v.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0 ? Integer.MAX_VALUE : v.intValueExact();
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
