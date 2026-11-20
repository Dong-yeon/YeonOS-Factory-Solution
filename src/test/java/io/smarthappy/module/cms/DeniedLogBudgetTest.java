package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/** docs/67 F9 — /ext 틀린 키 기록 상한(순수 단위 시험) */
class DeniedLogBudgetTest {

    @Test
    void 출처마다_분당_상한까지만_다음_분에는_다시() {
        Instant t = Instant.parse("2026-11-09T00:00:10Z");
        ExtSecurityConfig.DeniedLogBudget b = new ExtSecurityConfig.DeniedLogBudget(3);
        b.setClock(Clock.fixed(t, ZoneOffset.UTC));
        assertThat(b.tryLog("10.0.0.1")).isTrue();
        assertThat(b.tryLog("10.0.0.1")).isTrue();
        assertThat(b.tryLog("10.0.0.1")).isTrue();
        assertThat(b.tryLog("10.0.0.1")).isFalse();
        assertThat(b.tryLog("10.0.0.2")).as("다른 출처는 따로").isTrue();
        b.setClock(Clock.fixed(t.plus(Duration.ofMinutes(1)), ZoneOffset.UTC));
        assertThat(b.tryLog("10.0.0.1")).as("다음 분").isTrue();
    }

    @Test
    void IPv6_는_64_단위_이상한_입력도_예외_없음() {
        ExtSecurityConfig.DeniedLogBudget b = new ExtSecurityConfig.DeniedLogBudget(1);
        assertThat(b.tryLog("2001:db8:1:2::a")).isTrue();
        assertThat(b.tryLog("2001:db8:1:2::b")).as("같은 /64").isFalse();
        assertThat(b.tryLog(null)).isTrue();
        assertThat(b.tryLog("zz:zz")).isTrue();
    }
}
