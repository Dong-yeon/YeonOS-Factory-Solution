package io.smarthappy.module.wms.inv;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.wms.inv.InvDtos.TrxReq;
import io.smarthappy.module.wms.inv.LotStockMapper.Cell;
import io.smarthappy.module.wms.inv.LotStockMapper.GenRow;
import io.smarthappy.module.wms.link.LotStockLink;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.BusinessException.Detail;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.PolicyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * LOT 계보 → 재고 원장 (P4 2부 B5 투입 차감 · B2+ LOT 변환, docs/30 12.4) — {@link LotStockLink} 구현.
 * MES 트랜잭션 안에서 불린다(MANDATORY). 잠금: MES 가 잡은 공정·LOT·ECO 뒤에 inv_stock 칸(품목·위치·LOT·상태 순) → 원장 INSERT(기간 공유 → 투영).
 * <ul>
 * <li>CONSUME → ISSUE_WO −: 공정의 공장 스위치 {@code WMS_STOCK_LINK} = Y 일 때만. 칸 = 보급 라인 LINESIDE → pick_seq(창고, D-B13).
 *     다른 라인을 보급하는 LINESIDE 는 건드리지 않는다. 재공 LOT(원장에 들어온 적 없음)은 건너뜀(D-B14)</li>
 * <li>SPLIT·MERGE·RELABEL·REWORK·REPAIR → CONV_OUT/CONV_IN 짝: 부모 재고가 있는 칸에서 같은 칸·같은 상태로 자식 LOT 에. 상태 순서 D-B11.
 *     스위치가 켜진 공장의 칸만, LOT 관리 품목만(비관리 품목은 원장에 LOT 이 없다)</li>
 * <li>모자라면 409 INSUFFICIENT_STOCK(D-B4) — 칸 오류가 아니므로 MES 가 그대로 던진다. 멱등 키 LOT_GEN:{계보 id}</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LotStockPosting implements LotStockLink {

    static final String SRC = "LOT_GEN";
    static final String POLICY = "WMS_STOCK_LINK";
    static final Set<String> CONV_TYPES = Set.of("SPLIT", "MERGE", "RELABEL", "REWORK", "REPAIR");
    /** D-B11: 재작업·수리는 불량·보류분부터, 그 밖은 가용분부터 */
    static final List<String> REWORK_ORDER = List.of("HOLD", "BLOCKED", "QC", "AVAILABLE");
    static final List<String> CONV_ORDER = List.of("AVAILABLE", "QC", "HOLD", "BLOCKED");

    private final LotStockMapper mapper;
    private final InventoryService inventory;
    private final PolicyService policy;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onEdge(long genId) {
        GenRow g = gen(genId);
        if (g.getReversalOfId() != null) {
            onReverse(genId);               // 역분개 행을 onEdge 로 받아도 같은 처리
            return;
        }
        if ("CONSUME".equals(g.getRelType())) {
            issue(g);
        } else if (CONV_TYPES.contains(g.getRelType())) {
            convert(g);
        }
        // SORT 등 그 밖의 유형은 재고에 반영하지 않는다
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onReverse(long reversalGenId) {
        GenRow rev = gen(reversalGenId);
        if (rev.getReversalOfId() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        // 공정·공장은 원래 계보 행의 원장이 가진다(그 행들을 그대로 되돌림). 시각 = 정정 계보 시각(docs/30 11.5)
        String reason = rev.getReason() == null || rev.getReason().isBlank() ? "LOT 계보 정정" : rev.getReason().strip();
        int i = 0;
        for (Long trx : mapper.liveTrxOf(rev.getReversalOfId())) {
            inventory.reverseAt(trx, reason.length() > 500 ? reason.substring(0, 500) : reason, rev.getOccurredAt(),
                    SRC + "R:" + reversalGenId + ":" + (++i), rev.getRecordedBy());
        }
    }

    // ------------------------------------------------------------------ 투입 차감

    private void issue(GenRow g) {
        if (g.getProcPlantCd() == null || !on(g.getProcPlantCd())) {
            return;                         // 스위치 꺼짐
        }
        if (!mapper.stockTracked(g.getParentLotId())) {
            wipSkipped(g);                  // 재공 LOT(창고에 들어온 적 없음, D-B14)
            return;
        }
        unitCheck(g);
        Long lot = lotCtrl(g) ? g.getParentLotId() : null;
        List<Long> feeds = g.getProcNodeId() == null ? List.of() : mapper.feedNodesOf(g.getProcNodeId());
        List<Cell> plan;
        try {
            plan = take(mapper.issueCells(g.getProcPlantCd(), g.getItemId(), lot, feeds), g.getQty());
        } catch (BusinessException ex) {
            BigDecimal staged = ex.code() == HbErrorCode.INSUFFICIENT_STOCK ? mapper.stagingQty(g.getProcPlantCd(), g.getItemId(), lot) : null;
            if (staged != null && staged.signum() > 0) {      // 출하 대기 칸에는 있다 — 옮긴 뒤 투입(docs/53 F1, MES 요청 문구)
                throw new BusinessException(HbErrorCode.INSUFFICIENT_STOCK,
                        List.of(new Detail("wms.issue.stagingHint", staged.stripTrailingZeros().toPlainString())));
            }
            throw ex;
        }
        List<TrxReq> rows = new ArrayList<>();
        for (Cell c : plan) {
            rows.add(new TrxReq("ISSUE_WO", g.getItemId(), c.getNodeId(), c.getStockStatus(), c.getQty().negate(), null, SRC, g.getGenId(),
                    null, null, g.getOccurredAt(), "AUTO", g.getPerformedBy(), lot));
        }
        inventory.postAs(rows, SRC + ":" + g.getGenId(), g.getRecordedBy());
    }

    // ------------------------------------------------------------------ LOT 변환

    private void convert(GenRow g) {
        if (!lotCtrl(g)) {
            return;                         // 비관리 품목은 원장에 LOT 이 없다 — 재고 변화 없음
        }
        if (!mapper.stockTracked(g.getParentLotId())) {
            wipSkipped(g);                  // 재공 LOT(D-B14)
            return;
        }
        unitCheck(g);
        List<String> order = "REWORK".equals(g.getRelType()) || "REPAIR".equals(g.getRelType()) ? REWORK_ORDER : CONV_ORDER;
        Map<String, Boolean> plants = new HashMap<>();
        List<Cell> all = mapper.convCells(g.getParentLotId(), order);
        List<Cell> cells = all.stream().filter(c -> plants.computeIfAbsent(c.getPlantCd(), this::on)).toList();
        if (!all.isEmpty() && cells.isEmpty()) {
            return;                         // 부모 재고가 꺼진 공장에만 있다
        }
        if (all.isEmpty() && !anyOn(g)) {
            return;                         // 재고가 다 나갔고 켜진 공장도 모른다 — 반영할 곳이 없다
        }
        List<Cell> plan = take(cells, g.getQty());
        List<TrxReq> rows = new ArrayList<>();
        for (Cell c : plan) {
            UUID grp = UUID.randomUUID();
            rows.add(new TrxReq("CONV_OUT", g.getItemId(), c.getNodeId(), c.getStockStatus(), c.getQty().negate(), grp, SRC, g.getGenId(),
                    null, null, g.getOccurredAt(), "AUTO", g.getPerformedBy(), g.getParentLotId()));
            rows.add(new TrxReq("CONV_IN", g.getItemId(), c.getNodeId(), c.getStockStatus(), c.getQty(), grp, SRC, g.getGenId(),
                    null, null, g.getOccurredAt(), "AUTO", g.getPerformedBy(), g.getChildLotId()));
        }
        inventory.postAs(rows, SRC + ":" + g.getGenId(), g.getRecordedBy());
    }

    // ------------------------------------------------------------------ 공통

    /**
     * 우선순위 순서의 칸에서 qty 만큼 고른다. 칸은 칸 순서(품목·위치·LOT·상태)로 잠근 뒤 지금 수량으로 다시 계산한다.
     * 반환 = 칸별 차감 수량(Cell.qty 를 차감 수량으로 바꿔 돌려줌). 모자라면 409
     */
    private List<Cell> take(List<Cell> ordered, BigDecimal qty) {
        if (ordered.isEmpty()) {
            throw new BusinessException(HbErrorCode.INSUFFICIENT_STOCK);
        }
        Map<Long, Cell> locked = mapper.lockCells(ordered.stream().map(Cell::getStockId).sorted().toList()).stream()
                .collect(Collectors.toMap(Cell::getStockId, Function.identity()));
        BigDecimal left = qty;
        List<Cell> plan = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (Cell c : ordered) {
            Cell now = locked.get(c.getStockId());
            if (left.signum() <= 0 || now == null || now.getQty().signum() <= 0 || !seen.add(c.getStockId())) {
                continue;
            }
            BigDecimal t = now.getQty().min(left);
            Cell p = new Cell();
            p.setStockId(now.getStockId());
            p.setPlantCd(now.getPlantCd());
            p.setNodeId(now.getNodeId());
            p.setStockStatus(now.getStockStatus());
            p.setQty(t);
            plan.add(p);
            left = left.subtract(t);
        }
        if (left.signum() > 0) {
            throw new BusinessException(HbErrorCode.INSUFFICIENT_STOCK);      // D-B4 — 칸 오류 없음(MES 가 그대로 던진다)
        }
        return plan;
    }

    /** D-B14 건너뜀을 로그로 남긴다(사용자 확정 2026-10-22 — 건수는 로그 "WMS_WIP_SKIP" 로 센다) */
    private static void wipSkipped(GenRow g) {
        log.info("WMS_WIP_SKIP gen={} rel={} lot={} qty={}", g.getGenId(), g.getRelType(), g.getParentLotId(), g.getQty());
    }

    private GenRow gen(long genId) {
        GenRow g = mapper.findGen(genId);
        if (g == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);           // 다른 회사 계보(RLS) 또는 없는 id
        }
        return g;
    }

    /** 부모 재고가 0 일 때: 이 LOT 이 마지막으로 있던 공장이 켜져 있으면 부족(409), 아니면 반영 안 함 */
    private boolean anyOn(GenRow g) {
        String last = mapper.lastPlantOf(g.getParentLotId());
        return last != null && on(last);
    }

    private boolean on(String plantCd) {
        return "Y".equals(policy.value(POLICY, plantCd));
    }

    private static boolean lotCtrl(GenRow g) {
        return "Y".equals(g.getLotCtrlYn());
    }

    /** 계보 수량 = LOT 단위. 원장은 품목 기본 단위만(D-B1) — 다르면 섞지 않는다 */
    private static void unitCheck(GenRow g) {
        if (!g.getUnitCd().equals(g.getItemUnitCd())) {
            throw InventoryService.fieldError("unitCd", "UNIT");
        }
    }
}
