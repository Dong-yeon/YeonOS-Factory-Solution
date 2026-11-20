package io.smarthappy.module.wms.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
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

import io.smarthappy.module.wms.link.ShipQuery.LotShipment;
import io.smarthappy.module.wms.link.ShipQuery.LotStockRow;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 출하 추적 계약 (docs/57) — ShipQuery.byLots(LOT → 고객·출하·순 수량·팔레트)·lotStock(LOT 이 남은 칸·상태, 가용 정의 없이).
 * 출하 취소분은 순 수량으로 빠지고, 다시 확정하면 한 줄. 비관리 품목은 안 나온다. 입력 규칙(중복·null·상한), A사로 B사, NONE.
 */
@AutoConfigureMockMvc
class ShipTraceQueryTest extends PostgresTestBase {

    private static final String PW = "Cedar-signal-48";
    private static final String S = "/api/wms/ships";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    ShipQuery shipQuery;

    String comp;
    RequestPostProcessor admin;
    long a1;
    long a2;
    long qc;
    long stg;
    long fg;
    long fgLot;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("ST");
        admin = user(comp);
        seed(comp);
        long wh = node(comp, null, "WH", "FG-WH");
        a1 = node(comp, wh, "LOC", "A-01");
        a2 = node(comp, wh, "LOC", "A-02");
        qc = node(comp, wh, "LOC", "QC-01");
        stg = node(comp, wh, "LOC", "STG-01");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't')", stg, comp);
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'QC', 't', 't')", qc, comp);
        fg = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG-100'", Long.class);
        fgLot = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG-L'", Long.class);
        accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at, entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', 30, 'EA', 'MANUAL', now() - interval '1 hour', 'KEY', 't')""", comp, fg, a1);
    }

    // ---------- 도우미 ----------

    private RequestPostProcessor user(String c) {
        long u = accounts.newUser(c, "admin1", PW);
        accounts.grant(c, u, "SALES", "sal-order", "SEARCH", "SAVE");
        accounts.grant(c, u, "WMS", "wms-ship", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, u, "WMS", "wms-rcv", "SEARCH", "SAVE");
        return accounts.as(c, u, "admin1");
    }

    private void seed(String c) {
        accounts.addPlant(c, "P1", "P1 공장");
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

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 자재 입고 API 로 LOT 하나 → LOT 번호 */
    private String lotIn(long at, String status, String qty) throws Exception {
        MvcResult m = postJson("/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + at + ",\"itemId\":" + fgLot + ",\"qty\":" + qty
                + ",\"stockStatus\":\"" + status + "\",\"supplierCd\":\"SUP1\",\"clientReqId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(m.getResponse().getContentAsString(), "$.data.lotNo");
    }

    private long lotId(String lotNo) {
        return accounts.query(comp, "select lot_id from lot_master where lot_no = ?", Long.class, lotNo);
    }

    /** 수주(고객, 품목, 수량) → 출하 지시(수량) → id */
    private long ship(String cust, String item, int qty) throws Exception {
        MvcResult o = postJson("/api/sales/orders", "{\"custCd\":\"" + cust + "\",\"lines\":[{\"itemCd\":\"" + item + "\",\"qty\":\"" + qty
                + "\",\"dueDate\":\"2026-11-30\"}]}").andExpect(status().isOk()).andReturn();
        long orderId = ((Number) JsonPath.read(o.getResponse().getContentAsString(), "$.data")).longValue();
        long ol = accounts.query(comp, "select line_id from sal_order_line where order_id = ?", Long.class, orderId);
        MvcResult m = postJson(S, "{\"plantCd\":\"P1\",\"custCd\":\"" + cust + "\",\"stageNodeId\":" + stg + ",\"planDate\":\"2026-11-20\","
                + "\"lines\":[{\"orderLineId\":" + ol + ",\"qty\":" + qty + "}]}").andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.head.shipId")).longValue();
    }

    private int rowVer(long id) {
        return accounts.query(comp, "select row_ver from wms_ship where ship_id = ?", Integer.class, id);
    }

    private long sline(long id) {
        return accounts.query(comp, "select ship_line_id from wms_ship_line where ship_id = ?", Long.class, id);
    }

    private void pick(long id, long from, String lotNo, int qty) throws Exception {
        postJson(S + "/" + id + "/pick", "{\"rowVer\":" + rowVer(id) + ",\"clientReqId\":\"" + UUID.randomUUID() + "\",\"picks\":[{\"shipLineId\":"
                + sline(id) + ",\"fromNodeId\":" + from + ",\"lotNo\":" + (lotNo == null ? "null" : "\"" + lotNo + "\"") + ",\"qty\":" + qty + "}]}")
                .andExpect(status().isOk());
    }

    private void confirm(long id) throws Exception {
        postJson(S + "/" + id + "/confirm", "{\"rowVer\":" + rowVer(id) + ",\"clientReqId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isOk());
    }

    private void undo(long id) throws Exception {
        postJson(S + "/" + id + "/undo", "{\"rowVer\":" + rowVer(id) + ",\"clientReqId\":\"" + UUID.randomUUID() + "\",\"reason\":\"추적 시험\"}")
                .andExpect(status().isOk());
    }

    private String shipNo(long id) {
        return accounts.query(comp, "select ship_no from wms_ship where ship_id = ?", String.class, id);
    }

    private List<LotShipment> byLots(String c, Long... ids) {
        return runner.call(c, () -> shipQuery.byLots(Arrays.asList(ids)));
    }

    // =====================================================================

    @Test
    void 한_LOT_두_지시_한_지시_두_LOT_부분_출하와_출하_취소_다시_확정은_순_수량_한_줄() throws Exception {
        String l1 = lotIn(a1, "AVAILABLE", "10");
        String l2 = lotIn(a2, "AVAILABLE", "10");
        long l1Id = lotId(l1);
        long l2Id = lotId(l2);
        // 지시 A(C-001) — L1 4 + L2 3, 확정 / 지시 B(C-002) — L1 5, 확정 / 지시 C(C-001) — L1 1 피킹만(출하 전)
        long a = ship("C-001", "FG-L", 7);
        pick(a, a1, l1, 4);
        pick(a, a2, l2, 3);
        confirm(a);
        long b = ship("C-002", "FG-L", 5);
        pick(b, a1, l1, 5);
        confirm(b);
        long cShip = ship("C-001", "FG-L", 1);
        pick(cShip, a1, l1, 1);
        // 비관리 품목 출하 — LOT 추적에 안 나온다
        long d = ship("C-001", "FG-100", 2);
        pick(d, a1, null, 2);
        confirm(d);

        List<LotShipment> rows = byLots(comp, l1Id, l2Id, l1Id, null);              // 중복·null 무시
        assertThat(rows).extracting(r -> r.lotId() + " " + r.shipNo() + " " + r.custCd() + " " + r.qty().stripTrailingZeros().toPlainString())
                .containsExactly(l1Id + " " + shipNo(a) + " C-001 4", l1Id + " " + shipNo(b) + " C-002 5", l2Id + " " + shipNo(a) + " C-001 3");
        assertThat(rows.get(0).shippedAt()).isNotNull();
        assertThat(rows.get(0).itemId()).isEqualTo(fgLot);
        assertThat(rows.get(0).unitCd()).isEqualTo("EA");
        assertThat(rows.get(0).palletNos()).isEmpty();

        // B 출하 취소 → L1 의 B 줄이 빠진다(취소된 출하는 고객에게 가지 않음, T-3)
        undo(b);
        assertThat(byLots(comp, l1Id)).extracting(LotShipment::shipNo).containsExactly(shipNo(a));
        // B 다시 확정 → 한 줄(취소 짝 + 새 SHIP 의 순 수량 5)
        confirm(b);
        assertThat(byLots(comp, l1Id)).extracting(r -> r.shipNo() + " " + r.qty().stripTrailingZeros().toPlainString())
                .containsExactly(shipNo(a) + " 4", shipNo(b) + " 5");
    }

    @Test
    void 팔레트에_담긴_피킹은_팔레트_번호가_실린다() throws Exception {
        String l1 = lotIn(a1, "AVAILABLE", "10");
        long a = ship("C-001", "FG-L", 4);
        pick(a, a1, l1, 4);
        MvcResult m = postJson(S + "/" + a + "/pallets", "{\"rowVer\":" + rowVer(a) + "}").andExpect(status().isOk()).andReturn();
        long palletId = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.pallets[0].palletId")).longValue();
        long pickId = accounts.query(comp, "select pick_id from wms_ship_pick where ship_line_id = ?", Long.class, sline(a));
        postJson(S + "/" + a + "/pallets/picks", "{\"rowVer\":" + rowVer(a) + ",\"palletId\":" + palletId + ",\"pickIds\":[" + pickId + "]}")
                .andExpect(status().isOk());
        confirm(a);
        String palletNo = accounts.query(comp, "select pallet_no from wms_pallet where pallet_id = ?", String.class, palletId);
        assertThat(byLots(comp, lotId(l1))).singleElement().satisfies(r -> assertThat(r.palletNos()).containsExactly(palletNo));
    }

    @Test
    void lotStock_은_가용_정의_없이_모든_상태와_칸_용도_0_은_없음() throws Exception {
        String l1 = lotIn(a1, "AVAILABLE", "10");
        String lq = lotIn(qc, "QC", "6");
        long l1Id = lotId(l1);
        // L1: A-01 6 남고 4 는 출하 대기 칸(피킹, 아직 안 나감) / LQ: 검사 칸 QC 6 / 다 나간 L3 은 없음
        long a = ship("C-001", "FG-L", 4);
        pick(a, a1, l1, 4);
        String l3 = lotIn(a2, "AVAILABLE", "2");
        long b = ship("C-001", "FG-L", 2);
        pick(b, a2, l3, 2);
        confirm(b);
        long lqId = lotId(lq);
        long l3Id = lotId(l3);
        List<LotStockRow> rows = runner.call(comp, () -> shipQuery.lotStock(List.of(l1Id, lqId, l3Id)));
        assertThat(rows).extracting(r -> r.lotId() + " " + r.nodeId() + " " + r.locUsage() + " " + r.stockStatus() + " "
                + r.qty().stripTrailingZeros().toPlainString())
                .containsExactlyInAnyOrder(l1Id + " " + a1 + " null AVAILABLE 6", l1Id + " " + stg + " STAGING AVAILABLE 4",
                        lqId + " " + qc + " QC QC 6");
        assertThat(rows.get(0).path()).contains("FG-WH");
    }

    @Test
    void 입력_상한_빈_입력_A사로_B사_NONE() throws Exception {
        String l1 = lotIn(a1, "AVAILABLE", "10");
        long a = ship("C-001", "FG-L", 3);
        pick(a, a1, l1, 3);
        confirm(a);
        long l1Id = lotId(l1);
        assertThat(runner.call(comp, () -> shipQuery.byLots(List.of()))).isEmpty();
        assertThat(runner.call(comp, () -> shipQuery.lotStock(null))).isEmpty();
        List<Long> many = new ArrayList<>(LongStream.rangeClosed(1, ShipQuery.LOTS_MAX + 1).boxed().toList());
        assertThat(catchThrowable(() -> runner.call(comp, () -> shipQuery.byLots(many)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> runner.call(comp, () -> shipQuery.lotStock(many)))).isInstanceOf(IllegalArgumentException.class);
        // 상한 정확히 500 은 된다(없는 id 는 빠짐)
        List<Long> max = new ArrayList<>(LongStream.rangeClosed(1, ShipQuery.LOTS_MAX - 1).map(i -> -i).boxed().toList());
        max.add(l1Id);
        assertThat(runner.call(comp, () -> shipQuery.byLots(max))).hasSize(1);
        // A사로 B사 — B사 범위로 A사 LOT id: 출하·재고 모두 빈 목록
        String b = accounts.newComp("SU");
        assertThat(byLots(b, l1Id)).isEmpty();
        assertThat(runner.call(b, () -> shipQuery.lotStock(List.of(l1Id)))).isEmpty();
        // WMS 없는 설치본
        assertThat(ShipQuery.NONE.byLots(List.of(l1Id))).isEmpty();
        assertThat(ShipQuery.NONE.lotStock(List.of(l1Id))).isEmpty();
    }
}
