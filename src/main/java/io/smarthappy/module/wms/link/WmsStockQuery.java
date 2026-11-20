package io.smarthappy.module.wms.link;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.wms.link.WmsLinkMapper.ItemQty;
import io.smarthappy.platform.policy.PolicyService;
import lombok.RequiredArgsConstructor;

/** 재고 조회 계약 구현 (docs/30 13장) — 잠그지 않는 읽기(그 순간의 현재고 투영) */
@Component
@RequiredArgsConstructor
class WmsStockQuery implements StockQuery {

    /** 품질 상태 연동 공장 스위치(docs/30 12.15, D-B28) */
    static final String QUALITY_POLICY = "WMS_QUALITY_LINK";

    private final WmsLinkMapper mapper;
    private final PolicyService policy;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> available(String plantCd, Collection<Long> itemIds) {
        Map<Long, BigDecimal> m = StockQuery.zeros(itemIds);
        if (m.isEmpty() || plantCd == null || plantCd.isBlank()) {
            return m;
        }
        List<ItemQty> rows = mapper.availableQty(plantCd, List.copyOf(m.keySet()));
        rows.forEach(r -> m.put(r.getItemId(), r.getQty()));
        return m;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> reservedByShip(String plantCd, Collection<Long> itemIds) {
        Map<Long, BigDecimal> m = StockQuery.zeros(itemIds);
        if (m.isEmpty() || plantCd == null || plantCd.isBlank()) {
            return m;
        }
        mapper.reservedQty(plantCd, List.copyOf(m.keySet())).forEach(r -> m.put(r.getItemId(), r.getQty()));
        return m;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> lotStatusQty(long lotId) {
        Map<String, Boolean> on = new HashMap<>();
        Map<String, BigDecimal> m = new TreeMap<>();
        for (WmsLinkMapper.LotQty r : mapper.lotQty(lotId)) {
            if (on.computeIfAbsent(r.getPlantCd(), p -> "Y".equals(policy.value(QUALITY_POLICY, p)))) {
                m.merge(r.getStockStatus(), r.getQty(), BigDecimal::add);
            }
        }
        m.values().removeIf(q -> q.signum() == 0);
        return m;
    }

    @Override
    @Transactional(readOnly = true)
    public QcLots qcLots(String plantCd, Long afterLotId, int limit) {
        if (plantCd == null || plantCd.isBlank() || !"Y".equals(policy.value(QUALITY_POLICY, plantCd))) {
            return QcLots.EMPTY;
        }
        int n = Math.clamp(limit, 1, QC_LOTS_MAX);
        List<QcLot> rows = mapper.qcLots(plantCd, afterLotId, n + 1);      // 한 건 더 읽어 잘림을 안다
        return rows.size() > n ? new QcLots(List.copyOf(rows.subList(0, n)), true) : new QcLots(List.copyOf(rows), false);
    }
}
