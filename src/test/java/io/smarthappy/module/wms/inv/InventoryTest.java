package io.smarthappy.module.wms.inv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
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
 * 재고 원장 엔진 (P4 W3, docs/30 4.3) — 원장 → 현재고 투영, 마이너스 거부(동시 출고 포함), 이동 짝, 역분개, 멱등, 대사.
 * 정상 + 이상 사용 A~K + A사로 B사 + 직접 SQL(트리거·권한·지연 제약).
 */
@AutoConfigureMockMvc
class InventoryTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    InventoryJobs jobs;

    String comp;
    long userId;
    RequestPostProcessor admin;
    long wh;
    long a1;
    long a2;
    long sub;
    long lotItem;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("IV");
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "WMS", "wms-move", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "WMS", "wms-stock", "SEARCH", "EXCEL", "FN1");
        accounts.grant(comp, userId, "WMS", "wms-trx", "SEARCH", "SAVE", "EXCEL");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.addPlant(comp, "P2", "2공장");
        wh = node(comp, "P1", null, "WH", "SUB-WH");
        a1 = node(comp, "P1", wh, "LOC", "A-01");
        a2 = node(comp, "P1", wh, "LOC", "A-02");
        sub = item(comp, "SUB-1", "N");
        lotItem = item(comp, "RM-LOT", "Y");
    }

    long node(String c, String plant, Long parent, String type, String cd) {
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, c, plant, parent, type, cd, cd);
    }

    long item(String c, String cd, String lotYn) {
        return accounts.query(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, ?, ?, 'SUB', 'BUY', 'EA', ?, 't', 't') returning item_id""", Long.class, c, cd, cd + " 이름", lotYn);
    }

    private ResultActions postJson(String url, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    private ResultActions adjust(long node, long item, String delta, String extra) throws Exception {
        return postJson("/api/wms/move/adjust", "{\"plantCd\":\"P1\",\"nodeId\":" + node + ",\"itemId\":" + item
                + ",\"stockStatus\":\"AVAILABLE\",\"delta\":" + delta + ",\"reasonCd\":\"FOUND\"" + extra + "}", admin);
    }

    private ResultActions move(long from, String fs, long to, String ts, String qty) throws Exception {
        return postJson("/api/wms/move/move", "{\"plantCd\":\"P1\",\"itemId\":" + sub + ",\"fromNodeId\":" + from + ",\"fromStatus\":\"" + fs
                + "\",\"toNodeId\":" + to + ",\"toStatus\":\"" + ts + "\",\"qty\":" + qty + "}", admin);
    }

    private BigDecimal qty(long node, String status) {
        BigDecimal v = accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and item_id = ? and stock_status = ?",
                BigDecimal.class, node, sub, status);
        return v.stripTrailingZeros();
    }

    private int trxCount() {
        return accounts.query(comp, "select count(*) from inv_trx", Integer.class);
    }

    private long firstId(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.trxIds[0]")).longValue();
    }

    private String sqlError(String c, String sql, Object... args) {
        Throwable t = catchThrowable(() -> accounts.exec(c, sql, args));
        return t == null ? "OK" : PgErrors.sqlState(t).orElse("?") + " " + PgErrors.constraint(t).orElse("-");
    }

    private String today() {
        return accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void 조정_이동_상태변경이_현재고에_그대로() throws Exception {
        adjust(a1, sub, "100", "").andExpect(status().isOk()).andExpect(jsonPath("$.data.replay").value(false));
        move(a1, "AVAILABLE", a2, "AVAILABLE", "30").andExpect(status().isOk()).andExpect(jsonPath("$.data.trxIds.length()").value(2));
        move(a2, "AVAILABLE", a2, "QC", "10").andExpect(status().isOk());                       // 같은 위치 = 상태 변경
        adjust(a1, sub, "-5", "").andExpect(status().isOk());
        assertThat(qty(a1, "AVAILABLE")).isEqualByComparingTo("65");
        assertThat(qty(a2, "AVAILABLE")).isEqualByComparingTo("20");
        assertThat(qty(a2, "QC")).isEqualByComparingTo("10");
        assertThat(accounts.query(comp, "select string_agg(trx_type, ',' order by trx_id) from inv_trx", String.class))
                .isEqualTo("ADJ_IN,MOVE_OUT,MOVE_IN,STATUS_OUT,STATUS_IN,ADJ_OUT");
        // 원장 합 = 현재고
        assertThat(accounts.query(comp, "select sum(qty) from inv_trx", BigDecimal.class)).isEqualByComparingTo("95");
        mvc.perform(get("/api/wms/stock").param("plantCd", "P1").param("nodeId", String.valueOf(wh)).with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.list[*].path").value(containsInAnyOrder("P1/SUB-WH/A-01", "P1/SUB-WH/A-02", "P1/SUB-WH/A-02")));
        mvc.perform(get("/api/wms/trx").param("plantCd", "P1").param("from", today()).param("to", today()).with(admin))
                .andExpect(jsonPath("$.total").value(6));
        long stockId = accounts.query(comp, "select stock_id from inv_stock where node_id = ? and stock_status = 'AVAILABLE'", Long.class, a1);
        mvc.perform(get("/api/wms/stock/" + stockId + "/history").with(admin)).andExpect(jsonPath("$.data.length()").value(3));
        mvc.perform(get("/api/wms/stock/columns").with(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/wms/trx/columns").with(admin)).andExpect(status().isOk());
        mvc.perform(post("/api/wms/stock/export").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantCd\":\"P1\",\"withChildren\":\"false\",\"sort\":[\"qty,desc\"]}").with(admin)).andExpect(status().isOk());
        mvc.perform(post("/api/wms/trx/export").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantCd\":\"P1\",\"from\":\"" + today() + "\",\"to\":\"" + today() + "\",\"columns\":[\"trxId\",\"qty\"]}").with(admin))
                .andExpect(status().isOk());
    }

    // =====================================================================
    // 이상 사용
    // =====================================================================

    @Test
    void D_마이너스_재고는_409_아무것도_안_들어감() throws Exception {
        adjust(a1, sub, "10", "").andExpect(status().isOk());
        adjust(a1, sub, "-11", "").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        move(a1, "AVAILABLE", a2, "AVAILABLE", "10.000001").andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        adjust(a2, sub, "-1", "").andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));          // 재고 없는 위치에서 출고
        assertThat(trxCount()).isEqualTo(1);
        assertThat(qty(a1, "AVAILABLE")).isEqualByComparingTo("10");
        assertThat(qty(a2, "AVAILABLE")).isEqualByComparingTo("0");
    }

    @Test
    void C_동시_출고_둘중_하나만_통과() throws Exception {
        adjust(a1, sub, "10", "").andExpect(status().isOk());
        CountDownLatch inserted = new CountDownLatch(1);
        // 앞 트랜잭션: 7 출고를 넣고 1.5초 쥔다(현재고 행 잠금)
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> runner.run(comp, () -> {
            jdbc.update("""
                    insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type,
                                         occurred_at, entry_method, recorded_by)
                    values (?, 'P1', 'ADJ_OUT', ?, ?, 'AVAILABLE', -7, 'EA', 'MANUAL', now(), 'KEY', 't')""", comp, sub, a1);
            inserted.countDown();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
        MvcResult second = adjust(a1, sub, "-7", "").andReturn();      // 잠금을 기다렸다가 CHECK 위반
        first.get(10, TimeUnit.SECONDS);
        assertThat(second.getResponse().getStatus()).as(second.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(qty(a1, "AVAILABLE")).isEqualByComparingTo("3");
    }

    @Test
    void 역분개_이동은_짝_두행_다시는_안됨_소비된_뒤엔_409() throws Exception {
        long adj = firstId(adjust(a1, sub, "100", ""));
        long mv = firstId(move(a1, "AVAILABLE", a2, "AVAILABLE", "30"));
        postJson("/api/wms/trx/" + mv + "/reverse", "{\"reason\":\" \"}", admin).andExpect(jsonPath("$.errors[0].field").value("reason"));
        postJson("/api/wms/trx/" + (mv + 1) + "/reverse", "{\"reason\":\"위치 잘못 찍음\"}", admin)       // 짝의 IN 행을 골라도 둘 다
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        assertThat(qty(a1, "AVAILABLE")).isEqualByComparingTo("100");
        assertThat(qty(a2, "AVAILABLE")).isEqualByComparingTo("0");
        postJson("/api/wms/trx/" + mv + "/reverse", "{\"reason\":\"또 되돌림\"}", admin).andExpect(jsonPath("$.errors[0].code").value("ALREADY_REVERSED"));
        long rev = accounts.query(comp, "select max(trx_id) from inv_trx", Long.class);
        postJson("/api/wms/trx/" + rev + "/reverse", "{\"reason\":\"되돌림의 되돌림\"}", admin)
                .andExpect(jsonPath("$.errors[0].code").value("REVERSAL_OF_REVERSAL"));
        // 옮긴 30 을 도착지에서 써 버린 뒤 이동을 되돌리면 도착지가 마이너스 → 409
        long mv2 = firstId(move(a1, "AVAILABLE", a2, "AVAILABLE", "30"));
        adjust(a2, sub, "-30", "").andExpect(status().isOk());
        postJson("/api/wms/trx/" + mv2 + "/reverse", "{\"reason\":\"취소\"}", admin).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        // 조정 역분개는 원래대로
        postJson("/api/wms/trx/" + adj + "/reverse", "{\"reason\":\"수량 오타\"}", admin).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        mvc.perform(get("/api/wms/trx/" + mv).with(admin)).andExpect(jsonPath("$.data.reversedById").isNumber());
        assertThat(accounts.query(comp, "select count(*) from inv_trx where entry_method = 'CORRECT'", Integer.class)).isEqualTo(2);
    }

    @Test
    void 같은_요청을_다시_보내면_처음_결과_그대로() throws Exception {
        String req = ",\"clientReqId\":\"req-0001-abcd\"";
        long first = firstId(adjust(a1, sub, "5", req));
        adjust(a1, sub, "5", req).andExpect(status().isOk()).andExpect(jsonPath("$.data.replay").value(true))
                .andExpect(jsonPath("$.data.trxIds[0]").value(first));
        assertThat(qty(a1, "AVAILABLE")).isEqualByComparingTo("5");
        adjust(a1, sub, "5", ",\"clientReqId\":\"x y\"").andExpect(jsonPath("$.errors[0].field").value("clientReqId"));
        adjust(a1, sub, "5", ",\"clientReqId\":\"short\"").andExpect(jsonPath("$.errors[0].field").value("clientReqId"));
    }

    @Test
    void B_F_입력값과_흐름() throws Exception {
        adjust(a1, sub, "0", "").andExpect(jsonPath("$.errors[0].code").value("NOT_ZERO"));
        adjust(a1, sub, "1.1234567", "").andExpect(jsonPath("$.errors[0].code").value("SCALE"));
        adjust(a1, sub, "1000000000000", "").andExpect(jsonPath("$.errors[0].code").value("INT_DIGITS"));
        adjust(a1, lotItem, "5", "").andExpect(jsonPath("$.errors[0].code").value("LOT_REQUIRED"));
        postJson("/api/wms/move/adjust", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + sub
                + ",\"stockStatus\":\"GOOD\",\"delta\":1,\"reasonCd\":\"NOPE\",\"occurredAt\":\"2099-01-01T00:00\",\"entryMethod\":\"AUTO\"}", admin)
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("stockStatus", "reasonCd", "occurredAt", "entryMethod")));
        postJson("/api/wms/move/adjust", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + sub
                + ",\"stockStatus\":\"AVAILABLE\",\"delta\":1,\"occurredAt\":\"어제\"}", admin)
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("REQUIRED", "FORMAT")));
        // 위치: 다른 공장·구역·사용 중지·재고 안 둠
        long p2 = node(comp, "P2", null, "WH", "P2-WH");
        long area = node(comp, "P1", null, "AREA", "BODY");
        adjust(p2, sub, "1", "").andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        adjust(area, sub, "1", "").andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, stock_yn, created_by, updated_by) values (?, ?, 'N', 't', 't')", wh, comp);
        adjust(wh, sub, "1", "").andExpect(jsonPath("$.errors[0].code").value("STOCK_NOT_ALLOWED"));
        move(a1, "AVAILABLE", a1, "AVAILABLE", "1").andExpect(jsonPath("$.errors[0].code").value("SAME_PLACE"));
        move(a1, "AVAILABLE", a2, "AVAILABLE", "-1").andExpect(jsonPath("$.errors[0].code").value("POSITIVE"));
        // 품목 혼적 금지 위치
        long other = item(comp, "SUB-2", "N");
        adjust(a2, other, "1", "").andExpect(status().isOk());
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, mix_item_yn, created_by, updated_by) values (?, ?, 'N', 't', 't')", a2, comp);
        adjust(a2, sub, "1", "").andExpect(jsonPath("$.errors[0].code").value("MIX_ITEM"));
        adjust(a2, other, "1", "").andExpect(status().isOk());                                         // 같은 품목은 된다
        accounts.exec(comp, "update mst_item set use_yn = 'N' where item_id = ?", other);
        adjust(a2, other, "1", "").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        accounts.exec(comp, "update mst_equip_hier set use_yn = 'N' where node_id = ?", a1);
        adjust(a1, sub, "1", "").andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        assertThat(trxCount()).isEqualTo(2);
    }

    @Test
    void 직접_SQL_원장_수정삭제_현재고_직접변경_짝없는_이동_엉터리_역분개() throws Exception {
        long adj = firstId(adjust(a1, sub, "10", ""));
        assertThat(sqlError(comp, "update inv_trx set qty = 99 where trx_id = ?", adj)).startsWith("42501");
        assertThat(sqlError(comp, "delete from inv_trx where trx_id = ?", adj)).startsWith("42501");
        assertThat(sqlError(comp, "update inv_stock set qty = 999")).isEqualTo("55000 ck_inv_stock_projection");
        assertThat(sqlError(comp, "insert into inv_stock (comp_cd, plant_cd, item_id, node_id, stock_status, qty, last_trx_id) "
                + "values (?, 'P1', ?, ?, 'QC', 5, 1)", comp, sub, a2)).isEqualTo("55000 ck_inv_stock_projection");
        assertThat(sqlError(comp, "delete from inv_stock")).startsWith("42501");
        String ins = """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, move_grp_id, src_type,
                                     occurred_at, entry_method, recorded_by, reversal_of_id, remark)
                values (?, 'P1', ?, ?, ?, 'AVAILABLE', ?, 'EA', ?::uuid, 'MANUAL', now(), ?, 't', ?, ?)""";
        // 짝 없는 이동 출고 — 커밋 때 지연 제약
        assertThat(sqlError(comp, ins, comp, "MOVE_OUT", sub, a1, -3, "00000000-0000-0000-0000-000000000001", "KEY", null, null))
                .isEqualTo("23514 ck_inv_trx_move_pair");
        // 부호가 틀린 행, 거울이 아닌 역분개, LOT 품목, 다른 단위
        assertThat(sqlError(comp, ins, comp, "ADJ_IN", sub, a1, -3, null, "KEY", null, null)).isEqualTo("23514 ck_inv_trx_sign");
        assertThat(sqlError(comp, ins, comp, "ADJ_IN", sub, a1, -5, null, "CORRECT", adj, "반만")).isEqualTo("23514 ck_inv_trx_reversal_mirror");
        assertThat(sqlError(comp, ins, comp, "ADJ_IN", sub, a1, -10, null, "KEY", adj, null)).isEqualTo("23514 ck_inv_trx_correct");
        assertThat(sqlError(comp, ins, comp, "ADJ_IN", lotItem, a1, 3, null, "KEY", null, null)).isEqualTo("23514 ck_inv_trx_lot_item");
        assertThat(sqlError(comp, ins.replace("'EA'", "'KG'"), comp, "ADJ_IN", sub, a1, 3, null, "KEY", null, null))
                .isEqualTo("23514 ck_inv_trx_unit");
        assertThat(qty(a1, "AVAILABLE")).isEqualByComparingTo("10");
        assertThat(trxCount()).isEqualTo(1);
    }

    @Test
    void 대사_불일치는_알림만_자동보정_없음() throws Exception {
        adjust(a1, sub, "10", "").andExpect(status().isOk());
        postJson("/api/wms/stock/reconcile", "{}", admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keyCount").value(1)).andExpect(jsonPath("$.data.mismatchCount").value(0));
        // 소유자(슈퍼유저)가 트리거를 우회해 현재고를 망가뜨린다 — 운영 사고 흉내
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = c.createStatement()) {
            st.execute("set session_replication_role = replica");
            st.executeUpdate("update inv_stock set qty = 13 where comp_cd = '" + comp + "'");
        }
        int outboxBefore = accounts.query(comp, "select count(*) from sys_outbox where topic = 'NOTIFY'", Integer.class);
        assertThat(jobs.reconcile().failed()).isEmpty();
        assertThat(accounts.query(comp, "select mismatch_count from inv_reconcile_run order by run_id desc limit 1", Integer.class)).isEqualTo(1);
        assertThat(accounts.query(comp, "select count(*) from sys_outbox where topic = 'NOTIFY'", Integer.class)).isEqualTo(outboxBefore + 1);
        assertThat(qty(a1, "AVAILABLE")).isEqualByComparingTo("13");                                    // 고치지 않는다
        mvc.perform(get("/api/wms/stock/reconcile").with(admin)).andExpect(jsonPath("$.data.detail")
                .value(org.hamcrest.Matchers.containsString("\"stockQty\": 13")));
    }

    @Test
    void 이력_조회_기간() throws Exception {
        mvc.perform(get("/api/wms/trx").param("plantCd", "P1").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/wms/trx").param("plantCd", "P1").param("from", "2026-01-01").param("to", "2026-06-30").with(admin))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/wms/trx").param("plantCd", "P1").param("from", today()).param("to", today()).param("trxType", "RETURN_CUST")
                .with(admin)).andExpect(jsonPath("$.errors[0].field").value("trxType"));
        mvc.perform(get("/api/wms/stock").with(admin)).andExpect(jsonPath("$.errors[0].field").value("plantCd"));
        mvc.perform(get("/api/wms/stock").param("plantCd", "P1").param("itemCd", "%_").with(admin)).andExpect(jsonPath("$.total").value(0));
    }

    /**
     * 수불 원장 쪽 나눔(지연 조인, docs/30 5.1) — 같은 발생 시각 7행을 3행씩: 쪽 경계에서 빠지거나 겹치지 않고(끝 키 trx_id desc),
     * 품번 조건(안쪽에서만 품목 조인)·건수도 같다. B사는 같은 공장 코드·같은 날로 조회해도 0행(안쪽 inv_trx 도 RLS)
     */
    @Test
    void 수불_원장_쪽_나눔은_같은_시각도_빠짐_겹침_없고_B사는_0행() throws Exception {
        accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                select ?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', g, 'EA', 'MANUAL', now(), 'KEY', 't'
                  from generate_series(1, 7) g""", comp, sub, a1);
        List<Long> all = accounts.query(comp, "select string_agg(trx_id::text, ',' order by trx_id desc) from inv_trx", String.class)
                .lines().flatMap(x -> java.util.Arrays.stream(x.split(","))).map(Long::valueOf).toList();
        List<Long> seen = new java.util.ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            String body = mvc.perform(get("/api/wms/trx").param("plantCd", "P1").param("from", today()).param("to", today())
                    .param("page", String.valueOf(page)).param("size", "3").param("total", "true").with(admin))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(7)).andReturn().getResponse().getContentAsString();
            List<Number> ids = com.jayway.jsonpath.JsonPath.read(body, "$.list[*].trxId");
            ids.forEach(n -> seen.add(n.longValue()));
        }
        assertThat(seen).as("3쪽을 이으면 trx_id 내림차순 전부, 겹침 없음").containsExactlyElementsOf(all);
        // 품번 조건(안쪽에서만 품목 조인)도 같은 행·건수
        mvc.perform(get("/api/wms/trx").param("plantCd", "P1").param("from", today()).param("to", today()).param("itemCd", "SUB")
                .param("page", "3").param("size", "3").param("total", "true").with(admin))
                .andExpect(jsonPath("$.total").value(7)).andExpect(jsonPath("$.list.length()").value(1))
                .andExpect(jsonPath("$.list[0].trxId").value(all.get(6)));
        // B사 — 같은 공장 코드·같은 날
        String other = accounts.newComp("IP");
        accounts.addPlant(other, "P1", "B사");
        long bUser = accounts.newUser(other, "badmin", PW);
        accounts.grant(other, bUser, "WMS", "wms-trx", "SEARCH");
        mvc.perform(get("/api/wms/trx").param("plantCd", "P1").param("from", today()).param("to", today()).param("total", "true")
                .with(accounts.as(other, bUser, "badmin"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.list.length()").value(0));
    }

    @Test
    void A사로_B사_위치_품목_원장() throws Exception {
        long mine = firstId(adjust(a1, sub, "10", ""));
        String other = accounts.newComp("IB");
        accounts.addPlant(other, "P1", "B사");
        long bWh = node(other, "P1", null, "WH", "B-WH");
        long bItem = item(other, "B-ITEM", "N");
        long bUser = accounts.newUser(other, "badmin", PW);
        accounts.grant(other, bUser, "WMS", "wms-move", "SEARCH", "SAVE");
        accounts.grant(other, bUser, "WMS", "wms-trx", "SEARCH", "SAVE");
        accounts.grant(other, bUser, "WMS", "wms-stock", "SEARCH");
        RequestPostProcessor b = accounts.as(other, bUser, "badmin");
        // A사가 B사 위치·품목으로
        adjust(bWh, sub, "1", "").andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        adjust(a1, bItem, "1", "").andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        // B사가 A사 원장 보기·되돌리기, A사 재고 보기
        mvc.perform(get("/api/wms/trx/" + mine).with(b)).andExpect(status().isNotFound());
        mvc.perform(post("/api/wms/trx/" + mine + "/reverse").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"남의 것\"}").with(b)).andExpect(status().isNotFound());
        mvc.perform(get("/api/wms/stock").param("plantCd", "P1").with(b)).andExpect(jsonPath("$.total").value(0));
        // 직접 SQL: B사 범위에서 A사 품목·위치로 원장 행
        assertThat(sqlError(other, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', 1, 'EA', 'MANUAL', now(), 'KEY', 't')""", other, sub, bWh))
                .isEqualTo("23503 fk_inv_trx_item_comp");
        assertThat(sqlError(other, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by, reversal_of_id, remark)
                values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', -10, 'EA', 'MANUAL', now(), 'CORRECT', 't', ?, '남의 행 되돌림')""",
                other, bItem, bWh, mine)).isEqualTo("23503 fk_inv_trx_reversal_comp");
        assertThat(qty(a1, "AVAILABLE")).isEqualByComparingTo("10");
    }

    @Test
    void 권한() throws Exception {
        long viewer = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewer, "VIEW", "wms-move", "SEARCH");
        accounts.grant(comp, viewer, "VIEW", "wms-trx", "SEARCH");
        accounts.grant(comp, viewer, "VIEW", "wms-stock", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer");
        long id = firstId(adjust(a1, sub, "5", ""));
        postJson("/api/wms/move/adjust", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + sub
                + ",\"stockStatus\":\"AVAILABLE\",\"delta\":1,\"reasonCd\":\"FOUND\"}", v).andExpect(status().isForbidden());
        postJson("/api/wms/move/move", "{}", v).andExpect(status().isForbidden());
        postJson("/api/wms/trx/" + id + "/reverse", "{\"reason\":\"권한 없음\"}", v).andExpect(status().isForbidden());
        postJson("/api/wms/stock/reconcile", "{}", v).andExpect(status().isForbidden());
        mvc.perform(get("/api/wms/stock").param("plantCd", "P1").with(v)).andExpect(status().isOk());
        assertThat(trxCount()).isEqualTo(1);
        assertThat(List.of(id)).hasSize(1);
    }
}
