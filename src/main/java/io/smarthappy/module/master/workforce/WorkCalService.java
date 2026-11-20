package io.smarthappy.module.master.workforce;

import static io.smarthappy.module.master.workforce.ShiftService.blank;
import static io.smarthappy.module.master.workforce.ShiftService.fieldError;
import static io.smarthappy.module.master.workforce.ShiftService.me;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.equip.EquipDtos.PlantRow;
import io.smarthappy.module.master.workforce.WorkCalDtos.CalDisable;
import io.smarthappy.module.master.workforce.WorkCalDtos.CalForm;
import io.smarthappy.module.master.workforce.WorkCalDtos.CalRow;
import io.smarthappy.module.master.workforce.WorkCalDtos.CalSort;
import io.smarthappy.module.master.workforce.WorkCalDtos.CalValue;
import io.smarthappy.module.master.workforce.WorkCalDtos.MonthView;
import io.smarthappy.module.master.workforce.WorkforceDtos.ShiftRow;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 공장 휴일 달력 관리 (mst-work-cal, docs/25 4.15). 예외 행만 저장한다 — 주간 쉬는 날은 정책 WEEKLY_OFF.
 * 행의 대상(공장·날짜·근무조)은 고정, 바꿀 수 있는 것은 OFF/WORK·사유뿐. 삭제 = 사용 중지.
 */
@Service
@RequiredArgsConstructor
public class WorkCalService {

    static final String PROG = "mst-work-cal";
    private static final int REASON_MAX = 200;

    private final WorkCalMapper mapper;
    private final WorkforceMapper workforce;
    private final WorkCalendar calendar;
    private final PolicyService policies;
    private final InboxService inbox;

    @Transactional(readOnly = true)
    public List<PlantRow> plants() {
        return workforce.findPlants();
    }

    /** 그리드 기본 정렬 = 기존 목록(findActive) 순서 */
    static final List<String> DEFAULT_SORT = List.of("calDate,asc", "plantCd,asc", "shiftCd,asc");

    /** 범위(비면 회사 공통)·근무조(비면 하루 전체)·구분 글자는 화면이 붙인다(format·emptyText) */
    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("calDate", "workCal.col.calDate", ColumnSpec.ColumnType.DATE, 100).withSort().withPriority(1),
            ColumnSpec.text("plantCd", "workCal.col.plant", 90).withSort().withPriority(2),
            ColumnSpec.text("shiftCd", "workCal.col.shift", 120).withSort().withPriority(2),
            ColumnSpec.text("dayType", "workCal.col.dayType", 70).withSort().withPriority(1),
            ColumnSpec.text("reason", "workCal.col.reason", 200).withPriority(1).withFlex().withMinWidth(100));

    public record Page(List<CalRow> list, Long total) {
    }

    /** 예외 목록 그리드(UI 단계 3 ①, 결정 docs/29 #45) — 한 달 화면의 rows 와 같은 조건 + 페이지·정렬·건수 */
    @Transactional(readOnly = true)
    public Page grid(String plantCd, String month, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, CalSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        String plant = plant(plantCd, false);
        YearMonth ym = yearMonth(month);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        return new Page(mapper.findActivePage(plant, from, to, pq), pq.isWithTotal() ? mapper.countActive(plant, from, to) : null);
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    /** 한 달 — plantCd 비우면 회사 공통 층만(근무조 없음) */
    @Transactional(readOnly = true)
    public MonthView month(String plantCd, String month) {
        String plant = plant(plantCd, false);
        YearMonth ym = yearMonth(month);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        return new MonthView(plant, ym.toString(),
                plant == null ? policies.value(WorkCalendar.POLICY) : policies.value(WorkCalendar.POLICY, plant),
                new ArrayList<>(calendar.range(plant, from, to).values()), mapper.findActive(plant, from, to),
                plant == null ? List.of() : workforce.findShifts(plant, true).stream().map(WorkCalendar::shiftDay).toList());
    }

    @Transactional
    public long create(CalForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_WORK_CAL_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String plant = plant(in.plantCd(), true);
        LocalDate day = null;
        if (blank(in.calDate())) {
            e.add("calDate", "REQUIRED");
        } else {
            try {
                day = LocalDate.parse(in.calDate().strip());
                if (day.getYear() < 2000 || day.getYear() > 2999) {
                    e.add("calDate", "FORMAT");
                }
            } catch (DateTimeParseException ex) {
                e.add("calDate", "FORMAT");
            }
        }
        String shift = blank(in.shiftCd()) ? null : in.shiftCd().strip().toUpperCase(Locale.ROOT);
        if (shift != null) {
            if (plant == null) {
                e.add("shiftCd", "SHIFT_NEEDS_PLANT");
            } else {
                ShiftRow s = workforce.findShifts(plant, false).stream().filter(x -> x.getShiftCd().equals(shift)).findFirst().orElse(null);
                if (s == null) {
                    e.add("shiftCd", "NO_SUCH_CODE");
                } else if (!"Y".equals(s.getUseYn())) {
                    e.add("shiftCd", "INACTIVE");
                }
            }
        }
        String type = dayType(in.dayType(), e);
        String reason = reason(in.reason(), e);
        e.throwIfAny();
        try {
            return mapper.insert(new CalValue(plant, day, shift, type, reason), me.loginId());
        } catch (DuplicateKeyException ex) {
            throw fieldError("calDate", "DUPLICATE");
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /** OFF/WORK·사유만 — 대상(공장·날짜·근무조)은 고정 */
    @Transactional
    public void update(long calId, CalForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        CalRow cur = mapper.findById(calId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        FieldErrors e = FieldErrors.create();
        String type = dayType(in.dayType(), e);
        String reason = reason(in.reason(), e);
        e.throwIfAny();
        if (mapper.update(calId, type, reason, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    @Transactional
    public void disable(long calId, CalDisable in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (mapper.findById(calId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (mapper.disable(calId, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 공장 코드 → 있는 공장(대문자), 비우면 회사 공통(null). 저장은 사용 중 공장만 */
    private String plant(String raw, boolean forSave) {
        if (blank(raw)) {
            return null;
        }
        String p = raw.strip().toUpperCase(Locale.ROOT);
        String use = p.length() > 20 ? null : workforce.plantUseYn(p);
        if (use == null) {
            throw fieldError("plantCd", "NO_SUCH_CODE");
        }
        if (forSave && !"Y".equals(use)) {
            throw fieldError("plantCd", "INACTIVE");
        }
        return p;
    }

    /** "yyyy-MM" → 달(2000~2999) */
    private static YearMonth yearMonth(String month) {
        YearMonth ym;
        try {
            ym = YearMonth.parse(blank(month) ? "" : month.strip());
        } catch (DateTimeParseException e) {
            throw fieldError("month", blank(month) ? "REQUIRED" : "FORMAT");
        }
        if (ym.getYear() < 2000 || ym.getYear() > 2999) {
            throw fieldError("month", "FORMAT");
        }
        return ym;
    }

    private static String dayType(String raw, FieldErrors e) {
        String t = blank(raw) ? null : raw.strip().toUpperCase(Locale.ROOT);
        if (t == null) {
            e.add("dayType", "REQUIRED");
        } else if (!"OFF".equals(t) && !"WORK".equals(t)) {
            e.add("dayType", "CODE");
        }
        return t;
    }

    private static String reason(String raw, FieldErrors e) {
        if (blank(raw)) {
            e.add("reason", "REQUIRED");
            return null;
        }
        String r = raw.strip();
        if (r.length() > REASON_MAX) {
            e.add("reason", "TOO_LONG", REASON_MAX);
        }
        return r;
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "fk_mst_work_cal_shift" -> fieldError("shiftCd", "NO_SUCH_CODE");
            case "fk_mst_work_cal_plant" -> fieldError("plantCd", "NO_SUCH_CODE");
            default -> e;
        };
    }
}
