package io.smarthappy.module.qms;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.ibatis.cursor.Cursor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.module.qms.CapaDtos.CancelForm;
import io.smarthappy.module.qms.CapaDtos.CapaDetail;
import io.smarthappy.module.wms.link.ShipQuery;
import io.smarthappy.module.qms.CapaDtos.CapaExportRequest;
import io.smarthappy.module.qms.CapaDtos.CapaForm;
import io.smarthappy.module.qms.CapaDtos.CapaRow;
import io.smarthappy.module.qms.CapaDtos.CapaSearch;
import io.smarthappy.module.qms.CapaDtos.CapaSort;
import io.smarthappy.module.qms.CapaDtos.CapaValue;
import io.smarthappy.module.qms.CapaDtos.CsrDue;
import io.smarthappy.module.qms.CapaDtos.NcrRef;
import io.smarthappy.module.qms.CapaDtos.Ref;
import io.smarthappy.module.qms.CapaDtos.SpreadForm;
import io.smarthappy.module.qms.CapaDtos.SpreadValue;
import io.smarthappy.module.qms.NcrDtos.NcrRow;
import io.smarthappy.module.qms.DocDtos.ActionForm;
import io.smarthappy.module.qms.DocDtos.Approval;
import io.smarthappy.module.qms.DocDtos.AprLineRow;
import io.smarthappy.module.qms.DocDtos.LineItem;
import io.smarthappy.module.qms.DocDtos.SubmitForm;
import io.smarthappy.platform.approval.ApprovalService;
import io.smarthappy.platform.approval.ApprovalService.LineSpec;
import io.smarthappy.platform.approval.ApprovalService.LineType;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.platform.user.UserDirectory;
import jakarta.servlet.http.HttpServletResponse;
import io.smarthappy.platform.common.text.VisibleText;
import lombok.RequiredArgsConstructor;

/**
 * 시정조치(8D) (qms-capa) — P3 QMS Q4, docs/29 D7·D8.
 *
 * - 만들 때 기한 스냅샷: 발생일 + 정책 일수, 고객 클레임이면 그 고객 CSR override(CONTAIN_DAYS·CLOSE_DAYS)가 이긴다(근거 csr_id)
 * - 단계 = OPEN → CONTAINED(D1~D3) → ANALYZED(D4) → ACTIONED(D5·D6) → VERIFIED(D7 + 수평전개 OPEN 없음) — "다음 단계"는 칸 오류로 먼저,
 *   CHECK·트리거가 최종. 종결 = D8 + 결재(QMS_CAPA, 정책 QMS_DOC_APPROVAL = Y) 또는 APPROVE 권한으로 바로. 반려·회수 = ACTIONED
 * - 내용·수평전개는 종결 결재 전까지 고친다(수평전개는 VERIFIED 부터 고정). 종결·취소 뒤 불변, 종결 때 첨부 잠금
 */
@Service
@RequiredArgsConstructor
public class CapaService {

    static final String PROG = "qms-capa";
    static final String RULE = "QMS_CAPA";
    static final Set<String> SOURCES = Set.of("CUSTOMER_CLAIM", "INTERNAL", "SUPPLIER", "AUDIT");
    static final Set<String> STATUSES = Set.of("OPEN", "CONTAINED", "ANALYZED", "ACTIONED", "VERIFIED", "CLOSING", "CLOSED", "CANCELLED");
    static final Set<String> DUE_STATES = Set.of("ON_TIME", "CONTAIN_OVERDUE", "CLOSE_OVERDUE");
    static final Set<String> SPREAD_TARGETS = Set.of("ITEM", "PROC", "CUST");
    static final Set<String> SPREAD_STATUSES = Set.of("OPEN", "DONE", "NA");
    static final Map<String, String> NEXT = Map.of("OPEN", "CONTAINED", "CONTAINED", "ANALYZED", "ANALYZED", "ACTIONED",
            "ACTIONED", "VERIFIED");
    static final List<String> DEFAULT_SORT = List.of("updatedAt,desc");
    static final int MAX_SPREADS = 100;

    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("capaNo", "qms.capa.col.capaNo", 120).withSort().withPriority(1),
            ColumnSpec.code("source", "qms.capa.col.source", "CAPA_SOURCE", 100).withPriority(2),
            ColumnSpec.text("title", "qms.capa.col.title", 200).withPriority(1).withFlex().withMinWidth(120),
            ColumnSpec.text("custCd", "qms.capa.col.custCd", 100).withPriority(2),
            ColumnSpec.text("itemCd", "qms.capa.col.itemCd", 110).withPriority(3),
            ColumnSpec.of("occurredOn", "qms.capa.col.occurredOn", ColumnSpec.ColumnType.DATE, 100).withSort().withPriority(2),
            ColumnSpec.code("status", "qms.capa.col.status", "CAPA_STATUS", 90).withSort().withPriority(1),
            ColumnSpec.code("dueState", "qms.capa.col.dueState", "CAPA_DUE_STATE", 110).withPriority(1),
            ColumnSpec.of("containDue", "qms.capa.col.containDue", ColumnSpec.ColumnType.DATE, 100).withPriority(3),
            ColumnSpec.of("closeDue", "qms.capa.col.closeDue", ColumnSpec.ColumnType.DATE, 100).withSort().withPriority(2),
            ColumnSpec.text("ownerNm", "qms.capa.col.ownerNm", 90).withPriority(3));

    private final CapaMapper mapper;
    private final UserDirectory users;
    private final DocMapper docMapper;
    private final ApprovalService approvals;
    private final PolicyService policies;
    private final InboxService inbox;
    private final NumberingService numbering;
    private final ExcelExportService excel;
    private final BizClock clock;
    private final NcrMapper ncrMapper;
    private final ObjectProvider<ShipQuery> shipQuery;

    public record Page(List<CapaRow> list, Long total) {
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public Page list(String keyword, String status, String source, String dueState, Boolean withClosed, Integer page, Integer size,
            List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, CapaSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                DEFAULT_SORT);
        CapaSearch q = search(keyword, status, source, dueState, withClosed, pq);
        return new Page(mapper.findPage(q), pq.isWithTotal() ? mapper.count(q) : null);
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    @Transactional(readOnly = true)
    public CapaDetail detail(long id) {
        LoginUser me = SamplingService.me();
        CapaRow c = find(id);
        Approval apr = new Approval(null, null, false, false, List.of());
        if ("CLOSING".equals(c.getStatus()) && c.getAprId() != null) {
            List<AprLineRow> lines = docMapper.findAprLines(c.getAprId());
            boolean pending = "PENDING".equals(c.getAprStatus());
            boolean myTurn = pending && lines.stream().anyMatch(l -> "CURRENT".equals(l.getStatus()) && l.getApproverId() == me.userId());
            boolean anyActed = lines.stream().anyMatch(l -> "APPROVED".equals(l.getStatus()));
            boolean canWithdraw = pending && !anyActed && Objects.equals(c.getAprRequesterId(), me.userId());
            apr = new Approval(c.getAprId(), c.getAprStatus(), myTurn, canWithdraw, lines);
        }
        if (c.getNcrId() != null) {
            NcrRow n = ncrMapper.findById(c.getNcrId());
            c.setNcrLotNo(n == null ? null : n.getLotNo());
        }
        // 묶인 고객 반품(44장) — 이 8D 번호를 적은 반품 + 이어진 NCR 이 만든 반품. WMS 가 없으면 빈 목록
        List<ShipQuery.CustReturn> all = ShipQuery.of(shipQuery).custReturns(c.getCapaNo(),
                c.getNcrId() == null ? List.of() : List.of(c.getNcrId()));
        // R3-d(48장): 8D 번호는 사람이 적은 글자라 다른 고객 반품도 같은 번호면 섞인다 — 8D 에 고객이 있으면 그 고객 반품만.
        // 이어진 NCR 이 만든 반품은 고객과 무관하게 남긴다(사람이 적은 번호가 아니라 연결). 잘림은 거르기 전 건수로 판단
        List<ShipQuery.CustReturn> rtns = c.getCustCd() == null ? all : all.stream()
                .filter(r -> (c.getNcrId() != null && c.getNcrId().equals(r.ncrId())) || c.getCustCd().equalsIgnoreCase(r.custCd()))
                .toList();
        return new CapaDetail(c, mapper.findSpreads(id), apr, policies.bool(Policies.QMS_DOC_APPROVAL), rtns, all.size() - rtns.size(),
                all.size() >= ShipQuery.RETURNS_MAX, ShipQuery.RETURNS_MAX);
    }

    // ------------------------------------------------------------------ 저장

    @Transactional
    public long create(CapaForm in) {
        LoginUser me = SamplingService.me();
        inbox.claimRequestKey("QMS_CAPA_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String source = VisibleText.isBlank(in.source()) ? null : in.source().strip();
        if (source == null) {
            e.add("source", "REQUIRED");
        } else if (!SOURCES.contains(source)) {
            e.add("source", "CODE");
            source = null;
        }
        LocalDate on = BomService.date(in.occurredOn(), "occurredOn", e);
        if (VisibleText.isBlank(in.occurredOn())) {
            on = clock.today();
        } else if (on != null && on.isAfter(clock.today())) {
            e.add("occurredOn", "FUTURE");
        }
        CapaValue v = value(in, source, on, me.userId(), null, e);
        List<SpreadValue> spreads = spreads(in.spreads(), e);
        Long ncrId = ncr(in.ncrNo(), null, e);
        e.throwIfAny();
        // 기한: 정책, 고객 클레임이면 그 고객 CSR override
        int contain = policies.intValue(Policies.QMS_CAPA_CONTAIN_DAYS);
        int close = policies.intValue(Policies.QMS_CAPA_CLOSE_DAYS);
        Long csrId = null;
        if ("CUSTOMER_CLAIM".equals(source) && v.custId() != null) {
            CsrDue d = mapper.findCsrDue(v.custId(), v.itemId(), on);
            if (d != null) {
                csrId = d.getCsrId();
                contain = d.getContainDays() != null ? d.getContainDays() : contain;
                close = d.getCloseDays() != null ? d.getCloseDays() : close;
            }
        }
        LocalDate containDue = on.plusDays(contain);
        LocalDate closeDue = on.plusDays(Math.max(close, contain));
        String no = numbering.nextOrDefault(RULE, "시정조치 번호", "CA{yyMM}-{seq:4}", "MONTH");
        long id = mapper.insertCapa(v, no, containDue, closeDue, csrId, me.loginId());
        if (ncrId != null) {
            mapper.updateCapaNcr(id, ncrId);
        }
        if (!spreads.isEmpty()) {
            mapper.insertSpreads(id, spreads, me.loginId());
        }
        return id;
    }

    /** 내용·담당·D1~D8 + 수평전개 한 벌(spreads 가 null 이면 그대로) — 종결 결재 전까지. 출처·발생일·기한은 고정 */
    @Transactional
    public void update(long id, CapaForm in) {
        LoginUser me = SamplingService.me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        CapaRow cur = locked(id);
        if (Set.of("CLOSING", "CLOSED", "CANCELLED").contains(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_CAPA_STATE);
        }
        FieldErrors e = FieldErrors.create();
        if (!VisibleText.isBlank(in.source()) && !in.source().strip().equals(cur.getSource())) {
            e.add("source", "FIXED");
        }
        CapaValue v = value(in, cur.getSource(), cur.getOccurredOn(), cur.getOwnerUserId(), cur, e);
        List<SpreadValue> spreads = in.spreads() == null ? null : spreads(in.spreads(), e);
        if (spreads != null && Set.of("VERIFIED").contains(cur.getStatus())) {
            e.add("spreads", "CAPA_SPREAD_LOCKED");
        }
        // 부적합 연결: null = 그대로, 빈 문자열 = 끊기(다른 칸처럼 빠뜨렸다고 몰래 지우지 않는다)
        Long ncrId = in.ncrNo() == null ? cur.getNcrId() : ncr(in.ncrNo(), cur.getNcrId(), e);
        e.throwIfAny();
        if (mapper.updateCapa(id, v, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (!Objects.equals(ncrId, cur.getNcrId())) {
            mapper.updateCapaNcr(id, ncrId);
        }
        if (spreads != null) {
            mapper.deleteSpreads(id);
            if (!spreads.isEmpty()) {
                mapper.insertSpreads(id, spreads, me.loginId());
            }
        }
    }

    /** 다음 단계 — 그 단계의 D 구역이 채워져 있어야(칸 오류 400 QMS_CAPA_STEP_CHECK) */
    @Transactional
    public void advance(long id, ActionForm in) {
        LoginUser me = SamplingService.me();
        int ver = ver(in == null ? null : in.rowVer());
        CapaRow c = locked(id);
        String next = NEXT.get(c.getStatus());
        if (next == null) {
            throw new BusinessException(HbErrorCode.QMS_CAPA_STATE);
        }
        if (c.getRowVer() != ver) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        FieldErrors e = FieldErrors.create();
        required(c, next, e);
        if ("VERIFIED".equals(next) && c.getSpreadOpenCnt() > 0) {
            e.add("spreads", "CAPA_SPREAD_OPEN");
        }
        if (!e.isEmpty()) {
            throw new BusinessException(HbErrorCode.QMS_CAPA_STEP_CHECK).withFieldErrors(e.list());
        }
        if (mapper.setStatus(id, c.getStatus(), next, ver, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 종결 결재 상신(정책 Y) — VERIFIED + D8 */
    @Transactional
    public long submitClose(long id, SubmitForm in) {
        LoginUser me = SamplingService.me();
        inbox.claimRequestKey("QMS_CAPA_SUBMIT");
        int ver = ver(in == null ? null : in.rowVer());
        CapaRow c = locked(id);
        closeCheck(c, ver);
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
        String title = VisibleText.isBlank(in.title()) ? c.getCapaNo() + " " + c.getTitle() : in.title().strip();
        long aprId = approvals.submit(CapaApprovalDocType.DOC_TYPE, String.valueOf(id), title.length() > 200 ? title.substring(0, 200) : title,
                lines);
        if (mapper.submitClose(id, aprId, ver, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return aprId;
    }

    /** 결재 없이 종결(정책 N, APPROVE) */
    @Transactional
    public void close(long id, ActionForm in) {
        LoginUser me = SamplingService.me();
        int ver = ver(in == null ? null : in.rowVer());
        CapaRow c = locked(id);
        if (policies.bool(Policies.QMS_DOC_APPROVAL)) {
            throw new BusinessException(HbErrorCode.QMS_DOC_APPROVAL_REQUIRED);
        }
        closeCheck(c, ver);
        if (mapper.close(id, "VERIFIED", ver, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        mapper.lockFiles(id, me.loginId());
    }

    @Transactional
    public void cancel(long id, CancelForm in) {
        LoginUser me = SamplingService.me();
        int ver = ver(in == null ? null : in.rowVer());
        FieldErrors e = FieldErrors.create();
        String reason = VisibleText.reason(in.reason(), "reason", 1, 500, e);
        e.throwIfAny();
        CapaRow c = locked(id);
        if (Set.of("CLOSING", "CLOSED", "CANCELLED").contains(c.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_CAPA_STATE);
        }
        if (mapper.cancel(id, reason, ver, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    // ------------------------------------------------------------------ 엑셀

    @Transactional(readOnly = true)
    public long export(CapaExportRequest req, HttpServletResponse response) throws IOException {
        CapaExportRequest r = req == null ? new CapaExportRequest(null, null, null, null, null, null, null) : req;
        List<ColumnSpec> cols = ColumnSpec.selectExportable(COLUMNS, r.columns());
        CapaSearch q = search(r.keyword(), r.status(), r.source(), r.dueState(), r.withClosed(),
                PageQuery.sortOnly(r.sort(), CapaSort.values(), DEFAULT_SORT));
        List<String> keys = new ArrayList<>();
        if (q.getKeywordLike() != null) {
            keys.add("keyword");
        }
        if (q.getSource() != null) {
            keys.add("source");
        }
        if (q.getStatus() != null) {
            keys.add("status");
        }
        if (q.getDueState() != null) {
            keys.add("dueState");
        }
        return excel.export(response, new ExportJob(PROG, "prog.qmsCapa", cols, mapper.count(q), keys), w -> {
            try (Cursor<CapaRow> c = mapper.cursor(q)) {
                c.forEach(w);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    // ------------------------------------------------------------------ 검증

    private CapaValue value(CapaForm in, String source, LocalDate on, long defaultOwner, CapaRow cur, FieldErrors e) {
        Long custId = null;
        String custCd = VisibleText.isBlank(in.custCd()) ? null : in.custCd().strip().toUpperCase(Locale.ROOT);
        if (custCd != null) {
            Ref r = mapper.findCust(custCd);
            if (r == null) {
                e.add("custCd", "NO_SUCH_CODE");
            } else if (!"Y".equals(r.getUseYn()) && (cur == null || !Objects.equals(cur.getCustId(), r.getId()))) {
                e.add("custCd", "INACTIVE");
            } else {
                custId = r.getId();
            }
        } else if ("CUSTOMER_CLAIM".equals(source) || "SUPPLIER".equals(source)) {
            e.add("custCd", "REQUIRED");
        }
        Long itemId = null;
        String itemCd = VisibleText.isBlank(in.itemCd()) ? null : in.itemCd().strip().toUpperCase(Locale.ROOT);
        if (itemCd != null) {
            Ref r = mapper.findItem(itemCd);
            if (r == null) {
                e.add("itemCd", "NO_SUCH_CODE");
            } else if (!"Y".equals(r.getUseYn()) && (cur == null || !Objects.equals(cur.getItemId(), r.getId()))) {
                e.add("itemCd", "INACTIVE");
            } else {
                itemId = r.getId();
            }
        }
        String title = VisibleText.reason(in.title(), "title", 1, 200, e);
        long owner = in.ownerUserId() != null ? in.ownerUserId() : defaultOwner;
        if (in.ownerUserId() != null && (cur == null || owner != cur.getOwnerUserId()) && !users.isActive(owner)) {
            e.add("ownerUserId", "NOT_FOUND");
        }
        return new CapaValue(source, custId, itemId, title, on, owner,
                VisibleText.optional(in.d1Team(), "d1Team", 2000, e), VisibleText.optional(in.d2Problem(), "d2Problem", 4000, e),
                VisibleText.optional(in.d3Contain(), "d3Contain", 4000, e), VisibleText.optional(in.d4Cause(), "d4Cause", 4000, e),
                VisibleText.optional(in.d5Action(), "d5Action", 4000, e), VisibleText.optional(in.d6Verify(), "d6Verify", 4000, e),
                VisibleText.optional(in.d7Prevent(), "d7Prevent", 4000, e), VisibleText.optional(in.d8Close(), "d8Close", 2000, e),
                in.rowVer());
    }

    /**
     * 부적합 번호 → id (docs/29_P3 17장). 빈 값 = 연결 없음. 없는·다른 회사 번호는 NCR_NOT_FOUND(RLS 라 구분 없음),
     * 취소된 부적합은 새로 잇지 못한다(이미 이어진 것은 그대로 둘 수 있음)
     */
    private Long ncr(String raw, Long curNcrId, FieldErrors e) {
        if (VisibleText.isBlank(raw)) {
            return null;
        }
        NcrRef r = mapper.findNcr(raw.strip());
        if (r == null) {
            e.add("ncrNo", "NCR_NOT_FOUND");
            return null;
        }
        if ("CANCELLED".equals(r.getStatus()) && !Objects.equals(curNcrId, r.getId())) {
            e.add("ncrNo", "NCR_CANCELLED");
            return null;
        }
        return r.getId();
    }

    /** 수평전개 한 벌 — 행 키 = 배열 순번, 순번은 1부터 다시 매긴다 */
    private List<SpreadValue> spreads(List<SpreadForm> raw, FieldErrors e) {
        List<SpreadForm> in = raw == null ? List.of() : raw;
        if (in.size() > MAX_SPREADS || in.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        List<SpreadValue> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < in.size(); i++) {
            SpreadForm s = in.get(i);
            FieldErrors.RowScope row = e.row(String.valueOf(i));
            String type = VisibleText.isBlank(s.targetType()) ? null : s.targetType().strip();
            String cd = VisibleText.isBlank(s.targetCd()) ? null : s.targetCd().strip().toUpperCase(Locale.ROOT);
            String st = VisibleText.isBlank(s.status()) ? "OPEN" : s.status().strip();
            if (type == null || !SPREAD_TARGETS.contains(type)) {
                row.add("targetType", type == null ? "REQUIRED" : "CODE");
                continue;
            }
            if (!SPREAD_STATUSES.contains(st)) {
                row.add("status", "CODE");
                continue;
            }
            if (cd == null) {
                row.add("targetCd", "REQUIRED");
                continue;
            }
            Ref r = switch (type) {
                case "ITEM" -> mapper.findItem(cd);
                case "PROC" -> mapper.findProc(cd);
                default -> mapper.findCust(cd);
            };
            if (r == null) {
                row.add("targetCd", "NO_SUCH_CODE");
                continue;
            }
            if (!seen.add(type + ":" + r.getId())) {
                row.add("targetCd", "DUPLICATE");
                continue;
            }
            String note = VisibleText.strip(s.note());
            if (note != null && note.length() > 500) {
                row.add("note", "TOO_LONG", 500);
                continue;
            }
            out.add(new SpreadValue(out.size() + 1, type, "ITEM".equals(type) ? r.getId() : null, "PROC".equals(type) ? r.getId() : null,
                    "CUST".equals(type) ? r.getId() : null, st, note));
        }
        return out;
    }

    /** 단계에 필요한 D 구역 (CHECK ck_qms_capa_d3·d4·d6·d7·d8 과 같은 규칙) */
    private static void required(CapaRow c, String to, FieldErrors e) {
        List<String> order = List.of("CONTAINED", "ANALYZED", "ACTIONED", "VERIFIED", "CLOSED");
        int idx = order.indexOf(to);
        if (idx >= 0) {
            need(c.getD1Team(), "d1Team", e);
            need(c.getD2Problem(), "d2Problem", e);
            need(c.getD3Contain(), "d3Contain", e);
        }
        if (idx >= 1) {
            need(c.getD4Cause(), "d4Cause", e);
        }
        if (idx >= 2) {
            need(c.getD5Action(), "d5Action", e);
            need(c.getD6Verify(), "d6Verify", e);
        }
        if (idx >= 3) {
            need(c.getD7Prevent(), "d7Prevent", e);
        }
        if (idx >= 4) {
            need(c.getD8Close(), "d8Close", e);
        }
    }

    private static void need(String v, String field, FieldErrors e) {
        if (VisibleText.isBlank(v)) {
            e.add(field, "REQUIRED");
        }
    }

    private void closeCheck(CapaRow c, int ver) {
        if (!"VERIFIED".equals(c.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_CAPA_STATE);
        }
        if (c.getRowVer() != ver) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        FieldErrors e = FieldErrors.create();
        required(c, "CLOSED", e);
        if (!e.isEmpty()) {
            throw new BusinessException(HbErrorCode.QMS_CAPA_STEP_CHECK).withFieldErrors(e.list());
        }
    }

    private CapaSearch search(String keyword, String status, String source, String dueState, Boolean withClosed, PageQuery pq) {
        String kw = VisibleText.isBlank(keyword) ? null : keyword.strip();
        String st = VisibleText.isBlank(status) ? null : status.strip();
        String src = VisibleText.isBlank(source) ? null : source.strip();
        String due = VisibleText.isBlank(dueState) ? null : dueState.strip();
        if ((kw != null && kw.length() > 100) || (st != null && !STATUSES.contains(st)) || (src != null && !SOURCES.contains(src))
                || (due != null && !DUE_STATES.contains(due))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return new CapaSearch(kw == null ? null : "%" + BomService.likeEscape(kw) + "%", st, src, due, Boolean.TRUE.equals(withClosed),
                clock.today(), pq);
    }

    private CapaRow find(long id) {
        CapaRow c = mapper.findById(id, clock.today());
        if (c == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return c;
    }

    private CapaRow locked(long id) {
        if (mapper.lockCapa(id) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return find(id);
    }

    private static int ver(Integer v) {
        if (v == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return v;
    }
}
