package io.smarthappy.module.pms;

import static io.smarthappy.module.pms.PmsInputs.date;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.workforce.WorkCalendar;
import io.smarthappy.module.master.workforce.WorkCalendar.DayPlan;
import io.smarthappy.module.pms.LoadMapper.ConfWeek;
import io.smarthappy.module.pms.LoadMapper.PlanTask;
import io.smarthappy.module.pms.LoadMapper.UserRow;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 사람 × 주 부하 보기 (docs/32 4.26, 사용자 결정 P-14 = docs/48 ⑦ B) — 구성원 표 없이 작업 담당자와 공수 기록에서만 만든다.
 * 계획 = 잎 TASK 계획 공수를 기간 근무일에 고르게(PmsLoad), 확정 = pms_work_log CONFIRMED. 가용 = 주 근무일 × 하루 시간(PMS_HOURS_PER_DAY).
 * 보는 권한 = pms-project APPROVE(관리 화면만, 홈 카드 사람별 숫자 없음 — R9). 계산만, 저장·감사 없음. 트랜잭션 안(RLS·달력)
 */
@Service
@RequiredArgsConstructor
public class LoadService {

    static final int MAX_WEEKS = 26;
    static final int DEFAULT_WEEKS = 12;
    /** 계획 몫을 나눌 작업 수 상한 — 넘으면 잘라 보이지 않고 오류(docs/32 3장 트리 규칙과 같음) */
    static final int MAX_TASKS = 20_000;
    /** 달력을 읽는 창 = 조회 범위 앞뒤 이만큼. 그보다 긴 작업은 달력일로 나눈다(PmsLoad) */
    static final int CAL_MARGIN_DAYS = 3650;

    private final LoadMapper mapper;
    private final WorkCalendar calendar;
    private final PolicyService policy;
    private final BizClock clock;

    public record LoadWeek(LocalDate weekStart, LocalDate weekEnd, int workdays, BigDecimal capHours) {
    }

    /** userId null = 담당 없음 줄(확정 공수는 없다). over = 계획이 가용을 넘는 주 수 */
    public record LoadRow(Long userId, String loginId, String userNm, boolean active, List<BigDecimal> plan, List<BigDecimal> conf,
            BigDecimal planTotal, BigDecimal confTotal, int over) {
    }

    public record LoadView(LocalDate from, LocalDate to, int hoursPerDay, List<LoadWeek> weeks, List<LoadRow> rows, LoadRow unassigned,
            int taskCnt) {
    }

    /** 칸 하나를 이루는 작업(계획 몫 큰 순) */
    public record LoadTask(long taskId, long projId, String projNo, String projNm, String taskNm, String status, LocalDate planStart,
            LocalDate planEnd, BigDecimal planHours, BigDecimal weekHours) {
    }

    @Transactional(readOnly = true)
    public LoadView view(String from, Integer weeks, Long projId) {
        FieldErrors e = FieldErrors.create();
        LocalDate f = VisibleText.isBlank(from) ? clock.today() : date(from, "from", true, e);
        int n = weeks == null ? DEFAULT_WEEKS : weeks;
        if (n < 1 || n > MAX_WEEKS) {
            e.add("weeks", "RANGE", 1, MAX_WEEKS);
        }
        e.throwIfAny();
        LocalDate first = PmsLoad.monday(f);
        LocalDate last = first.plusWeeks(n).minusDays(1);
        if (last.isAfter(PmsInputs.MAX_DATE)) {
            throw PmsInputs.fieldError("from", "RANGE", PmsInputs.MIN_DATE.toString(), PmsInputs.MAX_DATE.toString());
        }
        int hpd = policy.intValue(Policies.PMS_HOURS_PER_DAY);
        List<PlanTask> tasks = tasks(first, last, projId, null, false);
        PmsLoad.Cal cal = cal(tasks, first, last);
        Map<Long, BigDecimal[]> plan = PmsLoad.plan(tasks.stream().map(LoadService::of).toList(), first, n, cal);
        Map<Long, BigDecimal[]> conf = new HashMap<>();
        for (ConfWeek c : mapper.findConfirmed(first, last, projId)) {
            int w = (int) (ChronoUnit.DAYS.between(first, c.getWeekStart()) / 7);
            conf.computeIfAbsent(c.getUserId(), k -> PmsLoad.zeros(n))[w] = c.getHours();
        }
        List<LoadWeek> weekList = new ArrayList<>();
        for (int w = 0; w < n; w++) {
            LocalDate ws = first.plusWeeks(w);
            int wd = cal.workdays(ws, ws.plusDays(6));
            weekList.add(new LoadWeek(ws, ws.plusDays(6), wd, BigDecimal.valueOf((long) wd * hpd)));
        }
        Set<Long> ids = new HashSet<>(conf.keySet());
        plan.keySet().stream().filter(java.util.Objects::nonNull).forEach(ids::add);
        Map<Long, UserRow> users = new HashMap<>();
        if (!ids.isEmpty()) {
            mapper.findUsers(ids).forEach(u -> users.put(u.getUserId(), u));
        }
        List<LoadRow> rows = new ArrayList<>();
        for (Long id : ids) {
            UserRow u = users.get(id);
            rows.add(row(id, u, plan.getOrDefault(id, PmsLoad.zeros(n)), conf.getOrDefault(id, PmsLoad.zeros(n)), weekList));
        }
        rows.sort(Comparator.comparing((LoadRow r) -> r.userNm() == null ? "" : r.userNm()).thenComparing(r -> r.loginId() == null ? "" : r.loginId()));
        LoadRow none = plan.containsKey(null) ? row(null, null, plan.get(null), PmsLoad.zeros(n), weekList) : null;
        return new LoadView(first, last, hpd, weekList, rows, none, tasks.size());
    }

    /** 칸 하나(사람 × 주)를 이루는 작업 — userId 없고 unassigned=true 면 담당 없음 */
    @Transactional(readOnly = true)
    public List<LoadTask> cell(Long userId, boolean unassigned, String week, Long projId) {
        FieldErrors e = FieldErrors.create();
        LocalDate d = date(week, "week", true, e);
        if (userId == null && !unassigned) {
            e.add("userId", "REQUIRED");
        }
        e.throwIfAny();
        LocalDate ws = PmsLoad.monday(d);
        LocalDate we = ws.plusDays(6);
        List<PlanTask> tasks = tasks(ws, we, projId, userId, userId == null);
        PmsLoad.Cal cal = cal(tasks, ws, we);
        List<LoadTask> out = new ArrayList<>();
        for (PlanTask t : tasks) {
            BigDecimal h = PmsLoad.share(of(t), ws, cal);
            if (h.signum() > 0) {
                out.add(new LoadTask(t.getTaskId(), t.getProjId(), t.getProjNo(), t.getProjNm(), t.getTaskNm(), t.getStatus(), t.getPlanStart(),
                        t.getPlanEnd(), t.getPlanHours(), h));
            }
        }
        out.sort(Comparator.comparing(LoadTask::weekHours).reversed().thenComparing(LoadTask::taskId));
        return out;
    }

    // ------------------------------------------------------------------ 내부

    private List<PlanTask> tasks(LocalDate from, LocalDate to, Long projId, Long ownerId, boolean unassigned) {
        List<PlanTask> tasks = mapper.findPlanTasks(from, to, projId, ownerId, unassigned, MAX_TASKS + 1);
        if (tasks.size() > MAX_TASKS) {
            throw new BusinessException(HbErrorCode.TOO_MANY_ROWS, MAX_TASKS + "+", MAX_TASKS);
        }
        return tasks;
    }

    /** 작업 기간을 덮는 근무일 표 — 조회 범위 ± CAL_MARGIN_DAYS 안만 읽는다(366일씩, WorkCalendar.MAX_DAYS 400 안) */
    private PmsLoad.Cal cal(List<PlanTask> tasks, LocalDate first, LocalDate last) {
        LocalDate a = first;
        LocalDate b = last;
        for (PlanTask t : tasks) {
            if (t.getPlanStart().isBefore(a)) {
                a = t.getPlanStart();
            }
            if (t.getPlanEnd().isAfter(b)) {
                b = t.getPlanEnd();
            }
        }
        if (a.isBefore(first.minusDays(CAL_MARGIN_DAYS))) {
            a = first.minusDays(CAL_MARGIN_DAYS);
        }
        if (b.isAfter(last.plusDays(CAL_MARGIN_DAYS))) {
            b = last.plusDays(CAL_MARGIN_DAYS);
        }
        boolean[] wd = new boolean[(int) ChronoUnit.DAYS.between(a, b) + 1];
        for (LocalDate from = a; !from.isAfter(b); from = from.plusDays(PmsDays.CHUNK)) {
            LocalDate to = from.plusDays(PmsDays.CHUNK - 1L).isAfter(b) ? b : from.plusDays(PmsDays.CHUNK - 1L);
            for (Map.Entry<LocalDate, DayPlan> x : calendar.range(null, from, to).entrySet()) {
                wd[(int) ChronoUnit.DAYS.between(a, x.getKey())] = x.getValue().workday();
            }
        }
        return new PmsLoad.Cal(a, wd);
    }

    private static PmsLoad.Task of(PlanTask t) {
        return new PmsLoad.Task(t.getTaskId(), t.getOwnerUserId(), t.getPlanStart(), t.getPlanEnd(), t.getPlanHours());
    }

    private static LoadRow row(Long id, UserRow u, BigDecimal[] plan, BigDecimal[] conf, List<LoadWeek> weeks) {
        BigDecimal pt = Arrays.stream(plan).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ct = Arrays.stream(conf).reduce(BigDecimal.ZERO, BigDecimal::add);
        int over = 0;
        for (int w = 0; w < plan.length; w++) {
            if (id != null && plan[w].compareTo(weeks.get(w).capHours()) > 0) {
                over++;
            }
        }
        return new LoadRow(id, u == null ? null : u.getLoginId(), u == null ? null : u.getUserNm(), u != null && "Y".equals(u.getUseYn()),
                Arrays.stream(plan).map(LoadService::n).toList(), Arrays.stream(conf).map(LoadService::n).toList(), n(pt), n(ct), over);
    }

    private static BigDecimal n(BigDecimal x) {
        return x.setScale(PmsLoad.SCALE, java.math.RoundingMode.HALF_UP);
    }
}
