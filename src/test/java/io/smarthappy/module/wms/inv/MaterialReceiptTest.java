package io.smarthappy.module.wms.inv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 자재 입고 RCV_MAT (P4 2부 B4a, docs/30 12.3). LOT 은 P2 LotService.receive 가 만든다(이 시험은 화면 API 로만).
 * 정상(LOT 관리·비관리 품목, 상태, 라벨), GS1 스캔, 같은 요청 두 번·동시, B(위치·수량·공급사·공급사 LOT 중복·시각·요청 id·프린터),
 * 마감 달, 역분개(재고 남음/이미 출고·동시 차감), 직접 SQL(출처 없는 입고), 대사, 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class MaterialReceiptTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    InboxService inbox;

    String comp;
    RequestPostProcessor admin;
    long viewerId;
    long wh;
    long a1;
    long qcLoc;
    long rack;
    long scrapLoc;
    long lotItem;
    long plain;
    long printer;
    int seq;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("RM");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "WMS", "wms-rcv", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-trx", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-move", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-stock", "SEARCH", "FN1");
        accounts.grant(comp, u, "WMS", "wms-period", "SEARCH", "APPROVE");
        admin = accounts.as(comp, u, "admin1");
        viewerId = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewerId, "WMSV", "wms-rcv", "SEARCH");
        accounts.addPlant(comp, "P1", "1공장");
        wh = node(null, "WH", "RM-WH");
        a1 = node(wh, "LOC", "A-01");
        qcLoc = node(wh, "LOC", "QC-01");
        rack = node(wh, "LOC", "RACK");
        long scrapWh = node(null, "WH", "SCRAP");
        scrapLoc = node(scrapWh, "LOC", "S-01");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, default_status, created_by, updated_by) "
                + "values (?, ?, 'QC', 'QC', 't', 't')", qcLoc, comp);
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, stock_yn, created_by, updated_by) values (?, ?, 'N', 't', 't')",
                rack, comp);
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'SCRAP', 't', 't')",
                scrapWh, comp);
        lotItem = item("RM-L", "Y", "08801234567893");
        plain = item("RM-P", "N", null);
        cust("SUP1", "PURCHASE");
        cust("CUS1", "SALES");
        printer = accounts.query(comp, """
                insert into wms_printer (comp_cd, plant_cd, printer_cd, printer_nm, host, usage, created_by, updated_by)
                values (?, 'P1', 'PR-LOT', 'LOT 프린터', '10.0.0.5', 'LOT', 't', 't') returning printer_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into wms_label_tpl (comp_cd, label_type, tpl_cd, tpl_nm, width_mm, height_mm, body, default_yn, created_by, updated_by)
                values (?, 'LOT', 'LOT-STD', 'LOT 기본', 50, 30, '^XA^FD{{lotNo}} {{supplierLotNo}} {{expiry}}^FS^XZ', 'Y', 't', 't')""", comp);
    }

    long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd);
    }

    long item(String cd, String lotYn, String gtin) {
        long id = accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, ?, ?, 'RM', 'BUY', 'EA', ?, 't', 't') returning item_id""", Long.class, comp, cd, cd + " 이름", lotYn);
        if (gtin != null) {
            // 품목 속성 GTIN(D-B10) — EAN-13 으로 적어 두어도 14자리로 맞춰 찾는다
            long cls = accounts.query(comp, """
                    insert into mst_item_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'ZTG', 'ZT 구매품', 't', 't')
                    on conflict (comp_cd, class_cd) do update set class_nm = excluded.class_nm returning class_id""", Long.class, comp);
            accounts.exec(comp, """
                    insert into mst_class_prop (comp_cd, class_type, class_id, prop_cd, prop_nm, data_type, created_by, updated_by)
                    values (?, 'ITEM', ?, 'GTIN', 'GTIN', 'TEXT', 't', 't') on conflict do nothing""", comp, cls);
            accounts.exec(comp, "update mst_item set item_class_id = ?, attrs = jsonb_build_object('GTIN', ?::text) where item_id = ?", cls,
                    gtin.substring(1), id);
        }
        return id;
    }

    void cust(String cd, String type) {
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, ?, ?, ?, 't', 't')",
                comp, cd, cd, type);
    }

    String req() {
        return "rcv-test-" + (++seq) + "-" + System.nanoTime();
    }

    ResultActions postJson(String url, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    /** extra = 뒤에 붙일 JSON 조각(",\"key\":…") */
    ResultActions receive(long node, long item, String qty, String status, String reqId, String extra) throws Exception {
        return postJson("/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + node + ",\"itemId\":" + item + ",\"qty\":" + qty
                + ",\"stockStatus\":\"" + status + "\",\"supplierCd\":\"SUP1\",\"clientReqId\":" + (reqId == null ? "null" : "\"" + reqId + "\"")
                + (extra == null ? "" : extra) + "}", admin);
    }

    BigDecimal stock(long node, long item, String status) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and item_id = ? and stock_status = ?",
                BigDecimal.class, node, item, status).stripTrailingZeros();
    }

    int count(String sql, Object... args) {
        return accounts.query(comp, sql, Integer.class, args);
    }

    static long trxId(MvcResult m) throws Exception {
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.trxId")).longValue();
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void LOT_관리_품목_입고는_RECEIVED_LOT_과_원장_한_행과_라벨() throws Exception {
        MvcResult m = receive(a1, lotItem, "120", "AVAILABLE", req(),
                ",\"supplierLotNo\":\"SL-001\",\"expiry\":\"2027-12-31\",\"printerId\":" + printer + ",\"copies\":2")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.replay").value(false))
                .andExpect(jsonPath("$.data.labelJobs").value(1)).andReturn();
        String lotNo = JsonPath.read(m.getResponse().getContentAsString(), "$.data.lotNo");
        long trx = trxId(m);
        assertThat(stock(a1, lotItem, "AVAILABLE")).isEqualByComparingTo("120");
        // LOT = P2 RECEIVED, 공급사·공급사 LOT, 원장 출처 = 그 LOT
        assertThat(count("""
                select count(*) from lot_master l join mst_cust c on c.cust_id = l.supplier_cust_id
                 where l.lot_no = ? and l.origin = 'RECEIVED' and l.qty_created = 120 and c.cust_cd = 'SUP1' and l.supplier_lot_no = 'SL-001'""",
                lotNo)).isEqualTo(1);
        assertThat(count("""
                select count(*) from inv_trx t join lot_master l on l.lot_id = t.src_id
                 where t.trx_id = ? and t.trx_type = 'RCV_MAT' and t.src_type = 'MAT_RECEIPT' and t.lot_id = l.lot_id and l.lot_no = ?""",
                trx, lotNo)).isEqualTo(1);
        // 라벨: 바코드 = LOT 내부 형식, 유통기한·공급사 LOT 은 라벨 글자로만(저장 안 함 — D-B9)
        assertThat(count("select count(*) from wms_print_job where label_type = 'LOT' and biz_key = ? and copies = 2", "LOT:" + lotNo))
                .isEqualTo(1);
        mvc.perform(get("/api/wms/rcv/recent").param("plantCd", "P1").with(admin)).andExpect(jsonPath("$.list[0].lotNo").value(lotNo));
    }

    @Test
    void LOT_비관리_품목도_입고_LOT_은_만들고_원장_lot_id_는_비운다() throws Exception {
        MvcResult m = receive(qcLoc, plain, "7", "QC", req(), null).andExpect(status().isOk()).andReturn();
        assertThat(stock(qcLoc, plain, "QC")).isEqualByComparingTo("7");
        assertThat(count("select count(*) from inv_trx where trx_id = ? and lot_id is null and src_id is not null", trxId(m))).isEqualTo(1);
        assertThat(count("select count(*) from lot_master where item_id = ? and origin = 'RECEIVED'", plain)).isEqualTo(1);
    }

    @Test
    void GS1_스캔은_GTIN_품목_공급사_LOT_유통기한_수량() throws Exception {
        postJson("/api/wms/rcv/scan", "{\"plantCd\":\"P1\",\"text\":\"(01)08801234567893(10)SL-77(17)271231(30)25\"}", admin)
                .andExpect(jsonPath("$.data.type").value("GS1")).andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.itemCd").value("RM-L")).andExpect(jsonPath("$.data.supplierLotNo").value("SL-77"))
                .andExpect(jsonPath("$.data.expiry").value("2027-12-31")).andExpect(jsonPath("$.data.qty").value(25));
        // 매핑 없는 GTIN — 품목은 비우고 나머지는 채움
        postJson("/api/wms/rcv/scan", "{\"plantCd\":\"P1\",\"text\":\"(01)08801234567886(10)SL-78\"}", admin)
                .andExpect(jsonPath("$.data.ok").value(true)).andExpect(jsonPath("$.data.itemId").doesNotExist())
                .andExpect(jsonPath("$.data.supplierLotNo").value("SL-78"));
        postJson("/api/wms/rcv/scan", "{\"plantCd\":\"P1\",\"text\":\"LOC:P1:A-01\"}", admin)
                .andExpect(jsonPath("$.data.type").value("LOC")).andExpect(jsonPath("$.data.nodeId").value(a1));
        postJson("/api/wms/rcv/scan", "{\"plantCd\":\"P1\",\"text\":\"LOT:ANY-1\"}", admin).andExpect(jsonPath("$.data.ok").value(false));
    }

    // =====================================================================
    // 멱등·동시
    // =====================================================================

    @Test
    void 같은_요청_id_두_번이면_처음_결과_LOT_하나() throws Exception {
        String r = req();
        MvcResult first = receive(a1, lotItem, "10", "AVAILABLE", r, null).andExpect(status().isOk()).andReturn();
        receive(a1, lotItem, "10", "AVAILABLE", r, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.replay").value(true))
                .andExpect(jsonPath("$.data.trxId").value(trxId(first)));
        assertThat(count("select count(*) from lot_master where item_id = ?", lotItem)).isEqualTo(1);
        assertThat(stock(a1, lotItem, "AVAILABLE")).isEqualByComparingTo("10");
    }

    @Test
    void C_같은_요청_id_동시_두_번이어도_LOT_하나_원장_한_행() throws Exception {
        String r = req();
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Integer>> calls = List.of(1, 2).stream().map(i -> CompletableFuture.supplyAsync(() -> {
            try {
                go.await(10, TimeUnit.SECONDS);
                return receive(a1, plain, "5", "AVAILABLE", r, null).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        })).toList();
        go.countDown();
        for (CompletableFuture<Integer> c : calls) {
            assertThat(c.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(count("select count(*) from lot_master where item_id = ?", plain)).isEqualTo(1);
        assertThat(count("select count(*) from inv_trx where client_req_id = ?", r)).isEqualTo(1);
        assertThat(stock(a1, plain, "AVAILABLE")).isEqualByComparingTo("5");
    }

    @Test
    void C_요청_id_를_다른_트랜잭션이_쥐고_있으면_기다렸다가_그쪽이_되돌리면_처음으로_처리() throws Exception {
        String r = req();
        CountDownLatch held = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> {
            try {
                runner.run(comp, () -> {
                    inbox.firstTime(MaterialReceiptService.INBOX, r);
                    held.countDown();
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IllegalStateException("되돌림");
                });
            } catch (IllegalStateException expected) {
                // 쥔 쪽이 실패해 되돌림
            }
        });
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        long t0 = System.nanoTime();
        receive(a1, plain, "3", "AVAILABLE", r, null).andExpect(status().isOk()).andExpect(jsonPath("$.data.replay").value(false));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isGreaterThan(500);
        holder.get(10, TimeUnit.SECONDS);
        assertThat(stock(a1, plain, "AVAILABLE")).isEqualByComparingTo("3");
    }

    @Test
    void C_입고_역분개와_같은_재고_출고가_동시면_하나만() throws Exception {
        long trx = trxId(receive(a1, plain, "10", "AVAILABLE", req(), null).andExpect(status().isOk()).andReturn());
        CountDownLatch go = new CountDownLatch(1);
        CompletableFuture<Integer> reverse = CompletableFuture.supplyAsync(() -> {
            try {
                go.await(10, TimeUnit.SECONDS);
                return postJson("/api/wms/trx/" + trx + "/reverse", "{\"reason\":\"잘못 입고\"}", admin).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        CompletableFuture<Integer> out = CompletableFuture.supplyAsync(() -> {
            try {
                go.await(10, TimeUnit.SECONDS);
                return postJson("/api/wms/move/adjust", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + plain
                        + ",\"stockStatus\":\"AVAILABLE\",\"delta\":-10,\"reasonCd\":\"FOUND\"}", admin).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        go.countDown();
        List<Integer> codes = List.of(reverse.get(20, TimeUnit.SECONDS), out.get(20, TimeUnit.SECONDS));
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(stock(a1, plain, "AVAILABLE")).isEqualByComparingTo("0");
    }

    // =====================================================================
    // 이상 사용
    // =====================================================================

    @Test
    void B_위치_수량_공급사_시각_요청_id_프린터() throws Exception {
        receive(scrapLoc, plain, "1", "AVAILABLE", req(), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("nodeId")).andExpect(jsonPath("$.errors[0].code").value("SCRAP_LOCATION"));
        receive(rack, plain, "1", "AVAILABLE", req(), null).andExpect(jsonPath("$.errors[0].code").value("STOCK_NOT_ALLOWED"));
        // 출하 대기(STAGING) 칸 — 사무 입고도 거부(W-9, PDA·생산 입고와 같은 규칙), 재고 0
        long stg = node(accounts.query(comp, "select parent_id from mst_equip_hier where node_id = ?", Long.class, rack), "LOC", "STG-01");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't')", stg, comp);
        receive(stg, plain, "1", "AVAILABLE", req(), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("nodeId")).andExpect(jsonPath("$.errors[0].code").value("STAGING_NOT_ALLOWED"));
        assertThat(stock(stg, plain, "AVAILABLE")).isEqualByComparingTo("0");
        receive(a1, plain, "0", "AVAILABLE", req(), null).andExpect(jsonPath("$.errors[0].code").value("POSITIVE"));
        // 단위 자릿수(EA = 0자리)는 P2 LOT 등록 판정
        receive(a1, plain, "7.5", "AVAILABLE", req(), null).andExpect(jsonPath("$.errors[0].field").value("qty"))
                .andExpect(jsonPath("$.errors[0].code").value("SCALE"));
        receive(a1, plain, "1", "WHATEVER", req(), null).andExpect(jsonPath("$.errors[0].code").value("CODE"));
        receive(a1, plain, "1", "AVAILABLE", req(), ",\"occurredAt\":\"2099-01-01T00:00\"")
                .andExpect(jsonPath("$.errors[0].code").value("FUTURE"));
        receive(a1, plain, "1", "AVAILABLE", null, null).andExpect(jsonPath("$.errors[0].field").value("clientReqId"));
        receive(a1, plain, "1", "AVAILABLE", "x y", null).andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        receive(a1, plain, "1", "AVAILABLE", req(), ",\"printerId\":999999").andExpect(jsonPath("$.errors[0].field").value("printerId"));
        receive(a1, plain, "1", "AVAILABLE", req(), ",\"printerId\":" + printer + ",\"copies\":21")
                .andExpect(jsonPath("$.errors[0].field").value("copies"));
        // 판매 거래처를 공급사로 · 없는 공급사 (P2 판정)
        postJson("/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + plain + ",\"qty\":1,\"stockStatus\":\"AVAILABLE\","
                + "\"supplierCd\":\"CUS1\",\"clientReqId\":\"" + req() + "\"}", admin)
                .andExpect(jsonPath("$.errors[0].code").value("NOT_SUPPLIER_LOT"));
        postJson("/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + plain + ",\"qty\":1,\"stockStatus\":\"AVAILABLE\","
                + "\"supplierCd\":\"NOPE\",\"clientReqId\":\"" + req() + "\"}", admin)
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        // 하나도 남지 않았다(LOT·원장·inbox 모두 되돌림)
        assertThat(count("select count(*) from lot_master")).isZero();
        assertThat(count("select count(*) from inv_trx")).isZero();
    }

    @Test
    void 같은_공급사_LOT_두_번은_새_내부_LOT_둘_원장도_둘_공급사_LOT_은_같다() throws Exception {
        // 결정 #43(MES V135): 같은 공급사 LOT 을 다시 받으면 새 내부 LOT — 예전에는 DUPLICATE 로 거부했다
        receive(a1, lotItem, "10", "AVAILABLE", req(), ",\"supplierLotNo\":\"SL-9\"").andExpect(status().isOk());
        receive(a1, lotItem, "10", "AVAILABLE", req(), ",\"supplierLotNo\":\"SL-9\"").andExpect(status().isOk());
        assertThat(count("select count(*) from lot_master")).isEqualTo(2);
        assertThat(count("select count(distinct supplier_lot_no) from lot_master")).isEqualTo(1);
        assertThat(count("select count(*) from inv_trx")).isEqualTo(2);
    }

    @Test
    void 마감된_달로_입고하면_PERIOD_CLOSED_LOT_도_남지_않음() throws Exception {
        String lastMonth = accounts.query(comp, "select to_char(date_trunc('month', now() at time zone 'Asia/Seoul') - interval '1 month', "
                + "'YYYY-MM')", String.class);
        String at = accounts.query(comp, "select to_char(date_trunc('month', now() at time zone 'Asia/Seoul') - interval '10 days', "
                + "'YYYY-MM-DD\"T\"HH24:MI')", String.class);
        postJson("/api/wms/periods/close", "{\"plantCd\":\"P1\",\"period\":\"" + lastMonth + "\"}", admin).andExpect(status().isOk());
        receive(a1, plain, "4", "AVAILABLE", req(), ",\"occurredAt\":\"" + at + "\"").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));
        assertThat(count("select count(*) from lot_master")).isZero();
    }

    @Test
    void 역분개는_재고가_남아_있을_때만() throws Exception {
        long t1 = trxId(receive(a1, plain, "10", "AVAILABLE", req(), null).andExpect(status().isOk()).andReturn());
        postJson("/api/wms/trx/" + t1 + "/reverse", "{\"reason\":\"수량 잘못\"}", admin).andExpect(status().isOk());
        assertThat(stock(a1, plain, "AVAILABLE")).isEqualByComparingTo("0");
        long t2 = trxId(receive(a1, plain, "10", "AVAILABLE", req(), null).andExpect(status().isOk()).andReturn());
        postJson("/api/wms/move/adjust", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + plain
                + ",\"stockStatus\":\"AVAILABLE\",\"delta\":-4,\"reasonCd\":\"FOUND\"}", admin).andExpect(status().isOk());
        postJson("/api/wms/trx/" + t2 + "/reverse", "{\"reason\":\"수량 잘못\"}", admin).andExpect(status().isConflict());
        assertThat(stock(a1, plain, "AVAILABLE")).isEqualByComparingTo("6");
    }

    @Test
    void 직접_SQL_출처_없는_입고는_막히고_대사는_LOT_수량_초과를_센다() throws Exception {
        MvcResult m = receive(a1, plain, "10", "AVAILABLE", req(), null).andExpect(status().isOk()).andReturn();
        long lot = accounts.query(comp, "select src_id from inv_trx where trx_id = ?", Long.class, trxId(m));
        Throwable t = catchThrowable(() -> accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'RCV_MAT', ?, ?, 'AVAILABLE', 1, 'EA', 'MANUAL', now(), 'KEY', 't')""", comp, plain, a1));
        assertThat(PgErrors.constraint(t).orElse("")).isEqualTo("ck_inv_trx_rcv_mat_src");
        postJson("/api/wms/stock/reconcile", "{}", admin).andExpect(jsonPath("$.data.rcvMatOver").value(0));
        // 같은 LOT 출처로 한 번 더(직접 SQL) → LOT 수량 10 < 원장 15
        accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, src_id, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'RCV_MAT', ?, ?, 'AVAILABLE', 5, 'EA', 'MAT_RECEIPT', ?, now(), 'KEY', 't')""", comp, plain, a1, lot);
        postJson("/api/wms/stock/reconcile", "{}", admin).andExpect(jsonPath("$.data.rcvMatOver").value(1));
    }

    @Test
    void E_조회만_사용자는_입고_403() throws Exception {
        RequestPostProcessor viewer = accounts.as(comp, viewerId, "viewer");
        mvc.perform(get("/api/wms/rcv/nodes").param("plantCd", "P1").with(viewer)).andExpect(status().isOk());
        postJson("/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + plain + ",\"qty\":1,\"stockStatus\":\"AVAILABLE\","
                + "\"clientReqId\":\"" + req() + "\"}", viewer).andExpect(status().isForbidden());
        assertThat(count("select count(*) from lot_master")).isZero();
    }

    @Test
    void A사로_B사_위치_품목_공급사_프린터() throws Exception {
        String b = accounts.newComp("RB");
        accounts.addPlant(b, "P1", "B사");
        long bu = accounts.newUser(b, "badmin", PW);
        accounts.grant(b, bu, "WMS", "wms-rcv", "SEARCH", "SAVE");
        RequestPostProcessor bp = accounts.as(b, bu, "badmin");
        long bWh = accounts.query(b, """
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', 'WH', 'RM-WH', 'B', 't', 't') returning node_id""", Long.class, b);
        long bItem = accounts.query(b, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'RM-P', 'B', 'RM', 'BUY', 'EA', 'N', 't', 't') returning item_id""", Long.class, b);
        // B사 사용자가 A사 위치·품목 id
        postJson("/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + plain + ",\"qty\":1,\"stockStatus\":\"AVAILABLE\","
                + "\"clientReqId\":\"" + req() + "\"}", bp).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.contains("NOT_FOUND", "NOT_FOUND")));
        // A사 공급사 코드·A사 프린터 → 없음
        postJson("/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + bWh + ",\"itemId\":" + bItem + ",\"qty\":1,\"stockStatus\":\"AVAILABLE\","
                + "\"supplierCd\":\"SUP1\",\"clientReqId\":\"" + req() + "\"}", bp).andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        postJson("/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + bWh + ",\"itemId\":" + bItem + ",\"qty\":1,\"stockStatus\":\"AVAILABLE\","
                + "\"printerId\":" + printer + ",\"clientReqId\":\"" + req() + "\"}", bp).andExpect(jsonPath("$.errors[0].field").value("printerId"));
        // A사 GTIN 은 B사 스캔에서 보이지 않음
        postJson("/api/wms/rcv/scan", "{\"plantCd\":\"P1\",\"text\":\"(01)08801234567893(10)X\"}", bp)
                .andExpect(jsonPath("$.data.itemId").doesNotExist());
        // A사 요청 id 를 B사가 다시 써도 A사 결과가 돌아가지 않는다(inbox·원장 모두 회사별)
        String r = req();
        receive(a1, plain, "2", "AVAILABLE", r, null).andExpect(status().isOk());
        postJson("/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + bWh + ",\"itemId\":" + bItem + ",\"qty\":1,\"stockStatus\":\"AVAILABLE\","
                + "\"clientReqId\":\"" + r + "\"}", bp).andExpect(status().isOk()).andExpect(jsonPath("$.data.replay").value(false));
        assertThat(accounts.query(b, "select count(*) from inv_trx", Integer.class)).isEqualTo(1);
        assertThat(count("select count(*) from inv_trx")).isEqualTo(1);
    }
}
