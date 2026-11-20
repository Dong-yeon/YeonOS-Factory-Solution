package io.smarthappy.platform.log;

import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 활용 일 집계 (docs/11 D2·D3). 접속 로그 → 회사 시간대 날짜·이벤트·화면·기능·단말 구분별 (사용자 수, 건수).
 * 사용자 ID 는 남기지 않는다 — 운영자도 이 숫자만 본다(D3 사용자 결정). 접속 로그가 보존 정리돼도 추이는 남는다.
 * 처리한 날에는 표시 행(event_cd = '_DAY')을 남겨 사용이 없던 날을 다시 훑지 않는다.
 */
@Service
@RequiredArgsConstructor
public class UsageDailyService {

    static final String DAY_MARK = "_DAY";
    static final int SETTLE_MINUTES = 10;
    static final int MAX_DAYS_PER_RUN = 62;
    /** 집계 대상 이벤트 — 활용(사용) 신호만, 보안 이벤트는 접속 로그에서 본다 */
    static final List<String> EVENTS = List.of("LOGIN_OK", "PAGE_VIEW", "ACTION", "FILE_UPLOAD", "FILE_DOWNLOAD");
    static final int MAX_RANGE_DAYS = 366;

    private final JdbcTemplate jdbc;
    private final BizClock clock;

    public record DaySummary(LocalDate date, int users, int logins, int pageViews, int actions) {
    }

    /** 현재 회사의 확정 가능한 날들을 집계한다. 집계한 날 수 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int aggregatePending() {
        ZoneId zone = clock.zone();
        LocalDate lastDay = clock.now().minusMinutes(SETTLE_MINUTES).toLocalDate().minusDays(1);
        LocalDate last = jdbc.queryForObject("select max(usage_date) from sys_usage_daily", LocalDate.class);
        LocalDate from;
        if (last != null) {
            from = last.plusDays(1);
        } else {
            OffsetDateTime first = jdbc.queryForObject("select min(occurred_at) from sys_access_log", OffsetDateTime.class);
            from = first == null ? lastDay : first.atZoneSameInstant(zone).toLocalDate();
        }
        String comp = TenantContext.requireCompCd();
        int n = 0;
        for (LocalDate d = from; !d.isAfter(lastDay) && n < MAX_DAYS_PER_RUN; d = d.plusDays(1), n++) {
            OffsetDateTime start = d.atStartOfDay(zone).toOffsetDateTime();
            OffsetDateTime end = d.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
            jdbc.update("""
                    insert into sys_usage_daily (comp_cd, usage_date, event_cd, prog_cd, action_cd, client_type,
                                                 user_cnt, event_cnt)
                    select comp_cd, ?, event_cd, coalesce(prog_cd, ''), coalesce(action_cd, ''), client_type,
                           count(distinct user_id), count(*)
                      from sys_access_log
                     where occurred_at >= ? and occurred_at < ? and result_cd = 'OK'
                       and event_cd = any (?::varchar[])
                     group by comp_cd, event_cd, coalesce(prog_cd, ''), coalesce(action_cd, ''), client_type
                    on conflict do nothing""",
                    Date.valueOf(d), start, end, "{" + String.join(",", EVENTS) + "}");
            jdbc.update("""
                    insert into sys_usage_daily (comp_cd, usage_date, event_cd, client_type, user_cnt, event_cnt)
                    values (?, ?, ?, 'SYSTEM', 0, 0) on conflict do nothing""", comp, Date.valueOf(d), DAY_MARK);
        }
        return n;
    }

    /** 일별 요약 (회사 관리자 화면·운영자 콘솔 공용 — 범위는 호출하는 쪽 트랜잭션의 회사/운영자 범위가 정한다) */
    @Transactional(readOnly = true)
    public List<DaySummary> summary(String compCd, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to) || from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return jdbc.query("""
                select usage_date,
                       coalesce(max(user_cnt) filter (where event_cd = 'LOGIN_OK'), 0) as users,
                       coalesce(sum(event_cnt) filter (where event_cd = 'LOGIN_OK'), 0) as logins,
                       coalesce(sum(event_cnt) filter (where event_cd = 'PAGE_VIEW'), 0) as page_views,
                       coalesce(sum(event_cnt) filter (where event_cd = 'ACTION'), 0) as actions
                  from sys_usage_daily
                 where comp_cd = ? and usage_date between ? and ?
                 group by usage_date
                 order by usage_date""",
                (rs, i) -> new DaySummary(rs.getObject(1, LocalDate.class), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                        rs.getInt(5)),
                compCd, Date.valueOf(from), Date.valueOf(to));
    }

    /** 기간 동안 화면별 열람 수 상위 (어떤 화면을 쓰고 어떤 화면을 안 쓰는지) */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> topPrograms(String compCd, LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to) || from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return jdbc.queryForList("""
                select prog_cd as "progCd", sum(event_cnt) as "views", max(user_cnt) as "maxDailyUsers"
                  from sys_usage_daily
                 where comp_cd = ? and usage_date between ? and ? and event_cd = 'PAGE_VIEW' and prog_cd <> ''
                 group by prog_cd
                 order by sum(event_cnt) desc
                 limit 50""", compCd, Date.valueOf(from), Date.valueOf(to));
    }
}
