package io.smarthappy.module.admin;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.approval.ApprovalDocType;
import io.smarthappy.platform.approval.ApprovalStatus;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * 결재 문서 유형 OUTBOX_CLOSE (docs/36 19장 O6) — 정책 OUTBOX_CLOSE_APPROVAL = Y 일 때 연계 실패 수동 종결. bizKey = outbox_id.
 * 대기 동안 행은 FAIL 그대로(close_apr_id 만) — 릴레이는 FAIL 을 집지 않는다. 승인 = CLOSED_MANUAL(종결자 = 요청자, 사유 = 요청 사유,
 * 시각 = 승인 시각), 반려·회수 = 대기 표시를 지워 다시 재시도·종결할 수 있게. OutboxAdminService 는 ApprovalService 를 쓰고
 * ApprovalService 는 문서 유형 빈을 모으므로 이 빈은 서비스에 기대지 않는다(순환 방지)
 */
@Component
@RequiredArgsConstructor
public class OutboxCloseDocType implements ApprovalDocType {

    static final String DOC_TYPE = "OUTBOX_CLOSE";

    private final OutboxMapper mapper;

    @Override
    public String docType() {
        return DOC_TYPE;
    }

    /** 상신은 FAIL·대기 없음일 때만 — OutboxAdminService.close 가 행을 잠근 같은 트랜잭션에서 */
    @Override
    public void beforeSubmit(String bizKey, long requesterId) {
        var cur = mapper.lockOne(id(bizKey));
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"FAIL".equals(cur.getStatus()) || cur.getCloseAprId() != null) {
            throw new BusinessException(HbErrorCode.OUTBOX_STATE);
        }
    }

    @Override
    public void onFinished(String bizKey, ApprovalStatus result) {
        long id = id(bizKey);
        int n = result == ApprovalStatus.APPROVED ? mapper.closeApproved(id) : mapper.clearClosePending(id);
        if (n == 0) {
            throw new IllegalStateException("종결 결재 대기가 아닌 아웃박스 행: " + id);
        }
    }

    @Override
    public String progCd() {
        return OutboxAdminService.PROG;
    }

    /** 결재함 제목 — 주제·업무 키·번호·사유 앞부분(결재자가 화면 권한이 없어도 무엇을 닫는지 알게) */
    static String title(String topic, String bizKey, long outboxId, String reason) {
        String t = topic + " " + (bizKey == null ? "" : bizKey + " ") + "#" + outboxId + " — " + reason;
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    private static long id(String bizKey) {
        try {
            return Long.parseLong(bizKey);
        } catch (NumberFormatException e) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }
}
