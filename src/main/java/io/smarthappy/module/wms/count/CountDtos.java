package io.smarthappy.module.wms.count;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** 재고 실사·기간 마감 (P4 W5, docs/30 4.5) */
public final class CountDtos {

    private CountDtos() {
    }

    // ---------------------------------------------------------------- 실사 입력

    public record CreateForm(String plantCd, String countType, Long scopeNodeId, String itemCd, String blindYn, String remark) {
    }

    public record VerForm(Integer rowVer) {
    }

    public record CancelForm(Integer rowVer, String reason) {
    }

    public record AddLineForm(Integer rowVer, Long nodeId, String itemCd, String stockStatus, String lotNo) {
    }

    @Getter
    @Setter
    public static class LotRef {
        private long lotId;
        private long itemId;
    }

    /** countedAt = 공장 현지 시각(yyyy-MM-ddTHH:mm), 비우면 지금. countedQty 빈 값 = 아직 안 셈(그대로 둠) */
    public record CountRow(Long lineId, String countedQty, String countedAt) {
    }

    public record CountsForm(Integer rowVer, List<CountRow> rows) {
    }

    public record RecountForm(Integer rowVer, List<Long> lineIds) {
    }

    // ---------------------------------------------------------------- 조회

    @Getter
    @Setter
    public static class CountHead {
        private long countId;
        private String plantCd;
        private String countNo;
        private String countType;
        private long scopeNodeId;
        private String scopePath;
        private Long itemId;
        private String itemCd;
        private String blindYn;
        private BigDecimal tolPct;
        private String status;
        private String remark;
        private OffsetDateTime startedAt;
        private OffsetDateTime postedAt;
        private String postedBy;
        private String cancelReason;
        private int rowVer;
        private OffsetDateTime createdAt;
        private String createdBy;
        private int lineCount;
        private int countedCount;
        private int diffCount;
    }

    @Getter
    @Setter
    public static class LineRow {
        private long lineId;
        private long nodeId;
        private String path;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private Long lotId;
        private String lotNo;
        private String stockStatus;
        private int recountNo;
        private BigDecimal bookQty;
        private BigDecimal countedQty;
        private OffsetDateTime countedAt;
        private String countedBy;
        /** 센 작업자(창고 PDA 배지, V9315) — 사무 입력이면 없음 */
        private String countedWorkerCd;
        private String countedWorkerNm;
        private BigDecimal diffQty;
        private Long adjTrxId;
        /** 같은 칸의 더 나중 재실사가 있으면 false(확정 대상 아님) */
        private boolean latest;
    }

    /** 화면 응답 — 블라인드 실사(세는 중)는 장부·차이를 비운다(서버가 안 보냄) */
    public record LineView(long lineId, long nodeId, String path, long itemId, String itemCd, String itemNm, String unitCd, String lotNo,
            String stockStatus,
            int recountNo, String bookQty, String countedQty, OffsetDateTime countedAt, String countedBy, String diffQty, String diffPct,
            boolean overTol, Long adjTrxId, boolean latest, String countedWorkerCd, String countedWorkerNm) {
    }

    /** 실사 줄 엑셀 한 행(엑셀 내보내기는 getter 로 읽는다) */
    @Getter
    @lombok.AllArgsConstructor
    public static class LineExport {
        private long lineId;
        private String path;
        private String itemCd;
        private String itemNm;
        private String lotNo;
        private String stockStatus;
        private int recountNo;
        private String unitCd;
        private BigDecimal bookQty;
        private BigDecimal countedQty;
        private BigDecimal diffQty;
        private String countedPerson;
    }

    public record CountView(CountHead head, boolean blindHidden, List<LineView> lines) {
    }

    // ---------------------------------------------------------------- 기간 마감

    public record PeriodForm(String plantCd, String period, Integer rowVer, String reason) {
    }

    @Getter
    @Setter
    public static class PeriodRow {
        private Long closeId;
        private String plantCd;
        private String period;
        private String status;
        private OffsetDateTime closedAt;
        private String closedBy;
        private OffsetDateTime reopenedAt;
        private String reopenedBy;
        private String reason;
        private Integer rowVer;
    }

    /** 마감 전 점검 — 경고만(마감은 막지 않는다) */
    public record PeriodCheck(String plantCd, String period, int openCounts, Integer lastMismatch, OffsetDateTime lastReconcileAt,
            boolean future) {
    }

    /** 실사 목록 그리드 정렬 허용 열(UI 단계 3 ①, 결정 docs/29 #45) — 매퍼가 enum 이름으로 열을 고르고 끝에 count_id 를 붙인다 */
    public enum CountSort implements io.smarthappy.platform.common.query.SortField {
        COUNT_NO("countNo"), COUNT_TYPE("countType"), STATUS("status"), CREATED_AT("createdAt");

        private final String field;

        CountSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 기간 마감 그리드 정렬 허용 열(docs/40 §10.2 D1, 결정 docs/29 #45) — 매퍼가 enum 이름으로 열을 고르고 끝에 월을 붙인다 */
    public enum PeriodSort implements io.smarthappy.platform.common.query.SortField {
        PERIOD("period"), STATUS("status"), CLOSED_AT("closedAt"), REOPENED_AT("reopenedAt");

        private final String field;

        PeriodSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }
}
