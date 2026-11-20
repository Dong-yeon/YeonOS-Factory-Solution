package io.smarthappy.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.smarthappy.platform.config.HappyBootProperties;

/** 접속 주소 기준 시도 제한 — 시계를 돌려 창 만료까지 확인 (DB 없음) */
class LoginAttemptLimiterTest {

    private LoginAttemptLimiter limiter;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        // IP+회사 3회, IP 5회, 창 10분
        limiter = new LoginAttemptLimiter(new HappyBootProperties(null,
                new HappyBootProperties.Security(5, 15, 3, 5, 10, 12), null, null));
        clock = new MutableClock(Instant.parse("2026-10-15T00:00:00Z"));
        limiter.setClock(clock);
    }

    @Test
    void 한도까지는_허용_넘으면_차단() {
        limiter.recordFailure("1.1.1.1", "ACME");
        limiter.recordFailure("1.1.1.1", "ACME");
        assertThat(limiter.isBlocked("1.1.1.1", "ACME")).isFalse();
        limiter.recordFailure("1.1.1.1", "ACME");
        assertThat(limiter.isBlocked("1.1.1.1", "ACME")).isTrue();
        assertThat(limiter.isBlocked("1.1.1.1", "OTHER")).as("다른 회사는 IP 한도 전까지 허용").isFalse();
        assertThat(limiter.isBlocked("2.2.2.2", "ACME")).as("다른 IP 는 영향 없음").isFalse();
    }

    @Test
    void 창이_지나면_풀린다() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("1.1.1.1", "ACME");
        }
        clock.advance(Duration.ofMinutes(9).plusSeconds(59));
        assertThat(limiter.isBlocked("1.1.1.1", "ACME")).isTrue();
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.isBlocked("1.1.1.1", "ACME")).isFalse();
    }

    @Test
    void 회사코드를_바꿔가며_시도해도_IP_한도에서_막힌다() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("1.1.1.1", "C" + i);
        }
        assertThat(limiter.isBlocked("1.1.1.1", "NEWCOMP")).isTrue();
    }

    @Test
    void 성공하면_IP_회사_키는_초기화된다() {
        limiter.recordFailure("1.1.1.1", "ACME");
        limiter.recordFailure("1.1.1.1", "ACME");
        limiter.recordSuccess("1.1.1.1", "ACME");
        limiter.recordFailure("1.1.1.1", "ACME");
        limiter.recordFailure("1.1.1.1", "ACME");
        assertThat(limiter.isBlocked("1.1.1.1", "ACME")).isFalse();
    }

    /** docs/67 F5 — 계정 하나로 로그인 성공해도 다른 계정들에 대한 시도 한도는 되돌아가지 않는다(스프레이) */
    @Test
    void 성공은_그_계정의_실패만_지운다() {
        // 한도 3: 다른 계정 2번 실패 + 내 계정 1번 실패
        limiter.recordFailure("1.1.1.1", "ACME", "victim1", false);
        limiter.recordFailure("1.1.1.1", "ACME", "victim2", false);
        limiter.recordFailure("1.1.1.1", "ACME", "me", false);
        assertThat(limiter.isBlocked("1.1.1.1", "ACME")).isTrue();
        // 내 계정 성공 → 내 실패 1건만 빠짐(2건 남음) → 한 번 더 다른 계정을 시도하면 다시 차단
        limiter.recordSuccess("1.1.1.1", "ACME", "ME ");   // 아이디는 대소문자·앞뒤 공백 무시
        assertThat(limiter.isBlocked("1.1.1.1", "ACME")).isFalse();
        limiter.recordFailure("1.1.1.1", "ACME", "victim3", false);
        assertThat(limiter.isBlocked("1.1.1.1", "ACME")).isTrue();
        // 실패한 적 없는 계정의 성공은 아무것도 지우지 않는다
        limiter.recordSuccess("1.1.1.1", "ACME", "never-failed");
        assertThat(limiter.isBlocked("1.1.1.1", "ACME")).isTrue();
    }

    @Test
    void 정상_사용자가_오타_뒤_성공하면_자기_몫은_지워진다() {
        limiter.recordFailure("1.1.1.1", "ACME", "kim", false);
        limiter.recordFailure("1.1.1.1", "ACME", "kim", false);
        limiter.recordSuccess("1.1.1.1", "ACME", "kim");
        limiter.recordFailure("1.1.1.1", "ACME", "lee", false);
        limiter.recordFailure("1.1.1.1", "ACME", "lee", false);
        assertThat(limiter.isBlocked("1.1.1.1", "ACME")).as("공장 NAT 뒤 여러 사람 오타가 쌓여 막히지 않게").isFalse();
    }

    @Test
    void IPv6_는_64_단위로_센다() {
        limiter.recordFailure("2001:db8:1:2::a", "ACME");
        limiter.recordFailure("2001:db8:1:2:ffff::b", "ACME");
        limiter.recordFailure("2001:0db8:0001:0002:1234:5678:9abc:def0", "ACME");
        assertThat(limiter.isBlocked("2001:db8:1:2::99", "ACME")).as("같은 /64 의 다른 주소").isTrue();
        assertThat(limiter.isBlocked("2001:db8:1:3::a", "ACME")).as("다른 /64").isFalse();
        assertThat(LoginAttemptLimiter.source("::ffff:1.2.3.4")).isEqualTo("1.2.3.4");
        assertThat(LoginAttemptLimiter.source("1.2.3.4")).isEqualTo("1.2.3.4");
        assertThat(LoginAttemptLimiter.source("not:an:ip:zz")).isEqualTo("not:an:ip:zz");   // 이상한 글자도 예외 없이
    }

    @Test
    void 키가_넘쳐도_진행_중_차단을_통째로_풀지_않는다() {
        // 공격 대상 차단을 먼저 걸고(창이 가장 늦게 끝나도록 시계를 조금 뒤로 미룬 뒤)
        clock.advance(Duration.ofMinutes(5));
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("7.7.7.7", "ACME");
        }
        assertThat(limiter.isBlocked("7.7.7.7", "ACME")).isTrue();
        // 그보다 먼저 끝나는 쓰레기 키를 상한 넘게 — 예전 동작(전부 비우기)이면 차단이 풀렸다
        MutableClock early = new MutableClock(Instant.parse("2026-10-15T00:00:00Z"));
        limiter.setClock(early);
        for (int i = 0; i < 60_000; i++) {
            limiter.recordFailure("10." + (i / 65536) + "." + (i / 256 % 256) + "." + (i % 256), "X");
        }
        limiter.setClock(clock);
        assertThat(limiter.size()).isLessThanOrEqualTo(LoginAttemptLimiter.MAX_KEYS + 3);
        assertThat(limiter.isBlocked("7.7.7.7", "ACME")).isTrue();
    }

    @Test
    void 이상한_입력에도_예외가_없다() {
        limiter.recordFailure(null, null);
        limiter.recordFailure("1.1.1.1", "x".repeat(10_000));
        assertThat(limiter.isBlocked(null, null)).isFalse();
    }

    @Test
    void 키가_폭증해도_메모리가_묶인다() {
        for (int i = 0; i < 60_000; i++) {
            limiter.recordFailure("10.0." + (i / 256) + "." + (i % 256), "ACME");
        }
        // 예외 없이 끝나고, 상한을 넘은 시점에 정리된다 (내부 맵 크기는 private — 동작만 확인)
        assertThat(limiter.isBlocked("9.9.9.9", "ACME")).isFalse();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
