package io.smarthappy.module.wms.inv;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.qms.link.NcrPort;
import io.smarthappy.module.qms.link.NcrPort.ClaimRequest;
import io.smarthappy.module.qms.link.NcrPort.NcrView;
import io.smarthappy.module.wms.inv.CustRtnDtos.CancelForm;
import io.smarthappy.module.wms.inv.CustRtnDtos.CreateForm;
import io.smarthappy.module.wms.inv.CustRtnDtos.NodeOpt;
import io.smarthappy.module.wms.inv.CustRtnDtos.Rtn;
import io.smarthappy.module.wms.inv.CustRtnDtos.Source;
import io.smarthappy.module.wms.inv.InvDtos.TrxReq;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 고객 반품 입고 (docs/59, 사용자 결정 R-1~R-8 2026-11-01).
 *
 * <ul>
 * <li>근거 = 출하 행 × LOT(R-7 — 근거 없는 반품은 받지 않는다). 받을 수 있는 양 = 순 출하(SHIP 원장) − 살아 있는 반품 — 넘으면 409</li>
 * <li>같은 LOT 으로 받는다(새 LOT 없음 — 계보·리콜 유지). 원장 RCV_RTN(+) 상태 <b>BLOCKED</b> 고정(판정 전 가용 금지)</li>
 * <li>LOT 관리 품목이면 QMS {@link NcrPort#createForClaim}(R-2)으로 클레임 NCR — 처분(USE_AS_IS·SCRAP)은 QMS NCR 종결 → 기존
 *     QualityStockLink.onNcrClosed 가 BLOCKED 재고를 풀거나 폐기. LOT 비관리 품목은 NCR 없음(QMS NCR 은 LOT 필수) — BLOCKED 로만</li>
 * <li>잠금: 출하 머리 FOR SHARE(출하 취소와 줄 세움) → 출하 행 advisory(같은 행 반품끼리) → 반품 INSERT → NCR(→ MES LOT 보류)
 *     → 원장(LOT FOR SHARE·칸). NCR 을 원장보다 먼저 — 원장이 LOT 을 FOR SHARE 로 쥔 뒤 보류(UPDATE)로 올리면 같은 LOT 의 다른 반품과 교착</li>
 * <li>취소(R-6) = NCR 이 OPEN 일 때만(NcrPort.cancelForClaim, 아니면 409 QMS_NCR_STATE) → 원장 역분개(출처 행 — 수불 원장 화면에서는 못 되돌림)</li>
 * <li>영업 출하 수량은 바꾸지 않는다(R-3), ERP 송신 없음(R-4 후속)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CustRtnService {

    public static final String PROG = "wms-cust-rtn";
    static final String SRC = "CUST_RTN";
    static final String TYPE = "RCV_RTN";
    static final String RULE = "WMS_CUST_RTN";
    /** 반품 사유 = 공통코드 WMS_CUST_RTN_REASON(시스템 4종 + 회사 코드, docs/68) */
    static final String REASON_GRP = "WMS_CUST_RTN_REASON";
    static final Set<String> STATUSES = Set.of("RECEIVED", "CANCELLED");
    static final int SOURCE_MAX = 200;
    static final int LIST_MAX = 500;
    static final int QTY_SCALE = 3;
    static final Pattern CODE = Pattern.compile("^[\\x21-\\x7E]([\\x20-\\x7E]*[\\x21-\\x7E])?$");

    private final CustRtnMapper mapper;
    private final InvMapper invMapper;
    private final StockService stock;
    private final CodeService codes;
    private final InventoryService inventory;
    private final NumberingService numbering;
    private final BizClock clock;
    private final ObjectProvider<NcrPort> ncrPort;
    private final CustRtnErpPublisher erp;
    private final PutawayCheck putaway;

    // ================================================================== 조회

    /** 근거 찾기 — 출하 번호·LOT 번호·고객 코드 중 하나는 있어야(고객이면 출하 기간 from~to, 공장 날짜) */
    @Transactional(readOnly = true)
    public List<Source> sources(String plantCd, String shipNo, String lotNo, String custCd, String from, String to) {
        String plant = stock.plant(plantCd);
        String ship = blankToNull(shipNo);
        String lot = blankToNull(lotNo);
        if (lot != null && lot.regionMatches(true, 0, "LOT:", 0, 4)) {
            lot = lot.substring(4).strip();             // 스캔 키 LOT:{번호}
        }
        String cust = blankToNull(custCd);
        if (ship == null && lot == null && cust == null) {
            throw StockService.fieldError("shipNo", "REQUIRED");
        }
        ZoneId zone = clock.zone(plant);
        OffsetDateTime fromAt = day(from, "from", zone, 0);
        OffsetDateTime toAt = day(to, "to", zone, 1);
        return mapper.sources(plant, ship, lot, cust == null ? null : cust.toUpperCase(java.util.Locale.ROOT), fromAt, toAt, SOURCE_MAX);
    }

    /** 받는 칸 후보 — 사무 입고와 같은 판정(재고 안 둠·출하 대기·폐기 위치 아래 제외), QC 용도 칸 먼저(R-5), 그다음 경로 */
    @Transactional(readOnly = true)
    public List<NodeOpt> nodes(String plantCd) {
        String plant = stock.plant(plantCd);
        return invMapper.rcvNodes(plant).stream()
                .filter(n -> MaterialReceiptService.rcvNodeReason(n, n.isUnderScrap()) == null)
                .sorted(Comparator.comparing((InvDtos.RcvNode n) -> "QC".equals(n.getLocUsage()) ? 0 : 1).thenComparing(InvDtos.RcvNode::getPath))
                .map(n -> new NodeOpt(n.getNodeId(), n.getPath(), n.getLocUsage())).toList();
    }

    @Transactional(readOnly = true)
    public List<Rtn> list(String plantCd, String status, String from, String to) {
        String plant = stock.plant(plantCd);
        String st = blankToNull(status);
        if (st != null && !STATUSES.contains(st)) {
            throw StockService.fieldError("status", "CODE");
        }
        ZoneId zone = clock.zone(plant);
        return withNcr(mapper.list(plant, st, day(from, "from", zone, 0), day(to, "to", zone, 1), LIST_MAX));
    }

    @Transactional(readOnly = true)
    public Rtn view(long rtnId) {
        Rtn r = mapper.find(rtnId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return withNcr(List.of(r)).get(0);
    }

    // ================================================================== 반품 입고

    @Transactional
    public Rtn receive(CreateForm f) {
        return receive(f, null);
    }

    /** performedBy = 실제 작업자(PDA 등, 지금은 사무 화면만이라 null) */
    @Transactional
    public Rtn receive(CreateForm f, Long performedBy) {
        if (f == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String req = f.clientReqId() == null ? "" : f.clientReqId().strip();
        if (!InventoryService.REQ_ID.matcher(req).matches()) {
            throw StockService.fieldError("clientReqId", req.isEmpty() ? "REQUIRED" : "FORMAT");
        }
        String plant = stock.plant(f.plantCd());
        FieldErrors e = FieldErrors.create();
        if (f.shipLineId() == null) {
            e.add("shipLineId", "REQUIRED");
        }
        BigDecimal qty = f.qty();
        if (qty == null || qty.signum() <= 0) {
            e.add("qty", "POSITIVE");
        } else if (qty.stripTrailingZeros().scale() > QTY_SCALE) {
            e.add("qty", "SCALE", QTY_SCALE);
        }
        String reason = f.reasonCd() == null ? null : f.reasonCd().strip();
        if (reason == null || reason.isEmpty()) {
            e.add("reasonCd", "REQUIRED");
        } else if (!codes.usable(REASON_GRP, reason)) {
            e.add("reasonCd", "CODE");
        }
        InvDtos.NodeOpt node = stock.node(f.nodeId(), plant, "nodeId", e);
        if (node != null && "Y".equals(node.getStockYn())) {
            String bad = MaterialReceiptService.rcvNodeReason(node, invMapper.underScrap(node.getNodeId()));
            if (bad != null) {
                e.add("nodeId", bad);                       // 출하 대기·폐기 위치 — 입고와 같은 규칙(W-9)
            }
        }
        String remark = StockService.remark(f.remark(), e);
        String capa = blankToNull(f.capaNo());
        if (capa != null && (capa.length() > 40 || !CODE.matcher(capa).matches())) {
            e.add("capaNo", capa.length() > 40 ? "TOO_LONG" : "FORMAT", 40);
        }
        String method = StockService.method(f.entryMethod(), e);
        e.throwIfAny();

        // 근거(출하 행 × LOT) — 다른 공장·다른 회사·없는 것은 "없음"
        Source s = mapper.source(plant, f.shipLineId(), f.lotId());
        if (s == null) {
            throw StockService.fieldError("shipLineId", "NOT_FOUND");
        }
        // 잠금: 출하 머리(공유) → 출하 행 advisory — 그 뒤 같은 요청·남은 양을 다시 본다
        mapper.lockShipShare(s.getShipId());
        mapper.lockShipLine(TenantContext.requireCompCd() + ":" + s.getShipLineId());
        Rtn done = mapper.findByReq(req);
        if (done != null) {
            if (done.getShipLineId() != s.getShipLineId() || !Objects.equals(done.getLotId(), s.getLotId())
                    || done.getQty().compareTo(qty) != 0 || done.getNodeId() != node.getNodeId() || !reason.equals(done.getReasonCd())) {
                throw new BusinessException(HbErrorCode.DUPLICATE_REQUEST);       // 같은 요청 id·다른 내용(PQ-1 규칙 — 칸·사유까지, QA CR-d)
            }
            return view(done.getRtnId());                                         // 같은 요청 다시 = 처음 결과
        }
        Source now = mapper.source(plant, s.getShipLineId(), s.getLotId());
        BigDecimal open = now == null ? BigDecimal.ZERO : now.getOpenQty();
        if (qty.compareTo(open) > 0) {
            throw new BusinessException(HbErrorCode.WMS_CUST_RTN_OVER, text(open), s.getUnitCd());
        }
        String warn = putaway.check(plant, node.getNodeId(), now.getItemId());    // 적치 검사(W-12) — 받는 칸. BLOCK 이면 여기서 409
        LoginUser me = InventoryService.me();
        String rtnNo = numbering.nextOrDefault(RULE, "고객 반품", "CRT-{yyMMdd}-{seq:3}", "DAY");
        long rtnId = mapper.insert(rtnNo, now, qty, reason, node.getNodeId(), remark, capa, req, performedBy, me.loginId());
        // 클레임 NCR(R-2) — 원장보다 먼저(LOT 보류 UPDATE 를 LOT FOR SHARE 보다 앞에). LOT 비관리 품목은 NCR 없음
        if (now.getLotId() != null) {
            NcrPort.of(ncrPort).createForClaim(new ClaimRequest(NcrPort.WMS_CUST_RTN, rtnId, rtnNo, now.getLotId(), qty,
                    claimText(rtnNo, now, reason, remark), me.loginId()))
                    .ifPresent(n -> mapper.setNcr(rtnId, n.ncrId(), n.ncrNo()));
        }
        boolean lotCtrl = "Y".equals(now.getLotCtrlYn());
        OffsetDateTime at = OffsetDateTime.now(clock.zone(plant));
        TrxReq row = new TrxReq(TYPE, now.getItemId(), node.getNodeId(), "BLOCKED", qty, null, SRC, rtnId, null,
                remark == null ? rtnNo : remark, at, method, performedBy, lotCtrl ? now.getLotId() : null);
        // 원장 멱등 키 = 같은 요청 id(반품 행 unique 와 짝). 같은 반품의 다시 보냄은 위 findByReq 가 먼저 돌려주므로,
        // 여기서 replay 면 다른 원장 행(출하·이동 등)이 쓰던 id — 원장 없이 반품·NCR·LOT 보류만 남지 않게 전체 롤백(QA CR-a)
        if (inventory.post(List.of(row), req).replay()) {
            throw new BusinessException(HbErrorCode.DUPLICATE_REQUEST);
        }
        Rtn v = view(rtnId);
        erp.publish(v, 1, at);                           // ERP 반품 전표(R-4) — 어댑터 없으면 아무것도 안 함
        v.setWarnings(PutawayCheck.warnings(warn));
        return v;
    }

    // ================================================================== 취소 (R-6)

    @Transactional
    public Rtn cancel(long rtnId, CancelForm f) {
        if (f == null || f.rowVer() == null) {
            throw StockService.fieldError("rowVer", "REQUIRED");
        }
        String reason = f.reason() == null ? "" : f.reason().strip();
        if (reason.length() < 2 || reason.length() > 500) {
            throw StockService.fieldError("reason", reason.length() < 2 ? "REQUIRED" : "TOO_LONG");
        }
        Rtn r = mapper.lock(rtnId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (r.getRowVer() != f.rowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (!"RECEIVED".equals(r.getStatus())) {
            throw new BusinessException(HbErrorCode.WMS_CUST_RTN_STATE);
        }
        String by = InventoryService.me().loginId();
        // 잠금: 반품 → NCR(OPEN 이 아니면 409 — 처분이 시작된 재고는 되돌리지 않는다) → LOT 보류 해제 → 원장 역분개
        if (r.getNcrId() != null) {
            NcrPort.of(ncrPort).cancelForClaim(NcrPort.WMS_CUST_RTN, rtnId, reason, by);
        }
        List<Long> live = mapper.liveTrxOf(rtnId);
        if (!live.isEmpty()) {
            inventory.reverseFromSource(live, reason, null, null, by);
        }
        if (mapper.markCancelled(rtnId, reason, by) != 1) {
            throw new BusinessException(HbErrorCode.WMS_CUST_RTN_STATE);
        }
        Rtn v = view(rtnId);
        erp.publish(v, -1, OffsetDateTime.now(clock.zone(v.getPlantCd())));     // ERP 음수 전표(출하 취소와 같은 방식)
        return v;
    }

    // ================================================================== 공통

    /** NCR 상태를 QMS 계약으로 채운다(QMS 없으면 그대로 null) */
    private List<Rtn> withNcr(List<Rtn> rows) {
        List<Long> ids = rows.stream().map(Rtn::getNcrId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return rows;
        }
        Map<Long, String> st = NcrPort.of(ncrPort).ncrStatus(ids).stream()
                .collect(Collectors.toMap(NcrView::ncrId, NcrView::status, (a, b) -> a));
        rows.forEach(r -> {
            if (r.getNcrId() != null) {
                r.setNcrStatus(st.get(r.getNcrId()));
            }
        });
        return rows;
    }

    private static String claimText(String rtnNo, Source s, String reason, String remark) {
        String t = "고객 반품 " + rtnNo + " · " + s.getCustCd() + " · 출하 " + s.getShipNo() + " · 사유 " + reason
                + (remark == null ? "" : " · " + remark);
        return t.length() > 2000 ? t.substring(0, 2000) : t;
    }

    /** 공장 날짜(yyyy-MM-dd) → 그날 0시(plusDays = 1 이면 다음 날 0시 — 끝 날짜 포함) */
    private static OffsetDateTime day(String v, String field, ZoneId zone, int plusDays) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(v.strip()).plusDays(plusDays).atStartOfDay(zone).toOffsetDateTime();
        } catch (DateTimeParseException ex) {
            throw StockService.fieldError(field, "FORMAT");
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static String text(BigDecimal v) {
        return v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
