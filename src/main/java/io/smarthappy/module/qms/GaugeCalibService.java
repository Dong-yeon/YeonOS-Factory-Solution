package io.smarthappy.module.qms;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.cursor.Cursor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.module.qms.GaugeDtos.CalibForm;
import io.smarthappy.module.qms.GaugeDtos.CalibRow;
import io.smarthappy.module.qms.GaugeDtos.CalibValue;
import io.smarthappy.module.qms.GaugeDtos.GaugeDetail;
import io.smarthappy.module.qms.GaugeDtos.GaugeExportRequest;
import io.smarthappy.module.qms.GaugeDtos.GaugeRow;
import io.smarthappy.module.qms.GaugeDtos.GaugeSearch;
import io.smarthappy.module.qms.GaugeDtos.GaugeSort;
import io.smarthappy.module.qms.GaugeDtos.ProfileForm;
import io.smarthappy.module.qms.GaugeDtos.VoidRequest;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.esign.ESignService;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletResponse;
import io.smarthappy.platform.common.text.VisibleText;
import lombok.RequiredArgsConstructor;

/**
 * 계측기 교정 (qms-gauge-calib) — P3 QMS Q2a, docs/29.
 *
 * - 계측기 = P1 물리 자산 GAUGE. 이 화면이 고치는 자산 칸은 계측기 클래스·교정 주기뿐(rowVer 공유)
 * - 교정 기록은 추가 + 무효(DELETE 액션, 사유 필수)만 — 트리거가 최종. 다음 교정일 = 교정일 + 그때 주기(생성 칸)
 * - 최근 교정일은 교정 트리거가 맞춘다(D4) — 서비스는 손대지 않는다
 * - 판정은 GaugeStatus(순수 함수, 목록 SQL 과 같은 규칙). 막는 것은 2부(D5)
 */
@Service
@RequiredArgsConstructor
public class GaugeCalibService {

    /** 영향 LOT 표 상한(검사 행 기준) */
    static final int IMPACT_LIMIT = 1000;

    static final String PROG = "qms-gauge-calib";
    static final Set<String> RESULTS = Set.of("OK", "LIMITED", "NG");
    static final Set<String> AGENCIES = Set.of("INTERNAL", "EXTERNAL");
    static final Set<String> STATUSES = Set.of("OK", "DUE_SOON", "OVERDUE", "NG", "NO_RECORD", "INACTIVE");
    static final List<String> DEFAULT_SORT = List.of("assetCd,asc");

    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("assetCd", "qms.gauge.col.assetCd", 120).withSort().withPriority(1),
            ColumnSpec.text("assetNm", "qms.gauge.col.assetNm", 180).withSort().withPriority(1).withFlex().withMinWidth(120),
            ColumnSpec.code("gaugeClass", "qms.gauge.col.gaugeClass", "GAUGE_CLASS", 100).withSort().withPriority(2),
            ColumnSpec.code("status", "qms.gauge.col.status", "GAUGE_STATUS", 90).withPriority(1),
            ColumnSpec.of("nextDueDate", "qms.gauge.col.nextDueDate", ColumnSpec.ColumnType.DATE, 100).withSort().withPriority(1),
            ColumnSpec.of("daysLeft", "qms.gauge.col.daysLeft", ColumnSpec.ColumnType.INT, 70).withPriority(2),
            ColumnSpec.of("lastCalibDate", "qms.gauge.col.lastCalibDate", ColumnSpec.ColumnType.DATE, 100).withSort().withPriority(2),
            ColumnSpec.code("lastResult", "qms.gauge.col.lastResult", "CALIB_RESULT", 80).withPriority(2),
            ColumnSpec.of("calibCycleDays", "qms.gauge.col.calibCycleDays", ColumnSpec.ColumnType.INT, 80).withPriority(3),
            ColumnSpec.text("serialNo", "qms.gauge.col.serialNo", 120).withPriority(3),
            ColumnSpec.of("calibCnt", "qms.gauge.col.calibCnt", ColumnSpec.ColumnType.INT, 70).withPriority(3),
            ColumnSpec.of("useYn", "qms.gauge.col.useYn", ColumnSpec.ColumnType.YN, 60).withPriority(3));

    private final GaugeMapper mapper;
    private final PolicyService policies;
    private final InboxService inbox;
    private final ExcelExportService excel;
    private final BizClock clock;
    private final QmsESign esign;

    public record Page(List<GaugeRow> list, Long total) {
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public Page list(String keyword, String gaugeClass, String status, Boolean withUnused, Integer page, Integer size,
            List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, GaugeSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        GaugeSearch q = search(keyword, gaugeClass, status, withUnused, pq);
        return new Page(mapper.findPage(q), pq.isWithTotal() ? mapper.count(q) : null);
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    /** 기록 무결성 점검(docs/52 Y1): 계측기 서명 대상 = 교정 이력 하나하나 → 지금 해시들. 계측기가 없으면 빈 Optional */
    @Transactional(readOnly = true)
    public java.util.Optional<Set<String>> signHashes(long assetId) {
        GaugeSearch q = search(null, null, null, true, PageQuery.sortOnly(null, GaugeSort.values(), DEFAULT_SORT));
        GaugeRow g = mapper.findGauge(q, assetId);
        if (g == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(mapper.findHistory(assetId).stream()
                .map(c -> ESignService.hash(QmsESign.calib(g.getAssetCd(), c))).collect(java.util.stream.Collectors.toSet()));
    }

    @Transactional(readOnly = true)
    public GaugeDetail detail(long assetId) {
        GaugeSearch q = search(null, null, null, true, PageQuery.sortOnly(null, GaugeSort.values(), DEFAULT_SORT));
        GaugeRow g = mapper.findGauge(q, assetId);
        if (g == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        GaugeStatus.Result st = GaugeStatus.of("Y".equals(g.getUseYn()), g.getLastResult(), g.getNextDueDate(), q.getToday(),
                q.getDueSoonDays());
        g.setStatus(st.status().name());
        g.setDaysLeft(st.daysLeft());
        List<CalibRow> history = mapper.findHistory(assetId);
        Map<String, Long> byHash = new HashMap<>();
        history.forEach(c -> byHash.put(ESignService.hash(QmsESign.calib(g.getAssetCd(), c)), c.getCalibId()));
        List<GaugeDtos.CalibSign> signs = new ArrayList<>();
        for (var sg : esign.list(QmsESign.GAUGE, assetId)) {
            Long calibId = byHash.get(sg.contentHash());
            if (calibId != null) {
                signs.add(new GaugeDtos.CalibSign(calibId, QmsESign.SignView.of(sg, sg.contentHash())));
            }
        }
        return new GaugeDetail(g, history, q.getDueSoonDays(), esign.required(), signs);
    }

    /** 2부(측정값 입력·품질 게이트)가 부를 판정 — 게이지가 아니거나 없으면 NOT_FOUND */
    @Transactional(readOnly = true)
    public GaugeStatus.Result status(long assetId) {
        GaugeRow g = detail(assetId).gauge();
        return new GaugeStatus.Result(GaugeStatus.Status.valueOf(g.getStatus()), g.getDaysLeft());
    }

    /**
     * 측정 시각 기준 판정(QA I-1, 2026-10-27 사용자 결정) — 그 시각의 회사 날짜까지 들어온 마지막 유효 교정으로 판정한다. 지난 시각의 측정(엑셀
     * 올리기)은 그 뒤 재교정해도 그때 기한 초과·NG 였으면 막힌다. 지금 시각이면 {@link #status} 와 같다(교정일은 미래를 받지 않음).
     * 사용 여부는 이력이 없어 지금 값 — 지금 사용 중지면 지난 측정도 막는다. 게이지가 아니거나 없으면 NOT_FOUND.
     * 그 시각엔 쓸 수 있었어도 **뒤에 들어온 NG 교정의 영향 구간**(직전 유효 교정일 ~ NG 교정일, {@link #impact} 와 같은 경계)에 들면 NG
     * (25.6-Q 사용자 결정 — 올리는 순간 이미 불량 구간임을 안다). NG 날은 같은 날 재교정이 있어도 하루 전체가 구간(QA G-1 결정, impact 와 같음).
     * 지금 시각(화면)은 NG 가 오늘일 때만 해당 — 오늘 NG 뒤 재교정했어도 오늘 측정은 막히고 다음 날부터 쓸 수 있다
     */
    @Transactional(readOnly = true)
    public GaugeStatus.Status statusAt(long assetId, java.time.OffsetDateTime at) {
        LocalDate day = at.atZoneSameInstant(clock.zone()).toLocalDate();
        GaugeDtos.GaugeAsOf g = mapper.gaugeAsOf(assetId, day);
        if (g == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        GaugeStatus.Status s = GaugeStatus.of("Y".equals(g.getUseYn()), g.getResult(), g.getNextDueDate(), day,
                policies.intValue(Policies.QMS_CALIB_DUE_SOON_DAYS)).status();
        return s.usable() && mapper.inNgWindow(assetId, day) ? GaugeStatus.Status.NG : s;
    }

    /**
     * 교정 NG 영향 LOT (2부 Q9, docs/29_P3 12.5) — 직전 유효 교정일(그날 0시, 회사 시간대)부터 NG 교정일 끝까지 이 게이지로 잰
     * 살아 있는 측정값의 LOT·검사. 읽기만(보류·NCR 은 Q8). 정추적(그 LOT 으로 만든 LOT)은 LOT 보고서가 맡는다
     */
    @Transactional(readOnly = true)
    public GaugeDtos.ImpactResult impact(long assetId, long calibId) {
        CalibRow c = mapper.findCalib(calibId);
        if (c == null || c.getAssetId() != assetId) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"NG".equals(c.getResult()) || "Y".equals(c.getVoidYn())) {
            throw SamplingService.fieldError("calibId", "CALIB_NOT_NG");
        }
        java.time.ZoneId zone = clock.zone();
        java.time.LocalDate from = mapper.prevGoodCalibDate(assetId, c.getCalibDate());
        java.time.OffsetDateTime fromTs = from == null ? null : from.atStartOfDay(zone).toOffsetDateTime();
        java.time.OffsetDateTime toTs = c.getCalibDate().plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        List<GaugeDtos.ImpactRow> rows = mapper.impactLots(assetId, fromTs, toTs, IMPACT_LIMIT + 1);
        boolean truncated = rows.size() > IMPACT_LIMIT;
        List<GaugeDtos.ImpactRow> out = truncated ? rows.subList(0, IMPACT_LIMIT) : rows;
        out.forEach(r -> r.setLotDate(r.getLotOccurredAt().atZoneSameInstant(zone).toLocalDate()));
        return new GaugeDtos.ImpactResult(calibId, from, c.getCalibDate(), out,
                (int) out.stream().map(GaugeDtos.ImpactRow::getLotId).distinct().count(), truncated);
    }

    // ------------------------------------------------------------------ 교정 기록

    @Transactional
    public long addCalib(long assetId, CalibForm form) {
        LoginUser me = SamplingService.me();
        inbox.claimRequestKey("QMS_CALIB_CREATE");
        GaugeRow g = detail(assetId).gauge();
        if (!"Y".equals(g.getUseYn())) {
            throw SamplingService.fieldError("calibDate", "GAUGE_INACTIVE");
        }
        CalibValue v = validate(g, form);
        long calibId;
        try {
            calibId = mapper.insertCalib(v, me.loginId());
        } catch (DataIntegrityViolationException e) {
            String c = PgErrors.constraint(e).orElse("");
            if ("fk_qms_gauge_calib_asset_comp".equals(c)) {
                throw SamplingService.fieldError("calibDate", "GAUGE_INACTIVE");     // 검사 뒤 사용 중지
            }
            if ("fk_qms_gauge_calib_cust_comp".equals(c)) {
                throw SamplingService.fieldError("custCd", "NO_SUCH_CODE");
            }
            throw e;
        }
        // 서명 대상은 계측기(교정 행 id 는 지금 생김) — 정규 글자의 calibId 로 이 교정에 묶는다(docs/29_P3 12.10 E-4)
        esign.sign(form.esignToken(), QmsESign.APPROVE, QmsESign.GAUGE, assetId, QmsESign.calib(g.getAssetCd(), mapper.findCalib(calibId)));
        return calibId;
    }

    CalibValue validate(GaugeRow g, CalibForm in) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        LocalDate date = BomService.date(in.calibDate(), "calibDate", e);
        if (VisibleText.isBlank(in.calibDate())) {
            e.add("calibDate", "REQUIRED");
        } else if (date != null && date.isAfter(clock.today())) {
            e.add("calibDate", "FUTURE");
        }
        String result = code(in.result(), "result", RESULTS, e);
        String limit = "LIMITED".equals(result) ? VisibleText.reason(in.limitNote(), "limitNote", 1, 500, e)
                : VisibleText.optional(in.limitNote(), "limitNote", 500, e);
        if (!"LIMITED".equals(result)) {
            limit = null;
        }
        String agency = code(in.agency(), "agency", AGENCIES, e);
        Long custId = null;
        if ("EXTERNAL".equals(agency)) {
            String cd = VisibleText.isBlank(in.custCd()) ? null : in.custCd().strip().toUpperCase(Locale.ROOT);
            if (cd == null) {
                e.add("custCd", "REQUIRED");
            } else {
                custId = mapper.findActiveCust(cd);
                if (custId == null) {
                    e.add("custCd", "NO_SUCH_CODE");
                }
            }
        }
        String cert = VisibleText.optional(in.certNo(), "certNo", 50, e);
        // 주기: 입력이 없으면 계측기 기본 주기. 둘 다 없으면 기한을 정할 수 없다
        Integer cycle = in.cycleDays() != null ? in.cycleDays() : g.getCalibCycleDays();
        if (cycle == null) {
            e.add("cycleDays", "REQUIRED");
        } else if (cycle < 1 || cycle > 3650) {
            e.add("cycleDays", "RANGE", 1, 3650);
        }
        String remark = VisibleText.optional(in.remark(), "remark", 500, e);
        e.throwIfAny();
        return new CalibValue(g.getAssetId(), date, result, limit, agency, custId, cert, cycle, remark);
    }

    /** 무효 — 사유 필수, 한 번만. 최근 교정일은 트리거가 다시 계산 */
    @Transactional
    public void voidCalib(long calibId, VoidRequest req) {
        LoginUser me = SamplingService.me();
        FieldErrors e = FieldErrors.create();
        String reason = VisibleText.reason(req == null ? null : req.reason(), "reason", 1, 500, e);
        e.throwIfAny();
        CalibRow c = mapper.findCalib(calibId);
        if (c == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (mapper.voidCalib(calibId, reason, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.QMS_CALIB_VOIDED);
        }
    }

    // ------------------------------------------------------------------ 계측기 정보

    @Transactional
    public void updateProfile(long assetId, ProfileForm in) {
        LoginUser me = SamplingService.me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        detail(assetId);
        FieldErrors e = FieldErrors.create();
        String cls = VisibleText.isBlank(in.gaugeClass()) ? null : in.gaugeClass().strip();
        if ("VISUAL".equals(cls)) {
            e.add("gaugeClass", "CODE");
        }
        if (in.calibCycleDays() != null && (in.calibCycleDays() < 1 || in.calibCycleDays() > 3650)) {
            e.add("calibCycleDays", "RANGE", 1, 3650);
        }
        e.throwIfAny();
        try {
            if (mapper.updateProfile(assetId, cls, in.calibCycleDays(), in.rowVer(), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            if (PgErrors.constraint(ex).filter("fk_mst_phys_asset_gauge_class"::equals).isPresent()) {
                throw SamplingService.fieldError("gaugeClass", "CODE");
            }
            throw ex;
        }
    }

    // ------------------------------------------------------------------ 엑셀

    @Transactional(readOnly = true)
    public long export(GaugeExportRequest req, HttpServletResponse response) throws IOException {
        GaugeExportRequest r = req == null ? new GaugeExportRequest(null, null, null, null, null, null) : req;
        List<ColumnSpec> cols = ColumnSpec.selectExportable(COLUMNS, r.columns());
        GaugeSearch q = search(r.keyword(), r.gaugeClass(), r.status(), r.withUnused(),
                PageQuery.sortOnly(r.sort(), GaugeSort.values(), DEFAULT_SORT));
        List<String> keys = new ArrayList<>();
        if (q.getKeywordLike() != null) {
            keys.add("keyword");
        }
        if (q.getGaugeClass() != null) {
            keys.add("gaugeClass");
        }
        if (q.getStatus() != null) {
            keys.add("status");
        }
        return excel.export(response, new ExportJob(PROG, "prog.qmsGaugeCalib", cols, mapper.count(q), keys), w -> {
            try (Cursor<GaugeRow> c = mapper.cursor(q)) {
                c.forEach(w);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    // ------------------------------------------------------------------ 공통

    private GaugeSearch search(String keyword, String gaugeClass, String status, Boolean withUnused, PageQuery pq) {
        String kw = VisibleText.isBlank(keyword) ? null : keyword.strip();
        String cls = VisibleText.isBlank(gaugeClass) ? null : gaugeClass.strip();
        String st = VisibleText.isBlank(status) ? null : status.strip();
        if ((kw != null && kw.length() > 100) || (cls != null && cls.length() > 30) || (st != null && !STATUSES.contains(st))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return new GaugeSearch(kw == null ? null : "%" + BomService.likeEscape(kw) + "%", cls, st,
                Boolean.TRUE.equals(withUnused) || "INACTIVE".equals(st), clock.today(),
                policies.intValue(Policies.QMS_CALIB_DUE_SOON_DAYS), pq);
    }

    private static String code(String v, String field, Set<String> allowed, FieldErrors e) {
        if (VisibleText.isBlank(v)) {
            e.add(field, "REQUIRED");
            return null;
        }
        String s = v.strip();
        if (!allowed.contains(s)) {
            e.add(field, "CODE");
            return null;
        }
        return s;
    }
}
