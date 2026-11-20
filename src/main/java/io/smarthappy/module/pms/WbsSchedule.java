package io.smarthappy.module.pms;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.smarthappy.module.pms.PmsDtos.DepRow;
import io.smarthappy.module.pms.PmsDtos.PushMove;
import io.smarthappy.module.pms.PmsDtos.TaskRow;

/**
 * 의존 위반·후행 밀기 계산 (docs/32 PM2·4.27, D4·P-15) — DB 를 모르는 순수 함수. 날짜는 양 끝 포함 하루 단위, lag = 달력일.
 * <ul>
 *   <li>FS: 후행 계획 시작 ≥ 선행 계획 종료 + 1 + lag(종료일도 일하는 날)</li>
 *   <li>SS: 후행 계획 시작 ≥ 선행 계획 시작 + lag</li>
 *   <li>FF: 후행 계획 종료 ≥ 선행 계획 종료 + lag</li>
 * </ul>
 * 밀기는 위상 순서로 "필요한 만큼만 뒤로"(기간 유지, 앞당기지 않음) — FF 는 종료를 맞추려면 시작이 (후행 기간 − 1) 만큼 앞이면 된다.
 * 완료·취소 작업은 움직이지 않는다(그 작업 뒤의 후행은 그 작업의 지금 계획을 기준으로 맞춘다). 순환 판정은 종류와 무관(그래프 고리)
 */
final class WbsSchedule {

    static final Set<String> DEP_TYPES = Set.of("FS", "SS", "FF");

    private WbsSchedule() {
    }

    /** FS 후행이 지켜야 할 가장 이른 시작일 */
    static LocalDate earliestStart(TaskRow pred, int lag) {
        return pred.getPlanEnd().plusDays(1L + lag);
    }

    /**
     * 종류별 — 선행의 (시작, 종료) 와 후행 기간이 주어졌을 때 후행이 지켜야 할 가장 이른 시작일.
     * succDays = 후행 기간(양 끝 포함 일수, 1 이상)
     */
    static LocalDate earliestStart(String type, LocalDate predStart, LocalDate predEnd, long succDays, int lag) {
        return switch (type == null ? "FS" : type) {
            case "SS" -> predStart.plusDays(lag);
            case "FF" -> predEnd.plusDays(lag).minusDays(succDays - 1);
            default -> predEnd.plusDays(1L + lag);
        };
    }

    static long days(TaskRow t) {
        return ChronoUnit.DAYS.between(t.getPlanStart(), t.getPlanEnd()) + 1;
    }

    /** FS 위반(기존 호출용) */
    static boolean violated(TaskRow pred, TaskRow succ, int lag) {
        return violated("FS", pred, succ, lag);
    }

    static boolean violated(String type, TaskRow pred, TaskRow succ, int lag) {
        return succ.getPlanStart().isBefore(earliestStart(type, pred.getPlanStart(), pred.getPlanEnd(), days(succ), lag));
    }

    /** 의존마다 violated 를 채우고, 위반된 후행 작업 id 를 돌려준다 */
    static Set<Long> markViolations(Map<Long, TaskRow> byId, List<DepRow> deps) {
        Set<Long> out = new HashSet<>();
        for (DepRow d : deps) {
            TaskRow p = byId.get(d.getPredTaskId());
            TaskRow s = byId.get(d.getSuccTaskId());
            d.setViolated(p != null && s != null && violated(d.getDepType(), p, s, d.getLagDays()));
            if (d.isViolated()) {
                out.add(d.getSuccTaskId());
            }
        }
        return out;
    }

    /** 밀기 결과: 움직일 것(moves)과 위반이지만 움직이지 않는 것(fixed) */
    record Plan(List<PushMove> moves, List<PushMove> fixed) {
    }

    static boolean fixedTask(TaskRow t) {
        return "DONE".equals(t.getStatus()) || "CANCELLED".equals(t.getStatus());
    }

    /**
     * 위상 순서(Kahn)로 후행 시작 = max(선행 새 종료 + 1 + lag) 까지 민다. 이미 충분히 늦으면 그대로.
     * 순환이 있으면(직접 SQL 등 — 트리거가 막는다) IllegalStateException
     */
    static Plan push(Map<Long, TaskRow> byId, List<DepRow> deps) {
        Map<Long, List<DepRow>> out = new HashMap<>();
        Map<Long, Integer> indeg = new LinkedHashMap<>();
        for (DepRow d : deps) {
            if (!byId.containsKey(d.getPredTaskId()) || !byId.containsKey(d.getSuccTaskId())) {
                continue;
            }
            out.computeIfAbsent(d.getPredTaskId(), k -> new ArrayList<>()).add(d);
            indeg.putIfAbsent(d.getPredTaskId(), 0);
            indeg.merge(d.getSuccTaskId(), 1, Integer::sum);
        }
        Map<Long, LocalDate> start = new HashMap<>();
        Map<Long, LocalDate> end = new HashMap<>();
        indeg.keySet().forEach(id -> {
            start.put(id, byId.get(id).getPlanStart());
            end.put(id, byId.get(id).getPlanEnd());
        });
        Deque<Long> ready = new ArrayDeque<>();
        indeg.forEach((id, n) -> {
            if (n == 0) {
                ready.add(id);
            }
        });
        Map<Long, LocalDate> need = new HashMap<>();
        int seen = 0;
        List<PushMove> moves = new ArrayList<>();
        List<PushMove> fixed = new ArrayList<>();
        while (!ready.isEmpty()) {
            long id = ready.poll();
            seen++;
            TaskRow t = byId.get(id);
            LocalDate req = need.get(id);
            if (req != null && req.isAfter(start.get(id))) {
                long shift = ChronoUnit.DAYS.between(start.get(id), req);
                if (fixedTask(t)) {
                    fixed.add(move(t, t.getPlanStart(), t.getPlanEnd(), (int) shift));
                } else {
                    start.put(id, req);
                    end.put(id, end.get(id).plusDays(shift));
                    moves.add(move(t, start.get(id), end.get(id), (int) shift));
                }
            }
            for (DepRow d : out.getOrDefault(id, List.of())) {
                LocalDate e = earliestStart(d.getDepType(), start.get(id), end.get(id), days(byId.get(d.getSuccTaskId())), d.getLagDays());
                need.merge(d.getSuccTaskId(), e, (a, b) -> a.isAfter(b) ? a : b);
                if (indeg.merge(d.getSuccTaskId(), -1, Integer::sum) == 0) {
                    ready.add(d.getSuccTaskId());
                }
            }
        }
        if (seen < indeg.size()) {
            throw new IllegalStateException("dependency cycle");
        }
        return new Plan(moves, fixed);
    }

    private static PushMove move(TaskRow t, LocalDate ns, LocalDate ne, int shift) {
        return new PushMove(t.getTaskId(), t.getWbsNo(), t.getTaskNm(), t.getPlanStart(), t.getPlanEnd(), ns, ne, shift);
    }

    /** 의존을 더하면 순환이 되나 — 후행에서 의존을 따라가 선행에 닿으면 순환 */
    static boolean makesCycle(List<DepRow> deps, long pred, long succ) {
        Map<Long, List<Long>> next = new HashMap<>();
        for (DepRow d : deps) {
            next.computeIfAbsent(d.getPredTaskId(), k -> new ArrayList<>()).add(d.getSuccTaskId());
        }
        Deque<Long> stack = new ArrayDeque<>(List.of(succ));
        Set<Long> seen = new HashSet<>();
        while (!stack.isEmpty()) {
            long n = stack.pop();
            if (n == pred) {
                return true;
            }
            if (seen.add(n)) {
                stack.addAll(next.getOrDefault(n, List.of()));
            }
        }
        return false;
    }
}
