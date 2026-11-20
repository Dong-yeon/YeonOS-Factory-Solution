package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.smarthappy.module.demo.DemoDataSeeder;
import io.smarthappy.module.production.CompletionService;
import io.smarthappy.module.production.LotService;
import io.smarthappy.module.production.ReceiptService;
import io.smarthappy.module.production.WoService;
import io.smarthappy.module.pop.PopWorkService;
import io.smarthappy.module.wms.WmsDemoFlow;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * APS 계획 데모 시더 (docs/38 17.6, docs/63 G10) — 공통 데모 기준정보 + WMS 재고 위에서: 지시 30건·계획 기준정보·계획 1건, 화면이 보일
 * 늦음·부족(구매품 + 반제품 "지시 필요")·병목(용접 셀 공정이 계획 기간을 넘거나 늦음), 멱등(다시 0), 기준정보 없는 회사는 0, B사에 안 섞임.
 */
class ApsDemoFlowTest extends PostgresTestBase {

    private static final String PW = "Cedar-fence-81";

    @Autowired
    TestAccounts accounts;
    @Autowired
    TenantRunner runner;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    NumberingService numbering;
    @Autowired
    BizClock clock;
    @Autowired
    WoService workOrders;
    @Autowired
    LotService lots;
    @Autowired
    PopWorkService popWork;
    @Autowired
    CompletionService completion;
    @Autowired
    ReceiptService receipts;
    @Autowired
    PolicyService policies;
    @Autowired
    WmsDemoFlow wms;
    @Autowired
    ApsDemoFlow aps;

    DemoDataSeeder seeder;
    private ch.qos.logback.classic.Level logLevel;

    @BeforeEach
    void setUp() {
        seeder = new DemoDataSeeder(runner, jdbc, numbering, clock, workOrders, lots, popWork, completion, receipts, policies);
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("io.smarthappy");
        logLevel = root.getLevel();
        root.setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @AfterEach
    void restoreLog() {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("io.smarthappy")).setLevel(logLevel);
    }

    private LoginUser company(String prefix, boolean withMaster) {
        String c = accounts.newComp(prefix);
        long userId = accounts.newUser(c, "admin1", PW);
        accounts.addPlant(c, "P1", "1공장");
        if (withMaster) {
            seeder.seed(c);
            seeder.seedItems(c);
            seeder.seedBoms(c);
            seeder.seedEquipHier(c, "P1");
            seeder.seedFgLocs(c, "P1");
            seeder.seedEquips(c, "P1");
            seeder.seedRoutings(c, "P1");
            seeder.seedWorkforce(c, "P1");
            seeder.seedSales(c);
        }
        return new LoginUser(userId, c, "admin1", "admin1", "ko", false);
    }

    private long n(String comp, String sql, Object... args) {
        Long v = accounts.query(comp, sql, Long.class, args);
        return v == null ? 0 : v;
    }

    @Test
    void 지시_30건과_기준정보_계획을_넣고_늦음_부족_병목이_보이며_멱등이고_B사에_안_섞인다() {
        LoginUser a = company("APD", true);
        LoginUser b = company("APE", true);
        String ca = a.compCd();
        wms.seed(ca, a);
        long bWos = n(b.compCd(), "select count(*) from prd_wo");

        assertThat(aps.seed(ca, a)).isEqualTo(30);
        assertThat(n(ca, "select count(*) from prd_wo where remark = ? and status = 'PLANNED'", ApsDemoFlow.REMARK)).isEqualTo(30);
        assertThat(n(ca, "select count(*) from aps_item_param where use_yn = 'Y'")).isEqualTo(4);
        assertThat(n(ca, "select count(*) from aps_plan where plant_cd = 'P1' and status = 'CALCULATED'")).isEqualTo(1);
        long plan = n(ca, "select plan_id from aps_plan where plant_cd = 'P1'");
        // 늦음 또는 기간 안에 못 한 공정(병목) — 첫 사흘 용접 몰림
        assertThat(n(ca, "select late_wo_cnt + unsched_op_cnt from aps_plan where plan_id = ?", plan)).isPositive();
        // 구매품 부족(용접 너트·강판) + 반제품 부족("지시 필요")
        assertThat(n(ca, """
                select count(*) from aps_plan_mat m join mst_item i on i.item_id = m.item_id
                 where m.plan_id = ? and m.short_qty > 0 and i.proc_type = 'BUY'""", plan)).isPositive();
        assertThat(n(ca, """
                select count(*) from aps_plan_mat m join mst_item i on i.item_id = m.item_id
                 where m.plan_id = ? and m.short_qty > 0 and i.proc_type <> 'BUY'""", plan)).isPositive();
        // 구매 요청은 자동으로 만들지 않는다(AP-7)
        assertThat(n(ca, "select count(*) from aps_plan_pr")).isZero();

        // 멱등 — 다시 돌리면 0, 흔적 그대로
        assertThat(aps.seed(ca, a)).isZero();
        assertThat(n(ca, "select count(*) from prd_wo where remark = ?", ApsDemoFlow.REMARK)).isEqualTo(30);
        assertThat(n(ca, "select count(*) from aps_plan")).isEqualTo(1);
        // B사는 그대로
        assertThat(n(b.compCd(), "select count(*) from prd_wo")).isEqualTo(bWos);
        assertThat(n(b.compCd(), "select count(*) from aps_plan")).isZero();
        assertThat(n(b.compCd(), "select count(*) from aps_item_param")).isZero();
    }

    @Test
    void 데모_기준정보가_없는_회사는_아무것도_하지_않는다() {
        LoginUser c = company("APF", false);
        assertThat(aps.seed(c.compCd(), c)).isZero();
        assertThat(n(c.compCd(), "select count(*) from prd_wo")).isZero();
        assertThat(n(c.compCd(), "select count(*) from aps_plan")).isZero();
    }
}
