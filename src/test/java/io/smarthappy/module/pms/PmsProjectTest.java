package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.hasItem;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
 * PMS 1단계 프로젝트·WBS·템플릿 (docs/32 PM1) — 템플릿 복사(SOP 역산·이후 변경 무관), 요약 파생, 상태·실적 규칙, 트리 이동·순환·깊이·개수 상한,
 * 행 권한(PM·담당자·PMO), 상태 전이·닫힌 프로젝트(직접 SQL 포함), 번호·멱등, A사로 B사, 이상 입력(B·F·H), 1,000 작업 조회 실측.
 */
@AutoConfigureMockMvc
class PmsProjectTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String T = "/api/pms/templates";
    private static final String P = "/api/pms/projects";
    private static final String K = "/api/pms/tasks";

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
        comp = accounts.newComp("PJ");
        pmId = accounts.newUser(comp, "pm1", PW);
        accounts.grant(comp, pmId, "PMS", "pms-project", "SEARCH", "SAVE");
        accounts.grant(comp, pmId, "PMS", "pms-template", "SEARCH", "SAVE");
        pm = accounts.as(comp, pmId, "pm1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY, LocalDate.class);
        accounts.exec(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'OEM1', '완성차 A', 'SALES', 't', 't')""", comp);
        item(comp, "84910-2S000");
        item(comp, "AB.123/4");
    }

    private void item(String c, String cd) {
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, ?, ?, 'FG', 'MAKE', 'EA', 'Y', 't', 't')""", c, cd, cd + " 품목");
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

    private long project(String extra) throws Exception {
        return id(send(post(P), "{\"projNm\":\"신규 브래킷\",\"projType\":\"APQP\"" + extra + "}", pm));
    }

    private long task(long proj, String body) throws Exception {
        return id(send(post(P + "/" + proj + "/tasks"), body, pm));
    }

    private Map<String, Object> taskRow(long proj, long taskId) throws Exception {
        List<Map<String, Object>> rows = read(mvc.perform(get(P + "/" + proj + "/tasks").with(pm)), "$.data.tasks[?(@.taskId == " + taskId + ")]");
        return rows.get(0);
    }

    private int rowVer(long proj, long taskId) throws Exception {
        return ((Number) taskRow(proj, taskId).get("rowVer")).intValue();
    }

    // ------------------------------------------------------------------ 템플릿 → 프로젝트

    @Test
    void 기본_APQP_템플릿은_한번만_SOP_역산으로_복사되고_템플릿을_고쳐도_프로젝트는_그대로() throws Exception {
        long t1 = id(send(post(T + "/default-apqp"), "{}", pm));
        long t2 = id(send(post(T + "/default-apqp"), "{}", pm));
        assertThat(t2).isEqualTo(t1);
        mvc.perform(get(T + "/" + t1).with(pm))
                .andExpect(jsonPath("$.data.template.anchor").value("SOP"))
                .andExpect(jsonPath("$.data.tasks.length()").value(24))
                .andExpect(jsonPath("$.data.tasks[0].wbsNo").value("1"))
                .andExpect(jsonPath("$.data.tasks[0].isSummary").value("Y"))
                .andExpect(jsonPath("$.data.tasks[0].startDay").value(-300));
        // SOP 템플릿인데 SOP 일 없음 → 칸 오류, 있으면 SOP 마일스톤이 SOP 일에 온다
        send(post(P), "{\"projNm\":\"x\",\"projType\":\"APQP\",\"tmplCd\":\"APQP-STD\"}", pm)
                .andExpect(jsonPath("$.errors[0].field").value("sopDate"))
                .andExpect(jsonPath("$.errors[0].code").value("PMS_SOP_FOR_TEMPLATE"));
        long proj = project(",\"custCd\":\"oem1\",\"sopDate\":\"2027-03-02\",\"tmplCd\":\"apqp-std\",\"itemCds\":[\"84910-2S000\",\"84910-2s000\"]");
        ResultActions v = mvc.perform(get(P + "/" + proj + "/tasks").with(pm));
        v.andExpect(jsonPath("$.data.tasks.length()").value(24))
                .andExpect(jsonPath("$.data.tasks[?(@.taskNm == 'SOP 양산 시작')].planStart").value(hasItem("2027-03-02")))
                .andExpect(jsonPath("$.data.tasks[?(@.taskNm == 'SOP 양산 시작')].wbsNo").value(hasItem("4.5")))
                .andExpect(jsonPath("$.data.project.planStart").value("2026-05-06"))      // SOP - 300
                .andExpect(jsonPath("$.data.project.planEnd").value("2027-05-31"))        // SOP + 90
                .andExpect(jsonPath("$.data.project.tmplCd").value("APQP-STD"))
                .andExpect(jsonPath("$.data.project.projNo").value(org.hamcrest.Matchers.startsWith("PJ-")));
        mvc.perform(get(P + "/" + proj).with(pm)).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.custCd").value("OEM1")).andExpect(jsonPath("$.data.canManage").value(true));
        // 템플릿 작업 이름을 바꿔도 이미 만든 프로젝트는 그대로
        Map<String, Object> first = read(mvc.perform(get(T + "/" + t1).with(pm)), "$.data.tasks[1]");
        send(put(T + "/tasks/" + first.get("ttId")), "{\"taskNm\":\"바뀐 이름\",\"taskType\":\"TASK\",\"offsetDays\":-300,\"durationDays\":20,\"rowVer\":"
                + first.get("rowVer") + "}", pm).andExpect(status().isOk());
        mvc.perform(get(P + "/" + proj + "/tasks").with(pm)).andExpect(jsonPath("$.data.tasks[1].taskNm").value("고객 요구사항·CSR 검토"));
        // 번호는 회사·해마다 0001 부터, 규칙을 회사가 바꾸면 그 규칙(기본 규칙을 덮지 않는다)
        accounts.exec(comp, "update sys_numbering_rule set pattern = 'NPI-{yy}-{seq:3}' where rule_cd = 'PMS_PROJECT'");
        long p2 = project("");
        mvc.perform(get(P + "/" + p2).with(pm)).andExpect(jsonPath("$.data.projNo").value(org.hamcrest.Matchers.startsWith("NPI-")));
    }

    @Test
    void 시작일_기준_템플릿_작업_추가_이동_삭제와_요약_일정() throws Exception {
        long t = id(send(post(T), "{\"tmplCd\":\"chg-4m\",\"tmplNm\":\"4M 변경\",\"projType\":\"CHANGE\",\"anchor\":\"START\"}", pm));
        long ph = id(send(post(T + "/" + t + "/tasks"), "{\"taskNm\":\"계획\",\"offsetDays\":0,\"durationDays\":1}", pm));
        long a = id(send(post(T + "/" + t + "/tasks"), "{\"parentId\":" + ph + ",\"taskNm\":\"영향 평가\",\"offsetDays\":0,\"durationDays\":5}", pm));
        long g = id(send(post(T + "/" + t + "/tasks"), "{\"parentId\":" + ph + ",\"taskNm\":\"승인\",\"taskType\":\"GATE\",\"offsetDays\":9,\"durationDays\":7}", pm));
        // 게이트·마일스톤 밑에는 하위 금지, 기간은 0 으로 저장
        send(post(T + "/" + t + "/tasks"), "{\"parentId\":" + g + ",\"taskNm\":\"x\"}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_NO_CHILDREN"));
        // a 뒤에 끼워 넣기 → 순서 a, b, g
        long b = id(send(post(T + "/" + t + "/tasks"), "{\"parentId\":" + ph + ",\"afterId\":" + a + ",\"taskNm\":\"고객 통보\",\"offsetDays\":5,\"durationDays\":2}", pm));
        ResultActions v = mvc.perform(get(T + "/" + t).with(pm));
        v.andExpect(jsonPath("$.data.tasks[*].taskNm").value(org.hamcrest.Matchers.contains("계획", "영향 평가", "고객 통보", "승인")))
                .andExpect(jsonPath("$.data.tasks[0].startDay").value(0)).andExpect(jsonPath("$.data.tasks[0].endDay").value(9))
                .andExpect(jsonPath("$.data.tasks[3].durationDays").value(0));
        // 하위가 있으면 삭제·마일스톤 전환 거부, 범위 밖 일수
        mvc.perform(delete(T + "/tasks/" + ph).with(csrf()).with(pm)).andExpect(jsonPath("$.errors[0].code").value("PMS_HAS_CHILDREN"));
        send(put(T + "/tasks/" + ph), "{\"taskNm\":\"계획\",\"taskType\":\"MILESTONE\",\"rowVer\":0}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_HAS_CHILDREN"));
        send(post(T + "/" + t + "/tasks"), "{\"taskNm\":\"x\",\"offsetDays\":99999,\"durationDays\":0}", pm)
                .andExpect(jsonPath("$.errors.length()").value(2));
        // 내어쓰기 b → 뿌리(계획 다음), 시작일 기준으로 복사하면 날짜 = 시작일 + 일수
        send(post(T + "/tasks/" + b + "/move"), "{\"op\":\"OUTDENT\",\"rowVer\":0}", pm).andExpect(status().isOk());
        mvc.perform(get(T + "/" + t).with(pm)).andExpect(jsonPath("$.data.tasks[3].wbsNo").value("2"));
        send(post(P), "{\"projNm\":\"x\",\"projType\":\"CHANGE\",\"tmplCd\":\"CHG-4M\"}", pm)
                .andExpect(jsonPath("$.errors[0].field").value("startDate"));
        long proj = project(",\"tmplCd\":\"CHG-4M\",\"startDate\":\"2026-11-02\"");
        mvc.perform(get(P + "/" + proj + "/tasks").with(pm))
                .andExpect(jsonPath("$.data.tasks[?(@.taskNm == '승인')].planStart").value(hasItem("2026-11-11")))
                .andExpect(jsonPath("$.data.tasks[?(@.taskNm == '고객 통보')].planEnd").value(hasItem("2026-11-08")));
        // 사용 중지 템플릿은 새 프로젝트에 못 쓴다
        send(put(T + "/" + t), "{\"tmplNm\":\"4M 변경\",\"projType\":\"CHANGE\",\"anchor\":\"START\",\"useYn\":\"N\",\"rowVer\":0}", pm)
                .andExpect(status().isOk());
        send(post(P), "{\"projNm\":\"x\",\"projType\":\"CHANGE\",\"tmplCd\":\"CHG-4M\",\"startDate\":\"2026-11-02\"}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        // 코드 고정, 중복
        send(put(T + "/" + t), "{\"tmplCd\":\"OTHER\",\"tmplNm\":\"x\",\"projType\":\"CHANGE\",\"rowVer\":1}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        send(post(T), "{\"tmplCd\":\"CHG-4M\",\"tmplNm\":\"x\",\"projType\":\"CHANGE\"}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
    }

    // ------------------------------------------------------------------ 작업 규칙

    @Test
    void 요약은_하위에서_파생되고_직접_못고침_상태와_실적은_서로_맞아야() throws Exception {
        long proj = project("");
        long ph = task(proj, "{\"taskNm\":\"설계\",\"planStart\":\"2026-03-01\",\"planEnd\":\"2026-03-01\"}");
        long a = task(proj, "{\"parentId\":" + ph + ",\"taskNm\":\"도면\",\"planStart\":\"2026-03-01\",\"planEnd\":\"2026-03-10\"}");
        long b = task(proj, "{\"parentId\":" + ph + ",\"taskNm\":\"시험\",\"planStart\":\"2026-03-11\",\"planEnd\":\"2026-04-09\"}");
        Map<String, Object> s = taskRow(proj, ph);
        assertThat(s.get("isSummary")).isEqualTo("Y");
        assertThat(s.get("planEnd")).isEqualTo("2026-04-09");
        // 진척 40% 진행 중(시작일 필요)
        send(put(K + "/" + a), "{\"taskNm\":\"도면\",\"planStart\":\"2026-03-01\",\"planEnd\":\"2026-03-10\",\"progressPct\":40,\"status\":\"IN_PROGRESS\",\"rowVer\":0}", pm)
                .andExpect(jsonPath("$.errors[0].field").value("actStart"));
        send(put(K + "/" + a), "{\"taskNm\":\"도면\",\"planStart\":\"2026-03-01\",\"planEnd\":\"2026-03-10\",\"actStart\":\"2026-03-02\",\"progressPct\":40,\"rowVer\":0}", pm)
                .andExpect(status().isOk());
        s = taskRow(proj, ph);
        assertThat(s.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(s.get("progressPct")).isEqualTo(10);            // 10일×40 / 40일 = 10
        assertThat(s.get("actStart")).isEqualTo("2026-03-02");
        mvc.perform(get(P + "/" + proj).with(pm)).andExpect(jsonPath("$.data.progressPct").value(10))
                .andExpect(jsonPath("$.data.planStart").value("2026-03-01"));
        // 요약의 날짜·진척을 직접 바꾸기 → 거부, 이름·비고는 된다
        int sv = ((Number) s.get("rowVer")).intValue();
        send(put(K + "/" + ph), "{\"taskNm\":\"설계\",\"planStart\":\"2026-01-01\",\"planEnd\":\"2026-04-09\",\"rowVer\":" + sv + "}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_SUMMARY_DERIVED"));
        send(put(K + "/" + ph), "{\"taskNm\":\"제품 설계\",\"planStart\":\"2026-03-01\",\"planEnd\":\"2026-04-09\",\"actStart\":\"2026-03-02\",\"progressPct\":10,\"status\":\"IN_PROGRESS\",\"remark\":\"r\",\"rowVer\":" + sv + "}", pm)
                .andExpect(status().isOk());
        // 상태·실적 모순들(F): 실적 종료 = 완료, 100% 진행 중, 시작 전인데 실적, 미래 실적, 종료 < 시작, 마일스톤 이틀
        String base = "\"taskNm\":\"시험\",\"planStart\":\"2026-03-11\",\"planEnd\":\"2026-04-09\",\"rowVer\":0";
        send(put(K + "/" + b), "{" + base + ",\"actStart\":\"2026-03-12\",\"actEnd\":\"2026-03-20\",\"status\":\"IN_PROGRESS\"}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_END_MEANS_DONE"));
        send(put(K + "/" + b), "{" + base + ",\"actStart\":\"2026-03-12\",\"progressPct\":100,\"status\":\"IN_PROGRESS\"}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_STATE_PROGRESS"));
        send(put(K + "/" + b), "{" + base + ",\"actStart\":\"2026-03-12\",\"status\":\"NOT_STARTED\"}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_STATE_ACTUALS"));
        send(put(K + "/" + b), "{" + base + ",\"actStart\":\"" + today.plusDays(3) + "\"}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("FUTURE"));
        send(put(K + "/" + b), "{" + base + ",\"actStart\":\"2026-03-12\",\"actEnd\":\"2026-03-11\"}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_END_BEFORE_START"));
        send(put(K + "/" + b), "{\"taskNm\":\"시험\",\"taskType\":\"MILESTONE\",\"planStart\":\"2026-03-11\",\"planEnd\":\"2026-03-12\",\"rowVer\":0}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_ONE_DAY"));
        // 둘 다 완료 → 요약 완료 100%, 실적 종료 = 늦은 쪽. 실적 종료만 넣으면 완료로(상태 비움)
        send(put(K + "/" + b), "{" + base + ",\"actStart\":\"2026-03-12\",\"actEnd\":\"2026-04-01\"}", pm).andExpect(status().isOk());
        send(put(K + "/" + a), "{\"taskNm\":\"도면\",\"planStart\":\"2026-03-01\",\"planEnd\":\"2026-03-10\",\"actStart\":\"2026-03-02\",\"actEnd\":\"2026-03-09\",\"rowVer\":1}", pm)
                .andExpect(status().isOk());
        s = taskRow(proj, ph);
        assertThat(s.get("status")).isEqualTo("DONE");
        assertThat(s.get("progressPct")).isEqualTo(100);
        assertThat(s.get("actEnd")).isEqualTo("2026-04-01");
        // 실적 있는 잎은 지울 수 없음(취소로), 실적 있는 잎 아래에는 하위를 못 둔다(요약이 되면 실적이 덮인다)
        mvc.perform(delete(K + "/" + a).with(csrf()).with(pm)).andExpect(jsonPath("$.errors[0].code").value("PMS_HAS_ACTUALS"));
        task(proj, "{\"taskNm\":\"단독\",\"planStart\":\"2026-03-01\"}");
        send(post(P + "/" + proj + "/tasks"), "{\"parentId\":" + a + ",\"taskNm\":\"x\",\"planStart\":\"2026-03-01\"}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_NO_CHILDREN"));
        // 낡은 화면(rowVer) 저장 → 409
        send(put(K + "/" + a), "{\"taskNm\":\"도면\",\"planStart\":\"2026-03-01\",\"planEnd\":\"2026-03-10\",\"rowVer\":0}", pm)
                .andExpect(status().isConflict());
        // 직접 SQL: 상태·진척 모순, 마일스톤 이틀 → CHECK
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update pms_task set progress_pct = 50 where task_id = ?", b)))).contains("ck_pms_task_state");
    }

    @Test
    void 트리_이동_순환_깊이_개수_상한과_직접SQL_다른프로젝트_부모() throws Exception {
        long proj = project("");
        long r1 = task(proj, "{\"taskNm\":\"r1\",\"planStart\":\"2026-03-01\"}");
        long r2 = task(proj, "{\"taskNm\":\"r2\",\"planStart\":\"2026-03-01\"}");
        long c1 = task(proj, "{\"parentId\":" + r1 + ",\"taskNm\":\"c1\",\"planStart\":\"2026-03-01\"}");
        // r2 들여쓰기 → r1 의 마지막 자식, 다시 위로 → c1 앞
        send(post(K + "/" + r2 + "/move"), "{\"op\":\"INDENT\",\"rowVer\":0}", pm).andExpect(status().isOk());
        send(post(K + "/" + r2 + "/move"), "{\"op\":\"UP\",\"rowVer\":" + rowVer(proj, r2) + "}", pm).andExpect(status().isOk());
        mvc.perform(get(P + "/" + proj + "/tasks").with(pm))
                .andExpect(jsonPath("$.data.tasks[*].wbsNo").value(org.hamcrest.Matchers.contains("1", "1.1", "1.2")))
                .andExpect(jsonPath("$.data.tasks[1].taskNm").value("r2"));
        // 낡은 rowVer 이동, 더 못 가는 이동
        send(post(K + "/" + r2 + "/move"), "{\"op\":\"DOWN\",\"rowVer\":0}", pm).andExpect(status().isConflict());
        send(post(K + "/" + r1 + "/move"), "{\"op\":\"OUTDENT\",\"rowVer\":" + rowVer(proj, r1) + "}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_CANNOT_MOVE"));
        // 직접 SQL 순환(r1 을 자기 손자 아래로) → 트리거
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update pms_task set parent_id = ? where task_id = ?", c1, r1)))).contains("ck_pms_task_cycle");
        // 다른 프로젝트 작업을 부모로 → 트리거
        long other = project("");
        long o1 = task(other, "{\"taskNm\":\"o1\",\"planStart\":\"2026-03-01\"}");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update pms_task set parent_id = ? where task_id = ?", o1, c1)))).contains("fk_pms_task_parent_proj");
        // 깊이 상한(정책 3) — 추가·들여쓰기(가지 전체 높이) 모두
        accounts.exec(comp, """
                insert into sys_policy (comp_cd, policy_key, plant_cd, policy_value, created_by, updated_by)
                values (?, 'PMS_MAX_DEPTH', '*', '3', 't', 't')""", comp);
        long g1 = task(proj, "{\"parentId\":" + c1 + ",\"taskNm\":\"g1\",\"planStart\":\"2026-03-01\"}");
        send(post(P + "/" + proj + "/tasks"), "{\"parentId\":" + g1 + ",\"taskNm\":\"too deep\",\"planStart\":\"2026-03-01\"}", pm)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PMS_TOO_DEEP"));
        long r3 = task(proj, "{\"taskNm\":\"r3\",\"planStart\":\"2026-03-01\"}");
        task(proj, "{\"parentId\":" + r3 + ",\"taskNm\":\"r3c\",\"planStart\":\"2026-03-01\"}");
        long r4 = task(proj, "{\"taskNm\":\"r4\",\"planStart\":\"2026-03-01\"}");
        send(post(K + "/" + r3 + "/move"), "{\"op\":\"INDENT\",\"rowVer\":" + rowVer(proj, r3) + "}", pm).andExpect(status().isOk());
        send(post(K + "/" + r4 + "/move"), "{\"op\":\"INDENT\",\"rowVer\":" + rowVer(proj, r4) + "}", pm).andExpect(status().isOk());
        // r3(2단)·r3c(3단) 를 r1 아래 c1 뒤로 더 밀면 4단 → 거부
        send(post(K + "/" + r3 + "/move"), "{\"op\":\"INDENT\",\"rowVer\":" + rowVer(proj, r3) + "}", pm)
                .andExpect(jsonPath("$.code").value("PMS_TOO_DEEP"));
        // 개수 상한(정책 최소 50) — 넘으면 잘라 보이지 않고 오류
        accounts.exec(comp, """
                insert into sys_policy (comp_cd, policy_key, plant_cd, policy_value, created_by, updated_by)
                values (?, 'PMS_MAX_TASKS', '*', '50', 't', 't')""", comp);
        accounts.exec(comp, """
                insert into pms_task (comp_cd, proj_id, seq, task_nm, plan_start, plan_end, created_by, updated_by)
                select ?, ?, 1000 + g, 'bulk ' || g, date '2026-03-01', date '2026-03-01', 't', 't' from generate_series(1, 50 - ?) g""",
                comp, other, 1);
        send(post(P + "/" + other + "/tasks"), "{\"taskNm\":\"51st\",\"planStart\":\"2026-03-01\"}", pm)
                .andExpect(jsonPath("$.code").value("PMS_TOO_MANY_TASKS"));
    }

    // ------------------------------------------------------------------ 권한·상태

    @Test
    void 행권한_PM_담당자_PMO_조회만() throws Exception {
        long proj = project("");
        long a = task(proj, "{\"taskNm\":\"도면\",\"planStart\":\"2026-03-01\",\"planEnd\":\"2026-03-10\",\"ownerLoginId\":\"pm1\"}");
        long engId = accounts.newUser(comp, "eng1", PW);
        accounts.grant(comp, engId, "ENG", "pms-project", "SEARCH", "SAVE");
        RequestPostProcessor eng = accounts.as(comp, engId, "eng1");
        // 담당자를 eng1 로 → eng1 은 진행만, 계획·이름은 못 바꿈, 구조(추가·이동·삭제)·머리·상태 못 바꿈
        send(put(K + "/" + a), "{\"taskNm\":\"도면\",\"planStart\":\"2026-03-01\",\"planEnd\":\"2026-03-10\",\"ownerLoginId\":\"ENG1\",\"rowVer\":0}", pm)
                .andExpect(status().isOk());
        mvc.perform(get(P + "/" + proj + "/tasks").with(eng)).andExpect(jsonPath("$.data.project.canManage").value(false))
                .andExpect(jsonPath("$.data.tasks[0].canEdit").value(true));
        send(put(K + "/" + a), "{\"taskNm\":\"도면\",\"planStart\":\"2026-03-01\",\"planEnd\":\"2026-03-20\",\"ownerLoginId\":\"eng1\",\"rowVer\":1}", eng)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PMS_NOT_EDITABLE"));
        send(put(K + "/" + a), "{\"taskNm\":\"도면\",\"planStart\":\"2026-03-01\",\"planEnd\":\"2026-03-10\",\"ownerLoginId\":\"eng1\",\"actStart\":\"2026-03-02\",\"progressPct\":30,\"rowVer\":1}", eng)
                .andExpect(status().isOk());
        send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"x\",\"planStart\":\"2026-03-01\"}", eng)
                .andExpect(jsonPath("$.code").value("PMS_NOT_EDITABLE"));
        send(put(P + "/" + proj), "{\"projNm\":\"y\",\"projType\":\"APQP\",\"rowVer\":0}", eng).andExpect(jsonPath("$.code").value("PMS_NOT_EDITABLE"));
        send(post(P + "/" + proj + "/status"), "{\"status\":\"ACTIVE\",\"rowVer\":0}", eng).andExpect(jsonPath("$.code").value("PMS_NOT_EDITABLE"));
        mvc.perform(delete(K + "/" + a).with(csrf()).with(eng)).andExpect(jsonPath("$.code").value("PMS_NOT_EDITABLE"));
        // 남의 작업(담당 아님)은 진행도 못 바꿈
        long b = task(proj, "{\"taskNm\":\"시험\",\"planStart\":\"2026-03-11\"}");
        send(put(K + "/" + b), "{\"taskNm\":\"시험\",\"planStart\":\"2026-03-11\",\"actStart\":\"2026-03-12\",\"rowVer\":0}", eng)
                .andExpect(jsonPath("$.code").value("PMS_NOT_EDITABLE"));
        // PMO(APPROVE) 는 PM 이 아니어도 관리
        accounts.grant(comp, engId, "ENG", "pms-project", "APPROVE");
        send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"pmo 추가\",\"planStart\":\"2026-03-01\"}", eng).andExpect(status().isOk());
        // 조회만 → 저장 403(화면 권한), 다른 화면 권한으로 템플릿 API 403
        long rd = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, rd, "READ", "pms-project", "SEARCH");
        RequestPostProcessor r = accounts.as(comp, rd, "reader1");
        mvc.perform(get(P + "/" + proj + "/tasks").with(r)).andExpect(jsonPath("$.data.tasks[0].canEdit").value(false));
        send(put(K + "/" + a), "{\"taskNm\":\"x\",\"planStart\":\"2026-03-01\",\"rowVer\":2}", r).andExpect(status().isForbidden());
        mvc.perform(get(T).with(r)).andExpect(status().isForbidden());
        mvc.perform(get(P + "/templates").with(r)).andExpect(status().isOk());
        // 없는·사용 중지 담당자
        accounts.setUserUse(comp, rd, "N");
        send(put(K + "/" + b), "{\"taskNm\":\"시험\",\"planStart\":\"2026-03-11\",\"ownerLoginId\":\"reader1\",\"rowVer\":0}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
    }

    @Test
    void 상태전이_보류취소_사유_종료는_열린작업없이_닫히면_직접SQL도_못고침() throws Exception {
        long proj = project("");
        send(post(P + "/" + proj + "/status"), "{\"status\":\"CLOSED\",\"rowVer\":0}", pm).andExpect(jsonPath("$.errors[0].code").value("PMS_TRANSITION"));
        send(post(P + "/" + proj + "/status"), "{\"status\":\"ACTIVE\",\"rowVer\":0}", pm).andExpect(status().isOk());
        send(post(P + "/" + proj + "/status"), "{\"status\":\"ON_HOLD\",\"rowVer\":1}", pm).andExpect(jsonPath("$.errors[0].field").value("reason"));
        send(post(P + "/" + proj + "/status"), "{\"status\":\"ON_HOLD\",\"reason\":\"고객 일정 보류\",\"rowVer\":1}", pm).andExpect(status().isOk());
        send(post(P + "/" + proj + "/status"), "{\"status\":\"ACTIVE\",\"rowVer\":2}", pm).andExpect(status().isOk());
        send(post(P + "/" + proj + "/status"), "{\"status\":\"CLOSED\",\"rowVer\":3}", pm).andExpect(jsonPath("$.errors[0].code").value("PMS_NO_TASKS"));
        long a = task(proj, "{\"taskNm\":\"a\",\"planStart\":\"2026-03-01\"}");
        task(proj, "{\"taskNm\":\"b\",\"planStart\":\"2026-03-01\"}");
        send(post(P + "/" + proj + "/status"), "{\"status\":\"CLOSED\",\"rowVer\":3}", pm).andExpect(jsonPath("$.errors[0].code").value("PMS_OPEN_TASKS"));
        // 같은 상태로·없는 상태로(요청값 조작) → 전이 오류
        send(post(P + "/" + proj + "/status"), "{\"status\":\"ACTIVE\",\"rowVer\":0}", pm).andExpect(jsonPath("$.errors[0].code").value("PMS_TRANSITION"));
        send(post(P + "/" + proj + "/status"), "{\"status\":\"DELETED\",\"rowVer\":3}", pm).andExpect(jsonPath("$.errors[0].code").value("PMS_TRANSITION"));
        send(post(P + "/" + proj + "/status"), "{\"status\":\"CANCELLED\",\"reason\":\"수주 취소\",\"rowVer\":3}", pm).andExpect(status().isOk());
        // 닫힌 프로젝트: 작업·머리·상태 모두 409, 직접 SQL 도 트리거
        send(put(K + "/" + a), "{\"taskNm\":\"a2\",\"planStart\":\"2026-03-01\",\"rowVer\":0}", pm).andExpect(jsonPath("$.code").value("PMS_PROJECT_CLOSED"));
        send(put(P + "/" + proj), "{\"projNm\":\"x\",\"projType\":\"APQP\",\"rowVer\":4}", pm).andExpect(jsonPath("$.code").value("PMS_PROJECT_CLOSED"));
        send(post(P + "/" + proj + "/status"), "{\"status\":\"ACTIVE\",\"rowVer\":4}", pm).andExpect(jsonPath("$.code").value("PMS_PROJECT_CLOSED"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update pms_task set task_nm = 'x' where task_id = ?", a)))).contains("ck_pms_task_project_closed");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update pms_project set status = 'ACTIVE' where proj_id = ?", proj)))).contains("ck_pms_project_closed_fixed");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from pms_project where proj_id = ?", proj))))
                .contains("42501");
        mvc.perform(get(P).param("status", "CANCELLED").with(pm)).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].closedOn").value(today.toString()));
        // 종료: 모든 잎 완료·취소면 된다
        long p2 = project("");
        long x = task(p2, "{\"taskNm\":\"x\",\"planStart\":\"2026-03-01\"}");
        send(post(P + "/" + p2 + "/status"), "{\"status\":\"ACTIVE\",\"rowVer\":0}", pm).andExpect(status().isOk());
        send(put(K + "/" + x), "{\"taskNm\":\"x\",\"planStart\":\"2026-03-01\",\"actStart\":\"2026-03-01\",\"actEnd\":\"2026-03-02\",\"rowVer\":0}", pm)
                .andExpect(status().isOk());
        send(post(P + "/" + p2 + "/status"), "{\"status\":\"CLOSED\",\"rowVer\":1}", pm).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ A사로 B사·입력 한계·멱등

    @Test
    void B사로_A사_프로젝트_작업_템플릿_모두_막힘_다른회사_참조_거부() throws Exception {
        long t = id(send(post(T + "/default-apqp"), "{}", pm));
        long proj = project(",\"custCd\":\"OEM1\",\"itemCds\":[\"AB.123/4\"]");
        long a = task(proj, "{\"taskNm\":\"a\",\"planStart\":\"2026-03-01\"}");
        String other = accounts.newComp("PB");
        long bu = accounts.newUser(other, "pm1", PW);
        accounts.grant(other, bu, "PMS", "pms-project", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(other, bu, "PMS", "pms-template", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bu, "pm1");
        mvc.perform(get(P).param("status", "").with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(P + "/" + proj).with(b)).andExpect(status().isNotFound());
        mvc.perform(get(P + "/" + proj + "/tasks").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(T + "/" + t).with(b)).andExpect(status().isNotFound());
        send(put(P + "/" + proj), "{\"projNm\":\"x\",\"projType\":\"APQP\",\"rowVer\":0}", b).andExpect(status().isNotFound());
        send(put(K + "/" + a), "{\"taskNm\":\"x\",\"planStart\":\"2026-03-01\",\"rowVer\":0}", b).andExpect(status().isNotFound());
        send(post(K + "/" + a + "/move"), "{\"op\":\"UP\",\"rowVer\":0}", b).andExpect(status().isNotFound());
        mvc.perform(delete(K + "/" + a).with(csrf()).with(b)).andExpect(status().isNotFound());
        send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"x\",\"planStart\":\"2026-03-01\"}", b).andExpect(status().isNotFound());
        send(post(T + "/" + t + "/tasks"), "{\"taskNm\":\"x\"}", b).andExpect(status().isNotFound());
        // A사 고객·품번·템플릿 코드·사용자로 B사 프로젝트 → 없는 코드
        send(post(P), "{\"projNm\":\"x\",\"projType\":\"APQP\",\"custCd\":\"OEM1\",\"itemCds\":[\"AB.123/4\"],\"pmLoginId\":\"nobody\",\"tmplCd\":\"APQP-STD\",\"sopDate\":\"2027-01-04\"}", b)
                .andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "NO_SUCH_CODE", "NO_SUCH_CODE", "PMS_NO_SUCH_ITEM", "NO_SUCH_CODE")));
        // 직접 SQL: B사 프로젝트가 A사 고객·PM 을, B사 작업이 A사 프로젝트를 가리키기
        long bp = id(send(post(P), "{\"projNm\":\"b\",\"projType\":\"GENERAL\"}", b));
        long aCust = accounts.query(comp, "select cust_id from mst_cust where cust_cd = 'OEM1'", Long.class);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other,
                "update pms_project set cust_id = ? where proj_id = ?", aCust, bp)))).contains("fk_pms_project_cust_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other,
                "update pms_project set pm_user_id = ? where proj_id = ?", pmId, bp)))).contains("fk_pms_project_pm_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into pms_task (comp_cd, proj_id, seq, task_nm, plan_start, plan_end, created_by, updated_by)
                values (?, ?, 1, 'x', date '2026-03-01', date '2026-03-01', 't', 't')""", other, proj)))).contains("fk_pms_task_proj_comp");
        // B사 이름으로 A사 회사코드 행 넣기 → RLS
        assertThat(PgErrors.isRlsViolation(catchThrowable(() -> accounts.exec(other, """
                insert into pms_template (comp_cd, tmpl_cd, tmpl_nm, proj_type, created_by, updated_by)
                values (?, 'X', 'x', 'GENERAL', 't', 't')""", comp)))).isTrue();
    }

    @Test
    void 이상입력_길이_공백_제어문자_HTML_날짜범위_LIKE_멱등() throws Exception {
        // 빈 값·공백만·너무 김·없는 유형
        send(post(P), "{\"projNm\":\"   \",\"projType\":\"ZZZ\"}", pm).andExpect(jsonPath("$.errors.length()").value(2));
        send(post(P), "{\"projNm\":\"" + "가".repeat(201) + "\",\"projType\":\"APQP\"}", pm).andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        // 제어문자는 공백으로, HTML 은 글자 그대로 저장(화면은 textContent)
        long proj = project(",\"remark\":\"<img src=x onerror=alert(1)>\"");
        long a = task(proj, "{\"taskNm\":\"줄\\n바꿈\\t탭 <b>굵게</b>\",\"planStart\":\"2026-03-01\"}");
        assertThat(taskRow(proj, a).get("taskNm")).isEqualTo("줄 바꿈 탭 <b>굵게</b>");
        // 날짜 형식·범위(9999년·0001년)·없는 날짜
        send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"x\",\"planStart\":\"9999-12-31\"}", pm).andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"x\",\"planStart\":\"2026-02-30\"}", pm).andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"x\",\"planStart\":\"2026/03/01\"}", pm).andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"x\",\"planStart\":\"2026-03-01\",\"progressPct\":-5}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        send(put(P + "/" + proj), "{\"projNm\":\"x\",\"projType\":\"APQP\",\"sopDate\":\"0001-01-01\",\"rowVer\":0}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        // 품번 50개 초과, 없는 품번
        String many = String.join(",", java.util.Collections.nCopies(51, "\"X\""));
        send(put(P + "/" + proj), "{\"projNm\":\"x\",\"projType\":\"APQP\",\"itemCds\":[" + many + "],\"rowVer\":0}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_TOO_MANY_ITEMS"));
        // 품번 바꾸기(추가·빼기) — 같은 품번을 두 번 보내도 한 번
        send(put(P + "/" + proj), "{\"projNm\":\"x\",\"projType\":\"APQP\",\"itemCds\":[\"AB.123/4\",\"84910-2S000\"],\"rowVer\":0}", pm).andExpect(status().isOk());
        send(put(P + "/" + proj), "{\"projNm\":\"x\",\"projType\":\"APQP\",\"itemCds\":[\"84910-2S000\",\"84910-2s000\"],\"rowVer\":1}", pm).andExpect(status().isOk());
        mvc.perform(get(P + "/" + proj).with(pm)).andExpect(jsonPath("$.data.items[*].itemCd").value(org.hamcrest.Matchers.contains("84910-2S000")));
        // LIKE 와일드카드는 글자로, 품번으로도 찾는다
        mvc.perform(get(P).param("keyword", "%").param("status", "").with(pm)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(P).param("keyword", "84910").param("status", "").with(pm)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(P).param("status", "NOPE").with(pm)).andExpect(status().isBadRequest());
        // 연타·재전송: 같은 멱등 키로 두 번 → 두 번째 409, 프로젝트 하나
        long before = accounts.query(comp, "select count(*) from pms_project", Long.class);
        mvc.perform(json(post(P), "{\"projNm\":\"연타\",\"projType\":\"APQP\"}").header("X-Idempotency-Key", "pms-key-0001").with(pm)).andExpect(status().isOk());
        mvc.perform(json(post(P), "{\"projNm\":\"연타\",\"projType\":\"APQP\"}").header("X-Idempotency-Key", "pms-key-0001").with(pm))
                .andExpect(status().isConflict());
        assertThat(accounts.query(comp, "select count(*) from pms_project", Long.class)).isEqualTo(before + 1);
    }

    @Test
    void 같은_프로젝트_동시_작업추가는_줄을_서고_요약이_어긋나지_않는다() throws Exception {
        long proj = project("");
        long ph = task(proj, "{\"taskNm\":\"단계\",\"planStart\":\"2026-03-01\"}");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> jobs = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int d = i + 1;
                jobs.add(() -> mvc.perform(json(post(P + "/" + proj + "/tasks"), "{\"parentId\":" + ph + ",\"taskNm\":\"c" + d
                        + "\",\"planStart\":\"2026-03-0" + d + "\"}").with(pm)).andReturn().getResponse().getStatus());
            }
            for (Future<Integer> f : pool.invokeAll(jobs)) {
                assertThat(f.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdown();
        }
        Map<String, Object> s = taskRow(proj, ph);
        assertThat(s.get("planStart")).isEqualTo("2026-03-01");
        assertThat(s.get("planEnd")).isEqualTo("2026-03-08");
        Long distinctSeq = accounts.query(comp, "select count(distinct seq) from pms_task where parent_id = ?", Long.class, ph);
        assertThat(distinctSeq).isEqualTo(8L);
    }

    @Test
    void 작업_1000개_프로젝트_조회와_2만_작업_목록_실측() throws Exception {
        long proj = project("");
        // 10 단계 × 99 작업 + 단계 10 = 1,000
        accounts.exec(comp, """
                insert into pms_task (comp_cd, proj_id, seq, task_nm, plan_start, plan_end, is_summary, created_by, updated_by)
                select ?, ?, g * 10, '단계 ' || g, date '2026-01-01', date '2026-01-01', 'Y', 't', 't' from generate_series(1, 10) g""", comp, proj);
        accounts.exec(comp, """
                insert into pms_task (comp_cd, proj_id, parent_id, seq, task_nm, plan_start, plan_end, created_by, updated_by)
                select ?, ?, p.task_id, g * 10, p.task_nm || '-' || g, date '2026-01-01' + g, date '2026-01-01' + g * 2, 't', 't'
                  from pms_task p, generate_series(1, 99) g where p.proj_id = ? and p.parent_id is null""", comp, proj, proj);
        // 다른 프로젝트 19개 × 1,000 = 2만 작업
        for (int i = 0; i < 19; i++) {
            long p = project("");
            accounts.exec(comp, """
                    insert into pms_task (comp_cd, proj_id, seq, task_nm, plan_start, plan_end, created_by, updated_by)
                    select ?, ?, g, 'x' || g, date '2026-01-01', date '2026-02-01', 't', 't' from generate_series(1, 1000) g""", comp, p);
        }
        long t0 = System.nanoTime();
        mvc.perform(get(P + "/" + proj + "/tasks").with(pm)).andExpect(jsonPath("$.data.tasks.length()").value(1000))
                .andExpect(jsonPath("$.data.tasks[1].wbsNo").value("1.1"));
        long viewMs = (System.nanoTime() - t0) / 1_000_000;
        // 저장 한 번 = 전체 요약 재계산 + 바뀐 행만 쓰기
        long leaf = accounts.query(comp, "select task_id from pms_task where proj_id = ? and parent_id is not null order by task_id limit 1", Long.class, proj);
        t0 = System.nanoTime();
        send(put(K + "/" + leaf), "{\"taskNm\":\"단계 1-1\",\"planStart\":\"2026-01-02\",\"planEnd\":\"2026-01-03\",\"actStart\":\"2026-01-02\",\"progressPct\":50,\"rowVer\":0}", pm)
                .andExpect(status().isOk());
        long saveMs = (System.nanoTime() - t0) / 1_000_000;
        t0 = System.nanoTime();
        mvc.perform(get(P).param("status", "").with(pm)).andExpect(jsonPath("$.data.length()").value(20));
        long listMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("[PMS 실측] 1,000 작업 조회 %d ms, 저장(요약 재계산) %d ms, 2만 작업 20 프로젝트 목록 %d ms%n", viewMs, saveMs, listMs);
        assertThat(viewMs).isLessThan(5000);
        assertThat(saveMs).isLessThan(5000);
        assertThat(listMs).isLessThan(3000);
    }
}
