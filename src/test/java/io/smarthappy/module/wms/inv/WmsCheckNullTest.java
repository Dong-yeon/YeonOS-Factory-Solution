package io.smarthappy.module.wms.inv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * CHECK 의 NULL 구멍(`B AND length(btrim(x)) > 0` 는 x NULL 이면 통과) — 사유 없는 역분개·재발행을 직접 SQL 로 넣으면 거부.
 * 두 제약은 마이그레이션 뒤 VALIDATE 까지 끝나 있어야 한다(빈 DB).
 */
@AutoConfigureMockMvc
class WmsCheckNullTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    long node;
    long item;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("CN");
        long u = accounts.newUser(comp, "admin1", "Maple-anchor-63");
        accounts.grant(comp, u, "WMS", "wms-move", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        node = accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', 'WH', 'WH', 'WH', 't', 't') returning node_id""", Long.class, comp);
        item = accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'RM-1', 'RM', 'RM', 'BUY', 'EA', 'N', 't', 't') returning item_id""", Long.class, comp);
    }

    String sqlError(String sql, Object... args) {
        Throwable t = catchThrowable(() -> accounts.exec(comp, sql, args));
        return t == null ? "OK" : PgErrors.constraint(t).orElse("?");
    }

    @Test
    void 두_제약은_검증까지_끝나_있다() {
        assertThat(accounts.query(comp, """
                select count(*) from pg_constraint
                 where conname in ('ck_inv_trx_correct', 'ck_wms_print_job_reprint') and convalidated""", Integer.class)).isEqualTo(2);
    }

    @Test
    void 사유_없는_역분개는_직접_SQL_로도_거부() throws Exception {
        mvc.perform(post("/api/wms/move/adjust").with(csrf()).contentType(MediaType.APPLICATION_JSON).with(admin)
                .content("{\"plantCd\":\"P1\",\"nodeId\":" + node + ",\"itemId\":" + item
                        + ",\"stockStatus\":\"AVAILABLE\",\"delta\":5,\"reasonCd\":\"FOUND\"}")).andExpect(status().isOk());
        long orig = accounts.query(comp, "select max(trx_id) from inv_trx", Long.class);
        String reversal = """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, reason_cd, remark,
                                     occurred_at, entry_method, recorded_by, reversal_of_id)
                select comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, -qty, unit_cd, src_type, reason_cd, ?,
                       occurred_at, 'CORRECT', 't', trx_id from inv_trx where trx_id = ?""";
        assertThat(sqlError(reversal, null, orig)).isEqualTo("ck_inv_trx_correct");
        assertThat(sqlError(reversal, "  ", orig)).isEqualTo("ck_inv_trx_correct");
        assertThat(sqlError(reversal, "수량 잘못", orig)).isEqualTo("OK");
    }

    @Test
    void 사유_없는_재발행은_직접_SQL_로도_거부() {
        long printer = accounts.query(comp, """
                insert into wms_printer (comp_cd, plant_cd, printer_cd, printer_nm, host, created_by, updated_by)
                values (?, 'P1', 'PR', 'PR', '10.0.0.5', 't', 't') returning printer_id""", Long.class, comp);
        long tpl = accounts.query(comp, """
                insert into wms_label_tpl (comp_cd, label_type, tpl_cd, tpl_nm, width_mm, height_mm, body, created_by, updated_by)
                values (?, 'LOC', 'T', 'T', 50, 30, '^XA^XZ', 't', 't') returning tpl_id""", Long.class, comp);
        String job = """
                insert into wms_print_job (comp_cd, plant_cd, printer_id, tpl_id, label_type, biz_key, zpl, zpl_sha256, outbox_message_id,
                                           reprint_of_id, reprint_reason, requested_by)
                values (?, 'P1', ?, ?, 'LOC', 'LOC:P1:WH', '^XA^XZ', repeat('a', 64), gen_random_uuid(), ?, ?, 't')""";
        assertThat(sqlError(job, comp, printer, tpl, null, null)).isEqualTo("OK");
        long orig = accounts.query(comp, "select max(job_id) from wms_print_job", Long.class);
        assertThat(sqlError(job, comp, printer, tpl, orig, null)).isEqualTo("ck_wms_print_job_reprint");
        assertThat(sqlError(job, comp, printer, tpl, orig, " x ")).isEqualTo("ck_wms_print_job_reprint");
        assertThat(sqlError(job, comp, printer, tpl, orig, "용지 걸림")).isEqualTo("OK");
    }
}
