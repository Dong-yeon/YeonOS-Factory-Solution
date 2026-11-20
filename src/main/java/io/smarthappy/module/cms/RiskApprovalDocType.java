package io.smarthappy.module.cms;

import org.springframework.stereotype.Component;

import io.smarthappy.module.cms.CmsRiskDtos.RaRow;
import io.smarthappy.platform.approval.ApprovalDocType;
import io.smarthappy.platform.approval.ApprovalStatus;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 결재 문서 유형 CMS_RA (docs/33 4.7, 정책 CMS_RA_APPROVAL = Y). 결재 엔진이 같은 트랜잭션에서 부른다 —
 * 승인 = 확정(RiskReleaser), 반려·회수 = 작성 중으로 되돌림(다시 고쳐 상신).
 */
@Component
@RequiredArgsConstructor
public class RiskApprovalDocType implements ApprovalDocType {

    static final String DOC_TYPE = "CMS_RA";

    private final CmsRiskMapper mapper;
    private final BizClock clock;
    private final RiskReleaser releaser;

    @Override
    public String docType() {
        return DOC_TYPE;
    }

    /** 상신은 RiskService.release 가 IN_APPROVAL 로 바꾼 같은 트랜잭션에서만 — 다른 경로의 상신은 거부 */
    @Override
    public void beforeSubmit(String bizKey, long requesterId) {
        RaRow r = mapper.findRa(raId(bizKey), clock.today());
        if (r == null || !"IN_APPROVAL".equals(r.getStatus()) || r.getAprId() != null) {
            throw new BusinessException(HbErrorCode.CMS_RA_STATE);
        }
    }

    @Override
    public void onFinished(String bizKey, ApprovalStatus result) {
        long id = raId(bizKey);
        String by = CurrentUser.get().map(LoginUser::loginId).orElse("SYSTEM");
        if (mapper.lockRa(id) == null) {
            throw new IllegalStateException("위험성평가 없음: " + id);
        }
        if (result == ApprovalStatus.APPROVED) {
            releaser.release(id, "IN_APPROVAL", null, by);
        } else if (mapper.setStatus(id, "DRAFT", null, false, null, by) == 0) {
            throw new IllegalStateException("결재 중이 아닌 위험성평가: " + id);
        }
    }

    private static long raId(String bizKey) {
        try {
            return Long.parseLong(bizKey);
        } catch (NumberFormatException e) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }
}
