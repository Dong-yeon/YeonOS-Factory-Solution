package io.smarthappy.module.wms.ship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.wms.link.ShipConfirmCheck;
import io.smarthappy.module.wms.link.ShipConfirmCheck.Block;
import io.smarthappy.module.wms.link.ShipConfirmCheck.ShipContext;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 출하 확정 조건 확장 지점 ShipConfirmCheck (docs/72 2.1) — 시험용 빈 2개(@Order 100·200)를 켜고 끈다.
 * 빈이 아예 없는 경우(= 기존 동작)는 공용 문맥의 ShipTest·ShipPlmGateTest 등이 덮고, 여기서는 빈이 "통과"를 돌려줄 때 같은 결과인지 본다.
 * 막으면 409 WMS_SHIP_CHECK_BLOCKED(첫 건 = 순서가 앞선 빈, 전부 errors)이고 영업·원장·출하 상태는 그대로, 풀면 같은 출하가 확정된다.
 * 이상 사용: A사 출하를 B사 계정으로(404, 빈 안 불림), 빈은 부른 회사 범위에서 돈다.
 */
@AutoConfigureMockMvc
@Import(ShipConfirmCheckTest.Config.class)
class ShipConfirmCheckTest extends PostgresTestBase {

    private static final String PW = "Harbor-cedar-64";
    private static final String S = "/api/wms/ships";

    static volatile boolean blockFirst;
    static volatile boolean blockSecond;
    /** 불린 순서: [빈 이름, 문맥, 그때 회사 범위] */
    static final List<Object[]> calls = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Config {

        @Bean
        @Order(200)
        ShipConfirmCheck testShipCheckSecond(JdbcTemplate jdbc) {
            return check("ZT_SECOND", jdbc, () -> blockSecond, "zt.ship.second");
        }

        @Bean
        @Order(100)
        ShipConfirmCheck testShipCheckFirst(JdbcTemplate jdbc) {
            // 있는 문구 키 — WMS 가 사용자 언어로 바꾸는지 본다
            return check("ZT_FIRST", jdbc, () -> blockFirst, "valid.LOT_HOLD");
        }

        private static ShipConfirmCheck check(String key, JdbcTemplate jdbc, java.util.function.BooleanSupplier on, String messageKey) {
            return new ShipConfirmCheck() {
                @Override
                public String key() {
                    return key;
                }

                @Override
                public List<Block> check(ShipContext ctx) {
                    calls.add(new Object[] {key, ctx, jdbc.queryForObject("select hb_current_comp()", String.class)});
                    return on.getAsBoolean()
                            ? ctx.lots().stream().map(l -> new Block(key, l.lotId(), l.lotNo(), messageKey, List.of())).toList()
                            : List.of();
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    long a1;
    long stg;
    long fgLot;
    int seq;

    @BeforeEach
    void setUp() {
        reset();
        comp = accounts.newComp("SC");
        admin = user(comp);
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

    @AfterEach
    void reset() {
        blockFirst = false;
        blockSecond = false;
        calls.clear();
    }

    private RequestPostProcessor user(String c) {
        long u = accounts.newUser(c, "admin1", PW);
        accounts.grant(c, u, "SALES", "sal-order", "SEARCH", "SAVE");
        accounts.grant(c, u, "WMS", "wms-ship", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, u, "WMS", "wms-rcv", "SEARCH", "SAVE");
        return accounts.as(c, u, "admin1");
    }

    private long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd);
    }

    private ResultActions postJson(String url, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String req() {
        return "ship-check-" + (++seq) + "-" + System.nanoTime();
    }

    private int rowVer(long id) {
        return accounts.query(comp, "select row_ver from wms_ship where ship_id = ?", Integer.class, id);
    }

    /** LOT 입고 5 → 수주 5 → 지시 → 피킹. 반환 = [지시 id, 수주 행 id, LOT id] */
    private long[] picked() throws Exception {
        MvcResult r = postJson("/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + fgLot
                + ",\"qty\":5,\"stockStatus\":\"AVAILABLE\",\"supplierCd\":\"SUP1\",\"clientReqId\":\"" + req() + "\"}", admin)
                .andExpect(status().isOk()).andReturn();
        String lotNo = JsonPath.read(r.getResponse().getContentAsString(), "$.data.lotNo");
        long lot = accounts.query(comp, "select lot_id from lot_master where lot_no = ?", Long.class, lotNo);
        MvcResult o = postJson("/api/sales/orders", "{\"custCd\":\"C-001\",\"lines\":[{\"itemCd\":\"FG-L\",\"qty\":\"5\",\"dueDate\":\"2026-11-30\"}]}",
                admin).andExpect(status().isOk()).andReturn();
        long orderId = ((Number) JsonPath.read(o.getResponse().getContentAsString(), "$.data")).longValue();
        long ol = accounts.query(comp, "select line_id from sal_order_line where order_id = ?", Long.class, orderId);
        MvcResult sh = postJson(S, "{\"plantCd\":\"P1\",\"custCd\":\"C-001\",\"stageNodeId\":" + stg + ",\"planDate\":\"2026-11-20\",\"lines\":[{\"orderLineId\":"
                + ol + ",\"qty\":5}]}", admin).andExpect(status().isOk()).andReturn();
        long id = ((Number) JsonPath.read(sh.getResponse().getContentAsString(), "$.data.head.shipId")).longValue();
        long sl = accounts.query(comp, "select ship_line_id from wms_ship_line where ship_id = ?", Long.class, id);
        postJson(S + "/" + id + "/pick", "{\"rowVer\":" + rowVer(id) + ",\"clientReqId\":\"" + req() + "\",\"picks\":[{\"shipLineId\":" + sl + ",\"fromNodeId\":"
                + a1 + ",\"lotNo\":\"" + lotNo + "\",\"qty\":5}]}", admin).andExpect(status().isOk());
        return new long[] {id, ol, lot};
    }

    private ResultActions confirm(long id, RequestPostProcessor who) throws Exception {
        return postJson(S + "/" + id + "/confirm", "{\"rowVer\":" + rowVer(id) + ",\"clientReqId\":\"" + req() + "\"}", who);
    }

    private String shipped(long ol) {
        return accounts.query(comp, "select trim_scale(shipped_qty)::text from sal_order_line where line_id = ?", String.class, ol);
    }

    @Test
    void 빈이_통과를_돌려주면_그대로_확정되고_문맥은_피킹_LOT_과_수주_행() throws Exception {
        long[] x = picked();
        confirm(x[0], admin).andExpect(status().isOk());
        assertThat(shipped(x[1])).isEqualTo("5");
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0)[0]).as("@Order 순서").isEqualTo("ZT_FIRST");
        assertThat(calls.get(1)[0]).isEqualTo("ZT_SECOND");
        ShipContext c = (ShipContext) calls.get(0)[1];
        assertThat(c.shipId()).isEqualTo(x[0]);
        assertThat(c.plantCd()).isEqualTo("P1");
        assertThat(c.lots()).hasSize(1);
        assertThat(c.lots().get(0).lotId()).isEqualTo(x[2]);
        assertThat(c.lots().get(0).itemId()).isEqualTo(fgLot);
        assertThat(c.lots().get(0).orderLineId()).isEqualTo(x[1]);
        assertThat(calls.get(0)[2]).as("부른 회사 범위에서 돈다").isEqualTo(comp);
    }

    @Test
    void 막으면_409이고_영업_원장_상태_그대로_전부_errors_풀면_확정된다() throws Exception {
        long[] x = picked();
        String lotNo = accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, x[2]);
        blockFirst = true;
        blockSecond = true;
        confirm(x[0], admin).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_SHIP_CHECK_BLOCKED"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString(lotNo)))
                // 첫 건 = 순서가 앞선 빈의 사유(있는 문구 키는 사용자 언어 문구로 — 키 글자가 아니다)
                .andExpect(jsonPath("$.message").value(Matchers.not(Matchers.containsString("valid.LOT_HOLD"))))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].rowKey").value(lotNo))
                .andExpect(jsonPath("$.errors[0].code").value("SHIP_CHECK"))
                // 없는 문구 키는 키 그대로(구현 모듈이 문구를 빠뜨린 것이 보이게)
                .andExpect(jsonPath("$.errors[1].message").value(Matchers.containsString("zt.ship.second")));
        assertThat(shipped(x[1])).isEqualTo("0");
        assertThat(accounts.query(comp, "select count(*) from inv_trx where trx_type = 'SHIP'", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select status from wms_ship where ship_id = ?", String.class, x[0])).isEqualTo("PICKING");
        // 뒤 빈만 막아도 막힌다
        blockFirst = false;
        confirm(x[0], admin).andExpect(status().isConflict()).andExpect(jsonPath("$.errors.length()").value(1));
        assertThat(shipped(x[1])).isEqualTo("0");
        // 풀면 같은 출하가 확정된다
        blockSecond = false;
        confirm(x[0], admin).andExpect(status().isOk());
        assertThat(shipped(x[1])).isEqualTo("5");
    }

    @Test
    void A사_출하를_B사_계정으로_확정하면_404이고_빈은_불리지_않는다() throws Exception {
        long[] x = picked();
        String other = accounts.newComp("SD");
        RequestPostProcessor b = user(other);
        blockFirst = true;
        confirm(x[0], b).andExpect(status().isNotFound());
        assertThat(calls).isEmpty();
        assertThat(shipped(x[1])).isEqualTo("0");
        assertThat(accounts.query(comp, "select status from wms_ship where ship_id = ?", String.class, x[0])).isEqualTo("PICKING");
    }
}
