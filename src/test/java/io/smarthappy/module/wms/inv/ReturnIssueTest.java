package io.smarthappy.module.wms.inv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.srm.link.ReturnShipGate;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 협력사 반품 출고 RTN_SUP + SRM 반품 출고 게이트 (SRM 2부 R4-4·R4-3·R4-9·R4-10, docs/30 12.14 · docs/31 4.12.7).
 * 구매 입고(WMS → SRM 입고 이력에 LOT, R4-8) 위에 반품 지시(SRM 확정)를 만들고 — 출고·교체 요구 되돌림·역분개 왕복,
 * 수량·LOT·상태·근거 중복·재고 부족·재입고 초과·공장 밖 칸·권한·A사로 B사·직접 SQL.
 */
@AutoConfigureMockMvc
class ReturnIssueTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String PO = "/api/srm/pos";
    private static final String RTN = "/api/wms/rtn-issue";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    ObjectProvider<ReturnShipGate> gate;

    String comp;
    RequestPostProcessor admin;
    long a1;
    long a2;
    long p2Loc;
    long bolt;
    long resin;
    long vCust;
    long po;
    long l1;
    long l2;
    int seq;
    int rtnSeq;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("RI");
        admin = user(comp, "admin1", true);
        vCust = seed(comp);
        long wh = node(comp, "P1", null, "WH", "RM-WH");
        a1 = node(comp, "P1", wh, "LOC", "A-01");
        a2 = node(comp, "P1", wh, "LOC", "A-02");
        long wh2 = node(comp, "P2", null, "WH", "P2-WH");
        p2Loc = node(comp, "P2", wh2, "LOC", "B-01");
        bolt = accounts.query(comp, "select item_id from mst_item where item_cd = 'BOLT-M8'", Long.class);
        resin = accounts.query(comp, "select item_id from mst_item where item_cd = 'RESIN-L'", Long.class);
        po = issued(comp, admin);
        l1 = line(po, 1);
        l2 = line(po, 2);
    }

    private RequestPostProcessor user(String c, String id, boolean rtn) {
        long u = accounts.newUser(c, id, PW);
        accounts.grant(c, u, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, u, "WMS", "wms-rcv", "SEARCH", "SAVE");
        accounts.grant(c, u, "WMS", "wms-trx", "SEARCH", "SAVE");
        if (rtn) {
            accounts.grant(c, u, "RTN", "wms-rtn-issue", "SEARCH", "SAVE");
        }
        return accounts.as(c, u, id);
    }

    /** 볼트(EA, LOT 비관리)·수지(LOT 관리 — 발주 BOX, 1 BOX = 12 EA), 공급사 V100 */
    private long seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.addPlant(c, "P2", "2공장");
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT-M8', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't'), (?, 'RESIN-L', '수지', 'RM', 'BUY', 'BOX', 'Y', 't', 't')""", c, c);
        long id = accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'V100', 'V100 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, c);
        accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, c);
        return id;
    }

    private long node(String c, String plant, Long parent, String type, String cd) {
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, c, plant, parent, type, cd, cd);
    }

    private ResultActions postJson(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions act(RequestPostProcessor who, long id, String action) throws Exception {
        int rv = JsonPath.read(mvc.perform(get(PO + "/" + id).with(who)).andReturn().getResponse().getContentAsString(), "$.data.po.rowVer");
        return postJson(who, PO + "/" + id + "/" + action, "{\"rowVer\":" + rv + "}");
    }

    /** 발행된 발주(P1): 행 1 볼트 100 EA, 행 2 수지 5 BOX(발행 뒤 기본 단위 EA, 1 BOX = 12 EA) */
    private long issued(String c, RequestPostProcessor who) throws Exception {
        String due = accounts.query(c, "select (" + TestAccounts.COMP_TODAY + " + 20)::text", String.class);
        String body = postJson(who, PO, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"100\",\"unitPrice\":\"1\",\"dueDate\":\""
                + due + "\"},{\"itemCd\":\"RESIN-L\",\"qty\":\"5\",\"unitPrice\":\"1\",\"dueDate\":\"" + due + "\"}]}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.data")).longValue();
        act(who, id, "confirm").andExpect(status().isOk());
        act(who, id, "issue").andExpect(status().isOk());
        accounts.exec(c, "update mst_item set unit_cd = 'EA' where item_cd = 'RESIN-L'");
        accounts.exec(c, "insert into mst_unit_conv (comp_cd, item_id, from_unit, to_unit, factor, created_by, updated_by) "
                + "select comp_cd, item_id, 'BOX', 'EA', 12, 't', 't' from mst_item where item_cd = 'RESIN-L'");
        return id;
    }

    private long line(long poId, int lineNo) {
        return accounts.query(comp, "select po_line_id from srm_po_line where po_id = ? and line_no = ?", Long.class, poId, lineNo);
    }

    private String req() {
        return "rtniss-" + (++seq) + "-" + System.nanoTime();
    }

    /** 구매 입고(WMS) → 받은 LOT id */
    private long receive(long node, long poLine, String qty) throws Exception {
        MvcResult m = postJson(admin, "/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + node + ",\"poLineId\":" + poLine + ",\"qty\":" + qty
                + ",\"stockStatus\":\"HOLD\",\"clientReqId\":\"" + req() + "\"}").andExpect(status().isOk()).andReturn();
        long trx = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.trxId")).longValue();
        return accounts.query(comp, "select src_id from inv_trx where trx_id = ?", Long.class, trx);
    }

    /** 확정된 반품 지시(SRM 화면 대신 직접 — NCR 은 계약 값 스냅샷이라 FK 없음). 단위 = 그 품목 발주 단위 */
    private String confirmed(long item, long lot, String unit, String qty, boolean replace, Long poLine) {
        String no = "RT-" + (++rtnSeq);
        String lotNo = accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, lot);
        long id = accounts.query(comp, """
                insert into srm_return (comp_cd, return_no, cust_id, plant_cd, ncr_id, ncr_no, ncr_line_no, lot_id, lot_no, po_line_id, item_id,
                                        unit_cd, qty, replace_yn, created_by, updated_by)
                values (?, ?, ?, 'P1', ?, ?, 1, ?, ?, ?, ?, ?, ?::numeric, ?, 't', 't') returning return_id""", Long.class,
                comp, no, vCust, 900L + rtnSeq, "NCR-" + rtnSeq, lot, lotNo, poLine, item, unit, qty, replace ? "Y" : "N");
        accounts.exec(comp, "update srm_return set status = 'CONFIRMED', confirmed_at = now(), confirmed_by = 't' where return_id = ?", id);
        return no;
    }

    private ResultActions issue(RequestPostProcessor who, String no, String rows, String reqId) throws Exception {
        return postJson(who, RTN, "{\"returnNo\":\"" + no + "\",\"rows\":[" + rows + "],\"clientReqId\":\"" + reqId + "\"}");
    }

    private static String row(long node, String status, String qty) {
        return "{\"nodeId\":" + node + ",\"stockStatus\":\"" + status + "\",\"qty\":" + qty + "}";
    }

    private String ret(String no) {
        return accounts.query(comp, "select status || ':' || coalesce(ship_ref, '-') || ':' || (shipped_at is not null) from srm_return where return_no = ?",
                String.class, no);
    }

    private BigDecimal stock(long node, long item) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and item_id = ?", BigDecimal.class, node, item)
                .stripTrailingZeros();
    }

    private String recv(long poLine) {
        return accounts.query(comp, "select trim_scale(recv_qty)::text from srm_po_line where po_line_id = ?", String.class, poLine);
    }

    private long returnId(String no) {
        return accounts.query(comp, "select return_id from srm_return where return_no = ?", Long.class, no);
    }

    private HbErrorCode fail(Runnable r) {
        Throwable t = catchThrowable(r::run);
        assertThat(t).isInstanceOf(BusinessException.class);
        return ((BusinessException) t).code();
    }

    private static String constraint(Runnable r) {
        Throwable t = catchThrowable(r::run);
        assertThat(t).isNotNull();
        return PgErrors.constraint(t).orElse(t.getMessage());
    }

    private static long firstTrx(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.data.trxIds[0]")).longValue();
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void 교체_없는_반품은_두_칸에서_지시_수량_전부_나가고_SRM은_출고됨_발주_입고는_그대로() throws Exception {
        long lot = receive(a1, l1, "30");
        receive(a2, l1, "20");
        String no = confirmed(bolt, lot, "EA", "35", false, null);
        // 보기: 지시 + 후보 칸(LOT 비관리 = LOT 없는 칸 — 이 공장 볼트 재고 전부)
        mvc.perform(get(RTN).param("returnNo", "RTN:" + no).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ret.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.ret.baseQty").value(35))
                .andExpect(jsonPath("$.data.lotCtrl").value(false))
                .andExpect(jsonPath("$.data.stock.length()").value(2));
        String r = req();
        ResultActions ok = issue(admin, no, row(a1, "HOLD", "30") + "," + row(a2, "HOLD", "5"), r).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay").value(false))
                .andExpect(jsonPath("$.data.shipped.status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.trxIds.length()").value(2));
        assertThat(stock(a1, bolt)).isEqualByComparingTo("0");
        assertThat(stock(a2, bolt)).isEqualByComparingTo("15");
        assertThat(ret(no)).isEqualTo("SHIPPED:RTN_ISS:" + r + ":true");
        assertThat(recv(l1)).isEqualTo("50");                                    // 교체 없음 — 발주 입고 그대로
        long t = firstTrx(ok);
        assertThat(accounts.query(comp, "select trx_type || ':' || src_type || ':' || src_id || ':' || (lot_id is null) from inv_trx where trx_id = ?",
                String.class, t)).isEqualTo("RTN_SUP:SRM_RETURN:" + returnId(no) + ":true");
        // 같은 요청 다시 → 처음 결과, 원장·재고 그대로
        issue(admin, no, row(a1, "HOLD", "30") + "," + row(a2, "HOLD", "5"), r).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay").value(true)).andExpect(jsonPath("$.data.trxIds[0]").value(t));
        assertThat(stock(a2, bolt)).isEqualByComparingTo("15");
        // 같은 요청 id·다른 내용 → 409, 그대로
        issue(admin, no, row(a2, "HOLD", "35"), r).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        // 이미 나간 반품을 새 요청으로 → 409 상태
        issue(admin, no, row(a2, "HOLD", "15"), req()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SRM_STATE"));
        assertThat(stock(a2, bolt)).isEqualByComparingTo("15");
        assertThat(accounts.query(comp, "select count(*) from inv_trx where trx_type = 'RTN_SUP'", Integer.class)).isEqualTo(2);
    }

    @Test
    void 교체_요구는_그_LOT_입고를_되돌리고_역분개하면_확정으로_입고도_다시_다시_출고할_수_있다() throws Exception {
        long lot = receive(a1, l1, "40");
        String no = confirmed(bolt, lot, "EA", "10", true, l1);
        String r = req();
        ResultActions ok = issue(admin, no, row(a1, "HOLD", "10"), r).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipped.openQty").value(70)).andExpect(jsonPath("$.data.shipped.poStatus").value("PARTIAL_RECEIVED"));
        assertThat(recv(l1)).isEqualTo("30");                                    // 교체품 대기 = 열린 수량 다시
        assertThat(accounts.query(comp, "select trim_scale(qty) || ':' || lot_id from srm_po_receipt where trx_ref = ?", String.class, "RTN_ISS:" + r))
                .isEqualTo("-10:" + lot);
        // 역분개(수불 원장 화면) → SRM 확정으로, 같은 LOT 로 다시 입고
        long t = firstTrx(ok);
        postJson(admin, "/api/wms/trx/" + t + "/reverse", "{\"reason\":\"잘못 출고\"}").andExpect(status().isOk());
        assertThat(ret(no)).isEqualTo("CONFIRMED:-:false");
        assertThat(recv(l1)).isEqualTo("40");
        assertThat(stock(a1, bolt)).isEqualByComparingTo("40");
        assertThat(accounts.query(comp, "select trim_scale(qty) || ':' || lot_id from srm_po_receipt where trx_ref = ?", String.class, "RTN_ISSR:" + t))
                .isEqualTo("10:" + lot);
        // 왕복 뒤 다시 출고 — 되돌린 입고의 LOT 이력을 다시 찾는다
        issue(admin, no, row(a1, "HOLD", "10"), req()).andExpect(status().isOk());
        assertThat(recv(l1)).isEqualTo("30");
        assertThat(ret(no)).startsWith("SHIPPED:");
    }

    @Test
    void LOT_관리_품목은_그_LOT_칸에서만_지시_단위_BOX를_기본_단위로_보류_LOT도_나간다() throws Exception {
        long lot = receive(a1, l2, "2");                                         // 2 BOX = 24 EA
        long other = receive(a2, l2, "1");                                       // 다른 LOT 12 EA
        accounts.exec(comp, "insert into lot_hold (comp_cd, lot_id, source, reason, held_by) values (?, ?, 'MANUAL', '불량 반품', 't')", comp, lot);
        assertThat(accounts.query(comp, "select hold_yn from lot_master where lot_id = ?", String.class, lot)).isEqualTo("Y");
        String no = confirmed(resin, lot, "BOX", "1", true, l2);
        mvc.perform(get(RTN).param("returnNo", no).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ret.baseQty").value(12)).andExpect(jsonPath("$.data.lotCtrl").value(true))
                .andExpect(jsonPath("$.data.stock.length()").value(1)).andExpect(jsonPath("$.data.stock[0].nodeId").value(a1));
        // 다른 LOT 칸(a2)에서 내보내려 하면 — 원장은 지시 LOT 로 쓰므로 그 칸에는 그 LOT 재고가 없다 → 재고 부족, 모두 그대로
        issue(admin, no, row(a2, "HOLD", "12"), req()).andExpect(status().isConflict());
        assertThat(ret(no)).startsWith("CONFIRMED:");
        assertThat(recv(l2)).isEqualTo("3");
        issue(admin, no, row(a1, "HOLD", "12"), req()).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select trim_scale(qty)::text from inv_stock where lot_id = ?", String.class, lot)).isEqualTo("12");
        assertThat(accounts.query(comp, "select trim_scale(qty)::text from inv_stock where lot_id = ?", String.class, other)).isEqualTo("12");
        assertThat(recv(l2)).isEqualTo("2");                                     // 1 BOX 되돌림(발주 단위)
        assertThat(accounts.query(comp, "select hold_yn from lot_master where lot_id = ?", String.class, lot)).isEqualTo("Y");   // LOT 마스터 그대로
    }

    // =====================================================================
    // 이상 사용
    // =====================================================================

    @Test
    void 수량_다름_재고_부족_공장_밖_칸_작성_중_없는_번호는_거부되고_모두_그대로() throws Exception {
        long lot = receive(a1, l1, "40");
        String no = confirmed(bolt, lot, "EA", "10", true, l1);
        issue(admin, no, row(a1, "HOLD", "9"), req()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SRM_RTN_QTY_MISMATCH"));
        issue(admin, no, row(a1, "HOLD", "6") + "," + row(a1, "HOLD", "4"), req()).andExpect(status().isBadRequest());   // 같은 칸 두 번
        issue(admin, no, row(p2Loc, "HOLD", "10"), req()).andExpect(status().isBadRequest());
        issue(admin, no, row(a1, "AVAILABLE", "10"), req()).andExpect(status().isConflict());                           // 그 상태 재고 없음
        issue(admin, no, "", req()).andExpect(status().isBadRequest());
        issue(admin, no, row(a1, "HOLD", "0"), req()).andExpect(status().isBadRequest());
        issue(admin, "RT-NOPE", row(a1, "HOLD", "10"), req()).andExpect(status().isBadRequest());
        postJson(admin, RTN, "{\"returnNo\":\"" + no + "\",\"rows\":[" + row(a1, "HOLD", "10") + "]}").andExpect(status().isBadRequest());   // 요청 id 없음
        assertThat(ret(no)).isEqualTo("CONFIRMED:-:false");
        assertThat(recv(l1)).isEqualTo("40");
        assertThat(stock(a1, bolt)).isEqualByComparingTo("40");
        // 작성 중(확정 전) 반품은 없는 것
        String draft = "RT-D";
        accounts.exec(comp, """
                insert into srm_return (comp_cd, return_no, cust_id, plant_cd, ncr_id, ncr_no, ncr_line_no, lot_id, lot_no, item_id, unit_cd, qty,
                                        created_by, updated_by)
                select ?, ?, ?, 'P1', 1, 'NCR-D', 1, lot_id, lot_no, ?, 'EA', 1, 't', 't' from lot_master where lot_id = ?""", comp, draft, vCust, bolt, lot);
        mvc.perform(get(RTN).param("returnNo", draft).with(admin)).andExpect(status().isBadRequest());
        issue(admin, draft, row(a1, "HOLD", "1"), req()).andExpect(status().isBadRequest());
        assertThat(accounts.query(comp, "select count(*) from inv_trx where trx_type = 'RTN_SUP'", Integer.class)).isZero();
    }

    @Test
    void 교체_요구인데_그_LOT_입고가_발주_행에_없으면_409_재입고가_넘치면_역분개는_409() throws Exception {
        // 발주 없이 받은 LOT(RCV_MAT) 로 교체 요구 반품 → 그 발주 행에 그 LOT 입고 없음
        MvcResult m = postJson(admin, "/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + a2 + ",\"itemId\":" + bolt
                + ",\"qty\":5,\"stockStatus\":\"HOLD\",\"clientReqId\":\"" + req() + "\"}").andExpect(status().isOk()).andReturn();
        long matLot = accounts.query(comp, "select src_id from inv_trx where trx_id = ?", Long.class,
                ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.trxId")).longValue());
        String noRcv = confirmed(bolt, matLot, "EA", "5", true, l1);
        issue(admin, noRcv, row(a2, "HOLD", "5"), req()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SRM_RTN_NO_RECEIPT"));
        assertThat(stock(a2, bolt)).isEqualByComparingTo("5");
        // 다 받음(100) → 교체 요구 10 출고(90) → 교체품 10 입고(100) → 출고 역분개 = 재입고 10 이면 110 > 100 → 409, 모두 그대로
        long lot = receive(a1, l1, "100");
        String no = confirmed(bolt, lot, "EA", "10", true, l1);
        long t = firstTrx(issue(admin, no, row(a1, "HOLD", "10"), req()).andExpect(status().isOk()));
        receive(a1, l1, "10");
        postJson(admin, "/api/wms/trx/" + t + "/reverse", "{\"reason\":\"잘못 출고\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SRM_RECV_OVER"));
        assertThat(ret(no)).startsWith("SHIPPED:");
        assertThat(recv(l1)).isEqualTo("100");
        assertThat(accounts.query(comp, "select count(*) from inv_trx where reversal_of_id = ?", Integer.class, t)).isZero();
    }

    @Test
    void 교체_요구_출고_뒤_발주를_마감하면_역분개는_409_발주가_닫혀_문구() throws Exception {
        long lot = receive(a1, l1, "40");
        String no = confirmed(bolt, lot, "EA", "10", true, l1);
        long t = firstTrx(issue(admin, no, row(a1, "HOLD", "10"), req()).andExpect(status().isOk()));
        act(admin, po, "close").andExpect(status().isOk());
        String poNo = accounts.query(comp, "select po_no from srm_po where po_id = ?", String.class, po);
        postJson(admin, "/api/wms/trx/" + t + "/reverse", "{\"reason\":\"잘못 출고\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SRM_RTN_PO_CLOSED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(poNo)));
        assertThat(ret(no)).startsWith("SHIPPED:");
        assertThat(stock(a1, bolt)).isEqualByComparingTo("30");
    }

    @Test
    void 게이트_직접_LOT_규칙_같은_근거_되돌림_근거_게이트만_부르면_커밋_거부() throws Exception {
        long lotA = receive(a1, l2, "1");
        long lotB = receive(a2, l2, "1");
        String rn = confirmed(resin, lotA, "BOX", "1", false, null);
        long rid = returnId(rn);
        assertThat(fail(() -> runner.run(comp, () -> ReturnShipGate.of(gate).ship(rid, lotB, new BigDecimal("12"), "G:0", "t", null))))
                .isEqualTo(HbErrorCode.SRM_RTN_LOT_MISMATCH);
        assertThat(fail(() -> runner.run(comp, () -> ReturnShipGate.of(gate).ship(rid, null, new BigDecimal("12"), "G:0", "t", null))))
                .isEqualTo(HbErrorCode.SRM_RTN_LOT_MISMATCH);
        // 비관리 품목은 뽑은 LOT 가 없다 — 값을 넘기면 부르는 쪽 버그
        long lot = receive(a1, l1, "40");
        String no = confirmed(bolt, lot, "EA", "10", false, null);
        long id = returnId(no);
        assertThat(catchThrowable(() -> runner.run(comp, () -> ReturnShipGate.of(gate).ship(id, lot, new BigDecimal("10"), "G:1", "t", null))))
                .isInstanceOf(IllegalArgumentException.class);
        // 게이트만 부르고 원장이 없으면 커밋 때 거부(V9314 — 출고됨 ⇔ 반품 출고 원장), 반품 그대로
        assertThat(constraint(() -> runner.run(comp, () -> ReturnShipGate.of(gate).ship(id, null, new BigDecimal("10"), "G:1", "t", null))))
                .isEqualTo("ck_srm_return_shipped_ledger");
        assertThat(ret(no)).isEqualTo("CONFIRMED:-:false");
        String r1 = req();
        issue(admin, no, row(a1, "HOLD", "10"), r1).andExpect(status().isOk());
        String ref = "RTN_ISS:" + r1;
        long lot2 = receive(a2, l1, "5");
        String no2 = confirmed(bolt, lot2, "EA", "5", false, null);
        long id2 = returnId(no2);
        assertThat(fail(() -> runner.run(comp, () -> ReturnShipGate.of(gate).ship(id2, null, new BigDecimal("5"), ref, "t", null))))
                .isEqualTo(HbErrorCode.DUPLICATE);
        assertThat(fail(() -> runner.run(comp, () -> ReturnShipGate.of(gate).unship(id, "G:R", "t", null, "G:OTHER"))))
                .isEqualTo(HbErrorCode.SRM_STATE);
        assertThat(fail(() -> runner.run(comp, () -> ReturnShipGate.of(gate).unship(id2, "G:R2", "t", null, ref))))
                .isEqualTo(HbErrorCode.SRM_STATE);                                  // 출고 안 된 반품
        // 원장 역분개 없이 게이트만 되돌리면 커밋 때 거부 — 출고 원장이 살아 있다
        assertThat(constraint(() -> runner.run(comp, () -> ReturnShipGate.of(gate).unship(id, "G:R", "t", null, ref))))
                .isEqualTo("ck_srm_return_shipped_ledger");
        assertThat(ret(no)).isEqualTo("SHIPPED:" + ref + ":true");
        // 출고 뒤 취소 불가(전이)
        assertThat(constraint(() -> accounts.exec(comp, "update srm_return set status = 'CANCELLED', cancel_reason = 'x' where return_no = ?", no)))
                .isEqualTo("ck_srm_return_transition");
    }

    @Test
    void 교체_요구_되돌림은_그_LOT_순입고까지_두_반품이_같은_LOT_를_넘게_되돌리면_409() throws Exception {
        long lot = receive(a1, l1, "10");
        receive(a2, l1, "5");
        String first = confirmed(bolt, lot, "EA", "6", true, l1);
        issue(admin, first, row(a1, "HOLD", "6"), req()).andExpect(status().isOk());
        assertThat(recv(l1)).isEqualTo("9");
        // 같은 LOT 의 다른 NCR 처리 줄 반품 6 — 그 LOT 순입고는 4 → 409, 모두 그대로
        String second = confirmed(bolt, lot, "EA", "6", true, l1);
        issue(admin, second, row(a1, "HOLD", "4") + "," + row(a2, "HOLD", "2"), req()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SRM_RECV_REVERSE"));
        assertThat(ret(second)).isEqualTo("CONFIRMED:-:false");
        assertThat(recv(l1)).isEqualTo("9");
        assertThat(stock(a1, bolt)).isEqualByComparingTo("4");
    }

    @Test
    void 권한_없으면_403_A사로_B사_반품_번호_id_는_없는_것() throws Exception {
        long lot = receive(a1, l1, "40");
        String no = confirmed(bolt, lot, "EA", "10", false, null);
        RequestPostProcessor noRtn = user(comp, "plain1", false);
        mvc.perform(get(RTN).param("returnNo", no).with(noRtn)).andExpect(status().isForbidden());
        issue(noRtn, no, row(a1, "HOLD", "10"), req()).andExpect(status().isForbidden());
        // B사
        String b = accounts.newComp("RJ");
        RequestPostProcessor bAdmin = user(b, "admin1", true);
        accounts.addPlant(b, "P1", "B 1공장");
        mvc.perform(get(RTN).param("returnNo", no).with(bAdmin)).andExpect(status().isBadRequest());
        issue(bAdmin, no, row(a1, "HOLD", "10"), req()).andExpect(status().isBadRequest());
        long id = returnId(no);
        assertThat(fail(() -> runner.run(b, () -> ReturnShipGate.of(gate).ship(id, lot, new BigDecimal("10"), "B:1", "t", null))))
                .isEqualTo(HbErrorCode.NOT_FOUND);
        assertThat(ret(no)).isEqualTo("CONFIRMED:-:false");
        assertThat(stock(a1, bolt)).isEqualByComparingTo("40");
    }

    @Test
    void 직접_SQL_출처_없는_RTN_SUP_근거_없는_출고됨_건너뛰는_전이_원장과_어긋난_출고는_DB가_막는다() throws Exception {
        long lot = receive(a1, l1, "40");
        String no = confirmed(bolt, lot, "EA", "10", false, null);
        String ins = """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, src_id, occurred_at, entry_method, recorded_by)
                values (?, 'P1', 'RTN_SUP', ?, ?, 'HOLD', ?, 'EA', ?, ?, now(), 'KEY', 't')""";
        assertThat(constraint(() -> accounts.exec(comp, ins, comp, bolt, a1, -1, "MANUAL", null))).isEqualTo("ck_inv_trx_rtn_src");
        assertThat(constraint(() -> accounts.exec(comp, ins, comp, bolt, a1, 1, "SRM_RETURN", returnId(no)))).isEqualTo("ck_inv_trx_sign");
        assertThat(constraint(() -> accounts.exec(comp, "update srm_return set status = 'SHIPPED', shipped_at = now() where return_no = ?", no)))
                .isEqualTo("ck_srm_return_ship_ref");
        assertThat(constraint(() -> accounts.exec(comp, "update srm_return set status = 'DRAFT' where return_no = ?", no)))
                .isEqualTo("ck_srm_return_transition");
        // 게이트 없이 — 근거를 지어 넣은 직접 출고됨 / 반품은 확정인데 직접 넣은 반품 출고 원장 → 커밋 때 거부(V9314)
        assertThat(constraint(() -> accounts.exec(comp,
                "update srm_return set status = 'SHIPPED', shipped_at = now(), ship_ref = 'RTN_ISS:FAKE' where return_no = ?", no)))
                .isEqualTo("ck_srm_return_shipped_ledger");
        assertThat(constraint(() -> accounts.exec(comp, ins, comp, bolt, a1, -10, "SRM_RETURN", returnId(no)))).isEqualTo("ck_srm_return_shipped_ledger");
        assertThat(ret(no)).isEqualTo("CONFIRMED:-:false");
        assertThat(stock(a1, bolt)).isEqualByComparingTo("40");
    }
}
