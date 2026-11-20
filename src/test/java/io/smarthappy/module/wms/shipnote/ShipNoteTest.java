package io.smarthappy.module.wms.shipnote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * 출하 특이사항 (docs/56, docs/49 W-4) — 조건(고객·품목군·품목, 비면 전체)이 출하 지시·목록 열·PDA 에 같게 걸리고, 확인 필수 규칙은 확인 전 피킹·출하 확정을
 * 서버가 막는다(사무·PDA 공통). 글을 고치면 다시 확인. 규칙은 지우지 않고(사용 끔) 확인은 쌓기만. A사로 B사 = 없는 것.
 */
@AutoConfigureMockMvc
class ShipNoteTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String S = "/api/wms/ships";
    private static final String N = "/api/wms/ship-notes";
    private static final String WH = "/api/pop/wh";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    String ip;
    RequestPostProcessor admin;
    long a1;
    long stg;
    long fg;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("SN");
        ip = "10.87." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, u, "ADM", "sal-order", "SEARCH", "SAVE");
        accounts.grant(comp, u, "ADM", "wms-ship", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, u, "ADM", ShipNoteService.PROG, "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        seed(comp);
        a1 = node(comp, "A-01");
        stg = node(comp, "STG-01");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't')",
                stg, comp);
        fg = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG-100'", Long.class);
        accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', 30, 'EA', 'MANUAL', now() - interval '1 hour', 'KEY', 't')""", comp, fg, a1);
    }

    // ---------- 도우미 ----------

    /** 공장 P1, 고객 C-001·C-002, 품목군 PRESS(FG-100)·COIL(FG-200) */
    void seed(String c) {
        accounts.addPlant(c, "P1", "P1 공장");
        accounts.exec(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C-001', 'C-001 상사', 'SALES', 't', 't'), (?, 'C-002', 'C-002 상사', 'SALES', 't', 't')""", c, c);
        accounts.exec(c, """
                insert into mst_item_class (comp_cd, class_cd, class_nm, created_by, updated_by)
                values (?, 'PRESS', '프레스품', 't', 't'), (?, 'COIL', '코일', 't', 't')""", c, c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, item_class_id, created_by, updated_by)
                select ?, x.cd, x.cd || ' 품목', 'FG', 'MAKE', 'EA', 'N', k.class_id, 't', 't'
                  from (values ('FG-100', 'PRESS'), ('FG-200', 'COIL')) x(cd, cls) join mst_item_class k on k.class_cd = x.cls""", c);
    }

    long node(String c, String cd) {
        Long wh = accounts.query(c, "select max(node_id) from mst_equip_hier where node_cd = 'WH-P1'", Long.class);
        if (wh == null) {
            wh = accounts.query(c, """
                    insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                    values (?, 'P1', 'WH', 'WH-P1', 'WH-P1', 't', 't') returning node_id""", Long.class, c);
        }
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, 'LOC', ?, ?, 't', 't') returning node_id""", Long.class, c, wh, cd, cd);
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    ResultActions postJson(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(who));
    }

    /** 규칙 추가 → id */
    long note(String cust, String cls, String item, String title, String ack) throws Exception {
        MvcResult m = postJson(admin, N, saveBody(null, cust, cls, item, title, ack, "Y", null)).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.noteId")).longValue();
    }

    static String saveBody(Long id, String cust, String cls, String item, String title, String ack, String use, Integer rowVer) {
        return "{" + (id == null ? "" : "\"noteId\":" + id + ",\"rowVer\":" + rowVer + ",") + "\"custCd\":" + q(cust) + ",\"classCd\":" + q(cls)
                + ",\"itemCd\":" + q(item) + ",\"title\":" + q(title) + ",\"body\":\"포장 사양서 동봉\",\"ackRequiredYn\":" + q(ack)
                + ",\"useYn\":" + q(use) + "}";
    }

    static String q(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    /** 수주(C-001 등, 품목 FG-100 10) → 출하 지시 id */
    long ship(String c, RequestPostProcessor who, String cust, String item, long stage) throws Exception {
        MvcResult o = postJson(who, "/api/sales/orders", "{\"custCd\":\"" + cust + "\",\"lines\":[{\"itemCd\":\"" + item
                + "\",\"qty\":\"10\",\"dueDate\":\"2026-11-30\"}]}").andExpect(status().isOk()).andReturn();
        long orderId = ((Number) JsonPath.read(o.getResponse().getContentAsString(), "$.data")).longValue();
        long ol = accounts.query(c, "select line_id from sal_order_line where order_id = ?", Long.class, orderId);
        MvcResult m = postJson(who, S, "{\"plantCd\":\"P1\",\"custCd\":\"" + cust + "\",\"stageNodeId\":" + stage
                + ",\"planDate\":\"2026-11-20\",\"lines\":[{\"orderLineId\":" + ol + ",\"qty\":10}]}").andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.head.shipId")).longValue();
    }

    int shipVer(long id) {
        return accounts.query(comp, "select row_ver from wms_ship where ship_id = ?", Integer.class, id);
    }

    long sline(long id) {
        return accounts.query(comp, "select ship_line_id from wms_ship_line where ship_id = ? and line_no = 1", Long.class, id);
    }

    String picks(long id, String qty) {
        return "{\"rowVer\":" + shipVer(id) + ",\"clientReqId\":\"" + UUID.randomUUID() + "\",\"picks\":[{\"shipLineId\":" + sline(id)
                + ",\"fromNodeId\":" + a1 + ",\"qty\":" + qty + "}]}";
    }

    ResultActions confirm(long id) throws Exception {
        return postJson(admin, S + "/" + id + "/confirm", "{\"rowVer\":" + shipVer(id) + ",\"clientReqId\":\"" + UUID.randomUUID() + "\"}");
    }

    int noteVer(long noteId) {
        return accounts.query(comp, "select row_ver from wms_ship_note where note_id = ?", Integer.class, noteId);
    }

    int acks(long shipId) {
        return accounts.query(comp, "select count(*) from wms_ship_note_ack where ship_id = ?", Integer.class, shipId);
    }

    BigDecimal stock(long node) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and item_id = ?", BigDecimal.class, node, fg)
                .stripTrailingZeros();
    }

    // =====================================================================
    // 조건 · 목록 열
    // =====================================================================

    @Test
    void 고객_품목군_품목_전체_조건이_지시에_걸리고_사용_끔_다른_고객_다른_품목군은_안_걸린다() throws Exception {
        long byCust = note("C-001", null, null, "C-001 전용 라벨", "N");
        long byClass = note(null, "PRESS", null, "프레스품 방청유", "N");
        long byItem = note(null, null, "FG-100", "FG-100 성적서", "N");
        long all = note(null, null, null, "모든 출하 사진", "N");
        note("C-002", null, null, "C-002 전용", "N");
        note(null, "COIL", null, "코일 받침", "N");
        note(null, null, "FG-200", "FG-200 전용", "N");
        long off = note("C-001", null, null, "사용 끔", "N");
        MvcResult cur = mvc.perform(get(N + "/" + off).with(admin)).andExpect(status().isOk()).andReturn();
        int ver = JsonPath.read(cur.getResponse().getContentAsString(), "$.data.rowVer");
        postJson(admin, N, saveBody(off, "C-001", null, null, "사용 끔", "N", "N", ver)).andExpect(status().isOk());
        // 고객 + 품목을 함께 = 둘 다 맞아야
        long both = note("C-001", null, "FG-100", "C-001 의 FG-100", "N");
        note("C-002", null, "FG-100", "C-002 의 FG-100", "N");

        long id = ship(comp, admin, "C-001", "FG-100", stg);
        long line = sline(id);
        mvc.perform(get(S + "/" + id).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notes.length()").value(5))
                .andExpect(jsonPath("$.data.notes[?(@.noteId == " + byCust + ")].lineIds[0]").value(org.hamcrest.Matchers.contains((int) line)))
                .andExpect(jsonPath("$.data.notes[?(@.noteId == " + byClass + ")]").isNotEmpty())
                .andExpect(jsonPath("$.data.notes[?(@.noteId == " + byItem + ")]").isNotEmpty())
                .andExpect(jsonPath("$.data.notes[?(@.noteId == " + all + ")]").isNotEmpty())
                .andExpect(jsonPath("$.data.notes[?(@.noteId == " + both + ")]").isNotEmpty())
                .andExpect(jsonPath("$.data.notes[?(@.noteId == " + off + ")]").isEmpty());
        // 목록 열 — 걸린 수 5, 확인 필수 없음 0
        mvc.perform(get(S + "/grid?plantCd=P1&from=2026-11-01&to=2026-11-30").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.list[?(@.shipId == " + id + ")].noteCnt").value(org.hamcrest.Matchers.contains(5)))
                .andExpect(jsonPath("$.list[?(@.shipId == " + id + ")].noteOpenCnt").value(org.hamcrest.Matchers.contains(0)));
        // 규칙 목록·검색
        mvc.perform(get(N + "/grid?q=FG-100").with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3));
        mvc.perform(get(N + "/grid?useYn=N").with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(N + "/classes").with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
    }

    // =====================================================================
    // 확인 필수 — 사무
    // =====================================================================

    @Test
    void 확인_필수_규칙은_확인_전_피킹_400_확인_뒤_통과_글을_고치면_확정_전에_다시_확인() throws Exception {
        long req = note(null, "PRESS", null, "방청유 도포 확인", "Y");
        long info = note("C-001", null, null, "참고만", "N");
        long id = ship(comp, admin, "C-001", "FG-100", stg);
        mvc.perform(get(S + "/grid?plantCd=P1&from=2026-11-01&to=2026-11-30").with(admin))
                .andExpect(jsonPath("$.list[?(@.shipId == " + id + ")].noteOpenCnt").value(org.hamcrest.Matchers.contains(1)));
        postJson(admin, S + "/" + id + "/pick", picks(id, "4")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOTE_ACK_REQUIRED"));
        assertThat(stock(stg)).isEqualByComparingTo("0");
        // 확인만인 규칙(필수 아님)도 확인할 수는 있다, 필수 확인 → 통과
        postJson(admin, S + "/" + id + "/notes/" + info + "/ack", "{}").andExpect(status().isOk());
        postJson(admin, S + "/" + id + "/notes/" + req + "/ack", "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notes[?(@.noteId == " + req + ")].acked").value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.data.head.noteOpenCnt").value(0));
        // 두 번 눌러도 한 행
        postJson(admin, S + "/" + id + "/notes/" + req + "/ack", "{}").andExpect(status().isOk());
        assertThat(acks(id)).isEqualTo(2);
        postJson(admin, S + "/" + id + "/pick", picks(id, "4")).andExpect(status().isOk());
        assertThat(stock(stg)).isEqualByComparingTo("4");
        // 피킹 뒤 글을 고침 — 버전이 올라 옛 확인은 무효, 확정 400 → 다시 확인 → 확정
        postJson(admin, N, saveBody(req, null, "PRESS", null, "방청유 + 덮개", "Y", "Y", noteVer(req))).andExpect(status().isOk());
        confirm(id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOTE_ACK_REQUIRED"));
        assertThat(accounts.query(comp, "select status from wms_ship where ship_id = ?", String.class, id)).isEqualTo("PICKING");
        postJson(admin, S + "/" + id + "/notes/" + req + "/ack", "{}").andExpect(status().isOk());
        assertThat(acks(id)).isEqualTo(3);
        confirm(id).andExpect(status().isOk());
        // 출하된 지시는 소급하지 않는다 — 글을 또 고쳐도 목록 "확인 필요" 0, 상세는 확인 안 됨으로 보일 뿐(동작 없음)
        postJson(admin, N, saveBody(req, null, "PRESS", null, "방청유 + 덮개 + 사진", "Y", "Y", noteVer(req))).andExpect(status().isOk());
        mvc.perform(get(S + "/" + id).with(admin)).andExpect(jsonPath("$.data.head.status").value("SHIPPED"))
                .andExpect(jsonPath("$.data.head.noteOpenCnt").value(0));
        // 출하 뒤 확인 = 409, 걸리지 않은 규칙 확인 = 400
        postJson(admin, S + "/" + id + "/notes/" + req + "/ack", "{}").andExpect(status().isConflict());
        long other = note("C-002", null, null, "C-002 전용", "Y");
        long id2 = ship(comp, admin, "C-001", "FG-100", stg);
        postJson(admin, S + "/" + id2 + "/notes/" + other + "/ack", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOTE_NOT_APPLICABLE"));
        // 다른 고객 필수 규칙은 이 지시의 피킹을 막지 않는다(PRESS 필수는 다시 확인해야)
        postJson(admin, S + "/" + id2 + "/notes/" + req + "/ack", "{}").andExpect(status().isOk());
        postJson(admin, S + "/" + id2 + "/pick", picks(id2, "1")).andExpect(status().isOk());
    }

    @Test
    void 피킹_뒤_규칙을_켜면_확정이_막히고_사용을_끄면_풀린다() throws Exception {
        long n = note(null, null, null, "전체", "N");
        long id = ship(comp, admin, "C-001", "FG-100", stg);
        postJson(admin, S + "/" + id + "/pick", picks(id, "2")).andExpect(status().isOk());
        postJson(admin, N, saveBody(n, null, null, null, "전체", "Y", "Y", noteVer(n))).andExpect(status().isOk());
        confirm(id).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOTE_ACK_REQUIRED"));
        postJson(admin, N, saveBody(n, null, null, null, "전체", "Y", "N", noteVer(n))).andExpect(status().isOk());
        confirm(id).andExpect(status().isOk());
        // 확인한 뒤 끈 규칙 — 새 지시·확정 검사에서 빠지고(상세에도 안 보임), 이미 남은 확인 기록은 그대로
        postJson(admin, N, saveBody(n, null, null, null, "전체", "Y", "Y", noteVer(n))).andExpect(status().isOk());
        long id2 = ship(comp, admin, "C-001", "FG-100", stg);
        postJson(admin, S + "/" + id2 + "/notes/" + n + "/ack", "{}").andExpect(status().isOk());
        assertThat(acks(id2)).isEqualTo(1);
        postJson(admin, N, saveBody(n, null, null, null, "전체", "Y", "N", noteVer(n))).andExpect(status().isOk());
        mvc.perform(get(S + "/" + id2).with(admin)).andExpect(jsonPath("$.data.notes.length()").value(0));
        assertThat(acks(id2)).isEqualTo(1);
        long id3 = ship(comp, admin, "C-001", "FG-100", stg);
        postJson(admin, S + "/" + id3 + "/pick", picks(id3, "1")).andExpect(status().isOk());
        confirm(id3).andExpect(status().isOk());
    }

    // =====================================================================
    // PDA
    // =====================================================================

    @Test
    void PDA_피킹도_같은_검사_확인자는_배지_작업자() throws Exception {
        long req = note("C-001", null, null, "C-001 라벨 확인", "Y");
        long id = ship(comp, admin, "C-001", "FG-100", stg);
        accounts.exec(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'W-001', '작업자', 'P1', ?, 't', 't')""", comp, WorkerService.badgeHash(comp, "BADGE-0001"));
        Cookie pda = terminal("PDA-01");
        MockHttpSession s = new MockHttpSession();
        mvc.perform(json(post("/api/pop/badge"), "{\"badge\":\"BADGE-0001\"}").cookie(pda).session(s)).andExpect(status().isOk());
        mvc.perform(get(WH + "/ships/" + id).cookie(pda).session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notes[0].noteId").value(req)).andExpect(jsonPath("$.data.notes[0].acked").value(false));
        mvc.perform(json(post(WH + "/ships/" + id + "/picks"), picks(id, "3")).cookie(pda).session(s)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOTE_ACK_REQUIRED"));
        mvc.perform(json(post(WH + "/ships/" + id + "/notes/" + req + "/ack"), "{}").cookie(pda).session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notes[0].ackWorkerNm").value("작업자"));
        assertThat(accounts.query(comp, "select w.worker_cd from wms_ship_note_ack a join mst_worker w on w.worker_id = a.worker_id where a.ship_id = ?",
                String.class, id)).isEqualTo("W-001");
        mvc.perform(json(post(WH + "/ships/" + id + "/picks"), picks(id, "3")).cookie(pda).session(s)).andExpect(status().isOk());
        assertThat(stock(stg)).isEqualByComparingTo("3");
        // 작업자 없는 단말 = 428
        mvc.perform(json(post(WH + "/ships/" + id + "/notes/" + req + "/ack"), "{}").cookie(pda)).andExpect(status().isPreconditionRequired());
    }

    Cookie terminal(String cd) throws Exception {
        MvcResult m = postJson(admin, "/api/production/terminals", "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\",\"terminalType\":\"PDA\"}").andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        MvcResult a = mvc.perform(json(post("/api/pop/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return new Cookie(PopAuthService.COOKIE, set.substring(set.indexOf('=') + 1, set.indexOf(';')));
    }

    // =====================================================================
    // 저장 검증 · 삭제 금지
    // =====================================================================

    @Test
    void 저장_검증_동시_수정_409_규칙_삭제와_확인_고치기는_DB_가_거부() throws Exception {
        postJson(admin, N, saveBody(null, null, "PRESS", "FG-100", "둘 다", "N", "Y", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOTE_CLASS_AND_ITEM"));
        postJson(admin, N, saveBody(null, "NOPE", null, null, "없는 고객", "N", "Y", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("custCd"));
        postJson(admin, N, saveBody(null, null, null, null, " ", "N", "Y", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        postJson(admin, N, saveBody(null, null, null, null, "글", "X", "Y", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("CODE"));
        long n = note(null, null, null, "전체", "Y");
        int v = noteVer(n);
        postJson(admin, N, saveBody(n, null, null, null, "고침 1", "Y", "Y", v)).andExpect(status().isOk());
        postJson(admin, N, saveBody(n, null, null, null, "고침 2(옛 버전)", "Y", "Y", v)).andExpect(status().isConflict());
        assertThat(accounts.query(comp, "select title from wms_ship_note where note_id = ?", String.class, n)).isEqualTo("고침 1");
        postJson(admin, N, saveBody(999_999_999L, null, null, null, "없음", "Y", "Y", 0)).andExpect(status().isNotFound());
        long id = ship(comp, admin, "C-001", "FG-100", stg);
        postJson(admin, S + "/" + id + "/notes/" + n + "/ack", "{}").andExpect(status().isOk());
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from wms_ship_note where note_id = ?", n))).isNotNull();
        assertThat(catchThrowable(() -> accounts.exec(comp, "update wms_ship_note_ack set acked_by = 'x' where ship_id = ?", id))).isNotNull();
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from wms_ship_note_ack where ship_id = ?", id))).isNotNull();
        assertThat(acks(id)).isEqualTo(1);
    }

    // =====================================================================
    // A사로 B사 · 권한
    // =====================================================================

    @Test
    void A사로_B사_규칙은_없는_것_B사_지시에_안_걸리고_남의_고객_id_는_DB_가_거부() throws Exception {
        long n = note("C-001", null, null, "A사 C-001", "Y");
        long aCust = accounts.query(comp, "select cust_id from mst_cust where cust_cd = 'C-001'", Long.class);
        String b = accounts.newComp("SM");
        long bu = accounts.newUser(b, "admin1", PW);
        accounts.grant(b, bu, "ADM", "sal-order", "SEARCH", "SAVE");
        accounts.grant(b, bu, "ADM", "wms-ship", "SEARCH", "SAVE");
        accounts.grant(b, bu, "ADM", ShipNoteService.PROG, "SEARCH", "SAVE");
        RequestPostProcessor bAdmin = accounts.as(b, bu, "admin1");
        seed(b);
        long bStg = node(b, "STG-01");
        accounts.exec(b, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't')", bStg, b);
        // B사 사용자 — A사 규칙 보기·고치기 404, 목록 0
        mvc.perform(get(N + "/" + n).with(bAdmin)).andExpect(status().isNotFound());
        postJson(bAdmin, N, saveBody(n, null, null, null, "가로채기", "N", "N", 0)).andExpect(status().isNotFound());
        mvc.perform(get(N + "/grid").with(bAdmin)).andExpect(jsonPath("$.total").value(0));
        assertThat(accounts.query(comp, "select title from wms_ship_note where note_id = ?", String.class, n)).isEqualTo("A사 C-001");
        // 같은 고객 코드 C-001 의 B사 지시 — A사 규칙은 걸리지 않고 확인도 안 된다(400), 피킹은 막히지 않음
        long bShip = ship(b, bAdmin, "C-001", "FG-100", bStg);
        mvc.perform(get(S + "/" + bShip).with(bAdmin)).andExpect(jsonPath("$.data.notes.length()").value(0));
        postJson(bAdmin, S + "/" + bShip + "/notes/" + n + "/ack", "{}").andExpect(status().isBadRequest());
        // A사 사용자로 B사 지시 확인 = 404
        postJson(admin, S + "/" + bShip + "/notes/" + n + "/ack", "{}").andExpect(status().isNotFound());
        assertThat(accounts.query(comp, "select count(*) from wms_ship_note_ack", Integer.class)).isZero();
        // B사 범위로 A사 고객 id 를 직접 넣기 — 트리거가 거부
        Throwable t = catchThrowable(() -> accounts.exec(b, """
                insert into wms_ship_note (comp_cd, cust_id, title, created_by, updated_by) values (?, ?, '남의 고객', 't', 't')""", b, aCust));
        assertThat(t).isNotNull();
        assertThat(t.getMessage()).contains("belongs to another company");
    }

    @Test
    void 규칙_화면_권한_없음_403_출하_조회만이면_확인_403() throws Exception {
        long n = note(null, null, null, "전체", "Y");
        long id = ship(comp, admin, "C-001", "FG-100", stg);
        long viewer = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewer, "VIEW", "wms-ship", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer");
        mvc.perform(get(N + "/grid").with(v)).andExpect(status().isForbidden());
        postJson(v, N, saveBody(null, null, null, null, "몰래", "N", "Y", null)).andExpect(status().isForbidden());
        mvc.perform(get(S + "/" + id).with(v)).andExpect(status().isOk()).andExpect(jsonPath("$.data.notes.length()").value(1));
        postJson(v, S + "/" + id + "/notes/" + n + "/ack", "{}").andExpect(status().isForbidden());
        assertThat(acks(id)).isZero();
    }
}
