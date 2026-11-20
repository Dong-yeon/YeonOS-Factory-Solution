package io.smarthappy.module.wms.link;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 출하 조회 계약 (docs/30 12.5.3·13장, docs/57) — 영업이 수주 행 출하 수량 투영(shipped_qty)을 원본(WMS SHIP 원장)과 맞춰 볼 때,
 * 리콜·클레임 추적(QMS)이 LOT 이 어느 고객에게 나갔는지·어디 남았는지 볼 때. 잠금 없음.
 * 호출자의 회사 범위에서 읽고, 다른 회사 행은 0·빠짐(RLS). WMS 가 없는 설치본은 {@link #NONE}.
 */
public interface ShipQuery {

    /** {@link #byLots}·{@link #lotStock} 한 번에 받을 수 있는 LOT 수 — MES LotLookup.MAX_IDS 와 같다 */
    int LOTS_MAX = 500;

    /**
     * LOT 이 나간 출하 한 줄(LOT × 출하 지시). qty = 그 LOT·그 지시의 SHIP 원장 합의 부호 반대(품목 기본 단위, 역분개 포함 — 출하 취소분은 빠지고
     * 0 인 줄은 없다). shippedAt = 지시의 출하 확정 시각(다시 확정했으면 마지막), palletNos·ssccs = 그 LOT 피킹이 담긴 팔레트(없으면 빈 목록)
     */
    record LotShipment(long lotId, long shipId, String shipNo, String plantCd, long custId, String custCd, String custNm,
            OffsetDateTime shippedAt, long itemId, BigDecimal qty, String unitCd, List<String> palletNos, List<String> ssccs) {
    }

    /**
     * LOT 이 아직 창고에 있는 곳 — LOT × 공장 × 칸 × 재고 상태, 수량 &gt; 0 만. 가용 정의(AvailSql)를 쓰지 않는다: 리콜은 "어디 있나" 라
     * QC·HOLD·BLOCKED 상태와 출하 대기·검사·폐기 칸도 그대로(locUsage = 칸 용도, 없으면 null)
     */
    record LotStockRow(long lotId, String plantCd, long nodeId, String path, String locUsage, String stockStatus, BigDecimal qty) {
    }

    /**
     * 고객 반품 한 건(docs/59, QMS 8D 표시 — docs/29_P3 44장). status = RECEIVED·CANCELLED(취소도 돌려준다 — 화면이 상태로 표시),
     * lotNo = LOT 비관리 품목이면 null, ncrId·ncrNo = 반품이 만든 QMS NCR 스냅숏(없으면 null), capaNo = 사람이 적은 클레임 번호(R-8)
     */
    record CustReturn(long rtnId, String rtnNo, String status, String custCd, String custNm, String itemCd, String itemNm, String lotNo,
            BigDecimal qty, String unitCd, String reasonCd, Long ncrId, String ncrNo, String capaNo, OffsetDateTime receivedAt) {
    }

    /** 조회 없음 — 요청한 행 모두 0 */
    ShipQuery NONE = StockQuery::zeros;

    /**
     * 수주 행별 출하 수량(품목 기본 단위) = 그 행 출하 줄의 SHIP 원장 합의 부호 반대(역분개 포함 — 취소한 출하는 빠진다).
     * 요청한 행 id 가 모두 키로 있다(출하 없으면 0). null·빈 목록이면 빈 맵
     */
    Map<Long, BigDecimal> shippedByLine(Collection<Long> orderLineIds);

    /**
     * LOT 들이 나간 출하(docs/57 2.2) — 순서 = lot_id, 확정 시각, ship_id. 중복·null id 는 한 번·무시, 없는·다른 회사 LOT 은 빠진다(예외 없음).
     * {@link #LOTS_MAX} 초과는 IllegalArgumentException(호출 코드 오류). WMS 가 없으면({@link #NONE}) 빈 목록
     */
    default List<LotShipment> byLots(Collection<Long> lotIds) {
        return List.of();
    }

    /** LOT 들이 아직 있는 곳(docs/57 2.2) — 순서 = lot_id, 공장, 칸 경로. 입력 규칙은 {@link #byLots} 와 같다 */
    default List<LotStockRow> lotStock(Collection<Long> lotIds) {
        return List.of();
    }

    /**
     * 클레임(8D)에 묶인 고객 반품 — capa_no 가 {@code capaNo}(앞뒤 공백 뺀 뒤 정확히 같음, 대소문자 그대로, 부분 일치 없음)이거나
     * ncr_id 가 {@code ncrIds} 중 하나. 순서 = 받은 시각 desc, rtn_id desc, 최대 {@link #RETURNS_MAX} 건. capaNo 비움·ncrIds 비움이면 빈 목록,
     * ncrIds 중복·null 은 무시, {@link #LOTS_MAX} 초과는 IllegalArgumentException. 다른 회사 반품은 RLS 로 빠진다. WMS 가 없으면 빈 목록
     */
    default List<CustReturn> custReturns(String capaNo, Collection<Long> ncrIds) {
        return List.of();
    }

    /** {@link #custReturns} 한 번에 돌려주는 최대 건수 */
    int RETURNS_MAX = 200;

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static ShipQuery of(ObjectProvider<ShipQuery> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
