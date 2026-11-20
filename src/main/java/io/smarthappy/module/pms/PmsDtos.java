package io.smarthappy.module.pms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** PMS 프로젝트·WBS·템플릿 DTO (docs/32 PM1) */
public final class PmsDtos {

    private PmsDtos() {
    }

    // ------------------------------------------------------------------ 템플릿

    @Getter
    @Setter
    public static class TemplateRow {
        private long tmplId;
        private String tmplCd;
        private String tmplNm;
        private String projType;
        private String anchor;
        private String useYn;
        private String remark;
        private int rowVer;
        private int taskCnt;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    /** 템플릿 grid 정렬 허용 목록 */
    public enum TemplateSort implements SortField {
        TMPL_CD("tmplCd"), TMPL_NM("tmplNm"), PROJ_TYPE("projType"), ANCHOR("anchor"), TASK_CNT("taskCnt"), USE_YN("useYn");

        private final String field;

        TemplateSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** grid 응답 한 쪽 */
    public record TemplatePage(List<TemplateRow> list, Long total) {
    }

    public record TemplateForm(String tmplCd, String tmplNm, String projType, String anchor, String useYn, String remark,
            Integer rowVer) {
    }

    @Getter
    @Setter
    public static class TmplTaskRow {
        private long ttId;
        private long tmplId;
        private Long parentId;
        private int seq;
        private String taskType;
        private String taskNm;
        private int offsetDays;
        private int durationDays;
        /** 표준 공수(시간, docs/48 R-2) — 프로젝트를 만들 때 잎 작업의 계획 공수로 복사. TASK 만 */
        private BigDecimal stdHours;
        private String isSummary;
        private String remark;
        private int rowVer;
        /** 아래는 조회 때 계산 — 레벨(1~), WBS 번호, 요약이면 자식에서 계산한 시작·끝(기준일 대비) */
        private int lvl;
        private String wbsNo;
        private int startDay;
        private int endDay;
    }

    /** afterId = 같은 부모 안에서 이 작업 바로 뒤에 넣는다(없으면 맨 끝) */
    public record TmplTaskForm(Long parentId, Long afterId, String taskType, String taskNm, Integer offsetDays,
            Integer durationDays, String remark, Integer rowVer, BigDecimal stdHours) {
    }

    public record TmplTaskValue(String taskType, String taskNm, int offsetDays, int durationDays, String remark, BigDecimal stdHours) {
    }

    // ------------------------------------------------------------------ 프로젝트

    @Getter
    @Setter
    public static class ProjectRow {
        private long projId;
        private String projNo;
        private String projNm;
        private String projType;
        private String custCd;
        private String custNm;
        private String plantCd;
        private long pmUserId;
        private String pmLoginId;
        private String pmUserNm;
        private String tmplCd;
        private LocalDate sopDate;
        private LocalDate planStart;
        private LocalDate planEnd;
        private int progressPct;
        private String status;
        private String statusReason;
        private LocalDate closedOn;
        private String remark;
        /** PLM 변경지시(PM7, D17) — 번호·제목·상태·고객 승인은 상세 조회 때 EcoService 로 채움 */
        private Long ecoId;
        private String ecoNo;
        private String ecoTitle;
        private String ecoStatus;
        private String ecoCustApproval;
        private int rowVer;
        private int taskCnt;
        /** 오늘 기준 지연된 잎 작업 수(계획 종료 < 오늘, 미완료) */
        private int lateCnt;
        private OffsetDateTime updatedAt;
        private String updatedBy;
        /** 상세에서만 채움 */
        private List<ItemRef> items;
        /** 상세에서만 — 지금 사용자가 머리·WBS 를 고칠 수 있나(PM 또는 APPROVE) */
        private boolean canManage;
        /** 착수·종료 결재(docs/32 4.16) — 결재 중 목표 상태(ACTIVE·CLOSED)·결재 id, 정책 PMS_PROJ_APPROVAL 켬 */
        private String aprTo;
        private Long aprId;
        private boolean approvalOn;
        /** 착수·종료 결재선 기본값(docs/32 4.16 S6, P-3·D-1) = 보는 사람의 부서장 — 정책 켬·관리 가능일 때만, 없으면 null(상신자가 고름) */
        private Long aprDefaultUserId;
        private String aprDefaultLoginId;
        private String aprDefaultUserNm;
    }

    @Getter
    @Setter
    public static class ItemRef {
        private long itemId;
        private String itemCd;
        private String itemNm;
    }

    /** 신규 때만: tmplCd(선택) + startDate(템플릿 기준 START 일 때 시작일). 기준이 SOP 면 sopDate 로 */
    public record ProjectForm(String projNm, String projType, String custCd, String plantCd, String pmLoginId,
            String sopDate, String remark, List<String> itemCds, String tmplCd, String startDate, Integer rowVer) {
    }

    public record ProjectValue(String projNm, String projType, Long custId, String plantCd, long pmUserId,
            LocalDate sopDate, String remark) {
    }

    /** ECO 연결 — ecoNo 비우면 해제 */
    /** 프로젝트 목록 조건(목록·grid·건수가 같은 SQL 조각 projWhere 를 쓴다). keywordLike 는 이미 이스케이프한 LIKE 값 */
    public record ProjectFilter(String keywordLike, String projType, List<String> statuses, Long pmUserId) {
    }

    /** grid 정렬 허용 목록 — enum 이름 = 매퍼 choose 키, field = 화면 필드 */
    public enum ProjectSort implements SortField {
        PROJ_NO("projNo"), PROJ_NM("projNm"), PROJ_TYPE("projType"), CUST_NM("custNm"), PM_NM("pmUserNm"), SOP_DATE("sopDate"),
        PLAN_START("planStart"), PLAN_END("planEnd"), PROGRESS("progressPct"), LATE_CNT("lateCnt"), STATUS("status");

        private final String field;

        ProjectSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /**
     * 프로젝트 고르기 grid(산출물·게이트 화면, docs/40 §10.2 D1) 정렬 허용 목록 — 보이는 다섯 칸만.
     * enum 이름은 {@link ProjectSort} 와 같게 두어 매퍼 findProjectPage 의 choose 키를 그대로 쓴다
     */
    public enum ProjectPickSort implements SortField {
        PROJ_NO("projNo"), PROJ_NM("projNm"), CUST_NM("custNm"), SOP_DATE("sopDate"), STATUS("status");

        private final String field;

        ProjectPickSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** grid 응답 한 쪽 */
    public record ProjectPage(List<ProjectRow> list, Long total) {
    }

    public record EcoLinkForm(String ecoNo, Integer rowVer) {
    }

    public record StatusForm(String status, String reason, Integer rowVer) {
    }

    // ------------------------------------------------------------------ 작업

    @Getter
    @Setter
    public static class TaskRow {
        private long taskId;
        private long projId;
        private Long parentId;
        private int seq;
        private String taskType;
        private String taskNm;
        private Long ownerUserId;
        private String ownerLoginId;
        private String ownerNm;
        private LocalDate planStart;
        private LocalDate planEnd;
        private LocalDate actStart;
        private LocalDate actEnd;
        private int progressPct;
        private String status;
        private String isSummary;
        private String remark;
        /** 계획 공수(시간, docs/48 R-2) — 잎 작업 입력, 요약은 자식 합(취소 제외, 서버 계산). TASK 만 */
        private BigDecimal planHours;
        private int rowVer;
        /** 아래는 조회 때 계산 */
        /** 실적 공수(시간) — 확정(CONFIRMED)·미확정(DRAFT) 따로, 요약은 아래 작업 합 */
        private BigDecimal confHours;
        private BigDecimal draftHours;
        private int lvl;
        private String wbsNo;
        private boolean late;
        private boolean canEdit;
        /** PM2: 최신 기준선의 계획일(기준선에 없으면 null), 지연 일수 = 지금 종료 − 기준선 종료, 선행 의존 위반 */
        private LocalDate baseStart;
        private LocalDate baseEnd;
        private Integer slipDays;
        private boolean depViolation;
        /** PM3: 앞 게이트 통과 전에 착수했으면 그 게이트 이름(정책 WARN 기록) */
        private String gateWarn;
    }

    /** 선행 미완 통과 기록(docs/32 4.21) — 정책 PMS_DEP_ENFORCE=BLOCK 에서 PM 이 사유로 실적 시작 */
    @Getter
    @Setter
    public static class DepWaiveRow {
        private long waiveId;
        private String reason;
        private String openPreds;
        private String createdBy;
        private java.time.OffsetDateTime createdAt;
    }

    /** depReason = 정책 PMS_DEP_ENFORCE=BLOCK 에서 PM 이 선행 미완 작업에 실적 시작을 넣을 때의 사유(docs/32 4.21) */
    public record TaskForm(Long parentId, Long afterId, String taskType, String taskNm, String ownerLoginId,
            String planStart, String planEnd, String actStart, String actEnd, Integer progressPct, String status,
            String remark, Integer rowVer, String planReason, BigDecimal planHours, String depReason) {
    }

    public record TaskValue(String taskType, String taskNm, Long ownerUserId, LocalDate planStart, LocalDate planEnd,
            LocalDate actStart, LocalDate actEnd, int progressPct, String status, String remark, BigDecimal planHours) {
    }

    /** 작업별 실적 공수(task_id null = 프로젝트에만 올린 공수) */
    @Getter
    @Setter
    public static class TaskHours {
        private Long taskId;
        private BigDecimal confHours;
        private BigDecimal draftHours;
    }

    /**
     * 프로젝트 공수 요약(docs/48 R-2) — 계획 = 잎 작업 계획 합(취소 제외, 없으면 null), 확정·미확정 = 그 프로젝트 공수 전체(작업 없이 올린 것 포함),
     * 소진율 = 확정 ÷ 계획(계획 없거나 0 이면 null), 남은 = 계획 − 확정(넘으면 음수), hoursPerDay = MD 전환(회사 정책)
     */
    public record EffortSum(BigDecimal planHours, BigDecimal confHours, BigDecimal draftHours, Integer burnPct, BigDecimal remainHours,
            int hoursPerDay) {
    }

    /** op = UP / DOWN / INDENT / OUTDENT */
    public record MoveForm(String op, Integer rowVer) {
    }

    /** 간트 한 번에: 프로젝트 요약 + 작업(WBS 순서) + 오늘(회사 시간대) */
    /** 간트 한 번에 — PM2: 의존(위반 표시)·최신 기준선 번호·프로젝트 지연 일수(기준선 대비, 없으면 null) */
    public record TaskView(ProjectRow project, List<TaskRow> tasks, LocalDate today, List<DepRow> deps, BaselineRow baseline,
            Integer projectSlipDays, EffortSum effort) {
    }

    // ------------------------------------------------------------------ PM2 의존·기준선·밀기

    @Getter
    @Setter
    public static class DepRow {
        private long depId;
        private long predTaskId;
        private long succTaskId;
        private int lagDays;
        /** FS·SS·FF (docs/32 4.27) */
        private String depType = "FS";
        private boolean violated;
    }

    /** depReason = 정책 BLOCK 에서 이미 시작한 작업에 끝나지 않은 선행을 걸 때 PM 사유(docs/32 4.21 E7, P-b) */
    public record DepForm(Long predTaskId, Long succTaskId, Integer lagDays, String depReason, String depType) {
    }

    @Getter
    @Setter
    public static class BaselineRow {
        private long baselineId;
        private int baselineNo;
        private String reason;
        private int taskCnt;
        private LocalDate planEnd;
        private OffsetDateTime createdAt;
        private String createdBy;
    }

    public record BaselineForm(String reason) {
    }

    /** 기준선 스냅샷 한 행(조회용) */
    @Getter
    @Setter
    public static class BaseTask {
        private long taskId;
        private String wbsNo;
        private String taskNm;
        private String isSummary;
        private LocalDate planStart;
        private LocalDate planEnd;
    }

    public record PushMove(long taskId, String wbsNo, String taskNm, LocalDate oldStart, LocalDate oldEnd, LocalDate newStart,
            LocalDate newEnd, int shiftDays) {
    }

    /** moves = 뒤로 밀 작업, fixed = 위반이지만 완료·취소라 움직이지 않는 후행 */
    public record PushPlan(List<PushMove> moves, List<PushMove> fixed, boolean needsReason) {
    }

    public record PushRef(Long taskId, String newStart) {
    }

    public record PushForm(List<PushRef> expected, String reason) {
    }

    @Getter
    @Setter
    public static class PlanChangeRow {
        private long pcId;
        private int baselineNo;
        private LocalDate beforeStart;
        private LocalDate beforeEnd;
        private LocalDate afterStart;
        private LocalDate afterEnd;
        private String source;
        private String reason;
        private OffsetDateTime createdAt;
        private String createdBy;
    }

    public record TmplTaskView(TemplateRow template, List<TmplTaskRow> tasks, List<TmplDepRow> deps) {
    }

    /** 템플릿 선후행(docs/32 4.18) — FS + lag, 잎 작업끼리 */
    @Getter
    @Setter
    public static class TmplDepRow {
        private long tdepId;
        private long predTtId;
        private long succTtId;
        private int lagDays;
        private String depType = "FS";
    }

    public record TmplDepForm(Long predTtId, Long succTtId, Integer lagDays, String depType) {
    }

    /** 프로젝트 → 새 템플릿(docs/32 4.18) — 유형·이름은 비우면 원본 프로젝트 것, 기준일 = 시작일 */
    public record TmplFromProjectForm(String tmplCd, String tmplNm, String projType, String remark) {
    }

    // ------------------------------------------------------------------ 선택지

    @Getter
    @Setter
    public static class CodeOption {
        private String code;
        private String name;
    }
}
