package io.smarthappy.module.wms.link;

import org.springframework.beans.factory.ObjectProvider;

/**
 * LOT 계보 → 재고 원장 계약 (docs/30 12.4, P4 2부 B5·B2+) — MES 가 LOT 계보 행을 만들거나 되돌린 직후, **같은 트랜잭션** 안에서 부른다.
 * MES 는 계보 id 만 넘기고, WMS 가 그 계보 행(`lot_genealogy`)을 직접 읽어 유형별로 반영한다:
 * <ul>
 * <li>{@code CONSUME} → 투입 차감 {@code ISSUE_WO}(위치는 WMS 가 정함 — LINESIDE {@code feed_node_id} → 창고 pick_seq, D-B6·D-B12·D-B13)</li>
 * <li>{@code SPLIT·MERGE·RELABEL·REWORK·REPAIR} → LOT 변환 {@code CONV_OUT/CONV_IN} 짝(같은 칸·같은 상태, D-B11 상태 순서)</li>
 * </ul>
 * 규칙:
 * <ul>
 * <li>공장 정책 {@code WMS_STOCK_LINK} = N(기본)이면 그 공장 몫은 아무것도 하지 않는다. 켜는 조건은 MES POP 대기열의 409 보존(docs/30 12.4.2)</li>
 * <li>재고가 모자라면 409 {@code INSUFFICIENT_STOCK}, 마감 달이면 409 {@code PERIOD_CLOSED} — 칸 오류가 없는 {@code BusinessException} 이므로
 *     MES 는 칸 오류로 바꾸지 말고 그대로 던진다(12.4.5 ③). 업무 전체가 되돌아간다</li>
 * <li>잠금: MES 가 잡은 prd_wo_proc·lot_master·ECO 뒤에 기간 advisory(공유) → inv_stock. WMS 는 그 뒤 LOT·ECO 를 잡지 않는다(11.6)</li>
 * <li>WMS 가 없는 설치본은 {@link #NONE}</li>
 * </ul>
 */
public interface LotStockLink {

    /** 반영 없음 — WMS 모듈이 없는 설치본(빈 없음) */
    LotStockLink NONE = new LotStockLink() {
        @Override
        public void onEdge(long genId) {
            // WMS 없음
        }

        @Override
        public void onReverse(long reversalGenId) {
            // WMS 없음
        }
    };

    /** 계보 행 하나를 만든 직후(LotService.edge) */
    void onEdge(long genId);

    /**
     * 계보 역분개 행을 만든 직후(LotService.reverse) — 공정·공장은 원래 계보 행(reversal_of_id)에서, 발생 시각은 이 역분개 행에서 읽는다
     */
    void onReverse(long reversalGenId);

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static LotStockLink of(ObjectProvider<LotStockLink> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
