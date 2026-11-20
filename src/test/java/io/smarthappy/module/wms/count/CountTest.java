package io.smarthappy.module.wms.count;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 재고 실사 (P4 W5, docs/30 4.5) — 센 시각 기준 장부(실사 중 이동 이중 조정 없음), 블라인드, 차이 한도 → 승인, 재실사,
 * 확정 = COUNT_ADJ/OPENING 원장 한 번(동시 확정 포함), 기초 재고. 이상 사용 A~K + A사로 B사 + 직접 SQL.
 */
@AutoConfigureMockMvc
class CountTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String API = "/api/wms/counts";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    RequestPostProcessor clerk;
    long wh;
    long a1;
    long a2;
    long item;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("CT");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "WMS", "wms-count", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, u, "WMS", "wms-move", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        long c = accounts.newUser(comp, "clerk", PW);
        accounts.grant(comp, c, "CLERK", "wms-count", "SEARCH", "SAVE");
        clerk = accounts.as(comp, c, "clerk");
        accounts.addPlant(comp, "P1", "1공장");
        wh = node(comp, "P1", null, "WH", "WH");
        a1 = node(comp, "P1", wh, "LOC", "A-01");
        a2 = node(comp, "P1", wh, "LOC", "A-02");
        item = item(comp, "SUB-1");
    }

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

    /** 1시간 전 입고 — 실사 시작 전 장부 */
    void stockIn(long node, int qty) {
        accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', ?, 'EA', 'MANUAL', now() - interval '1 hour', 'KEY', 't')""",
                comp, item, node, qty);
    }

    BigDecimal stock(long node) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and item_id = ?", BigDecimal.class,
                node, item).stripTrailingZeros();
    }

    ResultActions call(String method, String url, String body, RequestPostProcessor who) throws Exception {
        var b = "PUT".equals(method) ? put(url) : post(url);
        return mvc.perform(b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    ResultActions call(String url, String body) throws Exception {
        return call("POST", url, body, admin);
    }

    static int ver(ResultActions r) throws Exception {
        MvcResult m = r.andReturn();
        return JsonPath.read(m.getResponse().getContentAsString(), "$.data.head.rowVer");
    }

    long create(String type, String extra) throws Exception {
        MvcResult m = call(API, "{\"plantCd\":\"P1\",\"countType\":\"" + type + "\",\"scopeNodeId\":" + wh + extra + "}")
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.head.countId")).longValue();
    }

    int rowVer(long id) {
        return accounts.query(comp, "select row_ver from wms_count where count_id = ?", Integer.class, id);
    }

    long line(long id, long node) {
        return accounts.query(comp, "select max(line_id) from wms_count_line where count_id = ? and node_id = ?", Long.class, id, node);
    }

    ResultActions start(long id) throws Exception {
        return call(API + "/" + id + "/start", "{\"rowVer\":" + rowVer(id) + "}");
    }

    ResultActions enter(long id, String rows) throws Exception {
        return call("PUT", API + "/" + id + "/counts", "{\"rowVer\":" + rowVer(id) + ",\"rows\":[" + rows + "]}", admin);
    }

    String row(long lineId, String qty) {
        return "{\"lineId\":" + lineId + ",\"countedQty\":\"" + qty + "\"}";
    }

    ResultActions act(long id, String action, RequestPostProcessor who) throws Exception {
        return call("POST", API + "/" + id + "/" + action, "{\"rowVer\":" + rowVer(id) + "}", who);
    }

    private String sqlError(String c, String sql, Object... args) {
        Throwable t = catchThrowable(() -> accounts.exec(c, sql, args));
        return t == null ? "OK" : PgErrors.sqlState(t).orElse("?") + " " + PgErrors.constraint(t).orElse("-");
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void 정기_실사_블라인드_차이_승인_확정_원장() throws Exception {
        stockIn(a1, 10);
        stockIn(a2, 5);
        long id = create("CYCLE", "");
        mvc.perform(get(API + "/" + id).with(admin)).andExpect(jsonPath("$.data.head.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.head.blindYn").value("Y")).andExpect(jsonPath("$.data.head.countNo").value(org.hamcrest.Matchers.startsWith("CNT-")));
        start(id).andExpect(status().isOk()).andExpect(jsonPath("$.data.lines.length()").value(2))
                .andExpect(jsonPath("$.data.blindHidden").value(true)).andExpect(jsonPath("$.data.lines[0].bookQty").value(nullValue()));
        enter(id, row(line(id, a1), "8") + "," + row(line(id, a2), "5")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[0].countedQty").value("8"))
                .andExpect(jsonPath("$.data.lines[0].bookQty").value(nullValue()))           // 세는 동안은 장부 안 보냄
                .andExpect(jsonPath("$.data.lines[0].diffQty").value(nullValue()));
        act(id, "submit", admin).andExpect(status().isOk()).andExpect(jsonPath("$.data.blindHidden").value(false))
                .andExpect(jsonPath("$.data.lines[0].bookQty").value("10")).andExpect(jsonPath("$.data.lines[0].diffQty").value("-2"))
                .andExpect(jsonPath("$.data.lines[0].diffPct").value("20")).andExpect(jsonPath("$.data.lines[0].overTol").value(true))
                .andExpect(jsonPath("$.data.lines[1].overTol").value(false));
        // 20% > 한도 5% — 저장 권한만으로는 확정 못 함
        act(id, "post", clerk).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("WMS_COUNT_APPROVAL"));
        act(id, "post", admin).andExpect(status().isOk()).andExpect(jsonPath("$.data.head.status").value("POSTED"))
                .andExpect(jsonPath("$.data.lines[0].adjTrxId").isNumber()).andExpect(jsonPath("$.data.lines[1].adjTrxId").value(nullValue()));
        assertThat(stock(a1)).isEqualByComparingTo("8");
        assertThat(stock(a2)).isEqualByComparingTo("5");
        assertThat(accounts.query(comp, "select trx_type || ':' || qty::int || ':' || src_type from inv_trx where src_type = 'COUNT'",
                String.class)).isEqualTo("COUNT_ADJ:-2:COUNT");
        // 원장 발생 시각 = 센 시각
        assertThat(accounts.query(comp, "select t.occurred_at = l.counted_at from inv_trx t join wms_count_line l on l.adj_trx_id = t.trx_id",
                Boolean.class)).isTrue();
        // 차이가 한도 안이면 저장 권한만으로 확정
        long id2 = create("CYCLE", ",\"blindYn\":\"N\"");
        start(id2);
        enter(id2, row(line(id2, a1), "8") + "," + row(line(id2, a2), "5")).andExpect(jsonPath("$.data.lines[0].bookQty").value("8"));
        act(id2, "submit", clerk).andExpect(status().isOk());
        act(id2, "post", clerk).andExpect(status().isOk());
    }

    @Test
    void D9_실사_중_이동이_있어도_센_시각_장부로_이중_조정_없음() throws Exception {
        stockIn(a1, 10);
        long id = create("FULL", "");
        start(id);
        long l1 = line(id, a1);
        enter(id, row(l1, "9")).andExpect(status().isOk());                            // A-01 을 셌다(그 시각 장부 10 → 1 모자람)
        // 센 뒤에 A-01 → A-02 로 3 이동(실사 중에도 막지 않는다)
        call("/api/wms/move/move", "{\"plantCd\":\"P1\",\"itemId\":" + item + ",\"fromNodeId\":" + a1 + ",\"fromStatus\":\"AVAILABLE\","
                + "\"toNodeId\":" + a2 + ",\"toStatus\":\"AVAILABLE\",\"qty\":3}").andExpect(status().isOk());
        // A-02 는 장부에 없던 칸 — 줄 추가 후 이동 뒤에 셈(3)
        call(API + "/" + id + "/lines", "{\"rowVer\":" + rowVer(id) + ",\"nodeId\":" + a2 + ",\"itemCd\":\"sub-1\"}").andExpect(status().isOk());
        enter(id, row(line(id, a2), "3")).andExpect(status().isOk());
        act(id, "submit", admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[?(@.path == 'P1/WH/A-01')].diffQty").value(org.hamcrest.Matchers.contains("-1")))
                .andExpect(jsonPath("$.data.lines[?(@.path == 'P1/WH/A-02')].diffQty").value(org.hamcrest.Matchers.contains("0")));
        act(id, "post", admin).andExpect(status().isOk());
        // 실물: A-01 = 9 - 3 = 6, A-02 = 3. 이동분(3)을 두 번 빼지 않는다
        assertThat(stock(a1)).isEqualByComparingTo("6");
        assertThat(stock(a2)).isEqualByComparingTo("3");
    }

    @Test
    void 기초_재고_실사는_빈_창고에_줄을_넣어_OPENING_원장() throws Exception {
        long id = create("OPENING", ",\"blindYn\":\"N\"");
        start(id).andExpect(jsonPath("$.data.lines.length()").value(0));
        call(API + "/" + id + "/lines", "{\"rowVer\":" + rowVer(id) + ",\"nodeId\":" + a1 + ",\"itemCd\":\"SUB-1\"}").andExpect(status().isOk());
        call(API + "/" + id + "/lines", "{\"rowVer\":" + rowVer(id) + ",\"nodeId\":" + a1 + ",\"itemCd\":\"SUB-1\"}")
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        String yesterday = accounts.query(comp, "select to_char(now() at time zone 'Asia/Seoul' - interval '1 day', 'YYYY-MM-DD\"T\"HH24:MI')",
                String.class);
        enter(id, "{\"lineId\":" + line(id, a1) + ",\"countedQty\":\"120.5\",\"countedAt\":\"" + yesterday + "\"}")
                .andExpect(jsonPath("$.data.lines[0].bookQty").value("0")).andExpect(jsonPath("$.data.lines[0].diffQty").value("120.5"));
        act(id, "submit", admin).andExpect(status().isOk());
        act(id, "post", admin).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select trx_type || ':' || qty from inv_trx", String.class)).isEqualTo("OPENING:120.500000");
        assertThat(stock(a1)).isEqualByComparingTo("120.5");
        // 장부보다 적게 센 기초 재고는 OPENING(+만)으로 못 넣는다 — 정기 실사로
        long id2 = create("OPENING", ",\"blindYn\":\"N\"");
        start(id2);
        call(API + "/" + id2 + "/lines", "{\"rowVer\":" + rowVer(id2) + ",\"nodeId\":" + a1 + ",\"itemCd\":\"SUB-1\"}");
        enter(id2, row(line(id2, a1), "100"));
        act(id2, "submit", admin);
        act(id2, "post", admin).andExpect(jsonPath("$.errors[0].code").value("OPENING_BELOW_BOOK"));
    }

    @Test
    void 재실사는_새_줄_마지막_줄만_확정() throws Exception {
        stockIn(a1, 10);
        long id = create("CYCLE", ",\"blindYn\":\"N\"");
        start(id);
        long first = line(id, a1);
        enter(id, row(first, "7"));
        act(id, "submit", admin).andExpect(jsonPath("$.data.lines[0].diffQty").value("-3"));
        call(API + "/" + id + "/recount", "{\"rowVer\":" + rowVer(id) + ",\"lineIds\":[" + first + "]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.head.status").value("COUNTING")).andExpect(jsonPath("$.data.lines.length()").value(2))
                .andExpect(jsonPath("$.data.lines[0].latest").value(false)).andExpect(jsonPath("$.data.lines[1].recountNo").value(1));
        enter(id, row(first, "9")).andExpect(jsonPath("$.errors[0].code").value("RECOUNTED"));
        act(id, "submit", admin).andExpect(jsonPath("$.errors[0].code").value("UNCOUNTED"));
        enter(id, row(line(id, a1), "10"));
        act(id, "submit", admin);
        act(id, "post", admin).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from inv_trx where src_type = 'COUNT'", Integer.class)).isZero();   // 다시 세니 맞음
        assertThat(stock(a1)).isEqualByComparingTo("10");
    }

    // =====================================================================
    // 이상 사용
    // =====================================================================

    @Test
    void C_확정_두번은_한번만_동시_포함() throws Exception {
        stockIn(a1, 10);
        long id = create("CYCLE", ",\"blindYn\":\"N\"");
        start(id);
        enter(id, row(line(id, a1), "9.5"));
        act(id, "submit", admin);
        String body = "{\"rowVer\":" + rowVer(id) + "}";
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Integer>> runs = List.of(1, 2).stream().map(i -> CompletableFuture.supplyAsync(() -> {
            try {
                go.await(5, TimeUnit.SECONDS);
                return call("POST", API + "/" + id + "/post", body, admin).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        })).toList();
        go.countDown();
        List<Integer> codes = runs.stream().map(f -> f.join()).sorted().toList();
        assertThat(codes).containsExactly(200, 409);
        assertThat(accounts.query(comp, "select count(*) from inv_trx where src_type = 'COUNT'", Integer.class)).isEqualTo(1);
        assertThat(stock(a1)).isEqualByComparingTo("9.5");
        act(id, "post", admin).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_COUNT_STATE"));
    }

    @Test
    void B_F_입력값과_흐름() throws Exception {
        stockIn(a1, 10);
        call(API, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("plantCd", "countType", "scopeNodeId")));
        long area = node(comp, "P1", null, "AREA", "BODY");
        call(API, "{\"plantCd\":\"P1\",\"countType\":\"DAILY\",\"scopeNodeId\":" + area + ",\"itemCd\":\"NOPE\",\"blindYn\":\"X\"}")
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("countType", "scopeNodeId", "itemCd", "blindYn")));
        long id = create("CYCLE", ",\"itemCd\":\"SUB-1\"");
        // 시작 전 입력·검토, 낡은 rowVer
        enter(id, row(1, "1")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_COUNT_STATE"));
        call(API + "/" + id + "/start", "{\"rowVer\":99}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        call(API + "/" + id + "/start", "{}").andExpect(jsonPath("$.errors[0].field").value("rowVer"));
        start(id).andExpect(status().isOk());
        long l = line(id, a1);
        String future = accounts.query(comp, "select to_char(now() at time zone 'Asia/Seoul' + interval '1 hour', 'YYYY-MM-DD\"T\"HH24:MI')", String.class);
        String before = accounts.query(comp, "select to_char(now() at time zone 'Asia/Seoul' - interval '1 day', 'YYYY-MM-DD\"T\"HH24:MI')", String.class);
        enter(id, row(l, "-1")).andExpect(jsonPath("$.errors[0].code").value("NOT_NEGATIVE")).andExpect(jsonPath("$.errors[0].rowKey").value(String.valueOf(l)));
        enter(id, row(l, "1.1234567")).andExpect(jsonPath("$.errors[0].code").value("SCALE"));
        enter(id, row(l, "열")).andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        enter(id, "{\"lineId\":" + l + ",\"countedQty\":\"1\",\"countedAt\":\"" + future + "\"}").andExpect(jsonPath("$.errors[0].code").value("FUTURE"));
        enter(id, "{\"lineId\":" + l + ",\"countedQty\":\"1\",\"countedAt\":\"" + before + "\"}").andExpect(jsonPath("$.errors[0].code").value("BEFORE_START"));
        enter(id, row(l, "1") + "," + row(l, "2")).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));      // 같은 줄 두 번
        enter(id, row(99999999, "1")).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        // 범위 밖 줄 추가(다른 창고·다른 품목)
        long wh2 = node(comp, "P1", null, "WH", "WH2");
        item(comp, "SUB-2");
        call(API + "/" + id + "/lines", "{\"rowVer\":" + rowVer(id) + ",\"nodeId\":" + wh2 + ",\"itemCd\":\"SUB-2\",\"stockStatus\":\"GOOD\"}")
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("OUT_OF_SCOPE", "OUT_OF_SCOPE", "CODE")));
        act(id, "submit", admin).andExpect(jsonPath("$.errors[0].code").value("UNCOUNTED"));
        // 취소: 사유 없음 → 400, 있음 → CANCELLED, 다시 → 409
        call(API + "/" + id + "/cancel", "{\"rowVer\":" + rowVer(id) + ",\"reason\":\" \"}").andExpect(jsonPath("$.errors[0].field").value("reason"));
        call(API + "/" + id + "/cancel", "{\"rowVer\":" + rowVer(id) + ",\"reason\":\"범위 잘못\"}").andExpect(jsonPath("$.data.head.status").value("CANCELLED"));
        call(API + "/" + id + "/cancel", "{\"rowVer\":" + rowVer(id) + ",\"reason\":\"또\"}").andExpect(status().isConflict());
        assertThat(accounts.query(comp, "select count(*) from inv_trx where src_type = 'COUNT'", Integer.class)).isZero();
    }

    @Test
    void 확정_때_재고가_모자라면_전부_롤백() throws Exception {
        stockIn(a1, 10);
        long id = create("CYCLE", ",\"blindYn\":\"N\"");
        start(id);
        enter(id, row(line(id, a1), "2"));                                              // 8 모자람(그 시각 장부 10)
        act(id, "submit", admin);
        // 확정 전에 다른 사람이 9 를 꺼냄 → 현재 1, 조정 −8 은 마이너스
        call("/api/wms/move/adjust", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + item
                + ",\"stockStatus\":\"AVAILABLE\",\"delta\":-9,\"reasonCd\":\"LOSS\"}").andExpect(status().isOk());
        act(id, "post", admin).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        mvc.perform(get(API + "/" + id).with(admin)).andExpect(jsonPath("$.data.head.status").value("REVIEW"));
    }

    @Test
    void 직접_SQL_상태_건너뛰기_검토중_수정_확정본_수정_삭제_엉터리_원장_연결() throws Exception {
        stockIn(a1, 10);
        long id = create("CYCLE", ",\"blindYn\":\"N\"");
        assertThat(sqlError(comp, "update wms_count set status = 'POSTED', posted_at = now(), posted_by = 'x' where count_id = ?", id))
                .isEqualTo("55000 ck_wms_count_transition");
        start(id);
        long l = line(id, a1);
        enter(id, row(l, "10"));
        act(id, "submit", admin);
        assertThat(sqlError(comp, "update wms_count_line set counted_qty = 1 where line_id = ?", l)).isEqualTo("55000 ck_wms_count_line_state");
        assertThat(sqlError(comp, "update wms_count_line set node_id = ? where line_id = ?", a2, l)).isEqualTo("55000 ck_wms_count_line_fixed");
        long trx = accounts.query(comp, "select max(trx_id) from inv_trx", Long.class);
        assertThat(sqlError(comp, "update wms_count_line set adj_trx_id = ? where line_id = ?", trx, l)).isEqualTo("55000 ck_wms_count_line_post");
        assertThat(sqlError(comp, "update wms_count set scope_node_id = ? where count_id = ?", a1, id)).isEqualTo("55000 ck_wms_count_fixed");
        act(id, "post", admin).andExpect(status().isOk());
        assertThat(sqlError(comp, "update wms_count set remark = 'x' where count_id = ?", id)).isEqualTo("55000 ck_wms_count_done");
        assertThat(sqlError(comp, "delete from wms_count_line where count_id = ?", id)).startsWith("42501");
        assertThat(sqlError(comp, "delete from wms_count where count_id = ?", id)).startsWith("42501");
    }

    @Test
    void A사로_B사_실사_보기_확정_줄_추가() throws Exception {
        stockIn(a1, 10);
        long id = create("CYCLE", ",\"blindYn\":\"N\"");
        start(id);
        String other = accounts.newComp("CB");
        accounts.addPlant(other, "P1", "B사");
        long bu = accounts.newUser(other, "badmin", PW);
        accounts.grant(other, bu, "WMS", "wms-count", "SEARCH", "SAVE", "APPROVE");
        RequestPostProcessor b = accounts.as(other, bu, "badmin");
        mvc.perform(get(API + "/" + id).with(b)).andExpect(status().isNotFound());
        call("POST", API + "/" + id + "/post", "{\"rowVer\":" + rowVer(id) + "}", b).andExpect(status().isNotFound());
        call("PUT", API + "/" + id + "/counts", "{\"rowVer\":" + rowVer(id) + ",\"rows\":[" + row(line(id, a1), "0") + "]}", b)
                .andExpect(status().isNotFound());
        mvc.perform(get(API).param("plantCd", "P1").with(b)).andExpect(jsonPath("$.total").value(0));
        // B사 실사에 A사 위치로
        call("POST", API, "{\"plantCd\":\"P1\",\"countType\":\"CYCLE\",\"scopeNodeId\":" + wh + "}", b)
                .andExpect(jsonPath("$.errors[0].field").value("scopeNodeId"));
        // 직접 SQL: B사 범위에서 A사 실사에 줄
        assertThat(sqlError(other, """
                insert into wms_count_line (comp_cd, count_id, node_id, item_id, stock_status, created_by, updated_by)
                values (?, ?, ?, ?, 'AVAILABLE', 't', 't')""", other, id, a1, item)).isEqualTo("23503 fk_wms_count_line_count_comp");
        assertThat(accounts.exec(other, "update wms_count set remark = 'x' where count_id = ?", id)).isZero();
        assertThat(accounts.query(comp, "select counted_qty is null from wms_count_line where count_id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void 엑셀_내보내기는_EXCEL_권한_첫_열_줄_번호_블라인드면_장부_차이_비움_A사로_B사는_없음() throws Exception {
        stockIn(a1, 10);
        long id = create("CYCLE", "");                                   // 블라인드
        start(id).andExpect(status().isOk());
        long l1 = line(id, a1);
        enter(id, row(l1, "8")).andExpect(status().isOk());
        long x = accounts.newUser(comp, "xl1", PW);
        accounts.grant(comp, x, "XL", "wms-count", "SEARCH", "EXCEL");
        RequestPostProcessor xl = accounts.as(comp, x, "xl1");
        byte[] book = mvc.perform(post(API + "/" + id + "/export").with(csrf()).with(xl)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray();
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(book))) {
            org.apache.poi.ss.usermodel.Sheet sh = wb.getSheetAt(0);
            assertThat(sh.getLastRowNum()).isEqualTo(1);
            org.apache.poi.ss.usermodel.Row r = sh.getRow(1);
            assertThat((long) r.getCell(0).getNumericCellValue()).isEqualTo(l1);              // 붙여넣기 열쇠
            assertThat(r.getCell(8)).as("블라인드 — 장부 비움").isNull();
            assertThat(r.getCell(9).getNumericCellValue()).isEqualTo(8.0);                     // 센 수량(10번째 열 — 화면 붙여넣기 규칙)
            assertThat(r.getCell(10)).as("블라인드 — 차이 비움").isNull();
            assertThat(r.getCell(11).getStringCellValue()).isEqualTo("admin1");
        }
        // EXCEL 권한 없음 → 403
        mvc.perform(post(API + "/" + id + "/export").with(csrf()).with(clerk)).andExpect(status().isForbidden());
        // A사로 B사
        String other = accounts.newComp("CU");
        long bu = accounts.newUser(other, "xl1", PW);
        accounts.grant(other, bu, "XL", "wms-count", "SEARCH", "EXCEL");
        mvc.perform(post(API + "/" + id + "/export").with(csrf()).with(accounts.as(other, bu, "xl1"))).andExpect(status().isNotFound());
    }

    @Test
    void 권한() throws Exception {
        long v = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, v, "VIEW", "wms-count", "SEARCH");
        RequestPostProcessor vw = accounts.as(comp, v, "viewer");
        call("POST", API, "{\"plantCd\":\"P1\",\"countType\":\"CYCLE\",\"scopeNodeId\":" + wh + "}", vw).andExpect(status().isForbidden());
        long id = create("CYCLE", "");
        act(id, "start", vw).andExpect(status().isForbidden());
        mvc.perform(get(API + "/" + id).with(vw)).andExpect(status().isOk());
    }
}
