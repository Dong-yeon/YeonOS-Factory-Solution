package io.smarthappy.module.cms.edge;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.smarthappy.module.wms.label.PrinterAddress;
import io.smarthappy.module.wms.label.PrinterAddress.AddressException;
import io.smarthappy.platform.messaging.OutboxHandler;
import io.smarthappy.platform.messaging.OutboxMessage;
import tools.jackson.databind.json.JsonMapper;

/**
 * 아웃박스 EQUIP_CMD 송신기 (docs/65 16.4, DIRECT) — LAN 의 장비 어댑터에 `POST {adapter_url}/commands` {cmdKey, equipCd, tagCd, dataType, value, expiresAt}.
 *
 * 트랜잭션·회사 범위 밖(DB 금지). 라벨 프린터와 같은 주소 규칙: 보낼 때마다 이름을 해석해 **사설 대역인지 다시 보고**(공인·메타데이터 주소로 나가지 않게),
 * http 는 검사한 그 주소로 연결한다. 같은 명령이 두 번 갈 수 있다(최소 1회) — 어댑터는 cmdKey 로 한 번만 쓴다(가짜 엣지가 그렇게 한다).
 * 2xx = 보냄, 4xx = 다시 보내도 같음(FAIL — 명령은 시간 초과 때 "안 보냄" 으로 EXPIRED), 5xx·연결 실패 = 재시도.
 */
@Component
public class EquipCmdHandler implements OutboxHandler {

    public static final String TOPIC = "EQUIP_CMD";
    static final JsonMapper JSON = JsonMapper.builder().build();

    private final boolean allowLoopback;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile PrinterAddress.Resolver resolver = PrinterAddress.SYSTEM;

    public EquipCmdHandler(@Value("${happyboot.cms.edge-allow-loopback:false}") boolean allowLoopback) {
        this.allowLoopback = allowLoopback;
    }

    /** 시험용 */
    void setResolver(PrinterAddress.Resolver resolver) {
        this.resolver = resolver;
    }

    /** SaaS 에서는 서버가 사설망 어댑터로 먼저 연결하지 않는다(docs/67 F3). 주입 전 기본값 = 끔(fail-closed) */
    private volatile io.smarthappy.platform.config.LanDirectGate lanDirect = io.smarthappy.platform.config.LanDirectGate.fixed(false);

    @org.springframework.beans.factory.annotation.Autowired
    void setLanDirect(io.smarthappy.platform.config.LanDirectGate lanDirect) {
        this.lanDirect = lanDirect;
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public SendResult send(OutboxMessage m) throws IOException, InterruptedException {
        if (!lanDirect.enabled()) {
            return SendResult.fail("lan direct off");
        }
        if (!(m.payload().get("url") instanceof String url)) {
            return SendResult.fail("bad payload");
        }
        URI target;
        try {
            URI u = new URI(url.endsWith("/") ? url + "commands" : url + "/commands");
            if (u.getHost() == null || !("http".equals(u.getScheme()) || "https".equals(u.getScheme()))) {
                return SendResult.fail("bad url");
            }
            InetAddress addr = PrinterAddress.resolveChecked(u.getHost(), allowLoopback, resolver);
            // http 는 검사한 주소로 바로(이름 다시 해석 없음). https 는 인증서 이름 때문에 이름 그대로 — 검사 직후 연결
            target = "http".equals(u.getScheme())
                    ? new URI("http", null, addr.getHostAddress(), u.getPort(), u.getPath(), null, null) : u;
        } catch (URISyntaxException e) {
            return SendResult.fail("bad url");
        } catch (AddressException e) {
            return SendResult.fail("adapter address " + e.problem());
        }
        Map<String, Object> body = new LinkedHashMap<>(m.payload());
        body.remove("url");
        HttpRequest req = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-Idempotency-Key", String.valueOf(m.payload().get("cmdKey")))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
        HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
        int s = res.statusCode();
        if (s >= 200 && s < 300) {
            return SendResult.sent();
        }
        return s >= 400 && s < 500 && s != 408 && s != 429 ? SendResult.fail("HTTP " + s) : SendResult.retry("HTTP " + s);
    }
}
