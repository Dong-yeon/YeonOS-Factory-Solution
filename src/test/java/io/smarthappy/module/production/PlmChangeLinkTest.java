package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.master.workforce.WorkerService;
import io.smarthappy.module.pop.PopAuthService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * PLM 연결 3c (docs/25 4.12, docs/39 12.3·13장) — 새 LOT 세 곳(5b 실적 확정·7a 재작업 처리·7b LOT 단위 재작업)이
 * 같은 트랜잭션에서 ChangeMarker 를 부르고, 투입·재작업 계보 change_id 에 표시 ECO 를 사본으로 적는다.
 * 실적은 실제 POP API, 처리·LOT 재작업은 사무 API, ECO 는 PLM API(작성 → 결재 → 적용)로 만든다.
 * 이상 사용: 첫 N 넘음, 역분개 거울, 직접 SQL(다른 회사·없는 ECO·다른 change_id 역분개), 잠금 순서(교착 없음), A사로 B사.
 */
@AutoConfigureMockMvc
class PlmChangeLinkTest extends PostgresTestBase {

    private static final String PW = "Birch-signal-58";
    private static final String WO = "/api/production/wos";
    private static final String ECO = "/api/plm/ecos";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    LotService lots;

    String comp;
    long adminId;
    long approverId;
    RequestPostProcessor admin;
    RequestPostProcessor approver;
    String today;
    long woId;
    long spot;
    String cookie;
    MockHttpSession s;
    String ip;
    int rmSeq;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PCL");
        ip = "10.82." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        adminId = accounts.newUser(comp, "admin1", PW);
        approverId = accounts.newUser(comp, "mgr1", PW);
        accounts.grant(comp, adminId, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, adminId, "ADM", "prd-wo", "SEARCH", "SAVE");
        accounts.grant(comp, adminId, "ADM", "prd-lot", "SEARCH", "SAVE");
        for (long u : new long[] {adminId, approverId}) {
            accounts.grant(comp, u, "ENG", "plm-eco", "SEARCH", "SAVE", "APPROVE");
        }
        admin = accounts.as(comp, adminId, "admin1");
        approver = accounts.as(comp, approverId, "mgr1");
        accounts.addPlant(comp, "P1", "1공장");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        long area = node(null, "AREA", "A1");
        long line1 = node(area, "LINE", "L1");
        long cell1 = node(line1, "CELL", "C1");
        accounts.exec(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'E1', '홍작업', 'P1', ?, 't', 't')""", comp, WorkerService.badgeHash(comp, "BADGE-0001"));
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', 'FG 품목', 'FG', 'MAKE', 'EA', 't', 't'), (?, 'RM', 'RM 품목', 'FG', 'MAKE', 'KG', 't', 't')""", comp, comp);
        accounts.exec(comp, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'SPOT', '스폿 용접', 'WELD', 't', 't')""", comp);
        long rid = accounts.query(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                returning routing_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'SPOT'), ?, 30, 't', 't')""", comp, rid, cell1);
        accounts.exec(comp, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        long bom = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, base_qty, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 1, 't', 't')
                returning bom_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, issue_method, created_by, updated_by)
                values (?, ?, 10, (select item_id from mst_item where item_cd = 'RM'), 1, 'KG', 'NORMAL', 'BACKFLUSH', 't', 't')""", comp, bom);
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", bom);
        accounts.exec(comp, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'D-PIT', '기공', 'WELD', 't', 't')""", comp);
        woId = id(mvc.perform(json(post(WO), "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"100\",\"planStart\":\"" + today + "\"}")
                .with(admin)));
        mvc.perform(json(post(WO + "/" + woId + "/release"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        spot = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 10", Long.class, woId);
        cookie = terminal("TAB-01");
        s = new MockHttpSession();
        mvc.perform(json(post("/api/pop/badge"), "{\"badge\":\"BADGE-0001\"}").cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s))
                .andExpect(status().isOk());
        pop(post("/api/pop/procs/" + spot + "/events/start"), "{\"clientReqId\":\"" + req() + "\",\"ack\":true}").andExpect(status().isOk());
    }

    // ---------- 도우미 ----------

    private long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd + " 노드");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private String terminal(String cd) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\"}").with(admin)).andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        MvcResult a = mvc.perform(json(post("/api/pop/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return set.substring(set.indexOf('=') + 1, set.indexOf(';'));
    }

    private ResultActions pop(MockHttpServletRequestBuilder b, String body) throws Exception {
        return mvc.perform(json(b, body).cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s));
    }

    private static String req() {
        return UUID.randomUUID().toString();
    }

    /** 공급 자재 LOT(RM) — 투입 대상 */
    private String rmLot(String c, int qty) {
        String no = "RM-" + (++rmSeq);
        accounts.exec(c, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', ?, unit_cd, now(), 't', 't' from mst_item where item_cd = 'RM'""", c, no, qty);
        return no;
    }

    private long lotId(String no) {
        return accounts.query(comp, "select lot_id from lot_master where lot_no = ?", Long.class, no);
    }

    /** POP 실적(SPOT) — 투입은 [자재 LOT 번호, 수량], 재작업은 불량 D-PIT. 실적 id */
    private long result(int good, int rework, String inputLot, int inputQty) throws Exception {
        String r = req();
        String defects = rework > 0 ? "{\"kind\":\"REWORK\",\"defectCd\":\"D-PIT\",\"qty\":\"" + rework + "\"}" : "";
        String inputs = inputLot == null ? "" : "{\"lotNo\":\"" + inputLot + "\",\"qty\":\"" + inputQty + "\"}";
        pop(post("/api/pop/procs/" + spot + "/results"), "{\"clientReqId\":\"" + r + "\",\"goodQty\":\"" + good
                + "\",\"reworkQty\":\"" + rework + "\",\"defects\":[" + defects + "],\"inputs\":[" + inputs + "]}").andExpect(status().isOk());
        return accounts.query(comp, "select result_id from prd_result where client_req_id = ?", Long.class, r);
    }

    private long resultLot(long resultId) {
        return accounts.query(comp, "select lot_id from prd_result where result_id = ?", Long.class, resultId);
    }

    private int ecoVer(long eco) {
        return accounts.query(comp, "select row_ver from plm_eco where eco_id = ?", Integer.class, eco);
    }

    /** 적용된 ECO — 대상 = 품목만(도면 변경 모양 → 유효일부터 첫 N LOT), 첫 N = markLots */
    private long releasedEco(int markLots, String... itemCds) throws Exception {
        long eco = id(mvc.perform(json(post(ECO), "{\"title\":\"변경\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today
                + "\",\"markLots\":" + markLots + "}").with(admin)));
        StringBuilder t = new StringBuilder();
        for (String cd : itemCds) {
            t.append(t.length() == 0 ? "" : ",").append("{\"rowKey\":\"i").append(cd).append("\",\"targetType\":\"ITEM\",\"itemCd\":\"")
                    .append(cd).append("\"}");
        }
        mvc.perform(json(put(ECO + "/" + eco + "/targets"), "{\"rowVer\":" + ecoVer(eco) + ",\"targets\":[" + t + "]}").with(admin))
                .andExpect(status().isOk());
        mvc.perform(json(post(ECO + "/" + eco + "/submit"), "{\"rowVer\":" + ecoVer(eco)
                + ",\"lines\":[{\"userId\":" + approverId + ",\"lineType\":\"APPROVE\"}]}").with(admin)).andExpect(status().isOk());
        long apr = accounts.query(comp, "select apr_id from plm_eco where eco_id = ?", Long.class, eco);
        mvc.perform(json(post("/api/platform/approvals/" + apr + "/approve"), "{}").with(approver)).andExpect(status().isOk());
        mvc.perform(json(post(ECO + "/" + eco + "/release"), "{\"rowVer\":" + ecoVer(eco) + "}").with(approver)).andExpect(status().isOk());
        return eco;
    }

    private Long changeOfConsume(long childLotId) {
        return accounts.query(comp, "select change_id from lot_genealogy where child_lot_id = ? and reversal_of_id is null", Long.class,
                childLotId);
    }

    private boolean marked(long eco, long lot) {
        return accounts.query(comp, "select count(*) from plm_eco_lot where eco_id = ? and lot_id = ?", Integer.class, eco, lot) == 1;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- 정상 ----------

    @Test
    void 새_LOT_세_곳이_표시하고_투입_재작업_계보에_사본을_적는다_첫_N_넘으면_비운다() throws Exception {
        long eco = releasedEco(4, "FG");
        String rm1 = rmLot(comp, 100);

        // 5b 투입 있는 실적 → 표시 + 투입 계보 change_id = ECO (재작업 2 는 뒤의 7a 처리 대상)
        long r1 = result(5, 2, rm1, 5);
        long l1 = resultLot(r1);
        assertThat(marked(eco, l1)).isTrue();
        assertThat(changeOfConsume(l1)).isEqualTo(eco);
        // 계보 조회 응답에도 사본이 보인다
        mvc.perform(get("/api/production/lots/" + lotId(rm1)).with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.children[0].changeId").value(eco));

        // 7a 재작업 처리 → 새 산출 LOT 표시(계보 부모 없음 — 표시는 plm_eco_lot 에만)
        MvcResult rw = mvc.perform(json(post(WO + "/procs/" + spot + "/rework"), "{\"clientReqId\":\"" + req()
                + "\",\"kind\":\"REWORK\",\"goodQty\":\"2\",\"scrapQty\":\"0\",\"reason\":\"재용접\"}").with(admin))
                .andExpect(status().isOk()).andReturn();
        long reworkId = ((Number) JsonPath.read(rw.getResponse().getContentAsString(), "$.data.reworkId")).longValue();
        long l2 = accounts.query(comp, "select lot_id from prd_rework where rework_id = ?", Long.class, reworkId);
        assertThat(marked(eco, l2)).isTrue();

        // 5b 투입 없는 실적 → 계보 행 없이 표시만(F2)
        long l3 = resultLot(result(3, 0, null, 0));
        assertThat(marked(eco, l3)).isTrue();
        assertThat(accounts.query(comp, "select count(*) from lot_genealogy where child_lot_id = ?", Integer.class, l3)).isZero();

        // 7b LOT 단위 재작업(지시 없음) → 네 번째 표시 + REWORK 계보 change_id
        long l4 = id(mvc.perform(json(post("/api/production/lots/" + l1 + "/rework"), "{\"kind\":\"REWORK\",\"qty\":\"1\",\"reason\":\"외관\"}")
                .with(admin)));
        assertThat(marked(eco, l4)).isTrue();
        assertThat(accounts.query(comp, "select rel_type || ':' || change_id from lot_genealogy where child_lot_id = ?", String.class, l4))
                .isEqualTo("REWORK:" + eco);

        // 첫 4 를 다 썼다 → 다음 투입 실적은 표시 없음·change_id NULL, 실적은 그대로
        long l5 = resultLot(result(4, 0, rm1, 4));
        assertThat(marked(eco, l5)).isFalse();
        assertThat(changeOfConsume(l5)).isNull();
        assertThat(accounts.query(comp, "select count(*) from plm_eco_lot where eco_id = ?", Integer.class, eco)).isEqualTo(4);
    }

    @Test
    void 실적_정정의_투입_역분개는_같은_change_id_를_싣는다() throws Exception {
        long eco = releasedEco(1, "FG");
        long r1 = result(5, 0, rmLot(comp, 50), 5);
        long l1 = resultLot(r1);
        assertThat(changeOfConsume(l1)).isEqualTo(eco);
        pop(post("/api/pop/results/" + r1 + "/correct"), "{\"reason\":\"수량 잘못\"}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select change_id from lot_genealogy where child_lot_id = ? and reversal_of_id is not null",
                Long.class, l1)).isEqualTo(eco);
        // 표시는 사실 기록 — 정정으로 LOT 이 보류돼도 남는다(docs/39 12.4)
        assertThat(marked(eco, l1)).isTrue();
    }

    // ---------- 이상 사용 ----------

    @Test
    void 원장_직접_SQL_다른_회사_ECO_없는_ECO_다른_change_id_역분개는_거부() throws Exception {
        long eco = releasedEco(1, "FG");
        long l1 = resultLot(result(5, 0, rmLot(comp, 50), 5));
        long gen = accounts.query(comp, "select gen_id from lot_genealogy where child_lot_id = ?", Long.class, l1);
        long rm = accounts.query(comp, "select parent_lot_id from lot_genealogy where gen_id = ?", Long.class, gen);

        // 다른 회사 ECO(B사 작성 중 ECO)
        String other = accounts.newComp("PCB");
        long otherUser = accounts.newUser(other, "eng1", PW);
        long otherEco = accounts.query(other, """
                insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, created_by, updated_by)
                values (?, 'ECO-B1', 'B 변경', 'QUALITY', (now() at time zone 'Asia/Seoul')::date, ?, 't', 't') returning eco_id""", Long.class, other, otherUser);
        String insert = """
                insert into lot_genealogy (comp_cd, parent_lot_id, child_lot_id, rel_type, qty, unit_cd, occurred_at, entry_method,
                                           recorded_by, change_id)
                values (?, ?, ?, 'CONSUME', 1, 'KG', now(), 'KEY', 't', ?)""";
        for (long bad : new long[] {otherEco, 999_999_999L}) {
            Throwable t = catchThrowable(() -> accounts.exec(comp, insert, comp, rm, l1, bad));
            assertThat(PgErrors.constraint(t)).contains("fk_lot_genealogy_ctx_comp");
        }

        // 역분개는 원래 행과 같은 change_id 만 — 비우거나 바꾸면 거울 검사
        String reversal = """
                insert into lot_genealogy (comp_cd, parent_lot_id, child_lot_id, rel_type, qty, unit_cd, occurred_at, entry_method,
                                           recorded_by, reason, reversal_of_id, change_id)
                values (?, ?, ?, 'CONSUME', -5, 'KG', now(), 'CORRECT', 't', '정정', ?, NULL)""";
        Throwable t = catchThrowable(() -> accounts.exec(comp, reversal, comp, rm, l1, gen));
        assertThat(PgErrors.constraint(t)).contains("ck_lot_genealogy_reversal");
        // 계보 change_id 는 원장 — 고칠 수 없다
        Throwable u = catchThrowable(() -> accounts.exec(comp, "update lot_genealogy set change_id = null where gen_id = ?", gen));
        assertThat(u).isNotNull();
        assertThat(accounts.query(comp, "select change_id from lot_genealogy where gen_id = ?", Long.class, gen)).isEqualTo(eco);
    }

    /**
     * 잠금 순서 MES 행 → ECO 행. 다른 트랜잭션이 자재 LOT 을 쥔 채 같은 ECO 에 표시하려 할 때 POP 실적이 그 자재 LOT 을 투입한다.
     * 실적이 ECO 를 먼저 잡고 자재 LOT 을 기다리면(옛 순서) 서로 기다려 교착(40P01) — 지금은 실적이 자재 LOT 에서 먼저 기다린다.
     */
    @Test
    void D_자재_LOT_을_쥔_트랜잭션과_동시_실적이_교착_없이_둘_다_끝난다() throws Exception {
        long eco = releasedEco(10, "FG", "RM");
        String rmNo = rmLot(comp, 100);
        long rm = lotId(rmNo);
        // 표시 대상이 될 RM LOT(LOT 단위 재작업 모양, 지시 없음)
        long rmRework = accounts.query(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, 'RM-RW', item_id, 'REWORK', 1, unit_cd, now(), 't', 't' from mst_item where item_cd = 'RM' returning lot_id""",
                Long.class, comp);
        CountDownLatch held = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Long> holder = pool.submit(() -> runner.call(comp, () -> {
                lots.lockForConsume(List.of(rm));
                held.countDown();
                sleep(800);
                return lots.mark(rmRework, null, null, Instant.now());
            }));
            Future<Long> popResult = pool.submit(() -> {
                held.await(10, TimeUnit.SECONDS);
                return result(3, 0, rmNo, 3);
            });
            assertThat(holder.get(30, TimeUnit.SECONDS)).isEqualTo(eco);
            long l = resultLot(popResult.get(30, TimeUnit.SECONDS));
            assertThat(changeOfConsume(l)).isEqualTo(eco);
        } finally {
            pool.shutdownNow();
        }
        assertThat(accounts.query(comp, "select count(*) from plm_eco_lot where eco_id = ?", Integer.class, eco)).isEqualTo(2);
    }

    @Test
    void B_A사로_B사_다른_회사_LOT_은_A사_ECO_로_표시되지_않는다() throws Exception {
        long eco = releasedEco(10, "FG", "RM");
        String other = accounts.newComp("PCX");
        accounts.exec(other, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', 'B FG', 'FG', 'MAKE', 'EA', 't', 't')""", other);
        long bLot = accounts.query(other, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, 'B-RW', item_id, 'REWORK', 1, unit_cd, now(), 't', 't' from mst_item where item_cd = 'FG' returning lot_id""",
                Long.class, other);
        // B사 범위의 표시는 A사 ECO 를 못 본다(RLS) — 같은 품목 코드여도
        assertThat(runner.call(other, () -> lots.mark(bLot, null, null, Instant.now()))).isNull();
        assertThat(accounts.query(comp, "select count(*) from plm_eco_lot where eco_id = ?", Integer.class, eco)).isZero();
        // A사 사용자로 B사 LOT 계보·재작업 → 404
        mvc.perform(get("/api/production/lots/" + bLot).with(admin)).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/production/lots/" + bLot + "/rework"), "{\"kind\":\"REWORK\",\"qty\":\"1\",\"reason\":\"x\"}").with(admin))
                .andExpect(status().isNotFound());
        assertThat(accounts.query(other, "select count(*) from lot_genealogy", Integer.class)).isZero();
    }
}
