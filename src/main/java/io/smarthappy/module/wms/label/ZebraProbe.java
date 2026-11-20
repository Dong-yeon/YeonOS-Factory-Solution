package io.smarthappy.module.wms.label;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.smarthappy.module.wms.label.PrinterAddress.AddressException;
import io.smarthappy.module.wms.label.ZebraStatus.Probe;

/**
 * 설치형(DIRECT) 프린터 상태 확인 (docs/65 18.1) — {@code ~HQES} + SGD odometer 를 한 연결로 묻는다.
 * **트랜잭션 밖에서만** 부른다(외부 연결). 주소는 송신기와 같은 검사 — 보낼 때마다 해석·사설 대역·검사한 그 주소로.
 * 읽기는 짧게(전체 {@link #READ_BUDGET_MS}, 4KB) — 프린터가 답하지 않아도 작업 스레드를 오래 잡지 않는다.
 */
@Component
public class ZebraProbe {

    static final int CONNECT_TIMEOUT_MS = 2_000;
    static final int READ_BUDGET_MS = 2_500;
    static final int MAX_BYTES = 4_096;
    static final String QUERY = "~HQES\r\n! U1 getvar \"odometer.total_label_count\"\r\n";

    private final boolean allowLoopback;
    private volatile PrinterAddress.Resolver resolver = PrinterAddress.SYSTEM;

    public ZebraProbe(@Value("${happyboot.wms.print-allow-loopback:false}") boolean allowLoopback) {
        this.allowLoopback = allowLoopback;
    }

    /** 테스트용 */
    void setResolver(PrinterAddress.Resolver resolver) {
        this.resolver = resolver;
    }

    public Probe probe(String host, int port) {
        InetAddress addr;
        try {
            addr = PrinterAddress.resolveChecked(host, allowLoopback, resolver);
        } catch (AddressException e) {
            return Probe.offline("ADDRESS_" + e.problem().name());
        }
        if (port < 1 || port > 65535) {
            return Probe.offline("BAD_PORT");
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(addr, port), CONNECT_TIMEOUT_MS);
            OutputStream out = s.getOutputStream();
            out.write(QUERY.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String text = readSome(s);
            return ZebraStatus.parse(text);
        } catch (IOException e) {
            return Probe.offline(e.getClass().getSimpleName());
        }
    }

    /** 상태 줄 두 개와 odometer 가 다 오거나, 시간·크기 한도까지 읽는다 */
    static String readSome(Socket s) throws IOException {
        long deadline = System.nanoTime() + READ_BUDGET_MS * 1_000_000L;
        InputStream in = s.getInputStream();
        StringBuilder b = new StringBuilder();
        byte[] buf = new byte[512];
        while (b.length() < MAX_BYTES) {
            long left = (deadline - System.nanoTime()) / 1_000_000L;
            if (left <= 0) {
                break;
            }
            s.setSoTimeout((int) Math.max(1, left));
            int n;
            try {
                n = in.read(buf);
            } catch (SocketTimeoutException e) {
                break;
            }
            if (n < 0) {
                break;
            }
            b.append(new String(buf, 0, n, StandardCharsets.US_ASCII));
            String t = b.toString();
            if (t.contains("WARNINGS:") && t.indexOf('"') >= 0 && t.indexOf('"', t.indexOf('"') + 1) > 0) {
                break;
            }
        }
        return b.toString();
    }
}
