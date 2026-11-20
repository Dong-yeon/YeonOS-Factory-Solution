package io.smarthappy.module.pms;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;

import io.smarthappy.module.pms.GateDtos.CondRow;
import io.smarthappy.module.pms.GateDtos.DelivRow;
import io.smarthappy.module.pms.GateDtos.ReviewRow;
import io.smarthappy.module.pms.PmsDtos.ProjectRow;
import io.smarthappy.module.pms.PmsDtos.TaskRow;
import io.smarthappy.module.pms.PmsDtos.TaskValue;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.numbering.NumberingService;
import lombok.RequiredArgsConstructor;

/**
 * 게이트 결정 적용 — 결재 끝(같은 트랜잭션, {@link GateApprovalDocType})과 결재 없는 바로 결정(GateService)이 같은 함수를 쓴다.
 * GO·CONDITIONAL = 필수 산출물 준비를 다시 확인 → (조건부면) 조건마다 이슈 → 게이트 작업 완료(실적일 = 심사일) → 요약 재계산.
 * NO_GO = 심사만 결정(게이트 작업 그대로 — 다음 심사를 연다).
 * ApprovalService 에 기대지 않는다(문서 유형 빈 → ApprovalService 순환 방지).
 */
@Component
@RequiredArgsConstructor
public class GateDecider {

    static final String ISSUE_RULE = "PMS_ISSUE";

    private final GateMapper gm;
    private final PmsMapper mapper;
    private final TaskService tasks;
    private final NumberingService numbering;
    private final DeliverableDocSync docSync;
    private final PmsNotifier notifier;
    private final PmsDays days;

    /**
     * 잠금 순서는 어디서나 프로젝트 머리 → 심사 행(GateService 와 같게 — 교착 방지). 잠근 뒤 다시 읽은 심사를 돌려준다
     */
    ReviewRow lock(long reviewId) {
        ReviewRow r = gm.findReview(reviewId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        mapper.lockProject(r.getProjId());
        gm.lockReview(reviewId);
        return gm.findReview(reviewId);
    }

    /** lock() 뒤에 부른다. 프로젝트가 닫혔으면 409 */
    void decide(long reviewId, String by) {
        ReviewRow r = gm.findReview(reviewId);
        String projStatus = mapper.lockProject(r.getProjId());
        if (projStatus == null || !TaskService.OPEN.contains(projStatus)) {
            throw new BusinessException(HbErrorCode.PMS_PROJECT_CLOSED);
        }
        // 결재 중에 배포된 QMS 문서 연결 산출물을 먼저 승인으로(D15)
        docSync.sync(r.getProjId(), by);
        List<DelivRow> scope = DeliverableService.scope(tasks.placed(r.getProjId()), r.getGateTaskId(),
                gm.findDeliverables(r.getProjId()));
        int req = DeliverableService.requiredCount(scope);
        int ready = DeliverableService.readyCount(scope);
        boolean pass = !"NO_GO".equals(r.getDecision());
        if (pass) {
            if (ready < req) {
                // 상신 뒤 산출물이 새 제출(승인 → 제출 중)로 바뀌었다 — 결재자는 반려하고 다시 상신
                throw new BusinessException(HbErrorCode.PMS_GATE_NOT_READY, req - ready);
            }
            TaskRow gate = mapper.findTask(r.getGateTaskId());
            if ("DONE".equals(gate.getStatus()) || "CANCELLED".equals(gate.getStatus())) {
                throw new BusinessException(HbErrorCode.PMS_GATE_STATE);
            }
            if ("CONDITIONAL".equals(r.getDecision())) {
                ensureIssueRule(mapper, by);
                for (CondRow c : gm.findConds(reviewId)) {
                    String no = numbering.next(ISSUE_RULE);
                    long issueId = gm.insertGateIssue(no, r.getProjId(), r.getGateTaskId(), c.getTitle(), c.getOwnerUserId(),
                            c.getDueDate(), reviewId, by);
                    gm.setCondIssue(c.getCondId(), issueId);
                    notifier.issueAssigned(c.getOwnerUserId(), issueId, 0, no, c.getTitle());
                }
            }
            LocalDate day = r.getReviewDate();
            LocalDate start = gate.getActStart() != null && gate.getActStart().isBefore(day) ? gate.getActStart() : day;
            TaskValue v = new TaskValue(gate.getTaskType(), gate.getTaskNm(), gate.getOwnerUserId(), gate.getPlanStart(),
                    gate.getPlanEnd(), start, day, 100, "DONE", gate.getRemark(), gate.getPlanHours());
            if (mapper.updateTask(gate.getTaskId(), v, gate.getRowVer(), by) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        }
        if (gm.decideReview(reviewId, req, ready, by) == 0) {
            throw new BusinessException(HbErrorCode.PMS_GATE_STATE);
        }
        if (pass) {
            tasks.rollup(r.getProjId(), by);
        }
        ProjectRow p = mapper.findProject(r.getProjId(), days.lateRef());
        TaskRow g = mapper.findTask(r.getGateTaskId());
        notifier.gateDecided(p.getPmUserId(), g.getOwnerUserId(), reviewId, p.getProjNo(), g.getTaskNm(), r.getDecision());
    }

    /** 채번 규칙이 없으면 기본 IS-{yyyy}-{seq:5} — 회사가 바꾼 규칙은 덮지 않는다 */
    static void ensureIssueRule(PmsMapper mapper, String by) {
        mapper.ensureNumberingRule(ISSUE_RULE, "프로젝트 이슈 번호", "IS-{yyyy}-{seq:5}", "YEAR", by);
    }
}
