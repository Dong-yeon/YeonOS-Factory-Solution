package io.smarthappy.module.cms;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** CMS 1부 C3 DTO — 사용량 카운터·읽기 (docs/33 4.4). 수치는 응답에서 글자(카운터 자릿수로) */
public final class CmsCounterDtos {

    private CmsCounterDtos() {
    }

    @Getter
    @Setter
    public static class CounterRow {
        private long counterId;
        private String counterCd;
        private String counterNm;
        private String targetType;
        private Long equipId;
        private Long assetId;
        private String targetCd;
        private String targetNm;
        private String plantCd;
        private String timeZone;
        private String counterKind;
        private String unitNm;
        private int scale;
        private String useYn;
        private String remark;
        /** 누적 = Σ 증가량(무효 제외) */
        private BigDecimal total;
        /** 마지막 계기 값(무효 제외) — 다음 입력의 증가량 기준 */
        private BigDecimal lastGauge;
        private OffsetDateTime lastReadAt;
        /** 마지막 유효 읽기 — 화면이 본 것과 다르면 입력이 409 (그 사이 다른 사람이 넣음) */
        private Long lastReadingId;
        private int readingCnt;
        private int planCnt;
        /** 금형·지그 수명 타수(P1) 대비 */
        private Long lifeShots;
        private String life;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;

        public String getTotalText() {
            return CmsPmDtos.text(total, scale);
        }

        public String getLastGaugeText() {
            return CmsPmDtos.text(lastGauge, scale);
        }

        /** 엑셀(C21)만 — 수명 이름(사용자 언어) */
        private String lifeNm;

        public String getLastReadAtText() {
            return CmsSupport.hm(lastReadAt);
        }
    }

    public record CounterForm(String counterCd, String counterNm, String targetType, String targetCd, String counterKind, String unitNm,
            Integer scale, String useYn, String remark, Integer rowVer) {
    }

    @Getter
    @Setter
    public static class ReadingRow {
        private long readingId;
        private OffsetDateTime readAt;
        private BigDecimal gaugeValue;
        private BigDecimal deltaQty;
        /** 이 행까지 누적(무효 행은 비움) */
        private BigDecimal cumulative;
        private String resetYn;
        private String source;
        private String remark;
        private OffsetDateTime recordedAt;
        private String recordedBy;
        private String voidYn;
        private String voidReason;
        private String voidedBy;
        private OffsetDateTime voidedAt;
        private int scale;

        public String getGaugeText() {
            return CmsPmDtos.text(gaugeValue, scale);
        }

        public String getDeltaText() {
            return CmsPmDtos.text(deltaQty, scale);
        }

        public String getCumulativeText() {
            return CmsPmDtos.text(cumulative, scale);
        }
    }

    /**
     * 읽기 입력 — 계기 값(gaugeValue) 또는 증가량(deltaQty) 중 하나. 읽은 시각은 대상 공장 현지 "yyyy-MM-ddTHH:mm"(비우면 지금).
     * lastReadingId = 화면이 본 마지막 유효 읽기(없으면 0) — 다르면 409
     */
    public record ReadingForm(String readAt, String gaugeValue, String deltaQty, String resetYn, String remark, Long lastReadingId) {
    }

    public record VoidForm(String reason) {
    }

    /** 입력 결과: 증가량·새 누적·이 입력으로 만든 예방점검 예정 수 */
    public record ReadingResult(long readingId, String deltaText, String totalText, int runs, int workOrders) {
    }

    /** 카운터 grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum CounterSort implements io.smarthappy.platform.common.query.SortField {
        COUNTER_CD("counterCd"), COUNTER_NM("counterNm"), TARGET_CD("targetCd"), COUNTER_KIND("counterKind"), LAST_READ_AT("lastReadAt"), USE_YN("useYn");

        private final String field;

        CounterSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record CounterPage(List<CounterRow> list, Long total) {
    }

    /** 카운터 엑셀(C21) — grid 조건 + 정렬 + 보이는 열 */
    public record CounterExport(String keyword, String useYn, String targetType, List<String> sort, List<String> columns) {
    }
}
