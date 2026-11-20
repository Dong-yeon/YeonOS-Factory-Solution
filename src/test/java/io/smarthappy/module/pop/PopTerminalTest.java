package io.smarthappy.module.pop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.master.workforce.WorkerService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * POP 기반 (P2 5a, docs/25 4.6) — 단말 등록·활성화(등록 코드 한 번·해시만)·다시 발급 = 기존 키 무효·폐기 즉시 끊김,
 * 등록 코드 시도 제한(IP), 배지 전환·PIN(5회 → 잠금)·틀린 배지 시도 제한·비활동 내려감·두 단말 같은 작업자,
 * 작업장 홈(라인 + 아래 셀, 발행 지시만, 자격 게이트 미리보기), 체인 분리(사무 ↔ 단말)·CSRF, A사로 B사, 직접 SQL.
 */
@AutoConfigureMockMvc
class PopTerminalTest extends PostgresTestBase {

    private static final String PW = "Harbor-violet-58";
    private static final String T = "/api/production/terminals";
    private static final String P = "/api/pop";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    PolicyService policies;

    @Autowired
    PopAuthService popAuth;

    String comp;
    long userId;
    RequestPostProcessor admin;
    long line1;
    long cell1;
    long line2;
    long workerId;
    String today;
    /** 시험마다 다른 IP — 등록 코드 시도 제한이 시험끼리 섞이지 않게 */
    String ip;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PT");
        ip = "10.77." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "ADM", "prd-wo", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "ADM", "mst-worker", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        long area = node("P1", null, "AREA", "A1");
        line1 = node("P1", area, "LINE", "L1");
        cell1 = node("P1", line1, "CELL", "C1");
        line2 = node("P1", area, "LINE", "L2");
        workerId = worker("E1", "홍작업", "BADGE-0001");
        // 지시: FG — 10 SPOT(셀 C1) · 20 INSP(라인 L2). SPOT 은 자격 WELD 필요(WARN)
        for (String[] i : new String[][] {{"FG", "EA"}, {"RM", "KG"}}) {
            accounts.exec(comp, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                    values (?, ?, ?, 'FG', 'MAKE', ?, 't', 't')""", comp, i[0], i[0] + " 품목", i[1]);
        }
        accounts.exec(comp, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'SPOT', '스폿 용접', 'WELD', 't', 't'), (?, 'INSP', '검사', 'INSPECT', 't', 't')""", comp, comp);
        long rid = accounts.query(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                returning routing_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'SPOT'), ?, 30, 't', 't'),
                       (?, ?, 20, (select proc_id from mst_proc where proc_cd = 'INSP'), ?, 20, 't', 't')""",
                comp, rid, cell1, comp, rid, line2);
        accounts.exec(comp, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        long bom = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, base_qty, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 1, 't', 't')
                returning bom_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, issue_method, created_by, updated_by)
                values (?, ?, 10, (select item_id from mst_item where item_cd = 'RM'), 1.5, 'KG', 'NORMAL', 'BACKFLUSH', 't', 't')""",
                comp, bom);
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", bom);
        accounts.exec(comp, "insert into mst_qual (comp_cd, qual_cd, qual_nm, created_by, updated_by) values (?, 'WELD', '용접 1급', 't', 't')", comp);
        accounts.exec(comp, """
                insert into mst_proc_qual_req (comp_cd, proc_id, qual_id, gate_mode, created_by, updated_by)
                values (?, (select proc_id from mst_proc where proc_cd = 'SPOT'), (select qual_id from mst_qual where qual_cd = 'WELD'),
                        'WARN', 't', 't')""", comp);
        long released = id(postJson(admin, "/api/production/wos",
                "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"120\",\"planStart\":\"" + today + "\"}"));
        postJson(admin, "/api/production/wos/" + released + "/release", "{\"rowVer\":0}").andExpect(status().isOk());
        // 계획(발행 전) 지시는 홈에 안 보인다
        id(postJson(admin, "/api/production/wos", "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"7\",\"planStart\":\"" + today + "\"}"));
    }

    @AfterEach
    void restoreClock() {
        popAuth.setClock(Clock.systemUTC());
    }

    // ---------- 도우미 ----------

    private long node(String plant, Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, plant, parent, type, cd, cd + " 작업장");
    }

    private long worker(String cd, String nm, String badge) {
        return accounts.query(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, ?, ?, 'P1', ?, 't', 't') returning worker_id""", Long.class, comp, cd, nm,
                WorkerService.badgeHash(comp, badge));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions postJson(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(who));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private RequestPostProcessor from(String addr) {
        return r -> {
            r.setRemoteAddr(addr);
            return r;
        };
    }

    /** 단말 등록 → [단말 id, 등록 코드] */
    private Object[] register(RequestPostProcessor who, String cd, Long nodeId) throws Exception {
        MvcResult m = postJson(who, T, "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd + " 태블릿\",\"plantCd\":\"P1\""
                + (nodeId == null ? "" : ",\"nodeId\":" + nodeId) + "}").andExpect(status().isOk()).andReturn();
        String body = m.getResponse().getContentAsString();
        return new Object[] {((Number) JsonPath.read(body, "$.data.terminalId")).longValue(), JsonPath.read(body, "$.data.code")};
    }

    private ResultActions activate(String compCd, String code) throws Exception {
        return mvc.perform(json(post(P + "/activate"), "{\"compCd\":\"" + compCd + "\",\"code\":\"" + code + "\"}").with(from(ip)));
    }

    /** 활성화 → 단말 쿠키 값 */
    private String cookieOf(String compCd, String code) throws Exception {
        MvcResult m = activate(compCd, code).andExpect(status().isOk()).andReturn();
        String set = m.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        assertThat(set).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/").contains("Max-Age=" + 400L * 24 * 3600);
        return set.substring(set.indexOf('=') + 1, set.indexOf(';'));
    }

    private static Cookie terminal(String value) {
        return new Cookie(PopAuthService.COOKIE, value);
    }

    private ResultActions popGet(String cookie, MockHttpSession session, String path) throws Exception {
        MockHttpServletRequestBuilder b = get(P + path).cookie(terminal(cookie));
        return mvc.perform(session == null ? b : b.session(session));
    }

    private ResultActions badge(String cookie, MockHttpSession session, String badge, String pin) throws Exception {
        return mvc.perform(json(post(P + "/badge"), "{\"badge\":\"" + badge + "\"" + (pin == null ? "" : ",\"pin\":\"" + pin + "\"") + "}")
                .cookie(terminal(cookie)).session(session));
    }

    private int termVer(long id) {
        return accounts.query(comp, "select row_ver from sys_terminal where terminal_id = ?", Integer.class, id);
    }

    private int workerVer(long id) {
        return accounts.query(comp, "select row_ver from mst_worker where worker_id = ?", Integer.class, id);
    }

    // ---------- 등록·활성화·홈 ----------

    @Test
    void 등록_활성화_배지_작업장_홈() throws Exception {
        Object[] t = register(admin, "TAB-01", line1);
        long tid = (long) t[0];
        String code = (String) t[1];
        assertThat(code).matches("^[A-HJ-NP-Z2-9]{5}-[A-HJ-NP-Z2-9]{5}$");
        // 해시만 저장(코드 원문 없음), 아직 키 없음
        assertThat(accounts.query(comp, "select activation_hash from sys_terminal where terminal_id = ?", String.class, tid))
                .matches("^[0-9a-f]{64}$").doesNotContain(code.replace("-", ""));
        mvc.perform(get(T).with(admin)).andExpect(jsonPath("$.data[0].keyIssued").value(false))
                .andExpect(jsonPath("$.data[0].nodeCd").value("L1"));

        // 사람이 친 코드(소문자·공백) 도 받는다. 쿠키는 HttpOnly·SameSite=Strict
        String cookie = cookieOf(comp.toLowerCase(), " " + code.toLowerCase().replace("-", " ") + " ");
        String[] parts = cookie.split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(parts[0]).isEqualTo(comp);
        assertThat(accounts.query(comp, "select key_hash from sys_terminal where terminal_id = ?", String.class, tid))
                .isEqualTo(TerminalService.sha256(parts[2]));
        assertThat(accounts.query(comp, "select count(*) from sys_terminal where terminal_id = ? and activation_hash is null"
                + " and activated_at is not null", Integer.class, tid)).isEqualTo(1);
        // 등록 코드는 한 번만
        activate(comp, code).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TERMINAL_ACTIVATION_FAILED"));

        MockHttpSession s = new MockHttpSession();
        popGet(cookie, s, "/session").andExpect(status().isOk()).andExpect(jsonPath("$.data.terminalCd").value("TAB-01"))
                .andExpect(jsonPath("$.data.nodeCd").value("L1")).andExpect(jsonPath("$.data.workDay").value(today))
                .andExpect(jsonPath("$.data.workerCd").doesNotExist()).andExpect(jsonPath("$.data.pinRequired").value(false))
                .andExpect(jsonPath("$.data.idleMinutes").value(30));
        // 작업자 없으면 할 일 없음
        popGet(cookie, s, "/home").andExpect(jsonPath("$.data.tasks.length()").value(0));
        badge(cookie, s, "BADGE-0001", null).andExpect(status().isOk()).andExpect(jsonPath("$.data.needPin").value(false))
                .andExpect(jsonPath("$.data.workerNm").value("홍작업"));
        // 라인 L1 단말 → 아래 셀 C1 의 SPOT 만(L2 의 INSP·계획 지시 제외), 수량은 글자, 자격 없음 → WARN 미리보기
        popGet(cookie, s, "/home").andExpect(jsonPath("$.data.session.workerCd").value("E1"))
                .andExpect(jsonPath("$.data.tasks.length()").value(1))
                .andExpect(jsonPath("$.data.tasks[0].procCd").value("SPOT"))
                .andExpect(jsonPath("$.data.tasks[0].nodeCd").value("C1"))
                .andExpect(jsonPath("$.data.tasks[0].planQty").value("120"))
                .andExpect(jsonPath("$.data.tasks[0].gate.mode").value("WARN"))
                .andExpect(jsonPath("$.data.tasks[0].gate.missing[0].qualCd").value("WELD"));
        // 자격을 주면 OK
        accounts.exec(comp, """
                insert into mst_worker_qual (comp_cd, worker_id, qual_id, acquired_on, created_by, updated_by)
                values (?, ?, (select qual_id from mst_qual where qual_cd = 'WELD'), (now() at time zone 'Asia/Seoul')::date - 10, 't', 't')""", comp, workerId);
        popGet(cookie, s, "/home").andExpect(jsonPath("$.data.tasks[0].gate.mode").value("OK"));
        // 작업장 없는 단말 = 공장 전체(SPOT·INSP 둘 다)
        String whole = cookieOf(comp, (String) register(admin, "TAB-02", null)[1]);
        MockHttpSession s2 = new MockHttpSession();
        badge(whole, s2, "BADGE-0001", null).andExpect(status().isOk());
        popGet(whole, s2, "/home").andExpect(jsonPath("$.data.tasks[*].procCd").value(Matchers.contains("SPOT", "INSP")));
        // 두 단말에서 같은 작업자 — 서로 영향 없음
        popGet(cookie, s, "/session").andExpect(jsonPath("$.data.workerCd").value("E1"));
        // 작업자 내리기 → 단말은 그대로
        mvc.perform(json(post(P + "/worker/logout"), "{}").cookie(terminal(cookie)).session(s)).andExpect(status().isOk());
        popGet(cookie, s, "/session").andExpect(status().isOk()).andExpect(jsonPath("$.data.workerCd").doesNotExist());
        popGet(whole, s2, "/session").andExpect(jsonPath("$.data.workerCd").value("E1"));
        // 접속 로그: 활성화·배지 성공, client_type 은 헤더(참고값) 없으면 WEB
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'TERMINAL_ACTIVATED'", Integer.class))
                .isEqualTo(2);
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'BADGE_OK' and login_id = ?",
                Integer.class, "t" + tid + ":TAB-01")).isEqualTo(1);
        // 언어 1탭 — 단말 세션에만
        mvc.perform(json(post(P + "/lang"), "{\"lang\":\"en\"}").cookie(terminal(cookie)).session(s)).andExpect(status().isOk());
        popGet(cookie, s, "/session").andExpect(jsonPath("$.data.lang").value("en"));
        mvc.perform(json(post(P + "/lang"), "{\"lang\":\"vi\"}").cookie(terminal(cookie)).session(s)).andExpect(status().isBadRequest());
    }

    @Test
    void 입력_검증_권한() throws Exception {
        postJson(admin, T, "{\"terminalCd\":\"tab 01\",\"terminalNm\":\" \",\"plantCd\":\"PX\",\"terminalType\":\"PC\"}")
                .andExpect(jsonPath("$.errors[*].field").value(Matchers.contains("terminalCd", "terminalNm", "plantCd", "terminalType")));
        // 작업장은 그 공장의 사용 중 라인·셀만 (구역·다른 공장)
        long area = accounts.query(comp, "select node_id from mst_equip_hier where node_cd = 'A1'", Long.class);
        postJson(admin, T, "{\"terminalCd\":\"TAB-09\",\"terminalNm\":\"x\",\"plantCd\":\"P1\",\"nodeId\":" + area + "}")
                .andExpect(jsonPath("$.errors[0].field").value("nodeId")).andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        register(admin, "TAB-01", cell1);
        postJson(admin, T, "{\"terminalCd\":\"tab-01\",\"terminalNm\":\"x\",\"plantCd\":\"P1\"}")
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        mvc.perform(get(T + "/nodes").param("plantCd", "P1").with(admin))
                .andExpect(jsonPath("$.data[*].nodeCd").value(Matchers.contains("C1", "L1", "L2")));
        // 조회만 → 저장·다시 발급·폐기 403
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "sys-terminal", "SEARCH");
        RequestPostProcessor r = accounts.as(comp, reader, "reader1");
        mvc.perform(get(T).with(r)).andExpect(status().isOk());
        postJson(r, T, "{\"terminalCd\":\"TAB-05\",\"terminalNm\":\"x\",\"plantCd\":\"P1\"}").andExpect(status().isForbidden());
        long tid = accounts.query(comp, "select terminal_id from sys_terminal where terminal_cd = 'TAB-01'", Long.class);
        postJson(r, T + "/" + tid + "/reissue", "{\"rowVer\":0}").andExpect(status().isForbidden());
        postJson(r, T + "/" + tid + "/revoke", "{\"reason\":\"x\",\"rowVer\":0}").andExpect(status().isForbidden());
        // 이름 수정(코드는 그대로), 낡은 rowVer → 409
        mvc.perform(json(put(T + "/" + tid), "{\"terminalNm\":\"1라인 태블릿\",\"plantCd\":\"P1\",\"nodeId\":" + line1 + ",\"rowVer\":0}")
                .with(admin)).andExpect(status().isOk());
        mvc.perform(json(put(T + "/" + tid), "{\"terminalNm\":\"x\",\"plantCd\":\"P1\",\"rowVer\":0}").with(admin))
                .andExpect(status().isConflict());
        mvc.perform(get(T + "/" + tid).with(admin)).andExpect(jsonPath("$.data.terminalCd").value("TAB-01"))
                .andExpect(jsonPath("$.data.nodeCd").value("L1"));
    }

    @Test
    void 틀린_등록_코드는_IP_기준으로_막힌다() throws Exception {
        String code = (String) register(admin, "TAB-01", line1)[1];
        int blockedAt = -1;
        for (int i = 0; i < 30; i++) {
            int st = activate(comp, "ABCDE-FGHJK").andReturn().getResponse().getStatus();
            if (st == 429) {
                blockedAt = i;
                break;
            }
            assertThat(st).isEqualTo(400);
        }
        assertThat(blockedAt).as("시도 제한").isPositive();
        // 막힌 동안은 맞는 코드도 안 된다 → 다른 IP 에서는 된다
        activate(comp, code).andExpect(status().isTooManyRequests());
        ip = "10.78.1.1";
        cookieOf(comp, code);
        // 없는 회사·형식 아닌 회사·형식 아닌 코드 — 같은 오류
        ip = "10.78.1.2";
        activate("NOPE", code).andExpect(jsonPath("$.code").value("TERMINAL_ACTIVATION_FAILED"));
        activate("bad comp!", code).andExpect(jsonPath("$.code").value("TERMINAL_ACTIVATION_FAILED"));
        activate(comp, "0O1I").andExpect(jsonPath("$.code").value("TERMINAL_ACTIVATION_FAILED"));
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'TERMINAL_DENIED'", Integer.class))
                .isGreaterThanOrEqualTo(blockedAt);
    }

    @Test
    void 만료된_등록_코드는_안_된다() throws Exception {
        Object[] t = register(admin, "TAB-01", line1);
        accounts.exec(comp, "update sys_terminal set activation_expires_at = now() - interval '1 second' where terminal_id = ?", t[0]);
        activate(comp, (String) t[1]).andExpect(jsonPath("$.code").value("TERMINAL_ACTIVATION_FAILED"));
        mvc.perform(get(T).with(admin)).andExpect(jsonPath("$.data[0].keyIssued").value(false));
    }

    // ---------- 다시 발급·폐기·회사 정지 ----------

    @Test
    void 다시_발급하면_기존_키가_끊기고_폐기는_되돌리지_않는다() throws Exception {
        Object[] t = register(admin, "TAB-01", line1);
        long tid = (long) t[0];
        String old = cookieOf(comp, (String) t[1]);
        popGet(old, null, "/session").andExpect(status().isOk());

        MvcResult m = postJson(admin, T + "/" + tid + "/reissue", "{\"rowVer\":" + termVer(tid) + "}").andExpect(status().isOk()).andReturn();
        String code2 = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        // 기존 키 → 401 + 쿠키 지움
        popGet(old, null, "/session").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TERMINAL_UNAUTHENTICATED"))
                .andExpect(header().stringValues("Set-Cookie", Matchers.hasItem(Matchers.startsWith(PopAuthService.COOKIE + "=;"))));
        String fresh = cookieOf(comp, code2);
        popGet(fresh, null, "/session").andExpect(status().isOk());
        // 낡은 키 한 글자 바꾸기·남의 단말 id 붙이기도 401
        String[] p = fresh.split("\\.");
        popGet(p[0] + "." + p[1] + "." + p[2].substring(1) + "A", null, "/session").andExpect(status().isUnauthorized());
        popGet("garbage", null, "/session").andExpect(status().isUnauthorized());
        popGet(p[0] + ".99999999." + p[2], null, "/session").andExpect(status().isUnauthorized());

        // 회사 정지·공장 사용 중지 → 끊김, 되돌리면 다시
        accounts.setCompStatus(comp, "SUSPENDED");
        popGet(fresh, null, "/session").andExpect(status().isUnauthorized());
        accounts.setCompStatus(comp, "ACTIVE");
        accounts.exec(comp, "update sys_plant set use_yn = 'N' where plant_cd = 'P1'");
        popGet(fresh, null, "/session").andExpect(status().isUnauthorized());
        accounts.exec(comp, "update sys_plant set use_yn = 'Y' where plant_cd = 'P1'");
        popGet(fresh, null, "/session").andExpect(status().isOk());

        // 폐기: 사유 필수 → 즉시 끊김 → 다시 발급·수정 불가
        postJson(admin, T + "/" + tid + "/revoke", "{\"reason\":\" \",\"rowVer\":" + termVer(tid) + "}")
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        postJson(admin, T + "/" + tid + "/revoke", "{\"reason\":\"\u200B\uFEFF\u3000\",\"rowVer\":" + termVer(tid) + "}")   // QA R4-a
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        postJson(admin, T + "/" + tid + "/revoke", "{\"reason\":\"분실\",\"rowVer\":" + termVer(tid) + "}").andExpect(status().isOk());
        popGet(fresh, null, "/session").andExpect(status().isUnauthorized());
        postJson(admin, T + "/" + tid + "/reissue", "{\"rowVer\":" + termVer(tid) + "}").andExpect(status().isConflict());
        mvc.perform(json(put(T + "/" + tid), "{\"terminalNm\":\"x\",\"plantCd\":\"P1\",\"rowVer\":" + termVer(tid) + "}").with(admin))
                .andExpect(status().isConflict());
        mvc.perform(get(T + "/" + tid).with(admin)).andExpect(jsonPath("$.data.revokeReason").value("분실"))
                .andExpect(jsonPath("$.data.keyIssued").value(false));
        // 직접 SQL: 폐기 행은 잠김, 지울 수 없음, 키·코드는 남아 있지 않다
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update sys_terminal set revoked_at = null, revoke_reason = null where terminal_id = ?", tid))))
                .contains("ck_sys_terminal_revoked");
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from sys_terminal where terminal_id = ?", tid))).isNotNull();
        assertThat(accounts.query(comp, "select count(*) from sys_terminal where terminal_id = ? and key_hash is null"
                + " and activation_hash is null", Integer.class, tid)).isEqualTo(1);
        // 감사: 해시는 가린다
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'sys_terminal' and row_key = ?",
                Integer.class, String.valueOf(tid))).isGreaterThanOrEqualTo(4);
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'sys_terminal'"
                + " and (coalesce(before_data::text, '') || coalesce(after_data::text, '')) like ?", Integer.class,
                "%" + TerminalService.sha256(p[2]) + "%")).isZero();
    }

    // ---------- 배지·PIN ----------

    @Test
    void 배지_PIN_잠금과_다시_지정() throws Exception {
        String cookie = cookieOf(comp, (String) register(admin, "TAB-01", line1)[1]);
        MockHttpSession s = new MockHttpSession();
        // 없는 배지·사용 중지 작업자 → 같은 오류
        badge(cookie, s, "NO-SUCH", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BADGE_UNKNOWN"));
        long off = worker("E9", "중지", "BADGE-0009");
        accounts.exec(comp, "update mst_worker set use_yn = 'N' where worker_id = ?", off);
        badge(cookie, s, "BADGE-0009", null).andExpect(jsonPath("$.code").value("BADGE_UNKNOWN"));
        badge(cookie, s, "x", null).andExpect(jsonPath("$.code").value("BADGE_UNKNOWN"));

        runner.run(comp, () -> policies.set(PopAuthService.PIN_POLICY, "P1", "Y", "t"));
        popGet(cookie, s, "/session").andExpect(jsonPath("$.data.pinRequired").value(true));
        // PIN 없음 → 안내
        badge(cookie, s, "BADGE-0001", null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PIN_NOT_SET"));
        // PIN 지정(사무): 형식 검사
        mvc.perform(json(put("/api/master/workers/" + workerId + "/pin"), "{\"pin\":\"12a4\",\"rowVer\":" + workerVer(workerId) + "}")
                .with(admin)).andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        mvc.perform(json(put("/api/master/workers/" + workerId + "/pin"), "{\"pin\":\"123\",\"rowVer\":" + workerVer(workerId) + "}")
                .with(admin)).andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        mvc.perform(json(put("/api/master/workers/" + workerId + "/pin"), "{\"pin\":\"2468\",\"rowVer\":" + workerVer(workerId) + "}")
                .with(admin)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select pin_hash from mst_worker where worker_id = ?", String.class, workerId))
                .startsWith("{bcrypt}").doesNotContain("2468");
        mvc.perform(get("/api/master/workers/" + workerId).with(admin)).andExpect(jsonPath("$.data.pinSet").value(true));

        // 배지 → PIN 필요(작업자는 아직 안 바뀜)
        badge(cookie, s, "BADGE-0001", null).andExpect(jsonPath("$.data.needPin").value(true))
                .andExpect(jsonPath("$.data.workerNm").value("홍작업"));
        popGet(cookie, s, "/session").andExpect(jsonPath("$.data.workerCd").doesNotExist());
        for (int i = 1; i <= 4; i++) {
            badge(cookie, s, "BADGE-0001", "1111").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PIN_WRONG"))
                    .andExpect(jsonPath("$.message").value(Matchers.containsString(String.valueOf(5 - i))));
        }
        // 5번째 → 잠김(423), 잠긴 동안은 맞는 PIN 도 안 된다. 틀린 횟수는 오류 응답(롤백)과 관계없이 쌓였다
        badge(cookie, s, "BADGE-0001", "1111").andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("PIN_LOCKED"));
        badge(cookie, s, "BADGE-0001", "2468").andExpect(status().isLocked());
        assertThat(accounts.query(comp, "select count(*) from mst_worker where worker_id = ? and pin_locked_until > now() + interval '14 minutes'",
                Integer.class, workerId)).isEqualTo(1);
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'PIN_LOCKED'", Integer.class)).isEqualTo(1);
        popGet(cookie, s, "/session").andExpect(jsonPath("$.data.workerCd").doesNotExist());
        // 관리자가 PIN 을 다시 지정 → 잠금 풀림
        mvc.perform(json(put("/api/master/workers/" + workerId + "/pin"), "{\"pin\":\"97531\",\"rowVer\":" + workerVer(workerId) + "}")
                .with(admin)).andExpect(status().isOk());
        String sid = s.getId();
        badge(cookie, s, "BADGE-0001", "97531").andExpect(status().isOk()).andExpect(jsonPath("$.data.needPin").value(false));
        assertThat(s.getId()).as("작업자가 바뀌면 세션 ID 도 바뀐다").isNotEqualTo(sid);
        popGet(cookie, s, "/session").andExpect(jsonPath("$.data.workerCd").value("E1"));
        assertThat(accounts.query(comp, "select pin_fail_cnt from mst_worker where worker_id = ?", Integer.class, workerId)).isZero();
        // PIN 지우기 → PIN 공장에서는 못 들어온다. 공장 정책을 끄면 배지만으로
        mvc.perform(json(put("/api/master/workers/" + workerId + "/pin"), "{\"pin\":\"\",\"rowVer\":" + workerVer(workerId) + "}")
                .with(admin)).andExpect(status().isOk());
        badge(cookie, s, "BADGE-0001", "97531").andExpect(jsonPath("$.code").value("PIN_NOT_SET"));
        runner.run(comp, () -> policies.set(PopAuthService.PIN_POLICY, "P1", "N", "t"));
        badge(cookie, s, "BADGE-0001", null).andExpect(status().isOk());
    }

    @Test
    void 틀린_배지가_많으면_단말이_잠시_막힌다() throws Exception {
        String cookie = cookieOf(comp, (String) register(admin, "TAB-01", line1)[1]);
        String other = cookieOf(comp, (String) register(admin, "TAB-02", line1)[1]);
        MockHttpSession s = new MockHttpSession();
        for (int i = 0; i < PopAuthService.BADGE_MAX_FAIL; i++) {
            badge(cookie, s, "WRONG-" + i, null).andExpect(jsonPath("$.code").value("BADGE_UNKNOWN"));
        }
        badge(cookie, s, "BADGE-0001", null).andExpect(status().isTooManyRequests());
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'BADGE_THROTTLED'", Integer.class)).isEqualTo(1);
        // 다른 단말은 영향 없음
        badge(other, new MockHttpSession(), "BADGE-0001", null).andExpect(status().isOk());
        // 1분 뒤 풀림
        popAuth.setClock(Clock.offset(Clock.systemUTC(), Duration.ofSeconds(61)));
        badge(cookie, s, "BADGE-0001", null).andExpect(status().isOk());
    }

    @Test
    void 비활동이면_작업자만_내려간다() throws Exception {
        String cookie = cookieOf(comp, (String) register(admin, "TAB-01", line1)[1]);
        MockHttpSession s = new MockHttpSession();
        badge(cookie, s, "BADGE-0001", null).andExpect(status().isOk());
        Instant base = Instant.now();
        popAuth.setClock(Clock.fixed(base.plus(Duration.ofMinutes(29)), ZoneOffset.UTC));
        popGet(cookie, s, "/session").andExpect(jsonPath("$.data.workerCd").value("E1"));
        // 29분에 한 번 썼으니 거기서 30분 + 1분 뒤에 내려간다
        popAuth.setClock(Clock.fixed(base.plus(Duration.ofMinutes(29 + 31)), ZoneOffset.UTC));
        popGet(cookie, s, "/session").andExpect(status().isOk()).andExpect(jsonPath("$.data.workerCd").doesNotExist());
        // 사무에서 작업자를 사용 중지하면 다음 요청에서 내려간다
        popAuth.setClock(Clock.systemUTC());
        badge(cookie, s, "BADGE-0001", null).andExpect(status().isOk());
        accounts.exec(comp, "update mst_worker set use_yn = 'N' where worker_id = ?", workerId);
        popGet(cookie, s, "/home").andExpect(jsonPath("$.data.session.workerCd").doesNotExist())
                .andExpect(jsonPath("$.data.tasks.length()").value(0));
    }

    // ---------- 체인 분리·CSRF ----------

    @Test
    void 사무_사용자와_단말은_서로의_API_를_못_쓴다() throws Exception {
        String cookie = cookieOf(comp, (String) register(admin, "TAB-01", line1)[1]);
        // 사무 사용자(ROLE_USER) → POP API·화면 불가
        int st = mvc.perform(get(P + "/session").with(admin)).andReturn().getResponse().getStatus();
        assertThat(st).isIn(401, 403);
        // 단말 쿠키 → 사무 API·화면 불가
        mvc.perform(get(T).cookie(terminal(cookie))).andExpect(status().isUnauthorized());
        mvc.perform(get("/main").cookie(terminal(cookie))).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        // POP 화면: 쿠키 없으면 활성화 화면으로, 활성화 화면은 누구나
        mvc.perform(get("/pop")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/pop/activate"));
        mvc.perform(get("/pop").cookie(terminal(cookie))).andExpect(status().isOk());
        mvc.perform(get("/pop/activate")).andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", Matchers.containsString("script-src 'self'")));
        mvc.perform(get(P + "/home")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TERMINAL_UNAUTHENTICATED"));
        // CSRF: 토큰 없으면 403(배지·활성화 모두)
        mvc.perform(post(P + "/badge").contentType(MediaType.APPLICATION_JSON).content("{\"badge\":\"BADGE-0001\"}")
                .cookie(terminal(cookie))).andExpect(status().isForbidden());
        mvc.perform(post(P + "/activate").contentType(MediaType.APPLICATION_JSON).content("{\"compCd\":\"X\",\"code\":\"Y\"}"))
                .andExpect(status().isForbidden());
    }

    // ---------- A사로 B사 ----------

    @Test
    void A사로_B사_단말_작업자에_닿지_못한다() throws Exception {
        Object[] t = register(admin, "TAB-01", line1);
        long tid = (long) t[0];
        String cookieA = cookieOf(comp, (String) t[1]);
        String keyA = cookieA.split("\\.")[2];

        String other = accounts.newComp("PU");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "ADM", "sys-terminal", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        accounts.addPlant(other, "P1", "B 1공장");
        // 목록·단건·다시 발급·폐기
        mvc.perform(get(T).with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(T + "/" + tid).with(b)).andExpect(status().isNotFound());
        postJson(b, T + "/" + tid + "/reissue", "{\"rowVer\":1}").andExpect(status().isNotFound());
        postJson(b, T + "/" + tid + "/revoke", "{\"reason\":\"x\",\"rowVer\":1}").andExpect(status().isNotFound());
        mvc.perform(get(T + "/nodes").param("plantCd", "P1").with(b)).andExpect(jsonPath("$.data.length()").value(0));
        // A사 작업장으로 B사 단말 → 없는 코드
        postJson(b, T, "{\"terminalCd\":\"TAB-01\",\"terminalNm\":\"x\",\"plantCd\":\"P1\",\"nodeId\":" + line1 + "}")
                .andExpect(jsonPath("$.errors[0].field").value("nodeId"));
        // 쿠키의 회사만 바꾸기 → B사 범위에서 A사 단말은 안 보인다
        popGet(other + "." + tid + "." + keyA, null, "/session").andExpect(status().isUnauthorized());
        // B사 등록 코드를 A사 회사 코드로 → 실패
        String codeB = (String) register(b, "TAB-B", null)[1];
        activate(comp, codeB).andExpect(jsonPath("$.code").value("TERMINAL_ACTIVATION_FAILED"));
        String cookieB = cookieOf(other, codeB);
        // B사 작업자 배지(같은 값)로 A사 단말 → 없는 배지, A사 배지로 B사 단말 → 없는 배지
        accounts.exec(other, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'B1', 'B작업', 'P1', ?, 't', 't')""", other, WorkerService.badgeHash(other, "BADGE-B"));
        badge(cookieA, new MockHttpSession(), "BADGE-B", null).andExpect(jsonPath("$.code").value("BADGE_UNKNOWN"));
        badge(cookieB, new MockHttpSession(), "BADGE-0001", null).andExpect(jsonPath("$.code").value("BADGE_UNKNOWN"));
        // A사 세션의 작업자를 B사 단말 쿠키로 → 다른 단말 세션은 작업자 없음
        MockHttpSession s = new MockHttpSession();
        badge(cookieA, s, "BADGE-0001", null).andExpect(status().isOk());
        popGet(cookieB, s, "/session").andExpect(jsonPath("$.data.terminalCd").value("TAB-B"))
                .andExpect(jsonPath("$.data.workerCd").doesNotExist());
        popGet(cookieB, s, "/home").andExpect(jsonPath("$.data.tasks.length()").value(0));
        // 직접 SQL(B사 범위): A사 행 갱신 0건, A사 회사로 넣기 RLS, A사 작업장 가리키기
        assertThat(accounts.exec(other, "update sys_terminal set terminal_nm = 'x' where terminal_id = ?", tid)).isZero();
        assertThat(PgErrors.isRlsViolation(catchThrowable(() -> accounts.exec(other, """
                insert into sys_terminal (comp_cd, terminal_cd, terminal_nm, plant_cd, created_by, updated_by)
                values (?, 'X1', 'x', 'P1', 't', 't')""", comp)))).isTrue();
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into sys_terminal (comp_cd, terminal_cd, terminal_nm, plant_cd, node_id, created_by, updated_by)
                values (?, 'X2', 'x', 'P1', ?, 't', 't')""", other, line1)))).contains("fk_sys_terminal_node_plant");
    }

    @Test
    void 직접_SQL_규칙() throws Exception {
        long tid = (long) register(admin, "TAB-01", line1)[0];
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update sys_terminal set terminal_cd = 'TAB-99' where terminal_id = ?", tid)))).contains("ck_sys_terminal_fixed");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update sys_terminal set revoked_at = now() where terminal_id = ?", tid)))).contains("ck_sys_terminal_revoke");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update sys_terminal set revoked_at = now(), revoke_reason = 'x' where terminal_id = ?", tid))))
                .as("폐기하면서 키·코드를 남길 수 없다").contains("ck_sys_terminal_revoke");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update sys_terminal set activation_hash = 'nothex' where terminal_id = ?", tid)))).contains("ck_sys_terminal_hash");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update sys_terminal set activation_expires_at = null where terminal_id = ?", tid)))).contains("ck_sys_terminal_activation");
        long wh = node("P1", null, "WH", "W1");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update sys_terminal set node_id = ? where terminal_id = ?", wh, tid)))).contains("fk_sys_terminal_node_plant");
        long area = accounts.query(comp, "select node_id from mst_equip_hier where node_cd = 'A1'", Long.class);
        long line3 = node("P1", area, "LINE", "L3");
        accounts.exec(comp, "update mst_equip_hier set use_yn = 'N' where node_id = ?", line3);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update sys_terminal set node_id = ? where terminal_id = ?", line3, tid)))).contains("fk_sys_terminal_node_plant");
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from sys_terminal where terminal_id = ?", tid))).isNotNull();
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_worker set pin_fail_cnt = -1 where worker_id = ?", workerId)))).contains("ck_mst_worker_pin_fail");
    }
}
