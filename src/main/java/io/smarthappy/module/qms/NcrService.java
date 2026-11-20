package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.module.production.link.LotHoldPort;
import io.smarthappy.module.qms.link.NcrPort;
import io.smarthappy.module.qms.DocDtos.Approval;
import io.smarthappy.module.qms.DocDtos.AprLineRow;
import io.smarthappy.module.qms.DocDtos.LineItem;
import io.smarthappy.module.qms.NcrDtos.CancelForm;
import io.smarthappy.module.qms.NcrDtos.CreateForm;
import io.smarthappy.module.qms.NcrDtos.DefectOption;
import io.smarthappy.module.qms.NcrDtos.DispLine;
import io.smarthappy.module.qms.NcrDtos.DispRow;
import io.smarthappy.module.qms.NcrDtos.InspRef;
import io.smarthappy.module.qms.NcrDtos.LotRef;
import io.smarthappy.module.qms.NcrDtos.NcrDetail;
import io.smarthappy.module.qms.NcrDtos.NcrPage;
import io.smarthappy.module.qms.NcrDtos.NcrRow;
import io.smarthappy.module.qms.NcrDtos.NcrSort;
import io.smarthappy.module.qms.NcrDtos.NcrSubmitForm;
import io.smarthappy.module.qms.NcrDtos.SaveForm;
import io.smarthappy.module.wms.link.QualityStockLink;
import io.smarthappy.module.wms.link.StockQuery;
import io.smarthappy.platform.approval.ApprovalService.LineSpec;
import io.smarthappy.platform.approval.ApprovalService.LineType;
import io.smarthappy.platform.approval.ApprovalService;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.esign.ESignService;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.time.BizClock;

/**
 * 부적합(NCR)·처분(MRB) (qms-ncr) — P3 QMS 2부 Q8a, docs/29_P3 12.7.
 *
 * - 등록: 정책 QMS_NCR_AUTO_HOLD 가 Y 면 MES LotHoldPort 로 LOT 보류(출처 QMS_NCR, 참조 = ncr_id).
 *   잠금 순서 근거 검사(FOR SHARE) → LOT → qms_ncr — 근거 검사를 먼저 잠그고 JUDGED·같은 LOT 을 확인(검사 무효와 같은 순서 검사 → LOT,
 *   거꾸로면 교착이고 잠그지 않으면 무효로 커밋되는 검사에 NCR 이 붙는다), ncr_id 를 시퀀스에서 먼저 받고 → 포트 hold(LOT FOR UPDATE) → INSERT
 * - OPEN 동안 머리 내용·처분 행(통째 바꾸기)을 고친다. 종결·상신은 처분 수량 합 = 부적합 수량일 때만
 * - 특채(USE_AS_IS)가 있으면 결재(QMS_NCR)로만 종결 — 승인 = 종결 + 해제, 반려·회수 = OPEN(NcrApprovalDocType)
 * - 종결·취소·승인 모두 포트 release(LOT) 먼저 → qms_ncr UPDATE
 */
@Service
public class NcrService {

    static final String PROG = "qms-ncr";
    static final String RULE = "QMS_NCR";
    static final int LIST_LIMIT = 500;
    static final int MAX_DISPS = 20;
    static final BigDecimal MAX_QTY = new BigDecimal("1000000000");
    static final Set<String> SOURCES = Set.of("INSP", "SETUP", "PROCESS", "CLAIM", "OTHER");
    static final Set<String> DISP_TYPES = Set.of("USE_AS_IS", "REWORK", "SCRAP", "RETURN", "SORT");
    static final String USE_AS_IS = "USE_AS_IS";
    static final List<String> DEFAULT_SORT = List.of("createdAt,desc");

    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("ncrNo", "qms.ncr.col.ncrNo", 120).withSort().withPriority(1),
            ColumnSpec.code("status", "qms.ncr.col.status", "QMS_NCR_STATUS", 110).withSort().withPriority(1),
            ColumnSpec.code("source", "qms.ncr.col.source", "QMS_NCR_SOURCE", 100).withSort().withPriority(2),
            ColumnSpec.text("lotNo", "qms.insp.col.lotNo", 130).withSort().withPriority(1),
            ColumnSpec.text("defectNm", "qms.ncr.col.defect", 140).withFlex().withMinWidth(100).withPriority(2),
            ColumnSpec.labels("srcType", "qms.ncr.col.basis", "qms.ncr.basis.", 90, "WMS_CUST_RTN").withPriority(3),
            ColumnSpec.dec("ncQty", "qms.ncr.col.qty", 3, 80).withMinScale(0).withPriority(2),
            ColumnSpec.text("unitCd", "qms.ncr.col.unit", 50).withPriority(3),
            ColumnSpec.of("createdAt", "qms.ncr.col.created", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(2),
            ColumnSpec.text("createdBy", "qms.ncr.col.createdBy", 90).withPriority(3));

    private final NcrMapper mapper;
    private final DocMapper docMapper;
    private final ApprovalService approvals;
    private final NumberingService numbering;
    private final InboxService inbox;
    private final PolicyService policies;
    private final BizClock clock;
    private final ObjectProvider<LotHoldPort> holdPort;
    private final QmsESign esign;
    /** WMS 품질 상태 연동(B3) — 종결 끝에 처분 수량 반영, 409 는 잡지 않는다(종결이 같이 거부) */
    private final ObjectProvider<QualityStockLink> stockLink;
    /** NCR 상세의 BLOCKED 초과 경고(B-2) — 읽기만 */
    private final ObjectProvider<StockQuery> stocks;

    public NcrService(NcrMapper mapper, DocMapper docMapper, ApprovalService approvals, NumberingService numbering, InboxService inbox,
            PolicyService policies, BizClock clock, ObjectProvider<LotHoldPort> holdPort, QmsESign esign,
            ObjectProvider<QualityStockLink> stockLink, ObjectProvider<StockQuery> stocks) {
        this.mapper = mapper;
        this.docMapper = docMapper;
        this.approvals = approvals;
        this.numbering = numbering;
        this.inbox = inbox;
        this.policies = policies;
        this.clock = clock;
        this.holdPort = holdPort;
        this.esign = esign;
        this.stockLink = stockLink;
        this.stocks = stocks;
    }

    @Transactional(readOnly = true)
    public List<NcrRow> list(String keyword, String status, String source, String basis) {
        String kw = VisibleText.isBlank(keyword) ? null : "%" + BomService.likeEscape(keyword.strip()) + "%";
        return mapper.findList(kw, statuses(status), VisibleText.isBlank(source) ? null : source, blankNull(basis), LIST_LIMIT);
    }

    /** 근거 칸(39장) — CUST_RTN·NO_DEFECT, 비면 전체 */
    private static String blankNull(String v) {
        return VisibleText.isBlank(v) ? null : v.strip();
    }

    /** OPEN_ALL = 열린 것(처리 중 + 특채 결재 중) — 홈 "열린 부적합" 숫자와 같은 범위 */
    private static List<String> statuses(String status) {
        return VisibleText.isBlank(status) ? null : "OPEN_ALL".equals(status) ? List.of("OPEN", "PENDING") : List.of(status);
    }

    /** 그리드(UI 단계 3, 결정 #45) — 목록과 같은 조건 + 쪽·정렬, 기본 = 등록 최근 */
    @Transactional(readOnly = true)
    public NcrPage grid(String keyword, String status, String source, String basis, Integer page, Integer size, List<String> sort,
            Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, NcrSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        String kw = VisibleText.isBlank(keyword) ? null : "%" + BomService.likeEscape(keyword.strip()) + "%";
        List<String> statuses = statuses(status);
        String src = VisibleText.isBlank(source) ? null : source;
        String b = blankNull(basis);
        return new NcrPage(mapper.findPage(kw, statuses, src, b, pq), pq.isWithTotal() ? mapper.countList(kw, statuses, src, b) : null);
    }

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    @Transactional(readOnly = true)
    public List<DefectOption> defects() {
        return mapper.findDefects();
    }

    @Transactional(readOnly = true)
    public NcrDetail detail(long id) {
        LoginUser me = SamplingService.me();
        NcrRow n = find(id);
        Approval apr = new Approval(null, null, false, false, List.of());
        if (n.getAprId() != null && "PENDING".equals(n.getStatus())) {
            List<AprLineRow> lines = docMapper.findAprLines(n.getAprId());
            boolean pending = "PENDING".equals(n.getAprStatus());
            boolean myTurn = pending && lines.stream().anyMatch(l -> "CURRENT".equals(l.getStatus()) && l.getApproverId() == me.userId());
            boolean anyActed = lines.stream().anyMatch(l -> "APPROVED".equals(l.getStatus()));
            boolean canWithdraw = pending && !anyActed && Objects.equals(n.getAprRequesterId(), me.userId());
            apr = new Approval(n.getAprId(), n.getAprStatus(), myTurn, canWithdraw, lines);
        }
        List<DispRow> disps = mapper.findDisps(id);
        String hash = ESignService.hash(QmsESign.ncr(n, disps));
        return new NcrDetail(n, disps, LotHoldPort.of(holdPort).holds(n.getLotId()), apr, policies.bool(Policies.QMS_NCR_AUTO_HOLD),
                esign.required(), esign.list(QmsESign.NCR, id).stream().map(sg -> QmsESign.SignView.of(sg, hash)).toList(),
                mapper.findCapas(id), blockedQty(n));
    }

    /**
     * 처리 중 NCR 의 LOT 부적합 재고 — NG 판정 뒤 같은 LOT 에 더 들어온 생산 입고는 WMS 가 BLOCKED 로 이어받으므로(docs/30 12.15.13)
     * 부적합 수량보다 커질 수 있다. 화면이 "BLOCKED n > 부적합 m" 경고(종결은 막지 않음 — 처분 합 = 부적합 수량 규칙 그대로).
     * 단위가 다르면(LOT 단위 ≠ 기본 단위) 비교하지 않는다
     */
    private BigDecimal blockedQty(NcrRow n) {
        if (!("OPEN".equals(n.getStatus()) || "PENDING".equals(n.getStatus())) || !mapper.lotBaseUnit(n.getLotId())) {
            return null;
        }
        return StockQuery.of(stocks).lotStatusQty(n.getLotId()).get("BLOCKED");
    }

    /** 등록 — 정책 Y 면 LOT 보류(포트) 뒤 INSERT */
    @Transactional
    public long create(CreateForm in) {
        return create(in, null);
    }

    /**
     * 지난 부적합을 그 발생 시각으로 — 데모 이력 시더(QmsDemoFlow) 전용, API 없음. 검증·보류는 {@link #create} 와 같고 발생 시각(occurred_at)만 받는다
     * (등록 기록 created_at 은 지금). 지금보다 뒤면 거부
     */
    @Transactional
    public long createAt(CreateForm in, OffsetDateTime at) {
        if (at == null || at.toInstant().isAfter(clock.instant())) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return create(in, at);
    }

    private long create(CreateForm in, OffsetDateTime at) {
        LoginUser me = SamplingService.me();
        inbox.claimRequestKey("QMS_NCR_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        LotRef lot = null;
        if (VisibleText.isBlank(in.lotNo())) {
            e.add("lotNo", "REQUIRED");
        } else if ((lot = mapper.findLot(in.lotNo().strip())) == null) {
            e.add("lotNo", "NOT_FOUND");
        }
        if (in.source() == null) {
            e.add("source", "REQUIRED");
        } else if (!SOURCES.contains(in.source())) {
            e.add("source", "INVALID");
        }
        Long inspId = null;
        if (!VisibleText.isBlank(in.inspNo())) {
            InspRef insp = mapper.findInspByNo(in.inspNo().strip());
            if (insp == null) {
                e.add("inspNo", "NOT_FOUND");
            } else if (lot != null && (insp.getLotId() != lot.getLotId() || !"JUDGED".equals(insp.getStatus()))) {
                e.add("inspNo", "NCR_INSP");
            } else {
                inspId = insp.getInspId();
            }
        }
        defect(in.defectId(), e);
        qty(in.ncQty(), "ncQty", e);
        String desc = VisibleText.reason(in.description(), "description", 1, 2000, e);
        e.throwIfAny();
        boolean hold = policies.bool(Policies.QMS_NCR_AUTO_HOLD);
        long id = mapper.nextId();
        String no = numbering.nextOrDefault(RULE, "부적합 번호", "NC{yyMM}-{seq:4}", "MONTH");
        if (hold) {
            hold(lot.getLotId(), id, "부적합 " + no, me.loginId());
        }
        mapper.insert(id, no, in.source(), lot, inspId, in.defectId(), in.ncQty(), desc, at == null ? clock.now().toOffsetDateTime() : at,
                hold ? "Y" : "N", me.loginId());
        return id;
    }

    /** OPEN 동안 머리 내용 + 처분 행 통째 저장 — 머리를 먼저 잠근다(처분 행 트리거와 같은 순서) */
    @Transactional
    public void save(long id, SaveForm in) {
        LoginUser me = SamplingService.me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        defect(in.defectId(), e);
        qty(in.ncQty(), "ncQty", e);
        String desc = VisibleText.reason(in.description(), "description", 1, 2000, e);
        List<DispLine> disps = in.disps() == null ? List.of() : in.disps();
        if (disps.size() > MAX_DISPS) {
            e.add("disps", "TOO_MANY", MAX_DISPS);
        }
        List<String[]> clean = new ArrayList<>();
        for (DispLine d : disps) {
            FieldErrors.RowScope r = e.row(d == null || d.rowKey() == null ? "" : d.rowKey());
            if (d == null) {
                e.add("disps", "INVALID");
                clean.add(null);
                continue;
            }
            if (d.dispType() == null) {
                r.add("dispType", "REQUIRED");
            } else if (!DISP_TYPES.contains(d.dispType())) {
                r.add("dispType", "INVALID");
            }
            if (d.qty() == null) {
                r.add("qty", "REQUIRED");
            } else if (d.qty().signum() <= 0 || d.qty().compareTo(MAX_QTY) > 0 || d.qty().stripTrailingZeros().scale() > 3) {
                r.add("qty", "INVALID");
            }
            String note = VisibleText.strip(d.note());
            if (note != null && note.length() > 500) {
                r.add("note", "TOO_LONG", 500);
            }
            String rework = d.reworkLotNo() == null || d.reworkLotNo().isBlank() ? null : d.reworkLotNo().strip();
            if (rework != null && !"REWORK".equals(d.dispType())) {
                r.add("reworkLotNo", "NCR_REWORK_ONLY");
            } else if (rework != null && rework.length() > 50) {
                r.add("reworkLotNo", "TOO_LONG", 50);
            }
            clean.add(new String[] {d.dispType(), note, rework});
        }
        // 처분 합 ≤ 부적합 수량(R3-c, 47장) — OPEN 동안 저장도. 같음은 상신·종결 때(sumCheck)
        BigDecimal dispSum = disps.stream().filter(d -> d != null && d.qty() != null && d.qty().signum() > 0).map(DispLine::qty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (in.ncQty() != null && dispSum.compareTo(in.ncQty()) > 0) {
            e.add("disps", "NCR_DISP_OVER");
        }
        e.throwIfAny();
        NcrRow n = mapper.lockById(id);
        if (n == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"OPEN".equals(n.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_NCR_STATE);
        }
        // 클레임 NCR 부적합 수량 ≤ 반품 수량(R3-c) — 넘으면 WMS 종결 반영이 다른 반품 칸에서 가져간다
        if (n.getSrcQty() != null && in.ncQty().compareTo(n.getSrcQty()) > 0) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(
                    FieldErrors.create().add("ncQty", "NCR_CLAIM_QTY", n.getSrcQty().stripTrailingZeros().toPlainString()).list());
        }
        // 처분 행을 먼저 지운다 — 머리 트리거가 "부적합 수량 < 지금 처분 합" 줄이기를 막으므로(V236)
        mapper.deleteDisps(id);
        if (mapper.updateHead(id, in.defectId(), in.ncQty(), desc, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        int lineNo = 0;
        for (int i = 0; i < disps.size(); i++) {
            String[] c = clean.get(i);
            mapper.insertDisp(id, ++lineNo, c[0], disps.get(i).qty(), c[1], c[2], me.loginId());
        }
    }

    /** 결재 없이 종결 — 특채가 있으면 결재로만. 서명(승인) → 포트 해제(LOT) → qms_ncr */
    @Transactional
    public void close(long id, Integer rowVer, String esignToken) {
        LoginUser me = SamplingService.me();
        if (rowVer == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        NcrRow n = openChecked(id, rowVer);
        List<DispRow> disps = mapper.findDisps(id);
        if (disps.stream().anyMatch(d -> USE_AS_IS.equals(d.getDispType()))) {
            throw new BusinessException(HbErrorCode.QMS_NCR_APPROVAL_REQUIRED);
        }
        sumCheck(n, disps);
        esign.sign(esignToken, QmsESign.APPROVE, QmsESign.NCR, id, QmsESign.ncr(n, disps));
        release(n, "부적합 종결 " + n.getNcrNo(), me.loginId());
        if (mapper.close(id, "OPEN", rowVer, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        // WMS 가 읽는 qms_ncr 이 CLOSED 가 된 뒤. LOT 은 위 해제(포트)가 이미 FOR UPDATE 로 쥠 — WMS 의 공유 잠금은 그 위에서
        QualityStockLink.of(stockLink).onNcrClosed(id);
    }

    /** 특채 결재 상신 — 처분에 특채가 있고 수량 합이 맞을 때만 */
    @Transactional
    public long submit(long id, NcrSubmitForm in) {
        LoginUser me = SamplingService.me();
        inbox.claimRequestKey("QMS_NCR_SUBMIT");
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        NcrRow n = openChecked(id, in.rowVer());
        List<DispRow> disps = mapper.findDisps(id);
        if (disps.stream().noneMatch(d -> USE_AS_IS.equals(d.getDispType()))) {
            throw new BusinessException(HbErrorCode.QMS_NCR_STATE);
        }
        sumCheck(n, disps);
        esign.sign(in.esignToken(), QmsESign.AUTHOR, QmsESign.NCR, id, QmsESign.ncr(n, disps));
        List<LineSpec> lines = new ArrayList<>();
        for (LineItem l : in.lines() == null ? List.<LineItem>of() : in.lines()) {
            LineType type;
            try {
                type = l == null || l.userId() == null ? null : LineType.valueOf(l.lineType() == null ? "APPROVE" : l.lineType());
            } catch (IllegalArgumentException ex) {
                type = null;
            }
            if (type == null) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            lines.add(new LineSpec(l.userId(), type));
        }
        String title = VisibleText.isBlank(in.title()) ? n.getNcrNo() + " 특채 " + n.getLotNo() : in.title().strip();
        long aprId = approvals.submit(NcrApprovalDocType.DOC_TYPE, String.valueOf(id), title.length() > 200 ? title.substring(0, 200) : title,
                lines);
        if (mapper.submit(id, aprId, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return aprId;
    }

    @Transactional
    public void cancel(long id, CancelForm in) {
        LoginUser me = SamplingService.me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String reason = VisibleText.reason(in.reason(), "reason", 1, 500, e);
        e.throwIfAny();
        NcrRow n = openChecked(id, in.rowVer());
        // 출처가 있는 NCR 은 출처 쪽(반품 취소 → NcrPort.cancelForClaim)으로만 — 여기서 취소하면 반품·BLOCKED 재고가 막다른 상태로 남는다(43장 CR-b)
        if (n.getSrcType() != null) {
            throw new BusinessException(HbErrorCode.QMS_NCR_SOURCE_OWNED);
        }
        release(n, cut("부적합 취소 " + n.getNcrNo() + ": " + reason), me.loginId());
        if (mapper.cancel(id, reason, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    // ------------------------------------------------------------------ 계약 NcrPort (docs/29_P3 38장)

    /**
     * 클레임 NCR(계약) — 같은 근거는 advisory 로 줄 세운 뒤 있으면 그것, 없으면 만든다(NP-1). 불량 유형 비움(NP-4), 보류 정책은 손 NCR 과 같음(NP-3).
     * 잠금 순서: (부르는 쪽 근거 행) → advisory → NCR INSERT → LOT 보류(NP-2)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public NcrPort.NcrView createForClaim(NcrPort.ClaimRequest req) {
        if (req == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        if (!NcrPort.WMS_CUST_RTN.equals(req.refType())) {
            e.add("refType", "INVALID");
        }
        if (VisibleText.isBlank(req.by())) {
            e.add("by", "REQUIRED");
        }
        qty(req.qty(), "qty", e);
        String desc = VisibleText.isBlank(req.description()) ? "고객 반품 " + (req.refNo() == null ? req.refId() : req.refNo().strip())
                : req.description().strip();
        if (desc.length() > 2000) {
            e.add("description", "TOO_LONG", 2000);
        }
        e.throwIfAny();
        mapper.lockSrc("qms_ncr.src:" + req.refType() + ":" + req.refId());
        NcrPort.NcrView found = mapper.findBySrc(req.refType(), req.refId());
        if (found != null) {
            return found;
        }
        LotRef lot = mapper.findLotById(req.lotId());
        if (lot == null) {
            throw SamplingService.fieldError("lotId", "NOT_FOUND");
        }
        boolean hold = policies.bool(Policies.QMS_NCR_AUTO_HOLD);
        long id = mapper.nextId();
        String no = numbering.nextOrDefault(RULE, "부적합 번호", "NC{yyMM}-{seq:4}", "MONTH");
        mapper.insertClaim(id, no, lot, req.qty(), desc, clock.now().toOffsetDateTime(), hold ? "Y" : "N", req.refType(), req.refId(),
                req.by().strip());
        if (hold) {
            hold(lot.getLotId(), id, "부적합 " + no + " (고객 반품)", req.by().strip());
        }
        return new NcrPort.NcrView(id, no, "OPEN");
    }

    /** 근거 취소(결정 R-6) — OPEN 이면 취소·보류 해제, 없으면 false, 그 밖 상태는 409 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancelForClaim(String refType, long refId, String reason, String by) {
        FieldErrors e = FieldErrors.create();
        String why = VisibleText.reason(reason, "reason", 1, 400, e);
        if (VisibleText.isBlank(by)) {
            e.add("by", "REQUIRED");
        }
        e.throwIfAny();
        NcrPort.NcrView v = mapper.findBySrc(refType, refId);
        if (v == null) {
            return false;
        }
        NcrRow n = mapper.lockById(v.ncrId());
        if (n == null || !"OPEN".equals(n.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_NCR_STATE);
        }
        String text = cut("근거 취소(" + refType + "): " + why);
        release(n, cut("부적합 취소 " + n.getNcrNo() + ": " + text), by.strip());
        if (mapper.cancel(n.getNcrId(), text, n.getRowVer(), by.strip()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return true;
    }

    // ------------------------------------------------------------------ 내부

    /** 기록 무결성 점검(docs/52 Y1): 서명 대상 NCR 의 지금 내용 해시 — 없으면(다른 회사 포함) 빈 Optional */
    @Transactional(readOnly = true)
    public java.util.Optional<Set<String>> signHashes(long id) {
        NcrRow n = mapper.findById(id);
        if (n == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(Set.of(ESignService.hash(QmsESign.ncr(n, mapper.findDisps(id)))));
    }

    private NcrRow find(long id) {
        NcrRow n = mapper.findById(id);
        if (n == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return n;
    }

    private NcrRow openChecked(long id, int rowVer) {
        NcrRow n = find(id);
        if (!"OPEN".equals(n.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_NCR_STATE);
        }
        if (n.getRowVer() != rowVer) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return n;
    }

    static void sumCheck(NcrRow n, List<DispRow> disps) {
        BigDecimal sum = disps.stream().map(DispRow::getQty).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (disps.isEmpty() || sum.compareTo(n.getNcQty()) != 0) {
            throw SamplingService.fieldError("disps", "NCR_DISP_SUM");
        }
    }

    private void defect(Long defectId, FieldErrors e) {
        if (defectId == null) {
            e.add("defectId", "REQUIRED");
        } else if (!mapper.defectActive(defectId)) {
            e.add("defectId", "NOT_FOUND");
        }
    }

    private static void qty(BigDecimal q, String field, FieldErrors e) {
        if (q == null) {
            e.add(field, "REQUIRED");
        } else if (q.signum() <= 0 || q.compareTo(MAX_QTY) > 0 || q.stripTrailingZeros().scale() > 3) {
            e.add(field, "INVALID");
        }
    }

    private void hold(long lotId, long ncrId, String reason, String by) {
        try {
            LotHoldPort.of(holdPort).hold(lotId, LotHoldPort.QMS_NCR, ncrId, reason, by);
        } catch (IllegalStateException ex) {
            throw new BusinessException(HbErrorCode.QMS_NCR_HOLD_UNAVAILABLE);
        }
    }

    /** 포트 사유는 500자까지 */
    static String cut(String s) {
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    /** 이 NCR 이 잡은 보류만 푼다(다른 근거가 남아 있으면 LOT 은 계속 보류 — MES 원장) */
    private void release(NcrRow n, String reason, String by) {
        if (!"Y".equals(n.getHoldYn())) {
            return;
        }
        try {
            LotHoldPort.of(holdPort).release(n.getLotId(), LotHoldPort.QMS_NCR, n.getNcrId(), reason, by);
        } catch (IllegalStateException ex) {
            throw new BusinessException(HbErrorCode.QMS_NCR_HOLD_UNAVAILABLE);
        }
    }
}
