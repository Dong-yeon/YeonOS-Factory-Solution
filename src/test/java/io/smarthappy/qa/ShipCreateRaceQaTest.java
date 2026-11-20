package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.wms.link.StockQuery;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA — 출하 지시 만들기 동시(docs/53 (가) 소프트 예약 확인 R3). 모듈 시험(ShipTest)이 덮은 것(다른 수주 행 두 지시 → 둘 다 저장·경고 숫자, 피킹·
 * 피킹 취소·지시 취소 즉시 반영, 홈 카드, A사로 B사)은 다시 하지 않는다. 여기서는 **같은 수주 행**으로 두 지시를 동시에 — 남은 수량 확인(수주 − 출하 −
 * 다른 열린 지시)이 잠금 없이 읽기라, 둘 다 확인을 지나 채번에서 기다리게 한 뒤 놓는다. 분석 docs/qa/2026-10-29_pms-wms-qms-mes.md
 */
@AutoConfigureMockMvc
class ShipCreateRaceQaTest extends PostgresTestBase {

    static final String PW = "Qa-ship-race-31";
    static final String S = "/api/wms/ships";
    static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StockQuery stockQuery;

    String comp;
    RequestPostProcessor admin;
    long stg;
    long fg;

    @AfterAll
    static void stop() {
        POOL.shutdownNow();
    }

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("QW");
        long u = accounts.newUser(comp, "qw1", PW);
        accounts.grant(comp, u, "SALES", "sal-order", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-ship", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "qw1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'C-001', 'C-001 상사', 'SALES', 't', 't')",
                comp);
        fg = accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'FG-100', 'FG 품목', 'FG', 'MAKE', 'EA', 'N', 't', 't') returning item_id""", Long.class, comp);
        long wh = node(null, "WH", "FG-WH");
        long a1 = node(wh, "LOC", "A-01");
        stg = node(wh, "LOC", "STG-01");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't')", stg, comp);
        accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at, entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', 100, 'EA', 'MANUAL', now(), 'KEY', 't')""", comp, fg, a1);
    }

    long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd);
    }

    long orderLine(String qty) throws Exception {
        MvcResult m = mvc.perform(post("/api/sales/orders").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"custCd\":\"C-001\",\"lines\":[{\"itemCd\":\"FG-100\",\"qty\":\"" + qty + "\",\"dueDate\":\"2026-11-30\"}]}"))
                .andExpect(status().isOk()).andReturn();
        long order = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
        return accounts.query(comp, "select line_id from sal_order_line where order_id = ? and line_no = 10", Long.class, order);
    }

    MvcResult create(long orderLine, int qty) throws Exception {
        return mvc.perform(post(S).with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON).content("{\"plantCd\":\"P1\",\"custCd\":\"C-001\","
                + "\"stageNodeId\":" + stg + ",\"planDate\":\"2026-11-20\",\"lines\":[{\"orderLineId\":" + orderLine + ",\"qty\":" + qty + "}]}")).andReturn();
    }

    int lockWaiters() {
        return jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()",
                Integer.class);
    }

    /**
     * 결함 후보 W-1: 같은 수주 행(20)으로 지시 두 개(각 20)를 동시에 — 둘 다 남은 수량 20 을 보고 저장돼 수주 행에 열린 지시 40(남은 수량 −20),
     * 예약도 40. 차례로 보내면 둘째는 400 SHIP_OVER_OPEN. 기대(고친 뒤): 하나만 저장, 다른 하나 SHIP_OVER_OPEN
     */
    @Test
    void 같은_수주_행으로_동시에_만든_두_지시는_남은_수량을_넘지_않는다() throws Exception {
        create(orderLine("5"), 5);                                  // 오늘 채번 행을 만들어 둔다(잠글 대상)
        long line = orderLine("20");
        try (Connection hold = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = hold.createStatement()) {
            hold.setAutoCommit(false);
            st.execute("select 1 from sys_numbering_seq where comp_cd = '" + comp + "' and rule_cd = 'WMS_SHIP' for update");
            int before = lockWaiters();
            Future<MvcResult> f1 = POOL.submit(() -> create(line, 20));
            Future<MvcResult> f2 = POOL.submit(() -> create(line, 20));
            for (int i = 0; i < 200 && lockWaiters() - before < 2; i++) {
                Thread.sleep(50);
            }
            int waiting = lockWaiters() - before;
            hold.commit();
            int s1 = f1.get(30, TimeUnit.SECONDS).getResponse().getStatus();
            int s2 = f2.get(30, TimeUnit.SECONDS).getResponse().getStatus();
            BigDecimal inShips = accounts.query(comp, "select coalesce(sum(qty), 0) from wms_ship_line where order_line_id = ?", BigDecimal.class, line);
            BigDecimal reserved = runner.call(comp, () -> stockQuery.reservedByShip("P1", List.of(fg))).get(fg);
            System.out.println("[QW] 기다린 요청=" + waiting + " 응답=" + s1 + "," + s2 + " 수주 행 20 의 열린 지시 합=" + inShips + " 예약=" + reserved);
            assertThat(waiting).as("두 만들기 모두 남은 수량 확인을 지나 채번에서 기다린다(모두 관찰한 뒤 놓음)").isGreaterThanOrEqualTo(2);
            assertThat(inShips).as("수주 행 남은 수량(20)을 넘는 열린 지시가 없다").isLessThanOrEqualTo(new BigDecimal("20"));
        }
    }
}
