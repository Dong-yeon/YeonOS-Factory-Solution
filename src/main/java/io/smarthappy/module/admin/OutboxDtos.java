package io.smarthappy.module.admin;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.PageQuery;
import lombok.Getter;
import lombok.Setter;

/** 연계 현황·실패 재전송(sys-outbox, docs/36 19장) DTO */
public final class OutboxDtos {

    private OutboxDtos() {
    }

    @Getter
    @Setter
    public static class OutboxRow {
        private long outboxId;
        private OffsetDateTime createdAt;
        private String topic;
        private String bizKey;
        private String status;
        private int attempts;
        private int maxAttempts;
        private OffsetDateTime nextRetryAt;
        private OffsetDateTime sentAt;
        /** 앞 200자 — 전체는 상세 */
        private String lastError;
        private int retryCnt;
        /** 수동 종결 결재 대기 중 */
        private boolean closePending;

        /** 시도/상한 — 목록 칸 */
        public String getTries() {
            return attempts + "/" + maxAttempts;
        }
    }

    /** bizLike = 업무 키 앞부분 일치(likeEscape + %) */
    public record OutboxSearch(Instant from, Instant to, String topic, String status, String bizLike, PageQuery page) {
    }

    /** 상세 — payload 는 가린 JSON 트리(O4). 응답은 저장하지 않는다(O3, Q-O1) */
    @Getter
    @Setter
    public static class OutboxDetailRow {
        private long outboxId;
        private String messageId;
        private OffsetDateTime createdAt;
        private String createdBy;
        private String topic;
        private String bizKey;
        private String status;
        private int attempts;
        private int maxAttempts;
        private OffsetDateTime nextRetryAt;
        private OffsetDateTime sentAt;
        private String lastError;
        private String payload;
        private int retryCnt;
        private OffsetDateTime retriedAt;
        private String retriedBy;
        private OffsetDateTime closedAt;
        private String closedBy;
        private String closeReason;
        private Long closeAprId;
        /** 종결 결재 상태(apr_request.status) — 결재가 없으면 null */
        private String closeAprStatus;
    }

    public record OutboxDetail(long outboxId, String messageId, OffsetDateTime createdAt, String createdBy, String topic,
            String bizKey, String status, int attempts, int maxAttempts, OffsetDateTime nextRetryAt, OffsetDateTime sentAt,
            String lastError, Object payload, int retryCnt, OffsetDateTime retriedAt, String retriedBy, OffsetDateTime closedAt,
            String closedBy, String closeReason, Long closeAprId, String closeAprStatus, boolean closeNeedsApproval,
            String linkProg) {
    }

    /** 결재선 한 줄(정책 Y) — lineType 없으면 APPROVE */
    public record LineItem(Long userId, String lineType) {
    }

    /** 수동 종결 — 사유 필수(≤500). 정책 OUTBOX_CLOSE_APPROVAL = Y 면 결재선(lines) 필수 */
    public record CloseForm(String reason, List<LineItem> lines) {
    }

    /** 종결 결과 — closed = 바로 종결됨, 아니면 aprId = 올린 결재 */
    public record CloseResult(boolean closed, Long aprId) {
    }

    /** 홈 카드 — 주제별 FAIL 건수 */
    public record TopicCount(String topic, long cnt) {
    }

    /** 홈 카드 — 오래된 FAIL */
    public record FailTop(long outboxId, String topic, String bizKey, String lastError, OffsetDateTime createdAt) {
    }
}
