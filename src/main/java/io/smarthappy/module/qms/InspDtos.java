package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

/** P3 QMS 2부 Q6 검사 실행·측정값 원장 DTO (docs/29_P3 12.2) */
public final class InspDtos {

    private InspDtos() {
    }

    @Getter
    @Setter
    public static class InspRow {
        private long inspId;
        private String inspNo;
        private String inspType;
        private long lotId;
        private String lotNo;
        private String itemCd;
        private String itemNm;
        private String procCd;
        private long inspPlanId;
        private String planRev;
        private long lotQty;
        private String status;
        private String result;
        private OffsetDateTime occurredAt;
        private OffsetDateTime judgedAt;
        private String judgedBy;
        private String voidReason;
        private String remark;
        private int rowVer;
        private String createdBy;
    }

    @Getter
    @Setter
    public static class LineRow {
        private long inspLineId;
        private int lineNo;
        private long charId;
        private String charLabel;
        private String charNm;
        private String dataType;
        private BigDecimal nominal;
        private BigDecimal lsl;
        private BigDecimal usl;
        private Integer scale;
        private String unitNm;
        private String gaugeClass;
        private int sampleSize;
        private int ac;
        private int re;
        private String result;
        // 미리보기(살아 있는 값으로 계산)
        private int measured;
        private int nonconforming;
        private String verdict;
    }

    /** 살아 있는 측정값 */
    @Getter
    @Setter
    public static class MeasRow {
        private long measId;
        private long inspLineId;
        private int sampleNo;
        private BigDecimal value;
        private String okYn;
        private Long gaugeAssetId;
        private String gaugeCd;
        private OffsetDateTime occurredAt;
        private String recordedBy;
        private String entryMethod;
    }

    /** openNcrNos = 이 검사를 근거로 한 처리 중 NCR 번호(무효 전 경고용) */
    /** stockQc = 그 LOT 의 재고 상태별 수량(WMS B3 판정 확인 문구 WB-3 — 수입·출하 검사, WMS 연동 공장만, 아니면 빈 맵) */
    /** 초품·종품 나란히(docs/29_P3 27장) — 기준 검사 머리 */
    @Getter
    @Setter
    public static class OffHead {
        private long inspId;
        private String inspType;
        private long lotId;
        private long itemId;
        private Long procId;
    }

    /** 초품(FIRST_OFF)·종품(LAST_OFF) 검사 한 건 — 무효 빼고 */
    @Getter
    @Setter
    public static class OffRow {
        private long inspId;
        private String inspNo;
        private String inspType;
        private long lotId;
        private String lotNo;
        private String status;
        private String result;
        private OffsetDateTime occurredAt;
        private OffsetDateTime judgedAt;
        private String judgedBy;
    }

    /**
     * 한쪽(초품 또는 종품) — state = NONE(검사 없음)·PENDING(검사는 있으나 판정 전)·OK·NG(가장 최근 판정의 결과), rows = 최근부터
     */
    public record OffSide(String state, List<OffRow> rows) {
    }

    /**
     * 같은 지시·공정의 초품·종품 — woId 가 없으면(지시 없는 LOT·MES 없음) 두 쪽 모두 NONE.
     * basis = WO_LOTS(지시의 LOT 으로 찾음) / RECENT(LOT 을 못 받아 품목·공정 최근 검사로 찾음), truncated = 상한에 걸려 옛 짝이 빠졌을 수 있음 (27.4)
     */
    public record OffPair(Long woId, Long procId, OffSide firstOff, OffSide lastOff, String basis, boolean truncated) {
    }

    public record InspDetail(InspRow insp, List<LineRow> lines, List<MeasRow> meas, String verdict, List<String> openNcrNos,
            Map<String, BigDecimal> stockQc) {
    }

    /** 만들 때 찾은 LOT */
    @Getter
    @Setter
    public static class LotRef {
        private long lotId;
        private long itemId;
        private Long itemClassId;
        private BigDecimal qtyCreated;
        /** LOT 단위 = 품목 기본 단위(WMS 재고 수량과 같은 단위 — QC 수량을 로트 수량 기본값으로 쓸 수 있다) */
        private boolean baseUnit;
    }

    /** 계획 행 + 샘플링 계획 id (스냅샷 칸이 비면 특성 현재 값) */
    @Getter
    @Setter
    public static class PlanLine {
        private long lineId;
        private int lineNo;
        private long charId;
        private String dataType;
        private BigDecimal nominal;
        private BigDecimal lsl;
        private BigDecimal usl;
        private Integer scale;
        private String unitNm;
        private String gaugeClass;
        private long samplingPlanId;
    }

    public record LineValue(int lineNo, long planLineId, long charId, String dataType, BigDecimal nominal, BigDecimal lsl, BigDecimal usl,
            Integer scale, String unitNm, String gaugeClass, int sampleSize, int ac, int re) {
    }

    /** 게이지 후보 — 상태는 GaugeStatus, blocked = 측정에 쓸 수 없음 */
    @Getter
    @Setter
    public static class GaugeOption {
        private long assetId;
        private String assetCd;
        private String assetNm;
        private String status;
        private boolean blocked;
    }

    public record CreateForm(String lotNo, String inspType, String procCd, Long lotQty, String remark) {
    }

    public record MeasEntry(Integer sampleNo, BigDecimal value, String okYn, Long gaugeAssetId) {
    }

    public record MeasForm(Long inspLineId, List<MeasEntry> entries) {
    }

    /** occurredAt = 잰 시각(화면 = 입력 시각, 엑셀 = 파일 measuredAt 또는 실행 시각 — docs/29_P3 25장 XI-6) */
    public record MeasValue(long inspLineId, int sampleNo, BigDecimal value, String okYn, Long gaugeAssetId, OffsetDateTime occurredAt) {
    }

    /** 측정 입력 출처 (docs/29_P3 25장 XI-7) — 화면 KEY, 엑셀 IMPORT + 올리기 작업 */
    /** 측정 출처 — 화면 KEY / 엑셀 IMPORT(작업 id) / 현장 단말 KEY·SCAN(작업자·단말·요청 id, docs/29_P3 53장) */
    public record MeasSource(String entryMethod, Long importJobId, Long workerId, Long terminalId, String clientReqId) {
        public static final MeasSource KEY = new MeasSource("KEY", null);

        public MeasSource(String entryMethod, Long importJobId) {
            this(entryMethod, importJobId, null, null, null);
        }

        public static MeasSource floor(String entryMethod, long workerId, long terminalId, String clientReqId) {
            return new MeasSource(entryMethod, null, workerId, terminalId, clientReqId);
        }

        public static MeasSource importJob(long jobId) {
            return new MeasSource("IMPORT", jobId);
        }
    }

    public record VoidMeasForm(Long measId) {
    }

    /** 정정 한 번에(docs/72 3.3) — 살아 있는 측정 measId 를 무효로 막고 같은 샘플 번호로 entry 값(샘플 번호는 무시) */
    public record CorrectForm(Long measId, MeasEntry entry) {
    }

    public record ActionForm(Integer rowVer, String reason) {
    }

    /** 그리드 정렬 허용 — field = 그리드 열, 이름 = 매퍼 choose 키 */
    public enum InspSort implements io.smarthappy.platform.common.query.SortField {
        INSP_NO("inspNo"), INSP_TYPE("inspType"), LOT_NO("lotNo"), STATUS("status"), OCCURRED_AT("occurredAt");

        private final String field;

        InspSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record InspPage(List<InspRow> list, Long total) {
    }
}
