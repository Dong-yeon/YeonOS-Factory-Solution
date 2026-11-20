package io.smarthappy.module.srm.po;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.approval.ApprovalDocType;
import io.smarthappy.platform.approval.ApprovalStatus;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import lombok.RequiredArgsConstructor;

/**
 * 발주 결재 (doc_type SRM_PO, docs/31 D6). bizKey = "발주 id:개정 번호".
 * 상신 직전: 작성 중이고 개정 번호가 같아야. 끝나면(같은 트랜잭션): 승인 → APPROVED, 반려·회수 → DRAFT(다시 고쳐 상신).
 * PoService 를 주입하지 않는다 — ApprovalService 가 생성자에서 이 빈들을 모으므로 PoService → ApprovalService 와 순환이 된다.
 */
@Component
@RequiredArgsConstructor
public class PoApprovalDocType implements ApprovalDocType {

    private final PoMapper mapper;

    @Override
    public String docType() {
        return PoService.DOC_TYPE;
    }

    @Override
    public void beforeSubmit(String bizKey, long requesterId) {
        long[] k = parse(bizKey);
        PoDtos.PoRow cur = mapper.lockPo(k[0]);
        if (cur == null || !"DRAFT".equals(cur.getStatus()) || cur.getRev() != k[1]) {
            throw new BusinessException(HbErrorCode.SRM_STATE);
        }
    }

    @Override
    public void onFinished(String bizKey, ApprovalStatus result) {
        long[] k = parse(bizKey);
        PoDtos.PoRow cur = mapper.lockPo(k[0]);
        String actor = CurrentUser.get().map(u -> u.loginId()).orElse("SYSTEM");
        if (cur == null || cur.getRev() != k[1]
                || mapper.finishApproval(k[0], result == ApprovalStatus.APPROVED ? "APPROVED" : "DRAFT", actor) == 0) {
            throw new BusinessException(HbErrorCode.SRM_STATE);
        }
    }

    private static long[] parse(String bizKey) {
        String[] p = bizKey == null ? new String[0] : bizKey.split(":");
        if (p.length != 2) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        try {
            return new long[] {Long.parseLong(p[0]), Long.parseLong(p[1])};
        } catch (NumberFormatException e) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }
}
