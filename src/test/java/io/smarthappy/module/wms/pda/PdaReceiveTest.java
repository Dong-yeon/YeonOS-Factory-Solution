package io.smarthappy.module.wms.pda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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
 * 창고 PDA 입고 (docs/30 12.12.15, W-6 MES 단말 행위자 뒤) — 사무 입고와 같은 MaterialReceiptService 를 단말 + 배지 작업자로.
 * 자재 입고(발주 없이)·ASN → 발주 행 구매 입고, 기록(performed_by = 작업자, recorded_by·LOT 기록자 = 단말), 위치 후보 = 저장 판정
 * (출하 대기·폐기·재고 안 둠 거부, 기본 상태 QC), 같은 요청 다시 = 처음 결과. 거부: 작업자 없음 428, POP 유형·사무 계정 403, 본문 400,
 * 다른 공장 위치·ASN, A사로 B사.
 */
@AutoConfigureMockMvc
class PdaReceiveTest extends PostgresTestBase {

    private static final String PW = "Cedar-lantern-48";
    private static final String WH = "/api/pop/wh";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    String ip;
    RequestPostProcessor admin;
    long a1;
    long rcv;
    long stg;
    long scrap;
    long noStock;
    long bolt;
    long workerId;
    Cookie pda;
    MockHttpSession s;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PV");
        ip = "10.87." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        admin = admin(comp);
        seed(comp);
        a1 = node(comp, "P1", "A-01", null, null, "Y");
        rcv = node(comp, "P1", "RCV-01", "RECEIVING", "QC", "Y");
        stg = node(comp, "P1", "STG-01", "STAGING", null, "Y");
        noStock = node(comp, "P1", "NS-01", "STORAGE", null, "N");
        long sw = accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', 'WH', 'SCRAP-WH', 'SCRAP-WH', 't', 't') returning node_id""", Long.class, comp);
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'SCRAP', 't', 't')", sw, comp);
        scrap = accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, 'LOC', 'S-01', 'S-01', 't', 't') returning node_id""", Long.class, comp, sw);
        bolt = accounts.query(comp, "select item_id from mst_item where item_cd = 'BOLT-M8'", Long.class);
        workerId = accounts.query(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'W-001', '작업자', 'P1', ?, 't', 't') returning worker_id""", Long.class, comp, WorkerService.badgeHash(comp, "BADGE-0001"));
        pda = terminal("PDA-01", "PDA");
        s = new MockHttpSession();
    }

    // ---------- 도우미 ----------

    RequestPostProcessor admin(String c) {
        long u = accounts.newUser(c, "admin1", PW);
        accounts.grant(c, u, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(c, u, "ADM", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, u, "ADM", "srm-asn", "SEARCH", "SAVE");
        accounts.grant(c, u, "ADM", "wms-rcv", "SEARCH", "SAVE");
        return accounts.as(c, u, "admin1");
    }

    void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.addPlant(c, "P2", "2공장");
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT-M8', '볼트', 'RM', 'BUY', 'EA', 'Y', 't', 't')""", c);
        long id = accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'V100', 'V100 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, c);
        accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, c);
    }

    long node(String c, String plant, String cd, String usage, String defaultStatus, String stockYn) {
        Long wh = accounts.query(c, "select max(node_id) from mst_equip_hier where node_cd = ?", Long.class, "WH-" + plant);
        if (wh == null) {
            wh = accounts.query(c, """
                    insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                    values (?, ?, 'WH', ?, ?, 't', 't') returning node_id""", Long.class, c, plant, "WH-" + plant, "WH-" + plant);
        }
        long n = accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, ?, ?, 'LOC', ?, ?, 't', 't') returning node_id""", Long.class, c, plant, wh, cd, cd);
        if (usage != null) {
            accounts.exec(c, """
                    insert into wms_loc_attr (node_id, comp_cd, loc_usage, default_status, stock_yn, created_by, updated_by)
                    values (?, ?, ?, ?, ?, 't', 't')""", n, c, usage, defaultStatus, stockYn);
        }
        return n;
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

    ResultActions rcv(String req, long node, Long poLine, Long asnLine, String qty, String supLot) throws Exception {
        return mvc.perform(json(post(WH + "/rcv"), "{\"clientReqId\":" + (req == null ? "null" : "\"" + req + "\"") + ",\"itemId\":" + bolt
                + ",\"qty\":\"" + qty + "\",\"nodeId\":" + node + ",\"poLineId\":" + poLine + ",\"asnLineId\":" + asnLine + ",\"supplierLotNo\":"
                + (supLot == null ? "null" : "\"" + supLot + "\"") + ",\"entryMethod\":\"SCAN\"}").cookie(pda).session(s));
    }

    private ResultActions postJson(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(who));
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data")).longValue();
    }

    /** 발행된 발주(그 공장, 볼트 100 EA) → 발주 행 id */
    long poLine(RequestPostProcessor who, String c, String plant) throws Exception {
        long po = id(postJson(who, "/api/srm/pos", "{\"custCd\":\"V100\",\"plantCd\":\"" + plant + "\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"100\","
                + "\"unitPrice\":\"1\",\"dueDate\":\"" + TODAY.plusDays(20) + "\"}]}"));
        for (String a : List.of("confirm", "issue")) {
            int v = JsonPath.read(mvc.perform(get("/api/srm/pos/" + po).with(who)).andReturn().getResponse().getContentAsString(), "$.data.po.rowVer");
            postJson(who, "/api/srm/pos/" + po + "/" + a, "{\"rowVer\":" + v + "}").andExpect(status().isOk());
        }
        return accounts.query(c, "select po_line_id from srm_po_line where po_id = ?", Long.class, po);
    }

    /** 확정(SENT) ASN — 행 하나 → ASN 번호 */
    String asn(RequestPostProcessor who, String c, String plant, long poLine, String qty, String ref) throws Exception {
        long a = id(postJson(who, "/api/srm/asns", "{\"custCd\":\"V100\",\"plantCd\":\"" + plant + "\",\"shipDate\":\"" + TODAY + "\",\"etaDate\":\""
                + TODAY.plusDays(2) + "\",\"supplierRef\":\"" + ref + "\",\"lines\":[{\"poLineId\":" + poLine + ",\"qty\":\"" + qty
                + "\",\"supplierLotNo\":\"SL-" + ref + "\",\"expiryDate\":\"" + TODAY.plusYears(1) + "\"}]}"));
        int v = JsonPath.read(mvc.perform(get("/api/srm/asns/" + a).with(who)).andReturn().getResponse().getContentAsString(), "$.data.asn.rowVer");
        postJson(who, "/api/srm/asns/" + a + "/send", "{\"rowVer\":" + v + "}").andExpect(status().isOk());
        return accounts.query(c, "select asn_no from srm_asn where asn_id = ?", String.class, a);
    }

    BigDecimal stock(long node) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and item_id = ?", BigDecimal.class, node, bolt)
                .stripTrailingZeros();
    }

    String terminalActor() {
        return accounts.query(comp, "select 't' || terminal_id || ':' || terminal_cd from sys_terminal where terminal_cd = 'PDA-01'", String.class);
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void 발주_없이_품목_스캔_수량_위치로_받으면_LOT_이_생기고_작업자와_단말이_남는다() throws Exception {
        badgeIn();
        pdaGet(WH + "/rcv/scan?text=ITEM:BOLT-M8").andExpect(status().isOk()).andExpect(jsonPath("$.data.itemId").value(bolt));
        MvcResult m = rcv(UUID.randomUUID().toString(), a1, null, null, "12", "SUP-77").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay").value(false)).andReturn();
        String lotNo = JsonPath.read(m.getResponse().getContentAsString(), "$.data.lotNo");
        assertThat(stock(a1)).isEqualByComparingTo("12");
        String actor = terminalActor();
        assertThat(accounts.query(comp, """
                select t.trx_type || '/' || t.stock_status || '/' || t.performed_by || '/' || t.recorded_by || '/' || t.entry_method
                  from inv_trx t where t.node_id = ?""", String.class, a1)).isEqualTo("RCV_MAT/AVAILABLE/" + workerId + "/" + actor + "/SCAN");
        // LOT 의 기록자도 단말(W-6), 공급사 LOT 그대로
        assertThat(accounts.query(comp, "select created_by || '/' || supplier_lot_no from lot_master where lot_no = ?", String.class,
                lotNo)).isEqualTo(actor + "/SUP-77");
    }

    @Test
    void ASN_스캔으로_행을_고르고_받으면_발주_행과_ASN_받은_양이_같이_간다() throws Exception {
        long pl = poLine(admin, comp, "P1");
        String no = asn(admin, comp, "P1", pl, "60", "DN-1");
        badgeIn();
        pdaGet(WH + "/rcv/po-enabled").andExpect(jsonPath("$.data").value(true));
        pdaGet(WH + "/rcv/scan?text=ASN:" + no).andExpect(jsonPath("$.data.type").value("ASN")).andExpect(jsonPath("$.data.asnNo").value(no));
        MvcResult m = pdaGet(WH + "/rcv/asn?key=ASN:" + no).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].poLineId").value(pl)).andExpect(jsonPath("$.data.lines[0].remainQty").value(60)).andReturn();
        long al = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.lines[0].asnLineId")).longValue();
        pdaGet(WH + "/rcv/po-lines?itemId=" + bolt).andExpect(jsonPath("$.data[0].poLineId").value(pl)).andExpect(jsonPath("$.data[0].openQty").value(100));
        rcv(UUID.randomUUID().toString(), a1, pl, al, "40", "SL-DN-1").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select trx_type || '/' || performed_by from inv_trx where node_id = ?", String.class, a1))
                .isEqualTo("RCV_PO/" + workerId);
        assertThat(accounts.query(comp, "select a.status from srm_asn a where a.asn_no = ?", String.class, no)).isEqualTo("PARTIAL_RECEIVED");
        assertThat(accounts.query(comp, "select trim_scale(recv_qty)::text from srm_po_line where po_line_id = ?", String.class, pl)).isEqualTo("40");
    }

    @Test
    void 위치_후보는_저장과_같은_판정이고_목록에_없는_칸은_저장도_거부() throws Exception {
        badgeIn();
        MvcResult m = pdaGet(WH + "/rcv/locs?itemId=" + bolt).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].nodeId").value(rcv))                                  // 받는 칸 먼저
                .andExpect(jsonPath("$.data[0].status").value("QC"))                                 // 위치 기본 상태
                .andExpect(jsonPath("$.data[?(@.nodeId == " + a1 + ")].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.data[?(@.nodeId == " + stg + ")].reasonCd").value("STAGING_NOT_ALLOWED"))
                .andExpect(jsonPath("$.data[?(@.nodeId == " + scrap + ")].reasonCd").value("SCRAP_LOCATION"))
                .andExpect(jsonPath("$.data[?(@.nodeId == " + noStock + ")].reasonCd").value("STOCK_NOT_ALLOWED")).andReturn();
        assertThat(m.getResponse().getContentAsString()).doesNotContain("\"nodeCd\":\"WH-P2\"");    // 다른 공장 없음
        rcv(UUID.randomUUID().toString(), rcv, null, null, "3", null).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select stock_status from inv_trx where node_id = ?", String.class, rcv)).isEqualTo("QC");
        rcv(UUID.randomUUID().toString(), stg, null, null, "1", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("STAGING_NOT_ALLOWED"));
        rcv(UUID.randomUUID().toString(), scrap, null, null, "1", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SCRAP_LOCATION"));
        rcv(UUID.randomUUID().toString(), noStock, null, null, "1", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("STOCK_NOT_ALLOWED"));
        assertThat(stock(stg).add(stock(scrap)).add(stock(noStock))).isEqualByComparingTo("0");
        assertThat(accounts.query(comp, "select count(*) from lot_master", Integer.class)).isEqualTo(1);
    }

    @Test
    void 같은_요청을_다시_보내면_처음_결과_LOT_하나() throws Exception {
        badgeIn();
        String req = UUID.randomUUID().toString();
        String first = JsonPath.read(rcv(req, a1, null, null, "5", null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.data.lotNo");
        rcv(req, a1, null, null, "5", null).andExpect(status().isOk()).andExpect(jsonPath("$.data.replay").value(true))
                .andExpect(jsonPath("$.data.lotNo").value(first));
        assertThat(stock(a1)).isEqualByComparingTo("5");
        assertThat(accounts.query(comp, "select count(*) from lot_master", Integer.class)).isEqualTo(1);
    }

    // =====================================================================
    // 거부 · 이상 사용
    // =====================================================================

    @Test
    void 작업자_없음_428_POP_유형과_사무_계정_403_본문_400() throws Exception {
        rcv(UUID.randomUUID().toString(), a1, null, null, "1", null).andExpect(status().isPreconditionRequired());
        pdaGet(WH + "/rcv/locs?itemId=" + bolt).andExpect(status().isPreconditionRequired());
        Cookie pop = terminal("POP-01", "POP");
        mvc.perform(get(WH + "/rcv/scan?text=BOLT-M8").cookie(pop)).andExpect(status().isForbidden());
        mvc.perform(json(post(WH + "/rcv"), "{\"clientReqId\":\"x-1\",\"itemId\":" + bolt + ",\"qty\":\"1\",\"nodeId\":" + a1 + "}").with(admin))
                .andExpect(status().isForbidden());
        badgeIn();
        rcv(null, a1, null, null, "1", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("clientReqId"));
        rcv(UUID.randomUUID().toString(), a1, null, null, "1e", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("qty"));
        rcv(UUID.randomUUID().toString(), a1, null, null, "-2", null).andExpect(status().isBadRequest());
        mvc.perform(json(post(WH + "/rcv"), "").cookie(pda).session(s)).andExpect(status().isBadRequest());
        pdaGet(WH + "/rcv/scan?text=").andExpect(status().isBadRequest());
        pdaGet(WH + "/rcv/locs").andExpect(status().isBadRequest());
        assertThat(stock(a1)).isEqualByComparingTo("0");
        assertThat(accounts.query(comp, "select count(*) from lot_master", Integer.class)).isZero();
    }

    @Test
    void 다른_공장_위치_ASN_발주_행과_A사로_B사는_받지_않는다() throws Exception {
        long p2 = node(comp, "P2", "B-01", null, null, "Y");
        long p2Line = poLine(admin, comp, "P2");
        String p2Asn = asn(admin, comp, "P2", p2Line, "10", "DN-P2");
        badgeIn();
        rcv(UUID.randomUUID().toString(), p2, null, null, "1", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("nodeId"));
        pdaGet(WH + "/rcv/asn?key=" + p2Asn).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("PO_PLANT"));
        rcv(UUID.randomUUID().toString(), a1, p2Line, null, "1", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("PO_PLANT"));
        assertThat(accounts.query(comp, "select trim_scale(recv_qty)::text from srm_po_line where po_line_id = ?", String.class, p2Line)).isEqualTo("0");
        // B사 — 같은 공장 코드 P1 의 발주·ASN·위치·품목
        String b = accounts.newComp("PW");
        RequestPostProcessor bAdmin = admin(b);
        seed(b);
        long bLoc = node(b, "P1", "A-01", null, null, "Y");
        long bLine = poLine(bAdmin, b, "P1");
        String bAsn = asn(bAdmin, b, "P1", bLine, "10", "DN-B");
        long bItem = accounts.query(b, "select item_id from mst_item where item_cd = 'BOLT-M8'", Long.class);
        // B사 ASN 은 납품서 번호로(ASN 번호는 회사마다 채번이라 A사 자기 ASN 과 같을 수 있다) — 없음 404
        assertThat(bAsn).isNotBlank();
        pdaGet(WH + "/rcv/asn?key=DN-B").andExpect(status().isNotFound());
        pdaGet(WH + "/rcv/locs?itemId=" + bItem).andExpect(status().isBadRequest());
        rcv(UUID.randomUUID().toString(), bLoc, null, null, "1", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("nodeId"));
        rcv(UUID.randomUUID().toString(), a1, bLine, null, "1", null).andExpect(status().is4xxClientError());
        assertThat(accounts.query(b, "select count(*) from inv_trx", Integer.class)).isZero();
        assertThat(accounts.query(b, "select count(*) from lot_master", Integer.class)).isZero();
        assertThat(accounts.query(b, "select trim_scale(recv_qty)::text from srm_po_line where po_line_id = ?", String.class, bLine)).isEqualTo("0");
        assertThat(accounts.query(comp, "select count(*) from lot_master", Integer.class)).isZero();
    }
}
