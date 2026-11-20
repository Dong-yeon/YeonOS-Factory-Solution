package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
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
 * CMS 3차 보정 (docs/33 16.1~16.3, docs/64 F1~F3):
 * F1 예비품 사용일 = 작업지시 완료 시각(사용 이력·설비 이력 카드 요약·이력 표 같은 식, 취소 작업지시 제외),
 * F2 PM 작업지시를 과거 시각으로 완료하면 점검 실행 수행 시각·계획 마지막 완료일·다음 예정(FLOAT)이 그 시각 기준,
 * F3 수집 없는 설비의 작업지시 기준 MTBF·MTTR(이름을 나눠 따로, 손 검산), MTTA(G2 — 모든 설비), A사로 B사.
 */
@AutoConfigureMockMvc
class CmsWave3FixTest extends PostgresTestBase {

    private static final String PW = "Cedar-ridge-47";
    private static final String M = "/api/cms/mwos";
    private static final String D1 = "2026-03-02";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long userId;
    RequestPostProcessor admin;
    String today;
    long press;
    long eqA;
    long eqB;
    long sym;
    long cause;
    long act;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("W3");
        userId = accounts.newUser(comp, "admin1", PW);
        grantAll(comp, userId);
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        press = equipClass(comp, "PRESS");
        eqA = equip(comp, "PRS-01", press);
        eqB = equip(comp, "PRS-02", press);
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, use_yn, created_by, updated_by)
                values (?, 'BRG-6204', '베어링', 'CON', 'BUY', 'EA', 'Y', 't', 't')""", comp);
        sym = failCode(comp, "SYMPTOM", "NOISE", null);
        cause = failCode(comp, "CAUSE", "WEAR", "MECH");
        act = failCode(comp, "ACTION", "REPL", null);
        today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
    }

    private void grantAll(String c, long u) {
        accounts.grant(c, u, "CMS", "cms-mwo", "SEARCH", "SAVE", "APPROVE", "DELETE");
        accounts.grant(c, u, "CMS", "cms-spare", "SEARCH", "SAVE", "EXCEL");
        accounts.grant(c, u, "CMS", "cms-equip-card", "SEARCH");
        accounts.grant(c, u, "CMS", "cms-chk-tmpl", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-pm-plan", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-chk-run", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-kpi", "SEARCH", "EXCEL");
    }

    private long equipClass(String c, String cd) {
        return accounts.query(c, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, ?, ?, 't', 't') returning class_id",
                Long.class, c, cd, cd);
    }

    private long equip(String c, String cd, long classId) {
        return accounts.query(c, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, 't', 't') returning equip_id""", Long.class, c, cd, cd + " 설비", classId);
    }

    private long failCode(String c, String type, String cd, String cat) {
        return accounts.query(c, """
                insert into cms_fail_code (comp_cd, code_type, fail_cd, fail_nm, fail_cat, created_by, updated_by)
                values (?, ?, ?, ?, ?, 't', 't') returning fail_id""", Long.class, c, type, cd, cd + " 이름", cat);
    }

    /** 끝난 고장 수리(BM) 한 건 — 시각은 공장 현지(+09:00) "yyyy-MM-ddTHH:mm". downStart null = 정지 기록 없음 */
    static long doneBm(TestAccounts a, String c, long equip, String reported, String started, String finished, String downStart, String downEnd,
            long sym, long cause, long act) {
        long id = a.query(c, """
                insert into cms_mwo (comp_cd, mwo_no, mwo_type, plant_cd, target_type, equip_id, title, status, reported_at, symptom_id,
                                     down_yn, down_start_at, down_end_at, created_by, updated_by)
                values (?, 'T-' || nextval(pg_get_serial_sequence('cms_mwo', 'mwo_id')), 'BM', 'P1', 'EQUIP', ?, '고장', 'OPEN', ?::timestamptz, ?,
                        ?, ?::timestamptz, ?::timestamptz, 't', 't') returning mwo_id""", Long.class, c, equip, ts(reported), sym,
                downStart == null ? "N" : "Y", ts(downStart), ts(downEnd));
        a.exec(c, "update cms_mwo set status = 'WIP', started_at = ?::timestamptz where mwo_id = ?", ts(started), id);
        a.exec(c, """
                update cms_mwo set status = 'DONE', finished_at = ?::timestamptz, cause_id = ?, action_id = ?, work_text = '교체'
                 where mwo_id = ?""", ts(finished), cause, act, id);
        return id;
    }

    static String ts(String local) {
        return local == null ? null : local + ":00+09:00";
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

    private int ver(long mwo) {
        return accounts.query(comp, "select row_ver from cms_mwo where mwo_id = ?", Integer.class, mwo);
    }

    private <V> V read(ResultActions r, String path) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), path);
    }

    // ---------- F1 ----------

    @Test
    void F1_예비품_사용일은_작업지시_완료시각_카드요약과_이력표도_같은식_취소는_제외() throws Exception {
        String d5 = LocalDate.parse(today).minusDays(5).toString();
        long m = id(postJson(M, "{\"mwoType\":\"CM\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"title\":\"정비\"}"));
        postJson(M + "/" + m + "/parts", "{\"itemCd\":\"BRG-6204\",\"qty\":\"2\"}").andExpect(status().isOk());
        // 완료 전 — 잠정 사용일 = 기록 시각(오늘)
        mvc.perform(get("/api/cms/spares/usage").param("from", today).param("to", today).with(admin))
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].mwoStatus").value("OPEN"));
        postJson(M + "/" + m + "/start", "{\"rowVer\":" + ver(m) + "}").andExpect(status().isOk());
        // 5일 전 일을 오늘 사후 입력: 신고 08:00 · 착수 09:00 · 완료 10:30
        postJson(M + "/" + m + "/finish", "{\"title\":\"정비\",\"workText\":\"베어링 교체\",\"reportedAt\":\"" + d5 + "T08:00\",\"startedAt\":\"" + d5
                + "T09:00\",\"finishedAt\":\"" + d5 + "T10:30\",\"rowVer\":" + ver(m) + "}").andExpect(status().isOk());
        mvc.perform(get("/api/cms/spares/usage").param("from", d5).param("to", d5).with(admin))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].usedAt").value(org.hamcrest.Matchers.startsWith(d5 + "T10:30")))
                .andExpect(jsonPath("$.data[0].createdAt").value(org.hamcrest.Matchers.startsWith(today)));
        mvc.perform(get("/api/cms/spares/usage").param("from", today).param("to", today).with(admin)).andExpect(jsonPath("$.data.length()").value(0));
        // 설비 이력 카드: 요약(사용 예비품 줄 수)과 이력 표 PART 줄이 같은 날에
        mvc.perform(get("/api/cms/equip-card/summary").param("equipCd", "PRS-01").param("from", d5).param("to", d5).with(admin))
                .andExpect(jsonPath("$.data.part.lines").value(1));
        mvc.perform(get("/api/cms/equip-card/summary").param("equipCd", "PRS-01").param("from", today).param("to", today).with(admin))
                .andExpect(jsonPath("$.data.part.lines").value(0));
        mvc.perform(get("/api/cms/equip-card/history/grid").param("equipCd", "PRS-01").param("from", d5).param("to", d5).param("kind", "PART")
                .param("total", "true").with(admin)).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].atText").value(org.hamcrest.Matchers.startsWith(d5 + " 10:30")));
        // 취소된 작업지시의 줄(출고 없음)은 사용 이력에 없다
        long c2 = id(postJson(M, "{\"mwoType\":\"CM\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-02\",\"title\":\"취소될 정비\"}"));
        postJson(M + "/" + c2 + "/parts", "{\"itemCd\":\"BRG-6204\",\"qty\":\"1\"}").andExpect(status().isOk());
        postJson(M + "/" + c2 + "/cancel", "{\"reason\":\"중복\",\"rowVer\":" + ver(c2) + "}").andExpect(status().isOk());
        mvc.perform(get("/api/cms/spares/usage").param("from", today).param("to", today).with(admin)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/cms/equip-card/summary").param("equipCd", "PRS-02").param("from", today).param("to", today).with(admin))
                .andExpect(jsonPath("$.data.part.lines").value(0));
    }

    // ---------- F2 ----------

    @Test
    void F2_PM작업지시를_과거시각으로_완료하면_점검실행_수행시각과_다음예정이_그시각_기준() throws Exception {
        long tmpl = id(postJson("/api/cms/chk-tmpls", "{\"tmplCd\":\"PRS-W\",\"tmplNm\":\"프레스 주간\",\"equipClassCd\":\"PRESS\"}"));
        putJson("/api/cms/chk-tmpls/" + tmpl + "/items?rowVer=0", "[{\"seq\":1,\"itemNm\":\"윤활\",\"judgeType\":\"OKNG\"}]").andExpect(status().isOk());
        // 유동(FLOAT) 7일 — 다음 예정 = 완료일 + 7
        long plan = id(postJson("/api/cms/pm-plans", "{\"planCd\":\"PM-PRS\",\"planNm\":\"프레스 주간\",\"targetCd\":\"PRS-01\",\"tmplCd\":\"PRS-W\","
                + "\"execMode\":\"WO\",\"calUnit\":\"DAY\",\"calInterval\":7,\"scheduleBasis\":\"FLOAT\",\"anchorDate\":\"" + today + "\"}"));
        postJson("/api/cms/pm-plans/generate", "{}").andExpect(jsonPath("$.data.workOrders").value(1));
        long mwo = accounts.query(comp, "select mwo_id from cms_mwo where pm_plan_id = ?", Long.class, plan);
        long run = accounts.query(comp, "select run_id from cms_chk_run where plan_id = ?", Long.class, plan);
        putJson(M + "/" + mwo + "/run/results", "{\"rowVer\":0,\"results\":[{\"seq\":1,\"judge\":\"OK\"}]}").andExpect(status().isOk());
        postJson(M + "/" + mwo + "/start", "{\"rowVer\":" + ver(mwo) + "}").andExpect(status().isOk());
        String d3 = LocalDate.parse(today).minusDays(3).toString();
        postJson(M + "/" + mwo + "/finish", "{\"title\":\"프레스 주간\",\"reportedAt\":\"" + d3 + "T08:00\",\"startedAt\":\"" + d3
                + "T09:00\",\"finishedAt\":\"" + d3 + "T10:00\",\"dueDate\":\"" + today + "\",\"rowVer\":" + ver(mwo) + "}").andExpect(status().isOk());
        // 점검 실행 수행 시각 = 작업지시 완료 시각 (고치기 전: 지금)
        assertThat(accounts.query(comp, "select performed_at = finished_at from cms_chk_run r join cms_mwo m on m.mwo_id = r.mwo_id where r.run_id = ?",
                Boolean.class, run)).isTrue();
        mvc.perform(get("/api/cms/pm-plans/" + plan).with(admin)).andExpect(jsonPath("$.data.lastDoneDate").value(d3))
                .andExpect(jsonPath("$.data.nextDueDate").value(LocalDate.parse(d3).plusDays(7).toString()));
        // KPI PM 준수(작업지시 finished_at)와 점검 실행 화면(performed_at)이 같은 날을 본다 — 3일 전 하루 조회에 둘 다 1건
        mvc.perform(get("/api/cms/kpi").param("plantCd", "P1").param("from", d3).param("to", d3).with(admin))
                .andExpect(jsonPath("$.data.plant.raw.pmDone").value(1));
        assertThat(accounts.query(comp, "select count(*) from cms_chk_run where status = 'DONE' and (performed_at at time zone 'Asia/Seoul')::date = ?::date",
                Integer.class, d3)).isEqualTo(1);
    }

    // ---------- F3 (+ G2 MTTA) ----------

    @Test
    void F3_수집없는_설비는_작업지시기준_MTBF_MTTR_따로_수집있는_설비는_빈칸_MTTA는_모두_손검산() throws Exception {
        // PRS-01(수집 없음) D1: 고장 3건 — 정지 60분·30분·기록 없음. 반응 10·30·20분
        doneBm(accounts, comp, eqA, D1 + "T09:00", D1 + "T09:10", D1 + "T10:30", D1 + "T09:00", D1 + "T10:00", sym, cause, act);
        doneBm(accounts, comp, eqA, D1 + "T13:00", D1 + "T13:30", D1 + "T14:00", D1 + "T13:00", D1 + "T13:30", sym, cause, act);
        doneBm(accounts, comp, eqA, D1 + "T15:00", D1 + "T15:20", D1 + "T16:00", null, null, sym, cause, act);
        // 기간 밖·취소·진행 중·개선(CM)은 세지 않는다
        doneBm(accounts, comp, eqA, "2026-03-01T23:00", "2026-03-01T23:10", "2026-03-02T01:00", "2026-03-01T23:30", "2026-03-02T00:30", sym, cause, act);
        accounts.exec(comp, """
                insert into cms_mwo (comp_cd, mwo_no, mwo_type, plant_cd, target_type, equip_id, title, status, reported_at, symptom_id, down_yn,
                                     created_by, updated_by)
                values (?, 'T-OPEN', 'BM', 'P1', 'EQUIP', ?, '진행 중', 'OPEN', '2026-03-02T11:00:00+09:00', ?, 'N', 't', 't')""", comp, eqA, sym);
        // PRS-02(수집 있음): 상태 원장 PRD 하루 + 고장 작업지시 1건(반응 40분) — MTBF·MTTR(작업지시)는 빈칸, MTTA 는 있음
        accounts.exec(comp, """
                insert into cms_equip_state_log (comp_cd, equip_id, state_cat, start_at, end_at, source, occurred_at, entry_method, recorded_by)
                values (?, ?, 'PRD', '2026-03-02T00:00:00+09:00', '2026-03-03T00:00:00+09:00', 'AUTO', '2026-03-02T00:00:00+09:00', 'AUTO', 'col')""",
                comp, eqB);
        doneBm(accounts, comp, eqB, D1 + "T11:00", D1 + "T11:40", D1 + "T12:00", D1 + "T11:00", D1 + "T12:00", sym, cause, act);

        String body = mvc.perform(get("/api/cms/kpi").param("plantCd", "P1").param("from", D1).param("to", D1).with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Map<String, Object> a = equipWo(body, "PRS-01");
        Map<String, Object> b = equipWo(body, "PRS-02");
        // 손 검산: 달력 86400초, 정지 5400초(2건), 고장 3건 → MTTR = 5400/2/60 = 45.0분, MTBF = (86400-5400)/3/60 = 450.0분, MTTA = (10+30+20)/3 = 20.0분
        assertThat(a).containsEntry("failures", 3).containsEntry("downSec", 5400).containsEntry("downCount", 2).containsEntry("calSec", 86400);
        assertThat(num(a.get("mttrMin"))).isEqualTo("45.0");
        assertThat(num(a.get("mtbfMin"))).isEqualTo("450.0");
        assertThat(num(a.get("mttaMin"))).isEqualTo("20.0");
        assertThat(b.get("mtbfMin")).isNull();
        assertThat(b.get("mttrMin")).isNull();
        assertThat(num(b.get("mttaMin"))).isEqualTo("40.0");
        // 상태 원장 KPI 는 그대로 — PRS-01 은 수집 없음(빈칸), 작업지시로 채우지 않는다(D19)
        assertThat((Object) JsonPath.read(body, "$.data.equips[?(@.code == 'PRS-01')].kpi.mtbfMin")).asList().containsOnlyNulls();
        // 공장 합계: MTBF·MTTR(작업지시)는 수집 없는 설비만, MTTA 는 모든 설비 (10+30+20+40)/4 = 25.0
        Map<String, Object> p = JsonPath.read(body, "$.data.plant.wo");
        assertThat(num(p.get("mttrMin"))).isEqualTo("45.0");
        assertThat(num(p.get("mtbfMin"))).isEqualTo("450.0");
        assertThat(num(p.get("mttaMin"))).isEqualTo("25.0");
        assertThat(p).containsEntry("respCount", 4);
    }

    @Test
    void F3_A사로_B사_작업지시가_섞이지_않는다() throws Exception {
        String b = accounts.newComp("W4");
        accounts.addPlant(b, "P1", "B 1공장");
        long bEq = equip(b, "PRS-01", equipClass(b, "PRESS"));
        long bs = failCode(b, "SYMPTOM", "NOISE", null);
        long bc = failCode(b, "CAUSE", "WEAR", "MECH");
        long ba = failCode(b, "ACTION", "REPL", null);
        doneBm(accounts, b, bEq, D1 + "T09:00", D1 + "T09:10", D1 + "T10:00", D1 + "T09:00", D1 + "T10:00", bs, bc, ba);
        String body = mvc.perform(get("/api/cms/kpi").param("plantCd", "P1").param("from", D1).param("to", D1).with(admin))
                .andReturn().getResponse().getContentAsString();
        assertThat(equipWo(body, "PRS-01")).containsEntry("failures", 0);
        assertThat(((Map<String, Object>) JsonPath.read(body, "$.data.plant.wo")).get("mtbfMin")).isNull();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> equipWo(String body, String code) {
        List<Map<String, Object>> l = JsonPath.read(body, "$.data.equips[?(@.code == '" + code + "')].wo");
        assertThat(l).hasSize(1);
        return l.get(0);
    }

    private static String num(Object v) {
        return v == null ? null : new java.math.BigDecimal(v.toString()).setScale(1).toPlainString();
    }
}
