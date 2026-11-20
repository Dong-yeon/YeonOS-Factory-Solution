package io.smarthappy.module.aps;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.aps.ApsDtos.DayTick;
import io.smarthappy.module.aps.ApsDtos.DiscardForm;
import io.smarthappy.module.aps.ApsDtos.EquipRow;
import io.smarthappy.module.aps.ApsDtos.Gantt;
import io.smarthappy.module.aps.ApsDtos.GanttRes;
import io.smarthappy.module.aps.ApsDtos.MatOutRow;
import io.smarthappy.module.aps.ApsDtos.NodeRow;
import io.smarthappy.module.aps.ApsDtos.OpOutRow;
import io.smarthappy.module.aps.ApsDtos.OpSrc;
import io.smarthappy.module.aps.ApsDtos.OffDay;
import io.smarthappy.module.aps.ApsDtos.PinSrc;
import io.smarthappy.module.aps.ApsDtos.PlanCrit;
import io.smarthappy.module.aps.ApsDtos.PlanDetail;
import io.smarthappy.module.aps.ApsDtos.PlanForm;
import io.smarthappy.module.aps.ApsDtos.PlanInsert;
import io.smarthappy.module.aps.ApsDtos.PlanList;
import io.smarthappy.module.aps.ApsDtos.PlanOpRow;
import io.smarthappy.module.aps.ApsDtos.PlanPage;
import io.smarthappy.module.aps.ApsDtos.PlanRow;
import io.smarthappy.module.aps.ApsDtos.PlanSort;
import io.smarthappy.module.aps.ApsDtos.ShiftRow;
import io.smarthappy.module.aps.ApsDtos.SupplyOutRow;
import io.smarthappy.module.aps.ApsDtos.WoOutRow;
import io.smarthappy.module.aps.ApsDtos.WoSrc;
import io.smarthappy.module.aps.ApsDtos.WorkWindow;
import io.smarthappy.module.aps.FiniteScheduler.OpOut;
import io.smarthappy.module.aps.FiniteScheduler.Res;
import io.smarthappy.module.aps.FiniteScheduler.WoOut;
import io.smarthappy.module.master.equip.EquipDtos.PlantRow;
import io.smarthappy.module.master.workforce.WorkCalendar;
import io.smarthappy.module.production.link.WoMaterialQuery;
import io.smarthappy.module.srm.link.PoSupplyQuery;
import io.smarthappy.module.wms.link.StockQuery;
import io.smarthappy.module.master.workforce.WorkCalendar.DayPlan;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 생산 계획(간트) (aps-plan) — APS 1단계, docs/38.
 *
 * - 계산 = 한 트랜잭션(반복 읽기 — 지시·공정을 같은 시점으로 읽는다): 입력 읽기 → {@link FiniteScheduler} → 계획 머리 + 결과(추가만)
 * - 지시(prd_wo·prd_wo_proc)는 읽기만 한다 — 시뮬레이션. 반영은 2단계(MES 서비스 경유)
 * - 다시 계산하면 새 버전, 버전은 폐기 또는 반영(2단계 — {@link ApsApplyService})
 */
@Service
@RequiredArgsConstructor
public class ApsService {

    static final String PROG = "aps-plan";
    static final String RULE = "APS_PLAN";
    static final int MAX_DAYS = 31;
    static final int DEFAULT_DAYS = 7;
    static final int MAX_LEAD_HR = 720;
    static final int DEFAULT_LEAD_HR = 24;
    static final int MAX_WOS = 1000;
    static final int MAX_OPS = 5000;
    static final int LIST_LIMIT = 500;
    static final int MAX_PERIOD_DAYS = 366;
    private static final int CHUNK = 500;
    private static final LocalDate MIN_DAY = LocalDate.of(2000, 1, 1);
    private static final LocalDate MAX_DAY = LocalDate.of(2999, 12, 1);
    /** 쉬는 날 사본 — 날짜는 ISO 글자(yyyy-MM-dd) */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ApsMapper mapper;
    private final NumberingService numbering;
    private final InboxService inbox;
    private final BizClock clock;
    private final PolicyService policies;
    private final WorkCalendar calendar;
    /** 5단계 읽기 계약(A28) — 모듈이 없는 설치본은 NONE(빈 결과) */
    private final ObjectProvider<WoMaterialQuery> materialQueries;
    private final ObjectProvider<StockQuery> stockQueries;
    private final ObjectProvider<PoSupplyQuery> poQueries;

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public List<PlantRow> plants() {
        return mapper.findPlants();
    }

    /** 계산일(회사 시간대) 기간으로 계획 목록 */
    @Transactional(readOnly = true)
    public PlanList list(String plantCd, String from, String to) {
        PlanCrit c = criteria(plantCd, from, to);
        List<PlanRow> rows = mapper.findPlans(c, LIST_LIMIT + 1);
        boolean more = rows.size() > LIST_LIMIT;
        return new PlanList(more ? rows.subList(0, LIST_LIMIT) : rows, more);
    }

    /**
     * grid(UI 단계 3, docs/29 #45) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link PlanSort}, 최대 3개, 모르는 열 400).
     * 기간 필수·상한도 목록과 같다(행 수 상한 대신 페이지). 기본 순서 = 목록과 같은 계산 시각 내림차순
     */
    @Transactional(readOnly = true)
    public PlanPage grid(String plantCd, String from, String to, Integer page, Integer size, List<String> sort, Boolean total) {
        PlanCrit c = criteria(plantCd, from, to);
        PageQuery pq = PageQuery.parse(page, size, sort, total, PlanSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("calculatedAt,desc"));
        return new PlanPage(mapper.findPlanPage(c, pq), pq.isWithTotal() ? mapper.countPlans(c) : null);
    }

    /** grid 열(화면 목록과 같은 칸) — 정렬 가능 = {@link PlanSort} 에 있는 열. 상태 글자·배지는 화면(format·status) */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("planNo", "apsPlan.col.planNo", 120).withSort(),
            ColumnSpec.text("planNm", "apsPlan.col.planNm", 160).withSort().withFlex(),
            ColumnSpec.text("plantCd", "apsPlan.col.plant", 70).withSort(),
            ColumnSpec.of("horizonStart", "apsPlan.col.horizonStart", ColumnType.DATE, 100).withSort(),
            ColumnSpec.of("horizonDays", "apsPlan.col.horizonDays", ColumnType.INT, 60),
            ColumnSpec.of("woCnt", "apsPlan.col.woCnt", ColumnType.INT, 60).withPriority(3),
            ColumnSpec.of("opCnt", "apsPlan.col.opCnt", ColumnType.INT, 60).withPriority(3),
            ColumnSpec.of("lateWoCnt", "apsPlan.col.late", ColumnType.INT, 60).withSort(),
            ColumnSpec.of("unschedOpCnt", "apsPlan.col.unsched", ColumnType.INT, 60).withSort(),
            ColumnSpec.text("status", "apsPlan.col.status", 90).withSort(),
            ColumnSpec.of("calculatedAt", "apsPlan.col.calculatedAt", ColumnType.DATETIME, 140).withSort().withPriority(2));

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    /** 목록·grid 공통 조건 검증 — 계산일(회사 시간대) [from, to + 1일), 기간 필수·366일 상한, 공장 선택 */
    private PlanCrit criteria(String plantCd, String from, String to) {
        FieldErrors e = FieldErrors.create();
        LocalDate f = OrderService.date(from, "from", true, e);
        LocalDate t = OrderService.date(to, "to", true, e);
        if (f != null && t != null) {
            if (f.isAfter(t)) {
                e.add("to", "INVALID_RANGE");
            } else if (ChronoUnit.DAYS.between(f, t) + 1 > MAX_PERIOD_DAYS) {
                e.add("to", "RANGE_TOO_LONG", ChronoUnit.DAYS.between(f, t) + 1, MAX_PERIOD_DAYS);
            }
        }
        e.throwIfAny();
        ZoneId zone = clock.zone();
        return new PlanCrit(blank(plantCd) ? null : plantCd.strip().toUpperCase(Locale.ROOT),
                f.atStartOfDay(zone).toOffsetDateTime(), t.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
    }

    @Transactional(readOnly = true)
    public PlanDetail detail(long planId) {
        PlanRow p = plan(planId);
        return new PlanDetail(p, mapper.findPlanWos(planId), mapper.findPlanOps(planId, false), mapper.findPlanMats(planId));
    }

    /** 간트: 자원 행(설비 → 작업장 → 외주, 코드 순) + 계획된 공정 막대 + 계산 때 근무 구간 */
    @Transactional(readOnly = true)
    public Gantt gantt(long planId) {
        PlanRow p = plan(planId);
        List<PlanOpRow> ops = mapper.findPlanOps(planId, true);
        Map<String, GanttRes> res = new LinkedHashMap<>();
        for (PlanOpRow o : ops) {
            GanttRes g = switch (o.getResKind()) {
                case "EQUIP" -> new GanttRes("EQUIP:" + o.getEquipId(), "EQUIP", o.getEquipId(), null, o.getEquipCd(), o.getEquipNm());
                case "NODE" -> new GanttRes("NODE:" + o.getNodeId(), "NODE", null, o.getNodeId(), o.getNodeCd(), o.getNodeNm());
                default -> new GanttRes("OUTSRC", "OUTSRC", null, null, null, null);
            };
            res.putIfAbsent(g.key(), g);
        }
        List<GanttRes> rows = new ArrayList<>(res.values());
        rows.sort(Comparator.comparingInt((GanttRes g) -> "EQUIP".equals(g.kind()) ? 0 : "NODE".equals(g.kind()) ? 1 : 2)
                .thenComparing(g -> g.code() == null ? "" : g.code()));
        ZoneId zone = ZoneId.of(p.getTimeZone());
        List<DayTick> days = new ArrayList<>();
        for (int i = 0; i <= p.getHorizonDays(); i++) {
            LocalDate d = p.getHorizonStart().plusDays(i);
            days.add(new DayTick(d, d.atStartOfDay(zone).toEpochSecond()));
        }
        // 끌어 놓기 후보(3단계): 공정의 지금 후보 자원 — 계산과 같은 규칙, 발행 지시는 지금 배정 설비
        Function<FiniteScheduler.OpIn, List<Res>> cand = resources(p.getPlantCd());
        Map<Long, Long> curEquip = new HashMap<>();
        mapper.findCurEquips(planId).forEach(c -> curEquip.put(c.getPlanOpId(), c.getCurEquipId()));
        for (PlanOpRow o : ops) {
            if ("OUTSRC".equals(o.getResKind())) {
                o.setCands(List.of("OUTSRC"));
            } else if (o.getNodeId() != null) {
                FiniteScheduler.OpIn in = new FiniteScheduler.OpIn();
                in.nodeId = o.getNodeId();
                in.equipId = curEquip.get(o.getPlanOpId());
                o.setCands(cand.apply(in).stream().map(Res::key).toList());
            } else {
                o.setCands(List.of());
            }
        }
        return new Gantt(p, days, rows, ops, windows(mapper.findWindows(planId)), mapper.findPinList(p.getPlantCd()),
                offDays(mapper.findOffDays(planId)));
    }

    // ------------------------------------------------------------------ 계산

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public long calculate(PlanForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("APS_PLAN_CALC");
        return calculate(in, me.loginId());
    }

    /** 계산 본체 — 회사 범위·트랜잭션은 호출자 */
    long calculate(PlanForm in, String by) {
        long t0 = System.nanoTime();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String plant = null;
        if (blank(in.plantCd())) {
            e.add("plantCd", "REQUIRED");
        } else {
            plant = in.plantCd().strip().toUpperCase(Locale.ROOT);
        }
        LocalDate first = OrderService.date(in.horizonStart(), "horizonStart", true, e);
        if (first != null && (first.isBefore(MIN_DAY) || first.isAfter(MAX_DAY))) {
            e.add("horizonStart", "FORMAT");
        }
        int days = in.horizonDays() == null ? DEFAULT_DAYS : in.horizonDays();
        if (days < 1 || days > MAX_DAYS) {
            e.add("horizonDays", "RANGE", 1, MAX_DAYS);
        }
        int lead = in.outsrcLeadHr() == null ? DEFAULT_LEAD_HR : in.outsrcLeadHr();
        if (lead < 0 || lead > MAX_LEAD_HR) {
            e.add("outsrcLeadHr", "RANGE", 0, MAX_LEAD_HR);
        }
        String nm = OrderService.text(in.planNm(), "planNm", 100, e);
        MaterialPlanner.Mode matMode = MaterialPlanner.Mode.WARN;
        if (!blank(in.matMode())) {
            try {
                matMode = MaterialPlanner.Mode.valueOf(in.matMode().strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                e.add("matMode", "FORMAT");
            }
        }
        boolean matPo = !Boolean.FALSE.equals(in.matPo());  // A30 재판정 2026-10-22 — 기본 켬
        e.throwIfAny();
        Prepared pp = prepare(plant, first, days, lead, matMode, matPo, null);
        ZoneId zone = pp.zone();
        Instant from = pp.from();
        Instant to = pp.to();
        ShiftCalendar cal = pp.cal();
        List<OffDay> offDays = pp.offDays();
        MatInput matIn = pp.matIn();
        Map<Long, WoSrc> woById = pp.woById();
        Map<Long, Integer> procRowVer = pp.procRowVer();
        List<WoOut> result = pp.scheduler().schedule(new ArrayList<>(pp.inputs().values()));

        int late = 0;
        int unsched = 0;
        int opCnt = 0;
        Instant makespan = null;
        int matShort = 0;
        List<WoOutRow> woRows = new ArrayList<>();
        for (WoOut r : result) {
            WoSrc w = woById.get(r.wo.woId);
            late += r.late ? 1 : 0;
            String matCd = matIn.noBom().contains(w.getWoId()) ? "NO_BOM" : matIn.planner().isShort(w.getWoId()) ? "SHORT" : null;
            matShort += matCd != null ? 1 : 0;
            woRows.add(new WoOutRow(r.seq, w.getWoId(), w.getWoNo(), w.getStatus(), w.getItemId(), w.getPlanQty(), w.getUnitCd(),
                    w.getPriority(), w.getPlanStart(), w.getPlanEnd(), odt(r.start), odt(r.end), r.late ? "Y" : "N", r.exc, w.getRowVer(),
                    w.getDueDate(), w.getDueSrc(), matCd));
            for (OpOut o : r.ops) {
                opCnt++;
                if (o.exc != null) {
                    unsched++;
                } else if (makespan == null || o.end.isAfter(makespan)) {
                    makespan = o.end;
                }
            }
        }
        String planNo = numbering.nextOrDefault(RULE, "생산 계획 번호", "APS{yyMMdd}-{seq:3}", "DAY");
        int ms = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - t0) / 1_000_000);
        long planId = mapper.insertPlan(new PlanInsert(planNo, plant, nm, first, days, lead, zone.getId(), odt(from), odt(to),
                windowsJson(cal), result.size(), opCnt, late, unsched, odt(makespan), ms, JSON.writeValueAsString(offDays), matMode.name(),
                matPo ? "Y" : "N", matShort), by);
        for (int i = 0; i < woRows.size(); i += CHUNK) {
            mapper.insertWos(planId, woRows.subList(i, Math.min(woRows.size(), i + CHUNK)));
        }
        Map<Long, Long> planWo = new HashMap<>();
        mapper.findPlanWoKeys(planId).forEach(k -> planWo.put(k.getWoId(), k.getPlanWoId()));
        List<OpOutRow> opRows = new ArrayList<>();
        for (WoOut r : result) {
            long pw = planWo.get(r.wo.woId);
            for (OpOut o : r.ops) {
                FiniteScheduler.OpIn op = o.op;
                opRows.add(new OpOutRow(pw, r.wo.woId, op.woProcId, op.opSeq, op.procId, op.procCd, op.procNm, op.nodeId,
                        o.res == null ? null : o.res.equipId(), o.res == null ? null : o.res.kind(), op.qty, o.setupSec, o.runSec,
                        odt(o.start), odt(o.end), o.exc, op.woProcId == null ? null : procRowVer.get(op.woProcId), o.pinId, o.warn));
            }
        }
        for (int i = 0; i < opRows.size(); i += CHUNK) {
            mapper.insertOps(planId, opRows.subList(i, Math.min(opRows.size(), i + CHUNK)));
        }
        List<MatOutRow> matRows = matIn.planner().outs().stream()
                .map(m -> new MatOutRow(planWo.get(m.woId), m.woId, m.opSeq, m.srcOpSeq, m.itemId, m.needQty, m.unitCd, odt(m.needAt),
                        m.allocQty, m.shortQty, odt(m.coveredAt)))
                .toList();
        for (int i = 0; i < matRows.size(); i += CHUNK) {
            mapper.insertMats(planId, matRows.subList(i, Math.min(matRows.size(), i + CHUNK)));
        }
        for (int i = 0; i < matIn.supplies().size(); i += CHUNK) {
            mapper.insertSupplies(planId, matIn.supplies().subList(i, Math.min(matIn.supplies().size(), i + CHUNK)));
        }
        return planId;
    }

    /** 계산 입력(계획 계산·CTP 공통) — 지시·공정·고정·달력·자재를 계산 트랜잭션 안에서 읽어 스케줄러까지 만든다 */
    record Prepared(ZoneId zone, Instant from, Instant to, ShiftCalendar cal, List<OffDay> offDays, MatInput matIn,
            Map<Long, WoSrc> woById, Map<Long, FiniteScheduler.WoIn> inputs, Map<Long, Integer> procRowVer, FiniteScheduler scheduler) {
    }

    /** CTP 가상 지시(6단계) — 지시 id 음수, 소요는 MES 새 지시 전개 */
    record Virtual(FiniteScheduler.WoIn wo, List<MaterialPlanner.Need> needs) {
    }

    Prepared prepare(String plant, LocalDate first, int days, int lead, MaterialPlanner.Mode matMode, boolean matPo, Virtual virtual) {
        if (!"Y".equals(mapper.plantUseYn(plant))) {
            throw OrderService.fieldError("plantCd", "NO_SUCH_CODE");
        }
        List<ShiftRow> shifts = mapper.findShifts(plant);
        if (shifts.isEmpty()) {
            throw OrderService.fieldError("plantCd", "APS_NO_SHIFT");
        }
        ZoneId zone = clock.zone(plant);
        LocalDate last = first.plusDays(days - 1L);
        Instant start = first.atStartOfDay(zone).toInstant();
        Instant now = clock.instant();
        Instant nowUp = now.truncatedTo(ChronoUnit.MINUTES).equals(now) ? now : now.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60);
        Instant from = start.isAfter(nowUp) ? start : nowUp;
        Instant to = last.plusDays(1).atStartOfDay(zone).toInstant();
        if (!from.isBefore(to)) {
            throw OrderService.fieldError("horizonStart", "PAST");
        }
        List<WoSrc> wos = mapper.findWos(plant, last, MAX_WOS + 1);
        List<OpSrc> ops = mapper.findOps(plant, last, MAX_OPS + 1);
        if (wos.size() > MAX_WOS || ops.size() > MAX_OPS) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("horizonDays", "APS_TOO_LARGE", MAX_WOS, MAX_OPS).list());
        }

        // 공장 휴일 달력(MES, 4단계): 작업일마다 일하는 근무조 → 실제 시작일별로 펼친다. 첫날 전전날부터(전날 시작 야간조의 작업일)
        Map<LocalDate, DayPlan> calDays = calendar.range(plant, first.minusDays(2), last);
        ShiftCalendar cal = ShiftCalendar.build(ShiftCalendar.byStartDay(calDays.values(), first, last), zone, from, to);
        List<OffDay> offDays = calDays.values().stream().filter(d -> !d.workday() && !d.day().isBefore(first))
                .map(d -> new OffDay(d.day(), d.source(), d.reason())).toList();
        MatInput matIn = materials(plant, wos, last, zone, from, matMode, matPo, virtual);
        FiniteScheduler scheduler = new FiniteScheduler(cal, zone, from, to, lead * 3600L, resources(plant), matIn.planner());
        Map<Long, WoSrc> woById = wos.stream().collect(Collectors.toMap(WoSrc::getWoId, w -> w));
        Map<Long, FiniteScheduler.WoIn> inputs = new LinkedHashMap<>();
        Map<Long, Integer> procRowVer = new HashMap<>();     // 반영 때 낡음 판정용 사본(2단계)
        Map<String, PinSrc> pins = new HashMap<>();           // 수동 고정(3단계) — (지시, 순번)
        mapper.findPins(plant).forEach(p -> pins.put(p.getWoId() + ":" + p.getOpSeq(), p));
        for (OpSrc s : ops) {
            if (s.getWoProcId() != null) {
                procRowVer.put(s.getWoProcId(), s.getProcRowVer());
            }
            WoSrc w = woById.get(s.getWoId());
            if (w == null || s.getQty() == null || s.getQty().signum() <= 0) {
                continue;
            }
            FiniteScheduler.WoIn wi = inputs.computeIfAbsent(w.getWoId(), k -> woIn(w));
            FiniteScheduler.OpIn op = opIn(s);
            PinSrc pin = pins.get(w.getWoId() + ":" + s.getOpSeq());
            if (pin != null) {
                op.pin = new FiniteScheduler.Pin(pin.getPinId(), new Res(pin.getResKind(), pin.getEquipId(), pin.getNodeId(), ""),
                        pin.getStartAt().toInstant());
            }
            wi.ops.add(op);
        }
        if (virtual != null) {
            inputs.put(virtual.wo().woId, virtual.wo());
        }
        return new Prepared(zone, from, to, cal, offDays, matIn, woById, inputs, procRowVer, scheduler);
    }

    /** 자재 입력(5단계): 계획기·계산에 쓴 공급 사본·전개 못 한 계획 지시 */
    record MatInput(MaterialPlanner planner, List<SupplyOutRow> supplies, Set<Long> noBom) {
    }

    /**
     * 남은 소요(MES) → 품목 → 가용 재고(WMS, 계산 시작 시각) + 입고 예정(SRM, mat_po 일 때 그 날 00:00 현지, 지난 날은 계산 시작).
     * 기본 단위로 바꿀 수 없는 발주 줄은 사본에만 남기고 계산에서 뺀다(A30). 모두 계산 트랜잭션 안 읽기
     */
    private MatInput materials(String plant, List<WoSrc> wos, LocalDate last, ZoneId zone, Instant from, MaterialPlanner.Mode mode,
            boolean matPo, Virtual virtual) {
        WoMaterialQuery mq = WoMaterialQuery.of(materialQueries);
        Map<Long, List<MaterialPlanner.Need>> needs = new HashMap<>();
        Set<Long> noBom = new HashSet<>();
        Set<Long> items = new LinkedHashSet<>();
        List<Long> ids = wos.stream().map(WoSrc::getWoId).toList();
        for (int i = 0; i < ids.size(); i += WoMaterialQuery.MAX_IDS) {
            for (WoMaterialQuery.WoNeeds wn : mq.remaining(ids.subList(i, Math.min(ids.size(), i + WoMaterialQuery.MAX_IDS)))) {
                if (!wn.exploded()) {
                    noBom.add(wn.woId());
                }
                for (WoMaterialQuery.Need n : wn.needs()) {
                    if (n.remainingQty() == null || n.remainingQty().signum() <= 0) {
                        continue;
                    }
                    needs.computeIfAbsent(wn.woId(), k -> new ArrayList<>())
                            .add(new MaterialPlanner.Need(n.itemId(), n.opSeq(), n.remainingQty(), n.unitCd()));
                    items.add(n.itemId());
                }
            }
        }
        if (virtual != null && !virtual.needs().isEmpty()) {
            needs.put(virtual.wo().woId, virtual.needs());
            virtual.needs().forEach(n -> items.add(n.itemId()));
        }
        List<MaterialPlanner.Supply> supply = new ArrayList<>();
        List<SupplyOutRow> rows = new ArrayList<>();
        if (!items.isEmpty()) {
            Map<Long, String> unit = new HashMap<>();
            needs.values().forEach(l -> l.forEach(n -> unit.putIfAbsent(n.itemId(), n.unitCd())));
            StockQuery.of(stockQueries).available(plant, items).forEach((item, qty) -> {
                if (qty != null && qty.signum() > 0) {
                    supply.add(new MaterialPlanner.Supply(item, from, qty));
                    rows.add(new SupplyOutRow(item, "STOCK", odt(from), qty, unit.get(item), null, null, "N", "Y"));
                }
            });
            if (matPo) {
                for (PoSupplyQuery.Supply s : PoSupplyQuery.of(poQueries).open(plant, items, last)) {
                    if (s.qty() == null || s.qty().signum() <= 0) {
                        continue;
                    }
                    Instant at = s.supplyDate().atStartOfDay(zone).toInstant();
                    at = at.isBefore(from) ? from : at;
                    rows.add(new SupplyOutRow(s.itemId(), "PO", odt(at), s.qty(), s.unitCd(), s.poNo(), s.lineNo(),
                            s.promised() ? "Y" : "N", s.inBaseUnit() ? "Y" : "N"));
                    if (s.inBaseUnit()) {
                        supply.add(new MaterialPlanner.Supply(s.itemId(), at, s.qty()));
                    }
                }
            }
        }
        return new MatInput(new MaterialPlanner(mode, supply, needs), rows, noBom);
    }

    /** 공정 → 후보 자원: 배정 설비가 있으면 그것만, 아니면 작업장과 그 아래 노드의 사용 중 설비(코드 순), 없으면 작업장 자체 */
    Function<FiniteScheduler.OpIn, List<Res>> resources(String plant) {
        Map<Long, NodeRow> nodes = new HashMap<>();
        mapper.findNodes(plant).forEach(n -> nodes.put(n.getNodeId(), n));
        Map<Long, EquipRow> equips = new HashMap<>();
        Map<Long, List<Res>> under = new HashMap<>();
        List<EquipRow> all = mapper.findEquips(plant);
        all.sort(Comparator.comparing(EquipRow::getEquipCd));
        for (EquipRow eq : all) {
            equips.put(eq.getEquipId(), eq);
            if (!"Y".equals(eq.getUseYn()) || eq.getNodeId() == null) {
                continue;
            }
            Res r = new Res("EQUIP", eq.getEquipId(), eq.getNodeId(), eq.getEquipCd());
            Long n = eq.getNodeId();
            for (int depth = 0; n != null && depth < 20; depth++) {
                under.computeIfAbsent(n, k -> new ArrayList<>()).add(r);
                NodeRow nr = nodes.get(n);
                n = nr == null ? null : nr.getParentId();
            }
        }
        return op -> {
            if (op.equipId != null) {
                EquipRow eq = equips.get(op.equipId);
                return List.of(new Res("EQUIP", op.equipId, eq == null ? null : eq.getNodeId(),
                        eq == null ? String.valueOf(op.equipId) : eq.getEquipCd()));
            }
            List<Res> list = under.get(op.nodeId);
            if (list != null && !list.isEmpty()) {
                return list;
            }
            NodeRow nr = nodes.get(op.nodeId);
            return List.of(new Res("NODE", null, op.nodeId, nr == null ? "" : nr.getNodeCd()));
        };
    }

    private static FiniteScheduler.WoIn woIn(WoSrc w) {
        FiniteScheduler.WoIn wi = new FiniteScheduler.WoIn();
        wi.woId = w.getWoId();
        wi.woNo = w.getWoNo();
        wi.priority = w.getPriority();
        wi.planStart = w.getPlanStart();
        wi.planEnd = w.getPlanEnd();
        wi.due = w.getDueDate();
        return wi;
    }

    static FiniteScheduler.OpIn opIn(OpSrc s) {
        FiniteScheduler.OpIn op = new FiniteScheduler.OpIn();
        op.opSeq = s.getOpSeq();
        op.woProcId = s.getWoProcId();
        op.procId = s.getProcId();
        op.procCd = s.getProcCd();
        op.procNm = s.getProcNm();
        op.nodeId = s.getNodeId();
        op.equipId = s.getEquipId();
        op.outsrc = "Y".equals(s.getOutsrcYn());
        op.stdCtSec = s.getStdCtSec();
        op.unitsPerCycle = s.getUnitsPerCycle();
        op.setupMin = s.getSetupMin() == null ? BigDecimal.ZERO : s.getSetupMin();
        op.qty = s.getQty();
        op.running = "RUN".equals(s.getProcStatus()) || "PAUSE".equals(s.getProcStatus());
        return op;
    }

    // ------------------------------------------------------------------ 폐기

    @Transactional
    public void discard(long planId, DiscardForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String reason = OrderService.text(in.reason(), "reason", 500, e);
        if (reason == null) {
            e.add("reason", "REQUIRED");
        }
        e.throwIfAny();
        PlanRow p = plan(planId);
        if (!"CALCULATED".equals(p.getStatus())) {
            throw OrderService.fieldError("status", "STATE");
        }
        if (mapper.discard(planId, reason, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    // ------------------------------------------------------------------ 도우미

    private PlanRow plan(long planId) {
        PlanRow p = mapper.findPlan(planId);
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return p;
    }

    /** 근무 구간 → "[[시작,끝],…]"(epoch 초) */
    static String windowsJson(ShiftCalendar cal) {
        return cal.windows().stream().map(w -> "[" + w.start().getEpochSecond() + "," + w.end().getEpochSecond() + "]")
                .collect(Collectors.joining(",", "[", "]"));
    }

    /** 계산 때 쉬는 날 사본 — 이 서비스가 쓴 배열만 읽는다 */
    static List<OffDay> offDays(String json) {
        return json == null ? List.of() : JSON.readValue(json, new TypeReference<List<OffDay>>() { });
    }

    /** windowsJson 의 반대 — 이 서비스가 쓴 숫자 배열만 읽는다 */
    static List<WorkWindow> windows(String json) {
        List<WorkWindow> out = new ArrayList<>();
        if (json == null) {
            return out;
        }
        String[] nums = json.replaceAll("[\\[\\]\\s]", "").split(",");
        for (int i = 0; i + 1 < nums.length; i += 2) {
            out.add(new WorkWindow(Long.parseLong(nums[i]), Long.parseLong(nums[i + 1])));
        }
        return out;
    }

    private static OffsetDateTime odt(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
