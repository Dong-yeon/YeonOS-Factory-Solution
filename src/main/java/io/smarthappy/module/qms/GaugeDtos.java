package io.smarthappy.module.qms;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** P3 QMS Q2a 계측기 교정 DTO (docs/29) */
public final class GaugeDtos {

    private GaugeDtos() {
    }

    /** 계측기(물리 자산 GAUGE) + 최근 유효 교정 + 판정 */
    @Getter
    @Setter
    public static class GaugeRow {
        private long assetId;
        private String assetCd;
        private String assetNm;
        private String serialNo;
        private String gaugeClass;
        private Integer calibCycleDays;
        private LocalDate lastCalibDate;
        private String useYn;
        private int rowVer;
        private Long lastCalibId;
        private String lastResult;
        private String lastLimitNote;
        private LocalDate nextDueDate;
        private int calibCnt;
        /** GaugeStatus 와 같은 규칙(목록 SQL). 상세는 서비스가 GaugeStatus 로 다시 계산 */
        private String status;
        private Long daysLeft;
        private OffsetDateTime updatedAt;
    }

    @Getter
    @Setter
    public static class CalibRow {
        private long calibId;
        private long assetId;
        private LocalDate calibDate;
        private String result;
        private String limitNote;
        private String agency;
        private Long custId;
        private String custCd;
        private String custNm;
        private String certNo;
        private int cycleDays;
        private LocalDate nextDueDate;
        private String remark;
        private String voidYn;
        private String voidReason;
        private String voidedBy;
        private OffsetDateTime voidedAt;
        private OffsetDateTime createdAt;
        private String createdBy;
        private int fileCnt;
    }

    /** 상세 — esignRequired = 교정 등록에 서명 필수(정책), signs = 교정 행별 서명(그 행 정규 글자 해시와 같은 것, docs/29_P3 12.10) */
    /** 어느 날 기준 판정 재료(QA I-1) — 사용 여부(지금 값) + 그날까지의 마지막 유효 교정 */
    @Getter
    @Setter
    public static class GaugeAsOf {
        private String useYn;
        private String result;
        private LocalDate nextDueDate;
    }

    public record GaugeDetail(GaugeRow gauge, List<CalibRow> history, int dueSoonDays, boolean esignRequired, List<CalibSign> signs) {
    }

    public record CalibSign(long calibId, QmsESign.SignView sign) {
    }

    /** 교정 NG 영향 — 그 게이지로 잰 LOT·검사 한 줄 (2부 Q9, docs/29_P3 12.5) */
    @Getter
    @Setter
    public static class ImpactRow {
        private long lotId;
        private String lotNo;
        private String itemCd;
        private String itemNm;
        private long inspId;
        private String inspNo;
        private String inspType;
        private String inspStatus;
        private String inspResult;
        private int measCnt;
        private OffsetDateTime firstAt;
        private OffsetDateTime lastAt;
        /** LOT 발생 시각 — 봉쇄 후보(품목 × LOT 발생 기간)로 넘길 때 */
        private OffsetDateTime lotOccurredAt;
        /** LOT 발생일(회사 시간대) — 서비스가 채운다 */
        private LocalDate lotDate;
    }

    /**
     * @param fromDate 직전 유효 교정(OK·제한)일 — 없으면 null(처음부터)
     * @param ngDate   NG 교정일(그날 끝까지 포함)
     */
    public record ImpactResult(long calibId, LocalDate fromDate, LocalDate ngDate, List<ImpactRow> rows, int lotCnt, boolean truncated) {
    }

    /** esignToken = 전자서명 토큰(대상 = 계측기, 정책 Y 면 필수) */
    public record CalibForm(String calibDate, String result, String limitNote, String agency, String custCd, String certNo,
            Integer cycleDays, String remark, String esignToken) {
    }

    public record CalibValue(long assetId, LocalDate calibDate, String result, String limitNote, String agency, Long custId,
            String certNo, int cycleDays, String remark) {
    }

    public record VoidRequest(String reason) {
    }

    public record ProfileForm(String gaugeClass, Integer calibCycleDays, Integer rowVer) {
    }

    @Getter
    public static class GaugeSearch {
        private final String keywordLike;
        private final String gaugeClass;
        private final String status;
        private final boolean withUnused;
        private final LocalDate today;
        private final int dueSoonDays;
        private final PageQuery page;

        public GaugeSearch(String keywordLike, String gaugeClass, String status, boolean withUnused, LocalDate today,
                int dueSoonDays, PageQuery page) {
            this.keywordLike = keywordLike;
            this.gaugeClass = gaugeClass;
            this.status = status;
            this.withUnused = withUnused;
            this.today = today;
            this.dueSoonDays = dueSoonDays;
            this.page = page;
        }
    }

    public enum GaugeSort implements SortField {
        ASSET_CD("assetCd"), ASSET_NM("assetNm"), GAUGE_CLASS("gaugeClass"), NEXT_DUE_DATE("nextDueDate"),
        LAST_CALIB_DATE("lastCalibDate");

        private final String field;

        GaugeSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record GaugeExportRequest(String keyword, String gaugeClass, String status, Boolean withUnused, List<String> sort,
            List<String> columns) {
    }
}
