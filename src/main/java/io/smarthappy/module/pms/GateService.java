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
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.pms.GateDtos.AprLineRow;
import io.smarthappy.module.pms.GateDtos.Approval;
import io.smarthappy.module.pms.GateDtos.CheckIn;
import io.smarthappy.module.pms.GateDtos.CheckRow;
import io.smarthappy.module.pms.GateDtos.CondIn;
import io.smarthappy.module.pms.GateDtos.CondRow;
import io.smarthappy.module.pms.GateDtos.DelivRow;
import io.smarthappy.module.pms.GateDtos.GateRow;
import io.smarthappy.module.pms.GateDtos.LineIn;
import io.smarthappy.module.pms.GateDtos.ReviewDetail;
import io.smarthappy.module.pms.GateDtos.ReviewForm;
import io.smarthappy.module.pms.GateDtos.ReviewRow;
import io.smarthappy.module.pms.GateDtos.SubmitForm;
import io.smarthappy.module.pms.PmsDtos.ProjectRow;
import io.smarthappy.module.pms.PmsDtos.TaskRow;
import io.smarthappy.module.pms.WbsTree.Placed;
import io.smarthappy.platform.approval.ApprovalService;
import io.smarthappy.platform.approval.ApprovalService.LineSpec;
import io.smarthappy.platform.approval.ApprovalService.LineType;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import lombok.RequiredArgsConstructor;

/**
 * 게이트 심사 (pms-gate) — docs/32 PM3, D11.
 * - 심사 만들기·고치기·상신·취소 = 프로젝트를 관리하는 사람(PM·PMO). 한 게이트에 열린 심사는 하나, 통과한 게이트는 다시 심사하지 않는다
 * - 통과(GO·CONDITIONAL) 상신은 이 게이트 범위의 필수 산출물이 모두 승인·면제여야 하고, 결정 때 한 번 더 확인한다
 * - 정책 PMS_GATE_APPROVAL = Y 면 결재(PMS_GATE), N 이면 APPROVE 권한으로 바로 결정
 * - 잠금 순서 = 프로젝트 머리 → 심사 행(결재 끝 처리와 같게)
 */
@Service
@RequiredArgsConstructor
public class GateService {

    static final String PROG = "pms-gate";
    private static final Set<String> DECISIONS = Set.of("GO", "CONDITIONAL", "NO_GO");
    private static final Set<String> RESULTS = Set.of("OK", "NG", "NA");
    private static final int MAX_ROWS = 50;

    private final GateMapper gm;
    private final PmsMapper mapper;
    private final UserDirectory users;
    private final TaskService tasks;
    private final GateDecider decider;
    private final ApprovalService approvals;
    private final PermissionService permission;
    private final PolicyService policy;
    private final InboxService inbox;
    private final BizClock clock;
    private final PmsDays days;
    private final DeliverableDocSync docSync;

    // ------------------------------------------------------------------ 조회

    /** 게이트 작업(WBS 순서) + 마지막 심사 + 지금 준비율 */
    @Transactional(readOnly = true)
    public List<GateRow> gates(long projId) {
        project(projId);
        List<Placed<TaskRow>> placed = tasks.placed(projId);
        List<DelivRow> delivs = gm.findDeliverables(projId);
        Map<Long, ReviewRow> last = new HashMap<>();
        for (ReviewRow r : gm.findReviewsOfProject(projId)) {
            last.putIfAbsent(r.getGateTaskId(), r);      // 번호 내림차순 — 처음 것이 마지막 심사
        }
        List<GateRow> out = new ArrayList<>();
        for (Placed<TaskRow> x : placed) {
            TaskRow t = x.row();
            if (!"GATE".equals(t.getTaskType())) {
                continue;
            }
            GateRow g = gateRow(t, placed, delivs);
            ReviewRow r = last.get(t.getTaskId());
            if (r != null) {
                g.setReviewId(r.getReviewId());
                g.setReviewNo(r.getReviewNo());
                g.setReviewStatus(r.getStatus());
                g.setDecision(r.getDecision());
            }
            out.add(g);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<ReviewRow> reviewsOfGate(long gateTaskId) {
        TaskRow t = gateTask(gateTaskId);
        return gm.findReviewsOfProject(t.getProjId()).stream().filter(r -> r.getGateTaskId() == gateTaskId).toList();
    }

    @Transactional(readOnly = true)
    public ReviewDetail detail(long reviewId) {
        LoginUser me = me();
        ReviewRow r = gm.findReview(reviewId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        ProjectRow p = project(r.getProjId());
        List<Placed<TaskRow>> placed = tasks.placed(r.getProjId());
        List<DelivRow> delivs = DeliverableService.decorate(placed, gm.findDeliverables(r.getProjId()), days.lateRef());
        TaskRow gate = placed.stream().map(Placed::row).filter(t -> t.getTaskId() == r.getGateTaskId()).findFirst().orElseThrow();
        GateRow g = gateRow(gate, placed, delivs);
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
        boolean edit = "DRAFT".equals(r.getStatus()) && TaskService.OPEN.contains(p.getStatus()) && tasks.canManage(p, me);
        boolean aprRequired = policy.bool(Policies.PMS_GATE_APPROVAL);
        return new ReviewDetail(r, g, p.getProjNo(), p.getProjNm(), gm.findChecks(reviewId), gm.findConds(reviewId),
                DeliverableService.scope(placed, gate.getTaskId(), delivs), apr, edit, aprRequired,
                edit && !aprRequired && permission.actions(PROG).contains(Action.APPROVE));
    }

    // ------------------------------------------------------------------ 만들기·저장·취소

    /** 게이트 작업에 새 심사(작성 중). 점검표는 게이트의 점검 문구(템플릿 복사본)로 시작 */
    @Transactional
    public long create(long gateTaskId) {
        LoginUser me = me();
        inbox.claimRequestKey("PMS_GATE_CREATE");
        TaskRow t = gateTask(gateTaskId);
        tasks.openForEdit(t.getProjId(), me, true);
        t = gateTask(gateTaskId);
        if ("DONE".equals(t.getStatus()) || "CANCELLED".equals(t.getStatus())) {
            throw new BusinessException(HbErrorCode.PMS_GATE_STATE);
        }
        long id;
        try {
            id = gm.insertReview(t.getProjId(), gateTaskId, gm.nextReviewNo(gateTaskId), clock.today(), me.loginId());
        } catch (DuplicateKeyException e) {
            // 열린 심사가 이미 있다(uq_pms_gate_review_open)
            throw new BusinessException(HbErrorCode.PMS_GATE_STATE);
        }
        int seq = 10;
        for (String c : gm.findTaskChecks(gateTaskId)) {
            gm.insertCheck(id, seq, c, null, null);
            seq += 10;
        }
        return id;
    }

    @Transactional
    public void update(long reviewId, ReviewForm in) {
        LoginUser me = me();
        ReviewRow r = lockDraft(reviewId, me);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.rowVer() != r.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        FieldErrors e = FieldErrors.create();
        LocalDate day = date(in.reviewDate(), "reviewDate", true, e);
        if (day != null && day.isAfter(clock.today())) {
            e.add("reviewDate", "FUTURE");
        }
        String decision = upper(in.decision());
        if (decision != null && !DECISIONS.contains(decision)) {
            e.add("decision", "CODE");
        }
        String summary = visible(in.summary(), "summary", 2000, false, e);
        List<CheckIn> checks = in.checks() == null ? List.of() : in.checks();
        List<CondIn> conds = in.conds() == null ? List.of() : in.conds();
        if (checks.size() > MAX_ROWS) {
            e.add("checks", "PMS_TOO_MANY_ROWS", MAX_ROWS);
        }
        if (conds.size() > MAX_ROWS) {
            e.add("conds", "PMS_TOO_MANY_ROWS", MAX_ROWS);
        }
        List<Object[]> checkRows = new ArrayList<>();
        for (int i = 0; i < checks.size() && i < MAX_ROWS; i++) {
            CheckIn c = checks.get(i);
            FieldErrors re = FieldErrors.create();
            String nm = visible(c == null ? null : c.checkNm(), "checkNm", 200, true, re);
            String res = c == null ? null : upper(c.result());
            if (res != null && !RESULTS.contains(res)) {
                re.add("result", "CODE");
            }
            String note = visible(c == null ? null : c.note(), "note", 500, false, re);
            moveRow(re, e, "c" + i);
            checkRows.add(new Object[] {nm, res, note});
        }
        List<Object[]> condRows = new ArrayList<>();
        for (int i = 0; i < conds.size() && i < MAX_ROWS; i++) {
            CondIn c = conds.get(i);
            FieldErrors re = FieldErrors.create();
            String title = visible(c == null ? null : c.title(), "title", 200, true, re);
            Long owner = null;
            if (c != null && !isBlank(c.ownerLoginId())) {
                String login = c.ownerLoginId().strip();
                UserOption u = users.activeByLogin(login);
                if (u == null) {
                    re.add("ownerLoginId", "NO_SUCH_CODE");
                } else {
                    owner = u.getUserId();
                }
            }
            LocalDate due = date(c == null ? null : c.dueDate(), "dueDate", false, re);
            moveRow(re, e, "k" + i);
            condRows.add(new Object[] {title, owner, due});
        }
        e.throwIfAny();
        if (gm.updateReview(reviewId, day, decision, summary, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        gm.deleteChecks(reviewId);
        int seq = 10;
        for (Object[] c : checkRows) {
            gm.insertCheck(reviewId, seq, (String) c[0], (String) c[1], (String) c[2]);
            seq += 10;
        }
        gm.deleteConds(reviewId);
        seq = 10;
        for (Object[] c : condRows) {
            try {
                gm.insertCond(reviewId, seq, (String) c[0], (Long) c[1], (LocalDate) c[2]);
            } catch (DataIntegrityViolationException ex) {
                if ("fk_pms_gate_cond_owner_comp".equals(PgErrors.constraint(ex).orElse(""))) {
                    throw fieldError("ownerLoginId", "NO_SUCH_CODE");
                }
                throw ex;
            }
            seq += 10;
        }
    }

    @Transactional
    public void cancel(long reviewId, Integer rowVer) {
        LoginUser me = me();
        lockDraft(reviewId, me);
        if (rowVer == null || gm.cancelReview(reviewId, rowVer, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    // ------------------------------------------------------------------ 상신·결정

    /**
     * 상신 검사: 결정 선택, 점검 결과 모두, GO 는 NG 없음, 조건부는 조건 1개+, 통과는 필수 산출물 준비 완료.
     * 정책 PMS_GATE_APPROVAL=Y → 결재 상신(반환 = 결재 id), N → APPROVE 권한으로 바로 결정(반환 0)
     */
    @Transactional
    public long submit(long reviewId, SubmitForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("PMS_GATE_SUBMIT");
        ReviewRow r = lockDraft(reviewId, me);
        if (in == null || in.rowVer() == null || in.rowVer() != r.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        FieldErrors e = FieldErrors.create();
        if (r.getDecision() == null) {
            e.add("decision", "REQUIRED");
        }
        if (r.getReviewDate().isAfter(clock.today())) {
            e.add("reviewDate", "FUTURE");
        }
        List<CheckRow> checks = gm.findChecks(reviewId);
        for (int i = 0; i < checks.size(); i++) {
            CheckRow c = checks.get(i);
            if (c.getResult() == null) {
                e.row("c" + i).add("result", "REQUIRED");
            } else if ("NG".equals(c.getResult()) && "GO".equals(r.getDecision())) {
                e.row("c" + i).add("result", "PMS_GO_WITH_NG");
            }
        }
        if ("CONDITIONAL".equals(r.getDecision()) && gm.findConds(reviewId).isEmpty()) {
            e.add("conds", "PMS_COND_REQUIRED");
        }
        e.throwIfAny();
        // QMS 문서에 연결된 산출물: 그사이 배포됐으면 먼저 승인으로(D15)
        docSync.sync(r.getProjId(), me.loginId());
        List<DelivRow> scope = DeliverableService.scope(tasks.placed(r.getProjId()), r.getGateTaskId(),
                gm.findDeliverables(r.getProjId()));
        int req = DeliverableService.requiredCount(scope);
        int ready = DeliverableService.readyCount(scope);
        if (!"NO_GO".equals(r.getDecision()) && ready < req) {
            throw new BusinessException(HbErrorCode.PMS_GATE_NOT_READY, req - ready);
        }
        TaskRow gate = mapper.findTask(r.getGateTaskId());
        if ("DONE".equals(gate.getStatus()) || "CANCELLED".equals(gate.getStatus())) {
            throw new BusinessException(HbErrorCode.PMS_GATE_STATE);
        }
        if (!policy.bool(Policies.PMS_GATE_APPROVAL)) {
            if (!permission.actions(PROG).contains(Action.APPROVE)) {
                throw new BusinessException(HbErrorCode.NO_PERMISSION);
            }
            decider.decide(reviewId, me.loginId());
            return 0;
        }
        List<LineSpec> lines = new ArrayList<>();
        if (in.lines() != null) {
            for (LineIn l : in.lines()) {
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
        if (gm.toApproval(reviewId, req, ready, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        ProjectRow p = project(r.getProjId());
        String title = isBlank(in.title()) ? p.getProjNo() + " " + gate.getTaskNm() + " #" + r.getReviewNo() : in.title().strip();
        long aprId = approvals.submit(GateApprovalDocType.DOC_TYPE, String.valueOf(reviewId),
                title.length() > 200 ? title.substring(0, 200) : title, lines);
        gm.setAprId(reviewId, aprId);
        return aprId;
    }

    // ------------------------------------------------------------------ 내부

    /** 행 하나를 검사한 칸 오류를 행 키를 붙여 옮긴다 */
    private static void moveRow(FieldErrors from, FieldErrors to, String rowKey) {
        from.list().forEach(x -> to.row(rowKey).add(x.field(), x.code(), x.args()));
    }

    private GateRow gateRow(TaskRow t, List<Placed<TaskRow>> placed, List<DelivRow> delivs) {
        GateRow g = new GateRow();
        g.setTaskId(t.getTaskId());
        g.setWbsNo(t.getWbsNo());
        g.setTaskNm(t.getTaskNm());
        g.setPlanStart(t.getPlanStart());
        g.setTaskStatus(t.getStatus());
        List<DelivRow> scope = DeliverableService.scope(placed, t.getTaskId(), delivs);
        g.setReqCnt(DeliverableService.requiredCount(scope));
        g.setReadyCnt(DeliverableService.readyCount(scope));
        return g;
    }

    /** 프로젝트 머리 → 심사 행 잠금, 작성 중·관리 권한 확인 */
    private ReviewRow lockDraft(long reviewId, LoginUser me) {
        ReviewRow r = gm.findReview(reviewId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        tasks.openForEdit(r.getProjId(), me, true);
        gm.lockReview(reviewId);
        r = gm.findReview(reviewId);
        if (!"DRAFT".equals(r.getStatus())) {
            throw new BusinessException(HbErrorCode.PMS_GATE_STATE);
        }
        return r;
    }

    private TaskRow gateTask(long taskId) {
        TaskRow t = mapper.findTask(taskId);
        if (t == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"GATE".equals(t.getTaskType())) {
            throw fieldError("gateTaskId", "PMS_NOT_GATE");
        }
        return t;
    }

    private ProjectRow project(long projId) {
        ProjectRow p = mapper.findProject(projId, days.lateRef());
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return p;
    }
}
