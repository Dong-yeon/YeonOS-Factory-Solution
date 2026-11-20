package io.smarthappy.platform.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.hamcrest.Matchers;
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

import io.smarthappy.module.master.workforce.WorkerService;
import io.smarthappy.module.pop.PopAuthService;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * 접속 로그 단말 종류 (docs/50 Q9, 사용자 결정 2026-10-27) — 단말 요청은 화면 헤더(X-HB-Client)가 아니라 서버에 등록된 유형으로.
 * PDA 창고 화면은 hb.pop.api 로 "POP" 헤더를 보내지만 PDA 로 남는다. 사무 사용자는 지금처럼 헤더 참고값.
 * 접속 로그 화면 조회 조건 clientType 과 A사로 B사.
 */
@AutoConfigureMockMvc
class TerminalClientTypeTest extends PostgresTestBase {

    static final String PW = "Quiet-river-77";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    String ip;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("CT");
        ip = "10.92." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        long u = accounts.newUser(comp, "ct1", PW);
        accounts.grant(comp, u, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, u, "ADM", "sys-access-log", "SEARCH");
        admin = accounts.as(comp, u, "ct1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'CW-1', '작업자', 'P1', ?, 't', 't')""", comp, WorkerService.badgeHash(comp, "CT-BADGE-1"));
    }

    /** 접속 로그 조회 — 기간 필수(회사 시간대 기준 어제~오늘) */
    static MockHttpServletRequestBuilder logs() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        return get("/api/admin/access-logs").param("from", today.minusDays(1).toString()).param("to", today.toString());
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    Cookie terminal(String cd, String type) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\",\"terminalType\":\"" + type + "\"}").with(admin)).andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        MvcResult a = mvc.perform(json(post("/api/pop/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .header(AccessLogService.CLIENT_HEADER, "POP")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return new Cookie(PopAuthService.COOKIE, set.substring(set.indexOf('=') + 1, set.indexOf(';')));
    }

    /** 배지 로그인(접속 로그 POP_BADGE_OK 류) — 화면처럼 X-HB-Client 헤더를 붙인다 */
    void badge(Cookie t, String header) throws Exception {
        mvc.perform(json(post("/api/pop/badge"), "{\"badge\":\"CT-BADGE-1\"}").cookie(t).header(AccessLogService.CLIENT_HEADER, header))
                .andExpect(status().isOk());
    }

    String lastBadgeClient(String actorLike) {
        return accounts.query(comp, """
                select client_type from sys_access_log where action_cd = 'BADGE' and login_id like ?
                 order by log_id desc limit 1""", String.class, actorLike);
    }

    @Test
    void PDA_단말은_POP_헤더를_보내도_PDA_로_POP_단말은_POP_로() throws Exception {
        Cookie pda = terminal("CT-PDA", "PDA");
        Cookie pop = terminal("CT-POP", "POP");
        badge(pda, "POP");                    // hb.pop.api 가 PDA 창고 화면에서도 보내는 값
        assertThat(lastBadgeClient("%:CT-PDA")).isEqualTo("PDA");
        badge(pop, "POP");
        assertThat(lastBadgeClient("%:CT-POP")).isEqualTo("POP");
        // 단말이 헤더를 바꿔 보내도 서버 유형이 이긴다(위조 무시)
        badge(pop, "PDA");
        assertThat(lastBadgeClient("%:CT-POP")).isEqualTo("POP");
        badge(pda, "WEB");
        assertThat(lastBadgeClient("%:CT-PDA")).isEqualTo("PDA");
    }

    @Test
    void 헤더_값_정리는_허용값만_나머지는_WEB() {
        assertThat(AccessLogService.clientType(null)).isEqualTo("WEB");
        assertThat(AccessLogService.clientType(" pda ")).isEqualTo("PDA");
        assertThat(AccessLogService.clientType("SYSTEM ' or 1=1")).isEqualTo("WEB");
    }

    @Test
    void 접속_로그_화면_단말_조건과_칸_이름() throws Exception {
        Cookie pda = terminal("CT-PDA", "PDA");
        badge(pda, "POP");
        mvc.perform(logs().param("clientType", "PDA").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.list[*].clientType", Matchers.everyItem(Matchers.is("PDA"))))
                .andExpect(jsonPath("$.list[*].actionCd", Matchers.hasItem("BADGE")));
        // 활성화 기록(단말 유형을 아직 모름)은 헤더 그대로 POP — PDA 조건에 섞이지 않는다
        mvc.perform(logs().param("clientType", "POP").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.list[*].eventCd", Matchers.hasItem("TERMINAL_ACTIVATED")))
                .andExpect(jsonPath("$.list[*].clientType", Matchers.everyItem(Matchers.is("POP"))));
        mvc.perform(logs().param("clientType", "TABLET").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(logs().param("clientType", "pda").with(admin)).andExpect(status().isBadRequest());
        // 칸은 코드 → 글자(LABEL) — 화면과 엑셀이 같은 표
        String cols = mvc.perform(get("/api/admin/access-logs/columns").with(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> types = JsonPath.read(cols, "$.list[?(@.field=='clientType')].type");
        List<String> pdaLabel = JsonPath.read(cols, "$.list[?(@.field=='clientType')].labels.PDA");
        assertThat(types).containsExactly("LABEL");
        assertThat(pdaLabel).containsExactly("창고 PDA");
    }

    @Test
    void A사로_B사_단말_조건으로도_남의_기록은_안_보인다() throws Exception {
        Cookie pda = terminal("CT-PDA", "PDA");
        badge(pda, "POP");
        String other = accounts.newComp("CU");
        long bu = accounts.newUser(other, "cu1", PW);
        accounts.grant(other, bu, "ADM", "sys-access-log", "SEARCH");
        mvc.perform(logs().param("clientType", "PDA").with(accounts.as(other, bu, "cu1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.list.length()").value(0));
    }
}
