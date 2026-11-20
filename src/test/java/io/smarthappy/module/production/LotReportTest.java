package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.hamcrest.Matchers;
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
 * LOT 역추적 보고서·LOT 단위 재작업/수리 (P2 7b, docs/25 4.10) — 자재 LOT → SPOT(투입·파라미터) → INSP(앞 공정 LOT 투입) → 완료판정 →
 * 입고까지 실제 API 로 만든 뒤 보고서 절(출처·생산 이력·조건·판정·입고·행선)을 본다. 보류 LOT 재작업 → 새 LOT, 보류 LOT 의 분할·투입은
 * 여전히 거부, 권한·A사로 B사(같은 LOT 번호), 직접 SQL(출처와 다른 계보로 태어나는 재작업 LOT).
 */
@AutoConfigureMockMvc
class LotReportTest extends PostgresTestBase {

    private static final String PW = "Cedar-lantern-41";
    private static final String WO = "/api/production/wos";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    LotService lots;

    String comp;
    RequestPostProcessor admin;
    long userId;
    long woId;
    long spot;
    long insp;
    long loc;
    String rmLot;
    String cookie;
    MockHttpSession s;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("LR");
        String ip = "10.83." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "ADM", "prd-wo", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "ADM", "prd-receipt", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "ADM", "prd-lot", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "ADM", "prd-lot-report", "SEARCH");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        long area = node(null, "AREA", "A1");
        long line1 = node(area, "LINE", "L1");
        long cell1 = node(line1, "CELL", "C1");
        loc = node(node(null, "WH", "WH1"), "LOC", "WH1-A01");
        accounts.exec(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'E1', '홍작업', 'P1', ?, 't', 't')""", comp, WorkerService.badgeHash(comp, "BADGE-0001"));
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', 'FG 품목', 'FG', 'MAKE', 'EA', 't', 't'), (?, 'RM', 'RM 품목', 'FG', 'MAKE', 'KG', 't', 't')""", comp, comp);
        accounts.exec(comp, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'SPOT', '스폿 용접', 'WELD', 't', 't'), (?, 'INSP', '검사', 'INSPECT', 't', 't')""", comp, comp);
        long rid = accounts.query(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                returning routing_id""", Long.class, comp);
        long s1 = accounts.query(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'SPOT'), ?, 30, 't', 't') returning step_id""",
                Long.class, comp, rid, cell1);
        accounts.exec(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 20, (select proc_id from mst_proc where proc_cd = 'INSP'), ?, 20, 't', 't')""", comp, rid, line1);
        accounts.exec(comp, """
                insert into mst_routing_param (comp_cd, step_id, param_cd, param_nm, unit_nm, target_val, lsl, usl, sort_no,
                                               created_by, updated_by)
                values (?, ?, 'WELD_CURRENT', '용접 전류', 'kA', 9.0, 8.5, 9.5, 10, 't', 't')""", comp, s1);
        accounts.exec(comp, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        long bom = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, base_qty, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 1, 't', 't')
                returning bom_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, issue_method, created_by, updated_by)
                values (?, ?, 10, (select item_id from mst_item where item_cd = 'RM'), 1, 'KG', 'NORMAL', 'BACKFLUSH', 't', 't')""", comp, bom);
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", bom);
        woId = id(mvc.perform(json(post(WO), "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"10\",\"planStart\":\"" + today + "\"}")
                .with(admin)));
        mvc.perform(json(post(WO + "/" + woId + "/release"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        spot = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 10", Long.class, woId);
        insp = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 20", Long.class, woId);
        long rm = runner.call(comp, () -> lots.receiveAs("RM", new BigDecimal("50"), null, null, "t"));
        rmLot = accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, rm);
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"TAB-01\",\"terminalNm\":\"TAB\",\"plantCd\":\"P1\"}")
                .with(admin)).andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        MvcResult a = mvc.perform(json(post("/api/pop/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        cookie = set.substring(set.indexOf('=') + 1, set.indexOf(';'));
        s = new MockHttpSession();
        pop(post("/api/pop/badge"), "{\"badge\":\"BADGE-0001\"}").andExpect(status().isOk());
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

    private ResultActions pop(MockHttpServletRequestBuilder b, String body) throws Exception {
        return mvc.perform(json(b, body).cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s));
    }

    private static String req() {
        return UUID.randomUUID().toString();
    }

    private void ev(long proc, String e) throws Exception {
        pop(post("/api/pop/procs/" + proc + "/events/" + e), "{\"clientReqId\":\"" + req() + "\",\"ack\":true}").andExpect(status().isOk());
    }

    private String lotOf(long proc) {
        return accounts.query(comp, "select l.lot_no from prd_result r join lot_master l on l.lot_id = r.lot_id where r.wo_proc_id = ?"
                + " order by r.result_id desc limit 1", String.class, proc);
    }

    private ResultActions report(String lotNo) throws Exception {
        return mvc.perform(get("/api/production/lot-report").param("lotNo", lotNo).with(admin));
    }

    /** 자재 15 KG → SPOT 양품 10(파라미터 2) → INSP(SPOT LOT 10 투입) 양품 10 → 둘 다 완료판정 → 입고 7 */
    private String flow() throws Exception {
        ev(spot, "start");
        pop(post("/api/pop/procs/" + spot + "/results"), "{\"clientReqId\":\"" + req() + "\",\"goodQty\":\"10\",\"inputs\":[{\"lotNo\":\""
                + rmLot + "\",\"qty\":\"15\"}]}").andExpect(status().isOk());
        pop(post("/api/pop/procs/" + spot + "/params"), "{\"clientReqId\":\"" + req() + "\",\"paramCd\":\"WELD_CURRENT\",\"value\":\"9.1\"}")
                .andExpect(status().isOk());
        pop(post("/api/pop/procs/" + spot + "/params"), "{\"clientReqId\":\"" + req() + "\",\"paramCd\":\"WELD_CURRENT\",\"value\":\"9.9\"}")
                .andExpect(jsonPath("$.data.judge").value("NG"));
        ev(spot, "end");
        String spotLot = lotOf(spot);
        mvc.perform(json(post(WO + "/procs/" + spot + "/close"), "{\"rowVer\":" + procRowVer(spot) + "}").with(admin)).andExpect(status().isOk());
        ev(insp, "start");
        pop(post("/api/pop/procs/" + insp + "/results"), "{\"clientReqId\":\"" + req() + "\",\"goodQty\":\"10\",\"inputs\":[{\"lotNo\":\""
                + spotLot + "\",\"qty\":\"10\"}]}").andExpect(status().isOk());
        ev(insp, "end");
        mvc.perform(json(post(WO + "/procs/" + insp + "/close"), "{\"rowVer\":" + procRowVer(insp) + "}").with(admin)).andExpect(status().isOk());
        String fg = lotOf(insp);
        long fgId = accounts.query(comp, "select lot_id from lot_master where lot_no = ?", Long.class, fg);
        mvc.perform(json(post("/api/production/receipts"), "{\"clientReqId\":\"" + req() + "\",\"woId\":" + woId + ",\"lotId\":" + fgId
                + ",\"nodeId\":" + loc + ",\"qty\":\"7\"}").with(admin)).andExpect(status().isOk());
        return fg;
    }

    private int procRowVer(long proc) {
        return accounts.query(comp, "select row_ver from prd_wo_proc where wo_proc_id = ?", Integer.class, proc);
    }

    // ---------- 보고서 ----------

    @Test
    void 완제품_LOT_보고서는_출처부터_입고까지_한_번에() throws Exception {
        String fg = flow();
        String spotLot = lotOf(spot);
        report(fg).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lot.lotNo").value(fg)).andExpect(jsonPath("$.data.lot.origin").value("PRODUCED"))
                .andExpect(jsonPath("$.data.receivedQty").value("7"))
                // 어디서 왔나: SPOT LOT(생산) ← 자재 LOT(입고)
                .andExpect(jsonPath("$.data.back.rows[*].lotNo").value(Matchers.contains(spotLot, rmLot)))
                .andExpect(jsonPath("$.data.back.rows[1].origin").value("RECEIVED"))
                .andExpect(jsonPath("$.data.back.rows[1].qty").value("15"))
                // 생산 이력: 두 공정의 실적, 작업자·단말
                .andExpect(jsonPath("$.data.production.rows[*].procCd").value(Matchers.contains("SPOT", "INSP")))
                .andExpect(jsonPath("$.data.production.rows[0].workerNm").value("홍작업"))
                .andExpect(jsonPath("$.data.production.rows[0].terminalCd").value("TAB-01"))
                .andExpect(jsonPath("$.data.production.truncated").value(false))
                // 조건: SPOT 파라미터 2건(NG 포함), 판정 2건, 입고 1건, 행선 없음
                .andExpect(jsonPath("$.data.params.rows[*].judge").value(Matchers.contains("OK", "NG")))
                .andExpect(jsonPath("$.data.params.rows[0].lsl").value("8.5"))
                .andExpect(jsonPath("$.data.closes.rows[*].procCd").value(Matchers.contains("SPOT", "INSP")))
                .andExpect(jsonPath("$.data.closes.rows[1].inputQty").value("10"))
                .andExpect(jsonPath("$.data.receipts.rows[0].nodeCd").value("WH1-A01"))
                .andExpect(jsonPath("$.data.fwd.rows").isEmpty())
                .andExpect(jsonPath("$.data.generatedBy").value("admin1")).andExpect(jsonPath("$.data.zone").value("Asia/Seoul"));
        // 자재 LOT 보고서: 생산 이력 없음(자재), 행선 = SPOT LOT → 완제품 LOT
        report(rmLot).andExpect(jsonPath("$.data.production.rows").isEmpty())
                .andExpect(jsonPath("$.data.fwd.rows[*].lotNo").value(Matchers.contains(spotLot, fg)));
        report("NOPE-LOT").andExpect(status().isNotFound());
        report(" ").andExpect(status().isNotFound());
    }

    @Test
    void 권한_A사로_B사() throws Exception {
        String fg = flow();
        // 권한 없는 사용자
        long u2 = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, u2, "VIEW", "prd-lot", "SEARCH");
        mvc.perform(get("/api/production/lot-report").param("lotNo", fg).with(accounts.as(comp, u2, "viewer1")))
                .andExpect(status().isForbidden());
        // B사: 같은 LOT 번호를 B 가 갖고 있어도 B 의 것만, A 의 기록은 하나도 안 섞인다
        String a = comp;
        comp = accounts.newComp("LX");
        long bu = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, bu, "ADM", "prd-lot-report", "SEARCH");
        RequestPostProcessor b = accounts.as(comp, bu, "admin1");
        mvc.perform(get("/api/production/lot-report").param("lotNo", fg).with(b)).andExpect(status().isNotFound());
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', 'B 품목', 'FG', 'MAKE', 'EA', 't', 't')""", comp);
        accounts.exec(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', 3, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG'""", comp, fg);
        mvc.perform(get("/api/production/lot-report").param("lotNo", fg).with(b)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lot.itemNm").value("B 품목")).andExpect(jsonPath("$.data.back.rows").isEmpty())
                .andExpect(jsonPath("$.data.production.rows").isEmpty()).andExpect(jsonPath("$.data.receipts.rows").isEmpty());
        comp = a;
    }

    // ---------- LOT 단위 재작업·수리 ----------

    @Test
    void 보류_LOT_재작업은_새_LOT_분할_투입은_여전히_거부() throws Exception {
        String fg = flow();
        long fgId = accounts.query(comp, "select lot_id from lot_master where lot_no = ?", Long.class, fg);
        int rv = accounts.query(comp, "select row_ver from lot_master where lot_id = ?", Integer.class, fgId);
        mvc.perform(json(put("/api/production/lots/" + fgId + "/hold"), "{\"holdYn\":\"Y\",\"reason\":\"용접 불량 의심\",\"rowVer\":" + rv + "}")
                .with(admin)).andExpect(status().isOk());
        mvc.perform(json(post("/api/production/lots/" + fgId + "/split"), "{\"qtys\":[\"1\"]}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("LOT_HOLD"));
        // 검증: 종류·사유·잔량 넘김
        String rw = "/api/production/lots/" + fgId + "/rework";
        mvc.perform(json(post(rw), "{\"kind\":\"SORT\",\"qty\":\"1\",\"reason\":\"x\"}").with(admin)).andExpect(jsonPath("$.errors[0].code").value("CODE"));
        mvc.perform(json(post(rw), "{\"kind\":\"REWORK\",\"qty\":\"1\"}").with(admin)).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        mvc.perform(json(post(rw), "{\"kind\":\"REWORK\",\"qty\":\"11\",\"reason\":\"x\"}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("OVER_BALANCE"));
        // 보류 LOT 3 재작업 → 새 LOT(REWORK, 보류 아님), 계보 REWORK, 부모 잔량 7·보류 그대로
        long child = id(mvc.perform(json(post(rw), "{\"kind\":\"rework\",\"qty\":\"3\",\"reason\":\"재용접\"}").with(admin)));
        assertThat(accounts.query(comp, "select origin || '/' || hold_yn || '/' || trim_scale(qty_created)::text from lot_master where lot_id = ?",
                String.class, child)).isEqualTo("REWORK/N/3");
        assertThat(accounts.query(comp, "select rel_type || '/' || reason from lot_genealogy where child_lot_id = ?", String.class, child))
                .isEqualTo("REWORK/재용접");
        assertThat(accounts.query(comp, "select hb_lot_balance(lot_id)::int || hold_yn from lot_master where lot_id = ?", String.class, fgId))
                .isEqualTo("7Y");
        // 수량을 비우면 잔량 전부(수리)
        long rep = id(mvc.perform(json(post(rw), "{\"kind\":\"REPAIR\",\"reason\":\"수리\"}").with(admin)));
        assertThat(accounts.query(comp, "select origin || trim_scale(qty_created)::text from lot_master where lot_id = ?", String.class, rep))
                .isEqualTo("REPAIR7");
        mvc.perform(json(post(rw), "{\"kind\":\"REWORK\",\"reason\":\"x\"}").with(admin)).andExpect(jsonPath("$.errors[0].code").value("EMPTY_LOT"));
        // 재작업 LOT 의 보고서: 어디서 왔나 = 보류 완제품 LOT ← SPOT LOT ← 자재 LOT, 생산 이력 = 완제품·SPOT 실적
        String childNo = accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, child);
        report(childNo).andExpect(jsonPath("$.data.lot.origin").value("REWORK"))
                .andExpect(jsonPath("$.data.back.rows[0].lotNo").value(fg)).andExpect(jsonPath("$.data.back.rows[0].relType").value("REWORK"))
                .andExpect(jsonPath("$.data.back.rows.length()").value(3))
                .andExpect(jsonPath("$.data.production.rows[*].procCd").value(Matchers.contains("SPOT", "INSP")));
        // 직접 SQL: 보류 LOT 에서 분할·투입, 재작업 LOT 이 다른 유형의 계보로 태어남, 재작업 LOT 은 생산 입고 대상 아님
        String edge = """
                insert into lot_genealogy (comp_cd, parent_lot_id, child_lot_id, rel_type, qty, unit_cd, occurred_at, entry_method, recorded_by)
                values (?, ?, ?, ?, 1, 'EA', now(), 'KEY', 't')""";
        long spare = accounts.query(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, 'ZZ-REWORK-1', item_id, 'REWORK', 1, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG'
                returning lot_id""", Long.class, comp);
        int holdLotRv = accounts.query(comp, "select row_ver from lot_master where lot_id = ?", Integer.class, child);
        mvc.perform(json(put("/api/production/lots/" + child + "/hold"), "{\"holdYn\":\"Y\",\"reason\":\"재검\",\"rowVer\":" + holdLotRv + "}")
                .with(admin)).andExpect(status().isOk());
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, edge, comp, child, spare, "SPLIT"))))
                .contains("ck_lot_genealogy_hold");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, edge, comp, rep, spare, "SPLIT"))))
                .contains("ck_lot_genealogy_origin");
        accounts.exec(comp, edge, comp, child, spare, "REWORK");
        mvc.perform(json(post("/api/production/receipts"), "{\"clientReqId\":\"" + req() + "\",\"woId\":" + woId + ",\"lotId\":" + rep
                + ",\"nodeId\":" + loc + ",\"qty\":\"1\"}").with(admin)).andExpect(jsonPath("$.errors[0].code").value("LAST_OP"));
        // 목록 조회: 출처 REWORK 필터(전에는 출처 목록 밖이라 거부)
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        mvc.perform(get("/api/production/lots").param("origin", "REWORK").param("from", today).param("to", today).with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows[*].origin").value(Matchers.everyItem(Matchers.is("REWORK"))));
    }
}
