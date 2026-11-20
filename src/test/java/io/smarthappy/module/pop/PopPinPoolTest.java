package io.smarthappy.module.pop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import io.smarthappy.module.master.workforce.WorkerService;
import io.smarthappy.module.pop.PopDtos.ApprovalForm;
import io.smarthappy.module.pop.PopDtos.BadgeForm;
import io.smarthappy.module.pop.PopDtos.PopWorker;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * POP 배지·감독자 승인의 PIN 확인이 DB 연결을 겹쳐 잡지 않는다 (docs/25 4.42, 시스템관리 발견 — 상위 배정).
 * 예전: 바깥 @Transactional 이 연결 하나를 쥔 채 틀린 PIN 을 분리 트랜잭션(runIsolated)으로 남겨 요청 하나가 연결 2개 → 작은 풀(4)에
 * 단말 8대가 동시에 틀린 PIN 을 보내면 모두 두 번째 연결을 기다리다 시간 초과. 지금: 조회 → 선점(조건부 UPDATE) → 연결 없이 비교 → 맞으면 0.
 * 함께 고정: 같은 작업자 동시 시도도 정확히 5번째에 잠금, 잠겨 있어도 해시 비교, 잠금이 끝나면 1부터
 */
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=4", "spring.datasource.hikari.connection-timeout=2000"})
class PopPinPoolTest extends PostgresTestBase {

    private static final int TERMINALS = 8;

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

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PP");
        accounts.addPlant(comp, "P1", "1공장");
        runner.run(comp, () -> policies.set(PopAuthService.PIN_POLICY, "P1", "Y", "t"));
    }

    @AfterEach
    void resetClock() {
        auth.setClock(Clock.systemUTC());
        reset(encoder);
    }

    private long worker(String cd, String badge, String pin) {
        return accounts.query(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, pin_hash, created_by, updated_by)
                values (?, ?, ?, 'P1', ?, ?, 't', 't') returning worker_id""", Long.class, comp, cd, cd + " 작업자",
                WorkerService.badgeHash(comp, badge), encoder.encode(pin));
    }

    private TerminalPrincipal terminal(int i) {
        return new TerminalPrincipal(comp, 900_000L + i, "TAB-" + i, "TAB-" + i, "P1", null, null, null, "POP");
    }

    /** 요청 하나 — 필터처럼 회사 범위를 열고 서비스를 부른다. 결과 = 오류 코드(성공은 OK) */
    private String badge(int term, String badge, String pin) {
        TerminalPrincipal t = terminal(term);
        try (var ignored = TenantContext.openRequestScope(comp, t.actor())) {
            auth.badge(new MockHttpServletRequest(), t, new BadgeForm(badge, pin));
            return "OK";
        } catch (BusinessException e) {
            return e.code().name();
        }
    }

    private String approve(int term, PopWorker me, String badge, String pin) {
        TerminalPrincipal t = terminal(term);
        try (var ignored = TenantContext.openRequestScope(comp, t.actor())) {
            auth.approve(new MockHttpServletRequest(), t, me, new ApprovalForm(badge, pin, "RESULT_CORRECT", 1L));
            return "OK";
        } catch (BusinessException e) {
            return e.code().name();
        }
    }

    /** n 개를 동시에 출발시켜 결과 코드별 개수. 연결 대기 시간 초과 같은 예외는 그대로 테스트 실패로 */
    private Map<String, Integer> together(int n, java.util.function.IntFunction<String> call) throws Exception {
        CyclicBarrier go = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<String>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                Callable<String> c = () -> {
                    go.await(10, TimeUnit.SECONDS);
                    return call.apply(k);
                };
                fs.add(pool.submit(c));
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

    private int fails(long workerId) {
        return accounts.query(comp, "select pin_fail_cnt from mst_worker where worker_id = ?", Integer.class, workerId);
    }

    private boolean locked(long workerId) {
        return accounts.query(comp, "select coalesce(pin_locked_until > now(), false) from mst_worker where worker_id = ?",
                Boolean.class, workerId);
    }

    @Test
    void 작은_풀에서_단말_8대가_동시에_틀린_PIN_을_보내도_연결이_바닥나지_않는다() throws Exception {
        long[] ids = new long[TERMINALS];
        for (int i = 0; i < TERMINALS; i++) {
            ids[i] = worker("E" + i, "BADGE-01" + i, "2468");
        }
        long start = System.nanoTime();
        Map<String, Integer> out = together(TERMINALS, k -> badge(k, "BADGE-01" + k, "1111"));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(out).as("모두 PIN 틀림으로 답한다(연결 대기 시간 초과 없음)").containsExactly(Map.entry("PIN_WRONG", TERMINALS));
        for (long id : ids) {
            assertThat(fails(id)).isEqualTo(1);
        }
        assertThat(ms).as("연결 대기 시간 초과(2초)를 기다리지 않는다").isLessThan(10_000);
        // 맞는 PIN 도 동시에 — 모두 들어오고 실패 수 0
        assertThat(together(TERMINALS, k -> badge(k, "BADGE-01" + k, "2468"))).containsExactly(Map.entry("OK", TERMINALS));
        for (long id : ids) {
            assertThat(fails(id)).isZero();
        }
    }

    @Test
    void 같은_작업자를_단말_8대가_동시에_틀리면_정확히_5번째에_잠긴다() throws Exception {
        long id = worker("E1", "BADGE-0001", "2468");
        Map<String, Integer> out = together(TERMINALS, k -> badge(k, "BADGE-0001", "1111"));
        // 선점이 한 줄로 서므로 4번은 남은 횟수, 5번째가 잠그고, 그 뒤 3번은 잠김 — 횟수가 사라지거나 넘치지 않는다
        assertThat(out).containsExactly(Map.entry("PIN_LOCKED", 4), Map.entry("PIN_WRONG", 4));
        assertThat(fails(id)).isEqualTo(5);
        assertThat(locked(id)).isTrue();
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'PIN_LOCKED'", Integer.class))
                .as("잠근 시도 하나만 PIN_LOCKED 로그").isEqualTo(1);
        // 잠긴 동안은 맞는 PIN 도 안 된다
        assertThat(badge(0, "BADGE-0001", "2468")).isEqualTo("PIN_LOCKED");
    }

    @Test
    void 잠겨_있어도_해시_비교는_한다_잠금이_끝나면_1부터_센다() {
        long id = worker("E1", "BADGE-0001", "2468");
        Instant base = Instant.now();
        auth.setClock(Clock.fixed(base, ZoneOffset.UTC));
        for (int i = 4; i >= 1; i--) {
            assertThat(badge(0, "BADGE-0001", "1111")).isEqualTo("PIN_WRONG");
        }
        assertThat(badge(0, "BADGE-0001", "1111")).isEqualTo("PIN_LOCKED");
        clearInvocations(encoder);
        assertThat(badge(0, "BADGE-0001", "2468")).isEqualTo("PIN_LOCKED");
        verify(encoder).matches(anyString(), anyString());   // 응답 시간으로 잠김을 알리지 않게
        // PIN 없이 배지만: 잠김 안내(비교할 PIN 이 없음)
        assertThat(badge(0, "BADGE-0001", null)).isEqualTo("PIN_LOCKED");
        // 15분 뒤: 한 번 틀리면 남은 4번(1부터), 다시 잠기지 않는다
        auth.setClock(Clock.fixed(base.plus(Duration.ofMinutes(16)), ZoneOffset.UTC));
        assertThat(badge(0, "BADGE-0001", "1111")).isEqualTo("PIN_WRONG");
        assertThat(fails(id)).isEqualTo(1);
        assertThat(badge(0, "BADGE-0001", "2468")).isEqualTo("OK");
        assertThat(fails(id)).isZero();
        assertThat(accounts.query(comp, "select pin_locked_until is null from mst_worker where worker_id = ?", Boolean.class, id)).isTrue();
        // 형식이 틀린 PIN(글자)도 실패로 센다
        assertThat(badge(0, "BADGE-0001", "12ab")).isEqualTo("PIN_WRONG");
        assertThat(fails(id)).isEqualTo(1);
    }

    @Test
    void 감독자_승인도_작은_풀에서_동시에_틀려도_멈추지_않고_같은_카운터로_5번째에_잠긴다() throws Exception {
        long operator = worker("E1", "BADGE-0001", "1111");
        long sup = worker("S1", "BADGE-0002", "2468");
        long qual = accounts.query(comp, "insert into mst_qual (comp_cd, qual_cd, qual_nm, created_by, updated_by) values (?, 'SUP', '감독자', 't', 't') returning qual_id",
                Long.class, comp);
        accounts.exec(comp, """
                insert into mst_worker_qual (comp_cd, worker_id, qual_id, acquired_on, created_by, updated_by)
                values (?, ?, ?, (now() at time zone 'Asia/Seoul')::date - 10, 't', 't')""", comp, sup, qual);
        runner.run(comp, () -> policies.set(PopAuthService.APPROVER_QUAL_POLICY, null, "SUP", "t"));
        long now = System.currentTimeMillis();
        PopWorker me = new PopWorker(900_000L, operator, "E1", "E1 작업자", now, now);
        // 단말마다 지금 작업자는 다르지만(단말 id 만 다름) 감독자는 같다
        Map<String, Integer> out = together(TERMINALS, k -> approve(k, new PopWorker(900_000L + k, operator, "E1", "E1 작업자", now, now),
                "BADGE-0002", "0000"));
        assertThat(out).containsExactly(Map.entry("PIN_LOCKED", 4), Map.entry("PIN_WRONG", 4));
        assertThat(fails(sup)).isEqualTo(5);
        assertThat(approve(0, me, "BADGE-0002", "2468")).isEqualTo("PIN_LOCKED");
        // 잠금을 풀면 맞는 PIN 으로 승인, 실패 수 0
        accounts.exec(comp, "update mst_worker set pin_fail_cnt = 0, pin_locked_until = null where worker_id = ?", sup);
        assertThat(approve(0, me, "BADGE-0002", "2468")).isEqualTo("OK");
        assertThat(fails(sup)).isZero();
    }

    /** 맞는 PIN(pin) 의 해시 비교만 멈추고, 그사이 틀린 시도 wrongs 번을 다른 단말로 — 결과 = 맞는 PIN 응답 */
    private String okWhileOthersFail(String badge, String pin, int wrongs) throws Exception {
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        doAnswer(inv -> {
            if (pin.equals(String.valueOf((Object) inv.getArgument(0)))) {
                paused.countDown();
                go.await(30, TimeUnit.SECONDS);
            }
            return inv.callRealMethod();
        }).when(encoder).matches(any(), any());
        ExecutorService one = Executors.newSingleThreadExecutor();
        try {
            Future<String> ok = one.submit(() -> badge(0, badge, pin));
            assertThat(paused.await(10, TimeUnit.SECONDS)).as("맞는 PIN 이 선점 뒤 비교에서 멈춤").isTrue();
            for (int i = 1; i <= wrongs; i++) {
                badge(i, badge, "1111");
            }
            go.countDown();
            return ok.get(20, TimeUnit.SECONDS);
        } finally {
            one.shutdownNow();
            reset(encoder);
        }
    }

    @Test
    void 맞는_PIN_비교_중에_다른_단말이_틀리면_남의_실패와_그사이_걸린_잠금은_그대로_QA_O1() throws Exception {
        // 그사이 틀림 3번(선점 1 + 3 = 4) → 맞는 PIN 은 들어오고 자기 몫만 되돌림 → 3. 남은 시도는 2번째에 잠긴다(예산이 다시 가득 차지 않음)
        long a = worker("E1", "BADGE-0001", "2468");
        assertThat(okWhileOthersFail("BADGE-0001", "2468", 3)).isEqualTo("OK");
        assertThat(fails(a)).isEqualTo(3);
        assertThat(locked(a)).isFalse();
        assertThat(badge(5, "BADGE-0001", "1111")).isEqualTo("PIN_WRONG");
        assertThat(badge(5, "BADGE-0001", "1111")).isEqualTo("PIN_LOCKED");
        // 그사이 틀림 4번(선점 5번째 = 잠금) → 맞는 PIN 은 들어오지만 잠금은 그대로(실패 수 4), 다음 맞는 PIN 은 잠김
        long b = worker("E2", "BADGE-0002", "2468");
        assertThat(okWhileOthersFail("BADGE-0002", "2468", 4)).isEqualTo("OK");
        assertThat(fails(b)).isEqualTo(4);
        assertThat(locked(b)).isTrue();
        assertThat(badge(0, "BADGE-0002", "2468")).isEqualTo("PIN_LOCKED");
        // 아무도 끼어들지 않으면 지금처럼 0(앞선 실패 2번 + 맞는 PIN)
        long c = worker("E3", "BADGE-0003", "2468");
        badge(0, "BADGE-0003", "1111");
        badge(0, "BADGE-0003", "1111");
        assertThat(okWhileOthersFail("BADGE-0003", "2468", 0)).isEqualTo("OK");
        assertThat(fails(c)).isZero();
    }
}
