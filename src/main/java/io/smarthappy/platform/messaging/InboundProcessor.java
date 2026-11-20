package io.smarthappy.platform.messaging;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * 수신 원문 처리기 (docs/65 16.2) — 처리하는 쪽 절반. 트랜잭션 밖에서 불린다(정기 작업·시험).
 *
 * - 원문 한 건 = 처리 트랜잭션 한 개(행위자 = 보낸 쪽). 한 건이 틀려도 나머지는 계속
 * - 고르는 순서 = 줄 세움 단위(part_key) → 순번(order_no) → 받은 순서 — 같은 묶음 안에서 순서가 뒤바뀌어 온 것을 바로잡는다
 * - 행은 `for update skip locked` 로 잡는다 — 처리기가 여럿 돌아도 같은 행을 두 번 처리하지 않는다
 * - 경로가 결과를 돌려주면 그 상태(DONE·STALE·REJECTED), {@link InboundRoute.Reject} 면 되돌리고 REJECTED,
 *   그 밖의 예외는 시도 수 + 1 → 다시(5초·10초·20초 … 최대 5분 뒤) → 정책 EDGE_INBOUND_MAX_ATTEMPTS 이면 격리(QUARANTINE)
 */
@Slf4j
@Component
public class InboundProcessor {

    static final int BATCH = 200;
    static final long BASE_BACKOFF_SECONDS = 5;
    static final long MAX_BACKOFF_SECONDS = 300;

    private final TenantRunner runner;
    private final JdbcTemplate jdbc;
    private final InboundJournal journal;
    private final InboxService inbox;
    private final PolicyService policies;

    public InboundProcessor(TenantRunner runner, JdbcTemplate jdbc, InboundJournal journal, InboxService inbox, PolicyService policies) {
        this.runner = runner;
        this.jdbc = jdbc;
        this.journal = journal;
        this.inbox = inbox;
        this.policies = policies;
    }

    public record DrainResult(int done, int stale, int rejected, int retried, int quarantined) {
        public int total() {
            return done + stale + rejected + retried + quarantined;
        }
    }

    record Pending(long id, String sourceCd) {
    }

    /** 사용 중인 모든 회사. 한 회사 실패가 다른 회사를 막지 않는다 */
    public void drainAll() {
        for (String compCd : runner.activeCompCodes()) {
            try {
                DrainResult r = drain(compCd);
                if (r.total() > 0) {
                    log.debug("수신 처리 compCd={} {}", compCd, r);
                }
            } catch (RuntimeException e) {
                log.error("수신 처리 실패 compCd={}", compCd, e);
            }
        }
    }

    /** 한 회사의 처리 대기 원문을 한 묶음 처리한다 */
    public DrainResult drain(String compCd) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("수신 처리는 트랜잭션 밖에서만");
        }
        List<Pending> pending = runner.call(compCd, () -> jdbc.query("""
                select inbound_id, source_cd from sys_inbound_msg
                 where status = 'NEW' and next_try_at <= now()
                 order by part_key nulls first, order_no nulls last, inbound_id
                 limit ?""", (rs, i) -> new Pending(rs.getLong(1), rs.getString(2)), BATCH));
        int done = 0;
        int stale = 0;
        int rejected = 0;
        int retried = 0;
        int quarantined = 0;
        for (Pending p : pending) {
            String outcome;
            try {
                outcome = runner.callAs(compCd, p.sourceCd(), () -> processOne(p.id()));
            } catch (InboundRoute.Reject r) {
                outcome = runner.callAs(compCd, p.sourceCd(), () -> finish(p.id(), "REJECTED", r.code()));
            } catch (RuntimeException e) {
                log.warn("수신 처리 오류 compCd={} inboundId={}", compCd, p.id(), e);
                outcome = runner.callAs(compCd, p.sourceCd(), () -> fail(p.id(), e));
            }
            switch (outcome) {
                case "DONE" -> done++;
                case "STALE" -> stale++;
                case "REJECTED" -> rejected++;
                case "RETRY" -> retried++;
                case "QUARANTINE" -> quarantined++;
                default -> {
                }
            }
        }
        return new DrainResult(done, stale, rejected, retried, quarantined);
    }

    /** 한 건 — 이미 다른 처리기가 잡았거나 끝났으면 SKIP */
    String processOne(long id) {
        List<InboundMessage> rows = jdbc.query("""
                select inbound_id, route_cd, source_id, source_cd, biz_key, key_hash, body::text as body, order_no, occurred_at,
                       received_at, skew_ms
                  from sys_inbound_msg
                 where inbound_id = ? and status = 'NEW'
                 for update skip locked""", (rs, i) -> new InboundMessage(rs.getLong("inbound_id"), rs.getString("route_cd"),
                (Long) rs.getObject("source_id", Long.class), rs.getString("source_cd"), rs.getString("biz_key"), rs.getString("key_hash"),
                parse(rs.getString("body")), (Long) rs.getObject("order_no", Long.class), instant(rs.getObject("occurred_at", OffsetDateTime.class)),
                instant(rs.getObject("received_at", OffsetDateTime.class)), (Long) rs.getObject("skew_ms", Long.class)), id);
        if (rows.isEmpty()) {
            return "SKIP";
        }
        InboundMessage m = rows.get(0);
        InboundRoute route = journal.route(m.routeCd()).orElse(null);
        if (route == null) {
            return finish(id, "REJECTED", "NO_ROUTE");
        }
        // 같은 업무 키는 한 번만(저널 유일 인덱스 + sys_inbox 두 겹 — 다시 넣기·처리기 겹침에도)
        if (!inbox.firstTime("IN:" + m.routeCd(), m.keyHash())) {
            return finish(id, "DONE", "ALREADY");
        }
        InboundResult r = route.handle(m);
        if (r == null || !List.of("DONE", "STALE", "REJECTED").contains(r.status())) {
            throw new IllegalStateException("수신 경로 결과가 틀림: " + m.routeCd());
        }
        return finish(id, r.status(), r.code());
    }

    String finish(long id, String status, String code) {
        jdbc.update("""
                update sys_inbound_msg set status = ?, result_cd = ?, processed_at = now(), attempts = attempts + 1, last_error = null
                 where inbound_id = ? and status = 'NEW'""", status, cut(code, 50), id);
        return status;
    }

    String fail(long id, RuntimeException e) {
        int max = policies.intValue("EDGE_INBOUND_MAX_ATTEMPTS");
        String err = cut(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()), 500);
        Integer attempts = jdbc.queryForObject("select attempts from sys_inbound_msg where inbound_id = ? for update", Integer.class, id);
        int next = (attempts == null ? 0 : attempts) + 1;
        if (next >= max) {
            jdbc.update("""
                    update sys_inbound_msg set status = 'QUARANTINE', attempts = ?, last_error = ?, processed_at = now(), result_cd = 'ERROR'
                     where inbound_id = ? and status = 'NEW'""", next, err, id);
            log.error("수신 원문 격리 inboundId={} error={}", id, err);
            return "QUARANTINE";
        }
        jdbc.update("""
                update sys_inbound_msg set attempts = ?, last_error = ?, next_try_at = now() + make_interval(secs => ?)
                 where inbound_id = ? and status = 'NEW'""", next, err, backoffSeconds(next), id);
        return "RETRY";
    }

    /** 5초, 10초, 20초 … 최대 5분 */
    static long backoffSeconds(int attempts) {
        int shift = Math.max(0, Math.min(attempts - 1, 20));
        return Math.min(BASE_BACKOFF_SECONDS << shift, MAX_BACKOFF_SECONDS);
    }

    private static JsonNode parse(String s) {
        return InboundJournal.JSON.readTree(s);
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
