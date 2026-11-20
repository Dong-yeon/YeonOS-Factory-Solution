package io.smarthappy.module.master.cust;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.smarthappy.module.demo.DemoDataSeeder;
import io.smarthappy.module.wms.inv.InvDtos.TrxReq;
import io.smarthappy.module.wms.inv.InventoryService;
import io.smarthappy.module.pop.PopWorkService;
import io.smarthappy.module.production.CompletionService;
import io.smarthappy.module.production.LotService;
import io.smarthappy.module.production.ReceiptService;
import io.smarthappy.module.production.WoService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/** 로컬 데모 데이터 — local 프로필 빈이라 직접 만들어 부른다. 사업자번호 검증식을 보려고 cust 패키지에 둔다. */
class DemoDataSeederTest extends PostgresTestBase {

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
    InventoryService inventory;

    DemoDataSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new DemoDataSeeder(runner, jdbc, numbering, clock, workOrders, lots, popWork, completion, receipts, policies);
    }

    @Test
    void 빈_회사에_거래처와_주담당을_넣고_다시_돌려도_늘지_않는다() {
        String comp = accounts.newComp("DM");
        int n = seeder.seed(comp);
        assertThat(n).isGreaterThanOrEqualTo(20);
        assertThat(accounts.query(comp, "select count(*) from mst_cust", Integer.class)).isEqualTo(n);
        // 거래처마다 주 담당 정확히 1명
        assertThat(accounts.query(comp, """
                select count(*) from mst_cust c
                 where (select count(*) from mst_cust_contact t where t.cust_id = c.cust_id and t.main_yn = 'Y') <> 1""",
                Integer.class)).isZero();
        assertThat(seeder.seed(comp)).isZero();
        assertThat(accounts.query(comp, "select count(*) from mst_cust", Integer.class)).isEqualTo(n);
    }

    @Test
    void 품목도_빈_회사에만_넣고_B사에는_없다() {
        String a = accounts.newComp("DM");
        String b = accounts.newComp("DM");
        int n = seeder.seedItems(a);
        assertThat(n).isGreaterThanOrEqualTo(10);
        assertThat(seeder.seedItems(a)).isZero();
        assertThat(accounts.query(a, "select count(*) from mst_item", Integer.class)).isEqualTo(n);
        assertThat(accounts.query(b, "select count(*) from mst_item", Integer.class)).isZero();
        // 거래처가 이미 있어도 품목은 따로 판단(기존 로컬 DB 에도 품목이 들어간다)
        assertThat(seeder.seed(a)).isGreaterThan(0);
        assertThat(seeder.seedItems(b)).isEqualTo(n);
        assertThat(seeder.seedItems("NOCOMP")).isZero();
    }

    @Test
    void BOM_은_데모_품목이_있는_회사에만_한번_트리거를_지나_들어간다() {
        String a = accounts.newComp("DM");
        assertThat(seeder.seedBoms(a)).isZero();                 // 품목 없으면 안 넣음
        seeder.seedItems(a);
        int n = seeder.seedBoms(a);
        assertThat(n).isGreaterThanOrEqualTo(5);
        assertThat(seeder.seedBoms(a)).isZero();
        assertThat(accounts.query(a, "select count(*) from mst_bom where status = 'RELEASED'", Integer.class))
                .isEqualTo(n - 1);                                // 하나는 작성 중으로 남겨 화면에서 편집해 볼 수 있게
        assertThat(accounts.query(a, "select count(*) from mst_unit_conv", Integer.class)).isEqualTo(1);
        // 행 단위는 모두 자품목 기본 단위 — 확정 검사(NO_CONV)에 걸리지 않는다
        assertThat(accounts.query(a, """
                select count(*) from mst_bom_line l join mst_item i on i.item_id = l.item_id where l.unit_cd <> i.unit_cd""",
                Integer.class)).isZero();
    }

    @Test
    void 장비_계층은_공장이_있고_비었을_때만_트리거를_지나_들어간다() {
        String a = accounts.newComp("DM");
        assertThat(seeder.seedEquipHier(a, "P1")).isZero();                // 공장 없음
        accounts.addPlant(a, "P1", "1공장");
        int n = seeder.seedEquipHier(a, "P1");
        assertThat(n).isGreaterThanOrEqualTo(10);
        assertThat(seeder.seedEquipHier(a, "P1")).isZero();
        assertThat(accounts.query(a, "select count(*) from mst_equip_hier where parent_id is null", Integer.class)).isEqualTo(4);
    }

    @Test
    void 완제품_칸은_FG_B01_출하대기_FG_A01_일반칸이고_다시_기동해도_한번만_사용자가_바꾼_속성은_그대로() {
        String a = accounts.newComp("DM");
        String b = accounts.newComp("DM");
        assertThat(seeder.seedFgLocs(a, "P1")).isZero();                   // 공장·계층 없음
        accounts.addPlant(a, "P1", "1공장");
        accounts.addPlant(b, "P1", "1공장");
        seeder.seedEquipHier(a, "P1");                                     // 새 회사: FG-A01 은 계층에 들어 있다
        assertThat(seeder.seedFgLocs(a, "P1")).isEqualTo(1);               // FG-B01 속성만
        assertThat(seeder.seedFgLocs(a, "P1")).isZero();
        assertThat(accounts.query(a, """
                select string_agg(h.node_cd || ':' || coalesce(a.loc_usage, '-'), ',' order by h.node_cd)
                  from mst_equip_hier h left join wms_loc_attr a on a.node_id = h.node_id
                 where h.node_cd like 'FG-%'""", String.class)).isEqualTo("FG-A01:-,FG-B01:STAGING,FG-WH:-");

        // 계층을 이 변경 전에 넣은 회사(FG-A01 없음, FG-B01 속성 없음) — 다시 기동하면 둘 다. A사 실행은 B사를 건드리지 않는다
        accounts.exec(b, """
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', 'WH', 'FG-WH', '완제품 창고', 't', 't')""", b);
        accounts.exec(b, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                select ?, 'P1', node_id, 'LOC', 'FG-B01', '출하 대기 B01', 't', 't' from mst_equip_hier where node_cd = 'FG-WH'""", b);
        assertThat(seeder.seedFgLocs(a, "P1")).isZero();
        assertThat(accounts.query(b, "select count(*) from wms_loc_attr", Integer.class)).isZero();
        assertThat(seeder.seedFgLocs(b, "P1")).isEqualTo(2);
        assertThat(seeder.seedFgLocs(b, "P1")).isZero();
        assertThat(accounts.query(b, """
                select string_agg(h.node_cd || ':' || coalesce(a.loc_usage, '-') || ':' || coalesce(p.node_cd, '-'), ',' order by h.node_cd)
                  from mst_equip_hier h left join wms_loc_attr a on a.node_id = h.node_id
                  left join mst_equip_hier p on p.node_id = h.parent_id where h.node_type = 'LOC'""", String.class))
                .isEqualTo("FG-A01:-:FG-WH,FG-B01:STAGING:FG-WH");

        // 사용자가 FG-B01 을 다른 용도로 바꿔 두었으면 그대로 — 속성 행이 있으면 건너뛴다
        String c = accounts.newComp("DM");
        accounts.addPlant(c, "P1", "1공장");
        seeder.seedEquipHier(c, "P1");
        accounts.exec(c, """
                insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by)
                select node_id, ?, 'STORAGE', 't', 't' from mst_equip_hier where node_cd = 'FG-B01'""", c);
        assertThat(seeder.seedFgLocs(c, "P1")).isZero();
        assertThat(accounts.query(c, "select string_agg(loc_usage, ',') from wms_loc_attr", String.class)).isEqualTo("STORAGE");
    }

    /** DS-1: FG-B01 에 재고가 있으면(상태 무관) 출하 대기로 바꾸지 않는다 — 시드는 사용자 데이터를 건드리지 않는다. 비우면 다음 기동에 넣는다 */
    @Test
    void FG_B01_에_재고가_있으면_STAGING_을_넣지_않고_비운_뒤_다시_부르면_넣는다() {
        String d = accounts.newComp("DM");
        accounts.addPlant(d, "P1", "1공장");
        seeder.seedEquipHier(d, "P1");
        long b01 = accounts.query(d, "select node_id from mst_equip_hier where node_cd = 'FG-B01'", Long.class);
        long item = accounts.query(d, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'X', 'X', 'FG', 'MAKE', 'EA', 'N', 't', 't') returning item_id""", Long.class, d);
        adjust(d, "ADJ_IN", item, b01, "5");
        assertThat(seeder.seedFgLocs(d, "P1")).isZero();
        assertThat(seeder.seedFgLocs(d, "P1")).isZero();
        assertThat(accounts.query(d, "select count(*) from wms_loc_attr", Integer.class)).isZero();

        adjust(d, "ADJ_OUT", item, b01, "-5");                              // 비운 칸(수량 0 행은 남는다)
        assertThat(seeder.seedFgLocs(d, "P1")).isEqualTo(1);
        assertThat(accounts.query(d, "select loc_usage from wms_loc_attr where node_id = ?", String.class, b01)).isEqualTo("STAGING");
    }

    void adjust(String comp, String type, long item, long node, String qty) {
        runner.callAs(comp, "tester", () -> inventory.post(List.of(new TrxReq(type, item, node, "AVAILABLE", new BigDecimal(qty), null,
                "MANUAL", null, "FOUND", null, OffsetDateTime.now(), "KEY", null, null)), null));
    }

    @Test
    void 라우팅은_공정이_없고_계층_품목이_있을때만_트리거를_지나_들어간다() {
        String a = accounts.newComp("DM");
        assertThat(seeder.seedRoutings(a, "P1")).isZero();                 // 계층·품목 없음
        seeder.seedItems(a);
        accounts.addPlant(a, "P1", "1공장");
        seeder.seedEquipHier(a, "P1");
        assertThat(seeder.seedRoutings(a, "P1")).isEqualTo(3);
        assertThat(seeder.seedRoutings(a, "P1")).isZero();
        assertThat(accounts.query(a, "select count(*) from mst_routing where status = 'RELEASED'", Integer.class)).isEqualTo(2);
        assertThat(accounts.query(a, "select count(*) from mst_routing_param", Integer.class)).isEqualTo(6);
        // 외주 단계만 작업장·사이클타임 없음
        assertThat(accounts.query(a, """
                select count(*) from mst_routing_step s join mst_proc p on p.proc_id = s.proc_id
                 where (s.node_id is null or s.std_ct_sec is null) and p.outsrc_yn = 'N'""", Integer.class)).isZero();
    }

    @Test
    void 특성_불량유형은_공정이_있을때만_한번_트리거를_지나_들어간다() {
        String a = accounts.newComp("DM");
        seeder.seedItems(a);
        assertThat(seeder.seedQuality(a)).isZero();                       // 공정 없음
        accounts.addPlant(a, "P1", "1공장");
        seeder.seedEquipHier(a, "P1");
        seeder.seedRoutings(a, "P1");
        assertThat(seeder.seedQuality(a)).isEqualTo(8);
        assertThat(seeder.seedQuality(a)).isZero();
        assertThat(accounts.query(a, "select count(*) from qms_char where special_cls = 'CC'", Integer.class)).isEqualTo(1);
        assertThat(accounts.query(a, "select count(distinct cat_cd) from qms_defect", Integer.class)).isGreaterThanOrEqualTo(5);
    }

    @Test
    void QMS_검사계획_3건_수입검사는_공정_없이_코일_클래스로_확정() {
        String a = accounts.newComp("DQ");
        seeder.seedItems(a);
        accounts.addPlant(a, "P1", "1공장");
        seeder.seedEquipHier(a, "P1");
        seeder.seedEquips(a, "P1");        // 설비 클래스(코일 클래스 시드 조건)
        seeder.seedRoutings(a, "P1");
        seeder.seedQuality(a);
        seeder.seedExt(a);
        seeder.seedClassProps(a);          // 코일 클래스
        assertThat(seeder.seedQms(a)).isEqualTo(3);
        assertThat(seeder.seedQms(a)).isZero();
        // WMS 입고 검사(B3)는 공정 없이 계획을 찾는다 — 수입검사 계획에 공정이 붙으면 걸리지 않는다
        assertThat(accounts.query(a, """
                select p.status || ':' || coalesce(p.proc_id::text, '-') || ':' || (select count(*) from qms_insp_plan_line l where l.insp_plan_id = p.insp_plan_id)
                  from qms_insp_plan p where p.insp_type = 'RECEIVE'""", String.class)).isEqualTo("RELEASED:-:2");
    }

    @Test
    void 클래스속성과_외부코드는_앞단계_데모가_있을때만_한번_트리거를_지나_들어간다() {
        String a = accounts.newComp("DM");
        seeder.seedItems(a);
        assertThat(seeder.seedClassProps(a)).isZero();                    // 설비 클래스 없음
        accounts.addPlant(a, "P1", "1공장");
        seeder.seedEquipHier(a, "P1");
        seeder.seedEquips(a, "P1");
        assertThat(seeder.seedClassProps(a)).isEqualTo(6);
        assertThat(seeder.seedClassProps(a)).isZero();
        assertThat(accounts.query(a, "select attrs ->> 'GRADE' from mst_item where item_cd = 'RM-SAPH440-2.3'", String.class))
                .isEqualTo("SAPH440");
        assertThat(accounts.query(a, "select (attrs ->> 'MAX_TON')::int from mst_equip where equip_cd = 'PR-1200'", Integer.class))
                .isEqualTo(1200);
        assertThat(seeder.seedExt(a)).isGreaterThan(0);
        assertThat(seeder.seedExt(a)).isZero();
    }

    @Test
    void 계측기_교정_기록은_데모_게이지가_있을때만_한번_트리거를_지나_들어간다() {
        String a = accounts.newComp("DG");
        assertThat(seeder.seedQmsGauge(a)).isZero();                              // 게이지 없음
        seeder.seedItems(a);
        accounts.addPlant(a, "P1", "1공장");
        seeder.seedEquipHier(a, "P1");
        seeder.seedEquips(a, "P1");
        assertThat(seeder.seedQmsGauge(a)).isEqualTo(2);
        assertThat(seeder.seedQmsGauge(a)).isZero();
        // 최근 교정일은 교정 트리거가 늦은 기록(300일 전)으로, 다음 교정일 65일 뒤 → 사용 가능
        assertThat(accounts.query(a, "select (" + TestAccounts.COMP_TODAY + " - last_calib_date) || ':' || gauge_class "
                + "from mst_phys_asset where asset_cd = 'GG-CAL-01'", String.class)).isEqualTo("300:CALIPER");
    }

    @Test
    void 수주_내시는_고객_품목이_있을때만_한번_채번과_트리거를_지나_들어간다() {
        String a = accounts.newComp("DS");
        assertThat(seeder.seedSales(a)).isZero();                                  // 고객·품목 없음
        seeder.seed(a);
        seeder.seedItems(a);
        assertThat(seeder.seedSales(a)).isEqualTo(3);
        assertThat(seeder.seedSales(a)).isZero();
        assertThat(accounts.query(a, "select string_agg(status, ',' order by order_id) from sal_order", String.class))
                .isEqualTo("OPEN,OPEN,CLOSED");
        assertThat(accounts.query(a, "select count(*) from sal_order where order_no ~ '^SO[0-9]{4}-000[1-3]$'", Integer.class))
                .isEqualTo(3);
        // 내시 2차: 1차와 비교하면 늘어남·줄어듦·사라짐(RH 5주차)·새로 생김(RH 6주차)
        assertThat(accounts.query(a, "select string_agg(rev_no || ':' || (select count(*) from sal_forecast_line l "
                + "where l.rev_id = r.rev_id), ',' order by rev_no) from sal_forecast_rev r", String.class)).isEqualTo("1:11,2:11");
        assertThat(accounts.query(a, "select count(*) from sal_forecast_line where extract(isodow from bucket_start) <> 1",
                Integer.class)).isZero();
    }

    @Test
    void 작업지시는_수주_라우팅이_있을때만_한번_발행_스냅샷까지() {
        String a = accounts.newComp("DO");
        assertThat(seeder.seedWorkOrders(a, "P1")).isZero();
        accounts.addPlant(a, "P1", "1공장");
        seeder.seed(a);
        seeder.seedItems(a);
        seeder.seedBoms(a);
        seeder.seedEquipHier(a, "P1");
        seeder.seedRoutings(a, "P1");
        seeder.seedSales(a);
        assertThat(seeder.seedWorkOrders(a, "P1")).isEqualTo(2);
        assertThat(seeder.seedWorkOrders(a, "P1")).isZero();
        assertThat(accounts.query(a, "select string_agg(status, ',' order by wo_id) from prd_wo", String.class))
                .isEqualTo("RELEASED,PLANNED");
        assertThat(accounts.query(a, "select count(*) from prd_wo_proc", Integer.class)).isEqualTo(4);
        assertThat(accounts.query(a, "select count(*) from prd_wo_bom", Integer.class)).isEqualTo(5);
        assertThat(accounts.query(a, "select count(*) from prd_wo where order_line_id is not null", Integer.class)).isEqualTo(1);
        assertThat(seeder.seedLots(a)).isEqualTo(3);
        assertThat(seeder.seedLots(a)).isZero();
        assertThat(accounts.query(a, "select count(*) from lot_genealogy where rel_type = 'CONSUME'", Integer.class)).isEqualTo(2);
        assertThat(accounts.query(a, "select trim_scale(hb_lot_balance(lot_id))::text from lot_master where supplier_lot_no = 'IB-2610-118'",
                String.class)).isEqualTo("8392");
    }

    @Test
    void MES_관통_흐름은_한번만_완료판정_완료_입고까지_서비스_길로_가고_ERP_발행이_남는다() {
        String a = accounts.newComp("DF");
        accounts.addPlant(a, "P1", "1공장");
        assertThat(seeder.seedMesFlow(a, "P1")).isZero();                       // 지시 없음
        seeder.seed(a);
        seeder.seedItems(a);
        seeder.seedBoms(a);
        seeder.seedEquipHier(a, "P1");
        seeder.seedFgLocs(a, "P1");
        seeder.seedEquips(a, "P1");
        seeder.seedRoutings(a, "P1");
        seeder.seedQuality(a);
        seeder.seedExt(a);
        seeder.seedWorkforce(a, "P1");
        seeder.seedSales(a);
        seeder.seedWorkOrders(a, "P1");
        seeder.seedLots(a);
        assertThat(seeder.seedMesFlow(a, "P1")).isEqualTo(2);
        assertThat(seeder.seedMesFlow(a, "P1")).isZero();                       // 완료된 지시가 있으면 다시 안 넣음
        assertThat(accounts.query(a, """
                select w.status || '/' || (w.done_at is not null) from prd_wo w join mst_item i on i.item_id = w.item_id
                 where i.item_cd = '64311-DM100'""", String.class)).isEqualTo("DONE/true");
        assertThat(accounts.query(a, "select count(*) from prd_wo_proc_close", Integer.class)).isEqualTo(2);
        assertThat(accounts.query(a, "select trim_scale(sum(qty))::text from prd_receipt", String.class)).isEqualTo("596");
        // 생산 입고는 일반 칸으로 — 출하 대기 칸 FG-B01(STAGING) 은 받지 않는다(docs/53 F1)
        assertThat(accounts.query(a, """
                select string_agg(distinct h.node_cd, ',') from prd_receipt r join mst_equip_hier h on h.node_id = r.node_id""",
                String.class)).isEqualTo("FG-A01");
        // 강판 LOT → 생산 LOT 계보(보고서 "어디서 왔나")
        assertThat(accounts.query(a, """
                select count(*) from lot_genealogy g join lot_master p on p.lot_id = g.parent_lot_id
                 where p.supplier_lot_no = 'DD-CL-2610-07' and g.rel_type = 'CONSUME'""", Integer.class)).isEqualTo(1);
        // 정책이 비어 있던 회사 → FAKE, 완료 1 + 입고 1 이 아웃박스에(ERP 품목 코드 매핑 있음)
        assertThat(accounts.query(a, "select policy_value from sys_policy where policy_key = 'ERP_SYSTEM'", String.class)).isEqualTo("FAKE");
        assertThat(accounts.query(a, """
                select string_agg(topic || ':' || (payload ->> 'erpItemCd' is not null), ',' order by outbox_id) from sys_outbox
                 where topic like 'ERP_%'""", String.class)).isEqualTo("ERP_WO_RESULT:true,ERP_RECEIPT:true");
    }

    @Test
    void 근무조_자격_작업자는_공정이_있을때만_한번_트리거를_지나_들어간다() {
        String a = accounts.newComp("DW");
        accounts.addPlant(a, "P1", "1공장");
        assertThat(seeder.seedWorkforce(a, "P1")).isZero();                        // 공정 없음
        accounts.exec(a, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'SPOT', '스폿', 'WELD', 't', 't'), (?, 'DRAW', '드로잉', 'PRESS', 't', 't')""", a, a);
        assertThat(seeder.seedWorkforce(a, "P1")).isEqualTo(6);
        assertThat(seeder.seedWorkforce(a, "P1")).isZero();
        assertThat(accounts.query(a, "select count(*) from mst_worker_qual", Integer.class)).isEqualTo(7);
        // 만료된 용접 자격(800일 전 취득 + 24개월)
        assertThat(accounts.query(a, """
                select count(*) from mst_worker_qual h join mst_worker w on w.worker_id = h.worker_id
                 where w.worker_cd = 'E1002' and h.expires_on < (now() at time zone 'Asia/Seoul')::date""", Integer.class)).isEqualTo(1);
    }

    @Test
    void 사업자번호는_검증식을_통과하고_발급되지_않는_000_대역이며_서로_다르다() {
        String comp = accounts.newComp("DM");
        seeder.seed(comp);
        List<String> nos = runner.call(comp, () -> jdbc.queryForList("select biz_no from mst_cust", String.class));
        assertThat(nos).allMatch(b -> b.startsWith("000") && CustService.bizNoChecksum(b)).doesNotHaveDuplicates();
        // 메일은 example.com 만 — 데모 데이터가 실제 주소로 나가지 않게
        assertThat(accounts.query(comp, """
                select count(*) from (select email from mst_cust union all select email from mst_cust_contact) e
                 where email not like '%@example.com'""", Integer.class)).isZero();
    }

    @Test
    void 사용자가_만든_거래처가_있으면_건드리지_않는다() {
        String comp = accounts.newComp("DM");
        accounts.exec(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'S001', '내가 만든 거래처', 'SALES', 't', 't')""", comp);
        assertThat(seeder.seed(comp)).isZero();
        assertThat(accounts.query(comp, "select cust_nm from mst_cust", String.class)).isEqualTo("내가 만든 거래처");
    }

    @Test
    void A사에_넣어도_B사에는_없고_없는_회사는_아무것도_하지_않는다() {
        String a = accounts.newComp("DM");
        String b = accounts.newComp("DM");
        seeder.seed(a);
        assertThat(accounts.query(b, "select count(*) from mst_cust", Integer.class)).isZero();
        assertThat(accounts.query(b, "select count(*) from mst_cust_contact", Integer.class)).isZero();
        assertThat(seeder.seed("NOCOMP")).isZero();
    }
}
