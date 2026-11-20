package io.smarthappy.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 아웃박스 보존 정리 hb_purge_outbox (docs/36 19.2 R1~R8, Q-O2) — 끝난 3상태(SENT·CANCELED·CLOSED_MANUAL)만, 끝난 시각 기준,
 * 회사 보존 기간(클래스 최소 30일 이상)·법적 보류, 처리 안 끝난 것(NEW·SENDING·FAIL·종결 결재 대기) 남김, 회사당 상한·남은 수,
 * A사 정리가 B사 행을 건드리지 않음, 앱 계정은 실행 못 함, 로그 정리(hb_purge_logs)는 아웃박스를 건드리지 않음.
 * 시험 행은 지난 시각으로 넣고 기준 시각은 지금 — 다른 시험 클래스의 최근 행은 대상이 아니다.
 */
class OutboxPurgeTest extends PostgresTestBase {

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    private static Connection owner() throws Exception {
        return DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword());
    }

    /** 소유자 계정으로 정리 → 회사별 {지운 수, 남은 수} */
    private static Map<String, long[]> purge(int limit) throws Exception {
        Map<String, long[]> out = new HashMap<>();
        try (Connection c = owner(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("select * from hb_purge_outbox(now(), " + limit + ")")) {
            while (rs.next()) {
                out.put(rs.getString(1), new long[] {rs.getLong(2), rs.getLong(3)});
            }
        }
        return out;
    }

    /**
     * 행 하나 — status, 만든 지 며칠, 보낸 지 며칠(null = 없음), 종결한 지 며칠(null = 없음), 업무 키(확인용)
     */
    private void row(String comp, String status, int createdAgo, Integer sentAgo, Integer closedAgo, String bizKey) {
        accounts.exec(comp, """
                insert into sys_outbox (comp_cd, message_id, topic, biz_key, payload, status, created_at, sent_at, locked_at,
                                        closed_at, closed_by, close_reason, created_by)
                values (?, gen_random_uuid(), 'MAIL', ?, '{}'::jsonb, ?, now() - make_interval(days => ?),
                        case when ?::int is null then null else now() - make_interval(days => ?::int) end,
                        case when ? = 'SENDING' then now() end,
                        case when ?::int is null then null else now() - make_interval(days => ?::int) end,
                        case when ?::int is null then null else 'admin' end,
                        case when ?::int is null then null else '수동 종결 ZT' end, 't')""",
                comp, bizKey, status, createdAgo, sentAgo, sentAgo, status, closedAgo, closedAgo, closedAgo, closedAgo);
    }

    private List<String> keys(String comp) {
        return runner.call(comp, () -> jdbc.queryForList("select biz_key from sys_outbox where comp_cd = ? order by biz_key", String.class, comp));
    }

    @Test
    void 끝난_세_상태만_끝난_시각이_보존_기간을_지난_것만_지운다() throws Exception {
        String a = accounts.newComp("OP");
        row(a, "SENT", 120, 100, null, "D-sent-100");               // 지움
        row(a, "SENT", 300, 10, null, "K-sent-10");                 // 만든 지 오래돼도 보낸 지 10일 → 남김
        row(a, "CANCELED", 100, null, null, "D-canceled-100");      // 지움(끝난 시각 = 만든 시각)
        row(a, "CLOSED_MANUAL", 200, null, 100, "D-closed-100");    // 지움
        row(a, "CLOSED_MANUAL", 200, null, 10, "K-closed-10");      // 종결한 지 10일 → 남김
        row(a, "FAIL", 300, null, null, "K-fail-300");              // 처리 안 끝남 → 남김
        row(a, "NEW", 300, null, null, "K-new-300");
        row(a, "SENDING", 300, null, null, "K-sending-300");
        row(a, "FAIL", 300, null, null, "K-fail-approval");         // 종결 결재 대기
        accounts.exec(a, "update sys_outbox set close_apr_id = 1, close_reason = '결재 대기', closed_by = 'admin' where biz_key = 'K-fail-approval'");

        Map<String, long[]> r = purge(5000);
        assertThat(r.get(a)).containsExactly(3, 0);
        assertThat(keys(a)).allMatch(k -> k.startsWith("K-")).hasSize(6);
    }

    @Test
    void 회사_보존_기간_최소_기간_법적_보류를_지키고_A사_정리가_B사_행을_건드리지_않는다() throws Exception {
        String a = accounts.newComp("OP");
        String b = accounts.newComp("OP");
        String c = accounts.newComp("OP");
        for (String comp : List.of(a, b, c)) {
            row(comp, "SENT", 130, 120, null, "S-120");
            row(comp, "SENT", 50, 40, null, "S-040");
            row(comp, "SENT", 30, 20, null, "S-020");
        }
        // A: 200일로 늘림 → 셋 다 남김 / B: 10일로 줄이려 해도 최소 30일 → 120·40 지움, 20 남김
        accounts.exec(a, "insert into sys_retention_policy (comp_cd, class_cd, keep_days, created_by, updated_by) values (?, 'OUTBOX', 200, 't', 't')", a);
        accounts.exec(b, "insert into sys_retention_policy (comp_cd, class_cd, keep_days, created_by, updated_by) values (?, 'OUTBOX', 10, 't', 't')", b);
        row(c, "SENT", 110, 100, null, "S-100");
        // C: 기본 90일 + 110~130일 전 법적 보류 → 120 남김. 95~105일 전 보류는 해제됨 → 100 지움
        accounts.exec(c, """
                insert into sys_legal_hold (comp_cd, class_cd, from_at, to_at, reason, created_by, updated_by)
                values (?, 'OUTBOX', now() - interval '130 days', now() - interval '110 days', '거래처 분쟁 ZT', 't', 't')""", c);
        accounts.exec(c, """
                insert into sys_legal_hold (comp_cd, class_cd, from_at, to_at, reason, released_at, created_by, updated_by)
                values (?, 'OUTBOX', now() - interval '105 days', now() - interval '95 days', '종결 ZT', now(), 't', 't')""", c);

        Map<String, long[]> r = purge(5000);
        assertThat(r).doesNotContainKey(a);
        assertThat(keys(a)).containsExactly("S-020", "S-040", "S-120");
        assertThat(r.get(b)).containsExactly(2, 0);
        assertThat(keys(b)).containsExactly("S-020");
        assertThat(r.get(c)).containsExactly(1, 0);     // 해제된 보류 안의 100 만 지움, 120 은 보류
        assertThat(keys(c)).containsExactly("S-020", "S-040", "S-120");
    }

    @Test
    void 회사당_상한까지만_지우고_남은_수를_돌려준다() throws Exception {
        String a = accounts.newComp("OP");
        for (int i = 0; i < 5; i++) {
            row(a, "SENT", 200, 100, null, "D-" + i);
        }
        assertThat(purge(2).get(a)).containsExactly(2, 3);
        assertThat(purge(2).get(a)).containsExactly(2, 1);
        assertThat(purge(2).get(a)).containsExactly(1, 0);
        assertThat(purge(2)).doesNotContainKey(a);
        assertThat(keys(a)).isEmpty();
    }

    @Test
    void 상한_값이_틀리면_정리하지_않는다() throws Exception {
        for (String bad : List.of("0", "-1", "100001", "null")) {
            assertThatThrownBy(() -> {
                try (Connection c = owner(); Statement st = c.createStatement()) {
                    st.executeQuery("select * from hb_purge_outbox(now(), " + bad + ")").close();
                }
            }).as(bad).hasMessageContaining("p_limit");
        }
    }

    @Test
    void 앱_계정은_정리_함수와_판정_함수를_실행할_수_없고_아웃박스를_지울_수도_없다() {
        String a = accounts.newComp("OP");
        row(a, "SENT", 200, 100, null, "K-app");
        for (String sql : List.of("select * from hb_purge_outbox()",
                "select hb_outbox_on_hold('" + a + "', now())",
                "select hb_outbox_purgeable('SENT', now(), null, now(), now())")) {
            assertThatThrownBy(() -> runner.call(a, () -> jdbc.queryForList(sql))).as(sql)
                    .rootCause().hasMessageContaining("permission denied");
        }
        assertThatThrownBy(() -> runner.run(a, () -> jdbc.update("delete from sys_outbox where comp_cd = ?", a)))
                .rootCause().hasMessageContaining("permission denied");
        assertThat(keys(a)).containsExactly("K-app");
    }

    @Test
    void 로그_정리는_아웃박스를_건드리지_않고_매핑은_아웃박스만_OUTBOX() throws Exception {
        String a = accounts.newComp("OP");
        row(a, "SENT", 3000, 3000, null, "K-old");
        try (Connection c = owner(); Statement st = c.createStatement()) {
            st.executeQuery("select * from hb_purge_logs()").close();
        }
        assertThat(keys(a)).containsExactly("K-old");
        assertThat(runner.call(a, () -> jdbc.queryForList(
                "select table_nm || '=' || class_cd from sys_retention_table where table_nm in ('sys_outbox', 'sys_inbox') order by 1",
                String.class))).containsExactly("sys_inbox=MESSAGE", "sys_outbox=OUTBOX");
        assertThat(runner.call(a, () -> jdbc.queryForList(
                "select basis || ',' || default_days || ',' || min_days || ',' || purge_allowed_yn from sys_retention_class where class_cd = 'OUTBOX'",
                String.class))).containsExactly("STATUS_DONE,90,30,Y");
    }
}
