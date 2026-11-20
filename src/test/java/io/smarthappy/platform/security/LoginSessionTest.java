package io.smarthappy.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/** 로그인·세션·요청 회사 범위 — 정상 흐름 + 이상 사용 (P0 3단계) */
@AutoConfigureMockMvc
class LoginSessionTest extends PostgresTestBase {

    private static final String PW = "Correct-pw-1!";
    private static final java.util.concurrent.atomic.AtomicInteger IP_SEQ = new java.util.concurrent.atomic.AtomicInteger();

    /** 테스트마다 다른 접속 주소 — 접속 주소 기준 시도 제한(LoginAttemptLimiter)이 테스트끼리 섞이지 않게 */
    String ip;

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long userId;

    @BeforeEach
    void setUp() {
        ip = "10.1." + (IP_SEQ.incrementAndGet() / 250) + "." + (IP_SEQ.get() % 250 + 1);
        comp = accounts.newComp("LG");
        userId = accounts.newUser(comp, "kim", PW);
        accounts.addPlant(comp, "P1", comp + " 1공장");
    }

    // ---------- 정상 흐름 ----------

    @Test
    void 로그인하면_main으로_가고_세션API가_자기_회사를_돌려준다() throws Exception {
        MockHttpSession session = login(comp, "kim", PW);
        mvc.perform(get("/api/platform/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("OK"))
                .andExpect(jsonPath("$.data.compCd").value(comp))
                .andExpect(jsonPath("$.data.loginId").value("kim"))
                .andExpect(jsonPath("$.list").doesNotExist());
    }

    @Test
    void 로그인_사용자의_회사_데이터만_보인다() throws Exception {
        String other = accounts.newComp("LO");
        accounts.addPlant(other, "P1", other + " 1공장");
        MockHttpSession session = login(comp, "kim", PW);
        mvc.perform(get("/test-probe/my-plants").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0]").value(comp + " 1공장"));
        assertThat(TenantContext.currentCompCd()).as("요청 후 회사 범위가 스레드에 남지 않음").isEmpty();
    }

    @Test
    void 회사코드_소문자_공백_아이디_대소문자는_허용한다() throws Exception {
        MockHttpSession session = login("  " + comp.toLowerCase() + " ", " KIM ", PW);
        mvc.perform(get("/test-probe/my-comp").session(session)).andExpect(content().string(comp));
    }

    @Test
    void 성공하면_실패횟수가_0으로_돌아가고_마지막_로그인이_기록된다() throws Exception {
        loginFail(comp, "kim", "wrong-1");
        loginFail(comp, "kim", "wrong-2");
        login(comp, "kim", PW);
        assertThat(accounts.query(comp, "select login_fail_cnt from sys_user where user_id = ?", Integer.class, userId)).isZero();
        assertThat(accounts.query(comp, "select count(*) from sys_user where user_id = ? and last_login_at is not null",
                Integer.class, userId)).isEqualTo(1);
    }

    @Test
    void 비밀번호는_해시로만_저장된다() {
        String hash = accounts.query(comp, "select pwd_hash from sys_user where user_id = ?", String.class, userId);
        assertThat(hash).startsWith("{bcrypt}").doesNotContain(PW);
    }

    @Test
    void 로그아웃하면_세션이_끊긴다() throws Exception {
        MockHttpSession session = login(comp, "kim", PW);
        mvc.perform(post("/logout").session(session).with(csrf())).andExpect(redirectedUrl("/login?logout"));
        mvc.perform(get("/api/platform/session").session(session)).andExpect(status().isUnauthorized());
    }

    // ---------- 이상 사용: 입력 ----------

    @Test
    void 틀린_비밀번호_없는_사용자_없는_회사는_모두_같은_오류() throws Exception {
        expectLoginError(comp, "kim", "wrong", "bad");
        expectLoginError(comp, "nobody", PW, "bad");
        expectLoginError("NOCOMP" + userId, "kim", PW, "bad");
    }

    @Test
    void 형식이_이상한_입력은_500없이_같은_오류() throws Exception {
        expectLoginError("", "kim", PW, "bad");
        expectLoginError("한글회사", "kim", PW, "bad");
        expectLoginError(comp, "", PW, "bad");
        expectLoginError(comp, "kim", "", "bad");
        expectLoginError(comp, "x".repeat(10_000), PW, "bad");
        expectLoginError(comp, "kim' or '1'='1", PW, "bad");
        expectLoginError(comp + "'; drop table sys_user;--", "kim", PW, "bad");
        expectLoginError(comp, "kim", "가".repeat(30), "bad");        // 90바이트 > bcrypt 72바이트 한도
        expectLoginError(comp, "kim", "p".repeat(100_000), "bad");
        expectLoginError(comp, "kim😀", PW, "bad");
    }

    @Test
    void 로그인_파라미터가_아예_없어도_500이_아니다() throws Exception {
        mvc.perform(post("/login").with(csrf()).with(from(ip))).andExpect(redirectedUrl("/login?error=bad"));
    }

    @Test
    void 로그인_화면의_error_파라미터를_조작해도_임의_문구가_나오지_않는다() throws Exception {
        mvc.perform(get("/login").param("error", "<script>alert(1)</script>"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<script>alert(1)</script>"))))
                .andExpect(content().string(not(containsString("role=\"alert\""))));
    }

    @Test
    void 로그인_화면은_폼이_브랜드_패널보다_먼저이고_그림은_로그인_없이_CSP_아래에서_열린다() throws Exception {
        // 언어를 지정한다 — 지정하지 않으면 JVM 기본 언어를 따라 영어 그림이 나올 수 있다
        String html = mvc.perform(get("/login").header("Accept-Language", "ko")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // 키보드·스크린리더가 폼에 먼저 닿도록 DOM 순서: main(폼) → aside(브랜드). 장식 그림은 alt=""
        assertThat(html.indexOf("<main")).isPositive().isLessThan(html.indexOf("<aside"));
        assertThat(html).contains("autocomplete=\"current-password\"", "id=\"statusNotice\"", "/images/yeonos-platform.svg\" alt=\"\"");
        for (String img : new String[] {"/images/yeonos-platform.svg", "/images/yeonos-platform-en.svg", "/images/yeonos-mark.svg"}) {
            mvc.perform(get(img)).andExpect(status().isOk())
                    .andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY));
        }
    }

    @Test
    void 로그인_화면_그림은_화면_언어를_따른다() throws Exception {
        // 그림 속 글자(모듈·키 이름)도 i18n 대상 — 영어 화면은 영어 그림, 그 밖은 한국어 그림
        String en = mvc.perform(get("/login").header("Accept-Language", "en")).andReturn().getResponse().getContentAsString();
        assertThat(en).contains("/images/yeonos-platform-en.svg\" alt=\"\"");
        String ko = mvc.perform(get("/login").header("Accept-Language", "ko")).andReturn().getResponse().getContentAsString();
        assertThat(ko).contains("/images/yeonos-platform.svg\" alt=\"\"").doesNotContain("yeonos-platform-en.svg");
        // 두 그림은 글자만 다르다: 영어판에 한글이 남지 않는다(주석 제외)
        String svg = mvc.perform(get("/images/yeonos-platform-en.svg")).andReturn().getResponse().getContentAsString()
                .replaceAll("(?s)<!--.*?-->", "");
        assertThat(svg).doesNotContainPattern("[가-힣]");
    }

    @Test
    void 로그인_미리_채우기는_기본으로_꺼져_있다() throws Exception {
        // local 프로필에서만 켠다(application-local.yml). test·prod 에서는 회사·아이디가 비어 있어야 한다
        String html = mvc.perform(get("/login")).andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("value=\"DEMO\"").doesNotContain("value=\"admin\"");
        assertThat(html).containsPattern("id=\"compCd\"[^>]*>").doesNotContainPattern("id=\"compCd\"[^>]*value=\"[^\"]+\"");
    }

    @Test
    void 로그인_화면_그림은_스크립트_없이_표현_속성만_쓴다() throws Exception {
        // SVG 도 이 앱의 CSP(script-src·style-src 'self')를 받으므로 <script>·<style>·on* 를 쓰지 않는다
        for (String img : new String[] {"/images/yeonos-platform.svg", "/images/yeonos-platform-en.svg", "/images/yeonos-mark.svg"}) {
            String svg = mvc.perform(get(img)).andReturn().getResponse().getContentAsString();
            assertThat(svg).doesNotContainIgnoringCase("<script").doesNotContainIgnoringCase("<style")
                    .doesNotContainPattern("\\son[a-z]+=").doesNotContain("href=\"http");
        }
    }

    @Test
    void CSRF_토큰_없는_로그인은_거부된다() throws Exception {
        mvc.perform(post("/login").param("compCd", comp).param("loginId", "kim").param("password", PW))
                .andExpect(status().isForbidden());
    }

    // ---------- 이상 사용: 잠금·사용중지 ----------

    @Test
    void 다섯번_틀리면_잠기고_맞는_비밀번호도_거부된다() throws Exception {
        for (int i = 0; i < 5; i++) {
            expectLoginError(comp, "kim", "wrong-" + i, "bad");
        }
        expectLoginError(comp, "kim", PW, "locked");
    }

    @Test
    void 잠긴_계정에_틀린_비밀번호면_잠김을_알려주지_않는다() throws Exception {
        for (int i = 0; i < 5; i++) {
            loginFail(comp, "kim", "wrong-" + i);
        }
        expectLoginError(comp, "kim", "still-wrong", "bad");
    }

    @Test
    void 사용중지_사용자는_맞는_비밀번호일때만_사용불가로_안내() throws Exception {
        accounts.setUserUse(comp, userId, "N");
        expectLoginError(comp, "kim", "wrong", "bad");
        expectLoginError(comp, "kim", PW, "inactive");
    }

    @Test
    void 정지된_회사는_로그인할_수_없다() throws Exception {
        accounts.setCompStatus(comp, "SUSPENDED");
        expectLoginError(comp, "kim", PW, "inactive");
    }

    @Test
    void 사용중지_사용자는_실패횟수가_올라가지_않는다() throws Exception {
        accounts.setUserUse(comp, userId, "N");
        expectLoginError(comp, "kim", "wrong", "bad");
        assertThat(accounts.query(comp, "select login_fail_cnt from sys_user where user_id = ?", Integer.class, userId)).isZero();
    }

    // ---------- 이상 사용: 이미 로그인된 세션 ----------

    @Test
    void 로그인_중_사용중지되면_다음_요청에서_끊긴다() throws Exception {
        MockHttpSession session = login(comp, "kim", PW);
        accounts.setUserUse(comp, userId, "N");
        mvc.perform(get("/api/platform/session").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void 로그인_중_회사가_정지되면_화면요청은_로그인으로_보낸다() throws Exception {
        MockHttpSession session = login(comp, "kim", PW);
        accounts.setCompStatus(comp, "SUSPENDED");
        mvc.perform(get("/main").session(session)).andExpect(redirectedUrl("/login?error=inactive"));
    }

    @Test
    void 로그인하면_세션ID가_바뀐다_세션고정_방지() throws Exception {
        MockHttpSession before = new MockHttpSession();
        String beforeId = before.getId();
        // 브라우저처럼 그 세션 id 를 요청에 싣는다(쿠키) — Spring Security 는 "요청한 세션 id 가 유효"할 때만 id 를 바꾼다.
        // spring-test 7.0.10 부터 가짜 요청이 이것을 실제처럼 판정한다(전에는 늘 유효) — 실제 Tomcat 에서 로그인 전후 id 가 바뀌는 것도 확인(S-1)
        MvcResult r = mvc.perform(post("/login").session(before).with(csrf()).with(from(ip))
                        .with(req -> { req.setRequestedSessionId(beforeId); return req; })
                        .param("compCd", comp).param("loginId", "kim").param("password", PW))
                .andExpect(redirectedUrl("/main")).andReturn();
        assertThat(r.getRequest().getSession(false).getId()).isNotEqualTo(beforeId);
    }

    @Test
    void 로그인된_사용자라도_CSRF_없는_API_쓰기는_403() throws Exception {
        MockHttpSession session = login(comp, "kim", PW);
        mvc.perform(post("/api/platform/session").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ---------- 비로그인 ----------

    @Test
    void 비로그인_API는_401_JSON_화면은_로그인으로() throws Exception {
        mvc.perform(get("/api/platform/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/main")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    // ---------- 회사 간 ----------

    @Test
    void 같은_아이디가_두_회사에_있어도_각자_회사로_로그인된다() throws Exception {
        String other = accounts.newComp("LB");
        accounts.newUser(other, "kim", "Other-pw-2!");
        MockHttpSession a = login(comp, "kim", PW);
        MockHttpSession b = login(other, "kim", "Other-pw-2!");
        mvc.perform(get("/test-probe/my-comp").session(a)).andExpect(content().string(comp));
        mvc.perform(get("/test-probe/my-comp").session(b)).andExpect(content().string(other));
        expectLoginError(comp, "kim", "Other-pw-2!", "bad"); // B사 비밀번호로 A사 로그인 불가
    }

    @Test
    void 같은_회사에_대소문자만_다른_아이디는_만들_수_없다() {
        assertThatThrownBy(() -> accounts.newUser(comp, "KIM", "x-pw-1!"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void 한번_틀리면_실패횟수는_정확히_1_늘어난다() throws Exception {
        expectLoginError(comp, "kim", "wrong", "bad");
        assertThat(accounts.query(comp, "select login_fail_cnt from sys_user where user_id = ?", Integer.class, userId))
                .as("인증 관리자 이중 호출로 2씩 늘던 회귀 방지").isEqualTo(1);
    }

    @Test
    void 다섯번째_실패에서_잠기고_네번째까지는_안잠긴다() throws Exception {
        for (int i = 0; i < 4; i++) {
            expectLoginError(comp, "kim", "wrong-" + i, "bad");
        }
        login(comp, "kim", PW);
    }

    // ---------- 이상 사용: 잠금 자동 해제 (V004) ----------

    @Test
    void 실패잠금은_시간이_지나면_자동으로_풀린다() throws Exception {
        lockByFailures();
        expectLoginError(comp, "kim", PW, "locked");
        expireLock();
        login(comp, "kim", PW);
        assertThat(accounts.query(comp, "select lock_yn || login_fail_cnt || coalesce(locked_until::text, '-') from sys_user where user_id = ?",
                String.class, userId)).isEqualTo("N0-");
    }

    @Test
    void 자동해제_뒤_다시_틀리면_실패횟수는_1부터() throws Exception {
        lockByFailures();
        expireLock();
        expectLoginError(comp, "kim", "wrong-again", "bad");
        assertThat(accounts.query(comp, "select lock_yn || login_fail_cnt from sys_user where user_id = ?", String.class, userId))
                .isEqualTo("N1");
    }

    @Test
    void 남이_틀려서_생긴_실패잠금은_이미_로그인한_세션을_끊지_않는다() throws Exception {
        MockHttpSession session = login(comp, "kim", PW);
        lockByFailures();
        mvc.perform(get("/api/platform/session").session(session)).andExpect(status().isOk());
    }

    @Test
    void 관리자_수동잠금은_시간이_지나도_안풀리고_기존_세션도_끊는다() throws Exception {
        MockHttpSession session = login(comp, "kim", PW);
        accounts.exec(comp, "update sys_user set lock_yn = 'Y', locked_until = null where user_id = ?", userId);
        mvc.perform(get("/api/platform/session").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        expectLoginError(comp, "kim", PW, "locked");
    }

    // ---------- 세션 절대 수명 (docs/05 G7) ----------

    @Test
    void 로그인하면_로그인_시각이_세션에_남는다() throws Exception {
        MockHttpSession session = login(comp, "kim", PW);
        assertThat(session.getAttribute(TenantRequestFilter.LOGIN_AT)).isInstanceOf(Long.class);
    }

    @Test
    void 계속_사용해도_12시간이_지나면_끝난다() throws Exception {
        MockHttpSession session = login(comp, "kim", PW);
        session.setAttribute(TenantRequestFilter.LOGIN_AT,
                System.currentTimeMillis() - java.time.Duration.ofHours(12).plusMinutes(1).toMillis());
        mvc.perform(get("/api/platform/session").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void 열두시간_전이면_계속_쓸_수_있고_화면은_만료되면_로그인으로() throws Exception {
        MockHttpSession session = login(comp, "kim", PW);
        session.setAttribute(TenantRequestFilter.LOGIN_AT,
                System.currentTimeMillis() - java.time.Duration.ofHours(11).plusMinutes(59).toMillis());
        mvc.perform(get("/api/platform/session").session(session)).andExpect(status().isOk());

        session.setAttribute(TenantRequestFilter.LOGIN_AT, 0L);
        mvc.perform(get("/main").session(session)).andExpect(redirectedUrl("/login?expired"));
    }

    @Test
    void 로그인_시각을_조작할_수단이_없다_세션_속성은_서버만_쓴다() throws Exception {
        MockHttpSession session = login(comp, "kim", PW);
        Object before = session.getAttribute(TenantRequestFilter.LOGIN_AT);
        mvc.perform(get("/api/platform/session").session(session)
                .param(TenantRequestFilter.LOGIN_AT, "9999999999999")
                .header("X-Login-At", "9999999999999")).andExpect(status().isOk());
        assertThat(session.getAttribute(TenantRequestFilter.LOGIN_AT)).isEqualTo(before);
    }

    // ---------- 보안 헤더 (docs/05 4.4) ----------

    @Test
    void 보안_헤더가_붙는다() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Security-Policy", containsString("script-src 'self'")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Security-Policy", not(containsString("unsafe-inline"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Security-Policy", containsString("object-src 'none'")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Referrer-Policy", "same-origin"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("X-Content-Type-Options", "nosniff"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .exists("Permissions-Policy"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cross-Origin-Opener-Policy", "same-origin"));
        // 운영자 콘솔(허용 IP 에서만 응답)도 같은 헤더
        mvc.perform(get("/ops/login").with(r -> { r.setRemoteAddr("127.0.0.1"); return r; }))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cross-Origin-Opener-Policy", "same-origin"));
    }

    /** docs/67 F11 — 공용 PC: HTTPS 로그아웃은 브라우저 캐시만 비운다(저장소·서비스 워커는 그대로), http 에는 붙지 않는다 */
    @Test
    void HTTPS_로그아웃은_캐시만_비우라고_알린다() throws Exception {
        MockHttpSession session = login(comp, "kim", PW);
        mvc.perform(post("/logout").session(session).with(csrf()).secure(true))
                .andExpect(redirectedUrl("/login?logout"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Clear-Site-Data", "\"cache\""));
        MockHttpSession plain = login(comp, "kim", PW);
        mvc.perform(post("/logout").session(plain).with(csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().doesNotExist("Clear-Site-Data"));
    }

    // ---------- 이상 사용: 접속 주소 기준 시도 제한 ----------

    @Test
    void 같은_IP에서_한_회사로_20번_실패하면_맞는_비밀번호도_잠시_차단() throws Exception {
        for (int i = 0; i < 20; i++) {
            expectLoginError(comp, "nobody" + i, "x", "bad"); // 없는 계정이라 계정 잠금과 무관
        }
        expectLoginError(comp, "kim", PW, "throttled");
        expectLoginError(comp, "kim", PW, "throttled"); // 차단 중 시도
        // 다른 곳(IP)에서는 정상 로그인 — 남의 IP 를 막지 않는다
        String otherIp = ip;
        ip = "10.250.0.1";
        login(comp, "kim", PW);
        ip = otherIp;
        assertThat(accounts.query(comp, "select login_fail_cnt from sys_user where user_id = ?", Integer.class, userId))
                .as("차단된 시도는 계정 실패 횟수에 들어가지 않음").isZero();
    }

    @Test
    void 성공은_자기_실패만_지우고_다른_계정에_대한_시도_한도는_남긴다() throws Exception {
        // 내 오타는 성공하면 지워진다(공장 NAT 뒤 여러 사람 오타가 쌓여 막히지 않게)
        for (int i = 0; i < 4; i++) {
            expectLoginError(comp, "kim", "wrong-" + i, "bad");
        }
        login(comp, "kim", PW);
        // 다른 계정들에 대한 실패 19건 — 내 성공으로 되돌릴 수 없다(docs/67 F5, 예전에는 통째로 초기화)
        for (int i = 0; i < 19; i++) {
            expectLoginError(comp, "nobody" + i, "x", "bad");
        }
        login(comp, "kim", PW);
        expectLoginError(comp, "nobody-last", "x", "bad");   // 20번째 → 한도
        expectLoginError(comp, "kim", PW, "throttled");
    }

    // ---------- helpers ----------

    private void lockByFailures() throws Exception {
        String me = ip;
        ip = "10.251.0." + (IP_SEQ.incrementAndGet() % 250 + 1); // 공격자는 다른 IP
        for (int i = 0; i < 5; i++) {
            expectLoginError(comp, "kim", "wrong-" + i, "bad");
        }
        ip = me;
    }

    private void expireLock() {
        accounts.exec(comp, "update sys_user set locked_until = now() - interval '1 minute' where user_id = ?", userId);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private MockHttpSession login(String compCd, String loginId, String password) throws Exception {
        MvcResult r = mvc.perform(post("/login").with(csrf()).with(from(ip))
                        .param("compCd", compCd).param("loginId", loginId).param("password", password))
                .andExpect(redirectedUrl("/main"))
                .andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private void loginFail(String compCd, String loginId, String password) throws Exception {
        mvc.perform(post("/login").with(csrf()).with(from(ip))
                .param("compCd", compCd).param("loginId", loginId).param("password", password));
    }

    private void expectLoginError(String compCd, String loginId, String password, String code) throws Exception {
        mvc.perform(post("/login").with(csrf()).with(from(ip))
                        .param("compCd", compCd).param("loginId", loginId).param("password", password))
                .andExpect(redirectedUrl("/login?error=" + code));
    }
}
