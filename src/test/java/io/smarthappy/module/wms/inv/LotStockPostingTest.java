package io.smarthappy.module.wms.inv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.wms.link.LotStockLink;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * LOT 계보 → 재고 원장 (P4 2부 B5 투입 차감 · B2+ LOT 변환, docs/30 12.4). MES 훅은 아직 없으므로 MES 흉내:
 * 같은 트랜잭션에서 계보 행을 넣고(앱 계정 — 계보 가드가 그대로 돈다) {@link LotStockLink#onEdge}·{@link LotStockLink#onReverse} 를 부른다.
 * 자재는 자재 입고 API 로 들인다(재고로 관리되는 LOT — D-B14).
 */
@AutoConfigureMockMvc
class LotStockPostingTest extends PostgresTestBase {

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
    ObjectProvider<LotStockLink> links;

    String comp;
    RequestPostProcessor admin;
    long line1;
    long cell1;
    long line2;
    long a1;
    long a2;
    long ls1;
    long ls2;
    long lotItem;
    long plain;
    long product;
    long wo;
    long woProc;
    long outLot;
    int seq;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("LK");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "WMS", "wms-rcv", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-move", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-loc", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-period", "SEARCH", "APPROVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long area = node(null, "AREA", "AR");
        line1 = node(area, "LINE", "L1");
        cell1 = node(line1, "CELL", "C1");
        line2 = node(area, "LINE", "L2");
        long wh = node(null, "WH", "RM-WH");
        a1 = node(wh, "LOC", "A-01");
        a2 = node(wh, "LOC", "A-02");
        long lsWh = node(area, "WH", "LS-WH");
        ls1 = node(lsWh, "LOC", "LS-1");
        ls2 = node(lsWh, "LOC", "LS-2");
        attr(a1, "STORAGE", 1, null);
        attr(a2, "STORAGE", 2, null);
        attr(ls1, "LINESIDE", 0, line1);
        attr(ls2, "LINESIDE", 0, line2);
        lotItem = item("RM-L", "Y");
        plain = item("RM-P", "N");
        product = item("FG-1", "N");
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) "
                + "values (?, 'SUP1', 'SUP1', 'PURCHASE', 't', 't')", comp);
        wo = owner("insert into prd_wo (comp_cd, wo_no, plant_cd, item_id, plan_qty, unit_cd, plan_start, plan_end, routing_id, status, "
                + "released_at, created_by, updated_by) values (?, 'WO-1', 'P1', ?, 100, 'EA', " + TestAccounts.COMP_TODAY + ", "
                + TestAccounts.COMP_TODAY + ", 0, 'IN_PROGRESS', now(), 't', 't') returning wo_id", comp, product);
        woProc = owner("insert into prd_wo_proc (comp_cd, wo_id, op_seq, proc_id, proc_cd, proc_nm, node_id, units_per_cycle, setup_min, "
                + "src_step_id, status, created_by, updated_by) values (?, ?, 10, 0, 'P10', '조립', ?, 1, 0, 0, 'RUN', 't', 't') "
                + "returning wo_proc_id", comp, wo, cell1);
        outLot = lot("OUT-1", product, "PRODUCED");
        policy("Y");
    }

    long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd);
    }

    void attr(long node, String usage, int pick, Long feed) {
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, pick_seq, feed_node_id, created_by, updated_by) "
                + "values (?, ?, ?, ?, ?, 't', 't')", node, comp, usage, pick, feed);
    }

    long item(String cd, String lotYn) {
        return accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, ?, ?, 'RM', 'BUY', 'EA', ?, 't', 't') returning item_id""", Long.class, comp, cd, cd, lotYn);
    }

    void policy(String yn) {
        accounts.exec(comp, "delete from sys_policy where policy_key = 'WMS_STOCK_LINK'");
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) "
                + "values (?, 'P1', 'WMS_STOCK_LINK', ?, 't', 't')", comp, yn);
    }

    /** 소유자 연결(트리거 끔) — MES 표 준비만 */
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

    long lot(String no, long item, String origin) throws Exception {
        return owner("insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, wo_id, occurred_at, created_by, updated_by) "
                + "values (?, ?, ?, ?, 1000, 'EA', ?, now(), 't', 't') returning lot_id", comp, no, item, origin,
                "PRODUCED".equals(origin) ? wo : null);
    }

    ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(admin));
    }

    /** 자재 입고 → 입고 LOT id */
    long receive(long node, long item, String qty) throws Exception {
        MvcResult m = postJson("/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + node + ",\"itemId\":" + item + ",\"qty\":" + qty
                + ",\"stockStatus\":\"AVAILABLE\",\"supplierCd\":\"SUP1\",\"clientReqId\":\"lk-test-" + (++seq) + "-" + System.nanoTime() + "\"}")
                .andExpect(status().isOk()).andReturn();
        String lotNo = JsonPath.read(m.getResponse().getContentAsString(), "$.data.lotNo");
        return accounts.query(comp, "select lot_id from lot_master where lot_no = ?", Long.class, lotNo);
    }

    /** 조정 출고(LOT 잔량은 그대로, 재고만 줄임) */
    void adjustOut(long lot, String qty) throws Exception {
        String lotNo = accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, lot);
        postJson("/api/wms/move/adjust", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + lotItem
                + ",\"stockStatus\":\"AVAILABLE\",\"delta\":-" + qty + ",\"reasonCd\":\"FOUND\",\"lotNo\":\"" + lotNo + "\"}")
                .andExpect(status().isOk());
    }

    void move(long lot, long from, String fs, long to, String ts, String qty) throws Exception {
        String lotNo = accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, lot);
        postJson("/api/wms/move/move", "{\"plantCd\":\"P1\",\"itemId\":" + lotItem + ",\"fromNodeId\":" + from + ",\"fromStatus\":\"" + fs
                + "\",\"toNodeId\":" + to + ",\"toStatus\":\"" + ts + "\",\"qty\":" + qty + ",\"lotNo\":\"" + lotNo + "\"}").andExpect(status().isOk());
    }

    /** MES 흉내: 같은 트랜잭션에서 계보 행 + 훅 */
    long edge(String rel, long parent, long child, String qty, boolean withProc) {
        return runner.callAs(comp, "pop", () -> {
            Long g = jdbc.queryForObject("""
                    insert into lot_genealogy (comp_cd, parent_lot_id, child_lot_id, rel_type, qty, unit_cd, wo_id, wo_proc_id, reason,
                                               occurred_at, entry_method, recorded_by)
                    values (hb_current_comp(), ?, ?, ?, ?, 'EA', ?, ?, ?, now(), 'SCAN', 'pop') returning gen_id""", Long.class,
                    parent, child, rel, new BigDecimal(qty), withProc ? wo : null, withProc ? woProc : null,
                    "CONSUME".equals(rel) ? null : "시험 " + rel);
            LotStockLink.of(links).onEdge(g);
            return g;
        });
    }

    long reverse(long gen) {
        return runner.callAs(comp, "pop", () -> {
            Long r = jdbc.queryForObject("""
                    insert into lot_genealogy (comp_cd, parent_lot_id, child_lot_id, rel_type, qty, unit_cd, wo_id, wo_proc_id, equip_id, reason,
                                               occurred_at, entry_method, recorded_by, reversal_of_id)
                    select comp_cd, parent_lot_id, child_lot_id, rel_type, -qty, unit_cd, wo_id, wo_proc_id, equip_id, '실적 정정',
                           now(), 'CORRECT', 'pop', gen_id from lot_genealogy where gen_id = ? returning gen_id""", Long.class, gen);
            LotStockLink.of(links).onReverse(r);
            return r;
        });
    }

    BigDecimal stock(long node, long item, Long lot, String st) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and item_id = ? and lot_id is not distinct "
                + "from ?::bigint and stock_status = ?", BigDecimal.class, node, item, lot, st).stripTrailingZeros();
    }

    int count(String sql, Object... args) {
        return accounts.query(comp, sql, Integer.class, args);
    }

    static HbErrorCode code(Throwable t) {
        return t instanceof BusinessException b ? b.code() : null;
    }

    // =====================================================================
    // 투입 차감 ISSUE_WO
    // =====================================================================

    @Test
    void 투입은_보급_라인의_라인_옆_먼저_다음_창고_pick_seq_다른_라인_라인_옆은_안_건드림() throws Exception {
        long rm = receive(a1, lotItem, "20");
        move(rm, a1, "AVAILABLE", ls1, "AVAILABLE", "3");
        move(rm, a1, "AVAILABLE", ls2, "AVAILABLE", "5");
        long g = edge("CONSUME", rm, outLot, "6", true);
        // 셀 C1 의 라인 L1 → LS-1 3 먼저, 다음 창고 A-01 3. L2 의 LS-2 는 그대로
        assertThat(stock(ls1, lotItem, rm, "AVAILABLE")).isEqualByComparingTo("0");
        assertThat(stock(a1, lotItem, rm, "AVAILABLE")).isEqualByComparingTo("9");
        assertThat(stock(ls2, lotItem, rm, "AVAILABLE")).isEqualByComparingTo("5");
        assertThat(count("select count(*) from inv_trx where trx_type = 'ISSUE_WO' and src_type = 'LOT_GEN' and src_id = ? "
                + "and entry_method = 'AUTO' and recorded_by = 'pop'", g)).isEqualTo(2);
        // 같은 계보로 다시 불려도 두 번 안 들어간다(멱등 키 LOT_GEN:{id})
        runner.callAs(comp, "pop", () -> {
            LotStockLink.of(links).onEdge(g);
            return null;
        });
        assertThat(count("select count(*) from inv_trx where src_id = ? and src_type = 'LOT_GEN'", g)).isEqualTo(2);
    }

    @Test
    void 비관리_품목_투입은_품목_재고에서() throws Exception {
        long rm = receive(a2, plain, "10");
        edge("CONSUME", rm, outLot, "4", true);
        assertThat(stock(a2, plain, null, "AVAILABLE")).isEqualByComparingTo("6");
    }

    @Test
    void 스위치_N_이거나_재공_LOT_이면_반영하지_않는다() throws Exception {
        long rm = receive(a1, lotItem, "10");
        policy("N");
        edge("CONSUME", rm, outLot, "4", true);
        assertThat(stock(a1, lotItem, rm, "AVAILABLE")).isEqualByComparingTo("10");
        policy("Y");
        // 창고에 들어온 적 없는 재공 LOT(앞 공정 산출) — D-B14
        long wip = lot("WIP-1", lotItem, "PRODUCED");
        edge("CONSUME", wip, outLot, "4", true);
        assertThat(count("select count(*) from inv_trx where lot_id = ?", wip)).isZero();
    }

    @Test
    void 재고가_모자라면_409_계보까지_되돌림() throws Exception {
        long rm = receive(a1, lotItem, "10");
        adjustOut(rm, "5");                         // LOT 잔량(계보) 10 · 재고 5 — 재고 쪽에서 모자란다
        Throwable t = catchThrowable(() -> edge("CONSUME", rm, outLot, "6", true));
        assertThat(code(t)).isEqualTo(HbErrorCode.INSUFFICIENT_STOCK);
        assertThat(stock(a1, lotItem, rm, "AVAILABLE")).isEqualByComparingTo("5");
        assertThat(count("select count(*) from lot_genealogy where parent_lot_id = ?", rm)).isZero();
        // QC 상태 재고는 투입 대상이 아니다
        move(rm, a1, "AVAILABLE", a1, "QC", "5");
        assertThat(code(catchThrowable(() -> edge("CONSUME", rm, outLot, "1", true)))).isEqualTo(HbErrorCode.INSUFFICIENT_STOCK);
    }

    /**
     * docs/53 F-a(2026-10-29) — 가용 정의 하나(AvailSql): 검사 용도(QC) 칸에 AVAILABLE 로 옮겨 둔 재고는 투입하지 않는다.
     * 그 칸으로의 이동은 막지 않는다(가용에서 빠질 뿐). 출하 대기 안내는 출하 대기 칸일 때만
     */
    @Test
    void 투입은_검사_용도_칸의_AVAILABLE_재고를_건너뛰고_거기에만_있으면_409() throws Exception {
        long qcWh = node(null, "WH", "QC-WH");
        long qc1 = node(qcWh, "LOC", "QC-1");
        attr(qc1, "QC", 0, null);
        long rm = receive(a1, lotItem, "10");
        move(rm, a1, "AVAILABLE", qc1, "AVAILABLE", "4");                            // 이동은 된다 — A-01 6 · QC-1 4
        assertThat(stock(qc1, lotItem, rm, "AVAILABLE")).isEqualByComparingTo("4");
        edge("CONSUME", rm, outLot, "6", true);
        assertThat(stock(a1, lotItem, rm, "AVAILABLE")).isEqualByComparingTo("0");
        Throwable t = catchThrowable(() -> edge("CONSUME", rm, outLot, "1", true));  // 검사 용도 칸에만 남음
        assertThat(code(t)).isEqualTo(HbErrorCode.INSUFFICIENT_STOCK);
        assertThat(((BusinessException) t).details()).isEmpty();
        assertThat(stock(qc1, lotItem, rm, "AVAILABLE")).isEqualByComparingTo("4");
    }

    /** docs/53 F1 — 출하 대기(STAGING) 칸 재고는 투입하지 않는다(피킹해 둔 출하분). 거기에만 있으면 409 + "먼저 일반 칸으로" 안내 */
    @Test
    void 투입은_출하_대기_칸을_건너뛰고_거기에만_있으면_409와_옮기라는_안내() throws Exception {
        long stg = node(null, "WH", "STG-WH");
        long stg1 = node(stg, "LOC", "STG-1");
        attr(stg1, "STAGING", 0, null);
        long rm = receive(a1, lotItem, "10");
        move(rm, a1, "AVAILABLE", stg1, "AVAILABLE", "4");                          // A-01 6 · STG-1 4
        edge("CONSUME", rm, outLot, "5", true);
        assertThat(stock(a1, lotItem, rm, "AVAILABLE")).isEqualByComparingTo("1");   // 일반 칸에서만
        assertThat(stock(stg1, lotItem, rm, "AVAILABLE")).isEqualByComparingTo("4");
        Throwable t = catchThrowable(() -> edge("CONSUME", rm, outLot, "3", true));  // 일반 칸 1 + 출하 대기 4 — 모자람
        assertThat(code(t)).isEqualTo(HbErrorCode.INSUFFICIENT_STOCK);
        assertThat(((BusinessException) t).details()).extracting(BusinessException.Detail::messageKey).containsExactly("wms.issue.stagingHint");
        assertThat(((BusinessException) t).details().get(0).args()).containsExactly("4");
        assertThat(stock(stg1, lotItem, rm, "AVAILABLE")).isEqualByComparingTo("4");
        edge("CONSUME", rm, outLot, "1", true);                                       // 일반 칸만으로 되는 양은 그대로 된다
        assertThat(stock(a1, lotItem, rm, "AVAILABLE")).isEqualByComparingTo("0");
        Throwable t2 = catchThrowable(() -> edge("CONSUME", rm, outLot, "1", true));  // 출하 대기에만 남음
        assertThat(((BusinessException) t2).details()).extracting(BusinessException.Detail::messageKey).containsExactly("wms.issue.stagingHint");
        long other = receive(a1, lotItem, "4");                                      // 출하 대기에 없는 LOT 의 부족은 안내 없음
        adjustOut(other, "2");                                                       // LOT 잔량 4 · 재고 2
        Throwable t3 = catchThrowable(() -> edge("CONSUME", other, outLot, "3", true));
        assertThat(code(t3)).isEqualTo(HbErrorCode.INSUFFICIENT_STOCK);
        assertThat(((BusinessException) t3).details()).isEmpty();
    }

    @Test
    void 정정은_원래_칸으로_되돌리고_시각은_정정_계보_시각() throws Exception {
        long rm = receive(a1, lotItem, "20");
        move(rm, a1, "AVAILABLE", ls1, "AVAILABLE", "2");
        long g = edge("CONSUME", rm, outLot, "5", true);
        long r = reverse(g);
        assertThat(stock(ls1, lotItem, rm, "AVAILABLE")).isEqualByComparingTo("2");
        assertThat(stock(a1, lotItem, rm, "AVAILABLE")).isEqualByComparingTo("18");
        assertThat(count("""
                select count(*) from inv_trx t join inv_trx o on o.trx_id = t.reversal_of_id
                 where o.src_id = ? and t.entry_method = 'CORRECT'
                   and t.occurred_at = (select occurred_at from lot_genealogy where gen_id = ?)""", g, r)).isEqualTo(2);
        // 스위치를 끈 뒤의 정정도 이미 들어간 원장은 되돌린다 · 원장이 없던 계보의 정정은 아무것도 안 함
        long g2 = edge("CONSUME", rm, outLot, "1", true);
        policy("N");
        reverse(g2);
        assertThat(stock(a1, lotItem, rm, "AVAILABLE")).isEqualByComparingTo("18");
        long g3 = edge("CONSUME", rm, outLot, "1", true);
        reverse(g3);
        assertThat(count("select count(*) from inv_trx where src_id = ? and src_type = 'LOT_GEN'", g3)).isZero();
    }

    @Test
    void 마감된_달_투입은_PERIOD_CLOSED() throws Exception {
        long rm = receive(a1, lotItem, "10");
        String lastMonth = accounts.query(comp, "select to_char(date_trunc('month', now() at time zone 'Asia/Seoul') - interval '1 month', "
                + "'YYYY-MM')", String.class);
        postJson("/api/wms/periods/close", "{\"plantCd\":\"P1\",\"period\":\"" + lastMonth + "\"}").andExpect(status().isOk());
        Throwable t = catchThrowable(() -> runner.callAs(comp, "pop", () -> {
            Long g = jdbc.queryForObject("""
                    insert into lot_genealogy (comp_cd, parent_lot_id, child_lot_id, rel_type, qty, unit_cd, wo_id, wo_proc_id,
                                               occurred_at, entry_method, recorded_by)
                    values (hb_current_comp(), ?, ?, 'CONSUME', 1, 'EA', ?, ?, date_trunc('month', now()) - interval '10 days', 'SCAN', 'pop')
                    returning gen_id""", Long.class, rm, outLot, wo, woProc);
            LotStockLink.of(links).onEdge(g);
            return g;
        }));
        assertThat(code(t)).isEqualTo(HbErrorCode.PERIOD_CLOSED);
    }

    // =====================================================================
    // LOT 변환 CONV_OUT / CONV_IN
    // =====================================================================

    @Test
    void 분할은_가용분부터_같은_칸_같은_상태로_자식_LOT() throws Exception {
        long rm = receive(a1, lotItem, "10");
        move(rm, a1, "AVAILABLE", a2, "HOLD", "4");
        long child = lot("SPL-1", lotItem, "SPLIT");
        long g = edge("SPLIT", rm, child, "8", false);
        assertThat(stock(a1, lotItem, rm, "AVAILABLE")).isEqualByComparingTo("0");
        assertThat(stock(a1, lotItem, child, "AVAILABLE")).isEqualByComparingTo("6");
        assertThat(stock(a2, lotItem, rm, "HOLD")).isEqualByComparingTo("2");
        assertThat(stock(a2, lotItem, child, "HOLD")).isEqualByComparingTo("2");
        assertThat(count("select count(distinct move_grp_id) from inv_trx where src_id = ? and trx_type in ('CONV_OUT', 'CONV_IN')", g))
                .isEqualTo(2);
    }

    @Test
    void 재작업은_보류분부터() throws Exception {
        long rm = receive(a1, lotItem, "10");
        move(rm, a1, "AVAILABLE", a2, "HOLD", "4");
        long child = lot("RWK-1", lotItem, "REWORK");
        edge("REWORK", rm, child, "5", false);
        assertThat(stock(a2, lotItem, child, "HOLD")).isEqualByComparingTo("4");
        assertThat(stock(a1, lotItem, child, "AVAILABLE")).isEqualByComparingTo("1");
        assertThat(stock(a1, lotItem, rm, "AVAILABLE")).isEqualByComparingTo("5");
    }

    @Test
    void 비관리_품목_변환과_재공_LOT_변환은_재고_변화_없음() throws Exception {
        long rm = receive(a1, plain, "10");
        long child = lot("SPL-P", plain, "SPLIT");
        edge("SPLIT", rm, child, "3", false);
        assertThat(stock(a1, plain, null, "AVAILABLE")).isEqualByComparingTo("10");
        long wip = lot("WIP-2", lotItem, "PRODUCED");
        long wipChild = lot("SPL-W", lotItem, "SPLIT");
        edge("SPLIT", wip, wipChild, "3", false);
        assertThat(count("select count(*) from inv_trx where lot_id in (?, ?)", wip, wipChild)).isZero();
    }

    @Test
    void 변환_재고가_모자라면_409() throws Exception {
        long rm = receive(a1, lotItem, "10");
        adjustOut(rm, "7");
        long child = lot("SPL-2", lotItem, "SPLIT");
        assertThat(code(catchThrowable(() -> edge("SPLIT", rm, child, "4", false)))).isEqualTo(HbErrorCode.INSUFFICIENT_STOCK);
        assertThat(stock(a1, lotItem, rm, "AVAILABLE")).isEqualByComparingTo("3");
    }

    // =====================================================================
    // 동시성 · 직접 SQL · 회사 · 화면
    // =====================================================================

    @Test
    void C_같은_비관리_품목_재고를_두_투입이_동시에_하나만() throws Exception {
        long r1 = receive(a1, plain, "3");
        long r2 = receive(a1, plain, "3");
        // 품목 재고 = 5(조정 −1), 투입 LOT 은 서로 다름 — LOT 잠금으로 줄 서지 않고 같은 재고 칸에서 만난다
        postJson("/api/wms/move/adjust", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + plain
                + ",\"stockStatus\":\"AVAILABLE\",\"delta\":-1,\"reasonCd\":\"FOUND\"}").andExpect(status().isOk());
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<HbErrorCode>> calls = List.of(r1, r2).stream().map(lot -> CompletableFuture.supplyAsync(() -> {
            try {
                go.await(10, TimeUnit.SECONDS);
                edge("CONSUME", lot, outLot, "3", true);
                return (HbErrorCode) null;
            } catch (Exception e) {
                return code(e);
            }
        })).toList();
        go.countDown();
        List<HbErrorCode> codes = java.util.Arrays.asList(calls.get(0).get(20, TimeUnit.SECONDS), calls.get(1).get(20, TimeUnit.SECONDS));
        assertThat(codes).containsExactlyInAnyOrder(null, HbErrorCode.INSUFFICIENT_STOCK);
        assertThat(stock(a1, plain, null, "AVAILABLE")).isEqualByComparingTo("2");
    }

    @Test
    void 직접_SQL_출처_없는_투입_짝_어긋난_변환_다른_라인_아닌_보급_라인() throws Exception {
        long rm = receive(a1, lotItem, "10");
        String issue = """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, lot_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ISSUE_WO', ?, ?, ?, 'AVAILABLE', -1, 'EA', 'MANUAL', now(), 'AUTO', 't')""";
        assertThat(sqlError(issue, comp, lotItem, a1, rm)).isEqualTo("ck_inv_trx_lot_gen_src");
        long child = lot("SPL-X", lotItem, "SPLIT");
        // 변환 짝인데 위치가 다름 → 짝 검사(트랜잭션 끝)
        assertThat(sqlErrorTx("""
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, lot_id, stock_status, qty, unit_cd, src_type, src_id,
                                     move_grp_id, occurred_at, entry_method, recorded_by)
                values (hb_current_comp(), 'P1', 'CONV_OUT', %d, %d, %d, 'AVAILABLE', -1, 'EA', 'LOT_GEN', 1, '00000000-0000-0000-0000-000000000001',
                        now(), 'AUTO', 't'),
                       (hb_current_comp(), 'P1', 'CONV_IN', %d, %d, %d, 'AVAILABLE', 1, 'EA', 'LOT_GEN', 1, '00000000-0000-0000-0000-000000000001',
                        now(), 'AUTO', 't')""".formatted(lotItem, a1, rm, lotItem, a2, child))).isEqualTo("ck_inv_trx_move_pair");
        // 보급 라인: 창고 노드를 라인으로 / LINESIDE 아닌 위치에 보급 라인
        assertThat(sqlError("update wms_loc_attr set feed_node_id = ? where node_id = ?", a1, ls1)).isEqualTo("fk_wms_loc_attr_feed_comp");
        assertThat(sqlError("update wms_loc_attr set feed_node_id = ? where node_id = ?", line1, a1)).isEqualTo("ck_wms_loc_attr_feed");
    }

    String sqlError(String sql, Object... args) {
        Throwable t = catchThrowable(() -> accounts.exec(comp, sql, args));
        return t == null ? "OK" : PgErrors.constraint(t).orElse("?");
    }

    String sqlErrorTx(String sql) {
        Throwable t = catchThrowable(() -> runner.callAs(comp, "t", () -> jdbc.update(sql)));
        return t == null ? "OK" : PgErrors.constraint(t).orElse("?");
    }

    @Test
    void A사로_B사_계보_id_는_찾지_못함() throws Exception {
        long rm = receive(a1, lotItem, "10");
        long g = edge("CONSUME", rm, outLot, "1", true);
        String b = accounts.newComp("LKB");
        Throwable t = catchThrowable(() -> runner.callAs(b, "x", () -> {
            LotStockLink.of(links).onEdge(g);
            return null;
        }));
        assertThat(code(t)).isEqualTo(HbErrorCode.NOT_FOUND);
        // B사 위치 속성에 A사 라인을 보급 라인으로
        accounts.addPlant(b, "P1", "B");
        long bWh = accounts.query(b, "insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by) "
                + "values (?, 'P1', 'WH', 'W', 'W', 't', 't') returning node_id", Long.class, b);
        Throwable t2 = catchThrowable(() -> accounts.exec(b, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, feed_node_id, created_by, "
                + "updated_by) values (?, ?, 'LINESIDE', ?, 't', 't')", bWh, b, line1));
        assertThat(PgErrors.constraint(t2).orElse("")).isEqualTo("fk_wms_loc_attr_feed_comp");
    }

    @Test
    void 로케이션_화면_보급_라인_선택지와_저장() throws Exception {
        mvc.perform(get("/api/wms/loc/lines").param("plantCd", "P1").with(admin)).andExpect(jsonPath("$.data.length()").value(3));
        int ver = accounts.query(comp, "select row_ver from wms_loc_attr where node_id = ?", Integer.class, a1);
        mvc.perform(put("/api/wms/loc/" + a1).with(csrf()).contentType(MediaType.APPLICATION_JSON).with(admin)
                .content("{\"locUsage\":\"STORAGE\",\"stockYn\":\"Y\",\"pickSeq\":1,\"mixItemYn\":\"Y\",\"mixLotYn\":\"Y\",\"rowVer\":" + ver
                        + ",\"feedNodeId\":" + line1 + "}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("LINESIDE_ONLY"));
        mvc.perform(put("/api/wms/loc/" + a1).with(csrf()).contentType(MediaType.APPLICATION_JSON).with(admin)
                .content("{\"locUsage\":\"LINESIDE\",\"stockYn\":\"Y\",\"pickSeq\":1,\"mixItemYn\":\"Y\",\"mixLotYn\":\"Y\",\"rowVer\":" + ver
                        + ",\"feedNodeId\":" + line2 + "}"))
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select feed_node_id from wms_loc_attr where node_id = ?", Long.class, a1)).isEqualTo(line2);
        // 창고 노드를 보급 라인으로 → 칸 오류
        mvc.perform(put("/api/wms/loc/" + a1).with(csrf()).contentType(MediaType.APPLICATION_JSON).with(admin)
                .content("{\"locUsage\":\"LINESIDE\",\"stockYn\":\"Y\",\"pickSeq\":1,\"mixItemYn\":\"Y\",\"mixLotYn\":\"Y\",\"rowVer\":" + (ver + 1)
                        + ",\"feedNodeId\":" + a2 + "}"))
                .andExpect(jsonPath("$.errors[0].field").value("feedNodeId"));
    }
}
