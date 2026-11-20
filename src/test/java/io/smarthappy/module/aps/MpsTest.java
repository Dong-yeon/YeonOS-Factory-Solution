package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;

/**
 * MPS 기준생산계획 (docs/38 16장) — 수요(수주·최신 내시·소진)·지시 공급·제안·로트, 편집·사유, 확정·대체·낡음, 확정 구간 이월,
 * 결과 불변(직접 SQL), MPS 품목 검증, 권한, A사로 B사. 공통 데이터 = {@link ApsTestBase}(FG 지시 3 + 2, 완료일 내일)
 */
class MpsTest extends ApsTestBase {

    static final String M = "/api/aps/mps";

    long fg;
    long c1;
    long c2;

    @BeforeEach
    void mpsSetUp() throws Exception {
        accounts.grant(comp, userId, "APS", "aps-mps", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, userId, "APS", "aps-item-param", "SEARCH", "SAVE");
        fg = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG'", Long.class);
        c1 = cust("C1");
        c2 = cust("C2");
        item(admin, "P1", "FG").andExpect(status().isOk());
    }

    // ---------- 도우미 ----------

    long cust(String cd) {
        return accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, ?, ?, 'SALES', 't', 't') returning cust_id""", Long.class, comp, cd, cd + " 고객");
    }

    void order(long cust, String qty, LocalDate due) {
        long o = accounts.query(comp, """
                insert into sal_order (comp_cd, order_no, cust_id, order_date, created_by, updated_by)
                values (?, ?, ?, (now() at time zone 'Asia/Seoul')::date, 't', 't') returning order_id""", Long.class, comp,
                "SO-" + System.nanoTime(), cust);
        accounts.exec(comp, """
                insert into sal_order_line (comp_cd, order_id, line_no, item_id, qty, due_date, created_by, updated_by)
                values (?, ?, 1, ?, ?::numeric, ?, 't', 't')""", comp, o, fg, qty, java.sql.Date.valueOf(due));
    }

    void forecast(long cust, int revNo, String qty, LocalDate day) {
        long rev = accounts.query(comp, """
                insert into sal_forecast_rev (comp_cd, cust_id, rev_no, source, created_by)
                values (?, ?, ?, 'MANUAL', 't') returning rev_id""", Long.class, comp, cust, revNo);
        accounts.exec(comp, """
                insert into sal_forecast_line (comp_cd, rev_id, item_id, bucket_type, bucket_start, qty)
                values (?, ?, ?, 'DAY', ?, ?::numeric)""", comp, rev, fg, java.sql.Date.valueOf(day), qty);
    }

    /** MPS 대상 품목(공장·품목만 — 안전재고·로트는 계획 기준정보, docs/38 17장) */
    ResultActions item(RequestPostProcessor who, String plant, String itemCd) throws Exception {
        return mvc.perform(json(post(M + "/items"), "{\"plantCd\":\"" + plant + "\",\"itemCd\":\"" + itemCd + "\"}").with(who));
    }

    /** 계획 기준정보(aps-item-param) — 등록 */
    ResultActions param(RequestPostProcessor who, String plant, String itemCd, String safety, String rule, String lot) throws Exception {
        return mvc.perform(json(post("/api/aps/item-params"), "{\"plantCd\":\"" + plant + "\",\"itemCd\":\"" + itemCd + "\",\"safetyQty\":\""
                + safety + "\",\"lotRule\":\"" + rule + "\"" + (lot == null ? "" : ",\"lotQty\":\"" + lot + "\"") + "}").with(who));
    }

    ResultActions mps(RequestPostProcessor who, LocalDate first, int days) throws Exception {
        return mvc.perform(json(post(M), "{\"plantCd\":\"P1\",\"horizonStart\":\"" + first + "\",\"horizonDays\":" + days
                + ",\"mpsNm\":\"ZT MPS\"}").with(who));
    }

    String line(long mpsId, String field) throws Exception {
        String body = mvc.perform(get(M + "/" + mpsId).with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> v = JsonPath.read(body, "$.data.rows[0].cells[*]." + field);
        return String.join(",", v);
    }

    long cell(long mpsId, LocalDate d) {
        return accounts.query(comp, "select mps_cell_id from aps_mps_cell where mps_id = ? and bucket_date = ?", Long.class, mpsId,
                java.sql.Date.valueOf(d));
    }

    ResultActions firm(RequestPostProcessor who, long mpsId, int rowVer) throws Exception {
        return mvc.perform(json(post(M + "/" + mpsId + "/firm"), "{\"rowVer\":" + rowVer + "}").with(who));
    }

    void fence(int days) {
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'APS_MPS_FENCE_DAYS', ?, 't', 't')",
                comp, String.valueOf(days));
    }

    // ---------- 계산 ----------

    @Test
    void 수주와_최신_내시를_소진해_수요로_지시를_공급으로_제안을_만들고_지시는_바꾸지_않는다() throws Exception {
        fence(0);
        order(c1, "4", tomorrow.plusDays(1));
        forecast(c1, 1, "10", tomorrow.plusDays(1));     // 수주 4 가 소진 → 6
        forecast(c2, 1, "100", tomorrow.plusDays(2));    // 옛 버전 — 쓰지 않는다
        forecast(c2, 2, "7", tomorrow.plusDays(2));
        String before = woState();
        long m = id(mps(admin, tomorrow, 7));
        assertThat(woState()).isEqualTo(before);
        assertThat(line(m, "supplyWo")).isEqualTo("5,0,0,0,0,0,0");          // 지시 3 + 2(완료일 내일)
        assertThat(line(m, "demandOrder")).isEqualTo("0,4,0,0,0,0,0");
        assertThat(line(m, "demandFcst")).isEqualTo("0,6,7,0,0,0,0");
        assertThat(line(m, "propQty")).isEqualTo("0,5,7,0,0,0,0");
        assertThat(line(m, "planQty")).isEqualTo("0,5,7,0,0,0,0");
        assertThat(line(m, "pab")).isEqualTo("5,0,0,0,0,0,0");
        mvc.perform(get(M + "/" + m).with(admin)).andExpect(jsonPath("$.data.mps.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.mps.itemCnt").value(1)).andExpect(jsonPath("$.data.currentMpsId").doesNotExist());

        // 로트 FX 10 → 배수로 (계획 기준정보 — MPS 는 이 표를 읽는다, docs/38 17.1)
        param(admin, "P1", "FG", "0", "FX", "10").andExpect(status().isOk());
        long m2 = id(mps(admin, tomorrow, 7));
        assertThat(line(m2, "propQty")).isEqualTo("0,10,10,0,0,0,0");
        assertThat(line(m2, "pab")).isEqualTo("5,5,8,8,8,8,8");
    }

    // ---------- 편집·확정 ----------

    @Test
    void 확정_구간_칸은_사유와_함께_고치고_확정하면_다음_계산이_그_값을_이어받는다() throws Exception {
        fence(3);   // 오늘 + 3 전까지 = 내일·모레
        order(c1, "5", tomorrow);
        order(c1, "5", tomorrow.plusDays(1));
        long m1 = id(mps(admin, tomorrow, 7));
        assertThat(line(m1, "fencedYn")).isEqualTo("Y,Y,N,N,N,N,N");
        long cell = cell(m1, tomorrow.plusDays(1));
        // 구간 칸을 계산 값(5)과 다르게 — 사유 없으면 칸 오류
        mvc.perform(json(put(M + "/" + m1 + "/cells"), "{\"rowVer\":0,\"cells\":[{\"cellId\":" + cell + ",\"planQty\":\"9\"}]}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].rowKey").value(String.valueOf(cell)))
                .andExpect(jsonPath("$.errors[0].code").value("APS_MPS_FENCE_REASON"));
        mvc.perform(json(put(M + "/" + m1 + "/cells"), "{\"rowVer\":0,\"cells\":[{\"cellId\":" + cell
                + ",\"planQty\":\"9\",\"reason\":\"ZT 고객 증량 확정\"}]}").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));
        // 낡은 rowVer 409
        mvc.perform(json(put(M + "/" + m1 + "/cells"), "{\"rowVer\":0,\"cells\":[{\"cellId\":" + cell + ",\"planQty\":\"9\"}]}").with(admin))
                .andExpect(status().isConflict());
        firm(admin, m1, 1).andExpect(status().isOk());
        mvc.perform(get(M + "/" + m1).with(admin)).andExpect(jsonPath("$.data.mps.status").value("FIRMED"))
                .andExpect(jsonPath("$.data.currentMpsId").value((int) m1));

        long m2 = id(mps(admin, tomorrow, 7));
        mvc.perform(get(M + "/" + m2).with(admin)).andExpect(jsonPath("$.data.mps.baseMpsId").value((int) m1));
        // 내일(구간): 이전 확정 0 → 0 이월 / 모레(구간): 9 이월 / 그 뒤: 제안
        assertThat(line(m2, "baseQty")).isEqualTo("0,9,0,0,0,0,0");
        assertThat(line(m2, "propQty").split(",")[0]).isEqualTo("0");   // 지시 공급 5 가 내일 수주 5 를 덮는다
        // 확정 MPS 는 고칠 수 없다(서비스·직접 SQL)
        mvc.perform(json(put(M + "/" + m1 + "/cells"), "{\"rowVer\":2,\"cells\":[{\"cellId\":" + cell + ",\"planQty\":\"1\",\"reason\":\"x\"}]}")
                .with(admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("STATE"));
        Throwable t = catchThrowable(() -> accounts.exec(comp, "update aps_mps_cell set plan_qty = 1 where mps_cell_id = ?", cell));
        assertThat(PgErrors.constraint(t)).contains("ck_aps_mps_cell_draft");

        // 같은 기준(m1)으로 계산한 두 초안 — 먼저 확정한 쪽만, 뒤는 낡음
        long m3 = id(mps(admin, tomorrow, 7));
        firm(admin, m2, 0).andExpect(status().isOk());
        firm(admin, m3, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APS_MPS_STALE"));
        mvc.perform(get(M + "/" + m1).with(admin)).andExpect(jsonPath("$.data.mps.status").value("SUPERSEDED"));
        assertThat(accounts.query(comp, "select count(*) from aps_mps where status = 'FIRMED'", Long.class)).isEqualTo(1L);
        // 폐기는 초안만·사유 필수
        mvc.perform(json(post(M + "/" + m3 + "/discard"), "{\"rowVer\":0,\"reason\":\" \"}").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(json(post(M + "/" + m3 + "/discard"), "{\"rowVer\":0,\"reason\":\"ZT 낡음\"}").with(admin)).andExpect(status().isOk());
        mvc.perform(json(post(M + "/" + m2 + "/discard"), "{\"rowVer\":1,\"reason\":\"x\"}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("STATE"));
        // 칸 변경 감사(사람이 고친 것만)
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'aps_mps_cell'", Long.class)).isEqualTo(1L);
    }

    // ---------- 직접 SQL ----------

    @Test
    void 결과_행은_계산_밖에서_넣거나_지울_수_없고_계산_칸은_고정() throws Exception {
        long m = id(mps(admin, tomorrow, 7));
        long row = accounts.query(comp, "select mps_row_id from aps_mps_row where mps_id = ?", Long.class, m);
        Throwable ins = catchThrowable(() -> accounts.exec(comp, """
                insert into aps_mps_cell (comp_cd, mps_id, mps_row_id, bucket_date) values (?, ?, ?, ?)""",
                comp, m, row, java.sql.Date.valueOf(tomorrow.plusDays(30))));
        assertThat(PgErrors.constraint(ins)).contains("ck_aps_mps_result_calc");
        assertThatThrownBy(() -> accounts.exec(comp, "delete from aps_mps_cell where mps_id = ?", m))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> accounts.exec(comp, "update aps_mps_row set stock_qty = 99 where mps_id = ?", m))
                .rootCause().hasMessageContaining("permission denied");
        Throwable fixed = catchThrowable(() -> accounts.exec(comp, "update aps_mps_cell set prop_qty = 99 where mps_id = ?", m));
        assertThat(PgErrors.constraint(fixed)).contains("ck_aps_mps_cell_fixed");
        Throwable head = catchThrowable(() -> accounts.exec(comp, "update aps_mps set horizon_days = 9 where mps_id = ?", m));
        assertThat(PgErrors.constraint(head)).contains("ck_aps_mps_fixed");
        Throwable skip = catchThrowable(() -> accounts.exec(comp, "update aps_mps set status = 'SUPERSEDED', superseded_at = now() where mps_id = ?", m));
        assertThat(PgErrors.constraint(skip)).contains("ck_aps_mps_transition");
    }

    // ---------- 검증 ----------

    @Test
    void MPS_품목과_계산_입력을_검사한다() throws Exception {
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'RMB', '구매 자재', 'RM', 'BUY', 'EA', 't', 't')""", comp);
        item(admin, "P1", "RMB").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("APS_MPS_NOT_MAKE"));
        item(admin, "P2", "FG").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("APS_MPS_ITEM_DUP"));
        param(admin, "P1", "FG2", "0", "FX", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("lotQty"));
        param(admin, "P1", "FG2", "-1", "EX", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("safetyQty"));
        item(admin, "P1", "NOPE").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));

        mps(admin, tomorrow.minusDays(2), 7).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("PAST"));
        mps(admin, tomorrow, 6).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("horizonDays"));
        mvc.perform(json(post(M), "{\"plantCd\":\"P2\",\"horizonDays\":7}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("APS_MPS_NO_ITEM"));
        // 첫날 비우면 공장 오늘
        long m = id(mvc.perform(json(post(M), "{\"plantCd\":\"P1\"}").with(admin)));
        mvc.perform(get(M + "/" + m).with(admin)).andExpect(jsonPath("$.data.mps.horizonStart").value(tomorrow.minusDays(1).toString()))
                .andExpect(jsonPath("$.data.mps.horizonDays").value(28)).andExpect(jsonPath("$.data.mps.fenceDays").value(7));
    }

    // ---------- 권한·회사 ----------

    @Test
    void 확정은_APPROVE_가_있어야_하고_B사는_A사_MPS_를_보거나_고치거나_확정할_수_없다() throws Exception {
        long m = id(mps(admin, tomorrow, 7));
        long planner = accounts.newUser(comp, "planner", PW);
        accounts.grant(comp, planner, "PLAN", "aps-mps", "SEARCH", "SAVE");
        RequestPostProcessor pl = accounts.as(comp, planner, "planner");
        firm(pl, m, 0).andExpect(status().isForbidden());
        mvc.perform(get(M + "/" + m).with(pl)).andExpect(status().isOk());

        String compB = accounts.newComp("MPSB");
        long ub = accounts.newUser(compB, "admin1", PW);
        accounts.grant(compB, ub, "APS", "aps-mps", "SEARCH", "SAVE", "APPROVE");
        RequestPostProcessor b = accounts.as(compB, ub, "admin1");
        mvc.perform(get(M + "/" + m).with(b)).andExpect(status().isNotFound());
        firm(b, m, 0).andExpect(status().isNotFound());
        mvc.perform(json(put(M + "/" + m + "/cells"), "{\"rowVer\":0,\"cells\":[{\"cellId\":" + cell(m, tomorrow) + ",\"planQty\":\"1\"}]}")
                .with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(M + "/" + m + "/discard"), "{\"rowVer\":0,\"reason\":\"x\"}").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(M + "/items").with(b)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        long mi = accounts.query(comp, "select mps_item_id from aps_mps_item where item_id = ?", Long.class, fg);
        mvc.perform(json(put(M + "/items/" + mi), "{\"remark\":\"x\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        // B사 품목 코드로 A사 품목을 등록할 수 없다(없는 코드)
        accounts.addPlant(compB, "P1", "B 1공장");
        item(b, "P1", "FG").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        mvc.perform(get(M + "/" + m).with(admin)).andExpect(jsonPath("$.data.mps.status").value("DRAFT"));
    }

    // ---------- 계획 기준정보(8단계, docs/38 17장) ----------

    @Test
    void 계획_기준정보의_안전재고_최소_배수로_제안하고_행_사본에_배수를_남긴다() throws Exception {
        fence(0);
        // 안전재고 10, 최소 4, 배수 3 — 첫날 공급 5(지시 3 + 2): 부족 = 10 − 5 = 5 → 최소 4 보다 큼 → 5 → 배수 3 으로 올림 6 (손 검산)
        mvc.perform(json(post("/api/aps/item-params"), "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"safetyQty\":\"10\",\"lotRule\":\"MIN\","
                + "\"lotQty\":\"4\",\"lotMult\":\"3\"}").with(admin)).andExpect(status().isOk());
        mvc.perform(get(M + "/items").with(admin)).andExpect(jsonPath("$.data[0].safetyQty").value("10"))
                .andExpect(jsonPath("$.data[0].lotRule").value("MIN")).andExpect(jsonPath("$.data[0].lotMult").value("3"));
        long m = id(mps(admin, tomorrow, 7));
        assertThat(line(m, "propQty")).isEqualTo("6,0,0,0,0,0,0");
        assertThat(line(m, "pab")).isEqualTo("11,11,11,11,11,11,11");
        assertThat(accounts.query(comp, "select safety_qty::int || '/' || lot_rule || '/' || trim_scale(lot_mult) from aps_mps_row where mps_id = ?",
                String.class, m)).isEqualTo("10/MIN/3");
        // 사용 중지한 기준정보는 쓰지 않는다(0·EX)
        long pid = accounts.query(comp, "select param_id from aps_item_param where item_id = ?", Long.class, fg);
        mvc.perform(json(put("/api/aps/item-params/" + pid), "{\"safetyQty\":\"10\",\"lotRule\":\"MIN\",\"lotQty\":\"4\",\"lotMult\":\"3\","
                + "\"useYn\":\"N\",\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        long m2 = id(mps(admin, tomorrow, 7));
        assertThat(line(m2, "propQty")).isEqualTo("0,0,0,0,0,0,0");
        // MPS 품목 폼은 이제 안전재고·로트를 받지 않는다 — 보내도 기준정보를 바꾸지 않는다(조용히 덮어쓰기 없음)
        long mi = accounts.query(comp, "select mps_item_id from aps_mps_item where item_id = ?", Long.class, fg);
        mvc.perform(json(put(M + "/items/" + mi), "{\"safetyQty\":\"99\",\"lotRule\":\"EX\",\"useYn\":\"Y\",\"rowVer\":0}").with(admin))
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select trim_scale(safety_qty)::text from aps_item_param where param_id = ?", String.class, pid))
                .isEqualTo("10");
    }
}
