package io.smarthappy.module.wms.ship;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.wms.ship.ShipDtos.NodeOpt;
import io.smarthappy.module.wms.ship.ShipDtos.OrderLineOpt;
import io.smarthappy.module.wms.ship.ShipDtos.PickRow;
import io.smarthappy.module.wms.ship.ShipDtos.ShipHead;
import io.smarthappy.module.wms.ship.ShipDtos.ShipLine;
import io.smarthappy.module.wms.ship.ShipDtos.ShipPick;
import io.smarthappy.module.wms.ship.ShipDtos.ShipTrx;

/** 출하(B6a) — 영업 표는 읽지 않는다(수주 행은 영업 계약 OrderLineQuery — docs/30 12.7.5) */
@Mapper
public interface ShipMapper {

    boolean plantExists(@Param("plantCd") String plantCd);

    List<NodeOpt> stageNodes(@Param("plantCd") String plantCd);

    /** 사용 중 판매 거래처(매출·겸용) */
    Long custIdByCode(@Param("custCd") String custCd);

    // ---------------------------------------------------------------- 팔레트 (B6b)

    List<ShipDtos.Pallet> findPallets(@Param("shipId") long shipId);

    List<ShipHead> findShipPage(@Param("plantCd") String plantCd, @Param("status") String status, @Param("from") LocalDate from,
            @Param("to") LocalDate to, @Param("page") io.smarthappy.platform.common.query.PageQuery page);

    long countShips(@Param("plantCd") String plantCd, @Param("status") String status, @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    ShipDtos.PalletHit findPalletByKey(@Param("palletNo") String palletNo, @Param("sscc") String sscc);

    ShipDtos.Pallet lockPallet(@Param("shipId") long shipId, @Param("palletId") long palletId);

    long insertPallet(@Param("shipId") long shipId, @Param("palletNo") String palletNo, @Param("remark") String remark, @Param("by") String by);

    int closePallet(@Param("palletId") long palletId, @Param("sscc") String sscc, @Param("by") String by);

    int setPalletStatus(@Param("palletId") long palletId, @Param("from") String from, @Param("to") String to, @Param("remark") String remark,
            @Param("by") String by);

    int setPickPallet(@Param("pickIds") Collection<Long> pickIds, @Param("palletId") Long palletId, @Param("by") String by);

    int touchShip(@Param("id") long shipId, @Param("by") String by);

    int countUnpalletized(@Param("shipId") long shipId);

    List<PalletItem> palletContent(@Param("palletId") long palletId);

    @lombok.Getter
    @lombok.Setter
    class PalletItem {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private BigDecimal qty;
        private int lotCount;
    }

    /** 수주 행별로 열린(DRAFT·PICKING) 출하 지시에 잡힌 지시 수량 — 잡힌 행만 */
    /** 지시 만들기 줄 세우기(QA W-1) — 회사·수주 행 키 트랜잭션 advisory 잠금(커밋·롤백 때 풀림) */
    int lockOrderLineForShip(@Param("key") String key);

    List<Held> heldByOrderLine(@Param("lineIds") Collection<Long> orderLineIds);

    @lombok.Getter
    @lombok.Setter
    class Held {
        private long orderLineId;
        private BigDecimal qty;
    }

    List<ShipHead> findShips(@Param("plantCd") String plantCd, @Param("status") String status, @Param("from") LocalDate from,
            @Param("to") LocalDate to, @Param("limit") int limit);

    ShipHead findShip(@Param("id") long id);

    ShipHead lockShip(@Param("id") long id);

    List<ShipLine> findLines(@Param("id") long shipId);

    List<ShipPick> findPicks(@Param("id") long shipId);

    long insertShip(@Param("plantCd") String plantCd, @Param("shipNo") String shipNo, @Param("custId") long custId,
            @Param("stageNodeId") long stageNodeId, @Param("planDate") LocalDate planDate, @Param("remark") String remark, @Param("by") String by);

    void insertLine(@Param("shipId") long shipId, @Param("lineNo") int lineNo, @Param("orderLineId") long orderLineId,
            @Param("itemId") long itemId, @Param("qty") BigDecimal qty, @Param("by") String by);

    int updateStatus(@Param("id") long id, @Param("from") String from, @Param("to") String to, @Param("by") String by);

    int markShipped(@Param("id") long id, @Param("by") String by);

    int markUnshipped(@Param("id") long id, @Param("by") String by);

    /** 이 출하에 걸린 살아 있는 고객 반품 번호 하나(없으면 null) — docs/59 */
    String liveCustRtnOf(@Param("id") long id);

    int markCancelled(@Param("id") long id, @Param("reason") String reason, @Param("by") String by);

    long insertPick(@Param("shipLineId") long shipLineId, @Param("fromNodeId") long fromNodeId, @Param("lotId") Long lotId,
            @Param("qty") BigDecimal qty, @Param("by") String by);

    int setPickStatus(@Param("pickIds") Collection<Long> pickIds, @Param("from") String from, @Param("to") String to, @Param("by") String by);

    /** 상태별 피킹(+ 줄·품목) */
    List<PickRow> picksOf(@Param("shipId") long shipId, @Param("status") String status);

    /** 피킹 이동 짝 중 아직 되돌리지 않은 MOVE_OUT 원장 id */
    Long liveMoveOf(@Param("pickId") long pickId);

    /** 피킹의 되돌리지 않은 SHIP 원장 */
    List<ShipTrx> liveShipsOf(@Param("shipId") long shipId);

    /** 품목의 LOT 번호 → id (같은 회사) */
    Long lotIdByNo(@Param("itemId") long itemId, @Param("lotNo") String lotNo);

    /** 줄 품목의 가용 재고 칸 — 이 지시의 출하 대기 칸과 가용 정의(AvailSql: STAGING·QC·SCRAP 상위 포함) 밖 칸 제외, nodeId = 그 위치 하위만, lotId = 그 LOT 만 */
    List<ShipDtos.StockRow> pickStock(@Param("plantCd") String plantCd, @Param("itemId") long itemId, @Param("stageNodeId") long stageNodeId,
            @Param("nodeId") Long nodeId, @Param("lotId") Long lotId);

    /** 노드가 이 공장의 재고 위치인가(SCRAP·출하 대기 제외는 서비스가) */
    String nodeUsage(@Param("nodeId") long nodeId, @Param("plantCd") String plantCd);

    /** 피킹 출발 칸으로 꺼낼 수 있는가 — 가용 정의(AvailSql.availLoc: STAGING·QC 아님, SCRAP 상위 포함 아님) */
    boolean pickableLoc(@Param("nodeId") long nodeId);
}
