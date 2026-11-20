package io.smarthappy.module.wms.inv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.cms.link.SpareIssueLink;
import io.smarthappy.module.cms.link.SpareIssueLink.PartIssue;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * CMS 예비품 출고·반납 → 재고 원장 (docs/33 11.15 계약 {@link SpareIssueLink}, docs/30 12.8) — CMS 완료 전이 흉내(계약 직접 호출, 부르는 쪽 트랜잭션).
 * 정상(칸 순서 배분·LOT 선입·원장 밖 품목 건너뜀·멱등), 스위치 N, 부족·마감 409(전체 롤백), 수입검사·폐기·보류 칸 제외,
 * 반납(나중 칸부터·누적 초과 거부·같은 요청·출고 없음), 동시 반납, A사로 B사, NONE.
 */
@AutoConfigureMockMvc
class MroIssuePostingTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    ObjectProvider<SpareIssueLink> provider;

    String comp;
    RequestPostProcessor admin;
    long a1;
    long a2;
    long qc;
    long scrapLoc;
    long sp;
    long spLot;
    long spX;
    long seq = 1000;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("MR");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "WMS", "wms-rcv", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-trx", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long wh = node(null, "WH", "SP-WH");
        a1 = node(wh, "LOC", "A-01");
        a2 = node(wh, "LOC", "A-02");
        qc = node(wh, "LOC", "QC-01");
        long sw = node(null, "WH", "SCRAP");
        scrapLoc = node(sw, "LOC", "S-01");
        long stg = node(wh, "LOC", "STG-01");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'QC', 't', 't')", qc, comp);
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't')", stg, comp);
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'SCRAP', 't', 't')", sw, comp);
        sp = item("SP-1", "N");
        spLot = item("SP-L", "Y");
        spX = item("SP-X", "N");
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'SUP1', 'SUP1', 'PURCHASE', 't', 't')", comp);
        adjIn(sp, a1, "5");
        adjIn(sp, a2, "5");
        adjIn(sp, qc, "9");                                                  // 수입검사 위치 — 출고 안 함
        adjIn(sp, stg, "9");                                                 // 출하 대기 — 출고 안 함(가용 정의 AvailSql, docs/53)
        adjIn(sp, scrapLoc, "9");                                            // 폐기 — 출고 안 함
        policy("Y");
    }

    long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd);
    }

    long item(String cd, String lotYn) {
        return accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, ?, ?, 'CON', 'BUY', 'EA', ?, 't', 't') returning item_id""", Long.class, comp, cd, cd, lotYn);
    }

    void adjIn(long item, long node, String qty) {
        accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', ?::numeric, 'EA', 'MANUAL', now() - interval '1 minute', 'KEY', 't')""",
                comp, item, node, qty);
    }

    void policy(String yn) {
        accounts.exec(comp, "delete from sys_policy where policy_key = 'WMS_MRO_LINK'");
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) values (?, 'P1', 'WMS_MRO_LINK', ?, 't', 't')",
                comp, yn);
    }

    /** CMS 완료 전이 흉내 — 부르는 쪽 트랜잭션 */
    void done(String c, PartIssue... parts) {
        runner.callAs(c, "cms1", () -> {
            SpareIssueLink.of(provider).onMwoDone(1L, "P1", Instant.now().truncatedTo(ChronoUnit.SECONDS), List.of(parts), "cms1");
            return null;
        });
    }

    boolean ret(String c, long partId, String qty, String req) {
        return runner.callAs(c, "cms1", () -> SpareIssueLink.of(provider).onPartReturn(partId, new BigDecimal(qty), "덜 씀", req, "cms1"));
    }

    PartIssue part(long item, String qty) {
        return new PartIssue(++seq, item, new BigDecimal(qty));
    }

    BigDecimal stock(long node, long item) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and item_id = ?", BigDecimal.class, node, item)
                .stripTrailingZeros();
    }

    int rows(String type) {
        return accounts.query(comp, "select count(*) from inv_trx where trx_type = ?", Integer.class, type);
    }

    static HbErrorCode code(Throwable t) {
        assertThat(t).isInstanceOf(BusinessException.class);
        return ((BusinessException) t).code();
    }

    // =====================================================================
    // 출고
    // =====================================================================

    @Test
    void 완료하면_칸_순서로_출고하고_원장_밖_품목은_건너뛰며_같은_줄을_다시_보내도_한_번() {
        PartIssue p = part(sp, "7");
        PartIssue x = part(spX, "3");                                        // 원장에 없던 소모품
        done(comp, p, x);
        assertThat(stock(a1, sp)).isEqualByComparingTo("0");
        assertThat(stock(a2, sp)).isEqualByComparingTo("3");
        assertThat(stock(qc, sp)).isEqualByComparingTo("9");
        assertThat(stock(scrapLoc, sp)).isEqualByComparingTo("9");
        assertThat(accounts.query(comp, "select count(*) || ':' || min(src_type) || ':' || min(src_id) || ':' || min(client_req_id) || ':' || min(recorded_by) "
                + "|| ':' || min(entry_method) from inv_trx where trx_type = 'ISSUE_MRO'", String.class))
                .isEqualTo("2:CMS_PART:" + p.partId() + ":CMS_PART:" + p.partId() + ":cms1:AUTO");
        done(comp, p, x);                                                    // 완료 재시도
        assertThat(rows("ISSUE_MRO")).isEqualTo(2);
    }

    @Test
    void 스위치_N_이면_기록만_빈_목록은_아무것도_안_함() {
        done(comp);                                                          // CMS 는 사용 예비품이 없어도 분기 없이 넘긴다
        assertThat(rows("ISSUE_MRO")).isZero();
        policy("N");
        done(comp, part(sp, "3"));
        assertThat(rows("ISSUE_MRO")).isZero();
    }

    @Test
    void LOT_관리_예비품은_먼저_들어온_LOT_부터() throws Exception {
        String l1 = lotIn("4");
        String l2 = lotIn("4");
        done(comp, part(spLot, "6"));
        assertThat(accounts.query(comp, "select string_agg(l.lot_no || '=' || trim_scale(-t.qty), ',' order by l.lot_id) from inv_trx t "
                + "join lot_master l on l.lot_id = t.lot_id where t.trx_type = 'ISSUE_MRO'", String.class)).isEqualTo(l1 + "=4," + l2 + "=2");
    }

    String lotIn(String qty) throws Exception {
        MvcResult m = mvc.perform(post("/api/wms/rcv").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON).content(
                "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + spLot + ",\"qty\":" + qty + ",\"stockStatus\":\"AVAILABLE\",\"supplierCd\":\"SUP1\","
                        + "\"clientReqId\":\"mro-lot-" + (++seq) + "\"}")).andExpect(status().isOk()).andReturn();
        return JsonPath.read(m.getResponse().getContentAsString(), "$.data.lotNo");
    }

    @Test
    void 부족하면_409이고_앞_줄까지_전부_롤백_보류_LOT_은_출고하지_않는다() throws Exception {
        Throwable t = catchThrowable(() -> done(comp, part(sp, "3"), part(sp, "8")));    // 가용 10(QC·출하 대기·폐기 제외)
        assertThat(code(t)).isEqualTo(HbErrorCode.INSUFFICIENT_STOCK);
        assertThat(rows("ISSUE_MRO")).isZero();
        assertThat(stock(a1, sp)).isEqualByComparingTo("5");
        // 보류 LOT 은 후보가 아니다 → 부족
        String l1 = lotIn("4");
        accounts.exec(comp, "insert into lot_hold (comp_cd, lot_id, source, reason, held_by) select comp_cd, lot_id, 'MANUAL', '품질', 't' from lot_master where lot_no = ?", l1);
        assertThat(code(catchThrowable(() -> done(comp, part(spLot, "1"))))).isEqualTo(HbErrorCode.INSUFFICIENT_STOCK);
    }

    @Test
    void 마감된_달_시각_완료는_409_PERIOD_CLOSED() {
        String lastMonth = accounts.query(comp, "select to_char(date_trunc('month', now() at time zone 'Asia/Seoul') - interval '1 day', 'YYYYMM')",
                String.class);
        accounts.exec(comp, "insert into sys_period_close (comp_cd, plant_cd, module_cd, period, status, closed_at, closed_by, created_by, updated_by) "
                + "values (?, 'P1', 'INV', ?, 'CLOSED', now(), 't', 't', 't')", comp, lastMonth);
        Instant inClosed = accounts.query(comp, "select (date_trunc('month', now() at time zone 'Asia/Seoul') - interval '1 day') at time zone 'Asia/Seoul'",
                java.sql.Timestamp.class).toInstant();
        Throwable t = catchThrowable(() -> runner.callAs(comp, "cms1", () -> {
            SpareIssueLink.of(provider).onMwoDone(1L, "P1", inClosed, List.of(part(sp, "1")), "cms1");
            return null;
        }));
        assertThat(code(t)).isEqualTo(HbErrorCode.PERIOD_CLOSED);
    }

    // =====================================================================
    // 반납
    // =====================================================================

    @Test
    void 반납은_나중에_출고한_칸부터_누적은_출고를_넘지_못하고_같은_요청은_한_번() {
        PartIssue p = part(sp, "7");                                         // A-01 5, A-02 2
        done(comp, p);
        assertThat(ret(comp, p.partId(), "3", "rtn-req-0001")).isTrue();
        assertThat(stock(a2, sp)).isEqualByComparingTo("5");                  // A-02 +2
        assertThat(stock(a1, sp)).isEqualByComparingTo("1");                  // A-01 +1
        assertThat(ret(comp, p.partId(), "3", "rtn-req-0001")).isTrue();     // 두 번 누름 — 그대로
        assertThat(rows("MRO_RTN")).isEqualTo(2);
        Throwable t = catchThrowable(() -> ret(comp, p.partId(), "5", "rtn-req-0002"));       // 남은 4
        assertThat(code(t)).isEqualTo(HbErrorCode.WMS_MRO_RETURN_OVER);
        assertThat(((BusinessException) t).args()).containsExactly("4");
        assertThat(ret(comp, p.partId(), "4", "rtn-req-0003")).isTrue();
        assertThat(stock(a1, sp)).isEqualByComparingTo("5");
        // 출고 원장 없는 줄 — 할 일 없음
        assertThat(ret(comp, 999999L, "1", "rtn-req-0004")).isFalse();
        // 수량·사유·요청 id
        assertThat(code(catchThrowable(() -> ret(comp, p.partId(), "0", "rtn-req-0005")))).isEqualTo(HbErrorCode.INVALID_VALUE);
        assertThat(code(catchThrowable(() -> runner.callAs(comp, "cms1",
                () -> SpareIssueLink.of(provider).onPartReturn(p.partId(), BigDecimal.ONE, "x", "rtn-req-0006", "cms1"))))).isEqualTo(HbErrorCode.INVALID_VALUE);
        assertThat(code(catchThrowable(() -> ret(comp, p.partId(), "1", "short")))).isEqualTo(HbErrorCode.INVALID_VALUE);
    }

    /** W-10(2026-11-02 사용자 결정): 예비품 출고 ISSUE_MRO 는 원장 화면 역분개 400 CMS_ISSUE_SOURCE(바로잡기 = CMS 반납), 반납 MRO_RTN 은 지금처럼 된다 */
    @Test
    void 출고_원장은_원장_화면에서_되돌리지_못하고_반납과_반납_역분개는_된다() throws Exception {
        PartIssue p = part(sp, "3");
        done(comp, p);
        long issue = accounts.query(comp, "select min(trx_id) from inv_trx where trx_type = 'ISSUE_MRO'", Long.class);
        mvc.perform(post("/api/wms/trx/" + issue + "/reverse").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"사람이 되돌림\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("CMS_ISSUE_SOURCE"));
        assertThat(ret(comp, p.partId(), "1", "rtn-req-w10a")).isTrue();
        long rtn = accounts.query(comp, "select max(trx_id) from inv_trx where trx_type = 'MRO_RTN'", Long.class);
        mvc.perform(post("/api/wms/trx/" + rtn + "/reverse").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"반납 잘못 넣음\"}")).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from inv_trx where reversal_of_id is not null", Integer.class)).isEqualTo(1);
    }

    @Test
    void C_같은_줄을_동시에_반납해도_누적은_출고를_넘지_않는다() throws Exception {
        PartIssue p = part(sp, "4");
        done(comp, p);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> fs = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                String r = "rtn-conc-" + i + "-" + System.nanoTime();
                fs.add(pool.submit(() -> {
                    go.await();
                    try {
                        return String.valueOf(ret(comp, p.partId(), "3", r));
                    } catch (BusinessException e) {
                        return e.code().name();
                    }
                }));
            }
            go.countDown();
            List<String> out = new ArrayList<>();
            for (Future<String> f : fs) {
                out.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(out).containsExactlyInAnyOrder("true", "WMS_MRO_RETURN_OVER");
        } finally {
            pool.shutdownNow();
        }
        assertThat(accounts.query(comp, "select trim_scale(sum(qty)) from inv_trx where trx_type = 'MRO_RTN'", BigDecimal.class))
                .isEqualByComparingTo("3");
    }

    // =====================================================================
    // 반납 누계 읽기 (C18, docs/33 11.15.2)
    // =====================================================================

    Map<Long, BigDecimal> returned(String c, Long... ids) {
        return runner.callAs(c, "cms1", () -> SpareIssueLink.of(provider).returnedQty(Arrays.asList(ids)));
    }

    @Test
    void 반납_누계는_출고한_줄만_키_반납_없으면_0_원장_밖_줄과_없는_id_는_키_없음() {
        PartIssue p1 = part(sp, "7");
        PartIssue p2 = part(sp, "1");
        PartIssue px = part(spX, "2");                                        // 원장 밖 품목 — 출고 건너뜀
        done(comp, p1, p2, px);
        ret(comp, p1.partId(), "3", "rtn-sum-0001");
        ret(comp, p1.partId(), "1", "rtn-sum-0002");
        int before = rows("MRO_RTN");
        Map<Long, BigDecimal> m = returned(comp, p1.partId(), p2.partId(), px.partId(), 999999L, null, p1.partId());
        assertThat(m).containsOnlyKeys(p1.partId(), p2.partId());
        assertThat(m.get(p1.partId())).isEqualByComparingTo("4");
        assertThat(m.get(p2.partId())).isEqualByComparingTo("0");
        // 빈 입력·null 만 = 빈 맵
        assertThat(returned(comp)).isEmpty();
        assertThat(returned(comp, (Long) null)).isEmpty();
        // 읽기만 — 원장 그대로
        assertThat(rows("MRO_RTN")).isEqualTo(before);
    }

    @Test
    void 반납_누계_스위치_꺼짐_완료_전은_키_없음_A사로_B사도_키_없음_NONE_은_빈_맵() {
        policy("N");
        PartIssue off = part(sp, "1");
        done(comp, off);                                                      // 스위치 꺼짐 — 출고 원장 없음
        PartIssue notYet = part(sp, "1");                                     // 완료 전(출고 없음)
        assertThat(returned(comp, off.partId(), notYet.partId())).isEmpty();
        policy("Y");
        PartIssue p = part(sp, "2");
        done(comp, p);
        ret(comp, p.partId(), "1", "rtn-sum-0101");
        assertThat(returned(comp, p.partId())).containsEntry(p.partId(), new BigDecimal("1"));
        String other = accounts.newComp("MT");
        assertThat(returned(other, p.partId())).isEmpty();                     // B사 범위에서 A사 줄 id — 모름(RLS)
        assertThat(SpareIssueLink.NONE.returnedQty(List.of(p.partId()))).isEmpty();
    }

    // =====================================================================
    // A사로 B사 · NONE
    // =====================================================================

    @Test
    void A사로_B사_줄은_반납할_수_없고_B사_범위에서_A사_품목은_원장_밖() {
        PartIssue p = part(sp, "2");
        done(comp, p);
        String other = accounts.newComp("MS");
        accounts.addPlant(other, "P1", "B사");
        accounts.exec(other, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) values (?, 'P1', 'WMS_MRO_LINK', 'Y', 't', 't')",
                other);
        assertThat(ret(other, p.partId(), "1", "rtn-b-0001")).isFalse();
        done(other, part(sp, "1"));                                           // B사 범위에서 A사 품목 id — 원장 밖(RLS)
        assertThat(accounts.query(other, "select count(*) from inv_trx", Integer.class)).isZero();
        assertThat(stock(a1, sp)).isEqualByComparingTo("3");
    }

    @Test
    void NONE_은_아무것도_안_함() {
        SpareIssueLink.NONE.onMwoDone(1L, "P1", Instant.now(), List.of(part(sp, "1")), "x");
        assertThat(SpareIssueLink.NONE.onPartReturn(1L, BigDecimal.ONE, "덜 씀", "rtn-none-01", "x")).isFalse();
    }
}
