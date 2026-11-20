package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.qms.link.SupplierQualityQuery;
import io.smarthappy.module.qms.link.SupplierQualityQuery.ItemQuality;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA — QMS 협력사 품질 조회 계약(SupplierQualityQuery, main #341) 경계. 모듈 시험(SupplierQualityQueryTest)이 다루지 않는 것만:
 * 재검사·무효 때 NCR 불량이 어디로 가는지(QQ-1 정책 질의, 현행 고정) / 회사 시간대(LA) 날짜 경계 /
 * 잠금 없음(다른 트랜잭션이 검사·NCR·LOT 을 잡고 있어도 바로 읽음) / 반품 목록 상한 500 이 말없이 자름(QQ-3) /
 * 극단 날짜 인자에서 예외 없음·부르는 쪽 트랜잭션 계속(QQ-2 재현).
 */
@AutoConfigureMockMvc
class SupplierQualityQaTest extends PostgresTestBase {

    static final String PW = "Qa-ledger-hold-41";
    static final String I = "/api/qms/insps";
    static final String N = "/api/qms/ncrs";
    static final ExecutorService POOL = Executors.newCachedThreadPool();

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    SupplierQualityQuery query;

    @Autowired
    TenantRunner runner;

    @Autowired
    PlatformTransactionManager txm;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    RequestPostProcessor admin;
    LocalDate today;
    long defect;

    @AfterAll
    static void shutdown() {
        POOL.shutdownNow();
    }

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("QQ");
        long user = accounts.newUser(comp, "qa1", PW);
        accounts.grant(comp, user, "QA", "qms-insp-plan", "SEARCH", "SAVE", "APPROVE", "DELETE");
        accounts.grant(comp, user, "QA", "qms-insp", "SEARCH", "SAVE", "DELETE");
        accounts.grant(comp, user, "QA", "qms-ncr", "SEARCH", "SAVE", "DELETE");
        admin = accounts.as(comp, user, "qa1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY, LocalDate.class);
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'QQ-RM', 'QA 원자재', 'RM', 'BUY', 'EA', 't', 't')""", comp);
        accounts.exec(comp, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'IQC', '수입 검사', 'INSPECT', 't', 't')""", comp);
        long ch = accounts.query(comp, """
                insert into qms_char (comp_cd, char_type, proc_id, char_no, char_nm, data_type, scale, created_by, updated_by)
                select ?, 'PROCESS', proc_id, '1', '녹 없음', 'ATTRIBUTE', 0, 't', 't' from mst_proc where proc_cd = 'IQC'
                returning char_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into qms_sampling_plan (comp_cd, plan_cd, plan_nm, method, fixed_n, ac, re, created_by, updated_by)
                values (?, 'S5', '고정 5', 'FIXED', 5, 0, 1, 't', 't')""", comp);
        defect = accounts.query(comp, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'QQ-D', '녹', 'APPEARANCE', 't', 't') returning defect_id""", Long.class, comp);
        long plan = id(postJson("/api/qms/insp-plans", "{\"inspType\":\"RECEIVE\",\"itemCd\":\"QQ-RM\",\"classCd\":null,\"procCd\":\"IQC\"}"));
        int v = accounts.query(comp, "select row_ver from qms_insp_plan where insp_plan_id = ?", Integer.class, plan);
        mvc.perform(json(put("/api/qms/insp-plans/" + plan + "/lines?rowVer=" + v), "{\"lines\":[{\"rowKey\":\"a\",\"lineId\":null,\"lineNo\":10,"
                + "\"charId\":" + ch + ",\"samplingPlanCd\":\"S5\",\"gaugeClass\":\"VISUAL\",\"freqType\":null,\"freqN\":null,"
                + "\"methodNm\":\"육안\",\"reactionPlan\":\"격리\"}]}")).andExpect(status().isOk());
        v = accounts.query(comp, "select row_ver from qms_insp_plan where insp_plan_id = ?", Integer.class, plan);
        postJson("/api/qms/insp-plans/" + plan + "/release", "{\"rowVer\":" + v + "}").andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ 도우미

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body));
    }

    static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    long cust(String cd) {
        return accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, ?, ?, 'PURCHASE', 't', 't') returning cust_id""", Long.class, comp, cd, cd + " 협력사");
    }

    long lot(String no, long supplier, String qty, String unit) {
        return accounts.query(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, supplier_cust_id, supplier_lot_no, occurred_at,
                                        created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', ?::numeric, ?, ?, 'S' || ?, now(), 't', 't' from mst_item where item_cd = 'QQ-RM'
                returning lot_id""", Long.class, comp, no, qty, unit, supplier, no);
    }

    /** 수입검사 하나 만들어 5개 측정 후 판정 확정 → [검사 id, 검사 번호] */
    Object[] judged(String lotNo, boolean ok) throws Exception {
        long id = id(postJson(I, "{\"lotNo\":\"" + lotNo + "\",\"inspType\":\"RECEIVE\",\"procCd\":\"IQC\"}"));
        long line = accounts.query(comp, "select insp_line_id from qms_insp_line where insp_id = ?", Long.class, id);
        StringBuilder a = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            a.append(i == 1 ? "" : ",").append("{\"sampleNo\":").append(i).append(",\"okYn\":\"").append(!ok && i == 1 ? "N" : "Y").append("\"}");
        }
        postJson(I + "/" + id + "/meas", "{\"inspLineId\":" + line + ",\"entries\":[" + a + "]}").andExpect(status().isOk());
        postJson(I + "/" + id + "/judge", "{\"rowVer\":" + inspVer(id) + "}").andExpect(status().isOk());
        return new Object[] {id, accounts.query(comp, "select insp_no from qms_insp where insp_id = ?", String.class, id)};
    }

    int inspVer(long id) {
        return accounts.query(comp, "select row_ver from qms_insp where insp_id = ?", Integer.class, id);
    }

    int ncrVer(long id) {
        return accounts.query(comp, "select row_ver from qms_ncr where ncr_id = ?", Integer.class, id);
    }

    long ncr(String lotNo, String inspNo, String qty) throws Exception {
        return id(postJson(N, "{\"lotNo\":\"" + lotNo + "\",\"source\":\"" + (inspNo == null ? "CLAIM" : "INSP") + "\",\"inspNo\":"
                + (inspNo == null ? "null" : "\"" + inspNo + "\"") + ",\"defectId\":" + defect + ",\"ncQty\":" + qty + ",\"description\":\"녹\"}"));
    }

    void returnAndClose(long ncr, int lines) throws Exception {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            b.append(i == 0 ? "" : ",").append("{\"rowKey\":\"k").append(i).append("\",\"dispType\":\"RETURN\",\"qty\":1,\"note\":\"반품\"}");
        }
        mvc.perform(json(put(N + "/" + ncr), "{\"rowVer\":" + ncrVer(ncr) + ",\"defectId\":" + defect + ",\"ncQty\":" + lines
                + ",\"description\":\"녹\",\"disps\":[" + b + "]}")).andExpect(status().isOk());
        postJson(N + "/" + ncr + "/close", "{\"rowVer\":" + ncrVer(ncr) + "}").andExpect(status().isOk());
    }

    <T> T tx(Supplier<T> s) {
        TransactionTemplate t = new TransactionTemplate(txm);
        return runner.call(comp, () -> t.execute(st -> s.get()));
    }

    static String row(ItemQuality q) {
        return q.itemCd() + " " + q.unitCd() + " " + n(q.inspectedQty()) + "/" + n(q.rejectedQty()) + " " + q.inspectionCnt() + "/" + q.ngCnt()
                + " open" + q.openNcrCnt() + " other" + n(q.otherNcQty());
    }

    static String n(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    /** 고정 칸 트리거를 건너뛰어 시각만 옮긴다(소유자·replica) */
    static void ownerExec(String sql, Object... args) throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword())) {
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("set session_replication_role = replica");
            }
            try (java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
        }
    }

    // ------------------------------------------------------------------ 시험

    /**
     * QQ-1(사용자 결정 = NCR 기준 집계 + 무효 경고, docs/29_P3 16.1): 수입검사 NG 근거로 NCR 을 올린 뒤 ① 같은 LOT 을 무효 없이 다시 검사해
     * OK 로 확정하거나 ② NCR 을 그대로 둔 채 검사를 무효로 해도, 그 LOT 의 출처 INSP NCR 불량·처리 중 수는 계약 값에 남는다.
     * 서버는 ①②를 막지 않는다(무효는 화면 확인 + 무효 사유 끝에 처리 중 NCR 번호).
     */
    @Test
    void 재검사_무효_뒤_남은_NCR_불량도_계약_값에_남는다() throws Exception {
        long s1 = cust("QQ-S1");
        long s2 = cust("QQ-S2");
        lot("QQ-L1", s1, "100", "EA");
        lot("QQ-L2", s2, "50", "EA");
        // ① L1: NG → NCR 9(OPEN) → 무효 없이 두 번째 수입검사 OK
        String ng1 = (String) judged("QQ-L1", false)[1];
        long n1 = ncr("QQ-L1", ng1, "9");
        assertThat(tx(() -> query.receiptQuality(s1, today, today))).extracting(SupplierQualityQaTest::row)
                .containsExactly("QQ-RM EA 100/9 1/1 open1 other0");
        judged("QQ-L1", true);
        assertThat(tx(() -> query.receiptQuality(s1, today, today))).extracting(SupplierQualityQaTest::row)
                .as("재검사 OK 뒤: 판정 1·NG 0, NCR 9 불량·처리 중은 LOT 에 남는다")
                .containsExactly("QQ-RM EA 100/9 1/0 open1 other0");
        // ② L2: NG → NCR 6(OPEN) → NCR 그대로 두고 검사 무효(200)
        Object[] ng2 = judged("QQ-L2", false);
        long n2 = ncr("QQ-L2", (String) ng2[1], "6");
        postJson(I + "/" + ng2[0] + "/void", "{\"rowVer\":" + inspVer((Long) ng2[0]) + ",\"reason\":\"시료 바뀜\"}").andExpect(status().isOk());
        assertThat(tx(() -> query.receiptQuality(s2, today, today))).extracting(SupplierQualityQaTest::row)
                .as("무효 뒤: 판정은 없고 NCR 6 은 근거 검사 판정 시각으로, LOT 50 도 분모에(QE-1)").containsExactly("QQ-RM EA 50/6 0/0 open1 other0");
        assertThat(accounts.query(comp, "select void_reason from qms_insp where insp_id = ?", String.class, ng2[0])).startsWith("시료 바뀜 [OPEN NCR ");
        // NCR 둘은 그대로 살아 있고 LOT 도 보류
        assertThat(tx(() -> query.ncr(n1)).orElseThrow().status()).isEqualTo("OPEN");
        assertThat(tx(() -> query.ncr(n2)).orElseThrow().status()).isEqualTo("OPEN");
        assertThat(accounts.query(comp, "select count(*) from lot_master where lot_no in ('QQ-L1', 'QQ-L2') and hold_yn = 'Y'", Long.class))
                .isEqualTo(2L);
    }

    /** 기간 = 회사 시간대 날짜 — LA 회사에서 LA 자정 앞(23:59)은 그날, 자정은 다음 날 */
    @Test
    void 회사_시간대_LA_날짜_경계() throws Exception {
        long s = cust("QQ-LA");
        lot("QQ-A", s, "10", "EA");
        lot("QQ-B", s, "20", "EA");
        long a = (Long) judged("QQ-A", true)[0];
        long b = (Long) judged("QQ-B", false)[0];
        // 시간대는 판정 뒤에 바꾼다(검사계획 유효일이 서울 오늘이라 LA 어제로는 검사를 못 만든다)
        accounts.exec(comp, "update sys_comp set time_zone = 'America/Los_Angeles' where comp_cd = ?", comp);
        LocalDate d = LocalDate.of(2026, 3, 10);
        // A = LA 3/10 23:59 (서울로는 3/11 16:59), B = LA 3/11 00:00
        ownerExec("update qms_insp set judged_at = timestamp '2026-03-10 23:59' at time zone 'America/Los_Angeles' where insp_id = ?", a);
        ownerExec("update qms_insp set judged_at = timestamp '2026-03-11 00:00' at time zone 'America/Los_Angeles' where insp_id = ?", b);
        assertThat(tx(() -> query.receiptQuality(s, d, d))).extracting(SupplierQualityQaTest::row).containsExactly("QQ-RM EA 10/0 1/0 open0 other0");
        assertThat(tx(() -> query.receiptQuality(s, d.plusDays(1), d.plusDays(1)))).extracting(SupplierQualityQaTest::row)
                .containsExactly("QQ-RM EA 20/0 1/1 open0 other0");
    }

    /** 읽기만·잠금 없음 — 다른 트랜잭션이 검사·NCR·LOT 행을 FOR UPDATE 로 잡고 있어도(모두 잡은 것 확인 후) 바로 읽는다 */
    @Test
    void 다른_트랜잭션이_잡고_있어도_기다리지_않는다() throws Exception {
        long s = cust("QQ-K");
        lot("QQ-K1", s, "10", "EA");
        String ng = (String) judged("QQ-K1", false)[1];
        long n = ncr("QQ-K1", ng, "2");
        returnAndClose(n, 2);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = POOL.submit(() -> tx(() -> {
            jdbc.queryForList("select 1 from qms_insp where comp_cd = ? for update", comp);
            jdbc.queryForList("select 1 from qms_ncr where comp_cd = ? for update", comp);
            jdbc.queryForList("select 1 from qms_ncr_disp where comp_cd = ? for update", comp);
            jdbc.queryForList("select 1 from lot_master where comp_cd = ? for update", comp);
            held.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            Future<String> read = POOL.submit(() -> tx(() -> {
                jdbc.execute("set local lock_timeout = '2s'");
                return row(query.receiptQuality(s, today, today).get(0)) + " | " + query.returnsToSupplier(s, today, today).rows().size()
                        + " | " + query.ncr(n).orElseThrow().status();
            }));
            assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo("QQ-RM EA 10/2 1/1 open0 other0 | 2 | CLOSED");
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * QQ-3(docs/29_P3 16.1): 반품 줄이 상한(500)을 넘으면 앞 500 줄 + truncated — 부르는 쪽이 잘린 것을 안다.
     * NCR 26건 × RETURN 줄 = 25×20 + 1 = 501 줄.
     */
    @Test
    void 반품_목록_501줄은_500줄과_잘림_표시() throws Exception {
        long s = cust("QQ-R");
        lot("QQ-R1", s, "1000", "EA");
        for (int i = 0; i < 26; i++) {
            long n = ncr("QQ-R1", null, "1");
            returnAndClose(n, i < 25 ? 20 : 1);
        }
        SupplierQualityQuery.ReturnPage page = tx(() -> query.returnsToSupplier(s, today, today));
        assertThat(page.truncated()).isTrue();
        List<SupplierQualityQuery.SupplierReturn> r = page.rows();
        assertThat(r).hasSize(SupplierQualityQuery.MAX_ROWS);
        assertThat(r.stream().map(SupplierQualityQuery.SupplierReturn::qty).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("500");
        assertThat(accounts.query(comp, "select sum(qty) from qms_ncr_disp where disp_type = 'RETURN'", BigDecimal.class)).isEqualByComparingTo("501");
    }

    /** 잘못된 인자 중 날짜 범위 끝(LocalDate.MAX·DB timestamptz 범위 밖)도 빈 값이어야 하고, 같은 트랜잭션에서 다음 조회가 이어져야 한다 */
    @Test
    void 결함QQ2_극단_날짜도_예외없이_빈값_트랜잭션_계속() throws Exception {
        long s = cust("QQ-X");
        lot("QQ-X1", s, "10", "EA");
        judged("QQ-X1", false);
        String after = tx(() -> {
            assertThat(query.returnsToSupplier(s, LocalDate.of(300_000, 1, 1), LocalDate.of(300_000, 1, 2)).rows()).isEmpty();
            assertThat(query.receiptQuality(s, LocalDate.MAX, LocalDate.MAX)).isEmpty();
            assertThat(query.receiptQuality(s, LocalDate.of(-5000, 1, 1), LocalDate.of(-5000, 1, 2))).isEmpty();
            return row(query.receiptQuality(s, today, today).get(0));   // 같은 트랜잭션이 계속 쓸 수 있어야 한다
        });
        assertThat(after).isEqualTo("QQ-RM EA 10/0 1/1 open0 other0");
    }
}
