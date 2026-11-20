package io.smarthappy.module.wms.inv;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.link.SpareIssueLink;
import io.smarthappy.module.wms.inv.InvDtos.TrxReq;
import io.smarthappy.module.wms.inv.MroMapper.Cell;
import io.smarthappy.module.wms.inv.MroMapper.Issued;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * CMS 예비품 출고·반납 → 재고 원장 (docs/33 11.15, docs/30 12.8) — {@link SpareIssueLink} 구현. CMS 트랜잭션 안에서 불린다(MANDATORY).
 * <ul>
 * <li>출고 ISSUE_MRO −: 공장 정책 {@code WMS_MRO_LINK} = Y 일 때만, 원장 밖 품목은 건너뜀(D33). 칸 = 그 공장 AVAILABLE·보류 아닌 LOT,
 *     폐기·수입검사·출하 대기 위치 제외, LOT 선입 → pick_seq. 모든 줄의 후보 칸을 먼저 칸 순서로 잠그고(11.6 ③) 지금 수량으로 배분 → 줄마다 원장(멱등 키 CMS_PART:{partId})</li>
 * <li>반납 MRO_RTN +(D32): 그 줄을 출고한 칸으로, 나중에 출고한 칸부터. 칸을 잠근 뒤 누적 반납 ≤ 출고 확인 — 같은 줄 동시 반납도 넘지 않는다. 요청 id 로 한 번만</li>
 * <li>부족·마감은 409 그대로(칸 오류 없음) — CMS 완료 전체 롤백(D31)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MroIssuePosting implements SpareIssueLink {

    static final String SRC = "CMS_PART";
    static final String POLICY = "WMS_MRO_LINK";
    /** 반납 누계 한 번에 읽는 줄 수(IN 목록 길이 상한) */
    static final int RETURNED_CHUNK = 500;

    private final MroMapper mapper;
    private final InvMapper invMapper;
    private final InventoryService inventory;
    private final PolicyService policy;
    private final BizClock clock;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onMwoDone(long mwoId, String plantCd, Instant occurredAt, List<PartIssue> parts, String by) {
        if (parts == null || parts.isEmpty() || plantCd == null || !"Y".equals(policy.value(POLICY, plantCd))) {
            return;                                             // 스위치 꺼짐 — CMS 는 기록만
        }
        if (occurredAt == null || by == null || by.isBlank()) {
            throw new IllegalArgumentException("occurredAt·by 는 필수");
        }
        // 할 줄: 이미 들어간 줄(같은 키)·원장 밖 품목은 뺀다
        List<PartIssue> todo = new ArrayList<>();
        for (PartIssue p : parts.stream().sorted(Comparator.comparingLong(PartIssue::partId)).toList()) {
            if (p.qty() == null || p.qty().signum() <= 0) {
                throw new IllegalArgumentException("part " + p.partId() + " qty must be > 0");
            }
            if (!invMapper.findReq(key(p.partId())).isEmpty()) {
                continue;                                       // 같은 줄 두 번(완료 재시도) — 원장 키가 막는다
            }
            if (!mapper.itemTracked(p.itemId())) {
                log.info("WMS_MRO_SKIP mwo={} part={} item={} qty={}", mwoId, p.partId(), p.itemId(), p.qty());
                continue;
            }
            todo.add(p);
        }
        if (todo.isEmpty()) {
            return;
        }
        // 후보 칸 전부를 칸 순서로 먼저 잠근다 — 줄이 여러 개여도 잠금 순서는 하나(11.6 ③)
        Map<Long, List<Cell>> cands = new LinkedHashMap<>();
        List<Long> ids = new ArrayList<>();
        for (PartIssue p : todo) {
            List<Cell> c = cands.computeIfAbsent(p.itemId(), i -> mapper.issueCells(plantCd, i));
            c.forEach(x -> ids.add(x.getStockId()));
        }
        Map<Long, BigDecimal> left = new HashMap<>();
        List<Long> lots = new ArrayList<>();
        cands.values().forEach(l -> l.forEach(c -> { if (c.getLotId() != null) { lots.add(c.getLotId()); } }));
        inventory.lockLotsFirst(lots);
        if (!ids.isEmpty()) {
            mapper.lockCells(ids.stream().distinct().sorted().toList()).forEach(c -> left.put(c.getStockId(), c.getQty()));
        }
        OffsetDateTime at = occurredAt.atOffset(ZoneOffset.UTC);
        for (PartIssue p : todo) {
            BigDecimal need = p.qty();
            List<TrxReq> rows = new ArrayList<>();
            for (Cell c : cands.get(p.itemId())) {
                BigDecimal have = left.getOrDefault(c.getStockId(), BigDecimal.ZERO);
                if (need.signum() <= 0 || have.signum() <= 0) {
                    continue;
                }
                BigDecimal take = have.min(need);
                left.put(c.getStockId(), have.subtract(take));
                need = need.subtract(take);
                rows.add(new TrxReq("ISSUE_MRO", p.itemId(), c.getNodeId(), c.getStockStatus(), take.negate(), null, SRC, p.partId(), null,
                        null, at, "AUTO", null, c.getLotId()));
            }
            if (need.signum() > 0) {
                throw new BusinessException(HbErrorCode.INSUFFICIENT_STOCK);      // D31 — 완료 전체 롤백
            }
            inventory.postAs(rows, key(p.partId()), by);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean onPartReturn(long partId, BigDecimal qty, String reason, String clientReqId, String by) {
        String req = clientReqId == null ? "" : clientReqId.strip();
        if (!InventoryService.REQ_ID.matcher(req).matches()) {
            throw InventoryService.fieldError("clientReqId", req.isEmpty() ? "REQUIRED" : "FORMAT");
        }
        if (qty == null || qty.signum() <= 0) {
            throw InventoryService.fieldError("qty", "POSITIVE");
        }
        if (qty.stripTrailingZeros().scale() > 6) {
            throw InventoryService.fieldError("qty", "SCALE", 6);
        }
        String r = reason == null ? "" : reason.strip();
        if (r.length() < 2 || r.length() > 500) {
            throw InventoryService.fieldError("reason", r.length() < 2 ? "REQUIRED" : "TOO_LONG", 500);
        }
        if (!invMapper.findReq(req).isEmpty()) {
            return true;                                        // 같은 요청 — 처음 결과
        }
        List<Issued> before = mapper.issuedOf(partId);
        if (before.isEmpty()) {
            return false;                                       // 출고 원장 없음(스위치 꺼짐·원장 밖 품목)
        }
        // 그 줄을 출고한 칸을 잠근 뒤 다시 읽는다 — 같은 줄의 동시 반납은 여기서 줄 선다
        inventory.lockLotsFirst(before.stream().map(Issued::getLotId).filter(java.util.Objects::nonNull).toList());
        List<Long> cells = before.stream().map(Issued::getStockId).filter(java.util.Objects::nonNull).distinct().sorted().toList();
        if (!cells.isEmpty()) {
            mapper.lockCells(cells);
        }
        List<Issued> now = mapper.issuedOf(partId);
        BigDecimal open = now.stream().map(i -> i.getIssued().subtract(i.getReturned())).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (qty.compareTo(open) > 0) {
            throw new BusinessException(HbErrorCode.WMS_MRO_RETURN_OVER, open.stripTrailingZeros().toPlainString());
        }
        // 나중에 출고한 칸부터 되돌린다(그 칸 출고 − 반납 까지)
        OffsetDateTime at = OffsetDateTime.now(clock.zone(now.get(0).getPlantCd()));
        BigDecimal need = qty;
        List<TrxReq> rows = new ArrayList<>();
        for (int i = now.size() - 1; i >= 0 && need.signum() > 0; i--) {
            Issued c = now.get(i);
            BigDecimal room = c.getIssued().subtract(c.getReturned());
            if (room.signum() <= 0) {
                continue;
            }
            BigDecimal give = room.min(need);
            need = need.subtract(give);
            rows.add(new TrxReq("MRO_RTN", c.getItemId(), c.getNodeId(), c.getStockStatus(), give, null, SRC, partId, null, r, at, "KEY", null,
                    c.getLotId()));
        }
        inventory.postAs(rows, req, by);
        return true;
    }

    /**
     * 반납 누계(C18, docs/33 11.15.2) — 키 = 출고 원장이 있는 줄(반납 없으면 0), 키 없음 = 모름(스위치 꺼짐·원장 밖 품목·완료 전·남의 회사).
     * 부르는 쪽(CMS 작업지시 상세) 읽기 트랜잭션 안에서 잠금 없이 한 번. 읽기 실패는 빈 맵(모름) — 상세 화면을 막지 않는다
     */
    @Override
    public Map<Long, BigDecimal> returnedQty(Collection<Long> partIds) {
        if (partIds == null || partIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = partIds.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            Map<Long, BigDecimal> out = new LinkedHashMap<>();
            for (int i = 0; i < ids.size(); i += RETURNED_CHUNK) {
                mapper.returnedOf(ids.subList(i, Math.min(ids.size(), i + RETURNED_CHUNK)))
                        .forEach(r -> out.put(r.getPartId(), r.getReturned().stripTrailingZeros()));
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("WMS_MRO_RETURNED_READ_FAIL parts={} {}", ids.size(), e.toString());
            return Map.of();
        }
    }

    static String key(long partId) {
        return SRC + ":" + partId;
    }
}
