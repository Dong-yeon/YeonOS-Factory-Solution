package io.smarthappy.module.srm.notify;

import java.time.Instant;
import java.util.Map;

import io.smarthappy.platform.messaging.OutboxHandler;
import io.smarthappy.platform.messaging.OutboxMessage;
import lombok.extern.slf4j.Slf4j;

/**
 * 발주 통지 송신기 공통 (docs/31 S4·D8) — 트랜잭션·회사 범위 밖에서 불린다: DB 를 쓰지 않고 본문(payload)만으로 메일을 만든다.
 * 1부 채널 = 로그 + 가짜 메일함. 받는 주소가 없으면 재시도해도 같으므로 FAIL(통지 이력에서 보이고 "다시 보내기").
 * 메일은 중복이 누락보다 나쁜 채널이라 읽기 타임아웃은 재시도하지 않는다(01 5.8).
 */
@Slf4j
abstract class PoMailHandler implements OutboxHandler {

    private final SrmMailbox mailbox;

    PoMailHandler(SrmMailbox mailbox) {
        this.mailbox = mailbox;
    }

    abstract String subject(Map<String, Object> p);

    abstract String body(Map<String, Object> p);

    @Override
    public SendResult send(OutboxMessage m) {
        Map<String, Object> p = m.payload();
        Object to = p.get("to");
        if (to == null || to.toString().isBlank()) {
            return SendResult.fail("NO_RECIPIENT");
        }
        boolean first = mailbox.deliver(m.compCd(), new SrmMailbox.Mail(m.messageId(), m.topic(), to.toString(), subject(p), body(p),
                Instant.now()));
        log.info("협력사 통지(가짜 메일함) compCd={} topic={} messageId={} first={}", m.compCd(), m.topic(), m.messageId(), first);
        return SendResult.sent();
    }

    @Override
    public boolean retryOnReadTimeout() {
        return false;
    }

    static String text(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? "" : v.toString();
    }
}
