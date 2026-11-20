package io.smarthappy.module.cms;

import static io.smarthappy.module.cms.CmsSupport.blank;
import static io.smarthappy.module.cms.CmsSupport.date;
import static io.smarthappy.module.cms.CmsSupport.decimal;
import static io.smarthappy.module.cms.CmsSupport.fieldError;
import static io.smarthappy.module.cms.CmsSupport.like;
import static io.smarthappy.module.cms.CmsSupport.me;
import static io.smarthappy.module.cms.CmsSupport.text;
import static io.smarthappy.module.cms.CmsSupport.yn;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.CmsCounterDtos.CounterRow;
import io.smarthappy.module.cms.CmsDtos.TargetRow;
import io.smarthappy.module.cms.CmsPmDtos.BulkForm;
import io.smarthappy.module.cms.CmsPmDtos.BulkResult;
import io.smarthappy.module.cms.CmsPmDtos.PlanExport;
import io.smarthappy.module.cms.CmsPmDtos.PlanForm;
import io.smarthappy.module.cms.CmsPmDtos.PlanPage;
import io.smarthappy.module.cms.CmsPmDtos.PlanRow;
import io.smarthappy.module.cms.CmsPmDtos.PlanSort;
import io.smarthappy.module.cms.CmsPmDtos.PreviewForm;
import io.smarthappy.module.cms.CmsPmDtos.TmplRow;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserRef;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 예방점검 계획 (cms-pm-plan) — docs/33 4.3·4.4, D4·D5·D6. 대상 × 점검표 × 실행 방식 × 주기(달력 또는 사용량).
 * 다음 예정(날짜 또는 누적 사용량)은 PmDue 가 계산. 주기 종류·카운터는 만든 뒤 고정.
 */
@Service
@RequiredArgsConstructor
public class PmPlanService {

    static final String PROG = "cms-pm-plan";
    static final Pattern PLAN_CD = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,59}$");
    static final Set<String> UNITS = Set.of("DAY", "WEEK", "MONTH", "YEAR");
    static final int MAX_ROWS = 1000;
    static final int PREVIEW_N = 12;

    private final CmsPmMapper mapper;
    private final CmsMapper cms;
    private final UserDirectory users;
    private final CmsCounterMapper counters;
    private final MwoService mwos;
    private final InboxService inbox;
    private final PolicyService policies;
    private final BizClock clock;
    private final ExcelExportService excel;
    private final MessageSource messages;

    @Transactional(readOnly = true)
    public List<PlanRow> list(String plantCd, String keyword, String useYn, Long equipId) {
        checkListArgs(keyword, useYn);
        List<PlanRow> rows = mapper.findPlans(blank(plantCd) ? null : plantCd.strip().toUpperCase(Locale.ROOT),
                blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT)), blank(useYn) ? null : useYn, equipId, MAX_ROWS);
        Map<String, ZoneId> zones = new HashMap<>();
        int lead = policies.intValue(Policies.CMS_PM_LEAD_DAYS);
        rows.forEach(p -> decorate(p, zones, lead));
        return rows;
    }

    private static void checkListArgs(String keyword, String useYn) {
        if (!blank(useYn) && !"Y".equals(useYn) && !"N".equals(useYn) || keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }

    /** grid(UI 단계 3 ①, docs/33 15.1) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link PlanSort}) */
    @Transactional(readOnly = true)
    public PlanPage grid(String plantCd, String keyword, String useYn, Integer page, Integer size, List<String> sort, Boolean total) {
        checkListArgs(keyword, useYn);
        String pl = blank(plantCd) ? null : plantCd.strip().toUpperCase(Locale.ROOT);
        String k = blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT));
        String u = blank(useYn) ? null : useYn;
        PageQuery pq = PageQuery.parse(page, size, sort, total, PlanSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("nextDueText,asc", "planCd,asc"));
        List<PlanRow> rows = mapper.findPlanPage(pl, k, u, null, pq);
        Map<String, ZoneId> zones = new HashMap<>();
        int lead = policies.intValue(Policies.CMS_PM_LEAD_DAYS);
        rows.forEach(p -> decorate(p, zones, lead));
        return new PlanPage(rows, pq.isWithTotal() ? mapper.countPlans(pl, k, u, null) : null);
    }

    /** grid 열 — 다음 예정(달력 날짜 / 사용량 누적 한 칸)·상태를 앞에. 주기·다음 예정 글자는 화면이 행 값으로(format(value, row)). 예정 상태는 계산 값이라 정렬 밖 */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("planCd", "cms.plan.col.planCd", 120).withSort(),
            ColumnSpec.text("planNm", "cms.plan.col.planNm", 180).withSort(),
            ColumnSpec.text("targetCd", "cms.mwo.col.target", 110).withSort(),
            ColumnSpec.text("nextDueText", "cms.plan.col.nextDue", 150).withSort(),
            ColumnSpec.text("state", "cms.mwo.col.status", 80),
            ColumnSpec.text("assigneeNm", "cms.mwo.col.assignee", 90).withSort(),
            ColumnSpec.text("targetNm", "cms.mwo.col.targetNm", 150).withPriority(3),
            ColumnSpec.text("tmplCd", "cms.plan.col.tmpl", 110).withSort(),
            ColumnSpec.code("execMode", "cms.plan.col.execMode", "CMS_EXEC_MODE", 90).withSort(),
            ColumnSpec.text("cycleType", "cms.plan.col.cycle", 160).withPriority(3),
            ColumnSpec.of("useYn", "cms.col.useYn", ColumnType.YN, 70).withSort());

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    /**
     * 엑셀 열 바꿈 — 화면이 행 값으로 합쳐 보이는 칸을 엑셀에서는 나눈다. 다음 예정 = 날짜(날짜 셀) + 사용량 + 지금 누적,
     * 상태 = 이름, 주기 = 종류·간격·단위·기준·카운터·사용량 간격·사용량 단위(코드는 이름)
     */
    static final Map<String, List<ColumnSpec>> EXPORT_REPLACE = Map.of(
            "nextDueText", List.of(ColumnSpec.of("nextDueDate", "cms.plan.col.nextDueDate", ColumnType.DATE, 100),
                    ColumnSpec.text("nextDueUsageText", "cms.plan.col.nextDueUsage", 120),
                    ColumnSpec.text("counterTotalText", "cms.plan.col.counterTotal", 120)),
            "state", List.of(ColumnSpec.text("stateNm", "cms.mwo.col.status", 80)),
            "cycleType", List.of(ColumnSpec.code("cycleType", "cms.plan.col.cycleType", "CMS_CYCLE_TYPE", 90),
                    ColumnSpec.of("calInterval", "cms.plan.col.interval", ColumnType.INT, 60),
                    ColumnSpec.code("calUnit", "cms.plan.col.unit", "CMS_CAL_UNIT", 60),
                    ColumnSpec.code("scheduleBasis", "cms.plan.col.basis", "CMS_SCHED_BASIS", 100),
                    ColumnSpec.text("counterCd", "cms.plan.col.counter", 110),
                    ColumnSpec.text("usageIntervalText", "cms.plan.col.usageInterval", 100),
                    ColumnSpec.text("counterUnit", "cms.plan.col.counterUnit", 80)));

    /** 엑셀(C16) — grid 와 같은 조건·정렬(허용 목록)·보이는 열. 예정 상태는 내보내는 시각 기준 계산(화면과 같은 decorate) */
    @Transactional(readOnly = true)
    public long export(PlanExport req, HttpServletResponse response) throws IOException {
        PlanExport r = req == null ? new PlanExport(null, null, null, null, null) : req;
        checkListArgs(r.keyword(), r.useYn());
        List<ColumnSpec> cols = CmsSupport.exportColumns(GRID_COLUMNS, r.columns(), EXPORT_REPLACE);
        String pl = blank(r.plantCd()) ? null : r.plantCd().strip().toUpperCase(Locale.ROOT);
        String k = blank(r.keyword()) ? null : like(r.keyword().strip().toUpperCase(Locale.ROOT));
        String u = blank(r.useYn()) ? null : r.useYn();
        PageQuery pq = PageQuery.sortOnly(r.sort(), PlanSort.values(), List.of("nextDueText,asc", "planCd,asc"));
        List<String> keys = new ArrayList<>();
        if (pl != null) {
            keys.add("plantCd");
        }
        if (k != null) {
            keys.add("keyword");
        }
        if (u != null) {
            keys.add("useYn");
        }
        Locale locale = LocaleContextHolder.getLocale();
        Map<String, ZoneId> zones = new HashMap<>();
        int lead = policies.intValue(Policies.CMS_PM_LEAD_DAYS);
        return excel.export(response, new ExportJob(PROG, "prog.cmsPmPlan", cols, mapper.countPlans(pl, k, u, null), keys),
                w -> mapper.findPlanPage(pl, k, u, null, pq).forEach(p -> {
                    decorate(p, zones, lead);
                    p.setStateNm(messages.getMessage("js.cms.state." + p.getState(), null, p.getState(), locale));
                    w.accept(p);
                }));
    }

    @Transactional(readOnly = true)
    public PlanRow get(long planId) {
        PlanRow p = mapper.findPlan(planId);
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        decorate(p, new HashMap<>(), policies.intValue(Policies.CMS_PM_LEAD_DAYS));
        return p;
    }

    void decorate(PlanRow p, Map<String, ZoneId> zones, int defaultLead) {
        if (p.isUsage()) {
            p.setState(PmDue.usageState(p.getNextDueUsage(), p.getCounterTotal(), p.getUsageLead()).name());
            return;
        }
        ZoneId z = zone(p.getPlantCd(), zones);
        p.setEffLeadDays(p.getLeadDays() != null ? p.getLeadDays() : defaultLead);
        p.setState(PmDue.state(p.getNextDueDate(), clock.instant().atZone(z).toLocalDate(), p.getEffLeadDays()).name());
    }

    ZoneId zone(String plantCd, Map<String, ZoneId> zones) {
        return zones.computeIfAbsent(plantCd == null ? "" : plantCd, k -> k.isEmpty() ? clock.zone() : clock.zone(k));
    }

    static PmDue.Rule rule(PlanRow p) {
        return new PmDue.Rule(p.getScheduleBasis(), p.getCalUnit(), p.getCalInterval(), p.getAnchorDate());
    }

    static PmDue.UsageRule usageRule(PlanRow p) {
        return new PmDue.UsageRule(p.getScheduleBasis(), p.getUsageInterval(), p.getAnchorUsage());
    }

    // ------------------------------------------------------------------ 등록·수정

    @Transactional
    public long create(PlanForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("CMS_PM_PLAN_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        PlanRow p = new PlanRow();
        String cd = blank(in.planCd()) ? null : in.planCd().strip().toUpperCase(Locale.ROOT);
        if (cd == null) {
            e.add("planCd", "REQUIRED");
        } else if (!PLAN_CD.matcher(cd).matches()) {
            e.add("planCd", "FORMAT");
        }
        p.setPlanCd(cd);
        TargetRow t = mwos.target(in.targetType(), in.targetCd(), e);
        if (t != null) {
            p.setTargetType(t.getTargetType());
            p.setEquipId("EQUIP".equals(t.getTargetType()) ? t.getId() : null);
            p.setAssetId("ASSET".equals(t.getTargetType()) ? t.getId() : null);
        }
        apply(p, null, in, t == null ? null : t.getClassId(), e);
        e.throwIfAny();
        if (p.isUsage()) {
            p.setNextDueUsage(PmDue.initialUsage(usageRule(p), null));
        } else {
            p.setNextDueDate(PmDue.initial(rule(p), null));
        }
        try {
            return mapper.insertPlan(p, me.loginId());
        } catch (DuplicateKeyException ex) {
            throw fieldError("planCd", "DUPLICATE");
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /** 수정 — 코드·대상·주기 종류·카운터 고정. 주기 규칙이 바뀌면 열린 예정이 없을 때만 다음 예정을 다시 계산 */
    @Transactional
    public void update(long planId, PlanForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (mapper.lockPlan(planId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        PlanRow cur = mapper.findPlan(planId);
        FieldErrors e = FieldErrors.create();
        if (!blank(in.planCd()) && !in.planCd().strip().toUpperCase(Locale.ROOT).equals(cur.getPlanCd())) {
            e.add("planCd", "FIXED");
        }
        if (!blank(in.targetType()) && !in.targetType().strip().equals(cur.getTargetType())) {
            e.add("targetType", "FIXED");
        }
        if (!blank(in.targetCd()) && !in.targetCd().strip().toUpperCase(Locale.ROOT).equals(cur.getTargetCd())) {
            e.add("targetCd", "FIXED");
        }
        boolean usage = cur.isUsage();
        PmDue.Rule before = usage ? null : rule(cur);
        PmDue.UsageRule beforeUsage = usage ? usageRule(cur) : null;
        Long classId = cur.getEquipId() == null ? null : cms.findEquipTarget(cur.getTargetCd()).getClassId();
        apply(cur, cur, in, classId, e);
        e.throwIfAny();
        if (cur.getOpenRunId() == null) {
            if (usage && !usageRule(cur).sameAs(beforeUsage)) {
                cur.setNextDueUsage(PmDue.initialUsage(usageRule(cur), cur.getLastDoneUsage()));
            } else if (!usage && !rule(cur).equals(before)) {
                cur.setNextDueDate(PmDue.initial(rule(cur), cur.getLastDoneDate()));
            }
        }
        cur.setRowVer(in.rowVer());
        try {
            if (mapper.updatePlan(cur, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /** 공통 칸 적용 (등록·수정) */
    private void apply(PlanRow p, PlanRow cur, PlanForm in, Long targetClassId, FieldErrors e) {
        p.setPlanNm(text(in.planNm(), "planNm", 100, true, e));
        TmplRow tmpl = null;
        if (blank(in.tmplCd())) {
            e.add("tmplCd", "REQUIRED");
        } else {
            String tcd = in.tmplCd().strip().toUpperCase(Locale.ROOT);
            tmpl = tcd.length() > 20 ? null : mapper.findTmplByCd(tcd);
            boolean same = cur != null && tmpl != null && tmpl.getTmplId() == cur.getTmplId();
            if (tmpl == null) {
                e.add("tmplCd", "NO_SUCH_CODE");
            } else if (!same && !"Y".equals(tmpl.getUseYn())) {
                e.add("tmplCd", "INACTIVE");
            } else if (!same && p.getTargetType() != null && (!tmpl.getTargetKind().equals(p.getTargetType())
                    || tmpl.getEquipClassId() != null && !tmpl.getEquipClassId().equals(targetClassId))) {
                e.add("tmplCd", "TMPL_TARGET");
            } else {
                p.setTmplId(tmpl.getTmplId());
            }
        }
        String mode = blank(in.execMode()) ? "WO" : in.execMode().strip();
        if (!"WO".equals(mode) && !"CHECK".equals(mode)) {
            e.add("execMode", "CODE");
        }
        p.setExecMode(mode);
        String cycle = cur != null ? cur.getCycleType() : blank(in.cycleType()) ? "CAL" : in.cycleType().strip();
        if (cur != null && !blank(in.cycleType()) && !in.cycleType().strip().equals(cur.getCycleType())) {
            e.add("cycleType", "FIXED");
        } else if (!"CAL".equals(cycle) && !"USAGE".equals(cycle)) {
            e.add("cycleType", "CODE");
        }
        p.setCycleType(cycle);
        String basis = blank(in.scheduleBasis()) ? "FIXED" : in.scheduleBasis().strip();
        if (!"FIXED".equals(basis) && !"FLOAT".equals(basis)) {
            e.add("scheduleBasis", "CODE");
        }
        p.setScheduleBasis(basis);
        if ("USAGE".equals(cycle)) {
            applyUsage(p, cur, in, e);
        } else {
            applyCal(p, in, e);
        }
        if (in.estMinutes() != null && (in.estMinutes() < 1 || in.estMinutes() > 10080)) {
            e.add("estMinutes", "RANGE", 1, 10080);
        }
        p.setEstMinutes(in.estMinutes());
        Long assignee = null;
        if (!blank(in.assigneeLoginId())) {
            String login = in.assigneeLoginId().strip();
            UserRef u = users.byLogin(login);
            if (u == null) {
                e.add("assigneeLoginId", "NO_SUCH_CODE");
            } else if (!(cur != null && Objects.equals(u.userId(), cur.getAssigneeId())) && !u.active()) {
                e.add("assigneeLoginId", "INACTIVE");
            } else {
                assignee = u.userId();
            }
        }
        p.setAssigneeId(assignee);
        p.setUseYn(yn(in.useYn(), "Y", "useYn", e));
        p.setRemark(text(in.remark(), "remark", 500, false, e));
    }

    /** 달력 주기 칸 — 사용량 칸은 비운다 */
    private void applyCal(PlanRow p, PlanForm in, FieldErrors e) {
        p.setCounterId(null);
        p.setUsageInterval(null);
        p.setUsageLead(null);
        p.setAnchorUsage(null);
        p.setNextDueUsage(null);
        if (blank(in.calUnit())) {
            e.add("calUnit", "REQUIRED");
        } else if (!UNITS.contains(in.calUnit().strip())) {
            e.add("calUnit", "CODE");
        }
        p.setCalUnit(blank(in.calUnit()) ? null : in.calUnit().strip());
        if (in.calInterval() == null) {
            e.add("calInterval", "REQUIRED");
        } else if (in.calInterval() < 1 || in.calInterval() > 999) {
            e.add("calInterval", "RANGE", 1, 999);
        }
        p.setCalInterval(in.calInterval() == null ? 1 : in.calInterval());
        LocalDate anchor = date(in.anchorDate(), "anchorDate", e);
        if (blank(in.anchorDate())) {
            e.add("anchorDate", "REQUIRED");
        }
        p.setAnchorDate(anchor);
        if (in.leadDays() != null && (in.leadDays() < 0 || in.leadDays() > 90)) {
            e.add("leadDays", "RANGE", 0, 90);
        }
        p.setLeadDays(in.leadDays());
    }

    /**
     * 사용량 주기 칸 — 카운터(같은 대상·사용 중, 만든 뒤 고정)·간격(> 0)·선행(0 이상 간격 미만, 비우면 0)·첫 예정 사용량
     * (등록 때 비우면 지금 누적 + 간격). 수치 자릿수는 카운터 자릿수. 달력 칸은 비운다
     */
    private void applyUsage(PlanRow p, PlanRow cur, PlanForm in, FieldErrors e) {
        p.setCalUnit(null);
        p.setCalInterval(null);
        p.setAnchorDate(null);
        p.setNextDueDate(null);
        p.setLeadDays(null);
        CounterRow c = null;
        if (cur != null) {
            if (!blank(in.counterCd()) && !in.counterCd().strip().toUpperCase(Locale.ROOT).equals(cur.getCounterCd())) {
                e.add("counterCd", "FIXED");
            }
            c = counters.findCounter(cur.getCounterId());
        } else if (blank(in.counterCd())) {
            e.add("counterCd", "REQUIRED");
        } else {
            String ccd = in.counterCd().strip().toUpperCase(Locale.ROOT);
            CounterRow found = ccd.length() > 30 ? null : counters.findCounterByCd(ccd);
            if (found == null) {
                e.add("counterCd", "NO_SUCH_CODE");
            } else if (!"Y".equals(found.getUseYn())) {
                e.add("counterCd", "INACTIVE");
            } else if (p.getTargetType() != null && (!found.getTargetType().equals(p.getTargetType())
                    || !Objects.equals(found.getEquipId(), p.getEquipId()) || !Objects.equals(found.getAssetId(), p.getAssetId()))) {
                e.add("counterCd", "COUNTER_TARGET");
            } else {
                c = found;
            }
        }
        int scale = c == null ? 6 : c.getScale();
        BigDecimal interval = decimal(in.usageInterval(), "usageInterval", scale, e);
        if (blank(in.usageInterval())) {
            e.add("usageInterval", "REQUIRED");
        } else if (interval != null && interval.signum() <= 0) {
            e.add("usageInterval", "POSITIVE");
            interval = null;
        }
        BigDecimal lead = decimal(in.usageLead(), "usageLead", scale, e);
        if (lead != null && lead.signum() < 0) {
            e.add("usageLead", "NOT_NEGATIVE");
        } else if (lead != null && interval != null && lead.compareTo(interval) >= 0) {
            e.add("usageLead", "LEAD_GE_INTERVAL");
        }
        BigDecimal anchor = decimal(in.anchorUsage(), "anchorUsage", scale, e);
        if (anchor != null && anchor.signum() < 0) {
            e.add("anchorUsage", "NOT_NEGATIVE");
        } else if (blank(in.anchorUsage())) {
            if (cur != null) {
                e.add("anchorUsage", "REQUIRED");
            } else if (c != null && interval != null) {
                anchor = c.getTotal().add(interval);   // 지금 누적부터 한 간격 뒤
            }
        }
        if (c != null) {
            p.setCounterId(c.getCounterId());
            p.setCounterCd(c.getCounterCd());
            p.setCounterScale(c.getScale());
            p.setCounterTotal(c.getTotal());
        }
        p.setUsageInterval(interval);
        p.setUsageLead(lead);
        p.setAnchorUsage(anchor);
    }

    // ------------------------------------------------------------------ 달력 보기 (C27, docs/33 13.14)

    static final int MAX_CAL_ITEMS = 2000;
    static final int CAL_MONTHS = 24;
    static final Pattern MONTH = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    /**
     * 공장 × 월 달력(읽기 전용). 월 범위·"오늘"은 공장 시간대(BizClock). RUN = 그 달 예정일인 점검 실행(상태 무관),
     * FORECAST = 사용 중 달력 주기 계획의 아직 만들지 않은 예정(PmDue — 매번 예정일에 했다고 보고, 이미 지난 예정은 오늘 했다고 보고).
     * 사용량 주기는 날짜를 예측하지 않는다(생성된 예정만 RUN 으로).
     */
    @Transactional(readOnly = true)
    public CmsPmDtos.CalView calendar(String plantCd, String month) {
        FieldErrors e = FieldErrors.create();
        String plant = blank(plantCd) ? null : plantCd.strip().toUpperCase(Locale.ROOT);
        if (plant == null) {
            e.add("plantCd", "REQUIRED");
        } else if (cms.findPlants().stream().noneMatch(o -> o.code().equals(plant))) {
            e.add("plantCd", "NO_SUCH_CODE");
        }
        if (!blank(month) && !MONTH.matcher(month.strip()).matches()) {
            e.add("month", "FORMAT");
        }
        e.throwIfAny();
        LocalDate today = clock.instant().atZone(clock.zone(plant)).toLocalDate();
        LocalDate first = blank(month) ? today.withDayOfMonth(1) : LocalDate.parse(month.strip() + "-01");
        LocalDate thisMonth = today.withDayOfMonth(1);
        if (first.isBefore(thisMonth.minusMonths(CAL_MONTHS)) || first.isAfter(thisMonth.plusMonths(CAL_MONTHS))) {
            throw fieldError("month", "RANGE", -CAL_MONTHS, CAL_MONTHS);
        }
        LocalDate last = first.withDayOfMonth(first.lengthOfMonth());
        int defaultLead = policies.intValue(Policies.CMS_PM_LEAD_DAYS);
        List<CmsPmDtos.CalItem> items = new ArrayList<>();
        Set<String> runKeys = new HashSet<>();
        List<CmsPmDtos.CalRunRow> runs = mapper.findCalRuns(plant, first, last, MAX_CAL_ITEMS + 1);
        for (CmsPmDtos.CalRunRow r : runs) {
            boolean usage = "USAGE".equals(r.getCycleType());
            String state = "OPEN".equals(r.getStatus()) && !usage
                    ? PmDue.state(r.getDueDate(), today, r.getLeadDays() != null ? r.getLeadDays() : defaultLead).name() : null;
            items.add(new CmsPmDtos.CalItem(r.getDueDate().toString(), "RUN", r.getPlanId(), r.getPlanCd(), r.getPlanNm(), r.getTargetCd(),
                    r.getCycleType(), r.getRunId(), r.getStatus(), r.getResult(), state,
                    usage ? CmsPmDtos.text(r.getDueUsage(), r.getCounterScale()) : null));
            runKeys.add(r.getPlanId() + "|" + r.getDueDate());
        }
        List<CmsPmDtos.CalPlanRow> calPlans = mapper.findCalPlans(plant, MAX_ROWS + 1);
        boolean truncated = runs.size() > MAX_CAL_ITEMS || calPlans.size() > MAX_ROWS;
        for (CmsPmDtos.CalPlanRow p : calPlans.subList(0, Math.min(calPlans.size(), MAX_ROWS))) {
            int lead = p.getLeadDays() != null ? p.getLeadDays() : defaultLead;
            for (LocalDate d : forecast(p, today, first, last)) {
                if (!runKeys.contains(p.getPlanId() + "|" + d)) {
                    items.add(new CmsPmDtos.CalItem(d.toString(), "FORECAST", p.getPlanId(), p.getPlanCd(), p.getPlanNm(), p.getTargetCd(),
                            "CAL", null, null, null, PmDue.state(d, today, lead).name(), null));
                }
            }
        }
        items.sort(Comparator.comparing(CmsPmDtos.CalItem::date).thenComparing(CmsPmDtos.CalItem::planCd)
                .thenComparing(CmsPmDtos.CalItem::kind, Comparator.reverseOrder()));   // 같은 날 같은 계획이면 RUN 먼저
        if (items.size() > MAX_CAL_ITEMS) {
            items = new ArrayList<>(items.subList(0, MAX_CAL_ITEMS));
            truncated = true;
        }
        return new CmsPmDtos.CalView(plant, first.toString().substring(0, 7), today.toString(), items, truncated,
                mapper.countCalUsagePlans(plant));
    }

    /**
     * 계획 하나의 그 달 예상 날짜. 시작 = 다음 예정일 — 열린 예정이 있으면 그것은 RUN 이라 그다음부터.
     * 지난 예정은 오늘 했다고 보고(FIXED = 오늘 뒤 첫 고정 예정, FLOAT = 오늘 + 간격), 그 뒤는 매번 예정일에 했다고 본다(미리보기와 같음).
     */
    static List<LocalDate> forecast(CmsPmDtos.CalPlanRow p, LocalDate today, LocalDate first, LocalDate last) {
        PmDue.Rule r = new PmDue.Rule(p.getScheduleBasis(), p.getCalUnit(), p.getCalInterval(), p.getAnchorDate());
        LocalDate d = p.getNextDueDate();
        if (p.getOpenDueDate() != null) {
            d = PmDue.next(r, p.getOpenDueDate(), p.getOpenDueDate().isBefore(today) ? today : null);
        }
        List<LocalDate> out = new ArrayList<>();
        for (int guard = 0; !d.isAfter(last) && guard < 1000; guard++) {
            if (!d.isBefore(first)) {
                out.add(d);
            } else if ("FIXED".equals(r.basis()) && !d.isBefore(today)) {
                d = PmDue.fixedAfter(r, first.minusDays(1));   // 고정 예정은 첫 예정일에서 다시 더한 값 — 달 앞까지 건너뛴다
                continue;
            }
            d = PmDue.next(r, d, d.isBefore(today) ? today : null);
        }
        return out;
    }

    /** 미리보기 — 저장 전 규칙으로 앞으로 12 회(날짜 또는 누적 사용량, 글자) */
    @Transactional(readOnly = true)
    public List<String> preview(PreviewForm in) {
        if (in != null && "USAGE".equals(in.cycleType())) {
            return previewUsage(in);
        }
        FieldErrors e = FieldErrors.create();
        String unit = in == null || blank(in.calUnit()) ? null : in.calUnit().strip();
        if (unit == null || !UNITS.contains(unit)) {
            e.add("calUnit", unit == null ? "REQUIRED" : "CODE");
        }
        Integer iv = in == null ? null : in.calInterval();
        if (iv == null || iv < 1 || iv > 999) {
            e.add("calInterval", iv == null ? "REQUIRED" : "RANGE", 1, 999);
        }
        String basis = in == null || blank(in.scheduleBasis()) ? "FIXED" : in.scheduleBasis().strip();
        if (!"FIXED".equals(basis) && !"FLOAT".equals(basis)) {
            e.add("scheduleBasis", "CODE");
        }
        LocalDate anchor = in == null ? null : date(in.anchorDate(), "anchorDate", e);
        if (anchor == null && e.isEmpty()) {
            e.add("anchorDate", "REQUIRED");
        }
        LocalDate from = in == null ? null : date(in.from(), "from", e);
        e.throwIfAny();
        PmDue.Rule r = new PmDue.Rule(basis, unit, iv, anchor);
        return PmDue.preview(r, from == null ? anchor : from, PREVIEW_N).stream().map(LocalDate::toString).toList();
    }

    private List<String> previewUsage(PreviewForm in) {
        FieldErrors e = FieldErrors.create();
        CounterRow c = blank(in.counterCd()) || in.counterCd().strip().length() > 30 ? null
                : counters.findCounterByCd(in.counterCd().strip().toUpperCase(Locale.ROOT));
        int scale = c == null ? 6 : c.getScale();
        BigDecimal iv = decimal(in.usageInterval(), "usageInterval", scale, e);
        if (blank(in.usageInterval())) {
            e.add("usageInterval", "REQUIRED");
        } else if (iv != null && iv.signum() <= 0) {
            e.add("usageInterval", "POSITIVE");
        }
        String basis = blank(in.scheduleBasis()) ? "FIXED" : in.scheduleBasis().strip();
        if (!"FIXED".equals(basis) && !"FLOAT".equals(basis)) {
            e.add("scheduleBasis", "CODE");
        }
        BigDecimal anchor = decimal(in.anchorUsage(), "anchorUsage", scale, e);
        if (anchor == null && e.isEmpty()) {
            if (c == null) {
                e.add("anchorUsage", "REQUIRED");
            } else {
                anchor = c.getTotal().add(iv);
            }
        }
        if (anchor != null && anchor.signum() < 0) {
            e.add("anchorUsage", "NOT_NEGATIVE");
        }
        BigDecimal from = decimal(in.fromUsage(), "fromUsage", scale, e);
        e.throwIfAny();
        PmDue.UsageRule r = new PmDue.UsageRule(basis, iv, anchor);
        return PmDue.previewUsage(r, from == null ? anchor : from, PREVIEW_N).stream().map(v -> CmsPmDtos.text(v, scale)).toList();
    }

    /** 클래스 일괄 — 클래스의 사용 중 설비마다 계획(코드 = 점검표코드-설비코드), 있는 코드는 건너뜀 (D5) */
    @Transactional
    public BulkResult bulk(BulkForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("CMS_PM_PLAN_BULK");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        Long classId = null;
        if (blank(in.classCd())) {
            e.add("classCd", "REQUIRED");
        } else {
            String ccd = in.classCd().strip().toUpperCase(Locale.ROOT);
            classId = ccd.length() > 30 ? null : cms.findClassId(ccd, true);
            if (classId == null) {
                e.add("classCd", "NO_SUCH_CODE");
            }
        }
        e.throwIfAny();
        int created = 0;
        int skipped = 0;
        List<String> codes = new ArrayList<>();
        String tmplCd = blank(in.tmplCd()) ? "" : in.tmplCd().strip().toUpperCase(Locale.ROOT);
        TmplRow tmpl = tmplCd.isEmpty() || tmplCd.length() > 20 ? null : mapper.findTmplByCd(tmplCd);
        for (String equipCd : mapper.findActiveEquipOfClass(classId)) {
            TargetRow t = cms.findEquipTarget(equipCd);
            String cd = tmplCd + "-" + equipCd;
            if (cd.length() > 60 || mapper.planCdExists(cd)) {
                skipped++;
                continue;
            }
            String nm = (tmpl == null ? "" : tmpl.getTmplNm() + " · ") + t.getName();
            PlanForm f = new PlanForm(cd, nm.length() > 100 ? nm.substring(0, 100) : nm, "EQUIP", equipCd, in.tmplCd(), in.execMode(),
                    in.calUnit(), in.calInterval(), in.scheduleBasis(), in.anchorDate(), in.leadDays(), in.assigneeLoginId(), null, "Y",
                    null, null, "CAL", null, null, null, null);   // 일괄은 달력 주기만 — 사용량은 설비마다 카운터가 달라 개별로
            FieldErrors fe = FieldErrors.create();
            PlanRow p = new PlanRow();
            p.setPlanCd(cd);
            p.setTargetType("EQUIP");
            p.setEquipId(t.getId());
            apply(p, null, f, t.getClassId(), fe);
            fe.throwIfAny();   // 규칙 오류는 모든 설비에 같으니 첫 설비에서 칸 오류로 돌려준다
            p.setNextDueDate(PmDue.initial(rule(p), null));
            mapper.insertPlan(p, me.loginId());
            created++;
            codes.add(cd);
        }
        return new BulkResult(created, skipped, codes);
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "fk_cms_pm_plan_equip_comp", "fk_cms_pm_plan_asset_comp" -> fieldError("targetCd", "NO_SUCH_CODE");
            case "fk_cms_pm_plan_tmpl_comp", "fk_cms_pm_plan_tmpl" -> fieldError("tmplCd", "TMPL_TARGET");
            case "fk_cms_pm_plan_assignee_comp" -> fieldError("assigneeLoginId", "NO_SUCH_CODE");
            case "fk_cms_pm_plan_counter_comp" -> fieldError("counterCd", "COUNTER_TARGET");
            default -> e;
        };
    }
}
