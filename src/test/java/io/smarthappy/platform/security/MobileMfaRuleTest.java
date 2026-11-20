package io.smarthappy.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 관리자 모바일(docs/45 9장 N10) — "개인 기기에서도 현행 MFA 규칙"(사용자 결정 2026-10-29, 새 MFA 없음).
 * 휴대전화 PWA·브라우저는 같은 로그인·같은 세션이라 규칙도 같다: 관리자 그룹(MFA_REQUIRED_FOR_ADMIN 기본 Y)은 로그인 뒤 2단계를 요구받고,
 * 2단계를 건너뛰고 API(결재함·푸시 구독·알림)를 직접 불러도 403 MFA_REQUIRED. 2단계를 마치면 같은 API 가 열린다.
 * 휴대전화처럼 보이는 요청(모바일 User-Agent·standalone PWA 표시 헤더)이라고 다르게 대하지 않는다
 */
@AutoConfigureMockMvc
class MobileMfaRuleTest extends PostgresTestBase {

    static final String PW = "Mobile-mfa-pw-9!";
    static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1";
    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    MfaService mfa;

    String comp;
    Instant now = Instant.parse("2026-10-29T00:00:00Z");

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("MM");
        long adminId = accounts.newUser(comp, "boss", PW);
        accounts.exec(comp, """
                insert into sys_auth_group (comp_cd, auth_grp_cd, auth_grp_nm, admin_yn, created_by, updated_by)
                values (?, 'ADMIN', '관리자', 'Y', 't', 't')""", comp);
        accounts.exec(comp, "insert into sys_user_auth (comp_cd, user_id, auth_grp_cd, created_by) values (?, ?, 'ADMIN', 't')", comp, adminId);
        tick();
    }

    @AfterEach
    void resetClock() {
        mfa.setClock(Clock.systemUTC());
    }

    void tick() {
        now = now.plusSeconds(30);
        mfa.setClock(Clock.fixed(now, ZoneOffset.UTC));
    }

    /** 휴대전화 PWA 처럼 — 모바일 User-Agent + 설치 앱 표시(Sec-Fetch-Dest·display-mode 는 서버가 보지 않는다) */
    MockHttpServletRequestBuilder phone(MockHttpServletRequestBuilder b) {
        return b.header("User-Agent", IPHONE).header("Sec-Fetch-Site", "same-origin");
    }

    MockHttpSession login() throws Exception {
        String ip = "10.45." + (IP_SEQ.incrementAndGet() % 250 + 1) + ".9";
        MvcResult r = mvc.perform(phone(post("/login")).with(csrf()).with(req -> { req.setRemoteAddr(ip); return req; })
                .param("compCd", comp).param("loginId", "boss").param("password", PW)).andExpect(redirectedUrl("/main")).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return phone(b).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void 관리자는_휴대전화에서도_2단계를_요구받고_건너뛴_API_는_거부된다() throws Exception {
        MockHttpSession s = login();
        // 화면은 2단계 화면으로
        mvc.perform(phone(get("/main")).session(s)).andExpect(redirectedUrl("/mfa"));
        mvc.perform(phone(get("/page/sys-approval")).session(s)).andExpect(redirectedUrl("/mfa"));
        // API 직접 — 결재함·결재 처리·푸시 구독·알림·홈 카드 모두 403 MFA_REQUIRED
        for (MockHttpServletRequestBuilder b : new MockHttpServletRequestBuilder[] {
                phone(get("/api/platform/approvals/todo")), phone(get("/api/platform/push")), phone(get("/api/home/cards")),
                json(post("/api/platform/push/subscriptions"), "{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/x\"}"),
                json(post("/api/platform/approvals/1/approve"), "{}")}) {
            mvc.perform(b.session(s)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
        }
        assertThat(accounts.query(comp, "select count(*) from sys_push_sub", Integer.class)).isZero();

        // 2단계(등록 + 확인)를 마치면 같은 API 가 열린다 — 푸시는 이 서버가 꺼져 있어 409(MFA 가 아니라 설정 때문)
        MvcResult r = mvc.perform(json(post("/api/platform/mfa/enroll/start"), "{}").session(s)).andExpect(status().isOk()).andReturn();
        String secret = JsonPath.read(r.getResponse().getContentAsString(), "$.data.secret");
        mvc.perform(json(post("/api/platform/mfa/enroll/confirm"), "{\"code\":\"" + code(secret) + "\"}").session(s)).andExpect(status().isOk());
        mvc.perform(phone(get("/api/platform/approvals/todo")).session(s)).andExpect(status().isOk());
        mvc.perform(phone(get("/api/platform/push")).session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(false));
        mvc.perform(json(post("/api/platform/push/subscriptions"), "{\"endpoint\":\"https://fcm.googleapis.com/fcm/send/x\"}").session(s))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PUSH_DISABLED"));

        // 다음 로그인(새 휴대전화 세션)도 다시 2단계 — 등록돼 있으니 확인 코드를 요구
        tick();
        MockHttpSession s2 = login();
        mvc.perform(phone(get("/api/platform/approvals/todo")).session(s2)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
        mvc.perform(json(post("/api/platform/mfa/verify"), "{\"code\":\"" + code(secret) + "\"}").session(s2)).andExpect(status().isOk());
        mvc.perform(phone(get("/api/platform/approvals/todo")).session(s2)).andExpect(status().isOk());
    }

    /** RFC 6238 — HMAC-SHA1, 30초, 6자리 (서버 Totp 와 독립 계산, MfaTest 와 같은 식) */
    String code(String base32Secret) throws Exception {
        String s = base32Secret.replace(" ", "").toUpperCase(Locale.ROOT);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : s.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.put((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(out.array(), "HmacSHA1"));
        byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(now.getEpochSecond() / 30).array());
        int o = h[h.length - 1] & 0x0f;
        int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
        return String.format(Locale.ROOT, "%06d", bin % 1_000_000);
    }
}
