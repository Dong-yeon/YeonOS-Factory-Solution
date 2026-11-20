package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * MRP (docs/38 19장) — 확정 MPS → 3단계 전개(FG → SFG → RM)·로트·리드타임·안전재고 → 계획 주문, 페깅이 수주 행까지, 결과 불변, 권한, A사로 B사.
 *
 * 손 검산(MRP 첫날 = 오늘 T0, MPS 첫날 = 내일 T1):
 * - MPS: FG 수주 10 @T4, 열린 지시 5(@T1) → 계획 5 @T4 (EX) → 확정
 * - FG(MPS, 자체 1일): 계획 주문 MPS 5 완료 T4·시작 T3 → SFG 10 @T3. 수주 10 ← 지시 3 + 2 + MPS 5
 * - SFG(자체 2일, FX 50, 재고 4): 10 − 4 = 6 → 50, 완료 T3·시작 T1 → RM 150 @T1
 * - RM(구매, 공급원 3일·MOQ 100·배수 50, 안전 20, 재고 30): 안전 20 @T0 + 150 @T1 − 30 = 140 → 150, 완료 T1·시작 T-2 → 늦음
 */
class MrpTest extends ApsTestBase {

    static final String R = "/api/aps/mrp";

    long fg;
    long sfg;
    long rm;
    long loc;
    long mpsId;
    LocalDate today;

    @BeforeEach
    void mrpSetUp() throws Exception {
        accounts.grant(comp, userId, "APS", "aps-mrp", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "APS", "aps-mps", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, userId, "APS", "aps-item-param", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "APS", "wms-move", "SEARCH", "SAVE");
        today = tomorrow.minusDays(1);
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'SFG', '반제품', 'SFG', 'MAKE', 'EA', 'N', 't', 't'), (?, 'RM', '원자재', 'RM', 'BUY', 'EA', 'N', 't', 't')""", comp, comp);
        fg = item("FG");
        sfg = item("SFG");
        rm = item("RM");
        bom(fg, sfg, "2");
        bom(sfg, rm, "3");
        param("FG", "{\"safetyQty\":\"0\",\"lotRule\":\"EX\",\"makeLeadDays\":1}");
        param("SFG", "{\"safetyQty\":\"0\",\"lotRule\":\"FX\",\"lotQty\":\"50\",\"makeLeadDays\":2}");
        param("RM", "{\"safetyQty\":\"20\",\"lotRule\":\"EX\"}");
        long v = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'V100', 'ZT 공급사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, comp);
        accounts.exec(comp, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", v, comp);
        accounts.exec(comp, """
                insert into srm_source (comp_cd, cust_id, item_id, lead_days, moq, order_mult, priority, created_by, updated_by)
                values (?, ?, ?, 3, 100, 50, 1, 't', 't')""", comp, v, rm);
        long wh = node(null, "WH", "WH1");
        loc = node(wh, "LOC", "A-01");
        adjust(sfg, "4");
        adjust(rm, "30");
        // MPS: FG 수주 10 @T4 → 계획 5 @T4 → 확정
        long c1 = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C1', 'C1 고객', 'SALES', 't', 't') returning cust_id""", Long.class, comp);
        long o = accounts.query(comp, """
                insert into sal_order (comp_cd, order_no, cust_id, order_date, created_by, updated_by)
                values (?, 'SO-ZT1', ?, (now() at time zone 'Asia/Seoul')::date, 't', 't') returning order_id""", Long.class, comp, c1);
        accounts.exec(comp, """
                insert into sal_order_line (comp_cd, order_id, line_no, item_id, qty, due_date, created_by, updated_by)
                values (?, ?, 1, ?, 10, ?, 't', 't')""", comp, o, fg, java.sql.Date.valueOf(tomorrow.plusDays(3)));
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'APS_MPS_FENCE_DAYS', '0', 't', 't')", comp);
        mvc.perform(json(post("/api/aps/mps/items"), "{\"plantCd\":\"P1\",\"itemCd\":\"FG\"}").with(admin)).andExpect(status().isOk());
        mpsId = id(mvc.perform(json(post("/api/aps/mps"), "{\"plantCd\":\"P1\",\"horizonStart\":\"" + tomorrow + "\",\"horizonDays\":7}")
                .with(admin)));
        mvc.perform(json(post("/api/aps/mps/" + mpsId + "/firm"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select string_agg(trim_scale(c.plan_qty)::text, ',' order by c.bucket_date) from aps_mps_cell c where c.mps_id = ?",
                String.class, mpsId)).isEqualTo("0,0,0,5,0,0,0");
    }

    // ---------- 도우미 ----------

    long item(String cd) {
        return accounts.query(comp, "select item_id from mst_item where item_cd = ?", Long.class, cd);
    }

    void bom(long parent, long child, String qty) {
        long bom = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, base_qty, created_by, updated_by)
                values (?, ?, 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 1, 't', 't') returning bom_id""", Long.class, comp, parent);
        accounts.exec(comp, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, op_seq, issue_method, created_by, updated_by)
                values (?, ?, 10, ?, ?::numeric, 'EA', 'NORMAL', 10, 'MANUAL', 't', 't')""", comp, bom, child, qty);
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", bom);
    }

    void param(String itemCd, String body) throws Exception {
        mvc.perform(json(post("/api/aps/item-params"), "{\"plantCd\":\"P1\",\"itemCd\":\"" + itemCd + "\"," + body.substring(1)).with(admin))
                .andExpect(status().isOk());
    }

    void adjust(long itemId, String delta) throws Exception {
        mvc.perform(json(post("/api/wms/move/adjust"), "{\"plantCd\":\"P1\",\"nodeId\":" + loc + ",\"itemId\":" + itemId
                + ",\"stockStatus\":\"AVAILABLE\",\"delta\":" + delta + ",\"reasonCd\":\"FOUND\"}").with(admin)).andExpect(status().isOk());
    }

    ResultActions mrp(RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(json(post(R), body).with(who));
    }

    long calc() throws Exception {
        return id(mrp(admin, "{\"plantCd\":\"P1\",\"horizonDays\":28,\"mrpNm\":\"ZT MRP\"}"));
    }

    String body(String url) throws Exception {
        return mvc.perform(get(url).with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    /** 요소 요약 — 품목:종류:날짜:수량(:시작) */
    String elems(long id) {
        return accounts.query(comp, """
                select string_agg(i.item_cd || ':' || e.kind || ':' || e.elem_date || ':' || trim_scale(e.qty) || coalesce(':' || e.start_date, ''),
                                  ',' order by e.elem_seq)
                  from aps_mrp_elem e join mst_item i on i.item_id = e.item_id where e.mrp_id = ?""", String.class, id);
    }

    // ---------- 계산 ----------

    @Test
    void 확정_MPS_를_3단계로_전개해_계획_주문을_만들고_지시_구매요청은_만들지_않는다() throws Exception {
        String before = woState();
        long id = calc();
        assertThat(woState()).isEqualTo(before);
        assertThat(accounts.query(comp, "select count(*) from srm_pr", Long.class)).isZero();

        String d = body(R + "/" + id);
        assertThat((Integer) JsonPath.read(d, "$.data.mrp.mpsId")).isEqualTo((int) mpsId);
        assertThat((Integer) JsonPath.read(d, "$.data.mrp.orderCnt")).isEqualTo(3);
        assertThat(JsonPath.<List<String>>read(d, "$.data.items[*].itemCd")).containsExactly("FG", "SFG", "RM");
        assertThat(JsonPath.<List<Integer>>read(d, "$.data.items[*].llc")).containsExactly(0, 1, 2);
        assertThat(JsonPath.<List<String>>read(d, "$.data.items[?(@.itemCd == 'RM')].leadSrc")).containsExactly("SOURCE");
        assertThat(JsonPath.<List<String>>read(d, "$.data.items[?(@.itemCd == 'RM')].custCd")).containsExactly("V100");

        String o = body(R + "/" + id + "/orders");
        assertThat(JsonPath.<List<String>>read(o, "$.data.list[*].itemCd")).containsExactly("FG", "RM", "SFG");
        assertThat(JsonPath.<List<String>>read(o, "$.data.list[*].kind")).containsExactly("MPS", "PLAN", "PLAN");
        assertThat(JsonPath.<List<String>>read(o, "$.data.list[*].qty")).containsExactly("5", "150", "50");
        assertThat(JsonPath.<List<String>>read(o, "$.data.list[*].elemDate"))
                .containsExactly(tomorrow.plusDays(3).toString(), tomorrow.toString(), tomorrow.plusDays(2).toString());
        assertThat(JsonPath.<List<String>>read(o, "$.data.list[*].startDate"))
                .containsExactly(tomorrow.plusDays(2).toString(), tomorrow.minusDays(3).toString(), tomorrow.toString());
        assertThat(JsonPath.<List<String>>read(o, "$.data.list[*].lateYn")).containsExactly("N", "Y", "N");
        assertThat(JsonPath.<List<String>>read(body(R + "/" + id + "/orders?kind=PLAN"), "$.data.list[*].itemCd")).containsExactly("RM", "SFG");
        mvc.perform(get(R + "/" + id + "/orders?kind=WO").with(admin)).andExpect(status().isBadRequest());

        // 예외: RM 늦음(시작 T-2 < 오늘, 제안일 오늘)
        assertThat(JsonPath.<List<String>>read(d, "$.data.excs[*].excCd")).containsExactly("START_PAST");
        assertThat(JsonPath.<String>read(d, "$.data.excs[0].sugDate")).isEqualTo(today.toString());

        // 재고/소요 목록 RM: 재고 30 → 안전 20 → 계획 150 → 종속 150, 예상 재고 30·10·160·10
        String rmList = body(R + "/" + id + "/items/" + rm);
        assertThat(JsonPath.<List<String>>read(rmList, "$.data.lines[*].elem.kind")).containsExactly("STOCK", "SAFE", "PLAN", "DEP");
        assertThat(JsonPath.<List<String>>read(rmList, "$.data.lines[*].pab")).containsExactly("30", "10", "160", "10");
        assertThat(JsonPath.<List<String>>read(rmList, "$.data.lines[3].pegs[*].qty")).containsExactly("10", "140");

        // 같은 입력 → 같은 결과
        long id2 = calc();
        assertThat(elems(id2)).isEqualTo(elems(id));
    }

    @Test
    void 페깅은_원자재_계획_주문에서_위로_수주_행까지_닿는다() throws Exception {
        long id = calc();
        int rmPlan = accounts.query(comp, "select elem_seq from aps_mrp_elem where mrp_id = ? and item_id = ? and kind = 'PLAN'", Integer.class, id, rm);
        String chain = body(R + "/" + id + "/elems/" + rmPlan + "/peg");
        assertThat(JsonPath.<List<String>>read(chain, "$.data.nodes[*].elem.kind")).containsExactly("PLAN", "DEP", "PLAN", "DEP", "MPS", "SO");
        assertThat(JsonPath.<List<String>>read(chain, "$.data.nodes[*].elem.itemCd")).containsExactly("RM", "RM", "SFG", "SFG", "FG", "FG");
        assertThat(JsonPath.<String>read(chain, "$.data.nodes[5].elem.refNo")).isEqualTo("SO-ZT1-1");
        assertThat(JsonPath.<String>read(chain, "$.data.nodes[5].qty")).isEqualTo("5");
        assertThat((Boolean) JsonPath.read(chain, "$.data.more")).isFalse();
        // FG 수주 10 ← 지시 3 + 2 + MPS 5 (MPS 품목은 페깅만)
        String fgList = body(R + "/" + id + "/items/" + fg);
        assertThat(JsonPath.<List<String>>read(fgList, "$.data.lines[?(@.elem.kind == 'SO')].pegs[*].qty")).containsExactly("3", "2", "5");
        mvc.perform(get(R + "/" + id + "/elems/99999/peg").with(admin)).andExpect(status().isNotFound());
        mvc.perform(get(R + "/" + id + "/items/99999999").with(admin)).andExpect(status().isNotFound());
    }

    @Test
    void 결과는_계산_밖에서_넣거나_고치거나_지울_수_없고_폐기만_사유와_함께() throws Exception {
        long id = calc();
        assertThatThrownBy(() -> accounts.exec(comp, "update aps_mrp_elem set qty = 1 where mrp_id = ?", id))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> accounts.exec(comp, "delete from aps_mrp_peg where mrp_id = ?", id))
                .rootCause().hasMessageContaining("permission denied");
        Throwable late = catchThrowable(() -> accounts.exec(comp, """
                insert into aps_mrp_exc (comp_cd, mrp_id, item_id, exc_cd) values (?, ?, ?, 'CANCEL')""", comp, id, rm));
        assertThat(PgErrors.constraint(late)).contains("ck_aps_mrp_result_calc");
        Throwable fixed = catchThrowable(() -> accounts.exec(comp, "update aps_mrp set horizon_days = 30 where mrp_id = ?", id));
        assertThat(PgErrors.constraint(fixed)).contains("ck_aps_mrp_fixed");

        mvc.perform(json(post(R + "/" + id + "/discard"), "{\"rowVer\":0,\"reason\":\" \"}").with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        mvc.perform(json(post(R + "/" + id + "/discard"), "{\"rowVer\":5,\"reason\":\"다시\"}").with(admin)).andExpect(status().isConflict());
        mvc.perform(json(post(R + "/" + id + "/discard"), "{\"rowVer\":0,\"reason\":\"ZT 폐기\"}").with(admin)).andExpect(status().isOk());
        mvc.perform(json(post(R + "/" + id + "/discard"), "{\"rowVer\":1,\"reason\":\"ZT 폐기\"}").with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("STATE"));
        Throwable back = catchThrowable(() -> accounts.exec(comp, "update aps_mrp set status = 'CALC', discard_reason = null where mrp_id = ?", id));
        assertThat(PgErrors.constraint(back)).contains("ck_aps_mrp_transition");
        assertThat(JsonPath.<List<String>>read(body(R + "?plantCd=P1"), "$.data.list[*].status")).containsExactly("DISCARDED");
    }

    @Test
    void 입력_검사() throws Exception {
        mrp(admin, "{\"horizonDays\":28}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("plantCd"));
        mrp(admin, "{\"plantCd\":\"P1\",\"horizonDays\":6}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("horizonDays"));
        mrp(admin, "{\"plantCd\":\"P1\",\"horizonDays\":93}").andExpect(status().isBadRequest());
        mrp(admin, "{\"plantCd\":\"NOPE\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        mrp(admin, "{\"plantCd\":\"" + "P".repeat(30) + "\"}").andExpect(status().isBadRequest());
        mvc.perform(get(R + "?from=2026-13-01").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(R + "?from=2026-01-01&to=2025-01-01").with(admin)).andExpect(status().isBadRequest());
        // 공장 P2: 확정 MPS·지시·기준정보가 없다 → 빈 실행(MPS 없음)
        long empty = id(mrp(admin, "{\"plantCd\":\"P2\"}"));
        String d = body(R + "/" + empty);
        mvc.perform(get(R + "/" + empty).with(admin)).andExpect(jsonPath("$.data.mrp.mpsId").doesNotExist());
        assertThat(JsonPath.<List<Object>>read(d, "$.data.items")).isEmpty();
        assertThat((Integer) JsonPath.read(d, "$.data.mrp.horizonDays")).isEqualTo(28);
    }

    @Test
    void 화면_권한이_없으면_403_B사는_A사_실행을_보거나_폐기할_수_없다() throws Exception {
        long id = calc();
        long other = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, other, "VIEW", "aps-plan", "SEARCH");
        RequestPostProcessor viewer = accounts.as(comp, other, "viewer1");
        mvc.perform(get(R).with(viewer)).andExpect(status().isForbidden());
        mvc.perform(json(post(R), "{\"plantCd\":\"P1\"}").with(viewer)).andExpect(status().isForbidden());
        accounts.grant(comp, other, "VIEW", "aps-mrp", "SEARCH");
        mvc.perform(get(R + "/" + id).with(viewer)).andExpect(status().isOk());
        mvc.perform(json(post(R + "/" + id + "/discard"), "{\"rowVer\":0,\"reason\":\"x\"}").with(viewer)).andExpect(status().isForbidden());

        String compB = accounts.newComp("APSB");
        long ub = accounts.newUser(compB, "adminb", PW);
        accounts.grant(compB, ub, "APS", "aps-mrp", "SEARCH", "SAVE");
        accounts.addPlant(compB, "P1", "B 1공장");
        RequestPostProcessor b = accounts.as(compB, ub, "adminb");
        mvc.perform(get(R + "/" + id).with(b)).andExpect(status().isNotFound());
        mvc.perform(get(R + "/" + id + "/orders").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(R + "/" + id + "/items/" + rm).with(b)).andExpect(status().isNotFound());
        mvc.perform(get(R + "/" + id + "/elems/1/peg").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(R + "/" + id + "/discard"), "{\"rowVer\":0,\"reason\":\"x\"}").with(b)).andExpect(status().isNotFound());
        assertThat(JsonPath.<List<Object>>read(mvc.perform(get(R).with(b)).andReturn().getResponse().getContentAsString(), "$.data.list")).isEmpty();
        // B사 같은 공장 코드로 계산해도 A사 MPS·지시·재고·BOM 은 안 보인다
        long bRun = id(mvc.perform(json(post(R), "{\"plantCd\":\"P1\"}").with(b)));
        assertThat(accounts.query(compB, "select count(*) from aps_mrp_elem where mrp_id = ?", Long.class, bRun)).isZero();
        assertThat(accounts.query(compB, "select mps_id is null from aps_mrp where mrp_id = ?", Boolean.class, bRun)).isTrue();
        assertThat(accounts.query(comp, "select status from aps_mrp where mrp_id = ?", String.class, id)).isEqualTo("CALC");
    }
}
