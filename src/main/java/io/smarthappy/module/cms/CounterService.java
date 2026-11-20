package io.smarthappy.module.cms;

import static io.smarthappy.module.cms.CmsSupport.at;
import static io.smarthappy.module.cms.CmsSupport.blank;
import static io.smarthappy.module.cms.CmsSupport.decimal;
import static io.smarthappy.module.cms.CmsSupport.fieldError;
import static io.smarthappy.module.cms.CmsSupport.like;
import static io.smarthappy.module.cms.CmsSupport.me;
import static io.smarthappy.module.cms.CmsSupport.now;
import static io.smarthappy.module.cms.CmsSupport.text;
import static io.smarthappy.module.cms.CmsSupport.yn;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
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

import io.smarthappy.module.cms.CmsCounterDtos.CounterExport;
import io.smarthappy.module.cms.CmsCounterDtos.CounterForm;
import io.smarthappy.module.cms.CmsCounterDtos.CounterPage;
import io.smarthappy.module.cms.CmsCounterDtos.CounterRow;
import io.smarthappy.module.cms.CmsCounterDtos.CounterSort;
import io.smarthappy.module.cms.CmsCounterDtos.ReadingForm;
import io.smarthappy.module.cms.CmsCounterDtos.ReadingResult;
import io.smarthappy.module.cms.CmsCounterDtos.ReadingRow;
import io.smarthappy.module.cms.CmsDtos.TargetRow;
import io.smarthappy.module.cms.CmsPmDtos.GenResult;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;

import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

/**
 * 사용량 카운터 (cms-counter) — docs/33 4.4, D4. 읽기는 추가 + 무효만, 누적 = Σ 증가량.
 * 입력은 카운터 행을 잠가 줄 세우고, 같은 트랜잭션에서 그 카운터의 사용량 예방점검 예정을 만든다(PmGenerator).
 */
@Service
@RequiredArgsConstructor
public class CounterService {

    static final String PROG = "cms-counter";
    static final Pattern COUNTER_CD = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,29}$");
    static final Set<String> KINDS = Set.of("SHOTS", "CYCLES", "RUN_HOURS", "OTHER");
    static final int MAX_ROWS = 1000;
    /** 화면 이력 행 수(최근부터) */
    static final int READING_ROWS = 500;

    private final CmsCounterMapper mapper;
    private final MwoService mwos;
    private final PmGenerator generator;
    private final InboxService inbox;
    private final BizClock clock;
    private final PolicyService policies;
    private final ExcelExportService excel;
    private final MessageSource messages;

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public List<CounterRow> list(String keyword, String useYn, String targetType) {
        checkListArgs(keyword, useYn, targetType);
        List<CounterRow> rows = mapper.findCounters(blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT)),
                blank(useYn) ? null : useYn, blank(targetType) ? null : targetType, null, null, MAX_ROWS + 1);
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException(HbErrorCode.CMS_TOO_MANY_ROWS, MAX_ROWS);
        }
        Map<String, ZoneId> zones = new HashMap<>();
        rows.forEach(c -> decorate(c, zones));
        return rows;
    }

    private static void checkListArgs(String keyword, String useYn, String targetType) {
        if (!blank(useYn) && !"Y".equals(useYn) && !"N".equals(useYn) || keyword != null && keyword.length() > 100
                || !blank(targetType) && !"EQUIP".equals(targetType) && !"ASSET".equals(targetType)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
    }

    /** grid(UI 단계 3 ①, docs/33 15.1) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link CounterSort}) */
    @Transactional(readOnly = true)
    public CounterPage grid(String keyword, String useYn, String targetType, Integer page, Integer size, List<String> sort, Boolean total) {
        checkListArgs(keyword, useYn, targetType);
        String k = blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT));
        String u = blank(useYn) ? null : useYn;
        String t = blank(targetType) ? null : targetType;
        PageQuery pq = PageQuery.parse(page, size, sort, total, CounterSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("counterCd,asc"));
        List<CounterRow> rows = mapper.findCounterPage(k, u, t, null, null, pq);
        Map<String, ZoneId> zones = new HashMap<>();
        rows.forEach(c -> decorate(c, zones));
        return new CounterPage(rows, pq.isWithTotal() ? mapper.countCounters(k, u, t, null, null) : null);
    }

    /** grid 열(화면 카운터 표와 같은 칸). 누적은 카운터 자릿수 글자(화면이 단위를 붙인다), 수명(NONE·OK·NEAR·OVER)은 화면이 수명 타수와 함께 글자·배지로 */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("counterCd", "cms.counter.col.counterCd", 120).withSort(),
            ColumnSpec.text("counterNm", "cms.counter.col.counterNm", 160).withSort(),
            ColumnSpec.text("targetCd", "cms.mwo.col.target", 110).withSort(),
            ColumnSpec.text("targetNm", "cms.mwo.col.targetNm", 150),
            ColumnSpec.code("counterKind", "cms.counter.col.kind", "CMS_COUNTER_KIND", 90).withSort(),
            ColumnSpec.text("totalText", "cms.counter.col.total", 100),
            ColumnSpec.text("lastReadAt", "cms.counter.col.lastRead", 130).withSort(),   // 대상 공장 현지 시각(목록과 같은 decorate) — 화면이 글자로
            ColumnSpec.text("life", "cms.counter.col.life", 150),
            ColumnSpec.of("useYn", "cms.col.useYn", ColumnType.YN, 70).withSort());

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    /** 엑셀 열 바꿈 — 마지막 읽기 = 공장 현지 글자, 누적 = 글자 + 단위 칸, 수명 = 이름(수명 타수 포함) */
    static final Map<String, List<ColumnSpec>> EXPORT_REPLACE = Map.of(
            "lastReadAt", List.of(ColumnSpec.text("lastReadAtText", "cms.counter.col.lastRead", 130)),
            "totalText", List.of(ColumnSpec.text("totalText", "cms.counter.col.total", 100), ColumnSpec.text("unitNm", "cms.tmpl.col.unit", 60)),
            "life", List.of(ColumnSpec.text("lifeNm", "cms.counter.col.life", 150)));

    /** 엑셀(C21) — grid 와 같은 조건·정렬(허용 목록)·보이는 열 */
    @Transactional(readOnly = true)
    public long export(CounterExport req, HttpServletResponse response) throws IOException {
        var r = req == null ? new CounterExport(null, null, null, null, null) : req;
        checkListArgs(r.keyword(), r.useYn(), r.targetType());
        List<ColumnSpec> cols = CmsSupport.exportColumns(GRID_COLUMNS, r.columns(), EXPORT_REPLACE);
        String k = blank(r.keyword()) ? null : like(r.keyword().strip().toUpperCase(Locale.ROOT));
        String u = blank(r.useYn()) ? null : r.useYn();
        String t = blank(r.targetType()) ? null : r.targetType();
        PageQuery pq = PageQuery.sortOnly(r.sort(), CounterSort.values(), List.of("counterCd,asc"));
        List<String> keys = new ArrayList<>();
        if (k != null) {
            keys.add("keyword");
        }
        if (u != null) {
            keys.add("useYn");
        }
        if (t != null) {
            keys.add("targetType");
        }
        Locale locale = LocaleContextHolder.getLocale();
        Map<String, ZoneId> zones = new HashMap<>();
        return excel.export(response, new ExportJob(PROG, "prog.cmsCounter", cols,
                mapper.countCounters(k, u, t, null, null), keys), w -> mapper.findCounterPage(k, u, t, null, null, pq).forEach(c -> {
                    decorate(c, zones);
                    c.setLifeNm(c.getLife() == null || "NONE".equals(c.getLife()) ? null
                            : messages.getMessage("js.cms.counter.life." + c.getLife(), new Object[] {String.valueOf(c.getLifeShots())}, c.getLife(), locale));
                    w.accept(c);
                }));
    }

    /** 계획 화면: 대상의 사용 중 카운터 */
    @Transactional(readOnly = true)
    public List<CounterRow> ofTarget(String targetType, String targetCd) {
        FieldErrors e = FieldErrors.create();
        TargetRow t = mwos.target(targetType, targetCd, e);
        if (t == null) {
            return List.of();
        }
        boolean equip = "EQUIP".equals(t.getTargetType());
        List<CounterRow> rows = mapper.findCounters(null, "Y", t.getTargetType(), equip ? t.getId() : null, equip ? null : t.getId(), MAX_ROWS);
        Map<String, ZoneId> zones = new HashMap<>();
        rows.forEach(c -> decorate(c, zones));
        return rows;
    }

    @Transactional(readOnly = true)
    public CounterRow get(long counterId) {
        CounterRow c = mapper.findCounter(counterId);
        if (c == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        decorate(c, new HashMap<>());
        return c;
    }

    @Transactional(readOnly = true)
    public List<ReadingRow> readings(long counterId) {
        CounterRow c = get(counterId);
        ZoneId z = ZoneId.of(c.getTimeZone());
        List<ReadingRow> rows = mapper.findReadings(counterId, READING_ROWS);
        rows.forEach(r -> {
            r.setReadAt(at(r.getReadAt(), z));
            r.setRecordedAt(at(r.getRecordedAt(), z));
            r.setVoidedAt(at(r.getVoidedAt(), z));
        });
        return rows;
    }

    /** 누적(무효 제외) — 계획·점검이 부른다 */
    BigDecimal total(long counterId) {
        return mapper.total(counterId);
    }

    void decorate(CounterRow c, Map<String, ZoneId> zones) {
        ZoneId z = zones.computeIfAbsent(c.getPlantCd() == null ? "" : c.getPlantCd(), k -> k.isEmpty() ? clock.zone() : clock.zone(k));
        c.setTimeZone(z.getId());
        c.setLastReadAt(at(c.getLastReadAt(), z));
        c.setLife(CounterTotals.life(c.getTotal(), c.getLifeShots()).name());
    }

    // ------------------------------------------------------------------ 카운터 등록·수정

    @Transactional
    public long create(CounterForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("CMS_COUNTER_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        CounterRow c = new CounterRow();
        String cd = blank(in.counterCd()) ? null : in.counterCd().strip().toUpperCase(Locale.ROOT);
        if (cd == null) {
            e.add("counterCd", "REQUIRED");
        } else if (!COUNTER_CD.matcher(cd).matches()) {
            e.add("counterCd", "FORMAT");
        }
        c.setCounterCd(cd);
        TargetRow t = mwos.target(in.targetType(), in.targetCd(), e);
        if (t != null) {
            c.setTargetType(t.getTargetType());
            c.setEquipId("EQUIP".equals(t.getTargetType()) ? t.getId() : null);
            c.setAssetId("ASSET".equals(t.getTargetType()) ? t.getId() : null);
        }
        String kind = blank(in.counterKind()) ? null : in.counterKind().strip();
        if (kind == null) {
            e.add("counterKind", "REQUIRED");
        } else if (!KINDS.contains(kind)) {
            e.add("counterKind", "CODE");
        }
        c.setCounterKind(kind);
        int scale = in.scale() == null ? 0 : in.scale();
        if (scale < 0 || scale > 6) {
            e.add("scale", "RANGE", 0, 6);
        }
        c.setScale(scale);
        apply(c, in, e);
        e.throwIfAny();
        try {
            return mapper.insertCounter(c, me.loginId());
        } catch (DuplicateKeyException ex) {
            throw fieldError("counterCd", "DUPLICATE");
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /** 수정 — 이름·단위·사용·비고만. 코드·대상·종류·자릿수는 고정(값의 뜻이 바뀌므로) */
    @Transactional
    public void update(long counterId, CounterForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (mapper.lockCounter(counterId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        CounterRow cur = mapper.findCounter(counterId);
        FieldErrors e = FieldErrors.create();
        if (!blank(in.counterCd()) && !in.counterCd().strip().toUpperCase(Locale.ROOT).equals(cur.getCounterCd())) {
            e.add("counterCd", "FIXED");
        }
        if (!blank(in.targetType()) && !in.targetType().strip().equals(cur.getTargetType())) {
            e.add("targetType", "FIXED");
        }
        if (!blank(in.targetCd()) && !in.targetCd().strip().toUpperCase(Locale.ROOT).equals(cur.getTargetCd())) {
            e.add("targetCd", "FIXED");
        }
        if (!blank(in.counterKind()) && !in.counterKind().strip().equals(cur.getCounterKind())) {
            e.add("counterKind", "FIXED");
        }
        if (in.scale() != null && in.scale() != cur.getScale()) {
            e.add("scale", "FIXED");
        }
        apply(cur, in, e);
        e.throwIfAny();
        cur.setRowVer(in.rowVer());
        if (mapper.updateCounter(cur, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    private static void apply(CounterRow c, CounterForm in, FieldErrors e) {
        c.setCounterNm(text(in.counterNm(), "counterNm", 100, true, e));
        c.setUnitNm(text(in.unitNm(), "unitNm", 20, false, e));
        c.setUseYn(yn(in.useYn(), "Y", "useYn", e));
        c.setRemark(text(in.remark(), "remark", 500, false, e));
    }

    // ------------------------------------------------------------------ 읽기 입력·무효

    /**
     * 읽기 입력 — 계기 값 또는 증가량 하나. 계기 값이면 직전 계기 값과의 차이(줄면 리셋 확인 필요, 첫 계기 값은 기준 = 0).
     * 읽은 시각은 마지막 읽기보다 앞설 수 없다. 같은 트랜잭션에서 사용량 예정 생성
     */
    @Transactional
    public ReadingResult addReading(long counterId, ReadingForm in) {
        LoginUser me = me();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (mapper.lockCounter(counterId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        CounterRow c = mapper.findCounter(counterId);
        if (in.lastReadingId() != null && !Objects.equals(in.lastReadingId() == 0 ? null : in.lastReadingId(), c.getLastReadingId())) {
            throw new BusinessException(HbErrorCode.CONFLICT);   // 화면을 연 뒤 다른 사람이 넣었거나 무효로 했다
        }
        FieldErrors e = FieldErrors.create();
        if (!"Y".equals(c.getUseYn())) {
            e.add("counter", "INACTIVE");
            e.throwIfAny();
        }
        ZoneId z = c.getPlantCd() == null ? clock.zone() : clock.zone(c.getPlantCd());
        OffsetDateTime readAt = CmsSupport.dateTime(in.readAt(), "readAt", e, z, clock);
        if (readAt == null && blank(in.readAt())) {
            readAt = now(clock, z);
        }
        if (readAt != null && c.getLastReadAt() != null && readAt.isBefore(c.getLastReadAt())) {
            // 화면은 분 단위로 보낸다 — 마지막 읽기와 같은 분이면 그 시각으로(초 차이로 거부하지 않게, C1 작업지시 시각과 같은 규칙)
            if (readAt.toInstant().truncatedTo(ChronoUnit.MINUTES).equals(c.getLastReadAt().toInstant().truncatedTo(ChronoUnit.MINUTES))) {
                readAt = c.getLastReadAt();
            } else {
                e.add("readAt", "BEFORE_LAST");
            }
        }
        boolean reset = "Y".equals(yn(in.resetYn(), "N", "resetYn", e));
        BigDecimal gauge = decimal(in.gaugeValue(), "gaugeValue", c.getScale(), e);
        BigDecimal delta = decimal(in.deltaQty(), "deltaQty", c.getScale(), e);
        if (blank(in.gaugeValue()) && blank(in.deltaQty())) {
            e.add("gaugeValue", "REQUIRED");
        } else if (!blank(in.gaugeValue()) && !blank(in.deltaQty())) {
            e.add("deltaQty", "GAUGE_OR_DELTA");
        } else if (gauge != null) {
            if (gauge.signum() < 0) {
                e.add("gaugeValue", "NOT_NEGATIVE");
            } else {
                delta = CounterTotals.delta(c.getLastGauge(), gauge, reset);
                if (delta == null) {
                    e.add("gaugeValue", "GAUGE_DECREASE", CmsPmDtos.text(c.getLastGauge(), c.getScale()));
                }
            }
        } else if (delta != null) {
            if (reset) {
                e.add("resetYn", "GAUGE_OR_DELTA");   // 리셋은 계기 값에만
            }
            if (delta.signum() <= 0) {
                e.add("deltaQty", "POSITIVE");
            }
        }
        String remark = text(in.remark(), "remark", 500, false, e);
        e.throwIfAny();
        long id;
        try {
            id = mapper.insertReading(counterId, readAt, gauge, delta, reset ? "Y" : "N", remark, me.loginId());
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
        GenResult g = generator.generateForCounter(counterId, me.loginId());
        BigDecimal total = mapper.total(counterId);
        return new ReadingResult(id, CmsPmDtos.text(delta, c.getScale()), CmsPmDtos.text(total, c.getScale()), g.runs(), g.workOrders());
    }

    /** 무효 (DELETE 권한) — 사유 필수, 마지막 유효 행부터. 이미 만든 예방점검 예정은 그대로 둔다(D6 — 사람이 건너뛴다) */
    @Transactional
    public void voidReading(long counterId, long readingId, String reason) {
        LoginUser me = me();
        if (mapper.lockCounter(counterId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        ReadingRow r = mapper.findReading(counterId, readingId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        FieldErrors e = FieldErrors.create();
        String why = text(reason, "reason", 500, true, e);
        e.throwIfAny();
        if (!"N".equals(r.getVoidYn())) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        CounterRow c = mapper.findCounter(counterId);
        if (!Objects.equals(c.getLastReadingId(), readingId)) {
            throw fieldError("reason", "VOID_ORDER");
        }
        try {
            if (mapper.voidReading(readingId, why, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "fk_cms_counter_equip_comp", "fk_cms_counter_asset_comp" -> fieldError("targetCd", "NO_SUCH_CODE");
            case "ck_cms_counter_reading_order" -> fieldError("readAt", "BEFORE_LAST");
            case "ck_cms_counter_reading_decrease", "ck_cms_counter_reading_chain" -> fieldError("gaugeValue", "GAUGE_DECREASE", "");
            case "ck_cms_counter_reading_void_order" -> fieldError("reason", "VOID_ORDER");
            case "fk_cms_counter_reading_counter_comp" -> fieldError("counter", "INACTIVE");
            default -> e;
        };
    }
}
