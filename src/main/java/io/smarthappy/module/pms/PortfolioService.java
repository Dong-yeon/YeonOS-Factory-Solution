package io.smarthappy.module.pms;

import static io.smarthappy.platform.common.text.VisibleText.isBlank;
import static io.smarthappy.module.pms.PmsInputs.fieldError;
import static io.smarthappy.module.pms.PmsInputs.me;
import static io.smarthappy.module.pms.PmsInputs.upper;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.pms.PmsDtos.BaseTask;
import io.smarthappy.module.pms.PmsDtos.BaselineRow;
import io.smarthappy.module.pms.PmsDtos.ProjectRow;
import io.smarthappy.module.pms.PmsDtos.TaskRow;
import io.smarthappy.module.pms.PortfolioDtos.MyTodo;
import io.smarthappy.module.pms.PortfolioDtos.PortPage;
import io.smarthappy.module.pms.PortfolioDtos.PortRow;
import io.smarthappy.module.pms.PortfolioDtos.PortSort;
import io.smarthappy.module.pms.PortfolioDtos.Report;
import io.smarthappy.module.pms.PortfolioDtos.SlipTask;
import io.smarthappy.module.pms.WbsTree.Placed;
import io.smarthappy.module.pms.WorkDtos.IssueSearch;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * PMS 홈·포트폴리오·프로젝트 보고서 (pms-home) — docs/32 PM5.
 * 포트폴리오 = 프로젝트별 단계·SOP 까지 남은 날·지연(기준선 대비)·지연 작업·준비율·열린 이슈. 엑셀 = 같은 조건·같은 순서.
 * 내 할 일 = 내 작업(지연·임박)·내 산출물(미제출·반려, 임박)·내 이슈·확정 안 한 지난 평일. 보고서 = 한 프로젝트를 한 화면(인쇄용)
 */
@Service
@RequiredArgsConstructor
public class PortfolioService {

    static final String PROG = "pms-home";
    private static final int LIST_LIMIT = 500;
    private static final int TODO_LIMIT = 50;
    private static final int REPORT_ROWS = 20;

    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("projNo", "pms.col.projNo", 110),
            ColumnSpec.text("projNm", "pms.col.projNm", 200),
            ColumnSpec.code("projType", "pms.col.projType", "PMS_PROJ_TYPE", 90),
            ColumnSpec.text("custNm", "pms.col.cust", 120),
            ColumnSpec.text("pmUserNm", "pms.col.pm", 90),
            ColumnSpec.text("phaseNm", "pms.col.phase", 150),
            ColumnSpec.of("sopDate", "pms.col.sopDate", ColumnType.DATE, 100),
            ColumnSpec.of("sopDays", "pms.col.sopDays", ColumnType.INT, 80),
            ColumnSpec.of("slipDays", "pms.col.slipDays", ColumnType.INT, 80),
            ColumnSpec.of("lateCnt", "pms.col.late", ColumnType.INT, 80),
            ColumnSpec.of("readyPct", "pms.col.readyPct", ColumnType.INT, 80),
            ColumnSpec.of("openIssueCnt", "pms.col.openIssues", ColumnType.INT, 80),
            ColumnSpec.text("nextGateNm", "pms.col.nextGate", 160),
            ColumnSpec.of("nextGateDate", "pms.col.nextGateDate", ColumnType.DATE, 100),
            ColumnSpec.of("progressPct", "pms.col.progress", ColumnType.INT, 70),
            ColumnSpec.dec("planHours", "pms.col.planHours", 1, 90),
            ColumnSpec.dec("confHours", "pms.col.confHours", 1, 90),
            ColumnSpec.dec("draftHours", "pms.col.draftHours", 1, 90),
            ColumnSpec.of("burnPct", "pms.col.burnPct", ColumnType.INT, 80),
            ColumnSpec.dec("remainHours", "pms.col.remainHours", 1, 90));

    private final PortfolioMapper pm;
    private final PmsMapper mapper;
    private final GateMapper gm;
    private final TaskService tasks;
    private final GateService gates;
    private final IssueService issues;
    private final PolicyService policy;
    private final ExcelExportService excel;
    private final BizClock clock;
    private final PmsDays days;

    private record Query(String like, String type, List<String> statuses, Long pmUserId) {
    }

    @Transactional(readOnly = true)
    public List<PortRow> portfolio(String keyword, String projType, String status, boolean mine) {
        Query q = query(keyword, projType, status, mine);
        return pm.findPortfolio(q.like, q.type, q.statuses, q.pmUserId, null, clock.today(), days.lateRef(), LIST_LIMIT);
    }

    /**
     * grid(UI 단계 3 ②, docs/32 4.11) — 목록과 같은 조건(portWhere)·같은 칸(portSelect) + 서버 페이징·정렬(허용 목록 {@link PortSort}).
     * 기본 정렬 = 목록과 같음(SOP 가까운 순, 없으면 끝 → 번호)
     */
    @Transactional(readOnly = true)
    public PortPage grid(String keyword, String projType, String status, boolean mine, Integer page, Integer size, List<String> sort,
            Boolean total) {
        Query q = query(keyword, projType, status, mine);
        PageQuery pq = PageQuery.parse(page, size, sort, total, PortSort.values(), policy.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("sopDate,asc", "projNo,asc"));
        LocalDate today = clock.today();
        List<PortRow> rows = pm.findPortfolioPage(q.like, q.type, q.statuses, q.pmUserId, null, today, days.lateRef(today), pq);
        return new PortPage(rows, pq.isWithTotal() ? pm.countPortfolio(q.like, q.type, q.statuses, q.pmUserId, null) : null);
    }

    /** 포트폴리오 차트(docs/32 4.15) — 목록과 같은 조건, 상태 순서 = 작성·진행·보류·종료·취소, 없는 상태는 0 줄 */
    @Transactional(readOnly = true)
    public List<PortfolioDtos.ChartRow> chart(String keyword, String projType, String status, boolean mine) {
        Query q = query(keyword, projType, status, mine);
        java.util.Map<String, PortfolioDtos.ChartRow> by = new java.util.HashMap<>();
        pm.portfolioChart(q.like, q.type, q.statuses, q.pmUserId, null, days.lateRef()).forEach(r -> by.put(r.getStatus(), r));
        List<String> order = q.statuses == null ? CHART_STATUSES : CHART_STATUSES.stream().filter(q.statuses::contains).toList();
        return order.stream().map(s -> by.computeIfAbsent(s, k -> {
            PortfolioDtos.ChartRow r = new PortfolioDtos.ChartRow();
            r.setStatus(k);
            return r;
        })).toList();
    }

    /**
     * 연도·유형 차트(docs/32 4.20, P-11) — 상태별 막대와 같은 조건. 착수 = 계획 시작 연도(작성 중 포함), 종료 = 종료 프로젝트의 종료일 연도
     * (조건이 "진행 중" 이면 종료 프로젝트가 빠져 종료 막대는 0 — 조건과 같은 숫자). 연도는 있는 해만, 오래된 순
     */
    @Transactional(readOnly = true)
    public PortfolioDtos.TrendView trend(String keyword, String projType, String status, boolean mine) {
        Query q = query(keyword, projType, status, mine);
        return new PortfolioDtos.TrendView(pm.portfolioYears(q.like, q.type, q.statuses, q.pmUserId, null),
                pm.portfolioTypes(q.like, q.type, q.statuses, q.pmUserId, null),
                pm.countPortfolioNoPlan(q.like, q.type, q.statuses, q.pmUserId, null));
    }

    static final List<String> CHART_STATUSES = List.of("DRAFT", "ACTIVE", "ON_HOLD", "CLOSED", "CANCELLED");

    /** grid 열 — 엑셀 열(COLUMNS)과 같은 필드, 화면 글자로 보이는 칸(SOP 까지·기준선 대비·준비율·다음 게이트)은 TEXT 로 받아 화면이 꾸민다 */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("projNo", "pms.col.projNo", 110).withSort().withPriority(1),
            ColumnSpec.text("projNm", "pms.col.projNm", 200).withSort().withPriority(1).withFlex(),
            ColumnSpec.text("custNm", "pms.col.cust", 110).withSort(),
            ColumnSpec.text("pmUserNm", "pms.col.pm", 80).withSort().withPriority(3),
            ColumnSpec.text("phaseNm", "pms.col.phase", 140).withSort(),
            ColumnSpec.text("sopDays", "pms.col.sopDays", 80).withSort().withPriority(1),
            ColumnSpec.text("slipDays", "pms.col.slipDays", 96).withSort(),
            ColumnSpec.of("lateCnt", "pms.col.late", ColumnType.INT, 80).withSort(),
            ColumnSpec.text("readyPct", "pms.col.readyPct", 100).withSort(),
            ColumnSpec.of("openIssueCnt", "pms.col.openIssues", ColumnType.INT, 80).withSort(),
            ColumnSpec.text("nextGateNm", "pms.col.nextGate", 170).withSort().withPriority(3),
            ColumnSpec.of("progressPct", "pms.col.progress", ColumnType.INT, 70).withSort(),
            // 공수(docs/48 R-2) — 화면이 시간 + MD(회사 하루 시간)로 꾸민다
            ColumnSpec.text("planHours", "pms.col.planHours", 110).withSort().withPriority(3),
            ColumnSpec.text("confHours", "pms.col.confHours", 110).withSort().withPriority(3),
            ColumnSpec.text("draftHours", "pms.col.draftHours", 100).withPriority(3),
            ColumnSpec.text("burnPct", "pms.col.burnPct", 80).withSort(),
            ColumnSpec.text("remainHours", "pms.col.remainHours", 110).withSort().withPriority(3));

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    @Transactional(readOnly = true)
    public long export(String keyword, String projType, String status, boolean mine, List<String> columns, HttpServletResponse response)
            throws IOException {
        Query q = query(keyword, projType, status, mine);
        List<ColumnSpec> cols = ColumnSpec.selectExportable(COLUMNS, columns);
        long count = pm.countPortfolio(q.like, q.type, q.statuses, q.pmUserId, null);
        List<String> keys = new ArrayList<>();
        if (q.like != null) {
            keys.add("keyword");
        }
        if (q.type != null) {
            keys.add("projType");
        }
        if (!isBlank(status)) {
            keys.add("status");
        }
        if (mine) {
            keys.add("mine");
        }
        LocalDate today = clock.today();
        LocalDate lateRef = days.lateRef(today);
        return excel.export(response, new ExportJob(PROG, "prog.pmsHome", cols, count, keys),
                w -> pm.findPortfolio(q.like, q.type, q.statuses, q.pmUserId, null, today, lateRef, Integer.MAX_VALUE).forEach(w::accept));
    }

    /** 비우면 진행 중 셋, ALL, 또는 상태 하나 — 프로젝트 목록과 같은 규칙 */
    private Query query(String keyword, String projType, String status, boolean mine) {
        String type = upper(projType);
        if (type != null && !CodeService.isCodeFormat(type)) {
            throw fieldError("projType", "CODE");
        }
        String st = upper(status);
        List<String> statuses;
        if (st == null || "OPEN".equals(st)) {
            statuses = List.copyOf(TaskService.OPEN);
        } else if ("ALL".equals(st)) {
            statuses = null;
        } else if (Set.of("DRAFT", "ACTIVE", "ON_HOLD", "CLOSED", "CANCELLED").contains(st)) {
            statuses = List.of(st);
        } else {
            throw fieldError("status", "CODE");
        }
        return new Query(PmsInputs.like(keyword), type, statuses, mine ? me().userId() : null);
    }

    // ------------------------------------------------------------------ 내 할 일

    @Transactional(readOnly = true)
    public MyTodo myTodo() {
        LoginUser me = me();
        LocalDate today = clock.today();
        int soon = policy.intValue(Policies.PMS_DUE_SOON_DAYS);
        LocalDate until = today.plusDays(soon);
        LocalDate lateRef = days.lateRef(today);
        long u = me.userId();
        List<PortfolioDtos.MyTask> tasks = pm.findMyTasks(u, lateRef, until, TODO_LIMIT);
        List<PortfolioDtos.MyDeliv> delivs = pm.findMyDelivs(u, lateRef, until, TODO_LIMIT);
        // 홈 카드 pms-my-work 숫자와 같은 조건(myIssueWhere) — 취소된 프로젝트의 열린 이슈는 세지 않는다(R8)
        List<io.smarthappy.module.pms.WorkDtos.IssueRow> issues = pm.findMyIssues(u, lateRef, TODO_LIMIT);
        // 전체 건수(QA H-a: 상한에서 말없이 잘리지 않게) — 상한에 닿았을 때만 카드와 같은 count 를 더 부른다
        return new MyTodo(today, soon, tasks, delivs, issues, unconfirmedDays(u, today),
                tasks.size() < TODO_LIMIT ? tasks.size() : pm.countMyTasks(u, lateRef, until, false),
                delivs.size() < TODO_LIMIT ? delivs.size() : pm.countMyDelivs(u, until),
                issues.size() < TODO_LIMIT ? issues.size() : pm.countMyIssues(u));
    }

    /**
     * 최근 7일 중 지난 평일인데 확정 행이 없는 날(주말·오늘 제외) — 공휴일 달력은 아직 없다(D4).
     * 최근 30일에 공수 기록이 하나도 없는 사람(조회만 하는 사람)은 세지 않는다 — 공수를 쓰지 않는 사람에게 매주 5일씩 쌓이는 소음
     */
    List<LocalDate> unconfirmedDays(long userId, LocalDate today) {
        if (pm.countLogs(userId, today.minusDays(30), today) == 0) {
            return List.of();
        }
        LocalDate from = today.minusDays(7);
        Set<LocalDate> done = new HashSet<>(pm.findConfirmedDays(userId, from, today));
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = today.minusDays(1); !d.isBefore(from); d = d.minusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !done.contains(d)) {
                out.add(d);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 보고서

    @Transactional(readOnly = true)
    public Report report(long projId) {
        LocalDate today = clock.today();
        LocalDate lateRef = days.lateRef(today);
        ProjectRow p = mapper.findProject(projId, lateRef);
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        PortRow summary = pm.findPortfolio(null, null, null, null, projId, today, lateRef, 1).get(0);
        List<Placed<TaskRow>> placed = tasks.placed(projId);
        List<GateDtos.DelivRow> delivs = DeliverableService.decorate(placed, gm.findDeliverables(projId), lateRef);
        // 지연 작업(오늘 기준) · 기준선 대비 늦어진 작업(많이 늦은 순)
        Map<Long, BaseTask> base = Map.of();
        BaselineRow bl = mapper.findLatestBaseline(projId);
        if (bl != null) {
            base = mapper.findBaselineTasks(bl.getBaselineId()).stream().collect(Collectors.toMap(BaseTask::getTaskId, Function.identity()));
        }
        List<SlipTask> late = new ArrayList<>();
        List<SlipTask> slip = new ArrayList<>();
        for (Placed<TaskRow> x : placed) {
            TaskRow t = x.row();
            if ("Y".equals(t.getIsSummary())) {
                continue;
            }
            BaseTask b = base.get(t.getTaskId());
            SlipTask s = new SlipTask();
            s.setTaskId(t.getTaskId());
            s.setWbsNo(t.getWbsNo());
            s.setTaskNm(t.getTaskNm());
            s.setPlanEnd(t.getPlanEnd());
            s.setStatus(t.getStatus());
            if (b != null) {
                s.setBaseEnd(b.getPlanEnd());
                s.setSlipDays((int) ChronoUnit.DAYS.between(b.getPlanEnd(), t.getPlanEnd()));
            }
            boolean open = "NOT_STARTED".equals(t.getStatus()) || "IN_PROGRESS".equals(t.getStatus());
            if (open && t.getPlanEnd().isBefore(lateRef)) {
                late.add(s);
            }
            if (b != null && s.getSlipDays() > 0) {
                slip.add(s);
            }
        }
        slip.sort(Comparator.comparingInt(SlipTask::getSlipDays).reversed());
        return new Report(p, summary, DeliverableService.phases(placed, delivs), gates.gates(projId),
                late.stream().limit(REPORT_ROWS).toList(), slip.stream().limit(REPORT_ROWS).toList(),
                issues.list(new IssueSearch(projId, null, null, null, null, null, null, null, null)), pm.hoursByCategory(projId), today,
                policy.intValue(Policies.PMS_HOURS_PER_DAY));
    }

    /** 공수 표시 단위(docs/48 R-2) — 저장은 시간, 화면은 이 값으로 MD 를 함께 보인다 */
    public int hoursPerDay() {
        return policy.intValue(Policies.PMS_HOURS_PER_DAY);
    }
}
