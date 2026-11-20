package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * 선행 미완 실적 제약 (docs/32 4.21, 사용자 결정 P-12) — 정책 PMS_DEP_ENFORCE OFF·WARN(기본, 지금 그대로)·BLOCK, BLOCK 에서 담당자는 거부
 * (상세 폼·내 작업 줄 둘 다), PM 은 사유로 통과 + 기록, 선행 완료·취소면 통과, 이미 시작한 작업·게이트는 대상 아님, 직접 SQL, A사로 B사.
 */
@AutoConfigureMockMvc
class PmsDepEnforceTest extends PostgresTestBase {

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
    long a;
    long b;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PD");
        pmId = accounts.newUser(comp, "pm1", PW);
        memId = accounts.newUser(comp, "mem1", PW);
        for (long u : List.of(pmId, memId)) {
            accounts.grant(comp, u, "PMS" + u, "pms-project", "SEARCH", "SAVE");
        }
        pm = accounts.as(comp, pmId, "pm1");
        mem = accounts.as(comp, memId, "mem1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY, LocalDate.class);
        proj = id(send(post(P), "{\"projNm\":\"ZT 선행\",\"projType\":\"GENERAL\"}", pm));
        a = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"설계\",\"planStart\":\"" + today.minusDays(10) + "\",\"planEnd\":\""
                + today.minusDays(5) + "\"}", pm));
        b = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"시작품\",\"ownerLoginId\":\"mem1\",\"planStart\":\"" + today.minusDays(4)
                + "\",\"planEnd\":\"" + today.plusDays(3) + "\"}", pm));
        id(send(post(P + "/" + proj + "/deps"), "{\"predTaskId\":" + a + ",\"succTaskId\":" + b + ",\"lagDays\":0}", pm));
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

    private void policy(String value) {
        accounts.exec(comp, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'PMS_DEP_ENFORCE', ?, 't', 't')
                on conflict do nothing""", comp, value);
        accounts.exec(comp, "update sys_policy set policy_value = ? where policy_key = 'PMS_DEP_ENFORCE' and plant_cd = '*'", value);
    }

    /** b 실적 시작(상세 폼) — 계획·담당 그대로 */
    private ResultActions startB(RequestPostProcessor who, String depReason) throws Exception {
        Map<String, Object> r = row(b);
        return send(put(K + "/" + b), "{\"taskNm\":\"시작품\",\"ownerLoginId\":\"mem1\",\"planStart\":\"" + r.get("planStart") + "\",\"planEnd\":\""
                + r.get("planEnd") + "\",\"actStart\":\"" + today + "\",\"progressPct\":10,\"rowVer\":" + r.get("rowVer")
                + (depReason == null ? "" : ",\"depReason\":\"" + depReason + "\"") + "}", who);
    }

    private long waives() {
        return accounts.query(comp, "select count(*) from pms_task_dep_waive where task_id = ?", Long.class, b);
    }

    @Test
    void 기본WARN_과_OFF는_지금처럼_시작됨_기록없음() throws Exception {
        startB(mem, null).andExpect(status().isOk());
        assertThat(row(b)).containsEntry("status", "IN_PROGRESS");
        // 위반 표시는 그대로(선행 끝 전에 시작 = 경고 화면 표시는 기존 규칙)
        assertThat(waives()).isZero();
        policy("OFF");
        Map<String, Object> r = row(b);
        send(put(K + "/" + b), "{\"taskNm\":\"시작품\",\"ownerLoginId\":\"mem1\",\"planStart\":\"" + r.get("planStart") + "\",\"planEnd\":\""
                + r.get("planEnd") + "\",\"rowVer\":" + r.get("rowVer") + "}", pm).andExpect(status().isOk());   // 실적 지움(시작 전)
        startB(mem, null).andExpect(status().isOk());
        assertThat(waives()).isZero();
    }

    @Test
    void BLOCK_담당자_거부_상세폼과_줄편집_둘다_PM은_사유로_통과_기록() throws Exception {
        policy("BLOCK");
        startB(mem, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PMS_DEP_NOT_DONE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("1 설계")));
        // 내 작업 줄(C-1)도 같은 경로
        send(post(K + "/" + b + "/actual"), "{\"actStart\":\"" + today + "\",\"progressPct\":10,\"rowVer\":" + row(b).get("rowVer") + "}", mem)
                .andExpect(jsonPath("$.code").value("PMS_DEP_NOT_DONE"));
        // 담당자가 사유를 보내도 통과 못 함(PM 만)
        startB(mem, "급함").andExpect(jsonPath("$.code").value("PMS_DEP_NOT_DONE"));
        assertThat(row(b)).containsEntry("actStart", null);
        // PM: 사유 없으면 칸 오류, 공백도, 사유 있으면 저장 + 기록
        startB(pm, null).andExpect(jsonPath("$.errors[0].field").value("depReason"))
                .andExpect(jsonPath("$.errors[0].code").value("PMS_DEP_REASON"));
        startB(pm, "  ").andExpect(jsonPath("$.errors[0].code").value("PMS_DEP_REASON"));
        assertThat(waives()).isZero();
        startB(pm, "고객 일정 우선 — 설계 병행").andExpect(status().isOk());
        assertThat(row(b)).containsEntry("status", "IN_PROGRESS");
        List<Map<String, Object>> w = JsonPath.read(mvc.perform(get(K + "/" + b + "/dep-waivers").with(mem)).andReturn().getResponse()
                .getContentAsString(), "$.data");
        assertThat(w).hasSize(1);
        assertThat(w.get(0)).containsEntry("reason", "고객 일정 우선 — 설계 병행").containsEntry("openPreds", "1 설계")
                .containsEntry("createdBy", "pm1");
        // 이미 시작한 작업의 진척 수정은 대상 아님(담당자)
        send(post(K + "/" + b + "/actual"), "{\"actStart\":\"" + today + "\",\"progressPct\":30,\"rowVer\":" + row(b).get("rowVer") + "}", mem)
                .andExpect(status().isOk());
        assertThat(waives()).isEqualTo(1L);
    }

    @Test
    void BLOCK_선행이_완료_또는_취소면_통과_게이트는_대상아님() throws Exception {
        policy("BLOCK");
        Map<String, Object> ra = row(a);
        send(put(K + "/" + a), "{\"taskNm\":\"설계\",\"planStart\":\"" + ra.get("planStart") + "\",\"planEnd\":\"" + ra.get("planEnd")
                + "\",\"actStart\":\"" + today.minusDays(9) + "\",\"actEnd\":\"" + today.minusDays(5) + "\",\"rowVer\":" + ra.get("rowVer") + "}", pm)
                .andExpect(status().isOk());
        startB(mem, null).andExpect(status().isOk());
        assertThat(waives()).isZero();
        // 취소된 선행 → 끝난 것으로
        long c = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"보류 검토\",\"planStart\":\"" + today + "\",\"planEnd\":\"" + today + "\"}", pm));
        long d = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"후속\",\"ownerLoginId\":\"mem1\",\"planStart\":\"" + today
                + "\",\"planEnd\":\"" + today.plusDays(1) + "\"}", pm));
        id(send(post(P + "/" + proj + "/deps"), "{\"predTaskId\":" + c + ",\"succTaskId\":" + d + "}", pm));
        Map<String, Object> rc = row(c);
        send(put(K + "/" + c), "{\"taskNm\":\"보류 검토\",\"planStart\":\"" + today + "\",\"planEnd\":\"" + today + "\",\"status\":\"CANCELLED\","
                + "\"remark\":\"범위 제외\",\"rowVer\":" + rc.get("rowVer") + "}", pm).andExpect(status().isOk());
        send(post(K + "/" + d + "/actual"), "{\"actStart\":\"" + today + "\",\"progressPct\":5,\"rowVer\":" + row(d).get("rowVer") + "}", mem)
                .andExpect(status().isOk());
        // 게이트(선행 미완) → 이 규칙 대상 아님(완료는 심사로)
        long g = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"G\",\"taskType\":\"GATE\",\"ownerLoginId\":\"mem1\",\"planStart\":\""
                + today.plusDays(5) + "\",\"planEnd\":\"" + today.plusDays(5) + "\"}", pm));
        long e = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"미완 선행\",\"planStart\":\"" + today + "\",\"planEnd\":\"" + today + "\"}", pm));
        id(send(post(P + "/" + proj + "/deps"), "{\"predTaskId\":" + e + ",\"succTaskId\":" + g + "}", pm));
        Map<String, Object> rg = row(g);
        send(put(K + "/" + g), "{\"taskNm\":\"G\",\"taskType\":\"GATE\",\"ownerLoginId\":\"mem1\",\"planStart\":\"" + rg.get("planStart")
                + "\",\"planEnd\":\"" + rg.get("planEnd") + "\",\"actStart\":\"" + today + "\",\"rowVer\":" + rg.get("rowVer") + "}", mem)
                .andExpect(status().isOk());
    }

    @Test
    void BLOCK_PM이_같은_요청으로_GATE로_바꾸며_시작해도_제약_적용() throws Exception {
        // QA 2026-11-02 P-a: 제외를 새 유형만 보면 TASK→GATE + 실적 시작이 200·기록 0 으로 지나가고, 뒤에 TASK 로 되돌릴 수 있었다
        policy("BLOCK");
        Map<String, Object> r = row(b);
        String body = "{\"taskNm\":\"시작품\",\"taskType\":\"GATE\",\"ownerLoginId\":\"mem1\",\"planStart\":\"" + r.get("planStart")
                + "\",\"planEnd\":\"" + r.get("planStart") + "\",\"actStart\":\"" + today + "\",\"rowVer\":" + r.get("rowVer");
        send(put(K + "/" + b), body + "}", pm).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("depReason"))
                .andExpect(jsonPath("$.errors[0].code").value("PMS_DEP_REASON"));
        assertThat(row(b).get("taskType")).isEqualTo("TASK");
        assertThat(row(b).get("actStart")).isNull();
        assertThat(waives()).isZero();
        // 사유를 붙이면 PM 통과 + 기록(다른 길과 같게)
        send(put(K + "/" + b), body + ",\"depReason\":\"ZT 고객 일정\"}", pm).andExpect(status().isOk());
        assertThat(row(b).get("taskType")).isEqualTo("GATE");
        assertThat(waives()).isEqualTo(1);
    }

    @Test
    void Pb_BLOCK_시작된_작업에_미완_선행_추가는_PM_사유로만_기록_대상아님은_그대로() throws Exception {
        // 사용자 결정 P-b(docs/32 4.21 E7): 실적 시작을 넣어 만든 작업 c 에 끝나지 않은 a(설계)를 선행으로
        policy("BLOCK");
        long c = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"병행 시작\",\"ownerLoginId\":\"mem1\",\"planStart\":\"" + today
                + "\",\"planEnd\":\"" + today.plusDays(2) + "\",\"actStart\":\"" + today + "\"}", pm));
        String dep = "{\"predTaskId\":" + a + ",\"succTaskId\":" + c;
        send(post(P + "/" + proj + "/deps"), dep + "}", pm).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("depReason"))
                .andExpect(jsonPath("$.errors[0].code").value("PMS_DEP_ADD_REASON"))
                .andExpect(jsonPath("$.errors[0].message", containsString("1 설계")));
        send(post(P + "/" + proj + "/deps"), dep + ",\"depReason\":\"   \"}", pm).andExpect(jsonPath("$.errors[0].field").value("depReason"));
        assertThat(depCount(c)).isZero();       // 거부면 의존도 안 생긴다(같은 트랜잭션)
        // 담당자(관리 못 함)는 의존 추가 자체가 안 된다 — 사유를 붙여도
        send(post(P + "/" + proj + "/deps"), dep + ",\"depReason\":\"담당 사유\"}", mem).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PMS_NOT_EDITABLE"));
        // PM 사유 → 의존 생김 + 기록(그 선행 이름·사유·누가)
        send(post(P + "/" + proj + "/deps"), dep + ",\"depReason\":\"ZT 고객 일정 병행\"}", pm).andExpect(status().isOk());
        assertThat(depCount(c)).isEqualTo(1);
        assertThat(accounts.query(comp, "select open_preds || '|' || reason || '|' || created_by from pms_task_dep_waive where task_id = ?",
                String.class, c)).isEqualTo("1 설계|ZT 고객 일정 병행|pm1");
        // 대상 아님: 아직 시작 안 한 후행 / 끝난 선행 / 게이트 후행 → 사유 없이 추가, 기록 없음
        long d = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"미착수\",\"planStart\":\"" + today + "\",\"planEnd\":\"" + today + "\"}", pm));
        id(send(post(P + "/" + proj + "/deps"), "{\"predTaskId\":" + a + ",\"succTaskId\":" + d + "}", pm));
        long done = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"끝난 일\",\"planStart\":\"" + today.minusDays(3) + "\",\"planEnd\":\""
                + today.minusDays(2) + "\",\"actStart\":\"" + today.minusDays(3) + "\",\"actEnd\":\"" + today.minusDays(2) + "\"}", pm));
        id(send(post(P + "/" + proj + "/deps"), "{\"predTaskId\":" + done + ",\"succTaskId\":" + c + "}", pm));
        long g = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"G\",\"taskType\":\"GATE\",\"planStart\":\"" + today + "\",\"planEnd\":\""
                + today + "\",\"actStart\":\"" + today + "\"}", pm));
        id(send(post(P + "/" + proj + "/deps"), "{\"predTaskId\":" + a + ",\"succTaskId\":" + g + "}", pm));
        assertThat(accounts.query(comp, "select count(*) from pms_task_dep_waive where proj_id = ?", Long.class, proj)).isEqualTo(1L);
    }

    @Test
    void Pb_WARN_OFF_는_그대로_복사로_생기는_의존은_대상아님_A사로_B사() throws Exception {
        long c = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"병행 시작\",\"planStart\":\"" + today + "\",\"planEnd\":\""
                + today.plusDays(2) + "\",\"actStart\":\"" + today + "\"}", pm));
        for (String mode : List.of("WARN", "OFF")) {
            policy(mode);
            long depId = id(send(post(P + "/" + proj + "/deps"), "{\"predTaskId\":" + a + ",\"succTaskId\":" + c + "}", pm));
            mvc.perform(delete("/api/pms/deps/" + depId).with(csrf()).with(pm)).andExpect(status().isOk());
        }
        // BLOCK 에서 프로젝트 복사 — 새 작업은 실적 없이 만들어지고 의존이 한꺼번에 생긴다 → 사유·기록 없음
        policy("BLOCK");
        long copied = id(send(post(P + "/" + proj + "/copy"), "{\"projNm\":\"ZT 복사\",\"projType\":\"GENERAL\",\"startDate\":\"" + today + "\"}", pm));
        assertThat(accounts.query(comp, "select count(*) from pms_task_dep where proj_id = ?", Long.class, copied)).isEqualTo(1L);
        assertThat(accounts.query(comp, "select count(*) from pms_task where proj_id = ? and act_start is not null", Long.class, copied)).isZero();
        assertThat(accounts.query(comp, "select count(*) from pms_task_dep_waive", Long.class)).isZero();
        // B사: A사 프로젝트에 사유 붙여 의존 추가 → 404, 기록 없음
        String compB = accounts.newComp("PE");
        long bId = accounts.newUser(compB, "pm1", PW);
        accounts.grant(compB, bId, "PMS", "pms-project", "SEARCH", "SAVE");
        send(post(P + "/" + proj + "/deps"), "{\"predTaskId\":" + a + ",\"succTaskId\":" + c + ",\"depReason\":\"B 사유\"}",
                accounts.as(compB, bId, "pm1")).andExpect(status().isNotFound());
        assertThat(depCount(c)).isZero();
        assertThat(accounts.query(comp, "select count(*) from pms_task_dep_waive", Long.class)).isZero();
    }

    private int depCount(long succ) {
        return accounts.query(comp, "select count(*) from pms_task_dep where succ_task_id = ?", Integer.class, succ);
    }

    @Test
    void 직접SQL_기록_고치기_지우기_불가_다른프로젝트_작업() throws Exception {
        policy("BLOCK");
        startB(pm, "사유").andExpect(status().isOk());
        long w = accounts.query(comp, "select waive_id from pms_task_dep_waive where task_id = ?", Long.class, b);
        assertThat(catchThrowable(() -> accounts.exec(comp, "update pms_task_dep_waive set reason = 'x' where waive_id = ?", w)))
                .hasRootCauseMessage("ERROR: permission denied for table pms_task_dep_waive");
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from pms_task_dep_waive where waive_id = ?", w)))
                .hasRootCauseMessage("ERROR: permission denied for table pms_task_dep_waive");
        long other = id(send(post(P), "{\"projNm\":\"다른\",\"projType\":\"GENERAL\"}", pm));
        assertThat(catchThrowable(() -> accounts.exec(comp, "insert into pms_task_dep_waive (comp_cd, proj_id, task_id, reason, open_preds, "
                + "created_by) values (?, ?, ?, 'x', 'y', 't')", comp, other, b))).hasMessageContaining("is not in project");
    }

    @Test
    void A사로_B사_기록_조회_404() throws Exception {
        policy("BLOCK");
        startB(pm, "사유").andExpect(status().isOk());
        String compB = accounts.newComp("PE");
        long bId = accounts.newUser(compB, "pm1", PW);
        accounts.grant(compB, bId, "PMS", "pms-project", "SEARCH", "SAVE");
        RequestPostProcessor bu = accounts.as(compB, bId, "pm1");
        mvc.perform(get(K + "/" + b + "/dep-waivers").with(bu)).andExpect(status().isNotFound());
        startB(bu, "남의 사유").andExpect(status().isNotFound());
        assertThat(waives()).isEqualTo(1L);
    }
}
