package io.smarthappy.module.admin;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** 공지·자료 DTO (docs/36 11.2) */
public final class NoticeDtos {

    private NoticeDtos() {
    }

    @Getter
    @Setter
    public static class NoticeRow {
        private long noticeId;
        private String catCd;
        private String title;
        private String body;
        private String pinYn;
        private LocalDate postFrom;
        private LocalDate postTo;
        private String useYn;
        /** POSTED · SCHEDULED · ENDED · OFF (조회 날짜 기준) */
        private String stateCd;
        private int fileCnt;
        private String updatedBy;
        private OffsetDateTime updatedAt;
        private int rowVer;
    }

    /** 관리 목록 조건 */
    public record NoticeSearch(String keywordLike, String catCd, String stateCd, LocalDate today, PageQuery page) {
    }

    public record NoticeForm(String catCd, String title, String body, String pinYn, LocalDate postFrom,
            LocalDate postTo, String useYn, Integer rowVer) {
    }

    /** 읽는 사람 목록 한 줄 (본문 없음) */
    @Getter
    @Setter
    public static class NoticeItem {
        private long noticeId;
        private String catCd;
        private String title;
        private String pinYn;
        private LocalDate postFrom;
        private LocalDate postTo;
        private int fileCnt;
        private boolean unread;
    }

    public enum NoticeSort implements SortField {
        POST_FROM("postFrom"), TITLE("title"), UPDATED_AT("updatedAt");

        private final String field;

        NoticeSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }
}
