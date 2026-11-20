package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
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
 * 사람 × 주 부하 보기 GET /api/pms/load (docs/32 4.26, 사용자 결정 P-14) — 계획 공수를 근무일에 고르게(손 검산), 확정 공수만, 가용·초과 주,
 * 담당 없음 줄, 제외(취소 작업·취소 프로젝트·범위 밖), 프로젝트 조건, 칸 상세, 권한(APPROVE 만), 입력 검사, A사로 B사.
 * 날짜 고정: 2026-03-02 = 월요일, 회사 달력 토·일 쉼 + 3/4(수) 휴일 → 첫 주 근무일 4, 둘째 주 5.
 */
@AutoConfigureMockMvc
class PmsLoadTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/pms/projects";
    private static final String L = "/api/pms/load";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long pmoId;
    long aId;
    long bId;
    RequestPostProcessor pmo;
    RequestPostProcessor pmOnly;
    long proj;
    long proj2;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PL");
        pmoId = accounts.newUser(comp, "pmo1", PW);
        aId = accounts.newUser(comp, "aaa1", PW);
        bId = accounts.newUser(comp, "bbb1", PW);
        long pmOnlyId = accounts.newUser(comp, "pm1", PW);
        accounts.grant(comp, pmoId, "PMO", "pms-project", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, pmOnlyId, "PM", "pms-project", "SEARCH", "SAVE");
        pmo = accounts.as(comp, pmoId, "pmo1");
        pmOnly = accounts.as(comp, pmOnlyId, "pm1");
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'WEEKLY_OFF', 'SAT_SUN', 't', 't')",
                comp);
        accounts.exec(comp, "insert into mst_work_cal (comp_cd, cal_date, day_type, reason, created_by, updated_by) values (?, '2026-03-04', 'OFF', '휴무', 't', 't')",
                comp);
        proj = id(send(post(P), "{\"projNm\":\"ZT 부하1\",\"projType\":\"GENERAL\"}", pmo));
        proj2 = id(send(post(P), "{\"projNm\":\"ZT 부하2\",\"projType\":\"GENERAL\"}", pmo));
        // aaa1: 3/2~3/13 36시간(근무일 9 → 하루 4) → 1주 16, 2주 20
        task(proj, "설계", "aaa1", "2026-03-02", "2026-03-13", "36");
        // bbb1: 3/2~3/6 50시간 → 1주 50 > 가용 32 → 초과 1주. 다른 프로젝트
        task(proj2, "시작품", "bbb1", "2026-03-02", "2026-03-06", "50");
        // 담당 없음: 3/9 하루 4시간 → 2주
        task(proj, "검토", null, "2026-03-09", "2026-03-09", "4");
        // 확정 공수: aaa1 3/3 6(확정)·3/10 7(확정)·3/11 5(미확정 — 안 셈), bbb1 3/16 8(확정, 3주)
        log(aId, "2026-03-03", proj, 6, true);
        log(aId, "2026-03-10", proj, 7, true);
        log(aId, "2026-03-11", proj, 5, false);
        log(bId, "2026-03-16", proj2, 8, true);
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

    private long task(long p, String nm, String owner, String s, String e, String hours) throws Exception {
        return id(send(post(P + "/" + p + "/tasks"), "{\"taskNm\":\"" + nm + "\"" + (owner == null ? "" : ",\"ownerLoginId\":\"" + owner + "\"")
                + ",\"planStart\":\"" + s + "\",\"planEnd\":\"" + e + "\",\"planHours\":" + hours + "}", pmo));
    }

    private void log(long user, String day, long p, int hours, boolean confirm) {
        accounts.exec(comp, """
                insert into pms_work_log (comp_cd, user_id, work_day, proj_id, category_cd, hours, created_by, updated_by)
                values (?, ?, ?::date, ?, 'DESIGN', ?, 't', 't')""", comp, user, day, p, hours);
        if (confirm) {
            accounts.exec(comp, "update pms_work_log set status = 'CONFIRMED', confirmed_at = now() where user_id = ? and work_day = ?::date",
                    user, day);
        }
    }

    private String load(RequestPostProcessor who, String q) throws Exception {
        MvcResult m = mvc.perform(get(L + q).with(who)).andReturn();
        assertThat(m.getResponse().getStatus()).as(m.getResponse().getContentAsString()).isEqualTo(200);
        return m.getResponse().getContentAsString();
    }

    private static Map<String, Object> rowOf(String json, String login) {
        List<Map<String, Object>> r = JsonPath.read(json, "$.data.rows[?(@.loginId == '" + login + "')]");
        assertThat(r).as(login).hasSize(1);
        return r.get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<Double> nums(Map<String, Object> row, String key) {
        return ((List<Object>) row.get(key)).stream().map(o -> ((Number) o).doubleValue()).toList();
    }

    @Test
    void 계획은_근무일에_고르게_확정만_세고_가용과_초과주() throws Exception {
        // 주 중간 날짜도 그 주 월요일부터
        String j = load(pmo, "?from=2026-03-04&weeks=3");
        assertThat((String) JsonPath.read(j, "$.data.from")).isEqualTo("2026-03-02");
        assertThat((String) JsonPath.read(j, "$.data.to")).isEqualTo("2026-03-22");
        assertThat((List<Integer>) JsonPath.read(j, "$.data.weeks[*].workdays")).containsExactly(4, 5, 5);
        assertThat(((List<Number>) JsonPath.read(j, "$.data.weeks[*].capHours")).stream().map(Number::intValue)).containsExactly(32, 40, 40);
        Map<String, Object> a = rowOf(j, "aaa1");
        assertThat(nums(a, "plan")).containsExactly(16.0, 20.0, 0.0);
        assertThat(nums(a, "conf")).containsExactly(6.0, 7.0, 0.0);     // 3/11 미확정 5 는 안 셈
        assertThat(((Number) a.get("planTotal")).doubleValue()).isEqualTo(36.0);
        assertThat(a.get("over")).isEqualTo(0);
        Map<String, Object> b = rowOf(j, "bbb1");
        assertThat(nums(b, "plan")).containsExactly(50.0, 0.0, 0.0);
        assertThat(nums(b, "conf")).containsExactly(0.0, 0.0, 8.0);
        assertThat(b.get("over")).isEqualTo(1);
        assertThat(nums(JsonPath.read(j, "$.data.unassigned"), "plan")).containsExactly(0.0, 4.0, 0.0);
        assertThat((Integer) JsonPath.read(j, "$.data.hoursPerDay")).isEqualTo(8);
        assertThat((Integer) JsonPath.read(j, "$.data.taskCnt")).isEqualTo(3);
        // 회사 하루 시간 10 → 가용 40·50, bbb1 50 은 첫 주 가용 40 초과 그대로
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'PMS_HOURS_PER_DAY', '10', 't', 't')",
                comp);
        String j2 = load(pmo, "?from=2026-03-02&weeks=2");
        assertThat(((List<Number>) JsonPath.read(j2, "$.data.weeks[*].capHours")).stream().map(Number::intValue)).containsExactly(40, 50);
    }

    @Test
    void 취소작업_취소프로젝트_범위밖은_빼고_프로젝트_조건() throws Exception {
        long gone = task(proj, "취소될 작업", "aaa1", "2026-03-02", "2026-03-06", "100");
        List<Map<String, Object>> rows = JsonPath.read(mvc.perform(get(P + "/" + proj + "/tasks").with(pmo)).andReturn().getResponse()
                .getContentAsString(), "$.data.tasks[?(@.taskId == " + gone + ")]");
        send(put("/api/pms/tasks/" + gone), "{\"taskNm\":\"취소될 작업\",\"ownerLoginId\":\"aaa1\",\"planStart\":\"2026-03-02\",\"planEnd\":\"2026-03-06\","
                + "\"planHours\":100,\"status\":\"CANCELLED\",\"rowVer\":" + rows.get(0).get("rowVer") + "}", pmo).andExpect(status().isOk());
        task(proj, "범위 밖", "aaa1", "2026-05-04", "2026-05-08", "40");
        long p3 = id(send(post(P), "{\"projNm\":\"ZT 취소 프로젝트\",\"projType\":\"GENERAL\"}", pmo));
        task(p3, "취소 프로젝트 작업", "aaa1", "2026-03-02", "2026-03-06", "77");
        int rv = JsonPath.read(mvc.perform(get(P + "/" + p3).with(pmo)).andReturn().getResponse().getContentAsString(), "$.data.rowVer");
        send(post(P + "/" + p3 + "/status"), "{\"status\":\"CANCELLED\",\"reason\":\"ZT 취소\",\"rowVer\":" + rv + "}", pmo).andExpect(status().isOk());
        String j = load(pmo, "?from=2026-03-02&weeks=3");
        assertThat(nums(rowOf(j, "aaa1"), "plan")).containsExactly(16.0, 20.0, 0.0);
        // 프로젝트 조건: proj2 만 → aaa1 없음(계획·확정 모두 proj), bbb1 만
        String only2 = load(pmo, "?from=2026-03-02&weeks=3&projId=" + proj2);
        assertThat((List<String>) JsonPath.read(only2, "$.data.rows[*].loginId")).containsExactly("bbb1");
        assertThat((Object) JsonPath.read(only2, "$.data.unassigned")).isNull();
    }

    @Test
    void 칸_상세는_그_주_몫_큰_순() throws Exception {
        task(proj2, "도면", "aaa1", "2026-03-09", "2026-03-10", "6");
        String j = mvc.perform(get(L + "/cell").param("userId", String.valueOf(aId)).param("week", "2026-03-11").with(pmo)).andReturn()
                .getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(j, "$.data[*].taskNm")).containsExactly("설계", "도면");
        assertThat(((List<Number>) JsonPath.read(j, "$.data[*].weekHours")).stream().map(Number::doubleValue)).containsExactly(20.0, 6.0);
        String none = mvc.perform(get(L + "/cell").param("unassigned", "true").param("week", "2026-03-09").with(pmo)).andReturn()
                .getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(none, "$.data[*].taskNm")).containsExactly("검토");
        mvc.perform(get(L + "/cell").param("week", "2026-03-09").with(pmo)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("userId"));
    }

    @Test
    void 권한은_APPROVE_만_입력검사() throws Exception {
        mvc.perform(get(L).with(pmOnly)).andExpect(status().isForbidden());
        mvc.perform(get(L + "/cell").param("userId", String.valueOf(aId)).param("week", "2026-03-02").with(pmOnly))
                .andExpect(status().isForbidden());
        mvc.perform(get(L).param("weeks", "0").with(pmo)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        mvc.perform(get(L).param("weeks", "27").with(pmo)).andExpect(jsonPath("$.errors[0].field").value("weeks"));
        mvc.perform(get(L).param("from", "2026-02-30").with(pmo)).andExpect(jsonPath("$.errors[0].field").value("from"));
        mvc.perform(get(L).param("from", "2999-12-20").with(pmo)).andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        // 기본 = 이번 주부터 12주
        assertThat((List<Object>) JsonPath.read(load(pmo, ""), "$.data.weeks")).hasSize(12);
    }

    @Test
    void A사로_B사_다른_회사_작업_공수_달력이_섞이지_않는다() throws Exception {
        String b = accounts.newComp("PX");
        long bPmo = accounts.newUser(b, "pmo1", PW);
        long bA = accounts.newUser(b, "aaa1", PW);
        accounts.grant(b, bPmo, "PMO", "pms-project", "SEARCH", "SAVE", "APPROVE");
        RequestPostProcessor bpmo = accounts.as(b, bPmo, "pmo1");
        // B사는 주말 쉼 없음 — 3/2~3/13 = 12 근무일, 36시간 → 하루 3 → 1주 21, 2주 15
        long bp = id(send(post(P), "{\"projNm\":\"ZT B사\",\"projType\":\"GENERAL\"}", bpmo));
        id(send(post(P + "/" + bp + "/tasks"), "{\"taskNm\":\"B설계\",\"ownerLoginId\":\"aaa1\",\"planStart\":\"2026-03-02\",\"planEnd\":\"2026-03-13\","
                + "\"planHours\":36}", bpmo));
        String jb = load(bpmo, "?from=2026-03-02&weeks=2");
        assertThat((List<String>) JsonPath.read(jb, "$.data.rows[*].loginId")).containsExactly("aaa1");
        assertThat(nums(rowOf(jb, "aaa1"), "plan")).containsExactly(21.0, 15.0);
        assertThat((List<Integer>) JsonPath.read(jb, "$.data.weeks[*].workdays")).containsExactly(7, 7);
        assertThat(((Number) rowOf(jb, "aaa1").get("userId")).longValue()).isEqualTo(bA);
        // A사 프로젝트 id 로 조건 → B사에서는 0건, A사 사용자 id 로 칸 상세 → 0건
        String cross = load(bpmo, "?from=2026-03-02&weeks=2&projId=" + proj);
        assertThat((List<Object>) JsonPath.read(cross, "$.data.rows")).isEmpty();
        String cell = mvc.perform(get(L + "/cell").param("userId", String.valueOf(aId)).param("week", "2026-03-02").with(bpmo)).andReturn()
                .getResponse().getContentAsString();
        assertThat((List<Object>) JsonPath.read(cell, "$.data")).isEmpty();
        // A사 결과는 그대로(B사 작업·달력 없음)
        assertThat(nums(rowOf(load(pmo, "?from=2026-03-02&weeks=2"), "aaa1"), "plan")).containsExactly(16.0, 20.0);
    }
}
