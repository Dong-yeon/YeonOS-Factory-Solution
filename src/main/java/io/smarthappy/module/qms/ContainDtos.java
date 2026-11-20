package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.module.production.link.LotHoldPort.HoldView;
import lombok.Getter;
import lombok.Setter;

/** P3 QMS 2부 Q8b 봉쇄 DTO (docs/29_P3 12.8) */
public final class ContainDtos {

    private ContainDtos() {
    }

    @Getter
    @Setter
    public static class ContainRow {
        private long containId;
        private String containNo;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private Long ncrId;
        private String ncrNo;
        private LocalDate fromDate;
        private LocalDate toDate;
        private String reason;
        private String status;
        private int lotCnt;
        private int heldCnt;
        private OffsetDateTime closedAt;
        private OffsetDateTime createdAt;
        private String createdBy;
        private int rowVer;
    }

    @Getter
    @Setter
    public static class ContainLotRow {
        private long containLotId;
        private long lotId;
        private String lotNo;
        private BigDecimal qtyCreated;
        private String unitCd;
        private OffsetDateTime occurredAt;
        private OffsetDateTime releasedAt;
        private String releasedBy;
        private String releaseReason;
    }

    /** 후보 LOT(품목 × 기간) */
    @Getter
    @Setter
    public static class LotRow {
        private long lotId;
        private String lotNo;
        private long itemId;
        private String origin;
        private BigDecimal qtyCreated;
        private String unitCd;
        private OffsetDateTime occurredAt;
    }

    @Getter
    @Setter
    public static class ItemRef {
        private long itemId;
        private String itemCd;
        private String itemNm;
    }

    @Getter
    @Setter
    public static class NcrRef {
        private long ncrId;
        private long itemId;
    }

    /** 후보 — holds = 각 LOT 의 살아 있는 보류 근거(MES 계약), truncated = 상한(500) 넘음 */
    public record Candidates(ItemRef item, List<LotRow> lots, List<HoldView> holds, boolean truncated) {
    }

    public record ContainDetail(ContainRow contain, List<ContainLotRow> lots, List<HoldView> holds) {
    }

    public record CreateForm(String itemCd, LocalDate fromDate, LocalDate toDate, String ncrNo, String reason, List<Long> lotIds) {
    }

    public record ReleaseForm(List<Long> lotIds, String reason) {
    }

    /** 그리드 정렬 허용 — field = 그리드 열, 이름 = 매퍼 choose 키 */
    public enum ContainSort implements io.smarthappy.platform.common.query.SortField {
        CONTAIN_NO("containNo"), ITEM_CD("itemCd"), FROM_DATE("fromDate"), STATUS("status"), CREATED_AT("createdAt");

        private final String field;

        ContainSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record ContainPage(List<ContainRow> list, Long total) {
    }
}
