package io.smarthappy.module.srm.link;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.srm.link.PoReceiptGate.Receipt;
import io.smarthappy.module.srm.link.ReturnShipMapper.Ret;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** {@link ReturnShipGate} 의 SRM 구현 (docs/31 4.12.7) — 반품 잠금 → 확인 → (교체 요구면) 입고 게이트로 되돌림/다시 입고 → 상태 */
@Slf4j
@Component
@RequiredArgsConstructor
class SrmReturnShipGate implements ReturnShipGate {

    private final ReturnShipMapper mapper;
    /** 프록시 — 입고 게이트의 MANDATORY·잠금 순서(발주 머리 → 행)를 그대로 */
    private final PoReceiptGate receipts;
    private final BizClock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ReturnRef> find(String returnNo) {
        if (returnNo == null || returnNo.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.findByNo(returnNo.strip())).map(SrmReturnShipGate::ref);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Shipped ship(long returnId, Long lotId, BigDecimal baseQty, String trxRef, String by, OffsetDateTime occurredAt) {
        args(trxRef, by);
        if (baseQty == null || baseQty.signum() <= 0) {
            throw new IllegalArgumentException("baseQty must be positive");
        }
        Ret r = lock(returnId);
        if (!"CONFIRMED".equals(r.getStatus())) {
            throw new BusinessException(HbErrorCode.SRM_STATE);
        }
        if (mapper.refUsed(trxRef)) {
            throw new BusinessException(HbErrorCode.DUPLICATE);
        }
        // 재고에서 뽑은 LOT — 관리 품목 = 지시 LOT, 비관리 품목 = 없음(원장에 LOT 칸이 없다)
        if ("Y".equals(r.getLotCtrlYn())) {
            if (lotId == null || r.getLotId() != lotId) {
                throw new BusinessException(HbErrorCode.SRM_RTN_LOT_MISMATCH, r.getLotNo());
            }
        } else if (lotId != null) {
            throw new IllegalArgumentException("item is not lot-controlled — lotId must be null");
        }
        BigDecimal want = base(r);
        if (want.compareTo(baseQty) != 0) {
            throw new BusinessException(HbErrorCode.SRM_RTN_QTY_MISMATCH, plain(want), r.getBaseUnit());
        }
        Receipt rc = null;
        if (replace(r)) {
            // 교체 요구: 그 LOT 를 받은 입고를 지시 수량만큼 되돌린다 — 발주 단위로 환산(R4c-단위, 지금의 환산표)
            BigDecimal poQty = poQty(r);
            String recvRef = mapper.receiptOfLot(r.getPoLineId(), r.getLotId());
            if (recvRef == null) {
                throw new BusinessException(HbErrorCode.SRM_RTN_NO_RECEIPT, r.getLotNo());
            }
            // 그 LOT 의 순입고(이미 되돌린 것 — 입고 역분개·앞선 반품 — 을 뺀)까지만
            BigDecimal net = mapper.netOfLot(r.getPoLineId(), r.getLotId());
            if (net.compareTo(poQty) < 0) {
                throw new BusinessException(HbErrorCode.SRM_RECV_REVERSE, plain(net.max(BigDecimal.ZERO)));
            }
            rc = receipts.reverseAt(r.getPoLineId(), poQty.negate(), trxRef, by, occurredAt, recvRef);
        }
        OffsetDateTime at = occurredAt == null ? OffsetDateTime.ofInstant(clock.instant(), clock.zone(r.getPlantCd())) : occurredAt;
        if (mapper.markShipped(returnId, at, trxRef, by) != 1) {
            throw new IllegalStateException("locked return " + returnId + " changed status");
        }
        log.debug("return {} shipped ref {}", r.getReturnNo(), trxRef);
        return shipped(r, "SHIPPED", rc);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Shipped unship(long returnId, String trxRef, String by, OffsetDateTime occurredAt, String reversesRef) {
        args(trxRef, by);
        Ret r = lock(returnId);
        if (!"SHIPPED".equals(r.getStatus()) || reversesRef == null || !reversesRef.equals(r.getShipRef())) {
            throw new BusinessException(HbErrorCode.SRM_STATE);
        }
        Receipt rc = null;
        if (replace(r)) {
            // 되돌린 입고(그 양 그대로 — 환산표가 그 사이 바뀌어도)를 다시 입고 — 같은 LOT 를 이력에(그 LOT 를 다시 반품할 때 찾을 수 있게)
            BigDecimal back = mapper.reversedQty(reversesRef);
            if (back == null) {
                throw new IllegalStateException("shipped replacement return " + r.getReturnNo() + " has no receipt reversal " + reversesRef);
            }
            try {
                rc = receipts.receiveAt(r.getPoLineId(), back, trxRef, by, occurredAt);
            } catch (BusinessException e) {
                if (e.code() == HbErrorCode.SRM_STATE) {
                    // 발주 마감·행 닫힘 — 입고를 다시 걸 수 없다. 화면이 "발주가 닫혀" 라고 말하게(SRM 리뷰 2026-10-23)
                    throw new BusinessException(HbErrorCode.SRM_RTN_PO_CLOSED, mapper.poNoOf(r.getPoLineId()));
                }
                throw e;
            }
            receipts.attachLot(trxRef, r.getLotId());
        }
        if (mapper.markUnshipped(returnId, reversesRef, by) != 1) {
            throw new IllegalStateException("locked return " + returnId + " changed status");
        }
        log.debug("return {} unshipped ref {}", r.getReturnNo(), trxRef);
        return shipped(r, "CONFIRMED", rc);
    }

    private Ret lock(long returnId) {
        Ret r = mapper.lockReturn(returnId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return r;
    }

    /** 지시 수량의 기본 단위 값 — 환산 없음·자릿수 넘침은 입고 게이트와 같은 오류 */
    private static BigDecimal base(Ret r) {
        if (r.getFactor() == null) {
            throw new BusinessException(HbErrorCode.SRM_RECV_NO_CONV, r.getUnitCd(), r.getBaseUnit());
        }
        BigDecimal b = strip(r.getQty().multiply(r.getFactor()));
        int scale = r.getBaseScale() == null ? SrmPoReceiptGate.QTY_SCALE : Math.min(r.getBaseScale(), SrmPoReceiptGate.QTY_SCALE);
        if (b.scale() > scale) {
            throw new BusinessException(HbErrorCode.SRM_RECV_BASE_SCALE, plain(r.getQty()), r.getUnitCd(), b.toPlainString(), r.getBaseUnit(),
                    scale);
        }
        return b;
    }

    /** 지시 수량의 발주 단위 값 — 환산 없음은 입고 게이트와 같은 오류, 나누어떨어지지 않음·자릿수 넘침은 SRM_RTN_PO_SCALE */
    private static BigDecimal poQty(Ret r) {
        if (r.getUnitCd().equals(r.getPoUnit())) {
            return r.getQty();
        }
        ReturnUnits.PoQty q = ReturnUnits.poQty(r.getQty(), r.getFactor(), r.getPoFactor());
        if (q == null) {
            throw new BusinessException(HbErrorCode.SRM_RECV_NO_CONV, r.getFactor() == null ? r.getUnitCd() : r.getPoUnit(), r.getBaseUnit());
        }
        if (!q.fits(r.getPoScale())) {
            throw new BusinessException(HbErrorCode.SRM_RTN_PO_SCALE, plain(r.getQty()), r.getUnitCd(), q.show(r.getPoScale()), r.getPoUnit(),
                    ReturnUnits.scale(r.getPoScale()));
        }
        return q.value();
    }

    private static boolean replace(Ret r) {
        return "Y".equals(r.getReplaceYn()) && r.getPoLineId() != null;
    }

    private static void args(String trxRef, String by) {
        if (trxRef == null || trxRef.isBlank() || trxRef.length() > 100) {
            throw new IllegalArgumentException("trxRef is required (<= 100)");
        }
        if (by == null || by.isBlank()) {
            throw new IllegalArgumentException("by is required");
        }
    }

    private static ReturnRef ref(Ret r) {
        BigDecimal base = r.getFactor() == null ? null : strip(r.getQty().multiply(r.getFactor()));
        return new ReturnRef(r.getReturnId(), r.getReturnNo(), r.getStatus(), r.getCustId(), r.getPlantCd(), r.getLotId(), r.getLotNo(),
                r.getItemId(), r.getUnitCd(), strip(r.getQty()), r.getBaseUnit(), base, "Y".equals(r.getReplaceYn()), r.getPoLineId());
    }

    private static Shipped shipped(Ret r, String status, Receipt rc) {
        return new Shipped(r.getReturnId(), r.getReturnNo(), status, r.getPoLineId(), rc == null ? null : rc.poStatus(),
                rc == null ? null : rc.openQty());
    }

    private static BigDecimal strip(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    private static String plain(BigDecimal v) {
        return strip(v).toPlainString();
    }
}
