package io.smarthappy.module.sales;

import java.math.BigDecimal;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

/** 출하 게이트 (OrderShipGate, docs/25 4.22). 회사 조건은 RLS. 잠금 순서 = 수주 머리(order_id 순) → 행(line_id 순) */
@Mapper
public interface SalesShipMapper {

    /** 행 → 수주(잠금 없음) — 머리부터 잠그려고 먼저 읽는다 */
    List<ShipLine> lineOrders(@Param("ids") List<Long> lineIds);

    /** 행 잠금(line_id 순) */
    List<ShipLine> lockLines(@Param("ids") List<Long> lineIds);

    int updateShipped(@Param("id") long lineId, @Param("shipped") BigDecimal shipped, @Param("status") String status,
            @Param("doneByShip") String doneByShip, @Param("by") String by);

    /** 자동 마감 행이 다시 열려 CLOSED 머리를 연다 */
    int reopenOrder(@Param("id") long orderId, @Param("by") String by);

    @Getter
    @Setter
    class ShipLine {
        private long lineId;
        private long orderId;
        private String orderNo;
        private int lineNo;
        private BigDecimal qty;
        private BigDecimal shippedQty;
        private String status;
        private String doneByShipYn;
    }
}
