package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 설비·생산 대시보드 API (docs/51 C2·C4) — 새 계산이 아니라 KPI(cms-kpi)와 같은 값인지, 비가동 Pareto 합 = 비가동 총합,
 * Pareto 한 줄 = 비가동 사유 검토(reasonCd 드릴다운)의 같은 구간, 일별 합 = 기간, 앞 기간, 정책 기본 기준·목표, 권한·기간 검사, A사로 B사.
 */
@AutoConfigureMockMvc
class CmsDashboardTest extends PostgresTestBase {

    private static final String PW = "Aspen-meter-61";
    private static final String D = "/api/cms/dashboard";
    private static final String D1 = "2026-03-02";
    private static final String D2 = "2026-03-03";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    long eqA;
    long eqB;
    long rMold;
    long rTip;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("DB");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "ADM", "cms-kpi", "SEARCH");
        accounts.grant(comp, userId, "ADM", "cms-dashboard", "SEARCH");
        accounts.grant(comp, userId, "ADM2", "cms-state-review", "SEARCH");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        eqA = equip(comp, "EQ-A", cls);
        eqB = equip(comp, "EQ-B", cls);
        rMold = reason(comp, "MOLD", "금형 교환", "SDT");
        rTip = reason(comp, "TIP", "팁 교체", "UDT");
        // D1: EQ-A PRD 8h · 금형 SDT 1h · 팁 UDT 0.5h ×2 · 사유 없는 UDT 0.5h · 끊김 1h / EQ-B PRD 10h · 팁 UDT 1h · 대기 SBY 1h
        state(comp, eqA, "PRD", null, D1 + "T00:00", D1 + "T08:00");
        state(comp, eqA, "SDT", rMold, D1 + "T08:00", D1 + "T09:00");
        state(comp, eqA, "UDT", rTip, D1 + "T09:00", D1 + "T09:30");
        state(comp, eqA, "PRD", null, D1 + "T09:30", D1 + "T10:00");
        state(comp, eqA, "UDT", rTip, D1 + "T10:00", D1 + "T10:30");
        state(comp, eqA, "UDT", null, D1 + "T10:30", D1 + "T11:00");
        state(comp, eqA, "UNKNOWN", null, D1 + "T11:00", D1 + "T12:00");
        state(comp, eqB, "PRD", null, D1 + "T00:00", D1 + "T10:00");
        state(comp, eqB, "UDT", rTip, D1 + "T10:00", D1 + "T11:00");
        state(comp, eqB, "SBY", null, D1 + "T11:00", D1 + "T12:00");
        // D2 하루 — 일별 합 확인용. 앞 기간(D0 = 3/1) 은 EQ-B PRD 2h
        state(comp, eqB, "PRD", null, D2 + "T00:00", D2 + "T03:00");
        state(comp, eqB, "PRD", null, "2026-03-01T00:00", "2026-03-01T02:00");
    }

    private long equip(String c, String cd, long cls) {
        return accounts.query(c, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, 't', 't') returning equip_id""", Long.class, c, cd, cd + " 설비", cls);
    }

    private long reason(String c, String cd, String nm, String cat) {
        return accounts.query(c, """
                insert into cms_state_reason (comp_cd, reason_cd, reason_nm, state_cat, created_by, updated_by)
                values (?, ?, ?, ?, 't', 't') returning reason_id""", Long.class, c, cd, nm, cat);
    }

    private void state(String c, long equip, String cat, Long reasonId, String start, String end) {
        accounts.exec(c, """
                insert into cms_equip_state_log (comp_cd, equip_id, state_cat, reason_id, start_at, end_at, source, occurred_at, entry_method, recorded_by)
                values (?, ?, ?, ?, ?::timestamptz, ?::timestamptz, 'AUTO', ?::timestamptz, 'AUTO', 'col')""",
                c, equip, cat, reasonId, start + ":00+09:00", end == null ? null : end + ":00+09:00", start + ":00+09:00");
    }

    private ResultActions dash(String from, String to, RequestPostProcessor who) throws Exception {
        return mvc.perform(get(D).param("plantCd", "P1").param("from", from).param("to", to).with(who));
    }

    @Test
    void KPI_화면과_같은_값_Pareto_합은_비가동_총합_계획비계획_구분() throws Exception {
        String body = dash(D1, D2, admin).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String kpi = mvc.perform(get("/api/cms/kpi").param("plantCd", "P1").param("from", D1).param("to", D2).with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // 새 계산이 아니다 — 같은 조건의 KPI 화면 공장 합계와 원값·비율이 같다
        assertThat((Object) JsonPath.read(body, "$.data.total.raw")).isEqualTo(JsonPath.read(kpi, "$.data.plant.raw"));
        assertThat((Object) JsonPath.read(body, "$.data.total.kpi")).isEqualTo(JsonPath.read(kpi, "$.data.plant.kpi"));
        assertThat((String) JsonPath.read(body, "$.data.basis")).isEqualTo("RESULT");

        List<Map<String, Object>> losses = JsonPath.read(body, "$.data.losses");
        long lossSum = losses.stream().mapToLong(l -> ((Number) l.get("sec")).longValue()).sum();
        long covered = ((Number) JsonPath.read(body, "$.data.total.raw.coveredSec")).longValue();
        long prd = ((Number) JsonPath.read(body, "$.data.total.raw.prdSec")).longValue();
        long unknown = ((Number) JsonPath.read(body, "$.data.unknownSec")).longValue();
        assertThat(unknown).isEqualTo(3600);
        assertThat(lossSum).as("Pareto 합 = 수집 − 가동 − 끊김").isEqualTo(covered - prd - unknown);
        // 1위 = 팁 교체(UDT 0.5+0.5+1h = 2h, 구간 3개), 비계획 / 금형 = 계획 / 사유 없는 UDT 는 따로 한 줄 / 끊김은 Pareto 밖
        assertThat(losses.get(0)).containsEntry("reasonCd", "TIP").containsEntry("group", "UNPLANNED").containsEntry("sec", 7200)
                .containsEntry("intervals", 3);
        assertThat(losses).anySatisfy(l -> assertThat(l).containsEntry("reasonCd", "MOLD").containsEntry("group", "PLANNED").containsEntry("sec", 3600));
        assertThat(losses).anySatisfy(l -> assertThat(l).containsEntry("cat", "UDT").containsEntry("reasonCd", null).containsEntry("sec", 1800));
        assertThat(losses).anySatisfy(l -> assertThat(l).containsEntry("cat", "SBY").containsEntry("group", "UNPLANNED"));
        assertThat(losses).noneSatisfy(l -> assertThat(l.get("cat")).isIn("PRD", "UNKNOWN"));
        // 목표 정책이 0(기본) 이면 목표 없음
        assertThat((Object) JsonPath.read(body, "$.data.targets.oeePct")).isNull();
    }

    @Test
    void Pareto_한줄_드릴다운은_비가동사유검토의_같은_구간() throws Exception {
        mvc.perform(get("/api/cms/state-review").param("plantCd", "P1").param("from", D1).param("to", D2).param("reasonCd", "tip").with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[*].logReasonCd", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("TIP"))));
        mvc.perform(get("/api/cms/state-review/grid").param("plantCd", "P1").param("from", D1).param("to", D2).param("reasonCd", "MOLD")
                .param("total", "true").with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        // 형식이 틀린 사유 코드는 400(조용히 전체를 돌려주지 않는다)
        mvc.perform(get("/api/cms/state-review").param("plantCd", "P1").param("from", D1).param("to", D2).param("reasonCd", "TIP' or 1=1")
                .with(admin)).andExpect(status().isBadRequest());
    }

    @Test
    void 일별합은_기간_앞기간은_같은길이_바로앞() throws Exception {
        String body = dash(D1, D2, admin).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Number> dailyPrd = JsonPath.read(body, "$.data.daily[*].raw.prdSec");
        assertThat(dailyPrd).hasSize(2);
        assertThat(dailyPrd.stream().mapToLong(Number::longValue).sum()).isEqualTo(((Number) JsonPath.read(body, "$.data.total.raw.prdSec")).longValue());
        assertThat((String) JsonPath.read(body, "$.data.prevFrom")).isEqualTo("2026-02-28");
        assertThat((String) JsonPath.read(body, "$.data.prevTo")).isEqualTo("2026-03-01");
        assertThat(((Number) JsonPath.read(body, "$.data.prev.raw.prdSec")).longValue()).isEqualTo(7200);
    }

    @Test
    void 정책_기본기준과_목표_설비글자_거르기() throws Exception {
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) values (?, 'P1', 'CMS_DASH_BASIS', 'COUNT', 't', 't')", comp);
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) values (?, '*', 'CMS_TARGET_OEE_PCT', '85', 't', 't')", comp);
        dash(D1, D1, admin).andExpect(status().isOk()).andExpect(jsonPath("$.data.basis").value("COUNT"))
                .andExpect(jsonPath("$.data.targets.oeePct").value(85));
        // 화면이 고른 기준이 정책보다 앞
        mvc.perform(get(D).param("plantCd", "P1").param("from", D1).param("to", D1).param("basis", "RESULT").with(admin))
                .andExpect(jsonPath("$.data.basis").value("RESULT"));
        // 설비 글자 = KPI 화면 keyword 와 같은 거르기 — EQ-B 만이면 금형(EQ-A) 줄이 없다
        mvc.perform(get(D).param("plantCd", "P1").param("from", D1).param("to", D1).param("keyword", "eq-b").with(admin))
                .andExpect(jsonPath("$.data.equips.length()").value(1))
                .andExpect(jsonPath("$.data.losses[?(@.reasonCd == 'MOLD')]").isEmpty());
    }

    @Test
    void 권한_기간_입력검사() throws Exception {
        long viewer = accounts.newUser(comp, "viewer", PW);
        dash(D1, D1, accounts.as(comp, viewer, "viewer")).andExpect(status().isForbidden());
        // 설비 KPI 권한만으로는 대시보드 API 를 못 본다(2026-11-09 권한 = 대시보드 화면, docs/33 16.4)
        accounts.grant(comp, viewer, "KPI", "cms-kpi", "SEARCH");
        dash(D1, D1, accounts.as(comp, viewer, "viewer")).andExpect(status().isForbidden());
        dash("2026-01-01", D1, admin).andExpect(status().isBadRequest());
        mvc.perform(get(D).param("from", D1).param("to", D1).with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("plantCd"));
        mvc.perform(get(D).param("plantCd", "P1").param("from", D1).param("to", D1).param("basis", "SECRET").with(admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void A사로_B사_원장이_섞이지_않는다() throws Exception {
        String b = accounts.newComp("DC");
        accounts.addPlant(b, "P1", "B 1공장");
        long bCls = accounts.query(b, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, b);
        long bEq = equip(b, "EQ-A", bCls);
        long bTip = reason(b, "TIP", "B 팁", "UDT");
        state(b, bEq, "UDT", bTip, D1 + "T12:00", D1 + "T20:00");
        // 같은 공장·설비·사유 코드라도 A사 대시보드·드릴다운은 A사 원장만
        dash(D1, D1, admin).andExpect(jsonPath("$.data.losses[0].reasonCd").value("TIP")).andExpect(jsonPath("$.data.losses[0].sec").value(7200));
        mvc.perform(get("/api/cms/state-review").param("plantCd", "P1").param("from", D1).param("to", D1).param("reasonCd", "TIP").with(admin))
                .andExpect(jsonPath("$.data.length()").value(3));
        // B사 사용자는 B사 것만
        long bUser = accounts.newUser(b, "badmin", PW);
        accounts.grant(b, bUser, "ADM", "cms-dashboard", "SEARCH");
        dash(D1, D1, accounts.as(b, bUser, "badmin")).andExpect(jsonPath("$.data.losses.length()").value(1))
                .andExpect(jsonPath("$.data.losses[0].sec").value(8 * 3600)).andExpect(jsonPath("$.data.equips.length()").value(1));
    }
}
