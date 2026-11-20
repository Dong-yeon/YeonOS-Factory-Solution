package io.smarthappy.module.pms;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.module.pms.PmsDtos.ProjectRow;
import lombok.Getter;
import lombok.Setter;

/** PMS 3단계 산출물·게이트 심사 DTO (docs/32 PM3) */
public final class GateDtos {

    private GateDtos() {
    }

    // ------------------------------------------------------------------ 템플릿 산출물 칸·점검 문구

    @Getter
    @Setter
    public static class TemplateItemRow {
        private long tiId;
        private long ttId;
        private String kind;
        private int seq;
        private String itemNm;
        private String requiredYn;
        private String formDocNo;
    }

    public record TemplateItemForm(String kind, String itemNm, String requiredYn, String formDocNo) {
    }

    // ------------------------------------------------------------------ 산출물

    @Getter
    @Setter
    public static class DelivRow {
        private long delivId;
        private long projId;
        private long taskId;
        private int seq;
        private String delivNm;
        private String requiredYn;
        private String formDocNo;
        private Long ownerUserId;
        private String ownerLoginId;
        private String ownerNm;
        /** 저장한 기한(없으면 작업 계획 종료일) */
        private LocalDate dueDate;
        private String status;
        private String refType;
        private Long refId;
        private String fileName;
        private String waiveReason;
        private String decisionNote;
        private String decidedBy;
        private OffsetDateTime decidedAt;
        private String submittedBy;
        private OffsetDateTime submittedAt;
        private int subCnt;
        private int rowVer;
        /** 아래는 조회 때 채움 — 작업 정보·단계(뿌리 작업)·실제 기한·지연 */
        private String taskNm;
        private String taskType;
        private Long taskOwnerUserId;
        private LocalDate taskPlanEnd;
        private String wbsNo;
        private String phaseWbs;
        private String phaseNm;
        private LocalDate due;
        private boolean late;
        private boolean canSubmit;
        /** QMS 문서 연결 칸(ref_type QMS_DOC_REV) — 조회 때 DocService 로 채움(D6) */
        private String docNo;
        private String docNm;
        private String docRev;
        private String docStatus;
        /** PPAP 연결 칸(ref_type PLM_PPAP) — 조회 때 EcoLookup 으로 채움(D18): ECO 번호·PPAP 상태 */
        private String ppapEcoNo;
        private String ppapStatus;
    }

    /** PPAP 연결 — 프로젝트 ECO 의 패키지(입력 없음) */
    public record PpapLinkForm(String note, Integer rowVer) {
    }

    /** APQP 연결(docs/41 A3-1) — kind = FMEA·CP, 번호 필수, 리비전 비우면 확정본 → 없으면 작성·결재 중 리비전 */
    public record ApqpLinkForm(String kind, String no, Integer rev, String note, Integer rowVer) {
    }

    /** QMS 문서 연결 — 문서 번호 필수, 리비전 비우면 배포본 → 없으면 작성·결재 중 리비전 */
    public record DocLinkForm(String docNo, String rev, String note, Integer rowVer) {
    }

    /** 연결 결과 — 상태(SUBMITTED·APPROVED)와 연결한 리비전 */
    public record DocLinkResult(String status, long docRevId, String docNo, String rev, String docStatus) {
    }

    /** 신규는 taskId 필수, 수정은 rowVer 필수(작업은 못 바꾼다) */
    public record DelivForm(Long taskId, String delivNm, String requiredYn, String formDocNo, String ownerLoginId, String dueDate, Integer rowVer) {
    }

    /** action = APPROVE / REJECT / WAIVE / UNWAIVE. REJECT·WAIVE 는 note 필수 */
    public record DecideForm(String action, String note, Integer rowVer) {
    }

    @Getter
    @Setter
    public static class HistRow {
        private long dhId;
        private String action;
        private Long fileId;
        private String fileName;
        /** LINK 행: 연결한 QMS 문서 리비전과 "문서 번호 리비전" (조회 때 채움) */
        private Long docRevId;
        /** LINK 행: 연결한 PPAP 패키지(PM7 ②) */
        private Long ppapId;
        /** LINK 행: 연결한 PFMEA·관리계획서 리비전(docs/41 A3-1) */
        private Long fmeaId;
        private Long cpId;
        private String docLabel;
        private String note;
        private OffsetDateTime createdAt;
        private String createdBy;
    }

    /** 단계(뿌리 작업)별 준비율 = (승인 + 면제) / 필수 */
    public record PhaseReady(long phaseTaskId, String wbsNo, String phaseNm, int reqCnt, int readyCnt, int pct) {
    }

    /** 칸 추가 때 고를 작업(WBS 순서) */
    public record TaskOption(long taskId, String wbsNo, String taskNm, String taskType) {
    }

    public record DelivView(ProjectRow project, List<DelivRow> rows, List<PhaseReady> phases, LocalDate today, boolean canManage,
            boolean canApprove, List<TaskOption> tasks) {
    }

    // ------------------------------------------------------------------ 게이트 심사

    /** 게이트 목록 한 행 — 게이트 작업 + 마지막 심사 + 지금 준비율 */
    @Getter
    @Setter
    public static class GateRow {
        private long taskId;
        private String wbsNo;
        private String taskNm;
        private LocalDate planStart;
        private String taskStatus;
        private Long reviewId;
        private Integer reviewNo;
        private String reviewStatus;
        private String decision;
        private int reqCnt;
        private int readyCnt;
    }

    @Getter
    @Setter
    public static class ReviewRow {
        private long reviewId;
        private long projId;
        private long gateTaskId;
        private int reviewNo;
        private LocalDate reviewDate;
        private String decision;
        private String status;
        private String summary;
        private Integer reqCnt;
        private Integer readyCnt;
        private Long aprId;
        private String aprStatus;
        private Long aprRequesterId;
        private OffsetDateTime decidedAt;
        private String decidedBy;
        private int rowVer;
        private OffsetDateTime createdAt;
        private String createdBy;
    }

    @Getter
    @Setter
    public static class CheckRow {
        private long gcId;
        private int seq;
        private String checkNm;
        private String result;
        private String note;
    }

    @Getter
    @Setter
    public static class CondRow {
        private long condId;
        private int seq;
        private String title;
        private Long ownerUserId;
        private String ownerLoginId;
        private String ownerNm;
        private LocalDate dueDate;
        private Long issueId;
        private String issueNo;
    }

    public record CheckIn(String checkNm, String result, String note) {
    }

    public record CondIn(String title, String ownerLoginId, String dueDate) {
    }

    /** 작성 중 심사 한 벌 저장 — 점검표·조건은 보낸 목록으로 바뀐다 */
    public record ReviewForm(String reviewDate, String decision, String summary, List<CheckIn> checks, List<CondIn> conds,
            Integer rowVer) {
    }

    public record LineIn(Long userId, String lineType) {
    }

    /** 결재 상신(정책 PMS_GATE_APPROVAL=Y) — 결재선 필수. N 이면 lines 없이 바로 결정(APPROVE 권한) */
    public record SubmitForm(List<LineIn> lines, String title, Integer rowVer) {
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

    public record Approval(Long aprId, String status, boolean myTurn, boolean canWithdraw, List<AprLineRow> lines) {
    }

    /**
     * 심사 상세 — scope = 이 게이트가 보는 산출물(게이트의 단계 = 부모 작업 아래 전부, 뿌리 게이트면 WBS 앞쪽 전부),
     * canEdit = 작성 중이고 PM·PMO, approvalRequired = 정책 PMS_GATE_APPROVAL
     */
    public record ReviewDetail(ReviewRow review, GateRow gate, String projNo, String projNm, List<CheckRow> checks,
            List<CondRow> conds, List<DelivRow> scope, Approval approval, boolean canEdit, boolean approvalRequired,
            boolean canDecideDirect) {
    }
}
