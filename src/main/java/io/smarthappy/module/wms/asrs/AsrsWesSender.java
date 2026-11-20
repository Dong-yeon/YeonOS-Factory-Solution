package io.smarthappy.module.wms.asrs;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.smarthappy.module.wms.label.PrinterAddress;
import io.smarthappy.platform.messaging.OutboxHandler;
import io.smarthappy.platform.messaging.OutboxMessage;
import tools.jackson.databind.json.JsonMapper;

/**
 * 아웃박스 ASRS_REQ 송신기 (docs/65 23.2) — 설치형(DIRECT) 자동창고 WES 로 요청을 POST 한다.
 *
 * 트랜잭션·회사 범위 밖(DB 금지). 주소는 보낼 때마다 해석해 사설 대역인지 보고 **검사한 IP 로** 연결한다(DNS 재바인딩 방지 — 라벨 프린터와 같은 검사).
 * 멱등 키 = reqKey(우리가 만든 외부 요청 id, 헤더 X-Idempotency-Key) — 같은 메시지가 두 번 가도 WES 는 같은 요청으로 본다.
 * WES 의 2xx = 보냄. 408·425·429·503·504·연결 실패 = 다시, 그 밖 4xx·5xx = 실패(현장 규약과 같은 분류 — 5.2).
 */
@Component
public class AsrsWesSender implements OutboxHandler {

    static final String TOPIC = "ASRS_REQ";
    static final Set<Integer> RETRY = Set.of(408, 425, 429, 503, 504);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final boolean allowLoopback;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private volatile PrinterAddress.Resolver resolver = PrinterAddress.SYSTEM;

    public AsrsWesSender(@Value("${happyboot.wms.print-allow-loopback:false}") boolean allowLoopback) {
        this.allowLoopback = allowLoopback;
    }

    void setResolver(PrinterAddress.Resolver r) {
        this.resolver = r;
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public SendResult send(OutboxMessage m) {
        Object url = m.payload().get("url");
        Object key = m.payload().get("reqKey");
        Object body = m.payload().get("body");
        if (!(url instanceof String u) || !(key instanceof String k) || body == null) {
            return SendResult.fail("bad payload");
        }
        URI base;
        try {
            base = URI.create(u);
        } catch (IllegalArgumentException e) {
            return SendResult.fail("bad url");
        }
        InetAddress addr;
        try {
            addr = PrinterAddress.resolveChecked(base.getHost(), allowLoopback, resolver);
        } catch (PrinterAddress.AddressException e) {
            return SendResult.fail("wes address " + e.problem());
        }
        String host = addr.getHostAddress().contains(":") ? "[" + addr.getHostAddress() + "]" : addr.getHostAddress();
        URI target = URI.create(base.getScheme() + "://" + host + (base.getPort() < 0 ? "" : ":" + base.getPort())
                + (base.getPath() == null || base.getPath().isEmpty() ? "" : base.getPath().replaceAll("/+$", "")) + "/requests");
        HttpRequest req = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("X-Idempotency-Key", k)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        try {
            HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
            int s = res.statusCode();
            if (s >= 200 && s < 300) {
                return SendResult.sent();
            }
            return RETRY.contains(s) ? SendResult.retry("WES " + s) : SendResult.fail("WES " + s);
        } catch (HttpTimeoutException e) {
            return SendResult.retry("timeout");
        } catch (IOException e) {
            return SendResult.retry(e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.retry("interrupted");
        }
    }
}
