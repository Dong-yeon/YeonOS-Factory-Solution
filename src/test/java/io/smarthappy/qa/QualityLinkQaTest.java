package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.qms.link.InspPlanLookup;
import io.smarthappy.module.wms.inv.ProductionReceiptPosting;
import io.smarthappy.module.wms.link.QualityStockLink;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA — B3 품질 상태 연동(WMS #502·#513, QMS #522) 이상 사용. 모듈 시험(QualityStockTest·QmsStockLinkTest)이 덮지 않은 곳:
 * 공장 스위치를 판정과 무효·종결 사이에 바꿈, 같은 LOT 의 NCR 두 건이 동시에 폐기. 계약 {@link QualityStockLink} 를 직접 부르고
 * 검사·NCR 행은 소유자 연결(트리거 끔)로 넣는다 — QualityStockTest 와 같은 방식. 분석 docs/qa/2026-10-24_b3-mail-pcn.md
 */
@AutoConfigureMockMvc
class QualityLinkQaTest extends PostgresTestBase {

    static final String PW = "Qa-quality-link-51";
    static final ExecutorService POOL = Executors.newFixedThreadPool(3);

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    QualityStockLink link;

    @MockitoBean
    InspPlanLookup plans;

    @Autowired
    ProductionReceiptPosting prdPosting;

    String comp;
    RequestPostProcessor admin;
    long loc;
    long item;
    int seq;

    @AfterAll
    static void stop() {
        POOL.shutdownNow();
    }

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("QL");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "WMS", "wms-rcv", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long wh = accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', 'WH', 'QL-WH', 'QL-WH', 't', 't') returning node_id""", Long.class, comp);
        loc = accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, 'LOC', 'QL-01', 'QL-01', 't', 't') returning node_id""", Long.class, comp, wh);
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'QL-RM', 'QA 자재', 'RM', 'BUY', 'EA', 'Y', 't', 't')""", comp);
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'QL-S', '공급사', 'PURCHASE', 't', 't')",
                comp);
        item = accounts.query(comp, "select item_id from mst_item where item_cd = 'QL-RM'", Long.class);
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) "
                + "values (?, 'P1', 'WMS_QUALITY_LINK', 'Y', 't', 't')", comp);
        when(plans.lines(any(Long.class), isNull(), any(), any())).thenReturn(List.of());
    }

    void policy(String v) {
        accounts.exec(comp, "update sys_policy set policy_value = ? where policy_key = 'WMS_QUALITY_LINK' and plant_cd = 'P1'", v);
    }

    /** 자재 입고(상태 지정) → LOT id */
    long receive(String qty, String st) throws Exception {
        MvcResult m = mvc.perform(post("/api/wms/rcv").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantCd\":\"P1\",\"nodeId\":" + loc + ",\"itemId\":" + item + ",\"qty\":" + qty + ",\"stockStatus\":\"" + st
                        + "\",\"supplierCd\":\"QL-S\",\"clientReqId\":\"ql-" + (++seq) + "-" + System.nanoTime() + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String lotNo = JsonPath.read(m.getResponse().getContentAsString(), "$.data.lotNo");
        return accounts.query(comp, "select lot_id from lot_master where lot_no = ?", Long.class, lotNo);
    }

    BigDecimal qty(long lot, String st) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where lot_id = ? and stock_status = ?", BigDecimal.class, lot, st)
                .stripTrailingZeros();
    }

    long owner(String sql, Object... args) throws Exception {
        try (Connection con = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = con.createStatement()) {
            st.execute("set session_replication_role = replica");
            try (var ps = con.prepareStatement(sql)) {
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

    long insp(long lot, String result) throws Exception {
        return owner("""
                insert into qms_insp (comp_cd, insp_no, insp_type, lot_id, item_id, insp_plan_id, lot_qty, status, result, occurred_at,
                                      judged_at, judged_by, created_by, updated_by)
                values (?, ?, 'RECEIVE', ?, ?, 0, 10, 'JUDGED', ?, now(), now(), 'qa1', 'qa1', 'qa1') returning insp_id""",
                comp, "QL-I-" + (++seq) + "-" + System.nanoTime() % 100000, lot, item, result);
    }

    long ncr(long lot, String... disps) throws Exception {
        long id = owner("""
                insert into qms_ncr (comp_cd, ncr_no, source, lot_id, item_id, unit_cd, defect_id, nc_qty, status, closed_at, closed_by,
                                     occurred_at, description, hold_yn, created_by, updated_by)
                values (?, ?, 'INSP', ?, ?, 'EA', 0, 10, 'CLOSED', now(), 'qa1', now(), '불량', 'N', 'qa1', 'qa1') returning ncr_id""",
                comp, "QL-N-" + (++seq) + "-" + System.nanoTime() % 100000, lot, item);
        int no = 0;
        for (String d : disps) {
            String[] p = d.split(":");
            owner("insert into qms_ncr_disp (comp_cd, ncr_id, line_no, disp_type, qty, created_by) values (?, ?, ?, ?, ?::numeric, 'qa1') returning disp_id",
                    comp, id, ++no, p[0], p[1]);
        }
        return id;
    }

    void judged(long inspId) {
        runner.callAs(comp, "qa1", () -> {
            link.onInspJudged(inspId);
            return null;
        });
    }

    Throwable voided(long inspId) throws Exception {
        owner("update qms_insp set status = 'VOID', void_reason = '무효', voided_at = now(), voided_by = 'qa1' where insp_id = ? returning insp_id", inspId);
        return catchThrowable(() -> runner.callAs(comp, "qa1", () -> {
            link.onInspVoided(inspId);
            return null;
        }));
    }

    Throwable closed(long ncrId) {
        return catchThrowable(() -> runner.callAs(comp, "qa1", () -> {
            link.onNcrClosed(ncrId);
            return null;
        }));
    }

    int lockWaiters() {
        return jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()",
                Integer.class);
    }

    // ------------------------------------------------------------------ 시험

    /** 스위치를 판정 뒤에 끄고 무효 — 판정이 남긴 원장 짝은 스위치와 무관하게 되돌린다(일관) */
    @Test
    void 판정_뒤_스위치를_꺼도_무효는_판정을_되돌린다() throws Exception {
        long lot = receive("10", "QC");
        long ok = insp(lot, "OK");
        judged(ok);
        assertThat(qty(lot, "AVAILABLE")).isEqualByComparingTo("10");
        policy("N");
        assertThat(voided(ok)).isNull();
        assertThat(qty(lot, "QC")).isEqualByComparingTo("10");
        assertThat(qty(lot, "AVAILABLE")).isEqualByComparingTo("0");
    }

    /**
     * B-1(상위 결정 2026-10-24, docs/30 12.15.5): 스위치는 새 판정 반영만 끈다 — NG 판정이 켜짐 때 반영된 LOT 은 스위치를 끈 뒤에도
     * NCR 폐기 종결을 반영한다(무효가 스위치와 무관하게 되돌리는 것과 같이). 고치기 전에는 재고가 BLOCKED 10 그대로 남았다
     */
    @Test
    void 판정_뒤_스위치를_꺼도_NCR_폐기_종결은_재고에_간다() throws Exception {
        long lot = receive("10", "QC");
        judged(insp(lot, "NG"));
        assertThat(qty(lot, "BLOCKED")).isEqualByComparingTo("10");
        policy("N");
        long n = ncr(lot, "SCRAP:10");
        assertThat(closed(n)).isNull();
        assertThat(qty(lot, "BLOCKED")).as("NCR 폐기 종결 → 재고에서 빠짐").isEqualByComparingTo("0");
        assertThat(accounts.query(comp, "select count(*) from inv_trx where src_type = 'QMS_NCR' and trx_type = 'SCRAP'", Integer.class)).isEqualTo(1);
    }

    /**
     * 같은 LOT 의 NCR 두 건이 동시에 폐기 종결 — BLOCKED 10 에 6 + 6. 첫 건이 칸을 잠근 채 커밋을 미루는 동안 둘째가 잠금을 기다리는 것을
     * 관찰한 뒤 첫 건을 커밋 → 둘째는 잠금 뒤 다시 읽은 수량(4)으로 409 WMS_QUALITY_SHORT. 폐기는 한 번(6)만, 남은 BLOCKED 4
     */
    @Test
    void 같은_LOT_NCR_두_건이_동시에_폐기하면_하나만_되고_다른_하나는_부족_409() throws Exception {
        long lot = receive("10", "QC");
        judged(insp(lot, "NG"));
        long n1 = ncr(lot, "SCRAP:6");
        long n2 = ncr(lot, "SCRAP:6");
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        Future<Throwable> first = POOL.submit(() -> catchThrowable(() -> runner.callAs(comp, "qa1", () -> {
            link.onNcrClosed(n1);
            firstDone.countDown();
            try {
                go.await(30, TimeUnit.SECONDS);                // 모두 관찰한 뒤 커밋
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        })));
        assertThat(firstDone.await(20, TimeUnit.SECONDS)).isTrue();
        int before = lockWaiters();
        Future<Throwable> second = POOL.submit(() -> closed(n2));
        for (int i = 0; i < 200 && lockWaiters() <= before; i++) {
            Thread.sleep(50);
        }
        assertThat(lockWaiters()).as("둘째가 칸 잠금을 기다린다").isGreaterThan(before);
        go.countDown();
        assertThat(first.get(20, TimeUnit.SECONDS)).isNull();
        Throwable t = second.get(20, TimeUnit.SECONDS);
        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).code()).isEqualTo(HbErrorCode.WMS_QUALITY_SHORT);
        assertThat(qty(lot, "BLOCKED")).isEqualByComparingTo("4");
        assertThat(accounts.query(comp, "select count(*) from inv_trx where src_type = 'QMS_NCR' and trx_type = 'SCRAP'", Integer.class)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ B-2 (#538) — NG 무효와 추가 생산 입고가 겹침

    /** 생산 LOT(완제품, 작업지시 하나) → {lotId, woId, itemId}. FINAL 검사계획이 있다고 답하게(첫 입고 = QC) */
    long[] prdLot() throws Exception {
        Long fg = accounts.query(comp, "select max(item_id) from mst_item where item_cd = 'QL-FG'", Long.class);
        if (fg == null) {
            fg = accounts.query(comp, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                    values (?, 'QL-FG', 'QA 완제품', 'FG', 'MAKE', 'EA', 'Y', 't', 't') returning item_id""", Long.class, comp);
        }
        when(plans.lines(eq(fg), isNull(), eq("FINAL"), any())).thenReturn(List.of(new InspPlanLookup.PlanLine(2L, "A", 1, 1L, "C1", "외관",
                null, "ATTRIBUTE", null, null, null, null, null, null, "VISUAL", null, "EVERY_LOT", null, null)));
        long wo = owner("insert into prd_wo (comp_cd, wo_no, plant_cd, item_id, plan_qty, unit_cd, plan_start, plan_end, routing_id, status, "
                + "released_at, created_by, updated_by) values (?, ?, 'P1', ?, 100, 'EA', " + TestAccounts.COMP_TODAY + ", " + TestAccounts.COMP_TODAY
                + ", 0, 'IN_PROGRESS', now(), 't', 't') returning wo_id", comp, "QL-WO-" + (++seq), fg);
        long lot = owner("insert into lot_master (comp_cd, lot_no, item_id, origin, wo_id, qty_created, unit_cd, occurred_at, created_by, updated_by) "
                + "values (?, ?, ?, 'PRODUCED', ?, 100, 'EA', now(), 't', 't') returning lot_id", comp, "QL-PL-" + (++seq), fg, wo);
        return new long[] {lot, wo, fg};
    }

    long prdReceipt(long[] l, int qty) throws Exception {
        return owner("insert into prd_receipt (comp_cd, plant_cd, wo_id, item_id, lot_id, node_id, qty, unit_cd, client_req_id, occurred_at, "
                + "entry_method, recorded_by) values (?, 'P1', ?, ?, ?, ?, ?, 'EA', ?, now(), 'SCAN', 'mes-user') returning receipt_id",
                comp, l[1], l[2], l[0], loc, qty, "ql-req-" + System.nanoTime());
    }

    long finalInsp(long[] l, String result) throws Exception {
        return owner("""
                insert into qms_insp (comp_cd, insp_no, insp_type, lot_id, item_id, insp_plan_id, lot_qty, status, result, occurred_at,
                                      judged_at, judged_by, created_by, updated_by)
                values (?, ?, 'FINAL', ?, ?, 0, 10, 'JUDGED', ?, now(), now(), 'qa1', 'qa1', 'qa1') returning insp_id""",
                comp, "QL-F-" + (++seq) + "-" + System.nanoTime() % 100000, l[0], l[2], result);
    }

    /**
     * NG 판정 LOT 에 추가 생산 입고(+4)가 판정을 읽어 BLOCKED 로 게시한 뒤 커밋을 미루는 동안, 그 NG 를 무효로 하고 반영(되돌림·이어받은 몫 QC)을
     * 먼저 끝냄 → 입고 커밋. 바라는 끝: 살아 있는 NG 가 없으니 추가분도 QC(무효가 이어받은 몫을 놓치면 BLOCKED 4 가 판정 없이 남는다).
     * 실측(2026-10-26): 무효 반영이 입고 트랜잭션의 잠금을 기다렸다가(대기 1) 커밋 뒤 이어받은 몫까지 QC 로 — 끼어들 틈 없음
     */
    @Test
    void B2_NG_무효와_추가_입고가_겹쳐도_추가분이_BLOCKED로_남지_않는다() throws Exception {
        long[] l = prdLot();
        long first = prdReceipt(l, 10);
        runner.callAs(comp, "mes-user", () -> prdPosting.post(first));
        assertThat(qty(l[0], "QC")).isEqualByComparingTo("10");
        long ng = finalInsp(l, "NG");
        judged(ng);
        assertThat(qty(l[0], "BLOCKED")).isEqualByComparingTo("10");
        long extra = prdReceipt(l, 4);
        CountDownLatch posted = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        Future<Throwable> receipt = POOL.submit(() -> catchThrowable(() -> runner.callAs(comp, "mes-user", () -> {
            prdPosting.post(extra);                         // 판정(NG)을 읽어 BLOCKED 로 게시 — 아직 커밋 전
            posted.countDown();
            try {
                go.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        })));
        assertThat(posted.await(20, TimeUnit.SECONDS)).isTrue();
        Future<Throwable> voiding = POOL.submit(() -> voided(ng));
        boolean voidFirst = false;
        for (int i = 0; i < 20 && !(voidFirst = voiding.isDone()); i++) {
            Thread.sleep(50);
        }
        int waiting = lockWaiters();
        go.countDown();
        assertThat(receipt.get(20, TimeUnit.SECONDS)).isNull();
        assertThat(voiding.get(20, TimeUnit.SECONDS)).isNull();
        System.out.println("[B2] 무효가 입고 커밋 전에 끝남=" + voidFirst + ", 그때 잠금 대기=" + waiting);
        assertThat(accounts.query(comp, "select count(*) from qms_insp where lot_id = ? and status = 'JUDGED' and result = 'NG'", Integer.class, l[0]))
                .isZero();
        assertThat(qty(l[0], "BLOCKED")).as("살아 있는 NG 가 없는데 BLOCKED 가 남으면 안 된다").isEqualByComparingTo("0");
        assertThat(qty(l[0], "QC")).isEqualByComparingTo("14");
    }
}
