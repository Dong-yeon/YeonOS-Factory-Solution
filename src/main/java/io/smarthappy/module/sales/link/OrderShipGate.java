package io.smarthappy.module.sales.link;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 영업 출하 게이트 (docs/25 4.22, docs/30 12.5 — WMS B6 출하 확정·취소가 부른다). 영업 표를 직접 고치지 않는다.
 *
 * - 부르는 쪽(WMS 출하) 트랜잭션 안(MANDATORY), 출하 원장 INSERT 와 같은 트랜잭션 — 원장 한 행 = 게이트 한 번(따로 멱등 키 없음)
 * - 한 출하의 수주 행을 **한 번에** 넘긴다: 게이트가 수주 머리 order_id 순 → 행 line_id 순으로 잠근다(기존 영업 순서 머리 → 행).
 *   부르는 쪽은 그 뒤에 lot_master → inv_stock → 기간 순으로(docs/30 12.5.2)
 * - qty > 0 = 출하(미결·허용 초과율 확인, 다 나가면 행 자동 마감), qty < 0 = 출하 취소(되돌림, 자동 마감 행은 다시 연다)
 * - 거부(업무 전체를 되돌림, 칸 오류 없는 BusinessException): 409 OVER_SHIP(허용 초과) · 409 ORDER_LINE_CLOSED(취소·사람 마감 행에 출하) ·
 *   404(없는·다른 회사 행). 0 수량·되돌림이 출하 수량보다 큼 = IllegalArgumentException(부르는 쪽 결함)
 * 영업 모듈이 없는 설치본은 {@link #NONE}(투영 없음).
 */
public interface OrderShipGate {

    /** 수주 행 하나의 출하(+)·취소(−) 수량 — 품목 기본 단위 */
    record ShipQty(long orderLineId, BigDecimal qty) {
    }

    /** 투영 없음 — 영업 모듈이 없는 설치본(빈 없음) */
    OrderShipGate NONE = lines -> {
    };

    /** 같은 행이 여러 번 오면 합친다 */
    void ship(List<ShipQty> lines);

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static OrderShipGate of(ObjectProvider<OrderShipGate> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
