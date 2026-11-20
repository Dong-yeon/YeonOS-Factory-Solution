package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 프로젝트 착수·종료 결재 (docs/32 4.16, docs/48 P-3·P-4) — 정책 N 이면 바로 바꾸기, 정책 Y 면 결재로만(PMS_PROJ_OPEN·PMS_PROJ_CLOSE),
 * 결재 중 머리 정보·상태·ECO 잠금(API·직접 SQL), 반려·회수 = 상태 그대로, 종료 조건 상신 때·승인 때 두 번, 결재선 입력·자기결재,
 * A사로 B사.
 */
@AutoConfigureMockMvc
class PmsProjAprTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/pms/projects";
    private static final String K = "/api/pms/tasks";
    private static final String APR = "/api/platform/approvals";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long pmId;
    long aprUserId;
    RequestPostProcessor pm;
    RequestPostProcessor approver;
    long proj;
    long task;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PA");
        pmId = accounts.newUser(comp, "pm1", PW);
        aprUserId = accounts.newUser(comp, "apr1", PW);
        accounts.grant(comp, pmId, "PMS", "pms-project", "SEARCH", "SAVE");
        accounts.grant(comp, pmId, "PMS", "pms-issue", "SEARCH", "SAVE");
        for (long u : List.of(pmId, aprUserId)) {
            accounts.grant(comp, u, "APRBOX" + u, "sys-approval", "SEARCH");
        }
        pm = accounts.as(comp, pmId, "pm1");
        approver = accounts.as(comp, aprUserId, "apr1");
        proj = id(send(post(P), "{\"projNm\":\"ZT 착수결재\",\"projType\":\"GENERAL\"}", pm));
        task = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"설계\",\"planStart\":\"2026-03-01\",\"planEnd\":\"2026-03-10\"}", pm));
    }

    // ------------------------------------------------------------------ 도우미

    private ResultActions send(MockHttpServletRequestBuilder r, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(r.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andReturn();
        String s = m.getResponse().getContentAsString();
        assertThat(m.getResponse().getStatus()).as(s).isEqualTo(200);
        return ((Number) JsonPath.read(s, "$.data")).longValue();
    }

    private Map<String, Object> proj() throws Exception {
        return JsonPath.read(mvc.perform(get(P + "/" + proj).with(pm)).andReturn().getResponse().getContentAsString(), "$.data");
    }

    private int ver() throws Exception {
        return ((Number) proj().get("rowVer")).intValue();
    }

    private ResultActions move(String to) throws Exception {
        return send(post(P + "/" + proj + "/status"), "{\"status\":\"" + to + "\",\"rowVer\":" + ver() + "}", pm);
    }

    private ResultActions submit(String to, long approverId, RequestPostProcessor who) throws Exception {
        return send(post(P + "/" + proj + "/approval"), "{\"to\":\"" + to + "\",\"rowVer\":" + ver()
                + ",\"lines\":[{\"approverId\":" + approverId + ",\"lineType\":\"APPROVE\"}]}", who);
    }

    private void policy(String value) {
        accounts.exec(comp, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'PMS_PROJ_APPROVAL', ?, 't', 't')
                on conflict do nothing""", comp, value);
        accounts.exec(comp, "update sys_policy set policy_value = ? where policy_key = 'PMS_PROJ_APPROVAL' and plant_cd = '*'", value);
    }

    private void finishTask() throws Exception {
        int tv = accounts.query(comp, "select row_ver from pms_task where task_id = ?", Integer.class, task);
        send(put(K + "/" + task), "{\"taskNm\":\"설계\",\"planStart\":\"2026-03-01\",\"planEnd\":\"2026-03-10\",\"actStart\":\"2026-03-01\","
                + "\"actEnd\":\"2026-03-09\",\"rowVer\":" + tv + "}", pm).andExpect(status().isOk());
    }

    private String aprStatus(long aprId) {
        return accounts.query(comp, "select status from apr_request where apr_id = ?", String.class, aprId);
    }

    // ------------------------------------------------------------------ 정책

    @Test
    void 정책N이면_바로_착수_종료_결재상신은_거부() throws Exception {
        assertThat(proj()).containsEntry("approvalOn", false).containsEntry("aprTo", null);
        submit("ACTIVE", aprUserId, pm).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PMS_PROJ_APR_STATE"));
        move("ACTIVE").andExpect(status().isOk());
        finishTask();
        move("CLOSED").andExpect(status().isOk());
        assertThat(proj()).containsEntry("status", "CLOSED");
    }

    @Test
    void 정책Y_착수_결재승인이면_진행_결재중엔_정보_상태_ECO_잠금() throws Exception {
        policy("Y");
        assertThat(proj()).containsEntry("approvalOn", true);
        // 바로 바꾸기는 막힘(착수·종료만 — 보류는 결재 대상 아님)
        move("ACTIVE").andExpect(jsonPath("$.errors[0].code").value("PMS_APPROVAL_REQUIRED"));
        long apr = id(submit("ACTIVE", aprUserId, pm));
        assertThat(proj()).containsEntry("status", "DRAFT").containsEntry("aprTo", "ACTIVE").containsEntry("aprId", (int) apr);
        // 결재자 결재함: 문서 = 프로젝트 화면
        mvc.perform(get(APR + "/box").param("box", "TODO").with(approver))
                .andExpect(jsonPath("$.list[0].docType").value("PMS_PROJ_OPEN"))
                .andExpect(jsonPath("$.list[0].docProg").value("pms-project"));
        // 결재 중: 다시 상신·정보 수정·상태·ECO → 409
        submit("ACTIVE", aprUserId, pm).andExpect(jsonPath("$.code").value("PMS_PROJ_IN_APPROVAL"));
        send(put(P + "/" + proj), "{\"projNm\":\"바꿈\",\"projType\":\"GENERAL\",\"rowVer\":" + ver() + "}", pm)
                .andExpect(jsonPath("$.code").value("PMS_PROJ_IN_APPROVAL"));
        move("CANCELLED").andExpect(jsonPath("$.code").value("PMS_PROJ_IN_APPROVAL"));
        send(put(P + "/" + proj + "/eco"), "{\"ecoNo\":null,\"rowVer\":" + ver() + "}", pm)
                .andExpect(jsonPath("$.code").value("PMS_PROJ_IN_APPROVAL"));
        // 작업 일정은 계속 고칠 수 있다(머리 정보만 잠금 — 일정 롤업은 열림)
        int tv = accounts.query(comp, "select row_ver from pms_task where task_id = ?", Integer.class, task);
        send(put(K + "/" + task), "{\"taskNm\":\"설계\",\"planStart\":\"2026-03-02\",\"planEnd\":\"2026-03-12\",\"rowVer\":" + tv + "}", pm)
                .andExpect(status().isOk());

        send(post(APR + "/" + apr + "/approve"), "{\"comment\":\"착수 승인\"}", approver).andExpect(status().isOk());
        assertThat(proj()).containsEntry("status", "ACTIVE").containsEntry("aprTo", null).containsEntry("aprId", null)
                .containsEntry("planStart", "2026-03-02");
        // 끝나면 다시 고칠 수 있다
        send(put(P + "/" + proj), "{\"projNm\":\"ZT 착수결재 2\",\"projType\":\"GENERAL\",\"rowVer\":" + ver() + "}", pm)
                .andExpect(status().isOk());
    }

    @Test
    void 반려_회수면_상태_그대로_다시_상신() throws Exception {
        policy("Y");
        long a1 = id(submit("ACTIVE", aprUserId, pm));
        send(post(APR + "/" + a1 + "/reject"), "{\"comment\":\"일정 근거 보완\"}", approver).andExpect(status().isOk());
        assertThat(proj()).containsEntry("status", "DRAFT").containsEntry("aprTo", null).containsEntry("aprId", null);
        long a2 = id(submit("ACTIVE", aprUserId, pm));
        send(post(APR + "/" + a2 + "/withdraw"), "{}", pm).andExpect(status().isOk());
        assertThat(proj()).containsEntry("status", "DRAFT").containsEntry("aprTo", null);
        assertThat(aprStatus(a2)).isEqualTo("WITHDRAWN");
        id(submit("ACTIVE", aprUserId, pm));
    }

    @Test
    void 종료_조건은_상신때_승인때_두번() throws Exception {
        move("ACTIVE").andExpect(status().isOk());
        policy("Y");
        // 상신 때: 열린 작업 → 칸 오류, 아무것도 안 바뀜
        submit("CLOSED", aprUserId, pm).andExpect(jsonPath("$.errors[0].code").value("PMS_OPEN_TASKS"));
        assertThat(proj()).containsEntry("aprTo", null);
        move("CLOSED").andExpect(jsonPath("$.errors[0].code").value("PMS_APPROVAL_REQUIRED"));
        finishTask();
        long apr = id(submit("CLOSED", aprUserId, pm));
        // 상신 뒤 열린 이슈가 생김 → 승인 때 다시 확인해 승인 안 됨(결재 전체 롤백)
        long issue = id(send(post("/api/pms/issues"), "{\"issueType\":\"DEFECT\",\"projId\":" + proj + ",\"title\":\"ZT 늦은 결함\"}", pm));
        send(post(APR + "/" + apr + "/approve"), "{}", approver).andExpect(jsonPath("$.errors[0].code").value("PMS_OPEN_ISSUES"));
        assertThat(aprStatus(apr)).isEqualTo("PENDING");
        assertThat(proj()).containsEntry("status", "ACTIVE").containsEntry("aprTo", "CLOSED");
        // 이슈를 닫으면 승인 → 종료 + 종료일
        accounts.exec(comp, "update pms_issue set status = 'RESOLVED', resolution = 'x', resolved_on = current_date where issue_id = ?", issue);
        accounts.exec(comp, "update pms_issue set status = 'CLOSED', close_kind = 'DONE' where issue_id = ?", issue);
        send(post(APR + "/" + apr + "/approve"), "{}", approver).andExpect(status().isOk());
        assertThat(proj()).containsEntry("status", "CLOSED").containsEntry("aprTo", null);
        assertThat(accounts.query(comp, "select closed_on is not null from pms_project where proj_id = ?", Boolean.class, proj)).isTrue();
        assertThat(aprStatus(apr)).isEqualTo("APPROVED");
    }

    // ------------------------------------------------------------------ 입력·권한

    @Test
    void 입력_오류_자기결재_옛rowVer_권한() throws Exception {
        policy("Y");
        // 결재 대상 아닌 전환(작성 중 → 종료), 결재선 없음, 결재 구분 오류, 옛 rowVer
        submit("CLOSED", aprUserId, pm).andExpect(jsonPath("$.errors[0].code").value("PMS_TRANSITION"));
        send(post(P + "/" + proj + "/approval"), "{\"to\":\"ACTIVE\",\"rowVer\":" + ver() + ",\"lines\":[]}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        send(post(P + "/" + proj + "/approval"), "{\"to\":\"ACTIVE\",\"rowVer\":" + ver() + ",\"lines\":[{\"approverId\":" + aprUserId
                + ",\"lineType\":\"BOSS\"}]}", pm).andExpect(jsonPath("$.errors[0].code").value("CODE"));
        send(post(P + "/" + proj + "/approval"), "{\"to\":\"ACTIVE\",\"rowVer\":99,\"lines\":[{\"approverId\":" + aprUserId
                + ",\"lineType\":\"APPROVE\"}]}", pm).andExpect(status().isConflict());
        // 자기 자신 결재 → 거부, 결재 시작 표시도 남지 않음(같은 트랜잭션)
        submit("ACTIVE", pmId, pm).andExpect(jsonPath("$.code").value("APR_INVALID_LINE"));
        assertThat(proj()).containsEntry("aprTo", null).containsEntry("aprId", null);
        // 조회 권한만 → 403, 저장 권한만 있고 PM 아님 → 관리 불가
        submit("ACTIVE", pmId, approver).andExpect(status().isForbidden());
        long eng = accounts.newUser(comp, "eng1", PW);
        accounts.grant(comp, eng, "ENG", "pms-project", "SEARCH", "SAVE");
        submit("ACTIVE", aprUserId, accounts.as(comp, eng, "eng1")).andExpect(jsonPath("$.code").value("PMS_NOT_EDITABLE"));
        assertThat(proj()).containsEntry("aprTo", null);
    }

    @Test
    void 직접SQL_결재중_머리정보_상태_잠금_결재시작은_착수종료만() throws Exception {
        policy("Y");
        // 결재 시작 표시는 작성 중→진행, 진행→종료만
        assertThat(catchThrowable(() -> accounts.exec(comp, "update pms_project set apr_to = 'CLOSED' where proj_id = ?", proj)))
                .hasMessageContaining("cannot request CLOSED from DRAFT");
        assertThat(catchThrowable(() -> accounts.exec(comp, "update pms_project set apr_to = 'X' where proj_id = ?", proj)))
                .hasMessageContaining("cannot request X");
        id(submit("ACTIVE", aprUserId, pm));
        assertThat(catchThrowable(() -> accounts.exec(comp, "update pms_project set proj_nm = 'x' where proj_id = ?", proj)))
                .hasMessageContaining("is in approval");
        assertThat(catchThrowable(() -> accounts.exec(comp, "update pms_project set status = 'CANCELLED' where proj_id = ?", proj)))
                .hasMessageContaining("is in approval");
        // 결재를 거치지 않고 끝내며 다른 상태로 → 거부
        assertThat(catchThrowable(() -> accounts.exec(comp,
                "update pms_project set apr_to = null, apr_id = null, status = 'CANCELLED' where proj_id = ?", proj)))
                .hasMessageContaining("is in approval");
        assertThat(proj()).containsEntry("status", "DRAFT").containsEntry("aprTo", "ACTIVE");
    }

    private long dept(Long parent, String cd, Long head) {
        return accounts.query(comp, """
                insert into sys_dept (comp_cd, dept_cd, dept_nm, parent_id, head_user_id, created_by, updated_by)
                values (hb_current_comp(), ?, ?, ?, ?, 't', 't') returning dept_id""", Long.class, cd, "ZT " + cd, parent, head);
    }

    @Test
    void 결재선_기본값은_내_부서장_본인이면_상위장_없으면_null_정책끔_관리불가면_안줌() throws Exception {
        policy("Y");
        // 소속 없음 → 기본값 없음(상신자가 고름)
        assertThat(proj()).containsEntry("aprDefaultUserId", null);
        long hq = dept(null, "HQ", aprUserId);
        long team = dept(hq, "TEAM", null);
        accounts.exec(comp, "update sys_user set dept_id = ? where user_id = ?", team, pmId);
        // 팀장 없음 → 위로 올라가 본부장
        assertThat(proj()).containsEntry("aprDefaultUserId", (int) aprUserId).containsEntry("aprDefaultLoginId", "apr1");
        // 내가 팀장 → 본인 건너뛰고 본부장
        accounts.exec(comp, "update sys_dept set head_user_id = ? where dept_id = ?", pmId, team);
        assertThat(proj()).containsEntry("aprDefaultUserId", (int) aprUserId);
        // 기본값으로 바로 상신 가능(결재선은 여전히 요청 본문으로 확정)
        long apr = id(submit("ACTIVE", ((Number) proj().get("aprDefaultUserId")).longValue(), pm));
        mvc.perform(get(APR + "/box").param("box", "TODO").with(approver)).andExpect(jsonPath("$.list[0].docType").value("PMS_PROJ_OPEN"));
        // 결재 중(관리 불가) → 안 준다. 정책 끔 → 안 준다
        assertThat(proj()).containsEntry("aprDefaultUserId", null);
        send(post(APR + "/" + apr + "/withdraw"), "{}", pm).andExpect(status().isOk());
        policy("N");
        assertThat(proj()).containsEntry("aprDefaultUserId", null);
    }

    @Test
    void A사로_B사_프로젝트_결재상신_404() throws Exception {
        policy("Y");
        String compB = accounts.newComp("PB");
        long bId = accounts.newUser(compB, "pm1", PW);
        long bApr = accounts.newUser(compB, "apr1", PW);
        accounts.grant(compB, bId, "PMS", "pms-project", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(compB, bId, "pm1");
        send(post(P + "/" + proj + "/approval"), "{\"to\":\"ACTIVE\",\"rowVer\":0,\"lines\":[{\"approverId\":" + bApr
                + ",\"lineType\":\"APPROVE\"}]}", b).andExpect(status().isNotFound());
        // A사 결재선에 B사 사용자 → 거부
        submit("ACTIVE", bApr, pm).andExpect(status().is4xxClientError());
        assertThat(proj()).containsEntry("aprTo", null).containsEntry("status", "DRAFT");
    }
}
