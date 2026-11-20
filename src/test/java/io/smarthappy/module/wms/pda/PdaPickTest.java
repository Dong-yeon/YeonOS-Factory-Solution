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
 * 창고 PDA 피킹 API (B7-2a, docs/30 12.12.9) — 사무 화면과 같은 ShipService 를 단말 + 배지 작업자로. inbox 멱등·머리 rowVer 409(덮어쓰기 없음),
 * 온라인만(D-B25). 입고는 LOT 생성(MES LotService)이 로그인 사용자만 받아 보류(12.12.9).
 * 거부: 작업자 없음 428, 본문 400, POP 유형·사무 계정 403, 다른 공장·A사로 B사 = 없는 것.
 */
@AutoConfigureMockMvc
class PdaPickTest extends PostgresTestBase {

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
        comp = accounts.newComp("PR");
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

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void 피킹_목록_보기_제안_피킹하면_대기_칸으로_같은_요청은_한_번만_옛_rowVer_는_409() throws Exception {
        long id = ship("P1", stg);
        badgeIn();
        pdaGet(WH + "/ships").andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.shipId == " + id + ")]").isNotEmpty());
        pdaGet(WH + "/ships/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.data.head.status").value("DRAFT"));
        long line = sline(id);
        pdaGet(WH + "/ships/" + id + "/lines/" + line + "/suggest").andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].nodeId").value(a1));
        int ver = shipVer(id);
        String req = UUID.randomUUID().toString();
        pdaPost(WH + "/ships/" + id + "/picks", picks(ver, req, line, a1, "4")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.head.status").value("PICKING"));
        assertThat(stock(stg, fg)).isEqualByComparingTo("4");
        assertThat(stock(a1, fg)).isEqualByComparingTo("26");
        // 대기열·두 번 누름 — 같은 요청 id 는 한 번만
        pdaPost(WH + "/ships/" + id + "/picks", picks(ver, req, line, a1, "4")).andExpect(status().isOk());
        assertThat(stock(stg, fg)).isEqualByComparingTo("4");
        // 오래 열어 둔 다른 PDA(옛 rowVer, 새 요청) — 409, 재고 그대로
        pdaPost(WH + "/ships/" + id + "/picks", picks(ver, UUID.randomUUID().toString(), line, a1, "3")).andExpect(status().isConflict());
        assertThat(stock(stg, fg)).isEqualByComparingTo("4");
    }

    // =====================================================================
    // 거부 · 권한 · A사로 B사
    // =====================================================================

    @Test
    void 작업자_없으면_428_빈_피킹_0_재고보다_많이는_거부() throws Exception {
        long id = ship("P1", stg);
        pdaGet(WH + "/ships").andExpect(status().isPreconditionRequired());
        pdaPost(WH + "/ships/" + id + "/picks", picks(shipVer(id), UUID.randomUUID().toString(), sline(id), a1, "1"))
                .andExpect(status().isPreconditionRequired());
        assertThat(stock(stg, fg)).isEqualByComparingTo("0");
        badgeIn();
        pdaPost(WH + "/ships/" + id + "/picks", picks(shipVer(id), UUID.randomUUID().toString(), sline(id), a1, "0")).andExpect(status().isBadRequest());
        pdaPost(WH + "/ships/" + id + "/picks", picks(shipVer(id), UUID.randomUUID().toString(), sline(id), a1, "11")).andExpect(status().is4xxClientError());
        pdaPost(WH + "/ships/" + id + "/picks", "{\"rowVer\":" + shipVer(id) + ",\"clientReqId\":\"" + UUID.randomUUID() + "\",\"picks\":[]}")
                .andExpect(status().isBadRequest());
        assertThat(stock(a1, fg)).isEqualByComparingTo("30");
    }

    @Test
    void POP_유형_단말과_사무_계정은_403() throws Exception {
        Cookie pop = terminal("POP-01", "POP");
        mvc.perform(get(WH + "/ships").cookie(pop)).andExpect(status().isForbidden());
        long id = ship("P1", stg);
        mvc.perform(json(post(WH + "/ships/" + id + "/picks"), picks(shipVer(id), UUID.randomUUID().toString(), sline(id), a1, "1")).cookie(pop))
                .andExpect(status().isForbidden());
        mvc.perform(get(WH + "/ships").with(admin)).andExpect(status().isForbidden());
        assertThat(stock(stg, fg)).isEqualByComparingTo("0");
    }

    @Test
    void 다른_공장_출하와_A사로_B사는_없는_것() throws Exception {
        // 같은 회사 P2 의 출하 지시 — P1 PDA 에는 목록에도 없고 보기·피킹 404
        accounts.addPlant(comp, "P2", "P2 공장");
        long b1 = node(comp, "P2", "B-01");
        long stg2 = node(comp, "P2", "STG-02");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't')",
                stg2, comp);
        adjIn(comp, "P2", fg, b1, "10");
        long p2Ship = ship("P2", stg2);
        badgeIn();
        pdaGet(WH + "/ships").andExpect(jsonPath("$.data[?(@.shipId == " + p2Ship + ")]").isEmpty());
        pdaGet(WH + "/ships/" + p2Ship).andExpect(status().isNotFound());
        pdaGet(WH + "/ships/" + p2Ship + "/lines/" + sline(p2Ship) + "/suggest").andExpect(status().isNotFound());
        pdaPost(WH + "/ships/" + p2Ship + "/picks", picks(shipVer(p2Ship), UUID.randomUUID().toString(), sline(p2Ship), b1, "1"))
                .andExpect(status().isNotFound());
        // P1 출하를 P2 위치에서 피킹 — 공장 밖 칸은 서비스가 거부
        long id = ship("P1", stg);
        pdaPost(WH + "/ships/" + id + "/picks", picks(shipVer(id), UUID.randomUUID().toString(), sline(id), b1, "1")).andExpect(status().is4xxClientError());
        assertThat(stock(b1, fg)).isEqualByComparingTo("10");
        // B사 — 같은 공장 코드 P1 의 위치·출하 지시(B사 사무 화면으로 만든 것)
        String b = accounts.newComp("PS");
        long bu = accounts.newUser(b, "admin1", PW);
        accounts.grant(b, bu, "ADM", "sal-order", "SEARCH", "SAVE");
        accounts.grant(b, bu, "ADM", "wms-ship", "SEARCH", "SAVE", "APPROVE");
        seed(b, "P1");
        long bStg = node(b, "P1", "STG-01");
        accounts.exec(b, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't')",
                bStg, b);
        long bShip = ship(b, accounts.as(b, bu, "admin1"), "P1", bStg);
        pdaGet(WH + "/ships").andExpect(jsonPath("$.data[?(@.shipId == " + bShip + ")]").isEmpty());
        pdaGet(WH + "/ships/" + bShip).andExpect(status().isNotFound());
        pdaPost(WH + "/ships/" + bShip + "/picks", "{\"rowVer\":0,\"clientReqId\":\"" + UUID.randomUUID() + "\",\"picks\":[{\"shipLineId\":1,"
                + "\"fromNodeId\":" + a1 + ",\"qty\":1}]}").andExpect(status().isNotFound());
        assertThat(accounts.query(b, "select count(*) from inv_trx", Integer.class)).isZero();
        assertThat(accounts.query(b, "select status from wms_ship where ship_id = ?", String.class, bShip)).isEqualTo("DRAFT");
    }
}
