package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 홈 카드 pms-portfolio·pms-my-work 보완(홈 감사 ④⑤⑦, docs/32 4.22) — 포트폴리오 카드가 목록 상한(500) 없이 같은 조건 전체를 세는지,
 * 내 이슈 = 화면 "내 할 일" 과 같은 정의(취소 프로젝트 제외)인지, 카드 R1(화면 권한 없으면 카드 없음)·A사로 B사.
 */
@AutoConfigureMockMvc
class PmsHomeCardTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/pms/projects";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long pmId;
    RequestPostProcessor pm;
    LocalDate today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PK");
        pmId = accounts.newUser(comp, "pm1", PW);
        for (String p : List.of("pms-project", "pms-issue", "pms-home")) {
            accounts.grant(comp, pmId, "PMO", p, "SEARCH", "SAVE");
        }
        accounts.grant(comp, pmId, "PMO", "home-main", "SEARCH");
        pm = accounts.as(comp, pmId, "pm1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY, LocalDate.class);
    }

    private ResultActions send(MockHttpServletRequestBuilder r, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(r.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andReturn();
        String s = m.getResponse().getContentAsString();
        assertThat(m.getResponse().getStatus()).as(s).isEqualTo(200);
        return ((Number) JsonPath.read(s, "$.data")).longValue();
    }

    private static String card(String code) {
        return "$.data.cards[?(@.code == '" + code + "')]";
    }

    private <T> T cards(RequestPostProcessor who, String path) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/home/cards").with(who)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(), path);
    }

    @Test
    void 포트폴리오_카드는_목록_상한_없이_같은_조건_전체를_센다() throws Exception {
        // 진행 중 프로젝트 505건(500 넘게) — 모두 지연 작업 1개씩. 예전 코드는 목록 500건만 읽어 500 으로 셌다
        accounts.exec(comp, """
                insert into pms_project (comp_cd, proj_no, proj_nm, proj_type, pm_user_id, status, created_by, updated_by)
                select hb_current_comp(), 'ZT-' || lpad(g::text, 4, '0'), 'ZT 대량 ' || g, 'GENERAL', ?, 'DRAFT', 't', 't'
                  from generate_series(1, 505) g""", pmId);
        accounts.exec(comp, """
                insert into pms_task (comp_cd, proj_id, seq, task_nm, plan_start, plan_end, created_by, updated_by)
                select p.comp_cd, p.proj_id, 10, '지연 작업', ?::date - 10, ?::date - 3, 't', 't'
                  from pms_project p where p.proj_no like 'ZT-%'""", today, today);
        List<Integer> count = cards(pm, card("pms-portfolio") + ".count");
        assertThat(count).containsExactly(505);
        List<Integer> active = cards(pm, card("pms-portfolio") + ".facts[0].value");
        assertThat(active).containsExactly(505);
        // 항목은 상위 몇 개만(많이 늦은 순) — 숫자와 별개
        List<List<Object>> items = cards(pm, card("pms-portfolio") + ".items");
        assertThat(items.get(0)).isNotEmpty().hasSizeLessThanOrEqualTo(io.smarthappy.module.home.HomeService.MAX_ITEMS);
    }

    @Test
    void 내_이슈는_화면_내할일과_같은_정의_취소프로젝트_이슈_제외() throws Exception {
        long live = id(send(post(P), "{\"projNm\":\"ZT 진행\",\"projType\":\"GENERAL\"}", pm));
        long dead = id(send(post(P), "{\"projNm\":\"ZT 취소될\",\"projType\":\"GENERAL\"}", pm));
        for (long p : List.of(live, dead)) {
            id(send(post("/api/pms/issues"), "{\"issueType\":\"DEFECT\",\"projId\":" + p + ",\"title\":\"ZT 이슈 " + p + "\",\"ownerLoginId\":\"pm1\"}", pm));
        }
        int ver = JsonPath.read(mvc.perform(get(P + "/" + dead).with(pm)).andReturn().getResponse().getContentAsString(), "$.data.rowVer");
        send(post(P + "/" + dead + "/status"), "{\"status\":\"CANCELLED\",\"reason\":\"수주 취소\",\"rowVer\":" + ver + "}", pm)
                .andExpect(status().isOk());
        // 화면 "내 할 일" 목록 = 1건(진행 프로젝트), 카드 "내 이슈" 숫자 = 1 — 같은 집합
        List<Object> screen = JsonPath.read(mvc.perform(get("/api/pms/my-todo").with(pm)).andReturn().getResponse().getContentAsString(),
                "$.data.issues[*].title");
        assertThat(screen).containsExactly("ZT 이슈 " + live);
        List<Integer> fact = cards(pm, card("pms-my-work") + ".facts[2].value");
        assertThat(fact).containsExactly(screen.size());
    }

    @Test
    void 내할일_세_목록은_상한에서_잘려도_전체_건수를_싣고_카드_숫자와_같다() throws Exception {
        // QA H-a: 목록 상한(50)을 넘는 작업 55·산출물 53·이슈 56 — 예전엔 50건만 오고 건수가 없었다
        long p = id(send(post(P), "{\"projNm\":\"ZT 많은 일\",\"projType\":\"GENERAL\"}", pm));
        accounts.exec(comp, """
                insert into pms_task (comp_cd, proj_id, seq, task_nm, owner_user_id, plan_start, plan_end, created_by, updated_by)
                select hb_current_comp(), ?, g, 'ZT 작업 ' || g, ?, ?::date - 10, ?::date - 1, 't', 't' from generate_series(1, 55) g""",
                p, pmId, today, today);
        accounts.exec(comp, """
                insert into pms_deliverable (comp_cd, proj_id, task_id, seq, deliv_nm, owner_user_id, due_date, created_by, updated_by)
                select k.comp_cd, k.proj_id, k.task_id, 1, 'ZT 산출물 ' || k.seq, ?, ?::date, 't', 't'
                  from pms_task k where k.proj_id = ? and k.seq <= 53""", pmId, today, p);
        accounts.exec(comp, """
                insert into pms_issue (comp_cd, issue_no, proj_id, issue_type, title, owner_user_id, created_by, updated_by)
                select hb_current_comp(), 'ZT-I-' || g, ?, 'RISK', 'ZT 이슈 ' || g, ?, 't', 't' from generate_series(1, 56) g""", p, pmId);
        String todo = mvc.perform(get("/api/pms/my-todo").with(pm)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(todo, "$.data.tasks.length()")).isEqualTo(50);
        assertThat(JsonPath.<Integer>read(todo, "$.data.delivs.length()")).isEqualTo(50);
        assertThat(JsonPath.<Integer>read(todo, "$.data.issues.length()")).isEqualTo(50);
        assertThat(JsonPath.<Integer>read(todo, "$.data.taskCnt")).isEqualTo(55);
        assertThat(JsonPath.<Integer>read(todo, "$.data.delivCnt")).isEqualTo(53);
        assertThat(JsonPath.<Integer>read(todo, "$.data.issueCnt")).isEqualTo(56);
        // 카드 숫자 = 화면 건수(지연 작업 = 작업 전부 지연, 산출물, 이슈)
        assertThat(this.<List<Integer>>cards(pm, card("pms-my-work") + ".facts[0].value")).containsExactly(55);
        assertThat(this.<List<Integer>>cards(pm, card("pms-my-work") + ".facts[1].value")).containsExactly(53);
        assertThat(this.<List<Integer>>cards(pm, card("pms-my-work") + ".facts[2].value")).containsExactly(56);
        // 상한보다 적으면 건수 = 목록 건수(추가 count 없이)
        accounts.exec(comp, "update pms_issue set owner_user_id = null where proj_id = ? and issue_no > 'ZT-I-3'", p);
        String few = mvc.perform(get("/api/pms/my-todo").with(pm)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Integer>read(few, "$.data.issueCnt")).isEqualTo(JsonPath.<Integer>read(few, "$.data.issues.length()"));
        assertThat(this.<List<Integer>>cards(pm, card("pms-my-work") + ".facts[2].value"))
                .containsExactly(JsonPath.<Integer>read(few, "$.data.issueCnt"));
    }

    @Test
    void R1_화면권한_없으면_두_카드_없음_A사로_B사_0() throws Exception {
        long p = id(send(post(P), "{\"projNm\":\"ZT A사\",\"projType\":\"GENERAL\"}", pm));
        id(send(post(P + "/" + p + "/tasks"), "{\"taskNm\":\"지연\",\"ownerLoginId\":\"pm1\",\"planStart\":\"" + today.minusDays(9)
                + "\",\"planEnd\":\"" + today.minusDays(2) + "\"}", pm));
        id(send(post("/api/pms/issues"), "{\"issueType\":\"RISK\",\"projId\":" + p + ",\"title\":\"ZT A사 이슈\",\"ownerLoginId\":\"pm1\"}", pm));
        List<Integer> mine = cards(pm, card("pms-my-work") + ".count");
        assertThat(mine.get(0)).isGreaterThan(0);
        // R1: 홈만 있고 pms-home 화면 권한 없음 → 두 카드 다 응답에 없다
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "NONE", "pms-project", "SEARCH");
        accounts.grant(comp, none, "NONE", "home-main", "SEARCH");
        List<String> codes = cards(accounts.as(comp, none, "none1"), "$.data.cards[*].code");
        assertThat(codes).doesNotContain("pms-my-work", "pms-portfolio");
        // B사(같은 로그인 아이디 pm1): A사 숫자가 하나도 안 섞인다
        String b = accounts.newComp("PL");
        long bId = accounts.newUser(b, "pm1", PW);
        accounts.grant(b, bId, "PMO", "pms-home", "SEARCH");
        accounts.grant(b, bId, "PMO", "home-main", "SEARCH");
        RequestPostProcessor bu = accounts.as(b, bId, "pm1");
        mvc.perform(get("/api/home/cards").with(bu)).andExpect(jsonPath(card("pms-my-work") + ".count").value(hasItem(0)))
                .andExpect(jsonPath(card("pms-portfolio") + ".count").value(hasItem(0)))
                .andExpect(jsonPath(card("pms-portfolio") + ".facts[0].value").value(hasItem(0)))
                .andExpect(jsonPath("$.data.cards[*].code").value(not(hasItem("none"))));
    }
}
