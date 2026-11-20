package io.smarthappy.module.pop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * POP 설비 점검 (docs/25 4.58) — 예방점검 CHECK 방식을 단말에서 실행(CMS 계약 PmCheckPort 실제 구현까지 관통).
 * 목록 범위(작업장·CHECK 방식), 결과 + 완료 한 번에(수치 판정 경계·필수·형식), 작업자·단말 기록, 다음 예정, clientReqId 멱등(재전송·동시·다른 실행),
 * 건너뛰기(사유 코드 필수), 작업자 없음·쿠키 없음·PDA·작업장 밖·WO 방식·A사로 B사, 직접 SQL.
 */
@AutoConfigureMockMvc
class PopPmTest extends PostgresTestBase {

    private static final String P = PopFloor.P + "/pm/runs";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    PopFloor f;
    long spot1;
    long daily;
    long run;

    @BeforeEach
    void setUp() throws Exception {
        f = new PopFloor(mvc, accounts).build("PP");
        seedPm(f);
        spot1 = f.accounts.query(f.comp, "select equip_id from mst_equip where equip_cd = 'SPOT-01'", Long.class);
        daily = plan(f, "D-SPOT1", "SPOT-01", "CHECK", "DAY");
        plan(f, "M-SPOT2", "SPOT-02", "WO", "MONTH");          // WO 방식 — 단말에 안 보인다
        plan(f, "D-OTHER", "OTHER-01", "CHECK", "DAY");         // L2 설비 — L1 단말에 안 보인다
        office(f, post("/api/cms/pm-plans/generate"), "{}").andExpect(status().isOk());
        run = runOf(f, daily);
    }

    // ---------- 도우미 ----------

    /** 설비 3대(C1 SPOT-01·SPOT-02, L2 OTHER-01) + 용접기 일상 점검표(1 팁 직경 4.0~5.0 필수, 2 냉각수 누수 OK/NG 필수, 3 메모 선택) */
    private static void seedPm(PopFloor f) throws Exception {
        f.accounts.grant(f.comp, f.userId, "CMS", "cms-chk-tmpl", "SEARCH", "SAVE");
        f.accounts.grant(f.comp, f.userId, "CMS", "cms-pm-plan", "SEARCH", "SAVE");
        f.accounts.grant(f.comp, f.userId, "CMS", "cms-chk-run", "SEARCH", "SAVE", "DELETE");
        long cls = f.accounts.query(f.comp, """
                insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'WELD', 'WELD', 't', 't')
                returning class_id""", Long.class, f.comp);
        for (Object[] e : new Object[][] {{"SPOT-01", f.cell1}, {"SPOT-02", f.cell1}, {"OTHER-01", f.line2}}) {
            f.accounts.exec(f.comp, """
                    insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, node_id, use_yn, created_by, updated_by)
                    values (?, 'P1', ?, ?, ?, ?, 'Y', 't', 't')""", f.comp, e[0], e[0] + " 설비", cls, e[1]);
        }
        long tmpl = PopFloor.id(office(f, post("/api/cms/chk-tmpls"), "{\"tmplCd\":\"weld-d\",\"tmplNm\":\"용접기 일상\",\"equipClassCd\":\"WELD\"}"));
        office(f, put("/api/cms/chk-tmpls/" + tmpl + "/items?rowVer=0"), """
                [{"seq":1,"itemNm":"팁 직경","judgeType":"NUM","lsl":"4.0","usl":"5.0","unitNm":"mm","scale":1},
                 {"seq":2,"itemNm":"냉각수 누수","judgeType":"OKNG"},
                 {"seq":3,"itemNm":"메모","judgeType":"TEXT","requiredYn":"N"}]""").andExpect(status().isOk());
    }

    private static ResultActions office(PopFloor f, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, String body)
            throws Exception {
        return f.mvc.perform(f.json(b, body).with(f.admin));
    }

    private static long plan(PopFloor f, String cd, String target, String mode, String unit) throws Exception {
        return PopFloor.id(office(f, post("/api/cms/pm-plans"), "{\"planCd\":\"" + cd + "\",\"planNm\":\"" + cd + " 점검\",\"targetCd\":\"" + target
                + "\",\"tmplCd\":\"WELD-D\",\"execMode\":\"" + mode + "\",\"calUnit\":\"" + unit
                + "\",\"calInterval\":1,\"scheduleBasis\":\"FLOAT\",\"anchorDate\":\"" + f.today + "\"}"));
    }

    private static long runOf(PopFloor f, long planId) {
        return f.accounts.query(f.comp, "select run_id from cms_chk_run where plan_id = ? and status = 'OPEN'", Long.class, planId);
    }

    private String col(String col) {
        return accounts.query(f.comp, "select " + col + "::text from cms_chk_run where run_id = ?", String.class, run);
    }

    private static String ok(String req) {
        return "{\"rowVer\":0,\"clientReqId\":\"" + req + "\",\"results\":[{\"seq\":1,\"value\":\"4.5\"},{\"seq\":2,\"judge\":\"OK\"}]}";
    }

    // ---------- 흐름 ----------

    @Test
    void 열린_CHECK_점검만_작업장_설비_것만_보이고_결과와_완료가_한_번에_작업자와_단말로_남는다() throws Exception {
        f.popGet(PopFloor.P + "/session").andExpect(jsonPath("$.data.pm").value(true));
        f.popGet(P).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].runId").value((int) run)).andExpect(jsonPath("$.data[0].equipCd").value("SPOT-01"))
                .andExpect(jsonPath("$.data[0].itemCount").value(3)).andExpect(jsonPath("$.data[0].state").value("DUE"));
        f.popGet(P + "/" + run).andExpect(status().isOk()).andExpect(jsonPath("$.data.view.items.length()").value(3))
                .andExpect(jsonPath("$.data.view.items[0].judgeType").value("NUM")).andExpect(jsonPath("$.data.view.items[0].required").value(true))
                .andExpect(jsonPath("$.data.view.items[2].required").value(false))
                .andExpect(jsonPath("$.data.skipReasons[*].code", Matchers.hasItem("NO_PROD")));
        // 경계 포함 합격(5.0), 메모는 선택
        f.pop(post(P + "/" + run + "/complete"), "{\"rowVer\":0,\"clientReqId\":\"" + PopFloor.req()
                + "\",\"results\":[{\"seq\":1,\"value\":\"5.0\"},{\"seq\":2,\"judge\":\"OK\"},{\"seq\":3,\"valueText\":\"이상 없음\"}]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DONE")).andExpect(jsonPath("$.data.result").value("OK"))
                .andExpect(jsonPath("$.data.duplicate").value(false)).andExpect(jsonPath("$.data.ngItems.length()").value(0));
        assertThat(col("status")).isEqualTo("DONE");
        assertThat(col("floor_worker_id")).isEqualTo(String.valueOf(f.workerId));
        assertThat(col("performed_by")).isNull();
        assertThat(col("performed_at")).isNotNull();
        assertThat(col("updated_by")).matches("t\\d+:TAB-01");
        assertThat(accounts.query(f.comp, "select terminal_cd from sys_terminal where terminal_id = (select floor_terminal_id from cms_chk_run "
                + "where run_id = ?)", String.class, run)).isEqualTo("TAB-01");
        // 다음 예정 = 완료일 + 1(FLOAT), 목록에서 사라짐, 사무 화면에는 작업자 이름으로
        assertThat(accounts.query(f.comp, "select next_due_date::text from cms_pm_plan where plan_id = ?", String.class, daily))
                .isEqualTo(LocalDate.parse(f.today).plusDays(1).toString());
        f.popGet(P).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/cms/chk-runs/" + run).with(f.admin)).andExpect(jsonPath("$.data.performedNm").value("홍작업"))
                .andExpect(jsonPath("$.data.performedLoginId").value("E1"));
        // 끝난 실행을 다른 요청 id 로 다시 → 409, 끝난 실행 보기는 된다
        f.pop(post(P + "/" + run + "/complete"), ok(PopFloor.req())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CMS_RUN_STATE"));
        f.popGet(P + "/" + run).andExpect(status().isOk());
    }

    @Test
    void 필수_빠짐과_형식은_항목_오류_규격_밖은_불량으로_닫히고_불량_항목_이름을_돌려준다() throws Exception {
        // 필수 둘 다 빠짐 → 행 오류 2(행 키 = 순번), 실행은 그대로 OPEN
        f.pop(post(P + "/" + run + "/complete"), "{\"rowVer\":0,\"clientReqId\":\"" + PopFloor.req() + "\",\"results\":[]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[*].rowKey", Matchers.containsInAnyOrder("1", "2")));
        // 숫자 아님·자릿수 넘침·판정 코드 아님 — 저장 안 됨(같은 트랜잭션 롤백)
        f.pop(post(P + "/" + run + "/complete"), "{\"rowVer\":0,\"clientReqId\":\"" + PopFloor.req()
                + "\",\"results\":[{\"seq\":1,\"value\":\"4.55\"},{\"seq\":2,\"judge\":\"MAYBE\"}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(2));
        f.pop(post(P + "/" + run + "/complete"), "{\"rowVer\":0,\"clientReqId\":\"" + PopFloor.req()
                + "\",\"results\":[{\"seq\":9,\"value\":\"4.5\"}]}").andExpect(status().isBadRequest());
        assertThat(col("status")).isEqualTo("OPEN");
        assertThat(accounts.query(f.comp, "select count(*) from cms_chk_result where run_id = ? and (value_num is not null or judge is not null)",
                Integer.class, run)).isZero();
        // 낡은 rowVer → 409
        f.pop(post(P + "/" + run + "/complete"), ok(PopFloor.req()).replace("\"rowVer\":0", "\"rowVer\":7")).andExpect(status().isConflict());
        // 규격 밖(5.1) + 누수 불량 → NG 로 닫힘, 불량 항목 이름 둘
        f.pop(post(P + "/" + run + "/complete"), "{\"rowVer\":0,\"clientReqId\":\"" + PopFloor.req()
                + "\",\"results\":[{\"seq\":1,\"value\":\"5.1\"},{\"seq\":2,\"judge\":\"NG\"}]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.result").value("NG"))
                .andExpect(jsonPath("$.data.ngItems", Matchers.contains("팁 직경", "냉각수 누수")));
        assertThat(col("result")).isEqualTo("NG");
    }

    @Test
    void 같은_요청_id_재전송과_동시_도착은_한_번만_끝내고_처음_결과를_돌려준다() throws Exception {
        String req = PopFloor.req();
        // 동시 두 번 — 둘 다 200, 하나만 duplicate
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<String> call = () -> {
            go.await(5, TimeUnit.SECONDS);
            return f.pop(post(P + "/" + run + "/complete"), ok(req)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        };
        Future<String> a = pool.submit(call);
        Future<String> b = pool.submit(call);
        go.countDown();
        List<Boolean> dup = List.of(JsonPath.<Boolean>read(a.get(30, TimeUnit.SECONDS), "$.data.duplicate"),
                JsonPath.<Boolean>read(b.get(30, TimeUnit.SECONDS), "$.data.duplicate"));
        pool.shutdown();
        assertThat(dup).containsExactlyInAnyOrder(true, false);
        assertThat(col("row_ver")).isEqualTo("1");
        // 재전송(값을 바꿔 보내도 처음 결과) — 다음 예정이 두 번 밀리지 않는다
        f.pop(post(P + "/" + run + "/complete"), ok(req).replace("4.5", "9.9")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicate").value(true)).andExpect(jsonPath("$.data.result").value("OK"));
        assertThat(accounts.query(f.comp, "select next_due_date::text from cms_pm_plan where plan_id = ?", String.class, daily))
                .isEqualTo(LocalDate.parse(f.today).plusDays(1).toString());
        // 같은 요청 id 를 다른 실행에 → 409 DUPLICATE_REQUEST(그 실행은 그대로)
        office(f, post("/api/cms/pm-plans/generate"), "{}").andExpect(status().isOk());
        long next = runOf(f, daily);
        f.pop(post(P + "/" + next + "/complete"), ok(req)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        assertThat(accounts.query(f.comp, "select status from cms_chk_run where run_id = ?", String.class, next)).isEqualTo("OPEN");
        // 요청 id 없음·모양 다름 → 칸 오류
        f.pop(post(P + "/" + next + "/complete"), "{\"rowVer\":0,\"results\":[]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("clientReqId"));
        f.pop(post(P + "/" + next + "/complete"), "{\"rowVer\":0,\"clientReqId\":\"a b\",\"results\":[]}").andExpect(status().isBadRequest());
    }

    @Test
    void 건너뛰기는_사유_코드가_있어야_하고_누가_건너뛰었는지_남는다() throws Exception {
        f.pop(post(P + "/" + run + "/skip"), "{\"rowVer\":0,\"clientReqId\":\"" + PopFloor.req() + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reasonCd")).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        f.pop(post(P + "/" + run + "/skip"), "{\"rowVer\":0,\"clientReqId\":\"" + PopFloor.req() + "\",\"reasonCd\":\"NOPE\",\"memo\":\"x\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("CODE"));
        assertThat(col("status")).isEqualTo("OPEN");
        String req = PopFloor.req();
        f.pop(post(P + "/" + run + "/skip"), "{\"rowVer\":0,\"clientReqId\":\"" + req + "\",\"reasonCd\":\"NO_PROD\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SKIPPED")).andExpect(jsonPath("$.data.duplicate").value(false));
        assertThat(col("skip_reason_cd")).isEqualTo("NO_PROD");
        assertThat(col("skip_reason")).isNotBlank();                       // 메모가 없으면 코드 이름
        assertThat(col("floor_worker_id")).isEqualTo(String.valueOf(f.workerId));
        assertThat(col("updated_by")).matches("t\\d+:TAB-01");
        assertThat(col("performed_at")).isNull();
        // 재전송 = 처음 결과, 건너뛴 예정 다음 날로
        f.pop(post(P + "/" + run + "/skip"), "{\"rowVer\":0,\"clientReqId\":\"" + req + "\",\"reasonCd\":\"NO_PROD\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.duplicate").value(true));
        assertThat(accounts.query(f.comp, "select next_due_date::text from cms_pm_plan where plan_id = ?", String.class, daily))
                .isEqualTo(LocalDate.parse(f.today).plusDays(1).toString());
    }

    @Test
    void 작업자_없음_쿠키_없음_PDA_작업장_밖_WO_방식은_막힌다() throws Exception {
        // 배지 없는 세션 428, 쿠키 없음 401
        f.getAs(P, f.cookie, new MockHttpSession()).andExpect(status().is(428)).andExpect(jsonPath("$.code").value("POP_NO_WORKER"));
        f.postAs(P + "/" + run + "/complete", ok(PopFloor.req()), f.cookie, new MockHttpSession()).andExpect(status().is(428));
        f.getAs(P, null, new MockHttpSession()).andExpect(status().isUnauthorized());
        f.postAs(P + "/" + run + "/complete", ok(PopFloor.req()), null, new MockHttpSession()).andExpect(status().isUnauthorized());
        // 사무 로그인으로 단말 API → 단말이 아님
        mvc.perform(get(P).with(f.admin)).andExpect(status().is(Matchers.either(Matchers.is(401)).or(Matchers.is(403))));
        // PDA 단말 — 세션 플래그 거짓, API 403(배지가 있어도)
        String pda = f.terminal("PDA-01", null, "PDA");
        MockHttpSession ps = new MockHttpSession();
        f.badge(pda, ps, "BADGE-0001").andExpect(status().isOk());
        f.getAs(PopFloor.P + "/session", pda, ps).andExpect(jsonPath("$.data.pm").value(false));
        f.getAs(P, pda, ps).andExpect(status().isForbidden());
        f.postAs(P + "/" + run + "/complete", ok(PopFloor.req()), pda, ps).andExpect(status().isForbidden());
        // L2 단말 — 자기 작업장 설비 점검만 보이고, L1 설비의 점검은 보기·완료·건너뛰기 404
        String l2 = f.terminal("TAB-L2", f.line2);
        MockHttpSession s2 = new MockHttpSession();
        f.badge(l2, s2, "BADGE-0001").andExpect(status().isOk());
        f.getAs(P, l2, s2).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].equipCd").value("OTHER-01"));
        f.getAs(P + "/" + run, l2, s2).andExpect(status().isNotFound());
        f.postAs(P + "/" + run + "/complete", ok(PopFloor.req()), l2, s2).andExpect(status().isNotFound());
        f.postAs(P + "/" + run + "/skip", "{\"rowVer\":0,\"clientReqId\":\"" + PopFloor.req() + "\",\"reasonCd\":\"NO_PROD\"}", l2, s2)
                .andExpect(status().isNotFound());
        // WO 방식 실행 — 단말에서는 없는 것(보전 작업지시 완료가 끝낸다)
        long wo = accounts.query(f.comp, "select r.run_id from cms_chk_run r join cms_pm_plan p using (plan_id) where p.plan_cd = 'M-SPOT2'",
                Long.class);
        f.popGet(P + "/" + wo).andExpect(status().isNotFound());
        f.pop(post(P + "/" + wo + "/complete"), ok(PopFloor.req())).andExpect(status().isNotFound());
        // 없는 실행
        f.popGet(P + "/999999999").andExpect(status().isNotFound());
        assertThat(col("status")).isEqualTo("OPEN");
    }

    @Test
    void B사_단말로_A사_점검은_보이지도_끝나지도_않는다() throws Exception {
        PopFloor b = new PopFloor(mvc, accounts).build("PQ");
        seedPm(b);
        long bDaily = plan(b, "D-SPOT1", "SPOT-01", "CHECK", "DAY");
        office(b, post("/api/cms/pm-plans/generate"), "{}").andExpect(status().isOk());
        long bRun = runOf(b, bDaily);
        // B사 목록에는 B사 것만
        b.popGet(P).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].runId").value((int) bRun));
        // B사 단말 + A사 실행 id → 404, A사 실행은 그대로
        b.popGet(P + "/" + run).andExpect(status().isNotFound());
        b.pop(post(P + "/" + run + "/complete"), ok(PopFloor.req())).andExpect(status().isNotFound());
        b.pop(post(P + "/" + run + "/skip"), "{\"rowVer\":0,\"clientReqId\":\"" + PopFloor.req() + "\",\"reasonCd\":\"NO_PROD\"}")
                .andExpect(status().isNotFound());
        assertThat(col("status")).isEqualTo("OPEN");
        assertThat(col("floor_worker_id")).isNull();
        // A사가 쓴 요청 id 를 B사가 써도 서로 모른다(유일 키는 회사 안)
        String req = PopFloor.req();
        f.pop(post(P + "/" + run + "/complete"), ok(req)).andExpect(status().isOk());
        b.pop(post(P + "/" + bRun + "/complete"), ok(req)).andExpect(status().isOk()).andExpect(jsonPath("$.data.duplicate").value(false));
    }

    @Test
    void 직접_SQL_로도_현장_처리_칸은_끝낼_때_셋이_함께만_들어간다() {
        // 열린 실행에 작업자만 → 가드(단말 없음)·CHECK 가 막는다
        assertThatThrownBy(() -> accounts.exec(f.comp, "update cms_chk_run set floor_worker_id = ? where run_id = ?", f.workerId, run))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // 다른 회사 작업자로 끝내기 → 가드
        long terminal = accounts.query(f.comp, "select terminal_id from sys_terminal where terminal_cd = 'TAB-01'", Long.class);
        assertThatThrownBy(() -> accounts.exec(f.comp, """
                update cms_chk_run set status = 'SKIPPED', skip_reason = 'x', floor_worker_id = 999999999, floor_terminal_id = ?,
                       floor_req_id = 'direct-sql-0001' where run_id = ?""", terminal, run))
                .hasMessageContaining("is not an active worker");
        assertThat(col("status")).isEqualTo("OPEN");
    }
}
