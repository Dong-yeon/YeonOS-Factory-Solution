package io.smarthappy.module.srm.link;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 반품 출고 게이트 계약 (SRM 2부 R4-4, docs/31 4.12.7·docs/30 12.14) — WMS 반품 출고(RTN_SUP)가 원장 쓰기 **직전**에 부른다.
 * 모양은 {@link PoReceiptGate} 와 같다: SRM 표는 SRM 이 이 안에서 고치고, 원본은 WMS 원장.
 *
 * - 부르는 쪽 트랜잭션 안(MANDATORY). 잠금 = 반품 지시(FOR UPDATE) → (교체 요구면) 발주 머리 → 발주 행 — 입고 게이트와 같은 방향
 * - 수량은 **품목 기본 단위**(원장 단위). 반품 지시 수량(지시 단위)을 기본 단위로 바꾼 값과 같아야 한다(R4-9 나눠 출고 없음)
 * - LOT: 부르는 쪽은 **재고에서 실제로 뽑은 LOT** 를 넘긴다 — LOT 관리 품목 = 그 LOT id(지시 LOT 와 같아야), 비관리 품목 = null(SRM 리뷰 2026-10-23).
 *   교체 요구 되돌림은 인자가 아니라 지시의 lot_id 로 입고 이력(srm_po_receipt.lot_id — 비관리 품목도 입고 LOT 가 남는다)을 찾는다
 * - 없는·다른 회사 반품은 404 NOT_FOUND(RLS). 인자 빈 값은 IllegalArgumentException(부르는 쪽 버그)
 * - SRM 이 없는 설치본은 {@link #NONE} — IllegalStateException(조용히 통과하면 확정 안 된 반품이 출고된다). WMS 는 그때 화면을 감춘다
 */
public interface ReturnShipGate {

    /**
     * 반품 지시 보기(WMS 화면이 고를 때). status = CONFIRMED(출고할 것)·SHIPPED(이미 나감 — 화면에 그렇게 보이게)
     *
     * @param qty     지시 수량(지시 단위 unitCd)
     * @param baseQty 기본 단위 수량(baseUnitCd) — 환산이 없으면 null(출고하면 409 SRM_RECV_NO_CONV)
     */
    record ReturnRef(long returnId, String returnNo, String status, long custId, String plantCd, long lotId, String lotNo, long itemId,
            String unitCd, BigDecimal qty, String baseUnitCd, BigDecimal baseQty, boolean replace, Long poLineId) {
    }

    /**
     * 게이트를 지난 결과 — WMS 화면이 "교체품 대기 n" 을 보이게.
     *
     * @param openQty 교체 요구면 되돌린 뒤 그 발주 행 열린 수량(발주 단위), 아니면 null
     */
    record Shipped(long returnId, String returnNo, String status, Long poLineId, String poStatus, BigDecimal openQty) {
    }

    ReturnShipGate NONE = new ReturnShipGate() {
        @Override
        public Optional<ReturnRef> find(String returnNo) {
            throw new IllegalStateException("SRM 없음 — 반품 출고 게이트 없음");
        }

        @Override
        public Shipped ship(long returnId, Long lotId, BigDecimal baseQty, String trxRef, String by, OffsetDateTime occurredAt) {
            throw new IllegalStateException("SRM 없음 — 반품 출고 게이트 없음");
        }

        @Override
        public Shipped unship(long returnId, String trxRef, String by, OffsetDateTime occurredAt, String reversesRef) {
            throw new IllegalStateException("SRM 없음 — 반품 출고 게이트 없음");
        }

        @Override
        public boolean available() {
            return false;
        }
    };

    /** 확정·출고됨 반품만, 작성 중·취소·없음·다른 회사는 빈 값 */
    Optional<ReturnRef> find(String returnNo);

    /**
     * 출고 — 확정(CONFIRMED)만(아니면 409 SRM_STATE). LOT 관리 품목은 lotId 가 지시 LOT 와 다르면 409 SRM_RTN_LOT_MISMATCH, 비관리 품목은 lotId 가
     * null 이어야(값이면 IllegalArgumentException — 부르는 쪽 버그). baseQty 가 지시(기본 단위)와 다르면
     * 409 SRM_RTN_QTY_MISMATCH. trxRef(회사 안 유일, 100자 — WMS: RTN_ISS:{client_req_id}) 를 이미 쓴 반품이 있으면 409 DUPLICATE.
     * 성공 → SHIPPED + shipped_at = occurredAt(null = 지금). 교체 요구면 같은 트랜잭션에서 {@link PoReceiptGate#reverseAt} 로 지시 LOT 를 받은
     * 입고(입고 이력 lot_id, 그 발주 행)를 지시 수량만큼 되돌린다 — 그 입고가 없으면 409 SRM_RTN_NO_RECEIPT, 그 LOT 순입고(이미 되돌린 것을 뺀)보다
     * 많으면 409 SRM_RECV_REVERSE, 그 밖 되돌림 한도·상태 오류는 입고 게이트 것 그대로
     */
    Shipped ship(long returnId, Long lotId, BigDecimal baseQty, String trxRef, String by, OffsetDateTime occurredAt);

    /**
     * 출고 되돌림(WMS 반품 출고 역분개와 같은 트랜잭션, R4-10) — 출고됨(SHIPPED)만(아니면 409 SRM_STATE), reversesRef 가 출고 때의 trxRef 가
     * 아니면 409 SRM_STATE. → 확정(CONFIRMED), shipped_at 비움. 교체 요구였으면 되돌린 입고를 다시 입고(같은 LOT 을 이력에) — 그새 다른 입고로
     * 열린 수량이 찼으면 409 SRM_RECV_OVER(사람이 정리), 발주 마감·행 닫힘이면 409 SRM_RTN_PO_CLOSED(발주를 다시 열거나 정리)
     */
    Shipped unship(long returnId, String trxRef, String by, OffsetDateTime occurredAt, String reversesRef);

    /** SRM 이 있는 설치본인가(화면 켜기) */
    default boolean available() {
        return true;
    }

    static ReturnShipGate of(ObjectProvider<ReturnShipGate> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
