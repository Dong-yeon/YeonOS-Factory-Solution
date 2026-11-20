package io.smarthappy.module.pop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
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
 * POP 감독자 승인 (docs/25 4.37, 결정 P1~P6) — 정책 끔 = 지금 그대로, 켬 = 토큰 없으면 403, 감독자(자격 정책) 배지 + PIN → 토큰 → 정정 +
 * 승인 원장. 감독자 아님·자격 만료·본인·없는 배지·PIN 틀림·잠김·PIN 없음, 단말 배지 제한, 토큰 범위(다른 대상·다른 동작·만료·다른 단말·작업자
 * 바뀜·한 번), 동시 정정, A사로 B사, 직접 SQL(원장 UPDATE/DELETE·자기 승인·다른 회사 작업자), PDA 403.
 */
@AutoConfigureMockMvc
class PopApprovalTest extends PostgresTestBase {

    private static final String PW = "Juniper-signal-35";
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
    PasswordEncoder encoder;

    @Autowired
    PopAuthService auth;

    String comp;
    RequestPostProcessor admin;
    String ip;
    long line1;
    long proc;
    long operator;
    long sup;
    long qualId;
    String cookie;
    MockHttpSession s;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PA");
        ip = "10.87." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, u, "ADM", "prd-wo", "SEARCH", "SAVE");
        accounts.grant(comp, u, "ADM", "prd-result", "SEARCH", "EXCEL");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        long area = accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', null, 'AREA', 'A1', 'A1 구역', 't', 't') returning node_id""", Long.class, comp);
        line1 = accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, 'LINE', 'L1', 'L1 라인', 't', 't') returning node_id""", Long.class, comp, area);
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', 'FG 품목', 'FG', 'MAKE', 'EA', 't', 't')""", comp);
        accounts.exec(comp, "insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by) values (?, 'ASSY', '조립', 'ASSEMBLY', 't', 't')", comp);
        long rid = accounts.query(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                returning routing_id""", Long.class, comp);
        long step = accounts.query(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'ASSY'), ?, 30, 't', 't') returning step_id""",
                Long.class, comp, rid, line1);
        accounts.exec(comp, """
                insert into mst_routing_param (comp_cd, step_id, param_cd, param_nm, unit_nm, target_val, lsl, usl, sort_no, created_by, updated_by)
                values (?, ?, 'TORQUE', '토크', 'Nm', 9.0, 8.5, 9.5, 10, 't', 't')""", comp, step);
        accounts.exec(comp, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        MvcResult wo = mvc.perform(json(post("/api/production/wos"),
                "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"100\",\"planStart\":\"" + today + "\"}").with(admin))
                .andExpect(status().isOk()).andReturn();
        long woId = ((Number) JsonPath.read(wo.getResponse().getContentAsString(), "$.data")).longValue();
        mvc.perform(json(post("/api/production/wos/" + woId + "/release"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        proc = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ?", Long.class, woId);
        // 작업자: 조작자 E1(PIN 1111), 감독자 S1(자격 SUP 유효, PIN 2468), 자격 없는 W2, 자격 만료 S2, PIN 없는 감독자 S3
        qualId = accounts.query(comp, "insert into mst_qual (comp_cd, qual_cd, qual_nm, created_by, updated_by) values (?, 'SUP', '감독자', 't', 't') returning qual_id",
                Long.class, comp);
        operator = worker(comp, "E1", "BADGE-0001", "1111");
        sup = worker(comp, "S1", "BADGE-0002", "2468");
        worker(comp, "W2", "BADGE-0003", "1357");
        long expired = worker(comp, "S2", "BADGE-0004", "2468");
        long noPin = worker(comp, "S3", "BADGE-0005", null);
        held(sup, "(now() at time zone 'Asia/Seoul')::date - 10", null);
        held(expired, "(now() at time zone 'Asia/Seoul')::date - 400", "(now() at time zone 'Asia/Seoul')::date - 1");
        held(noPin, "(now() at time zone 'Asia/Seoul')::date - 10", null);
        runner.run(comp, () -> policies.set(PopAuthService.APPROVER_QUAL_POLICY, null, "SUP", "t"));
        cookie = terminal("TAB-01", line1, "POP");
        s = new MockHttpSession();
        badge(cookie, s, "BADGE-0001").andExpect(status().isOk());
        pop(post(P + "/procs/" + proc + "/events/start"), "{\"clientReqId\":\"" + req() + "\"}").andExpect(status().isOk());
    }

    @AfterEach
    void resetClock() {
        auth.setClock(Clock.systemUTC());
    }

    // ---------- 도우미 ----------

    private long worker(String c, String cd, String badge, String pin) {
        return accounts.query(c, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, pin_hash, created_by, updated_by)
                values (?, ?, ?, 'P1', ?, ?, 't', 't') returning worker_id""", Long.class, c, cd, cd + " 작업자",
                WorkerService.badgeHash(c, badge), pin == null ? null : encoder.encode(pin));
    }

    private void held(long workerId, String acquired, String expires) {
        accounts.exec(comp, "insert into mst_worker_qual (comp_cd, worker_id, qual_id, acquired_on, expires_on, created_by, updated_by) values (?, ?, ?, "
                + acquired + ", " + (expires == null ? "null" : expires) + ", 't', 't')", comp, workerId, qualId);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String terminal(String cd, Long nodeId, String type) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\",\"terminalType\":\"" + type + "\"" + (nodeId == null ? "" : ",\"nodeId\":" + nodeId) + "}").with(admin))
                .andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        MvcResult a = mvc.perform(json(post(P + "/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return set.substring(set.indexOf('=') + 1, set.indexOf(';'));
    }

    private ResultActions badge(String c, MockHttpSession session, String badge) throws Exception {
        return mvc.perform(json(post(P + "/badge"), "{\"badge\":\"" + badge + "\"}").cookie(new Cookie(PopAuthService.COOKIE, c)).session(session));
    }

    private ResultActions pop(MockHttpServletRequestBuilder b, String body) throws Exception {
        return mvc.perform(json(b, body).cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s));
    }

    private static String req() {
        return UUID.randomUUID().toString();
    }

    /** 실적 하나(양품 n) → result_id */
    private long result(int good) throws Exception {
        String r = req();
        pop(post(P + "/procs/" + proc + "/results"), "{\"clientReqId\":\"" + r + "\",\"goodQty\":\"" + good + "\"}").andExpect(status().isOk());
        return accounts.query(comp, "select result_id from prd_result where client_req_id = ?", Long.class, r);
    }

    private long param(String value) throws Exception {
        MvcResult m = pop(post(P + "/procs/" + proc + "/params"), "{\"clientReqId\":\"" + req() + "\",\"paramCd\":\"TORQUE\",\"value\":\""
                + value + "\"}").andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.actualId")).longValue();
    }

    private ResultActions approve(String badge, String pin, String action, long target) throws Exception {
        return pop(post(P + "/approvals"), "{\"badge\":\"" + badge + "\",\"pin\":\"" + pin + "\",\"action\":\"" + action
                + "\",\"targetId\":" + target + "}");
    }

    private String token(String action, long target) throws Exception {
        MvcResult m = approve("BADGE-0002", "2468", action, target).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approverCd").value("S1")).andReturn();
        return JsonPath.read(m.getResponse().getContentAsString(), "$.data.approvalToken");
    }

    private ResultActions correct(long resultId, String token) throws Exception {
        return pop(post(P + "/results/" + resultId + "/correct"), "{\"reason\":\"수량 오입력\""
                + (token == null ? "" : ",\"approvalToken\":\"" + token + "\"") + "}");
    }

    private void policy(String key, String value) {
        runner.run(comp, () -> policies.set(key, "P1", value, "t"));
    }

    private int approvals() {
        return accounts.query(comp, "select count(*) from pop_approval", Integer.class);
    }

    private int reversals(long resultId) {
        return accounts.query(comp, "select count(*) from prd_result where reversal_of_id = ?", Integer.class, resultId);
    }

    // ---------- 시험 ----------

    @Test
    void 정책_끔이면_승인_없이_지금_그대로() throws Exception {
        long r = result(5);
        correct(r, null).andExpect(status().isOk());
        assertThat(reversals(r)).isEqualTo(1);
        assertThat(approvals()).isZero();
    }

    @Test
    void 정책_켬_토큰_없으면_403_감독자_승인_뒤_정정과_원장() throws Exception {
        policy(PopWorkService.RESULT_APPROVAL_POLICY, "Y");
        long r = result(5);
        correct(r, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("POP_APPROVAL_REQUIRED"));
        // 사유 검사가 먼저 — 사유가 없으면 승인 요구 전에 400
        pop(post(P + "/results/" + r + "/correct"), "{\"reason\":\" \"}").andExpect(status().isBadRequest());
        assertThat(reversals(r)).isZero();
        String token = token("RESULT_CORRECT", r);
        long rev = ((Number) JsonPath.read(correct(r, token).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.data")).longValue();
        long terminalId = accounts.query(comp, "select terminal_id from sys_terminal where terminal_cd = 'TAB-01'", Long.class);
        String row = accounts.query(comp, """
                select concat_ws('|', terminal_id, requester_worker_id, approver_worker_id, action_cd, target_id, reversal_id, reason,
                                 recorded_by, occurred_at <= recorded_at)
                  from pop_approval""", String.class);
        assertThat(row).isEqualTo(String.join("|", String.valueOf(terminalId), String.valueOf(operator), String.valueOf(sup), "RESULT_CORRECT",
                String.valueOf(r), String.valueOf(rev), "수량 오입력", "t" + terminalId + ":TAB-01", "t"));
        // 쓴 토큰은 버려진다 — 다른 실적에 다시 못 쓴다
        long r2 = result(3);
        correct(r2, token).andExpect(status().isForbidden());
        assertThat(reversals(r2)).isZero();
        assertThat(approvals()).isEqualTo(1);
    }

    @Test
    void 승인자_검사_감독자_아님_만료_본인_없는_배지_PIN() throws Exception {
        policy(PopWorkService.RESULT_APPROVAL_POLICY, "Y");
        long r = result(5);
        approve("BADGE-0003", "1357", "RESULT_CORRECT", r).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POP_APPROVER_NOT_QUALIFIED"));
        approve("BADGE-0004", "2468", "RESULT_CORRECT", r).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POP_APPROVER_NOT_QUALIFIED"));
        approve("BADGE-0001", "1111", "RESULT_CORRECT", r).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("POP_APPROVER_SELF"));
        approve("BADGE-9999", "2468", "RESULT_CORRECT", r).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BADGE_UNKNOWN"));
        approve("BADGE-0005", "1234", "RESULT_CORRECT", r).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PIN_NOT_SET"));
        approve("BADGE-0002", "2468", "COUNT_FIX", r).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("action"));
        // PIN 4번 틀림 → 남은 횟수, 5번째 잠김, 잠긴 뒤 맞는 PIN 도 잠김(배지 로그인과 같은 카운터)
        for (int i = 4; i >= 1; i--) {
            approve("BADGE-0002", "0000", "RESULT_CORRECT", r).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PIN_WRONG"));
        }
        approve("BADGE-0002", "0000", "RESULT_CORRECT", r).andExpect(status().is(423));
        approve("BADGE-0002", "2468", "RESULT_CORRECT", r).andExpect(status().is(423));
        // 잠그면 잠금 시각을 남긴다(배지 로그인과 같은 선점 pinClaim — 횟수는 5 그대로, 잠금이 끝난 뒤 1부터)
        assertThat(accounts.query(comp, "select pin_locked_until > now() from mst_worker where worker_id = ?", Boolean.class, sup)).isTrue();
        // 자격 정책을 비우면 아무도 감독자가 아니다
        accounts.exec(comp, "update mst_worker set pin_fail_cnt = 0, pin_locked_until = null where worker_id = ?", sup);
        runner.run(comp, () -> policies.set(PopAuthService.APPROVER_QUAL_POLICY, null, "", "t"));
        approve("BADGE-0002", "2468", "RESULT_CORRECT", r).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POP_APPROVER_NOT_QUALIFIED"));
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'POP_APPROVAL_FAIL'", Integer.class))
                .isGreaterThanOrEqualTo(10);
        assertThat(reversals(r)).isZero();
    }

    @Test
    void 단말_배지_제한은_로그인과_합산() throws Exception {
        long r = result(5);
        for (int i = 0; i < 10; i++) {
            approve("BADGE-0003", "1357", "RESULT_CORRECT", r).andExpect(status().isForbidden());   // 감독자 아닌 배지도 센다
        }
        approve("BADGE-0002", "2468", "RESULT_CORRECT", r).andExpect(status().isTooManyRequests());
        badge(cookie, s, "BADGE-0001").andExpect(status().isTooManyRequests());
    }

    @Test
    void 토큰_범위_다른_대상_다른_동작_만료_다른_단말_작업자_바뀜() throws Exception {
        policy(PopWorkService.RESULT_APPROVAL_POLICY, "Y");
        policy(PopWorkService.PARAM_APPROVAL_POLICY, "Y");
        long a = result(5);
        long b = result(3);
        // 다른 대상
        correct(b, token("RESULT_CORRECT", a)).andExpect(status().isForbidden());
        // 다른 동작 — 실적 정정 토큰으로 파라미터 정정
        long pa = param("9.2");
        pop(post(P + "/params/" + pa + "/correct"), "{\"reason\":\"센서 오독\",\"approvalToken\":\"" + token("RESULT_CORRECT", pa) + "\"}")
                .andExpect(status().isForbidden());
        // 파라미터 정정 토큰이면 된다
        pop(post(P + "/params/" + pa + "/correct"), "{\"reason\":\"센서 오독\",\"approvalToken\":\"" + token("PARAM_CORRECT", pa) + "\"}")
                .andExpect(status().isOk());
        // 만료(2분 뒤)
        String t1 = token("RESULT_CORRECT", a);
        auth.setClock(Clock.offset(Clock.systemUTC(), Duration.ofMinutes(3)));
        correct(a, t1).andExpect(status().isForbidden());
        auth.setClock(Clock.systemUTC());
        // 같은 세션을 다른 단말 쿠키로 — 단말이 달라 쓸 수 없다
        String t2 = token("RESULT_CORRECT", a);
        String other = terminal("TAB-02", line1, "POP");
        mvc.perform(json(post(P + "/results/" + a + "/correct"), "{\"reason\":\"x\",\"approvalToken\":\"" + t2 + "\"}")
                .cookie(new Cookie(PopAuthService.COOKIE, other)).session(s)).andExpect(status().is4xxClientError());
        // 작업자가 바뀌면 앞 작업자가 받은 승인은 버려진다
        String t3 = token("RESULT_CORRECT", a);
        accounts.exec(comp, "insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by) values (?, 'E2', 'E2', 'P1', ?, 't', 't')",
                comp, WorkerService.badgeHash(comp, "BADGE-0006"));
        badge(cookie, s, "BADGE-0006").andExpect(status().isOk());
        correct(a, t3).andExpect(status().isForbidden());
        assertThat(reversals(a)).isZero();
        assertThat(approvals()).isEqualTo(1);   // 파라미터 정정 하나
    }

    @Test
    void 같은_토큰으로_동시_두_번이면_정정과_원장_하나() throws Exception {
        policy(PopWorkService.RESULT_APPROVAL_POLICY, "Y");
        long r = result(5);
        String token = token("RESULT_CORRECT", r);
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Integer>> calls = List.of(1, 2).stream().map(i -> CompletableFuture.supplyAsync(() -> {
            try {
                go.await(10, TimeUnit.SECONDS);
                return correct(r, token).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        })).toList();
        go.countDown();
        List<Integer> codes = calls.stream().map(f -> f.orTimeout(20, TimeUnit.SECONDS).join()).toList();
        assertThat(codes).contains(200).hasSize(2);
        assertThat(codes.stream().filter(c -> c == 200).count()).isEqualTo(1);
        assertThat(reversals(r)).isEqualTo(1);
        assertThat(approvals()).isEqualTo(1);
    }

    @Test
    void A사_단말에_B사_배지_B사_실적_id_직접_SQL_PDA() throws Exception {
        policy(PopWorkService.RESULT_APPROVAL_POLICY, "Y");
        String other = accounts.newComp("PB");
        accounts.addPlant(other, "P1", "1공장");
        long theirSup = worker(other, "S1", "BADGE-0002", "2468");   // 같은 배지 글자·코드, 다른 회사
        // B사 감독자 배지는 A사 단말에서 없음(배지 해시에 회사가 들어가고 RLS)
        long r = result(5);
        accounts.exec(comp, "update mst_worker_qual set revoked_on = acquired_on where worker_id = ?", sup);   // A사 감독자 S1 자격을 취소해도
        approve("BADGE-0002", "2468", "RESULT_CORRECT", r).andExpect(status().isForbidden());   // B사 S1 으로 넘어가지 않는다
        // B사 실적 id 로 토큰을 받아도 정정은 A사 범위에서 없음
        held(sup, "(now() at time zone 'Asia/Seoul')::date - 1", null);
        String t = token("RESULT_CORRECT", 999999999L);
        correct(999999999L, t).andExpect(status().isNotFound());
        // 직접 SQL — 원장 수정·삭제 거부, 자기 승인 거부, 다른 회사 작업자 거부
        correct(r, token("RESULT_CORRECT", r)).andExpect(status().isOk());
        assertThat(sqlError(comp, "update pop_approval set reason = 'x'")).isNotEqualTo("OK");
        assertThat(sqlError(comp, "delete from pop_approval")).isNotEqualTo("OK");
        long terminalId = accounts.query(comp, "select terminal_id from sys_terminal where terminal_cd = 'TAB-01'", Long.class);
        assertThat(sqlError(comp, """
                insert into pop_approval (comp_cd, terminal_id, requester_worker_id, approver_worker_id, action_cd, target_id, reversal_id,
                                          reason, occurred_at, recorded_by) values (?, ?, ?, ?, 'PARAM_CORRECT', 1, 1, 'x', now(), 't')""",
                comp, terminalId, operator, operator)).isEqualTo("23514 ck_pop_approval_self");
        assertThat(sqlError(comp, """
                insert into pop_approval (comp_cd, terminal_id, requester_worker_id, approver_worker_id, action_cd, target_id, reversal_id,
                                          reason, occurred_at, recorded_by) values (?, ?, ?, ?, 'PARAM_CORRECT', 1, 1, 'x', now(), 't')""",
                comp, terminalId, operator, theirSup)).isEqualTo("23503 fk_pop_approval_ctx_comp");
        assertThat(accounts.query(other, "select count(*) from pop_approval", Integer.class)).isZero();
        // PDA 는 승인 경로 403
        String pda = terminal("PDA-01", null, "PDA");
        MockHttpSession ps = new MockHttpSession();
        badge(pda, ps, "BADGE-0001").andExpect(status().isOk());
        mvc.perform(json(post(P + "/approvals"), "{\"badge\":\"BADGE-0002\",\"pin\":\"2468\",\"action\":\"RESULT_CORRECT\",\"targetId\":" + r + "}")
                .cookie(new Cookie(PopAuthService.COOKIE, pda)).session(ps)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 파라미터_최근_값_정정_버튼의_대상은_그_앞_값으로_넘어간다() throws Exception {
        // POP 화면 [정정] 은 params[].lastActualId 를 정정한다(docs/25 4.38) — 정정하면 그 앞 유효 값이 "최근" 이 된다
        long first = param("9.0");
        long second = param("9.9");
        procView().andExpect(jsonPath("$.data.params[0].lastActualId").value((int) second))
                .andExpect(jsonPath("$.data.params[0].lastJudge").value("NG"));
        pop(post(P + "/params/" + second + "/correct"), "{\"reason\":\"단위 착오\"}").andExpect(status().isOk());
        procView().andExpect(jsonPath("$.data.params[0].lastActualId").value((int) first))
                .andExpect(jsonPath("$.data.params[0].lastValue").value("9")).andExpect(jsonPath("$.data.params[0].lastJudge").value("OK"));
        // 정책이 켜지면 같은 버튼도 감독자 승인 — 토큰 없으면 403, 승인 뒤 정정
        policy(PopWorkService.PARAM_APPROVAL_POLICY, "Y");
        pop(post(P + "/params/" + first + "/correct"), "{\"reason\":\"센서 오독\"}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("POP_APPROVAL_REQUIRED"));
        pop(post(P + "/params/" + first + "/correct"), "{\"reason\":\"센서 오독\",\"approvalToken\":\"" + token("PARAM_CORRECT", first) + "\"}")
                .andExpect(status().isOk());
        procView().andExpect(jsonPath("$.data.params[0].lastActualId").doesNotExist());
        assertThat(approvals()).isEqualTo(1);
    }

    @Test
    void 실적_조회에_정정_승인자가_보이고_승인_없는_정정은_비어_있다() throws Exception {
        // docs/25 4.39 — 사무 실적 조회(prd-result) "실적 한 건씩" 의 정정 행에 승인한 감독자
        long plain = result(4);
        correct(plain, null).andExpect(status().isOk());                       // 정책 끔 — 승인 없는 정정
        policy(PopWorkService.RESULT_APPROVAL_POLICY, "Y");
        long approved = result(6);
        correct(approved, token("RESULT_CORRECT", approved)).andExpect(status().isOk());
        String workDay = JsonPath.read(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(P + "/session")
                .cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s)).andReturn().getResponse().getContentAsString(), "$.data.workDay");
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/production/results?from=" + workDay + "&to=" + workDay).with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Object> a = JsonPath.read(body, "$.data.rows[?(@.resultId == " + approved + ")].approverNm");
        List<Object> aCd = JsonPath.read(body, "$.data.rows[?(@.resultId == " + approved + ")].approverCd");
        List<Object> p = JsonPath.read(body, "$.data.rows[?(@.resultId == " + plain + ")].approverNm");
        List<Object> pc = JsonPath.read(body, "$.data.rows[?(@.resultId == " + plain + ")].corrected");
        assertThat(a).containsExactly("S1 작업자");
        assertThat(aCd).containsExactly("S1");
        assertThat(pc).containsExactly(true);
        assertThat(p).hasSize(1).containsOnlyNulls();
    }

    private ResultActions procView() throws Exception {
        return mvc.perform(get(P + "/procs/" + proc).cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s)).andExpect(status().isOk());
    }

    private String sqlError(String c, String sql, Object... args) {
        Throwable t = catchThrowable(() -> accounts.exec(c, sql, args));
        return t == null ? "OK" : PgErrors.sqlState(t).orElse("?") + " " + PgErrors.constraint(t).orElse("-");
    }
}
