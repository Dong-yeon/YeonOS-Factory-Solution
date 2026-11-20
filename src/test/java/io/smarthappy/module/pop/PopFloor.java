package io.smarthappy.module.pop;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.master.workforce.WorkerService;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * POP 현장 화면 시험 공용 바닥(docs/25 4.56~) — 회사 하나에 공장 P1, 작업장 A1 > L1 > C1 · L2, 작업자 E1(배지 BADGE-0001),
 * 품목 FG(라우팅 10 SPOT@C1 → 20 INSP@L1, BOM RM 1.5KG), L1 단말 TAB-01 + 배지 로그인까지. 시험마다 새 회사(ZT 접두 — TestAccounts).
 */
final class PopFloor {

    static final String P = "/api/pop";
    private static final String PW = "Cedar-lantern-41";

    final MockMvc mvc;
    final TestAccounts accounts;
    String comp;
    long userId;
    RequestPostProcessor admin;
    String today;
    long line1;
    long cell1;
    long line2;
    long workerId;
    String cookie;
    MockHttpSession session;
    private String ip;

    PopFloor(MockMvc mvc, TestAccounts accounts) {
        this.mvc = mvc;
        this.accounts = accounts;
    }

    PopFloor build(String prefix) throws Exception {
        comp = accounts.newComp(prefix);
        ip = "10.81." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "ADM", "prd-wo", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        long area = node(null, "AREA", "A1");
        line1 = node(area, "LINE", "L1");
        cell1 = node(line1, "CELL", "C1");
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
                values (?, ?, 10, (select item_id from mst_item where item_cd = 'RM'), 1.5, 'KG', 'NORMAL', 'BACKFLUSH', 't', 't')""",
                comp, bom);
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", bom);
        cookie = terminal("TAB-01", line1);
        session = new MockHttpSession();
        badge(cookie, session, "BADGE-0001").andExpect(status().isOk());
        return this;
    }

    long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd + " 작업장");
    }

    long worker(String cd, String nm, String badge) {
        return accounts.query(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, ?, ?, 'P1', ?, 't', 't') returning worker_id""", Long.class, comp, cd, nm, WorkerService.badgeHash(comp, badge));
    }

    /** 발행된 FG 작업지시 → wo_id */
    long releasedWo(String qty) throws Exception {
        long woId = id(mvc.perform(json(post("/api/production/wos"),
                "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"" + qty + "\",\"planStart\":\"" + today + "\"}").with(admin)));
        mvc.perform(json(post("/api/production/wos/" + woId + "/release"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        return woId;
    }

    long proc(long woId, int opSeq) {
        return accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = ?", Long.class, woId, opSeq);
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    /** 단말 등록 + 활성화 → 쿠키 값 */
    String terminal(String cd, Long nodeId) throws Exception {
        return terminal(cd, nodeId, "POP");
    }

    /** 유형(POP·PDA)을 골라 등록 + 활성화 */
    String terminal(String cd, Long nodeId, String type) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\",\"terminalType\":\"" + type + "\"" + (nodeId == null ? "" : ",\"nodeId\":" + nodeId) + "}").with(admin))
                .andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        MvcResult a = mvc.perform(json(post(P + "/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return set.substring(set.indexOf('=') + 1, set.indexOf(';'));
    }

    ResultActions badge(String c, MockHttpSession s, String badge) throws Exception {
        return mvc.perform(json(post(P + "/badge"), "{\"badge\":\"" + badge + "\"}").cookie(new Cookie(PopAuthService.COOKIE, c)).session(s));
    }

    ResultActions pop(MockHttpServletRequestBuilder b, String body) throws Exception {
        return mvc.perform(json(b, body).cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(session));
    }

    ResultActions popGet(String url) throws Exception {
        return mvc.perform(get(url).cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(session));
    }

    /** 다른 단말 쿠키·세션으로 GET (작업장 밖·배지 없음 시험) */
    ResultActions getAs(String url, String c, MockHttpSession s) throws Exception {
        MockHttpServletRequestBuilder b = get(url).session(s);
        return mvc.perform(c == null ? b : b.cookie(new Cookie(PopAuthService.COOKIE, c)));
    }

    /** 다른 단말 쿠키·세션으로 POST */
    ResultActions postAs(String url, String body, String c, MockHttpSession s) throws Exception {
        MockHttpServletRequestBuilder b = json(post(url), body).session(s);
        return mvc.perform(c == null ? b : b.cookie(new Cookie(PopAuthService.COOKIE, c)));
    }

    static String req() {
        return UUID.randomUUID().toString();
    }
}
