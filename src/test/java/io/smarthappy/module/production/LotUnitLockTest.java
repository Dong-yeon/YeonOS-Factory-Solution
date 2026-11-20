package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * 품목 기본 단위 변경 금지에 LOT 사용 (docs/25 4.32, WMS 합의 Q1) — 자재 LOT 만 있는 품목도 단위를 바꿀 수 없다.
 * 품목 화면 API(칸 오류 IN_USE), 직접 SQL(트리거), LOT 등록과 단위 변경 동시(양쪽 순서 모두 FOR SHARE 줄 세움), A사로 B사.
 */
@AutoConfigureMockMvc
class LotUnitLockTest extends PostgresTestBase {

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

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("LU");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "MST", "mst-item", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
    }

    private long item(String c, String cd) {
        return accounts.query(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, ?, ?, 'SUB', 'BUY', 'EA', 'Y', 't', 't') returning item_id""", Long.class, c, cd, cd);
    }

    /** 자재 LOT(지시 없음, 품목 단위 EA) */
    private static final String LOT_SQL = """
            insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
            values (?, ?, ?, 'RECEIVED', 10, 'EA', now(), 't', 't')""";

    private ResultActions changeUnit(long id, String cd) throws Exception {
        int ver = accounts.query(comp, "select row_ver from mst_item where item_id = ?", Integer.class, id);
        return mvc.perform(put("/api/master/items/" + id).with(csrf()).contentType(MediaType.APPLICATION_JSON).with(admin)
                .content("{\"itemCd\":\"" + cd + "\",\"itemNm\":\"" + cd + "\",\"itemType\":\"SUB\",\"procType\":\"BUY\",\"unitCd\":\"KG\","
                        + "\"lotCtrlYn\":\"Y\",\"useYn\":\"Y\",\"rowVer\":" + ver + "}"));
    }

    private String sqlError(String c, String sql, Object... args) {
        Throwable t = catchThrowable(() -> accounts.exec(c, sql, args));
        return t == null ? "OK" : PgErrors.sqlState(t).orElse("?") + " " + PgErrors.constraint(t).orElse("-");
    }

    private String unitOf(long id) {
        return accounts.query(comp, "select unit_cd from mst_item where item_id = ?", String.class, id);
    }

    @Test
    void 자재_LOT_만_있는_품목도_단위_변경_막힘_LOT_없는_품목은_됨() throws Exception {
        long used = item(comp, "RM-LOT");
        accounts.exec(comp, LOT_SQL, comp, "L-RM-1", used);
        changeUnit(used, "RM-LOT").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("unitCd")).andExpect(jsonPath("$.errors[0].code").value("IN_USE"));
        assertThat(sqlError(comp, "update mst_item set unit_cd = 'KG' where item_id = ?", used)).isEqualTo("23503 fk_mst_item_unit_in_use");
        assertThat(unitOf(used)).isEqualTo("EA");
        // LOT 이 없는 품목은 그대로 바꿀 수 있고, 이름만 바꾸는 저장은 쓰인 품목도 된다
        long free = item(comp, "RM-FREE");
        changeUnit(free, "RM-FREE").andExpect(status().isOk());
        assertThat(unitOf(free)).isEqualTo("KG");
        assertThat(sqlError(comp, "update mst_item set item_nm = '새 이름' where item_id = ?", used)).isEqualTo("OK");
    }

    @Test
    void B사_LOT_은_A사_품목_단위를_막지_않는다() throws Exception {
        String other = accounts.newComp("LV");
        long theirs = item(other, "RM-B");
        accounts.exec(other, LOT_SQL, other, "L-B-1", theirs);
        long mine = item(comp, "RM-B");                   // 같은 품목 코드, 다른 회사
        changeUnit(mine, "RM-B").andExpect(status().isOk());
        assertThat(unitOf(mine)).isEqualTo("KG");
        // B사 품목은 그대로 막힌다
        assertThat(sqlError(other, "update mst_item set unit_cd = 'KG' where item_id = ?", theirs)).isEqualTo("23503 fk_mst_item_unit_in_use");
    }

    @Test
    void C_LOT_등록이_먼저면_커밋을_기다린_단위_변경이_LOT_을_보고_막힌다() throws Exception {
        long id = item(comp, "RACE-L");
        CountDownLatch inserted = new CountDownLatch(1);
        CompletableFuture<Void> lot = CompletableFuture.runAsync(() -> runner.run(comp, () -> {
            jdbc.update(LOT_SQL, comp, "L-RACE-1", id);
            inserted.countDown();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
        // LOT 이 커밋 전(품목 행 FOR SHARE) — 단위 변경은 기다렸다가 그 LOT 을 보고 막힌다. FOR SHARE 가 없으면 둘 다 통과해 EA LOT·KG 품목
        assertThat(sqlError(comp, "update mst_item set unit_cd = 'KG' where item_id = ?", id)).isEqualTo("23503 fk_mst_item_unit_in_use");
        lot.get(10, TimeUnit.SECONDS);
        assertThat(unitOf(id)).isEqualTo("EA");
        assertThat(accounts.query(comp, "select unit_cd from lot_master where lot_no = 'L-RACE-1'", String.class)).isEqualTo("EA");
    }

    @Test
    void C_단위_변경이_먼저면_커밋을_기다린_LOT_등록이_새_단위를_보고_막힌다() throws Exception {
        long id = item(comp, "RACE-U");
        CountDownLatch updated = new CountDownLatch(1);
        CompletableFuture<Void> change = CompletableFuture.runAsync(() -> runner.run(comp, () -> {
            jdbc.update("update mst_item set unit_cd = 'KG' where item_id = ?", id);
            updated.countDown();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(updated.await(10, TimeUnit.SECONDS)).isTrue();
        // 단위 변경이 커밋 전 — 옛 단위(EA) LOT 등록은 기다렸다가 새 단위(KG)를 보고 막힌다
        assertThat(sqlError(comp, LOT_SQL, comp, "L-RACE-2", id)).isEqualTo("23503 fk_lot_master_item_comp");
        change.get(10, TimeUnit.SECONDS);
        assertThat(unitOf(id)).isEqualTo("KG");
        assertThat(accounts.query(comp, "select count(*) from lot_master where item_id = ?", Integer.class, id)).isZero();
    }
}
