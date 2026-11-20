package io.smarthappy.module.wms.ship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.plm.link.EcoTrace;
import io.smarthappy.module.plm.link.EcoTrace.ShipCheck;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 출하 PLM 변경 게이트 (docs/30 12.5.2 ③, docs/39 17장) — PLM 계약 {@link EcoTrace#shipChecks} 를 흉내 낸다(PPAP 데이터 없이 판정만).
 * block + 정책 Y(기본) → 409 이고 영업·원장 그대로, 정책 N → 출하되고 경고, INTERIM(block 아님) → 출하되고 경고.
 */
@AutoConfigureMockMvc
class ShipPlmGateTest extends PostgresTestBase {

    private static final String PW = "Harbor-violet-27";
    private static final String S = "/api/wms/ships";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @MockitoBean
    EcoTrace eco;

    String comp;
    RequestPostProcessor admin;
    long a1;
    long stg;
    long fgLot;
    int seq;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("SP");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "SALES", "sal-order", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-ship", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, u, "WMS", "wms-rcv", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C-001', 'C-001 상사', 'SALES', 't', 't'), (?, 'SUP1', '공급사', 'PURCHASE', 't', 't')""", comp, comp);
        fgLot = accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'FG-L', 'FG LOT 품목', 'FG', 'MAKE', 'EA', 'Y', 't', 't') returning item_id""", Long.class, comp);
        long wh = node(null, "WH", "FG-WH");
        a1 = node(wh, "LOC", "A-01");
        stg = node(wh, "LOC", "STG-01");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't')", stg, comp);
    }

    private long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String req() {
        return "plm-ship-" + (++seq) + "-" + System.nanoTime();
    }

    private int rowVer(long id) {
        return accounts.query(comp, "select row_ver from wms_ship where ship_id = ?", Integer.class, id);
    }

    /** LOT 입고 5 → 수주 5 → 지시 → 피킹. 반환 = [지시 id, 수주 행 id, LOT id] */
    private long[] picked() throws Exception {
        MvcResult r = postJson("/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + fgLot
                + ",\"qty\":5,\"stockStatus\":\"AVAILABLE\",\"supplierCd\":\"SUP1\",\"clientReqId\":\"" + req() + "\"}").andExpect(status().isOk()).andReturn();
        String lotNo = JsonPath.read(r.getResponse().getContentAsString(), "$.data.lotNo");
        long lot = accounts.query(comp, "select lot_id from lot_master where lot_no = ?", Long.class, lotNo);
        MvcResult o = postJson("/api/sales/orders", "{\"custCd\":\"C-001\",\"lines\":[{\"itemCd\":\"FG-L\",\"qty\":\"5\",\"dueDate\":\"2026-11-30\"}]}")
                .andExpect(status().isOk()).andReturn();
        long orderId = ((Number) JsonPath.read(o.getResponse().getContentAsString(), "$.data")).longValue();
        long ol = accounts.query(comp, "select line_id from sal_order_line where order_id = ?", Long.class, orderId);
        MvcResult s = postJson(S, "{\"plantCd\":\"P1\",\"custCd\":\"C-001\",\"stageNodeId\":" + stg + ",\"planDate\":\"2026-11-20\",\"lines\":[{\"orderLineId\":"
                + ol + ",\"qty\":5}]}").andExpect(status().isOk()).andReturn();
        long id = ((Number) JsonPath.read(s.getResponse().getContentAsString(), "$.data.head.shipId")).longValue();
        long sl = accounts.query(comp, "select ship_line_id from wms_ship_line where ship_id = ?", Long.class, id);
        postJson(S + "/" + id + "/pick", "{\"rowVer\":" + rowVer(id) + ",\"clientReqId\":\"" + req() + "\",\"picks\":[{\"shipLineId\":" + sl + ",\"fromNodeId\":"
                + a1 + ",\"lotNo\":\"" + lotNo + "\",\"qty\":5}]}").andExpect(status().isOk());
        return new long[] {id, ol, lot};
    }

    private ResultActions confirm(long id) throws Exception {
        return postJson(S + "/" + id + "/confirm", "{\"rowVer\":" + rowVer(id) + ",\"clientReqId\":\"" + req() + "\"}");
    }

    private String shipped(long ol) {
        return accounts.query(comp, "select trim_scale(shipped_qty)::text from sal_order_line where line_id = ?", String.class, ol);
    }

    @Test
    void PPAP_미승인_변경_LOT_은_정책_Y_면_409이고_영업_원장_그대로() throws Exception {
        long[] x = picked();
        when(eco.shipChecks(any())).thenReturn(List.of(new ShipCheck(x[2], 77, "ECO-77", "PPAP_OPEN", "SUBMITTED", true)));
        confirm(x[0]).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_SHIP_CHANGE_BLOCKED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ECO-77")));
        assertThat(shipped(x[1])).isEqualTo("0");
        assertThat(accounts.query(comp, "select count(*) from inv_trx where trx_type = 'SHIP'", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select status from wms_ship where ship_id = ?", String.class, x[0])).isEqualTo("PICKING");
    }

    @Test
    void 정책_N_이면_경고만_INTERIM_은_항상_경고만() throws Exception {
        long[] x = picked();
        when(eco.shipChecks(any())).thenReturn(List.of(new ShipCheck(x[2], 78, "ECO-78", "PPAP_INTERIM", "INTERIM", false)));
        confirm(x[0]).andExpect(status().isOk()).andExpect(jsonPath("$.data.warnings[0]").value(org.hamcrest.Matchers.containsString("ECO-78")));
        assertThat(shipped(x[1])).isEqualTo("5");
        long[] y = picked();
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) "
                + "values (?, 'SHIP_BLOCK_UNAPPROVED_CHANGE', 'N', 't', 't')", comp);
        when(eco.shipChecks(any())).thenReturn(List.of(new ShipCheck(y[2], 79, "ECO-79", "PPAP_OPEN", "REJECTED", true)));
        confirm(y[0]).andExpect(status().isOk()).andExpect(jsonPath("$.data.warnings[0]").value(org.hamcrest.Matchers.containsString("ECO-79")));
        assertThat(shipped(y[1])).isEqualTo("5");
    }
}
