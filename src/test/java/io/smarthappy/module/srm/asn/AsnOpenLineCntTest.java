package io.smarthappy.module.srm.asn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.srm.link.AsnQuery;
import io.smarthappy.module.srm.link.PoReceiptGate;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * ASN 덜 받은 행 수 칸(docs/31 5.6, V185) — 트리거로만 유지되는 {@code srm_asn.open_line_cnt}.
 * 무작위 흐름(만들기·줄 고치기·확정·입고·되돌림) 뒤 칸 = 이력으로 다시 센 값, 동시 입고 2건(게이트 없이 — 트리거의 머리 행 잠금만으로),
 * 칸 직접 고치기 거부, A사로 B사.
 */
@AutoConfigureMockMvc
class AsnOpenLineCntTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String PO = "/api/srm/pos";
    private static final String ASN = "/api/srm/asns";
    private static final AtomicInteger REF = new AtomicInteger();
    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    /** 이력으로 다시 센 덜 받은 행 수(함수 hb_srm_asn_open_cnt 와 따로 쓴 같은 정의) */
    private static final String RECOUNT = """
            select a.asn_id, a.open_line_cnt,
                   (select count(*) from srm_asn_line l where l.asn_id = a.asn_id
                       and l.qty > coalesce((select sum(r.qty) from srm_po_receipt r where r.asn_line_id = l.asn_line_id), 0))::int as recount
              from srm_asn a order by a.asn_id""";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    ObjectProvider<PoReceiptGate> gate;

    @Autowired
    ObjectProvider<AsnQuery> query;

    @Autowired
    PlatformTransactionManager txm;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    RequestPostProcessor me;
    LocalDate today;

    @AfterAll
    static void shutdown() {
        POOL.shutdownNow();
    }

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("OL");
        me = buyer(comp);
        seed(comp);
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    private RequestPostProcessor buyer(String c) {
        long u = accounts.newUser(c, "buyer1", PW);
        accounts.grant(c, u, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, u, "PUR", "srm-asn", "SEARCH", "SAVE");
        return accounts.as(c, u, "buyer1");
    }

    private void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT-M8', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't'), (?, 'NUT-M8', '너트', 'RM', 'BUY', 'EA', 'N', 't', 't')""", c, c);
        long id = accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'V100', 'V100 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, c);
        accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, c);
    }

    private ResultActions send(RequestPostProcessor who, String method, String url, String body) throws Exception {
        var b = "PUT".equals(method) ? put(url) : post(url);
        return mvc.perform(b.with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data")).longValue();
    }

    /** 발행된 발주(행 1: BOLT 1000, 행 2: NUT 1000) — 행 id 둘 */
    private long[] issued() throws Exception {
        RequestPostProcessor who = me;
        long po = id(send(who, "POST", PO, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"1000\",\"unitPrice\":\"1\","
                + "\"dueDate\":\"" + today.plusDays(20) + "\"},{\"itemCd\":\"NUT-M8\",\"qty\":\"1000\",\"unitPrice\":\"1\",\"dueDate\":\""
                + today.plusDays(20) + "\"}]}"));
        for (String a : List.of("confirm", "issue")) {
            int v = JsonPath.read(mvc.perform(get(PO + "/" + po).with(who)).andReturn().getResponse().getContentAsString(), "$.data.po.rowVer");
            send(who, "POST", PO + "/" + po + "/" + a, "{\"rowVer\":" + v + "}").andExpect(status().isOk());
        }
        return new long[] {
            accounts.query(comp, "select po_line_id from srm_po_line where po_id = ? and line_no = 1", Long.class, po),
            accounts.query(comp, "select po_line_id from srm_po_line where po_id = ? and line_no = 2", Long.class, po) };
    }

    private String asnBody(String linesJson, Integer rowVer) {
        return "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"shipDate\":\"" + today + "\",\"etaDate\":\"" + today.plusDays(3) + "\""
                + (rowVer == null ? "" : ",\"rowVer\":" + rowVer) + ",\"lines\":[" + linesJson + "]}";
    }

    private static String ln(long poLineId, int qty, String lot) {
        return "{\"poLineId\":" + poLineId + ",\"qty\":\"" + qty + "\",\"supplierLotNo\":\"" + lot + "\"}";
    }

    private int ver(RequestPostProcessor who, long asnId) throws Exception {
        return JsonPath.read(mvc.perform(get(ASN + "/" + asnId).with(who)).andReturn().getResponse().getContentAsString(), "$.data.asn.rowVer");
    }

    private int openCnt(String c, long asnId) {
        return accounts.query(c, "select open_line_cnt from srm_asn where asn_id = ?", Integer.class, asnId);
    }

    private void assertReconciled(String c) {
        List<Map<String, Object>> rows = tx(c, () -> jdbc.queryForList(RECOUNT));
        assertThat(rows).isNotEmpty();
        for (Map<String, Object> r : rows) {
            assertThat(((Number) r.get("open_line_cnt")).intValue()).as("asn %s", r.get("asn_id")).isEqualTo(((Number) r.get("recount")).intValue());
        }
    }

    private <T> T tx(String c, Supplier<T> s) {
        return runner.call(c, () -> new TransactionTemplate(txm).execute(st -> s.get()));
    }

    @Test
    void 무작위_흐름_뒤_칸이_이력으로_다시_센_값과_같다() throws Exception {
        long[] pl = issued();
        Random rnd = new Random(20261024);
        record Line(long asnLineId, long poLineId, int qty) {
        }
        record Rcv(long poLineId, String ref, int qty) {
        }
        List<Long> sent = new ArrayList<>();
        Map<Long, List<Line>> lines = new java.util.HashMap<>();
        List<Rcv> live = new ArrayList<>();
        int lot = 0;
        for (int i = 0; i < 8; i++) {
            // 만들기(1~3줄) → 작성 중에 줄 고치기(한 번) → 확정(대개)
            StringBuilder js = new StringBuilder();
            int n = 1 + rnd.nextInt(3);
            for (int k = 0; k < n; k++) {
                js.append(k == 0 ? "" : ",").append(ln(pl[rnd.nextInt(2)], 1 + rnd.nextInt(5), "L" + (++lot)));
            }
            long asn = id(send(me, "POST", ASN, asnBody(js.toString(), null)));
            assertThat(openCnt(comp, asn)).isEqualTo(n);
            if (rnd.nextBoolean()) {
                send(me, "PUT", ASN + "/" + asn, asnBody(js + "," + ln(pl[rnd.nextInt(2)], 2, "L" + (++lot)), ver(me, asn))).andExpect(status().isOk());
                assertThat(openCnt(comp, asn)).isEqualTo(n + 1);
            }
            if (i % 4 == 3) {
                continue;   // 작성 중으로 남김
            }
            send(me, "POST", ASN + "/" + asn + "/send", "{\"rowVer\":" + ver(me, asn) + "}").andExpect(status().isOk());
            sent.add(asn);
            lines.put(asn, tx(comp, () -> jdbc.queryForList("select asn_line_id, po_line_id, qty from srm_asn_line where asn_id = ? order by line_no", asn))
                    .stream()
                    .map(m -> new Line(((Number) m.get("asn_line_id")).longValue(), ((Number) m.get("po_line_id")).longValue(),
                            ((BigDecimal) m.get("qty")).intValue()))
                    .toList());
        }
        // 입고·되돌림 섞기 — 줄마다 남은 양 안에서만(한도 초과 입고는 게이트가 다른 이유로 막으므로 피한다)
        for (int step = 0; step < 60; step++) {
            if (!live.isEmpty() && rnd.nextInt(4) == 0) {
                Rcv r = live.remove(rnd.nextInt(live.size()));
                String ref = "RCV_PO:ol-" + REF.incrementAndGet();
                runner.call(comp, () -> PoReceiptGate.of(gate).reverseAt(r.poLineId(), BigDecimal.valueOf(-r.qty()), ref, "wms1", null, r.ref()));
                continue;
            }
            long asn = sent.get(rnd.nextInt(sent.size()));
            Line l = lines.get(asn).get(rnd.nextInt(lines.get(asn).size()));
            int done = accounts.query(comp, "select coalesce(sum(qty), 0)::int from srm_po_receipt where asn_line_id = ?", Integer.class, l.asnLineId());
            if (done >= l.qty()) {
                continue;
            }
            int q = 1 + rnd.nextInt(l.qty() - done);
            String ref = "RCV_PO:ol-" + REF.incrementAndGet();
            runner.call(comp, () -> PoReceiptGate.of(gate).receiveAt(l.poLineId(), BigDecimal.valueOf(q), ref, "wms1", null, l.asnLineId()));
            live.add(new Rcv(l.poLineId(), ref, q));
        }
        assertReconciled(comp);
        // WMS 계약(AsnQuery#expected)도 칸을 읽는다 — 계약 값 = 다시 센 값, 고른 ASN = 보냄·입고 중 + 다시 센 값 > 0
        Map<Long, Integer> recount = new java.util.HashMap<>();
        tx(comp, () -> jdbc.queryForList(RECOUNT)).forEach(r -> recount.put(((Number) r.get("asn_id")).longValue(), ((Number) r.get("recount")).intValue()));
        List<Long> openIds = tx(comp, () -> jdbc.queryForList("select asn_id from srm_asn where status in ('SENT', 'PARTIAL_RECEIVED')", Long.class))
                .stream().filter(id -> recount.get(id) > 0).toList();
        List<AsnQuery.AsnHead> heads = runner.call(comp, () -> AsnQuery.of(query).expected("P1", null, null));
        assertThat(heads).extracting(AsnQuery.AsnHead::asnId).containsExactlyInAnyOrderElementsOf(openIds);
        assertThat(heads).allSatisfy(h -> assertThat(h.openLineCnt()).as("asn %s", h.asnId()).isEqualTo(recount.get(h.asnId())));
        List<Integer> values = tx(comp, () -> jdbc.queryForList("select open_line_cnt from srm_asn", Integer.class));
        assertThat(values).as("흐름이 0 과 0 아닌 값을 모두 만들었는가").contains(0).anyMatch(v -> v > 0);
        // grid 정렬·늦은 ASN 이 칸을 읽는다 — 덜 받은 행 수 정렬 결과 = 칸 내림차순
        String g = mvc.perform(get(ASN + "/grid").param("from", today.minusDays(5).toString()).param("to", today.plusDays(5).toString())
                .param("size", "100").param("sort", "openLineCnt,desc").with(me)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Integer> shown = JsonPath.read(g, "$.list[*].openLineCnt");
        assertThat(shown).hasSize(8).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void 같은_ASN_다른_줄에_동시_입고_2건이면_둘_다_반영된다() throws Exception {
        long[] pl = issued();
        long asn = id(send(me, "POST", ASN, asnBody(ln(pl[0], 5, "A") + "," + ln(pl[1], 5, "B"), null)));
        send(me, "POST", ASN + "/" + asn + "/send", "{\"rowVer\":" + ver(me, asn) + "}").andExpect(status().isOk());
        long a1 = accounts.query(comp, "select asn_line_id from srm_asn_line where asn_id = ? and line_no = 1", Long.class, asn);
        long a2 = accounts.query(comp, "select asn_line_id from srm_asn_line where asn_id = ? and line_no = 2", Long.class, asn);
        long po = accounts.query(comp, "select po_id from srm_po_line where po_line_id = ?", Long.class, pl[0]);
        assertThat(openCnt(comp, asn)).isEqualTo(2);
        // 게이트 없이 직접 — 이력 INSERT 뒤 트리거가 ASN 머리 행을 잠근다. 앞이 잠근 채 커밋 전이면 뒤는 기다렸다가 앞의 입고까지 보고 센다
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        Future<?> first = POOL.submit(() -> tx(comp, () -> {
            receiveDirect(po, pl[0], a1);
            inserted.countDown();
            await(go);
            return null;
        }));
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
        Future<?> second = POOL.submit(() -> tx(comp, () -> {
            receiveDirect(po, pl[1], a2);
            return null;
        }));
        // 뒤가 잠금 대기에 들어갈 때까지(또는 끝날 때까지) 본 뒤 앞을 놓는다
        for (int i = 0; i < 100 && !second.isDone() && lockWaiters() == 0; i++) {
            Thread.sleep(50);
        }
        assertThat(second.isDone()).as("뒤 트랜잭션은 앞이 끝날 때까지 기다려야 한다").isFalse();
        go.countDown();
        first.get(10, TimeUnit.SECONDS);
        second.get(10, TimeUnit.SECONDS);
        assertThat(openCnt(comp, asn)).isZero();
        assertReconciled(comp);
    }

    private void receiveDirect(long po, long poLine, long asnLine) {
        jdbc.update("update srm_po_line set recv_qty = recv_qty + 5, updated_at = now(), updated_by = 't' where po_line_id = ?", poLine);
        jdbc.update("""
                insert into srm_po_receipt (comp_cd, po_id, po_line_id, occurred_on, qty, trx_ref, asn_line_id, created_by)
                values (?, ?, ?, current_date, 5, ?, ?, 't')""", comp, po, poLine, "DIRECT:" + REF.incrementAndGet(), asnLine);
    }

    private static void await(CountDownLatch l) {
        try {
            l.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int lockWaiters() {
        return accounts.query(comp, "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()",
                Integer.class);
    }

    @Test
    void 칸을_직접_고치면_거부되고_줄_고치기는_다시_센다() throws Exception {
        long[] pl = issued();
        long asn = id(send(me, "POST", ASN, asnBody(ln(pl[0], 3, "A"), null)));
        assertThat(openCnt(comp, asn)).isEqualTo(1);
        Throwable t = catchThrowable(() -> accounts.exec(comp, "update srm_asn set open_line_cnt = 0 where asn_id = ?", asn));
        assertThat(PgErrors.constraint(t).orElse(String.valueOf(t))).isEqualTo("ck_srm_asn_open_cnt");
        Throwable ins = catchThrowable(() -> accounts.exec(comp, """
                insert into srm_asn (comp_cd, asn_no, cust_id, plant_cd, ship_date, eta_date, open_line_cnt, created_by, updated_by)
                select ?, 'OL-X', cust_id, 'P1', current_date, current_date, 3, 't', 't' from mst_cust where cust_cd = 'V100'""", comp));
        assertThat(PgErrors.constraint(ins).orElse(String.valueOf(ins))).isEqualTo("ck_srm_asn_open_cnt");
        // 줄 수량·삭제(작성 중 직접 SQL)도 트리거가 다시 센다
        accounts.exec(comp, "insert into srm_asn_line (comp_cd, asn_id, line_no, po_line_id, item_id, unit_cd, qty, supplier_lot_no, created_by, updated_by) "
                + "select ?, ?, 2, po_line_id, item_id, unit_cd, 1, 'B', 't', 't' from srm_po_line where po_line_id = ?", comp, asn, pl[1]);
        assertThat(openCnt(comp, asn)).isEqualTo(2);
        accounts.exec(comp, "delete from srm_asn_line where asn_id = ? and line_no = 2", asn);
        assertThat(openCnt(comp, asn)).isEqualTo(1);
        assertReconciled(comp);
    }

    @Test
    void A사로_B사_다른_회사_입고는_칸에_닿지_않고_보이지도_않는다() throws Exception {
        long[] pl = issued();
        long asn = id(send(me, "POST", ASN, asnBody(ln(pl[0], 4, "A"), null)));
        send(me, "POST", ASN + "/" + asn + "/send", "{\"rowVer\":" + ver(me, asn) + "}").andExpect(status().isOk());
        long a1 = accounts.query(comp, "select asn_line_id from srm_asn_line where asn_id = ?", Long.class, asn);
        String b = accounts.newComp("OM");
        RequestPostProcessor bu = buyer(b);
        seed(b);
        // B사 사용자로 A사 ASN 줄에 입고 → 줄을 못 찾음(RLS), A사 칸은 그대로
        Throwable t = catchThrowable(() -> runner.call(b, () -> PoReceiptGate.of(gate).receiveAt(pl[0], BigDecimal.ONE, "RCV_PO:x-" + REF.incrementAndGet(),
                "wms1", null, a1)));
        assertThat(t).isNotNull();
        assertThat(openCnt(comp, asn)).isEqualTo(1);
        mvc.perform(get(ASN + "/" + asn).with(bu)).andExpect(status().isNotFound());
        // A사 입고는 A사 칸만
        runner.call(comp, () -> PoReceiptGate.of(gate).receiveAt(pl[0], BigDecimal.valueOf(4), "RCV_PO:a-" + REF.incrementAndGet(), "wms1", null, a1));
        assertThat(openCnt(comp, asn)).isZero();
        assertReconciled(comp);
    }
}
