package io.smarthappy.module.pms;

import static io.smarthappy.platform.common.text.VisibleText.isBlank;
import static io.smarthappy.module.pms.PmsInputs.date;
import static io.smarthappy.module.pms.PmsInputs.fieldError;
import static io.smarthappy.module.pms.PmsInputs.me;
import static io.smarthappy.module.pms.PmsInputs.visible;
import static io.smarthappy.module.pms.PmsInputs.upper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.pms.PmsDtos.BaseTask;
import io.smarthappy.module.pms.PmsDtos.BaselineRow;
import io.smarthappy.module.pms.PmsDtos.DepRow;
import io.smarthappy.module.pms.PmsDtos.EffortSum;
import io.smarthappy.module.pms.PmsDtos.MoveForm;
import io.smarthappy.module.pms.PmsDtos.ProjectRow;
import io.smarthappy.module.pms.PmsDtos.TaskForm;
import io.smarthappy.module.pms.PmsDtos.TaskRow;
import io.smarthappy.module.pms.PmsDtos.TaskValue;
import io.smarthappy.module.pms.PmsDtos.TaskView;
import io.smarthappy.module.pms.WbsMoves.Move;
import io.smarthappy.module.pms.WbsMoves.Slot;
import io.smarthappy.module.pms.WbsTree.Placed;
import io.smarthappy.module.pms.WbsTree.Rollup;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import lombok.RequiredArgsConstructor;

/**
 * WBS 작업 (pms-project 의 작업 표·간트) — docs/32 PM1, 3장.
 * - 구조(추가·삭제·이동·계획일·이름·담당) = 프로젝트를 관리하는 사람(PM 또는 APPROVE), 진행(실적·진척·상태·비고) = 담당자도 (D8)
 * - 요약 작업(자식 있음)의 날짜·진척·상태는 자식에서 파생 — 저장할 때마다 프로젝트 전체를 다시 계산해 바뀐 행만 쓴다.
 *   같은 프로젝트 편집은 머리 행 잠금(FOR UPDATE)으로 줄 세운다(동시 저장 두 건이 서로의 파생 값을 덮지 않게)
 */
@Service
@RequiredArgsConstructor
public class TaskService {

    static final String PROG = "pms-project";
    static final Set<String> OPEN = Set.of("DRAFT", "ACTIVE", "ON_HOLD");
    private static final Set<String> STATUSES = Set.of("NOT_STARTED", "IN_PROGRESS", "DONE", "CANCELLED");

    private final PmsMapper mapper;
    private final UserDirectory users;
    private final GateMapper gm;
    private final PolicyService policy;
    private final PermissionService permission;
    private final BizClock clock;
    private final PmsDays days;
    private final PmsNotifier notifier;

    @Transactional(readOnly = true)
    public TaskView view(long projId) {
        LocalDate today = clock.today();
        LocalDate lateRef = days.lateRef(today);
        ProjectRow p = mapper.findProject(projId, lateRef);
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        LoginUser me = me();
        boolean manage = canManage(p, me);
        p.setCanManage(manage);
        List<Placed<TaskRow>> pl = placed(projId);
        List<TaskRow> rows = pl.stream().map(x -> {
            TaskRow t = x.row();
            t.setLate("N".equals(t.getIsSummary()) && OPEN_TASK.contains(t.getStatus()) && t.getPlanEnd().isBefore(lateRef));
            t.setCanEdit(OPEN.contains(p.getStatus()) && (manage || Objects.equals(t.getOwnerUserId(), me.userId())));
            return t;
        }).toList();
        // PM2: 의존 위반 · 최신 기준선 대비 지연
        Map<Long, TaskRow> byId = new HashMap<>();
        rows.forEach(t -> byId.put(t.getTaskId(), t));
        List<DepRow> deps = mapper.findDeps(projId);
        Set<Long> violated = WbsSchedule.markViolations(byId, deps);
        violated.forEach(id -> byId.get(id).setDepViolation(true));
        // PM3: 앞 게이트가 통과되기 전에 착수한 작업(정책 WARN 의 기록 = 착수일·통과일 자체)
        markGateWarnings(rows);
        BaselineRow bl = mapper.findLatestBaseline(projId);
        Integer projectSlip = null;
        if (bl != null) {
            for (BaseTask b : mapper.findBaselineTasks(bl.getBaselineId())) {
                TaskRow t = byId.get(b.getTaskId());
                if (t != null) {
                    t.setBaseStart(b.getPlanStart());
                    t.setBaseEnd(b.getPlanEnd());
                    t.setSlipDays((int) ChronoUnit.DAYS.between(b.getPlanEnd(), t.getPlanEnd()));
                }
            }
            if (bl.getPlanEnd() != null && p.getPlanEnd() != null) {
                projectSlip = (int) ChronoUnit.DAYS.between(bl.getPlanEnd(), p.getPlanEnd());
            }
        }
        return new TaskView(p, rows, today, deps, bl, projectSlip, effort(projId, rows, byId, pl));
    }

    private static final Set<String> OPEN_TASK = Set.of("NOT_STARTED", "IN_PROGRESS");

    /**
     * 실적 공수(docs/48 R-2) — 작업별 확정·미확정을 행에 넣고 요약은 아래 작업 합(전위 순서의 역순 = 자식 먼저).
     * 프로젝트 요약: 계획 = 뿌리 작업 합(취소 제외), 확정·미확정 = 작업 없이 올린 공수까지 전부
     */
    private EffortSum effort(long projId, List<TaskRow> rows, Map<Long, TaskRow> byId, List<Placed<TaskRow>> pl) {
        BigDecimal conf = BigDecimal.ZERO;
        BigDecimal draft = BigDecimal.ZERO;
        for (PmsDtos.TaskHours h : mapper.findTaskHours(projId)) {
            conf = conf.add(h.getConfHours());
            draft = draft.add(h.getDraftHours());
            TaskRow t = h.getTaskId() == null ? null : byId.get(h.getTaskId());
            if (t != null) {
                t.setConfHours(h.getConfHours());
                t.setDraftHours(h.getDraftHours());
            }
        }
        for (int i = rows.size() - 1; i >= 0; i--) {
            TaskRow t = rows.get(i);
            TaskRow parent = t.getParentId() == null ? null : byId.get(t.getParentId());
            if (parent != null) {
                parent.setConfHours(plus(parent.getConfHours(), t.getConfHours()));
                parent.setDraftHours(plus(parent.getDraftHours(), t.getDraftHours()));
            }
        }
        Rollup r = WbsTree.project(pl);
        return effortSum(r == null ? null : r.planHours(), conf, draft, policy.intValue(Policies.PMS_HOURS_PER_DAY));
    }

    private static BigDecimal plus(BigDecimal a, BigDecimal b) {
        return a == null ? b : b == null ? a : a.add(b);
    }

    /** 소진율 = 확정 ÷ 계획 × 100(반올림, 계획 없거나 0 이면 null), 남은 = 계획 − 확정(넘으면 음수) — 포트폴리오 SQL 과 같은 규칙 */
    static EffortSum effortSum(BigDecimal plan, BigDecimal conf, BigDecimal draft, int hoursPerDay) {
        Integer burn = plan == null || plan.signum() == 0 ? null
                : conf.multiply(BigDecimal.valueOf(100)).divide(plan, 0, java.math.RoundingMode.HALF_UP).intValue();
        return new EffortSum(plan, conf, draft, burn, plan == null ? null : plan.subtract(conf), hoursPerDay);
    }

    /** 작업 고르기(WBS 순서) — 공수·이슈 화면이 프로젝트 화면 권한 없이 쓴다 */
    @Transactional(readOnly = true)
    public List<GateDtos.TaskOption> options(long projId) {
        if (mapper.findProject(projId, days.lateRef()) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return placed(projId).stream()
                .map(x -> new GateDtos.TaskOption(x.row().getTaskId(), x.row().getWbsNo(), x.row().getTaskNm(), x.row().getTaskType()))
                .toList();
    }

    List<Placed<TaskRow>> placed(long projId) {
        return placed(mapper, projId);
    }

    /** 매퍼만으로 — 결재 콜백(문서 유형 빈)이 서비스에 기대지 않게(순환 방지, TaskChgApplier) */
    static List<Placed<TaskRow>> placed(PmsMapper mapper, long projId) {
        List<Placed<TaskRow>> p = WbsTree.order(mapper.findTasks(projId), TaskRow::getTaskId, TaskRow::getParentId, TaskRow::getSeq);
        p.forEach(x -> {
            x.row().setLvl(x.lvl());
            x.row().setWbsNo(x.wbsNo());
        });
        return p;
    }

    boolean canManage(ProjectRow p, LoginUser me) {
        return p.getPmUserId() == me.userId() || permission.actions(PROG).contains(Action.APPROVE);
    }

    /** 머리 행 잠금 + 열린 프로젝트 + (manage 면) 관리 권한 확인 */
    ProjectRow openForEdit(long projId, LoginUser me, boolean manage) {
        String status = mapper.lockProject(projId);
        if (status == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!OPEN.contains(status)) {
            throw new BusinessException(HbErrorCode.PMS_PROJECT_CLOSED);
        }
        ProjectRow p = mapper.findProject(projId, days.lateRef());
        if (manage && !canManage(p, me)) {
            throw new BusinessException(HbErrorCode.PMS_NOT_EDITABLE);
        }
        return p;
    }

    // ------------------------------------------------------------------ 추가·수정·이동·삭제

    @Transactional
    public long add(long projId, TaskForm in) {
        LoginUser me = me();
        ProjectRow proj = openForEdit(projId, me, true);
        List<Placed<TaskRow>> p = placed(projId);
        int max = policy.intValue(Policies.PMS_MAX_TASKS);
        if (p.size() + 1 > max) {
            throw new BusinessException(HbErrorCode.PMS_TOO_MANY_TASKS, max);
        }
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        Long parentId = in.parentId();
        int lvl = 1;
        if (parentId != null) {
            Placed<TaskRow> par = TemplateService.find(p, parentId, TaskRow::getTaskId);
            if (par == null) {
                throw fieldError("parentId", "NOT_FOUND");
            }
            if (!canHaveKids(par.row())) {
                throw fieldError("parentId", "PMS_NO_CHILDREN");
            }
            if (mapper.countDepsOfTask(parentId) > 0) {
                throw fieldError("parentId", "PMS_HAS_DEPS");
            }
            lvl = par.lvl() + 1;
        }
        checkDepth(lvl);
        TaskValue v = validate(in, null);
        if ("GATE".equals(v.taskType()) && "DONE".equals(v.status())) {
            throw new BusinessException(HbErrorCode.PMS_GATE_BY_REVIEW);
        }
        Slot s = WbsMoves.slot(p, TaskRow::getTaskId, TaskRow::getParentId, TaskRow::getSeq, parentId, in.afterId());
        for (Move m : s.renumber()) {
            mapper.moveTask(m.id(), m.parentId(), m.seq(), me.loginId());
        }
        long id = translate(() -> mapper.insertTask(projId, parentId, s.seq(), v, me.loginId()));
        rollup(projId, me.loginId());
        if (v.ownerUserId() != null) {
            notifier.taskAssigned(v.ownerUserId(), id, 0, proj.getProjNo(), v.taskNm());
        }
        return id;
    }

    /** 반환 = 경고(정책 WARN 에서 통과 전에 지나친 게이트 이름) — 저장은 됐다 */
    @Transactional
    public List<String> update(long taskId, TaskForm in) {
        LoginUser me = me();
        TaskRow cur = taskOrThrow(taskId);
        ProjectRow p = openForEdit(cur.getProjId(), me, false);
        cur = taskOrThrow(taskId);          // 잠근 뒤 다시 읽는다
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.rowVer() != cur.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        boolean manage = canManage(p, me);
        if (!manage && !Objects.equals(cur.getOwnerUserId(), me.userId())) {
            throw new BusinessException(HbErrorCode.PMS_NOT_EDITABLE);
        }
        TaskValue v = validate(in, cur);
        if (!manage && (!v.taskType().equals(cur.getTaskType()) || !v.taskNm().equals(cur.getTaskNm())
                || !Objects.equals(v.ownerUserId(), cur.getOwnerUserId()) || !v.planStart().equals(cur.getPlanStart())
                || !v.planEnd().equals(cur.getPlanEnd()) || !WbsTree.sameHours(v.planHours(), cur.getPlanHours()))) {
            // 담당자는 진행(실적·진척·상태·비고)만 — 계획(일정·계획 공수)·구조는 PM
            throw new BusinessException(HbErrorCode.PMS_NOT_EDITABLE);
        }
        // PM3: 게이트 완료·완료 취소는 게이트 심사 결정으로만
        if (("GATE".equals(cur.getTaskType()) || "GATE".equals(v.taskType()))
                && (("DONE".equals(v.status()) != "DONE".equals(cur.getStatus()))
                    || ("DONE".equals(cur.getStatus()) && (!Objects.equals(v.actStart(), cur.getActStart())
                        || !Objects.equals(v.actEnd(), cur.getActEnd()) || !v.taskType().equals(cur.getTaskType()))))) {
            throw new BusinessException(HbErrorCode.PMS_GATE_BY_REVIEW);
        }
        List<String> warnings = new java.util.ArrayList<>();
        // 게이트 제외는 지금·새 유형이 둘 다 게이트일 때만 — 새 값만 보면 같은 요청으로 GATE 로 바꾸며 실적 시작을 넣어
        // 게이트 단계·선행 제약을 건너뛰고 뒤에 되돌릴 수 있다(QA 2026-11-02 P-a)
        boolean stayGate = "GATE".equals(cur.getTaskType()) && "GATE".equals(v.taskType());
        if (cur.getActStart() == null && v.actStart() != null && !"Y".equals(cur.getIsSummary()) && !stayGate) {
            String mode = policy.value(Policies.PMS_GATE_ENFORCE);
            if (!"OFF".equals(mode)) {
                TaskRow gate = openGateBefore(placed(cur.getProjId()), taskId);
                if (gate != null && "BLOCK".equals(mode)) {
                    throw new BusinessException(HbErrorCode.PMS_GATE_NOT_PASSED, gate.getTaskNm());
                }
                if (gate != null) {
                    warnings.add(gate.getTaskNm());
                }
            }
        }
        // 선후행 실적 제약(docs/32 4.21·4.27, P-12·P-15): BLOCK 이면 실적 시작을 처음 넣을 때 FS 선행 완료·SS 선행 시작이, 처음 완료할 때
        // FF 선행 완료가 필요하다(완료·취소된 선행은 지킨 것으로). PM(관리 가능)은 사유를 적으면 통과 + 기록. 게이트는 심사로 끝나므로 빼고,
        // 요약은 하위에서 계산되므로 뺀다. WARN = 위반 표시만(지금 그대로)
        String waiveReason = null;
        String waivePreds = null;
        boolean starting = cur.getActStart() == null && v.actStart() != null;
        boolean finishing = !"DONE".equals(cur.getStatus()) && "DONE".equals(v.status());
        if ((starting || finishing) && !"Y".equals(cur.getIsSummary()) && !stayGate
                && "BLOCK".equals(policy.value(Policies.PMS_DEP_ENFORCE))) {
            List<TaskRow> placedRows = placed(cur.getProjId()).stream().map(Placed::row).toList();
            List<DepRow> deps = mapper.findDeps(cur.getProjId());
            String openStart = starting ? unmetPreds(placedRows, deps, taskId, false) : null;
            String openFinish = finishing ? unmetPreds(placedRows, deps, taskId, true) : null;
            if (openStart != null || openFinish != null) {
                if (!manage) {
                    throw openStart != null ? new BusinessException(HbErrorCode.PMS_DEP_NOT_DONE, openStart)
                            : new BusinessException(HbErrorCode.PMS_DEP_NOT_FINISHED, openFinish);
                }
                FieldErrors e = FieldErrors.create();
                waiveReason = visible(in.depReason(), "depReason", 500, false, e);
                e.throwIfAny();
                if (waiveReason == null) {
                    throw openStart != null ? fieldError("depReason", "PMS_DEP_REASON", openStart)
                            : fieldError("depReason", "PMS_DEP_FINISH_REASON", openFinish);
                }
                String open = openStart == null ? openFinish : openFinish == null ? openStart : openStart + ", " + openFinish;
                waivePreds = open.length() > 2000 ? open.substring(0, 2000) : open;
            }
        }
        // 기준선 뒤 잎 작업 계획일 변경 = 사유 필수 + 이력(D3)
        boolean planChanged = !"Y".equals(cur.getIsSummary())
                && (!v.planStart().equals(cur.getPlanStart()) || !v.planEnd().equals(cur.getPlanEnd()));
        BaselineRow bl = planChanged ? mapper.findLatestBaseline(cur.getProjId()) : null;
        String reason = null;
        if (bl != null) {
            FieldErrors e = FieldErrors.create();
            reason = visible(in.planReason(), "planReason", 500, true, e);
            e.throwIfAny();
        }
        if (translate(() -> (long) mapper.updateTask(taskId, v, in.rowVer(), me.loginId())) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (bl != null) {
            mapper.insertPlanChange(cur.getProjId(), taskId, bl.getBaselineNo(), cur, v.planStart(), v.planEnd(), "EDIT", reason,
                    me.loginId());
        }
        if (waiveReason != null) {
            mapper.insertDepWaive(cur.getProjId(), taskId, waiveReason, waivePreds, me.loginId());
        }
        rollup(cur.getProjId(), me.loginId());
        if (v.ownerUserId() != null && !v.ownerUserId().equals(cur.getOwnerUserId())) {
            notifier.taskAssigned(v.ownerUserId(), taskId, in.rowVer() + 1, p.getProjNo(), v.taskNm());
        }
        return warnings;
    }

    /**
     * 조건을 못 지킨 선행 — "WBS 이름"(SS·FF 는 뒤에 " (SS)"·" (FF)")을 쉼표로, 없으면 null.
     * 시작 순간(finish=false): FS 선행이 끝나지 않음(완료·취소 아님), SS 선행이 시작 안 함(실적 시작 없음·완료·취소 아님).
     * 완료 순간(finish=true): FF 선행이 끝나지 않음
     */
    private static String unmetPreds(List<TaskRow> placedRows, List<DepRow> deps, long taskId, boolean finish) {
        java.util.Map<Long, TaskRow> rows = new java.util.HashMap<>();
        placedRows.forEach(t -> rows.put(t.getTaskId(), t));
        List<String> open = deps.stream().filter(d -> d.getSuccTaskId() == taskId)
                .filter(d -> unmet(d.getDepType(), rows.get(d.getPredTaskId()), finish))
                .map(d -> label(rows.get(d.getPredTaskId()), d.getDepType())).toList();
        return open.isEmpty() ? null : String.join(", ", open);
    }

    /** 이 종류의 의존이 그 순간(시작·완료)에 아직 못 지켜졌나 — 선행 행이 없으면(다른 프로젝트 등) 지킨 것으로 */
    static boolean unmet(String type, TaskRow pred, boolean finish) {
        if (pred == null || "DONE".equals(pred.getStatus()) || "CANCELLED".equals(pred.getStatus())) {
            return false;
        }
        String t = type == null ? "FS" : type;
        return finish ? "FF".equals(t) : "FS".equals(t) || ("SS".equals(t) && pred.getActStart() == null);
    }

    private static String label(TaskRow t, String type) {
        String s = t.getWbsNo() + " " + t.getTaskNm();
        return type == null || "FS".equals(type) ? s : s + " (" + type + ")";
    }

    /**
     * 이미 시작(FF 는 완료)한 후행에 조건을 못 지킨 선행 의존을 거는 경우(docs/32 4.21 E7·4.27 S4, 사용자 결정 P-b·P-15) — 정책 BLOCK 이면
     * 실적 제약과 같은 규칙: FS = 시작한 후행 + 끝나지 않은 선행, SS = 시작한 후행 + 시작 안 한 선행, FF = 완료한 후행 + 끝나지 않은 선행.
     * 사유가 없으면 칸 오류 depReason, 있으면 통과 + 기록(open_preds = 그 선행). 의존 추가는 관리하는 사람만이라 담당자 거부 길은
     * 그 앞(PMS_NOT_EDITABLE)에서 끝난다. 요약·게이트 후행, 아직 시작 안 한 후행(복사로 한꺼번에 생기는 의존 포함), WARN·OFF 는 대상 아님
     */
    void waiveDepOnStarted(long projId, TaskRow pred, TaskRow succ, String type, String depReason, String by) {
        boolean finish = "FF".equals(type);
        boolean reached = finish ? "DONE".equals(succ.getStatus()) : succ.getActStart() != null;
        if (!reached || "Y".equals(succ.getIsSummary()) || "GATE".equals(succ.getTaskType()) || !unmet(type, pred, finish)
                || !"BLOCK".equals(policy.value(Policies.PMS_DEP_ENFORCE))) {
            return;
        }
        String label = placed(projId).stream().map(Placed::row).filter(t -> t.getTaskId() == pred.getTaskId())
                .map(t -> label(t, type)).findFirst().orElse(pred.getTaskNm());
        FieldErrors e = FieldErrors.create();
        String reason = visible(depReason, "depReason", 500, false, e);
        e.throwIfAny();
        if (reason == null) {
            throw fieldError("depReason", "PMS_DEP_ADD_REASON", label);
        }
        mapper.insertDepWaive(projId, succ.getTaskId(), reason, label, by);
    }

    /** 선행 미완 통과 기록(최근 것부터) — 그 작업을 볼 수 있으면(회사 범위) */
    @Transactional(readOnly = true)
    public List<PmsDtos.DepWaiveRow> depWaivers(long taskId) {
        taskOrThrow(taskId);
        return mapper.findDepWaives(taskId);
    }

    /** 내 작업 줄 편집(docs/32 4.19, P-10) — 실적 시작·종료·진척만. 상태는 실적에서(비우면 자동) */
    public record ActualForm(String actStart, String actEnd, Integer progressPct, Integer rowVer) {
    }

    /**
     * 실적만 저장 — 나머지 칸(이름·종류·담당·계획일·계획 공수·비고)은 지금 값 그대로 {@link #update} 로 넘긴다. 그래서 담당자 권한·
     * 게이트 정책·row_ver 충돌·롤업이 상세 폼과 똑같고, 목록이 보여 주지 않는 칸을 덮어쓰지 않는다. 요약 작업은 실적이 하위에서 계산된다
     */
    @Transactional
    public List<String> updateActual(long taskId, ActualForm in) {
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        TaskRow cur = taskOrThrow(taskId);
        if ("Y".equals(cur.getIsSummary())) {
            throw fieldError("actStart", "PMS_SUMMARY_DERIVED");
        }
        // 취소된 작업은 상태를 그대로(실적으로 다시 살리지 않는다), 나머지는 실적에서 상태를 정한다
        String status = "CANCELLED".equals(cur.getStatus()) ? "CANCELLED" : null;
        return update(taskId, new TaskForm(null, null, cur.getTaskType(), cur.getTaskNm(), cur.getOwnerLoginId(),
                cur.getPlanStart().toString(), cur.getPlanEnd().toString(), in.actStart(), in.actEnd(), in.progressPct(), status,
                cur.getRemark(), in.rowVer(), null, cur.getPlanHours(), null));
    }

    @Transactional
    public void move(long taskId, MoveForm in) {
        LoginUser me = me();
        TaskRow cur = taskOrThrow(taskId);
        openForEdit(cur.getProjId(), me, true);
        cur = taskOrThrow(taskId);
        if (in == null || in.rowVer() == null || in.rowVer() != cur.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        List<Placed<TaskRow>> p = placed(cur.getProjId());
        Set<Long> withDeps = new java.util.HashSet<>();
        for (DepRow d : mapper.findDeps(cur.getProjId())) {
            withDeps.add(d.getPredTaskId());
            withDeps.add(d.getSuccTaskId());
        }
        // 의존이 걸린 작업은 부모가 될 수 없다(요약이 되면 의존 뜻이 흐려진다 — V040 트리거도 막는다)
        List<Move> moves = WbsMoves.plan(p, TaskRow::getTaskId, TaskRow::getParentId, taskId, in.op(),
                t -> canHaveKids(t) && !withDeps.contains(t.getTaskId()));
        if ("INDENT".equals(in.op())) {
            Placed<TaskRow> self = TemplateService.find(p, taskId, TaskRow::getTaskId);
            checkDepth(self.lvl() + 1 + TemplateService.subtreeHeight(p, taskId, TaskRow::getTaskId));
        }
        for (Move m : moves) {
            translate(() -> (long) mapper.moveTask(m.id(), m.parentId(), m.seq(), me.loginId()));
        }
        rollup(cur.getProjId(), me.loginId());
    }

    @Transactional
    public void delete(long taskId) {
        LoginUser me = me();
        TaskRow cur = taskOrThrow(taskId);
        openForEdit(cur.getProjId(), me, true);
        cur = taskOrThrow(taskId);
        if ("Y".equals(cur.getIsSummary())) {
            throw fieldError("taskId", "PMS_HAS_CHILDREN");
        }
        if (cur.getActStart() != null) {
            throw fieldError("taskId", "PMS_HAS_ACTUALS");
        }
        // PM3: 제출·결정 이력이 있는 산출물, 게이트 심사, 이슈가 걸린 작업은 지우지 않는다(취소로)
        if (gm.countDeliverablesWithHist(taskId) > 0) {
            throw fieldError("taskId", "PMS_HAS_DELIVERABLES");
        }
        if (gm.countReviewsOfTask(taskId) > 0) {
            throw fieldError("taskId", "PMS_HAS_REVIEWS");
        }
        if (gm.countIssuesOfTask(taskId) > 0) {
            throw fieldError("taskId", "PMS_HAS_ISSUES");
        }
        gm.deleteDeliverablesOfTask(taskId);
        gm.deleteTaskChecks(taskId);
        mapper.deleteDepsOfTask(taskId);
        mapper.deleteTask(taskId);
        rollup(cur.getProjId(), me.loginId());
    }

    // ------------------------------------------------------------------ 게이트 (PM3, D11)

    /** WBS 순서로 이 작업 앞에 있는, 아직 통과(완료)도 취소도 아닌 첫 게이트 — 없으면 null */
    static TaskRow openGateBefore(List<Placed<TaskRow>> p, long taskId) {
        for (Placed<TaskRow> x : p) {
            TaskRow t = x.row();
            if (t.getTaskId() == taskId) {
                return null;
            }
            if ("GATE".equals(t.getTaskType()) && !"DONE".equals(t.getStatus()) && !"CANCELLED".equals(t.getStatus())) {
                return t;
            }
        }
        return null;
    }

    /** 착수일이 앞 게이트 통과일(완료 실적일)보다 빠르거나, 앞 게이트가 아직 통과 전인데 착수한 잎 작업에 그 게이트 이름 */
    static void markGateWarnings(List<TaskRow> wbsOrder) {
        List<TaskRow> gates = new java.util.ArrayList<>();
        for (TaskRow t : wbsOrder) {
            if ("GATE".equals(t.getTaskType())) {
                gates.add(t);
                continue;
            }
            if ("Y".equals(t.getIsSummary()) || t.getActStart() == null) {
                continue;
            }
            for (TaskRow g : gates) {
                boolean passedBefore = "DONE".equals(g.getStatus()) && g.getActEnd() != null && !g.getActEnd().isAfter(t.getActStart());
                if (!passedBefore && !"CANCELLED".equals(g.getStatus())) {
                    t.setGateWarn(g.getTaskNm());
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ 파생

    /** 요약 작업과 프로젝트 머리의 파생 값을 다시 계산해 바뀐 것만 저장 */
    void rollup(long projId, String by) {
        rollup(mapper, projId, by);
    }

    /** 요약 행·프로젝트 파생 값 다시 계산 — 매퍼만으로(TaskChgApplier 도 쓴다) */
    static void rollup(PmsMapper mapper, long projId, String by) {
        List<Placed<TaskRow>> p = placed(mapper, projId);
        Set<Long> changed = WbsTree.rollupInPlace(p);
        for (Placed<TaskRow> x : p) {
            if (changed.contains(x.row().getTaskId())) {
                mapper.updateTaskRollup(x.row(), by);
            }
        }
        Rollup r = WbsTree.project(p);
        mapper.updateProjectRollup(projId, r == null ? null : r.planStart(), r == null ? null : r.planEnd(),
                r == null ? 0 : r.progressPct());
    }

    // ------------------------------------------------------------------ 검증

    /**
     * 작업 입력 → 저장 값. 상태를 비우면 실적에서 정한다(종료일 = 완료, 시작일 = 진행 중, 없음 = 시작 전).
     * 요약 작업(cur 가 요약)은 이름·담당·비고만 — 날짜·진척·상태는 자식에서 오므로 다른 값이 오면 거부
     */
    TaskValue validate(TaskForm in, TaskRow cur) {
        FieldErrors e = FieldErrors.create();
        boolean summary = cur != null && "Y".equals(cur.getIsSummary());
        String type = isBlank(in.taskType()) ? (cur == null ? "TASK" : cur.getTaskType()) : upper(in.taskType());
        if (!PmsInputs.TASK_TYPES.contains(type)) {
            e.add("taskType", "CODE");
        } else if (summary && !"TASK".equals(type)) {
            e.add("taskType", "PMS_HAS_CHILDREN");
        }
        String nm = visible(in.taskNm(), "taskNm", 200, true, e);
        Long owner = null;
        if (!isBlank(in.ownerLoginId())) {
            String login = in.ownerLoginId().strip();
            UserOption u = users.activeByLogin(login);
            if (u == null) {
                e.add("ownerLoginId", "NO_SUCH_CODE");
            } else {
                owner = u.getUserId();
            }
        }
        String remark = visible(in.remark(), "remark", 500, false, e);
        if (summary) {
            e.throwIfAny();
            if (!same(in.planStart(), cur.getPlanStart()) || !same(in.planEnd(), cur.getPlanEnd())
                    || !same(in.actStart(), cur.getActStart()) || !same(in.actEnd(), cur.getActEnd())
                    || (in.progressPct() != null && in.progressPct() != cur.getProgressPct())
                    || (!isBlank(in.status()) && !in.status().strip().equals(cur.getStatus()))) {
                throw fieldError("planStart", "PMS_SUMMARY_DERIVED");
            }
            // 계획 공수도 자식 합(docs/48 R-2) — 다른 값이면 거부, 안 보내면 그대로
            if (in.planHours() != null && !WbsTree.sameHours(in.planHours(), cur.getPlanHours())) {
                throw fieldError("planHours", "PMS_SUMMARY_DERIVED");
            }
            return new TaskValue(type, nm, owner, cur.getPlanStart(), cur.getPlanEnd(), cur.getActStart(), cur.getActEnd(),
                    cur.getProgressPct(), cur.getStatus(), remark, cur.getPlanHours());
        }
        // 계획 공수(시간) — TASK 만(마일스톤·게이트는 기간 0, 값이 오면 거부)
        BigDecimal planHours = PmsInputs.hours(in.planHours(), "planHours", e);
        if (planHours != null && !"TASK".equals(type)) {
            e.add("planHours", "PMS_TASK_ONLY");
        }
        LocalDate ps = date(in.planStart(), "planStart", true, e);
        LocalDate pe = isBlank(in.planEnd()) ? ps : date(in.planEnd(), "planEnd", true, e);
        if (ps != null && pe != null) {
            if (pe.isBefore(ps)) {
                e.add("planEnd", "PMS_END_BEFORE_START");
            } else if (!"TASK".equals(type) && !pe.equals(ps)) {
                e.add("planEnd", "PMS_ONE_DAY");
            }
        }
        LocalDate as = date(in.actStart(), "actStart", false, e);
        LocalDate ae = date(in.actEnd(), "actEnd", false, e);
        LocalDate today = clock.today();
        if (as != null && as.isAfter(today)) {
            e.add("actStart", "FUTURE");
        }
        if (ae != null && ae.isAfter(today)) {
            e.add("actEnd", "FUTURE");
        }
        if (ae != null && as == null) {
            e.add("actStart", "REQUIRED");
        } else if (as != null && ae != null && ae.isBefore(as)) {
            e.add("actEnd", "PMS_END_BEFORE_START");
        }
        Integer pctIn = in.progressPct();
        if (pctIn != null && (pctIn < 0 || pctIn > 100)) {
            e.add("progressPct", "RANGE", 0, 100);
        }
        String status = isBlank(in.status()) ? (ae != null ? "DONE" : as != null ? "IN_PROGRESS" : "NOT_STARTED") : upper(in.status());
        int pct = pctIn == null ? 0 : pctIn;
        if (!STATUSES.contains(status)) {
            e.add("status", "CODE");
        } else {
            switch (status) {
                case "NOT_STARTED" -> {
                    if (as != null || ae != null) {
                        e.add("status", "PMS_STATE_ACTUALS");
                    }
                    if (pct != 0) {
                        e.add("progressPct", "PMS_STATE_PROGRESS");
                    }
                }
                case "IN_PROGRESS" -> {
                    if (as == null) {
                        e.add("actStart", "REQUIRED");
                    }
                    if (ae != null) {
                        e.add("actEnd", "PMS_END_MEANS_DONE");
                    }
                    if (pct >= 100) {
                        e.add("progressPct", "PMS_STATE_PROGRESS");
                    }
                }
                case "DONE" -> {
                    if (ae == null) {
                        e.add("actEnd", "REQUIRED");
                    }
                    pct = 100;
                }
                default -> {
                    // CANCELLED — 그때 실적·진척 그대로(닫을 때 필수: 사유는 비고)
                }
            }
        }
        e.throwIfAny();
        return new TaskValue(type, nm, owner, ps, pe, as, ae, pct, status, remark, planHours);
    }

    private static boolean same(String raw, LocalDate v) {
        return isBlank(raw) ? v == null : v != null && raw.strip().equals(v.toString());
    }

    /** 자식을 가질 수 있나 — 작업(TASK)이고, 실적이 있는 잎이 아니어야(요약이 되면 실적이 자식 값으로 덮인다) */
    static boolean canHaveKids(TaskRow t) {
        return "TASK".equals(t.getTaskType()) && ("Y".equals(t.getIsSummary()) || t.getActStart() == null);
    }

    private TaskRow taskOrThrow(long taskId) {
        TaskRow t = mapper.findTask(taskId);
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

    static long translate(java.util.function.LongSupplier op) {
        try {
            return op.getAsLong();
        } catch (DataIntegrityViolationException e) {
            String c = PgErrors.constraint(e).orElse("");
            throw switch (c) {
                case "ck_pms_task_cycle" -> fieldError("op", "CYCLE");
                case "ck_pms_task_depth" -> fieldError("op", "DEPTH");
                case "ck_pms_task_project_closed" -> new BusinessException(HbErrorCode.PMS_PROJECT_CLOSED);
                case "ck_pms_task_parent_has_deps" -> fieldError("parentId", "PMS_HAS_DEPS");
                case "fk_pms_task_owner_comp" -> fieldError("ownerLoginId", "NO_SUCH_CODE");
                case "ck_pms_task_delete_actual" -> fieldError("taskId", "PMS_HAS_ACTUALS");
                case "ck_pms_task_gate_has_reviews" -> fieldError("taskType", "PMS_HAS_REVIEWS");
                default -> e;
            };
        }
    }
}
