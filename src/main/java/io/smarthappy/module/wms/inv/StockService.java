package io.smarthappy.module.wms.inv;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.wms.inv.InvDtos.AdjustForm;
import io.smarthappy.module.wms.inv.InvDtos.ItemOpt;
import io.smarthappy.module.wms.inv.InvDtos.Mismatch;
import io.smarthappy.module.wms.inv.InvDtos.MoveForm;
import io.smarthappy.module.wms.inv.InvDtos.NodeOpt;
import io.smarthappy.module.wms.inv.InvDtos.PostResult;
import io.smarthappy.module.wms.inv.InvDtos.ReconcileRow;
import io.smarthappy.module.wms.inv.InvDtos.ScanForm;
import io.smarthappy.module.wms.inv.InvDtos.ScanHit;
import io.smarthappy.module.wms.inv.InvDtos.StockQuery;
import io.smarthappy.module.wms.inv.InvDtos.StockRow;
import io.smarthappy.module.wms.inv.InvDtos.StockSearch;
import io.smarthappy.module.wms.inv.InvDtos.StockExport;
import io.smarthappy.module.wms.inv.InvDtos.StockSort;
import io.smarthappy.module.wms.inv.InvDtos.TrxQuery;
import io.smarthappy.module.wms.inv.InvDtos.TrxExport;
import io.smarthappy.module.wms.inv.InvDtos.TrxReq;
import io.smarthappy.module.wms.inv.InvDtos.TrxRow;
import io.smarthappy.module.wms.inv.InvDtos.TrxSearch;
import io.smarthappy.module.wms.inv.InvDtos.TrxSort;
import io.smarthappy.module.wms.scan.ScanKeyResolver;
import io.smarthappy.module.wms.scan.ScanKeyResolver.BareAs;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.NotifyLogHandler;
import io.smarthappy.platform.messaging.OutboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * 현재고·수불 원장·재고 이동/조정 화면(wms-stock·wms-trx·wms-move) — P4 W3, docs/30 4.3.
 * 게시는 {@link InventoryService} 에 맡기고, 여기서는 화면 입력을 원장 행으로 바꾼다(발생 시각 = 공장 현지 시각, 미래 거부).
 */
@Service
@RequiredArgsConstructor
public class StockService {

    static final String PROG_STOCK = "wms-stock";
    static final String PROG_TRX = "wms-trx";
    static final String PROG_MOVE = "wms-move";
    static final String ADJ_REASON = "WMS_ADJ_REASON";
    static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);
    static final int MAX_TRX_DAYS = 92;
    static final List<String> STOCK_SORT = List.of("itemCd,asc", "path,asc");
    static final List<String> TRX_SORT = List.of("occurredAt,desc");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final List<ColumnSpec> STOCK_COLUMNS = List.of(
            ColumnSpec.text("itemCd", "wmsStock.col.itemCd", 130).withSort(),
            ColumnSpec.text("itemNm", "wmsStock.col.itemNm", 180).withFlex().withMinWidth(120),
            ColumnSpec.text("path", "wmsStock.col.path", 200).withSort(),
            ColumnSpec.text("lotNo", "wmsStock.col.lotNo", 130).withSort(),
            ColumnSpec.code("stockStatus", "wmsStock.col.stockStatus", "STOCK_STATUS", 90),
            ColumnSpec.dec("qty", "wmsStock.col.qty", 6, 110).withMinScale(0).withSort(),
            ColumnSpec.text("unitCd", "wmsStock.col.unitCd", 60),
            ColumnSpec.text("spec", "wmsStock.col.spec", 140),
            ColumnSpec.of("updatedAt", "wmsStock.col.updatedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort());

    static final List<ColumnSpec> TRX_COLUMNS = List.of(
            ColumnSpec.of("trxId", "wmsTrx.col.trxId", ColumnSpec.ColumnType.INT, 80),
            ColumnSpec.of("occurredAt", "wmsTrx.col.occurredAt", ColumnSpec.ColumnType.DATETIME, 140).withSort(),
            ColumnSpec.code("trxType", "wmsTrx.col.trxType", "INV_TRX_TYPE", 100),
            ColumnSpec.text("itemCd", "wmsTrx.col.itemCd", 130),
            ColumnSpec.text("itemNm", "wmsTrx.col.itemNm", 160).withFlex().withMinWidth(100),
            ColumnSpec.text("path", "wmsTrx.col.path", 180),
            ColumnSpec.text("lotNo", "wmsTrx.col.lotNo", 130),
            ColumnSpec.code("stockStatus", "wmsTrx.col.stockStatus", "STOCK_STATUS", 90),
            ColumnSpec.dec("qty", "wmsTrx.col.qty", 6, 110).withMinScale(0),
            ColumnSpec.text("unitCd", "wmsTrx.col.unitCd", 60),
            ColumnSpec.code("reasonCd", "wmsTrx.col.reasonCd", ADJ_REASON, 90),
            ColumnSpec.code("entryMethod", "wmsTrx.col.entryMethod", "ENTRY_METHOD", 80),
            ColumnSpec.text("recordedBy", "wmsTrx.col.recordedBy", 100),
            ColumnSpec.text("performedByNm", "wmsTrx.col.performedBy", 100),
            ColumnSpec.of("reversalOfId", "wmsTrx.col.reversalOfId", ColumnSpec.ColumnType.INT, 80),
            ColumnSpec.of("reversedById", "wmsTrx.col.reversedById", ColumnSpec.ColumnType.INT, 80),
            ColumnSpec.of("recordedAt", "wmsTrx.col.recordedAt", ColumnSpec.ColumnType.DATETIME, 140),
            ColumnSpec.text("remark", "wmsTrx.col.remark", 200));

    private final InvMapper mapper;
    private final InventoryService inventory;
    private final PolicyService policy;
    private final BizClock clock;
    private final DateRanges ranges;
    private final ExcelExportService excel;
    private final OutboxService outbox;
    private final MessageSource messages;
    private final ReceiptPostingMapper receipts;
    private final QualityStockMapper quality;
    private final PutawayCheck putaway;

    // ================================================================== 선택지·스캔

    @Transactional(readOnly = true)
    public List<NodeOpt> nodes(String plantCd) {
        return mapper.findStorageNodes(plant(plantCd));
    }

    @Transactional(readOnly = true)
    public ItemOpt item(String itemCd) {
        if (itemCd == null || itemCd.isBlank() || itemCd.length() > 40) {
            throw fieldError("itemCd", "REQUIRED");
        }
        ItemOpt it = mapper.findItemByCode(itemCd.strip().toUpperCase(Locale.ROOT));
        if (it == null) {
            throw fieldError("itemCd", "NO_SUCH_CODE");
        }
        return it;
    }

    /** 스캔 한 번으로 위치 또는 품목 — 판별은 스캔 키 규칙(W1 순수 함수), 존재·사용 여부만 여기서 */
    @Transactional(readOnly = true)
    public ScanHit scan(ScanForm f) {
        String plant = plant(f == null ? null : f.plantCd());
        var r = ScanKeyResolver.resolve(f.text(), new ScanKeyResolver.Context(plant,
                BareAs.valueOf(policy.value("WMS_SCAN_BARE_AS")), clock.today()));
        Locale loc = LocaleContextHolder.getLocale();
        if (!r.ok()) {
            return new ScanHit("UNKNOWN", null, null, null, null, null, null, null, false,
                    messages.getMessage("wmsScan.reason." + r.reason().name(), new Object[] {r.detail() == null ? "" : r.detail()}, loc));
        }
        if (r.type() == ScanKeyResolver.Type.LOC) {
            NodeOpt n = mapper.findNodeByCode(r.plantCd(), r.key());
            boolean ok = n != null && ("WH".equals(n.getNodeType()) || "LOC".equals(n.getNodeType())) && "Y".equals(n.getStockYn());
            return new ScanHit("LOC", n == null ? null : n.getNodeId(), r.key(), n == null ? null : n.getPath(), null, null, null, null,
                    ok, messages.getMessage(ok ? "wmsScan.lookup.locOk" : "wmsScan.lookup.locNotFound",
                            new Object[] {n == null ? r.key() : n.getPath()}, loc));
        }
        if (r.type() == ScanKeyResolver.Type.ITEM) {
            ItemOpt it = mapper.findItemByCode(r.key());
            boolean ok = it != null && "Y".equals(it.getUseYn());
            return new ScanHit("ITEM", null, null, null, it == null ? null : it.getItemId(), r.key(), it == null ? null : it.getItemNm(),
                    it == null ? null : it.getUnitCd(), ok,
                    messages.getMessage(ok ? "wmsScan.lookup.itemOk" : "wmsScan.lookup.itemNotFound", new Object[] {r.key()}, loc));
        }
        if (r.type() == ScanKeyResolver.Type.LOT) {
            InvDtos.LotOpt l = mapper.findLotByNo(r.key());
            boolean ok = l != null;
            return new ScanHit("LOT", null, null, null, ok ? l.getItemId() : null, ok ? l.getItemCd() : null, ok ? l.getItemNm() : null,
                    ok ? l.getUnitCd() : null, ok, messages.getMessage(!ok ? "wmsScan.lookup.lotNotFound"
                            : "Y".equals(l.getHoldYn()) ? "wmsScan.lookup.lotHold" : "wmsScan.lookup.lotOk", new Object[] {r.key()}, loc),
                    ok ? l.getLotId() : null, ok ? l.getLotNo() : null);
        }
        return new ScanHit(r.type().name(), null, null, null, null, null, null, null, false,
                messages.getMessage("wmsScan.lookup.later", new Object[] {r.key()}, loc));
    }

    // ================================================================== 조정·이동·역분개

    @Transactional
    public PostResult adjust(AdjustForm f) {
        if (f == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String plant = plant(f.plantCd());
        FieldErrors e = FieldErrors.create();
        NodeOpt node = node(f.nodeId(), plant, "nodeId", e);
        ItemOpt item = itemById(f.itemId(), e);
        String status = status(f.stockStatus(), "stockStatus", e);
        BigDecimal delta = f.delta();
        if (delta == null || delta.signum() == 0) {
            e.add("delta", "NOT_ZERO");
        }
        String reason = f.reasonCd() == null || f.reasonCd().isBlank() ? null : f.reasonCd().strip();
        if (reason == null) {
            e.add("reasonCd", "REQUIRED");
        } else if (!mapper.codeUsable(ADJ_REASON, reason)) {
            e.add("reasonCd", "CODE");
        }
        String remark = remark(f.remark(), e);
        OffsetDateTime at = occurredAt(f.occurredAt(), plant, e);
        String method = method(f.entryMethod(), e);
        Long lot = lot(f.lotNo(), item, delta != null && delta.signum() < 0, e);
        e.throwIfAny();
        if (delta.signum() > 0) {
            mixCheck(node, item, "nodeId");
        }
        String type = delta.signum() > 0 ? "ADJ_IN" : "ADJ_OUT";
        return inventory.postChecked(List.of(new TrxReq(type, item.getItemId(), node.getNodeId(), status, delta, null, "MANUAL", null, reason,
                remark, at, method, f.performedBy(), lot)), f.clientReqId());
    }

    @Transactional
    public PostResult move(MoveForm f) {
        if (f == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String plant = plant(f.plantCd());
        FieldErrors e = FieldErrors.create();
        ItemOpt item = itemById(f.itemId(), e);
        NodeOpt from = node(f.fromNodeId(), plant, "fromNodeId", e);
        NodeOpt to = node(f.toNodeId(), plant, "toNodeId", e);
        String fromStatus = status(f.fromStatus(), "fromStatus", e);
        String toStatus = status(f.toStatus(), "toStatus", e);
        BigDecimal qty = f.qty();
        if (qty == null || qty.signum() <= 0) {
            e.add("qty", "POSITIVE");
        }
        String remark = remark(f.remark(), e);
        OffsetDateTime at = occurredAt(f.occurredAt(), plant, e);
        String method = method(f.entryMethod(), e);
        if (from != null && to != null && fromStatus != null && from.getNodeId() == to.getNodeId() && fromStatus.equals(toStatus)) {
            e.add("toNodeId", "SAME_PLACE");
        }
        // 위치를 바꾸는 이동만 "꺼냄" — 같은 위치 상태 바꿈은 보류 LOT 도 된다(HOLD 로 격리)
        boolean leaves = from == null || to == null || from.getNodeId() != to.getNodeId();
        Long lot = lot(f.lotNo(), item, leaves, e);
        e.throwIfAny();
        mixCheck(to, item, "toNodeId");
        boolean statusOnly = from.getNodeId() == to.getNodeId();
        String kind = statusOnly ? "STATUS" : "MOVE";
        // 적치 검사(W-12) — 위치를 바꾸는 이동만(같은 위치 상태 바꿈은 둘 곳이 그대로). BLOCK 이면 여기서 409
        String warn = statusOnly ? null : putaway.check(plant, to.getNodeId(), item.getItemId());
        UUID grp = UUID.randomUUID();
        return inventory.postChecked(List.of(
                new TrxReq(kind + "_OUT", item.getItemId(), from.getNodeId(), fromStatus, qty.negate(), grp, "MANUAL", null, null, remark,
                        at, method, f.performedBy(), lot),
                new TrxReq(kind + "_IN", item.getItemId(), to.getNodeId(), toStatus, qty, grp, "MANUAL", null, null, remark, at, method,
                        f.performedBy(), lot)), f.clientReqId()).withWarnings(PutawayCheck.warnings(warn));
    }

    @Transactional
    public List<Long> reverse(long trxId, String reason) {
        return inventory.reverse(trxId, reason);
    }

    void mixCheck(NodeOpt node, ItemOpt item, String field) {
        if ("N".equals(mapper.mixItemYn(node.getNodeId())) && mapper.otherItemAt(node.getNodeId(), item.getItemId())) {
            throw fieldError(field, "MIX_ITEM");
        }
    }

    // ================================================================== 조회

    public record Page<T>(List<T> list, Long total) {
    }

    @Transactional(readOnly = true)
    public Page<StockRow> stock(StockQuery q, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, StockSort.values(), policy.intValue(Policies.GRID_PAGE_SIZE_MAX),
                STOCK_SORT);
        StockSearch s = stockSearch(q, pq);
        return new Page<>(mapper.findStock(s), pq.isWithTotal() ? mapper.countStock(s) : null);
    }

    /** 현재고 한 키의 최근 원장 20건 */
    @Transactional(readOnly = true)
    public List<TrxRow> stockHistory(long stockId) {
        StockRow r = mapper.findStockRow(stockId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return mapper.findKeyHistory(r.getItemId(), r.getNodeId(), r.getStockStatus(), r.getLotId(), 20);
    }

    @Transactional(readOnly = true)
    public Page<TrxRow> trxs(TrxQuery q, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, TrxSort.values(), policy.intValue(Policies.GRID_PAGE_SIZE_MAX), TRX_SORT);
        TrxSearch s = trxSearch(q, pq);
        mapper.customPlan();                                      // 깊은 쪽 일반 계획 방지(docs/30 5.1)
        return new Page<>(mapper.findTrxs(s), pq.isWithTotal() ? mapper.countTrxs(s) : null);
    }

    @Transactional(readOnly = true)
    public TrxRow trx(long id) {
        TrxRow t = mapper.findTrx(id);
        if (t == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return t;
    }

    public List<ColumnSpec> stockColumns() {
        return STOCK_COLUMNS;
    }

    public List<ColumnSpec> trxColumns() {
        return TRX_COLUMNS;
    }

    @Transactional(readOnly = true)
    public long exportStock(StockExport req, HttpServletResponse response) throws IOException {
        StockSearch s = stockSearch(req == null ? null : req.query(),
                PageQuery.sortOnly(req == null ? null : req.sort(), StockSort.values(), STOCK_SORT));
        List<ColumnSpec> cols = ColumnSpec.selectExportable(STOCK_COLUMNS, req == null ? null : req.columns());
        return excel.export(response, new ExportJob(PROG_STOCK, "prog.wmsStock", cols, mapper.countStock(s), List.of("plantCd")),
                w -> mapper.cursorStock(s).forEach(w));
    }

    @Transactional(readOnly = true)
    public long exportTrx(TrxExport req, HttpServletResponse response) throws IOException {
        TrxSearch s = trxSearch(req == null ? null : req.query(),
                PageQuery.sortOnly(req == null ? null : req.sort(), TrxSort.values(), TRX_SORT));
        List<ColumnSpec> cols = ColumnSpec.selectExportable(TRX_COLUMNS, req == null ? null : req.columns());
        return excel.export(response, new ExportJob(PROG_TRX, "prog.wmsTrx", cols, mapper.countTrxs(s), List.of("plantCd", "from", "to")),
                w -> mapper.cursorTrxs(s).forEach(w));
    }

    private StockSearch stockSearch(StockQuery q, PageQuery pq) {
        if (q == null) {
            throw fieldError("plantCd", "REQUIRED");
        }
        String plant = plant(q.plantCd());
        String status = q.stockStatus() == null || q.stockStatus().isBlank() ? null : q.stockStatus();
        if (status != null && !InventoryService.STATUSES.contains(status)) {
            throw fieldError("stockStatus", "CODE");
        }
        return new StockSearch(plant, q.nodeId(), !Boolean.FALSE.equals(q.withChildren()), like(q.itemCd(), true, 40),
                like(q.itemNm(), false, 200), status, !Boolean.FALSE.equals(q.hideZero()), pq, likeRaw(q.lotNo(), 60));
    }

    private TrxSearch trxSearch(TrxQuery q, PageQuery pq) {
        if (q == null || q.from() == null || q.to() == null) {
            throw fieldError("from", "REQUIRED");
        }
        ranges.check(q.from(), q.to(), MAX_TRX_DAYS);
        String plant = plant(q.plantCd());
        String type = q.trxType() == null || q.trxType().isBlank() ? null : q.trxType();
        if (type != null && !InventoryService.TYPES.contains(type)) {
            throw fieldError("trxType", "CODE");
        }
        ZoneId zone = clock.zone(plant);
        return new TrxSearch(q.from().atStartOfDay(zone).toOffsetDateTime(), q.to().plusDays(1).atStartOfDay(zone).toOffsetDateTime(),
                plant, q.itemId(), like(q.itemCd(), true, 40), q.nodeId(), type, pq);
    }

    // ================================================================== 대사

    /** 원장 합 = 현재고 확인. 불일치는 결과 행 + 알림(아웃박스 NOTIFY) — 자동 보정 없음(01 3.1) */
    @Transactional
    public ReconcileRow reconcile() {
        int keys = mapper.countStockKeys();
        int bad = mapper.countMismatches();
        List<Mismatch> detail = bad == 0 ? List.of() : mapper.findMismatches(100);
        // 생산 입고가 원장에 빠짐(이관을 한 번 끝낸 회사만 — 그 전에는 null). MES 호출이 빠진 경로를 찾는다(docs/30 11.4)
        Integer missing = receipts.backfillDone() ? receipts.countMissing() : null;
        // 자재 입고 원장 합 > 입고 LOT 수량(B4a) — 화면 경로로는 생기지 않는다(직접 SQL·버그 검출)
        int rcvOver = mapper.countRcvMatOver();
        // SRM 발주 행 입고 수량(투영) ≠ 원장 RCV_PO 합(B4b) — 게이트를 거치지 않은 변경 검출
        int poBad = mapper.countRcvPoMismatch();
        // 품질 상태 연동(B3, docs/30 12.15.12) — 판정이 끝났는데 남은 QC LOT(스위치 Y 공장만) · 무효 검사인데 살아 있는 판정 원장
        List<String> qcPlants = quality.qcPlants().stream().filter(p -> "Y".equals(policy.value(QualityStockPosting.POLICY, p))).toList();
        int qcJudged = qcPlants.isEmpty() ? 0 : quality.countQcJudged(qcPlants);
        int voidLive = quality.countVoidLive();
        String actor = TenantContext.currentActor();
        long id = mapper.insertReconcile(keys, bad, missing, rcvOver, poBad, qcJudged, voidLive, JSON.writeValueAsString(detail),
                actor == null ? "SYSTEM" : actor);
        if (bad > 0 || (missing != null && missing > 0) || rcvOver > 0 || poBad > 0 || qcJudged > 0 || voidLive > 0) {
            outbox.publish(NotifyLogHandler.TOPIC, "INV_RECONCILE:" + id, Map.of("kind", "INV_RECONCILE", "runId", id, "mismatches", bad,
                    "missingReceipts", missing == null ? 0 : missing, "rcvMatOver", rcvOver, "rcvPoMismatch", poBad,
                    "qcJudged", qcJudged, "qmsVoidLive", voidLive));
        }
        return mapper.lastReconcile();
    }

    @Transactional(readOnly = true)
    public ReconcileRow lastReconcile() {
        return mapper.lastReconcile();
    }

    // ================================================================== 공통

    String plant(String plantCd) {
        if (plantCd == null || plantCd.isBlank()) {
            throw fieldError("plantCd", "REQUIRED");
        }
        String p = plantCd.strip().toUpperCase(Locale.ROOT);
        if (p.length() > 20 || !mapper.plantExists(p)) {
            throw fieldError("plantCd", "NOT_FOUND");
        }
        return p;
    }

    NodeOpt node(Long id, String plant, String field, FieldErrors e) {
        if (id == null) {
            e.add(field, "REQUIRED");
            return null;
        }
        NodeOpt n = mapper.findNode(id, plant);
        if (n == null) {
            e.add(field, "NOT_FOUND");          // 다른 공장·다른 회사·사용 중지·구역 노드 모두 "없음"
        } else if (!"Y".equals(n.getStockYn())) {
            e.add(field, "STOCK_NOT_ALLOWED");
        }
        return n;
    }

    ItemOpt itemById(Long id, FieldErrors e) {
        if (id == null) {
            e.add("itemId", "REQUIRED");
            return null;
        }
        ItemOpt it = mapper.findItem(id);
        if (it == null) {
            e.add("itemId", "NOT_FOUND");
        } else if (!"Y".equals(it.getUseYn())) {
            e.add("itemId", "INACTIVE");
        }
        return it;
    }

    /**
     * LOT 번호 → lot_id. LOT 관리 품목은 필수·같은 품목, 비관리 품목은 비워야 한다(B2). 꺼내는 쪽(출고·다른 위치로 이동)이면 보류 LOT 거부 —
     * 최종 판정은 원장 트리거(LOT 행 FOR SHARE)
     */
    private Long lot(String lotNo, ItemOpt item, boolean outgoing, FieldErrors e) {
        String no = lotNo == null || lotNo.isBlank() ? null : lotNo.strip();
        if (item == null) {
            return null;
        }
        if (!"Y".equals(item.getLotCtrlYn())) {
            if (no != null) {
                e.add("lotNo", "LOT_NOT_CONTROLLED");
            }
            return null;
        }
        if (no == null) {
            e.add("lotNo", "LOT_REQUIRED");
            return null;
        }
        InvDtos.LotOpt l = no.length() > 60 ? null : mapper.findLotByNo(no);
        if (l == null) {
            e.add("lotNo", "NO_SUCH_CODE");
            return null;
        }
        if (l.getItemId() != item.getItemId()) {
            e.add("lotNo", "LOT_MISMATCH");
            return null;
        }
        if (outgoing && "Y".equals(l.getHoldYn())) {
            e.add("lotNo", "LOT_HOLD");
        }
        return l.getLotId();
    }

    static String status(String s, String field, FieldErrors e) {
        if (s == null || !InventoryService.STATUSES.contains(s)) {
            e.add(field, s == null ? "REQUIRED" : "CODE");
            return null;
        }
        return s;
    }

    static String method(String m, FieldErrors e) {
        String v = m == null || m.isBlank() ? "KEY" : m;
        if (!"KEY".equals(v) && !"SCAN".equals(v)) {
            e.add("entryMethod", "CODE");      // 화면 입력은 손 입력·스캔만(AUTO·IMPORT 는 다른 경로)
        }
        return v;
    }

    static String remark(String r, FieldErrors e) {
        if (r == null || r.isBlank()) {
            return null;
        }
        String s = r.strip();
        if (s.length() > 500) {
            e.add("remark", "TOO_LONG", 500);
        }
        return s;
    }

    /** 공장 현지 시각(yyyy-MM-ddTHH:mm) → 시각. 비우면 지금, DST 없는 시각·미래(허용 5분 밖)는 칸 오류 */
    OffsetDateTime occurredAt(String local, String plant, FieldErrors e) {
        Instant now = clock.instant();
        if (local == null || local.isBlank()) {
            return OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        }
        LocalDateTime ldt;
        try {
            ldt = LocalDateTime.parse(local.strip());
        } catch (DateTimeParseException ex) {
            e.add("occurredAt", "FORMAT");
            return null;
        }
        Instant at = BizClock.toInstant(ldt, clock.zone(plant));
        if (at == null) {
            e.add("occurredAt", "DST_GAP");
            return null;
        }
        if (at.isAfter(now.plus(FUTURE_TOLERANCE))) {
            e.add("occurredAt", "FUTURE");
            return null;
        }
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    /** LOT 번호 앞 일치(대소문자 그대로 — LOT 번호는 라벨 글자) */
    private static String likeRaw(String v, int max) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String s = v.strip();
        if (s.length() > max) {
            throw fieldError("lotNo", "TOO_LONG", max);
        }
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static String like(String v, boolean prefix, int max) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String s = v.strip();
        if (s.length() > max) {
            throw fieldError("itemCd", "TOO_LONG", max);
        }
        String esc = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return prefix ? esc.toUpperCase(Locale.ROOT) + "%" : "%" + esc + "%";
    }

    static BusinessException fieldError(String field, String code, Object... args) {
        return InventoryService.fieldError(field, code, args);
    }
}
