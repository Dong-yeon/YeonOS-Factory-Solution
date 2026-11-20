package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;

import io.smarthappy.module.master.workforce.WorkerService;
import io.smarthappy.module.pop.PopAuthService;
import io.smarthappy.module.pop.PopDtos.BadgeForm;
import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA — POP PIN 확인 선점(#533) 이상 사용. 모듈 시험(PopPinPoolTest)이 덮은 것(같은 작업자 동시 틀림 → 정확히 5번째 잠금, 작은 풀, 잠긴 동안 해시
 * 비교, 잠금 끝나면 1부터)은 다시 하지 않는다. 여기서는 잠금이 끝나는 순간의 동시 시도, 맞는 PIN 의 "실패 수 0" 이 그사이 선점된 실패를 지우지 않는지(O-1 — MES 가 고침),
 * A사로 B사. 서비스를 직접 부른다(PopPinPoolTest 와 같은 방식). 분석 docs/qa/2026-10-26_pop-spc-asn-b2.md
 */
class PopPinQaTest extends PostgresTestBase {

    static final String OK_PIN = "2468";

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    PolicyService policies;

    @Autowired
    PopAuthService auth;

    @MockitoSpyBean
    PasswordEncoder encoder;

    String comp;
    final AtomicBoolean pauseOk = new AtomicBoolean();
    CountDownLatch paused;
    CountDownLatch release;

    @BeforeEach
    void setUp() throws Exception {
        comp = newPinComp("QP");
        pauseOk.set(false);
        paused = new CountDownLatch(1);
        release = new CountDownLatch(1);
        // 맞는 PIN 의 해시 비교를 멈출 수 있게(선점 뒤·결과 기록 전) — 나머지는 진짜 비교
        doAnswer(inv -> {
            if (pauseOk.get() && OK_PIN.equals(String.valueOf((Object) inv.getArgument(0)))) {
                paused.countDown();
                release.await(30, TimeUnit.SECONDS);
            }
            return inv.callRealMethod();
        }).when(encoder).matches(any(), any());
    }

    @AfterEach
    void resetClock() {
        ReflectionTestUtils.invokeMethod(auth, "setClock", Clock.systemUTC());
    }

    String newPinComp(String prefix) {
        String c = accounts.newComp(prefix);
        accounts.addPlant(c, "P1", "1공장");
        runner.run(c, () -> policies.set("POP_PIN_REQUIRED", "P1", "Y", "t"));
        return c;
    }

    long worker(String c, String cd, String badge, String pin) {
        return accounts.query(c, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, pin_hash, created_by, updated_by)
                values (?, ?, ?, 'P1', ?, ?, 't', 't') returning worker_id""", Long.class, c, cd, cd, WorkerService.badgeHash(c, badge),
                encoder.encode(pin));
    }

    String badge(String c, int term, String badge, String pin) {
        TerminalPrincipal t = new TerminalPrincipal(c, 800_000L + term, "TAB-" + term, "TAB-" + term, "P1", null, null, null, "POP");
        try (var ignored = TenantContext.openRequestScope(c, t.actor())) {
            auth.badge(new MockHttpServletRequest(), t, new BadgeForm(badge, pin));
            return "OK";
        } catch (BusinessException e) {
            return e.code().name();
        }
    }

    int fails(String c, long id) {
        return accounts.query(c, "select pin_fail_cnt from mst_worker where worker_id = ?", Integer.class, id);
    }

    boolean locked(String c, long id) {
        return accounts.query(c, "select pin_locked_until is not null from mst_worker where worker_id = ?", Boolean.class, id);
    }

    Map<String, Integer> together(int n, java.util.function.IntFunction<String> call) throws Exception {
        CyclicBarrier go = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<String>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                fs.add(pool.submit(() -> {
                    go.await(10, TimeUnit.SECONDS);
                    return call.apply(k);
                }));
            }
            Map<String, Integer> out = new TreeMap<>();
            for (Future<String> f : fs) {
                out.merge(f.get(30, TimeUnit.SECONDS), 1, Integer::sum);
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 시험

    /** 잠금이 끝난 바로 그 시각에 단말 8대가 동시에 틀림 — 다시 1부터 세어 정확히 5번째에 잠긴다(만료 경계에서 횟수가 늘지 않음) */
    @Test
    void 잠금이_끝나는_순간_동시에_틀려도_다시_5번째에_잠긴다() throws Exception {
        long id = worker(comp, "E1", "BADGE-QP01", OK_PIN);
        Instant base = Instant.now();
        ReflectionTestUtils.invokeMethod(auth, "setClock", Clock.fixed(base, ZoneOffset.UTC));
        for (int i = 0; i < 5; i++) {
            badge(comp, 0, "BADGE-QP01", "1111");
        }
        assertThat(locked(comp, id)).isTrue();
        String until = accounts.query(comp, "select pin_locked_until::text from mst_worker where worker_id = ?", String.class, id);
        // 잠금 끝 시각 그대로(조건 pin_locked_until <= now) — 경계
        ReflectionTestUtils.invokeMethod(auth, "setClock", Clock.fixed(base.plus(Duration.ofMinutes(15)), ZoneOffset.UTC));
        assertThat(accounts.query(comp, "select ?::timestamptz <= pin_locked_until from mst_worker where worker_id = ?", Boolean.class,
                base.plus(Duration.ofMinutes(15)).atOffset(ZoneOffset.UTC), id)).as("시계가 잠금 끝 시각 " + until).isTrue();
        Map<String, Integer> out = together(8, k -> badge(comp, k, "BADGE-QP01", "1111"));
        assertThat(out).containsExactly(Map.entry("PIN_LOCKED", 4), Map.entry("PIN_WRONG", 4));
        assertThat(fails(comp, id)).isEqualTo(5);
        assertThat(locked(comp, id)).isTrue();
    }

    /**
     * O-1(낮음 — 맞는 PIN 을 아는 사람만 일으킴, MES 가 고침 2026-10-26): 맞는 PIN 이 선점한 뒤 해시 비교 중에 다른 단말의 틀린 시도 3번이 선점되면
     * (실패 수 4), 맞는 PIN 의 결과 기록(pinOk)은 자기 선점분만 되돌린다(실패 수 3) — 그 3번이 사라지지 않고, 남은 틀린 시도는 1번(2번째에 잠김).
     * 고치기 전: 0 으로 덮어써 예산이 다시 가득(틀림 4번 모두 PIN_WRONG)
     */
    @Test
    void O1_맞는_PIN_의_리셋이_그사이_선점된_실패를_지우지_않는다() throws Exception {
        long id = worker(comp, "E2", "BADGE-QP02", OK_PIN);
        pauseOk.set(true);
        ExecutorService one = Executors.newSingleThreadExecutor();
        try {
            Future<String> good = one.submit(() -> badge(comp, 0, "BADGE-QP02", OK_PIN));
            assertThat(paused.await(10, TimeUnit.SECONDS)).as("맞는 PIN 이 선점 뒤 비교에서 멈춤").isTrue();
            assertThat(fails(comp, id)).isEqualTo(1);
            for (int i = 1; i <= 3; i++) {
                assertThat(badge(comp, i, "BADGE-QP02", "1111")).isEqualTo("PIN_WRONG");
            }
            assertThat(fails(comp, id)).isEqualTo(4);
            release.countDown();
            assertThat(good.get(20, TimeUnit.SECONDS)).isEqualTo("OK");
        } finally {
            one.shutdownNow();
        }
        pauseOk.set(false);
        assertThat(fails(comp, id)).as("선점된 실패 3번은 남고 맞는 PIN 의 몫만 되돌림").isEqualTo(3);
        int wrong = 0;
        for (int i = 0; i < 4; i++) {
            if ("PIN_WRONG".equals(badge(comp, 5, "BADGE-QP02", "1111"))) {
                wrong++;
            }
        }
        assertThat(wrong).as("남은 틀린 시도는 1번 — 2번째에 잠김(예산이 다시 차지 않음)").isEqualTo(1);
        assertThat(locked(comp, id)).isTrue();
    }

    /** O-1 같은 뿌리(MES 가 고침): 비교 중에 틀린 시도가 잠금(5번째)까지 가면, 맞는 PIN 은 들어오지만 그 잠금은 그대로(실패 수 4). 고치기 전: 잠금을 풀었다 */
    @Test
    void O1_맞는_PIN_의_리셋이_그사이_걸린_잠금은_풀지_않는다() throws Exception {
        long id = worker(comp, "E3", "BADGE-QP03", OK_PIN);
        pauseOk.set(true);
        ExecutorService one = Executors.newSingleThreadExecutor();
        try {
            Future<String> good = one.submit(() -> badge(comp, 0, "BADGE-QP03", OK_PIN));
            assertThat(paused.await(10, TimeUnit.SECONDS)).isTrue();
            for (int i = 1; i <= 4; i++) {
                badge(comp, i, "BADGE-QP03", "1111");
            }
            assertThat(locked(comp, id)).as("4번째 틀림(선점 5번째)에서 잠김").isTrue();
            release.countDown();
            assertThat(good.get(20, TimeUnit.SECONDS)).isEqualTo("OK");
        } finally {
            one.shutdownNow();
        }
        assertThat(locked(comp, id)).as("그사이 걸린 잠금은 그대로").isTrue();
        assertThat(fails(comp, id)).isEqualTo(4);
    }

    /** A사로 B사: 같은 배지 글자를 두 회사가 써도 해시에 회사가 들어가 A사 단말은 A사 작업자만 — B사 PIN 은 틀림, B사 실패 수는 그대로. B사에만 있는 배지는 모름 */
    @Test
    void A사로_B사_배지는_회사별이라_B사_작업자에_닿지_않는다() {
        String b = newPinComp("QQ");
        long aw = worker(comp, "A1", "BADGE-SAME1", "1357");
        long bw = worker(b, "B1", "BADGE-SAME1", OK_PIN);
        worker(b, "B2", "BADGE-ONLYB", OK_PIN);
        assertThat(badge(comp, 0, "BADGE-SAME1", OK_PIN)).isEqualTo("PIN_WRONG");   // A사 작업자에게 B사 PIN
        assertThat(fails(comp, aw)).isEqualTo(1);
        assertThat(fails(b, bw)).isZero();
        assertThat(badge(comp, 0, "BADGE-ONLYB", OK_PIN)).isEqualTo("BADGE_UNKNOWN");
        assertThat(accounts.query(b, "select coalesce(sum(pin_fail_cnt), 0) from mst_worker", Integer.class)).isZero();
    }
}
