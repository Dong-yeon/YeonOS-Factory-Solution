package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** P3 QMS Q2b MSA(GRR 연구) DTO (docs/29) */
public final class MsaDtos {

    private MsaDtos() {
    }

    @Getter
    @Setter
    public static class StudyRow {
        private long studyId;
        private String studyNo;
        private long assetId;
        private String assetCd;
        private String assetNm;
        private Long charId;
        private String charLabel;
        private BigDecimal charLsl;
        private BigDecimal charUsl;
        private String method;
        private int appraiserCnt;
        private int partCnt;
        private int trialCnt;
        private String basis;
        private BigDecimal processSigma;
        /** 측정자 이름을 0x1F 로 이은 글자(매퍼) — 응답은 appraisers */
        private String appraiserNmsText;
        private List<String> appraisers;
        private LocalDate studyDate;
        private String status;
        private BigDecimal tolerance;
        private String resultJson;
        private BigDecimal grrPct;
        private Integer ndc;
        private String verdict;
        private OffsetDateTime doneAt;
        private String doneBy;
        private String remark;
        private int dataCnt;
        private int rowVer;
        private OffsetDateTime updatedAt;
    }

    /** 원자료 한 칸 — v 는 수치 글자 또는 OK/NG. a = 0 은 계수형 기준값 */
    @Getter
    @Setter
    public static class Cell {
        private int a;
        private int p;
        private int t;
        private String v;
    }

    public record CellForm(Integer a, Integer p, Integer t, String v) {
    }

    /** 원자료 칸 저장 값 */
    public record CellValue(int a, int p, int t, BigDecimal value, String attr) {
    }

    /**
     * 상세 — 작성 중이고 원자료가 다 찼으면 preview(지금 값으로 계산, 저장 안 함), 확정본은 result(스냅샷).
     * 계산할 수 없으면 previewError(문구 키 코드)
     */
    public record StudyDetail(StudyRow study, List<Cell> cells, Map<String, Object> result, Map<String, Object> preview,
            String previewError, int okPct, int limitPct) {
    }

    public record StudyForm(String assetCd, Long charId, String method, Integer appraiserCnt, Integer partCnt, Integer trialCnt,
            String basis, String processSigma, List<String> appraisers, String studyDate, String remark, Integer rowVer,
            List<CellForm> cells) {
    }

    public record StudyValue(long assetId, Long charId, String method, int appraiserCnt, int partCnt, int trialCnt, String basis,
            BigDecimal processSigma, String appraiserNmsText, LocalDate studyDate, String remark, Integer rowVer) {
    }

    public record ActionForm(Integer rowVer) {
    }

    /** 고르기 — 계측기·특성 */
    @Getter
    @Setter
    public static class Choice {
        private long id;
        private String cd;
        private String nm;
        private String extra;
    }

    @Getter
    public static class StudySearch {
        private final String keywordLike;
        private final String method;
        private final String status;
        private final PageQuery page;

        public StudySearch(String keywordLike, String method, String status, PageQuery page) {
            this.keywordLike = keywordLike;
            this.method = method;
            this.status = status;
            this.page = page;
        }
    }

    public enum StudySort implements SortField {
        STUDY_NO("studyNo"), STUDY_DATE("studyDate"), ASSET_CD("assetCd"), GRR_PCT("grrPct"), STATUS("status");

        private final String field;

        StudySort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record StudyExportRequest(String keyword, String method, String status, List<String> sort, List<String> columns) {
    }
}
