package io.smarthappy.platform.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import io.smarthappy.platform.log.AccessLogService.Result;
import io.smarthappy.platform.tenant.TenantAwareTransactionManager;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 접속 로그는 바깥 트랜잭션이 연결을 반납한 뒤 쓴다 (docs/36 15.1, 상위 결정 B1).
 * 재현: 작은 풀(4) + 동시 요청 4건이 각자 연결 하나를 쥔 채 로그를 남기면, 예전(REQUIRES_NEW 바로 쓰기)은 모두 두 번째 연결을
 * 기다리다 시간 초과 → 로그가 사라지고 요청이 연결 대기 시간만큼 멈췄다. 거부·롤백 기록도 남는다는 규칙을 함께 고정한다
 */
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=4", "spring.datasource.hikari.connection-timeout=2000"})
class AccessLogAfterReleaseTest extends PostgresTestBase {

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    AccessLogService accessLog;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    javax.sql.DataSource dataSource;

    String comp;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("ALR");
    }

    private void record(String detail, Result result) {
        accessLog.record(AccessEvent.GRANT_DENIED, comp, null, "t", "sys-user", "SAVE", result, detail);
    }

    private int rows(String detailPrefix) {
        return accounts.query(comp, "select count(*) from sys_access_log where detail like ?", Integer.class, detailPrefix + "%");
    }

    @Test
    void 작은_풀에서_동시_요청이_각자_연결을_쥔_채_로그를_남겨도_멈추지_않고_모두_남는다() throws Exception {
        int n = 4;   // 풀 크기와 같다 — 모두 하나씩 쥐면 남는 연결이 없다
        CyclicBarrier allHolding = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        long start = System.nanoTime();
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                fs.add(pool.submit(() -> runner.run(comp, () -> {
                    jdbc.queryForObject("select 1", Integer.class);   // 연결을 쥔다
                    try {
                        allHolding.await(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    record("pool-" + k, Result.DENIED);
                })));
            }
            for (Future<?> f : fs) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(rows("pool-")).as("로그가 하나도 사라지지 않는다").isEqualTo(n);
        assertThat(ms).as("연결 대기 시간 초과(2초)를 기다리지 않는다").isLessThan(2000);
    }

    @Test
    void 바깥이_롤백돼도_거부_기록은_남는다() {
        Throwable t = catchThrowable(() -> runner.run(comp, () -> {
            record("rollback-1", Result.DENIED);
            throw new IllegalStateException("업무 거부");
        }));
        assertThat(t).hasMessage("업무 거부");
        assertThat(rows("rollback-")).isEqualTo(1);
    }

    @Test
    void 연결을_쥔_동안에는_쓰지_않고_가장_바깥이_반납한_뒤_쓴다_안쪽_분리_트랜잭션이_끝나도_아직() {
        int[] seenInside = new int[2];
        runner.run(comp, () -> {
            record("nest-outer", Result.OK);
            runner.runIsolated(comp, () -> record("nest-inner", Result.OK));
            // 안쪽 분리 트랜잭션은 끝났지만 바깥 연결을 아직 쥐고 있다 → 둘 다 아직 안 썼다(다른 트랜잭션 커밋도 보이는 READ COMMITTED)
            seenInside[0] = jdbc.queryForObject("select count(*) from sys_access_log where detail like 'nest-%'", Integer.class);
            seenInside[1] = TenantAwareTransactionManager.holdsConnection() ? 1 : 0;
        });
        assertThat(seenInside[0]).isZero();
        assertThat(seenInside[1]).isEqualTo(1);
        assertThat(rows("nest-")).isEqualTo(2);
        assertThat(TenantAwareTransactionManager.holdsConnection()).isFalse();
    }

    @Test
    void 바깥_트랜잭션이_없으면_바로_쓴다() {
        assertThat(TenantAwareTransactionManager.holdsConnection()).isFalse();
        record("now-1", Result.OK);
        assertThat(rows("now-")).isEqualTo(1);
    }

    @Test
    void 연결_반납이_예외를_던져도_셈이_0으로_돌아오고_미룬_기록은_쓰이며_다음_기록도_바로_남는다() {
        // 반납(super) 뒤 예외를 던지는 관리자 — 셈이 줄지 않으면 이 스레드의 이후 기록이 영영 미뤄진다(상위 검토 #520)
        TenantAwareTransactionManager failing = new TenantAwareTransactionManager(dataSource) {
            @Override
            protected void releaseConnection(Object transaction) {
                super.releaseConnection(transaction);
                throw new IllegalStateException("반납 실패 흉내");
            }
        };
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(failing);
        Throwable f = catchThrowable(() -> {
            try (var ignored = io.smarthappy.platform.tenant.TenantContext.openRequestScope(comp, "t")) {
                tx.executeWithoutResult(s -> record("cleanup-fail-1", Result.DENIED));
            }
        });
        assertThat(f).hasMessage("반납 실패 흉내");
        assertThat(TenantAwareTransactionManager.holdsConnection()).as("셈이 0 으로").isFalse();
        assertThat(rows("cleanup-fail-")).as("미룬 기록은 예외가 나도 쓴다").isEqualTo(1);
        // 다음 요청(트랜잭션 밖)의 기록은 바로 남는다
        record("cleanup-next-1", Result.OK);
        assertThat(rows("cleanup-next-")).isEqualTo(1);
    }

    @Test
    void 반납_뒤_작업이_실패해도_다음_작업과_본_요청은_그대로() {
        List<String> ran = new ArrayList<>();
        runner.run(comp, () -> {
            TenantAwareTransactionManager.afterRelease(() -> {
                throw new IllegalStateException("기록 실패");
            });
            TenantAwareTransactionManager.afterRelease(() -> ran.add("second"));
        });
        assertThat(ran).containsExactly("second");
        assertThat(TenantAwareTransactionManager.holdsConnection()).isFalse();
    }
}
