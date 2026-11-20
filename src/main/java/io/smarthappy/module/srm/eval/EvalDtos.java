package io.smarthappy.module.srm.eval;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.SortField;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/** 협력사 평가 DTO (SRM S6, docs/31 4.6). 점수·배점·가중치는 응답에서 글자(trim_scale) */
public final class EvalDtos {

    private EvalDtos() {
    }

    // ------------------------------------------------------------------ 템플릿

    @Getter
    @Setter
    public static class TmplRow {
        private long tmplId;
        private String tmplCd;
        private int rev;
        private String tmplNm;
        private String status;
        private LocalDate validFrom;
        private LocalDate validTo;
        private String remark;
        private int itemCnt;
        private String weightSum;
        private String releasedBy;
        private OffsetDateTime releasedAt;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    @Getter
    @Setter
    public static class ItemRow {
        private long itemId;
        private int itemNo;
        private String sectionNm;
        private String itemNm;
        private String maxScore;
        private String weight;
        private String failBelow;
        private String inputType;
        private String metricCd;
        private String guide;
        /** 점수 구간(RECV_PPM 만, 상한 오름차순·끝 없는 구간 마지막) — 서비스가 채운다 */
        private List<BandRow> bands = List.of();
    }

    /** 점수 구간 행(docs/31 4.12.3) — 상한 NULL = 그 위 전부 */
    @Getter
    @Setter
    public static class BandRow {
        private long itemId;
        private String upperPpm;
        private String scorePct;
    }

    @Getter
    @Setter
    public static class GradeRow {
        private long gradeId;
        private String gradeCd;
        private String minScore;
        private String suggestHoldYn;
    }

    public record TmplDetail(TmplRow tmpl, List<ItemRow> items, List<GradeRow> grades) {
    }

    /** 신규는 tmplCd 필수(이후 고정). 항목·등급은 "한 벌"(작성 중 템플릿은 통째로 바꾼다 — 항목 번호 = 배열 순서) */
    public record TmplForm(String tmplCd, String tmplNm, String validFrom, String validTo, String remark, Integer rowVer,
            List<ItemForm> items, List<GradeForm> grades) {
    }

    public record ItemForm(String sectionNm, String itemNm, String maxScore, String weight, String failBelow, String inputType,
            String metricCd, String guide, List<BandForm> bands) {
    }

    /** 점수 구간 입력 — 상한 비우면 "그 위 전부"(항목마다 하나, 마지막) */
    public record BandForm(String upperPpm, String scorePct) {
    }

    public record GradeForm(String gradeCd, String minScore, String suggestHoldYn) {
    }

    public record ItemValue(int itemNo, String sectionNm, String itemNm, BigDecimal maxScore, BigDecimal weight, BigDecimal failBelow,
            String inputType, String metricCd, String guide, List<BandValue> bands) {
    }

    /** 검증된 구간 — upperPpm null = 끝 없는 구간 */
    public record BandValue(BigDecimal upperPpm, BigDecimal scorePct) {
    }

    public record GradeValue(String gradeCd, BigDecimal minScore, String suggestHoldYn) {
    }

    public record ActionForm(Integer rowVer, String reason) {
    }

    // ------------------------------------------------------------------ 평가

    @Getter
    @Setter
    public static class EvalRow {
        private long evalId;
        private String evalNo;
        private long custId;
        private String custCd;
        private String custNm;
        private String tradeStatus;
        private long tmplId;
        private String tmplCd;
        private int tmplRev;
        private String tmplNm;
        private LocalDate periodFrom;
        private LocalDate periodTo;
        private String status;
        private String totalScore;
        private String gradeCd;
        private String failYn;
        private String suggestTradeStatus;
        private String confirmedBy;
        private OffsetDateTime confirmedAt;
        private String cancelReason;
        private String remark;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    @Getter
    @Setter
    public static class ScoreRow {
        private long scoreId;
        private long itemId;
        private int itemNo;
        private String sectionNm;
        private String itemNm;
        private String maxScore;
        private String weight;
        private String failBelow;
        private String inputType;
        private String metricCd;
        private String guide;
        private String score;
        private String rawValue;
        private String autoYn;
        /** AUTO 근거(기계 글: ACK_RATE:발행:기한 안 / ACK_NONE / CERT:개수:가장 이른 만료 / CERT_NONE / PART2) — 화면이 문구로 */
        private String basis;
        private String comment;
    }

    /** 미리보기(넣은 점수까지) — 확정 값과 같은 함수(EvalScorer) */
    public record Preview(String total, String gradeCd, boolean fail, boolean suggestHold, boolean complete, List<Long> failedItems) {
    }

    public record EvalDetail(EvalRow eval, List<ScoreRow> scores, List<GradeRow> grades, Preview preview) {
    }

    public record EvalForm(String custCd, String tmplCd, String periodFrom, String periodTo, String remark) {
    }

    public record ScoresForm(Integer rowVer, String remark, List<ScoreForm> scores) {
    }

    public record ScoreForm(Long itemId, String score, String comment) {
    }

    /** 계산 재료 */
    @Getter
    @Setter
    public static class ItemSpec {
        private long itemId;
        private BigDecimal maxScore;
        private BigDecimal weight;
        private BigDecimal failBelow;
        private String inputType;
        private String metricCd;
    }

    @Getter
    @Setter
    public static class GradeSpec {
        private String gradeCd;
        private BigDecimal minScore;
        private String suggestHoldYn;
    }

    @Getter
    @Setter
    public static class ScoreSpec {
        private long itemId;
        private BigDecimal score;
        private String autoYn;
    }

    @Getter
    @Setter
    public static class AckRate {
        private int issuedCnt;
        private int onTimeCnt;
    }

    /** 납기 준수율(2부 R2) — 요구 납기가 기간 안·오늘 이하인 발주 행 수 / 그중 납기까지 다 받은 행 수 */
    @Getter
    @Setter
    public static class OtdRate {
        private int dueCnt;
        private int onTimeCnt;
    }

    @Getter
    @Setter
    public static class CertState {
        private int currentCnt;
        private LocalDate minExpires;
    }

    @Getter
    @Setter
    public static class SupplierOption {
        private long custId;
        private String custCd;
        private String custNm;
        private String tradeStatus;
    }

    /** 템플릿 목록·grid 공통 조건(검증된 값) — MyBatis OGNL 이 읽게 getter 클래스 */
    @Getter
    @AllArgsConstructor
    public static class TmplCrit {
        private String status;
        private String keywordLike;
    }

    /** grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum TmplSort implements SortField {
        TMPL_CD("tmplCd"), TMPL_NM("tmplNm"), STATUS("status"), VALID_FROM("validFrom");

        private final String field;

        TmplSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** grid 응답 한 쪽 */
    public record TmplPage(List<TmplRow> list, Long total) {
    }

    /** 평가 목록·grid 공통 조건(검증된 값) */
    @Getter
    @AllArgsConstructor
    public static class EvalCrit {
        private LocalDate from;
        private LocalDate to;
        private String status;
        private String keywordLike;
    }

    /** grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum EvalSort implements SortField {
        EVAL_NO("evalNo"), SUPPLIER("custNm"), PERIOD_TO("periodTo"), STATUS("status"), SCORE("totalScore"), GRADE("gradeCd");

        private final String field;

        EvalSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** grid 응답 한 쪽 */
    public record EvalPage(List<EvalRow> list, Long total) {
    }
}
