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
 * 결재 문서 유형 APQP_FMEA (docs/41 A3-7) — bizKey = PFMEA 리비전 id. 승인 = 확정(같은 번호의 이전 확정본 폐기, 같은 트랜잭션), 반려·회수 = 작성 중.
 * 서비스가 결재 서비스를 쓰고 결재 서비스는 문서 유형 빈을 모으므로 이 빈은 서비스에 기대지 않고 저장소만 쓴다(순환 방지)
 */
@Component
@RequiredArgsConstructor
class FmeaApprovalDocType implements ApprovalDocType {

    static final String DOC_TYPE = "APQP_FMEA";
    /** DFMEA(A5-2) — 같은 흐름, 결재함 링크·권한 화면만 apqp-dfmea */
    static final String DFMEA_DOC_TYPE = "APQP_DFMEA";

    private final ApqpRepository repo;

    static String docTypeOf(String fmeaType) {
        return FmeaService.DFMEA.equals(fmeaType) ? DFMEA_DOC_TYPE : DOC_TYPE;
    }

    /** 기록 유형 — 이 결재 문서 유형이 다루는 FMEA */
    String fmeaType() {
        return FmeaService.PFMEA;
    }

    @Override
    public String docType() {
        return docTypeOf(fmeaType());
    }

    /** 상신은 작성 중 리비전만 — 서비스가 머리를 잠근 같은 트랜잭션에서 */
    @Override
    public void beforeSubmit(String bizKey, long requesterId) {
        long id = id(bizKey);
        if (!"DRAFT".equals(repo.lockFmea(id)) || !fmeaType().equals(repo.fmeaType(id))) {
            throw new BusinessException(HbErrorCode.APQP_FMEA_STATE);
        }
    }

    @Override
    public void onFinished(String bizKey, ApprovalStatus result) {
        long id = id(bizKey);
        String by = CurrentUser.get().map(LoginUser::loginId).orElse("SYSTEM");
        if (!"IN_APPROVAL".equals(repo.lockFmea(id))) {
            throw new IllegalStateException("결재 중이 아닌 FMEA: " + id);
        }
        if (result == ApprovalStatus.APPROVED) {
            repo.obsoleteReleased(repo.findFmea(id).getFmeaNo(), by);
            repo.approveFmea(id, by);
        } else {
            repo.draftFmea(id, by);
        }
    }

    @Override
    public String progCd() {
        return FmeaService.prog(fmeaType());
    }

    /** DFMEA 결재 문서 유형 */
    @Component
    static class Dfmea extends FmeaApprovalDocType {

        Dfmea(ApqpRepository repo) {
            super(repo);
        }

        @Override
        String fmeaType() {
            return FmeaService.DFMEA;
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
