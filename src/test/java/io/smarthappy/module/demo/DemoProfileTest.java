package io.smarthappy.module.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 공개 데모 프로필(docs/23 3장, 이상 사용 T1·T2·T5·T6) — 체험 계정 비밀번호가 공개돼도
 * 비밀번호 변경·지원 세션 부여로 데모를 망가뜨리거나, 틀린 비밀번호로 계정을 잠가 남을 막을 수 없어야 한다.
 */
@AutoConfigureMockMvc
@ActiveProfiles("demo")
@TestPropertySource(properties = {
        "happyboot.dev.comp-cd=" + DemoProfileTest.COMP,
        "happyboot.demo.password=" + DemoProfileTest.PW})
class DemoProfileTest extends PostgresTestBase {

    static final String COMP = "DEMOTEST";
    static final String PW = "Demo-visit-2026!";
    static final String LOGIN_ID = "user1";   // application-demo.yml 기본값(2026-10-26 demo → user1)
    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    private static RequestPostProcessor from(String ip) {
        return r -> {
            r.setRemoteAddr(ip);
            return r;
        };
    }

    private static String newIp() {
        int n = IP_SEQ.incrementAndGet();
        return "10.77." + (n / 250) + "." + (n % 250 + 1);
    }

    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mvc.perform(post("/login").with(csrf()).with(from(newIp()))
                        .param("compCd", COMP).param("loginId", LOGIN_ID).param("password", PW))
                .andExpect(redirectedUrl("/main"))
                .andReturn().getRequest().getSession(false);
    }

    // ---------- 정상 ----------

    @Test
    void 체험_계정으로_로그인하고_데모_데이터가_보인다() throws Exception {
        MockHttpSession s = login();
        mvc.perform(get("/api/master/customers").session(s)).andExpect(status().isOk());
        assertThat(accounts.query(COMP, "select count(*) from mst_item", Integer.class)).isPositive();
        assertThat(accounts.query(COMP, "select count(*) from mst_cust", Integer.class)).isPositive();
        // 홈 H2(docs/37): 기본 홈은 home-main 조회 권한이 있을 때만 — 2026-10-26 부터 체험 계정도 홈을 본다(전 모듈 조회)
        mvc.perform(get("/api/platform/home").session(s)).andExpect(jsonPath("$.data").value("home-main"));
        // 업무 흐름 지도(docs/46 12장)도 본다
        mvc.perform(get("/api/home/flow-map/screens").session(s)).andExpect(status().isOk());
        // 다른 모듈 화면도 조회는 된다
        mvc.perform(get("/page/qms-ncr").session(s)).andExpect(status().isOk());
    }

    @Test
    void 체험_권한은_기준정보_영업_생산_POP_단말_전체와_나머지_업무_화면_조회와_시스템_조회뿐이다() {
        String open = "(prog_cd like 'mst-%' or prog_cd like 'sal-%' or prog_cd like 'prd-%' or prog_cd = 'sys-terminal')";
        String view = "(action_cd = 'SEARCH' and (prog_cd in ('sys-usage', 'sys-my-info', 'home-main', 'home-flow-map') or prog_cd !~ '^(sys|home)-'))";
        // 허용 목록 밖 권한이 하나도 없다 (비밀번호 변경·지원 세션·홈 카드 관리·앞으로 생길 시스템 관리 화면)
        assertThat(accounts.query(COMP, "select count(*) from sys_auth_prog where auth_grp_cd = 'DEMO_VIEWER' and not (" + open
                + " or " + view + ")", Integer.class)).isZero();
        // 나머지 업무 모듈(품질·창고·구매·설비·프로젝트·계획·설계변경·APQP)은 SEARCH 가 있는 화면 전부 조회만
        assertThat(accounts.query(COMP, "select count(*) from sys_auth_prog where auth_grp_cd = 'DEMO_VIEWER' and not " + open
                + " and prog_cd !~ '^(sys|home)-'", Integer.class)).isEqualTo(jdbc.queryForObject(
                "select count(*) from sys_prog p where 'SEARCH' = any(p.actions) and p.prog_cd !~ '^(sys|home)-'"
                + " and not (p.prog_cd like 'mst-%' or p.prog_cd like 'sal-%' or p.prog_cd like 'prd-%')", Integer.class));
        assertThat(accounts.query(COMP, "select count(*) from sys_auth_prog where auth_grp_cd = 'DEMO_VIEWER'"
                + " and prog_cd in ('sys-user', 'sys-auth-group', 'sys-policy', 'home-card-mgt', 'sys-support')", Integer.class))
                .as("시스템 관리·홈 카드 관리는 빠진다").isZero();
        // 기준정보·영업·생산·POP 단말 관리는 화면이 정의한 액션 전부
        assertThat(accounts.query(COMP, "select count(*) from sys_auth_prog where auth_grp_cd = 'DEMO_VIEWER' and " + open,
                Integer.class)).isEqualTo(jdbc.queryForObject(
                "select count(*) from sys_prog p, unnest(p.actions) a where " + open.replace("prog_cd", "p.prog_cd"), Integer.class));
        assertThat(accounts.query(COMP, "select count(distinct prog_cd) from sys_auth_prog where auth_grp_cd = 'DEMO_VIEWER'"
                + " and prog_cd in ('sal-order', 'sal-forecast', 'prd-wo', 'prd-lot', 'sys-terminal')", Integer.class)).isEqualTo(5);
        // 체험 계정은 ADMIN 이 아니다
        assertThat(accounts.query(COMP, """
                select string_agg(a.auth_grp_cd, ',') from sys_user_auth a join sys_user u using (comp_cd, user_id)
                 where u.login_id = 'user1'""", String.class)).isEqualTo("DEMO_VIEWER");
    }

    @Test
    void 로그인_화면에_회사_아이디_비밀번호_안내가_나오고_칸이_미리_채워진다() throws Exception {
        // 2026-10-26 사용자 결정: 손님이 바로 들어오게 공개 체험 비밀번호도 화면에(전에는 README 에만)
        mvc.perform(get("/login")).andExpect(status().isOk())
                .andExpect(content().string(containsString("DEMO")))
                .andExpect(content().string(containsString(LOGIN_ID)))
                .andExpect(content().string(containsString(PW)))
                .andExpect(content().string(containsString("value=\"" + LOGIN_ID + "\"")));
    }

    @Test
    void 디스크_보호_정책이_들어간다() {
        assertThat(accounts.query(COMP, "select policy_value from sys_policy where policy_key = 'FILE_MAX_MB'", String.class))
                .isEqualTo("2");
    }

    // ---------- 이상 사용 ----------

    @Test
    void T1_체험_계정으로_비밀번호를_바꿀_수_없다() throws Exception {
        mvc.perform(post("/api/platform/my-info/password").session(login()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"Hijacked-demo-99!\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_PERMISSION"));
        login();   // 원래 비밀번호로 여전히 들어간다
    }

    @Test
    void T2_체험_계정으로_운영자_지원_세션을_허락할_수_없다() throws Exception {
        mvc.perform(post("/api/platform/support/grants").session(login()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"opLoginId\":\"ops\",\"scopeCd\":\"LOGS\",\"reason\":\"demo\",\"hours\":24}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_PERMISSION"));
    }

    @Test
    void T5_틀린_비밀번호를_반복해도_계정은_잠기지_않고_그_IP만_막힌다() throws Exception {
        String attacker = newIp();
        String last = null;
        for (int i = 0; i < 25; i++) {
            last = mvc.perform(post("/login").with(csrf()).with(from(attacker))
                            .param("compCd", COMP).param("loginId", LOGIN_ID).param("password", "wrong-" + i))
                    .andReturn().getResponse().getRedirectedUrl();
        }
        assertThat(last).as("IP+회사 한도(20) 넘으면 차단").isEqualTo("/login?error=throttled");
        assertThat(accounts.query(COMP, "select lock_yn from sys_user where login_id = 'user1'", String.class)).isEqualTo("N");
        login();   // 다른 방문자(다른 IP)는 그대로 들어간다
    }

    @Test
    void T6_데모_비밀번호가_없거나_짧으면_기동을_멈춘다() {
        for (String bad : new String[] {null, "", "short-pw"}) {
            DemoAccountSeeder s = new DemoAccountSeeder(runner, jdbc, encoder);
            ReflectionTestUtils.setField(s, "compCd", COMP);
            ReflectionTestUtils.setField(s, "loginId", LOGIN_ID);
            ReflectionTestUtils.setField(s, "password", bad);
            assertThatThrownBy(() -> s.run(null)).as(String.valueOf(bad)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void 다시_돌려도_늘지_않고_비밀번호를_바꾸면_따라간다() throws Exception {
        DemoAccountSeeder s = new DemoAccountSeeder(runner, jdbc, encoder);
        ReflectionTestUtils.setField(s, "compCd", COMP);
        ReflectionTestUtils.setField(s, "loginId", LOGIN_ID);
        ReflectionTestUtils.setField(s, "password", "Another-demo-pw-77");
        s.run(null);
        assertThat(accounts.query(COMP, "select count(*) from sys_user where login_id = 'user1'", Integer.class)).isEqualTo(1);
        assertThat(accounts.query(COMP, "select count(*) from sys_menu where prog_cd = 'mst-cust'", Integer.class)).isEqualTo(1);
        assertThat(encoder.matches("Another-demo-pw-77",
                accounts.query(COMP, "select pwd_hash from sys_user where login_id = 'user1'", String.class))).isTrue();
        ReflectionTestUtils.setField(s, "password", PW);   // 다른 테스트를 위해 되돌림
        s.run(null);
    }
}
