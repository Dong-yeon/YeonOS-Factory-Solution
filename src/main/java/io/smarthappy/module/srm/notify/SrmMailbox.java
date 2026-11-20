package io.smarthappy.module.srm.notify;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 가짜 메일함 (docs/31 D8) — 실제 SMTP 대신 협력사에게 "보냈을" 메일을 회사별로 최근 {@value #MAX} 통만 메모리에 둔다.
 * 테스트·데모에서 보낸 내용을 확인하는 용도. 같은 아웃박스 메시지가 두 번 와도 한 통(수신측 멱등 — messageId).
 * 실제 메일 채널이 정해지면 이 클래스 대신 메일 송신기를 같은 토픽에 붙인다.
 */
@Component
public class SrmMailbox {

    static final int MAX = 100;

    public record Mail(UUID messageId, String topic, String to, String subject, String body, Instant receivedAt) {
    }

    private final Map<String, Deque<Mail>> boxes = new ConcurrentHashMap<>();

    /** 처음 받은 메시지면 true */
    public boolean deliver(String compCd, Mail mail) {
        Deque<Mail> box = boxes.computeIfAbsent(compCd, k -> new ArrayDeque<>());
        synchronized (box) {
            if (box.stream().anyMatch(m -> m.messageId().equals(mail.messageId()))) {
                return false;
            }
            box.addFirst(mail);
            while (box.size() > MAX) {
                box.removeLast();
            }
            return true;
        }
    }

    /** 최근 순 */
    public List<Mail> list(String compCd) {
        Deque<Mail> box = boxes.get(compCd);
        if (box == null) {
            return List.of();
        }
        synchronized (box) {
            return new ArrayList<>(box);
        }
    }
}
