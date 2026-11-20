package io.smarthappy.module.pms;

import java.util.Map;

import org.springframework.stereotype.Component;

import io.smarthappy.module.pms.TaskChgDtos.ChgRow;
import io.smarthappy.platform.approval.ApprovalDocType;
import io.smarthappy.platform.approval.ApprovalStatus;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 결재 문서 유형 PMS_TASK_CHG — 담당자 일정변경 요청(docs/32 4.13). 결재 엔진이 같은 트랜잭션에서 부른다:
 * 승인 = 계획일 변경 적용(TaskChgApplier — 작업이 그사이 바뀌었으면 409 로 결재 처리 전체 롤백), 반려·회수 = 요청 끝.
 * 매퍼·적용기만 쓴다(TaskChgService 는 ApprovalService 를 쓰므로 기대지 않는다)
 */
@Component
@RequiredArgsConstructor
public class TaskChgApprovalDocType implements ApprovalDocType {

    static final String DOC_TYPE = "PMS_TASK_CHG";

    private final TaskChgMapper cm;
    private final TaskChgApplier applier;

    @Override
    public String docType() {
        return DOC_TYPE;
    }

    /** 상신은 TaskChgService.request 가 결재 중 요청을 만든 같은 트랜잭션에서만 — 다른 경로의 상신은 거부 */
    @Override
    public void beforeSubmit(String bizKey, long requesterId) {
        ChgRow r = cm.findReq(reqId(bizKey));
        if (r == null || !"IN_APPROVAL".equals(r.getStatus()) || r.getAprId() != null || r.getRequesterId() != requesterId) {
            throw new BusinessException(HbErrorCode.PMS_CHG_STATE);
        }
    }

    @Override
    public void onFinished(String bizKey, ApprovalStatus result) {
        String by = CurrentUser.get().map(LoginUser::loginId).orElse("SYSTEM");
        ChgRow r = applier.lock(reqId(bizKey));
        if (!"IN_APPROVAL".equals(r.getStatus())) {
            throw new IllegalStateException("결재 중이 아닌 일정변경 요청: " + r.getReqId());
        }
        switch (result) {
            case APPROVED -> applier.approve(r, null, by);
            case REJECTED -> applier.close(r.getReqId(), "REJECTED", null, by);
            default -> applier.close(r.getReqId(), "WITHDRAWN", null, by);
        }
    }

    /** 결재함 "문서 열기" → 프로젝트 화면이 이 요청을 연다(조회 칸 chgReq) */
    @Override
    public String progCd() {
        return TaskService.PROG;
    }

    @Override
    public Map<String, String> linkParams(String bizKey) {
        // 결재함 목록 행마다 불린다 — 조회 없이 키만(없는 요청·권한은 화면이 열 때 서버가 다시 판정)
        return Map.of("chgReq", String.valueOf(reqId(bizKey)));
    }

    private static long reqId(String bizKey) {
        try {
            return Long.parseLong(bizKey);
        } catch (NumberFormatException e) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }
}
