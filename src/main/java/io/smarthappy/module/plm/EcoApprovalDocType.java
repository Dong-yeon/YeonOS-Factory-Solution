package io.smarthappy.module.plm;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import io.smarthappy.platform.approval.ApprovalDocType;
import io.smarthappy.platform.approval.ApprovalStatus;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.esign.ESignService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

/**
 * 결재 문서 유형 PLM_ECO (docs/39 4장). 결재 엔진이 같은 트랜잭션에서 부른다 — 승인 = APPROVED, 반려·회수 = DRAFT(대상 고정 해제).
 * EcoService 는 ApprovalService 를 쓰고 ApprovalService 는 문서 유형 빈을 모으므로, 이 빈은 EcoService 에 기대지 않는다(순환 방지).
 * 전자서명(docs/39 19장·22장)은 줄마다 {@link #beforeAct} 에서 — PLM 화면이든 공통 결재함이든 같은 자리에서 검증·소비한다.
 */
@Component
@RequiredArgsConstructor
public class EcoApprovalDocType implements ApprovalDocType {

    private final PlmMapper mapper;
    private final io.smarthappy.platform.policy.PolicyService policies;
    private final ESignService esign;

    @Override
    public String docType() {
        return EcoService.DOC_TYPE;
    }

    /** 상신은 EcoService.submit 이 PENDING 으로 바꾼 같은 트랜잭션에서만 — 다른 경로로 들어온 상신은 거부 */
    @Override
    public void beforeSubmit(String bizKey, long requesterId) {
        if (!"PENDING".equals(mapper.ecoStatus(ecoId(bizKey)))) {
            throw new BusinessException(HbErrorCode.PLM_ECO_STATE);
        }
    }

    /**
     * 승인·합의 줄마다 전자서명(docs/39 22장, 결재 엔진 확장점 docs/36 18.2) — 의미: 합의 줄 REVIEW, 결재 줄 APPROVE.
     * 토큰이 있으면 지금 내용 해시로 소비(틀리면 403 ESIGN_TOKEN), 없으면 정책 Y 일 때만 403 PLM_ESIGN_REQUIRED → 그 처리 전체 롤백.
     * 반려는 서명 없음(19.3 S3 — 반려 의견이 근거). 위임 대결이면 서명자 = 실제로 누른 사람
     */
    @Override
    public void beforeAct(String bizKey, LineAct act) {
        if (!act.approve()) {
            return;
        }
        long ecoId = ecoId(bizKey);
        if (act.esignToken() == null) {
            if (EcoService.esignRequired(policies)) {
                throw new BusinessException(HbErrorCode.PLM_ESIGN_REQUIRED);
            }
            return;
        }
        java.util.List<PlmDtos.EcoRow> rows = mapper.findEcos(null, null, null, ecoId, null, 1);
        if (rows.isEmpty()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        esign.consume(session(), act.esignToken(), meaning(act.lineType()), PlmSignContent.ECO, ecoId,
                PlmSignContent.ecoHash(rows.get(0), mapper.findTargets(ecoId)));
    }

    /**
     * 결재함 화면이 열 서명 대화상자(docs/39 22.4, 결재함 docs/36 18.2b) — 정책 Y 일 때 승인·합의만(반려는 서명 없음).
     * 의미·대상은 beforeAct 와 같은 규칙. 정책 N 이면 null(결재함은 묻지 않고, 토큰 없이도 beforeAct 통과)
     */
    @Override
    public ESignSpec esignFor(String bizKey, io.smarthappy.platform.approval.ApprovalService.LineType lineType, boolean approve) {
        if (!approve || !EcoService.esignRequired(policies)) {
            return null;
        }
        long ecoId = ecoId(bizKey);
        java.util.List<PlmDtos.EcoRow> rows = mapper.findEcos(null, null, null, ecoId, null, 1);
        if (rows.isEmpty()) {
            return null;
        }
        return new ESignSpec(meaning(lineType), PlmSignContent.ECO, ecoId, rows.get(0).getEcoNo() + " " + rows.get(0).getTitle());
    }

    private static String meaning(io.smarthappy.platform.approval.ApprovalService.LineType lineType) {
        return lineType == io.smarthappy.platform.approval.ApprovalService.LineType.AGREE ? "REVIEW" : "APPROVE";
    }

    @Override
    public void onFinished(String bizKey, ApprovalStatus result) {
        long ecoId = ecoId(bizKey);
        String by = CurrentUser.get().map(LoginUser::loginId).orElse("SYSTEM");
        boolean approved = result == ApprovalStatus.APPROVED;
        if (mapper.finishApproval(ecoId, approved ? "APPROVED" : "DRAFT", by) == 0) {
            throw new IllegalStateException("결재 중이 아닌 설계 변경 지시: " + ecoId);
        }
        if (!approved) {
            mapper.clearBaseRowVer(ecoId, by);
        }
    }

    private static HttpSession session() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a ? a.getRequest().getSession(false) : null;
    }

    private static long ecoId(String bizKey) {
        try {
            return Long.parseLong(bizKey);
        } catch (NumberFormatException e) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }
}
