package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import io.smarthappy.module.demo.DemoDataSeeder;
import io.smarthappy.module.qms.SpcDtos.ChartQuery;
import io.smarthappy.module.qms.SpcDtos.ChartView;
import io.smarthappy.module.qms.SpcDtos.PointView;
import io.smarthappy.platform.security.DevCompanySeed;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 로컬 QMS 데모 시더 — local 프로필에서만 빈이 생기고, 기준정보 데모가 있는 빈 회사에 판정 확정 검사 30건(하루 1건)·고정 한계·
 * 마지막 4건만 한계 밖(SPC 위반)을 서비스 길로 넣는다. 다시 돌려도 늘지 않고, 다른 회사·실제 시계는 건드리지 않는다.
 */
class QmsDemoSeederTest extends PostgresTestBase {

    @Autowired
    ApplicationContext ctx;

    @Autowired
    AutowireCapableBeanFactory factory;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SpcService spc;

    @Autowired
    BizClock clock;

    @Test
    void local_프로필에만_빈이_생긴다() {
        assertThat(ctx.getBeanNamesForType(QmsDemoSeeder.class)).as("test 프로필").isEmpty();
        assertThat(QmsDemoSeeder.class.getAnnotation(Profile.class).value()).containsExactly("local");
    }

    @Test
    void 빈_회사에_검사_30건과_마지막_4건_SPC_위반을_넣고_다시_돌려도_늘지_않는다() {
        String comp = accounts.newComp("QSD");
        String other = accounts.newComp("QSX");
        runner.run(comp, () -> {
            DevCompanySeed.ensureCompany(jdbc, comp, "t");
            DevCompanySeed.ensureAdminGroupAndMenus(jdbc, comp, "t");
        });
        long admin = accounts.newUser(comp, "qadmin", "Seed-test-pw-1!");
        accounts.exec(comp, "insert into sys_user_auth (comp_cd, user_id, auth_grp_cd, created_by) values (?, ?, 'ADMIN', 't')", comp, admin);
        DemoDataSeeder demo = factory.createBean(DemoDataSeeder.class);
        ReflectionTestUtils.setField(demo, "compCd", comp);
        demo.run(null);
        QmsDemoSeeder seeder = factory.createBean(QmsDemoSeeder.class);
        ReflectionTestUtils.setField(seeder, "compCd", comp);

        seeder.onReady();

        assertThat(accounts.query(comp, "select count(*) from qms_insp where status = 'JUDGED' and result = 'OK' and created_by = 'qadmin'",
                Integer.class)).isEqualTo(30);
        assertThat(accounts.query(comp, "select count(distinct insp_no) from qms_insp", Integer.class)).as("채번").isEqualTo(30);
        assertThat(accounts.query(comp, "select count(distinct (occurred_at at time zone 'Asia/Seoul')::date) from qms_insp", Integer.class))
                .as("하루 1건").isEqualTo(30);
        assertThat(accounts.query(comp, "select count(*) from qms_insp where occurred_at > now()", Integer.class)).as("미래 시각 없음").isZero();
        // 측정 원장 시각 = 검사 1분 뒤(시더 시계), 기록 시각은 실제
        assertThat(accounts.query(comp, """
                select count(*) from qms_insp_meas m join qms_insp_line x on x.insp_line_id = m.insp_line_id join qms_insp h on h.insp_id = x.insp_id
                 where m.occurred_at <> h.occurred_at + interval '1 minute'""", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select count(*) from qms_spc_limit where status = 'ACTIVE' and subgroup_cnt = 26", Integer.class))
                .isEqualTo(1);
        // SPC 위반 = 마지막 4건에만, 모두 미처리
        assertThat(accounts.query(comp, """
                select count(distinct e.insp_id) from qms_spc_event e join qms_spc_event_state s on s.event_id = e.event_id
                 where s.status = 'OPEN' and e.insp_id in (select insp_id from qms_insp order by occurred_at desc limit 4)""", Integer.class))
                .isEqualTo(4);
        assertThat(accounts.query(comp, "select count(distinct insp_id) from qms_spc_event", Integer.class)).isEqualTo(4);
        assertThat(accounts.query(comp, "select count(*) from qms_spc_event where rule_cd = 'WE1'", Integer.class)).isEqualTo(4);

        // 관리도(화면과 같은 계산): 30점, 고정 한계, 한계 밖 점은 마지막 4개
        ChartView v = chart(comp);
        assertThat(v.points()).hasSize(30);
        assertThat(v.source()).isEqualTo("FIXED");
        assertThat(v.points().stream().filter(p -> !p.rules().isEmpty()).map(PointView::index).toList())
                .isEqualTo(IntStream.range(26, 30).boxed().toList());
        List<PointView> out = v.points().subList(26, 30);
        assertThat(out).allMatch(p -> p.mean() > p.xUcl());
        assertThat(v.points().subList(0, 26)).allMatch(p -> p.mean() <= p.xUcl() && p.mean() >= p.xLcl());

        // 시계는 실제로 돌아와 있다
        assertThat(Duration.between(clock.instant(), Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));

        // 다시 돌려도 늘지 않는다
        int lots = accounts.query(comp, "select count(*) from lot_master", Integer.class);
        int events = accounts.query(comp, "select count(*) from qms_spc_event", Integer.class);
        seeder.onReady();
        assertThat(accounts.query(comp, "select count(*) from qms_insp", Integer.class)).isEqualTo(30);
        assertThat(accounts.query(comp, "select count(*) from qms_spc_event", Integer.class)).isEqualTo(events);
        assertThat(accounts.query(comp, "select count(*) from qms_spc_limit", Integer.class)).isEqualTo(1);
        assertThat(accounts.query(comp, "select count(*) from lot_master", Integer.class)).isEqualTo(lots);

        // 다른 회사(A사로 B사)는 그대로
        assertThat(accounts.query(other, "select count(*) from qms_insp", Integer.class)).isZero();
    }

    @Test
    void 기준정보_데모가_없는_회사는_건너뛴다() {
        String comp = accounts.newComp("QSE");
        long admin = accounts.newUser(comp, "qadmin", "Seed-test-pw-1!");
        runner.run(comp, () -> DevCompanySeed.ensureAdminGroupAndMenus(jdbc, comp, "t"));
        accounts.exec(comp, "insert into sys_user_auth (comp_cd, user_id, auth_grp_cd, created_by) values (?, ?, 'ADMIN', 't')", comp, admin);
        QmsDemoSeeder seeder = factory.createBean(QmsDemoSeeder.class);
        ReflectionTestUtils.setField(seeder, "compCd", comp);
        seeder.onReady();
        assertThat(accounts.query(comp, "select count(*) from qms_insp", Integer.class)).isZero();
    }

    @Test
    void 계획_유효_시작일이_가까우면_그_사이에_몰아_넣고_늘_지금보다_앞이다() {
        ZoneId seoul = ZoneId.of("Asia/Seoul");
        LocalDate today = LocalDate.of(2026, 11, 2);
        // 이른 아침(오늘 9시 전)에 띄워도 오늘 몫은 지금 앞으로 당겨지고 순서가 유지된다
        Instant now = today.atTime(7, 30).atZone(seoul).toInstant();
        for (LocalDate validFrom : List.of(today.minusDays(60), today.minusDays(7), today)) {
            List<Instant> t = QmsDemoSeeder.schedule(today, validFrom, seoul, now);
            assertThat(t).as(validFrom.toString()).hasSize(30).isSorted().doesNotHaveDuplicates().allMatch(x -> x.isBefore(now));
            LocalDate first = t.get(0).atZone(seoul).toLocalDate();
            assertThat(first).as("계획 시작일보다 앞으로 가지 않는다(오늘 몫 당김으로 오늘이 시작일이면 전날 밤까지는 허용)")
                    .isAfterOrEqualTo(validFrom.minusDays(1));
        }
        assertThat(QmsDemoSeeder.schedule(today, today.minusDays(60), seoul, now).stream().map(x -> x.atZone(seoul).toLocalDate()).distinct())
                .hasSize(30);
        assertThat(QmsDemoSeeder.schedule(today, today.minusDays(7), seoul, now).stream().map(x -> x.atZone(seoul).toLocalDate()).distinct())
                .hasSize(8);
    }

    private ChartView chart(String comp) {
        return runner.call(comp, () -> {
            long item = jdbc.queryForObject("select item_id from mst_item where item_cd = '64311-DM100'", Long.class);
            long ch = jdbc.queryForObject("select char_id from qms_spc_limit where status = 'ACTIVE'", Long.class);
            long proc = jdbc.queryForObject("select proc_id from mst_proc where proc_cd = 'DRAW'", Long.class);
            return spc.chart(new ChartQuery(item, ch, "PROCESS", proc, 30, null, null));
        });
    }
}
