package io.smarthappy.module.plm.link;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** LOT → ECO 조회 (PLM 3b, docs/39 13.3) — (가) 리비전 경로 + (나) 표시, ECO 하나에 한 줄(둘 다면 REVISION). ECO → 표시 LOT(16장), 출하 확인(17장) */
@Component
@RequiredArgsConstructor
class EcoLotTrace implements EcoTrace {

    private final PlmLinkMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public List<EcoRef> ecosOfLot(long lotId) {
        return mapper.ecosOfLot(lotId).stream()
                .map(r -> new EcoRef(r.getEcoId(), r.getEcoNo(), r.getTitle(), r.getEffectiveDate(), r.getCustApprovalStatus(),
                        Via.valueOf(r.getVia())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FirstLot> firstLotsOfItem(long itemId, int limit) {
        if (limit < 1 || limit > MAX_FIRST_LOTS) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_FIRST_LOTS + ": " + limit);
        }
        return mapper.firstLotsOfItem(itemId, limit).stream()
                .map(r -> new FirstLot(r.getEcoId(), r.getEcoNo(), r.getLotId(), r.getLotNo(), r.getSeqNo(), r.getOccurredAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MarkedLot> markedLots(long ecoId) {
        return mapper.markedLots(ecoId).stream()
                .map(r -> new MarkedLot(r.getLotId(), r.getLotNo(), r.getMarkReason(), r.getSeqNo(), r.getOccurredAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShipCheck> shipChecks(Collection<Long> lotIds) {
        if (lotIds == null || lotIds.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        lotIds.stream().filter(Objects::nonNull).forEach(ids::add);
        if (ids.size() > EcoLookup.MAX_IDS) {
            throw new IllegalArgumentException("too many LOT ids: " + ids.size() + " > " + EcoLookup.MAX_IDS);
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        return mapper.shipChecks(ids).stream()
                .map(r -> {
                    boolean interim = "INTERIM".equals(r.getPpapStatus());
                    return new ShipCheck(r.getLotId(), r.getEcoId(), r.getEcoNo(), interim ? "PPAP_INTERIM" : "PPAP_OPEN", r.getPpapStatus(), !interim);
                })
                .toList();
    }
}
