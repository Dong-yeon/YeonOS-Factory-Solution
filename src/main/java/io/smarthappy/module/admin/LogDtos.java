package io.smarthappy.module.admin;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** 시스템관리 3단계(docs/36 12장) DTO — 접속·보안 로그, 변경 이력, 받은 의견 */
public final class LogDtos {

    private LogDtos() {
    }

    // ------------------------------------------------------------------ 접속·보안 로그

    @Getter
    @Setter
    public static class AccessRow {
        private long logId;
        private OffsetDateTime occurredAt;
        private String severity;
        private String eventCd;
        private String resultCd;
        private Long userId;
        private String loginId;
        private String progCd;
        private String progNm;
        private String actionCd;
        private String clientType;
        private String ip;
        private String detail;
        /** INTERNAL · SUPPLIER(협력사 포털, docs/42 P4). 사용자 없는 행은 null */
        private String userType;
    }

    /** events = 고른 이벤트들(권한 변경 빠른 조건이면 그 묶음), 비면 전체 */
    public record AccessSearch(Instant from, Instant to, List<String> events, String severity, String resultCd,
            String loginLike, String ipLike, String userType, String clientType, PageQuery page) {

        /** 단말 조건 없이 (clientType 추가 전 호출 모양 — 실행계획 QA 시험이 쓴다) */
        public AccessSearch(Instant from, Instant to, List<String> events, String severity, String resultCd,
                String loginLike, String ipLike, String userType, PageQuery page) {
            this(from, to, events, severity, resultCd, loginLike, ipLike, userType, null, page);
        }
    }

    // ------------------------------------------------------------------ 변경 이력

    @Getter
    @Setter
    public static class AuditRow {
        private long auditId;
        private OffsetDateTime occurredAt;
        private String tableNm;
        private String tableProg;
        private String tableProgNm;
        private String rowKey;
        private String action;
        private String actor;
    }

    /** tables = 볼 수 있는 표(관리자면 null = 전부) ∩ 고른 표 */
    public record AuditSearch(Instant from, Instant to, List<String> tables, String action, String actorLike,
            PageQuery page) {
    }

    public record AuditChange(String column, String before, String after) {
    }

    public record AuditDetail(long auditId, OffsetDateTime occurredAt, String tableNm, String tableProgNm, String rowKey,
            String action, String actor, List<AuditChange> changes) {
    }

    /** 표 선택지 (볼 수 있는 표만) */
    public record AuditTable(String tableNm, String progCd, String progNm) {
    }

    // ------------------------------------------------------------------ 받은 의견

    @Getter
    @Setter
    public static class FeedbackRow {
        private long feedbackId;
        private OffsetDateTime createdAt;
        private String progCd;
        private String progNm;
        private String content;
        private String status;
        private String clientType;
        private long userId;
        private String loginId;
        private String userNm;
        private String handledBy;
        private OffsetDateTime handledAt;
        private int rowVer;
    }

    public record FeedbackSearch(Instant from, Instant to, String status, String progCd, PageQuery page) {
    }

    public record FeedbackStatusForm(String status, Integer rowVer) {
    }

    /** 로그·감사·의견 목록은 최신순 하나 — 시각 정렬만 허용(행이 많아 다른 정렬은 인덱스가 없다) */
    public enum TimeSort implements SortField {
        OCCURRED_AT("occurredAt"), CREATED_AT("createdAt");

        private final String field;

        TimeSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }
}
