package io.smarthappy.module.wms.pda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.master.workforce.WorkerService;
import io.smarthappy.module.pop.PopAuthService;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * 창고 PDA 반품 출고 API (B7-3, docs/30 12.12.12) — 사무 화면과 같은 ReturnIssueService(SRM 게이트·RTN_SUP·멱등)를 단말 + 배지 작업자로.
 * 기록: 원장 performed_by = 작업자, recorded_by·SRM updated_by = 단말 행위자(t{id}:{cd}). 온라인만.
 * 거부: 작업자 없음 428, 수량 다름 409, POP 유형·사무 계정 403, 다른 공장 반품·A사로 B사 = 없는 것.
 */
@AutoConfigureMockMvc
class PdaReturnTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String WH = "/api/pop/wh";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    String ip;
    RequestPostProcessor admin;
    long a1;
    long stg;
    long fg;
    long fgLot;
    Cookie pda;
    MockHttpSession s;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PT");
        ip = "10.86." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, u, "ADM", "sal-order", "SEARCH", "SAVE");
        accounts.grant(comp, u, "ADM", "wms-ship", "SEARCH", "SAVE", "APPROVE");
        admin = accounts.as(comp, u, "admin1");
        seed(comp, "P1");
        a1 = node(comp, "P1", "A-01");
        stg = node(comp, "P1", "STG-01");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't')",
                stg, comp);
        fg = itemId(comp, "FG-100");
        fgLot = itemId(comp, "FG-L");
        adjIn(comp, "P1", fg, a1, "30");
        accounts.exec(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'W-001', '작업자', 'P1', ?, 't', 't')""", comp, WorkerService.badgeHash(comp, "BADGE-0001"));
        pda = terminal("PDA-01", "PDA");
        s = new MockHttpSession();
    }

    // ---------- 도우미 ----------

    void seed(String c, String plant) {
        accounts.addPlant(c, plant, plant + " 공장");
        accounts.exec(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C-001', 'C-001 상사', 'SALES', 't', 't'), (?, 'SUP1', '공급사', 'PURCHASE', 't', 't')""", c, c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'FG-100', 'FG 품목', 'FG', 'MAKE', 'EA', 'N', 't', 't'), (?, 'FG-L', 'FG LOT 품목', 'FG', 'MAKE', 'EA', 'Y', 't', 't')""",
                c, c);
    }

    /** 공장마다 창고 WH-{공장} 아래 위치 */
    long node(String c, String plant, String cd) {
        Long wh = accounts.query(c, "select max(node_id) from mst_equip_hier where node_cd = ?", Long.class, "WH-" + plant);
        if (wh == null) {
            wh = accounts.query(c, """
                    insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                    values (?, ?, 'WH', ?, ?, 't', 't') returning node_id""", Long.class, c, plant, "WH-" + plant, "WH-" + plant);
        }
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, ?, ?, 'LOC', ?, ?, 't', 't') returning node_id""", Long.class, c, plant, wh, cd, cd);
    }

    long itemId(String c, String cd) {
        return accounts.query(c, "select item_id from mst_item where item_cd = ?", Long.class, cd);
    }

    void adjIn(String c, String plant, long item, long node, String qty) {
        accounts.exec(c, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, ?, 'ADJ_IN', ?, ?, 'AVAILABLE', ?::numeric, 'EA', 'MANUAL', now() - interval '1 hour', 'KEY', 't')""",
                c, plant, item, node, qty);
    }

    BigDecimal stock(long node, long item) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and item_id = ?", BigDecimal.class,
                node, item).stripTrailingZeros();
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    Cookie terminal(String cd, String type) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\",\"terminalType\":\"" + type + "\"}").with(admin)).andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        MvcResult a = mvc.perform(json(post("/api/pop/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return new Cookie(PopAuthService.COOKIE, set.substring(set.indexOf('=') + 1, set.indexOf(';')));
    }

    void badgeIn() throws Exception {
        mvc.perform(json(post("/api/pop/badge"), "{\"badge\":\"BADGE-0001\"}").cookie(pda).session(s)).andExpect(status().isOk());
    }

    ResultActions pdaGet(String url) throws Exception {
        return mvc.perform(get(url).cookie(pda).session(s));
    }

    ResultActions pdaPost(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).cookie(pda).session(s));
    }

    /** 사무 화면에서 수주(FG-100 10) → 출하 지시(공장·대기 칸) → 지시 id */
    long ship(String plant, long stage) throws Exception {
        return ship(comp, admin, plant, stage);
    }

    long ship(String c, RequestPostProcessor who, String plant, long stage) throws Exception {
        MvcResult o = mvc.perform(json(post("/api/sales/orders"), "{\"custCd\":\"C-001\",\"lines\":[{\"itemCd\":\"FG-100\",\"qty\":\"10\","
                + "\"dueDate\":\"2026-11-30\"}]}").with(who)).andExpect(status().isOk()).andReturn();
        long orderId = ((Number) JsonPath.read(o.getResponse().getContentAsString(), "$.data")).longValue();
        long ol = accounts.query(c, "select line_id from sal_order_line where order_id = ?", Long.class, orderId);
        MvcResult m = mvc.perform(json(post("/api/wms/ships"), "{\"plantCd\":\"" + plant + "\",\"custCd\":\"C-001\",\"stageNodeId\":" + stage
                + ",\"planDate\":\"2026-11-20\",\"lines\":[{\"orderLineId\":" + ol + ",\"qty\":10}]}").with(who))
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.head.shipId")).longValue();
    }

    int shipVer(long id) {
        return accounts.query(comp, "select row_ver from wms_ship where ship_id = ?", Integer.class, id);
    }

    long sline(long id) {
        return accounts.query(comp, "select ship_line_id from wms_ship_line where ship_id = ? and line_no = 1", Long.class, id);
    }

    String picks(int ver, String req, long line, long from, String qty) {
        return "{\"rowVer\":" + ver + ",\"clientReqId\":\"" + req + "\",\"picks\":[{\"shipLineId\":" + line + ",\"fromNodeId\":" + from
                + ",\"qty\":" + qty + "}]}";
    }

    long supplier(String c) {
        long cust = accounts.query(c, "select cust_id from mst_cust where cust_cd = 'SUP1'", Long.class);
        accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't') "
                + "on conflict do nothing", cust, c);
        return cust;
    }

    /** 확정된 반품 지시(SRM 화면 대신 직접) — FG-100(LOT 비관리)의 받은 LOT, 교체 요구 없음 */
    String confirmedReturn(String c, String plant, String no, String qty) {
        long cust = supplier(c);
        long lot = accounts.query(c, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', 30, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG-100' returning lot_id""",
                Long.class, c, "L-" + no);
        long id = accounts.query(c, """
                insert into srm_return (comp_cd, return_no, cust_id, plant_cd, ncr_id, ncr_no, ncr_line_no, lot_id, lot_no, item_id, unit_cd, qty,
                                        created_by, updated_by)
                select ?, ?, ?, ?, ?, ?, 1, ?, ?, item_id, 'EA', ?::numeric, 't', 't' from mst_item where item_cd = 'FG-100'
                returning return_id""", Long.class, c, no, cust, plant, Math.abs(no.hashCode()) + 1L, "NCR-" + no, lot, "L-" + no, qty);
        accounts.exec(c, "update srm_return set status = 'CONFIRMED', confirmed_at = now(), confirmed_by = 't' where return_id = ?", id);
        return no;
    }

    String issue(String no, String qty, String req) {
        return "{\"returnNo\":\"" + no + "\",\"rows\":[{\"nodeId\":" + a1 + ",\"stockStatus\":\"AVAILABLE\",\"qty\":" + qty
                + "}],\"clientReqId\":\"" + req + "\"}";
    }

    String ret(String no) {
        return accounts.query(comp, "select status || ':' || updated_by from srm_return where return_no = ?", String.class, no);
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void 반품_라벨_스캔으로_보고_출고하면_원장_작업자_단말_SRM_출고됨_같은_요청은_한_번만() throws Exception {
        String no = confirmedReturn(comp, "P1", "RT-1", "5");
        badgeIn();
        pdaGet(WH + "/returns/available").andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        pdaGet(WH + "/returns?returnNo=RTN:" + no).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ret.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.stock[0].nodeId").value(a1));
        String req = UUID.randomUUID().toString();
        pdaPost(WH + "/returns", issue(no, "5", req)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay").value(false)).andExpect(jsonPath("$.data.shipped.status").value("SHIPPED"));
        assertThat(stock(a1, fg)).isEqualByComparingTo("25");
        long worker = accounts.query(comp, "select worker_id from mst_worker where worker_cd = 'W-001'", Long.class);
        String actor = accounts.query(comp, "select recorded_by from inv_trx where trx_type = 'RTN_SUP'", String.class);
        assertThat(actor).matches("t\\d+:PDA-01");
        assertThat(accounts.query(comp, "select performed_by || ':' || entry_method from inv_trx where trx_type = 'RTN_SUP'", String.class))
                .isEqualTo(worker + ":SCAN");
        assertThat(ret(no)).isEqualTo("SHIPPED:" + actor);                     // SRM 게이트 by = 단말 행위자(최대 41자, 칸 50자)
        // 두 번 누름 — 같은 요청 id 는 처음 결과
        pdaPost(WH + "/returns", issue(no, "5", req)).andExpect(status().isOk()).andExpect(jsonPath("$.data.replay").value(true));
        assertThat(stock(a1, fg)).isEqualByComparingTo("25");
    }

    // =====================================================================
    // 거부 · 권한 · A사로 B사
    // =====================================================================

    @Test
    void 작업자_없으면_428_수량이_지시와_다르면_409_모두_그대로() throws Exception {
        String no = confirmedReturn(comp, "P1", "RT-2", "5");
        pdaGet(WH + "/returns?returnNo=" + no).andExpect(status().isPreconditionRequired());
        pdaPost(WH + "/returns", issue(no, "5", UUID.randomUUID().toString())).andExpect(status().isPreconditionRequired());
        badgeIn();
        pdaPost(WH + "/returns", issue(no, "4", UUID.randomUUID().toString())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SRM_RTN_QTY_MISMATCH"));
        pdaPost(WH + "/returns", "{\"returnNo\":\"" + no + "\",\"rows\":[]}").andExpect(status().isBadRequest());
        assertThat(stock(a1, fg)).isEqualByComparingTo("30");
        assertThat(ret(no)).startsWith("CONFIRMED:");
    }

    @Test
    void POP_유형_단말과_사무_계정은_403() throws Exception {
        String no = confirmedReturn(comp, "P1", "RT-3", "5");
        Cookie pop = terminal("POP-01", "POP");
        mvc.perform(get(WH + "/returns?returnNo=" + no).cookie(pop)).andExpect(status().isForbidden());
        mvc.perform(json(post(WH + "/returns"), issue(no, "5", UUID.randomUUID().toString())).cookie(pop)).andExpect(status().isForbidden());
        mvc.perform(get(WH + "/returns?returnNo=" + no).with(admin)).andExpect(status().isForbidden());
        assertThat(ret(no)).startsWith("CONFIRMED:");
    }

    @Test
    void 다른_공장_반품과_A사로_B사는_없는_것() throws Exception {
        accounts.addPlant(comp, "P2", "P2 공장");
        String p2 = confirmedReturn(comp, "P2", "RT-P2", "5");
        badgeIn();
        pdaGet(WH + "/returns?returnNo=" + p2).andExpect(status().isBadRequest());
        pdaPost(WH + "/returns", issue(p2, "5", UUID.randomUUID().toString())).andExpect(status().isBadRequest());
        assertThat(ret(p2)).startsWith("CONFIRMED:");
        // B사 — 같은 공장 코드 P1 의 확정 반품
        String b = accounts.newComp("PU");
        seed(b, "P1");
        confirmedReturn(b, "P1", "RT-B", "5");
        pdaGet(WH + "/returns?returnNo=RT-B").andExpect(status().isBadRequest());
        pdaPost(WH + "/returns", issue("RT-B", "5", UUID.randomUUID().toString())).andExpect(status().isBadRequest());
        assertThat(accounts.query(b, "select status from srm_return where return_no = 'RT-B'", String.class)).isEqualTo("CONFIRMED");
        assertThat(stock(a1, fg)).isEqualByComparingTo("30");
    }
}
