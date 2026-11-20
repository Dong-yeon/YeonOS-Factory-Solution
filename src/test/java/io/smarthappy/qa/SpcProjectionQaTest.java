package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA — QMS SPC 위반 상태 투영(#536, V186 qms_spc_event_state) 동시성. 모듈 시험(SpcEventTest)이 덮은 것(투영 = 다시 계산, 직접 쓰기 거부,
 * A사로 B사)은 다시 하지 않는다. 처리 기록(qms_spc_event_act)이 동시에 들어올 때 투영이 원장 계산(hb_qms_spc_event_state_calc)과 갈라지지
 * 않는지 — V157 가드(이벤트 단위 advisory + 종결·무효 뒤 거부)가 순서를 지키므로 "마지막 처리 = 상태" 투영이 맞는지 본다.
 * <p>부모 행(관리 한계·검사·검사 행)은 소유자 연결에서 트리거를 끄고 맞춰 넣고, 이벤트는 트리거를 켠 채(가드·투영 그대로) 넣는다.
 * 처리 기록은 앱 계정·회사 범위로 넣는다(서비스의 상태 확인은 잠금 없는 읽기라, 동시 요청의 최종 방어는 DB 가드다).
 * 검사 행은 계수형(ATTRIBUTE)이라 단위 요약 표(qms_spc_unit)는 건드리지 않는다. 분석 docs/qa/2026-10-26_pop-spc-asn-b2.md
 */
class SpcProjectionQaTest extends PostgresTestBase {

    static final ExecutorService POOL = Executors.newFixedThreadPool(5);

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    long item;
    long limitId;
    int seq;

    @AfterAll
    static void stop() {
        POOL.shutdownNow();
    }

    @BeforeEach
    void setUp() throws Exception {
        useComp(accounts.newComp("QS"));
    }

    /** 이 회사를 지금 회사로 — 품목·관리 한계를 새로 만든다 */
    void useComp(String c) throws Exception {
        comp = c;
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'QS-FG', 'QA 제품', 'FG', 'MAKE', 'EA', 'Y', 't', 't')""", comp);
        item = accounts.query(comp, "select item_id from mst_item where item_cd = 'QS-FG'", Long.class);
        limitId = replica("""
                insert into qms_spc_limit (comp_cd, item_id, char_id, insp_type, center, sigma, subgroup_cnt, from_at, to_at, rules, created_by, updated_by)
                values (?, ?, 1, 'FINAL', 10, 1, 20, now() - interval '1 day', now() + interval '1 day', 'WE1', 'qa', 'qa') returning limit_id""",
                comp, item);
    }

    long replica(String sql, Object... args) throws Exception {
        return owner(true, sql, args);
    }

    long owner(boolean replica, String sql, Object... args) throws Exception {
        try (Connection con = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = con.createStatement()) {
            if (replica) {
                st.execute("set session_replication_role = replica");
            }
            try (var ps = con.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }
    }

    /** 판정 검사 + 검사 행(부모, 트리거 끔) → 이벤트(트리거 켬 — 가드·투영) */
    long event() throws Exception {
        long insp = replica("""
                insert into qms_insp (comp_cd, insp_no, insp_type, lot_id, item_id, insp_plan_id, lot_qty, status, result, occurred_at,
                                      judged_at, judged_by, created_by, updated_by)
                values (?, ?, 'FINAL', 0, ?, 0, 10, 'JUDGED', 'OK', now(), now(), 'qa', 'qa', 'qa') returning insp_id""",
                comp, "QS-I-" + (++seq) + "-" + System.nanoTime() % 100000, item);
        long line = replica("""
                insert into qms_insp_line (comp_cd, insp_id, line_no, plan_line_id, char_id, data_type, gauge_class, sample_size, ac, re, created_by)
                values (?, ?, 10, 0, 1, 'ATTRIBUTE', 'X', 5, 0, 1, 'qa') returning insp_line_id""", comp, insp);
        return owner(false, """
                insert into qms_spc_event (comp_cd, limit_id, item_id, char_id, insp_type, insp_id, insp_line_id, rule_cd, n, point_mean,
                                           point_range, center, sigma, occurred_at, created_by)
                values (?, ?, ?, 1, 'FINAL', ?, ?, 'WE1', 5, 14, 1, 10, 1, now(), 'qa') returning event_id""", comp, limitId, item, insp, line);
    }

    void act(long ev, String type) {
        runner.call(comp, () -> jdbc.update("insert into qms_spc_event_act (comp_cd, event_id, act_type, note, acted_by) values (hb_current_comp(), ?, ?, ?, 'qa')",
                ev, type, "ACK".equals(type) ? null : "QA " + type));
    }

    String state(long ev) {
        return accounts.query(comp, "select status from qms_spc_event_state where event_id = ?", String.class, ev);
    }

    /** 투영과 원장 다시 계산의 차이(양방향) — 상태·마지막 처리자·시각 */
    int diff() {
        return accounts.query(comp, """
                select (select count(*) from (select event_id, status, last_by, last_at from qms_spc_event_state
                                              except select event_id, status, last_by, last_at from hb_qms_spc_event_state_calc()) a)
                     + (select count(*) from (select event_id, status, last_by, last_at from hb_qms_spc_event_state_calc()
                                              except select event_id, status, last_by, last_at from qms_spc_event_state) b)""", Integer.class);
    }

    /** 어긋난 이벤트마다 "event 투영(상태:처리자) | 계산(상태:처리자) | act_seq 순 처리자" — 실패 원인(S-1 이면 상태 같고 처리자만 다름)을 바로 보이게 */
    String mismatch() {
        return String.join("; ", accounts.query(comp, """
                select coalesce(string_agg(s.event_id || ' ' || s.status || ':' || coalesce(s.last_by, '-') || ' | ' || c.status || ':' || coalesce(c.last_by, '-')
                       || ' | ' || (select string_agg(x.act_type || ':' || x.acted_by, ',' order by x.act_seq) from qms_spc_event_act x where x.event_id = s.event_id),
                       '; '), '')
                  from qms_spc_event_state s join hb_qms_spc_event_state_calc() c on c.event_id = s.event_id
                 where (s.status, s.last_by, s.last_at) is distinct from (c.status, c.last_by, c.last_at)""", String.class));
    }

    int lockWaiters() {
        return jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()",
                Integer.class);
    }

    /** 첫 처리가 커밋을 미루는 동안 둘째가 가드 잠금을 기다리는 것을 관찰한 뒤 커밋 — 결과 [첫, 둘째] 의 예외(없으면 null) */
    Throwable[] race(long ev, String first, String second) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        Future<Throwable> a = POOL.submit(() -> catchThrowable(() -> runner.call(comp, () -> {
            jdbc.update("insert into qms_spc_event_act (comp_cd, event_id, act_type, note, acted_by) values (hb_current_comp(), ?, ?, ?, 'qa1')",
                    ev, first, "QA " + first);
            done.countDown();
            try {
                go.await(30, TimeUnit.SECONDS);                // 모두 관찰한 뒤 커밋
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        })));
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        int before = lockWaiters();
        Future<Throwable> b = POOL.submit(() -> catchThrowable(() -> runner.call(comp, () -> jdbc.update(
                "insert into qms_spc_event_act (comp_cd, event_id, act_type, note, acted_by) values (hb_current_comp(), ?, ?, ?, 'qa2')",
                ev, second, "QA " + second))));
        for (int i = 0; i < 200 && lockWaiters() <= before; i++) {
            Thread.sleep(50);
        }
        assertThat(lockWaiters()).as("둘째가 가드 잠금을 기다린다").isGreaterThan(before);
        go.countDown();
        return new Throwable[] {a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)};
    }

    // ------------------------------------------------------------------ 시험

    /** 종결이 먼저(커밋 미룸) · 조치가 뒤 — 조치는 가드가 거부(55000 ck_qms_spc_event_act_done), 투영은 CLOSED 이고 계산과 같다 */
    @Test
    void 종결과_조치가_동시면_종결_뒤_조치는_거부되고_투영은_계산과_같다() throws Exception {
        long ev = event();
        act(ev, "ACK");
        Throwable[] r = race(ev, "CLOSE", "ACTION");
        assertThat(r[0]).isNull();
        assertThat(r[1]).isNotNull();
        assertThat(r[1].getMessage()).contains("[55000]").contains("already closed or voided");   // ck_qms_spc_event_act_done
        assertThat(state(ev)).isEqualTo("CLOSED");
        assertThat(diff()).isZero();
    }

    /** 조치가 먼저 · 종결이 뒤 — 둘 다 들어가고 마지막 처리 = 종결, 투영 CLOSED(last_by = 종결한 사람)·계산과 같다 */
    @Test
    void 조치가_먼저면_둘_다_들어가고_마지막_종결이_상태() throws Exception {
        long ev = event();
        Throwable[] r = race(ev, "ACTION", "CLOSE");
        assertThat(r[0]).isNull();
        assertThat(r[1]).isNull();
        assertThat(accounts.query(comp, "select status || ':' || last_by from qms_spc_event_state where event_id = ?", String.class, ev))
                .isEqualTo("CLOSED:qa2");
        assertThat(diff()).isZero();
    }

    /**
     * 결함 S-1 재현(2026-10-27, 무작위 회귀 시험이 가끔 2 로 깨진 원인): 처리 번호(act_id, 식별 열 기본값)는 행을 만들 때 받고, 같은 이벤트의
     * 줄 세우기(V157 가드 advisory)는 그 뒤 BEFORE 트리거에서 한다. 그래서 번호를 먼저 받은 처리가 줄은 늦게 서서 나중에 커밋할 수 있다.
     * 투영(V186 hb_qms_spc_state_on_act)은 "마지막에 커밋한 처리" 로 last_by·last_at 을 덮고, 다시 계산 함수는 "act_id 가 가장 큰 처리" 를
     * 마지막으로 본다 → 마지막 처리자·시각이 조용히 갈라진다(상태는 같음 — 종결·무효 뒤는 가드가 거부).
     * 결정적으로: T1 의 INSERT 가 번호를 받은 뒤 note 식에서 시험이 쥔 advisory 잠금을 기다리게 하고(대상 목록은 열 순서로 평가 — act_id 가
     * 먼저), 그동안 T2 가 큰 번호로 들어가 커밋, 그다음 T1 이 커밋
     */
    @Test
    void 결함S1_번호를_먼저_받은_처리가_늦게_커밋해도_투영의_마지막_처리가_계산과_같다() throws Exception {
        long ev = event();
        long gate = 700_000L + ev;
        try (Connection hold = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = hold.createStatement()) {
            st.execute("select pg_advisory_lock(" + gate + ")");
            int before = lockWaiters();
            Future<Throwable> late = POOL.submit(() -> catchThrowable(() -> runner.call(comp, () -> jdbc.update("""
                    insert into qms_spc_event_act (comp_cd, event_id, act_type, note, acted_by)
                    values (hb_current_comp(), ?, 'ACTION', (select 'QA 늦게' from (select pg_advisory_xact_lock(?)) z), 'late')""", ev, gate))));
            for (int i = 0; i < 200 && lockWaiters() <= before; i++) {
                Thread.sleep(50);
            }
            assertThat(lockWaiters()).as("T1 이 번호를 받은 뒤 note 식에서 기다린다").isGreaterThan(before);
            runner.call(comp, () -> jdbc.update("insert into qms_spc_event_act (comp_cd, event_id, act_type, acted_by) values (hb_current_comp(), ?, 'ACK', 'early')",
                    ev));
            st.execute("select pg_advisory_unlock(" + gate + ")");
            assertThat(late.get(20, TimeUnit.SECONDS)).isNull();
        }
        assertThat(accounts.query(comp, "select string_agg(acted_by, ',' order by act_id) from qms_spc_event_act where event_id = ?", String.class, ev))
                .as("번호 순서: 늦게 커밋한 T1 이 작은 번호").isEqualTo("late,early");
        System.out.println("[S1] 투영=" + accounts.query(comp, "select status || ':' || last_by from qms_spc_event_state where event_id = ?", String.class, ev)
                + " 계산=" + accounts.query(comp, "select status || ':' || last_by from hb_qms_spc_event_state_calc() where event_id = ?", String.class, ev));
        assertThat(diff()).as("투영의 마지막 처리(커밋 순서) = 계산의 마지막 처리(act_id 최대)").isZero();
    }

    /**
     * 회귀: 이벤트 12개에 단말 4개가 처리 기록 25개씩을 무작위로 동시에(ACK·ACTION·CLOSE·VOID — 종결·무효 뒤는 거부) → 투영 전체 = 원장 계산,
     * 종결·무효가 있는 이벤트는 그것이 마지막 처리(순서는 act_seq — 가드 잠금 뒤 매긴 실제 처리 순서). 끄지 않는다 — 두 단언 모두 늘 통과해야 한다.
     * 경위: "expected 0 but was 2" 는 결함 S-1(투영 last_by, V199 로 고침 — 위 결함S1 시험), 그 뒤 "[종결·무효 뒤에 다른 처리가 없다] expected 0 but was 1"
     * (40회 중 4회)은 결함 S-2(act_id 순서 ≠ 실제 처리 순서, V201 act_seq 로 고침 — 아래 결함S2 시험). 고친 뒤 단독 반복으로 확인(docs/testing/qms.md)
     */
    @Test
    void 무작위_동시_처리_뒤에도_투영_전체가_원장_계산과_같다() throws Exception {
        List<Long> evs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            evs.add(event());
        }
        String[] types = {"ACK", "ACTION", "ACTION", "CLOSE", "VOID"};
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Random rnd = new Random(42 + t);
            fs.add(POOL.submit(() -> {
                for (int i = 0; i < 25; i++) {
                    long ev = evs.get(rnd.nextInt(evs.size()));
                    String type = types[rnd.nextInt(types.length)];
                    if (catchThrowable(() -> act(ev, type)) != null) {
                        rejected.incrementAndGet();
                    }
                }
            }));
        }
        for (Future<?> f : fs) {
            f.get(60, TimeUnit.SECONDS);
        }
        assertThat(rejected.get()).as("종결·무효 뒤 처리는 거부됨(무작위라 일부)").isPositive();
        assertThat(diff()).as("투영 ≠ 계산 — 어긋난 이벤트(투영 | 계산 | 처리 순서): %s", mismatch()).isZero();
        assertThat(accounts.query(comp, """
                select count(*) from qms_spc_event e
                 where exists (select 1 from qms_spc_event_act x where x.event_id = e.event_id and x.act_type in ('CLOSE', 'VOID'))
                   and (select x.act_type from qms_spc_event_act x where x.event_id = e.event_id order by x.act_seq desc limit 1) not in ('CLOSE', 'VOID')""",
                Integer.class)).as("종결·무효 뒤에 다른 처리가 없다").isZero();
        assertThat(accounts.query(comp, "select count(*) from (select x.event_id from qms_spc_event_act x where x.act_type in ('CLOSE', 'VOID') "
                + "group by x.event_id having count(*) > 1) d", Integer.class)).as("종결·무효는 이벤트마다 한 번").isZero();
    }

    /**
     * V199 기존 행 맞춤(2026-10-27 QA) — 두 회사의 투영 행을 트리거 끄고 틀리게 만든 뒤(상태·처리자), 마이그레이션 파일의 맞춤 블록
     * (가드 끄기 → 회사마다 app.comp_cd 로 다시 계산과 다르면 고침 → 가드 켜기)을 그대로 실행 → 두 회사 모두 투영 = 계산, 맞는 행은 그대로.
     * 시험 DB 의 소유자는 슈퍼유저라 RLS 를 건너뛴다 — 운영(소유자 ≠ 슈퍼유저, FORCE RLS)의 회사별 거름은 [코드]로 봄(문서)
     */
    @Test
    void V199_기존_행_맞춤은_여러_회사에서_틀린_행만_고친다() throws Exception {
        String a = comp;
        long a1 = event();
        long a2 = event();
        act(a1, "ACK");
        act(a2, "CLOSE");
        String b = accounts.newComp("QT");
        useComp(b);
        long b1 = event();
        act(b1, "ACTION");
        String okA2 = accounts.query(a, "select status || ':' || last_by || ':' || last_at from qms_spc_event_state where event_id = ?", String.class, a2);
        // 틀리게(트리거 끔): A 의 a1 처리자, B 의 b1 상태
        replica("update qms_spc_event_state set last_by = 'WRONG' where event_id = ? returning event_id", a1);
        replica("update qms_spc_event_state set status = 'OPEN' where event_id = ? returning event_id", b1);
        comp = a;
        assertThat(diff()).isEqualTo(2);
        comp = b;
        assertThat(diff()).isEqualTo(2);
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/V199__qms_spc_state_last_from_ledger.sql"));
        String block = sql.substring(sql.indexOf("ALTER TABLE qms_spc_event_state DISABLE TRIGGER"));
        try (Connection con = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = con.createStatement()) {
            con.setAutoCommit(false);
            st.execute(block);
            con.commit();
        }
        comp = a;
        assertThat(diff()).as("A사 투영 = 계산").isZero();
        assertThat(accounts.query(a, "select status || ':' || last_by || ':' || last_at from qms_spc_event_state where event_id = ?", String.class, a2))
                .as("맞던 행은 그대로").isEqualTo(okA2);
        comp = b;
        assertThat(diff()).as("B사 투영 = 계산").isZero();
        // 가드는 다시 켜졌다 — 앱 계정 직접 수정 거부
        assertThat(catchThrowable(() -> accounts.exec(b, "update qms_spc_event_state set last_by = 'X' where event_id = ?", b1))).isNotNull();
    }

    /**
     * 결함 S-2 재현(2026-10-27, QA docs/qa/2026-10-27_qms-import-ocap-pda.md 부록 A — 고친 뒤 켜서 넣음): 처리 번호(act_id)는 줄 세우기(V157 가드 잠금)
     * 전에 받으므로 "번호 순서 ≠ 실제 처리 순서". 번호를 먼저 받은 종결(T1)이 가드 앞에서 밀린 사이 큰 번호의 확인(T2)이 먼저 들어가고, 그다음 종결이
     * 들어간다(종결 뒤가 아니므로 가드가 허용). 고치기 전엔 원장을 번호 순으로 읽으면 "종결 → 확인" 이라 ① 처리 이력이 거꾸로 보이고 ② 투영(V199,
     * act_id 최대)의 마지막 처리자가 확인한 사람이었다. 고친 뒤(V201): 가드가 잠금 뒤 매기는 act_seq 로 읽으면 실제 순서
     */
    @Test
    void 결함S2_번호를_먼저_받은_종결이_늦게_들어가도_마지막_처리는_종결이다() throws Exception {
        long ev = event();
        long gate = 800_000L + ev;
        try (Connection hold = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = hold.createStatement()) {
            st.execute("select pg_advisory_lock(" + gate + ")");
            int before = lockWaiters();
            Future<Throwable> late = POOL.submit(() -> catchThrowable(() -> runner.call(comp, () -> jdbc.update("""
                    insert into qms_spc_event_act (comp_cd, event_id, act_type, note, acted_by)
                    values (hb_current_comp(), ?, 'CLOSE', (select 'QA 종결' from (select pg_advisory_xact_lock(?)) z), 'closer')""", ev, gate))));
            for (int i = 0; i < 200 && lockWaiters() <= before; i++) {
                Thread.sleep(50);
            }
            assertThat(lockWaiters()).as("종결이 번호를 받은 뒤 기다린다").isGreaterThan(before);
            runner.call(comp, () -> jdbc.update("insert into qms_spc_event_act (comp_cd, event_id, act_type, acted_by) values (hb_current_comp(), ?, 'ACK', 'acker')",
                    ev));
            st.execute("select pg_advisory_unlock(" + gate + ")");
            assertThat(late.get(20, TimeUnit.SECONDS)).as("종결은 확인 뒤라 가드가 허용").isNull();
        }
        System.out.println("[S2] 번호순=" + accounts.query(comp, "select string_agg(act_type || ':' || acted_by, ',' order by act_id) from qms_spc_event_act where event_id = ?",
                String.class, ev) + " 투영=" + accounts.query(comp, "select status || ':' || last_by from qms_spc_event_state where event_id = ?", String.class, ev));
        assertThat(accounts.query(comp, "select string_agg(act_type, ',' order by act_id) from qms_spc_event_act where event_id = ?", String.class, ev))
                .as("재현 확인: 번호 순서는 여전히 종결이 먼저").isEqualTo("CLOSE,ACK");
        assertThat(accounts.query(comp, "select status || ':' || last_by from qms_spc_event_state where event_id = ?", String.class, ev))
                .as("종결된 이벤트의 마지막 처리자 = 종결한 사람").isEqualTo("CLOSED:closer");
        assertThat(accounts.query(comp, "select string_agg(act_type || act_seq, ',' order by act_seq) from qms_spc_event_act where event_id = ?", String.class, ev))
                .as("원장 순서 = 실제 처리 순서(확인 → 종결)").isEqualTo("ACK1,CLOSE2");
        assertThat(diff()).isZero();
    }

    /**
     * act_seq 는 가드가 매긴다 — 요청이 넣은 값(중복·음수)은 무시하고 덮는다. 앱 계정은 순번을 고칠 수 없다(원장 UPDATE 권한 없음),
     * 소유자 계정도 가드(추가만)에 막힌다
     */
    @Test
    void 순번은_가드가_매기고_요청값과_수정은_통하지_않는다() throws Exception {
        long ev = event();
        act(ev, "ACK");
        runner.call(comp, () -> jdbc.update("""
                insert into qms_spc_event_act (comp_cd, event_id, act_type, note, acted_by, act_seq) values (hb_current_comp(), ?, 'ACTION', '순번 위조', 'qa', 1)""", ev));
        runner.call(comp, () -> jdbc.update("""
                insert into qms_spc_event_act (comp_cd, event_id, act_type, note, acted_by, act_seq) values (hb_current_comp(), ?, 'ACTION', '순번 음수', 'qa', -5)""", ev));
        assertThat(accounts.query(comp, "select string_agg(act_seq::text, ',' order by act_id) from qms_spc_event_act where event_id = ?", String.class, ev))
                .isEqualTo("1,2,3");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp,
                "update qms_spc_event_act set act_seq = 9 where event_id = ?", ev)))).contains("42501");
        assertThat(PgErrors.constraint(catchThrowable(() -> owner(false,
                "update qms_spc_event_act set act_seq = 9 where event_id = ? returning 1", ev)))).contains("ck_qms_spc_event_act_append_only");
    }

    /**
     * V201 기존 행 채우기 — 과거 S-2 로 뒤바뀐 이벤트(act_id 로는 종결이 확인·조치보다 앞)를 만들고 마이그레이션 파일의 두 블록(순번 채우기 · 투영 맞춤)을
     * 그대로 실행 → 종결이 맨 뒤 순번, 투영의 마지막 처리자 = 종결한 사람, 맞던 이벤트는 그대로. 채우기는 순번 유일 인덱스를 만들기 전 단계라
     * 한 트랜잭션에서 인덱스를 잠시 내리고 실행한 뒤 보고 되돌린다(공용 시험 DB 에 흔적 없음)
     */
    @Test
    void V201_기존_행_채우기는_종결을_맨_뒤로_투영도_맞춘다() throws Exception {
        long bad = event();
        act(bad, "ACK");
        act(bad, "ACTION");
        act(bad, "CLOSE");
        long ok = event();
        runner.call(comp, () -> jdbc.update("insert into qms_spc_event_act (comp_cd, event_id, act_type, acted_by) values (hb_current_comp(), ?, 'ACK', 'acker')", ok));
        runner.call(comp, () -> jdbc.update("insert into qms_spc_event_act (comp_cd, event_id, act_type, note, acted_by) values (hb_current_comp(), ?, 'ACTION', 'QA', 'doer')",
                ok));
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/V201__qms_spc_act_seq.sql"));
        String fill = sql.substring(sql.indexOf("ALTER TABLE qms_spc_event_act DISABLE TRIGGER"), sql.indexOf("ALTER TABLE qms_spc_event_act ALTER COLUMN"));
        String resync = sql.substring(sql.indexOf("ALTER TABLE qms_spc_event_state DISABLE TRIGGER"));
        try (Connection con = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = con.createStatement()) {
            con.setAutoCommit(false);
            try {
                st.execute("drop index uq_qms_spc_event_act_seq");
                // 과거 S-2 흉내(트리거 끔): 종결보다 큰 act_id 의 확인 + 그때 투영(V199, act_id 최대)이 본 마지막 처리자
                st.execute("set session_replication_role = replica");
                st.execute("insert into qms_spc_event_act (comp_cd, event_id, act_type, acted_by, act_seq) values ('" + comp + "', " + bad + ", 'ACK', 'late-acker', 99)");
                st.execute("update qms_spc_event_state set last_by = 'late-acker' where event_id = " + bad);
                st.execute("set session_replication_role = origin");
                st.execute(fill);
                st.execute(resync);
                assertThat(one(st, "select string_agg(act_type || ':' || acted_by || ':' || act_seq, ',' order by act_seq) from qms_spc_event_act where event_id = " + bad))
                        .as("act_id 가 종결보다 큰 확인은 S-2 로 뒤바뀐 것 — 종결이 맨 뒤").isEqualTo("ACK:qa:1,ACTION:qa:2,ACK:late-acker:3,CLOSE:qa:4");
                assertThat(one(st, "select string_agg(acted_by || ':' || act_seq, ',' order by act_seq) from qms_spc_event_act where event_id = " + ok))
                        .as("처리 중 이벤트는 act_id 순 그대로").isEqualTo("acker:1,doer:2");
                assertThat(one(st, "select status || ':' || last_by from qms_spc_event_state where event_id = " + bad)).as("투영 마지막 처리자 = 종결한 사람")
                        .isEqualTo("CLOSED:qa");
                assertThat(one(st, "select count(*)::text from qms_spc_event_state s join hb_qms_spc_event_state_calc() c on c.event_id = s.event_id "
                        + "where (s.status, s.last_by, s.last_at) is distinct from (c.status, c.last_by, c.last_at)")).as("투영 = 계산(전체)").isEqualTo("0");
                assertThat(one(st, "select count(*)::text from pg_trigger where tgname in ('tg_qms_spc_event_act_guard', 'tg_qms_spc_event_state_guard') and tgenabled = 'O'"))
                        .as("가드 두 개 다시 켜짐").isEqualTo("2");
            } finally {
                con.rollback();
            }
        }
    }

    static String one(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
