package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import io.smarthappy.module.cms.CmsPmDtos.PlanForm;
import io.smarthappy.module.cms.KpiService.KpiLine;
import io.smarthappy.module.cms.KpiService.KpiReport;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * CMS 데모 시더 (docs/64 C3) — 서비스 길로 넣은 지난 90일이 KPI(MTBF·MTTR·가용률·점검 준수율)와 원인 파레토로 보이는지, 원장 두 시각(occurred_at 과거 ·
 * recorded_at 지금), 멱등(이미 있으면 0), A사 시드가 B사에 안 섞임(같은 설비 코드여도), 데모 설비 없는 회사는 아무것도 안 함, 지난 점검 완료 시각 경로의 미래 거부.
 */
class CmsDemoFlowTest extends PostgresTestBase {

    private static final String PW = "Birch-gauge-73";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    TestAccounts accounts;

    @Autowired
    CmsDemoFlow demo;

    @Autowired
    KpiService kpi;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PmPlanService plans;

    @Autowired
    PmGenerator generator;

    @Autowired
    ChkRunService runs;

    /** 회사 + 관리자 + 공장 P1 + (equips 면) 데모와 같은 설비 4대·콘택트 팁 품목 */
    private LoginUser company(String prefix, boolean equips) {
        String comp = accounts.newComp(prefix);
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.addPlant(comp, "P1", "1공장");
        if (equips) {
            accounts.exec(comp, """
                    insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by)
                    values (?, 'PRESS', '프레스', 't', 't'), (?, 'WELD', '용접기', 't', 't')""", comp, comp);
            long area = accounts.query(comp, """
                    insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                    values (?, 'P1', null, 'AREA', 'BODY', '차체동', 't', 't') returning node_id""", Long.class, comp);
            long line = accounts.query(comp, """
                    insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                    values (?, 'P1', ?, 'LINE', 'L1', '1라인', 't', 't') returning node_id""", Long.class, comp, area);
            for (String[] e : new String[][] {{"PR-1200", "PRESS"}, {"SPOT-101", "WELD"}, {"SPOT-102", "WELD"}, {"CO2-201", "WELD"}}) {
                accounts.exec(comp, """
                        insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, node_id, created_by, updated_by)
                        values (?, 'P1', ?, ?, (select class_id from mst_equip_class where class_cd = ?), ?, 't', 't')""",
                        comp, e[0], e[0] + " 설비", e[1], line);
            }
            accounts.exec(comp, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                    values (?, 'TIP-M6-1.2', '콘택트 팁', 'CON', 'BUY', 'EA', 't', 't')""", comp);
        }
        return new LoginUser(userId, comp, "admin1", "admin1", "ko", false);
    }

    private int n(String comp, String sql, Object... args) {
        Integer v = accounts.query(comp, sql, Integer.class, args);
        return v == null ? 0 : v;
    }

    /** 회사 하나에 남은 CMS 데모 흔적 합 */
    private int footprint(String comp) {
        return n(comp, """
                select (select count(*) from cms_equip_state_log) + (select count(*) from cms_mwo) + (select count(*) from cms_chk_run)
                     + (select count(*) from cms_pm_plan) + (select count(*) from cms_fail_code) + (select count(*) from cms_mwo_part)
                     + (select count(*) from cms_state_reason) + (select count(*) from sys_terminal)""");
    }

    @Test
    void 지난_90일을_서비스_길로_넣고_KPI_와_파레토가_보인다_멱등_A사로_B사() {
        LoginUser a = company("CDA", true);
        LoginUser b = company("CDB", true);
        Instant before = Instant.now().minusSeconds(1);

        int made = demo.seed(a.compCd(), a);
        assertThat(made).isGreaterThan(20);
        String ca = a.compCd();

        // 원장: 발생은 지난 90일(+ 하루 전 밤 비계획 시작), 기록은 지금. 수집 이벤트 길(AUTO)
        assertThat(n(ca, "select count(*) from cms_equip_state_log")).isGreaterThan(400);
        assertThat(n(ca, "select count(*) from cms_equip_state_log where occurred_at < now() - interval '92 days' or occurred_at > now()")).isZero();
        assertThat(n(ca, "select count(*) from cms_equip_state_log where recorded_at < ?", java.sql.Timestamp.from(before))).isZero();
        assertThat(n(ca, "select count(*) from cms_equip_state_log where entry_method <> 'AUTO' or event_id not like 'CMSDEMO-%'")).isZero();
        // 고장 = 비계획 정지 구간 = 정지 있는 고장 작업지시(진행 중 1건 포함)
        int udt = n(ca, "select count(*) from cms_equip_state_log where state_cat = 'UDT'");
        assertThat(udt).isGreaterThan(15);
        assertThat(n(ca, "select count(*) from cms_mwo where mwo_type = 'BM' and down_yn = 'Y'")).isEqualTo(udt);
        int past = n(ca, "select count(*) from cms_mwo where mwo_type = 'BM' and status in ('DONE', 'CLOSED')");
        assertThat(n(ca, "select count(*) from cms_mwo where status = 'DONE'")).isEqualTo(3);
        assertThat(n(ca, "select count(*) from cms_mwo where status = 'CLOSED'")).isEqualTo(past - 3);
        assertThat(n(ca, "select count(*) from cms_mwo_down where void_yn = 'N'")).isEqualTo(past - 2);
        assertThat(n(ca, "select count(*) from cms_mwo where finished_at > now() or down_start_at < now() - interval '92 days'")).isZero();
        assertThat(n(ca, "select count(*) from cms_mwo_part")).isGreaterThan(5);
        assertThat(n(ca, "select count(*) from cms_mwo_labor")).isEqualTo(past);

        // 원인 파레토: 전극 팁 마모가 가장 많고 원인이 5가지 이상
        List<Map<String, Object>> pareto = runner.call(ca, () -> jdbc.queryForList("""
                select f.fail_cd, count(*) as cnt from cms_mwo m join cms_fail_code f on f.fail_id = m.cause_id
                 group by f.fail_cd order by count(*) desc, f.fail_cd"""));
        assertThat(pareto.size()).isGreaterThanOrEqualTo(5);
        assertThat(pareto.get(0).get("fail_cd")).isEqualTo("TIP-WEAR");
        assertThat(((Number) pareto.get(0).get("cnt")).longValue()).isGreaterThan(((Number) pareto.get(1).get("cnt")).longValue());

        // 주간 점검: 계획마다 지난 예정은 모두 끝(완료·건너뜀), 몇 건은 하루 늦게, 수행 시각은 과거·기록은 지금
        assertThat(n(ca, "select count(*) from cms_pm_plan where plan_cd like 'DM-PM-%'")).isEqualTo(4);
        assertThat(n(ca, "select count(*) from cms_chk_run where status = 'DONE'")).isGreaterThan(40);
        assertThat(n(ca, "select count(*) from cms_chk_run where status = 'SKIPPED'")).isEqualTo(1);
        assertThat(n(ca, "select count(*) from cms_chk_run where status = 'OPEN' and due_date < " + TestAccounts.COMP_TODAY)).isZero();
        assertThat(n(ca, "select count(*) from cms_chk_run where status = 'DONE' and (performed_at at time zone 'Asia/Seoul')::date > due_date"))
                .isGreaterThan(2);
        assertThat(n(ca, "select count(*) from cms_chk_run where performed_at > now() or updated_at < ?", java.sql.Timestamp.from(before))).isZero();
        assertThat(n(ca, "select count(*) from cms_pm_plan where next_due_date < " + TestAccounts.COMP_TODAY)).isZero();

        // KPI 화면 계산: 설비마다 고장·MTBF·MTTR·가용률, 공장 점검 준수율 0~1 사이(늦은 것 때문에 1 미만)
        LocalDate today = LocalDate.now(SEOUL);
        KpiReport r = runner.call(ca, () -> kpi.report("P1", today.minusDays(89).toString(), today.toString(), null));
        assertThat(r.equips()).hasSize(4);
        for (KpiLine l : r.equips()) {
            assertThat(l.raw().failures()).as(l.code()).isPositive();
            assertThat(l.kpi().mtbfMin()).as(l.code()).isNotNull();
            assertThat(l.kpi().mttrMin()).as(l.code()).isNotNull();
            assertThat(l.kpi().availability()).as(l.code()).isNotNull().isLessThan(BigDecimal.ONE);
        }
        assertThat(r.plant().kpi().pmCompliance()).isNotNull().isGreaterThan(new BigDecimal("0.5")).isLessThan(BigDecimal.ONE);

        // A사로 B사: 같은 설비 코드여도 B 에는 아무것도 없다. A 의 작업지시 id 를 B 범위로 읽으면 0
        assertThat(footprint(b.compCd())).isZero();
        Long[] aIds = runner.call(ca, () -> jdbc.queryForList("select mwo_id from cms_mwo", Long.class).toArray(Long[]::new));
        assertThat(n(b.compCd(), "select count(*) from cms_mwo where mwo_id = any(?)", (Object) aIds)).isZero();

        // 멱등: 다시 돌리면 0, 아무것도 늘지 않음
        int fa = footprint(ca);
        assertThat(demo.seed(ca, a)).isZero();
        assertThat(footprint(ca)).isEqualTo(fa);

        // B 를 따로 시드해도 A 는 그대로, B 는 자기 것만
        assertThat(demo.seed(b.compCd(), b)).isGreaterThan(20);
        assertThat(footprint(ca)).isEqualTo(fa);
        assertThat(n(b.compCd(), "select count(*) from cms_mwo where mwo_id = any(?)", (Object) aIds)).isZero();
    }

    @Test
    void 데모_설비가_없으면_아무것도_넣지_않는다() {
        LoginUser c = company("CDC", false);
        int before = footprint(c.compCd());
        assertThat(demo.seed(c.compCd(), c)).isZero();
        assertThat(footprint(c.compCd())).isEqualTo(before);
    }

    @Test
    void 지난_점검_완료_시각은_미래를_받지_않는다() {
        LoginUser u = company("CDD", true);
        String c = u.compCd();
        accounts.exec(c, """
                insert into cms_chk_tmpl (comp_cd, tmpl_cd, tmpl_nm, target_kind, created_by, updated_by) values (?, 'T1', '점검', 'EQUIP', 't', 't')""", c);
        long run = as(u, () -> runner.call(c, () -> {
            plans.create(new PlanForm("P-ONE", "점검", "EQUIP", "PR-1200", "T1", "CHECK", "WEEK", 1, "FIXED",
                    LocalDate.now(SEOUL).toString(), 0, null, null, "Y", null, null, "CAL", null, null, null, null));
            generator.generate("t");
            return jdbc.queryForObject("select run_id from cms_chk_run", Long.class);
        }));
        Instant now = Instant.now();
        assertThatThrownBy(() -> as(u, () -> runner.call(c, () -> {
            runs.completeAt(run, 0, now.plus(1, ChronoUnit.HOURS).atZone(SEOUL).toOffsetDateTime());
            return null;
        }))).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(HbErrorCode.INVALID_VALUE));
        as(u, () -> runner.call(c, () -> {
            runs.completeAt(run, 0, now.minus(10, ChronoUnit.MINUTES).atZone(SEOUL).toOffsetDateTime());
            return null;
        }));
        assertThat(n(c, "select count(*) from cms_chk_run where status = 'DONE' and performed_at < now() - interval '5 minutes'")).isEqualTo(1);
    }

    private static <T> T as(LoginUser user, Supplier<T> work) {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(ctx);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
