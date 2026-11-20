package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA — OCAP 표준 조치 목록(V198, docs/29_P3 26장) 이상 사용. 모듈 시험(OcapTest)이 덮은 것(문구 수정과 고르기 동시, 거부 사유별 처리 행 없음,
 * 권한·A사로 B사 항목·앱 계정 직접 SQL)은 다시 하지 않는다. 여기서는 사용 중지·규칙 변경이 고르기와 겹칠 때, 고르기가 항목 잠금을 기다리는 사이
 * 종결, 사본 원장의 소유자 계정 수정·TRUNCATE, B사가 A사 위반 id 로. 분석 docs/qa/2026-10-27_qms-import-ocap-pda.md
 */
@AutoConfigureMockMvc
class OcapQaTest extends PostgresTestBase {

    static final String PW = "Qa-ocap-list-53";
    static final String E = "/api/qms/spc/events";
    static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    RequestPostProcessor admin;
    long item;
    long limitId;
    long charId;
    long lotId;
    int seq;

    @AfterAll
    static void stop() {
        POOL.shutdownNow();
    }

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("QO");
        long u = accounts.newUser(comp, "qo1", PW);
        accounts.grant(comp, u, "QA", "qms-spc", "SEARCH", "SAVE");
        accounts.grant(comp, u, "QA", "qms-ocap", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "qo1");
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'QO-FG', 'QA 제품', 'FG', 'MAKE', 'EA', 't', 't')""", comp);
        item = accounts.query(comp, "select item_id from mst_item where item_cd = 'QO-FG'", Long.class);
        charId = accounts.query(comp, """
                insert into qms_char (comp_cd, char_type, item_id, char_no, char_nm, data_type, unit_nm, scale, lsl, nominal, usl, created_by, updated_by)
                values (?, 'PRODUCT', ?, '1', '지름', 'VARIABLE', 'mm', 2, 9.95, 10.00, 10.05, 't', 't') returning char_id""", Long.class, comp, item);
        lotId = accounts.query(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                values (?, 'L-QO-1', ?, 'RECEIVED', 10, 'EA', now(), 't', 't') returning lot_id""", Long.class, comp, item);
        limitId = owner(true, """
                insert into qms_spc_limit (comp_cd, item_id, char_id, insp_type, center, sigma, subgroup_cnt, from_at, to_at, rules, created_by, updated_by)
                values (?, ?, ?, 'FINAL', 10, 1, 20, now() - interval '1 day', now() + interval '1 day', 'WE1', 'qa', 'qa') returning limit_id""",
                comp, item, charId);
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

    /** 판정 검사 + 검사 행(트리거 끔) → WE1 이벤트(트리거 켬) — SpcProjectionQaTest 와 같은 준비 */
    long event() throws Exception {
        long insp = owner(true, """
                insert into qms_insp (comp_cd, insp_no, insp_type, lot_id, item_id, insp_plan_id, lot_qty, status, result, occurred_at,
                                      judged_at, judged_by, created_by, updated_by)
                values (?, ?, 'FINAL', ?, ?, 0, 10, 'JUDGED', 'OK', now(), now(), 'qa', 'qa', 'qa') returning insp_id""",
                comp, "QO-I-" + (++seq) + "-" + System.nanoTime() % 100000, lotId, item);
        long line = owner(true, """
                insert into qms_insp_line (comp_cd, insp_id, line_no, plan_line_id, char_id, data_type, gauge_class, sample_size, ac, re, created_by)
                values (?, ?, 10, 0, ?, 'VARIABLE', 'CALIPER', 5, 0, 1, 'qa') returning insp_line_id""", comp, insp, charId);
        return owner(false, """
                insert into qms_spc_event (comp_cd, limit_id, item_id, char_id, insp_type, insp_id, insp_line_id, rule_cd, n, point_mean,
                                           point_range, center, sigma, occurred_at, created_by)
                values (?, ?, ?, ?, 'FINAL', ?, ?, 'WE1', 5, 14, 1, 10, 1, now(), 'qa') returning event_id""", comp, limitId, item, charId, insp, line);
    }

    long ocap(String rule, String text) throws Exception {
        MvcResult m = mvc.perform(post("/api/qms/ocaps").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ruleCd\":" + (rule == null ? "null" : "\"" + rule + "\"") + ",\"seq\":1,\"actionText\":\"" + text + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    MvcResult pick(RequestPostProcessor who, long ev, long ocapId) throws Exception {
        return mvc.perform(post(E + "/" + ev + "/acts").with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"actType\":\"ACTION\",\"note\":\"현장 조치\",\"ocapIds\":[" + ocapId + "]}")).andReturn();
    }

    String acts(long ev) {
        return accounts.query(comp, "select coalesce(string_agg(act_type, ',' order by act_id), '') from qms_spc_event_act where event_id = ?",
                String.class, ev);
    }

    long picks(long ev) {
        return accounts.query(comp, """
                select count(*) from qms_spc_event_act_ocap p join qms_spc_event_act a on a.act_id = p.act_id where a.event_id = ?""", Long.class, ev);
    }

    int lockWaiters() {
        return jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()",
                Integer.class);
    }

    /** 시험이 항목 행을 sql 로 잡은 채(커밋 전) 고르기를 보내 기다리는 것을 관찰 → between 실행 → 커밋 → 고르기 응답 */
    MvcResult pickWhileHeld(long ev, long ocapId, String sql, Runnable between) throws Exception {
        try (Connection hold = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = hold.createStatement()) {
            hold.setAutoCommit(false);
            st.execute(sql.replace("?", String.valueOf(ocapId)));
            int before = lockWaiters();
            Future<MvcResult> f = POOL.submit(() -> pick(admin, ev, ocapId));
            for (int i = 0; i < 200 && lockWaiters() <= before; i++) {
                Thread.sleep(50);
            }
            assertThat(lockWaiters()).as("고르기가 항목 잠금(FOR SHARE)을 기다린다").isGreaterThan(before);
            between.run();
            hold.commit();
            return f.get(30, TimeUnit.SECONDS);
        }
    }

    static String code(MvcResult r) throws Exception {
        String body = r.getResponse().getContentAsString();
        return r.getResponse().getStatus() + ":" + (body.contains("\"errors\"") && !JsonPath.<java.util.List<Object>>read(body, "$.errors").isEmpty()
                ? JsonPath.read(body, "$.errors[0].code") : JsonPath.read(body, "$.code"));
    }

    // ------------------------------------------------------------------ 시험

    /** 사용 중지·규칙 변경이 커밋 전일 때 고른 조치는 기다렸다가 바뀐 항목으로 다시 판단 — 거부, 처리 행·사본 없음(옛 상태로 들어가지 않음) */
    @Test
    void 사용중지와_규칙변경이_겹치면_고르기는_기다렸다_거부_처리행_없음() throws Exception {
        long ev = event();
        long a = ocap("WE1", "재측정");
        MvcResult r = pickWhileHeld(ev, a, "update qms_ocap_item set use_yn = 'N', row_ver = row_ver + 1 where ocap_id = ?", () -> { });
        assertThat(code(r)).isEqualTo("400:INACTIVE");
        long b = ocap(null, "측정 방법 재확인");
        MvcResult r2 = pickWhileHeld(ev, b, "update qms_ocap_item set rule_cd = 'WE2', row_ver = row_ver + 1 where ocap_id = ?", () -> { });
        assertThat(code(r2)).isEqualTo("400:OCAP_RULE");
        assertThat(acts(ev)).isEmpty();
        assertThat(picks(ev)).isZero();
    }

    /**
     * 고르기가 항목 잠금을 기다리는 사이 다른 사람이 종결 — 고르기는 시작할 때 본 OPEN 이 아니라 가드(V157)에 막혀 처리 행·사본 없이 409.
     * 응답 코드는 화면 사전 확인과 같은 QMS_SPC_EVENT_STATE(O-a — 관찰 때는 DB 가드의 일반 CONFLICT 였음, 2026-10-27 고침: SpcEventService.act 가 가드 거부를 옮김)
     */
    @Test
    void 고르기가_기다리는_사이_종결되면_409_처리행_없음() throws Exception {
        long ev = event();
        long a = ocap("WE1", "재측정");
        MvcResult r = pickWhileHeld(ev, a, "select 1 from qms_ocap_item where ocap_id = ? for update", () -> runner.call(comp, () -> jdbc.update(
                "insert into qms_spc_event_act (comp_cd, event_id, act_type, note, acted_by) values (hb_current_comp(), ?, 'CLOSE', 'QA 종결', 'closer')", ev)));
        System.out.println("[QO-종결] " + code(r) + " " + r.getResponse().getContentAsString());
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("409:QMS_SPC_EVENT_STATE");
        assertThat(acts(ev)).isEqualTo("CLOSE");
        assertThat(picks(ev)).isZero();
        assertThat(accounts.query(comp, "select status from qms_spc_event_state where event_id = ?", String.class, ev)).isEqualTo("CLOSED");
    }

    /** 사본 원장은 소유자(마이그레이션) 계정의 UPDATE·DELETE 도 트리거가 막는다. 앱 계정은 TRUNCATE 권한 없음. 소유자 TRUNCATE 는 행 트리거 밖(관찰·범위 밖) */
    @Test
    void 사본_원장은_소유자_계정도_고치거나_지우지_못한다() throws Exception {
        long ev = event();
        long a = ocap("WE1", "재측정");
        assertThat(pick(admin, ev, a).getResponse().getStatus()).isEqualTo(200);
        for (String sql : new String[] {"update qms_spc_event_act_ocap set action_text = '고침' where comp_cd = '" + comp + "' returning 1",
                "delete from qms_spc_event_act_ocap where comp_cd = '" + comp + "' returning 1"}) {
            Throwable t = catchThrowable(() -> owner(false, sql));
            assertThat(PgErrors.constraint(t)).as(sql).contains("ck_qms_spc_event_act_ocap_append_only");
        }
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "truncate qms_spc_event_act_ocap")))).contains("42501");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "truncate qms_ocap_item cascade")))).contains("42501");
        assertThat(accounts.query(comp, "select string_agg(action_text, ',') from qms_spc_event_act_ocap", String.class)).isEqualTo("재측정");
    }

    /** B사 사용자가 A사 위반 id 로 고르기 목록·조치 — 404, A사 원장에 아무것도 안 남는다 */
    @Test
    void B사가_A사_위반_id로_고르기_목록과_조치를_보내도_404() throws Exception {
        long ev = event();
        long a = ocap("WE1", "재측정");
        String other = accounts.newComp("QP");
        long bu = accounts.newUser(other, "qp1", PW);
        accounts.grant(other, bu, "QA", "qms-spc", "SEARCH", "SAVE");
        accounts.grant(other, bu, "QA", "qms-ocap", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bu, "qp1");
        assertThat(mvc.perform(get(E + "/" + ev + "/ocaps").with(b)).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(pick(b, ev, a).getResponse().getStatus()).isEqualTo(404);
        assertThat(acts(ev)).isEmpty();
        assertThat(accounts.query(other, "select count(*) from qms_spc_event_act", Long.class)).isZero();
    }
}
