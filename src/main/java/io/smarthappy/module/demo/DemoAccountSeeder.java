package io.smarthappy.module.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import io.smarthappy.platform.security.DevCompanySeed;
import io.smarthappy.platform.tenant.TenantRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 공개 데모 전용(demo 프로필, prod 위에 얹는다 — docs/23 3.2): 데모 회사와 비밀번호가 공개되는 체험 계정을 만든다.
 * 체험 계정의 권한그룹 DEMO_VIEWER 는 허용 목록 방식 — 기준정보(mst-*)·영업(sal-*)·생산(prd-*) 화면 전체 + POP 단말 관리
 * (sys-terminal — 체험자가 단말을 등록해 /pop 을 써 볼 수 있게, 2026-10-20 사용자 결정) + 활용 현황 조회 + 내 정보 조회
 * + 업무 흐름 지도 조회(home-flow-map, docs/46 12장 — 둘러보기 길잡이)
 * + **나머지 업무 모듈 전 화면 조회(SEARCH)**(품질·창고·구매·설비·프로젝트·계획·설계변경·APQP·홈 — 2026-10-26 사용자 결정,
 * 손님이 모든 모듈을 둘러볼 수 있게). 조회는 `sys-*`·`home-*` 를 뺀 규칙이라 새 업무 화면은 자동으로 조회에 들어간다.
 * 비밀번호 변경(내 정보 SAVE)·지원 세션 부여(sys-support)·홈 카드 관리와 앞으로 생길 시스템 관리 화면은 자동으로 빠진다.
 * 데이터는 매일 DB 를 새로 만들어 지운다(deploy/demo/reset.sh) — 이 시더와 DemoDataSeeder 가 다시 채운다.
 */
@Slf4j
@Component
@Profile("demo")
@Order(0)   // 회사·계정이 먼저 — 데모 데이터(DemoDataSeeder)는 그 뒤
@RequiredArgsConstructor
public class DemoAccountSeeder implements ApplicationRunner {

    static final String ACTOR = "DEMOSEED";
    static final String GROUP = "DEMO_VIEWER";
    static final int MIN_PASSWORD = 12;

    private final TenantRunner runner;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    @Value("${happyboot.dev.comp-cd:DEMO}")
    private String compCd;
    @Value("${happyboot.demo.login-id:user1}")
    private String loginId;
    @Value("${happyboot.demo.password:}")
    private String password;

    @Override
    public void run(ApplicationArguments args) {
        // 공개 데모가 "로그인 안 되는 사이트"로 조용히 뜨지 않게 기동을 멈춘다
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new IllegalStateException("demo 프로필에는 DEMO_PASSWORD(" + MIN_PASSWORD + "자 이상)가 필요합니다");
        }
        runner.run(compCd, () -> {
            DevCompanySeed.ensureCompany(jdbc, compCd, ACTOR);
            DevCompanySeed.ensureModules(jdbc, compCd, ACTOR);
            DevCompanySeed.ensureAdminGroupAndMenus(jdbc, compCd, ACTOR);
            jdbc.update("""
                    insert into sys_auth_group (comp_cd, auth_grp_cd, auth_grp_nm, created_by, updated_by)
                    values (?, ?, '데모 체험', ?, ?) on conflict do nothing""", compCd, GROUP, ACTOR, ACTOR);
            jdbc.update("""
                    insert into sys_auth_prog (comp_cd, auth_grp_cd, prog_cd, action_cd, created_by)
                    select ?, ?, p.prog_cd, a, ? from sys_prog p, unnest(p.actions) a
                     where p.prog_cd like 'mst-%' or p.prog_cd like 'sal-%' or p.prog_cd like 'prd-%'
                        or p.prog_cd = 'sys-terminal'
                        or (p.prog_cd in ('sys-usage', 'sys-my-info', 'home-main', 'home-flow-map') and a = 'SEARCH')
                        or (a = 'SEARCH' and p.prog_cd !~ '^(sys|home)-')
                    on conflict do nothing""", compCd, GROUP, ACTOR);
            int created = jdbc.update("""
                    insert into sys_user (comp_cd, login_id, user_nm, pwd_hash, created_by, updated_by)
                    select ?, ?, '데모 체험', ?, ?, ?
                     where not exists (select 1 from sys_user where comp_cd = ? and lower(login_id) = lower(?))""",
                    compCd, loginId, encoder.encode(password), ACTOR, ACTOR, compCd, loginId);
            if (created == 0) {
                syncPassword();
            }
            jdbc.update("""
                    insert into sys_user_auth (comp_cd, user_id, auth_grp_cd, created_by)
                    select comp_cd, user_id, ?, ? from sys_user
                     where comp_cd = ? and lower(login_id) = lower(?) and created_by = ? on conflict do nothing""",
                    GROUP, ACTOR, compCd, loginId, ACTOR);
            // 디스크 보호 — 첨부 2MB, 엑셀 이관 2,000행 (정책 범위 안)
            for (String[] p : new String[][] {{"FILE_MAX_MB", "2"}, {"IMPORT_MAX_ROWS", "2000"}}) {
                jdbc.update("""
                        insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by)
                        values (?, '*', ?, ?, ?, ?) on conflict do nothing""", compCd, p[0], p[1], ACTOR, ACTOR);
            }
        });
        log.info("데모 계정 준비: 회사 {} / 아이디 {} (권한그룹 {})", compCd, loginId, GROUP);
    }

    /** DEMO_PASSWORD 를 바꾸고 다시 띄우면 따라간다. 이 시더가 만든 계정만, 실패 횟수·자동 잠금도 푼다 */
    private void syncPassword() {
        String hash = jdbc.query("""
                select pwd_hash from sys_user where comp_cd = ? and lower(login_id) = lower(?) and created_by = ?""",
                rs -> rs.next() ? rs.getString(1) : null, compCd, loginId, ACTOR);
        if (hash != null && !encoder.matches(password, hash)) {
            jdbc.update("""
                    update sys_user set pwd_hash = ?, pwd_changed_at = now(), login_fail_cnt = 0,
                                        lock_yn = case when locked_until is not null then 'N' else lock_yn end,
                                        locked_until = null, updated_at = now(), updated_by = ?
                     where comp_cd = ? and lower(login_id) = lower(?) and created_by = ?""",
                    encoder.encode(password), ACTOR, compCd, loginId, ACTOR);
        }
    }
}
