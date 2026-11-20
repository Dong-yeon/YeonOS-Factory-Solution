package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.module.qms.SpcDtos.ChartQuery;
import io.smarthappy.module.qms.SpcDtos.ChartUnit;
import io.smarthappy.module.qms.SpcDtos.ChartView;
import io.smarthappy.module.qms.SpcDtos.LimitForm;
import io.smarthappy.module.qms.SpcDtos.LimitRow;
import io.smarthappy.module.qms.SpcDtos.MeasVal;
import io.smarthappy.module.qms.SpcDtos.PointView;
import io.smarthappy.module.qms.SpcDtos.SubgroupHead;
import io.smarthappy.module.qms.SpcDtos.UnitHead;
import io.smarthappy.module.qms.SpcDtos.UnitPage;
import io.smarthappy.module.qms.SpcDtos.UnitSort;
import io.smarthappy.module.qms.SpcDtos.VoidForm;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.time.BizClock;

/**
 * SPC 최소형 (qms-spc) — P3 QMS 2부 Q10, docs/29_P3 12.6.
 *
 * - 관리도는 조회 때 Q6 측정값 원장에서 계산: 부분군 = 판정 확정 검사의 검사 행, 살아 있는 값만(무효 제외)
 * - 고정 한계(qms_spc_limit)가 있으면 그 중심·σ̂ 로 그리고(FIXED), 없으면 보이는 구간에서 계산(CALC)
 * - 고정 한계 정하기 = 같은 구간 계산값 저장(부분군 MIN_SUBGROUPS 개 이상, 변동 있음), 단위마다 살아 있는 한계 하나
 */
@Service
public class SpcService {

    static final String PROG = "qms-spc";
    static final int DEFAULT_N = 25;
    static final int MAX_N = 200;
    static final int MIN_SUBGROUPS = 20;
    static final int UNIT_LIMIT = 200;
    static final List<String> GRID_DEFAULT_SORT = List.of("lastAt,desc");

    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("itemCd", "qms.spc.col.item", 120).withSort().withPriority(1),
            ColumnSpec.text("itemNm", "qms.spc.col.itemNm", 150).withFlex().withMinWidth(100).withPriority(3),
            ColumnSpec.text("charNo", "qms.spc.col.char", 70).withPriority(1),
            ColumnSpec.text("charNm", "qms.spc.col.charNm", 140).withPriority(2),
            ColumnSpec.labels("chartType", "qms.spc.col.chart", "qms.spc.chart.", 70, "XBAR_R", "P").withPriority(2),
            ColumnSpec.code("inspType", "qms.spc.col.type", "INSP_TYPE", 90).withSort().withPriority(2),
            ColumnSpec.text("procCd", "qms.spc.col.proc", 80).withPriority(3),
            ColumnSpec.of("subgroupCnt", "qms.spc.col.subgroups", ColumnSpec.ColumnType.INT, 70).withSort().withPriority(1),
            ColumnSpec.of("lastAt", "qms.spc.col.last", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(2),
            ColumnSpec.of("limitYn", "qms.spc.col.limit", ColumnSpec.ColumnType.YN, 70).withPriority(1));

    static final Set<String> INSP_TYPES = Set.of("RECEIVE", "PROCESS", "FINAL", "FIRST_OFF", "LAST_OFF", "PATROL");

    private final SpcMapper mapper;
    private final InboxService inbox;
    private final BizClock clock;
    private final PolicyService policies;

    public SpcService(SpcMapper mapper, InboxService inbox, BizClock clock, PolicyService policies) {
        this.mapper = mapper;
        this.inbox = inbox;
        this.clock = clock;
        this.policies = policies;
    }

    @Transactional(readOnly = true)
    public List<ChartUnit> units(String keyword) {
        String kw = keyword == null || keyword.isBlank() ? null : "%" + BomService.likeEscape(keyword.strip()) + "%";
        return mapper.findUnits(kw, UNIT_LIMIT);
    }

    /** 그리드(UI 단계 3, 결정 #45) — 목록과 같은 묶음 조건 + 쪽·정렬, 기본 = 최근 검사 */
    @Transactional(readOnly = true)
    public UnitPage unitGrid(String keyword, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, UnitSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                GRID_DEFAULT_SORT);
        String kw = keyword == null || keyword.isBlank() ? null : "%" + BomService.likeEscape(keyword.strip()) + "%";
        return new UnitPage(mapper.findUnitGrid(kw, pq), pq.isWithTotal() ? mapper.countUnits(kw) : null);
    }

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    @Transactional(readOnly = true)
    public ChartView chart(ChartQuery q) {
        if (q == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return compute(new LimitForm(q.itemId(), q.charId(), q.inspType(), q.procId(), q.n(), q.to(), q.rules(), null), true);
    }

    @Transactional(readOnly = true)
    public List<LimitRow> limits(long itemId, long charId, String inspType, Long procId) {
        head(itemId, charId, procId);
        if (!INSP_TYPES.contains(inspType)) {
            throw SamplingService.fieldError("inspType", "INVALID");
        }
        return mapper.findLimits(itemId, charId, inspType, procId);
    }

    /** 고정 한계 정하기 — 보이는 구간의 계산값(고정 한계 무시)을 저장 */
    @Transactional
    public long createLimit(LimitForm in) {
        LoginUser me = SamplingService.me();
        inbox.claimRequestKey("QMS_SPC_LIMIT");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String remark = VisibleText.optional(in.remark(), "remark", 1000, e);
        e.throwIfAny();
        ChartView v = compute(in, false);
        if (v.points().size() < MIN_SUBGROUPS) {
            throw SamplingService.fieldError("n", "SPC_FEW_SUBGROUPS");
        }
        if (v.calcSigma() == null || v.calcSigma() <= 0) {
            throw SamplingService.fieldError("n", "SPC_NO_VARIATION");   // p: p̄ = 0(불량 없음)·1 이면 한계를 낼 수 없다
        }
        if (mapper.findLiveLimit(in.itemId(), in.charId(), in.inspType(), in.procId()) != null) {
            throw new BusinessException(HbErrorCode.QMS_SPC_LIMIT_EXISTS);
        }
        List<PointView> p = v.points();
        try {
            return mapper.insertLimit(in.itemId(), in.charId(), in.inspType(), in.procId(), BigDecimal.valueOf(v.calcCenter()),
                    BigDecimal.valueOf(v.calcSigma()), p.size(), p.get(0).at(), p.get(p.size() - 1).at(), String.join(",", v.rules()),
                    v.chartType(), remark, me.loginId());
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(HbErrorCode.QMS_SPC_LIMIT_EXISTS);
        }
    }

    @Transactional
    public void voidLimit(long limitId, VoidForm in) {
        LoginUser me = SamplingService.me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String reason = VisibleText.reason(in.reason(), "reason", 1, 500, e);
        e.throwIfAny();
        LimitRow r = mapper.findLimit(limitId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"ACTIVE".equals(r.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_SPC_LIMIT_EXISTS);
        }
        if (mapper.voidLimit(limitId, reason, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** @param useFixed true = 살아 있는 고정 한계가 있으면 그것으로 그림 */
    private ChartView compute(LimitForm q, boolean useFixed) {
        FieldErrors e = FieldErrors.create();
        if (q.itemId() == null) {
            e.add("itemId", "REQUIRED");
        }
        if (q.charId() == null) {
            e.add("charId", "REQUIRED");
        }
        if (q.inspType() == null) {
            e.add("inspType", "REQUIRED");
        } else if (!INSP_TYPES.contains(q.inspType())) {
            e.add("inspType", "INVALID");
        }
        int n = q.n() == null ? DEFAULT_N : q.n();
        if (n < 1 || n > MAX_N) {
            e.add("n", "SPC_N_RANGE");
        }
        Set<String> rules = null;
        if (q.rules() != null && !q.rules().isEmpty()) {
            rules = new TreeSet<>(q.rules());
            if (!SpcCalc.ALL_RULES.containsAll(rules)) {
                e.add("rules", "INVALID");
            }
        }
        e.throwIfAny();
        UnitHead unit = head(q.itemId(), q.charId(), q.procId());
        if ("ATTRIBUTE".equals(unit.getDataType())) {
            return computeP(q, unit, n, rules, useFixed);
        }
        if (!"VARIABLE".equals(unit.getDataType())) {
            throw SamplingService.fieldError("charId", "SPC_NOT_VARIABLE");
        }
        LimitRow fixed = useFixed ? mapper.findLiveLimit(q.itemId(), q.charId(), q.inspType(), q.procId()) : null;
        if (rules == null) {
            rules = fixed != null ? new TreeSet<>(List.of(fixed.getRules().split(","))) : new TreeSet<>(SpcCalc.DEFAULT_RULES);
        }
        OffsetDateTime to = q.to() == null ? null : q.to().plusDays(1).atStartOfDay(clock.zone()).toOffsetDateTime();
        List<SubgroupHead> heads = new ArrayList<>(mapper.findSubgroups(q.itemId(), q.charId(), q.inspType(), q.procId(), to, n));
        Collections.reverse(heads);
        Map<Long, List<BigDecimal>> vals = new LinkedHashMap<>();
        heads.forEach(h -> vals.put(h.getInspLineId(), new ArrayList<>()));
        if (!heads.isEmpty()) {
            for (MeasVal m : mapper.findValues(vals.keySet())) {
                vals.get(m.getInspLineId()).add(m.getValue());
            }
        }
        boolean specOk = heads.stream().allMatch(h -> same(h.getLsl(), heads.get(heads.size() - 1).getLsl())
                && same(h.getUsl(), heads.get(heads.size() - 1).getUsl()));
        BigDecimal lsl = heads.isEmpty() ? null : heads.get(heads.size() - 1).getLsl();
        BigDecimal usl = heads.isEmpty() ? null : heads.get(heads.size() - 1).getUsl();
        SpcCalc.Limits lim = fixed == null ? null : new SpcCalc.Limits(fixed.getCenter().doubleValue(), fixed.getSigma().doubleValue());
        SpcCalc.Result r = SpcCalc.chart(new ArrayList<>(vals.values()), lim, rules, lsl, usl, specOk);
        List<PointView> points = r.points().stream().map(p -> {
            SubgroupHead h = heads.get(p.index());
            return new PointView(p.index(), h.getInspLineId(), h.getInspNo(), h.getLotNo(), h.getFirstAt(), p.n(), r10(p.mean()),
                    r10(p.range()), r10(p.xUcl()), r10(p.xLcl()), r10(p.rCl()), r10(p.rUcl()), r10(p.rLcl()), r10(p.z()),
                    new TreeSet<>(p.rules()).stream().toList());
        }).toList();
        return new ChartView(unit, q.inspType(), fixed != null ? "FIXED" : "CALC", fixed,
                r.limits() == null ? null : r10(r.limits().center()), r.limits() == null ? null : r10(r.limits().sigma()),
                r.calcLimits() == null ? null : r10(r.calcLimits().center()), r.calcLimits() == null ? null : r10(r.calcLimits().sigma()),
                List.copyOf(rules), points, r.excluded(), r.capability(), !specOk, lsl, usl, MIN_SUBGROUPS, "XBAR_R");
    }

    /**
     * p 관리도(docs/29_P3 28장) — 계수형 특성. 부분군 = 판정 확정 검사 행의 살아 있는 OK/NG 샘플(n)·NG(d). 점의 mean = p, range = d,
     * xUcl·xLcl = 그 점의 한계(n 마다 다름), R 칸은 0. center = p̄, sigma = √(p̄(1-p̄)). 규칙은 WE1·WE4 만(그 밖은 400)
     */
    private ChartView computeP(LimitForm q, UnitHead unit, int n, Set<String> rules, boolean useFixed) {
        if (rules != null && !SpcCalc.P_RULES.containsAll(rules)) {
            throw SamplingService.fieldError("rules", "SPC_P_RULES");
        }
        LimitRow fixed = useFixed ? mapper.findLiveLimit(q.itemId(), q.charId(), q.inspType(), q.procId()) : null;
        if (fixed != null && !"P".equals(fixed.getChartType())) {
            fixed = null;   // 계수형 특성에 X̄ 한계는 생길 수 없다(가드) — 방어
        }
        if (rules == null) {
            rules = fixed != null ? new TreeSet<>(List.of(fixed.getRules().split(","))) : new TreeSet<>(SpcCalc.P_DEFAULT_RULES);
        }
        OffsetDateTime to = q.to() == null ? null : q.to().plusDays(1).atStartOfDay(clock.zone()).toOffsetDateTime();
        List<SubgroupHead> heads = new ArrayList<>(mapper.findPSubgroups(q.itemId(), q.charId(), q.inspType(), q.procId(), to, n));
        Collections.reverse(heads);
        List<int[]> groups = heads.stream().map(h -> new int[] {h.getN(), h.getD()}).toList();
        SpcCalc.PResult r = SpcCalc.pChart(groups, fixed == null ? null : fixed.getCenter().doubleValue(), rules);
        List<PointView> points = r.points().stream().map(p -> {
            SubgroupHead h = heads.get(p.index());
            return new PointView(p.index(), h.getInspLineId(), h.getInspNo(), h.getLotNo(), h.getFirstAt(), p.n(), r10(p.p()), p.d(),
                    r10(p.ucl()), r10(p.lcl()), 0, 0, 0, r10(p.z()), new TreeSet<>(p.rules()).stream().toList());
        }).toList();
        Double center = r.pbar() == null ? null : r10(r.pbar());
        Double calc = r.calcPbar() == null ? null : r10(r.calcPbar());
        return new ChartView(unit, q.inspType(), fixed != null ? "FIXED" : "CALC", fixed, center, center == null ? null : r10(SpcCalc.pSigma(r.pbar())),
                calc, calc == null ? null : r10(SpcCalc.pSigma(r.calcPbar())), List.copyOf(rules), points, r.excluded(), null, false, null, null,
                MIN_SUBGROUPS, "P");
    }

    private UnitHead head(long itemId, long charId, Long procId) {
        UnitHead h = mapper.findUnitHead(itemId, charId, procId);
        if (h == null || (procId != null && h.getProcId() == null)) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return h;
    }

    /** 응답 값은 소수 10자리까지(부동소수 끝자리 잡음을 화면·JSON 에 내지 않는다). 고정 한계 칸도 소수 10자리 */
    static Double r10(Double v) {
        if (v == null || v.isNaN() || v.isInfinite()) {
            return v;
        }
        return BigDecimal.valueOf(v).setScale(10, RoundingMode.HALF_UP).doubleValue();
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }
}
