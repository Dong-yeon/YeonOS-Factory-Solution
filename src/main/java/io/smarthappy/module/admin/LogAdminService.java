package io.smarthappy.module.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.admin.LogDtos.AccessRow;
import io.smarthappy.module.admin.LogDtos.AccessSearch;
import io.smarthappy.module.admin.LogDtos.AuditChange;
import io.smarthappy.module.admin.LogDtos.AuditDetail;
import io.smarthappy.module.admin.LogDtos.AuditRow;
import io.smarthappy.module.admin.LogDtos.AuditSearch;
import io.smarthappy.module.admin.LogDtos.AuditTable;
import io.smarthappy.module.admin.LogDtos.TimeSort;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.AuditViewService;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 접속·보안 로그(sys-access-log)·변경 이력 모아 보기(sys-audit-log) — docs/36 12장.
 * 회사 관리자는 자기 회사 것만(RLS). 감사는 **볼 수 있는 화면의 표만**(Q17): 표 → 화면 대응이 있으면 그 화면 SEARCH,
 * 없으면 관리자 그룹만 — 감사 행은 그 표의 값 전부라 화면 권한을 우회하지 않게.
 * 기간은 회사 날짜(BizClock) → [시작, 끝+1일) 시각, 상한 = min(화면 92일, 정책 SEARCH_MAX_RANGE_DAYS).
 */
@Service
@RequiredArgsConstructor
public class LogAdminService {

    static final String ACCESS_PROG = "sys-access-log";
    static final String AUDIT_PROG = "sys-audit-log";
    static final int MAX_DAYS = 92;
    static final List<String> DEFAULT_SORT = List.of("occurredAt,desc");
    static final Set<String> SEVERITIES = Set.of("INFO", "WARN", "ALERT");
    static final Set<String> RESULTS = Set.of("OK", "DENIED", "FAIL");
    /** 접속 로그 단말 종류 — 단말 요청은 서버가 아는 등록 유형(POP/PDA), 그 밖은 헤더 참고값·요청 밖 SYSTEM (docs/50 Q9) */
    static final List<String> CLIENT_TYPES = List.of("WEB", "POP", "PDA", "API", "SYSTEM");
    static final Set<String> ACTIONS = Set.of("INSERT", "UPDATE", "DELETE");
    /** "권한 변경" 빠른 조건 — 접속 로그 이벤트 */
    static final List<String> GRANT_EVENTS = List.of(AccessEvent.GRANT_DENIED.name(), AccessEvent.PASSWORD_RESET.name(),
            AccessEvent.USER_LOCKED.name(), AccessEvent.USER_UNLOCKED.name());
    /** "권한 변경" 빠른 조건 — 감사 표 (소속·권한 표·그룹·사용자 잠금/사용/초기화) */
    static final List<String> GRANT_TABLES = List.of("sys_user_auth", "sys_auth_prog", "sys_auth_group", "sys_user");
    /** 감사 바뀐 칸에서 뺄 잡음 */
    static final Set<String> NOISE = Set.of("row_ver", "updated_at", "updated_by");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> EVENTS = Arrays.stream(AccessEvent.values()).map(Enum::name)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    static final List<ColumnSpec> ACCESS_COLUMNS = List.of(
            ColumnSpec.of("occurredAt", "sysLog.col.occurredAt", ColumnSpec.ColumnType.DATETIME, 140).withPriority(1),
            ColumnSpec.code("severity", "sysLog.col.severity", "LOG_SEVERITY", 70).withPriority(1),
            ColumnSpec.code("eventCd", "sysLog.col.eventCd", "ACCESS_EVENT", 140).withPriority(1),
            ColumnSpec.code("resultCd", "sysLog.col.resultCd", "LOG_RESULT", 70),
            ColumnSpec.text("loginId", "sysLog.col.loginId", 110).noExport(),
            ColumnSpec.code("userType", "sysUser.col.userType", "USER_TYPE", 90).withPriority(2),
            ColumnSpec.text("progNm", "sysLog.col.progNm", 140).withPriority(2),
            ColumnSpec.text("actionCd", "sysLog.col.actionCd", 70).withPriority(3),
            ColumnSpec.labels("clientType", "sysLog.col.clientType", "clientType.", 70, CLIENT_TYPES.toArray(String[]::new)).withPriority(3),
            ColumnSpec.text("ip", "sysLog.col.ip", 110).withPriority(3).noExport(),
            ColumnSpec.text("detail", "sysLog.col.detail", 220).withFlex().withPriority(2));

    static final List<ColumnSpec> AUDIT_COLUMNS = List.of(
            ColumnSpec.of("occurredAt", "sysLog.col.occurredAt", ColumnSpec.ColumnType.DATETIME, 140).withPriority(1),
            ColumnSpec.text("tableProgNm", "sysAudit.col.screen", 140).withPriority(1),
            ColumnSpec.text("tableNm", "sysAudit.col.tableNm", 140).withPriority(3),
            ColumnSpec.code("action", "sysAudit.col.action", "AUDIT_ACTION", 70).withPriority(1),
            ColumnSpec.text("rowKey", "sysAudit.col.rowKey", 120).withPriority(2),
            ColumnSpec.text("actor", "sysAudit.col.actor", 160).withFlex());

    public record Page<T>(List<T> list, Long total) {
    }

    private final LogMapper mapper;
    private final AdminMapper admins;
    private final GrantGuard guard;
    private final PermissionService permissions;
    private final DateRanges ranges;
    private final BizClock clock;
    private final PolicyService policies;
    private final MessageSource messages;

    // ------------------------------------------------------------------ 접속·보안 로그

    @Transactional(readOnly = true)
    public Page<AccessRow> access(LocalDate from, LocalDate to, String eventCd, boolean grantOnly, String severity,
            String resultCd, String loginId, String ip, String userType, String clientType, Integer page, Integer size, Boolean total,
            Locale locale) {
        Instant[] t = period(from, to);
        if ((!blank(eventCd) && !EVENTS.contains(eventCd)) || (!blank(severity) && !SEVERITIES.contains(severity))
                || (!blank(resultCd) && !RESULTS.contains(resultCd)) || (!blank(loginId) && loginId.strip().length() > 50)
                || (!blank(ip) && ip.strip().length() > 45)
                || (!blank(userType) && !UserAdminService.USER_TYPES.contains(userType))
                || (!blank(clientType) && !CLIENT_TYPES.contains(clientType))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        List<String> events = grantOnly ? (blank(eventCd) ? GRANT_EVENTS
                : GRANT_EVENTS.contains(eventCd) ? List.of(eventCd) : List.of("-")) : blank(eventCd) ? List.of() : List.of(eventCd);
        PageQuery pq = page(page, size, total);
        AccessSearch q = new AccessSearch(t[0], t[1], events, blank(severity) ? null : severity,
                blank(resultCd) ? null : resultCd, blank(loginId) ? null : "%" + likeEscape(loginId.strip()) + "%",
                blank(ip) ? null : likeEscape(ip.strip()) + "%", blank(userType) ? null : userType,
                blank(clientType) ? null : clientType, pq);
        List<AccessRow> rows = mapper.findAccess(q);
        rows.forEach(r -> r.setProgNm(r.getProgNm() == null ? r.getProgCd()
                : messages.getMessage(r.getProgNm(), null, r.getProgCd(), locale)));
        return new Page<>(rows, pq.isWithTotal() ? mapper.countAccess(q) : null);
    }

    public List<ColumnSpec> accessColumns() {
        return ACCESS_COLUMNS;
    }

    // ------------------------------------------------------------------ 변경 이력

    /** 볼 수 있는 표 — 관리자면 null(전부) */
    Set<String> visibleTables() {
        if (admins.isActiveAdmin(guard.me().userId())) {
            return null;
        }
        Set<String> out = new TreeSet<>();
        for (String table : AuditViewService.tables()) {
            if (permissions.actions(AuditViewService.progOf(table)).contains(Action.SEARCH)) {
                out.add(table);
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<AuditTable> auditTables(Locale locale) {
        Set<String> visible = visibleTables();
        Map<String, String> names = progNames(locale);
        List<AuditTable> out = new ArrayList<>();
        for (String t : mapper.auditTablesPresent()) {
            if (visible == null || visible.contains(t)) {
                String prog = AuditViewService.progOf(t);
                out.add(new AuditTable(t, prog, prog == null ? null : names.get(prog)));
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Page<AuditRow> audit(LocalDate from, LocalDate to, String tableNm, boolean grantOnly, String action,
            String actor, Integer page, Integer size, Boolean total, Locale locale) {
        Instant[] t = period(from, to);
        if ((!blank(tableNm) && !tableNm.matches("^[a-z][a-z0-9_]{1,62}$")) || (!blank(action) && !ACTIONS.contains(action))
                || (!blank(actor) && actor.strip().length() > 80)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        Set<String> visible = visibleTables();
        Set<String> tables = visible == null ? null : new LinkedHashSet<>(visible);
        if (!blank(tableNm)) {
            if (visible != null && !visible.contains(tableNm)) {
                throw new BusinessException(HbErrorCode.NO_PERMISSION);
            }
            tables = new LinkedHashSet<>(List.of(tableNm));
        }
        if (grantOnly) {
            Set<String> g = new LinkedHashSet<>(GRANT_TABLES);
            if (tables != null) {
                g.retainAll(tables);
            }
            tables = g;
        }
        PageQuery pq = page(page, size, total);
        if (tables != null && tables.isEmpty()) {
            return new Page<>(List.of(), pq.isWithTotal() ? 0L : null);
        }
        AuditSearch q = new AuditSearch(t[0], t[1], tables == null ? null : List.copyOf(tables),
                blank(action) ? null : action, blank(actor) ? null : "%" + likeEscape(actor.strip()) + "%", pq);
        List<AuditRow> rows = mapper.findAudit(q);
        Map<String, String> names = progNames(locale);
        for (AuditRow r : rows) {
            r.setTableProg(AuditViewService.progOf(r.getTableNm()));
            String nm = r.getTableProg() == null ? null : names.get(r.getTableProg());
            r.setTableProgNm(nm == null ? r.getTableNm() : nm);
        }
        return new Page<>(rows, pq.isWithTotal() ? mapper.countAudit(q) : null);
    }

    /** 한 행의 바뀐 칸. 볼 수 없는 표·다른 회사·없는 행은 404(존재를 알리지 않는다) */
    @Transactional(readOnly = true)
    public AuditDetail auditDetail(long auditId, Locale locale) {
        Map<String, Object> row = mapper.findAuditOne(auditId);
        if (row == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        String table = (String) row.get("tableNm");
        Set<String> visible = visibleTables();
        if (visible != null && !visible.contains(table)) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        String prog = AuditViewService.progOf(table);
        String nm = prog == null ? null : progNames(locale).get(prog);
        Object at = row.get("occurredAt");   // map 결과의 timestamptz 는 드라이버 기본형(Timestamp)
        OffsetDateTime occurred = at instanceof java.sql.Timestamp ts ? ts.toInstant().atOffset(java.time.ZoneOffset.UTC)
                : (OffsetDateTime) at;
        return new AuditDetail(auditId, occurred, table, nm == null ? table : nm,
                (String) row.get("rowKey"), (String) row.get("action"), (String) row.get("actor"),
                changes((String) row.get("action"), (String) row.get("before"), (String) row.get("after")));
    }

    /** UPDATE = 값이 다른 칸(잡음 칸 제외), INSERT·DELETE = 값이 있는 칸 전부. 가린 칸은 (changed) 그대로 */
    static List<AuditChange> changes(String action, String beforeJson, String afterJson) {
        JsonNode before = beforeJson == null ? null : JSON.readTree(beforeJson);
        JsonNode after = afterJson == null ? null : JSON.readTree(afterJson);
        Set<String> cols = new TreeSet<>();
        addNames(before, cols);
        addNames(after, cols);
        List<AuditChange> out = new ArrayList<>();
        for (String c : cols) {
            String b = text(before, c);
            String a = text(after, c);
            if ("UPDATE".equals(action) && (NOISE.contains(c) || java.util.Objects.equals(a, b))) {
                continue;
            }
            if (!"UPDATE".equals(action) && a == null && b == null) {
                continue;
            }
            out.add(new AuditChange(c, b, a));
        }
        return out;
    }

    private static void addNames(JsonNode n, Set<String> out) {
        if (n != null && n.isObject()) {
            for (Iterator<String> it = n.propertyNames().iterator(); it.hasNext(); ) {
                out.add(it.next());
            }
        }
    }

    private static String text(JsonNode n, String col) {
        if (n == null || !n.has(col) || n.get(col).isNull()) {
            return null;
        }
        JsonNode v = n.get(col);
        return v.isString() ? v.asString() : v.toString();
    }

    public List<ColumnSpec> auditColumns() {
        return AUDIT_COLUMNS;
    }

    // ------------------------------------------------------------------ 공용

    Instant[] period(LocalDate from, LocalDate to) {
        ranges.check(from, to, MAX_DAYS);
        ZoneId zone = clock.zone();
        return new Instant[] {from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant()};
    }

    PageQuery page(Integer page, Integer size, Boolean total) {
        return PageQuery.parse(page, size, null, total, TimeSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                DEFAULT_SORT);
    }

    /** 화면 코드 → 번역된 이름 (한 요청에 한 번 읽는다) */
    Map<String, String> progNames(Locale locale) {
        Map<String, String> out = new java.util.HashMap<>();
        admins.findProgs().forEach(p -> out.put(p.getProgCd(), messages.getMessage(p.getProgNmKey(), null, p.getProgCd(), locale)));
        return out;
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
