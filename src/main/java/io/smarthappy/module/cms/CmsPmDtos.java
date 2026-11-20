package io.smarthappy.module.cms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** CMS 1부 C2 DTO — 점검표·예방점검 계획·점검 실행 (docs/33 4.3) */
public final class CmsPmDtos {

    private CmsPmDtos() {
    }

    /** 수치 → 글자, 항목 자릿수로(4.0 이 4 로 보이지 않게). 자릿수보다 긴 값(직접 SQL 등)은 그대로 */
    static String text(BigDecimal v, int scale) {
        if (v == null) {
            return null;
        }
        BigDecimal t = v.stripTrailingZeros();
        return t.scale() > scale ? t.toPlainString() : v.setScale(scale, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    // ------------------------------------------------------------------ 점검표

    @Getter
    @Setter
    public static class TmplRow {
        /** 엑셀 대상 종류 칸(C23) — 화면과 같은 이름, 서비스가 채운다 */
        private String targetKindNm;
        private long tmplId;
        private String tmplCd;
        private String tmplNm;
        private String targetKind;
        private Long equipClassId;
        private String equipClassCd;
        private String equipClassNm;
        private String useYn;
        private String remark;
        private int itemCnt;
        private int planCnt;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    public record TmplForm(String tmplCd, String tmplNm, String targetKind, String equipClassCd, String useYn, String remark,
            Integer rowVer) {
    }

    public record TmplValue(String tmplCd, String tmplNm, String targetKind, Long equipClassId, String useYn, String remark,
            Integer rowVer) {
    }

    /** 수치는 응답에서 글자(BigDecimal 을 Double 로 보내지 않는다) */
    @Getter
    @Setter
    public static class ItemRow {
        private long itemId;
        private long tmplId;
        private int seq;
        private String itemNm;
        private String methodText;
        private String judgeType;
        private BigDecimal lsl;
        private BigDecimal usl;
        private String unitNm;
        private int scale;
        private String requiredYn;

        public String getLslText() {
            return CmsPmDtos.text(lsl, scale);
        }

        public String getUslText() {
            return CmsPmDtos.text(usl, scale);
        }
    }

    /** 한 벌 저장의 행 — itemId 없으면 새 행. 수치는 글자 */
    public record ItemForm(Long itemId, Integer seq, String itemNm, String methodText, String judgeType, String lsl, String usl,
            String unitNm, Integer scale, String requiredYn) {
    }

    public record ItemValue(Long itemId, int seq, String itemNm, String methodText, String judgeType, BigDecimal lsl,
            BigDecimal usl, String unitNm, int scale, String requiredYn) {
    }

    // ------------------------------------------------------------------ 계획

    @Getter
    @Setter
    public static class PlanRow {
        private long planId;
        private String planCd;
        private String planNm;
        private String targetType;
        private Long equipId;
        private Long assetId;
        private String targetCd;
        private String targetNm;
        private String plantCd;
        private long tmplId;
        private String tmplCd;
        private String tmplNm;
        private String execMode;
        private String cycleType;
        private String calUnit;
        private Integer calInterval;
        private String scheduleBasis;
        private LocalDate anchorDate;
        private Integer leadDays;
        private Long assigneeId;
        private String assigneeLoginId;
        private String assigneeNm;
        private Integer estMinutes;
        private LocalDate nextDueDate;
        private LocalDate lastDoneDate;
        /** 사용량 주기(C3): 카운터·간격·선행·첫 예정·다음 예정·마지막 완료 누적, 카운터의 지금 누적 */
        private Long counterId;
        private String counterCd;
        private String counterNm;
        private String counterUnit;
        private int counterScale;
        private BigDecimal counterTotal;
        private BigDecimal usageInterval;
        private BigDecimal usageLead;
        private BigDecimal anchorUsage;
        private BigDecimal nextDueUsage;
        private BigDecimal lastDoneUsage;
        private String useYn;
        private String remark;
        /** 열린 예정(점검 실행) — 있으면 그 예정이 끝나야 다음 */
        private Long openRunId;
        /** 계산: UPCOMING/DUE/OVERDUE, 실제 선행일 */
        private String state;
        /** 엑셀(C16)만 — 예정 상태 이름(사용자 언어) */
        private String stateNm;
        private int effLeadDays;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;

        public boolean isUsage() {
            return "USAGE".equals(cycleType);
        }

        public String getCounterTotalText() {
            return CmsPmDtos.text(counterTotal, counterScale);
        }

        public String getUsageIntervalText() {
            return CmsPmDtos.text(usageInterval, counterScale);
        }

        public String getUsageLeadText() {
            return CmsPmDtos.text(usageLead, counterScale);
        }

        public String getAnchorUsageText() {
            return CmsPmDtos.text(anchorUsage, counterScale);
        }

        public String getNextDueUsageText() {
            return CmsPmDtos.text(nextDueUsage, counterScale);
        }

        /** grid 다음 예정 한 칸 — 달력 = 날짜, 사용량 = 예정 누적(화면이 단위·지금 누적을 붙인다). 정렬 키는 next_due_date */
        public String getNextDueText() {
            return isUsage() ? getNextDueUsageText() : nextDueDate == null ? null : nextDueDate.toString();
        }

        public String getLastDoneUsageText() {
            return CmsPmDtos.text(lastDoneUsage, counterScale);
        }
    }

    /** 사용량 칸(cycleType = USAGE): 카운터 코드·간격·선행·첫 예정 사용량 — 수치는 글자 */
    public record PlanForm(String planCd, String planNm, String targetType, String targetCd, String tmplCd, String execMode,
            String calUnit, Integer calInterval, String scheduleBasis, String anchorDate, Integer leadDays,
            String assigneeLoginId, Integer estMinutes, String useYn, String remark, Integer rowVer,
            String cycleType, String counterCd, String usageInterval, String usageLead, String anchorUsage) {
    }

    /** 미리보기: 규칙만으로(저장 전에도). 사용량 주기는 카운터 자릿수로 간격·첫 예정 사용량 */
    public record PreviewForm(String scheduleBasis, String calUnit, Integer calInterval, String anchorDate, String from,
            String cycleType, String counterCd, String usageInterval, String anchorUsage, String fromUsage) {
    }

    public record BulkForm(String classCd, String tmplCd, String execMode, String calUnit, Integer calInterval,
            String scheduleBasis, String anchorDate, Integer leadDays, String assigneeLoginId) {
    }

    public record BulkResult(int created, int skipped, List<String> planCds) {
    }

    public record GenResult(int runs, int workOrders) {
    }

    // ------------------------------------------------------------------ 점검 실행

    @Getter
    @Setter
    public static class RunRow {
        private long runId;
        private long planId;
        private String planCd;
        private String planNm;
        private String execMode;
        private Long mwoId;
        private String mwoNo;
        private String mwoStatus;
        private String targetType;
        private Long equipId;
        private Long assetId;
        private String targetCd;
        private String targetNm;
        private Long targetClassId;
        private String plantCd;
        private String timeZone;
        private long tmplId;
        private String tmplCd;
        private String tmplNm;
        private LocalDate dueDate;
        /** 사용량 주기: 예정 사용량·완료 때 누적·카운터 지금 누적 */
        private String cycleType;
        private BigDecimal dueUsage;
        private BigDecimal doneUsage;
        private BigDecimal counterTotal;
        private String counterUnit;
        private int counterScale;
        private BigDecimal usageLead;
        private String status;
        private OffsetDateTime performedAt;
        private String performedLoginId;
        private String performedNm;
        private String result;
        /** 건너뛰기 사유 코드(CMS_SKIP_REASON, docs/68 Q1) — 비면 미분류 */
        private String skipReasonCd;
        private String skipReason;
        private Integer leadDays;
        /** 계산: OPEN 이면 UPCOMING/DUE/OVERDUE, DONE 이면 기한 준수 여부 */
        private String state;
        private Boolean onTime;
        /** 엑셀(C21)만 — 상태 이름(사용자 언어) */
        private String stateNm;
        private int ngCnt;
        /** 점검 항목 수(현장 단말 목록) */
        private int itemCnt;
        /** 현장 단말 요청 id(docs/25 4.58) — 단말에서 끝낸 실행만 */
        private String floorReqId;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
        private List<ResultRow> results;

        public String getDueUsageText() {
            return CmsPmDtos.text(dueUsage, counterScale);
        }

        public String getDoneUsageText() {
            return CmsPmDtos.text(doneUsage, counterScale);
        }

        public String getCounterTotalText() {
            return CmsPmDtos.text(counterTotal, counterScale);
        }

        /** 엑셀(C21) — 실시 시각을 공장 현지 글자로(조회가 이미 공장 시간대로 바꿔 둔 값) */
        public String getPerformedAtText() {
            return performedAt == null ? null : performedAt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
    }

    @Getter
    @Setter
    public static class ResultRow {
        private long resultId;
        private int seq;
        private String itemNm;
        private String methodText;
        private String judgeType;
        private BigDecimal lsl;
        private BigDecimal usl;
        private String unitNm;
        private int scale;
        private String requiredYn;
        private BigDecimal valueNum;
        private String valueText;
        private String judge;
        private String remark;

        public String getLslText() {
            return CmsPmDtos.text(lsl, scale);
        }

        public String getUslText() {
            return CmsPmDtos.text(usl, scale);
        }

        public String getValueNumText() {
            return CmsPmDtos.text(valueNum, scale);
        }
    }

    /** 결과 저장 행 — 순번으로 찾는다. 수치는 글자 */
    public record ResultForm(Integer seq, String value, String valueText, String judge, String remark) {
    }

    public record ResultsForm(Integer rowVer, List<ResultForm> results) {
    }

    /** reasonCd = 건너뛰기 사유 코드(CMS_SKIP_REASON, docs/68 Q1 — 화면은 필수), reason = 메모(코드가 있으면 선택) */
    public record RunAction(Integer rowVer, String reasonCd, String reason, Integer seq) {
    }

    /** 예방점검 계획 grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum PlanSort implements io.smarthappy.platform.common.query.SortField {
        PLAN_CD("planCd"), PLAN_NM("planNm"), TARGET_CD("targetCd"), TMPL_CD("tmplCd"), EXEC_MODE("execMode"), NEXT_DUE("nextDueText"), ASSIGNEE_NM("assigneeNm"), USE_YN("useYn");

        private final String field;

        PlanSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    // ------------------------------------------------------------------ 달력 보기 (C27, docs/33 13.14)

    /** 달력 한 칸의 실행 행(그 달 예정일) — 매퍼 결과 */
    @Getter
    @Setter
    public static class CalRunRow {
        private long runId;
        private long planId;
        private String planCd;
        private String planNm;
        private String targetCd;
        private String cycleType;
        private LocalDate dueDate;
        private BigDecimal dueUsage;
        private int counterScale;
        private String status;
        private String result;
        private Integer leadDays;
    }

    /** 달력 계획(사용 중 달력 주기) — 예상 계산 입력 */
    @Getter
    @Setter
    public static class CalPlanRow {
        private long planId;
        private String planCd;
        private String planNm;
        private String targetCd;
        private String scheduleBasis;
        private String calUnit;
        private int calInterval;
        private LocalDate anchorDate;
        private LocalDate nextDueDate;
        private Integer leadDays;
        private LocalDate openDueDate;
    }

    /**
     * 달력 항목. kind RUN = 생성된 예정(점검 실행 — 상태 OPEN/DONE/SKIPPED), FORECAST = 아직 만들지 않은 예상(달력 주기만, PmDue).
     * state = 열린 예정·예상의 UPCOMING/DUE/OVERDUE(공장 오늘 기준), 끝난 것은 null. 사용량 주기는 RUN 만(dueUsageText = 예정 사용량)
     */
    public record CalItem(String date, String kind, long planId, String planCd, String planNm, String targetCd, String cycleType,
            Long runId, String status, String result, String state, String dueUsageText) {
    }

    /** 달력 응답 — month = YYYY-MM, today = 공장 오늘, truncated = 상한(MAX_CAL_ITEMS)에서 잘림, usagePlans = 그 공장의 사용 중 사용량 계획 수 */
    public record CalView(String plantCd, String month, String today, List<CalItem> items, boolean truncated, int usagePlans) {
    }

    public record PlanPage(List<PlanRow> list, Long total) {
    }

    /** 예방점검 계획 엑셀(C16) — grid 조건 + 정렬 + 보이는 열 */
    public record PlanExport(String plantCd, String keyword, String useYn, List<String> sort, List<String> columns) {
    }

    /** 점검표 grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum TmplSort implements io.smarthappy.platform.common.query.SortField {
        TMPL_CD("tmplCd"), TMPL_NM("tmplNm"), TARGET_KIND("targetKind"), EQUIP_CLASS("equipClassCd"), USE_YN("useYn");

        private final String field;

        TmplSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record TmplPage(List<TmplRow> list, Long total) {
    }

    /** 점검 실행 grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum RunSort implements io.smarthappy.platform.common.query.SortField {
        DUE_DATE("dueDate"), PLAN_CD("planCd"), TARGET_CD("targetCd"), EXEC_MODE("execMode"), STATUS("status"), RESULT("result"), MWO_NO("mwoNo"), PERFORMED_AT("performedAt");

        private final String field;

        RunSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record RunPage(List<RunRow> list, Long total) {
    }

    /** 점검 실행 엑셀(C21) — grid 조건 + 정렬 + 보이는 열 */
    public record RunExport(String from, String to, String status, String execMode, String plantCd, String keyword, List<String> sort,
            List<String> columns) {
    }

    /** 점검표 엑셀(C23) — grid 와 같은 조건·정렬·보이는 열 */
    public record TmplExport(String useYn, String keyword, List<String> sort, List<String> columns) {
    }
}
