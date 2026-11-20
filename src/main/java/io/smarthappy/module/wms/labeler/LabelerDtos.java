package io.smarthappy.module.wms.labeler;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** 오토라벨러 DTO (docs/65 21장) */
public final class LabelerDtos {

    private LabelerDtos() {
    }

    // ------------------------------------------------------------------ 마스터

    @Getter
    @Setter
    public static class LabelerRow {
        private long labelerId;
        private String plantCd;
        private String labelerCd;
        private String labelerNm;
        private long terminalId;
        private String terminalCd;
        private OffsetDateTime terminalSeenAt;
        private String connMode;
        private long tplId;
        private String tplCd;
        private int deadlineMs;
        private String useYn;
        private String remark;
        private int rowVer;
        private OffsetDateTime updatedAt;
    }

    public record LabelerForm(String plantCd, String labelerCd, String labelerNm, Long terminalId, String connMode, Long tplId,
            Integer deadlineMs, String useYn, String remark, Integer rowVer) {
    }

    // ------------------------------------------------------------------ 박스

    @Getter
    @Setter
    public static class BoxRow {
        private long boxId;
        private String plantCd;
        private String boxCd;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private BigDecimal qty;
        private String lotNo;
        private String refNo;
        private String labelSkipYn;
        private String sscc;
        private String status;
        private int attemptCnt;
        private String lastReasonCd;
        private Long prefetchedBy;
        private OffsetDateTime prefetchedAt;
        private OffsetDateTime doneAt;
        private int rowVer;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
    }

    public record BoxForm(String plantCd, String boxCd, String itemCd, BigDecimal qty, String lotNo, String refNo, String labelSkipYn,
            Integer rowVer) {
    }

    public record BoxQuery(String plantCd, String status, String boxCd, LocalDate from, LocalDate to) {
    }

    @Getter
    public static class BoxSearch {
        private final String plantCd;
        private final String status;
        private final String boxLike;
        private final OffsetDateTime fromAt;
        private final OffsetDateTime toAt;
        private final PageQuery page;

        public BoxSearch(String plantCd, String status, String boxLike, OffsetDateTime fromAt, OffsetDateTime toAt, PageQuery page) {
            this.plantCd = plantCd;
            this.status = status;
            this.boxLike = boxLike;
            this.fromAt = fromAt;
            this.toAt = toAt;
            this.page = page;
        }
    }

    public enum BoxSort implements SortField {
        CREATED_AT("createdAt"), BOX_CD("boxCd"), STATUS("status");

        private final String field;

        BoxSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    @Getter
    @Setter
    public static class EventRow {
        private long eventId;
        private Long labelerId;
        private String labelerCd;
        private Long boxId;
        private String requestId;
        private String eventCd;
        private String actionCd;
        private String reasonCd;
        private String sscc;
        private String scanned;
        private Integer elapsedMs;
        private String detail;
        private String src;
        private OffsetDateTime occurredAt;
        private String recordedBy;
    }

    @Getter
    @Setter
    public static class DevLogRow {
        private long logId;
        private long labelerId;
        private String eventCd;
        private String code;
        private String detail;
        private String replyCd;
        private OffsetDateTime occurredAt;
    }

    // ------------------------------------------------------------------ 장비 API (/ext/v1/labeler)

    /**
     * 데이터 요청. ageMs = 장비가 박스를 읽은 뒤 지난 시간(장비 시계 차이를 피하려고 경과만 받는다).
     * answeredLocally = SaaS 엣지가 현장에서 이미 답한 것을 올림(action·sscc·reasonCd 를 함께) — 기한 검사 안 함
     */
    public record RequestIn(String requestId, String scanned, Integer ageMs, Boolean answeredLocally, String action, String sscc,
            String reasonCd, String occurredAt) {
    }

    /** 답 — 같은 requestId 다시 = 이 답 그대로 */
    public record Answer(String requestId, String action, String reasonCd, String boxCd, String sscc, String tplCd, String sizeCd,
            Map<String, String> values, String zpl, Integer elapsedMs, boolean replay) {

        Answer asReplay() {
            return new Answer(requestId, action, reasonCd, boxCd, sscc, tplCd, sizeCd, values, zpl, elapsedMs, true);
        }
    }

    public record ResultIn(String requestId, String phase, String scanned, String reasonCd, String detail, String occurredAt) {
    }

    public record ResultView(String requestId, String phase, String outcome, String boxStatus, String eventCd, String reasonCd) {
    }

    public record PrefetchIn(Integer max) {
    }

    public record PrefetchItem(String boxCd, String sscc, String tplCd, String sizeCd, Map<String, String> values, String zpl) {
    }

    public record ReleaseIn(List<String> boxCds) {
    }

    public record ReleaseView(int voided, List<String> unknown) {
    }

    public record DeviceEventIn(String type, String code, String detail, String occurredAt) {
    }

    public record DeviceEventView(long logId, String reply) {
    }
}
