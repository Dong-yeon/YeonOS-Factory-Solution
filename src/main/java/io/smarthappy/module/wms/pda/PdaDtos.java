package io.smarthappy.module.wms.pda;

import java.math.BigDecimal;
import java.util.List;

import io.smarthappy.module.wms.inv.InvDtos.ScanHit;

/** 창고 PDA(B7-1, docs/30 12.12) DTO — 재고·실사 서비스의 값을 그대로 쓰고 PDA 에만 필요한 모양만 둔다 */
public final class PdaDtos {

    private PdaDtos() {
    }

    /** 위치에 있는 재고 한 줄(0 보다 큰 것만) — 이동의 "무엇을" 고르기 */
    public record StockLine(long itemId, String itemCd, String itemNm, String unitCd, Long lotId, String lotNo, String stockStatus,
            BigDecimal qty) {
    }

    /** 스캔 한 번 — 위치면 그 위치 재고(stock), 품목·LOT 이면 hit 만 */
    public record PdaScan(ScanHit hit, List<StockLine> stock) {
    }

    /**
     * 이동 — 단말이 만든 clientReqId·발생 시각(공장 현지 yyyy-MM-ddTHH:mm, 누른 순간)으로 오프라인 재전송해도 같은 결과.
     * 상태는 바꾸지 않는다(출발 = 도착 상태). 수량은 글자(자릿수는 품목 단위 규칙)
     */
    public record MoveForm(String clientReqId, String occurredAt, Long itemId, String lotNo, String stockStatus, Long fromNodeId,
            Long toNodeId, String qty, String entryMethod) {
    }

    /** 이동 결과 — replay = 같은 요청을 다시 받아 처음 결과를 돌려줌 */
    /** warnings = 적치 검사 경고(W-12, 정책 WARN — 저장은 됨) */
    public record MoveResult(List<Long> trxIds, boolean replay, List<String> warnings) {
    }

    /**
     * PDA 입고(docs/30 12.12.15, 온라인만) — 공장 = 단말 공장, 상태 = 위치 기본 상태(+ 수입검사 QC), 발생 시각 = 서버가 받은 때.
     * 발주 행이 있으면 구매 입고(품목·공급사는 발주에서), 없으면 자재 입고(공급사 없음). 수량은 글자
     */
    public record RcvForm(String clientReqId, Long itemId, String qty, Long nodeId, Long poLineId, Long asnLineId, String supplierLotNo,
            String entryMethod) {
    }
}
