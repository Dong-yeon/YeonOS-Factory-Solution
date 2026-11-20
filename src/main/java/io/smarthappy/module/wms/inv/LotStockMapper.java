package io.smarthappy.module.wms.inv;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

/** LOT 계보 → 재고 원장 (P4 2부 B5·B2+, docs/30 12.4). lot_genealogy·prd_wo_proc·prd_wo 는 읽기만 */
@Mapper
public interface LotStockMapper {

    /** 계보 행 + 부모 LOT 품목·단위 + 공정의 공장·노드(투입이 아니면 null). 다른 회사면 RLS 로 null */
    GenRow findGen(@Param("genId") long genId);

    /** 공정 노드와 그 위 LINE(CELL 이면 부모 LINE) — LINESIDE 보급 라인 비교용 */
    List<Long> feedNodesOf(@Param("nodeId") long nodeId);

    /** 투입 후보: AVAILABLE·수량 > 0·SCRAP 아님. 순서 = 보급 라인 LINESIDE → pick_seq → 노드 id (D-B6·D-B12·D-B13) */
    /** 출하 대기(STAGING) 칸에만 남은 가용 수량 — 투입 부족 때 안내(docs/53 F1) */
    java.math.BigDecimal stagingQty(@Param("plantCd") String plantCd, @Param("itemId") long itemId, @Param("lotId") Long lotId);

    List<Cell> issueCells(@Param("plantCd") String plantCd, @Param("itemId") long itemId, @Param("lotId") Long lotId,
            @Param("feeds") List<Long> feeds);

    /** 변환 후보: 부모 LOT 의 모든 칸(수량 > 0). 순서 = 상태 순서(D-B11) → pick_seq → 노드 id */
    List<Cell> convCells(@Param("lotId") long lotId, @Param("statusOrder") List<String> statusOrder);

    /** 고른 칸을 칸 순서(품목·위치·LOT·상태 — InventoryService.CELL_ORDER)로 잠그고 지금 수량을 다시 읽는다(경로마다 우선순위가 달라도 잠금 순서는 같게) */
    List<Cell> lockCells(@Param("stockIds") List<Long> stockIds);

    /**
     * 재고로 관리되는 LOT 인가(D-B14) — 한 번이라도 원장에 들어온 LOT: LOT 관리 품목은 lot_id, 비관리 품목은 입고 출처
     * (자재 입고 src_id = LOT, 생산 입고 prd_receipt.lot_id). 아니면 재공(WIP) — 투입·변환을 원장에 반영하지 않는다
     */
    boolean stockTracked(@Param("lotId") long lotId);

    /** 이 LOT 의 마지막 원장 공장(재고가 0 이 된 뒤에도) */
    String lastPlantOf(@Param("lotId") long lotId);

    /** 이 계보 행에서 나온 아직 안 되돌린 원장(투입 = ISSUE_WO 행들, 변환 = CONV_OUT 행 — 짝은 역분개가 같이) */
    List<Long> liveTrxOf(@Param("genId") long genId);

    @Getter
    @Setter
    class GenRow {
        private long genId;
        private String relType;
        private long parentLotId;
        private long childLotId;
        private BigDecimal qty;
        private String unitCd;
        private Long woProcId;
        private OffsetDateTime occurredAt;
        private String recordedBy;
        private Long performedBy;
        private Long reversalOfId;
        private String reason;
        private long itemId;
        private String itemUnitCd;
        private String lotCtrlYn;
        private String procPlantCd;
        private Long procNodeId;
    }

    @Getter
    @Setter
    class Cell {
        private long stockId;
        private String plantCd;
        private long nodeId;
        private String stockStatus;
        private BigDecimal qty;
    }
}
