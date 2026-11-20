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

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 담당자 일정변경 요청 → 승인 (docs/32 4.13, docs/48 R-3) — 결재(PMS_TASK_CHG) 승인·반려·회수, 승인 = 사유 있는 계획 변경(이력 REQ,
 * 기준선 불변), 후행 밀기 미리보기(승인은 밀지 않음), 정책 N = PM 바로 처리, 중복 요청, 작업 row_ver 충돌, 입력·권한·자기결재,
 * A사로 B사, 직접 SQL(내용 불변·끝난 요청·삭제·다른 회사).
 */
@AutoConfigureMockMvc
class PmsTaskChgTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/pms/projects";
    private static final String K = "/api/pms/tasks";
    private static final String C = "/api/pms/chg-reqs";
    private static final String APR = "/api/platform/approvals";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long pmId;
    long memId;
    long otherId;
    RequestPostProcessor pm;
    RequestPostProcessor mem;
    RequestPostProcessor other;
    long proj;
    long a;
    long b;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PC");
        pmId = accounts.newUser(comp, "pm1", PW);
        memId = accounts.newUser(comp, "mem1", PW);
        otherId = accounts.newUser(comp, "oth1", PW);
        accounts.grant(comp, pmId, "PMS", "pms-project", "SEARCH", "SAVE");
        accounts.grant(comp, memId, "MEM", "pms-project", "SEARCH", "SAVE");
        accounts.grant(comp, otherId, "OTH", "pms-project", "SEARCH");
        for (long u : List.of(pmId, memId, otherId)) {
            accounts.grant(comp, u, "APRBOX" + u, "sys-approval", "SEARCH");
        }
        pm = accounts.as(comp, pmId, "pm1");
        mem = accounts.as(comp, memId, "mem1");
        other = accounts.as(comp, otherId, "oth1");
        proj = id(send(post(P), "{\"projNm\":\"일정변경\",\"projType\":\"GENERAL\"}", pm));
        a = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"설계\",\"planStart\":\"2026-03-01\",\"planEnd\":\"2026-03-10\","
                + "\"ownerLoginId\":\"mem1\"}", pm));
        b = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"시작품\",\"planStart\":\"2026-03-11\",\"planEnd\":\"2026-03-12\"}", pm));
        id(send(post(P + "/" + proj + "/deps"), "{\"predTaskId\":" + a + ",\"succTaskId\":" + b + ",\"lagDays\":0}", pm));
    }

    // ------------------------------------------------------------------ 도우미

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder r, String body) {
        return r.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions send(MockHttpServletRequestBuilder r, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(json(r, body).with(who));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private static <T> T read(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), path);
    }

    private Map<String, Object> row(long taskId) throws Exception {
        List<Map<String, Object>> rows = read(mvc.perform(get(P + "/" + proj + "/tasks").with(pm)), "$.data.tasks[?(@.taskId == " + taskId + ")]");
        return rows.get(0);
    }

    private int rowVer(long taskId) throws Exception {
        return ((Number) row(taskId).get("rowVer")).intValue();
    }

    private ResultActions request(long taskId, String start, String end, String reason, RequestPostProcessor who) throws Exception {
        return send(post(K + "/" + taskId + "/chg-reqs"), "{\"newStart\":\"" + start + "\",\"newEnd\":\"" + end + "\",\"reason\":\""
                + reason + "\",\"rowVer\":" + rowVer(taskId) + "}", who);
    }

    private Map<String, Object> detail(long reqId, RequestPostProcessor who) throws Exception {
        return read(mvc.perform(get(C + "/" + reqId).with(who)).andExpect(status().isOk()), "$.data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> req(long reqId) throws Exception {
        return (Map<String, Object>) detail(reqId, pm).get("req");
    }

    private long aprId(long reqId) {
        return accounts.query(comp, "select apr_id from pms_task_chg_req where req_id = ?", Long.class, reqId);
    }

    private void policy(String key, String value) {
        accounts.exec(comp, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, ?, ?, 't', 't')
                on conflict do nothing""", comp, key, value);
        accounts.exec(comp, "update sys_policy set policy_value = ? where policy_key = ? and plant_cd = '*'", value, key);
    }

    // ------------------------------------------------------------------ 결재

    @Test
    void 결재_승인이면_계획일_바뀌고_이력REQ_기준선_불변_후행은_미리보기만() throws Exception {
        id(send(post(P + "/" + proj + "/baselines"), "{\"reason\":\"착수 합의\"}", pm));
        // 작업 폼이 보는 정보: 정책 Y, 결재선 기본 = PM
        mvc.perform(get(K + "/" + a + "/chg-info").with(mem)).andExpect(jsonPath("$.data.approvalRequired").value(true))
                .andExpect(jsonPath("$.data.pmUserId").value(pmId)).andExpect(jsonPath("$.data.open").doesNotExist());
        long req = id(request(a, "2026-03-01", "2026-03-15", "고객 도면 변경", mem));
        long apr = aprId(req);
        assertThat(req(req)).containsEntry("status", "IN_APPROVAL").containsEntry("beforeEnd", "2026-03-10");
        mvc.perform(get(K + "/" + a + "/chg-info").with(mem)).andExpect(jsonPath("$.data.open.reqId").value(req));
        // 결재자(PM) 결재함: 문서 열기 = 프로젝트 화면 + 요청 id
        mvc.perform(get(APR + "/box").param("box", "TODO").with(pm))
                .andExpect(jsonPath("$.list[0].docType").value("PMS_TASK_CHG"))
                .andExpect(jsonPath("$.list[0].docProg").value("pms-project"))
                .andExpect(jsonPath("$.list[0].docParams.chgReq").value(String.valueOf(req)));
        // 상세 미리보기: 이 변경이면 후행 b 가 3/16 로 밀린다(승인 화면에서 보는 것), 결재자 차례
        Map<String, Object> d = detail(req, pm);
        assertThat(JsonPath.<List<Object>>read(d, "$.preview[?(@.taskId == " + b + ")].newStart")).containsExactly("2026-03-16");
        assertThat(JsonPath.<Boolean>read(d, "$.approval.myTurn")).isTrue();
        assertThat(JsonPath.<Boolean>read(d, "$.taskChanged")).isFalse();
        assertThat(JsonPath.<Boolean>read(d, "$.canDecide")).isFalse();
        // 담당자는 결재 전에 계획일을 직접 못 고친다(그대로)
        send(put(K + "/" + a), "{\"taskNm\":\"설계\",\"planStart\":\"2026-03-01\",\"planEnd\":\"2026-03-15\",\"ownerLoginId\":\"mem1\""
                + ",\"rowVer\":" + rowVer(a) + "}", mem).andExpect(jsonPath("$.code").value("PMS_NOT_EDITABLE"));

        send(post(APR + "/" + apr + "/approve"), "{\"comment\":\"승인\"}", pm).andExpect(status().isOk());
        assertThat(row(a)).containsEntry("planEnd", "2026-03-15").containsEntry("baseEnd", "2026-03-10");
        // 후행은 자동으로 밀지 않는다 — 위반 표시 + PM 의 [후행 밀기]
        assertThat(row(b)).containsEntry("planStart", "2026-03-11").containsEntry("depViolation", true);
        assertThat(req(req)).containsEntry("status", "APPROVED");
        List<Map<String, Object>> hist = read(mvc.perform(get(K + "/" + a + "/plan-changes").with(pm)), "$.data");
        assertThat(hist).hasSize(1);
        assertThat(hist.get(0)).containsEntry("source", "REQ").containsEntry("reason", "고객 도면 변경")
                .containsEntry("beforeEnd", "2026-03-10").containsEntry("afterEnd", "2026-03-15");
        // 끝난 요청은 미리보기 없음, 목록에 남는다
        assertThat(JsonPath.<List<Object>>read(detail(req, mem), "$.preview")).isEmpty();
        mvc.perform(get(P + "/" + proj + "/chg-reqs").with(other)).andExpect(jsonPath("$.data[0].reqId").value(req))
                .andExpect(jsonPath("$.data[0].wbsNo").value("1"));
    }

    @Test
    void 반려_회수_중복요청_거부_끝나면_다시_요청() throws Exception {
        long r1 = id(request(a, "2026-03-02", "2026-03-11", "자재 지연", mem));
        // 같은 작업에 열린 요청이 있으면 409(정책과 무관하게 하나)
        request(a, "2026-03-03", "2026-03-12", "또", mem).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PMS_CHG_PENDING"));
        send(post(APR + "/" + aprId(r1) + "/reject"), "{\"comment\":\"근거 부족\"}", pm).andExpect(status().isOk());
        assertThat(req(r1)).containsEntry("status", "REJECTED");
        assertThat(row(a)).containsEntry("planStart", "2026-03-01").containsEntry("planEnd", "2026-03-10");
        // 반려 뒤 다시 요청 → 요청자가 결재함에서 회수
        long r2 = id(request(a, "2026-03-02", "2026-03-11", "자재 지연(보완)", mem));
        assertThat(JsonPath.<Boolean>read(detail(r2, mem), "$.approval.canWithdraw")).isTrue();
        send(post(APR + "/" + aprId(r2) + "/withdraw"), "{}", mem).andExpect(status().isOk());
        assertThat(req(r2)).containsEntry("status", "WITHDRAWN");
        // 결재 중 요청은 화면 회수(바로 처리용)로 끝내지 않는다
        long r3 = id(request(a, "2026-03-02", "2026-03-11", "세 번째", mem));
        send(post(C + "/" + r3 + "/withdraw"), "{\"rowVer\":0}", mem).andExpect(jsonPath("$.code").value("PMS_CHG_STATE"));
        assertThat(row(a)).containsEntry("planEnd", "2026-03-10");
        assertThat(accounts.query(comp, "select count(*) from pms_task_chg_req where task_id = ?", Long.class, a)).isEqualTo(3L);
    }

    @Test
    void 정책N_PM이_바로_승인반려_담당자_남은_못함_정책_바꿔도_요청때_방식() throws Exception {
        long viaApr = id(request(a, "2026-03-02", "2026-03-10", "결재로 시작", mem));
        policy("PMS_TASK_CHG_APPROVAL", "N");
        // 결재로 만든 요청은 정책을 바꿔도 결재로만
        send(post(C + "/" + viaApr + "/decide"), "{\"approve\":true,\"rowVer\":0}", pm).andExpect(jsonPath("$.code").value("PMS_CHG_STATE"));
        send(post(APR + "/" + aprId(viaApr) + "/withdraw"), "{}", mem).andExpect(status().isOk());

        long r1 = id(request(a, "2026-03-05", "2026-03-12", "설비 점검", mem));
        Map<String, Object> d = detail(r1, pm);
        assertThat(JsonPath.<String>read(d, "$.req.status")).isEqualTo("REQUESTED");
        assertThat(JsonPath.<Object>read(d, "$.approval.aprId")).isNull();
        assertThat(JsonPath.<Boolean>read(d, "$.canDecide")).isTrue();
        assertThat(JsonPath.<Boolean>read(detail(r1, mem), "$.canDecide")).isFalse();
        assertThat(JsonPath.<Boolean>read(detail(r1, mem), "$.canWithdraw")).isTrue();
        // 요청자·조회만 가진 사람은 처리 못 함, 옛 rowVer 는 409
        send(post(C + "/" + r1 + "/decide"), "{\"approve\":true,\"rowVer\":0}", mem).andExpect(jsonPath("$.code").value("PMS_NOT_EDITABLE"));
        send(post(C + "/" + r1 + "/decide"), "{\"approve\":true,\"rowVer\":0}", other).andExpect(status().isForbidden());
        send(post(C + "/" + r1 + "/decide"), "{\"approve\":true,\"rowVer\":9}", pm).andExpect(status().isConflict());
        send(post(C + "/" + r1 + "/decide"), "{\"approve\":true,\"note\":\"OK\",\"rowVer\":0}", pm).andExpect(status().isOk());
        assertThat(row(a)).containsEntry("planStart", "2026-03-05").containsEntry("planEnd", "2026-03-12");
        assertThat(req(r1)).containsEntry("status", "APPROVED").containsEntry("decideNote", "OK").containsEntry("decidedBy", "pm1");
        // 기준선 없으면 이력은 남기지 않는다(폼 수정과 같은 규칙) — 요청 표가 기록
        assertThat(accounts.query(comp, "select count(*) from pms_task_plan_change where task_id = ?", Long.class, a)).isZero();
        // 두 번 처리 → 409, 반려·요청자 회수
        send(post(C + "/" + r1 + "/decide"), "{\"approve\":false,\"rowVer\":1}", pm).andExpect(jsonPath("$.code").value("PMS_CHG_STATE"));
        long r2 = id(request(a, "2026-03-06", "2026-03-12", "다시", mem));
        send(post(C + "/" + r2 + "/decide"), "{\"approve\":false,\"note\":\"이번 주는 불가\",\"rowVer\":0}", pm).andExpect(status().isOk());
        assertThat(req(r2)).containsEntry("status", "REJECTED");
        long r3 = id(request(a, "2026-03-06", "2026-03-12", "세 번째", mem));
        send(post(C + "/" + r3 + "/withdraw"), "{\"rowVer\":0}", pm).andExpect(jsonPath("$.code").value("PMS_NOT_EDITABLE"));
        send(post(C + "/" + r3 + "/withdraw"), "{\"rowVer\":0}", mem).andExpect(status().isOk());
        assertThat(req(r3)).containsEntry("status", "WITHDRAWN");
        assertThat(row(a)).containsEntry("planStart", "2026-03-05");
    }

    @Test
    void 요청뒤_작업이_바뀌면_승인_409_결재는_그대로_남음_옛_rowVer_요청도_409() throws Exception {
        int ver = rowVer(a);
        long req = id(request(a, "2026-03-01", "2026-03-14", "검토 추가", mem));
        // 담당자가 실적(진행)을 저장 → 작업 row_ver 가 바뀜
        send(put(K + "/" + a), "{\"taskNm\":\"설계\",\"planStart\":\"2026-03-01\",\"planEnd\":\"2026-03-10\",\"ownerLoginId\":\"mem1\""
                + ",\"actStart\":\"2026-03-01\",\"rowVer\":" + ver + "}", mem).andExpect(status().isOk());
        assertThat(JsonPath.<Boolean>read(detail(req, pm), "$.taskChanged")).isTrue();
        send(post(APR + "/" + aprId(req) + "/approve"), "{}", pm).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        // 결재 처리 전체가 롤백 — 결재·요청 모두 처리 전 그대로, 계획일 그대로 → 결재자는 반려
        assertThat(accounts.query(comp, "select status from apr_request where apr_id = ?", String.class, aprId(req))).isEqualTo("PENDING");
        assertThat(req(req)).containsEntry("status", "IN_APPROVAL");
        assertThat(row(a)).containsEntry("planEnd", "2026-03-10");
        send(post(APR + "/" + aprId(req) + "/reject"), "{\"comment\":\"다시 요청\"}", pm).andExpect(status().isOk());
        // 화면이 본 rowVer 가 옛 것이면 요청 자체가 409
        send(post(K + "/" + a + "/chg-reqs"), "{\"newStart\":\"2026-03-01\",\"newEnd\":\"2026-03-14\",\"reason\":\"x\",\"rowVer\":" + ver + "}",
                mem).andExpect(status().isConflict());
    }

    @Test
    void 입력검사_관리자는_직접_담당자_아니면_못함_요약_자기결재_기본금지() throws Exception {
        request(a, "2026-03-01", "2026-03-10", "같음", mem).andExpect(jsonPath("$.errors[0].code").value("PMS_CHG_SAME"));
        request(a, "2026-03-10", "2026-03-01", "거꾸로", mem).andExpect(jsonPath("$.errors[0].code").value("PMS_END_BEFORE_START"));
        request(a, "2026-03-01", "2026-03-12", " ", mem).andExpect(jsonPath("$.errors[0].field").value("reason"));
        request(a, "3000-01-01", "3000-01-02", "범위", mem).andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        // 담당자 아님 → 403, PM(관리하는 사람)은 폼에서 직접 → 400
        request(b, "2026-03-12", "2026-03-13", "남의 작업", mem).andExpect(jsonPath("$.code").value("PMS_NOT_EDITABLE"));
        send(put(K + "/" + b), "{\"taskNm\":\"시작품\",\"planStart\":\"2026-03-11\",\"planEnd\":\"2026-03-12\",\"ownerLoginId\":\"pm1\""
                + ",\"rowVer\":" + rowVer(b) + "}", pm).andExpect(status().isOk());
        request(b, "2026-03-12", "2026-03-13", "PM 본인", pm).andExpect(jsonPath("$.errors[0].code").value("PMS_CHG_DIRECT"));
        // 요약 작업 담당자 — 요약 날짜는 자식에서
        long ph = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"단계\",\"planStart\":\"2026-04-01\",\"ownerLoginId\":\"mem1\"}", pm));
        id(send(post(P + "/" + proj + "/tasks"), "{\"parentId\":" + ph + ",\"taskNm\":\"하위\",\"planStart\":\"2026-04-01\"}", pm));
        request(ph, "2026-04-02", "2026-04-03", "요약", mem).andExpect(jsonPath("$.errors[0].code").value("PMS_SUMMARY_DERIVED"));
        // 결재자로 자기 자신 → 결재선 거부(정책 APR_SELF_APPROVE_ALLOWED 기본 N), 요청도 남지 않음(같은 트랜잭션)
        send(post(K + "/" + a + "/chg-reqs"), "{\"newStart\":\"2026-03-01\",\"newEnd\":\"2026-03-12\",\"reason\":\"자기\",\"rowVer\":"
                + rowVer(a) + ",\"approverId\":" + memId + "}", mem).andExpect(jsonPath("$.code").value("APR_INVALID_LINE"));
        assertThat(accounts.query(comp, "select count(*) from pms_task_chg_req", Long.class)).isZero();
        // 조회만 가진 사람은 요청 API 자체가 403
        request(a, "2026-03-01", "2026-03-12", "조회만", other).andExpect(status().isForbidden());
        // 닫힌 프로젝트
        send(post(P + "/" + proj + "/status"), "{\"status\":\"CANCELLED\",\"reason\":\"수주 취소\",\"rowVer\":0}", pm).andExpect(status().isOk());
        request(a, "2026-03-01", "2026-03-12", "닫힘", mem).andExpect(jsonPath("$.code").value("PMS_PROJECT_CLOSED"));
    }

    @Test
    void 결재중_프로젝트가_닫히면_승인_409_반려는_됨() throws Exception {
        long req = id(request(a, "2026-03-01", "2026-03-12", "막판 변경", mem));
        send(post(P + "/" + proj + "/status"), "{\"status\":\"CANCELLED\",\"reason\":\"수주 취소\",\"rowVer\":0}", pm).andExpect(status().isOk());
        send(post(APR + "/" + aprId(req) + "/approve"), "{}", pm).andExpect(jsonPath("$.code").value("PMS_PROJECT_CLOSED"));
        send(post(APR + "/" + aprId(req) + "/reject"), "{\"comment\":\"반려\"}", pm).andExpect(status().isOk());
        assertThat(req(req)).containsEntry("status", "REJECTED");
    }

    @Test
    void A사로_B사_요청은_안_보이고_못_건드림_직접SQL_불변() throws Exception {
        long req = id(request(a, "2026-03-01", "2026-03-12", "A사 요청", mem));
        String compB = accounts.newComp("PD");
        long bId = accounts.newUser(compB, "pm1", PW);
        accounts.grant(compB, bId, "PMS", "pms-project", "SEARCH", "SAVE", "APPROVE");
        RequestPostProcessor bpm = accounts.as(compB, bId, "pm1");
        mvc.perform(get(C + "/" + req).with(bpm)).andExpect(status().isNotFound());
        mvc.perform(get(P + "/" + proj + "/chg-reqs").with(bpm)).andExpect(status().isNotFound());
        mvc.perform(get(K + "/" + a + "/chg-info").with(bpm)).andExpect(status().isNotFound());
        send(post(C + "/" + req + "/decide"), "{\"approve\":true,\"rowVer\":0}", bpm).andExpect(status().isNotFound());
        send(post(C + "/" + req + "/withdraw"), "{\"rowVer\":0}", bpm).andExpect(status().isNotFound());
        send(post(K + "/" + a + "/chg-reqs"), "{\"newStart\":\"2026-03-01\",\"newEnd\":\"2026-03-12\",\"reason\":\"x\",\"rowVer\":0}", bpm)
                .andExpect(status().isNotFound());
        send(post(APR + "/" + aprId(req) + "/approve"), "{}", bpm).andExpect(status().isNotFound());
        assertThat(req(req)).containsEntry("status", "IN_APPROVAL");
        assertThat(accounts.query(compB, "select count(*) from pms_task_chg_req", Long.class)).isZero();
        // B사 범위에서 A사 작업으로 요청 행 넣기 → 트리거 거부
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(compB, """
                insert into pms_task_chg_req (comp_cd, proj_id, task_id, requester_id, before_start, before_end, new_start, new_end,
                                              reason, task_row_ver, status, created_by, updated_by)
                values (?, ?, ?, ?, date '2026-03-01', date '2026-03-10', date '2026-03-01', date '2026-03-12', 'x', 0, 'REQUESTED', 't', 't')""",
                compB, proj, a, bId)))).contains("fk_pms_task_chg_req_proj_comp");
        // 직접 SQL: 내용 불변, 지우기 금지, 결재 중 → 바로 처리 상태로 못 바꿈, 끝난 요청 불변
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update pms_task_chg_req set new_end = date '2026-03-20' where req_id = ?", req)))).contains("ck_pms_task_chg_req_fixed");
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from pms_task_chg_req where req_id = ?", req))).isNotNull();
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update pms_task_chg_req set status = 'REQUESTED' where req_id = ?", req)))).contains("ck_pms_task_chg_req_transition");
        send(post(APR + "/" + aprId(req) + "/reject"), "{\"comment\":\"반려\"}", pm).andExpect(status().isOk());
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update pms_task_chg_req set decide_note = 'x' where req_id = ?", req)))).contains("ck_pms_task_chg_req_final");
    }
}
