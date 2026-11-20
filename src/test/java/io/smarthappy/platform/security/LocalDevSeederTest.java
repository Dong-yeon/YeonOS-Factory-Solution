package io.smarthappy.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/** 로컬 개발 계정 시더 — local 프로필 빈이라 직접 만들어 부른다 */
class LocalDevSeederTest extends PostgresTestBase {

    private static final String ID = "devadmin";

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    TestAccounts accounts;

    private static int seq;

    private LocalDevSeeder seeder(String comp, String password) {
        LocalDevSeeder s = new LocalDevSeeder(runner, jdbc, encoder);
        ReflectionTestUtils.setField(s, "compCd", comp);
        ReflectionTestUtils.setField(s, "loginId", ID);
        ReflectionTestUtils.setField(s, "password", password);
        return s;
    }

    private String hash(String comp) {
        return accounts.query(comp, "select pwd_hash from sys_user where login_id = ?", String.class, ID);
    }

    private String newCompCd() {
        return "LDS" + (++seq) + System.nanoTime() % 100000;
    }

    @Test
    void env_비밀번호를_바꾸고_다시_띄우면_개발_계정도_따라간다() {
        String comp = newCompCd();
        seeder(comp, "Old-pass-11").run(null);
        assertThat(encoder.matches("Old-pass-11", hash(comp))).isTrue();

        seeder(comp, "1234").run(null);
        assertThat(encoder.matches("1234", hash(comp))).isTrue();
        assertThat(encoder.matches("Old-pass-11", hash(comp))).isFalse();
    }

    @Test
    void 같은_비밀번호면_해시를_다시_만들지_않는다() {
        String comp = newCompCd();
        seeder(comp, "Same-pass-22").run(null);
        String before = hash(comp);
        seeder(comp, "Same-pass-22").run(null);
        assertThat(hash(comp)).isEqualTo(before);
    }

    @Test
    void 자동_잠금은_풀고_관리자_수동_잠금은_그대로_둔다() {
        String comp = newCompCd();
        seeder(comp, "First-pass-33").run(null);
        accounts.exec(comp, """
                update sys_user set login_fail_cnt = 5, lock_yn = 'Y', locked_until = now() + interval '15 minutes'
                 where login_id = ?""", ID);
        seeder(comp, "Second-pass-44").run(null);
        assertThat(accounts.query(comp, "select lock_yn || login_fail_cnt || coalesce(locked_until::text, '-') from sys_user where login_id = ?",
                String.class, ID)).isEqualTo("N0-");

        accounts.exec(comp, "update sys_user set lock_yn = 'Y', locked_until = null where login_id = ?", ID);
        seeder(comp, "Third-pass-55").run(null);
        assertThat(accounts.query(comp, "select lock_yn from sys_user where login_id = ?", String.class, ID)).isEqualTo("Y");
    }

    @Test
    void 시더가_만들지_않은_같은_아이디는_건드리지_않는다() {
        // 운영자 콘솔·관리 화면에서 만든 계정(created_by 가 DEVSEED 가 아님)
        String comp = accounts.newComp("LD");
        accounts.newUser(comp, ID, "Console-made-66");
        String before = hash(comp);
        seeder(comp, "Other-pass-77").run(null);
        assertThat(hash(comp)).isEqualTo(before);
        assertThat(encoder.matches("Console-made-66", hash(comp))).isTrue();
    }

    @Test
    void 비밀번호가_비어_있으면_아무것도_하지_않는다() {
        String comp = newCompCd();
        seeder(comp, "Keep-pass-88").run(null);
        String before = hash(comp);
        seeder(comp, " ").run(null);
        assertThat(hash(comp)).isEqualTo(before);
    }
}
