package io.smarthappy.module.pms;

import static io.smarthappy.platform.common.text.VisibleText.isBlank;
import static io.smarthappy.module.pms.PmsInputs.date;
import static io.smarthappy.module.pms.PmsInputs.fieldError;
import static io.smarthappy.module.pms.PmsInputs.me;
import static io.smarthappy.module.pms.PmsInputs.visible;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.pms.GateDtos.Approval;
import io.smarthappy.module.pms.GateDtos.AprLineRow;
import io.smarthappy.module.pms.PmsDtos.ProjectRow;
import io.smarthappy.module.pms.PmsDtos.PushMove;
import io.smarthappy.module.pms.PmsDtos.TaskRow;
import io.smarthappy.module.pms.TaskChgDtos.ChgDetail;
import io.smarthappy.module.pms.TaskChgDtos.ChgForm;
import io.smarthappy.module.pms.TaskChgDtos.ChgInfo;
import io.smarthappy.module.pms.TaskChgDtos.ChgRow;
import io.smarthappy.module.pms.TaskChgDtos.DecideForm;
import io.smarthappy.module.pms.TaskChgDtos.WithdrawForm;
import io.smarthappy.module.pms.WbsTree.Placed;
import io.smarthappy.platform.approval.ApprovalService;
import io.smarthappy.platform.approval.ApprovalService.LineSpec;
import io.smarthappy.platform.approval.ApprovalService.LineType;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 담당자 일정변경 요청 → 승인 (pms-project, docs/32 4.13·docs/48 R-3).
 * - 요청 = 잎 작업 담당자(관리하는 사람은 작업 폼에서 바로 고친다). 새 계획일 + 사유 + 화면이 본 작업 row_ver
 * - 정책 PMS_TASK_CHG_APPROVAL=Y → 플랫폼 결재(결재선 기본 = 그 프로젝트 PM), N → 관리하는 사람이 바로 승인·반려.
 *   요청 때 정한 방식으로 끝까지(정책을 바꿔도 열린 요청은 그대로)
 * - 승인 = TaskChgApplier(결재 콜백과 같은 함수). 후행 밀기는 하지 않는다 — 상세의 미리보기로 보이고 PM 이 기존 [후행 밀기]로
 */
@Service
@RequiredArgsConstructor
public class TaskChgService {

    private static final int LIST_LIMIT = 200;

    private final PmsMapper mapper;
    private final TaskChgMapper cm;
    private final GateMapper gm;
    private final TaskService tasks;
    private final TaskChgApplier applier;
    private final ApprovalService approvals;
    private final PolicyService policy;
    private final InboxService inbox;
    private final PmsDays days;
    private final PmsNotifier notifier;

    /** 작업 폼에서 요청 칸을 그릴 때 — 정책·결재선 기본값(PM)·이 작업의 열린 요청 */
    @Transactional(readOnly = true)
    public ChgInfo info(long taskId) {
        TaskRow t = mapper.findTask(taskId);
        if (t == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        ProjectRow p = project(t.getProjId());
        return new ChgInfo(policy.bool(Policies.PMS_TASK_CHG_APPROVAL), p.getPmUserId(), p.getPmUserNm(), cm.findOpenOfTask(taskId));
    }

    @Transactional(readOnly = true)
    public List<ChgRow> list(long projId) {
        project(projId);
        List<ChgRow> rows = cm.findReqsOfProject(projId, LIST_LIMIT);
        Map<Long, String> wbs = new HashMap<>();
        tasks.placed(projId).forEach(x -> wbs.put(x.row().getTaskId(), x.wbsNo()));
        rows.forEach(r -> r.setWbsNo(wbs.get(r.getTaskId())));
        return rows;
    }

    /** 요청 — 반환 = 요청 id. 정책 Y 면 같은 트랜잭션에서 결재 상신(실패하면 요청도 없다) */
    @Transactional
    public long request(long taskId, ChgForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("PMS_TASK_CHG");
        TaskRow t = mapper.findTask(taskId);
        if (t == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        ProjectRow p = tasks.openForEdit(t.getProjId(), me, false);
        t = mapper.findTask(taskId);            // 잠근 뒤 다시 읽는다
        if (!Objects.equals(t.getOwnerUserId(), me.userId())) {
            throw new BusinessException(HbErrorCode.PMS_NOT_EDITABLE);
        }
        if (tasks.canManage(p, me)) {
            // 관리하는 사람은 작업 폼에서 사유와 함께 바로 고친다 — 자기 요청을 자기가 승인하는 길을 만들지 않는다
            throw fieldError("newStart", "PMS_CHG_DIRECT");
        }
        if ("Y".equals(t.getIsSummary())) {
            throw fieldError("newStart", "PMS_SUMMARY_DERIVED");
        }
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.rowVer() != t.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        FieldErrors e = FieldErrors.create();
        LocalDate ns = date(in.newStart(), "newStart", true, e);
        LocalDate ne = isBlank(in.newEnd()) ? ns : date(in.newEnd(), "newEnd", true, e);
        String reason = visible(in.reason(), "reason", 500, true, e);
        if (ns != null && ne != null) {
            if (ne.isBefore(ns)) {
                e.add("newEnd", "PMS_END_BEFORE_START");
            } else if (!"TASK".equals(t.getTaskType()) && !ne.equals(ns)) {
                e.add("newEnd", "PMS_ONE_DAY");
            } else if (ns.equals(t.getPlanStart()) && ne.equals(t.getPlanEnd())) {
                e.add("newStart", "PMS_CHG_SAME");
            }
        }
        e.throwIfAny();
        if (cm.findOpenOfTask(taskId) != null) {
            throw new BusinessException(HbErrorCode.PMS_CHG_PENDING);
        }
        boolean viaApproval = policy.bool(Policies.PMS_TASK_CHG_APPROVAL);
        long reqId;
        try {
            reqId = cm.insertReq(t.getProjId(), taskId, me.userId(), t.getPlanStart(), t.getPlanEnd(), ns, ne, reason, t.getRowVer(),
                    viaApproval ? "IN_APPROVAL" : "REQUESTED", me.loginId());
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(HbErrorCode.PMS_CHG_PENDING);
        }
        if (viaApproval) {
            long approver = in.approverId() == null ? p.getPmUserId() : in.approverId();
            String title = p.getProjNo() + " " + t.getTaskNm() + " " + ns + "~" + ne;
            long aprId = approvals.submit(TaskChgApprovalDocType.DOC_TYPE, String.valueOf(reqId),
                    title.length() > 200 ? title.substring(0, 200) : title, List.of(new LineSpec(approver, LineType.APPROVE)));
            cm.setAprId(reqId, aprId);
            // 결재자에게는 결재 차례 알림(APR_TODO)이 간다 — PM 이 결재자가 아닐 때만 PM 에게 따로
            if (approver != p.getPmUserId()) {
                notifier.chgRequested(p.getPmUserId(), reqId, p.getProjNo(), t.getTaskNm());
            }
        } else {
            notifier.chgRequested(p.getPmUserId(), reqId, p.getProjNo(), t.getTaskNm());
        }
        return reqId;
    }

    /**
     * 상세 — 프로젝트를 볼 수 있는 사람(pms-project SEARCH). 미리보기 = 지금 계획에서 이 작업만 새 날짜로 바꿨을 때
     * 후행 밀기 계산(WbsSchedule.push) 결과 중 바꾸기 전에도 같은 자리로 밀릴 것은 뺀 것 — 승인은 밀지 않는다
     */
    @Transactional(readOnly = true)
    public ChgDetail detail(long reqId) {
        LoginUser me = me();
        ChgRow r = cm.findReq(reqId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        ProjectRow p = project(r.getProjId());
        Map<Long, TaskRow> byId = new HashMap<>();
        for (Placed<TaskRow> x : tasks.placed(r.getProjId())) {
            byId.put(x.row().getTaskId(), x.row());
        }
        TaskRow t = byId.get(r.getTaskId());
        if (t != null) {
            r.setWbsNo(t.getWbsNo());
        }
        boolean open = "REQUESTED".equals(r.getStatus()) || "IN_APPROVAL".equals(r.getStatus());
        List<PushMove> preview = List.of();
        List<PushMove> fixed = List.of();
        if (open && t != null) {
            var deps = mapper.findDeps(r.getProjId());
            Set<String> before = new HashSet<>();
            try {
                WbsSchedule.push(byId, deps).moves().forEach(m -> before.add(m.taskId() + "@" + m.newStart()));
                t.setPlanStart(r.getNewStart());
                t.setPlanEnd(r.getNewEnd());
                WbsSchedule.Plan after = WbsSchedule.push(byId, deps);
                preview = after.moves().stream().filter(m -> !before.contains(m.taskId() + "@" + m.newStart())).toList();
                fixed = after.fixed();
            } catch (IllegalStateException ex) {
                // 의존 순환(트리거가 막지만 옛 데이터) — 미리보기만 비운다
                preview = List.of();
            }
        }
        TaskRow cur = mapper.findTask(r.getTaskId());
        boolean taskChanged = open && (cur == null || cur.getRowVer() != r.getTaskRowVer());
        Approval apr;
        if (r.getAprId() == null) {
            apr = new Approval(null, null, false, false, List.of());
        } else {
            List<AprLineRow> lines = gm.findAprLines(r.getAprId());
            boolean pending = "PENDING".equals(r.getAprStatus());
            boolean myTurn = pending && lines.stream().anyMatch(l -> "CURRENT".equals(l.getStatus()) && l.getApproverId() == me.userId());
            boolean acted = lines.stream().anyMatch(l -> "APPROVED".equals(l.getStatus()));
            boolean withdraw = pending && !acted && r.getAprRequesterId() != null && r.getAprRequesterId() == me.userId();
            apr = new Approval(r.getAprId(), r.getAprStatus(), myTurn, withdraw, lines);
        }
        boolean direct = "REQUESTED".equals(r.getStatus()) && TaskService.OPEN.contains(p.getStatus());
        return new ChgDetail(r, p.getProjNo(), p.getProjNm(), cur == null ? null : cur.getPlanStart(),
                cur == null ? null : cur.getPlanEnd(), preview, fixed, taskChanged, apr,
                direct && tasks.canManage(p, me), direct && r.getRequesterId() == me.userId());
    }

    /** 바로 처리(정책 N 으로 만든 요청) — 관리하는 사람만. 결재 중 요청은 결재함에서만 */
    @Transactional
    public void decide(long reqId, DecideForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("PMS_TASK_CHG_DECIDE");
        ChgRow r = applier.lock(reqId);
        tasks.openForEdit(r.getProjId(), me, true);
        if (!"REQUESTED".equals(r.getStatus())) {
            throw new BusinessException(HbErrorCode.PMS_CHG_STATE);
        }
        if (in == null || in.approve() == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.rowVer() != r.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        FieldErrors e = FieldErrors.create();
        String note = visible(in.note(), "note", 500, false, e);
        e.throwIfAny();
        if (r.getRequesterId() == me.userId()) {
            // 요청 뒤 관리 권한을 받은 요청자 — 자기 요청은 자기가 처리하지 않는다(결재 자기결재 금지와 같은 뜻)
            throw new BusinessException(HbErrorCode.PMS_NOT_EDITABLE);
        }
        if (in.approve()) {
            applier.approve(r, note, me.loginId());
        } else {
            applier.close(reqId, "REJECTED", note, me.loginId());
        }
        // 바로 처리만 — 결재로 간 요청의 결과는 결재 결과 알림(APR_DONE)이 요청자에게 간다
        notifier.chgResult(r.getRequesterId(), reqId, in.approve(), project(r.getProjId()).getProjNo(), r.getTaskNm());
    }

    /** 요청자 회수 — 바로 처리 요청만(결재 중 요청은 결재함 회수 → 결재 콜백이 끝낸다) */
    @Transactional
    public void withdraw(long reqId, WithdrawForm in) {
        LoginUser me = me();
        ChgRow r = applier.lock(reqId);
        if (r.getRequesterId() != me.userId()) {
            throw new BusinessException(HbErrorCode.PMS_NOT_EDITABLE);
        }
        if (!"REQUESTED".equals(r.getStatus())) {
            throw new BusinessException(HbErrorCode.PMS_CHG_STATE);
        }
        if (in == null || in.rowVer() == null || in.rowVer() != r.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        applier.close(reqId, "WITHDRAWN", null, me.loginId());
    }

    private ProjectRow project(long projId) {
        ProjectRow p = mapper.findProject(projId, days.lateRef());
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return p;
    }
}
