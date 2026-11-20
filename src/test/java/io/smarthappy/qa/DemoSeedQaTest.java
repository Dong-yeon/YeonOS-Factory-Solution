package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.demo.DemoDataSeeder;
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

/**
 * QA — DEMO 시드 FG-B01 출하 대기(STAGING)·FG-A01 일반 칸(#638). 모듈 시험(DemoDataSeederTest)이 덮은 것(새 회사·기존 회사 다시 기동 멱등,
 * 사용자가 바꾼 속성 그대로, A사 실행이 B사를 안 건드림, 생산 입고 = FG-A01)은 다시 하지 않는다. 여기서는 ① 시드한 회사에서 FG-B01 을 출하 대기
 * 칸으로 출하 지시를 만들 수 있는지(일반 칸 FG-A01 은 거부), ② 예전 시드로 완제품을 FG-B01 에 받아 둔 회사가 다시 기동하면 그 재고가 출하 대기 칸에
 * 남는지(관찰 DS-1 → 결정: 재고가 있으면 속성을 넣지 않음). 분석 docs/qa/2026-10-28_grid-s2-f1-d2-calib.md 7·9장
 */
@AutoConfigureMockMvc
class DemoSeedQaTest extends PostgresTestBase {

    static final String PW = "Qa-demo-seed-83";

    @Autowired
    MockMvc mvc;

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

    DemoDataSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new DemoDataSeeder(runner, jdbc, numbering, clock, workOrders, lots, popWork, completion, receipts, policies);
    }

    RequestPostProcessor user(String comp, String... progs) {
        long u = accounts.newUser(comp, "qd1", PW);
        for (String p : progs) {
            accounts.grant(comp, u, "QA" + p.hashCode() % 1000, p, "SEARCH", "SAVE");
        }
        return accounts.as(comp, u, "qd1");
    }

    long node(String comp, String cd) {
        return accounts.query(comp, "select node_id from mst_equip_hier where node_cd = ?", Long.class, cd);
    }

    /** DemoDataSeederTest 의 전체 시드 순서 그대로(완제품 입고까지) */
    String seededComp() {
        String a = accounts.newComp("QE");
        seeder.seed(a);
        seeder.seedItems(a);
        seeder.seedBoms(a);
        accounts.addPlant(a, "P1", "1공장");
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
        seeder.seedMesFlow(a, "P1");
        return a;
    }

    int ship(String comp, RequestPostProcessor who, long stage, long orderLine) throws Exception {
        String cust = accounts.query(comp, """
                select c.cust_cd from sal_order_line l join sal_order o on o.order_id = l.order_id join mst_cust c on c.cust_id = o.cust_id
                 where l.line_id = ?""", String.class, orderLine);
        MvcResult r = mvc.perform(post("/api/wms/ships").with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantCd\":\"P1\",\"custCd\":\"" + cust + "\",\"stageNodeId\":" + stage + ",\"planDate\":\""
                        + java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(1) + "\",\"lines\":[{\"orderLineId\":" + orderLine + ",\"qty\":1}]}"))
                .andReturn();
        System.out.println("[QE] 출하 지시 stage=" + stage + " → " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString());
        return r.getResponse().getStatus();
    }

    /** ① 시드한 회사: FG-B01 을 출하 대기 칸으로 출하 지시가 만들어진다(시드 전에는 STAGING 칸이 없어 만들 수 없었다), 일반 칸 FG-A01 은 NOT_STAGING */
    @Test
    void 시드한_회사는_FG_B01_로_출하_지시를_만들고_FG_A01_은_거부() throws Exception {
        String a = seededComp();
        RequestPostProcessor who = user(a, "wms-ship");
        long line = accounts.query(a, """
                select l.line_id from sal_order_line l join mst_item i on i.item_id = l.item_id
                 where i.item_cd = '64310-DM100' order by l.line_id limit 1""", Long.class);
        assertThat(ship(a, who, node(a, "FG-A01"), line)).as("일반 칸은 출하 대기 칸이 아니다").isEqualTo(400);
        assertThat(ship(a, who, node(a, "FG-B01"), line)).isEqualTo(200);
        assertThat(accounts.query(a, """
                select string_agg(distinct h.node_cd, ',') from inv_stock s join mst_equip_hier h on h.node_id = s.node_id where s.qty > 0
                   and h.node_cd like 'FG-%'""", String.class)).as("완제품 재고는 일반 칸에만").isEqualTo("FG-A01");
    }

    /**
     * ② DS-1 결정(2026-10-28 사용자, WMS #643): 예전 시드(생산 입고 칸 = 이름순 첫 LOC → FG-A01 이 없던 때 FG-B01)로 완제품을 FG-B01 에 받아 둔
     * 회사가 다시 기동해도, FG-B01 에 재고(상태 무관 수량 > 0)가 있으면 출하 대기(STAGING) 속성을 넣지 않고 로그만 — 재고는 일반 칸 그대로 쓸 수 있다.
     * 관찰 때(1648faff)는 속성이 들어가 596 이 피킹 기록 없이 출하 대기 칸에 남았다. 재고를 옮겨 비우면 다음 기동에 속성이 들어간다
     */
    @Test
    void 결정_DS1_FG_B01_에_재고가_있으면_STAGING_을_넣지_않고_비우면_다음_기동에_넣는다() throws Exception {
        String b = accounts.newComp("QE");
        accounts.addPlant(b, "P1", "1공장");
        seeder.seedItems(b);
        seeder.seed(b);
        accounts.exec(b, "insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by) values (?, 'P1', 'WH', 'FG-WH', '완제품 창고', 't', 't')", b);
        accounts.exec(b, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                select ?, 'P1', node_id, 'LOC', 'FG-B01', '출하 대기 B01', 't', 't' from mst_equip_hier where node_cd = 'FG-WH'""", b);
        // 예전 시드처럼 완제품이 FG-B01 에(속성 없는 일반 칸이던 때) — 입고 API 로
        RequestPostProcessor who = user(b, "wms-rcv", "wms-move");
        String supplier = accounts.query(b, "select cust_cd from mst_cust where cust_type in ('PURCHASE', 'BOTH') order by cust_cd limit 1", String.class);
        long item = accounts.query(b, "select item_id from mst_item where item_cd = '64310-DM100'", Long.class);
        MvcResult rcv = mvc.perform(post("/api/wms/rcv").with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantCd\":\"P1\",\"nodeId\":" + node(b, "FG-B01") + ",\"itemId\":" + item + ",\"qty\":596,\"stockStatus\":\"AVAILABLE\","
                        + "\"supplierCd\":\"" + supplier + "\",\"clientReqId\":\"qe-old-" + System.nanoTime() + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String lotNo = JsonPath.read(rcv.getResponse().getContentAsString(), "$.data.lotNo");
        assertThat(seeder.seedFgLocs(b, "P1")).as("FG-A01 만 추가 — 재고 있는 FG-B01 은 속성을 넣지 않음").isEqualTo(1);
        assertThat(accounts.query(b, """
                select count(*) from wms_loc_attr a join mst_equip_hier h on h.node_id = a.node_id where h.node_cd = 'FG-B01'""", Integer.class)).isZero();
        assertThat(accounts.query(b, """
                select h.node_cd || ':' || coalesce(a.loc_usage, '-') || ':' || trim_scale(sum(s.qty)) from inv_stock s join mst_equip_hier h on h.node_id = s.node_id
                  left join wms_loc_attr a on a.node_id = h.node_id where s.stock_status = 'AVAILABLE' and s.qty > 0 group by h.node_cd, a.loc_usage""", String.class))
                .as("완제품 596 은 일반 칸(속성 없음) FG-B01 그대로").isEqualTo("FG-B01:-:596");
        assertThat(seeder.seedFgLocs(b, "P1")).as("다시 기동해도 재고가 있으면 그대로").isZero();
        // 재고를 FG-A01 로 옮겨 비우면 다음 기동에 STAGING
        mvc.perform(post("/api/wms/move/move").with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantCd\":\"P1\",\"itemId\":" + item + ",\"fromNodeId\":" + node(b, "FG-B01") + ",\"fromStatus\":\"AVAILABLE\",\"toNodeId\":"
                        + node(b, "FG-A01") + ",\"toStatus\":\"AVAILABLE\",\"qty\":596" + (lotNo == null ? "" : ",\"lotNo\":\"" + lotNo + "\"") + "}"))
                .andExpect(status().isOk());
        assertThat(seeder.seedFgLocs(b, "P1")).as("비운 뒤 기동 — FG-B01 STAGING").isEqualTo(1);
        assertThat(accounts.query(b, """
                select a.loc_usage from wms_loc_attr a join mst_equip_hier h on h.node_id = a.node_id where h.node_cd = 'FG-B01'""", String.class))
                .isEqualTo("STAGING");
        assertThat(accounts.query(b, "select count(*) from wms_ship_pick", Integer.class)).isZero();
    }
}
