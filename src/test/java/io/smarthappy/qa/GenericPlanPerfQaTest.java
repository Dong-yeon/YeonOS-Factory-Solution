package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import javax.sql.DataSource;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariDataSource;

import io.smarthappy.module.admin.LogDtos;
import io.smarthappy.module.admin.LogMapper;
import io.smarthappy.module.cms.CmsAlarmDtos;
import io.smarthappy.module.cms.CmsAlarmMapper;
import io.smarthappy.module.production.LotDtos;
import io.smarthappy.module.production.LotMapper;
import io.smarthappy.module.srm.notify.NotifyDtos;
import io.smarthappy.module.srm.notify.NotifyMapper;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA 실측 — 준비 문장의 일반 계획(generic plan). WMS 수불 원장(#492, docs/30 5.1)에서 limit·offset 이 바인드 값이라 같은 문장을 여러 번
 * 쓰면 일반 계획으로 바뀌어 깊은 쪽이 느려졌다. 같은 일이 다른 큰 목록(QA offset 조사 H1·H2·M4, 대조 = 조인만 있는 접속 로그)에도 있는지,
 * 그리고 모듈이 지연 조인으로 고친 뒤(H1·H2 를 WMS 모양으로 쓴 SQL)에도 있는지 본다.
 * <ul>
 * <li>한 연결(한 트랜잭션)에서 같은 쪽을 12번 부르고 호출마다 시간을 적는다. 끝에 {@code pg_prepared_statements} 의 일반·custom 계획 수</li>
 * <li>드라이버(pgjdbc) 기본 prepareThreshold = 5 → 5번째부터 이름 있는 서버 준비 문장, 서버는 그 뒤 custom 5번 후 일반 계획을 견준다</li>
 * <li>장면: 깊은 쪽만 / 1쪽만 / 1쪽 10번 뒤 깊은 쪽 / {@code plan_cache_mode = force_custom_plan}(WMS 방식). 장면마다 연결 풀을 비워 새 연결</li>
 * <li>{@code EXPLAIN (GENERIC_PLAN)}(PG16) 으로 일반 계획 모양 — 매퍼 문장은 MyBatis BoundSql 의 ? 를 $n 으로</li>
 * <li>적재는 소유자 연결 + session_replication_role = replica(트리거·FK 끔) — 측정과 무관한 검사라서. 다른 회사 행을 섞어 RLS 가 거르게</li>
 * </ul>
 * 실행: ./gradlew test --tests '*GenericPlanPerfQaTest*' -Dhb.perf=true (평소 check 에서는 건너뜀). 기록은 docs/qa/2026-10-24_generic-plan.md
 */
class GenericPlanPerfQaTest extends PostgresTestBase {

    static final int SIZE = 50;
    static final int CALLS = 12;
    static final int ROWS = 200_000;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    SqlSessionFactory sessions;

    @Autowired
    LotMapper lots;

    @Autowired
    CmsAlarmMapper alarms;

    @Autowired
    NotifyMapper notices;

    @Autowired
    LogMapper logs;

    /** 한 목록: 이름, 쪽 → 호출, (MyBatis 문장 id + 파라미터 — EXPLAIN 용, 지연 조인 SQL 이면 null), 준비 문장을 찾을 글자 */
    record Probe(String name, IntFunction<List<?>> call, IntFunction<Object[]> stmt, String marker, int deepPage) {
    }

    @Test
    @EnabledIfSystemProperty(named = "hb.perf", matches = "true")
    void 일반_계획_큰_목록() throws Exception {
        String comp = accounts.newComp("GP");
        String noise = accounts.newComp("GN");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long t0 = System.nanoTime();
        seed(comp, now, ROWS);
        seed(noise, now, ROWS / 4);
        for (String t : new String[] {"lot_master", "cms_alarm_log", "sys_outbox", "sys_access_log", "mst_equip", "mst_item"}) {
            owner("analyze " + t);
        }
        StringBuilder out = new StringBuilder(String.format("%n[perf] 일반 계획 — 목록마다 %,d행(+다른 회사 %,d), 50행 쪽, 같은 쪽 %d번 연속(ms)"
                + " — 적재 %,dms%n", ROWS, ROWS / 4, CALLS, (System.nanoTime() - t0) / 1_000_000));

        LocalDate today = now.toLocalDate();
        OffsetDateTime aFrom = now.minusDays(91);
        OffsetDateTime aTo = now.plusDays(1);
        int last = (ROWS + SIZE - 1) / SIZE;
        List<Probe> probes = List.of(
                new Probe("H1 LOT findLotPage(지금)", p -> lots.findLotPage(today.minusDays(365), today, "Asia/Seoul", null, null, null, null,
                        false, page(p, LotDtos.LotSort.values(), "createdAt,desc")), p -> lotStmt(today, p), "from lot_master m", last / 2),
                new Probe("H2 알람 findAlarmPage(지금)", p -> alarms.findAlarmPage("P1", aFrom, aTo, null, false, 24,
                        page(p, CmsAlarmDtos.AlarmSort.values(), "raisedAt,desc")), p -> alarmStmt(aFrom, aTo, p), "from cms_alarm_log l", last / 2),
                new Probe("M4 SRM 통지 findNoticePage(지금)", p -> notices.findNoticePage(new NotifyDtos.NoticeCrit(now.minusDays(365), aTo, null,
                        null), page(p, NotifyDtos.NoticeSort.values(), "createdAt,desc")), p -> noticeStmt(now, aTo, p), "from sys_outbox o", last / 2),
                new Probe("대조 접속 로그 findAccess(조인만)", p -> logs.findAccess(new LogDtos.AccessSearch(aFrom.toInstant(), aTo.toInstant(),
                        List.of(), null, null, null, null, null, page(p, LogDtos.TimeSort.values(), "occurredAt,desc"))),
                        p -> accessStmt(aFrom.toInstant(), aTo.toInstant(), p), "from sys_access_log a", last),
                new Probe("H1 LOT 지연 조인(고친 뒤 모양)", p -> jdbc.queryForList(LOT_LATE, Long.class, aFrom.minusDays(274), aTo, SIZE,
                        (long) (p - 1) * SIZE), p -> new Object[] {LOT_LATE}, "from lot_master m where", last),
                new Probe("H2 알람 지연 조인(고친 뒤 모양)", p -> jdbc.queryForList(ALARM_LATE, Long.class, "P1", aFrom, aTo, SIZE,
                        (long) (p - 1) * SIZE, 24), p -> new Object[] {ALARM_LATE}, "join mst_equip e on e.equip_id = l.equip_id where", last));

        for (Probe pr : probes) {
            out.append(String.format("%n%s — 깊은 쪽 %,d (offset %,d)%n", pr.name, pr.deepPage, (long) (pr.deepPage - 1) * SIZE));
            assertThat(tenant(comp, () -> pr.call.apply(1))).as(pr.name + " 1쪽").hasSize(SIZE);
            assertThat(tenant(comp, () -> pr.call.apply(pr.deepPage))).as(pr.name + " 깊은 쪽").isNotEmpty();
            out.append("  깊은 쪽 ×12          ").append(series(comp, pr, List.of(), pr.deepPage, CALLS, false)).append('\n');
            out.append("  깊은 쪽 ×12 custom   ").append(series(comp, pr, List.of(), pr.deepPage, CALLS, true)).append('\n');
            out.append("  1쪽 ×12              ").append(series(comp, pr, List.of(), 1, CALLS, false)).append('\n');
            out.append("  1쪽 ×10 → 깊은 쪽 ×3 ").append(series(comp, pr, List.of(1, 1, 1, 1, 1, 1, 1, 1, 1, 1), pr.deepPage, 3, false)).append('\n');
            out.append("  깊은 쪽 ×10 → 1쪽 ×3 ").append(series(comp, pr, rep(pr.deepPage, 10), 1, 3, false)).append('\n');
            out.append("  일반 계획(EXPLAIN GENERIC_PLAN): ").append(genericPlan(comp, pr)).append('\n');
            System.out.println(out);
        }
        System.out.println(out);
    }

    /**
     * 플랫폼 공통안의 득실 — 같은 문장을 세 연결 방식으로: ① 지금(prepareThreshold 5, plan_cache_mode auto) ② 세션 force_custom_plan
     * (= 역할 설정 ALTER ROLE … SET 또는 TenantAwareTransactionManager 의 set_config 와 같은 효과) ③ 드라이버 prepareThreshold=0(서버 준비 문장 안 씀).
     * 짧은 문장(기본키 한 행 · 이름 있는 조회)은 n번 총 시간, 지연 조인 깊은 쪽은 호출마다. 앱 계정 연결 + 회사 범위(세션)로 RLS 아래.
     */
    @Test
    @EnabledIfSystemProperty(named = "hb.perf", matches = "true")
    void 공통안_득실_연결_방식별() throws Exception {
        String comp = accounts.newComp("GQ");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int rows = ROWS2;
        seed(comp, now, rows);
        for (String t : new String[] {"lot_master", "cms_alarm_log", "sys_outbox", "sys_access_log", "mst_equip", "mst_item"}) {
            owner("analyze " + t);
        }
        long item = accounts.query(comp, "select min(item_id) from mst_item where item_cd like 'GP-%'", Long.class);
        OffsetDateTime aFrom = now.minusDays(91);
        OffsetDateTime aTo = now.plusDays(1);
        long deep = (long) ((rows + SIZE - 1) / SIZE - 1) * SIZE;
        StringBuilder out = new StringBuilder(String.format("%n[perf] 공통안 득실 — 알람·LOT %,d행, 같은 연결에서 반복%n", rows));
        String[][] modes = {{"① 지금(기본)", "", ""}, {"② force_custom_plan", "", "set plan_cache_mode = force_custom_plan"},
                {"③ prepareThreshold=0", "prepareThreshold=0", ""}};
        for (String[] m : modes) {
            try (Connection con = app(comp, m[1], m[2])) {
                long pk = loop(con, "select item_cd from mst_item where item_id = ?", 2000, ps -> ps.setLong(1, item));
                long nm = loop(con, "select count(*) from cms_alarm_log l join mst_equip e on e.equip_id = l.equip_id"
                        + " where e.equip_cd = ? and l.alarm_cd = ? and l.raised_at >= ?", 500, ps -> {
                            ps.setString(1, "GP-E3");
                            ps.setString(2, "A2");
                            ps.setObject(3, now.minusDays(1));
                        });
                long p1 = loop(con, ALARM_LATE, 200, ps -> bindAlarm(ps, aFrom, aTo, 0));
                List<String> d = new ArrayList<>();
                for (int i = 0; i < CALLS; i++) {
                    long s = System.nanoTime();
                    loop(con, ALARM_LATE, 1, ps -> bindAlarm(ps, aFrom, aTo, deep));
                    d.add(String.valueOf((System.nanoTime() - s) / 1_000_000));
                }
                List<String> dl = new ArrayList<>();
                for (int i = 0; i < CALLS; i++) {
                    long s = System.nanoTime();
                    loop(con, LOT_LATE, 1, ps -> {
                        ps.setObject(1, now.minusDays(365));
                        ps.setObject(2, aTo);
                        ps.setInt(3, SIZE);
                        ps.setLong(4, deep);
                    });
                    dl.add(String.valueOf((System.nanoTime() - s) / 1_000_000));
                }
                List<String> dn = new ArrayList<>();
                for (int i = 0; i < CALLS; i++) {
                    long s = System.nanoTime();
                    loop(con, LOT_LATE_NN, 1, ps -> {
                        ps.setObject(1, now.minusDays(365));
                        ps.setObject(2, aTo);
                        ps.setInt(3, SIZE);
                        ps.setLong(4, deep);
                    });
                    dn.add(String.valueOf((System.nanoTime() - s) / 1_000_000));
                }
                out.append(String.format("  %-22s 기본키 2000번 %,6dms | 이름 조회 500번 %,6dms | 알람 지연 조인 1쪽 200번 %,6dms%n", m[0], pk, nm, p1));
                out.append("      알람 지연 조인 끝 쪽 ×12: ").append(String.join(" ", d)).append('\n');
                out.append("      LOT 지연 조인 끝 쪽 ×12: ").append(String.join(" ", dl)).append('\n');
                out.append("      LOT 지연 조인(nulls last 뺌) 끝 쪽 ×12: ").append(String.join(" ", dn)).append('\n');
            }
            System.out.println(out);
        }
        // 왜 — PREPARE 한 같은 문장을 일반·custom 계획으로 강제해 EXPLAIN ANALYZE (1쪽·끝 쪽)
        String alarmArgs = "'P1', '" + aFrom + "', '" + aTo + "', " + SIZE + ", %d, 24";
        String lotArgs = "'" + now.minusDays(365) + "', '" + aTo + "', " + SIZE + ", %d";
        out.append(explainBoth(comp, "알람 지연 조인", ALARM_LATE, "text, timestamptz, timestamptz, int, bigint, int", alarmArgs, deep));
        out.append(explainBoth(comp, "LOT 지연 조인", LOT_LATE, "timestamptz, timestamptz, int, bigint", lotArgs, deep));
        out.append(explainBoth(comp, "LOT 지연 조인(nulls last 뺌)", LOT_LATE_NN, "timestamptz, timestamptz, int, bigint", lotArgs, deep));
        System.out.println(out);
    }

    /** 단순 질의 모드 앱 연결에서 PREPARE → plan_cache_mode 강제 → EXPLAIN (ANALYZE, BUFFERS) EXECUTE — 노드·버퍼·실행 시간 요약 */
    String explainBoth(String comp, String name, String sql, String types, String args, long deep) throws java.sql.SQLException {
        StringBuilder q = new StringBuilder();
        int n = 0;
        for (char ch : sql.toCharArray()) {
            q.append(ch == '?' ? "$" + (++n) : String.valueOf(ch));
        }
        StringBuilder out = new StringBuilder("  " + name + " 계획(강제)\n");
        try (Connection con = app(comp, "preferQueryMode=simple", ""); Statement st = con.createStatement()) {
            st.execute("prepare p (" + types + ") as " + q);
            for (long off : new long[] {0, deep}) {
                for (String mode : new String[] {"force_custom_plan", "force_generic_plan"}) {
                    st.execute("set plan_cache_mode = " + mode);
                    List<String> keep = new ArrayList<>();
                    String exec = "";
                    try (java.sql.ResultSet rs = st.executeQuery("explain (analyze, buffers) execute p(" + String.format(args, off) + ")")) {
                        while (rs.next()) {
                            String l = rs.getString(1).strip();
                            if (l.startsWith("Execution Time")) {
                                exec = l;
                            } else if (l.matches("^(->  )?(Limit|Sort|Incremental Sort|Nested Loop|Hash Join|Merge Join|Gather.*|(Parallel )?(Seq|Index|Index Only|Bitmap Heap|Bitmap Index) Scan.*)\\b.*")
                                    && keep.size() < 8) {
                                keep.add(l.replace("->  ", "").replaceAll("\\s+\\(cost=[^)]*\\)", "").replaceAll("\\(actual time=[^ ]+ ", "(").replaceAll(" loops=", " x"));
                            }
                        }
                    }
                    out.append(String.format("    offset %,d %s: %s | %s%n", off, mode.replace("force_", "").replace("_plan", ""), exec,
                            String.join(" / ", keep)));
                }
            }
        }
        return out.toString();
    }

    static final int ROWS2 = 1_000_000;

    interface Bind {
        void bind(java.sql.PreparedStatement ps) throws java.sql.SQLException;
    }

    static void bindAlarm(java.sql.PreparedStatement ps, OffsetDateTime from, OffsetDateTime to, long offset) throws java.sql.SQLException {
        ps.setString(1, "P1");
        ps.setObject(2, from);
        ps.setObject(3, to);
        ps.setInt(4, SIZE);
        ps.setLong(5, offset);
        ps.setInt(6, 24);
    }

    /** 같은 PreparedStatement 를 n번(MyBatis 는 호출마다 새로 만들지만 드라이버 캐시가 같은 SQL 을 같은 서버 문장으로 잇는다 — 그대로 흉내) */
    static long loop(Connection con, String sql, int n, Bind b) throws java.sql.SQLException {
        long s = System.nanoTime();
        for (int i = 0; i < n; i++) {
            try (java.sql.PreparedStatement ps = con.prepareStatement(sql)) {
                b.bind(ps);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rs.getObject(1);
                    }
                }
            }
        }
        return (System.nanoTime() - s) / 1_000_000;
    }

    /** 앱 계정 연결(RLS 아래) + 회사 범위(세션) + 방식별 URL 속성·세션 설정 */
    Connection app(String comp, String urlProp, String init) throws java.sql.SQLException {
        String url = jdbcUrl() + (urlProp.isEmpty() ? "" : (jdbcUrl().contains("?") ? "&" : "?") + urlProp);
        Connection con = DriverManager.getConnection(url, APP_USER, testPassword());
        try (Statement st = con.createStatement()) {
            st.execute("set statement_timeout = '30s'");
            st.execute("select set_config('app.comp_cd', '" + comp + "', false)");
            if (!init.isEmpty()) {
                st.execute(init);
            }
        }
        return con;
    }

    // ------------------------------------------------------------------ 고친 뒤 모양(지연 조인) — WMS findTrxs 와 같은 꼴, 정렬은 매퍼 foreach 그대로(nulls last)

    static final String LOT_LATE = """
            select k.lot_id from (select m.lot_id, m.created_at from lot_master m where m.created_at >= ? and m.created_at < ?
             order by m.created_at desc nulls last, m.lot_id desc limit ? offset ?) k
            join lot_master m on m.lot_id = k.lot_id join mst_item i on i.item_id = m.item_id
            cross join lateral (select trim_scale(hb_lot_balance(k.lot_id)) as balance) b
            order by k.created_at desc nulls last, k.lot_id desc""";

    /** 같은 LOT 지연 조인에서 정렬의 nulls last 만 뺀 것(created_at·lot_id 는 NOT NULL — 결과 같음). WMS trxOrder 와 같은 꼴 */
    static final String LOT_LATE_NN = LOT_LATE.replace(" nulls last", "");

    static final String ALARM_LATE = """
            select k.alarm_id from (select l.alarm_id, l.raised_at from cms_alarm_log l
              join mst_equip e on e.equip_id = l.equip_id where e.plant_cd = ? and l.raised_at >= ? and l.raised_at < ?
             order by l.raised_at desc nulls last, l.alarm_id desc limit ? offset ?) k
            join cms_alarm_log l on l.alarm_id = k.alarm_id
            cross join lateral (select count(*) as window_count from cms_alarm_log r
              where r.equip_id = l.equip_id and r.alarm_cd = l.alarm_cd and r.raised_at > l.raised_at - make_interval(hours => ?)
                and (r.raised_at < l.raised_at or (r.raised_at = l.raised_at and r.alarm_id <= l.alarm_id))) w
            order by k.raised_at desc nulls last, k.alarm_id desc""";

    // ------------------------------------------------------------------ 잴 것

    /** 새 연결에서 warm(쪽 목록) 뒤 같은 쪽 n번 — "ms ms … | 일반 n·custom n" */
    String series(String comp, Probe pr, List<Integer> warm, int page, int n, boolean custom) {
        ((HikariDataSource) dataSource).getHikariPoolMXBean().softEvictConnections();
        return tenant(comp, () -> {
            jdbc.queryForObject("select set_config('statement_timeout', '30000', true)", String.class);
            if (custom) {
                jdbc.queryForObject("select set_config('plan_cache_mode', 'force_custom_plan', true)", String.class);
            }
            for (int w : warm) {
                pr.call.apply(w);
            }
            List<String> ms = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                long s = System.nanoTime();
                try {
                    pr.call.apply(page);
                    ms.add(String.valueOf((System.nanoTime() - s) / 1_000_000));
                } catch (org.springframework.dao.QueryTimeoutException | org.springframework.dao.DataAccessResourceFailureException e) {
                    ms.add("시간초과");
                    break;
                }
            }
            Map<String, Object> plans = jdbc.queryForMap("""
                    select coalesce(sum(generic_plans), 0) as g, coalesce(sum(custom_plans), 0) as c from pg_prepared_statements
                     where statement like '%' || ? || '%' and statement not like '%pg_prepared_statements%'""", pr.marker);
            return String.join(" ", ms) + "  | 일반 " + plans.get("g") + "·custom " + plans.get("c");
        });
    }

    /** EXPLAIN (GENERIC_PLAN) — 맨 위 3줄 + 주 표 스캔 노드 */
    String genericPlan(String comp, Probe pr) {
        Object[] st = pr.stmt.apply(pr.deepPage);
        String sql;
        if (st.length == 1) {
            sql = (String) st[0];
        } else {
            BoundSql b = sessions.getConfiguration().getMappedStatement((String) st[0]).getBoundSql(st[1]);
            sql = b.getSql();
        }
        StringBuilder q = new StringBuilder();
        int n = 0;
        for (char ch : sql.toCharArray()) {
            q.append(ch == '?' ? "$" + (++n) : String.valueOf(ch));
        }
        // $n 이 든 글자는 확장 프로토콜로 보내면 드라이버가 매개변수 0개로 묶어 연결이 깨진다 — 단순 질의 모드 연결, 앱 계정·회사 범위로
        List<String> plan = new ArrayList<>();
        try (Connection con = DriverManager.getConnection(jdbcUrl() + (jdbcUrl().contains("?") ? "&" : "?") + "preferQueryMode=simple",
                OWNER_USER, testPassword()); Statement st2 = con.createStatement()) {
            st2.execute("set role " + APP_USER);
            st2.execute("select set_config('app.comp_cd', '" + comp + "', false)");
            try (java.sql.ResultSet rs = st2.executeQuery("explain (generic_plan) " + q)) {
                while (rs.next()) {
                    plan.add(rs.getString(1));
                }
            }
        } catch (java.sql.SQLException e) {
            return "(EXPLAIN 실패: " + e.getMessage() + ")";
        }
        List<String> keep = new ArrayList<>();
        for (String l : plan) {
            String s = l.strip().replaceAll("\\s+\\(cost=[^)]*\\)", "").replace("->  ", "");
            if (keep.size() < 3 || s.matches("^(Parallel )?(Seq|Index|Index Only|Bitmap Heap) Scan.*")) {
                keep.add(s);
            }
        }
        return String.join(" / ", keep.subList(0, Math.min(keep.size(), 7)));
    }

    // ------------------------------------------------------------------ MyBatis 문장 + 파라미터(EXPLAIN 용) — 매퍼 @Param 이름 그대로

    Object[] lotStmt(LocalDate today, int p) {
        Map<String, Object> m = new HashMap<>();
        m.put("from", today.minusDays(365));
        m.put("to", today);
        m.put("zone", "Asia/Seoul");
        m.put("itemId", null);
        m.put("keyword", null);
        m.put("origin", null);
        m.put("woId", null);
        m.put("holdOnly", false);
        m.put("page", page(p, LotDtos.LotSort.values(), "createdAt,desc"));
        return new Object[] {LotMapper.class.getName() + ".findLotPage", m};
    }

    Object[] alarmStmt(OffsetDateTime from, OffsetDateTime to, int p) {
        Map<String, Object> m = new HashMap<>();
        m.put("plantCd", "P1");
        m.put("from", from);
        m.put("to", to);
        m.put("keyword", null);
        m.put("openOnly", false);
        m.put("hours", 24);
        m.put("page", page(p, CmsAlarmDtos.AlarmSort.values(), "raisedAt,desc"));
        return new Object[] {CmsAlarmMapper.class.getName() + ".findAlarmPage", m};
    }

    Object[] noticeStmt(OffsetDateTime now, OffsetDateTime to, int p) {
        Map<String, Object> m = new HashMap<>();
        m.put("c", new NotifyDtos.NoticeCrit(now.minusDays(365), to, null, null));
        m.put("page", page(p, NotifyDtos.NoticeSort.values(), "createdAt,desc"));
        return new Object[] {NotifyMapper.class.getName() + ".findNoticePage", m};
    }

    Object[] accessStmt(Instant from, Instant to, int p) {
        Map<String, Object> m = new HashMap<>();
        m.put("q", new LogDtos.AccessSearch(from, to, List.of(), null, null, null, null, null, page(p, LogDtos.TimeSort.values(),
                "occurredAt,desc")));
        return new Object[] {LogMapper.class.getName() + ".findAccess", m};
    }

    static <E extends Enum<E> & io.smarthappy.platform.common.query.SortField> PageQuery page(int p, E[] sorts, String def) {
        return PageQuery.parse(p, SIZE, null, false, sorts, 2000, List.of(def));
    }

    static List<Integer> rep(int v, int n) {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(v);
        }
        return l;
    }

    // ------------------------------------------------------------------ 적재

    /** 기준(공장·품목 50·설비 20·알람 정의 10) + LOT·알람·SRM 통지(아웃박스)·접속 로그 각 rows 행 */
    void seed(String c, OffsetDateTime now, int rows) throws Exception {
        accounts.addPlant(c, "P1", "1공장");
        long[] base = tenant(c, () -> {
            jdbc.update("""
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                    select ?, 'GP-' || g, '실측 ' || g, 'RM', 'BUY', 'EA', 'Y', 't', 't' from generate_series(1, 50) g""", c);
            long cls = jdbc.queryForObject("""
                    insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'GP-CL', 'GP', 't', 't')
                    returning class_id""", Long.class, c);
            jdbc.update("""
                    insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                    select ?, 'P1', 'GP-E' || g, '설비 ' || g, ?, 't', 't' from generate_series(1, 20) g""", c, cls);
            jdbc.update("""
                    insert into cms_alarm_def (comp_cd, alarm_cd, alarm_nm, created_by, updated_by)
                    select ?, 'A' || g, '알람 ' || g, 't', 't' from generate_series(1, 10) g""", c);
            return new long[] {jdbc.queryForObject("select min(item_id) from mst_item where item_cd like 'GP-%'", Long.class),
                    jdbc.queryForObject("select min(equip_id) from mst_equip where equip_cd like 'GP-E%'", Long.class)};
        });
        String ts = "timestamptz '" + now + "'";
        // LOT: 360일에 고르게(등록 시각 = created_at — 목록 기간·정렬 키)
        owner(String.format("""
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_at, created_by, updated_at, updated_by)
                select '%1$s', '%1$s-L' || g, %2$d + (g %% 50), 'RECEIVED', 10, 'EA', %3$s - g * interval '%4$s second',
                       %3$s - g * interval '%4$s second', 'perf', now(), 'perf' from generate_series(1, %5$d) g""",
                c, base[0], ts, 360L * 86400 / rows, rows));
        // 알람: 85일에 고르게, 설비 20 × 코드 10 돌려 가며, 1분 뒤 해제
        owner(String.format("""
                insert into cms_alarm_log (comp_cd, equip_id, alarm_cd, raised_at, cleared_at, cleared_recorded_at, recorded_by)
                select '%1$s', %2$d + (g %% 20), 'A' || (1 + (g / 20) %% 10), %3$s - g * interval '%4$s second',
                       %3$s - g * interval '%4$s second' + interval '1 minute', now(), 'perf' from generate_series(1, %5$d) g""",
                c, base[1], ts, 85L * 86400 / rows, rows));
        // SRM 통지 = 아웃박스(발주 발행), 360일
        owner(String.format("""
                insert into sys_outbox (comp_cd, message_id, topic, biz_key, payload, status, created_at, created_by)
                select '%1$s', gen_random_uuid(), 'SRM_PO_ISSUED', 'PO' || g,
                       jsonb_build_object('poId', g, 'poNo', 'PO' || g, 'rev', 1, 'custNm', '협력사 ' || (g %% 30), 'to', 'v@example.com'),
                       'SENT', %2$s - g * interval '%3$s second', 'perf' from generate_series(1, %4$d) g""", c, ts, 360L * 86400 / rows, rows));
        // 접속 로그: 85일
        owner(String.format("""
                insert into sys_access_log (comp_cd, occurred_at, login_id, event_cd, prog_cd, result_cd, ip)
                select '%1$s', %2$s - g * interval '%3$s second', 'u' || (g %% 40), 'PAGE_VIEW', 'prd-lot', 'OK', '10.0.0.1'
                  from generate_series(1, %4$d) g""", c, ts, 85L * 86400 / rows, rows));
    }

    void owner(String sql) throws Exception {
        try (Connection con = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = con.createStatement()) {
            st.execute("set session_replication_role = replica");
            st.execute(sql);
        }
    }

    <T> T tenant(String c, java.util.function.Supplier<T> work) {
        return runner.call(c, work);
    }
}
