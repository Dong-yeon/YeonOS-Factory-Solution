package io.smarthappy.module.pop;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * 단말 유형별 경로 (docs/25 4.33, WMS B7 PDA 경계) — 창고 화면·API(/pop/wh, /api/pop/wh) = PDA 만, 공정 할 일·실적 API = POP 만,
 * 세션·배지 = 둘 다. 다른 유형이 부르면 API 403·화면은 자기 홈, PDA 의 /pop = /pop/wh 로. 세션 응답에 단말 유형.
 * 창고 경로도 같은 단말 인증(쿠키 없음·사무 로그인 = 401)·CSRF 를 탄다(WMS 컨트롤러는 아직 없음 → 통과하면 404).
 * 수집 장치(COLLECTOR, /ext Bearer)는 다른 체인 — 회귀는 EquipEventTest.
 */
@AutoConfigureMockMvc
class PopTerminalTypeTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    String ip;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("TT");
        ip = "10.84." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "ADM", "sys-terminal", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** 단말 등록(작업장 없이 공장만) → 활성화 → 단말 쿠키 값 */
    private Cookie terminal(String cd, String type) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\",\"terminalType\":\"" + type + "\"}").with(admin)).andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        MvcResult a = mvc.perform(json(post("/api/pop/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return new Cookie(PopAuthService.COOKIE, set.substring(set.indexOf('=') + 1, set.indexOf(';')));
    }

    @Test
    void PDA_는_창고_경로만_공정_API_는_403_홈은_창고로() throws Exception {
        Cookie pda = terminal("PDA-01", "PDA");
        mvc.perform(get("/api/pop/session").cookie(pda)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.terminalType").value("PDA")).andExpect(jsonPath("$.data.nodeCd").doesNotExist());
        // 창고 경로는 통과(WMS 컨트롤러가 아직 없어 404 — 인증·권한에서 막히지 않았다는 뜻)
        mvc.perform(get("/api/pop/wh/stock").cookie(pda)).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/pop/wh/move"), "{}").cookie(pda)).andExpect(status().isNotFound());
        // 공정 할 일·실적 API = POP 만
        mvc.perform(get("/api/pop/home").cookie(pda)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/pop/procs/1").cookie(pda)).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/pop/procs/1/results"), "{}").cookie(pda)).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/pop/results/1/correct"), "{}").cookie(pda)).andExpect(status().isForbidden());
        mvc.perform(get("/api/pop/lots").cookie(pda)).andExpect(status().isForbidden());
        // 둘 다 쓰는 것 — 배지(작업자 로그인)는 유형과 관계없이 체인 통과(없는 배지 = 업무 오류)
        mvc.perform(json(post("/api/pop/badge"), "{\"badge\":\"NOPE\"}").cookie(pda)).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not("FORBIDDEN")));
        // 화면: /pop → 창고 홈으로
        mvc.perform(get("/pop").cookie(pda)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/pop/wh"));
    }

    @Test
    void POP_은_창고_경로_403_화면은_자기_홈() throws Exception {
        Cookie pop = terminal("POP-01", "POP");
        mvc.perform(get("/api/pop/session").cookie(pop)).andExpect(status().isOk()).andExpect(jsonPath("$.data.terminalType").value("POP"));
        mvc.perform(get("/api/pop/home").cookie(pop)).andExpect(status().isOk());
        mvc.perform(get("/api/pop/wh/stock").cookie(pop)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(json(post("/api/pop/wh/move"), "{}").cookie(pop)).andExpect(status().isForbidden());
        mvc.perform(get("/pop/wh").cookie(pop)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/pop"));
        mvc.perform(get("/pop/wh/move").cookie(pop)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/pop"));
        mvc.perform(get("/pop").cookie(pop)).andExpect(status().isOk()).andExpect(view().name("pop/home"));
    }

    @Test
    void 창고_경로도_같은_단말_인증_CSRF() throws Exception {
        // 쿠키 없음: API 401(단말 미인증), 화면은 활성화로
        mvc.perform(get("/api/pop/wh/stock")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TERMINAL_UNAUTHENTICATED"));
        mvc.perform(get("/pop/wh")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/pop/activate"));
        // 사무 로그인(사람 계정)은 단말이 아니다 — 시험 도구가 인증을 요청에 바로 넣어 "인증됐지만 단말 권한 없음" 403
        // (실제 사무 세션 쿠키는 이 체인이 세션 저장소를 쓰지 않아 인증으로 읽히지 않는다 = 401). 어느 쪽이든 막힌다
        mvc.perform(get("/api/pop/wh/stock").with(admin)).andExpect(status().isForbidden());
        // 위조·폐기 쿠키
        mvc.perform(get("/api/pop/wh/stock").cookie(new Cookie(PopAuthService.COOKIE, comp + ".1.forged"))).andExpect(status().isUnauthorized());
        // CSRF 없는 쓰기 = 403(창고 경로도 같은 쿠키 CSRF)
        Cookie pda = terminal("PDA-02", "PDA");
        mvc.perform(post("/api/pop/wh/move").contentType(MediaType.APPLICATION_JSON).content("{}").cookie(pda))
                .andExpect(status().isForbidden());
        // 폐기한 PDA 는 다음 요청에서 바로 끊긴다
        long id = accounts.query(comp, "select terminal_id from sys_terminal where terminal_cd = 'PDA-02'", Long.class);
        int ver = accounts.query(comp, "select row_ver from sys_terminal where terminal_id = ?", Integer.class, id);   // 활성화로 올라감
        mvc.perform(json(post("/api/production/terminals/" + id + "/revoke"), "{\"reason\":\"분실\",\"rowVer\":" + ver + "}").with(admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/pop/wh/stock").cookie(pda)).andExpect(status().isUnauthorized());
    }

    @Test
    void B사_단말_쿠키는_B사_세션() throws Exception {
        Cookie mine = terminal("PDA-A", "PDA");
        mvc.perform(get("/api/pop/session").cookie(mine)).andExpect(jsonPath("$.data.compCd").value(comp))
                .andExpect(jsonPath("$.data.terminalCd").value("PDA-A"));
        // A사 단말 id 에 B사 회사 코드를 붙인 쿠키 — 인증 안 됨
        String value = mine.getValue();
        String other = accounts.newComp("TU");
        mvc.perform(get("/api/pop/wh/stock").cookie(new Cookie(PopAuthService.COOKIE, other + value.substring(value.indexOf('.')))))
                .andExpect(status().isUnauthorized());
    }
}
