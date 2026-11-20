package io.smarthappy.module.plm;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.EcoReleaseGate;
import io.smarthappy.module.master.bom.BomChangeDtos.ReplaceDone;
import io.smarthappy.module.master.bom.BomChangeDtos.ReplacePreview;
import io.smarthappy.module.master.bom.BomChangeDtos.ReplaceRequest;
import io.smarthappy.module.master.bom.BomChangeService;
import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.module.master.routing.RoutingService;
import io.smarthappy.module.plm.PlmDtos.Approval;
import io.smarthappy.module.plm.PlmDtos.AprLineRow;
import io.smarthappy.module.plm.PlmDtos.Candidate;
import io.smarthappy.module.plm.PlmDtos.CustApprovalForm;
import io.smarthappy.module.plm.PlmDtos.CustOption;
import io.smarthappy.module.plm.PlmDtos.DocRef;
import io.smarthappy.module.plm.PlmDtos.EcoActForm;
import io.smarthappy.module.plm.PlmDtos.EcoDetail;
import io.smarthappy.module.plm.PlmDtos.EcoForm;
import io.smarthappy.module.plm.PlmDtos.EcoList;
import io.smarthappy.module.plm.PlmDtos.EcoRow;
import io.smarthappy.module.plm.PlmDtos.EcrRow;
import io.smarthappy.module.plm.PlmDtos.ItemRef;
import io.smarthappy.module.plm.PlmDtos.LineItem;
import io.smarthappy.module.plm.PlmDtos.MarkList;
import io.smarthappy.module.plm.PlmDtos.MarkRow;
import io.smarthappy.module.plm.PlmDtos.RefInfo;
import io.smarthappy.module.plm.PlmDtos.ReplaceForm;
import io.smarthappy.module.plm.PlmDtos.SubmitForm;
import io.smarthappy.module.plm.PlmDtos.TargetItem;
import io.smarthappy.module.plm.PlmDtos.TargetRow;
import io.smarthappy.module.plm.PlmDtos.TargetSet;
import io.smarthappy.module.plm.PlmMapper.EcoValue;
import io.smarthappy.module.plm.link.EcoQualityView;
import io.smarthappy.module.plm.link.EcoSubmitRule;
import io.smarthappy.module.qms.DocService;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.module.wms.link.StockQuery;
import io.smarthappy.platform.esign.ESignService;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.approval.ApprovalService;
import io.smarthappy.platform.approval.ApprovalService.LineSpec;
import io.smarthappy.platform.approval.ApprovalService.LineType;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import lombok.RequiredArgsConstructor;

/**
 * 설계 변경 지시 ECO (plm-eco) — docs/39.
 *
 * - 작성 중(DRAFT)에만 내용·대상을 바꾼다. 대상 BOM·라우팅은 작성 중 리비전, 한 리비전 = 열린 지시 하나(트리거)
 * - 상신 = 대상 리비전 row_ver 고정 → PENDING → 결재 엔진(PLM_ECO). 결재 끝은 {@link EcoApprovalDocType}
 * - 적용 = 대상마다 "지금도 작성 중 + row_ver 같음" 확인 → 머리에 ECO 번호·유효일 → 기존 BOM·라우팅 확정(한 트랜잭션)
 * - 상신 확장점 {@link EcoSubmitRule}(다른 모듈 빈, 예 QMS 4M 세부 필수) — ECO 행 잠금 뒤 같은 트랜잭션, 칸 오류를 합친다(16장)
 * - 2단계: 도면 대상 DOC(QMS 문서 리비전, DRAWING 작성 중) — 적용 때 {@link DocService#releaseFromChange(long)}. 적용 때 ECO 첨부 잠금 표시(D7)
 * - 4단계: D4 정책 PLM_ECO_REQUIRED 면 적용만 확정 허용 범위({@link EcoReleaseGate})를 연다, 일괄 대체(docs/18 C5) 결과를 BOM 대상으로
 * - 3b: 변경 종류(DESIGN·PROCESS_4M·TEMP)·표시 구간, 대상 EQUIP·PROC(적용 때 할 일 없음), 표시된 LOT 조회. 표시는 {@code link.EcoChangeMarker}
 */
@Service
@RequiredArgsConstructor
public class EcoService {

    static final String PROG = "plm-eco";
    static final String RULE = "PLM_ECO";
    static final String DOC_TYPE = "PLM_ECO";
    static final int LIST_LIMIT = 500;
    static final int MAX_TARGETS = 200;
    static final int CANDIDATE_LIMIT = 200;
    static final Set<String> STATUSES = Set.of("DRAFT", "PENDING", "APPROVED", "RELEASED", "CANCELLED");
    /** 목록 묶음 선택지 "고객 응답 대기"(상태 아님) — PPAP 판정 대기 또는 응답 기한 지난 열린 PCN (docs/39 30장) */
    static final String CUST_WAIT = "CUST_WAIT";
    static final Set<String> TARGET_TYPES = Set.of("ITEM", "BOM", "ROUTING", "EQUIP", "PROC", "DOC");
    /** 리비전 대상 — 상신·적용 때 확인하고 확정·배포하는 것(ITEM·EQUIP·PROC 는 기록·표시용) */
    static final Set<String> REVISION_TYPES = Set.of("BOM", "ROUTING", "DOC");
    /** ECO 로 개정하는 관리문서 유형(16.1, 36장) — 도면·작업표준서·관리계획서·검사기준서·PFMEA(DB 트리거 hb_guard_plm_eco_target 와 같은 목록) */
    static final Set<String> DOC_TYPES = Set.of("DRAWING", "WI", "CP", "INSP_STD", "PFMEA");
    static final Set<String> CHANGE_KINDS = Set.of("DESIGN", "PROCESS_4M", "TEMP");
    static final int MAX_MARK_LOTS = 100;
    static final Set<String> APPLY_CONDS = Set.of("DATE", "STOCK_OUT");
    static final Set<String> CUST_STATUSES = Set.of("PENDING", "APPROVED", "REJECTED");

    private final PlmMapper mapper;
    private final UserDirectory users;
    private final NumberingService numbering;
    private final InboxService inbox;
    private final BizClock clock;
    private final ApprovalService approvals;
    private final BomService boms;
    private final RoutingService routings;
    private final OrderService orders;
    private final EcoReleaseGate ecoGate;
    private final BomChangeService bomChanges;
    private final DocService docs;
    private final ObjectProvider<EcoSubmitRule> submitRules;
    private final ObjectProvider<EcoQualityView> qualityViews;
    private final ObjectProvider<StockQuery> stockQueries;
    private final ESignService esign;
    private final io.smarthappy.module.master.bom.BomTreeService bomTree;
    private final PolicyService policies;
    private final io.smarthappy.platform.excel.ExcelExportService excel;

    // ------------------------------------------------------------------ 조회

    /**
     * grid(UI 단계 3, docs/39 21.2) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link io.smarthappy.module.plm.PlmDtos.EcoSort},
     * 최대 3개, 모르는 열 400, 마지막 PK). 없는 품목 코드면 빈 쪽(목록과 같다)
     */
    @Transactional(readOnly = true)
    public io.smarthappy.module.plm.PlmDtos.EcoPage grid(String status, String keyword, String itemCd, Integer page, Integer size,
            List<String> sort, Boolean total) {
        io.smarthappy.platform.common.query.PageQuery pq = io.smarthappy.platform.common.query.PageQuery.parse(page, size, sort, total,
                io.smarthappy.module.plm.PlmDtos.EcoSort.values(), policies.intValue(io.smarthappy.platform.policy.Policies.GRID_PAGE_SIZE_MAX),
                List.of("ecoNo,desc"));
        Criteria c = criteria(status, keyword);
        Long itemId = null;
        if (!blank(itemCd)) {
            ItemRef i = mapper.findItem(itemCd.strip().toUpperCase(Locale.ROOT));
            if (i == null) {
                return new io.smarthappy.module.plm.PlmDtos.EcoPage(List.of(), pq.isWithTotal() ? 0L : null);
            }
            itemId = i.getItemId();
        }
        List<EcoRow> rows = mapper.findEcoPage(c.status(), c.keyword(), itemId, null, c.custWaitOn(), pq);
        return new io.smarthappy.module.plm.PlmDtos.EcoPage(rows,
                pq.isWithTotal() ? mapper.countEcos(c.status(), c.keyword(), itemId, null, c.custWaitOn()) : null);
    }

    /**
     * 엑셀(docs/39 25장) — grid 와 같은 조건·정렬(허용 enum, 최대 3개)·보이는 열. 없는 품목 코드면 빈 파일(grid 와 같다).
     * 행 수 상한·시간 제한은 플랫폼 엑셀 서비스(건수 먼저)
     */
    @Transactional(readOnly = true)
    public long export(io.smarthappy.module.plm.PlmDtos.EcoExport req, jakarta.servlet.http.HttpServletResponse response)
            throws java.io.IOException {
        io.smarthappy.module.plm.PlmDtos.EcoExport r = req == null ? new io.smarthappy.module.plm.PlmDtos.EcoExport(null, null, null, null, null) : req;
        List<io.smarthappy.platform.grid.ColumnSpec> cols = io.smarthappy.platform.grid.ColumnSpec.selectExportable(GRID_COLUMNS, r.columns());
        io.smarthappy.platform.common.query.PageQuery pq = io.smarthappy.platform.common.query.PageQuery.sortOnly(r.sort(),
                io.smarthappy.module.plm.PlmDtos.EcoSort.values(), List.of("ecoNo,desc"));
        Criteria crit = criteria(r.status(), r.keyword());
        List<String> keys = new java.util.ArrayList<>();
        if (!blank(r.status())) {
            keys.add("status");
        }
        if (crit.keyword() != null) {
            keys.add("keyword");
        }
        Long itemId = null;
        boolean none = false;
        if (!blank(r.itemCd())) {
            keys.add("itemCd");
            ItemRef i = mapper.findItem(r.itemCd().strip().toUpperCase(Locale.ROOT));
            none = i == null;
            itemId = none ? null : i.getItemId();
        }
        Long item = itemId;
        long count = none ? 0 : mapper.countEcos(crit.status(), crit.keyword(), item, null, crit.custWaitOn());
        return excel.export(response, new io.smarthappy.platform.excel.ExcelExportService.ExportJob(PROG, "prog.plmEco", cols, count, keys), w -> {
            if (count == 0) {
                return;
            }
            try (org.apache.ibatis.cursor.Cursor<EcoRow> c = mapper.cursorEcos(crit.status(), crit.keyword(), item, null, crit.custWaitOn(), pq)) {
                c.forEach(w);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    /** grid 열(화면 목록과 같은 칸) — 정렬 가능 = EcoSort 에 있는 열 */
    static final List<io.smarthappy.platform.grid.ColumnSpec> GRID_COLUMNS = List.of(
            io.smarthappy.platform.grid.ColumnSpec.text("ecoNo", "plm.col.ecoNo", 120).withSort(),
            io.smarthappy.platform.grid.ColumnSpec.text("title", "plm.col.title", 180).withSort(),
            io.smarthappy.platform.grid.ColumnSpec.of("effectiveDate", "plm.col.effectiveDate",
                    io.smarthappy.platform.grid.ColumnSpec.ColumnType.DATE, 100).withSort(),
            io.smarthappy.platform.grid.ColumnSpec.text("ownerNm", "plm.col.owner", 90).withSort(),
            io.smarthappy.platform.grid.ColumnSpec.of("targetCnt", "plm.col.targetCnt", io.smarthappy.platform.grid.ColumnSpec.ColumnType.INT, 50),
            io.smarthappy.platform.grid.ColumnSpec.code("status", "plm.col.status", "PLM_ECO_STATUS", 80).withSort());

    public List<io.smarthappy.platform.grid.ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    /** 목록 조건 — 상태(또는 null), like 패턴(또는 null), 고객 응답 대기 기준일(status=CUST_WAIT 일 때 회사 오늘, 아니면 null) */
    record Criteria(String status, String keyword, LocalDate custWaitOn) {
    }

    /** 상태·검색어 검증(목록·grid·엑셀 공통). CUST_WAIT 는 상태가 아니라 묶음 선택지(docs/39 30장 — 홈 카드 숫자 링크) */
    private Criteria criteria(String status, String keyword) {
        FieldErrors e = FieldErrors.create();
        if (!blank(status) && !STATUSES.contains(status) && !CUST_WAIT.equals(status)) {
            e.add("status", "CODE");
        }
        if (!blank(keyword) && keyword.strip().length() > 50) {
            e.add("keyword", "TOO_LONG", 50);
        }
        e.throwIfAny();
        boolean wait = CUST_WAIT.equals(status);
        return new Criteria(blank(status) || wait ? null : status,
                blank(keyword) ? null : "%" + like(keyword.strip().toUpperCase(Locale.ROOT)) + "%", wait ? clock.today() : null);
    }

    @Transactional(readOnly = true)
    public EcoList list(String status, String keyword, String itemCd) {
        Criteria c = criteria(status, keyword);
        Long itemId = null;
        if (!blank(itemCd)) {
            ItemRef i = mapper.findItem(itemCd.strip().toUpperCase(Locale.ROOT));
            if (i == null) {
                return new EcoList(List.of(), false);
            }
            itemId = i.getItemId();
        }
        List<EcoRow> rows = mapper.findEcos(c.status(), c.keyword(), itemId, null, c.custWaitOn(), LIST_LIMIT + 1);
        boolean more = rows.size() > LIST_LIMIT;
        return new EcoList(more ? rows.subList(0, LIST_LIMIT) : rows, more);
    }

    @Transactional(readOnly = true)
    public EcoDetail detail(long ecoId) {
        LoginUser me = me();
        EcoRow eco = find(ecoId);
        List<TargetRow> targets = mapper.findTargets(ecoId);
        // 결재 뒤 바뀜 = 결재 중·승인됨에서만 의미가 있다(적용하면 확정이 row_ver 를 올린다)
        boolean fixed = "PENDING".equals(eco.getStatus()) || "APPROVED".equals(eco.getStatus());
        targets.forEach(t -> t.setChanged(fixed && t.getBaseRowVer() != null && !t.getBaseRowVer().equals(t.getRefRowVer())));
        List<EcrRow> ecrs = mapper.findEcrs(null, null, null, null, ecoId, LIST_LIMIT);
        // 재고 소진 조건(18장): 적용 전이면 지금 가용 재고를 같이 보여 준다(WMS 없으면 null — 저장 때 이미 막는다)
        if ("STOCK_OUT".equals(eco.getApplyCond()) && !"RELEASED".equals(eco.getStatus()) && !"CANCELLED".equals(eco.getStatus())) {
            StockQuery q = stockQueries.getIfAvailable();
            if (q != null) {
                eco.setStockAvailable(q.available(eco.getStockPlantCd(), List.of(eco.getStockItemId())).get(eco.getStockItemId()));
            }
        }
        Approval apr;
        if (eco.getAprId() == null) {
            apr = new Approval(null, null, false, false, List.of());
        } else {
            List<AprLineRow> lines = mapper.findAprLines(eco.getAprId());
            boolean pending = "PENDING".equals(eco.getAprStatus());
            boolean myTurn = pending && lines.stream().anyMatch(l -> "CURRENT".equals(l.getStatus()) && l.getApproverId() == me.userId());
            boolean anyActed = lines.stream().anyMatch(l -> "APPROVED".equals(l.getStatus()));
            boolean canWithdraw = pending && !anyActed && eco.getAprRequesterId() != null && eco.getAprRequesterId() == me.userId();
            apr = new Approval(eco.getAprId(), eco.getAprStatus(), myTurn, canWithdraw, lines);
        }
        return new EcoDetail(eco, targets, ecrs, apr, EcoQualityView.of(qualityViews).summary(ecoId).orElse(null),
                signs(esign, PlmSignContent.ECO, ecoId, PlmSignContent.ecoHash(eco, targets)));
    }

    /** 대상의 서명 + 지금 내용과 다른지 */
    static List<io.smarthappy.module.plm.PlmDtos.SignView> signs(ESignService esign, String type, long id, String nowHash) {
        return esign.list(type, id).stream()
                .map(s -> new io.smarthappy.module.plm.PlmDtos.SignView(s.esignId(), s.userId(), s.signerNm(), s.meaningCd(),
                        s.authMethod(), s.signedAt(), !s.contentHash().equals(nowHash)))
                .toList();
    }

    /** 회사 정책 PLM_ESIGN_REQUIRED(기본 N) — Y 면 ECO 결재 승인·PPAP 판정에 전자서명 필수(19장) */
    static boolean esignRequired(PolicyService policies) {
        return policies.bool(ESIGN_POLICY);
    }

    static final String ESIGN_POLICY = "PLM_ESIGN_REQUIRED";

    /**
     * 결재 승인(PLM 화면, docs/39 19장·22장) — 토큰을 결재 엔진에 넘기고, 서명 검증·소비는 문서 유형 beforeAct 가 줄마다 한다
     * (공통 결재함 경로와 같은 자리). ECO 행은 여기서 잠그지 않는다 — 결재 엔진 순서(결재 행 → 핸들러가 ECO)를 지킨다
     */
    @Transactional
    public void approve(long ecoId, io.smarthappy.module.plm.PlmDtos.EcoApproveForm in) {
        me();
        EcoRow eco = find(ecoId);
        if (!"PENDING".equals(eco.getStatus()) || eco.getAprId() == null) {
            throw new BusinessException(HbErrorCode.PLM_ECO_STATE);
        }
        approvals.approve(eco.getAprId(), in == null ? null : in.comment(), in == null ? null : in.esignToken());
    }

    /** 표시된 LOT (3b) — 도착 순, 다른 회사 지시는 404 */
    @Transactional(readOnly = true)
    public MarkList marks(long ecoId) {
        find(ecoId);
        List<MarkRow> rows = mapper.findMarks(ecoId, LIST_LIMIT + 1);
        boolean more = rows.size() > LIST_LIMIT;
        return new MarkList(more ? rows.subList(0, LIST_LIMIT) : rows, more);
    }

    @Transactional(readOnly = true)
    public List<Candidate> candidates(String itemCd) {
        Long itemId = null;
        if (!blank(itemCd)) {
            ItemRef i = mapper.findItem(itemCd.strip().toUpperCase(Locale.ROOT));
            if (i == null) {
                return List.of();
            }
            itemId = i.getItemId();
        }
        return mapper.candidates(itemId, CANDIDATE_LIMIT);
    }

    /**
     * 영향 대상 후보 보기(docs/39 20.7) — 이 품목을 쓰는 상위 BOM(ECO 유효일 기준 확정 리비전, master 역전개 그대로) +
     * 그 모품목의 작성 중 리비전·다른 열린 ECO·이 ECO 대상 여부. 읽기만 — 대상 추가는 기존 대상 저장이 판정한다.
     * scope = PARENT(기본, 1단계 상위 = BOM 후보) · ALL(전체 레벨, 2단계 이상 = 품목 대상 후보)
     */
    @Transactional(readOnly = true)
    public io.smarthappy.module.plm.PlmDtos.ImpactResult impact(long ecoId, String itemCd, String scope) {
        EcoRow eco = find(ecoId);
        String sc = blank(scope) ? "PARENT" : scope;
        if (!"PARENT".equals(sc) && !"ALL".equals(sc)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add("scope", "CODE").list());
        }
        var where = bomTree.whereUsed(itemCd, eco.getEffectiveDate().toString(), false, false);
        // 같은 모품목 리비전이 여러 경로·여러 행으로 나오면 가장 낮은 레벨 한 줄
        Map<Long, io.smarthappy.module.plm.PlmDtos.ImpactRow> byBom = new java.util.LinkedHashMap<>();
        for (var w : where.rows()) {
            if ("PARENT".equals(sc) && w.getLvl() != 1) {
                continue;
            }
            var cur = byBom.get(w.getBomId());
            if (cur != null && cur.getLevel() <= w.getLvl()) {
                continue;
            }
            var r = new io.smarthappy.module.plm.PlmDtos.ImpactRow();
            r.setLevel(w.getLvl());
            r.setPath(w.getPath());
            r.setParentId(w.getParentId());
            r.setParentCd(w.getParentCd());
            r.setParentNm(w.getParentNm());
            r.setBomId(w.getBomId());
            r.setRev(w.getRev());
            byBom.put(w.getBomId(), r);
        }
        List<io.smarthappy.module.plm.PlmDtos.ImpactRow> rows = new ArrayList<>(byBom.values());
        Map<Long, PlmMapper.DraftOf> drafts = new HashMap<>();
        if (!rows.isEmpty()) {
            mapper.draftsOf(byBom.keySet(), ecoId).forEach(d -> drafts.put(d.getSrcBomId(), d));
        }
        Set<Long> itemTargets = new HashSet<>(mapper.itemTargetsOf(ecoId));
        for (var r : rows) {
            if (r.getLevel() > 1) {
                r.setState(itemTargets.contains(r.getParentId()) ? "IN_THIS" : "ITEM");
                continue;
            }
            PlmMapper.DraftOf d = drafts.get(r.getBomId());
            if (d == null) {
                r.setState("NO_DRAFT");
                continue;
            }
            r.setDraftBomId(d.getDraftBomId());
            r.setDraftRev(d.getDraftRev());
            r.setOpenEcoNo(d.getOpenEcoNo());
            r.setState(d.isInThis() ? "IN_THIS" : d.getOpenEcoNo() != null ? "OPEN_ECO" : "ADDABLE");
        }
        rows.sort(java.util.Comparator.comparingInt(io.smarthappy.module.plm.PlmDtos.ImpactRow::getLevel)
                .thenComparing(io.smarthappy.module.plm.PlmDtos.ImpactRow::getParentCd));
        return new io.smarthappy.module.plm.PlmDtos.ImpactResult(where.itemCd(), where.itemNm(), where.date(), sc, rows);
    }

    /** 품목 찾기(입력 보조) — 수주·작업지시와 같은 규칙(코드 앞부분·이름, 사용 중, 20개) */
    @Transactional(readOnly = true)
    public List<io.smarthappy.module.sales.SalesDtos.ItemOption> items(String q) {
        return orders.items(q);
    }

    @Transactional(readOnly = true)
    public List<UserOption> users() {
        return users.activeUsers();   // 회사 사용자만(공통 사용자 고르기, docs/42 15장)
    }

    @Transactional(readOnly = true)
    public List<CustOption> custs() {
        return mapper.salesCusts();
    }

    // ------------------------------------------------------------------ 만들기·고치기

    @Transactional
    public long create(EcoForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("PLM_ECO_CREATE");
        return insert(validate(in, me), me);
    }

    /**
     * 지시 복사(docs/39 27장) — 어느 상태의 지시든 새 작성 중 지시로. 머리는 지금 검증 규칙을 다시 탄다(유효일은 오늘 이후로,
     * 담당 = 복사한 사람, 고객 승인은 필요 여부·고객만 — 승인 기록은 없음). 대상은 품목·설비·공정 행만 대상 저장 규칙으로 옮기고,
     * 특정 작성 중 리비전에 묶인 BOM·라우팅·문서 대상과 지금 사용 중지된 품목·설비·공정은 뺀다(건수로 알림).
     * 결재·서명·첨부·PPAP·통지·표시 LOT 은 옮기지 않는다. 다른 회사 지시는 404
     */
    @Transactional
    public io.smarthappy.module.plm.PlmDtos.EcoCopyResult copy(long ecoId) {
        LoginUser me = me();
        inbox.claimRequestKey("PLM_ECO_COPY");
        EcoRow src = find(ecoId);
        LocalDate today = clock.today();
        LocalDate eff = src.getEffectiveDate().isBefore(today) ? today : src.getEffectiveDate();
        LocalDate until = src.getMarkUntil();
        if (until != null && until.isBefore(eff)) {
            until = "TEMP".equals(src.getChangeKind()) ? eff : null;   // 임시 변경은 끝 날짜가 필수 — 새 유효일로 맞춘다
        }
        EcoForm f = new EcoForm(src.getTitle(), src.getReasonCd(), src.getDescription(), eff.toString(), null,
                src.getCustApprovalReqYn(), src.getCustCd(), src.getChangeKind(), src.getMarkLots(),
                until == null ? null : until.toString(), src.getApplyCond(), src.getStockItemCd(), src.getStockPlantCd(), null);
        long id = insert(validate(f, me), me);
        List<TargetItem> items = new ArrayList<>();
        int skipped = 0;
        for (TargetRow t : mapper.findTargets(ecoId)) {
            if (REVISION_TYPES.contains(t.getTargetType()) || "DOC".equals(t.getTargetType())) {
                skipped++;
                continue;
            }
            ItemRef item = t.getItemCd() == null ? null : mapper.findItem(t.getItemCd());
            String refCd = "EQUIP".equals(t.getTargetType()) ? t.getEquipCd() : "PROC".equals(t.getTargetType()) ? t.getProcCd() : null;
            boolean refOk = refCd == null || ("EQUIP".equals(t.getTargetType()) ? mapper.findEquip(refCd) : mapper.findProc(refCd)) != null;
            if (item == null || !"Y".equals(item.getUseYn()) || !refOk || items.size() >= MAX_TARGETS) {
                skipped++;
                continue;
            }
            items.add(new TargetItem("c" + t.getTargetId(), null, t.getTargetType(), t.getItemCd(), null, refCd, t.getChangeDesc()));
        }
        if (!items.isEmpty()) {
            saveTargets(id, new TargetSet(mapper.lockEco(id).getRowVer(), items));
        }
        return new io.smarthappy.module.plm.PlmDtos.EcoCopyResult(id, items.size(), skipped);
    }

    /** ECR 접수로 만들기 — 요청 내용 복사, 담당 = 접수자, 주 품목은 ITEM 대상 */
    long createFromEcr(EcrRow ecr, LocalDate effective, LoginUser me) {
        long id = insert(new EcoValue(ecr.getTitle(), ecr.getReasonCd(), ecr.getDescription(), effective, me.userId(), "N", null,
                "NA", "DESIGN", null, null, "DATE", null, null), me);
        if (ecr.getItemId() != null) {
            mapper.insertTarget(id, 1, "ITEM", ecr.getItemId(), null, null, null, null, null, null, me.loginId());
        }
        return id;
    }

    /** 기존 작성 중 지시에 ECR 주 품목을 ITEM 대상으로(이미 있으면 그대로) */
    void addItemTarget(long ecoId, long itemId, LoginUser me) {
        List<TargetRow> cur = mapper.findTargets(ecoId);
        if (cur.stream().anyMatch(t -> "ITEM".equals(t.getTargetType()) && Long.valueOf(itemId).equals(t.getItemId()))) {
            return;
        }
        if (cur.size() >= MAX_TARGETS) {
            throw OrderService.fieldError("targets", "TOO_MANY");
        }
        int seq = cur.stream().mapToInt(TargetRow::getSeq).max().orElse(0) + 1;
        mapper.insertTarget(ecoId, seq, "ITEM", itemId, null, null, null, null, null, null, me.loginId());
        EcoRow eco = mapper.lockEco(ecoId);
        mapper.touchEco(ecoId, eco.getRowVer(), me.loginId());
    }

    private long insert(EcoValue v, LoginUser me) {
        String no = numbering.nextOrDefault(RULE, "설계 변경 지시 번호", "ECO{yyMM}-{seq:4}", "MONTH");
        try {
            return mapper.insertEco(no, v, me.loginId());
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    @Transactional
    public void update(long ecoId, EcoForm in) {
        LoginUser me = me();
        int ver = rowVer(in == null ? null : in.rowVer());
        EcoRow cur = lockDraft(ecoId);
        if (cur.getRowVer() != ver) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        EcoValue v = validate(in, me);
        try {
            if (mapper.updateEco(ecoId, ver, v, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /**
     * 대상 한 벌 저장 — 지시 rowVer 로 동시 편집 409, 검증 전부(행 rowKey) → 삭제 → 수정 → 추가. 순번 = 보낸 순서.
     * BOM·라우팅은 작성 중 리비전만, 이미 다른 열린 지시에 묶인 리비전은 OPEN_ECO
     */
    @Transactional
    public void saveTargets(long ecoId, TargetSet in) {
        LoginUser me = me();
        int ver = rowVer(in == null ? null : in.rowVer());
        lockDraft(ecoId);
        if (mapper.touchEco(ecoId, ver, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        List<TargetItem> items = in.targets() == null ? List.of() : in.targets();
        if (items.size() > MAX_TARGETS) {
            throw OrderService.fieldError("targets", "TOO_MANY");
        }
        Map<Long, TargetRow> existing = new HashMap<>();
        mapper.findTargets(ecoId).forEach(t -> existing.put(t.getTargetId(), t));

        record Resolved(TargetItem in, String type, Long itemId, Long bomId, Long routingId, Long equipId, Long procId,
                Long docRevId, String desc) {
        }
        FieldErrors e = FieldErrors.create();
        List<Resolved> resolved = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<Long> kept = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            TargetItem t = items.get(i);
            if (t == null) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            String key = blank(t.rowKey()) ? "r" + i : t.rowKey();
            FieldErrors.RowScope row = e.row(key);
            String desc = rowText(t.changeDesc(), "changeDesc", 1000, row);
            if (t.targetId() != null) {
                TargetRow old = existing.get(t.targetId());
                if (old == null || !kept.add(t.targetId())) {
                    // 다른 지시의 대상 id 이거나 같은 id 두 번
                    throw new BusinessException(HbErrorCode.INVALID_VALUE);
                }
                // 유형·품목·리비전은 고정 — 바꾸려면 지우고 새로
                seen.add(old.getTargetType() + ":" + refKey(old));
                resolved.add(new Resolved(t, old.getTargetType(), old.getItemId(), old.getBomId(), old.getRoutingId(),
                        old.getEquipId(), old.getProcId(), old.getDocRevId(), desc));
                continue;
            }
            String type = t.targetType();
            if (type == null || !TARGET_TYPES.contains(type)) {
                row.add("targetType", "CODE");
                continue;
            }
            if ("DOC".equals(type)) {
                // 문서(2단계 도면 → 16.1 도면·작업표준서·관리계획서·검사기준서): 문서 번호 → 작성 중 리비전, 품목은 선택
                ItemRef item = blank(t.itemCd()) ? null : mapper.findItem(t.itemCd().strip().toUpperCase(Locale.ROOT));
                if (!blank(t.itemCd()) && item == null) {
                    row.add("itemCd", "NOT_FOUND");
                } else if (item != null && !"Y".equals(item.getUseYn())) {
                    row.add("itemCd", "INACTIVE");
                }
                String cd = blank(t.refCd()) ? null : t.refCd().strip().toUpperCase(Locale.ROOT);
                DocRef doc = cd == null || cd.length() > 30 ? null : mapper.findDocRef(cd, ecoId);
                if (cd == null) {
                    row.add("refCd", "REQUIRED");
                } else if (doc == null) {
                    // 없는·사용 중지·다른 회사 문서(RLS)
                    row.add("refCd", "NOT_FOUND");
                } else if (!DOC_TYPES.contains(doc.getDocType())) {
                    row.add("refCd", "DOC_TYPE");
                } else if (doc.getDocRevId() == null) {
                    row.add("refCd", "NO_DRAFT_REV");
                } else if (!"DRAFT".equals(doc.getStatus())) {
                    row.add("refCd", "NOT_DRAFT");
                } else if (doc.getOpenEcoNo() != null) {
                    row.add("refCd", "OPEN_ECO", doc.getOpenEcoNo());
                } else if (item == null ? blank(t.itemCd()) : "Y".equals(item.getUseYn())) {
                    if (!seen.add("DOC:" + doc.getDocRevId())) {
                        row.add("refCd", "DUPLICATE");
                    } else {
                        resolved.add(new Resolved(t, type, item == null ? null : item.getItemId(), null, null, null, null,
                                doc.getDocRevId(), desc));
                    }
                }
                continue;
            }
            if (!REVISION_TYPES.contains(type)) {
                // ITEM·EQUIP·PROC: 품목 코드(+ 설비·공정 코드)
                ItemRef item = blank(t.itemCd()) ? null : mapper.findItem(t.itemCd().strip().toUpperCase(Locale.ROOT));
                Long refId = null;
                if (blank(t.itemCd())) {
                    row.add("itemCd", "REQUIRED");
                } else if (item == null) {
                    row.add("itemCd", "NOT_FOUND");
                } else if (!"Y".equals(item.getUseYn())) {
                    row.add("itemCd", "INACTIVE");
                }
                if (!"ITEM".equals(type)) {
                    String cd = blank(t.refCd()) ? null : t.refCd().strip().toUpperCase(Locale.ROOT);
                    refId = cd == null || cd.length() > 30 ? null : "EQUIP".equals(type) ? mapper.findEquip(cd) : mapper.findProc(cd);
                    if (cd == null) {
                        row.add("refCd", "REQUIRED");
                    } else if (refId == null) {
                        // 없는·사용 중지·다른 회사 설비·공정(RLS 로 안 보임)
                        row.add("refCd", "NOT_FOUND");
                    }
                }
                if (item == null || !"Y".equals(item.getUseYn()) || (!"ITEM".equals(type) && refId == null)) {
                    continue;
                }
                if (!seen.add(type + ":" + item.getItemId() + ":" + refId)) {
                    row.add("ITEM".equals(type) ? "itemCd" : "refCd", "DUPLICATE");
                } else {
                    resolved.add(new Resolved(t, type, item.getItemId(), null, null, "EQUIP".equals(type) ? refId : null,
                            "PROC".equals(type) ? refId : null, null, desc));
                }
                continue;
            }
            if (t.refId() == null) {
                row.add("refId", "REQUIRED");
                continue;
            }
            RefInfo ref = "BOM".equals(type) ? mapper.findBomRef(t.refId(), ecoId) : mapper.findRoutingRef(t.refId(), ecoId);
            if (ref == null) {
                // 없는 리비전 또는 다른 회사 리비전(RLS 로 안 보임)
                row.add("refId", "NOT_FOUND");
            } else if (!"DRAFT".equals(ref.getStatus())) {
                row.add("refId", "NOT_DRAFT");
            } else if (ref.getOpenEcoNo() != null) {
                row.add("refId", "OPEN_ECO", ref.getOpenEcoNo());
            } else if (!seen.add(type + ":" + ref.getRefId())) {
                row.add("refId", "DUPLICATE");
            } else {
                resolved.add(new Resolved(t, type, ref.getItemId(), "BOM".equals(type) ? ref.getRefId() : null,
                        "ROUTING".equals(type) ? ref.getRefId() : null, null, null, null, desc));
            }
        }
        if (!e.isEmpty()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(e.list());
        }
        try {
            for (TargetRow old : existing.values()) {
                if (!kept.contains(old.getTargetId())) {
                    mapper.deleteTarget(old.getTargetId());
                }
            }
            for (int i = 0; i < resolved.size(); i++) {
                Resolved r = resolved.get(i);
                if (r.in().targetId() != null) {
                    mapper.updateTarget(r.in().targetId(), i + 1, r.desc(), me.loginId());
                }
            }
            for (int i = 0; i < resolved.size(); i++) {
                Resolved r = resolved.get(i);
                if (r.in().targetId() == null) {
                    mapper.insertTarget(ecoId, i + 1, r.type(), r.itemId(), r.bomId(), r.routingId(), r.equipId(), r.procId(),
                            r.docRevId(), r.desc(), me.loginId());
                }
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    // ------------------------------------------------------------------ 상신·고객 승인·적용·취소

    /** 상신 — 대상 1건+, 대상 리비전이 지금도 작성 중, 유효일 ≥ 오늘, 고객 지정. 대상 row_ver 고정 후 결재 엔진 */
    @Transactional
    public long submit(long ecoId, SubmitForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("PLM_ECO_SUBMIT");
        int ver = rowVer(in == null ? null : in.rowVer());
        EcoRow cur = lockDraft(ecoId);
        if (cur.getRowVer() != ver) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        FieldErrors e = FieldErrors.create();
        if (cur.getEffectiveDate().isBefore(clock.today())) {
            e.add("effectiveDate", "PAST");
        }
        List<TargetRow> targets = mapper.findTargets(ecoId);
        if (targets.isEmpty()) {
            e.add("targets", "NO_TARGETS");
        }
        for (TargetRow t : targets) {
            if (REVISION_TYPES.contains(t.getTargetType()) && !"DRAFT".equals(t.getRefStatus())) {
                e.row(targetKey(t)).add("refId", "NOT_DRAFT");
            }
        }
        List<LineSpec> lines = new ArrayList<>();
        if (in.lines() != null) {
            for (LineItem l : in.lines()) {
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
        }
        // 다른 모듈 상신 규칙(docs/39 16장) — plm_eco 를 잠근 뒤라 규칙 쪽 저장(FOR SHARE)과 직렬화된다
        submitRules.orderedStream().forEach(rule -> rule.check(ecoId).forEach(f -> {
            if (f.rowKey() == null) {
                e.add(f.field(), f.code(), f.args());
            } else {
                e.row(f.rowKey()).add(f.field(), f.code(), f.args());
            }
        }));
        e.throwIfAny();
        mapper.snapshotBaseRowVer(ecoId, me.loginId());
        if (mapper.submitEco(ecoId, ver, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        String title = blank(in.title()) ? cur.getEcoNo() + " " + cur.getTitle() : in.title().strip();
        long aprId = approvals.submit(DOC_TYPE, String.valueOf(ecoId), title.length() > 200 ? title.substring(0, 200) : title, lines);
        mapper.setAprId(ecoId, aprId);
        return aprId;
    }

    /** 고객 승인 기록 — 고객 승인 필요한 지시, 적용 전까지 */
    @Transactional
    public void custApproval(long ecoId, CustApprovalForm in) {
        LoginUser me = me();
        int ver = rowVer(in == null ? null : in.rowVer());
        EcoRow cur = lock(ecoId);
        if (!"Y".equals(cur.getCustApprovalReqYn()) || !Set.of("DRAFT", "PENDING", "APPROVED").contains(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.PLM_ECO_STATE);
        }
        FieldErrors e = FieldErrors.create();
        String status = in.status();
        if (status == null || !CUST_STATUSES.contains(status)) {
            e.add("custApprovalStatus", "CODE");
        }
        String ref = OrderService.text(in.ref(), "custApprovalRef", 100, e);
        LocalDate on = OrderService.date(in.approvedOn(), "custApprovedOn", false, e);
        if ("APPROVED".equals(status)) {
            if (ref == null) {
                e.add("custApprovalRef", "REQUIRED");
            }
            if (on == null) {
                e.add("custApprovedOn", "REQUIRED");
            }
        }
        if (on != null && on.isAfter(clock.today())) {
            e.add("custApprovedOn", "FUTURE");
        }
        e.throwIfAny();
        if (mapper.setCustApproval(ecoId, ver, status, ref, on, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /**
     * 적용 — 승인된 지시의 대상 리비전을 유효일로 확정. 한 트랜잭션: 하나라도 실패하면 아무것도 확정되지 않는다.
     * 결재 뒤 리비전이 바뀌었으면(row_ver) PLM_TARGET_CHANGED, 확정 검사 실패는 PLM_RELEASE_CHECK — 둘 다 대상 rowKey
     */
    @Transactional
    public void release(long ecoId, EcoActForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("PLM_ECO_RELEASE");
        int ver = rowVer(in == null ? null : in.rowVer());
        EcoRow cur = lock(ecoId);
        if (!"APPROVED".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.PLM_ECO_STATE);
        }
        if (cur.getRowVer() != ver) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if ("Y".equals(cur.getCustApprovalReqYn()) && !"APPROVED".equals(cur.getCustApprovalStatus())) {
            throw new BusinessException(HbErrorCode.PLM_CUST_APPROVAL_REQUIRED);
        }
        LocalDate eff = cur.getEffectiveDate();
        if ("STOCK_OUT".equals(cur.getApplyCond())) {
            // 재고 소진(18장): 유효일 = 가장 이른 날, 실제 적용일 = max(유효일, 오늘). 옛 품목 가용 재고가 남았으면 적용 거부
            if (eff.isBefore(clock.today())) {
                eff = clock.today();
            }
            stockOut(cur);
        } else if (eff.isBefore(clock.today())) {
            throw new BusinessException(HbErrorCode.PLM_EFFECTIVE_PAST);
        }
        List<TargetRow> targets = mapper.findTargets(ecoId);
        FieldErrors changed = FieldErrors.create();
        for (TargetRow t : targets) {
            if (!REVISION_TYPES.contains(t.getTargetType())) {
                continue;
            }
            if (!"DRAFT".equals(t.getRefStatus())) {
                changed.row(targetKey(t)).add("refId", "NOT_DRAFT");
            } else if (t.getBaseRowVer() == null || !t.getBaseRowVer().equals(t.getRefRowVer())) {
                changed.row(targetKey(t)).add("refId", "TARGET_CHANGED");
            }
        }
        if (!changed.isEmpty()) {
            throw new BusinessException(HbErrorCode.PLM_TARGET_CHANGED).withFieldErrors(changed.list());
        }
        String reason = cur.getTitle().length() > 500 ? cur.getTitle().substring(0, 500) : cur.getTitle();
        // D4: 정책 PLM_ECO_REQUIRED=Y 회사에서도 이 트랜잭션의 확정만 허용(트랜잭션 로컬 — 끝나면 사라진다)
        ecoGate.open();
        for (TargetRow t : targets) {
            try {
                if ("BOM".equals(t.getTargetType())) {
                    releaseBom(t, cur.getEcoNo(), reason, eff);
                } else if ("ROUTING".equals(t.getTargetType())) {
                    releaseRouting(t, cur.getEcoNo(), reason, eff);
                }
            } catch (BusinessException ex) {
                throw releaseFailed(t, ex);
            }
        }
        ecoGate.close();
        // 도면(2단계) — BOM·라우팅 뒤에 배포. 실패하면 전체 롤백(새 BOM + 옛 도면이 남지 않게)
        for (TargetRow t : targets) {
            if ("DOC".equals(t.getTargetType())) {
                try {
                    docs.releaseFromChange(t.getDocRevId());
                } catch (BusinessException ex) {
                    throw releaseFailed(t, ex);
                }
            }
        }
        // D7: 적용된 지시의 첨부는 근거로 굳힌다(한 방향, 공통 트리거가 지킨다)
        mapper.lockFiles(String.valueOf(ecoId), me.loginId());
        if (mapper.releaseEco(ecoId, ver, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 소진 품목의 공장 가용 재고 — 0 초과면 409(남은 수량을 칸 오류로). WMS 가 없으면 판정할 수 없으니 거부 */
    private void stockOut(EcoRow cur) {
        StockQuery q = stockQueries.getIfAvailable();
        if (q == null) {
            throw new BusinessException(HbErrorCode.PLM_STOCK_REMAIN)
                    .withFieldErrors(FieldErrors.create().add("stockItemCd", "NO_WMS").list());
        }
        java.math.BigDecimal left = q.available(cur.getStockPlantCd(), List.of(cur.getStockItemId())).get(cur.getStockItemId());
        if (left != null && left.signum() > 0) {
            throw new BusinessException(HbErrorCode.PLM_STOCK_REMAIN)
                    .withFieldErrors(FieldErrors.create().add("stockItemCd", "STOCK_REMAIN", left.stripTrailingZeros().toPlainString()).list());
        }
    }

    private void releaseBom(TargetRow t, String ecoNo, String reason, LocalDate eff) {
        RefInfo ref = mapper.findBomRef(t.getBomId(), null);
        boms.update(t.getBomId(), new io.smarthappy.module.master.bom.BomDtos.HeaderForm(null, null, ref.getRev(),
                ref.getBaseQty(), eff.toString(), ecoNo, reason, ref.getRemark(), ref.getRowVer()));
        RefInfo after = mapper.findBomRef(t.getBomId(), null);
        boms.release(t.getBomId(), new io.smarthappy.module.master.bom.BomDtos.ActionRequest(after.getRowVer(), null, null));
    }

    private void releaseRouting(TargetRow t, String ecoNo, String reason, LocalDate eff) {
        RefInfo ref = mapper.findRoutingRef(t.getRoutingId(), null);
        routings.update(t.getRoutingId(), new io.smarthappy.module.master.routing.RoutingDtos.HeaderForm(null, null, null,
                ref.getRev(), eff.toString(), ecoNo, reason, ref.getRemark(), ref.getRowVer()));
        RefInfo after = mapper.findRoutingRef(t.getRoutingId(), null);
        routings.release(t.getRoutingId(),
                new io.smarthappy.module.master.routing.RoutingDtos.ActionRequest(after.getRowVer(), null, null));
    }

    /** BOM·라우팅 확정 오류를 대상 rowKey 로 감싼다 — 원래 칸 오류(행 rowKey)는 "t{대상}:{원래 rowKey}" 로 */
    private static BusinessException releaseFailed(TargetRow t, BusinessException ex) {
        if (ex.code() == HbErrorCode.CONFLICT) {
            return ex;
        }
        String key = targetKey(t);
        FieldErrors e = FieldErrors.create();
        e.row(key).add("refId", "RELEASE_CHECK", t.getRev());
        List<FieldErrors.FieldError> all = new ArrayList<>(e.list());
        ex.fieldErrors().forEach(f -> all.add(new FieldErrors.FieldError(
                f.rowKey() == null ? key : key + ":" + f.rowKey(), f.field(), f.code(), f.args())));
        return new BusinessException(HbErrorCode.PLM_RELEASE_CHECK, ex.details()).withFieldErrors(all);
    }

    // ------------------------------------------------------------------ 일괄 대체 연동 (4단계, docs/39 14.3)

    /** 일괄 대체 미리보기 — 시작일 = 이 지시의 유효일 */
    @Transactional(readOnly = true)
    public ReplacePreview replacePreview(long ecoId, String fromItemCd, String toItemCd) {
        EcoRow eco = find(ecoId);
        return bomChanges.replacePreview(fromItemCd, toItemCd, eco.getEffectiveDate().toString());
    }

    /**
     * 일괄 대체 실행 + 결과 작성 중 BOM 을 이 지시의 BOM 대상으로 — 한 트랜잭션. 다른 열린 지시에 묶인 리비전이 나오면
     * 전체 롤백(대상 rowKey b{bomId}, OPEN_ECO) — 새 리비전만 생기고 어느 지시에도 안 묶이는 반쪽 상태를 만들지 않는다
     */
    @Transactional
    public List<ReplaceDone> replace(long ecoId, ReplaceForm in) {
        LoginUser me = me();
        int ver = rowVer(in == null ? null : in.rowVer());
        EcoRow cur = lockDraft(ecoId);
        if (cur.getRowVer() != ver) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        List<ReplaceDone> done = bomChanges.replace(new ReplaceRequest(in.fromItemCd(), in.toItemCd(),
                cur.getEffectiveDate().toString(), in.bomIds()));
        List<TargetRow> targets = mapper.findTargets(ecoId);
        Set<Long> have = new HashSet<>();
        targets.forEach(t -> {
            if (t.getBomId() != null) {
                have.add(t.getBomId());
            }
        });
        int seq = targets.stream().mapToInt(TargetRow::getSeq).max().orElse(0);
        String desc = rowDesc(in.fromItemCd(), in.toItemCd());
        FieldErrors e = FieldErrors.create();
        List<RefInfo> add = new ArrayList<>();
        for (ReplaceDone d : done) {
            if (!have.add(d.bomId())) {
                continue;
            }
            RefInfo ref = mapper.findBomRef(d.bomId(), ecoId);
            if (ref.getOpenEcoNo() != null) {
                e.row("b" + d.bomId()).add("refId", "OPEN_ECO", ref.getOpenEcoNo());
            } else {
                add.add(ref);
            }
        }
        if (targets.size() + add.size() > MAX_TARGETS) {
            e.add("targets", "TOO_MANY");
        }
        if (!e.isEmpty()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(e.list());
        }
        try {
            for (RefInfo ref : add) {
                mapper.insertTarget(ecoId, ++seq, "BOM", ref.getItemId(), ref.getRefId(), null, null, null, null, desc, me.loginId());
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
        if (mapper.touchEco(ecoId, ver, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return done;
    }

    private static String rowDesc(String from, String to) {
        String d = (from == null ? "" : from.strip().toUpperCase(Locale.ROOT)) + " → " + (to == null ? "" : to.strip().toUpperCase(Locale.ROOT));
        return d.length() > 1000 ? d.substring(0, 1000) : d;
    }

    /** 취소 — 작성 중·승인됨(결재 중이면 먼저 회수). 대상 리비전은 작성 중 그대로 남는다 */
    @Transactional
    public void cancel(long ecoId, EcoActForm in) {
        LoginUser me = me();
        int ver = rowVer(in == null ? null : in.rowVer());
        EcoRow cur = lock(ecoId);
        if (!"DRAFT".equals(cur.getStatus()) && !"APPROVED".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.PLM_ECO_STATE);
        }
        FieldErrors e = FieldErrors.create();
        String reason = OrderService.text(in.reason(), "reason", 500, e);
        if (reason == null) {
            e.add("reason", "REQUIRED");
        }
        e.throwIfAny();
        if (mapper.cancelEco(ecoId, ver, reason, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    // ------------------------------------------------------------------ 검증·도움

    private EcoValue validate(EcoForm in, LoginUser me) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String title = OrderService.text(in.title(), "title", 200, e);
        if (title == null) {
            e.add("title", "REQUIRED");
        }
        String reason = reason(in.reasonCd(), mapper, e);
        String desc = OrderService.text(in.description(), "description", 4000, e);
        LocalDate eff = OrderService.date(in.effectiveDate(), "effectiveDate", true, e);
        if (eff != null && eff.isBefore(clock.today())) {
            e.add("effectiveDate", "PAST");
        }
        long owner = in.ownerId() == null ? me.userId() : in.ownerId();
        if (in.ownerId() != null && !users.isActive(owner)) {
            e.add("ownerId", "NOT_FOUND");
        }
        String req = blank(in.custApprovalReqYn()) ? "N" : in.custApprovalReqYn();
        Long custId = null;
        if (!"Y".equals(req) && !"N".equals(req)) {
            e.add("custApprovalReqYn", "CODE");
        } else if ("Y".equals(req)) {
            if (blank(in.custCd())) {
                e.add("custCd", "REQUIRED");
            } else {
                CustOption c = mapper.findSalesCust(in.custCd().strip().toUpperCase(Locale.ROOT));
                if (c == null) {
                    e.add("custCd", "NOT_SALES");
                } else {
                    custId = c.getCustId();
                }
            }
        }
        // 변경 종류·표시 구간 (3b, docs/39 13.1)
        String kind = blank(in.changeKind()) ? "DESIGN" : in.changeKind();
        if (!CHANGE_KINDS.contains(kind)) {
            e.add("changeKind", "CODE");
        }
        if (in.markLots() != null && (in.markLots() < 1 || in.markLots() > MAX_MARK_LOTS)) {
            e.add("markLots", "RANGE", 1, MAX_MARK_LOTS);
        }
        LocalDate until = OrderService.date(in.markUntil(), "markUntil", false, e);
        if (until != null && "DESIGN".equals(kind)) {
            e.add("markUntil", "MARK_UNTIL_KIND");
        } else if (until == null && "TEMP".equals(kind) && blank(in.markUntil())) {
            e.add("markUntil", "REQUIRED");
        } else if (until != null && eff != null && until.isBefore(eff)) {
            e.add("markUntil", "BEFORE_EFFECTIVE");
        }
        // 적용 조건 (18장) — STOCK_OUT 은 소진 품목·공장 필수, WMS 가 없으면 재고를 볼 수 없어 거부
        String cond = blank(in.applyCond()) ? "DATE" : in.applyCond();
        Long stockItemId = null;
        String stockPlant = null;
        if (!APPLY_CONDS.contains(cond)) {
            e.add("applyCond", "CODE");
        } else if ("STOCK_OUT".equals(cond)) {
            if (stockQueries.getIfAvailable() == null) {
                e.add("applyCond", "NO_WMS");
            }
            if (blank(in.stockItemCd())) {
                e.add("stockItemCd", "REQUIRED");
            } else {
                ItemRef i = mapper.findItem(in.stockItemCd().strip().toUpperCase(Locale.ROOT));
                if (i == null || !"Y".equals(i.getUseYn())) {
                    e.add("stockItemCd", "NOT_FOUND");
                } else {
                    stockItemId = i.getItemId();
                }
            }
            if (blank(in.stockPlantCd())) {
                e.add("stockPlantCd", "REQUIRED");
            } else {
                stockPlant = in.stockPlantCd().strip().toUpperCase(Locale.ROOT);
                if (stockPlant.length() > 20 || !mapper.plantUsable(stockPlant)) {
                    e.add("stockPlantCd", "NOT_FOUND");
                    stockPlant = null;
                }
            }
        } else if (!blank(in.stockItemCd()) || !blank(in.stockPlantCd())) {
            e.add("stockItemCd", "STOCK_COND");
        }
        e.throwIfAny();
        return new EcoValue(title, reason, desc, eff, owner, req, custId, "Y".equals(req) ? "PENDING" : "NA", kind,
                in.markLots(), until, cond, stockItemId, stockPlant);
    }

    static String reason(String raw, PlmMapper mapper, FieldErrors e) {
        if (blank(raw)) {
            e.add("reasonCd", "REQUIRED");
            return null;
        }
        String code = raw.strip().toUpperCase(Locale.ROOT);
        if (code.length() > 30 || !mapper.reasonUsable(code)) {
            e.add("reasonCd", "NO_SUCH_CODE");
            return null;
        }
        return code;
    }

    /** 기록 무결성 점검(docs/52 Y1): 서명 대상 ECO 의 지금 내용 해시(상세·결재 서명과 같은 정규 글자) — 없으면 빈 Optional */
    @Transactional(readOnly = true)
    public java.util.Optional<Set<String>> signHashes(long ecoId) {
        List<EcoRow> rows = mapper.findEcos(null, null, null, ecoId, null, 1);
        if (rows.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(Set.of(PlmSignContent.ecoHash(rows.get(0), mapper.findTargets(ecoId))));
    }

    private EcoRow find(long ecoId) {
        List<EcoRow> rows = mapper.findEcos(null, null, null, ecoId, null, 1);
        if (rows.isEmpty()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    private EcoRow lock(long ecoId) {
        EcoRow cur = mapper.lockEco(ecoId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return cur;
    }

    private EcoRow lockDraft(long ecoId) {
        EcoRow cur = lock(ecoId);
        if (!"DRAFT".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.PLM_ECO_STATE);
        }
        return cur;
    }

    private static String rowText(String raw, String field, int max, FieldErrors.RowScope row) {
        FieldErrors tmp = FieldErrors.create();
        String s = OrderService.text(raw, field, max, tmp);
        tmp.list().forEach(f -> row.add(f.field(), f.code(), f.args()));
        return s;
    }

    static String targetKey(TargetRow t) {
        return "t" + t.getTargetId();
    }

    /** saveTargets 의 중복 키와 같은 모양 — ITEM·EQUIP·PROC = 품목:설비·공정, BOM·ROUTING = 리비전 id */
    private static String refKey(TargetRow t) {
        return switch (t.getTargetType()) {
            case "ITEM" -> t.getItemId() + ":null";
            case "EQUIP" -> t.getItemId() + ":" + t.getEquipId();
            case "PROC" -> t.getItemId() + ":" + t.getProcId();
            case "DOC" -> String.valueOf(t.getDocRevId());
            default -> String.valueOf(t.getBomId() != null ? t.getBomId() : t.getRoutingId());
        };
    }

    static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "uq_plm_eco_target_open" -> OrderService.fieldError("targets", "OPEN_ECO_ANY");
            case "ck_plm_eco_target_draft" -> OrderService.fieldError("targets", "NOT_DRAFT");
            case "fk_plm_eco_target_comp", "ck_plm_eco_target_item" -> OrderService.fieldError("targets", "NOT_FOUND");
            case "fk_plm_eco_reason", "fk_plm_ecr_reason" -> OrderService.fieldError("reasonCd", "NO_SUCH_CODE");
            case "fk_plm_eco_ref_comp" -> OrderService.fieldError("ownerId", "NOT_FOUND");
            default -> e;
        };
    }

    static int rowVer(Integer v) {
        if (v == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return v;
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
