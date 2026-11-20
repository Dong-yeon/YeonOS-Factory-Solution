package io.smarthappy.module.cms.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** 알람 계약 (docs/33 11.4 Q2) — C8 구현이 없으면 받기만(no-op), MES 수집을 막지 않는다 */
class AlarmSinkContractTest {

    @Test
    void 빈이_없으면_NONE_이고_예외없이_받기만() {
        AlarmSink sink = AlarmSink.of(new StaticListableBeanFactory().getBeanProvider(AlarmSink.class));
        assertThat(sink).isSameAs(AlarmSink.NONE);
        assertThatCode(() -> {
            sink.raise(1L, "E-101", null, Instant.EPOCH, null, "ev-1");
            sink.clear(1L, "E-101", Instant.EPOCH, null, "ev-2");
        }).doesNotThrowAnyException();
    }

    @Test
    void 빈이_있으면_그것() {
        StaticListableBeanFactory f = new StaticListableBeanFactory();
        AlarmSink mine = new AlarmSink() {
            @Override
            public void raise(long equipId, String alarmCd, String alarmText, Instant occurredAt, Long terminalId, String eventId) {
            }

            @Override
            public void clear(long equipId, String alarmCd, Instant occurredAt, Long terminalId, String eventId) {
            }
        };
        f.addBean("alarmSink", mine);
        assertThat(AlarmSink.of(f.getBeanProvider(AlarmSink.class))).isSameAs(mine);
    }
}
