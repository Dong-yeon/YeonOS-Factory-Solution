package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import io.smarthappy.module.production.link.LotLookup;
import io.smarthappy.module.production.link.LotLookup.LotOrigin;
import io.smarthappy.module.production.link.LotLookup.LotView;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * LOT 조회 계약 LotLookup (docs/25 4.21, QMS Q9 R-2·PMS PM7) — 여러 LOT(순서·중복·없는 id·상한), 번호로(없으면 empty — 예외 없이,
 * 부르는 쪽 트랜잭션이 rollback-only 가 되지 않음), A사로 B사, NONE.
 */
class LotLookupTest extends PostgresTestBase {

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    LotService lots;

    @Autowired
    LotLookup lookup;

    String comp;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("LK");
        item(comp);
    }

    private void item(String c) {
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'RM', 'RM 자재', 'RM', 'BUY', 'KG', 't', 't')""", c);
    }

    private long receive(String c) {
        return runner.call(c, () -> lots.receiveAs("RM", BigDecimal.ONE, null, null, "t"));
    }

    private String lotNo(long id) {
        return accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, id);
    }

    @Test
    void 여러_LOT_은_lot_id_순_중복_null_없는_id_는_빠지고_상한() {
        long a = receive(comp);
        long b = receive(comp);
        List<LotView> v = runner.call(comp, () -> lookup.lots(Arrays.asList(b, a, b, null, 999_999_999L)));
        assertThat(v).extracting(LotView::lotId).containsExactly(a, b);
        assertThat(v.get(0).lotNo()).isEqualTo(lotNo(a));
        assertThat(v.get(0).itemCd()).isEqualTo("RM");
        assertThat(v.get(0).itemNm()).isEqualTo("RM 자재");
        assertThat(v.get(0).woId()).isNull();
        assertThat(v.get(0).occurredAt()).isNotNull();
        assertThat(runner.call(comp, () -> lookup.lots(List.of()))).isEmpty();
        assertThat(runner.call(comp, () -> lookup.lots(null))).isEmpty();
        List<Long> many = LongStream.rangeClosed(1, LotLookup.MAX_IDS + 1).boxed().toList();
        assertThat(catchThrowable(() -> runner.call(comp, () -> lookup.lots(many)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 번호로는_없으면_empty_예외가_없어_부르는_쪽_트랜잭션이_그대로_커밋된다() {
        long a = receive(comp);
        String no = lotNo(a);
        assertThat(runner.call(comp, () -> lookup.byNo("  " + no + " "))).get().extracting(LotView::lotId).isEqualTo(a);
        for (String bad : new String[] {null, "", "   ", "NOPE-1", "x".repeat(61)}) {
            assertThat(runner.call(comp, () -> lookup.byNo(bad))).as(String.valueOf(bad)).isEmpty();
        }
        // 없는 번호를 찾은 뒤 같은 트랜잭션에서 쓰기 → 커밋된다(전에는 LotService.byNo 404 가 rollback-only 를 남겼다)
        long b = runner.call(comp, () -> {
            assertThat(lookup.byNo("NOPE-2")).isEmpty();
            return lots.receiveAs("RM", BigDecimal.TEN, null, null, "t");
        });
        assertThat(accounts.query(comp, "select count(*) from lot_master where lot_id = ?", Integer.class, b)).isEqualTo(1);
    }

    @Test
    void A사로_B사_LOT_은_빠지거나_empty() {
        long a = receive(comp);
        String no = lotNo(a);
        String other = accounts.newComp("LKB");
        item(other);
        assertThat(runner.call(other, () -> lookup.lots(List.of(a)))).isEmpty();
        assertThat(runner.call(other, () -> lookup.byNo(no))).isEmpty();
    }

    /** 지시·생산 LOT — 트리거 끄고(owner·replica) 바로 넣는다 */
    private long owner(String sql, Object... args) throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword())) {
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("set session_replication_role = replica");
            }
            try (java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }
    }

    private long wo(String c, String no) throws Exception {
        return owner("insert into prd_wo (comp_cd, wo_no, plant_cd, item_id, plan_qty, unit_cd, plan_start, plan_end, routing_id, status, "
                + "released_at, created_by, updated_by) select ?, ?, 'P1', item_id, 100, 'KG', " + TestAccounts.COMP_TODAY + ", "
                + TestAccounts.COMP_TODAY + ", 0, 'RELEASED', now(), 't', 't' from mst_item where comp_cd = ? and item_cd = 'RM' returning wo_id", c, no, c);
    }

    private long produced(String c, String no, long wo) throws Exception {
        return owner("insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, wo_id, occurred_at, created_by, updated_by) "
                + "select ?, ?, item_id, 'PRODUCED', 10, 'KG', ?, now(), 't', 't' from mst_item where comp_cd = ? and item_cd = 'RM' returning lot_id",
                c, no, wo, c);
    }

    @Test
    void 지시의_LOT_은_lot_id_순_다른_지시_입고_LOT_은_빠지고_상한은_1_500_A사로_B사_빈목록() throws Exception {
        long w1 = wo(comp, "WO-LK-1");
        long w2 = wo(comp, "WO-LK-2");
        long b = produced(comp, "L-LK-B", w1);
        long a = produced(comp, "L-LK-A", w1);
        produced(comp, "L-LK-C", w2);
        receive(comp);                                    // 입고 LOT(지시 없음)
        List<LotView> v = runner.call(comp, () -> lookup.lotsOfWo(w1, 10));
        assertThat(v).extracting(LotView::lotId).containsExactly(Math.min(a, b), Math.max(a, b));
        assertThat(v).extracting(LotView::woId).containsOnly(w1);
        // 상한: 1 미만은 1, 넘는 LOT 은 빠짐(받은 수 = limit 이면 잘렸을 수 있음), 큰 값은 MAX_IDS 로
        assertThat(runner.call(comp, () -> lookup.lotsOfWo(w1, 1))).hasSize(1);
        assertThat(runner.call(comp, () -> lookup.lotsOfWo(w1, 0))).hasSize(1);
        assertThat(runner.call(comp, () -> lookup.lotsOfWo(w1, 99_999))).hasSize(2);
        assertThat(LotLookup.clampLimit(99_999)).isEqualTo(LotLookup.MAX_IDS);
        assertThat(runner.call(comp, () -> lookup.lotsOfWo(999_999_999L, 10))).isEmpty();
        // B사 범위에서 A사 지시 id → 빈 목록, NONE 은 늘 빈 목록
        String other = accounts.newComp("LKB");
        item(other);
        assertThat(runner.call(other, () -> lookup.lotsOfWo(w1, 10))).isEmpty();
        assertThat(LotLookup.NONE.lotsOfWo(w1, 10)).isEmpty();
    }

    @Test
    void 출처는_입고_LOT_만_공급사가_있고_생산_LOT_은_비며_상한_A사로_B사_빈목록() throws Exception {
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'S1', 'S1 공급사', 'PURCHASE', 't', 't')",
                comp);
        long r = runner.call(comp, () -> lots.receiveAs("RM", BigDecimal.ONE, "S1", "SUP-LOT-9", "t"));
        long bare = receive(comp);                         // 공급사 없이 입고
        long p = produced(comp, "L-OR-P", wo(comp, "WO-OR-1"));
        List<LotOrigin> o = runner.call(comp, () -> lookup.origins(Arrays.asList(p, r, bare, r, null, 999_999_999L)));
        assertThat(o).extracting(LotOrigin::lotId).containsExactly(r, bare, p);
        assertThat(o.get(0)).extracting(LotOrigin::origin, LotOrigin::supplierCd, LotOrigin::supplierNm, LotOrigin::supplierLotNo)
                .containsExactly("RECEIVED", "S1", "S1 공급사", "SUP-LOT-9");
        assertThat(o.get(0).supplierCustId()).isNotNull();
        assertThat(o.get(1)).extracting(LotOrigin::origin, LotOrigin::supplierCustId, LotOrigin::supplierCd).containsExactly("RECEIVED", null, null);
        assertThat(o.get(2)).extracting(LotOrigin::origin, LotOrigin::supplierCustId).containsExactly("PRODUCED", null);
        assertThat(runner.call(comp, () -> lookup.origins(null))).isEmpty();
        List<Long> many = LongStream.rangeClosed(1, LotLookup.MAX_IDS + 1).boxed().toList();
        assertThat(catchThrowable(() -> runner.call(comp, () -> lookup.origins(many)))).isInstanceOf(IllegalArgumentException.class);
        String other = accounts.newComp("LKO");
        assertThat(runner.call(other, () -> lookup.origins(List.of(r, p)))).isEmpty();
        assertThat(LotLookup.NONE.origins(List.of(r))).isEmpty();
    }

    @Test
    void MES_없는_설치본은_NONE() {
        LotLookup none = LotLookup.of(new DefaultListableBeanFactory().getBeanProvider(LotLookup.class));
        assertThat(none).isSameAs(LotLookup.NONE);
        assertThat(none.lots(List.of(1L))).isEmpty();
        assertThat(none.byNo("L-1")).isEmpty();
    }
}
