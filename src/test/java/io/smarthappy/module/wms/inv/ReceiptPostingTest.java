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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 생산 입고 → 재고 원장 (P4 2부 B1, docs/30 11장). MES 표(prd_wo·lot_master·prd_receipt)는 시험 준비에서만 소유자 연결로
 * 트리거를 끄고 직접 만든다(MES 흐름 시험은 MES 쪽) — WMS 가 보는 것은 "그 행을 읽어 원장에 옮기는 규칙" 이다.
 * 실시간 입고·정정(정정한 때), 멱등, 미이관 입고의 정정, 단위 불일치, 재고 안 둠, 이관(마감 달 실패·재실행), 대사 누락, A사로 B사, 동시성.
 */
@AutoConfigureMockMvc
class ReceiptPostingTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    ProductionReceiptPosting posting;

    @Autowired
    InventoryJobs jobs;

    String comp;
    RequestPostProcessor admin;
    long a1;
    long qc;
    long fg;
    long fgLot;
    long wo;
    long woLot;
    long lot;
    long lotL;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("RP");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "WMS", "wms-stock", "SEARCH", "FN1", "FN2");
        accounts.grant(comp, u, "WMS", "wms-trx", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long wh = node("WH", null, "WH");
        a1 = node("LOC", wh, "A-01");
        qc = node("LOC", wh, "QC-01");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, default_status, created_by, updated_by) "
                + "values (?, ?, 'QC', 'QC', 't', 't')", qc, comp);
        fg = item("FG", "N");
        fgLot = item("FGL", "Y");
        wo = ownerId("insert into prd_wo (comp_cd, wo_no, plant_cd, item_id, plan_qty, unit_cd, plan_start, plan_end, routing_id, status, released_at, "
                + "created_by, updated_by) values (?, 'WO-1', 'P1', ?, 100, 'EA', " + TestAccounts.COMP_TODAY + ", " + TestAccounts.COMP_TODAY + ", 0, 'IN_PROGRESS', now(), 't', 't') "
                + "returning wo_id", comp, fg);
        woLot = ownerId("insert into prd_wo (comp_cd, wo_no, plant_cd, item_id, plan_qty, unit_cd, plan_start, plan_end, routing_id, status, released_at, "
                + "created_by, updated_by) values (?, 'WO-2', 'P1', ?, 100, 'EA', " + TestAccounts.COMP_TODAY + ", " + TestAccounts.COMP_TODAY + ", 0, 'IN_PROGRESS', now(), 't', 't') "
                + "returning wo_id", comp, fgLot);
        lot = lot("L-1", fg, wo);
        lotL = lot("L-2", fgLot, woLot);
    }

    long node(String type, Long parent, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd);
    }

    long item(String cd, String lotYn) {
        return accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, ?, ?, 'FG', 'MAKE', 'EA', ?, 't', 't') returning item_id""", Long.class, comp, cd, cd, lotYn);
    }

    long lot(String no, long item, long woId) throws Exception {
        return ownerId("insert into lot_master (comp_cd, lot_no, item_id, origin, wo_id, qty_created, unit_cd, occurred_at, created_by, updated_by) "
                + "values (?, ?, ?, 'PRODUCED', ?, 100, 'EA', now(), 't', 't') returning lot_id", comp, no, item, woId);
    }

    /** MES 입고 행(소유자·트리거 끔). minutesAgo = 발생 시각 */
    long receipt(long woId, long itemId, long lotId, long node, int qty, String occurredSql, Long reversalOf, String reason) throws Exception {
        return ownerId("insert into prd_receipt (comp_cd, plant_cd, wo_id, item_id, lot_id, node_id, qty, unit_cd, client_req_id, reason, "
                + "occurred_at, entry_method, recorded_by, reversal_of_id) values (?, 'P1', ?, ?, ?, ?, ?, 'EA', ?, ?, " + occurredSql
                + ", ?, 'mes-user', ?) returning receipt_id", comp, woId, itemId, lotId, node, qty,
                reversalOf == null ? "req-" + System.nanoTime() : null, reason, reversalOf == null ? "SCAN" : "CORRECT", reversalOf);
    }

    long receipt(int qty) throws Exception {
        return receipt(wo, fg, lot, a1, qty, "now()", null, null);
    }

    static long ownerId(String sql, Object... args) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword())) {
            try (Statement st = c.createStatement()) {
                st.execute("set session_replication_role = replica");
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }
    }

    long postReceipt(long receiptId) {
        return runner.call(comp, () -> posting.post(receiptId));
    }

    BigDecimal stock(long node, long item) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and item_id = ?", BigDecimal.class, node,
                item).stripTrailingZeros();
    }

    String err(Runnable r) {
        Throwable t = catchThrowable(r::run);
        if (t instanceof BusinessException b) {
            return b.fieldErrors().isEmpty() ? b.code().name() : b.fieldErrors().get(0).code();
        }
        return t == null ? "OK" : PgErrors.sqlState(t).orElse("?") + " " + PgErrors.constraint(t).orElse("-");
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void 입고는_RCV_PRD_위치_기본상태_비관리품목은_LOT_비움_같은_입고는_한번() throws Exception {
        long r1 = receipt(10);
        long t1 = postReceipt(r1);
        assertThat(postReceipt(r1)).isEqualTo(t1);                                                   // 멱등
        assertThat(accounts.query(comp, "select trx_type || ':' || qty::int || ':' || stock_status || ':' || coalesce(lot_id::text, '-') || ':'"
                + " || src_type || ':' || src_id || ':' || entry_method || ':' || recorded_by from inv_trx where trx_id = ?", String.class, t1))
                .isEqualTo("RCV_PRD:10:AVAILABLE:-:PRD_RECEIPT:" + r1 + ":SCAN:mes-user");
        assertThat(accounts.query(comp, "select occurred_at = (select occurred_at from prd_receipt where receipt_id = ?) from inv_trx where trx_id = ?",
                Boolean.class, r1, t1)).isTrue();
        // QC 구역 → 검사 대기, LOT 관리 품목 → 입고 LOT
        long r2 = receipt(woLot, fgLot, lotL, qc, 7, "now()", null, null);
        long t2 = postReceipt(r2);
        assertThat(accounts.query(comp, "select stock_status || ':' || lot_id from inv_trx where trx_id = ?", String.class, t2))
                .isEqualTo("QC:" + lotL);
        assertThat(stock(a1, fg)).isEqualByComparingTo("10");
        assertThat(stock(qc, fgLot)).isEqualByComparingTo("7");
    }

    @Test
    void 정정은_원래_입고의_역분개_정정한_때로() throws Exception {
        long r1 = receipt(wo, fg, lot, a1, 10, "now() - interval '40 days'", null, null);
        postReceipt(r1);
        long r2 = receipt(wo, fg, lot, a1, -10, "now()", r1, "수량 잘못");
        long t2 = postReceipt(r2);
        assertThat(accounts.query(comp, "select entry_method || ':' || qty::int || ':' || (reversal_of_id is not null) || ':' || remark "
                + "from inv_trx where trx_id = ?", String.class, t2)).isEqualTo("CORRECT:-10:true:[PRD_RECEIPT:" + r1 + "] 수량 잘못");
        assertThat(accounts.query(comp, "select occurred_at > now() - interval '1 minute' from inv_trx where trx_id = ?", Boolean.class, t2))
                .isTrue();                                                                    // 원래 시각(40일 전)이 아니라 정정한 때
        assertThat(stock(a1, fg)).isEqualByComparingTo("0");
        assertThat(postReceipt(r2)).isEqualTo(t2);
    }

    /** 생산 입고 원장은 수불 원장 화면에서 되돌리지 못한다(400 MES_SOURCE) — MES 입고 정정(출처 경로)은 그대로 된다 */
    @Test
    void 입고_원장은_원장_화면에서_되돌리지_못하고_MES_정정은_된다() throws Exception {
        long r1 = receipt(wo, fg, lot, a1, 10, "now()", null, null);
        long t1 = postReceipt(r1);
        mvc.perform(post("/api/wms/trx/" + t1 + "/reverse").with(csrf()).with(admin).contentType("application/json").content("{\"reason\":\"사람이 되돌림\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("MES_SOURCE"));
        assertThat(stock(a1, fg)).isEqualByComparingTo("10");
        long r2 = receipt(wo, fg, lot, a1, -10, "now()", r1, "수량 잘못");
        long t2 = postReceipt(r2);
        assertThat(accounts.query(comp, "select reversal_of_id from inv_trx where trx_id = ?", Long.class, t2)).isEqualTo(t1);
        assertThat(stock(a1, fg)).isEqualByComparingTo("0");
    }

    @Test
    void 아직_옮기지_않은_입고를_정정하면_입고를_이관으로_먼저() throws Exception {
        long r1 = receipt(10);
        long r2 = receipt(wo, fg, lot, a1, -10, "now()", r1, "취소");
        postReceipt(r2);
        assertThat(accounts.query(comp, "select string_agg(entry_method || ':' || qty::int, ',' order by trx_id) from inv_trx", String.class))
                .isEqualTo("IMPORT:10,CORRECT:-10");
        assertThat(postReceipt(r1)).isEqualTo(accounts.query(comp, "select min(trx_id) from inv_trx", Long.class));
    }

    // =====================================================================
    // 이상 사용
    // =====================================================================

    @Test
    void 정정_때_재고가_이미_나갔으면_409_단위가_바뀌었으면_거부_재고안둠_위치는_실시간만_거부() throws Exception {
        long r1 = receipt(10);
        postReceipt(r1);
        accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_OUT', ?, ?, 'AVAILABLE', -8, 'EA', 'MANUAL', now(), 'KEY', 't')""", comp, fg, a1);
        long r2 = receipt(wo, fg, lot, a1, -10, "now()", r1, "취소");
        assertThat(err(() -> postReceipt(r2))).isEqualTo("INSUFFICIENT_STOCK");
        // 단위: 입고 뒤 품목 기본 단위를 바꿈(D-B1 빈틈)
        long r3 = receipt(5);
        ownerId("update mst_item set unit_cd = 'KG' where item_id = ? returning item_id", fg);
        assertThat(err(() -> postReceipt(r3))).isEqualTo("UNIT");
        ownerId("update mst_item set unit_cd = 'EA' where item_id = ? returning item_id", fg);
        // 재고 안 둠 위치: 실시간 입고는 거부, 이관은 그때 사실이라 받는다
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, stock_yn, created_by, updated_by) values (?, ?, 'N', 't', 't')", a1, comp);
        long r4 = receipt(3);
        assertThat(err(() -> postReceipt(r4))).isEqualTo("STOCK_NOT_ALLOWED");
        runner.run(comp, () -> posting.post(r4, true));
        assertThat(accounts.query(comp, "select entry_method from inv_trx where src_id = ? and src_type = 'PRD_RECEIPT'", String.class, r4))
                .isEqualTo("IMPORT");
        assertThat(err(() -> postReceipt(999_999_999L))).isEqualTo("NOT_FOUND");
    }

    @Test
    void 이관은_입고마다_따로_마감_달은_실패로_남기고_다시_돌리면_그것만() throws Exception {
        long old = receipt(wo, fg, lot, a1, 4, "date_trunc('month', now() at time zone 'Asia/Seoul') at time zone 'Asia/Seoul' - interval '1 day'",
                null, null);
        long r1 = receipt(10);
        receipt(wo, fg, lot, a1, -10, "now()", r1, "취소");
        String lastMonth = accounts.query(comp, "select to_char(date_trunc('month', now() at time zone 'Asia/Seoul') - interval '1 day', 'YYYYMM')",
                String.class);
        accounts.exec(comp, "insert into sys_period_close (comp_cd, plant_cd, module_cd, period, status, closed_at, closed_by, created_by, updated_by) "
                + "values (?, 'P1', 'INV', ?, 'CLOSED', now(), 't', 't', 't')", comp, lastMonth);
        mvc.perform(get("/api/wms/stock/backfill").with(admin)).andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.pending").value(3)).andExpect(jsonPath("$.data.lastRun").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(post("/api/wms/stock/backfill").with(csrf()).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.posted").value(2)).andExpect(jsonPath("$.data.failed").value(1))
                .andExpect(jsonPath("$.data.detail").value(org.hamcrest.Matchers.containsString("\"receiptId\": " + old)))
                .andExpect(jsonPath("$.data.detail").value(org.hamcrest.Matchers.containsString("PERIOD_CLOSED")));
        mvc.perform(post("/api/wms/stock/backfill").with(csrf()).with(admin))
                .andExpect(jsonPath("$.data.existed").value(2)).andExpect(jsonPath("$.data.failed").value(1));
        accounts.exec(comp, "update sys_period_close set status = 'REOPENED', reopened_at = now(), reopened_by = 't', reason = '이관' where period = ?",
                lastMonth);
        mvc.perform(post("/api/wms/stock/backfill").with(csrf()).with(admin))
                .andExpect(jsonPath("$.data.posted").value(1)).andExpect(jsonPath("$.data.failed").value(0));
        assertThat(stock(a1, fg)).isEqualByComparingTo("4");
        // 대사: 이관을 끝낸 회사는 원장에 빠진 생산 입고를 센다 → 알림
        int before = accounts.query(comp, "select count(*) from sys_outbox where topic = 'NOTIFY'", Integer.class);
        assertThat(jobs.reconcile().failed()).isEmpty();
        assertThat(accounts.query(comp, "select missing_receipts from inv_reconcile_run order by run_id desc limit 1", Integer.class)).isZero();
        receipt(2);                                                                           // MES 호출이 빠진 입고
        jobs.reconcile();
        assertThat(accounts.query(comp, "select missing_receipts from inv_reconcile_run order by run_id desc limit 1", Integer.class)).isEqualTo(1);
        assertThat(accounts.query(comp, "select count(*) from sys_outbox where topic = 'NOTIFY'", Integer.class)).isEqualTo(before + 1);
    }

    @Test
    void 이관_전에는_대사가_누락을_세지_않음() throws Exception {
        receipt(2);
        jobs.reconcile();
        assertThat(accounts.query(comp, "select missing_receipts is null from inv_reconcile_run order by run_id desc limit 1", Boolean.class))
                .isTrue();
    }

    @Test
    void C_같은_입고_동시_두번은_한번만() throws Exception {
        long r1 = receipt(10);
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<String>> runs = List.of(1, 2).stream().map(i -> CompletableFuture.supplyAsync(() -> {
            try {
                go.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return err(() -> postReceipt(r1));
        })).toList();
        go.countDown();
        List<String> out = runs.stream().map(CompletableFuture::join).sorted().toList();
        assertThat(out).containsAnyOf("OK").hasSize(2);
        assertThat(out.get(1)).isIn("OK", "DUPLICATE_REQUEST");                                 // 뒤 것은 앞 것을 보거나(OK) 키에서 막힘
        assertThat(accounts.query(comp, "select count(*) from inv_trx where src_id = ?", Integer.class, r1)).isEqualTo(1);
        assertThat(stock(a1, fg)).isEqualByComparingTo("10");
    }

    @Test
    void 직접_SQL_LOT_규칙() {
        String ins = """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, lot_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, ?, 'AVAILABLE', 1, 'EA', 'MANUAL', now(), 'KEY', 't')""";
        assertThat(err(() -> accounts.exec(comp, ins, comp, fgLot, a1, null))).isEqualTo("23514 ck_inv_trx_lot_item");    // LOT 품목에 LOT 없음
        assertThat(err(() -> accounts.exec(comp, ins, comp, fg, a1, lot))).isEqualTo("23514 ck_inv_trx_lot_item");        // 비관리에 LOT
        assertThat(err(() -> accounts.exec(comp, ins, comp, fgLot, a1, lot))).isEqualTo("23503 fk_inv_trx_lot_comp");    // 다른 품목 LOT
        assertThat(err(() -> accounts.exec(comp, ins, comp, fgLot, a1, lotL))).isEqualTo("OK");
    }

    @Test
    void A사로_B사_입고_이관_대사() throws Exception {
        long r1 = receipt(10);
        String other = accounts.newComp("RB");
        long bu = accounts.newUser(other, "badmin", PW);
        accounts.grant(other, bu, "WMS", "wms-stock", "SEARCH", "FN2");
        RequestPostProcessor b = accounts.as(other, bu, "badmin");
        assertThat(err(() -> runner.call(other, () -> posting.post(r1)))).isEqualTo("NOT_FOUND");
        mvc.perform(get("/api/wms/stock/backfill").with(b)).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(post("/api/wms/stock/backfill").with(csrf()).with(b)).andExpect(jsonPath("$.data.total").value(0));
        assertThat(accounts.query(comp, "select count(*) from inv_trx", Integer.class)).isZero();
        // 직접 SQL: B사 범위에서 A사 LOT 으로 원장
        assertThat(err(() -> accounts.exec(other, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, lot_id, stock_status, qty, unit_cd, src_type,
                                     occurred_at, entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, ?, 'AVAILABLE', 1, 'EA', 'MANUAL', now(), 'KEY', 't')""", other, fgLot, a1, lotL)))
                .startsWith("23503");
    }

    @Test
    void 권한() throws Exception {
        long v = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, v, "VIEW", "wms-stock", "SEARCH");
        mvc.perform(post("/api/wms/stock/backfill").with(csrf()).with(accounts.as(comp, v, "viewer"))).andExpect(status().isForbidden());
    }
}
