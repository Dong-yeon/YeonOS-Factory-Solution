package io.smarthappy.module.aps;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.aps.DispatchDtos.Board;
import io.smarthappy.module.aps.DispatchDtos.BoardRow;
import io.smarthappy.module.aps.DispatchDtos.CurrentPlan;
import io.smarthappy.module.aps.DispatchDtos.SlotRow;
import io.smarthappy.module.aps.link.ApsDispatchQuery;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 일일 작업 배정 (APS 9단계, docs/38 18.4·18.5 — docs/63 G5, 결정 AP-10 ⓐ). 현행 계획(공장의 마지막 반영 계획) 결과를 자원별 순서로.
 * {@link ApsDispatchQuery} 구현(POP 이 부를 계약) + 화면 배정표. 지시·공정은 읽기만, 순서 어김은 표시만.
 */
@Service
@RequiredArgsConstructor
public class ApsDispatchService implements ApsDispatchQuery {

    /** 진행했다고 보는 공정 상태 — 앞 순번이 아직 이 상태가 아니면 뒤 순번의 착수는 "순서 어김" */
    static final Set<String> STARTED = Set.of("RUN", "PAUSE", "DONE");

    private final DispatchMapper mapper;
    private final BizClock clock;

    // ------------------------------------------------------------------ 계약

    @Override
    @Transactional(readOnly = true)
    public List<Slot> sequence(String plantCd, LocalDate day) {
        if (VisibleText.isBlank(plantCd) || day == null) {
            return List.of();
        }
        CurrentPlan cur = mapper.findCurrent(plantCd.strip().toUpperCase(Locale.ROOT));
        return cur == null ? List.of() : mapper.findSlots(cur.getPlanId(), day, null).stream().map(ApsDispatchService::slot).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Slot> slots(String plantCd, Collection<Long> woProcIds) {
        if (woProcIds == null || woProcIds.isEmpty() || VisibleText.isBlank(plantCd)) {
            return Map.of();
        }
        Set<Long> ids = new LinkedHashSet<>(woProcIds);
        ids.remove(null);
        if (ids.size() > MAX_IDS) {
            throw new IllegalArgumentException("at most " + MAX_IDS + " work order processes");
        }
        CurrentPlan cur = ids.isEmpty() ? null : mapper.findCurrent(plantCd.strip().toUpperCase(Locale.ROOT));
        if (cur == null) {
            return Map.of();
        }
        Map<Long, Slot> out = new LinkedHashMap<>();
        for (SlotRow r : mapper.findSlots(cur.getPlanId(), null, ids)) {
            out.putIfAbsent(r.getWoProcId(), slot(r));     // 같은 공정이 계획에 두 번 나오지 않지만 첫 자리만
        }
        return out;
    }

    // ------------------------------------------------------------------ 화면 배정표

    /** 공장·날짜(비우면 공장 오늘) → 현행 계획 배정표. 현행 계획이 없으면 planId null·빈 행 */
    @Transactional(readOnly = true)
    public Board board(String plantCd, String day) {
        FieldErrors e = FieldErrors.create();
        if (VisibleText.isBlank(plantCd)) {
            e.add("plantCd", "REQUIRED");
        }
        LocalDate d = OrderService.date(day, "day", false, e);
        e.throwIfAny();
        String plant = plantCd.strip().toUpperCase(Locale.ROOT);
        if (d == null) {
            d = LocalDate.ofInstant(clock.instant(), clock.zone(plant));
        }
        CurrentPlan cur = plant.length() > 20 ? null : mapper.findCurrent(plant);
        if (cur == null) {
            return new Board(plant, d, null, null, null, null, List.of());
        }
        return new Board(plant, d, cur.getPlanId(), cur.getPlanNo(), cur.getAppliedAt(), cur.getTimeZone(),
                rows(mapper.findSlots(cur.getPlanId(), d, null)));
    }

    /** 순서 어김: 같은 자원에서 앞 순번 중 아직 착수 안 한 것이 있는데 이 행이 착수(RUN·PAUSE·DONE)했으면 표시 */
    static List<BoardRow> rows(List<SlotRow> src) {
        List<BoardRow> out = new ArrayList<>(src.size());
        String key = null;
        boolean waitingBefore = false;
        for (SlotRow r : src) {
            String k = r.getResKind() + ":" + r.getEquipId() + ":" + r.getNodeId();
            if (!Objects.equals(k, key)) {
                key = k;
                waitingBefore = false;
            }
            boolean started = r.getProcStatus() != null && STARTED.contains(r.getProcStatus());
            boolean out0 = started && waitingBefore;
            if (!started) {
                waitingBefore = true;
            }
            String res = "EQUIP".equals(r.getResKind()) ? r.getEquipCd() : "NODE".equals(r.getResKind()) ? r.getNodeCd() : null;
            out.add(new BoardRow(r.getSeq(), r.getResKind(), res, r.getWoId(), r.getWoNo(), r.getWoProcId(), r.getOpSeq(), r.getProcCd(),
                    r.getProcNm(), r.getStartAt(), r.getEndAt(), r.getItemCd(), r.getItemNm(), r.getQty(), r.getUnitCd(), r.getWoStatus(),
                    r.getProcStatus(), r.getCurEquipCd(), out0));
        }
        return out;
    }

    private static Slot slot(SlotRow r) {
        return new Slot(r.getPlanId(), r.getPlanNo(), r.getSeq(), r.getResKind(), r.getEquipId(), r.getEquipCd(), r.getNodeId(),
                r.getNodeCd(), r.getWoId(), r.getWoNo(), r.getWoProcId(), r.getOpSeq(), r.getProcCd(), r.getStartAt(), r.getEndAt());
    }
}
