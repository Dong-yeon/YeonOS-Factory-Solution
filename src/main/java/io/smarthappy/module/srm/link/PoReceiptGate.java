package io.smarthappy.module.srm.link;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 구매 입고 게이트 계약 (docs/31 4.9, WMS docs/30 12.2 D-B3) — WMS 구매 입고(RCV_PO)가 발주 행에 입고를 걸 때 부른다.
 * WMS 구매 입고 순서: {@code receiveAt} → (LOT 생성) → {@code attachLot} → 원장(R4-8).
 * SRM 표를 직접 고치지 않는다. 원본은 WMS 원장(그 발주 행 RCV_PO 합), SRM 은 발주 행 입고 수량 투영과 머리 상태를 이 안에서 바꾼다.
 *
 * - 부르는 쪽 트랜잭션 안(MANDATORY), **원장 INSERT 보다 먼저**. 잠금 = 발주 머리 → 발주 행(FOR UPDATE) — 부르는 쪽은 그 뒤에 LOT·기간·재고
 * - qty &gt; 0 = 입고: 발주가 발행·회신·입고 중·다 받음이고 행이 열려 있어야(아니면 409 SRM_STATE), ERP 원천 발주는 409 SRM_ERP_OWNED,
 *   입고 합 ≤ (수량 − 취소) × (1 + SRM_RECV_OVER_PCT/100) 넘으면 409 SRM_RECV_OVER
 * - qty &lt; 0 = 되돌림(원장 역분개와 같은 트랜잭션): 받은 것보다 많이 되돌리면 409 SRM_RECV_REVERSE. 마감된 발주도 되돌릴 수 있다(상태는 그대로)
 * - 머리 상태: 열린 행 전부 다 받음 → RECEIVED, 일부 → PARTIAL_RECEIVED, 되돌려 0 → 유효 회신(수락·부분)이 있으면 ACKED 아니면 ISSUED.
 *   자동 마감은 없다(허용 초과율 안의 추가 입고·잔량 마감은 사람이)
 * - 수량 자릿수는 발주 단위 자릿수(넘으면 400 INVALID_VALUE). 기본 단위 환산 = 품목 지금 기본 단위(같으면 1, 아니면 품목 단위 환산표),
 *   환산이 없으면 409 SRM_RECV_NO_CONV, 환산 결과가 기본 단위 자릿수를 넘으면(반올림하지 않음) 409 SRM_RECV_BASE_SCALE
 *   — 원장은 기본 단위만(D-B1)이므로 버리거나 반올림하지 않고 원장보다 먼저 거부한다
 * - 호출마다 입고 이력 1행(2부 R2, docs/31 4.10): 발생일 occurredOn(비우면 그 공장 오늘, 오늘보다 뒤·발주일보다 앞이면 400 INVALID_VALUE),
 *   되돌림은 되돌리는 입고의 발생일로 — 그날 순입고보다 많으면 409 SRM_RECV_REVERSE. trxRef 는 회사 안 유일(같은 근거 두 번 = 409 DUPLICATE)
 * - 없는·다른 회사 발주 행은 404 NOT_FOUND(RLS). qty 0·null, trxRef·by 빈 값은 IllegalArgumentException(부르는 쪽 버그)
 * - SRM 이 없는 설치본은 {@link #NONE} — IllegalStateException(조용히 통과하면 열린 수량 확인 없이 원장에 발주 행이 붙는다).
 *   WMS 는 그때 발주 행 칸을 숨기고 RCV_MAT 로 받는다
 */
public interface PoReceiptGate {

    /**
     * 게이트를 지난 결과.
     * @param baseQty    기본 단위 수량(부호는 qty 와 같다) — 원장에 이 값을 쓴다
     * @param recvQty    이번 호출 뒤 그 행 입고 합(발주 단위)
     * @param openQty    이번 호출 뒤 열린 수량(수량 − 취소 − 입고, 초과 입고면 음수)
     * @param poStatus   이번 호출 뒤 발주 머리 상태
     * @param occurredOn 이력에 남긴 발생일
     * @param asnLineId  이력에 남긴 ASN 행(없으면 null) — 되돌림은 원래 입고의 값(2부 R3, docs/31 4.11)
     */
    record Receipt(long poId, String poNo, long poLineId, int lineNo, long custId, String plantCd, Long whId, long itemId,
            BigDecimal qty, String poUnitCd, BigDecimal baseQty, String baseUnitCd, BigDecimal recvQty, BigDecimal openQty,
            String poStatus, LocalDate occurredOn, Long asnLineId) {
    }

    /** SRM 모듈이 없는 설치본(빈 없음) — 거부 */
    PoReceiptGate NONE = new PoReceiptGate() {
        @Override
        public Receipt receive(long poLineId, BigDecimal qty, String trxRef, String by, LocalDate occurredOn) {
            throw new IllegalStateException("SRM 없음 — 발주 입고 게이트 없음");
        }

        @Override
        public Receipt receiveAt(long poLineId, BigDecimal qty, String trxRef, String by, OffsetDateTime occurredAt) {
            throw new IllegalStateException("SRM 없음 — 발주 입고 게이트 없음");
        }

        @Override
        public Receipt receiveAt(long poLineId, BigDecimal qty, String trxRef, String by, OffsetDateTime occurredAt, Long asnLineId) {
            throw new IllegalStateException("SRM 없음 — 발주 입고 게이트 없음");
        }

        @Override
        public Receipt reverseAt(long poLineId, BigDecimal qty, String trxRef, String by, OffsetDateTime occurredAt, String reversesRef) {
            throw new IllegalStateException("SRM 없음 — 발주 입고 게이트 없음");
        }

        @Override
        public void attachLot(String trxRef, long lotId) {
            throw new IllegalStateException("SRM 없음 — 발주 입고 게이트 없음");
        }
    };

    /**
     * @param poLineId 발주 행
     * @param qty      발주 단위 수량 — 양수 입고, 음수 되돌림
     * @param trxRef   부르는 쪽 근거(원장 출처 키 등, 100자) — 로그·감사 추적용
     * @param by       처리자 로그인 아이디
     * @param occurredOn 입고 발생일(공장 날짜, null = 오늘). 되돌림은 되돌리는 입고의 발생일
     */
    Receipt receive(long poLineId, BigDecimal qty, String trxRef, String by, LocalDate occurredOn);

    /**
     * 원장 발생 시각으로 — SRM 이 그 발주 공장의 시간대로 날짜를 정한다(부르는 쪽이 시간대를 몰라도 되게).
     * WMS 구매 입고 = 원장 occurred_at, 되돌림 = **원래 RCV_PO 행의 occurred_at**. null = 그 공장 오늘
     */
    Receipt receiveAt(long poLineId, BigDecimal qty, String trxRef, String by, OffsetDateTime occurredAt);

    /**
     * ASN 행을 고른 입고(2부 R3, docs/31 4.11) — qty &gt; 0 만(되돌림은 {@link #reverseAt}). asnLineId 가 null 이면 {@link #receiveAt(long, BigDecimal, String, String, OffsetDateTime)}
     * 와 같다. ASN 행이 그 발주 행 것이 아니면 409 SRM_ASN_LINE_MISMATCH, ASN 이 확정·입고 중·다 받음이 아니면 409 SRM_STATE,
     * 다른 회사 ASN 행은 404. ASN 남은 양을 넘는 입고는 막지 않는다(발주 허용 초과율로만 — R3-4), ASN 상태는 같은 트랜잭션에서.
     * 잠금 = 발주 머리 → 발주 행 → ASN 머리
     */
    Receipt receiveAt(long poLineId, BigDecimal qty, String trxRef, String by, OffsetDateTime occurredAt, Long asnLineId);

    /**
     * 입고 되돌림 — **qty 는 음수**(양수면 IllegalArgumentException). reversesRef = 되돌리는 입고의 근거(WMS: RCV_PO:{client_req_id}). 그 이력 행이 있으면
     * 발생일·ASN 행을 그 행에서 가져와 ASN 받은 양·상태도 함께 되돌린다(occurredAt 은 무시). 다른 발주 행의 근거면 409 SRM_ASN_LINE_MISMATCH,
     * 이력이 없으면(R2 전 입고) occurredAt 으로 ASN 없이
     */
    Receipt reverseAt(long poLineId, BigDecimal qty, String trxRef, String by, OffsetDateTime occurredAt, String reversesRef);

    /**
     * 받은 LOT 를 입고 이력에 남긴다(2부 R4-8, docs/31 4.12.7) — 같은 트랜잭션에서 {@link #receiveAt} 뒤, LOT 를 만든 다음에.
     * trxRef 의 입고(qty &gt; 0) 이력 1행의 lot_id 가 비어 있을 때만 채운다. 그런 행이 없으면(근거 없음·되돌림 행·이미 채움)
     * 409 SRM_STATE — 조용히 넘어가면 반품 출고가 되돌릴 입고를 못 찾는다. 다른 회사 LOT 는 DB 가 거부(fk_srm_po_receipt_lot_comp).
     * 되돌림 행은 {@link #reverseAt} 의 reversesRef 이력에서 lot_id 를 그대로 받는다
     */
    void attachLot(String trxRef, long lotId);

    /** 발생일 = 그 공장 오늘 */
    default Receipt receive(long poLineId, BigDecimal qty, String trxRef, String by) {
        return receive(poLineId, qty, trxRef, by, null);
    }

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static PoReceiptGate of(ObjectProvider<PoReceiptGate> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
