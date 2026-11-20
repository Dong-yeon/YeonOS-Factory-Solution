package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * 홈 카드 pms-chg-todo "처리할 일정변경 요청" (docs/32 4.24) — 내가 PM 인 열린 프로젝트의 PM 처리 대기 요청만, 결재 중·끝난 것·닫힌 프로젝트·
 * 내가 낸 것 제외, 항목 링크 q.chgReq, R1(pms-project 조회 권한 없으면 카드 없음), A사로 B사.
 */
@AutoConfigureMockMvc
class PmsChgTodoCardTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/pms/projects";
    private static final String K = "/api/pms/tasks";
    private static final String CARD = "$.data.cards[?(@.code == 'pms-chg-todo')]";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long pmId;
    long memId;
    RequestPostProcessor pm;
    RequestPostProcessor mem;
    long proj;
    long a;
    long b;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PT");
        pmId = accounts.newUser(comp, "pm1", PW);
        memId = accounts.newUser(comp, "mem1", PW);
        for (long u : List.of(pmId, memId)) {
            accounts.grant(comp, u, "PMS" + u, "pms-project", "SEARCH", "SAVE");
            accounts.grant(comp, u, "PMS" + u, "home-main", "SEARCH");
        }
        pm = accounts.as(comp, pmId, "pm1");
        mem = accounts.as(comp, memId, "mem1");
        proj = id(send(post(P), "{\"projNm\":\"ZT 일정변경 카드\",\"projType\":\"GENERAL\"}", pm));
        a = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"설계\",\"planStart\":\"2026-03-01\",\"planEnd\":\"2026-03-10\","
                + "\"ownerLoginId\":\"mem1\"}", pm));
        b = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"시작품\",\"planStart\":\"2026-03-11\",\"planEnd\":\"2026-03-12\","
                + "\"ownerLoginId\":\"mem1\"}", pm));
        policy("N");        // 정책 N = PM 이 직접 처리(REQUESTED). 기본 Y 는 결재(IN_APPROVAL) — 결재함 몫
    }

    private void policy(String v) {
        accounts.exec(comp, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'PMS_TASK_CHG_APPROVAL', ?, 't', 't')
                on conflict do nothing""", comp, v);
        accounts.exec(comp, "update sys_policy set policy_value = ? where policy_key = 'PMS_TASK_CHG_APPROVAL'", v);
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

    private int rowVer(long task) {
        return accounts.query(comp, "select row_ver from pms_task where task_id = ?", Integer.class, task);
    }

    /** 담당자 요청 — 정책 PMS_TASK_CHG_APPROVAL Y 면 결재(IN_APPROVAL, approverId 결재자), N 이면 PM 처리 대기(REQUESTED) */
    private long request(long task, String start, String end, Long approverId) throws Exception {
        return id(send(post(K + "/" + task + "/chg-reqs"), "{\"newStart\":\"" + start + "\",\"newEnd\":\"" + end + "\",\"reason\":\"ZT 고객 일정\","
                + "\"rowVer\":" + rowVer(task) + (approverId == null ? "" : ",\"approverId\":" + approverId) + "}", mem));
    }

    private <T> T cards(RequestPostProcessor who, String path) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/home/cards").with(who)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(), path);
    }

    private List<Integer> count(RequestPostProcessor who) throws Exception {
        return cards(who, CARD + ".count");
    }

    @Test
    void PM_처리대기만_세고_항목은_그_요청으로_링크_처리하면_빠진다() throws Exception {
        assertThat(count(pm)).containsExactly(0);
        long req = request(a, "2026-03-03", "2026-03-12", null);
        assertThat(count(pm)).containsExactly(1);
        assertThat(this.<List<Integer>>cards(pm, CARD + ".facts[0].value")).containsExactly(1);
        String projNo = accounts.query(comp, "select proj_no from pms_project where proj_id = ?", String.class, proj);
        assertThat(this.<List<String>>cards(pm, CARD + ".items[0].title")).containsExactly(projNo);
        assertThat(this.<List<String>>cards(pm, CARD + ".items[0].detail")).singleElement().asString()
                .contains("설계").contains("2026-03-03").contains("2026-03-12");
        assertThat(this.<List<String>>cards(pm, CARD + ".items[0].link.chgReq")).containsExactly(String.valueOf(req));
        // 요청한 담당자 본인 카드에는 없다(PM 이 아니다) — 화면 권한은 있어 카드는 0
        assertThat(count(mem)).containsExactly(0);
        // 정책 Y(기본)로 결재에 올린 요청(IN_APPROVAL)은 결재함 몫 → 세지 않음
        policy("Y");
        request(b, "2026-03-13", "2026-03-14", pmId);
        assertThat(accounts.query(comp, "select status from pms_task_chg_req where task_id = ?", String.class, b)).isEqualTo("IN_APPROVAL");
        assertThat(count(pm)).containsExactly(1);
        // PM 이 처리(승인) → 빠짐
        int ver = accounts.query(comp, "select row_ver from pms_task_chg_req where req_id = ?", Integer.class, req);
        send(post("/api/pms/chg-reqs/" + req + "/decide"), "{\"approve\":true,\"note\":\"ok\",\"rowVer\":" + ver + "}", pm)
                .andExpect(status().isOk());
        assertThat(count(pm)).containsExactly(0);
    }

    @Test
    void 닫힌_프로젝트_내가_낸_요청_제외_R1_A사로_B사() throws Exception {
        request(a, "2026-03-03", "2026-03-12", null);
        // 내가 낸 요청(직접 SQL — 화면·API 로는 PM 이 요청을 못 낸다)은 내가 처리할 것이 아니다
        accounts.exec(comp, """
                insert into pms_task_chg_req (comp_cd, proj_id, task_id, requester_id, before_start, before_end, new_start, new_end, reason,
                                              task_row_ver, status, created_by, updated_by)
                values (hb_current_comp(), ?, ?, ?, '2026-03-11', '2026-03-12', '2026-03-15', '2026-03-16', 'ZT 자기', 0, 'REQUESTED', 't', 't')""",
                proj, b, pmId);
        assertThat(count(pm)).containsExactly(1);
        // R1: home-main 만 있고 pms-project 조회 권한 없음 → 카드 없음
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "NONE", "home-main", "SEARCH");
        assertThat(this.<List<String>>cards(accounts.as(comp, none, "none1"), "$.data.cards[*].code")).doesNotContain("pms-chg-todo");
        // B사(같은 로그인 아이디 pm1, 같은 권한): A사 요청이 하나도 안 섞인다
        String compB = accounts.newComp("PU");
        long bId = accounts.newUser(compB, "pm1", PW);
        accounts.grant(compB, bId, "PMS", "pms-project", "SEARCH", "SAVE");
        accounts.grant(compB, bId, "PMS", "home-main", "SEARCH");
        RequestPostProcessor bu = accounts.as(compB, bId, "pm1");
        assertThat(count(bu)).containsExactly(0);
        assertThat(this.<List<Object>>cards(bu, CARD + ".items[*]")).isEmpty();
        // 프로젝트 취소 → 열린 요청이 남아도 세지 않음(R8)
        int ver = accounts.query(comp, "select row_ver from pms_project where proj_id = ?", Integer.class, proj);
        send(post(P + "/" + proj + "/status"), "{\"status\":\"CANCELLED\",\"reason\":\"수주 취소\",\"rowVer\":" + ver + "}", pm)
                .andExpect(status().isOk());
        assertThat(count(pm)).containsExactly(0);
    }
}
