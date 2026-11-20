package io.smarthappy.module.cms;

import static io.smarthappy.module.cms.CmsSupport.at;
import static io.smarthappy.module.cms.CmsSupport.blank;
import static io.smarthappy.module.cms.CmsSupport.date;
import static io.smarthappy.module.cms.CmsSupport.fieldError;
import static io.smarthappy.module.cms.CmsSupport.like;
import static io.smarthappy.module.cms.CmsSupport.me;
import static io.smarthappy.module.cms.CmsSupport.text;
import static io.smarthappy.module.cms.CmsSupport.yn;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.CmsAlarmDtos.AlarmExport;
import io.smarthappy.module.cms.CmsAlarmDtos.AlarmPage;
import io.smarthappy.module.cms.CmsAlarmDtos.AlarmRow;
import io.smarthappy.module.cms.CmsAlarmDtos.AlarmSort;
import io.smarthappy.module.cms.CmsAlarmDtos.DefExport;
import io.smarthappy.module.cms.CmsAlarmDtos.DefForm;
import io.smarthappy.module.cms.CmsAlarmDtos.DefPage;
import io.smarthappy.module.cms.CmsAlarmDtos.DefRow;
import io.smarthappy.module.cms.CmsAlarmDtos.DefSort;
import io.smarthappy.module.cms.CmsAlarmDtos.TopRow;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.DateRanges;
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

import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

/**
 * 설비 알람 정의(cms-alarm-def)·알람 이력(cms-alarm) — docs/33 11.6 C8. 원장 쓰기는 {@link CmsAlarmSink}(수집)만.
 * 반복 알람 = 같은 설비·코드의 발생이 정책 시간(CMS_ALARM_REPEAT_HOURS) 안에 정책 횟수(CMS_ALARM_REPEAT_COUNT) 이상 — {@link #isRepeat}.
 */
@Service
@RequiredArgsConstructor
public class AlarmService {

    static final String PROG = "cms-alarm";
    static final String PROG_DEF = "cms-alarm-def";
    static final String POLICY_REPEAT_COUNT = "CMS_ALARM_REPEAT_COUNT";
    static final String POLICY_REPEAT_HOURS = "CMS_ALARM_REPEAT_HOURS";
    static final int MAX_ROWS = 2000;
    static final int MAX_DAYS = 92;
    static final int TOP_LIMIT = 20;
    static final Set<String> SEVERITIES = Set.of("INFO", "WARN", "CRITICAL");

    private final CmsAlarmMapper mapper;
    private final CmsMapper cms;
    private final DateRanges ranges;
    private final PolicyService policies;
    private final InboxService inbox;
    private final BizClock clock;
    private final ExcelExportService excel;
    private final MessageSource messages;

    /** 반복 판정 — 창(발생 앞 N 시간, 그 행 포함) 안 발생 수가 기준 이상 */
    static boolean isRepeat(int windowCount, int threshold) {
        return windowCount >= threshold;
    }

    // ------------------------------------------------------------------ 정의

    @Transactional(readOnly = true)
    public List<DefRow> defs(String keyword, String classCd, String useYn) {
        Long classId = defArgs(keyword, classCd, useYn);
        List<DefRow> rows = mapper.findDefs(blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT)), classId,
                blank(useYn) ? null : useYn);
        if (rows.size() > 1000) {
            throw new BusinessException(HbErrorCode.CMS_TOO_MANY_ROWS, 1000);
        }
        return rows;
    }

    /** 정의 목록·grid 공통 검증 — 클래스 코드 → id(없는 코드면 -1 = 0건) */
    private Long defArgs(String keyword, String classCd, String useYn) {
        if (keyword != null && keyword.length() > 100 || !blank(useYn) && !"Y".equals(useYn) && !"N".equals(useYn)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (blank(classCd)) {
            return null;
        }
        String cd = classCd.strip().toUpperCase(Locale.ROOT);
        Long c = cd.length() > 30 ? null : cms.findClassId(cd, false);
        return c == null ? -1L : c;
    }

    /** grid(UI 단계 3 ①, docs/33 15.1) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link DefSort}) */
    @Transactional(readOnly = true)
    public DefPage defGrid(String keyword, String classCd, String useYn, Integer page, Integer size, List<String> sort, Boolean total) {
        Long classId = defArgs(keyword, classCd, useYn);
        String k = blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT));
        String u = blank(useYn) ? null : useYn;
        PageQuery pq = PageQuery.parse(page, size, sort, total, DefSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("alarmCd,asc"));
        return new DefPage(mapper.findDefPage(k, classId, u, pq), pq.isWithTotal() ? mapper.countDefs(k, classId, u) : null);
    }

    /** 정의 grid 열(화면 정의 표와 같은 칸). 클래스 없음 = 모든 클래스(화면이 글자로) */
    static final List<ColumnSpec> DEF_GRID_COLUMNS = List.of(
            ColumnSpec.text("alarmCd", "cms.alarmDef.col.code", 110).withSort(),
            ColumnSpec.text("classCd", "cms.alarmDef.col.class", 110).withSort(),
            ColumnSpec.text("alarmNm", "cms.alarmDef.col.name", 180).withSort(),
            ColumnSpec.code("severity", "cms.alarmDef.col.severity", "CMS_ALARM_SEV", 80).withSort(),
            ColumnSpec.text("symptomCd", "cms.alarmDef.col.symptom", 100).withPriority(3),
            ColumnSpec.text("autoBmYn", "cms.alarmDef.col.autoBm", 100).withSort(),
            ColumnSpec.of("useYn", "cms.col.useYn", ColumnType.YN, 70).withSort());

    public List<ColumnSpec> defGridColumns() {
        return DEF_GRID_COLUMNS;
    }

    /** 정의 엑셀 열 바꿈 — 자동 신고는 화면처럼 표시 글자(Y 만) */
    static final Map<String, List<ColumnSpec>> DEF_EXPORT_REPLACE = Map.of(
            "autoBmYn", List.of(ColumnSpec.text("autoBmNm", "cms.alarmDef.col.autoBm", 100)));

    /** 정의 엑셀(C23) — grid 와 같은 조건·정렬(허용 목록)·보이는 열 */
    @Transactional(readOnly = true)
    public long defExport(DefExport req, HttpServletResponse response) throws IOException {
        DefExport r = req == null ? new DefExport(null, null, null, null, null) : req;
        Long classId = defArgs(r.keyword(), r.classCd(), r.useYn());
        List<ColumnSpec> cols = CmsSupport.exportColumns(DEF_GRID_COLUMNS, r.columns(), DEF_EXPORT_REPLACE);
        String k = blank(r.keyword()) ? null : like(r.keyword().strip().toUpperCase(Locale.ROOT));
        String u = blank(r.useYn()) ? null : r.useYn();
        PageQuery pq = PageQuery.sortOnly(r.sort(), DefSort.values(), List.of("alarmCd,asc"));
        List<String> keys = new ArrayList<>();
        for (String[] x : new String[][] {{"keyword", r.keyword()}, {"classCd", r.classCd()}, {"useYn", r.useYn()}}) {
            if (!blank(x[1])) {
                keys.add(x[0]);
            }
        }
        String mark = messages.getMessage("js.cms.alarmDef.autoMark", null, "Y", LocaleContextHolder.getLocale());
        return excel.export(response, new ExportJob(PROG_DEF, "prog.cmsAlarmDef", cols, mapper.countDefs(k, classId, u), keys),
                w -> mapper.findDefPage(k, classId, u, pq).forEach(d -> {
                    d.setAutoBmNm("Y".equals(d.getAutoBmYn()) ? mark : null);
                    w.accept(d);
                }));
    }

    @Transactional(readOnly = true)
    public DefRow def(long defId) {
        DefRow d = mapper.findDef(defId);
        if (d == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return d;
    }

    @Transactional
    public long createDef(DefForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("CMS_ALARM_DEF_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        DefRow d = new DefRow();
        if (blank(in.alarmCd())) {
            e.add("alarmCd", "REQUIRED");
        } else {
            String cd = CmsAlarmSink.code(in.alarmCd());
            if (cd == null) {
                e.add("alarmCd", "FORMAT");
            }
            d.setAlarmCd(cd);
        }
        if (!blank(in.classCd())) {
            String cd = in.classCd().strip().toUpperCase(Locale.ROOT);
            Long c = cd.length() > 30 ? null : cms.findClassId(cd, false);
            if (c == null) {
                e.add("classCd", "NO_SUCH_CODE");
            } else if (cms.findClassId(cd, true) == null) {
                e.add("classCd", "INACTIVE");
            } else {
                d.setEquipClassId(c);
            }
        }
        apply(d, in, e);
        e.throwIfAny();
        try {
            return mapper.insertDef(d, me.loginId());
        } catch (DuplicateKeyException ex) {
            throw fieldError("alarmCd", "DUPLICATE");
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /** 수정 — 코드·클래스 고정, 이름·심각도·현상·자동 신고·사용·비고 */
    @Transactional
    public void updateDef(long defId, DefForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (mapper.lockDef(defId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        DefRow cur = mapper.findDef(defId);
        FieldErrors e = FieldErrors.create();
        if (!blank(in.alarmCd()) && !in.alarmCd().strip().toUpperCase(Locale.ROOT).equals(cur.getAlarmCd())) {
            e.add("alarmCd", "FIXED");
        }
        String classCd = blank(in.classCd()) ? null : in.classCd().strip().toUpperCase(Locale.ROOT);
        if (in.classCd() != null && !java.util.Objects.equals(classCd, cur.getClassCd())) {
            e.add("classCd", "FIXED");
        }
        apply(cur, in, e);
        e.throwIfAny();
        cur.setRowVer(in.rowVer());
        try {
            if (mapper.updateDef(cur, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    private void apply(DefRow d, DefForm in, FieldErrors e) {
        d.setAlarmNm(text(in.alarmNm(), "alarmNm", 100, true, e));
        String sev = blank(in.severity()) ? "WARN" : in.severity().strip();
        if (!SEVERITIES.contains(sev)) {
            e.add("severity", "CODE");
        }
        d.setSeverity(sev);
        d.setSymptomId(null);
        if (!blank(in.symptomCd())) {
            String cd = in.symptomCd().strip().toUpperCase(Locale.ROOT);
            Long s = cd.length() > 20 ? null : mapper.findSymptomId(cd);
            if (s == null) {
                e.add("symptomCd", "NO_SUCH_CODE");
            }
            d.setSymptomId(s);
        }
        d.setAutoBmYn(yn(in.autoBmYn(), "N", "autoBmYn", e));
        if ("Y".equals(d.getAutoBmYn()) && !"CRITICAL".equals(sev)) {
            e.add("autoBmYn", "ALARM_AUTO_CRITICAL");
        }
        d.setUseYn(yn(in.useYn(), "Y", "useYn", e));
        d.setRemark(text(in.remark(), "remark", 500, false, e));
    }

    // ------------------------------------------------------------------ 이력

    /** 공장·발생일(공장 현지 날짜, 최대 92일)·검색어(설비·코드·이름)·열린 알람만 */
    @Transactional(readOnly = true)
    public List<AlarmRow> alarms(String plantCd, String from, String to, String keyword, boolean openOnly) {
        Period p = period(plantCd, from, to);
        if (keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        int hours = policies.intValue(POLICY_REPEAT_HOURS);
        List<AlarmRow> rows = mapper.findAlarms(p.plant, p.from, p.to, blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT)),
                openOnly, hours, MAX_ROWS + 1);
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException(HbErrorCode.CMS_TOO_MANY_ROWS, MAX_ROWS);
        }
        decorate(rows, p.zone);
        return rows;
    }

    /** 반복 여부(정책 창 안 발생 수) + 공장 현지 시각 — 목록·grid 같은 값 */
    private void decorate(List<AlarmRow> rows, ZoneId zone) {
        int n = policies.intValue(POLICY_REPEAT_COUNT);
        rows.forEach(r -> {
            r.setRepeat(isRepeat(r.getWindowCount(), n));
            r.setRaisedAt(at(r.getRaisedAt(), zone));
            r.setClearedAt(at(r.getClearedAt(), zone));
        });
    }

    /** grid(UI 단계 3 ①, docs/33 15.1) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link AlarmSort}) */
    @Transactional(readOnly = true)
    public AlarmPage alarmGrid(String plantCd, String from, String to, String keyword, boolean openOnly, Integer page, Integer size,
            List<String> sort, Boolean total) {
        Period p = period(plantCd, from, to);
        if (keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String k = blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT));
        PageQuery pq = PageQuery.parse(page, size, sort, total, AlarmSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("raisedAt,desc"));
        List<AlarmRow> rows = mapper.findAlarmPage(p.plant, p.from, p.to, k, openOnly, policies.intValue(POLICY_REPEAT_HOURS), pq);
        decorate(rows, p.zone);
        return new AlarmPage(rows, pq.isWithTotal() ? mapper.countAlarms(p.plant, p.from, p.to, k, openOnly) : null);
    }

    /** 이력 grid 열(화면 이력 표와 같은 칸). 시각은 공장 현지 글자, 분·반복은 계산 값이라 정렬 밖 */
    static final List<ColumnSpec> ALARM_GRID_COLUMNS = List.of(
            ColumnSpec.text("raisedAt", "cms.alarm.col.raised", 130).withSort(),
            ColumnSpec.text("equipCd", "cms.alarm.col.equip", 110).withSort(),
            ColumnSpec.text("alarmCd", "cms.alarm.col.code", 100).withSort(),
            ColumnSpec.text("alarmNm", "cms.alarm.col.name", 160).withSort(),
            ColumnSpec.code("severity", "cms.alarm.col.severity", "CMS_ALARM_SEV", 80).withSort(),
            ColumnSpec.text("clearedAt", "cms.alarm.col.cleared", 130).withSort(),
            ColumnSpec.of("minutes", "cms.alarm.col.minutes", ColumnType.INT, 60),
            ColumnSpec.text("repeat", "cms.alarm.col.repeat", 60),
            ColumnSpec.text("mwoNo", "cms.alarm.col.mwo", 120));

    public List<ColumnSpec> alarmGridColumns() {
        return ALARM_GRID_COLUMNS;
    }

    /** 이력 엑셀 열 바꿈 — 발생·해제 = 공장 현지 글자, 반복 = 표시 글자 */
    static final Map<String, List<ColumnSpec>> ALARM_EXPORT_REPLACE = Map.of(
            "raisedAt", List.of(ColumnSpec.text("raisedAtText", "cms.alarm.col.raised", 130)),
            "clearedAt", List.of(ColumnSpec.text("clearedAtText", "cms.alarm.col.cleared", 130)),
            "repeat", List.of(ColumnSpec.text("repeatNm", "cms.alarm.col.repeat", 60)));

    /** 이력 엑셀(C21) — grid 와 같은 조건(공장·기간 필수)·정렬(허용 목록)·보이는 열 */
    @Transactional(readOnly = true)
    public long alarmExport(AlarmExport req, HttpServletResponse response) throws IOException {
        var r = req == null ? new AlarmExport(null, null, null, null, null, null, null) : req;
        Period p = period(r.plantCd(), r.from(), r.to());
        if (r.keyword() != null && r.keyword().length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        List<ColumnSpec> cols = CmsSupport.exportColumns(ALARM_GRID_COLUMNS, r.columns(), ALARM_EXPORT_REPLACE);
        String k = blank(r.keyword()) ? null : like(r.keyword().strip().toUpperCase(Locale.ROOT));
        boolean open = "Y".equals(r.openOnly());
        PageQuery pq = PageQuery.sortOnly(r.sort(), AlarmSort.values(), List.of("raisedAt,desc"));
        List<String> keys = new ArrayList<>(List.of("plantCd", "from", "to"));
        if (k != null) {
            keys.add("keyword");
        }
        if (open) {
            keys.add("openOnly");
        }
        Locale locale = LocaleContextHolder.getLocale();
        String rep = messages.getMessage("js.cms.alarm.repeatTag", null, "R", locale);
        return excel.export(response, new ExportJob(PROG, "prog.cmsAlarm", cols,
                mapper.countAlarms(p.plant, p.from, p.to, k, open), keys), w -> {
                    List<AlarmRow> rows = mapper.findAlarmPage(p.plant, p.from, p.to, k, open, policies.intValue(POLICY_REPEAT_HOURS), pq);
                    decorate(rows, p.zone);
                    rows.forEach(a -> {
                        a.setRepeatNm(a.isRepeat() ? rep : null);
                        w.accept(a);
                    });
                });
    }

    /** 상위 알람(발생 수·활성 시간 순 20개) — 기간과 겹친 알람, 열린 알람은 지금까지 */
    @Transactional(readOnly = true)
    public List<TopRow> top(String plantCd, String from, String to) {
        Period p = period(plantCd, from, to);
        return mapper.top(p.plant, p.from, p.to, CmsSupport.now(clock, p.zone), TOP_LIMIT);
    }

    private record Period(String plant, ZoneId zone, OffsetDateTime from, OffsetDateTime to) {
    }

    private Period period(String plantCd, String from, String to) {
        FieldErrors e = FieldErrors.create();
        if (blank(plantCd)) {
            e.add("plantCd", "REQUIRED");
        }
        LocalDate f = date(from, "from", e);
        LocalDate t = date(to, "to", e);
        e.throwIfAny();
        ranges.check(f, t, MAX_DAYS);
        String plant = plantCd.strip();
        ZoneId z = clock.zone(plant);
        return new Period(plant, z, f.atStartOfDay(z).toOffsetDateTime(), t.plusDays(1).atStartOfDay(z).toOffsetDateTime());
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "fk_cms_alarm_def_class_comp" -> fieldError("classCd", "NO_SUCH_CODE");
            case "fk_cms_alarm_def_symptom_comp" -> fieldError("symptomCd", "NO_SUCH_CODE");
            case "ck_cms_alarm_def_auto" -> fieldError("autoBmYn", "ALARM_AUTO_CRITICAL");
            default -> e;
        };
    }
}
