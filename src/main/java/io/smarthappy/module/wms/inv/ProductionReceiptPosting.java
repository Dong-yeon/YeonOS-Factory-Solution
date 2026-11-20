package io.smarthappy.module.wms.inv;

import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.wms.inv.InvDtos.TrxReq;
import io.smarthappy.module.wms.inv.ReceiptPostingMapper.SrcReceipt;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * 생산 입고 → 재고 원장 (P4 2부 B1, docs/30 11장 — MES 합의 2026-10-21). **MES 가 부르는 계약**:
 * {@code ReceiptService.receiveAs}·{@code correct} 에서 prd_receipt INSERT 직후, ERP 발행 전에 같은 트랜잭션으로 {@link #post}.
 *
 * <ul>
 * <li>멱등 키 {@code PRD_RECEIPT:{receiptId}} — 이관(backfill)과 실시간 호출이 같은 함수라 순서가 꼬이지 않는다</li>
 * <li>입고 = RCV_PRD(위치의 기본 상태, 없으면 AVAILABLE), 정정 = 원래 입고 원장의 역분개(정정한 때 — 원래 달이 마감돼도 이번 달)</li>
 * <li>단위가 지금 품목 기본 단위와 다르면 칸 오류 UNIT(환산하지 않음 — MES Q1)</li>
 * <li>LOT 관리 품목은 입고 LOT, 비관리 품목은 LOT 비움(src_id 로 연결 — MES Q2)</li>
 * <li>오류는 그대로 던진다 — MES 입고와 재고가 같이 롤백되어 갈라지지 않는다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ProductionReceiptPosting {

    static final String SRC = "PRD_RECEIPT";

    private final ReceiptPostingMapper mapper;
    private final InventoryService inventory;
    private final QcReceiptRule qcRule;

    /** MES 실시간 호출 — 입력 방식·입력자는 입고 행 그대로. 반환 = 원장 행 id(정정이면 역분개 행) */
    @Transactional(propagation = Propagation.MANDATORY)
    public long post(long receiptId) {
        return post(receiptId, false);
    }

    /**
     * @param importMode 이관 — 과거 사실의 옮겨 적기(entry_method IMPORT: 사용 중지 품목·위치 검사 건너뜀).
     *                   실시간 호출에서도 정정의 원래 입고가 아직 없으면 그 입고는 이관으로 먼저 넣는다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    long post(long receiptId, boolean importMode) {
        String key = key(receiptId);
        Long done = mapper.trxOf(key);
        if (done != null) {
            return done;
        }
        SrcReceipt r = mapper.findReceipt(receiptId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!r.getUnitCd().equals(r.getItemUnitCd())) {
            throw InventoryService.fieldError("unitCd", "UNIT");        // 입고 뒤 품목 기본 단위가 바뀜(D-B1) — 섞어 넣지 않는다
        }
        if (r.getReversalOfId() == null) {
            String bad = importMode ? null : nodeReason(r.getNodeUseYn(), r.getStockYn(), r.getLocUsage());
            if (bad != null) {
                throw InventoryService.fieldError("nodeId", bad);     // 이관은 그때 사실이라 위치 규칙을 건너뛴다(원장 트리거와 같이)
            }
            Long lot = "Y".equals(r.getLotCtrlYn()) ? r.getLotId() : null;
            String status = importMode ? (r.getDefaultStatus() == null ? "AVAILABLE" : r.getDefaultStatus())
                    : status(r.getPlantCd(), r.getItemId(), lot, r.getLotCtrlYn(), r.getDefaultStatus());
            String method = importMode ? "IMPORT" : r.getEntryMethod();
            TrxReq t = new TrxReq("RCV_PRD", r.getItemId(), r.getNodeId(), status, r.getQty(), null, SRC, r.getReceiptId(), null, null,
                    r.getOccurredAt().withOffsetSameInstant(ZoneOffset.UTC), method, r.getPerformedBy(), lot);
            // 실시간 = 입고한 사람, 이관 = 이관을 돌린 사람(원래 입력자는 출처 행에 남아 있다)
            return inventory.postAs(List.of(t), key, importMode ? InventoryService.me().loginId() : r.getRecordedBy()).trxIds().get(0);
        }
        // 정정: 원래 입고의 원장(없으면 먼저 이관으로) → 정정한 때로 역분개.
        // 같은 입고를 동시에 두 번 넣으면 멱등 키 unique 에서 409 DUPLICATE_REQUEST(InventoryService) — 호출자 트랜잭션째 롤백
        long orig = post(r.getReversalOfId(), true);
        String reason = "[" + key(r.getReversalOfId()) + "] " + (r.getReason() == null ? "" : r.getReason().strip());
        return inventory.reverseAt(orig, reason.length() > 500 ? reason.substring(0, 500) : reason, r.getOccurredAt().withOffsetSameInstant(ZoneOffset.UTC), key, r.getRecordedBy())
                .get(0);
    }

    /**
     * 생산 입고를 받을 수 없는 위치의 사유 — null = 받음. 실시간 입고와 입고 가능 위치 계약(ReceiptLocQuery)이 같은 판정을 쓴다.
     * 사용 중지 위치·재고 안 둠 위치(원장 트리거와 같은 규칙) + 출하 대기(STAGING) 칸(출하분과 생산분을 섞지 않음, docs/53 F1)
     */
    static String nodeReason(String useYn, String stockYn, String locUsage) {
        if (!"Y".equals(useYn)) {
            return "NODE_INACTIVE";
        }
        if (!"Y".equals(stockYn)) {
            return "STOCK_NOT_ALLOWED";
        }
        return "STAGING".equals(locUsage) ? "STAGING_NOT_ALLOWED" : null;
    }

    /**
     * 실시간 입고의 재고 상태 — 위치 기본 상태(없으면 AVAILABLE) → 출하검사 대상 QC·NG LOT 추가분 BLOCKED(12.15.6·12.15.13).
     * 입고 가능 위치 계약(ReceiptLocQuery)이 같은 메서드로 미리 보여 준다
     */
    String status(String plantCd, long itemId, Long lotId, String lotCtrlYn, String defaultStatus) {
        return qcRule.status(plantCd, itemId, lotId, lotCtrlYn, "FINAL", defaultStatus == null ? "AVAILABLE" : defaultStatus);
    }

    static String key(long receiptId) {
        return SRC + ":" + receiptId;
    }
}
