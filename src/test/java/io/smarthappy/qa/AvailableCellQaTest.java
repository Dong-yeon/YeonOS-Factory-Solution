package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

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
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA — F1 뒤 "가용 칸" 정의 비교(docs/53 2장). 모듈 시험(LotStockPostingTest)이 출하 대기(STAGING) 칸 건너뛰기·안내를 덮는다.
 * 여기서는 F1 이 맞추지 않은 차이 하나 — QC 용도 칸에 AVAILABLE 상태로 놓인 재고를 생산 투입이 꺼내는지(MRO 출고는 QC 용도 칸을 뺀다).
 * → F-a 결정(2026-10-29)으로 가용 정의를 하나로 모아 기대를 뒤집음.
 * 준비는 LotStockPostingTest 와 같은 방식(MES 흉내: 같은 트랜잭션에서 계보 행 + 훅). 분석 docs/qa/2026-10-28_grid-s2-f1-d2-calib.md
 */
@AutoConfigureMockMvc
class AvailableCellQaTest extends PostgresTestBase {

    static final String PW = "Qa-avail-cell-47";

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
    long a1;
    long qc1;
    long cell1;
    long rmItem;
    long wo;
    long woProc;
    long outLot;
    int seq;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("QF");
        long u = accounts.newUser(comp, "qf1", PW);
        accounts.grant(comp, u, "WMS", "wms-rcv", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-move", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "qf1");
        accounts.addPlant(comp, "P1", "1공장");
        long area = node(null, "AREA", "AR");
        long line1 = node(area, "LINE", "L1");
        cell1 = node(line1, "CELL", "C1");
        long wh = node(null, "WH", "RM-WH");
        a1 = node(wh, "LOC", "A-01");
        qc1 = node(wh, "LOC", "QC-01");
        attr(a1, "STORAGE");
        attr(qc1, "QC");
        rmItem = accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'QF-RM', 'QF-RM', 'RM', 'BUY', 'EA', 'Y', 't', 't') returning item_id""", Long.class, comp);
        long product = accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'QF-FG', 'QF-FG', 'FG', 'MAKE', 'EA', 'N', 't', 't') returning item_id""", Long.class, comp);
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'SUP1', 'SUP1', 'PURCHASE', 't', 't')",
                comp);
        wo = owner("insert into prd_wo (comp_cd, wo_no, plant_cd, item_id, plan_qty, unit_cd, plan_start, plan_end, routing_id, status, "
                + "released_at, created_by, updated_by) values (?, 'WO-QF', 'P1', ?, 100, 'EA', " + TestAccounts.COMP_TODAY + ", "
                + TestAccounts.COMP_TODAY + ", 0, 'IN_PROGRESS', now(), 't', 't') returning wo_id", comp, product);
        woProc = owner("insert into prd_wo_proc (comp_cd, wo_id, op_seq, proc_id, proc_cd, proc_nm, node_id, units_per_cycle, setup_min, "
                + "src_step_id, status, created_by, updated_by) values (?, ?, 10, 0, 'P10', '조립', ?, 1, 0, 0, 'RUN', 't', 't') "
                + "returning wo_proc_id", comp, wo, cell1);
        outLot = owner("insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, wo_id, occurred_at, created_by, updated_by) "
                + "values (?, 'QF-OUT', ?, 'PRODUCED', 1000, 'EA', ?, now(), 't', 't') returning lot_id", comp, product, wo);
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) "
                + "values (?, 'P1', 'WMS_STOCK_LINK', 'Y', 't', 't')", comp);
    }

    long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd);
    }

    void attr(long node, String usage) {
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, pick_seq, created_by, updated_by) values (?, ?, ?, 0, 't', 't')",
                node, comp, usage);
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

    ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(admin));
    }

    long receive(long node, String qty) throws Exception {
        MvcResult m = postJson("/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + node + ",\"itemId\":" + rmItem + ",\"qty\":" + qty
                + ",\"stockStatus\":\"AVAILABLE\",\"supplierCd\":\"SUP1\",\"clientReqId\":\"qf-" + (++seq) + "-" + System.nanoTime() + "\"}")
                .andExpect(status().isOk()).andReturn();
        String lotNo = JsonPath.read(m.getResponse().getContentAsString(), "$.data.lotNo");
        return accounts.query(comp, "select lot_id from lot_master where lot_no = ?", Long.class, lotNo);
    }

    int move(long lot, long from, long to, String toStatus, String qty) throws Exception {
        String lotNo = accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, lot);
        return postJson("/api/wms/move/move", "{\"plantCd\":\"P1\",\"itemId\":" + rmItem + ",\"fromNodeId\":" + from + ",\"fromStatus\":\"AVAILABLE\""
                + ",\"toNodeId\":" + to + ",\"toStatus\":\"" + toStatus + "\",\"qty\":" + qty + ",\"lotNo\":\"" + lotNo + "\"}")
                .andReturn().getResponse().getStatus();
    }

    long consume(long parent, String qty) {
        return runner.callAs(comp, "pop", () -> {
            Long g = jdbc.queryForObject("""
                    insert into lot_genealogy (comp_cd, parent_lot_id, child_lot_id, rel_type, qty, unit_cd, wo_id, wo_proc_id, occurred_at,
                                               entry_method, recorded_by)
                    values (hb_current_comp(), ?, ?, 'CONSUME', ?, 'EA', ?, ?, now(), 'SCAN', 'pop') returning gen_id""", Long.class,
                    parent, outLot, new BigDecimal(qty), wo, woProc);
            LotStockLink.of(links).onEdge(g);
            return g;
        });
    }

    BigDecimal stock(long node, long lot) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and item_id = ? and lot_id = ? and stock_status = 'AVAILABLE'",
                BigDecimal.class, node, rmItem, lot).stripTrailingZeros();
    }

    /**
     * F-a 결정(2026-10-29, docs/53 6장): 가용 = AVAILABLE 이고 칸 용도가 STAGING·QC·SCRAP 이 아님(한 SQL 조각 AvailSql). QC 용도 칸으로의
     * AVAILABLE 이동은 허용(막지 않음) — 그 칸 재고가 가용에서 빠질 뿐. 생산 투입은 일반 칸만, 검사 용도 칸에만 남으면 409.
     * (관찰 당시 기대는 "QC 용도 칸에서도 꺼냈다" — 결정으로 뒤집음)
     */
    @Test
    void 결정_QC_용도_칸으로_AVAILABLE_이동은_되고_생산_투입은_그_칸을_꺼내지_않는다() throws Exception {
        long rm = receive(a1, "10");
        int mv = move(rm, a1, qc1, "AVAILABLE", "4");
        assertThat(mv).as("QC 용도 칸에 AVAILABLE 로 둘 수 있다(허용)").isEqualTo(200);
        consume(rm, "6");                          // 일반 칸 6 만
        assertThat(stock(a1, rm)).isEqualByComparingTo("0");
        assertThat(stock(qc1, rm)).as("검사 용도 칸은 그대로").isEqualByComparingTo("4");
        Throwable t = catchThrowable(() -> consume(rm, "1"));
        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).code()).isEqualTo(HbErrorCode.INSUFFICIENT_STOCK);
        assertThat(stock(qc1, rm)).isEqualByComparingTo("4");
    }
}
