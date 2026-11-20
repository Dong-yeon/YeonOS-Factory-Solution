package io.smarthappy.module.pms;

import java.time.LocalDate;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.smarthappy.module.pms.PmsDtos.ProjectRow;
import io.smarthappy.platform.approval.ApprovalDocType;
import io.smarthappy.platform.approval.ApprovalStatus;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 결재 문서 유형 PMS_PROJ_OPEN(착수)·PMS_PROJ_CLOSE(종료) — docs/32 4.16. bizKey = 프로젝트 id. 결재 엔진이 같은 트랜잭션에서 부른다:
 * 승인 = 상태 바꾸기(종료는 조건을 다시 본다 — P-4, 미달이면 승인 자체가 실패), 반려·회수 = 결재 걸림만 푼다(상태 그대로).
 * ProjectService 는 ApprovalService 를 쓰므로 이 빈은 매퍼만 쓴다(순환 방지)
 */
@RequiredArgsConstructor
abstract class ProjApprovalDocType implements ApprovalDocType {

    static final String OPEN = "PMS_PROJ_OPEN";
    static final String CLOSE = "PMS_PROJ_CLOSE";

    private final PmsMapper mapper;
    private final GateMapper gm;
    private final PmsDays days;
    private final BizClock clock;

    /** 이 문서 유형이 거는 목표 상태 */
    abstract String target();

    static String docTypeOf(String target) {
        return "CLOSED".equals(target) ? CLOSE : OPEN;
    }

    @Override
    public String docType() {
        return docTypeOf(target());
    }

    /** 상신은 ProjectService.submitApproval 이 목표 상태를 건 같은 트랜잭션에서만 — 다른 경로의 상신은 거부 */
    @Override
    public void beforeSubmit(String bizKey, long requesterId) {
        ProjectRow p = mapper.findProject(projId(bizKey), days.lateRef());
        if (p == null || !target().equals(p.getAprTo()) || p.getAprId() != null) {
            throw new BusinessException(HbErrorCode.PMS_PROJ_APR_STATE);
        }
    }

    @Override
    public void onFinished(String bizKey, ApprovalStatus result) {
        long id = projId(bizKey);
        String by = CurrentUser.get().map(LoginUser::loginId).orElse("SYSTEM");
        if (mapper.lockProject(id) == null) {
            throw new IllegalStateException("없는 프로젝트: " + id);
        }
        ProjectRow p = mapper.findProject(id, days.lateRef());
        if (!target().equals(p.getAprTo())) {
            throw new IllegalStateException("결재 중이 아닌 프로젝트: " + id);
        }
        boolean approved = result == ApprovalStatus.APPROVED;
        if (approved && "CLOSED".equals(target())) {
            ProjClose.check(mapper, gm, id);
        }
        String status = approved ? target() : p.getStatus();
        LocalDate closedOn = approved && "CLOSED".equals(target()) ? clock.today() : p.getClosedOn();
        if (mapper.finishProjectApproval(id, status, closedOn, by) == 0) {
            throw new IllegalStateException("결재 중이 아닌 프로젝트: " + id);
        }
    }

    /** 결재함 "문서 열기" → 프로젝트 화면 검색어 = 프로젝트 번호 */
    @Override
    public String progCd() {
        return TaskService.PROG;
    }

    @Override
    public Map<String, String> linkParams(String bizKey) {
        String no = mapper.findProjectNo(projId(bizKey));
        return no == null ? Map.of() : Map.of("keyword", no);
    }

    private static long projId(String bizKey) {
        try {
            return Long.parseLong(bizKey);
        } catch (NumberFormatException e) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }

    /** 착수 결재 */
    @Component
    static class Open extends ProjApprovalDocType {
        Open(PmsMapper mapper, GateMapper gm, PmsDays days, BizClock clock) {
            super(mapper, gm, days, clock);
        }

        @Override
        String target() {
            return "ACTIVE";
        }
    }

    /** 종료 결재 */
    @Component
    static class Close extends ProjApprovalDocType {
        Close(PmsMapper mapper, GateMapper gm, PmsDays days, BizClock clock) {
            super(mapper, gm, days, clock);
        }

        @Override
        String target() {
            return "CLOSED";
        }
    }
}
