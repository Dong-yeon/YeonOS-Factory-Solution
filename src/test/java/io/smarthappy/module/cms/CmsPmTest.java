package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

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
 * CMS 1부 C2 (docs/33 4.3) — 점검표 한 벌 저장, 예방점검 계획·예정 생성(멱등·정기 작업), 점검 결과·판정 경계, 작업지시 완료와 함께 끝나는
 * PM, CHECK 방식 완료·건너뛰기, NG 후속, 직접 SQL, 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class CmsPmTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String T = "/api/cms/chk-tmpls";
    private static final String P = "/api/cms/pm-plans";
    private static final String R = "/api/cms/chk-runs";
    private static final String M = "/api/cms/mwos";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    CmsJobs jobs;

    String comp;
    long userId;
    RequestPostProcessor admin;
    String today;
    long tmpl;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PM");
        userId = accounts.newUser(comp, "admin1", PW);
        grantAll(comp, userId);
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long weld = equipClass(comp, "WELD");
        long press = equipClass(comp, "PRESS");
        equip(comp, "P1", "SPOT-01", weld);
        equip(comp, "P1", "SPOT-02", weld);
        equip(comp, "P1", "PRS-01", press);
        asset(comp, "M-100", "MOLD");
        asset(comp, "G-100", "GAUGE");
        today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        // 용접기 월간 점검표: 1 팁 직경(수치 4.0~5.0, 1자리) 2 냉각수 누수(OK/NG) 3 메모(글, 선택)
        tmpl = id(postJson(T, "{\"tmplCd\":\"weld-m\",\"tmplNm\":\"용접기 월간\",\"equipClassCd\":\"WELD\"}"));
        putJson(T + "/" + tmpl + "/items?rowVer=0", """
                [{"seq":1,"itemNm":"팁 직경","judgeType":"NUM","lsl":"4.0","usl":"5.0","unitNm":"mm","scale":1},
                 {"seq":2,"itemNm":"냉각수 누수","judgeType":"OKNG"},
                 {"seq":3,"itemNm":"메모","judgeType":"TEXT","requiredYn":"N"}]""").andExpect(status().isOk());
    }

    private void grantAll(String c, long u) {
        accounts.grant(c, u, "CMS", "cms-chk-tmpl", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-pm-plan", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-chk-run", "SEARCH", "SAVE", "DELETE");
        accounts.grant(c, u, "CMS", "cms-mwo", "SEARCH", "SAVE", "APPROVE", "DELETE");
    }

    private long equipClass(String c, String cd) {
        return accounts.query(c, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, ?, ?, 't', 't') returning class_id",
                Long.class, c, cd, cd);
    }

    private long equip(String c, String plant, String cd, long classId) {
        return accounts.query(c, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                values (?, ?, ?, ?, ?, 't', 't') returning equip_id""", Long.class, c, plant, cd, cd + " 설비", classId);
    }

    private long asset(String c, String cd, String type) {
        return accounts.query(c, """
                insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, created_by, updated_by)
                values (?, ?, ?, ?, 't', 't') returning asset_id""", Long.class, c, cd, type, cd);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(admin));
    }

    private ResultActions putJson(String url, String body) throws Exception {
        return mvc.perform(json(put(url), body).with(admin));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        Object v = JsonPath.read(m.getResponse().getContentAsString(), "$.data");
        return v instanceof Number n ? n.longValue() : ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.mwoId")).longValue();
    }

    private <V> V read(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), path);
    }

    private long plan(String cd, String target, String mode, String unit, int n, String basis, String anchor) throws Exception {
        return id(postJson(P, "{\"planCd\":\"" + cd + "\",\"planNm\":\"" + cd + " 점검\",\"targetCd\":\"" + target + "\",\"tmplCd\":\"WELD-M\","
                + "\"execMode\":\"" + mode + "\",\"calUnit\":\"" + unit + "\",\"calInterval\":" + n + ",\"scheduleBasis\":\"" + basis
                + "\",\"anchorDate\":\"" + anchor + "\"}"));
    }

    private long runOf(long planId) {
        return accounts.query(comp, "select run_id from cms_chk_run where plan_id = ? and status = 'OPEN'", Long.class, planId);
    }

    private int runVer(long runId) {
        return accounts.query(comp, "select row_ver from cms_chk_run where run_id = ?", Integer.class, runId);
    }

    // ---------- 점검표 ----------

    @Test
    void 점검표_항목_한벌저장_규격_자릿수_순번중복_다른점검표id_동시편집_고정칸() throws Exception {
        mvc.perform(get(T + "/" + tmpl + "/items").with(admin)).andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].lslText").value("4.0"));   // 항목 자릿수(1)로
        // 수치인데 규격 없음 / 하한 ≥ 상한 / 자릿수 넘침 / OKNG 에 규격 / 순번 중복 / 판정 형식
        putJson(T + "/" + tmpl + "/items?rowVer=1", """
                [{"seq":1,"itemNm":"a","judgeType":"NUM"},
                 {"seq":2,"itemNm":"b","judgeType":"NUM","lsl":"5","usl":"5"},
                 {"seq":3,"itemNm":"c","judgeType":"NUM","lsl":"1.25","scale":1},
                 {"seq":4,"itemNm":"d","judgeType":"OKNG","usl":"1"},
                 {"seq":4,"itemNm":"","judgeType":"BOGUS"}]""")
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("CHK_LIMIT", "CHK_RANGE", "SCALE", "CHK_NO_SPEC",
                        "DUPLICATE", "REQUIRED", "CODE")))
                .andExpect(jsonPath("$.errors[?(@.code == 'DUPLICATE')].rowKey").value("4"));
        // 다른 점검표의 항목 id → 400
        long other = id(postJson(T, "{\"tmplCd\":\"PRS-D\",\"tmplNm\":\"프레스 일상\"}"));
        putJson(T + "/" + other + "/items?rowVer=0", "[{\"seq\":1,\"itemNm\":\"x\"}]").andExpect(status().isOk());
        long otherItem = accounts.query(comp, "select item_id from cms_chk_tmpl_item where tmpl_id = ?", Long.class, other);
        putJson(T + "/" + tmpl + "/items?rowVer=1", "[{\"itemId\":" + otherItem + ",\"seq\":1,\"itemNm\":\"x\"}]").andExpect(status().isBadRequest());
        // 옛 rowVer → 409, 순번 바꾸기(1↔2 — 미뤄진 유일 제약) + 3 삭제
        long i1 = accounts.query(comp, "select item_id from cms_chk_tmpl_item where tmpl_id = ? and seq = 1", Long.class, tmpl);
        long i2 = accounts.query(comp, "select item_id from cms_chk_tmpl_item where tmpl_id = ? and seq = 2", Long.class, tmpl);
        putJson(T + "/" + tmpl + "/items?rowVer=0", "[]").andExpect(status().isConflict());
        putJson(T + "/" + tmpl + "/items?rowVer=1", "[{\"itemId\":" + i1 + ",\"seq\":2,\"itemNm\":\"팁 직경\",\"judgeType\":\"NUM\",\"lsl\":\"4\",\"usl\":\"5\",\"scale\":1},"
                + "{\"itemId\":" + i2 + ",\"seq\":1,\"itemNm\":\"냉각수 누수\"}]").andExpect(status().isOk());
        mvc.perform(get(T + "/" + tmpl + "/items").with(admin)).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].itemNm").value("냉각수 누수"));
        // 코드·대상 종류 고정, 금형 점검표에 설비 클래스
        putJson(T + "/" + tmpl, "{\"tmplCd\":\"X\",\"tmplNm\":\"x\",\"targetKind\":\"ASSET\",\"rowVer\":2}")
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("FIXED", "FIXED")));
        postJson(T, "{\"tmplCd\":\"MOLD-W\",\"tmplNm\":\"금형\",\"targetKind\":\"ASSET\",\"equipClassCd\":\"WELD\"}")
                .andExpect(jsonPath("$.errors[0].code").value("CODE"));
        postJson(T, "{\"tmplCd\":\"WELD-M\",\"tmplNm\":\"x\"}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from cms_chk_tmpl where tmpl_id = ?", tmpl)))).contains("42501");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_chk_tmpl set tmpl_cd = 'Z' where tmpl_id = ?", tmpl))))
                .contains("ck_cms_chk_tmpl_fixed");
    }

    // ---------- 계획 → 예정 → 작업지시 완료 ----------

    @Test
    void WO계획_예정생성은_멱등_결과판정_경계_완료때_필수_다음예정_NG후속() throws Exception {
        long pl = plan("PM-SPOT1", "spot-01", "WO", "MONTH", 1, "FIXED", today);
        mvc.perform(get(P + "/" + pl).with(admin)).andExpect(jsonPath("$.data.nextDueDate").value(today))
                .andExpect(jsonPath("$.data.state").value("DUE")).andExpect(jsonPath("$.data.effLeadDays").value(7));
        postJson(P + "/generate", "{}").andExpect(jsonPath("$.data.runs").value(1)).andExpect(jsonPath("$.data.workOrders").value(1));
        postJson(P + "/generate", "{}").andExpect(jsonPath("$.data.runs").value(0));          // 멱등
        jobs.pmGenerate();                                                                   // 정기 작업도 같은 결과
        assertThat(accounts.query(comp, "select count(*) from cms_chk_run", Integer.class)).isEqualTo(1);
        long mwo = accounts.query(comp, "select mwo_id from cms_mwo where pm_plan_id = ?", Long.class, pl);
        mvc.perform(get(M + "/" + mwo).with(admin)).andExpect(jsonPath("$.data.mwoType").value("PM"))
                .andExpect(jsonPath("$.data.status").value("OPEN")).andExpect(jsonPath("$.data.title").value("PM-SPOT1 점검"))
                .andExpect(jsonPath("$.data.dueDate").value(today));
        mvc.perform(get(M + "/" + mwo + "/run").with(admin)).andExpect(jsonPath("$.data.results.length()").value(3))
                .andExpect(jsonPath("$.data.status").value("OPEN"));
        long run = runOf(pl);
        // 점검표를 바꿔도 이미 만든 실행은 스냅샷 그대로
        putJson(T + "/" + tmpl + "/items?rowVer=1", "[{\"seq\":1,\"itemNm\":\"바뀐 항목\"}]").andExpect(status().isOk());
        mvc.perform(get(R + "/" + run).with(admin)).andExpect(jsonPath("$.data.results[0].itemNm").value("팁 직경"));
        // 판정: 상한 경계 5.0 = OK, 5.1 = NG, 자릿수 넘침·형식·OKNG 아닌 값
        putJson(M + "/" + mwo + "/run/results", "{\"rowVer\":0,\"results\":[{\"seq\":1,\"value\":\"5.0\"},{\"seq\":2,\"judge\":\"OK\"}]}")
                .andExpect(status().isOk());
        mvc.perform(get(R + "/" + run).with(admin)).andExpect(jsonPath("$.data.results[0].judge").value("OK"))
                .andExpect(jsonPath("$.data.results[0].valueNumText").value("5.0"));
        putJson(M + "/" + mwo + "/run/results", "{\"rowVer\":1,\"results\":[{\"seq\":1,\"value\":\"5.05\"},{\"seq\":2,\"judge\":\"MAYBE\"},"
                + "{\"seq\":3,\"valueText\":\"앞\\u0001뒤\"}]}").andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("SCALE", "CODE", "FORMAT")));
        putJson(M + "/" + mwo + "/run/results", "{\"rowVer\":1,\"results\":[{\"seq\":9,\"value\":\"1\"}]}").andExpect(status().isBadRequest());
        putJson(M + "/" + mwo + "/run/results", "{\"rowVer\":0,\"results\":[{\"seq\":1,\"value\":\"5.1\"}]}").andExpect(status().isConflict());
        putJson(M + "/" + mwo + "/run/results", "{\"rowVer\":1,\"results\":[{\"seq\":1,\"value\":\"5.1\"},{\"seq\":2}]}").andExpect(status().isOk());
        // 착수 → 완료: 필수 결과(2 누수) 없음 → 칸 오류, 작업지시도 그대로
        int v = read(mvc.perform(get(M + "/" + mwo).with(admin)), "$.data.rowVer");
        postJson(M + "/" + mwo + "/start", "{\"rowVer\":" + v + "}").andExpect(status().isOk());
        postJson(M + "/" + mwo + "/finish", "{\"title\":\"PM-SPOT1 점검\",\"rowVer\":" + (v + 1) + "}")
                .andExpect(jsonPath("$.errors[0].rowKey").value("2")).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        assertThat(accounts.query(comp, "select status from cms_mwo where mwo_id = ?", String.class, mwo)).isEqualTo("WIP");
        putJson(M + "/" + mwo + "/run/results", "{\"rowVer\":2,\"results\":[{\"seq\":2,\"judge\":\"OK\"}]}").andExpect(status().isOk());
        postJson(M + "/" + mwo + "/finish", "{\"title\":\"PM-SPOT1 점검\",\"rowVer\":" + (v + 1) + "}").andExpect(status().isOk());
        mvc.perform(get(R + "/" + run).with(admin)).andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.result").value("NG")).andExpect(jsonPath("$.data.onTime").value(true))
                .andExpect(jsonPath("$.data.ngCnt").value(1));
        String nextMonth = LocalDate.parse(today).plusMonths(1).toString();
        mvc.perform(get(P + "/" + pl).with(admin)).andExpect(jsonPath("$.data.nextDueDate").value(nextMonth))
                .andExpect(jsonPath("$.data.lastDoneDate").value(today)).andExpect(jsonPath("$.data.openRunId").doesNotExist());
        postJson(P + "/generate", "{}").andExpect(jsonPath("$.data.runs").value(0));   // 다음 예정은 선행일 밖
        // 끝난 점검의 결과는 못 바꾼다(API·직접 SQL)
        putJson(R + "/" + run + "/results", "{\"rowVer\":4,\"results\":[{\"seq\":1,\"value\":\"4.5\"}]}").andExpect(jsonPath("$.code").value("CMS_RUN_STATE"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_chk_result set value_num = 4.5, judge = 'OK' where run_id = ? and seq = 1", run))))
                .contains("ck_cms_chk_result_status");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_chk_run set result = 'OK' where run_id = ?", run))))
                .contains("ck_cms_chk_run_done_fixed");
        // NG 후속 → 고장 수리, 상위 = PM 작업지시, 출처 = 점검. NG 아닌 항목은 NOT_NG
        long fu = id(postJson(R + "/" + run + "/follow-up", "{\"seq\":1}"));
        mvc.perform(get(M + "/" + fu).with(admin)).andExpect(jsonPath("$.data.mwoType").value("BM"))
                .andExpect(jsonPath("$.data.parentMwoId").value(mwo)).andExpect(jsonPath("$.data.srcRunId").value(run))
                .andExpect(jsonPath("$.data.symptomText").value("PM-SPOT1 점검 · 팁 직경 = 5.1 mm"));
        postJson(R + "/" + run + "/follow-up", "{\"seq\":2}").andExpect(jsonPath("$.errors[0].code").value("NOT_NG"));
    }

    @Test
    void CHECK방식은_실행만_완료와_건너뛰기_WO실행은_여기서_못끝냄_PM취소는_건너뛰기() throws Exception {
        long daily = plan("D-SPOT2", "SPOT-02", "CHECK", "DAY", 1, "FLOAT", today);
        long wo = plan("M-SPOT1", "SPOT-01", "WO", "MONTH", 1, "FIXED", today);
        postJson(P + "/generate", "{}").andExpect(jsonPath("$.data.runs").value(2)).andExpect(jsonPath("$.data.workOrders").value(1));
        long dr = runOf(daily);
        long wr = runOf(wo);
        mvc.perform(get(R).param("from", today).param("to", today).param("execMode", "CHECK").with(admin))
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].mwoId").doesNotExist());
        // WO 방식 실행을 점검 화면에서 완료·건너뛰기 → 상태 오류
        postJson(R + "/" + wr + "/complete", "{\"rowVer\":0}").andExpect(jsonPath("$.code").value("CMS_RUN_STATE"));
        postJson(R + "/" + wr + "/skip", "{\"rowVer\":0,\"reason\":\"x\"}").andExpect(jsonPath("$.code").value("CMS_RUN_STATE"));
        // CHECK 완료: 필수 빠짐 → 행 오류 2개, 채우면 완료 → 다음 예정 = 완료일 + 1(FLOAT)
        postJson(R + "/" + dr + "/complete", "{\"rowVer\":0}").andExpect(jsonPath("$.errors.length()").value(2));
        putJson(R + "/" + dr + "/results", "{\"rowVer\":0,\"results\":[{\"seq\":1,\"value\":\"4\"},{\"seq\":2,\"judge\":\"OK\"}]}").andExpect(status().isOk());
        postJson(R + "/" + dr + "/complete", "{\"rowVer\":1}").andExpect(status().isOk());
        String tomorrow = LocalDate.parse(today).plusDays(1).toString();
        mvc.perform(get(P + "/" + daily).with(admin)).andExpect(jsonPath("$.data.nextDueDate").value(tomorrow));
        // 내일 것 생성(선행 7일 안) → 건너뛰기: 사유 필수, 건너뛰면 다음 = 예정 + 1
        postJson(P + "/generate", "{}").andExpect(jsonPath("$.data.runs").value(1));
        long dr2 = runOf(daily);
        postJson(R + "/" + dr2 + "/skip", "{\"rowVer\":0,\"reason\":\" \"}").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        postJson(R + "/" + dr2 + "/skip", "{\"rowVer\":0,\"reason\":\"설비 휴무\"}").andExpect(status().isOk());
        mvc.perform(get(P + "/" + daily).with(admin)).andExpect(jsonPath("$.data.nextDueDate").value(LocalDate.parse(today).plusDays(2).toString()));
        // PM 작업지시 취소 → 점검 SKIPPED, 계획 다음 달
        long mwo = accounts.query(comp, "select mwo_id from cms_mwo where pm_plan_id = ?", Long.class, wo);
        postJson(M + "/" + mwo + "/cancel", "{\"rowVer\":0,\"reason\":\"설비 이전\"}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select status from cms_chk_run where run_id = ?", String.class, wr)).isEqualTo("SKIPPED");
        mvc.perform(get(P + "/" + wo).with(admin)).andExpect(jsonPath("$.data.nextDueDate").value(LocalDate.parse(today).plusMonths(1).toString()));
        // 기간·상태 필터, 기간 없음
        mvc.perform(get(R).param("from", today).param("to", tomorrow).param("status", "SKIPPED").with(admin)).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get(R).with(admin)).andExpect(status().isBadRequest());
    }

    // ---------- 계획 검증·규칙 변경·일괄 ----------

    @Test
    void 계획_대상과_점검표_맞춤_게이지없음_규칙검증_규칙바꾸면_다시계산_열린예정있으면_그대로_일괄() throws Exception {
        String base = "{\"planCd\":\"X1\",\"planNm\":\"x\",\"calUnit\":\"MONTH\",\"calInterval\":1,\"anchorDate\":\"" + today + "\",";
        postJson(P, base + "\"targetCd\":\"PRS-01\",\"tmplCd\":\"WELD-M\"}").andExpect(jsonPath("$.errors[0].code").value("TMPL_TARGET"));  // 용접기 전용
        postJson(P, base + "\"targetType\":\"ASSET\",\"targetCd\":\"M-100\",\"tmplCd\":\"WELD-M\"}").andExpect(jsonPath("$.errors[0].code").value("TMPL_TARGET"));
        postJson(P, base + "\"targetType\":\"ASSET\",\"targetCd\":\"G-100\",\"tmplCd\":\"WELD-M\"}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        postJson(P, "{\"planCd\":\"x 1\",\"targetCd\":\"SPOT-01\",\"tmplCd\":\"WELD-M\",\"execMode\":\"PAPER\",\"calUnit\":\"HOUR\",\"calInterval\":0,"
                + "\"scheduleBasis\":\"SOMETIMES\",\"leadDays\":91}").andExpect(jsonPath("$.errors.length()").value(8));
        long pl = plan("W-1", "SPOT-01", "WO", "WEEK", 2, "FIXED", "2026-01-05");
        // 규칙 바꾸기(열린 예정 없음) → 다시 계산
        putJson(P + "/" + pl, "{\"planNm\":\"w\",\"tmplCd\":\"WELD-M\",\"execMode\":\"WO\",\"calUnit\":\"WEEK\",\"calInterval\":2,"
                + "\"scheduleBasis\":\"FIXED\",\"anchorDate\":\"2026-01-12\",\"rowVer\":0}").andExpect(status().isOk());
        mvc.perform(get(P + "/" + pl).with(admin)).andExpect(jsonPath("$.data.nextDueDate").value("2026-01-12"))
                .andExpect(jsonPath("$.data.state").value("OVERDUE"));
        postJson(P + "/generate", "{}").andExpect(jsonPath("$.data.runs").value(1));   // 밀린 예정 하나만(쌓이지 않음)
        postJson(P + "/generate", "{}").andExpect(jsonPath("$.data.runs").value(0));
        // 열린 예정이 있으면 규칙을 바꿔도 다음 예정일 그대로
        putJson(P + "/" + pl, "{\"planNm\":\"w\",\"tmplCd\":\"WELD-M\",\"execMode\":\"WO\",\"calUnit\":\"DAY\",\"calInterval\":1,"
                + "\"scheduleBasis\":\"FIXED\",\"anchorDate\":\"2026-03-01\",\"rowVer\":1}").andExpect(status().isOk());
        mvc.perform(get(P + "/" + pl).with(admin)).andExpect(jsonPath("$.data.nextDueDate").value("2026-01-12"));
        putJson(P + "/" + pl, "{\"planCd\":\"W-2\",\"targetCd\":\"SPOT-02\",\"planNm\":\"w\",\"tmplCd\":\"WELD-M\",\"calUnit\":\"DAY\",\"calInterval\":1,"
                + "\"anchorDate\":\"2026-03-01\",\"rowVer\":2}").andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("FIXED", "FIXED")));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_pm_plan set plan_cd = 'Z' where plan_id = ?", pl))))
                .contains("ck_cms_pm_plan_fixed");
        // 미리보기(저장 전 규칙)
        postJson(P + "/preview", "{\"scheduleBasis\":\"FIXED\",\"calUnit\":\"MONTH\",\"calInterval\":1,\"anchorDate\":\"2026-01-31\"}")
                .andExpect(jsonPath("$.data[1]").value("2026-02-28")).andExpect(jsonPath("$.data.length()").value(12));
        postJson(P + "/preview", "{\"calUnit\":\"MONTH\"}").andExpect(status().isBadRequest());
        // 클래스 일괄: 용접기 2대 → 2개(W-1 이 있는 SPOT-01 도 코드가 달라 생성), 다시 → 모두 건너뜀
        postJson(P + "/bulk", "{\"classCd\":\"WELD\",\"tmplCd\":\"WELD-M\",\"calUnit\":\"MONTH\",\"calInterval\":3,\"anchorDate\":\"" + today + "\"}")
                .andExpect(jsonPath("$.data.created").value(2)).andExpect(jsonPath("$.data.planCds[0]").value("WELD-M-SPOT-01"));
        postJson(P + "/bulk", "{\"classCd\":\"WELD\",\"tmplCd\":\"WELD-M\",\"calUnit\":\"MONTH\",\"calInterval\":3,\"anchorDate\":\"" + today + "\"}")
                .andExpect(jsonPath("$.data.created").value(0)).andExpect(jsonPath("$.data.skipped").value(2));
        postJson(P + "/bulk", "{\"classCd\":\"PRESS\",\"tmplCd\":\"WELD-M\",\"calUnit\":\"MONTH\",\"calInterval\":3,\"anchorDate\":\"" + today + "\"}")
                .andExpect(jsonPath("$.errors[0].code").value("TMPL_TARGET"));
        // 사용 중지 계획은 예정을 만들지 않는다
        accounts.exec(comp, "update cms_pm_plan set use_yn = 'N'");
        postJson(P + "/generate", "{}").andExpect(jsonPath("$.data.runs").value(0));
    }

    // ---------- A사로 B사 · 권한 · 직접 SQL ----------

    @Test
    void B사로_A사_점검표_계획_점검_막힘_직접SQL_다른회사_참조_거부_권한() throws Exception {
        long pl = plan("PM-1", "SPOT-01", "WO", "MONTH", 1, "FIXED", today);
        postJson(P + "/generate", "{}").andExpect(status().isOk());
        long run = runOf(pl);
        long mwo = accounts.query(comp, "select mwo_id from cms_mwo where pm_plan_id = ?", Long.class, pl);
        String other = accounts.newComp("PB");
        long bu = accounts.newUser(other, "admin1", PW);
        grantAll(other, bu);
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        accounts.addPlant(other, "P1", "B");
        long bClass = equipClass(other, "WELD");
        long be = equip(other, "P1", "B-01", bClass);
        mvc.perform(get(T).with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(P).with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(T + "/" + tmpl + "/items").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(T + "/" + tmpl + "/items?rowVer=1"), "[]").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(P + "/" + pl).with(b)).andExpect(status().isNotFound());
        mvc.perform(get(R + "/" + run).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(R + "/" + run + "/results"), "{\"rowVer\":0,\"results\":[{\"seq\":2,\"judge\":\"NG\"}]}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(R + "/" + run + "/follow-up"), "{\"seq\":1}").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(M + "/" + mwo + "/run").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(P), "{\"planCd\":\"B1\",\"planNm\":\"b\",\"targetCd\":\"B-01\",\"tmplCd\":\"WELD-M\",\"calUnit\":\"DAY\",\"calInterval\":1,"
                + "\"anchorDate\":\"" + today + "\"}").with(b)).andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        // B사 생성은 A사 계획을 건드리지 않는다
        mvc.perform(json(post(P + "/generate"), "{}").with(b)).andExpect(jsonPath("$.data.runs").value(0));
        // 직접 SQL: B사 계획이 A사 점검표 / B사 실행이 A사 계획 / B사 항목이 A사 점검표
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into cms_pm_plan (comp_cd, plan_cd, plan_nm, target_type, equip_id, tmpl_id, cal_unit, cal_interval, anchor_date,
                                         next_due_date, created_by, updated_by)
                values (?, 'X', 'x', 'EQUIP', ?, ?, 'DAY', 1, (now() at time zone 'Asia/Seoul')::date, (now() at time zone 'Asia/Seoul')::date, 't', 't')""", other, be, tmpl))))
                .contains("fk_cms_pm_plan_tmpl_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into cms_chk_run (comp_cd, plan_id, target_type, equip_id, tmpl_id, due_date, created_by, updated_by)
                values (?, ?, 'EQUIP', ?, ?, (now() at time zone 'Asia/Seoul')::date, 't', 't')""", other, pl, be, tmpl)))).contains("fk_cms_chk_run_plan_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into cms_chk_tmpl_item (comp_cd, tmpl_id, seq, item_nm, created_by, updated_by) values (?, ?, 9, 'x', 't', 't')""",
                other, tmpl)))).contains("fk_cms_chk_tmpl_item_tmpl_comp");
        // A사 직접 SQL: 같은 계획·예정 두 번, 실행 삭제
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, """
                insert into cms_chk_run (comp_cd, plan_id, target_type, equip_id, tmpl_id, due_date, created_by, updated_by)
                select comp_cd, plan_id, target_type, equip_id, tmpl_id, due_date, 't', 't' from cms_chk_run where run_id = ?""", run))))
                .contains("23505");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from cms_chk_run where run_id = ?", run)))).contains("42501");
        // 권한: 점검 조회만 → 결과 저장 403, 건너뛰기는 DELETE
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", "cms-chk-run", "SEARCH", "SAVE");
        RequestPostProcessor vw = accounts.as(comp, viewer, "viewer1");
        mvc.perform(json(post(R + "/" + run + "/skip"), "{\"rowVer\":0,\"reason\":\"x\"}").with(vw)).andExpect(status().isForbidden());
        mvc.perform(json(post(P + "/generate"), "{}").with(vw)).andExpect(status().isForbidden());
        mvc.perform(get(M + "/" + mwo + "/run").with(vw)).andExpect(status().isForbidden());
    }

    // ---------- 건너뛰기 사유 코드 (docs/68 Q1) ----------

    @Test
    void 건너뛰기_사유코드_메모없으면_코드이름_없는코드_다른회사코드_거부_코드는_건너뛴_예정에만() throws Exception {
        long daily = plan("D-RC", "SPOT-02", "CHECK", "DAY", 1, "FLOAT", today);
        postJson(P + "/generate", "{}").andExpect(status().isOk());
        long r1 = runOf(daily);
        String other = accounts.newComp("PM");
        accounts.exec(other, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by) values (?, 'CMS_SKIP_REASON', 'BONLY', 'B 사유', 't', 't')", other);
        for (String bad : new String[] {"NOPE", "BONLY", "no_prod"}) {
            postJson(R + "/" + r1 + "/skip", "{\"rowVer\":0,\"reasonCd\":\"" + bad + "\"}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("reasonCd")).andExpect(jsonPath("$.errors[0].code").value("CODE"));
        }
        assertThat(accounts.query(comp, "select status from cms_chk_run where run_id = ?", String.class, r1)).isEqualTo("OPEN");
        // 코드만 → 사유 글자 = 코드 이름, 코드 + 메모 → 메모
        postJson(R + "/" + r1 + "/skip", "{\"rowVer\":0,\"reasonCd\":\"NO_PROD\"}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select skip_reason_cd || '/' || skip_reason from cms_chk_run where run_id = ?", String.class, r1))
                .isEqualTo("NO_PROD/비가동(생산 없음)");
        postJson(P + "/generate", "{}").andExpect(status().isOk());
        long r2 = runOf(daily);
        postJson(R + "/" + r2 + "/skip", "{\"rowVer\":0,\"reasonCd\":\"EQUIP_DOWN\",\"reason\":\"모터 교체 대기\"}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select skip_reason_cd || '/' || skip_reason from cms_chk_run where run_id = ?", String.class, r2))
                .isEqualTo("EQUIP_DOWN/모터 교체 대기");
        mvc.perform(get(R).param("from", today).param("to", LocalDate.parse(today).plusDays(7).toString()).param("status", "SKIPPED").with(admin))
                .andExpect(jsonPath("$.data[*].skipReasonCd").value(containsInAnyOrder("NO_PROD", "EQUIP_DOWN")));
        // 직접 SQL: 열린 예정에는 사유 코드를 못 넣는다, 다른 회사 코드는 트리거가 거부
        postJson(P + "/generate", "{}").andExpect(status().isOk());
        long r3 = runOf(daily);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_chk_run set skip_reason_cd = 'OTHER' where run_id = ?", r3))))
                .contains("ck_cms_chk_run_skip_cd_status");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_chk_run set skip_reason_cd = 'BONLY' where run_id = ?", r2))))
                .contains("fk_cms_chk_run_skip_reason_cd_code");
    }
}
