package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.security.DevCompanySeed;
import io.smarthappy.platform.tenant.TenantRunner;

/**
 * 현장 단말 진입 화면 (docs/36 21장) — prd-pop(생산)·wms-pda(창고).
 * 정상: 화면·메뉴·바로가기 판정 / 이상: 화면 권한 없음 403·다른 진입 화면 API 403·바로가기 권한 없음 → 숨김,
 * <b>POP 분리 회귀</b> — 사무 로그인 세션으로 /api/pop/** 를 직접 불러도 단말 인증이 아니므로 401, /pop 은 활성화 화면으로.
 * A사로 B사: 회사 데이터를 읽지 않는 화면(권한 판정·정적 안내만)이라 해당 없음 — 다른 회사 단말 이름 숨김은 화면 JS 규칙(브라우저 확인).
 */
class TerminalEntryTest extends SysAdminTestSupport {

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    RequestPostProcessor popOnly;
    RequestPostProcessor none;

    @BeforeEach
    void setUp() {
        setUpCompany("TE");
        long u = accounts.newUser(comp, "floor", PW);
        accounts.grant(comp, u, "FLOOR", "prd-pop", "SEARCH");
        popOnly = accounts.as(comp, u, "floor");
        none = worker;   // mst-cust 만
    }

    @Test
    void 관리자는_두_화면과_바로가기_둘다() throws Exception {
        for (String p : List.of("prd-pop", "wms-pda")) {
            mvc.perform(get("/page/" + p).with(admin)).andExpect(status().isOk())
                    .andExpect(header().string("Content-Security-Policy", Matchers.containsString("script-src 'self'")));
        }
        for (String k : List.of("pop", "pda")) {
            mvc.perform(get("/api/terminal-entry/" + k + "/links").with(admin)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.terminalMgt").value(true)).andExpect(jsonPath("$.data.pdaApp").value(true));
        }
    }

    @Test
    void 화면_권한만_있으면_바로가기는_숨김_다른_진입화면과_권한없음은_403() throws Exception {
        mvc.perform(get("/page/prd-pop").with(popOnly)).andExpect(status().isOk());
        mvc.perform(get("/api/terminal-entry/pop/links").with(popOnly)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.terminalMgt").value(false)).andExpect(jsonPath("$.data.pdaApp").value(false));
        // 창고 진입 화면·API 는 권한 없음
        mvc.perform(get("/page/wms-pda").with(popOnly)).andExpect(status().isForbidden());
        mvc.perform(get("/api/terminal-entry/pda/links").with(popOnly)).andExpect(status().isForbidden());
        // 둘 다 없는 사용자
        mvc.perform(get("/page/prd-pop").with(none)).andExpect(status().isForbidden());
        mvc.perform(get("/api/terminal-entry/pop/links").with(none)).andExpect(status().isForbidden());
    }

    @Test
    void 사무_로그인으로는_단말에_로그인되지_않는다_POP_분리_회귀() throws Exception {
        // 실제 사무 로그인 세션(쿠키)으로 POP API 를 직접 불러도 단말 인증(등록 단말 쿠키)이 없으므로 401 — POP 체인은 사무 세션을 읽지 않는다
        var s = login("floor", PW);   // 관리자는 2단계 인증으로 돌려지므로 일반 사용자(prd-pop 권한)
        mvc.perform(get("/page/prd-pop").session(s)).andExpect(status().isOk());   // 같은 세션으로 사무 화면은 열린다
        for (String api : List.of("/api/pop/session", "/api/pop/home", "/api/pop/wh/counts")) {
            mvc.perform(get(api).session(s)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("TERMINAL_UNAUTHENTICATED"));
        }
        // 단말 화면은 활성화 화면으로(사무 로그인은 단말 화면을 열어 주지 않는다)
        mvc.perform(get("/pop").session(s)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/pop/activate"));
        mvc.perform(get("/pop/wh").session(s)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/pop/activate"));
    }

    @Test
    void 진입은_현장_모바일_진입의_관련_화면으로_메뉴에는_없다_권한_있는_것만_단추() throws Exception {
        // docs/79 M1: POP·PDA 진입·단말 앱은 메뉴에서 빠지고 현장·모바일 진입(sys-mobile-app, 개인 화면)의 [관련 화면] 단추로 간다
        runner.run(comp, () -> DevCompanySeed.ensureAdminGroupAndMenus(jdbc, comp, "t"));
        assertThat(accounts.query(comp, "select count(*) from sys_menu where prog_cd in ('prd-pop', 'wms-pda', 'sys-pda-app')", Long.class))
                .isZero();
        mvc.perform(get("/api/platform/menu").with(popOnly)).andExpect(status().isOk())
                .andExpect(jsonPath("$..progCd").value(Matchers.hasItem("sys-mobile-app")))
                .andExpect(jsonPath("$..progCd").value(Matchers.not(Matchers.hasItem("prd-pop"))));
        String links = "/api/platform/prog-links?progs=prd-pop,wms-pda,sys-pda-app";
        mvc.perform(get(links).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].progCd").value(Matchers.contains("prd-pop", "wms-pda", "sys-pda-app")));
        mvc.perform(get(links).with(popOnly)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].progCd").value(Matchers.contains("prd-pop")));
        mvc.perform(get(links).with(none)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
        // 화면 자체는 메뉴 밖에서도 그대로 열린다(권한 그대로)
        mvc.perform(get("/page/prd-pop").with(popOnly)).andExpect(status().isOk());
        mvc.perform(get("/page/wms-pda").with(popOnly)).andExpect(status().isForbidden());
    }
}
