package io.smarthappy.module.wms.pda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
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
 * 창고 PDA (B7-1, docs/30 12.12) — 단말(PDA) + 배지 작업자로 스캔·이동·실사 센 수량. 이동 원장에 작업자(performed_by)·단말(recorded_by)·SCAN,
 * 같은 clientReqId 재전송 = 처음 결과(오프라인 대기열), 실사는 블라인드 장부 감춤·머리 rowVer 409(덮어쓰기 없음).
 * 거부: 작업자 없음 428, 본문 오류 400, POP 유형·사무 계정 403, 다른 공장·A사로 B사 = 없는 것.
 */
@AutoConfigureMockMvc
class PdaWarehouseTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String WH = "/api/pop/wh";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    String ip;
    RequestPostProcessor admin;
    long wh;
    long a1;
    long a2;
    long item;
    long worker;
    Cookie pda;
    MockHttpSession s;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PD");
        ip = "10.85." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, u, "ADM", "wms-count", "SEARCH", "SAVE", "APPROVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        wh = node(comp, "P1", null, "WH", "WH");
        a1 = node(comp, "P1", wh, "LOC", "A-01");
        a2 = node(comp, "P1", wh, "LOC", "A-02");
        item = item(comp, "SUB-1");
        stockIn(comp, a1, item, 10);
        worker = worker(comp, "W-001", "BADGE-0001");
        pda = terminal("PDA-01", "PDA");
        s = new MockHttpSession();
    }

    // ---------- 도우미 ----------

    long node(String c, String plant, Long parent, String type, String cd) {
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, c, plant, parent, type, cd, cd);
    }

    long item(String c, String cd) {
        return accounts.query(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, ?, ?, 'SUB', 'BUY', 'EA', 'N', 't', 't') returning item_id""", Long.class, c, cd, cd + " 이름");
    }

    void stockIn(String c, long node, long itemId, int qty) {
        accounts.exec(c, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                select ?, plant_cd, 'ADJ_IN', ?, ?, 'AVAILABLE', ?, 'EA', 'MANUAL', now() - interval '1 hour', 'KEY', 't'
                  from mst_equip_hier where node_id = ?""", c, itemId, node, qty, node);
    }

    long worker(String c, String cd, String badge) {
        return accounts.query(c, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, ?, ?, 'P1', ?, 't', 't') returning worker_id""", Long.class, c, cd, cd + " 작업자", WorkerService.badgeHash(c, badge));
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** 단말 등록(공장만, 작업장 없음 = 공장 전체) → 활성화 → 쿠키 */
    Cookie terminal(String cd, String type) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\",\"terminalType\":\"" + type + "\"}").with(admin)).andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        MvcResult a = mvc.perform(json(post("/api/pop/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return new Cookie(PopAuthService.COOKIE, set.substring(set.indexOf('=') + 1, set.indexOf(';')));
    }

    void badgeIn() throws Exception {
        mvc.perform(json(post("/api/pop/badge"), "{\"badge\":\"BADGE-0001\"}").cookie(pda).session(s)).andExpect(status().isOk());
    }

    ResultActions pdaGet(String url) throws Exception {
        return mvc.perform(get(url).cookie(pda).session(s));
    }

    ResultActions pdaPost(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).cookie(pda).session(s));
    }

    String move(String req, long from, long to, String qty) {
        return "{\"clientReqId\":\"" + req + "\",\"itemId\":" + item + ",\"stockStatus\":\"AVAILABLE\",\"fromNodeId\":" + from
                + ",\"toNodeId\":" + to + ",\"qty\":\"" + qty + "\",\"entryMethod\":\"SCAN\"}";
    }

    BigDecimal stock(long node) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and item_id = ?", BigDecimal.class,
                node, item).stripTrailingZeros();
    }

    /** 사무 화면에서 실사 만들고 시작 → id */
    long countStarted() throws Exception {
        MvcResult m = mvc.perform(json(post("/api/wms/counts"), "{\"plantCd\":\"P1\",\"countType\":\"CYCLE\",\"scopeNodeId\":" + wh + "}")
                .with(admin)).andExpect(status().isOk()).andReturn();
        long id = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.head.countId")).longValue();
        mvc.perform(json(post("/api/wms/counts/" + id + "/start"), "{\"rowVer\":" + rowVer(id) + "}").with(admin)).andExpect(status().isOk());
        return id;
    }

    int rowVer(long id) {
        return accounts.query(comp, "select row_ver from wms_count where count_id = ?", Integer.class, id);
    }

    long line(long id, long node) {
        return accounts.query(comp, "select max(line_id) from wms_count_line where count_id = ? and node_id = ?", Long.class, id, node);
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void 위치_스캔_재고_보고_이동하면_원장에_작업자_단말_SCAN_같은_요청은_한_번만() throws Exception {
        badgeIn();
        pdaGet(WH + "/scan?text=A-01").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hit.type").value("LOC")).andExpect(jsonPath("$.data.hit.nodeId").value(a1))
                .andExpect(jsonPath("$.data.stock.length()").value(1))
                .andExpect(jsonPath("$.data.stock[0].itemCd").value("SUB-1")).andExpect(jsonPath("$.data.stock[0].qty").value(10));
        pdaGet(WH + "/scan?text=A-02").andExpect(jsonPath("$.data.stock.length()").value(0));
        pdaGet(WH + "/scan?text=ITEM:SUB-1").andExpect(jsonPath("$.data.hit.type").value("ITEM")).andExpect(jsonPath("$.data.hit.itemId").value(item));

        String req = UUID.randomUUID().toString();
        pdaPost(WH + "/moves", move(req, a1, a2, "3")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay").value(false)).andExpect(jsonPath("$.data.trxIds.length()").value(2));
        assertThat(stock(a1)).isEqualByComparingTo("7");
        assertThat(stock(a2)).isEqualByComparingTo("3");
        String rec = accounts.query(comp, """
                select string_agg(trx_type || ':' || performed_by || ':' || recorded_by || ':' || entry_method, ',' order by trx_id)
                  from inv_trx where client_req_id = ?""", String.class, req);
        long term = accounts.query(comp, "select terminal_id from sys_terminal where terminal_cd = 'PDA-01'", Long.class);
        String who = worker + ":t" + term + ":PDA-01:SCAN";
        assertThat(rec).isEqualTo("MOVE_OUT:" + who + ",MOVE_IN:" + who);
        // 오프라인 대기열이 같은 요청을 다시 보냄 → 처음 결과, 원장 그대로
        pdaPost(WH + "/moves", move(req, a1, a2, "3")).andExpect(status().isOk()).andExpect(jsonPath("$.data.replay").value(true));
        assertThat(stock(a1)).isEqualByComparingTo("7");
        assertThat(accounts.query(comp, "select count(*) from inv_trx where client_req_id = ?", Integer.class, req)).isEqualTo(2);
    }

    /**
     * PQ-1(사용자 결정 — QA #369): 같은 clientReqId 는 같은 내용일 때만 처음 결과. 내용이 다르거나(수량·방향) 회사 안 다른 출처 원장 행이 쓰던 id 면 409,
     * 아무것도 움직이지 않는다 — 화면이 "성공" 을 보이고 재고는 그대로인 조용한 틀림을 막는다. 사무 이동 화면도 같은 규칙
     */
    @Test
    void 같은_요청_id_다른_내용과_다른_출처_행은_409_같은_내용만_처음_결과() throws Exception {
        badgeIn();
        String req = UUID.randomUUID().toString();
        pdaPost(WH + "/moves", move(req, a1, a2, "3")).andExpect(status().isOk()).andExpect(jsonPath("$.data.replay").value(false));
        pdaPost(WH + "/moves", move(req, a1, a2, "3")).andExpect(status().isOk()).andExpect(jsonPath("$.data.replay").value(true));
        pdaPost(WH + "/moves", move(req, a1, a2, "2")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        pdaPost(WH + "/moves", move(req, a2, a1, "3")).andExpect(status().isConflict());
        assertThat(stock(a1)).isEqualByComparingTo("7");
        assertThat(stock(a2)).isEqualByComparingTo("3");
        assertThat(accounts.query(comp, "select count(*) from inv_trx where client_req_id = ?", Integer.class, req)).isEqualTo(2);
        // 다른 출처: 사무 조정 행이 같은 id 를 이미 썼다
        String other = UUID.randomUUID().toString();
        accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by, client_req_id, req_seq)
                values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', 1, 'EA', 'MANUAL', now(), 'KEY', 'office', ?, 1)""", comp, item, a2, other);
        pdaPost(WH + "/moves", move(other, a1, a2, "2")).andExpect(status().isConflict());
        assertThat(stock(a1)).isEqualByComparingTo("7");
        // 사무 이동 화면(같은 StockService.move) — 같은 규칙
        long u = accounts.newUser(comp, "mover", PW);
        accounts.grant(comp, u, "MV", "wms-move", "SEARCH", "SAVE");
        RequestPostProcessor mover = accounts.as(comp, u, "mover");
        String office = "{\"plantCd\":\"P1\",\"itemId\":" + item + ",\"fromNodeId\":" + a1 + ",\"fromStatus\":\"AVAILABLE\",\"toNodeId\":" + a2
                + ",\"toStatus\":\"AVAILABLE\",\"qty\":1,\"clientReqId\":\"" + req + "\"}";
        mvc.perform(json(post("/api/wms/move/move"), office).with(mover)).andExpect(status().isConflict());
        assertThat(stock(a1)).isEqualByComparingTo("7");
    }

    @Test
    void 실사_목록_블라인드_장부는_감추고_센_수량_저장_옛_rowVer_는_409() throws Exception {
        badgeIn();
        long id = countStarted();
        pdaGet(WH + "/counts").andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].countId").value(id));
        int ver = rowVer(id);
        pdaGet(WH + "/counts/" + id).andExpect(status().isOk()).andExpect(jsonPath("$.data.blindHidden").value(true))
                .andExpect(jsonPath("$.data.lines[0].bookQty").value(nullValue()));
        long l1 = line(id, a1);
        pdaPost(WH + "/counts/" + id + "/counts", "{\"rowVer\":" + ver + ",\"rows\":[{\"lineId\":" + l1 + ",\"countedQty\":\"9\"}]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.lines[?(@.lineId == " + l1 + ")].countedQty").value("9"));
        long term = accounts.query(comp, "select terminal_id from sys_terminal where terminal_cd = 'PDA-01'", Long.class);
        assertThat(accounts.query(comp, "select counted_by from wms_count_line where line_id = ?", String.class, l1))
                .isEqualTo("t" + term + ":PDA-01");
        // 같은 화면을 오래 열어 둔 다른 PDA(옛 rowVer) — 덮어쓰지 않고 409, 앞 사람 값 그대로
        pdaPost(WH + "/counts/" + id + "/counts", "{\"rowVer\":" + ver + ",\"rows\":[{\"lineId\":" + l1 + ",\"countedQty\":\"5\"}]}")
                .andExpect(status().isConflict());
        assertThat(accounts.query(comp, "select counted_qty from wms_count_line where line_id = ?", BigDecimal.class, l1))
                .isEqualByComparingTo("9");
    }

    @Test
    void 센_사람은_PDA_배지_작업자_사무에서_다시_세면_비움_직접_SQL_은_다른_회사_작업자와_수량_없는_작업자를_막는다() throws Exception {
        badgeIn();
        long id = countStarted();
        long l1 = line(id, a1);
        // 센 수량 없이 작업자만 — DB 거부(CHECK)
        assertThat(constraint(() -> accounts.exec(comp, "update wms_count_line set counted_worker_id = ? where line_id = ?", worker, l1)))
                .isEqualTo("ck_wms_count_line_worker");
        pdaPost(WH + "/counts/" + id + "/counts", "{\"rowVer\":" + rowVer(id) + ",\"rows\":[{\"lineId\":" + l1 + ",\"countedQty\":\"9\"}]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.lines[?(@.lineId == " + l1 + ")].countedWorkerCd").value("W-001"));
        assertThat(accounts.query(comp, "select counted_worker_id from wms_count_line where line_id = ?", Long.class, l1)).isEqualTo(worker);
        // 사무 화면에서 다시 셈 — 센 사람 = 로그인 사용자, 작업자 비움
        mvc.perform(json(put("/api/wms/counts/" + id + "/counts"), "{\"rowVer\":" + rowVer(id) + ",\"rows\":[{\"lineId\":" + l1
                + ",\"countedQty\":\"8\"}]}").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[?(@.lineId == " + l1 + ")].countedBy").value("admin1"));
        assertThat(accounts.query(comp, "select coalesce(counted_worker_id::text, '-') from wms_count_line where line_id = ?", String.class, l1))
                .isEqualTo("-");
        // A사로 B사 — B사 작업자 id 를 A사 줄에(직접 SQL)
        String b = accounts.newComp("PX");
        accounts.addPlant(b, "P1", "B 공장");
        long bWorker = worker(b, "W-B", "BADGE-B");
        assertThat(constraint(() -> accounts.exec(comp, "update wms_count_line set counted_worker_id = ? where line_id = ?", bWorker, l1)))
                .isEqualTo("fk_wms_count_line_worker_comp");
        assertThat(accounts.query(comp, "select coalesce(counted_worker_id::text, '-') from wms_count_line where line_id = ?", String.class, l1))
                .isEqualTo("-");
    }

    private static String constraint(Runnable r) {
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(r::run);
        assertThat(t).isNotNull();
        return io.smarthappy.platform.common.db.PgErrors.constraint(t).orElse(t.getMessage());
    }

    // =====================================================================
    // 거부 · 권한 · A사로 B사
    // =====================================================================

    @Test
    void 작업자_없으면_428_본문_오류_400_재고보다_많이_옮기면_거부() throws Exception {
        pdaGet(WH + "/scan?text=A-01").andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value("POP_NO_WORKER"));
        pdaPost(WH + "/moves", move(UUID.randomUUID().toString(), a1, a2, "1")).andExpect(status().isPreconditionRequired());
        pdaGet(WH + "/counts").andExpect(status().isPreconditionRequired());
        assertThat(stock(a1)).isEqualByComparingTo("10");
        badgeIn();
        pdaPost(WH + "/moves", move("", a1, a2, "1")).andExpect(status().isBadRequest());                       // 요청 id 필수
        pdaPost(WH + "/moves", move(UUID.randomUUID().toString(), a1, a2, "abc")).andExpect(status().isBadRequest());
        pdaPost(WH + "/moves", move(UUID.randomUUID().toString(), a1, a2, "0")).andExpect(status().isBadRequest());
        pdaPost(WH + "/moves", move(UUID.randomUUID().toString(), a1, a1, "1")).andExpect(status().isBadRequest());   // 같은 자리
        pdaPost(WH + "/moves", move(UUID.randomUUID().toString(), a1, a2, "11")).andExpect(status().is4xxClientError());
        pdaPost(WH + "/moves", move(UUID.randomUUID().toString(), a1, a2, "1").replace("\"SCAN\"", "\"AUTO\""))
                .andExpect(status().isBadRequest());
        pdaGet(WH + "/scan").andExpect(status().isBadRequest());
        assertThat(stock(a1)).isEqualByComparingTo("10");
    }

    @Test
    void POP_유형_단말과_사무_계정은_403() throws Exception {
        Cookie pop = terminal("POP-01", "POP");
        mvc.perform(get(WH + "/scan?text=A-01").cookie(pop)).andExpect(status().isForbidden());
        mvc.perform(json(post(WH + "/moves"), move(UUID.randomUUID().toString(), a1, a2, "1")).cookie(pop)).andExpect(status().isForbidden());
        mvc.perform(get(WH + "/counts").with(admin)).andExpect(status().isForbidden());
        assertThat(stock(a1)).isEqualByComparingTo("10");
    }

    @Test
    void 다른_공장_위치와_실사_A사로_B사는_없는_것() throws Exception {
        badgeIn();
        // 같은 회사 다른 공장 P2 — 위치로 옮기기·P2 실사 id
        accounts.addPlant(comp, "P2", "2공장");
        long wh2 = node(comp, "P2", null, "WH", "WH2");
        long b1 = node(comp, "P2", wh2, "LOC", "B-01");
        pdaPost(WH + "/moves", move(UUID.randomUUID().toString(), a1, b1, "1")).andExpect(status().isBadRequest());
        pdaGet(WH + "/scan?text=B-01").andExpect(jsonPath("$.data.hit.ok").value(false));
        MvcResult m = mvc.perform(json(post("/api/wms/counts"), "{\"plantCd\":\"P2\",\"countType\":\"CYCLE\",\"scopeNodeId\":" + wh2 + "}")
                .with(admin)).andExpect(status().isOk()).andReturn();
        long p2Count = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.head.countId")).longValue();
        pdaGet(WH + "/counts/" + p2Count).andExpect(status().isNotFound());
        pdaPost(WH + "/counts/" + p2Count + "/counts", "{\"rowVer\":0,\"rows\":[]}").andExpect(status().isNotFound());
        // B사 — 같은 코드의 위치·실사가 있어도 A사 PDA 에는 없는 것
        String b = accounts.newComp("PE");
        accounts.addPlant(b, "P1", "B사 공장");
        long bwh = node(b, "P1", null, "WH", "WH");
        long bLoc = node(b, "P1", bwh, "LOC", "Z-09");
        long bItem = item(b, "SUB-9");
        stockIn(b, bLoc, bItem, 4);
        pdaGet(WH + "/scan?text=Z-09").andExpect(jsonPath("$.data.hit.ok").value(false)).andExpect(jsonPath("$.data.stock.length()").value(0));
        pdaPost(WH + "/moves", move(UUID.randomUUID().toString(), a1, bLoc, "1")).andExpect(status().isBadRequest());
        long bCount = accounts.query(b, """
                insert into wms_count (comp_cd, plant_cd, count_no, count_type, scope_node_id, blind_yn, tol_pct, status, created_by, updated_by)
                values (?, 'P1', 'CNT-B', 'CYCLE', ?, 'Y', 0, 'DRAFT', 't', 't') returning count_id""", Long.class, b, bwh);
        pdaGet(WH + "/counts/" + bCount).andExpect(status().isNotFound());
        assertThat(stock(a1)).isEqualByComparingTo("10");
        assertThat(accounts.query(b, "select coalesce(sum(qty), 0) from inv_stock where node_id = ?", BigDecimal.class, bLoc))
                .isEqualByComparingTo("4");
    }
}
