package io.smarthappy.module.wms.inv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
 * B5 통합 — 실제 POP 실적 경로(MES 훅 #180: LotService.edge/reverse → LotStockLink)로 재고 원장 반영 (docs/30 12.4).
 * 공장 스위치 WMS_STOCK_LINK = Y: 투입 → ISSUE_WO(보급 라인 라인 옆 먼저), 부족 → POP 409 INSUFFICIENT_STOCK·실적 없음, 정정 → 원래 칸으로,
 * 재전송 → 두 번 안 빠짐, 스위치 N → 반영 없음.
 */
@AutoConfigureMockMvc
class PopStockLinkTest extends PostgresTestBase {

    private static final String PW = "Cedar-lantern-41";
    private static final String P = "/api/pop";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    long a1;
    long ls1;
    long rm;
    long rmLot;
    String rmLotNo;
    long spot;
    String cookie;
    MockHttpSession s;
    String ip;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PS");
        ip = "10.81." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, u, "ADM", "prd-wo", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-rcv", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-move", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-trx", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        long area = node(null, "AREA", "A1");
        long line1 = node(area, "LINE", "L1");
        long cell1 = node(line1, "CELL", "C1");
        long wh = node(null, "WH", "RM-WH");
        a1 = node(wh, "LOC", "A-01");
        long lsWh = node(area, "WH", "LS-WH");
        ls1 = node(lsWh, "LOC", "LS-1");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, feed_node_id, created_by, updated_by) "
                + "values (?, ?, 'LINESIDE', ?, 't', 't')", ls1, comp, line1);
        accounts.exec(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'E1', '작업자', 'P1', ?, 't', 't')""", comp, WorkerService.badgeHash(comp, "BADGE-0001"));
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'FG', 'FG', 'FG', 'MAKE', 'EA', 'N', 't', 't'), (?, 'RM', 'RM', 'RM', 'BUY', 'EA', 'Y', 't', 't')""", comp, comp);
        rm = accounts.query(comp, "select item_id from mst_item where item_cd = 'RM'", Long.class);
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) "
                + "values (?, 'SUP1', 'SUP1', 'PURCHASE', 't', 't')", comp);
        accounts.exec(comp, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'SPOT', '스폿 용접', 'WELD', 't', 't')""", comp);
        long rid = accounts.query(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                returning routing_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'SPOT'), ?, 30, 't', 't')""", comp, rid, cell1);
        accounts.exec(comp, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        long woId = id(mvc.perform(json(post("/api/production/wos"),
                "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"100\",\"planStart\":\"" + today + "\"}").with(admin)));
        mvc.perform(json(post("/api/production/wos/" + woId + "/release"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        spot = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 10", Long.class, woId);
        // 자재: 자재 입고 20(A-01) → 라인 옆 LS-1 로 5
        MvcResult m = mvc.perform(json(post("/api/wms/rcv"), "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + rm
                + ",\"qty\":20,\"stockStatus\":\"AVAILABLE\",\"supplierCd\":\"SUP1\",\"clientReqId\":\"" + req() + "\"}").with(admin))
                .andExpect(status().isOk()).andReturn();
        rmLotNo = JsonPath.read(m.getResponse().getContentAsString(), "$.data.lotNo");
        rmLot = accounts.query(comp, "select lot_id from lot_master where lot_no = ?", Long.class, rmLotNo);
        mvc.perform(json(post("/api/wms/move/move"), "{\"plantCd\":\"P1\",\"itemId\":" + rm + ",\"fromNodeId\":" + a1
                + ",\"fromStatus\":\"AVAILABLE\",\"toNodeId\":" + ls1 + ",\"toStatus\":\"AVAILABLE\",\"qty\":5,\"lotNo\":\"" + rmLotNo + "\"}")
                .with(admin)).andExpect(status().isOk());
        policy("Y");
        cookie = terminal("TAB-01", line1);
        s = new MockHttpSession();
        mvc.perform(json(post(P + "/badge"), "{\"badge\":\"BADGE-0001\"}").cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s))
                .andExpect(status().isOk());
        pop(post(P + "/procs/" + spot + "/events/start"), "{\"clientReqId\":\"" + req() + "\"}").andExpect(status().isOk());
    }

    long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd);
    }

    void policy(String yn) {
        accounts.exec(comp, "delete from sys_policy where policy_key = 'WMS_STOCK_LINK'");
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) "
                + "values (?, 'P1', 'WMS_STOCK_LINK', ?, 't', 't')", comp, yn);
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    String terminal(String cd, long nodeId) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\",\"nodeId\":" + nodeId + "}").with(admin)).andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        MvcResult a = mvc.perform(json(post(P + "/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return set.substring(set.indexOf('=') + 1, set.indexOf(';'));
    }

    ResultActions pop(MockHttpServletRequestBuilder b, String body) throws Exception {
        return mvc.perform(json(b, body).cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s));
    }

    ResultActions result(String reqId, String good, String inputQty) throws Exception {
        return pop(post(P + "/procs/" + spot + "/results"), "{\"clientReqId\":\"" + reqId + "\",\"goodQty\":\"" + good
                + "\",\"inputs\":[{\"lotNo\":\"" + rmLotNo + "\",\"qty\":\"" + inputQty + "\"}]}");
    }

    static String req() {
        return UUID.randomUUID().toString();
    }

    BigDecimal stock(long node) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and lot_id = ? and stock_status = 'AVAILABLE'",
                BigDecimal.class, node, rmLot).stripTrailingZeros();
    }

    int count(String sql, Object... args) {
        return accounts.query(comp, sql, Integer.class, args);
    }

    @Test
    void POP_실적_투입은_라인_옆_먼저_차감되고_재전송은_한_번만() throws Exception {
        String r = req();
        result(r, "8", "8").andExpect(status().isOk());
        assertThat(stock(ls1)).isEqualByComparingTo("0");
        assertThat(stock(a1)).isEqualByComparingTo("12");
        assertThat(count("""
                select count(*) from inv_trx t join lot_genealogy g on g.gen_id = t.src_id
                 where t.trx_type = 'ISSUE_WO' and t.src_type = 'LOT_GEN' and g.rel_type = 'CONSUME' and g.wo_proc_id = ?""", spot))
                .isEqualTo(2);
        // 같은 요청 재전송 — MES replay, 원장 그대로
        result(r, "8", "8").andExpect(status().isOk());
        assertThat(stock(a1)).isEqualByComparingTo("12");
    }

    @Test
    void 재고가_모자라면_POP_409_INSUFFICIENT_STOCK_실적_계보_없음() throws Exception {
        // LOT 잔량 20 · 재고 5(15 조정 출고) — 계보 잔량은 넉넉하고 재고만 모자람
        mvc.perform(json(post("/api/wms/move/adjust"), "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + rm
                + ",\"stockStatus\":\"AVAILABLE\",\"delta\":-15,\"reasonCd\":\"FOUND\",\"lotNo\":\"" + rmLotNo + "\"}").with(admin))
                .andExpect(status().isOk());
        result(req(), "8", "8").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        assertThat(count("select count(*) from prd_result where wo_proc_id = ?", spot)).isZero();
        assertThat(count("select count(*) from lot_genealogy where parent_lot_id = ?", rmLot)).isZero();
        assertThat(stock(ls1).add(stock(a1))).isEqualByComparingTo("5");
    }

    @Test
    void POP_정정은_투입_원장을_원래_칸으로_되돌린다() throws Exception {
        MvcResult m = result(req(), "8", "8").andExpect(status().isOk()).andReturn();
        long rid = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.resultId")).longValue();
        pop(post(P + "/results/" + rid + "/correct"), "{\"reason\":\"수량 오입력\"}").andExpect(status().isOk());
        assertThat(stock(ls1)).isEqualByComparingTo("5");
        assertThat(stock(a1)).isEqualByComparingTo("15");
        assertThat(count("select count(*) from inv_trx where trx_type = 'ISSUE_WO' and entry_method = 'CORRECT'")).isEqualTo(2);
    }

    /** 투입 차감(ISSUE_WO, 출처 LOT_GEN)은 수불 원장 화면에서 되돌리지 못한다(400 MES_SOURCE) — POP 정정(출처 경로)은 그대로 된다 */
    @Test
    void 투입_차감_원장은_원장_화면에서_되돌리지_못하고_POP_정정은_된다() throws Exception {
        MvcResult m = result(req(), "8", "8").andExpect(status().isOk()).andReturn();
        long rid = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.resultId")).longValue();
        long trx = count("select min(trx_id)::int from inv_trx where trx_type = 'ISSUE_WO'");
        mvc.perform(post("/api/wms/trx/" + trx + "/reverse").with(csrf()).with(admin).contentType("application/json").content("{\"reason\":\"사람이 되돌림\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("MES_SOURCE"));
        assertThat(count("select count(*) from inv_trx where trx_type = 'ISSUE_WO' and entry_method = 'CORRECT'")).isZero();
        pop(post(P + "/results/" + rid + "/correct"), "{\"reason\":\"수량 오입력\"}").andExpect(status().isOk());
        assertThat(count("select count(*) from inv_trx where trx_type = 'ISSUE_WO' and entry_method = 'CORRECT'")).isEqualTo(2);
        assertThat(stock(ls1).add(stock(a1))).isEqualByComparingTo("20");
    }

    @Test
    void 스위치_N_이면_POP_실적은_재고를_건드리지_않는다() throws Exception {
        policy("N");
        result(req(), "8", "8").andExpect(status().isOk());
        assertThat(count("select count(*) from inv_trx where trx_type = 'ISSUE_WO'")).isZero();
        assertThat(stock(ls1).add(stock(a1))).isEqualByComparingTo("20");
    }
}
