package io.smarthappy.module.pms;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.module.pms.GateDtos.Approval;
import io.smarthappy.module.pms.PmsDtos.PushMove;
import lombok.Getter;
import lombok.Setter;

/** 담당자 일정변경 요청 → 승인 DTO (docs/32 4.13, docs/48 R-3) */
public final class TaskChgDtos {

    private TaskChgDtos() {
    }

    /**
     * 요청 — 새 계획일·사유, rowVer = 화면이 본 작업 row_ver(다르면 409), approverId = 결재자(비우면 그 프로젝트 PM, 정책 N 이면 무시)
     */
    public record ChgForm(String newStart, String newEnd, String reason, Integer rowVer, Long approverId) {
    }

    /** PM 바로 처리(정책 N 으로 만든 요청) — approve = 승인(false = 반려), rowVer = 요청 row_ver */
    public record DecideForm(Boolean approve, String note, Integer rowVer) {
    }

    /** 요청자 회수(바로 처리 요청 — 결재 중 요청은 결재함 회수로) */
    public record WithdrawForm(Integer rowVer) {
    }

    /** 홈 카드 pms-chg-todo 항목 */
    @Getter
    @Setter
    public static class PmTodoRow {
        private long reqId;
        private String projNo;
        /** 작업이 지워졌으면 null */
        private String taskNm;
        private String requesterNm;
        private LocalDate newStart;
        private LocalDate newEnd;
        private OffsetDateTime createdAt;
    }

    @Getter
    @Setter
    public static class ChgRow {
        private long reqId;
        private long projId;
        private long taskId;
        private String taskNm;
        private long requesterId;
        private String requesterNm;
        private LocalDate beforeStart;
        private LocalDate beforeEnd;
        private LocalDate newStart;
        private LocalDate newEnd;
        private String reason;
        private int taskRowVer;
        private String status;
        private Long aprId;
        private String aprStatus;
        private Long aprRequesterId;
        private OffsetDateTime decidedAt;
        private String decidedBy;
        private String decideNote;
        private int rowVer;
        private OffsetDateTime createdAt;
        /** 화면용 — WBS 번호(작업이 지워졌으면 null) */
        private String wbsNo;
    }

    /**
     * 요청 상세 — preview = 이 변경을 적용하면 밀릴 후행(기존 후행 밀기와 같은 계산, 변경 전에도 밀릴 것은 뺌),
     * fixed = 위반이 되지만 완료·취소라 움직이지 않는 후행, taskChanged = 그사이 작업이 바뀌어 승인하면 409,
     * canDecide = 바로 처리 요청이고 관리하는 사람, canWithdraw = 바로 처리 요청의 요청자
     */
    public record ChgDetail(ChgRow req, String projNo, String projNm, LocalDate curStart, LocalDate curEnd, List<PushMove> preview,
            List<PushMove> fixed, boolean taskChanged, Approval approval, boolean canDecide, boolean canWithdraw) {
    }

    /** 작업 폼에서 요청을 열 때 — approvalRequired = 정책, pmUserId = 결재선 기본값, open = 이 작업의 열린 요청(없으면 null) */
    public record ChgInfo(boolean approvalRequired, long pmUserId, String pmUserNm, ChgRow open) {
    }
}
