package io.smarthappy.support;

import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.smarthappy.platform.approval.ApprovalDocType;
import io.smarthappy.platform.approval.ApprovalService;
import io.smarthappy.platform.approval.ApprovalStatus;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.OutboxHandler;
import io.smarthappy.platform.messaging.OutboxMessage;
import io.smarthappy.platform.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/** 테스트 전용 결재 문서 유형·아웃박스 송신기 */
public final class TestMessaging {

    private TestMessaging() {
    }

    /** 송신 기록: 메시지 + 송신 순간 트랜잭션·회사 범위가 열려 있었는지 */
    public record Call(OutboxMessage message, boolean inTransaction, Optional<String> scope) {
    }

    /** 토픽 TEST_ECHO — 동작을 테스트가 바꾼다 */
    @Component
    public static class EchoHandler implements OutboxHandler {
        public static final String TOPIC = "TEST_ECHO";
        public static final Queue<Call> CALLS = new ConcurrentLinkedQueue<>();
        public static volatile Function<OutboxMessage, SendResult> behavior = m -> SendResult.sent();

        public static void reset() {
            CALLS.clear();
            behavior = m -> SendResult.sent();
        }

        @Override
        public String topic() {
            return TOPIC;
        }

        @Override
        public SendResult send(OutboxMessage m) {
            CALLS.add(new Call(m, TransactionSynchronizationManager.isActualTransactionActive(),
                    TenantContext.currentCompCd()));
            return behavior.apply(m);
        }
    }

    /** 토픽 TEST_ALERT — 읽기 타임아웃을 재시도하지 않는 채널(메일·알림 흉내) */
    @Component
    public static class TimeoutHandler implements OutboxHandler {
        public static final String TOPIC = "TEST_ALERT";
        public static final AtomicInteger CALLS = new AtomicInteger();

        @Override
        public String topic() {
            return TOPIC;
        }

        @Override
        public SendResult send(OutboxMessage m) throws Exception {
            CALLS.incrementAndGet();
            throw new SocketTimeoutException("Read timed out");
        }

        @Override
        public boolean retryOnReadTimeout() {
            return false;
        }
    }

    /**
     * 결재 문서 유형 TEST_DOC — 업무 키 = 권한그룹 코드. 끝나면 권한그룹 사용 여부를 바꾼다(승인 → Y, 그 밖 → N).
     * BLOCKED 는 상신 전 검증 거부, EXPLODE 는 끝날 때 예외(결재 전체 롤백 확인용),
     * NOACT 로 시작하면 줄 처리 직전(beforeAct) 거부 — NOACT_AGREE 는 합의 줄만, SIGN 은 서명 토큰 없으면 403 ESIGN_TOKEN.
     * 줄 처리는 ACTS 에 쌓인다
     */
    @Component
    @RequiredArgsConstructor
    public static class TestDocType implements ApprovalDocType {
        public static final String DOC_TYPE = "TEST_DOC";
        public static final AtomicInteger FINISHED = new AtomicInteger();
        /** beforeAct 로 들어온 줄 처리(업무 키, 처리) — 시험이 비우고 본다 */
        public static final Queue<Map.Entry<String, LineAct>> ACTS = new ConcurrentLinkedQueue<>();

        private final JdbcTemplate jdbc;

        @Override
        public String docType() {
            return DOC_TYPE;
        }

        @Override
        public void beforeSubmit(String bizKey, long requesterId) {
            if ("BLOCKED".equals(bizKey)) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
        }

        @Override
        public void beforeAct(String bizKey, LineAct act) {
            ACTS.add(Map.entry(bizKey, act));
            if ("NOACT".equals(bizKey) || ("NOACT_AGREE".equals(bizKey) && act.lineType() == ApprovalService.LineType.AGREE)) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            if ("SIGN".equals(bizKey) && act.approve() && act.esignToken() == null) {
                throw new BusinessException(HbErrorCode.ESIGN_TOKEN);
            }
        }

        /** SIGN 문서의 승인만 서명(합의 줄은 REVIEW) — 반려는 서명 없음 */
        @Override
        public ESignSpec esignFor(String bizKey, ApprovalService.LineType lineType, boolean approve) {
            if (!"SIGN".equals(bizKey) || !approve) {
                return null;
            }
            return new ESignSpec(lineType == ApprovalService.LineType.AGREE ? "REVIEW" : "APPROVE", "test_doc", 1L, "권한그룹 " + bizKey);
        }

        @Override
        public void onFinished(String bizKey, ApprovalStatus result) {
            if ("EXPLODE".equals(bizKey)) {
                throw new IllegalStateException("업무 상태 변경 실패(테스트)");
            }
            FINISHED.incrementAndGet();
            jdbc.update("update sys_auth_group set use_yn = ? where auth_grp_cd = ?",
                    result == ApprovalStatus.APPROVED ? "Y" : "N", bizKey);
        }
    }
}
