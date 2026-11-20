package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
 * PMS 3단계 산출물·게이트 심사 (docs/32 PM3) — 템플릿 산출물 칸 복사, 제출(파일)·승인·반려·면제·새 제출, 단계 준비율,
 * 게이트 상신 검사(필수 산출물·점검 결과·NG·조건), 결재(PMS_GATE) 승인·반려·회수, 조건부 통과 → 이슈, 결재 없는 결정(정책 N),
 * 게이트 강제(WARN·BLOCK), 게이트 완료는 심사로만, 행 권한, 종료 검사, A사로 B사, 직접 SQL(불변·파일 삭제·다른 회사), 동시 심사 만들기.
 */
@AutoConfigureMockMvc
class PmsGateTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/pms/projects";
    private static final String D = "/api/pms/deliverables";
    private static final String R = "/api/pms/gate-reviews";
    private static final String APR = "/api/platform/approvals";
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long pmId;
    long qaId;
    long memberId;
    RequestPostProcessor pm;
    RequestPostProcessor qa;
    RequestPostProcessor member;
    LocalDate today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PG");
        pmId = accounts.newUser(comp, "pm1", PW);
        qaId = accounts.newUser(comp, "qa1", PW);
        memberId = accounts.newUser(comp, "mem1", PW);
        for (String prog : List.of("pms-project", "pms-template")) {
            accounts.grant(comp, pmId, "PMS", prog, "SEARCH", "SAVE");
        }
        accounts.grant(comp, pmId, "PMS", "pms-gate", "SEARCH", "SAVE");
        accounts.grant(comp, pmId, "PMS", "pms-deliverable", "SEARCH", "SAVE", "EXCEL");
        // 품질(qa1) = 산출물 승인·결재자, 구성원 = 담당자 제출만
        accounts.grant(comp, qaId, "QA", "pms-deliverable", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, qaId, "QA", "pms-gate", "SEARCH", "APPROVE");
        accounts.grant(comp, memberId, "MEM", "pms-deliverable", "SEARCH", "SAVE");
        accounts.grant(comp, memberId, "MEM", "pms-project", "SEARCH", "SAVE");
        pm = accounts.as(comp, pmId, "pm1");
        qa = accounts.as(comp, qaId, "qa1");
        member = accounts.as(comp, memberId, "mem1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY, LocalDate.class);
    }

    // ------------------------------------------------------------------ 도우미

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

    /** 기본 APQP 템플릿(산출물 칸 12 · 게이트 점검 문구 3) 으로 프로젝트 — SOP 는 오늘 + 400 */
    private long apqpProject() throws Exception {
        id(send(post("/api/pms/templates/default-apqp"), "{}", pm));
        return id(send(post(P), "{\"projNm\":\"신규 브래킷\",\"projType\":\"APQP\",\"tmplCd\":\"APQP-STD\",\"sopDate\":\""
                + today.plusDays(400) + "\"}", pm));
    }

    private Map<String, Object> deliv(long proj, String nm) throws Exception {
        List<Map<String, Object>> rows = read(mvc.perform(get(P + "/" + proj + "/deliverables").with(pm)),
                "$.data.rows[?(@.delivNm == '" + nm + "')]");
        return rows.get(0);
    }

    private long taskId(long proj, String nm) throws Exception {
        List<Number> ids = read(mvc.perform(get(P + "/" + proj + "/tasks").with(pm)), "$.data.tasks[?(@.taskNm == '" + nm + "')].taskId");
        return ids.get(0).longValue();
    }

    private Map<String, Object> task(long proj, String nm) throws Exception {
        List<Map<String, Object>> rows = read(mvc.perform(get(P + "/" + proj + "/tasks").with(pm)), "$.data.tasks[?(@.taskNm == '" + nm + "')]");
        return rows.get(0);
    }

    private ResultActions submit(RequestPostProcessor who, long delivId, int rowVer, String name) throws Exception {
        return mvc.perform(multipart(D + "/" + delivId + "/submit").file(new MockMultipartFile("file", name, "application/pdf", PDF))
                .param("rowVer", String.valueOf(rowVer)).with(who).with(csrf()));
    }

    private ResultActions decide(RequestPostProcessor who, long delivId, String action, String note, int rowVer) throws Exception {
        return send(post(D + "/" + delivId + "/decide"), "{\"action\":\"" + action + "\",\"note\":" + (note == null ? "null" : "\"" + note + "\"")
                + ",\"rowVer\":" + rowVer + "}", who);
    }

    /** 이름의 산출물을 제출 → 승인 */
    private void approveDeliv(long proj, String nm) throws Exception {
        Map<String, Object> d = deliv(proj, nm);
        long id = ((Number) d.get("delivId")).longValue();
        submit(pm, id, ((Number) d.get("rowVer")).intValue(), nm + ".pdf").andExpect(status().isOk());
        decide(qa, id, "APPROVE", null, ((Number) deliv(proj, nm).get("rowVer")).intValue()).andExpect(status().isOk());
    }

    private Map<String, Object> review(long reviewId, RequestPostProcessor who) throws Exception {
        return read(mvc.perform(get(R + "/" + reviewId).with(who)), "$.data");
    }

    private int reviewVer(long reviewId) throws Exception {
        return ((Number) ((Map<?, ?>) review(reviewId, pm).get("review")).get("rowVer")).intValue();
    }

    /** 점검 3개 결과 + 결정 + (조건) 저장 */
    private ResultActions saveReview(long reviewId, String decision, String result, String conds) throws Exception {
        String checks = "[{\"checkNm\":\"a\",\"result\":\"OK\"},{\"checkNm\":\"b\",\"result\":\"" + result + "\"},{\"checkNm\":\"c\",\"result\":\"NA\"}]";
        return send(put(R + "/" + reviewId), "{\"reviewDate\":\"" + today + "\",\"decision\":" + (decision == null ? "null" : "\"" + decision + "\"")
                + ",\"summary\":\"심사 요약\",\"checks\":" + checks + ",\"conds\":" + conds + ",\"rowVer\":" + reviewVer(reviewId) + "}", pm);
    }

    private ResultActions submitReview(long reviewId) throws Exception {
        return send(post(R + "/" + reviewId + "/submit"), "{\"lines\":[{\"userId\":" + qaId + ",\"lineType\":\"APPROVE\"}],\"rowVer\":"
                + reviewVer(reviewId) + "}", pm);
    }

    private long aprId(long reviewId) {
        return accounts.query(comp, "select apr_id from pms_gate_review where review_id = ?", Long.class, reviewId);
    }

    private void policy(String key, String value) {
        accounts.exec(comp, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, ?, ?, 't', 't')
                on conflict do nothing""", comp, key, value);
        accounts.exec(comp, "update sys_policy set policy_value = ? where policy_key = ? and plant_cd = '*'", value, key);
    }

    // ------------------------------------------------------------------ 정상 흐름

    @Test
    void 템플릿_산출물_복사_제출_승인_면제와_게이트_결재_통과() throws Exception {
        long proj = apqpProject();
        ResultActions v = mvc.perform(get(P + "/" + proj + "/deliverables").with(pm));
        v.andExpect(jsonPath("$.data.rows.length()").value(12))
                .andExpect(jsonPath("$.data.rows[0].delivNm").value("고객 요구사항 검토서"))
                .andExpect(jsonPath("$.data.rows[0].phaseWbs").value("1"))
                .andExpect(jsonPath("$.data.rows[0].status").value("NONE"))
                .andExpect(jsonPath("$.data.phases.length()").value(5))
                .andExpect(jsonPath("$.data.phases[0].reqCnt").value(2))
                .andExpect(jsonPath("$.data.phases[0].pct").value(0))
                .andExpect(jsonPath("$.data.canManage").value(true))
                .andExpect(jsonPath("$.data.canApprove").value(false));
        // 기한은 작업 계획 종료일(저장 안 함)
        Map<String, Object> d1 = deliv(proj, "고객 요구사항 검토서");
        assertThat(d1.get("due")).isEqualTo(d1.get("taskPlanEnd"));
        long d1Id = ((Number) d1.get("delivId")).longValue();
        long fileId = id(submit(pm, d1Id, 0, "요구사항.pdf"));
        Map<String, Object> s1 = deliv(proj, "고객 요구사항 검토서");
        assertThat(s1.get("status")).isEqualTo("SUBMITTED");
        assertThat(s1.get("fileName")).isEqualTo("요구사항.pdf");
        assertThat(((Number) s1.get("refId")).longValue()).isEqualTo(fileId);
        decide(qa, d1Id, "APPROVE", null, 1).andExpect(status().isOk());
        // 타당성 검토서는 면제(사유 필수)
        long d2Id = ((Number) deliv(proj, "타당성 검토서").get("delivId")).longValue();
        decide(qa, d2Id, "WAIVE", null, 0).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        decide(qa, d2Id, "WAIVE", "기존 라인 그대로 — 검토 생략", 0).andExpect(status().isOk());
        mvc.perform(get(P + "/" + proj + "/deliverables").with(pm)).andExpect(jsonPath("$.data.phases[0].pct").value(100));
        mvc.perform(get(D + "/" + d1Id + "/history").with(pm))
                .andExpect(jsonPath("$.data[*].action").value(org.hamcrest.Matchers.contains("APPROVE", "SUBMIT")))
                .andExpect(jsonPath("$.data[1].fileName").value("요구사항.pdf"));

        // 게이트 G1: 점검 문구 3개 복사, 준비 2/2
        long g1 = taskId(proj, "G1 개발 착수 심사");
        mvc.perform(get(P + "/" + proj + "/gates").with(pm))
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[0].reqCnt").value(2)).andExpect(jsonPath("$.data[0].readyCnt").value(2));
        long rv = id(send(post("/api/pms/gates/" + g1 + "/reviews"), "{}", pm));
        Map<String, Object> det = review(rv, pm);
        assertThat((List<?>) det.get("checks")).hasSize(3);
        assertThat((List<?>) det.get("scope")).hasSize(2);
        assertThat(det.get("canEdit")).isEqualTo(true);
        assertThat(det.get("approvalRequired")).isEqualTo(true);
        saveReview(rv, "GO", "OK", "[]").andExpect(status().isOk());
        long aprId = id(submitReview(rv));
        assertThat(aprId).isPositive();
        Map<String, Object> inApr = review(rv, qa);
        assertThat(((Map<?, ?>) inApr.get("review")).get("status")).isEqualTo("IN_APPROVAL");
        assertThat(((Map<?, ?>) inApr.get("approval")).get("myTurn")).isEqualTo(true);
        // 결재 중에는 고칠 수 없다
        saveReview(rv, "GO", "OK", "[]").andExpect(jsonPath("$.code").value("PMS_GATE_STATE"));
        send(post(APR + "/" + aprId + "/approve"), "{\"comment\":\"통과\"}", qa).andExpect(status().isOk());
        Map<String, Object> done = review(rv, pm);
        assertThat(((Map<?, ?>) done.get("review")).get("status")).isEqualTo("DECIDED");
        assertThat(((Map<?, ?>) done.get("review")).get("readyCnt")).isEqualTo(2);
        Map<String, Object> gate = task(proj, "G1 개발 착수 심사");
        assertThat(gate.get("status")).isEqualTo("DONE");
        assertThat(gate.get("actEnd")).isEqualTo(today.toString());
        // 통과한 게이트는 다시 심사하지 않는다
        send(post("/api/pms/gates/" + g1 + "/reviews"), "{}", pm).andExpect(jsonPath("$.code").value("PMS_GATE_STATE"));
        // 엑셀 = 같은 목록
        MvcResult x = mvc.perform(post(P + "/" + proj + "/deliverables/export").with(csrf()).with(pm)).andExpect(status().isOk()).andReturn();
        assertThat(x.getResponse().getContentType()).contains("spreadsheetml");
    }

    @Test
    void 상신_검사_반려_회수_되돌림과_조건부_통과_이슈() throws Exception {
        long proj = apqpProject();
        long g1 = taskId(proj, "G1 개발 착수 심사");
        long rv = id(send(post("/api/pms/gates/" + g1 + "/reviews"), "{}", pm));
        // 결정·점검 결과 없음
        send(post(R + "/" + rv + "/submit"), "{\"lines\":[{\"userId\":" + qaId + "}],\"rowVer\":" + reviewVer(rv) + "}", pm)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'decision')].code").value(hasItem("REQUIRED")))
                .andExpect(jsonPath("$.errors[?(@.field == 'result')].rowKey").value(hasItem("c0")));
        // 미래 심사일·없는 결정 코드
        send(put(R + "/" + rv), "{\"reviewDate\":\"" + today.plusDays(1) + "\",\"decision\":\"MAYBE\",\"rowVer\":" + reviewVer(rv) + "}", pm)
                .andExpect(jsonPath("$.errors.length()").value(2));
        // GO 인데 NG / 조건부인데 조건 없음 / 필수 산출물 미승인
        saveReview(rv, "GO", "NG", "[]").andExpect(status().isOk());
        submitReview(rv).andExpect(jsonPath("$.errors[0].code").value("PMS_GO_WITH_NG"));
        saveReview(rv, "CONDITIONAL", "NG", "[]").andExpect(status().isOk());
        submitReview(rv).andExpect(jsonPath("$.errors[0].code").value("PMS_COND_REQUIRED"));
        saveReview(rv, "GO", "OK", "[]").andExpect(status().isOk());
        submitReview(rv).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PMS_GATE_NOT_READY"));
        // 불합격(NO_GO)은 준비와 무관하게 상신 → 결정돼도 게이트 작업은 그대로, 새 심사 가능
        saveReview(rv, "NO_GO", "NG", "[]").andExpect(status().isOk());
        send(post(APR + "/" + id(submitReview(rv)) + "/approve"), "{}", qa).andExpect(status().isOk());
        assertThat(task(proj, "G1 개발 착수 심사").get("status")).isEqualTo("NOT_STARTED");
        long rv2 = id(send(post("/api/pms/gates/" + g1 + "/reviews"), "{}", pm));
        assertThat(((Map<?, ?>) review(rv2, pm).get("review")).get("reviewNo")).isEqualTo(2);

        approveDeliv(proj, "고객 요구사항 검토서");
        approveDeliv(proj, "타당성 검토서");
        // 조건부 + 조건 2개 → 반려하면 작성 중으로, 회수도 작성 중으로
        String conds = "[{\"title\":\"금형 견적 재확인\",\"ownerLoginId\":\"MEM1\",\"dueDate\":\"" + today.plusDays(14)
                + "\"},{\"title\":\"원가 재산정\"}]";
        saveReview(rv2, "CONDITIONAL", "NG", conds).andExpect(status().isOk());
        long a1 = id(submitReview(rv2));
        send(post(APR + "/" + a1 + "/reject"), "{\"comment\":\"조건 기한 보완\"}", qa).andExpect(status().isOk());
        assertThat(((Map<?, ?>) review(rv2, pm).get("review")).get("status")).isEqualTo("DRAFT");
        long a2 = id(submitReview(rv2));
        send(post(APR + "/" + a2 + "/withdraw"), "{}", pm).andExpect(status().isOk());
        assertThat(((Map<?, ?>) review(rv2, pm).get("review")).get("status")).isEqualTo("DRAFT");
        long a3 = id(submitReview(rv2));
        send(post(APR + "/" + a3 + "/approve"), "{}", qa).andExpect(status().isOk());
        Map<String, Object> done = review(rv2, pm);
        List<Map<String, Object>> cs = (List<Map<String, Object>>) done.get("conds");
        assertThat(cs).hasSize(2).allSatisfy(c -> assertThat((String) c.get("issueNo")).startsWith("IS-"));
        assertThat(accounts.query(comp, "select count(*) from pms_issue where gate_review_id = ? and source = 'GATE' and status = 'OPEN'",
                Integer.class, rv2)).isEqualTo(2);
        assertThat(accounts.query(comp, "select owner_user_id from pms_issue where title = '금형 견적 재확인'", Long.class)).isEqualTo(memberId);
        assertThat(task(proj, "G1 개발 착수 심사").get("status")).isEqualTo("DONE");
        // 열린 이슈·미결 필수 산출물이 있으면 프로젝트를 닫을 수 없다(작업은 모두 취소해 둔다)
        accounts.exec(comp, "update pms_task set status = 'CANCELLED' where proj_id = ? and status <> 'DONE'", proj);
        int ver = read(mvc.perform(get(P + "/" + proj).with(pm)), "$.data.rowVer");
        send(post(P + "/" + proj + "/status"), "{\"status\":\"ACTIVE\",\"rowVer\":" + ver + "}", pm).andExpect(status().isOk());
        send(post(P + "/" + proj + "/status"), "{\"status\":\"CLOSED\",\"rowVer\":" + (ver + 1) + "}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_OPEN_DELIVERABLES"));
        accounts.exec(comp, "update pms_deliverable set status = 'WAIVED', waive_reason = '시험', decided_at = now() where proj_id = ? and status = 'NONE'", proj);
        send(post(P + "/" + proj + "/status"), "{\"status\":\"CLOSED\",\"rowVer\":" + (ver + 1) + "}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_OPEN_ISSUES"));
    }

    @Test
    void 결재_없는_결정은_APPROVE_권한으로만_그리고_결정_때_준비를_다시_본다() throws Exception {
        policy("PMS_GATE_APPROVAL", "N");
        long proj = apqpProject();
        approveDeliv(proj, "고객 요구사항 검토서");
        approveDeliv(proj, "타당성 검토서");
        long g1 = taskId(proj, "G1 개발 착수 심사");
        long rv = id(send(post("/api/pms/gates/" + g1 + "/reviews"), "{}", pm));
        saveReview(rv, "GO", "OK", "[]").andExpect(status().isOk());
        assertThat(review(rv, pm).get("canDecideDirect")).isEqualTo(false);
        send(post(R + "/" + rv + "/submit"), "{\"rowVer\":" + reviewVer(rv) + "}", pm).andExpect(status().isForbidden());
        accounts.grant(comp, pmId, "PMS", "pms-gate", "APPROVE");
        assertThat(id(send(post(R + "/" + rv + "/submit"), "{\"rowVer\":" + reviewVer(rv) + "}", pm))).isZero();
        assertThat(task(proj, "G1 개발 착수 심사").get("status")).isEqualTo("DONE");
    }

    @Test
    void 결재_중에_산출물이_새_제출로_바뀌면_승인이_거부된다() throws Exception {
        long proj = apqpProject();
        approveDeliv(proj, "고객 요구사항 검토서");
        approveDeliv(proj, "타당성 검토서");
        long rv = id(send(post("/api/pms/gates/" + taskId(proj, "G1 개발 착수 심사") + "/reviews"), "{}", pm));
        saveReview(rv, "GO", "OK", "[]").andExpect(status().isOk());
        long apr = id(submitReview(rv));
        // 승인본을 새 판으로 다시 제출 → 제출 중(승인 아님)
        Map<String, Object> d = deliv(proj, "타당성 검토서");
        submit(pm, ((Number) d.get("delivId")).longValue(), ((Number) d.get("rowVer")).intValue(), "타당성 2판.pdf").andExpect(status().isOk());
        send(post(APR + "/" + apr + "/approve"), "{}", qa).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PMS_GATE_NOT_READY"));
        assertThat(((Map<?, ?>) review(rv, pm).get("review")).get("status")).isEqualTo("IN_APPROVAL");
        assertThat(task(proj, "G1 개발 착수 심사").get("status")).isEqualTo("NOT_STARTED");
    }

    // ------------------------------------------------------------------ 게이트 강제·게이트 작업

    @Test
    void 게이트_통과_전_착수는_WARN_이면_경고와_표시_BLOCK_이면_거부_게이트_완료는_심사로만() throws Exception {
        long proj = apqpProject();
        Map<String, Object> t = task(proj, "설계 FMEA");
        String body = "{\"taskNm\":\"설계 FMEA\",\"taskType\":\"TASK\",\"planStart\":\"" + t.get("planStart") + "\",\"planEnd\":\"" + t.get("planEnd")
                + "\",\"actStart\":\"" + today + "\",\"progressPct\":10,\"rowVer\":" + t.get("rowVer") + "}";
        policy("PMS_GATE_ENFORCE", "BLOCK");
        send(put("/api/pms/tasks/" + t.get("taskId")), body, pm).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PMS_GATE_NOT_PASSED"));
        // 같은 요청으로 GATE 로 바꾸며 시작해도 게이트 단계 규칙 적용(QA 2026-11-02 P-a — 제외는 지금·새 유형 둘 다 게이트일 때만)
        send(put("/api/pms/tasks/" + t.get("taskId")), body.replace("\"taskType\":\"TASK\"", "\"taskType\":\"GATE\"")
                .replace("\"planEnd\":\"" + t.get("planEnd"), "\"planEnd\":\"" + t.get("planStart")), pm)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PMS_GATE_NOT_PASSED"));
        assertThat(task(proj, "설계 FMEA").get("taskType")).isEqualTo("TASK");
        policy("PMS_GATE_ENFORCE", "WARN");
        send(put("/api/pms/tasks/" + t.get("taskId")), body, pm).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("G1 개발 착수 심사"));
        assertThat(task(proj, "설계 FMEA").get("gateWarn")).isEqualTo("G1 개발 착수 심사");
        // 앞 단계 작업은 게이트와 무관
        Map<String, Object> t0 = task(proj, "고객 요구사항·CSR 검토");
        send(put("/api/pms/tasks/" + t0.get("taskId")), "{\"taskNm\":\"고객 요구사항·CSR 검토\",\"planStart\":\"" + t0.get("planStart")
                + "\",\"planEnd\":\"" + t0.get("planEnd") + "\",\"actStart\":\"" + today + "\",\"rowVer\":" + t0.get("rowVer") + "}", pm)
                .andExpect(jsonPath("$.data.length()").value(0));
        // 게이트를 폼으로 완료 → 거부
        Map<String, Object> g = task(proj, "G1 개발 착수 심사");
        send(put("/api/pms/tasks/" + g.get("taskId")), "{\"taskNm\":\"G1\",\"taskType\":\"GATE\",\"planStart\":\"" + g.get("planStart")
                + "\",\"actStart\":\"" + today + "\",\"actEnd\":\"" + today + "\",\"rowVer\":" + g.get("rowVer") + "}", pm)
                .andExpect(jsonPath("$.code").value("PMS_GATE_BY_REVIEW"));
        // 심사가 있는 게이트는 다른 종류로 못 바꾸고, 지울 수 없다
        id(send(post("/api/pms/gates/" + g.get("taskId") + "/reviews"), "{}", pm));
        send(put("/api/pms/tasks/" + g.get("taskId")), "{\"taskNm\":\"G1\",\"taskType\":\"MILESTONE\",\"planStart\":\"" + g.get("planStart")
                + "\",\"rowVer\":" + g.get("rowVer") + "}", pm).andExpect(jsonPath("$.errors[0].code").value("PMS_HAS_REVIEWS"));
        mvc.perform(delete("/api/pms/tasks/" + g.get("taskId")).with(csrf()).with(pm)).andExpect(jsonPath("$.errors[0].code").value("PMS_HAS_REVIEWS"));
        // 제출 이력 있는 산출물이 걸린 작업도 못 지운다 / 이력 없으면 칸과 함께 지운다
        Map<String, Object> d = deliv(proj, "DFMEA");
        submit(pm, ((Number) d.get("delivId")).longValue(), 0, "dfmea.pdf").andExpect(status().isOk());
        accounts.exec(comp, "update pms_task set act_start = null, status = 'NOT_STARTED', progress_pct = 0 where task_id = ?", t.get("taskId"));
        mvc.perform(delete("/api/pms/tasks/" + t.get("taskId")).with(csrf()).with(pm)).andExpect(jsonPath("$.errors[0].code").value("PMS_HAS_DELIVERABLES"));
        long ppapTask = taskId(proj, "PPAP 제출·승인");
        mvc.perform(delete("/api/pms/tasks/" + ppapTask).with(csrf()).with(pm)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from pms_deliverable where proj_id = ?", Integer.class, proj)).isEqualTo(11);
    }

    // ------------------------------------------------------------------ 산출물 규칙·권한

    @Test
    void 산출물_칸_권한_상태_규칙과_승인본_파일은_지울_수_없다() throws Exception {
        long proj = apqpProject();
        long tid = taskId(proj, "공정 FMEA");
        // 칸 추가 = PM 만, 담당 지정
        send(post(P + "/" + proj + "/deliverables"), "{\"taskId\":" + tid + ",\"delivNm\":\"특성 매트릭스\"}", member)
                .andExpect(status().isForbidden());
        long add = id(send(post(P + "/" + proj + "/deliverables"), "{\"taskId\":" + tid + ",\"delivNm\":\"특성 매트릭스\",\"ownerLoginId\":\"mem1\",\"dueDate\":\""
                + today.minusDays(1) + "\"}", pm));
        Map<String, Object> row = deliv(proj, "특성 매트릭스");
        assertThat(row.get("late")).isEqualTo(true);
        assertThat(row.get("seq")).isEqualTo(20);
        // 담당 아닌 사람 제출 거부, 담당은 제출, 승인 권한 없으면 결정 거부
        long other = ((Number) deliv(proj, "PFMEA").get("delivId")).longValue();
        submit(member, other, 0, "x.pdf").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PMS_NOT_EDITABLE"));
        long fileId = id(submit(member, add, 0, "매트릭스.pdf"));
        decide(member, add, "APPROVE", null, 1).andExpect(status().isForbidden());
        // 낡은 rowVer, 없는 동작, 제출 전 승인
        decide(qa, add, "APPROVE", null, 0).andExpect(status().isConflict());
        decide(qa, add, "DELETE", null, 1).andExpect(jsonPath("$.errors[0].field").value("action"));
        decide(qa, other, "APPROVE", null, 0).andExpect(jsonPath("$.code").value("PMS_DELIV_STATE"));
        // 반려(사유 필수) → 다시 제출 → 승인
        decide(qa, add, "REJECT", null, 1).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        decide(qa, add, "REJECT", "개정 번호 누락", 1).andExpect(status().isOk());
        long file2 = id(submit(member, add, 2, "매트릭스 rev1.pdf"));
        decide(qa, add, "APPROVE", "확인", 3).andExpect(status().isOk());
        // 제출한 파일(반려본·승인본)은 제출 때 잠기고(공통 sys_file.locked_yn) 첨부 API 로 지울 수 없다
        assertThat(accounts.query(comp, "select count(*) from sys_file where file_id in (?, ?) and locked_yn = 'Y'", Long.class, fileId, file2))
                .isEqualTo(2L);
        mvc.perform(post("/api/platform/files/" + file2 + "/delete").with(csrf()).with(pm)).andExpect(status().isConflict());
        mvc.perform(post("/api/platform/files/" + fileId + "/delete").with(csrf()).with(pm)).andExpect(status().isConflict());
        mvc.perform(get("/api/platform/files/" + file2).with(qa)).andExpect(status().isOk());
        // 첨부 API 로 산출물 대상에 직접 올리기: 없는 산출물·형식 아닌 대상은 잠금 규칙(DeliverableFileLock)이 409
        for (String target : List.of("D999999999", "X" + add)) {
            mvc.perform(multipart("/api/platform/files").file(new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF-1.4".getBytes()))
                    .param("targetType", "pms-deliverable").param("targetId", target).with(csrf()).with(pm))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        }
        // 이력 있는 칸은 못 지우고, 이력 없는 칸은 지운다
        mvc.perform(delete(D + "/" + add).with(csrf()).with(pm)).andExpect(jsonPath("$.errors[0].code").value("PMS_DELIV_HAS_HISTORY"));
        long spare = id(send(post(P + "/" + proj + "/deliverables"), "{\"taskId\":" + tid + ",\"delivNm\":\"잘못 만든 칸\"}", pm));
        mvc.perform(delete(D + "/" + spare).with(csrf()).with(pm)).andExpect(status().isOk());
        // 면제 풀기: 제출물 있던 칸은 불가, 없던 칸은 NONE
        long pf = ((Number) deliv(proj, "공정 흐름도").get("delivId")).longValue();
        decide(qa, pf, "WAIVE", "기존 흐름도 사용", 0).andExpect(status().isOk());
        decide(qa, pf, "UNWAIVE", null, 1).andExpect(status().isOk());
        assertThat(deliv(proj, "공정 흐름도").get("status")).isEqualTo("NONE");
        decide(qa, add, "WAIVE", "x", 4).andExpect(jsonPath("$.code").value("PMS_DELIV_STATE"));
        // 빈 파일·실행 파일은 첨부 규칙이 거부(상태 그대로)
        mvc.perform(multipart(D + "/" + pf + "/submit").file(new MockMultipartFile("file", "a.exe", "application/octet-stream",
                new byte[] {'M', 'Z', 0, 0})).param("rowVer", "2").with(pm).with(csrf()))
                .andExpect(jsonPath("$.code").value("FILE_REJECTED"));
        assertThat(deliv(proj, "공정 흐름도").get("status")).isEqualTo("NONE");
    }

    // ------------------------------------------------------------------ A사로 B사 · 직접 SQL · 동시

    @Test
    void A사로_B사_산출물_심사_조회_제출_결정_불가와_직접_SQL_다른_회사_참조_거부() throws Exception {
        long proj = apqpProject();
        long dId = ((Number) deliv(proj, "DFMEA").get("delivId")).longValue();
        long rv = id(send(post("/api/pms/gates/" + taskId(proj, "G1 개발 착수 심사") + "/reviews"), "{}", pm));
        String other = accounts.newComp("PH");
        long bId = accounts.newUser(other, "pm1", PW);
        for (String prog : List.of("pms-project", "pms-deliverable", "pms-gate")) {
            accounts.grant(other, bId, "ALL", prog, "SEARCH", "SAVE", "APPROVE");
        }
        RequestPostProcessor b = accounts.as(other, bId, "pm1");
        mvc.perform(get(P + "/" + proj + "/deliverables").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(D + "/" + dId + "/history").with(b)).andExpect(status().isNotFound());
        submit(b, dId, 0, "x.pdf").andExpect(status().isNotFound());
        // 첨부 API 로 A사 산출물 대상에 직접 올리기 — B사 범위에선 산출물이 없으니 잠김(409)
        mvc.perform(multipart("/api/platform/files").file(new MockMultipartFile("file", "a.pdf", "application/pdf", "%PDF-1.4".getBytes()))
                .param("targetType", "pms-deliverable").param("targetId", "D" + dId).with(csrf()).with(b))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_LOCKED"));
        decide(b, dId, "WAIVE", "x", 0).andExpect(status().isNotFound());
        mvc.perform(get(R + "/" + rv).with(b)).andExpect(status().isNotFound());
        send(put(R + "/" + rv), "{\"reviewDate\":\"" + today + "\",\"rowVer\":0}", b).andExpect(status().isNotFound());
        mvc.perform(get(P + "/" + proj + "/gates").with(b)).andExpect(status().isNotFound());
        assertThat(accounts.query(comp, "select status from pms_deliverable where deliv_id = ?", String.class, dId)).isEqualTo("NONE");
        // B사 범위에서 A사 작업·심사를 가리키는 행을 직접 넣으면 같은 회사 확인 트리거(23503) 또는 RLS(42501)
        long aTask = taskId(proj, "설계 FMEA");
        Throwable t1 = catchThrowable(() -> accounts.exec(other, """
                insert into pms_deliverable (comp_cd, proj_id, task_id, seq, deliv_nm, created_by, updated_by)
                values (?, ?, ?, 99, '침입', 't', 't')""", other, proj, aTask));
        assertThat(PgErrors.sqlState(t1)).hasValueSatisfying(s -> assertThat(s).isIn("23503", "42501"));
        Throwable t2 = catchThrowable(() -> accounts.exec(other, "insert into pms_gate_check (comp_cd, review_id, seq, check_nm) values (?, ?, 1, 'x')",
                other, rv));
        assertThat(PgErrors.sqlState(t2)).hasValueSatisfying(s -> assertThat(s).isIn("23503", "42501"));
        // A사 이슈가 B사 품목을 가리키지 못한다
        accounts.exec(other, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'B-ITEM', 'B 품목', 'FG', 'MAKE', 'EA', 'Y', 't', 't')""", other);
        long bItem = accounts.query(other, "select item_id from mst_item where item_cd = 'B-ITEM'", Long.class);
        Throwable t3 = catchThrowable(() -> accounts.exec(comp, """
                insert into pms_issue (comp_cd, issue_no, proj_id, title, obj_type, obj_id, created_by, updated_by)
                values (?, 'IS-X', ?, '침입', 'ITEM', ?, 't', 't')""", comp, proj, bItem));
        assertThat(PgErrors.constraint(t3)).hasValue("fk_pms_issue_obj_comp");
    }

    @Test
    void 직접_SQL_결정된_심사_이력_승인본_파일_불변() throws Exception {
        long proj = apqpProject();
        approveDeliv(proj, "고객 요구사항 검토서");
        approveDeliv(proj, "타당성 검토서");
        long rv = id(send(post("/api/pms/gates/" + taskId(proj, "G1 개발 착수 심사") + "/reviews"), "{}", pm));
        saveReview(rv, "GO", "OK", "[]").andExpect(status().isOk());
        send(post(APR + "/" + id(submitReview(rv)) + "/approve"), "{}", qa).andExpect(status().isOk());
        Throwable t1 = catchThrowable(() -> accounts.exec(comp, "update pms_gate_review set decision = 'NO_GO' where review_id = ?", rv));
        assertThat(PgErrors.constraint(t1)).hasValue("ck_pms_gate_review_final");
        Throwable t2 = catchThrowable(() -> accounts.exec(comp, "update pms_gate_check set result = 'NG' where review_id = ?", rv));
        assertThat(PgErrors.constraint(t2)).hasValue("ck_pms_gate_child_draft");
        Throwable t3 = catchThrowable(() -> accounts.exec(comp, "update pms_deliverable_hist set note = 'x'"));
        assertThat(t3).rootCause().hasMessageContaining("permission denied");
        // 승인본의 파일을 그 자리에서 다른 파일로 바꾸거나, 승인을 바로 지우기(NONE)는 트리거가 막는다
        long dId = ((Number) deliv(proj, "고객 요구사항 검토서").get("delivId")).longValue();
        Throwable t4 = catchThrowable(() -> accounts.exec(comp, "update pms_deliverable set ref_id = ref_id + 1 where deliv_id = ?", dId));
        assertThat(PgErrors.constraint(t4)).isPresent();
        Throwable t5 = catchThrowable(() -> accounts.exec(comp, "update pms_deliverable set status = 'NONE', ref_type = null, ref_id = null where deliv_id = ?", dId));
        assertThat(PgErrors.constraint(t5)).hasValue("ck_pms_deliverable_transition");
        Throwable t6 = catchThrowable(() -> accounts.exec(comp, "update sys_file set use_yn = 'N' where target_type = 'pms-deliverable'"));
        assertThat(PgErrors.constraint(t6)).hasValue("ck_sys_file_locked");
        Throwable t7 = catchThrowable(() -> accounts.exec(comp, "delete from pms_gate_review where review_id = ?", rv));
        assertThat(t7).isNotNull();
    }

    @Test
    void 같은_게이트_심사를_동시에_만들면_하나만() throws Exception {
        long proj = apqpProject();
        long g1 = taskId(proj, "G1 개발 착수 심사");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Callable<Integer> call = () -> mvc.perform(json(post("/api/pms/gates/" + g1 + "/reviews"), "{}").with(pm)).andReturn()
                    .getResponse().getStatus();
            List<Future<Integer>> fs = pool.invokeAll(List.of(call, call, call, call));
            int ok = 0;
            for (Future<Integer> f : fs) {
                if (f.get() == 200) {
                    ok++;
                } else {
                    assertThat(f.get()).isEqualTo(409);
                }
            }
            assertThat(ok).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(accounts.query(comp, "select count(*) from pms_gate_review where gate_task_id = ?", Integer.class, g1)).isEqualTo(1);
    }

    @Test
    void 템플릿_산출물_칸_점검_문구_규칙() throws Exception {
        long t = id(send(post("/api/pms/templates"), "{\"tmplCd\":\"T1\",\"tmplNm\":\"t\",\"projType\":\"GENERAL\"}", pm));
        long a = id(send(post("/api/pms/templates/" + t + "/tasks"), "{\"taskNm\":\"작업\",\"durationDays\":3}", pm));
        long g = id(send(post("/api/pms/templates/" + t + "/tasks"), "{\"taskNm\":\"게이트\",\"taskType\":\"GATE\",\"offsetDays\":5}", pm));
        send(post("/api/pms/templates/tasks/" + a + "/items"), "{\"kind\":\"CHECK\",\"itemNm\":\"x\"}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_NOT_GATE"));
        send(post("/api/pms/templates/tasks/" + a + "/items"), "{\"kind\":\"DELIV\",\"itemNm\":\"결과서\",\"requiredYn\":\"N\"}", pm).andExpect(status().isOk());
        long c = id(send(post("/api/pms/templates/tasks/" + g + "/items"), "{\"kind\":\"CHECK\",\"itemNm\":\"점검 1\"}", pm));
        send(post("/api/pms/templates/tasks/" + g + "/items"), "{\"kind\":\"BOTH\",\"itemNm\":\"x\"}", pm).andExpect(jsonPath("$.errors[0].field").value("kind"));
        mvc.perform(get("/api/pms/templates/" + t + "/items").with(pm)).andExpect(jsonPath("$.data.length()").value(2));
        // 점검 문구가 있는 게이트는 다른 종류로 못 바꾼다 → 지우면 된다
        send(put("/api/pms/templates/tasks/" + g), "{\"taskNm\":\"게이트\",\"taskType\":\"TASK\",\"durationDays\":1,\"rowVer\":0}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_HAS_CHECKS"));
        mvc.perform(delete("/api/pms/templates/items/" + c).with(csrf()).with(pm)).andExpect(status().isOk());
        // 복사: 산출물 칸(필수 아님 그대로)
        long proj = id(send(post(P), "{\"projNm\":\"p\",\"projType\":\"GENERAL\",\"tmplCd\":\"T1\",\"startDate\":\"" + today + "\"}", pm));
        mvc.perform(get(P + "/" + proj + "/deliverables").with(pm)).andExpect(jsonPath("$.data.rows[0].requiredYn").value("N"))
                .andExpect(jsonPath("$.data.phases[0].pct").value(100));
        // 템플릿 작업을 지우면 칸도 함께
        mvc.perform(delete("/api/pms/templates/tasks/" + a).with(csrf()).with(pm)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from pms_template_item where tmpl_id = ?", Integer.class, t)).isZero();
    }
}
