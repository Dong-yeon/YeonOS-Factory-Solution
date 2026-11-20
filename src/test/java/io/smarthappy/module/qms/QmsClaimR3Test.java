package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import io.smarthappy.module.wms.link.ShipQuery;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QMS 2차 R3-c·R3-d (QA docs/qa/2026-11-02_round3.md, docs/29_P3 47·48장) — 고객 반품이 만든 클레임 NCR:
 * 부적합 수량 ≤ 반품 수량(저장 409 대신 칸 오류 400 — 47장 R3C-3), 그래서 종결 때 WMS 가 다른 반품 칸에서 가져가지 않는다(QA 재현 반대).
 * 8D 반품 표: 8D 고객과 다른 고객의 반품(같은 8D 번호)은 빼고 건수 안내, NCR 로 이은 반품은 남김, 200건 잘림 안내, A사로 B사.
 */
@AutoConfigureMockMvc
class QmsClaimR3Test extends PostgresTestBase {

    private static final String PW = "Willow-ember-58";
    private static final String R = "/api/wms/cust-rtns";
    private static final String S = "/api/wms/ships";
    private static final String A = "/api/qms/capas";
    private static final String N = "/api/qms/ncrs";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    int seq;

    final class Co {
        final String comp;
        final RequestPostProcessor admin;
        final long a1;
        final long qc;
        final long stg;
        final long fgLot;
        final long defect;

        Co(String prefix) {
            comp = accounts.newComp(prefix);
            long u = accounts.newUser(comp, "admin1", PW);
            for (String[] g : new String[][] {{"sal-order", "SEARCH", "SAVE"}, {"wms-ship", "SEARCH", "SAVE", "APPROVE"}, {"wms-rcv", "SEARCH", "SAVE"},
                    {"wms-cust-rtn", "SEARCH", "SAVE", "APPROVE"}, {"qms-ncr", "SEARCH", "SAVE", "DELETE"}, {"qms-capa", "SEARCH", "SAVE"}}) {
                accounts.grant(comp, u, "G_ADMIN1", g[0], java.util.Arrays.copyOfRange(g, 1, g.length));
            }
            admin = accounts.as(comp, u, "admin1");
            accounts.addPlant(comp, "P1", "1공장");
            accounts.exec(comp, """
                    insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                    values (?, 'C-001', 'C-001 상사', 'SALES', 't', 't'), (?, 'C-002', 'C-002 상사', 'SALES', 't', 't'),
                           (?, 'SUP1', '공급사', 'PURCHASE', 't', 't')""", comp, comp, comp);
            accounts.exec(comp, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                    values (?, 'FG-L', 'FG LOT 품목', 'FG', 'MAKE', 'EA', 'Y', 't', 't')""", comp);
            accounts.exec(comp, """
                    insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                    values (?, 'D-RTN', '고객 불량', 'OTHER', 't', 't')""", comp);
            defect = accounts.query(comp, "select defect_id from qms_defect where defect_cd = 'D-RTN'", Long.class);
            long wh = node(null, "WH", "FG-WH");
            a1 = node(wh, "LOC", "A-01");
            qc = node(wh, "LOC", "QC-01");
            stg = node(wh, "LOC", "STG-01");
            accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'QC', 't', 't')", qc, comp);
            accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't')", stg, comp);
            fgLot = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG-L'", Long.class);
        }

        private long node(Long parent, String type, String cd) {
            return accounts.query(comp, """
                    insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                    values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd);
        }

        ResultActions postJson(String url, String body) throws Exception {
            return mvc.perform(post(url).with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON).content(body));
        }

        ResultActions putJson(String url, String body) throws Exception {
            return mvc.perform(put(url).with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON).content(body));
        }

        long lotIn(String qty) throws Exception {
            MvcResult m = postJson("/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + fgLot + ",\"qty\":" + qty
                    + ",\"stockStatus\":\"AVAILABLE\",\"supplierCd\":\"SUP1\",\"clientReqId\":\"" + req() + "\"}").andExpect(status().isOk()).andReturn();
            String no = JsonPath.read(m.getResponse().getContentAsString(), "$.data.lotNo");
            return accounts.query(comp, "select lot_id from lot_master where lot_no = ?", Long.class, no);
        }

        int shipVer(long id) {
            return accounts.query(comp, "select row_ver from wms_ship where ship_id = ?", Integer.class, id);
        }

        long lineOf(long shipId) {
            return accounts.query(comp, "select ship_line_id from wms_ship_line where ship_id = ?", Long.class, shipId);
        }

        /** LOT 을 그 고객에게 출하까지 — 출하 행 id */
        long shippedLine(long lot, String qty, String cust) throws Exception {
            MvcResult o = postJson("/api/sales/orders", "{\"custCd\":\"" + cust + "\",\"lines\":[{\"itemCd\":\"FG-L\",\"qty\":\"" + qty
                    + "\",\"dueDate\":\"2026-11-30\"}]}").andExpect(status().isOk()).andReturn();
            long order = ((Number) JsonPath.read(o.getResponse().getContentAsString(), "$.data")).longValue();
            long ol = accounts.query(comp, "select line_id from sal_order_line where order_id = ? and line_no = 10", Long.class, order);
            MvcResult s = postJson(S, "{\"plantCd\":\"P1\",\"custCd\":\"" + cust + "\",\"stageNodeId\":" + stg + ",\"planDate\":\"2026-11-20\",\"lines\":[{"
                    + "\"orderLineId\":" + ol + ",\"qty\":" + qty + "}]}").andExpect(status().isOk()).andReturn();
            long id = ((Number) JsonPath.read(s.getResponse().getContentAsString(), "$.data.head.shipId")).longValue();
            String lotNo = accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, lot);
            postJson(S + "/" + id + "/pick", "{\"rowVer\":" + shipVer(id) + ",\"clientReqId\":\"" + req() + "\",\"picks\":[{\"shipLineId\":" + lineOf(id)
                    + ",\"fromNodeId\":" + a1 + ",\"lotNo\":\"" + lotNo + "\",\"qty\":" + qty + "}]}").andExpect(status().isOk());
            postJson(S + "/" + id + "/confirm", "{\"rowVer\":" + shipVer(id) + ",\"clientReqId\":\"" + req() + "\"}").andExpect(status().isOk());
            return lineOf(id);
        }

        long rtn(long line, long lot, String qty, String capaNo) throws Exception {
            MvcResult m = postJson(R, "{\"plantCd\":\"P1\",\"shipLineId\":" + line + ",\"lotId\":" + lot + ",\"qty\":" + qty
                    + ",\"reasonCd\":\"QUALITY\",\"nodeId\":" + qc + (capaNo == null ? "" : ",\"capaNo\":\"" + capaNo + "\"")
                    + ",\"clientReqId\":\"" + req() + "\"}").andExpect(status().isOk()).andReturn();
            return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.rtnId")).longValue();
        }

        long capa(String cust) throws Exception {
            MvcResult m = postJson(A, "{\"source\":\"" + (cust == null ? "INTERNAL" : "CUSTOMER_CLAIM") + "\""
                    + (cust == null ? "" : ",\"custCd\":\"" + cust + "\"") + ",\"title\":\"반품 클레임\"}").andExpect(status().isOk()).andReturn();
            return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
        }

        String capaNo(long id) {
            return accounts.query(comp, "select capa_no from qms_capa where capa_id = ?", String.class, id);
        }

        String rtnNo(long id) {
            return accounts.query(comp, "select rtn_no from wms_cust_rtn where rtn_id = ?", String.class, id);
        }

        long ncrOf(long rtnId) {
            return accounts.query(comp, "select ncr_id from wms_cust_rtn where rtn_id = ?", Long.class, rtnId);
        }

        int ncrVer(long ncr) {
            return accounts.query(comp, "select row_ver from qms_ncr where ncr_id = ?", Integer.class, ncr);
        }

        ResultActions saveNcr(long ncr, String qty, String disps) throws Exception {
            return putJson(N + "/" + ncr, "{\"rowVer\":" + ncrVer(ncr) + ",\"defectId\":" + defect + ",\"ncQty\":" + qty
                    + ",\"description\":\"고객 반품 불량\",\"disps\":[" + disps + "]}");
        }

        /** "칸 경로:상태:수량" — 그 LOT 재고 칸들 */
        String stock(long lot) {
            return accounts.query(comp, """
                    select coalesce(string_agg(h.node_cd || ':' || s.stock_status || ':' || trim_scale(s.qty)::text, ',' order by h.node_cd, s.stock_status), '')
                      from inv_stock s join mst_equip_hier h on h.node_id = s.node_id where s.lot_id = ? and s.qty <> 0""", String.class, lot);
        }
    }

    Co a;

    String req() {
        return "r3-" + (++seq) + "-" + System.nanoTime();
    }

    @BeforeEach
    void setUp() {
        a = new Co("R3");
    }

    static String disp(String type, String qty) {
        return "{\"rowKey\":\"" + type + "\",\"dispType\":\"" + type + "\",\"qty\":" + qty + ",\"note\":\"MRB\"}";
    }

    // ------------------------------------------------------------------ R3-c

    /**
     * QA R3-c 재현 반대: 같은 LOT 반품 둘(반품 1 = 2, 반품 2 = 2). 반품 1 NCR 의 부적합 수량을 3 으로 늘리면 — 전에는 200 이고 종결 때 반품 2 칸에서 1 을
     * 가져갔다. 지금은 칸 오류(NCR_CLAIM_QTY, 반품 수량 2), 수량 2·폐기 2 로 종결하면 반품 1 칸만 쓰이고 반품 2 칸(판정 전)은 그대로
     */
    @Test
    void 클레임_NCR_부적합_수량은_반품_수량을_넘지_못하고_종결은_자기_반품_칸만_쓴다() throws Exception {
        long lot = a.lotIn("6");
        long line = a.shippedLine(lot, "6", "C-001");
        // WMS 품질 연동(공장 스위치) 켬 — NCR 종결이 처분을 재고 원장으로 반영(docs/30 12.15)
        accounts.exec(a.comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) values (?, 'P1', 'WMS_QUALITY_LINK', 'Y', 't', 't')",
                a.comp);
        long r1 = a.rtn(line, lot, "2", null);
        long r2 = a.rtn(line, lot, "2", null);
        long n1 = a.ncrOf(r1);
        assertThat(accounts.query(a.comp, "select trim_scale(src_qty)::text from qms_ncr where ncr_id = ?", String.class, n1)).isEqualTo("2");
        mvc.perform(get(N + "/" + n1).with(a.admin)).andExpect(jsonPath("$.data.ncr.srcQty").value(2));
        a.saveNcr(n1, "3", disp("SCRAP", "3")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("ncQty")).andExpect(jsonPath("$.errors[0].code").value("NCR_CLAIM_QTY"))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsString("2")));
        assertThat(accounts.query(a.comp, "select trim_scale(nc_qty)::text from qms_ncr where ncr_id = ?", String.class, n1)).isEqualTo("2");
        // 줄이는 것은 된다(반품 일부만 불량) — 그 뒤 다시 2 까지 늘리기도 된다(상한은 반품 수량, 처음 값이 아님)
        a.saveNcr(n1, "1", disp("SCRAP", "1")).andExpect(status().isOk());
        a.saveNcr(n1, "2", disp("SCRAP", "2")).andExpect(status().isOk());
        String before = a.stock(lot);
        a.postJson(N + "/" + n1 + "/close", "{\"rowVer\":" + a.ncrVer(n1) + "}").andExpect(status().isOk());
        // 반품 1 의 2 개만 폐기 — QC 칸의 BLOCKED 4 → 2(반품 2 몫), 다른 칸은 그대로
        assertThat(before).contains("QC-01:BLOCKED:4");
        assertThat(a.stock(lot)).contains("QC-01:BLOCKED:2");
        assertThat(accounts.query(a.comp, "select status from qms_ncr where ncr_id = ?", String.class, a.ncrOf(r2))).isEqualTo("OPEN");
    }

    @Test
    void 클레임_NCR_직접SQL_근거_수량은_고정이고_넘는_수량은_CHECK() throws Exception {
        long lot = a.lotIn("2");
        long line = a.shippedLine(lot, "2", "C-001");
        long n = a.ncrOf(a.rtn(line, lot, "1", null));
        constraint(a.comp, "ck_qms_ncr_src_qty", "update qms_ncr set nc_qty = 1.001 where ncr_id = ?", n);
        constraint(a.comp, "ck_qms_ncr_immutable", "update qms_ncr set src_qty = 5, nc_qty = 5 where ncr_id = ?", n);
        // 근거가 있는데 근거 수량 없이 INSERT(계약을 건너뛴 SQL)
        constraint(a.comp, "ck_qms_ncr_src_qty_required", """
                insert into qms_ncr (comp_cd, ncr_no, source, lot_id, item_id, unit_cd, nc_qty, description, occurred_at, hold_yn, src_type, src_id,
                                     created_by, updated_by)
                select comp_cd, 'X-RQ', 'CLAIM', lot_id, item_id, unit_cd, 1, 'x', now(), 'N', 'WMS_CUST_RTN', 999999, 't', 't' from qms_ncr where ncr_id = ?""",
                n);
    }

    @Test
    void A사로_B사_클레임_NCR_은_B사에서_저장_못하고_A사_수량은_그대로() throws Exception {
        long lot = a.lotIn("2");
        long line = a.shippedLine(lot, "2", "C-001");
        long n = a.ncrOf(a.rtn(line, lot, "2", null));
        Co b = new Co("R4");
        mvc.perform(put(N + "/" + n).with(csrf()).with(b.admin).contentType(MediaType.APPLICATION_JSON).content("{\"rowVer\":" + a.ncrVer(n)
                + ",\"defectId\":" + b.defect + ",\"ncQty\":1,\"description\":\"B\",\"disps\":[]}")).andExpect(status().isNotFound());
        mvc.perform(get(N + "/" + n).with(b.admin)).andExpect(status().isNotFound());
        assertThat(accounts.query(a.comp, "select trim_scale(nc_qty)::text || '/' || description from qms_ncr where ncr_id = ?", String.class, n))
                .startsWith("2/");
    }

    // ------------------------------------------------------------------ R3-d

    @Test
    void 팔디_고객과_다른_고객의_반품은_같은_번호라도_빼고_건수만_알린다_NCR_로_이은_반품은_남긴다() throws Exception {
        long lot = a.lotIn("6");
        long l1 = a.shippedLine(lot, "3", "C-001");
        long l2 = a.shippedLine(lot, "3", "C-002");
        long capa = a.capa("C-001");
        String no = a.capaNo(capa);
        long mine = a.rtn(l1, lot, "1", no);                  // 8D 고객 반품, 번호 적음
        long otherNo = a.rtn(l2, lot, "1", no);               // 다른 고객이 같은 번호를 적음 → 뺌
        long otherNcr = a.rtn(l2, lot, "1", null);            // 다른 고객 반품이지만 8D 에 그 NCR 을 이음 → 남김
        String ncrNo = accounts.query(a.comp, "select ncr_no from qms_ncr where ncr_id = ?", String.class, a.ncrOf(otherNcr));
        int ver = accounts.query(a.comp, "select row_ver from qms_capa where capa_id = ?", Integer.class, capa);
        a.putJson(A + "/" + capa, "{\"title\":\"반품 클레임\",\"custCd\":\"C-001\",\"ncrNo\":\"" + ncrNo + "\",\"rowVer\":" + ver + "}")
                .andExpect(status().isOk());

        mvc.perform(get(A + "/" + capa).with(a.admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.custReturns.length()").value(2))
                .andExpect(jsonPath("$.data.custReturns[?(@.rtnNo == '" + a.rtnNo(mine) + "')].custCd").value("C-001"))
                .andExpect(jsonPath("$.data.custReturns[?(@.rtnNo == '" + a.rtnNo(otherNcr) + "')].custCd").value("C-002"))
                .andExpect(jsonPath("$.data.custReturns[?(@.rtnNo == '" + a.rtnNo(otherNo) + "')]").isEmpty())
                .andExpect(jsonPath("$.data.returnsOtherCust").value(1))
                .andExpect(jsonPath("$.data.returnsTruncated").value(false))
                .andExpect(jsonPath("$.data.returnsMax").value(ShipQuery.RETURNS_MAX));
        // 고객이 없는 8D(내부)는 거르지 않는다 — 같은 번호를 적은 반품 모두
        long internal = a.capa(null);
        String no2 = a.capaNo(internal);
        a.rtn(l1, lot, "1", no2);
        a.rtn(l2, lot, "1", no2);
        mvc.perform(get(A + "/" + internal).with(a.admin)).andExpect(jsonPath("$.data.custReturns.length()").value(2))
                .andExpect(jsonPath("$.data.returnsOtherCust").value(0));
    }

    @Test
    void 이백건에_닿으면_잘림을_알리고_고객_거르기는_잘린_목록_위에서() throws Exception {
        long lot = a.lotIn("2");
        long l1 = a.shippedLine(lot, "2", "C-001");
        long capa = a.capa("C-001");
        String no = a.capaNo(capa);
        long first = a.rtn(l1, lot, "1", no);
        // 같은 반품을 SQL 로 200 건까지 복제(받은 시각을 앞으로 — 받은 시각 desc 라 원래 행은 잘린다). 화면 길은 반품 하나에 출하가 필요해 느리다
        accounts.exec(a.comp, """
                insert into wms_cust_rtn (comp_cd, rtn_no, plant_cd, cust_id, ship_id, ship_line_id, item_id, lot_id, qty, unit_cd, reason_cd, node_id,
                                          capa_no, status, client_req_id, received_at, received_by, created_by, updated_by)
                select comp_cd, rtn_no || '-' || g, plant_cd, cust_id, ship_id, ship_line_id, item_id, lot_id, qty, unit_cd, reason_cd, node_id,
                       capa_no, status, client_req_id || '-' || g, received_at + make_interval(secs => g), received_by, 't', 't'
                  from wms_cust_rtn cross join generate_series(1, 200) g where rtn_id = ?""", first);
        mvc.perform(get(A + "/" + capa).with(a.admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.custReturns.length()").value(ShipQuery.RETURNS_MAX))
                .andExpect(jsonPath("$.data.returnsTruncated").value(true))
                .andExpect(jsonPath("$.data.custReturns[?(@.rtnNo == '" + a.rtnNo(first) + "')]").isEmpty());
    }

    @Test
    void A사로_B사_같은_8D_번호_B사_반품은_안_보이고_다른_고객_건수에도_안_들어간다() throws Exception {
        long lot = a.lotIn("2");
        long l1 = a.shippedLine(lot, "2", "C-001");
        long capaA = a.capa("C-001");
        String no = a.capaNo(capaA);
        a.rtn(l1, lot, "1", no);
        Co b = new Co("R5");
        long lotB = b.lotIn("2");
        long lB = b.shippedLine(lotB, "2", "C-002");
        long capaB = b.capa("C-001");
        assertThat(b.capaNo(capaB)).isEqualTo(no);
        b.rtn(lB, lotB, "1", no);                             // B사·다른 고객·같은 번호
        mvc.perform(get(A + "/" + capaA).with(a.admin)).andExpect(jsonPath("$.data.custReturns.length()").value(1))
                .andExpect(jsonPath("$.data.returnsOtherCust").value(0));
        mvc.perform(get(A + "/" + capaB).with(b.admin)).andExpect(jsonPath("$.data.custReturns.length()").value(0))
                .andExpect(jsonPath("$.data.returnsOtherCust").value(1));
        mvc.perform(get(A + "/" + capaA).with(b.admin)).andExpect(status().isNotFound());
    }

    void constraint(String comp, String name, String sql, Object... args) {
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> accounts.exec(comp, sql, args));
        assertThat(t).as("DB 가 막아야 한다: " + name).isNotNull();
        assertThat(PgErrors.constraint(t)).as(String.valueOf(t)).contains(name);
    }
}
