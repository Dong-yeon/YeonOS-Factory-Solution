package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.importer.ImportCodes;
import io.smarthappy.platform.importer.ImportTemplate;
import lombok.RequiredArgsConstructor;

/**
 * AQL 표 2 엑셀 올리기 (QMS_AQL_PLAN) — 표준 × 엄격도 × 시료 문자 × AQL → n · Ac · Re (docs/29 D3).
 * 업무 키 = 표준|엄격도|문자|AQL(수치 정규화 — 0.65 와 0.650 은 같은 줄). 화살표는 올리는 쪽이 풀어서 넣는다.
 * 1회 샘플링: Re > Ac, Re ≤ n, Re = Ac + 1(수월한 검사만 간격 허용) — DB CHECK 와 같은 규칙을 행 오류로 먼저.
 */
@Component
@RequiredArgsConstructor
public class AqlPlanImportTemplate implements ImportTemplate {

    static final String CODE = "QMS_AQL_PLAN";

    private static final List<ImportColumn> COLUMNS = List.of(
            new ImportColumn("stdCd", "qms.aql.col.stdCd", true, "help.qms-sampling.stdCd", null, "ISO2859-1").asText(),
            new ImportColumn("severity", "qms.aql.col.severity", true, null, "AQL_SEVERITY", "NORMAL").asText(),
            new ImportColumn("codeLetter", "qms.aql.col.codeLetter", true, null, null, "H").asText(),
            new ImportColumn("aql", "qms.aql.col.aql", true, "help.qms-sampling.aql", null, "0.65"),
            new ImportColumn("sampleSize", "qms.aql.col.sampleSize", true, null, null, "50"),
            new ImportColumn("ac", "qms.aql.col.ac", true, null, null, "1"),
            new ImportColumn("re", "qms.aql.col.re", true, null, null, "2"),
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
        return "qms.aql.planTitle";
    }

    @Override
    public List<ImportColumn> columns() {
        return COLUMNS;
    }

    @Override
    public ImportSession open() {
        Map<String, Existing> existing = new HashMap<>();
        jdbc.query("select aql_row_id, row_ver, std_cd, severity, code_letter, aql, sample_size, ac, re, use_yn from qms_aql_plan_row",
                rs -> {
                    Map<String, String> v = new LinkedHashMap<>();
                    v.put("stdCd", rs.getString("std_cd"));
                    v.put("severity", rs.getString("severity"));
                    v.put("codeLetter", rs.getString("code_letter"));
                    v.put("aql", plain(rs.getBigDecimal("aql")));
                    v.put("sampleSize", String.valueOf(rs.getInt("sample_size")));
                    v.put("ac", String.valueOf(rs.getInt("ac")));
                    v.put("re", String.valueOf(rs.getInt("re")));
                    v.put("useYn", rs.getString("use_yn"));
                    existing.put(key(v.get("stdCd"), v.get("severity"), v.get("codeLetter"), v.get("aql")),
                            new Existing(rs.getLong("aql_row_id"), rs.getInt("row_ver"), v));
                });
        ImportCodes severities = new ImportCodes(codes, "AQL_SEVERITY");
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
                String severity = severities.resolve(cells.get("severity"));
                if (severity == null) {
                    e.add("severity", "REQUIRED");
                } else if (!SamplingService.SEVERITIES.contains(severity)) {
                    e.add("severity", "CODE");
                    severity = null;
                }
                String letter = cells.get("codeLetter") == null ? null : cells.get("codeLetter").strip().toUpperCase(Locale.ROOT);
                if (letter == null) {
                    e.add("codeLetter", "REQUIRED");
                } else if (!letter.matches("^[A-Z]$")) {
                    e.add("codeLetter", "FORMAT");
                    letter = null;
                }
                BigDecimal aql = BomService.decimal(cells.get("aql"), "aql", 3, 4, true, e);
                if (cells.get("aql") == null || cells.get("aql").isBlank()) {
                    e.add("aql", "REQUIRED");
                } else if (aql != null && aql.compareTo(BigDecimal.valueOf(1000)) > 0) {
                    e.add("aql", "RANGE", "0.001", "1000");
                    aql = null;
                }
                Long n = AqlTableImportTemplate.whole(cells.get("sampleSize"), "sampleSize", true, e);
                if (n != null && n > SamplingService.MAX_N) {
                    e.add("sampleSize", "RANGE", 1, SamplingService.MAX_N);
                    n = null;
                }
                Long ac = nonNegative(cells.get("ac"), "ac", e);
                Long re = nonNegative(cells.get("re"), "re", e);
                if (ac != null && re != null) {
                    if (re <= ac || (n != null && re > n)) {
                        e.add("re", "AC_RE");
                    } else if (re != ac + 1 && severity != null && !"REDUCED".equals(severity)) {
                        e.add("re", "AC_RE_GAP");
                    }
                }
                String useYn = ImportCodes.yn(cells.get("useYn"));
                if (useYn != null && !"Y".equals(useYn) && !"N".equals(useYn)) {
                    e.add("useYn", "CODE");
                }
                String aqlText = aql == null ? null : plain(aql);
                String key = std != null && severity != null && letter != null && aqlText != null ? key(std, severity, letter, aqlText)
                        : null;
                Map<String, String> v = new LinkedHashMap<>();
                v.put("stdCd", std);
                v.put("severity", severity);
                v.put("codeLetter", letter);
                v.put("aql", aqlText);
                v.put("sampleSize", n == null ? null : String.valueOf(n));
                v.put("ac", ac == null ? null : String.valueOf(ac));
                v.put("re", re == null ? null : String.valueOf(re));
                v.put("useYn", useYn);
                return new Validated(key, v, e.list());
            }

            @Override
            public long insert(Map<String, String> v, String by) {
                Long id = jdbc.queryForObject("""
                        insert into qms_aql_plan_row (comp_cd, std_cd, severity, code_letter, aql, sample_size, ac, re, use_yn,
                                                      created_by, updated_by)
                        values (hb_current_comp(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning aql_row_id""", Long.class,
                        v.get("stdCd"), v.get("severity"), v.get("codeLetter"), new BigDecimal(v.get("aql")),
                        Integer.valueOf(v.get("sampleSize")), Integer.valueOf(v.get("ac")), Integer.valueOf(v.get("re")),
                        v.get("useYn") == null ? "Y" : v.get("useYn"), by, by);
                return id == null ? 0 : id;
            }

            @Override
            public int update(long id, int rowVer, Map<String, String> v, String by) {
                return jdbc.update("""
                        update qms_aql_plan_row set sample_size = ?, ac = ?, re = ?, use_yn = ?, row_ver = row_ver + 1,
                                                    updated_at = now(), updated_by = ?
                         where aql_row_id = ? and row_ver = ?""",
                        Integer.valueOf(v.get("sampleSize")), Integer.valueOf(v.get("ac")), Integer.valueOf(v.get("re")),
                        v.get("useYn") == null ? "Y" : v.get("useYn"), by, id, rowVer);
            }

            @Override
            public long count() {
                Long c = jdbc.queryForObject("select count(*) from qms_aql_plan_row", Long.class);
                return c == null ? 0 : c;
            }
        };
    }

    static String key(String std, String severity, String letter, String aql) {
        return std + "|" + severity + "|" + letter + "|" + aql;
    }

    static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    /** 0 이상 정수 (Ac·Re) */
    private static Long nonNegative(String raw, String field, FieldErrors e) {
        if (raw == null || raw.isBlank()) {
            e.add(field, "REQUIRED");
            return null;
        }
        try {
            BigDecimal d = new BigDecimal(raw.strip());
            if (d.signum() < 0) {
                e.add(field, "NOT_NEGATIVE");
                return null;
            }
            if (d.stripTrailingZeros().scale() > 0 || d.compareTo(BigDecimal.valueOf(SamplingService.MAX_N)) > 0) {
                e.add(field, "FORMAT");
                return null;
            }
            return d.longValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            e.add(field, "FORMAT");
            return null;
        }
    }
}
