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
 * 결재 문서 유형 QMS_CAPA — 8D 종결 결재 (docs/29 Q4, D7). bizKey = 시정조치 id. 승인 = 종결 + 첨부 잠금, 반려·회수 = ACTIONED(D7 다시).
 * CapaService 는 ApprovalService 를 쓰므로 이 빈은 매퍼만 쓴다(순환 방지)
 */
@Component
@RequiredArgsConstructor
public class CapaApprovalDocType implements ApprovalDocType {

    static final String DOC_TYPE = "QMS_CAPA";

    private final CapaMapper mapper;

    @Override
    public String docType() {
        return DOC_TYPE;
    }

    /** 상신은 효과 검증(VERIFIED)까지 끝난 시정조치만 — CapaService.submitClose 가 잠근 같은 트랜잭션에서 */
    @Override
    public void beforeSubmit(String bizKey, long requesterId) {
        if (!"VERIFIED".equals(mapper.capaStatus(id(bizKey)))) {
            throw new BusinessException(HbErrorCode.QMS_CAPA_STATE);
        }
    }

    @Override
    public void onFinished(String bizKey, ApprovalStatus result) {
        long id = id(bizKey);
        String by = CurrentUser.get().map(LoginUser::loginId).orElse("SYSTEM");
        if (result == ApprovalStatus.APPROVED) {
            if (mapper.close(id, "CLOSING", null, by) == 0) {
                throw new IllegalStateException("종결 결재 중이 아닌 시정조치: " + id);
            }
            mapper.lockFiles(id, by);
        } else if (mapper.setStatus(id, "CLOSING", "ACTIONED", null, by) == 0) {
            throw new IllegalStateException("종결 결재 중이 아닌 시정조치: " + id);
        }
    }

    private static long id(String bizKey) {
        try {
            return Long.parseLong(bizKey);
        } catch (NumberFormatException e) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }
}
