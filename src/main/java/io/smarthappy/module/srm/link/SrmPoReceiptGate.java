package io.smarthappy.module.srm.link;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.srm.link.PoReceiptMapper.AsnLock;
import io.smarthappy.module.srm.link.PoReceiptMapper.AsnState;
import io.smarthappy.module.srm.link.PoReceiptMapper.Head;
import io.smarthappy.module.srm.link.PoReceiptMapper.Line;
import io.smarthappy.module.srm.link.PoReceiptMapper.ReceiptRef;
import io.smarthappy.module.srm.link.PoReceiptMapper.Summary;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** {@link PoReceiptGate} 의 SRM 구현 (docs/31 4.9·4.10·4.11) — 머리 → 행(→ ASN 머리) 잠금, 열린 수량·허용 초과율 확인, 입고 이력 + 입고 수량 투영, 발주·ASN 상태 */
@Slf4j
@Component
@RequiredArgsConstructor
class SrmPoReceiptGate implements PoReceiptGate {

    /** 입고를 받을 수 있는 머리 상태 */
    static final Set<String> RECEIVABLE = Set.of("ISSUED", "ACKED", "PARTIAL_RECEIVED", "RECEIVED");
    /** 되돌릴 수 있는 머리 상태 — 받은 게 있는 상태 + 마감 */
    static final Set<String> REVERSIBLE = Set.of("PARTIAL_RECEIVED", "RECEIVED", "CLOSED");
    /** 입고를 걸 수 있는 ASN 상태(다 받은 ASN 에 더 받는 것은 막지 않는다 — R3-4) */
    static final Set<String> ASN_RECEIVABLE = Set.of("SENT", "PARTIAL_RECEIVED", "RECEIVED");
    /** 환산 수량 소수 자릿수 — 수량 칸(numeric(18,6))과 같게 */
    static final int QTY_SCALE = 6;

    private final PoReceiptMapper mapper;
    private final PolicyService policies;
    private final BizClock clock;

    /** 인터페이스 기본 메서드를 덮어 프록시의 MANDATORY 검사를 같이 받는다(기본 메서드 안의 자기 호출은 프록시를 거치지 않음) */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Receipt receive(long poLineId, BigDecimal qty, String trxRef, String by) {
        return receive(poLineId, qty, trxRef, by, (LocalDate) null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Receipt receiveAt(long poLineId, BigDecimal qty, String trxRef, String by, OffsetDateTime occurredAt) {
        return run(poLineId, qty, trxRef, by, dateOf(poLineId, occurredAt), null, null, null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Receipt receiveAt(long poLineId, BigDecimal qty, String trxRef, String by, OffsetDateTime occurredAt, Long asnLineId) {
        if (asnLineId != null && qty != null && qty.signum() < 0) {
            throw new IllegalArgumentException("asnLineId is for receipts — reverse with reverseAt");
        }
        return run(poLineId, qty, trxRef, by, dateOf(poLineId, occurredAt), asnLineId, null, null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Receipt reverseAt(long poLineId, BigDecimal qty, String trxRef, String by, OffsetDateTime occurredAt, String reversesRef) {
        if (qty != null && qty.signum() > 0) {
            throw new IllegalArgumentException("reverseAt takes a negative qty");
        }
        if (reversesRef == null || reversesRef.isBlank()) {
            return receiveAt(poLineId, qty, trxRef, by, occurredAt);
        }
        // 원래 입고 이력이 있으면 그 발생일·ASN 행으로(WMS 원장은 ASN 칸을 들지 않는다 — R3-3)
        ReceiptRef orig = mapper.findReceiptByRef(reversesRef);
        if (orig == null) {
            return run(poLineId, qty, trxRef, by, dateOf(poLineId, occurredAt), null, null, null);
        }
        if (orig.getPoLineId() != poLineId) {
            throw new BusinessException(HbErrorCode.SRM_ASN_LINE_MISMATCH);
        }
        return run(poLineId, qty, trxRef, by, orig.getOccurredOn(), orig.getAsnLineId(), reversesRef, orig.getLotId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Receipt receive(long poLineId, BigDecimal qty, String trxRef, String by, LocalDate occurredOn) {
        return run(poLineId, qty, trxRef, by, occurredOn, null, null, null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void attachLot(String trxRef, long lotId) {
        if (trxRef == null || trxRef.isBlank()) {
            throw new IllegalArgumentException("trxRef is required");
        }
        if (mapper.attachLot(trxRef, lotId) != 1) {
            throw new BusinessException(HbErrorCode.SRM_STATE);
        }
    }

    /** 원장 발생 시각 → 그 발주 공장 날짜(null = null → 공장 오늘) */
    private LocalDate dateOf(long poLineId, OffsetDateTime at) {
        if (at == null) {
            return null;
        }
        Long poId = mapper.findPoId(poLineId);
        String plant = poId == null ? null : mapper.plantOf(poId);
        if (plant == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return at.atZoneSameInstant(clock.zone(plant)).toLocalDate();
    }

    /** reversesRef 가 있으면 asnLineId·lotId 는 원래 입고에서 온 값(되돌림), 없으면 고른 ASN 행(입고)·LOT 없음(뒤에 attachLot) */
    private Receipt run(long poLineId, BigDecimal qty, String trxRef, String by, LocalDate occurredOn, Long asnLineId, String reversesRef,
            Long lotId) {
        if (qty == null || qty.signum() == 0) {
            throw new IllegalArgumentException("qty must not be zero");
        }
        if (trxRef == null || trxRef.isBlank() || trxRef.length() > 100) {
            throw new IllegalArgumentException("trxRef is required (<= 100)");
        }
        if (by == null || by.isBlank()) {
            throw new IllegalArgumentException("by is required");
        }
        Long poId = mapper.findPoId(poLineId);
        if (poId == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        Head head = mapper.lockHead(poId);
        Line line = mapper.lockLine(poLineId);
        if (head == null || line == null || line.getPoId() != poId) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), clock.zone(head.getPlantCd()));
        LocalDate on = occurredOn == null ? today : occurredOn;
        if (on.isAfter(today) || on.isBefore(head.getPoDate())) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (mapper.refExists(trxRef)) {
            throw new BusinessException(HbErrorCode.DUPLICATE);   // 같은 원장 행으로 두 번 — 유일 제약이 뒤를 받친다
        }
        int scale = line.getUnitScale() == null ? QTY_SCALE : Math.min(line.getUnitScale(), QTY_SCALE);
        if (qty.stripTrailingZeros().scale() > scale) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        BigDecimal recv = line.getRecvQty().add(qty);
        BigDecimal ordered = line.getQty().subtract(line.getCancelQty());
        if (qty.signum() > 0) {
            if ("ERP".equals(head.getSource())) {
                throw new BusinessException(HbErrorCode.SRM_ERP_OWNED);   // ERP 원천 발주의 입고는 ERP 가 주인(D1) — WMS 는 RCV_MAT 로
            }
            if (!RECEIVABLE.contains(head.getStatus()) || !"OPEN".equals(line.getLineStatus())) {
                throw new BusinessException(HbErrorCode.SRM_STATE);
            }
            int pct = Integer.parseInt(policies.value(Policies.SRM_RECV_OVER_PCT, head.getPlantCd()));
            BigDecimal limit = ordered.multiply(BigDecimal.valueOf(100L + pct)).divide(BigDecimal.valueOf(100));
            if (recv.compareTo(limit) > 0) {
                BigDecimal room = limit.subtract(line.getRecvQty()).max(BigDecimal.ZERO);
                throw new BusinessException(HbErrorCode.SRM_RECV_OVER, plain(room.setScale(scale, RoundingMode.DOWN)));
            }
        } else {
            if (!REVERSIBLE.contains(head.getStatus())) {
                throw new BusinessException(HbErrorCode.SRM_STATE);
            }
            if (recv.signum() < 0) {
                throw new BusinessException(HbErrorCode.SRM_RECV_REVERSE, plain(line.getRecvQty()));
            }
            BigDecimal dayNet = mapper.dayNet(poLineId, on);   // 되돌림은 그 입고의 발생일로 — 그날 받은 것보다 많이 되돌리지 못한다
            if (dayNet.add(qty).signum() < 0) {
                throw new BusinessException(HbErrorCode.SRM_RECV_REVERSE, plain(dayNet.max(BigDecimal.ZERO)));
            }
        }
        if (line.getFactor() == null) {
            throw new BusinessException(HbErrorCode.SRM_RECV_NO_CONV, line.getUnitCd(), line.getBaseUnit());
        }
        // 원장은 기본 단위 자릿수로 받는다 — 나누어떨어지지 않는 환산(예: 1 BOX = 2.5 EA)을 여기서 막아야 원인이 흐려지지 않는다
        BigDecimal baseQty = strip(qty.multiply(line.getFactor()));
        int baseScale = line.getBaseScale() == null ? QTY_SCALE : Math.min(line.getBaseScale(), QTY_SCALE);
        if (baseQty.scale() > baseScale) {
            throw new BusinessException(HbErrorCode.SRM_RECV_BASE_SCALE, plain(qty), line.getUnitCd(), baseQty.toPlainString(),
                    line.getBaseUnit(), baseScale);
        }
        // ASN 머리 잠금(발주 머리 → 발주 행 → ASN 머리) — 고른 ASN 은 확정 뒤여야, 되돌림은 마감된 ASN 도 받는다
        AsnLock asn = null;
        if (asnLineId != null) {
            asn = mapper.lockAsn(asnLineId);
            if (asn == null) {
                throw new BusinessException(HbErrorCode.NOT_FOUND);
            }
            if (asn.getPoLineId() != poLineId) {
                throw new BusinessException(HbErrorCode.SRM_ASN_LINE_MISMATCH);
            }
            if (reversesRef == null && !ASN_RECEIVABLE.contains(asn.getStatus())) {
                throw new BusinessException(HbErrorCode.SRM_STATE);
            }
        }
        mapper.insertReceipt(poId, poLineId, on, qty, trxRef, asnLineId, lotId, by);
        mapper.addRecv(poLineId, qty, by);
        String status = head.getStatus();
        if (!"CLOSED".equals(status)) {
            Summary s = mapper.summary(poId);
            String to = s.isAnyRecv() ? (s.isFull() ? "RECEIVED" : "PARTIAL_RECEIVED")
                    : (mapper.ackAccepted(poId) ? "ACKED" : "ISSUED");
            if (!to.equals(status)) {
                if (mapper.changeStatus(poId, status, to, by) != 1) {
                    throw new IllegalStateException("locked purchase order " + poId + " changed status");
                }
                status = to;
            }
        }
        if (asn != null && !"CLOSED".equals(asn.getStatus())) {
            AsnState st = mapper.asnState(asn.getAsnId());
            String to = st.isAllRecv() ? "RECEIVED" : st.isAnyRecv() ? "PARTIAL_RECEIVED" : "SENT";
            if (!to.equals(asn.getStatus()) && mapper.changeAsnStatus(asn.getAsnId(), asn.getStatus(), to, by) != 1) {
                throw new IllegalStateException("locked notice " + asn.getAsnId() + " changed status");
            }
        }
        log.debug("po receipt {} line {} qty {} ref {}", head.getPoNo(), line.getLineNo(), qty, trxRef);
        return new Receipt(poId, head.getPoNo(), poLineId, line.getLineNo(), head.getCustId(), head.getPlantCd(), head.getWhId(),
                line.getItemId(), qty, line.getUnitCd(), baseQty, line.getBaseUnit(), strip(recv), strip(ordered.subtract(recv)),
                status, on, asnLineId);
    }

    private static BigDecimal strip(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }

    private static String plain(BigDecimal v) {
        return strip(v).toPlainString();
    }
}
