package io.smarthappy.module.pop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * 키오스크 빠져나오기 API (docs/25 4.52, docs/50 K4·K5·Q8) — 감독자(정책 POP_APPROVER_QUAL 자격, 오늘 유효) 배지 + PIN 을 서버가 확인,
 * 접속 로그 POP_KIOSK_EXIT. 지금 작업자 없이도·감독자 본인이 작업자여도 된다. POP·PDA 단말 공용.
 * 이상 사용: 감독자 아님·자격 만료·없는 배지·PIN 틀림·PIN 없음, PIN 잠금과 단말 배지 제한은 승인·배지 로그인과 같은 카운터(따로 맞혀 보지 못함),
 * 단말 아님(쿠키 없음 401·사무 사용자 거부), A사로 B사(B사 감독자 배지로 A사 단말) 실패.
 */
@AutoConfigureMockMvc
class PopKioskExitTest extends PostgresTestBase {

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

    String comp;
    RequestPostProcessor admin;
    String ip;
    long qualId;
    String pop;
    MockHttpSession s;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PK");
        ip = "10.88." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "ADM", "sys-terminal", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        setUpComp(comp);
        pop = terminal(comp, admin, "TAB-01", "POP");
        s = new MockHttpSession();
    }

    // ---------- 도우미 ----------

    /** 공장 P1·작업장, 자격 SUP, 작업자: 조작자 E1(1111), 감독자 S1(2468), 자격 없음 W2, 자격 만료 S2, PIN 없는 감독자 S3. 정책 = SUP */
    private void setUpComp(String c) {
        accounts.addPlant(c, "P1", "1공장");
        long q = accounts.query(c, "insert into mst_qual (comp_cd, qual_cd, qual_nm, created_by, updated_by) values (?, 'SUP', '감독자', 't', 't') returning qual_id",
                Long.class, c);
        if (c.equals(comp)) {
            qualId = q;
        }
        worker(c, "E1", "BADGE-0001", "1111");
        long sup = worker(c, "S1", "BADGE-0002", "2468");
        worker(c, "W2", "BADGE-0003", "1357");
        long expired = worker(c, "S2", "BADGE-0004", "2468");
        long noPin = worker(c, "S3", "BADGE-0005", null);
        held(c, q, sup, "(now() at time zone 'Asia/Seoul')::date - 10", null);
        held(c, q, expired, "(now() at time zone 'Asia/Seoul')::date - 400", "(now() at time zone 'Asia/Seoul')::date - 1");
        held(c, q, noPin, "(now() at time zone 'Asia/Seoul')::date - 10", null);
        runner.run(c, () -> policies.set(PopAuthService.APPROVER_QUAL_POLICY, null, "SUP", "t"));
    }

    private long worker(String c, String cd, String badge, String pin) {
        return accounts.query(c, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, pin_hash, created_by, updated_by)
                values (?, ?, ?, 'P1', ?, ?, 't', 't') returning worker_id""", Long.class, c, cd, cd + " 작업자",
                WorkerService.badgeHash(c, badge), pin == null ? null : encoder.encode(pin));
    }

    private void held(String c, long q, long workerId, String acquired, String expires) {
        accounts.exec(c, "insert into mst_worker_qual (comp_cd, worker_id, qual_id, acquired_on, expires_on, created_by, updated_by) values (?, ?, ?, "
                + acquired + ", " + (expires == null ? "null" : expires) + ", 't', 't')", c, workerId, q);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String terminal(String c, RequestPostProcessor who, String cd, String type) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\",\"terminalType\":\"" + type + "\"}").with(who)).andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        MvcResult a = mvc.perform(json(post(P + "/activate"), "{\"compCd\":\"" + c + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return set.substring(set.indexOf('=') + 1, set.indexOf(';'));
    }

    private ResultActions exit(String cookie, MockHttpSession session, String badge, String pin) throws Exception {
        return mvc.perform(json(post(P + "/kiosk/exit"), "{\"badge\":\"" + badge + "\",\"pin\":\"" + pin + "\"}")
                .cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(session));
    }

    private ResultActions badge(String cookie, MockHttpSession session, String badge) throws Exception {
        return mvc.perform(json(post(P + "/badge"), "{\"badge\":\"" + badge + "\"}").cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(session));
    }

    private String lastLog(String event) {
        return accounts.query(comp, """
                select result_cd || '|' || coalesce(detail, '') || '|' || login_id || '|' || client_type from sys_access_log
                 where event_cd = ? order by log_id desc limit 1""", String.class, event);
    }

    private int logs(String event) {
        return accounts.query(comp, "select count(*) from sys_access_log where event_cd = ?", Integer.class, event);
    }

    // ---------- 시험 ----------

    @Test
    void 감독자_배지_PIN_이면_빠져나오고_접속_로그에_누가() throws Exception {
        // 지금 작업자 없이(배지 화면에서)
        exit(pop, s, "BADGE-0002", "2468").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approverCd").value("S1")).andExpect(jsonPath("$.data.approverNm").value("S1 작업자"))
                .andExpect(jsonPath("$.data.relockAfterSec").value(600));
        String log = lastLog("POP_KIOSK_EXIT");
        assertThat(log).matches("OK\\|worker=S1 ref=[A-Za-z0-9_-]{22}\\|t\\d+:TAB-01\\|POP");
        // 감독자 본인이 지금 작업자여도 된다(승인과 달리 자기 동작을 승인하는 것이 아님)
        badge(pop, s, "BADGE-0002").andExpect(status().isOk());
        exit(pop, s, "BADGE-0002", "2468").andExpect(status().isOk());
        // 다른 작업자가 로그인한 단말에서도
        MockHttpSession other = new MockHttpSession();
        badge(pop, other, "BADGE-0001").andExpect(status().isOk());
        exit(pop, other, "BADGE-0002", "2468").andExpect(status().isOk());
        assertThat(logs("POP_KIOSK_EXIT")).isEqualTo(3);
    }

    @Test
    void 감독자_아님_자격_만료_없는_배지_PIN_틀림_PIN_없음은_실패하고_사유를_남긴다() throws Exception {
        exit(pop, s, "BADGE-0003", "1357").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("POP_APPROVER_NOT_QUALIFIED"));
        assertThat(lastLog("POP_KIOSK_EXIT_FAIL")).startsWith("FAIL|worker=W2 reason=qual|");
        exit(pop, s, "BADGE-0004", "2468").andExpect(jsonPath("$.code").value("POP_APPROVER_NOT_QUALIFIED"));
        exit(pop, s, "BADGE-9999", "2468").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BADGE_UNKNOWN"));
        assertThat(lastLog("POP_KIOSK_EXIT_FAIL")).startsWith("FAIL|reason=badge|");
        exit(pop, s, "BADGE-0002", "0000").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PIN_WRONG"));
        assertThat(lastLog("POP_KIOSK_EXIT_FAIL")).startsWith("FAIL|worker=S1 reason=pin|");
        exit(pop, s, "BADGE-0005", "2468").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PIN_NOT_SET"));
        mvc.perform(json(post(P + "/kiosk/exit"), "").cookie(new Cookie(PopAuthService.COOKIE, pop)).session(s)).andExpect(status().isBadRequest());
        assertThat(logs("POP_KIOSK_EXIT")).isZero();
        // 자격 정책이 비면 아무도 빠져나오지 못한다
        runner.run(comp, () -> policies.set(PopAuthService.APPROVER_QUAL_POLICY, null, "", "t"));
        exit(pop, s, "BADGE-0002", "2468").andExpect(jsonPath("$.code").value("POP_APPROVER_NOT_QUALIFIED"));
    }

    @Test
    void PIN_잠금은_승인_배지_로그인과_같은_카운터() throws Exception {
        for (int i = 0; i < 4; i++) {
            exit(pop, s, "BADGE-0002", "0000").andExpect(jsonPath("$.code").value("PIN_WRONG"));
        }
        // 다섯째(다른 경로 — 배지 로그인 PIN 이 아니라도 같은 작업자 PIN 카운터) → 잠김
        exit(pop, s, "BADGE-0002", "0000").andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("PIN_LOCKED"));
        exit(pop, s, "BADGE-0002", "2468").andExpect(status().isLocked());
        assertThat(accounts.query(comp, "select pin_fail_cnt from mst_worker where worker_cd = 'S1'", Integer.class)).isGreaterThanOrEqualTo(5);
        assertThat(logs("POP_KIOSK_EXIT")).isZero();
    }

    @Test
    void 단말_배지_제한도_같은_카운터() throws Exception {
        for (int i = 0; i < 6; i++) {
            badge(pop, s, "BADGE-90" + (10 + i)).andExpect(status().isBadRequest());
        }
        for (int i = 0; i < 4; i++) {
            exit(pop, s, "BADGE-80" + (10 + i), "2468").andExpect(jsonPath("$.code").value("BADGE_UNKNOWN"));
        }
        // 배지 로그인 6 + 빠져나오기 4 = 10 → 이제 맞는 감독자도 잠시 막힘
        exit(pop, s, "BADGE-0002", "2468").andExpect(status().isTooManyRequests());
    }

    @Test
    void PDA_단말도_되고_단말이_아니면_401() throws Exception {
        String pda = terminal(comp, admin, "PDA-01", "PDA");
        exit(pda, new MockHttpSession(), "BADGE-0002", "2468").andExpect(status().isOk());
        assertThat(lastLog("POP_KIOSK_EXIT")).endsWith(":PDA-01|PDA");
        // 쿠키 없음
        mvc.perform(json(post(P + "/kiosk/exit"), "{\"badge\":\"BADGE-0002\",\"pin\":\"2468\"}")).andExpect(status().isUnauthorized());
        // 사무 사용자 로그인으로는 POP 체인을 못 쓴다 — 실제로는 체인이 사무 세션을 읽지 않아 401, 시험 도구는 인증을 바로 넣으므로 403(단말 아님)
        mvc.perform(json(post(P + "/kiosk/exit"), "{\"badge\":\"BADGE-0002\",\"pin\":\"2468\"}").with(admin)).andExpect(status().isForbidden());
        assertThat(logs("POP_KIOSK_EXIT")).isEqualTo(1);
    }

    @Test
    void A사로_B사_감독자_배지는_실패() throws Exception {
        String b = accounts.newComp("PKB");
        setUpComp(b);   // B사에도 같은 배지 글(BADGE-0002)·PIN 의 감독자 — 배지 해시는 회사별
        exit(pop, s, "BADGE-0002", "2468").andExpect(status().isOk());   // A사 자기 감독자는 됨
        // B사 감독자만 가진 배지
        long bOnly = accounts.query(b, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, pin_hash, created_by, updated_by)
                values (?, 'BS', 'B 감독자', 'P1', ?, ?, 't', 't') returning worker_id""", Long.class, b,
                WorkerService.badgeHash(b, "BADGE-7777"), encoder.encode("2468"));
        accounts.exec(b, "insert into mst_worker_qual (comp_cd, worker_id, qual_id, acquired_on, created_by, updated_by) select ?, ?, qual_id, current_date - 10, 't', 't' from mst_qual where qual_cd = 'SUP'",
                b, bOnly);
        exit(pop, s, "BADGE-7777", "2468").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BADGE_UNKNOWN"));
        assertThat(accounts.query(b, "select count(*) from sys_access_log where event_cd like 'POP_KIOSK%'", Integer.class)).isZero();
    }

    // ---------- KS9 풀기 실패 알림 (docs/50 7.2, docs/25 4.54) ----------

    private String exitRef(String cookie) throws Exception {
        MvcResult m = exit(cookie, new MockHttpSession(), "BADGE-0002", "2468").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exitRef").isString()).andReturn();
        return JsonPath.read(m.getResponse().getContentAsString(), "$.data.exitRef");
    }

    private ResultActions unlockFailed(String cookie, String body) throws Exception {
        MockHttpServletRequestBuilder b = json(post(P + "/kiosk/unlock-failed"), body);
        return mvc.perform(cookie == null ? b : b.cookie(new Cookie(PopAuthService.COOKIE, cookie)));
    }

    private static String body(String ref, String reason) {
        return "{\"exitRef\":\"" + ref + "\",\"reason\":\"" + reason + "\"}";
    }

    @Test
    void 풀기_실패는_exitRef_로_한_번만_남고_두번째는_아무것도_안_한다() throws Exception {
        String ref = exitRef(pop);
        assertThat(ref).matches("[A-Za-z0-9_-]{22}");
        assertThat(exitRef(pop)).isNotEqualTo(ref);   // 빠져나올 때마다 새 값
        unlockFailed(pop, body(ref, "stale")).andExpect(status().isOk());
        assertThat(lastLog("POP_KIOSK_UNLOCK_FAIL"))
                .matches("FAIL\\|worker=S1 ref=" + java.util.regex.Pattern.quote(ref) + " reason=stale\\|t\\d+:TAB-01\\|POP");
        // 같은 exitRef 두 번째 — 사유가 달라도 200 아무것도 안 함(멱등)
        unlockFailed(pop, body(ref, "refused")).andExpect(status().isOk());
        unlockFailed(pop, body(ref, "stale")).andExpect(status().isOk());
        assertThat(logs("POP_KIOSK_UNLOCK_FAIL")).isEqualTo(1);
        // 다른 빠져나오기는 따로
        String ref2 = exitRef(pop);
        unlockFailed(pop, body(ref2, "refused")).andExpect(status().isOk());
        assertThat(lastLog("POP_KIOSK_UNLOCK_FAIL")).startsWith("FAIL|worker=S1 ref=" + ref2 + " reason=refused|");
        assertThat(logs("POP_KIOSK_UNLOCK_FAIL")).isEqualTo(2);
        // 작업자 로그인 없이(배지 화면) 왔다 — 빠져나오기 기록은 그대로 성공 3건
        assertThat(logs("POP_KIOSK_EXIT")).isEqualTo(3);
    }

    @Test
    void 풀기_실패_입력이_틀리면_400_404_단말이_아니면_401() throws Exception {
        String ref = exitRef(pop);
        unlockFailed(pop, body(ref, "other")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"));
        unlockFailed(pop, body(ref, "STALE")).andExpect(status().isBadRequest());   // 대소문자도 정확히
        unlockFailed(pop, "{\"exitRef\":\"" + ref + "\"}").andExpect(status().isBadRequest());
        unlockFailed(pop, "{\"reason\":\"stale\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("exitRef"));
        unlockFailed(pop, "").andExpect(status().isBadRequest());
        // 모양이 다르거나(LIKE 와일드카드·길이) 우리가 준 적 없는 값 = 404
        unlockFailed(pop, body("%", "stale")).andExpect(status().isNotFound());
        unlockFailed(pop, body("______________________", "stale")).andExpect(status().isNotFound());
        unlockFailed(pop, body(ref.substring(1), "stale")).andExpect(status().isNotFound());
        unlockFailed(pop, body("AAAAAAAAAAAAAAAAAAAAAA", "stale")).andExpect(status().isNotFound());
        // 단말 쿠키 없음 = 401, 사무 로그인은 POP 체인에서 단말이 아님
        unlockFailed(null, body(ref, "stale")).andExpect(status().isUnauthorized());
        mvc.perform(json(post(P + "/kiosk/unlock-failed"), body(ref, "stale")).with(admin)).andExpect(status().isForbidden());
        assertThat(logs("POP_KIOSK_UNLOCK_FAIL")).isZero();
        // 실패한 시도들이 그 exitRef 를 써 버리지 않았다
        unlockFailed(pop, body(ref, "stale")).andExpect(status().isOk());
        assertThat(logs("POP_KIOSK_UNLOCK_FAIL")).isEqualTo(1);
    }

    @Test
    void 다른_단말이_받은_exitRef_는_404() throws Exception {
        String other = terminal(comp, admin, "TAB-02", "POP");
        String ref = exitRef(pop);
        unlockFailed(other, body(ref, "stale")).andExpect(status().isNotFound());
        assertThat(logs("POP_KIOSK_UNLOCK_FAIL")).isZero();
        unlockFailed(pop, body(ref, "stale")).andExpect(status().isOk());   // 받은 단말은 된다(다른 단말 시도가 멱등 기록을 남기지 않음)
        assertThat(lastLog("POP_KIOSK_UNLOCK_FAIL")).endsWith(":TAB-01|POP");
        // PDA 단말도 자기 것은(단말 공용 API)
        String pda = terminal(comp, admin, "PDA-01", "PDA");
        String pdaRef = exitRef(pda);
        unlockFailed(pop, body(pdaRef, "refused")).andExpect(status().isNotFound());
        unlockFailed(pda, body(pdaRef, "refused")).andExpect(status().isOk());
        assertThat(lastLog("POP_KIOSK_UNLOCK_FAIL")).endsWith(":PDA-01|PDA");
    }

    @Test
    void A사로_B사_exitRef_는_404() throws Exception {
        String b = accounts.newComp("PKC");
        long ub = accounts.newUser(b, "admin1", PW);
        accounts.grant(b, ub, "ADM", "sys-terminal", "SEARCH", "SAVE");
        setUpComp(b);
        String popB = terminal(b, accounts.as(b, ub, "admin1"), "TAB-01", "POP");   // 같은 단말 코드
        String refA = exitRef(pop);
        unlockFailed(popB, body(refA, "stale")).andExpect(status().isNotFound());
        assertThat(accounts.query(b, "select count(*) from sys_access_log where event_cd = 'POP_KIOSK_UNLOCK_FAIL'", Integer.class)).isZero();
        assertThat(accounts.query(b, "select count(*) from sys_inbox where consumer_cd = 'POP_KIOSK_UNLOCK_FAIL'", Integer.class)).isZero();
        assertThat(logs("POP_KIOSK_UNLOCK_FAIL")).isZero();
        unlockFailed(pop, body(refA, "stale")).andExpect(status().isOk());
    }

    @Test
    void 같은_exitRef_동시_두_번이면_기록_하나() throws Exception {
        String ref = exitRef(pop);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.List<java.util.concurrent.Future<Integer>> fs = new java.util.ArrayList<>();
            for (String reason : new String[] {"stale", "refused"}) {
                fs.add(pool.submit(() -> {
                    go.await();
                    return unlockFailed(pop, body(ref, reason)).andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            for (var f : fs) {
                assertThat(f.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(logs("POP_KIOSK_UNLOCK_FAIL")).isEqualTo(1);
    }

    @SuppressWarnings("unused")
    private static String req() {
        return UUID.randomUUID().toString();
    }
}
