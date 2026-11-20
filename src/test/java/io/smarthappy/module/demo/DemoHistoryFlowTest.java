package io.smarthappy.module.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.smarthappy.module.cms.CmsDemoFlow;
import io.smarthappy.module.cms.KpiService;
import io.smarthappy.module.cms.KpiService.KpiLine;
import io.smarthappy.module.cms.KpiService.KpiReport;
import io.smarthappy.module.pop.PopWorkService;
import io.smarthappy.module.production.CompletionService;
import io.smarthappy.module.production.LotService;
import io.smarthappy.module.production.MesDemoFlow;
import io.smarthappy.module.production.ReceiptDtos.ReceiveForm;
import io.smarthappy.module.production.ReceiptService;
import io.smarthappy.module.production.WoService;
import io.smarthappy.module.qms.InspDtos;
import io.smarthappy.module.qms.InspService;
import io.smarthappy.module.qms.NcrDtos;
import io.smarthappy.module.qms.NcrService;
import io.smarthappy.module.qms.QmsDemoFlow;
import io.smarthappy.module.wms.WmsDemoFlow;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 데모 이력 시더 MES·QMS·WMS (docs/25 4.53·docs/29_P3 46장·docs/30 12.20) — 공통 데모 기준정보 위에 CMS → MES → QMS → WMS 순서로 넣었을 때:
 * 화면·현황판이 쓸 모양(KPI 성능·품질·OEE, 불량 파레토, SPC 한계·위반, 부적합·8D·COPQ, 재고 연령 구간·출하·반품·점검·실사), 원장 두 시각
 * (발생 = 과거, 기록 = 지금)·entry_method, 멱등(다시 돌리면 0·흔적 그대로), A사 시드가 B사에 안 섞임, 기준정보 없는 회사는 아무것도 안 함,
 * 시더 전용 과거 시각 길의 미래 거부.
 */
class DemoHistoryFlowTest extends PostgresTestBase {

    private static final String PW = "Birch-gauge-73";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

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
    CmsDemoFlow cms;
    @Autowired
    MesDemoFlow mes;
    @Autowired
    QmsDemoFlow qms;
    @Autowired
    WmsDemoFlow wms;
    @Autowired
    KpiService kpi;
    @Autowired
    InspService insps;
    @Autowired
    NcrService ncrs;

    @Autowired
    org.springframework.beans.factory.config.AutowireCapableBeanFactory factory;

    DemoDataSeeder seeder;
    io.smarthappy.module.qms.QmsDemoSeeder processQms;
    private ch.qos.logback.classic.Level logLevel;

    @BeforeEach
    void setUp() {
        seeder = new DemoDataSeeder(runner, jdbc, numbering, clock, workOrders, lots, popWork, completion, receipts, policies);
        processQms = factory.createBean(io.smarthappy.module.qms.QmsDemoSeeder.class);
        // 시드 한 번이 SQL 디버그 로그 40MB 를 넘는다(시험 프로필 io.smarthappy DEBUG) — 전체 check 의 시험 JVM 힙(1g)을 누르지 않게 이 시험 동안만 INFO.
        // 속성으로 바꾸면 Spring 컨텍스트가 하나 더 생긴다(캐시 16개 제한)
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("io.smarthappy");
        logLevel = root.getLevel();
        root.setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @org.junit.jupiter.api.AfterEach
    void restoreLog() {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("io.smarthappy")).setLevel(logLevel);
    }

    /** 공통 데모 기준정보(DemoDataSeeder.run 과 같은 순서) + 관리자. withMaster = false 면 회사·공장·관리자만 */
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
            seeder.seedQuality(c);
            seeder.seedExt(c);
            seeder.seedClassProps(c);
            seeder.seedQms(c);
            seeder.seedQmsGauge(c);
            seeder.seedWorkforce(c, "P1");
            seeder.seedSales(c);
            seeder.seedWorkOrders(c, "P1");
            seeder.seedLots(c);
            seeder.seedMesFlow(c, "P1");
        }
        return new LoginUser(userId, c, "admin1", "admin1", "ko", false);
    }

    private int n(String comp, String sql, Object... args) {
        Integer v = accounts.query(comp, sql, Integer.class, args);
        return v == null ? 0 : v;
    }

    /** MES·QMS·WMS 데모 이력이 남기는 흔적 합 */
    private int footprint(String comp) {
        return n(comp, """
                select (select count(*) from prd_wo where remark = ?) + (select count(*) from prd_result) + (select count(*) from prd_param_actual)
                     + (select count(*) from prd_receipt) + (select count(*) from qms_insp) + (select count(*) from qms_ncr)
                     + (select count(*) from qms_capa) + (select count(*) from qms_copq_cost) + (select count(*) from qms_spc_limit)
                     + (select count(*) from inv_trx) + (select count(*) from wms_ship) + (select count(*) from wms_cust_rtn)
                     + (select count(*) from wms_loc_check) + (select count(*) from wms_count)""", MesDemoFlow.REMARK);
    }

    @Test
    void CMS_MES_QMS_WMS_순서로_넣으면_화면이_쓸_모양이_생기고_멱등이며_B사에_안_섞인다() {
        LoginUser a = company("DHA", true);
        LoginUser b = company("DHB", true);
        String ca = a.compCd();
        int before0 = footprint(b.compCd());
        Instant before = Instant.now().minusSeconds(1);

        long t0 = System.nanoTime();
        assertThat(cms.seed(ca, a)).isGreaterThan(20);
        long t1 = System.nanoTime();
        int wos = mes.seed(ca, a);
        assertThat(wos).isGreaterThan(110);
        long t2 = System.nanoTime();
        // QMS 공정검사 시더(#808, local 빈) 먼저 — DemoSeedOrder.QMS < QMS_HISTORY 와 같은 순서
        org.springframework.test.util.ReflectionTestUtils.setField(processQms, "compCd", ca);
        assertThat(processQms.seed(ca, a)).isEqualTo(30);
        assertThat(qms.seed(ca, a)).isEqualTo(30);
        long t3 = System.nanoTime();
        assertThat(wms.seed(ca, a)).isGreaterThan(12);
        long t4 = System.nanoTime();
        System.out.printf("[DH] 시간(초) CMS %d MES %d QMS %d WMS %d / 지시 %d 실적 %d 파라미터 %d(NG %d) 입고 %d%n", (t1 - t0) / 1_000_000_000L,
                (t2 - t1) / 1_000_000_000L, (t3 - t2) / 1_000_000_000L, (t4 - t3) / 1_000_000_000L, wos,
                n(ca, "select count(*) from prd_result"), n(ca, "select count(*) from prd_param_actual"),
                n(ca, "select count(*) from prd_param_actual where judge = 'NG'"), n(ca, "select count(*) from prd_receipt"));

        // ---------------- MES: 지시·실적·파라미터 — 발생 = 지난 90일, 기록 = 지금, 실적·파라미터는 이관(IMPORT)
        assertThat(n(ca, "select count(*) from prd_wo where remark = ?", MesDemoFlow.REMARK)).isEqualTo(wos);
        assertThat(n(ca, "select count(*) from prd_wo where remark = ? and status = 'CLOSED'", MesDemoFlow.REMARK)).isGreaterThan(100);
        assertThat(n(ca, "select count(*) from prd_wo where remark = ? and status = 'DONE'", MesDemoFlow.REMARK)).isBetween(6, 12);
        String mine = "r.wo_id in (select wo_id from prd_wo where remark = '" + MesDemoFlow.REMARK + "')";
        assertThat(n(ca, "select count(*) from prd_result r where " + mine)).isGreaterThan(600);
        assertThat(n(ca, "select count(*) from prd_result r where " + mine + " and (r.entry_method <> 'IMPORT' or r.occurred_at > now()"
                + " or r.occurred_at < now() - interval '91 days' or r.recorded_at < ?)", java.sql.Timestamp.from(before))).isZero();
        assertThat(n(ca, "select count(*) from prd_wo_proc_event r where " + mine + " and (r.occurred_at > now() or r.recorded_at < ?)",
                java.sql.Timestamp.from(before))).isZero();
        assertThat(n(ca, "select count(*) from prd_param_actual where entry_method = 'IMPORT'")).isGreaterThan(300);
        assertThat(n(ca, "select count(*) from prd_param_actual where judge = 'NG'")).isPositive();
        // 근무조 실적 둘(주간·야간)
        assertThat(n(ca, "select count(distinct r.shift_cd) from prd_result r where " + mine)).isEqualTo(2);
        // 불량 파레토 1위 = 스폿 누락
        String top = accounts.query(ca, """
                select q.defect_cd from prd_result_defect d join qms_defect q on q.defect_id = d.defect_id
                 group by q.defect_cd order by sum(d.qty) desc limit 1""", String.class);
        assertThat(top).isEqualTo("SPOT-MISS");
        // 완제품 입고 = 그날 시각(재고 원장 발생 과거)
        assertThat(n(ca, "select count(*) from prd_receipt where occurred_at < now() - interval '12 hours'")).isGreaterThan(100);
        assertThat(n(ca, "select count(*) from inv_trx where trx_type = 'RCV_PRD' and occurred_at < now() - interval '30 days'")).isGreaterThan(50);

        // CMS KPI: 데모 설비 3대에 성능·품질·OEE, CO2-201 은 실적이 없어 성능 빈칸(D21)
        LocalDate today = LocalDate.now(SEOUL);
        KpiReport r = runner.call(ca, () -> kpi.report("P1", today.minusDays(89).toString(), today.toString(), null));
        for (KpiLine l : r.equips()) {
            System.out.printf("[DH] KPI %s 가용 %s 성능 %s 품질 %s OEE %s%n", l.code(), l.kpi().availability(), l.kpi().performance(),
                    l.kpi().quality(), l.kpi().oee());
            if (List.of("PR-1200", "SPOT-101", "SPOT-102").contains(l.code())) {
                assertThat(l.kpi().performance()).as(l.code()).isNotNull().isBetween(new BigDecimal("0.6"), new BigDecimal("1.05"));
                assertThat(l.kpi().quality()).as(l.code()).isNotNull().isBetween(new BigDecimal("0.95"), new BigDecimal("0.9999"));
                assertThat(l.kpi().oee()).as(l.code()).isNotNull().isLessThan(l.kpi().availability());
            } else if ("CO2-201".equals(l.code())) {
                assertThat(l.kpi().performance()).isNull();
            }
        }

        // ---------------- QMS: 최종검사 30(NG 3), 고정 한계 1, 위반 이벤트, 부적합 18 + 고객 반품 1, 8D 3, COPQ 수기 비용
        assertThat(n(ca, "select count(*) from qms_insp where insp_type = 'FINAL' and status = 'JUDGED'")).isEqualTo(30);
        assertThat(n(ca, "select count(*) from qms_insp where insp_type = 'FINAL' and result = 'NG'")).isEqualTo(3);
        assertThat(n(ca, "select count(*) from qms_insp where insp_type = 'FINAL' and (occurred_at > now() - interval '1 day' or occurred_at < now() - interval '92 days')"))
                .isZero();
        assertThat(n(ca, "select count(*) from qms_insp_meas m join qms_insp_line l on l.insp_line_id = m.insp_line_id join qms_insp i on i.insp_id = l.insp_id where i.insp_type = 'FINAL' and m.occurred_at > now() - interval '1 day'")).isZero();
        assertThat(n(ca, "select count(*) from qms_spc_limit")).isEqualTo(2);   // 공정검사 시더 1 + 최종검사 1
        assertThat(n(ca, "select count(*) from qms_spc_event")).isGreaterThanOrEqualTo(2);
        assertThat(n(ca, "select count(*) from qms_spc_event_act where act_type = 'ACK'")).isEqualTo(1);
        assertThat(n(ca, "select count(*) from qms_ncr where src_type is null")).isEqualTo(18);
        assertThat(n(ca, "select count(*) from qms_ncr where src_type is null and status = 'CLOSED'")).isEqualTo(13);
        assertThat(n(ca, "select count(*) from qms_ncr where status = 'CANCELLED'")).isEqualTo(1);
        assertThat(n(ca, "select count(*) from qms_ncr where src_type is null and status = 'OPEN'")).isEqualTo(4);
        assertThat(n(ca, "select count(*) from qms_ncr where src_type is null and occurred_at > now() - interval '1 day'")).isZero();
        assertThat(n(ca, "select count(*) from qms_ncr where source = 'CLAIM'")).isEqualTo(1);
        assertThat(n(ca, "select count(*) from qms_capa")).isEqualTo(3);
        assertThat(n(ca, "select count(*) from qms_capa where occurred_on < " + TestAccounts.COMP_TODAY)).isEqualTo(3);
        assertThat(n(ca, "select count(distinct cost_month) from qms_copq_cost")).isEqualTo(4);

        // ---------------- WMS: 자재 입고 9(연령 구간 넷 모두), 이동·상태 변경, 출하 셋, 반품, 정위치 점검, 실사 조정
        assertThat(n(ca, "select count(*) from inv_trx where trx_type = 'RCV_MAT' and recorded_at >= ?", java.sql.Timestamp.from(before)))
                .isEqualTo(9);
        List<Map<String, Object>> age = runner.call(ca, () -> jdbc.queryForList("""
                select case when a <= 30 then 'A' when a <= 90 then 'B' when a <= 180 then 'C' else 'D' end as bucket, count(*) as cnt
                  from (select (""" + TestAccounts.COMP_TODAY + """
                 - (m.occurred_at at time zone 'Asia/Seoul')::date) as a
                          from inv_stock s join lot_master m on m.lot_id = s.lot_id where s.qty > 0) x group by 1 order by 1"""));
        assertThat(age).extracting(m -> m.get("bucket")).containsExactly("A", "B", "C", "D");
        assertThat(n(ca, "select count(*) from inv_trx where trx_type in ('MOVE_OUT', 'MOVE_IN') and occurred_at < now() - interval '50 days'"))
                .isEqualTo(4);
        assertThat(n(ca, "select count(*) from inv_stock where stock_status in ('QC', 'BLOCKED') and qty > 0")).isGreaterThanOrEqualTo(2);
        assertThat(runner.call(ca, () -> jdbc.queryForList("select status from wms_ship order by ship_id", String.class)))
                .containsExactly("SHIPPED", "PICKING", "DRAFT");
        assertThat(n(ca, "select count(*) from wms_cust_rtn where status = 'RECEIVED'")).isEqualTo(1);
        assertThat(n(ca, "select count(*) from wms_loc_check where status = 'DONE'")).isEqualTo(1);
        assertThat(n(ca, "select count(*) from wms_loc_check_line where result = 'OK'")).isEqualTo(2);
        assertThat(n(ca, "select count(*) from wms_loc_check_line where result = 'MISPLACED'")).isEqualTo(1);
        assertThat(n(ca, "select count(*) from wms_loc_check_line where result = 'MISSING'")).isPositive();
        assertThat(n(ca, "select count(*) from wms_count where status = 'POSTED'")).isEqualTo(1);
        assertThat(n(ca, "select count(*) from inv_trx where trx_type = 'COUNT_ADJ'")).isEqualTo(1);

        // ---------------- 멱등: 다시 돌리면 모두 0, 흔적 그대로
        int fa = footprint(ca);
        assertThat(mes.seed(ca, a)).isZero();
        assertThat(qms.seed(ca, a)).isZero();
        assertThat(wms.seed(ca, a)).isZero();
        assertThat(footprint(ca)).isEqualTo(fa);

        // ---------------- A사로 B사: 같은 기준정보 코드여도 B 에는 아무것도 늘지 않고, A 의 id 를 B 범위로 읽으면 0
        assertThat(footprint(b.compCd())).isEqualTo(before0);
        Long[] aWos = runner.call(ca, () -> jdbc.queryForList("select wo_id from prd_wo where remark = ?", Long.class, MesDemoFlow.REMARK)
                .toArray(Long[]::new));
        Long[] aNcr = runner.call(ca, () -> jdbc.queryForList("select ncr_id from qms_ncr", Long.class).toArray(Long[]::new));
        assertThat(n(b.compCd(), "select count(*) from prd_wo where wo_id = any(?)", (Object) aWos)).isZero();
        assertThat(n(b.compCd(), "select count(*) from qms_ncr where ncr_id = any(?)", (Object) aNcr)).isZero();
    }

    @Test
    void 기준정보가_없으면_아무것도_넣지_않는다() {
        LoginUser c = company("DHC", false);
        int before = footprint(c.compCd());
        assertThat(mes.seed(c.compCd(), c)).isZero();
        assertThat(qms.seed(c.compCd(), c)).isZero();
        assertThat(wms.seed(c.compCd(), c)).isZero();
        assertThat(footprint(c.compCd())).isEqualTo(before);
    }

    @Test
    void 시더_전용_과거_시각_길은_미래를_받지_않는다() {
        OffsetDateTime later = OffsetDateTime.now().plusHours(1);
        assertThatThrownBy(() -> insps.createAt(new InspDtos.CreateForm("X", "FINAL", null, 5L, null), later))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(HbErrorCode.INVALID_VALUE));
        assertThatThrownBy(() -> insps.measureAt(1L, new InspDtos.MeasForm(1L, List.of()), later))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(HbErrorCode.INVALID_VALUE));
        assertThatThrownBy(() -> ncrs.createAt(new NcrDtos.CreateForm("X", "OTHER", null, 1L, BigDecimal.ONE, "x"), later))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(HbErrorCode.INVALID_VALUE));
        assertThatThrownBy(() -> receipts.receiveAt(new ReceiveForm("abcdefgh-1", 1L, 1L, 1L, "1"), later.toInstant(), "t"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(HbErrorCode.INVALID_VALUE));
        assertThatThrownBy(() -> popWork.eventAt(null, null, 1L, "START", null, later.toInstant()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(HbErrorCode.INVALID_VALUE));
    }
}
