package io.smarthappy.module.qms;

import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** P3 QMS Q3 관리문서·정합 점검 DTO (docs/29) */
public final class DocDtos {

    private DocDtos() {
    }

    /** 목록·상세 머리 — 현행 배포본과 작성 중(결재 중 포함) 리비전 요약 */
    @Getter
    @Setter
    public static class DocRow {
        private long docId;
        private String docNo;
        private String docType;
        private String docNm;
        private String useYn;
        private String remark;
        private Long releasedRevId;
        private String releasedRev;
        private OffsetDateTime releasedAt;
        private Long openRevId;
        private String openRev;
        private String openStatus;
        private String targetText;
        private int targetCnt;
        private int rowVer;
        private OffsetDateTime updatedAt;
    }

    @Getter
    @Setter
    public static class TargetRow {
        private long targetId;
        private String targetType;
        private Long refId;
        private String targetCd;
        private String targetNm;
        private String useYn;
    }

    @Getter
    @Setter
    public static class RevRow {
        private long docRevId;
        private long docId;
        private String docNo;
        private String docType;
        private String docNm;
        private String rev;
        private String status;
        private String changeSummary;
        private Long sourceRevId;
        private Long aprId;
        private String aprStatus;
        private Long aprRequesterId;
        private OffsetDateTime submittedAt;
        private String submittedBy;
        private OffsetDateTime releasedAt;
        private String releasedBy;
        private OffsetDateTime obsoletedAt;
        private String obsoletedBy;
        private int fileCnt;
        private int rowVer;
    }

    @Getter
    @Setter
    public static class AprLineRow {
        private int seq;
        private long approverId;
        private String approverNm;
        private String lineType;
        private String status;
        private OffsetDateTime actedAt;
        private String comment;
    }

    /** 작성 중 리비전의 결재 요약 — 화면이 승인·반려·회수 버튼을 보일지 정한다(판정은 결재 엔진) */
    public record Approval(Long aprId, String status, boolean myTurn, boolean canWithdraw, List<AprLineRow> lines) {
    }

    public record DocDetail(DocRow doc, List<TargetRow> targets, List<RevRow> revs, Approval approval, boolean approvalRequired) {
    }

    public record TargetForm(String targetType, String targetCd) {
    }

    public record DocForm(String docNo, String docType, String docNm, String useYn, String remark, List<TargetForm> targets,
            Integer rowVer) {
    }

    /** 검증된 대상 — 행 키 = 배열 순번 */
    public record TargetValue(String targetType, Long itemId, Long itemClassId, Long procId) {
    }

    public record RevForm(String rev, String changeSummary, Integer rowVer) {
    }

    public record NewRevForm(String rev, String changeSummary) {
    }

    public record LineItem(Long userId, String lineType) {
    }

    public record SubmitForm(Integer rowVer, String title, List<LineItem> lines) {
    }

    public record ActionForm(Integer rowVer) {
    }

    @Getter
    @Setter
    public static class Ref {
        private long id;
        private String useYn;
    }

    @Getter
    public static class DocSearch {
        private final String keywordLike;
        private final String docType;
        private final boolean withUnused;
        private final PageQuery page;

        public DocSearch(String keywordLike, String docType, boolean withUnused, PageQuery page) {
            this.keywordLike = keywordLike;
            this.docType = docType;
            this.withUnused = withUnused;
            this.page = page;
        }
    }

    public enum DocSort implements SortField {
        DOC_NO("docNo"), DOC_TYPE("docType"), DOC_NM("docNm"), RELEASED_AT("releasedAt"), UPDATED_AT("updatedAt");

        private final String field;

        DocSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    // ------------------------------------------------------------------ 정합 점검

    /** 점검 결과 한 줄 — checkCd = SPECIAL_NOT_IN_PLAN | PLAN_NO_INSP_STD | PLAN_DOC_OBSOLETE, refId = 특성 id 또는 검사계획 id */
    @Getter
    @Setter
    public static class Finding {
        private String checkCd;
        private long refId;
        private String targetCd;
        private String targetNm;
        private String detail;
    }

    public record ConsistencyExportRequest(String checkCd) {
    }
}
