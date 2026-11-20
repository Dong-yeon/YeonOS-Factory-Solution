package io.smarthappy.module.srm.asn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * ASN grid 깊은 쪽 실측 (QA 2026-10-24 offset 조사 M5 — 평소 건너뜀: -Dhb.perf=true).
 * 협력사 하나·발주 행 하나에 ASN(행 1개씩, 반은 보냄) 10만 → 100만 건을 소유자 계정으로 한꺼번에 넣고(시험 전용, 트리거 끔),
 * grid API(기간 = 전체, 쪽 크기 50, 기본 정렬 도착 예정일 desc)의 첫 쪽·가운데 쪽·끝 쪽 시간을 잰다. 합계(total)는 첫 쪽에서만.
 */
@AutoConfigureMockMvc
class AsnGridPerfTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Test
    @EnabledIfSystemProperty(named = "hb.perf", matches = "true")
    void 실측_10만_100만_첫_가운데_끝_쪽() throws Exception {
        String comp = accounts.newComp("PF");
        long u = accounts.newUser(comp, "buyer1", PW);
        accounts.grant(comp, u, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, u, "PUR", "srm-asn", "SEARCH", "SAVE");
        RequestPostProcessor me = accounts.as(comp, u, "buyer1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", comp);
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't')""", comp);
        long cust = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'V100', 'V100 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, comp);
        accounts.exec(comp, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", cust, comp);
        String due = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " + 20)::text", String.class);
        long po = ((Number) JsonPath.read(mvc.perform(post("/api/srm/pos").with(csrf()).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT\",\"qty\":\"100\",\"unitPrice\":\"1\",\"dueDate\":\"" + due
                        + "\"}]}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data")).longValue();
        long poLine = accounts.query(comp, "select po_line_id from srm_po_line where po_id = ?", Long.class, po);
        long item = accounts.query(comp, "select item_id from mst_item where item_cd = 'BOLT'", Long.class);
        String from = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " - 200)::text", String.class);
        String to = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " + 150)::text", String.class);

        List<String> out = new ArrayList<>();
        int done = 0;
        for (int target : new int[] {100_000, 1_000_000}) {
            long t0 = System.nanoTime();
            bulk(comp, cust, poLine, item, done + 1, target);
            done = target;
            out.add(String.format("%,d건 넣기 %,dms", target, (System.nanoTime() - t0) / 1_000_000));
            int last = (target + 49) / 50;
            for (int[] pt : new int[][] {{1, 1}, {last / 2, 0}, {last, 0}}) {
                long best = Long.MAX_VALUE;
                MvcResult r = null;
                for (int i = 0; i < 2; i++) {
                    long s = System.nanoTime();
                    r = mvc.perform(get("/api/srm/asns/grid").param("from", from).param("to", to).param("page", String.valueOf(pt[0]))
                            .param("size", "50").param("total", pt[1] == 1 ? "true" : "false").with(me))
                            .andExpect(status().isOk()).andReturn();
                    best = Math.min(best, System.nanoTime() - s);
                }
                int rows = JsonPath.read(r.getResponse().getContentAsString(), "$.list.length()");
                assertThat(rows).isPositive();
                if (pt[1] == 1) {
                    assertThat(((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.total")).intValue()).isEqualTo(target);
                }
                out.add(String.format("  %,d건 %s쪽(%,d)%s: %,dms", target, pt[0] == 1 ? "첫 " : pt[0] == last ? "끝 " : "가운데 ", pt[0],
                        pt[1] == 1 ? "+합계" : "", best / 1_000_000));
            }
            // 덜 받은 행 수로 정렬·"늦은 ASN 만" — V185 전에는 안쪽이 후보 전체를 셌다(docs/31 5.6)
            long s = System.nanoTime();
            mvc.perform(get("/api/srm/asns/grid").param("from", from).param("to", to).param("page", String.valueOf(last / 2)).param("size", "50")
                    .param("sort", "openLineCnt,desc").param("total", "false").with(me)).andExpect(status().isOk());
            out.add(String.format("  %,d건 덜 받은 행 수 정렬 가운데 쪽: %,dms", target, (System.nanoTime() - s) / 1_000_000));
            s = System.nanoTime();
            mvc.perform(get("/api/srm/asns/grid").param("from", from).param("to", to).param("late", "true").param("size", "50")
                    .param("total", "true").with(me)).andExpect(status().isOk());
            out.add(String.format("  %,d건 늦은 ASN 만 첫 쪽+합계: %,dms", target, (System.nanoTime() - s) / 1_000_000));
        }
        out.forEach(l -> System.out.println("[perf] " + l));
    }

    /** ASN(작성 중) + 행 1개 — 소유자 계정, 트리거 끔(시험 전용). 도착 예정일은 300일에 퍼뜨림 */
    private static void bulk(String comp, long cust, long poLine, long item, int fromNo, int toNo) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement s = c.createStatement()) {
            s.execute("set session_replication_role = replica");
            s.execute(String.format("""
                    insert into srm_asn (comp_cd, asn_no, cust_id, plant_cd, ship_date, eta_date, status, sent_at, created_by, updated_by)
                    select '%s', 'PF' || g, %d, 'P1', current_date - 190 + (g %% 300), current_date - 188 + (g %% 300),
                           case when g %% 2 = 0 then 'SENT' else 'DRAFT' end, case when g %% 2 = 0 then now() end, 't', 't'
                      from generate_series(%d, %d) g""", comp, cust, fromNo, toNo));
            s.execute(String.format("""
                    insert into srm_asn_line (comp_cd, asn_id, line_no, po_line_id, item_id, unit_cd, qty, created_by, updated_by)
                    select a.comp_cd, a.asn_id, 1, %d, %d, 'EA', 10, 't', 't'
                      from srm_asn a where a.comp_cd = '%s' and a.asn_no between 'PF' and 'PG'
                       and not exists (select 1 from srm_asn_line l where l.asn_id = a.asn_id)""", poLine, item, comp));
            s.execute(String.format("update srm_asn a set open_line_cnt = hb_srm_asn_open_cnt(a.asn_id) "
                    + "where a.comp_cd = '%s' and a.asn_no between 'PF' and 'PG' and a.open_line_cnt = 0", comp));
            s.execute("analyze srm_asn");
            s.execute("analyze srm_asn_line");
        }
    }
}
