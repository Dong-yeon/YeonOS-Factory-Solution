package io.smarthappy.module.wms.count;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 재고 기간 마감 (P4 W5, docs/30 4.5·D10) — 마감 월 소급 입력 55000 → 409, 역분개도 막힘, 재개(사유)·감사,
 * **월 경계**(공장 시간대 23:59 / 00:00), 이번 달 마감 거부, **마감과 원장 동시**(잠금으로 줄 세움), A사로 B사, 권한.
 */
@AutoConfigureMockMvc
class PeriodCloseTest extends PostgresTestBase {

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
    long item;
    String lastMonth;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PC");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "WMS", "wms-period", "SEARCH", "APPROVE");
        accounts.grant(comp, u, "WMS", "wms-move", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-trx", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long wh = accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', 'WH', 'WH', 'WH', 't', 't') returning node_id""", Long.class, comp);
        a1 = accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, 'LOC', 'A-01', 'A-01', 't', 't') returning node_id""", Long.class, comp, wh);
        item = accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'SUB-1', 'SUB', 'SUB', 'BUY', 'EA', 'N', 't', 't') returning item_id""", Long.class, comp);
        lastMonth = accounts.query(comp, "select to_char(date_trunc('month', now() at time zone 'Asia/Seoul') - interval '1 day', 'YYYYMM')",
                String.class);
    }

    ResultActions postJson(String url, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    /** 공장 현지 시각 글자: 이번 달 1일 00:00 에서 분만큼 */
    String localAt(int minutesFromMonthStart) {
        return accounts.query(comp, "select to_char(date_trunc('month', now() at time zone 'Asia/Seoul') + make_interval(mins => ?), "
                + "'YYYY-MM-DD\"T\"HH24:MI')", String.class, minutesFromMonthStart);
    }

    ResultActions adjust(String qty, String occurredAt) throws Exception {
        return postJson("/api/wms/move/adjust", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + item
                + ",\"stockStatus\":\"AVAILABLE\",\"delta\":" + qty + ",\"reasonCd\":\"FOUND\",\"occurredAt\":\"" + occurredAt + "\"}", admin);
    }

    ResultActions close(String period) throws Exception {
        return postJson("/api/wms/periods/close", "{\"plantCd\":\"P1\",\"period\":\"" + period + "\"}", admin);
    }

    int ver(String period) {
        return accounts.query(comp, "select row_ver from sys_period_close where period = ?", Integer.class, period);
    }

    @Test
    void 마감_월_소급_입력_역분개_막힘_재개하면_됨_감사() throws Exception {
        MvcResult m = adjust("10", localAt(-60)).andExpect(status().isOk()).andReturn();     // 지난달 말 23:00
        long trx = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.trxIds[0]")).longValue();
        close(lastMonth).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CLOSED"));
        adjust("1", localAt(-60)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));
        postJson("/api/wms/trx/" + trx + "/reverse", "{\"reason\":\"마감 뒤 되돌림\"}", admin)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));
        adjust("1", localAt(0)).andExpect(status().isOk());                                 // 이번 달은 열려 있다
        // 재개: 사유 없음 → 400, 낡은 버전 → 409, 사유 → REOPENED
        postJson("/api/wms/periods/reopen", "{\"plantCd\":\"P1\",\"period\":\"" + lastMonth + "\",\"rowVer\":" + ver(lastMonth) + "}", admin)
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        postJson("/api/wms/periods/reopen", "{\"plantCd\":\"P1\",\"period\":\"" + lastMonth + "\",\"rowVer\":99,\"reason\":\"누락 입고\"}", admin)
                .andExpect(status().isConflict());
        postJson("/api/wms/periods/reopen", "{\"plantCd\":\"P1\",\"period\":\"" + lastMonth + "\",\"rowVer\":" + ver(lastMonth)
                + ",\"reason\":\"누락 입고\"}", admin).andExpect(jsonPath("$.data.status").value("REOPENED"))
                .andExpect(jsonPath("$.data.reason").value("누락 입고"));
        adjust("1", localAt(-60)).andExpect(status().isOk());
        // 다시 마감(재개 행 → CLOSED)
        postJson("/api/wms/periods/close", "{\"plantCd\":\"P1\",\"period\":\"" + lastMonth + "\",\"rowVer\":" + ver(lastMonth) + "}", admin)
                .andExpect(jsonPath("$.data.status").value("CLOSED"));
        close(lastMonth).andExpect(status().isConflict());                                   // 이미 마감
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'sys_period_close'", Integer.class))
                .isGreaterThanOrEqualTo(3);
        mvc.perform(get("/api/wms/periods").param("plantCd", "P1").param("year", lastMonth.substring(0, 4)).with(admin))
                .andExpect(jsonPath("$.total").value(12))
                .andExpect(jsonPath("$.list[" + (Integer.parseInt(lastMonth.substring(4)) - 1) + "].status").value("CLOSED"));
    }

    @Test
    void 월_경계는_공장_시간대_23시59분_막힘_0시0분_열림() throws Exception {
        close(lastMonth).andExpect(status().isOk());
        adjust("1", localAt(-1)).andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));      // 지난달 마지막 분
        adjust("1", localAt(0)).andExpect(status().isOk());                                  // 이번 달 첫 분
        // 직접 SQL 도 같은 경계(UTC 로 넣어도 공장 시간대로 판정)
        String ins = """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', 1, 'EA', 'MANUAL',
                        (date_trunc('month', now() at time zone 'Asia/Seoul') + make_interval(secs => ?)) at time zone 'Asia/Seoul', 'KEY', 't')""";
        Throwable t = catchThrowable(() -> accounts.exec(comp, ins, comp, item, a1, -0.001));
        assertThat(PgErrors.constraint(t).orElse("")).isEqualTo("ck_inv_trx_period_closed");
        assertThat(accounts.exec(comp, ins, comp, item, a1, 0)).isEqualTo(1);
        // 공장 시간대가 다르면 그 시간대 월: P1 을 UTC 로 바꾸면 서울 기준 이번 달 1일 08:59 는 UTC 로 지난달 말
        accounts.exec(comp, "update sys_plant set time_zone = 'UTC' where plant_cd = 'P1'");
        Throwable t2 = catchThrowable(() -> accounts.exec(comp, ins, comp, item, a1, 8 * 3600 + 59 * 60));
        assertThat(PgErrors.constraint(t2).orElse("")).isEqualTo("ck_inv_trx_period_closed");
    }

    @Test
    void 이번_달_미래_형식_점검() throws Exception {
        String thisMonth = accounts.query(comp, "select to_char(now() at time zone 'Asia/Seoul', 'YYYYMM')", String.class);
        close(thisMonth).andExpect(jsonPath("$.errors[0].code").value("NOT_PAST_MONTH"));
        close("209912").andExpect(jsonPath("$.errors[0].code").value("NOT_PAST_MONTH"));
        close("202613").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        close("").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        postJson("/api/wms/periods/reopen", "{\"plantCd\":\"P1\",\"period\":\"" + lastMonth + "\",\"rowVer\":0,\"reason\":\"없는 마감\"}", admin)
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/wms/periods/check").param("plantCd", "P1").param("period", thisMonth).with(admin))
                .andExpect(jsonPath("$.data.future").value(true)).andExpect(jsonPath("$.data.openCounts").value(0));
    }

    @Test
    void C_마감과_원장이_동시면_줄_세운다() throws Exception {
        // ① 원장이 먼저(지난달, 커밋 전 1.5초) → 마감은 기다렸다가 성공, 원장 행은 남는다
        CountDownLatch inserted = new CountDownLatch(1);
        CompletableFuture<Void> ledger = CompletableFuture.runAsync(() -> runner.run(comp, () -> {
            jdbc.update("""
                    insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                         entry_method, recorded_by)
                    values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', 5, 'EA', 'MANUAL',
                            date_trunc('month', now() at time zone 'Asia/Seoul') at time zone 'Asia/Seoul' - interval '1 hour', 'KEY', 't')""",
                    comp, item, a1);
            inserted.countDown();
            sleep(1500);
        }));
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
        long t0 = System.nanoTime();
        close(lastMonth).andExpect(status().isOk());
        assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThan(800);                 // 원장 커밋을 기다렸다
        ledger.get(10, TimeUnit.SECONDS);
        assertThat(accounts.query(comp, "select count(*) from inv_trx", Integer.class)).isEqualTo(1);

        // ② 마감(재개 → 다시 마감)이 먼저 잠금을 쥔 채 1.5초 → 그 사이 들어온 지난달 원장은 기다렸다가 PERIOD_CLOSED
        postJson("/api/wms/periods/reopen", "{\"plantCd\":\"P1\",\"period\":\"" + lastMonth + "\",\"rowVer\":" + ver(lastMonth)
                + ",\"reason\":\"시험 재개\"}", admin).andExpect(status().isOk());
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> closer = CompletableFuture.runAsync(() -> runner.run(comp, () -> {
            jdbc.queryForObject("select pg_advisory_xact_lock(hb_period_lock_key(?, 'P1', 'INV', ?))::text", String.class, comp, lastMonth);
            jdbc.update("update sys_period_close set status = 'CLOSED', closed_at = now(), closed_by = 't' where period = ?", lastMonth);
            locked.countDown();
            sleep(1500);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        adjust("1", localAt(-60)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERIOD_CLOSED"));
        closer.get(10, TimeUnit.SECONDS);
        assertThat(accounts.query(comp, "select count(*) from inv_trx", Integer.class)).isEqualTo(1);
    }

    @Test
    void A사로_B사_마감은_서로_영향_없음() throws Exception {
        String other = accounts.newComp("PB");
        accounts.addPlant(other, "P1", "B사");
        long bu = accounts.newUser(other, "badmin", PW);
        accounts.grant(other, bu, "WMS", "wms-period", "SEARCH", "APPROVE");
        RequestPostProcessor b = accounts.as(other, bu, "badmin");
        postJson("/api/wms/periods/close", "{\"plantCd\":\"P1\",\"period\":\"" + lastMonth + "\"}", b).andExpect(status().isOk());
        adjust("1", localAt(-60)).andExpect(status().isOk());                               // A사 지난달은 열려 있다
        close(lastMonth).andExpect(status().isOk());
        // B사가 A사 마감을 재개하려 함 → B사 자기 행만 보인다(버전 다름 → 409 또는 자기 것 재개). A사 행은 그대로
        int aVer = ver(lastMonth);
        assertThat(accounts.exec(other, "update sys_period_close set status = 'REOPENED' where comp_cd = ?", comp)).isZero();
        assertThat(accounts.query(comp, "select status || row_ver from sys_period_close", String.class)).isEqualTo("CLOSED" + aVer);
        // 직접 SQL: B사 범위에서 A사 회사 코드로 마감 행
        Throwable t = catchThrowable(() -> accounts.exec(other, """
                insert into sys_period_close (comp_cd, plant_cd, module_cd, period, status, closed_at, closed_by, created_by, updated_by)
                values (?, 'P1', 'INV', '200001', 'CLOSED', now(), 't', 't', 't')""", comp));
        assertThat(PgErrors.sqlState(t).orElse("")).isIn("42501", "23503");
    }

    @Test
    void 권한() throws Exception {
        long v = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, v, "VIEW", "wms-period", "SEARCH");
        RequestPostProcessor vw = accounts.as(comp, v, "viewer");
        postJson("/api/wms/periods/close", "{\"plantCd\":\"P1\",\"period\":\"" + lastMonth + "\"}", vw).andExpect(status().isForbidden());
        mvc.perform(get("/api/wms/periods").param("plantCd", "P1").with(vw)).andExpect(status().isOk());
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
