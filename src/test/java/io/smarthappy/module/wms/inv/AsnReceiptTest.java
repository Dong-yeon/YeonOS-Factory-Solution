package io.smarthappy.module.wms.inv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * ASN 입고 (P4 2부 R3b, docs/30 12.9 · SRM docs/31 4.11) — SRM {@code AsnQuery} 로 ASN 을 찾아 칸을 채우고, 게이트 ASN 오버로드로 받는다.
 * ASN 스캔 키, 도착 예정 목록·번호·납품서 번호 찾기, ASN 행 입고(받은 양·상태), ASN 수량 초과 입고 허용(R3-4), 역분개(ASN 받은 양도 되돌림),
 * 거부(발주 행 없이 ASN 행·다른 발주 행의 ASN 행·다른 공장 ASN), A사로 B사.
 */
@AutoConfigureMockMvc
class AsnReceiptTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String PO = "/api/srm/pos";
    private static final String ASN = "/api/srm/asns";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor me;
    long a1;
    long p2Loc;
    int seq;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("AR");
        me = user(comp, "admin1");
        seed(comp);
        long wh = node(comp, "P1", null, "WH", "RM-WH");
        a1 = node(comp, "P1", wh, "LOC", "A-01");
        long wh2 = node(comp, "P2", null, "WH", "P2-WH");
        p2Loc = node(comp, "P2", wh2, "LOC", "B-01");
    }

    private RequestPostProcessor user(String c, String id) {
        long u = accounts.newUser(c, id, PW);
        accounts.grant(c, u, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, u, "PUR", "srm-asn", "SEARCH", "SAVE");
        accounts.grant(c, u, "WMS", "wms-rcv", "SEARCH", "SAVE");
        accounts.grant(c, u, "WMS", "wms-trx", "SEARCH", "SAVE");
        return accounts.as(c, u, id);
    }

    private void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.addPlant(c, "P2", "2공장");
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT-M8', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't')""", c);
        long id = accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'V100', 'V100 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, c);
        accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, c);
    }

    private long node(String c, String plant, Long parent, String type, String cd) {
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, c, plant, parent, type, cd, cd);
    }

    private ResultActions postJson(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data")).longValue();
    }

    /** 발행된 발주(그 공장, 볼트 100 EA) → 발주 행 id */
    private long poLine(RequestPostProcessor who, String c, String plant) throws Exception {
        long po = id(postJson(who, PO, "{\"custCd\":\"V100\",\"plantCd\":\"" + plant + "\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"100\",\"unitPrice\":\"1\","
                + "\"dueDate\":\"" + TODAY.plusDays(20) + "\"}]}"));
        for (String a : List.of("confirm", "issue")) {
            int v = JsonPath.read(mvc.perform(get(PO + "/" + po).with(who)).andReturn().getResponse().getContentAsString(), "$.data.po.rowVer");
            postJson(who, PO + "/" + po + "/" + a, "{\"rowVer\":" + v + "}").andExpect(status().isOk());
        }
        return accounts.query(c, "select po_line_id from srm_po_line where po_id = ?", Long.class, po);
    }

    /** 확정(SENT)한 ASN — 행 하나(발주 행·수량·공급사 LOT·유통기한) → ASN 번호 */
    private String asn(RequestPostProcessor who, String c, String plant, long poLine, String qty, String ref) throws Exception {
        long a = id(postJson(who, ASN, "{\"custCd\":\"V100\",\"plantCd\":\"" + plant + "\",\"shipDate\":\"" + TODAY + "\",\"etaDate\":\"" + TODAY.plusDays(2)
                + "\",\"supplierRef\":\"" + ref + "\",\"lines\":[{\"poLineId\":" + poLine + ",\"qty\":\"" + qty + "\",\"supplierLotNo\":\"SL-" + ref
                + "\",\"expiryDate\":\"" + TODAY.plusYears(1) + "\"}]}"));
        int v = JsonPath.read(mvc.perform(get(ASN + "/" + a).with(who)).andReturn().getResponse().getContentAsString(), "$.data.asn.rowVer");
        postJson(who, ASN + "/" + a + "/send", "{\"rowVer\":" + v + "}").andExpect(status().isOk());
        return accounts.query(c, "select asn_no from srm_asn where asn_id = ?", String.class, a);
    }

    private long asnLine(String asnNo) {
        return accounts.query(comp, "select l.asn_line_id from srm_asn_line l join srm_asn a on a.asn_id = l.asn_id where a.asn_no = ?", Long.class, asnNo);
    }

    private String asnState(String asnNo) {
        return accounts.query(comp, "select a.status || ':' || coalesce(trim_scale((select sum(r.qty) from srm_po_receipt r where r.asn_line_id = l.asn_line_id))::text, '0') "
                + "from srm_asn a join srm_asn_line l on l.asn_id = a.asn_id where a.asn_no = ?", String.class, asnNo);
    }

    private String req() {
        return "asn-rcv-" + (++seq) + "-" + System.nanoTime();
    }

    private ResultActions receive(long node, Long poLine, Long asnLine, String qty) throws Exception {
        return postJson(me, "/api/wms/rcv", "{\"plantCd\":\"" + (node == p2Loc ? "P2" : "P1") + "\",\"nodeId\":" + node + ",\"poLineId\":" + poLine
                + ",\"asnLineId\":" + asnLine + ",\"qty\":" + qty + ",\"stockStatus\":\"AVAILABLE\",\"supplierLotNo\":\"SL-" + (++seq) + "\",\"clientReqId\":\""
                + req() + "\"}");
    }

    @Test
    void ASN_스캔_목록_찾기로_행을_읽고_ASN_행으로_받으면_받은_양과_상태가_같이_간다() throws Exception {
        long pl = poLine(me, comp, "P1");
        String no = asn(me, comp, "P1", pl, "60", "DN-1");
        long al = asnLine(no);
        // 스캔 ASN:{번호} → 유형 ASN
        postJson(me, "/api/wms/rcv/scan", "{\"plantCd\":\"P1\",\"text\":\"ASN:" + no + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("ASN")).andExpect(jsonPath("$.data.asnNo").value(no));
        mvc.perform(get("/api/wms/rcv/asns").param("plantCd", "P1").with(me)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].asnNo").value(no)).andExpect(jsonPath("$.data[0].openLineCnt").value(1));
        for (String key : List.of(no, "ASN:" + no, "DN-1")) {                 // 번호·바코드·협력사 납품서 번호
            mvc.perform(get("/api/wms/rcv/asn").param("plantCd", "P1").param("key", key).with(me)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.lines[0].asnLineId").value(al)).andExpect(jsonPath("$.data.lines[0].poLineId").value(pl))
                    .andExpect(jsonPath("$.data.lines[0].remainQty").value(60)).andExpect(jsonPath("$.data.lines[0].supplierLotNo").value("SL-DN-1"));
        }
        receive(a1, pl, al, "40").andExpect(status().isOk());
        assertThat(asnState(no)).isEqualTo("PARTIAL_RECEIVED:40");
        mvc.perform(get("/api/wms/rcv/asn").param("plantCd", "P1").param("key", no).with(me)).andExpect(jsonPath("$.data.lines[0].remainQty").value(20));
        // ASN 남은 양(20)보다 많이 받아도 막지 않는다(발주 허용 초과율로만 — R3-4)
        receive(a1, pl, al, "30").andExpect(status().isOk());
        assertThat(asnState(no)).isEqualTo("RECEIVED:70");
        assertThat(accounts.query(comp, "select trim_scale(recv_qty)::text from srm_po_line where po_line_id = ?", String.class, pl)).isEqualTo("70");
    }

    @Test
    void 같은_ASN_행을_같은_공급사_LOT_으로_나눠_받으면_내부_LOT_이_둘() throws Exception {
        // 결정 #43(MES V135) — ASN 의 공급사 LOT 그대로 두 번 받기(R3b 에서 거부되던 흐름)
        long pl = poLine(me, comp, "P1");
        String no = asn(me, comp, "P1", pl, "60", "DN-9");
        long al = asnLine(no);
        for (String q : List.of("40", "20")) {
            postJson(me, "/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"poLineId\":" + pl + ",\"asnLineId\":" + al + ",\"qty\":" + q
                    + ",\"stockStatus\":\"AVAILABLE\",\"supplierLotNo\":\"SL-DN-9\",\"clientReqId\":\"" + req() + "\"}").andExpect(status().isOk());
        }
        assertThat(asnState(no)).isEqualTo("RECEIVED:60");
        assertThat(accounts.query(comp, "select count(*) from lot_master where supplier_lot_no = 'SL-DN-9'", Integer.class)).isEqualTo(2);
    }

    @Test
    void 역분개하면_ASN_받은_양과_상태도_되돌아간다() throws Exception {
        long pl = poLine(me, comp, "P1");
        String no = asn(me, comp, "P1", pl, "60", "DN-2");
        long al = asnLine(no);
        MvcResult m = receive(a1, pl, al, "60").andExpect(status().isOk()).andReturn();
        long trx = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.trxId")).longValue();
        assertThat(asnState(no)).isEqualTo("RECEIVED:60");
        postJson(me, "/api/wms/trx/" + trx + "/reverse", "{\"reason\":\"수량 잘못\"}").andExpect(status().isOk());
        assertThat(asnState(no)).isEqualTo("SENT:0");
        assertThat(accounts.query(comp, "select trim_scale(recv_qty)::text from srm_po_line where po_line_id = ?", String.class, pl)).isEqualTo("0");
    }

    @Test
    void 발주_행_없이_ASN_행_다른_발주_행의_ASN_행_다른_공장_ASN_은_거부() throws Exception {
        long pl = poLine(me, comp, "P1");
        long other = poLine(me, comp, "P1");
        String no = asn(me, comp, "P1", pl, "10", "DN-3");
        long al = asnLine(no);
        receive(a1, null, al, "1").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("asnLineId"))
                .andExpect(jsonPath("$.errors[0].code").value("ASN_NEEDS_PO"));
        receive(a1, other, al, "1").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SRM_ASN_LINE_MISMATCH"));
        assertThat(asnState(no)).isEqualTo("SENT:0");
        // 2공장 ASN 은 1공장 입고 화면에서 고를 수 없다
        String p2 = asn(me, comp, "P2", poLine(me, comp, "P2"), "5", "DN-4");
        mvc.perform(get("/api/wms/rcv/asn").param("plantCd", "P1").param("key", p2).with(me)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("PO_PLANT"));
        mvc.perform(get("/api/wms/rcv/asn").param("plantCd", "P1").param("key", "NO-SUCH").with(me)).andExpect(status().isNotFound());
        mvc.perform(get("/api/wms/rcv/asn").param("plantCd", "P1").param("key", "").with(me)).andExpect(status().isBadRequest());
    }

    @Test
    void A사로_B사_ASN_은_찾을_수_없고_ASN_행으로_받을_수_없다() throws Exception {
        String other = accounts.newComp("AQ");
        RequestPostProcessor ob = user(other, "buyer2");
        seed(other);
        long bpl = poLine(ob, other, "P1");
        String bno = asn(ob, other, "P1", bpl, "10", "DN-B");
        long bal = accounts.query(other, "select l.asn_line_id from srm_asn_line l join srm_asn a on a.asn_id = l.asn_id where a.asn_no = ?", Long.class, bno);
        mvc.perform(get("/api/wms/rcv/asn").param("plantCd", "P1").param("key", bno).with(me)).andExpect(status().isNotFound());
        mvc.perform(get("/api/wms/rcv/asns").param("plantCd", "P1").with(me)).andExpect(jsonPath("$.data.length()").value(0));
        long pl = poLine(me, comp, "P1");
        receive(a1, pl, bal, "1").andExpect(status().isNotFound());
        assertThat(accounts.query(other, "select status from srm_asn where asn_no = ?", String.class, bno)).isEqualTo("SENT");
    }
}
