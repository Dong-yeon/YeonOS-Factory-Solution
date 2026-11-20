package io.smarthappy.module.cms;

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
 * 개발·데모 회사에 CMS 지난 90일 이력 — {@link CmsDemoFlow}. 회사 관리자(admin_yn 그룹 구성원) 이름으로 실행. 공통 DemoDataSeeder 가 넣는 데모 설비가 있어야
 * 하므로 모든 ApplicationRunner 뒤인 ApplicationReadyEvent 에서 돈다(PmsDemoSeeder 와 같은 이유). 그 설비에 이력이 있으면 건너뛴다
 */
@Slf4j
@Component
@Profile({"local", "demo"})
@RequiredArgsConstructor
public class CmsDemoSeeder {

    private final CmsDemoFlow flow;
    private final TenantRunner runner;
    private final JdbcTemplate jdbc;

    @Value("${happyboot.dev.comp-cd:DEMO}")
    private String compCd;
    @Value("${happyboot.dev.demo-data:true}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    @Order(DemoSeedOrder.CMS)
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
                log.info("데모 데이터: 회사 {} 에 CMS 지난 {}일 이력(설비 상태·고장 작업지시 {}건·주간 점검·예비품)", compCd, CmsDemoFlow.DAYS, n);
            }
        } catch (RuntimeException e) {
            // 데모 시드 실패가 기동을 막지 않게 — 한 트랜잭션이라 남는 것 없음, 원인은 로그로
            log.warn("CMS 데모 데이터를 넣지 못했습니다: {}", e.toString());
        }
    }
}
