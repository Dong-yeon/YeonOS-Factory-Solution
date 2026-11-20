package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.master.workforce.WorkerService;
import io.smarthappy.module.pop.PopAuthService;
import io.smarthappy.module.wms.link.ReceiptLocQuery;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * 공정 완료판정·지시 완료·마감·생산 입고·재작업/수리 처리 (P2 7a, docs/25 4.9) — 실적은 실제 POP API 로 남기고(착수·실적·종료),
 * 사무 API 로 판정(보존식·대기·앞 공정·상태)·처리(새 산출 LOT)·완료·마감·입고(LOT 별 초과·동시·정정·마감 뒤)를 본다.
 * 이상 사용: 순서 우회, 완료된 공정 실적 정정, 처리된 재작업 되돌리기, 같은 요청 id, A사로 B사, 원장 직접 SQL.
 */
@AutoConfigureMockMvc
class CompletionReceiptTest extends PostgresTestBase {

    private static final String PW = "Cedar-lantern-41";
    private static final String WO = "/api/production/wos";
    private static final String RCV = "/api/production/receipts";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    PolicyService policies;

    @Autowired
    ReceiptService receipts;

    String comp;
    RequestPostProcessor admin;
    long woId;
    long spot;
    long insp;
    long loc;
    long line1;
    String cookie;
    MockHttpSession s;
    String ip;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("CR");
        ip = "10.81." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "ADM", "prd-wo", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "ADM", "prd-receipt", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        long area = node(null, "AREA", "A1");
        line1 = node(area, "LINE", "L1");
        long cell1 = node(line1, "CELL", "C1");
        long wh = node(null, "WH", "WH1");
        loc = node(wh, "LOC", "WH1-A01");
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
        accounts.exec(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'SPOT'), ?, 30, 't', 't'),
                       (?, ?, 20, (select proc_id from mst_proc where proc_cd = 'INSP'), ?, 20, 't', 't')""",
                comp, rid, cell1, comp, rid, line1);
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
        woId = id(mvc.perform(json(post(WO), "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"10\",\"planStart\":\"" + today + "\"}")
                .with(admin)));
        mvc.perform(json(post(WO + "/" + woId + "/release"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        spot = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 10", Long.class, woId);
        insp = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 20", Long.class, woId);
        cookie = terminal("TAB-01");
        s = new MockHttpSession();
        mvc.perform(json(post("/api/pop/badge"), "{\"badge\":\"BADGE-0001\"}").cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s))
                .andExpect(status().isOk());
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

    private void popEvent(long proc, String ev) throws Exception {
        pop(post("/api/pop/procs/" + proc + "/events/" + ev), "{\"clientReqId\":\"" + req() + "\",\"ack\":true}").andExpect(status().isOk());
    }

    /** 실적 — 폐기·재작업·수리는 불량 D-PIT 로 */
    private long result(long proc, int good, int scrap, int rework, int repair) throws Exception {
        String r = req();
        StringBuilder d = new StringBuilder();
        for (Object[] k : new Object[][] {{"SCRAP", scrap}, {"REWORK", rework}, {"REPAIR", repair}}) {
            if ((int) k[1] > 0) {
                d.append(d.length() == 0 ? "" : ",").append("{\"kind\":\"").append(k[0]).append("\",\"defectCd\":\"D-PIT\",\"qty\":\"")
                        .append(k[1]).append("\"}");
            }
        }
        pop(post("/api/pop/procs/" + proc + "/results"), "{\"clientReqId\":\"" + r + "\",\"goodQty\":\"" + good + "\",\"scrapQty\":\"" + scrap
                + "\",\"reworkQty\":\"" + rework + "\",\"repairQty\":\"" + repair + "\",\"defects\":[" + d + "]}").andExpect(status().isOk());
        return accounts.query(comp, "select result_id from prd_result where client_req_id = ?", Long.class, r);
    }

    private int procRowVer(long proc) {
        return accounts.query(comp, "select row_ver from prd_wo_proc where wo_proc_id = ?", Integer.class, proc);
    }

    private int woRowVer() {
        return accounts.query(comp, "select row_ver from prd_wo where wo_id = ?", Integer.class, woId);
    }

    private ResultActions closeProc(long proc) throws Exception {
        return mvc.perform(json(post(WO + "/procs/" + proc + "/close"), "{\"rowVer\":" + procRowVer(proc) + "}").with(admin));
    }

    private ResultActions rework(long proc, String reqId, String kind, String good, String scrap) throws Exception {
        return mvc.perform(json(post(WO + "/procs/" + proc + "/rework"), "{\"clientReqId\":\"" + reqId + "\",\"kind\":\"" + kind
                + "\",\"goodQty\":\"" + good + "\",\"scrapQty\":\"" + scrap + "\",\"reason\":\"재용접\"}").with(admin));
    }

    private ResultActions receive(String reqId, long lotId, long nodeId, String qty) throws Exception {
        return mvc.perform(json(post(RCV), "{\"clientReqId\":\"" + reqId + "\",\"woId\":" + woId + ",\"lotId\":" + lotId + ",\"nodeId\":"
                + nodeId + ",\"qty\":\"" + qty + "\"}").with(admin));
    }

    private long lastLot() {
        return accounts.query(comp, "select r.lot_id from prd_result r where r.wo_proc_id = ? and r.lot_id is not null order by r.result_id desc limit 1",
                Long.class, insp);
    }

    private String procStatus(long proc) {
        return accounts.query(comp, "select status from prd_wo_proc where wo_proc_id = ?", String.class, proc);
    }

    /** SPOT: 양품 10·폐기 1·재작업 2 → 종료 */
    private void spotRun() throws Exception {
        popEvent(spot, "start");
        result(spot, 10, 1, 2, 0);
        popEvent(spot, "end");
    }

    // ---------- 흐름 ----------

    @Test
    void 판정_처리_완료_입고_정정_마감() throws Exception {
        spotRun();
        // 대기 2(재작업) → 완료 불가, 미리보기에 이유
        mvc.perform(get(WO + "/procs/" + spot + "/close-check").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.firstOp").value(true)).andExpect(jsonPath("$.data.inputQty").value("13"))
                .andExpect(jsonPath("$.data.reworkWait").value("2")).andExpect(jsonPath("$.data.problems").value(Matchers.contains("PENDING")))
                // 완료판정 경고 빈(CloseNotice, 4.46)이 없으면 빈 목록
                .andExpect(jsonPath("$.data.notices").isArray()).andExpect(jsonPath("$.data.notices").isEmpty());
        closeProc(spot).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("PENDING"));
        // 처리: 대기보다 많으면 거부, 양품 1(새 LOT)·폐기 1, 같은 요청 id → 처음 응답
        rework(spot, req(), "REWORK", "2", "1").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("OVER_PENDING"));
        rework(spot, req(), "REPAIR", "1", "0").andExpect(jsonPath("$.errors[0].code").value("OVER_PENDING"));
        String r1 = req();
        rework(spot, r1, "rework", "1", "1").andExpect(status().isOk()).andExpect(jsonPath("$.data.lotNo").isNotEmpty())
                .andExpect(jsonPath("$.data.replayed").value(false));
        rework(spot, r1, "REWORK", "1", "1").andExpect(jsonPath("$.data.replayed").value(true));
        rework(insp, r1, "REWORK", "1", "1").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        assertThat(accounts.query(comp, "select count(*) from prd_rework where wo_proc_id = ?", Integer.class, spot)).isEqualTo(1);
        // 첫 공정: 투입 = 처리 합계(13), 유효 양품 = 10 + 처리 양품 1
        mvc.perform(get(WO + "/procs/" + spot + "/close-check").with(admin)).andExpect(jsonPath("$.data.problems").isEmpty())
                .andExpect(jsonPath("$.data.effGoodQty").value("11"));
        closeProc(spot).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DONE"));
        assertThat(accounts.query(comp, """
                select input_qty::int || '/' || eff_good_qty::int || '/' || first_op_yn || '/' || recorded_by from prd_wo_proc_close
                 where wo_proc_id = ?""", String.class, spot)).isEqualTo("13/11/Y/admin1");
        closeProc(spot).andExpect(jsonPath("$.errors[0].code").value("STATE"));
        // 완료된 공정은 다시 착수할 수 없다
        pop(post("/api/pop/procs/" + spot + "/events/start"), "{\"clientReqId\":\"" + req() + "\",\"ack\":true}")
                .andExpect(jsonPath("$.errors[0].code").value("STATE"));

        // INSP: 투입 11(앞 공정 유효 양품) — 양품 9 + 폐기 1 = 10 ≠ 11 → BALANCE(허용 0%)
        popEvent(insp, "start");
        result(insp, 9, 1, 0, 0);
        popEvent(insp, "end");
        closeProc(insp).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("BALANCE"))
                .andExpect(jsonPath("$.errors[0].message").value(Matchers.containsString("11")));
        // 공장 허용 오차 10% → |10 − 11| = 1 ≤ 1.1
        runner.run(comp, () -> policies.set(CompletionService.POLICY_TOL, "P1", "10", "t"));
        mvc.perform(get(WO + "/procs/" + insp + "/close-check").with(admin)).andExpect(jsonPath("$.data.tolPct").value(10))
                .andExpect(jsonPath("$.data.diffQty").value("-1")).andExpect(jsonPath("$.data.problems").isEmpty());
        // 완료 전 입고: 마지막 공정 LOT 9 — 만든 양 넘게는 안 된다(화면 검사 + 트리거)
        long lot = lastLot();
        receive(req(), lot, loc, "10").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("OVER_RECEIPT"));
        String rr = req();
        receive(rr, lot, loc, "6").andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(false));
        receive(rr, lot, loc, "6").andExpect(jsonPath("$.data.replayed").value(true));
        closeProc(insp).andExpect(status().isOk());
        // 지시 완료 → 입고는 계속 가능, 마감 뒤 불가
        mvc.perform(json(post(WO + "/" + woId + "/complete"), "{\"rowVer\":" + woRowVer() + "}").with(admin)).andExpect(status().isOk());
        receive(req(), lot, loc, "3").andExpect(status().isOk());
        mvc.perform(get(RCV + "/wos/" + woId).with(admin)).andExpect(jsonPath("$.data.lots[0].remainQty").value("0"))
                .andExpect(jsonPath("$.data.wo.receivedQty").value("9")).andExpect(jsonPath("$.data.history.length()").value(2));
        // 정정: 사유 필수, 한 번만, 반대 수량
        long first = accounts.query(comp, "select receipt_id from prd_receipt where client_req_id = ?", Long.class, rr);
        mvc.perform(json(post(RCV + "/" + first + "/correct"), "{\"reason\":\" \"}").with(admin)).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        mvc.perform(json(post(RCV + "/" + first + "/correct"), "{\"reason\":\"수량 잘못\"}").with(admin)).andExpect(status().isOk());
        mvc.perform(json(post(RCV + "/" + first + "/correct"), "{\"reason\":\"또\"}").with(admin)).andExpect(jsonPath("$.errors[0].code").value("STATE"));
        assertThat(accounts.query(comp, "select trim_scale(sum(qty))::text from prd_receipt where wo_id = ?", String.class, woId)).isEqualTo("3");
        mvc.perform(get(WO + "/" + woId + "/completion").with(admin)).andExpect(jsonPath("$.data.lastGoodQty").value("9"))
                .andExpect(jsonPath("$.data.receivedQty").value("3")).andExpect(jsonPath("$.data.unreceivedQty").value("6"));
        // 마감(미입고 6 은 막지 않는다 — D16) → 입고·정정 불가
        mvc.perform(json(post(WO + "/" + woId + "/close"), "{\"rowVer\":" + woRowVer() + "}").with(admin)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select status from prd_wo where wo_id = ?", String.class, woId)).isEqualTo("CLOSED");
        receive(req(), lot, loc, "1").andExpect(jsonPath("$.errors[0].code").value("STATE"));
        long second = accounts.query(comp, "select receipt_id from prd_receipt where qty = 3", Long.class);
        mvc.perform(json(post(RCV + "/" + second + "/correct"), "{\"reason\":\"x\"}").with(admin)).andExpect(jsonPath("$.errors[0].code").value("STATE"));
        // 입고 목록: 기본은 진행·완료, 마감은 상태로
        mvc.perform(get(RCV + "/wos").param("plantCd", "P1").with(admin)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(RCV + "/wos").param("plantCd", "P1").param("status", "CLOSED").with(admin))
                .andExpect(jsonPath("$.data[0].woId").value((int) woId));
    }

    /** 입고·정정은 같은 트랜잭션으로 재고 원장(WMS, docs/25 4.14·docs/30 11장) — 실패하면 입고도 남지 않는다 */
    @Test
    void 입고_정정은_재고_원장으로_옮긴_재고는_정정_거부_재고_안_둠_위치는_칸_오류() throws Exception {
        spotRun();
        popEvent(insp, "start");
        result(insp, 5, 0, 0, 0);
        popEvent(insp, "end");
        long lot = lastLot();
        String rr = req();
        receive(rr, lot, loc, "5").andExpect(status().isOk());
        long rcv = accounts.query(comp, "select receipt_id from prd_receipt where client_req_id = ?", Long.class, rr);
        assertThat(accounts.query(comp, """
                select trx_type || ':' || trim_scale(qty) || ':' || stock_status || ':' || (lot_id = ?) from inv_trx
                 where src_type = 'PRD_RECEIPT' and src_id = ?""", String.class, lot, rcv)).isEqualTo("RCV_PRD:5:AVAILABLE:true");
        // 같은 요청 다시 → 처음 응답, 재고 원장도 한 번
        receive(rr, lot, loc, "5").andExpect(jsonPath("$.data.replayed").value(true));
        assertThat(accounts.query(comp, "select count(*) from inv_trx where src_type = 'PRD_RECEIPT'", Integer.class)).isEqualTo(1);

        // 그 사이 4 를 내보냄 → 정정(-5)은 재고가 모자라 409 + 입고 화면 문구, 정정 행도 재고도 그대로
        long fg = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG'", Long.class);
        String adj = """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by, lot_id)
                values (?, 'P1', ?, ?, ?, 'AVAILABLE', ?, 'EA', 'MANUAL', now(), 'KEY', 't', ?)""";
        accounts.exec(comp, adj, comp, "ADJ_OUT", fg, loc, -4, lot);
        mvc.perform(json(post(RCV + "/" + rcv + "/correct"), "{\"reason\":\"수량 잘못\"}").with(admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.errors[0].code").value("STOCK_MOVED"))
                .andExpect(jsonPath("$.errors[0].message").value(Matchers.containsString("되돌린 뒤")));
        assertThat(accounts.query(comp, "select count(*) from prd_receipt where reversal_of_id = ?", Integer.class, rcv)).isZero();
        // 되돌려 놓으면 정정 → 재고 원장도 역분개, 순재고 0
        accounts.exec(comp, adj, comp, "ADJ_IN", fg, loc, 4, lot);
        mvc.perform(json(post(RCV + "/" + rcv + "/correct"), "{\"reason\":\"수량 잘못\"}").with(admin)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select trim_scale(coalesce(sum(qty), 0))::text from inv_trx where item_id = ?", String.class, fg))
                .isEqualTo("0");

        // 재고 안 둠 위치(WMS 로케이션 속성) → nodeId 칸 오류, 입고 행도 남지 않는다
        long noStock = node(accounts.query(comp, "select parent_id from mst_equip_hier where node_id = ?", Long.class, loc), "LOC", "WH1-X99");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, stock_yn, created_by, updated_by) values (?, ?, 'N', 't', 't')",
                noStock, comp);
        int before = accounts.query(comp, "select count(*) from prd_receipt", Integer.class);
        receive(req(), lot, noStock, "1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("nodeId")).andExpect(jsonPath("$.errors[0].code").value("STOCK_NOT_ALLOWED"));
        assertThat(accounts.query(comp, "select count(*) from prd_receipt", Integer.class)).isEqualTo(before);
    }

    @Test
    void 순서_우회_완료된_공정_정정_처리된_재작업_되돌리기() throws Exception {
        popEvent(spot, "start");
        long res = result(spot, 10, 0, 2, 0);
        // 진행 중(RUN)·앞 공정 미완료·실적 없음·공정 미완료 지시
        closeProc(spot).andExpect(jsonPath("$.errors[0].code").value("STATE"));
        closeProc(insp).andExpect(jsonPath("$.errors[0].code").value("STATE"));
        mvc.perform(get(WO + "/procs/" + insp + "/close-check").with(admin))
                .andExpect(jsonPath("$.data.problems").value(Matchers.contains("STATE", "PREV_OPEN", "NO_RESULT")));
        mvc.perform(json(post(WO + "/" + woId + "/complete"), "{\"rowVer\":" + woRowVer() + "}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("PROCS_OPEN"));
        mvc.perform(json(post(WO + "/" + woId + "/close"), "{\"rowVer\":" + woRowVer() + "}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("STATE"));
        // 재작업 2 를 처리한 뒤 그 실적을 정정하면 대기가 0 미만 → 거부, 실적 그대로
        rework(spot, req(), "REWORK", "2", "0").andExpect(status().isOk());
        pop(post("/api/pop/results/" + res + "/correct"), "{\"reason\":\"잘못 입력\"}").andExpect(status().is4xxClientError());
        assertThat(accounts.query(comp, "select count(*) from prd_result where reversal_of_id = ?", Integer.class, res)).isZero();
        // 완료된 공정의 실적 정정 → 거부
        long res2 = result(spot, 1, 0, 0, 0);
        popEvent(spot, "end");
        closeProc(spot).andExpect(status().isOk());
        pop(post("/api/pop/results/" + res2 + "/correct"), "{\"reason\":\"잘못 입력\"}").andExpect(status().is4xxClientError());
        assertThat(accounts.query(comp, "select count(*) from prd_result where reversal_of_id = ?", Integer.class, res2)).isZero();
        // 완료된 공정에 처리 → STATE
        rework(spot, req(), "REWORK", "1", "0").andExpect(jsonPath("$.errors[0].code").value("STATE"));
        // 첫 공정 LOT 은 입고 대상이 아니다(마지막 공정 산출만)
        long spotLot = accounts.query(comp, "select lot_id from prd_result where result_id = ?", Long.class, res);
        receive(req(), spotLot, loc, "1").andExpect(jsonPath("$.errors[0].code").value("LAST_OP"));
        // 창고가 아닌 노드
        popEvent(insp, "start");
        result(insp, 13, 0, 0, 0);
        receive(req(), lastLot(), line1, "1").andExpect(jsonPath("$.errors[0].code").value("NOT_WH"));
        // 보류 LOT
        accounts.exec(comp, "insert into lot_hold (comp_cd, lot_id, source, reason, held_by) select comp_cd, lot_id, 'MANUAL', '검사', 't' from lot_master where lot_id = ?", lastLot());
        receive(req(), lastLot(), loc, "1").andExpect(jsonPath("$.errors[0].code").value("LOT_HOLD"));
        assertThat(procStatus(insp)).isEqualTo("RUN");
    }

    @Test
    void 동시_입고는_만든_양을_넘지_않는다() throws Exception {
        popEvent(spot, "start");
        result(spot, 10, 0, 0, 0);
        popEvent(insp, "start");
        // LOT 10 에 동시에 6 + 6 — 하나만 들어간다(화면 검사를 둘 다 통과해도 트리거가 LOT 행 잠금 뒤 판정) ×3회
        for (int round = 0; round < 3; round++) {
            result(insp, 10, 0, 0, 0);
            long lot = lastLot();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Integer> c = () -> receive(req(), lot, loc, "6").andReturn().getResponse().getStatus();
                List<Future<Integer>> fs = pool.invokeAll(List.of(c, c));
                long ok = 0;
                for (Future<Integer> f : fs) {
                    ok += f.get() == 200 ? 1 : 0;
                }
                assertThat(ok).isEqualTo(1);
            } finally {
                pool.shutdown();
            }
            assertThat(accounts.query(comp, "select sum(qty) from prd_receipt where lot_id = ?", BigDecimal.class, lot))
                    .isEqualByComparingTo("6");
        }
    }

    /** 위치 목록의 한 칸 → "status/reasonCd" */
    private static String locOf(String json, long nodeId) {
        List<java.util.Map<String, Object>> list = JsonPath.read(json, "$.data");
        return list.stream().filter(m -> ((Number) m.get("nodeId")).longValue() == nodeId)
                .map(m -> m.get("status") + "/" + m.get("reasonCd")).findFirst().orElse("(없음)");
    }

    /**
     * 입고 위치(docs/25 4.45, WMS ReceiptLocQuery) — 지시·LOT 기준 판정: 재고 안 둠 칸은 사유와 함께 목록에, 기본 상태 QC 칸은 받으면 QC.
     * 목록 밖(재고 안 둠) 칸을 API 로 직접 보내면 저장 전 칸 오류·행 없음. WMS 없음(NONE)이면 사유 없는 지금 목록이고 post 가 최종으로 막는다.
     * B사는 A사 지시로 위치를 물을 수 없다(404)
     */
    @Test
    void 입고_위치는_WMS_판정으로_사유와_상태_직접_요청은_거부_NONE_은_지금_목록_B사는_404() throws Exception {
        spotRun();
        popEvent(insp, "start");
        result(insp, 5, 0, 0, 0);
        popEvent(insp, "end");
        long lot = lastLot();
        long wh = accounts.query(comp, "select parent_id from mst_equip_hier where node_id = ?", Long.class, loc);
        long noStock = node(wh, "LOC", "WH1-X98");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, stock_yn, created_by, updated_by) values (?, ?, 'N', 't', 't')",
                noStock, comp);
        long staging = node(wh, "LOC", "WH1-S01");   // 출하 대기 칸(WMS F1 #634) — 생산 입고 받지 않음
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't')",
                staging, comp);
        long qcLoc = node(wh, "LOC", "WH1-Q01");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, default_status, created_by, updated_by) values (?, ?, 'QC', 't', 't')",
                qcLoc, comp);
        String json = mvc.perform(get(RCV + "/locations").param("woId", String.valueOf(woId)).param("lotId", String.valueOf(lot)).with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(locOf(json, loc)).isEqualTo("AVAILABLE/null");
        assertThat(locOf(json, qcLoc)).isEqualTo("QC/null");
        assertThat(locOf(json, noStock)).isEqualTo("null/STOCK_NOT_ALLOWED");
        assertThat(locOf(json, staging)).isEqualTo("null/STAGING_NOT_ALLOWED");
        // 지시 없이 공장만(예전 호출) — 판정 없음
        String plain = mvc.perform(get(RCV + "/locations").param("plantCd", "P1").with(admin)).andReturn().getResponse().getContentAsString();
        assertThat(locOf(plain, noStock)).isEqualTo("null/null");
        // 목록에서 고를 수 없는 칸을 API 로 직접 → 저장 전 칸 오류, 입고·재고 행 없음
        int before = accounts.query(comp, "select count(*) from prd_receipt", Integer.class);
        receive(req(), lot, noStock, "1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("nodeId")).andExpect(jsonPath("$.errors[0].code").value("STOCK_NOT_ALLOWED"));
        assertThat(accounts.query(comp, "select count(*) from prd_receipt", Integer.class)).isEqualTo(before);
        receive(req(), lot, staging, "1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("nodeId")).andExpect(jsonPath("$.errors[0].code").value("STAGING_NOT_ALLOWED"));
        assertThat(accounts.query(comp, "select count(*) from prd_receipt", Integer.class)).isEqualTo(before);
        // 받을 수 있는 QC 칸 → 저장, 재고는 QC
        receive(req(), lot, qcLoc, "1").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select stock_status from inv_trx where src_type = 'PRD_RECEIPT' and node_id = ?", String.class, qcLoc))
                .isEqualTo("QC");

        // WMS 없음(NONE) — 사유·상태 없는 지금 목록, 저장은 post 가 최종으로 막는다(서버 검증은 화면 숨김과 무관하게 그대로)
        Object real = ReflectionTestUtils.getField(receipts, "locQuery");
        ReflectionTestUtils.setField(receipts, "locQuery", new StaticListableBeanFactory().getBeanProvider(ReceiptLocQuery.class));
        try {
            String none = mvc.perform(get(RCV + "/locations").param("woId", String.valueOf(woId)).with(admin))
                    .andReturn().getResponse().getContentAsString();
            assertThat(locOf(none, noStock)).isEqualTo("null/null");
            assertThat(locOf(none, loc)).isEqualTo("null/null");
            receive(req(), lot, noStock, "1").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].code").value("STOCK_NOT_ALLOWED"));
        } finally {
            ReflectionTestUtils.setField(receipts, "locQuery", real);
        }

        // B사: A사 지시로 위치 묻기 → 404
        comp = accounts.newComp("CY");
        long bu = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, bu, "ADM", "prd-receipt", "SEARCH", "SAVE");
        accounts.addPlant(comp, "P1", "B 1공장");
        mvc.perform(get(RCV + "/locations").param("woId", String.valueOf(woId)).with(accounts.as(comp, bu, "admin1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void A사로_B사() throws Exception {
        spotRun();
        long aLot = accounts.query(comp, "select lot_id from prd_result where wo_proc_id = ? and lot_id is not null", Long.class, spot);
        String a = comp;
        long aLoc = loc;
        comp = accounts.newComp("CX");
        long bu = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, bu, "ADM", "prd-wo", "SEARCH", "SAVE");
        accounts.grant(comp, bu, "ADM", "prd-receipt", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(comp, bu, "admin1");
        accounts.addPlant(comp, "P1", "B 1공장");
        String bc = comp;
        mvc.perform(get(WO + "/" + woId + "/completion").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(WO + "/procs/" + spot + "/close-check").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(WO + "/procs/" + spot + "/close"), "{\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(WO + "/procs/" + spot + "/rework"), "{\"clientReqId\":\"" + req() + "\",\"kind\":\"REWORK\",\"goodQty\":\"1\"}")
                .with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(WO + "/" + woId + "/complete"), "{\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(RCV + "/wos/" + woId).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(RCV), "{\"clientReqId\":\"" + req() + "\",\"woId\":" + woId + ",\"lotId\":" + aLot + ",\"nodeId\":" + aLoc
                + ",\"qty\":\"1\"}").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(RCV + "/locations").param("plantCd", "P1").with(b)).andExpect(jsonPath("$.data.length()").value(0));
        // 직접 SQL(B 범위): A 지시·공정·LOT 으로 원장 행
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(bc, """
                insert into prd_rework (comp_cd, wo_id, wo_proc_id, kind, qty, good_qty, scrap_qty, unit_cd, client_req_id, occurred_at,
                                        entry_method, recorded_by)
                values (?, ?, ?, 'REWORK', 1, 0, 1, 'EA', 'REQ-00000001', now(), 'KEY', 'x')""", bc, woId, spot))))
                .contains("fk_prd_rework_ctx_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(bc, """
                insert into prd_receipt (comp_cd, plant_cd, wo_id, item_id, lot_id, node_id, qty, unit_cd, client_req_id, occurred_at,
                                         entry_method, recorded_by)
                select ?, 'P1', ?, 1, ?, ?, 1, 'EA', 'REQ-00000002', now(), 'KEY', 'x'""", bc, woId, aLot, aLoc))))
                .contains("fk_prd_receipt_ctx_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(bc, """
                insert into prd_wo_proc_close (comp_cd, wo_id, wo_proc_id, input_qty, good_qty, scrap_qty, rework_qty, repair_qty, eff_good_qty,
                                               diff_qty, tol_pct, first_op_yn, occurred_at, entry_method, recorded_by)
                values (?, ?, ?, 13, 10, 1, 2, 0, 10, 0, 0, 'Y', now(), 'KEY', 'x')""", bc, woId, spot))))
                .contains("fk_prd_wo_proc_close_ctx_comp");
        comp = a;
        assertThat(procStatus(spot)).isEqualTo("READY");
    }

    @Test
    void 원장_직접_SQL() throws Exception {
        spotRun();
        // 판정 없이 상태만 DONE, 칸 값을 지어낸 판정 행, 공정 미완료 지시를 DONE
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update prd_wo_proc set status = 'DONE' where wo_proc_id = ?", spot)))).contains("ck_prd_wo_proc_close_required");
        String close = """
                insert into prd_wo_proc_close (comp_cd, wo_id, wo_proc_id, input_qty, good_qty, scrap_qty, rework_qty, repair_qty, eff_good_qty,
                                               diff_qty, tol_pct, first_op_yn, occurred_at, entry_method, recorded_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'Y', now(), 'KEY', 'x')""";
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, close, comp, woId, spot, 13, 10, 1, 2, 0, 10, 0))))
                .contains("ck_prd_wo_proc_close_pending");
        // 처리: 대기 넘김·양품인데 LOT 없음·수량 합
        String rw = """
                insert into prd_rework (comp_cd, wo_id, wo_proc_id, kind, qty, good_qty, scrap_qty, unit_cd, client_req_id, occurred_at,
                                        entry_method, recorded_by)
                values (?, ?, ?, ?, ?, ?, ?, 'EA', ?, now(), 'KEY', 'x')""";
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, rw, comp, woId, spot, "REWORK", 1, 1, 0, "REQ-00000012"))))
                .contains("ck_prd_rework_lot");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, rw, comp, woId, spot, "REWORK", 2, 0, 1, "REQ-00000013"))))
                .contains("ck_prd_rework_qty");
        accounts.exec(comp, """
                insert into prd_rework (comp_cd, wo_id, wo_proc_id, kind, qty, good_qty, scrap_qty, unit_cd, client_req_id, occurred_at,
                                        entry_method, recorded_by)
                values (?, ?, ?, 'REWORK', 2, 0, 2, 'EA', 'REQ-00000010', now(), 'KEY', 'x')""", comp, woId, spot);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, close, comp, woId, spot, 99, 10, 1, 2, 0, 10, 0))))
                .contains("ck_prd_wo_proc_close_values");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, close, comp, woId, insp, 10, 0, 0, 0, 0, 0, -10))))
                .contains("ck_prd_wo_proc_close_state");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update prd_wo set status = 'DONE' where wo_id = ?", woId)))).contains("ck_prd_wo_procs_done");
        // 대기를 모두 처리한 뒤 더 처리 → 대기 넘김
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, rw, comp, woId, spot, "REWORK", 1, 0, 1, "REQ-00000011"))))
                .contains("ck_prd_rework_pending");
        // 정상 판정(API) — 직접 넣은 처리(폐기 2)까지 계산에 들어간다
        mvc.perform(json(post(WO + "/procs/" + spot + "/close"), "{\"rowVer\":" + procRowVer(spot) + "}").with(admin)).andExpect(status().isOk());
        // 원장 UPDATE·DELETE → 42501
        for (String t : new String[] {"prd_rework", "prd_wo_proc_close", "prd_receipt"}) {
            assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "update " + t + " set comp_cd = comp_cd")))).contains("42501");
            assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from " + t)))).contains("42501");
        }
        // 완료 공정에서 DONE → 다른 상태
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update prd_wo_proc set status = 'READY' where wo_proc_id = ?", spot)))).contains("ck_prd_wo_proc_transition");
        // 입고: 지시와 다른 품목·단위, 역분개 거울 아님, 사유 없는 역분개
        popEvent(insp, "start");
        result(insp, 10, 0, 0, 0);
        long lot = lastLot();
        String ins = """
                insert into prd_receipt (comp_cd, plant_cd, wo_id, item_id, lot_id, node_id, qty, unit_cd, client_req_id, reason, occurred_at,
                                         entry_method, recorded_by, reversal_of_id)
                values (?, 'P1', ?, (select item_id from mst_item where item_cd = ?), ?, ?, ?, ?, ?, ?, now(), ?, 'x', ?)""";
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, woId, "RM", lot, loc, 1, "EA", "REQ-00000020", null,
                "KEY", null)))).contains("ck_prd_receipt_wo_match");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, woId, "FG", lot, loc, 11, "EA", "REQ-00000021", null,
                "KEY", null)))).contains("ck_prd_receipt_over");
        long r = accounts.query(comp, ins + " returning receipt_id", Long.class, comp, woId, "FG", lot, loc, 4, "EA", "REQ-00000022", null,
                "KEY", null);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, woId, "FG", lot, loc, -3, "EA", null, "x",
                "CORRECT", r)))).contains("ck_prd_receipt_reversal");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, woId, "FG", lot, loc, -4, "EA", null, null,
                "CORRECT", r)))).contains("ck_prd_receipt_qty");
        accounts.exec(comp, ins, comp, woId, "FG", lot, loc, -4, "EA", null, "정정", "CORRECT", r);
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, ins, comp, woId, "FG", lot, loc, -4, "EA", null, "또",
                "CORRECT", r)))).contains("23505");
    }
}
