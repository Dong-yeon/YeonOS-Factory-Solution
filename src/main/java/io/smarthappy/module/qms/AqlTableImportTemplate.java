package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.importer.ImportCodes;
import io.smarthappy.platform.importer.ImportTemplate;
import lombok.RequiredArgsConstructor;

/**
 * AQL 표 1 엑셀 올리기 (QMS_AQL_TABLE) — 표준 × 검사 수준 × 로트 구간 → 시료 문자 (docs/29 D3: 시드 없음, 회사가 올림).
 * 업무 키 = 표준|검사 수준|로트 시작. 같은 표준·수준의 사용 중 구간 겹침은 기존 행(같은 키 제외)과 파일 앞 행에 대해 OVERLAP,
 * 마지막 판정은 DB 배제 제약(커밋 시점).
 */
@Component
@RequiredArgsConstructor
public class AqlTableImportTemplate implements ImportTemplate {

    static final String CODE = "QMS_AQL_TABLE";
    static final long MAX_LOT = 1_000_000_000_000L;
    /** 천 단위 쉼표 숫자 — 쉼표가 있으면 이 모양만 받는다(hb.numbers GROUPED 와 같은 규칙, docs/29_P3 41장) */
    static final Pattern GROUPED = Pattern.compile("^[1-9]\\d{0,2}(,\\d{3})+(\\.\\d+)?$");

    private static final List<ImportColumn> COLUMNS = List.of(
            new ImportColumn("stdCd", "qms.aql.col.stdCd", true, "help.qms-sampling.stdCd", null, "ISO2859-1").asText(),
            new ImportColumn("inspLevel", "qms.aql.col.inspLevel", true, null, "INSP_LEVEL", "II").asText(),
            new ImportColumn("lotFrom", "qms.aql.col.lotFrom", true, null, null, "281"),
            new ImportColumn("lotTo", "qms.aql.col.lotTo", false, "help.qms-sampling.lotTo", null, "500"),
            new ImportColumn("codeLetter", "qms.aql.col.codeLetter", true, null, null, "H").asText(),
            new ImportColumn("useYn", "qms.aql.col.useYn", false, "import.guide.yn", null, "Y"));

    private final CodeService codes;
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
        return SamplingService.PROG;
    }

    @Override
    public String titleKey() {
        return "qms.aql.letterTitle";
    }

    @Override
    public List<ImportColumn> columns() {
        return COLUMNS;
    }

    record Range(String key, long from, Long to) {
        boolean overlaps(long f, Long t) {
            return (to == null || f <= to) && (t == null || from <= t);
        }
    }

    @Override
    public ImportSession open() {
        Map<String, Existing> existing = new HashMap<>();
        Map<String, List<Range>> active = new HashMap<>();
        jdbc.query("select aql_table_id, row_ver, std_cd, insp_level, lot_from, lot_to, code_letter, use_yn from qms_aql_table", rs -> {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("stdCd", rs.getString("std_cd"));
            v.put("inspLevel", rs.getString("insp_level"));
            v.put("lotFrom", String.valueOf(rs.getLong("lot_from")));
            long to = rs.getLong("lot_to");
            v.put("lotTo", rs.wasNull() ? null : String.valueOf(to));
            v.put("codeLetter", rs.getString("code_letter"));
            v.put("useYn", rs.getString("use_yn"));
            String key = key(v.get("stdCd"), v.get("inspLevel"), rs.getLong("lot_from"));
            existing.put(key, new Existing(rs.getLong("aql_table_id"), rs.getInt("row_ver"), v));
            if ("Y".equals(v.get("useYn"))) {
                active.computeIfAbsent(v.get("stdCd") + "|" + v.get("inspLevel"), k -> new ArrayList<>())
                        .add(new Range(key, rs.getLong("lot_from"), v.get("lotTo") == null ? null : Long.valueOf(v.get("lotTo"))));
            }
        });
        ImportCodes levels = new ImportCodes(codes, "INSP_LEVEL");
        return new ImportSession() {
            @Override
            public Existing find(String key) {
                return key == null ? null : existing.get(key);
            }

            @Override
            public Validated validate(Map<String, String> cells) {
                FieldErrors e = FieldErrors.create();
                String std = cells.get("stdCd") == null ? null : cells.get("stdCd").strip().toUpperCase(Locale.ROOT);
                if (std == null) {
                    e.add("stdCd", "REQUIRED");
                } else if (!SamplingService.STD_CD.matcher(std).matches()) {
                    e.add("stdCd", "FORMAT");
                    std = null;
                }
                String level = levels.resolve(cells.get("inspLevel"));
                if (level == null) {
                    e.add("inspLevel", "REQUIRED");
                } else if (!SamplingService.LEVELS.contains(level)) {
                    e.add("inspLevel", "CODE");
                    level = null;
                }
                Long from = whole(cells.get("lotFrom"), "lotFrom", true, e);
                Long to = whole(cells.get("lotTo"), "lotTo", false, e);
                if (from != null && to != null && to < from) {
                    e.add("lotTo", "LOT_RANGE");
                    to = null;
                }
                String letter = cells.get("codeLetter") == null ? null : cells.get("codeLetter").strip().toUpperCase(Locale.ROOT);
                if (letter == null) {
                    e.add("codeLetter", "REQUIRED");
                } else if (!letter.matches("^[A-Z]$")) {
                    e.add("codeLetter", "FORMAT");
                }
                String useYn = ImportCodes.yn(cells.get("useYn"));
                if (useYn != null && !"Y".equals(useYn) && !"N".equals(useYn)) {
                    e.add("useYn", "CODE");
                }
                String key = std != null && level != null && from != null ? key(std, level, from) : null;
                if (key != null && e.isEmpty() && !"N".equals(useYn)) {
                    // 같은 표준·수준의 사용 중 구간과 겹침 — 같은 키(이 행이 고칠 기존 행)는 뺀다. 통과한 행은 뒤 행 비교에 넣는다
                    List<Range> list = active.computeIfAbsent(std + "|" + level, k -> new ArrayList<>());
                    final Long toF = to;
                    final long fromF = from;
                    if (list.stream().anyMatch(r -> !r.key().equals(key) && r.overlaps(fromF, toF))) {
                        e.add("lotFrom", "AQL_OVERLAP");
                    } else {
                        list.removeIf(r -> r.key().equals(key));
                        list.add(new Range(key, from, to));
                    }
                }
                Map<String, String> v = new LinkedHashMap<>();
                v.put("stdCd", std);
                v.put("inspLevel", level);
                v.put("lotFrom", from == null ? null : String.valueOf(from));
                v.put("lotTo", to == null ? null : String.valueOf(to));
                v.put("codeLetter", letter);
                v.put("useYn", useYn);
                return new Validated(key, v, e.list());
            }

            @Override
            public long insert(Map<String, String> v, String by) {
                Long id = jdbc.queryForObject("""
                        insert into qms_aql_table (comp_cd, std_cd, insp_level, lot_from, lot_to, code_letter, use_yn,
                                                   created_by, updated_by)
                        values (hb_current_comp(), ?, ?, ?, ?, ?, ?, ?, ?) returning aql_table_id""", Long.class,
                        v.get("stdCd"), v.get("inspLevel"), Long.valueOf(v.get("lotFrom")), num(v.get("lotTo")), v.get("codeLetter"),
                        v.get("useYn") == null ? "Y" : v.get("useYn"), by, by);
                return id == null ? 0 : id;
            }

            @Override
            public int update(long id, int rowVer, Map<String, String> v, String by) {
                return jdbc.update("""
                        update qms_aql_table set lot_to = ?, code_letter = ?, use_yn = ?, row_ver = row_ver + 1,
                                                 updated_at = now(), updated_by = ?
                         where aql_table_id = ? and row_ver = ?""",
                        num(v.get("lotTo")), v.get("codeLetter"), v.get("useYn") == null ? "Y" : v.get("useYn"), by, id, rowVer);
            }

            @Override
            public long count() {
                Long n = jdbc.queryForObject("select count(*) from qms_aql_table", Long.class);
                return n == null ? 0 : n;
            }
        };
    }

    static String key(String std, String level, long from) {
        return std + "|" + level + "|" + from;
    }

    /** 양의 정수 칸 (엑셀 숫자 셀은 "281" 또는 "281.0", 글자 셀은 "1,000" 같은 천 단위 쉼표까지) */
    static Long whole(String raw, String field, boolean required, FieldErrors e) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                e.add(field, "REQUIRED");
            }
            return null;
        }
        String s = raw.strip();
        // 쉼표는 천 단위 자리일 때만 — 자리 확인 없이 지우면 "0,5"·"1,23" 이 5·123 으로 조용히 들어간다
        if (s.indexOf(',') >= 0 && !GROUPED.matcher(s).matches()) {
            e.add(field, "FORMAT");
            return null;
        }
        try {
            BigDecimal d = new BigDecimal(s.replace(",", ""));
            if (d.signum() <= 0) {
                e.add(field, "POSITIVE");
                return null;
            }
            if (d.stripTrailingZeros().scale() > 0) {
                e.add(field, "SCALE", 0);
                return null;
            }
            if (d.compareTo(BigDecimal.valueOf(MAX_LOT)) > 0) {
                e.add(field, "INT_DIGITS", 13);
                return null;
            }
            return d.longValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            e.add(field, "FORMAT");
            return null;
        }
    }

    private static Long num(String v) {
        return v == null ? null : Long.valueOf(v);
    }
}
