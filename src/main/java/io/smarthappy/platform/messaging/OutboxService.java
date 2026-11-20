package io.smarthappy.platform.messaging;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.tenant.TenantContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * 아웃박스 발행 (01 5.8). **업무 트랜잭션 안에서만**(MANDATORY) — 업무가 롤백되면 발행도 없다.
 * 보내는 일은 {@link OutboxRelay} 가 트랜잭션 밖에서 한다.
 */
@Service
public class OutboxService {

    static final Pattern TOPIC = Pattern.compile("^[A-Z][A-Z0-9_]{1,49}$");
    /** 메시지 본문 상한 — 큰 데이터는 키만 싣고 소비자가 다시 읽는다 */
    static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final Set<String> topics;

    public OutboxService(JdbcTemplate jdbc, List<OutboxHandler> handlers) {
        this.jdbc = jdbc;
        this.topics = handlers.stream().map(OutboxHandler::topic).collect(Collectors.toUnmodifiableSet());
    }

    /** 발행하고 메시지 ID(수신측 멱등키)를 돌려준다. 송신기가 없는 토픽은 프로그래밍 오류 */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String topic, String bizKey, Map<String, ?> payload) {
        if (topic == null || !TOPIC.matcher(topic).matches() || !topics.contains(topic)) {
            throw new IllegalArgumentException("송신기가 없는 아웃박스 토픽: " + topic);
        }
        String json = JSON.writeValueAsString(payload == null ? Map.of() : payload);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("아웃박스 본문이 너무 큽니다 (키만 싣고 소비자가 다시 읽을 것)");
        }
        UUID messageId = UUID.randomUUID();
        jdbc.update("""
                insert into sys_outbox (comp_cd, message_id, topic, biz_key, payload, created_by)
                values (?, ?, ?, ?, ?::jsonb, ?)""", TenantContext.requireCompCd(), messageId, topic,
                bizKey == null ? null : bizKey.length() > 100 ? bizKey.substring(0, 100) : bizKey, json,
                TenantContext.currentActor());
        return messageId;
    }
}
