package io.smarthappy.platform.messaging;

import java.util.Map;
import java.util.UUID;

/** 릴레이가 송신기에 넘기는 메시지 (attempts = 이번 시도를 포함한 횟수) */
public record OutboxMessage(long outboxId, String compCd, UUID messageId, String topic, String bizKey,
        Map<String, Object> payload, int attempts) {
}
