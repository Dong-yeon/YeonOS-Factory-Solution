package io.smarthappy.module.cms;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** 수집 COUNT 조회·정정 (docs/25 4.36) */
public final class EquipCountDtos {

    private EquipCountDtos() {
    }

    /**
     * 원장 한 행 — state: NORMAL(수집 그대로) / CORRECTED(역분개됨) / REVERSAL(역분개 행).
     * occurredAt = 수집 이벤트 시각(역분개 행은 원 행 시각 그대로), recordedAt = 받은·정정한 시각
     */
    @Getter
    @Setter
    public static class CountRow {
        private long countId;
        private long equipId;
        private String equipCd;
        private String equipNm;
        private String metricCd;
        private BigDecimal qty;
        private String entryMethod;
        private String collectorCd;
        private String eventId;
        private String reason;
        private OffsetDateTime occurredAt;
        private OffsetDateTime recordedAt;
        private String recordedBy;
        private Long reversalOfId;
        private Long reversedById;
        private String state;
        /** 엑셀 이름 칸(docs/25 4.40) — 내보낼 때만 채운다 */
        private String stateNm;
        private String metricNm;
    }

    /** 수집 카운트 grid 엑셀(docs/25 4.40) — grid 와 같은 조건 */
    public record CountExport(String plantCd, String equipId, String metricCd, String from, String to, List<String> sort,
            List<String> columns) {
    }

    /** 조건 칸 설비 — 사용 중지 설비도(지난 기록 조회) */
    @Getter
    @Setter
    public static class EquipOption {
        private long equipId;
        private String equipCd;
        private String equipNm;
        private String useYn;
    }

    public record CountPage(List<CountRow> list, Long total) {
    }

    public record CorrectForm(String reason) {
    }

    /** 정렬 허용 목록 — 기본 = 발생 시각 최신 */
    public enum CountSort implements io.smarthappy.platform.common.query.SortField {
        OCCURRED_AT("occurredAt"), RECORDED_AT("recordedAt"), EQUIP_CD("equipCd"), METRIC_CD("metricCd");

        private final String field;

        CountSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }
}
