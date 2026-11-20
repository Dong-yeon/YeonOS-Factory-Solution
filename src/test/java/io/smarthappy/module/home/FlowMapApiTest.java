package io.smarthappy.module.home;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 업무 흐름 지도 (home-flow-map, docs/46 12장) — 화면 이름 + 열 수 있는지(조회 권한).
 * 권한 없는 화면도 이름은 나온다(회색 상자), 열 수 있는지는 내 권한그룹만, 화면 권한 없으면 403, 비로그인 401,
 * A사 권한그룹이 B사 사용자 판정을 바꾸지 않는다.
 */
@AutoConfigureMockMvc
class FlowMapApiTest extends PostgresTestBase {

    private static final String PW = "Lantern-orbit-47";
    private static final String API = "/api/home/flow-map/screens";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long userId;
    RequestPostProcessor me;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("FM");
        userId = accounts.newUser(comp, "user1", PW);
        accounts.grant(comp, userId, "OFFICE", FlowMapService.PROG, "SEARCH");
        accounts.grant(comp, userId, "OFFICE", "sal-order", "SEARCH");
        me = accounts.as(comp, userId, "user1");
    }

    @Test
    void 모든_화면의_이름과_내_조회_권한만_열_수_있다() throws Exception {
        mvc.perform(get(API).with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.progCd == 'sal-order')].canOpen").value(Matchers.contains(true)))
                .andExpect(jsonPath("$.data[?(@.progCd == 'home-flow-map')].canOpen").value(Matchers.contains(true)))
                // 권한 없는 화면도 이름은 나온다(번역된 이름 — 키 그대로가 아님)
                .andExpect(jsonPath("$.data[?(@.progCd == 'prd-wo')].canOpen").value(Matchers.contains(false)))
                .andExpect(jsonPath("$.data[?(@.progCd == 'prd-wo')].name").value(Matchers.contains(Matchers.not(Matchers.startsWith("prog.")))))
                .andExpect(jsonPath("$.data.length()").value(Matchers.greaterThan(100)));
        // 권한을 받으면 다음 요청부터
        accounts.grant(comp, userId, "OFFICE", "prd-wo", "SEARCH");
        mvc.perform(get(API).with(me))
                .andExpect(jsonPath("$.data[?(@.progCd == 'prd-wo')].canOpen").value(Matchers.contains(true)));
    }

    @Test
    void 화면_권한이_없으면_403_비로그인은_401() throws Exception {
        long other = accounts.newUser(comp, "user2", PW);
        accounts.grant(comp, other, "FIELD", "sal-order", "SEARCH");
        mvc.perform(get(API).with(accounts.as(comp, other, "user2"))).andExpect(status().isForbidden());
        mvc.perform(get(API)).andExpect(status().isUnauthorized());
    }

    @Test
    void A사_권한그룹이_B사_사용자의_판정을_바꾸지_않는다() throws Exception {
        // B사: 같은 이름의 권한그룹(OFFICE)에 prd-wo 조회 — A사 사용자에게 새지 않는다
        String b = accounts.newComp("FB");
        long bUser = accounts.newUser(b, "user1", PW);
        accounts.grant(b, bUser, "OFFICE", FlowMapService.PROG, "SEARCH");
        accounts.grant(b, bUser, "OFFICE", "prd-wo", "SEARCH");
        mvc.perform(get(API).with(me))
                .andExpect(jsonPath("$.data[?(@.progCd == 'prd-wo')].canOpen").value(Matchers.contains(false)));
        mvc.perform(get(API).with(accounts.as(b, bUser, "user1")))
                .andExpect(jsonPath("$.data[?(@.progCd == 'prd-wo')].canOpen").value(Matchers.contains(true)))
                .andExpect(jsonPath("$.data[?(@.progCd == 'sal-order')].canOpen").value(Matchers.contains(false)));
    }
}
