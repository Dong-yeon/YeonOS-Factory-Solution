package io.smarthappy.module.wms.link;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.wms.link.WmsLinkMapper.LineQty;
import lombok.RequiredArgsConstructor;

/** 출하 조회 계약 구현 (B6a, docs/57) — 잠그지 않는 읽기 */
@Component
@RequiredArgsConstructor
class WmsShipQuery implements ShipQuery {

    private final WmsLinkMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> shippedByLine(Collection<Long> orderLineIds) {
        Map<Long, BigDecimal> m = StockQuery.zeros(orderLineIds);
        if (m.isEmpty()) {
            return m;
        }
        List<LineQty> rows = mapper.shippedByLine(List.copyOf(m.keySet()));
        rows.forEach(r -> m.put(r.getOrderLineId(), r.getQty()));
        return m;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LotShipment> byLots(Collection<Long> lotIds) {
        List<Long> ids = ids(lotIds);
        if (ids.isEmpty()) {
            return List.of();
        }
        return mapper.shipsByLots(ids).stream().map(r -> new LotShipment(r.getLotId(), r.getShipId(), r.getShipNo(), r.getPlantCd(),
                r.getCustId(), r.getCustCd(), r.getCustNm(), r.getShippedAt(), r.getItemId(), r.getQty(), r.getUnitCd(),
                split(r.getPalletNos()), split(r.getSsccs()))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LotStockRow> lotStock(Collection<Long> lotIds) {
        List<Long> ids = ids(lotIds);
        return ids.isEmpty() ? List.of() : mapper.stockByLots(ids);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustReturn> custReturns(String capaNo, Collection<Long> ncrIds) {
        String capa = capaNo == null || capaNo.isBlank() ? null : capaNo.strip();
        List<Long> ids = ids(ncrIds);
        if (capa == null && ids.isEmpty()) {
            return List.of();
        }
        return mapper.custReturns(capa, ids, RETURNS_MAX);
    }

    /** 중복·null 빼고 정렬, 상한 넘으면 IllegalArgumentException(LotLookup 과 같은 규칙) */
    private static List<Long> ids(Collection<Long> lotIds) {
        if (lotIds == null) {
            return List.of();
        }
        List<Long> ids = lotIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (ids.size() > LOTS_MAX) {
            throw new IllegalArgumentException("too many lot ids: " + ids.size() + " > " + LOTS_MAX);
        }
        return ids;
    }

    /** SQL string_agg(구분자 탭) → 목록, 없으면 빈 목록 */
    private static List<String> split(String joined) {
        return joined == null || joined.isEmpty() ? List.of() : Arrays.asList(joined.split("\t"));
    }
}
