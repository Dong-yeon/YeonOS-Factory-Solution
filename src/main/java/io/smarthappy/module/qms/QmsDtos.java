package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** P3 QMS Q1 검사 기준 DTO — 샘플링 계획·AQL 표·검사계획 (docs/29) */
public final class QmsDtos {

    private QmsDtos() {
    }

    // ------------------------------------------------------------------ 샘플링 계획

    @Getter
    @Setter
    public static class SamplingRow {
        private long samplingPlanId;
        private String planCd;
        private String planNm;
        private String method;
        private Integer fixedN;
        private BigDecimal pct;
        private String stdCd;
        private String inspLevel;
        private String severity;
        private BigDecimal aql;
        private Integer ac;
        private Integer re;
        private int sortNo;
        private String useYn;
        private String remark;
        /** 이 계획을 쓰는 확정·폐기 검사계획 수 — 0 이 아니면 방식·수치를 바꿀 수 없다 */
        private int lockedCnt;
        /** 쓰는 작성 중 검사계획 수 */
        private int draftCnt;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    public record SamplingForm(String planCd, String planNm, String method, Integer fixedN, String pct, String stdCd,
            String inspLevel, String severity, String aql, Integer ac, Integer sortNo, String useYn, String remark,
            Integer rowVer) {
    }

    public record SamplingValue(String planCd, String planNm, String method, Integer fixedN, BigDecimal pct, String stdCd,
            String inspLevel, String severity, BigDecimal aql, Integer ac, Integer re, int sortNo, String useYn, String remark,
            Integer rowVer) {
    }

    @Getter
    public static class SamplingSearch {
        private final String keywordLike;
        private final String method;
        private final boolean withUnused;
        private final PageQuery page;

        public SamplingSearch(String keywordLike, String method, boolean withUnused, PageQuery page) {
            this.keywordLike = keywordLike;
            this.method = method;
            this.withUnused = withUnused;
            this.page = page;
        }
    }

    public enum SamplingSort implements SortField {
        PLAN_CD("planCd"), PLAN_NM("planNm"), METHOD("method"), SORT_NO("sortNo"), UPDATED_AT("updatedAt");

        private final String field;

        SamplingSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record SamplingExportRequest(String keyword, String method, Boolean withUnused, List<String> sort,
            List<String> columns) {
    }

    /** 판정 미리보기 — 저장된 계획(samplingPlanId) 또는 화면 입력 값(form) */
    public record PreviewRequest(Long samplingPlanId, SamplingForm form, Long lotQty, Integer defects) {
    }

    public record PreviewResult(long lotQty, int sampleSize, int ac, int re, String codeLetter, boolean all, Integer defects,
            String verdict) {
    }

    // ------------------------------------------------------------------ AQL 표

    @Getter
    @Setter
    public static class AqlLetterRow {
        private long aqlTableId;
        private String stdCd;
        private String inspLevel;
        private long lotFrom;
        private Long lotTo;
        private String codeLetter;
        private String useYn;
        private int rowVer;
    }

    @Getter
    @Setter
    public static class AqlPlanRowView {
        private long aqlRowId;
        private String stdCd;
        private String severity;
        private String codeLetter;
        private BigDecimal aql;
        private int sampleSize;
        private int ac;
        private int re;
        private String useYn;
        private int rowVer;
    }

    /** 표준 하나의 표 전체 (보기) */
    public record AqlView(String stdCd, List<AqlLetterRow> letters, List<AqlPlanRowView> rows) {
    }

    // ------------------------------------------------------------------ 검사계획 머리

    @Getter
    @Setter
    public static class PlanRow {
        private long inspPlanId;
        private String inspType;
        private Long itemId;
        private String itemCd;
        private String itemNm;
        private String itemUseYn;
        private Long itemClassId;
        private String classCd;
        private String classNm;
        private String classUseYn;
        private Long procId;
        private String procCd;
        private String procNm;
        private String procUseYn;
        /** 목록 표시용 대상 코드(품목 코드 또는 클래스 코드) */
        private String targetCd;
        private String targetNm;
        private int altNo;
        private String rev;
        private String status;
        private LocalDate validFrom;
        private LocalDate validTo;
        private String changeNo;
        private String changeReason;
        private String remark;
        private OffsetDateTime releasedAt;
        private String releasedBy;
        private int lineCnt;
        /** 확정본 행 중 특성 규격이 지금 특성과 다른 행 수 — "새 리비전 필요" */
        private int specChangedCnt;
        /** 검사기준서 리비전(Q3 관리문서) — 표시는 "문서 번호 / 리비전" 과 그 리비전 상태 */
        private Long docRevId;
        private String docLabel;
        private String docRevStatus;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    @Getter
    @Setter
    public static class LineRow {
        private long lineId;
        private long inspPlanId;
        private int lineNo;
        private long charId;
        private String charType;
        private String charNo;
        private String charNm;
        private String charUseYn;
        /** 특성의 품목(제품 특성)·공정 — 표시용 */
        private String charItemCd;
        private String charProcCd;
        private String methodNm;
        private String gaugeClass;
        private long samplingPlanId;
        private String samplingPlanCd;
        private String samplingPlanNm;
        private String samplingUseYn;
        private String freqType;
        private Integer freqN;
        private String reactionPlan;
        private String remark;
        /** 분류·증빙(docs/72 3.2) — 증빙 NONE / PHOTO */
        private String grpNm;
        private String evidenceReq;
        /** 보이는 규격 — 확정·폐기본은 스냅샷, 작성 중은 지금 특성 */
        private BigDecimal nominal;
        private BigDecimal lsl;
        private BigDecimal usl;
        private String unitNm;
        private Integer scale;
        private String specialCls;
        private String dataType;
        private String specText;
        /** 확정본인데 지금 특성 규격이 스냅샷과 다름 */
        private boolean specChanged;
        /** 지금 특성 규격 글자(다를 때 비교용) */
        private String currentSpecText;
        /** 지금 특성 값 — 서비스가 비교 글자를 만든다 */
        private BigDecimal curNominal;
        private BigDecimal curLsl;
        private BigDecimal curUsl;
        private String curUnitNm;
        private Integer curScale;
        private String curDataType;
        private int rowVer;
    }

    public record PlanDetail(PlanRow plan, List<LineRow> lines) {
    }

    @Getter
    public static class PlanSearch {
        private final String inspType;
        private final String targetLike;
        private final String procCd;
        private final String status;
        private final boolean withObsolete;
        private final boolean specChangedOnly;
        private final PageQuery page;

        public PlanSearch(String inspType, String targetLike, String procCd, String status, boolean withObsolete,
                boolean specChangedOnly, PageQuery page) {
            this.inspType = inspType;
            this.targetLike = targetLike;
            this.procCd = procCd;
            this.status = status;
            this.withObsolete = withObsolete;
            this.specChangedOnly = specChangedOnly;
            this.page = page;
        }
    }

    public enum PlanSort implements SortField {
        INSP_TYPE("inspType"), TARGET_CD("targetCd"), PROC_CD("procCd"), REV("rev"), STATUS("status"), VALID_FROM("validFrom"),
        UPDATED_AT("updatedAt");

        private final String field;

        PlanSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 대상은 품목 코드 또는 클래스 코드 하나 */
    public record HeaderForm(String inspType, String itemCd, String classCd, String procCd, Integer altNo, String rev,
            String validFrom, String changeNo, String changeReason, String remark, Integer rowVer) {
    }

    public record HeaderValue(String inspType, Long itemId, Long itemClassId, Long procId, int altNo, String rev,
            LocalDate validFrom, String changeNo, String changeReason, String remark, Integer rowVer) {
    }

    /** 행: lineId 가 있으면 수정, 없으면 추가. 목록에 없는 기존 행은 삭제 (한 벌 저장 — 라우팅과 같음) */
    public record LineItem(String rowKey, Long lineId, Integer lineNo, Long charId, String methodNm, String gaugeClass,
            String samplingPlanCd, String freqType, Integer freqN, String reactionPlan, String remark, String grpNm, String evidenceReq) {
        /** 분류·증빙 없이(이전 호출) — 분류 없음, 증빙 NONE */
        public LineItem(String rowKey, Long lineId, Integer lineNo, Long charId, String methodNm, String gaugeClass,
                String samplingPlanCd, String freqType, Integer freqN, String reactionPlan, String remark) {
            this(rowKey, lineId, lineNo, charId, methodNm, gaugeClass, samplingPlanCd, freqType, freqN, reactionPlan, remark, null, null);
        }
    }

    public record LinesForm(List<LineItem> lines) {
    }

    public record LineValue(String rowKey, Long lineId, int lineNo, long charId, String methodNm, String gaugeClass,
            long samplingPlanId, String freqType, Integer freqN, String reactionPlan, String remark, String grpNm, String evidenceReq) {
    }

    public record SaveResult(int lines) {
    }

    /** 상태 동작(확정·폐기·새 리비전) */
    public record ActionRequest(Integer rowVer, String validFrom, String rev) {
    }

    public record PlanExportRequest(String inspType, String target, String procCd, String status, Boolean withObsolete,
            Boolean specChangedOnly, List<String> sort, List<String> columns) {
    }

    // ------------------------------------------------------------------ 고르기

    /** 행 특성 선택지 — 계획 대상(품목·공정)에 맞는 사용 중 특성 */
    @Getter
    @Setter
    public static class CharChoice {
        private long charId;
        private String charType;
        private Long itemId;
        private Long procId;
        private String charNo;
        private String charNm;
        private String itemCd;
        private String procCd;
        private String dataType;
        private String unitNm;
        private int scale;
        private BigDecimal nominal;
        private BigDecimal lsl;
        private BigDecimal usl;
        private String specialCls;
        private String useYn;
        private String specText;
    }

    @Getter
    @Setter
    public static class TargetLookup {
        private long id;
        private String cd;
        private String nm;
        private String useYn;
    }

    /** InspPlanResolver 후보 행 */
    @Getter
    @Setter
    public static class CandidateRow {
        private long inspPlanId;
        private String inspType;
        private Long itemId;
        private Long itemClassId;
        private Long procId;
        private int altNo;
        private String rev;
        private String status;
        private LocalDate validFrom;
        private LocalDate validTo;
    }

    @Getter
    @Setter
    public static class ItemLookup {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private Long itemClassId;
        private String useYn;
    }

    /** 유효 검사계획 찾기 결과 (InspPlanResolver) */
    public record EffectiveResult(Long inspPlanId, String rev, String targetKind, Long procId) {
    }

    // ------------------------------------------------------------------ 검사기준서 연결 (Q3)

    public record LinkDocForm(Long docRevId, Integer rowVer) {
    }

    @Getter
    @Setter
    public static class DocChoice {
        private long docRevId;
        private String docNo;
        private String docNm;
        private String rev;
        private String status;
    }
}
