package io.smarthappy.module.wms.inv;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.smarthappy.module.wms.inv.InvDtos.PostResult;
import io.smarthappy.module.wms.inv.InvDtos.TrxReq;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 재고 칸 잠금 순서 (docs/30 11.6 ③, QA QB-1) — 한 트랜잭션에서 칸 여러 개를 건드리면 (품목, 위치, LOT, 상태) 순으로 넣는다.
 * 행 id·req_seq 는 받은 순서 그대로. 순서가 거꾸로인 두 다건 원장(실사 확정 칸 A→B · 이동 B→A 모양)이 겹쳐도 교착 없이 둘 다 끝난다.
 */
class CellLockOrderTest extends PostgresTestBase {

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    InventoryService inventory;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    long nodeA;
    long nodeB;
    long item;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("CL");
        accounts.addPlant(comp, "P1", "1공장");
        long wh = node(null, "WH", "WH");
        nodeA = node(wh, "LOC", "A");
        nodeB = node(wh, "LOC", "B");
        item = accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'X', 'X', 'RM', 'BUY', 'EA', 'N', 't', 't') returning item_id""", Long.class, comp);
        // 두 칸 모두 재고 행이 있어야 서로 같은 inv_stock 행을 잡는다
        post(List.of(adj(nodeA, "10"), adj(nodeB, "10")), null);
    }

    long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd);
    }

    TrxReq adj(long node, String qty) {
        return new TrxReq("ADJ_IN", item, node, "AVAILABLE", new BigDecimal(qty), null, "MANUAL", null, "FOUND", null, OffsetDateTime.now(),
                "KEY", null, null);
    }

    PostResult post(List<TrxReq> rows, String req) {
        return runner.callAs(comp, "tester", () -> inventory.post(rows, req));
    }

    BigDecimal stock(long node) {
        return accounts.query(comp, "select qty from inv_stock where node_id = ? and item_id = ?", BigDecimal.class, node, item)
                .stripTrailingZeros();
    }

    @Test
    void 넣는_순서는_칸_순서이고_행_id와_req_seq는_받은_순서() {
        // nodeB > nodeA 이므로 [B, A] 로 주면 A 를 먼저 넣는다
        PostResult r = post(List.of(adj(nodeB, "1"), adj(nodeA, "2")), "cell-order-" + UUID.randomUUID());
        long forB = r.trxIds().get(0);
        long forA = r.trxIds().get(1);
        assertThat(accounts.query(comp, "select node_id from inv_trx where trx_id = ?", Long.class, forB)).isEqualTo(nodeB);
        assertThat(accounts.query(comp, "select node_id from inv_trx where trx_id = ?", Long.class, forA)).isEqualTo(nodeA);
        assertThat(forA).isLessThan(forB);                                     // A 를 먼저 INSERT
        assertThat(accounts.query(comp, "select req_seq from inv_trx where trx_id = ?", Integer.class, forB)).isEqualTo(1);
        // 같은 요청을 다시 보내면 받은 순서 그대로
        String req = accounts.query(comp, "select client_req_id from inv_trx where trx_id = ?", String.class, forB);
        assertThat(post(List.of(adj(nodeB, "1"), adj(nodeA, "2")), req).trxIds()).containsExactly(forB, forA);
    }

    @Test
    void C_칸_순서가_거꾸로인_두_다건_원장이_겹쳐도_교착_없이_둘_다() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        // 칸 A 를 1.5초 쥐고 있는 동안 [A, B] 와 [B, A] 두 건을 보낸다. 칸 순서가 없으면 뒤 건이 B 를 먼저 잡아
        // A 를 풀었을 때 앞 건(A 쥠 → B 기다림)과 서로 기다린다(교착 → 한 건 실패)
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> runner.callAs(comp, "holder", () -> {
            jdbc.queryForObject("select stock_id from inv_stock where node_id = ? and item_id = ? for update", Long.class, nodeA, item);
            held.countDown();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<PostResult> ab = CompletableFuture.supplyAsync(() -> post(List.of(adj(nodeA, "1"), adj(nodeB, "1")), null));
        CompletableFuture<PostResult> ba = CompletableFuture.supplyAsync(() -> post(List.of(adj(nodeB, "2"), adj(nodeA, "2")), null));
        holder.get(20, TimeUnit.SECONDS);
        ab.get(20, TimeUnit.SECONDS);
        ba.get(20, TimeUnit.SECONDS);
        assertThat(stock(nodeA)).isEqualByComparingTo("13");
        assertThat(stock(nodeB)).isEqualByComparingTo("13");
    }
}
