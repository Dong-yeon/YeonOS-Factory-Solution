package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import io.smarthappy.module.production.LotService;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA 2026-10-22 ① — WMS B5 투입 차감·LOT 변환(LotStockPosting) + MES 훅(#180)을 **실제 POP API 와 실제 WMS 빈**으로 잇는다.
 * 모듈 시험은 한쪽을 가짜로 둔다(PopWorkTest = 409 를 스파이로 흉내, LotStockPostingTest = MES 를 SQL 로 흉내). 분석 docs/qa/2026-10-22_module-boundary.md.
 */
@AutoConfigureMockMvc
class PopWmsStockLinkQaTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    /** 실제 빈을 감싼 스파이 — 동시성 시험에서만 첫 투입 뒤 멈춤을 끼운다(그 밖에는 실제 동작) */
    @MockitoSpyBean
    LotService lots;

    QaPopFixture f;

    @BeforeEach
    void setUp() throws Exception {
        f = new QaPopFixture(mvc, accounts, "QB");
    }

    private int issueRows(String lotNo) {
        return f.count("""
                select count(*) from inv_trx t join lot_genealogy g on g.gen_id = t.src_id
                 where t.src_type = 'LOT_GEN' and t.trx_type = 'ISSUE_WO' and t.reversal_of_id is null
                   and g.parent_lot_id = (select lot_id from lot_master where lot_no = ?)""", lotNo);
    }

    @Test
    void 실제_POP_실적_투입은_ISSUE_WO_차감_기록자는_단말_작업자는_계보와_같다() throws Exception {
        String rm = f.receive("RM", "20");
        f.start(f.pop1, f.spot);
        f.result(f.pop1, f.spot, QaPopFixture.req(), "\"goodQty\":\"5\",\"inputs\":[{\"lotNo\":\"" + rm + "\",\"qty\":\"6\"}]")
                .andExpect(status().isOk());
        assertThat(f.stock("RM", rm)).isEqualByComparingTo("14");
        assertThat(issueRows(rm)).isEqualTo(1);
        // 원장의 기록자 = 단말(계보 recorded_by), 작업자 = 계보 performed_by, 발생 시각 = 계보 시각, 입력 방식 AUTO
        assertThat(f.count("""
                select count(*) from inv_trx t join lot_genealogy g on g.gen_id = t.src_id and t.src_type = 'LOT_GEN'
                 where g.parent_lot_id = (select lot_id from lot_master where lot_no = ?)
                   and t.recorded_by = g.recorded_by and t.recorded_by like 't%:TAB-1' and t.performed_by = g.performed_by
                   and t.occurred_at = g.occurred_at and t.entry_method = 'AUTO'""", rm)).isEqualTo(1);
    }

    @Test
    void 재고_부족_409_는_실적_산출LOT_계보_원장을_모두_되돌리고_같은_요청_id_로_다시_보낼_수_있다() throws Exception {
        String rm = f.receive("RM", "10");
        long rmId = f.lotId(rm);
        // 재고만 줄인다(LOT 잔량 10 · 재고 4) — 조정 출고
        f.pop(f.pop1, post("/api/pop/procs/" + f.spot + "/events/start"), "{\"clientReqId\":\"" + QaPopFixture.req() + "\",\"ack\":true}")
                .andExpect(status().isOk());
        mvc.perform(QaPopFixture.json(post("/api/wms/move/adjust"), "{\"plantCd\":\"P1\",\"nodeId\":" + f.a1 + ",\"itemId\":"
                + itemId("RM") + ",\"stockStatus\":\"AVAILABLE\",\"delta\":-6,\"reasonCd\":\"FOUND\",\"lotNo\":\"" + rm
                + "\"}").with(f.admin)).andExpect(status().isOk());
        String req = QaPopFixture.req();
        f.result(f.pop1, f.spot, req, "\"goodQty\":\"5\",\"inputs\":[{\"lotNo\":\"" + rm + "\",\"qty\":\"6\"}]")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        assertThat(f.count("select count(*) from prd_result where wo_proc_id = ?", f.spot)).isZero();
        assertThat(f.count("select count(*) from lot_master where wo_id = ?", f.wo1)).isZero();
        assertThat(f.count("select count(*) from lot_genealogy where parent_lot_id = ?", rmId)).isZero();
        assertThat(f.count("select count(*) from inv_trx where src_type = 'LOT_GEN'")).isZero();
        assertThat(f.stock("RM", rm)).isEqualByComparingTo("4");
        // POP 대기열은 409 를 보존했다가 다시 보낸다(docs/30 12.4.2) — 같은 요청 id 가 소모되지 않아야 재고를 채운 뒤 통과
        mvc.perform(QaPopFixture.json(post("/api/wms/move/adjust"), "{\"plantCd\":\"P1\",\"nodeId\":" + f.a1 + ",\"itemId\":" + itemId("RM")
                + ",\"stockStatus\":\"AVAILABLE\",\"delta\":2,\"reasonCd\":\"FOUND\",\"lotNo\":\"" + rm + "\"}").with(f.admin))
                .andExpect(status().isOk());
        f.result(f.pop1, f.spot, req, "\"goodQty\":\"5\",\"inputs\":[{\"lotNo\":\"" + rm + "\",\"qty\":\"6\"}]")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(false));
        assertThat(f.stock("RM", rm)).isEqualByComparingTo("0");
    }

    @Test
    void 실적_정정은_ISSUE_WO_를_역분개해_재고를_원래_칸으로_되돌리고_산출_LOT_은_보류() throws Exception {
        String rm = f.receive("RM", "20");
        f.start(f.pop1, f.spot);
        String req = QaPopFixture.req();
        f.result(f.pop1, f.spot, req, "\"goodQty\":\"5\",\"inputs\":[{\"lotNo\":\"" + rm + "\",\"qty\":\"6\"}]").andExpect(status().isOk());
        long rid = f.accounts.query(f.comp, "select result_id from prd_result where client_req_id = ?", Long.class, req);
        f.pop(f.pop1, post("/api/pop/results/" + rid + "/correct"), "{\"reason\":\"수량 잘못 입력\"}").andExpect(status().isOk());
        assertThat(f.stock("RM", rm)).isEqualByComparingTo("20");
        assertThat(f.count("""
                select count(*) from inv_trx r join inv_trx o on o.trx_id = r.reversal_of_id
                 where o.trx_type = 'ISSUE_WO' and r.entry_method = 'CORRECT' and r.remark = '수량 잘못 입력'
                   and r.recorded_by like 't%:TAB-1'""")).isEqualTo(1);
        assertThat(f.count("select count(*) from lot_master l join prd_result r on r.lot_id = l.lot_id where r.result_id = ? "
                + "and l.hold_yn = 'Y'", rid)).isEqualTo(1);
        // 같은 실적을 다시 정정 → STATE, 원장은 한 번만 되돌아갔다
        f.pop(f.pop1, post("/api/pop/results/" + rid + "/correct"), "{\"reason\":\"두 번\"}")
                .andExpect(jsonPath("$.errors[0].code").value("STATE"));
        assertThat(f.count("select count(*) from inv_trx where entry_method = 'CORRECT'")).isEqualTo(1);
    }

    @Test
    void 재공_LOT_다음_공정_투입은_재고를_건드리지_않고_실적은_된다_D_B14() throws Exception {
        String rm = f.receive("RM", "20");
        f.start(f.pop1, f.spot);
        String r1 = QaPopFixture.req();
        f.result(f.pop1, f.spot, r1, "\"goodQty\":\"5\",\"inputs\":[{\"lotNo\":\"" + rm + "\",\"qty\":\"5\"}]").andExpect(status().isOk());
        String wip = f.accounts.query(f.comp, "select l.lot_no from prd_result r join lot_master l on l.lot_id = r.lot_id "
                + "where r.client_req_id = ?", String.class, r1);
        f.start(f.pop1, f.insp);
        f.result(f.pop1, f.insp, QaPopFixture.req(), "\"goodQty\":\"3\",\"inputs\":[{\"lotNo\":\"" + wip + "\",\"qty\":\"3\"}]")
                .andExpect(status().isOk());
        assertThat(f.count("select count(*) from inv_trx where lot_id = ?", f.lotId(wip))).isZero();
        assertThat(f.count("select count(*) from inv_trx where src_type = 'LOT_GEN'")).isEqualTo(1);   // 자재 투입 1행만
    }

    @Test
    void 스위치는_회사별_B사_가_켜도_A사_공장_P1_은_꺼진_그대로() throws Exception {
        f.stockLink("N");
        String b = accounts.newComp("QBB");
        accounts.addPlant(b, "P1", "B공장");
        accounts.exec(b, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) "
                + "values (?, 'P1', 'WMS_STOCK_LINK', 'Y', 't', 't')", b);
        String rm = f.receive("RM", "10");
        f.start(f.pop1, f.spot);
        f.result(f.pop1, f.spot, QaPopFixture.req(), "\"goodQty\":\"1\",\"inputs\":[{\"lotNo\":\"" + rm + "\",\"qty\":\"3\"}]")
                .andExpect(status().isOk());
        assertThat(f.stock("RM", rm)).isEqualByComparingTo("10");
        assertThat(issueRows(rm)).isZero();
    }

    /**
     * 결함 QB-1 — POP **실적 정정**과 다른 라인의 **실적**이 같은 비관리 품목 두 가지(NUT·BOLT)를 서로 반대 순서로 지나가면 교착(40P01).
     * 비관리 품목은 재고 칸(inv_stock, LOT 없음)을 품목끼리 같이 쓰고, 투입 LOT 선잠금(lockForConsume)은 LOT 이 달라 줄을 세우지 못한다.
     * 정정은 투입 계보를 **계보 id 순**(= 원래 투입 행 순서 NUT → BOLT)으로 역분개하며 칸을 잡고, 실적은 **요청 투입 행 순서**(BOLT → NUT)로 잡는다.
     * (실적 ↔ 실적은 산출 LOT 채번 행 잠금이 회사 단위로 줄을 세워 교착이 나지 않는다 — 처음 가설, 같은 래치로 확인함.)
     * 재현: 스파이로 각 요청의 첫 칸 잠금(정정 = 첫 reverse, 실적 = 첫 consume) 뒤 두 요청이 모두 첫 칸을 쥘 때까지 기다린다("모두 관찰한 뒤 진행").
     * 기대: 둘 다 200. 고치기 전에는 한쪽이 PostgreSQL 교착 감지로 깨졌다 — MES 가 투입·되돌림을 (품목, LOT) 순으로 맞춰 해결(2026-10-22).
     */
    @Test
    void 결함QB1_실적_정정과_다른_라인_실적이_비관리_품목_둘을_반대_순서로_지나가도_교착_없음() throws Exception {
        String n1 = f.receive("NUT", "50");
        String n2 = f.receive("NUT", "50");
        String b1 = f.receive("BOLT", "50");
        String b2 = f.receive("BOLT", "50");
        f.start(f.pop1, f.spot);
        f.start(f.pop2, f.spot2);
        String r0 = QaPopFixture.req();
        f.result(f.pop1, f.spot, r0, "\"goodQty\":\"2\",\"inputs\":[{\"lotNo\":\"" + n1 + "\",\"qty\":\"2\"},{\"lotNo\":\"" + b1
                + "\",\"qty\":\"2\"}]").andExpect(status().isOk());
        long rid = f.accounts.query(f.comp, "select result_id from prd_result where client_req_id = ?", Long.class, r0);
        CountDownLatch firstHeld = new CountDownLatch(2);
        ThreadLocal<Integer> calls = ThreadLocal.withInitial(() -> 0);
        org.mockito.stubbing.Answer<Object> pause = inv -> {
            Object r = inv.callRealMethod();
            int n = calls.get() + 1;
            calls.set(n);
            if (n == 1) {
                firstHeld.countDown();
                firstHeld.await(5, TimeUnit.SECONDS);      // 고친 뒤(같은 순서)에는 한쪽이 첫 칸에서 기다리므로 시간 초과 후 그대로 진행
            }
            return r;
        };
        Mockito.doAnswer(pause).when(lots).consume(ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
        Mockito.doAnswer(pause).when(lots).reverse(ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.any());
        CompletableFuture<Integer> correct = async(() -> {
            try {
                return f.pop(f.pop1, post("/api/pop/results/" + rid + "/correct"), "{\"reason\":\"교착 시험\"}").andReturn().getResponse()
                        .getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        CompletableFuture<Integer> result = async(() -> postResult(f.pop2, f.spot2, b2, n2));
        List<Integer> codes = List.of(correct.get(30, TimeUnit.SECONDS), result.get(30, TimeUnit.SECONDS));
        assertThat(codes).as("[정정, 실적] 상태 코드").containsExactly(200, 200);
        assertThat(f.stock("NUT", null)).isEqualByComparingTo("98");
        assertThat(f.stock("BOLT", null)).isEqualByComparingTo("98");
    }

    /** 처음 가설(실적 ↔ 실적) — 산출 LOT 채번 행 잠금이 줄을 세워 교착 없음. 같은 래치로 확인(관찰, 통과) */
    @Test
    void 관찰_두_라인_실적끼리는_비관리_품목_반대_순서여도_채번_잠금으로_줄_서서_교착_없음() throws Exception {
        String n1 = f.receive("NUT", "50");
        String n2 = f.receive("NUT", "50");
        String b1 = f.receive("BOLT", "50");
        String b2 = f.receive("BOLT", "50");
        f.start(f.pop1, f.spot);
        f.start(f.pop2, f.spot2);
        CountDownLatch firstHeld = new CountDownLatch(2);
        java.util.concurrent.atomic.AtomicInteger timedOut = new java.util.concurrent.atomic.AtomicInteger();
        ThreadLocal<Integer> calls = ThreadLocal.withInitial(() -> 0);
        Mockito.doAnswer(inv -> {
            Object r = inv.callRealMethod();
            int n = calls.get() + 1;
            calls.set(n);
            if (n == 1) {
                firstHeld.countDown();
                if (!firstHeld.await(3, TimeUnit.SECONDS)) {
                    timedOut.incrementAndGet();
                }
            }
            return r;
        }).when(lots).consume(ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
        CompletableFuture<Integer> one = async(() -> postResult(f.pop1, f.spot, n1, b1));
        CompletableFuture<Integer> two = async(() -> postResult(f.pop2, f.spot2, b2, n2));
        assertThat(List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS))).containsExactly(200, 200);
        // 먼저 온 쪽의 래치가 시간 초과로 풀렸다 = 둘째 요청은 첫 투입 전(LOT 채번 행 upsert 잠금)에서 기다렸다
        assertThat(timedOut.get()).isEqualTo(1);
    }

    private Integer postResult(QaPopFixture.Pop p, long proc, String first, String second) {
        try {
            return f.result(p, proc, QaPopFixture.req(), "\"goodQty\":\"2\",\"inputs\":[{\"lotNo\":\"" + first + "\",\"qty\":\"2\"},"
                    + "{\"lotNo\":\"" + second + "\",\"qty\":\"2\"}]").andReturn().getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long itemId(String cd) {
        return accounts.query(f.comp, "select item_id from mst_item where item_cd = ?", Long.class, cd);
    }

    /** 공용 ForkJoin 풀은 코어 수에 따라 스레드가 1~2개라 동시 요청이 실제로 동시에 출발하지 않는다 — 전용 풀 */
    private static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newCachedThreadPool();

    private static <T> CompletableFuture<T> async(java.util.function.Supplier<T> s) {
        return CompletableFuture.supplyAsync(s, POOL);
    }

    private static CompletableFuture<Void> asyncRun(Runnable r) {
        return CompletableFuture.runAsync(r, POOL);
    }
}
