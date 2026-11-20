package io.smarthappy.module.cms;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** CMS 1부 C6 DTO — 위험성평가 (docs/33 4.7) */
public final class CmsRiskDtos {

    private CmsRiskDtos() {
    }

    @Getter
    @Setter
    public static class RaRow {
        private long raId;
        private String raNo;
        private String plantCd;
        private String raType;
        private String method;
        private String matrixCd;
        private String title;
        private String scopeText;
        private LocalDate assessDate;
        private String status;
        private Long prevRaId;
        private String prevRaNo;
        private Long aprId;
        private OffsetDateTime releasedAt;
        private String releasedBy;
        private String remark;
        private int itemCnt;
        private int highCnt;
        /** 개선 대책 중 완료 안 된 것·그중 기한 지난 것(계산) */
        private int openCnt;
        private int overdueCnt;
        private int memberCnt;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
        private List<ItemRow> items;
        private List<MemberRow> members;
        /** 계산: 결재 필요(정책)·확정 못 하는 이유 */
        private boolean approvalRequired;
        private List<RiskMatrix.Problem> problems;
    }

    public record RaForm(String raType, String method, String title, String scopeText, String plantCd, String assessDate, String remark,
            Integer rowVer) {
    }

    @Getter
    @Setter
    public static class ItemRow {
        private long itemId;
        private int seq;
        private String taskText;
        private Long equipId;
        private String equipCd;
        private String equipNm;
        private String hazardCat;
        private String hazardText;
        private String currentMeasure;
        private Integer likelihood;
        private Integer severity;
        private String riskLevel;
        private String planMeasure;
        private Long ownerUserId;
        private String ownerLoginId;
        private String ownerNm;
        private LocalDate dueDate;
        private LocalDate doneDate;
        private Integer residualL;
        private Integer residualS;
        private String residualLevel;
        /** 계산: 개선 기한 지남 */
        private boolean overdue;
        private int rowVer;
    }

    /** 항목 저장 — itemId 없으면 추가. 빈도·강도법은 가능성·중대성, 3단계 판단법은 riskLevel */
    public record ItemForm(Long itemId, Integer seq, String taskText, String equipCd, String hazardCat, String hazardText,
            String currentMeasure, Integer likelihood, Integer severity, String riskLevel, String planMeasure, String ownerLoginId,
            String dueDate, Integer rowVer) {
    }

    /** 확정 뒤 개선 진행 — 완료일·개선 후 위험성 */
    public record ProgressForm(String doneDate, Integer residualL, Integer residualS, String residualLevel, Integer rowVer) {
    }

    @Getter
    @Setter
    public static class MemberRow {
        private long memberId;
        private Long userId;
        private String loginId;
        /** 현장 작업자(C19) — 사용자와 함께 쓰지 않는다 */
        private Long workerId;
        private String workerCd;
        private String memberNm;
        private String roleCd;
        private LocalDate participatedOn;
        private LocalDate notifiedOn;
        private String remark;
        private int rowVer;
    }

    /** 참여자 — 사용자(loginId)·작업자(workerCd, C19) 중 하나, 또는 이름만(협력업체 근로자 등) */
    public record MemberForm(String loginId, String memberNm, String roleCd, String participatedOn, String notifiedOn, String remark,
            String workerCd) {
    }

    /** 참여자 고르기 — 사용 중 작업자(기준정보 mst_worker 읽기만, C19) */
    public record WorkerOption(long workerId, String workerCd, String workerNm, String plantCd) {
    }

    public record NotifyForm(String notifiedOn, Integer rowVer) {
    }

    /** 확정 요청: 결재선(정책 Y), 머리 rowVer */
    public record ReleaseForm(Integer rowVer, List<LineIn> lines) {
    }

    public record LineIn(Long userId, String lineType) {
    }

    public record CopyForm(String raType, String assessDate) {
    }

    public record RaAction(Integer rowVer, String reason) {
    }

    /** grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum RaSort implements io.smarthappy.platform.common.query.SortField {
        RA_NO("raNo"), TITLE("title"), RA_TYPE("raType"), STATUS("status"), ASSESS_DATE("assessDate");

        private final String field;

        RaSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** grid 응답 한 쪽 */
    public record RaPage(List<RaRow> list, Long total) {
    }
}
