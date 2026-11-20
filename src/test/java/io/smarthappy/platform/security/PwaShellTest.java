package io.smarthappy.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PWA 셸 서버 쪽 (docs/45 M-1, 2.2). 워커·manifest·아이콘은 로그인 전에 받는다(로그인 화면에서 설치) — 업무 데이터 없음.
 * CSP 는 그대로(가드 약화 없음) — 이 파일들도 같은 CSP 헤더를 받는다. 공개 허용이 이웃 경로로 새지 않는지(/sw.js 뒤에 붙인 경로 등).
 * 브라우저 동작(등록·캐시 범위·로그아웃 비우기)은 실제 Chromium 실측(docs/45 2.2)과 node 단위 시험(hb.pwa.test.js)
 */
@AutoConfigureMockMvc
class PwaShellTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Test
    void 워커_manifest_아이콘은_로그인_없이_받고_CSP_는_그대로() throws Exception {
        String sw = mvc.perform(get("/sw.js")).andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY))
                .andReturn().getResponse().getContentAsString();
        assertThat(sw).contains("importScripts('/js/common/hb.pwa.js')", "isCacheable");
        String ct = mvc.perform(get("/sw.js")).andReturn().getResponse().getContentType();
        assertThat(ct).contains("javascript");

        var m = mvc.perform(get("/manifest.webmanifest")).andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", SecurityConfig.CONTENT_SECURITY_POLICY)).andReturn().getResponse();
        assertThat(m.getContentType()).startsWith("application/manifest+json");
        String manifest = m.getContentAsString();
        assertThat(JsonPath.<String>read(manifest, "$.start_url")).isEqualTo("/main");
        assertThat(JsonPath.<String>read(manifest, "$.scope")).isEqualTo("/");
        assertThat(JsonPath.<String>read(manifest, "$.display")).isEqualTo("standalone");
        for (String icon : JsonPath.<java.util.List<String>>read(manifest, "$.icons[*].src")) {
            mvc.perform(get(icon)).andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/png"));
        }
        mvc.perform(get("/images/pwa/apple-touch-icon-180.png")).andExpect(status().isOk());
        mvc.perform(get("/js/common/hb.pwa.js")).andExpect(status().isOk());
    }

    @Test
    void 공개_허용은_정확히_두_파일_이웃_경로는_로그인으로() throws Exception {
        for (String p : new String[] {"/sw.js/x", "/sw.json", "/manifest.webmanifest/x", "/SW.js", "/sw", "/pwa/sw.js"}) {
            int s = mvc.perform(get(p)).andReturn().getResponse().getStatus();
            assertThat(s).as(p).isNotEqualTo(200);
        }
    }

    @Test
    void 로그인_화면과_셸이_manifest_와_워커_스크립트를_싣는다() throws Exception {
        String login = mvc.perform(get("/login")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(login).contains("rel=\"manifest\" href=\"/manifest.webmanifest\"", "/js/common/hb.pwa.js",
                "rel=\"apple-touch-icon\"", "name=\"theme-color\"");

        String comp = accounts.newComp("PW");
        long u = accounts.newUser(comp, "pw1", "Pwa-shell-pw-1!");
        String main = mvc.perform(get("/main").with(accounts.as(comp, u, "pw1"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(main).contains("rel=\"manifest\" href=\"/manifest.webmanifest\"", "/js/common/hb.pwa.js");
    }
}
