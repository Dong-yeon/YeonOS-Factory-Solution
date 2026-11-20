package io.smarthappy.module.apqp;

import static io.smarthappy.module.apqp.ApqpInputs.blank;
import static io.smarthappy.module.apqp.ApqpInputs.date;
import static io.smarthappy.module.apqp.ApqpInputs.fieldError;
import static io.smarthappy.module.apqp.ApqpInputs.me;
import static io.smarthappy.module.apqp.ApqpInputs.rating;
import static io.smarthappy.module.apqp.ApqpInputs.text;
import static io.smarthappy.module.apqp.ApqpInputs.upper;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.apqp.ApqpDtos.ActionForm;
import io.smarthappy.module.apqp.ApqpDtos.ActionRow;
import io.smarthappy.module.apqp.ApqpDtos.ApSetRow;
import io.smarthappy.module.apqp.ApqpDtos.ChainForm;
import io.smarthappy.module.apqp.ApqpDtos.ChainRow;
import io.smarthappy.module.apqp.ApqpDtos.CharOption;
import io.smarthappy.module.apqp.ApqpDtos.CharRef;
import io.smarthappy.module.apqp.ApqpDtos.FailForm;
import io.smarthappy.module.apqp.ApqpDtos.FailRow;
import io.smarthappy.module.apqp.ApqpDtos.FmeaDetail;
import io.smarthappy.module.apqp.ApqpDtos.FmeaExportRequest;
import io.smarthappy.module.apqp.ApqpDtos.FmeaFilter;
import io.smarthappy.module.apqp.ApqpDtos.FmeaForm;
import io.smarthappy.module.apqp.ApqpDtos.FmeaPage;
import io.smarthappy.module.apqp.ApqpDtos.FmeaRow;
import io.smarthappy.module.apqp.ApqpDtos.FmeaSort;
import io.smarthappy.module.apqp.ApqpDtos.FmeaValue;
import io.smarthappy.module.apqp.ApqpDtos.FuncForm;
import io.smarthappy.module.apqp.ApqpDtos.FuncRow;
import io.smarthappy.module.apqp.ApqpDtos.ItemOption;
import io.smarthappy.module.apqp.ApqpDtos.MoveForm;
import io.smarthappy.module.apqp.ApqpDtos.NodeForm;
import io.smarthappy.module.apqp.ApqpDtos.NodeRow;
import io.smarthappy.module.apqp.ApqpDtos.RelinkForm;
import io.smarthappy.module.apqp.ApqpDtos.RelinkPlan;
import io.smarthappy.module.apqp.ApqpDtos.RelinkStep;
import io.smarthappy.module.apqp.ApqpDtos.RoutingOption;
import io.smarthappy.module.apqp.ApqpDtos.RiskForm;
import io.smarthappy.module.apqp.ApqpDtos.RiskRow;
import io.smarthappy.module.apqp.ApqpDtos.StepOption;
import io.smarthappy.module.apqp.ApqpDtos.SubmitForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * PFMEA (apqp-fmea, docs/41 4장). 구조 ITEM › STEP › ELEMENT, 기능, 고장(FE·FM·FC — 종류는 구조 수준이 정한다), 사슬 FM → FE,
 * 위험(FM, FC), 조치. 같은 규칙을 트리거가 한 번 더 지킨다(직접 SQL·동시 요청) — 서비스는 칸 오류로 먼저 알려 준다.
 * - 작성 중(DRAFT)만 고친다. 확정(APPROVE) = 위험행 O·D·AP 다 있음 + 고장 형태마다 사슬·위험행 → 이전 확정본은 폐기(OBSOLETE)
 * - 새 리비전 = 확정본 복사(구조·사슬·위험·조치 그대로). 확정본의 조치 진행은 같은 번호의 작성 중 리비전이 없을 때만(P7)
 * - 하위 행을 바꿀 때마다 머리를 잠그고(lockFmea) row_ver 를 올린다 — 같은 FMEA 를 두 사람이 동시에 고쳐도 순서가 정해진다
 */
@Service
@RequiredArgsConstructor
public class FmeaService {

    static final String PROG = "apqp-fmea";
    /** DFMEA 화면(docs/41 13장 A5-2, D8) — 같은 서비스·같은 표, 기록 유형마다 화면 권한을 나눈다 */
    static final String DFMEA_PROG = "apqp-dfmea";
    static final String PFMEA = "PFMEA";
    static final String DFMEA = "DFMEA";
    static final String DFMEA_RULE = "APQP_DFMEA";
    /** PMS 이슈 화면 — 조치 → 이슈(A3-5) 단추·표시 여부만 */
    static final String ISSUE_PROG = "pms-issue";
    static final String RULE = "APQP_FMEA";
    static final int MAX_NODES = 2000;
    static final int MAX_ROWS = 20000;

    private final ApqpRepository repo;
    private final UserDirectory users;
    private final FmeaCalc calc;
    private final NumberingService numbering;
    private final InboxService inbox;
    private final PolicyService policy;
    private final PermissionService permission;
    private final ApqpApproval approval;
    private final ApqpChangeWatch changeWatch;
    private final ExcelExportService excel;
    private final org.springframework.beans.factory.ObjectProvider<io.smarthappy.module.qms.link.DocLookup> docs;
    private final io.smarthappy.platform.time.BizClock clock;

    /** 기록 유형 → 화면 */
    static String prog(String type) {
        return DFMEA.equals(type) ? DFMEA_PROG : PROG;
    }

    /** 3단 구조의 뿌리·2단·3단 노드 종류 */
    static String rootKind(String type) {
        return DFMEA.equals(type) ? "SYSTEM" : "ITEM";
    }

    static String midKind(String type) {
        return DFMEA.equals(type) ? "SUBSYSTEM" : "STEP";
    }

    static String leafKind(String type) {
        return DFMEA.equals(type) ? "COMPONENT" : "ELEMENT";
    }

    static boolean isRoot(String kind) {
        return "ITEM".equals(kind) || "SYSTEM".equals(kind);
    }

    static boolean isMid(String kind) {
        return "STEP".equals(kind) || "SUBSYSTEM".equals(kind);
    }

    // ------------------------------------------------------------------ 목록 (grid)

    static final List<String> DEFAULT_SORT = List.of("fmeaNo,desc", "rev,desc");

    /** status: 비우면 현행(작성 중·확정), ALL, 또는 하나 */
    @Transactional(readOnly = true)
    public FmeaPage grid(String type, String keyword, String status, Long projId, Integer page, Integer size, List<String> sort,
            Boolean total) {
        FmeaFilter f = filter(type, keyword, status, projId);
        PageQuery pq = PageQuery.parse(page, size, sort, total, FmeaSort.values(), policy.intValue(Policies.GRID_PAGE_SIZE_MAX),
                DEFAULT_SORT);
        List<FmeaRow> rows = repo.findFmeaPage(f, pq);
        Map<Long, Boolean> cache = new HashMap<>();
        rows.forEach(r -> markDrawing(r, cache));
        return new FmeaPage(rows, pq.isWithTotal() ? repo.countFmeas(f) : null);
    }

    /** 목록 엑셀(EXCEL) — grid 와 같은 조건·정렬, 재검토 칸은 LABEL 열(X2)이 화면과 같은 문구로 */
    @Transactional(readOnly = true)
    public long export(String type, FmeaExportRequest req, HttpServletResponse response) throws IOException {
        FmeaExportRequest r = req == null ? new FmeaExportRequest(null, null, null, null, null) : req;
        List<ColumnSpec> cols = ColumnSpec.selectExportable(gridColumns(type), r.columns());
        FmeaFilter f = filter(type, r.keyword(), r.status(), r.projId());
        PageQuery pq = PageQuery.sortOnly(r.sort(), FmeaSort.values(), DEFAULT_SORT);
        List<String> keys = new ArrayList<>();
        if (!blank(r.keyword())) {
            keys.add("keyword");
        }
        if (!blank(r.status())) {
            keys.add("status");
        }
        if (r.projId() != null) {
            keys.add("projId");
        }
        return excel.export(response, new ExportJob(prog(type), DFMEA.equals(type) ? "prog.apqpDfmea" : "prog.apqpFmea", cols,
                repo.countFmeas(f), keys),
                w -> {
                    Map<Long, Boolean> cache = new HashMap<>();
                    repo.eachFmea(f, pq, row -> {
                        markDrawing(row, cache);
                        w.accept(row);
                    });
                });
    }

    private FmeaFilter filter(String type, String keyword, String status, Long projId) {
        String st = upper(status);
        List<String> statuses;
        if (st == null) {
            statuses = List.of("DRAFT", "IN_APPROVAL", "RELEASED");
        } else if ("ALL".equals(st)) {
            statuses = null;
        } else if (Set.of("DRAFT", "IN_APPROVAL", "RELEASED", "OBSOLETE").contains(st)) {
            statuses = List.of(st);
        } else {
            throw fieldError("status", "CODE");
        }
        return new FmeaFilter(ApqpInputs.like(keyword), statuses, projId, type);
    }

    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("fmeaNo", "apqp.col.fmeaNo", 120).withSort().withPriority(1),
            ColumnSpec.of("rev", "apqp.col.rev", ColumnType.INT, 50).withSort(),
            ColumnSpec.text("title", "apqp.col.title", 220).withSort().withPriority(1).withFlex(),
            ColumnSpec.text("itemCd", "apqp.col.itemCd", 120).withSort(),
            ColumnSpec.text("itemNm", "apqp.col.itemNm", 160).withPriority(3),
            ColumnSpec.text("routingText", "apqp.col.routing", 100).withPriority(3),
            ColumnSpec.code("status", "apqp.col.status", "APQP_FMEA_STATUS", 80).withSort().withPriority(1),
            ColumnSpec.labels("review", "apqp.col.review", "apqp.review.", 110, "ROUTING", "BOM", "DRAWING"),
            ColumnSpec.of("highCnt", "apqp.col.highCnt", ColumnType.INT, 70).withSort(),
            ColumnSpec.of("openActionCnt", "apqp.col.openActions", ColumnType.INT, 80).withSort(),
            ColumnSpec.of("updatedAt", "apqp.col.updatedAt", ColumnType.DATETIME, 140).withSort().withPriority(3));

    /** DFMEA 목록 — 라우팅 칸 자리에 BOM */
    static final List<ColumnSpec> DFMEA_GRID_COLUMNS = GRID_COLUMNS.stream()
            .map(c -> "routingText".equals(c.field()) ? ColumnSpec.text("bomText", "apqp.col.bom", 100).withPriority(3) : c).toList();

    public List<ColumnSpec> gridColumns(String type) {
        return DFMEA.equals(type) ? DFMEA_GRID_COLUMNS : GRID_COLUMNS;
    }

    // ------------------------------------------------------------------ 상세

    @Transactional(readOnly = true)
    public FmeaDetail get(long fmeaId) {
        FmeaRow f = found(fmeaId);
        boolean save = permission.actions(prog(f.getFmeaType())).contains(Action.SAVE);
        boolean df = DFMEA.equals(f.getFmeaType());
        boolean editable = save && "DRAFT".equals(f.getStatus());
        boolean actionEditable = save && ("DRAFT".equals(f.getStatus()) || ("RELEASED".equals(f.getStatus()) && !f.isDraftExists()));
        List<StepOption> steps = f.getRoutingId() == null ? List.of() : repo.findSteps(f.getRoutingId());
        List<FuncRow> funcs = repo.findFuncs(fmeaId);
        // M4 겹침 비교 = 기능에 붙은 특성(마스터 특별특성)
        List<CharRef> chars = funcs.stream().filter(fn -> fn.getCharId() != null)
                .map(fn -> CharRef.of(fn.getCharId(), fn.getCharNo(), fn.getCharNm(), fn.getCharSpecial())).toList();
        return new FmeaDetail(f, repo.findNodes(fmeaId), funcs, repo.findFails(fmeaId), repo.findChains(fmeaId),
                repo.findRisks(fmeaId), repo.findActions(fmeaId), steps, editable, actionEditable, approval.on(),
                changeWatch.since(ApqpDocService.FMEA, fmeaId, f.getStatus(), f.getItemId(), f.getReleasedAt(), chars, null),
                // 조치 → PMS 이슈(A3-5)는 PFMEA 만 — ApqpLookup 이 PFMEA 만 보인다(docs/41 13.7)
                !df && permission.actions(ISSUE_PROG).contains(Action.SEARCH), !df && permission.actions(ISSUE_PROG).contains(Action.SAVE));
    }

    @Transactional(readOnly = true)
    public List<CharOption> chars(long fmeaId) {
        FmeaRow f = found(fmeaId);
        return charOptions(f);
    }

    // ------------------------------------------------------------------ 머리

    @Transactional
    public long create(String type, FmeaForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("APQP_FMEA_CREATE");
        FmeaValue v = validate(type, in, null);
        if (DFMEA.equals(type)) {
            repo.ensureNumberingRule(DFMEA_RULE, "DFMEA 번호", "DF-{yyyy}-{seq:4}", "YEAR", me.loginId());
        } else {
            repo.ensureNumberingRule(RULE, "PFMEA 번호", "FM-{yyyy}-{seq:4}", "YEAR", me.loginId());
        }
        String no = numbering.next(DFMEA.equals(type) ? DFMEA_RULE : RULE);
        long id = drawingChecked(() -> repo.insertFmea(no, 1, v, null, me.loginId()));
        ItemOption item = repo.findItemByCd(in.itemCd().strip());
        long root = repo.insertNode(id, null, rootKind(type), 0, item == null ? v.title() : item.getItemCd() + " " + item.getItemNm(), null,
                null, me.loginId());
        // BOM 1~2단계로 하위 시스템·구성품 시작(D6, 선택) — 1단계 = BOM 줄, 2단계 = 그 자품목의 지금 유효한 확정 BOM 줄
        if (DFMEA.equals(type) && Boolean.TRUE.equals(in.fromBom()) && v.bomId() != null) {
            pullBom(id, root, v.bomId(), me.loginId());
        }
        // 라우팅 단계로 공정 단계 시작(선택) — 이름 = "공정순서 공정명", 나중에 고친다
        if (Boolean.TRUE.equals(in.fromRouting()) && v.routingId() != null) {
            int seq = 0;
            for (StepOption s : repo.findSteps(v.routingId())) {
                repo.insertNode(id, root, "STEP", ++seq, s.getOpSeq() + " " + s.getProcNm(), s.getStepId(), null, me.loginId());
            }
        }
        return id;
    }

    @Transactional
    public void update(long fmeaId, FmeaForm in) {
        LoginUser me = me();
        FmeaRow cur = editableHead(fmeaId);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FmeaValue v = validate(cur.getFmeaType(), in, cur);
        if (!Objects.equals(v.routingId(), cur.getRoutingId())
                && repo.findNodes(fmeaId).stream().anyMatch(n -> n.getStepId() != null)) {
            throw fieldError("routingId", "APQP_ROUTING_USED");
        }
        if (!Objects.equals(v.bomId(), cur.getBomId())
                && repo.findNodes(fmeaId).stream().anyMatch(n -> n.getRefItemId() != null)) {
            throw fieldError("bomId", "APQP_BOM_USED");
        }
        if (drawingChecked(() -> repo.updateFmea(fmeaId, v, in.rowVer(), me.loginId())) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (!Objects.equals(v.apSetId(), cur.getApSetId())) {
            calc.recalc(fmeaId);
        }
    }

    // ------------------------------------------------------------------ 라우팅 바꾸기 (11.9)

    /** 미리보기 — 작성 중만, 같은 품목의 확정 라우팅 리비전만. 바꾸지 않는다 */
    @Transactional(readOnly = true)
    public RelinkPlan relinkPreview(long fmeaId, Long routingId) {
        FmeaRow cur = found(fmeaId);
        if (!"DRAFT".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.APQP_FMEA_STATE);
        }
        return relinkPlan(cur, routingId);
    }

    /**
     * 적용 — 미리보기 결과를 받지 않고 서버가 다시 계산한다. 머리 rowVer 가 다르면(그 사이 PFMEA 가 고쳐짐) 409.
     * 순서(트리거 규칙 그대로): 단계 노드의 단계 비우기 → 머리 라우팅 바꾸기 → 짝 있는 노드에 새 단계. 짝 없는 노드는 글자 단계(이름 "10 용접").
     * 바뀐 내용은 노드·머리 감사 기록(hb_audit)에 남는다 — 글자로 바뀐 노드는 옛 step_id 가 감사 기록에 있어 사람이 다시 이을 수 있다
     */
    @Transactional
    public RelinkPlan relink(long fmeaId, RelinkForm in) {
        LoginUser me = me();
        FmeaRow cur = editableHead(fmeaId);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.rowVer() != cur.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        RelinkPlan plan = relinkPlan(cur, in.routingId());
        Map<Long, NodeRow> nodes = repo.findNodes(fmeaId).stream().collect(Collectors.toMap(NodeRow::getNodeId, n -> n));
        for (RelinkStep st : plan.steps()) {
            NodeRow n = nodes.get(st.nodeId());
            String nm = st.matched() ? n.getNodeNm() : st.textNm();
            if (repo.updateNode(n.getNodeId(), nm, null, null, n.getRowVer(), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
            n.setRowVer(n.getRowVer() + 1);
        }
        FmeaValue v = new FmeaValue(cur.getItemId(), plan.routingId(), cur.getProjId(), cur.getTitle(), cur.getScope(), cur.getTeam(),
                cur.getDrawingRev(), cur.getApSetId(), cur.getFmeaType(), null, null);
        // 노드 고치기가 머리 row_ver 를 올렸을 수 있다 — 잠근 머리의 지금 값으로
        if (repo.updateFmea(fmeaId, v, found(fmeaId).getRowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        for (RelinkStep st : plan.steps()) {
            if (st.matched()) {
                NodeRow n = nodes.get(st.nodeId());
                if (repo.updateNode(n.getNodeId(), n.getNodeNm(), st.toStepId(), null, n.getRowVer(), me.loginId()) == 0) {
                    throw new BusinessException(HbErrorCode.CONFLICT);
                }
            }
        }
        return new RelinkPlan(plan.routingId(), plan.routingText(), plan.steps(), plan.matched(), plan.unmatched(), found(fmeaId).getRowVer());
    }

    private RelinkPlan relinkPlan(FmeaRow cur, Long routingId) {
        if (routingId == null) {
            throw fieldError("routingId", "REQUIRED");
        }
        if (routingId.equals(cur.getRoutingId())) {
            throw fieldError("routingId", "APQP_ROUTING_SAME");
        }
        RoutingOption r = repo.findRoutings(cur.getItemId()).stream().filter(x -> x.getRoutingId() == routingId).findFirst()
                .orElseThrow(() -> fieldError("routingId", "NOT_FOUND"));
        if (!"RELEASED".equals(r.getStatus())) {
            throw fieldError("routingId", "APQP_ROUTING_NOT_RELEASED");
        }
        List<StepOption> from = cur.getRoutingId() == null ? List.of() : repo.findSteps(cur.getRoutingId());
        Map<Long, StepOption> fromById = from.stream().collect(Collectors.toMap(StepOption::getStepId, x -> x));
        Map<Long, StepOption> pairs = RoutingRelink.pair(from, repo.findSteps(routingId));
        List<RelinkStep> steps = new ArrayList<>();
        for (NodeRow n : repo.findNodes(cur.getFmeaId())) {
            if (!"STEP".equals(n.getNodeKind()) || n.getStepId() == null) {
                continue;
            }
            StepOption old = fromById.get(n.getStepId());
            StepOption to = pairs.get(n.getStepId());
            steps.add(new RelinkStep(n.getNodeId(), n.getNodeNm(), n.getOpSeq(), n.getProcCd(), n.getProcNm(), n.getStepId(),
                    to == null ? null : to.getStepId(), to != null, to == null ? RoutingRelink.text(old, n.getNodeNm()) : null));
        }
        int matched = (int) steps.stream().filter(RelinkStep::matched).count();
        return new RelinkPlan(routingId, r.getPlantCd() + " · " + r.getAltNo() + " · " + r.getRev(), steps, matched, steps.size() - matched,
                cur.getRowVer());
    }

    /** PFMEA 고장 영향 제안(A5-2b D7) — 같은 품목 확정 DFMEA 의 고장 형태. 고르면 화면이 글자를 복사하고 출처(srcFailId)를 남긴다 */
    @Transactional(readOnly = true)
    public List<ApqpDtos.DfmeaSource> dfmeaSources(long fmeaId) {
        FmeaRow f = found(fmeaId);
        return PFMEA.equals(f.getFmeaType()) ? repo.findDfmeaSources(f.getItemId()) : List.of();
    }

    // ------------------------------------------------------------------ BOM 다시 잇기 (DFMEA, A5-2b D6)

    @Transactional(readOnly = true)
    public ApqpDtos.BomRelinkPlan bomRelinkPreview(long fmeaId, Long bomId) {
        FmeaRow cur = found(fmeaId);
        if (!"DRAFT".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.APQP_FMEA_STATE);
        }
        return bomRelinkPlan(cur, bomId);
    }

    /**
     * 적용 — 서버가 다시 계산, 머리 rowVer 가 다르면 409. 짝 = 하위 시스템의 자품목이 새 BOM 1단계 줄에 있음(같은 품목).
     * 순서(트리거 규칙): 짝 없는 하위 시스템의 자품목 연결 비우기(이름·기능·고장은 그대로 = 글자 노드) → 머리 BOM 바꾸기 → 새 BOM 줄 중 구조에 없는
     * 자품목을 하위 시스템으로 더하기(구성품은 사람이). 구성품의 자품목은 하위 시스템 품목의 BOM 에서 온 것이라 그대로
     */
    @Transactional
    public ApqpDtos.BomRelinkPlan bomRelink(long fmeaId, ApqpDtos.BomRelinkForm in) {
        LoginUser me = me();
        FmeaRow cur = editableHead(fmeaId);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.rowVer() != cur.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        ApqpDtos.BomRelinkPlan plan = bomRelinkPlan(cur, in.bomId());
        Map<Long, NodeRow> nodes = repo.findNodes(fmeaId).stream().collect(Collectors.toMap(NodeRow::getNodeId, n -> n));
        for (ApqpDtos.BomRelinkNode x : plan.nodes()) {
            if (!x.matched()) {
                NodeRow n = nodes.get(x.nodeId());
                if (repo.updateNodeRef(n.getNodeId(), null, n.getRowVer(), me.loginId()) == 0) {
                    throw new BusinessException(HbErrorCode.CONFLICT);
                }
            }
        }
        FmeaValue v = new FmeaValue(cur.getItemId(), null, cur.getProjId(), cur.getTitle(), cur.getScope(), cur.getTeam(), null,
                cur.getApSetId(), cur.getFmeaType(), plan.bomId(), cur.getDocRevId());
        if (repo.updateFmea(fmeaId, v, found(fmeaId).getRowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        long root = nodes.values().stream().filter(n -> isRoot(n.getNodeKind())).mapToLong(NodeRow::getNodeId).findFirst().orElseThrow();
        int seq = nodes.values().stream().filter(n -> Objects.equals(n.getParentId(), root)).mapToInt(NodeRow::getSeq).max().orElse(0);
        if (nodes.size() + plan.added().size() > MAX_NODES) {
            throw fieldError("bomId", "APQP_TOO_MANY_ROWS", MAX_NODES);
        }
        for (ApqpDtos.BomChild c : plan.added()) {
            repo.insertNodeRef(fmeaId, root, "SUBSYSTEM", ++seq, c.getItemCd() + " " + c.getItemNm(), c.getItemId(), me.loginId());
        }
        return new ApqpDtos.BomRelinkPlan(plan.bomId(), plan.bomText(), plan.nodes(), plan.added(), plan.matched(), plan.unmatched(),
                found(fmeaId).getRowVer());
    }

    private ApqpDtos.BomRelinkPlan bomRelinkPlan(FmeaRow cur, Long bomId) {
        if (!DFMEA.equals(cur.getFmeaType())) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (bomId == null) {
            throw fieldError("bomId", "REQUIRED");
        }
        if (bomId.equals(cur.getBomId())) {
            throw fieldError("bomId", "APQP_BOM_SAME");
        }
        ApqpDtos.BomOption b = repo.findReleasedBoms(cur.getItemId()).stream().filter(x -> x.getBomId() == bomId).findFirst()
                .orElseThrow(() -> fieldError("bomId", "NOT_FOUND"));
        List<ApqpDtos.BomChild> lines = repo.findBomChildren(bomId);
        Set<Long> lineItems = lines.stream().map(ApqpDtos.BomChild::getItemId).collect(Collectors.toSet());
        List<ApqpDtos.BomRelinkNode> out = new ArrayList<>();
        Set<Long> linked = new HashSet<>();
        for (NodeRow n : repo.findNodes(cur.getFmeaId())) {
            if (!"SUBSYSTEM".equals(n.getNodeKind()) || n.getRefItemId() == null) {
                continue;
            }
            linked.add(n.getRefItemId());
            out.add(new ApqpDtos.BomRelinkNode(n.getNodeId(), n.getNodeNm(), n.getRefItemCd(), lineItems.contains(n.getRefItemId())));
        }
        // 같은 자품목이 BOM 에 두 줄이어도 하위 시스템은 하나
        Set<Long> seen = new HashSet<>(linked);
        List<ApqpDtos.BomChild> added = lines.stream().filter(c -> seen.add(c.getItemId())).toList();
        int matched = (int) out.stream().filter(ApqpDtos.BomRelinkNode::matched).count();
        return new ApqpDtos.BomRelinkPlan(bomId, b.getAltNo() + " · " + b.getRev(), out, added, matched, out.size() - matched,
                cur.getRowVer());
    }

    /** 작성 중 리비전 지우기(하위 행 모두) — 확정본은 지우지 않는다 */
    @Transactional
    public void delete(long fmeaId, Integer rowVer) {
        editableHead(fmeaId);
        if (rowVer == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        deleteRows(repo.findActions(fmeaId).stream().map(ActionRow::getActionId).toList(), repo::deleteActions);
        deleteRows(repo.findRisks(fmeaId).stream().map(RiskRow::getRiskId).toList(), repo::deleteRisks);
        deleteRows(repo.findChains(fmeaId).stream().map(ChainRow::getChainId).toList(), repo::deleteChains);
        deleteRows(repo.findFails(fmeaId).stream().map(FailRow::getFailId).toList(), repo::deleteFails);
        deleteRows(repo.findFuncs(fmeaId).stream().map(FuncRow::getFuncId).toList(), repo::deleteFuncs);
        deleteRows(repo.findNodes(fmeaId).stream().map(NodeRow::getNodeId).toList(), repo::deleteNodes);
        if (repo.deleteFmea(fmeaId, rowVer) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 확정(APPROVE, 결재 정책 N) — 조건 미달이면 행마다 사유. 같은 번호의 이전 확정본은 폐기 */
    @Transactional
    public void release(long fmeaId, Integer rowVer) {
        LoginUser me = me();
        FmeaRow f = editableHead(fmeaId);
        if (rowVer == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        approval.requireDirect();
        releaseCheck(f);
        repo.obsoleteReleased(f.getFmeaNo(), me.loginId());
        if (repo.releaseFmea(fmeaId, rowVer, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 결재 상신(정책 APQP_RELEASE_APPROVAL = Y, A3-7) — 확정 조건을 먼저 보고, 승인 = 확정(FmeaApprovalDocType) */
    @Transactional
    public long submit(long fmeaId, SubmitForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("APQP_FMEA_SUBMIT");
        FmeaRow f = editableHead(fmeaId);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.rowVer() != f.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        releaseCheck(f);
        String title = blank(in.title()) ? f.getFmeaType() + " " + f.getFmeaNo() + " rev " + f.getRev() + " " + f.getTitle() : in.title();
        long aprId = approval.submit(FmeaApprovalDocType.docTypeOf(f.getFmeaType()), fmeaId, title, in.lines());
        // releaseCheck 의 다시 계산이 머리 row_ver 를 올리지 않으므로 받은 값 그대로
        if (repo.submitFmea(fmeaId, aprId, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return aprId;
    }

    /** 확정 조건(바로 확정·상신 공통) — 미달이면 APQP_RELEASE_CHECK + 행 키 */
    private void releaseCheck(FmeaRow f) {
        long fmeaId = f.getFmeaId();
        calc.recalc(fmeaId);
        FieldErrors e = FieldErrors.create();
        if (f.getApSetId() == null) {
            e.add("apSetId", "REQUIRED");
        } else if (!"Y".equals(repo.findApSet(f.getApSetId()).getUseYn())) {
            e.add("apSetId", "APQP_SET_NOT_USABLE");
        }
        List<NodeRow> nodes = repo.findNodes(fmeaId);
        if (nodes.stream().noneMatch(n -> isMid(n.getNodeKind()))) {
            e.add("nodes", "APQP_NO_STEPS");
        }
        Set<Long> chained = repo.findChains(fmeaId).stream().map(ChainRow::getFmId).collect(Collectors.toSet());
        List<RiskRow> risks = repo.findRisks(fmeaId);
        Set<Long> withRisk = risks.stream().map(RiskRow::getFmId).collect(Collectors.toSet());
        for (FailRow x : repo.findFails(fmeaId)) {
            if ("FM".equals(x.getFailKind())) {
                if (!chained.contains(x.getFailId())) {
                    e.row("FAIL:" + x.getFailId()).add("feId", "APQP_NO_CHAIN");
                }
                if (!withRisk.contains(x.getFailId())) {
                    e.row("FAIL:" + x.getFailId()).add("fcId", "APQP_NO_RISK");
                }
            }
        }
        for (RiskRow r : risks) {
            if (r.getOcc() == null) {
                e.row("RISK:" + r.getRiskId()).add("occ", "REQUIRED");
            }
            if (r.getDet() == null) {
                e.row("RISK:" + r.getRiskId()).add("det", "REQUIRED");
            }
            if (r.getOcc() != null && r.getDet() != null && r.getAp() == null) {
                e.row("RISK:" + r.getRiskId()).add("ap", "APQP_RISK_NO_AP");
            }
        }
        if (!e.isEmpty()) {
            throw new BusinessException(HbErrorCode.APQP_RELEASE_CHECK).withFieldErrors(e.list());
        }
    }

    /** 새 리비전 = 확정본 복사(작성 중). 같은 번호에 작성 중이 이미 있으면 거부 */
    @Transactional
    public long revise(long fmeaId) {
        LoginUser me = me();
        inbox.claimRequestKey("APQP_FMEA_REVISE");
        String status = repo.lockFmea(fmeaId);
        if (status == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        FmeaRow src = found(fmeaId);
        if (!"RELEASED".equals(status) || repo.draftExists(src.getFmeaNo())) {
            throw new BusinessException(HbErrorCode.APQP_FMEA_STATE);
        }
        String by = me.loginId();
        long id = repo.insertFmea(src.getFmeaNo(), src.getRev() + 1, new FmeaValue(src.getItemId(), src.getRoutingId(), src.getProjId(),
                src.getTitle(), src.getScope(), src.getTeam(), src.getDrawingRev(), src.getApSetId(), src.getFmeaType(), src.getBomId(),
                src.getDocRevId()), fmeaId, by);
        Map<Long, Long> node = new HashMap<>();
        for (NodeRow n : treeOrder(repo.findNodes(fmeaId))) {
            Long parent = n.getParentId() == null ? null : node.get(n.getParentId());
            node.put(n.getNodeId(), n.getRefItemId() != null
                    ? repo.insertNodeRef(id, parent, n.getNodeKind(), n.getSeq(), n.getNodeNm(), n.getRefItemId(), by)
                    : repo.insertNode(id, parent, n.getNodeKind(), n.getSeq(), n.getNodeNm(), n.getStepId(), n.getM4(), by));
        }
        Map<Long, Long> func = new HashMap<>();
        for (FuncRow x : repo.findFuncs(fmeaId)) {
            func.put(x.getFuncId(), repo.insertFunc(id, node.get(x.getNodeId()), x.getSeq(), x.getFuncText(), x.getCharId(), by));
        }
        Map<Long, Long> fail = new HashMap<>();
        for (FailRow x : repo.findFails(fmeaId)) {
            fail.put(x.getFailId(), repo.insertFail(id, func.get(x.getFuncId()), x.getFailKind(), x.getSeq(), x.getFailText(),
                    x.getSeverity(), x.getDefectId(), x.getSrcFailId(), by));
        }
        for (ChainRow c : repo.findChains(fmeaId)) {
            repo.insertChain(id, fail.get(c.getFmId()), fail.get(c.getFeId()), by);
        }
        Map<Long, Long> risk = new HashMap<>();
        for (RiskRow r : repo.findRisks(fmeaId)) {
            long old = r.getRiskId();
            r.setFmId(fail.get(r.getFmId()));
            r.setFcId(fail.get(r.getFcId()));
            risk.put(old, repo.insertRisk(id, r, by));
        }
        for (ActionRow a : repo.findActions(fmeaId)) {
            a.setRiskId(risk.get(a.getRiskId()));
            repo.insertAction(id, a, by);
        }
        calc.recalc(id);
        return id;
    }

    private FmeaValue validate(String type, FmeaForm in, FmeaRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        long itemId = 0;
        if (cur != null) {
            itemId = cur.getItemId();
            if (!blank(in.itemCd()) && !cur.getItemCd().equals(in.itemCd().strip())) {
                e.add("itemCd", "FIXED");
            }
        } else if (blank(in.itemCd())) {
            e.add("itemCd", "REQUIRED");
        } else {
            ItemOption item = repo.findItemByCd(in.itemCd().strip());
            if (item == null) {
                e.add("itemCd", "NO_SUCH_CODE");
            } else {
                itemId = item.getItemId();
            }
        }
        boolean dfmea = DFMEA.equals(type);
        if (dfmea && in.routingId() != null) {
            e.add("routingId", "APQP_DFMEA_NO_ROUTING");
        } else if (in.routingId() != null && itemId != 0
                && repo.findRoutings(itemId).stream().noneMatch(r -> r.getRoutingId() == in.routingId())) {
            e.add("routingId", "NOT_FOUND");
        }
        // DFMEA 대상(D5): 그 품목의 확정 BOM 리비전, 도면 리비전(QMS) — DRAWING 인지는 트리거가 최종 확인
        Long bomId = dfmea ? in.bomId() : null;
        Long docRevId = dfmea ? in.docRevId() : null;
        if (!dfmea && (in.bomId() != null || in.docRevId() != null)) {
            e.add("bomId", "APQP_PFMEA_NO_BOM");
        }
        if (bomId != null && itemId != 0 && (cur == null || !bomId.equals(cur.getBomId()))
                && repo.findReleasedBoms(itemId).stream().noneMatch(b -> b.getBomId() == bomId)) {
            e.add("bomId", "NOT_FOUND");
        }
        if (docRevId != null && (cur == null || !docRevId.equals(cur.getDocRevId()))
                && io.smarthappy.module.qms.link.DocLookup.of(docs).rev(docRevId).isEmpty()) {
            e.add("docRevId", "NOT_FOUND");
        }
        if (in.projId() != null && repo.findProjects().stream().noneMatch(p -> p.getProjId() == in.projId())
                && (cur == null || !in.projId().equals(cur.getProjId()))) {
            e.add("projId", "NOT_FOUND");
        }
        if (in.apSetId() != null && (cur == null || !in.apSetId().equals(cur.getApSetId()))) {
            ApSetRow s = repo.findApSet(in.apSetId());
            if (s == null) {
                e.add("apSetId", "NOT_FOUND");
            } else if (!"Y".equals(s.getUseYn())) {
                e.add("apSetId", "APQP_SET_NOT_USABLE");
            }
        }
        String title = text(in.title(), "title", 200, true, e);
        String scope = text(in.scope(), "scope", 1000, false, e);
        String team = text(in.team(), "team", 500, false, e);
        String drawingRev = dfmea ? null : text(in.drawingRev(), "drawingRev", 30, false, e);
        e.throwIfAny();
        return new FmeaValue(itemId, dfmea ? null : in.routingId(), in.projId(), title, scope, team, drawingRev, in.apSetId(),
                cur == null ? type : cur.getFmeaType(), bomId, docRevId);
    }

    // ------------------------------------------------------------------ 구조

    @Transactional
    public long addNode(long fmeaId, NodeForm in) {
        LoginUser me = me();
        FmeaRow f = editableHead(fmeaId);
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        List<NodeRow> nodes = repo.findNodes(fmeaId);
        if (nodes.size() >= MAX_NODES) {
            throw fieldError("nodes", "APQP_TOO_MANY_ROWS", MAX_NODES);
        }
        String kind = upper(in.nodeKind());
        FieldErrors e = FieldErrors.create();
        NodeRow parent = in.parentId() == null ? null : nodes.stream().filter(n -> n.getNodeId() == in.parentId()).findFirst().orElse(null);
        String type = f.getFmeaType();
        if (!midKind(type).equals(kind) && !leafKind(type).equals(kind)) {
            e.add("nodeKind", "CODE");
        } else if (parent == null || (midKind(type).equals(kind) && !rootKind(type).equals(parent.getNodeKind()))
                || (leafKind(type).equals(kind) && !midKind(type).equals(parent.getNodeKind()))) {
            e.add("parentId", "APQP_LEVEL");
        }
        NodeValue v = nodeValue(f, kind, in, e);
        e.throwIfAny();
        int seq = nodes.stream().filter(n -> Objects.equals(n.getParentId(), in.parentId())).mapToInt(NodeRow::getSeq).max().orElse(0) + 1;
        long id = repo.insertNode(fmeaId, in.parentId(), kind, seq, v.nm(), v.stepId(), v.m4(), me.loginId());
        repo.touchFmea(fmeaId, me.loginId());
        return id;
    }

    @Transactional
    public void updateNode(long nodeId, NodeForm in) {
        LoginUser me = me();
        long fmeaId = fmeaOf("NODE", nodeId);
        FmeaRow f = editableHead(fmeaId);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        NodeRow cur = repo.findNodes(fmeaId).stream().filter(n -> n.getNodeId() == nodeId).findFirst().orElseThrow();
        FieldErrors e = FieldErrors.create();
        NodeValue v = nodeValue(f, cur.getNodeKind(), in, e);
        e.throwIfAny();
        if (repo.updateNode(nodeId, v.nm(), v.stepId(), v.m4(), in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        repo.touchFmea(fmeaId, me.loginId());
    }

    /** 노드 지우기 = 아래 노드·기능·고장·사슬·위험·조치까지(작성 중이라 이력은 감사 로그) */
    @Transactional
    public void deleteNode(long nodeId, Integer rowVer) {
        LoginUser me = me();
        long fmeaId = fmeaOf("NODE", nodeId);
        editableHead(fmeaId);
        List<NodeRow> nodes = repo.findNodes(fmeaId);
        NodeRow cur = nodes.stream().filter(n -> n.getNodeId() == nodeId).findFirst().orElseThrow();
        if (rowVer == null || cur.getRowVer() != rowVer) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (isRoot(cur.getNodeKind())) {
            throw fieldError("nodeId", "APQP_LEVEL");
        }
        Set<Long> sub = new HashSet<>(List.of(nodeId));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (NodeRow n : nodes) {
                if (n.getParentId() != null && sub.contains(n.getParentId()) && sub.add(n.getNodeId())) {
                    grew = true;
                }
            }
        }
        Set<Long> funcs = repo.findFuncs(fmeaId).stream().filter(x -> sub.contains(x.getNodeId())).map(FuncRow::getFuncId)
                .collect(Collectors.toSet());
        deleteFuncsCascade(fmeaId, funcs);
        deleteRows(new ArrayList<>(sub), repo::deleteNodes);
        repo.touchFmea(fmeaId, me.loginId());
        calc.recalc(fmeaId);
    }

    /** 형제 안에서 위·아래로 — 순번을 1부터 다시 매긴다 */
    @Transactional
    public void moveNode(long nodeId, MoveForm in) {
        LoginUser me = me();
        long fmeaId = fmeaOf("NODE", nodeId);
        editableHead(fmeaId);
        String op = in == null ? null : upper(in.op());
        if (!"UP".equals(op) && !"DOWN".equals(op)) {
            throw fieldError("op", "CODE");
        }
        List<NodeRow> nodes = repo.findNodes(fmeaId);
        NodeRow cur = nodes.stream().filter(n -> n.getNodeId() == nodeId).findFirst().orElseThrow();
        if (in.rowVer() == null || cur.getRowVer() != in.rowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        List<NodeRow> sib = nodes.stream().filter(n -> Objects.equals(n.getParentId(), cur.getParentId()))
                .sorted(Comparator.comparingInt(NodeRow::getSeq).thenComparingLong(NodeRow::getNodeId)).collect(Collectors.toList());
        int i = sib.indexOf(cur);
        int j = "UP".equals(op) ? i - 1 : i + 1;
        if (j < 0 || j >= sib.size()) {
            return;
        }
        sib.set(i, sib.get(j));
        sib.set(j, cur);
        for (int k = 0; k < sib.size(); k++) {
            if (sib.get(k).getSeq() != k + 1) {
                repo.updateNodeSeq(sib.get(k).getNodeId(), k + 1, me.loginId());
            }
        }
        repo.touchFmea(fmeaId, me.loginId());
    }

    private record NodeValue(String nm, Long stepId, String m4) {
    }

    private NodeValue nodeValue(FmeaRow f, String kind, NodeForm in, FieldErrors e) {
        Long stepId = null;
        String m4 = null;
        String nm = text(in.nodeNm(), "nodeNm", 200, false, e);
        if ("STEP".equals(kind) && in.stepId() != null) {
            StepOption s = f.getRoutingId() == null ? null
                    : repo.findSteps(f.getRoutingId()).stream().filter(x -> x.getStepId() == in.stepId()).findFirst().orElse(null);
            if (s == null) {
                e.add("stepId", "APQP_STEP_ROUTING");
            } else {
                stepId = s.getStepId();
                if (nm == null) {
                    nm = s.getOpSeq() + " " + s.getProcNm();
                }
            }
        }
        if ("ELEMENT".equals(kind)) {
            m4 = upper(in.m4());
            if (m4 == null) {
                e.add("m4", "REQUIRED");
            } else if (!ApqpInputs.M4.contains(m4)) {
                e.add("m4", "CODE");
            }
        }
        if (nm == null && !isRoot(kind)) {
            e.add("nodeNm", "REQUIRED");
        }
        return new NodeValue(nm, stepId, m4);
    }

    // ------------------------------------------------------------------ 기능

    @Transactional
    public long addFunc(long fmeaId, FuncForm in) {
        LoginUser me = me();
        FmeaRow f = editableHead(fmeaId);
        if (in == null || in.nodeId() == null) {
            throw fieldError("nodeId", "REQUIRED");
        }
        NodeRow node = repo.findNodes(fmeaId).stream().filter(n -> n.getNodeId() == in.nodeId()).findFirst().orElse(null);
        if (node == null) {
            throw fieldError("nodeId", "NOT_FOUND");
        }
        limitRows(fmeaId);
        FieldErrors e = FieldErrors.create();
        String t = text(in.funcText(), "funcText", 500, true, e);
        Long charId = charOf(f, node, in.charId(), e);
        e.throwIfAny();
        int seq = repo.findFuncs(fmeaId).stream().filter(x -> x.getNodeId() == in.nodeId()).mapToInt(FuncRow::getSeq).max().orElse(0) + 1;
        long id = repo.insertFunc(fmeaId, in.nodeId(), seq, t, charId, me.loginId());
        repo.touchFmea(fmeaId, me.loginId());
        return id;
    }

    @Transactional
    public void updateFunc(long funcId, FuncForm in) {
        LoginUser me = me();
        long fmeaId = fmeaOf("FUNC", funcId);
        FmeaRow f = editableHead(fmeaId);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FuncRow cur = repo.findFuncs(fmeaId).stream().filter(x -> x.getFuncId() == funcId).findFirst().orElseThrow();
        NodeRow node = repo.findNodes(fmeaId).stream().filter(n -> n.getNodeId() == cur.getNodeId()).findFirst().orElseThrow();
        FieldErrors e = FieldErrors.create();
        String t = text(in.funcText(), "funcText", 500, true, e);
        Long charId = charOf(f, node, in.charId(), e);
        e.throwIfAny();
        if (repo.updateFunc(funcId, t, charId, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        repo.touchFmea(fmeaId, me.loginId());
    }

    @Transactional
    public void deleteFunc(long funcId, Integer rowVer) {
        LoginUser me = me();
        long fmeaId = fmeaOf("FUNC", funcId);
        editableHead(fmeaId);
        FuncRow cur = repo.findFuncs(fmeaId).stream().filter(x -> x.getFuncId() == funcId).findFirst().orElseThrow();
        if (rowVer == null || cur.getRowVer() != rowVer) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        deleteFuncsCascade(fmeaId, Set.of(funcId));
        repo.touchFmea(fmeaId, me.loginId());
        calc.recalc(fmeaId);
    }

    /** 특성 = 머리 품목의 제품 특성이거나, 이 요소가 속한 라우팅 단계 공정의 공정 특성(고르기 목록 안) */
    private Long charOf(FmeaRow f, NodeRow node, Long charId, FieldErrors e) {
        if (charId == null) {
            return null;
        }
        CharOption c = charOptions(f).stream().filter(x -> x.getCharId() == charId).findFirst().orElse(null);
        if (c == null) {
            e.add("charId", "APQP_CHAR_TARGET");
            return null;
        }
        // DFMEA — 제품 특성은 머리 품목 것 또는 이 노드가 가리키는 자품목 것(후보의 procCd = 품번)
        if (DFMEA.equals(f.getFmeaType()) && !c.getProcCd().equals(f.getItemCd()) && !c.getProcCd().equals(node.getRefItemCd())) {
            e.add("charId", "APQP_CHAR_TARGET");
            return null;
        }
        if ("PROCESS".equals(c.getCharType())) {
            Long step = node.getStepId();
            if (step == null && node.getParentId() != null) {
                step = repo.findNodes(f.getFmeaId()).stream().filter(n -> n.getNodeId() == node.getParentId()).map(NodeRow::getStepId)
                        .filter(Objects::nonNull).findFirst().orElse(null);
            }
            if (step != null && !procOfStep(f, step).equals(procOfChar(c))) {
                e.add("charId", "APQP_CHAR_TARGET");
                return null;
            }
        }
        return charId;
    }

    private String procOfStep(FmeaRow f, long stepId) {
        return repo.findSteps(f.getRoutingId()).stream().filter(s -> s.getStepId() == stepId).map(StepOption::getProcCd).findFirst()
                .orElse("");
    }

    /** 공정 특성의 공정 코드 — 고르기 목록은 라우팅 단계 공정의 특성만 주므로, 같은 공정인지는 트리거가 최종 확인 */
    private static String procOfChar(CharOption c) {
        return c.getProcCd() == null ? "" : c.getProcCd();
    }

    // ------------------------------------------------------------------ 고장 · 사슬

    @Transactional
    public long addFail(long fmeaId, FailForm in) {
        LoginUser me = me();
        FmeaRow f = editableHead(fmeaId);
        if (in == null || in.funcId() == null) {
            throw fieldError("funcId", "REQUIRED");
        }
        FuncRow func = repo.findFuncs(fmeaId).stream().filter(x -> x.getFuncId() == in.funcId()).findFirst().orElse(null);
        if (func == null) {
            throw fieldError("funcId", "NOT_FOUND");
        }
        limitRows(fmeaId);
        String kind = kindOf(repo.findNodes(fmeaId).stream().filter(n -> n.getNodeId() == func.getNodeId()).findFirst().orElseThrow());
        if (!blank(in.failKind()) && !kind.equals(upper(in.failKind()))) {
            throw fieldError("failKind", "APQP_LEVEL");
        }
        FieldErrors e = FieldErrors.create();
        FailValue v = failValue(f, kind, in, null, e);
        e.throwIfAny();
        int seq = repo.findFails(fmeaId).stream().filter(x -> x.getFuncId() == in.funcId()).mapToInt(FailRow::getSeq).max().orElse(0) + 1;
        long id = repo.insertFail(fmeaId, in.funcId(), kind, seq, v.text(), v.severity(), v.defectId(), v.srcFailId(), me.loginId());
        repo.touchFmea(fmeaId, me.loginId());
        return id;
    }

    @Transactional
    public void updateFail(long failId, FailForm in) {
        LoginUser me = me();
        long fmeaId = fmeaOf("FAIL", failId);
        FmeaRow f = editableHead(fmeaId);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FailRow cur = repo.findFails(fmeaId).stream().filter(x -> x.getFailId() == failId).findFirst().orElseThrow();
        if (!blank(in.failKind()) && !cur.getFailKind().equals(upper(in.failKind()))) {
            throw fieldError("failKind", "FIXED");
        }
        FieldErrors e = FieldErrors.create();
        FailValue v = failValue(f, cur.getFailKind(), in, cur, e);
        e.throwIfAny();
        if (repo.updateFail(failId, v.text(), v.severity(), v.defectId(), v.srcFailId(), in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        repo.touchFmea(fmeaId, me.loginId());
        if (!Objects.equals(cur.getSeverity(), v.severity())) {
            calc.recalc(fmeaId);
        }
    }

    @Transactional
    public void deleteFail(long failId, Integer rowVer) {
        LoginUser me = me();
        long fmeaId = fmeaOf("FAIL", failId);
        editableHead(fmeaId);
        FailRow cur = repo.findFails(fmeaId).stream().filter(x -> x.getFailId() == failId).findFirst().orElseThrow();
        if (rowVer == null || cur.getRowVer() != rowVer) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        deleteFailsCascade(fmeaId, Set.of(failId));
        repo.touchFmea(fmeaId, me.loginId());
        calc.recalc(fmeaId);
    }

    @Transactional
    public long addChain(long fmeaId, ChainForm in) {
        LoginUser me = me();
        editableHead(fmeaId);
        if (in == null || in.fmId() == null || in.feId() == null) {
            throw fieldError(in == null || in.fmId() == null ? "fmId" : "feId", "REQUIRED");
        }
        Map<Long, FailRow> fails = repo.findFails(fmeaId).stream().collect(Collectors.toMap(FailRow::getFailId, x -> x));
        FailRow fm = fails.get(in.fmId());
        FailRow fe = fails.get(in.feId());
        if (fm == null || !"FM".equals(fm.getFailKind())) {
            throw fieldError("fmId", "APQP_LEVEL");
        }
        if (fe == null || !"FE".equals(fe.getFailKind())) {
            throw fieldError("feId", "APQP_LEVEL");
        }
        if (repo.findChains(fmeaId).stream().anyMatch(c -> c.getFmId() == in.fmId() && c.getFeId() == in.feId())) {
            throw fieldError("feId", "DUPLICATE");
        }
        long id = repo.insertChain(fmeaId, in.fmId(), in.feId(), me.loginId());
        repo.touchFmea(fmeaId, me.loginId());
        calc.recalc(fmeaId);
        return id;
    }

    @Transactional
    public void deleteChain(long chainId) {
        LoginUser me = me();
        long fmeaId = fmeaOf("CHAIN", chainId);
        editableHead(fmeaId);
        repo.deleteChains(List.of(chainId));
        repo.touchFmea(fmeaId, me.loginId());
        calc.recalc(fmeaId);
    }

    private record FailValue(String text, Integer severity, Long defectId, Long srcFailId) {
    }

    /** cur = 고치는 행(새 행이면 null) — 출처를 그대로 두면(같은 id) 원본 DFMEA 가 폐기됐어도 그대로 */
    private FailValue failValue(FmeaRow f, String kind, FailForm in, FailRow cur, FieldErrors e) {
        String t = text(in.failText(), "failText", 500, true, e);
        Integer sev = "FE".equals(kind) ? rating(in.severity(), "severity", true, e) : null;
        Long defectId = null;
        if (in.defectId() != null) {
            if (!"FM".equals(kind)) {
                e.add("defectId", "APQP_LEVEL");
            } else if (repo.findDefects().stream().noneMatch(d -> d.getDefectId() == in.defectId())) {
                e.add("defectId", "NOT_FOUND");
            } else {
                defectId = in.defectId();
            }
        }
        // 출처(A5-2b D7): PFMEA 고장 영향만, 같은 품목 확정 DFMEA 의 고장 형태 — 글자는 화면이 복사, 여기선 출처만
        Long src = in.srcFailId();
        if (src != null && !(cur != null && src.equals(cur.getSrcFailId()))) {
            if (!PFMEA.equals(f.getFmeaType()) || !"FE".equals(kind)) {
                e.add("srcFailId", "APQP_LEVEL");
                src = null;
            } else {
                long id = src;
                if (repo.findDfmeaSources(f.getItemId()).stream().noneMatch(x -> x.getFailId() == id)) {
                    e.add("srcFailId", "NOT_FOUND");
                    src = null;
                }
            }
        }
        return new FailValue(t, sev, defectId, src);
    }

    /** 구조 수준이 고장 종류를 정한다: ITEM = FE(영향), STEP = FM(형태), ELEMENT = FC(원인) */
    private static String kindOf(NodeRow n) {
        return switch (n.getNodeKind()) {
            case "ITEM", "SYSTEM" -> "FE";
            case "STEP", "SUBSYSTEM" -> "FM";
            default -> "FC";
        };
    }

    // ------------------------------------------------------------------ 위험

    @Transactional
    public long addRisk(long fmeaId, RiskForm in) {
        LoginUser me = me();
        editableHead(fmeaId);
        if (in == null || in.fmId() == null || in.fcId() == null) {
            throw fieldError(in == null || in.fmId() == null ? "fmId" : "fcId", "REQUIRED");
        }
        limitRows(fmeaId);
        if (!pairOk(fmeaId, in.fmId(), in.fcId())) {
            throw fieldError("fcId", "APQP_PAIR");
        }
        if (repo.findRisks(fmeaId).stream().anyMatch(r -> r.getFmId() == in.fmId() && r.getFcId() == in.fcId())) {
            throw fieldError("fcId", "DUPLICATE");
        }
        RiskRow r = riskValue(in);
        r.setFmId(in.fmId());
        r.setFcId(in.fcId());
        long id = repo.insertRisk(fmeaId, r, me.loginId());
        repo.touchFmea(fmeaId, me.loginId());
        calc.recalc(fmeaId);
        return id;
    }

    @Transactional
    public void updateRisk(long riskId, RiskForm in) {
        LoginUser me = me();
        long fmeaId = fmeaOf("RISK", riskId);
        editableHead(fmeaId);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        RiskRow cur = repo.findRisks(fmeaId).stream().filter(r -> r.getRiskId() == riskId).findFirst().orElseThrow();
        if ((in.fmId() != null && in.fmId() != cur.getFmId()) || (in.fcId() != null && in.fcId() != cur.getFcId())) {
            throw fieldError("fcId", "FIXED");
        }
        if (repo.updateRisk(riskId, riskValue(in), in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        repo.touchFmea(fmeaId, me.loginId());
        calc.recalc(fmeaId);
    }

    @Transactional
    public void deleteRisk(long riskId, Integer rowVer) {
        LoginUser me = me();
        long fmeaId = fmeaOf("RISK", riskId);
        editableHead(fmeaId);
        RiskRow cur = repo.findRisks(fmeaId).stream().filter(r -> r.getRiskId() == riskId).findFirst().orElseThrow();
        if (rowVer == null || cur.getRowVer() != rowVer) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        deleteRisksCascade(fmeaId, Set.of(riskId));
        repo.touchFmea(fmeaId, me.loginId());
    }

    private RiskRow riskValue(RiskForm in) {
        FieldErrors e = FieldErrors.create();
        RiskRow r = new RiskRow();
        r.setPrevCtrl(text(in.prevCtrl(), "prevCtrl", 500, false, e));
        r.setDetCtrl(text(in.detCtrl(), "detCtrl", 500, false, e));
        r.setOcc(rating(in.occ(), "occ", false, e));
        r.setDet(rating(in.det(), "det", false, e));
        String sc = upper(in.specialCls());
        if (sc != null && !repo.codeUsable("SPECIAL_CHAR", sc)) {
            e.add("specialCls", "CODE");
        }
        r.setSpecialCls(sc);
        e.throwIfAny();
        return r;
    }

    /** FC 는 FM 단계 바로 아래 작업요소의 기능에 달린 원인이어야 한다 */
    private boolean pairOk(long fmeaId, long fmId, long fcId) {
        Map<Long, FailRow> fails = repo.findFails(fmeaId).stream().collect(Collectors.toMap(FailRow::getFailId, x -> x));
        FailRow fm = fails.get(fmId);
        FailRow fc = fails.get(fcId);
        if (fm == null || fc == null || !"FM".equals(fm.getFailKind()) || !"FC".equals(fc.getFailKind())) {
            return false;
        }
        Map<Long, Long> nodeOfFunc = repo.findFuncs(fmeaId).stream().collect(Collectors.toMap(FuncRow::getFuncId, FuncRow::getNodeId));
        Map<Long, NodeRow> nodes = repo.findNodes(fmeaId).stream().collect(Collectors.toMap(NodeRow::getNodeId, n -> n));
        NodeRow fcNode = nodes.get(nodeOfFunc.get(fc.getFuncId()));
        return fcNode != null && Objects.equals(fcNode.getParentId(), nodeOfFunc.get(fm.getFuncId()));
    }

    // ------------------------------------------------------------------ 조치

    @Transactional
    public long addAction(long fmeaId, ActionForm in) {
        LoginUser me = me();
        editableHead(fmeaId);
        if (in == null || in.riskId() == null) {
            throw fieldError("riskId", "REQUIRED");
        }
        if (repo.findRisks(fmeaId).stream().noneMatch(r -> r.getRiskId() == in.riskId())) {
            throw fieldError("riskId", "NOT_FOUND");
        }
        limitRows(fmeaId);
        ActionRow a = actionValue(in, null);
        a.setRiskId(in.riskId());
        long id = repo.insertAction(fmeaId, a, me.loginId());
        repo.touchFmea(fmeaId, me.loginId());
        calc.recalc(fmeaId);
        return id;
    }

    /** 작성 중 = 모든 칸, 확정본(같은 번호 작성 중 없음) = 진행 칸만(상태·완료일·증빙·조치 뒤 O·D) — P7 */
    @Transactional
    public void updateAction(long actionId, ActionForm in) {
        LoginUser me = me();
        long fmeaId = fmeaOf("ACTION", actionId);
        String status = lockStatus(fmeaId);
        FmeaRow f = found(fmeaId);
        boolean draft = "DRAFT".equals(status);
        if (!draft && !("RELEASED".equals(status) && !f.isDraftExists())) {
            throw new BusinessException(HbErrorCode.APQP_FMEA_STATE);
        }
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        ActionRow cur = repo.findActions(fmeaId).stream().filter(a -> a.getActionId() == actionId).findFirst().orElseThrow();
        ActionRow a = actionValue(in, draft ? null : cur);
        a.setRiskId(cur.getRiskId());
        if (repo.updateAction(actionId, a, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        repo.touchFmea(fmeaId, me.loginId());
        calc.recalc(fmeaId);
    }

    @Transactional
    public void deleteAction(long actionId, Integer rowVer) {
        LoginUser me = me();
        long fmeaId = fmeaOf("ACTION", actionId);
        editableHead(fmeaId);
        ActionRow cur = repo.findActions(fmeaId).stream().filter(a -> a.getActionId() == actionId).findFirst().orElseThrow();
        if (rowVer == null || cur.getRowVer() != rowVer) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        repo.deleteActions(List.of(actionId));
        repo.touchFmea(fmeaId, me.loginId());
    }

    /** fixed != null 이면 확정본 — 내용·종류·담당·기한은 그 값 그대로여야 한다 */
    private ActionRow actionValue(ActionForm in, ActionRow fixed) {
        FieldErrors e = FieldErrors.create();
        ActionRow a = new ActionRow();
        String kind = upper(in.actionKind());
        if (kind == null) {
            e.add("actionKind", "REQUIRED");
        } else if (!"PREVENT".equals(kind) && !"DETECT".equals(kind)) {
            e.add("actionKind", "CODE");
        }
        a.setActionKind(kind);
        a.setActionText(text(in.actionText(), "actionText", 500, true, e));
        Long owner = null;
        if (!blank(in.ownerLoginId())) {
            UserOption u = users.activeByLogin(in.ownerLoginId());   // 사용 중 회사 사용자만(공통 사용자 고르기)
            owner = u == null ? null : u.getUserId();
            if (owner == null) {
                e.add("ownerLoginId", "NOT_FOUND");
            }
        }
        a.setOwnerUserId(owner);
        a.setDueDate(date(in.dueDate(), "dueDate", false, e));
        String st = blank(in.status()) ? "OPEN" : upper(in.status());
        if (!Set.of("OPEN", "DONE", "CANCELLED").contains(st)) {
            e.add("status", "CODE");
        }
        a.setStatus(st);
        LocalDate done = date(in.doneOn(), "doneOn", false, e);
        if ("DONE".equals(st) && done == null && e.isEmpty()) {
            e.add("doneOn", "REQUIRED");
        }
        a.setDoneOn("DONE".equals(st) ? done : null);
        a.setEvidence(text(in.evidence(), "evidence", 500, false, e));
        a.setNewOcc(rating(in.newOcc(), "newOcc", false, e));
        a.setNewDet(rating(in.newDet(), "newDet", false, e));
        if (fixed != null && e.isEmpty()
                && (!Objects.equals(a.getActionKind(), fixed.getActionKind()) || !Objects.equals(a.getActionText(), fixed.getActionText())
                        || !Objects.equals(a.getOwnerUserId(), fixed.getOwnerUserId())
                        || !Objects.equals(a.getDueDate(), fixed.getDueDate()))) {
            e.add("actionText", "FIXED");
        }
        e.throwIfAny();
        return a;
    }

    // ------------------------------------------------------------------ 공통

    private FmeaRow found(long fmeaId) {
        FmeaRow f = repo.findFmea(fmeaId);
        if (f == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (f.getDocRevId() != null) {
            io.smarthappy.module.qms.link.DocLookup.of(docs).rev(f.getDocRevId())
                    .ifPresent(r -> f.setDocRevText(r.docNo() + " rev " + r.rev() + " · " + r.docNm()));
        }
        markDrawing(f, new HashMap<>());
        return f;
    }

    /**
     * 도면 재검토(A5-2b D6) — 이 리비전보다 나중의(DocLookup 이 최근 것부터 준다) 확정 리비전이 같은 문서에 있으면 표시만.
     * 목록·엑셀은 쪽마다 같은 리비전을 한 번만 묻는다(cache)
     */
    private void markDrawing(FmeaRow f, Map<Long, Boolean> cache) {
        if (f.getDocRevId() == null) {
            return;
        }
        f.setDrawingStale(cache.computeIfAbsent(f.getDocRevId(), id -> {
            io.smarthappy.module.qms.link.DocLookup lookup = io.smarthappy.module.qms.link.DocLookup.of(docs);
            return lookup.rev(id).map(cur -> {
                for (io.smarthappy.module.qms.link.DocLookup.DocRevView r : lookup.revsByDocNo(cur.docNo())) {
                    if (r.docRevId() == id) {
                        return false;
                    }
                    if (r.docId() == cur.docId() && "RELEASED".equals(r.status())) {
                        return true;
                    }
                }
                return false;
            }).orElse(false);
        }));
    }

    /**
     * 기능에 붙일 특성 후보 — PFMEA = 머리 품목 제품 특성 + 라우팅 단계 공정 특성, DFMEA = 머리 품목 + 구조에 끌어온 자품목들의 제품 특성
     * (D7: 특성은 마스터 id 그대로 — 복사 없음)
     */
    private List<CharOption> charOptions(FmeaRow f) {
        if (!DFMEA.equals(f.getFmeaType())) {
            // A5-2b D7: 이 품목의 확정 DFMEA 가 정한 특성(같은 id)을 표시 — 고르는 것은 PFMEA 담당
            List<CharOption> out = repo.findChars(f.getItemId(), f.getRoutingId());
            Set<Long> fromDfmea = repo.findDfmeaCharIds(f.getItemId());
            out.forEach(c -> c.setInDfmea(fromDfmea.contains(c.getCharId())));
            return out;
        }
        Set<Long> items = new HashSet<>(List.of(f.getItemId()));
        repo.findNodes(f.getFmeaId()).stream().map(NodeRow::getRefItemId).filter(Objects::nonNull).forEach(items::add);
        return repo.findProductChars(items);
    }

    /** BOM 1단계 줄 → 하위 시스템, 그 자품목의 지금 유효한 확정 BOM 줄 → 구성품(이름 = 품번 품명, 자품목 연결) */
    private void pullBom(long fmeaId, long root, long bomId, String by) {
        LocalDate today = clock.today();
        int seq = 0;
        int count = 0;
        for (ApqpDtos.BomChild c : repo.findBomChildren(bomId)) {
            long sub = repo.insertNodeRef(fmeaId, root, "SUBSYSTEM", ++seq, c.getItemCd() + " " + c.getItemNm(), c.getItemId(), by);
            count++;
            Long childBom = repo.findCurrentBom(c.getItemId(), today);
            if (childBom == null) {
                continue;
            }
            int s2 = 0;
            for (ApqpDtos.BomChild g : repo.findBomChildren(childBom)) {
                if (++count > MAX_NODES - 1) {
                    throw fieldError("bomId", "APQP_TOO_MANY_ROWS", MAX_NODES);
                }
                repo.insertNodeRef(fmeaId, sub, "COMPONENT", ++s2, g.getItemCd() + " " + g.getItemNm(), g.getItemId(), by);
            }
        }
    }

    /** 도면 리비전이 DRAWING 이 아니면(트리거) 칸 오류로 */
    private static <T> T drawingChecked(java.util.function.Supplier<T> op) {
        try {
            return op.get();
        } catch (org.springframework.dao.DataAccessException ex) {
            if (io.smarthappy.platform.common.db.PgErrors.constraint(ex).filter("fk_apqp_fmea_doc_rev_drawing"::equals).isPresent()) {
                throw fieldError("docRevId", "APQP_NOT_DRAWING");
            }
            throw ex;
        }
    }

    private String lockStatus(long fmeaId) {
        String status = repo.lockFmea(fmeaId);
        if (status == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return status;
    }

    /** 머리를 잠그고 작성 중인지 — 아니면 409 APQP_FMEA_STATE */
    private FmeaRow editableHead(long fmeaId) {
        if (!"DRAFT".equals(lockStatus(fmeaId))) {
            throw new BusinessException(HbErrorCode.APQP_FMEA_STATE);
        }
        return found(fmeaId);
    }

    private long fmeaOf(String kind, long id) {
        Long f = repo.fmeaOf(kind, id);
        if (f == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return f;
    }

    private void limitRows(long fmeaId) {
        FmeaRow f = found(fmeaId);
        if (repo.findFuncs(fmeaId).size() + repo.findFails(fmeaId).size() + f.getRiskCnt() >= MAX_ROWS) {
            throw fieldError("rows", "APQP_TOO_MANY_ROWS", MAX_ROWS);
        }
    }

    private void deleteFuncsCascade(long fmeaId, Set<Long> funcs) {
        if (funcs.isEmpty()) {
            return;
        }
        Set<Long> fails = repo.findFails(fmeaId).stream().filter(x -> funcs.contains(x.getFuncId())).map(FailRow::getFailId)
                .collect(Collectors.toSet());
        deleteFailsCascade(fmeaId, fails);
        deleteRows(new ArrayList<>(funcs), repo::deleteFuncs);
    }

    private void deleteFailsCascade(long fmeaId, Set<Long> fails) {
        if (fails.isEmpty()) {
            return;
        }
        Set<Long> risks = repo.findRisks(fmeaId).stream().filter(r -> fails.contains(r.getFmId()) || fails.contains(r.getFcId()))
                .map(RiskRow::getRiskId).collect(Collectors.toSet());
        deleteRisksCascade(fmeaId, risks);
        deleteRows(repo.findChains(fmeaId).stream().filter(c -> fails.contains(c.getFmId()) || fails.contains(c.getFeId()))
                .map(ChainRow::getChainId).toList(), repo::deleteChains);
        deleteRows(new ArrayList<>(fails), repo::deleteFails);
    }

    private void deleteRisksCascade(long fmeaId, Set<Long> risks) {
        if (risks.isEmpty()) {
            return;
        }
        deleteRows(repo.findActions(fmeaId).stream().filter(a -> risks.contains(a.getRiskId())).map(ActionRow::getActionId).toList(),
                repo::deleteActions);
        deleteRows(new ArrayList<>(risks), repo::deleteRisks);
    }

    private static void deleteRows(List<Long> ids, java.util.function.Function<List<Long>, Integer> delete) {
        if (!ids.isEmpty()) {
            delete.apply(ids);
        }
    }

    /** 부모가 먼저 오게(복사 때 새 부모 id 를 알도록) */
    private static List<NodeRow> treeOrder(List<NodeRow> nodes) {
        List<NodeRow> out = new ArrayList<>();
        Set<Long> placed = new HashSet<>();
        while (out.size() < nodes.size()) {
            int before = out.size();
            for (NodeRow n : nodes) {
                if (!placed.contains(n.getNodeId()) && (n.getParentId() == null || placed.contains(n.getParentId()))) {
                    out.add(n);
                    placed.add(n.getNodeId());
                }
            }
            if (out.size() == before) {
                break;
            }
        }
        return out;
    }
}
