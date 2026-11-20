package io.smarthappy.platform.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.smarthappy.platform.common.web.BusinessException;

/** SSE 시범 허브(docs/70 3장) — 상한·회사·사용자 분리 (순수 단위) */
class SseHubTest {

    SseHub hub = new SseHub();

    @AfterEach
    void close() {
        hub.destroy();
    }

    @Test
    void 사용자당_상한을_넘으면_오래된_것부터_닫고_회사가_다르면_다른_자리() {
        for (int i = 0; i < SseHub.PER_USER + 3; i++) {
            hub.open("ACME", 1L);
        }
        assertThat(hub.size("ACME", 1L)).isEqualTo(SseHub.PER_USER);
        hub.open("OTHER", 1L);   // 같은 사용자 번호라도 회사가 다르면 다른 구독
        assertThat(hub.size("OTHER", 1L)).isEqualTo(1);
        assertThat(hub.size()).isEqualTo(SseHub.PER_USER + 1);
        // 없는 구독에 보내기는 조용히 지나간다
        hub.publish("NOPE", 9L, "inbox");
        hub.publishAfterCommit("ACME", 1L, "inbox");   // 트랜잭션 밖이면 바로
    }

    @Test
    void 전체_상한을_넘으면_429() {
        for (int u = 0; u < SseHub.MAX_TOTAL; u++) {
            hub.open("ACME", u);
        }
        assertThatThrownBy(() -> hub.open("ACME", 99_999L)).isInstanceOf(BusinessException.class);
        assertThat(hub.size()).isEqualTo(SseHub.MAX_TOTAL);
    }
}
