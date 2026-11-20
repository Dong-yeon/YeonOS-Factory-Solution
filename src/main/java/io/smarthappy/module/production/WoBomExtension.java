package io.smarthappy.module.production;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 지시 발행 때 자재 소요 스냅숏(prd_wo_bom)에 **추가 BOM** 을 붙이는 확장 지점(docs/72 2.4) — 주문·프로젝트별 추가 자재 등.
 * 발행 트랜잭션 안에서 본체 BOM 을 펼친 뒤 부른다. 돌려준 BOM 마다 본체와 같은 규칙(가상 조립 펼침·단위 환산·스크랩·올림)으로 펼쳐
 * 줄 번호를 이어 붙이고, 각 줄의 출처(src_bom_id·src_line_id)는 그 BOM 그대로 남는다. 발행을 막아야 하면 BusinessException 을 던진다(전부 롤백).
 * 구현이 없으면 아무것도 하지 않는다(기존 동작 그대로).
 */
public interface WoBomExtension {

    /**
     * @param woId 발행하는 지시
     * @param planStart 지시 계획 시작일(BOM 유효일 기준)
     * @param planQty 지시 수량
     * @return 붙일 BOM(확정·유효한 BOM id, 펼칠 부모 수량) — 없으면 빈 목록
     */
    List<ExtraBom> extraBoms(long woId, LocalDate planStart, BigDecimal planQty);

    /** 붙일 BOM 하나 — qty = 그 BOM 의 부모 품목 수량(지시 수량에 맞춰 구현이 계산) */
    record ExtraBom(long bomId, BigDecimal qty) {
    }
}
