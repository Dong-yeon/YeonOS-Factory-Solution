package io.smarthappy.qa;

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
import io.smarthappy.module.pop.PopAuthService;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * QA 2026-11-02 회차 2 — MES K5 키오스크 빠져나오기(#768, V226) 이상 사용 중 모듈 시험(PopKioskExitTest)이 덮지 않은 것.
 * 결과는 docs/qa/2026-11-02_regress-k5.md.
 */
@AutoConfigureMockMvc
class PopKioskQaTest extends PostgresTestBase {

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
        comp = accounts.newComp("KQ");
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
        runner.run(c, () -> policies.set("POP_APPROVER_QUAL", null, "SUP", "t"));
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

    // ---------- QA ----------

    private ResultActions approve(MockHttpSession session, String badge, String pin) throws Exception {
        return mvc.perform(json(post(P + "/approvals"), "{\"badge\":\"" + badge + "\",\"pin\":\"" + pin + "\",\"action\":\"RESULT_CORRECT\",\"targetId\":1}")
                .cookie(new Cookie(PopAuthService.COOKIE, pop)).session(session));
    }

    /** 교차 방향: 승인 경로에서 PIN 4번 틀림 → 빠져나오기 한 번 틀림 = 잠김, 맞는 PIN 도 두 경로 모두 423 */
    @Test
    void 승인에서_틀린_PIN_이_빠져나오기_잠금으로_이어진다() throws Exception {
        badge(pop, s, "BADGE-0001").andExpect(status().isOk());              // 조작자 E1 로그인(승인은 지금 작업자가 있어야)
        for (int i = 0; i < 4; i++) {
            approve(s, "BADGE-0002", "0000").andExpect(jsonPath("$.code").value("PIN_WRONG"));
        }
        exit(pop, s, "BADGE-0002", "0000").andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("PIN_LOCKED"));
        exit(pop, s, "BADGE-0002", "2468").andExpect(status().isLocked());
        approve(s, "BADGE-0002", "2468").andExpect(status().isLocked());
        assertThat(logs("POP_KIOSK_EXIT")).isZero();
        assertThat(lastLog("POP_KIOSK_EXIT_FAIL")).startsWith("FAIL|worker=S1 reason=locked|");
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'PIN_LOCKED'", Integer.class)).isEqualTo(1);
    }

    /** 사용 중지된 감독자(퇴사 등) — 자격·PIN 이 맞아도 없는 배지와 같게(작업자 코드 안 드러냄), 단말 배지 제한에 센다 */
    @Test
    void 사용_중지된_감독자는_없는_배지와_같다() throws Exception {
        accounts.exec(comp, "update mst_worker set use_yn = 'N', updated_by = 't' where worker_cd = 'S1'");
        exit(pop, s, "BADGE-0002", "2468").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BADGE_UNKNOWN"));
        assertThat(lastLog("POP_KIOSK_EXIT_FAIL")).startsWith("FAIL|reason=badge|");
        assertThat(logs("POP_KIOSK_EXIT")).isZero();
        // PIN 카운터는 건드리지 않는다(배지 단계에서 끝)
        assertThat(accounts.query(comp, "select pin_fail_cnt from mst_worker where worker_cd = 'S1'", Integer.class)).isZero();
    }

    /** 표기: 앞뒤 공백은 지운다(같은 감독자), 소문자·전각은 다른 배지(없는 배지) — 다른 표기로 같은 감독자를 맞혀 보지 못한다 */
    @Test
    void 배지_PIN_앞뒤_공백은_같은_감독자() throws Exception {
        exit(pop, s, " BADGE-0002 ", " 2468 ").andExpect(status().isOk()).andExpect(jsonPath("$.data.approverCd").value("S1"));
        exit(pop, s, "badge-0002", "2468").andExpect(jsonPath("$.code").value("BADGE_UNKNOWN"));
        exit(pop, s, "ＢＡＤＧＥ-０００２", "2468").andExpect(jsonPath("$.code").value("BADGE_UNKNOWN"));
        assertThat(logs("POP_KIOSK_EXIT")).isEqualTo(1);
    }

    /** 단말 배지 제한에 걸린 뒤의 빠져나오기 시도 — 429, BADGE_THROTTLED 로 남고 성공 기록은 없다(POP_KIOSK_EXIT_FAIL 로는 안 남음 — 문서 K-b) */
    @Test
    void 제한에_걸린_뒤_빠져나오기_시도의_기록() throws Exception {
        for (int i = 0; i < 10; i++) {
            exit(pop, s, "BADGE-80" + (10 + i), "2468");
        }
        exit(pop, s, "BADGE-0002", "2468").andExpect(status().isTooManyRequests());
        int thr = accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'BADGE_THROTTLED'", Integer.class);
        assertThat(logs("POP_KIOSK_EXIT")).isZero();
        assertThat(thr).isGreaterThanOrEqualTo(1);
    }
}
