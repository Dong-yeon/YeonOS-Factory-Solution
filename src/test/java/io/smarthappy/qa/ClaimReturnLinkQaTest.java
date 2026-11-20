package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;

/**
 * QA — SRM 클레임 ↔ 반품 연결(#473, main #478) 이상 사용. 모듈 시험(ClaimReturnLinkTest)은 취소 한 건·다른 NCR·A사로 B사를 본다. 여기서는
 * 같은 NCR 에 여러 건(클레임 NCR 여럿 × 반품 줄 여럿 × 취소 뒤 다시) / 클레임 취소 뒤 새 클레임 / "한 NCR 은 살아 있는 클레임 하나" 규칙의 동시성(QC-1).
 * 행은 모듈 시험처럼 직접 넣는다(NCR 은 QMS 계약 값 스냅샷이라 FK 없음 — 서비스 검사는 각 서비스 시험).
 */
@AutoConfigureMockMvc
class ClaimReturnLinkQaTest extends PostgresTestBase {

    static final String PW = "Qa-claim-link-62";
    static final ExecutorService POOL = Executors.newCachedThreadPool();

    @Autowired
    MockMvc mvc;

    @Autowired
    io.smarthappy.support.TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    PlatformTransactionManager txm;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    RequestPostProcessor me;
    long cust;
    int seq;

    @AfterAll
    static void shutdown() {
        POOL.shutdownNow();
    }

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("QK");
        long u = accounts.newUser(comp, "buyer1", PW);
        accounts.grant(comp, u, "PUR", "srm-claim", "SEARCH");
        accounts.grant(comp, u, "PUR", "srm-return", "SEARCH");
        me = accounts.as(comp, u, "buyer1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'QK-BOLT', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't')""", comp);
        cust = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'QK-V', 'QK 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, comp);
        accounts.exec(comp, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')",
                cust, comp);
    }

    long claim(long... ncrIds) {
        long id = accounts.query(comp, """
                insert into srm_claim (comp_cd, claim_no, cust_id, action_cd, title, request_text, created_by, updated_by)
                values (?, ?, ?, 'CAPA', '녹', '원인·대책', 't', 't') returning claim_id""", Long.class, comp, "QK-CL-" + (++seq), cust);
        for (long n : ncrIds) {
            addNcr(id, n);
        }
        return id;
    }

    void addNcr(long claimId, long ncrId) {
        accounts.exec(comp, """
                insert into srm_claim_ncr (comp_cd, claim_id, ncr_id, ncr_no, item_id, nc_qty, unit_cd, created_by, updated_by)
                select ?, ?, ?, ?, item_id, 5, 'EA', 't', 't' from mst_item where item_cd = 'QK-BOLT'""", comp, claimId, ncrId, "NCR-" + ncrId);
    }

    long ret(long ncrId, int line) {
        return accounts.query(comp, """
                insert into srm_return (comp_cd, return_no, cust_id, plant_cd, ncr_id, ncr_no, ncr_line_no, lot_id, lot_no, item_id, unit_cd, qty,
                                        created_by, updated_by)
                select ?, ?, ?, 'P1', ?, ?, ?, 1, 'L1', item_id, 'EA', 1, 't', 't' from mst_item where item_cd = 'QK-BOLT' returning return_id""",
                Long.class, comp, "QK-RT-" + (++seq), cust, ncrId, "NCR-" + ncrId, line);
    }

    void cancelReturn(long id) {
        accounts.exec(comp, "update srm_return set status = 'CANCELLED', cancel_reason = 'x' where return_id = ?", id);
    }

    void cancelClaim(long id) {
        accounts.exec(comp, "update srm_claim set status = 'CANCELLED', cancel_reason = 'x' where claim_id = ?", id);
    }

    <T> T tx(Supplier<T> s) {
        return runner.call(comp, () -> new TransactionTemplate(txm).execute(st -> s.get()));
    }

    int liveClaims(long ncrId) {
        return accounts.query(comp, "select count(*) from srm_claim_ncr n join srm_claim c on c.claim_id = n.claim_id "
                + "where n.ncr_id = ? and c.status <> 'CANCELLED'", Integer.class, ncrId);
    }

    int lockWaiters() {
        return accounts.query(comp, "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()",
                Integer.class);
    }

    // ------------------------------------------------------------------ 시험

    /** 클레임 NCR 둘 × 반품 줄 여럿, 취소된 반품은 빠지고 그 줄의 새 반품은 들어온다. 반품 쪽에서는 그 클레임 하나 */
    @Test
    void 같은_NCR_에_여러_건_취소_뒤_다시() throws Exception {
        long k = claim(901, 902);
        long r1 = ret(901, 1);
        long r2 = ret(901, 2);
        long r3 = ret(902, 1);
        cancelReturn(r3);
        long r4 = ret(902, 1);
        long other = ret(903, 1);
        mvc.perform(get("/api/srm/claims/" + k).with(me)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.returns[*].id").value(Matchers.contains((int) r1, (int) r2, (int) r4)));
        for (long r : new long[] {r1, r2, r4}) {
            mvc.perform(get("/api/srm/returns/" + r).with(me)).andExpect(jsonPath("$.data.claims[*].id").value(Matchers.contains((int) k)));
        }
        mvc.perform(get("/api/srm/returns/" + other).with(me)).andExpect(jsonPath("$.data.claims.length()").value(0));
        // 취소된 반품 상세에서도 클레임은 보인다(반품 쪽 거름은 클레임 상태만)
        mvc.perform(get("/api/srm/returns/" + r3).with(me)).andExpect(jsonPath("$.data.claims[*].id").value(Matchers.contains((int) k)));
    }

    /** 클레임 취소 → 반품 쪽에서 사라지고, 같은 NCR 의 새 클레임이 보인다. 취소된 클레임 상세에서는 반품이 그대로 보인다(관찰) */
    @Test
    void 클레임_취소_뒤_새_클레임() throws Exception {
        long k1 = claim(911);
        long r = ret(911, 1);
        cancelClaim(k1);
        mvc.perform(get("/api/srm/returns/" + r).with(me)).andExpect(jsonPath("$.data.claims.length()").value(0));
        long k2 = claim(911);
        mvc.perform(get("/api/srm/returns/" + r).with(me)).andExpect(jsonPath("$.data.claims[*].id").value(Matchers.contains((int) k2)));
        mvc.perform(get("/api/srm/claims/" + k1).with(me)).andExpect(jsonPath("$.data.returns[*].id").value(Matchers.contains((int) r)));
        // 살아 있는 클레임이 있는 NCR 을 세 번째 클레임에 → DB 가 거부
        long k3 = claim();
        Throwable t = catchThrowable(() -> addNcr(k3, 911));
        assertThat(PgErrors.constraint(t).orElse(String.valueOf(t))).isEqualTo("uq_srm_claim_ncr_live");
        assertThat(liveClaims(911)).isEqualTo(1);
    }

    /**
     * QC-1: "한 NCR 은 살아 있는 클레임 하나"(트리거 uq_srm_claim_ncr_live)는 다른 행을 EXISTS 로 볼 뿐 잠그지 않는다 — 작성 중 클레임 둘이
     * 같은 NCR 을 동시에 넣으면(앞 트랜잭션이 넣고 커밋 전인 것을 확인한 뒤 뒤 트랜잭션이 넣음) 둘 다 커밋된다. 클레임 만들기는 채번 잠금이
     * 줄을 세워 우연히 막히지만, 작성 중 클레임 고치기(PUT, NCR 바꾸기)는 서로 다른 클레임 행만 잠가 이 경로가 열려 있다 [코드].
     */
    @Test
    void 결함QC1_같은_NCR_을_두_클레임에_동시에_넣으면_하나는_거부() throws Exception {
        long k1 = claim();
        long k2 = claim();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        Future<?> first = POOL.submit(() -> tx(() -> {
            jdbc.update("""
                    insert into srm_claim_ncr (comp_cd, claim_id, ncr_id, ncr_no, item_id, nc_qty, unit_cd, created_by, updated_by)
                    select ?, ?, 921, 'NCR-921', item_id, 5, 'EA', 't', 't' from mst_item where item_cd = 'QK-BOLT'""", comp, k1);
            inserted.countDown();
            try {
                go.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
        // 뒤 트랜잭션도 풀에서 — 고친 뒤(NCR 단위 잠금)에는 앞 것을 기다리므로, 뒤가 끝나거나 잠금 대기에 들어간 것을 본 뒤 앞을 놓는다
        int before = lockWaiters();
        Future<?> second = POOL.submit(() -> tx(() -> {
            jdbc.update("""
                    insert into srm_claim_ncr (comp_cd, claim_id, ncr_id, ncr_no, item_id, nc_qty, unit_cd, created_by, updated_by)
                    select ?, ?, 921, 'NCR-921', item_id, 5, 'EA', 't', 't' from mst_item where item_cd = 'QK-BOLT'""", comp, k2);
            return null;
        }));
        for (int i = 0; i < 200 && !second.isDone() && lockWaiters() - before < 1; i++) {
            Thread.sleep(25);
        }
        go.countDown();
        Throwable secondErr = catchThrowable(() -> second.get(10, TimeUnit.SECONDS));
        Throwable firstErr = catchThrowable(() -> first.get(10, TimeUnit.SECONDS));
        assertThat(liveClaims(921)).as("살아 있는 클레임 수 — 뒤: " + secondErr + " / 앞: " + firstErr).isEqualTo(1);
    }

    /** 순차: 같은 NCR 을 다른 작성 중 클레임에 → 거부(규칙 자체는 맞다 — 동시만 새는지 대조) */
    @Test
    void 순차로는_같은_NCR_두_클레임이_거부된다() {
        long k1 = claim(931);
        long k2 = claim();
        Throwable t = catchThrowable(() -> addNcr(k2, 931));
        assertThat(PgErrors.constraint(t).orElse(String.valueOf(t))).isEqualTo("uq_srm_claim_ncr_live");
        assertThat(List.of(k1, k2)).hasSize(2);
        assertThat(liveClaims(931)).isEqualTo(1);
    }
}
