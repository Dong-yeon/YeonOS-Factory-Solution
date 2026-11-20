package io.smarthappy.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** docs/67 F3 — 서버 → 사설망 직접 연결을 켜는 조건 */
class LanDirectGateTest {

    @Test
    void 설치형은_켜고_SaaS_운영은_끄고_개발_시험_프로필은_켠다() {
        assertThat(LanDirectGate.decide(true, "", false)).isTrue();     // onprem
        assertThat(LanDirectGate.decide(false, "", false)).isFalse();   // saas 운영·데모
        assertThat(LanDirectGate.decide(false, null, false)).isFalse();
        assertThat(LanDirectGate.decide(false, "", true)).isTrue();     // local·test
    }

    @Test
    void 명시한_값이_이긴다() {
        assertThat(LanDirectGate.decide(false, "true", false)).isTrue();    // 망이 분리된 전용 SaaS 설치
        assertThat(LanDirectGate.decide(false, " TRUE ", false)).isTrue();
        assertThat(LanDirectGate.decide(true, "false", false)).isFalse();   // 설치형이라도 끌 수 있다
        assertThat(LanDirectGate.decide(false, "false", true)).isFalse();   // 개발 PC 에서 SaaS 동작 확인
        assertThat(LanDirectGate.decide(false, "yes", false)).isFalse();    // 모르는 값은 기본 규칙(SaaS = 끔)
    }

    @Test
    void 시험용_고정값() {
        assertThat(LanDirectGate.fixed(true).enabled()).isTrue();
        assertThat(LanDirectGate.fixed(false).enabled()).isFalse();
    }
}
