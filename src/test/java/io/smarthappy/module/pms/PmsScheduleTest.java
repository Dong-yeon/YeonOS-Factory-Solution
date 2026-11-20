package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PMS 2단계 의존·기준선·지연 (docs/32 PM2) — 의존 규칙(잎·같은 프로젝트·순환·중복·요약 금지·하위 금지), 위반 표시,
 * 후행 밀기 미리보기 = 확정(다르면 409), 기준선 불변·계획 변경 사유·이력, 지연 일수, 권한, 닫힌 프로젝트, A사로 B사.
 */
@AutoConfigureMockMvc
class PmsScheduleTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/pms/projects";
    private static final String K = "/api/pms/tasks";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long pmId;
    RequestPostProcessor pm;
    long proj;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PS");
        pmId = accounts.newUser(comp, "pm1", PW);
        accounts.grant(comp, pmId, "PMS", "pms-project", "SEARCH", "SAVE");
        pm = accounts.as(comp, pmId, "pm1");
        proj = id(send(post(P), "{\"projNm\":\"일정\",\"projType\":\"GENERAL\"}", pm));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions send(MockHttpServletRequestBuilder b, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(json(b, body).with(who));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private static <T> T read(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), path);
    }

    private long task(Long parent, String nm, String start, String end) throws Exception {
        return id(send(post(P + "/" + proj + "/tasks"), "{" + (parent == null ? "" : "\"parentId\":" + parent + ",") + "\"taskNm\":\""
                + nm + "\",\"planStart\":\"" + start + "\",\"planEnd\":\"" + end + "\"}", pm));
    }

    private ResultActions dep(long pred, long succ, int lag) throws Exception {
        return send(post(P + "/" + proj + "/deps"), "{\"predTaskId\":" + pred + ",\"succTaskId\":" + succ + ",\"lagDays\":" + lag + "}", pm);
    }

    private Map<String, Object> row(long taskId) throws Exception {
        List<Map<String, Object>> rows = read(mvc.perform(get(P + "/" + proj + "/tasks").with(pm)), "$.data.tasks[?(@.taskId == " + taskId + ")]");
        return rows.get(0);
    }

    @Test
    void 의존_규칙_잎끼리_같은프로젝트_순환_중복_자기자신_하위금지_삭제때_같이지움() throws Exception {
        long ph = task(null, "단계", "2026-03-01", "2026-03-01");
        long a = task(ph, "a", "2026-03-01", "2026-03-10");
        long b = task(ph, "b", "2026-03-11", "2026-03-12");
        long c = task(null, "c", "2026-03-13", "2026-03-13");
        id(dep(a, b, 0));
        id(dep(b, c, 0));
        dep(a, b, 3).andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        dep(c, a, 0).andExpect(jsonPath("$.errors[0].code").value("PMS_DEP_CYCLE"));
        dep(a, a, 0).andExpect(jsonPath("$.errors[0].code").value("PMS_DEP_SELF"));
        dep(ph, c, 0).andExpect(jsonPath("$.errors[0].code").value("PMS_DEP_SUMMARY"));
        dep(a, c, 999).andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        send(post(P + "/" + proj + "/deps"), "{}", pm).andExpect(jsonPath("$.errors.length()").value(2));
        // 다른 프로젝트 작업
        long other = id(send(post(P), "{\"projNm\":\"딴\",\"projType\":\"GENERAL\"}", pm));
        long o1 = id(send(post(P + "/" + other + "/tasks"), "{\"taskNm\":\"o\",\"planStart\":\"2026-03-01\"}", pm));
        dep(a, o1, 0).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        // 직접 SQL: 순환·다른 프로젝트·요약·수정
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into pms_task_dep (comp_cd, proj_id, pred_task_id, succ_task_id, created_by) values (?, ?, ?, ?, 't')""",
                comp, proj, c, a)))).contains("ck_pms_task_dep_cycle");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into pms_task_dep (comp_cd, proj_id, pred_task_id, succ_task_id, created_by) values (?, ?, ?, ?, 't')""",
                comp, proj, a, o1)))).contains("ck_pms_task_dep_tasks");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update pms_task_dep set lag_days = 5 where pred_task_id = ?", a)))).contains("ck_pms_task_dep_fixed");
        // 의존이 걸린 작업 아래 하위 추가·들여쓰기 → 거부(서비스 + 트리거)
        send(post(P + "/" + proj + "/tasks"), "{\"parentId\":" + c + ",\"taskNm\":\"x\",\"planStart\":\"2026-03-13\"}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_HAS_DEPS"));
        long d = task(null, "d", "2026-03-14", "2026-03-14");
        send(post(K + "/" + d + "/move"), "{\"op\":\"INDENT\",\"rowVer\":" + row(d).get("rowVer") + "}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_NO_CHILDREN"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update pms_task set parent_id = ? where task_id = ?", c, d)))).contains("ck_pms_task_parent_has_deps");
        // 작업 삭제 = 그 의존도 같이
        mvc.perform(delete(K + "/" + c).with(csrf()).with(pm)).andExpect(status().isOk());
        mvc.perform(get(P + "/" + proj + "/tasks").with(pm)).andExpect(jsonPath("$.data.deps.length()").value(1));
        // 의존 삭제
        long depId = ((Number) read(mvc.perform(get(P + "/" + proj + "/tasks").with(pm)), "$.data.deps[0].depId")).longValue();
        mvc.perform(delete("/api/pms/deps/" + depId).with(csrf()).with(pm)).andExpect(status().isOk());
        mvc.perform(delete("/api/pms/deps/" + depId).with(csrf()).with(pm)).andExpect(status().isNotFound());
    }

    @Test
    void 위반표시와_후행밀기_미리보기와_확정이_같고_낡은_미리보기는_409() throws Exception {
        long a = task(null, "a", "2026-03-01", "2026-03-10");
        long b = task(null, "b", "2026-03-05", "2026-03-06");
        long c = task(null, "c", "2026-03-15", "2026-03-15");
        id(dep(a, b, 0));
        id(dep(b, c, 1));
        ResultActions v = mvc.perform(get(P + "/" + proj + "/tasks").with(pm));
        v.andExpect(jsonPath("$.data.deps[0].violated").value(true)).andExpect(jsonPath("$.data.deps[1].violated").value(false));
        assertThat(row(b).get("depViolation")).isEqualTo(true);
        // 미리보기: b 3-11~12(6일), c 는 3-14 이후면 되는데 이미 3-15 → 그대로
        mvc.perform(get(P + "/" + proj + "/push-preview").with(pm))
                .andExpect(jsonPath("$.data.moves.length()").value(1))
                .andExpect(jsonPath("$.data.moves[0].newStart").value("2026-03-11"))
                .andExpect(jsonPath("$.data.moves[0].newEnd").value("2026-03-12"))
                .andExpect(jsonPath("$.data.needsReason").value(false));
        // 그 사이 누가 a 를 늘림 → 화면이 본 미리보기로 확정하면 409
        String stale = "{\"expected\":[{\"taskId\":" + b + ",\"newStart\":\"2026-03-11\"}]}";
        send(put(K + "/" + a), "{\"taskNm\":\"a\",\"planStart\":\"2026-03-01\",\"planEnd\":\"2026-03-14\",\"rowVer\":" + row(a).get("rowVer") + "}", pm)
                .andExpect(status().isOk());
        send(post(P + "/" + proj + "/push"), stale, pm).andExpect(status().isConflict());
        // 다시 미리보기 → b 3-15~16, c 3-18 → 그대로 확정
        List<Map<String, Object>> moves = read(mvc.perform(get(P + "/" + proj + "/push-preview").with(pm)), "$.data.moves");
        assertThat(moves).extracting(m -> m.get("newStart")).containsExactly("2026-03-15", "2026-03-18");
        StringBuilder exp = new StringBuilder("{\"expected\":[");
        for (int i = 0; i < moves.size(); i++) {
            exp.append(i > 0 ? "," : "").append("{\"taskId\":").append(moves.get(i).get("taskId")).append(",\"newStart\":\"")
                    .append(moves.get(i).get("newStart")).append("\"}");
        }
        exp.append("]}");
        assertThat(id(send(post(P + "/" + proj + "/push"), exp.toString(), pm))).isEqualTo(2);
        assertThat(row(c).get("planStart")).isEqualTo("2026-03-18");
        mvc.perform(get(P + "/" + proj + "/tasks").with(pm)).andExpect(jsonPath("$.data.deps[*].violated").value(contains(false, false)))
                .andExpect(jsonPath("$.data.project.planEnd").value("2026-03-18"));
        // 더 밀 것 없음
        send(post(P + "/" + proj + "/push"), "{\"expected\":[]}", pm).andExpect(jsonPath("$.errors[0].code").value("PMS_NOTHING_TO_PUSH"));
    }

    @Test
    void 기준선은_번호_올라가고_불변_뒤의_계획변경은_사유필수_이력_지연일수() throws Exception {
        long a = task(null, "a", "2026-03-01", "2026-03-10");
        long b = task(null, "b", "2026-03-11", "2026-03-12");
        send(post(P + "/" + proj + "/baselines"), "{\"reason\":\" \"}", pm).andExpect(jsonPath("$.errors[0].field").value("reason"));
        assertThat(id(send(post(P + "/" + proj + "/baselines"), "{\"reason\":\"고객 일정 합의\"}", pm))).isEqualTo(1);
        // 기준선 뒤 계획일 변경: 사유 없으면 칸 오류, 있으면 저장 + 이력, 이름만 바꾸면 사유 불필요
        String body = "{\"taskNm\":\"a\",\"planStart\":\"2026-03-01\",\"planEnd\":\"2026-03-15\",\"rowVer\":0";
        send(put(K + "/" + a), body + "}", pm).andExpect(jsonPath("$.errors[0].field").value("planReason"));
        send(put(K + "/" + a), body + ",\"planReason\":\"금형 수정\"}", pm).andExpect(status().isOk());
        send(put(K + "/" + a), "{\"taskNm\":\"a2\",\"planStart\":\"2026-03-01\",\"planEnd\":\"2026-03-15\",\"rowVer\":1}", pm).andExpect(status().isOk());
        mvc.perform(get(K + "/" + a + "/plan-changes").with(pm)).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].beforeEnd").value("2026-03-10")).andExpect(jsonPath("$.data[0].afterEnd").value("2026-03-15"))
                .andExpect(jsonPath("$.data[0].source").value("EDIT")).andExpect(jsonPath("$.data[0].baselineNo").value(1));
        // 지연: a +5일, 기준선 뒤 새 작업은 기준선 없음, 프로젝트 = 지금 끝(새 작업 3-20) - 기준선 끝 3-12 = +8
        long n = task(null, "new", "2026-03-20", "2026-03-20");
        ResultActions v = mvc.perform(get(P + "/" + proj + "/tasks").with(pm));
        v.andExpect(jsonPath("$.data.baseline.baselineNo").value(1)).andExpect(jsonPath("$.data.projectSlipDays").value(8));
        assertThat(row(a).get("slipDays")).isEqualTo(5);
        assertThat(row(a).get("baseEnd")).isEqualTo("2026-03-10");
        assertThat(row(n).get("slipDays")).isNull();
        // 두 번째 기준선 → 2, 지연은 최신 기준선 대비
        assertThat(id(send(post(P + "/" + proj + "/baselines"), "{\"reason\":\"재계획\"}", pm))).isEqualTo(2);
        assertThat(row(a).get("slipDays")).isEqualTo(0);
        mvc.perform(get(P + "/" + proj + "/baselines").with(pm)).andExpect(jsonPath("$.data[*].baselineNo").value(contains(2, 1)))
                .andExpect(jsonPath("$.data[0].taskCnt").value(3));
        // 밀기도 기준선 뒤엔 사유 필수 + PUSH 이력
        id(dep(a, b, 0));
        List<Map<String, Object>> moves = read(mvc.perform(get(P + "/" + proj + "/push-preview").with(pm)), "$.data.moves");
        String exp = "{\"expected\":[{\"taskId\":" + b + ",\"newStart\":\"" + moves.get(0).get("newStart") + "\"}]";
        send(post(P + "/" + proj + "/push"), exp + "}", pm).andExpect(jsonPath("$.errors[0].field").value("reason"));
        send(post(P + "/" + proj + "/push"), exp + ",\"reason\":\"선행 지연\"}", pm).andExpect(status().isOk());
        mvc.perform(get(K + "/" + b + "/plan-changes").with(pm)).andExpect(jsonPath("$.data[0].source").value("PUSH"))
                .andExpect(jsonPath("$.data[0].baselineNo").value(2));
        // 직접 SQL: 기준선·스냅샷·이력 UPDATE/DELETE → 권한 회수(42501)
        for (String sql : List.of("update pms_baseline set reason = 'x'", "delete from pms_baseline_task",
                "update pms_task_plan_change set reason = 'x'")) {
            assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, sql)))).contains("42501");
        }
    }

    @Test
    void 권한_담당자는_의존_기준선_밀기_못함_조회만_닫힌프로젝트_B사() throws Exception {
        long a = task(null, "a", "2026-03-01", "2026-03-10");
        long b = task(null, "b", "2026-03-05", "2026-03-06");
        long eng = accounts.newUser(comp, "eng1", PW);
        accounts.grant(comp, eng, "ENG", "pms-project", "SEARCH", "SAVE");
        RequestPostProcessor e = accounts.as(comp, eng, "eng1");
        send(post(P + "/" + proj + "/deps"), "{\"predTaskId\":" + a + ",\"succTaskId\":" + b + "}", e).andExpect(jsonPath("$.code").value("PMS_NOT_EDITABLE"));
        send(post(P + "/" + proj + "/baselines"), "{\"reason\":\"x\"}", e).andExpect(jsonPath("$.code").value("PMS_NOT_EDITABLE"));
        send(post(P + "/" + proj + "/push"), "{\"expected\":[]}", e).andExpect(jsonPath("$.code").value("PMS_NOT_EDITABLE"));
        mvc.perform(get(P + "/" + proj + "/push-preview").with(e)).andExpect(status().isOk());
        long rd = accounts.newUser(comp, "rd1", PW);
        accounts.grant(comp, rd, "RD", "pms-project", "SEARCH");
        RequestPostProcessor r = accounts.as(comp, rd, "rd1");
        send(post(P + "/" + proj + "/baselines"), "{\"reason\":\"x\"}", r).andExpect(status().isForbidden());
        // 기준선은 작업이 있어야
        long empty = id(send(post(P), "{\"projNm\":\"빈\",\"projType\":\"GENERAL\"}", pm));
        send(post(P + "/" + empty + "/baselines"), "{\"reason\":\"x\"}", pm).andExpect(jsonPath("$.errors[0].code").value("PMS_NO_TASKS"));
        // B사: 의존·기준선·미리보기·이력 모두 404
        String other = accounts.newComp("PT");
        long bu = accounts.newUser(other, "pm1", PW);
        accounts.grant(other, bu, "PMS", "pms-project", "SEARCH", "SAVE", "APPROVE");
        RequestPostProcessor bb = accounts.as(other, bu, "pm1");
        long depId = id(dep(a, b, 0));
        send(post(P + "/" + proj + "/deps"), "{\"predTaskId\":" + b + ",\"succTaskId\":" + a + "}", bb).andExpect(status().isNotFound());
        mvc.perform(delete("/api/pms/deps/" + depId).with(csrf()).with(bb)).andExpect(status().isNotFound());
        send(post(P + "/" + proj + "/baselines"), "{\"reason\":\"x\"}", bb).andExpect(status().isNotFound());
        mvc.perform(get(P + "/" + proj + "/baselines").with(bb)).andExpect(status().isNotFound());
        mvc.perform(get(P + "/" + proj + "/push-preview").with(bb)).andExpect(status().isNotFound());
        mvc.perform(get(K + "/" + a + "/plan-changes").with(bb)).andExpect(status().isNotFound());
        // 직접 SQL: B사 범위로 A사 프로젝트 의존 → 트리거
        long bp = id(send(post(P), "{\"projNm\":\"b\",\"projType\":\"GENERAL\"}", bb));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into pms_task_dep (comp_cd, proj_id, pred_task_id, succ_task_id, created_by) values (?, ?, ?, ?, 't')""",
                other, bp, a, b)))).contains("ck_pms_task_dep_tasks");
        // 닫힌 프로젝트: 의존 추가·삭제·기준선 → 409
        send(post(P + "/" + proj + "/status"), "{\"status\":\"CANCELLED\",\"reason\":\"중단\",\"rowVer\":0}", pm).andExpect(status().isOk());
        send(post(P + "/" + proj + "/deps"), "{\"predTaskId\":" + b + ",\"succTaskId\":" + a + "}", pm).andExpect(jsonPath("$.code").value("PMS_PROJECT_CLOSED"));
        mvc.perform(delete("/api/pms/deps/" + depId).with(csrf()).with(pm)).andExpect(jsonPath("$.code").value("PMS_PROJECT_CLOSED"));
        send(post(P + "/" + proj + "/baselines"), "{\"reason\":\"x\"}", pm).andExpect(jsonPath("$.code").value("PMS_PROJECT_CLOSED"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "delete from pms_task_dep where dep_id = ?", depId))))
                .contains("ck_pms_task_dep_project_closed");
    }

    @Test
    void 의존_500개_사슬_밀기_실측() throws Exception {
        accounts.exec(comp, """
                insert into pms_task (comp_cd, proj_id, seq, task_nm, plan_start, plan_end, created_by, updated_by)
                select ?, ?, g, 't' || g, date '2026-03-01', date '2026-03-02', 't', 't' from generate_series(1, 500) g""", comp, proj);
        accounts.exec(comp, """
                insert into pms_task_dep (comp_cd, proj_id, pred_task_id, succ_task_id, created_by)
                select ?, ?, a.task_id, b.task_id, 't' from pms_task a join pms_task b on b.proj_id = a.proj_id and b.seq = a.seq + 1
                 where a.proj_id = ?""", comp, proj, proj);
        long t0 = System.nanoTime();
        List<Map<String, Object>> moves = read(mvc.perform(get(P + "/" + proj + "/push-preview").with(pm)), "$.data.moves");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(moves).hasSize(499);
        // 마지막 = 3-01 + 2일 × 499 → 2028-12-21 무렵 — 손 검산: 시작 = 3-01 + 998일
        assertThat(moves.get(498).get("newStart")).isEqualTo(java.time.LocalDate.parse("2026-03-01").plusDays(998).toString());
        System.out.printf("[PMS 실측] 의존 499개 사슬 밀기 미리보기 %d ms%n", ms);
        assertThat(ms).isLessThan(5000);
    }
}
