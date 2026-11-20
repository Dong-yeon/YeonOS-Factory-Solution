package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * P3 QMS 2부 Q10 SPC — 관리도(부분군 = 판정 확정 검사 행, 살아 있는 값만) + 고정 한계(정하기·무효·이력)
 * + 이상 사용(무효·정정 측정값 제외, 진행 중·무효 검사 제외, 부분군 부족·변동 없음, 계수형, 입력, 권한, A사로 B사, 직접 SQL).
 */
class SpcApiTest extends QmsTestBase {

    static final String S = "/api/qms/spc";
    static final String I = "/api/qms/insps";
    static final String G = "/api/qms/gauges";
    /** 부분군 값: 평균 10.00, 범위 0.04 → σ̂ = 0.04/2.326 */
    static final String[] BASE = {"9.98", "10.00", "10.02", "10.00", "10.00"};

    long gauge;
    long item;
    int lotSeq;

    @BeforeEach
    void spcSetUp() throws Exception {
        accounts.grant(comp, userId, "QA", "qms-insp", "SEARCH", "SAVE", "DELETE");
        accounts.grant(comp, userId, "QA", "qms-gauge-calib", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "QA", "qms-spc", "SEARCH", "SAVE", "DELETE");
        accounts.exec(comp, """
                insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, gauge_class, calib_cycle_days, created_by, updated_by)
                values (?, 'GSPC', 'GAUGE', 'SPC 캘리퍼', 'CALIPER', 365, 't', 't')""", comp);
        gauge = accounts.query(comp, "select asset_id from mst_phys_asset where asset_cd = 'GSPC'", Long.class);
        postJson(G + "/" + gauge + "/calibs", "{\"calibDate\":" + q(today.minusDays(10)) + ",\"result\":\"OK\",\"agency\":\"INTERNAL\"}")
                .andExpect(status().isOk());
        // 출하검사 확정본: FG1-1 계량(캘리퍼, 고정 5) + FG1-2 계수(육안, 고정 5)
        long p = plan("FINAL", "FG1", null, null);
        lines(p, line("a", null, 10, fgChar1, "S5", "CALIPER"), line("b", null, 20, fgChar2, "S5", "VISUAL")).andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
        item = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG1'", Long.class);
    }

    /** 검사 하나: 계량 행 5개 값 + 계수 행 모두 OK, judge = true 면 판정 확정 → 검사 id */
    long insp(boolean judge, String... values) throws Exception {
        String no = "L-SPC-" + (++lotSeq);
        accounts.exec(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', 50, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG1'""", comp, no);
        long id = id(postJson(I, "{\"lotNo\":" + q(no) + ",\"inspType\":\"FINAL\"}"));
        StringBuilder v = new StringBuilder();
        StringBuilder a = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            v.append(i == 0 ? "" : ",").append("{\"sampleNo\":").append(i + 1).append(",\"value\":").append(values[i])
                    .append(",\"gaugeAssetId\":").append(gauge).append("}");
            a.append(i == 0 ? "" : ",").append("{\"sampleNo\":").append(i + 1).append(",\"okYn\":\"Y\"}");
        }
        postJson(I + "/" + id + "/meas", "{\"inspLineId\":" + line(id, 10) + ",\"entries\":[" + v + "]}").andExpect(status().isOk());
        postJson(I + "/" + id + "/meas", "{\"inspLineId\":" + line(id, 20) + ",\"entries\":[" + a + "]}").andExpect(status().isOk());
        if (judge) {
            judge(id).andExpect(status().isOk());
        }
        return id;
    }

    long line(long insp, int no) {
        return accounts.query(comp, "select insp_line_id from qms_insp_line where insp_id = ? and line_no = ?", Long.class, insp, no);
    }

    ResultActions judge(long insp) throws Exception {
        return postJson(I + "/" + insp + "/judge", "{\"rowVer\":" + ver(insp) + "}");
    }

    int ver(long insp) {
        return accounts.query(comp, "select row_ver from qms_insp where insp_id = ?", Integer.class, insp);
    }

    MockHttpServletRequestBuilder chart(long charId) {
        return get(S + "/chart").param("itemId", String.valueOf(item)).param("charId", String.valueOf(charId)).param("inspType", "FINAL");
    }

    String limitBody(String extra) {
        return "{\"itemId\":" + item + ",\"charId\":" + fgChar1 + ",\"inspType\":\"FINAL\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    @Test
    void 관리도는_판정확정_검사의_살아있는_값만_쓰고_고정한계로_새_검사를_판정한다() throws Exception {
        for (int i = 0; i < 18; i++) {
            insp(true, BASE);
        }
        // 정정: 샘플 1 을 12.00 으로 잘못 넣고 무효 → 9.98 다시 (무효 값이 섞이면 n = 6·범위 2.02 가 된다)
        long fix = insp(false, "12.00", "10.00", "10.02", "10.00", "10.00");
        long bad = accounts.query(comp, "select meas_id from qms_insp_meas where insp_line_id = ? and sample_no = 1", Long.class, line(fix, 10));
        postJson(I + "/" + fix + "/meas/void", "{\"measId\":" + bad + "}").andExpect(status().isOk());
        postJson(I + "/" + fix + "/meas", "{\"inspLineId\":" + line(fix, 10) + ",\"entries\":[{\"sampleNo\":1,\"value\":9.98,\"gaugeAssetId\":"
                + gauge + "}]}").andExpect(status().isOk());
        judge(fix).andExpect(status().isOk());
        insp(true, BASE);
        // 제외: 진행 중 검사, 판정 뒤 무효된 검사(값이 튀어도 관리도에 안 나온다)
        insp(false, "11", "11", "11", "11", "11");
        long voided = insp(true, "9", "9", "9", "9", "9");
        postJson(I + "/" + voided + "/void", "{\"rowVer\":" + ver(voided) + ",\"reason\":\"잘못 만든 검사\"}").andExpect(status().isOk());

        mvc.perform(chart(fgChar1).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("CALC"))
                .andExpect(jsonPath("$.data.points.length()").value(20))
                .andExpect(jsonPath("$.data.points[*].n").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(5))))
                .andExpect(jsonPath("$.data.points[18].range").value(closeTo(0.04, 1e-9)))
                .andExpect(jsonPath("$.data.center").value(closeTo(10.0, 1e-9)))
                .andExpect(jsonPath("$.data.sigma").value(closeTo(0.017197, 1e-6)))
                .andExpect(jsonPath("$.data.rules", contains("WE1", "WE2")))
                // 공정능력 손 계산: Cp = 0.1/(6·0.017197) = 0.969167, 전체 s = √(0.016/99) = 0.012713 → Pp = 1.311011
                .andExpect(jsonPath("$.data.capability.n").value(100))
                .andExpect(jsonPath("$.data.capability.cp").value(closeTo(0.969167, 1e-5)))
                .andExpect(jsonPath("$.data.capability.pp").value(closeTo(1.311011, 1e-5)))
                .andExpect(jsonPath("$.data.specMixed").value(false));
        // 끝 날짜 어제 → 부분군 없음 / 최근 5개만
        mvc.perform(chart(fgChar1).param("to", today.minusDays(1).toString()).with(admin)).andExpect(jsonPath("$.data.points", empty()));
        mvc.perform(chart(fgChar1).param("n", "5").with(admin)).andExpect(jsonPath("$.data.points.length()").value(5));

        // 고정 한계 = 보이는 20군 계산값
        long lim = id(postJson(S + "/limits", limitBody("\"remark\":\"양산 초기\"")));
        assertThat(accounts.query(comp, "select round(center, 6) || ':' || round(sigma, 6) || ':' || subgroup_cnt || ':' || rules from qms_spc_limit "
                + "where limit_id = ?", String.class, lim)).isEqualTo("10.000000:0.017197:20:WE1,WE2");
        postJson(S + "/limits", limitBody("")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_SPC_LIMIT_EXISTS"));
        // 새 검사 평균 10.05 → z = 0.05/(0.017197/√5) = 6.50 → WE1. 고정이라 중심은 그대로
        insp(true, "10.03", "10.05", "10.07", "10.05", "10.05");
        mvc.perform(chart(fgChar1).with(admin)).andExpect(jsonPath("$.data.source").value("FIXED"))
                .andExpect(jsonPath("$.data.limit.limitId").value(lim))
                .andExpect(jsonPath("$.data.center").value(closeTo(10.0, 1e-9)))
                .andExpect(jsonPath("$.data.points.length()").value(21))
                .andExpect(jsonPath("$.data.points[20].z").value(closeTo(6.5014, 1e-3)))
                .andExpect(jsonPath("$.data.points[20].rules", contains("WE1")))
                .andExpect(jsonPath("$.data.calcCenter").value(org.hamcrest.Matchers.greaterThan(10.0)));
        // 규칙을 고르면 그 규칙으로(WE4 는 8점 같은 쪽이라 이 데이터에선 안 걸림)
        mvc.perform(chart(fgChar1).param("rules", "WE1", "WE4").with(admin)).andExpect(jsonPath("$.data.rules", contains("WE1", "WE4")));
        mvc.perform(get(S + "/units").with(admin)).andExpect(jsonPath("$.data[?(@.charId == " + fgChar1 + ")].limitId",
                contains((int) lim))).andExpect(jsonPath("$.data[?(@.charId == " + fgChar1 + ")].subgroupCnt", contains(21)));
        mvc.perform(get(S + "/units").param("keyword", "없는말").with(admin)).andExpect(jsonPath("$.data", empty()));
        // 무효 → 다시 계산으로, 이력 2행(다시 정한 뒤)
        postJson(S + "/limits/" + lim + "/void", "{\"rowVer\":0,\"reason\":\"공정 변경\"}").andExpect(status().isOk());
        postJson(S + "/limits/" + lim + "/void", "{\"rowVer\":1,\"reason\":\"다시\"}").andExpect(status().isConflict());
        mvc.perform(chart(fgChar1).with(admin)).andExpect(jsonPath("$.data.source").value("CALC"));
        id(postJson(S + "/limits", limitBody("")));
        mvc.perform(get(S + "/limits").param("itemId", String.valueOf(item)).param("charId", String.valueOf(fgChar1)).param("inspType", "FINAL")
                .with(admin)).andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[1].status").value("VOID"));
    }

    @Test
    void 이상_부분군_부족_변동없음_계수형_입력() throws Exception {
        String[] flat = {"10", "10", "10", "10", "10"};
        for (int i = 0; i < 19; i++) {
            insp(true, flat);
        }
        postJson(S + "/limits", limitBody("")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SPC_FEW_SUBGROUPS"));
        insp(true, flat);
        postJson(S + "/limits", limitBody("")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SPC_NO_VARIATION"));
        // σ̂ = 0 이면 z·지수 없음, 경보 없음
        mvc.perform(chart(fgChar1).with(admin)).andExpect(jsonPath("$.data.points[0].z").doesNotExist())
                .andExpect(jsonPath("$.data.capability.cp").doesNotExist());
        // 계수형은 p 관리도(docs/29_P3 28장 — 전에는 400 SPC_NOT_VARIABLE)
        mvc.perform(chart(fgChar2).with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.chartType").value("P"));
        mvc.perform(get(S + "/chart").param("charId", String.valueOf(fgChar1)).with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].code", contains("REQUIRED", "REQUIRED")));
        mvc.perform(chart(fgChar1).param("n", "0").with(admin)).andExpect(jsonPath("$.errors[0].code").value("SPC_N_RANGE"));
        mvc.perform(chart(fgChar1).param("n", "201").with(admin)).andExpect(jsonPath("$.errors[0].code").value("SPC_N_RANGE"));
        mvc.perform(chart(fgChar1).param("rules", "WE9").with(admin)).andExpect(jsonPath("$.errors[0].field").value("rules"));
        mvc.perform(get(S + "/chart").param("itemId", String.valueOf(item)).param("charId", String.valueOf(fgChar1)).param("inspType", "XX")
                .with(admin)).andExpect(jsonPath("$.errors[0].field").value("inspType"));
        mvc.perform(get(S + "/chart").param("itemId", "999999999").param("charId", String.valueOf(fgChar1)).param("inspType", "FINAL")
                .with(admin)).andExpect(status().isNotFound());
        mvc.perform(chart(fgChar1).param("procId", "999999999").with(admin)).andExpect(status().isNotFound());
        postJson(S + "/limits/999999999/void", "{\"rowVer\":0,\"reason\":\"x\"}").andExpect(status().isNotFound());
        postJson(S + "/limits/1/void", "{\"rowVer\":0,\"reason\":\" \"}").andExpect(status().isBadRequest());
        postJson(S + "/limits", limitBody("\"remark\":\"" + "가".repeat(1001) + "\"")).andExpect(jsonPath("$.errors[0].field").value("remark"));
    }

    @Test
    void 권한_A사로_B사_직접SQL() throws Exception {
        for (int i = 0; i < 20; i++) {
            insp(true, BASE);
        }
        long lim = id(postJson(S + "/limits", limitBody("")));
        // 권한: 없음 403, 조회만은 정하기 403
        long reader = accounts.newUser(comp, "reader1", PW);
        RequestPostProcessor rd = accounts.as(comp, reader, "reader1");
        mvc.perform(chart(fgChar1).with(rd)).andExpect(status().isForbidden());
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "QV", "qms-spc", "SEARCH");
        RequestPostProcessor vw = accounts.as(comp, viewer, "viewer1");
        mvc.perform(chart(fgChar1).with(vw)).andExpect(status().isOk());
        mvc.perform(json(post(S + "/limits"), limitBody("")).with(vw)).andExpect(status().isForbidden());
        mvc.perform(json(post(S + "/limits/" + lim + "/void"), "{\"rowVer\":0,\"reason\":\"x\"}").with(vw)).andExpect(status().isForbidden());
        // A사로 B사: 단위 목록 0, 관리도·이력·무효 404, 정하기도 404
        String other = accounts.newComp("QM");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-spc", "SEARCH", "SAVE", "DELETE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        mvc.perform(get(S + "/units").with(b)).andExpect(jsonPath("$.data", empty()));
        mvc.perform(chart(fgChar1).with(b)).andExpect(status().isNotFound());
        mvc.perform(get(S + "/limits").param("itemId", String.valueOf(item)).param("charId", String.valueOf(fgChar1)).param("inspType", "FINAL")
                .with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(S + "/limits/" + lim + "/void"), "{\"rowVer\":0,\"reason\":\"x\"}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(S + "/limits"), limitBody("")).with(b)).andExpect(status().isNotFound());
        assertThat(accounts.query(comp, "select status from qms_spc_limit where limit_id = ?", String.class, lim)).isEqualTo("ACTIVE");
        // 직접 SQL
        String ins = "insert into qms_spc_limit (comp_cd, item_id, char_id, insp_type, center, sigma, subgroup_cnt, from_at, to_at, rules, "
                + "created_by, updated_by) values (?, ?, ?, 'PROCESS', 10, ?, 20, now(), now(), ?, 't', 't')";
        constraint(comp, "ck_qms_spc_limit_value", () -> accounts.exec(comp, ins, comp, item, fgChar1, 0, "WE1"));
        constraint(comp, "ck_qms_spc_limit_rules", () -> accounts.exec(comp, ins, comp, item, fgChar1, 1, "WE5"));
        constraint(comp, "ck_qms_spc_limit_rules", () -> accounts.exec(comp, ins, comp, item, fgChar1, 1, "WE2,WE1,"));
        constraint(comp, "uq_qms_spc_limit_live", () -> accounts.exec(comp, ins.replace("'PROCESS'", "'FINAL'"), comp, item, fgChar1, 1, "WE1"));
        constraint(comp, "fk_qms_spc_limit_char_comp", () -> accounts.exec(comp, ins, comp, item, fgChar2, 1, "WE1"));
        constraint(other, "fk_qms_spc_limit_item_comp", () -> accounts.exec(other, ins, other, item, fgChar1, 1, "WE1"));
        constraint(comp, "ck_qms_spc_limit_active", () -> accounts.exec(comp,
                ins.replace("rules, created_by", "rules, status, voided_at, voided_by, void_reason, created_by")
                        .replace("?, 't', 't')", "?, 'VOID', now(), 't', 'x', 't', 't')"), comp, item, fgChar1, 1, "WE1"));
        constraint(comp, "ck_qms_spc_limit_immutable", () -> accounts.exec(comp, "update qms_spc_limit set center = 11 where limit_id = ?", lim));
        constraint(comp, "ck_qms_spc_limit_immutable", () -> accounts.exec(comp,
                "update qms_spc_limit set rules = 'WE1' where limit_id = ?", lim));
        sqlState("42501", () -> accounts.exec(comp, "delete from qms_spc_limit where limit_id = ?", lim));
        assertThat(accounts.query(other, "select count(*) from qms_spc_limit", Long.class)).isZero();
        assertThat(accounts.exec(other, "update qms_spc_limit set status = 'VOID', void_reason = 'x', voided_by = 't', voided_at = now()")).isZero();
        accounts.exec(comp, "update qms_spc_limit set status = 'VOID', void_reason = 'x', voided_by = 't', voided_at = now() where limit_id = ?", lim);
        constraint(comp, "ck_qms_spc_limit_immutable", () -> accounts.exec(comp,
                "update qms_spc_limit set status = 'ACTIVE', void_reason = null, voided_by = null, voided_at = null where limit_id = ?", lim));
        // 무효 뒤 다시 정한 한계를 사유 없이 무효
        accounts.exec(comp, ins.replace("'PROCESS'", "'FINAL'"), comp, item, fgChar1, 1, "WE1");
        constraint(comp, "ck_qms_spc_limit_void", () -> accounts.exec(comp,
                "update qms_spc_limit set status = 'VOID', voided_at = now(), voided_by = 't' where status = 'ACTIVE'"));
    }

    @Test
    void 관리도_단위_grid_목록과_같은_묶음_정렬허용_권한_B사() throws Exception {
        insp(true, "1.10", "1.20", "1.30", "1.40", "1.50");
        insp(true, "1.20", "1.30", "1.40", "1.50", "1.60");
        insp(false, "1.20", "1.30", "1.40", "1.50", "1.60");   // 판정 전은 묶음 밖
        // 단위 = 계량형 FG1-1 + 계수형 FG1-2(p 관리도, 28장) — 같은 시각이면 특성 id 순
        mvc.perform(get(S + "/units/grid").with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.list[0].subgroupCnt").value(2)).andExpect(jsonPath("$.list[0].limitYn").value("N"))
                .andExpect(jsonPath("$.list[0].unitKey").value(item + ":" + fgChar1 + ":FINAL:0"))
                .andExpect(jsonPath("$.list[0].chartType").value("XBAR_R")).andExpect(jsonPath("$.list[1].chartType").value("P"));
        mvc.perform(get(S + "/units/grid").param("keyword", "없는 품목").with(admin)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(S + "/units/grid").param("sort", "subgroupCnt,desc").param("size", "1").with(admin)).andExpect(jsonPath("$.list.length()").value(1));
        mvc.perform(get(S + "/units/grid").param("sort", "charNm,asc").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(S + "/units/grid").param("size", "100000").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(S + "/units/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(org.hamcrest.Matchers.hasItems("itemCd", "subgroupCnt", "limitYn")));
        String other = accounts.newComp("QP");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "QA", "qms-spc", "SEARCH");
        mvc.perform(get(S + "/units/grid").with(accounts.as(other, bu, "admin1"))).andExpect(jsonPath("$.total").value(0));
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "OTHER", "qms-ncr", "SEARCH");
        mvc.perform(get(S + "/units/grid").with(accounts.as(comp, none, "none1"))).andExpect(status().isForbidden());
        mvc.perform(get(S + "/units/columns").with(accounts.as(comp, none, "none1"))).andExpect(status().isForbidden());
    }
}
