package io.smarthappy.module.pms;

import static io.smarthappy.platform.common.text.VisibleText.isBlank;
import static io.smarthappy.module.pms.PmsInputs.fieldError;
import static io.smarthappy.module.pms.PmsInputs.me;
import static io.smarthappy.module.pms.PmsInputs.visible;
import static io.smarthappy.module.pms.PmsInputs.upper;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.pms.GateDtos.TemplateItemForm;
import io.smarthappy.module.pms.GateDtos.TemplateItemRow;
import io.smarthappy.module.pms.PmsDtos.MoveForm;
import io.smarthappy.module.pms.PmsDtos.TemplateForm;
import io.smarthappy.module.pms.PmsDtos.TemplatePage;
import io.smarthappy.module.pms.PmsDtos.TemplateRow;
import io.smarthappy.module.pms.PmsDtos.TemplateSort;
import io.smarthappy.module.pms.PmsDtos.TmplTaskForm;
import io.smarthappy.module.pms.PmsDtos.TmplTaskRow;
import io.smarthappy.module.pms.PmsDtos.TmplTaskValue;
import io.smarthappy.module.pms.PmsDtos.TmplTaskView;
import io.smarthappy.module.pms.WbsMoves.Move;
import io.smarthappy.module.pms.WbsMoves.Slot;
import io.smarthappy.module.pms.WbsTree.Placed;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 프로젝트 템플릿 (pms-template) — docs/32 PM1·4.1·4.2(D7).
 * 작업 일정은 기준일(START/SOP) 대비 상대 일수. 템플릿을 고쳐도 이미 만든 프로젝트는 그대로(복사만 한다).
 */
@Service
@RequiredArgsConstructor
public class TemplateService {

    static final String PROG = "pms-template";
    static final String DEFAULT_APQP = "APQP-STD";
    private static final int MAX_ITEMS = 30;
    /** 기본 APQP 템플릿의 산출물 칸(작업 이름 → 산출물) — 일반 용어, 매뉴얼 문구 아님(docs/32 4.2) */
    private static final java.util.Map<String, List<String>> DEFAULT_DELIVS = java.util.Map.of(
            "고객 요구사항·CSR 검토", List.of("고객 요구사항 검토서"),
            "타당성 검토(설비·공수·원가)", List.of("타당성 검토서"),
            "설계 FMEA", List.of("DFMEA"),
            "도면·특별특성 확정", List.of("확정 도면", "특별특성 목록"),
            "공정 흐름도", List.of("공정 흐름도"),
            "공정 FMEA", List.of("PFMEA"),
            "관리계획서(양산 전)", List.of("관리계획서(양산 전)"),
            "측정 시스템 분석·초기 공정능력", List.of("MSA 결과", "초기 공정능력 결과"),
            "PPAP 제출·승인", List.of("PPAP 제출 승인서"),
            "Safe Launch(강화 검사)", List.of("초기 안정화 결과 보고"));
    /** 게이트 점검 문구 — 회사가 고쳐 쓴다 */
    private static final List<String> DEFAULT_CHECKS = List.of(
            "이 단계 필수 산출물이 승인 또는 면제되었다", "열린 이슈·위험을 검토하고 담당·기한을 정했다",
            "다음 단계 일정·인원·설비 준비를 확인했다");
    private static final Pattern TMPL_CD = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,19}$");

    private final PmsMapper mapper;
    private final GateMapper gm;
    private final PolicyService policy;
    private final InboxService inbox;
    /** 빈 서식 문서 번호 확인(docs/32 4.17) — QMS 계약으로만, 없으면 빈 값 */
    private final org.springframework.beans.factory.ObjectProvider<io.smarthappy.module.qms.link.DocLookup> docs;
    private final io.smarthappy.platform.auth.PermissionService permission;

    @Transactional(readOnly = true)
    public List<TemplateRow> list(String useYn) {
        return mapper.findTemplates(useYn(useYn), null);
    }

    /** grid(UI 단계 3 ②, docs/32 4.11) — 사용 여부(목록과 같은 조건) + 코드·이름 검색어, 서버 페이징·정렬(허용 목록 {@link TemplateSort}) */
    @Transactional(readOnly = true)
    public TemplatePage grid(String keyword, String useYn, Integer page, Integer size, List<String> sort, Boolean total) {
        String u = useYn(useYn);
        String k = PmsInputs.like(keyword);
        PageQuery pq = PageQuery.parse(page, size, sort, total, TemplateSort.values(), policy.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("tmplCd,asc"));
        return new TemplatePage(mapper.findTemplatePage(u, k, pq), pq.isWithTotal() ? mapper.countTemplates(u, k) : null);
    }

    /** grid 열(목록 표와 같은 칸) */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("tmplCd", "pms.col.tmplCd", 110).withSort().withPriority(1),
            ColumnSpec.text("tmplNm", "pms.col.tmplNm", 200).withSort().withPriority(1).withFlex(),
            ColumnSpec.code("projType", "pms.col.projType", "PMS_PROJ_TYPE", 90).withSort(),
            ColumnSpec.text("anchor", "pms.col.anchor", 90).withSort(),
            ColumnSpec.of("taskCnt", "pms.col.taskCnt", ColumnType.INT, 70).withSort(),
            ColumnSpec.of("useYn", "pms.col.useYn", ColumnType.YN, 84).withSort());

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    private static String useYn(String useYn) {
        String u = isBlank(useYn) ? null : useYn.strip();
        if (u != null && !"Y".equals(u) && !"N".equals(u)) {
            throw fieldError("useYn", "CODE");
        }
        return u;
    }

    @Transactional(readOnly = true)
    public TmplTaskView get(long tmplId) {
        TemplateRow t = mapper.findTemplate(tmplId);
        if (t == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return new TmplTaskView(t, placed(tmplId).stream().map(Placed::row).toList(), mapper.findTmplDeps(tmplId));
    }

    /** 펼친 작업(레벨·WBS 번호·기준일 대비 시작·끝 채움) — 프로젝트 복사도 이것을 쓴다 */
    List<Placed<TmplTaskRow>> placed(long tmplId) {
        List<Placed<TmplTaskRow>> p = WbsTree.order(mapper.findTmplTasks(tmplId), TmplTaskRow::getTtId, TmplTaskRow::getParentId,
                TmplTaskRow::getSeq);
        p.forEach(x -> {
            x.row().setLvl(x.lvl());
            x.row().setWbsNo(x.wbsNo());
        });
        WbsTree.templateSpans(p);
        return p;
    }

    @Transactional
    public long create(TemplateForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("PMS_TEMPLATE_CREATE");
        TemplateForm f = validate(in, null);
        try {
            return mapper.insertTemplate(f, me.loginId());
        } catch (DuplicateKeyException e) {
            throw fieldError("tmplCd", "DUPLICATE");
        }
    }

    @Transactional
    public void update(long tmplId, TemplateForm in) {
        LoginUser me = me();
        TemplateRow cur = mapper.findTemplate(tmplId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        TemplateForm f = validate(in, cur);
        if (mapper.updateTemplate(tmplId, f, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    private TemplateForm validate(TemplateForm in, TemplateRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String cd = cur != null ? cur.getTmplCd() : upper(in.tmplCd());
        if (cur != null && !isBlank(in.tmplCd()) && !cur.getTmplCd().equals(upper(in.tmplCd()))) {
            e.add("tmplCd", "FIXED");
        } else if (cd == null) {
            e.add("tmplCd", "REQUIRED");
        } else if (!TMPL_CD.matcher(cd).matches()) {
            e.add("tmplCd", "FORMAT");
        }
        String nm = visible(in.tmplNm(), "tmplNm", 100, true, e);
        String type = upper(in.projType());
        if (type == null) {
            e.add("projType", "REQUIRED");
        } else if (!mapper.codeUsable(PmsInputs.PROJ_TYPE_GRP, type)) {
            e.add("projType", "CODE");
        }
        String anchor = isBlank(in.anchor()) ? "START" : upper(in.anchor());
        if (!"START".equals(anchor) && !"SOP".equals(anchor)) {
            e.add("anchor", "CODE");
        }
        String use = isBlank(in.useYn()) ? "Y" : in.useYn().strip();
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        }
        String remark = visible(in.remark(), "remark", 500, false, e);
        e.throwIfAny();
        return new TemplateForm(cd, nm, type, anchor, use, remark, in.rowVer());
    }

    // ------------------------------------------------------------------ 작업

    @Transactional
    public long addTask(long tmplId, TmplTaskForm in) {
        LoginUser me = me();
        lock(tmplId);
        TmplTaskValue v = validateTask(in);
        List<Placed<TmplTaskRow>> p = placed(tmplId);
        int max = policy.intValue(Policies.PMS_MAX_TASKS);
        if (p.size() + 1 > max) {
            throw new BusinessException(HbErrorCode.PMS_TOO_MANY_TASKS, max);
        }
        Long parentId = in.parentId();
        int lvl = 1;
        if (parentId != null) {
            Placed<TmplTaskRow> par = find(p, parentId);
            if (par == null) {
                throw fieldError("parentId", "NOT_FOUND");
            }
            if (!"TASK".equals(par.row().getTaskType())) {
                throw fieldError("parentId", "PMS_NO_CHILDREN");
            }
            if (hasDeps(tmplId, parentId)) {
                throw fieldError("parentId", "PMS_HAS_DEPS");   // 의존이 걸린 잎은 요약이 될 수 없다(트리거도)
            }
            lvl = par.lvl() + 1;
        }
        checkDepth(lvl);
        Slot s = WbsMoves.slot(p, TmplTaskRow::getTtId, TmplTaskRow::getParentId, TmplTaskRow::getSeq, parentId, in.afterId());
        for (Move m : s.renumber()) {
            mapper.moveTmplTask(m.id(), m.parentId(), m.seq(), me.loginId());
        }
        return translate(() -> mapper.insertTmplTask(tmplId, parentId, s.seq(), v, me.loginId()));
    }

    @Transactional
    public void updateTask(long ttId, TmplTaskForm in) {
        LoginUser me = me();
        TmplTaskRow cur = taskOrThrow(ttId);
        lock(cur.getTmplId());
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        TmplTaskValue v = validateTask(in);
        if (!"TASK".equals(v.taskType()) && mapper.countTmplChildren(ttId) > 0) {
            // 하위가 있는 작업을 마일스톤·게이트로 바꾸면 "하루짜리 요약" 이 된다
            throw fieldError("taskType", "PMS_HAS_CHILDREN");
        }
        if (translate(() -> (long) mapper.updateTmplTask(ttId, v, in.rowVer(), me.loginId())) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    @Transactional
    public void moveTask(long ttId, MoveForm in) {
        LoginUser me = me();
        TmplTaskRow cur = taskOrThrow(ttId);
        lock(cur.getTmplId());
        if (in == null || in.rowVer() == null || in.rowVer() != cur.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        List<Placed<TmplTaskRow>> p = placed(cur.getTmplId());
        List<Move> moves = WbsMoves.plan(p, TmplTaskRow::getTtId, TmplTaskRow::getParentId, ttId, in.op(),
                t -> "TASK".equals(t.getTaskType()));
        if ("INDENT".equals(in.op())) {
            // 옮긴 작업 아래 가지 전체가 한 단 깊어진다
            Placed<TmplTaskRow> me0 = find(p, ttId);
            checkDepth(me0.lvl() + 1 + subtreeHeight(p, ttId));
        }
        for (Move m : moves) {
            translate(() -> (long) mapper.moveTmplTask(m.id(), m.parentId(), m.seq(), me.loginId()));
        }
    }

    @Transactional
    public void deleteTask(long ttId) {
        TmplTaskRow cur = taskOrThrow(ttId);
        lock(cur.getTmplId());
        if (mapper.countTmplChildren(ttId) > 0) {
            throw fieldError("ttId", "PMS_HAS_CHILDREN");
        }
        gm.deleteTemplateItemsOfTask(ttId);
        mapper.deleteTmplDepsOfTask(ttId);
        mapper.deleteTmplTask(ttId);
    }

    // ------------------------------------------------------------------ 산출물 칸·점검 문구 (PM3)

    @Transactional(readOnly = true)
    public List<TemplateItemRow> items(long tmplId) {
        if (mapper.findTemplate(tmplId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return gm.findTemplateItems(tmplId);
    }

    /** kind = DELIV(산출물 칸, 모든 작업) / CHECK(게이트 점검 문구, 게이트만). 작업마다 칸 수 상한 MAX_ITEMS */
    @Transactional
    public long addItem(long ttId, TemplateItemForm in) {
        LoginUser me = me();
        TmplTaskRow t = taskOrThrow(ttId);
        lock(t.getTmplId());
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String kind = upper(in.kind());
        if (kind == null) {
            e.add("kind", "REQUIRED");
        } else if (!"DELIV".equals(kind) && !"CHECK".equals(kind)) {
            e.add("kind", "CODE");
        } else if ("CHECK".equals(kind) && !"GATE".equals(t.getTaskType())) {
            e.add("kind", "PMS_NOT_GATE");
        }
        String nm = visible(in.itemNm(), "itemNm", 200, true, e);
        String req = isBlank(in.requiredYn()) ? "Y" : in.requiredYn().strip();
        if (!"Y".equals(req) && !"N".equals(req)) {
            e.add("requiredYn", "CODE");
        }
        String form = null;
        if (!isBlank(in.formDocNo())) {
            if ("CHECK".equals(kind)) {
                e.add("formDocNo", "PMS_FORM_DELIV_ONLY");   // 점검 문구에는 서식이 없다 — 조용히 버리지 않는다
            } else {
                form = FormDoc.check(io.smarthappy.module.qms.link.DocLookup.of(docs), in.formDocNo(), "formDocNo", e);
            }
        }
        e.throwIfAny();
        String formDocNo = form;
        long n = gm.findTemplateItems(t.getTmplId()).stream().filter(i -> i.getTtId() == ttId).count();
        if (n >= MAX_ITEMS) {
            throw fieldError("itemNm", "PMS_TOO_MANY_ROWS", MAX_ITEMS);
        }
        return gm.insertTemplateItem(t.getTmplId(), ttId, kind, nm, "CHECK".equals(kind) ? "Y" : req, formDocNo, me.loginId());
    }

    @Transactional
    public void deleteItem(long tiId) {
        Long tmplId = gm.findTemplateItemTmplId(tiId);
        if (tmplId == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        lock(tmplId);
        gm.deleteTemplateItem(tiId);
    }

    // ------------------------------------------------------------------ 기본 APQP 템플릿 (D7)

    /** 일반 용어로 쓴 5단계 APQP 템플릿을 만든다 — 이미 있으면 그 id. 매뉴얼 체크리스트 문구는 넣지 않는다(저작권, docs/32 4.2) */
    @Transactional
    public long createDefaultApqp() {
        LoginUser me = me();
        TemplateRow exists = mapper.findTemplateByCd(DEFAULT_APQP);
        if (exists != null) {
            return exists.getTmplId();
        }
        long id;
        try {
            id = mapper.insertTemplate(new TemplateForm(DEFAULT_APQP, "APQP 표준(양산 준비)", "APQP", "SOP", "Y",
                    "기준일 = 고객 SOP. 단계·게이트·작업은 회사 사정에 맞게 고쳐 쓰세요", null), me.loginId());
        } catch (DuplicateKeyException e) {
            // 동시에 두 번 눌림 — 먼저 만든 것을 쓴다
            return mapper.findTemplateByCd(DEFAULT_APQP).getTmplId();
        }
        Object[][] phases = {
            {"1 계획·정의", new Object[][] {
                {"TASK", "고객 요구사항·CSR 검토", -300, 20}, {"TASK", "타당성 검토(설비·공수·원가)", -280, 15},
                {"GATE", "G1 개발 착수 심사", -265, 0}}},
            {"2 제품 설계·개발", new Object[][] {
                {"TASK", "설계 FMEA", -260, 30}, {"TASK", "도면·특별특성 확정", -240, 20},
                {"TASK", "시작품 제작·시험", -220, 40}, {"GATE", "G2 설계 확정 심사", -180, 0}}},
            {"3 공정 설계·개발", new Object[][] {
                {"TASK", "공정 흐름도", -175, 15}, {"TASK", "공정 FMEA", -165, 25},
                {"TASK", "금형·설비 발주·제작", -170, 90}, {"TASK", "관리계획서(양산 전)", -140, 15},
                {"GATE", "G3 공정 준비 심사", -75, 0}}},
            {"4 제품·공정 검증", new Object[][] {
                {"TASK", "양산 조건 시험 생산", -70, 10}, {"TASK", "측정 시스템 분석·초기 공정능력", -60, 20},
                {"TASK", "PPAP 제출·승인", -40, 30}, {"GATE", "G4 양산 이관 심사", -5, 0},
                {"MILESTONE", "SOP 양산 시작", 0, 0}}},
            {"5 양산·초기 안정화", new Object[][] {
                {"TASK", "Safe Launch(강화 검사)", 0, 90}, {"GATE", "G5 초기 안정화 종료 심사", 90, 0}}},
        };
        int ps = 10;
        for (Object[] ph : phases) {
            long pid = mapper.insertTmplTask(id, null, ps, new TmplTaskValue("TASK", (String) ph[0], 0, 1, null, null), me.loginId());
            int s = 10;
            for (Object[] t : (Object[][]) ph[1]) {
                long tt = mapper.insertTmplTask(id, pid, s, new TmplTaskValue((String) t[0], (String) t[1], (Integer) t[2],
                        (Integer) t[3], null, null), me.loginId());
                for (String d : DEFAULT_DELIVS.getOrDefault((String) t[1], List.of())) {
                    gm.insertTemplateItem(id, tt, "DELIV", d, "Y", null, me.loginId());
                }
                if ("GATE".equals(t[0])) {
                    for (String c : DEFAULT_CHECKS) {
                        gm.insertTemplateItem(id, tt, "CHECK", c, "Y", null, me.loginId());
                    }
                }
                s += 10;
            }
            ps += 10;
        }
        return id;
    }

    // ------------------------------------------------------------------ 내부

    private TmplTaskValue validateTask(TmplTaskForm in) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String type = isBlank(in.taskType()) ? "TASK" : upper(in.taskType());
        if (!PmsInputs.TASK_TYPES.contains(type)) {
            e.add("taskType", "CODE");
        }
        String nm = visible(in.taskNm(), "taskNm", 200, true, e);
        int offset = in.offsetDays() == null ? 0 : in.offsetDays();
        if (offset < -3650 || offset > 3650) {
            e.add("offsetDays", "RANGE", -3650, 3650);
        }
        int dur;
        if ("TASK".equals(type)) {
            dur = in.durationDays() == null ? 1 : in.durationDays();
            if (dur < 1 || dur > 3650) {
                e.add("durationDays", "RANGE", 1, 3650);
            }
        } else {
            dur = 0;   // 마일스톤·게이트는 하루
        }
        String remark = visible(in.remark(), "remark", 500, false, e);
        // 표준 공수(시간, docs/48 R-2) — TASK 만, 요약 작업에 넣어도 프로젝트로 복사할 때는 잎 작업 것만 쓴다
        java.math.BigDecimal std = PmsInputs.hours(in.stdHours(), "stdHours", e);
        if (std != null && !"TASK".equals(type)) {
            e.add("stdHours", "PMS_TASK_ONLY");
        }
        e.throwIfAny();
        return new TmplTaskValue(type, nm, offset, dur, remark, std);
    }

    private boolean hasDeps(long tmplId, long ttId) {
        return mapper.findTmplDeps(tmplId).stream().anyMatch(d -> d.getPredTtId() == ttId || d.getSuccTtId() == ttId);
    }

    // ------------------------------------------------------------------ 선후행 (docs/32 4.18)

    /** 템플릿 선후행 추가 — 프로젝트 의존(ScheduleService.addDep)과 같은 규칙: 같은 템플릿의 잎끼리, 자기 자신·중복·순환 금지, lag ±365 */
    @Transactional
    public long addDep(long tmplId, PmsDtos.TmplDepForm in) {
        LoginUser me = me();
        lock(tmplId);
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        if (in.predTtId() == null) {
            e.add("predTtId", "REQUIRED");
        }
        if (in.succTtId() == null) {
            e.add("succTtId", "REQUIRED");
        }
        int lag = in.lagDays() == null ? 0 : in.lagDays();
        if (lag < -365 || lag > 365) {
            e.add("lagDays", "RANGE", -365, 365);
        }
        String type = PmsInputs.depType(in.depType(), e);
        e.throwIfAny();
        long pred = in.predTtId();
        long succ = in.succTtId();
        if (pred == succ) {
            throw fieldError("predTtId", "PMS_DEP_SELF");
        }
        List<Placed<TmplTaskRow>> p = placed(tmplId);
        Placed<TmplTaskRow> a = find(p, pred);
        Placed<TmplTaskRow> b = find(p, succ);
        if (a == null) {
            throw fieldError("predTtId", "NOT_FOUND");
        }
        if (b == null) {
            throw fieldError("succTtId", "NOT_FOUND");
        }
        if ("Y".equals(a.row().getIsSummary()) || "Y".equals(b.row().getIsSummary())) {
            throw fieldError("predTtId", "PMS_DEP_SUMMARY");
        }
        List<PmsDtos.TmplDepRow> deps = mapper.findTmplDeps(tmplId);
        if (deps.stream().anyMatch(d -> d.getPredTtId() == pred && d.getSuccTtId() == succ)) {
            throw fieldError("predTtId", "DUPLICATE");
        }
        List<PmsDtos.DepRow> asTask = deps.stream().map(d -> {
            PmsDtos.DepRow r = new PmsDtos.DepRow();
            r.setPredTaskId(d.getPredTtId());
            r.setSuccTaskId(d.getSuccTtId());
            return r;
        }).toList();
        if (WbsSchedule.makesCycle(asTask, pred, succ)) {
            throw fieldError("predTtId", "PMS_DEP_CYCLE");
        }
        try {
            return mapper.insertTmplDep(tmplId, pred, succ, lag, type, me.loginId());
        } catch (DuplicateKeyException ex) {
            throw fieldError("predTtId", "DUPLICATE");
        } catch (DataIntegrityViolationException ex) {
            String c = PgErrors.constraint(ex).orElse("");
            throw switch (c) {
                case "ck_pms_template_dep_cycle" -> fieldError("predTtId", "PMS_DEP_CYCLE");
                case "ck_pms_template_dep_tasks" -> fieldError("predTtId", "PMS_DEP_SUMMARY");
                default -> ex;
            };
        }
    }

    @Transactional
    public void deleteDep(long tdepId) {
        Long tmplId = mapper.findTmplDepTmplId(tdepId);
        if (tmplId == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        lock(tmplId);
        mapper.deleteTmplDep(tdepId);
    }

    // ------------------------------------------------------------------ 프로젝트 → 템플릿으로 저장 (docs/32 4.18, P-7)

    /**
     * 프로젝트 WBS → 새 템플릿(기준일 = 시작일). 상대 일정 = 프로젝트 첫 계획 시작에서 며칠 뒤·기간(작업)·0(마일스톤·게이트),
     * 표준 공수 = 잎 작업 계획 공수, 선후행(lag), 산출물 칸(이름·필수·서식), 게이트 점검 문구. 담당·실적·기준선·제출물은 가져오지 않는다.
     * 원본 프로젝트는 회사 안 어떤 상태든 읽기만 — pms-project SEARCH 도 있어야 한다(템플릿 저장 권한만으로 프로젝트를 읽지 않게)
     */
    @Transactional
    public long fromProject(long projId, PmsDtos.TmplFromProjectForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("PMS_TEMPLATE_FROM_PROJECT");
        if (!permission.actions(TaskService.PROG).contains(io.smarthappy.platform.auth.Action.SEARCH)) {
            throw new BusinessException(HbErrorCode.NO_PERMISSION);
        }
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        PmsDtos.ProjectRow src = mapper.findProject(projId, null);
        if (src == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        TemplateForm f = validate(new TemplateForm(in.tmplCd(), isBlank(in.tmplNm()) ? src.getProjNm() : in.tmplNm(),
                isBlank(in.projType()) ? src.getProjType() : in.projType(), "START", "Y", in.remark(), null), null);
        List<PmsDtos.TaskRow> tasks = mapper.findTasks(projId);
        if (tasks.isEmpty()) {
            throw fieldError("tmplCd", "PMS_NO_TASKS");
        }
        int max = policy.intValue(Policies.PMS_MAX_TASKS);
        if (tasks.size() > max) {
            throw new BusinessException(HbErrorCode.PMS_TOO_MANY_TASKS, max);
        }
        java.time.LocalDate first = tasks.stream().map(PmsDtos.TaskRow::getPlanStart).min(java.time.LocalDate::compareTo).orElseThrow();
        long tmplId;
        try {
            tmplId = mapper.insertTemplate(f, me.loginId());
        } catch (DuplicateKeyException e) {
            throw fieldError("tmplCd", "DUPLICATE");
        }
        java.util.Map<Long, Long> ids = new java.util.HashMap<>();
        for (PmsDtos.TaskRow t : WbsTree.parentsFirst(tasks, PmsDtos.TaskRow::getTaskId, PmsDtos.TaskRow::getParentId)) {
            long offset = java.time.temporal.ChronoUnit.DAYS.between(first, t.getPlanStart());
            long dur = "TASK".equals(t.getTaskType()) ? java.time.temporal.ChronoUnit.DAYS.between(t.getPlanStart(), t.getPlanEnd()) + 1 : 0;
            if (offset > 3650 || dur > 3650) {
                // 템플릿 상대 일정 한계(±3650일, V039) — 그보다 긴 프로젝트는 템플릿으로 못 만든다(조용히 자르지 않는다)
                throw fieldError("tmplCd", "PMS_TMPL_SPAN", 3650);
            }
            Long parent = t.getParentId() == null ? null : ids.get(t.getParentId());
            BigDecimal hours = "TASK".equals(t.getTaskType()) && !"Y".equals(t.getIsSummary()) ? t.getPlanHours() : null;
            ids.put(t.getTaskId(), translate(() -> mapper.insertTmplTask(tmplId, parent, t.getSeq(),
                    new TmplTaskValue(t.getTaskType(), t.getTaskNm(), (int) offset, (int) dur, t.getRemark(), hours), me.loginId())));
        }
        for (PmsDtos.DepRow d : mapper.findDeps(projId)) {
            mapper.insertTmplDep(tmplId, ids.get(d.getPredTaskId()), ids.get(d.getSuccTaskId()), d.getLagDays(), d.getDepType(), me.loginId());
        }
        for (GateDtos.DelivRow d : gm.findDeliverables(projId)) {
            gm.insertTemplateItem(tmplId, ids.get(d.getTaskId()), "DELIV", d.getDelivNm(), d.getRequiredYn(), d.getFormDocNo(), me.loginId());
        }
        for (PmsDtos.TaskRow t : tasks) {
            if ("GATE".equals(t.getTaskType())) {
                for (String c : gm.findTaskChecks(t.getTaskId())) {
                    gm.insertTemplateItem(tmplId, ids.get(t.getTaskId()), "CHECK", c, "Y", null, me.loginId());
                }
            }
        }
        return tmplId;
    }

    private void lock(long tmplId) {
        if (mapper.lockTemplate(tmplId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
    }

    private TmplTaskRow taskOrThrow(long ttId) {
        TmplTaskRow t = mapper.findTmplTask(ttId);
        if (t == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return t;
    }

    private void checkDepth(int lvl) {
        int max = policy.intValue(Policies.PMS_MAX_DEPTH);
        if (lvl > max) {
            throw new BusinessException(HbErrorCode.PMS_TOO_DEEP, max);
        }
    }

    static <T> Placed<T> find(List<Placed<T>> p, long id, java.util.function.Function<T, Long> idOf) {
        for (Placed<T> x : p) {
            if (idOf.apply(x.row()) == id) {
                return x;
            }
        }
        return null;
    }

    private static Placed<TmplTaskRow> find(List<Placed<TmplTaskRow>> p, long id) {
        return find(p, id, TmplTaskRow::getTtId);
    }

    /** 이 행 아래 가지의 높이(자식 없음 = 0) — 전위 순서라 바로 뒤에 이어지는 더 깊은 행들이 가지다 */
    static <T> int subtreeHeight(List<Placed<T>> p, long id, java.util.function.Function<T, Long> idOf) {
        int at = -1;
        for (int i = 0; i < p.size(); i++) {
            if (idOf.apply(p.get(i).row()) == id) {
                at = i;
            }
        }
        if (at < 0) {
            return 0;
        }
        int base = p.get(at).lvl();
        int h = 0;
        for (int i = at + 1; i < p.size() && p.get(i).lvl() > base; i++) {
            h = Math.max(h, p.get(i).lvl() - base);
        }
        return h;
    }

    private static int subtreeHeight(List<Placed<TmplTaskRow>> p, long id) {
        return subtreeHeight(p, id, TmplTaskRow::getTtId);
    }

    private static long translate(java.util.function.LongSupplier op) {
        try {
            return op.getAsLong();
        } catch (DataIntegrityViolationException e) {
            String c = PgErrors.constraint(e).orElse("");
            throw switch (c) {
                case "ck_pms_template_task_cycle" -> fieldError("op", "CYCLE");
                case "ck_pms_template_task_depth" -> fieldError("op", "DEPTH");
                case "ck_pms_template_task_duration" -> fieldError("durationDays", "RANGE", 1, 3650);
                case "ck_pms_template_task_has_checks" -> fieldError("taskType", "PMS_HAS_CHECKS");
                case "ck_pms_template_task_parent_has_deps" -> fieldError("op", "PMS_HAS_DEPS");
                default -> e;
            };
        }
    }
}
