package io.smarthappy.module.cms;

import static io.smarthappy.module.cms.CmsSupport.blank;
import static io.smarthappy.module.cms.CmsSupport.date;
import static io.smarthappy.module.cms.CmsSupport.like;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.CmsDashMapper.LossRow;
import io.smarthappy.module.cms.CmsDashMapper.WoRow;
import io.smarthappy.module.cms.EquipKpi.Raw;
import io.smarthappy.module.cms.EquipKpi.Result;
import io.smarthappy.module.cms.KpiService.KpiLine;
import io.smarthappy.module.cms.KpiService.KpiReport;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 설비·생산 관리자 대시보드 API — docs/51 C2(화면은 차트 도구 결정 뒤). **새 계산을 만들지 않는다**: KPI 는 {@link KpiService#report}(= cms-kpi 화면·엑셀과 같은
 * 원값·{@link EquipKpi})를 기간·하루마다 부르고, 대시보드에만 있는 것은 비가동 사유별 시간과 지시별 계획 대비 양품 두 질의뿐이다.
 *
 * <pre>
 * 기간       공장 현지 날짜 [from 0시, to 다음날 0시), 지금에서 자름 — 최대 31일(드릴다운할 비가동 사유 검토와 같은 상한)
 * 앞 기간    같은 길이로 바로 앞(from − n … from − 1) — 타일의 "앞 기간 대비"
 * 일별 추이  하루마다 report(같은 조건) — 일별 원값을 더하면 기간 원값(C9 시험과 같은 성질)
 * Pareto     실효 분류 × 실효 사유(사후 사유 우선). 계획 = SDT·NST(KPI 계획 시간에서 빠지는 것), 비계획 = UDT·SBY·ENG. UNKNOWN 은 따로 숫자로
 * 계획 대비  계획 시작일이 기간 안인 발행 이후 지시 — 계획 수량 vs 마지막 공정 양품(단위가 지시마다 달라 합하지 않고 지시별 달성률)
 * 기준       생산 수 기준(RESULT 확정 실적 / COUNT 수집 카운트) — 비우면 공장 정책 CMS_DASH_BASIS
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** 대시보드 화면 (docs/33 16.4 — 화면 등록 V248) */
    static final String PROG = "cms-dashboard";
    static final int MAX_DAYS = KpiService.MAX_TREND_DAYS;
    static final int MAX_WOS = 40;
    static final Set<String> PLANNED_CATS = Set.of("SDT", "NST");

    private final KpiService kpi;
    private final CmsDashMapper mapper;
    private final DateRanges ranges;
    private final PolicyService policies;
    private final BizClock clock;

    /** 사유별 비가동 — group = PLANNED / UNPLANNED, reasonCd null = 사유 없음(분류마다 한 줄) */
    public record Loss(String cat, String reasonCd, String reasonNm, String group, long sec, long intervals) {
    }

    /** 지시별 계획 대비 — attainment = 양품 ÷ 계획(소수 4자리), 수량은 글자(단위 자릿수 그대로) */
    public record WoProgress(long woId, String woNo, String itemCd, String itemNm, String planQty, String goodQty, String unitCd,
            LocalDate planStart, String status, BigDecimal attainment) {
    }

    /** 하루 — KPI 계산 결과 그대로(raw·kpi) */
    public record Day(String day, Raw raw, Result kpi) {
    }

    /** KPI 목표(0 = 없음 → null). 비율은 % 정수, MTTR 은 분 */
    public record Targets(Integer oeePct, Integer availabilityPct, Integer performancePct, Integer qualityPct, Integer mttrMin) {
    }

    public record Dashboard(String plantCd, String timeZone, String basis, String from, String to, OffsetDateTime fromAt, OffsetDateTime until,
            String prevFrom, String prevTo, KpiLine total, KpiLine prev, List<Day> daily, List<KpiLine> equips, List<Loss> losses,
            long unknownSec, long uncoveredSec, List<WoProgress> wos, boolean wosTruncated, Targets targets) {
    }

    @Transactional(readOnly = true)
    public Dashboard load(String plantCd, String from, String to, String keyword, String basisCd) {
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
        String basis = blank(basisCd) ? policies.value(Policies.CMS_DASH_BASIS, plant) : KpiService.basis(basisCd);
        String kw = blank(keyword) ? null : keyword.strip();

        KpiReport period = kpi.report(plant, f.toString(), t.toString(), kw, basis);
        long days = ChronoUnit.DAYS.between(f, t) + 1;
        LocalDate pf = f.minusDays(days);
        LocalDate pt = f.minusDays(1);
        KpiReport before = kpi.report(plant, pf.toString(), pt.toString(), kw, basis);

        ZoneId z = ZoneId.of(period.timeZone());
        LocalDate today = CmsSupport.now(clock, z).toLocalDate();
        List<Day> daily = new ArrayList<>();
        for (LocalDate d = f; !d.isAfter(t) && !d.isAfter(today); d = d.plusDays(1)) {
            KpiLine x = kpi.report(plant, d.toString(), d.toString(), kw, basis).plant();
            daily.add(new Day(d.toString(), x.raw(), x.kpi()));
        }

        String likeKw = kw == null ? null : like(kw.toUpperCase(Locale.ROOT));
        List<Loss> losses = new ArrayList<>();
        for (LossRow r : mapper.losses(plant, period.from(), period.until(), likeKw)) {
            losses.add(new Loss(r.getCat(), r.getReasonCd(), r.getReasonNm(), PLANNED_CATS.contains(r.getCat()) ? "PLANNED" : "UNPLANNED", r.getSec(),
                    r.getIntervals()));
        }
        Raw total = period.plant().raw();

        List<WoRow> rows = mapper.wos(plant, f, t, MAX_WOS + 1);
        List<WoProgress> wos = new ArrayList<>();
        for (WoRow r : rows.subList(0, Math.min(rows.size(), MAX_WOS))) {
            BigDecimal att = r.getPlanNum() == null || r.getPlanNum().signum() <= 0 ? null
                    : r.getGoodNum().divide(r.getPlanNum(), EquipKpi.RATIO_SCALE, RoundingMode.HALF_UP);
            wos.add(new WoProgress(r.getWoId(), r.getWoNo(), r.getItemCd(), r.getItemNm(), r.getPlanQty(), r.getGoodQty(), r.getUnitCd(),
                    r.getPlanStart(), r.getStatus(), att));
        }
        return new Dashboard(plant, period.timeZone(), basis, f.toString(), t.toString(), period.from(), period.until(), pf.toString(), pt.toString(),
                period.plant(), before.plant(), daily, period.equips(), losses, total.unknownSec(), period.plant().kpi().uncoveredSec(), wos,
                rows.size() > MAX_WOS, targets(plant));
    }

    private Targets targets(String plant) {
        return new Targets(target(Policies.CMS_TARGET_OEE_PCT, plant), target(Policies.CMS_TARGET_AVAIL_PCT, plant),
                target(Policies.CMS_TARGET_PERF_PCT, plant), target(Policies.CMS_TARGET_QUAL_PCT, plant), target(Policies.CMS_TARGET_MTTR_MIN, plant));
    }

    /** 0 = 목표 없음 */
    private Integer target(String key, String plant) {
        int v = Integer.parseInt(policies.value(key, plant));
        return v <= 0 ? null : v;
    }
}
