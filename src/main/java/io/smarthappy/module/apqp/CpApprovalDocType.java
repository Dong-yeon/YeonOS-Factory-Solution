package io.smarthappy.module.apqp;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.approval.ApprovalDocType;
import io.smarthappy.platform.approval.ApprovalStatus;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 결재 문서 유형 APQP_CP (docs/41 A3-7) — bizKey = 관리계획서 리비전 id. 승인 = 확정(같은 번호의 이전 확정본 폐기, 같은 트랜잭션), 반려·회수 = 작성 중.
 * 서비스가 결재 서비스를 쓰고 결재 서비스는 문서 유형 빈을 모으므로 이 빈은 서비스에 기대지 않고 저장소만 쓴다(순환 방지)
 */
@Component
@RequiredArgsConstructor
class CpApprovalDocType implements ApprovalDocType {

    static final String DOC_TYPE = "APQP_CP";

    private final CpRepository repo;

    @Override
    public String docType() {
        return DOC_TYPE;
    }

    /** 상신은 작성 중 리비전만 — 서비스가 머리를 잠근 같은 트랜잭션에서 */
    @Override
    public void beforeSubmit(String bizKey, long requesterId) {
        if (!"DRAFT".equals(repo.lockCp(id(bizKey)))) {
            throw new BusinessException(HbErrorCode.APQP_CP_STATE);
        }
    }

    @Override
    public void onFinished(String bizKey, ApprovalStatus result) {
        long id = id(bizKey);
        String by = CurrentUser.get().map(LoginUser::loginId).orElse("SYSTEM");
        if (!"IN_APPROVAL".equals(repo.lockCp(id))) {
            throw new IllegalStateException("결재 중이 아닌 관리계획서: " + id);
        }
        if (result == ApprovalStatus.APPROVED) {
            repo.obsoleteReleased(repo.findCp(id).getCpNo(), by);
            repo.approveCp(id, by);
        } else {
            repo.draftCp(id, by);
        }
    }

    @Override
    public String progCd() {
        return CpService.PROG;
    }

    private static long id(String bizKey) {
        try {
            return Long.parseLong(bizKey);
        } catch (NumberFormatException e) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }
}
