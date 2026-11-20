package io.smarthappy.module.master.workforce;

import static io.smarthappy.module.master.workforce.ShiftService.blank;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.smarthappy.module.master.workforce.WorkCalDtos.CalValue;
import io.smarthappy.module.master.workforce.WorkforceDtos.ShiftRow;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.importer.ImportTemplate;
import lombok.RequiredArgsConstructor;

/**
 * 공장 휴일 달력 엑셀 올리기 (docs/25 11.4a 1번). 키 = 날짜·공장·근무조(사용 중 행 하나 — uq_mst_work_cal), 바꿀 수 있는 것은 OFF/WORK·사유뿐
 * (화면과 같다). 검증 규칙은 화면 저장(WorkCalService.create)과 같고, 행마다 오류를 모은다. 사용 중지는 화면에서.
 */
@Component
@RequiredArgsConstructor
public class WorkCalImportTemplate implements ImportTemplate {

    static final String CODE = "MST_WORK_CAL";
    private static final int REASON_MAX = 200;

    private static final List<ImportColumn> COLUMNS = List.of(
            new ImportColumn("calDate", "workCal.col.calDate", true, "import.guide.workCalDate", null, "2026-12-25").asText(),
            new ImportColumn("plantCd", "workCal.col.plantCd", false, "import.guide.workCalPlant", null, "P1").asText(),
            new ImportColumn("shiftCd", "workCal.col.shift", false, "import.guide.workCalShift", null, null).asText(),
            new ImportColumn("dayType", "workCal.col.dayType", true, "import.guide.workCalType", null, "OFF"),
            new ImportColumn("reason", "workCal.col.reason", true, null, null, "성탄절"));

    private final WorkCalMapper mapper;
    private final WorkforceMapper workforce;
    private final JdbcTemplate jdbc;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public String progCd() {
        return WorkCalService.PROG;
    }

    @Override
    public String titleKey() {
        return "prog.mstWorkCal";
    }

    @Override
    public List<ImportColumn> columns() {
        return COLUMNS;
    }

    /** 업무 키 — 날짜|공장|근무조(빈 칸 = 회사 공통·하루 전체) */
    static String key(String calDate, String plantCd, String shiftCd) {
        return calDate + "|" + (plantCd == null ? "" : plantCd) + "|" + (shiftCd == null ? "" : shiftCd);
    }

    @Override
    public ImportSession open() {
        Map<String, Existing> existing = new HashMap<>();
        jdbc.query("""
                select cal_id, row_ver, cal_date, plant_cd, shift_cd, day_type, reason
                  from mst_work_cal where use_yn = 'Y'""", rs -> {
            String day = rs.getObject("cal_date", LocalDate.class).toString();
            Map<String, String> v = new LinkedHashMap<>();
            v.put("calDate", day);
            v.put("plantCd", rs.getString("plant_cd"));
            v.put("shiftCd", rs.getString("shift_cd"));
            v.put("dayType", rs.getString("day_type"));
            v.put("reason", rs.getString("reason"));
            existing.put(key(day, rs.getString("plant_cd"), rs.getString("shift_cd")),
                    new Existing(rs.getLong("cal_id"), rs.getInt("row_ver"), v));
        });
        Map<String, String> plantUse = new HashMap<>();
        Map<String, List<ShiftRow>> shifts = new HashMap<>();
        return new ImportSession() {
            @Override
            public Existing find(String key) {
                return key == null ? null : existing.get(key);
            }

            @Override
            public Validated validate(Map<String, String> cells) {
                FieldErrors e = FieldErrors.create();
                LocalDate day = date(cells.get("calDate"), e);
                String plant = blank(cells.get("plantCd")) ? null : cells.get("plantCd").strip().toUpperCase(Locale.ROOT);
                if (plant != null) {
                    String use = plant.length() > 20 ? null : plantUse.computeIfAbsent(plant, p -> {
                        String u = workforce.plantUseYn(p);
                        return u == null ? "" : u;
                    });
                    if (use == null || use.isEmpty()) {
                        e.add("plantCd", "NO_SUCH_CODE");
                    } else if (!"Y".equals(use)) {
                        e.add("plantCd", "INACTIVE");
                    }
                }
                String shift = blank(cells.get("shiftCd")) ? null : cells.get("shiftCd").strip().toUpperCase(Locale.ROOT);
                if (shift != null) {
                    if (plant == null) {
                        e.add("shiftCd", "SHIFT_NEEDS_PLANT");
                    } else if (e.list().stream().noneMatch(x -> "plantCd".equals(x.field()))) {
                        ShiftRow s = shifts.computeIfAbsent(plant, p -> workforce.findShifts(p, false)).stream()
                                .filter(x -> x.getShiftCd().equals(shift)).findFirst().orElse(null);
                        if (s == null) {
                            e.add("shiftCd", "NO_SUCH_CODE");
                        } else if (!"Y".equals(s.getUseYn())) {
                            e.add("shiftCd", "INACTIVE");
                        }
                    }
                }
                String type = blank(cells.get("dayType")) ? null : cells.get("dayType").strip().toUpperCase(Locale.ROOT);
                if (type == null) {
                    e.add("dayType", "REQUIRED");
                } else if (!"OFF".equals(type) && !"WORK".equals(type)) {
                    e.add("dayType", "CODE");
                }
                String reason = blank(cells.get("reason")) ? null : cells.get("reason").strip();
                if (reason == null) {
                    e.add("reason", "REQUIRED");
                } else if (reason.length() > REASON_MAX) {
                    e.add("reason", "TOO_LONG", REASON_MAX);
                }
                Map<String, String> v = new LinkedHashMap<>();
                v.put("calDate", day == null ? null : day.toString());
                v.put("plantCd", plant);
                v.put("shiftCd", shift);
                v.put("dayType", type);
                v.put("reason", reason);
                boolean keyOk = day != null && e.list().stream().noneMatch(x -> List.of("calDate", "plantCd", "shiftCd").contains(x.field()));
                return new Validated(keyOk ? key(day.toString(), plant, shift) : null, v, e.list());
            }

            @Override
            public long insert(Map<String, String> v, String by) {
                return mapper.insert(new CalValue(v.get("plantCd"), LocalDate.parse(v.get("calDate")), v.get("shiftCd"),
                        v.get("dayType"), v.get("reason")), by);
            }

            @Override
            public int update(long id, int rowVer, Map<String, String> v, String by) {
                // 대상(날짜·공장·근무조)은 키라 그대로 — OFF/WORK·사유만
                return mapper.update(id, v.get("dayType"), v.get("reason"), rowVer, by);
            }

            @Override
            public long count() {
                Long n = jdbc.queryForObject("select count(*) from mst_work_cal where use_yn = 'Y'", Long.class);
                return n == null ? 0 : n;
            }
        };
    }

    /** 화면 저장과 같은 날짜 규칙(ISO, 2000~2999년) */
    private static LocalDate date(String raw, FieldErrors e) {
        if (blank(raw)) {
            e.add("calDate", "REQUIRED");
            return null;
        }
        try {
            LocalDate d = LocalDate.parse(raw.strip());
            if (d.getYear() < 2000 || d.getYear() > 2999) {
                e.add("calDate", "FORMAT");
                return null;
            }
            return d;
        } catch (DateTimeParseException ex) {
            e.add("calDate", "FORMAT");
            return null;
        }
    }
}
