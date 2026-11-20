package io.smarthappy.module.cms;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.CollectorDtos.PlantRow;
import io.smarthappy.module.cms.EquipCountDtos.CorrectForm;
import io.smarthappy.module.cms.EquipCountDtos.CountPage;
import io.smarthappy.module.cms.EquipCountDtos.CountRow;
import io.smarthappy.module.cms.EquipCountDtos.CountSort;
import io.smarthappy.module.cms.EquipCountDtos.EquipOption;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 수집 COUNT 조회·정정 (사무, cms-equip-count — docs/25 4.36, 4.27 후속). 기간 = 공장 현지 날짜 [from 00:00, to 다음날 00:00).
 * 정정 = 역분개 행 하나(CORRECT, qty = -원 qty, 사유 필수, 시각은 원 행 그대로) — 원 행은 그대로 두고, 한 원 행에 한 번만
 * (uq_cms_equip_count_reversal), 역분개 행은 다시 정정하지 않는다. POP 제안·CMS KPI 는 역분개 행을 더해 읽으므로 따로 고칠 것이 없다.
 */
@Service
@RequiredArgsConstructor
public class EquipCountService {

    public static final String PROG = "cms-equip-count";
    static final int MAX_DAYS = 92;
    static final int REASON_MAX = 500;
    private static final Set<String> METRICS = Set.of("GOOD", "SCRAP");

    private final EquipCountMapper mapper;
    private final CollectorMapper collectors;
    private final DateRanges ranges;
    private final PolicyService policies;
    private final BizClock clock;
    private final io.smarthappy.platform.excel.ExcelExportService excel;
    private final org.springframework.context.MessageSource messages;

    @Transactional(readOnly = true)
    public List<PlantRow> plants() {
        return collectors.plants();
    }

    @Transactional(readOnly = true)
    public List<EquipOption> equips(String plantCd) {
        if (CmsSupport.blank(plantCd)) {
            throw CmsSupport.fieldError("plantCd", "REQUIRED");
        }
        return mapper.equips(plantCd.strip());
    }

    static final java.util.Map<String, String> STATE_KEYS = java.util.Map.of("NORMAL", "js.cmsCount.state.NORMAL",
            "CORRECTED", "js.cmsCount.state.CORRECTED", "REVERSAL", "js.cmsCount.state.REVERSAL");
    static final java.util.Map<String, String> METRIC_KEYS = java.util.Map.of("GOOD", "js.cmsCount.metric.GOOD", "SCRAP", "js.cmsCount.metric.SCRAP");

    /** 수집 카운트 grid 엑셀(docs/25 4.40) — grid 와 같은 조건 검사(공장·기간 필수)·정렬·보이는 열, 상태·구분은 화면과 같은 이름 */
    @Transactional(readOnly = true)
    public long export(EquipCountDtos.CountExport req, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        EquipCountDtos.CountExport r = req == null ? new EquipCountDtos.CountExport(null, null, null, null, null, null, null) : req;
        Long equipId = null;
        if (!CmsSupport.blank(r.equipId())) {
            try {
                equipId = Long.valueOf(r.equipId().strip());
            } catch (NumberFormatException ex) {
                throw CmsSupport.fieldError("equipId", "FORMAT");
            }
        }
        Cond c = cond(r.plantCd(), r.metricCd(), r.from(), r.to());
        List<ColumnSpec> cols = io.smarthappy.module.production.MesExport.named(ColumnSpec.selectExportable(GRID_COLUMNS, r.columns()),
                java.util.Map.of("state", "stateNm", "metricCd", "metricNm"));
        PageQuery pq = PageQuery.sortOnly(r.sort(), CountSort.values(), List.of("occurredAt,desc"));
        List<String> keys = new java.util.ArrayList<>(List.of("plantCd", "from", "to"));
        if (equipId != null) {
            keys.add("equipId");
        }
        if (c.metric() != null) {
            keys.add("metricCd");
        }
        Long eq = equipId;
        return excel.export(response, new io.smarthappy.platform.excel.ExcelExportService.ExportJob(PROG, "prog.cmsEquipCount", cols,
                mapper.count(c.plant(), eq, c.metric(), c.start(), c.end()), keys),
                w -> mapper.findPage(c.plant(), eq, c.metric(), c.start(), c.end(), pq).forEach(x -> {
                    x.setStateNm(io.smarthappy.module.production.MesExport.name(messages, STATE_KEYS, x.getState()));
                    x.setMetricNm(io.smarthappy.module.production.MesExport.name(messages, METRIC_KEYS, x.getMetricCd()));
                    w.accept(x);
                }));
    }

    private record Cond(String plant, String metric, java.time.Instant start, java.time.Instant end) {
    }

    /** grid·엑셀 공통 조건 검사 — 공장 필수, 구분 코드, 기간(공장 현지 날짜, 92일 + 정책) */
    private Cond cond(String plantCd, String metricCd, String from, String to) {
        FieldErrors e = FieldErrors.create();
        if (CmsSupport.blank(plantCd)) {
            e.add("plantCd", "REQUIRED");
        }
        if (!CmsSupport.blank(metricCd) && !METRICS.contains(metricCd)) {
            e.add("metricCd", "CODE");
        }
        LocalDate f = date(from, "from", e);
        LocalDate t = date(to, "to", e);
        e.throwIfAny();
        ranges.check(f, t, MAX_DAYS);
        String plant = plantCd.strip();
        ZoneId zone = clock.zone(plant);
        return new Cond(plant, CmsSupport.blank(metricCd) ? null : metricCd, f.atStartOfDay(zone).toInstant(),
                t.plusDays(1).atStartOfDay(zone).toInstant());
    }

    @Transactional(readOnly = true)
    public CountPage grid(String plantCd, Long equipId, String metricCd, String from, String to, Integer page, Integer size,
            List<String> sort, Boolean total) {
        Cond c = cond(plantCd, metricCd, from, to);
        PageQuery pq = PageQuery.parse(page, size, sort, total, CountSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("occurredAt,desc"));
        return new CountPage(mapper.findPage(c.plant(), equipId, c.metric(), c.start(), c.end(), pq),
                pq.isWithTotal() ? mapper.count(c.plant(), equipId, c.metric(), c.start(), c.end()) : null);
    }

    /** grid 열 — 상태(그대로·정정됨·역분개)는 식별 칸 바로 다음(상세가 열려 좁아져도 보이게) */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.of("occurredAt", "cmsCount.col.occurredAt", ColumnType.DATETIME, 150).withSort(),
            ColumnSpec.text("equipCd", "cmsCount.col.equipCd", 110).withSort(),
            ColumnSpec.text("state", "cmsCount.col.state", 110),
            ColumnSpec.text("metricCd", "cmsCount.col.metric", 70).withSort(),
            ColumnSpec.dec("qty", "cmsCount.col.qty", 6, 90).withMinScale(0),
            ColumnSpec.text("equipNm", "cmsCount.col.equipNm", 160).withFlex().withPriority(3),
            ColumnSpec.text("collectorCd", "cmsCount.col.collector", 110).withPriority(3),
            ColumnSpec.text("reason", "cmsCount.col.reason", 160).withPriority(3),
            ColumnSpec.of("recordedAt", "cmsCount.col.recordedAt", ColumnType.DATETIME, 150).withSort().withPriority(3),
            ColumnSpec.text("recordedBy", "cmsCount.col.recordedBy", 110).withPriority(3));

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    @Transactional(readOnly = true)
    public CountRow detail(long countId) {
        CountRow r = mapper.findOne(countId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return r;
    }

    /** 정정 = 역분개 행 하나. 이미 정정됨·역분개 행 = 409 STATE(칸 countId), 동시 두 번 = 유일 인덱스가 하나만 통과 → 같은 409 */
    @Transactional
    public long correct(long countId, CorrectForm in) {
        FieldErrors e = FieldErrors.create();
        String reason = CmsSupport.text(in == null ? null : in.reason(), "reason", REASON_MAX, true, e);
        e.throwIfAny();
        CountRow o = mapper.findOne(countId);
        if (o == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (o.getReversalOfId() != null || o.getReversedById() != null) {
            throw state();
        }
        try {
            return mapper.insertReversal(o, o.getQty().negate(), reason, o.getOccurredAt(), CmsSupport.me().loginId());
        } catch (DuplicateKeyException ex) {
            throw state();
        }
    }

    private static BusinessException state() {
        return new BusinessException(HbErrorCode.CONFLICT).withFieldErrors(FieldErrors.create().add("countId", "STATE").list());
    }

    private static LocalDate date(String v, String field, FieldErrors e) {
        if (CmsSupport.blank(v)) {
            return null;   // 필수 검사는 DateRanges(REQUIRED)
        }
        try {
            return LocalDate.parse(v.strip());
        } catch (DateTimeParseException ex) {
            e.add(field, "FORMAT");
            return null;
        }
    }
}
