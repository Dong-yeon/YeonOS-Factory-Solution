package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * 공정 완료판정의 LOT 보류 차단 (docs/72 3.1) — 산출 LOT 이 보류 중이면 미리보기 problems 에 LOT_HOLD, 판정 API 직접 호출도 400.
 * 보류를 풀면 판정된다. (출하·생산 입고의 보류 차단은 원래 있던 원장·입고 트리거)
 */
@AutoConfigureMockMvc
class ProcCloseHoldTest extends PostgresTestBase {

    private static final String PW = "Willow-anchor-72";
    private static final String WO = "/api/production/wos";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    long spot;
    String cookie;
    MockHttpSession s;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PH");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "ADM", "prd-wo", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        long area = node(null, "AREA", "A1");
        long line = node(area, "LINE", "L1");
        long cell = node(line, "CELL", "C1");
        accounts.exec(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'E1', '홍작업', 'P1', ?, 't', 't')""", comp, WorkerService.badgeHash(comp, "BADGE-0001"));
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', 'FG 품목', 'FG', 'MAKE', 'EA', 't', 't')""", comp);
        accounts.exec(comp, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'ASSY', '조립', 'ASSEMBLY', 't', 't')""", comp);
        long rid = accounts.query(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                returning routing_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'ASSY'), ?, 30, 't', 't')""", comp, rid, cell);
        accounts.exec(comp, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        // 지시 1건(수량 1)
        long wo = id(mvc.perform(json(post(WO), "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"1\",\"planStart\":\"" + today + "\"}")
                .with(admin)));
        mvc.perform(json(post(WO + "/" + wo + "/release"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        spot = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 10", Long.class, wo);
        cookie = terminal("TAB-01");
        s = new MockHttpSession();
        mvc.perform(json(post("/api/pop/badge"), "{\"badge\":\"BADGE-0001\"}").cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s))
                .andExpect(status().isOk());
        popEvent("start");
        pop(post("/api/pop/procs/" + spot + "/results"), "{\"clientReqId\":\"" + UUID.randomUUID() + "\",\"goodQty\":\"1\",\"scrapQty\":\"0\","
                + "\"reworkQty\":\"0\",\"repairQty\":\"0\",\"defects\":[]}").andExpect(status().isOk());
        popEvent("end");
    }

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
        String ip = "10.83." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        MvcResult a = mvc.perform(json(post("/api/pop/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return set.substring(set.indexOf('=') + 1, set.indexOf(';'));
    }

    private ResultActions pop(MockHttpServletRequestBuilder b, String body) throws Exception {
        return mvc.perform(json(b, body).cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s));
    }

    private void popEvent(String ev) throws Exception {
        pop(post("/api/pop/procs/" + spot + "/events/" + ev), "{\"clientReqId\":\"" + UUID.randomUUID() + "\",\"ack\":true}")
                .andExpect(status().isOk());
    }

    private ResultActions closeProc() throws Exception {
        int v = accounts.query(comp, "select row_ver from prd_wo_proc where wo_proc_id = ?", Integer.class, spot);
        return mvc.perform(json(post(WO + "/procs/" + spot + "/close"), "{\"rowVer\":" + v + "}").with(admin));
    }

    @Test
    void 산출_LOT_이_보류_중이면_완료판정을_막고_풀면_판정된다() throws Exception {
        long lot = accounts.query(comp, "select lot_id from prd_result where wo_proc_id = ? and lot_id is not null limit 1", Long.class, spot);
        long hold = accounts.query(comp, """
                insert into lot_hold (comp_cd, lot_id, source, reason, held_by) values (?, ?, 'MANUAL', '부적합 확인 중', 't') returning hold_id""",
                Long.class, comp, lot);
        mvc.perform(get(WO + "/procs/" + spot + "/close-check").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.problems[0]").value("LOT_HOLD"));
        closeProc().andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("LOT_HOLD"));
        assertThat(accounts.query(comp, "select status from prd_wo_proc where wo_proc_id = ?", String.class, spot)).isEqualTo("READY");
        accounts.exec(comp, "update lot_hold set released_at = now(), released_by = 't', release_reason = '확인 끝' where hold_id = ?", hold);
        closeProc().andExpect(status().isOk());
        assertThat(accounts.query(comp, "select status from prd_wo_proc where wo_proc_id = ?", String.class, spot)).isEqualTo("DONE");
    }
}
