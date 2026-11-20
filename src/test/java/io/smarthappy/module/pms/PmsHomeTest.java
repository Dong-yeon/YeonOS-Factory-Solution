package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PMS 5단계 홈·포트폴리오·보고서·데모 (docs/32 PM5) — 데모 관통 흐름(템플릿 → 게이트 2개 통과 → 지연 → 이슈 → 공수)이 서비스 길로 끊김 없이,
 * 두 번 돌려도 그대로, 포트폴리오 숫자(SOP 남은 날·기준선 지연·지연 작업·준비율·다음 게이트) 손 검산, 엑셀 = 같은 조건, 내 할 일, 보고서,
 * 홈 카드(권한 없으면 안 보임), A사로 B사.
 */
@AutoConfigureMockMvc
class PmsHomeTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final List<String> PROGS = List.of("pms-home", "pms-project", "pms-template", "pms-gate", "pms-deliverable",
            "pms-issue", "pms-worklog");

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    PmsDemoFlow demo;

    @Autowired
    io.smarthappy.platform.tenant.TenantRunner runner;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    String comp;
    long pmId;
    RequestPostProcessor pm;
    LocalDate today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PH");
        pmId = accounts.newUser(comp, "pm1", PW);
        for (String p : PROGS) {
            accounts.grant(comp, pmId, "PMO", p, "SEARCH", "SAVE", "APPROVE", "EXCEL");
        }
        accounts.grant(comp, pmId, "PMO", "home-main", "SEARCH");
        pm = accounts.as(comp, pmId, "pm1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY, LocalDate.class);
    }

    private int seed(String c, long userId, String login) {
        return demo.seed(c, new LoginUser(userId, c, login, login, "ko", false));
    }

    private static <T> T read(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), path);
    }

    private static String card(String code) {
        return "$.data.cards[?(@.code == '" + code + "')]";
    }

    @Test
    void 데모_흐름이_끊김_없이_들어가고_포트폴리오_보고서_내_할_일이_맞다() throws Exception {
        assertThat(seed(comp, pmId, "pm1")).isEqualTo(2);
        assertThat(seed(comp, pmId, "pm1")).isZero();                 // 프로젝트가 있으면 건너뜀
        // 게이트 2개 통과(G1 GO, G2 조건부 → 이슈), 지연 1(공정 흐름도), 이슈 2(조건 + 금형), 공수 3일 확정 + 오늘 작성 중
        assertThat(accounts.query(comp, "select count(*) from pms_gate_review where status = 'DECIDED'", Integer.class)).isEqualTo(2);
        assertThat(accounts.query(comp, "select count(*) from pms_issue where source = 'GATE'", Integer.class)).isEqualTo(1);
        assertThat(accounts.query(comp, "select count(distinct work_day) from pms_work_log where status = 'CONFIRMED'", Integer.class))
                .isEqualTo(3);
        assertThat(accounts.query(comp, "select count(*) from pms_deliverable_hist where action = 'SUBMIT'", Integer.class)).isEqualTo(5);

        ResultActions port = mvc.perform(get("/api/pms/portfolio").with(pm));
        List<Map<String, Object>> rows = read(port, "$.data");
        assertThat(rows).hasSize(2);
        Map<String, Object> apqp = rows.stream().filter(r -> "APQP".equals(r.get("projType"))).findFirst().orElseThrow();
        assertThat(apqp.get("sopDays")).isEqualTo(150);
        // Safe Launch(SOP ~ SOP+89) 를 기준선 뒤 7일 연장 → 끝 SOP+96, 기준선 끝은 G5(SOP+90) → 프로젝트 지연 6일(손 검산)
        assertThat(apqp.get("slipDays")).isEqualTo(6);
        assertThat(apqp.get("phaseNm")).isEqualTo("3 공정 설계·개발");
        assertThat(apqp.get("nextGateNm")).isEqualTo("G3 공정 준비 심사");
        assertThat(((Number) apqp.get("lateCnt")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(apqp.get("reqCnt")).isEqualTo(12);
        assertThat(apqp.get("readyCnt")).isEqualTo(5);               // 승인 4 + 면제 1
        assertThat(apqp.get("readyPct")).isEqualTo(41);              // 5 * 100 / 12 내림
        assertThat(apqp.get("openIssueCnt")).isEqualTo(2);
        assertThat(apqp.get("highIssueCnt")).isEqualTo(1);
        // 엑셀 = 같은 조건
        MvcResult x = mvc.perform(post("/api/pms/portfolio/export").with(csrf()).with(pm).contentType(MediaType.APPLICATION_JSON)
                .content("{\"projType\":\"APQP\"}")).andExpect(status().isOk()).andReturn();
        assertThat(x.getResponse().getContentType()).contains("spreadsheetml");
        mvc.perform(get("/api/pms/portfolio").param("projType", "CHANGE").with(pm)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/pms/portfolio").param("status", "CLOSED").with(pm)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/pms/portfolio").param("status", "NOPE").with(pm)).andExpect(jsonPath("$.errors[0].field").value("status"));

        // 보고서: 게이트 5(G1 GO·G2 조건부), 지연 작업에 공정 흐름도, 기준선 대비 Safe Launch +7, 공수 설계 15h
        long projId = ((Number) apqp.get("projId")).longValue();
        ResultActions rep = mvc.perform(get("/api/pms/projects/" + projId + "/report").with(pm));
        rep.andExpect(jsonPath("$.data.gates.length()").value(5))
                .andExpect(jsonPath("$.data.gates[0].decision").value("GO"))
                .andExpect(jsonPath("$.data.gates[1].decision").value("CONDITIONAL"))
                .andExpect(jsonPath("$.data.lateTasks[*].taskNm").value(hasItem("공정 흐름도")))
                .andExpect(jsonPath("$.data.slipTasks[0].taskNm").value("Safe Launch(강화 검사)"))
                .andExpect(jsonPath("$.data.slipTasks[0].slipDays").value(7))
                .andExpect(jsonPath("$.data.hours[?(@.categoryCd == 'DESIGN')].confirmedHours").value(hasItem(15)))
                .andExpect(jsonPath("$.data.phases[0].pct").value(100))
                .andExpect(jsonPath("$.data.openIssues.length()").value(2));

        // 내 할 일: 지연 작업, 내 이슈, 확정한 날은 미확정 목록에 없다
        ResultActions todo = mvc.perform(get("/api/pms/my-todo").with(pm));
        todo.andExpect(jsonPath("$.data.tasks[?(@.taskNm == '공정 흐름도')].late").value(hasItem(true)))
                .andExpect(jsonPath("$.data.issues.length()").value(3))    // APQP 2 + 4M 1 (모두 내 담당)
                .andExpect(jsonPath("$.data.dueSoonDays").value(7));
        List<String> confirmed = runner.call(comp, () -> jdbc.queryForList(
                "select distinct work_day::text from pms_work_log where status = 'CONFIRMED'", String.class));
        List<String> missing = read(todo, "$.data.unconfirmedDays");
        assertThat(missing).doesNotContainAnyElementsOf(confirmed);
        missing.forEach(d -> assertThat(LocalDate.parse(d).getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));

        // 홈 카드: 내 할 일(지연 → ALERT) · 지연 프로젝트
        mvc.perform(get("/api/home/cards").with(pm))
                .andExpect(jsonPath(card("pms-my-work") + ".tone").value(hasItem("ALERT")))
                .andExpect(jsonPath(card("pms-my-work") + ".progCd").value(hasItem("pms-home")))
                .andExpect(jsonPath(card("pms-portfolio") + ".count").value(hasItem(1)))
                .andExpect(jsonPath(card("pms-portfolio") + ".items[0].link.keyword").value(hasItem(apqp.get("projNo"))));
    }

    @Test
    void 권한_없으면_카드_안_보이고_엑셀_권한_따로_A사로_B사() throws Exception {
        seed(comp, pmId, "pm1");
        long viewer = accounts.newUser(comp, "view1", PW);
        accounts.grant(comp, viewer, "VIEW", "pms-home", "SEARCH");
        accounts.grant(comp, viewer, "VIEW", "home-main", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "view1");
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "NONE", "pms-project", "SEARCH");
        accounts.grant(comp, none, "NONE", "home-main", "SEARCH");
        RequestPostProcessor n = accounts.as(comp, none, "none1");
        // 조회만 = 엑셀 403, 카드: 내 할 일 0(본인 것 없음)
        mvc.perform(post("/api/pms/portfolio/export").with(csrf()).with(v)).andExpect(status().isForbidden());
        mvc.perform(get("/api/home/cards").with(v)).andExpect(jsonPath(card("pms-my-work") + ".count").value(hasItem(0)));
        // pms-home 권한 없음 → 카드 없음·API 403
        mvc.perform(get("/api/home/cards").with(n)).andExpect(jsonPath("$.data.cards[*].code").value(not(hasItem("pms-my-work"))));
        mvc.perform(get("/api/pms/portfolio").with(n)).andExpect(status().isForbidden());
        // B사: 포트폴리오 비어 있음, A사 보고서 404, 카드 0
        String b = accounts.newComp("PI");
        long bId = accounts.newUser(b, "pm1", PW);
        accounts.grant(b, bId, "PMO", "pms-home", "SEARCH", "EXCEL");
        accounts.grant(b, bId, "PMO", "home-main", "SEARCH");
        RequestPostProcessor bu = accounts.as(b, bId, "pm1");
        long aProj = accounts.query(comp, "select min(proj_id) from pms_project", Long.class);
        mvc.perform(get("/api/pms/portfolio").param("status", "ALL").with(bu)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/pms/projects/" + aProj + "/report").with(bu)).andExpect(status().isNotFound());
        mvc.perform(get("/api/pms/my-todo").with(bu)).andExpect(jsonPath("$.data.tasks.length()").value(0));
        mvc.perform(get("/api/home/cards").with(bu)).andExpect(jsonPath(card("pms-portfolio") + ".count").value(hasItem(0)));
    }
}
