package io.smarthappy.module.wms.ship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 출하 팔레트·SSCC (P4 2부 B6b-1, docs/30 12.10) — 담기·빼기·닫기(SSCC·라벨)·다시 열기·취소, 팔레트 필수 정책,
 * 닫힌 팔레트 보호, 직접 SQL 가드, 동시 닫기, A사로 B사.
 */
@AutoConfigureMockMvc
class ShipPalletTest extends PostgresTestBase {

    private static final String PW = "Harbor-violet-27";
    private static final String S = "/api/wms/ships";
    /** GS1 일반 규격 예시 업체코드(7자리) — 일련 9자리 */
    private static final String PREFIX = "0614141";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    long a1;
    long stg;
    long fg;
    long printer;
    int seq;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PL");
        admin = user(comp, "admin1");
        seed(comp);
        long wh = node(comp, null, "WH", "FG-WH");
        a1 = node(comp, wh, "LOC", "A-01");
        stg = node(comp, wh, "LOC", "STG-01");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't')", stg, comp);
        fg = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG-100'", Long.class);
        printer = accounts.query(comp, """
                insert into wms_printer (comp_cd, plant_cd, printer_cd, printer_nm, host, usage, created_by, updated_by)
                values (?, 'P1', 'PR-PLT', '팔레트 프린터', '10.0.0.7', 'PALLET', 't', 't') returning printer_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into wms_label_tpl (comp_cd, label_type, tpl_cd, tpl_nm, width_mm, height_mm, body, default_yn, created_by, updated_by)
                values (?, 'PALLET', 'PLT-STD', '팔레트 기본', 100, 150, '^XA^FD{{palletNo}} {{sscc}} {{itemCd}} {{qty}}^FS^XZ', 'Y', 't', 't')""", comp);
        adjIn(comp, fg, a1, "30");
    }

    private RequestPostProcessor user(String c, String id) {
        long u = accounts.newUser(c, id, PW);
        accounts.grant(c, u, "SALES", "sal-order", "SEARCH", "SAVE");
        accounts.grant(c, u, "WMS", "wms-ship", "SEARCH", "SAVE", "APPROVE");
        return accounts.as(c, u, id);
    }

    private void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C-001', 'C-001 상사', 'SALES', 't', 't')""", c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'FG-100', 'FG 품목', 'FG', 'MAKE', 'EA', 'N', 't', 't'), (?, 'FG-200', 'FG 둘째', 'FG', 'MAKE', 'EA', 'N', 't', 't')""", c, c);
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

    private void policy(String key, String plant, String value) {
        accounts.exec(comp, "delete from sys_policy where policy_key = ?", key);
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) values (?, ?, ?, ?, 't', 't')",
                comp, plant, key, value);
    }

    private ResultActions postJson(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String req() {
        return "plt-" + (++seq) + "-" + System.nanoTime();
    }

    /** 수주 FG-100 qty → 지시 → 피킹(A-01 에서 두 번 나눠) → 출하 id */
    private long pickedShip(int q1, int q2) throws Exception {
        MvcResult m = postJson(admin, "/api/sales/orders", "{\"custCd\":\"C-001\",\"lines\":[{\"itemCd\":\"FG-100\",\"qty\":\"" + (q1 + q2)
                + "\",\"dueDate\":\"2026-11-30\"}]}").andExpect(status().isOk()).andReturn();
        long o = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
        long ol = accounts.query(comp, "select line_id from sal_order_line where order_id = ? and line_no = 10", Long.class, o);
        MvcResult s = postJson(admin, S, "{\"plantCd\":\"P1\",\"custCd\":\"C-001\",\"stageNodeId\":" + stg + ",\"planDate\":\"2026-11-20\",\"lines\":[{\"orderLineId\":"
                + ol + ",\"qty\":" + (q1 + q2) + "}]}").andExpect(status().isOk()).andReturn();
        long id = ((Number) JsonPath.read(s.getResponse().getContentAsString(), "$.data.head.shipId")).longValue();
        long sl = accounts.query(comp, "select ship_line_id from wms_ship_line where ship_id = ?", Long.class, id);
        postJson(admin, S + "/" + id + "/pick", "{\"rowVer\":" + rowVer(id) + ",\"clientReqId\":\"" + req() + "\",\"picks\":[{\"shipLineId\":" + sl
                + ",\"fromNodeId\":" + a1 + ",\"qty\":" + q1 + "},{\"shipLineId\":" + sl + ",\"fromNodeId\":" + a1 + ",\"qty\":" + q2 + "}]}")
                .andExpect(status().isOk());
        return id;
    }

    private int rowVer(long id) {
        return accounts.query(comp, "select row_ver from wms_ship where ship_id = ?", Integer.class, id);
    }

    private List<Long> picks(long id) {
        String ids = accounts.query(comp, "select coalesce(string_agg(p.pick_id::text, ',' order by p.pick_id), '') from wms_ship_pick p "
                + "join wms_ship_line l on l.ship_line_id = p.ship_line_id where l.ship_id = ? and p.status = 'PICKED'", String.class, id);
        return ids.isEmpty() ? List.of() : java.util.Arrays.stream(ids.split(",")).map(Long::valueOf).toList();
    }

    private long newPallet(long id) throws Exception {
        MvcResult m = postJson(admin, S + "/" + id + "/pallets", "{\"rowVer\":" + rowVer(id) + "}").andExpect(status().isOk()).andReturn();
        List<Number> ids = JsonPath.read(m.getResponse().getContentAsString(), "$.data.pallets[*].palletId");
        return ids.get(ids.size() - 1).longValue();
    }

    private ResultActions put(long id, Long palletId, List<Long> pickIds) throws Exception {
        return postJson(admin, S + "/" + id + "/pallets/picks", "{\"rowVer\":" + rowVer(id) + ",\"palletId\":" + palletId + ",\"pickIds\":" + pickIds + "}");
    }

    private ResultActions close(long id, long palletId, String extra) throws Exception {
        return postJson(admin, S + "/" + id + "/pallets/" + palletId + "/close", "{\"rowVer\":" + rowVer(id) + (extra == null ? "" : extra) + "}");
    }

    private ResultActions confirm(long id) throws Exception {
        return postJson(admin, S + "/" + id + "/confirm", "{\"rowVer\":" + rowVer(id) + ",\"clientReqId\":\"" + req() + "\"}");
    }

    private String pallet(long palletId, String col) {
        return accounts.query(comp, "select " + col + "::text from wms_pallet where pallet_id = ?", String.class, palletId);
    }

    private static String constraint(Runnable r) {
        Throwable t = catchThrowable(r::run);
        assertThat(t).isNotNull();
        return PgErrors.constraint(t).orElse(t.getMessage());
    }

    // =====================================================================
    // 순수 함수
    // =====================================================================

    @Test
    void SSCC_검사_숫자는_GS1_예시와_같고_업체코드_모양과_자리_넘침을_가린다() {
        // GS1 일반 규격의 예시 (00) 1 0614141 123456789 7
        assertThat(Sscc.build(1, PREFIX, 123456789L)).isEqualTo("106141411234567897");
        assertThat(Sscc.valid("106141411234567897")).isTrue();
        assertThat(Sscc.valid("106141411234567890")).isFalse();
        assertThat(Sscc.checkDigit("629104150021")).isEqualTo(3);                  // GTIN-13 6291041500213 과 같은 mod-10
        assertThat(Sscc.prefixOk("12345")).isFalse();
        assertThat(Sscc.prefixOk("1234567890123")).isFalse();
        assertThat(Sscc.prefixOk("12A4567")).isFalse();
        assertThat(Sscc.serialDigits("123456789012")).isEqualTo(4);
        assertThat(Sscc.build(0, "123456789012", 9999L)).hasSize(18);
        assertThat(Sscc.build(0, "123456789012", 10000L)).isNull();                // 일련 자리 넘침
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void 담고_닫으면_SSCC_와_라벨이_붙고_다시_열어도_SSCC_는_그대로이며_원장은_바뀌지_않는다() throws Exception {
        policy("WMS_GS1_COMPANY_PREFIX", "*", PREFIX);
        long id = pickedShip(4, 6);
        long trx = accounts.query(comp, "select count(*) from inv_trx", Long.class);
        long p1 = newPallet(id);
        assertThat(pallet(p1, "pallet_no")).matches("PLT-\\d{6}-\\d{3}");
        put(id, p1, picks(id)).andExpect(status().isOk()).andExpect(jsonPath("$.data.pallets[0].pickCount").value(2))
                .andExpect(jsonPath("$.data.picks[0].palletNo").value(pallet(p1, "pallet_no")));
        close(id, p1, ",\"printerId\":" + printer).andExpect(status().isOk()).andExpect(jsonPath("$.data.labelJobs").value(1))
                .andExpect(jsonPath("$.data.view.pallets[0].status").value("CLOSED"));
        String sscc = pallet(p1, "sscc");
        assertThat(sscc).hasSize(18).startsWith("0" + PREFIX);
        assertThat(Sscc.valid(sscc)).isTrue();
        String payload = accounts.query(comp, "select label_values::text from wms_print_job order by job_id desc limit 1", String.class);
        assertThat(payload).contains(sscc).contains("PLT:" + pallet(p1, "pallet_no")).contains("FG-100");
        // 다시 열기(사유) → 빼고 담고 → 다시 닫기: SSCC 그대로, 일련번호 더 쓰지 않음
        postJson(admin, S + "/" + id + "/pallets/" + p1 + "/reopen", "{\"rowVer\":" + rowVer(id) + ",\"reason\":\"수량 재확인\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pallets[0].status").value("OPEN"));
        put(id, null, picks(id).subList(0, 1)).andExpect(status().isOk()).andExpect(jsonPath("$.data.pallets[0].pickCount").value(1));
        long p2 = newPallet(id);
        put(id, p2, picks(id).subList(0, 1)).andExpect(status().isOk());
        close(id, p1, null).andExpect(status().isOk());
        close(id, p2, null).andExpect(status().isOk());
        assertThat(pallet(p1, "sscc")).isEqualTo(sscc);
        assertThat(Long.parseLong(pallet(p2, "sscc").substring(8, 17))).isEqualTo(Long.parseLong(sscc.substring(8, 17)) + 1);
        // 빈 팔레트는 취소
        long p3 = newPallet(id);
        postJson(admin, S + "/" + id + "/pallets/" + p3 + "/cancel", "{\"rowVer\":" + rowVer(id) + "}").andExpect(status().isOk());
        assertThat(pallet(p3, "status")).isEqualTo("CANCELLED");
        assertThat(accounts.query(comp, "select count(*) from inv_trx", Long.class)).isEqualTo(trx);   // D-B25
        confirm(id).andExpect(status().isOk());
        mvc.perform(get(S + "/" + id).with(admin)).andExpect(jsonPath("$.data.pallets.length()").value(3))
                .andExpect(jsonPath("$.data.picks[0].status").value("SHIPPED"));
    }

    @Test
    void 업체코드가_없으면_SSCC_없이_닫히고_피킹_취소는_OPEN_팔레트에서_함께_빠진다() throws Exception {
        long id = pickedShip(3, 2);
        long p = newPallet(id);
        put(id, p, picks(id)).andExpect(status().isOk());
        long one = picks(id).get(0);
        postJson(admin, S + "/" + id + "/unpick", "{\"rowVer\":" + rowVer(id) + ",\"pickIds\":[" + one + "],\"reason\":\"잘못 피킹\"}")
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select pallet_id is null from wms_ship_pick where pick_id = ?", Boolean.class, one)).isTrue();
        close(id, p, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.view.pallets[0].pickCount").value(1));
        assertThat(pallet(p, "sscc")).isNull();
        assertThat(pallet(p, "status")).isEqualTo("CLOSED");
    }

    @Test
    void 팔레트_필수_공장은_덜_담긴_출하_확정을_409로_막고_다_닫으면_된다() throws Exception {
        policy("WMS_SHIP_PALLET_REQUIRED", "P1", "Y");
        long id = pickedShip(4, 6);
        confirm(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_SHIP_UNPALLETIZED"));
        long p = newPallet(id);
        put(id, p, picks(id)).andExpect(status().isOk());
        confirm(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_SHIP_UNPALLETIZED"));   // OPEN 은 안 됨
        close(id, p, null).andExpect(status().isOk());
        confirm(id).andExpect(status().isOk()).andExpect(jsonPath("$.data.view.head.status").value("SHIPPED"));
    }

    // ---------------------------------------------------------------- B6b-2 스캔·ERP·출하 라벨

    private ResultActions scan(RequestPostProcessor who, String text) throws Exception {
        return postJson(who, S + "/scan", "{\"text\":" + (text == null ? "null" : "\"" + text.replace("\u001d", "\\u001d") + "\"") + "}");
    }

    @Test
    void 팔레트_스캔은_내부_번호와_SSCC_네_모양을_읽고_검사_숫자_틀림_팔레트_아님_없음은_거부() throws Exception {
        policy("WMS_GS1_COMPANY_PREFIX", "*", PREFIX);
        long id = pickedShip(4, 6);
        long p = newPallet(id);
        put(id, p, picks(id)).andExpect(status().isOk());
        close(id, p, null).andExpect(status().isOk());
        String no = pallet(p, "pallet_no");
        String sscc = pallet(p, "sscc");
        for (String t : List.of("PLT:" + no, " plt:" + no + " ", sscc, "00" + sscc, "(00)" + sscc, "]C100" + sscc)) {
            scan(admin, t).andExpect(status().isOk()).andExpect(jsonPath("$.data.shipId").value(id))
                    .andExpect(jsonPath("$.data.palletId").value(p)).andExpect(jsonPath("$.data.status").value("CLOSED"));
        }
        String bad = sscc.substring(0, 17) + ((sscc.charAt(17) - '0' + 1) % 10);
        scan(admin, bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("CHECK_DIGIT"));
        scan(admin, "(00)" + bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("CHECK_DIGIT"));
        scan(admin, "LOT:L-1").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_PALLET"));
        scan(admin, "(01)09506000134352").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_PALLET"));
        scan(admin, "A-01").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_PALLET"));
        scan(admin, "  ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        scan(admin, "PLT:NO-SUCH").andExpect(status().isNotFound());
        scan(admin, "106141411234567897").andExpect(status().isNotFound());                     // 맞는 SSCC 지만 없는 팔레트
        // A사로 B사 — 같은 SSCC·번호를 B사 사용자가 찍으면 없음
        String b = accounts.newComp("PS");
        accounts.addPlant(b, "P1", "1공장");
        RequestPostProcessor bAdmin = user(b, "admin1");
        scan(bAdmin, sscc).andExpect(status().isNotFound());
        scan(bAdmin, "PLT:" + no).andExpect(status().isNotFound());
    }

    @Test
    void 출하_확정의_ERP_SHIP_본문에_줄별_팔레트_번호와_SSCC가_실리고_출하_라벨에_팔레트_수가_찍힌다() throws Exception {
        policy("WMS_GS1_COMPANY_PREFIX", "*", PREFIX);
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) values (?, '*', 'ERP_SYSTEM', 'FAKE', 't', 't')",
                comp);
        long shipPrinter = accounts.query(comp, """
                insert into wms_printer (comp_cd, plant_cd, printer_cd, printer_nm, host, usage, created_by, updated_by)
                values (?, 'P1', 'PR-SHIP', '출하 프린터', '10.0.0.6', 'SHIP', 't', 't') returning printer_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into wms_label_tpl (comp_cd, label_type, tpl_cd, tpl_nm, width_mm, height_mm, body, default_yn, created_by, updated_by)
                values (?, 'SHIP', 'SHIP-STD', '출하 기본', 100, 50, '^XA^FD{{shipNo}} {{palletCount}}^FS^XZ', 'Y', 't', 't')""", comp);
        long id = pickedShip(4, 6);
        List<Long> ps = picks(id);
        long p1 = newPallet(id);
        put(id, p1, ps.subList(0, 1)).andExpect(status().isOk());
        long p2 = newPallet(id);
        put(id, p2, ps.subList(1, 2)).andExpect(status().isOk());
        close(id, p1, null).andExpect(status().isOk());
        close(id, p2, null).andExpect(status().isOk());
        postJson(admin, S + "/" + id + "/confirm", "{\"rowVer\":" + rowVer(id) + ",\"clientReqId\":\"" + req() + "\",\"printerId\":" + shipPrinter + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.labelJobs").value(1));
        String payload = accounts.query(comp, "select payload::text from sys_outbox where topic = 'ERP_SHIP'", String.class);
        assertThat(payload).contains(pallet(p1, "sscc")).contains(pallet(p2, "sscc"))
                .contains(pallet(p1, "pallet_no")).contains(pallet(p2, "pallet_no")).contains("\"ssccs\"").contains("\"palletNos\"");
        assertThat(accounts.query(comp, "select label_values ->> 'palletCount' from wms_print_job where label_type = 'SHIP'", String.class))
                .isEqualTo("2");
        // 팔레트 없이 나간 출하는 빈 목록·0
        long loose = pickedShip(1, 1);
        postJson(admin, S + "/" + loose + "/confirm", "{\"rowVer\":" + rowVer(loose) + ",\"clientReqId\":\"" + req() + "\",\"printerId\":" + shipPrinter + "}")
                .andExpect(status().isOk());
        String lp = accounts.query(comp, "select payload::text from sys_outbox where topic = 'ERP_SHIP' order by outbox_id desc limit 1", String.class);
        assertThat(lp.replace(" ", "")).contains("\"ssccs\":[]").contains("\"palletNos\":[]");
        assertThat(accounts.query(comp, "select label_values ->> 'palletCount' from wms_print_job where label_type = 'SHIP' order by job_id desc limit 1",
                String.class)).isEqualTo("0");
    }

    // =====================================================================
    // 거부
    // =====================================================================

    @Test
    void B_빈_팔레트_닫기_찬_팔레트_취소_닫힌_팔레트_담기_빼기_피킹_취소_다른_지시_팔레트_낡은_rowVer_출하_뒤_동작은_거부() throws Exception {
        long id = pickedShip(4, 6);
        long other = pickedShip(1, 1);
        long p = newPallet(id);
        long po = newPallet(other);
        close(id, p, null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("PALLET_EMPTY"));
        put(id, po, picks(id)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        put(id, p, picks(other)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        put(id, p, List.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        put(id, p, picks(id)).andExpect(status().isOk());
        postJson(admin, S + "/" + id + "/pallets/" + p + "/cancel", "{\"rowVer\":" + rowVer(id) + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("PALLET_NOT_EMPTY"));
        close(id, p, ",\"printerId\":" + printer + ",\"copies\":21").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        assertThat(pallet(p, "status")).isEqualTo("OPEN");
        close(id, p, null).andExpect(status().isOk());
        close(id, p, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_SHIP_STATE"));
        long p2 = newPallet(id);
        put(id, p2, picks(id).subList(0, 1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("PALLET_CLOSED"));
        put(id, null, picks(id).subList(0, 1)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("PALLET_CLOSED"));
        postJson(admin, S + "/" + id + "/unpick", "{\"rowVer\":" + rowVer(id) + ",\"pickIds\":[" + picks(id).get(0) + "],\"reason\":\"잘못 피킹\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("PALLET_CLOSED"));
        postJson(admin, S + "/" + id + "/pallets/" + p + "/reopen", "{\"rowVer\":" + rowVer(id) + ",\"reason\":\"x\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        postJson(admin, S + "/" + id + "/pallets", "{\"rowVer\":" + (rowVer(id) - 1) + "}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        postJson(admin, S + "/" + id + "/pallets/" + po + "/close", "{\"rowVer\":" + rowVer(id) + "}").andExpect(status().isNotFound());
        // 출하 확정 뒤에는 팔레트 동작 없음
        confirm(id).andExpect(status().isOk());
        postJson(admin, S + "/" + id + "/pallets", "{\"rowVer\":" + rowVer(id) + "}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WMS_SHIP_STATE"));
        postJson(admin, S + "/" + id + "/pallets/" + p + "/reopen", "{\"rowVer\":" + rowVer(id) + ",\"reason\":\"수량 재확인\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_SHIP_STATE"));
    }

    @Test
    void B_업체코드_모양이_틀리면_409이고_팔레트는_OPEN_그대로() throws Exception {
        policy("WMS_GS1_COMPANY_PREFIX", "*", "12AB567");
        long id = pickedShip(4, 6);
        long p = newPallet(id);
        put(id, p, picks(id)).andExpect(status().isOk());
        close(id, p, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_SSCC_UNAVAILABLE"));
        assertThat(pallet(p, "status")).isEqualTo("OPEN");
    }

    // =====================================================================
    // 직접 SQL
    // =====================================================================

    @Test
    void 직접_SQL_닫힌_채_넣기_SSCC_바꾸기_찬_팔레트_취소_다른_지시_팔레트에_담기_닫힌_팔레트에서_빼기_삭제는_DB가_막는다() throws Exception {
        long id = pickedShip(4, 6);
        long other = pickedShip(1, 1);
        long p = newPallet(id);
        long po = newPallet(other);
        long pick = picks(id).get(0);
        assertThat(constraint(() -> accounts.exec(comp, "insert into wms_pallet (comp_cd, ship_id, pallet_no, status, closed_at, closed_by, created_by, updated_by) "
                + "values (?, ?, 'PLT-X', 'CLOSED', now(), 't', 't', 't')", comp, id))).isEqualTo("ck_wms_pallet_transition");
        assertThat(constraint(() -> accounts.exec(comp, "update wms_ship_pick set pallet_id = ? where pick_id = ?", po, pick)))
                .isEqualTo("ck_wms_ship_pick_pallet");
        accounts.exec(comp, "update wms_ship_pick set pallet_id = ? where pick_id = ?", p, pick);
        assertThat(constraint(() -> accounts.exec(comp, "update wms_pallet set status = 'CANCELLED' where pallet_id = ?", p)))
                .isEqualTo("ck_wms_pallet_not_empty");
        accounts.exec(comp, "update wms_pallet set status = 'CLOSED', closed_at = now(), closed_by = 't', sscc = '106141411234567897' where pallet_id = ?", p);
        assertThat(constraint(() -> accounts.exec(comp, "update wms_pallet set sscc = '106141411234567880' where pallet_id = ?", p)))
                .isEqualTo("ck_wms_pallet_fixed");
        assertThat(constraint(() -> accounts.exec(comp, "update wms_ship_pick set pallet_id = null where pick_id = ?", pick)))
                .isEqualTo("ck_wms_ship_pick_pallet");
        assertThat(constraint(() -> accounts.exec(comp, "update wms_pallet set status = 'OPEN' where pallet_id = ?", p)))
                .isEqualTo("ck_wms_pallet_closed");                                                  // 닫힘 표시를 안 지우면
        assertThat(constraint(() -> accounts.exec(comp, "update wms_pallet set pallet_no = 'PLT-Y' where pallet_id = ?", p))).isEqualTo("ck_wms_pallet_fixed");
        assertThat(constraint(() -> accounts.exec(comp, "update wms_pallet set sscc = '12' where pallet_id = ?", po))).isEqualTo("ck_wms_pallet_sscc");
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from wms_pallet where pallet_id = ?", po))).isNotNull();
    }

    // =====================================================================
    // 동시 · A사로 B사
    // =====================================================================

    @Test
    void C_같은_팔레트를_동시에_두_번_닫으면_하나만_되고_SSCC_일련은_한_번만_쓴다() throws Exception {
        policy("WMS_GS1_COMPANY_PREFIX", "*", PREFIX);
        long id = pickedShip(4, 6);
        long p = newPallet(id);
        put(id, p, picks(id)).andExpect(status().isOk());
        int rv = rowVer(id);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> fs = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                fs.add(pool.submit(() -> {
                    go.await();
                    return postJson(admin, S + "/" + id + "/pallets/" + p + "/close", "{\"rowVer\":" + rv + "}").andReturn().getResponse().getStatus();
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
        assertThat(pallet(p, "status")).isEqualTo("CLOSED");
        assertThat(accounts.query(comp, "select count(*) from sys_numbering_seq where rule_cd = 'WMS_SSCC'", Integer.class)).isEqualTo(1);
        assertThat(accounts.query(comp, "select max(last_no) from sys_numbering_seq where rule_cd = 'WMS_SSCC'", Long.class)).isEqualTo(1L);
    }

    @Test
    void A사로_B사_지시_팔레트는_보이지도_바뀌지도_않는다() throws Exception {
        long id = pickedShip(4, 6);
        long p = newPallet(id);
        String b = accounts.newComp("PB");
        accounts.addPlant(b, "P1", "1공장");
        RequestPostProcessor bAdmin = user(b, "admin1");
        postJson(bAdmin, S + "/" + id + "/pallets", "{\"rowVer\":" + rowVer(id) + "}").andExpect(status().isNotFound());
        postJson(bAdmin, S + "/" + id + "/pallets/" + p + "/close", "{\"rowVer\":" + rowVer(id) + "}").andExpect(status().isNotFound());
        postJson(bAdmin, S + "/" + id + "/pallets/picks", "{\"rowVer\":" + rowVer(id) + ",\"palletId\":" + p + ",\"pickIds\":" + picks(id) + "}")
                .andExpect(status().isNotFound());
        assertThat(accounts.query(b, "select count(*) from wms_pallet", Integer.class)).isZero();
        assertThat(pallet(p, "status")).isEqualTo("OPEN");
        assertThat(accounts.query(comp, "select count(*) from wms_ship_pick where pallet_id is not null", Integer.class)).isZero();
    }
}
