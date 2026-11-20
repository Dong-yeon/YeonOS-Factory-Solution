package io.smarthappy.platform.log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 로그 일 다이제스트 (docs/05 D6). 회사·로그 테이블·날짜(회사 시간대)별로 그날 행 전체의 SHA-256 을 구하고
 * 앞날 다이제스트와 이어 체인을 만든다. 과거 로그를 고치거나 지우면 다시 계산한 값이 저장값과 어긋난다.
 * 운영: 다이제스트를 불변 백업 저장소로도 반출해야 DB 관리자 권한 공격자에도 의미가 있다 (9단계).
 *
 * 하루가 끝나고 {@link #SETTLE_MINUTES}분 뒤부터 그날을 확정한다 (자정 무렵 커밋되는 로그를 기다림).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LogDigestService {

    /** 대상 테이블 → 정렬 키 (식별자는 이 상수에서만 온다 — 사용자 입력 없음) */
    static final Map<String, String> TABLES = Map.of("sys_access_log", "log_id", "sys_audit_log", "audit_id");
    static final String GENESIS = "0".repeat(64);
    static final int SETTLE_MINUTES = 10;
    /** 한 번에 확정하는 최대 일수 (밀린 경우 다음 실행이 이어서) */
    static final int MAX_DAYS_PER_RUN = 62;

    private final JdbcTemplate jdbc;
    private final BizClock clock;

    public record DayDigest(LocalDate day, long rowCnt, String digest, String prevDigest) {
    }

    /** 현재 회사의 확정 가능한 날짜들을 다이제스트한다. 새로 만든 개수 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int digestPending() {
        ZoneId zone = clock.zone();
        LocalDate lastDay = clock.now().minusMinutes(SETTLE_MINUTES).toLocalDate().minusDays(1);
        int created = 0;
        for (String table : TABLES.keySet().stream().sorted().toList()) {
            DayDigest last = latest(table);
            LocalDate from;
            if (last != null) {
                from = last.day().plusDays(1);
            } else {
                OffsetDateTime first = jdbc.queryForObject("select min(occurred_at) from " + table, OffsetDateTime.class);
                from = first == null ? lastDay : first.atZoneSameInstant(zone).toLocalDate();
            }
            String prev = last == null ? GENESIS : last.digest();
            int n = 0;
            for (LocalDate d = from; !d.isAfter(lastDay) && n < MAX_DAYS_PER_RUN; d = d.plusDays(1), n++) {
                DayDigest dd = compute(table, d, zone, prev);
                jdbc.update("""
                        insert into sys_log_digest (comp_cd, table_nm, log_date, row_cnt, digest, prev_digest)
                        values (?, ?, ?, ?, ?, ?)""", TenantContext.requireCompCd(), table, Date.valueOf(d),
                        dd.rowCnt(), dd.digest(), dd.prevDigest());
                prev = dd.digest();
                created++;
            }
        }
        return created;
    }

    /** 저장된 다이제스트를 처음부터 다시 계산해 어긋난 날짜를 돌려준다 (비어 있으면 무결) */
    @Transactional(readOnly = true)
    public List<String> verify() {
        ZoneId zone = clock.zone();
        List<String> broken = new ArrayList<>();
        for (String table : TABLES.keySet().stream().sorted().toList()) {
            String prev = GENESIS;
            for (DayDigest stored : stored(table)) {
                DayDigest again = compute(table, stored.day(), zone, prev);
                if (!again.digest().equals(stored.digest()) || !prev.equals(stored.prevDigest())) {
                    broken.add(table + ":" + stored.day());
                }
                prev = stored.digest();
            }
        }
        if (!broken.isEmpty()) {
            log.warn("로그 다이제스트 불일치 compCd={} {}", TenantContext.currentCompCd().orElse("-"), broken);
        }
        return broken;
    }

    DayDigest compute(String table, LocalDate day, ZoneId zone, String prev) {
        String orderKey = TABLES.get(table);
        if (orderKey == null) {
            throw new IllegalArgumentException("다이제스트 대상이 아닌 테이블");
        }
        OffsetDateTime start = day.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime end = day.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        // to_jsonb 의 timestamptz 표기는 세션 시간대를 따른다 → 계산·검증이 같은 값을 내도록 이 트랜잭션만 UTC 로 고정
        jdbc.queryForObject("select set_config('TimeZone', 'UTC', true)", String.class);
        Map<String, Object> r = jdbc.queryForMap("select count(*) as cnt, "
                + "encode(sha256(convert_to(coalesce(string_agg(to_jsonb(l)::text, E'\\n' order by l." + orderKey
                + "), ''), 'UTF8')), 'hex') as body from " + table + " l where l.occurred_at >= ? and l.occurred_at < ?",
                start, end);
        long cnt = ((Number) r.get("cnt")).longValue();
        String body = (String) r.get("body");
        String chained = sha256(prev + "|" + table + "|" + day + "|" + cnt + "|" + body);
        return new DayDigest(day, cnt, chained, prev);
    }

    private DayDigest latest(String table) {
        List<DayDigest> rows = jdbc.query("""
                select log_date, row_cnt, digest, prev_digest from sys_log_digest
                 where table_nm = ? order by log_date desc limit 1""",
                (rs, i) -> new DayDigest(rs.getDate(1).toLocalDate(), rs.getLong(2), rs.getString(3), rs.getString(4)),
                table);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<DayDigest> stored(String table) {
        return jdbc.query("""
                select log_date, row_cnt, digest, prev_digest from sys_log_digest
                 where table_nm = ? order by log_date""",
                (rs, i) -> new DayDigest(rs.getDate(1).toLocalDate(), rs.getLong(2), rs.getString(3), rs.getString(4)),
                table);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
