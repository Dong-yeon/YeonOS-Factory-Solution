package io.smarthappy.module.wms.inv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.qms.link.InspPlanLookup;
import io.smarthappy.module.wms.link.QualityStockLink;
import io.smarthappy.module.wms.link.StockQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * B3 품질 상태 연동 (docs/30 12.15) — QMS 호출 4곳은 QMS 세션 몫이라 여기서는 계약 {@link QualityStockLink} 를 직접 부른다.
 * 검사·NCR 행은 소유자 연결에서 트리거를 끄고 넣는다(QMS 검사계획·측정 흐름 없이 판정 결과만 — WMS 는 그 표를 읽기만).
 * 정상(OK·NG·무효·특채·폐기·QC 고정) + 이상 사용(스위치 꺼짐·비관리·유형·QC 없음·부족·단위·처분 뒤 무효·화면 역분개·A사로 B사) + 동시(판정 대 이동).
 */
@AutoConfigureMockMvc
class QualityStockTest extends PostgresTestBase {

    private static final String PW = "Quartz-river-88";
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    QualityStockLink link;

    @Autowired
    StockQuery stockQuery;

    @Autowired
    ProductionReceiptPosting prdPosting;

    @MockitoBean
    InspPlanLookup plans;

    String comp;
    RequestPostProcessor admin;
    long a1;
    long a2;
    long p2Loc;
    long lotItem;
    long plainItem;
    int seq;

    @AfterAll
    static void stop() {
        POOL.shutdownNow();
    }

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("QS");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "WMS", "wms-rcv", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-move", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-trx", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-stock", "SEARCH", "FN1");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.addPlant(comp, "P2", "2공장");
        long wh = node(comp, "P1", null, "WH", "QS-WH");
        a1 = node(comp, "P1", wh, "LOC", "A-01");
        a2 = node(comp, "P1", wh, "LOC", "A-02");
        long wh2 = node(comp, "P2", null, "WH", "QS-WH2");
        p2Loc = node(comp, "P2", wh2, "LOC", "B-01");
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'RM-Q', 'LOT 관리 자재', 'RM', 'BUY', 'EA', 'Y', 't', 't'), (?, 'RM-N', '비관리 자재', 'RM', 'BUY', 'EA', 'N', 't', 't')""",
                comp, comp);
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'SUP1', '공급사', 'PURCHASE', 't', 't')",
                comp);
        lotItem = accounts.query(comp, "select item_id from mst_item where item_cd = 'RM-Q'", Long.class);
        plainItem = accounts.query(comp, "select item_id from mst_item where item_cd = 'RM-N'", Long.class);
        policy(comp, "P1", "Y");
        when(plans.lines(any(Long.class), isNull(), any(), any())).thenReturn(List.of());
    }

    // ------------------------------------------------------------------ 도움

    private long node(String c, String plant, Long parent, String type, String cd) {
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, c, plant, parent, type, cd, cd);
    }

    private void policy(String c, String plant, String v) {
        accounts.exec(c, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) "
                + "values (?, ?, 'WMS_QUALITY_LINK', ?, 't', 't')", c, plant, v);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(admin));
    }

    /** 자재 입고(QC 등) → LOT id */
    private long receive(long item, String plant, long nodeId, String qty, String status) throws Exception {
        MvcResult m = postJson("/api/wms/rcv", "{\"plantCd\":\"" + plant + "\",\"nodeId\":" + nodeId + ",\"itemId\":" + item + ",\"qty\":" + qty
                + ",\"stockStatus\":\"" + status + "\",\"supplierCd\":\"SUP1\",\"clientReqId\":\"qs-rcv-" + (++seq) + "-" + System.nanoTime() + "\"}")
                .andExpect(status().isOk()).andReturn();
        String lotNo = JsonPath.read(m.getResponse().getContentAsString(), "$.data.lotNo");
        return accounts.query(comp, "select lot_id from lot_master where lot_no = ?", Long.class, lotNo);
    }

    private BigDecimal qty(long lot, String st) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where lot_id = ? and stock_status = ?", BigDecimal.class, lot, st)
                .stripTrailingZeros();
    }

    private BigDecimal plainQty(long nodeId, String st) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where item_id = ? and node_id = ? and stock_status = ?",
                BigDecimal.class, plainItem, nodeId, st).stripTrailingZeros();
    }

    private String lotNo(long lot) {
        return accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, lot);
    }

    /** 소유자 연결(트리거 끔) — QMS 판정 결과만 넣는다 */
    private long ownerInsert(String sql, Object... args) throws Exception {
        try (Connection con = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = con.createStatement()) {
            st.execute("set session_replication_role = replica");
            try (var ps = con.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }
    }

    private long insp(String c, long lot, long item, String type, String result) throws Exception {
        return ownerInsert("""
                insert into qms_insp (comp_cd, insp_no, insp_type, lot_id, item_id, insp_plan_id, lot_qty, status, result, occurred_at,
                                      judged_at, judged_by, created_by, updated_by)
                values (?, ?, ?, ?, ?, 0, 10, 'JUDGED', ?, now(), now(), 'qa1', 'qa1', 'qa1') returning insp_id""",
                c, "QI-" + (++seq) + "-" + System.nanoTime() % 100000, type, lot, item, result);
    }

    /** 생산 LOT(완제품 FG-Q, 작업지시 하나) → {lotId, woId}. 품목은 처음 한 번 만든다 */
    private long[] prdLot() throws Exception {
        Long fg = accounts.query(comp, "select max(item_id) from mst_item where item_cd = 'FG-Q'", Long.class);
        if (fg == null) {
            fg = accounts.query(comp, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                    values (?, 'FG-Q', 'LOT 관리 완제품', 'FG', 'MAKE', 'EA', 'Y', 't', 't') returning item_id""", Long.class, comp);
        }
        long wo = ownerInsert("insert into prd_wo (comp_cd, wo_no, plant_cd, item_id, plan_qty, unit_cd, plan_start, plan_end, routing_id, status, "
                + "released_at, created_by, updated_by) values (?, ?, 'P1', ?, 100, 'EA', " + TestAccounts.COMP_TODAY + ", " + TestAccounts.COMP_TODAY
                + ", 0, 'IN_PROGRESS', now(), 't', 't') returning wo_id", comp, "WO-" + (++seq), fg);
        long lot = ownerInsert("insert into lot_master (comp_cd, lot_no, item_id, origin, wo_id, qty_created, unit_cd, occurred_at, created_by, updated_by) "
                + "values (?, ?, ?, 'PRODUCED', ?, 100, 'EA', now(), 't', 't') returning lot_id", comp, "PL-" + (++seq), fg, wo);
        return new long[] {lot, wo, fg};
    }

    /** 생산 입고(MES 입고 행 → WMS 게시) */
    private void prdIn(long[] l, long nodeId, int qty) throws Exception {
        long r = ownerInsert("insert into prd_receipt (comp_cd, plant_cd, wo_id, item_id, lot_id, node_id, qty, unit_cd, client_req_id, occurred_at, "
                + "entry_method, recorded_by) values (?, 'P1', ?, ?, ?, ?, ?, 'EA', ?, now(), 'SCAN', 'mes-user') returning receipt_id",
                comp, l[1], l[2], l[0], nodeId, qty, "req-" + System.nanoTime());
        runner.callAs(comp, "mes-user", () -> prdPosting.post(r));
    }

    /** 열린 검사(판정 전) */
    private long openInsp(String c, long lot, long item, String type) throws Exception {
        return ownerInsert("""
                insert into qms_insp (comp_cd, insp_no, insp_type, lot_id, item_id, insp_plan_id, lot_qty, status, occurred_at, created_by, updated_by)
                values (?, ?, ?, ?, ?, 0, 10, 'OPEN', now(), 'qa1', 'qa1') returning insp_id""",
                c, "QO-" + (++seq) + "-" + System.nanoTime() % 100000, type, lot, item);
    }

    /** 대사 지금 실행 → 결과 JSON */
    private String reconcile() throws Exception {
        return postJson("/api/wms/stock/reconcile", "{}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void voidInsp(String c, long inspId) throws Exception {
        ownerInsert("update qms_insp set status = 'VOID', void_reason = '무효', voided_at = now(), voided_by = 'qa1' where insp_id = ? returning insp_id",
                inspId);
    }

    /** 종결된 NCR + 처분 행들("유형:수량") */
    private long ncr(String c, long lot, long item, String unit, String... disps) throws Exception {
        long id = ownerInsert("""
                insert into qms_ncr (comp_cd, ncr_no, source, lot_id, item_id, unit_cd, defect_id, nc_qty, status, closed_at, closed_by,
                                     occurred_at, description, hold_yn, created_by, updated_by)
                values (?, ?, 'INSP', ?, ?, ?, 0, 10, 'CLOSED', now(), 'qa1', now(), '불량', 'N', 'qa1', 'qa1') returning ncr_id""",
                c, "NCR-" + (++seq) + "-" + System.nanoTime() % 100000, lot, item, unit);
        int no = 0;
        for (String d : disps) {
            String[] p = d.split(":");
            ownerInsert("insert into qms_ncr_disp (comp_cd, ncr_id, line_no, disp_type, qty, created_by) values (?, ?, ?, ?, ?::numeric, 'qa1') "
                    + "returning disp_id", c, id, ++no, p[0], p[1]);
        }
        return id;
    }

    private void judged(long inspId) {
        runner.callAs(comp, "qa1", () -> {
            link.onInspJudged(inspId);
            return null;
        });
    }

    private Throwable voided(long inspId) {
        return catchThrowable(() -> runner.callAs(comp, "qa1", () -> {
            link.onInspVoided(inspId);
            return null;
        }));
    }

    private Throwable closed(long ncrId) {
        return catchThrowable(() -> runner.callAs(comp, "qa1", () -> {
            link.onNcrClosed(ncrId);
            return null;
        }));
    }

    private static HbErrorCode code(Throwable t) {
        assertThat(t).isInstanceOf(BusinessException.class);
        return ((BusinessException) t).code();
    }

    // ------------------------------------------------------------------ 정상

    @Test
    void 판정_OK는_QC를_가용으로_NG는_부적합으로_같은_위치_짝이고_다시_불러도_한_번() throws Exception {
        long lot = receive(lotItem, "P1", a1, "10", "QC");
        long ok = insp(comp, lot, lotItem, "RECEIVE", "OK");
        judged(ok);
        assertThat(qty(lot, "QC")).isEqualByComparingTo("0");
        assertThat(qty(lot, "AVAILABLE")).isEqualByComparingTo("10");
        assertThat(accounts.query(comp, "select string_agg(trx_type || ':' || stock_status || ':' || trim_scale(qty) || ':' || entry_method || ':' || recorded_by, ',' "
                + "order by req_seq) from inv_trx where src_type = 'QMS_INSP' and src_id = ?", String.class, ok))
                .isEqualTo("STATUS_OUT:QC:-10:AUTO:qa1,STATUS_IN:AVAILABLE:10:AUTO:qa1");
        judged(ok);                                                                   // 같은 판정 다시
        assertThat(accounts.query(comp, "select count(*) from inv_trx where src_type = 'QMS_INSP'", Integer.class)).isEqualTo(2);
        // QMS 확인 문구용 읽기(WB-3)
        Map<String, BigDecimal> m = runner.call(comp, () -> stockQuery.lotStatusQty(lot));
        assertThat(m).containsOnlyKeys("AVAILABLE");
        // FINAL NG → BLOCKED
        long lot2 = receive(lotItem, "P1", a2, "6", "QC");
        judged(insp(comp, lot2, lotItem, "FINAL", "NG"));
        assertThat(qty(lot2, "BLOCKED")).isEqualByComparingTo("6");
        assertThat(qty(lot2, "QC")).isEqualByComparingTo("0");
    }

    @Test
    void 무효는_판정을_되돌리고_재고가_나갔으면_409_그대로() throws Exception {
        long lot = receive(lotItem, "P1", a1, "10", "QC");
        long id = insp(comp, lot, lotItem, "RECEIVE", "OK");
        judged(id);
        voidInsp(comp, id);
        assertThat(voided(id)).isNull();
        assertThat(qty(lot, "QC")).isEqualByComparingTo("10");
        assertThat(qty(lot, "AVAILABLE")).isEqualByComparingTo("0");
        // 다시 판정 → 풀린 뒤 가용 4 를 내보냄 → 그 판정 무효는 모자라 409(원장·판정이 갈라지지 않게, D-B30)
        long again = insp(comp, lot, lotItem, "RECEIVE", "OK");
        judged(again);
        postJson("/api/wms/move/adjust", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + lotItem + ",\"stockStatus\":\"AVAILABLE\","
                + "\"delta\":-4,\"reasonCd\":\"LOSS\",\"lotNo\":\"" + lotNo(lot) + "\"}").andExpect(status().isOk());
        voidInsp(comp, again);
        assertThat(code(voided(again))).isEqualTo(HbErrorCode.INSUFFICIENT_STOCK);
        assertThat(qty(lot, "AVAILABLE")).isEqualByComparingTo("6");
        // 판정 전 검사 무효는 아무것도 안 함
        long none = insp(comp, lot, lotItem, "RECEIVE", "OK");
        voidInsp(comp, none);
        assertThat(voided(none)).isNull();
    }

    @Test
    void NCR_종결은_특채만_가용으로_폐기는_SCRAP_나머지는_부적합_그대로_처분_뒤_무효는_409() throws Exception {
        long lot = receive(lotItem, "P1", a1, "10", "QC");
        long ng = insp(comp, lot, lotItem, "RECEIVE", "NG");
        judged(ng);
        long n = ncr(comp, lot, lotItem, "EA", "USE_AS_IS:4", "SCRAP:3", "RETURN:3");
        assertThat(closed(n)).isNull();
        assertThat(qty(lot, "AVAILABLE")).isEqualByComparingTo("4");
        assertThat(qty(lot, "BLOCKED")).isEqualByComparingTo("3");
        assertThat(accounts.query(comp, "select string_agg(trx_type || ':' || stock_status || ':' || trim_scale(qty), ',' order by req_seq) "
                + "from inv_trx where src_type = 'QMS_NCR'", String.class))
                .isEqualTo("STATUS_OUT:BLOCKED:-4,STATUS_IN:AVAILABLE:4,SCRAP:BLOCKED:-3");
        assertThat(closed(n)).isNull();                                               // 다시 불러도 한 번
        assertThat(accounts.query(comp, "select count(*) from inv_trx where src_type = 'QMS_NCR'", Integer.class)).isEqualTo(3);
        // 처분이 반영된 LOT 의 검사 무효 → 409 WMS_QUALITY_DISPOSED(WC-2), 재고 그대로
        voidInsp(comp, ng);
        assertThat(code(voided(ng))).isEqualTo(HbErrorCode.WMS_QUALITY_DISPOSED);
        assertThat(qty(lot, "BLOCKED")).isEqualByComparingTo("3");
    }

    @Test
    void 입고는_스위치_켜진_공장의_검사계획_있는_LOT_관리_품목만_QC로() throws Exception {
        when(plans.lines(eq(lotItem), isNull(), eq("RECEIVE"), any())).thenReturn(List.of(new InspPlanLookup.PlanLine(1L, "A", 1, 1L, "C1", "길이",
                null, "VARIABLE", null, null, null, null, null, null, "VISUAL", null, "EVERY_LOT", null, null)));
        long lot = receive(lotItem, "P1", a1, "5", "AVAILABLE");                       // 화면은 가용으로 보냄
        assertThat(qty(lot, "QC")).isEqualByComparingTo("5");
        long p2 = receive(lotItem, "P2", p2Loc, "5", "AVAILABLE");                      // 스위치 꺼진 공장
        assertThat(qty(p2, "AVAILABLE")).isEqualByComparingTo("5");
        receive(plainItem, "P1", a1, "5", "AVAILABLE");                                 // 비관리 품목
        assertThat(plainQty(a1, "AVAILABLE")).isEqualByComparingTo("5");
    }

    /** 입고 화면 안내(12.15.6) — 저장 때 QC 로 고정되는 조합만 true. 서버 저장 규칙과 같은 판정을 쓴다 */
    @Test
    void 입고_화면_QC_고정_안내는_스위치_공장의_검사계획_있는_LOT_관리_품목만() throws Exception {
        when(plans.lines(eq(lotItem), isNull(), eq("RECEIVE"), any())).thenReturn(List.of(new InspPlanLookup.PlanLine(1L, "A", 1, 1L, "C1", "길이",
                null, "VARIABLE", null, null, null, null, null, null, "VISUAL", null, "EVERY_LOT", null, null)));
        mvc.perform(get("/api/wms/rcv/qc-fixed?plantCd=P1&itemId=" + lotItem).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
        mvc.perform(get("/api/wms/rcv/qc-fixed?plantCd=P2&itemId=" + lotItem).with(admin)).andExpect(jsonPath("$.data").value(false));
        mvc.perform(get("/api/wms/rcv/qc-fixed?plantCd=P1&itemId=" + plainItem).with(admin)).andExpect(jsonPath("$.data").value(false));
        mvc.perform(get("/api/wms/rcv/qc-fixed?plantCd=P1").with(admin)).andExpect(jsonPath("$.data").value(false));
        String other = accounts.newComp("QX");                                         // A사로 B사 품목 — 없는 품목
        accounts.exec(other, "insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by) "
                + "values (?, 'RM-Q', 'B사 자재', 'RM', 'BUY', 'EA', 'Y', 't', 't')", other);
        long otherItem = accounts.query(other, "select item_id from mst_item where item_cd = 'RM-Q'", Long.class);
        mvc.perform(get("/api/wms/rcv/qc-fixed?plantCd=P1&itemId=" + otherItem).with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
    }

    /** QMS 할 일 후보 읽기(12.15.6 "검사 만들기") — 스위치 공장의 QC 재고 LOT 만, lot_id 순 키셋, 상한에서 잘림 표시 */
    @Test
    void QC_재고_LOT_읽기는_스위치_공장만_상한에서_잘림_표시() throws Exception {
        long l1 = receive(lotItem, "P1", a1, "5", "QC");
        long l2 = receive(lotItem, "P1", a2, "4", "QC");
        long l3 = receive(lotItem, "P1", a1, "3", "QC");
        receive(lotItem, "P1", a1, "7", "AVAILABLE");                                  // 가용 — 후보 아님
        receive(lotItem, "P2", p2Loc, "6", "QC");                                      // 스위치 꺼진 공장
        StockQuery.QcLots first = runner.call(comp, () -> stockQuery.qcLots("P1", null, 2));
        assertThat(first.lots()).extracting(StockQuery.QcLot::lotId).containsExactly(l1, l2);
        assertThat(first.truncated()).isTrue();
        assertThat(first.lots().get(0).qcQty()).isEqualByComparingTo("5");
        assertThat(first.lots().get(0).lotNo()).isEqualTo(lotNo(l1));
        StockQuery.QcLots next = runner.call(comp, () -> stockQuery.qcLots("P1", l2, 2));
        assertThat(next.lots()).extracting(StockQuery.QcLot::lotId).containsExactly(l3);
        assertThat(next.truncated()).isFalse();
        judged(insp(comp, l1, lotItem, "RECEIVE", "OK"));                               // 판정으로 QC 가 0 → 빠진다
        assertThat(runner.call(comp, () -> stockQuery.qcLots("P1", null, 0)).lots()).extracting(StockQuery.QcLot::lotId).containsExactly(l2);
        assertThat(runner.call(comp, () -> stockQuery.qcLots("P1", null, 9999)).lots()).extracting(StockQuery.QcLot::lotId)
                .containsExactly(l2, l3);                                               // 범위 밖 limit 은 끝값(1·500)
        assertThat(runner.call(comp, () -> stockQuery.qcLots("P2", null, 10))).isEqualTo(StockQuery.QcLots.EMPTY);
        assertThat(runner.call(comp, () -> stockQuery.qcLots(null, null, 10))).isEqualTo(StockQuery.QcLots.EMPTY);
    }

    /** 대사(12.15.12) — QMS 호출이 빠진 경로를 찾는다: 판정이 끝났는데 남은 QC LOT · 무효인데 살아 있는 판정 원장. 자동 보정 없음 */
    @Test
    void 대사는_판정이_빠진_QC_LOT과_무효인데_살아_있는_판정_원장을_세고_고치지_않는다() throws Exception {
        long missed = receive(lotItem, "P1", a1, "5", "QC");
        insp(comp, missed, lotItem, "RECEIVE", "OK");                                // 판정만 — WMS 호출이 빠짐
        long reInsp = receive(lotItem, "P1", a1, "4", "QC");
        insp(comp, reInsp, lotItem, "RECEIVE", "NG");
        openInsp(comp, reInsp, lotItem, "RECEIVE");                                  // 다시 검사 중 — 센 대상 아님
        long p2 = receive(lotItem, "P2", p2Loc, "3", "QC");
        insp(comp, p2, lotItem, "RECEIVE", "OK");                                    // 스위치 꺼진 공장
        long proc = receive(lotItem, "P1", a2, "2", "QC");
        insp(comp, proc, lotItem, "PROCESS", "OK");                                  // 공정검사는 재고와 무관
        long done = receive(lotItem, "P1", a2, "6", "QC");
        long doneInsp = insp(comp, done, lotItem, "RECEIVE", "OK");
        judged(doneInsp);                                                            // 정상 반영
        voidInsp(comp, doneInsp);                                                    // 무효만 — WMS 호출이 빠짐
        int outbox = accounts.query(comp, "select count(*) from sys_outbox where topic = 'NOTIFY'", Integer.class);

        String r = reconcile();
        assertThat((Integer) JsonPath.read(r, "$.data.qcJudged")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(r, "$.data.qmsVoidLive")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(r, "$.data.mismatchCount")).isZero();
        assertThat(accounts.query(comp, "select count(*) from sys_outbox where topic = 'NOTIFY'", Integer.class)).isEqualTo(outbox + 1);
        assertThat(qty(missed, "QC")).isEqualByComparingTo("5");                    // 고치지 않는다
        assertThat(qty(done, "AVAILABLE")).isEqualByComparingTo("6");

        String other = accounts.newComp("QR");                                        // A사로 B사 — B사 대사는 A사 것을 세지 않는다
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "WMS", "wms-stock", "SEARCH", "FN1");
        String rb = mvc.perform(post("/api/wms/stock/reconcile").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")
                .with(accounts.as(other, bu, "admin1"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(rb, "$.data.qcJudged")).isZero();
        assertThat((Integer) JsonPath.read(rb, "$.data.qmsVoidLive")).isZero();

        judged(accounts.query(comp, "select insp_id from qms_insp where lot_id = ? and status = 'JUDGED'", Long.class, missed));  // 빠진 호출을 다시
        assertThat(voided(doneInsp)).isNull();
        String after = reconcile();
        assertThat((Integer) JsonPath.read(after, "$.data.qcJudged")).isZero();
        assertThat((Integer) JsonPath.read(after, "$.data.qmsVoidLive")).isZero();
        assertThat(qty(done, "QC")).isEqualByComparingTo("6");
    }

    /** B-1(QA 2026-10-24, 상위 결정): 스위치는 새 판정 반영만 끈다 — 이미 반영된 판정의 NCR 종결은 스위치를 꺼도 반영. 반영된 적 없는 LOT 은 그대로 */
    @Test
    void 판정_뒤_스위치를_꺼도_NCR_종결은_반영되고_반영_안_된_LOT은_그대로() throws Exception {
        long lot = receive(lotItem, "P1", a1, "10", "QC");
        judged(insp(comp, lot, lotItem, "RECEIVE", "NG"));
        assertThat(qty(lot, "BLOCKED")).isEqualByComparingTo("10");
        long never = receive(lotItem, "P2", p2Loc, "10", "QC");                    // 스위치 꺼진 공장 — 판정이 반영되지 않음
        judged(insp(comp, never, lotItem, "RECEIVE", "NG"));
        assertThat(qty(never, "QC")).isEqualByComparingTo("10");
        accounts.exec(comp, "update sys_policy set policy_value = 'N' where policy_key = 'WMS_QUALITY_LINK' and plant_cd = 'P1'");

        assertThat(closed(ncr(comp, lot, lotItem, "EA", "SCRAP:4", "USE_AS_IS:3"))).isNull();
        assertThat(qty(lot, "BLOCKED")).isEqualByComparingTo("3");
        assertThat(qty(lot, "AVAILABLE")).isEqualByComparingTo("3");
        assertThat(accounts.query(comp, "select coalesce(sum(qty), 0) from inv_trx where lot_id = ? and trx_type = 'SCRAP'", BigDecimal.class, lot))
                .isEqualByComparingTo("-4");
        assertThat(closed(ncr(comp, never, lotItem, "EA", "SCRAP:4"))).isNull();
        assertThat(qty(never, "QC")).isEqualByComparingTo("10");                     // 반영 안 된 LOT — 그대로(로그만)
        assertThat(accounts.query(comp, "select count(*) from inv_trx where lot_id = ? and src_type = 'QMS_NCR'", Integer.class, never)).isZero();
    }

    /**
     * B-2(QA 2026-10-24, QMS·상위 합의 — 12.15.13): 판정된 LOT 에 생산 입고가 더 들어오면 — OK 면 QC(재검사), NG 면 판정을 이어받아 BLOCKED.
     * NG 가 무효로 사라지면 이어받은 추가분도 QC 로. 다른 NG 가 살아 있으면 그대로. 대사는 이 몫을 "판정 빠짐" 으로 세지 않는다
     */
    @Test
    void 판정된_LOT에_더_들어온_생산_입고는_OK면_재검사_QC_NG면_BLOCKED_무효면_QC로() throws Exception {
        long[] ok = prdLot();
        when(plans.lines(eq(ok[2]), isNull(), eq("FINAL"), any())).thenReturn(List.of(new InspPlanLookup.PlanLine(2L, "A", 1, 1L, "C1", "외관",
                null, "ATTRIBUTE", null, null, null, null, null, null, "VISUAL", null, "EVERY_LOT", null, null)));
        prdIn(ok, a1, 10);
        judged(insp(comp, ok[0], ok[2], "FINAL", "OK"));
        prdIn(ok, a1, 5);
        assertThat(qty(ok[0], "AVAILABLE")).isEqualByComparingTo("10");
        assertThat(qty(ok[0], "QC")).isEqualByComparingTo("5");                      // 추가분은 재검사

        long[] ng = prdLot();
        prdIn(ng, a1, 10);
        long ngInsp = insp(comp, ng[0], ng[2], "FINAL", "NG");
        judged(ngInsp);
        prdIn(ng, a2, 4);
        assertThat(qty(ng[0], "BLOCKED")).isEqualByComparingTo("14");               // 판정을 이어받음
        assertThat(accounts.query(comp, "select stock_status from inv_trx where lot_id = ? and trx_type = 'RCV_PRD' order by trx_id desc limit 1",
                String.class, ng[0])).isEqualTo("BLOCKED");
        String r = reconcile();
        assertThat((Integer) JsonPath.read(r, "$.data.qcJudged")).isZero();         // OK LOT 의 추가 QC 는 빠짐이 아님
        assertThat((Integer) JsonPath.read(r, "$.data.qmsVoidLive")).isZero();

        voidInsp(comp, ngInsp);
        assertThat(voided(ngInsp)).isNull();
        assertThat(qty(ng[0], "QC")).isEqualByComparingTo("14");                    // 판정 되돌림 10 + 이어받은 4
        assertThat(qty(ng[0], "BLOCKED")).isZero();
        r = reconcile();
        assertThat((Integer) JsonPath.read(r, "$.data.qmsVoidLive")).isZero();      // 되돌린 짝(→ QC)은 살아 있는 판정 원장이 아님
        assertThat((Integer) JsonPath.read(r, "$.data.qcJudged")).isZero();
        prdIn(ng, a1, 3);
        assertThat(qty(ng[0], "QC")).isEqualByComparingTo("17");                     // NG 가 사라졌으니 계획대로 QC

        long[] two = prdLot();                                                       // NG 둘 — 나중 것만 무효면 앞 NG 가 살아 있어 그대로
        prdIn(two, a1, 10);
        judged(insp(comp, two[0], two[2], "FINAL", "NG"));
        long ng2 = insp(comp, two[0], two[2], "FINAL", "NG");
        judged(ng2);                                                                 // QC 없음 — 반영 없음
        prdIn(two, a1, 2);
        voidInsp(comp, ng2);
        assertThat(voided(ng2)).isNull();
        assertThat(qty(two[0], "BLOCKED")).isEqualByComparingTo("12");
    }

    // ------------------------------------------------------------------ 이상 사용

    @Test
    void 반영_안_함_스위치_꺼짐_비관리_공정검사_QC_없음() throws Exception {
        long p2 = receive(lotItem, "P2", p2Loc, "5", "QC");
        judged(insp(comp, p2, lotItem, "RECEIVE", "OK"));
        assertThat(qty(p2, "QC")).isEqualByComparingTo("5");                         // P2 스위치 N
        long plainLot = receive(plainItem, "P1", a1, "5", "QC");
        judged(insp(comp, plainLot, plainItem, "RECEIVE", "OK"));
        assertThat(plainQty(a1, "QC")).isEqualByComparingTo("5");                     // 비관리(D-B27)
        assertThat(closed(ncr(comp, plainLot, plainItem, "EA", "SCRAP:5"))).isNull();   // NCR 종결도 막지 않음(D-B27 확장)
        long lot = receive(lotItem, "P1", a1, "5", "QC");
        judged(insp(comp, lot, lotItem, "PROCESS", "OK"));
        assertThat(qty(lot, "QC")).isEqualByComparingTo("5");                         // 공정검사는 재고와 무관(WC-3)
        long avail = receive(lotItem, "P1", a2, "5", "AVAILABLE");
        judged(insp(comp, avail, lotItem, "RECEIVE", "NG"));
        assertThat(qty(avail, "AVAILABLE")).isEqualByComparingTo("5");               // QC 없음 — 가용을 끌어내리지 않음(D-B26)
        assertThat(accounts.query(comp, "select count(*) from inv_trx where src_type like 'QMS%'", Integer.class)).isZero();
    }

    @Test
    void NCR_처분_합이_부적합보다_크면_409_단위가_다르면_409_모두_그대로() throws Exception {
        long lot = receive(lotItem, "P1", a1, "10", "QC");
        judged(insp(comp, lot, lotItem, "RECEIVE", "NG"));
        assertThat(code(closed(ncr(comp, lot, lotItem, "EA", "USE_AS_IS:8", "SCRAP:4")))).isEqualTo(HbErrorCode.WMS_QUALITY_SHORT);
        assertThat(code(closed(ncr(comp, lot, lotItem, "BOX", "USE_AS_IS:1")))).isEqualTo(HbErrorCode.WMS_QUALITY_UNIT);
        assertThat(qty(lot, "BLOCKED")).isEqualByComparingTo("10");
        assertThat(accounts.query(comp, "select count(*) from inv_trx where src_type = 'QMS_NCR'", Integer.class)).isZero();
    }

    @Test
    void 판정_원장_행은_수불_원장_화면에서_되돌릴_수_없다() throws Exception {
        long lot = receive(lotItem, "P1", a1, "10", "QC");
        long id = insp(comp, lot, lotItem, "RECEIVE", "OK");
        judged(id);
        long trx = accounts.query(comp, "select min(trx_id) from inv_trx where src_type = 'QMS_INSP' and src_id = ?", Long.class, id);
        postJson("/api/wms/trx/" + trx + "/reverse", "{\"reason\":\"사람이 되돌림\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("QMS_SOURCE"));
        assertThat(qty(lot, "AVAILABLE")).isEqualByComparingTo("10");
    }

    @Test
    void A사로_B사_검사와_NCR은_없는_것() throws Exception {
        long lot = receive(lotItem, "P1", a1, "10", "QC");
        long id = insp(comp, lot, lotItem, "RECEIVE", "OK");
        long n = ncr(comp, lot, lotItem, "EA", "SCRAP:1");
        String other = accounts.newComp("QB");
        Throwable t = catchThrowable(() -> runner.callAs(other, "b1", () -> {
            link.onInspJudged(id);
            return null;
        }));
        assertThat(code(t)).isEqualTo(HbErrorCode.NOT_FOUND);
        Throwable t2 = catchThrowable(() -> runner.callAs(other, "b1", () -> {
            link.onNcrClosed(n);
            return null;
        }));
        assertThat(code(t2)).isEqualTo(HbErrorCode.NOT_FOUND);
        assertThat(runner.call(other, () -> stockQuery.lotStatusQty(lot))).isEmpty();
        accounts.addPlant(other, "P1", "B사 1공장");                                   // 같은 공장 코드·스위치 Y 여도 A사 QC LOT 은 안 보인다(RLS)
        policy(other, "P1", "Y");
        assertThat(runner.call(other, () -> stockQuery.qcLots("P1", null, 10)).lots()).isEmpty();
        assertThat(qty(lot, "QC")).isEqualByComparingTo("10");
    }

    // ------------------------------------------------------------------ 동시(상위 지시 2026-10-24 — 12.15.7)

    /**
     * 같은 LOT·같은 칸에 판정 반영과 사람의 이동(QC → A-02)을 칸 잠금 대기 뒤 동시에 놓는다 → 교착 없이 둘 다 끝난다.
     * 먼저 들어간 쪽에 따라 이동 200·판정 남은 QC 만 / 판정 먼저·이동 409 INSUFFICIENT_STOCK — 어느 쪽이든 원장 합 = 현재고, QC 음수 없음
     */
    @Test
    void C_판정_반영과_이동이_같은_칸에서_겹쳐도_교착_없음() throws Exception {
        long lot = receive(lotItem, "P1", a1, "10", "QC");
        long id = insp(comp, lot, lotItem, "RECEIVE", "OK");
        long stockId = accounts.query(comp, "select stock_id from inv_stock where lot_id = ? and stock_status = 'QC'", Long.class, lot);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        Future<?> holder = POOL.submit(() -> runner.call(comp, () -> {
            jdbc.queryForList("select 1 from inv_stock where stock_id = ? for update", stockId);
            held.countDown();
            try {
                go.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        int before = waiters();
        CompletableFuture<String> judge = CompletableFuture.supplyAsync(() -> {
            try {
                judged(id);
                return "OK";
            } catch (BusinessException e) {
                return e.code().name();
            }
        }, POOL);
        CompletableFuture<String> move = CompletableFuture.supplyAsync(() -> {
            try {
                return String.valueOf(postJson("/api/wms/move/move", "{\"plantCd\":\"P1\",\"itemId\":" + lotItem + ",\"fromNodeId\":" + a1
                        + ",\"fromStatus\":\"QC\",\"toNodeId\":" + a2 + ",\"toStatus\":\"QC\",\"qty\":6,\"lotNo\":\"" + lotNo(lot) + "\"}")
                        .andReturn().getResponse().getStatus());
            } catch (Exception e) {
                return "EX " + e;
            }
        }, POOL);
        for (int i = 0; i < 200 && waiters() - before < 2; i++) {
            Thread.sleep(50);
        }
        int waiting = waiters() - before;
        go.countDown();
        holder.get(10, TimeUnit.SECONDS);
        String j = judge.get(30, TimeUnit.SECONDS);
        String mv = move.get(30, TimeUnit.SECONDS);
        assertThat(waiting).as("둘 다 칸 잠금 대기 " + j + "/" + mv).isEqualTo(2);
        assertThat(j).isEqualTo("OK");
        assertThat(mv).isIn("200", "409");
        // 어느 순서든: 이동이 먼저면 A-01 QC 4 가 가용으로·A-02 QC 6 은 그대로(판정 순간의 QC 칸 — 같은 LOT 의 다른 칸도 포함),
        // 판정이 먼저면 이동 409 — 모두 원장 합 = 현재고, 음수 없음
        assertThat(qty(lot, "QC").add(qty(lot, "AVAILABLE"))).isEqualByComparingTo("10");
        assertThat(accounts.query(comp, "select count(*) from inv_stock where qty < 0", Integer.class)).isZero();
        assertThat(accounts.query(comp, """
                select count(*) from (select item_id, node_id, lot_id, stock_status, sum(qty) q from inv_trx group by 1, 2, 3, 4) t
                  full join inv_stock s on s.item_id = t.item_id and s.node_id = t.node_id and s.lot_id is not distinct from t.lot_id
                                       and s.stock_status = t.stock_status
                 where coalesce(t.q, 0) <> coalesce(s.qty, 0)""", Integer.class)).isZero();
        List<String> seen = new ArrayList<>(List.of(j, mv));
        System.out.println("[B3 동시] 판정·이동 = " + seen);
    }

    private int waiters() {
        return accounts.query(comp, "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()",
                Integer.class);
    }
}
