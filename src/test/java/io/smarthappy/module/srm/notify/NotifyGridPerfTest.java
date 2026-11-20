package io.smarthappy.module.srm.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 협력사 통지 grid 깊은 쪽 실측 (QA 2026-10-24 offset 조사 M4 — 평소 건너뜀: -Dhb.perf=true).
 * sys_outbox 에 SRM 통지(발주 발행 payload 그대로의 모양, 보냄) 10만 → 100만 건 + 같은 회사의 다른 토픽 같은 수를 소유자 계정으로 넣고
 * (시험 전용, 트리거 끔), grid API(기간 301일, 쪽 크기 50)의 첫·가운데·끝 쪽(기본 정렬 = 만든 시각 desc)과
 * payload 칸 정렬(협력사·번호) 가운데 쪽 시간을 잰다. 합계는 첫 쪽에서만.
 */
@AutoConfigureMockMvc
class NotifyGridPerfTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Test
    @EnabledIfSystemProperty(named = "hb.perf", matches = "true")
    void 실측_10만_100만_첫_가운데_끝_쪽과_payload_정렬() throws Exception {
        String comp = accounts.newComp("PN");
        long u = accounts.newUser(comp, "buyer1", PW);
        accounts.grant(comp, u, "PUR", NotifyService.PROG, "SEARCH");
        RequestPostProcessor me = accounts.as(comp, u, "buyer1");
        String from = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " - 300)::text", String.class);
        String to = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + ")::text", String.class);

        List<String> out = new ArrayList<>();
        int done = 0;
        for (int target : new int[] {100_000, 1_000_000}) {
            long t0 = System.nanoTime();
            bulk(comp, done + 1, target);
            done = target;
            out.add(String.format("%,d건(+다른 토픽 %,d건) 넣기 %,dms", target, target, (System.nanoTime() - t0) / 1_000_000));
            int last = (target + 49) / 50;
            for (int[] pt : new int[][] {{1, 1}, {last / 2, 0}, {last, 0}}) {
                MvcResult r = null;
                long best = Long.MAX_VALUE;
                for (int i = 0; i < 2; i++) {
                    long s = System.nanoTime();
                    r = grid(me, from, to, pt[0], pt[1] == 1, null);
                    best = Math.min(best, System.nanoTime() - s);
                }
                assertThat((int) JsonPath.read(r.getResponse().getContentAsString(), "$.list.length()")).isPositive();
                if (pt[1] == 1) {
                    assertThat(((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.total")).intValue()).isEqualTo(target);
                }
                out.add(String.format("  %,d건 %s쪽(%,d)%s: %,dms", target, pt[0] == 1 ? "첫 " : pt[0] == last ? "끝 " : "가운데 ", pt[0],
                        pt[1] == 1 ? "+합계" : "", best / 1_000_000));
            }
            for (String sort : new String[] {"custNm,asc", "poNo,desc"}) {
                long best = Long.MAX_VALUE;
                for (int i = 0; i < 2; i++) {
                    long s = System.nanoTime();
                    grid(me, from, to, last / 2, false, sort);
                    best = Math.min(best, System.nanoTime() - s);
                }
                out.add(String.format("  %,d건 %s 정렬 가운데 쪽: %,dms", target, sort, best / 1_000_000));
            }
        }
        out.forEach(l -> System.out.println("[perf] " + l));
        // 계획 비교(소유자 계정 — RLS 없이, 비교용): 고치기 전 질의 vs 지연 조인(안쪽 outbox_id 만), 기본 정렬 끝 쪽
        String where = "where o.comp_cd = '" + comp + "' and o.topic in ('SRM_PO_ISSUED', 'SRM_PO_CANCELLED', 'SRM_CLAIM_SENT')"
                + " and o.created_at >= now() - interval '301 days'";
        String cols = "o.outbox_id, o.topic, o.biz_key, (o.payload ->> 'poId')::bigint, coalesce(o.payload ->> 'poNo', o.payload ->> 'claimNo'),"
                + " (o.payload ->> 'rev')::int, o.payload ->> 'custNm', o.payload ->> 'to', o.status, o.attempts, o.last_error, o.created_at, o.sent_at";
        explain("고치기 전", "select " + cols + " from sys_outbox o " + where
                + " order by o.created_at desc nulls last, o.outbox_id desc limit 50 offset 999950");
        explain("지연 조인(지금 매퍼 모양)", "select " + cols + " from (select o.outbox_id, o.created_at from sys_outbox o " + where
                + " order by o.created_at desc nulls last, o.outbox_id desc limit 50 offset 999950) k join sys_outbox o on o.outbox_id = k.outbox_id"
                + " order by k.created_at desc nulls last, k.outbox_id desc");
        explain("payload 정렬, 고치기 전", "select " + cols + " from sys_outbox o " + where
                + " order by o.payload ->> 'custNm' nulls last, o.outbox_id desc limit 50 offset 500000");
    }

    private static void explain(String label, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement s = c.createStatement();
                var rs = s.executeQuery("explain (analyze, buffers, timing off) " + sql)) {
            System.out.println("[plan] ---- " + label);
            while (rs.next()) {
                System.out.println("[plan] " + rs.getString(1));
            }
        }
    }

    private MvcResult grid(RequestPostProcessor me, String from, String to, int page, boolean total, String sort) throws Exception {
        var b = get("/api/srm/notices/grid").param("from", from).param("to", to).param("page", String.valueOf(page)).param("size", "50")
                .param("total", String.valueOf(total));
        if (sort != null) {
            b = b.param("sort", sort);
        }
        return mvc.perform(b.with(me)).andExpect(status().isOk()).andReturn();
    }

    /** SRM 통지(PoService.notifySupplier 와 같은 payload 칸) + 다른 토픽 같은 수 — 소유자 계정, 트리거 끔. 만든 시각은 299일에 퍼뜨림 */
    private static void bulk(String comp, int fromNo, int toNo) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement s = c.createStatement()) {
            s.execute("set session_replication_role = replica");
            s.execute(String.format("""
                    insert into sys_outbox (comp_cd, message_id, topic, biz_key, payload, status, sent_at, created_at, created_by)
                    select '%s', gen_random_uuid(), case g %% 10 when 0 then 'SRM_PO_CANCELLED' when 1 then 'SRM_CLAIM_SENT' else 'SRM_PO_ISSUED' end,
                           'PO:' || g || ':0',
                           jsonb_build_object('kind', 'SRM_PO_ISSUED', 'poId', g, 'poNo', 'PO-' || lpad(g::text, 8, '0'), 'rev', 0,
                                              'custNm', '협력사 ' || (g %% 500), 'compNm', '데모 회사', 'to', 'v' || (g %% 500) || '@example.com',
                                              'lineCnt', 3, 'totalAmt', 123456.78, 'currencyCd', 'KRW', 'firstDue', '2026-11-01'),
                           'SENT', now(), now() - ((g %% 299) || ' days')::interval - ((g %% 86400) || ' seconds')::interval, 't'
                      from generate_series(%d, %d) g""", comp, fromNo, toNo));
            s.execute(String.format("""
                    insert into sys_outbox (comp_cd, message_id, topic, biz_key, payload, status, sent_at, created_at, created_by)
                    select '%s', gen_random_uuid(), 'WMS_STOCK_CHANGED', 'X:' || g, jsonb_build_object('itemId', g, 'qty', 1),
                           'SENT', now(), now() - ((g %% 300) || ' days')::interval, 't'
                      from generate_series(%d, %d) g""", comp, fromNo, toNo));
            s.execute("analyze sys_outbox");
        }
    }
}
