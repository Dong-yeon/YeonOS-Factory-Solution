package io.smarthappy.module.demo;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 모듈 데모 시더 공용 도우미 (SRM·PLM — PmsDemoFlow 와 같은 방식을 나눠 씀).
 * <ul>
 * <li>실행자 = 회사 관리자(admin_yn 그룹 구성원) — 권한 판정이 화면과 같게</li>
 * <li>결재자 = 로그인할 수 없는 "데모 결재자" 사용자 — 결재는 요청자 본인이 못 하므로(정책 APR_SELF_APPROVE_ALLOWED 기본 N) 결재를 실제
 *     ApprovalService 로 끝내려고 둘째 사용자가 필요하다. 로그인 불가 = 비밀번호가 아무도 모르는 무작위 값의
 *     해시(값은 저장·기록하지 않음). 수동 잠금은 쓰지 않는다 — 잠긴 사용자는 결재선에 넣을 수 없다(ApprovalService)</li>
 * <li>지난 날짜 = 이 시더 스레드에만 지정 시각을 주는 시계를 BizClock 에 끼운다 — 서비스가 "오늘"(발주일·회신일 검사·채번 연월)을 BizClock 으로
 *     정하므로. 다른 스레드(요청·릴레이)는 실제 시각. DB 의 now()(감사 칸·발행 시각 등)는 바꾸지 않는다 — 그 칸들은 실제 넣은 시각</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class DemoSeedSupport {

    public static final String APPROVER_LOGIN = "demo-approver";
    private final TenantRunner runner;
    private final JdbcTemplate jdbc;
    private final BizClock clock;
    private final PasswordEncoder encoder;

    /** 회사 관리자 하나(사용 중, 사용자 id 가 가장 작은) — 없으면 빈 값. 트랜잭션 밖에서 부른다 */
    public Optional<LoginUser> admin(String compCd) {
        List<LoginUser> admins = runner.call(compCd, () -> jdbc.query("""
                select u.user_id, u.login_id, u.user_nm, u.locale_cd from sys_user u
                 where u.use_yn = 'Y' and u.user_type = 'INTERNAL'
                   and exists (select 1 from sys_user_auth a join sys_auth_group g
                                  on g.comp_cd = a.comp_cd and g.auth_grp_cd = a.auth_grp_cd
                               where a.user_id = u.user_id and g.admin_yn = 'Y' and g.use_yn = 'Y')
                 order by u.user_id limit 1""",
                (rs, i) -> new LoginUser(rs.getLong("user_id"), compCd, rs.getString("login_id"), rs.getString("user_nm"),
                        rs.getString("locale_cd"), false)));
        return admins.stream().findFirst();
    }

    /** 데모 결재자(로그인 불가) — 없으면 만든다. 회사 범위·트랜잭션 안에서 부른다(시드와 같이 되돌아가게) */
    public LoginUser approver(String compCd, String by) {
        jdbc.update("""
                insert into sys_user (comp_cd, login_id, user_nm, pwd_hash, created_by, updated_by)
                select hb_current_comp(), ?, '데모 결재자(로그인 불가)', ?, ?, ?
                 where not exists (select 1 from sys_user where lower(login_id) = lower(?))""",
                APPROVER_LOGIN, encoder.encode(UUID.randomUUID().toString()), by, by, APPROVER_LOGIN);
        return jdbc.queryForObject("""
                select user_id, login_id, user_nm, locale_cd from sys_user where lower(login_id) = lower(?)""",
                (rs, i) -> new LoginUser(rs.getLong("user_id"), compCd, rs.getString("login_id"), rs.getString("user_nm"),
                        rs.getString("locale_cd"), false), APPROVER_LOGIN);
    }

    /** user 이름으로 work — 서비스의 me() 가 이 사용자가 된다 */
    public static <T> T as(LoginUser user, Supplier<T> work) {
        SecurityContext before = SecurityContextHolder.getContext();
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(ctx);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.setContext(before);
        }
    }

    public static void as(LoginUser user, Runnable work) {
        as(user, () -> {
            work.run();
            return null;
        });
    }

    /** 시드 시계를 끼우고 work, 끝나면(실패해도) 실제 시계로 되돌린다 */
    public <T> T withClock(java.util.function.Function<SeedClock, T> work) {
        SeedClock c = new SeedClock(Thread.currentThread(), ZoneId.of("UTC"), new Instant[1]);
        clock.setClock(c);
        try {
            return work.apply(c);
        } finally {
            clock.setClock(Clock.systemUTC());
        }
    }

    /** 이 시더 스레드에서만 at 을 돌려주는 시계(비어 있으면 실제 시각). 다른 스레드는 늘 실제 시각 */
    public static final class SeedClock extends Clock {

        private static final Clock REAL = Clock.systemUTC();
        private final Thread owner;
        private final ZoneId zone;
        private final Instant[] at;

        SeedClock(Thread owner, ZoneId zone, Instant[] at) {
            this.owner = owner;
            this.zone = zone;
            this.at = at;
        }

        /** 회사 현지 날짜·시각으로 맞춘다 */
        public void set(LocalDate day, LocalTime time, ZoneId local) {
            at[0] = BizClock.toInstant(day.atTime(time), local);
        }

        /** 실제 시각으로 */
        public void reset() {
            at[0] = null;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return new SeedClock(owner, z, at);
        }

        @Override
        public Instant instant() {
            return Thread.currentThread() == owner && at[0] != null ? at[0] : REAL.instant();
        }
    }
}
