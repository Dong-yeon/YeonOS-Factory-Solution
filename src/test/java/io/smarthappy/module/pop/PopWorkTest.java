package io.smarthappy.module.pop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.hamcrest.Matchers;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.common.web.BusinessException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import io.smarthappy.module.production.link.StartGate;
import io.smarthappy.module.production.link.StartGate.GateReason;
import io.smarthappy.module.production.link.StartGate.GateVerdict;
import io.smarthappy.module.production.link.StartGate.StartContext;
import io.smarthappy.platform.policy.PolicyService;
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
import io.smarthappy.module.production.LotService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * POP 착수·실적·투입 (P2 5b, docs/25 4.7) — 공정 전이(착수·멈춤 사유·재개·종료, 자격 WARN 확인), 실적 확정(산출 LOT + 투입 계보 +
 * 불량 내역 합), 파라미터 실측·판정, client_req_id 멱등(재전송·동시·다른 공정), 오프라인 재전송 발생 시각, 정정 = 역분개(투입 되돌림·LOT 보류,
 * 뒤 공정에서 쓴 LOT 은 거부), 대리 입력(recorded_by 단말 / performed_by 작업자), 작업자 없음, 작업장 밖·A사로 B사, 원장 직접 SQL.
 */
@AutoConfigureMockMvc
@Import(PopWorkTest.TestGates.class)
class PopWorkTest extends PostgresTestBase {

    // ---------- 착수 게이트 가짜 빈 2개(docs/25 4.21) — 기본은 통과, 시험마다 바꾼다 ----------

    static final AtomicReference<Function<StartContext, GateVerdict>> QMS_GATE = new AtomicReference<>(c -> GateVerdict.PASS);
    static final AtomicReference<Function<StartContext, GateVerdict>> AUX_GATE = new AtomicReference<>(c -> GateVerdict.PASS);
    static final List<StartContext> SEEN = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class TestGates {

        @Bean
        @Order(1)
        StartGate qmsGate() {
            return gate("QMS", QMS_GATE);
        }

        @Bean
        @Order(2)
        StartGate auxGate() {
            return gate("AUX", AUX_GATE);
        }

        private static StartGate gate(String key, AtomicReference<Function<StartContext, GateVerdict>> fn) {
            return new StartGate() {
                @Override
                public String key() {
                    return key;
                }

                @Override
                public GateVerdict check(StartContext ctx) {
                    SEEN.add(ctx);
                    return fn.get().apply(ctx);
                }
            };
        }
    }


    private static final String PW = "Cedar-lantern-41";
    private static final String P = "/api/pop";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    io.micrometer.core.instrument.MeterRegistry registry;

    @Autowired
    TenantRunner runner;

    /** 실제 빈을 감싼 스파이 — B5 ② 시험에서만 consume 이 WMS 409 를 던지게 바꾼다(그 밖에는 실제 동작) */
    @MockitoSpyBean
    LotService lots;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PolicyService policies;

    String comp;
    RequestPostProcessor admin;
    long line1;
    long line2;
    long workerId;
    long woId;
    long spot;
    long insp;
    long other;
    String rmLot;
    String cookie;
    MockHttpSession s;
    String ip;

    @BeforeEach
    void setUp() throws Exception {
        QMS_GATE.set(c -> GateVerdict.PASS);
        AUX_GATE.set(c -> GateVerdict.PASS);
        SEEN.clear();
        comp = accounts.newComp("PW");
        ip = "10.79." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "ADM", "prd-wo", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        long area = node(null, "AREA", "A1");
        line1 = node(area, "LINE", "L1");
        long cell1 = node(line1, "CELL", "C1");
        line2 = node(area, "LINE", "L2");
        workerId = worker("E1", "홍작업", "BADGE-0001");
        for (String[] i : new String[][] {{"FG", "EA"}, {"RM", "KG"}}) {
            accounts.exec(comp, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                    values (?, ?, ?, 'FG', 'MAKE', ?, 't', 't')""", comp, i[0], i[0] + " 품목", i[1]);
        }
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
                values (?, ?, 10, (select item_id from mst_item where item_cd = 'RM'), 1.5, 'KG', 'NORMAL', 'BACKFLUSH', 't', 't')""",
                comp, bom);
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", bom);
        accounts.exec(comp, "insert into mst_qual (comp_cd, qual_cd, qual_nm, created_by, updated_by) values (?, 'WELD', '용접 1급', 't', 't')", comp);
        accounts.exec(comp, """
                insert into mst_proc_qual_req (comp_cd, proc_id, qual_id, gate_mode, created_by, updated_by)
                values (?, (select proc_id from mst_proc where proc_cd = 'SPOT'), (select qual_id from mst_qual where qual_cd = 'WELD'),
                        'WARN', 't', 't')""", comp);
        accounts.exec(comp, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'D-PIT', '기공', 'WELD', 't', 't'), (?, 'D-BURR', '버', 'APPEARANCE', 't', 't')""", comp, comp);
        woId = id(mvc.perform(json(post("/api/production/wos"),
                "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"100\",\"planStart\":\"" + today + "\"}").with(admin)));
        mvc.perform(json(post("/api/production/wos/" + woId + "/release"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        spot = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 10", Long.class, woId);
        insp = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 20", Long.class, woId);
        // 다른 라인(L2) 의 공정 — L1 단말에서 안 보인다
        long rid2 = accounts.query(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'RM'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                returning routing_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'INSP'), ?, 20, 't', 't')""", comp, rid2, line2);
        accounts.exec(comp, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid2);
        long wo2 = id(mvc.perform(json(post("/api/production/wos"),
                "{\"plantCd\":\"P1\",\"itemCd\":\"RM\",\"planQty\":\"5\",\"planStart\":\"" + today + "\"}").with(admin)));
        mvc.perform(json(post("/api/production/wos/" + wo2 + "/release"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        other = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ?", Long.class, wo2);
        // 자재 LOT 100 KG
        long rm = runner.call(comp, () -> lots.receiveAs("RM", new BigDecimal("100"), null, null, "t"));
        rmLot = accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, rm);
        cookie = terminal("TAB-01", line1);
        s = new MockHttpSession();
        badge(cookie, s, "BADGE-0001").andExpect(status().isOk());
    }

    // ---------- 도우미 ----------

    private long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd + " 작업장");
    }

    private long worker(String cd, String nm, String badge) {
        return accounts.query(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, ?, ?, 'P1', ?, 't', 't') returning worker_id""", Long.class, comp, cd, nm, WorkerService.badgeHash(comp, badge));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    /** 단말 등록 + 활성화 → 쿠키 값 */
    private String terminal(String cd, Long nodeId) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\"" + (nodeId == null ? "" : ",\"nodeId\":" + nodeId) + "}").with(admin))
                .andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        MvcResult a = mvc.perform(json(post(P + "/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return set.substring(set.indexOf('=') + 1, set.indexOf(';'));
    }

    private ResultActions badge(String c, MockHttpSession session, String badge) throws Exception {
        return mvc.perform(json(post(P + "/badge"), "{\"badge\":\"" + badge + "\"}").cookie(new Cookie(PopAuthService.COOKIE, c))
                .session(session));
    }

    private ResultActions pop(MockHttpServletRequestBuilder b, String body) throws Exception {
        return mvc.perform(json(b, body).cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s));
    }

    private ResultActions popGet(String url) throws Exception {
        return mvc.perform(get(url).cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s));
    }

    private static String req() {
        return UUID.randomUUID().toString();
    }

    private ResultActions ev(long proc, String name, String extra) throws Exception {
        return pop(post(P + "/procs/" + proc + "/events/" + name), "{\"clientReqId\":\"" + req() + "\"" + extra + "}");
    }

    private ResultActions result(long proc, String reqId, String body) throws Exception {
        return pop(post(P + "/procs/" + proc + "/results"), "{\"clientReqId\":\"" + reqId + "\"," + body + "}");
    }

    private String procStatus(long proc) {
        return accounts.query(comp, "select status from prd_wo_proc where wo_proc_id = ?", String.class, proc);
    }

    private BigDecimal balance(String lotNo) {
        return accounts.query(comp, "select hb_lot_balance(lot_id) from lot_master where lot_no = ?", BigDecimal.class, lotNo);
    }

    private double resendCount() {
        io.micrometer.core.instrument.Counter c = registry.find("hb.pop.resend").tag("kind", "result").counter();
        return c == null ? 0 : c.count();
    }

    private int count(String sql, Object... args) {
        return accounts.query(comp, sql, Integer.class, args);
    }

    // ---------- 흐름 ----------

    @Test
    void 착수_파라미터_실적_투입_멈춤_재개_종료() throws Exception {
        // 자격 WARN → 확인 요구 → ack 로 착수(이벤트에 WARN)
        ev(spot, "start", "").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUAL_WARN_ACK"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("용접 1급")));
        assertThat(procStatus(spot)).isEqualTo("WAIT");
        ev(spot, "start", ",\"ack\":true").andExpect(status().isOk()).andExpect(jsonPath("$.data.procStatus").value("RUN"))
                .andExpect(jsonPath("$.data.gateMode").value("WARN"));
        assertThat(accounts.query(comp, "select status from prd_wo where wo_id = ?", String.class, woId)).isEqualTo("IN_PROGRESS");
        assertThat(count("select count(*) from prd_wo_proc_event where wo_proc_id = ? and event_cd = 'START' and gate_mode = 'WARN'"
                + " and recorded_by like 't%:TAB-01' and performed_by = ?", spot, workerId)).isEqualTo(1);
        ev(spot, "start", ",\"ack\":true").andExpect(jsonPath("$.errors[0].code").value("STATE"));

        // 공정 화면: 파라미터·BOM·불량·멈춤 사유
        popGet(P + "/procs/" + spot).andExpect(status().isOk()).andExpect(jsonPath("$.data.proc.status").value("RUN"))
                .andExpect(jsonPath("$.data.params[0].paramCd").value("WELD_CURRENT"))
                .andExpect(jsonPath("$.data.bom[0].itemCd").value("RM"))
                .andExpect(jsonPath("$.data.defects.length()").value(2))
                .andExpect(jsonPath("$.data.reasons[*].code").value(Matchers.hasItem("MAT_WAIT")));
        // 파라미터: 상하한 안 OK, 밖 NG, 없는 파라미터·형식
        pop(post(P + "/procs/" + spot + "/params"), "{\"clientReqId\":\"" + req() + "\",\"paramCd\":\"weld_current\",\"value\":\"9.2\"}")
                .andExpect(jsonPath("$.data.judge").value("OK"));
        pop(post(P + "/procs/" + spot + "/params"), "{\"clientReqId\":\"" + req() + "\",\"paramCd\":\"WELD_CURRENT\",\"value\":\"10\"}")
                .andExpect(jsonPath("$.data.judge").value("NG"));
        pop(post(P + "/procs/" + spot + "/params"), "{\"clientReqId\":\"" + req() + "\",\"paramCd\":\"NOPE\",\"value\":\"1e3\"}")
                .andExpect(jsonPath("$.errors[*].code").value(Matchers.contains("NO_SUCH_CODE", "FORMAT")));
        popGet(P + "/procs/" + spot).andExpect(jsonPath("$.data.params[0].lastValue").value("10"))
                .andExpect(jsonPath("$.data.params[0].lastJudge").value("NG"));

        // 실적: 양품 10 · 폐기 2(기공 2) + 자재 LOT 15 KG 투입
        String r1 = req();
        result(spot, r1, "\"goodQty\":\"10\",\"scrapQty\":\"2\",\"defects\":[{\"kind\":\"SCRAP\",\"defectCd\":\"d-pit\",\"qty\":\"2\"}],"
                + "\"inputs\":[{\"lotNo\":\"" + rmLot + "\",\"qty\":\"15\"}]").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.todayGood").value("10")).andExpect(jsonPath("$.data.planQty").value("100"))
                .andExpect(jsonPath("$.data.lastDefect").value("기공")).andExpect(jsonPath("$.data.replayed").value(false))
                .andExpect(jsonPath("$.data.lotNo").isNotEmpty());
        String outLot = accounts.query(comp, "select l.lot_no from prd_result r join lot_master l on l.lot_id = r.lot_id where r.client_req_id = ?",
                String.class, r1);
        assertThat(balance(outLot)).isEqualByComparingTo("10");
        assertThat(balance(rmLot)).isEqualByComparingTo("85");
        // 대리 입력이 드러난다: 기록 = 단말, 작업 = 작업자 (실적·투입 계보 모두)
        assertThat(count("select count(*) from prd_result where client_req_id = ? and recorded_by like 't%:TAB-01' and performed_by = ?"
                + " and entry_method = 'KEY' and work_day is not null", r1, workerId)).isEqualTo(1);
        assertThat(count("select count(*) from lot_genealogy g join lot_master p on p.lot_id = g.parent_lot_id where p.lot_no = ?"
                + " and g.rel_type = 'CONSUME' and g.entry_method = 'SCAN' and g.performed_by = ? and g.wo_proc_id = ?",
                rmLot, workerId, spot)).isEqualTo(1);

        // 멈춤: 사유 필수·없는 사유 → MAT_WAIT. 멈춤 중에도 실적은 된다
        ev(spot, "pause", "").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        ev(spot, "pause", ",\"reasonCd\":\"NOPE\"").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        ev(spot, "pause", ",\"reasonCd\":\"mat_wait\"").andExpect(jsonPath("$.data.procStatus").value("PAUSE"));
        result(spot, req(), "\"goodQty\":\"5\"").andExpect(jsonPath("$.data.todayGood").value("15"));
        ev(spot, "resume", "").andExpect(jsonPath("$.data.procStatus").value("RUN"));
        ev(spot, "end", "").andExpect(jsonPath("$.data.procStatus").value("READY"));
        // 종료 뒤 실적·파라미터 → STATE, 다시 착수 가능
        result(spot, req(), "\"goodQty\":\"1\"").andExpect(jsonPath("$.errors[0].code").value("STATE"));
        ev(spot, "start", ",\"ack\":true").andExpect(jsonPath("$.data.procStatus").value("RUN"));
        assertThat(count("select count(*) from prd_wo_proc_event where wo_proc_id = ?", spot)).isEqualTo(5);
        // 다음 공정은 앞 공정 LOT 을 투입으로(공정 LOT 사슬)
        ev(insp, "start", "").andExpect(jsonPath("$.data.gateMode").value("OK"));
        result(insp, req(), "\"goodQty\":\"8\",\"inputs\":[{\"lotNo\":\"" + outLot + "\",\"qty\":\"8\"}]").andExpect(status().isOk());
        assertThat(balance(outLot)).isEqualByComparingTo("2");
        // 홈: 상태 반영
        popGet(P + "/home").andExpect(jsonPath("$.data.tasks[?(@.procCd == 'SPOT')].procStatus").value(Matchers.contains("RUN")));
    }

    @Test
    void 같은_요청_id_는_처음_응답_그대로() throws Exception {
        ev(insp, "start", "").andExpect(status().isOk());
        String r = req();
        long first = ((Number) JsonPath.read(result(insp, r, "\"goodQty\":\"3\"").andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(), "$.data.resultId")).longValue();
        // 재전송(다른 내용이어도 처음 것) → 같은 행, replayed — 운영 지표 hb_pop_resend_total{kind=result} 정확히 +1(docs/52 Y2)
        double resendBefore = resendCount();
        result(insp, r, "\"goodQty\":\"99\"").andExpect(status().isOk()).andExpect(jsonPath("$.data.resultId").value(first))
                .andExpect(jsonPath("$.data.replayed").value(true)).andExpect(jsonPath("$.data.todayGood").value("3"));
        assertThat(resendCount() - resendBefore).isEqualTo(1.0);
        // 같은 id 를 다른 공정에 → 409
        ev(spot, "start", ",\"ack\":true").andExpect(status().isOk());
        result(spot, r, "\"goodQty\":\"1\"").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        // 동시에 두 번(같은 id) → 둘 다 200·같은 행, 행 1개 ×3회
        for (int round = 0; round < 3; round++) {
            String same = req();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<String> c = () -> result(insp, same, "\"goodQty\":\"1\"").andReturn().getResponse().getContentAsString();
                List<Future<String>> fs = pool.invokeAll(List.of(c, c));
                Object a = JsonPath.read(fs.get(0).get(), "$.data.resultId");
                Object b = JsonPath.read(fs.get(1).get(), "$.data.resultId");
                assertThat(a).isEqualTo(b);
            } finally {
                pool.shutdown();
            }
            assertThat(count("select count(*) from prd_result where client_req_id = ?", same)).isEqualTo(1);
        }
        assertThat(count("select count(*) from lot_master where wo_id = ?", woId)).isEqualTo(4);
        // 이벤트 재전송
        String e = req();
        long ev1 = ((Number) JsonPath.read(pop(post(P + "/procs/" + insp + "/events/end"), "{\"clientReqId\":\"" + e + "\"}")
                .andReturn().getResponse().getContentAsString(), "$.data.eventId")).longValue();
        pop(post(P + "/procs/" + insp + "/events/end"), "{\"clientReqId\":\"" + e + "\"}").andExpect(jsonPath("$.data.eventId").value(ev1))
                .andExpect(jsonPath("$.data.replayed").value(true)).andExpect(jsonPath("$.data.procStatus").value("READY"));
        // 요청 id 형식
        result(insp, "short", "\"goodQty\":\"1\"").andExpect(jsonPath("$.errors[0].field").value("clientReqId"));
    }

    @Test
    void 오프라인_재전송은_발생_시각을_지킨다() throws Exception {
        ev(insp, "start", "").andExpect(status().isOk());
        // 음영에 갇혔던 확정: 발행 직후 시각(단말이 누른 순간)을 지금 보낸다 → 그 시각 그대로, 기록 시각은 서버 지금
        Instant released = accounts.query(comp, "select released_at from prd_wo where wo_id = ?", java.sql.Timestamp.class, woId).toInstant();
        Instant past = released.plusMillis(1);
        String r = req();
        result(insp, r, "\"occurredAt\":\"" + past + "\",\"goodQty\":\"2\"").andExpect(status().isOk());
        assertThat(count("select count(*) from prd_result where client_req_id = ? and occurred_at = ?::timestamptz and recorded_at > occurred_at",
                r, past.toString())).isEqualTo(1);
        // 같은 요청이 한 번 더(대기열 재전송) → 그대로
        result(insp, r, "\"occurredAt\":\"" + past + "\",\"goodQty\":\"2\"").andExpect(jsonPath("$.data.replayed").value(true));
        // 미래(허용 300초 밖)·발행 전·형식
        result(insp, req(), "\"occurredAt\":\"" + Instant.now().plusSeconds(900) + "\",\"goodQty\":\"1\"")
                .andExpect(jsonPath("$.errors[0].code").value("FUTURE"));
        result(insp, req(), "\"occurredAt\":\"2001-01-01T00:00:00Z\",\"goodQty\":\"1\"").andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        result(insp, req(), "\"occurredAt\":\"어제\",\"goodQty\":\"1\"").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
    }

    @Test
    void 실적_검증() throws Exception {
        ev(spot, "start", ",\"ack\":true").andExpect(status().isOk());
        result(spot, req(), "\"goodQty\":\"0\"").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        result(spot, req(), "\"goodQty\":\"1.5\",\"scrapQty\":\"-1\"").andExpect(jsonPath("$.errors[*].code").value(Matchers.contains("SCALE", "NOT_NEGATIVE")));
        // 불량 내역: 없음·합 다름·종류·없는 유형·중복
        result(spot, req(), "\"goodQty\":\"1\",\"scrapQty\":\"2\"").andExpect(jsonPath("$.errors[0].field").value("scrapQty"))
                .andExpect(jsonPath("$.errors[0].code").value("DEFECT_SUM"));
        result(spot, req(), "\"goodQty\":\"1\",\"reworkQty\":\"2\",\"defects\":[{\"kind\":\"REWORK\",\"defectCd\":\"D-PIT\",\"qty\":\"1\"}]")
                .andExpect(jsonPath("$.errors[0].field").value("reworkQty"));
        result(spot, req(), "\"goodQty\":\"1\",\"defects\":[{\"kind\":\"LOST\",\"defectCd\":\"D-PIT\",\"qty\":\"1\"},"
                + "{\"kind\":\"SCRAP\",\"defectCd\":\"NOPE\",\"qty\":\"1\"}]")
                .andExpect(jsonPath("$.errors[*].code").value(Matchers.contains("FORMAT", "NO_SUCH_CODE")));
        result(spot, req(), "\"goodQty\":\"1\",\"scrapQty\":\"2\",\"defects\":[{\"kind\":\"SCRAP\",\"defectCd\":\"D-PIT\",\"qty\":\"1\"},"
                + "{\"kind\":\"SCRAP\",\"defectCd\":\"D-PIT\",\"qty\":\"1\"}]").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        // 두 유형으로 나눈 폐기는 된다
        result(spot, req(), "\"goodQty\":\"1\",\"scrapQty\":\"3\",\"defects\":[{\"kind\":\"SCRAP\",\"defectCd\":\"D-PIT\",\"qty\":\"1\"},"
                + "{\"kind\":\"SCRAP\",\"defectCd\":\"D-BURR\",\"qty\":\"2\"}]").andExpect(status().isOk());
        // 투입: 양품 없이·없는 LOT·같은 LOT 두 번·잔량 초과·보류 LOT·자릿수
        result(spot, req(), "\"scrapQty\":\"1\",\"defects\":[{\"kind\":\"SCRAP\",\"defectCd\":\"D-PIT\",\"qty\":\"1\"}],"
                + "\"inputs\":[{\"lotNo\":\"" + rmLot + "\",\"qty\":\"1\"}]").andExpect(jsonPath("$.errors[0].code").value("INPUT_NEEDS_GOOD"));
        result(spot, req(), "\"goodQty\":\"1\",\"inputs\":[{\"lotNo\":\"NO-LOT\",\"qty\":\"1\"},{\"lotNo\":\"" + rmLot + "\",\"qty\":\"1\"},"
                + "{\"lotNo\":\"" + rmLot + "\",\"qty\":\"1\"}]").andExpect(jsonPath("$.errors[*].code").value(Matchers.contains("NO_SUCH_CODE", "DUPLICATE")))
                .andExpect(jsonPath("$.errors[*].rowKey").value(Matchers.contains("0", "2")));
        result(spot, req(), "\"goodQty\":\"1\",\"inputs\":[{\"lotNo\":\"" + rmLot + "\",\"qty\":\"100.5\"}]")
                .andExpect(jsonPath("$.errors[0].code").value("OVER_BALANCE")).andExpect(jsonPath("$.errors[0].rowKey").value("0"));
        result(spot, req(), "\"goodQty\":\"1\",\"inputs\":[{\"lotNo\":\"" + rmLot + "\",\"qty\":\"1.0000001\"}]")
                .andExpect(jsonPath("$.errors[0].code").value("SCALE"));
        accounts.exec(comp, "insert into lot_hold (comp_cd, lot_id, source, reason, held_by) select comp_cd, lot_id, 'MANUAL', '시험', 't' from lot_master where lot_no = ?", rmLot);
        result(spot, req(), "\"goodQty\":\"1\",\"inputs\":[{\"lotNo\":\"" + rmLot + "\",\"qty\":\"1\"}]")
                .andExpect(jsonPath("$.errors[0].code").value("LOT_HOLD")).andExpect(jsonPath("$.errors[0].field").value("lotNo"));
        // 실패한 요청은 아무것도 남기지 않는다(산출 LOT·계보·실적 — 결번 없음)
        assertThat(count("select count(*) from prd_result where wo_proc_id = ?", spot)).isEqualTo(1);
        assertThat(count("select count(*) from lot_master where wo_id = ?", woId)).isEqualTo(1);
        assertThat(balance(rmLot)).isEqualByComparingTo("100");
        // 투입 스캔 확인
        popGet(P + "/lots?lotNo=" + rmLot).andExpect(jsonPath("$.data.balance").value("100")).andExpect(jsonPath("$.data.holdYn").value("Y"));
        popGet(P + "/lots?lotNo=NONE").andExpect(status().isNotFound());
    }

    @Test
    void 정정은_역분개_투입_되돌림_LOT_보류() throws Exception {
        // 공정에 설비 배정 — 실적·투입 계보가 설비를 갖고, 정정(역분개) 행도 같은 맥락을 갖는지(docs/25 4.5, V110)
        accounts.exec(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'WELDER', '용접기', 't', 't')",
                comp);
        long eq = accounts.query(comp, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                select ?, 'P1', 'EQ-W1', 'EQ-W1 용접기', class_id, 't', 't' from mst_equip_class where class_cd = 'WELDER' returning equip_id""",
                Long.class, comp);
        accounts.exec(comp, "update prd_wo_proc set equip_id = ? where wo_proc_id = ?", eq, spot);
        ev(spot, "start", ",\"ack\":true").andExpect(status().isOk());
        String r1 = req();
        result(spot, r1, "\"goodQty\":\"10\",\"reworkQty\":\"1\",\"defects\":[{\"kind\":\"REWORK\",\"defectCd\":\"D-BURR\",\"qty\":\"1\"}],"
                + "\"inputs\":[{\"lotNo\":\"" + rmLot + "\",\"qty\":\"15\"}]").andExpect(status().isOk());
        long rid = accounts.query(comp, "select result_id from prd_result where client_req_id = ?", Long.class, r1);
        String outLot = accounts.query(comp, "select l.lot_no from prd_result r join lot_master l on l.lot_id = r.lot_id where r.result_id = ?",
                String.class, rid);
        pop(post(P + "/results/" + rid + "/correct"), "{\"reason\":\" \"}").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        long rev = id(pop(post(P + "/results/" + rid + "/correct"), "{\"reason\":\"수량 오입력\"}"));
        assertThat(count("select count(*) from prd_result where result_id = ? and reversal_of_id = ? and good_qty = -10 and rework_qty = -1"
                + " and entry_method = 'CORRECT' and reason = '수량 오입력'", rev, rid)).isEqualTo(1);
        assertThat(count("select count(*) from prd_result_defect where result_id = ? and qty = -1 and kind = 'REWORK'", rev)).isEqualTo(1);
        // 역분개 행 맥락 = 원래 행(설비), 발생 시각은 정정 시각(복사 안 함)
        assertThat(count("select count(*) from prd_result where result_id = ? and equip_id = ?", rev, eq)).isEqualTo(1);
        assertThat(count("select count(*) from prd_result o join prd_result v on v.reversal_of_id = o.result_id"
                + " where o.result_id = ? and v.occurred_at > o.occurred_at", rid)).isEqualTo(1);
        // 투입 계보 역분개도 지시·공정·설비를 원래 행과 같게
        assertThat(count("""
                select count(*) from lot_genealogy v join lot_genealogy o on o.gen_id = v.reversal_of_id
                 where o.wo_proc_id = ? and v.wo_id = o.wo_id and v.wo_proc_id = o.wo_proc_id and v.equip_id = ?""", spot, eq)).isEqualTo(1);
        assertThat(balance(rmLot)).isEqualByComparingTo("100");
        assertThat(accounts.query(comp, "select hold_yn from lot_master where lot_no = ?", String.class, outLot)).isEqualTo("Y");
        // 보류 근거 = 이 실적(MES_CORRECT, docs/25 4.19) — 다른 근거와 따로 풀린다
        assertThat(accounts.query(comp, """
                select h.source || '/' || h.ref_id || '/' || h.reason from lot_hold h join lot_master l on l.lot_id = h.lot_id
                 where l.lot_no = ? and h.released_at is null""", String.class, outLot)).isEqualTo("MES_CORRECT/" + rid + "/수량 오입력");
        // 원래 행은 그대로, 오늘 합은 0, 두 번째 정정·역분개 행 정정 거부
        assertThat(count("select count(*) from prd_result where result_id = ? and good_qty = 10", rid)).isEqualTo(1);
        popGet(P + "/procs/" + spot).andExpect(jsonPath("$.data.todayGood").value("0"))
                .andExpect(jsonPath("$.data.today[0].reversed").value(true));
        pop(post(P + "/results/" + rid + "/correct"), "{\"reason\":\"x\"}").andExpect(jsonPath("$.errors[0].code").value("STATE"));
        pop(post(P + "/results/" + rev + "/correct"), "{\"reason\":\"x\"}").andExpect(status().isNotFound());
        // 뒤 공정이 쓴 LOT 의 실적은 정정 거부
        String r2 = req();
        result(spot, r2, "\"goodQty\":\"6\"").andExpect(status().isOk());
        String lot2 = accounts.query(comp, "select l.lot_no from prd_result r join lot_master l on l.lot_id = r.lot_id where r.client_req_id = ?",
                String.class, r2);
        ev(insp, "start", "").andExpect(status().isOk());
        result(insp, req(), "\"goodQty\":\"4\",\"inputs\":[{\"lotNo\":\"" + lot2 + "\",\"qty\":\"4\"}]").andExpect(status().isOk());
        long rid2 = accounts.query(comp, "select result_id from prd_result where client_req_id = ?", Long.class, r2);
        pop(post(P + "/results/" + rid2 + "/correct"), "{\"reason\":\"x\"}").andExpect(jsonPath("$.errors[0].code").value("LOT_USED"));
        // 파라미터 정정: 둘 다 조회에서 빠짐
        long a = ((Number) JsonPath.read(pop(post(P + "/procs/" + spot + "/params"), "{\"clientReqId\":\"" + req()
                + "\",\"paramCd\":\"WELD_CURRENT\",\"value\":\"7\"}").andReturn().getResponse().getContentAsString(), "$.data.actualId")).longValue();
        pop(post(P + "/params/" + a + "/correct"), "{\"reason\":\"센서 오독\"}").andExpect(status().isOk());
        pop(post(P + "/params/" + a + "/correct"), "{\"reason\":\"x\"}").andExpect(jsonPath("$.errors[0].code").value("STATE"));
        popGet(P + "/procs/" + spot).andExpect(jsonPath("$.data.params[0].lastValue").doesNotExist());
        // 직접 SQL: 설비를 비운 역분개 → 맥락 검사(새 실적 하나에)
        String rx = req();
        result(spot, rx, "\"goodQty\":\"0\",\"scrapQty\":\"1\",\"defects\":[{\"kind\":\"SCRAP\",\"defectCd\":\"D-BURR\",\"qty\":\"1\"}]")
                .andExpect(status().isOk());
        long ridx = accounts.query(comp, "select result_id from prd_result where client_req_id = ?", Long.class, rx);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into prd_result (comp_cd, wo_id, wo_proc_id, lot_id, equip_id, good_qty, scrap_qty, rework_qty, repair_qty, unit_cd,
                                        work_day, reason, occurred_at, entry_method, recorded_by, reversal_of_id)
                select comp_cd, wo_id, wo_proc_id, lot_id, null, -good_qty, -scrap_qty, -rework_qty, -repair_qty, unit_cd, work_day,
                       '설비 비움', now(), 'CORRECT', 'x', result_id
                  from prd_result where result_id = ?""", ridx)))).contains("ck_prd_result_reversal_ctx");
    }

    @Test
    void 두_단말이_동시에_착수하면_한쪽만() throws Exception {
        String c2 = terminal("TAB-02", line1);
        MockHttpSession s2 = new MockHttpSession();
        long w2 = worker("E2", "김작업", "BADGE-0002");
        badge(c2, s2, "BADGE-0002").andExpect(status().isOk());
        for (int round = 0; round < 3; round++) {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Integer> a = () -> mvc.perform(json(post(P + "/procs/" + insp + "/events/start"), "{\"clientReqId\":\"" + req() + "\"}")
                        .cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s)).andReturn().getResponse().getStatus();
                Callable<Integer> b = () -> mvc.perform(json(post(P + "/procs/" + insp + "/events/start"), "{\"clientReqId\":\"" + req() + "\"}")
                        .cookie(new Cookie(PopAuthService.COOKIE, c2)).session(s2)).andReturn().getResponse().getStatus();
                List<Future<Integer>> fs = pool.invokeAll(List.of(a, b));
                assertThat(List.of(fs.get(0).get(), fs.get(1).get())).containsExactlyInAnyOrder(200, 400);
            } finally {
                pool.shutdown();
            }
            ev(insp, "end", "").andExpect(status().isOk());
        }
        assertThat(count("select count(*) from prd_wo_proc_event where wo_proc_id = ? and event_cd = 'START'", insp)).isEqualTo(3);
        assertThat(w2).isPositive();
    }

    @Test
    void 작업자_없음_작업장_밖_A사로_B사() throws Exception {
        // 작업자 없음 → 428
        mvc.perform(json(post(P + "/procs/" + insp + "/events/start"), "{\"clientReqId\":\"" + req() + "\"}")
                .cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(new MockHttpSession()))
                .andExpect(status().is(428)).andExpect(jsonPath("$.code").value("POP_NO_WORKER"));
        // 작업장(L1) 밖 공정·없는 동작
        popGet(P + "/procs/" + other).andExpect(status().isNotFound());
        ev(other, "start", "").andExpect(status().isNotFound());
        ev(insp, "fly", "").andExpect(status().isNotFound());
        // B사 단말: A사 공정·실적·LOT
        ev(insp, "start", "").andExpect(status().isOk());
        String r = req();
        result(insp, r, "\"goodQty\":\"2\"").andExpect(status().isOk());
        long rid = accounts.query(comp, "select result_id from prd_result where client_req_id = ?", Long.class, r);
        String a = comp;
        comp = accounts.newComp("PX");
        long bu = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, bu, "ADM", "sys-terminal", "SEARCH", "SAVE");
        admin = accounts.as(comp, bu, "admin1");
        accounts.addPlant(comp, "P1", "B 1공장");
        String cb = terminal("TAB-B", null);
        worker("E1", "B작업", "BADGE-0001");
        MockHttpSession sb = new MockHttpSession();
        badge(cb, sb, "BADGE-0001").andExpect(status().isOk());
        mvc.perform(get(P + "/procs/" + insp).cookie(new Cookie(PopAuthService.COOKIE, cb)).session(sb)).andExpect(status().isNotFound());
        mvc.perform(json(post(P + "/procs/" + insp + "/results"), "{\"clientReqId\":\"" + req() + "\",\"goodQty\":\"1\"}")
                .cookie(new Cookie(PopAuthService.COOKIE, cb)).session(sb)).andExpect(status().isNotFound());
        mvc.perform(json(post(P + "/results/" + rid + "/correct"), "{\"reason\":\"x\"}").cookie(new Cookie(PopAuthService.COOKIE, cb)).session(sb))
                .andExpect(status().isNotFound());
        mvc.perform(get(P + "/lots").param("lotNo", rmLot).cookie(new Cookie(PopAuthService.COOKIE, cb)).session(sb))
                .andExpect(status().isNotFound());
        // A사 요청 id 를 B사가 써도 B사 범위에서는 새 요청(멱등은 회사 안)
        String b = comp;
        assertThat(accounts.query(b, "select count(*) from prd_result where client_req_id = ?", Integer.class, r)).isZero();
        // 직접 SQL(B사 범위): A사 공정에 실적·이벤트
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(b, """
                insert into prd_wo_proc_event (comp_cd, wo_id, wo_proc_id, event_cd, gate_mode, work_day, client_req_id, occurred_at,
                                               entry_method, recorded_by, performed_by)
                select ?, ?, ?, 'START', 'OK', (now() at time zone 'Asia/Seoul')::date, 'REQ-00000001', now(), 'KEY', 'x', worker_id from mst_worker""",
                b, woId, insp)))).contains("fk_prd_wo_proc_event_ctx_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(b, """
                insert into prd_result (comp_cd, wo_id, wo_proc_id, good_qty, scrap_qty, rework_qty, repair_qty, unit_cd, work_day,
                                        client_req_id, occurred_at, entry_method, recorded_by, performed_by)
                select ?, ?, ?, 0, 1, 0, 0, 'EA', (now() at time zone 'Asia/Seoul')::date, 'REQ-00000002', now(), 'KEY', 'x', worker_id from mst_worker""",
                b, woId, insp)))).contains("fk_prd_result_ctx_comp");
        comp = a;
    }

    @Test
    void 원장_직접_SQL() throws Exception {
        ev(spot, "start", ",\"ack\":true").andExpect(status().isOk());
        String r = req();
        result(spot, r, "\"goodQty\":\"3\",\"scrapQty\":\"1\",\"defects\":[{\"kind\":\"SCRAP\",\"defectCd\":\"D-PIT\",\"qty\":\"1\"}]")
                .andExpect(status().isOk());
        long rid = accounts.query(comp, "select result_id from prd_result where client_req_id = ?", Long.class, r);
        pop(post(P + "/procs/" + spot + "/params"), "{\"clientReqId\":\"" + req() + "\",\"paramCd\":\"WELD_CURRENT\",\"value\":\"9\"}")
                .andExpect(status().isOk());
        // 원장 UPDATE·DELETE → 42501(권한 회수) ×8
        for (String t : new String[] {"prd_wo_proc_event", "prd_result", "prd_result_defect", "prd_param_actual"}) {
            assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "update " + t + " set comp_cd = comp_cd")))).contains("42501");
            assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from " + t)))).contains("42501");
        }
        // 공정 상태: 이벤트 없이·표 밖 전이
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update prd_wo_proc set status = 'PAUSE' where wo_proc_id = ?", spot)))).contains("ck_prd_wo_proc_event_required");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update prd_wo_proc set status = 'DONE' where wo_proc_id = ?", spot)))).contains("ck_prd_wo_proc_transition");
        // 진행 중 아닌 공정에 실적, 불량 합 다름(커밋 때), 역분개 거울 아님, 양품 있는데 LOT 없음
        String ins = """
                insert into prd_result (comp_cd, wo_id, wo_proc_id, lot_id, good_qty, scrap_qty, rework_qty, repair_qty, unit_cd, work_day,
                                        client_req_id, reason, occurred_at, entry_method, recorded_by, performed_by, reversal_of_id)
                values (?, ?, ?, ?, ?, ?, 0, 0, 'EA', (now() at time zone 'Asia/Seoul')::date, ?, ?, now(), ?, 'x', ?, ?)""";
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, woId, insp, null, 0, 1, "REQ-00000011", null,
                "KEY", workerId, null)))).contains("ck_prd_result_proc_running");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, woId, spot, null, 0, 1, "REQ-00000012", null,
                "KEY", workerId, null)))).contains("ck_prd_result_defect_sum");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, woId, spot, null, 1, 0, "REQ-00000013", null,
                "KEY", workerId, null)))).contains("ck_prd_result_lot");
        long lotId = accounts.query(comp, "select lot_id from prd_result where result_id = ?", Long.class, rid);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, woId, spot, lotId, -2, -1, null, "x",
                "CORRECT", workerId, rid)))).contains("ck_prd_result_reversal");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, woId, spot, null, 0, 1, "REQ-00000014", null,
                "CORRECT", workerId, null)))).contains("ck_prd_result_qty");
        // 사유 없는(NULL) 역분개 — 처음 제약은 NULL 을 통과시켰다(6단계에서 발견, 사유 제약 추가)
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, woId, spot, lotId, -3, -1, null, null,
                "CORRECT", workerId, rid)))).contains("ck_prd_result_reversal_reason");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into prd_param_actual (comp_cd, wo_proc_id, wo_param_id, param_cd, value, judge, work_day, occurred_at, entry_method,
                                              recorded_by, reversal_of_id)
                select comp_cd, wo_proc_id, wo_param_id, param_cd, value, judge, work_day, now(), 'CORRECT', 'x', actual_id
                  from prd_param_actual where wo_proc_id = ?""", spot)))).contains("ck_prd_param_actual_reversal_reason");
        // 불량 내역 부호, 이벤트 사유·게이트 규칙
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into prd_result_defect (comp_cd, result_id, kind, defect_id, qty) select ?, ?, 'SCRAP', defect_id, -1 from qms_defect where defect_cd = 'D-PIT'",
                comp, rid)))).contains("ck_prd_result_defect_sign");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into prd_wo_proc_event (comp_cd, wo_id, wo_proc_id, event_cd, work_day, client_req_id, occurred_at, entry_method,
                                               recorded_by, performed_by)
                values (?, ?, ?, 'PAUSE', (now() at time zone 'Asia/Seoul')::date, 'REQ-00000021', now(), 'KEY', 'x', ?)""", comp, woId, spot, workerId))))
                .contains("ck_prd_wo_proc_event_reason");
        // 파라미터: 다른 공정의 파라미터
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into prd_param_actual (comp_cd, wo_proc_id, wo_param_id, param_cd, value, judge, work_day, client_req_id, occurred_at,
                                              entry_method, recorded_by)
                select ?, ?, wo_param_id, param_cd, 1, 'OK', (now() at time zone 'Asia/Seoul')::date, 'REQ-00000031', now(), 'KEY', 'x' from prd_wo_param
                 where wo_proc_id = ?""", comp, insp, spot)))).contains("fk_prd_param_actual_ctx_comp");
    }

    // ---------- WMS B5 연결 MES 몫(docs/25 4.20 ②③) ----------

    @Test
    void 투입_중_칸_오류_없는_거부는_원인_그대로_나간다_재고_부족_409() throws Exception {
        ev(spot, "start", ",\"ack\":true").andExpect(status().isOk());
        // WMS 훅(onEdge)이 재고 부족을 던진 것과 같은 모양 — 칸 오류 없는 BusinessException
        Mockito.doThrow(new BusinessException(HbErrorCode.INSUFFICIENT_STOCK)).when(lots).consume(ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
        result(spot, req(), "\"goodQty\":\"1\",\"inputs\":[{\"lotNo\":\"" + rmLot + "\",\"qty\":\"1\"}]")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        // 실적·산출 LOT·계보 모두 롤백
        assertThat(count("select count(*) from prd_result where wo_proc_id = ?", spot)).isZero();
        assertThat(count("select count(*) from lot_master where wo_id = ?", woId)).isZero();
        assertThat(balance(rmLot)).isEqualByComparingTo("100");
        // 칸 오류가 있는 거부(잔량 초과)는 지금처럼 투입 행 칸 오류
        Mockito.reset(lots);
        result(spot, req(), "\"goodQty\":\"1\",\"inputs\":[{\"lotNo\":\"" + rmLot + "\",\"qty\":\"100.5\"}]")
                .andExpect(jsonPath("$.errors[0].code").value("OVER_BALANCE")).andExpect(jsonPath("$.errors[0].rowKey").value("0"));
    }

    @Test
    void 정정은_투입_LOT_과_산출_LOT_을_LOT_id_순으로_먼저_잠근다() throws Exception {
        // 두 번째 자재 LOT — 실적 투입 순서는 [B, A](계보 id 순 = B → A), LOT id 는 A < B
        long rmB = runner.call(comp, () -> lots.receiveAs("RM", new BigDecimal("100"), null, null, "t"));
        String rmBNo = accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, rmB);
        long rmA = accounts.query(comp, "select lot_id from lot_master where lot_no = ?", Long.class, rmLot);
        assertThat(rmA).isLessThan(rmB);
        ev(spot, "start", ",\"ack\":true").andExpect(status().isOk());
        String r1 = req();
        result(spot, r1, "\"goodQty\":\"5\",\"inputs\":[{\"lotNo\":\"" + rmBNo + "\",\"qty\":\"2\"},{\"lotNo\":\"" + rmLot
                + "\",\"qty\":\"3\"}]").andExpect(status().isOk());
        long rid = accounts.query(comp, "select result_id from prd_result where client_req_id = ?", Long.class, r1);
        // 다른 트랜잭션이 LOT A 를 잡고 있는 동안 정정 → A 에서 기다려야 하고, 그 사이 B 는 아직 안 잡았어야 한다
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = pool.submit(() -> runner.run(comp, () -> {
                jdbc.queryForObject("select lot_id from lot_master where lot_id = ? for update", Long.class, rmA);
                held.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> correcting = pool.submit(() -> pop(post(P + "/results/" + rid + "/correct"), "{\"reason\":\"순서 시험\"}")
                    .andReturn().getResponse().getStatus());
            // 정정이 잠금 대기에 들어갈 때까지
            for (int i = 0; i < 100 && count("select count(*) from pg_stat_activity where wait_event_type = 'Lock'") == 0; i++) {
                Thread.sleep(50);
            }
            assertThat(count("select count(*) from pg_stat_activity where wait_event_type = 'Lock'")).isPositive();
            // B 는 아직 아무도 잡지 않았다(옛 코드는 계보 순서로 B 를 먼저 잡고 A 에서 기다렸다)
            Boolean bFree = runner.call(comp, () -> {
                try {
                    jdbc.queryForObject("select lot_id from lot_master where lot_id = ? for update nowait", Long.class, rmB);
                    return true;
                } catch (org.springframework.dao.DataAccessException e) {
                    return false;
                }
            });
            assertThat(bFree).isTrue();
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(correcting.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(balance(rmLot)).isEqualByComparingTo("100");
        assertThat(balance(rmBNo)).isEqualByComparingTo("100");
    }


    // ---------- 착수 게이트 확장점 StartGate (docs/25 4.21, docs/29_P3 12.9) ----------

    private static GateVerdict warn(String code, Long lotId) {
        return new GateVerdict(StartGate.WARN, List.of(new GateReason(code, "pop.gate.qual", List.of("Q-" + code), "LOT", lotId)));
    }

    private String lastStart(String col) {
        return accounts.query(comp, "select " + col + " from prd_wo_proc_event where wo_proc_id = ? and event_cd = 'START' order by event_id desc limit 1",
                String.class, spot);
    }

    @Test
    void 착수_게이트_가장_나쁜_mode_자격과_같은_한_번의_확인_경고_근거는_코드로_남는다() throws Exception {
        // 미리보기(공정 화면): 게이트 경고가 보이고 preview = true, 맥락 값
        QMS_GATE.set(c -> warn("QMS_FIRST_PENDING", 12L));
        popGet(P + "/procs/" + spot).andExpect(jsonPath("$.data.startGate.mode").value("WARN"))
                .andExpect(jsonPath("$.data.startGate.reasons[0].source").value("QMS"))
                .andExpect(jsonPath("$.data.startGate.reasons[0].code").value("QMS_FIRST_PENDING"))
                .andExpect(jsonPath("$.data.startGate.reasons[0].message").value(Matchers.containsString("Q-QMS_FIRST_PENDING")));
        StartContext pv = SEEN.get(0);
        assertThat(pv.preview()).isTrue();
        assertThat(pv.woProcId()).isEqualTo(spot);
        assertThat(pv.woId()).isEqualTo(woId);
        assertThat(pv.workerId()).isEqualTo(workerId);
        assertThat(pv.itemId()).isEqualTo(accounts.query(comp, "select item_id from prd_wo where wo_id = ?", Long.class, woId));
        // 착수: 자격 WARN + 게이트 WARN → 한 번의 확인(START_GATE_WARN_ACK, 두 이유 한 문구)
        SEEN.clear();
        ev(spot, "start", "").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("START_GATE_WARN_ACK"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("Q-QMS_FIRST_PENDING")))
                .andExpect(jsonPath("$.message").value(Matchers.anyOf(Matchers.containsString("WELD"), Matchers.containsString("용접"))));
        assertThat(SEEN.get(0).preview()).isFalse();
        assertThat(procStatus(spot)).isEqualTo("WAIT");
        // 다른 빈이 BLOCK → 가장 나쁜 mode, 확인해도 거부, 이벤트 없음
        AUX_GATE.set(c -> new GateVerdict(StartGate.BLOCK, List.of(new GateReason("AUX_STOP", null, List.of(), null, null))));
        ev(spot, "start", ",\"ack\":true").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("START_GATE_BLOCKED"))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("AUX_STOP")));
        assertThat(count("select count(*) from prd_wo_proc_event where wo_proc_id = ?", spot)).isZero();
        // BLOCK 이 풀리면 확인 뒤 착수 — 근거는 코드로(QUAL:WELD, QMS:QMS_FIRST_PENDING(LOT 12))
        AUX_GATE.set(c -> GateVerdict.PASS);
        ev(spot, "start", ",\"ack\":true").andExpect(status().isOk());
        assertThat(lastStart("gate_mode")).isEqualTo("WARN");
        assertThat(lastStart("gate_detail")).isEqualTo("QUAL:WELD, QMS:QMS_FIRST_PENDING(LOT 12)");
    }

    @Test
    void 착수_게이트_오류는_세이브포인트로_되돌리고_정책대로_기본_BLOCK_WARN_이면_확인_뒤_착수() throws Exception {
        // 빈이 SQL 오류(0 나누기) — 세이브포인트가 없으면 착수 트랜잭션이 aborted 가 되어 이벤트 INSERT 까지 실패한다
        QMS_GATE.set(c -> {
            jdbc.queryForObject("select 1 / 0", Integer.class);
            return GateVerdict.PASS;
        });
        AUX_GATE.set(c -> new GateVerdict("MAYBE", List.of()));     // 모르는 mode 도 오류로
        ev(spot, "start", ",\"ack\":true").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("START_GATE_BLOCKED"));
        assertThat(procStatus(spot)).isEqualTo("WAIT");
        runner.run(comp, () -> policies.set("START_GATE_ON_ERROR", null, "WARN", "t"));
        ev(spot, "start", "").andExpect(jsonPath("$.code").value("START_GATE_WARN_ACK"));
        ev(spot, "start", ",\"ack\":true").andExpect(status().isOk());
        assertThat(procStatus(spot)).isEqualTo("RUN");
        assertThat(lastStart("gate_detail")).isEqualTo("QUAL:WELD, QMS:GATE_ERROR, AUX:GATE_ERROR");
        // 직접 SQL: OK 착수에 근거 / 다른 이벤트에 근거 → CHECK
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into prd_wo_proc_event (comp_cd, wo_id, wo_proc_id, event_cd, gate_mode, gate_detail, work_day, client_req_id, occurred_at,
                                               entry_method, recorded_by, performed_by)
                values (?, ?, ?, 'START', 'OK', 'X:Y', (now() at time zone 'Asia/Seoul')::date, 'REQ-GATE-1', now(), 'KEY', 'x', ?)""",
                comp, woId, insp, workerId)))).contains("ck_prd_wo_proc_event_gate_detail");
    }

}
