package io.smarthappy.module.pms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import io.smarthappy.module.pms.PmsDtos.TaskRow;
import io.smarthappy.module.pms.PmsDtos.TmplTaskRow;

/**
 * WBS 트리 계산 (docs/32 3장) — DB 를 모르는 순수 함수. 프로젝트·템플릿 작업 모두.
 * - 순서: 부모 먼저(전위), 형제는 seq → id. 레벨·WBS 번호(1.2.3)는 저장하지 않고 여기서 계산
 * - 요약 작업(자식 있음)의 계획·실적·진척·상태는 자식에서 파생 — 저장 때 서비스가 이 결과로 행을 맞춘다
 */
final class WbsTree {

    private WbsTree() {
    }

    /** 펼친 한 행 */
    record Placed<T>(T row, int lvl, String wbsNo, boolean hasKids) {
    }

    /**
     * 전위 순서로 펼친다. 부모가 목록에 없는 행(있으면 안 됨 — 트리거가 막는다)은 뿌리로 취급해 잃지 않는다.
     * 순환이 있어도(직접 SQL 등) 한 번 방문한 행은 다시 가지 않는다
     */
    static <T> List<Placed<T>> order(List<T> rows, Function<T, Long> id, Function<T, Long> parent, ToIntFunction<T> seq) {
        Map<Long, T> byId = new HashMap<>();
        rows.forEach(r -> byId.put(id.apply(r), r));
        Map<Long, List<T>> kids = new HashMap<>();
        List<T> roots = new ArrayList<>();
        for (T r : rows) {
            Long p = parent.apply(r);
            if (p == null || !byId.containsKey(p)) {
                roots.add(r);
            } else {
                kids.computeIfAbsent(p, k -> new ArrayList<>()).add(r);
            }
        }
        Comparator<T> bySeq = Comparator.<T>comparingInt(seq).thenComparing(id);
        roots.sort(bySeq);
        kids.values().forEach(l -> l.sort(bySeq));
        List<Placed<T>> out = new ArrayList<>(rows.size());
        Set<Long> seen = new HashSet<>();
        // 재귀 대신 스택(깊이 상한이 있어도 직접 SQL 로 깊어진 트리에서 스택 넘침이 없게)
        Deque<Object[]> stack = new ArrayDeque<>();
        for (int i = roots.size() - 1; i >= 0; i--) {
            stack.push(new Object[] {roots.get(i), 1, String.valueOf(i + 1)});
        }
        while (!stack.isEmpty()) {
            Object[] f = stack.pop();
            @SuppressWarnings("unchecked")
            T r = (T) f[0];
            Long rid = id.apply(r);
            if (!seen.add(rid)) {
                continue;
            }
            int lvl = (Integer) f[1];
            String no = (String) f[2];
            List<T> ks = kids.getOrDefault(rid, List.of());
            out.add(new Placed<>(r, lvl, no, !ks.isEmpty()));
            for (int i = ks.size() - 1; i >= 0; i--) {
                stack.push(new Object[] {ks.get(i), lvl + 1, no + "." + (i + 1)});
            }
        }
        return out;
    }

    static int maxLevel(List<? extends Placed<?>> placed) {
        int m = 0;
        for (Placed<?> p : placed) {
            m = Math.max(m, p.lvl());
        }
        return m;
    }

    /** 계획 기간(일, 양 끝 포함) — 진척 가중치. 마일스톤·게이트는 1 */
    static long days(LocalDate start, LocalDate end) {
        return ChronoUnit.DAYS.between(start, end) + 1;
    }

    // ------------------------------------------------------------------ 프로젝트 요약 파생

    /** 요약 하나 또는 프로젝트 전체의 파생 값 */
    record Rollup(LocalDate planStart, LocalDate planEnd, LocalDate actStart, LocalDate actEnd, int progressPct,
            String status, BigDecimal planHours) {
    }

    /**
     * 자식 값 → 요약 값. 취소된 자식은 진척·상태에서 빼고(계획 기간에는 넣는다 — 취소돼도 막대는 남는다).
     * 모두 취소 = CANCELLED, 남은 것 모두 완료 = DONE, 하나라도 시작 = IN_PROGRESS(진척은 99 이하), 아니면 NOT_STARTED.
     * 진척 = 계획 기간 가중 평균(내림 — 공수 가중으로 바꾸지 않음, docs/48 R-2). 계획 공수 = 취소 아닌 자식의 합(모두 비었으면 null)
     */
    static Rollup combine(List<TaskRow> children) {
        LocalDate ps = null, pe = null, as = null, ae = null;
        long w = 0, wp = 0;
        int active = 0, done = 0, started = 0;
        BigDecimal hours = null;
        for (TaskRow c : children) {
            ps = min(ps, c.getPlanStart());
            pe = max(pe, c.getPlanEnd());
            if ("CANCELLED".equals(c.getStatus())) {
                continue;
            }
            if (c.getPlanHours() != null) {
                hours = hours == null ? c.getPlanHours() : hours.add(c.getPlanHours());
            }
            active++;
            long d = days(c.getPlanStart(), c.getPlanEnd());
            w += d;
            wp += d * c.getProgressPct();
            if (c.getActStart() != null) {
                started++;
                as = min(as, c.getActStart());
            }
            if ("DONE".equals(c.getStatus())) {
                done++;
                ae = max(ae, c.getActEnd());
            }
        }
        if (active == 0) {
            return new Rollup(ps, pe, null, null, 0, "CANCELLED", null);
        }
        if (done == active) {
            return new Rollup(ps, pe, as, ae, 100, "DONE", hours);
        }
        if (started > 0) {
            int pct = (int) Math.min(99, w == 0 ? 0 : wp / w);
            return new Rollup(ps, pe, as, null, pct, "IN_PROGRESS", hours);
        }
        return new Rollup(ps, pe, null, null, 0, "NOT_STARTED", hours);
    }

    /**
     * 요약 작업을 아래에서 위로 다시 계산해 행에 써 넣는다(is_summary 포함). 바뀐 행의 id 를 돌려준다.
     * 프로젝트 전체 값(뿌리들의 합)은 {@link #project}
     */
    static Set<Long> rollupInPlace(List<Placed<TaskRow>> placed) {
        Map<Long, List<TaskRow>> kids = new HashMap<>();
        for (Placed<TaskRow> p : placed) {
            if (p.row().getParentId() != null) {
                kids.computeIfAbsent(p.row().getParentId(), k -> new ArrayList<>()).add(p.row());
            }
        }
        Set<Long> changed = new HashSet<>();
        // 전위 순서의 역순 = 자식이 부모보다 먼저
        for (int i = placed.size() - 1; i >= 0; i--) {
            TaskRow t = placed.get(i).row();
            List<TaskRow> ks = kids.get(t.getTaskId());
            String summary = ks == null ? "N" : "Y";
            boolean diff = !summary.equals(t.getIsSummary());
            t.setIsSummary(summary);
            if (ks != null) {
                Rollup r = combine(ks);
                diff |= !eq(t.getPlanStart(), r.planStart()) || !eq(t.getPlanEnd(), r.planEnd())
                        || !eq(t.getActStart(), r.actStart()) || !eq(t.getActEnd(), r.actEnd())
                        || t.getProgressPct() != r.progressPct() || !r.status().equals(t.getStatus())
                        || !sameHours(t.getPlanHours(), r.planHours());
                t.setPlanStart(r.planStart());
                t.setPlanEnd(r.planEnd());
                t.setActStart(r.actStart());
                t.setActEnd(r.actEnd());
                t.setProgressPct(r.progressPct());
                t.setStatus(r.status());
                t.setPlanHours(r.planHours());
            }
            if (diff) {
                changed.add(t.getTaskId());
            }
        }
        return changed;
    }

    static boolean sameHours(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    /** 프로젝트 = 뿌리 작업들의 파생 값(작업이 없으면 null) */
    static Rollup project(List<Placed<TaskRow>> placed) {
        List<TaskRow> roots = new ArrayList<>();
        for (Placed<TaskRow> p : placed) {
            if (p.lvl() == 1) {
                roots.add(p.row());
            }
        }
        return roots.isEmpty() ? null : combine(roots);
    }

    // ------------------------------------------------------------------ 템플릿

    /**
     * 템플릿 작업의 기준일 대비 시작·끝(일) — 잎은 offset ~ offset + max(기간,1) - 1, 요약은 자식의 최소·최대.
     * 행의 startDay·endDay·isSummary 에 써 넣는다
     */
    /**
     * 부모가 먼저 오도록 깊이 순(같은 깊이는 원래 순서) — 트리를 다른 표로 옮겨 넣을 때(WBS 복사·템플릿으로 저장, docs/32 4.17·4.18).
     * 목록 안에 없는 부모는 뿌리로 본다
     */
    static <T> List<T> parentsFirst(List<T> rows, Function<T, Long> id, Function<T, Long> parent) {
        Map<Long, T> byId = new HashMap<>();
        rows.forEach(r -> byId.put(id.apply(r), r));
        Map<Long, Integer> depth = new HashMap<>();
        for (T r : rows) {
            int d = 0;
            for (Long p = parent.apply(r); p != null && byId.containsKey(p) && d <= rows.size(); p = parent.apply(byId.get(p))) {
                d++;
            }
            depth.put(id.apply(r), d);
        }
        return rows.stream().sorted(Comparator.comparingInt(r -> depth.get(id.apply(r)))).toList();
    }

    static void templateSpans(List<Placed<TmplTaskRow>> placed) {
        Map<Long, List<TmplTaskRow>> kids = new HashMap<>();
        for (Placed<TmplTaskRow> p : placed) {
            if (p.row().getParentId() != null) {
                kids.computeIfAbsent(p.row().getParentId(), k -> new ArrayList<>()).add(p.row());
            }
        }
        for (int i = placed.size() - 1; i >= 0; i--) {
            TmplTaskRow t = placed.get(i).row();
            List<TmplTaskRow> ks = kids.get(t.getTtId());
            if (ks == null) {
                t.setIsSummary("N");
                t.setStartDay(t.getOffsetDays());
                t.setEndDay(t.getOffsetDays() + Math.max(t.getDurationDays(), 1) - 1);
            } else {
                t.setIsSummary("Y");
                t.setStartDay(ks.stream().mapToInt(TmplTaskRow::getStartDay).min().orElse(0));
                t.setEndDay(ks.stream().mapToInt(TmplTaskRow::getEndDay).max().orElse(0));
            }
        }
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a == null ? b : b == null ? a : a.isBefore(b) ? a : b;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
