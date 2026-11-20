package io.smarthappy.platform.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.smarthappy.support.PostgresTestBase;

class TenantRunnerTest extends PostgresTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    String compA;
    String compB;

    @BeforeEach
    void setUp() {
        int n = SEQ.incrementAndGet();
        compA = "TRA" + n;
        compB = "TRB" + n;
        createComp(compA, "ACTIVE");
        createComp(compB, "ACTIVE");
        runner.run(compA, () -> insertPlant(compA, "P1", "A사 1공장"));
        runner.run(compB, () -> insertPlant(compB, "P1", "B사 1공장"));
    }

    // ---------- 정상 흐름 ----------

    @Test
    void 트랜잭션매니저가_회사범위_설정용이다() {
        assertThat(transactionManager).isInstanceOf(TenantAwareTransactionManager.class);
    }

    @Test
    void 지정한_회사_데이터만_보인다() {
        assertThat(runner.call(compA, () -> plantNames())).containsExactly("A사 1공장");
        assertThat(runner.call(compB, () -> plantNames())).containsExactly("B사 1공장");
    }

    @Test
    void 실행이_끝나면_회사범위가_비워진다() {
        runner.run(compA, () -> assertThat(TenantContext.currentCompCd()).contains(compA));
        assertThat(TenantContext.currentCompCd()).isEmpty();
    }

    @Test
    void 사용중인_회사만_순회한다() {
        String suspended = "TRS" + SEQ.incrementAndGet();
        createComp(suspended, "SUSPENDED");

        List<String> visited = Collections.synchronizedList(new ArrayList<>());
        TenantRunner.BatchResult result = runner.forEachActiveComp(comp -> {
            assertThat(TenantContext.currentCompCd()).contains(comp);
            visited.add(comp);
        });

        assertThat(visited).contains(compA, compB).doesNotContain(suspended);
        assertThat(result.allSucceeded()).isTrue();
    }

    @Test
    void 회사목록_범위에서도_업무데이터는_안보인다() {
        // activeCompCodes 는 SYSTEM 범위 — sys_comp 읽기만 허용, sys_plant 는 0건이어야 함
        TenantContext.Scope s = TenantContext.open(TenantContext.TenantScope.systemDirectory());
        try {
            Integer plants = new TransactionTemplate(transactionManager)
                    .execute(st -> jdbc.queryForObject("select count(*) from sys_plant", Integer.class));
            Integer comps = new TransactionTemplate(transactionManager)
                    .execute(st -> jdbc.queryForObject("select count(*) from sys_comp", Integer.class));
            assertThat(plants).isZero();
            assertThat(comps).isGreaterThanOrEqualTo(2);
        } finally {
            s.close();
        }
    }

    @Test
    void 회사목록_범위로는_회사를_만들거나_바꿀수_없다() {
        TenantContext.Scope s = TenantContext.open(TenantContext.TenantScope.systemDirectory());
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            assertThatThrownBy(() -> tx.execute(st -> jdbc.update(
                    "insert into sys_comp (comp_cd, comp_nm, created_by, updated_by) values ('SYSHACK', 'x', 't', 't')")))
                    .isInstanceOf(DataAccessException.class);
            Integer updated = tx.execute(st -> jdbc.update("update sys_comp set comp_nm = '변조' where comp_cd = ?", compA));
            assertThat(updated).isZero();
        } finally {
            s.close();
        }
    }

    // ---------- 이상 사용 ----------

    @Test
    void 회사코드_형식이_틀리면_DB에_가기전에_거부() {
        for (String bad : new String[] {null, "", " ", "a1", "A", compA + " ", " " + compA, compA.toLowerCase(),
                "AB'; drop table sys_comp;--", "ABCDEFGHIJKLMNOPQRSTU"}) {
            assertThatThrownBy(() -> runner.run(bad, () -> {
            })).as("[%s]", bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 트랜잭션_안에서_호출하면_거부() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.execute(st -> runner.call(compB, () -> plantNames())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 실행중_예외면_롤백되고_회사범위도_비워진다() {
        assertThatThrownBy(() -> runner.run(compA, () -> {
            insertPlant(compA, "P2", "롤백될 공장");
            throw new IllegalStateException("업무 오류");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(TenantContext.currentCompCd()).isEmpty();
        assertThat(runner.call(compA, () -> plantNames())).containsExactly("A사 1공장");
    }

    @Test
    void 다른회사_코드로_저장하면_RLS가_막는다() {
        assertThatThrownBy(() -> runner.run(compA, () -> insertPlant(compB, "P9", "몰래")))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void 한_회사가_실패해도_다른_회사는_계속_실행된다() {
        TenantRunner.BatchResult result = runner.forEachActiveComp(comp -> {
            if (comp.equals(compA)) {
                throw new IllegalStateException("A사만 실패");
            }
        });
        assertThat(result.failed()).containsKey(compA);
        assertThat(result.succeeded()).contains(compB).doesNotContain(compA);
    }

    @Test
    void 트랜잭션_밖_조회는_0건_fail_closed() {
        assertThat(jdbc.queryForObject("select count(*) from sys_plant", Integer.class)).isZero();
    }

    @Test
    void 동시에_다른_회사로_실행해도_섞이지_않는다() throws Exception {
        int threads = 8;
        int rounds = 30;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                String comp = (i % 2 == 0) ? compA : compB;
                String expected = (i % 2 == 0) ? "A사 1공장" : "B사 1공장";
                futures.add(pool.submit(() -> {
                    int wrong = 0;
                    barrier.await(10, TimeUnit.SECONDS);
                    for (int r = 0; r < rounds; r++) {
                        List<String> names = runner.call(comp, () -> plantNames());
                        if (!names.equals(List.of(expected)) || !TenantContext.currentCompCd().isEmpty()) {
                            wrong++;
                        }
                    }
                    done.countDown();
                    return wrong;
                }));
            }
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
            for (Future<Integer> f : futures) {
                assertThat(f.get()).isZero();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- helpers ----------

    private void createComp(String compCd, String status) {
        runner.run(compCd, () -> jdbc.update(
                "insert into sys_comp (comp_cd, comp_nm, status_cd, created_by, updated_by) values (?, ?, ?, 'test', 'test')",
                compCd, compCd + " 회사", status));
    }

    private int insertPlant(String compCd, String plantCd, String plantNm) {
        return jdbc.update(
                "insert into sys_plant (comp_cd, plant_cd, plant_nm, created_by, updated_by) values (?, ?, ?, 'test', 'test')",
                compCd, plantCd, plantNm);
    }

    private List<String> plantNames() {
        return jdbc.queryForList("select plant_nm from sys_plant order by plant_cd", String.class);
    }
}
