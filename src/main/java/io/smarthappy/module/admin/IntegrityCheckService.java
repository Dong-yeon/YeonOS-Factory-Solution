package io.smarthappy.module.admin;

import java.io.IOException;
import java.text.MessageFormat;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.admin.IntegrityDtos.Check;
import io.smarthappy.module.admin.IntegrityDtos.ExcelRow;
import io.smarthappy.module.admin.IntegrityDtos.Item;
import io.smarthappy.module.admin.IntegrityDtos.Run;
import io.smarthappy.module.admin.IntegrityDtos.RunRow;
import io.smarthappy.module.admin.IntegrityDtos.Status;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.esign.ESignContentSource;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.log.AuditCoverage;
import io.smarthappy.platform.log.LogDigestService;
import io.smarthappy.platform.tenant.TenantCatalogInspector;
import io.smarthappy.platform.tenant.TenantContext;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * 기록 무결성 점검 (sys-integrity, docs/52 Y1) — 기동 가드·시험이 지키는 규칙을 <b>운영 중에</b> 버튼 하나로 다시 돌린다.
 * 시장 제품이 "검증 문서 패키지" 로 파는 것을, 1인 제품은 다시 실행할 수 있는 점검으로 보인다(ALCOA+ 대응표 docs/54).
 *
 * <ol>
 * <li>DIGEST — 로그 일 다이제스트 체인({@link LogDigestService#verify()}): 과거 접속·감사 로그를 고치거나 지우면 어긋난다</li>
 * <li>LEDGER — 원장 표마다 수정·삭제 차단 트리거(꺼 두면 없는 것)·앱 계정 UPDATE/DELETE 권한 없음·두 시각 칸(카탈로그)</li>
 * <li>ESIGN — 서명 원장의 content_hash 를 대상의 지금 내용으로 다시 계산해 대조({@link ESignContentSource} 가 있는 유형만)</li>
 * <li>AUDIT — 회사별 표 중 변경 감사 트리거(hb_audit)가 없는 표(근거 있는 예외 {@link AuditCoverage#EXEMPT} 밖)</li>
 * </ol>
 * 회사 범위 안에서 돈다 — 다이제스트·서명은 RLS 로 이 회사 것만, 카탈로그(2·4)는 제품 공통 구조라 회사와 무관하다.
 * 결과는 sys_integrity_run 에 추가만(고칠 수 없음)으로 남고, 실행은 접속 로그 ACTION(FN1)·내보내기는 EXCEL 로 남는다.
 */
@Service
public class IntegrityCheckService {

    public static final String PROG = "sys-integrity";
    static final int MAX_ITEMS = 200;
    /** 한 번에 대조하는 서명 상한 — 넘으면 최근 것부터 이만큼만 보고 WARN(ESIGN_LIMIT) */
    static final int MAX_SIGNATURES = 20_000;
    static final int HISTORY = 50;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final LogDigestService digests;
    private final Map<String, ESignContentSource> sources = new HashMap<>();
    private final ExcelExportService excel;
    private final MessageSource messages;

    public IntegrityCheckService(JdbcTemplate jdbc, LogDigestService digests, List<ESignContentSource> sources,
            ExcelExportService excel, MessageSource messages) {
        this.jdbc = jdbc;
        this.digests = digests;
        this.excel = excel;
        this.messages = messages;
        for (ESignContentSource s : sources) {
            if (this.sources.put(s.targetType(), s) != null) {
                throw new IllegalStateException("서명 대상 유형의 내용 해시 계약이 둘: " + s.targetType());
            }
        }
    }

    /** 점검을 돌리고 결과를 남긴다(회사 범위 트랜잭션 하나 — 읽기 + 결과 한 행) */
    @Transactional
    public Run run() {
        List<Check> checks = List.of(digest(), ledger(), esign(), audit());
        Status status = checks.stream().map(Check::status).reduce(Status.OK, Status::worst);
        int problems = checks.stream().mapToInt(Check::problems).sum();
        String actor = TenantContext.currentActor();
        Map<String, Object> saved = jdbc.queryForMap("""
                insert into sys_integrity_run (comp_cd, status, problem_cnt, result, run_by)
                values (hb_current_comp(), ?, ?, cast(? as jsonb), ?) returning run_id, occurred_at""",
                status.name(), problems, JSON.writeValueAsString(checks), actor);
        AccessLogService.setDetail("status=" + status + ";problems=" + problems + ";"
                + String.join(";", checks.stream().map(c -> c.key() + "=" + c.problems()).toList()));
        return new Run(((Number) saved.get("run_id")).longValue(), toOffset(saved.get("occurred_at")), actor, status, checks);
    }

    private static OffsetDateTime toOffset(Object v) {
        if (v instanceof OffsetDateTime o) {
            return o;
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toInstant().atOffset(java.time.ZoneOffset.UTC);
        }
        throw new IllegalStateException("occurred_at 형식: " + v);
    }

    @Transactional(readOnly = true)
    public List<RunRow> history() {
        return jdbc.query("""
                select run_id, occurred_at, run_by, status, problem_cnt from sys_integrity_run
                 order by occurred_at desc, run_id desc limit ?""",
                (rs, i) -> new RunRow(rs.getLong(1), rs.getObject(2, OffsetDateTime.class), rs.getString(3),
                        Status.valueOf(rs.getString(4)), rs.getInt(5)), HISTORY);
    }

    /** 지난 결과 — 없거나 다른 회사 것이면 404(RLS 로 안 보인다) */
    @Transactional(readOnly = true)
    public Run detail(long runId) {
        List<Run> rows = jdbc.query("""
                select run_id, occurred_at, run_by, status, result::text from sys_integrity_run where run_id = ?""",
                (rs, i) -> new Run(rs.getLong(1), rs.getObject(2, OffsetDateTime.class), rs.getString(3),
                        Status.valueOf(rs.getString(4)), List.of(JSON.readValue(rs.getString(5), Check[].class))), runId);
        if (rows.isEmpty()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    static final List<ColumnSpec> EXCEL_COLUMNS = List.of(
            ColumnSpec.text("checkNm", "sysIntegrity.col.check", 160),
            ColumnSpec.text("subject", "sysIntegrity.col.subject", 220),
            ColumnSpec.text("status", "sysIntegrity.col.status", 80),
            ColumnSpec.text("detail", "sysIntegrity.col.detail", 420));

    /** 지난 결과를 엑셀로(점검마다 요약 줄 + 결과 줄) — 출력 정보 시트(누가·언제·몇 줄)는 공통 내보내기가 붙인다 */
    @Transactional(readOnly = true)
    public long export(long runId, Locale locale, HttpServletResponse response) throws IOException {
        Run run = detail(runId);
        List<ExcelRow> rows = new ArrayList<>();
        for (Check c : run.checks()) {
            String checkNm = messages.getMessage(CHECK_KEYS.getOrDefault(c.key(), c.key()), null, c.key(), locale);
            rows.add(new ExcelRow(checkNm, "-", c.status().name(), text(locale, "js.sysIntegrity.summary",
                    List.of(String.valueOf(c.checked()), String.valueOf(c.problems())))));
            for (Item it : c.items()) {
                rows.add(new ExcelRow(checkNm, it.subject(), it.status().name(),
                        text(locale, ITEM_KEYS.getOrDefault(it.code(), it.code()), it.args())));
            }
        }
        return excel.export(response, new ExportJob(PROG, "prog.sysIntegrity", EXCEL_COLUMNS, rows.size(), List.of("runId")),
                w -> rows.forEach(w::accept));
    }

    /** 점검·결과 코드 → 문구 키(키를 이어 붙이지 않는다 — I18nKeyGuardTest 가 쓰인 키를 찾는다) */
    static final Map<String, String> CHECK_KEYS = Map.of("DIGEST", "js.sysIntegrity.check.DIGEST",
            "LEDGER", "js.sysIntegrity.check.LEDGER", "ESIGN", "js.sysIntegrity.check.ESIGN", "AUDIT", "js.sysIntegrity.check.AUDIT");
    static final Map<String, String> ITEM_KEYS = Map.ofEntries(
            Map.entry("DIGEST_BROKEN", "js.sysIntegrity.item.DIGEST_BROKEN"),
            Map.entry("LEDGER_OK", "js.sysIntegrity.item.LEDGER_OK"),
            Map.entry("LEDGER_NO_TRIGGER", "js.sysIntegrity.item.LEDGER_NO_TRIGGER"),
            Map.entry("LEDGER_APP_CAN_MODIFY", "js.sysIntegrity.item.LEDGER_APP_CAN_MODIFY"),
            Map.entry("LEDGER_STD_COLS", "js.sysIntegrity.item.LEDGER_STD_COLS"),
            Map.entry("ESIGN_TYPE", "js.sysIntegrity.item.ESIGN_TYPE"),
            Map.entry("ESIGN_NO_SOURCE", "js.sysIntegrity.item.ESIGN_NO_SOURCE"),
            Map.entry("ESIGN_LIMIT", "js.sysIntegrity.item.ESIGN_LIMIT"),
            Map.entry("ESIGN_CHANGED", "js.sysIntegrity.item.ESIGN_CHANGED"),
            Map.entry("ESIGN_TARGET_MISSING", "js.sysIntegrity.item.ESIGN_TARGET_MISSING"),
            Map.entry("AUDIT_MISSING", "js.sysIntegrity.item.AUDIT_MISSING"),
            Map.entry("AUDIT_EXEMPT", "js.sysIntegrity.item.AUDIT_EXEMPT"));

    private String text(Locale locale, String key, List<String> args) {
        String pattern = messages.getMessage(key, null, key, locale);
        return MessageFormat.format(pattern.replace("'", "''"), args == null ? new Object[0] : args.toArray());
    }

    // ------------------------------------------------------------------ 1. 다이제스트 체인

    Check digest() {
        Integer days = jdbc.queryForObject("select count(*) from sys_log_digest", Integer.class);
        List<Item> items = new ArrayList<>();
        for (String broken : digests.verify()) {
            int at = broken.indexOf(':');
            items.add(new Item(broken, Status.FAIL, "DIGEST_BROKEN", List.of(broken.substring(0, at), broken.substring(at + 1))));
        }
        return check("DIGEST", days == null ? 0 : days, items);
    }

    // ------------------------------------------------------------------ 2. 원장 보호

    Check ledger() {
        List<TenantCatalogInspector.Ledger> ledgers = new TenantCatalogInspector(jdbc).ledgers();
        List<Item> items = new ArrayList<>();
        for (TenantCatalogInspector.Ledger t : ledgers) {
            if (!t.guarded()) {
                items.add(new Item(t.table(), Status.FAIL, "LEDGER_NO_TRIGGER", List.of()));
            }
            if (t.mutable()) {
                items.add(new Item(t.table(), Status.FAIL, "LEDGER_APP_CAN_MODIFY", List.of()));
            }
            if (t.stdCols() < 3) {
                items.add(new Item(t.table(), Status.FAIL, "LEDGER_STD_COLS", List.of()));
            }
            if (t.ok()) {
                items.add(new Item(t.table(), Status.OK, "LEDGER_OK", List.of()));
            }
        }
        return check("LEDGER", ledgers.size(), items);
    }

    // ------------------------------------------------------------------ 3. 전자서명 해시

    private record Sig(long esignId, String type, long targetId, String hash, String signer, String meaning, OffsetDateTime at) {
    }

    Check esign() {
        Integer total = jdbc.queryForObject("select count(*) from sys_esign", Integer.class);
        List<Sig> sigs = jdbc.query("""
                select esign_id, target_type, target_id, content_hash, signer_nm, meaning_cd, signed_at
                  from sys_esign order by esign_id desc limit ?""",
                (rs, i) -> new Sig(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getObject(7, OffsetDateTime.class)), MAX_SIGNATURES);
        Map<String, Optional<Set<String>>> cache = new HashMap<>();
        Map<String, int[]> byType = new TreeMap<>();   // 유형 → [일치, 바뀜, 대상 없음, 계약 없음]
        List<Item> items = new ArrayList<>();
        for (Sig s : sigs) {
            int[] n = byType.computeIfAbsent(s.type(), k -> new int[4]);
            ESignContentSource src = sources.get(s.type());
            if (src == null) {
                n[3]++;
                continue;
            }
            Optional<Set<String>> now = cache.computeIfAbsent(s.type() + ":" + s.targetId(), k -> src.currentHashes(s.targetId()));
            String subject = s.type() + ":" + s.targetId();
            List<String> args = List.of(s.meaning(), s.signer(), String.valueOf(s.at()), String.valueOf(s.esignId()));
            if (now.isEmpty()) {
                n[2]++;
                items.add(new Item(subject, Status.WARN, "ESIGN_TARGET_MISSING", args));
            } else if (now.get().contains(s.hash())) {
                n[0]++;
            } else {
                n[1]++;
                items.add(new Item(subject, Status.WARN, "ESIGN_CHANGED", args));
            }
        }
        // 유형별 합계 줄 — 계약 없는 유형은 대조하지 못했다고 알린다(WARN)
        List<Item> all = new ArrayList<>();
        byType.forEach((type, n) -> all.add(new Item(type, n[3] > 0 ? Status.WARN : Status.OK,
                n[3] > 0 ? "ESIGN_NO_SOURCE" : "ESIGN_TYPE", List.of(String.valueOf(n[0]), String.valueOf(n[1]),
                        String.valueOf(n[2]), String.valueOf(n[3])))));
        if (total != null && total > sigs.size()) {
            all.add(new Item("sys_esign", Status.WARN, "ESIGN_LIMIT", List.of(String.valueOf(sigs.size()), String.valueOf(total))));
        }
        all.addAll(items);
        return check("ESIGN", total == null ? 0 : total, all);
    }

    // ------------------------------------------------------------------ 4. 감사 트리거

    Check audit() {
        List<String> noAudit = jdbc.queryForList("""
                select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'public' and c.relkind in ('r', 'p')
                   and exists (select 1 from pg_attribute a where a.attrelid = c.oid and a.attname = 'comp_cd' and not a.attisdropped)
                   and not exists (select 1 from pg_trigger t join pg_proc p on p.oid = t.tgfoid
                                    where t.tgrelid = c.oid and not t.tgisinternal and t.tgenabled <> 'D' and p.proname = 'hb_audit')
                 order by c.relname""", String.class);
        Integer all = jdbc.queryForObject("""
                select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'public' and c.relkind in ('r', 'p')
                   and exists (select 1 from pg_attribute a where a.attrelid = c.oid and a.attname = 'comp_cd' and not a.attisdropped)""",
                Integer.class);
        List<Item> items = new ArrayList<>();
        Set<String> missing = new TreeSet<>(noAudit);
        missing.removeAll(AuditCoverage.EXEMPT);
        missing.forEach(t -> items.add(new Item(t, Status.FAIL, "AUDIT_MISSING", List.of())));
        items.add(new Item("-", Status.OK, "AUDIT_EXEMPT", List.of(String.valueOf(noAudit.size() - missing.size()))));
        return check("AUDIT", all == null ? 0 : all, items);
    }

    /** 문제(나쁜 것)를 앞으로, 줄 상한 */
    private static Check check(String key, int checked, List<Item> items) {
        List<Item> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing((Item i) -> -i.status().ordinal()));
        Status status = sorted.stream().map(Item::status).reduce(Status.OK, Status::worst);
        int problems = (int) sorted.stream().filter(i -> i.status() != Status.OK).count();
        int more = Math.max(0, sorted.size() - MAX_ITEMS);
        return new Check(key, status, checked, problems, more > 0 ? List.copyOf(sorted.subList(0, MAX_ITEMS)) : sorted, more);
    }
}
