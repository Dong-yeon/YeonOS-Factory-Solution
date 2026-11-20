package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
 * 내 작업 줄에서 실적 바로 저장 (docs/32 4.19, 사용자 결정 P-10) — 실적 시작·종료·진척만, 나머지 칸(이름·계획일·계획 공수·비고) 그대로,
 * 상태는 실적에서, 담당자만(또는 PM), row_ver 409, 요약·게이트·닫힌 프로젝트, 입력 검사, 내 할 일 목록 칸, A사로 B사.
 */
@AutoConfigureMockMvc
class PmsActualTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/pms/projects";
    private static final String K = "/api/pms/tasks";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long pmId;
    long memId;
    RequestPostProcessor pm;
    RequestPostProcessor mem;
    LocalDate today;
    long proj;
    long phase;
    long task;
    long gate;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PV");
        pmId = accounts.newUser(comp, "pm1", PW);
        memId = accounts.newUser(comp, "mem1", PW);
        for (long u : List.of(pmId, memId)) {
            accounts.grant(comp, u, "PMS" + u, "pms-project", "SEARCH", "SAVE");
            accounts.grant(comp, u, "PMS" + u, "pms-home", "SEARCH");
        }
        pm = accounts.as(comp, pmId, "pm1");
        mem = accounts.as(comp, memId, "mem1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY, LocalDate.class);
        proj = id(send(post(P), "{\"projNm\":\"ZT 실적\",\"projType\":\"GENERAL\"}", pm));
        phase = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"단계\",\"planStart\":\"" + today.minusDays(5) + "\",\"planEnd\":\""
                + today.plusDays(1) + "\"}", pm));
        task = id(send(post(P + "/" + proj + "/tasks"), "{\"parentId\":" + phase + ",\"taskNm\":\"ZT 도면\",\"ownerLoginId\":\"mem1\",\"planStart\":\""
                + today.minusDays(5) + "\",\"planEnd\":\"" + today.plusDays(1) + "\",\"planHours\":12,\"remark\":\"고객 양식\"}", pm));
        gate = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"G1\",\"taskType\":\"GATE\",\"ownerLoginId\":\"mem1\",\"planStart\":\""
                + today.plusDays(1) + "\",\"planEnd\":\"" + today.plusDays(1) + "\"}", pm));
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

    private Map<String, Object> row(long taskId) throws Exception {
        List<Map<String, Object>> rows = JsonPath.read(mvc.perform(get(P + "/" + proj + "/tasks").with(pm)).andReturn().getResponse()
                .getContentAsString(), "$.data.tasks[?(@.taskId == " + taskId + ")]");
        return rows.get(0);
    }

    private int ver(long taskId) throws Exception {
        return ((Number) row(taskId).get("rowVer")).intValue();
    }

    private ResultActions actual(long taskId, String start, String end, Integer pct, Integer rowVer, RequestPostProcessor who) throws Exception {
        return send(post(K + "/" + taskId + "/actual"), "{\"actStart\":" + q(start) + ",\"actEnd\":" + q(end) + ",\"progressPct\":" + pct
                + ",\"rowVer\":" + rowVer + "}", who);
    }

    private static String q(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    private List<Map<String, Object>> myTasks(RequestPostProcessor who) throws Exception {
        return JsonPath.read(mvc.perform(get("/api/pms/my-todo").with(who)).andReturn().getResponse().getContentAsString(), "$.data.tasks");
    }

    @Test
    void 담당자가_줄에서_시작_진척_종료_나머지칸_그대로_상태는_실적에서() throws Exception {
        // 내 할 일 줄 = 편집에 쓰는 칸(실적·종류·row_ver)
        Map<String, Object> mine = myTasks(mem).stream().filter(t -> ((Number) t.get("taskId")).longValue() == task).findFirst().orElseThrow();
        assertThat(mine).containsEntry("actStart", null).containsEntry("taskType", "TASK").containsKey("rowVer");
        actual(task, today.minusDays(4).toString(), null, 30, ver(task), mem).andExpect(status().isOk());
        assertThat(row(task)).containsEntry("status", "IN_PROGRESS").containsEntry("progressPct", 30)
                .containsEntry("actStart", today.minusDays(4).toString())
                // 줄에 없는 칸은 그대로 — 이름·계획일·계획 공수·비고·담당
                .containsEntry("taskNm", "ZT 도면").containsEntry("planStart", today.minusDays(5).toString())
                .containsEntry("planEnd", today.plusDays(1).toString()).containsEntry("planHours", 12.0).containsEntry("remark", "고객 양식")
                .containsEntry("ownerLoginId", "mem1");
        // 요약(단계)은 하위에서 롤업
        assertThat(row(phase)).containsEntry("status", "IN_PROGRESS");
        actual(task, today.minusDays(4).toString(), today.toString(), null, ver(task), mem).andExpect(status().isOk());
        assertThat(row(task)).containsEntry("status", "DONE").containsEntry("progressPct", 100);
        // 끝난 작업은 내 할 일에서 빠진다
        assertThat(myTasks(mem)).extracting(t -> ((Number) t.get("taskId")).longValue()).doesNotContain(task);
    }

    @Test
    void 입력검사_옛rowVer_권한_요약_게이트_닫힌프로젝트() throws Exception {
        int v = ver(task);
        actual(task, null, today.toString(), null, v, mem).andExpect(jsonPath("$.errors[0].field").value("actStart"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        actual(task, today.toString(), today.plusDays(2).toString(), null, v, mem).andExpect(jsonPath("$.errors[0].code").value("FUTURE"));
        actual(task, today.toString(), null, 150, v, mem).andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        actual(task, today.toString(), null, 100, v, mem).andExpect(jsonPath("$.errors[0].code").value("PMS_STATE_PROGRESS"));
        actual(task, today.minusDays(1).toString(), today.minusDays(2).toString(), null, v, mem)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_END_BEFORE_START"));
        send(post(K + "/" + task + "/actual"), "{\"actStart\":null}", mem).andExpect(jsonPath("$.code").value("INVALID_VALUE"));
        // 다른 사람이 먼저 저장 → 옛 rowVer 409
        actual(task, today.toString(), null, 10, v, mem).andExpect(status().isOk());
        actual(task, today.toString(), null, 20, v, mem).andExpect(status().isConflict());
        // 담당 아닌 사람(저장 권한은 있음) → 못 함, 조회만 → 403, PM → 됨
        long oth = accounts.newUser(comp, "oth1", PW);
        accounts.grant(comp, oth, "OTH", "pms-project", "SEARCH", "SAVE");
        actual(task, today.toString(), null, 20, ver(task), accounts.as(comp, oth, "oth1")).andExpect(jsonPath("$.code").value("PMS_NOT_EDITABLE"));
        long viewer = accounts.newUser(comp, "view1", PW);
        accounts.grant(comp, viewer, "VIEW", "pms-project", "SEARCH");
        actual(task, today.toString(), null, 20, ver(task), accounts.as(comp, viewer, "view1")).andExpect(status().isForbidden());
        actual(task, today.toString(), null, 25, ver(task), pm).andExpect(status().isOk());
        // 요약 → 하위에서 계산, 게이트 완료 → 심사로만
        actual(phase, today.toString(), null, 10, ver(phase), pm).andExpect(jsonPath("$.errors[0].code").value("PMS_SUMMARY_DERIVED"));
        actual(gate, today.toString(), today.toString(), null, ver(gate), mem).andExpect(jsonPath("$.code").value("PMS_GATE_BY_REVIEW"));
        assertThat(row(task)).containsEntry("progressPct", 25);
        // 취소된 프로젝트 → 못 고침
        int pv = JsonPath.read(mvc.perform(get(P + "/" + proj).with(pm)).andReturn().getResponse().getContentAsString(), "$.data.rowVer");
        send(post(P + "/" + proj + "/status"), "{\"status\":\"CANCELLED\",\"reason\":\"중단\",\"rowVer\":" + pv + "}", pm).andExpect(status().isOk());
        actual(task, today.toString(), null, 30, ver(task), mem).andExpect(jsonPath("$.code").value("PMS_PROJECT_CLOSED"));
    }

    @Test
    void 계획일은_줄에서_못바꿈_상세폼_규칙과_같음() throws Exception {
        // 줄 편집 API 에는 계획일 칸이 없다 — 보내도 쓰이지 않는다(앱 전체가 모르는 JSON 칸은 무시), 계획일 그대로
        send(post(K + "/" + task + "/actual"), "{\"planEnd\":\"" + today.plusDays(9) + "\",\"rowVer\":" + ver(task) + "}", mem)
                .andExpect(status().isOk());
        assertThat(row(task)).containsEntry("planEnd", today.plusDays(1).toString());
        // 담당자가 상세 폼으로 계획일을 바꾸는 것도 여전히 막힘(일정변경 요청으로)
        send(put(K + "/" + task), "{\"taskNm\":\"ZT 도면\",\"ownerLoginId\":\"mem1\",\"planStart\":\"" + today.minusDays(5) + "\",\"planEnd\":\""
                + today.plusDays(9) + "\",\"planHours\":12,\"remark\":\"고객 양식\",\"rowVer\":" + ver(task) + "}", mem)
                .andExpect(jsonPath("$.code").value("PMS_NOT_EDITABLE"));
        assertThat(row(task)).containsEntry("planEnd", today.plusDays(1).toString());
    }

    @Test
    void A사로_B사_작업_실적_404() throws Exception {
        String compB = accounts.newComp("PW");
        long bId = accounts.newUser(compB, "mem1", PW);
        accounts.grant(compB, bId, "PMS", "pms-project", "SEARCH", "SAVE");
        accounts.grant(compB, bId, "PMS", "pms-home", "SEARCH");
        RequestPostProcessor b = accounts.as(compB, bId, "mem1");
        actual(task, today.toString(), null, 10, ver(task), b).andExpect(status().isNotFound());
        assertThat(row(task)).containsEntry("actStart", null);
        assertThat(myTasks(b)).isEmpty();
    }
}
