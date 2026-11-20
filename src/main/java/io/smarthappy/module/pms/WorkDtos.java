package io.smarthappy.module.pms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** PMS 4단계 공수·이슈 DTO (docs/32 PM4) */
public final class WorkDtos {

    private WorkDtos() {
    }

    // ------------------------------------------------------------------ 공수

    @Getter
    @Setter
    public static class LogRow {
        private long wlId;
        private long userId;
        private LocalDate workDay;
        private long projId;
        private String projNo;
        private String projNm;
        private Long taskId;
        private String wbsTaskNm;
        private String categoryCd;
        private BigDecimal hours;
        private String entryMethod;
        private String status;
        private OffsetDateTime confirmedAt;
        private String reopenReason;
        private String note;
        private int rowVer;
    }

    /** 미리 채움 후보 = 내가 담당인 진행 중(또는 오늘 계획에 걸린) 잎 작업 */
    @Getter
    @Setter
    public static class Suggestion {
        private long projId;
        private String projNo;
        private String projNm;
        private long taskId;
        private String taskNm;
        private LocalDate planStart;
        private LocalDate planEnd;
        private String status;
    }

    /** 하루: 합계(글자), 상태 NONE(행 없음)·DRAFT·CONFIRMED */
    public record DayView(LocalDate day, String total, String status, boolean future, List<LogRow> rows) {
    }

    public record WeekView(LocalDate from, LocalDate to, LocalDate today, int maxHours, List<DayView> days,
            List<Suggestion> suggestions, List<ProjOption> projects) {
    }

    /** 기록할 수 있는 프로젝트(진행 중 셋) */
    public record ProjOption(long projId, String projNo, String projNm) {
    }

    /** hours 는 글자(10진) — 서버가 BigDecimal 로 */
    public record LogIn(Long wlId, Long projId, Long taskId, String categoryCd, String hours, String note, Integer rowVer) {
    }

    public record DayForm(String day, List<LogIn> rows) {
    }

    public record DayAction(String day, String reason) {
    }

    /** 프로젝트 공수 요약(PM 조회) — 사용자 × 분류, 확정·작성 중 따로 */
    @Getter
    @Setter
    public static class SummaryRow {
        private long userId;
        private String loginId;
        private String userNm;
        private String categoryCd;
        private BigDecimal confirmedHours;
        private BigDecimal draftHours;
        private LocalDate firstDay;
        private LocalDate lastDay;
    }

    // ------------------------------------------------------------------ 이슈

    @Getter
    @Setter
    public static class IssueRow {
        private long issueId;
        private String issueNo;
        private long projId;
        private String projNo;
        private String projNm;
        private Long taskId;
        private String taskNm;
        private String title;
        private String detail;
        private String severity;
        private String status;
        /** 유형(공통코드 PMS_ISSUE_TYPE)·종료 방식(DONE·REJECTED, 종료 때만) — docs/32 4.15 */
        private String issueType;
        private String closeKind;
        private Long ownerUserId;
        private String ownerLoginId;
        private String ownerNm;
        private LocalDate dueDate;
        private String resolution;
        private LocalDate resolvedOn;
        private String source;
        private Long gateReviewId;
        private Integer gateReviewNo;
        private String objType;
        private Long objId;
        private String objCd;
        private String objNm;
        /** "시정조치로 올리기" 로 만든 QMS 시정조치(PM6, D13) — 번호·상태는 상세 조회 때 CapaService 로 채움 */
        private Long capaId;
        private String capaNo;
        private String capaStatus;
        private int rowVer;
        private OffsetDateTime createdAt;
        private String createdBy;
        private OffsetDateTime updatedAt;
        private String updatedBy;
        /** 조회 때 — 기한 지남(열린 이슈), 내가 고칠 수 있음·닫을 수 있음 */
        private boolean late;
        private boolean canEdit;
        private boolean canClose;
    }

    /** status: 비우면 열린 셋(OPEN·IN_PROGRESS·RESOLVED), ALL, 또는 하나. objType+objCd = 연결 객체로 찾기(품목 화면 링크) */
    public record IssueSearch(Long projId, String status, String severity, Boolean mine, String objType, String objCd,
            String keyword, List<String> columns, String issueType) {
    }

    /** 이슈 grid 정렬 허용 목록 — SEVERITY 는 높음 → 낮음 순위(코드 글자 순 아님) */
    public enum IssueSort implements SortField {
        ISSUE_NO("issueNo"), TITLE("title"), PROJ_NO("projNo"), SEVERITY("severity"), STATUS("status"), OWNER_NM("ownerNm"),
        DUE_DATE("dueDate");

        private final String field;

        IssueSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** grid 응답 한 쪽 */
    public record IssuePage(List<IssueRow> list, Long total) {
    }

    public record IssueForm(Long projId, Long taskId, String title, String detail, String severity, String ownerLoginId,
            String dueDate, String objType, String objCd, Integer rowVer, String issueType) {
    }

    /** PFMEA 조치 → 이슈(A3-5) — projId 는 FMEA 에 프로젝트가 없을 때만(있으면 같아야 함) */
    public record ApqpIssueForm(Long projId) {
    }

    /** 조치에 연결된 이슈(A3-5 — FMEA 화면 표시용) */
    @Getter
    @Setter
    public static class ApqpIssueRow {
        private long actionId;
        private long issueId;
        private String issueNo;
        private String status;
        private long projId;
        private String projNo;
    }

    /** 시정조치로 올리기 — rowVer 만 */
    public record EscalateForm(Integer rowVer) {
    }

    public record IssueStatusForm(String status, String resolution, Integer rowVer) {
    }

    /** 이슈 첨부 잠금 판정 */
    @Getter
    @Setter
    public static class IssueLockRow {
        private String issueStatus;
        private String projStatus;
    }

    @Getter
    @Setter
    public static class ObjRef {
        private String objType;
        private long objId;
        private String objCd;
        private String objNm;
    }
}
