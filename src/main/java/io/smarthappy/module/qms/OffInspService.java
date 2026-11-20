package io.smarthappy.module.qms;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.production.link.LotLookup;
import io.smarthappy.module.production.link.LotLookup.LotView;
import io.smarthappy.module.qms.InspDtos.OffHead;
import io.smarthappy.module.qms.InspDtos.OffPair;
import io.smarthappy.module.qms.InspDtos.OffRow;
import io.smarthappy.module.qms.InspDtos.OffSide;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;

/**
 * 초품(FIRST_OFF)·종품(LAST_OFF) 판정 — 같은 지시·공정 단위 (docs/29_P3 27장, IATF 16949 8.5.1.3).
 *
 * - 판정 = 그 유형 검사의 판정 확정(JUDGED, OK/NG) — 생성·측정·판정은 검사 실행(Q6) 흐름 그대로. 무효 검사는 보지 않는다
 * - "같은 지시" = 검사 LOT 의 작업지시(MES LotLookup R-2 계약). 지시 없는 LOT(입고·재작업)·MES 없음이면 짝을 찾지 않는다
 * - 짝 찾기(27.4, 사용자 결정 Q-1): ① 지시의 LOT 들(LotLookup.lotsOfWo, 최대 500)에서 바로 거름 ② LOT 을 못 받으면(계약 구현 없음)
 *   지금 방식 — 품목·공정 최근 200건을 지시로 거름. ① 이 상한에 걸리면 ② 를 더해 합치고, 어느 쪽이든 상한에 걸리면 truncated
 * - 한쪽 상태 = 가장 최근 판정의 결과(OK·NG), 판정이 없고 검사만 있으면 PENDING, 없으면 NONE
 * - 읽기만, 잠금 없음
 */
@Service
public class OffInspService {

    static final String FIRST_OFF = "FIRST_OFF";
    static final String LAST_OFF = "LAST_OFF";
    /** 같은 품목·공정에서 짝 후보로 읽는 최근 검사 수 — 한 번 조회량을 묶는다(넘는 옛 검사는 짝에서 빠질 수 있음, 27장) */
    static final int CANDIDATE_LIMIT = 200;
    static final String WO_LOTS = "WO_LOTS";
    static final String RECENT = "RECENT";

    private final InspMapper mapper;
    private final ObjectProvider<LotLookup> lotLookup;

    public OffInspService(InspMapper mapper, ObjectProvider<LotLookup> lotLookup) {
        this.mapper = mapper;
        this.lotLookup = lotLookup;
    }

    /** 이 검사와 같은 지시·공정의 초품·종품 — 검사 유형과 무관하게 부를 수 있다(공정 검사 상세에서도 짝을 본다) */
    @Transactional(readOnly = true)
    public OffPair pair(long inspId) {
        OffHead h = mapper.offHead(inspId);
        if (h == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        LotLookup lots = LotLookup.of(lotLookup);
        Long woId = lots.lots(List.of(h.getLotId())).stream().findFirst().map(LotView::woId).orElse(null);
        if (woId == null) {
            return new OffPair(null, h.getProcId(), side(List.of()), side(List.of()), null, false);
        }
        List<Long> woLots = lots.lotsOfWo(woId, LotLookup.MAX_IDS).stream().map(LotView::lotId).toList();
        Map<Long, OffRow> byId = new LinkedHashMap<>();
        boolean truncated = false;
        String basis;
        if (!woLots.isEmpty()) {
            basis = WO_LOTS;
            mapper.offOfWoLots(h.getProcId(), woLots).forEach(r -> byId.put(r.getInspId(), r));
            truncated = woLots.size() >= LotLookup.MAX_IDS;
        } else {
            basis = RECENT;
        }
        if (woLots.isEmpty() || truncated) {
            // LOT 을 못 받았거나 LOT 이 상한을 넘음 — 품목·공정 최근 검사를 지시로 거른 것을 더한다
            List<OffRow> cands = mapper.offCandidates(h.getItemId(), h.getProcId(), CANDIDATE_LIMIT);
            truncated = truncated || cands.size() >= CANDIDATE_LIMIT;
            Map<Long, Long> woOfLot = lots.lots(cands.stream().map(OffRow::getLotId).distinct().toList()).stream()
                    .filter(v -> v.woId() != null).collect(Collectors.toMap(LotView::lotId, LotView::woId, (a, b) -> a));
            cands.stream().filter(r -> woId.equals(woOfLot.get(r.getLotId()))).forEach(r -> byId.putIfAbsent(r.getInspId(), r));
        }
        List<OffRow> same = byId.values().stream()
                .sorted(Comparator.comparing(OffRow::getOccurredAt).reversed().thenComparing(Comparator.comparingLong(OffRow::getInspId).reversed()))
                .toList();
        return new OffPair(woId, h.getProcId(), side(byType(same, FIRST_OFF)), side(byType(same, LAST_OFF)), basis, truncated);
    }

    /** 공정 완료 경고용(27장) — 그 공정·산출 LOT 들의 종품 상태. LOT 이 없으면 NONE */
    @Transactional(readOnly = true)
    public OffSide lastOff(long procId, Collection<Long> outputLotIds) {
        List<Long> ids = outputLotIds == null ? List.of() : outputLotIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return side(List.of());
        }
        return side(mapper.offOfLots(LAST_OFF, procId, ids));
    }

    private static List<OffRow> byType(List<OffRow> rows, String type) {
        return rows.stream().filter(r -> type.equals(r.getInspType())).toList();
    }

    /** rows 는 최근부터. 상태 = 가장 최근 판정(judged_at)의 결과 */
    static OffSide side(List<OffRow> rows) {
        if (rows.isEmpty()) {
            return new OffSide("NONE", List.of());
        }
        String state = rows.stream().filter(r -> "JUDGED".equals(r.getStatus()) && r.getJudgedAt() != null)
                .max(Comparator.comparing(OffRow::getJudgedAt).thenComparing(OffRow::getInspId))
                .map(OffRow::getResult).orElse("PENDING");
        return new OffSide(state, new ArrayList<>(rows));
    }
}
