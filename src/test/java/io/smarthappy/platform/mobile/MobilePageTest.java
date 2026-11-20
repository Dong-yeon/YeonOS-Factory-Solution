package io.smarthappy.platform.mobile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.security.SecurityConfig;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 모바일 웹앱 /m 화면 (docs/74) — 사무 화면과 같은 보안 체인을 타는지, 화면 권한이 사무 화면 progCd 그대로인지.
 * 비로그인 → /login, 권한 없음 → 403, 협력사 사용자 → /portal, 숨긴 단추의 API 를 직접 불러도 403(화면에서 숨긴 것은 막은 것이 아니다),
 * 로그인 뒤 이동(/m 을 열려던 경우만 고정 주소 /m), manifest 공개 범위, CSP.
 */
@AutoConfigureMockMvc
class MobilePageTest extends PostgresTestBase {

    private static final String PW = "Cedar-lantern-52";
    private static final String[] PAGES = {"/m", "/m/approval", "/m/find", "/m/ncr", "/m/status", "/m/me"};
    private static final AtomicInteger IP = new AtomicInteger(10);

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long plain;
    long full;
    RequestPostProcessor asPlain;
    RequestPostProcessor asFull;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("MB");
        plain = accounts.newUser(comp, "plain", PW);   // 권한그룹 없음
        full = accounts.newUser(comp, "full", PW);
        accounts.grant(comp, full, "MOBILE", "home-main", "SEARCH");
        accounts.grant(comp, full, "MOBILE", "prd-lot", "SEARCH");
        accounts.grant(comp, full, "MOBILE", "prd-wo", "SEARCH");
        accounts.grant(comp, full, "MOBILE", "cms-state", "SEARCH");
        accounts.grant(comp, full, "MOBILE", "qms-ncr", "SEARCH", "SAVE");
        accounts.grant(comp, full, "MOBILE", "sys-my-info", "SEARCH", "SAVE");
        asPlain = accounts.as(comp, plain, "plain");
        asFull = accounts.as(comp, full, "full");
    }

    private String page(String path, RequestPostProcessor who) throws Exception {
        return mvc.perform(get(path).with(who)).andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
                .andReturn().getResponse().getContentAsString();
    }

    private static RequestPostProcessor from() {
        String ip = "10.77." + IP.incrementAndGet() + ".9";   // 시험마다 다른 주소 — IP 기준 시도 제한에 걸리지 않게
        return req -> {
            req.setRemoteAddr(ip);
            return req;
        };
    }

    // ---------- 인증 ----------

    @Test
    void 비로그인은_모든_화면이_로그인으로() throws Exception {
        for (String p : PAGES) {
            mvc.perform(get(p)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        }
        // 화면이 부르는 API 는 401 JSON(경로 기준)
        mvc.perform(get("/api/home/cards")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 로그인_전에_모바일을_열려던_경우만_로그인_뒤_고정_주소_m_으로() throws Exception {
        // /m/approval?x=1 을 열려다 로그인 → 저장된 주소가 아니라 고정 /m
        MockHttpSession s1 = new MockHttpSession();
        mvc.perform(get("/m/approval").param("x", "1").session(s1)).andExpect(redirectedUrl("/login"));
        mvc.perform(post("/login").session(s1).with(csrf()).with(from())
                .with(req -> { req.setRequestedSessionId(s1.getId()); return req; })
                .param("compCd", comp).param("loginId", "full").param("password", PW)).andExpect(redirectedUrl("/m"));

        // 사무 화면을 열려던 경우·바로 로그인한 경우는 지금처럼 /main
        MockHttpSession s2 = new MockHttpSession();
        mvc.perform(get("/page/home-main").session(s2)).andExpect(redirectedUrl("/login"));
        mvc.perform(post("/login").session(s2).with(csrf()).with(from())
                .with(req -> { req.setRequestedSessionId(s2.getId()); return req; })
                .param("compCd", comp).param("loginId", "full").param("password", PW)).andExpect(redirectedUrl("/main"));
        mvc.perform(post("/login").with(csrf()).with(from())
                .param("compCd", comp).param("loginId", "full").param("password", PW)).andExpect(redirectedUrl("/main"));

        // /m 으로 시작만 하는 다른 경로(/main·/mfa·/mx)는 모바일이 아니다
        MockHttpSession s3 = new MockHttpSession();
        mvc.perform(get("/mx").session(s3)).andExpect(redirectedUrl("/login"));
        mvc.perform(post("/login").session(s3).with(csrf()).with(from())
                .with(req -> { req.setRequestedSessionId(s3.getId()); return req; })
                .param("compCd", comp).param("loginId", "full").param("password", PW)).andExpect(redirectedUrl("/main"));
    }

    @Test
    void 협력사_사용자는_모바일_화면에_못_온다() throws Exception {
        LoginUser sup = new LoginUser(plain, comp, "plain", "plain 사용자", "ko", false, LoginUser.SUPPLIER, 1L);
        RequestPostProcessor asSup = SecurityMockMvcRequestPostProcessors.authentication(UsernamePasswordAuthenticationToken
                .authenticated(sup, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        for (String p : PAGES) {
            mvc.perform(get(p).with(asSup)).andExpect(redirectedUrl("/portal"));
        }
    }

    // ---------- 화면 권한 = 사무 화면 progCd 그대로 ----------

    @Test
    void 권한이_있으면_여섯_화면이_열리고_탭과_조각이_보인다() throws Exception {
        String home = page("/m", asFull);
        assertThat(home).contains("href=\"/m/manifest.webmanifest\"", "/css/tokens.css", "/css/m.css", "/js/views/m/m-home.js",
                "href=\"/m/approval\"", "href=\"/m/find\"", "href=\"/m/status\"", "href=\"/m/me\"", "href=\"/m/ncr\"",
                "id=\"cards\"", "id=\"inbox\"", "id=\"aprCnt\"");
        assertThat(home).doesNotContain("<style", "style=\"", "onclick=");
        assertThat(page("/m/approval", asFull)).contains("id=\"approveBtn\"", "id=\"rejectBtn\"", "/js/common/hb.esign.js");
        assertThat(page("/m/find", asFull)).contains("data-can-lot=\"true\"", "data-can-equip=\"true\"", "id=\"kindSeg\"", "/js/common/hb.scan.js");
        assertThat(page("/m/ncr", asFull)).contains("id=\"ncrForm\"", "type=\"file\"", "accept=\"image/*\"");
        assertThat(page("/m/status", asFull)).contains("id=\"woList\"", "id=\"equipList\"");
        String me = page("/m/me", asFull);
        assertThat(me).contains("full 사용자", comp, "id=\"localeSel\"", "action=\"/logout\"", "name=\"_csrf\"");
        // 접속 로그 — 사무 화면 코드 + 설명 m
        assertThat(accounts.query(comp, """
                select count(*) from sys_access_log where event_cd = 'PAGE_VIEW' and detail = 'm' and user_id = ?
                   and prog_cd in ('home-main', 'sys-approval', 'prd-lot', 'qms-ncr', 'prd-wo', 'sys-my-info')""", Long.class, full))
                .isEqualTo(6L);
    }

    @Test
    void 권한이_없으면_찾기_부적합_현황은_403_홈과_내정보는_열리되_조각이_없다() throws Exception {
        for (String p : new String[] {"/m/find", "/m/ncr", "/m/status"}) {
            mvc.perform(get(p).with(asPlain)).andExpect(status().isForbidden());
        }
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'PERM_DENIED' and detail = 'm' and user_id = ?",
                Long.class, plain)).isEqualTo(3L);
        String home = page("/m", asPlain);
        assertThat(home).doesNotContain("href=\"/m/find\"", "href=\"/m/status\"", "href=\"/m/ncr\"", "id=\"cards\"");
        assertThat(page("/m/me", asPlain)).doesNotContain("id=\"localeSel\"");
    }

    @Test
    void 조회_권한만으로는_부적합_등록_화면이_열리지_않는다_한쪽_권한이면_그쪽만_보인다() throws Exception {
        long viewer = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewer, "VIEW", "qms-ncr", "SEARCH");
        accounts.grant(comp, viewer, "VIEW", "prd-lot", "SEARCH");
        RequestPostProcessor asViewer = accounts.as(comp, viewer, "viewer");
        mvc.perform(get("/m/ncr").with(asViewer)).andExpect(status().isForbidden());
        String find = page("/m/find", asViewer);
        assertThat(find).contains("data-can-lot=\"true\"", "data-can-equip=\"false\"").doesNotContain("id=\"kindSeg\"");
        mvc.perform(get("/m/status").with(asViewer)).andExpect(status().isForbidden());   // prd-wo·cms-state 둘 다 없음
    }

    // ---------- 숨긴 단추는 막은 것이 아니다 — 화면이 부르는 API 를 권한 없는 계정으로 직접 ----------

    @Test
    void 권한_없는_계정이_API_를_직접_부르면_403() throws Exception {
        String[] reads = {"/api/home/cards", "/api/production/lots/by-no?lotNo=L1", "/api/production/lots/1", "/api/production/wos",
                "/api/production/wos/1", "/api/cms/state/plants", "/api/cms/state?plantCd=P1", "/api/qms/ncrs/defects", "/api/qms/ncrs/1"};
        for (String url : reads) {
            mvc.perform(get(url).with(asPlain)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_PERMISSION"));
        }
        mvc.perform(post("/api/qms/ncrs").with(asPlain).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lotNo\":\"L1\",\"source\":\"PROCESS\",\"defectId\":1,\"ncQty\":1,\"description\":\"x\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_PERMISSION"));
        mvc.perform(post("/api/platform/my-info/locale").with(asPlain).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"localeCd\":\"en\"}")).andExpect(status().isForbidden());
        assertThat(accounts.query(comp, "select locale_cd from sys_user where user_id = ?", String.class, plain)).isNotEqualTo("en");
        // CSRF 없는 쓰기는 권한이 있어도 403
        mvc.perform(post("/api/platform/my-info/locale").with(asFull).contentType(MediaType.APPLICATION_JSON)
                .content("{\"localeCd\":\"en\"}")).andExpect(status().isForbidden());
    }

    @Test
    void 언어를_바꾸면_다음_화면부터_그_언어() throws Exception {
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/api/platform/my-info/locale").with(asFull).session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"localeCd\":\"en\"}")).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select locale_cd from sys_user where user_id = ?", String.class, full)).isEqualTo("en");
        String me = mvc.perform(get("/m/me").with(accounts.as(comp, full, "full", "en"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(me).contains("Open desktop view", "lang=\"en\"");
    }

    // ---------- PWA ----------

    @Test
    void 모바일_manifest_는_로그인_없이_받고_이웃_경로는_아니다() throws Exception {
        var m = mvc.perform(get("/m/manifest.webmanifest")).andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY)).andReturn().getResponse();
        assertThat(m.getContentType()).startsWith("application/manifest+json");
        String manifest = m.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(JsonPath.<String>read(manifest, "$.start_url")).isEqualTo(MobilePageController.HOME);
        assertThat(JsonPath.<String>read(manifest, "$.scope")).isEqualTo("/");
        for (String icon : JsonPath.<List<String>>read(manifest, "$.icons[*].src")) {
            mvc.perform(get(icon)).andExpect(status().isOk());
        }
        for (String p : new String[] {"/m/manifest.webmanifest/x", "/m/manifest.json", "/m/Manifest.webmanifest"}) {
            assertThat(mvc.perform(get(p)).andReturn().getResponse().getStatus()).as(p).isNotEqualTo(200);
        }
        // 보안 설정의 글자 상수가 컨트롤러 경로와 같다
        assertThat(SecurityConfig.MOBILE_HOME).isEqualTo(MobilePageController.HOME);
        assertThat(SecurityConfig.MOBILE_MANIFEST).isEqualTo(MobileManifestController.PATH);
    }
}
