package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA — WMS 반품 출고 RTN_SUP(#414·#425, V159·V161) 이상 사용. 모듈 시험(ReturnIssueTest)이 다루지 않는 것만:
 * C 같은 요청 id 동시 두 번(둘 다 반품 잠금 대기 확인 후 — QR-1 재현) / C 두 사람이 같은 출고를 동시에 역분개 / 역분개 왕복 두 번(출고→되돌림→출고→되돌림) /
 * 앱 계정으로 커밋 검사 우회 시도(SET CONSTRAINTS ALL IMMEDIATE · session_replication_role · DISABLE TRIGGER) / 역분개 행을 다시 역분개.
 */
@AutoConfigureMockMvc
class ReturnIssueQaTest extends PostgresTestBase {

    static final String PW = "Qa-return-gate-55";
    static final String RTN = "/api/wms/rtn-issue";
    static final ExecutorService POOL = Executors.newCachedThreadPool();

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    PlatformTransactionManager txm;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    RequestPostProcessor u1;
    RequestPostProcessor u2;
    long a1;
    long bolt;
    long cust;
    long poLine;
    int seq;

    @AfterAll
    static void shutdown() {
        POOL.shutdownNow();
    }

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("QW");
        u1 = user("wh1");
        u2 = user("wh2");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) "
                + "values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", comp);
        bolt = accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'QW-BOLT', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't') returning item_id""", Long.class, comp);
        cust = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'QW-V', 'QW 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, comp);
        accounts.exec(comp, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')",
                cust, comp);
        long wh = accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', null, 'WH', 'QW-WH', 'QW-WH', 't', 't') returning node_id""", Long.class, comp);
        a1 = accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, 'LOC', 'QW-A1', 'QW-A1', 't', 't') returning node_id""", Long.class, comp, wh);
        String due = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " + 20)::text", String.class);
        MvcResult m = postJson(u1, "/api/srm/pos", "{\"custCd\":\"QW-V\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"QW-BOLT\",\"qty\":\"100\","
                + "\"unitPrice\":\"1\",\"dueDate\":\"" + due + "\"}]}").andExpect(status().isOk()).andReturn();
        long po = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
        for (String act : new String[] {"confirm", "issue"}) {
            int rv = accounts.query(comp, "select row_ver from srm_po where po_id = ?", Integer.class, po);
            postJson(u1, "/api/srm/pos/" + po + "/" + act, "{\"rowVer\":" + rv + "}").andExpect(status().isOk());
        }
        poLine = accounts.query(comp, "select po_line_id from srm_po_line where po_id = ?", Long.class, po);
    }

    // ------------------------------------------------------------------ 도우미

    RequestPostProcessor user(String id) {
        long u = accounts.newUser(comp, id, PW);
        accounts.grant(comp, u, "QW", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, u, "QW", "wms-rcv", "SEARCH", "SAVE");
        accounts.grant(comp, u, "QW", "wms-trx", "SEARCH", "SAVE");
        accounts.grant(comp, u, "QW", "wms-rtn-issue", "SEARCH", "SAVE");
        return accounts.as(comp, u, id);
    }

    ResultActions postJson(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    String req() {
        return "qa-rtn-" + (++seq) + "-" + System.nanoTime();
    }

    /** 구매 입고 40(HOLD) → LOT id */
    long receive() throws Exception {
        MvcResult m = postJson(u1, "/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"poLineId\":" + poLine + ",\"qty\":40,"
                + "\"stockStatus\":\"HOLD\",\"clientReqId\":\"" + req() + "\"}").andExpect(status().isOk()).andReturn();
        long trx = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.trxId")).longValue();
        return accounts.query(comp, "select src_id from inv_trx where trx_id = ?", Long.class, trx);
    }

    /** 확정된 반품 지시 10 EA(교체 없음) → 번호 */
    String confirmed(long lot) {
        String no = "QW-RT-" + (++seq);
        String lotNo = accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, lot);
        long id = accounts.query(comp, """
                insert into srm_return (comp_cd, return_no, cust_id, plant_cd, ncr_id, ncr_no, ncr_line_no, lot_id, lot_no, po_line_id, item_id,
                                        unit_cd, qty, replace_yn, created_by, updated_by)
                values (?, ?, ?, 'P1', ?, ?, 1, ?, ?, null, ?, 'EA', 10, 'N', 't', 't') returning return_id""", Long.class,
                comp, no, cust, 9000L + seq, "NCR-QW-" + seq, lot, lotNo, bolt);
        accounts.exec(comp, "update srm_return set status = 'CONFIRMED', confirmed_at = now(), confirmed_by = 't' where return_id = ?", id);
        return no;
    }

    String issueBody(String no, String reqId) {
        return "{\"returnNo\":\"" + no + "\",\"rows\":[{\"nodeId\":" + a1 + ",\"stockStatus\":\"HOLD\",\"qty\":10}],\"clientReqId\":\"" + reqId + "\"}";
    }

    String ret(String no) {
        return accounts.query(comp, "select status || ':' || coalesce(ship_ref, '-') from srm_return where return_no = ?", String.class, no);
    }

    BigDecimal stock() {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and item_id = ?", BigDecimal.class, a1, bolt);
    }

    int rtnRows(String no) {
        return accounts.query(comp, "select count(*) from inv_trx t join srm_return r on r.return_id = t.src_id "
                + "where t.src_type = 'SRM_RETURN' and r.return_no = ?", Integer.class, no);
    }

    int lockWaiters() {
        return accounts.query(comp, "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()",
                Integer.class);
    }

    <T> T tx(Supplier<T> s) {
        return runner.call(comp, () -> new TransactionTemplate(txm).execute(st -> s.get()));
    }

    /** holder 가 sql 로 행을 잡은 동안 요청들을 동시에 보내고, 모두 잠금 대기인 것을 확인한 뒤 놓는다 → "상태 본문" */
    List<String> concurrent(String holdSql, Object holdArg, List<Supplier<MvcResult>> calls) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        Future<?> holder = POOL.submit(() -> tx(() -> {
            jdbc.queryForList(holdSql, holdArg);
            held.countDown();
            try {
                go.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        int before = lockWaiters();
        List<CompletableFuture<String>> fs = new ArrayList<>();
        for (Supplier<MvcResult> c : calls) {
            fs.add(CompletableFuture.supplyAsync(() -> {
                try {
                    MvcResult r = c.get();
                    return r.getResponse().getStatus() + " " + r.getResponse().getContentAsString();
                } catch (Exception e) {
                    return "EX " + e;
                }
            }, POOL));
        }
        for (int i = 0; i < 200 && lockWaiters() - before < calls.size(); i++) {
            Thread.sleep(50);
        }
        int waiting = lockWaiters() - before;
        go.countDown();
        holder.get(10, TimeUnit.SECONDS);
        List<String> out = new ArrayList<>();
        for (CompletableFuture<String> f : fs) {
            out.add(f.get(30, TimeUnit.SECONDS));
        }
        assertThat(waiting).as("모두 잠금 대기 " + out).isEqualTo(calls.size());
        return out;
    }

    Supplier<MvcResult> call(RequestPostProcessor who, String url, String body) {
        return () -> {
            try {
                return postJson(who, url, body).andReturn();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    // ------------------------------------------------------------------ 시험

    /** 출고 버튼 두 번을 동시에(같은 요청 id, 둘 다 반품 잠금 대기 확인 후) → [응답 둘] */
    List<String> doubleIssue(String no, String r) throws Exception {
        return concurrent("select 1 from srm_return where return_no = ? for update", no,
                List.of(call(u1, RTN, issueBody(no, r)), call(u1, RTN, issueBody(no, r))));
    }

    /** C — 같은 요청 id 동시 두 번: 원장은 한 번만(재고 40→30), 반품 SHIPPED, 하나는 200 */
    @Test
    void 같은_요청_id_동시_두_번은_한_번만_나간다() throws Exception {
        long lot = receive();
        String no = confirmed(lot);
        String r = req();
        List<String> out = doubleIssue(no, r);
        assertThat(out.stream().filter(x -> x.startsWith("200 "))).as(out.toString()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(rtnRows(no)).isEqualTo(1);
        assertThat(stock()).isEqualByComparingTo("30");
        assertThat(ret(no)).isEqualTo("SHIPPED:RTN_ISS:" + r);
    }

    /**
     * QR-1: 같은 요청 id 는 처음 결과가 규칙(ReturnIssueService 주석, PQ-1)인데, 동시에 둘이면 늦은 쪽이 반품 잠금을 기다린 뒤
     * 게이트에서 409 SRM_STATE("지금 상태에서는 할 수 없습니다") — 실제로 나간 출고를 사용자는 실패로 본다. PDA 이동(replayMove)처럼 다시 불러 처음 결과로.
     */
    @Test
    void 결함QR1_같은_요청_id_동시_두_번은_둘_다_처음_결과() throws Exception {
        long lot = receive();
        String no = confirmed(lot);
        List<String> out = doubleIssue(no, req());
        assertThat(out.stream().filter(x -> x.startsWith("200 "))).as(out.toString()).hasSize(2);
        assertThat(rtnRows(no)).isEqualTo(1);
    }

    /** C — 두 사람이 같은 출고 원장 행을 동시에 역분개(둘 다 대기 확인 후) → 하나 200·하나 4xx, 반품 확정 한 번, 재고 원복 한 번 */
    @Test
    void 두_사람이_같은_출고를_동시에_역분개() throws Exception {
        long lot = receive();
        String no = confirmed(lot);
        MvcResult ok = postJson(u1, RTN, issueBody(no, req())).andExpect(status().isOk()).andReturn();
        long t = ((Number) JsonPath.read(ok.getResponse().getContentAsString(), "$.data.trxIds[0]")).longValue();
        List<String> out = concurrent("select 1 from srm_return where return_no = ? for update", no,
                List.of(call(u1, "/api/wms/trx/" + t + "/reverse", "{\"reason\":\"잘못 출고\"}"),
                        call(u2, "/api/wms/trx/" + t + "/reverse", "{\"reason\":\"잘못 출고\"}")));
        assertThat(out.stream().filter(x -> x.startsWith("200 "))).as(out.toString()).hasSize(1);
        assertThat(out.stream().filter(x -> x.matches("^4\\d\\d .*"))).as(out.toString()).hasSize(1);
        assertThat(ret(no)).isEqualTo("CONFIRMED:-");
        assertThat(stock()).isEqualByComparingTo("40");
        assertThat(rtnRows(no)).isEqualTo(2);
    }

    /** 역분개 왕복 두 번 — 출고→되돌림→출고→되돌림, 매번 SRM 상태·재고·원장이 맞고, 역분개 행을 다시 되돌리면 거부 */
    @Test
    void 역분개_왕복_두_번과_역분개의_역분개() throws Exception {
        long lot = receive();
        String no = confirmed(lot);
        long last = 0;
        for (int round = 1; round <= 2; round++) {
            MvcResult ok = postJson(u1, RTN, issueBody(no, req())).andExpect(status().isOk()).andReturn();
            long t = ((Number) JsonPath.read(ok.getResponse().getContentAsString(), "$.data.trxIds[0]")).longValue();
            assertThat(ret(no)).startsWith("SHIPPED:");
            assertThat(stock()).isEqualByComparingTo("30");
            postJson(u1, "/api/wms/trx/" + t + "/reverse", "{\"reason\":\"왕복 " + round + "\"}").andExpect(status().isOk());
            assertThat(ret(no)).isEqualTo("CONFIRMED:-");
            assertThat(stock()).isEqualByComparingTo("40");
            last = accounts.query(comp, "select trx_id from inv_trx where reversal_of_id = ?", Long.class, t);
        }
        assertThat(rtnRows(no)).isEqualTo(4);
        MvcResult r = postJson(u1, "/api/wms/trx/" + last + "/reverse", "{\"reason\":\"역분개의 역분개\"}").andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(400, 499);
        assertThat(ret(no)).isEqualTo("CONFIRMED:-");
        assertThat(stock()).isEqualByComparingTo("40");
    }

    /**
     * 앱 계정으로 커밋 검사(지연 제약 트리거) 우회 시도 — ① SET CONSTRAINTS ALL IMMEDIATE 로 당겨도 그 자리에서 거부
     * ② session_replication_role = replica(트리거 끄기)·ALTER TABLE … DISABLE TRIGGER 는 권한 없음(42501). 반품·재고 그대로.
     */
    @Test
    void 앱_계정으로_커밋_검사_우회_시도는_막힌다() throws Exception {
        long lot = receive();
        String no = confirmed(lot);
        Throwable immediate = catchThrowable(() -> tx(() -> {
            jdbc.execute("set constraints all immediate");
            jdbc.update("update srm_return set status = 'SHIPPED', shipped_at = now(), ship_ref = 'RTN_ISS:QA-FAKE' where return_no = ?", no);
            return null;
        }));
        assertThat(PgErrors.constraint(immediate).orElse(String.valueOf(immediate))).isEqualTo("ck_srm_return_shipped_ledger");
        for (String sql : List.of("set session_replication_role = replica", "alter table srm_return disable trigger all",
                "alter table srm_return disable trigger tg_srm_return_shipped_ledger")) {
            Throwable t = catchThrowable(() -> tx(() -> {
                jdbc.execute(sql);
                jdbc.update("update srm_return set status = 'SHIPPED', shipped_at = now(), ship_ref = 'RTN_ISS:QA-FAKE2' where return_no = ?", no);
                return null;
            }));
            assertThat(t).as(sql).isNotNull();
            assertThat(PgErrors.sqlState(t).orElse("?")).as(sql + " — " + t.getMessage()).isEqualTo("42501");
        }
        assertThat(ret(no)).isEqualTo("CONFIRMED:-");
        assertThat(stock()).isEqualByComparingTo("40");
    }
}
