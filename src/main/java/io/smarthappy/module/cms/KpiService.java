package io.smarthappy.module.cms;

import static io.smarthappy.module.cms.CmsSupport.blank;
import static io.smarthappy.module.cms.CmsSupport.date;
import static io.smarthappy.module.cms.CmsSupport.like;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.CmsFailMapper.WoStatRow;
import io.smarthappy.module.cms.CmsKpiMapper.CountRow;
import io.smarthappy.module.cms.CmsKpiMapper.PmRow;
import io.smarthappy.module.cms.CmsKpiMapper.RawRow;
import io.smarthappy.module.cms.EquipKpi.Raw;
import io.smarthappy.module.cms.EquipKpi.Result;
import io.smarthappy.module.cms.EquipKpi.WoKpi;
import io.smarthappy.module.cms.EquipKpi.WoRaw;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 설비 KPI (cms-kpi) — docs/33 11.8 C9. 원장에서 설비별 합을 모으고(CmsKpiMapper), 라인·공장은 그 합을 다시 더한 뒤 {@link EquipKpi} 로 나눈다.
 * 기간 = 공장 현지 날짜 [from 00:00, to 다음날 00:00) 을 지금에서 자른다(미래 시간은 세지 않는다).
 */
@Service
@RequiredArgsConstructor
public class KpiService {

    static final String PROG = "cms-kpi";
    static final int MAX_DAYS = 92;
    static final int MAX_EQUIPS = 2000;
    /** 일별 추이는 하루씩 원장을 다시 모은다(하루 한 번 질의) — 최대 31일 */
    static final int MAX_TREND_DAYS = 31;

    private final CmsKpiMapper mapper;
    private final CmsFailMapper failMapper;
    private final DateRanges ranges;
    private final PolicyService policies;
    private final BizClock clock;
    private final ExcelExportService excel;

    /** 한 줄 = 설비·라인·공장 합계. kind = EQUIP / LINE / PLANT. wo = 작업지시 기준(F3·G2 — 상태 원장 kpi 와 이름을 나눠 따로) */
    public record KpiLine(String kind, Long equipId, String code, String name, String nodeCd, Raw raw, Result kpi, WoKpi wo) {
    }

    public record KpiReport(String plantCd, String timeZone, String basis, OffsetDateTime from, OffsetDateTime until, List<KpiLine> equips,
            List<KpiLine> lines, KpiLine plant) {
    }

    /** 생산 수 기준(docs/33 D34·D35) — 확정 실적(기본, 품목별 표준 사이클) / 수집 카운트(설비 프로필 개당 이론 초) */
    static final String BASIS_RESULT = "RESULT";
    static final String BASIS_COUNT = "COUNT";

    static String basis(String raw) {
        if (blank(raw)) {
            return BASIS_RESULT;
        }
        String b = raw.strip();
        if (!BASIS_RESULT.equals(b) && !BASIS_COUNT.equals(b)) {
            throw CmsSupport.fieldError("basis", "CODE");
        }
        return b;
    }

    @Transactional(readOnly = true)
    public KpiReport report(String plantCd, String from, String to, String keyword) {
        return report(plantCd, from, to, keyword, null);
    }

    @Transactional(readOnly = true)
    public KpiReport report(String plantCd, String from, String to, String keyword, String basisCd) {
        String basis = basis(basisCd);
        FieldErrors e = FieldErrors.create();
        if (blank(plantCd)) {
            e.add("plantCd", "REQUIRED");
        }
        LocalDate f = date(from, "from", e);
        LocalDate t = date(to, "to", e);
        e.throwIfAny();
        if (keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        ranges.check(f, t, MAX_DAYS);
        String plant = plantCd.strip();
        ZoneId z = clock.zone(plant);
        OffsetDateTime a = f.atStartOfDay(z).toOffsetDateTime();
        OffsetDateTime b = t.plusDays(1).atStartOfDay(z).toOffsetDateTime();
        OffsetDateTime now = CmsSupport.now(clock, z);
        OffsetDateTime until = b.isAfter(now) ? now : b;
        if (until.isBefore(a)) {
            until = a;
        }
        long periodSec = Duration.between(a, until).getSeconds();

        List<RawRow> rows = mapper.raw(plant, a, until, blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT)));
        if (rows.size() > MAX_EQUIPS) {
            throw new BusinessException(HbErrorCode.CMS_TOO_MANY_ROWS, MAX_EQUIPS);
        }
        Map<Long, long[]> pm = pm(plant, a, until, z);
        Map<Long, CountRow> counts = new HashMap<>();
        if (BASIS_COUNT.equals(basis)) {
            mapper.countSums(plant, a, until).forEach(c -> counts.put(c.getEquipId(), c));
        }

        // 작업지시 기준(F3·G2, docs/33 16.3) — 같은 기간·설비 글자, 고장 정의는 CmsFailMapper.failWhere 하나
        String kw = blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT));
        Map<Long, WoStatRow> woRows = new HashMap<>();
        failMapper.woStats(plant, a, until, kw, null).forEach(w -> woRows.put(w.getEquipId(), w));

        List<KpiLine> equips = new ArrayList<>();
        Map<String, Raw> lineSum = new LinkedHashMap<>();
        Map<String, WoRaw[]> lineWo = new LinkedHashMap<>();
        Map<String, String> lineNames = new HashMap<>();
        Raw total = Raw.ZERO;
        WoRaw[] totalWo = {null, WoRaw.ZERO};
        for (RawRow r : rows) {
            long[] p = pm.getOrDefault(r.getEquipId(), new long[2]);
            // 미수집은 수집 장치가 있는(구간이 하나라도 있는) 설비만 — 장치 없는 설비 시간까지 더하면 합계의 "미수집" 이 부풀려진다
            Raw raw = BASIS_COUNT.equals(basis) ? countRaw(r, counts.get(r.getEquipId()), r.getCoveredSec() > 0 ? periodSec : 0, p)
                    : new Raw(r.getCoveredSec() > 0 ? periodSec : 0, r.getCoveredSec(), r.getUnknownSec(), r.getNstSec(), r.getSdtSec(), r.getUdtSec(),
                            r.getPrdSec(), r.getFailures(), r.getIdealSec(), r.getIdealGoodSec(), r.getCtMissing(), r.getGoodQty(), r.getTotalQty(), p[0], p[1]);
            Result k = EquipKpi.of(raw);
            WoRaw wo = woRaw(woRows.get(r.getEquipId()), periodSec);
            WoRaw scope = k.collected() ? null : wo;   // 수집 있는 설비는 상태 원장 값만(두 숫자가 다투지 않게)
            equips.add(new KpiLine("EQUIP", r.getEquipId(), r.getEquipCd(), r.getEquipNm(), r.getNodeCd(), raw, k, EquipKpi.wo(scope, wo)));
            String node = r.getNodeCd() == null ? "" : r.getNodeCd();
            lineSum.merge(node, raw, Raw::plus);
            WoRaw[] lw = lineWo.computeIfAbsent(node, x -> new WoRaw[] {null, WoRaw.ZERO});
            addWo(lw, scope, wo);
            addWo(totalWo, scope, wo);
            lineNames.putIfAbsent(node, r.getNodeNm());
            total = total.plus(raw);
        }
        List<KpiLine> lines = new ArrayList<>();
        lineSum.forEach((node, raw) -> lines.add(new KpiLine("LINE", null, node.isEmpty() ? null : node, lineNames.get(node), node, raw,
                EquipKpi.of(raw), EquipKpi.wo(lineWo.get(node)[0], lineWo.get(node)[1]))));
        KpiLine plantLine = new KpiLine("PLANT", null, plant, null, null, total, EquipKpi.of(total), EquipKpi.wo(totalWo[0], totalWo[1]));
        return new KpiReport(plant, z.getId(), basis, a, until, equips, lines, plantLine);
    }

    /** 작업지시 기준 원값 — 고장 작업지시가 없으면 0(달력 시간은 있음) */
    static WoRaw woRaw(WoStatRow w, long periodSec) {
        return w == null ? new WoRaw(0, 0, 0, periodSec, 0, 0)
                : new WoRaw(w.getFailures(), w.getDownSec(), w.getDownCount(), periodSec, w.getRespSec(), w.getRespCount());
    }

    /** [0] = 수집 없는 설비 합(MTBF·MTTR, 하나도 없으면 null), [1] = 모든 설비 합(MTTA) */
    private static void addWo(WoRaw[] acc, WoRaw scope, WoRaw all) {
        if (scope != null) {
            acc[0] = acc[0] == null ? scope : acc[0].plus(scope);
        }
        acc[1] = acc[1].plus(all);
    }

    /**
     * 수집 기준 원값 — 시간(상태 구간)은 같고 생산 수만 수집 COUNT 순합(역분개 포함). 이론 시간 = 개당 이론 초 × 수량.
     * 이론 초가 없는데 수집 수량이 있으면 ctMissing = 1 → 성능·OEE 빈칸(합계에서도, 실적 기준 D21 과 같은 규칙). 근사: 품목 섞인 설비는 품목별 표준과 다를 수 있다
     */
    static Raw countRaw(RawRow r, CountRow c, long periodSec, long[] pm) {
        BigDecimal good = c == null ? BigDecimal.ZERO : c.getGoodQty();
        BigDecimal total = c == null ? BigDecimal.ZERO : c.getGoodQty().add(c.getScrapQty());
        BigDecimal ct = r.getIdealCtSec();
        long missing = ct == null && total.signum() != 0 ? 1 : 0;
        BigDecimal ideal = ct == null ? BigDecimal.ZERO : ct.multiply(total);
        BigDecimal idealGood = ct == null ? BigDecimal.ZERO : ct.multiply(good);
        return new Raw(periodSec, r.getCoveredSec(), r.getUnknownSec(), r.getNstSec(), r.getSdtSec(), r.getUdtSec(), r.getPrdSec(), r.getFailures(),
                ideal, idealGood, missing, good, total, pm[0], pm[1]);
    }

    /** 설비별 [끝난 예정 수, 기한 안 완료 수] — 판정은 점검 실행 화면과 같은 PmDue(허용 일수 정책, 사용량 주기는 완료 사용량) */
    private Map<Long, long[]> pm(String plant, OffsetDateTime a, OffsetDateTime until, ZoneId z) {
        int grace = policies.intValue(Policies.CMS_PM_GRACE_DAYS);
        Map<Long, long[]> out = new HashMap<>();
        for (PmRow r : mapper.pmDone(plant, a, until)) {
            boolean onTime = r.getDueUsage() != null && r.getDoneUsage() != null ? PmDue.usageOnTime(r.getDueUsage(), r.getDoneUsage())
                    : PmDue.onTime(r.getDueDate(), r.getDoneAt().atZoneSameInstant(z).toLocalDate(), grace);
            long[] c = out.computeIfAbsent(r.getEquipId(), k -> new long[2]);
            c[0]++;
            if (onTime) {
                c[1]++;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 일별 추이 (C11)

    public record DayKpi(String day, Raw raw, Result kpi) {
    }

    /**
     * 일별 추이 — 공장 합계 또는 설비 하나(equipCd). 하루마다 같은 규칙으로 모으므로 일별 원값을 더하면 기간 원값과 같다(C9 시험).
     * 오늘은 지금까지, 미래 날은 0 이라 넣지 않는다
     */
    @Transactional(readOnly = true)
    public List<DayKpi> daily(String plantCd, String from, String to, String equipCd) {
        return daily(plantCd, from, to, equipCd, null);
    }

    @Transactional(readOnly = true)
    public List<DayKpi> daily(String plantCd, String from, String to, String equipCd, String basisCd) {
        String basis = basis(basisCd);
        FieldErrors e = FieldErrors.create();
        if (blank(plantCd)) {
            e.add("plantCd", "REQUIRED");
        }
        LocalDate f = date(from, "from", e);
        LocalDate t = date(to, "to", e);
        e.throwIfAny();
        if (equipCd != null && equipCd.length() > 30) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        ranges.check(f, t, MAX_TREND_DAYS);
        String plant = plantCd.strip();
        ZoneId z = clock.zone(plant);
        LocalDate today = CmsSupport.now(clock, z).toLocalDate();
        String eq = blank(equipCd) ? null : equipCd.strip().toUpperCase(Locale.ROOT);
        List<DayKpi> out = new ArrayList<>();
        for (LocalDate d = f; !d.isAfter(t) && !d.isAfter(today); d = d.plusDays(1)) {
            KpiReport r = report(plant, d.toString(), d.toString(), null, basis);
            Raw raw = eq == null ? r.plant().raw()
                    : r.equips().stream().filter(x -> x.code().equals(eq)).map(KpiLine::raw).findFirst().orElse(null);
            if (raw == null) {
                throw CmsSupport.fieldError("equipCd", "NO_SUCH_CODE");
            }
            out.add(new DayKpi(d.toString(), raw, EquipKpi.of(raw)));
        }
        return out;
    }

    // ------------------------------------------------------------------ 엑셀 (C11)

    /** 엑셀 한 줄 = 화면 표 한 줄(공장 합계·라인·설비). 비율은 %, 시간은 h·분 */
    @Getter
    public static class ExcelRow {
        private final String kind;
        private final String code;
        private final String name;
        private final BigDecimal plannedH;
        private final BigDecimal runH;
        private final BigDecimal availability;
        private final BigDecimal performance;
        private final BigDecimal quality;
        private final BigDecimal oee;
        private final Long failures;
        private final BigDecimal mtbfH;
        private final BigDecimal mttrMin;
        private final BigDecimal lostH;
        private final BigDecimal pmCompliance;
        /** 작업지시 기준(F3) — 수집 없는 설비(·그 합)만 */
        private final Long woFailures;
        private final BigDecimal woMtbfH;
        private final BigDecimal woMttrMin;
        /** 평균 반응(G2, 작업지시) — 모든 설비 */
        private final BigDecimal mttaMin;

        ExcelRow(KpiLine l) {
            Raw r = l.raw();
            Result k = l.kpi();
            this.kind = l.kind();
            this.code = l.code();
            this.name = l.name();
            this.plannedH = k.collected() ? hours(k.plannedSec()) : null;
            this.runH = k.collected() ? hours(r.prdSec()) : null;
            this.availability = pct(k.availability());
            this.performance = pct(k.performance());
            this.quality = pct(k.quality());
            this.oee = pct(k.oee());
            this.failures = k.collected() ? r.failures() : null;
            this.mtbfH = k.mtbfMin() == null ? null : k.mtbfMin().divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP);
            this.mttrMin = k.mttrMin();
            long lost = r.unknownSec() + k.uncoveredSec();
            this.lostH = lost == 0 ? null : hours(lost);
            this.pmCompliance = pct(k.pmCompliance());
            WoKpi w = l.wo();
            this.woFailures = w == null ? null : w.failures();
            this.woMtbfH = w == null || w.mtbfMin() == null ? null : w.mtbfMin().divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP);
            this.woMttrMin = w == null ? null : w.mttrMin();
            this.mttaMin = w == null ? null : w.mttaMin();
        }

        private static BigDecimal hours(long sec) {
            return BigDecimal.valueOf(sec).divide(BigDecimal.valueOf(3600), 1, RoundingMode.HALF_UP);
        }

        private static BigDecimal pct(BigDecimal ratio) {
            return ratio == null ? null : ratio.movePointRight(2).setScale(1, RoundingMode.HALF_UP);
        }
    }

    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.code("kind", "cms.kpi.col.kind", "CMS_KPI_KIND", 70),
            ColumnSpec.text("code", "cms.kpi.col.code", 110),
            ColumnSpec.text("name", "cms.kpi.col.name", 180),
            ColumnSpec.dec("plannedH", "cms.kpi.col.planned", 1, 90),
            ColumnSpec.dec("runH", "cms.kpi.col.run", 1, 90),
            ColumnSpec.dec("availability", "cms.kpi.col.availabilityPct", 1, 90),
            ColumnSpec.dec("performance", "cms.kpi.col.performancePct", 1, 90),
            ColumnSpec.dec("quality", "cms.kpi.col.qualityPct", 1, 90),
            ColumnSpec.dec("oee", "cms.kpi.col.oeePct", 1, 90),
            ColumnSpec.of("failures", "cms.kpi.col.failures", ColumnType.INT, 70),
            ColumnSpec.dec("mtbfH", "cms.kpi.col.mtbf", 1, 90),
            ColumnSpec.dec("mttrMin", "cms.kpi.col.mttr", 1, 90),
            ColumnSpec.dec("lostH", "cms.kpi.col.unknown", 1, 110),
            ColumnSpec.dec("pmCompliance", "cms.kpi.col.pmPct", 1, 90),
            ColumnSpec.of("woFailures", "cms.kpi.col.woFailures", ColumnType.INT, 90),
            ColumnSpec.dec("woMtbfH", "cms.kpi.col.woMtbf", 1, 110),
            ColumnSpec.dec("woMttrMin", "cms.kpi.col.woMttr", 1, 110),
            ColumnSpec.dec("mttaMin", "cms.kpi.col.mtta", 1, 100));

    public record KpiExport(String plantCd, String from, String to, String keyword, String basis) {
    }

    /** 화면과 같은 조건·같은 계산 — 공장 합계, 라인마다(라인 합계 → 그 라인 설비) 순서 */
    @Transactional(readOnly = true)
    public long export(KpiExport req, HttpServletResponse response) throws IOException {
        KpiExport q = req == null ? new KpiExport(null, null, null, null, null) : req;
        KpiReport r = report(q.plantCd(), q.from(), q.to(), q.keyword(), q.basis());
        List<ExcelRow> rows = new ArrayList<>();
        rows.add(new ExcelRow(r.plant()));
        for (KpiLine l : r.lines()) {
            rows.add(new ExcelRow(l));
            r.equips().stream().filter(x -> java.util.Objects.equals(x.nodeCd(), l.nodeCd()) || x.nodeCd() == null && "".equals(l.nodeCd()))
                    .forEach(x -> rows.add(new ExcelRow(x)));
        }
        List<String> keys = new ArrayList<>(List.of("plantCd", "from", "to", "basis"));
        if (!blank(q.keyword())) {
            keys.add("keyword");
        }
        return excel.export(response, new ExportJob(PROG, "prog.cmsKpi", COLUMNS, rows.size(), keys), w -> rows.forEach(w));
    }
}
