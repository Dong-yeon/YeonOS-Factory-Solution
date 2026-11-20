package io.smarthappy.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/** 비밀번호 변경·정책(05 D9)·신뢰 IP(05 G14) */
@AutoConfigureMockMvc
class PasswordChangeTest extends PostgresTestBase {

    private static final String PW = "Quiet-river-77";
    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    PolicyService policies;

    String comp;
    long userId;
    RequestPostProcessor user;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PW");
        userId = accounts.newUser(comp, "park", PW);
        accounts.grant(comp, userId, "SELF", "sys-my-info", "SEARCH", "SAVE");
        user = accounts.as(comp, userId, "park");
    }

    private ResultActions change(String current, String next) throws Exception {
        return mvc.perform(post("/api/platform/my-info/password").with(user).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":" + json(current) + ",\"newPassword\":" + json(next) + "}"));
    }

    private static String json(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void expectLogin(String password, String expectedRedirect) throws Exception {
        String ip = "10.77.0." + (IP_SEQ.incrementAndGet() % 250 + 1);
        mvc.perform(post("/login").with(csrf()).with(r -> { r.setRemoteAddr(ip); return r; })
                        .param("compCd", comp).param("loginId", "park").param("password", password))
                .andExpect(redirectedUrl(expectedRedirect));
    }

    // ---------- 정상 ----------

    @Test
    void 바꾸면_새_비밀번호로만_로그인된다() throws Exception {
        change(PW, "Bright-orchard-2026").andExpect(status().isOk());
        expectLogin("Bright-orchard-2026", "/main");
        expectLogin(PW, "/login?error=bad");
        assertThat(accounts.query(comp, "select count(*) from sys_user where user_id = ? and pwd_changed_at is not null",
                Integer.class, userId)).isEqualTo(1);
    }

    // ---------- 정책 위반 ----------

    @Test
    void 정책_위반은_항목을_모두_알려준다() throws Exception {
        change(PW, "park1234").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_POLICY"))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details").value(hasItem(containsString("12자 이상"))))
                .andExpect(jsonPath("$.details").value(hasItem(containsString("아이디"))))
                .andExpect(content().string(not(containsString("park1234"))));
    }

    @Test
    void 흔한_비밀번호는_변형해도_막는다() throws Exception {
        for (String common : new String[] {"password1234", "Password123!", "qwer1234qwer", "!!1q2w3e4r5t", "Smartfactory2026"}) {
            change(PW, common).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details").value(hasItem(containsString("흔하거나"))));
        }
    }

    @Test
    void 이상한_비밀번호_입력() throws Exception {
        change(PW, "aaaaaaaaaaaaaaaa").andExpect(jsonPath("$.details").value(hasItem(containsString("같은 글자"))));
        change(PW, "가".repeat(30)).andExpect(jsonPath("$.details").value(hasItem(containsString("72바이트"))));
        change(PW, "x".repeat(65)).andExpect(status().isBadRequest());
        change(PW, "").andExpect(status().isBadRequest());
        change(PW, null).andExpect(status().isBadRequest());
        change(PW, PW).andExpect(jsonPath("$.details").value(hasItem(containsString("현재 비밀번호와 같습니다"))));
        change(PW, "Correct horse battery staple 🐴").andExpect(status().isOk()); // 공백·이모지 허용(NIST)
    }

    @Test
    void 회사가_조합_규칙을_켜면_적용된다() throws Exception {
        runner.run(comp, () -> policies.set(Policies.PWD_COMPLEXITY_REQUIRED, null, "Y", "test"));
        change(PW, "longlowercaseonly").andExpect(jsonPath("$.details").value(hasItem(containsString("3종류"))));
        change(PW, "Long-lower-Mixed9").andExpect(status().isOk());
    }

    @Test
    void 회사가_최소_길이를_올리면_적용된다() throws Exception {
        runner.run(comp, () -> policies.set(Policies.PWD_MIN_LENGTH, null, "20", "test"));
        change(PW, "Bright-orchard-26").andExpect(jsonPath("$.details").value(hasItem(containsString("20자 이상"))));
    }

    // ---------- 현재 비밀번호 ----------

    @Test
    void 현재_비밀번호가_틀리면_로그인_실패처럼_세고_잠기면_더_확인하지_않는다() throws Exception {
        for (int i = 0; i < 5; i++) {
            change("wrong-" + i, "Bright-orchard-2026").andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"));
        }
        assertThat(accounts.query(comp, "select lock_yn from sys_user where user_id = ?", String.class, userId))
                .isEqualTo("Y");
        // 잠긴 뒤에는 맞는 현재 비밀번호여도 거부 (세션을 가진 사람의 무차별 대입 방지)
        change(PW, "Bright-orchard-2026").andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"));
    }

    @Test
    void 저장_권한이_없으면_403() throws Exception {
        String other = accounts.newComp("PN");
        long viewer = accounts.newUser(other, "viewer", PW);
        accounts.grant(other, viewer, "VIEW", "sys-my-info", "SEARCH");
        mvc.perform(post("/api/platform/my-info/password").with(accounts.as(other, viewer, "viewer")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"Bright-orchard-2026\"}"))
                .andExpect(status().isForbidden());
    }

    // ---------- 신뢰 IP (공장 NAT) ----------

    @Test
    void 신뢰_IP는_IP단독_한도에_걸리지_않지만_IP_회사_한도는_그대로() throws Exception {
        String factoryIp = "203.0.113." + (IP_SEQ.incrementAndGet() % 250 + 1);
        runner.run(comp, () -> policies.set(Policies.LOGIN_TRUSTED_IPS, null, factoryIp, "test"));
        // 다른 회사들로 IP 단독 한도(100) 를 채운다 — 회사코드는 존재하지 않아도 IP 단독 키는 증가
        for (int i = 0; i < 100; i++) {
            String fakeComp = "ZZ" + i;
            mvc.perform(post("/login").with(csrf()).with(r -> { r.setRemoteAddr(factoryIp); return r; })
                    .param("compCd", fakeComp).param("loginId", "x").param("password", "x"));
        }
        // 이 회사는 공장 IP 를 신뢰 → IP 단독 한도에 막히지 않음
        mvc.perform(post("/login").with(csrf()).with(r -> { r.setRemoteAddr(factoryIp); return r; })
                        .param("compCd", comp).param("loginId", "park").param("password", PW))
                .andExpect(redirectedUrl("/main"));
        // 신뢰 등록이 없는 회사는 같은 IP 에서 차단
        String other = accounts.newComp("PT");
        accounts.newUser(other, "lee", PW);
        mvc.perform(post("/login").with(csrf()).with(r -> { r.setRemoteAddr(factoryIp); return r; })
                        .param("compCd", other).param("loginId", "lee").param("password", PW))
                .andExpect(redirectedUrl("/login?error=throttled"));
    }
}
