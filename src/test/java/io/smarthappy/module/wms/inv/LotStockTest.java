package io.smarthappy.module.wms.inv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * LOT 관리 품목 재고 — 사무 화면 (P4 2부 B2, docs/30 12장). LOT 은 P2 lot_master(읽기만 — 시험 준비만 소유자 연결로 만든다).
 * LOT 필수·다른 품목 LOT·비관리 품목 LOT, LOT 별 현재고·수불·조회 조건, 보류 LOT 출고·이동 거부(상태 바꿈은 됨), LOT 스캔,
 * 실사 LOT 줄·장부, 차감 미리보기 LOT 순서, 보류와 출고 동시, A사로 B사.
 */
@AutoConfigureMockMvc
class LotStockTest extends PostgresTestBase {

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
    long wh;
    long a1;
    long a2;
    long lotItem;
    long plain;
    long other;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("LS");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "WMS", "wms-move", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-stock", "SEARCH");
        accounts.grant(comp, u, "WMS", "wms-trx", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-count", "SEARCH", "SAVE", "APPROVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        wh = node(null, "WH", "WH");
        a1 = node(wh, "LOC", "A-01");
        a2 = node(wh, "LOC", "A-02");
        lotItem = item("RM-L", "Y");
        plain = item("SUB-1", "N");
        other = item("RM-O", "Y");
        lot("L-NEW", lotItem, 1);
        lot("L-OLD", lotItem, 10);
        lot("O-1", other, 1);
    }

    long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd);
    }

    long item(String cd, String lotYn) {
        return accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, ?, ?, 'RM', 'BUY', 'EA', ?, 't', 't') returning item_id""", Long.class, comp, cd, cd + " 이름", lotYn);
    }

    long lot(String no, long itemId, int daysAgo) throws Exception {
        return owner("insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by) "
                + "values (?, ?, ?, 'RECEIVED', 1000, 'EA', now() - make_interval(days => ?), 't', 't') returning lot_id", comp, no, itemId, daysAgo);
    }

    static long owner(String sql, Object... args) throws Exception {
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

    void hold(String lotNo, boolean on) throws Exception {
        // 보류 근거(lot_hold, docs/25 4.19) + LOT 칸 — owner() 는 트리거를 끈 연결(replica)이라 둘 다 직접 맞춘다
        if (on) {
            owner("with h as (" + "insert into lot_hold (comp_cd, lot_id, source, reason, held_by) "
                    + "select comp_cd, lot_id, 'MANUAL', '시험 보류', 't' from lot_master where lot_no = ? and comp_cd = ? returning lot_id) "
                    + "update lot_master set hold_yn = 'Y', hold_reason = '시험 보류' where lot_id in (select lot_id from h) returning lot_id",
                    lotNo, comp);
        } else {
            owner("with h as (update lot_hold set released_at = now(), released_by = 't', release_reason = '시험 해제' "
                    + "where released_at is null and lot_id = (select lot_id from lot_master where lot_no = ? and comp_cd = ?) returning lot_id) "
                    + "update lot_master set hold_yn = 'N', hold_reason = null where lot_id in (select lot_id from h) returning lot_id",
                    lotNo, comp);
        }
    }

    ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(admin));
    }

    ResultActions adjust(long node, long item, String lotNo, String delta) throws Exception {
        return postJson("/api/wms/move/adjust", "{\"plantCd\":\"P1\",\"nodeId\":" + node + ",\"itemId\":" + item + ",\"stockStatus\":\"AVAILABLE\","
                + "\"delta\":" + delta + ",\"reasonCd\":\"FOUND\"" + (lotNo == null ? "" : ",\"lotNo\":\"" + lotNo + "\"") + "}");
    }

    ResultActions move(String lotNo, long from, String fs, long to, String ts, String qty) throws Exception {
        return postJson("/api/wms/move/move", "{\"plantCd\":\"P1\",\"itemId\":" + lotItem + ",\"fromNodeId\":" + from + ",\"fromStatus\":\"" + fs
                + "\",\"toNodeId\":" + to + ",\"toStatus\":\"" + ts + "\",\"qty\":" + qty + ",\"lotNo\":\"" + lotNo + "\"}");
    }

    BigDecimal stock(long node, String lotNo, String status) {
        return accounts.query(comp, "select coalesce(sum(s.qty), 0) from inv_stock s join lot_master l on l.lot_id = s.lot_id "
                + "where s.node_id = ? and l.lot_no = ? and s.stock_status = ?", BigDecimal.class, node, lotNo, status).stripTrailingZeros();
    }

    String sqlError(String sql, Object... args) {
        Throwable t = catchThrowable(() -> accounts.exec(comp, sql, args));
        return t == null ? "OK" : PgErrors.sqlState(t).orElse("?") + " " + PgErrors.constraint(t).orElse("-");
    }

    long lotId(String no) {
        return accounts.query(comp, "select lot_id from lot_master where lot_no = ?", Long.class, no);
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void LOT_별_조정_이동_현재고_수불_조회조건() throws Exception {
        adjust(a1, lotItem, "L-NEW", "10").andExpect(status().isOk());
        adjust(a1, lotItem, "L-OLD", "4").andExpect(status().isOk());
        move("L-NEW", a1, "AVAILABLE", a2, "AVAILABLE", "3").andExpect(status().isOk());
        assertThat(stock(a1, "L-NEW", "AVAILABLE")).isEqualByComparingTo("7");
        assertThat(stock(a2, "L-NEW", "AVAILABLE")).isEqualByComparingTo("3");
        assertThat(stock(a1, "L-OLD", "AVAILABLE")).isEqualByComparingTo("4");
        mvc.perform(get("/api/wms/stock").param("plantCd", "P1").param("lotNo", "L-N").with(admin))
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.list[*].lotNo").value(containsInAnyOrder("L-NEW", "L-NEW")));
        mvc.perform(get("/api/wms/stock").param("plantCd", "P1").param("lotNo", "%").with(admin)).andExpect(jsonPath("$.total").value(0));
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        mvc.perform(get("/api/wms/trx").param("plantCd", "P1").param("from", today).param("to", today).with(admin))
                .andExpect(jsonPath("$.list[0].lotNo").value("L-NEW"));
        long stockId = accounts.query(comp, "select stock_id from inv_stock where node_id = ? and lot_id = ?", Long.class, a1, lotId("L-NEW"));
        mvc.perform(get("/api/wms/stock/" + stockId + "/history").with(admin)).andExpect(jsonPath("$.data.length()").value(2));   // 그 LOT 만
    }

    @Test
    void LOT_스캔은_품목과_LOT_을_함께() throws Exception {
        postJson("/api/wms/move/scan", "{\"plantCd\":\"P1\",\"text\":\"LOT:L-OLD\"}").andExpect(jsonPath("$.data.type").value("LOT"))
                .andExpect(jsonPath("$.data.ok").value(true)).andExpect(jsonPath("$.data.lotNo").value("L-OLD"))
                .andExpect(jsonPath("$.data.itemCd").value("RM-L"));
        postJson("/api/wms/move/scan", "{\"plantCd\":\"P1\",\"text\":\"LOT:NOPE\"}").andExpect(jsonPath("$.data.ok").value(false));
        hold("L-OLD", true);
        postJson("/api/wms/move/scan", "{\"plantCd\":\"P1\",\"text\":\"LOT:L-OLD\"}")
                .andExpect(jsonPath("$.data.message").value(org.hamcrest.Matchers.containsString("보류")));
    }

    @Test
    void 실사는_LOT_줄_LOT_별_장부로_확정() throws Exception {
        adjust(a1, lotItem, "L-NEW", "10").andExpect(status().isOk());
        adjust(a1, lotItem, "L-OLD", "5").andExpect(status().isOk());
        MvcResult m = postJson("/api/wms/counts", "{\"plantCd\":\"P1\",\"countType\":\"CYCLE\",\"scopeNodeId\":" + wh + ",\"blindYn\":\"N\"}")
                .andExpect(status().isOk()).andReturn();
        long id = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.head.countId")).longValue();
        postJson("/api/wms/counts/" + id + "/start", "{\"rowVer\":0}").andExpect(jsonPath("$.data.lines.length()").value(2))
                .andExpect(jsonPath("$.data.lines[*].lotNo").value(containsInAnyOrder("L-NEW", "L-OLD")));
        long lNew = accounts.query(comp, "select line_id from wms_count_line l join lot_master m on m.lot_id = l.lot_id where m.lot_no = 'L-NEW'",
                Long.class);
        long lOld = accounts.query(comp, "select line_id from wms_count_line l join lot_master m on m.lot_id = l.lot_id where m.lot_no = 'L-OLD'",
                Long.class);
        int ver = accounts.query(comp, "select row_ver from wms_count where count_id = ?", Integer.class, id);
        mvc.perform(put("/api/wms/counts/" + id + "/counts").with(csrf()).contentType(MediaType.APPLICATION_JSON).with(admin)
                .content("{\"rowVer\":" + ver + ",\"rows\":[{\"lineId\":" + lNew + ",\"countedQty\":\"9\"},{\"lineId\":" + lOld
                        + ",\"countedQty\":\"5\"}]}"))
                .andExpect(jsonPath("$.data.lines[?(@.lotNo == 'L-NEW')].bookQty").value(org.hamcrest.Matchers.contains("10")));
        // 장부에 없던 LOT 줄 추가: LOT 없음 / 다른 품목 LOT / 비관리 품목에 LOT
        ver = accounts.query(comp, "select row_ver from wms_count where count_id = ?", Integer.class, id);
        postJson("/api/wms/counts/" + id + "/lines", "{\"rowVer\":" + ver + ",\"nodeId\":" + a2 + ",\"itemCd\":\"RM-L\"}")
                .andExpect(jsonPath("$.errors[0].code").value("LOT_REQUIRED"));
        postJson("/api/wms/counts/" + id + "/lines", "{\"rowVer\":" + ver + ",\"nodeId\":" + a2 + ",\"itemCd\":\"RM-L\",\"lotNo\":\"O-1\"}")
                .andExpect(jsonPath("$.errors[0].code").value("LOT_MISMATCH"));
        postJson("/api/wms/counts/" + id + "/lines", "{\"rowVer\":" + ver + ",\"nodeId\":" + a2 + ",\"itemCd\":\"SUB-1\",\"lotNo\":\"L-NEW\"}")
                .andExpect(jsonPath("$.errors[0].code").value("LOT_NOT_CONTROLLED"));
        ver = accounts.query(comp, "select row_ver from wms_count where count_id = ?", Integer.class, id);
        postJson("/api/wms/counts/" + id + "/submit", "{\"rowVer\":" + ver + "}").andExpect(status().isOk());
        ver = accounts.query(comp, "select row_ver from wms_count where count_id = ?", Integer.class, id);
        postJson("/api/wms/counts/" + id + "/post", "{\"rowVer\":" + ver + "}").andExpect(status().isOk());
        assertThat(stock(a1, "L-NEW", "AVAILABLE")).isEqualByComparingTo("9");
        assertThat(stock(a1, "L-OLD", "AVAILABLE")).isEqualByComparingTo("5");
        assertThat(accounts.query(comp, "select count(*) from inv_trx where trx_type = 'COUNT_ADJ' and lot_id = ?", Integer.class,
                lotId("L-NEW"))).isEqualTo(1);
    }

    @Test
    void 차감_미리보기는_LOT_이_만들어진_순서() throws Exception {
        adjust(a1, lotItem, "L-NEW", "10").andExpect(status().isOk());
        adjust(a2, lotItem, "L-OLD", "5").andExpect(status().isOk());
        postJson("/api/wms/stock/alloc-preview", "{\"plantCd\":\"P1\",\"itemCd\":\"RM-L\",\"qty\":\"8\",\"strategy\":\"FIFO\"}")
                .andExpect(jsonPath("$.data.picks[0].lotNo").value("L-OLD")).andExpect(jsonPath("$.data.picks[0].takeQty").value("5"))
                .andExpect(jsonPath("$.data.picks[1].lotNo").value("L-NEW")).andExpect(jsonPath("$.data.picks[1].takeQty").value("3"));
    }

    // =====================================================================
    // 이상 사용
    // =====================================================================

    @Test
    void B_LOT_필수_다른품목_비관리품목_없는_LOT() throws Exception {
        adjust(a1, lotItem, null, "1").andExpect(jsonPath("$.errors[0].field").value("lotNo"))
                .andExpect(jsonPath("$.errors[0].code").value("LOT_REQUIRED"));
        adjust(a1, lotItem, "O-1", "1").andExpect(jsonPath("$.errors[0].code").value("LOT_MISMATCH"));
        adjust(a1, plain, "L-NEW", "1").andExpect(jsonPath("$.errors[0].code").value("LOT_NOT_CONTROLLED"));
        adjust(a1, lotItem, "NOPE", "1").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        adjust(a1, plain, null, "1").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from inv_trx where lot_id is not null", Integer.class)).isZero();
    }

    @Test
    void 보류_LOT_은_꺼내거나_옮길_수_없고_같은_자리_상태만_바꿈() throws Exception {
        adjust(a1, lotItem, "L-NEW", "10").andExpect(status().isOk());
        MvcResult mv = move("L-NEW", a1, "AVAILABLE", a2, "AVAILABLE", "2").andExpect(status().isOk()).andReturn();
        long moveOut = ((Number) JsonPath.read(mv.getResponse().getContentAsString(), "$.data.trxIds[0]")).longValue();
        hold("L-NEW", true);
        adjust(a1, lotItem, "L-NEW", "-1").andExpect(jsonPath("$.errors[0].code").value("LOT_HOLD"));
        move("L-NEW", a1, "AVAILABLE", a2, "AVAILABLE", "1").andExpect(jsonPath("$.errors[0].code").value("LOT_HOLD"));
        move("L-NEW", a1, "AVAILABLE", a1, "HOLD", "8").andExpect(status().isOk());              // 격리
        adjust(a1, lotItem, "L-NEW", "1").andExpect(status().isOk());                            // 들어오는 것은 된다
        // 직접 SQL 출고 / 앞 이동의 역분개(정정은 된다)
        assertThat(sqlError("""
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, lot_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_OUT', ?, ?, ?, 'HOLD', -1, 'EA', 'MANUAL', now(), 'KEY', 't')""", comp, lotItem, a1, lotId("L-NEW")))
                .isEqualTo("23514 ck_inv_trx_lot_hold");
        postJson("/api/wms/trx/" + moveOut + "/reverse", "{\"reason\":\"보류 LOT 이동 취소\"}").andExpect(status().isOk());
        assertThat(stock(a1, "L-NEW", "HOLD")).isEqualByComparingTo("8");
        assertThat(stock(a1, "L-NEW", "AVAILABLE")).isEqualByComparingTo("3");
        assertThat(stock(a2, "L-NEW", "AVAILABLE")).isEqualByComparingTo("0");
    }

    @Test
    void C_보류_지정이_먼저_잡히면_출고는_기다렸다가_거부() throws Exception {
        adjust(a1, lotItem, "L-NEW", "10").andExpect(status().isOk());
        long lot = lotId("L-NEW");
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> runner.run(comp, () -> {
            // MES 의 보류 지정 흉내(보류 근거 추가 → 트리거가 LOT 행을 잠그고 고침) — 그 채 1.5초(소유자가 아니라 앱 계정, 같은 회사)
            jdbc.update("insert into lot_hold (comp_cd, lot_id, source, reason, held_by) select comp_cd, lot_id, 'MANUAL', '동시 시험', 't' from lot_master where lot_id = ?", lot);
            locked.countDown();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        // 서비스 검사는 커밋 전 보류를 못 보지만, 원장 트리거가 LOT 행 FOR SHARE 로 기다린 뒤 보류를 보고 막는다
        adjust(a1, lotItem, "L-NEW", "-1").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("LOT_HOLD"));
        holder.get(10, TimeUnit.SECONDS);
        assertThat(stock(a1, "L-NEW", "AVAILABLE")).isEqualByComparingTo("10");
    }

    @Test
    void A사로_B사_LOT() throws Exception {
        String b = accounts.newComp("LB");
        accounts.addPlant(b, "P1", "B사");
        long bWh = accounts.query(b, """
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', 'WH', 'WH', 'WH', 't', 't') returning node_id""", Long.class, b);
        long bItem = accounts.query(b, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'RM-L', 'B', 'RM', 'BUY', 'EA', 'Y', 't', 't') returning item_id""", Long.class, b);
        long bu = accounts.newUser(b, "badmin", PW);
        accounts.grant(b, bu, "WMS", "wms-move", "SEARCH", "SAVE");
        RequestPostProcessor bp = accounts.as(b, bu, "badmin");
        // B사 화면에서 A사 LOT 번호 → 없음(RLS), 스캔도 없음
        mvc.perform(post("/api/wms/move/adjust").with(csrf()).contentType(MediaType.APPLICATION_JSON).with(bp)
                .content("{\"plantCd\":\"P1\",\"nodeId\":" + bWh + ",\"itemId\":" + bItem + ",\"stockStatus\":\"AVAILABLE\",\"delta\":1,"
                        + "\"reasonCd\":\"FOUND\",\"lotNo\":\"L-NEW\"}"))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        mvc.perform(post("/api/wms/move/scan").with(csrf()).contentType(MediaType.APPLICATION_JSON).with(bp)
                .content("{\"plantCd\":\"P1\",\"text\":\"LOT:L-NEW\"}")).andExpect(jsonPath("$.data.ok").value(false));
        // 직접 SQL: B사 범위에서 A사 LOT id 로 원장
        Throwable t = catchThrowable(() -> accounts.exec(b, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, lot_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, ?, 'AVAILABLE', 1, 'EA', 'MANUAL', now(), 'KEY', 't')""", b, bItem, bWh, lotId("L-NEW")));
        assertThat(PgErrors.constraint(t).orElse("")).isEqualTo("fk_inv_trx_lot_comp");
    }
}
