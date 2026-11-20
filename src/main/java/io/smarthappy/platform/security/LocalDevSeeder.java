package io.smarthappy.platform.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import io.smarthappy.platform.tenant.TenantRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 로컬 개발 전용(local 프로필): 개발 회사와 관리자 계정이 없으면 만든다.
 * 비밀번호는 .env 의 DEV_ADMIN_PASSWORD (없으면 만들지 않음). 운영·테스트에는 실행되지 않는다.
 * 실제 회사·초기 관리자 생성은 운영자 콘솔(P0 9단계)이 담당한다.
 */
@Slf4j
@Component
@Profile("local")
@Order(0)   // 회사·계정이 먼저 — 데모 데이터(DemoDataSeeder)는 그 뒤
@RequiredArgsConstructor
public class LocalDevSeeder implements ApplicationRunner {

    private final TenantRunner runner;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    @Value("${happyboot.dev.comp-cd:DEMO}")
    private String compCd;
    @Value("${happyboot.dev.admin-login-id:admin}")
    private String loginId;
    @Value("${happyboot.dev.admin-password:}")
    private String password;

    @Override
    public void run(ApplicationArguments args) {
        if (password == null || password.isBlank()) {
            log.warn("DEV_ADMIN_PASSWORD 가 없어 개발 계정을 만들지 않습니다 (.env 확인)");
            return;
        }
        runner.run(compCd, () -> {
            DevCompanySeed.ensureCompany(jdbc, compCd, "DEVSEED");
            DevCompanySeed.ensureModules(jdbc, compCd, "DEVSEED");
            int created = jdbc.update("""
                    insert into sys_user (comp_cd, login_id, user_nm, pwd_hash, created_by, updated_by)
                    select ?, ?, '개발 관리자', ?, 'DEVSEED', 'DEVSEED'
                     where not exists (select 1 from sys_user where comp_cd = ? and lower(login_id) = lower(?))""",
                    compCd, loginId, encoder.encode(password), compCd, loginId);
            if (created > 0) {
                log.info("개발 계정 생성: 회사 {} / 아이디 {} (비밀번호는 .env DEV_ADMIN_PASSWORD)", compCd, loginId);
            } else {
                syncPassword();
            }
            DevCompanySeed.ensureAdminGroupAndMenus(jdbc, compCd, "DEVSEED");
            jdbc.update("""
                    insert into sys_user_auth (comp_cd, user_id, auth_grp_cd, created_by)
                    select comp_cd, user_id, 'ADMIN', 'DEVSEED' from sys_user
                     where comp_cd = ? and lower(login_id) = lower(?) on conflict do nothing""", compCd, loginId);
            // 개발 회사는 관리자 2단계 인증을 강제하지 않는다(docs/36 M3) — 회사 값이 없을 때만, 사용자가 정한 값은 둔다
            jdbc.update("""
                    insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by)
                    values (?, 'MFA_REQUIRED_FOR_ADMIN', 'N', 'DEVSEED', 'DEVSEED'), (?, 'MFA_REQUIRED_PROGS', '-', 'DEVSEED', 'DEVSEED')
                    on conflict do nothing""", compCd, compCd);
        });
    }

    /**
     * .env 의 DEV_ADMIN_PASSWORD 를 바꾸고 다시 띄우면 개발 계정 비밀번호도 따라간다(2026-10-16 사용자 요청).
     * 이 시더가 만든 계정(created_by = DEVSEED)만 — 운영자 콘솔·화면에서 만든 같은 아이디는 건드리지 않는다.
     * 맞출 때 실패 횟수·자동 잠금(locked_until 있는 잠금)도 풀어 준다. 관리자 수동 잠금(locked_until 없음)은 그대로 둔다(V004).
     */
    private void syncPassword() {
        List<String> hashes = jdbc.queryForList("""
                select pwd_hash from sys_user
                 where comp_cd = ? and lower(login_id) = lower(?) and created_by = 'DEVSEED'""",
                String.class, compCd, loginId);
        if (hashes.size() == 1 && !encoder.matches(password, hashes.get(0))) {
            jdbc.update("""
                    update sys_user set pwd_hash = ?, pwd_changed_at = now(), login_fail_cnt = 0,
                                        lock_yn = case when locked_until is not null then 'N' else lock_yn end,
                                        locked_until = null,
                                        updated_at = now(), updated_by = 'DEVSEED'
                     where comp_cd = ? and lower(login_id) = lower(?) and created_by = 'DEVSEED'""",
                    encoder.encode(password), compCd, loginId);
            log.info("개발 계정 비밀번호를 .env DEV_ADMIN_PASSWORD 값으로 맞췄습니다: 회사 {} / 아이디 {}", compCd, loginId);
        }
    }
}
