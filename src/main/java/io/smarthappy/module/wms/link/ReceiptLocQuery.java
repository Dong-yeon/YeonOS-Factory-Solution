package io.smarthappy.module.wms.link;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 입고 가능 위치 계약 (docs/44 S2, docs/30 11.8 Q5) — MES 생산 입고 화면의 위치 목록을 WMS 저장 규칙과 같게 거르기.
 * 저장(`ProductionReceiptPosting.post`)과 <b>같은 코드</b>로 판정한다: 위치 = 원장 트리거 규칙(이 공장 WH·LOC, 사용 중, 재고 둠),
 * 상태 = 위치 기본 상태 → 입고 QC 고정·NG LOT 이어받기(`QcReceiptRule`). 호출자 트랜잭션 안에서만(MANDATORY), 회사 범위 RLS.
 * WMS 가 없는 설치본은 {@link #NONE} — MES 는 자기 목록을 그대로 쓴다({@code q == NONE} 으로 가린다)
 */
public interface ReceiptLocQuery {

    /** WMS 없음 — 빈 목록(호출자는 NONE 인지 보고 자기 목록을 쓴다) */
    ReceiptLocQuery NONE = (plantCd, itemId, lotId) -> List.of();

    /**
     * 생산 입고(RCV_PRD) 위치 후보 — 이 공장의 창고·로케이션 전부, 받을 수 없는 칸은 사유 코드와 함께(경로 순).
     * lotId 는 LOT 관리 품목의 입고 LOT(없으면 null — 판정 이어받기를 보지 않음). LOT 보류는 MES 가 먼저 막으므로 보지 않는다
     */
    List<ReceiptLoc> productionReceipt(String plantCd, long itemId, Long lotId);

    /**
     * @param status   받으면 들어갈 재고 상태(저장과 같은 판정 — 받을 수 없는 칸은 null)
     * @param reasonCd null = 받을 수 있음. NODE_INACTIVE(사용 중지 위치) · STOCK_NOT_ALLOWED(재고 안 둠 위치) · STAGING_NOT_ALLOWED(출하 대기 칸)
     */
    record ReceiptLoc(long nodeId, String nodeCd, String path, String status, String reasonCd) {

        public boolean ok() {
            return reasonCd == null;
        }
    }

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static ReceiptLocQuery of(ObjectProvider<ReceiptLocQuery> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
