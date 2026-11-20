package io.smarthappy.module.wms.inv;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.wms.link.ReceiptLocQuery;
import lombok.RequiredArgsConstructor;

/** 입고 가능 위치 계약 구현 (docs/44 S2) — 상태는 저장과 같은 {@link ProductionReceiptPosting#status} */
@Component
@RequiredArgsConstructor
class WmsReceiptLocQuery implements ReceiptLocQuery {

    private final ReceiptPostingMapper mapper;
    private final ProductionReceiptPosting posting;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ReceiptLoc> productionReceipt(String plantCd, long itemId, Long lotId) {
        String lotCtrl = mapper.lotCtrlOf(itemId);
        if (plantCd == null || plantCd.isBlank() || lotCtrl == null) {
            return List.of();                               // 없는 공장·품목(다른 회사 포함) — 후보 없음
        }
        Long lot = "Y".equals(lotCtrl) ? lotId : null;
        Map<String, String> byDefault = new HashMap<>();             // 상태는 위치 기본 상태로만 갈린다 — 판정·계획 조회는 기본 상태마다 한 번
        return mapper.receiptNodes(plantCd).stream().map(n -> {
            String reason = ProductionReceiptPosting.nodeReason(n.getUseYn(), n.getStockYn(), n.getLocUsage());
            String status = reason == null ? byDefault.computeIfAbsent(String.valueOf(n.getDefaultStatus()),
                    k -> posting.status(plantCd, itemId, lot, lotCtrl, n.getDefaultStatus())) : null;
            return new ReceiptLoc(n.getNodeId(), n.getNodeCd(), n.getPath(), status, reason);
        }).toList();
    }
}
