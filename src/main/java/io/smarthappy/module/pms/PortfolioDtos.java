package io.smarthappy.module.pms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import io.smarthappy.module.pms.GateDtos.GateRow;
import io.smarthappy.module.pms.GateDtos.PhaseReady;
import io.smarthappy.module.pms.PmsDtos.ProjectRow;
import io.smarthappy.module.pms.WorkDtos.IssueRow;
import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** PMS 5단계 홈·포트폴리오·보고서 DTO (docs/32 PM5) */
public final class PortfolioDtos {

    private PortfolioDtos() {
    }

    /** 포트폴리오 grid 정렬 허용 목록 — 계산 칸(SOP 까지·기준선 대비·지연·준비율·열린 이슈·다음 게이트)도 포함, 빈 값은 끝 */
    public enum PortSort implements SortField {
        PROJ_NO("projNo"), PROJ_NM("projNm"), CUST_NM("custNm"), PM_NM("pmUserNm"), PHASE_NM("phaseNm"), SOP_DATE("sopDate"),
        SOP_DAYS("sopDays"), SLIP_DAYS("slipDays"), LATE_CNT("lateCnt"), READY_PCT("readyPct"), OPEN_ISSUES("openIssueCnt"),
        NEXT_GATE("nextGateNm"), PROGRESS("progressPct"), PLAN_HOURS("planHours"), CONF_HOURS("confHours"), BURN_PCT("burnPct"),
        REMAIN_HOURS("remainHours");

        private final String field;

        PortSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** grid 응답 한 쪽 */
    public record PortPage(List<PortRow> list, Long total) {
    }

    /** 포트폴리오 한 행 — 단계·SOP 까지 남은 날·지연·준비율·열린 이슈 */
    @Getter
    @Setter
    public static class PortRow {
        private long projId;
        private String projNo;
        private String projNm;
        private String projType;
        private String custNm;
        private String pmUserNm;
        private String status;
        private LocalDate sopDate;
        /** SOP − 오늘(음수 = SOP 지남) */
        private Integer sopDays;
        private LocalDate planEnd;
        private int progressPct;
        private String phaseNm;
        private String nextGateNm;
        private LocalDate nextGateDate;
        /** 최신 기준선 대비 프로젝트 끝 지연 일수(기준선 없으면 null) */
        private Integer slipDays;
        private int lateCnt;
        private int reqCnt;
        private int readyCnt;
        /** 준비율 = 준비/필수(필수 없으면 null) */
        private Integer readyPct;
        private int openIssueCnt;
        private int highIssueCnt;
        private int gateInApprovalCnt;
        /**
         * 공수(시간, docs/48 R-2) — 계획 = 잎 작업 합(취소 제외, 없으면 null), 확정·미확정 = 프로젝트 공수 전체(확정만 실적),
         * 소진율 = 확정 ÷ 계획(계획 없거나 0 이면 null), 남은 = 계획 − 확정(넘으면 음수)
         */
        private BigDecimal planHours;
        private BigDecimal confHours;
        private BigDecimal draftHours;
        private Integer burnPct;
        private BigDecimal remainHours;
    }

    /** 내 할 일 — 작업 한 줄 */
    @Getter
    @Setter
    public static class MyTask {
        private long projId;
        private String projNo;
        private long taskId;
        private String taskNm;
        private LocalDate planEnd;
        private String status;
        private int progressPct;
        private boolean late;
        /** 줄 편집(docs/32 4.19) — 실적·종류·row_ver */
        private LocalDate planStart;
        private LocalDate actStart;
        private LocalDate actEnd;
        private String taskType;
        private int rowVer;
    }

    @Getter
    @Setter
    public static class MyDeliv {
        private long projId;
        private String projNo;
        private long delivId;
        private String delivNm;
        private String taskNm;
        private LocalDate due;
        private String status;
        private boolean late;
    }

    /**
     * 내 할 일 — 기한 임박 기준(정책 PMS_DUE_SOON_DAYS), 확정 안 한 지난 평일(최근 7일).
     * 세 목록은 상한(50)까지만 — *Cnt = 같은 조건 전체 건수(홈 카드 pms-my-work 숫자와 같은 count, QA H-a). 목록이 상한보다 적으면 = 목록 건수
     */
    public record MyTodo(LocalDate today, int dueSoonDays, List<MyTask> tasks, List<MyDeliv> delivs, List<IssueRow> issues,
            List<LocalDate> unconfirmedDays, long taskCnt, long delivCnt, long issueCnt) {
    }

    /** 하루 요약 한 사람 — 지연·임박(내 할 일과 같은 조건) */
    @Getter
    @Setter
    public static class DigestRow {
        private long userId;
        private long lateCnt;
        private long soonCnt;
    }

    /** 포트폴리오 차트(docs/32 4.15) — 상태 하나: 프로젝트 수, 지연 작업이 있는 프로젝트 수, 기준선보다 끝이 늦어진 프로젝트 수 */
    @Getter
    @Setter
    public static class ChartRow {
        private String status;
        private int projCnt;
        private int lateCnt;
        private int slipCnt;
    }

    /** 홈 카드 pms-portfolio 수·합(목록과 같은 조건 전체) */
    @Getter
    @Setter
    public static class CardStats {
        private int activeCnt;
        private int delayedCnt;
        private int gateCnt;
        private int highCnt;
        private int overBurnCnt;
    }

    /** 연도별(docs/32 4.20, P-11) — 착수 = 계획 시작 연도(작성 중도), 종료 = 종료(CLOSED) 프로젝트의 closed_on 연도 */
    @Getter
    @Setter
    public static class YearRow {
        private int year;
        private int startCnt;
        private int closeCnt;
    }

    /** 유형별 — 조건 안 프로젝트 수 · 그중 진행 중 */
    @Getter
    @Setter
    public static class TypeRow {
        private String projType;
        private int projCnt;
        private int activeCnt;
    }

    /** 연도·유형 차트 — noPlanCnt = 작업이 없어 계획 시작이 없는 프로젝트(연도 막대에서 빠짐, 숫자로만) */
    public record TrendView(List<YearRow> years, List<TypeRow> types, int noPlanCnt) {
    }

    @Getter
    @Setter
    public static class HoursRow {
        private String categoryCd;
        private BigDecimal confirmedHours;
        private BigDecimal draftHours;
    }

    @Getter
    @Setter
    public static class SlipTask {
        private long taskId;
        private String wbsNo;
        private String taskNm;
        private LocalDate baseEnd;
        private LocalDate planEnd;
        private int slipDays;
        private String status;
    }

    /** 프로젝트 보고서(인쇄용 화면) — 한 번에 */
    public record Report(ProjectRow project, PortRow summary, List<PhaseReady> phases, List<GateRow> gates, List<SlipTask> lateTasks,
            List<SlipTask> slipTasks, List<IssueRow> openIssues, List<HoursRow> hours, LocalDate today, int hoursPerDay) {
    }
}
