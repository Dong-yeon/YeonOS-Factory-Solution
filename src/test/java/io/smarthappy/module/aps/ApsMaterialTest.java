package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;

/**
 * APS 5단계 ② 자재 가용 (docs/38 14.3, A27~A34) — MES 남은 소요(발행 스냅샷·계획 전개)·WMS 가용 재고로
 * WARN(부족 표시, 시각 그대로)·DELAY(기간 안 안 덮이면 MAT), 결과 사본 불변, 보존 정리가 자재 사본도 지움, 입력 검증, A사로 B사,
 * 입고 예정 기본 켬 + 발주로 받은 수량은 재고로만 한 번(A30 재판정 2026-10-22).
 */
class ApsMaterialTest extends ApsTestBase {

    long rm;
    long wo3;
    long wo4;
    long loc;

    @BeforeEach
    void material() throws Exception {
        // FG3 = 절단(L1, 1시간/개) 한 공정, BOM: RM 2 EA / 개(절단 투입). RM 은 LOT 관리 안 함 — 투입 기록이 없어 전량 남은 소요(A31)
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'FG3', 'FG3 품목', 'FG', 'MAKE', 'EA', 'N', 't', 't'), (?, 'RM', '원자재', 'RM', 'BUY', 'EA', 'N', 't', 't')""", comp, comp);
        rm = accounts.query(comp, "select item_id from mst_item where item_cd = 'RM'", Long.class);
        long line1 = accounts.query(comp, "select node_id from mst_equip_hier where node_cd = 'L1'", Long.class);
        routing("FG3", line1, false);
        long bom = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, base_qty, created_by, updated_by)
                select ?, item_id, 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 1, 't', 't' from mst_item where item_cd = 'FG3' returning bom_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, op_seq, issue_method, created_by, updated_by)
                values (?, ?, 10, ?, 2, 'EA', 'NORMAL', 10, 'MANUAL', 't', 't')""", comp, bom, rm);
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", bom);
        // 지시3(우선 1, 5개 → RM 10, 발행 = 스냅샷), 지시4(우선 2, 3개 → RM 6, 계획 = 전개)
        wo3 = wo("FG3", "5", 1);
        mvc.perform(json(post("/api/production/wos/" + wo3 + "/release"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        wo4 = wo("FG3", "3", 2);
        // 창고·로케이션, 가용 12 (상태별 제외는 WMS StockQuery 시험이 맡는다 — 조정 API 는 AVAILABLE 만 받는다)
        accounts.grant(comp, userId, "APS", "wms-move", "SEARCH", "SAVE");
        long wh = node(null, "WH", "WH1");
        loc = node(wh, "LOC", "A-01");
        adjust("AVAILABLE", "12");
    }

    private void adjust(String st, String delta) throws Exception {
        mvc.perform(json(post("/api/wms/move/adjust"), "{\"plantCd\":\"P1\",\"nodeId\":" + loc + ",\"itemId\":" + rm + ",\"stockStatus\":\""
                + st + "\",\"delta\":" + delta + ",\"reasonCd\":\"FOUND\"}").with(admin)).andExpect(status().isOk());
    }

    private ResultActions calcMat(RequestPostProcessor who, String mode) throws Exception {
        return calcMat(who, mode, null);
    }

    private ResultActions calcMat(RequestPostProcessor who, String mode, Boolean matPo) throws Exception {
        return mvc.perform(json(post(A), "{\"plantCd\":\"P1\",\"horizonStart\":\"" + tomorrow + "\",\"horizonDays\":3,\"planNm\":\"ZT 자재\""
                + (mode == null ? "" : ",\"matMode\":\"" + mode + "\"") + (matPo == null ? "" : ",\"matPo\":" + matPo) + "}").with(who));
    }

    private String supplies(long plan) {
        return accounts.query(comp, "select string_agg(kind || ':' || trim_scale(qty), ',' order by kind) from aps_plan_supply where plan_id = ?",
                String.class, plan);
    }

    private void poAct(long po, String action) throws Exception {
        int rv = JsonPath.read(mvc.perform(get("/api/srm/pos/" + po).with(admin)).andReturn().getResponse().getContentAsString(), "$.data.po.rowVer");
        mvc.perform(json(post("/api/srm/pos/" + po + "/" + action), "{\"rowVer\":" + rv + "}").with(admin)).andExpect(status().isOk());
    }

    private String mat(long plan, long woId) {
        return accounts.query(comp, """
                select trim_scale(need_qty) || '/' || trim_scale(alloc_qty) || '/' || trim_scale(short_qty)
                  from aps_plan_mat where plan_id = ? and wo_id = ? and item_id = ?""", String.class, plan, woId, rm);
    }

    @Test
    void WARN_은_앞_지시가_먼저_가지고_뒤_지시는_부족_표시만_시각은_그대로() throws Exception {
        long plan = id(calcMat(admin, null));
        assertThat(mat(plan, wo3)).isEqualTo("10/10/0");
        assertThat(mat(plan, wo4)).isEqualTo("6/2/4");
        mvc.perform(get(A + "/" + plan).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.matMode").value("WARN"))
                .andExpect(jsonPath("$.data.plan.matPo").value("Y"))  // 빈 값 = 켬(A30 재판정)
                .andExpect(jsonPath("$.data.plan.matShortWoCnt").value(1))
                .andExpect(jsonPath("$.data.mats[0].woId").value((int) wo4))  // 부족 먼저
                .andExpect(jsonPath("$.data.mats[0].shortQty").value("4"))
                .andExpect(jsonPath("$.data.mats[0].supplyQty").value("12"))
                .andExpect(jsonPath("$.data.mats[0].coveredAt").doesNotExist())
                .andExpect(jsonPath("$.data.wos[?(@.woId == " + wo4 + ")].matCd").value("SHORT"))
                .andExpect(jsonPath("$.data.wos[?(@.woId == " + wo4 + ")].startAt").isNotEmpty());
        mvc.perform(get(A + "/" + plan + "/gantt").with(admin))
                .andExpect(jsonPath("$.data.ops[?(@.woId == " + wo4 + ")].matShortYn").value("Y"))
                .andExpect(jsonPath("$.data.ops[?(@.woId == " + wo3 + ")].matShortYn").value("N"));
        // 계산에 쓴 공급 사본: 재고 한 줄(가용만)
        assertThat(accounts.query(comp, "select kind || ':' || trim_scale(qty) from aps_plan_supply where plan_id = ?", String.class, plan))
                .isEqualTo("STOCK:12");
    }

    @Test
    void DELAY_는_기간_안에_안_덮이면_MAT_으로_계획하지_않는다() throws Exception {
        long plan = id(calcMat(admin, "DELAY"));
        assertThat(accounts.query(comp, "select exc_cd from aps_plan_wo where plan_id = ? and wo_id = ?", String.class, plan, wo4)).isEqualTo("MAT");
        assertThat(accounts.query(comp, "select exc_cd from aps_plan_op where plan_id = ? and wo_id = ?", String.class, plan, wo4)).isEqualTo("MAT");
        assertThat(mat(plan, wo4)).as("계획 못 한 공정 — 판정 없음").isEqualTo("6/0/0");
        assertThat(mat(plan, wo3)).isEqualTo("10/10/0");
        mvc.perform(get(A + "/" + plan).with(admin)).andExpect(jsonPath("$.data.plan.matMode").value("DELAY"))
                .andExpect(jsonPath("$.data.unscheduled[?(@.woId == " + wo4 + ")].excCd").value("MAT"));
        // 재고를 채우면 계획된다
        adjust("AVAILABLE", "10");
        long plan2 = id(calcMat(admin, "DELAY"));
        assertThat(accounts.query(comp, "select coalesce(exc_cd, '-') from aps_plan_wo where plan_id = ? and wo_id = ?", String.class, plan2, wo4))
                .isEqualTo("-");
        assertThat(mat(plan2, wo4)).isEqualTo("6/6/0");
    }

    @Test
    void 입고_예정은_기본으로_세고_발주로_받은_수량은_재고로만_한_번_센다() throws Exception {
        // 공급사 V100, 발주 RM 10 EA(납기 = 계획 둘째 날) 발행 → 그중 4 를 발주 행으로 입고(WMS RCV_PO → SRM recv_qty 4)
        accounts.grant(comp, userId, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, userId, "WMS", "wms-rcv", "SEARCH", "SAVE");
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", comp);
        long cust = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'V100', 'ZT 공급사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, comp);
        accounts.exec(comp, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", cust, comp);
        long po = id(mvc.perform(json(post("/api/srm/pos"), "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"RM\",\"qty\":\"10\","
                + "\"unitPrice\":\"1\",\"dueDate\":\"" + tomorrow.plusDays(1) + "\"}]}").with(admin)));
        poAct(po, "confirm");
        poAct(po, "issue");
        long poLine = accounts.query(comp, "select po_line_id from srm_po_line where po_id = ?", Long.class, po);
        mvc.perform(json(post("/api/wms/rcv"), "{\"plantCd\":\"P1\",\"nodeId\":" + loc + ",\"poLineId\":" + poLine
                + ",\"qty\":4,\"stockStatus\":\"AVAILABLE\",\"clientReqId\":\"zt-aps-rcv-1\"}").with(admin)).andExpect(status().isOk());

        // 기본(빈 값) = 켬: 재고 16(12 + 받은 4) + 발주 열린 6 — 받은 4 를 발주로 또 세지 않는다
        long plan = id(calcMat(admin, null));
        assertThat(supplies(plan)).isEqualTo("PO:6,STOCK:16");
        mvc.perform(get(A + "/" + plan).with(admin)).andExpect(jsonPath("$.data.plan.matPo").value("Y"));
        // 끄면 재고만
        long off = id(calcMat(admin, null, false));
        assertThat(supplies(off)).isEqualTo("STOCK:16");
        mvc.perform(get(A + "/" + off).with(admin)).andExpect(jsonPath("$.data.plan.matPo").value("N"));
    }

    @Test
    void 입력_검증과_결과_사본_불변_그리고_보존_정리() throws Exception {
        calcMat(admin, "LATER").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("matMode"));
        long plan = id(calcMat(admin, null));
        assertThatThrownBy(() -> accounts.exec(comp, "update aps_plan_mat set short_qty = 0 where plan_id = ?", plan))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> accounts.exec(comp, "delete from aps_plan_supply where plan_id = ?", plan))
                .rootCause().hasMessageContaining("permission denied");
        Throwable fixed = catchThrowable(() -> accounts.exec(comp, "update aps_plan set mat_mode = 'DELAY' where plan_id = ?", plan));
        assertThat(PgErrors.constraint(fixed)).contains("ck_aps_plan_fixed");
        // 보존 정리(소유자)가 자재 사본도 지운다
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = c.createStatement()) {
            st.executeQuery("select * from hb_purge_aps_results(now() + interval '100 days')").close();
        }
        assertThat(accounts.query(comp, "select count(*) from aps_plan_mat where plan_id = ?", Long.class, plan)).isZero();
        assertThat(accounts.query(comp, "select count(*) from aps_plan_supply where plan_id = ?", Long.class, plan)).isZero();
        assertThat(accounts.query(comp, "select purged_at is not null from aps_plan where plan_id = ?", Boolean.class, plan)).isTrue();
    }

    @Test
    void B사는_A사_자재_결과를_보지_못하고_A사_품목으로_사본을_못_넣으며_A사_재고를_세지_않는다() throws Exception {
        long plan = id(calcMat(admin, null));
        String compB = accounts.newComp("APSB");
        long ub = accounts.newUser(compB, "admin1", PW);
        accounts.grant(compB, ub, "APS", "aps-plan", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(compB, ub, "admin1");
        mvc.perform(get(A + "/" + plan).with(b)).andExpect(status().isNotFound());
        assertThat(accounts.query(compB, "select count(*) from aps_plan_mat", Long.class)).isZero();
        assertThat(accounts.query(compB, "select count(*) from aps_plan_supply", Long.class)).isZero();
        // B사 범위에서 A사 계획·품목으로 공급 사본 INSERT → 같은 회사 트리거(계산 트랜잭션 표식보다 회사 확인이 먼저 걸리지 않아도 막힘)
        Throwable cross = catchThrowable(() -> accounts.exec(compB, """
                insert into aps_plan_supply (comp_cd, plan_id, item_id, kind, supply_at, qty, unit_cd)
                values (?, ?, ?, 'STOCK', now(), 1, 'EA')""", compB, plan, rm));
        assertThat(PgErrors.constraint(cross)).isPresent();
        // B사가 같은 품목 id 로 재고를 물어도 0 — B사 계산에 A사 재고가 섞이지 않는다(StockQuery RLS)
        accounts.addPlant(compB, "P1", "B 1공장");
        accounts.exec(compB, """
                insert into mst_shift (comp_cd, plant_cd, shift_cd, shift_nm, start_time, end_time, created_by, updated_by)
                values (?, 'P1', 'D', '주간', '08:00', '17:00', 't', 't')""", compB);
        long planB = id(calcMat(b, null));
        assertThat(accounts.query(compB, "select count(*) from aps_plan_supply where plan_id = ?", Long.class, planB)).isZero();
    }
}
