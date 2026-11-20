package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.production.link.LotHoldPort;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA 2026-10-22 ② — MES lot_hold(V109) + QMS Q8a 부적합·Q8b 봉쇄(LotHoldPort) 경계를 실제 POP·WMS 와 함께.
 * 모듈 시험(LotHoldTest·NcrApiTest·ContainApiTest)은 각 모듈 안 동시성을 본다 — 여기서는 "보류가 커밋되기 전에 들어온 다른 모듈의 출고·투입",
 * MES 화면의 QMS 근거 해제, NCR 취소 ↔ 봉쇄 해제 동시. 분석 docs/qa/2026-10-22_module-boundary.md.
 */
@AutoConfigureMockMvc
class LotHoldBoundaryQaTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectProvider<LotHoldPort> port;

    QaPopFixture f;
    long defect;

    @BeforeEach
    void setUp() throws Exception {
        f = new QaPopFixture(mvc, accounts, "QH");
        accounts.grant(f.comp, f.userId, "QA", "qms-ncr", "SEARCH", "SAVE", "DELETE");
        accounts.grant(f.comp, f.userId, "QA", "qms-contain", "SEARCH", "SAVE");
        defect = accounts.query(f.comp, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'D-QA', '외관', 'APPEARANCE', 't', 't') returning defect_id""", Long.class, f.comp);
    }

    private String lotState(long lotId) {
        return accounts.query(f.comp, """
                select l.hold_yn || '/' || coalesce((select string_agg(h.source, ',' order by h.hold_id)
                  from lot_hold h where h.lot_id = l.lot_id and h.released_at is null), '') from lot_master l where l.lot_id = ?""",
                String.class, lotId);
    }

    private long ncr(String lotNo) throws Exception {
        MvcResult m = mvc.perform(QaPopFixture.json(post("/api/qms/ncrs"), "{\"lotNo\":\"" + lotNo + "\",\"source\":\"CLAIM\",\"defectId\":"
                + defect + ",\"ncQty\":1,\"description\":\"QA\"}").with(f.admin)).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private long contain(long lotId) throws Exception {
        String today = accounts.query(f.comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        MvcResult m = mvc.perform(QaPopFixture.json(post("/api/qms/contains"), "{\"itemCd\":\"RM\",\"fromDate\":\"" + today + "\",\"toDate\":\""
                + today + "\",\"reason\":\"같은 금형\",\"lotIds\":[" + lotId + "]}").with(f.admin)).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private int rowVer(long lotId) {
        return accounts.query(f.comp, "select row_ver from lot_master where lot_id = ?", Integer.class, lotId);
    }

    private int lockWaiters() {
        return accounts.query(f.comp, "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()",
                Integer.class);
    }

    private void awaitLockWaiters(int n) throws InterruptedException {
        for (int i = 0; i < 200 && lockWaiters() < n; i++) {
            Thread.sleep(50);
        }
        assertThat(lockWaiters()).isGreaterThanOrEqualTo(n);
    }

    @Test
    void MES_화면은_QMS_근거를_풀지_못하고_MANUAL_해제도_QMS_근거를_남긴다() throws Exception {
        String rm = f.receive("RM", "10");
        long lot = f.lotId(rm);
        long n = ncr(rm);
        assertThat(lotState(lot)).isEqualTo("Y/QMS_NCR");
        long holdId = accounts.query(f.comp, "select hold_id from lot_hold where lot_id = ? and source = 'QMS_NCR'", Long.class, lot);
        // QMS 근거를 MES 화면 해제 API 로 → 칸 오류 HOLD_SOURCE
        mvc.perform(QaPopFixture.json(post("/api/production/lots/" + lot + "/holds/" + holdId + "/release"),
                "{\"reason\":\"현장 판단\",\"rowVer\":" + rowVer(lot) + "}").with(f.admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("HOLD_SOURCE"));
        // MANUAL 해제(holdYn N)는 MANUAL 근거가 없으면 STATE — QMS 근거는 그대로
        mvc.perform(QaPopFixture.json(put("/api/production/lots/" + lot + "/hold"), "{\"holdYn\":\"N\",\"reason\":\"풀기\",\"rowVer\":"
                + rowVer(lot) + "}").with(f.admin)).andExpect(jsonPath("$.errors[0].code").value("STATE"));
        mvc.perform(QaPopFixture.json(put("/api/production/lots/" + lot + "/hold"), "{\"holdYn\":\"Y\",\"reason\":\"현장 의심\",\"rowVer\":"
                + rowVer(lot) + "}").with(f.admin)).andExpect(status().isOk());
        mvc.perform(QaPopFixture.json(put("/api/production/lots/" + lot + "/hold"), "{\"holdYn\":\"N\",\"reason\":\"현장 확인\",\"rowVer\":"
                + rowVer(lot) + "}").with(f.admin)).andExpect(status().isOk());
        assertThat(lotState(lot)).isEqualTo("Y/QMS_NCR");
        // 보류 중 WMS 이동 출고·POP 투입 거부
        mvc.perform(QaPopFixture.json(post("/api/wms/move/move"), "{\"plantCd\":\"P1\",\"itemId\":" + itemId("RM") + ",\"fromNodeId\":" + f.a1
                + ",\"fromStatus\":\"AVAILABLE\",\"toNodeId\":" + f.a1 + ",\"toStatus\":\"QC\",\"qty\":1,\"lotNo\":\"" + rm + "\"}").with(f.admin))
                .andExpect(status().isOk());       // 상태 이동(AVAILABLE → QC, 같은 칸)은 출고가 아니다 — STATUS_OUT/IN
        f.start(f.pop1, f.spot);
        f.result(f.pop1, f.spot, QaPopFixture.req(), "\"goodQty\":\"1\",\"inputs\":[{\"lotNo\":\"" + rm + "\",\"qty\":\"1\"}]")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("lotNo"))
                .andExpect(jsonPath("$.errors[0].code").value("LOT_HOLD"));
        // NCR 취소 → 그 근거만 풀려 LOT 해제
        mvc.perform(QaPopFixture.json(post("/api/qms/ncrs/" + n + "/cancel"), "{\"rowVer\":" + accounts.query(f.comp,
                "select row_ver from qms_ncr where ncr_id = ?", Integer.class, n) + ",\"reason\":\"오등록\"}").with(f.admin)).andExpect(status().isOk());
        assertThat(lotState(lot)).isEqualTo("N/");
    }

    /** 보류가 커밋되기 전에 들어온 요청 — 보류 트랜잭션이 LOT 을 쥔 것을 확인(잠금 대기)한 뒤 커밋하고, 요청 결과를 본다 */
    private int whileHolding(long lot, Supplier<Integer> request) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CompletableFuture<Void> holder = asyncRun(() -> runner.run(f.comp, () -> {
            LotHoldPort.of(port).hold(lot, LotHoldPort.QMS_NCR, 990001L, "커밋 전 보류", "qa");
            held.countDown();
            try {
                commit.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        int before = lockWaiters();
        CompletableFuture<Integer> req = async(request);
        awaitLockWaiters(before + 1);              // 요청이 LOT 잠금에서 기다리는 것을 본 뒤에 커밋
        commit.countDown();
        holder.get(10, TimeUnit.SECONDS);
        return req.get(20, TimeUnit.SECONDS);
    }

    @Test
    void 보류_커밋_전에_들어온_POP_투입은_기다린_뒤_LOT_HOLD_로_거부되고_산출_LOT_도_남지_않는다() throws Exception {
        String rm = f.receive("RM", "10");
        long lot = f.lotId(rm);
        f.start(f.pop1, f.spot);
        int code = whileHolding(lot, () -> {
            try {
                return f.result(f.pop1, f.spot, QaPopFixture.req(), "\"goodQty\":\"1\",\"inputs\":[{\"lotNo\":\"" + rm + "\",\"qty\":\"2\"}]")
                        .andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(code).isEqualTo(400);
        assertThat(f.count("select count(*) from lot_master where wo_id = ?", f.wo1)).isZero();
        assertThat(f.count("select count(*) from inv_trx where src_type = 'LOT_GEN'")).isZero();
        assertThat(f.stock("RM", rm)).isEqualByComparingTo("10");
    }

    @Test
    void 보류_커밋_전에_들어온_WMS_이동_출고는_원장_트리거가_기다린_뒤_LOT_HOLD() throws Exception {
        String rm = f.receive("RM", "10");
        long lot = f.lotId(rm);
        long a2 = f.node(accounts.query(f.comp, "select parent_id from mst_equip_hier where node_id = ?", Long.class, f.a1), "LOC", "A-02");
        accounts.exec(f.comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, pick_seq, created_by, updated_by) "
                + "values (?, ?, 'STORAGE', 2, 't', 't')", a2, f.comp);
        MvcResult[] out = new MvcResult[1];
        whileHolding(lot, () -> {
            try {
                out[0] = mvc.perform(QaPopFixture.json(post("/api/wms/move/move"), "{\"plantCd\":\"P1\",\"itemId\":" + itemId("RM")
                        + ",\"fromNodeId\":" + f.a1 + ",\"fromStatus\":\"AVAILABLE\",\"toNodeId\":" + a2 + ",\"toStatus\":\"AVAILABLE\",\"qty\":3,"
                        + "\"lotNo\":\"" + rm + "\"}").with(f.admin)).andReturn();
                return out[0].getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(out[0].getResponse().getStatus()).isEqualTo(400);
        assertThat(out[0].getResponse().getContentAsString()).contains("LOT_HOLD");
        assertThat(f.stock("RM", rm)).isEqualByComparingTo("10");
    }

    @Test
    void NCR_등록_취소와_봉쇄_해제를_같은_LOT_에_동시에_해도_교착_없이_근거대로_남는다() throws Exception {
        String rm = f.receive("RM", "10");
        long lot = f.lotId(rm);
        long n1 = ncr(rm);
        long c = contain(lot);
        assertThat(lotState(lot)).isEqualTo("Y/QMS_NCR,QMS_CONTAIN");
        int n1Ver = accounts.query(f.comp, "select row_ver from qms_ncr where ncr_id = ?", Integer.class, n1);
        // 다른 트랜잭션이 LOT 을 쥔 동안 세 요청(NCR 취소·봉쇄 해제·새 NCR 등록)을 보내 모두 LOT 에서 기다리는 것을 본 뒤 놓는다
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        CompletableFuture<Void> holder = asyncRun(() -> runner.run(f.comp, () -> {
            jdbc.queryForObject("select lot_id from lot_master where lot_id = ? for update", Long.class, lot);
            held.countDown();
            try {
                go.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        int before = lockWaiters();
        List<CompletableFuture<Integer>> reqs = List.of(
                async(() -> code(post("/api/qms/ncrs/" + n1 + "/cancel"), "{\"rowVer\":" + n1Ver + ",\"reason\":\"오등록\"}")),
                async(() -> code(post("/api/qms/contains/" + c + "/release"),
                        "{\"reason\":\"선별 OK\",\"lotIds\":[" + lot + "]}")),
                async(() -> code(post("/api/qms/ncrs"), "{\"lotNo\":\"" + rm + "\",\"source\":\"CLAIM\",\"defectId\":"
                        + defect + ",\"ncQty\":1,\"description\":\"재발\"}")));
        for (int i = 0; i < 100 && lockWaiters() < before + 3; i++) {
            Thread.sleep(50);
        }
        int waiting = lockWaiters() - before;
        go.countDown();
        holder.get(10, TimeUnit.SECONDS);
        List<Integer> codes = new java.util.ArrayList<>();
        for (CompletableFuture<Integer> r : reqs) {
            codes.add(r.get(30, TimeUnit.SECONDS));
        }
        assertThat(codes).as("[NCR 취소, 봉쇄 해제, NCR 등록] — LOT 에서 기다린 요청 " + waiting).containsExactly(200, 200, 200);
        assertThat(waiting).isEqualTo(3);
        assertThat(lotState(lot)).isEqualTo("Y/QMS_NCR");
        assertThat(accounts.query(f.comp, "select status from qms_contain where contain_id = ?", String.class, c)).isEqualTo("CLOSED");
        assertThat(f.count("select count(*) from lot_hold where lot_id = ? and source = 'QMS_NCR' and released_at is null and ref_id <> ?",
                lot, n1)).isEqualTo(1);
    }

    private Integer code(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, String body) {
        try {
            return mvc.perform(QaPopFixture.json(b, body).with(f.admin)).andReturn().getResponse().getStatus();
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
