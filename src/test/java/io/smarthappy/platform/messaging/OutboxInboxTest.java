package io.smarthappy.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.messaging.OutboxHandler.SendResult;
import io.smarthappy.platform.messaging.OutboxRelay.RelayResult;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import io.smarthappy.support.TestMessaging.Call;
import io.smarthappy.support.TestMessaging.EchoHandler;
import io.smarthappy.support.TestMessaging.TimeoutHandler;

/** 아웃박스(발행·릴레이 3단)·인박스(수신 멱등) — P0 7단계 */
class OutboxInboxTest extends PostgresTestBase {

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OutboxService outbox;

    @Autowired
    OutboxRelay relay;

    @Autowired
    InboxService inbox;

    String comp;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("OB");
        EchoHandler.reset();
        TimeoutHandler.CALLS.set(0);
    }

    private UUID publish(String topic, String key) {
        return runner.call(comp, () -> outbox.publish(topic, key, Map.of("k", key)));
    }

    private Map<String, Object> row(UUID messageId) {
        return runner.call(comp, () -> jdbc.queryForMap("""
                select status, attempts, last_error, sent_at, locked_at, next_retry_at > now() as later
                  from sys_outbox where message_id = ?""", messageId));
    }

    // ---------- 발행 ----------

    @Test
    void 발행은_업무_트랜잭션_안에서만() {
        assertThatThrownBy(() -> outbox.publish(EchoHandler.TOPIC, "x", Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void 송신기가_없거나_형식이_틀린_토픽은_거부() {
        assertThatThrownBy(() -> publish("NO_SUCH_TOPIC", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> publish("test_echo", "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 업무가_롤백되면_발행도_없다() {
        assertThatThrownBy(() -> runner.run(comp, () -> {
            outbox.publish(EchoHandler.TOPIC, "R-1", Map.of());
            throw new IllegalStateException("업무 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(accounts.query(comp, "select count(*) from sys_outbox", Integer.class)).isZero();
    }

    @Test
    void 너무_큰_본문은_거부() {
        assertThatThrownBy(() -> runner.call(comp, () -> outbox.publish(EchoHandler.TOPIC, "big",
                Map.of("v", "x".repeat(OutboxService.MAX_PAYLOAD_BYTES))))).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 릴레이 ----------

    @Test
    void 보내면_SENT_이고_송신은_트랜잭션과_회사_범위_밖에서() {
        UUID id = publish(EchoHandler.TOPIC, "S-1");
        RelayResult r = relay.relay(comp);
        assertThat(r.sent()).isEqualTo(1);
        assertThat(row(id)).containsEntry("status", "SENT").containsEntry("attempts", 1);
        Call call = EchoHandler.CALLS.peek();
        assertThat(call.inTransaction()).isFalse();
        assertThat(call.scope()).isEmpty();
        assertThat(call.message().payload()).containsEntry("k", "S-1");
        assertThat(call.message().messageId()).isEqualTo(id);
        // 보낸 것은 다시 집지 않는다
        assertThat(relay.relay(comp).total()).isZero();
    }

    @Test
    void 송신_예외는_뒤로_미뤄_재시도하고_바로_다시_집지_않는다() {
        EchoHandler.behavior = m -> {
            throw new IllegalStateException("ERP 503");
        };
        UUID id = publish(EchoHandler.TOPIC, "E-1");
        assertThat(relay.relay(comp).retried()).isEqualTo(1);
        assertThat(row(id)).containsEntry("status", "NEW").containsEntry("attempts", 1).containsEntry("later", true);
        assertThat((String) row(id).get("last_error")).contains("ERP 503");
        assertThat(relay.relay(comp).total()).isZero();
        // 시간이 지나면(next_retry_at 도래) 다시 집어 성공
        EchoHandler.behavior = m -> SendResult.sent();
        accounts.exec(comp, "update sys_outbox set next_retry_at = now() where message_id = ?", id);
        assertThat(relay.relay(comp).sent()).isEqualTo(1);
        assertThat(row(id)).containsEntry("status", "SENT").containsEntry("attempts", 2);
    }

    @Test
    void 재시도_상한을_넘으면_보내지_않고_FAIL() {
        EchoHandler.behavior = m -> SendResult.retry("계속 실패");
        UUID id = publish(EchoHandler.TOPIC, "M-1");
        accounts.exec(comp, "update sys_outbox set max_attempts = 2 where message_id = ?", id);
        relay.relay(comp);
        accounts.exec(comp, "update sys_outbox set next_retry_at = now() where message_id = ?", id);
        relay.relay(comp);
        assertThat(row(id)).containsEntry("status", "FAIL").containsEntry("attempts", 2);
        assertThat(EchoHandler.CALLS).hasSize(2);
    }

    @Test
    void 상한에_이미_닿은_행은_집을_때_FAIL_로_끝내고_송신하지_않는다() {
        UUID id = publish(EchoHandler.TOPIC, "M-2");
        accounts.exec(comp, "update sys_outbox set attempts = 3, max_attempts = 3 where message_id = ?", id);
        relay.relay(comp);
        assertThat(row(id)).containsEntry("status", "FAIL");
        assertThat(EchoHandler.CALLS).isEmpty();
    }

    @Test
    void 알림_채널의_읽기_타임아웃은_재시도하지_않는다() {
        UUID id = publish(TimeoutHandler.TOPIC, "T-1");
        assertThat(relay.relay(comp).failed()).isEqualTo(1);
        assertThat(row(id)).containsEntry("status", "FAIL");
        assertThat((String) row(id).get("last_error")).contains("timeout");
        assertThat(TimeoutHandler.CALLS.get()).isEqualTo(1);
    }

    @Test
    void ERP처럼_타임아웃_재시도가_되는_채널은_재시도() {
        EchoHandler.behavior = m -> {
            throw new RuntimeException(new java.net.SocketTimeoutException("x"));
        };
        UUID id = publish(EchoHandler.TOPIC, "T-2");
        relay.relay(comp);
        assertThat(row(id)).containsEntry("status", "NEW");
    }

    @Test
    void 프로세스가_송신_중_죽어_남은_오래된_SENDING_은_다시_집는다() {
        UUID stale = publish(EchoHandler.TOPIC, "ST-1");
        UUID fresh = publish(EchoHandler.TOPIC, "ST-2");
        accounts.exec(comp, "update sys_outbox set status = 'SENDING', attempts = 1, locked_at = now() - interval '20 minutes' where message_id = ?", stale);
        accounts.exec(comp, "update sys_outbox set status = 'SENDING', attempts = 1, locked_at = now() - interval '1 minute' where message_id = ?", fresh);
        assertThat(relay.relay(comp).sent()).isEqualTo(1);
        assertThat(row(stale)).containsEntry("status", "SENT").containsEntry("attempts", 2);
        assertThat(row(fresh)).containsEntry("status", "SENDING");
    }

    @Test
    void 그사이_다시_집힌_행의_늦은_결과는_버린다() {
        UUID id = publish(EchoHandler.TOPIC, "L-1");
        EchoHandler.behavior = m -> {
            // 송신 중에 다른 인스턴스가 오래된 SENDING 으로 판단해 다시 집은 상황
            accounts.exec(comp, "update sys_outbox set locked_at = clock_timestamp() + interval '1 second' where message_id = ?", id);
            return SendResult.sent();
        };
        RelayResult r = relay.relay(comp);
        assertThat(r.stale()).isEqualTo(1);
        assertThat(row(id)).containsEntry("status", "SENDING");
    }

    @Test
    void 두_릴레이가_동시에_돌아도_한_메시지는_한_번만_보낸다() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            ids.add(publish(EchoHandler.TOPIC, "C-" + i));
        }
        Map<UUID, Integer> count = new ConcurrentHashMap<>();
        EchoHandler.behavior = m -> {
            count.merge(m.messageId(), 1, Integer::sum);
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return SendResult.sent();
        };
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<RelayResult>> fs = new ArrayList<>();
            for (int t = 0; t < 3; t++) {
                fs.add(pool.submit(() -> {
                    start.await();
                    return relay.relay(comp);
                }));
            }
            start.countDown();
            int sent = 0;
            for (Future<RelayResult> f : fs) {
                sent += f.get(30, TimeUnit.SECONDS).sent();
            }
            assertThat(sent).isEqualTo(30);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count).hasSize(30).allSatisfy((k, v) -> assertThat(v).isEqualTo(1));
        assertThat(Set.copyOf(count.keySet())).containsExactlyInAnyOrderElementsOf(ids);
    }

    @Test
    void 릴레이는_트랜잭션_안에서_부를_수_없다() {
        assertThatThrownBy(() -> runner.run(comp, () -> relay.relay(comp))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void A사_릴레이는_B사_메시지를_보내지_않는다() {
        String other = accounts.newComp("OB");
        UUID bMsg = runner.call(other, () -> outbox.publish(EchoHandler.TOPIC, "B-1", Map.of()));
        relay.relay(comp);
        assertThat(EchoHandler.CALLS).isEmpty();
        assertThat(runner.call(other, () -> jdbc.queryForObject(
                "select status from sys_outbox where message_id = ?", String.class, bMsg))).isEqualTo("NEW");
        assertThat(accounts.query(comp, "select count(*) from sys_outbox where message_id = ?", Integer.class, bMsg))
                .isZero();
    }

    @Test
    void 송신기가_빠진_토픽의_행은_FAIL() {
        accounts.exec(comp, """
                insert into sys_outbox (comp_cd, message_id, topic, payload, created_by)
                values (?, gen_random_uuid(), 'GONE_TOPIC', '{}'::jsonb, 't')""", comp);
        assertThat(relay.relay(comp).failed()).isEqualTo(1);
    }

    @Test
    void 재시도_간격은_30초부터_두배씩_최대_1시간() {
        assertThat(OutboxRelay.backoffSeconds(1)).isEqualTo(30);
        assertThat(OutboxRelay.backoffSeconds(2)).isEqualTo(60);
        assertThat(OutboxRelay.backoffSeconds(5)).isEqualTo(480);
        assertThat(OutboxRelay.backoffSeconds(50)).isEqualTo(3600);
        assertThat(OutboxRelay.backoffSeconds(0)).isEqualTo(30);
    }

    @Test
    void 앱_계정은_아웃박스를_지울_수_없다() {
        publish(EchoHandler.TOPIC, "D-1");
        assertThatThrownBy(() -> accounts.exec(comp, "delete from sys_outbox")).rootCause()
                .hasMessageContaining("permission denied");
    }

    // ---------- 인박스 ----------

    @Test
    void 같은_메시지는_한_번만_처리되고_롤백되면_다시_처리할_수_있다() {
        assertThat(runner.call(comp, () -> inbox.firstTime("ERP_ACK", "msg-00000001"))).isTrue();
        assertThat(runner.call(comp, () -> inbox.firstTime("ERP_ACK", "msg-00000001"))).isFalse();
        assertThatThrownBy(() -> runner.run(comp, () -> {
            assertThat(inbox.firstTime("ERP_ACK", "msg-00000002")).isTrue();
            throw new IllegalStateException("처리 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(runner.call(comp, () -> inbox.firstTime("ERP_ACK", "msg-00000002"))).isTrue();
    }

    @Test
    void 소비자와_회사가_다르면_같은_ID_도_따로() {
        String other = accounts.newComp("OB");
        assertThat(runner.call(comp, () -> inbox.firstTime("ERP_ACK", "same-id-1"))).isTrue();
        assertThat(runner.call(comp, () -> inbox.firstTime("LABEL", "same-id-1"))).isTrue();
        assertThat(runner.call(other, () -> inbox.firstTime("ERP_ACK", "same-id-1"))).isTrue();
    }

    @Test
    void 인박스_형식_오류와_트랜잭션_밖_호출은_거부() {
        assertThatThrownBy(() -> runner.call(comp, () -> inbox.firstTime("erp", "msg-00000001")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> runner.call(comp, () -> inbox.firstTime("ERP", "short")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> runner.call(comp, () -> inbox.firstTime("ERP", "a b c d e f g")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> inbox.firstTime("ERP", "msg-00000001"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void 같은_키가_동시에_와도_한쪽만_처음이다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> fs = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                fs.add(pool.submit(() -> {
                    start.await();
                    return runner.call(comp, () -> inbox.firstTime("ERP_ACK", "race-0000001"));
                }));
            }
            start.countDown();
            int first = 0;
            for (Future<Boolean> f : fs) {
                first += f.get(30, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertThat(first).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 앱_계정은_인박스_기록을_고치거나_지울_수_없다() {
        runner.call(comp, () -> inbox.firstTime("ERP_ACK", "msg-00000009"));
        assertThatThrownBy(() -> accounts.exec(comp, "delete from sys_inbox")).rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> accounts.exec(comp, "update sys_inbox set message_id = 'x'")).rootCause()
                .hasMessageContaining("permission denied");
    }
}
