package io.smarthappy.module.pop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.smarthappy.module.aps.link.ApsDispatchQuery;
import io.smarthappy.module.aps.link.ApsDispatchQuery.Slot;
import io.smarthappy.module.pop.PopDtos.PlanSlot;
import io.smarthappy.module.pop.PopDtos.TaskRow;
import lombok.RequiredArgsConstructor;

/**
 * POP 일일 배정 순서 (docs/25 4.56, docs/63 G5·AP-10 ⓐ) — "내 할 일"을 APS 현행 계획의 자원별 순서로 놓고 계획 시각을 붙인다.
 * APS 표·매퍼는 건드리지 않고 읽기 계약 {@link ApsDispatchQuery} 만 부른다(APS 없는 설치본 = NONE → 지금 순서 그대로).
 *
 * - 계획에 있는 공정이 먼저(계획 시작 → 자원 코드 → 순번), 계획에 없는 공정은 그 뒤에 원래 순서(우선순위·계획일·지시 번호·공정 순)대로
 * - 순서 어김은 <b>표시만</b>: 같은 자원에서 계획상 앞인 공정이 아직 시작 전인데 이 공정이 시작됐으면 early. 착수를 막지 않는다
 * - "시작했다" 의 기준은 APS 배정표와 같다(RUN·PAUSE·DONE) — 두 화면이 같은 공정을 다르게 말하지 않게
 * 트랜잭션 안에서 부른다(계약이 읽기 트랜잭션을 요구).
 */
@Component
@RequiredArgsConstructor
class PopDispatch {

    /** APS 배정표(ApsDispatchService.STARTED)와 같은 기준 */
    static final Set<String> STARTED = Set.of("RUN", "PAUSE", "DONE");

    private final ObjectProvider<ApsDispatchQuery> aps;
    private final PopMapper mapper;

    /** 목록에 계획 자리를 붙이고 계획 순서로 다시 놓는다. 현행 계획이 없으면 받은 그대로 */
    List<TaskRow> arrange(String plantCd, List<TaskRow> tasks) {
        if (tasks.isEmpty()) {
            return tasks;
        }
        return arrange(tasks, ApsDispatchQuery.of(aps).slots(plantCd, tasks.stream().map(TaskRow::getWoProcId).toList()));
    }

    /** 공정 화면용 — 이 단말 목록 기준 그 공정의 자리(목록 밖·계획 밖이면 null) */
    PlanSlot slotOf(TerminalPrincipal t, long woProcId) {
        return arrange(t.plantCd(), mapper.findTasks(t.nodeId(), t.plantCd(), PopService.TASK_LIMIT)).stream()
                .filter(r -> r.getWoProcId() == woProcId).findFirst().map(TaskRow::getPlan).orElse(null);
    }

    /** 순수 함수 — 정렬 + 앞선 미착수 수. tasks 의 순서(원래 정렬)는 계획 밖 공정과 같은 자리 다툼에 그대로 쓴다 */
    static List<TaskRow> arrange(List<TaskRow> tasks, Map<Long, Slot> slots) {
        if (slots == null || slots.isEmpty()) {
            return tasks;
        }
        Map<Long, Integer> original = new HashMap<>();
        for (int i = 0; i < tasks.size(); i++) {
            original.putIfAbsent(tasks.get(i).getWoProcId(), i);
        }
        List<TaskRow> planned = new ArrayList<>();
        List<TaskRow> rest = new ArrayList<>();
        for (TaskRow r : tasks) {
            (slots.get(r.getWoProcId()) == null ? rest : planned).add(r);
        }
        Comparator<TaskRow> byPlan = Comparator
                .comparing((TaskRow r) -> slots.get(r.getWoProcId()).startAt().toInstant())
                .thenComparing(r -> resCd(slots.get(r.getWoProcId())))
                .thenComparingInt(r -> slots.get(r.getWoProcId()).seq())
                .thenComparingInt(r -> original.get(r.getWoProcId()));
        planned.sort(byPlan);
        // 같은 자원 안에서 앞에 놓인(계획상 먼저인) 미착수 공정 수 — planned 가 이미 계획 시작 순이라 앞에서부터 센다
        Map<String, Integer> waiting = new HashMap<>();
        for (TaskRow r : planned) {
            Slot s = slots.get(r.getWoProcId());
            String key = resKey(s);
            int ahead = waiting.getOrDefault(key, 0);
            boolean started = STARTED.contains(r.getProcStatus());
            r.setPlan(new PlanSlot(s.planNo(), s.seq(), resCd(s), s.startAt(), s.endAt(), ahead, started && ahead > 0));
            if (!started) {
                waiting.merge(key, 1, Integer::sum);
            }
        }
        List<TaskRow> out = new ArrayList<>(planned);
        out.addAll(rest);
        return out;
    }

    private static String resKey(Slot s) {
        return s.resKind() + ":" + (s.equipId() != null ? s.equipId() : s.nodeId());
    }

    private static String resCd(Slot s) {
        return s.equipCd() != null ? s.equipCd() : s.nodeCd() != null ? s.nodeCd() : "";
    }
}
