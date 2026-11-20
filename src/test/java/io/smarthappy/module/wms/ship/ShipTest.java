package io.smarthappy.module.wms.ship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.home.HomeCard;
import io.smarthappy.module.home.HomeCardContext;
import io.smarthappy.module.wms.link.ShipQuery;
import io.smarthappy.module.wms.link.StockQuery;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 출하 (P4 2부 B6a, docs/30 12.7) — 수주 행 → 출하 지시 → 피킹(출발 칸 → STAGING 이동) → 출하 확정(영업 게이트·SHIP 원장·라벨·ERP).
 * 정상(전량·부분, LOT 관리 품목, 출하 수량 계약), 출하 취소·피킹 취소·지시 취소, 같은 요청 두 번, 거부(남은 수량·피킹 칸·초과·재고 부족·LOT·보류·
 * 영업 초과), 직접 SQL, 동시 확정, A사로 B사.
 */
@AutoConfigureMockMvc
class ShipTest extends PostgresTestBase {

    private static final String PW = "Harbor-violet-27";
    private static final String S = "/api/wms/ships";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    ShipQuery shipQuery;

    @Autowired
    StockQuery stockQuery;

    @Autowired
    ShipReserveCard reserveCard;

    @Autowired
    MessageSource messages;

    @Autowired
    BizClock clock;

    String comp;
    RequestPostProcessor admin;
    long a1;
    long stg;
    long scrap;
    long fg;
    long fgLot;
    long printer;
    int seq;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("SH");
        admin = user(comp, "admin1");
        seed(comp);
        long wh = node(comp, null, "WH", "FG-WH");
        a1 = node(comp, wh, "LOC", "A-01");
        stg = node(comp, wh, "LOC", "STG-01");
        long sw = node(comp, null, "WH", "SCRAP");
        scrap = node(comp, sw, "LOC", "S-01");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't')", stg, comp);
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'SCRAP', 't', 't')", sw, comp);
        fg = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG-100'", Long.class);
        fgLot = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG-L'", Long.class);
        printer = accounts.query(comp, """
                insert into wms_printer (comp_cd, plant_cd, printer_cd, printer_nm, host, usage, created_by, updated_by)
                values (?, 'P1', 'PR-SHIP', '출하 프린터', '10.0.0.6', 'SHIP', 't', 't') returning printer_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into wms_label_tpl (comp_cd, label_type, tpl_cd, tpl_nm, width_mm, height_mm, body, default_yn, created_by, updated_by)
                values (?, 'SHIP', 'SHIP-STD', '출하 기본', 100, 50, '^XA^FD{{shipNo}} {{itemCd}} {{qty}}^FS^XZ', 'Y', 't', 't')""", comp);
        // 재고: FG-100 A-01 30(조정), FG-L 은 자재 입고로 LOT 두 개(L1 8, L2 8)
        adjIn(comp, fg, a1, "30");
    }

    private RequestPostProcessor user(String c, String id) {
        long u = accounts.newUser(c, id, PW);
        accounts.grant(c, u, "SALES", "sal-order", "SEARCH", "SAVE");
        accounts.grant(c, u, "WMS", "wms-ship", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, u, "WMS", "wms-rcv", "SEARCH", "SAVE");
        return accounts.as(c, u, id);
    }

    private void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C-001', 'C-001 상사', 'SALES', 't', 't'), (?, 'C-002', 'C-002 상사', 'SALES', 't', 't'),
                       (?, 'SUP1', '공급사', 'PURCHASE', 't', 't')""", c, c, c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'FG-100', 'FG 품목', 'FG', 'MAKE', 'EA', 'N', 't', 't'), (?, 'FG-L', 'FG LOT 품목', 'FG', 'MAKE', 'EA', 'Y', 't', 't')""", c, c);
    }

    private long node(String c, Long parent, String type, String cd) {
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, c, parent, type, cd, cd);
    }

    private void adjIn(String c, long item, long node, String qty) {
        accounts.exec(c, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', ?::numeric, 'EA', 'MANUAL', now(), 'KEY', 't')""", c, item, node, qty);
    }

    private ResultActions postJson(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String req() {
        return "ship-" + (++seq) + "-" + System.nanoTime();
    }

    /** 자재 입고 API 로 LOT 하나 → LOT 번호 */
    private String lotIn(String qty) throws Exception {
        MvcResult m = postJson(admin, "/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + fgLot + ",\"qty\":" + qty
                + ",\"stockStatus\":\"AVAILABLE\",\"supplierCd\":\"SUP1\",\"clientReqId\":\"" + req() + "\"}").andExpect(status().isOk()).andReturn();
        return JsonPath.read(m.getResponse().getContentAsString(), "$.data.lotNo");
    }

    /** 수주(C-001) — 품목 코드·수량 짝, 행 번호 10, 20, … */
    private long order(RequestPostProcessor who, String cust, String... itemQty) throws Exception {
        List<String> ls = new ArrayList<>();
        for (int i = 0; i < itemQty.length; i += 2) {
            ls.add("{\"itemCd\":\"" + itemQty[i] + "\",\"qty\":\"" + itemQty[i + 1] + "\",\"dueDate\":\"2026-11-30\"}");
        }
        MvcResult m = postJson(who, "/api/sales/orders", "{\"custCd\":\"" + cust + "\",\"lines\":[" + String.join(",", ls) + "]}")
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private long oline(String c, long orderId, int no) {
        return accounts.query(c, "select line_id from sal_order_line where order_id = ? and line_no = ?", Long.class, orderId, no);
    }

    private String olineState(long lineId) {
        return accounts.query(comp, "select status || '/' || trim_scale(shipped_qty) from sal_order_line where line_id = ?", String.class, lineId);
    }

    /** 출하 지시 만들기 — (수주 행 id, 수량) 짝 */
    private ResultActions create(RequestPostProcessor who, String cust, Object... lineQty) throws Exception {
        List<String> ls = new ArrayList<>();
        for (int i = 0; i < lineQty.length; i += 2) {
            ls.add("{\"orderLineId\":" + lineQty[i] + ",\"qty\":" + lineQty[i + 1] + "}");
        }
        return postJson(who, S, "{\"plantCd\":\"P1\",\"custCd\":\"" + cust + "\",\"stageNodeId\":" + stg + ",\"planDate\":\"2026-11-20\",\"lines\":["
                + String.join(",", ls) + "]}");
    }

    private long shipId(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.head.shipId"))
                .longValue();
    }

    private int rowVer(long id) {
        return accounts.query(comp, "select row_ver from wms_ship where ship_id = ?", Integer.class, id);
    }

    private long sline(long shipId, int no) {
        return accounts.query(comp, "select ship_line_id from wms_ship_line where ship_id = ? and line_no = ?", Long.class, shipId, no);
    }

    /** 피킹 — (출하 줄 id, 출발 칸, LOT 번호 또는 null, 수량) 넷씩 */
    private ResultActions pick(long id, Object... p) throws Exception {
        return pickReq(id, req(), p);
    }

    private ResultActions pickReq(long id, String r, Object... p) throws Exception {
        List<String> ls = new ArrayList<>();
        for (int i = 0; i < p.length; i += 4) {
            ls.add("{\"shipLineId\":" + p[i] + ",\"fromNodeId\":" + p[i + 1] + ",\"lotNo\":" + (p[i + 2] == null ? "null" : "\"" + p[i + 2] + "\"")
                    + ",\"qty\":" + p[i + 3] + "}");
        }
        return postJson(admin, S + "/" + id + "/pick", "{\"rowVer\":" + rowVer(id) + ",\"clientReqId\":\"" + r + "\",\"picks\":[" + String.join(",", ls) + "]}");
    }

    private ResultActions confirm(long id, String r, String extra) throws Exception {
        return postJson(admin, S + "/" + id + "/confirm", "{\"rowVer\":" + rowVer(id) + ",\"clientReqId\":\"" + r + "\"" + (extra == null ? "" : extra) + "}");
    }

    private BigDecimal stock(long node, long item) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and item_id = ?", BigDecimal.class, node, item)
                .stripTrailingZeros();
    }

    private String shipStatus(long id) {
        return accounts.query(comp, "select status from wms_ship where ship_id = ?", String.class, id);
    }

    private static String constraint(Runnable r) {
        Throwable t = catchThrowable(r::run);
        assertThat(t).isNotNull();
        return PgErrors.constraint(t).orElse(t.getMessage());
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void 지시_피킹_확정으로_수주_행이_자동_마감되고_원장_라벨_ERP_출하수량_계약이_맞는다() throws Exception {
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'ERP_SYSTEM', 'FAKE', 't', 't')", comp);
        String l1 = lotIn("8");
        String l2 = lotIn("8");
        long o = order(admin, "C-001", "FG-100", "10", "FG-L", "12");
        long ol1 = oline(comp, o, 10);
        long ol2 = oline(comp, o, 20);
        mvc.perform(get(S + "/order-lines").param("custCd", "C-001").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[0].openQty").value(10));
        long id = shipId(create(admin, "C-001", ol1, 10, ol2, 12));
        assertThat(shipStatus(id)).isEqualTo("DRAFT");
        // 열린 지시에 잡힌 양은 다른 지시의 남은 수량에서 빠진다
        mvc.perform(get(S + "/order-lines").param("custCd", "C-001").with(admin)).andExpect(jsonPath("$.data.length()").value(0));
        // 피킹 제안: 차감 전략 미리보기(출하 대기 칸 제외)
        mvc.perform(get(S + "/" + id + "/lines/" + sline(id, 2) + "/suggest").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        pick(id, sline(id, 1), a1, null, 10, sline(id, 2), a1, l1, 8, sline(id, 2), a1, l2, 4).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.head.status").value("PICKING"));
        assertThat(stock(stg, fg)).isEqualByComparingTo("10");
        assertThat(stock(stg, fgLot)).isEqualByComparingTo("12");
        assertThat(stock(a1, fgLot)).isEqualByComparingTo("4");
        MvcResult m = confirm(id, req(), ",\"printerId\":" + printer).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.view.head.status").value("SHIPPED")).andExpect(jsonPath("$.data.labelJobs").value(2))
                .andExpect(jsonPath("$.data.replay").value(false)).andReturn();
        assertThat(m.getResponse().getContentAsString()).contains("\"warnings\":[]");
        assertThat(stock(stg, fg)).isEqualByComparingTo("0");
        assertThat(stock(stg, fgLot)).isEqualByComparingTo("0");
        assertThat(olineState(ol1)).isEqualTo("DONE/10");
        assertThat(olineState(ol2)).isEqualTo("DONE/12");
        assertThat(accounts.query(comp, "select status from sal_order where order_id = ?", String.class, o)).isEqualTo("CLOSED");
        // 원장 SHIP 3행(피킹마다), LOT 관리 품목은 LOT 이 붙는다
        assertThat(accounts.query(comp, "select count(*) from inv_trx where trx_type = 'SHIP' and src_type = 'SHIP_PICK'", Integer.class)).isEqualTo(3);
        assertThat(accounts.query(comp, "select count(*) from inv_trx where trx_type = 'SHIP' and item_id = ? and lot_id is not null", Integer.class, fgLot))
                .isEqualTo(2);
        // 출하 수량 계약 = 영업 투영
        Map<Long, BigDecimal> shipped = runner.call(comp, () -> shipQuery.shippedByLine(List.of(ol1, ol2, 999999L)));
        assertThat(shipped.get(ol1)).isEqualByComparingTo("10");
        assertThat(shipped.get(ol2)).isEqualByComparingTo("12");
        assertThat(shipped.get(999999L)).isEqualByComparingTo("0");
        // ERP 출하 전표: 줄마다 하나
        assertThat(accounts.query(comp, "select count(*) from sys_outbox where topic = 'ERP_SHIP'", Integer.class)).isEqualTo(2);
        assertThat(accounts.query(comp, "select count(*) from wms_print_job", Integer.class)).isEqualTo(2);
    }

    @Test
    void 부분_출하는_수주_행이_열린_채_남고_같은_확정_요청을_다시_보내도_한_번만() throws Exception {
        long o = order(admin, "C-001", "FG-100", "10");
        long ol = oline(comp, o, 10);
        long id = shipId(create(admin, "C-001", ol, 10));
        pick(id, sline(id, 1), a1, null, 6).andExpect(status().isOk());
        String r = req();
        confirm(id, r, null).andExpect(status().isOk());
        postJson(admin, S + "/" + id + "/confirm", "{\"rowVer\":0,\"clientReqId\":\"" + r + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay").value(true));
        assertThat(olineState(ol)).isEqualTo("OPEN/6");
        assertThat(stock(a1, fg)).isEqualByComparingTo("24");
        // 남은 4 는 새 지시로
        mvc.perform(get(S + "/order-lines").param("custCd", "C-001").with(admin)).andExpect(jsonPath("$.data[0].openQty").value(4));
    }

    // =====================================================================
    // 취소
    // =====================================================================

    @Test
    void 출하_취소는_수주_행을_다시_열고_STAGING_으로_돌리며_피킹_취소는_출발_칸으로_지시_취소는_DRAFT_만() throws Exception {
        long o = order(admin, "C-001", "FG-100", "10");
        long ol = oline(comp, o, 10);
        long id = shipId(create(admin, "C-001", ol, 10));
        pick(id, sline(id, 1), a1, null, 10).andExpect(status().isOk());
        confirm(id, req(), null).andExpect(status().isOk());
        assertThat(olineState(ol)).isEqualTo("DONE/10");
        postJson(admin, S + "/" + id + "/undo", "{\"rowVer\":" + rowVer(id) + ",\"clientReqId\":\"" + req() + "\",\"reason\":\"고객 요청 보류\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.view.head.status").value("PICKING"));
        assertThat(olineState(ol)).isEqualTo("OPEN/0");
        assertThat(stock(stg, fg)).isEqualByComparingTo("10");
        Map<Long, BigDecimal> shipped = runner.call(comp, () -> shipQuery.shippedByLine(List.of(ol)));
        assertThat(shipped.get(ol)).isEqualByComparingTo("0");
        // 피킹 취소 → 출발 칸으로, 남은 피킹 없음 → DRAFT → 지시 취소
        long pickId = accounts.query(comp, "select pick_id from wms_ship_pick where ship_line_id = ?", Long.class, sline(id, 1));
        postJson(admin, S + "/" + id + "/cancel", "{\"rowVer\":" + rowVer(id) + ",\"reason\":\"취소\"}").andExpect(status().isConflict());
        postJson(admin, S + "/" + id + "/unpick", "{\"rowVer\":" + rowVer(id) + ",\"pickIds\":[" + pickId + "],\"reason\":\"잘못 피킹\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.head.status").value("DRAFT"));
        assertThat(stock(a1, fg)).isEqualByComparingTo("30");
        assertThat(stock(stg, fg)).isEqualByComparingTo("0");
        postJson(admin, S + "/" + id + "/cancel", "{\"rowVer\":" + rowVer(id) + ",\"reason\":\"취소\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.head.status").value("CANCELLED"));
        // 취소한 지시는 남은 수량에서 빠지지 않는다
        mvc.perform(get(S + "/order-lines").param("custCd", "C-001").with(admin)).andExpect(jsonPath("$.data[0].openQty").value(10));
    }

    // =====================================================================
    // 거부
    // =====================================================================

    @Test
    void B_남은_수량_다른_고객_피킹_칸_초과_LOT_재고_부족은_칸_오류나_409() throws Exception {
        String l1 = lotIn("8");
        long o = order(admin, "C-001", "FG-100", "10", "FG-L", "8");
        long o2 = order(admin, "C-002", "FG-100", "5");
        long ol1 = oline(comp, o, 10);
        long ol2 = oline(comp, o, 20);
        create(admin, "C-001", ol1, 11).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("SHIP_OVER_OPEN"));
        create(admin, "C-001", oline(comp, o2, 10), 1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        create(admin, "C-001", ol1, 1, ol1, 1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        postJson(admin, S, "{\"plantCd\":\"P1\",\"custCd\":\"C-001\",\"stageNodeId\":" + a1 + ",\"planDate\":\"2026-11-20\",\"lines\":[{\"orderLineId\":"
                + ol1 + ",\"qty\":1}]}").andExpect(jsonPath("$.errors[0].code").value("NOT_STAGING"));
        long id = shipId(create(admin, "C-001", ol1, 10, ol2, 8));
        pick(id, sline(id, 1), stg, null, 1).andExpect(jsonPath("$.errors[0].code").value("NOT_PICKABLE"));
        pick(id, sline(id, 1), scrap, null, 1).andExpect(jsonPath("$.errors[0].code").value("NOT_PICKABLE"));
        pick(id, sline(id, 1), a1, null, 11).andExpect(jsonPath("$.errors[0].code").value("PICK_OVER"));
        pick(id, sline(id, 2), a1, null, 1).andExpect(jsonPath("$.errors[0].code").value("LOT_REQUIRED"));
        pick(id, sline(id, 2), a1, "NO-SUCH", 1).andExpect(jsonPath("$.errors[0].code").value("LOT_MISMATCH"));
        // 재고 부족: FG-100 은 30 — 지시 10 이라 초과 못 함 → LOT 품목 8 중 L1 8 을 넘게
        pick(id, sline(id, 2), a1, l1, 8).andExpect(status().isOk());
        assertThat(shipStatus(id)).isEqualTo("PICKING");
        // 보류 LOT 은 확정에서 막힌다(피킹 뒤 보류)
        accounts.exec(comp, "insert into lot_hold (comp_cd, lot_id, source, reason, held_by) select comp_cd, lot_id, 'MANUAL', '품질 확인', 't' "
                + "from lot_master where lot_no = ?", l1);
        confirm(id, req(), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("LOT_HOLD"));
        assertThat(olineState(ol2)).isEqualTo("OPEN/0");                                         // 영업 게이트도 롤백
        assertThat(shipStatus(id)).isEqualTo("PICKING");
    }

    /**
     * A-1(사용자 결정 2026-10-30): 가용 정의 하나(AvailSql.available)에 "보류 아닌 LOT" — 제안(차감 미리보기)·다른 칸 목록·APS 가용이 같은 규칙.
     * "목록에 있는 칸 = 피킹(post)이 받는 칸": 보류 LOT 은 목록에 없고, 직접 보내도 원장이 거부한다
     */
    @Test
    void A1_보류_LOT_은_제안_다른칸_목록_가용에서_빠지고_직접_피킹도_거부() throws Exception {
        String l1 = lotIn("8");
        String l2 = lotIn("8");
        accounts.exec(comp, "insert into lot_hold (comp_cd, lot_id, source, reason, held_by) select comp_cd, lot_id, 'MANUAL', '품질 확인', 't' "
                + "from lot_master where lot_no = ?", l1);
        long o = order(admin, "C-001", "FG-L", "12");
        long id = shipId(create(admin, "C-001", oline(comp, o, 10), 12));
        long line = sline(id, 1);
        // 제안: L2 8 만, 모자람 4 — L1 은 HOLD 로 건너뜀
        mvc.perform(get(S + "/" + id + "/lines/" + line + "/suggest").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].lotNo").value(l2))
                .andExpect(jsonPath("$.data[0].takeQty").value("8"));
        // 다른 칸 목록: L1 없음
        mvc.perform(get(S + "/" + id + "/lines/" + line + "/stock").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[?(@.lotNo == '" + l1 + "')]").isEmpty())
                .andExpect(jsonPath("$.data.rows[?(@.lotNo == '" + l2 + "')]").isNotEmpty());
        // APS 가용(StockQuery) = 8, 예약 카드도 같은 정의
        assertThat(runner.call(comp, () -> stockQuery.available("P1", List.of(fgLot))).get(fgLot)).isEqualByComparingTo("8");
        // 목록에 없는 L1 을 직접 보내면 원장이 거부(이동 출고 보류 검사) — 재고·피킹 그대로
        pick(id, line, a1, l1, 1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("LOT_HOLD"));
        assertThat(accounts.query(comp, "select count(*) from wms_ship_pick", Integer.class)).isZero();
        pick(id, line, a1, l2, 8).andExpect(status().isOk());
        // 보류를 풀면 다시 보인다
        accounts.exec(comp, "update lot_hold set released_at = now(), released_by = 't', release_reason = '확인 끝' where lot_id = "
                + "(select lot_id from lot_master where lot_no = ?)", l1);
        mvc.perform(get(S + "/" + id + "/lines/" + line + "/suggest").with(admin))
                .andExpect(jsonPath("$.data[0].lotNo").value(l1));
        // A사로 B사: B사 범위의 가용은 A사 품목 id 로 0
        String b = accounts.newComp("SI");
        assertThat(runner.call(b, () -> stockQuery.available("P1", List.of(fgLot))).get(fgLot)).isEqualByComparingTo("0");
    }

    /**
     * U-1(사용자 결정 2026-10-30): 출하 취소(undo)도 지시 만들기와 같은 수주 행 잠금을 잡고, 다시 열면 열린 지시가 남은 수량을 넘을 때 409.
     * 수주 20 · 지시 A 10 중 4 출하 → 남은 16 으로 지시 B 16 → A 출하 취소 = 열린 지시 26 &gt; 20 → 409, 모두 그대로
     */
    @Test
    void U1_부분_출하_뒤_나머지_지시가_있으면_출하_취소는_409_그대로() throws Exception {
        long o = order(admin, "C-001", "FG-100", "20");
        long ol = oline(comp, o, 10);
        long a = shipId(create(admin, "C-001", ol, 10));
        pick(a, sline(a, 1), a1, null, 4).andExpect(status().isOk());
        confirm(a, req(), null).andExpect(status().isOk());
        assertThat(olineState(ol)).isEqualTo("OPEN/4");
        shipId(create(admin, "C-001", ol, 16));
        postJson(admin, S + "/" + a + "/undo", "{\"rowVer\":" + rowVer(a) + ",\"clientReqId\":\"" + req() + "\",\"reason\":\"고객 요청\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_SHIP_UNDO_OVER"));
        assertThat(shipStatus(a)).isEqualTo("SHIPPED");
        assertThat(olineState(ol)).isEqualTo("OPEN/4");                                          // 영업 게이트도 롤백
        assertThat(stock(stg, fg)).isEqualByComparingTo("0");                                     // SHIP 역분개도 롤백
        // B 를 줄이면(취소) 출하 취소가 된다
        long b = accounts.query(comp, "select max(ship_id) from wms_ship", Long.class);
        postJson(admin, S + "/" + b + "/cancel", "{\"rowVer\":" + rowVer(b) + ",\"reason\":\"다시 잡음\"}").andExpect(status().isOk());
        postJson(admin, S + "/" + a + "/undo", "{\"rowVer\":" + rowVer(a) + ",\"clientReqId\":\"" + req() + "\",\"reason\":\"고객 요청\"}")
                .andExpect(status().isOk());
        assertThat(olineState(ol)).isEqualTo("OPEN/0");
    }

    /** U-1 동시: 출하 취소와 나머지 지시 만들기를 동시에 — 둘 다 수주 행 잠금에서 줄 서고, 하나만 된다(열린 지시 ≤ 수주) */
    @Test
    void U1_출하_취소와_지시_만들기를_동시에_하면_하나만_되고_열린_지시는_수주를_넘지_않는다() throws Exception {
        long o = order(admin, "C-001", "FG-100", "20");
        long ol = oline(comp, o, 10);
        long a = shipId(create(admin, "C-001", ol, 10));
        pick(a, sline(a, 1), a1, null, 4).andExpect(status().isOk());
        confirm(a, req(), null).andExpect(status().isOk());
        int ver = rowVer(a);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (java.sql.Connection hold = java.sql.DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword());
             java.sql.Statement st = hold.createStatement()) {
            hold.setAutoCommit(false);
            // 만들기는 수주 행 잠금을 잡은 뒤 채번에서 기다리게 — 그동안 출하 취소는 같은 수주 행 잠금에서 기다린다
            st.execute("select 1 from sys_numbering_seq where comp_cd = '" + comp + "' and rule_cd = 'WMS_SHIP' for update");
            Future<Integer> mk = pool.submit(() -> create(admin, "C-001", ol, 16).andReturn().getResponse().getStatus());
            Thread.sleep(300);
            Future<Integer> un = pool.submit(() -> postJson(admin, S + "/" + a + "/undo", "{\"rowVer\":" + ver + ",\"clientReqId\":\"" + req()
                    + "\",\"reason\":\"동시\"}").andReturn().getResponse().getStatus());
            Thread.sleep(500);
            hold.commit();
            int s1 = mk.get(30, TimeUnit.SECONDS);
            int s2 = un.get(30, TimeUnit.SECONDS);
            // 만들기가 먼저 끝나면 출하 취소 409(WMS_SHIP_UNDO_OVER), 출하 취소가 먼저면 만들기 400(SHIP_OVER_OPEN) — 어느 쪽이든 하나만
            assertThat(List.of(s1, s2)).as("하나만 성공 (만들기, 출하 취소)").containsOnlyOnce(200);
            assertThat(s1 == 200 ? s2 : s1).isIn(400, 409);
        } finally {
            pool.shutdownNow();
        }
        BigDecimal open = accounts.query(comp, """
                select coalesce(sum(l.qty), 0) from wms_ship_line l join wms_ship s on s.ship_id = l.ship_id
                 where l.order_line_id = ? and s.status in ('DRAFT', 'PICKING')""", BigDecimal.class, ol);
        BigDecimal shipped = accounts.query(comp, "select shipped_qty from sal_order_line where line_id = ?", BigDecimal.class, ol);
        assertThat(open.add(shipped)).as("열린 지시 + 출하 ≤ 수주").isLessThanOrEqualTo(new BigDecimal("20"));
    }

    @Test
    void B_재고가_모자라면_피킹은_409이고_피킹_행도_남지_않는다() throws Exception {
        long o = order(admin, "C-001", "FG-100", "40");
        long id = shipId(create(admin, "C-001", oline(comp, o, 10), 40));
        pick(id, sline(id, 1), a1, null, 31).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        assertThat(accounts.query(comp, "select count(*) from wms_ship_pick", Integer.class)).isZero();
        assertThat(shipStatus(id)).isEqualTo("DRAFT");
    }

    @Test
    void B_수주가_줄어_출하가_초과되면_영업_게이트_409이고_아무것도_바뀌지_않는다() throws Exception {
        long o = order(admin, "C-001", "FG-100", "10");
        long ol = oline(comp, o, 10);
        long id = shipId(create(admin, "C-001", ol, 10));
        pick(id, sline(id, 1), a1, null, 10).andExpect(status().isOk());
        accounts.exec(comp, "update sal_order_line set qty = 5 where line_id = ?", ol);           // 지시 뒤 수주 감량(영업 화면 모양)
        confirm(id, req(), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OVER_SHIP"));
        assertThat(olineState(ol)).isEqualTo("OPEN/0");
        assertThat(stock(stg, fg)).isEqualByComparingTo("10");
        assertThat(accounts.query(comp, "select count(*) from inv_trx where trx_type = 'SHIP'", Integer.class)).isZero();
    }

    // =====================================================================
    // 직접 SQL
    // =====================================================================

    @Test
    void 직접_SQL_출처_없는_SHIP_상태_건너뛰기_DRAFT_아닌_지시에_줄_추가는_DB가_막는다() throws Exception {
        long o = order(admin, "C-001", "FG-100", "10", "FG-100", "3");
        long id = shipId(create(admin, "C-001", oline(comp, o, 10), 10));
        assertThat(constraint(() -> accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at, entry_method, recorded_by)
                values (?, 'P1', 'SHIP', ?, ?, 'AVAILABLE', -1, 'EA', 'MANUAL', now(), 'KEY', 't')""", comp, fg, a1))).isEqualTo("ck_inv_trx_ship_src");
        assertThat(constraint(() -> accounts.exec(comp, "update wms_ship set status = 'SHIPPED', shipped_at = now(), shipped_by = 't' where ship_id = ?", id)))
                .isEqualTo("ck_wms_ship_transition");
        pick(id, sline(id, 1), a1, null, 2).andExpect(status().isOk());
        assertThat(constraint(() -> accounts.exec(comp, "insert into wms_ship_line (comp_cd, ship_id, line_no, order_line_id, item_id, qty, created_by, updated_by) "
                + "values (?, ?, 2, ?, ?, 1, 't', 't')", comp, id, oline(comp, o, 20), fg))).isEqualTo("ck_wms_ship_line_draft");
        long pickId = accounts.query(comp, "select pick_id from wms_ship_pick where ship_line_id = ?", Long.class, sline(id, 1));
        assertThat(constraint(() -> accounts.exec(comp, "update wms_ship_pick set qty = 3 where pick_id = ?", pickId))).isEqualTo("ck_wms_ship_pick_transition");
        assertThat(constraint(() -> accounts.exec(comp, "update wms_ship set stage_node_id = ? where ship_id = ?", a1, id))).isEqualTo("ck_wms_ship_fixed");
    }

    // =====================================================================
    // 동시 · A사로 B사
    // =====================================================================

    @Test
    void C_같은_지시를_동시에_두_번_확정하면_하나만_되고_출하는_한_번() throws Exception {
        long o = order(admin, "C-001", "FG-100", "10");
        long ol = oline(comp, o, 10);
        long id = shipId(create(admin, "C-001", ol, 10));
        pick(id, sline(id, 1), a1, null, 10).andExpect(status().isOk());
        int rv = rowVer(id);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> fs = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                String r = req();
                fs.add(pool.submit(() -> {
                    go.await();
                    return postJson(admin, S + "/" + id + "/confirm", "{\"rowVer\":" + rv + ",\"clientReqId\":\"" + r + "\"}").andReturn().getResponse()
                            .getStatus();
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
        assertThat(olineState(ol)).isEqualTo("DONE/10");
        assertThat(accounts.query(comp, "select count(*) from inv_trx where trx_type = 'SHIP'", Integer.class)).isEqualTo(1);
    }

    Map<Long, BigDecimal> reserved(String c, String plant, Long... items) {
        return runner.call(c, () -> stockQuery.reservedByShip(plant, List.of(items)));
    }

    /** 홈 카드 R1(docs/37 22.2 ⑦) — 출하 화면 조회 권한이 있어야 "예약 초과 품목" 카드가 보인다. 저장만으로는 안 보인다 */
    @Test
    void 예약_초과_카드는_출하_조회_권한이_있을_때만() throws Exception {
        long u = accounts.newUser(comp, "home1", PW);
        accounts.grant(comp, u, "HOME_ONLY", io.smarthappy.module.home.HomeService.PROG, "SEARCH");
        RequestPostProcessor who = accounts.as(comp, u, "home1");
        mvc.perform(get("/api/home/cards").param("plant", "P1").with(who)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cards[*].code").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("wms-ship-reserve"))));
        accounts.grant(comp, u, "HOME_ONLY", "wms-ship", "SAVE");
        mvc.perform(get("/api/home/cards").param("plant", "P1").with(who))
                .andExpect(jsonPath("$.data.cards[*].code").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("wms-ship-reserve"))));
        accounts.grant(comp, u, "HOME_ONLY", "wms-ship", "SEARCH");
        mvc.perform(get("/api/home/cards").param("plant", "P1").with(who))
                .andExpect(jsonPath("$.data.cards[*].code").value(org.hamcrest.Matchers.hasItem("wms-ship-reserve")));
    }

    HomeCard card(String c, String plant) {
        // 카드 문맥 = 서버 시계(BizClock) — 시험 JVM(UTC) now 로 만들면 공장 날짜와 어긋날 수 있다(PdaLocCheckTest 5a9ecac3 와 같게)
        return runner.call(c, () -> reserveCard.load(new HomeCardContext(ZonedDateTime.now(clock.zone()), 0L, Locale.KOREAN, messages, plant,
                plant == null ? null : ZonedDateTime.now(clock.zone(plant)))));
    }

    /**
     * docs/53 (가) 계산형 소프트 예약(2026-10-29 L1-1·L1-3) — 예약 = 열린 지시(DRAFT·PICKING) 줄의 미피킹. 두 지시가 같은 품목(가용 30)에
     * 20 씩: 둘째도 저장되고(경고만) 줄마다 가용·다른 지시 예약·순 가용·모자람. 지시 만들기 목록(공장을 보내면)도 같은 숫자.
     * 피킹·피킹 취소·지시 취소가 바로 반영(새 표 없음). 계약 StockQuery.reservedByShip — 다른 공장·A사로 B사 0
     */
    @Test
    void 소프트_예약은_다른_지시의_미피킹을_빼서_경고만_하고_피킹_취소_지시_취소가_바로_반영된다() throws Exception {
        long o1 = order(admin, "C-001", "FG-100", "20");
        mvc.perform(get(S + "/order-lines").param("custCd", "C-001").param("plantCd", "p1").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].availQty").value(30)).andExpect(jsonPath("$.data[0].reservedQty").value(0))
                .andExpect(jsonPath("$.data[0].netQty").value(30));
        mvc.perform(get(S + "/order-lines").param("custCd", "C-001").with(admin))                    // 공장 없으면 숫자 없음
                .andExpect(jsonPath("$.data[0].netQty").doesNotExist());
        long a = shipId(create(admin, "C-001", oline(comp, o1, 10), 20));
        long o2 = order(admin, "C-002", "FG-100", "20");
        mvc.perform(get(S + "/order-lines").param("custCd", "C-002").param("plantCd", "P1").with(admin))
                .andExpect(jsonPath("$.data[0].reservedQty").value(20)).andExpect(jsonPath("$.data[0].netQty").value(10));
        ResultActions rb = create(admin, "C-002", oline(comp, o2, 10), 20).andExpect(status().isOk())   // 모자라도 저장(L1-3)
                .andExpect(jsonPath("$.data.lines[0].availQty").value(30)).andExpect(jsonPath("$.data.lines[0].reservedOther").value(20))
                .andExpect(jsonPath("$.data.lines[0].netQty").value(10)).andExpect(jsonPath("$.data.lines[0].shortQty").value(10));
        long b = shipId(rb);
        mvc.perform(get(S + "/" + a).with(admin)).andExpect(jsonPath("$.data.lines[0].reservedOther").value(20))
                .andExpect(jsonPath("$.data.lines[0].shortQty").value(10));
        assertThat(reserved(comp, "P1", fg, fgLot)).containsEntry(fg, new BigDecimal("40.000000")).containsEntry(fgLot, BigDecimal.ZERO);
        HomeCard c = card(comp, "P1");
        assertThat(c.count()).isEqualTo(1);
        assertThat(c.items().get(0).title()).isEqualTo("FG-100 FG 품목");
        assertThat(c.items().get(0).detail()).contains("예약 40").contains("가용 30").contains("10 EA");
        // 피킹 5(A) — 그만큼 예약이 풀리고 가용도 준다(출하 대기 칸은 가용 아님)
        pick(a, sline(a, 1), a1, null, 5).andExpect(status().isOk());
        assertThat(reserved(comp, "P1", fg).get(fg)).isEqualByComparingTo("35");
        mvc.perform(get(S + "/" + b).with(admin)).andExpect(jsonPath("$.data.lines[0].availQty").value(25))
                .andExpect(jsonPath("$.data.lines[0].reservedOther").value(15)).andExpect(jsonPath("$.data.lines[0].shortQty").value(10));
        long pickId = accounts.query(comp, "select pick_id from wms_ship_pick where ship_line_id = ?", Long.class, sline(a, 1));
        postJson(admin, S + "/" + a + "/unpick", "{\"rowVer\":" + rowVer(a) + ",\"pickIds\":[" + pickId + "],\"reason\":\"되돌림\"}")
                .andExpect(status().isOk());
        assertThat(reserved(comp, "P1", fg).get(fg)).isEqualByComparingTo("40");
        // 지시 취소 — 바로 풀림, 취소한 지시에는 숫자 없음
        postJson(admin, S + "/" + b + "/cancel", "{\"rowVer\":" + rowVer(b) + ",\"reason\":\"취소\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].availQty").doesNotExist());
        assertThat(reserved(comp, "P1", fg).get(fg)).isEqualByComparingTo("20");
        mvc.perform(get(S + "/" + a).with(admin)).andExpect(jsonPath("$.data.lines[0].reservedOther").value(0))
                .andExpect(jsonPath("$.data.lines[0].shortQty").value(0));
        assertThat(card(comp, "P1").count()).isZero();
        // 다 피킹하면 예약 0(피킹이 곧 해제)
        pick(a, sline(a, 1), a1, null, 20).andExpect(status().isOk());
        assertThat(reserved(comp, "P1", fg).get(fg)).isEqualByComparingTo("0");
        // 다른 공장·A사로 B사·WMS 없음 = 0
        assertThat(reserved(comp, "P2", fg).get(fg)).isEqualByComparingTo("0");
        String other = accounts.newComp("SR");
        accounts.addPlant(other, "P1", "B 1공장");
        long b2 = shipId(create(admin, "C-001", oline(comp, order(admin, "C-001", "FG-100", "50"), 10), 50));
        assertThat(reserved(comp, "P1", fg).get(fg)).isEqualByComparingTo("50");
        assertThat(reserved(other, "P1", fg).get(fg)).isEqualByComparingTo("0");
        assertThat(card(other, "P1").count()).isZero();
        assertThat(card(comp, null).count()).isEqualTo(1);                                   // 공장 선택 없음 = 전체
        assertThat(card(comp, "P2").count()).isZero();
        assertThat(StockQuery.NONE.reservedByShip("P1", List.of(fg))).containsEntry(fg, BigDecimal.ZERO);
        assertThat(b2).isPositive();
    }

    /**
     * docs/53 F-a(2026-10-29) — 가용 정의 하나(AvailSql): 검사 용도(QC) 칸의 AVAILABLE 재고는 다른 칸 목록·피킹 제안·피킹 출발 칸에서 빠진다.
     * A사로 B사 칸을 출발 칸으로 보내면 NOT_FOUND
     */
    @Test
    void 검사_용도_칸의_AVAILABLE_재고는_다른_칸_목록_제안_피킹에서_빠지고_B사_칸은_NOT_FOUND() throws Exception {
        long wh = accounts.query(comp, "select parent_id from mst_equip_hier where node_id = ?", Long.class, a1);
        long qcLoc = node(comp, wh, "LOC", "QC-01");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'QC', 't', 't')", qcLoc, comp);
        adjIn(comp, fg, qcLoc, "20");
        long o = order(admin, "C-001", "FG-100", "40");
        long id = shipId(create(admin, "C-001", oline(comp, o, 10), 40));
        long l = sline(id, 1);
        mvc.perform(get(S + "/" + id + "/lines/" + l + "/stock").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(1)).andExpect(jsonPath("$.data.rows[0].nodeId").value(a1));
        mvc.perform(get(S + "/" + id + "/lines/" + l + "/suggest").with(admin)).andExpect(status().isOk())   // A-01 30 만 — 모자라도 QC 칸은 안 씀
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].nodeId").value(a1));
        pick(id, l, qcLoc, null, 1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_PICKABLE"));
        String b = accounts.newComp("SB");
        accounts.addPlant(b, "P1", "B 1공장");
        long bWh = node(b, null, "WH", "B-WH");
        pick(id, l, node(b, bWh, "LOC", "B-01"), null, 1).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        assertThat(stock(qcLoc, fg)).isEqualByComparingTo("20");
    }

    /**
     * 12.7.5 다른 칸 — 줄 품목의 가용 칸 전부(출하 대기·폐기 위치 제외), LOC: 스캔은 그 위치(하위 포함)로, 다른 품목 스캔은 빈 목록 + 안내.
     * 제안 밖 칸에서 피킹하면 그 칸에서 나간다. 권한 없음 403, A사로 B사 404
     */
    @Test
    void 다른_칸은_제안_밖_가용_칸을_보이고_스캔으로_좁히며_고른_칸에서_피킹된다() throws Exception {
        long wh = accounts.query(comp, "select parent_id from mst_equip_hier where node_id = ?", Long.class, a1);
        long a2 = node(comp, wh, "LOC", "A-02");
        adjIn(comp, fg, a2, "5");
        adjIn(comp, fg, stg, "3");                                           // 출하 대기 — 안 보임
        adjIn(comp, fg, scrap, "2");                                         // 폐기 창고 아래 — 안 보임
        long o = order(admin, "C-001", "FG-100", "10");
        long id = shipId(create(admin, "C-001", oline(comp, o, 10), 4));
        long l = sline(id, 1);
        String url = S + "/" + id + "/lines/" + l + "/stock";
        mvc.perform(get(url).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                .andExpect(jsonPath("$.data.rows[?(@.nodeId == " + a2 + ")].qty").value(org.hamcrest.Matchers.contains(5.0)))
                .andExpect(jsonPath("$.data.rows[?(@.nodeId == " + stg + " || @.nodeId == " + scrap + ")]").isEmpty());
        mvc.perform(get(url).param("scan", "LOC:P1:A-02").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(1)).andExpect(jsonPath("$.data.rows[0].nodeId").value(a2));
        mvc.perform(get(url).param("scan", "LOC:P1:FG-WH").with(admin)).andExpect(jsonPath("$.data.rows.length()").value(2));   // 하위 포함
        mvc.perform(get(url).param("scan", "ITEM:FG-L").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(0)).andExpect(jsonPath("$.data.message").isNotEmpty());
        mvc.perform(get(url).param("scan", "LOC:P1:NOPE").with(admin)).andExpect(jsonPath("$.data.rows.length()").value(0));
        // 제안(A-01 30)이 아닌 A-02 에서 4 — 서버 피킹 규칙 그대로
        pick(id, l, a2, null, 4).andExpect(status().isOk());
        assertThat(stock(a2, fg)).isEqualByComparingTo("1");
        assertThat(stock(a1, fg)).isEqualByComparingTo("30");
        // 출하 대기 칸을 직접 고르면 서버가 거부(화면 목록은 도움일 뿐)
        long id2 = shipId(create(admin, "C-001", oline(comp, o, 10), 2));
        pick(id2, sline(id2, 1), stg, null, 1).andExpect(status().isBadRequest());
        // 권한 없음 · A사로 B사
        long u = accounts.newUser(comp, "noship", PW);
        accounts.grant(comp, u, "NOSHIP", "sal-order", "SEARCH");
        mvc.perform(get(url).with(accounts.as(comp, u, "noship"))).andExpect(status().isForbidden());
        String other = accounts.newComp("SR");
        RequestPostProcessor ob = user(other, "admin3");
        seed(other);
        mvc.perform(get(url).with(ob)).andExpect(status().isNotFound());
    }

    @Test
    void A사로_B사_수주_행과_지시는_보이지_않고_직접_SQL_도_막힌다() throws Exception {
        String other = accounts.newComp("SQ");
        RequestPostProcessor ob = user(other, "admin2");
        seed(other);
        long bo = order(ob, "C-001", "FG-100", "5");
        long bol = oline(other, bo, 10);
        create(admin, "C-001", bol, 1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        long o = order(admin, "C-001", "FG-100", "10");
        long id = shipId(create(admin, "C-001", oline(comp, o, 10), 10));
        mvc.perform(get(S + "/" + id).with(ob)).andExpect(status().isNotFound());
        postJson(ob, S + "/" + id + "/confirm", "{\"rowVer\":0,\"clientReqId\":\"" + req() + "\"}").andExpect(status().isNotFound());
        assertThat(constraint(() -> accounts.exec(comp, "insert into wms_ship_line (comp_cd, ship_id, line_no, order_line_id, item_id, qty, created_by, updated_by) "
                + "values (?, ?, 2, ?, ?, 1, 't', 't')", comp, id, bol, fg))).isIn("fk_wms_ship_line_order_comp", "ck_wms_ship_line_draft");
        long ol = oline(comp, o, 10);
        Map<Long, BigDecimal> m = runner.call(other, () -> shipQuery.shippedByLine(List.of(ol)));
        assertThat(m.values()).allSatisfy(v -> assertThat(v).isEqualByComparingTo("0"));
    }

    @Test
    void SHIP_QUERY_NONE은_요청한_행을_0으로() {
        assertThat(ShipQuery.NONE.shippedByLine(List.of(1L, 2L))).containsEntry(1L, BigDecimal.ZERO).containsEntry(2L, BigDecimal.ZERO);
    }
}
