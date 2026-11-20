package io.smarthappy.module.sales.link;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 수주 행 읽기 계약 (docs/25 4.23, WMS B6a — docs/30 12.7.5) — 다른 모듈이 {@code sal_order}·{@code sal_order_line} 을 직접 읽지 않게.
 * 읽기만, 잠금 없음, 회사는 RLS. 없는·다른 회사 행은 빠진다(예외 없음 — 부르는 쪽 트랜잭션을 rollback-only 로 만들지 않는다).
 * 쓰기(출하 수량)는 {@link OrderShipGate} 만. 영업이 없는 설치본은 {@link #NONE}.
 */
public interface OrderLineQuery {

    /** 한 번에 받을 수 있는 행 수 — 넘으면 IllegalArgumentException */
    int MAX_IDS = 500;

    /**
     * 수주 행 — 수량·출하 수량은 품목 기본 단위. status = OPEN·DONE·CANCELLED, doneByShipYn = Y 면 출하가 다 나가 자동 마감.
     * 남은 수량 = qty − shippedQty(다른 모듈이 따로 잡아 둔 수량은 그 모듈 몫)
     */
    record OrderLineView(long lineId, long orderId, String orderNo, int lineNo, long custId, String custPoNo, long itemId,
            String itemCd, String itemNm, String unitCd, LocalDate dueDate, BigDecimal qty, BigDecimal shippedQty, String status,
            String doneByShipYn) {
    }

    /** 조회 없음 — 영업 모듈이 없는 설치본(빈 없음) */
    OrderLineQuery NONE = new OrderLineQuery() {
        @Override
        public List<OrderLineView> openLines(long custId) {
            return List.of();
        }

        @Override
        public List<OrderLineView> lines(Collection<Long> lineIds) {
            return List.of();
        }
    };

    /** 그 거래처의 열린(OPEN) 행 — 순서 = 납기 → 수주번호 → 행번호. 없는·다른 회사 거래처는 빈 목록 */
    List<OrderLineView> openLines(long custId);

    /** 여러 행(상태 무관) — 순서 = line_id, 중복·null id 는 한 번·무시, 없는·다른 회사 id 는 빠진다. {@link #MAX_IDS} 초과는 IllegalArgumentException */
    List<OrderLineView> lines(Collection<Long> lineIds);

    /**
     * 품목들의 열린(OPEN) 행 중 납기 ≤ dueTo — APS MPS 수요(docs/38 16.3). 거래처 무관, 순서 = 품목 → 납기 → line_id.
     * 중복·null 품목 id 는 한 번·무시, 다른 회사 품목은 빠진다. 품목 {@link #MAX_IDS} 초과는 IllegalArgumentException. 영업이 없으면 빈 목록
     */
    default List<OrderLineView> openLinesByItems(Collection<Long> itemIds, LocalDate dueTo) {
        return List.of();
    }

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static OrderLineQuery of(ObjectProvider<OrderLineQuery> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
