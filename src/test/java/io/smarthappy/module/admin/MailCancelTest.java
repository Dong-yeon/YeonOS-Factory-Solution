package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.OutboxRelay;
import io.smarthappy.platform.messaging.OutboxService;
import io.smarthappy.platform.notify.MailNotifyHandler;
import io.smarthappy.platform.notify.MailService;
import io.smarthappy.platform.notify.MailService.Recipient;
import io.smarthappy.platform.notify.MailStatus.State;
import io.smarthappy.platform.tenant.TenantRunner;

/**
 * 외부 메일 발행 취소 (docs/36 18.7) — 대기만 CANCELED, 릴레이와 동시에 일어나면 한쪽만 이긴다(필수),
 * 보낸 뒤·두 번 409, A사로 B사·사내 알림 메일 404, 장애(FAIL)와 따로 센다. SMTP 는 JavaMailSender 가짜
 */
@TestPropertySource(properties = {"happyboot.mail.enabled=true", "happyboot.mail.from=noreply@yeonos.test",
        "happyboot.mail.base-url=https://mes.example.test/", "spring.mail.host=smtp.invalid"})
class MailCancelTest extends SysAdminTestSupport {

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
        setUpCompany("MC");
        reset(sender);
    }

    private UUID publish(String bizKey) {
        return runner.call(comp, () -> mail.publish(bizKey, Recipient.of("buyer@customer.test"), "s", "b"));
    }

    private void cancel(String compCd, UUID id) {
        runner.run(compCd, () -> mail.cancel(id));
    }

    private HbErrorCode codeOf(Runnable r) {
        try {
            r.run();
        } catch (BusinessException e) {
            return e.code();
        }
        return null;
    }

    private String rowStatus(UUID id) {
        return accounts.query(comp, "select status from sys_outbox where message_id = ?", String.class, id);
    }

    @Test
    void 대기_중이면_취소되고_릴레이가_보내지_않는다_결과는_CANCELED() {
        UUID id = publish("PCN:1:CUST:1");
        cancel(comp, id);
        assertThat(rowStatus(id)).isEqualTo("CANCELED");
        assertThat(relay.relay(comp).total()).isZero();
        verify(sender, never()).send(any(SimpleMailMessage.class));
        assertThat(runner.call(comp, () -> mail.status(id)).orElseThrow().state()).isEqualTo(State.CANCELED);
        // 장애로 세는 FAIL 에 섞이지 않는다
        assertThat(accounts.query(comp, "select count(*) from sys_outbox where status = 'FAIL'", Integer.class)).isZero();
    }

    @Test
    void 재시도_대기도_취소되고_결과는_더_보내지_않음_STOPPED() {
        UUID id = publish("PCN:1:CUST:2");
        accounts.exec(comp, "update sys_outbox set attempts = 2, next_retry_at = now() + interval '1 hour', last_error = 'TEMPORARY: x' where message_id = ?", id);
        cancel(comp, id);
        assertThat(rowStatus(id)).isEqualTo("CANCELED");
        // 한 번 이상 보내 봤다 — 직전 시도가 배달됐을 수 있으니 "보내기 전 취소" 가 아니라 "더 보내지 않음"(QA M-1)
        assertThat(runner.call(comp, () -> mail.status(id)).orElseThrow().state()).isEqualTo(State.STOPPED);
        assertThat(runner.call(comp, () -> mail.status(id)).orElseThrow().reason()).isNull();
    }

    @Test
    void 보낸_뒤_두_번째_보내는_중은_409() throws Exception {
        UUID sent = publish("PCN:2:CUST:1");
        relay.relay(comp);
        assertThat(codeOf(() -> cancel(comp, sent))).isEqualTo(HbErrorCode.MAIL_NOT_CANCELABLE);

        UUID twice = publish("PCN:2:CUST:2");
        cancel(comp, twice);
        assertThat(codeOf(() -> cancel(comp, twice))).isEqualTo(HbErrorCode.MAIL_NOT_CANCELABLE);

        // 송신기 안에서 붙잡아 SENDING 인 동안 취소 → 409, 붙잡기를 풀면 보낸다
        UUID sending = publish("PCN:2:CUST:3");
        CountDownLatch inSend = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(i -> {
            inSend.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(sender).send(any(SimpleMailMessage.class));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> f = pool.submit(() -> relay.relay(comp));
            assertThat(inSend.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(rowStatus(sending)).isEqualTo("SENDING");
            assertThat(codeOf(() -> cancel(comp, sending))).isEqualTo(HbErrorCode.MAIL_NOT_CANCELABLE);
            release.countDown();
            f.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(rowStatus(sending)).isEqualTo("SENT");
    }

    @Test
    void 릴레이와_취소가_동시에_일어나면_한쪽만_이긴다() throws Exception {
        AtomicInteger sends = new AtomicInteger();
        doAnswer(i -> {
            sends.incrementAndGet();
            return null;
        }).when(sender).send(any(SimpleMailMessage.class));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        int canceledWins = 0;
        int relayWins = 0;
        try {
            for (int round = 0; round < 20; round++) {
                UUID id = publish("PCN:9:R:" + round);
                int before = sends.get();
                CyclicBarrier go = new CyclicBarrier(2);
                Future<HbErrorCode> c = pool.submit(() -> {
                    go.await(10, TimeUnit.SECONDS);
                    return codeOf(() -> cancel(comp, id));
                });
                Future<?> r = pool.submit(() -> {
                    go.await(10, TimeUnit.SECONDS);
                    return relay.relay(comp);
                });
                HbErrorCode cancelResult = c.get(20, TimeUnit.SECONDS);
                r.get(20, TimeUnit.SECONDS);
                relay.relay(comp);   // 취소가 이겼으면 이 뒤에도 안 나간다
                String st = rowStatus(id);
                int sentNow = sends.get() - before;
                if (cancelResult == null) {
                    assertThat(st).as("round %d", round).isEqualTo("CANCELED");
                    assertThat(sentNow).as("취소가 이기면 메일 0통 round %d", round).isZero();
                    canceledWins++;
                } else {
                    assertThat(cancelResult).isEqualTo(HbErrorCode.MAIL_NOT_CANCELABLE);
                    assertThat(st).as("round %d", round).isEqualTo("SENT");
                    assertThat(sentNow).as("릴레이가 이기면 정확히 1통 round %d", round).isEqualTo(1);
                    relayWins++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(canceledWins + relayWins).isEqualTo(20);
    }

    @Test
    void A사로_B사_사내_알림_메일_없는_id_null은_404_트랜잭션_밖은_거부() {
        UUID mine = publish("PCN:3:CUST:1");
        UUID internal = runner.call(comp, () -> outbox.publish(MailNotifyHandler.TOPIC, "NOTIFY:1",
                Map.of("kind", "NOTIFY", "to", "worker@factory.test", "subject", "s", "body", "b")));
        String other = accounts.newComp("MCB");
        assertThat(codeOf(() -> cancel(other, mine))).isEqualTo(HbErrorCode.NOT_FOUND);
        assertThat(rowStatus(mine)).isEqualTo("NEW");
        assertThat(codeOf(() -> cancel(comp, internal))).isEqualTo(HbErrorCode.NOT_FOUND);
        assertThat(codeOf(() -> cancel(comp, UUID.randomUUID()))).isEqualTo(HbErrorCode.NOT_FOUND);
        assertThat(codeOf(() -> cancel(comp, null))).isEqualTo(HbErrorCode.NOT_FOUND);
        assertThat(catchThrowable(() -> mail.cancel(mine))).isNotNull();
        assertThat(rowStatus(mine)).isEqualTo("NEW");
    }

    @Test
    void 업무가_롤백되면_취소도_없다() {
        UUID id = publish("PCN:4:CUST:1");
        catchThrowable(() -> runner.run(comp, () -> {
            mail.cancel(id);
            throw new IllegalStateException("업무 실패");
        }));
        assertThat(rowStatus(id)).isEqualTo("NEW");
    }
}
