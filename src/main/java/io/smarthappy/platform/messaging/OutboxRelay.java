package io.smarthappy.platform.messaging;

import java.net.SocketTimeoutException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClientResponseException;

import io.smarthappy.platform.messaging.OutboxHandler.Outcome;
import io.smarthappy.platform.messaging.OutboxHandler.SendResult;
import io.smarthappy.platform.tenant.TenantRunner;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;

/**
 * 아웃박스 릴레이 — 3단 (01 5.8, docs/04 2.5):
 * ① 짧은 트랜잭션에서 `for update skip locked` 로 집어 SENDING·locked_at·attempts+1 로 바꾸고 커밋
 * ② **트랜잭션 밖에서** 송신 (외부 호출을 DB 트랜잭션 안에서 기다리면 커넥션 풀이 고갈된다)
 * ③ 짧은 트랜잭션에서 `status = 'SENDING' and locked_at = 집은 값` 일 때만 결과 기록 (그사이 다시 집혔으면 버림)
 *
 * 오래된 SENDING(프로세스가 ②에서 죽음)은 {@link #STALE_MINUTES}분 뒤 다시 집는다. 재시도 상한은 집을 때 검사.
 * 여러 인스턴스가 동시에 돌아도 skip locked 로 같은 행을 두 번 집지 않는다.
 */
@Slf4j
@Component
public class OutboxRelay {

    static final int STALE_MINUTES = 15;
    static final int BATCH = 50;
    static final long BASE_BACKOFF_SECONDS = 30;
    static final long MAX_BACKOFF_SECONDS = 3600;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final TenantRunner runner;
    private final JdbcTemplate jdbc;
    private final Map<String, OutboxHandler> handlers;

    public OutboxRelay(TenantRunner runner, JdbcTemplate jdbc, List<OutboxHandler> handlers) {
        this.runner = runner;
        this.jdbc = jdbc;
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(OutboxHandler::topic, Function.identity()));
    }

    record Claimed(OutboxMessage message, OffsetDateTime lockedAt) {
    }

    public record RelayResult(int sent, int retried, int failed, int stale) {
        public int total() {
            return sent + retried + failed;
        }
    }

    /** 사용 중인 모든 회사. 한 회사 실패가 다른 회사를 막지 않는다 */
    public void relayAll() {
        for (String compCd : runner.activeCompCodes()) {
            try {
                RelayResult r = relay(compCd);
                if (r.total() > 0) {
                    log.info("아웃박스 compCd={} sent={} retry={} fail={}", compCd, r.sent(), r.retried(), r.failed());
                }
            } catch (RuntimeException e) {
                log.error("아웃박스 릴레이 실패 compCd={}", compCd, e);
            }
        }
    }

    /** 한 회사의 대기 메시지를 한 묶음 처리한다 */
    public RelayResult relay(String compCd) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("아웃박스 릴레이는 트랜잭션 밖에서만 (송신을 트랜잭션 안에서 기다리지 않는다)");
        }
        List<Claimed> claimed = runner.call(compCd, () -> claim(BATCH));
        int sent = 0;
        int retried = 0;
        int failed = 0;
        int stale = 0;
        for (Claimed c : claimed) {
            SendResult result = send(c.message());
            boolean recorded = Boolean.TRUE.equals(runner.call(compCd, () -> record(c, result)));
            if (!recorded) {
                stale++;
                log.warn("아웃박스 결과 버림(그사이 다시 집힘) outboxId={}", c.message().outboxId());
                continue;
            }
            switch (result.outcome()) {
                case SENT -> sent++;
                case RETRY -> retried++;
                case FAIL -> failed++;
            }
        }
        return new RelayResult(sent, retried, failed, stale);
    }

    /** ① 집기 — 상한을 넘은 행은 송신하지 않고 FAIL 로. 돌려주는 순서 = outbox_id 오름차순(보내는 순서) */
    List<Claimed> claim(int limit) {
        return jdbc.query("""
                with picked as (
                    select outbox_id from sys_outbox
                     where (status = 'NEW' and next_retry_at <= now())
                        or (status = 'SENDING' and locked_at < now() - make_interval(mins => ?))
                     order by outbox_id
                     for update skip locked
                     limit ?)
                update sys_outbox o
                   set status     = case when o.attempts >= o.max_attempts then 'FAIL' else 'SENDING' end,
                       locked_at  = case when o.attempts >= o.max_attempts then null else clock_timestamp() end,
                       attempts   = case when o.attempts >= o.max_attempts then o.attempts else o.attempts + 1 end,
                       last_error = case when o.attempts >= o.max_attempts then 'max attempts' else o.last_error end
                  from picked
                 where o.outbox_id = picked.outbox_id
                returning o.outbox_id, o.comp_cd, o.message_id, o.topic, o.biz_key, o.payload::text as payload,
                          o.attempts, o.status, o.locked_at""",
                (rs, i) -> "SENDING".equals(rs.getString("status"))
                        ? new Claimed(new OutboxMessage(rs.getLong("outbox_id"), rs.getString("comp_cd"),
                                rs.getObject("message_id", UUID.class), rs.getString("topic"), rs.getString("biz_key"),
                                OutboxService.JSON.readValue(rs.getString("payload"), MAP), rs.getInt("attempts")),
                                rs.getObject("locked_at", OffsetDateTime.class))
                        : null,
                STALE_MINUTES, limit).stream().filter(c -> c != null)
                // UPDATE … RETURNING 은 CTE 의 order by 를 지키지 않는다(실행계획 따라 달라짐) — 보내는 순서는 만든 순서(outbox_id)로
                .sorted(java.util.Comparator.comparingLong(c -> c.message().outboxId())).toList();
    }

    /** ② 송신 — 트랜잭션 밖 */
    SendResult send(OutboxMessage m) {
        OutboxHandler handler = handlers.get(m.topic());
        if (handler == null) {
            return SendResult.fail("송신기 없음: " + m.topic());
        }
        try {
            SendResult r = handler.send(m);
            return r == null ? SendResult.retry("송신기가 결과를 돌려주지 않음") : r;
        } catch (SocketTimeoutException e) {
            return handler.retryOnReadTimeout() ? SendResult.retry("timeout: " + e.getMessage())
                    : SendResult.fail("timeout(재시도 안 함 — 중복 위험): " + e.getMessage());
        } catch (Exception e) {
            RestClientResponseException http = httpResponse(e);
            if (http != null) {
                // 응답 본문은 저장·기록하지 않는다(docs/36 19장 O13) — 이 예외의 메시지에는 응답 본문이 들어 있다
                log.warn("아웃박스 송신 오류 outboxId={} topic={} type={} HTTP {}", m.outboxId(), m.topic(), e.getClass().getName(),
                        http.getStatusCode().value());
                return SendResult.retry(e.getClass().getSimpleName() + ": HTTP " + http.getStatusCode().value());
            }
            log.warn("아웃박스 송신 오류 outboxId={} topic={}", m.outboxId(), m.topic(), e);
            return SendResult.retry(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 원인 사슬 안의 HTTP 응답 예외(RestClient·RestTemplate) — 감싼 예외의 메시지도 원인의 메시지(응답 본문)를 담는다 */
    static RestClientResponseException httpResponse(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof RestClientResponseException r) {
                return r;
            }
        }
        return null;
    }

    /** ③ 결과 기록 — 집은 그 상태일 때만 */
    boolean record(Claimed c, SendResult r) {
        long id = c.message().outboxId();
        String error = r.error() == null ? null : r.error().length() > 500 ? r.error().substring(0, 500) : r.error();
        int n = switch (r.outcome()) {
            case SENT -> jdbc.update("""
                    update sys_outbox set status = 'SENT', sent_at = now(), locked_at = null, last_error = null
                     where outbox_id = ? and status = 'SENDING' and locked_at = ?""", id, c.lockedAt());
            case RETRY -> jdbc.update("""
                    update sys_outbox
                       set status = case when attempts >= max_attempts then 'FAIL' else 'NEW' end,
                           next_retry_at = now() + make_interval(secs => ?), locked_at = null, last_error = ?
                     where outbox_id = ? and status = 'SENDING' and locked_at = ?""",
                    backoffSeconds(c.message().attempts()), error, id, c.lockedAt());
            case FAIL -> jdbc.update("""
                    update sys_outbox set status = 'FAIL', locked_at = null, last_error = ?
                     where outbox_id = ? and status = 'SENDING' and locked_at = ?""", error, id, c.lockedAt());
        };
        if (n > 0 && r.outcome() == Outcome.FAIL) {
            log.error("아웃박스 최종 실패 outboxId={} topic={} error={}", id, c.message().topic(), error);
        }
        return n > 0;
    }

    /** 30초, 1분, 2분 … 최대 1시간 */
    static long backoffSeconds(int attempts) {
        int shift = Math.max(0, Math.min(attempts - 1, 20));
        return Math.min(BASE_BACKOFF_SECONDS << shift, MAX_BACKOFF_SECONDS);
    }
}
