package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;

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
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * QA — 현장 단말 앱(V197 #601)·PDA 접속 로그(#604) 이상 사용. 모듈 시험(PdaAppTest·TerminalClientTypeTest)이 덮은 것(세 칸·권한 401/403·경로 조작
 * 파라미터·링크·A사로 B사, 활성화 뒤 PDA/POP·단말의 헤더 위조 무시)은 다시 하지 않는다. 여기서는 공개 경로 허용이 이웃 경로로 새지 않는지,
 * 활성화 요청 자체의 단말 종류, 등록 유형을 바꾼 뒤, 사무 사용자의 헤더. 분석 docs/qa/2026-10-27_qms-import-ocap-pda.md
 */
@AutoConfigureMockMvc
class PdaTerminalQaTest extends PostgresTestBase {

    static final String PW = "Qa-pda-term-61";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    String ip;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("QD");
        ip = "10.94." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        long u = accounts.newUser(comp, "qd1", PW);
        accounts.grant(comp, u, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, u, "ADM", "sys-access-log", "SEARCH");
        admin = accounts.as(comp, u, "qd1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'QDW-1', '작업자', 'P1', ?, 't', 't')""", comp, WorkerService.badgeHash(comp, "QD-BADGE-1"));
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** 단말 등록 → 활성화(헤더 POP — hb.pop.api 가 PDA 껍데기에서도 보내는 값) → 쿠키 */
    Cookie terminal(String cd, String type) throws Exception {
        return terminal(cd, type, "POP");
    }

    Cookie terminal(String cd, String type, String activateHeader) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\",\"terminalType\":\"" + type + "\"}").with(admin)).andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        MvcResult a = mvc.perform(json(post("/api/pop/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .header(AccessLogService.CLIENT_HEADER, activateHeader).with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return new Cookie(PopAuthService.COOKIE, set.substring(set.indexOf('=') + 1, set.indexOf(';')));
    }

    void badge(Cookie t, String header) throws Exception {
        mvc.perform(json(post("/api/pop/badge"), "{\"badge\":\"QD-BADGE-1\"}").cookie(t).header(AccessLogService.CLIENT_HEADER, header))
                .andExpect(status().isOk());
    }

    String lastBadgeClient(String actorLike) {
        return accounts.query(comp, "select client_type from sys_access_log where action_cd = 'BADGE' and login_id like ? order by log_id desc limit 1",
                String.class, actorLike);
    }

    // ------------------------------------------------------------------ 시험

    /** 공개 허용은 정확히 GET /pda/version.json 하나 — 꼬리 슬래시·경로 매개변수·대소문자·확장자·이웃 경로는 공개 내용이 나오지 않는다 */
    @Test
    void 공개_경로_허용이_이웃_경로로_새지_않는다() throws Exception {
        assertThat(mvc.perform(get("/pda/version.json")).andReturn().getResponse().getStatus()).isEqualTo(200);
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String p : new String[] {"/pda/version.json/", "/pda/version.json;jsessionid=x", "/PDA/version.json", "/pda/Version.json",
                "/pda/version.json.bak", "/pda/version", "/pda/release.json", "/pda/yeonos-pda.apk", "/pda/", "/pda/../api/platform/pda-app",
                "/api/platform/pda-app", "/api/platform/pda-app/download"}) {
            out.put(p, mvc.perform(get(p).accept(MediaType.APPLICATION_JSON)).andReturn().getResponse().getStatus());
        }
        assertThat(out).as("로그인 없이 200 은 없다(401·404·400·로그인 이동)").allSatisfy((p, s) -> assertThat(s).as(p).isNotEqualTo(200));
        // 쓰기 메서드는 공개 아님(CSRF 없이 403, 있어도 GET 전용이라 405)
        assertThat(mvc.perform(post("/pda/version.json")).andReturn().getResponse().getStatus()).isIn(403, 405);
    }

    /**
     * 관찰(정책 질의 D-1): 활성화 요청은 아직 단말 주체가 없어 헤더 참고값으로 남는다 — PDA 로 등록한 단말의 활성화가 POP 로 기록된다
     * (껍데기가 POP 헤더를 보냄). 활성화 코드로 서버가 단말 유형을 아는 시점이라, 로그를 등록 유형으로 남길지 결정. 지금 동작을 고정한다
     */
    @Test
    void 관찰_PDA_단말의_활성화_요청은_헤더값_POP_으로_남는다() throws Exception {
        terminal("QD-PDA", "PDA");
        assertThat(accounts.query(comp, """
                select client_type from sys_access_log where ip = ? and event_cd like '%ACTIV%' order by log_id desc limit 1""", String.class, ip))
                .isEqualTo("POP");
    }

    /** 등록 유형을 POP → PDA 로 바꾸면 다음 요청부터 PDA(단말 주체는 요청마다 DB 에서 만든다 — 세션 사본 없음) */
    @Test
    void 등록_유형을_바꾸면_다음_요청부터_새_유형으로_남는다() throws Exception {
        Cookie t = terminal("QD-SW", "POP");
        badge(t, "POP");
        assertThat(lastBadgeClient("%:QD-SW")).isEqualTo("POP");
        accounts.exec(comp, "update sys_terminal set terminal_type = 'PDA' where terminal_cd = 'QD-SW'");
        badge(t, "POP");
        assertThat(lastBadgeClient("%:QD-SW")).isEqualTo("PDA");
    }

    /**
     * D-2 결정(사용자 2026-10-27, #623): 사무 사용자(단말 주체 아님)가 헤더 X-HB-Client: PDA 를 보내도 접속 로그는 WEB —
     * 헤더는 단말 경로에서 단말 주체가 아직 없을 때(활성화, D-1)만 받는다. 관찰 때(PDA 로 남음) 고정했던 동작을 결정대로 뒤집었다
     */
    @Test
    void 결정_D2_사무_사용자가_PDA_헤더를_보내도_WEB_으로_남는다() throws Exception {
        mvc.perform(get("/api/admin/access-logs").param("from", "2026-01-01").param("to", "2026-01-02").header(AccessLogService.CLIENT_HEADER, "PDA")
                .with(admin)).andExpect(status().isOk());
        mvc.perform(get("/page/sys-access-log").header(AccessLogService.CLIENT_HEADER, "PDA").with(admin));
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where login_id = 'qd1' and client_type = 'PDA'", Integer.class))
                .as("사무 사용자 요청이 PDA 로").isZero();
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where login_id = 'qd1' and client_type = 'WEB'", Integer.class))
                .as("사무 사용자 요청은 WEB").isPositive();
    }

    /**
     * D-2 뒤 경로별 확인(2026-10-28): 단말 경로에서 아직 단말 주체가 없는 활성화 요청만 헤더를 그대로 쓴다(D-1 결정). 그래서 활성화 요청이
     * 헤더 API·WEB 를 보내면 그 값으로 남는다 — 단말 경로인데 POP·PDA 가 아닌 유형(관찰 D-3, 낮음: 활성화 기록 하나뿐, 다음 요청부터 등록 유형)
     */
    @Test
    void 관찰_활성화_요청이_API_헤더면_API_로_남고_다음_요청은_등록_유형() throws Exception {
        Cookie t = terminal("QD-API", "PDA", "API");
        assertThat(accounts.query(comp, """
                select client_type from sys_access_log where ip = ? and event_cd = 'TERMINAL_ACTIVATED' order by log_id desc limit 1""", String.class, ip))
                .isEqualTo("API");
        badge(t, "API");
        assertThat(lastBadgeClient("%:QD-API")).isEqualTo("PDA");
    }
}
