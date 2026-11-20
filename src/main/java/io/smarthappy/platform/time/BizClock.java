package io.smarthappy.platform.time;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.tenant.TenantContext;

/**
 * 업무 시계: "오늘"은 서버 시간대가 아니라 **회사 시간대**(sys_comp.time_zone) 기준이다 (docs/04 2.9).
 * 날짜가 필요한 코드는 LocalDate.now() 대신 이것을 쓴다 (ZonelessNowGuardTest 가 막는다).
 * 공장 시간대: zone(plantCd) = sys_plant.time_zone, 없거나 잘못된 값이면 회사 시간대 (docs/25 D5).
 * 근무조 경계 작업일은 module 쪽 WorkDayResolver 가 이 위에 얹는다.
 */
@Component
public class BizClock {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;
    private Clock clock = Clock.systemUTC();

    public BizClock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 테스트용 시계 교체 */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    /** 현재 회사의 시간대. 회사 범위 안(트랜잭션)에서만 — 범위가 없으면 TenantNotSetException */
    @Transactional(readOnly = true)
    public ZoneId zone() {
        String compCd = TenantContext.requireCompCd();
        List<String> tz = jdbc.queryForList("select time_zone from sys_comp where comp_cd = ?", String.class, compCd);
        if (tz.isEmpty() || tz.get(0) == null) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(tz.get(0));
        } catch (DateTimeException e) {
            return DEFAULT_ZONE;
        }
    }

    /** 공장 시간대 — sys_plant.time_zone, 없거나(공장 없음 포함) 잘못된 값이면 회사 시간대 */
    @Transactional(readOnly = true)
    public ZoneId zone(String plantCd) {
        if (plantCd == null) {
            return zone();
        }
        TenantContext.requireCompCd();
        List<String> tz = jdbc.queryForList("select time_zone from sys_plant where plant_cd = ?", String.class, plantCd);
        if (tz.isEmpty() || tz.get(0) == null) {
            return zone();
        }
        try {
            return ZoneId.of(tz.get(0));
        } catch (DateTimeException e) {
            return zone();
        }
    }

    /**
     * 현지 시각 → 시각. DST 로 **없는 시각(봄)은 null**(호출한 쪽이 DST_GAP 칸 오류로), **겹치는 시각(가을)은 앞쪽**(서머타임 쪽 —
     * java.time 기본과 같고 docs/25 4.2 에 적어 둠). 조용히 1시간 옮기지 않는다
     */
    public static Instant toInstant(LocalDateTime local, ZoneId zone) {
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (offsets.isEmpty()) {
            return null;
        }
        return local.toInstant(offsets.get(0));
    }

    /** 지금 시각(시간대 없음) — 원장 발생 시각 비교용 */
    public Instant instant() {
        return clock.instant();
    }

    @Transactional(readOnly = true)
    public ZonedDateTime now() {
        return ZonedDateTime.now(clock.withZone(zone()));
    }

    @Transactional(readOnly = true)
    public LocalDate today() {
        return now().toLocalDate();
    }
}
