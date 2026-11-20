package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 깊은 offset 실측 (docs/qa/2026-10-24_offset-deep-page.md H1·M2, docs/25 4.41) — 평소 건너뜀: -Dhb.perf=true.
 * LOT grid(lot_master + 행마다 hb_lot_balance)와 입고 대상 grid(prd_wo + 행마다 hb_proc_qty·입고 합)를 앞·중간·끝 쪽에서 잰다.
 * 크기: 환경 변수 HB_PERF_ROWS(기본 100000 — 시험 JVM 은 Gradle 의 -D 를 hb.perf 만 넘기고 환경 변수는 물려받는다).
 * 결과는 [perf] 줄로 남긴다(문서 표의 근거).
 */
@EnabledIfSystemProperty(named = "hb.perf", matches = "true")
class MesOffsetPerfTest extends PostgresTestBase {

    private static final int SIZE = 50;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LotService lots;

    @Autowired
    ReceiptService receipts;

    private static int rows() {
        String v = System.getenv("HB_PERF_ROWS");
        return v == null || v.isBlank() ? 100_000 : Integer.parseInt(v.trim());
    }

    /** 소유자 연결로 통계 갱신 — 큰 표를 막 채운 직후 계획이 통계 없이 잡히지 않게 */
    private static void analyze(String... tables) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement s = c.createStatement()) {
            for (String t : tables) {
                s.execute("analyze " + t);
            }
        }
    }

    private static long bestMs(Supplier<?> call) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long t0 = System.nanoTime();
            call.get();
            best = Math.min(best, System.nanoTime() - t0);
        }
        return best / 1_000_000;
    }

    private String seedMaster() {
        String comp = accounts.newComp("OP");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'FG', 'FG 품목', 'FG', 'MAKE', 'EA', 'Y', 't', 't')""", comp);
        accounts.exec(comp, "insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by) values (?, 'ASSY', '조립', 'ASSEMBLY', 't', 't')", comp);
        long rid = accounts.query(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 900, 't', 't')
                returning routing_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'ASSY'), 't', 't')""", comp, rid);
        accounts.exec(comp, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        return comp;
    }

    @Test
    void LOT_grid_앞_중간_끝_보류만() throws Exception {
        String comp = seedMaster();
        int n = rows();
        long t0 = System.nanoTime();
        // LOT n개(등록 시각 1초 간격), 10% 는 다음 LOT 으로 소비 계보(잔량 계산이 실제로 합을 하게), 0.5% 보류
        runner.run(comp, () -> {
            jdbc.execute("set local statement_timeout = 0");   // 채우기만(재는 조회는 앱 시간 상한 그대로)
            jdbc.update("""
                    insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_at, created_by, updated_by)
                    select ?, 'PL-' || lpad(g::text, 8, '0'), i.item_id, 'RECEIVED', 10, 'EA', now(), now() - make_interval(secs => g), 't', 't'
                      from mst_item i cross join generate_series(1, ?) g where i.item_cd = 'FG'""", comp, n);
            jdbc.update("""
                    insert into lot_genealogy (comp_cd, parent_lot_id, child_lot_id, rel_type, qty, unit_cd, occurred_at, entry_method, recorded_by)
                    select m.comp_cd, m.lot_id, c.lot_id, 'CONSUME', 3, 'EA', now(), 'KEY', 't'
                      from lot_master m join lot_master c on c.lot_no = 'PL-' || lpad((substr(m.lot_no, 4)::int + 1)::text, 8, '0')
                     where substr(m.lot_no, 4)::int % 10 = 0 and substr(m.lot_no, 4)::int < ?""", n);
            jdbc.update("""
                    insert into lot_hold (comp_cd, lot_id, source, reason, held_by)
                    select comp_cd, lot_id, 'MANUAL', '실측', 't' from lot_master where substr(lot_no, 4)::int % 200 = 7""");
        });
        analyze("lot_master", "lot_genealogy", "lot_hold");
        long seeded = (System.nanoTime() - t0) / 1_000_000;
        int last = (n + SIZE - 1) / SIZE;
        StringBuilder sb = new StringBuilder("LOT rows=" + n + " seed=" + seeded + "ms");
        for (int[] p : new int[][] {{1}, {last / 2}, {last}}) {
            long ms = bestMs(() -> runner.call(comp, () -> lots.grid(null, null, null, null, null, null, p[0], SIZE, null, false)));
            sb.append(" | page ").append(p[0]).append(" ").append(ms).append("ms");
        }
        long item = bestMs(() -> runner.call(comp, () -> lots.grid(null, null, null, null, null, null, last / 2, SIZE,
                List.of("itemCd,asc"), false)));
        long hold = bestMs(() -> runner.call(comp, () -> lots.grid(null, null, null, null, null, "Y", 1, SIZE, null, true)));
        long count = bestMs(() -> runner.call(comp, () -> lots.grid(null, null, null, null, null, null, 1, SIZE, null, true)));
        sb.append(" | itemCd sort mid ").append(item).append("ms | holdOnly+total ").append(hold).append("ms | page1+total ").append(count).append("ms");
        System.out.println("[perf] " + sb);
        // 쪽 내용이 맞는지(가운데 쪽 = 그 순번의 LOT)
        var mid = runner.call(comp, () -> lots.grid(null, null, null, null, null, null, last / 2, SIZE, null, false));
        assertThat(mid.list()).hasSize(SIZE);
        assertThat(mid.list().get(0).getLotNo()).isEqualTo("PL-" + String.format("%08d", (last / 2 - 1) * SIZE + 1));
    }

    @Test
    void 입고_대상_grid_앞_중간_끝() throws Exception {
        String comp = seedMaster();
        int n = rows();
        long t0 = System.nanoTime();
        // 지시 n개를 한 트랜잭션에서 계획 → 발행(released_at = now(), 공정 스냅샷 1행) → 진행 중
        runner.run(comp, () -> {
            jdbc.execute("set local statement_timeout = 0");
            jdbc.update("""
                    insert into prd_wo (comp_cd, wo_no, plant_cd, item_id, plan_qty, unit_cd, plan_start, plan_end, routing_id, priority,
                                        created_by, updated_by)
                    select ?, 'PW-' || lpad(g::text, 8, '0'), 'P1', i.item_id, 10, 'EA', current_date - (g % 300), current_date - (g % 300),
                           r.routing_id, 5, 't', 't'
                      from mst_item i join mst_routing r on r.item_id = i.item_id cross join generate_series(1, ?) g
                     where i.item_cd = 'FG'""", comp, n);
            jdbc.update("update prd_wo set status = 'RELEASED', released_at = now(), released_by = 't'");
            jdbc.update("""
                    insert into prd_wo_proc (comp_cd, wo_id, op_seq, proc_id, proc_cd, proc_nm, units_per_cycle, setup_min, src_step_id,
                                             created_by, updated_by)
                    select w.comp_cd, w.wo_id, 10, s.proc_id, 'ASSY', '조립', 1, 0, s.step_id, 't', 't'
                      from prd_wo w join mst_routing_step s on s.routing_id = w.routing_id""");
            jdbc.update("update prd_wo set status = 'IN_PROGRESS'");
        });
        analyze("prd_wo", "prd_wo_proc");
        long seeded = (System.nanoTime() - t0) / 1_000_000;
        int last = (n + SIZE - 1) / SIZE;
        StringBuilder sb = new StringBuilder("RECEIPT rows=" + n + " seed=" + seeded + "ms");
        for (int[] p : new int[][] {{1}, {last / 2}, {last}}) {
            long ms = bestMs(() -> runner.call(comp, () -> receipts.woGrid("P1", null, null, p[0], SIZE, null, false)));
            sb.append(" | page ").append(p[0]).append(" ").append(ms).append("ms");
        }
        long count = bestMs(() -> runner.call(comp, () -> receipts.woGrid("P1", null, null, 1, SIZE, null, true)));
        sb.append(" | page1+total ").append(count).append("ms");
        System.out.println("[perf] " + sb);
        assertThat(runner.call(comp, () -> receipts.woGrid("P1", null, null, last / 2, SIZE, null, false)).list()).hasSize(SIZE);
    }
}
