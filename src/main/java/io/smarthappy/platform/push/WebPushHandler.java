package io.smarthappy.platform.push;

import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.messaging.OutboxHandler;
import io.smarthappy.platform.messaging.OutboxMessage;
import lombok.RequiredArgsConstructor;

/**
 * 아웃박스 WEB_PUSH 송신기 (docs/45 9장 N4·N6·N8). 트랜잭션 밖 — DB 를 쓰지 않는다(OutboxHandler 규칙).
 * payload: endpoint·p256dh·auth(받는 기기) + body(기기에서 보일 JSON — 이벤트 이름·여는 주소만, N4).
 * <ul>
 *   <li>보내기 전 허용 호스트 다시 검사(구독 뒤 설정이 바뀌었을 수 있다) — 밖이면 실패 "HOST"</li>
 *   <li>201·200·202 = 보냄 / 404·410 = 사라진 구독 → 실패 "GONE …"(정리 작업이 구독을 지우고 아웃박스 행을 수동 종결)
 *       / 429·5xx = 재시도 / 그 밖 4xx = 실패</li>
 *   <li>푸시 중복은 해가 작다(같은 알림 두 번) — 읽기 타임아웃은 재시도</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class WebPushHandler implements OutboxHandler {

    public static final String TOPIC = "WEB_PUSH";
    public static final String GONE = "GONE";
    /** 푸시 서비스가 기기에 못 닿을 때 들고 있는 시간(초) — 하루 */
    static final int TTL_SECONDS = 86_400;
    static final long VAPID_EXP_SECONDS = 12 * 3600;

    private final PushSettings settings;
    private final PushTransport transport;
    private final Clock clock = Clock.systemUTC();

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public SendResult send(OutboxMessage m) throws Exception {
        if (!settings.enabled()) {
            return SendResult.fail("DISABLED: 푸시 키 설정 없음");
        }
        Map<String, Object> p = m.payload();
        String endpoint = str(p.get("endpoint"));
        if (!settings.allowedEndpoint(endpoint)) {
            return SendResult.fail("HOST: 허용 밖 푸시 서비스");
        }
        byte[] body;
        try {
            body = WebPushCrypto.encrypt(WebPushCrypto.unb64(str(p.get("p256dh"))), WebPushCrypto.unb64(str(p.get("auth"))),
                    str(p.get("body")).getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return SendResult.fail("KEY: 구독 키 모양이 틀림");
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("TTL", String.valueOf(TTL_SECONDS));
        h.put("Content-Encoding", "aes128gcm");
        h.put("Content-Type", "application/octet-stream");
        h.put("Urgency", "normal");
        h.put("Authorization", WebPushCrypto.vapidHeader(PushSettings.audience(endpoint), settings.subject(),
                clock.instant().getEpochSecond() + VAPID_EXP_SECONDS, settings.publicKey(), settings.privateKey()));
        int status;
        try {
            status = transport.post(endpoint, h, body);
        } catch (HttpTimeoutException e) {
            return SendResult.retry("timeout");
        }
        if (status == 200 || status == 201 || status == 202) {
            return SendResult.sent();
        }
        if (status == 404 || status == 410) {
            return SendResult.fail(GONE + ": 구독이 사라짐(" + status + ")");
        }
        if (status == 429 || status >= 500) {
            return SendResult.retry("HTTP " + status);
        }
        return SendResult.fail("HTTP " + status);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
