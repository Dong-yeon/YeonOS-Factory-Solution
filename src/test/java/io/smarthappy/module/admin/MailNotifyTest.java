package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.smarthappy.platform.messaging.OutboxHandler.Outcome;
import io.smarthappy.platform.messaging.OutboxHandler.SendResult;
import io.smarthappy.platform.messaging.OutboxMessage;
import io.smarthappy.platform.messaging.OutboxRelay;
import io.smarthappy.platform.notify.MailNotifyHandler;
import io.smarthappy.platform.notify.MailSettings;
import io.smarthappy.platform.notify.NotificationService;
import io.smarthappy.platform.notify.NotifyMessage;
import io.smarthappy.platform.notify.PlatformNotifyEvents;
import io.smarthappy.platform.tenant.TenantRunner;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;

/**
 * 메일 알림 (docs/36 18.3, Q12) — 알림함에 새로 들어간 것만 아웃박스 MAIL 로, 받는 사람 언어로, 구독·주소가 있을 때만.
 * SMTP 는 JavaMailSender 를 가짜로 바꿔 무엇을 보냈는지만 본다. 이상 사용: 머리 주입·주소 없음·중복·다른 회사·실패 분류
 */
@TestPropertySource(properties = {"happyboot.mail.enabled=true", "happyboot.mail.from=noreply@yeonos.test",
        "happyboot.mail.base-url=https://mes.example.test/", "spring.mail.host=smtp.invalid"})
class MailNotifyTest extends SysAdminTestSupport {

    @MockitoBean
    JavaMailSender sender;

    @Autowired
    TenantRunner runner;

    @Autowired
    NotificationService notify;

    @Autowired
    OutboxRelay relay;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    MailNotifyHandler handler;

    @BeforeEach
    void setUp() {
        setUpCompany("ML");
        reset(sender);
        accounts.exec(comp, "update sys_user set email = 'worker@factory.test' where user_id = ?", workerId);
    }

    private NotificationService.Result send(String compCd, NotifyMessage m) {
        return runner.call(compCd, () -> notify.send(m));
    }

    private static NotifyMessage msg(long userId, String key) {
        return NotifyMessage.of(PlatformNotifyEvents.APR_DONE, userId, key, "notify.apr.approved", "설비 점검표 " + key);
    }

    private void subscribe(String body) throws Exception {
        mvc.perform(json(put("/api/platform/inbox/subscriptions"), body).with(worker)).andExpect(status().isOk());
    }

    private List<Map<String, Object>> mails(String compCd) {
        return runner.call(compCd, () -> jdbc.queryForList(
                "select biz_key, payload::text as payload from sys_outbox where topic = 'MAIL' order by outbox_id"));
    }

    @Test
    void 구독_화면은_메일_칸과_주소_여부를_주고_메일만_따로_저장된다() throws Exception {
        mvc.perform(get("/api/platform/inbox/subscriptions").with(worker))
                .andExpect(jsonPath("$.list[0].mailAvailable").value(true))
                .andExpect(jsonPath("$.list[?(@.eventCd=='APR_DONE')].mailYn").value(Matchers.contains("N")));   // 기본 끔
        subscribe("[{\"eventCd\":\"APR_DONE\",\"inboxYn\":\"Y\",\"mailYn\":\"Y\"}]");
        // mailYn 없이 저장(예전 화면·알림함만 바꿈) = 메일 값 그대로
        subscribe("[{\"eventCd\":\"APR_DONE\",\"inboxYn\":\"Y\"}]");
        mvc.perform(get("/api/platform/inbox/subscriptions").with(worker))
                .andExpect(jsonPath("$.list[?(@.eventCd=='APR_DONE')].mailYn").value(Matchers.contains("Y")));
        mvc.perform(json(put("/api/platform/inbox/subscriptions"), "[{\"eventCd\":\"APR_DONE\",\"inboxYn\":\"Y\",\"mailYn\":\"X\"}]")
                .with(worker)).andExpect(status().isBadRequest());
        // 주소 없는 사람 = 메일 칸 없음
        mvc.perform(get("/api/platform/inbox/subscriptions").with(hr)).andExpect(jsonPath("$.list[0].mailAvailable").value(false));
    }

    @Test
    void 메일을_켠_알림만_받는_사람_언어로_아웃박스에_실리고_릴레이가_보낸다() throws Exception {
        assertThat(send(comp, msg(workerId, "M0"))).isEqualTo(NotificationService.Result.SENT);
        assertThat(mails(comp)).isEmpty();                                 // 메일 기본 끔 — 알림함만
        subscribe("[{\"eventCd\":\"APR_DONE\",\"inboxYn\":\"Y\",\"mailYn\":\"Y\"}]");
        assertThat(send(comp, msg(workerId, "M1").withLink("mst-cust", Map.of()))).isEqualTo(NotificationService.Result.SENT);
        assertThat(send(comp, msg(workerId, "M1"))).isEqualTo(NotificationService.Result.DUPLICATE);   // 같은 중복 키 = 메일도 한 번
        List<Map<String, Object>> rows = mails(comp);
        assertThat(rows).hasSize(1);
        String payload = (String) rows.get(0).get("payload");
        assertThat(payload).contains("\"to\": \"worker@factory.test\"", "[YeonOS] 결재 승인", "설비 점검표 M1",
                "https://mes.example.test/page/mst-cust");
        assertThat((String) rows.get(0).get("biz_key")).startsWith("NOTIFY:");
        // 영어 사용자는 영어로
        accounts.exec(comp, "update sys_user set locale_cd = 'en' where user_id = ?", workerId);
        send(comp, msg(workerId, "M2"));
        assertThat((String) mails(comp).get(1).get("payload")).contains("Approved: 설비 점검표 M2", "notification settings");

        assertThat(relay.relay(comp).sent()).isEqualTo(2);
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender, org.mockito.Mockito.times(2)).send(sent.capture());
        SimpleMailMessage first = sent.getAllValues().get(0);
        assertThat(first.getFrom()).isEqualTo("noreply@yeonos.test");
        assertThat(first.getTo()).containsExactly("worker@factory.test");
        assertThat(first.getSubject()).startsWith("[YeonOS] ");
        assertThat(first.getText()).contains("설비 점검표 M1");
    }

    @Test
    void 알림함을_끄면_메일도_없고_주소가_없거나_틀리면_알림함만() throws Exception {
        subscribe("[{\"eventCd\":\"APR_DONE\",\"inboxYn\":\"N\",\"mailYn\":\"Y\"}]");
        assertThat(send(comp, msg(workerId, "N1"))).isEqualTo(NotificationService.Result.UNSUBSCRIBED);
        assertThat(mails(comp)).isEmpty();
        subscribe("[{\"eventCd\":\"APR_DONE\",\"inboxYn\":\"Y\",\"mailYn\":\"Y\"}]");
        // 머리 주입을 노린 주소·빈 주소 → 메일 없음, 알림함은 들어감
        for (String bad : List.of("worker@factory.test\r\nBcc: spy@evil.test", "a b@factory.test", "<x@y.test>", "", "x".repeat(70) + "@a.test")) {
            accounts.exec(comp, "update sys_user set email = ? where user_id = ?", bad, workerId);
            assertThat(send(comp, msg(workerId, "N-" + bad.length()))).isEqualTo(NotificationService.Result.SENT);
        }
        accounts.exec(comp, "update sys_user set email = null where user_id = ?", workerId);
        assertThat(send(comp, msg(workerId, "N-null"))).isEqualTo(NotificationService.Result.SENT);
        assertThat(mails(comp)).isEmpty();
        // 제목 인자에 줄바꿈을 넣어도 메일 제목은 한 줄
        accounts.exec(comp, "update sys_user set email = 'worker@factory.test' where user_id = ?", workerId);
        send(comp, NotifyMessage.of(PlatformNotifyEvents.APR_DONE, workerId, "N-crlf", "notify.apr.approved", "a\r\nBcc: spy@evil.test"));
        relay.relay(comp);
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(sent.capture());
        assertThat(sent.getValue().getSubject()).doesNotContain("\r", "\n");
    }

    @Test
    void B사_사용자는_A사_알림의_메일을_받지_않고_A사_구독은_B사에_없다() throws Exception {
        subscribe("[{\"eventCd\":\"APR_DONE\",\"inboxYn\":\"Y\",\"mailYn\":\"Y\"}]");
        String compB = accounts.newComp("MLB");
        long bUser = accounts.newUser(compB, "buser", PW);
        accounts.exec(compB, "update sys_user set email = 'b@other.test' where user_id = ?", bUser);
        // A사 범위에서 B사 사용자에게 → 없는 사람(RLS), 메일 없음
        assertThat(send(comp, msg(bUser, "X1"))).isEqualTo(NotificationService.Result.NO_USER);
        // B사 범위에서 A사 사용자에게 → 없는 사람
        assertThat(send(compB, msg(workerId, "X2"))).isEqualTo(NotificationService.Result.NO_USER);
        assertThat(mails(comp)).isEmpty();
        assertThat(mails(compB)).isEmpty();
        assertThat(accounts.query(compB, "select count(*) from sys_notify_sub", Integer.class)).isZero();
    }

    @Test
    void 송신기_결과_분류_영구_거절과_읽기_시간_초과는_다시_보내지_않는다() throws Exception {
        Map<String, Object> ok = Map.of("to", "worker@factory.test", "subject", "s", "body", "b");
        assertThat(handler.send(msg(ok)).outcome()).isEqualTo(Outcome.SENT);
        assertThat(handler.send(msg(Map.of("to", "x@y.test\r\nBcc: z@w.test", "subject", "s", "body", "b"))).outcome())
                .isEqualTo(Outcome.FAIL);
        assertThat(handler.send(msg(Map.of("to", "x@y.test"))).outcome()).isEqualTo(Outcome.FAIL);
        assertThat(handler.retryOnReadTimeout()).isFalse();

        doThrow(sendFailure(new SendFailedException("550", null, null, null, new InternetAddress[] {new InternetAddress("x@y.test")})))
                .when(sender).send(any(SimpleMailMessage.class));
        assertThat(handler.send(msg(ok)).outcome()).isEqualTo(Outcome.FAIL);
        doThrow(sendFailure(new jakarta.mail.MessagingException("x", new SocketTimeoutException("Read timed out"))))
                .when(sender).send(any(SimpleMailMessage.class));
        assertThat(handler.send(msg(ok)).outcome()).isEqualTo(Outcome.FAIL);
        doThrow(sendFailure(new jakarta.mail.MessagingException("x", new SocketTimeoutException("Connect timed out"))))
                .when(sender).send(any(SimpleMailMessage.class));
        assertThat(handler.send(msg(ok)).outcome()).isEqualTo(Outcome.RETRY);
        doThrow(sendFailure(new jakarta.mail.MessagingException("421 try later"))).when(sender).send(any(SimpleMailMessage.class));
        SendResult r = handler.send(msg(ok));
        assertThat(r.outcome()).isEqualTo(Outcome.RETRY);
        assertThat(r.error()).doesNotContain("worker@factory.test");   // 송신 오류 기록에 주소를 남기지 않는다
    }

    @Test
    void 켰는데_보내는_주소나_SMTP_서버가_없거나_열기_주소가_틀리면_기동_중단() {
        assertThatThrownBy(() -> new MailSettings(true, "", "", "smtp.test")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MailSettings(true, "a@b.test", "", "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MailSettings(false, "", "javascript:alert(1)", "")).isInstanceOf(IllegalStateException.class);
        assertThat(new MailSettings(false, "", "", "").enabled()).isFalse();
        verify(sender, never()).send(any(SimpleMailMessage.class));
    }

    private static MailSendException sendFailure(Exception cause) {
        return new MailSendException(Map.of(new Object(), cause));
    }

    private OutboxMessage msg(Map<String, Object> payload) {
        return new OutboxMessage(1L, comp, UUID.randomUUID(), MailNotifyHandler.TOPIC, "NOTIFY:1", payload, 0);
    }
}
