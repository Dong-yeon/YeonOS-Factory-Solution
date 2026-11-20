package io.smarthappy.platform.messaging;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * 토픽 NOTIFY (사용자 알림) — P0 에서는 애플리케이션 로그로만 남긴다.
 * 메일·앱 푸시·메신저 채널과 알림 예산(역할별 상한·묶음)은 P5 알림 설계에서 이 토픽의 송신기를 바꾼다(docs/11 2.8).
 * 알림은 중복이 누락보다 나쁜 채널이라 읽기 타임아웃을 재시도하지 않는다.
 */
@Slf4j
@Component
public class NotifyLogHandler implements OutboxHandler {

    public static final String TOPIC = "NOTIFY";

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public SendResult send(OutboxMessage m) {
        log.info("알림(로그 전용) compCd={} messageId={} payload={}", m.compCd(), m.messageId(), m.payload());
        return SendResult.sent();
    }

    @Override
    public boolean retryOnReadTimeout() {
        return false;
    }
}
