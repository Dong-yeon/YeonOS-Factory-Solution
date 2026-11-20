package io.smarthappy.module.wms.inv;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.srm.link.PoReceiptGate;
import io.smarthappy.module.srm.link.ReturnShipGate;
import io.smarthappy.module.wms.inv.InvDtos.ItemOpt;
import io.smarthappy.module.wms.inv.InvDtos.PostResult;
import io.smarthappy.module.wms.inv.InvDtos.TrxReq;
import io.smarthappy.module.wms.inv.InvDtos.TrxRow;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantContext;

/**
 * 재고 원장 엔진 (docs/30 W3) — **다른 모듈이 부르는 계약**: {@link #post}·{@link #reverse}(업무 트랜잭션 MANDATORY).
 *
 * <ul>
 * <li>원장 행을 넣으면 DB 트리거가 현재고(inv_stock)를 고친다 — 서비스는 inv_stock 에 쓰지 않는다(규칙 SQL 한 곳)</li>
 * <li>마이너스 재고는 inv_stock CHECK 가 막는다(동시 출고도) → 409 INSUFFICIENT_STOCK</li>
 * <li>clientReqId: 같은 요청을 다시 보내면 처음 넣은 행 id 를 그대로 돌려준다(오프라인 재전송, docs/25 3장)</li>
 * <li>정정은 역분개만 — 이동은 짝 두 행을 함께 되돌린다</li>
 * </ul>
 */
@Service
public class InventoryService {

    static final String RCV_PO = "RCV_PO";
    static final Set<String> TYPES = Set.of("OPENING", "ADJ_IN", "ADJ_OUT", "MOVE_OUT", "MOVE_IN", "STATUS_OUT", "STATUS_IN",
            "COUNT_ADJ", "RCV_PRD", "RCV_MAT", "ISSUE_WO", "CONV_OUT", "CONV_IN", "RCV_PO", "SHIP", "ISSUE_MRO", "MRO_RTN", "RTN_SUP", "SCRAP", "RCV_RTN");
    static final Set<String> STATUSES = Set.of("AVAILABLE", "QC", "HOLD", "BLOCKED");
    static final Set<String> METHODS = Set.of("KEY", "SCAN", "AUTO", "IMPORT");
    /** 협력사 반품 출고 유형과 SRM 게이트 근거 접두(docs/30 12.14) */
    static final String RETURN_ISSUE = "RTN_SUP";
    static final String RETURN_REF = "RTN_ISS:";
    static final Pattern REQ_ID = Pattern.compile("^[A-Za-z0-9._:-]{8,64}$");
    static final int MAX_ROWS = 1000;

    /**
     * 한 트랜잭션에서 재고 칸 여러 개를 건드릴 때의 순서 — (품목, 위치, LOT(없음 먼저), 상태). 원장 트리거가 행마다 칸(inv_stock)을 잠그므로
     * INSERT 순서 = 칸 잠금 순서. 모든 경로(실사 확정·이동 짝·LOT 변환·투입 차감, MES 투입 순서)가 같은 순서면 서로 거꾸로 기다리지 않는다
     * (docs/30 11.6, QA QB-1). {@code LotStockMapper.lockCells} 의 ORDER BY 와 같아야 한다
     */
    static final Comparator<TrxReq> CELL_ORDER = Comparator.comparingLong(TrxReq::itemId).thenComparingLong(TrxReq::nodeId)
            .thenComparing(TrxReq::lotId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(TrxReq::stockStatus, Comparator.nullsFirst(Comparator.naturalOrder()));

    /** 트리거·제약 → (칸, 코드). ck_inv_stock_qty 는 칸 오류가 아니라 409 */
    static final Map<String, String[]> CONSTRAINT_FIELD = Map.ofEntries(
            Map.entry("ck_inv_trx_lot_item", new String[] {"lotNo", "LOT_REQUIRED"}),
            Map.entry("ck_inv_trx_lot_hold", new String[] {"lotNo", "LOT_HOLD"}),
            Map.entry("ck_inv_trx_item_use", new String[] {"itemId", "INACTIVE"}),
            Map.entry("fk_inv_trx_item_comp", new String[] {"itemId", "NOT_FOUND"}),
            Map.entry("fk_inv_trx_lot_comp", new String[] {"lotNo", "LOT_MISMATCH"}),
            Map.entry("ck_inv_trx_unit", new String[] {"itemId", "UNIT"}),
            Map.entry("ck_inv_trx_node_stock", new String[] {"nodeId", "STOCK_NOT_ALLOWED"}),
            Map.entry("ck_inv_trx_node_type", new String[] {"nodeId", "NOT_STORAGE_NODE"}),
            Map.entry("fk_inv_trx_node_comp", new String[] {"nodeId", "NOT_FOUND"}),
            Map.entry("fk_inv_trx_worker_comp", new String[] {"performedBy", "NOT_FOUND"}),
            Map.entry("fk_inv_trx_worker", new String[] {"performedBy", "NOT_FOUND"}),
            Map.entry("fk_inv_trx_po_line_comp", new String[] {"poLineId", "NOT_FOUND"}));

    private final InvMapper mapper;
    private final ObjectProvider<PoReceiptGate> poGate;
    private final ObjectProvider<ReturnShipGate> rtnGate;

    public InventoryService(InvMapper mapper, ObjectProvider<PoReceiptGate> poGate, ObjectProvider<ReturnShipGate> rtnGate) {
        this.mapper = mapper;
        this.poGate = poGate;
        this.rtnGate = rtnGate;
    }

    /**
     * 원장 게시. 행 순서대로 넣고 행 id 를 돌려준다.
     *
     * @param clientReqId 멱등 키(선택). 이미 들어간 요청이면 아무것도 넣지 않고 처음 결과(replay = true)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostResult post(List<TrxReq> rows, String clientReqId) {
        if (rows != null && rows.stream().anyMatch(t -> t != null && RCV_PO.equals(t.trxType()))) {
            // 구매 입고는 SRM 게이트(PoReceiptGate.receiveAt)와 한 트랜잭션인 입고 서비스 경로만 — 직접 게시는 게이트를 건너뛴다(12.6.5)
            throw fieldError("trxType", "CODE");
        }
        return postAs(rows, clientReqId, me().loginId());
    }

    /** 구매·자재 입고 게시 — {@link MaterialReceiptService} 만(RCV_PO 는 SRM 게이트를 부른 뒤에만, 12.6.5) */
    @Transactional(propagation = Propagation.MANDATORY)
    PostResult postReceipt(List<TrxReq> rows, String clientReqId) {
        return postAs(rows, clientReqId, me().loginId());
    }

    /**
     * 사람이 보낸 요청(이동·조정 — 사무 화면·창고 PDA)의 게시. 같은 clientReqId 가 다시 오면 **내용이 같을 때만** 처음 결과(replay),
     * 내용(유형·품목·위치·상태·수량·LOT·출처)이 다르거나 다른 출처 원장 행이 쓰던 id 면 409 DUPLICATE_REQUEST(PQ-1, POP 실적과 같은 규칙).
     * 내부 게시(LOT·생산 입고·예비품)는 자기 키로 다시 계산한 행을 보낼 수 있어 {@link #post} 그대로 둔다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostResult postChecked(List<TrxReq> rows, String clientReqId) {
        String req = clientReqId == null || clientReqId.isBlank() ? null : clientReqId.strip();
        if (req != null && rows != null && !rows.isEmpty() && REQ_ID.matcher(req).matches()) {
            List<InvMapper.ReqRow> done = mapper.findReqRows(req);
            if (!done.isEmpty()) {
                if (!sameRequest(done, rows)) {
                    throw new BusinessException(HbErrorCode.DUPLICATE_REQUEST);
                }
                return new PostResult(mapper.findReq(req), true);
            }
        }
        return post(rows, clientReqId);
    }

    /** 처음 행들과 다시 온 요청 행들이 같은 내용인가 — 행 수·순서(req_seq)·유형·품목·위치·상태·수량(값 비교)·LOT·출처 */
    static boolean sameRequest(List<InvMapper.ReqRow> done, List<TrxReq> rows) {
        if (done.size() != rows.size()) {
            return false;
        }
        for (int i = 0; i < rows.size(); i++) {
            InvMapper.ReqRow d = done.get(i);
            TrxReq r = rows.get(i);
            if (r == null || d.getReqSeq() != i + 1 || !d.getTrxType().equals(r.trxType()) || d.getItemId() != r.itemId()
                    || d.getNodeId() != r.nodeId() || !d.getStockStatus().equals(r.stockStatus()) || r.qty() == null
                    || d.getQty().compareTo(r.qty()) != 0 || !java.util.Objects.equals(d.getLotId(), r.lotId())
                    || !java.util.Objects.equals(d.getSrcType(), r.srcType())) {
                return false;
            }
        }
        return true;
    }

    /** 기록자를 출처에서 이어받는 게시(다른 모듈의 원장을 옮겨 적을 때 — 입력한 사람이 그대로 남는다) */
    @Transactional(propagation = Propagation.MANDATORY)
    PostResult postAs(List<TrxReq> rows, String clientReqId, String recordedBy) {
        if (rows == null || rows.isEmpty()) {
            throw fieldError("rows", "REQUIRED");
        }
        if (rows.size() > MAX_ROWS) {
            throw fieldError("rows", "TOO_MANY_LABELS", MAX_ROWS);
        }
        String req = clientReqId == null || clientReqId.isBlank() ? null : clientReqId.strip();
        if (req != null) {
            if (!REQ_ID.matcher(req).matches()) {
                throw fieldError("clientReqId", "FORMAT");
            }
            List<Long> done = mapper.findReq(req);
            if (!done.isEmpty()) {
                return new PostResult(done, true);
            }
        }
        lockLots(rows.stream().map(TrxReq::lotId).toList());
        // 넣는 순서는 칸 순서(CELL_ORDER), 행 id·req_seq 는 받은 순서 그대로 돌려준다
        Long[] ids = new Long[rows.size()];
        for (int i : cellOrder(rows)) {
            TrxReq t = rows.get(i);
            ItemOpt item = check(t);
            String plant = mapper.nodePlant(t.nodeId());
            if (plant == null) {
                throw fieldError("nodeId", "NOT_FOUND");
            }
            ids[i] = insert(plant, t, item.getUnitCd(), req, req == null ? null : i + 1, recordedBy, null);
        }
        return new PostResult(Arrays.asList(ids), false);
    }

    /**
     * 역분개 — 원래 행(이동이면 짝 두 행)의 정확한 반대 행을 넣는다. 반환 = 역분개 행 id.
     * 원래 행은 그대로(01 3.1). 이미 되돌린 행·역분개 행은 다시 되돌리지 않는다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> reverse(long trxId, String reason) {
        return reverseAll(List.of(trxId), reason, null, null, me().loginId(), false, false);   // 사람 화면 — 업무 출처 행은 막는다
    }

    /**
     * 여러 행을 한 번에 역분개(출하 취소·피킹 취소 — B6a, 출하 모듈 전용: 출처 SHIP_PICK 행도 되돌린다). 이동 짝은 짝째로, 모든 역분개 행을 칸 순서(CELL_ORDER)로 넣는다(11.6 ③).
     * 반환 = 역분개 행 id(받은 행·짝 순서)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> reverseAll(List<Long> trxIds, String reason) {
        return reverseAll(trxIds, reason, null, null, me().loginId(), false, true);
    }

    /** 출처 모듈의 정정(QMS 검사 무효 등)이 부르는 역분개 — 사람 화면이 막힌 출처 행도 되돌린다(docs/30 12.15.4) */
    @Transactional(propagation = Propagation.MANDATORY)
    List<Long> reverseFromSource(List<Long> trxIds, String reason, OffsetDateTime at, String reqId, String by) {
        return reverseAll(trxIds, reason, at, reqId, by, true, false);
    }

    /**
     * 역분개 — 발생 시각을 정할 수 있다(정정이 원래 달이 아니라 정정한 때 반영 — docs/30 11.5). at = null 이면 원래 시각.
     * reqId 가 있으면 첫 역분개 행에 멱등 키로 남긴다(출처 정정 행 하나 = 역분개 한 번)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<Long> reverseAt(long trxId, String reason, OffsetDateTime at, String reqId, String by) {
        return reverseAll(List.of(trxId), reason, at, reqId, by, true, false);
    }

    /** QMS 판정·처분이 만든 행 — 사람이 수불 원장 화면에서 되돌리면 QMS 판정·NCR 과 어긋난다(QMS 에서 무효로, docs/30 12.15.4) */
    static final Set<String> SOURCE_ONLY = Set.of("QMS_INSP", "QMS_NCR");

    /**
     * MES 가 만든 행 — 생산 입고(PRD_RECEIPT, 정정 = MES 입고 정정 → {@link ProductionReceiptPosting})·LOT 계보(LOT_GEN — 투입 차감·변환,
     * 정정 = POP 실적 정정 → {@link LotStockPosting#onReverse}). 사람이 원장만 되돌리면 MES 기록과 어긋나고, 뒤의 MES 정정이 ALREADY_REVERSED 로 막힌다
     */
    static final Set<String> MES_SOURCE = Set.of(ProductionReceiptPosting.SRC, LotStockPosting.SRC);

    /** 출하 피킹·출하 확정이 만든 행(이동 짝·SHIP) — 출하·피킹·영업 출하 수량과 함께 되돌려야 해서 출하 취소·피킹 취소로만(QA CR-e) */
    static final String SHIP_SRC = "SHIP_PICK";

    /**
     * @param fromSource 출처 모듈의 정정 — 사람 화면이 막힌 출처 행도 되돌린다
     * @param shipPath   출하 모듈의 출하 취소·피킹 취소({@link #reverseAll(List, String)}) — {@value #SHIP_SRC} 행을 되돌릴 수 있다
     */
    private List<Long> reverseAll(List<Long> trxIds, String reason, OffsetDateTime at, String reqId, String by, boolean fromSource,
            boolean shipPath) {
        String r = reason == null ? "" : reason.strip();
        if (r.length() < 2 || r.length() > 500) {
            throw fieldError("reason", r.length() < 2 ? "REQUIRED" : "TOO_LONG", 500);
        }
        if (trxIds == null || trxIds.isEmpty()) {
            throw fieldError("trxId", "REQUIRED");
        }
        // 되돌릴 행(이동 짝은 짝째로, 같은 짝을 두 번 넣지 않음)과 짝마다 새 묶음 id
        List<TrxRow> group = new ArrayList<>();
        Set<Long> seen = new java.util.HashSet<>();
        Map<String, UUID> newGrps = new java.util.HashMap<>();
        for (Long trxId : trxIds) {
            TrxRow orig = trxId == null ? null : mapper.findTrx(trxId);
            if (orig == null) {
                throw new BusinessException(HbErrorCode.NOT_FOUND);
            }
            if (orig.getReversalOfId() != null) {
                throw fieldError("trxId", "REVERSAL_OF_REVERSAL");
            }
            if (!fromSource && SOURCE_ONLY.contains(orig.getSrcType())) {
                throw fieldError("trxId", "QMS_SOURCE");
            }
            if (!fromSource && CustRtnService.SRC.equals(orig.getSrcType())) {
                throw fieldError("trxId", "CUST_RTN_SOURCE");     // 고객 반품 입고는 반품 화면 [취소]로만(NCR 과 함께 — docs/59 R-6)
            }
            if (!fromSource && MES_SOURCE.contains(orig.getSrcType())) {
                throw fieldError("trxId", "MES_SOURCE");          // 생산 입고·투입 차감·LOT 변환은 MES 정정으로만(출처 정정이 reverseAt 으로 되돌린다)
            }
            if (!fromSource && MroIssuePosting.SRC.equals(orig.getSrcType()) && "ISSUE_MRO".equals(orig.getTrxType())) {
                throw fieldError("trxId", "CMS_ISSUE_SOURCE");    // 예비품 출고는 CMS 반납으로 바로잡는다(W-10). 반납 MRO_RTN 은 열어 둠 — CMS 에 반납 취소 계약이 없다
            }
            if (!fromSource && !shipPath && SHIP_SRC.equals(orig.getSrcType())) {
                throw fieldError("trxId", "SHIP_SOURCE");         // 출하·피킹 행은 출하 화면 [출하 취소]·[피킹 취소]로만(영업 출하 수량·반품 확인과 함께)
            }
            List<TrxRow> g = orig.getMoveGrpId() != null ? mapper.findMoveGroup(UUID.fromString(orig.getMoveGrpId()))
                    : RETURN_ISSUE.equals(orig.getTrxType()) && orig.getClientReqId() != null ? returnIssueRows(orig.getClientReqId())
                    : List.of(orig);
            for (TrxRow o : g) {
                if (seen.add(o.getTrxId())) {
                    group.add(o);
                }
            }
            if (orig.getMoveGrpId() != null) {
                newGrps.putIfAbsent(orig.getMoveGrpId(), UUID.randomUUID());
            }
        }
        for (TrxRow o : group) {
            if (o.getReversedById() != null) {
                throw fieldError("trxId", "ALREADY_REVERSED");
            }
        }
        // 구매 입고 되돌림: SRM 발주 행 입고 수량을 먼저 줄인다(게이트 잠금 = 발주 머리 → 행, 그 뒤 LOT·기간·재고 — B4b)
        for (TrxRow o : group) {
            if (o.getPoLineId() != null) {
                // 원래 입고의 근거(RCV_PO:{요청 id})로 — SRM 이 그 이력의 발생일·ASN 행을 찾아 함께 되돌린다(SRM 2부 R2·R3, R3b)
                PoReceiptGate.of(poGate).reverseAt(o.getPoLineId(), o.getPoQty().negate(), "RCV_POR:" + o.getTrxId(), by,
                        o.getOccurredAt(), o.getClientReqId() == null ? null : "RCV_PO:" + o.getClientReqId());
            }
        }
        // 반품 출고 되돌림: SRM 반품을 확정으로(교체 요구면 입고 다시) — 반품 → 발주 머리 → 행, 그 뒤 LOT·재고(R4-10, docs/30 12.14)
        Map<Long, TrxRow> returns = new java.util.TreeMap<>();
        group.stream().filter(o -> RETURN_ISSUE.equals(o.getTrxType())).forEach(o -> returns.putIfAbsent(o.getSrcId(), o));
        for (TrxRow o : returns.values()) {
            ReturnShipGate.of(rtnGate).unship(o.getSrcId(), "RTN_ISSR:" + o.getTrxId(), by, o.getOccurredAt(),
                    o.getClientReqId() == null ? null : RETURN_REF + o.getClientReqId());
        }
        lockLots(group.stream().map(TrxRow::getLotId).toList());
        List<TrxReq> backs = new ArrayList<>();
        for (TrxRow o : group) {
            UUID grp = o.getMoveGrpId() == null ? null : newGrps.get(o.getMoveGrpId());
            TrxReq back = new TrxReq(o.getTrxType(), o.getItemId(), o.getNodeId(), o.getStockStatus(), o.getQty().negate(), grp,
                    o.getSrcType(), o.getSrcId(), o.getReasonCd(), r, at == null ? o.getOccurredAt() : at, "CORRECT", o.getPerformedBy());
            backs.add(back.withLot(o.getLotId()).withPo(o.getPoLineId(), o.getPoQty() == null ? null : o.getPoQty().negate()));
        }
        // 칸 순서로 넣는다(이동 짝의 역분개도) — 멱등 키는 원래 첫 행의 역분개에
        Long[] ids = new Long[group.size()];
        for (int i : cellOrder(backs)) {
            TrxRow o = group.get(i);
            boolean first = i == 0;
            ids[i] = insert(o.getPlantCd(), backs.get(i), o.getUnitCd(), first ? reqId : null, first && reqId != null ? 1 : null, by,
                    o.getTrxId());
        }
        return Arrays.asList(ids);
    }

    /** 반품 출고 한 번(같은 요청 id)의 RTN_SUP 행 — 한 행을 되돌리면 그 출고 전체를 되돌린다(나눠 출고 없음 — R4-9) */
    private List<TrxRow> returnIssueRows(String clientReqId) {
        return mapper.findReq(clientReqId).stream().map(mapper::findTrx)
                .filter(t -> t != null && RETURN_ISSUE.equals(t.getTrxType()) && t.getReversalOfId() == null).toList();
    }

    /** 원장 INSERT 앞에 다른 업무 판정(PLM 출하 확인 등)을 끼울 때 — 관련 LOT 을 lot_id 순 FOR SHARE 로 먼저(12.4.5 ②, 12.5.2 ②) */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockLotsFirst(List<Long> lotIds) {
        lockLots(lotIds);
    }

    /** 행 번호를 칸 순서(CELL_ORDER)로 — 같은 칸이면 받은 순서 */
    static int[] cellOrder(List<TrxReq> rows) {
        return java.util.stream.IntStream.range(0, rows.size()).boxed()
                .sorted(Comparator.comparing((Integer i) -> rows.get(i), CELL_ORDER)).mapToInt(Integer::intValue).toArray();
    }

    /**
     * 여러 행을 한 트랜잭션에 넣기 전에 관련 LOT 을 lot_id 순으로 먼저 FOR SHARE (docs/30 12.4.5 ②).
     * 원장 트리거는 행마다 "LOT → 기간 → inv_stock" 순으로 잡으므로, 줄1 = 비관리 품목 X(inv_stock), 줄2 = LOT B 처럼 섞이면
     * LOT B 를 먼저 쥐고 X 재고로 가는 MES 경로(POP 실적·생산 입고)와 서로 기다릴 수 있다 — LOT 을 모두 먼저 잡으면 순서가 같아진다
     */
    private void lockLots(List<Long> lotIds) {
        List<Long> ids = lotIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (!ids.isEmpty()) {
            mapper.lockLotsShare(ids);
        }
    }

    /** 그 시각까지 원장 합(LOT 없음) — 실사 장부 수량(W5) */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal ledgerQtyAsOf(long itemId, long nodeId, String status, Long lotId, OffsetDateTime at) {
        return mapper.ledgerQtyAsOf(itemId, nodeId, status, lotId, at);
    }

    private long insert(String plant, TrxReq t, String unitCd, String req, Integer seq, String by, Long reversalOf) {
        try {
            return mapper.insertTrx(plant, t, unitCd, req, seq, by, reversalOf);
        } catch (DuplicateKeyException e) {
            String c = PgErrors.constraint(e).orElse("");
            if ("uq_inv_trx_reversal".equals(c)) {
                throw fieldError("trxId", "ALREADY_REVERSED");      // 두 사람이 같은 행을 동시에 되돌림
            }
            throw new BusinessException(HbErrorCode.DUPLICATE_REQUEST); // 같은 clientReqId 가 동시에 두 번
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        } catch (DataAccessException e) {
            if ("ck_inv_trx_period_closed".equals(PgErrors.constraint(e).orElse(null))) {
                throw new BusinessException(HbErrorCode.PERIOD_CLOSED);       // 마감 월(W5) — 역분개도 원래 시각이라 같이 막힌다
            }
            throw e;
        }
    }

    /** 형식 검사(DB 를 덜 부르고 칸 오류로) — 최종 판정은 트리거 */
    private ItemOpt check(TrxReq t) {
        FieldErrors e = FieldErrors.create();
        if (t == null) {
            throw fieldError("rows", "REQUIRED");
        }
        if (t.trxType() == null || !TYPES.contains(t.trxType())) {
            e.add("trxType", "CODE");
        }
        if (t.stockStatus() == null || !STATUSES.contains(t.stockStatus())) {
            e.add("stockStatus", "CODE");
        }
        if (t.entryMethod() == null || !METHODS.contains(t.entryMethod())) {
            e.add("entryMethod", "CODE");
        }
        if (t.qty() == null || t.qty().signum() == 0) {
            e.add("qty", "POSITIVE");
        } else if (t.qty().stripTrailingZeros().scale() > 6) {
            e.add("qty", "SCALE", 6);
        } else if (t.qty().abs().compareTo(new BigDecimal("1000000000000")) >= 0) {
            e.add("qty", "INT_DIGITS", 12);
        }
        if (t.occurredAt() == null) {
            e.add("occurredAt", "REQUIRED");
        }
        if (t.srcType() == null || !t.srcType().matches("^[A-Z][A-Z0-9_]{1,19}$")) {
            e.add("srcType", "CODE");
        }
        e.throwIfAny();
        ItemOpt item = mapper.findItem(t.itemId());
        if (item == null) {
            throw fieldError("itemId", "NOT_FOUND");
        }
        return item;
    }

    RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse(null);
        if ("ck_inv_stock_qty".equals(c)) {
            return new BusinessException(HbErrorCode.INSUFFICIENT_STOCK);
        }
        String[] fe = c == null ? null : CONSTRAINT_FIELD.get(c);
        return fe == null ? e : fieldError(fe[0], fe[1]);
    }

    static BusinessException fieldError(String field, String code, Object... args) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code, args).list());
    }

    /** 로그인 사용자, 없으면 트랜잭션 행위자(TenantRunner 작업·창고 PDA 단말 t{id}:{코드}) — WMS 서비스 공통 기록자 */
    public static LoginUser me() {
        return CurrentUser.get().orElseGet(() -> {
            // 요청 밖(TenantRunner 작업)에서도 쓸 수 있게 — 기록자는 트랜잭션의 행위자
            String actor = TenantContext.currentActor();
            if (actor == null) {
                throw new BusinessException(HbErrorCode.UNAUTHENTICATED);
            }
            return new LoginUser(0, TenantContext.requireCompCd(), actor.length() > 50 ? actor.substring(0, 50) : actor, actor, "ko",
                    false);
        });
    }
}
