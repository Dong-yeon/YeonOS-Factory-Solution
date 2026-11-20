package io.smarthappy.platform.numbering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/** 채번 — 형식·초기화·회사 시간대·동시성·결번 없음·이상 사용 */
class NumberingServiceTest extends PostgresTestBase {

    @Autowired
    NumberingService numbering;

    @Autowired
    TenantRunner runner;

    @Autowired
    TestAccounts accounts;

    @Autowired
    BizClock bizClock;

    String comp;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("NB");
    }

    @AfterEach
    void resetClock() {
        bizClock.setClock(Clock.systemUTC());
    }

    private void at(String instant) {
        bizClock.setClock(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private void rule(String ruleCd, String pattern, String reset) {
        runner.run(comp, () -> numbering.saveRule(ruleCd, ruleCd + " 규칙", pattern, reset, "test"));
    }

    private String next(String ruleCd) {
        return runner.call(comp, () -> numbering.next(ruleCd));
    }

    @Test
    void 패턴대로_번호가_나오고_순서대로_늘어난다() {
        at("2026-10-15T03:00:00Z");
        rule("WO", "WO-{yyMM}-{seq:4}", "MONTH");
        assertThat(next("WO")).isEqualTo("WO-2610-0001");
        assertThat(next("WO")).isEqualTo("WO-2610-0002");
    }

    @Test
    void 한_트랜잭션에서_여러_번_받아도_같은_번호가_나오지_않는다_MyBatis_캐시() {
        rule("LOT", "L{seq:5}", "NONE");
        List<String> nos = runner.call(comp, () -> List.of(numbering.next("LOT"), numbering.next("LOT"), numbering.next("LOT")));
        assertThat(nos).containsExactly("L00001", "L00002", "L00003");
    }

    @Test
    void 날짜는_회사_시간대_기준이다_서버가_UTC여도() {
        // UTC 2026-10-31 16:00 = 서울 11-01 01:00 → 11월 번호
        at("2026-10-31T16:00:00Z");
        rule("WO", "WO-{yyMMdd}-{seq:3}", "DAY");
        assertThat(next("WO")).isEqualTo("WO-261101-001");
    }

    @Test
    void 월이_바뀌면_1부터_다시() {
        rule("WO", "WO-{yyMM}-{seq:3}", "MONTH");
        at("2026-10-31T10:00:00Z");
        next("WO");
        next("WO");
        at("2026-11-01T10:00:00Z");
        assertThat(next("WO")).isEqualTo("WO-2611-001");
    }

    @Test
    void 업무가_롤백되면_번호도_되돌아간다_결번_없음() {
        rule("SH", "SH-{seq:4}", "NONE");
        assertThat(next("SH")).isEqualTo("SH-0001");
        assertThatThrownBy(() -> runner.run(comp, () -> {
            numbering.next("SH");
            throw new IllegalStateException("업무 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(next("SH")).isEqualTo("SH-0002");
    }

    @Test
    void 트랜잭션_밖에서는_번호를_받을_수_없다() {
        assertThatThrownBy(() -> numbering.next("WO")).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void 동시에_30스레드가_받아도_중복도_빈번호도_없다() throws Exception {
        rule("PAR", "P-{seq:6}", "NONE");
        int threads = 30;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CyclicBarrier barrier = new CyclicBarrier(10);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                        // 10개씩 묶음 출발 — 마지막 묶음 외에는 무시해도 순서와 무관
                    }
                    return next("PAR");
                }));
            }
            List<String> all = Collections.synchronizedList(new ArrayList<>());
            for (Future<String> f : futures) {
                all.add(f.get(60, TimeUnit.SECONDS));
            }
            assertThat(new HashSet<>(all)).hasSize(threads);
            List<String> expected = new ArrayList<>();
            for (int i = 1; i <= threads; i++) {
                expected.add(String.format("P-%06d", i));
            }
            assertThat(all).containsExactlyInAnyOrderElementsOf(expected);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 회사마다_번호가_따로다() {
        rule("WO", "W{seq:3}", "NONE");
        String other = accounts.newComp("NO");
        runner.run(other, () -> numbering.saveRule("WO", "다른 회사", "W{seq:3}", "NONE", "test"));
        next("WO");
        next("WO");
        assertThat(runner.call(other, () -> numbering.next("WO"))).isEqualTo("W001");
    }

    @Test
    void 다른_회사_규칙은_보이지_않는다() {
        String other = accounts.newComp("NX");
        runner.run(other, () -> numbering.saveRule("SECRET", "남의 규칙", "S{seq:3}", "NONE", "test"));
        assertThatThrownBy(() -> next("SECRET"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(HbErrorCode.NOT_FOUND));
    }

    @Test
    void 자릿수를_넘기면_조용히_늘리지_않고_막는다() {
        rule("TINY", "T{seq:1}", "NONE");
        for (int i = 0; i < 9; i++) {
            next("TINY");
        }
        assertThatThrownBy(() -> next("TINY"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(HbErrorCode.NUMBERING_EXHAUSTED));
    }

    @Test
    void 공장_토큰은_공장코드가_있어야_하고_형식을_검사한다() {
        at("2026-10-15T03:00:00Z");
        rule("PL", "{plant}-{yy}-{seq:3}", "YEAR");
        assertThat(runner.call(comp, () -> numbering.next("PL", "P1"))).isEqualTo("P1-26-001");
        assertThatThrownBy(() -> runner.call(comp, () -> numbering.next("PL")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> runner.call(comp, () -> numbering.next("PL", "p1/../x")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 잘못된_규칙은_저장할_수_없다() {
        String[][] bad = {
                {"WO-{seq:4}", "MONTH"},          // 매월 초기화인데 월이 없음 → 매달 같은 번호
                {"WO-{yy}-{seq:4}", "MONTH"},     // 연도만
                {"WO-{yyMM}-{seq:4}", "DAY"},     // 일 없음
                {"WO-{yyMM}", "NONE"},             // 순번 없음
                {"WO-{seq:3}-{seq:3}", "NONE"},    // 순번 2개
                {"WO-{seq:0}", "NONE"},            // 0자리
                {"WO-{seq:13}", "NONE"},           // 13자리
                {"wo-{seq:4}", "NONE"},            // 소문자 리터럴
                {"WO {seq:4}", "NONE"},            // 공백
                {"WO-{SEQ:4}", "NONE"},            // 모르는 토큰
                {"WO-{seq:4}'; drop table x;--", "NONE"},
                {"<b>{seq:4}</b>", "NONE"},
                {"WO-{seq:4}", "WEEK"},            // 모르는 주기
                {"X".repeat(51) + "{seq:3}", "NONE"},
        };
        for (String[] b : bad) {
            assertThatThrownBy(() -> rule("BAD", b[0], b[1]))
                    .as("%s / %s", b[0], b[1])
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void 없는_규칙이나_형식이_틀린_규칙코드() {
        for (String bad : new String[] {"NOPE", "wo", "", "A".repeat(40), "WO;DROP"}) {
            assertThatThrownBy(() -> next(bad)).as(bad).isInstanceOf(BusinessException.class);
        }
    }
}
