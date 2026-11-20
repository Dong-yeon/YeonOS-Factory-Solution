package io.smarthappy.module.qms;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.approval.ApprovalDocType;
import io.smarthappy.platform.approval.ApprovalStatus;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 결재 문서 유형 QMS_DOC (docs/29 Q3, D7) — bizKey = 리비전 id. 승인 = 배포(이전 배포본 폐기, 같은 트랜잭션), 반려·회수 = 작성 중으로.
 * DocService 는 ApprovalService 를 쓰고 ApprovalService 는 문서 유형 빈을 모으므로 이 빈은 DocService 에 기대지 않는다(순환 방지)
 */
@Component
@RequiredArgsConstructor
public class DocApprovalDocType implements ApprovalDocType {

    static final String DOC_TYPE = "QMS_DOC";

    private final DocMapper mapper;
    private final DocReleaser releaser;

    @Override
    public String docType() {
        return DOC_TYPE;
    }

    /** 상신은 작성 중 리비전만 — DocService.submit 이 잠근 같은 트랜잭션에서 */
    @Override
    public void beforeSubmit(String bizKey, long requesterId) {
        if (!"DRAFT".equals(mapper.revStatus(revId(bizKey)))) {
            throw new BusinessException(HbErrorCode.QMS_DOC_STATE);
        }
    }

    @Override
    public void onFinished(String bizKey, ApprovalStatus result) {
        long revId = revId(bizKey);
        String by = CurrentUser.get().map(LoginUser::loginId).orElse("SYSTEM");
        if (result == ApprovalStatus.APPROVED) {
            releaser.release(revId, by);
        } else if (mapper.backToDraft(revId, by) == 0) {
            throw new IllegalStateException("결재 중이 아닌 문서 리비전: " + revId);
        }
    }

    private static long revId(String bizKey) {
        try {
            return Long.parseLong(bizKey);
        } catch (NumberFormatException e) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }
}
