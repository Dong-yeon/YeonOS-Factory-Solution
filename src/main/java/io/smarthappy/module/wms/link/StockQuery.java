package io.smarthappy.module.wms.link;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 재고 조회 계약 (docs/30 13장) — APS 자재 가용 등 다른 모듈이 WMS 현재고를 읽을 때. inv_stock·lot_master 를 직접 조회하지 않는다.
 * 호출자의 회사 범위(TenantContext)에서 읽고, 다른 회사 품목은 0(RLS). WMS 가 없는 설치본은 {@link #NONE}.
 */
public interface StockQuery {

    /** 조회 없음 — WMS 모듈이 없는 설치본(빈 없음). 요청한 품목 모두 0 */
    StockQuery NONE = (plantCd, itemIds) -> zeros(itemIds);

    /**
     * 품목별 가용 수량(품목 기본 단위) — 공장 전체 위치 합. 재고 상태 AVAILABLE 만, 보류 LOT 은 빼고, 칸 용도 STAGING·QC(칸 자신)·SCRAP(조상 포함)도
     * 뺀다(가용 정의 하나, docs/53 2장). 요청한 품목 id 가 모두 키로 있다(재고 없으면 0). null·빈 목록이면 빈 맵.
     */
    Map<Long, BigDecimal> available(String plantCd, Collection<Long> itemIds);

    /**
     * 품목별 출하 예약(품목 기본 단위, docs/53 (가) 계산형 소프트 예약) — 그 공장 열린 출하 지시(DRAFT·PICKING) 줄의 아직 피킹 안 한 수량 합.
     * 막지 않는 숫자다: "가용 − 출하 예약" 을 쓸지는 부르는 쪽 판단(APS 등). 요청한 품목 id 가 모두 키(예약 없으면 0), null·빈 목록이면 빈 맵.
     * 호출자 회사 범위(RLS)·읽기만 — WMS 가 없으면({@link #NONE}) 모두 0
     */
    default Map<Long, BigDecimal> reservedByShip(String plantCd, Collection<Long> itemIds) {
        return zeros(itemIds);
    }

    /**
     * LOT 하나의 재고 상태별 수량(품목 기본 단위, docs/30 12.15.8 WB-3) — 공장 정책 {@code WMS_QUALITY_LINK} = Y 인 공장의 위치 합.
     * 키 = 재고 상태(QC·AVAILABLE·HOLD·BLOCKED, 0 인 상태는 없음). 읽기만·잠금 없음·예외 없음 — 없는 LOT·다른 회사·비관리 품목이면 빈 맵
     */
    default Map<String, BigDecimal> lotStatusQty(long lotId) {
        return Map.of();
    }

    /** {@link #qcLots} 한 번에 읽는 최대 LOT 수 */
    int QC_LOTS_MAX = 500;

    /**
     * QC(검사 대기) 재고가 남은 LOT — 공장 정책 {@code WMS_QUALITY_LINK} = Y 인 공장만(docs/30 12.15.6 "검사 만들기").
     * QMS 할 일 목록이 이 중 검사가 없는 LOT 을 거른다(WMS 는 QMS 표를 읽지 않는다). lot_id 오름차순, {@code afterLotId} 다음부터
     * {@code limit}(1~{@value #QC_LOTS_MAX}, 범위 밖은 끝값)건 — 더 있으면 {@link QcLots#truncated()}. 호출자 회사 범위(RLS)·읽기만·예외 없음 —
     * 공장이 비었거나 스위치가 N 이거나 WMS 가 없으면 빈 목록
     */
    default QcLots qcLots(String plantCd, Long afterLotId, int limit) {
        return QcLots.EMPTY;
    }

    /** QC 재고 LOT 한 건 — 수량은 그 공장 위치 합(품목 기본 단위) */
    record QcLot(long lotId, String lotNo, long itemId, BigDecimal qcQty) {
    }

    /** @param truncated 상한에서 잘렸다 — 마지막 lotId 를 afterLotId 로 다시 부르면 이어진다 */
    record QcLots(List<QcLot> lots, boolean truncated) {
        public static final QcLots EMPTY = new QcLots(List.of(), false);
    }

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static StockQuery of(ObjectProvider<StockQuery> provider) {
        return provider.getIfAvailable(() -> NONE);
    }

    /** 요청 품목 id(중복·null 제외, 순서 유지) → 0 */
    static Map<Long, BigDecimal> zeros(Collection<Long> itemIds) {
        Map<Long, BigDecimal> m = new LinkedHashMap<>();
        if (itemIds != null) {
            itemIds.stream().filter(Objects::nonNull).forEach(id -> m.put(id, BigDecimal.ZERO));
        }
        return m;
    }
}
