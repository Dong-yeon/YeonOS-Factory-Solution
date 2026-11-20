package io.smarthappy.module.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import io.smarthappy.module.sales.link.OrderLineQuery;
import io.smarthappy.module.sales.link.OrderLineQuery.OrderLineView;
import io.smarthappy.module.sales.link.OrderShipGate;
import io.smarthappy.module.sales.link.OrderShipGate.ShipQty;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 수주 행 읽기 계약 OrderLineQuery (docs/25 4.23, WMS B6a) — 거래처의 열린 행(납기 → 수주번호 → 행번호, 끝난 행·다른 거래처 빠짐),
 * 여러 행(상태 무관·line_id 순·중복·null·없는 id·상한), 출하 투영 값, 잠그지 않음, A사로 B사, NONE.
 */
class OrderLineQueryTest extends PostgresTestBase {

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    OrderLineQuery query;

    @Autowired
    OrderShipGate gate;

    String comp;
    long cust;
    long other;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("OQ");
        base(comp);
        cust = custId(comp, "C-001");
        other = custId(comp, "C-002");
    }

    private void base(String c) {
        accounts.exec(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C-001', 'C-001 상사', 'SALES', 't', 't'), (?, 'C-002', 'C-002 상사', 'BOTH', 't', 't')""", c, c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG-100', 'FG 품목', 'FG', 'MAKE', 'EA', 't', 't')""", c);
    }

    private long custId(String c, String cd) {
        return accounts.query(c, "select cust_id from mst_cust where cust_cd = ?", Long.class, cd);
    }

    private long order(String c, String no, long custId, String po) {
        return accounts.query(c, """
                insert into sal_order (comp_cd, order_no, cust_id, cust_po_no, order_date, created_by, updated_by)
                values (?, ?, ?, ?, date '2026-10-01', 't', 't') returning order_id""", Long.class, c, no, custId, po);
    }

    private long line(String c, long orderId, int no, String qty, String due) {
        return accounts.query(c, """
                insert into sal_order_line (comp_cd, order_id, line_no, item_id, qty, due_date, created_by, updated_by)
                select ?, ?, ?, item_id, ?, ?, 't', 't' from mst_item where item_cd = 'FG-100' returning line_id""", Long.class,
                c, orderId, no, new BigDecimal(qty), LocalDate.parse(due));
    }

    private void ship(long lineId, String qty) {
        runner.call(comp, () -> {
            gate.ship(List.of(new ShipQty(lineId, new BigDecimal(qty))));
            return null;
        });
    }

    @Test
    void 열린_행은_그_거래처만_납기_수주번호_행번호_순_끝난_행은_빠진다() {
        long o1 = order(comp, "SO-1", cust, "PO-1");
        long o2 = order(comp, "SO-2", cust, null);
        long a = line(comp, o1, 10, "10", "2026-11-30");
        long b = line(comp, o1, 20, "5", "2026-11-10");
        long c = line(comp, o2, 10, "7", "2026-11-10");
        long done = line(comp, o2, 20, "3", "2026-11-01");
        long cancelled = line(comp, o2, 30, "3", "2026-11-01");
        line(comp, order(comp, "SO-3", other, null), 10, "1", "2026-11-01");    // 다른 거래처
        ship(a, "4");
        ship(done, "3");                                                       // 출하로 자동 마감
        accounts.exec(comp, "update sal_order_line set status = 'CANCELLED', cancel_reason = '취소' where line_id = ?", cancelled);

        List<OrderLineView> v = runner.call(comp, () -> query.openLines(cust));
        assertThat(v).extracting(OrderLineView::lineId).containsExactly(b, c, a);
        OrderLineView last = v.get(2);
        assertThat(last.orderNo()).isEqualTo("SO-1");
        assertThat(last.lineNo()).isEqualTo(10);
        assertThat(last.custId()).isEqualTo(cust);
        assertThat(last.custPoNo()).isEqualTo("PO-1");
        assertThat(last.itemCd()).isEqualTo("FG-100");
        assertThat(last.itemNm()).isEqualTo("FG 품목");
        assertThat(last.unitCd()).isEqualTo("EA");
        assertThat(last.dueDate()).isEqualTo(LocalDate.parse("2026-11-30"));
        assertThat(last.qty()).isEqualByComparingTo("10");
        assertThat(last.shippedQty()).isEqualByComparingTo("4");
        assertThat(last.status()).isEqualTo("OPEN");
        assertThat(last.doneByShipYn()).isEqualTo("N");
        assertThat(v.get(1).custPoNo()).isNull();
        // 없는 거래처·열린 행 없는 거래처
        assertThat(runner.call(comp, () -> query.openLines(999_999_999L))).isEmpty();
        accounts.exec(comp, "update sal_order_line set status = 'CANCELLED', cancel_reason = 'x' where order_id = (select order_id from sal_order where order_no = 'SO-3')");
        assertThat(runner.call(comp, () -> query.openLines(other))).isEmpty();
    }

    @Test
    void 여러_행은_상태_무관_line_id_순_중복_null_없는_id_는_빠지고_상한() {
        long o = order(comp, "SO-1", cust, null);
        long a = line(comp, o, 10, "3", "2026-11-30");
        long b = line(comp, o, 20, "5", "2026-11-30");
        ship(a, "3");
        List<OrderLineView> v = runner.call(comp, () -> query.lines(Arrays.asList(b, a, b, null, 999_999_999L)));
        assertThat(v).extracting(OrderLineView::lineId).containsExactly(a, b);
        assertThat(v.get(0).status()).isEqualTo("DONE");
        assertThat(v.get(0).doneByShipYn()).isEqualTo("Y");
        assertThat(v.get(0).shippedQty()).isEqualByComparingTo("3");
        assertThat(runner.call(comp, () -> query.lines(List.of()))).isEmpty();
        assertThat(runner.call(comp, () -> query.lines(null))).isEmpty();
        List<Long> many = LongStream.rangeClosed(1, OrderLineQuery.MAX_IDS + 1).boxed().toList();
        assertThat(catchThrowable(() -> runner.call(comp, () -> query.lines(many)))).isInstanceOf(IllegalArgumentException.class);
        // 상한 딱 500 은 된다
        List<Long> max = LongStream.rangeClosed(1, OrderLineQuery.MAX_IDS).boxed().toList();
        assertThat(catchThrowable(() -> runner.call(comp, () -> query.lines(max)))).isNull();
    }

    @Test
    void 읽은_트랜잭션이_열려_있어도_다른_트랜잭션의_출하는_기다리지_않는다() {
        long a = line(comp, order(comp, "SO-1", cust, null), 10, "10", "2026-11-30");
        runner.call(comp, () -> {
            assertThat(query.openLines(cust)).hasSize(1);
            assertThat(query.lines(List.of(a))).hasSize(1);
            // 같은 행을 다른 트랜잭션이 바로 바꿀 수 있다(FOR UPDATE·FOR SHARE 없음)
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> ship(a, "1"));
            try {
                f.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
        assertThat(runner.call(comp, () -> query.lines(List.of(a))).get(0).shippedQty()).isEqualByComparingTo("1");
    }

    @Test
    void B사_범위에서는_A사_거래처의_열린_행도_A사_행_id_도_보이지_않는다() {
        long a = line(comp, order(comp, "SO-1", cust, null), 10, "10", "2026-11-30");
        String b = accounts.newComp("OR");
        base(b);
        line(b, order(b, "SO-1", custId(b, "C-001"), null), 10, "1", "2026-11-30");
        assertThat(runner.call(b, () -> query.openLines(cust))).isEmpty();
        assertThat(runner.call(b, () -> query.lines(List.of(a)))).isEmpty();
        assertThat(runner.call(comp, () -> query.openLines(cust))).extracting(OrderLineView::lineId).containsExactly(a);
    }

    @Test
    void 영업_없는_설치본은_NONE() {
        OrderLineQuery none = OrderLineQuery.of(new DefaultListableBeanFactory().getBeanProvider(OrderLineQuery.class));
        assertThat(none).isSameAs(OrderLineQuery.NONE);
        assertThat(none.openLines(1L)).isEmpty();
        assertThat(none.lines(List.of(1L))).isEmpty();
    }
}
