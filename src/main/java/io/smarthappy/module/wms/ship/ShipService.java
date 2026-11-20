package io.smarthappy.module.wms.ship;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.plm.link.EcoTrace;
import io.smarthappy.module.plm.link.EcoTrace.ShipCheck;
import io.smarthappy.module.sales.link.OrderLineQuery;
import io.smarthappy.module.wms.link.ShipConfirmCheck;
import io.smarthappy.module.wms.link.StockQuery;
import io.smarthappy.module.sales.link.OrderLineQuery.OrderLineView;
import io.smarthappy.module.sales.link.OrderShipGate;
import io.smarthappy.module.sales.link.OrderShipGate.ShipQty;
import io.smarthappy.module.wms.alloc.AllocDtos.PickView;
import io.smarthappy.module.wms.alloc.AllocDtos.PreviewForm;
import io.smarthappy.module.wms.alloc.AllocService;
import io.smarthappy.module.wms.inv.InvDtos.TrxReq;
import io.smarthappy.module.wms.inv.InventoryService;
import io.smarthappy.module.wms.inv.InvDtos.ScanForm;
import io.smarthappy.module.wms.inv.InvDtos.ScanHit;
import io.smarthappy.module.wms.inv.StockService;
import io.smarthappy.module.wms.label.LabelDtos.PrintItem;
import io.smarthappy.module.wms.label.LabelMapper;
import io.smarthappy.module.wms.label.LabelPrintService;
import io.smarthappy.module.wms.scan.ScanKeyResolver;
import io.smarthappy.module.wms.shipnote.ShipNoteService;
import io.smarthappy.module.wms.ship.ShipDtos.CancelForm;
import io.smarthappy.module.wms.ship.ShipDtos.ConfirmForm;
import io.smarthappy.module.wms.ship.ShipDtos.CreateForm;
import io.smarthappy.module.wms.ship.ShipDtos.LineForm;
import io.smarthappy.module.wms.ship.ShipDtos.NodeOpt;
import io.smarthappy.module.wms.ship.ShipDtos.OrderLineOpt;
import io.smarthappy.module.wms.ship.ShipDtos.PickForm;
import io.smarthappy.module.wms.ship.ShipDtos.PickReq;
import io.smarthappy.module.wms.ship.ShipDtos.PickRow;
import io.smarthappy.module.wms.ship.ShipDtos.ShipHead;
import io.smarthappy.module.wms.ship.ShipDtos.ShipLine;
import io.smarthappy.module.wms.ship.ShipDtos.ShipTrx;
import io.smarthappy.module.wms.ship.ShipDtos.ShipView;
import io.smarthappy.module.wms.ship.ShipDtos.StockPick;
import io.smarthappy.module.wms.ship.ShipDtos.UnpickForm;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 출하 (P4 2부 B6a, docs/30 12.5·12.7) — 출하 지시(수주 행) → 피킹(출발 칸 → 출하 대기 STAGING 이동) → 출하 확정(SHIP 원장).
 * <ul>
 * <li>출하 확정 한 트랜잭션: 지시 머리 FOR UPDATE → 영업 게이트 {@link OrderShipGate}(수주 머리 → 행 잠금, 출하 수량 투영·자동 마감)
 *     → 피킹 LOT lot_id 순 FOR SHARE → PLM {@link EcoTrace#shipChecks}(읽기만, 정책 Y 면 block 409) → 원장 SHIP −(칸 순서) → 라벨·ERP 아웃박스</li>
 * <li>출하 취소: 지시 머리 → 영업 게이트 음수 → SHIP 역분개(한 번에, 칸 순서) — 피킹은 STAGING 으로 돌아와 PICKED</li>
 * <li>멱등: 피킹·확정·출하 취소는 요청 id(sys_inbox) — 게이트에는 멱등이 없으므로 WMS 가 한 번만 부른다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ShipService {

    static final String PROG = "wms-ship";
    static final String RULE = "WMS_SHIP";
    static final String POLICY_BLOCK = "SHIP_BLOCK_UNAPPROVED_CHANGE";
    static final String POLICY_PALLET = "WMS_SHIP_PALLET_REQUIRED";
    static final String POLICY_GS1 = "WMS_GS1_COMPANY_PREFIX";
    static final String RULE_PALLET = "WMS_PALLET";
    static final String RULE_SSCC = "WMS_SSCC";
    static final String SRC = "SHIP_PICK";
    static final String INBOX_PICK = "WMS_SHIP_PICK";
    static final String INBOX_CONFIRM = "WMS_SHIP_CONFIRM";
    static final String INBOX_UNDO = "WMS_SHIP_UNDO";
    static final int MAX_LINES = 200;
    static final int MAX_PICKS = 500;
    static final int MAX_COPIES = 20;

    private final ShipMapper mapper;
    private final InventoryService inventory;
    private final AllocService alloc;
    private final StockService stock;
    private final MessageSource messages;
    private final NumberingService numbering;
    private final PolicyService policy;
    private final InboxService inbox;
    private final LabelPrintService labels;
    private final LabelMapper labelMapper;
    private final ShipErpPublisher erp;
    private final BizClock clock;
    private final ObjectProvider<OrderShipGate> shipGate;
    private final ObjectProvider<OrderLineQuery> orderLines;
    private final ObjectProvider<EcoTrace> ecoTrace;
    private final ObjectProvider<StockQuery> stockQuery;
    private final ShipNoteService notes;
    /** 출하 조건 확장 지점(docs/72 2.1). 없으면 그대로 */
    private final ObjectProvider<ShipConfirmCheck> confirmChecks;

    /** 출하 확정 결과 — warnings = PLM 임시 승인(INTERIM) 등 막지 않은 경고 */
    public record ConfirmResult(ShipView view, List<String> warnings, int labelJobs, boolean replay) {
    }

    // ================================================================== 조회

    @Transactional(readOnly = true)
    public List<ShipHead> list(String plantCd, String status, String from, String to) {
        String plant = plant(plantCd);
        String st = status == null || status.isBlank() ? null : status.strip();
        return mapper.findShips(plant, st, date(from, "from", false), date(to, "to", false), 200);
    }

    /** 그리드 열(UI 단계 3 ①) — 정렬 표시 = ShipSort 와 같은 열 */
    static final List<ColumnSpec> SHIP_COLUMNS = List.of(
            ColumnSpec.text("shipNo", "wmsShip.col.shipNo", 140).withSort(),
            ColumnSpec.text("custCd", "wmsShip.col.custCd", 110).withSort(),
            ColumnSpec.text("custNm", "wmsShip.col.cust", 160).withFlex().withMinWidth(110),
            ColumnSpec.of("planDate", "wmsShip.col.planDate", ColumnSpec.ColumnType.DATE, 110).withSort(),
            ColumnSpec.code("status", "wmsShip.col.status", "WMS_SHIP_STATUS", 100).withSort(),
            ColumnSpec.of("lineCount", "wmsShip.col.lines", ColumnSpec.ColumnType.INT, 60),
            ColumnSpec.of("noteCnt", "wmsShip.col.notes", ColumnSpec.ColumnType.INT, 70),
            ColumnSpec.of("noteOpenCnt", "wmsShip.col.noteOpen", ColumnSpec.ColumnType.INT, 80),
            ColumnSpec.text("stagePath", "wmsShip.stage", 160).withPriority(3),
            ColumnSpec.text("carrierNm", "wmsShip.col.carrier", 120).withPriority(3),
            ColumnSpec.text("trackingNo", "wmsShip.col.trackingNo", 140).withPriority(3),
            ColumnSpec.of("createdAt", "wmsShip.col.createdAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(3));
    static final List<String> SHIP_SORT = List.of("planDate,desc");

    /** 출하 목록 그리드 — 조건은 list 와 같고 페이징·정렬(허용 열만)·건수. 기존 list 는 그대로(화면 밖 호출 호환) */
    @Transactional(readOnly = true)
    public Page<ShipHead> grid(String plantCd, String status, String from, String to, Integer page, Integer size, List<String> sort,
            Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, ShipDtos.ShipSort.values(), policy.intValue(Policies.GRID_PAGE_SIZE_MAX),
                SHIP_SORT);
        String plant = plant(plantCd);
        String st = status == null || status.isBlank() ? null : status.strip();
        LocalDate f = date(from, "from", false);
        LocalDate t = date(to, "to", false);
        return new Page<>(mapper.findShipPage(plant, st, f, t, pq), pq.isWithTotal() ? mapper.countShips(plant, st, f, t) : null);
    }

    public List<ColumnSpec> columns() {
        return SHIP_COLUMNS;
    }

    /** 그리드 한 쪽 */
    public record Page<T>(List<T> list, Long total) {
    }

    @Transactional(readOnly = true)
    public ShipView view(long id) {
        ShipHead h = mapper.findShip(id);
        if (h == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        List<ShipLine> ls = lines(id);
        if ("DRAFT".equals(h.getStatus()) || "PICKING".equals(h.getStatus())) {
            reserve(h.getPlantCd(), ls);
        }
        return new ShipView(h, ls, mapper.findPicks(id), mapper.findPallets(id), notes.forShip(id));
    }

    /**
     * 출하 예약 경고(docs/53 (가) 계산형 소프트 예약) — 줄마다 공장 가용·다른 열린 지시 예약·순 가용·모자람. 다른 지시 예약 = 전체 예약 − 이 지시의 미피킹
     * (같은 SQL 조각 AvailSql.openShipReserve 로 센 값이라 자기 몫이 정확히 빠진다). 막지 않는다
     */
    private void reserve(String plantCd, List<ShipLine> ls) {
        if (ls.isEmpty()) {
            return;
        }
        Map<Long, BigDecimal> self = new LinkedHashMap<>();
        for (ShipLine l : ls) {
            self.merge(l.getItemId(), l.getQty().subtract(l.getPickedQty()).max(BigDecimal.ZERO), BigDecimal::add);
        }
        StockQuery q = StockQuery.of(stockQuery);
        Map<Long, BigDecimal> avail = q.available(plantCd, self.keySet());
        Map<Long, BigDecimal> reserved = q.reservedByShip(plantCd, self.keySet());
        for (ShipLine l : ls) {
            long item = l.getItemId();
            BigDecimal a = avail.getOrDefault(item, BigDecimal.ZERO);
            BigDecimal other = reserved.getOrDefault(item, BigDecimal.ZERO).subtract(self.get(item)).max(BigDecimal.ZERO);
            BigDecimal net = a.subtract(other).max(BigDecimal.ZERO);
            l.setAvailQty(a);
            l.setReservedOther(other);
            l.setNetQty(net);
            l.setShortQty(self.get(item).subtract(net).max(BigDecimal.ZERO));
        }
    }

    /** 출하 줄(WMS 표) + 수주 번호·행·납기는 영업 읽기 계약으로(docs/30 12.7.5 — 영업 표를 직접 읽지 않는다) */
    private List<ShipLine> lines(long shipId) {
        List<ShipLine> ls = mapper.findLines(shipId);
        if (ls.isEmpty()) {
            return ls;
        }
        Map<Long, OrderLineView> v = OrderLineQuery.of(orderLines).lines(ls.stream().map(ShipLine::getOrderLineId).toList()).stream()
                .collect(Collectors.toMap(OrderLineView::lineId, Function.identity()));
        for (ShipLine l : ls) {
            OrderLineView o = v.get(l.getOrderLineId());
            if (o != null) {
                l.setOrderNo(o.orderNo());
                l.setOrderLineNo(o.lineNo());
                l.setDueDate(o.dueDate());
            }
        }
        return ls;
    }

    /**
     * 출하 지시에 넣을 수 있는 수주 행 — 영업 계약의 열린 행(lineIds = null) 또는 고른 행, 이 고객·OPEN 만.
     * 남은 수량 = 수주 − 출하(투영) − 다른 열린 지시(DRAFT·PICKING)에 잡힌 지시 수량(WMS 몫)
     */
    private List<OrderLineOpt> openOrderLines(long custId, java.util.Collection<Long> lineIds) {
        return openOrderLines(custId, lineIds, null);
    }

    /** plantCd 가 있으면 품목마다 공장 가용·열린 지시 예약·순 가용을 채운다(docs/53 (가) — 지시 만들기 경고, 막지 않음) */
    private List<OrderLineOpt> openOrderLines(long custId, java.util.Collection<Long> lineIds, String plantCd) {
        OrderLineQuery q = OrderLineQuery.of(orderLines);
        List<OrderLineView> vs = lineIds == null ? q.openLines(custId)
                : q.lines(lineIds).stream().filter(o -> o.custId() == custId && "OPEN".equals(o.status())).toList();
        if (vs.isEmpty()) {
            return List.of();
        }
        Map<Long, BigDecimal> held = new HashMap<>();
        mapper.heldByOrderLine(vs.stream().map(OrderLineView::lineId).toList()).forEach(r -> held.put(r.getOrderLineId(), r.getQty()));
        List<OrderLineOpt> out = new ArrayList<>();
        for (OrderLineView o : vs) {
            OrderLineOpt x = new OrderLineOpt();
            x.setOrderLineId(o.lineId());
            x.setOrderNo(o.orderNo());
            x.setLineNo(o.lineNo());
            x.setCustPoNo(o.custPoNo());
            x.setItemId(o.itemId());
            x.setItemCd(o.itemCd());
            x.setItemNm(o.itemNm());
            x.setUnitCd(o.unitCd());
            x.setDueDate(o.dueDate());
            x.setQty(o.qty());
            x.setShippedQty(o.shippedQty());
            x.setInOtherShips(held.getOrDefault(o.lineId(), BigDecimal.ZERO));
            x.setOpenQty(o.qty().subtract(o.shippedQty()).subtract(x.getInOtherShips()));
            if (lineIds != null || x.getOpenQty().signum() > 0) {
                out.add(x);
            }
        }
        if (plantCd != null && !out.isEmpty()) {
            List<Long> items = out.stream().map(OrderLineOpt::getItemId).distinct().toList();
            StockQuery sq = StockQuery.of(stockQuery);
            Map<Long, BigDecimal> avail = sq.available(plantCd, items);
            Map<Long, BigDecimal> reserved = sq.reservedByShip(plantCd, items);
            for (OrderLineOpt x : out) {
                BigDecimal a = avail.getOrDefault(x.getItemId(), BigDecimal.ZERO);
                BigDecimal r = reserved.getOrDefault(x.getItemId(), BigDecimal.ZERO);
                x.setAvailQty(a);
                x.setReservedQty(r);
                x.setNetQty(a.subtract(r).max(BigDecimal.ZERO));
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<NodeOpt> stages(String plantCd) {
        return mapper.stageNodes(plant(plantCd));
    }

    @Transactional(readOnly = true)
    public List<OrderLineOpt> orderLines(String custCd) {
        return orderLines(custCd, null);
    }

    /** plantCd(선택) = 순 가용 경고를 셀 공장 — 없거나 이 회사 공장이 아니면 숫자 없이(목록은 그대로) */
    @Transactional(readOnly = true)
    public List<OrderLineOpt> orderLines(String custCd, String plantCd) {
        Long cust = cust(custCd);
        if (cust == null) {
            throw fieldError("custCd", custCd == null || custCd.isBlank() ? "REQUIRED" : "NO_SUCH_CODE");
        }
        String p = plantCd == null || plantCd.isBlank() ? null : plantCd.strip().toUpperCase(java.util.Locale.ROOT);
        return openOrderLines(cust, null, p != null && p.length() <= 20 && mapper.plantExists(p) ? p : null);
    }

    /** 출하 라벨 템플릿(선택지 — path 칸에 "코드 · 이름") */
    @Transactional(readOnly = true)
    public List<NodeOpt> templates(String usage) {
        return labelMapper.findTpls(labelUsage(usage), "Y").stream().map(t -> new NodeOpt(t.getTplId(), t.getTplCd() + " · " + t.getTplNm(), null)).toList();
    }

    /** 출하 라벨 프린터(용도 SHIP·ANY) */
    @Transactional(readOnly = true)
    public List<NodeOpt> printers(String plantCd, String usage) {
        String u = labelUsage(usage);
        return labelMapper.findPrinters(plant(plantCd), "Y").stream().filter(p -> "ANY".equals(p.getUsage()) || u.equals(p.getUsage()))
                .map(p -> new NodeOpt(p.getPrinterId(), p.getPrinterCd() + " · " + p.getPrinterNm(), null)).toList();
    }

    /** 출하 화면의 라벨 용도 — 출하(SHIP) 또는 팔레트(PALLET, B6b) */
    private static String labelUsage(String usage) {
        return "PALLET".equals(usage) ? "PALLET" : "SHIP";
    }

    /** 피킹 제안 — 차감 전략(W4) 미리보기(가용 정의 밖 칸은 미리보기가 LOCATION 으로 건너뜀), 줄의 남은 수량만큼 */
    @Transactional(readOnly = true)
    public List<PickView> suggest(long shipId, long shipLineId) {
        ShipHead h = mapper.findShip(shipId);
        if (h == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        ShipLine l = lines(shipId).stream().filter(x -> x.getShipLineId() == shipLineId).findFirst()
                .orElseThrow(() -> new BusinessException(HbErrorCode.NOT_FOUND));
        BigDecimal left = l.getQty().subtract(l.getPickedQty());
        if (left.signum() <= 0) {
            return List.of();
        }
        var p = alloc.preview(new PreviewForm(h.getPlantCd(), l.getItemId(), null, left.toPlainString(), null, null, true, true));
        return p.picks();
    }

    /**
     * 다른 칸(12.7.5 — 제안 밖 칸 직접 지정·스캔): 줄 품목의 가용 재고 칸. scan = 위치(하위 포함)·LOT·품목 스캔 키 —
     * 다른 품목·없는 키면 빈 목록과 안내. 피킹 자체는 {@link #pick} 이 칸 용도·수량·LOT 을 다시 본다(화면 목록은 고르기 도움일 뿐)
     */
    @Transactional(readOnly = true)
    public StockPick stock(long shipId, long shipLineId, String scan) {
        ShipHead h = mapper.findShip(shipId);
        if (h == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        ShipLine l = lines(shipId).stream().filter(x -> x.getShipLineId() == shipLineId).findFirst()
                .orElseThrow(() -> new BusinessException(HbErrorCode.NOT_FOUND));
        String text = scan == null ? "" : scan.strip();
        if (text.isEmpty()) {
            return new StockPick(mapper.pickStock(h.getPlantCd(), l.getItemId(), h.getStageNodeId(), null, null), null);
        }
        if (text.length() > ScanKeyResolver.MAX_LEN) {
            throw fieldError("scan", "MAX_LENGTH", ScanKeyResolver.MAX_LEN);
        }
        ScanHit hit = stock.scan(new ScanForm(h.getPlantCd(), text));
        Long node = null;
        Long lot = null;
        boolean ok = hit.ok();
        if (ok && "LOC".equals(hit.type())) {
            node = hit.nodeId();
        } else if (ok && ("LOT".equals(hit.type()) || "ITEM".equals(hit.type()))) {
            if (!Objects.equals(hit.itemId(), l.getItemId())) {
                return new StockPick(List.of(), messages.getMessage("wmsShip.scanOtherItem", new Object[] {hit.itemCd(), l.getItemCd()},
                        LocaleContextHolder.getLocale()));
            }
            lot = hit.lotId();
        } else {
            return new StockPick(List.of(), hit.message());
        }
        return new StockPick(mapper.pickStock(h.getPlantCd(), l.getItemId(), h.getStageNodeId(), node, lot), hit.message());
    }

    // ================================================================== 만들기·취소

    @Transactional
    public long create(CreateForm f) {
        if (f == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        LoginUser me = me();
        FieldErrors e = FieldErrors.create();
        String plant = null;
        try {
            plant = plant(f.plantCd());
        } catch (BusinessException ex) {
            e.add("plantCd", f.plantCd() == null || f.plantCd().isBlank() ? "REQUIRED" : "NOT_FOUND");
        }
        Long cust = cust(f.custCd());
        if (cust == null) {
            e.add("custCd", f.custCd() == null || f.custCd().isBlank() ? "REQUIRED" : "NO_SUCH_CODE");
        }
        final String p = plant;
        if (f.stageNodeId() == null) {
            e.add("stageNodeId", "REQUIRED");
        } else if (p != null && mapper.stageNodes(p).stream().noneMatch(n -> n.nodeId() == f.stageNodeId())) {
            e.add("stageNodeId", "NOT_STAGING");
        }
        LocalDate planDate = date(f.planDate(), "planDate", true);
        String remark = remark(f.remark(), "remark", e);
        List<LineForm> lines = f.lines() == null ? List.of() : f.lines();
        if (lines.isEmpty()) {
            e.add("lines", "REQUIRED");
        } else if (lines.size() > MAX_LINES) {
            e.add("lines", "TOO_MANY_ITEMS", MAX_LINES);
        }
        e.throwIfAny();
        // 줄: 같은 고객의 열린 수주 행, 지시 수량 ≤ 남은 수량(수주 − 출하 − 다른 열린 지시). 최종 판정(초과율)은 확정 때 영업 게이트
        Set<Long> ids = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            LineForm l = lines.get(i);
            var row = e.row(String.valueOf(i + 1));
            if (l == null || l.orderLineId() == null) {
                row.add("orderLineId", "REQUIRED");
            } else if (!ids.add(l.orderLineId())) {
                row.add("orderLineId", "DUPLICATE");
            }
            if (l == null || l.qty() == null || l.qty().signum() <= 0) {
                row.add("qty", "POSITIVE");
            } else if (l.qty().stripTrailingZeros().scale() > 6) {
                row.add("qty", "SCALE", 6);
            }
        }
        e.throwIfAny();
        // 같은 수주 행으로 동시에 만드는 지시를 줄 세운다(QA W-1) — 남은 수량을 읽기 전에, 수주 행 id 오름차순으로. 수주 행은 영업 표라 FOR UPDATE 하지
        // 않고(영업에 잠금 계약 없음) 회사·수주 행 키의 트랜잭션 advisory 잠금. 잠금 순서: 수주 행(advisory) → 채번 → 출하 머리(docs/30 12.7)
        String comp = io.smarthappy.platform.tenant.TenantContext.requireCompCd();
        ids.stream().sorted().forEach(olId -> mapper.lockOrderLineForShip(comp + ":" + olId));
        Map<Long, OrderLineOpt> open = openOrderLines(cust, ids).stream()
                .collect(Collectors.toMap(OrderLineOpt::getOrderLineId, Function.identity()));
        for (int i = 0; i < lines.size(); i++) {
            LineForm l = lines.get(i);
            OrderLineOpt o = open.get(l.orderLineId());
            var row = e.row(String.valueOf(i + 1));
            if (o == null) {
                row.add("orderLineId", "NOT_FOUND");                 // 다른 고객·다른 회사·닫힌 행
            } else if (l.qty().compareTo(o.getOpenQty()) > 0) {
                row.add("qty", "SHIP_OVER_OPEN", text(o.getOpenQty()));
            }
        }
        e.throwIfAny();
        String no = numbering.nextOrDefault(RULE, "출하 지시", "SHP-{yyMMdd}-{seq:3}", "DAY");
        long id = mapper.insertShip(plant, no, cust, f.stageNodeId(), planDate, remark, me.loginId());
        int n = 0;
        for (LineForm l : lines) {
            mapper.insertLine(id, ++n, l.orderLineId(), open.get(l.orderLineId()).getItemId(), l.qty(), me.loginId());
        }
        return id;
    }

    /** 지시 취소 — 피킹이 남아 있지 않은 DRAFT 만(피킹이 있으면 피킹 취소 먼저) */
    @Transactional
    public ShipView cancel(long id, CancelForm f) {
        LoginUser me = me();
        ShipHead h = lock(id, f == null ? null : f.rowVer());
        if (!"DRAFT".equals(h.getStatus())) {
            throw new BusinessException(HbErrorCode.WMS_SHIP_STATE);
        }
        String reason = reason(f.reason());
        if (mapper.markCancelled(id, reason, me.loginId()) != 1) {
            throw new BusinessException(HbErrorCode.WMS_SHIP_STATE);
        }
        return view(id);
    }

    // ================================================================== 피킹

    /**
     * 피킹 확정 — 줄마다 출발 칸·LOT·수량. 원장 MOVE 짝(출발 → 출하 대기, AVAILABLE 만, 출처 SHIP_PICK = 피킹 id)을 한 번에(칸 순서).
     * 줄의 피킹 합(PICKED·SHIPPED) ≤ 지시 수량. 재고가 모자라면 409(원장)
     */
    @Transactional
    public ShipView pick(long id, PickForm f) {
        LoginUser me = me();
        String req = reqId(f == null ? null : f.clientReqId());
        if (!inbox.firstTime(INBOX_PICK, req)) {
            return view(id);                                  // 같은 요청을 다시 보냄 — 처음 결과
        }
        ShipHead h = lock(id, f.rowVer());
        if (!"DRAFT".equals(h.getStatus()) && !"PICKING".equals(h.getStatus())) {
            throw new BusinessException(HbErrorCode.WMS_SHIP_STATE);
        }
        List<PickReq> picks = f.picks() == null ? List.of() : f.picks();
        if (picks.isEmpty()) {
            throw fieldError("picks", "REQUIRED");
        }
        if (picks.size() > MAX_PICKS) {
            throw fieldError("picks", "TOO_MANY_ITEMS", MAX_PICKS);
        }
        Map<Long, ShipLine> lines = mapper.findLines(id).stream().collect(Collectors.toMap(ShipLine::getShipLineId, Function.identity()));
        Map<Long, BigDecimal> picked = new HashMap<>();
        lines.values().forEach(l -> picked.put(l.getShipLineId(), l.getPickedQty()));
        // 출하 특이사항 확인 필수(docs/56) — 머리를 잠근 뒤 읽는다. 사무·PDA 가 같은 검사
        Map<Long, String> unacked = notes.unacked(id, lines.keySet());
        FieldErrors e = FieldErrors.create();
        List<Long> lotIds = new ArrayList<>();
        for (int i = 0; i < picks.size(); i++) {
            PickReq p = picks.get(i);
            var row = e.row(String.valueOf(i + 1));
            ShipLine l = p == null || p.shipLineId() == null ? null : lines.get(p.shipLineId());
            Long lot = null;
            if (l == null) {
                row.add("shipLineId", p == null || p.shipLineId() == null ? "REQUIRED" : "NOT_FOUND");
            } else if (unacked.containsKey(l.getShipLineId())) {
                row.add("shipLineId", "NOTE_ACK_REQUIRED", unacked.get(l.getShipLineId()));
            }
            if (p == null || p.fromNodeId() == null) {
                row.add("fromNodeId", "REQUIRED");
            } else {
                String usage = mapper.nodeUsage(p.fromNodeId(), h.getPlantCd());
                if (usage == null) {
                    row.add("fromNodeId", "NOT_FOUND");
                } else if (!mapper.pickableLoc(p.fromNodeId()) || p.fromNodeId() == h.getStageNodeId()) {
                    row.add("fromNodeId", "NOT_PICKABLE");
                }
            }
            if (p == null || p.qty() == null || p.qty().signum() <= 0) {
                row.add("qty", "POSITIVE");
            } else if (p.qty().stripTrailingZeros().scale() > 6) {
                row.add("qty", "SCALE", 6);
            } else if (l != null) {
                BigDecimal sum = picked.get(l.getShipLineId()).add(p.qty());
                if (sum.compareTo(l.getQty()) > 0) {
                    row.add("qty", "PICK_OVER", text(l.getQty().subtract(picked.get(l.getShipLineId()))));
                } else {
                    picked.put(l.getShipLineId(), sum);
                }
            }
            if (l != null) {
                String lotNo = p.lotNo() == null ? "" : p.lotNo().strip();
                if ("Y".equals(l.getLotCtrlYn())) {
                    if (lotNo.isEmpty()) {
                        row.add("lotNo", "LOT_REQUIRED");
                    } else if ((lot = mapper.lotIdByNo(l.getItemId(), lotNo)) == null) {
                        row.add("lotNo", "LOT_MISMATCH");
                    }
                }
            }
            lotIds.add(lot);
        }
        e.throwIfAny();
        // 피킹 행 → 원장 이동 짝(한 번에, 칸 순서로 들어간다). 출하 대기 칸은 AVAILABLE(D-B15: 피킹은 합격분만)
        OffsetDateTime at = OffsetDateTime.now(clock.zone(h.getPlantCd()));
        List<TrxReq> rows = new ArrayList<>();
        for (int i = 0; i < picks.size(); i++) {
            PickReq p = picks.get(i);
            ShipLine l = lines.get(p.shipLineId());
            long pickId = mapper.insertPick(l.getShipLineId(), p.fromNodeId(), lotIds.get(i), p.qty(), me.loginId());
            UUID grp = UUID.randomUUID();
            rows.add(new TrxReq("MOVE_OUT", l.getItemId(), p.fromNodeId(), "AVAILABLE", p.qty().negate(), grp, SRC, pickId, null, h.getShipNo(),
                    at, "KEY", null, lotIds.get(i)));
            rows.add(new TrxReq("MOVE_IN", l.getItemId(), h.getStageNodeId(), "AVAILABLE", p.qty(), grp, SRC, pickId, null, h.getShipNo(), at,
                    "KEY", null, lotIds.get(i)));
        }
        inventory.post(rows, req);
        if ("DRAFT".equals(h.getStatus())) {
            mapper.updateStatus(id, "DRAFT", "PICKING", me.loginId());
        }
        return view(id);
    }

    /** 피킹 취소 — PICKED 피킹의 이동 짝을 한 번에 역분개(출하 대기 → 출발 칸), 남은 피킹이 없으면 DRAFT 로 */
    @Transactional
    public ShipView unpick(long id, UnpickForm f) {
        LoginUser me = me();
        ShipHead h = lock(id, f == null ? null : f.rowVer());
        if (!"PICKING".equals(h.getStatus())) {
            throw new BusinessException(HbErrorCode.WMS_SHIP_STATE);
        }
        String reason = reason(f.reason());
        Map<Long, PickRow> live = mapper.picksOf(id, "PICKED").stream().collect(Collectors.toMap(PickRow::getPickId, Function.identity()));
        List<Long> pickIds = f.pickIds() == null ? List.of() : f.pickIds().stream().distinct().toList();
        if (pickIds.isEmpty()) {
            throw fieldError("pickIds", "REQUIRED");
        }
        FieldErrors e = FieldErrors.create();
        List<Long> moves = new ArrayList<>();
        for (Long pid : pickIds) {
            Long mv = pid == null || !live.containsKey(pid) ? null : mapper.liveMoveOf(pid);
            if (mv == null) {
                e.row(String.valueOf(pid)).add("pickId", "NOT_FOUND");
            } else {
                moves.add(mv);
            }
        }
        e.throwIfAny();
        // 팔레트에 담긴 피킹은 함께 뺀다 — 닫힌(봉인) 팔레트의 피킹은 다시 열기 먼저(B6b)
        List<Long> onPallet = pickIds.stream().filter(pid -> live.get(pid).getPalletId() != null).toList();
        Map<Long, String> palletState = new TreeMap<>();
        onPallet.forEach(pid -> palletState.put(live.get(pid).getPalletId(), null));
        for (Long tid : palletState.keySet()) {               // 팔레트 id 순으로 잠금
            ShipDtos.Pallet t = mapper.lockPallet(id, tid);
            palletState.put(tid, t == null ? null : t.getStatus());
        }
        for (Long pid : onPallet) {
            if (!"OPEN".equals(palletState.get(live.get(pid).getPalletId()))) {
                e.row(String.valueOf(pid)).add("pickId", "PALLET_CLOSED");
            }
        }
        e.throwIfAny();
        if (!onPallet.isEmpty()) {
            mapper.setPickPallet(onPallet, null, me.loginId());
        }
        inventory.reverseAll(moves, reason);
        mapper.setPickStatus(pickIds, "PICKED", "UNPICKED", me.loginId());
        if (mapper.picksOf(id, "PICKED").isEmpty()) {
            mapper.updateStatus(id, "PICKING", "DRAFT", me.loginId());
        }
        return view(id);
    }

    // ================================================================== 출하 특이사항 확인 (docs/56)

    /** 확인 — 열린 지시(DRAFT·PICKING)에 걸린 사용 중 규칙, 현재 버전. 같은 버전 두 번 = 처음 기록. workerId = PDA 배지 작업자 */
    @Transactional
    public ShipView ackNote(long id, long noteId, Long workerId) {
        ShipHead h = mapper.findShip(id);
        if (h == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"DRAFT".equals(h.getStatus()) && !"PICKING".equals(h.getStatus())) {
            throw new BusinessException(HbErrorCode.WMS_SHIP_STATE);
        }
        notes.ack(id, noteId, workerId);
        return view(id);
    }

    // ================================================================== 출하 확정·취소

    @Transactional
    public ConfirmResult confirm(long id, ConfirmForm f) {
        LoginUser me = me();
        String req = reqId(f == null ? null : f.clientReqId());
        if (!inbox.firstTime(INBOX_CONFIRM, req)) {
            return new ConfirmResult(view(id), List.of(), 0, true);
        }
        ShipHead h = lock(id, f.rowVer());
        if (!"PICKING".equals(h.getStatus())) {
            throw new BusinessException(HbErrorCode.WMS_SHIP_STATE);
        }
        int copies = f.copies() == null ? 1 : f.copies();
        if (f.printerId() != null) {
            FieldErrors e = FieldErrors.create();
            if (copies < 1 || copies > MAX_COPIES) {
                e.add("copies", "RANGE", 1, MAX_COPIES);
            }
            if (labelMapper.findPrinters(h.getPlantCd(), "Y").stream()
                    .noneMatch(p -> p.getPrinterId() == f.printerId() && ("ANY".equals(p.getUsage()) || "SHIP".equals(p.getUsage())))) {
                e.add("printerId", "NOT_FOUND");
            }
            e.throwIfAny();
        }
        List<PickRow> picks = mapper.picksOf(id, "PICKED");
        if (picks.isEmpty()) {
            throw fieldError("picks", "REQUIRED");
        }
        // 출하 특이사항 확인 필수 — 피킹 뒤에 규칙을 켜거나 글을 고친 경우(docs/56 2.2)
        Map<Long, String> unacked = notes.unacked(id, picks.stream().map(PickRow::getShipLineId).collect(Collectors.toSet()));
        if (!unacked.isEmpty()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(
                    FieldErrors.create().add("notes", "NOTE_ACK_REQUIRED", unacked.values().iterator().next()).list());
        }
        // 팔레트 필수 공장이면 PICKED 피킹이 전부 닫힌 팔레트에(B6b, D-B24)
        if ("Y".equals(policy.value(POLICY_PALLET, h.getPlantCd()))) {
            int loose = mapper.countUnpalletized(id);
            if (loose > 0) {
                throw new BusinessException(HbErrorCode.WMS_SHIP_UNPALLETIZED, loose);
            }
        }
        // ① 영업 게이트 — 수주 행별 합을 한 번에(영업이 머리 → 행 순으로 잠근다)
        Map<Long, BigDecimal> byLine = new TreeMap<>();
        picks.forEach(p -> byLine.merge(p.getOrderLineId(), p.getQty(), BigDecimal::add));
        OrderShipGate.of(shipGate).ship(byLine.entrySet().stream().map(x -> new ShipQty(x.getKey(), x.getValue())).toList());
        // ② 피킹 LOT 먼저(FOR SHARE) ③ PLM 변경 확인(읽기만)
        List<Long> lots = picks.stream().map(PickRow::getLotId).filter(java.util.Objects::nonNull).distinct().sorted().toList();
        inventory.lockLotsFirst(lots);
        List<String> warnings = new ArrayList<>();
        if (!lots.isEmpty()) {
            Map<Long, String> lotNos = new HashMap<>();
            picks.forEach(p -> { if (p.getLotId() != null) { lotNos.put(p.getLotId(), p.getLotNo()); } });
            boolean block = !"N".equals(policy.value(POLICY_BLOCK));
            for (ShipCheck c : EcoTrace.of(ecoTrace).shipChecks(lots)) {
                if (c.block() && block) {
                    throw new BusinessException(HbErrorCode.WMS_SHIP_CHANGE_BLOCKED, lotNos.get(c.lotId()), c.ecoNo());
                }
                warnings.add(lotNos.get(c.lotId()) + " · " + c.ecoNo() + " · " + c.ppapStatus());
            }
        }
        // ③-2 다른 모듈 출하 조건(읽기만) — 막는 것이 하나라도 있으면 전부 알리고 거부
        runConfirmChecks(h, picks);
        // ④ 원장 SHIP −(출하 대기 칸) — 칸 순서로 들어간다
        OffsetDateTime at = OffsetDateTime.now(clock.zone(h.getPlantCd()));
        List<TrxReq> rows = picks.stream().map(p -> new TrxReq("SHIP", p.getItemId(), h.getStageNodeId(), "AVAILABLE", p.getQty().negate(), null,
                SRC, p.getPickId(), null, h.getShipNo(), at, "KEY", null, p.getLotId())).toList();
        inventory.post(rows, req);
        mapper.setPickStatus(picks.stream().map(PickRow::getPickId).toList(), "PICKED", "SHIPPED", me.loginId());
        if (mapper.markShipped(id, me.loginId()) != 1) {
            throw new BusinessException(HbErrorCode.WMS_SHIP_STATE);
        }
        // ⑤ 라벨(선택)·ERP(정책 어댑터가 있을 때)
        ShipView v = view(id);
        int jobs = f.printerId() == null ? 0 : labels(v, f.tplId(), f.printerId(), copies);
        erp.shipped(v, picks, 1, at);
        return new ConfirmResult(v, warnings, jobs, false);
    }

    /**
     * 출하 조건 확장 지점(docs/72 2.1): 피킹 LOT(LOT 관리 품목, 같은 LOT 한 번)을 빈 순서대로 넘기고, 막은 것을 LOT 별 칸 오류(rowKey = LOT 번호)로 모아
     * 409 WMS_SHIP_CHECK_BLOCKED — 첫 건 LOT·사유가 문구 인자. 빈이 없으면 아무것도 하지 않는다.
     * LOT 이 없는 출하에도 부른다(문맥 lots 빈 목록, docs/78 5장 — 출하 단위 확인). LOT 없는 막음(lotNo null)은 행 키·문구 인자가 출하 번호
     */
    private void runConfirmChecks(ShipHead h, List<PickRow> picks) {
        Map<Long, ShipConfirmCheck.ShipLot> lots = new java.util.LinkedHashMap<>();
        picks.stream().filter(p -> p.getLotId() != null).sorted(java.util.Comparator.comparing(PickRow::getLotId))
                .forEach(p -> lots.putIfAbsent(p.getLotId(), new ShipConfirmCheck.ShipLot(p.getLotId(), p.getLotNo(), p.getItemId(),
                        p.getOrderLineId())));
        var ctx = new ShipConfirmCheck.ShipContext(h.getShipId(), h.getShipNo(), h.getPlantCd(), List.copyOf(lots.values()));
        var locale = LocaleContextHolder.getLocale();
        FieldErrors e = FieldErrors.create();
        String first = null;
        String firstLot = null;
        for (ShipConfirmCheck c : confirmChecks.orderedStream().toList()) {
            for (ShipConfirmCheck.Block b : c.check(ctx)) {
                String text = messages.getMessage(b.messageKey(), b.args().toArray(), b.messageKey(), locale);
                String rowKey = b.lotNo() == null ? h.getShipNo() : b.lotNo();
                e.row(rowKey).add("lotNo", "SHIP_CHECK", text);
                if (first == null) {
                    first = text;
                    firstLot = b.lotNo() == null ? h.getShipNo() : "LOT " + b.lotNo();
                }
            }
        }
        if (first != null) {
            throw new BusinessException(HbErrorCode.WMS_SHIP_CHECK_BLOCKED, firstLot, first).withFieldErrors(e.list());
        }
    }

    /** 출하 취소 — 영업 게이트 음수 → SHIP 역분개(STAGING 으로 돌아옴) → 피킹 PICKED, 머리 PICKING. ERP 는 음수 전표 */
    @Transactional
    public ConfirmResult undo(long id, CancelForm f) {
        LoginUser me = me();
        String req = reqId(f == null ? null : f.clientReqId());
        if (!inbox.firstTime(INBOX_UNDO, req)) {
            return new ConfirmResult(view(id), List.of(), 0, true);
        }
        ShipHead h = lock(id, f.rowVer());
        if (!"SHIPPED".equals(h.getStatus())) {
            throw new BusinessException(HbErrorCode.WMS_SHIP_STATE);
        }
        // 고객 반품이 걸린 출하는 취소하지 않는다(docs/59) — 반품 먼저 취소. 반품 입고는 이 머리를 FOR SHARE 로 잡아 줄 선다
        String rtnNo = mapper.liveCustRtnOf(id);
        if (rtnNo != null) {
            throw new BusinessException(HbErrorCode.WMS_SHIP_HAS_RETURN, rtnNo);
        }
        String reason = reason(f.reason());
        // 다시 열면 이 지시 줄 수량 전체가 "열린 지시" 로 돌아온다 — 지시 만들기와 같은 수주 행 잠금으로 줄 세우고(U-1, 사용자 결정 2026-10-30),
        // 끝에서 남은 수량이 음수가 되면(부분 출하 뒤 나머지로 만든 다른 지시가 있음) 409. 잠금 순서: 출하 머리 → 수주 행(advisory) → 영업 게이트
        List<ShipLine> shipLines = mapper.findLines(id);
        String comp = io.smarthappy.platform.tenant.TenantContext.requireCompCd();
        shipLines.stream().map(ShipLine::getOrderLineId).distinct().sorted().forEach(olId -> mapper.lockOrderLineForShip(comp + ":" + olId));
        List<ShipTrx> trx = mapper.liveShipsOf(id);
        Map<Long, BigDecimal> byLine = new TreeMap<>();
        trx.forEach(t -> byLine.merge(t.getOrderLineId(), t.getQty(), BigDecimal::add));          // SHIP qty 는 음수
        OrderShipGate.of(shipGate).ship(byLine.entrySet().stream().map(x -> new ShipQty(x.getKey(), x.getValue())).toList());
        List<PickRow> picks = mapper.picksOf(id, "SHIPPED");
        inventory.reverseAll(trx.stream().map(ShipTrx::getTrxId).toList(), reason);
        mapper.setPickStatus(picks.stream().map(PickRow::getPickId).toList(), "SHIPPED", "PICKED", me.loginId());
        if (mapper.markUnshipped(id, me.loginId()) != 1) {
            throw new BusinessException(HbErrorCode.WMS_SHIP_STATE);
        }
        for (OrderLineOpt o : openOrderLines(h.getCustId(), shipLines.stream().map(ShipLine::getOrderLineId).distinct().toList())) {
            if (o.getOpenQty().signum() < 0) {                  // 트랜잭션째 되돌린다(게이트·역분개 포함)
                throw new BusinessException(HbErrorCode.WMS_SHIP_UNDO_OVER, o.getOrderNo() + "-" + o.getLineNo(), text(o.getOpenQty().negate()));
            }
        }
        ShipView v = view(id);
        erp.shipped(v, picks, -1, OffsetDateTime.now(clock.zone(h.getPlantCd())));
        return new ConfirmResult(v, List.of(), 0, false);
    }

    // ================================================================== 팔레트 (B6b, docs/30 12.10)

    /** 새 팔레트(OPEN) — 지시가 DRAFT·PICKING 일 때. 머리 rowVer 로 줄 세운다 */
    @Transactional
    public ShipView createPallet(long id, ShipDtos.PalletForm f) {
        LoginUser me = me();
        ShipHead h = lock(id, f == null ? null : f.rowVer());
        openForPallets(h);
        FieldErrors e = FieldErrors.create();
        String remark = remark(f.remark(), "remark", e);
        e.throwIfAny();
        String no = numbering.nextOrDefault(RULE_PALLET, "출하 팔레트", "PLT-{yyMMdd}-{seq:3}", "DAY");
        mapper.insertPallet(id, no, remark, me.loginId());
        mapper.touchShip(id, me.loginId());
        return view(id);
    }

    /** 피킹을 팔레트에 담기(palletId) 또는 빼기(null) — PICKED 피킹만, 팔레트는 이 지시·OPEN */
    @Transactional
    public ShipView setPallet(long id, ShipDtos.PalletPicksForm f) {
        LoginUser me = me();
        ShipHead h = lock(id, f == null ? null : f.rowVer());
        openForPallets(h);
        List<Long> pickIds = f.pickIds() == null ? List.of() : f.pickIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (pickIds.isEmpty()) {
            throw fieldError("pickIds", "REQUIRED");
        }
        Map<Long, PickRow> live = mapper.picksOf(id, "PICKED").stream().collect(Collectors.toMap(PickRow::getPickId, Function.identity()));
        FieldErrors e = FieldErrors.create();
        java.util.Set<Long> touched = new java.util.TreeSet<>();
        for (Long pid : pickIds) {
            PickRow p = live.get(pid);
            if (p == null) {
                e.row(String.valueOf(pid)).add("pickId", "NOT_FOUND");
            } else if (p.getPalletId() != null) {
                touched.add(p.getPalletId());
            }
        }
        if (f.palletId() != null) {
            touched.add(f.palletId());
        }
        e.throwIfAny();
        // 관련 팔레트를 id 순으로 잠그고 OPEN 인지(빼는 쪽·넣는 쪽 모두)
        for (Long tid : touched) {
            ShipDtos.Pallet t = mapper.lockPallet(id, tid);
            if (t == null) {
                throw fieldError("palletId", "NOT_FOUND");
            }
            if (!"OPEN".equals(t.getStatus())) {
                throw fieldError("palletId", "PALLET_CLOSED");
            }
        }
        mapper.setPickPallet(pickIds, f.palletId(), me.loginId());
        mapper.touchShip(id, me.loginId());
        return view(id);
    }

    /**
     * 팔레트 닫기(봉인) — 빈 팔레트는 안 됨. 처음 닫을 때 SSCC 부여(회사 정책 GS1 업체코드가 있을 때, D-B22·D-B23).
     * 프린터를 고르면 팔레트 라벨(PALLET)
     */
    @Transactional
    public ConfirmResult closePallet(long id, long palletId, ShipDtos.PalletCloseForm f) {
        LoginUser me = me();
        ShipHead h = lock(id, f == null ? null : f.rowVer());
        openForPallets(h);
        ShipDtos.Pallet t = mapper.lockPallet(id, palletId);
        if (t == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"OPEN".equals(t.getStatus())) {
            throw new BusinessException(HbErrorCode.WMS_SHIP_STATE);
        }
        if (t.getPickCount() == 0) {
            throw fieldError("palletId", "PALLET_EMPTY");
        }
        int copies = f.copies() == null ? 1 : f.copies();
        if (f.printerId() != null) {
            checkPrinter(h, f.printerId(), copies, "PALLET");
        }
        String sscc = t.getSscc() != null ? t.getSscc() : newSscc();
        mapper.closePallet(palletId, sscc, me.loginId());
        mapper.touchShip(id, me.loginId());
        int jobs = f.printerId() == null ? 0 : palletLabel(id, palletId, f.tplId(), f.printerId(), copies);
        return new ConfirmResult(view(id), List.of(), jobs, false);
    }

    /** 다시 열기(사유) — 출하 확정 전(지시 PICKING)만. SSCC 는 그대로(D-B23) */
    @Transactional
    public ShipView reopenPallet(long id, long palletId, ShipDtos.PalletReopenForm f) {
        LoginUser me = me();
        ShipHead h = lock(id, f == null ? null : f.rowVer());
        openForPallets(h);
        String reason = reason(f.reason());
        ShipDtos.Pallet t = mapper.lockPallet(id, palletId);
        if (t == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (mapper.setPalletStatus(palletId, "CLOSED", "OPEN", reason, me.loginId()) != 1) {
            throw new BusinessException(HbErrorCode.WMS_SHIP_STATE);
        }
        mapper.touchShip(id, me.loginId());
        return view(id);
    }

    /** 빈 OPEN 팔레트 취소 */
    @Transactional
    public ShipView cancelPallet(long id, long palletId, ShipDtos.PalletForm f) {
        LoginUser me = me();
        ShipHead h = lock(id, f == null ? null : f.rowVer());
        openForPallets(h);
        ShipDtos.Pallet t = mapper.lockPallet(id, palletId);
        if (t == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (t.getPickCount() > 0) {
            throw fieldError("palletId", "PALLET_NOT_EMPTY");
        }
        if (mapper.setPalletStatus(palletId, "OPEN", "CANCELLED", null, me.loginId()) != 1) {
            throw new BusinessException(HbErrorCode.WMS_SHIP_STATE);
        }
        mapper.touchShip(id, me.loginId());
        return view(id);
    }

    /**
     * 팔레트 스캔(B6b-2, 12.10) — {@code PLT:{번호}}(내부 라벨 barcode) 또는 SSCC: GS1 (00)(심볼 식별자·괄호·GS 형식, 스캔 키 규칙 W1),
     * 또는 접두어 없는 숫자 18자리(SSCC)·20자리("00" + SSCC — 스캐너가 심볼 식별자를 안 보낼 때). 검사 숫자가 틀리면 400.
     * 찾은 팔레트의 출하 지시를 돌려준다(없거나 다른 회사 = 404)
     */
    @Transactional(readOnly = true)
    public ShipDtos.PalletHit scanPallet(ShipDtos.PalletScanForm f) {
        String text = f == null || f.text() == null ? "" : f.text().strip();
        if (text.isEmpty()) {
            throw fieldError("text", "REQUIRED");
        }
        String palletNo = null;
        String sscc = null;
        if (text.matches("^(00)?[0-9]{18}$")) {
            sscc = text.substring(text.length() - 18);
        } else {
            var r = ScanKeyResolver.resolve(text, new ScanKeyResolver.Context(null, ScanKeyResolver.BareAs.NONE, null));
            if (r.type() == ScanKeyResolver.Type.PLT) {
                palletNo = r.key();
            } else if (r.type() == ScanKeyResolver.Type.GS1 && r.gs1() != null && r.gs1().sscc() != null) {
                sscc = r.gs1().sscc();
            } else if (r.type() == ScanKeyResolver.Type.UNKNOWN && r.reason() == ScanKeyResolver.Reason.GS1_CHECK_DIGIT) {
                throw fieldError("text", "CHECK_DIGIT");
            } else {
                throw fieldError("text", "NOT_PALLET");
            }
        }
        if (sscc != null && !Sscc.valid(sscc)) {
            throw fieldError("text", "CHECK_DIGIT");
        }
        ShipDtos.PalletHit hit = mapper.findPalletByKey(palletNo, sscc);
        if (hit == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return hit;
    }

    /** 팔레트 동작은 출하 확정 전(DRAFT·PICKING)만 */
    private static void openForPallets(ShipHead h) {
        if (!"DRAFT".equals(h.getStatus()) && !"PICKING".equals(h.getStatus())) {
            throw new BusinessException(HbErrorCode.WMS_SHIP_STATE);
        }
    }

    /** 새 SSCC — 업체코드 정책이 비면 null(내부 번호만), 모양이 틀리거나 일련번호 자리가 모자라면 409 */
    private String newSscc() {
        String prefix = policy.value(POLICY_GS1);
        if (prefix == null || prefix.isBlank()) {
            return null;
        }
        prefix = prefix.strip();
        if (!Sscc.prefixOk(prefix)) {
            throw new BusinessException(HbErrorCode.WMS_SSCC_UNAVAILABLE);
        }
        long serial = Long.parseLong(numbering.nextOrDefault(RULE_SSCC, "SSCC 일련번호", "{seq:10}", "NONE"));
        String sscc = Sscc.build(0, prefix, serial);
        if (sscc == null) {
            throw new BusinessException(HbErrorCode.WMS_SSCC_UNAVAILABLE);
        }
        return sscc;
    }

    private void checkPrinter(ShipHead h, long printerId, int copies, String usage) {
        FieldErrors e = FieldErrors.create();
        if (copies < 1 || copies > MAX_COPIES) {
            e.add("copies", "RANGE", 1, MAX_COPIES);
        }
        if (labelMapper.findPrinters(h.getPlantCd(), "Y").stream()
                .noneMatch(p -> p.getPrinterId() == printerId && ("ANY".equals(p.getUsage()) || usage.equals(p.getUsage())))) {
            e.add("printerId", "NOT_FOUND");
        }
        e.throwIfAny();
    }

    /** 팔레트 라벨 한 장 — 한 품목이면 그 품목·수량, 여럿이면 MIXED. barcode = PLT:{번호}(내부 스캔 키), sscc 는 GS1-128 (00) 용 */
    private int palletLabel(long shipId, long palletId, Long tplId, long printerId, int copies) {
        ShipDtos.Pallet t = mapper.findPallets(shipId).stream().filter(x -> x.getPalletId() == palletId).findFirst()
                .orElseThrow(() -> new BusinessException(HbErrorCode.NOT_FOUND));
        List<ShipMapper.PalletItem> items = mapper.palletContent(palletId);
        boolean one = items.size() == 1;
        Map<String, String> m = new LinkedHashMap<>();
        m.put("palletNo", t.getPalletNo());
        m.put("sscc", t.getSscc() == null ? "" : t.getSscc());
        m.put("itemCd", one ? items.get(0).getItemCd() : "MIXED");
        m.put("itemNm", one ? items.get(0).getItemNm() : items.size() + " items");
        m.put("qty", one ? text(items.get(0).getQty()) : "");
        m.put("unitCd", one ? items.get(0).getUnitCd() : "");
        m.put("lotCount", String.valueOf(items.stream().mapToInt(ShipMapper.PalletItem::getLotCount).sum()));
        m.put("barcode", "PLT:" + t.getPalletNo());
        return labels.request("PALLET", tplId, printerId, copies,
                List.of(new PrintItem(m.get("barcode"), m, null, one ? items.get(0).getItemId() : null))).size();
    }

    // ================================================================== 라벨

    /** 출하 줄마다 출하 라벨 한 장(필드 = LabelFields SHIP). 고객 품번은 비움(D-B19) */
    private int labels(ShipView v, Long tplId, long printerId, int copies) {
        List<PrintItem> items = new ArrayList<>();
        for (ShipLine l : v.lines()) {
            if (l.getShippedQty().signum() <= 0) {
                continue;
            }
            Map<String, String> m = new LinkedHashMap<>();
            m.put("shipNo", v.head().getShipNo());
            m.put("custNm", v.head().getCustNm());
            // 이 줄이 실린 팔레트 수(B6b-2) — 출하된 피킹이 담긴 팔레트, 팔레트 없이 나가면 0
            m.put("palletCount", String.valueOf(v.picks().stream()
                    .filter(p -> p.getShipLineId() == l.getShipLineId() && "SHIPPED".equals(p.getStatus()) && p.getPalletId() != null)
                    .map(ShipDtos.ShipPick::getPalletId).distinct().count()));
            m.put("custItemCd", "");
            m.put("itemCd", l.getItemCd());
            m.put("itemNm", l.getItemNm());
            m.put("qty", text(l.getShippedQty()));
            m.put("unitCd", l.getUnitCd());
            m.put("dueDate", l.getDueDate() == null ? "" : l.getDueDate().toString());
            m.put("barcode", v.head().getShipNo() + "-" + l.getLineNo());
            items.add(new PrintItem(m.get("barcode"), m, v.head().getCustId(), l.getItemId()));   // 템플릿 규칙 문맥(docs/65 18.4)
        }
        return items.isEmpty() ? 0 : labels.request("SHIP", tplId, printerId, copies, items).size();
    }

    // ================================================================== 공통

    private ShipHead lock(long id, Integer rowVer) {
        if (rowVer == null) {
            throw fieldError("rowVer", "REQUIRED");
        }
        ShipHead h = mapper.lockShip(id);
        if (h == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (h.getRowVer() != rowVer) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return h;
    }

    private String plant(String plantCd) {
        if (plantCd == null || plantCd.isBlank()) {
            throw fieldError("plantCd", "REQUIRED");
        }
        String p = plantCd.strip().toUpperCase(Locale.ROOT);
        if (p.length() > 20 || !mapper.plantExists(p)) {
            throw fieldError("plantCd", "NOT_FOUND");
        }
        return p;
    }

    private Long cust(String custCd) {
        if (custCd == null || custCd.isBlank()) {
            return null;
        }
        String c = custCd.strip().toUpperCase(Locale.ROOT);
        return c.length() > 40 ? null : mapper.custIdByCode(c);
    }

    private LocalDate date(String v, String field, boolean required) {
        if (v == null || v.isBlank()) {
            if (required) {
                throw fieldError(field, "REQUIRED");
            }
            return null;
        }
        try {
            return LocalDate.parse(v.strip());
        } catch (DateTimeParseException ex) {
            throw fieldError(field, "FORMAT");
        }
    }

    static String reqId(String v) {
        String r = v == null ? "" : v.strip();
        if (!r.matches("^[A-Za-z0-9._:-]{8,64}$")) {
            throw fieldError("clientReqId", r.isEmpty() ? "REQUIRED" : "FORMAT");
        }
        return r;
    }

    static String reason(String v) {
        String r = v == null ? "" : v.strip();
        if (r.length() < 2) {
            throw fieldError("reason", "REQUIRED");
        }
        if (r.length() > 500) {
            throw fieldError("reason", "TOO_LONG", 500);
        }
        return r;
    }

    static String remark(String v, String field, FieldErrors e) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String r = v.strip();
        if (r.length() > 500) {
            e.add(field, "TOO_LONG", 500);
        }
        return r;
    }

    static String text(BigDecimal v) {
        return v == null ? null : v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
    }

    /** 로그인 사용자, 없으면 요청 행위자(창고 PDA 단말 — 피킹한 사람 칸에 단말, docs/30 12.12) */
    static LoginUser me() {
        return InventoryService.me();
    }

    static BusinessException fieldError(String field, String code, Object... args) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code, args).list());
    }
}
