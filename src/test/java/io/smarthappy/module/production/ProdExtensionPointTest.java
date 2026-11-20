package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
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
import io.smarthappy.module.production.WoBomExtension.ExtraBom;
import io.smarthappy.module.production.link.CloseBlock;
import io.smarthappy.module.production.link.CloseNotice.CloseContext;
import io.smarthappy.module.production.link.UnitLotSource;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * 생산 확장점 3개 (docs/72 2.2~2.4) — 시험용 빈을 켜고 끄며 본다. 빈이 아예 없는 경우(= 기존 동작)는 PostgresTestBase 공용 문맥의
 * 다른 시험(WorkOrderTest·PopWorkTest·CloseNoticeTest·CompletionReceiptTest 등)이 덮고, 여기서는 빈이 "해당 없음"을 돌려줄 때 같은 결과인지 본다.
 * <ul>
 * <li>WoBomExtension — 발행 때 추가 BOM 이 스냅숏 끝에 붙는다. 작성 중 BOM·다른 회사 BOM 이면 발행 전체가 거부된다</li>
 * <li>UnitLotSource — 정해 준 LOT 이 있으면 새 LOT 을 만들지 않는다</li>
 * <li>CloseBlock — 막는 코드가 있으면 미리보기 problems·판정 400, 없으면 판정된다. A사로 B사 공정은 404 이고 빈을 부르지 않는다</li>
 * </ul>
 */
@AutoConfigureMockMvc
@Import(ProdExtensionPointTest.Config.class)
class ProdExtensionPointTest extends PostgresTestBase {

    private static final String PW = "Birch-lantern-58";
    private static final String WO = "/api/production/wos";

    static volatile List<ExtraBom> extraBoms = List.of();
    static volatile Long fixedLot;
    static volatile List<String> blockCodes = List.of();
    /** CloseBlock 이 불린 문맥 + 그때 회사 범위 */
    static final List<Object[]> blockCalls = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Config {

        @Bean
        WoBomExtension testWoBomExtension() {
            return (woId, planStart, planQty) -> extraBoms;
        }

        @Bean
        UnitLotSource testUnitLotSource() {
            return (woId, qty, occurredAt, by) -> Optional.ofNullable(fixedLot);
        }

        @Bean
        CloseBlock testCloseBlock(JdbcTemplate jdbc) {
            return ctx -> {
                blockCalls.add(new Object[] {ctx, jdbc.queryForObject("select hb_current_comp()", String.class)});
                return blockCodes;
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    String today;
    String cookie;
    MockHttpSession s;

    @BeforeEach
    void setUp() throws Exception {
        reset();
        comp = accounts.newComp("XP");
        admin = fixture(comp);
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
    }

    @AfterEach
    void reset() {
        extraBoms = List.of();
        fixedLot = null;
        blockCodes = List.of();
        blockCalls.clear();
    }

    // ---------- 도우미 ----------

    /** 회사 하나: 관리자·공장·품목(FG, OPT, PART)·공정·확정 라우팅(FG, 공정 1개) */
    private RequestPostProcessor fixture(String c) {
        long userId = accounts.newUser(c, "admin1", PW);
        accounts.grant(c, userId, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(c, userId, "ADM", "prd-wo", "SEARCH", "SAVE");
        accounts.addPlant(c, "P1", "1공장");
        long area = node(c, null, "AREA", "A1");
        long line = node(c, area, "LINE", "L1");
        long cell = node(c, line, "CELL", "C1");
        accounts.exec(c, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'E1', '홍작업', 'P1', ?, 't', 't')""", c, WorkerService.badgeHash(c, "BADGE-0001"));
        for (String item : List.of("FG", "OPT", "PART")) {
            accounts.exec(c, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                    values (?, ?, ?, 'FG', 'MAKE', 'EA', 't', 't')""", c, item, item + " 품목");
        }
        accounts.exec(c, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'ASSY', '조립', 'ASSEMBLY', 't', 't')""", c);
        long rid = accounts.query(c, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                returning routing_id""", Long.class, c);
        accounts.exec(c, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'ASSY'), ?, 30, 't', 't')""", c, rid, cell);
        accounts.exec(c, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        return accounts.as(c, userId, "admin1");
    }

    private long node(String c, Long parent, String type, String cd) {
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, c, parent, type, cd, cd + " 노드");
    }

    /** OPT 의 BOM(기준 1) — PART 2 EA. released = 확정 여부 */
    private long optBom(String c, boolean released) {
        long id = accounts.query(c, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, base_qty, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'OPT'), 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 1, 't', 't')
                returning bom_id""", Long.class, c);
        accounts.exec(c, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, scrap_pct, fixed_yn, issue_method,
                                          created_by, updated_by)
                values (?, ?, 10, (select item_id from mst_item where item_cd = 'PART'), 2, 'EA', 'NORMAL', 0, 'N', 'BACKFLUSH', 't', 't')""", c, id);
        if (released) {
            accounts.exec(c, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", id);
        }
        return id;
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private long wo(String qty) throws Exception {
        return id(mvc.perform(json(post(WO), "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"" + qty + "\",\"planStart\":\"" + today + "\"}")
                .with(admin)));
    }

    private ResultActions release(long woId) throws Exception {
        int v = accounts.query(comp, "select row_ver from prd_wo where wo_id = ?", Integer.class, woId);
        return mvc.perform(json(post(WO + "/" + woId + "/release"), "{\"rowVer\":" + v + "}").with(admin));
    }

    private String woStatus(long woId) {
        return accounts.query(comp, "select status from prd_wo where wo_id = ?", String.class, woId);
    }

    private int snapCount(long woId) {
        return accounts.query(comp, "select count(*) from prd_wo_bom where wo_id = ?", Integer.class, woId);
    }

    private long spot(long woId) {
        return accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 10", Long.class, woId);
    }

    private void login(String terminalCd) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + terminalCd + "\",\"terminalNm\":\"" + terminalCd
                + "\",\"plantCd\":\"P1\"}").with(admin)).andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        String ip = "10.84." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        MvcResult a = mvc.perform(json(post("/api/pop/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        cookie = set.substring(set.indexOf('=') + 1, set.indexOf(';'));
        s = new MockHttpSession();
        mvc.perform(json(post("/api/pop/badge"), "{\"badge\":\"BADGE-0001\"}").cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s))
                .andExpect(status().isOk());
    }

    private ResultActions pop(MockHttpServletRequestBuilder b, String body) throws Exception {
        return mvc.perform(json(b, body).cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s));
    }

    private void popEvent(long proc, String ev) throws Exception {
        pop(post("/api/pop/procs/" + proc + "/events/" + ev), "{\"clientReqId\":\"" + UUID.randomUUID() + "\",\"ack\":true}")
                .andExpect(status().isOk());
    }

    private ResultActions result(long proc, int good) throws Exception {
        return pop(post("/api/pop/procs/" + proc + "/results"), "{\"clientReqId\":\"" + UUID.randomUUID() + "\",\"goodQty\":\"" + good
                + "\",\"scrapQty\":\"0\",\"reworkQty\":\"0\",\"repairQty\":\"0\",\"defects\":[]}");
    }

    private ResultActions closeProc(long proc, RequestPostProcessor who) throws Exception {
        int v = accounts.query(comp, "select row_ver from prd_wo_proc where wo_proc_id = ?", Integer.class, proc);
        return mvc.perform(json(post(WO + "/procs/" + proc + "/close"), "{\"rowVer\":" + v + "}").with(who));
    }

    // ---------- 시험 ----------

    @Test
    void 추가_BOM_이_없으면_그대로_있으면_스냅숏_끝에_출처와_함께_붙는다() throws Exception {
        // 확장점이 빈 목록 → 본체 BOM 없는 지시는 스냅숏 0줄(기존 동작)
        long w0 = wo("3");
        release(w0).andExpect(status().isOk());
        assertThat(snapCount(w0)).isZero();
        // 추가 BOM(OPT 3개분) → PART 6 EA 한 줄, 출처는 그 BOM
        long bom = optBom(comp, true);
        long w1 = wo("3");
        extraBoms = List.of(new ExtraBom(bom, new BigDecimal("3")));
        release(w1).andExpect(status().isOk());
        assertThat(snapCount(w1)).isEqualTo(1);
        assertThat(accounts.query(comp, """
                select b.seq || ':' || i.item_cd || ':' || trim_scale(b.req_qty) || ':' || b.src_bom_id
                  from prd_wo_bom b join mst_item i on i.item_id = b.item_id where b.wo_id = ?""", String.class, w1))
                .isEqualTo("1:PART:6:" + bom);
        // 같은 BOM 을 두 번 주면 줄 번호를 이어서 두 줄
        long w2 = wo("1");
        extraBoms = List.of(new ExtraBom(bom, BigDecimal.ONE), new ExtraBom(bom, new BigDecimal("2")));
        release(w2).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select string_agg(seq || ':' || trim_scale(req_qty), ',' order by seq) from prd_wo_bom where wo_id = ?",
                String.class, w2)).isEqualTo("1:2,2:4");
    }

    @Test
    void 추가_BOM_이_작성중이거나_다른_회사_것이면_발행_전체가_거부된다() throws Exception {
        long draft = optBom(comp, false);
        long w = wo("2");
        String before = woStatus(w);
        extraBoms = List.of(new ExtraBom(draft, BigDecimal.ONE));
        release(w).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("bomId"));
        assertThat(woStatus(w)).isEqualTo(before);
        assertThat(snapCount(w)).isZero();
        // A사 지시에 B사 BOM id — 회사 범위 밖이라 보이지 않는다(404), 지시는 그대로
        String other = accounts.newComp("XQ");
        fixture(other);
        long bBom = optBom(other, true);
        extraBoms = List.of(new ExtraBom(bBom, BigDecimal.ONE));
        release(w).andExpect(status().isNotFound());
        assertThat(woStatus(w)).isEqualTo(before);
        assertThat(accounts.query(comp, "select count(*) from prd_wo_bom", Integer.class)).isZero();
        // 확장점을 끄면 같은 지시가 발행된다
        extraBoms = List.of();
        release(w).andExpect(status().isOk());
    }

    @Test
    void LOT_출처가_정해_주면_새_LOT_을_만들지_않고_없으면_새로_만든다() throws Exception {
        long w = wo("10");
        release(w).andExpect(status().isOk());
        long proc = spot(w);
        login("TAB-01");
        popEvent(proc, "start");
        // 해당 없음(empty) → 새 생산 LOT
        result(proc, 4).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from lot_master where wo_id = ?", Integer.class, w)).isEqualTo(1);
        long made = accounts.query(comp, "select lot_id from lot_master where wo_id = ?", Long.class, w);
        // 정해 준 LOT → 그 LOT 에 실적이 붙고 LOT 은 늘지 않는다
        long fixed = accounts.query(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, wo_id, occurred_at, created_by, updated_by)
                select ?, 'ZT-UNIT-0001', item_id, 'PRODUCED', 6, 'EA', ?, now(), 't', 't' from mst_item where item_cd = 'FG' returning lot_id""",
                Long.class, comp, w);
        fixedLot = fixed;
        result(proc, 6).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from lot_master where wo_id = ?", Integer.class, w)).isEqualTo(2);
        assertThat(accounts.query(comp, "select string_agg(lot_id::text, ',' order by result_id) from prd_result where wo_proc_id = ?",
                String.class, proc)).isEqualTo(made + "," + fixed);
    }

    @Test
    void 막는_이유가_있으면_미리보기와_판정이_막히고_없으면_판정된다_A사로_B사는_404() throws Exception {
        long w = wo("5");
        release(w).andExpect(status().isOk());
        long proc = spot(w);
        login("TAB-02");
        popEvent(proc, "start");
        result(proc, 5).andExpect(status().isOk());
        popEvent(proc, "end");
        // 막는 코드 → 미리보기 problems, 판정 400(칸 status), 공정은 그대로
        blockCodes = List.of("ZT_PART_MISSING");
        mvc.perform(get(WO + "/procs/" + proc + "/close-check").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.problems", Matchers.contains("ZT_PART_MISSING")));
        CloseContext c = (CloseContext) blockCalls.get(0)[0];
        assertThat(c.woId()).isEqualTo(w);
        assertThat(c.woProcId()).isEqualTo(proc);
        assertThat(c.outputLotIds()).hasSize(1);
        assertThat(blockCalls.get(0)[1]).as("부른 회사 범위에서 돈다").isEqualTo(comp);
        closeProc(proc, admin).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("ZT_PART_MISSING"));
        assertThat(accounts.query(comp, "select status from prd_wo_proc where wo_proc_id = ?", String.class, proc)).isEqualTo("READY");
        assertThat(accounts.query(comp, "select count(*) from prd_wo_proc_close where wo_proc_id = ?", Integer.class, proc)).isZero();
        // A사 공정을 B사 계정으로 — 404, 빈은 불리지 않는다
        String other = accounts.newComp("XR");
        RequestPostProcessor b = fixture(other);
        blockCalls.clear();
        mvc.perform(get(WO + "/procs/" + proc + "/close-check").with(b)).andExpect(status().isNotFound());
        closeProc(proc, b).andExpect(status().isNotFound());
        assertThat(blockCalls).isEmpty();
        // 막는 이유 없음 → 판정된다. 끝난 공정에는 빈을 부르지 않는다
        blockCodes = List.of();
        closeProc(proc, admin).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DONE"));
        blockCalls.clear();
        blockCodes = List.of("ZT_PART_MISSING");
        mvc.perform(get(WO + "/procs/" + proc + "/close-check").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.problems", Matchers.contains("DONE")));
        assertThat(blockCalls).isEmpty();
    }
}
