package io.smarthappy.module.pms;

import static io.smarthappy.module.pms.PmsInputs.fieldError;
import static io.smarthappy.module.pms.PmsInputs.me;
import static io.smarthappy.module.pms.PmsInputs.visible;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.pms.PmsDtos.BaselineForm;
import io.smarthappy.module.pms.PmsDtos.BaselineRow;
import io.smarthappy.module.pms.PmsDtos.DepForm;
import io.smarthappy.module.pms.PmsDtos.DepRow;
import io.smarthappy.module.pms.PmsDtos.PlanChangeRow;
import io.smarthappy.module.pms.PmsDtos.PushForm;
import io.smarthappy.module.pms.PmsDtos.PushMove;
import io.smarthappy.module.pms.PmsDtos.PushPlan;
import io.smarthappy.module.pms.PmsDtos.PushRef;
import io.smarthappy.module.pms.PmsDtos.ProjectRow;
import io.smarthappy.module.pms.PmsDtos.TaskRow;
import io.smarthappy.module.pms.WbsTree.Placed;
import io.smarthappy.module.pms.WbsTree.Rollup;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 의존·기준선·후행 밀기 (pms-project) — docs/32 PM2, 4.4, D3·D4.
 * 모두 "프로젝트를 관리하는 사람"(PM·APPROVE) 만. 머리 행 잠금으로 같은 프로젝트 편집과 줄 세운다.
 */
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private static final int BASELINE_BATCH = 500;

    private final PmsMapper mapper;
    private final TaskService tasks;
    private final BizClock clock;
    private final PmsDays days;

    // ------------------------------------------------------------------ 의존

    @Transactional
    public long addDep(long projId, DepForm in) {
        LoginUser me = me();
        tasks.openForEdit(projId, me, true);
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        if (in.predTaskId() == null) {
            e.add("predTaskId", "REQUIRED");
        }
        if (in.succTaskId() == null) {
            e.add("succTaskId", "REQUIRED");
        }
        int lag = in.lagDays() == null ? 0 : in.lagDays();
        if (lag < -365 || lag > 365) {
            e.add("lagDays", "RANGE", -365, 365);
        }
        String type = PmsInputs.depType(in.depType(), e);
        e.throwIfAny();
        long pred = in.predTaskId();
        long succ = in.succTaskId();
        if (pred == succ) {
            throw fieldError("predTaskId", "PMS_DEP_SELF");
        }
        TaskRow p = mapper.findTask(pred);
        TaskRow s = mapper.findTask(succ);
        if (p == null || p.getProjId() != projId) {
            throw fieldError("predTaskId", "NOT_FOUND");
        }
        if (s == null || s.getProjId() != projId) {
            throw fieldError("succTaskId", "NOT_FOUND");
        }
        if ("Y".equals(p.getIsSummary()) || "Y".equals(s.getIsSummary())) {
            throw fieldError("predTaskId", "PMS_DEP_SUMMARY");
        }
        List<DepRow> deps = mapper.findDeps(projId);
        if (deps.stream().anyMatch(d -> d.getPredTaskId() == pred && d.getSuccTaskId() == succ)) {
            throw fieldError("predTaskId", "DUPLICATE");
        }
        if (WbsSchedule.makesCycle(deps, pred, succ)) {
            throw fieldError("predTaskId", "PMS_DEP_CYCLE");
        }
        long depId;
        try {
            depId = mapper.insertDep(projId, pred, succ, lag, type, me.loginId());
        } catch (DuplicateKeyException ex) {
            throw fieldError("predTaskId", "DUPLICATE");
        } catch (DataIntegrityViolationException ex) {
            String c = PgErrors.constraint(ex).orElse("");
            throw switch (c) {
                case "ck_pms_task_dep_cycle" -> fieldError("predTaskId", "PMS_DEP_CYCLE");
                case "ck_pms_task_dep_tasks" -> fieldError("predTaskId", "PMS_DEP_SUMMARY");
                default -> ex;
            };
        }
        // P-b: 이미 시작(FF 는 완료)한 후행에 조건을 못 지킨 선행 — BLOCK 이면 사유·기록(같은 트랜잭션, 거부면 의존도 롤백). 종류별 = docs/32 4.27 S4
        tasks.waiveDepOnStarted(projId, p, s, type, in.depReason(), me.loginId());
        return depId;
    }

    @Transactional
    public void deleteDep(long depId) {
        LoginUser me = me();
        Long projId = mapper.findDepProjId(depId);
        if (projId == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        tasks.openForEdit(projId, me, true);
        mapper.deleteDep(depId);
    }

    // ------------------------------------------------------------------ 기준선

    @Transactional(readOnly = true)
    public List<BaselineRow> baselines(long projId) {
        project(projId);
        return mapper.findBaselines(projId);
    }

    /** 지금 계획을 기준선으로(번호 = 마지막 + 1). 모든 작업(요약 포함)의 이름·WBS·계획일 스냅샷 */
    @Transactional
    public int setBaseline(long projId, BaselineForm in) {
        LoginUser me = me();
        tasks.openForEdit(projId, me, true);
        FieldErrors e = FieldErrors.create();
        String reason = visible(in == null ? null : in.reason(), "reason", 500, true, e);
        e.throwIfAny();
        List<Placed<TaskRow>> p = tasks.placed(projId);
        if (p.isEmpty()) {
            throw fieldError("reason", "PMS_NO_TASKS");
        }
        Rollup r = WbsTree.project(p);
        BaselineRow last = mapper.findLatestBaseline(projId);
        int no = last == null ? 1 : last.getBaselineNo() + 1;
        long id = mapper.insertBaseline(projId, no, reason, p.size(), r == null ? null : r.planEnd(), me.loginId());
        List<TaskRow> rows = p.stream().map(Placed::row).toList();
        for (int i = 0; i < rows.size(); i += BASELINE_BATCH) {
            mapper.insertBaselineTasks(id, rows.subList(i, Math.min(rows.size(), i + BASELINE_BATCH)));
        }
        return no;
    }

    // ------------------------------------------------------------------ 후행 밀기

    @Transactional(readOnly = true)
    public PushPlan previewPush(long projId) {
        project(projId);
        WbsSchedule.Plan plan = plan(projId);
        return new PushPlan(plan.moves(), plan.fixed(), mapper.findLatestBaseline(projId) != null);
    }

    /**
     * 화면이 본 미리보기(expected)와 지금 다시 계산한 결과가 같을 때만 적용 — 그 사이 계획이 바뀌었으면 409.
     * 기준선이 있으면 사유 필수, 작업마다 계획 변경 이력(PUSH)
     */
    @Transactional
    public int applyPush(long projId, PushForm in) {
        LoginUser me = me();
        tasks.openForEdit(projId, me, true);
        if (in == null || in.expected() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        WbsSchedule.Plan plan = plan(projId);
        if (plan.moves().isEmpty()) {
            throw fieldError("expected", "PMS_NOTHING_TO_PUSH");
        }
        Set<String> want = new HashSet<>();
        for (PushRef r : in.expected()) {
            want.add(r.taskId() + "@" + r.newStart());
        }
        Set<String> have = new HashSet<>();
        for (PushMove m : plan.moves()) {
            have.add(m.taskId() + "@" + m.newStart());
        }
        if (!want.equals(have)) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        BaselineRow bl = mapper.findLatestBaseline(projId);
        String reason = null;
        if (bl != null) {
            FieldErrors e = FieldErrors.create();
            reason = visible(in.reason(), "reason", 500, true, e);
            e.throwIfAny();
        }
        Map<Long, TaskRow> byId = byId(projId);
        for (PushMove m : plan.moves()) {
            if (m.newEnd().isAfter(PmsInputs.MAX_DATE)) {
                throw fieldError("expected", "RANGE", PmsInputs.MIN_DATE.toString(), PmsInputs.MAX_DATE.toString());
            }
            mapper.updateTaskPlan(m.taskId(), m.newStart(), m.newEnd(), me.loginId());
            if (bl != null) {
                mapper.insertPlanChange(projId, m.taskId(), bl.getBaselineNo(), byId.get(m.taskId()), m.newStart(), m.newEnd(),
                        "PUSH", reason, me.loginId());
            }
        }
        tasks.rollup(projId, me.loginId());
        return plan.moves().size();
    }

    @Transactional(readOnly = true)
    public List<PlanChangeRow> planChanges(long taskId) {
        if (mapper.findTask(taskId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return mapper.findPlanChanges(taskId);
    }

    // ------------------------------------------------------------------ 내부

    private WbsSchedule.Plan plan(long projId) {
        Map<Long, TaskRow> byId = byId(projId);
        try {
            return WbsSchedule.push(byId, mapper.findDeps(projId));
        } catch (IllegalStateException ex) {
            throw fieldError("expected", "PMS_DEP_CYCLE");
        }
    }

    private Map<Long, TaskRow> byId(long projId) {
        Map<Long, TaskRow> m = new HashMap<>();
        tasks.placed(projId).forEach(x -> m.put(x.row().getTaskId(), x.row()));
        return m;
    }

    private ProjectRow project(long projId) {
        ProjectRow p = mapper.findProject(projId, days.lateRef());
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return p;
    }

}
