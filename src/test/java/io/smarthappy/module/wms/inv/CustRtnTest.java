package io.smarthappy.module.wms.inv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

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
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 고객 반품 입고 (docs/59, 사용자 결정 R-1~R-8) — 출하 근거(출하 행 × LOT) → 반품 입고(RCV_RTN·BLOCKED·같은 LOT) + QMS 클레임 NCR(R-2)
 * → NCR 처분(USE_AS_IS)이 가용으로. 받을 수 있는 양·멱등·동시, 취소(NCR OPEN 일 때만 — R-6), 원장 화면 역분개 막음, 출하 취소 막음,
 * 영업 출하 수량 그대로(R-3), LOT 비관리 품목(NCR 없음), 거부, 직접 SQL, A사로 B사.
 */
@AutoConfigureMockMvc
class CustRtnTest extends PostgresTestBase {

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
    long scrap;
    long fg;
    long fgLot;
    final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("CR");
        admin = user(comp, "admin1", "SEARCH", "SAVE", "APPROVE");
        seed(comp);
        long wh = node(comp, null, "WH", "FG-WH");
        a1 = node(comp, wh, "LOC", "A-01");
        qc = node(comp, wh, "LOC", "QC-01");
        stg = node(comp, wh, "LOC", "STG-01");
        long sw = node(comp, null, "WH", "SCRAP");
        scrap = node(comp, sw, "LOC", "S-01");
        attr(comp, qc, "QC");
        attr(comp, stg, "STAGING");
        attr(comp, sw, "SCRAP");
        fg = item(comp, "FG-100");
        fgLot = item(comp, "FG-L");
        accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', 30, 'EA', 'MANUAL', now(), 'KEY', 't')""", comp, fg, a1);
    }

    // ---------- 도우미 ----------

    private RequestPostProcessor user(String c, String id, String... rtnActions) {
        long u = accounts.newUser(c, id, PW);
        String grp = "G_" + id.toUpperCase(java.util.Locale.ROOT);
        accounts.grant(c, u, grp, "sal-order", "SEARCH", "SAVE");
        accounts.grant(c, u, grp, "wms-ship", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, u, grp, "wms-rcv", "SEARCH", "SAVE");
        accounts.grant(c, u, grp, "wms-trx", "SEARCH", "SAVE");
        if (rtnActions.length > 0) {
            accounts.grant(c, u, grp, "wms-cust-rtn", rtnActions);
        }
        return accounts.as(c, u, id);
    }

    private void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.addPlant(c, "P2", "2공장");
        accounts.exec(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C-001', 'C-001 상사', 'SALES', 't', 't'), (?, 'SUP1', '공급사', 'PURCHASE', 't', 't')""", c, c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'FG-100', 'FG 품목', 'FG', 'MAKE', 'EA', 'N', 't', 't'), (?, 'FG-L', 'FG LOT 품목', 'FG', 'MAKE', 'EA', 'Y', 't', 't')""", c, c);
    }

    private long node(String c, Long parent, String type, String cd) {
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, c, parent, type, cd, cd);
    }

    private void attr(String c, long node, String usage) {
        accounts.exec(c, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, ?, 't', 't')", node, c, usage);
    }

    private long item(String c, String cd) {
        return accounts.query(c, "select item_id from mst_item where item_cd = ?", Long.class, cd);
    }

    private ResultActions postJson(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /**
     * 요청 id — 동시 시험에서 여러 스레드가 부른다. 예전 {@code ++seq + nanoTime} 은 두 스레드가 같은 값을 받을 수 있었다
     * (++ 비원자 + Windows nanoTime 해상도 ~100ns) → 두 번째 요청이 "같은 요청 다시 = 처음 결과" 200 이 되어 넘침 시험이
     * [200, 200] 으로 흔들렸다(2026-11-03 전체 check 1회). 반품은 실제로 한 건 — 제품 결함 아님(docs/testing/wms.md)
     */
    private String req() {
        return "crt-" + seq.incrementAndGet() + "-" + System.nanoTime();
    }

    /** 자재 입고로 LOT 하나 → lot_id */
    private long lotIn(String qty) throws Exception {
        MvcResult m = postJson(admin, "/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + fgLot + ",\"qty\":" + qty
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

    /** 수주 → 출하 지시 → 피킹(A-01, LOT 또는 null) → 확정. 돌려줌 = 출하 id */
    private long shipped(long itemId, String itemCd, Long lot, String qty) throws Exception {
        MvcResult o = postJson(admin, "/api/sales/orders", "{\"custCd\":\"C-001\",\"lines\":[{\"itemCd\":\"" + itemCd + "\",\"qty\":\"" + qty
                + "\",\"dueDate\":\"2026-11-30\"}]}").andExpect(status().isOk()).andReturn();
        long order = ((Number) JsonPath.read(o.getResponse().getContentAsString(), "$.data")).longValue();
        long ol = accounts.query(comp, "select line_id from sal_order_line where order_id = ? and line_no = 10", Long.class, order);
        MvcResult s = postJson(admin, S, "{\"plantCd\":\"P1\",\"custCd\":\"C-001\",\"stageNodeId\":" + stg + ",\"planDate\":\"2026-11-20\",\"lines\":[{"
                + "\"orderLineId\":" + ol + ",\"qty\":" + qty + "}]}").andExpect(status().isOk()).andReturn();
        long id = ((Number) JsonPath.read(s.getResponse().getContentAsString(), "$.data.head.shipId")).longValue();
        long line = accounts.query(comp, "select ship_line_id from wms_ship_line where ship_id = ?", Long.class, id);
        postJson(admin, S + "/" + id + "/pick", "{\"rowVer\":" + shipVer(id) + ",\"clientReqId\":\"" + req() + "\",\"picks\":[{\"shipLineId\":" + line
                + ",\"fromNodeId\":" + a1 + ",\"lotNo\":" + (lot == null ? "null" : "\"" + lotNo(lot) + "\"") + ",\"qty\":" + qty + "}]}")
                .andExpect(status().isOk());
        postJson(admin, S + "/" + id + "/confirm", "{\"rowVer\":" + shipVer(id) + ",\"clientReqId\":\"" + req() + "\"}").andExpect(status().isOk());
        return id;
    }

    private long lineOf(long shipId) {
        return accounts.query(comp, "select ship_line_id from wms_ship_line where ship_id = ?", Long.class, shipId);
    }

    private ResultActions rtn(RequestPostProcessor who, String reqId, long shipLine, Long lot, String qty, long node) throws Exception {
        return postJson(who, R, "{\"plantCd\":\"P1\",\"shipLineId\":" + shipLine + ",\"lotId\":" + lot + ",\"qty\":" + qty
                + ",\"reasonCd\":\"QUALITY\",\"nodeId\":" + node + ",\"capaNo\":\"CAPA-1\",\"remark\":\"찍힘\",\"clientReqId\":\"" + reqId + "\"}");
    }

    private long rtnId(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.rtnId")).longValue();
    }

    private BigDecimal stock(long node, long item, String st) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and item_id = ? and stock_status = ?",
                BigDecimal.class, node, item, st).stripTrailingZeros();
    }

    private int rtnVer(long id) {
        return accounts.query(comp, "select row_ver from wms_cust_rtn where rtn_id = ?", Integer.class, id);
    }

    private ResultActions cancel(RequestPostProcessor who, long id) throws Exception {
        return postJson(who, R + "/" + id + "/cancel", "{\"rowVer\":" + rtnVer(id) + ",\"reason\":\"잘못 받음\"}");
    }

    /** NCR 처분·종결을 흉내(소유자, 트리거 끔) — QMS 화면 흐름 대신 상태·처분 행만 */
    private void closeNcr(long ncrId, String disp, String qty) throws Exception {
        try (Connection con = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = con.createStatement()) {
            st.execute("set session_replication_role = replica");
            st.execute("update qms_ncr set status = 'CLOSED', defect_id = 0, closed_at = now(), closed_by = 'qa1' where ncr_id = " + ncrId);
            st.execute("insert into qms_ncr_disp (comp_cd, ncr_id, line_no, disp_type, qty, created_by) values ('" + comp + "', " + ncrId + ", 1, '"
                    + disp + "', " + qty + ", 'qa1')");
        }
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void 출하_근거로_반품을_받으면_같은_LOT_BLOCKED_원장과_클레임_NCR_이_생기고_받을_수_있는_양이_준다() throws Exception {
        long lot = lotIn("8");
        long ship = shipped(fgLot, "FG-L", lot, "6");
        String shipNo = accounts.query(comp, "select ship_no from wms_ship where ship_id = ?", String.class, ship);
        mvc.perform(get(R + "/sources").param("plantCd", "P1").param("shipNo", shipNo).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].openQty").value(6))
                .andExpect(jsonPath("$.data[0].lotNo").value(lotNo(lot)));
        // 받는 칸 후보: QC 칸 먼저, 출하 대기·폐기 칸 없음
        mvc.perform(get(R + "/nodes").param("plantCd", "P1").with(admin)).andExpect(jsonPath("$.data[0].nodeId").value(qc))
                .andExpect(jsonPath("$.data[?(@.nodeId == " + stg + ")]").isEmpty())
                .andExpect(jsonPath("$.data[?(@.nodeId == " + scrap + ")]").isEmpty());
        long id = rtnId(rtn(admin, req(), lineOf(ship), lot, "2", qc));
        assertThat(accounts.query(comp, """
                select r.status || '/' || trim_scale(r.qty) || '/' || r.reason_cd || '/' || r.capa_no || '/' || coalesce(r.ncr_no, '-')
                  from wms_cust_rtn r where r.rtn_id = ?""", String.class, id)).startsWith("RECEIVED/2/QUALITY/CAPA-1/");
        assertThat(accounts.query(comp, "select trx_type || '/' || stock_status || '/' || trim_scale(qty) || '/' || lot_id from inv_trx where src_type = 'CUST_RTN' and src_id = ?",
                String.class, id)).isEqualTo("RCV_RTN/BLOCKED/2/" + lot);
        assertThat(stock(qc, fgLot, "BLOCKED")).isEqualByComparingTo("2");
        // 클레임 NCR(R-2): 출처 CLAIM·근거 WMS_CUST_RTN·수량·LOT, 불량 유형 비움, 정책 기본 Y → LOT 보류
        assertThat(accounts.query(comp, """
                select n.source || '/' || n.src_type || '/' || n.src_id || '/' || trim_scale(n.nc_qty) || '/' || n.lot_id || '/' || n.status
                       || '/' || coalesce(n.defect_id::text, '-')
                  from qms_ncr n where n.src_id = ?""", String.class, id)).isEqualTo("CLAIM/WMS_CUST_RTN/" + id + "/2/" + lot + "/OPEN/-");
        assertThat(accounts.query(comp, "select hold_yn from lot_master where lot_id = ?", String.class, lot)).isEqualTo("Y");
        mvc.perform(get(R + "/" + id).with(admin)).andExpect(jsonPath("$.data.ncrStatus").value("OPEN"))
                .andExpect(jsonPath("$.data.lotNo").value(lotNo(lot)));
        // 받을 수 있는 양 6 → 4, LOT 스캔(LOT:)으로도 찾음
        mvc.perform(get(R + "/sources").param("plantCd", "P1").param("lotNo", "LOT:" + lotNo(lot)).with(admin))
                .andExpect(jsonPath("$.data[0].openQty").value(4)).andExpect(jsonPath("$.data[0].returnedQty").value(2));
        // 영업 출하 수량은 그대로(R-3)
        assertThat(accounts.query(comp, "select trim_scale(shipped_qty)::text from sal_order_line where line_id = (select order_line_id from wms_ship_line where ship_id = ?)",
                String.class, ship)).isEqualTo("6");
        // 목록
        mvc.perform(get(R).param("plantCd", "P1").with(admin)).andExpect(jsonPath("$.list.length()").value(1))
                .andExpect(jsonPath("$.list[0].ncrStatus").value("OPEN"));
    }

    @Test
    void NCR_처분_USE_AS_IS_면_반품_재고가_가용이_되고_그_뒤_반품_취소는_409() throws Exception {
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) values (?, 'P1', 'WMS_QUALITY_LINK', 'Y', 't', 't')", comp);
        long lot = lotIn("5");
        long ship = shipped(fgLot, "FG-L", lot, "5");
        long id = rtnId(rtn(admin, req(), lineOf(ship), lot, "3", qc));
        long ncr = accounts.query(comp, "select ncr_id from wms_cust_rtn where rtn_id = ?", Long.class, id);
        closeNcr(ncr, "USE_AS_IS", "3");
        runner.callAs(comp, "qa1", () -> {
            link.onNcrClosed(ncr);
            return null;
        });
        assertThat(stock(qc, fgLot, "BLOCKED")).isEqualByComparingTo("0");
        assertThat(stock(qc, fgLot, "AVAILABLE")).isEqualByComparingTo("3");
        cancel(admin, id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_NCR_STATE"));
        assertThat(accounts.query(comp, "select status from wms_cust_rtn where rtn_id = ?", String.class, id)).isEqualTo("RECEIVED");
    }

    @Test
    void 취소는_NCR_과_함께_되돌리고_원장_화면_역분개는_막고_LOT_비관리_품목은_NCR_없이() throws Exception {
        long lot = lotIn("6");
        long ship = shipped(fgLot, "FG-L", lot, "6");
        long id = rtnId(rtn(admin, req(), lineOf(ship), lot, "4", qc));
        long trx = accounts.query(comp, "select trx_id from inv_trx where src_type = 'CUST_RTN' and src_id = ?", Long.class, id);
        // 수불 원장 화면 역분개 → 400(반품 화면으로만)
        postJson(admin, "/api/wms/trx/" + trx + "/reverse", "{\"reason\":\"잘못\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("CUST_RTN_SOURCE"));
        // 취소: SAVE 만 있으면 403, APPROVE 면 취소 — 원장 역분개·NCR CANCELLED·보류 해제·받을 수 있는 양 복구
        cancel(user(comp, "clerk1", "SEARCH", "SAVE"), id).andExpect(status().isForbidden());
        cancel(admin, id).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        assertThat(stock(qc, fgLot, "BLOCKED")).isEqualByComparingTo("0");
        assertThat(accounts.query(comp, "select status from qms_ncr where src_id = ? and src_type = 'WMS_CUST_RTN'", String.class, id)).isEqualTo("CANCELLED");
        assertThat(accounts.query(comp, "select hold_yn from lot_master where lot_id = ?", String.class, lot)).isEqualTo("N");
        mvc.perform(get(R + "/sources").param("plantCd", "P1").param("lotNo", lotNo(lot)).with(admin)).andExpect(jsonPath("$.data[0].openQty").value(6));
        cancel(admin, id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_CUST_RTN_STATE"));
        // LOT 비관리 품목: NCR 없음, BLOCKED, lot 없음, 취소 자유
        long ship2 = shipped(fg, "FG-100", null, "10");
        long id2 = rtnId(rtn(admin, req(), lineOf(ship2), null, "1", a1));
        assertThat(accounts.query(comp, "select coalesce(ncr_id::text, '-') || '/' || coalesce(lot_id::text, '-') from wms_cust_rtn where rtn_id = ?",
                String.class, id2)).isEqualTo("-/-");
        assertThat(stock(a1, fg, "BLOCKED")).isEqualByComparingTo("1");
        cancel(admin, id2).andExpect(status().isOk());
        assertThat(stock(a1, fg, "BLOCKED")).isEqualByComparingTo("0");
    }

    /** QA CR-a(docs/qa/2026-11-02_cust-rtn.md 부록 A-1): 다른 원장(출하 확정 SHIP)이 쓴 요청 id 로 반품 — 409, 반품·NCR·LOT 보류 모두 롤백 */
    @Test
    void 결함CRa_다른_원장이_쓴_요청_id_로_반품하면_409_이고_반품도_NCR_도_남지_않는다() throws Exception {
        long lot = lotIn("4");
        long ship = shipped(fgLot, "FG-L", lot, "4");
        String used = accounts.query(comp, "select client_req_id from inv_trx where trx_type = 'SHIP' order by trx_id desc limit 1", String.class);
        rtn(admin, used, lineOf(ship), lot, "2", qc).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        assertThat(accounts.query(comp, "select count(*) from wms_cust_rtn", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select count(*) from qms_ncr where src_type = 'WMS_CUST_RTN'", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select hold_yn from lot_master where lot_id = ?", String.class, lot)).isEqualTo("N");
        // 같은 반품을 같은 요청 id 로 다시 보내는 것은 그대로 처음 결과(200)
        String mine = req();
        long id = rtnId(rtn(admin, mine, lineOf(ship), lot, "2", qc));
        assertThat(rtnId(rtn(admin, mine, lineOf(ship), lot, "2", qc))).isEqualTo(id);
        assertThat(stock(qc, fgLot, "BLOCKED")).isEqualByComparingTo("2");
    }

    /**
     * QA CR-e(부록 A-4 + 피킹 짝): 출하 SHIP 행·피킹 이동 짝(출처 SHIP_PICK)은 수불 원장 화면에서 되돌리지 못한다(400 SHIP_SOURCE) —
     * 출하·피킹·영업 출하 수량과 함께 되돌리는 출하 취소·피킹 취소로만. 반품이 없어도 같다
     */
    @Test
    void 결함CRe_출하_SHIP_행과_피킹_이동_짝은_원장_화면에서_되돌리지_못한다() throws Exception {
        long lot = lotIn("5");
        long ship = shipped(fgLot, "FG-L", lot, "3");
        rtnId(rtn(admin, req(), lineOf(ship), lot, "2", qc));
        long shipTrx = accounts.query(comp, "select trx_id from inv_trx where trx_type = 'SHIP' and reversal_of_id is null order by trx_id desc limit 1", Long.class);
        postJson(admin, "/api/wms/trx/" + shipTrx + "/reverse", "{\"reason\":\"잘못\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SHIP_SOURCE"));
        for (String type : List.of("MOVE_OUT", "MOVE_IN")) {
            long mv = accounts.query(comp, "select trx_id from inv_trx where src_type = 'SHIP_PICK' and trx_type = ? order by trx_id desc limit 1",
                    Long.class, type);
            postJson(admin, "/api/wms/trx/" + mv + "/reverse", "{\"reason\":\"잘못\"}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].code").value("SHIP_SOURCE"));
        }
        assertThat(accounts.query(comp, "select count(*) from inv_trx where reversal_of_id is not null", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select coalesce(sum(qty), 0) from inv_trx where trx_type = 'SHIP'", BigDecimal.class)).isEqualByComparingTo("-3");
    }

    /**
     * QA CR-c(2026-11-02 사용자 결정): 클레임 NCR 처분은 그 반품이 받은 칸의 BLOCKED 부터. 같은 LOT 반품 둘 —
     * 반품 1(불량, 작은 칸 A)·반품 2(양품, 큰 칸 QC). 반품 2 NCR 을 USE_AS_IS 로 닫아도 반품 1 의 불량품(A)이 가용이 되면 안 된다
     */
    @Test
    void 결함CRc_반대_조합_양품_특채가_다른_반품의_불량품을_가용으로_만들지_않는다() throws Exception {
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) values (?, 'P1', 'WMS_QUALITY_LINK', 'Y', 't', 't')", comp);
        long lot = lotIn("4");
        long ship = shipped(fgLot, "FG-L", lot, "4");
        long bad = rtnId(rtn(admin, req(), lineOf(ship), lot, "2", a1));
        long good = rtnId(rtn(admin, req(), lineOf(ship), lot, "2", qc));
        long ncrGood = accounts.query(comp, "select ncr_id from wms_cust_rtn where rtn_id = ?", Long.class, good);
        closeNcr(ncrGood, "USE_AS_IS", "2");
        runner.callAs(comp, "qa1", () -> {
            link.onNcrClosed(ncrGood);
            return null;
        });
        assertThat(stock(qc, fgLot, "AVAILABLE")).as("반품 2(양품)가 받은 QC 칸이 가용").isEqualByComparingTo("2");
        assertThat(stock(a1, fgLot, "BLOCKED")).as("반품 1(불량)의 A 칸은 그대로 BLOCKED").isEqualByComparingTo("2");
        assertThat(stock(a1, fgLot, "AVAILABLE")).isEqualByComparingTo("0");
        long ncrBad = accounts.query(comp, "select ncr_id from wms_cust_rtn where rtn_id = ?", Long.class, bad);
        closeNcr(ncrBad, "SCRAP", "2");
        runner.callAs(comp, "qa1", () -> {
            link.onNcrClosed(ncrBad);
            return null;
        });
        assertThat(stock(a1, fgLot, "BLOCKED")).as("반품 1 의 불량품이 폐기").isEqualByComparingTo("0");
        assertThat(stock(qc, fgLot, "AVAILABLE")).isEqualByComparingTo("2");
    }

    /** QA CR-d(2026-11-02 사용자 결정): 같은 요청 id 로 다시 오면 받는 칸·사유까지 비교 — 다르면 409(PQ-1 규칙) */
    @Test
    void 결함CRd_같은_요청_id_로_칸이나_사유만_바꾸면_409() throws Exception {
        long lot = lotIn("4");
        long ship = shipped(fgLot, "FG-L", lot, "4");
        long line = lineOf(ship);
        String r1 = req();
        long id = rtnId(rtn(admin, r1, line, lot, "1", qc));
        rtn(admin, r1, line, lot, "1", a1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        postJson(admin, R, "{\"plantCd\":\"P1\",\"shipLineId\":" + line + ",\"lotId\":" + lot + ",\"qty\":1,\"reasonCd\":\"WRONG_SHIP\",\"nodeId\":"
                + qc + ",\"clientReqId\":\"" + r1 + "\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        assertThat(rtnId(rtn(admin, r1, line, lot, "1", qc))).isEqualTo(id);                         // 같은 내용 = 처음 결과
        assertThat(accounts.query(comp, "select count(*) from inv_trx where src_type = 'CUST_RTN'", Integer.class)).isEqualTo(1);
        assertThat(stock(a1, fgLot, "BLOCKED")).isEqualByComparingTo("0");
    }

    /** QMS 8D 조회용 색인(V9324) — QMS 쿼리 식 btrim(capa_no)·ncr_id 와 같은 정의인지 */
    @Test
    void QMS_8D_조회용_색인이_있다() {
        assertThat(accounts.query(comp, "select indexdef from pg_indexes where indexname = 'ix_wms_cust_rtn_capa'", String.class))
                .contains("btrim((capa_no)::text)").contains("WHERE (capa_no IS NOT NULL)");
        assertThat(accounts.query(comp, "select indexdef from pg_indexes where indexname = 'ix_wms_cust_rtn_ncr'", String.class))
                .contains("(comp_cd, ncr_id)").contains("WHERE (ncr_id IS NOT NULL)");
    }

    /** ERP 반품 송신(docs/59 R-4): 정책 ERP_SYSTEM 어댑터가 있으면 입고 = 양수 전표, 취소 = 음수 전표. 같은 요청 다시는 발행 안 함. 어댑터 없으면 0 */
    @Test
    void ERP_반품_전표는_입고에_양수_취소에_음수_같은_요청은_한_번() throws Exception {
        String q = "select count(*) from sys_outbox where topic = 'ERP_CUST_RTN'";
        long lot = lotIn("6");
        long ship = shipped(fgLot, "FG-L", lot, "6");
        // 어댑터 없음(정책 없음) — 발행 안 함
        long none = rtnId(rtn(admin, req(), lineOf(ship), lot, "1", qc));
        assertThat(accounts.query(comp, q, Integer.class)).isZero();
        cancel(admin, none).andExpect(status().isOk());
        assertThat(accounts.query(comp, q, Integer.class)).isZero();
        // 어댑터 FAKE
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'ERP_SYSTEM', 'FAKE', 't', 't')", comp);
        String r1 = req();
        long id = rtnId(rtn(admin, r1, lineOf(ship), lot, "2", qc));
        assertThat(rtnId(rtn(admin, r1, lineOf(ship), lot, "2", qc))).isEqualTo(id);                 // 같은 요청 다시 — 처음 결과, 발행 안 함
        String no = accounts.query(comp, "select rtn_no from wms_cust_rtn where rtn_id = ?", String.class, id);
        assertThat(accounts.query(comp, q, Integer.class)).isEqualTo(1);
        assertThat(accounts.query(comp, "select biz_key || '|' || (payload->>'qty') || '|' || (payload->>'rtnNo') || '|' || (payload->>'lotNo') || '|' || "
                + "(payload->>'reasonCd') || '|' || (payload->>'ncrNo' is not null) from sys_outbox where topic = 'ERP_CUST_RTN'", String.class))
                .isEqualTo(no + "-R|2|" + no + "|" + lotNo(lot) + "|QUALITY|true");
        cancel(admin, id).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select string_agg(biz_key || ':' || (payload->>'qty'), ',' order by biz_key) from sys_outbox where topic = 'ERP_CUST_RTN'",
                String.class)).isEqualTo(no + "-C:-2," + no + "-R:2");
        // 409 로 롤백된 반품(남의 원장 요청 id — CR-a)은 전표도 남지 않는다
        String used = accounts.query(comp, "select client_req_id from inv_trx where trx_type = 'SHIP' order by trx_id desc limit 1", String.class);
        rtn(admin, used, lineOf(ship), lot, "1", qc).andExpect(status().isConflict());
        assertThat(accounts.query(comp, q, Integer.class)).isEqualTo(2);
        // A사로 B사: B사 범위에서는 A사 전표가 보이지 않는다
        String other = accounts.newComp("CB");
        assertThat(accounts.query(other, q, Integer.class)).isZero();
    }

    /** W-12 적치 검사: 반품 받는 칸도 같은 판정 — WARN(기본) = 받고 경고, BLOCK = 409(반품·NCR·원장 없음) */
    @Test
    void 반품_받는_칸도_적치_검사_WARN_은_경고_BLOCK_은_409() throws Exception {
        long cls = accounts.query(comp, "insert into mst_item_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PKG', '포장재', 't', 't') returning class_id",
                Long.class, comp);
        accounts.exec(comp, "insert into wms_loc_class_allow (comp_cd, node_id, item_class_id, created_by, updated_by) values (?, ?, ?, 't', 't')", comp, qc, cls);
        long lot = lotIn("4");
        long ship = shipped(fgLot, "FG-L", lot, "4");
        rtn(admin, req(), lineOf(ship), lot, "1", qc).andExpect(status().isOk()).andExpect(jsonPath("$.data.warnings.length()").value(1));
        rtn(admin, req(), lineOf(ship), lot, "1", a1).andExpect(status().isOk()).andExpect(jsonPath("$.data.warnings.length()").value(0));   // 목록 없는 칸
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) values (?, 'P1', 'WMS_PUTAWAY_CHECK', 'BLOCK', 't', 't')", comp);
        int before = accounts.query(comp, "select count(*) from wms_cust_rtn", Integer.class);
        rtn(admin, req(), lineOf(ship), lot, "1", qc).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_PUTAWAY_DENIED"));
        assertThat(accounts.query(comp, "select count(*) from wms_cust_rtn", Integer.class)).isEqualTo(before);
        assertThat(accounts.query(comp, "select count(*) from qms_ncr where src_type = 'WMS_CUST_RTN'", Integer.class)).isEqualTo(before);
    }

    @Test
    void 반품이_걸린_출하는_출하_취소_409_반품_취소_뒤엔_된다() throws Exception {
        long lot = lotIn("4");
        long ship = shipped(fgLot, "FG-L", lot, "4");
        long id = rtnId(rtn(admin, req(), lineOf(ship), lot, "1", qc));
        String rtnNo = accounts.query(comp, "select rtn_no from wms_cust_rtn where rtn_id = ?", String.class, id);
        postJson(admin, S + "/" + ship + "/undo", "{\"rowVer\":" + shipVer(ship) + ",\"clientReqId\":\"" + req() + "\",\"reason\":\"취소\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_SHIP_HAS_RETURN"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(rtnNo)));
        cancel(admin, id).andExpect(status().isOk());
        postJson(admin, S + "/" + ship + "/undo", "{\"rowVer\":" + shipVer(ship) + ",\"clientReqId\":\"" + req() + "\",\"reason\":\"취소\"}")
                .andExpect(status().isOk());
        // 출하 취소 뒤에는 근거 없음(순 출하 0)
        mvc.perform(get(R + "/sources").param("plantCd", "P1").param("lotNo", lotNo(lot)).with(admin)).andExpect(jsonPath("$.data.length()").value(0));
        // 순 출하 0 = 근거 없음(R-7) — 400 shipLineId
        rtn(admin, req(), lineOf(ship), lot, "1", qc).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("shipLineId"));
    }

    // =====================================================================
    // 거부 · 이상 사용
    // =====================================================================

    @Test
    void 넘침_같은_요청_다른_내용_동시_두_건() throws Exception {
        long lot = lotIn("5");
        long ship = shipped(fgLot, "FG-L", lot, "5");
        long line = lineOf(ship);
        rtn(admin, req(), line, lot, "6", qc).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_CUST_RTN_OVER"));
        String r1 = req();
        long id = rtnId(rtn(admin, r1, line, lot, "1", qc));
        assertThat(rtnId(rtn(admin, r1, line, lot, "1", qc))).isEqualTo(id);                         // 같은 요청 = 처음 결과
        rtn(admin, r1, line, lot, "2", qc).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        assertThat(accounts.query(comp, "select count(*) from inv_trx where src_type = 'CUST_RTN'", Integer.class)).isEqualTo(1);
        // 남은 4 에 3 + 3 동시 → 하나만
        CountDownLatch go = new CountDownLatch(1);
        List<String> ids = List.of(req(), req());                  // 요청 id 는 시작 스레드에서 — 서로 다른 요청임을 보장
        assertThat(ids).doesNotHaveDuplicates();
        List<CompletableFuture<Integer>> runs = ids.stream().map(r2 -> CompletableFuture.supplyAsync(() -> {
            try {
                go.await(10, TimeUnit.SECONDS);
                return rtn(admin, r2, line, lot, "3", qc).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        })).toList();
        go.countDown();
        List<Integer> codes = runs.stream().map(f -> f.join()).toList();
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(accounts.query(comp, "select trim_scale(sum(qty))::text from wms_cust_rtn where status = 'RECEIVED'", String.class)).isEqualTo("4");
    }

    /** 같은 요청 id 를 두 스레드가 동시에 — 둘 다 200·같은 반품(처음 결과), 반품·원장 한 건. 위 넘침 시험이 흔들린 경로를 결정적으로 */
    @Test
    void 같은_요청_id_동시_두_번은_한_건() throws Exception {
        long lot = lotIn("5");
        long line = lineOf(shipped(fgLot, "FG-L", lot, "5"));
        String r = req();
        List<String> bodies = concurrently(2, i -> rtn(admin, r, line, lot, "3", qc));
        assertThat(bodies).allSatisfy(b -> assertThat(b).contains("\"result\":\"OK\""));
        assertThat(bodies.stream().map(b -> ((Number) JsonPath.read(b, "$.data.rtnId")).longValue()).distinct()).hasSize(1);
        assertThat(accounts.query(comp, "select count(*) from wms_cust_rtn", Integer.class)).isEqualTo(1);
        assertThat(accounts.query(comp, "select count(*) from inv_trx where src_type = 'CUST_RTN'", Integer.class)).isEqualTo(1);
        assertThat(stock(qc, fgLot, "BLOCKED")).isEqualByComparingTo("3");
    }

    /** 서로 다른 요청 여섯이 동시에 3 씩 — 출하 10 이면 정확히 세 건(9)만, 나머지 409. 남은 양을 잠금 뒤 새 문장으로 다시 읽는지 */
    @Test
    void 넘침_다른_요청_여섯_동시() throws Exception {
        long lot = lotIn("10");
        long line = lineOf(shipped(fgLot, "FG-L", lot, "10"));
        List<String> ids = IntStream.range(0, 6).mapToObj(i -> req()).toList();
        assertThat(ids).doesNotHaveDuplicates();
        List<String> bodies = concurrently(6, i -> rtn(admin, ids.get(i), line, lot, "3", qc));
        assertThat(bodies.stream().filter(b -> b.contains("\"result\":\"OK\""))).hasSize(3);
        assertThat(bodies.stream().filter(b -> b.contains("WMS_CUST_RTN_OVER"))).hasSize(3);
        assertThat(accounts.query(comp, "select trim_scale(sum(qty))::text from wms_cust_rtn where status = 'RECEIVED'", String.class)).isEqualTo("9");
        assertThat(accounts.query(comp, "select count(*) from inv_trx where src_type = 'CUST_RTN'", Integer.class)).isEqualTo(3);
        assertThat(stock(qc, fgLot, "BLOCKED")).isEqualByComparingTo("9");
    }

    interface Call {
        ResultActions run(int i) throws Exception;
    }

    /** n 개 스레드(전용 풀 — 공용 ForkJoin 풀은 병렬도가 코어 수에 묶임)를 한꺼번에 출발시키고 응답 본문을 모은다. n ≤ 연결 풀(10) */
    private List<String> concurrently(int n, Call call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> fs = IntStream.range(0, n).mapToObj(i -> pool.submit(() -> {
                go.await();
                return call.run(i).andReturn().getResponse().getContentAsString();
            })).toList();
            go.countDown();
            List<String> out = new ArrayList<>();
            for (Future<String> f : fs) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 입력_칸_권한_근거_없음() throws Exception {
        long lot = lotIn("5");
        long ship = shipped(fgLot, "FG-L", lot, "5");
        long line = lineOf(ship);
        rtn(admin, req(), line, lot, "1", stg).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("STAGING_NOT_ALLOWED"));
        rtn(admin, req(), line, lot, "1", scrap).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("SCRAP_LOCATION"));
        rtn(admin, req(), line, lot, "0.0001", qc).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("qty"));
        rtn(admin, "short", line, lot, "1", qc).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("clientReqId"));
        postJson(admin, R, "{\"plantCd\":\"P1\",\"shipLineId\":" + line + ",\"lotId\":" + lot + ",\"qty\":1,\"reasonCd\":\"NOPE\",\"nodeId\":" + qc
                + ",\"clientReqId\":\"" + req() + "\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reasonCd"));
        // 근거 없음: 없는 출하 행 / 다른 LOT / 다른 공장으로
        rtn(admin, req(), 999_999_999L, lot, "1", qc).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("shipLineId"));
        rtn(admin, req(), line, lotIn("1"), "1", qc).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("shipLineId"));
        mvc.perform(get(R + "/sources").param("plantCd", "P1").with(admin)).andExpect(status().isBadRequest());
        // 조회만 / 권한 없음
        rtn(user(comp, "viewer1", "SEARCH"), req(), line, lot, "1", qc).andExpect(status().isForbidden());
        mvc.perform(get(R).param("plantCd", "P1").with(user(comp, "none1"))).andExpect(status().isForbidden());
        assertThat(accounts.query(comp, "select count(*) from wms_cust_rtn", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select count(*) from inv_trx where src_type = 'CUST_RTN'", Integer.class)).isZero();
    }

    @Test
    void 직접_SQL_과_A사로_B사() throws Exception {
        long lot = lotIn("5");
        long ship = shipped(fgLot, "FG-L", lot, "5");
        long id = rtnId(rtn(admin, req(), lineOf(ship), lot, "1", qc));
        // 직접 SQL: 출처 없는 RCV_RTN
        Throwable t = catchThrowable(() -> accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at, entry_method, recorded_by)
                values (?, 'P1', 'RCV_RTN', ?, ?, 'AVAILABLE', 1, 'EA', 'MANUAL', now(), 'KEY', 't')""", comp, fg, a1));
        assertThat(PgErrors.constraint(t)).hasValue("ck_inv_trx_cust_rtn_src");
        // B사
        String b = accounts.newComp("CS");
        seed(b);
        RequestPostProcessor other = user(b, "admin1", "SEARCH", "SAVE", "APPROVE");
        long bNode = accounts.query(b, """
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', 'WH', 'B-WH', 'B-WH', 't', 't') returning node_id""", Long.class, b);
        postJson(other, R, "{\"plantCd\":\"P1\",\"shipLineId\":" + lineOf(ship) + ",\"lotId\":" + lot + ",\"qty\":1,\"reasonCd\":\"QUALITY\",\"nodeId\":"
                + bNode + ",\"clientReqId\":\"" + req() + "\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("shipLineId"));
        mvc.perform(get(R + "/sources").param("plantCd", "P1").param("lotNo", lotNo(lot)).with(other)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(R).param("plantCd", "P1").with(other)).andExpect(jsonPath("$.list.length()").value(0));
        mvc.perform(get(R + "/" + id).with(other)).andExpect(status().isNotFound());
        postJson(other, R + "/" + id + "/cancel", "{\"rowVer\":0,\"reason\":\"남의 것\"}").andExpect(status().isNotFound());
        // B사 세션이 A사 출하 id 로 반품 행 → 회사+id FK
        Throwable fk = catchThrowable(() -> accounts.exec(b, """
                insert into wms_cust_rtn (comp_cd, rtn_no, plant_cd, cust_id, ship_id, ship_line_id, item_id, qty, unit_cd, reason_cd, node_id,
                                          client_req_id, received_at, received_by, created_by, updated_by)
                select ?, 'X-1', 'P1', c.cust_id, ?, ?, i.item_id, 1, 'EA', 'QUALITY', ?, ?, now(), 't', 't', 't'
                  from mst_cust c, mst_item i where c.cust_cd = 'C-001' and i.item_cd = 'FG-100'""", b, ship, lineOf(ship), bNode, UUID.randomUUID().toString()));
        assertThat(PgErrors.constraint(fk)).hasValue("fk_wms_cust_rtn_ship");
        assertThat(accounts.query(comp, "select status from wms_cust_rtn where rtn_id = ?", String.class, id)).isEqualTo("RECEIVED");
    }
}
