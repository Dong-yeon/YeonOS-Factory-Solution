package io.smarthappy.module.master.workforce;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.workforce.WorkDayResolver.WorkDay;
import io.smarthappy.module.master.workforce.WorkforceDtos.ShiftForm;
import io.smarthappy.module.master.workforce.WorkforceDtos.ShiftRow;
import io.smarthappy.module.master.workforce.WorkforceDtos.ShiftSort;
import io.smarthappy.module.master.workforce.WorkforceDtos.ShiftValue;
import io.smarthappy.module.master.workforce.WorkforceDtos.WorkDayView;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 근무조 관리 (mst-shift) — P2 1단계, docs/25 4.2.
 * 겹침은 서비스가 먼저 칸 오류로 알리고, 동시 저장·직접 SQL 은 트리거 ck_mst_shift_overlap(공장 advisory 잠금)이 최종 판정.
 */
@Service
@RequiredArgsConstructor
public class ShiftService {

    static final String PROG = "mst-shift";
    static final Pattern SHIFT_CD = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,9}$");
    private static final Pattern HHMM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final DateTimeFormatter LOCAL_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final WorkforceMapper mapper;
    private final WorkDayResolver resolver;
    private final BizClock clock;
    private final InboxService inbox;
    private final PolicyService policies;

    /** 그리드 기본 정렬 = 기존 목록 순서 */
    static final List<String> DEFAULT_SORT = List.of("sortNo,asc", "startTime,asc", "shiftCd,asc");

    /** 시간 칸은 시작~끝(+다음 날) 한 칸, 작업일 기준은 글자 — 화면이 붙인다(format) */
    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("shiftCd", "shift.col.shiftCd", 80).withSort().withPriority(1),
            ColumnSpec.text("shiftNm", "shift.col.shiftNm", 120).withSort().withPriority(1).withFlex().withMinWidth(80),
            ColumnSpec.text("startTime", "shift.col.time", 150).withSort().withPriority(1),
            ColumnSpec.text("dayOffset", "shift.col.dayOffset", 90).withPriority(2),
            ColumnSpec.of("breakMin", "shift.col.breakMin", ColumnSpec.ColumnType.INT, 80).withPriority(3),
            ColumnSpec.of("sortNo", "shift.col.sortNo", ColumnSpec.ColumnType.INT, 60).withSort().withPriority(3),
            ColumnSpec.of("useYn", "shift.col.useYn", ColumnSpec.ColumnType.YN, 70).withPriority(2));

    public record Page(List<ShiftRow> list, Long total) {
    }

    /** 그리드(UI 단계 3 ①, 결정 docs/29 #45) — 기존 목록과 같은 조건(공장 필수) + 페이지·정렬·건수 */
    @Transactional(readOnly = true)
    public Page grid(String plantCd, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, ShiftSort.values(),
                policies.intValue(Policies.GRID_PAGE_SIZE_MAX), DEFAULT_SORT);
        String p = plantCd(plantCd);
        return new Page(mapper.findShiftPage(p, pq), pq.isWithTotal() ? mapper.countShifts(p) : null);
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    @Transactional(readOnly = true)
    public List<ShiftRow> list(String plantCd) {
        String p = plantCd(plantCd);
        return mapper.findShifts(p, false);
    }

    @Transactional
    public long create(ShiftForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("MST_SHIFT_CREATE");
        ShiftValue f = validate(in, null);
        try {
            return mapper.insertShift(f, me.loginId());
        } catch (DuplicateKeyException e) {
            throw fieldError("shiftCd", "DUPLICATE");
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Transactional
    public void update(long shiftId, ShiftForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        ShiftRow cur = mapper.findShift(shiftId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        ShiftValue f = validate(in, cur);
        try {
            if (mapper.updateShift(shiftId, f, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    /** "yyyy-MM-ddTHH:mm"(공장 현지 시각, 비면 지금) → 작업일·근무조 */
    @Transactional(readOnly = true)
    public WorkDayView resolve(String plantCd, String localTime) {
        String p = plantCd(plantCd);
        Instant at;
        if (blank(localTime)) {
            at = clock.instant();
        } else {
            FieldErrors e = FieldErrors.create();
            at = localInstant(localTime, clock.zone(p), "localTime", e);
            e.throwIfAny();
        }
        WorkDay d = resolver.resolve(p, at);
        return new WorkDayView(p, d.zone().getId(), LOCAL_MIN.format(d.local()), d.workDay(), d.shiftCd(), d.shiftNm());
    }

    /**
     * 현지 시각 글자 → 시각. 형식 오류 FORMAT, DST 로 없는 시각 DST_GAP(겹치는 시각은 앞쪽 — BizClock.toInstant).
     * 다른 모듈(설치 이력·POP)도 같은 규칙을 쓴다
     */
    public static Instant localInstant(String raw, ZoneId zone, String field, FieldErrors e) {
        try {
            LocalDateTime local = LocalDateTime.parse(raw.strip());
            Instant at = BizClock.toInstant(local, zone);
            if (at == null) {
                e.add(field, "DST_GAP");
            }
            return at;
        } catch (DateTimeParseException ex) {
            e.add(field, "FORMAT");
            return null;
        }
    }

    private String plantCd(String raw) {
        String p = blank(raw) ? null : raw.strip().toUpperCase(Locale.ROOT);
        if (p == null || p.length() > 20) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE)
                    .withFieldErrors(FieldErrors.create().add("plantCd", "REQUIRED").list());
        }
        if (mapper.plantUseYn(p) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return p;
    }

    private ShiftValue validate(ShiftForm in, ShiftRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String plant = cur != null ? cur.getPlantCd() : blank(in.plantCd()) ? null : in.plantCd().strip().toUpperCase(Locale.ROOT);
        if (cur != null && !blank(in.plantCd()) && !cur.getPlantCd().equals(in.plantCd().strip().toUpperCase(Locale.ROOT))) {
            e.add("plantCd", "FIXED");
        } else if (plant == null) {
            e.add("plantCd", "REQUIRED");
        } else {
            String use = plant.length() > 20 ? null : mapper.plantUseYn(plant);
            if (use == null) {
                e.add("plantCd", "NO_SUCH_CODE");
            } else if (cur == null && !"Y".equals(use)) {
                e.add("plantCd", "INACTIVE");
            }
        }
        String cd = cur != null ? cur.getShiftCd() : blank(in.shiftCd()) ? null : in.shiftCd().strip().toUpperCase(Locale.ROOT);
        if (cur != null && !blank(in.shiftCd()) && !cur.getShiftCd().equals(in.shiftCd().strip().toUpperCase(Locale.ROOT))) {
            e.add("shiftCd", "FIXED");
        } else if (cd == null) {
            e.add("shiftCd", "REQUIRED");
        } else if (!SHIFT_CD.matcher(cd).matches()) {
            e.add("shiftCd", "FORMAT");
        }
        String nm = text(in.shiftNm(), "shiftNm", 50, true, e);
        LocalTime start = time(in.startTime(), "startTime", e);
        LocalTime end = time(in.endTime(), "endTime", e);
        if (start != null && end != null && start.equals(end)) {
            e.add("endTime", "SAME_AS_START");
        }
        int offset = in.dayOffset() == null ? 0 : in.dayOffset();
        if (offset != 0 && offset != -1) {
            e.add("dayOffset", "CODE");
        }
        int brk = in.breakMin() == null ? 0 : in.breakMin();
        if (brk < 0 || brk > 600) {
            e.add("breakMin", "RANGE", 0, 600);
        } else if (start != null && end != null && !start.equals(end) && brk >= length(start, end)) {
            e.add("breakMin", "BREAK_TOO_LONG");
        }
        int sort = in.sortNo() == null ? 0 : in.sortNo();
        if (sort < 0 || sort > 99999) {
            e.add("sortNo", "CODE");
        }
        String use = blank(in.useYn()) ? "Y" : in.useYn();
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        }
        String remark = text(in.remark(), "remark", 500, false, e);
        if (e.isEmpty() && "Y".equals(use)) {
            // 사용 중 조끼리 겹치면 어느 조인지 알려 준다(트리거는 마지막 방어)
            for (ShiftRow o : mapper.findShifts(plant, true)) {
                if (cur != null && o.getShiftId() == cur.getShiftId()) {
                    continue;
                }
                if (overlaps(start, end, o.getStartTime(), o.getEndTime())) {
                    e.add("startTime", "SHIFT_OVERLAP", o.getShiftCd());
                    break;
                }
            }
        }
        e.throwIfAny();
        return new ShiftValue(plant, cd, nm, start, end, offset, brk, sort, use, remark, cur == null ? null : in.rowVer());
    }

    /** 근무 시간(분), 자정을 넘으면 + 1440 */
    static int length(LocalTime start, LocalTime end) {
        int s = start.getHour() * 60 + start.getMinute();
        int t = end.getHour() * 60 + end.getMinute();
        return t > s ? t - s : t - s + 1440;
    }

    /** 하루 원 위의 두 구간이 겹치는지 — 트리거 hb_guard_shift 와 같은 식 */
    static boolean overlaps(LocalTime s1, LocalTime e1, LocalTime s2, LocalTime e2) {
        int a = s1.getHour() * 60 + s1.getMinute();
        int b = a + length(s1, e1);
        int c = s2.getHour() * 60 + s2.getMinute();
        int d = c + length(s2, e2);
        for (int k = -1; k <= 1; k++) {
            if (a < d + k * 1440 && c + k * 1440 < b) {
                return true;
            }
        }
        return false;
    }

    private static LocalTime time(String v, String field, FieldErrors e) {
        if (blank(v)) {
            e.add(field, "REQUIRED");
            return null;
        }
        String s = v.strip();
        if (!HHMM.matcher(s).matches()) {
            e.add(field, "FORMAT");
            return null;
        }
        return LocalTime.parse(s);
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "ck_mst_shift_overlap" -> fieldError("startTime", "SHIFT_OVERLAP_ANY");
            case "ck_mst_shift_break_len" -> fieldError("breakMin", "BREAK_TOO_LONG");
            case "fk_mst_shift_plant" -> fieldError("plantCd", "NO_SUCH_CODE");
            default -> e;
        };
    }

    private static String text(String v, String field, int max, boolean required, FieldErrors e) {
        if (blank(v)) {
            if (required) {
                e.add(field, "REQUIRED");
            }
            return null;
        }
        String s = v.strip();
        if (s.length() > max) {
            e.add(field, "TOO_LONG", max);
        }
        return s;
    }

    static BusinessException fieldError(String field, String code) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code).list());
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
