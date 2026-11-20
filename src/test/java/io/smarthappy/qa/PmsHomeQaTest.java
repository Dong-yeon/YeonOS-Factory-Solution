package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
 * QA 2026-11-02 회차 2 — PMS 홈 카드(#763/#766, 홈 감사 ④⑤) 회귀. 모듈 시험(PmsHomeCardTest)은 "모두 지연인 505건" 과 "취소 프로젝트 이슈 1건" 을 본다.
 * 여기서는 섞인 데이터로 카드 숫자 = 포트폴리오 목록(grid) 전체 건수·지연 건수, 내 이슈 = 내 할 일 목록인지 본다. 결과는 docs/qa/2026-11-02_regress-k5.md.
 */
@AutoConfigureMockMvc
class PmsHomeQaTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

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
        comp = accounts.newComp("PH");
        pmId = accounts.newUser(comp, "pm1", PW);
        for (String p : List.of("pms-project", "pms-issue", "pms-home")) {
            accounts.grant(comp, pmId, "PMO", p, "SEARCH", "SAVE");
        }
        accounts.grant(comp, pmId, "PMO", "home-main", "SEARCH");
        pm = accounts.as(comp, pmId, "pm1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY, LocalDate.class);
    }

    private ResultActions send(MockHttpServletRequestBuilder r, String body) throws Exception {
        return mvc.perform(r.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(pm));
    }

    private <T> T card(String code, String path) throws Exception {
        List<T> v = JsonPath.read(mvc.perform(get("/api/home/cards").with(pm)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(), "$.data.cards[?(@.code == '" + code + "')]" + path);
        assertThat(v).hasSize(1);
        return v.get(0);
    }

    /** 포트폴리오 grid 기본 조건(상태 = 열린 것) 전체를 쪽마다 읽어 [전체 건수, 지연 건수, total] */
    private int[] gridAll() throws Exception {
        int rows = 0;
        int delayed = 0;
        int total = -1;
        for (int page = 1; page <= 20; page++) {
            MvcResult m = mvc.perform(get("/api/pms/portfolio/grid").param("page", String.valueOf(page)).param("size", "100")
                    .param("total", String.valueOf(page == 1)).with(pm)).andReturn();
            String s = m.getResponse().getContentAsString();
            assertThat(m.getResponse().getStatus()).as(s).isEqualTo(200);
            if (page == 1) {
                total = ((Number) JsonPath.read(s, "$.total")).intValue();
            }
            List<Map<String, Object>> list = JsonPath.read(s, "$.list");
            if (list.isEmpty()) {
                break;
            }
            rows += list.size();
            for (Map<String, Object> r : list) {
                Number slip = (Number) r.get("slipDays");
                Number late = (Number) r.get("lateCnt");
                if ((slip != null && slip.intValue() > 0) || (late != null && late.intValue() > 0)) {
                    delayed++;
                }
            }
        }
        return new int[] {rows, delayed, total};
    }

    /** 열린 프로젝트 520(지연 260 · 정상 260) + 종료 7 + 취소 5 — 카드 진행 중 = grid total = 읽은 행 520, 카드 숫자 = grid 의 지연 260 */
    @Test
    void 포트폴리오_카드_숫자는_섞인_데이터에서도_목록_전체와_같다() throws Exception {
        accounts.exec(comp, """
                insert into pms_project (comp_cd, proj_no, proj_nm, proj_type, pm_user_id, status, created_by, updated_by)
                select hb_current_comp(), 'ZQ-' || lpad(g::text, 4, '0'), 'ZQ ' || g, 'GENERAL', ?,
                       'DRAFT', 't', 't'
                  from generate_series(1, 520) g""", pmId);
        // 짝수 번호만 지연 작업(계획 끝 < 오늘), 홀수는 앞으로 끝날 작업
        accounts.exec(comp, """
                insert into pms_task (comp_cd, proj_id, seq, task_nm, plan_start, plan_end, created_by, updated_by)
                select p.comp_cd, p.proj_id, 10, '작업',
                       case when substr(p.proj_no, 4)::int % 2 = 0 then ?::date - 10 else ?::date end,
                       case when substr(p.proj_no, 4)::int % 2 = 0 then ?::date - 3 else ?::date + 10 end, 't', 't'
                  from pms_project p where p.proj_no like 'ZQ-%'""", today, today, today, today);
        accounts.exec(comp, """
                insert into pms_project (comp_cd, proj_no, proj_nm, proj_type, pm_user_id, status, created_by, updated_by)
                select hb_current_comp(), 'ZX-' || g, 'ZX ' || g, 'GENERAL', ?, 'DRAFT', 't', 't'
                  from generate_series(1, 12) g""", pmId);
        // 상태는 시험 준비로만 바꾼다(소유자·트리거 끔) — 진행·보류·작성 섞기, 종료 7·취소 5
        owner("update pms_project set status = case when substr(proj_no, 4)::int % 3 = 0 then 'ACTIVE' when substr(proj_no, 4)::int % 3 = 1 "
                + "then 'DRAFT' else 'ON_HOLD' end where comp_cd = '" + comp + "' and proj_no like 'ZQ-%'");
        owner("update pms_project set closed_on = current_date, status = case when substr(proj_no, 4)::int <= 7 then 'CLOSED' else 'CANCELLED' end where comp_cd = '" + comp
                + "' and proj_no like 'ZX-%'");
        int[] g = gridAll();
        int count = card("pms-portfolio", ".count");
        int active = card("pms-portfolio", ".facts[0].value");
        assertThat(g[2]).as("grid total").isEqualTo(520);
        assertThat(g[0]).as("grid 읽은 행").isEqualTo(520);
        assertThat(active).as("카드 진행 중").isEqualTo(g[2]);
        assertThat(count).as("카드 지연").isEqualTo(g[1]).isEqualTo(260);
    }

    /**
     * 내 이슈: 진행 프로젝트(열림 30·진행 25·해결 1), 보류 프로젝트(열림 1), 종료·취소 프로젝트(열림 각 1) — 카드 = 56(열린 프로젝트의 열림·진행),
     * 화면 "내 할 일" 이슈 목록은 지금 50 상한(카드 56) — 문서 H-a. 여기서는 목록이 카드 정의 밖 이슈를 담지 않는 것만 본다
     */
    @Test
    void 내_이슈_카드와_내_할_일_목록() throws Exception {
        long live = proj("ZT 진행");
        long hold = proj("ZT 보류");
        long closed = proj("ZT 종료될");
        long dead = proj("ZT 취소될");
        for (int i = 0; i < 55; i++) {
            issue(live, "진행 이슈 " + i);
        }
        owner("update pms_issue set status = 'IN_PROGRESS' where comp_cd = '" + comp + "' and title like '진행 이슈 %' and substr(title, 7)::int < 25");
        long r1 = issue(live, "해결됨");
        owner("update pms_issue set status = 'RESOLVED', resolution = '조치', resolved_on = current_date where issue_id = " + r1);
        issue(hold, "보류 프로젝트 이슈");
        issue(closed, "종료 프로젝트 이슈");
        issue(dead, "취소 프로젝트 이슈");
        owner("update pms_project set status = 'ON_HOLD' where proj_id = " + hold);
        owner("update pms_project set status = 'CLOSED', closed_on = current_date where proj_id = " + closed);
        owner("update pms_project set status = 'CANCELLED', closed_on = current_date where proj_id = " + dead);

        int fact = card("pms-my-work", ".facts[2].value");
        String todo = mvc.perform(get("/api/pms/my-todo").with(pm)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> titles = JsonPath.read(todo, "$.data.issues[*].title");
        assertThat(fact).isEqualTo(56);
        assertThat(titles).hasSizeLessThanOrEqualTo(fact).doesNotContain("해결됨", "종료 프로젝트 이슈", "취소 프로젝트 이슈");
    }

    /** 시험 준비 전용 — 소유자 연결, 트리거·검사 제약 끔(상태값만 섞는다) */
    private void owner(String sql) throws Exception {
        try (java.sql.Connection con = java.sql.DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword());
                java.sql.Statement st = con.createStatement()) {
            st.execute("set session_replication_role = replica");
            st.execute(sql);
        }
    }

    private long proj(String nm) throws Exception {
        MvcResult m = send(post("/api/pms/projects"), "{\"projNm\":\"" + nm + "\",\"projType\":\"GENERAL\"}").andReturn();
        assertThat(m.getResponse().getStatus()).as(m.getResponse().getContentAsString()).isEqualTo(200);
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private long issue(long proj, String title) throws Exception {
        MvcResult m = send(post("/api/pms/issues"), "{\"issueType\":\"DEFECT\",\"projId\":" + proj + ",\"title\":\"" + title
                + "\",\"ownerLoginId\":\"pm1\"}").andReturn();
        assertThat(m.getResponse().getStatus()).as(m.getResponse().getContentAsString()).isEqualTo(200);
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }
}
