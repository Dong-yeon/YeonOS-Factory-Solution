package io.smarthappy.module.wms.label;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 시험용 Zebra 비슷한 프린터 (docs/65 18.1) — 받은 ZPL 은 큐에, ~HQES·odometer 질의에는 상태로 답한다.
 * 정상이면 받은 ZPL 의 서식(^XA) 수 × ^PQ 만큼 odometer 가 오른다(찍었다고 침). 오류 비트가 켜져 있으면 받기만 하고 안 찍는다.
 */
final class FakeZebra implements AutoCloseable {

    final ServerSocket server;
    final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    final AtomicLong odometer = new AtomicLong(1000);
    volatile long errorBits;
    volatile long warningBits;
    /** 찍히는 장 수를 막는다(용지 없음 흉내 — 받기만) */
    volatile boolean holdPrinting;
    /** 상태 질의에 답하지 않는다(벤더가 다른 프린터 흉내) */
    volatile boolean silent;

    FakeZebra() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::loop, "fake-zebra");
        t.setDaemon(true);
        t.start();
    }

    private void loop() {
        while (!server.isClosed()) {
            try (Socket s = server.accept(); InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
                s.setSoTimeout(3_000);
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                while (true) {
                    int n;
                    try {
                        n = in.read(buf);
                    } catch (IOException e) {
                        break;
                    }
                    if (n < 0) {
                        break;
                    }
                    b.write(buf, 0, n);
                    String sofar = b.toString(StandardCharsets.UTF_8);
                    if (sofar.contains("~HQES") && sofar.contains("odometer.total_label_count\"\r\n")) {
                        if (!silent) {
                            out.write(status().getBytes(StandardCharsets.US_ASCII));
                            out.flush();
                        }
                        b.reset();
                        break;
                    }
                }
                String zpl = b.toString(StandardCharsets.UTF_8);
                if (!zpl.isEmpty()) {
                    received.add(zpl);
                    if (!holdPrinting && errorBits == 0) {
                        odometer.addAndGet(ZplLayout.labelsPerCopy(zpl));
                    }
                }
            } catch (IOException e) {
                // 닫힘
            }
        }
    }

    String status() {
        return "\u0002\r\n  PRINTER STATUS\r\n   ERRORS:         " + (errorBits == 0 ? "0" : "1") + " 00000000 "
                + String.format("%08X", errorBits) + "\r\n   WARNINGS:       " + (warningBits == 0 ? "0" : "1") + " 00000000 "
                + String.format("%08X", warningBits) + "\r\n\u0003\r\n\"" + odometer.get() + "\"";
    }

    int port() {
        return server.getLocalPort();
    }

    String take() throws InterruptedException {
        return received.poll(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
