package io.smarthappy.module.wms.inv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.production.LotService;
import io.smarthappy.module.srm.link.PoReceiptGate;
import io.smarthappy.module.srm.link.PoSupplyQuery;
import io.smarthappy.module.wms.inv.InvDtos.RcvForm;
import io.smarthappy.module.wms.inv.InvDtos.TrxReq;
import io.smarthappy.module.wms.label.LabelMapper;
import io.smarthappy.module.wms.label.LabelPrintService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 구매 입고 RCV_PO (P4 2부 B4b, docs/30 12.6) — SRM {@link PoReceiptGate} 를 거쳐 발주 행 입고 수량(투영)과 원장이 같이 움직인다.
 * 정상(부분·완료, LOT 관리 품목, 발주 단위 ≠ 기본 단위), 같은 요청 두 번, 초과·공장·품목 거부(아무것도 안 바뀜), 역분개(SRM 되돌림),
 * 대사, 직접 SQL(발주 행 없는 RCV_PO·다른 유형에 발주 행·거울 아닌 역분개·다른 회사 발주 행), 동시 입고, A사로 B사, SRM 없는 설치본.
 */
@AutoConfigureMockMvc
class PoReceiptTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String PO = "/api/srm/pos";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    String comp;
    RequestPostProcessor admin;
    long wh;
    long a1;
    long p2Loc;
    long bolt;
    long resin;
    int seq;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("RP");
        admin = user(comp, "admin1");
        seed(comp);
        wh = node(comp, "P1", null, "WH", "RM-WH");
        a1 = node(comp, "P1", wh, "LOC", "A-01");
        long wh2 = node(comp, "P2", null, "WH", "P2-WH");
        p2Loc = node(comp, "P2", wh2, "LOC", "B-01");
        bolt = accounts.query(comp, "select item_id from mst_item where item_cd = 'BOLT-M8'", Long.class);
        resin = accounts.query(comp, "select item_id from mst_item where item_cd = 'RESIN-L'", Long.class);
    }

    private RequestPostProcessor user(String c, String id) {
        long u = accounts.newUser(c, id, PW);
        accounts.grant(c, u, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, u, "PUR", "srm-ack", "SEARCH", "SAVE");
        accounts.grant(c, u, "WMS", "wms-rcv", "SEARCH", "SAVE");
        accounts.grant(c, u, "WMS", "wms-trx", "SEARCH", "SAVE");
        accounts.grant(c, u, "WMS", "wms-stock", "SEARCH", "FN1");
        return accounts.as(c, u, id);
    }

    /** 볼트(EA, LOT 비관리)·수지(EA 기본, LOT 관리 — 발주는 BOX, 1 BOX = 12 EA), 공급사 V100 */
    private void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.addPlant(c, "P2", "2공장");
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT-M8', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't'), (?, 'RESIN-L', '수지', 'RM', 'BUY', 'BOX', 'Y', 't', 't'),
                       (?, 'NUT-M8', '너트', 'RM', 'BUY', 'EA', 'N', 't', 't')""", c, c, c);
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

    private ResultActions act(RequestPostProcessor who, long id, String action) throws Exception {
        int rv = JsonPath.read(mvc.perform(get(PO + "/" + id).with(who)).andReturn().getResponse().getContentAsString(), "$.data.po.rowVer");
        return postJson(who, PO + "/" + id + "/" + action, "{\"rowVer\":" + rv + "}");
    }

    /**
     * 발행된 발주(P1, 행 1: 볼트 100 EA, 행 2: 수지 5 BOX). 수지는 BOX 로 발주한 뒤 기본 단위를 EA 로 바꾸고 1 BOX = 12 EA
     * (발주 행 단위는 발주 때 스냅샷 — 발주 단위 ≠ 기본 단위)
     */
    private long issued(String c, RequestPostProcessor who) throws Exception {
        String due = accounts.query(c, "select (" + TestAccounts.COMP_TODAY + " + 20)::text", String.class);
        String body = postJson(who, PO, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"100\",\"unitPrice\":\"1\",\"dueDate\":\""
                + due + "\"},{\"itemCd\":\"RESIN-L\",\"qty\":\"5\",\"unitPrice\":\"1\",\"dueDate\":\"" + due + "\"}]}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.data")).longValue();
        act(who, id, "confirm").andExpect(status().isOk());
        act(who, id, "issue").andExpect(status().isOk());
        if (accounts.query(c, "select unit_cd from mst_item where item_cd = 'RESIN-L'", String.class).equals("BOX")) {
            accounts.exec(c, "update mst_item set unit_cd = 'EA' where item_cd = 'RESIN-L'");
            accounts.exec(c, "insert into mst_unit_conv (comp_cd, item_id, from_unit, to_unit, factor, created_by, updated_by) "
                    + "select comp_cd, item_id, 'BOX', 'EA', 12, 't', 't' from mst_item where item_cd = 'RESIN-L'");
        }
        return id;
    }

    private long line(String c, long poId, int lineNo) {
        return accounts.query(c, "select po_line_id from srm_po_line where po_id = ? and line_no = ?", Long.class, poId, lineNo);
    }

    private String recvOf(long lineId) {
        return accounts.query(comp, "select trim_scale(recv_qty)::text from srm_po_line where po_line_id = ?", String.class, lineId);
    }

    private String poStatus(long poId) {
        return accounts.query(comp, "select status from srm_po where po_id = ?", String.class, poId);
    }

    private String req() {
        return "rcvpo-" + (++seq) + "-" + System.nanoTime();
    }

    private ResultActions receive(RequestPostProcessor who, long node, long poLine, String qty, String reqId, String extra) throws Exception {
        return postJson(who, "/api/wms/rcv", "{\"plantCd\":\"" + (node == p2Loc ? "P2" : "P1") + "\",\"nodeId\":" + node + ",\"poLineId\":"
                + poLine + ",\"qty\":" + qty + ",\"stockStatus\":\"AVAILABLE\",\"supplierCd\":\"NOPE\",\"clientReqId\":\"" + reqId + "\""
                + (extra == null ? "" : extra) + "}");
    }

    private BigDecimal stock(long node, long item) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and item_id = ?", BigDecimal.class, node, item)
                .stripTrailingZeros();
    }

    private int count(String sql, Object... args) {
        return accounts.query(comp, sql, Integer.class, args);
    }

    private static long trxId(MvcResult m) throws Exception {
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.trxId")).longValue();
    }

    // =====================================================================
    // 정상
    // =====================================================================

    /**
     * 이상 사용(12.6.5): 다른 모듈 코드가 계약 InventoryService.post 로 RCV_PO 를 직접 넣으면 SRM 게이트를 건너뛴다 — 400 trxType 으로 거부,
     * 원장·재고·SRM 입고 수량 모두 그대로. 같은 행을 정상 입고 화면 경로로 보내면 들어간다(게이트를 부르는 postReceipt 경로)
     */
    @Test
    void RCV_PO_를_원장_계약으로_직접_게시하면_거부되고_원장_SRM_모두_그대로() throws Exception {
        long l1 = line(comp, issued(comp, admin), 1);
        int before = count("select count(*) from inv_trx where trx_type = 'RCV_PO'");
        TrxReq direct = new TrxReq("RCV_PO", bolt, a1, "AVAILABLE", new BigDecimal("40"), null, "MAT_RECEIPT", null, null, null,
                java.time.OffsetDateTime.now(), "KEY", null, null).withPo(l1, new BigDecimal("40"));
        Throwable t = catchThrowable(() -> runner.callAs(comp, "intruder", () -> inventory.post(java.util.List.of(direct), null)));
        assertThat(t).isInstanceOf(io.smarthappy.platform.common.web.BusinessException.class);
        assertThat(((io.smarthappy.platform.common.web.BusinessException) t).code()).isEqualTo(io.smarthappy.platform.common.web.HbErrorCode.INVALID_VALUE);
        assertThat(count("select count(*) from inv_trx where trx_type = 'RCV_PO'")).isEqualTo(before);
        assertThat(stock(a1, bolt)).isEqualByComparingTo("0");
        assertThat(recvOf(l1)).isEqualTo("0");
        // 화면 경로(입고 서비스 → 게이트 → postReceipt)는 그대로 된다
        receive(admin, a1, l1, "40", req(), ",\"itemId\":" + bolt).andExpect(status().isOk());
        assertThat(stock(a1, bolt)).isEqualByComparingTo("40");
        assertThat(recvOf(l1)).isEqualTo("40");
    }

    @Test
    void 발주_행_입고는_SRM_입고수량과_원장을_같이_움직이고_공급사는_발주_거래처다() throws Exception {
        long po = issued(comp, admin);
        long l1 = line(comp, po, 1);
        // 선택지: 이 공장·품목의 열린 발주 행
        mvc.perform(get("/api/wms/rcv/po-lines").param("plantCd", "P1").param("itemId", String.valueOf(bolt)).with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.lines[0].poLineId").value(l1)).andExpect(jsonPath("$.data.lines[0].openQty").value(100))
                .andExpect(jsonPath("$.data.lines[0].supplierCd").value("V100"));
        MvcResult m = receive(admin, a1, l1, "40", req(), ",\"itemId\":" + bolt).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay").value(false)).andReturn();
        long trx = trxId(m);
        assertThat(accounts.query(comp, "select trx_type || ':' || trim_scale(qty) || ':' || po_line_id || ':' || trim_scale(po_qty) || ':' || src_type "
                + "|| ':' || (lot_id is null) from inv_trx where trx_id = ?", String.class, trx)).isEqualTo("RCV_PO:40:" + l1 + ":40:MAT_RECEIPT:true");
        assertThat(recvOf(l1)).isEqualTo("40");
        assertThat(poStatus(po)).isEqualTo("PARTIAL_RECEIVED");
        assertThat(stock(a1, bolt)).isEqualByComparingTo("40");
        // 입고 LOT 공급사 = 발주 거래처(화면이 보낸 NOPE 은 쓰지 않음)
        assertThat(accounts.query(comp, "select c.cust_cd from inv_trx t join lot_master l on l.lot_id = t.src_id join mst_cust c on c.cust_id = l.supplier_cust_id "
                + "where t.trx_id = ?", String.class, trx)).isEqualTo("V100");
        // 남은 60 을 받으면 행은 다 받음
        receive(admin, a1, l1, "60", req(), null).andExpect(status().isOk());
        assertThat(recvOf(l1)).isEqualTo("100");
        mvc.perform(get("/api/wms/rcv/po-lines").param("plantCd", "P1").param("itemId", String.valueOf(bolt)).with(admin))
                .andExpect(jsonPath("$.data.lines.length()").value(0));
        mvc.perform(get("/api/wms/rcv/recent").param("plantCd", "P1").with(admin)).andExpect(jsonPath("$.list.length()").value(2))
                .andExpect(jsonPath("$.list[0].trxType").value("RCV_PO"));
    }

    @Test
    void 발주_단위가_기본_단위와_다르면_원장은_기본_단위_LOT_관리_품목은_LOT_이_붙는다() throws Exception {
        long po = issued(comp, admin);
        long l2 = line(comp, po, 2);
        // 선택지 잔량은 발주 단위(입력 수량과 같은 단위) — 기본 단위 EA 로 환산한 60 이 아니라 5 BOX
        mvc.perform(get("/api/wms/rcv/po-lines").param("plantCd", "P1").param("itemId", String.valueOf(resin)).with(admin))
                .andExpect(jsonPath("$.data.lines[0].openQty").value(5)).andExpect(jsonPath("$.data.lines[0].unitCd").value("BOX"));
        MvcResult m = receive(admin, a1, l2, "2", req(), null).andExpect(status().isOk()).andReturn();
        long trx = trxId(m);
        assertThat(accounts.query(comp, "select trim_scale(qty) || ' ' || unit_cd || ' / ' || trim_scale(po_qty) || ':' || (lot_id = src_id) from inv_trx where trx_id = ?",
                String.class, trx)).isEqualTo("24 EA / 2:true");
        assertThat(accounts.query(comp, "select trim_scale(l.qty_created)::text from inv_trx t join lot_master l on l.lot_id = t.lot_id where t.trx_id = ?",
                String.class, trx)).isEqualTo("24");
        assertThat(recvOf(l2)).isEqualTo("2");
        assertThat(stock(a1, resin)).isEqualByComparingTo("24");
    }

    @Test
    void 같은_요청을_두_번_보내면_SRM도_원장도_한_번만() throws Exception {
        long l1 = line(comp, issued(comp, admin), 1);
        String r = req();
        long t1 = trxId(receive(admin, a1, l1, "30", r, null).andExpect(status().isOk()).andReturn());
        receive(admin, a1, l1, "30", r, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.replay").value(true))
                .andExpect(jsonPath("$.data.trxId").value(t1));
        assertThat(recvOf(l1)).isEqualTo("30");
        assertThat(count("select count(*) from inv_trx where po_line_id = ?", l1)).isEqualTo(1);
    }

    // =====================================================================
    // 거부 — 아무것도 바뀌지 않는다
    // =====================================================================

    @Test
    void 초과_공장_품목이_틀리면_거부되고_SRM_LOT_원장_모두_그대로() throws Exception {
        long l1 = line(comp, issued(comp, admin), 1);
        int lots = count("select count(*) from lot_master");
        receive(admin, a1, l1, "101", req(), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SRM_RECV_OVER"));
        receive(admin, p2Loc, l1, "10", req(), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("poLineId")).andExpect(jsonPath("$.errors[0].code").value("PO_PLANT"));
        receive(admin, a1, l1, "10", req(), ",\"itemId\":" + resin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("PO_ITEM"));
        receive(admin, a1, 999999999L, "10", req(), null).andExpect(status().isNotFound());
        receive(admin, a1, l1, "0.5", req(), null).andExpect(status().isBadRequest());           // EA 자릿수 0 (SRM 판정)
        assertThat(recvOf(l1)).isEqualTo("0");
        assertThat(count("select count(*) from lot_master")).isEqualTo(lots);
        assertThat(count("select count(*) from inv_trx")).isZero();
    }

    // =====================================================================
    // 정정 · 대사
    // =====================================================================

    @Test
    void 역분개하면_SRM_입고수량도_되돌아가고_대사는_0_직접_SQL로_어긋나면_1() throws Exception {
        long po = issued(comp, admin);
        long l1 = line(comp, po, 1);
        long trx = trxId(receive(admin, a1, l1, "40", req(), null).andExpect(status().isOk()).andReturn());
        postJson(admin, "/api/wms/trx/" + trx + "/reverse", "{\"reason\":\"수량 잘못\"}").andExpect(status().isOk());
        assertThat(recvOf(l1)).isEqualTo("0");
        assertThat(poStatus(po)).isEqualTo("ISSUED");
        assertThat(accounts.query(comp, "select trim_scale(qty) || ':' || trim_scale(po_qty) || ':' || po_line_id from inv_trx where reversal_of_id = ?",
                String.class, trx)).isEqualTo("-40:-40:" + l1);
        assertThat(stock(a1, bolt)).isEqualByComparingTo("0");
        postJson(admin, "/api/wms/trx/" + trx + "/reverse", "{\"reason\":\"두 번\"}").andExpect(status().isBadRequest());
        assertThat(recvOf(l1)).isEqualTo("0");                                                   // 두 번째 되돌림은 SRM 도 안 바뀜
        postJson(admin, "/api/wms/stock/reconcile", "{}").andExpect(jsonPath("$.data.rcvPoMismatch").value(0));
        // 게이트를 거치지 않은 변경(직접 SQL) → 대사가 찾는다. SRM 은 입고 수량 = 이력 합을 커밋 때 검사하므로(SRM 2부 R2)
        // 이력과 입고 수량을 같이 바꿔 SRM 안은 맞고 원장과만 어긋나게 한다
        receive(admin, a1, l1, "10", req(), null).andExpect(status().isOk());
        accounts.exec(comp, """
                do $$ begin
                  insert into srm_po_receipt (comp_cd, po_id, po_line_id, occurred_on, qty, trx_ref, created_by)
                  values (hb_current_comp(), %d, %d, (now() at time zone 'Asia/Seoul')::date, 1, 'SQL-MISMATCH', 't');
                  update srm_po_line set recv_qty = 11 where po_line_id = %d;
                end $$""".formatted(po, l1, l1));
        postJson(admin, "/api/wms/stock/reconcile", "{}").andExpect(jsonPath("$.data.rcvPoMismatch").value(1));
    }

    @Test
    void 구매_입고는_SRM_입고_이력에_받은_LOT_를_남기고_역분개_행도_같은_LOT() throws Exception {
        long po = issued(comp, admin);
        long l1 = line(comp, po, 1);
        String r = req();
        long trx = trxId(receive(admin, a1, l1, "40", r, null).andExpect(status().isOk()).andReturn());
        long lot = accounts.query(comp, "select src_id from inv_trx where trx_id = ?", Long.class, trx);   // LOT 비관리 품목도 입고 LOT 는 출처로
        assertThat(accounts.query(comp, "select lot_id from srm_po_receipt where trx_ref = ?", Long.class, "RCV_PO:" + r)).isEqualTo(lot);
        // 같은 요청 다시(replay) — 이력 1행 그대로
        receive(admin, a1, l1, "40", r, null).andExpect(status().isOk());
        assertThat(count("select count(*) from srm_po_receipt where lot_id = ?", lot)).isEqualTo(1);
        postJson(admin, "/api/wms/trx/" + trx + "/reverse", "{\"reason\":\"수량 잘못\"}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select string_agg(trim_scale(qty) || ':' || lot_id, ',' order by receipt_id) from srm_po_receipt where po_line_id = ?",
                String.class, l1)).isEqualTo("40:" + lot + ",-40:" + lot);
        // 발주 없는 입고(RCV_MAT)는 SRM 이력이 없다
        int before = count("select count(*) from srm_po_receipt");
        postJson(admin, "/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + bolt
                + ",\"qty\":5,\"stockStatus\":\"AVAILABLE\",\"clientReqId\":\"" + req() + "\"}").andExpect(status().isOk());
        assertThat(count("select count(*) from srm_po_receipt")).isEqualTo(before);
    }

    @Test
    void 지난_날짜로_받은_입고를_다음_날_역분개해도_SRM_이력은_원래_입고일로_되돌린다() throws Exception {
        // SRM 2부 R2(docs/31 4.10): 이력 발생일 = 원장 발생 시각의 공장 날짜, 되돌림은 원래 입고의 날짜로(오늘 날짜로 되돌리면 "그날 순입고 초과" 409)
        long po;
        clock.setClock(Clock.offset(Clock.systemUTC(), Duration.ofDays(-2)));   // 발주일 = 이틀 전(발주일보다 앞선 입고는 SRM 이 400)
        try {
            po = issued(comp, admin);
        } finally {
            clock.setClock(Clock.systemUTC());
        }
        long l1 = line(comp, po, 1);
        String yesterday = accounts.query(comp, "select ((now() at time zone 'Asia/Seoul')::date - 1)::text", String.class);
        long trx = trxId(receive(admin, a1, l1, "40", req(), ",\"occurredAt\":\"" + yesterday + "T09:00\"").andExpect(status().isOk()).andReturn());
        assertThat(accounts.query(comp, "select occurred_on::text from srm_po_receipt where po_line_id = ?", String.class, l1)).isEqualTo(yesterday);
        postJson(admin, "/api/wms/trx/" + trx + "/reverse", "{\"reason\":\"수량 잘못\"}").andExpect(status().isOk());
        assertThat(recvOf(l1)).isEqualTo("0");
        assertThat(poStatus(po)).isEqualTo("ISSUED");
        assertThat(accounts.query(comp, "select string_agg(occurred_on || ' ' || trim_scale(qty), ', ' order by receipt_id) from srm_po_receipt where po_line_id = ?",
                String.class, l1)).isEqualTo(yesterday + " 40, " + yesterday + " -40");
    }

    @Test
    void 이미_출고해_재고가_모자라면_역분개는_409이고_SRM도_그대로() throws Exception {
        long l1 = line(comp, issued(comp, admin), 1);
        long trx = trxId(receive(admin, a1, l1, "40", req(), null).andExpect(status().isOk()).andReturn());
        accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_OUT', ?, ?, 'AVAILABLE', -35, 'EA', 'MANUAL', now(), 'KEY', 't')""", comp, bolt, a1);
        postJson(admin, "/api/wms/trx/" + trx + "/reverse", "{\"reason\":\"수량 잘못\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        assertThat(recvOf(l1)).isEqualTo("40");
    }

    // =====================================================================
    // 직접 SQL
    // =====================================================================

    @Test
    void 직접_SQL_발주_행_없는_RCV_PO_다른_유형의_발주_행_거울_아닌_역분개는_DB가_막는다() throws Exception {
        long l1 = line(comp, issued(comp, admin), 1);
        long trx = trxId(receive(admin, a1, l1, "40", req(), null).andExpect(status().isOk()).andReturn());
        long lot = accounts.query(comp, "select src_id from inv_trx where trx_id = ?", Long.class, trx);
        String ins = """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, src_id, occurred_at,
                                     entry_method, recorded_by, po_line_id, po_qty, reversal_of_id)
                values (?, 'P1', ?, ?, ?, 'AVAILABLE', ?, 'EA', 'MAT_RECEIPT', ?, now(), 'KEY', 't', ?, ?, ?)""";
        assertThat(constraint(() -> accounts.exec(comp, ins, comp, "RCV_PO", bolt, a1, 5, lot, null, null, null))).isEqualTo("ck_inv_trx_rcv_po");
        assertThat(constraint(() -> accounts.exec(comp, ins, comp, "RCV_MAT", bolt, a1, 5, lot, l1, 5, null))).isEqualTo("ck_inv_trx_rcv_po");
        assertThat(constraint(() -> accounts.exec(comp, ins, comp, "RCV_PO", bolt, a1, 5, lot, l1, -5, null))).isEqualTo("ck_inv_trx_rcv_po");
        // 발주 행 품목이 아닌 품목
        long nut = accounts.query(comp, "select item_id from mst_item where item_cd = 'NUT-M8'", Long.class);
        assertThat(constraint(() -> accounts.exec(comp, ins, comp, "RCV_PO", nut, a1, 5, lot, l1, 5, null))).isEqualTo("fk_inv_trx_po_line_comp");
        // 역분개가 발주 단위 수량을 거울로 되돌리지 않음
        assertThat(constraint(() -> accounts.exec(comp, ins, comp, "RCV_PO", bolt, a1, -40, lot, l1, -39, trx))).isEqualTo("ck_inv_trx_reversal_mirror");
        assertThat(constraint(() -> accounts.exec(comp, ins, comp, "RCV_PO", bolt, a1, -40, lot, null, null, trx))).isEqualTo("ck_inv_trx_reversal_mirror");
    }

    private static String constraint(Runnable r) {
        Throwable t = catchThrowable(r::run);
        assertThat(t).isNotNull();
        return PgErrors.constraint(t).orElse(t.getMessage());
    }

    // =====================================================================
    // 동시 · A사로 B사 · SRM 없음
    // =====================================================================

    @Test
    void 같은_발주_행에_동시에_60씩_받으면_하나만_넘고_하나는_409() throws Exception {
        long l1 = line(comp, issued(comp, admin), 1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> fs = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                String r = req();
                fs.add(pool.submit(() -> {
                    go.await();
                    return receive(admin, a1, l1, "60", r, null).andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : fs) {
                codes.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(recvOf(l1)).isEqualTo("60");
        assertThat(stock(a1, bolt)).isEqualByComparingTo("60");
        assertThat(count("select count(*) from inv_trx where po_line_id = ?", l1)).isEqualTo(1);
    }

    @Test
    void A사로_B사_발주_행에_입고할_수_없고_직접_SQL도_막힌다() throws Exception {
        String other = accounts.newComp("RQ");
        RequestPostProcessor ob = user(other, "buyer2");
        seed(other);
        long bl1 = line(other, issued(other, ob), 1);
        receive(admin, a1, bl1, "10", req(), null).andExpect(status().isNotFound());
        mvc.perform(get("/api/wms/rcv/po-lines").param("plantCd", "P1").param("itemId", String.valueOf(bolt)).with(admin))
                .andExpect(jsonPath("$.data.lines.length()").value(0));
        assertThat(accounts.query(other, "select trim_scale(recv_qty)::text from srm_po_line where po_line_id = ?", String.class, bl1)).isEqualTo("0");
        // 우리 회사 원장에 B사 발주 행을 직접 붙이기
        long own = trxId(receive(admin, a1, line(comp, issued(comp, admin), 1), "5", req(), null).andExpect(status().isOk()).andReturn());
        long lot = accounts.query(comp, "select src_id from inv_trx where trx_id = ?", Long.class, own);
        assertThat(constraint(() -> accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, src_id, occurred_at,
                                     entry_method, recorded_by, po_line_id, po_qty)
                values (?, 'P1', 'RCV_PO', ?, ?, 'AVAILABLE', 5, 'EA', 'MAT_RECEIPT', ?, now(), 'KEY', 't', ?, 5)""", comp, bolt, a1, lot, bl1)))
                .isEqualTo("fk_inv_trx_po_line_comp");
    }

    @Autowired
    StockService stockService;
    @Autowired
    InventoryService inventory;
    @Autowired
    InvMapper invMapper;
    @Autowired
    LotService lotService;
    @Autowired
    LabelPrintService labels;
    @Autowired
    LabelMapper labelMapper;
    @Autowired
    InboxService inbox;
    @Autowired
    PolicyService policy;
    @Autowired
    BizClock clock;
    @Autowired
    MessageSource messages;
    @Autowired
    QcReceiptRule qcRule;
    @Autowired
    PutawayCheck putaway;

    @Test
    void SRM_없는_설치본은_발주_칸이_꺼지고_발주_행을_보내면_칸_오류() {
        ObjectProvider<PoReceiptGate> noGate = new StaticListableBeanFactory().getBeanProvider(PoReceiptGate.class);
        ObjectProvider<PoSupplyQuery> noSupply = new StaticListableBeanFactory().getBeanProvider(PoSupplyQuery.class);
        ObjectProvider<io.smarthappy.module.srm.link.AsnQuery> noAsn = new StaticListableBeanFactory()
                .getBeanProvider(io.smarthappy.module.srm.link.AsnQuery.class);
        MaterialReceiptService bare = new MaterialReceiptService(stockService, inventory, invMapper, lotService, labels, labelMapper, inbox,
                policy, clock, messages, noGate, noSupply, noAsn, qcRule, putaway);
        assertThat(bare.poEnabled()).isFalse();
        assertThat(runner.call(comp, () -> bare.poLines("P1", bolt))).isEmpty();
        Throwable t = catchThrowable(() -> runner.call(comp, () -> bare.receive(new RcvForm("P1", a1, bolt, BigDecimal.TEN, "AVAILABLE", null, null,
                null, null, "KEY", req(), null, null, null, null, 1L, null))));
        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).fieldErrors()).anySatisfy(f -> assertThat(f.code()).isEqualTo("PO_UNAVAILABLE"));
        assertThatThrownBy(() -> PoReceiptGate.NONE.receive(1L, BigDecimal.ONE, "x", "y")).isInstanceOf(IllegalStateException.class);
    }
}
