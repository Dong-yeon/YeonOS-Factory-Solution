package io.smarthappy.module.cms;

import static io.smarthappy.module.cms.CmsSupport.at;
import static io.smarthappy.module.cms.CmsSupport.blank;
import static io.smarthappy.module.cms.CmsSupport.date;
import static io.smarthappy.module.cms.CmsSupport.decimal;
import static io.smarthappy.module.cms.CmsSupport.like;
import static io.smarthappy.module.cms.CmsSupport.me;
import static io.smarthappy.module.cms.CmsSupport.now;
import static io.smarthappy.module.cms.CmsSupport.text;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.CmsPmDtos.PlanRow;
import io.smarthappy.module.cms.CmsPmDtos.ResultForm;
import io.smarthappy.module.cms.CmsPmDtos.ResultRow;
import io.smarthappy.module.cms.CmsPmDtos.RunExport;
import io.smarthappy.module.cms.CmsPmDtos.RunPage;
import io.smarthappy.module.cms.CmsPmDtos.RunRow;
import io.smarthappy.module.cms.CmsPmDtos.RunSort;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
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
import io.smarthappy.module.cms.CmsDtos.Option;
import io.smarthappy.module.cms.link.PmCheckPort;
import lombok.RequiredArgsConstructor;

/**
 * 점검 실행 (cms-chk-run) — docs/33 4.3, D7. 결과는 OPEN 에서만, 판정은 서버. 완료·건너뛰기 뒤 계획의 다음 예정을 PmDue 로.
 * WO 방식 실행은 작업지시 완료·취소가 함께 끝낸다(completeForMwo·skipForMwo) — 이 화면에서 따로 끝내지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ChkRunService {

    static final String PROG = "cms-chk-run";
    static final int MAX_ROWS = 1000;
    static final int MAX_DAYS = 366;
    static final Set<String> STATUSES = Set.of("OPEN", "DONE", "SKIPPED");
    /** 건너뛰기 사유 코드(docs/68 Q1) */
    static final String SKIP_REASON_GRP = "CMS_SKIP_REASON";

    private final CmsPmMapper mapper;
    private final DateRanges ranges;
    private final PolicyService policies;
    private final BizClock clock;
    private final ExcelExportService excel;
    private final MessageSource messages;
    private final io.smarthappy.platform.code.CodeService codes;
    private final CmsMapper cmsMapper;

    /**
     * 끝내는 주체 — 사무 계정(userId·loginId) 또는 현장 단말(작업자 + 단말 + 요청 id, docs/25 4.58). name = 감사 칸(updated_by)에 남는 글자
     */
    private record By(Long userId, String name, Long workerId, Long terminalId, String reqId) {
        static By of(LoginUser me) {
            return new By(me.userId(), me.loginId(), null, null, null);
        }
    }

    // ------------------------------------------------------------------ 조회

    /** 기간 = 예정일(필수) */
    @Transactional(readOnly = true)
    public List<RunRow> list(String from, String to, String status, String execMode, String plantCd, String keyword, Long planId,
            Long equipId) {
        RunCrit c = criteria(from, to, status, execMode, plantCd, keyword);
        List<RunRow> rows = mapper.findRuns(c.from, c.to, c.statuses, c.execMode, c.plantCd, c.keyword, planId, equipId, MAX_ROWS + 1);
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException(HbErrorCode.CMS_TOO_MANY_ROWS, MAX_ROWS);
        }
        Map<String, ZoneId> zones = new HashMap<>();
        int lead = policies.intValue(Policies.CMS_PM_LEAD_DAYS);
        int grace = policies.intValue(Policies.CMS_PM_GRACE_DAYS);
        rows.forEach(r -> decorate(r, zones, lead, grace));
        return rows;
    }

    /** 목록·grid 공통 조건(검증 + 정규화) */
    private record RunCrit(LocalDate from, LocalDate to, List<String> statuses, String execMode, String plantCd, String keyword) {
    }

    private RunCrit criteria(String from, String to, String status, String execMode, String plantCd, String keyword) {
        FieldErrors e = FieldErrors.create();
        LocalDate f = date(from, "from", e);
        LocalDate t = date(to, "to", e);
        e.throwIfAny();
        ranges.check(f, t, MAX_DAYS);
        List<String> statuses = null;
        if (!blank(status)) {
            statuses = Arrays.stream(status.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
            if (statuses.isEmpty() || !STATUSES.containsAll(statuses)) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
        }
        if (!blank(execMode) && !"WO".equals(execMode) && !"CHECK".equals(execMode) || keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return new RunCrit(f, t, statuses, blank(execMode) ? null : execMode, blank(plantCd) ? null : plantCd.strip().toUpperCase(Locale.ROOT),
                blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT)));
    }

    /** grid(UI 단계 3 ①, docs/33 15.1) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link RunSort}) */
    @Transactional(readOnly = true)
    public RunPage grid(String from, String to, String status, String execMode, String plantCd, String keyword, Integer page, Integer size,
            List<String> sort, Boolean total) {
        RunCrit c = criteria(from, to, status, execMode, plantCd, keyword);
        PageQuery pq = PageQuery.parse(page, size, sort, total, RunSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        List<RunRow> rows = gridRows(c, pq);
        return new RunPage(rows, pq.isWithTotal() ? count(c) : null);
    }

    static final List<String> DEFAULT_SORT = List.of("dueDate,asc");

    private List<RunRow> gridRows(RunCrit c, PageQuery pq) {
        List<RunRow> rows = mapper.findRunPage(c.from, c.to, c.statuses, c.execMode, c.plantCd, c.keyword, null, null, pq);
        Map<String, ZoneId> zones = new HashMap<>();
        int lead = policies.intValue(Policies.CMS_PM_LEAD_DAYS);
        int grace = policies.intValue(Policies.CMS_PM_GRACE_DAYS);
        rows.forEach(r -> {
            decorate(r, zones, lead, grace);
            if (r.getOnTime() != null) {
                r.setState(r.getOnTime() ? "ON_TIME" : "LATE");   // grid 상태 칸 하나로 — 완료는 기한 안·늦음(목록은 onTime 그대로)
            }
        });
        return rows;
    }

    private long count(RunCrit c) {
        return mapper.countRuns(c.from, c.to, c.statuses, c.execMode, c.plantCd, c.keyword, null, null);
    }

    /** 엑셀 열 바꿈 — 화면이 꾸미는 칸: 상태(예정·할 때·지남·기한 안·늦음) = 이름, 실시 시각 = 공장 현지 글자 */
    static final Map<String, List<ColumnSpec>> EXPORT_REPLACE = Map.of(
            "state", List.of(ColumnSpec.text("stateNm", "cms.run.col.state", 80)),
            "performedAt", List.of(ColumnSpec.text("performedAtText", "cms.run.col.performedAt", 130)));

    /** 엑셀(C21) — grid 와 같은 조건(기간 필수)·정렬(허용 목록)·보이는 열 */
    @Transactional(readOnly = true)
    public long export(RunExport req, HttpServletResponse response) throws IOException {
        RunExport r = req == null ? new RunExport(null, null, null, null, null, null, null, null) : req;
        RunCrit c = criteria(r.from(), r.to(), r.status(), r.execMode(), r.plantCd(), r.keyword());
        List<ColumnSpec> cols = CmsSupport.exportColumns(GRID_COLUMNS, r.columns(), EXPORT_REPLACE);
        PageQuery pq = PageQuery.sortOnly(r.sort(), RunSort.values(), DEFAULT_SORT);
        List<String> keys = new ArrayList<>(List.of("from", "to"));
        if (c.statuses != null) {
            keys.add("status");
        }
        if (c.execMode != null) {
            keys.add("execMode");
        }
        if (c.plantCd != null) {
            keys.add("plantCd");
        }
        if (c.keyword != null) {
            keys.add("keyword");
        }
        Locale locale = LocaleContextHolder.getLocale();
        return excel.export(response, new ExportJob(PROG, "prog.cmsChkRun", cols, count(c), keys), w -> gridRows(c, pq).forEach(row -> {
            row.setStateNm(stateName(row.getState(), locale));
            w.accept(row);
        }));
    }

    private String stateName(String state, Locale locale) {
        if (state == null) {
            return null;
        }
        String key = switch (state) {
            case "ON_TIME" -> "js.cms.run.onTime";
            case "LATE" -> "js.cms.run.late";
            default -> "js.cms.state." + state;
        };
        return messages.getMessage(key, null, state, locale);
    }

    /** grid 열(화면 점검 실행 표와 같은 칸). 예정 상태·기한 안 완료는 계산 값이라 정렬 밖, 수행 시각은 대상 공장 현지 글자 */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.of("dueDate", "cms.run.col.dueDate", ColumnType.DATE, 100).withSort(),
            ColumnSpec.text("dueUsageText", "cms.run.col.dueUsage", 100),
            ColumnSpec.text("planCd", "cms.run.col.plan", 110).withSort(),
            ColumnSpec.text("planNm", "cms.plan.col.planNm", 160).withPriority(3),
            ColumnSpec.text("targetCd", "cms.mwo.col.target", 110).withSort(),
            ColumnSpec.text("targetNm", "cms.mwo.col.targetNm", 150).withPriority(3),
            ColumnSpec.code("execMode", "cms.plan.col.execMode", "CMS_EXEC_MODE", 90).withSort(),
            ColumnSpec.code("status", "cms.mwo.col.status", "CMS_RUN_STATUS", 80).withSort(),
            ColumnSpec.text("state", "cms.run.col.state", 80),
            ColumnSpec.text("result", "cms.run.col.result", 70).withSort(),
            ColumnSpec.of("ngCnt", "cms.run.col.ngCnt", ColumnType.INT, 60),
            ColumnSpec.text("mwoNo", "cms.mwo.col.mwoNo", 120).withSort(),
            ColumnSpec.text("performedAt", "cms.run.col.performedAt", 130).withSort());

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    @Transactional(readOnly = true)
    public RunRow get(long runId) {
        RunRow r = mapper.findRun(runId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return withResults(r);
    }

    /** 작업지시의 점검 실행(PM) — 없으면 null */
    @Transactional(readOnly = true)
    public RunRow getByMwo(long mwoId) {
        RunRow r = mapper.findRunByMwo(mwoId);
        return r == null ? null : withResults(r);
    }

    private RunRow withResults(RunRow r) {
        decorate(r, new HashMap<>(), policies.intValue(Policies.CMS_PM_LEAD_DAYS), policies.intValue(Policies.CMS_PM_GRACE_DAYS));
        r.setResults(mapper.findResults(r.getRunId()));
        return r;
    }

    private void decorate(RunRow r, Map<String, ZoneId> zones, int defaultLead, int grace) {
        ZoneId z = zones.computeIfAbsent(r.getPlantCd() == null ? "" : r.getPlantCd(),
                k -> k.isEmpty() ? clock.zone() : clock.zone(k));
        r.setTimeZone(z.getId());
        r.setPerformedAt(at(r.getPerformedAt(), z));
        boolean usage = r.getDueUsage() != null;
        if ("OPEN".equals(r.getStatus())) {
            if (usage) {
                r.setState(PmDue.usageState(r.getDueUsage(), r.getCounterTotal(), r.getUsageLead()).name());
            } else {
                int lead = r.getLeadDays() != null ? r.getLeadDays() : defaultLead;
                r.setState(PmDue.state(r.getDueDate(), clock.instant().atZone(z).toLocalDate(), lead).name());
            }
        } else if ("DONE".equals(r.getStatus())) {
            r.setOnTime(usage && r.getDoneUsage() != null ? PmDue.usageOnTime(r.getDueUsage(), r.getDoneUsage())
                    : PmDue.onTime(r.getDueDate(), r.getPerformedAt().toLocalDate(), grace));
        }
    }

    // ------------------------------------------------------------------ 결과·완료·건너뛰기

    /**
     * 결과 저장 — 순번으로 찾는다(보낸 항목만 바꾼다). NUM 은 값으로 판정(하한 ≤ 값 ≤ 상한 OK, 경계 포함), OKNG 는 OK/NG,
     * TEXT 는 판정 없음. 칸 오류 행 키 = 순번. mwoId 가 있으면 그 작업지시의 실행이어야 한다(작업지시 화면 경로)
     */
    @Transactional
    public void saveResults(long runId, Long mwoId, Integer rowVer, List<ResultForm> rows) {
        LoginUser me = me();
        RunRow run = lock(runId, mwoId, rowVer);
        if (applyResults(runId, rows, me.loginId()) > 0) {
            mapper.finishRun(runId, "OPEN", null, null, null, null, null, null, null, null, null, run.getRowVer(), me.loginId());   // rowVer 올리기(동시 입력 409)
        }
    }

    /** 결과 검증 + 저장(잠긴 OPEN 실행에) — 사무 화면과 현장 단말이 같은 규칙. 바꾼 항목 수 */
    private int applyResults(long runId, List<ResultForm> rows, String by) {
        if (rows == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        Map<Integer, ResultRow> bySeq = new HashMap<>();
        mapper.findResults(runId).forEach(r -> bySeq.put(r.getSeq(), r));
        FieldErrors e = FieldErrors.create();
        Set<Integer> seen = new HashSet<>();
        record Upd(int seq, BigDecimal num, String text, String judge, String remark) {
        }
        List<Upd> ups = new java.util.ArrayList<>();
        for (ResultForm f : rows) {
            if (f == null || f.seq() == null || !bySeq.containsKey(f.seq()) || !seen.add(f.seq())) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);   // 없는 순번·같은 순번 두 번
            }
            ResultRow item = bySeq.get(f.seq());
            FieldErrors.RowScope rs = e.row(String.valueOf(f.seq()));
            FieldErrors one = FieldErrors.create();
            BigDecimal num = null;
            String judge = null;
            String txt = null;
            switch (item.getJudgeType()) {
                case "NUM" -> {
                    num = decimal(f.value(), "value", item.getScale(), one);
                    if (num != null) {
                        boolean ok = (item.getLsl() == null || num.compareTo(item.getLsl()) >= 0)
                                && (item.getUsl() == null || num.compareTo(item.getUsl()) <= 0);
                        judge = ok ? "OK" : "NG";
                    }
                    txt = text(f.valueText(), "valueText", 500, false, one);
                }
                case "OKNG" -> {
                    if (!blank(f.judge())) {
                        judge = f.judge().strip();
                        if (!"OK".equals(judge) && !"NG".equals(judge)) {
                            one.add("judge", "CODE");
                            judge = null;
                        }
                    }
                    txt = text(f.valueText(), "valueText", 500, false, one);
                }
                default -> txt = text(f.valueText(), "valueText", 500, false, one);
            }
            String remark = text(f.remark(), "remark", 500, false, one);
            one.list().forEach(x -> rs.add(x.field(), x.code(), x.args()));
            ups.add(new Upd(f.seq(), num, txt, judge, remark));
        }
        e.throwIfAny();
        for (Upd u : ups) {
            mapper.updateResult(runId, u.seq(), u.num(), u.text(), u.judge(), u.remark(), by);
        }
        return ups.size();
    }

    /** CHECK 방식 완료 — 필수 결과가 모두 있어야 */
    @Transactional
    public void complete(long runId, Integer rowVer) {
        LoginUser me = me();
        RunRow run = lock(runId, null, rowVer);
        if ("WO".equals(run.getExecMode())) {
            throw new BusinessException(HbErrorCode.CMS_RUN_STATE);   // 작업지시 완료로 끝낸다
        }
        finish(run, By.of(me), rowVer, null);
    }

    /**
     * 지난 점검을 그 수행 시각으로 완료 — 데모 시더({@link CmsDemoFlow}) 전용, API 없음. 판정·필수 검사·다음 예정은 {@link #complete} 와 같고
     * 수행 시각(발생)만 받는다(기록 시각 updated_at 은 지금). 지금보다 뒤면 거부
     */
    @Transactional
    void completeAt(long runId, Integer rowVer, OffsetDateTime performedAt) {
        LoginUser me = me();
        RunRow run = lock(runId, null, rowVer);
        if ("WO".equals(run.getExecMode())) {
            throw new BusinessException(HbErrorCode.CMS_RUN_STATE);
        }
        if (performedAt == null || performedAt.toInstant().isAfter(clock.instant())) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        finish(run, By.of(me), rowVer, performedAt);
    }

    /** CHECK 방식 건너뛰기(사유 필수) — WO 방식은 작업지시 취소로 */
    @Transactional
    public void skip(long runId, Integer rowVer, String reason) {
        skip(runId, rowVer, null, reason);
    }

    /**
     * 건너뛰기 사유 코드(CMS_SKIP_REASON) + 메모(docs/68 Q1). 코드가 있으면 메모는 선택(비면 코드 이름), 없으면 글자 필수(미분류 — 옛 API).
     * 코드는 잠그기 전에 본다(없는·사용 중지·다른 회사 코드 = reasonCd CODE)
     */
    @Transactional
    public void skip(long runId, Integer rowVer, String reasonCd, String reason) {
        LoginUser me = me();
        String code = blank(reasonCd) ? null : reasonCd.strip();
        String text = reason;
        if (code != null) {
            FieldErrors e = FieldErrors.create();
            if (!codes.usable(SKIP_REASON_GRP, code)) {
                e.add("reasonCd", "CODE");
            }
            String memo = text(reason, "reason", 500, false, e);
            e.throwIfAny();
            text = memo != null ? memo
                    : codes.names(SKIP_REASON_GRP, org.springframework.context.i18n.LocaleContextHolder.getLocale()).getOrDefault(code, code);
        }
        RunRow run = lock(runId, null, rowVer);
        if ("WO".equals(run.getExecMode())) {
            throw new BusinessException(HbErrorCode.CMS_RUN_STATE);
        }
        skipRun(run, code, text, By.of(me), rowVer);
    }

    /** 작업지시(PM) 완료가 부른다 — 같은 트랜잭션. 필수 결과가 빠지면 칸 오류로 작업지시 완료도 막힌다 */
    void completeForMwo(long mwoId, LoginUser me, OffsetDateTime finishedAt) {
        RunRow run = mapper.findRunByMwo(mwoId);
        if (run == null || !"OPEN".equals(run.getStatus())) {
            return;
        }
        mapper.lockRun(run.getRunId());
        // 수행 시각 = 작업지시 완료 시각(docs/33 16.2 F2) — 사후 입력이면 과거. 지금으로 두면 유동 계획 다음 예정이 입력이 늦은 만큼 밀린다
        finish(mapper.findRun(run.getRunId()), By.of(me), null, finishedAt);
    }

    /** 작업지시(PM) 취소가 부른다 — 그 예정은 건너뜀 */
    void skipForMwo(long mwoId, String reason, LoginUser me) {
        RunRow run = mapper.findRunByMwo(mwoId);
        if (run == null || !"OPEN".equals(run.getStatus())) {
            return;
        }
        mapper.lockRun(run.getRunId());
        skipRun(mapper.findRun(run.getRunId()), null, reason, By.of(me), null);
    }

    /** performedAt = null 이면 지금(점검 실행 화면). 작업지시 경로는 작업지시 완료 시각 */
    private void finish(RunRow run, By me, Integer rowVer, OffsetDateTime performedAt) {
        List<ResultRow> results = mapper.findResults(run.getRunId());
        FieldErrors e = FieldErrors.create();
        boolean ng = false;
        for (ResultRow r : results) {
            boolean has = switch (r.getJudgeType()) {
                case "NUM" -> r.getValueNum() != null;
                case "OKNG" -> r.getJudge() != null;
                default -> !blank(r.getValueText());
            };
            if ("Y".equals(r.getRequiredYn()) && !has) {
                e.row(String.valueOf(r.getSeq())).add("value", "REQUIRED");
            }
            ng |= "NG".equals(r.getJudge());
        }
        if (!e.isEmpty()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(e.list());
        }
        ZoneId z = zoneOf(run);
        OffsetDateTime at = performedAt == null ? now(clock, z) : performedAt.atZoneSameInstant(z).toOffsetDateTime();
        // 사용량 주기: 완료 때 누적을 남긴다(기한 준수·다음 예정의 기준)
        BigDecimal doneUsage = run.getDueUsage() == null ? null : counterTotal(run.getPlanId());
        if (mapper.finishRun(run.getRunId(), "DONE", at, me.userId(), ng ? "NG" : "OK", null, null, doneUsage, me.workerId(), me.terminalId(),
                me.reqId(), rowVer, me.name()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        advance(run, at.toLocalDate(), doneUsage, me);
    }

    private BigDecimal counterTotal(long planId) {
        PlanRow p = mapper.findPlan(planId);
        return p.getCounterTotal() == null ? BigDecimal.ZERO : p.getCounterTotal();
    }

    private void skipRun(RunRow run, String reasonCd, String reason, By me, Integer rowVer) {
        FieldErrors e = FieldErrors.create();
        String r = text(reason, "reason", 500, true, e);
        e.throwIfAny();
        if (mapper.finishRun(run.getRunId(), "SKIPPED", null, null, null, reasonCd, r, null, me.workerId(), me.terminalId(), me.reqId(), rowVer,
                me.name()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        advance(run, null, null, me);
    }

    /** 다음 예정 — 계획을 잠그고, 이 실행이 계획의 지금 예정일 때만 옮긴다. 사용량 주기는 예정 사용량으로 */
    private void advance(RunRow run, LocalDate doneDate, BigDecimal doneUsage, By me) {
        mapper.lockPlan(run.getPlanId());
        PlanRow p = mapper.findPlan(run.getPlanId());
        if (p.isUsage()) {
            if (run.getDueUsage() == null || run.getDueUsage().compareTo(p.getNextDueUsage()) != 0) {
                return;
            }
            BigDecimal next = PmDue.nextUsage(PmPlanService.usageRule(p), run.getDueUsage(), doneUsage);
            mapper.advanceUsagePlan(p.getPlanId(), next, doneDate, doneUsage, me.name());
            return;
        }
        if (!run.getDueDate().equals(p.getNextDueDate())) {
            return;   // 규칙을 바꿔 다음 예정이 이미 달라졌으면 그대로
        }
        LocalDate next = PmDue.next(PmPlanService.rule(p), run.getDueDate(), doneDate);
        mapper.advancePlan(p.getPlanId(), next, doneDate, me.name());
    }

    // ------------------------------------------------------------------ 현장 단말(POP) — 계약 PmCheckPort (docs/25 4.58)

    static final java.util.regex.Pattern FLOOR_REQ = java.util.regex.Pattern.compile("^[A-Za-z0-9_.:-]{8,64}$");
    static final int FLOOR_DUE_LIMIT = 200;

    /** 그 공장·그 설비들의 열린 CHECK 방식 점검(예정일 순) */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PmCheckPort.DueRun> floorDue(String plantCd, Collection<Long> equipIds) {
        if (blank(plantCd) || equipIds == null || equipIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = equipIds.stream().filter(Objects::nonNull).distinct().limit(1000).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<RunRow> rows = mapper.findFloorDue(plantCd.strip(), ids, FLOOR_DUE_LIMIT);
        Map<String, ZoneId> zones = new HashMap<>();
        int lead = policies.intValue(Policies.CMS_PM_LEAD_DAYS);
        int grace = policies.intValue(Policies.CMS_PM_GRACE_DAYS);
        rows.forEach(r -> decorate(r, zones, lead, grace));
        return rows.stream().map(ChkRunService::dueRun).toList();
    }

    /** 점검 하나 + 항목. 끝난 실행도 읽힌다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public PmCheckPort.RunView floorDetail(long runId, long terminalId) {
        Option terminal = cmsMapper.findPopTerminal(terminalId);
        RunRow r = mapper.findRun(runId);
        if (!floorVisible(r, terminal)) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        withResults(r);
        return new PmCheckPort.RunView(dueRun(r), r.getResults().stream()
                .map(x -> new PmCheckPort.Item(x.getSeq(), x.getItemNm(), x.getMethodText(), x.getJudgeType(), x.getLsl(), x.getUsl(),
                        x.getUnitNm(), x.getScale(), "Y".equals(x.getRequiredYn()), x.getValueNum(), x.getValueText(), x.getJudge(),
                        x.getRemark())).toList());
    }

    /** 결과 저장 + 완료(한 트랜잭션). 같은 요청 id 가 이 실행을 이미 끝냈으면 처음 결과 */
    @Transactional(propagation = Propagation.MANDATORY)
    public PmCheckPort.Done floorComplete(PmCheckPort.Complete in) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        By by = floorBy(in.workerId(), in.terminalId(), in.clientReqId(), null);
        RunRow run = floorLock(in.runId(), in.terminalId(), in.clientReqId());
        if (!"OPEN".equals(run.getStatus())) {
            return floorDone(run, true);
        }
        floorOpen(run, in.rowVer());
        List<PmCheckPort.ItemResult> rs = in.results() == null ? List.of() : in.results();
        List<ResultForm> forms = new ArrayList<>();
        rs.forEach(x -> forms.add(x == null ? null : new ResultForm(x.seq(), x.value(), x.valueText(), x.judge(), x.remark())));
        applyResults(run.getRunId(), forms, by.name());
        finish(run, by, in.rowVer(), null);
        return floorDone(mapper.findRun(run.getRunId()), false);
    }

    /** 건너뛰기 — 사유 코드 필수(메모는 선택, 비면 코드 이름) */
    @Transactional(propagation = Propagation.MANDATORY)
    public PmCheckPort.Done floorSkip(PmCheckPort.Skip in) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String code = blank(in.reasonCd()) ? null : in.reasonCd().strip();
        if (code == null) {
            e.add("reasonCd", "REQUIRED");
        } else if (!codes.usable(SKIP_REASON_GRP, code)) {
            e.add("reasonCd", "CODE");
        }
        String memo = text(in.memo(), "memo", 500, false, e);
        By by = floorBy(in.workerId(), in.terminalId(), in.clientReqId(), e);
        RunRow run = floorLock(in.runId(), in.terminalId(), in.clientReqId());
        if (!"OPEN".equals(run.getStatus())) {
            return floorDone(run, true);
        }
        floorOpen(run, in.rowVer());
        skipRun(run, code, memo != null ? memo : codes.names(SKIP_REASON_GRP, LocaleContextHolder.getLocale()).getOrDefault(code, code), by,
                in.rowVer());
        return floorDone(mapper.findRun(run.getRunId()), false);
    }

    /** 요청 id 모양·단말·작업자 확인 → 단말 행위자. 앞서 모은 칸 오류(more)가 있으면 같이 던진다 */
    private By floorBy(long workerId, long terminalId, String reqId, FieldErrors more) {
        FieldErrors e = more == null ? FieldErrors.create() : more;
        if (reqId == null || !FLOOR_REQ.matcher(reqId).matches()) {
            e.add("clientReqId", blank(reqId) ? "REQUIRED" : "FORMAT");
        }
        Option terminal = cmsMapper.findPopTerminal(terminalId);   // RLS — 다른 회사 단말은 없음
        if (terminal == null) {
            e.add("terminalId", "NO_SUCH_CODE");
        }
        if (!cmsMapper.popWorkerActive(workerId)) {
            e.add("workerId", "NO_SUCH_CODE");
        }
        e.throwIfAny();
        return new By(null, "t" + terminalId + ":" + terminal.code(), workerId, terminalId, reqId);   // POP 행위자 관례(TerminalPrincipal.actor)
    }

    /**
     * 잠금 → 범위(단말 공장·CHECK 방식·설비 대상) → 끝난 실행이면 같은 요청 id 일 때만 돌려준다(그 밖은 CMS_RUN_STATE).
     * 같은 요청 id 가 다른 실행을 이미 끝냈으면 DUPLICATE_REQUEST
     */
    private RunRow floorLock(long runId, long terminalId, String reqId) {
        Long used = mapper.findRunIdByFloorReq(reqId);
        if (used != null && used != runId) {
            throw new BusinessException(HbErrorCode.DUPLICATE_REQUEST);
        }
        if (mapper.lockRun(runId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        RunRow run = mapper.findRun(runId);
        if (!floorVisible(run, cmsMapper.findPopTerminal(terminalId))) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"OPEN".equals(run.getStatus()) && !reqId.equals(run.getFloorReqId())) {
            throw new BusinessException(HbErrorCode.CMS_RUN_STATE);
        }
        return run;
    }

    private static void floorOpen(RunRow run, Integer rowVer) {
        if (rowVer == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (run.getRowVer() != rowVer) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 단말이 볼 수 있는 실행 — 설비 대상·CHECK 방식·단말 공장(Option.name = 공장) */
    private static boolean floorVisible(RunRow r, Option terminal) {
        return r != null && terminal != null && r.getEquipId() != null && "CHECK".equals(r.getExecMode())
                && Objects.equals(terminal.name(), r.getPlantCd());
    }

    private PmCheckPort.Done floorDone(RunRow run, boolean duplicate) {
        List<String> ng = "DONE".equals(run.getStatus()) ? mapper.findResults(run.getRunId()).stream()
                .filter(x -> "NG".equals(x.getJudge())).map(ResultRow::getItemNm).toList() : List.of();
        return new PmCheckPort.Done(run.getStatus(), run.getResult(), ng, duplicate);
    }

    private static PmCheckPort.DueRun dueRun(RunRow r) {
        return new PmCheckPort.DueRun(r.getRunId(), r.getPlanCd(), r.getPlanNm(), r.getTmplNm(), r.getEquipId(), r.getTargetCd(),
                r.getTargetNm(), r.getDueDate(), r.getState(), r.getItemCnt(), r.getRowVer());
    }

    /** 잠금 → 다시 읽기 → rowVer·OPEN 확인. 작업지시 경로면 그 작업지시의 실행인지도 */
    private RunRow lock(long runId, Long mwoId, Integer rowVer) {
        if (rowVer == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (mapper.lockRun(runId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        RunRow run = mapper.findRun(runId);
        if (mwoId != null && !mwoId.equals(run.getMwoId())) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (run.getRowVer() != rowVer) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (!"OPEN".equals(run.getStatus())) {
            throw new BusinessException(HbErrorCode.CMS_RUN_STATE);
        }
        return run;
    }

    private ZoneId zoneOf(RunRow r) {
        return r.getPlantCd() == null ? clock.zone() : clock.zone(r.getPlantCd());
    }
}
