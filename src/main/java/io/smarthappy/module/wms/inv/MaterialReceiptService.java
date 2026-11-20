package io.smarthappy.module.wms.inv;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.production.LotDtos.ReceiveForm;
import io.smarthappy.module.production.LotService;
import io.smarthappy.module.srm.link.AsnQuery;
import io.smarthappy.module.srm.link.PoReceiptGate;
import io.smarthappy.module.srm.link.PoReceiptGate.Receipt;
import io.smarthappy.module.srm.link.PoSupplyQuery;
import io.smarthappy.module.srm.link.PoSupplyQuery.Supply;
import io.smarthappy.module.wms.inv.InvDtos.ItemOpt;
import io.smarthappy.module.wms.inv.InvDtos.NodeOpt;
import io.smarthappy.module.wms.inv.InvDtos.PoLineOpt;
import io.smarthappy.module.wms.inv.InvDtos.PostResult;
import io.smarthappy.module.wms.inv.InvDtos.PrintOpt;
import io.smarthappy.module.wms.inv.InvDtos.RcvForm;
import io.smarthappy.module.wms.inv.InvDtos.RcvLoc;
import io.smarthappy.module.wms.inv.InvDtos.RcvReplay;
import io.smarthappy.module.wms.inv.InvDtos.RcvResult;
import io.smarthappy.module.wms.inv.InvDtos.RcvScan;
import io.smarthappy.module.wms.inv.InvDtos.ScanForm;
import io.smarthappy.module.wms.inv.InvDtos.ScanHit;
import io.smarthappy.module.wms.inv.InvDtos.TrxQuery;
import io.smarthappy.module.wms.inv.InvDtos.TrxReq;
import io.smarthappy.module.wms.inv.InvDtos.TrxRow;
import io.smarthappy.module.wms.label.LabelDtos.PrintItem;
import io.smarthappy.module.wms.label.LabelMapper;
import io.smarthappy.module.wms.label.LabelPrintService;
import io.smarthappy.module.wms.scan.ScanKeyResolver;
import io.smarthappy.module.wms.scan.ScanKeyResolver.BareAs;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 자재 입고 RCV_MAT (P4 2부 B4a, docs/30 12.3) · 구매 입고 RCV_PO (B4b, 12.6) — 들어오는 자재를 위치와 함께 원장에.
 * <ol>
 * <li>요청 id 선점(sys_inbox WMS_RCV_MAT) — 이미 있으면 처음 결과(새 LOT 을 만들지 않는다). 동시에 두 번이면 뒤 요청이 기다렸다가 처음 결과</li>
 * <li>발주 행이 있으면 SRM {@link PoReceiptGate}(발주 단위 수량) — 열린 수량·상태·초과율·환산 판정과 발주 행 입고 수량 투영은 SRM.
 *     품목·공급사는 발주에서, 원장 수량은 게이트가 돌려준 기본 단위 수량</li>
 * <li>P2 {@link LotService#receive} 로 RECEIVED LOT(관리 품목이 아니어도 — 공급사·공급사 LOT 추적, 원장 lot_id 는 관리 품목만, B1 규칙)</li>
 * <li>원장 RCV_MAT 또는 RCV_PO(+, 발주 행·발주 단위 수량), 출처 MAT_RECEIPT·src_id = LOT</li>
 * <li>(선택) LOT 라벨 → 인쇄 대기열(아웃박스)</li>
 * </ol>
 * 잠금: [발주 머리 → 발주 행(게이트)] → 새 LOT INSERT(경합 없음) → 기간 advisory(공유) → inv_stock — docs/30 11.6 고정 조건과 같다.
 */
@Service
@RequiredArgsConstructor
public class MaterialReceiptService {

    static final String PROG = "wms-rcv";
    static final String INBOX = "WMS_RCV_MAT";
    static final int MAX_COPIES = 20;

    private final StockService stock;
    private final InventoryService inventory;
    private final InvMapper mapper;
    private final LotService lots;
    private final LabelPrintService labels;
    private final LabelMapper labelMapper;
    private final InboxService inbox;
    private final PolicyService policy;
    private final BizClock clock;
    private final MessageSource messages;
    private final ObjectProvider<PoReceiptGate> poGate;
    private final ObjectProvider<PoSupplyQuery> poSupply;
    private final ObjectProvider<AsnQuery> asnQuery;
    private final QcReceiptRule qcRule;
    private final PutawayCheck putaway;

    // ================================================================== 선택지·스캔

    @Transactional(readOnly = true)
    public List<PrintOpt> printers(String plantCd) {
        return labelMapper.findPrinters(stock.plant(plantCd), "Y").stream()
                .filter(p -> "ANY".equals(p.getUsage()) || "LOT".equals(p.getUsage()))
                .map(p -> new PrintOpt(p.getPrinterId(), p.getPrinterCd(), p.getPrinterNm())).toList();
    }

    @Transactional(readOnly = true)
    public List<PrintOpt> templates() {
        return labelMapper.findTpls("LOT", "Y").stream().map(t -> new PrintOpt(t.getTplId(), t.getTplCd(), t.getTplNm())).toList();
    }

    /** 오늘 이 공장 자재·구매 입고 원장(최근 50건) */
    @Transactional(readOnly = true)
    public List<TrxRow> recent(String plantCd) {
        LocalDate today = clock.today();
        List<TrxRow> rows = new java.util.ArrayList<>();
        for (String type : List.of("RCV_MAT", "RCV_PO")) {
            rows.addAll(stock.trxs(new TrxQuery(today, today, plantCd, null, null, null, type), 1, 50, null, false).list());
        }
        rows.sort(Comparator.comparingLong(TrxRow::getTrxId).reversed());
        return rows.size() > 50 ? rows.subList(0, 50) : rows;
    }

    /** 이 공장·품목을 지금 받으면 검사 대기(QC)로 고정되는가 — 화면 안내용(저장 때 같은 규칙이 다시 정한다, 12.15.6) */
    @Transactional(readOnly = true)
    public boolean qcFixed(String plantCd, Long itemId) {
        if (itemId == null) {
            return false;
        }
        FieldErrors e = FieldErrors.create();
        ItemOpt item = stock.itemById(itemId, e);
        e.throwIfAny();
        return "QC".equals(qcRule.status(stock.plant(plantCd), item.getItemId(), item.getLotCtrlYn(), "RECEIVE", "AVAILABLE"));
    }

    /** SRM 이 있는 설치본인가 — 없으면 화면이 발주 행 칸을 숨긴다(RCV_MAT 로만 받음) */
    public boolean poEnabled() {
        return poGate.getIfAvailable() != null;
    }

    /** 도착 예정·입고 중 ASN(남은 행이 있는 것) — 늦은 것 포함, 앞으로 30일까지(R3b) */
    @Transactional(readOnly = true)
    public List<AsnQuery.AsnHead> asns(String plantCd) {
        return AsnQuery.of(asnQuery).expected(stock.plant(plantCd), null, clock.today().plusDays(30));
    }

    /** ASN 번호(ASN: 접두어 허용)·협력사 납품서 번호로 하나 — 없으면 404. 다른 공장 ASN 은 칸 오류 */
    @Transactional(readOnly = true)
    public AsnQuery.Asn asn(String plantCd, String key) {
        String plant = stock.plant(plantCd);
        String k = key == null ? "" : key.strip();
        if (k.isEmpty() || k.length() > 80) {
            throw StockService.fieldError("asnKey", k.isEmpty() ? "REQUIRED" : "TOO_LONG", 80);
        }
        AsnQuery.Asn a = AsnQuery.of(asnQuery).find(k).orElseThrow(() -> new BusinessException(HbErrorCode.NOT_FOUND));
        if (!plant.equals(a.head().plantCd())) {
            throw StockService.fieldError("asnKey", "PO_PLANT");
        }
        return a;
    }

    /**
     * 이 공장·품목의 열린 발주 행(SRM {@link PoSupplyQuery} — WMS 는 SRM 표를 읽지 않는다). 약속 줄을 발주 행 하나로 합친다.
     * 잔량 = 발주 단위(입력 수량과 같은 단위 — Supply.openPoQty·poUnitCd)
     */
    @Transactional(readOnly = true)
    public List<PoLineOpt> poLines(String plantCd, Long itemId) {
        if (itemId == null) {
            return List.of();
        }
        Map<Long, PoLineOpt> m = new LinkedHashMap<>();
        for (Supply s : PoSupplyQuery.of(poSupply).open(stock.plant(plantCd), List.of(itemId), null)) {
            m.merge(s.poLineId(), new PoLineOpt(s.poLineId(), s.poNo(), s.lineNo(), mapper.custCdOf(s.custId()), s.supplyDate(), s.openPoQty(),
                    s.poUnitCd()), (a, b) -> new PoLineOpt(a.poLineId(), a.poNo(), a.lineNo(), a.supplierCd(),
                            a.dueDate().isBefore(b.dueDate()) ? a.dueDate() : b.dueDate(), a.openQty().add(b.openQty()), a.unitCd()));
        }
        return List.copyOf(m.values());
    }

    /**
     * 위치·품목은 이동 화면과 같은 판정. GS1 이면 (01) GTIN → 품목 속성 GTIN(D-B10, 없거나 둘 이상이면 품번을 직접),
     * (10) → 공급사 LOT, (17) → 유통기한(저장하지 않음 — D-B9), (30)(37) → 수량. 우리 LOT 라벨은 입고 대상이 아니다
     */
    @Transactional(readOnly = true)
    public RcvScan scan(ScanForm f) {
        String plant = stock.plant(f == null ? null : f.plantCd());
        var r = ScanKeyResolver.resolve(f.text(), new ScanKeyResolver.Context(plant,
                BareAs.valueOf(policy.value("WMS_SCAN_BARE_AS")), clock.today()));
        Locale loc = LocaleContextHolder.getLocale();
        if (r.type() == ScanKeyResolver.Type.GS1 && r.gs1() != null) {
            var g = r.gs1();
            ItemOpt it = g.gtin() == null ? null : mapper.findItemByGtin(g.gtin());
            boolean usable = it != null && "Y".equals(it.getUseYn());
            String m = it == null
                    ? messages.getMessage("wmsRcv.scan.gtinNoItem", new Object[] {g.gtin() == null ? "-" : g.gtin()}, loc)
                    : messages.getMessage(usable ? "wmsRcv.scan.gs1Item" : "wmsScan.lookup.itemNotFound", new Object[] {it.getItemCd()}, loc);
            return new RcvScan("GS1", true, m, null, null, usable ? it.getItemId() : null, usable ? it.getItemCd() : null,
                    usable ? it.getItemNm() : null, usable ? it.getUnitCd() : null, g.batch(), g.expiry(), g.qty(), null);
        }
        if (r.type() == ScanKeyResolver.Type.LOT) {
            return new RcvScan("LOT", false, messages.getMessage("wmsRcv.scan.ourLot", new Object[] {r.key()}, loc), null, null, null, null,
                    null, null, null, null, null, null);
        }
        if (r.type() == ScanKeyResolver.Type.ASN) {
            // SRM 납품서 바코드 — 화면이 ASN 을 열어 행을 고른다(R3b)
            return new RcvScan("ASN", true, messages.getMessage("wmsRcv.scan.asn", new Object[] {r.key()}, loc), null, null, null, null,
                    null, null, null, null, null, r.key());
        }
        ScanHit h = stock.scan(f);
        return new RcvScan(h.type(), h.ok(), h.message(), h.nodeId(), h.path(), h.itemId(), h.itemCd(), h.itemNm(), h.unitCd(), null, null,
                null, null);
    }

    // ================================================================== 입고

    /**
     * PDA 입고 위치 후보(docs/30 12.12.15) — 이 공장 사용 중 WH·LOC 전부(받는 칸 먼저), 못 받는 칸은 사유와 함께. 상태는 저장과 같은 판정
     * (위치 기본 상태, 없으면 AVAILABLE → 수입검사 대상이면 QC). 받을 수 있음 판정 = {@link #rcvNodeReason} — PDA 입고 저장이 같은 메서드로 막는다
     */
    @Transactional(readOnly = true)
    public List<RcvLoc> locations(String plantCd, Long itemId) {
        String plant = stock.plant(plantCd);
        FieldErrors e = FieldErrors.create();
        ItemOpt item = stock.itemById(itemId, e);
        e.throwIfAny();
        Map<String, String> byDefault = new HashMap<>();
        return mapper.rcvNodes(plant).stream().map(n -> {
            String reason = rcvNodeReason(n, n.isUnderScrap());
            String status = reason == null ? byDefault.computeIfAbsent(String.valueOf(n.getDefaultStatus()),
                    k -> qcRule.status(plant, item.getItemId(), item.getLotCtrlYn(), "RECEIVE", defaultStatus(n))) : null;
            return new RcvLoc(n.getNodeId(), n.getNodeCd(), n.getPath(), status, reason);
        }).toList();
    }

    /**
     * 입고(사무·PDA)가 받지 않는 칸 — 재고 안 둠·출하 대기(STAGING — 출하분과 섞지 않음, docs/53 F1·사용자 결정 W-9 2026-11-01)·폐기 위치 아래.
     * null = 받음. 생산 입고(ProductionReceiptPosting.nodeReason)와 같은 STAGING 규칙
     */
    static String rcvNodeReason(NodeOpt n, boolean underScrap) {
        if (!"Y".equals(n.getStockYn())) {
            return "STOCK_NOT_ALLOWED";
        }
        if ("STAGING".equals(n.getLocUsage())) {
            return "STAGING_NOT_ALLOWED";
        }
        return underScrap ? "SCRAP_LOCATION" : null;
    }

    /** PDA 입고의 요청 상태 = 위치 기본 상태(없으면 AVAILABLE) — 저장 때 수입검사 QC 고정이 덮는다 */
    static String defaultStatus(NodeOpt n) {
        return n.getDefaultStatus() == null ? "AVAILABLE" : n.getDefaultStatus();
    }

    @Transactional
    public RcvResult receive(RcvForm f) {
        return receive(f, null, false);
    }

    /**
     * 창고 PDA 입고(docs/30 12.12.15) — 사무 입고와 같은 규칙에 더해 ① 원장 performed_by = 배지 작업자(기록자 = 단말 — InventoryService.me())
     * ② 상태를 고르지 않음(위치 기본 상태). 출하 대기 칸·폐기 위치 거부는 사무와 같다(W-9). 공장·상태·발생 시각은 부르는 쪽(PdaService)이 정해 넣는다
     */
    @Transactional
    public RcvResult receiveByWorker(RcvForm f, long workerId) {
        return receive(f, workerId, true);
    }

    private RcvResult receive(RcvForm f, Long workerId, boolean pda) {
        if (f == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String req = f.clientReqId() == null ? "" : f.clientReqId().strip();
        if (!InventoryService.REQ_ID.matcher(req).matches()) {
            throw StockService.fieldError("clientReqId", req.isEmpty() ? "REQUIRED" : "FORMAT");
        }
        String plant = stock.plant(f.plantCd());
        if (!inbox.firstTime(INBOX, req)) {
            RcvReplay done = mapper.findRcvByReq(req);
            if (done == null) {
                throw new BusinessException(HbErrorCode.DUPLICATE_REQUEST);
            }
            return new RcvResult(done.getTrxId(), done.getLotNo(), true, 0);
        }
        FieldErrors e = FieldErrors.create();
        NodeOpt node = stock.node(f.nodeId(), plant, "nodeId", e);
        if (node != null && mapper.underScrap(node.getNodeId())) {
            e.add("nodeId", "SCRAP_LOCATION");
        } else if (node != null && "Y".equals(node.getStockYn()) && rcvNodeReason(node, false) != null) {   // 출하 대기 칸 — 사무·PDA 같음(W-9)
            e.add("nodeId", rcvNodeReason(node, false));
        }
        boolean po = f.poLineId() != null;
        if (f.asnLineId() != null && !po) {
            e.add("asnLineId", "ASN_NEEDS_PO");                 // ASN 행은 발주 행 입고에만(화면은 ASN 행을 고르면 발주 행도 채운다)
        }
        if (po && !poEnabled()) {
            e.add("poLineId", "PO_UNAVAILABLE");
        }
        // 구매 입고는 품목을 발주 행에서 정한다(게이트 뒤) — 화면이 보낸 품목은 발주 행 품목과 같아야 한다
        ItemOpt item = po ? null : stock.itemById(f.itemId(), e);
        BigDecimal qty = f.qty();
        if (qty == null || qty.signum() <= 0) {
            e.add("qty", "POSITIVE");
        }
        // PDA 는 상태를 고르지 않는다 — 위치 기본 상태(없으면 AVAILABLE), 아래 수입검사 QC 고정이 덮는다(위치 후보 목록과 같은 판정)
        String status = pda && f.stockStatus() == null ? (node == null ? null : defaultStatus(node))
                : StockService.status(f.stockStatus(), "stockStatus", e);
        String remark = StockService.remark(f.remark(), e);
        OffsetDateTime at = stock.occurredAt(f.occurredAt(), plant, e);
        String method = StockService.method(f.entryMethod(), e);
        int copies = f.copies() == null ? 1 : f.copies();
        if (f.printerId() != null) {
            if (copies < 1 || copies > MAX_COPIES) {
                e.add("copies", "RANGE", 1, MAX_COPIES);
            }
            if (printers(plant).stream().noneMatch(p -> p.id() == f.printerId())) {
                e.add("printerId", "NOT_FOUND");
            }
        }
        e.throwIfAny();

        // 구매 입고: 게이트 먼저(발주 머리 → 행 잠금, 원장보다 먼저 — docs/31 4.9). 판정이 실패하면 트랜잭션 전체가 되돌아간다
        Receipt rc = null;
        BigDecimal baseQty = qty;
        String supplierCd = f.supplierCd();
        if (po) {
            rc = PoReceiptGate.of(poGate).receiveAt(f.poLineId(), qty, "RCV_PO:" + req, InventoryService.me().loginId(), at,
                    f.asnLineId());   // ASN 행을 고른 입고면 SRM 이 ASN 받은 양·상태도 같은 트랜잭션에서(R3b)
            if (!plant.equals(rc.plantCd())) {
                throw StockService.fieldError("poLineId", "PO_PLANT");
            }
            if (f.itemId() != null && f.itemId() != rc.itemId()) {
                throw StockService.fieldError("itemId", "PO_ITEM");
            }
            FieldErrors ie = FieldErrors.create();
            item = stock.itemById(rc.itemId(), ie);
            ie.throwIfAny();
            baseQty = rc.baseQty();
            supplierCd = mapper.custCdOf(rc.custId());          // 공급사 = 발주 거래처(화면 값은 쓰지 않는다)
        }
        stock.mixCheck(node, item, "nodeId");
        String warn = putaway.check(plant, node.getNodeId(), item.getItemId());     // 적치 검사(W-12) — BLOCK 이면 여기서 409

        // LOT 생성은 P2 — 공급사·공급사 LOT·수량 자릿수 판정도 그쪽 칸 오류(supplierCd·supplierLotNo·qty)
        String lotAt = f.occurredAt() == null || f.occurredAt().isBlank() ? null
                : LocalDateTime.ofInstant(at.toInstant(), clock.zone()).truncatedTo(ChronoUnit.MINUTES).toString();
        long lotId = lots.receive(new ReceiveForm(item.getItemCd(), baseQty.toPlainString(), supplierCd, f.supplierLotNo(), lotAt, remark));
        if (po) {
            PoReceiptGate.of(poGate).attachLot("RCV_PO:" + req, lotId);   // 받은 LOT 를 SRM 입고 이력에(R4-8 — 반품 출고가 이 입고를 찾는다)
        }
        boolean lotCtrl = "Y".equals(item.getLotCtrlYn());
        status = qcRule.status(plant, item.getItemId(), item.getLotCtrlYn(), "RECEIVE", status);     // 수입검사 대상이면 QC 고정(12.15.6)
        TrxReq row = new TrxReq(po ? "RCV_PO" : "RCV_MAT", item.getItemId(), node.getNodeId(), status, baseQty, null,
                "MAT_RECEIPT", lotId, null, remark, at, method, workerId, lotCtrl ? lotId : null);
        PostResult posted = inventory.postReceipt(List.of(po ? row.withPo(rc.poLineId(), qty) : row), req);
        String lotNo = mapper.lotNoOf(lotId);
        int jobs = 0;
        if (f.printerId() != null) {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("lotNo", lotNo);
            v.put("itemCd", item.getItemCd());
            v.put("itemNm", item.getItemNm());
            v.put("qty", baseQty.stripTrailingZeros().toPlainString());
            v.put("unitCd", item.getUnitCd());
            v.put("expiry", f.expiry() == null ? "" : f.expiry().strip());
            v.put("supplierLotNo", f.supplierLotNo() == null ? "" : f.supplierLotNo().strip());
            v.put("barcode", "LOT:" + lotNo);       // ScanKeyResolver 내부 형식
            jobs = labels.request("LOT", f.tplId(), f.printerId(), copies,
                    List.of(new PrintItem(v.get("barcode"), v, null, item.getItemId()))).size();   // 품목 규칙 문맥(docs/65 18.4)
        }
        return new RcvResult(posted.trxIds().get(0), lotNo, false, jobs, PutawayCheck.warnings(warn));
    }
}
