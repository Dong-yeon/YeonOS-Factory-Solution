package io.smarthappy.module.apqp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.module.apqp.ApqpDtos.ChangeNotes;
import io.smarthappy.module.apqp.ApqpDtos.CharOption;
import io.smarthappy.module.apqp.ApqpDtos.StepOption;
import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** 관리계획서(A2, docs/41 5장) 요청·응답 */
public final class CpDtos {

    private CpDtos() {
    }

    // ---------- 머리 ----------

    @Getter
    @Setter
    public static class CpRow {
        private long cpId;
        private String cpNo;
        private int rev;
        private String phase;
        private String title;
        private String team;
        private String changeReason;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private Long routingId;
        private String routingText;
        private Long fmeaId;
        private String fmeaNo;
        private Integer fmeaRev;
        private String fmeaStatus;
        private Long projId;
        private String projNo;
        private String status;
        private OffsetDateTime releasedAt;
        private String releasedBy;
        private Long prevCpId;
        private int lineCnt;
        /** 근거 PFMEA 가 폐기됨 = 같은 번호의 새 리비전이 확정됨(C2 — 자동으로 바꾸지 않고 표시만) */
        private boolean fmeaStale;
        /** 같은 번호에 작성 중(결재 중 포함) 리비전이 있음 */
        private boolean draftExists;
        /** 같은 품목·공장·대안의 라우팅 새 리비전이 확정됨(docs/41 A3-2 ① — 표시만) */
        private boolean routingStale;
        /** 마지막 결재 요청(정책 APQP_RELEASE_APPROVAL) */
        private Long aprId;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
        /**
         * 재검토 코드(docs/41 11.6 M2) — ROUTING(라우팅 새 리비전 확정, ①)·FMEA(근거 PFMEA 새 리비전 확정, C2) 를 쉼표로, 폐기본은 없음.
         * ECO·4M(②③)은 품목마다 모듈 간 조회라 상세에만. 정렬 키 아님. 글자는 LABEL 열(X2, 문구 키 apqp.review.*)이 화면·엑셀 같은 문구로
         */
        public String getReview() {
            if ("OBSOLETE".equals(status) || !(routingStale || fmeaStale)) {
                return null;
            }
            return routingStale && fmeaStale ? "ROUTING,FMEA" : routingStale ? "ROUTING" : "FMEA";
        }
    }

    public enum CpSort implements SortField {
        CP_NO("cpNo"), REV("rev"), PHASE("phase"), TITLE("title"), ITEM_CD("itemCd"), STATUS("status"), LINE_CNT("lineCnt"),
        UPDATED_AT("updatedAt");

        private final String field;

        CpSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 목록 엑셀 — 조건은 grid 와 같음 */
    public record CpExportRequest(String keyword, String status, String phase, List<String> sort, List<String> columns) {
    }

    public record CpPage(List<CpRow> list, Long total) {
    }

    /** 목록 조건 — keywordLike 는 이미 이스케이프한 LIKE 값, statuses null = 전체 */
    public record CpFilter(String keywordLike, List<String> statuses, String phase, Long itemId) {
    }

    /** 서버 안에서 쓰는 머리 값(검증 뒤) */
    public record CpValue(long itemId, Long routingId, Long fmeaId, Long projId, String title, String team, String changeReason) {
    }

    public record CpForm(String itemCd, Long routingId, Long fmeaId, String phase, Long projId, String title, String team,
            String changeReason, Boolean pullFromFmea, Integer rowVer) {
    }

    /** 새 리비전·다음 단계 — fmeaId 비우면 지금 근거 그대로(그것이 폐기됐으면 골라야), phase 는 다음 단계만 */
    public record CpCopyForm(Long fmeaId, String phase, String title) {
    }

    // ---------- 행 ----------

    @Getter
    @Setter
    public static class CpLineRow {
        private long lineId;
        private int lineNo;
        private Long stepId;
        private Integer opSeq;
        private String procCd;
        private String procNm;
        private String stepText;
        private Long charId;
        private String charNo;
        private String charNm;
        private String charType;
        private String charText;
        private String ctrlMethod;
        private String evalMethod;
        private String gaugeClass;
        private String inspType;
        private Integer sampleN;
        private String sampleText;
        private String freqType;
        private Integer freqN;
        private String reactionPlan;
        private Long riskId;
        private String riskAp;
        /** 행에 정한 특별특성(끌어오기 제안·사람 입력). 확정 때 비어 있으면 특성의 값으로 채운다 */
        private String specialCls;
        /** 특성 마스터의 지금 값 — 작성 중 화면 표시용(확정본은 스냅샷 칸) */
        private String charSpecial;
        private BigDecimal charNominal;
        private BigDecimal charLsl;
        private BigDecimal charUsl;
        private String charUnit;
        // 확정 때 스냅샷
        private BigDecimal nominal;
        private BigDecimal lsl;
        private BigDecimal usl;
        private String unitNm;
        private String dataType;
        private int rowVer;
    }

    public record CpLineForm(Long stepId, String stepText, Long charId, String charText, String ctrlMethod, String evalMethod,
            String gaugeClass, String inspType, Integer sampleN, String sampleText, String freqType, Integer freqN, String reactionPlan,
            Long riskId, String specialCls, Integer rowVer) {
    }

    /** 확정 전 점검 — 근거 PFMEA·품목의 특별특성 중 행이 없는 것(kind = RISK·CHAR) */
    public record SpecialGap(String kind, long id, Long charId, String charNo, String charNm, String specialCls, String text) {
    }

    /** 근거로 고를 수 있는 확정 PFMEA */
    @Getter
    @Setter
    public static class FmeaOption {
        private long fmeaId;
        private String fmeaNo;
        private int rev;
        private String title;
        private Long routingId;
    }

    /** 근거 PFMEA 의 위험행 요약(행의 근거 고르기·끌어오기 미리보기) */
    public record RiskOption(long riskId, Long stepId, String stepNm, String fm, String fc, Long charId, String charNo, String detCtrl,
            String prevCtrl, String ap, String specialCls) {
    }

    public record CpDetail(CpRow cp, List<CpLineRow> lines, List<StepOption> steps, List<CharOption> chars, List<RiskOption> risks,
            List<SpecialGap> gaps, CompareResult compare, Long newerFmeaId, boolean editable, boolean approvalOn,
            ChangeNotes changes) {
    }

    /**
     * 검사계획 대조 한 줄(C5) — kind = MISSING_IN_PLAN(CP 검사 행이 그 날 유효한 확정 검사계획에 없음) · DIFF(계측기·주기가 다름) ·
     * MISSING_IN_CP(검사계획에는 있는데 같은 공정·검사 유형의 CP 행에 없음). lineId 는 CP 행(MISSING_IN_CP 는 null)
     */
    public record CompareRow(String kind, Long lineId, long charId, String charNo, String charNm, String inspType, String procCd,
            Long inspPlanId, String planRev, String cpGauge, String planGauge, String cpFreqType, Integer cpFreqN, String planFreqType,
            Integer planFreqN, String cpSample, String planSampling) {
    }

    /** ready = QMS 검사계획 조회가 있는 설치본, matched = 검사계획과 맞는 CP 검사 행 수, skipped = 대조 대상이 아닌 행(검사 유형·특성 키 없음) */
    /** plans = 대조에서 물어본 확정 검사계획 리비전(중복 없음 — 4M 검사계획 겹침에 씀, docs/41 11.6.2) */
    public record CompareResult(boolean ready, int matched, int skipped, List<CompareRow> rows, List<ApqpDtos.PlanRef> plans) {
    }

    /** 끌어오기 결과 — 더한 행 수·건너뛴 위험행 수(같은 근거 위험행 또는 같은 단계·특성·관리 방법이 이미 있음, C7) */
    public record PullResult(int added, int skipped) {
    }
}
