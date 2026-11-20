package io.smarthappy.module.production;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.module.production.ReceiptDtos.CorrectForm;
import io.smarthappy.module.production.ReceiptDtos.LocOption;
import io.smarthappy.module.production.ReceiptDtos.NewReceipt;
import io.smarthappy.module.production.ReceiptDtos.ReceiptRef;
import io.smarthappy.module.production.ReceiptDtos.ReceiptView;
import io.smarthappy.module.production.ReceiptDtos.ReceiptWo;
import io.smarthappy.module.production.ReceiptDtos.ReceiptWoPage;
import io.smarthappy.module.production.ReceiptDtos.ReceiptWoSort;
import io.smarthappy.module.production.ReceiptDtos.ReceiveForm;
import io.smarthappy.module.production.ReceiptDtos.ReceiveReply;
import io.smarthappy.module.production.WoDtos.WoRow;
import io.smarthappy.module.production.erp.ErpPublisher;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.module.wms.inv.ProductionReceiptPosting;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.module.wms.link.ReceiptLocQuery;
import io.smarthappy.module.wms.link.ReceiptLocQuery.ReceiptLoc;
import lombok.RequiredArgsConstructor;

/**
 * 생산 입고 (prd-receipt, P2 7a, docs/25 4.9). 마지막 공정의 양품 LOT → 같은 공장 창고·로케이션. LOT 별 순입고 ≤ 만든 양은
 * 트리거가 LOT 행 잠금 뒤 최종 판정. 재고 원장은 WMS 가 — 입고·정정 행을 넣은 직후 같은 트랜잭션으로
 * {@link ProductionReceiptPosting#post}(docs/30 11장, docs/25 4.14). WMS 오류는 잡지 않는다(입고·재고가 같이 롤백).
 * 같은 clientReqId 는 처음 응답, 정정 = 역분개(사유, 한 번, 마감 전).
 * 위치 고르기(docs/25 4.45): WMS {@link ReceiptLocQuery}(저장과 같은 판정)로 받을 수 없는 칸에 사유·받으면 들어갈 상태를 붙이고,
 * 저장 전에도 같은 판정으로 막는다(화면을 거치지 않은 요청). 최종은 post 그대로. WMS 없음(NONE)이면 지금 목록 그대로.
 */
@Service
@RequiredArgsConstructor
public class ReceiptService {

    public static final String PROG = "prd-receipt";
    static final int LIST_LIMIT = 200;
    static final Set<String> STATUSES = Set.of("IN_PROGRESS", "DONE", "CLOSED");
    private static final Pattern REQ = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private final ReceiptMapper mapper;
    private final WoMapper woMapper;
    private final OrderService orders;
    private final BizClock clock;
    private final ErpPublisher erp;
    private final ProductionReceiptPosting stock;
    private final PolicyService policies;
    private final io.smarthappy.platform.excel.ExcelExportService excel;
    private final org.springframework.beans.factory.ObjectProvider<ReceiptLocQuery> locQuery;

    @Transactional(readOnly = true)
    public List<io.smarthappy.module.master.equip.EquipDtos.PlantRow> plants() {
        return woMapper.findPlants();
    }

    @Transactional(readOnly = true)
    public List<ReceiptWo> wos(String plantCd, String status, String keyword) {
        FieldErrors e = FieldErrors.create();
        if (plantCd == null || plantCd.isBlank()) {
            e.add("plantCd", "REQUIRED");
        }
        String st = status == null || status.isBlank() ? null : status.strip();
        if (st != null && !STATUSES.contains(st)) {
            e.add("status", "CODE");
        }
        String kw = OrderService.text(keyword, "keyword", 60, e);
        e.throwIfAny();
        return mapper.wos(plantCd.strip(), st, kw == null ? null : BomService.likeEscape(kw), LIST_LIMIT);
    }

    /** grid(UI 단계 3 ①) — 목록과 같은 조건(공장 필수) + 서버 페이징·정렬(허용 목록 {@link ReceiptWoSort}). 기본 = 목록과 같은 계획 시작 최신 */
    @Transactional(readOnly = true)
    public ReceiptWoPage woGrid(String plantCd, String status, String keyword, Integer page, Integer size, List<String> sort, Boolean total) {
        FieldErrors e = FieldErrors.create();
        if (plantCd == null || plantCd.isBlank()) {
            e.add("plantCd", "REQUIRED");
        }
        String st = status == null || status.isBlank() ? null : status.strip();
        if (st != null && !STATUSES.contains(st)) {
            e.add("status", "CODE");
        }
        String kw = OrderService.text(keyword, "keyword", 60, e);
        e.throwIfAny();
        PageQuery pq = PageQuery.parse(page, size, sort, total, ReceiptWoSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("planStart,desc"));
        String p = plantCd.strip();
        String k = kw == null ? null : BomService.likeEscape(kw);
        return new ReceiptWoPage(mapper.woPage(p, st, k, pq), pq.isWithTotal() ? mapper.countWos(p, st, k) : null);
    }

    /** 입고 대상 grid 엑셀(docs/25 4.40) — grid 와 같은 조건 검사(공장 필수)·정렬·보이는 열 */
    @Transactional(readOnly = true)
    public long woExport(ReceiptDtos.ReceiptWoExport req, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        ReceiptDtos.ReceiptWoExport r = req == null ? new ReceiptDtos.ReceiptWoExport(null, null, null, null, null) : req;
        FieldErrors e = FieldErrors.create();
        if (r.plantCd() == null || r.plantCd().isBlank()) {
            e.add("plantCd", "REQUIRED");
        }
        String st = r.status() == null || r.status().isBlank() ? null : r.status().strip();
        if (st != null && !STATUSES.contains(st)) {
            e.add("status", "CODE");
        }
        String kw = OrderService.text(r.keyword(), "keyword", 60, e);
        e.throwIfAny();
        PageQuery pq = PageQuery.sortOnly(r.sort(), ReceiptWoSort.values(), List.of("planStart,desc"));
        String p = r.plantCd().strip();
        String k = kw == null ? null : BomService.likeEscape(kw);
        java.util.List<String> keys = new java.util.ArrayList<>(List.of("plantCd"));
        if (st != null) {
            keys.add("status");
        }
        if (kw != null) {
            keys.add("keyword");
        }
        return excel.export(response, new io.smarthappy.platform.excel.ExcelExportService.ExportJob(PROG, "prog.prdReceipt",
                MesExport.named(ColumnSpec.selectExportable(GRID_COLUMNS, r.columns()), java.util.Map.of("planQty", "planQtyNum",
                        "lastGoodQty", "lastGoodQtyNum", "receivedQty", "receivedQtyNum")),
                mapper.countWos(p, st, k), keys), w -> mapper.woPage(p, st, k, pq).forEach(w));
    }

    /** grid 열(화면 입고 대상 표와 같은 칸) — 상태는 화면이 배지로(GridOptions.status) */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("woNo", "prdRcv.col.woNo", 130).withSort(),
            ColumnSpec.text("itemCd", "prdWo.col.itemCd", 120).withSort(),
            ColumnSpec.text("itemNm", "prdWo.col.itemNm", 180).withFlex().withPriority(3),
            ColumnSpec.dec("planQty", "prdRcv.col.planQty", 6, 90).withMinScale(0).withPriority(3),
            ColumnSpec.dec("lastGoodQty", "prdRcv.col.lastGood", 6, 110).withMinScale(0),
            ColumnSpec.dec("receivedQty", "prdRcv.col.received", 6, 90).withMinScale(0),
            ColumnSpec.text("unitCd", "prdWo.col.unit", 60).withPriority(3),
            ColumnSpec.code("status", "prdRcv.col.status", "WO_STATUS", 90).withSort(),   // 상세가 열려 목록이 좁아도 보이게 앞쪽
            ColumnSpec.of("planStart", "prdWo.col.planStart", ColumnType.DATE, 100).withSort().withPriority(3));

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    @Transactional(readOnly = true)
    public ReceiptView view(long woId) {
        ReceiptWo w = mapper.wo(woId);
        if (w == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return new ReceiptView(w, mapper.lots(woId), mapper.history(woId));
    }

    @Transactional(readOnly = true)
    public List<LocOption> locations(String plantCd) {
        return locations(plantCd, null, null);
    }

    /**
     * 입고 위치 선택지 — 공장의 사용 중 창고·로케이션. 지시(woId)를 주면 WMS 판정(품목·LOT)으로 칸마다 받으면 들어갈 상태·받을 수 없는 사유를 붙인다.
     * 지시의 공장이 기준(plantCd 는 지시 없을 때만). lotId 는 그 지시의 입고 대상 LOT 일 때만 판정에 넘긴다(NG 이어받기)
     */
    @Transactional(readOnly = true)
    public List<LocOption> locations(String plantCd, Long woId, Long lotId) {
        if (woId == null) {
            if (plantCd == null || plantCd.isBlank()) {
                throw OrderService.fieldError("plantCd", "REQUIRED");
            }
            return mapper.locations(plantCd.strip());
        }
        WoRow w = woMapper.findWo(woId);
        if (w == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        List<LocOption> list = mapper.locations(w.getPlantCd());
        ReceiptLocQuery q = ReceiptLocQuery.of(locQuery);
        if (q == ReceiptLocQuery.NONE) {
            return list;
        }
        Long lot = lotId != null && mapper.lots(woId).stream().anyMatch(l -> l.getLotId() == lotId) ? lotId : null;
        java.util.Map<Long, ReceiptLoc> judged = new java.util.HashMap<>();
        q.productionReceipt(w.getPlantCd(), w.getItemId(), lot).forEach(r -> judged.put(r.nodeId(), r));
        for (LocOption o : list) {
            ReceiptLoc r = judged.get(o.getNodeId());
            o.setStatus(r == null ? null : r.status());
            o.setReasonCd(r == null ? "NOT_WH" : r.reasonCd());
        }
        return list;
    }

    @Transactional
    public ReceiveReply receive(ReceiveForm in) {
        return receiveAs(in, me().loginId());
    }

    /** 요청 밖(데모 시더)에서 입고 — 입력자 = by */
    @Transactional
    public ReceiveReply receiveAs(ReceiveForm in, String by) {
        return receive(in, by, null);
    }

    /**
     * 지난 생산 입고를 그 시각으로 — 데모 이력 시더(MesDemoFlow) 전용, API 없음. 검증·재고 원장은 {@link #receiveAs} 와 같고 다른 점은 둘:
     * 발생 시각을 받음(지금보다 뒤 거부 — 입고·재고 원장 occurred_at, 기록은 지금), ERP 송신 없음(지난 사실을 옮겨 적을 뿐).
     * entry_method 는 KEY 그대로(prd_receipt CHECK 에 IMPORT 가 없다 — V055)
     */
    @Transactional
    public ReceiveReply receiveAt(ReceiveForm in, java.time.Instant at, String by) {
        if (at == null || at.isAfter(clock.instant())) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return receive(in, by, at);
    }

    private ReceiveReply receive(ReceiveForm in, String by, java.time.Instant importedAt) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String req = in.clientReqId() == null ? null : in.clientReqId().strip();
        if (req == null || !REQ.matcher(req).matches()) {
            throw OrderService.fieldError("clientReqId", "FORMAT");
        }
        ReceiptRef done = mapper.findByReq(req);
        if (done != null) {
            if (in.woId() == null || done.getWoId() != in.woId()) {
                throw new BusinessException(HbErrorCode.DUPLICATE_REQUEST);
            }
            return new ReceiveReply(done.getReceiptId(), true);
        }
        FieldErrors e = FieldErrors.create();
        if (in.woId() == null) {
            e.add("woId", "REQUIRED");
        }
        if (in.lotId() == null) {
            e.add("lotId", "REQUIRED");
        }
        if (in.nodeId() == null) {
            e.add("nodeId", "REQUIRED");
        }
        e.throwIfAny();
        WoRow w = woMapper.findWo(in.woId());
        if (w == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        BigDecimal qty = OrderService.qty(in.qty(), "qty", OrderService.scaleOf(orders.unitScales(), w.getUnitCd()), true, e);
        if (qty == null && e.isEmpty()) {
            e.add("qty", "REQUIRED");
        }
        e.throwIfAny();
        if (!"IN_PROGRESS".equals(w.getStatus()) && !"DONE".equals(w.getStatus())) {
            throw OrderService.fieldError("status", "STATE");
        }
        // 화면이 보여 준 남은 수량으로 먼저 칸 오류(동시 입고·직접 SQL 은 트리거가 최종)
        var lot = mapper.lots(in.woId()).stream().filter(l -> l.getLotId() == in.lotId()).findFirst().orElse(null);
        if (lot == null) {
            throw OrderService.fieldError("lotId", "LAST_OP");
        }
        if ("Y".equals(lot.getHoldYn())) {
            throw OrderService.fieldError("lotId", "LOT_HOLD");
        }
        if (qty.compareTo(new BigDecimal(lot.getRemainQty())) > 0) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("qty", "OVER_RECEIPT", lot.getRemainQty()).list());
        }
        if (mapper.locations(w.getPlantCd()).stream().noneMatch(n -> n.getNodeId() == in.nodeId())) {
            throw OrderService.fieldError("nodeId", "NOT_WH");
        }
        // WMS 위치 판정(화면 목록과 같은 계약) — 받을 수 없는 칸은 저장 전에 칸 오류. 최종 판정은 post 그대로
        ReceiptLocQuery q = ReceiptLocQuery.of(locQuery);
        if (q != ReceiptLocQuery.NONE) {
            ReceiptLoc loc = q.productionReceipt(w.getPlantCd(), w.getItemId(), in.lotId()).stream()
                    .filter(r -> r.nodeId() == in.nodeId()).findFirst().orElse(null);
            if (loc == null || !loc.ok()) {
                throw OrderService.fieldError("nodeId", loc == null ? "NOT_WH" : loc.reasonCd());
            }
        }
        try {
            long id = mapper.insert(new NewReceipt(w.getPlantCd(), w.getWoId(), w.getItemId(), in.lotId(), in.nodeId(), qty, w.getUnitCd(),
                    req, null, "KEY", null), importedAt == null ? clock.instant() : importedAt, by);
            // 재고 원장(WMS) — LOT 행 잠금(입고 트리거) 뒤 기간·재고 행 순서. 재고 안 둠 위치는 nodeId 칸 오류로 그대로 올라온다
            stock.post(id);
            if (importedAt == null) {
                erp.receipt(id);
            }
            return new ReceiveReply(id, false);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(HbErrorCode.DUPLICATE_REQUEST);
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /** 정정 = 역분개(같은 LOT·위치, 수량 반대, 사유). 한 번만, 마감 전 */
    @Transactional
    public long correct(long receiptId, CorrectForm in) {
        LoginUser me = me();
        FieldErrors e = FieldErrors.create();
        String reason = in == null ? null : OrderService.text(in.reason(), "reason", 500, e);
        if (reason == null && e.isEmpty()) {
            e.add("reason", "REQUIRED");
        }
        e.throwIfAny();
        ReceiptRef r = mapper.find(receiptId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (r.getReversalOfId() != null || r.isReversed()) {
            throw OrderService.fieldError("status", "STATE");
        }
        WoRow w = woMapper.findWo(r.getWoId());
        if (!"IN_PROGRESS".equals(w.getStatus()) && !"DONE".equals(w.getStatus())) {
            throw OrderService.fieldError("status", "STATE");
        }
        try {
            long id = mapper.insert(new NewReceipt(w.getPlantCd(), w.getWoId(), w.getItemId(), r.getLotId(), r.getNodeId(), r.getQty().negate(),
                    w.getUnitCd(), null, reason, "CORRECT", receiptId), clock.instant(), me.loginId());
            postCorrection(id);
            erp.receipt(id);   // 정정도 ERP 로 — 음수 전표(D19 표)
            return id;
        } catch (DuplicateKeyException ex) {
            throw OrderService.fieldError("status", "STATE");
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /** 정정의 재고 역분개 — 그 사이 재고를 옮기거나 써서 모자라면 입고 화면에 맞는 이유(STOCK_MOVED)로 알린다 */
    private void postCorrection(long correctionId) {
        try {
            stock.post(correctionId);
        } catch (BusinessException ex) {
            if (ex.code() == HbErrorCode.INSUFFICIENT_STOCK) {
                throw new BusinessException(HbErrorCode.INSUFFICIENT_STOCK)
                        .withFieldErrors(FieldErrors.create().add("status", "STOCK_MOVED").list());
            }
            throw ex;
        }
    }

    static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "ck_prd_receipt_over" -> new BusinessException(HbErrorCode.CONFLICT);
            case "ck_prd_receipt_hold" -> OrderService.fieldError("lotId", "LOT_HOLD");
            case "ck_prd_receipt_last_op" -> OrderService.fieldError("lotId", "LAST_OP");
            case "ck_prd_receipt_node" -> OrderService.fieldError("nodeId", "NOT_WH");
            case "ck_prd_receipt_wo_state" -> OrderService.fieldError("status", "STATE");
            default -> e;
        };
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
