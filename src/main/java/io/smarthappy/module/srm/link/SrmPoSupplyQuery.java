package io.smarthappy.module.srm.link;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.srm.link.PoSupplyMapper.AsnSupply;
import io.smarthappy.module.srm.link.PoSupplyMapper.OpenLine;
import io.smarthappy.module.srm.link.PoSupplyMapper.Promise;
import lombok.RequiredArgsConstructor;

/** {@link PoSupplyQuery} 의 SRM 구현 — 열린 발주 행을 ASN 도착 예정일 → 약속일 → 요구 납기일 순으로 공급 줄로, 기본 단위로 환산 */
@Component
@RequiredArgsConstructor
public class SrmPoSupplyQuery implements PoSupplyQuery {

    /** 환산 수량 소수 자릿수 — 수량 칸(numeric(18,6))과 같게 */
    static final int QTY_SCALE = 6;
    /** in (…) 목록 한 번에 */
    static final int CHUNK = 1000;

    private final PoSupplyMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public List<Supply> open(String plantCd, Collection<Long> itemIds, LocalDate toDate) {
        if (itemIds == null) {
            throw new IllegalArgumentException("itemIds is required");   // 회사·공장 전체를 상한 없이 읽지 않는다
        }
        if (plantCd == null || itemIds.isEmpty()) {
            return List.of();
        }
        List<OpenLine> lines = new ArrayList<>();
        List<Long> ids = itemIds.stream().filter(Objects::nonNull).distinct().toList();
        for (int i = 0; i < ids.size(); i += CHUNK) {
            lines.addAll(mapper.openLines(plantCd, ids.subList(i, Math.min(ids.size(), i + CHUNK))));
        }
        List<Long> ackIds = lines.stream().map(OpenLine::getAckId).filter(Objects::nonNull).distinct().toList();
        Map<Long, List<Promise>> byLine = new HashMap<>();
        for (int i = 0; i < ackIds.size(); i += CHUNK) {
            mapper.promises(ackIds.subList(i, Math.min(ackIds.size(), i + CHUNK)))
                    .forEach(p -> byLine.computeIfAbsent(p.getPoLineId(), k -> new ArrayList<>()).add(p));
        }
        // ASN 남은 양(확정·입고 중) — 공급일 = 도착 예정일이 약속일보다 먼저(R3-6, docs/31 4.11)
        List<Long> lineIds = lines.stream().map(OpenLine::getPoLineId).toList();
        Map<Long, List<AsnSupply>> asnByLine = new HashMap<>();
        for (int i = 0; i < lineIds.size(); i += CHUNK) {
            mapper.asnSupplies(lineIds.subList(i, Math.min(lineIds.size(), i + CHUNK)))
                    .forEach(a -> asnByLine.computeIfAbsent(a.getPoLineId(), k -> new ArrayList<>()).add(a));
        }
        List<Supply> out = new ArrayList<>();
        for (OpenLine l : lines) {
            BigDecimal rest = l.getOpenQty();
            BigDecimal recv = l.getRecvQty() == null ? BigDecimal.ZERO : l.getRecvQty();   // 받은 양은 이른 약속부터 채운 것으로 본다
            for (AsnSupply a : asnByLine.getOrDefault(l.getPoLineId(), List.of())) {
                BigDecimal q = a.getRemainQty().min(rest);
                if (q.signum() > 0) {
                    add(out, l, a.getEtaDate(), q, true, toDate);
                    rest = rest.subtract(q);
                    recv = recv.add(q);     // ASN 으로 오는 양도 이른 약속을 채운 것으로 본다(약속과 겹쳐 두 번 세지 않게)
                }
            }
            if (l.getAckId() != null) {
                for (Promise p : byLine.getOrDefault(l.getPoLineId(), List.of())) {
                    if (p.getAckId() != l.getAckId()) {
                        continue;
                    }
                    BigDecimal left = p.getPromisedQty().subtract(recv);
                    recv = left.signum() < 0 ? left.negate() : BigDecimal.ZERO;
                    BigDecimal q = left.min(rest);   // 취소 수량이 늘어 약속이 열린 수량을 넘으면 열린 만큼만
                    if (q.signum() > 0) {
                        add(out, l, p.getPromisedDate(), q, true, toDate);
                        rest = rest.subtract(q);
                    }
                }
            }
            if (rest.signum() > 0) {
                add(out, l, l.getDueDate(), rest, false, toDate);
            }
        }
        out.sort(Comparator.comparing(Supply::supplyDate).thenComparing(Supply::poNo).thenComparingInt(Supply::lineNo));
        return out;
    }

    private static void add(List<Supply> out, OpenLine l, LocalDate date, BigDecimal qty, boolean promised, LocalDate toDate) {
        if (toDate != null && date.isAfter(toDate)) {
            return;
        }
        boolean base = l.getFactor() != null;
        BigDecimal q = (base ? qty.multiply(l.getFactor()).setScale(QTY_SCALE, RoundingMode.HALF_UP) : qty).stripTrailingZeros();
        if (q.scale() < 0) {
            q = q.setScale(0);
        }
        BigDecimal po = qty.stripTrailingZeros();
        if (po.scale() < 0) {
            po = po.setScale(0);
        }
        out.add(new Supply(l.getPoId(), l.getPoNo(), l.getPoLineId(), l.getLineNo(), l.getCustId(), l.getItemId(), date, q,
                base ? l.getBaseUnit() : l.getLineUnit(), promised, base, l.getLineUnit(), po));
    }
}
