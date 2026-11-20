package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA — PMS 계획 공수(V203, docs/48 R-2). 모듈 시험(PmsWorkTest)이 덮은 것(요약 = 취소 아닌 자식 합, 요약 직접 입력 거부, 템플릿 복사, 정책 MD,
 * A사로 B사)은 다시 하지 않는다. 여기서는 같은 요약 아래 자식 둘을 **동시에** 고칠 때 요약 합(조용히 틀림 후보) — 시험이 프로젝트 머리를 먼저 잠그고
 * 두 수정이 모두 기다리는 것을 관찰한 뒤 놓는다. 분석 docs/qa/2026-10-29_pms-wms-qms-mes.md
 */
@AutoConfigureMockMvc
class PmsPlanHoursQaTest extends PostgresTestBase {

    static final String PW = "Qa-pms-hours-19";
    static final String P = "/api/pms/projects";
    static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    RequestPostProcessor pm;
    LocalDate today;
    long proj;
    long sum;
    long c1;
    long c2;

    @AfterAll
    static void stop() {
        POOL.shutdownNow();
    }

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("QP");
        long u = accounts.newUser(comp, "pm1", PW);
        accounts.grant(comp, u, "PMS", "pms-project", "SEARCH", "SAVE");
        pm = accounts.as(comp, u, "pm1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY, LocalDate.class);
        proj = id(send(post(P), "{\"projNm\":\"QA 공수\",\"projType\":\"GENERAL\"}"));
        sum = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"단계\",\"planStart\":\"" + today + "\",\"planEnd\":\"" + today.plusDays(10) + "\"}"));
        c1 = id(send(post(P + "/" + proj + "/tasks"), "{\"parentId\":" + sum + ",\"taskNm\":\"하위1\",\"planStart\":\"" + today + "\",\"planEnd\":\""
                + today.plusDays(3) + "\",\"planHours\":4}"));
        c2 = id(send(post(P + "/" + proj + "/tasks"), "{\"parentId\":" + sum + ",\"taskNm\":\"하위2\",\"planStart\":\"" + today.plusDays(4)
                + "\",\"planEnd\":\"" + today.plusDays(8) + "\",\"planHours\":3}"));
        int ver = JsonPath.read(mvc.perform(get(P + "/" + proj).with(pm)).andReturn().getResponse().getContentAsString(), "$.data.rowVer");
        send(post(P + "/" + proj + "/status"), "{\"status\":\"ACTIVE\",\"rowVer\":" + ver + "}").andExpect(status().isOk());
    }

    ResultActions send(MockHttpServletRequestBuilder b, String body) throws Exception {
        return mvc.perform(b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(pm));
    }

    static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    Map<String, Object> task(long taskId) throws Exception {
        List<Map<String, Object>> r = JsonPath.read(mvc.perform(get(P + "/" + proj + "/tasks").with(pm)).andReturn().getResponse().getContentAsString(),
                "$.data.tasks[?(@.taskId == " + taskId + ")]");
        return r.get(0);
    }

    MockHttpServletRequestBuilder putHours(long taskId, int hours) throws Exception {
        Map<String, Object> t = task(taskId);
        return put("/api/pms/tasks/" + taskId).with(csrf()).with(pm).contentType(MediaType.APPLICATION_JSON).content("{\"taskNm\":\"" + t.get("taskNm")
                + "\",\"taskType\":\"" + t.get("taskType") + "\",\"planStart\":\"" + t.get("planStart") + "\",\"planEnd\":\"" + t.get("planEnd")
                + "\",\"rowVer\":" + t.get("rowVer") + ",\"planHours\":" + hours + "}");
    }

    int lockWaiters() {
        return jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()",
                Integer.class);
    }

    /** 자식 둘을 동시에 고침 → 둘 다 저장, 요약 = 새 합(10 + 20), 프로젝트 공수·포트폴리오 공수도 같은 30 */
    @Test
    void 같은_요약_아래_자식_둘을_동시에_고쳐도_요약_합은_둘_다_반영() throws Exception {
        MockHttpServletRequestBuilder r1 = putHours(c1, 10);
        MockHttpServletRequestBuilder r2 = putHours(c2, 20);
        try (Connection hold = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = hold.createStatement()) {
            hold.setAutoCommit(false);
            st.execute("select 1 from pms_project where proj_id = " + proj + " for update");
            int before = lockWaiters();
            Future<Integer> f1 = POOL.submit(() -> mvc.perform(r1).andReturn().getResponse().getStatus());
            Future<Integer> f2 = POOL.submit(() -> mvc.perform(r2).andReturn().getResponse().getStatus());
            for (int i = 0; i < 200 && lockWaiters() - before < 2; i++) {
                Thread.sleep(50);
            }
            assertThat(lockWaiters() - before).as("두 수정이 모두 프로젝트 머리를 기다린다(모두 관찰한 뒤 놓음)").isGreaterThanOrEqualTo(2);
            hold.commit();
            assertThat(f1.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(f2.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(((Number) task(sum).get("planHours")).doubleValue()).isEqualTo(30.0);
        assertThat(accounts.query(comp, "select trim_scale(plan_hours)::text from pms_task where task_id = ?", String.class, sum)).isEqualTo("30");
        assertThat(((Number) JsonPath.read(mvc.perform(get(P + "/" + proj + "/tasks").with(pm)).andReturn().getResponse().getContentAsString(),
                "$.data.effort.planHours")).doubleValue()).as("프로젝트 공수 = 요약 합").isEqualTo(30.0);
        assertThat(accounts.query(comp, """
                select trim_scale(sum(plan_hours) filter (where is_summary = 'N' and status <> 'CANCELLED'))::text from pms_task where proj_id = ?""",
                String.class, proj)).as("포트폴리오 정의(잎·취소 아님 합) = 같은 30").isEqualTo("30");
    }
}
