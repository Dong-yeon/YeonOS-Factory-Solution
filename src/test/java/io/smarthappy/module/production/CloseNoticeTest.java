package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.master.workforce.WorkerService;
import io.smarthappy.module.pop.PopAuthService;
import io.smarthappy.module.production.link.CloseNotice;
import io.smarthappy.module.production.link.CloseNotice.CloseContext;
import io.smarthappy.module.production.link.CloseNotice.Notice;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * 완료판정 경고 확장점 CloseNotice (docs/25 4.46) — 시험용 빈이 경고를 내면 미리보기(지시 완료판정 목록·close-check)와 판정(close)
 * 응답에 notices 로 오고, 판정은 막히지 않는다. 빈 예외(SQL 오류 포함)는 무시하고 판정 성공, 빈이 없을 때(빈 목록)는 PostgresTestBase
 * 공용 문맥의 다른 시험(CompletionReceiptTest 등)이 notices = [] 로 덮는다 — 여기서는 빈이 "경고 없음"을 돌려주는 경우로 본다.
 * 이상 사용: A사로 B사 공정(404, 빈 안 불림), 빈은 부른 회사 범위에서만 돈다.
 */
@AutoConfigureMockMvc
@Import(CloseNoticeTest.Config.class)
class CloseNoticeTest extends PostgresTestBase {

    private static final String PW = "Cedar-lantern-41";
    private static final String WO = "/api/production/wos";

    /** 시험용 빈 동작 — OFF = 경고 없음, WARN = LOT 마다 경고, THROW = 실행 오류, SQL = SQL 오류(세이브포인트 확인) */
    enum Mode { OFF, WARN, THROW, SQL }

    static volatile Mode mode = Mode.OFF;
    /** 부른 문맥 + 그때 회사 범위 */
    static final List<Object[]> calls = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Config {

        @Bean
        CloseNotice testCloseNotice(JdbcTemplate jdbc) {
            return ctx -> {
                String compNow = jdbc.queryForObject("select hb_current_comp()", String.class);
                calls.add(new Object[] {ctx, compNow});
                return switch (mode) {
                    case OFF -> List.of();
                    case WARN -> ctx.outputLotIds().stream()
                            .map(id -> new Notice("ZT_FINAL_PENDING", "pop.gate.error", List.of("ZT-" + id), "LOT", id)).toList();
                    case THROW -> throw new IllegalStateException("zt boom");
                    case SQL -> {
                        jdbc.queryForObject("select 1 / 0", Integer.class);
                        yield List.of();
                    }
                };
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    long woId;
    long spot;
    long itemId;
    long procId;
    String cookie;
    MockHttpSession s;

    @BeforeEach
    void setUp() throws Exception {
        mode = Mode.OFF;
        calls.clear();
        comp = accounts.newComp("CN");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "ADM", "prd-wo", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        WoSetup w = setUpWo(comp, admin);
        woId = w.woId;
        spot = w.spot;
        itemId = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG'", Long.class);
        procId = accounts.query(comp, "select proc_id from mst_proc where proc_cd = 'SPOT'", Long.class);
        cookie = terminal(comp, admin, "TAB-01");
        s = new MockHttpSession();
        mvc.perform(json(post("/api/pop/badge"), "{\"badge\":\"BADGE-0001\"}").cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s))
                .andExpect(status().isOk());
        // 실적 2건 → 산출 LOT 2개
        popEvent(spot, "start");
        result(spot, 4);
        result(spot, 6);
        popEvent(spot, "end");
    }

    @AfterEach
    void tearDown() {
        mode = Mode.OFF;
    }

    // ---------- 도우미 ----------

    record WoSetup(long woId, long spot) {
    }

    private WoSetup setUpWo(String c, RequestPostProcessor who) throws Exception {
        accounts.addPlant(c, "P1", "1공장");
        String today = accounts.query(c, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        long area = node(c, null, "AREA", "A1");
        long line = node(c, area, "LINE", "L1");
        long cell = node(c, line, "CELL", "C1");
        accounts.exec(c, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'E1', '홍작업', 'P1', ?, 't', 't')""", c, WorkerService.badgeHash(c, "BADGE-0001"));
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', 'FG 품목', 'FG', 'MAKE', 'EA', 't', 't')""", c);
        accounts.exec(c, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'SPOT', '스폿 용접', 'WELD', 't', 't')""", c);
        long rid = accounts.query(c, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                returning routing_id""", Long.class, c);
        accounts.exec(c, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'SPOT'), ?, 30, 't', 't')""", c, rid, cell);
        accounts.exec(c, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        long wo = id(mvc.perform(json(post(WO), "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"10\",\"planStart\":\"" + today + "\"}")
                .with(who)));
        mvc.perform(json(post(WO + "/" + wo + "/release"), "{\"rowVer\":0}").with(who)).andExpect(status().isOk());
        long sp = accounts.query(c, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 10", Long.class, wo);
        return new WoSetup(wo, sp);
    }

    private long node(String c, Long parent, String type, String cd) {
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, c, parent, type, cd, cd + " 노드");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private String terminal(String c, RequestPostProcessor who, String cd) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\"}").with(who)).andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        String ip = "10.82." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        MvcResult a = mvc.perform(json(post("/api/pop/activate"), "{\"compCd\":\"" + c + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return set.substring(set.indexOf('=') + 1, set.indexOf(';'));
    }

    private ResultActions pop(MockHttpServletRequestBuilder b, String body) throws Exception {
        return mvc.perform(json(b, body).cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s));
    }

    private void popEvent(long proc, String ev) throws Exception {
        pop(post("/api/pop/procs/" + proc + "/events/" + ev), "{\"clientReqId\":\"" + UUID.randomUUID() + "\",\"ack\":true}")
                .andExpect(status().isOk());
    }

    private void result(long proc, int good) throws Exception {
        pop(post("/api/pop/procs/" + proc + "/results"), "{\"clientReqId\":\"" + UUID.randomUUID() + "\",\"goodQty\":\"" + good
                + "\",\"scrapQty\":\"0\",\"reworkQty\":\"0\",\"repairQty\":\"0\",\"defects\":[]}").andExpect(status().isOk());
    }

    private int procRowVer(long proc) {
        return accounts.query(comp, "select row_ver from prd_wo_proc where wo_proc_id = ?", Integer.class, proc);
    }

    private ResultActions closeProc(long proc) throws Exception {
        return mvc.perform(json(post(WO + "/procs/" + proc + "/close"), "{\"rowVer\":" + procRowVer(proc) + "}").with(admin));
    }

    private List<Long> lots() {
        String csv = accounts.query(comp, """
                select string_agg(lot_id::text, ',' order by lot_id) from (select distinct lot_id from prd_result
                 where wo_proc_id = ? and lot_id is not null) x""", String.class, spot);
        return csv == null ? List.of() : Arrays.stream(csv.split(",")).map(Long::valueOf).toList();
    }

    private static CloseContext ctx(int i) {
        return (CloseContext) calls.get(i)[0];
    }

    // ---------- 시험 ----------

    @Test
    void 경고는_미리보기와_판정_응답에_notices_로_오고_판정은_막지_않는다() throws Exception {
        mode = Mode.WARN;
        List<Long> lots = lots();
        assertThat(lots).hasSize(2);
        // 미리보기 — 공정 close-check: 막는 이유 없음 + 경고 2(LOT 마다), 문맥 = 이 공정·품목·산출 LOT·preview
        mvc.perform(get(WO + "/procs/" + spot + "/close-check").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.problems").isEmpty())
                .andExpect(jsonPath("$.data.notices.length()").value(2))
                .andExpect(jsonPath("$.data.notices[0].code").value("ZT_FINAL_PENDING"))
                .andExpect(jsonPath("$.data.notices[0].refType").value("LOT"))
                .andExpect(jsonPath("$.data.notices[0].refId").value(lots.get(0)))
                .andExpect(jsonPath("$.data.notices[0].message").value(Matchers.containsString("ZT-" + lots.get(0))));
        CloseContext c = ctx(0);
        assertThat(c.plantCd()).isEqualTo("P1");
        assertThat(c.woId()).isEqualTo(woId);
        assertThat(c.woProcId()).isEqualTo(spot);
        assertThat(c.procId()).isEqualTo(procId);
        assertThat(c.itemId()).isEqualTo(itemId);
        assertThat(c.outputLotIds()).isEqualTo(lots);
        assertThat(c.preview()).isTrue();
        assertThat(calls.get(0)[1]).isEqualTo(comp);
        // 지시 완료판정 목록(화면이 쓰는 미리보기)에도
        mvc.perform(get(WO + "/" + woId + "/completion").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.procs[0].notices.length()").value(2));
        // 판정 — 성공(DONE) + 응답에 경고, 문맥 preview = false
        calls.clear();
        closeProc(spot).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.notices.length()").value(2));
        assertThat(calls).hasSize(1);
        assertThat(ctx(0).preview()).isFalse();
        assertThat(accounts.query(comp, "select status from prd_wo_proc where wo_proc_id = ?", String.class, spot)).isEqualTo("DONE");
        assertThat(accounts.query(comp, "select count(*) from prd_wo_proc_close where wo_proc_id = ?", Integer.class, spot)).isEqualTo(1);
        // 끝난 공정은 다시 부르지 않는다(목록·close-check 경고 없음)
        calls.clear();
        mvc.perform(get(WO + "/" + woId + "/completion").with(admin)).andExpect(jsonPath("$.data.procs[0].notices").isEmpty());
        mvc.perform(get(WO + "/procs/" + spot + "/close-check").with(admin)).andExpect(jsonPath("$.data.notices").isEmpty());
        assertThat(calls).isEmpty();
    }

    @Test
    void 경고_없음이면_빈_목록() throws Exception {
        mode = Mode.OFF;
        mvc.perform(get(WO + "/procs/" + spot + "/close-check").with(admin)).andExpect(jsonPath("$.data.notices").isEmpty());
        closeProc(spot).andExpect(status().isOk()).andExpect(jsonPath("$.data.notices").isEmpty());
    }

    @Test
    void 빈_예외는_무시하고_판정_성공() throws Exception {
        mode = Mode.THROW;
        mvc.perform(get(WO + "/procs/" + spot + "/close-check").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notices").isEmpty());
        closeProc(spot).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.notices").isEmpty());
    }

    @Test
    void 빈_SQL_오류도_세이브포인트로_되돌려_판정_트랜잭션은_성공() throws Exception {
        mode = Mode.SQL;
        mvc.perform(get(WO + "/" + woId + "/completion").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.procs[0].notices").isEmpty());
        closeProc(spot).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.notices").isEmpty());
        assertThat(accounts.query(comp, "select count(*) from prd_wo_proc_close where wo_proc_id = ?", Integer.class, spot)).isEqualTo(1);
    }

    @Test
    void A사로_B사_공정은_404_빈은_안_불린다() throws Exception {
        mode = Mode.WARN;
        String b = accounts.newComp("CNB");
        long bUser = accounts.newUser(b, "admin1", PW);
        accounts.grant(b, bUser, "ADM", "prd-wo", "SEARCH", "SAVE");
        RequestPostProcessor bAdmin = accounts.as(b, bUser, "admin1");
        WoSetup bw = setUpWo(b, bAdmin);
        calls.clear();
        mvc.perform(get(WO + "/procs/" + bw.spot() + "/close-check").with(admin)).andExpect(status().isNotFound());
        mvc.perform(get(WO + "/" + bw.woId() + "/completion").with(admin)).andExpect(status().isNotFound());
        mvc.perform(json(post(WO + "/procs/" + bw.spot() + "/close"), "{\"rowVer\":0}").with(admin)).andExpect(status().isNotFound());
        assertThat(calls).isEmpty();
        // B사 자기 공정은 B사 범위에서 빈이 돈다(실적 없음 → 산출 LOT 없음 → 경고 없음)
        mvc.perform(get(WO + "/procs/" + bw.spot() + "/close-check").with(bAdmin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notices").isEmpty());
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0)[1]).isEqualTo(b);
        assertThat(ctx(0).outputLotIds()).isEmpty();
    }
}
