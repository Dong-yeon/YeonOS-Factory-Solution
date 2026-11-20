package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.LongStream;

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
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 8D 상세의 묶인 고객 반품 (docs/29_P3 44장, R-8 후속) — WMS 계약 ShipQuery.custReturns.
 * 8D 번호를 적은 반품(btrim 후 정확히 같음 — 대소문자·부분 일치 없음) + 8D 에 이은 NCR 이 만든 반품, 취소 반품도 상태로, A사로 B사, NONE, 입력 규칙.
 */
@AutoConfigureMockMvc
class CapaCustRtnTest extends PostgresTestBase {

    private static final String PW = "Willow-ember-58";
    private static final String R = "/api/wms/cust-rtns";
    private static final String S = "/api/wms/ships";
    private static final String A = "/api/qms/capas";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    ShipQuery shipQuery;

    int seq;

    /** 회사 하나의 기준정보·권한·칸 */
    final class Co {
        final String comp;
        final RequestPostProcessor admin;
        final long a1;
        final long qc;
        final long stg;
        final long fgLot;

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
                    values (?, 'C-001', 'C-001 상사', 'SALES', 't', 't'), (?, 'SUP1', '공급사', 'PURCHASE', 't', 't')""", comp, comp);
            accounts.exec(comp, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                    values (?, 'FG-L', 'FG LOT 품목', 'FG', 'MAKE', 'EA', 'Y', 't', 't')""", comp);
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

        /** LOT 을 출하까지 — 출하 행 id */
        long shippedLine(long lot, String qty) throws Exception {
            MvcResult o = postJson("/api/sales/orders", "{\"custCd\":\"C-001\",\"lines\":[{\"itemCd\":\"FG-L\",\"qty\":\"" + qty
                    + "\",\"dueDate\":\"2026-11-30\"}]}").andExpect(status().isOk()).andReturn();
            long order = ((Number) JsonPath.read(o.getResponse().getContentAsString(), "$.data")).longValue();
            long ol = accounts.query(comp, "select line_id from sal_order_line where order_id = ? and line_no = 10", Long.class, order);
            MvcResult s = postJson(S, "{\"plantCd\":\"P1\",\"custCd\":\"C-001\",\"stageNodeId\":" + stg + ",\"planDate\":\"2026-11-20\",\"lines\":[{"
                    + "\"orderLineId\":" + ol + ",\"qty\":" + qty + "}]}").andExpect(status().isOk()).andReturn();
            long id = ((Number) JsonPath.read(s.getResponse().getContentAsString(), "$.data.head.shipId")).longValue();
            String lotNo = accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, lot);
            postJson(S + "/" + id + "/pick", "{\"rowVer\":" + shipVer(id) + ",\"clientReqId\":\"" + req() + "\",\"picks\":[{\"shipLineId\":" + lineOf(id)
                    + ",\"fromNodeId\":" + a1 + ",\"lotNo\":\"" + lotNo + "\",\"qty\":" + qty + "}]}").andExpect(status().isOk());
            postJson(S + "/" + id + "/confirm", "{\"rowVer\":" + shipVer(id) + ",\"clientReqId\":\"" + req() + "\"}").andExpect(status().isOk());
            return lineOf(id);
        }

        /** 반품 1 EA(capaNo 없으면 칸을 보내지 않음) — rtn_id */
        long rtn(long line, long lot, String capaNo) throws Exception {
            MvcResult m = postJson(R, "{\"plantCd\":\"P1\",\"shipLineId\":" + line + ",\"lotId\":" + lot + ",\"qty\":1,\"reasonCd\":\"QUALITY\",\"nodeId\":" + qc
                    + (capaNo == null ? "" : ",\"capaNo\":\"" + capaNo + "\"") + ",\"clientReqId\":\"" + req() + "\"}")
                    .andExpect(status().isOk()).andReturn();
            return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.rtnId")).longValue();
        }

        long capa() throws Exception {
            MvcResult m = postJson(A, "{\"source\":\"CUSTOMER_CLAIM\",\"custCd\":\"C-001\",\"title\":\"반품 클레임\"}").andExpect(status().isOk()).andReturn();
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
    }

    Co a;

    String req() {
        return "c8d-" + (++seq) + "-" + System.nanoTime();
    }

    @BeforeEach
    void setUp() {
        a = new Co("C8");
    }

    @Test
    void 팔디_번호를_적은_반품과_이은_NCR_의_반품을_보이고_취소도_상태로_대소문자_부분일치는_안_보인다() throws Exception {
        long lot = a.lotIn("6");
        long line = a.shippedLine(lot, "6");
        long capa = a.capa();
        String no = a.capaNo(capa);
        long byNo = a.rtn(line, lot, no);                         // 8D 번호를 적은 반품
        long byNcr = a.rtn(line, lot, null);                      // 번호 없음 — 8D 에 그 NCR 을 이음
        long lower = a.rtn(line, lot, no.toLowerCase());          // 대소문자 다름
        long partial = a.rtn(line, lot, no + "-1");               // 부분 일치
        long other = a.rtn(line, lot, null);                      // 상관없는 반품
        String ncrNo = accounts.query(a.comp, "select ncr_no from qms_ncr where ncr_id = ?", String.class, a.ncrOf(byNcr));
        int ver = accounts.query(a.comp, "select row_ver from qms_capa where capa_id = ?", Integer.class, capa);
        mvc.perform(put(A + "/" + capa).with(csrf()).with(a.admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"반품 클레임\",\"custCd\":\"C-001\",\"ncrNo\":\"" + ncrNo + "\",\"rowVer\":" + ver + "}")).andExpect(status().isOk());
        // 번호로 묶인 반품을 취소 — 목록에서 빠지지 않고 상태로 보인다
        int rv = accounts.query(a.comp, "select row_ver from wms_cust_rtn where rtn_id = ?", Integer.class, byNo);
        a.postJson(R + "/" + byNo + "/cancel", "{\"rowVer\":" + rv + ",\"reason\":\"잘못 받음\"}").andExpect(status().isOk());

        mvc.perform(get(A + "/" + capa).with(a.admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.custReturns.length()").value(2))
                .andExpect(jsonPath("$.data.custReturns[?(@.rtnNo == '" + a.rtnNo(byNo) + "')].status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.custReturns[?(@.rtnNo == '" + a.rtnNo(byNo) + "')].capaNo").value(no))
                .andExpect(jsonPath("$.data.custReturns[?(@.rtnNo == '" + a.rtnNo(byNcr) + "')].status").value("RECEIVED"))
                .andExpect(jsonPath("$.data.custReturns[?(@.rtnNo == '" + a.rtnNo(byNcr) + "')].ncrNo").value(ncrNo))
                .andExpect(jsonPath("$.data.custReturns[?(@.rtnNo == '" + a.rtnNo(byNcr) + "')].lotNo").exists())
                .andExpect(jsonPath("$.data.custReturns[?(@.rtnNo == '" + a.rtnNo(lower) + "')]").isEmpty())
                .andExpect(jsonPath("$.data.custReturns[?(@.rtnNo == '" + a.rtnNo(partial) + "')]").isEmpty())
                .andExpect(jsonPath("$.data.custReturns[?(@.rtnNo == '" + a.rtnNo(other) + "')]").isEmpty());
        // 계약 직접: 앞뒤 공백은 빼고 비교, 둘 다 비면 빈 목록
        assertThat(runner.call(a.comp, () -> shipQuery.custReturns("  " + no + "  ", List.of()))).extracting(ShipQuery.CustReturn::rtnId)
                .containsExactly(byNo);
        assertThat(runner.call(a.comp, () -> shipQuery.custReturns("  ", null))).isEmpty();
        // 묶인 반품이 없는 8D 는 빈 목록
        long empty = a.capa();
        mvc.perform(get(A + "/" + empty).with(a.admin)).andExpect(jsonPath("$.data.custReturns.length()").value(0));
    }

    @Test
    void A사로_B사_같은_8D_번호와_NCR_id_라도_다른_회사_반품은_안_보인다() throws Exception {
        long lotA = a.lotIn("2");
        long lineA = a.shippedLine(lotA, "2");
        long capaA = a.capa();
        String no = a.capaNo(capaA);
        long rA = a.rtn(lineA, lotA, no);
        long ncrA = a.ncrOf(rA);

        Co b = new Co("C9");
        long lotB = b.lotIn("2");
        long lineB = b.shippedLine(lotB, "2");
        long capaB = b.capa();
        assertThat(b.capaNo(capaB)).isEqualTo(no);               // 회사별 채번이라 같은 번호
        long rB = b.rtn(lineB, lotB, no);

        mvc.perform(get(A + "/" + capaA).with(a.admin)).andExpect(jsonPath("$.data.custReturns.length()").value(1))
                .andExpect(jsonPath("$.data.custReturns[0].rtnNo").value(a.rtnNo(rA)));
        mvc.perform(get(A + "/" + capaB).with(b.admin)).andExpect(jsonPath("$.data.custReturns.length()").value(1))
                .andExpect(jsonPath("$.data.custReturns[0].rtnNo").value(b.rtnNo(rB)));
        // B사 범위에서 A사 NCR id 로 물어도 빈 목록, A사 8D 상세는 404
        assertThat(runner.call(b.comp, () -> shipQuery.custReturns(null, List.of(ncrA)))).isEmpty();
        mvc.perform(get(A + "/" + capaA).with(b.admin)).andExpect(status().isNotFound());
    }

    @Test
    void NONE_은_빈_목록_NCR_id_상한_넘으면_호출_오류() {
        assertThat(ShipQuery.NONE.custReturns("8D-1", List.of(1L))).isEmpty();
        List<Long> many = LongStream.rangeClosed(1, ShipQuery.LOTS_MAX + 1).boxed().toList();
        assertThatThrownBy(() -> runner.call(a.comp, () -> shipQuery.custReturns(null, many))).isInstanceOf(IllegalArgumentException.class);
        // 중복·null 은 무시(상한 계산도 중복 뺀 뒤)
        List<Long> dup = new java.util.ArrayList<>(java.util.Collections.nCopies(ShipQuery.LOTS_MAX + 5, 7L));
        dup.add(null);
        assertThat(runner.call(a.comp, () -> shipQuery.custReturns(null, dup))).isEmpty();
    }
}
