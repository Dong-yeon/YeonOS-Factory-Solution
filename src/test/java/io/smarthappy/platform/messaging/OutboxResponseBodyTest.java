package io.smarthappy.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import io.smarthappy.platform.messaging.OutboxHandler.SendResult;
import io.smarthappy.platform.notify.MailNotifyHandler;
import io.smarthappy.platform.push.PushTransport;
import io.smarthappy.platform.push.WebPushCrypto;
import io.smarthappy.platform.push.WebPushHandler;
import io.smarthappy.support.PostgresTestBase;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;

/**
 * 송신 응답 본문은 저장하지 않는다(docs/36 19장 O13, 사용자 결정 Q-O1 2026-10-30).
 * 받는 쪽이 돌려준 글(SMTP 응답 줄·HTTP 응답 본문)에 표시 글자를 심고, 그 글자가 마지막 오류(last_error — 연계 현황 화면에 보임)에 들어가지 않는지 본다.
 * <ul>
 *   <li>릴레이 공통: 송신기가 던진 HTTP 응답 예외(RestClient) — 메시지에 응답 본문이 들어 있어 상태 코드만 남긴다. 감싼 예외도</li>
 *   <li>메일: SMTP 거절·일시 오류·인증 실패·예상 밖 오류 — 사유 코드 + 예외 종류만</li>
 *   <li>Web Push: 전송 계약이 상태 숫자만 돌려준다(본문을 읽지 않음) — 상태별 고정 문구</li>
 * </ul>
 * ERP 어댑터(`ErpPort.ErpResult.detail`)는 MES 소유 계약 — 짧은 사유만 싣는다는 규칙을 docs/36 19장 O13 에 적고 MES 에 넘긴다
 */
@TestPropertySource(properties = {"happyboot.mail.enabled=true", "happyboot.mail.from=noreply@yeonos.test",
        "happyboot.mail.base-url=https://mes.example.test/", "spring.mail.host=smtp.invalid"})
class OutboxResponseBodyTest extends PostgresTestBase {

    static final String BODY = "RESPONSE-BODY-" + UUID.randomUUID();
    static final String TEST_TOPIC = "TEST_RESPONSE_BODY";
    static final KeyPair VAPID = WebPushCrypto.newKeyPair();
    /** 시험 송신기가 던질 예외 — 시험마다 바꾼다 */
    static final AtomicReference<Exception> THROW = new AtomicReference<>();

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry r) {
        r.add("happyboot.push.vapid-public-key", () -> WebPushCrypto.b64(WebPushCrypto.raw((ECPublicKey) VAPID.getPublic())));
        r.add("happyboot.push.vapid-private-key", () -> WebPushCrypto.b64(WebPushCrypto.raw((ECPrivateKey) VAPID.getPrivate())));
        r.add("happyboot.push.subject", () -> "mailto:ops@example.test");
    }

    @TestConfiguration
    static class ThrowingHandler {
        @Bean
        OutboxHandler responseBodyTestHandler() {
            return new OutboxHandler() {
                @Override
                public String topic() {
                    return TEST_TOPIC;
                }

                @Override
                public SendResult send(OutboxMessage m) throws Exception {
                    throw THROW.get();
                }
            };
        }
    }

    @MockitoBean
    JavaMailSender mail;

    @MockitoBean
    PushTransport transport;

    @Autowired
    OutboxRelay relay;

    @Autowired
    MailNotifyHandler mailHandler;

    @Autowired
    WebPushHandler pushHandler;

    @BeforeEach
    void setUp() {
        reset(mail, transport);
    }

    static OutboxMessage msg(String topic, Map<String, Object> payload) {
        return new OutboxMessage(1L, "ANY", UUID.randomUUID(), topic, "BIZ:1", payload, 0);
    }

    SendResult relayWith(Exception e) {
        THROW.set(e);
        return relay.send(msg(TEST_TOPIC, Map.of()));
    }

    /** 실제 RestClient 호출(가짜 서버)이 던지는 그대로의 예외 — 어댑터가 RestClient 를 쓰면 이것이 송신기 밖으로 나온다 */
    static RestClientResponseException httpError(HttpStatus status, String body) {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo("https://erp.example.test/doc")).andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body));
        try {
            b.build().post().uri("https://erp.example.test/doc").retrieve().toBodilessEntity();
        } catch (RestClientResponseException e) {
            return e;
        }
        throw new AssertionError("오류 응답인데 예외가 없음");
    }

    @Test
    void 릴레이는_HTTP_응답_예외의_본문을_남기지_않고_상태만() {
        String body = "{\"error\":\"" + BODY + "\",\"customer\":\"홍길동 010-1234-5678\"}";
        RestClientResponseException bad = httpError(HttpStatus.BAD_REQUEST, body);
        assertThat(bad.getMessage()).contains(BODY);   // 전제: RestClient 기본 오류 처리가 만든 예외의 메시지는 응답 본문을 담는다

        SendResult r = relayWith(bad);
        assertThat(r.outcome()).isEqualTo(OutboxHandler.Outcome.RETRY);
        assertThat(r.error()).isEqualTo("BadRequest: HTTP 400").doesNotContain(BODY).doesNotContain("010-1234");

        // 어댑터가 감싸 던져도(감싼 예외의 메시지는 원인 메시지를 그대로 담는다)
        assertThat(relayWith(new IllegalStateException(bad)).error()).isEqualTo("IllegalStateException: HTTP 400").doesNotContain(BODY);
        assertThat(relayWith(httpError(HttpStatus.SERVICE_UNAVAILABLE, body)).error()).isEqualTo("ServiceUnavailable: HTTP 503");

        // 응답이 아닌 오류(연결 실패 등 이쪽 사정)는 진단에 필요해 그대로 남긴다
        assertThat(relayWith(new IOException("Connection refused")).error()).isEqualTo("IOException: Connection refused");
    }

    @Test
    void 메일_송신기는_SMTP_응답_줄을_남기지_않는다() throws Exception {
        Map<String, Object> ok = Map.of("to", "x@y.test", "subject", "s", "body", "b");
        Exception[] replies = {
                new MailSendException(Map.of(new Object(), new SendFailedException("550 5.1.1 " + BODY))),
                new MailSendException(Map.of(new Object(), new MessagingException("421 " + BODY))),
                new MailAuthenticationException("535 5.7.8 " + BODY),
                new IllegalStateException(BODY)};
        for (Exception reply : replies) {
            doThrow(reply).when(mail).send(any(SimpleMailMessage.class));
            SendResult r = mailHandler.send(msg(MailNotifyHandler.TOPIC, ok));
            assertThat(r.outcome()).isNotEqualTo(OutboxHandler.Outcome.SENT);
            assertThat(r.error()).as(reply.getClass().getSimpleName()).doesNotContain(BODY).doesNotContain("x@y.test");
        }
    }

    @Test
    void 푸시_송신기는_상태_숫자만_받아_고정_문구로() throws Exception {
        // 계약: 전송은 상태 코드만 돌려준다 — 본문을 받을 길이 없다
        assertThat(PushTransport.class.getMethod("post", String.class, Map.class, byte[].class).getReturnType()).isEqualTo(int.class);

        byte[] auth = new byte[16];
        new SecureRandom().nextBytes(auth);
        Map<String, Object> p = Map.of("endpoint", "https://fcm.googleapis.com/fcm/send/abc",
                "p256dh", WebPushCrypto.b64(WebPushCrypto.raw((ECPublicKey) WebPushCrypto.newKeyPair().getPublic())),
                "auth", WebPushCrypto.b64(auth), "body", "{\"t\":\"x\",\"u\":\"/main\"}");
        Map<Integer, String> expected = Map.of(400, "HTTP 400", 403, "HTTP 403", 410, WebPushHandler.GONE + ": 구독이 사라짐(410)",
                429, "HTTP 429", 500, "HTTP 500");
        for (Map.Entry<Integer, String> e : expected.entrySet()) {
            when(transport.post(anyString(), anyMap(), any())).thenReturn(e.getKey());
            assertThat(pushHandler.send(msg(WebPushHandler.TOPIC, p)).error()).isEqualTo(e.getValue());
        }
        when(transport.post(anyString(), anyMap(), any())).thenThrow(new HttpTimeoutException(BODY));
        assertThat(pushHandler.send(msg(WebPushHandler.TOPIC, p)).error()).isEqualTo("timeout");
    }
}
