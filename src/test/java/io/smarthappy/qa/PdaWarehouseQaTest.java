package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.master.workforce.WorkerService;
import io.smarthappy.module.pop.PopAuthService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * QA — WMS 창고 PDA(B7-1, docs/30 12.12, main #365) 이상 사용. 모듈 시험(PdaWarehouseTest)이 다루지 않는 것만:
 * 같은 clientReqId 동시 두 번(재고 전량, 둘 다 원장 앞에서 기다린 것 확인 후) / 같은 id 다른 내용·다른 출처 행(PQ-1 사용자 결정 — 409) /
 * 작업자 사용 중지 뒤 같은 세션 / 실사 줄 섞어 보내기·시작 전 실사 / 발생 시각 미래·형식 / CSRF 없음 / 단말 쿠키로 사무 API.
 */
@AutoConfigureMockMvc
class PdaWarehouseQaTest extends PostgresTestBase {

    static final String PW = "Qa-ledger-hold-41";
    static final String WH = "/api/pop/wh";
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
    String ip;
    RequestPostProcessor admin;
    long wh;
    long a1;
    long a2;
    long item;
    long worker;
    Cookie pda;
    MockHttpSession s;

    @AfterAll
    static void shutdown() {
        POOL.shutdownNow();
    }

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("QP");
        ip = "10.91." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        long u = accounts.newUser(comp, "qa1", PW);
        accounts.grant(comp, u, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, u, "ADM", "wms-count", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, u, "ADM", "wms-stock", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "qa1");
        accounts.addPlant(comp, "P1", "1공장");
        wh = node("P1", null, "WH", "WH");
        a1 = node("P1", wh, "LOC", "QA-01");
        a2 = node("P1", wh, "LOC", "QA-02");
        item = accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'QA-SUB', 'QA 부품', 'SUB', 'BUY', 'EA', 'N', 't', 't') returning item_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', 10, 'EA', 'MANUAL', now() - interval '1 hour', 'KEY', 't')""", comp, item, a1);
        worker = accounts.query(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'QW-1', 'QA 작업자', 'P1', ?, 't', 't') returning worker_id""", Long.class, comp,
                WorkerService.badgeHash(comp, "QA-BADGE-1"));
        pda = terminal("QPDA-1");
        s = badgeIn();
    }

    // ------------------------------------------------------------------ 도우미

    long node(String plant, Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, plant, parent, type, cd, cd);
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    Cookie terminal(String cd) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\",\"terminalType\":\"PDA\"}").with(admin)).andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        MvcResult a = mvc.perform(json(post("/api/pop/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return new Cookie(PopAuthService.COOKIE, set.substring(set.indexOf('=') + 1, set.indexOf(';')));
    }

    MockHttpSession badgeIn() throws Exception {
        MockHttpSession x = new MockHttpSession();
        mvc.perform(json(post("/api/pop/badge"), "{\"badge\":\"QA-BADGE-1\"}").cookie(pda).session(x)).andExpect(status().isOk());
        return x;
    }

    ResultActions pdaPost(MockHttpSession x, String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).cookie(pda).session(x));
    }

    String move(String req, long from, long to, String qty) {
        return "{\"clientReqId\":\"" + req + "\",\"itemId\":" + item + ",\"stockStatus\":\"AVAILABLE\",\"fromNodeId\":" + from
                + ",\"toNodeId\":" + to + ",\"qty\":\"" + qty + "\",\"entryMethod\":\"SCAN\"}";
    }

    BigDecimal stock(long node) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and item_id = ?", BigDecimal.class, node, item);
    }

    int ledgerRows(String req) {
        return accounts.query(comp, "select count(*) from inv_trx where client_req_id = ?", Integer.class, req);
    }

    int lockWaiters() {
        return accounts.query(comp, "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()",
                Integer.class);
    }

    long countStarted() throws Exception {
        MvcResult m = mvc.perform(json(post("/api/wms/counts"), "{\"plantCd\":\"P1\",\"countType\":\"CYCLE\",\"scopeNodeId\":" + wh + "}")
                .with(admin)).andExpect(status().isOk()).andReturn();
        long id = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.head.countId")).longValue();
        mvc.perform(json(post("/api/wms/counts/" + id + "/start"), "{\"rowVer\":" + countVer(id) + "}").with(admin))
                .andExpect(status().isOk());
        return id;
    }

    int countVer(long id) {
        return accounts.query(comp, "select row_ver from wms_count where count_id = ?", Integer.class, id);
    }

    long line(long id, long node) {
        return accounts.query(comp, "select max(line_id) from wms_count_line where count_id = ? and node_id = ?", Long.class, id, node);
    }

    // ------------------------------------------------------------------ 시험

    /**
     * C — 오프라인 대기열이 같은 이동(재고 전량 10)을 두 단말 세션에서 동시에 다시 보냄. 다른 트랜잭션이 재고 행을 잡은 동안 둘 다 원장 앞에서
     * 기다리는 것을 확인한 뒤 놓는다 → 둘 다 200·같은 원장 행, 하나만 replay=false, 원장 한 쌍, 재고 한 번만(둘째가 "재고 부족" 409 가 되면
     * 단말은 실제로 된 이동을 거부 목록에 남긴다).
     */
    @Test
    void 같은_요청_id_동시_두_번은_한_번만_움직이고_둘_다_같은_결과() throws Exception {
        String req = "QA-" + UUID.randomUUID();
        List<String> out = concurrentMoves(List.of(move(req, a1, a2, "10"), move(req, a1, a2, "10")));
        assertThat(out).allMatch(x -> x.startsWith("200 "));
        assertThat(out.stream().map(x -> x.substring(x.indexOf('['))).distinct()).as("같은 원장 행").hasSize(1);
        assertThat(out.stream().filter(x -> x.startsWith("200 false"))).hasSize(1);
        assertThat(ledgerRows(req)).isEqualTo(2);
        assertThat(stock(a1)).isEqualByComparingTo("0");
        assertThat(stock(a2)).isEqualByComparingTo("10");
    }

    /**
     * C — PQ-1 의 동시 경로: 같은 clientReqId 로 내용이 다른 두 이동(4·6)이 동시에(둘 다 원장 앞 대기 확인 후). 늦은 쪽은 unique 에 걸려
     * replayMove 로 다시 불리고, 먼저 들어간 행과 내용이 달라 409 — 처음 결과로 200 이 되지 않는다. 원장 한 쌍, 이긴 쪽 수량만 움직임.
     */
    @Test
    void 같은_요청_id_다른_내용_동시_두_번은_하나만_200_나머지_409() throws Exception {
        String req = "QA-" + UUID.randomUUID();
        List<String> out = concurrentMoves(List.of(move(req, a1, a2, "4"), move(req, a1, a2, "6")));
        assertThat(out.stream().filter(x -> x.startsWith("200 false"))).as(out.toString()).hasSize(1);
        assertThat(out.stream().filter(x -> x.startsWith("409 ") && x.contains("DUPLICATE_REQUEST"))).as(out.toString()).hasSize(1);
        assertThat(ledgerRows(req)).isEqualTo(2);
        BigDecimal moved = stock(a2);
        assertThat(moved.stripTrailingZeros().toPlainString()).isIn("4", "6");
        assertThat(stock(a1).add(moved)).isEqualByComparingTo("10");
    }

    /** 다른 트랜잭션이 재고 행을 잡은 동안 이동들을 세션마다 동시에 보내고, 모두 잠금 대기인 것을 확인한 뒤 놓는다 → "상태 replay trxIds" 또는 "상태 본문" */
    List<String> concurrentMoves(List<String> bodies) throws Exception {
        List<MockHttpSession> sessions = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) {
            sessions.add(i == 0 ? s : badgeIn());
        }
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        Future<?> holder = POOL.submit(() -> runner.call(comp, () -> new TransactionTemplate(txm).execute(st -> {
            jdbc.queryForList("select 1 from inv_stock where item_id = ? for update", item);
            held.countDown();
            try {
                go.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        })));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        int before = lockWaiters();
        List<CompletableFuture<String>> reqs = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) {
            MockHttpSession x = sessions.get(i);
            String body = bodies.get(i);
            reqs.add(CompletableFuture.supplyAsync(() -> {
                try {
                    MvcResult m = pdaPost(x, WH + "/moves", body).andReturn();
                    String b = m.getResponse().getContentAsString();
                    return m.getResponse().getStatus() + (m.getResponse().getStatus() == 200
                            ? " " + JsonPath.read(b, "$.data.replay") + " " + JsonPath.read(b, "$.data.trxIds") : " " + b);
                } catch (Exception e) {
                    return "EX " + e;
                }
            }, POOL));
        }
        for (int i = 0; i < 200 && lockWaiters() - before < bodies.size(); i++) {
            Thread.sleep(50);
        }
        int waiting = lockWaiters() - before;
        go.countDown();
        holder.get(10, TimeUnit.SECONDS);
        List<String> out = new ArrayList<>();
        for (CompletableFuture<String> r : reqs) {
            out.add(r.get(30, TimeUnit.SECONDS));
        }
        assertThat(waiting).as("모두 원장 앞에서 기다림").isEqualTo(bodies.size());
        return out;
    }

    /**
     * PQ-1(사용자 결정 2026-10-23 — 같은 clientReqId 는 같은 내용일 때만 처음 결과): ① 같은 id 로 수량·방향을 바꿔 보내면 409 DUPLICATE_REQUEST,
     * 아무것도 움직이지 않는다 ② 회사 안 다른 출처(사무 화면·다른 단말)의 원장 행이 같은 id 를 쓰고 있으면 PDA 이동도 409.
     * 같은 내용 재전송은 처음 결과(replay=true) 그대로.
     */
    @Test
    void 같은_요청_id_다른_내용과_다른_출처_행은_409() throws Exception {
        String req = "QA-" + UUID.randomUUID();
        MvcResult first = pdaPost(s, WH + "/moves", move(req, a1, a2, "3")).andExpect(status().isOk()).andReturn();
        String ids = JsonPath.read(first.getResponse().getContentAsString(), "$.data.trxIds").toString();
        pdaPost(s, WH + "/moves", move(req, a1, a2, "3")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay").value(true))
                .andExpect(r -> assertThat(JsonPath.read(r.getResponse().getContentAsString(), "$.data.trxIds").toString()).isEqualTo(ids));
        pdaPost(s, WH + "/moves", move(req, a2, a1, "1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        assertThat(stock(a1)).as("둘째(반대로 1)는 일어나지 않음").isEqualByComparingTo("7");
        assertThat(ledgerRows(req)).isEqualTo(2);
        // 다른 출처: 사무 쪽 조정 행이 같은 id 를 이미 썼다(직접 넣어 흉내)
        String other = "QA-OTHER-" + UUID.randomUUID();
        accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by, client_req_id, req_seq)
                values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', 1, 'EA', 'MANUAL', now(), 'KEY', 'office', ?, 1)""", comp, item, a2, other);
        pdaPost(s, WH + "/moves", move(other, a1, a2, "2")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        assertThat(stock(a1)).as("PDA 이동 2 는 일어나지 않음").isEqualByComparingTo("7");
        assertThat(ledgerRows(other)).isEqualTo(1);
    }

    /** 배지 뒤 작업자를 사용 중지 → 같은 세션의 다음 이동은 작업자 없음(428), 재고 그대로 */
    @Test
    void 작업자_사용_중지_뒤_같은_세션은_거부() throws Exception {
        pdaPost(s, WH + "/moves", move("QA-" + UUID.randomUUID(), a1, a2, "1")).andExpect(status().isOk());
        accounts.exec(comp, "update mst_worker set use_yn = 'N' where worker_id = ?", worker);
        pdaPost(s, WH + "/moves", move("QA-" + UUID.randomUUID(), a1, a2, "1")).andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("POP_NO_WORKER"));
        assertThat(stock(a1)).isEqualByComparingTo("9");
    }

    /** 실사 A 의 머리(rowVer)로 실사 B 의 줄을 섞어 보냄 → 거부, B 의 줄은 그대로 / 시작 전(DRAFT) 실사 → 거부 */
    @Test
    void 다른_실사_줄_섞기와_시작_전_실사는_거부() throws Exception {
        long ca = countStarted();
        // 같은 공장 다른 위치 범위의 실사 B
        long whB = node("P1", null, "WH", "WHB");
        long b1 = node("P1", whB, "LOC", "QB-01");
        accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', 4, 'EA', 'MANUAL', now() - interval '1 hour', 'KEY', 't')""", comp, item, b1);
        MvcResult m = mvc.perform(json(post("/api/wms/counts"), "{\"plantCd\":\"P1\",\"countType\":\"CYCLE\",\"scopeNodeId\":" + whB + "}")
                .with(admin)).andExpect(status().isOk()).andReturn();
        long cb = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.head.countId")).longValue();
        mvc.perform(json(post("/api/wms/counts/" + cb + "/start"), "{\"rowVer\":" + countVer(cb) + "}").with(admin)).andExpect(status().isOk());
        long lineB = line(cb, b1);
        int verB = countVer(cb);
        MvcResult r = pdaPost(s, WH + "/counts/" + ca + "/counts", "{\"rowVer\":" + countVer(ca) + ",\"rows\":[{\"lineId\":" + lineB
                + ",\"countedQty\":\"99\"}]}").andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(400, 499);
        assertThat(accounts.query(comp, "select counted_qty from wms_count_line where line_id = ?", BigDecimal.class, lineB)).isNull();
        assertThat(countVer(cb)).isEqualTo(verB);
        // 시작 전 실사
        MvcResult d = mvc.perform(json(post("/api/wms/counts"), "{\"plantCd\":\"P1\",\"countType\":\"CYCLE\",\"scopeNodeId\":" + wh + "}")
                .with(admin)).andExpect(status().isOk()).andReturn();
        long draft = ((Number) JsonPath.read(d.getResponse().getContentAsString(), "$.data.head.countId")).longValue();
        MvcResult dr = pdaPost(s, WH + "/counts/" + draft + "/counts", "{\"rowVer\":" + countVer(draft) + ",\"rows\":[]}").andReturn();
        assertThat(dr.getResponse().getStatus()).as(dr.getResponse().getContentAsString()).isBetween(400, 499);
    }

    /** 발생 시각(D-B23): 미래(허용 넘음)·형식 오류는 400 칸 오류, 500 아님. 재고 그대로 */
    @Test
    void 발생_시각_미래와_형식_오류는_400() throws Exception {
        String future = accounts.query(comp, "select to_char((now() at time zone 'Asia/Seoul') + interval '2 hours', 'YYYY-MM-DD\"T\"HH24:MI')",
                String.class);
        for (String at : List.of(future, "2026-13-40T99:99", "어제", "2026-10-23 10:00")) {
            String body = move("QA-" + UUID.randomUUID(), a1, a2, "1").replace("{", "{\"occurredAt\":\"" + at + "\",");
            pdaPost(s, WH + "/moves", body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("occurredAt"));
        }
        assertThat(stock(a1)).isEqualByComparingTo("10");
    }

    /** CSRF 토큰 없는 이동 → 403 / 단말 쿠키로 사무 재고 API → 거부(401·403), 재고 그대로 */
    @Test
    void CSRF_없음과_단말_쿠키로_사무_API는_거부() throws Exception {
        mvc.perform(post(WH + "/moves").contentType(MediaType.APPLICATION_JSON).content(move("QA-" + UUID.randomUUID(), a1, a2, "1"))
                .cookie(pda).session(s)).andExpect(status().isForbidden());
        int code = mvc.perform(get("/api/wms/stock?plantCd=P1").cookie(pda).session(s)).andReturn().getResponse().getStatus();
        assertThat(code).isIn(401, 403);
        int moveCode = mvc.perform(json(post("/api/wms/move/move"), "{\"plantCd\":\"P1\",\"itemId\":" + item + ",\"fromNodeId\":" + a1
                + ",\"fromStatus\":\"AVAILABLE\",\"toNodeId\":" + a2 + ",\"toStatus\":\"AVAILABLE\",\"qty\":1}").cookie(pda).session(s))
                .andReturn().getResponse().getStatus();
        assertThat(moveCode).isIn(401, 403);
        assertThat(stock(a1)).isEqualByComparingTo("10");
    }
}
