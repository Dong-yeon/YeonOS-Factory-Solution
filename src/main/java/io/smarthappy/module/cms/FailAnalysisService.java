package io.smarthappy.module.cms;

import static io.smarthappy.module.cms.CmsSupport.blank;
import static io.smarthappy.module.cms.CmsSupport.date;
import static io.smarthappy.module.cms.CmsSupport.like;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.CmsFailMapper.BarRow;
import io.smarthappy.module.cms.CmsFailMapper.FailMwoRow;
import io.smarthappy.module.cms.CmsFailMapper.GroupRow;
import io.smarthappy.module.cms.CmsFailMapper.RepeatRow;
import io.smarthappy.module.cms.KpiService.KpiLine;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 고장 분석 (cms-dashboard 고장 분석 탭) — docs/33 16.4 G1 · 16.5 G2. 작업지시 원장만 읽는다(상태 원장 값은 KpiService 에서 옆에 둘 뿐).
 *
 * <pre>
 * 고장       = CmsFailMapper.failWhere 하나(BM · DONE·CLOSED · 설비 · 고장 시각 = coalesce(정지 시작, 신고) ∈ [from, until)) — KPI F3 와 같은 정의
 * 기간       공장 현지 날짜 [from 0시, to 다음날 0시), 지금에서 자름. 최대 92일(KPI 와 같음)
 * 파레토     축 = CAUSE(기본)·SYMPTOM·CAT(원인 상위 분류)·EQUIP (+ G3 MECH·DETECT·IMPACT). 건수 큰 순, 정지 시간 같이
 * 분해(G2)   설비별·담당별 — 평균 대기(신고→착수)·수리(착수→완료)·정지·공수 = 합 ÷ 그 값이 있는 건수
 * 반복       같은 설비 × 같은 원인, 앞 고장과 간격 ≤ N일(공장 정책 CMS_REPEAT_FAIL_DAYS) — 기간 앞 N일 고장도 앞 고장으로 본다
 * 드릴다운   같은 조건 + 축 키 → 작업지시 목록(막대 값 = 건수)
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class FailAnalysisService {

    static final int MAX_DAYS = KpiService.MAX_DAYS;
    static final int MAX_MWOS = 500;
    /** 키 없음(값이 비어 있는 막대)의 드릴다운 키 */
    static final String NONE_KEY = "-";
    static final Set<String> AXES = Set.of("CAUSE", "SYMPTOM", "CAT", "EQUIP", "MECH", "DETECT", "IMPACT");
    /** 공통코드 축 → 코드 그룹(이름은 보는 사람 언어) */
    static final Map<String, String> CODE_AXES = Map.of("CAT", "CMS_FAIL_CAT", "MECH", MwoService.ISO_MECH, "DETECT", MwoService.ISO_DETECT,
            "IMPACT", MwoService.ISO_IMPACT);

    private final CmsFailMapper mapper;
    private final CmsMapper cms;
    private final KpiService kpi;
    private final CodeService codes;
    private final DateRanges ranges;
    private final PolicyService policies;
    private final BizClock clock;

    public record Bar(String keyCd, String keyNm, long failures, long downSec) {
    }

    /**
     * 분해 한 묶음. 평균은 분(소수 1자리), 그 값이 있는 건이 없으면 null.
     * 설비 묶음만: collected(상태 원장 수집 여부)·kpiMtbfMin·kpiMttrMin(상태 원장) / woMtbfMin·woMttrMin(작업지시 — 수집 없는 설비만, F3)
     */
    public record Group(String keyCd, String keyNm, long failures, long downSec, long downCount, long waitCount, long repairCount, long laborCount,
            BigDecimal avgDownMin, BigDecimal avgWaitMin, BigDecimal avgRepairMin, BigDecimal avgLaborMin, Boolean collected,
            BigDecimal kpiMtbfMin, BigDecimal kpiMttrMin, BigDecimal woMtbfMin, BigDecimal woMttrMin) {
    }

    /** 반복 — minGapDays = 가장 짧은 간격(일, 소수 1자리) */
    public record Repeat(String equipCd, String equipNm, String causeCd, String causeNm, long repeats, OffsetDateTime lastAt, BigDecimal minGapDays) {
    }

    public record FailReport(String plantCd, String timeZone, String from, String to, OffsetDateTime fromAt, OffsetDateTime until, String axis,
            long failures, long downSec, long detectRecorded, long detectPreventive, List<Bar> bars, List<Group> byEquip, List<Group> byAssignee,
            List<Repeat> repeats, int repeatDays) {
    }

    public record FailMwo(long mwoId, String mwoNo, String status, String title, String equipCd, String equipNm, OffsetDateTime failAt,
            String symptomCd, String symptomNm, String causeCd, String causeNm, String actionCd, String actionNm, Long downSec, Long waitSec,
            Long repairSec, String assigneeNm) {
    }

    public record FailMwos(String axis, String key, List<FailMwo> list, boolean truncated) {
    }

    /** 검증·정규화된 조건 */
    private record Crit(String plant, ZoneId zone, LocalDate f, LocalDate t, OffsetDateTime a, OffsetDateTime until, String keyword,
            String likeKw, Long classId, String axis) {
    }

    @Transactional(readOnly = true)
    public FailReport load(String plantCd, String from, String to, String keyword, String classCd, String axis) {
        Crit c = crit(plantCd, from, to, keyword, classCd, axis);
        String grp = CODE_AXES.get(c.axis);
        Map<String, String> codeNames = grp == null ? Map.of() : codes.names(grp, LocaleContextHolder.getLocale());
        List<Bar> bars = new ArrayList<>();
        long failures = 0;
        long down = 0;
        List<BarRow> rows = mapper.pareto(c.plant, c.a, c.until, c.likeKw, c.classId, c.axis);
        for (BarRow r : rows) {
            String nm = grp != null && r.getKeyCd() != null ? codeNames.getOrDefault(r.getKeyCd(), r.getKeyCd()) : r.getKeyNm();
            bars.add(new Bar(r.getKeyCd(), nm, r.getFailures(), r.getDownSec()));
            failures += r.getFailures();
            down += r.getDownSec();
        }
        // 탐지 방법(G3): 기록한 고장 수 · 예방 활동(점검·예방 보전·상태 감시 등, 코드 속성 preventive)으로 찾은 수 — "점검으로 찾은 고장 비율"
        List<BarRow> detect = "DETECT".equals(c.axis) ? rows : mapper.pareto(c.plant, c.a, c.until, c.likeKw, c.classId, "DETECT");
        Set<String> preventive = new java.util.HashSet<>();
        codes.codes(MwoService.ISO_DETECT, LocaleContextHolder.getLocale()).forEach(x -> {
            if (Boolean.TRUE.equals(x.attrs().get("preventive"))) {
                preventive.add(x.code());
            }
        });
        long detectRecorded = detect.stream().filter(r -> r.getKeyCd() != null).mapToLong(BarRow::getFailures).sum();
        long detectPreventive = detect.stream().filter(r -> r.getKeyCd() != null && preventive.contains(r.getKeyCd())).mapToLong(BarRow::getFailures).sum();
        // 설비별 MTBF·MTTR — 상태 원장(KPI)과 작업지시 기준(F3)을 이름을 나눠 옆에. 같은 기간·설비 글자
        Map<Long, KpiLine> kpiByEquip = new HashMap<>();
        kpi.report(c.plant, c.f.toString(), c.t.toString(), c.keyword).equips().forEach(l -> kpiByEquip.put(l.equipId(), l));
        List<Group> byEquip = new ArrayList<>();
        for (GroupRow r : mapper.breakdown(c.plant, c.a, c.until, c.likeKw, c.classId, "EQUIP")) {
            byEquip.add(group(r, kpiByEquip.get(r.getEquipId())));
        }
        List<Group> byAssignee = new ArrayList<>();
        for (GroupRow r : mapper.breakdown(c.plant, c.a, c.until, c.likeKw, c.classId, "ASSIGNEE")) {
            byAssignee.add(group(r, null));
        }
        int days = Integer.parseInt(policies.value(Policies.CMS_REPEAT_FAIL_DAYS, c.plant));
        List<Repeat> repeats = new ArrayList<>();
        for (RepeatRow r : mapper.repeats(c.plant, c.a.minusDays(days), c.a, c.until, c.likeKw, c.classId, days)) {
            repeats.add(new Repeat(r.getEquipCd(), r.getEquipNm(), r.getCauseCd(), r.getCauseNm(), r.getRepeats(), CmsSupport.at(r.getLastAt(), c.zone),
                    BigDecimal.valueOf(r.getMinGapSec()).divide(BigDecimal.valueOf(86400), 1, RoundingMode.HALF_UP)));
        }
        return new FailReport(c.plant, c.zone.getId(), c.f.toString(), c.t.toString(), c.a, c.until, c.axis, failures, down, detectRecorded,
                detectPreventive, bars, byEquip, byAssignee, repeats, days);
    }

    /** 드릴다운 — 파레토와 같은 조건 + 축 키(NONE_KEY = 값 없음). 최대 500건(넘으면 truncated) */
    @Transactional(readOnly = true)
    public FailMwos mwos(String plantCd, String from, String to, String keyword, String classCd, String axis, String key) {
        Crit c = crit(plantCd, from, to, keyword, classCd, axis);
        if (blank(key) || key.length() > 40) {
            throw CmsSupport.fieldError("key", blank(key) ? "REQUIRED" : "FORMAT");
        }
        String k = key.strip();
        boolean none = NONE_KEY.equals(k);
        List<FailMwoRow> rows = mapper.mwos(c.plant, c.a, c.until, c.likeKw, c.classId, c.axis, none ? null : k, none, MAX_MWOS + 1);
        List<FailMwo> list = new ArrayList<>();
        for (FailMwoRow r : rows.subList(0, Math.min(rows.size(), MAX_MWOS))) {
            list.add(new FailMwo(r.getMwoId(), r.getMwoNo(), r.getStatus(), r.getTitle(), r.getEquipCd(), r.getEquipNm(), CmsSupport.at(r.getFailAt(), c.zone),
                    r.getSymptomCd(), r.getSymptomNm(), r.getCauseCd(), r.getCauseNm(), r.getActionCd(), r.getActionNm(), r.getDownSec(), r.getWaitSec(),
                    r.getRepairSec(), r.getAssigneeNm()));
        }
        return new FailMwos(c.axis, k, list, rows.size() > MAX_MWOS);
    }

    private Crit crit(String plantCd, String from, String to, String keyword, String classCd, String axis) {
        FieldErrors e = FieldErrors.create();
        if (blank(plantCd)) {
            e.add("plantCd", "REQUIRED");
        }
        LocalDate f = date(from, "from", e);
        LocalDate t = date(to, "to", e);
        String ax = blank(axis) ? "CAUSE" : axis.strip().toUpperCase(Locale.ROOT);
        if (!AXES.contains(ax)) {
            e.add("axis", "CODE");
        }
        e.throwIfAny();
        if (keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        ranges.check(f, t, MAX_DAYS);
        Long classId = null;
        if (!blank(classCd)) {
            String cd = classCd.strip().toUpperCase(Locale.ROOT);
            classId = cd.length() > 30 ? null : cms.findClassId(cd, false);
            if (classId == null) {
                throw CmsSupport.fieldError("classCd", "NO_SUCH_CODE");
            }
        }
        String plant = plantCd.strip();
        ZoneId z = clock.zone(plant);
        OffsetDateTime a = f.atStartOfDay(z).toOffsetDateTime();
        OffsetDateTime b = t.plusDays(1).atStartOfDay(z).toOffsetDateTime();
        OffsetDateTime now = CmsSupport.now(clock, z);
        OffsetDateTime until = b.isAfter(now) ? now : b;
        if (until.isBefore(a)) {
            until = a;
        }
        String kw = blank(keyword) ? null : keyword.strip();
        return new Crit(plant, z, f, t, a, until, kw, kw == null ? null : like(kw.toUpperCase(Locale.ROOT)), classId, ax);
    }

    private static Group group(GroupRow r, KpiLine k) {
        return new Group(r.getKeyCd(), r.getKeyNm(), r.getFailures(), r.getDownSec(), r.getDownCount(), r.getWaitCount(), r.getRepairCount(),
                r.getLaborCount(), avg(r.getDownSec(), r.getDownCount()), avg(r.getWaitSec(), r.getWaitCount()), avg(r.getRepairSec(), r.getRepairCount()),
                r.getLaborCount() == 0 ? null : BigDecimal.valueOf(r.getLaborMin()).divide(BigDecimal.valueOf(r.getLaborCount()), 1, RoundingMode.HALF_UP),
                k == null ? null : k.kpi().collected(), k == null ? null : k.kpi().mtbfMin(), k == null ? null : k.kpi().mttrMin(),
                k == null || k.wo() == null ? null : k.wo().mtbfMin(), k == null || k.wo() == null ? null : k.wo().mttrMin());
    }

    /** 초 합 ÷ 건수 → 분(소수 1자리) */
    private static BigDecimal avg(long sec, long count) {
        return count == 0 ? null : EquipKpi.minutes(sec, count);
    }
}
