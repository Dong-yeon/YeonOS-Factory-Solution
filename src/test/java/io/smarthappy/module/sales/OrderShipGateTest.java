package io.smarthappy.module.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.IllegalTransactionStateException;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.sales.link.OrderShipGate;
import io.smarthappy.module.sales.link.OrderShipGate.ShipQty;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 출하 게이트 (docs/25 4.22, WMS B6) — 부분·전량 출하(자동 마감), 허용 초과율 정책, 취소·사람 마감 행 409, 출하 취소(음수)로
 * 자동 마감 행·머리 다시 열기, 사람 마감 행은 그대로, 영업 규칙(출하 아래 수량·출하 행 취소 금지), 직접 SQL 가드,
 * 기본 단위 잠금에 수주 행, 엇갈린 순서 동시 출하, A사로 B사, NONE.
 */
@AutoConfigureMockMvc
class OrderShipGateTest extends PostgresTestBase {

    private static final String PW = "Harbor-violet-27";
    private static final String O = "/api/sales/orders";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    OrderShipGate gate;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("SG");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "SALES", "sal-order", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        base(comp);
    }

    private void base(String c) {
        accounts.exec(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C-001', 'C-001 상사', 'SALES', 't', 't')""", c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG-100', 'FG 품목', 'FG', 'MAKE', 'EA', 't', 't')""", c);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(admin);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body));
    }

    private long order(String... qtys) throws Exception {
        List<String> ls = new ArrayList<>();
        for (String q : qtys) {
            ls.add("{\"itemCd\":\"FG-100\",\"qty\":\"" + q + "\",\"dueDate\":\"2026-11-30\"}");
        }
        MvcResult m = postJson(O, "{\"custCd\":\"C-001\",\"lines\":[" + String.join(",", ls) + "]}")
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private long line(long orderId, int no) {
        return accounts.query(comp, "select line_id from sal_order_line where order_id = ? and line_no = ?", Long.class, orderId, no);
    }

    private String lineState(long lineId) {
        return accounts.query(comp, "select status || '/' || done_by_ship_yn || '/' || trim_scale(shipped_qty) || '/' || row_ver"
                + " from sal_order_line where line_id = ?", String.class, lineId);
    }

    private String head(long orderId) {
        return accounts.query(comp, "select status from sal_order where order_id = ?", String.class, orderId);
    }

    private void ship(String c, Object... idQty) {
        List<ShipQty> in = new ArrayList<>();
        for (int i = 0; i < idQty.length; i += 2) {
            in.add(new ShipQty((Long) idQty[i], new BigDecimal((String) idQty[i + 1])));
        }
        runner.call(c, () -> {
            gate.ship(in);
            return null;
        });
    }

    private HbErrorCode code(Runnable r) {
        Throwable t = catchThrowable(r::run);
        assertThat(t).isInstanceOf(BusinessException.class);
        return ((BusinessException) t).code();
    }

    // ---------- 정상 흐름 ----------

    @Test
    void 부분_출하는_열린_채_전량이면_자동_마감_머리도_CLOSED_화면은_출하_수량을_보인다() throws Exception {
        long o = order("10", "5");
        long l1 = line(o, 10);
        long l2 = line(o, 20);
        ship(comp, l1, "4");
        assertThat(lineState(l1)).isEqualTo("OPEN/N/4/1");                // row_ver 도 오른다
        // 같은 행 두 번 = 합친 수량, 두 행 한 번에
        ship(comp, l1, "3", l2, "5", l1, "3");
        assertThat(lineState(l1)).isEqualTo("DONE/Y/10/2");
        assertThat(lineState(l2)).isEqualTo("DONE/Y/5/1");
        assertThat(head(o)).isEqualTo("CLOSED");
        mvc.perform(get(O + "/" + o).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].shippedQty").value("10"))
                .andExpect(jsonPath("$.data.lines[0].doneByShipYn").value("Y"));
    }

    @Test
    void 초과_출하는_409_OVER_SHIP_정책_퍼센트_안이면_자동_마감_행에도_더_나간다() throws Exception {
        long o = order("10");
        long l = line(o, 10);
        assertThat(code(() -> ship(comp, l, "10.5"))).isEqualTo(HbErrorCode.OVER_SHIP);
        assertThat(lineState(l)).isEqualTo("OPEN/N/0/0");                   // 아무것도 안 바뀜
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by)"
                + " values (?, 'SAL_OVERSHIP_PCT', '10', 't', 't')", comp);
        ship(comp, l, "10");
        ship(comp, l, "1");                                                 // 자동 마감 행 + 허용 안(11)
        assertThat(lineState(l)).isEqualTo("DONE/Y/11/2");
        assertThat(code(() -> ship(comp, l, "0.000001"))).isEqualTo(HbErrorCode.OVER_SHIP);
    }

    @Test
    void 취소_행과_사람이_닫은_행은_409_ORDER_LINE_CLOSED_한_행이라도_막히면_전부_되돌린다() throws Exception {
        long o = order("10", "10", "10");
        long open = line(o, 10);
        long cancelled = line(o, 20);
        long closed = line(o, 30);
        postJson(O + "/lines/" + cancelled + "/cancel", "{\"reason\":\"고객 취소\",\"rowVer\":0}").andExpect(status().isOk());
        postJson(O + "/lines/" + closed + "/close", "{\"rowVer\":0}").andExpect(status().isOk());
        assertThat(code(() -> ship(comp, open, "1", cancelled, "1"))).isEqualTo(HbErrorCode.ORDER_LINE_CLOSED);
        assertThat(code(() -> ship(comp, closed, "1"))).isEqualTo(HbErrorCode.ORDER_LINE_CLOSED);
        assertThat(lineState(open)).isEqualTo("OPEN/N/0/0");
        // 없는 행·0 수량·출하보다 큰 취소
        assertThat(code(() -> ship(comp, 999_999_999L, "1"))).isEqualTo(HbErrorCode.NOT_FOUND);
        assertThat(catchThrowable(() -> ship(comp, open, "0"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> ship(comp, open, "-1"))).isInstanceOf(IllegalArgumentException.class);
        // 트랜잭션 밖에서는 부를 수 없다(MANDATORY)
        assertThat(catchThrowable(() -> gate.ship(List.of(new ShipQty(open, BigDecimal.ONE)))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void 출하_취소는_자동_마감_행과_머리를_다시_열고_사람이_닫은_행은_닫힌_채_수량만_돌린다() throws Exception {
        long o = order("10", "10");
        long auto = line(o, 10);
        long manual = line(o, 20);
        ship(comp, manual, "4");
        postJson(O + "/lines/" + manual + "/close", "{\"rowVer\":1}").andExpect(status().isOk());   // 부분 출하 뒤 짧게 닫기
        ship(comp, auto, "10");
        assertThat(head(o)).isEqualTo("CLOSED");
        ship(comp, auto, "-3", manual, "-4");
        assertThat(lineState(auto)).isEqualTo("OPEN/N/7/2");
        assertThat(lineState(manual)).isEqualTo("DONE/N/0/3");
        assertThat(head(o)).isEqualTo("OPEN");
        // 다시 나가면 다시 자동 마감
        ship(comp, auto, "3");
        assertThat(lineState(auto)).isEqualTo("DONE/Y/10/3");
        assertThat(head(o)).isEqualTo("CLOSED");
        // 사람 마감만 남은 수주의 되돌림은 머리를 열지 않는다
        long o2 = order("10");
        long m2 = line(o2, 10);
        ship(comp, m2, "2");
        postJson(O + "/lines/" + m2 + "/close", "{\"rowVer\":1}").andExpect(status().isOk());
        ship(comp, m2, "-2");
        assertThat(lineState(m2)).isEqualTo("DONE/N/0/3");
        assertThat(head(o2)).isEqualTo("CLOSED");
    }

    // ---------- 영업 규칙 (F) · 직접 SQL ----------

    @Test
    void 출하_아래로_수량_줄이기_출하_행_취소_수주_취소는_화면에서_막힌다_사람_마감은_된다() throws Exception {
        long o = order("10", "10");
        long l = line(o, 10);
        ship(comp, l, "6");
        mvc.perform(json(put(O + "/lines/" + l), "{\"qty\":\"5\",\"dueDate\":\"2026-11-30\",\"rowVer\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("qty"))
                .andExpect(jsonPath("$.errors[0].code").value("BELOW_SHIPPED"));
        mvc.perform(json(put(O + "/lines/" + l), "{\"qty\":\"6\",\"dueDate\":\"2026-11-30\",\"rowVer\":1}"))
                .andExpect(status().isOk());                                // 출하 수량과 같게는 된다(자동 마감은 출하 때만)
        postJson(O + "/lines/" + l + "/cancel", "{\"reason\":\"취소\",\"rowVer\":2}")
                .andExpect(jsonPath("$.errors[0].code").value("SHIPPED"));
        postJson(O + "/" + o + "/cancel", "{\"reason\":\"취소\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[0].code").value("SHIPPED"));
        assertThat(lineState(line(o, 20))).isEqualTo("OPEN/N/0/0");         // 수주 취소가 다른 행도 건드리지 않았다
        postJson(O + "/lines/" + l + "/close", "{\"rowVer\":2}").andExpect(status().isOk());
        assertThat(lineState(l)).isEqualTo("DONE/N/6/3");
    }

    @Test
    void 직접_SQL_로도_출하_규칙과_끝난_행_머리_가드는_그대로_행_삭제는_권한_없음() throws Exception {
        long o = order("10", "10");
        long l = line(o, 10);
        long l2 = line(o, 20);
        ship(comp, l, "4");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update sal_order_line set qty = 3 where line_id = ?", l)))).contains("ck_sal_order_line_qty_shipped");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update sal_order_line set status = 'CANCELLED', cancel_reason = 'x' where line_id = ?", l))))
                .contains("ck_sal_order_line_cancel_shipped");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update sal_order_line set shipped_qty = -1 where line_id = ?", l)))).contains("ck_sal_order_line_shipped");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update sal_order_line set done_by_ship_yn = 'Y' where line_id = ?", l)))).contains("ck_sal_order_line_done_by_ship");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into sal_order_line (comp_cd, order_id, line_no, item_id, qty, due_date, shipped_qty, created_by, updated_by)
                select comp_cd, order_id, 90, item_id, 5, due_date, 1, 't', 't' from sal_order_line where line_id = ?""", l))))
                .contains("ck_sal_order_line_status_new");
        // 사람이 닫은 행: 다시 열기 금지, 수량·사유 금지 / 열린 행이 없는 머리는 CLOSED → OPEN 금지
        postJson(O + "/lines/" + l + "/close", "{\"rowVer\":1}").andExpect(status().isOk());
        postJson(O + "/lines/" + l2 + "/close", "{\"rowVer\":0}").andExpect(status().isOk());
        assertThat(head(o)).isEqualTo("CLOSED");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update sal_order_line set status = 'OPEN' where line_id = ?", l)))).contains("ck_sal_order_line_closed");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update sal_order_line set qty = 20, shipped_qty = 5 where line_id = ?", l)))).contains("ck_sal_order_line_closed");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update sal_order set status = 'OPEN' where order_id = ?", o)))).contains("ck_sal_order_closed");
        // 행 삭제는 앱 계정에 권한이 없다(출하 투영이 있는 행을 지워 원장과 어긋나게 할 수 없다)
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from sal_order_line where line_id = ?", l))))
                .contains("42501");
    }

    @Test
    void 수주_행이_있는_품목은_기본_단위를_바꿀_수_없다() throws Exception {
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG-FREE', '쓰지 않은 품목', 'FG', 'MAKE', 'EA', 't', 't')""", comp);
        order("10");                                                        // FG-100 은 수주 행에만 쓰였다(BOM·작업지시·재고 없음)
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_item set unit_cd = 'KG' where item_cd = 'FG-100'")))).contains("fk_mst_item_unit_in_use");
        accounts.exec(comp, "update mst_item set unit_cd = 'KG' where item_cd = 'FG-FREE'");
    }

    // ---------- 동시성 (C) ----------

    @Test
    void 두_수주의_행을_엇갈린_순서로_동시에_출하해도_교착_없이_합계가_맞다() throws Exception {
        long a = order("100");
        long b = order("100");
        long la = line(a, 10);
        long lb = line(b, 10);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                Future<?> f1 = pool.submit(() -> ship(comp, la, "1", lb, "1"));
                Future<?> f2 = pool.submit(() -> ship(comp, lb, "1", la, "1"));
                f1.get();
                f2.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(lineState(la)).isEqualTo("OPEN/N/10/10");
        assertThat(lineState(lb)).isEqualTo("OPEN/N/10/10");
    }

    // ---------- 회사 격리 (A사로 B사) ----------

    @Test
    void B사는_A사_수주_행을_출하할_수_없고_직접_SQL_로도_못_바꾼다() throws Exception {
        long o = order("10");
        long l = line(o, 10);
        String other = accounts.newComp("SH");
        base(other);
        assertThat(code(() -> ship(other, l, "1"))).isEqualTo(HbErrorCode.NOT_FOUND);
        assertThat(accounts.exec(other, "update sal_order_line set shipped_qty = 1 where line_id = ?", l)).isZero();
        assertThat(lineState(l)).isEqualTo("OPEN/N/0/0");
    }

    @Test
    void 영업_없는_설치본은_NONE() {
        OrderShipGate none = OrderShipGate.of(new DefaultListableBeanFactory().getBeanProvider(OrderShipGate.class));
        assertThat(none).isSameAs(OrderShipGate.NONE);
        none.ship(List.of(new ShipQty(1L, BigDecimal.ONE)));
    }
}
