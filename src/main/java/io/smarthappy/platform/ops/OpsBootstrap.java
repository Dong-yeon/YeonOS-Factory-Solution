package io.smarthappy.platform.ops;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 첫 운영자 만들기 — 앱 계정은 운영자를 만들 수 없으므로(V011 권한) **DB 소유자 자격증명이 있는 실행에서만**:
 * 마이그레이션 전용 실행(migrate) 또는 로컬 개발(local, 앱이 Flyway 를 돌림).
 * OPS_BOOTSTRAP_LOGIN·OPS_BOOTSTRAP_PASSWORD 가 있고 운영자가 한 명도 없을 때만 만든다. TOTP 는 첫 로그인 때 등록.
 */
@Slf4j
@Component
@Profile({"migrate", "local"})
@RequiredArgsConstructor
public class OpsBootstrap implements ApplicationRunner {

    private final Environment env;
    private final PasswordEncoder encoder;

    @Override
    public void run(ApplicationArguments args) {
        String login = env.getProperty("happyboot.ops.bootstrap-login", "");
        String password = env.getProperty("happyboot.ops.bootstrap-password", "");
        if (login.isBlank() || password.isBlank()) {
            return;
        }
        String url = env.getProperty("spring.flyway.url", env.getProperty("spring.datasource.url"));
        String user = env.getProperty("spring.flyway.user");
        String pw = env.getProperty("spring.flyway.password");
        if (url == null || user == null || pw == null) {
            log.warn("운영자 부트스트랩: DB 소유자 자격증명이 없어 건너뜀");
            return;
        }
        int created = bootstrap(new JdbcTemplate(new DriverManagerDataSource(url, user, pw)), login, password);
        if (created > 0) {
            log.info("첫 운영자 생성: {} (TOTP 는 첫 로그인 때 등록)", login.strip().toLowerCase(java.util.Locale.ROOT));
        }
    }

    int bootstrap(JdbcTemplate owner, String login, String password) {
        if (password.length() < 12) {
            throw new IllegalStateException("운영자 비밀번호는 12자 이상이어야 합니다");
        }
        return owner.update("""
                insert into sys_operator (login_id, op_nm, pwd_hash, created_by)
                select ?, '운영자', ?, 'BOOTSTRAP' where not exists (select 1 from sys_operator)""",
                login.strip().toLowerCase(java.util.Locale.ROOT), encoder.encode(password));
    }
}
