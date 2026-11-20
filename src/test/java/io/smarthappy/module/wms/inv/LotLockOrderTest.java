package io.smarthappy.module.wms.inv;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.smarthappy.module.wms.inv.InvDtos.TrxReq;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 다건 원장의 LOT 선잠금 (docs/30 12.4.5 ②, MES 검토 2026-10-22).
 * MES 경로(POP 실적·생산 입고)는 "LOT B FOR UPDATE → 비관리 품목 X 재고" 순으로 잡는다. WMS 다건 원장이 "X 재고 → LOT B" 순이면 서로 기다려 교착 —
 * WMS 가 관련 LOT 을 lot_id 순으로 먼저 FOR SHARE 잡으므로 뒤 트랜잭션이 LOT 에서 기다렸다가 둘 다 끝난다.
 */
class LotLockOrderTest extends PostgresTestBase {

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    InventoryService inventory;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    long node;
    long plainX;
    long lotItem;
    long lotB;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("LO");
        accounts.addPlant(comp, "P1", "1공장");
        node = accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', 'WH', 'WH', 'WH', 't', 't') returning node_id""", Long.class, comp);
        plainX = item("SUB-X", "N");
        lotItem = item("RM-L", "Y");
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword())) {
            try (Statement st = c.createStatement()) {
                st.execute("set session_replication_role = replica");
            }
            try (PreparedStatement ps = c.prepareStatement("insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, "
                    + "occurred_at, created_by, updated_by) values (?, 'L-B', ?, 'RECEIVED', 1000, 'EA', now(), 't', 't') returning lot_id")) {
                ps.setString(1, comp);
                ps.setLong(2, lotItem);
                var rs = ps.executeQuery();
                rs.next();
                lotB = rs.getLong(1);
            }
        }
        // X 재고 행이 이미 있어야 두 쪽이 같은 inv_stock 행을 잡는다
        post(List.of(adj(plainX, null, "10")));
    }

    long item(String cd, String lotYn) {
        return accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, ?, ?, 'RM', 'BUY', 'EA', ?, 't', 't') returning item_id""", Long.class, comp, cd, cd, lotYn);
    }

    TrxReq adj(long item, Long lot, String qty) {
        return new TrxReq("ADJ_IN", item, node, "AVAILABLE", new BigDecimal(qty), null, "MANUAL", null, "FOUND", null, OffsetDateTime.now(),
                "KEY", null, lot);
    }

    void post(List<TrxReq> rows) {
        runner.callAs(comp, "tester", () -> inventory.post(rows, null));
    }

    BigDecimal stock(long item) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where item_id = ?", BigDecimal.class, item)
                .stripTrailingZeros();
    }

    @Test
    void C_다건_원장_비관리_X_와_LOT_B_가_MES_순서_LOT_B_다음_X_와_겹쳐도_교착_없이_둘_다() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        // MES 흉내: LOT B 를 먼저 잡고(계보 가드 FOR UPDATE) 1.5초 뒤 X 재고로
        CompletableFuture<Void> mes = CompletableFuture.runAsync(() -> runner.callAs(comp, "mes", () -> {
            jdbc.queryForObject("select lot_id from lot_master where lot_id = ? for update", Long.class, lotB);
            held.countDown();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return inventory.post(List.of(adj(plainX, null, "1")), null);
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        // WMS 다건: 줄1 = X, 줄2 = LOT B (실사 확정·여러 줄 입고 모양)
        post(List.of(adj(plainX, null, "2"), adj(lotItem, lotB, "5")));
        mes.get(20, TimeUnit.SECONDS);
        assertThat(stock(plainX)).isEqualByComparingTo("13");
        assertThat(stock(lotItem)).isEqualByComparingTo("5");
    }
}
