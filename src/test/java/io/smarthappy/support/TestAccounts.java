package io.smarthappy.support;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import lombok.RequiredArgsConstructor;

/** 테스트용 회사·사용자 생성 (테스트마다 새 회사코드로 서로 간섭하지 않게) */
@Component
@RequiredArgsConstructor
public class TestAccounts {

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** newComp 회사의 오늘(기본 시간대 Asia/Seoul) — DB 세션의 current_date(UTC)와 UTC 15~24시에 하루 어긋난다. */
    public static final String COMP_TODAY = "(now() at time zone 'Asia/Seoul')::date";

    private final TenantRunner runner;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    public String newComp(String prefix) {
        String compCd = prefix + SEQ.incrementAndGet();
        runner.run(compCd, () -> jdbc.update(
                "insert into sys_comp (comp_cd, comp_nm, created_by, updated_by) values (?, ?, 'test', 'test')",
                compCd, compCd + " 회사"));
        return compCd;
    }

    public long newUser(String compCd, String loginId, String rawPassword) {
        return runner.call(compCd, () -> jdbc.queryForObject("""
                insert into sys_user (comp_cd, login_id, user_nm, pwd_hash, created_by, updated_by)
                values (?, ?, ?, ?, 'test', 'test') returning user_id""",
                Long.class, compCd, loginId, loginId + " 사용자", encoder.encode(rawPassword)));
    }

    public void addPlant(String compCd, String plantCd, String plantNm) {
        runner.run(compCd, () -> jdbc.update(
                "insert into sys_plant (comp_cd, plant_cd, plant_nm, created_by, updated_by) values (?, ?, ?, 't', 't')",
                compCd, plantCd, plantNm));
    }

    public void setUserUse(String compCd, long userId, String useYn) {
        runner.run(compCd, () -> jdbc.update("update sys_user set use_yn = ? where user_id = ?", useYn, userId));
    }

    public void setCompStatus(String compCd, String status) {
        runner.run(compCd, () -> jdbc.update("update sys_comp set status_cd = ? where comp_cd = ?", status, compCd));
    }

    /** 권한그룹(없으면 생성)에 사용자를 넣고 프로그램 액션을 준다 */
    public void grant(String compCd, long userId, String authGrpCd, String progCd, String... actions) {
        runner.run(compCd, () -> {
            jdbc.update("""
                    insert into sys_auth_group (comp_cd, auth_grp_cd, auth_grp_nm, created_by, updated_by)
                    values (?, ?, ?, 't', 't') on conflict do nothing""", compCd, authGrpCd, authGrpCd + " 그룹");
            jdbc.update("""
                    insert into sys_user_auth (comp_cd, user_id, auth_grp_cd, created_by)
                    values (?, ?, ?, 't') on conflict do nothing""", compCd, userId, authGrpCd);
            for (String a : actions) {
                jdbc.update("""
                        insert into sys_auth_prog (comp_cd, auth_grp_cd, prog_cd, action_cd, created_by)
                        values (?, ?, ?, ?, 't') on conflict do nothing""", compCd, authGrpCd, progCd, a);
            }
        });
    }

    /** 메뉴 추가. 반환 menu_id */
    public long addMenu(String compCd, Long parentId, String menuNm, String progCd, int sortNo) {
        return runner.call(compCd, () -> jdbc.queryForObject("""
                insert into sys_menu (comp_cd, parent_id, menu_nm, prog_cd, sort_no, created_by, updated_by)
                values (?, ?, ?, ?, ?, 't', 't') returning menu_id""", Long.class, compCd, parentId, menuNm, progCd, sortNo));
    }

    public int exec(String compCd, String sql, Object... args) {
        return runner.call(compCd, () -> jdbc.update(sql, args));
    }

    public <T> T query(String compCd, String sql, Class<T> type, Object... args) {
        return runner.call(compCd, () -> jdbc.queryForObject(sql, type, args));
    }

    /** 로그인 과정 없이 인증된 요청 (MockMvc) */
    public RequestPostProcessor as(String compCd, long userId, String loginId) {
        return as(compCd, userId, loginId, "ko");
    }

    public RequestPostProcessor as(String compCd, long userId, String loginId, String localeCd) {
        LoginUser user = new LoginUser(userId, compCd, loginId, loginId + " 사용자", localeCd, false);
        return SecurityMockMvcRequestPostProcessors.authentication(UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
