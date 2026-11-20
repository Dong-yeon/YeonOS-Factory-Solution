package io.smarthappy.module.wms.inv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 사용자 결정 D-B1 (docs/30 11.10) — 작업지시·재고 원장에 쓰인 품목은 기본 단위를 바꿀 수 없다.
 * 품목 화면 API(칸 오류 IN_USE), 직접 SQL(트리거), 원장 INSERT 와 단위 변경 동시(FOR SHARE 줄 세움), A사로 B사.
 */
@AutoConfigureMockMvc
class ItemUnitLockTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    RequestPostProcessor admin;
    long a1;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("UL");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "MST", "mst-item", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        a1 = accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', 'WH', 'WH', 'WH', 't', 't') returning node_id""", Long.class, comp);
    }

    long item(String cd) {
        return accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, ?, ?, 'SUB', 'BUY', 'EA', 'N', 't', 't') returning item_id""", Long.class, comp, cd, cd);
    }

    String ledgerSql() {
        return """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', 1, 'EA', 'MANUAL', now(), 'KEY', 't')""";
    }

    ResultActions changeUnit(long id, String cd, RequestPostProcessor who) throws Exception {
        int ver = accounts.query(comp, "select row_ver from mst_item where item_id = ?", Integer.class, id);
        return mvc.perform(put("/api/master/items/" + id).with(csrf()).contentType(MediaType.APPLICATION_JSON).with(who)
                .content("{\"itemCd\":\"" + cd + "\",\"itemNm\":\"" + cd + "\",\"itemType\":\"SUB\",\"procType\":\"BUY\",\"unitCd\":\"KG\","
                        + "\"lotCtrlYn\":\"N\",\"useYn\":\"Y\",\"rowVer\":" + ver + "}"));
    }

    String sqlError(String sql, Object... args) {
        Throwable t = catchThrowable(() -> accounts.exec(comp, sql, args));
        return t == null ? "OK" : PgErrors.sqlState(t).orElse("?") + " " + PgErrors.constraint(t).orElse("-");
    }

    @Test
    void 원장_지시에_쓰인_품목은_단위_변경_막힘_안_쓰인_품목은_됨() throws Exception {
        long used = item("LEDGER-1");
        accounts.exec(comp, ledgerSql(), comp, used, a1);
        changeUnit(used, "LEDGER-1", admin).andExpect(status().isConflict()).andExpect(jsonPath("$.errors[0].field").value("unitCd"))
                .andExpect(jsonPath("$.errors[0].code").value("IN_USE"));
        assertThat(sqlError("update mst_item set unit_cd = 'KG' where item_id = ?", used)).isEqualTo("23503 fk_mst_item_unit_in_use");
        // 작업지시에 쓰인 품목(지시 행은 소유자 연결로 준비 — MES 흐름 시험은 MES 쪽)
        long woItem = item("WO-1");
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = c.createStatement()) {
            st.execute("set session_replication_role = replica");
            st.executeUpdate("insert into prd_wo (comp_cd, wo_no, plant_cd, item_id, plan_qty, unit_cd, plan_start, plan_end, routing_id, "
                    + "created_by, updated_by) values ('" + comp + "', 'WO-1', 'P1', " + woItem + ", 1, 'EA', " + TestAccounts.COMP_TODAY + ", " + TestAccounts.COMP_TODAY + ", 0, 't', 't')");
        }
        changeUnit(woItem, "WO-1", admin).andExpect(jsonPath("$.errors[0].code").value("IN_USE"));
        assertThat(sqlError("update mst_item set unit_cd = 'KG' where item_id = ?", woItem)).isEqualTo("23503 fk_mst_item_unit_in_use");
        long free = item("FREE-1");
        changeUnit(free, "FREE-1", admin).andExpect(status().isOk());
        // 이름만 바꾸는 저장은 쓰인 품목도 된다
        assertThat(sqlError("update mst_item set item_nm = '새 이름' where item_id = ?", used)).isEqualTo("OK");
    }

    @Test
    void C_원장과_단위_변경이_동시면_줄_세운다() throws Exception {
        long id = item("RACE-1");
        CountDownLatch inserted = new CountDownLatch(1);
        CompletableFuture<Void> ledger = CompletableFuture.runAsync(() -> runner.run(comp, () -> {
            jdbc.update(ledgerSql(), comp, id, a1);
            inserted.countDown();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
        // 원장이 커밋 전(품목 행 FOR SHARE) — 단위 변경은 기다렸다가 그 원장을 보고 막힌다
        assertThat(sqlError("update mst_item set unit_cd = 'KG' where item_id = ?", id)).isEqualTo("23503 fk_mst_item_unit_in_use");
        ledger.get(10, TimeUnit.SECONDS);
        assertThat(accounts.query(comp, "select unit_cd from mst_item where item_id = ?", String.class, id)).isEqualTo("EA");
    }

    @Test
    void A사로_B사_원장은_B사_품목_단위를_막지_않는다() throws Exception {
        long mine = item("SAME");
        accounts.exec(comp, ledgerSql(), comp, mine, a1);
        String other = accounts.newComp("UB");
        long bItem = accounts.query(other, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'SAME', 'SAME', 'SUB', 'BUY', 'EA', 'N', 't', 't') returning item_id""", Long.class, other);
        assertThat(accounts.exec(other, "update mst_item set unit_cd = 'KG' where item_id = ?", bItem)).isEqualTo(1);
        assertThat(accounts.exec(other, "update mst_item set unit_cd = 'KG' where item_id = ?", mine)).isZero();      // A사 품목은 안 보임
        assertThat(accounts.query(comp, "select unit_cd from mst_item where item_id = ?", String.class, mine)).isEqualTo("EA");
    }
}
