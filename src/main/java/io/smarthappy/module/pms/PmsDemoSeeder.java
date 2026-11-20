package io.smarthappy.module.pms;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.smarthappy.module.demo.DemoSeedOrder;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 개발·데모 회사에 PMS 데모 프로젝트 2개(docs/32 PM5) — {@link PmsDemoFlow}. 회사 관리자(admin_yn 그룹 구성원) 이름으로 실행,
 * 회사에 프로젝트가 있으면 건너뛴다. 공통 DemoDataSeeder 를 고치지 않으려고 모듈 안에 따로 둔다(docs/29 2.4) — 그 시더가 넣는
 * 거래처·품목을 연결하려고 모든 ApplicationRunner(그 시더 포함) 뒤인 ApplicationReadyEvent 에서 돈다
 * (같은 LOWEST_PRECEDENCE 러너끼리는 순서가 정해지지 않아 먼저 돌았다 — 화면 확인에서 발견). 그래도 없으면 연결 없이 만든다
 */
@Slf4j
@Component
@Profile({"local", "demo"})
@RequiredArgsConstructor
public class PmsDemoSeeder {

    private final PmsDemoFlow flow;
    private final TenantRunner runner;
    private final JdbcTemplate jdbc;

    @Value("${happyboot.dev.comp-cd:DEMO}")
    private String compCd;
    @Value("${happyboot.dev.demo-data:true}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    @Order(DemoSeedOrder.PMS)
    public void seed() {
        if (!enabled) {
            return;
        }
        List<LoginUser> admins = runner.call(compCd, () -> jdbc.query("""
                select u.user_id, u.login_id, u.user_nm, u.locale_cd from sys_user u
                 where u.use_yn = 'Y' and exists (select 1 from sys_user_auth a join sys_auth_group g
                                                     on g.comp_cd = a.comp_cd and g.auth_grp_cd = a.auth_grp_cd
                                                  where a.user_id = u.user_id and g.admin_yn = 'Y' and g.use_yn = 'Y')
                 order by u.user_id limit 1""",
                (rs, i) -> new LoginUser(rs.getLong("user_id"), compCd, rs.getString("login_id"), rs.getString("user_nm"),
                        rs.getString("locale_cd"), false)));
        if (admins.isEmpty()) {
            return;
        }
        try {
            int n = flow.seed(compCd, admins.get(0));
            if (n > 0) {
                log.info("데모 데이터: 회사 {} 에 PMS 프로젝트 {}개(APQP 게이트 2개 통과·지연·이슈·공수, 4M 변경)", compCd, n);
            }
        } catch (RuntimeException e) {
            // 데모 시드 실패가 기동을 막지 않게 — 원인은 로그로
            log.warn("PMS 데모 데이터를 넣지 못했습니다: {}", e.toString());
        }
    }
}
