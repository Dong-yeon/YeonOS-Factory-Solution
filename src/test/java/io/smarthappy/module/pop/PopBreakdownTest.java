package io.smarthappy.module.pop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * POP 고장 신고 (docs/25 4.35, 계약 docs/33 11.16) — 설비 선택지 = 단말 공장 + 작업장(라인이면 아래 셀), 현상 = CMS 선택지,
 * 신고 = CMS 보전 작업지시(BM·REQ, 신고자 작업자 + 단말). 같은 clientReqId 두 번·동시 = 처음 번호(duplicate), 칸 오류는 CMS 것 그대로,
 * 범위 밖(다른 공장·다른 라인·사용 중지·없음)·A사로 B사 설비 id, 작업자 없음 428, PDA 403·세션 플래그. NONE 은 PopBreakdownNoneTest.
 */
@AutoConfigureMockMvc
class PopBreakdownTest extends PostgresTestBase {

    private static final String PW = "Birch-signal-58";
    private static final String P = "/api/pop";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    String ip;
    long line1;
    long eLine1;
    long eCell1;
    long eLine2;
    long eOff;
    long eLoose;
    long eP2;
    long workerId;
    String cookie;
    MockHttpSession s;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PB");
        ip = "10.86." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "ADM", "sys-terminal", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.addPlant(comp, "P2", "2공장");
        long area = node(null, "AREA", "A1");
        line1 = node(area, "LINE", "L1");
        long cell1 = node(line1, "CELL", "C1");
        long line2 = node(area, "LINE", "L2");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', '프레스', 't', 't') returning class_id",
                Long.class, comp);
        eLine1 = equip(comp, "P1", "E-L1", cls, line1, "Y");
        eCell1 = equip(comp, "P1", "E-C1", cls, cell1, "Y");
        eLine2 = equip(comp, "P1", "E-L2", cls, line2, "Y");
        eOff = equip(comp, "P1", "E-OFF", cls, line1, "N");
        eLoose = equip(comp, "P1", "E-LOOSE", cls, null, "Y");      // 보관·미설치(노드 없음)
        eP2 = equip(comp, "P2", "E-P2", cls, null, "Y");
        accounts.exec(comp, "insert into cms_fail_code (comp_cd, code_type, fail_cd, fail_nm, sort_no, created_by, updated_by) values (?, 'SYMPTOM', 'LEAK', '누유', 2, 't', 't')", comp);
        accounts.exec(comp, "insert into cms_fail_code (comp_cd, code_type, fail_cd, fail_nm, sort_no, created_by, updated_by) values (?, 'SYMPTOM', 'NOISE', '소음', 1, 't', 't')", comp);
        workerId = accounts.query(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'E1', '홍작업', 'P1', ?, 't', 't') returning worker_id""", Long.class, comp, WorkerService.badgeHash(comp, "BADGE-0001"));
        cookie = terminal("TAB-01", line1, "POP");
        s = new MockHttpSession();
        badge(cookie, s).andExpect(status().isOk());
    }

    // ---------- 도우미 ----------

    private long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd + " 작업장");
    }

    private long equip(String c, String plant, String cd, long cls, Long nodeId, String useYn) {
        return accounts.query(c, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, node_id, use_yn, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, 't', 't') returning equip_id""", Long.class, c, plant, cd, cd + " 설비", cls, nodeId, useYn);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** 단말 등록(유형) + 활성화 → 쿠키 값 */
    private String terminal(String cd, Long nodeId, String type) throws Exception {
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

    private ResultActions badge(String c, MockHttpSession session) throws Exception {
        return mvc.perform(json(post(P + "/badge"), "{\"badge\":\"BADGE-0001\"}").cookie(new Cookie(PopAuthService.COOKIE, c)).session(session));
    }

    private ResultActions report(String c, MockHttpSession session, long equipId, String symptomCd, String text, String reqId) throws Exception {
        String body = "{\"equipId\":" + equipId + ",\"symptomCd\":" + (symptomCd == null ? "null" : "\"" + symptomCd + "\"")
                + ",\"symptomText\":" + (text == null ? "null" : "\"" + text + "\"") + ",\"clientReqId\":\"" + reqId + "\"}";
        return mvc.perform(json(post(P + "/breakdown"), body).cookie(new Cookie(PopAuthService.COOKIE, c)).session(session));
    }

    private ResultActions report(long equipId, String symptomCd, String text, String reqId) throws Exception {
        return report(cookie, s, equipId, symptomCd, text, reqId);
    }

    private ResultActions popGet(String url) throws Exception {
        return mvc.perform(get(url).cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s));
    }

    private static String req() {
        return UUID.randomUUID().toString();
    }

    private static String reqNo(ResultActions r) throws Exception {
        return JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.reqNo");
    }

    private int mwoCount(String c) {
        return accounts.query(c, "select count(*) from cms_mwo", Integer.class);
    }

    // ---------- 시험 ----------

    @Test
    void 설비_선택지는_단말_라인과_아래_셀만_신고는_CMS_작업지시_신고자는_작업자와_단말() throws Exception {
        mvc.perform(get(P + "/session").cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s))
                .andExpect(jsonPath("$.data.breakdown").value(true));
        // L1 단말: L1 설비 + 아래 셀 C1 설비(코드 순). 다른 라인·사용 중지·미설치·다른 공장은 없다
        popGet(P + "/breakdown/equips").andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].equipCd").value(org.hamcrest.Matchers.contains("E-C1", "E-L1")))
                .andExpect(jsonPath("$.data[1].nodeCd").value("L1"))
                .andExpect(jsonPath("$.data[0].plantCd").doesNotExist());
        popGet(P + "/breakdown/symptoms?equipId=" + eCell1).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].symptomCd").value(org.hamcrest.Matchers.contains("NOISE", "LEAK")));

        String no = reqNo(report(eCell1, "LEAK", "실린더 누유", req()).andExpect(jsonPath("$.data.duplicate").value(false)));
        long terminalId = accounts.query(comp, "select terminal_id from sys_terminal where terminal_cd = 'TAB-01'", Long.class);
        String row = accounts.query(comp, """
                select concat_ws('|', mwo_type, status, equip_id, (select fail_cd from cms_fail_code where fail_id = symptom_id),
                                 reported_worker_id, reported_terminal_id, coalesce(requested_by::text, '-'), created_by)
                  from cms_mwo where mwo_no = ?""", String.class, no);
        assertThat(row).isEqualTo(String.join("|", "BM", "REQ", String.valueOf(eCell1), "LEAK", String.valueOf(workerId),
                String.valueOf(terminalId), "-", "t" + terminalId + ":TAB-01"));
        // 글만으로도 된다(현상 코드 없음)
        report(eLine1, null, "소리가 이상함", req()).andExpect(status().isOk());
        assertThat(mwoCount(comp)).isEqualTo(2);
    }

    @Test
    void 같은_요청_id_두_번이면_처음_번호와_duplicate_칸_오류_뒤_고쳐_같은_id_는_새로() throws Exception {
        String id = req();
        String first = reqNo(report(eLine1, "LEAK", null, id));
        report(eLine1, "LEAK", null, id).andExpect(status().isOk()).andExpect(jsonPath("$.data.reqNo").value(first))
                .andExpect(jsonPath("$.data.duplicate").value(true));
        assertThat(mwoCount(comp)).isEqualTo(1);
        // 검사 오류(현상·글 없음)는 멱등 기록 전 — 고쳐서 같은 id 로 보내면 새로 만든다
        String id2 = req();
        report(eLine1, null, "  ", id2).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("symptomCd")).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        report(eLine1, "NOISE", null, id2).andExpect(status().isOk()).andExpect(jsonPath("$.data.duplicate").value(false));
        assertThat(mwoCount(comp)).isEqualTo(2);
    }

    @Test
    void 같은_요청_id_가_동시에_두_번_와도_작업지시는_하나() throws Exception {
        String id = req();
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<String>> calls = List.of(1, 2).stream().map(i -> CompletableFuture.supplyAsync(() -> {
            try {
                go.await(10, TimeUnit.SECONDS);
                return report(eLine1, "LEAK", null, id).andReturn().getResponse().getContentAsString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        })).toList();
        go.countDown();
        List<String> bodies = calls.stream().map(f -> f.orTimeout(20, TimeUnit.SECONDS).join()).toList();
        List<String> nos = bodies.stream().map(b -> (String) JsonPath.read(b, "$.data.reqNo")).toList();
        List<Boolean> dups = bodies.stream().map(b -> (Boolean) JsonPath.read(b, "$.data.duplicate")).toList();
        assertThat(nos.get(0)).isNotNull().isEqualTo(nos.get(1));
        assertThat(dups).containsExactlyInAnyOrder(true, false);
        assertThat(mwoCount(comp)).isEqualTo(1);
    }

    @Test
    void 범위_밖_설비와_CMS_칸_오류는_400_그대로_작업지시_없음() throws Exception {
        String[][] cases = {
            {String.valueOf(eP2), "PLANT"}, {String.valueOf(eLine2), "EQUIP_NODE"}, {String.valueOf(eLoose), "EQUIP_NODE"},
            {String.valueOf(eOff), "INACTIVE"}, {"999999999", "NO_SUCH_CODE"}};
        for (String[] c : cases) {
            report(Long.parseLong(c[0]), "LEAK", null, req()).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("equipId")).andExpect(jsonPath("$.errors[0].code").value(c[1]));
            popGet(P + "/breakdown/symptoms?equipId=" + c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].code").value(c[1]));
        }
        popGet(P + "/breakdown/symptoms").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        // CMS 칸 오류를 그대로 — 글 200자 초과, 요청 id 형식, 없는 현상 코드
        report(eLine1, null, "가".repeat(201), req()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("symptomText")).andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        report(eLine1, "LEAK", null, "short").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("clientReqId"));
        report(eLine1, "NOPE", null, req()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("symptomCd"));
        mvc.perform(json(post(P + "/breakdown"), "").cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(s))
                .andExpect(status().isBadRequest());
        assertThat(mwoCount(comp)).isZero();
    }

    @Test
    void A사_단말로_B사_설비_id_는_없음_B사_작업지시도_없음() throws Exception {
        String other = accounts.newComp("PC");
        accounts.addPlant(other, "P1", "1공장");
        long cls = accounts.query(other, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', '프레스', 't', 't') returning class_id",
                Long.class, other);
        long theirs = equip(other, "P1", "E-L1", cls, null, "Y");   // 같은 공장 코드·설비 코드, 다른 회사
        report(theirs, "LEAK", null, req()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("equipId")).andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        popGet(P + "/breakdown/symptoms?equipId=" + theirs).andExpect(status().isBadRequest());
        assertThat(mwoCount(other)).isZero();
        assertThat(mwoCount(comp)).isZero();
    }

    @Test
    void 작업자_없으면_428_PDA_는_403_공장_단말은_공장_전체() throws Exception {
        MockHttpSession none = new MockHttpSession();
        mvc.perform(get(P + "/breakdown/equips").cookie(new Cookie(PopAuthService.COOKIE, cookie)).session(none))
                .andExpect(status().is(428)).andExpect(jsonPath("$.code").value("POP_NO_WORKER"));
        report(cookie, none, eLine1, "LEAK", null, req()).andExpect(status().is(428));
        // PDA — 세션 플래그 거짓, 고장 신고 API 는 단말 유형에서 막힌다(배지가 있어도)
        String pda = terminal("PDA-01", null, "PDA");
        MockHttpSession ps = new MockHttpSession();
        badge(pda, ps).andExpect(status().isOk());
        mvc.perform(get(P + "/session").cookie(new Cookie(PopAuthService.COOKIE, pda)).session(ps))
                .andExpect(jsonPath("$.data.breakdown").value(false));
        mvc.perform(get(P + "/breakdown/equips").cookie(new Cookie(PopAuthService.COOKIE, pda)).session(ps))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get(P + "/breakdown/symptoms?equipId=" + eLine1).cookie(new Cookie(PopAuthService.COOKIE, pda)).session(ps))
                .andExpect(status().isForbidden());
        report(pda, ps, eLine1, "LEAK", null, req()).andExpect(status().isForbidden());
        // 작업장 없는 공장 단말 — 공장 1 의 사용 중 설비 전부(미설치 포함), 다른 공장 설비는 PLANT
        String plantWide = terminal("TAB-P1", null, "POP");
        MockHttpSession ws = new MockHttpSession();
        badge(plantWide, ws).andExpect(status().isOk());
        mvc.perform(get(P + "/breakdown/equips").cookie(new Cookie(PopAuthService.COOKIE, plantWide)).session(ws)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].equipCd").value(org.hamcrest.Matchers.contains("E-C1", "E-L1", "E-L2", "E-LOOSE")));
        report(plantWide, ws, eLoose, "LEAK", null, req()).andExpect(status().isOk());
        report(plantWide, ws, eP2, "LEAK", null, req()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("PLANT"));
        assertThat(mwoCount(comp)).isEqualTo(1);
    }
}
