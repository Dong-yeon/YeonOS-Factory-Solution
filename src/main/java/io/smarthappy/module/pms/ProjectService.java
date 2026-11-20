package io.smarthappy.module.pms;

import static io.smarthappy.platform.common.text.VisibleText.isBlank;
import static io.smarthappy.module.pms.PmsInputs.date;
import static io.smarthappy.module.pms.PmsInputs.fieldError;
import static io.smarthappy.module.pms.PmsInputs.me;
import static io.smarthappy.module.pms.PmsInputs.visible;
import static io.smarthappy.module.pms.PmsInputs.upper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.pms.PmsDtos.EcoLinkForm;
import io.smarthappy.module.pms.PmsDtos.ItemRef;
import io.smarthappy.module.pms.PmsDtos.ProjectFilter;
import io.smarthappy.module.pms.PmsDtos.ProjectForm;
import io.smarthappy.module.pms.PmsDtos.ProjectPage;
import io.smarthappy.module.pms.PmsDtos.ProjectPickSort;
import io.smarthappy.module.pms.PmsDtos.ProjectRow;
import io.smarthappy.module.pms.PmsDtos.ProjectSort;
import io.smarthappy.module.pms.PmsDtos.ProjectValue;
import io.smarthappy.module.pms.PmsDtos.StatusForm;
import io.smarthappy.module.pms.PmsDtos.TaskRow;
import io.smarthappy.module.pms.PmsDtos.TaskValue;
import io.smarthappy.module.pms.PmsDtos.TemplateRow;
import io.smarthappy.module.pms.PmsDtos.TmplTaskRow;
import io.smarthappy.module.pms.WbsTree.Placed;
import io.smarthappy.module.plm.link.EcoLookup;
import io.smarthappy.module.plm.link.EcoLookup.EcoView;
import io.smarthappy.platform.approval.ApprovalService.LineSpec;
import io.smarthappy.platform.approval.ApprovalService.LineType;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import lombok.RequiredArgsConstructor;

/**
 * 프로젝트 머리·상태·대상 품목·템플릿 복사 (pms-project) — docs/32 PM1, 4.1.
 * 번호는 채번 규칙 PMS_PROJECT(없으면 기본 PJ-{yyyy}-{seq:4} 를 만든다 — 회사가 바꾼 규칙은 덮지 않는다).
 */
@Service
@RequiredArgsConstructor
public class ProjectService {

    /** PLM 변경지시 화면 — ECO 연결은 이 화면 SEARCH 도 있어야(D20) */
    static final String ECO_PROG = "plm-eco";

    static final String RULE = "PMS_PROJECT";
    private static final int LIST_LIMIT = 500;
    private static final int MAX_ITEMS = 50;
    private static final Set<String> STATUSES = Set.of("DRAFT", "ACTIVE", "ON_HOLD", "CLOSED", "CANCELLED");

    private final PmsMapper mapper;
    private final UserDirectory users;
    private final GateMapper gm;
    private final TaskService tasks;
    private final TemplateService templates;
    private final NumberingService numbering;
    private final PolicyService policy;
    private final InboxService inbox;
    private final BizClock clock;
    private final PmsDays days;
    private final PermissionService permission;
    /** PLM 은 제공자 계약(EcoLookup — 없거나 다른 회사면 빈 값)으로만 읽는다. PLM 이 없는 설치본은 NONE */
    private final ObjectProvider<EcoLookup> ecos;
    private final io.smarthappy.platform.approval.ApprovalService approvals;

    /** status: 비우면 전체, OPEN = 진행 중 셋(DRAFT·ACTIVE·ON_HOLD), 또는 상태 하나. mine = 내가 PM */
    @Transactional(readOnly = true)
    public List<ProjectRow> list(String keyword, String projType, String status, boolean mine) {
        ProjectFilter f = filter(keyword, projType, status, mine);
        return mapper.findProjects(f, days.lateRef(), LIST_LIMIT);
    }

    /** grid(UI 단계 3 ②, docs/32 4.11) — 목록과 같은 조건(같은 SQL 조각) + 서버 페이징·정렬(허용 목록 {@link ProjectSort}) */
    @Transactional(readOnly = true)
    public ProjectPage grid(String keyword, String projType, String status, boolean mine, Integer page, Integer size, List<String> sort,
            Boolean total) {
        ProjectFilter f = filter(keyword, projType, status, mine);
        PageQuery pq = PageQuery.parse(page, size, sort, total, ProjectSort.values(), policy.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("projNo,desc"));
        List<ProjectRow> rows = mapper.findProjectPage(f, days.lateRef(), pq);
        return new ProjectPage(rows, pq.isWithTotal() ? mapper.countProjects(f) : null);
    }

    /** grid 열(목록 표와 같은 칸, 계획 기간은 시작·종료 두 칸) */
    static final List<ColumnSpec> PROJECT_GRID_COLUMNS = List.of(
            ColumnSpec.text("projNo", "pms.col.projNo", 120).withSort().withPriority(1),
            ColumnSpec.text("projNm", "pms.col.projNm", 220).withSort().withPriority(1).withFlex(),
            ColumnSpec.code("projType", "pms.col.projType", "PMS_PROJ_TYPE", 90).withSort(),
            ColumnSpec.text("custNm", "pms.col.cust", 120).withSort(),
            ColumnSpec.text("pmUserNm", "pms.col.pm", 90).withSort(),
            ColumnSpec.of("sopDate", "pms.col.sopDate", ColumnType.DATE, 100).withSort(),
            ColumnSpec.of("planStart", "pms.col.planStart", ColumnType.DATE, 100).withSort().withPriority(3),
            ColumnSpec.of("planEnd", "pms.col.planEnd", ColumnType.DATE, 100).withSort().withPriority(3),
            ColumnSpec.of("progressPct", "pms.col.progress", ColumnType.INT, 70).withSort(),
            ColumnSpec.of("lateCnt", "pms.col.late", ColumnType.INT, 80).withSort(),
            ColumnSpec.text("status", "pms.col.status", 80).withSort().withPriority(1));

    public List<ColumnSpec> gridColumns() {
        return PROJECT_GRID_COLUMNS;
    }

    /**
     * 프로젝트 고르기 grid(산출물·게이트 화면 위 목록, docs/40 §10.2 D1) — 고르기 목록(list(keyword, null, status, false))과 같은 조건·같은
     * 기본 순서(번호 내림차순). 정렬은 보이는 칸만({@link ProjectPickSort}). 화면 권한은 컨트롤러가 화면별 경로로 따로 확인
     */
    @Transactional(readOnly = true)
    public ProjectPage pickGrid(String keyword, String status, Integer page, Integer size, List<String> sort, Boolean total) {
        ProjectFilter f = filter(keyword, null, status, false);
        PageQuery pq = PageQuery.parse(page, size, sort, total, ProjectPickSort.values(), policy.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("projNo,desc"));
        List<ProjectRow> rows = mapper.findProjectPage(f, days.lateRef(), pq);
        return new ProjectPage(rows, pq.isWithTotal() ? mapper.countProjects(f) : null);
    }

    /** 고르기 grid 열(지금 표와 같은 다섯 칸) */
    static final List<ColumnSpec> PICK_GRID_COLUMNS = List.of(
            ColumnSpec.text("projNo", "pms.col.projNo", 120).withSort().withPriority(1),
            ColumnSpec.text("projNm", "pms.col.projNm", 240).withSort().withPriority(1).withFlex(),
            ColumnSpec.text("custNm", "pms.col.cust", 140).withSort(),
            ColumnSpec.of("sopDate", "pms.col.sopDate", ColumnType.DATE, 100).withSort(),
            ColumnSpec.text("status", "pms.col.status", 90).withSort().withPriority(1));

    public List<ColumnSpec> pickGridColumns() {
        return PICK_GRID_COLUMNS;
    }

    private ProjectFilter filter(String keyword, String projType, String status, boolean mine) {
        String type = upper(projType);
        if (type != null && !CodeService.isCodeFormat(type)) {
            throw fieldError("projType", "CODE");
        }
        String st = upper(status);
        List<String> statuses;
        if (st == null) {
            statuses = null;
        } else if ("OPEN".equals(st)) {
            statuses = List.copyOf(TaskService.OPEN);
        } else if (STATUSES.contains(st)) {
            statuses = List.of(st);
        } else {
            throw fieldError("status", "CODE");
        }
        return new ProjectFilter(PmsInputs.like(keyword), type, statuses, mine ? me().userId() : null);
    }

    @Transactional(readOnly = true)
    public ProjectRow get(long projId) {
        ProjectRow p = mapper.findProject(projId, days.lateRef());
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        p.setItems(mapper.findProjectItems(projId));
        p.setCanManage(tasks.canManage(p, me()));
        p.setApprovalOn(policy.bool(Policies.PMS_PROJ_APPROVAL));
        if (p.isApprovalOn() && p.isCanManage() && p.getAprTo() == null) {
            // 결재선 기본값 = 상신할 사람(지금 사용자)의 부서장(위로 올라가며 첫 장, 본인 건너뜀). 기본값일 뿐 — 상신 때 결재선은 사람이 확정한다
            UserOption head = users.deptHeadOf(me().userId());
            if (head != null) {
                p.setAprDefaultUserId(head.getUserId());
                p.setAprDefaultLoginId(head.getLoginId());
                p.setAprDefaultUserNm(head.getUserNm());
            }
        }
        if (p.getEcoId() != null) {
            EcoLookup.of(ecos).eco(p.getEcoId()).ifPresent(e -> {
                p.setEcoNo(e.ecoNo());
                p.setEcoTitle(e.title());
                p.setEcoStatus(e.status());
                p.setEcoCustApproval(e.custApprovalStatus());
            });
        }
        return p;
    }

    /**
     * PLM ECO 연결·해제(PM7, D17·D20) — PM·PMO, plm-eco SEARCH. 번호로 찾고(EcoLookup.byNo, 대소문자 무시) 취소된 ECO 는 거부.
     * 회사 안에서 ECO 하나에 프로젝트 하나, PPAP 연결 산출물이 있으면 바꾸거나 풀 수 없다(D18, 트리거도)
     */
    @Transactional
    public void linkEco(long projId, EcoLinkForm in) {
        LoginUser me = me();
        ProjectRow cur = tasks.openForEdit(projId, me, true);
        notInApproval(cur);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (!permission.actions(ECO_PROG).contains(Action.SEARCH)) {
            throw new BusinessException(HbErrorCode.NO_PERMISSION);
        }
        Long ecoId = null;
        if (!isBlank(in.ecoNo())) {
            String no = in.ecoNo().strip();
            EcoView e = no.length() > 50 ? null : EcoLookup.of(ecos).byNo(no).orElse(null);
            if (e == null) {
                throw fieldError("ecoNo", "PMS_ECO_NOT_FOUND");
            }
            if ("CANCELLED".equals(e.status())) {
                throw fieldError("ecoNo", "PMS_ECO_CANCELLED");
            }
            ecoId = e.ecoId();
        }
        if (!java.util.Objects.equals(ecoId, cur.getEcoId()) && gm.countPpapLinked(projId) > 0) {
            throw fieldError("ecoNo", "PMS_ECO_FIXED");
        }
        Long id = ecoId;
        if (translate(() -> (long) mapper.updateProjectEco(projId, id, in.rowVer(), me.loginId())) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    @Transactional
    public long create(ProjectForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("PMS_PROJECT_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        ProjectValue v = validate(in, me.userId(), e);
        List<ItemRef> items = items(in.itemCds(), e);
        TemplateRow tmpl = null;
        LocalDate anchorDate = null;
        if (!isBlank(in.tmplCd())) {
            String cd = upper(in.tmplCd());
            tmpl = cd.length() > 20 ? null : mapper.findTemplateByCd(cd);
            if (tmpl == null) {
                e.add("tmplCd", "NO_SUCH_CODE");
            } else if (!"Y".equals(tmpl.getUseYn())) {
                e.add("tmplCd", "INACTIVE");
            } else if ("SOP".equals(tmpl.getAnchor())) {
                anchorDate = v == null ? null : v.sopDate();
                if (anchorDate == null && v != null) {
                    e.add("sopDate", "PMS_SOP_FOR_TEMPLATE");
                }
            } else {
                anchorDate = date(in.startDate(), "startDate", true, e);
            }
        }
        e.throwIfAny();
        mapper.ensureNumberingRule(RULE, "프로젝트 번호", "PJ-{yyyy}-{seq:4}", "YEAR", me.loginId());
        String no = numbering.next(RULE);
        Long tmplId = tmpl == null ? null : tmpl.getTmplId();
        long id = translate(() -> mapper.insertProject(no, v, tmplId, me.loginId()));
        for (ItemRef i : items) {
            mapper.insertProjectItem(id, i.getItemId(), me.loginId());
        }
        if (tmpl != null) {
            copyTemplate(id, tmpl, anchorDate, me.loginId());
        }
        return id;
    }

    /**
     * 기존 프로젝트 WBS 복사 → 새 프로젝트(docs/32 4.17, 사용자 결정 P-6·P-7). 머리는 입력(신규와 같은 검사), 시작일 필수.
     * 복사 = 작업 트리·종류·이름·비고·계획 공수(잎 작업)·선후행(lag)·산출물 정의(이름·필수·서식)·게이트 점검 문구.
     * 일정 = 원본을 통째로 평행 이동(원본 첫 계획 시작 → 새 시작일, 기간·간격 유지). 담당·실적·진척·상태·기준선·변경이력·
     * 일정변경요청·산출물 제출/결정·이슈는 가져오지 않는다. 원본은 회사 안 어떤 상태든(종료·취소 포함) — 읽기만
     */
    @Transactional
    public long copy(long srcId, ProjectForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("PMS_PROJECT_COPY");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (mapper.findProject(srcId, days.lateRef()) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        FieldErrors e = FieldErrors.create();
        if (!isBlank(in.tmplCd())) {
            e.add("tmplCd", "PMS_COPY_NO_TEMPLATE");   // 템플릿과 원본 프로젝트를 섞지 않는다
        }
        ProjectValue v = validate(in, me.userId(), e);
        List<ItemRef> items = items(in.itemCds(), e);
        LocalDate start = date(in.startDate(), "startDate", true, e);
        e.throwIfAny();
        List<TaskRow> src = mapper.findTasks(srcId);
        int max = policy.intValue(Policies.PMS_MAX_TASKS);
        if (src.size() > max) {
            throw new BusinessException(HbErrorCode.PMS_TOO_MANY_TASKS, max);
        }
        mapper.ensureNumberingRule(RULE, "프로젝트 번호", "PJ-{yyyy}-{seq:4}", "YEAR", me.loginId());
        String no = numbering.next(RULE);
        long id = translate(() -> mapper.insertProject(no, v, null, me.loginId()));
        for (ItemRef i : items) {
            mapper.insertProjectItem(id, i.getItemId(), me.loginId());
        }
        copyWbs(srcId, id, src, start, me.loginId());
        return id;
    }

    private void copyWbs(long srcId, long projId, List<TaskRow> src, LocalDate start, String by) {
        if (src.isEmpty()) {
            return;
        }
        LocalDate first = src.stream().map(TaskRow::getPlanStart).min(LocalDate::compareTo).orElseThrow();
        long shift = java.time.temporal.ChronoUnit.DAYS.between(first, start);
        // 부모가 먼저 들어가도록 깊이 순(같은 깊이는 원본 순서 그대로)
        List<TaskRow> ordered = WbsTree.parentsFirst(src, TaskRow::getTaskId, TaskRow::getParentId);
        Map<Long, Long> ids = new HashMap<>();
        for (TaskRow t : ordered) {
            LocalDate ps = t.getPlanStart().plusDays(shift);
            LocalDate pe = t.getPlanEnd().plusDays(shift);
            if (ps.isBefore(PmsInputs.MIN_DATE) || pe.isAfter(PmsInputs.MAX_DATE)) {
                throw fieldError("startDate", "RANGE", PmsInputs.MIN_DATE.toString(), PmsInputs.MAX_DATE.toString());
            }
            Long parent = t.getParentId() == null ? null : ids.get(t.getParentId());
            // 계획 공수는 잎 작업만(요약은 rollup 이 자식 합으로)
            long nid = mapper.insertTask(projId, parent, t.getSeq(),
                    new TaskValue(t.getTaskType(), t.getTaskNm(), null, ps, pe, null, null, 0, "NOT_STARTED", t.getRemark(),
                            "TASK".equals(t.getTaskType()) && !"Y".equals(t.getIsSummary()) ? t.getPlanHours() : null), by);
            ids.put(t.getTaskId(), nid);
        }
        for (PmsDtos.DepRow d : mapper.findDeps(srcId)) {
            mapper.insertDep(projId, ids.get(d.getPredTaskId()), ids.get(d.getSuccTaskId()), d.getLagDays(), d.getDepType(), by);
        }
        // 산출물 정의 — 담당·기한·제출·결정·연결은 비움(기한 = 작업 계획 종료일로 파생)
        for (io.smarthappy.module.pms.GateDtos.DelivRow d : gm.findDeliverables(srcId)) {
            gm.insertDeliverable(projId, ids.get(d.getTaskId()), d.getDelivNm(), d.getRequiredYn(), d.getFormDocNo(), null, null, by);
        }
        for (TaskRow t : ordered) {
            if ("GATE".equals(t.getTaskType())) {
                int seq = 0;
                for (String c : gm.findTaskChecks(t.getTaskId())) {
                    seq += 10;
                    gm.insertTaskCheck(projId, ids.get(t.getTaskId()), seq, c, by);
                }
            }
        }
        tasks.rollup(projId, by);
    }

    /**
     * 템플릿 작업 → 프로젝트 작업(한 트랜잭션). 날짜 = 기준일 + 기준일 대비 시작·끝, 요약은 자식에서 다시 계산.
     * 템플릿 출처는 tmpl_id 로만 남기고 이후 템플릿 변경은 반영하지 않는다(4.1)
     */
    private void copyTemplate(long projId, TemplateRow tmpl, LocalDate anchor, String by) {
        List<Placed<TmplTaskRow>> src = templates.placed(tmpl.getTmplId());
        int max = policy.intValue(Policies.PMS_MAX_TASKS);
        if (src.size() > max) {
            throw new BusinessException(HbErrorCode.PMS_TOO_MANY_TASKS, max);
        }
        Map<Long, Long> ids = new HashMap<>();
        for (Placed<TmplTaskRow> x : src) {
            TmplTaskRow t = x.row();
            LocalDate ps = anchor.plusDays(t.getStartDay());
            LocalDate pe = anchor.plusDays(t.getEndDay());
            if (ps.isBefore(PmsInputs.MIN_DATE) || pe.isAfter(PmsInputs.MAX_DATE)) {
                throw fieldError("startDate", "RANGE", PmsInputs.MIN_DATE.toString(), PmsInputs.MAX_DATE.toString());
            }
            Long parent = t.getParentId() == null ? null : ids.get(t.getParentId());
            // 표준 공수 → 잎 작업 계획 공수(docs/48 R-2). 요약은 복사 뒤 자식 합으로 다시 계산(tasks.rollup)
            long nid = mapper.insertTask(projId, parent, t.getSeq(),
                    new TaskValue(t.getTaskType(), t.getTaskNm(), null, ps, pe, null, null, 0, "NOT_STARTED", t.getRemark(),
                            "TASK".equals(t.getTaskType()) && !"Y".equals(t.getIsSummary()) ? t.getStdHours() : null), by);
            ids.put(t.getTtId(), nid);
        }
        // 템플릿 선후행(docs/32 4.18) → 프로젝트 의존(잎끼리라 그대로 들어간다)
        for (PmsDtos.TmplDepRow d : mapper.findTmplDeps(tmpl.getTmplId())) {
            mapper.insertDep(projId, ids.get(d.getPredTtId()), ids.get(d.getSuccTtId()), d.getLagDays(), d.getDepType(), by);
        }
        // PM3: 산출물 칸 → pms_deliverable(기한 = 작업 계획 종료일), 게이트 점검 문구 → pms_task_check
        java.util.Map<Long, Integer> checkSeq = new HashMap<>();
        for (io.smarthappy.module.pms.GateDtos.TemplateItemRow i : gm.findTemplateItems(tmpl.getTmplId())) {
            Long taskId = ids.get(i.getTtId());
            if (taskId == null) {
                continue;
            }
            if ("DELIV".equals(i.getKind())) {
                gm.insertDeliverable(projId, taskId, i.getItemNm(), i.getRequiredYn(), i.getFormDocNo(), null, null, by);
            } else {
                int seq = checkSeq.merge(taskId, 10, Integer::sum);
                gm.insertTaskCheck(projId, taskId, seq, i.getItemNm(), by);
            }
        }
        tasks.rollup(projId, by);
    }

    @Transactional
    public void update(long projId, ProjectForm in) {
        LoginUser me = me();
        ProjectRow cur = tasks.openForEdit(projId, me, true);
        notInApproval(cur);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.pmLoginId() != null && in.pmLoginId().strip().equalsIgnoreCase(cur.getPmLoginId())) {
            // 지금 PM 그대로 — 그 계정이 사용 중지돼도 머리 저장이 막히지 않게
            in = new ProjectForm(in.projNm(), in.projType(), in.custCd(), in.plantCd(), null, in.sopDate(), in.remark(),
                    in.itemCds(), in.tmplCd(), in.startDate(), in.rowVer());
        }
        FieldErrors e = FieldErrors.create();
        ProjectValue v = validate(in, cur.getPmUserId(), e);
        List<ItemRef> items = in.itemCds() == null ? null : items(in.itemCds(), e);
        e.throwIfAny();
        int rowVer = in.rowVer();
        if (translate(() -> (long) mapper.updateProject(projId, v, rowVer, me.loginId())) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (items != null) {
            Map<Long, ItemRef> want = new LinkedHashMap<>();
            items.forEach(i -> want.put(i.getItemId(), i));
            for (ItemRef have : mapper.findProjectItems(projId)) {
                if (want.remove(have.getItemId()) == null) {
                    mapper.deleteProjectItem(projId, have.getItemId());
                }
            }
            for (ItemRef i : want.values()) {
                mapper.insertProjectItem(projId, i.getItemId(), me.loginId());
            }
        }
    }

    /**
     * 상태 전이(트리거와 같은 표): DRAFT→ACTIVE·CANCELLED, ACTIVE→ON_HOLD·CLOSED·CANCELLED, ON_HOLD→ACTIVE·CANCELLED.
     * 보류·취소는 사유 필수, 종료는 열린 잎 작업이 없어야(닫을 때 필수, 11 D13)
     */
    @Transactional
    public void changeStatus(long projId, StatusForm in) {
        LoginUser me = me();
        ProjectRow cur = tasks.openForEdit(projId, me, true);
        notInApproval(cur);  // 결재 중이면 어떤 전이도 안 된다(입력 검사보다 먼저 — 고쳐도 소용없으니)
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String to = upper(in.status());
        String from = cur.getStatus();
        boolean ok = to != null && switch (from) {
            case "DRAFT" -> to.equals("ACTIVE") || to.equals("CANCELLED");
            case "ACTIVE" -> to.equals("ON_HOLD") || to.equals("CLOSED") || to.equals("CANCELLED");
            case "ON_HOLD" -> to.equals("ACTIVE") || to.equals("CANCELLED");
            default -> false;
        };
        if (!ok) {
            throw fieldError("status", "PMS_TRANSITION");
        }
        FieldErrors e = FieldErrors.create();
        String reason = visible(in.reason(), "reason", 500, "ON_HOLD".equals(to) || "CANCELLED".equals(to), e);
        e.throwIfAny();
        // 착수·종료 결재(docs/32 4.16): 정책 켬이면 착수(작성 중 → 진행)·종료(진행 → 종료)는 결재로만
        if (policy.bool(Policies.PMS_PROJ_APPROVAL) && approvalTarget(from, to)) {
            throw fieldError("status", "PMS_APPROVAL_REQUIRED");
        }
        if ("CLOSED".equals(to)) {
            ProjClose.check(mapper, gm, projId);
        }
        LocalDate closedOn = "CLOSED".equals(to) || "CANCELLED".equals(to) ? clock.today() : null;
        if (mapper.updateProjectStatus(projId, to, reason, closedOn, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 결재 대상 전이 — 착수(작성 중 → 진행)·종료(진행 → 종료) */
    static boolean approvalTarget(String from, String to) {
        return ("DRAFT".equals(from) && "ACTIVE".equals(to)) || ("ACTIVE".equals(from) && "CLOSED".equals(to));
    }

    /**
     * 착수·종료 상신(docs/32 4.16, P-3·P-4) — 정책 PMS_PROJ_APPROVAL 켬일 때, 관리하는 사람이. 종료는 조건을 지금 한 번 보고(승인 때 또 본다),
     * 머리에 목표 상태를 걸어 잠근 뒤 플랫폼 결재 상신(문서 유형 PMS_PROJ_OPEN·PMS_PROJ_CLOSE). 결재선은 상신자가 고른다
     * (P-3 "부서장" 기본값은 플랫폼에 부서장 자료가 없어 계약 뒤 — 화면 기본값만 바뀐다). 반환 = 결재 id
     */
    @Transactional
    public long submitApproval(long projId, ProjAprForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("PMS_PROJ_APPROVAL");
        ProjectRow cur = tasks.openForEdit(projId, me, true);
        if (!policy.bool(Policies.PMS_PROJ_APPROVAL)) {
            throw new BusinessException(HbErrorCode.PMS_PROJ_APR_STATE);
        }
        if (cur.getAprTo() != null) {
            throw new BusinessException(HbErrorCode.PMS_PROJ_IN_APPROVAL);
        }
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.rowVer() != cur.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        String to = upper(in.to());
        if (!approvalTarget(cur.getStatus(), to)) {
            throw fieldError("to", "PMS_TRANSITION");
        }
        if (in.lines() == null || in.lines().isEmpty()) {
            throw fieldError("lines", "REQUIRED");
        }
        List<LineSpec> lines = new ArrayList<>();
        for (ProjAprLine l : in.lines()) {
            if (l == null || l.approverId() == null) {
                throw fieldError("lines", "REQUIRED");
            }
            LineType type;
            try {
                type = l.lineType() == null ? LineType.APPROVE : LineType.valueOf(upper(l.lineType()));
            } catch (IllegalArgumentException ex) {
                throw fieldError("lines", "CODE");
            }
            lines.add(new LineSpec(l.approverId(), type));
        }
        if ("CLOSED".equals(to)) {
            ProjClose.check(mapper, gm, projId);
        }
        if (mapper.startProjectApproval(projId, to, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        String title = isBlank(in.title())
                ? cur.getProjNo() + " " + cur.getProjNm() + " " + ("ACTIVE".equals(to) ? "착수" : "종료")
                : in.title().strip();
        long aprId = approvals.submit(ProjApprovalDocType.docTypeOf(to), String.valueOf(projId),
                title.length() > 200 ? title.substring(0, 200) : title, lines);
        mapper.setProjectAprId(projId, aprId);
        return aprId;
    }

    public record ProjAprLine(Long approverId, String lineType) {
    }

    public record ProjAprForm(String to, String title, List<ProjAprLine> lines, Integer rowVer) {
    }

    /** 결재 중이면 머리·연결을 고치지 않는다(트리거도 막는다) */
    private static void notInApproval(ProjectRow p) {
        if (p.getAprTo() != null) {
            throw new BusinessException(HbErrorCode.PMS_PROJ_IN_APPROVAL);
        }
    }

    // ------------------------------------------------------------------ 검증

    /** pmLoginId 를 비우면 defaultPm(신규 = 나, 수정 = 지금 PM) */
    private ProjectValue validate(ProjectForm in, long defaultPm, FieldErrors e) {
        String nm = visible(in.projNm(), "projNm", 200, true, e);
        String type = upper(in.projType());
        if (type == null) {
            e.add("projType", "REQUIRED");
        } else if (!mapper.codeUsable(PmsInputs.PROJ_TYPE_GRP, type)) {
            e.add("projType", "CODE");
        }
        Long custId = null;
        if (!isBlank(in.custCd())) {
            String cd = upper(in.custCd());
            custId = cd.length() > 30 ? null : mapper.findCustId(cd);
            if (custId == null) {
                e.add("custCd", "NO_SUCH_CODE");
            }
        }
        String plant = upper(in.plantCd());
        if (plant != null && (plant.length() > 20 || mapper.plantUseYn(plant) == null)) {
            e.add("plantCd", "NO_SUCH_CODE");
        }
        long pm = defaultPm;
        if (!isBlank(in.pmLoginId())) {
            String login = in.pmLoginId().strip();
            UserOption u = users.activeByLogin(login);
            if (u == null) {
                e.add("pmLoginId", "NO_SUCH_CODE");
            } else {
                pm = u.getUserId();
            }
        }
        LocalDate sop = date(in.sopDate(), "sopDate", false, e);
        String remark = visible(in.remark(), "remark", 1000, false, e);
        return e.isEmpty() ? new ProjectValue(nm, type, custId, plant, pm, sop, remark) : null;
    }

    private List<ItemRef> items(List<String> codes, FieldErrors e) {
        List<ItemRef> out = new ArrayList<>();
        if (codes == null) {
            return out;
        }
        if (codes.size() > MAX_ITEMS) {
            e.add("itemCds", "PMS_TOO_MANY_ITEMS", MAX_ITEMS);
            return out;
        }
        Set<Long> seen = new java.util.HashSet<>();
        for (String c : codes) {
            String cd = upper(c);
            if (cd == null) {
                continue;
            }
            ItemRef i = cd.length() > 40 ? null : mapper.findItem(cd);
            if (i == null) {
                e.add("itemCds", "PMS_NO_SUCH_ITEM", cd.length() > 40 ? cd.substring(0, 40) : cd);
            } else if (seen.add(i.getItemId())) {
                out.add(i);
            }
        }
        return out;
    }

    private static long translate(java.util.function.LongSupplier op) {
        try {
            return op.getAsLong();
        } catch (DataIntegrityViolationException ex) {
            String c = PgErrors.constraint(ex).orElse("");
            throw switch (c) {
                case "fk_pms_project_cust_comp", "fk_pms_project_cust" -> fieldError("custCd", "NO_SUCH_CODE");
                case "fk_pms_project_pm_comp" -> fieldError("pmLoginId", "NO_SUCH_CODE");
                case "fk_pms_project_plant" -> fieldError("plantCd", "NO_SUCH_CODE");
                case "uq_pms_project_eco" -> fieldError("ecoNo", "PMS_ECO_TAKEN");
                case "fk_pms_project_eco_comp" -> fieldError("ecoNo", "PMS_ECO_NOT_FOUND");
                default -> ex;
            };
        }
    }
}
