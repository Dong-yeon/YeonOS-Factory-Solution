package io.smarthappy.module.srm.asn;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import io.smarthappy.module.srm.link.AsnQuery;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * WMS 도착 예정 ASN(AsnQuery#expected) 실측·계획 (docs/31 5.6 뒤 정리 — 평소 건너뜀: -Dhb.perf=true).
 * 한 공장에 ASN 100만 건(행 1개씩), 도착 예정일 1,010일에 퍼뜨리고 최근 약 15일 것만 열림(보냄·입고 중, 덜 받은 행 1) — 나머지는 다 받음.
 * 열린 것이 최근에 몰리는 실제 모양이라, 도착 예정일 순 색인으로 앞에서부터 읽으면 다 받은 행을 거의 다 지나야 한다.
 * 계획 비교는 소유자 계정(RLS 없이 회사 조건을 직접)으로: 고치기 전(행마다 이력 합) / 칸 + 부분 색인 ix_srm_asn_open.
 */
class AsnExpectedPerfTest extends PostgresTestBase {

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    ObjectProvider<AsnQuery> query;

    static final String OLD = """
            select * from (
                select a.asn_id, a.asn_no, a.eta_date, a.status,
                       (select count(*) from srm_asn_line l where l.asn_id = a.asn_id)::int as line_cnt,
                       (select count(*) from srm_asn_line l where l.asn_id = a.asn_id
                           and l.qty > coalesce((select sum(x.qty) from srm_po_receipt x where x.asn_line_id = l.asn_line_id), 0))::int as open_line_cnt
                  from srm_asn a join mst_cust c on c.cust_id = a.cust_id
                 where a.comp_cd = '%s' and a.plant_cd = 'P1' and a.status in ('SENT', 'PARTIAL_RECEIVED')
            ) x where x.open_line_cnt > 0 order by x.eta_date, x.asn_no limit 500""";

    static final String NEW = """
            select a.asn_id, a.asn_no, a.eta_date, a.status,
                   (select count(*) from srm_asn_line l where l.asn_id = a.asn_id)::int as line_cnt, a.open_line_cnt
              from srm_asn a join mst_cust c on c.cust_id = a.cust_id
             where a.comp_cd = '%s' and a.plant_cd = 'P1' and a.status in ('SENT', 'PARTIAL_RECEIVED') and a.open_line_cnt > 0
             order by a.eta_date, a.asn_no limit 500""";

    @Test
    @EnabledIfSystemProperty(named = "hb.perf", matches = "true")
    void 실측_100만_도착_예정_목록_계획_비교() throws Exception {
        String comp = accounts.newComp("PE");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't')""", comp);
        long cust = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'V100', 'V100 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, comp);
        accounts.exec(comp, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", cust, comp);
        long item = accounts.query(comp, "select item_id from mst_item where item_cd = 'BOLT'", Long.class);
        long buyer = accounts.newUser(comp, "buyer1", "Maple-anchor-63");
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement s = c.createStatement()) {
            s.execute("set session_replication_role = replica");
            // 발주·행 하나(FK 대상) — 트리거 꺼서 상태 검사 없이
            s.execute(String.format("""
                    insert into srm_po (comp_cd, po_no, cust_id, plant_cd, po_date, status, currency_cd, buyer_user_id, created_by, updated_by)
                    values ('%s', 'PE-PO', %d, 'P1', current_date, 'ISSUED', 'KRW', %d, 't', 't')""", comp, cust, buyer));
            s.execute(String.format("""
                    insert into srm_po_line (comp_cd, po_id, line_no, item_id, item_nm, unit_cd, qty, unit_price, amt, due_date, created_by, updated_by)
                    select '%s', po_id, 1, %d, '볼트', 'EA', 1000000000, 1, 1000000000, current_date, 't', 't' from srm_po where po_no = 'PE-PO' and comp_cd = '%s'""",
                    comp, item, comp));
            long t0 = System.nanoTime();
            s.execute(String.format("""
                    insert into srm_asn (comp_cd, asn_no, cust_id, plant_cd, ship_date, eta_date, status, sent_at, open_line_cnt, created_by, updated_by)
                    select '%s', 'PE' || g, %d, 'P1', d - 2, d,
                           case when d >= current_date - 5 then (case when g %% 2 = 0 then 'SENT' else 'PARTIAL_RECEIVED' end) else 'RECEIVED' end,
                           now(), case when d >= current_date - 5 then 1 else 0 end, 't', 't'
                      from generate_series(1, 1000000) g cross join lateral (select current_date - 1000 + (g %% 1010) as d) e""", comp, cust));
            s.execute(String.format("""
                    insert into srm_asn_line (comp_cd, asn_id, line_no, po_line_id, item_id, unit_cd, qty, created_by, updated_by)
                    select a.comp_cd, a.asn_id, 1, l.po_line_id, %d, 'EA', 10, 't', 't'
                      from srm_asn a cross join (select po_line_id from srm_po_line where comp_cd = '%s') l where a.comp_cd = '%s'""",
                    item, comp, comp));
            s.execute("analyze srm_asn");
            s.execute("analyze srm_asn_line");
            out.add(String.format("100만 건 넣기 %,dms, 열린 ASN %s건", (System.nanoTime() - t0) / 1_000_000,
                    one(s, "select count(*) from srm_asn where comp_cd = '" + comp + "' and open_line_cnt > 0")));
            s.execute("set session_replication_role = origin");
            out.addAll(plan(s, "고치기 전(행마다 이력 합)", String.format(OLD, comp)));
            out.addAll(plan(s, "칸 + 부분 색인 ix_srm_asn_open(V189)", String.format(NEW, comp)));
        }
        // 계약 경로(앱 계정·RLS)로 한 번 — 칸(색인 그대로)
        long s0 = System.nanoTime();
        int n = runner.call(comp, () -> AsnQuery.of(query).expected("P1", null, null)).size();
        out.add(String.format("계약 expected(P1) %d건 %,dms", n, (System.nanoTime() - s0) / 1_000_000));
        assertThat(n).isPositive();
        out.forEach(l -> System.out.println("[perf] " + l));
    }

    private static String one(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static List<String> plan(Statement s, String label, String sql) throws Exception {
        List<String> out = new ArrayList<>();
        out.add("---- " + label);
        try (ResultSet rs = s.executeQuery("explain (analyze, buffers, timing off) " + sql)) {
            while (rs.next()) {
                String line = rs.getString(1);
                if (line.contains("Scan") || line.contains("Execution Time") || line.contains("Sort") || line.contains("Rows Removed")
                        || line.contains("Buffers: shared") && out.size() < 3) {
                    out.add(line);
                }
            }
        }
        return out;
    }
}
