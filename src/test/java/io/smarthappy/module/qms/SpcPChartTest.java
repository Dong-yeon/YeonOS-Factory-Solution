package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

/**
 * p 관리도(불량률) — docs/29_P3 28장. 계수형 특성(FG1-2, 전수 SALL 이라 LOT 수량 = n)의 판정 확정 검사 행 = 부분군(n = 살아 있는 OK/NG, d = NG).
 * 손 검산 · 부분군 크기 다름 · 정정(무효 행) 빼기 · 진행 중 검사 빼기 · 고정 한계(p̄)·규칙 WE1·WE4 만 · p̄ = 0 은 한계 못 냄 · 판정 훅 이벤트(P) ·
 * 단위 목록 관리도 칸 · 권한 · A사로 B사 · 직접 SQL(가드)
 */
class SpcPChartTest extends QmsTestBase {

    static final String S = "/api/qms/spc";
    static final String I = "/api/qms/insps";
    static final String E = "/api/qms/spc/events";

    long item;
    int lotSeq;

    @BeforeEach
    void pSetUp() throws Exception {
        accounts.grant(comp, userId, "QA", "qms-insp", "SEARCH", "SAVE", "DELETE");
        accounts.grant(comp, userId, "QA", "qms-spc", "SEARCH", "SAVE", "DELETE");
        long p = plan("FINAL", "FG1", null, null);
        lines(p, line("b", null, 20, fgChar2, "SALL", "VISUAL")).andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
        item = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG1'", Long.class);
    }

    long line(long insp) {
        return accounts.query(comp, "select insp_line_id from qms_insp_line where insp_id = ?", Long.class, insp);
    }

    /** 검사 하나: 전수 n 개 중 앞 d 개 NG, judge = true 면 판정 확정 → 검사 id */
    long insp(int n, int d, boolean judge) throws Exception {
        String no = "L-P-" + (++lotSeq);
        accounts.exec(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', ?, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG1'""", comp, no, n);
        long id = id(postJson(I, "{\"lotNo\":" + q(no) + ",\"inspType\":\"FINAL\",\"lotQty\":" + n + "}"));
        StringBuilder a = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            a.append(i == 1 ? "" : ",").append("{\"sampleNo\":").append(i).append(",\"okYn\":").append(q(i <= d ? "N" : "Y")).append("}");
        }
        postJson(I + "/" + id + "/meas", "{\"inspLineId\":" + line(id) + ",\"entries\":[" + a + "]}").andExpect(status().isOk());
        if (judge) {
            judge(id).andExpect(status().isOk());
        }
        return id;
    }

    ResultActions judge(long insp) throws Exception {
        return postJson(I + "/" + insp + "/judge", "{\"rowVer\":" + accounts.query(comp, "select row_ver from qms_insp where insp_id = ?", Integer.class,
                insp) + "}");
    }

    MockHttpServletRequestBuilder chart() {
        return get(S + "/chart").param("itemId", String.valueOf(item)).param("charId", String.valueOf(fgChar2)).param("inspType", "FINAL");
    }

    String limitBody(String extra) {
        return "{\"itemId\":" + item + ",\"charId\":" + fgChar2 + ",\"inspType\":\"FINAL\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    /** 손 검산(SpcCalcTest 와 같은 값): (50,2)·(50,3)·(100,4)·(25,6) → p̄ 0.0666667, n 마다 다른 UCL, 4번째 WE1. 정정·진행 중 검사는 빠진다 */
    @Test
    void p_관리도는_판정확정_계수형_행의_살아있는_OK_NG_로_손계산과_같다() throws Exception {
        insp(50, 2, true);
        // 정정: 샘플 50 을 N 으로 잘못 → 무효 → Y 다시(무효 행이 섞이면 n 51·d 4 가 된다)
        long fix = insp(50, 3, false);
        long bad = accounts.query(comp, "select meas_id from qms_insp_meas where insp_line_id = ? and sample_no = 50", Long.class, line(fix));
        postJson(I + "/" + fix + "/meas/void", "{\"measId\":" + bad + "}").andExpect(status().isOk());
        postJson(I + "/" + fix + "/meas", "{\"inspLineId\":" + line(fix) + ",\"entries\":[{\"sampleNo\":50,\"okYn\":\"N\"}]}").andExpect(status().isOk());
        // ↑ 다시 넣은 값도 N 이면 d 4. 다시 무효 → Y
        long again = accounts.query(comp, "select meas_id from qms_insp_meas where insp_line_id = ? and sample_no = 50 and void_of_id is null "
                + "and not exists (select 1 from qms_insp_meas v where v.void_of_id = qms_insp_meas.meas_id)", Long.class, line(fix));
        postJson(I + "/" + fix + "/meas/void", "{\"measId\":" + again + "}").andExpect(status().isOk());
        postJson(I + "/" + fix + "/meas", "{\"inspLineId\":" + line(fix) + ",\"entries\":[{\"sampleNo\":50,\"okYn\":\"Y\"}]}").andExpect(status().isOk());
        judge(fix).andExpect(status().isOk());
        insp(100, 4, true);
        insp(30, 30, false);   // 진행 중 — 빠짐
        insp(25, 6, true);
        String body = mvc.perform(chart().with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chartType").value("P"))
                .andExpect(jsonPath("$.data.source").value("CALC"))
                .andExpect(jsonPath("$.data.rules[*]").value(org.hamcrest.Matchers.contains("WE1")))
                .andExpect(jsonPath("$.data.points.length()").value(4))
                .andExpect(jsonPath("$.data.capability").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(body, "$.data.center")).doubleValue()).isCloseTo(15.0 / 225, within(1e-9));
        assertThat(((Number) JsonPath.read(body, "$.data.sigma")).doubleValue()).isCloseTo(0.2494438, within(1e-6));
        List<Integer> ns = JsonPath.read(body, "$.data.points[*].n");
        assertThat(ns).containsExactly(50, 50, 100, 25);
        List<Number> ds = JsonPath.read(body, "$.data.points[*].range");
        assertThat(ds.stream().map(Number::intValue).toList()).as("정정 뒤 d = 3").containsExactly(2, 3, 4, 6);
        assertThat(((Number) JsonPath.read(body, "$.data.points[0].xUcl")).doubleValue()).isCloseTo(0.1724968, within(1e-6));
        assertThat(((Number) JsonPath.read(body, "$.data.points[2].xUcl")).doubleValue()).isCloseTo(0.1414998, within(1e-6));
        assertThat(((Number) JsonPath.read(body, "$.data.points[3].xUcl")).doubleValue()).isCloseTo(0.2163330, within(1e-6));
        assertThat(((Number) JsonPath.read(body, "$.data.points[0].xLcl")).doubleValue()).isZero();
        List<List<String>> rules = JsonPath.read(body, "$.data.points[*].rules");
        assertThat(rules).containsExactly(List.of(), List.of(), List.of(), List.of("WE1"));
        // p 에 맞지 않는 규칙 요청 → 400
        mvc.perform(chart().param("rules", "WE2").with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SPC_P_RULES"));
        // 단위 목록: 관리도 칸 P
        mvc.perform(get(S + "/units/grid").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.list[?(@.charId == " + fgChar2 + ")].chartType").value(org.hamcrest.Matchers.contains("P")));
    }

    /** 고정 한계(p̄) — 20 부분군 이상, center = p̄·sigma = √(p̄(1-p̄))·chart_type P. 새 검사 판정 때 한계 밖이면 위반 이벤트(P) */
    @Test
    void 고정_p_한계로_새_검사를_판정하면_위반_이벤트_P() throws Exception {
        for (int i = 0; i < 20; i++) {
            insp(20, i % 4 == 0 ? 2 : 1, true);   // Σd = 5·2 + 15·1 = 25, Σn = 400 → p̄ 0.0625
        }
        postJson(S + "/limits", limitBody("\"rules\":[\"WE2\"]")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SPC_P_RULES"));
        long lim = id(postJson(S + "/limits", limitBody("\"rules\":[\"WE1\",\"WE4\"]")));
        assertThat(accounts.query(comp, "select chart_type || ':' || center::float8 || ':' || rules from qms_spc_limit where limit_id = ?", String.class,
                lim)).isEqualTo("P:0.0625:WE1,WE4");
        assertThat(accounts.query(comp, "select sigma::float8 from qms_spc_limit where limit_id = ?", Double.class, lim))
                .isCloseTo(Math.sqrt(0.0625 * 0.9375), within(1e-9));
        mvc.perform(chart().with(admin)).andExpect(jsonPath("$.data.source").value("FIXED")).andExpect(jsonPath("$.data.limit.chartType").value("P"));
        // 한계 안: 이벤트 없음 / 한계 밖(n 20, d 8 → p 0.4 > 0.0625 + 3·0.2420615/√20 = 0.2248803): WE1
        long ok = insp(20, 1, true);
        long bad = insp(20, 8, true);
        assertThat(accounts.query(comp, "select count(*) from qms_spc_event where insp_id = ?", Long.class, ok)).isZero();
        assertThat(accounts.query(comp, "select string_agg(chart_type || ':' || rule_cd || ':' || n || ':' || point_mean::float8 || ':' || point_range::float8, ',') "
                + "from qms_spc_event where insp_id = ?", String.class, bad)).isEqualTo("P:WE1:20:0.4:8");
        long ev = accounts.query(comp, "select event_id from qms_spc_event where insp_id = ?", Long.class, bad);
        accounts.grant(comp, userId, "QA", "qms-spc", "SEARCH", "SAVE", "DELETE");
        mvc.perform(get(E + "/" + ev).with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.event.chartType").value("P"));
    }

    @Test
    void 불량이_없으면_p_한계를_못_낸다() throws Exception {
        for (int i = 0; i < 20; i++) {
            insp(5, 0, true);
        }
        postJson(S + "/limits", limitBody("")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("SPC_NO_VARIATION"));
    }

    @Test
    void 권한_A사로_B사_직접SQL() throws Exception {
        insp(20, 1, true);
        long u2 = accounts.newUser(comp, "p2", PW);
        mvc.perform(chart().with(accounts.as(comp, u2, "p2"))).andExpect(status().isForbidden());
        String other = accounts.newComp("QP");
        long bu = accounts.newUser(other, "qp1", PW);
        accounts.grant(other, bu, "QA", "qms-spc", "SEARCH", "SAVE");
        mvc.perform(chart().with(accounts.as(other, bu, "qp1"))).andExpect(status().isNotFound());
        // 직접 SQL: p 한계를 계량형 특성에 / X̄ 한계를 계수형 특성에 / p 에 WE2 / p̄ 1 이상
        String ins = "insert into qms_spc_limit (comp_cd, item_id, char_id, insp_type, center, sigma, subgroup_cnt, from_at, to_at, rules, chart_type, "
                + "created_by, updated_by) values (?, ?, ?, 'FINAL', ?, 0.2, 20, now(), now(), ?, ?, 't', 't')";
        constraint(comp, "fk_qms_spc_limit_char_comp", () -> accounts.exec(comp, ins, comp, item, fgChar1, 0.1, "WE1", "P"));
        constraint(comp, "fk_qms_spc_limit_char_comp", () -> accounts.exec(comp, ins, comp, item, fgChar2, 10, "WE1", "XBAR_R"));
        constraint(comp, "ck_qms_spc_limit_chart", () -> accounts.exec(comp, ins, comp, item, fgChar2, 0.1, "WE1,WE2", "P"));
        constraint(comp, "ck_qms_spc_limit_chart", () -> accounts.exec(comp, ins, comp, item, fgChar2, 1, "WE1", "P"));
    }
}
