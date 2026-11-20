package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import io.smarthappy.module.production.link.LotTraceQuery;
import io.smarthappy.module.production.link.LotTraceQuery.TraceResult;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * LOT 계보 추적 계약 LotTraceQuery (docs/25 4.49 — WMS 출하 추적 W-5 의 전제). 계보는 직접 SQL(원장 트리거 검사 — 같은 회사·잔량·품목 — 는 그대로 받는다).
 * 계보: RM1 —투입→ FG1 —분할→ FG2·FG3, FG3 + FG4 —병합→ FG5, FG5 —분할→ FG6 —재표시→ FG5? (순환 시험은 따로)
 * 이상 사용: 역분개된 계보 제외, 깊이·줄 상한 잘림 표시(조용히 덜 보이지 않음), 순환, 상한 밖 인자, A사로 B사(RLS), NONE.
 */
class LotTraceQueryTest extends PostgresTestBase {

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectProvider<LotTraceQuery> queries;

    String comp;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("LT");
        items(comp);
    }

    // ---------- 도우미 ----------

    private void items(String c) {
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', 'FG 품목', 'FG', 'MAKE', 'EA', 't', 't'), (?, 'RM', 'RM 품목', 'RM', 'BUY', 'EA', 't', 't')""", c, c);
    }

    private long lot(String c, String no, String itemCd, int qty) {
        return accounts.query(c, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, i.item_id, 'RECEIVED', ?, 'EA', now(), 't', 't' from mst_item i where i.item_cd = ? returning lot_id""",
                Long.class, c, no, qty, itemCd);
    }

    private long gen(String c, long parent, long child, String type, int qty) {
        return accounts.query(c, """
                insert into lot_genealogy (comp_cd, parent_lot_id, child_lot_id, rel_type, qty, unit_cd, occurred_at, entry_method, recorded_by)
                values (?, ?, ?, ?, ?, 'EA', now(), 'KEY', 't') returning gen_id""", Long.class, c, parent, child, type, qty);
    }

    private void reverse(String c, long genId) {
        accounts.exec(c, """
                insert into lot_genealogy (comp_cd, parent_lot_id, child_lot_id, rel_type, qty, unit_cd, occurred_at, entry_method, recorded_by,
                                           reversal_of_id, reason)
                select comp_cd, parent_lot_id, child_lot_id, rel_type, -qty, unit_cd, now(), 'CORRECT', 't', gen_id, '잘못 연결'
                  from lot_genealogy where gen_id = ?""", genId);
    }

    private TraceResult fwd(String c, List<Long> ids, int depth) {
        return runner.call(c, () -> LotTraceQuery.of(queries).forward(ids, depth));
    }

    private TraceResult back(String c, List<Long> ids, int depth) {
        return runner.call(c, () -> LotTraceQuery.of(queries).backward(ids, depth));
    }

    /** 줄 → "루트>LOT@깊이(via,종류)" — id 를 이름으로 */
    private static List<String> show(TraceResult r, java.util.Map<Long, String> names) {
        return r.rows().stream().map(x -> names.get(x.rootLotId()) + ">" + names.get(x.lotId()) + "@" + x.depth()
                + (x.viaLotId() == null ? "" : "(" + names.get(x.viaLotId()) + "," + x.relType() + ")") + (x.cycle() ? "!" : "")).toList();
    }

    // ---------- 시험 ----------

    @Test
    void 투입_분할_병합을_정방향_역방향으로_루트_자신_포함() {
        long rm1 = lot(comp, "RM1", "RM", 10);
        long fg1 = lot(comp, "FG1", "FG", 10);
        long fg2 = lot(comp, "FG2", "FG", 4);
        long fg3 = lot(comp, "FG3", "FG", 6);
        long fg4 = lot(comp, "FG4", "FG", 5);
        long fg5 = lot(comp, "FG5", "FG", 11);
        gen(comp, rm1, fg1, "CONSUME", 10);
        gen(comp, fg1, fg2, "SPLIT", 4);
        gen(comp, fg1, fg3, "SPLIT", 6);
        gen(comp, fg3, fg5, "MERGE", 6);
        gen(comp, fg4, fg5, "MERGE", 5);
        var n = java.util.Map.of(rm1, "RM1", fg1, "FG1", fg2, "FG2", fg3, "FG3", fg4, "FG4", fg5, "FG5");
        TraceResult f = fwd(comp, List.of(rm1), 10);
        assertThat(f.truncated()).isFalse();
        assertThat(show(f, n)).containsExactly("RM1>RM1@0", "RM1>FG1@1(RM1,CONSUME)", "RM1>FG2@2(FG1,SPLIT)", "RM1>FG3@2(FG1,SPLIT)",
                "RM1>FG5@3(FG3,MERGE)");
        TraceResult b = back(comp, List.of(fg5), 10);
        assertThat(show(b, n)).containsExactly("FG5>FG5@0", "FG5>FG3@1(FG5,MERGE)", "FG5>FG1@2(FG3,SPLIT)", "FG5>RM1@3(FG1,CONSUME)",
                "FG5>FG4@1(FG5,MERGE)");
        // 여러 루트 — 루트 id 순, 중복·null 은 한 번·무시, 없는 id 는 루트가 안 됨
        List<Long> ids = new ArrayList<>(Arrays.asList(fg4, rm1, fg4, null, 999_999_999L));
        assertThat(show(fwd(comp, ids, 1), n)).containsExactly("RM1>RM1@0", "RM1>FG1@1(RM1,CONSUME)", "FG4>FG4@0", "FG4>FG5@1(FG4,MERGE)");
        // 깊이 0 = 루트만, 계보가 이어지니 잘림 표시
        TraceResult zero = fwd(comp, List.of(rm1), 0);
        assertThat(show(zero, n)).containsExactly("RM1>RM1@0");
        assertThat(zero.truncated()).isTrue();
        // 끝 LOT 은 깊이 0 이어도 잘림 아님
        assertThat(fwd(comp, List.of(fg2), 0).truncated()).isFalse();
    }

    @Test
    void 역분개된_계보는_빠진다() {
        long a = lot(comp, "A", "FG", 10);
        long b = lot(comp, "B", "FG", 5);
        long c = lot(comp, "C", "FG", 5);
        long ab = gen(comp, a, b, "SPLIT", 5);
        gen(comp, a, c, "SPLIT", 5);
        reverse(comp, ab);
        var n = java.util.Map.of(a, "A", b, "B", c, "C");
        assertThat(show(fwd(comp, List.of(a), 10), n)).containsExactly("A>A@0", "A>C@1(A,SPLIT)");
        assertThat(show(back(comp, List.of(b), 10), n)).containsExactly("B>B@0");
    }

    @Test
    void 깊이_상한에_닿으면_잘림_표시_닿지_않으면_아님() {
        long[] chain = LongStream.range(0, 6).map(i -> lot(comp, "C" + i, "FG", 10)).toArray();
        for (int i = 0; i < 5; i++) {
            gen(comp, chain[i], chain[i + 1], "RELABEL", 10);
        }
        TraceResult cut = fwd(comp, List.of(chain[0]), 3);
        assertThat(cut.rows()).hasSize(4);
        assertThat(cut.truncated()).isTrue();
        TraceResult full = fwd(comp, List.of(chain[0]), 5);
        assertThat(full.rows()).hasSize(6);
        assertThat(full.truncated()).isFalse();
    }

    @Test
    void 줄_상한을_넘으면_MAX_ROWS_까지와_잘림_표시() {
        // 루트 1 → 자식 5,000 (줄 = 5,001)
        long root = lot(comp, "ROOT", "FG", 5000);
        runner.run(comp, () -> {
            jdbc.update("""
                    insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                    select ?, 'K-' || g, i.item_id, 'RECEIVED', 1, 'EA', now(), 't', 't'
                      from mst_item i cross join generate_series(1, 5000) g where i.item_cd = 'FG'""", comp);
            jdbc.update("""
                    insert into lot_genealogy (comp_cd, parent_lot_id, child_lot_id, rel_type, qty, unit_cd, occurred_at, entry_method, recorded_by)
                    select ?, ?, m.lot_id, 'SPLIT', 1, 'EA', now(), 'KEY', 't' from lot_master m where m.lot_no like 'K-%'""", comp, root);
        });
        TraceResult r = fwd(comp, List.of(root), 1);
        assertThat(r.rows()).hasSize(LotTraceQuery.MAX_ROWS);
        assertThat(r.truncated()).isTrue();
    }

    @Test
    void 순환은_표시하고_멈춘다() {
        long a = lot(comp, "A", "FG", 10);
        long b = lot(comp, "B", "FG", 10);
        gen(comp, a, b, "RELABEL", 5);
        gen(comp, b, a, "SORT", 5);
        var n = java.util.Map.of(a, "A", b, "B");
        TraceResult r = fwd(comp, List.of(a), 10);
        assertThat(show(r, n)).containsExactly("A>A@0", "A>B@1(A,RELABEL)", "A>A@2(B,SORT)!");
        assertThat(r.truncated()).isFalse();
    }

    @Test
    void A사로_B사_LOT_은_루트가_안_되고_계보도_안_보인다() {
        long a = lot(comp, "A", "FG", 10);
        String other = accounts.newComp("LTB");
        items(other);
        long x = lot(other, "X", "FG", 10);
        long y = lot(other, "Y", "FG", 10);
        gen(other, x, y, "SPLIT", 3);
        assertThat(fwd(comp, List.of(x), 5).rows()).isEmpty();
        assertThat(fwd(comp, List.of(a, x), 5).rows()).extracting(r -> r.lotId()).containsExactly(a);
        assertThat(fwd(other, List.of(x), 5).rows()).hasSize(2);
    }

    @Test
    void NONE_과_상한_밖_인자() {
        LotTraceQuery none = LotTraceQuery.of(new StaticListableBeanFactory().getBeanProvider(LotTraceQuery.class));
        assertThat(none).isSameAs(LotTraceQuery.NONE);
        assertThat(none.forward(List.of(1L), 3)).isEqualTo(TraceResult.EMPTY);
        assertThat(none.backward(List.of(1L), 3).rows()).isEmpty();
        assertThat(fwd(comp, List.of(), 3)).isEqualTo(TraceResult.EMPTY);
        assertThat(fwd(comp, null, 3)).isEqualTo(TraceResult.EMPTY);
        List<Long> many = LongStream.rangeClosed(1, LotTraceQuery.MAX_IDS + 1).boxed().toList();
        assertThatThrownBy(() -> fwd(comp, many, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fwd(comp, List.of(1L), LotTraceQuery.MAX_DEPTH + 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> back(comp, List.of(1L), -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(fwd(comp, LongStream.rangeClosed(1, LotTraceQuery.MAX_IDS).boxed().toList(), 3).rows()).isEmpty();
    }
}
