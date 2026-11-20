package io.smarthappy.platform.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import io.smarthappy.platform.job.JobLock;
import io.smarthappy.platform.job.PlatformJobs;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/** 로그 일 다이제스트 체인 + 작업 잠금 + 스케줄 조건부 활성 (P0 6단계) */
class LogDigestJobTest extends PostgresTestBase {

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    LogDigestService digests;

    @Autowired
    JobLock lock;

    @Autowired
    PlatformJobs jobs;

    @Autowired
    ApplicationContext context;

    String comp;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("DG");
        // 사흘 전·이틀 전 로그 (앱 계정도 occurred_at 을 지정해 넣을 수 있다 — 추가만 허용)
        for (int d = 3; d >= 2; d--) {
            accounts.exec(comp, """
                    insert into sys_access_log (comp_cd, occurred_at, event_cd, result_cd)
                    values (?, now() - make_interval(days => ?), 'LOGIN_OK', 'OK')""", comp, d);
        }
    }

    private int digestRows() {
        return accounts.query(comp, "select count(*) from sys_log_digest", Integer.class);
    }

    @Test
    void 지난_날짜를_체인으로_확정하고_다시_돌려도_중복되지_않는다() {
        int created = runner.call(comp, () -> digests.digestPending());
        assertThat(created).isPositive();
        assertThat(runner.call(comp, () -> digests.digestPending())).isZero();
        assertThat(runner.call(comp, () -> digests.verify())).isEmpty();
        // 체인: 각 날의 prev_digest = 앞날 digest
        assertThat(accounts.query(comp, """
                select count(*) from sys_log_digest d
                  join sys_log_digest p on p.comp_cd = d.comp_cd and p.table_nm = d.table_nm
                                       and p.log_date = d.log_date - 1
                 where d.prev_digest <> p.digest""", Integer.class)).isZero();
        // 오늘은 아직 확정하지 않는다
        assertThat(accounts.query(comp, "select count(*) from sys_log_digest where log_date >= " + TestAccounts.COMP_TODAY,
                Integer.class)).isZero();
    }

    @Test
    void 과거_로그를_DB_관리자가_고치면_검증에서_드러난다() throws Exception {
        runner.call(comp, () -> digests.digestPending());
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword());
                PreparedStatement ps = c.prepareStatement(
                        "update sys_access_log set result_cd = 'DENIED' where comp_cd = ?")) {
            ps.setString(1, comp);
            assertThat(ps.executeUpdate()).isEqualTo(2);
        }
        assertThat(runner.call(comp, () -> digests.verify())).isNotEmpty()
                .allMatch(s -> s.startsWith("sys_access_log:"));
    }

    @Test
    void 과거_로그를_지워도_드러난다() throws Exception {
        runner.call(comp, () -> digests.digestPending());
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword());
                PreparedStatement ps = c.prepareStatement(
                        "delete from sys_access_log where comp_cd = ? and occurred_at < now() - interval '3 days' + interval '1 hour'")) {
            ps.setString(1, comp);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        assertThat(runner.call(comp, () -> digests.verify())).isNotEmpty();
    }

    @Test
    void 다이제스트_행도_앱_계정이_고칠_수_없다() {
        runner.call(comp, () -> digests.digestPending());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> accounts.exec(comp,
                "update sys_log_digest set digest = repeat('0', 64)")).rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void 같은_작업_같은_회사는_동시에_한_곳에서만_잡힌다() throws Exception {
        String other = accounts.newComp("DG");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> first = pool.submit(() -> runner.call(comp, () -> {
                boolean got = lock.tryLock("t-job");
                held.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return got;
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(runner.call(comp, () -> lock.tryLock("t-job"))).isFalse();
            assertThat(runner.call(comp, () -> lock.tryLock("other-job"))).isTrue();
            assertThat(runner.call(other, () -> lock.tryLock("t-job"))).isTrue();
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            // 트랜잭션이 끝나면 저절로 풀린다
            assertThat(runner.call(comp, () -> lock.tryLock("t-job"))).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void 트랜잭션_밖에서는_잠글_수_없다() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> lock.tryLock("x"))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void 정기_작업은_회사마다_돌고_테스트에서는_스케줄이_꺼져_있다() {
        assertThat(jobs.logDigest().allSucceeded()).isTrue();
        assertThat(digestRows()).isPositive();
        assertThat(context.getBeanNamesForType(io.smarthappy.platform.job.SchedulingConfig.class)).isEmpty();
    }
}
