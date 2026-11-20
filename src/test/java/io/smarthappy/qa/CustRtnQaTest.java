package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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

import io.smarthappy.module.wms.link.QualityStockLink;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA 2026-11-02 고객 반품 입고(#749, V224, docs/59) 이상 사용 — 모듈 시험(CustRtnTest)이 덮지 않은 것만.
 * 결과·분류는 docs/qa/2026-11-02_cust-rtn.md. 지금 main 에서 실패하는 재현 시험(CR-a·b·c·e)은 이 클래스가 아니라 그 문서 부록 A 에 있다.
 */
@AutoConfigureMockMvc
class CustRtnQaTest extends PostgresTestBase {

    private static final String PW = "Willow-ember-58";
    private static final String R = "/api/wms/cust-rtns";
    private static final String S = "/api/wms/ships";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    QualityStockLink link;

    String comp;
    RequestPostProcessor admin;
    long a1;
    long qc;
    long stg;
    long fgLot;
    int seq;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("CQ");
        long u = accounts.newUser(comp, "admin1", PW);
        for (String[] g : new String[][] {{"sal-order", "SEARCH", "SAVE"}, {"wms-ship", "SEARCH", "SAVE", "APPROVE"}, {"wms-rcv", "SEARCH", "SAVE"},
                {"wms-trx", "SEARCH", "SAVE"}, {"wms-move", "SEARCH", "SAVE"}, {"wms-cust-rtn", "SEARCH", "SAVE", "APPROVE"}, {"qms-ncr", "SEARCH", "SAVE", "DELETE"}}) {
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
        a1 = node(wh, "LOC", "A-01");          // node_id 순서: a1 < qc (처분이 칸을 node_id 순으로 고르는지 보려고)
        qc = node(wh, "LOC", "QC-01");
        stg = node(wh, "LOC", "STG-01");
        attr(qc, "QC");
        attr(stg, "STAGING");
        fgLot = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG-L'", Long.class);
    }

    // ---------- 도우미 (CustRtnTest 와 같은 흐름) ----------

    private long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd);
    }

    private void attr(long node, String usage) {
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, ?, 't', 't')", node, comp, usage);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String req() {
        return "cqa-" + (++seq) + "-" + System.nanoTime();
    }

    private long lotIn(String qty) throws Exception {
        MvcResult m = postJson("/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + fgLot + ",\"qty\":" + qty
                + ",\"stockStatus\":\"AVAILABLE\",\"supplierCd\":\"SUP1\",\"clientReqId\":\"" + req() + "\"}").andExpect(status().isOk()).andReturn();
        String no = JsonPath.read(m.getResponse().getContentAsString(), "$.data.lotNo");
        return accounts.query(comp, "select lot_id from lot_master where lot_no = ?", Long.class, no);
    }

    private String lotNo(long lot) {
        return accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, lot);
    }

    private int shipVer(long id) {
        return accounts.query(comp, "select row_ver from wms_ship where ship_id = ?", Integer.class, id);
    }

    private long shipped(long lot, String qty) throws Exception {
        long id = picked(lot, qty);
        postJson(S + "/" + id + "/confirm", "{\"rowVer\":" + shipVer(id) + ",\"clientReqId\":\"" + req() + "\"}").andExpect(status().isOk());
        return id;
    }

    /** 수주 → 출하 지시 → 피킹(A-01 → STG, 확정 전) — 돌려줌 = 출하 id */
    private long picked(long lot, String qty) throws Exception {
        MvcResult o = postJson("/api/sales/orders", "{\"custCd\":\"C-001\",\"lines\":[{\"itemCd\":\"FG-L\",\"qty\":\"" + qty
                + "\",\"dueDate\":\"2026-11-30\"}]}").andExpect(status().isOk()).andReturn();
        long order = ((Number) JsonPath.read(o.getResponse().getContentAsString(), "$.data")).longValue();
        long ol = accounts.query(comp, "select line_id from sal_order_line where order_id = ? and line_no = 10", Long.class, order);
        MvcResult s = postJson(S, "{\"plantCd\":\"P1\",\"custCd\":\"C-001\",\"stageNodeId\":" + stg + ",\"planDate\":\"2026-11-20\",\"lines\":[{"
                + "\"orderLineId\":" + ol + ",\"qty\":" + qty + "}]}").andExpect(status().isOk()).andReturn();
        long id = ((Number) JsonPath.read(s.getResponse().getContentAsString(), "$.data.head.shipId")).longValue();
        postJson(S + "/" + id + "/pick", "{\"rowVer\":" + shipVer(id) + ",\"clientReqId\":\"" + req() + "\",\"picks\":[{\"shipLineId\":" + lineOf(id)
                + ",\"fromNodeId\":" + a1 + ",\"lotNo\":\"" + lotNo(lot) + "\",\"qty\":" + qty + "}]}").andExpect(status().isOk());
        return id;
    }

    private long lineOf(long shipId) {
        return accounts.query(comp, "select ship_line_id from wms_ship_line where ship_id = ?", Long.class, shipId);
    }

    private ResultActions rtn(String reqId, long shipLine, long lot, String qty, long node) throws Exception {
        return postJson(R, "{\"plantCd\":\"P1\",\"shipLineId\":" + shipLine + ",\"lotId\":" + lot + ",\"qty\":" + qty
                + ",\"reasonCd\":\"QUALITY\",\"nodeId\":" + node + ",\"clientReqId\":\"" + reqId + "\"}");
    }

    private long rtnId(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.rtnId")).longValue();
    }

    private ResultActions cancel(long id) throws Exception {
        int ver = accounts.query(comp, "select row_ver from wms_cust_rtn where rtn_id = ?", Integer.class, id);
        return postJson(R + "/" + id + "/cancel", "{\"rowVer\":" + ver + ",\"reason\":\"잘못 받음\"}");
    }

    private BigDecimal stock(long node, String st) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and item_id = ? and stock_status = ?",
                BigDecimal.class, node, fgLot, st).stripTrailingZeros();
    }

    private long ncrOf(long rtnId) {
        return accounts.query(comp, "select ncr_id from wms_cust_rtn where rtn_id = ?", Long.class, rtnId);
    }

    /** 둘을 같은 출발선에서 — 응답 코드 둘 */
    private List<Integer> race(Supplier<Integer> x, Supplier<Integer> y) {
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Integer>> runs = List.of(x, y).stream().map(f -> CompletableFuture.supplyAsync(() -> {
            try {
                go.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return f.get();
        })).toList();
        go.countDown();
        return runs.stream().map(CompletableFuture::join).toList();
    }

    private Supplier<Integer> call(ThrowingCall c) {
        return () -> {
            try {
                return c.run().andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    interface ThrowingCall {
        ResultActions run() throws Exception;
    }

    // =====================================================================

    /** CR-a(부록 A-1, 고침 2026-11-02): 반품 요청 id 가 이미 다른 원장(출하 확정 SHIP)에 쓰인 id 면 409 — 반품·NCR 모두 롤백 */
    @Test
    void 결함CRa_다른_원장이_쓴_요청_id_로_반품하면_409_이고_반품도_NCR_도_남지_않는다() throws Exception {
        long lot = lotIn("4");
        long ship = shipped(lot, "4");
        String used = accounts.query(comp, "select client_req_id from inv_trx where trx_type = 'SHIP' order by trx_id desc limit 1", String.class);
        rtn(used, lineOf(ship), lot, "2", qc).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        assertThat(accounts.query(comp, "select count(*) from wms_cust_rtn", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select count(*) from qms_ncr where src_type = 'WMS_CUST_RTN'", Integer.class)).isZero();
    }

    /** CR-e(부록 A-4, 고침 2026-11-02): 수불 원장 화면에서 반품이 걸린 출하의 SHIP 행을 역분개 — 4xx(400 SHIP_SOURCE) */
    @Test
    void 결함CRe_반품이_걸린_출하의_SHIP_원장을_원장_화면에서_되돌리지_못한다() throws Exception {
        long lot = lotIn("3");
        long ship = shipped(lot, "3");
        rtnId(rtn(req(), lineOf(ship), lot, "2", qc));
        long trx = accounts.query(comp, "select trx_id from inv_trx where trx_type = 'SHIP' and reversal_of_id is null order by trx_id desc limit 1", Long.class);
        int code = postJson("/api/wms/trx/" + trx + "/reverse", "{\"reason\":\"잘못\"}").andReturn().getResponse().getStatus();
        assertThat(code).isBetween(400, 499);
    }

    /** CR-a 회귀(2026-11-02 회차 2): 자재 입고 원장이 쓴 요청 id 로 반품 → 409, 반품·NCR·LOT 보류·원장 하나도 안 남고 받을 수 있는 양 그대로, 새 id 면 된다 */
    @Test
    void 회귀CRa_입고_원장이_쓴_요청_id_면_409_이고_보류도_원장도_남지_않는다() throws Exception {
        long lot = lotIn("5");
        long ship = shipped(lot, "5");
        String used = accounts.query(comp, "select client_req_id from inv_trx where trx_type = 'RCV_MAT' and lot_id = ? order by trx_id limit 1",
                String.class, lot);
        assertThat(used).isNotNull();
        rtn(used, lineOf(ship), lot, "2", qc).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        assertThat(accounts.query(comp, "select count(*) from wms_cust_rtn", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select count(*) from qms_ncr where src_type = 'WMS_CUST_RTN'", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select hold_yn from lot_master where lot_id = ?", String.class, lot)).isEqualTo("N");
        assertThat(accounts.query(comp, "select count(*) from inv_trx where src_type = 'CUST_RTN'", Integer.class)).isZero();
        assertThat(stock(qc, "BLOCKED")).isEqualByComparingTo("0");
        // 같은 요청을 다시 보내도 같은 409(반 저장 없음), 새 id 면 정상
        rtn(used, lineOf(ship), lot, "2", qc).andExpect(status().isConflict());
        long r = rtnId(rtn(req(), lineOf(ship), lot, "2", qc));
        assertThat(accounts.query(comp, "select count(*) from inv_trx where src_type = 'CUST_RTN' and src_id = ?", Integer.class, r)).isEqualTo(1);
    }

    /** CR-e 회귀: 피킹 이동 짝(MOVE_OUT·MOVE_IN)·SHIP 행은 수불 원장 화면에서 400 SHIP_SOURCE, 피킹 취소·출하 취소는 그대로 되고 일반 이동 역분개도 그대로 */
    @Test
    void 회귀CRe_피킹_이동_짝도_원장_화면_역분개는_400_피킹_취소_출하_취소는_된다() throws Exception {
        long lot = lotIn("6");
        long ship = picked(lot, "3");
        String ids = accounts.query(comp, "select string_agg(trx_id::text, ',' order by trx_id) from inv_trx where src_type = 'SHIP_PICK' "
                + "and trx_type in ('MOVE_OUT', 'MOVE_IN')", String.class);
        List<Long> pair = java.util.Arrays.stream(ids.split(",")).map(Long::valueOf).toList();
        assertThat(pair).hasSize(2);
        for (long t : pair) {
            postJson("/api/wms/trx/" + t + "/reverse", "{\"reason\":\"잘못 피킹\"}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].code").value("SHIP_SOURCE"));
        }
        // 피킹 취소(출하 화면)는 된다 — 재고가 A-01 로 돌아옴
        long pick = accounts.query(comp, "select pick_id from wms_ship_pick where ship_line_id = ?", Long.class, lineOf(ship));
        postJson(S + "/" + ship + "/unpick", "{\"rowVer\":" + shipVer(ship) + ",\"pickIds\":[" + pick + "],\"reason\":\"다시 피킹\"}")
                .andExpect(status().isOk());
        assertThat(stock(stg, "AVAILABLE")).isEqualByComparingTo("0");
        assertThat(stock(a1, "AVAILABLE")).isEqualByComparingTo("6");
        // 다시 피킹·확정 → SHIP 행 400, 출하 취소는 된다, 출하 취소가 만든 역분개 행은 원장 화면에서 REVERSAL_OF_REVERSAL
        postJson(S + "/" + ship + "/pick", "{\"rowVer\":" + shipVer(ship) + ",\"clientReqId\":\"" + req() + "\",\"picks\":[{\"shipLineId\":"
                + lineOf(ship) + ",\"fromNodeId\":" + a1 + ",\"lotNo\":\"" + lotNo(lot) + "\",\"qty\":3}]}").andExpect(status().isOk());
        postJson(S + "/" + ship + "/confirm", "{\"rowVer\":" + shipVer(ship) + ",\"clientReqId\":\"" + req() + "\"}").andExpect(status().isOk());
        long shipTrx = accounts.query(comp, "select trx_id from inv_trx where trx_type = 'SHIP' and reversal_of_id is null", Long.class);
        postJson("/api/wms/trx/" + shipTrx + "/reverse", "{\"reason\":\"잘못 출하\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SHIP_SOURCE"));
        postJson(S + "/" + ship + "/undo", "{\"rowVer\":" + shipVer(ship) + ",\"clientReqId\":\"" + req() + "\",\"reason\":\"출하 취소\"}")
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select status from wms_ship where ship_id = ?", String.class, ship)).isEqualTo("PICKING");
        long back = accounts.query(comp, "select trx_id from inv_trx where trx_type = 'SHIP' and reversal_of_id is not null", Long.class);
        postJson("/api/wms/trx/" + back + "/reverse", "{\"reason\":\"되돌림 되돌림\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("REVERSAL_OF_REVERSAL"));
        // 출하와 무관한 일반 이동은 원장 화면 역분개 그대로
        postJson("/api/wms/move/move", "{\"plantCd\":\"P1\",\"itemId\":" + fgLot + ",\"lotNo\":\"" + lotNo(lot) + "\",\"fromNodeId\":" + a1
                + ",\"fromStatus\":\"AVAILABLE\",\"toNodeId\":" + qc + ",\"toStatus\":\"AVAILABLE\",\"qty\":1,\"clientReqId\":\"" + req() + "\"}")
                .andExpect(status().isOk());
        long mv = accounts.query(comp, "select trx_id from inv_trx where trx_type = 'MOVE_OUT' and src_type is distinct from 'SHIP_PICK' "
                + "and reversal_of_id is null order by trx_id desc limit 1", Long.class);
        postJson("/api/wms/trx/" + mv + "/reverse", "{\"reason\":\"잘못 이동\"}").andExpect(status().isOk());
        assertThat(stock(qc, "AVAILABLE")).isEqualByComparingTo("0");
    }

    /** NCR 처분·종결 흉내(소유자, 트리거 끔) — CustRtnTest 와 같은 방식 */
    private void closeNcr(long ncrId, String disp, String qty) throws Exception {
        try (Connection con = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = con.createStatement()) {
            st.execute("set session_replication_role = replica");
            st.execute("update qms_ncr set status = 'CLOSED', defect_id = 0, closed_at = now(), closed_by = 'qa1' where ncr_id = " + ncrId);
            st.execute("insert into qms_ncr_disp (comp_cd, ncr_id, line_no, disp_type, qty, created_by) values ('" + comp + "', " + ncrId + ", 1, '"
                    + disp + "', " + qty + ", 'qa1')");
        }
    }

    /** CR-c(부록 A-3, 고침 2026-11-02): 같은 LOT 반품 둘(QC 칸·A 칸) — 반품 1(QC 칸)의 NCR 을 SCRAP 처분하면 반품 1 의 물건(QC 칸)이 폐기돼야 한다 */
    @Test
    void 결함CRc_클레임_NCR_처분은_그_반품이_받은_칸의_재고부터() throws Exception {
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) values (?, 'P1', 'WMS_QUALITY_LINK', 'Y', 't', 't')", comp);
        long lot = lotIn("4");
        long ship = shipped(lot, "4");
        long r1 = rtnId(rtn(req(), lineOf(ship), lot, "2", qc));
        rtnId(rtn(req(), lineOf(ship), lot, "2", a1));
        long ncr1 = ncrOf(r1);
        closeNcr(ncr1, "SCRAP", "2");
        runner.callAs(comp, "qa1", () -> {
            link.onNcrClosed(ncr1);
            return null;
        });
        assertThat(stock(qc, "BLOCKED")).as("반품 1 이 받은 QC 칸").isEqualByComparingTo("0");
        assertThat(stock(a1, "BLOCKED")).as("반품 2 가 받은 A 칸 — 아직 판정 전").isEqualByComparingTo("2");
    }

    /** NCR 취소는 그 NCR 이 잡은 보류만 푼다 — 같은 LOT 의 다른 반품 NCR 이 열려 있으면 LOT 은 계속 보류 */
    @Test
    void 같은_LOT_반품_둘_중_하나를_취소해도_다른_반품의_LOT_보류는_남는다() throws Exception {
        long lot = lotIn("6");
        long ship = shipped(lot, "6");
        long r1 = rtnId(rtn(req(), lineOf(ship), lot, "2", qc));
        long r2 = rtnId(rtn(req(), lineOf(ship), lot, "1", qc));
        assertThat(accounts.query(comp, "select hold_yn from lot_master where lot_id = ?", String.class, lot)).isEqualTo("Y");
        cancel(r1).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select hold_yn from lot_master where lot_id = ?", String.class, lot)).isEqualTo("Y");
        assertThat(stock(qc, "BLOCKED")).isEqualByComparingTo("1");
        cancel(r2).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select hold_yn from lot_master where lot_id = ?", String.class, lot)).isEqualTo("N");
        assertThat(stock(qc, "BLOCKED")).isEqualByComparingTo("0");
    }

    /** 같은 요청 id 가 서로 다른 출하 행으로 동시에 — 출하 행 잠금이 달라 둘 다 요청 확인을 지나도 하나만 저장, 나머지는 409(500 아님) */
    @Test
    void 같은_요청_id_다른_출하_행_동시_두_건은_하나만_저장되고_나머지는_409() throws Exception {
        long lot = lotIn("6");
        long s1 = shipped(lot, "3");
        long s2 = shipped(lot, "3");
        String same = req();
        List<Integer> codes = race(call(() -> rtn(same, lineOf(s1), lot, "1", qc)), call(() -> rtn(same, lineOf(s2), lot, "1", qc)));
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(accounts.query(comp, "select count(*) from wms_cust_rtn", Integer.class)).isEqualTo(1);
        assertThat(accounts.query(comp, "select count(*) from inv_trx where src_type = 'CUST_RTN'", Integer.class)).isEqualTo(1);
        assertThat(accounts.query(comp, "select count(*) from qms_ncr where src_type = 'WMS_CUST_RTN'", Integer.class)).isEqualTo(1);
        assertThat(stock(qc, "BLOCKED")).isEqualByComparingTo("1");
    }

    /** 출하 취소와 반품 입고가 동시에 — 둘 다 성공해 "취소된 출하에 살아 있는 반품" 이 남지 않는다(3회) */
    @Test
    void 출하_취소와_반품_입고가_동시면_둘_다_성공하지_않는다() throws Exception {
        for (int round = 0; round < 3; round++) {
            long lot = lotIn("2");
            long ship = shipped(lot, "2");
            long line = lineOf(ship);
            int ver = shipVer(ship);
            String undoReq = req();
            String rtnReq = req();
            List<Integer> codes = race(
                    call(() -> postJson(S + "/" + ship + "/undo", "{\"rowVer\":" + ver + ",\"clientReqId\":\"" + undoReq + "\",\"reason\":\"취소\"}")),
                    call(() -> rtn(rtnReq, line, lot, "1", qc)));
            assertThat(codes).as("round %d [undo, rtn]", round).doesNotContainSequence(200, 200).hasSize(2).contains(200);
            String shipSt = accounts.query(comp, "select status from wms_ship where ship_id = ?", String.class, ship);
            int live = accounts.query(comp, "select count(*) from wms_cust_rtn where ship_id = ? and status = 'RECEIVED'", Integer.class, ship);
            assertThat(shipSt.equals("SHIPPED") || live == 0).as("round %d ship=%s live=%d", round, shipSt, live).isTrue();
        }
    }

    private ResultActions moveBlocked(long lot, long from, long to, String qty) throws Exception {
        return postJson("/api/wms/move/move", "{\"plantCd\":\"P1\",\"itemId\":" + fgLot + ",\"lotNo\":\"" + lotNo(lot) + "\",\"fromNodeId\":" + from
                + ",\"fromStatus\":\"BLOCKED\",\"toNodeId\":" + to + ",\"toStatus\":\"BLOCKED\",\"qty\":" + qty + ",\"clientReqId\":\"" + req() + "\"}");
    }

    /**
     * 반품 재고를 다른 칸으로 옮긴 뒤 반품 취소 — 보류(정책 기본 Y)면 이동부터 LOT_HOLD 로 막히고,
     * 보류를 끈 회사(QMS_NCR_AUTO_HOLD=N)는 이동이 되지만 그 뒤 반품 취소는 통째로 롤백(반품 RECEIVED·NCR OPEN·원장 그대로)
     */
    @Test
    void 반품_재고를_옮긴_뒤_반품_취소는_통째로_막힌다() throws Exception {
        long lot = lotIn("3");
        long ship = shipped(lot, "3");
        long r = rtnId(rtn(req(), lineOf(ship), lot, "2", qc));
        moveBlocked(lot, qc, a1, "2").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("LOT_HOLD"));

        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'QMS_NCR_AUTO_HOLD', 'N', 't', 't')", comp);
        long lot2 = lotIn("3");
        long ship2 = shipped(lot2, "3");
        long r2 = rtnId(rtn(req(), lineOf(ship2), lot2, "2", qc));
        assertThat(accounts.query(comp, "select hold_yn from lot_master where lot_id = ?", String.class, lot2)).isEqualTo("N");
        moveBlocked(lot2, qc, a1, "2").andExpect(status().isOk());
        int trx = accounts.query(comp, "select count(*) from inv_trx", Integer.class);
        cancel(r2).andExpect(status().is4xxClientError());
        assertThat(accounts.query(comp, "select status from wms_cust_rtn where rtn_id = ?", String.class, r2)).isEqualTo("RECEIVED");
        assertThat(accounts.query(comp, "select status from qms_ncr where ncr_id = ?", String.class, ncrOf(r2))).isEqualTo("OPEN");
        assertThat(accounts.query(comp, "select count(*) from inv_trx", Integer.class)).isEqualTo(trx);
        assertThat(accounts.query(comp, "select status from wms_cust_rtn where rtn_id = ?", String.class, r)).isEqualTo("RECEIVED");
    }

    /** CR-b(QA 부록 A-2, 결정 2026-11-02 — docs/29_P3 43장): 출처가 있는 NCR 은 QMS 화면·API 에서 취소 못 함, 취소는 반품 화면으로만 */
    @Test
    void 결함CRb_QMS_화면이_클레임_NCR_을_직접_취소하지_못한다() throws Exception {
        long lot = lotIn("3");
        long ship = shipped(lot, "3");
        long r = rtnId(rtn(req(), lineOf(ship), lot, "2", qc));
        long ncr = ncrOf(r);
        int ver = accounts.query(comp, "select row_ver from qms_ncr where ncr_id = ?", Integer.class, ncr);
        MvcResult m = postJson("/api/qms/ncrs/" + ncr + "/cancel", "{\"rowVer\":" + ver + ",\"reason\":\"중복 등록\"}").andReturn();
        int code = m.getResponse().getStatus();
        String ncrSt = accounts.query(comp, "select status from qms_ncr where ncr_id = ?", String.class, ncr);
        assertThat(code).isEqualTo(409);
        assertThat(ncrSt).isEqualTo("OPEN");
        assertThat(m.getResponse().getContentAsString()).contains("QMS_NCR_SOURCE_OWNED");
        // 출처 쪽(반품 화면) 취소는 그대로 된다 — 반품 CANCELLED·NCR CANCELLED
        cancel(r).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select status from qms_ncr where ncr_id = ?", String.class, ncr)).isEqualTo("CANCELLED");
        assertThat(accounts.query(comp, "select status from wms_cust_rtn where rtn_id = ?", String.class, r)).isEqualTo("CANCELLED");
    }

    // =====================================================================
    // 회차 5 — ERP 반품 송신(#791, docs/59 R-4)
    // =====================================================================

    private static final String ERP_Q = "select string_agg(biz_key || ':' || (payload->>'qty'), ',' order by biz_key) from sys_outbox where topic = 'ERP_CUST_RTN'";

    private void erpOn() {
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'ERP_SYSTEM', 'FAKE', 't', 't')", comp);
    }

    /** 같은 반품 취소 두 번이 동시에 — 하나만 취소(200·409), 음수 전표도 하나 */
    @Test
    void ERP_반품_취소_동시_두_번이면_음수_전표도_하나() throws Exception {
        erpOn();
        long lot = lotIn("4");
        long ship = shipped(lot, "4");
        long r = rtnId(rtn(req(), lineOf(ship), lot, "2", qc));
        int ver = accounts.query(comp, "select row_ver from wms_cust_rtn where rtn_id = ?", Integer.class, r);
        String body = "{\"rowVer\":" + ver + ",\"reason\":\"잘못 받음\"}";
        List<Integer> codes = race(call(() -> postJson(R + "/" + r + "/cancel", body)), call(() -> postJson(R + "/" + r + "/cancel", body)));
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        String no = accounts.query(comp, "select rtn_no from wms_cust_rtn where rtn_id = ?", String.class, r);
        assertThat(accounts.query(comp, ERP_Q, String.class)).isEqualTo(no + "-C:-2," + no + "-R:2");
    }
}
