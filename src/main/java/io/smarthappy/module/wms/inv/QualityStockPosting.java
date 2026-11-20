package io.smarthappy.module.wms.inv;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.wms.inv.InvDtos.TrxReq;
import io.smarthappy.module.wms.inv.LotStockMapper.Cell;
import io.smarthappy.module.wms.inv.QualityStockMapper.DispRow;
import io.smarthappy.module.wms.inv.QualityStockMapper.InspRow;
import io.smarthappy.module.wms.inv.QualityStockMapper.NcrRow;
import io.smarthappy.module.wms.link.QualityStockLink;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.PolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * QMS 판정·무효·NCR 종결 → 재고 상태 (P4 2부 B3, docs/30 12.15 · QMS 검토 docs/29_P3 21장) — {@link QualityStockLink} 구현.
 * QMS 트랜잭션 안에서 불린다(MANDATORY). 잠금: QMS 가 잡은 qms_insp·qms_ncr(→ QMS 자체 순서 "검사 → LOT") 뒤에 lot_master FOR SHARE
 * → inv_stock 칸(칸 순서) → 원장 INSERT(기간 공유 → 투영). 무효는 역분개가 같은 순서(LOT 먼저)로 잡는다. QMS 표는 잠그지 않는다.
 * <ul>
 * <li>판정(RECEIVE·FINAL): 그 LOT 의 QC 칸 전부 → OK 면 AVAILABLE, NG 면 BLOCKED(같은 위치 STATUS_OUT/IN 짝). 출처 QMS_INSP·키 QMS_INSP:{검사 id}</li>
 * <li>무효: 판정 원장 짝을 되돌림(키 QMS_INSPR:{id}). 처분이 반영된 LOT 이면 409 WMS_QUALITY_DISPOSED</li>
 * <li>NCR 종결: 처분 행 순서대로 BLOCKED 에서 — 특채 → AVAILABLE, 폐기 → SCRAP. 출처 QMS_NCR(src_id = 처분 id)·키 QMS_NCR:{NCR id}</li>
 * <li>공장 스위치 {@code WMS_QUALITY_LINK} = Y 공장의 칸만. LOT 비관리 품목은 반영 안 함(로그 WMS_QC_SKIP, D-B27)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QualityStockPosting implements QualityStockLink {

    static final String POLICY = "WMS_QUALITY_LINK";
    static final String SRC_INSP = "QMS_INSP";
    static final String SRC_NCR = "QMS_NCR";
    /** 재고에 반영하는 검사 유형(D-B15·WC-3) — 초품·공정·순회는 재고와 무관 */
    static final Set<String> INSP_TYPES = Set.of("RECEIVE", "FINAL");

    private final QualityStockMapper mapper;
    private final InventoryService inventory;
    private final PolicyService policy;

    // ------------------------------------------------------------------ 판정

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onInspJudged(long inspId) {
        InspRow r = insp(inspId);
        if (!INSP_TYPES.contains(r.getInspType()) || !"JUDGED".equals(r.getStatus()) || r.getResult() == null) {
            return;
        }
        if (!"Y".equals(r.getLotCtrlYn())) {
            log.info("WMS_QC_SKIP insp={} lot={} — LOT 비관리 품목(D-B27)", inspId, r.getLotId());
            return;
        }
        if (!mapper.liveTrxOf(SRC_INSP, inspId).isEmpty()) {
            return;                         // 이미 반영(같은 판정을 다시 부름)
        }
        String to = "OK".equals(r.getResult()) ? "AVAILABLE" : "BLOCKED";
        inventory.lockLotsFirst(List.of(r.getLotId()));          // 11.6: 검사(QMS) → LOT(공유) → 칸 — 칸보다 LOT 먼저
        List<Cell> cells = lock(onPlants(mapper.cellsOf(r.getLotId(), "QC")));
        List<TrxReq> rows = new ArrayList<>();
        for (Cell c : cells) {
            if (c.getQty().signum() > 0) {
                statusPair(rows, r.getItemId(), r.getLotId(), c, c.getQty(), to, SRC_INSP, inspId, r.getInspNo(), r.getJudgedAt());
            }
        }
        if (rows.isEmpty()) {
            log.info("WMS_QC_EMPTY insp={} lot={} — 검사 대기(QC) 재고 없음(D-B26)", inspId, r.getLotId());
            return;
        }
        inventory.postAs(rows, SRC_INSP + ":" + inspId, r.getJudgedBy());
    }

    // ------------------------------------------------------------------ 무효

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onInspVoided(long inspId) {
        InspRow r = insp(inspId);
        List<Long> live = mapper.liveTrxOf(SRC_INSP, inspId);
        String by = r.getVoidedBy() == null ? InventoryService.me().loginId() : r.getVoidedBy();
        if (!live.isEmpty()) {                // 비었으면 판정 전 검사·반영 안 된 판정(스위치 꺼짐·비관리·QC 없음)
            String ncrNo = mapper.disposedNcrNo(r.getLotId());
            if (ncrNo != null) {
                throw new BusinessException(HbErrorCode.WMS_QUALITY_DISPOSED, ncrNo);     // WC-2
            }
            inventory.reverseFromSource(live, "QMS 검사 무효 " + r.getInspNo(), r.getVoidedAt(), SRC_INSP + "R:" + inspId, by);
        }
        releaseInherited(r, by);
    }

    /**
     * NG 판정을 이어받아 BLOCKED 로 들어온 추가 입고분(12.15.13 B-2) — 그 NG 가 무효로 사라지고 살아 있는 최신 판정이 NG 가 아니면 QC 로 되돌린다
     * (검사 대기 = 재검사). 판정 시각 뒤 BLOCKED 입고 원장 순합만큼, 그 위치에 남은 BLOCKED 안에서. 출처 QMS_INSP·같은 검사 id(사람 화면 역분개 막힘)
     */
    private void releaseInherited(InspRow r, String by) {
        if (!"NG".equals(r.getResult()) || r.getJudgedAt() == null || !"Y".equals(r.getLotCtrlYn())
                || "NG".equals(mapper.latestResult(r.getLotId()))) {
            return;
        }
        Map<Long, BigDecimal> inherited = new HashMap<>();
        mapper.blockedReceiptsSince(r.getLotId(), r.getJudgedAt()).forEach(n -> inherited.put(n.getNodeId(), n.getQty()));
        if (inherited.isEmpty()) {
            return;
        }
        inventory.lockLotsFirst(List.of(r.getLotId()));
        List<Cell> cells = lock(mapper.cellsOf(r.getLotId(), "BLOCKED").stream().filter(c -> inherited.containsKey(c.getNodeId())).toList());
        List<TrxReq> rows = new ArrayList<>();
        for (Cell c : cells) {
            BigDecimal take = c.getQty().min(inherited.get(c.getNodeId()));
            if (take.signum() > 0) {
                statusPair(rows, r.getItemId(), r.getLotId(), c, take, "QC", SRC_INSP, r.getInspId(), r.getInspNo(), r.getVoidedAt());
            }
        }
        if (!rows.isEmpty()) {
            inventory.postAs(rows, SRC_INSP + "Q:" + r.getInspId(), by);
        }
    }

    // ------------------------------------------------------------------ NCR 종결

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onNcrClosed(long ncrId) {
        NcrRow n = mapper.findNcr(ncrId);
        if (n == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);           // 다른 회사(RLS)·없는 id
        }
        if (!"CLOSED".equals(n.getStatus())) {
            return;
        }
        if (!"Y".equals(n.getLotCtrlYn())) {
            log.info("WMS_QC_SKIP ncr={} lot={} — LOT 비관리 품목(D-B27 확장)", ncrId, n.getLotId());
            return;
        }
        List<DispRow> disps = mapper.findDisps(ncrId).stream()
                .filter(d -> "USE_AS_IS".equals(d.getDispType()) || "SCRAP".equals(d.getDispType())).toList();
        if (disps.isEmpty() || !mapper.liveTrxOf(SRC_NCR, disps.get(0).getDispId()).isEmpty()) {
            return;                         // 반영할 처분 없음(RETURN·REWORK·SORT 는 BLOCKED 그대로) 또는 이미 반영
        }
        // 스위치는 새 판정 반영만 끈다 — 판정이 이미 반영된 공장은 스위치를 꺼도 후속(종결)을 반영(무효와 같이, 12.15.5 B-1)
        Set<String> reflected = Set.copyOf(mapper.reflectedPlants(n.getLotId()));
        Predicate<String> target = p -> reflected.contains(p) || on(p);
        if (mapper.plantsOf(n.getLotId()).stream().noneMatch(target)) {
            log.info("WMS_QC_OFF ncr={} lot={} — 스위치 켜진·판정 반영된 공장에 재고 없음", ncrId, n.getLotId());
            return;
        }
        if (!n.getUnitCd().equals(n.getItemUnitCd())) {
            throw new BusinessException(HbErrorCode.WMS_QUALITY_UNIT, n.getUnitCd(), n.getItemUnitCd());     // WC-1 — 환산하지 않음
        }
        BigDecimal need = disps.stream().map(DispRow::getQty).reduce(BigDecimal.ZERO, BigDecimal::add);
        inventory.lockLotsFirst(List.of(n.getLotId()));          // 11.6: NCR(QMS) → LOT(공유) → 칸
        List<Cell> cells = lock(mapper.cellsOf(n.getLotId(), "BLOCKED").stream().filter(c -> target.test(c.getPlantCd())).toList());
        if (n.getRtnNodeId() != null) {
            // 클레임 NCR: 그 반품이 받은 칸부터, 모자라면 칸 순(QA CR-c) — 같은 LOT 의 다른 반품·검사 NG 칸을 먼저 처분하지 않게
            long own = n.getRtnNodeId();
            cells = cells.stream().sorted(Comparator.comparing((Cell c) -> c.getNodeId() != own).thenComparing(Cell::getNodeId)).toList();
        }
        BigDecimal have = cells.stream().map(Cell::getQty).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (have.compareTo(need) < 0) {
            throw new BusinessException(HbErrorCode.WMS_QUALITY_SHORT, have.stripTrailingZeros().toPlainString(),
                    need.stripTrailingZeros().toPlainString());              // WB-2 — 결재자도 "재고" 맥락으로 읽는다
        }
        List<TrxReq> rows = new ArrayList<>();
        Map<Long, BigDecimal> left = new HashMap<>();
        cells.forEach(c -> left.put(c.getStockId(), c.getQty()));
        for (DispRow d : disps) {
            BigDecimal want = d.getQty();
            for (Cell c : cells) {
                BigDecimal room = left.get(c.getStockId());
                if (want.signum() <= 0 || room.signum() <= 0) {
                    continue;
                }
                BigDecimal take = room.min(want);
                if ("USE_AS_IS".equals(d.getDispType())) {
                    statusPair(rows, n.getItemId(), n.getLotId(), c, take, "AVAILABLE", SRC_NCR, d.getDispId(), n.getNcrNo(), n.getClosedAt());
                } else {
                    rows.add(new TrxReq("SCRAP", n.getItemId(), c.getNodeId(), "BLOCKED", take.negate(), null, SRC_NCR, d.getDispId(), null,
                            n.getNcrNo(), n.getClosedAt(), "AUTO", null, n.getLotId()));
                }
                left.put(c.getStockId(), room.subtract(take));
                want = want.subtract(take);
            }
        }
        inventory.postAs(rows, SRC_NCR + ":" + ncrId, n.getClosedBy());
    }

    // ------------------------------------------------------------------ 공통

    private InspRow insp(long inspId) {
        InspRow r = mapper.findInsp(inspId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);           // 다른 회사(RLS)·없는 id
        }
        return r;
    }

    /** 같은 위치에서 상태 바꿈 짝(STATUS_OUT 지금 상태 − / STATUS_IN 새 상태 +) */
    private static void statusPair(List<TrxReq> rows, long itemId, long lotId, Cell c, BigDecimal qty, String to, String src, long srcId,
            String remark, java.time.OffsetDateTime at) {
        UUID grp = UUID.randomUUID();
        rows.add(new TrxReq("STATUS_OUT", itemId, c.getNodeId(), c.getStockStatus(), qty.negate(), grp, src, srcId, null, remark, at, "AUTO", null,
                lotId));
        rows.add(new TrxReq("STATUS_IN", itemId, c.getNodeId(), to, qty, grp, src, srcId, null, remark, at, "AUTO", null, lotId));
    }

    /** 스위치 켜진 공장의 칸만 */
    private List<Cell> onPlants(List<Cell> cells) {
        Map<String, Boolean> on = new HashMap<>();
        return cells.stream().filter(c -> on.computeIfAbsent(c.getPlantCd(), this::on)).toList();
    }

    /** 칸 순서로 잠근 뒤 지금 수량(잠그기 전 목록 순서 = 위치 순) */
    private List<Cell> lock(List<Cell> cells) {
        if (cells.isEmpty()) {
            return cells;
        }
        Map<Long, Cell> now = new HashMap<>();
        mapper.lockCells(cells.stream().map(Cell::getStockId).sorted().toList()).forEach(c -> now.put(c.getStockId(), c));
        return cells.stream().map(c -> now.get(c.getStockId())).filter(c -> c != null && c.getQty().signum() > 0).toList();
    }

    private boolean on(String plantCd) {
        return "Y".equals(policy.value(POLICY, plantCd));
    }
}
