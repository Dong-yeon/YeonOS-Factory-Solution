package io.smarthappy.module.wms.pda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

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
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * W-12 적치 검사 (2026-11-02 사용자 결정, docs/30 12.19) — 위치 ↔ 허용 품목군.
 * 위치 화면 목록(직접·상속·없음, 빼기 = use_yn N), 판정 하나를 사무 입고·사무 이동·PDA 입고·PDA 이동이 함께 쓴다(고객 반품은 CustRtnTest),
 * 정책 WARN(기본 — 저장 + 경고)·BLOCK(409, 아무것도 안 남음)·OFF, 목록 없는 위치 = 전부 허용, 품목군 없는 품목 = 위반, 같은 위치 상태 바꿈은 보지 않음,
 * 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class PutawayCheckTest extends PostgresTestBase {

    private static final String PW = "Maple-harbor-73";
    private static final String WH = "/api/pop/wh";
    private static final String LOC = "/api/wms/loc";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    String ip;
    RequestPostProcessor admin;
    long whA;
    long a1;
    long a2;
    long b1;
    long raw;
    long fg;
    long none;
    long clsRaw;
    long clsFg;
    Cookie pda;
    MockHttpSession s;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PA");
        ip = "10.88." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, u, "ADM", "wms-rcv", "SEARCH", "SAVE");
        accounts.grant(comp, u, "ADM", "wms-move", "SEARCH", "SAVE");
        accounts.grant(comp, u, "ADM", "wms-loc", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long cust = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'V100', 'V100 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, comp);
        accounts.exec(comp, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", cust, comp);
        clsRaw = cls(comp, "RAW");
        clsFg = cls(comp, "FG");
        raw = item(comp, "RAW-1", clsRaw);
        fg = item(comp, "FG-1", clsFg);
        none = item(comp, "NONE-1", null);
        whA = wh(comp, "WH-A");
        a1 = loc(comp, whA, "A1");
        a2 = loc(comp, whA, "A2");
        b1 = loc(comp, wh(comp, "WH-B"), "B1");
        // 목록: 창고 WH-A = RAW (A1 은 상속), A2 는 직접 FG, WH-B·B1 은 목록 없음
        allow(admin, whA, clsRaw).andExpect(status().isOk());
        allow(admin, a2, clsFg).andExpect(status().isOk());
        accounts.exec(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'W-001', '작업자', 'P1', ?, 't', 't')""", comp, WorkerService.badgeHash(comp, "BADGE-0001"));
        pda = terminal("PDA-01");
        s = new MockHttpSession();
        mvc.perform(json(post("/api/pop/badge"), "{\"badge\":\"BADGE-0001\"}").cookie(pda).session(s)).andExpect(status().isOk());
    }

    // ---------- 도우미 ----------

    long cls(String c, String cd) {
        return accounts.query(c, "insert into mst_item_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, ?, ?, 't', 't') returning class_id",
                Long.class, c, cd, cd + " 군");
    }

    long item(String c, String cd, Long cls) {
        return accounts.query(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, item_class_id, created_by, updated_by)
                values (?, ?, ?, 'RM', 'BUY', 'EA', 'N', ?, 't', 't') returning item_id""", Long.class, c, cd, cd, cls);
    }

    long wh(String c, String cd) {
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', 'WH', ?, ?, 't', 't') returning node_id""", Long.class, c, cd, cd);
    }

    long loc(String c, long parent, String cd) {
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, 'LOC', ?, ?, 't', 't') returning node_id""", Long.class, c, parent, cd, cd);
    }

    void mode(String m) {
        accounts.exec(comp, "delete from sys_policy where policy_key = 'WMS_PUTAWAY_CHECK'");
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) values (?, 'P1', 'WMS_PUTAWAY_CHECK', ?, 't', 't')",
                comp, m);
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    ResultActions allow(RequestPostProcessor who, long node, Long... classIds) throws Exception {
        String ids = java.util.Arrays.stream(classIds).map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        return mvc.perform(json(put(LOC + "/" + node + "/allow"), "{\"classIds\":[" + ids + "]}").with(who));
    }

    Cookie terminal(String cd) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\",\"terminalType\":\"PDA\"}").with(admin)).andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        MvcResult a = mvc.perform(json(post("/api/pop/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return new Cookie(PopAuthService.COOKIE, set.substring(set.indexOf('=') + 1, set.indexOf(';')));
    }

    String req() {
        return UUID.randomUUID().toString();
    }

    ResultActions rcv(long node, long item, String qty) throws Exception {
        return mvc.perform(json(post("/api/wms/rcv"), "{\"plantCd\":\"P1\",\"nodeId\":" + node + ",\"itemId\":" + item + ",\"qty\":" + qty
                + ",\"stockStatus\":\"AVAILABLE\",\"supplierCd\":\"V100\",\"clientReqId\":\"" + req() + "\"}").with(admin));
    }

    ResultActions move(long item, long from, String fromSt, long to, String toSt, String qty) throws Exception {
        return mvc.perform(json(post("/api/wms/move/move"), "{\"plantCd\":\"P1\",\"itemId\":" + item + ",\"fromNodeId\":" + from
                + ",\"fromStatus\":\"" + fromSt + "\",\"toNodeId\":" + to + ",\"toStatus\":\"" + toSt + "\",\"qty\":" + qty + ",\"clientReqId\":\""
                + req() + "\"}").with(admin));
    }

    ResultActions pdaRcv(long node, long item, String qty) throws Exception {
        return mvc.perform(json(post(WH + "/rcv"), "{\"clientReqId\":\"" + req() + "\",\"itemId\":" + item + ",\"qty\":\"" + qty + "\",\"nodeId\":"
                + node + ",\"entryMethod\":\"SCAN\"}").cookie(pda).session(s));
    }

    ResultActions pdaMove(long item, long from, long to, String qty) throws Exception {
        return mvc.perform(json(post(WH + "/moves"), "{\"clientReqId\":\"" + req() + "\",\"itemId\":" + item + ",\"stockStatus\":\"AVAILABLE\","
                + "\"fromNodeId\":" + from + ",\"toNodeId\":" + to + ",\"qty\":\"" + qty + "\",\"entryMethod\":\"SCAN\"}").cookie(pda).session(s));
    }

    BigDecimal stock(long node, long item) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and item_id = ?", BigDecimal.class, node, item);
    }

    // =====================================================================

    @Test
    void 위치_화면_목록은_직접_상속_없음을_보여주고_빼기는_사용_중지로_남긴다() throws Exception {
        mvc.perform(get(LOC + "/classes").with(admin)).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get(LOC + "/" + whA + "/allow").with(admin)).andExpect(jsonPath("$.data.own[0]").value(clsRaw))
                .andExpect(jsonPath("$.data.inheritedFrom").doesNotExist());
        mvc.perform(get(LOC + "/" + a1 + "/allow").with(admin)).andExpect(jsonPath("$.data.own.length()").value(0))
                .andExpect(jsonPath("$.data.inheritedFrom").value("WH-A")).andExpect(jsonPath("$.data.inherited[0]").value("RAW"));
        mvc.perform(get(LOC + "/" + a2 + "/allow").with(admin)).andExpect(jsonPath("$.data.own[0]").value(clsFg));
        mvc.perform(get(LOC + "/" + b1 + "/allow").with(admin)).andExpect(jsonPath("$.data.own.length()").value(0))
                .andExpect(jsonPath("$.data.inheritedFrom").doesNotExist());
        // A2 목록을 비우면 상위(WH-A, RAW)를 따른다 — 행은 남고 사용 중지
        allow(admin, a2).andExpect(status().isOk()).andExpect(jsonPath("$.data.inheritedFrom").value("WH-A"));
        assertThat(accounts.query(comp, "select string_agg(use_yn, ',') from wms_loc_class_allow where node_id = ?", String.class, a2)).isEqualTo("N");
        // 다시 넣으면 같은 행을 다시 사용(새 행 없음)
        allow(admin, a2, clsFg, clsRaw).andExpect(status().isOk()).andExpect(jsonPath("$.data.own.length()").value(2));
        assertThat(accounts.query(comp, "select count(*) from wms_loc_class_allow where node_id = ?", Integer.class, a2)).isEqualTo(2);
        // 없는 품목군·재고 노드가 아닌 곳·권한
        allow(admin, a1, 999_999_999L).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("classIds"));
        long view = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, view, "VIEW", "wms-loc", "SEARCH");
        RequestPostProcessor viewer = accounts.as(comp, view, "viewer1");
        mvc.perform(get(LOC + "/" + a1 + "/allow").with(viewer)).andExpect(status().isOk());
        allow(viewer, a1, clsRaw).andExpect(status().isForbidden());
        // 직접 SQL: 다른 노드 유형(라인 등)·삭제는 막힘
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> accounts.exec(comp, "delete from wms_loc_class_allow where node_id = ?", a2)))
                .isNotNull();
    }

    @Test
    void 사무_입고는_기본_WARN_이면_저장하고_경고_BLOCK_이면_409_OFF_면_보지_않는다() throws Exception {
        // 기본 WARN(정책 행 없음)
        rcv(a1, fg, "2").andExpect(status().isOk()).andExpect(jsonPath("$.data.warnings.length()").value(1))
                .andExpect(jsonPath("$.data.warnings[0]").value(org.hamcrest.Matchers.containsString("A1")));
        assertThat(stock(a1, fg)).isEqualByComparingTo("2");
        rcv(a1, raw, "1").andExpect(status().isOk()).andExpect(jsonPath("$.data.warnings.length()").value(0));          // 상속 RAW 허용
        rcv(a1, none, "1").andExpect(status().isOk()).andExpect(jsonPath("$.data.warnings.length()").value(1));         // 품목군 없음 = 위반
        rcv(b1, fg, "5").andExpect(status().isOk()).andExpect(jsonPath("$.data.warnings.length()").value(0));           // 목록 없는 위치
        rcv(a2, fg, "1").andExpect(status().isOk()).andExpect(jsonPath("$.data.warnings.length()").value(0));           // 직접 목록 FG
        // BLOCK — 409, 재고·원장 그대로
        mode("BLOCK");
        int trx = accounts.query(comp, "select count(*) from inv_trx", Integer.class);
        rcv(a1, fg, "2").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_PUTAWAY_DENIED"));
        rcv(a2, raw, "1").andExpect(status().isConflict());                                                               // 직접 목록이 상위를 대신한다
        assertThat(accounts.query(comp, "select count(*) from inv_trx", Integer.class)).isEqualTo(trx);
        assertThat(stock(a1, fg)).isEqualByComparingTo("2");
        rcv(a1, raw, "1").andExpect(status().isOk());
        // OFF
        mode("OFF");
        rcv(a1, fg, "1").andExpect(status().isOk()).andExpect(jsonPath("$.data.warnings.length()").value(0));
    }

    @Test
    void 사무_이동_PDA_입고_PDA_이동도_같은_판정이고_같은_위치_상태_바꿈은_보지_않는다() throws Exception {
        rcv(b1, fg, "10").andExpect(status().isOk());
        // WARN
        move(fg, b1, "AVAILABLE", a1, "AVAILABLE", "2").andExpect(status().isOk()).andExpect(jsonPath("$.data.warnings.length()").value(1));
        pdaRcv(a1, fg, "1").andExpect(status().isOk()).andExpect(jsonPath("$.data.warnings.length()").value(1));
        pdaMove(fg, b1, a1, "1").andExpect(status().isOk()).andExpect(jsonPath("$.data.warnings.length()").value(1));
        pdaMove(fg, b1, a2, "1").andExpect(status().isOk()).andExpect(jsonPath("$.data.warnings.length()").value(0));
        assertThat(stock(a1, fg)).isEqualByComparingTo("4");
        // BLOCK
        mode("BLOCK");
        move(fg, b1, "AVAILABLE", a1, "AVAILABLE", "1").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_PUTAWAY_DENIED"));
        pdaRcv(a1, fg, "1").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_PUTAWAY_DENIED"));
        pdaMove(fg, b1, a1, "1").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_PUTAWAY_DENIED"));
        assertThat(stock(a1, fg)).isEqualByComparingTo("4");
        // 이미 들어와 있는 것의 같은 위치 상태 바꿈(HOLD 로 격리)은 둘 곳이 그대로라 보지 않는다
        move(fg, a1, "AVAILABLE", a1, "HOLD", "1").andExpect(status().isOk());
        // 허용 위치로 꺼내는 이동은 된다
        move(fg, a1, "AVAILABLE", a2, "AVAILABLE", "1").andExpect(status().isOk());
    }

    @Test
    void A사로_B사_목록은_보이지도_바뀌지도_않고_판정에도_섞이지_않는다() throws Exception {
        String other = accounts.newComp("PB");
        long u = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, u, "ADM", "wms-loc", "SEARCH", "SAVE");
        accounts.grant(other, u, "ADM", "wms-rcv", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, u, "admin1");
        accounts.addPlant(other, "P1", "1공장");
        mvc.perform(get(LOC + "/" + a1 + "/allow").with(b)).andExpect(status().isNotFound());
        allow(b, whA, clsRaw).andExpect(status().isNotFound());
        mvc.perform(get(LOC + "/classes").with(b)).andExpect(jsonPath("$.data.length()").value(0));
        // B사 노드에 A사 품목군 id → 없는 품목군(400)
        long bWh = wh(other, "WH-A");
        allow(b, bWh, clsRaw).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("classIds"));
        assertThat(accounts.query(other, "select count(*) from wms_loc_class_allow", Integer.class)).isZero();
        // 직접 SQL: B사 범위에서 A사 노드로 행 넣기 → 거부
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> accounts.exec(other,
                "insert into wms_loc_class_allow (comp_cd, node_id, item_class_id, created_by, updated_by) values (?, ?, ?, 't', 't')", other, a1, clsRaw)))
                .isNotNull();
        // A사 목록은 그대로
        assertThat(accounts.query(comp, "select count(*) from wms_loc_class_allow where use_yn = 'Y'", Integer.class)).isEqualTo(2);
    }
}
