package io.smarthappy.platform.messaging;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.time.BizClock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 수신 원문 저널 (docs/65 5.2·16.2) — 받는 쪽 절반. **원문을 먼저 저장**하고 호출한 쪽은 바로 200 을 돌려준다(처리는 {@link InboundProcessor}).
 *
 * - 업무 키 = 경로 + {@link InboundRoute#keyFields()} 값 → SHA-256. (회사, 경로, 키) 에 원래 행 하나
 * - 같은 키·같은 내용 = DUP(원래 행 dup_count + 1, 새 행 없음 — 297번 와도 행 1개)
 * - 같은 키·다른 내용 = CONFLICT(증거 행, 처리 안 함) — 호출한 쪽이 경보를 남긴다
 * - 형식이 틀린 건 = {@link Invalid}(저장 안 함) — 호출한 쪽이 그 건만 INVALID 로 돌려준다
 * - 같은 키가 동시에 오면 둘째는 첫째 커밋까지 기다렸다가 DUP·CONFLICT 로 판정된다(부분 유일 인덱스)
 */
@Service
public class InboundJournal {

    static final Pattern ROUTE = Pattern.compile("^[A-Z][A-Z0-9_]{2,29}$");
    static final int MAX_BODY_BYTES = 16 * 1024;
    static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final PolicyService policies;
    private final BizClock clock;
    private final Map<String, InboundRoute> routes;

    public InboundJournal(JdbcTemplate jdbc, PolicyService policies, BizClock clock, ObjectProvider<InboundRoute> routes) {
        this.jdbc = jdbc;
        this.policies = policies;
        this.clock = clock;
        this.routes = routes.orderedStream().peek(r -> {
            if (!ROUTE.matcher(r.routeCd()).matches() || r.keyFields().isEmpty()) {
                throw new IllegalStateException("수신 경로 정의가 틀림: " + r.routeCd());
            }
        }).collect(Collectors.toUnmodifiableMap(InboundRoute::routeCd, r -> r));
    }

    /** 저장 결과 — outcome = STORED / DUP / CONFLICT, conflictOf = 충돌이면 원래 행 */
    public record Stored(String outcome, long inboundId, Long conflictOf) {
    }

    /** 형식 오류(그 건만) — code 를 응답에 담는다 */
    public static final class Invalid extends RuntimeException {
        private final String code;

        public Invalid(String code) {
            super(code, null, false, false);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public Optional<InboundRoute> route(String routeCd) {
        return Optional.ofNullable(routeCd == null ? null : routes.get(routeCd));
    }

    public Collection<String> routeCodes() {
        return routes.keySet();
    }

    /**
     * 원문 한 건 저장(업무 트랜잭션 MANDATORY — 호출한 쪽이 건마다 또는 묶음으로 연다).
     *
     * @param sourceId 보낸 장치 id(없으면 null)
     * @param sourceCd 보낸 쪽 표기(행위자)
     * @param sentAt   보낸 쪽 봉투 시각(장치 시계 — 차이만 본다), 모르면 null
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = Invalid.class)   // 형식 오류 한 건이 묶음 전체를 되돌리지 않게
    public Stored store(String routeCd, Long sourceId, String sourceCd, JsonNode body, Instant sentAt) {
        InboundRoute route = route(routeCd).orElseThrow(() -> new Invalid("ROUTE"));
        if (body == null || !body.isObject()) {
            throw new Invalid("FORMAT");
        }
        String missing = InboundKeys.firstMissing(body, route.keyFields());
        if (missing != null) {
            throw new Invalid("KEY:" + missing);
        }
        String raw = JSON.writeValueAsString(body);
        if (raw.length() > MAX_BODY_BYTES) {
            throw new Invalid("TOO_LARGE");
        }
        Instant occurredAt = route.occurredField() == null ? null : instant(body.get(route.occurredField()));
        Long orderNo = route.orderField() == null ? null : orderNo(body.get(route.orderField()));
        String partKey = route.partField() == null ? null : InboundKeys.canonical(body.get(route.partField()));
        if (partKey != null && (partKey.isEmpty() || partKey.length() > 100)) {
            partKey = partKey.isEmpty() ? null : partKey.substring(0, 100);
        }
        String bizKey = InboundKeys.join(body, route.keyFields());
        String keyHash = InboundKeys.sha256Hex(routeCd + "|" + bizKey);
        String bodyHash = InboundKeys.sha256Hex(InboundKeys.join(body, route.contentFields()));
        Instant now = clock.instant();
        Long skew = sentAt == null ? null : Duration.between(sentAt, now).toMillis();
        String comp = TenantContext.requireCompCd();
        String shownKey = bizKey.length() > 300 ? bizKey.substring(0, 300) : bizKey;

        List<Long> inserted = jdbc.queryForList("""
                insert into sys_inbound_msg (comp_cd, route_cd, source_id, source_cd, biz_key, key_hash, body_hash, body, part_key, order_no,
                                             occurred_at, sent_at, skew_ms)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                on conflict (comp_cd, route_cd, key_hash) where conflict_of_id is null do nothing
                returning inbound_id""", Long.class, comp, routeCd, sourceId, cut(sourceCd, 50), shownKey, keyHash, bodyHash, raw, partKey,
                orderNo, ts(occurredAt), ts(sentAt), skew);
        if (!inserted.isEmpty()) {
            return new Stored("STORED", inserted.get(0), null);
        }
        // 같은 업무 키가 이미 있다 — 원래 행을 잠그고 내용 비교(동시 중복 수 세기도 줄 세운다)
        Map<String, Object> orig = jdbc.queryForMap("""
                select inbound_id, body_hash from sys_inbound_msg
                 where route_cd = ? and key_hash = ? and conflict_of_id is null
                 for update""", routeCd, keyHash);
        long origId = ((Number) orig.get("inbound_id")).longValue();
        if (bodyHash.equals(orig.get("body_hash"))) {
            jdbc.update("update sys_inbound_msg set dup_count = dup_count + 1, last_dup_at = now() where inbound_id = ?", origId);
            return new Stored("DUP", origId, null);
        }
        Long id = jdbc.queryForObject("""
                insert into sys_inbound_msg (comp_cd, route_cd, source_id, source_cd, biz_key, key_hash, body_hash, body, part_key, order_no,
                                             occurred_at, sent_at, skew_ms, status, conflict_of_id)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, 'CONFLICT', ?)
                returning inbound_id""", Long.class, comp, routeCd, sourceId, cut(sourceCd, 50), shownKey, keyHash, bodyHash, raw, partKey,
                orderNo, ts(occurredAt), ts(sentAt), skew, origId);
        return new Stored("CONFLICT", id, origId);
    }

    /**
     * 과부하인가 — 그 회사의 처리 안 된 원문이 정책 상한 이상이면 true(이때만 429 + Retry-After). 회사 범위 안에서(트랜잭션)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean overloaded() {
        int max = policies.intValue("EDGE_INBOUND_MAX_BACKLOG");
        Integer n = jdbc.queryForObject("select count(*) from (select 1 from sys_inbound_msg where status = 'NEW' limit ?) x",
                Integer.class, max);
        return n != null && n >= max;
    }

    public int retryAfterSec() {
        return policies.intValue("EDGE_INBOUND_RETRY_AFTER_SEC");
    }

    /** 원문 목록(화면) — 경로·상태로 거르고 최근 순, 상한 limit */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Collection<String> routeCds, String status, int limit) {
        if (routeCds.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList("""
                select inbound_id, route_cd, source_cd, biz_key, status, result_cd, attempts, dup_count, last_error,
                       occurred_at, received_at, processed_at, skew_ms, conflict_of_id, body::text as body
                  from sys_inbound_msg
                 where route_cd = any (?) and (?::varchar is null or status = ?)
                 order by inbound_id desc
                 limit ?""", routeCds.toArray(String[]::new), status, status, Math.max(1, Math.min(limit, 500)));
    }

    /** 격리된 원문을 다시 처리 대기로(시도 수 초기화). @return 바뀐 행 수(격리 상태가 아니면 0) */
    @Transactional(propagation = Propagation.MANDATORY)
    public int requeue(long inboundId, Collection<String> routeCds) {
        return jdbc.update("""
                update sys_inbound_msg
                   set status = 'NEW', attempts = 0, next_try_at = now(), processed_at = null, result_cd = null
                 where inbound_id = ? and status = 'QUARANTINE' and route_cd = any (?)""", inboundId, routeCds.toArray(String[]::new));
    }

    private static Instant instant(JsonNode v) {
        String s = InboundKeys.canonical(v);
        if (s.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            throw new Invalid("OCCURRED_AT");
        }
    }

    private static Long orderNo(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        if (!v.isIntegralNumber() || !v.canConvertToLong() || v.asLong() < 0) {
            throw new Invalid("ORDER");
        }
        return v.asLong();
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static String cut(String s, int max) {
        return s == null ? "?" : s.length() > max ? s.substring(0, max) : s;
    }
}
