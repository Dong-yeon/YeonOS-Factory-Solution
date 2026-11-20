package io.smarthappy.module.wms.inv;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 수불 원장(wms-trx) 페이지 방식 실측 — offset(지금) 대 keyset(후보), docs/30 5장 "keyset 은 실측 뒤".
 * 원장 10만·100만 행에서 앞·중간·끝 페이지(50행)의 시간(3번 중 최선)과 EXPLAIN (ANALYZE, BUFFERS) 요약을 찍는다.
 * <ul>
 * <li>SQL 은 InvMapper findTrxs 와 같은 열·조인·조건·정렬(occurred_at desc, trx_id desc). keyset 은 offset 대신 (occurred_at, trx_id) &lt; 직전 행</li>
 * <li>앱 계정·RLS 아래(TenantRunner)에서 잰다 — 운영과 같은 조건(comp_cd 정책 + 색인 ix_inv_trx_plant)</li>
 * <li>적재는 소유자 연결에서 session_replication_role = replica(트리거 끔)로 — 투영(inv_stock)·검사 트리거는 이 측정과 무관하고 100만 행이 몇 분 걸린다</li>
 * <li>다른 회사 원장 20만 행을 함께 넣어 RLS 조건이 걸러야 하는 상황을 만든다</li>
 * <li>세 번째 방식 "지연 조인"(offset 은 그대로, 안쪽에서 trx_id 만 골라 50행에만 열·부질의 계산) — 페이지 번호(hb.grid)를 바꾸지 않는 대안</li>
 * <li>문장 시간 제한 30초(운영 MyBatis default-statement-timeout 과 같음) — 넘으면 "시간 초과"로 적고 EXPLAIN 은 ANALYZE 없이</li>
 * </ul>
 * 실행: ./gradlew test --tests '*TrxPagingPerfTest*' -Dhb.perf=true (평소 check 에서는 건너뜀)
 */
class TrxPagingPerfTest extends PostgresTestBase {

    private static final int SIZE = 50;
    private static final String COLS = """
            t.trx_id, t.lot_id, (select l.lot_no from lot_master l where l.lot_id = t.lot_id) as lot_no, t.plant_cd, t.trx_type, t.item_id, i.item_cd,
            i.item_nm, t.unit_cd, t.node_id, n.node_cd, wms_node_path(t.node_id) as path, t.stock_status, t.qty, t.move_grp_id::text as move_grp_id,
            t.src_type, t.src_id, t.reason_cd, t.remark, t.occurred_at, t.recorded_at, t.entry_method, t.recorded_by, t.performed_by,
            w.worker_nm as performed_by_nm, t.reversal_of_id, (select r.trx_id from inv_trx r where r.reversal_of_id = t.trx_id) as reversed_by_id,
            t.po_line_id, t.po_qty, t.client_req_id""";
    private static final String FROM = """
            from inv_trx t
            join mst_item i on i.item_id = t.item_id
            join mst_equip_hier n on n.node_id = t.node_id
            left join mst_worker w on w.worker_id = t.performed_by
            where t.occurred_at >= '%s' and t.occurred_at < '%s' and t.plant_cd = 'P1'""";
    private static final String ORDER = " order by t.occurred_at desc, t.trx_id desc";
    private static final String KEYS = "select t.trx_id from inv_trx t where t.occurred_at >= '%s' and t.occurred_at < '%s' and t.plant_cd = 'P1'";
    private static final int TIMEOUT_MS = 30_000;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    InvMapper mapper;

    @Test
    @EnabledIfSystemProperty(named = "hb.perf", matches = "true")
    void 수불_원장_offset_대_keyset_10만_100만() throws Exception {
        String comp = accounts.newComp("TP");
        String noise = accounts.newComp("TN");
        long[] base = seedMaster(comp);
        long[] nb = seedMaster(noise);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String from = now.minusDays(91).toString();
        String to = now.plusDays(1).toString();
        StringBuilder out = new StringBuilder("\n[perf] 수불 원장 페이지 — 50행, 3번 중 최선(ms), EXPLAIN 실행 시간·버퍼\n");
        load(noise, nb, 1, 200_000, now);
        int loaded = 0;
        for (int target : new int[] {100_000, 1_000_000}) {
            long t0 = System.nanoTime();
            load(comp, base, loaded + 1, target, now);
            loaded = target;
            owner("analyze inv_trx");
            out.append(String.format("%n원장 %,d행(+다른 회사 20만) — 적재 %,dms%n", target, (System.nanoTime() - t0) / 1_000_000));
            String where = String.format(FROM, from, to);
            long total = tenant(comp, () -> jdbc.queryForObject("select count(*) " + where, Long.class));
            assertThat(total).isEqualTo(target);
            out.append(String.format("  count(*) 최선 %dms  | %s%n", best(() -> tenant(comp, () -> jdbc.queryForObject("select count(*) " + where,
                    Long.class))), summary(comp, "select count(*) " + where, true)));
            long pages = (total + SIZE - 1) / SIZE;
            for (long page : new long[] {1, pages / 2, pages}) {
                long offset = (page - 1) * SIZE;
                String off = "select " + COLS + " " + where + ORDER + " limit " + SIZE + " offset " + offset;
                String key;
                if (page == 1) {
                    key = "select " + COLS + " " + where + ORDER + " limit " + SIZE;
                } else {
                    Map<String, Object> prev = tenant(comp, () -> jdbc.queryForMap("select t.occurred_at, t.trx_id " + where + ORDER
                            + " limit 1 offset " + (offset - 1)));
                    OffsetDateTime at = ((Timestamp) prev.get("occurred_at")).toInstant().atOffset(ZoneOffset.UTC);
                    key = "select " + COLS + " " + where + " and (t.occurred_at, t.trx_id) < ('" + at + "', " + prev.get("trx_id") + ")" + ORDER
                            + " limit " + SIZE;
                }
                String keys = String.format(KEYS, from, to);
                String late = "select " + COLS + " from (" + keys + ORDER + " limit " + SIZE + " offset " + offset + ") k"
                        + " join inv_trx t on t.trx_id = k.trx_id join mst_item i on i.item_id = t.item_id join mst_equip_hier n on n.node_id = t.node_id"
                        + " left join mst_worker w on w.worker_id = t.performed_by" + ORDER;
                List<Long> b = ids(comp, key);
                assertThat(ids(comp, late)).as("지연 조인이 keyset 과 같은 행").isEqualTo(b);
                InvDtos.TrxSearch q = new InvDtos.TrxSearch(OffsetDateTime.parse(from), OffsetDateTime.parse(to), "P1", null, null, null, null,
                        io.smarthappy.platform.common.query.PageQuery.parse((int) page, SIZE, null, false, InvDtos.TrxSort.values(), 2000,
                                StockService.TRX_SORT));
                Supplier<List<Long>> real = () -> tenant(comp, () -> mapper.findTrxs(q).stream().map(InvDtos.TrxRow::getTrxId).toList());   // 일반 계획 그대로
                assertThat(real.get()).as("바꾼 매퍼(findTrxs)가 keyset 과 같은 행").isEqualTo(b);
                long mapperMs = best(real::get);
                Supplier<List<Long>> custom = () -> tenant(comp, () -> {
                    mapper.customPlan();                                       // StockService.trxs 와 같게
                    return mapper.findTrxs(q).stream().map(InvDtos.TrxRow::getTrxId).toList();
                });
                long customMs = best(custom::get);
                long to1 = best(() -> ids(comp, off));
                if (to1 >= 0 && to1 < TIMEOUT_MS / 3) {                        // 제한 근처면 다시 부르다 시간 초과 — 재확인 생략
                    assertThat(ids(comp, off)).as("keyset 이 offset 과 같은 행").isEqualTo(b);
                }
                out.append(String.format("  %,6d쪽 offset %,9d — offset %s | 지연 조인 %s | keyset %s | findTrxs(일반 계획) %s | 바꾼 trxs(+custom plan) %s%n", page, offset, ms(to1),
                        ms(best(() -> ids(comp, late))), ms(best(() -> ids(comp, key))), ms(mapperMs), ms(customMs)));
                out.append("           offset: ").append(summary(comp, off, to1 >= 0 && to1 < TIMEOUT_MS / 3)).append('\n');
                out.append("           지연 조인: ").append(summary(comp, late, true)).append('\n');
                out.append("           keyset: ").append(summary(comp, key, true)).append('\n');
                System.out.println(out);
            }
        }
        System.out.println(out);
    }

    /**
     * 현재고(wms-stock, QA 2026-10-24 M3) — 칸 10만·50만(품목 100·500 × 위치 1,000), 기본 정렬 품번·경로.
     * 바꾸기 전 SQL(행마다 wms_node_path — 정렬 키라 1쪽도 전 행) 대 바꾼 findStock(지연 조인 + 위치마다 경로 한 번)
     */
    @Test
    @EnabledIfSystemProperty(named = "hb.perf", matches = "true")
    void 현재고_지연_조인_전후_10만_50만() throws Exception {
        String comp = accounts.newComp("TS");
        accounts.addPlant(comp, "P1", "1공장");
        long[] base = tenant(comp, () -> {
            jdbc.update("""
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                    select ?, 'SK-' || lpad(g::text, 4, '0'), '실측 ' || g, 'RM', 'BUY', 'EA', 'N', 't', 't' from generate_series(1, 500) g""", comp);
            long wh = jdbc.queryForObject("""
                    insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                    values (?, 'P1', 'WH', 'SK-WH', 'SK-WH', 't', 't') returning node_id""", Long.class, comp);
            jdbc.update("""
                    insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                    select ?, 'P1', ?, 'LOC', 'SK-' || lpad(g::text, 4, '0'), 'SK-' || g, 't', 't' from generate_series(1, 1000) g""", comp, wh);
            return new long[] {jdbc.queryForObject("select min(item_id) from mst_item where item_cd like 'SK-%'", Long.class),
                    jdbc.queryForObject("select min(node_id) from mst_equip_hier where node_cd like 'SK-%' and node_type = 'LOC'", Long.class)};
        });
        StringBuilder out = new StringBuilder("\n[perf] 현재고 페이지 — 50행, 기본 정렬(품번·경로), 3번 중 최선\n");
        String old = "select s.stock_id, wms_node_path(s.node_id) as path from inv_stock s join mst_item i on i.item_id = s.item_id"
                + " join mst_equip_hier n on n.node_id = s.node_id left join lot_master lm on lm.lot_id = s.lot_id where s.plant_cd = 'P1'"
                + " order by i.item_cd, path, s.stock_id";
        int items = 0;
        for (int target : new int[] {100, 500}) {
            owner(String.format("""
                    insert into inv_stock (comp_cd, plant_cd, item_id, node_id, stock_status, qty, last_trx_id, updated_at)
                    select '%s', 'P1', %d + i, %d + n, 'AVAILABLE', 1, 0, now() from generate_series(%d, %d) i, generate_series(0, 999) n""",
                    comp, base[0], base[1], items, target - 1));
            items = target;
            owner("analyze inv_stock");
            long total = tenant(comp, () -> jdbc.queryForObject("select count(*) from inv_stock where plant_cd = 'P1'", Long.class));
            out.append(String.format("%n재고 칸 %,d%n", total));
            long pages = (total + SIZE - 1) / SIZE;
            for (long page : new long[] {1, pages / 2, pages}) {
                String before = old + " limit " + SIZE + " offset " + (page - 1) * SIZE;
                InvDtos.StockSearch q = new InvDtos.StockSearch("P1", null, false, null, null, null, false,
                        io.smarthappy.platform.common.query.PageQuery.parse((int) page, SIZE, null, false, InvDtos.StockSort.values(), 2000,
                                StockService.STOCK_SORT), null);
                Supplier<List<Long>> real = () -> tenant(comp, () -> mapper.findStock(q).stream().map(InvDtos.StockRow::getStockId).toList());
                List<Long> after = real.get();
                long b = best(() -> tenant(comp, () -> {
                    jdbc.queryForObject("select set_config('statement_timeout', ?, true)", String.class, String.valueOf(TIMEOUT_MS));
                    return jdbc.query(before, (rs, i) -> rs.getLong("stock_id"));
                }));
                if (b >= 0) {
                    assertThat(after).as("바꾼 findStock 이 바꾸기 전과 같은 행").isEqualTo(
                            tenant(comp, () -> jdbc.query(before, (rs, i) -> rs.getLong("stock_id"))));
                }
                Supplier<List<Long>> custom = () -> tenant(comp, () -> {
                    jdbc.queryForObject("select set_config('plan_cache_mode', 'force_custom_plan', true)", String.class);
                    return mapper.findStock(q).stream().map(InvDtos.StockRow::getStockId).toList();
                });
                out.append(String.format("  %,6d쪽 — 바꾸기 전 %s | 바꾼 findStock %s | + custom plan %s%n", page, ms(b), ms(best(real::get)),
                        ms(best(custom::get))));
            }
        }
        System.out.println(out);
    }

    // ------------------------------------------------------------------ 안쪽

    /** 품목 50·위치 20(창고 하나 아래) — [첫 품목 id, 첫 위치 id] */
    private long[] seedMaster(String c) {
        accounts.addPlant(c, "P1", "1공장");
        return tenant(c, () -> {
            jdbc.update("""
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                    select ?, 'PF-' || g, '실측 ' || g, 'RM', 'BUY', 'EA', 'N', 't', 't' from generate_series(1, 50) g""", c);
            long wh = jdbc.queryForObject("""
                    insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                    values (?, 'P1', 'WH', 'PF-WH', 'PF-WH', 't', 't') returning node_id""", Long.class, c);
            jdbc.update("""
                    insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                    select ?, 'P1', ?, 'LOC', 'PF-' || g, 'PF-' || g, 't', 't' from generate_series(1, 20) g""", c, wh);
            long item = jdbc.queryForObject("select min(item_id) from mst_item where item_cd like 'PF-%'", Long.class);
            long node = jdbc.queryForObject("select min(node_id) from mst_equip_hier where node_cd like 'PF-%' and node_type = 'LOC'", Long.class);
            return new long[] {item, node};
        });
    }

    /** 원장 행 [lo, hi] — 85일에 고르게(행마다 약 7초 간격, 과거로), 품목·위치 돌려 가며 */
    private void load(String c, long[] base, int lo, int hi, OffsetDateTime now) throws Exception {
        owner(String.format("""
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     recorded_at, entry_method, recorded_by)
                select '%s', 'P1', 'ADJ_IN', %d + (g %% 50), %d + (g %% 20), 'AVAILABLE', 1, 'EA', 'MANUAL',
                       timestamptz '%s' - interval '1 hour' - (g * interval '7 second'), now(), 'KEY', 'perf'
                  from generate_series(%d, %d) g""", c, base[0], base[1], now, lo, hi));
    }

    private void owner(String sql) throws Exception {
        try (Connection con = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = con.createStatement()) {
            st.execute("set session_replication_role = replica");
            st.execute(sql);
        }
    }

    private <T> T tenant(String c, Supplier<T> work) {
        return runner.call(c, work);
    }

    private List<Long> ids(String c, String sql) {
        return tenant(c, () -> {
            jdbc.queryForObject("select set_config('statement_timeout', ?, true)", String.class, String.valueOf(TIMEOUT_MS));
            return jdbc.query(sql, (rs, i) -> rs.getLong("trx_id"));
        });
    }

    /** 3번 중 최선(ms), 시간 제한을 넘으면 -1(더 돌리지 않음) */
    private static long best(Runnable r) {
        long min = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long s = System.nanoTime();
            try {
                r.run();
            } catch (org.springframework.dao.QueryTimeoutException e) {
                return -1;
            }
            min = Math.min(min, System.nanoTime() - s);
        }
        return min / 1_000_000;
    }

    private static String ms(long v) {
        return v < 0 ? String.format("%9s", "시간 초과(>" + TIMEOUT_MS / 1000 + "s)") : String.format("%7dms", v);
    }

    /** EXPLAIN (ANALYZE, BUFFERS) — 맨 위 노드·스캔 노드 이름, 맨 위 버퍼, 실행 시간 */
    private String summary(String c, String sql, boolean analyze) {
        List<String> plan = tenant(c, () -> jdbc.queryForList((analyze ? "explain (analyze, buffers) " : "explain ") + sql, String.class));
        List<String> scans = new ArrayList<>();
        String buffers = "";
        String exec = "";
        for (String l : plan) {
            String s = l.strip();
            if (buffers.isEmpty() && s.startsWith("Buffers:")) {
                buffers = s;
            }
            if (s.startsWith("Execution Time")) {
                exec = s;
            }
            if ((s.contains("Scan") || s.startsWith("->  Limit") || s.startsWith("->  Sort")) && s.contains(" on inv_trx t")) {
                scans.add(s.replaceAll("\\s+\\(cost=.*", "").replace("->  ", ""));
            }
        }
        String top = plan.get(0).strip().replaceAll("\\s+\\(cost=.*", "");
        return top + " / " + String.join(", ", scans) + " / " + buffers + " / " + (analyze ? exec : "(ANALYZE 생략 — 시간 초과·제한 근처)");
    }
}
