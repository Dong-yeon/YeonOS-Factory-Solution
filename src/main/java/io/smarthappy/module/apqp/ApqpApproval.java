package io.smarthappy.module.apqp;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import io.smarthappy.module.apqp.ApqpDtos.AprLine;
import io.smarthappy.platform.approval.ApprovalService;
import io.smarthappy.platform.approval.ApprovalService.LineSpec;
import io.smarthappy.platform.approval.ApprovalService.LineType;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.PolicyService;
import lombok.RequiredArgsConstructor;

/**
 * PFMEA·관리계획서 확정의 결재(docs/41 A3-7, L6) — 정책 APQP_RELEASE_APPROVAL(기본 N). Y 면 확정 대신 상신(승인 = 확정, 반려·회수 = 작성 중),
 * N 이면 지금처럼 APPROVE 권한자가 바로 확정. 결재선 검증은 결재 엔진이 한다
 */
@Component
@RequiredArgsConstructor
class ApqpApproval {

    static final String POLICY = "APQP_RELEASE_APPROVAL";

    private final PolicyService policies;
    private final ApprovalService approvals;

    boolean on() {
        return policies.bool(POLICY);
    }

    /** 바로 확정 — 정책 N 일 때만 */
    void requireDirect() {
        if (on()) {
            throw new BusinessException(HbErrorCode.APQP_APPROVAL_POLICY);
        }
    }

    /** 상신 — 정책 Y 일 때만. 반환 = 결재 요청 id */
    long submit(String docType, long bizId, String title, List<AprLine> in) {
        if (!on()) {
            throw new BusinessException(HbErrorCode.APQP_APPROVAL_POLICY);
        }
        List<LineSpec> lines = new ArrayList<>();
        for (AprLine l : in == null ? List.<AprLine>of() : in) {
            LineType type;
            try {
                type = l == null || l.userId() == null ? null : LineType.valueOf(l.lineType() == null ? "APPROVE" : l.lineType());
            } catch (IllegalArgumentException ex) {
                type = null;
            }
            if (type == null) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            lines.add(new LineSpec(l.userId(), type));
        }
        String t = title.strip();
        return approvals.submit(docType, String.valueOf(bizId), t.length() > 200 ? t.substring(0, 200) : t, lines);
    }
}
