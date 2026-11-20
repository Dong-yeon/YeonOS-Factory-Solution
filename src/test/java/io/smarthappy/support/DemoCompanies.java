package io.smarthappy.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.test.util.ReflectionTestUtils;

import io.smarthappy.module.demo.DemoDataSeeder;
import io.smarthappy.module.pop.PopWorkService;
import io.smarthappy.module.production.CompletionService;
import io.smarthappy.module.production.LotService;
import io.smarthappy.module.production.ReceiptService;
import io.smarthappy.module.production.WoService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 모듈 데모 시더 시험용 — 새 회사에 공장 P1 + 회사 관리자(admin_yn 그룹) + 기준정보·MES 데모(DemoDataSeeder 전체, local 빈이라 직접 만들어 부른다).
 * 회사마다 새 코드라 서로 간섭하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class DemoCompanies {

    public record Demo(String comp, LoginUser admin) {
    }

    private final TestAccounts accounts;
    private final TenantRunner runner;
    private final JdbcTemplate jdbc;
    private final NumberingService numbering;
    private final BizClock clock;
    private final WoService workOrders;
    private final LotService lots;
    private final PopWorkService popWork;
    private final CompletionService completion;
    private final ReceiptService receipts;
    private final PolicyService policies;

    /** 관리자만 있는 빈 회사(공장 P1) */
    public Demo empty(String prefix) {
        String comp = accounts.newComp(prefix);
        accounts.addPlant(comp, "P1", "1공장");
        long admin = accounts.newUser(comp, "admin", "Demo-admin-seed-71");
        accounts.grant(comp, admin, "ADMIN", "home-main", "SEARCH");
        accounts.exec(comp, "update sys_auth_group set admin_yn = 'Y' where auth_grp_cd = 'ADMIN'");
        return new Demo(comp, new LoginUser(admin, comp, "admin", "admin 사용자", "ko", false));
    }

    /** 기준정보·MES 데모까지 넣은 회사 */
    public Demo seeded(String prefix) {
        Demo d = empty(prefix);
        DemoDataSeeder base = new DemoDataSeeder(runner, jdbc, numbering, clock, workOrders, lots, popWork, completion, receipts, policies);
        ReflectionTestUtils.setField(base, "compCd", d.comp());
        ReflectionTestUtils.setField(base, "enabled", true);
        base.run(null);
        return d;
    }
}
