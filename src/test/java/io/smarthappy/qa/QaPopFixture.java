package io.smarthappy.qa;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * QA 공용 고정 데이터 — 실제 POP(단말·작업자·지시 공정) + 실제 WMS(창고 위치·스위치 WMS_STOCK_LINK)를 한 회사에 준비한다.
 * 라인 2개(L1·L2), 셀 C1·C2, 창고 RM-WH/A-01. 지시 2개: FG1(C1 op10 · L1 op20), FG2(C2 op10). 단말 TAB-1(L1)·TAB-2(L2), 작업자 2명.
 * 모듈 시험(PopWorkTest·LotStockPostingTest)은 WMS 를 가짜로 두거나 MES 를 흉내 내므로, 이 고정 데이터는 둘을 실제로 잇는 데만 쓴다.
 */
final class QaPopFixture {

    static final String PW = "Quartz-harbor-58";

    final MockMvc mvc;
    final TestAccounts accounts;
    final String comp;
    final RequestPostProcessor admin;
    final long userId;
    final long line1;
    final long line2;
    final long cell1;
    final long cell2;
    final long a1;
    final long wo1;
    final long wo2;
    /** FG1 op10 (C1) */
    final long spot;
    /** FG1 op20 (L1) */
    final long insp;
    /** FG2 op10 (C2) */
    final long spot2;
    final Pop pop1;
    final Pop pop2;
    private int seq;

    /** 단말 하나 + 작업자 세션 */
    record Pop(String cookie, MockHttpSession session) {
    }

    QaPopFixture(MockMvc mvc, TestAccounts accounts, String prefix) throws Exception {
        this.mvc = mvc;
        this.accounts = accounts;
        comp = accounts.newComp(prefix);
        userId = accounts.newUser(comp, "admin1", PW);
        for (String[] g : new String[][] {{"sys-terminal", "SAVE"}, {"prd-wo", "SAVE"}, {"wms-rcv", "SAVE"}, {"wms-move", "SAVE"},
                {"prd-lot", "SAVE"}}) {
            accounts.grant(comp, userId, "QA", g[0], "SEARCH", g[1]);
        }
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        long area = node(null, "AREA", "AR");
        line1 = node(area, "LINE", "L1");
        cell1 = node(line1, "CELL", "C1");
        line2 = node(area, "LINE", "L2");
        cell2 = node(line2, "CELL", "C2");
        long wh = node(null, "WH", "RM-WH");
        a1 = node(wh, "LOC", "A-01");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, pick_seq, created_by, updated_by) "
                + "values (?, ?, 'STORAGE', 1, 't', 't')", a1, comp);
        item("FG1", "EA", "Y", "MAKE");
        item("FG2", "EA", "Y", "MAKE");
        item("RM", "KG", "Y", "BUY");
        item("NUT", "EA", "N", "BUY");
        item("BOLT", "EA", "N", "BUY");
        accounts.exec(comp, "insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by) "
                + "values (?, 'SPOT', '스폿', 'WELD', 't', 't'), (?, 'INSP', '검사', 'INSPECT', 't', 't')", comp, comp);
        routing("FG1", new Object[][] {{10, "SPOT", cell1}, {20, "INSP", line1}});
        routing("FG2", new Object[][] {{10, "SPOT", cell2}});
        bom("FG1");
        bom("FG2");
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) "
                + "values (?, 'SUP1', 'SUP1', 'PURCHASE', 't', 't')", comp);
        wo1 = wo("FG1", today);
        wo2 = wo("FG2", today);
        spot = proc(wo1, 10);
        insp = proc(wo1, 20);
        spot2 = proc(wo2, 10);
        worker("E1", "BADGE-QA-01");
        worker("E2", "BADGE-QA-02");
        pop1 = new Pop(terminal("TAB-1", line1), new MockHttpSession());
        pop2 = new Pop(terminal("TAB-2", line2), new MockHttpSession());
        badge(pop1, "BADGE-QA-01");
        badge(pop2, "BADGE-QA-02");
        stockLink("Y");
    }

    long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd);
    }

    private void item(String cd, String unit, String lotYn, String procType) {
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, ?, ?, 'RM', ?, ?, ?, 't', 't')""", comp, cd, cd, procType, unit, lotYn);
    }

    private void routing(String itemCd, Object[][] steps) {
        long rid = accounts.query(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = ?), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                returning routing_id""", Long.class, comp, itemCd);
        for (Object[] s : steps) {
            accounts.exec(comp, """
                    insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, created_by, updated_by)
                    values (?, ?, ?, (select proc_id from mst_proc where proc_cd = ?), ?, 30, 't', 't')""", comp, rid, s[0], s[1], s[2]);
        }
        accounts.exec(comp, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
    }

    private void bom(String itemCd) {
        long bom = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, base_qty, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = ?), 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 1, 't', 't')
                returning bom_id""", Long.class, comp, itemCd);
        accounts.exec(comp, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, issue_method, created_by, updated_by)
                values (?, ?, 10, (select item_id from mst_item where item_cd = 'RM'), 1, 'KG', 'NORMAL', 'BACKFLUSH', 't', 't')""", comp, bom);
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", bom);
    }

    private long wo(String itemCd, String today) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/wos"),
                "{\"plantCd\":\"P1\",\"itemCd\":\"" + itemCd + "\",\"planQty\":\"100\",\"planStart\":\"" + today + "\"}").with(admin))
                .andExpect(status().isOk()).andReturn();
        long id = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
        mvc.perform(json(post("/api/production/wos/" + id + "/release"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        return id;
    }

    private long proc(long wo, int op) {
        return accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = ?", Long.class, wo, op);
    }

    private void worker(String cd, String badge) {
        accounts.exec(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, ?, ?, 'P1', ?, 't', 't')""", comp, cd, cd, WorkerService.badgeHash(comp, badge));
    }

    private String terminal(String cd, long nodeId) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\",\"nodeId\":" + nodeId + "}").with(admin)).andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        String ip = "10.92." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        MvcResult a = mvc.perform(json(post("/api/pop/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return set.substring(set.indexOf('=') + 1, set.indexOf(';'));
    }

    private void badge(Pop p, String badge) throws Exception {
        mvc.perform(json(post("/api/pop/badge"), "{\"badge\":\"" + badge + "\"}").cookie(new Cookie(PopAuthService.COOKIE, p.cookie()))
                .session(p.session())).andExpect(status().isOk());
    }

    void stockLink(String yn) {
        accounts.exec(comp, "delete from sys_policy where policy_key = 'WMS_STOCK_LINK'");
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) "
                + "values (?, 'P1', 'WMS_STOCK_LINK', ?, 't', 't')", comp, yn);
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    static String req() {
        return UUID.randomUUID().toString();
    }

    ResultActions pop(Pop p, MockHttpServletRequestBuilder b, String body) throws Exception {
        return mvc.perform(json(b, body).cookie(new Cookie(PopAuthService.COOKIE, p.cookie())).session(p.session()));
    }

    void start(Pop p, long proc) throws Exception {
        pop(p, post("/api/pop/procs/" + proc + "/events/start"), "{\"clientReqId\":\"" + req() + "\",\"ack\":true}")
                .andExpect(status().isOk());
    }

    ResultActions result(Pop p, long proc, String reqId, String body) throws Exception {
        return pop(p, post("/api/pop/procs/" + proc + "/results"), "{\"clientReqId\":\"" + reqId + "\"," + body + "}");
    }

    /** WMS 자재 입고 → MES LOT 번호 (비관리 품목도 MES LOT 은 생긴다 — 재고 원장의 lot_id 만 비어 있음) */
    String receive(String itemCd, String qty) throws Exception {
        long itemId = accounts.query(comp, "select item_id from mst_item where item_cd = ?", Long.class, itemCd);
        MvcResult m = mvc.perform(json(post("/api/wms/rcv"), "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + itemId + ",\"qty\":"
                + qty + ",\"stockStatus\":\"AVAILABLE\",\"supplierCd\":\"SUP1\",\"clientReqId\":\"qa-rcv-" + (++seq) + "-" + System.nanoTime()
                + "\"}").with(admin)).andExpect(status().isOk()).andReturn();
        return JsonPath.read(m.getResponse().getContentAsString(), "$.data.lotNo");
    }

    long lotId(String lotNo) {
        return accounts.query(comp, "select lot_id from lot_master where lot_no = ?", Long.class, lotNo);
    }

    /** A-01 의 그 품목 재고(LOT 관리 품목은 그 LOT, 비관리 품목은 LOT 없음) */
    java.math.BigDecimal stock(String itemCd, String lotNo) {
        return accounts.query(comp, """
                select coalesce(sum(s.qty), 0) from inv_stock s join mst_item i on i.item_id = s.item_id
                 where s.node_id = ? and i.item_cd = ? and s.stock_status = 'AVAILABLE'
                   and s.lot_id is not distinct from (select lot_id from lot_master where lot_no = ?)""",
                java.math.BigDecimal.class, a1, itemCd, lotNo).stripTrailingZeros();
    }

    int count(String sql, Object... args) {
        return accounts.query(comp, sql, Integer.class, args);
    }
}
