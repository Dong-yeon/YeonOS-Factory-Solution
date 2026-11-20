package io.smarthappy.module.wms.label;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.smarthappy.module.wms.label.PrinterAddress.AddressException;
import io.smarthappy.platform.messaging.OutboxHandler;
import io.smarthappy.platform.messaging.OutboxMessage;

/**
 * 아웃박스 LABEL_PRINT 송신기 (docs/30 W2, 27 U11) — 네트워크 라벨 프린터(원시 TCP, 보통 9100)에 ZPL 을 보낸다.
 *
 * 트랜잭션·회사 범위 밖에서 불린다(DB 금지). 보낼 때마다 이름을 해석해 **사설 대역인지 다시 보고 검사한 그 주소로** 연결한다
 * (저장 뒤 DNS 가 바뀌어도 공인·메타데이터 주소로 나가지 않게). 주소 문제는 재시도해도 같으니 FAIL, 연결·쓰기 실패는 재시도.
 * 같은 메시지가 두 번 갈 수 있다(최소 1회) — 라벨 프린터는 멱등키를 모르므로 드물게 한 장 더 나올 수 있고, 누락보다 낫다.
 */
@Component
public class LabelPrintHandler implements OutboxHandler {

    static final int CONNECT_TIMEOUT_MS = 3_000;
    static final int SO_TIMEOUT_MS = 5_000;

    private final boolean allowLoopback;
    private volatile PrinterAddress.Resolver resolver = PrinterAddress.SYSTEM;

    public LabelPrintHandler(@Value("${happyboot.wms.print-allow-loopback:false}") boolean allowLoopback) {
        this.allowLoopback = allowLoopback;
    }

    /** 테스트용 */
    void setResolver(PrinterAddress.Resolver resolver) {
        this.resolver = resolver;
    }

    /** SaaS 에서는 서버가 사설망 프린터로 먼저 연결하지 않는다(docs/67 F3). 주입 전 기본값 = 끔(fail-closed) */
    private volatile io.smarthappy.platform.config.LanDirectGate lanDirect = io.smarthappy.platform.config.LanDirectGate.fixed(false);

    @org.springframework.beans.factory.annotation.Autowired
    void setLanDirect(io.smarthappy.platform.config.LanDirectGate lanDirect) {
        this.lanDirect = lanDirect;
    }

    @Override
    public String topic() {
        return LabelPrintService.TOPIC;
    }

    @Override
    public SendResult send(OutboxMessage m) {
        if (!lanDirect.enabled()) {
            return SendResult.fail("lan direct off");
        }
        Object host = m.payload().get("host");
        Object port = m.payload().get("port");
        Object zpl = m.payload().get("zpl");
        Object copies = m.payload().get("copies");
        if (!(host instanceof String h) || !(port instanceof Number p) || !(zpl instanceof String z) || z.isEmpty()) {
            return SendResult.fail("bad payload");
        }
        int n = copies instanceof Number c ? Math.max(1, Math.min(99, c.intValue())) : 1;
        if (p.intValue() < 1 || p.intValue() > 65535) {
            return SendResult.fail("bad port");
        }
        InetAddress addr;
        try {
            addr = PrinterAddress.resolveChecked(h, allowLoopback, resolver);
        } catch (AddressException e) {
            return SendResult.fail("printer address " + e.problem());
        }
        byte[] bytes = z.getBytes(StandardCharsets.UTF_8);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(addr, p.intValue()), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(SO_TIMEOUT_MS);
            OutputStream out = s.getOutputStream();
            for (int i = 0; i < n; i++) {
                out.write(bytes);
            }
            out.flush();
            s.shutdownOutput();
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            return SendResult.retry(e.getClass().getSimpleName() + ": " + (msg.length() > 200 ? msg.substring(0, 200) : msg));
        }
        return SendResult.sent();
    }
}
