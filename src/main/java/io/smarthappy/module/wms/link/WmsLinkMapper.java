package io.smarthappy.module.wms.link;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

/** 다른 모듈에 내주는 읽기 (docs/30 13장) — 잠금 없음 */
@Mapper
public interface WmsLinkMapper {

    /** 품목별 가용 수량 — 재고가 있는 품목만 */
    List<ItemQty> availableQty(@Param("plantCd") String plantCd, @Param("itemIds") List<Long> itemIds);

    /** 품목별 출하 예약(열린 지시 DRAFT·PICKING 의 미피킹 합) — 예약이 있는 품목만 */
    List<ItemQty> reservedQty(@Param("plantCd") String plantCd, @Param("itemIds") List<Long> itemIds);

    /** 수주 행별 출하 수량(SHIP 원장 합의 부호 반대, 역분개 포함) — 출하가 있는 행만 */
    List<LineQty> shippedByLine(@Param("lineIds") List<Long> orderLineIds);

    /** LOT 하나의 공장·상태별 수량 — 비관리 품목(원장 lot_id 없음)은 행이 없다 */
    List<LotQty> lotQty(@Param("lotId") long lotId);

    /** QC 재고가 남은 LOT(공장 위치 합 &gt; 0) — lot_id 오름차순, afterLotId 다음부터 limit 건 */
    List<StockQuery.QcLot> qcLots(@Param("plantCd") String plantCd, @Param("afterLotId") Long afterLotId, @Param("limit") int limit);

    /** LOT × 출하 지시의 순 출하(docs/57) — 팔레트는 탭으로 이은 글자 */
    List<LotShipRow> shipsByLots(@Param("lotIds") List<Long> lotIds);

    /** LOT 이 있는 칸·상태(가용 정의 없이, docs/57) */
    List<ShipQuery.LotStockRow> stockByLots(@Param("lotIds") List<Long> lotIds);

    /** 클레임에 묶인 고객 반품(docs/29_P3 44장) — capaNo·ncrIds 중 하나 이상은 서비스가 채운다 */
    List<ShipQuery.CustReturn> custReturns(@Param("capaNo") String capaNo, @Param("ncrIds") List<Long> ncrIds, @Param("limit") int limit);

    @Getter
    @Setter
    class LotShipRow {
        private long lotId;
        private long shipId;
        private String shipNo;
        private String plantCd;
        private long custId;
        private String custCd;
        private String custNm;
        private OffsetDateTime shippedAt;
        private long itemId;
        private BigDecimal qty;
        private String unitCd;
        private String palletNos;
        private String ssccs;
    }

    @Getter
    @Setter
    class LotQty {
        private String plantCd;
        private String stockStatus;
        private BigDecimal qty;
    }

    @Getter
    @Setter
    class LineQty {
        private long orderLineId;
        private BigDecimal qty;
    }

    @Getter
    @Setter
    class ItemQty {
        private long itemId;
        private BigDecimal qty;
    }
}
