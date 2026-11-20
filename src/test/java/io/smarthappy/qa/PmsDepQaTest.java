package io.smarthappy.qa;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 * QA 2026-11-02 PMS 선행 미완 실적 제약(C-3, #753, V225, docs/32 4.21) 이상 사용 — 모듈 시험(PmsDepEnforceTest)이 덮지 않은 것만.
 * 결과·분류는 docs/qa/2026-11-02_link-pmsdep-copq.md. 지금 main 에서 실패하는 재현은 그 문서 부록에 있다.
 */
@AutoConfigureMockMvc
class PmsDepQaTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/pms/projects";
    private static final String K = "/api/pms/tasks";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor pm;
    RequestPostProcessor mem;
    LocalDate today;
    long proj;
    long a;
    long b;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PQ");
        long pmId = accounts.newUser(comp, "pm1", PW);
        long memId = accounts.newUser(comp, "mem1", PW);
        for (long u : List.of(pmId, memId)) {
            accounts.grant(comp, u, "PMS" + u, "pms-project", "SEARCH", "SAVE");
        }
        pm = accounts.as(comp, pmId, "pm1");
        mem = accounts.as(comp, memId, "mem1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY, LocalDate.class);
        proj = id(send(post(P), "{\"projNm\":\"ZT 선행 QA\",\"projType\":\"GENERAL\"}", pm));
        a = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"설계\",\"planStart\":\"" + today.minusDays(10) + "\",\"planEnd\":\""
                + today.minusDays(5) + "\"}", pm));
        b = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"시작품\",\"ownerLoginId\":\"mem1\",\"planStart\":\"" + today.minusDays(4)
                + "\",\"planEnd\":\"" + today.plusDays(3) + "\"}", pm));
        id(send(post(P + "/" + proj + "/deps"), "{\"predTaskId\":" + a + ",\"succTaskId\":" + b + ",\"lagDays\":0}", pm));
        accounts.exec(comp, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'PMS_DEP_ENFORCE', 'BLOCK', 't', 't')""",
                comp);
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

    private long waives(long task) {
        return accounts.query(comp, "select count(*) from pms_task_dep_waive where task_id = ?", Long.class, task);
    }

    /** b 실적 시작(상세 폼, PM) — 계획 그대로, 사유 */
    private String startBody(int rowVer, String reason) throws Exception {
        Map<String, Object> r = row(b);
        return "{\"taskNm\":\"시작품\",\"ownerLoginId\":\"mem1\",\"planStart\":\"" + r.get("planStart") + "\",\"planEnd\":\"" + r.get("planEnd")
                + "\",\"actStart\":\"" + today + "\",\"progressPct\":10,\"rowVer\":" + rowVer + ",\"depReason\":\"" + reason + "\"}";
    }

    /** 같은 rowVer 로 PM 이 실적 시작(사유) 두 번을 동시에 — 하나만 저장, 통과 기록도 하나(프로젝트 잠금 + rowVer) */
    @Test
    void PM_사유_통과_같은_요청_동시_두_번이면_하나만_저장되고_기록도_하나() throws Exception {
        int ver = ((Number) row(b).get("rowVer")).intValue();
        String body = startBody(ver, "고객 일정 우선");
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Integer>> runs = List.of(1, 2).stream().map(i -> CompletableFuture.supplyAsync(() -> {
            try {
                go.await(10, TimeUnit.SECONDS);
                return send(put(K + "/" + b), body, pm).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        })).toList();
        go.countDown();
        assertThat(runs.stream().map(CompletableFuture::join).toList()).containsExactlyInAnyOrder(200, 409);
        assertThat(waives(b)).isEqualTo(1L);
        assertThat(row(b)).containsEntry("status", "IN_PROGRESS");
    }

    /** 담당자 줄 편집 ∥ PM 사유 통과 동시 — 담당자는 거부, PM 은 저장, 기록 하나(담당자 쪽이 먼저 잠가도 같은 결론) */
    @Test
    void 담당자_줄편집과_PM_사유_통과가_동시면_PM_만_저장된다() throws Exception {
        int ver = ((Number) row(b).get("rowVer")).intValue();
        String pmBody = startBody(ver, "병행 승인");
        String memBody = "{\"actStart\":\"" + today + "\",\"progressPct\":10,\"rowVer\":" + ver + "}";
        CountDownLatch go = new CountDownLatch(1);
        CompletableFuture<Integer> m = CompletableFuture.supplyAsync(() -> {
            try {
                go.await(10, TimeUnit.SECONDS);
                return send(post(K + "/" + b + "/actual"), memBody, mem).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        CompletableFuture<Integer> p = CompletableFuture.supplyAsync(() -> {
            try {
                go.await(10, TimeUnit.SECONDS);
                return send(put(K + "/" + b), pmBody, pm).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        go.countDown();
        assertThat(m.join()).isEqualTo(409);           // PMS_DEP_NOT_DONE 또는(PM 이 먼저면) CONFLICT — 어느 쪽이든 담당자 저장 없음
        assertThat(p.join()).isEqualTo(200);
        assertThat(waives(b)).isEqualTo(1L);
        assertThat(accounts.query(comp, "select created_by from pms_task_dep_waive where task_id = ?", String.class, b)).isEqualTo("pm1");
    }

    /** P-a(부록 A-1, #771 에서 고침): PM 이 같은 요청에서 유형을 GATE 로 바꾸며 실적 시작 — 사유 없이는 통과하지 않는다, 되돌려도 마찬가지 */
    @Test
    void 결함Pa_PM_이_유형을_GATE_로_바꿔_시작해도_사유가_필요하다() throws Exception {
        Map<String, Object> r = row(b);
        int code = send(put(K + "/" + b), "{\"taskNm\":\"시작품\",\"taskType\":\"GATE\",\"ownerLoginId\":\"mem1\",\"planStart\":\"" + today
                + "\",\"planEnd\":\"" + today + "\",\"actStart\":\"" + today + "\",\"rowVer\":" + r.get("rowVer") + "}", pm).andReturn().getResponse().getStatus();
        Map<String, Object> r2 = row(b);
        send(put(K + "/" + b), "{\"taskNm\":\"시작품\",\"taskType\":\"TASK\",\"ownerLoginId\":\"mem1\",\"planStart\":\"" + r.get("planStart")
                + "\",\"planEnd\":\"" + r.get("planEnd") + "\",\"actStart\":\"" + today + "\",\"progressPct\":10,\"rowVer\":" + r2.get("rowVer") + "}", pm)
                .andReturn();
        assertThat(code).as("사유 없는 GATE 전환 시작").isNotEqualTo(200);
        assertThat(row(b)).containsEntry("taskType", "TASK").containsEntry("actStart", null);
        assertThat(waives(b)).isZero();
    }

    /** P-b(사용자 결정 2026-11-02): 실적 시작을 넣어 만든 작업에 나중에 미완 선행 의존 — 사유 없이 200·기록 0 이던 것 → 사유 필수·기록 */
    @Test
    void 결함Pb_시작된_작업에_미완_선행을_걸면_사유와_기록() throws Exception {
        long c = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"병행 시작\",\"planStart\":\"" + today + "\",\"planEnd\":\""
                + today.plusDays(2) + "\",\"actStart\":\"" + today + "\"}", pm));
        int code = send(post(P + "/" + proj + "/deps"), "{\"predTaskId\":" + a + ",\"succTaskId\":" + c + "}", pm).andReturn().getResponse().getStatus();
        assertThat(code).as("사유 없는 의존 추가").isNotEqualTo(200);
        assertThat(waives(c)).isZero();
        send(post(P + "/" + proj + "/deps"), "{\"predTaskId\":" + a + ",\"succTaskId\":" + c + ",\"depReason\":\"고객 일정\"}", pm)
                .andExpect(status().isOk());
        assertThat(waives(c)).isEqualTo(1L);
    }

    /** R3-a(QA 2026-11-02 회차 3 부록 A-1): 제로폭 문자(U+200B)만인 PM 통과 사유 — 빈 사유와 같게 칸 오류, 통과 기록 없음 */
    @Test
    void 결함R3a_제로폭_문자만인_사유는_빈_사유와_같다() throws Exception {
        int ver = ((Number) row(b).get("rowVer")).intValue();
        send(put(K + "/" + b), startBody(ver, "\u200B"), pm).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("PMS_DEP_REASON"));
        assertThat(waives(b)).isZero();
    }

    /** R3-a 의 의존 추가 길(P-b) — NBSP·U+FEFF·U+3000 만인 사유도 빈 사유, 의존도 안 생긴다 */
    @Test
    void 결함R3a_의존_추가_사유도_보이지_않는_글자뿐이면_빈_사유() throws Exception {
        long c = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"병행 시작\",\"planStart\":\"" + today + "\",\"planEnd\":\""
                + today.plusDays(2) + "\",\"actStart\":\"" + today + "\"}", pm));
        send(post(P + "/" + proj + "/deps"), "{\"predTaskId\":" + a + ",\"succTaskId\":" + c + ",\"depReason\":\"\u00A0\uFEFF\u3000\"}", pm)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("PMS_DEP_ADD_REASON"));
        assertThat(waives(c)).isZero();
        assertThat(accounts.query(comp, "select count(*) from pms_task_dep where succ_task_id = ?", Long.class, c)).isZero();
    }

    /** B사 사용자가 A사 작업 id 로 내 작업 줄 편집(실적 시작) — 404, A사 작업·기록 그대로 */
    @Test
    void A사로_B사_작업_id_로_내_작업_줄_편집은_404() throws Exception {
        String compB = accounts.newComp("PR");
        long bId = accounts.newUser(compB, "mem1", PW);
        accounts.grant(compB, bId, "PMS", "pms-project", "SEARCH", "SAVE");
        RequestPostProcessor bu = accounts.as(compB, bId, "mem1");
        int ver = ((Number) row(b).get("rowVer")).intValue();
        send(post(K + "/" + b + "/actual"), "{\"actStart\":\"" + today + "\",\"progressPct\":10,\"rowVer\":" + ver + "}", bu)
                .andExpect(status().isNotFound());
        assertThat(row(b)).containsEntry("actStart", null);
        assertThat(waives(b)).isZero();
    }
}
