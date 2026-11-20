package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.messaging.OutboxRelay;
import io.smarthappy.platform.messaging.OutboxService;
import io.smarthappy.platform.notify.MailFailReason;
import io.smarthappy.platform.notify.MailNotifyHandler;
import io.smarthappy.platform.notify.MailService;
import io.smarthappy.platform.notify.MailService.Recipient;
import io.smarthappy.platform.notify.MailStatus;
import io.smarthappy.platform.notify.MailStatus.State;
import io.smarthappy.platform.tenant.TenantRunner;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;

/**
 * 외부 메일 발송 결과 읽기 (docs/36 18.5) — 상태·사유 코드, 오류 기록에 주소·계정·비밀번호가 남지 않음(R4),
 * 지금 회사의 외부 메일만(A사로 B사·사내 알림 메일 안 보임), 상한·빈 입력. SMTP 는 JavaMailSender 가짜
 */
@TestPropertySource(properties = {"happyboot.mail.enabled=true", "happyboot.mail.from=noreply@yeonos.test",
        "happyboot.mail.base-url=https://mes.example.test/", "spring.mail.host=smtp.invalid"})
class MailStatusTest extends SysAdminTestSupport {

    static final String TO = "buyer@customer.test";
    static final String SMTP_USER = "smtp-user-77";
    static final String SMTP_PASSWORD = "S3cret-pw!";

    @MockitoBean
    JavaMailSender sender;

    @Autowired
    MailService mail;

    @Autowired
    OutboxService outbox;

    @Autowired
    TenantRunner runner;

    @Autowired
    OutboxRelay relay;

    @BeforeEach
    void setUp() {
        setUpCompany("MT");
        reset(sender);
    }

    private UUID publish(String bizKey) {
        return runner.call(comp, () -> mail.publish(bizKey, new Recipient(TO, "김 구매"), "[PCN] 안내", "본문"));
    }

    private MailStatus status(UUID id) {
        return runner.call(comp, () -> mail.status(id)).orElseThrow();
    }

    private String lastError(UUID id) {
        return accounts.query(comp, "select coalesce(last_error, '') from sys_outbox where message_id = ?", String.class, id);
    }

    /** 오류 기록·결과 어디에도 주소·계정·비밀번호가 없다 */
    private void assertNoSecret(UUID id) {
        assertThat(lastError(id)).doesNotContain(TO, "buyer", SMTP_USER, SMTP_PASSWORD);
        assertThat(status(id).toString()).doesNotContain(TO, SMTP_USER, SMTP_PASSWORD);
    }

    private void failWith(RuntimeException e) {
        doThrow(e).when(sender).send(any(SimpleMailMessage.class));
    }

    private static MailSendException sendFailure(Exception cause) {
        return new MailSendException(Map.of(new Object(), cause));
    }

    @Test
    void 발행_직후는_대기_보내면_보냄과_보낸_시각() {
        UUID id = publish("PCN:1:CUST:1");
        MailStatus s = status(id);
        assertThat(s.state()).isEqualTo(State.PENDING);
        assertThat(s.reason()).isNull();
        assertThat(s.attempts()).isZero();
        assertThat(relay.relay(comp).sent()).isEqualTo(1);
        s = status(id);
        assertThat(s.state()).isEqualTo(State.SENT);
        assertThat(s.sentAt()).isNotNull();
        assertThat(s.attempts()).isEqualTo(1);
        assertThat(s.reason()).isNull();
        assertThat(s.messageId()).isEqualTo(id);
    }

    @Test
    void 받는_주소_영구_거절은_REJECTED_오류_기록에_주소가_없다() throws Exception {
        UUID id = publish("PCN:1:CUST:2");
        failWith(sendFailure(new SendFailedException("550 5.1.1 <" + TO + ">: user unknown", null, null, null,
                new InternetAddress[] {new InternetAddress(TO)})));
        relay.relay(comp);
        assertThat(status(id).state()).isEqualTo(State.FAILED);
        assertThat(status(id).reason()).isEqualTo(MailFailReason.REJECTED);
        assertThat(lastError(id)).startsWith("REJECTED: ");
        assertNoSecret(id);
    }

    @Test
    void 인증_실패는_AUTH_계정과_비밀번호가_오류_기록에_없다() {
        UUID id = publish("PCN:1:CUST:3");
        failWith(new MailAuthenticationException("535 5.7.8 auth failed for " + SMTP_USER + " password=" + SMTP_PASSWORD));
        relay.relay(comp);
        assertThat(status(id).state()).isEqualTo(State.FAILED);
        assertThat(status(id).reason()).isEqualTo(MailFailReason.AUTH);
        assertThat(lastError(id)).isEqualTo("AUTH: MailAuthenticationException");
        assertNoSecret(id);
    }

    @Test
    void 일시_오류는_대기_중_TEMPORARY_상한에_닿으면_FAILED_MAX_ATTEMPTS() {
        UUID id = publish("PCN:1:CUST:4");
        failWith(sendFailure(new jakarta.mail.MessagingException("421 4.7.0 " + TO + " try later, user " + SMTP_USER)));
        relay.relay(comp);
        MailStatus s = status(id);
        assertThat(s.state()).isEqualTo(State.PENDING);
        assertThat(s.reason()).isEqualTo(MailFailReason.TEMPORARY);
        assertThat(s.attempts()).isEqualTo(1);
        assertNoSecret(id);
        // 상한 직전으로 당겨 한 번 더 실패 → 최종 실패
        accounts.exec(comp, "update sys_outbox set max_attempts = 2, next_retry_at = now() where message_id = ?", id);
        relay.relay(comp);
        s = status(id);
        assertThat(s.state()).isEqualTo(State.FAILED);
        assertThat(s.reason()).isEqualTo(MailFailReason.MAX_ATTEMPTS);
        assertNoSecret(id);
    }

    @Test
    void 송신기_안_예상_밖_오류도_메시지_없이_TEMPORARY() {
        UUID id = publish("PCN:1:CUST:5");
        failWith(new IllegalStateException("boom while sending to " + TO + " as " + SMTP_USER + ":" + SMTP_PASSWORD));
        relay.relay(comp);
        assertThat(status(id).state()).isEqualTo(State.PENDING);
        assertThat(status(id).reason()).isEqualTo(MailFailReason.TEMPORARY);
        assertThat(lastError(id)).isEqualTo("TEMPORARY: IllegalStateException");
        assertNoSecret(id);
    }

    @Test
    void 연결_뒤_시간_초과는_FAILED_TIMEOUT_NO_RETRY() {
        UUID id = publish("PCN:1:CUST:6");
        failWith(sendFailure(new jakarta.mail.MessagingException(TO, new SocketTimeoutException("Read timed out"))));
        relay.relay(comp);
        assertThat(status(id).state()).isEqualTo(State.FAILED);
        assertThat(status(id).reason()).isEqualTo(MailFailReason.TIMEOUT_NO_RETRY);
        assertNoSecret(id);
    }

    @Test
    void 코드_이전_기록은_UNKNOWN_집을_때_상한은_MAX_ATTEMPTS() {
        UUID a = publish("PCN:2:CUST:1");
        UUID b = publish("PCN:2:CUST:2");
        accounts.exec(comp, "update sys_outbox set status = 'FAIL', last_error = 'SMTP 거절' where message_id = ?", a);
        accounts.exec(comp, "update sys_outbox set attempts = 3, max_attempts = 3 where message_id = ?", b);
        relay.relay(comp);
        assertThat(status(a).reason()).isEqualTo(MailFailReason.UNKNOWN);
        assertThat(status(b).state()).isEqualTo(State.FAILED);
        assertThat(status(b).reason()).isEqualTo(MailFailReason.MAX_ATTEMPTS);
    }

    @Test
    void A사_메일은_B사에서_없고_사내_알림_메일도_결과에_없다() {
        UUID mine = publish("PCN:3:CUST:1");
        UUID internal = runner.call(comp, () -> outbox.publish(MailNotifyHandler.TOPIC, "NOTIFY:1",
                Map.of("kind", "NOTIFY", "to", "worker@factory.test", "subject", "s", "body", "b")));
        String other = accounts.newComp("MTB");
        assertThat(runner.call(other, () -> mail.status(mine))).isEmpty();
        assertThat(runner.call(other, () -> mail.statuses(List.of(mine)))).isEmpty();
        assertThat(runner.call(comp, () -> mail.status(internal))).isEmpty();
        assertThat(runner.call(comp, () -> mail.statuses(List.of(mine, internal, UUID.randomUUID())))).containsOnlyKeys(mine);
    }

    @Test
    void 여러_통_한번에_null_빈_목록_중복_상한() {
        UUID a = publish("PCN:4:CUST:1");
        UUID b = publish("PCN:4:CUST:2");
        assertThat(runner.call(comp, () -> mail.statuses(null))).isEmpty();
        assertThat(runner.call(comp, () -> mail.statuses(List.of()))).isEmpty();
        assertThat(runner.call(comp, () -> mail.statuses(Arrays.asList(a, null, a, b)))).containsOnlyKeys(a, b);
        assertThat(runner.call(comp, () -> mail.status(null))).isEmpty();
        List<UUID> many = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            many.add(UUID.randomUUID());
        }
        Throwable t = catchThrowable(() -> runner.call(comp, () -> mail.statuses(many)));
        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).fieldErrors()).extracting(f -> f.field() + ":" + f.code()).containsExactly("ids:TOO_LONG");
        assertThat(runner.call(comp, () -> mail.statuses(many.subList(0, 500)))).isEmpty();
    }

    @Test
    void 회사_범위_밖에서는_읽을_수_없다() {
        UUID id = publish("PCN:5:CUST:1");
        assertThat(catchThrowable(() -> mail.status(id))).isNotNull();
    }
}
