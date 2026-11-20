package io.smarthappy.module.pms;

import org.springframework.stereotype.Component;

import io.smarthappy.module.pms.GateDtos.ReviewRow;
import io.smarthappy.platform.approval.ApprovalDocType;
import io.smarthappy.platform.approval.ApprovalStatus;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 결재 문서 유형 PMS_GATE (docs/32 PM3). 결재 엔진이 같은 트랜잭션에서 부른다 — 승인 = 결정 적용(GateDecider),
 * 반려·회수 = 작성 중으로 되돌림(다시 고쳐 상신). GateService 는 ApprovalService 를 쓰므로 이 빈은 GateService 에 기대지 않는다.
 */
@Component
@RequiredArgsConstructor
public class GateApprovalDocType implements ApprovalDocType {

    static final String DOC_TYPE = "PMS_GATE";

    private final GateMapper gm;
    private final GateDecider decider;

    @Override
    public String docType() {
        return DOC_TYPE;
    }

    /** 상신은 GateService.submit 이 IN_APPROVAL 로 바꾼 같은 트랜잭션에서만 — 다른 경로의 상신은 거부 */
    @Override
    public void beforeSubmit(String bizKey, long requesterId) {
        ReviewRow r = gm.findReview(reviewId(bizKey));
        if (r == null || !"IN_APPROVAL".equals(r.getStatus()) || r.getAprId() != null) {
            throw new BusinessException(HbErrorCode.PMS_GATE_STATE);
        }
    }

    @Override
    public void onFinished(String bizKey, ApprovalStatus result) {
        long id = reviewId(bizKey);
        String by = CurrentUser.get().map(LoginUser::loginId).orElse("SYSTEM");
        ReviewRow r = decider.lock(id);
        if (!"IN_APPROVAL".equals(r.getStatus())) {
            throw new IllegalStateException("결재 중이 아닌 게이트 심사: " + id);
        }
        if (result == ApprovalStatus.APPROVED) {
            decider.decide(id, by);
        } else if (gm.backToDraft(id, by) == 0) {
            throw new IllegalStateException("결재 중이 아닌 게이트 심사: " + id);
        }
    }

    private static long reviewId(String bizKey) {
        try {
            return Long.parseLong(bizKey);
        } catch (NumberFormatException e) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }
}
