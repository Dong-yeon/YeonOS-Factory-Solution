package io.smarthappy.module.pms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
 * PMS 4단계 공수·이슈 (docs/32 PM4) — 미리 채움(담당 작업·전날 시간 복사), 하루 한 벌 저장·합계 상한·미래·자릿수, 일 확정(분류 필수·0시간 정리)·다시 열기(사유),
 * 남의 공수(PM 도) 수정 불가·PM 요약 조회, 확정 행 직접 SQL 불변·24h, 이슈 등록·객체 연결(같은 회사만)·상태 전이·종료 권한·엑셀,
 * 품목으로 이슈 찾기, A사로 B사, 동시 저장.
 */
@AutoConfigureMockMvc
class PmsWorkTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/pms/projects";
    private static final String WL = "/api/pms/worklogs";
    private static final String IS = "/api/pms/issues";

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
    LocalDate today;
    long proj;
    long taskA;
    long taskB;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PW");
        pmId = accounts.newUser(comp, "pm1", PW);
        memId = accounts.newUser(comp, "mem1", PW);
        otherId = accounts.newUser(comp, "oth1", PW);
        for (long u : List.of(pmId, memId, otherId)) {
            accounts.grant(comp, u, "PMS" + u, "pms-worklog", "SEARCH", "SAVE");
            accounts.grant(comp, u, "PMS" + u, "pms-issue", "SEARCH", "SAVE", "EXCEL");
        }
        accounts.grant(comp, pmId, "PMS" + pmId, "pms-project", "SEARCH", "SAVE");
        pm = accounts.as(comp, pmId, "pm1");
        mem = accounts.as(comp, memId, "mem1");
        other = accounts.as(comp, otherId, "oth1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY, LocalDate.class);
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'ZT-BRKT', '브래킷', 'FG', 'MAKE', 'EA', 'Y', 't', 't')""", comp);
        proj = id(send(post(P), "{\"projNm\":\"공수 시험\",\"projType\":\"GENERAL\"}", pm));
        taskA = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"설계\",\"ownerLoginId\":\"mem1\",\"planStart\":\"" + today.minusDays(5)
                + "\",\"planEnd\":\"" + today.plusDays(5) + "\"}", pm));
        taskB = id(send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"시험\",\"ownerLoginId\":\"mem1\",\"planStart\":\"" + today.plusDays(10)
                + "\",\"planEnd\":\"" + today.plusDays(20) + "\"}", pm));
        int ver = read(mvc.perform(get(P + "/" + proj).with(pm)), "$.data.rowVer");
        send(post(P + "/" + proj + "/status"), "{\"status\":\"ACTIVE\",\"rowVer\":" + ver + "}", pm).andExpect(status().isOk());
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

    private ResultActions day(RequestPostProcessor who, LocalDate d, String rows) throws Exception {
        return send(put(WL + "/day"), "{\"day\":\"" + d + "\",\"rows\":" + rows + "}", who);
    }

    private List<Map<String, Object>> rows(RequestPostProcessor who, LocalDate d) throws Exception {
        return read(mvc.perform(get(WL).param("from", d.toString()).with(who)), "$.data.days[?(@.day == '" + d + "')].rows[*]");
    }

    private void policy(String key, String value) {
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, ?, ?, 't', 't')",
                comp, key, value);
    }

    // ------------------------------------------------------------------ 계획 공수·소진율 (docs/48 R-2)

    /** 작업 수정 — 지금 값에 바꿀 칸만 덮어쓴다(JSON 조각) */
    private ResultActions putTask(long taskId, String extra, RequestPostProcessor who) throws Exception {
        Map<String, Object> t = taskRow(taskId);
        String body = "{\"taskNm\":\"" + t.get("taskNm") + "\",\"taskType\":\"" + t.get("taskType") + "\",\"ownerLoginId\":\""
                + (t.get("ownerLoginId") == null ? "" : t.get("ownerLoginId")) + "\",\"planStart\":\"" + t.get("planStart")
                + "\",\"planEnd\":\"" + t.get("planEnd") + "\",\"rowVer\":" + t.get("rowVer") + extra + "}";
        return send(put("/api/pms/tasks/" + taskId), body, who);
    }

    private Map<String, Object> taskRow(long taskId) throws Exception {
        List<Map<String, Object>> r = read(mvc.perform(get(P + "/" + proj + "/tasks").with(pm)), "$.data.tasks[?(@.taskId == " + taskId + ")]");
        return nums(r.get(0));
    }

    private Map<String, Object> effort() throws Exception {
        return nums(read(mvc.perform(get(P + "/" + proj + "/tasks").with(pm)), "$.data.effort"));
    }

    /** JSON 숫자(3 · 3.0)를 double 로 맞춘다 — 원천 소수 자릿수 차이는 값이 아니다(hoursPerDay·burnPct 정수도 double) */
    private static Map<String, Object> nums(Map<String, Object> m) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        m.forEach((k, v) -> out.put(k, v instanceof Number n ? (Object) n.doubleValue() : v));
        return out;
    }

    @Test
    void 계획_공수_소진율_확정만_미확정_따로_다시열기_하루시간_정책_요약_권한_A사로B사() throws Exception {
        accounts.grant(comp, pmId, "PMS" + pmId, "pms-home", "SEARCH");
        accounts.grant(comp, memId, "PMS" + memId, "pms-project", "SEARCH", "SAVE");
        // 계획 공수 = 잎 작업 입력(시간), 담당자(화면 권한 있음)는 못 고침(계획은 PM)
        putTask(taskA, ",\"planHours\":10", pm).andExpect(status().isOk());
        putTask(taskB, ",\"planHours\":6", pm).andExpect(status().isOk());
        putTask(taskA, ",\"planHours\":12", mem).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PMS_NOT_EDITABLE"));
        // 이상 입력: 음수·소수 둘째 자리·상한 넘음 / 마일스톤에 공수
        putTask(taskA, ",\"planHours\":-1", pm).andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        putTask(taskA, ",\"planHours\":1.25", pm).andExpect(jsonPath("$.errors[0].code").value("SCALE"));
        putTask(taskA, ",\"planHours\":100000", pm).andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        send(post(P + "/" + proj + "/tasks"), "{\"taskNm\":\"검토\",\"taskType\":\"MILESTONE\",\"planStart\":\"" + today
                + "\",\"planHours\":2}", pm).andExpect(jsonPath("$.errors[0].code").value("PMS_TASK_ONLY"));
        // 실적: 어제 3.5h 확정, 오늘 2h 작성 중(미확정) — 소진율은 확정만
        LocalDate y = today.minusDays(1);
        day(mem, y, "[{\"projId\":" + proj + ",\"taskId\":" + taskA + ",\"categoryCd\":\"DESIGN\",\"hours\":\"3.5\"}]").andExpect(status().isOk());
        send(post(WL + "/confirm"), "{\"day\":\"" + y + "\"}", mem).andExpect(status().isOk());
        day(mem, today, "[{\"projId\":" + proj + ",\"taskId\":" + taskA + ",\"categoryCd\":\"DESIGN\",\"hours\":\"2\"},"
                + "{\"projId\":" + proj + ",\"categoryCd\":\"MEETING\",\"hours\":\"1\"}]").andExpect(status().isOk());
        Map<String, Object> e = effort();
        assertThat(e).containsEntry("planHours", 16.0).containsEntry("confHours", 3.5).containsEntry("draftHours", 3.0)
                .containsEntry("burnPct", 22.0).containsEntry("remainHours", 12.5).containsEntry("hoursPerDay", 8.0);
        assertThat(taskRow(taskA)).containsEntry("planHours", 10.0).containsEntry("confHours", 3.5).containsEntry("draftHours", 2.0);
        // 포트폴리오·보고서 같은 숫자(보고서 = 포트폴리오 한 행)
        mvc.perform(get("/api/pms/portfolio/grid").param("keyword", "공수 시험").with(pm))
                .andExpect(jsonPath("$.list[0].planHours").value(16.0)).andExpect(jsonPath("$.list[0].confHours").value(3.5))
                .andExpect(jsonPath("$.list[0].draftHours").value(3.0)).andExpect(jsonPath("$.list[0].burnPct").value(22))
                .andExpect(jsonPath("$.list[0].remainHours").value(12.5));
        mvc.perform(get(P + "/" + proj + "/report").with(pm)).andExpect(jsonPath("$.data.summary.burnPct").value(22))
                .andExpect(jsonPath("$.data.hoursPerDay").value(8));
        mvc.perform(get("/api/pms/portfolio/grid").param("sort", "burnPct,desc").with(pm)).andExpect(status().isOk());
        // 다시 열기(어제 확정 → 작성 중) 뒤: 확정 0, 미확정 6.5, 소진율 0
        send(post(WL + "/reopen"), "{\"day\":\"" + y + "\",\"reason\":\"작업 정정\"}", mem).andExpect(status().isOk());
        e = effort();
        assertThat(e).containsEntry("confHours", 0.0).containsEntry("draftHours", 6.5).containsEntry("burnPct", 0.0).containsEntry("remainHours", 16.0);
        // 하루 시간 정책 바꿈 → MD 전환 기준만 바뀐다(저장 시간은 그대로)
        policy("PMS_HOURS_PER_DAY", "6");
        assertThat(effort()).containsEntry("hoursPerDay", 6.0).containsEntry("planHours", 16.0);
        mvc.perform(get("/api/pms/portfolio/effort-unit").with(pm)).andExpect(jsonPath("$.data.hoursPerDay").value(6));
        // 요약: B 아래 작업(4h)을 넣으면 B 는 자식 합(자기 6h 대신 4h), 요약 공수를 직접 바꾸면 거부, 취소된 자식은 빠진다
        long c1 = id(send(post(P + "/" + proj + "/tasks"), "{\"parentId\":" + taskB + ",\"taskNm\":\"하위1\",\"planStart\":\""
                + today.plusDays(10) + "\",\"planEnd\":\"" + today.plusDays(12) + "\",\"planHours\":4}", pm));
        long c2 = id(send(post(P + "/" + proj + "/tasks"), "{\"parentId\":" + taskB + ",\"taskNm\":\"하위2\",\"planStart\":\""
                + today.plusDays(13) + "\",\"planEnd\":\"" + today.plusDays(14) + "\",\"planHours\":3}", pm));
        assertThat(taskRow(taskB)).containsEntry("isSummary", "Y").containsEntry("planHours", 7.0);
        putTask(taskB, ",\"planHours\":9", pm).andExpect(jsonPath("$.errors[0].code").value("PMS_SUMMARY_DERIVED"));
        putTask(c2, ",\"status\":\"CANCELLED\",\"planHours\":3", pm).andExpect(status().isOk());
        assertThat(taskRow(taskB)).containsEntry("planHours", 4.0);
        assertThat(effort()).containsEntry("planHours", 14.0);
        assertThat(c1).isPositive();
        // A사로 B사: B사 PM 은 A사 작업 공수를 못 고치고, A사 프로젝트가 B사 포트폴리오에 없다
        String b = accounts.newComp("PX");
        long bId = accounts.newUser(b, "pm1", PW);
        accounts.grant(b, bId, "PMS", "pms-project", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(b, bId, "PMS", "pms-home", "SEARCH");
        RequestPostProcessor bpm = accounts.as(b, bId, "pm1");
        Map<String, Object> t = taskRow(taskA);
        send(put("/api/pms/tasks/" + taskA), "{\"taskNm\":\"설계\",\"planStart\":\"" + t.get("planStart") + "\",\"planEnd\":\""
                + t.get("planEnd") + "\",\"rowVer\":" + t.get("rowVer") + ",\"planHours\":99}", bpm).andExpect(status().isNotFound());
        mvc.perform(get("/api/pms/portfolio/grid").with(bpm)).andExpect(jsonPath("$.list.length()").value(0));
        assertThat(taskRow(taskA)).containsEntry("planHours", 10.0);
    }

    @Test
    void 템플릿_표준_공수는_프로젝트_잎_작업_계획_공수로_마일스톤엔_못넣음() throws Exception {
        accounts.grant(comp, pmId, "PMS" + pmId, "pms-template", "SEARCH", "SAVE");
        String T = "/api/pms/templates";
        long tm = id(send(post(T), "{\"tmplCd\":\"eff\",\"tmplNm\":\"공수 템플릿\",\"projType\":\"GENERAL\",\"anchor\":\"START\"}", pm));
        long ph = id(send(post(T + "/" + tm + "/tasks"), "{\"taskNm\":\"단계\",\"offsetDays\":0,\"durationDays\":1,\"stdHours\":99}", pm));
        id(send(post(T + "/" + tm + "/tasks"), "{\"parentId\":" + ph + ",\"taskNm\":\"설계\",\"offsetDays\":0,\"durationDays\":5,\"stdHours\":16.5}", pm));
        id(send(post(T + "/" + tm + "/tasks"), "{\"parentId\":" + ph + ",\"taskNm\":\"검토\",\"offsetDays\":5,\"durationDays\":2}", pm));
        send(post(T + "/" + tm + "/tasks"), "{\"parentId\":" + ph + ",\"taskNm\":\"승인\",\"taskType\":\"GATE\",\"offsetDays\":8,\"stdHours\":2}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("PMS_TASK_ONLY"));
        long p2 = id(send(post(P), "{\"projNm\":\"템플릿 공수\",\"projType\":\"GENERAL\",\"tmplCd\":\"eff\",\"startDate\":\"" + today + "\"}", pm));
        List<Map<String, Object>> ts = read(mvc.perform(get(P + "/" + p2 + "/tasks").with(pm)), "$.data.tasks");
        // 잎 작업만 표준 → 계획(요약에 넣은 99 는 쓰지 않고 자식 합), 표준 없는 잎은 비움
        assertThat(ts).extracting(x -> x.get("taskNm") + "=" + x.get("planHours"))
                .containsExactly("단계=16.5", "설계=16.5", "검토=null");
        assertThat(nums(read(mvc.perform(get(P + "/" + p2 + "/tasks").with(pm)), "$.data.effort")))
                .containsEntry("planHours", 16.5).containsEntry("burnPct", 0.0);
    }

    // ------------------------------------------------------------------ 공수

    @Test
    void 미리_채움_한_벌_저장_확정_다시_열기() throws Exception {
        LocalDate y = today.minusDays(1);
        // 어제: 직접 입력 3.5h(분류 설계) → 확정
        day(mem, y, "[{\"projId\":" + proj + ",\"taskId\":" + taskA + ",\"categoryCd\":\"design\",\"hours\":\"3.5\"}]").andExpect(status().isOk());
        assertThat(id(send(post(WL + "/confirm"), "{\"day\":\"" + y + "\"}", mem))).isEqualTo(1);
        // 오늘 미리 채움: 담당 진행 가능 작업 A(계획 기간 안) 만, B(미래 계획)는 아님 → 어제 시간·분류 복사
        mvc.perform(get(WL).with(mem)).andExpect(jsonPath("$.data.suggestions.length()").value(1))
                .andExpect(jsonPath("$.data.suggestions[0].taskId").value(taskA));
        assertThat(id(send(post(WL + "/prefill"), "{\"day\":\"" + today + "\"}", mem))).isEqualTo(1);
        assertThat(id(send(post(WL + "/prefill"), "{\"day\":\"" + today + "\"}", mem))).isZero();     // 두 번 눌러도 한 행
        Map<String, Object> r = rows(mem, today).get(0);
        assertThat(r.get("hours")).isEqualTo(3.5);
        assertThat(r.get("categoryCd")).isEqualTo("DESIGN");
        assertThat(r.get("entryMethod")).isEqualTo("PREFILL");
        // 시간을 바꾸면 KEY, 분류 없는 행 추가 + 0시간 행
        day(mem, today, "[{\"wlId\":" + r.get("wlId") + ",\"taskId\":" + taskA + ",\"categoryCd\":\"DESIGN\",\"hours\":\"4\",\"rowVer\":0},"
                + "{\"projId\":" + proj + ",\"hours\":\"2\"},{\"projId\":" + proj + ",\"taskId\":" + taskB + ",\"categoryCd\":\"TEST\",\"hours\":\"0\"}]")
                .andExpect(status().isOk());
        assertThat(rows(mem, today)).extracting(x -> x.get("entryMethod")).containsExactlyInAnyOrder("KEY", "KEY", "KEY");
        // 확정: 분류 없는 행 → 행 오류(행 키 = wlId)
        ResultActions c = send(post(WL + "/confirm"), "{\"day\":\"" + today + "\"}", mem);
        c.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("categoryCd"));
        Map<String, Object> noCat = rows(mem, today).stream().filter(x -> x.get("categoryCd") == null).findFirst().orElseThrow();
        String rowsAll = rows(mem, today).stream().map(x -> "{\"wlId\":" + x.get("wlId") + ",\"taskId\":" + x.get("taskId")
                + ",\"categoryCd\":\"" + (x.get("categoryCd") == null ? "MEETING" : x.get("categoryCd")) + "\",\"hours\":\"" + x.get("hours")
                + "\",\"rowVer\":" + x.get("rowVer") + "}").reduce((a, b) -> a + "," + b).orElse("");
        assertThat(noCat).isNotNull();
        day(mem, today, "[" + rowsAll.replace("\"taskId\":null,", "") + "]").andExpect(status().isOk());
        assertThat(id(send(post(WL + "/confirm"), "{\"day\":\"" + today + "\"}", mem))).isEqualTo(2);    // 0시간 행은 지움
        mvc.perform(get(WL).param("from", today.toString()).with(mem))
                .andExpect(jsonPath("$.data.days[?(@.day == '" + today + "')].status").value(org.hamcrest.Matchers.hasItem("CONFIRMED")))
                .andExpect(jsonPath("$.data.days[?(@.day == '" + today + "')].total").value(org.hamcrest.Matchers.hasItem("6")));
        // 확정된 날 저장·미리 채움 → 409, 다시 열기 사유 필수 → 작성 중, 확정 안 된 날 다시 열기 409
        day(mem, today, "[]").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PMS_DAY_CONFIRMED"));
        send(post(WL + "/prefill"), "{\"day\":\"" + today + "\"}", mem).andExpect(jsonPath("$.code").value("PMS_DAY_CONFIRMED"));
        send(post(WL + "/reopen"), "{\"day\":\"" + today + "\"}", mem).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        assertThat(id(send(post(WL + "/reopen"), "{\"day\":\"" + today + "\",\"reason\":\"회의 시간 정정\"}", mem))).isEqualTo(2);
        assertThat(rows(mem, today)).allSatisfy(x -> assertThat(x.get("reopenReason")).isEqualTo("회의 시간 정정"));
        send(post(WL + "/reopen"), "{\"day\":\"" + today + "\",\"reason\":\"x\"}", mem).andExpect(jsonPath("$.code").value("PMS_DAY_NOT_CONFIRMED"));
        // PM 은 요약만(남의 공수 조회), 확정 3.5(어제) + 작성 중 6
        mvc.perform(get(P + "/" + proj + "/worklog-summary").with(pm)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.categoryCd == 'DESIGN')].confirmedHours").value(org.hamcrest.Matchers.hasItem(3.5)));
    }

    @Test
    void 공수_입력_규칙_합계_미래_자릿수_남의_행() throws Exception {
        String base = "{\"projId\":" + proj + ",\"taskId\":" + taskA + ",\"categoryCd\":\"DESIGN\",\"hours\":\"";
        policy("PMS_DAY_MAX_HOURS", "10");
        day(mem, today, "[" + base + "8\"},{\"projId\":" + proj + ",\"categoryCd\":\"MEETING\",\"hours\":\"3\"}]")
                .andExpect(jsonPath("$.errors[0].code").value("PMS_DAY_OVER"));
        day(mem, today.plusDays(1), "[" + base + "1\"}]").andExpect(jsonPath("$.errors[0].code").value("FUTURE"));
        day(mem, today, "[" + base + "-1\"}]").andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        day(mem, today, "[" + base + "1.25\"}]").andExpect(jsonPath("$.errors[0].code").value("SCALE"));
        day(mem, today, "[" + base + "abc\"}]").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        day(mem, today, "[" + base.replace("DESIGN", "NOPE") + "1\"}]").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        day(mem, today, "[" + base + "1\"}," + base + "2\"}]").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        day(mem, today, "[{\"projId\":999999,\"hours\":\"1\"}]").andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        day(mem, today, "[" + base + "7.5\"}]").andExpect(status().isOk());
        long wl = ((Number) rows(mem, today).get(0).get("wlId")).longValue();
        // 남의 행(PM 도): 그 wlId 로 저장하면 404 — 본인 그 날 행만
        day(pm, today, "[{\"wlId\":" + wl + ",\"hours\":\"1\",\"rowVer\":0}]").andExpect(status().isNotFound());
        assertThat(rows(pm, today)).isEmpty();
        // 낡은 rowVer, 회사 정책 상한 안의 회사 분류 코드
        day(mem, today, "[{\"wlId\":" + wl + ",\"hours\":\"1\",\"rowVer\":5}]").andExpect(status().isConflict());
        accounts.exec(comp, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by) values (?, 'PMS_WORK_CAT', 'CUSTVISIT', '고객 방문', 't', 't')", comp);
        day(mem, today, "[{\"wlId\":" + wl + ",\"taskId\":" + taskA + ",\"categoryCd\":\"custvisit\",\"hours\":\"2\",\"rowVer\":0}]").andExpect(status().isOk());
        // 닫힌(취소) 프로젝트에는 새 행을 못 넣는다
        int ver = read(mvc.perform(get(P + "/" + proj).with(pm)), "$.data.rowVer");
        send(post(P + "/" + proj + "/status"), "{\"status\":\"CANCELLED\",\"reason\":\"시험\",\"rowVer\":" + ver + "}", pm).andExpect(status().isOk());
        day(mem, today.minusDays(2), "[" + base + "1\"}]").andExpect(jsonPath("$.errors[0].code").value("PMS_PROJECT_CLOSED"));
    }

    @Test
    void 직접_SQL_확정_행_불변과_24시간_상한과_A사로_B사() throws Exception {
        day(mem, today, "[{\"projId\":" + proj + ",\"taskId\":" + taskA + ",\"categoryCd\":\"DESIGN\",\"hours\":\"8\"}]").andExpect(status().isOk());
        send(post(WL + "/confirm"), "{\"day\":\"" + today + "\"}", mem).andExpect(status().isOk());
        Throwable t1 = catchThrowable(() -> accounts.exec(comp, "update pms_work_log set hours = 9 where user_id = ?", memId));
        assertThat(PgErrors.constraint(t1)).hasValue("ck_pms_work_log_fixed");
        Throwable t2 = catchThrowable(() -> accounts.exec(comp, "delete from pms_work_log where user_id = ?", memId));
        assertThat(PgErrors.constraint(t2)).hasValue("ck_pms_work_log_fixed");
        Throwable t3 = catchThrowable(() -> accounts.exec(comp, """
                insert into pms_work_log (comp_cd, user_id, work_day, proj_id, hours, created_by, updated_by)
                values (?, ?, ?, ?, 17, 't', 't')""", comp, memId, today, proj));
        assertThat(PgErrors.constraint(t3)).hasValue("ck_pms_work_log_day_sum");
        Throwable t4 = catchThrowable(() -> accounts.exec(comp, """
                insert into pms_work_log (comp_cd, user_id, work_day, proj_id, hours, status, created_by, updated_by)
                values (?, ?, ?, ?, 1, 'CONFIRMED', 't', 't')""", comp, memId, today.minusDays(3), proj));
        assertThat(PgErrors.constraint(t4)).hasValue("ck_pms_work_log_fixed");
        // B사
        String b = accounts.newComp("PX");
        long bId = accounts.newUser(b, "mem1", PW);
        accounts.grant(b, bId, "ALL", "pms-worklog", "SEARCH", "SAVE");
        accounts.grant(b, bId, "ALL", "pms-project", "SEARCH", "SAVE");
        RequestPostProcessor bu = accounts.as(b, bId, "mem1");
        long wl = ((Number) rows(mem, today).get(0).get("wlId")).longValue();
        assertThat(rows(bu, today)).isEmpty();
        day(bu, today, "[{\"wlId\":" + wl + ",\"hours\":\"1\",\"rowVer\":0}]").andExpect(status().isNotFound());
        day(bu, today, "[{\"projId\":" + proj + ",\"hours\":\"1\"}]").andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        mvc.perform(get(P + "/" + proj + "/worklog-summary").with(bu)).andExpect(status().isNotFound());
        mvc.perform(get(WL + "/projects/" + proj + "/tasks").with(bu)).andExpect(status().isNotFound());
        Throwable t5 = catchThrowable(() -> accounts.exec(b, """
                insert into pms_work_log (comp_cd, user_id, work_day, proj_id, hours, created_by, updated_by)
                values (?, ?, ?, ?, 1, 't', 't')""", b, bId, today, proj));
        assertThat(PgErrors.sqlState(t5)).hasValueSatisfying(s -> assertThat(s).isIn("23503", "42501"));
    }

    @Test
    void 같은_날_동시_저장은_한_벌씩_줄_선다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Callable<Integer> a = () -> mvc.perform(json(put(WL + "/day"), "{\"day\":\"" + today + "\",\"rows\":[{\"projId\":" + proj
                    + ",\"taskId\":" + taskA + ",\"categoryCd\":\"DESIGN\",\"hours\":\"8\"}]}").with(mem)).andReturn().getResponse().getStatus();
            Callable<Integer> c = () -> mvc.perform(json(put(WL + "/day"), "{\"day\":\"" + today + "\",\"rows\":[{\"projId\":" + proj
                    + ",\"categoryCd\":\"MEETING\",\"hours\":\"9\"}]}").with(mem)).andReturn().getResponse().getStatus();
            for (Future<Integer> f : pool.invokeAll(List.of(a, c, a, c))) {
                assertThat(f.get()).isIn(200, 409);
            }
        } finally {
            pool.shutdownNow();
        }
        // 마지막 한 벌만 남는다(섞이지 않음): 8 또는 9 한 행
        List<Map<String, Object>> rs = rows(mem, today);
        assertThat(rs).hasSize(1);
        assertThat(((Number) rs.get(0).get("hours")).doubleValue()).isIn(8.0, 9.0);
    }

    // ------------------------------------------------------------------ 이슈

    @Test
    void 이슈_등록_객체_연결_상태_전이_종료_권한_엑셀() throws Exception {
        long is = id(send(post(IS), "{\"issueType\":\"DEFECT\",\"projId\":" + proj + ",\"taskId\":" + taskA + ",\"title\":\"금형 견적 지연\",\"severity\":\"high\","
                + "\"ownerLoginId\":\"mem1\",\"dueDate\":\"" + today.minusDays(1) + "\",\"objType\":\"ITEM\",\"objCd\":\"zt-brkt\"}", other));
        mvc.perform(get(IS + "/" + is).with(mem)).andExpect(jsonPath("$.data.issueNo").value(org.hamcrest.Matchers.startsWith("IS-")))
                .andExpect(jsonPath("$.data.objCd").value("ZT-BRKT")).andExpect(jsonPath("$.data.late").value(true))
                .andExpect(jsonPath("$.data.canEdit").value(true)).andExpect(jsonPath("$.data.canClose").value(false));
        // 품목으로 찾기(품목 화면 링크), 없는 품목 = 빈 목록, 내 이슈
        mvc.perform(get(IS).param("objType", "ITEM").param("objCd", "ZT-BRKT").with(pm)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(IS).param("objType", "ITEM").param("objCd", "NONE").with(pm)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(IS).param("mine", "true").with(pm)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(IS).param("mine", "true").with(mem)).andExpect(jsonPath("$.data.length()").value(1));
        // 입력 오류: 없는 품목·유형 없이 코드·없는 심각도·다른 프로젝트 작업·제목 없음
        send(post(IS), "{\"issueType\":\"DEFECT\",\"projId\":" + proj + ",\"title\":\"x\",\"objType\":\"ITEM\",\"objCd\":\"NO-ITEM\"}", other)
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        send(post(IS), "{\"issueType\":\"DEFECT\",\"projId\":" + proj + ",\"title\":\"x\",\"objCd\":\"ZT-BRKT\"}", other).andExpect(jsonPath("$.errors[0].field").value("objType"));
        send(post(IS), "{\"issueType\":\"DEFECT\",\"projId\":" + proj + ",\"severity\":\"URGENT\",\"taskId\":999999}", other).andExpect(jsonPath("$.errors.length()").value(3));
        // 전이: 열림 → 종료 바로 불가 / 해결 사유 필수 / 담당이 해결 / 담당은 종료 못 함 / PM 종료 / 종료 뒤 수정 불가
        int v = read(mvc.perform(get(IS + "/" + is).with(mem)), "$.data.rowVer");
        send(post(IS + "/" + is + "/status"), "{\"status\":\"CLOSED\",\"rowVer\":" + v + "}", pm).andExpect(jsonPath("$.code").value("PMS_ISSUE_STATE"));
        send(post(IS + "/" + is + "/status"), "{\"status\":\"RESOLVED\",\"rowVer\":" + v + "}", mem).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        send(post(IS + "/" + is + "/status"), "{\"status\":\"RESOLVED\",\"resolution\":\"대체 업체 견적 확보\",\"rowVer\":" + v + "}", mem)
                .andExpect(status().isOk());
        send(post(IS + "/" + is + "/status"), "{\"status\":\"CLOSED\",\"rowVer\":" + (v + 1) + "}", mem).andExpect(status().isForbidden());
        // 관계없는 사람(등록자도 담당도 PM 도 아님)은 못 고친다
        long is2 = id(send(post(IS), "{\"issueType\":\"DEFECT\",\"projId\":" + proj + ",\"title\":\"작은 일\"}", mem));
        send(put(IS + "/" + is2), "{\"issueType\":\"DEFECT\",\"title\":\"남이 고침\",\"rowVer\":0}", other).andExpect(status().isForbidden());
        send(post(IS + "/" + is + "/status"), "{\"status\":\"CLOSED\",\"rowVer\":" + (v + 1) + "}", pm).andExpect(status().isOk());
        send(put(IS + "/" + is), "{\"issueType\":\"DEFECT\",\"title\":\"바꿈\",\"rowVer\":" + (v + 2) + "}", pm).andExpect(jsonPath("$.code").value("PMS_ISSUE_STATE"));
        mvc.perform(get(IS).with(pm)).andExpect(jsonPath("$.data.length()").value(1));                    // 기본 = 열린 셋
        mvc.perform(get(IS).param("status", "ALL").with(pm)).andExpect(jsonPath("$.data.length()").value(2));
        MvcResult x = mvc.perform(json(post(IS + "/export"), "{\"status\":\"ALL\"}").with(pm)).andExpect(status().isOk()).andReturn();
        assertThat(x.getResponse().getContentType()).contains("spreadsheetml");
        // 직접 SQL: 닫힌 이슈 수정·삭제
        Throwable t1 = catchThrowable(() -> accounts.exec(comp, "update pms_issue set title = 'x' where issue_id = ?", is));
        assertThat(PgErrors.constraint(t1)).hasValue("ck_pms_issue_closed");
        Throwable t2 = catchThrowable(() -> accounts.exec(comp, "delete from pms_issue where issue_id = ?", is2));
        assertThat(t2).isNotNull();
    }

    @Test
    void A사로_B사_이슈_조회_수정_전이_불가_B사_품목_연결_불가() throws Exception {
        long is = id(send(post(IS), "{\"issueType\":\"DEFECT\",\"projId\":" + proj + ",\"title\":\"A사 이슈\"}", pm));
        String b = accounts.newComp("PY");
        long bId = accounts.newUser(b, "pm1", PW);
        accounts.grant(b, bId, "ALL", "pms-issue", "SEARCH", "SAVE", "EXCEL");
        accounts.grant(b, bId, "ALL", "pms-project", "SEARCH", "SAVE", "APPROVE");
        RequestPostProcessor bu = accounts.as(b, bId, "pm1");
        accounts.exec(b, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'B-ONLY', 'B 품목', 'FG', 'MAKE', 'EA', 'Y', 't', 't')""", b);
        mvc.perform(get(IS + "/" + is).with(bu)).andExpect(status().isNotFound());
        send(put(IS + "/" + is), "{\"issueType\":\"DEFECT\",\"title\":\"x\",\"rowVer\":0}", bu).andExpect(status().isNotFound());
        send(post(IS + "/" + is + "/status"), "{\"status\":\"IN_PROGRESS\",\"rowVer\":0}", bu).andExpect(status().isNotFound());
        send(post(IS), "{\"issueType\":\"DEFECT\",\"projId\":" + proj + ",\"title\":\"x\"}", bu).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        mvc.perform(get(IS).param("status", "ALL").with(bu)).andExpect(jsonPath("$.data.length()").value(0));
        // A사 사용자가 B사 품목 코드로 연결 → 안 보이므로 "없는 코드"
        send(put(IS + "/" + is), "{\"issueType\":\"DEFECT\",\"title\":\"A사 이슈\",\"objType\":\"ITEM\",\"objCd\":\"B-ONLY\",\"rowVer\":0}", pm)
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        assertThat(accounts.query(comp, "select title from pms_issue where issue_id = ?", String.class, is)).isEqualTo("A사 이슈");
    }
}
