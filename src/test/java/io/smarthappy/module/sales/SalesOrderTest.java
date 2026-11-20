package io.smarthappy.module.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 수주 (P2 2단계, docs/25 4.3) — 채번·행 번호, 수량 자릿수(반올림 금지)·글자 응답, 끝난 행 불변(서비스·트리거), 머리 상태 계산,
 * 같은 발주 두 번(동시 포함), 마지막 두 행 동시 마감, 엑셀 올리기(새 발주·변경·끝난 행·품목 고정), A사로 B사, 권한.
 */
@AutoConfigureMockMvc
class SalesOrderTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String O = "/api/sales/orders";
    private static final String IMP = "/api/platform/import";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long userId;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("SO");
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "SALES", "sal-order", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        cust(comp, "C-001", "SALES");
        cust(comp, "C-002", "BOTH");
        cust(comp, "V-001", "PURCHASE");
        item(comp, "FG-100", "EA");
        item(comp, "RM-KG", "KG");
    }

    // ---------- 도우미 ----------

    private void cust(String c, String cd, String type) {
        accounts.exec(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, ?, ?, ?, 't', 't')""", c, cd, cd + " 상사", type);
    }

    private void item(String c, String cd, String unit) {
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, ?, ?, 'FG', 'MAKE', ?, 't', 't')""", c, cd, cd + " 품목", unit);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(admin));
    }

    private ResultActions putJson(String url, String body) throws Exception {
        return mvc.perform(json(put(url), body).with(admin));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private long order(String po, String... lines) throws Exception {
        return id(postJson(O, "{\"custCd\":\"C-001\",\"custPoNo\":" + (po == null ? "null" : "\"" + po + "\"")
                + ",\"lines\":[" + String.join(",", lines) + "]}"));
    }

    private static String line(String item, String qty, String due) {
        return "{\"itemCd\":\"" + item + "\",\"qty\":\"" + qty + "\",\"dueDate\":\"" + due + "\"}";
    }

    private long lineId(long orderId, int no) {
        return accounts.query(comp, "select line_id from sal_order_line where order_id = ? and line_no = ?", Long.class, orderId, no);
    }

    private int lineVer(long lineId) {
        return accounts.query(comp, "select row_ver from sal_order_line where line_id = ?", Integer.class, lineId);
    }

    private String orderStatus(long orderId) {
        return accounts.query(comp, "select status from sal_order where order_id = ?", String.class, orderId);
    }

    // ---------- 정상 흐름 ----------

    @Test
    void 새_수주는_채번되고_행을_추가_수정_마감_취소하면_머리_상태가_따라간다() throws Exception {
        long o = order("PO-1", line("FG-100", "1200", "2026-11-30"), line("rm-kg", "12.345", "2026-12-15"));
        String no = accounts.query(comp, "select order_no from sal_order where order_id = ?", String.class, o);
        assertThat(no).matches("^SO\\d{4}-0001$");
        assertThat(order("PO-2", line("FG-100", "1", "2026-11-30"))).isNotEqualTo(o);
        assertThat(accounts.query(comp, "select order_no from sal_order where cust_po_no = 'PO-2'", String.class)).endsWith("-0002");

        mvc.perform(get(O + "/" + o).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.status").value("OPEN"))
                .andExpect(jsonPath("$.data.order.lineCnt").value(2))
                .andExpect(jsonPath("$.data.lines[0].lineNo").value(10))
                .andExpect(jsonPath("$.data.lines[1].lineNo").value(20))
                .andExpect(jsonPath("$.data.lines[1].itemCd").value("RM-KG"))
                .andExpect(jsonPath("$.data.lines[1].qty").value("12.345"));          // 글자(Double 아님)
        // 행 추가: 번호를 비우면 다음 10 단위
        long l3 = id(postJson(O + "/" + o + "/lines", line("FG-100", "5", "2027-01-10")));
        assertThat(accounts.query(comp, "select line_no from sal_order_line where line_id = ?", Integer.class, l3)).isEqualTo(30);
        // 수량·납기 변경
        long l1 = lineId(o, 10);
        putJson(O + "/lines/" + l1, "{\"qty\":\"1500\",\"dueDate\":\"2026-12-01\",\"rowVer\":0}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select qty from sal_order_line where line_id = ?", BigDecimal.class, l1))
                .isEqualByComparingTo("1500");
        // 마감 1 · 취소 2 → 머리 CLOSED
        postJson(O + "/lines/" + l1 + "/close", "{\"rowVer\":1}").andExpect(status().isOk());
        assertThat(orderStatus(o)).isEqualTo("OPEN");
        postJson(O + "/lines/" + lineId(o, 20) + "/cancel", "{\"reason\":\"고객 취소\",\"rowVer\":0}").andExpect(status().isOk());
        postJson(O + "/lines/" + l3 + "/cancel", "{\"reason\":\"중복\",\"rowVer\":0}").andExpect(status().isOk());
        assertThat(orderStatus(o)).isEqualTo("CLOSED");
        // 끝난 수주에는 행을 붙일 수 없다
        postJson(O + "/" + o + "/lines", line("FG-100", "1", "2027-01-10"))
                .andExpect(jsonPath("$.errors[0].code").value("CLOSED"));
        // 변경 이력
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'sal_order_line' and action = 'UPDATE'",
                Integer.class)).isGreaterThanOrEqualTo(4);
    }

    @Test
    void 모든_행을_취소하면_CANCELLED_수주_취소는_열린_행만_사유_필수() throws Exception {
        long o = order("PO-1", line("FG-100", "10", "2026-11-30"), line("FG-100", "20", "2026-12-30"));
        postJson(O + "/lines/" + lineId(o, 10) + "/close", "{\"rowVer\":0}").andExpect(status().isOk());
        postJson(O + "/" + o + "/cancel", "{\"reason\":\" \",\"rowVer\":0}").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        postJson(O + "/" + o + "/cancel", "{\"reason\":\"고객 사정\",\"rowVer\":0}").andExpect(status().isOk());
        assertThat(orderStatus(o)).isEqualTo("CLOSED");          // 마감 행이 있어 CLOSED
        assertThat(accounts.query(comp, "select status from sal_order_line where line_id = ?", String.class, lineId(o, 10)))
                .isEqualTo("DONE");
        long o2 = order("PO-2", line("FG-100", "10", "2026-11-30"));
        postJson(O + "/" + o2 + "/cancel", "{\"reason\":\"취소\",\"rowVer\":0}").andExpect(status().isOk());
        assertThat(orderStatus(o2)).isEqualTo("CANCELLED");
        // 이미 끝난 수주를 다시 취소·머리 수정
        postJson(O + "/" + o2 + "/cancel", "{\"reason\":\"또\",\"rowVer\":1}").andExpect(jsonPath("$.errors[0].code").value("CLOSED"));
        putJson(O + "/" + o2, "{\"custPoNo\":\"PO-9\",\"rowVer\":1}").andExpect(jsonPath("$.errors[0].code").value("CLOSED"));
    }

    // ---------- 입력 검증 (B) ----------

    @Test
    void 수량은_단위_자릿수까지_반올림하지_않고_지수_음수_쉼표는_거부() throws Exception {
        postJson(O, "{\"custCd\":\"C-001\",\"lines\":[" + String.join(",",
                line("FG-100", "1.5", "2026-11-30"),       // EA 자릿수 0
                line("RM-KG", "1.2345", "2026-11-30"),     // KG 3
                line("FG-100", "1e3", "2026-11-30"),
                line("FG-100", "-1", "2026-11-30"),
                line("FG-100", "1,000", "2026-11-30"),
                line("FG-100", "1234567890123", "2026-11-30"),
                line("FG-100", "1", "2026-02-30")) + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(Matchers.contains("lines[0].qty", "lines[1].qty", "lines[2].qty",
                        "lines[3].qty", "lines[4].qty", "lines[5].qty", "lines[6].dueDate")))
                .andExpect(jsonPath("$.errors[*].code").value(Matchers.contains("SCALE", "SCALE", "FORMAT", "POSITIVE",
                        "FORMAT", "INT_DIGITS", "FORMAT")));
        // 큰 수·작은 소수도 그대로(응답은 글자)
        long o = order(null, line("RM-KG", "999999999999.999", "2026-11-30"));
        mvc.perform(get(O + "/" + o).with(admin)).andExpect(jsonPath("$.data.lines[0].qty").value("999999999999.999"));
    }

    @Test
    void 거래처_품목_발주번호_행_검증() throws Exception {
        accounts.exec(comp, "update mst_item set use_yn = 'N' where item_cd = 'RM-KG'");
        cust(comp, "C-OFF", "SALES");
        accounts.exec(comp, "update mst_cust set use_yn = 'N' where cust_cd = 'C-OFF'");
        postJson(O, "{\"custCd\":\"V-001\",\"lines\":[" + line("FG-100", "1", "2026-11-30") + "]}")
                .andExpect(jsonPath("$.errors[0].code").value("NOT_SALES"));
        postJson(O, "{\"custCd\":\"C-OFF\",\"lines\":[" + line("FG-100", "1", "2026-11-30") + "]}")
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        postJson(O, "{\"custCd\":\"NOPE\",\"lines\":[" + line("FG-100", "1", "2026-11-30") + "]}")
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        postJson(O, "{\"custCd\":\"C-001\",\"lines\":[]}").andExpect(jsonPath("$.errors[0].code").value("NO_LINES"));
        postJson(O, "{\"custCd\":\"C-001\",\"lines\":[" + line("RM-KG", "1", "2026-11-30") + "," + line("NOPE", "1", "2026-11-30") + "]}")
                .andExpect(jsonPath("$.errors[*].code").value(Matchers.contains("INACTIVE", "NO_SUCH_CODE")));
        postJson(O, "{\"custCd\":\"C-001\",\"lines\":[{\"lineNo\":10,\"itemCd\":\"FG-100\",\"qty\":\"1\",\"dueDate\":\"2026-11-30\"},"
                + "{\"lineNo\":10,\"itemCd\":\"FG-100\",\"qty\":\"2\",\"dueDate\":\"2026-11-30\"}]}")
                .andExpect(jsonPath("$.errors[0].field").value("lines[1].lineNo"))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        // 발주 번호: 앞뒤 공백은 정리, 제어 문자·한글은 FORMAT, 같은 거래처 안 유일(다른 거래처는 같은 번호 가능)
        order(" PO-77 ", line("FG-100", "1", "2026-11-30"));
        assertThat(accounts.query(comp, "select count(*) from sal_order where cust_po_no = 'PO-77'", Integer.class)).isEqualTo(1);
        postJson(O, "{\"custCd\":\"C-001\",\"custPoNo\":\"PO-77\",\"lines\":[" + line("FG-100", "1", "2026-11-30") + "]}")
                .andExpect(jsonPath("$.errors[0].field").value("custPoNo"))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        postJson(O, "{\"custCd\":\"C-002\",\"custPoNo\":\"PO-77\",\"lines\":[" + line("FG-100", "1", "2026-11-30") + "]}")
                .andExpect(status().isOk());
        postJson(O, "{\"custCd\":\"C-001\",\"custPoNo\":\"PO\\u0007\",\"lines\":[" + line("FG-100", "1", "2026-11-30") + "]}")
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        postJson(O, "{\"custCd\":\"C-001\",\"custPoNo\":\"발주1\",\"lines\":[" + line("FG-100", "1", "2026-11-30") + "]}")
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        postJson(O, "{\"custCd\":\"C-001\",\"remark\":\"a\\u0000b\",\"lines\":[" + line("FG-100", "1", "2026-11-30") + "]}")
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
    }

    @Test
    void 행의_품목_행번호_거래처는_바꿀_수_없다() throws Exception {
        long o = order("PO-1", line("FG-100", "10", "2026-11-30"));
        long l = lineId(o, 10);
        putJson(O + "/lines/" + l, "{\"itemCd\":\"RM-KG\",\"lineNo\":20,\"qty\":\"5\",\"dueDate\":\"2026-11-30\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[*].code").value(Matchers.contains("FIXED", "FIXED")));
        putJson(O + "/" + o, "{\"custCd\":\"C-002\",\"rowVer\":0}").andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        // 같은 rowVer 로 두 번 → 두 번째 409
        putJson(O + "/lines/" + l, "{\"qty\":\"5\",\"dueDate\":\"2026-11-30\",\"rowVer\":0}").andExpect(status().isOk());
        putJson(O + "/lines/" + l, "{\"qty\":\"6\",\"dueDate\":\"2026-11-30\",\"rowVer\":0}").andExpect(status().isConflict());
    }

    // ---------- 흐름 우회 (F) · 직접 SQL ----------

    @Test
    void 끝난_행은_화면도_직접_SQL_도_바꿀_수_없고_지울_수도_없다() throws Exception {
        long o = order("PO-1", line("FG-100", "10", "2026-11-30"), line("FG-100", "20", "2026-11-30"));
        long l = lineId(o, 10);
        postJson(O + "/lines/" + l + "/cancel", "{\"reason\":\"취소\",\"rowVer\":0}").andExpect(status().isOk());
        putJson(O + "/lines/" + l, "{\"qty\":\"5\",\"dueDate\":\"2026-11-30\",\"rowVer\":1}")
                .andExpect(jsonPath("$.errors[0].code").value("CLOSED"));
        postJson(O + "/lines/" + l + "/close", "{\"rowVer\":1}").andExpect(jsonPath("$.errors[0].code").value("CLOSED"));
        // 직접 SQL(앱 계정): 취소 행 되살리기·수량, 품목 바꾸기, 새 행 상태 DONE, 삭제
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update sal_order_line set status = 'OPEN', cancel_reason = null where line_id = ?", l))))
                .contains("ck_sal_order_line_closed");
        long l2 = lineId(o, 20);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update sal_order_line set item_id = (select item_id from mst_item where item_cd = 'RM-KG') where line_id = ?", l2))))
                .contains("ck_sal_order_line_fixed");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update sal_order_line set status = 'CANCELLED' where line_id = ?", l2))))
                .contains("ck_sal_order_line_cancel");                  // 취소는 사유와 함께만
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from sal_order_line where line_id = ?", l2))))
                .contains("42501");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from sal_order where order_id = ?", o))))
                .contains("42501");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update sal_order set cust_id = (select cust_id from mst_cust where cust_cd = 'C-002') where order_id = ?", o))))
                .contains("ck_sal_order_fixed");
    }

    // ---------- 동시성 (C) ----------

    @Test
    void 마지막_두_행을_동시에_마감해도_머리는_CLOSED_같은_발주_동시_생성은_한쪽만() throws Exception {
        for (int round = 0; round < 3; round++) {
            long o = order("PO-R" + round, line("FG-100", "1", "2026-11-30"), line("FG-100", "2", "2026-11-30"));
            long a = lineId(o, 10);
            long b = lineId(o, 20);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<Integer>> fs = pool.invokeAll(List.<Callable<Integer>>of(
                        () -> postJson(O + "/lines/" + a + "/close", "{\"rowVer\":0}").andReturn().getResponse().getStatus(),
                        () -> postJson(O + "/lines/" + b + "/close", "{\"rowVer\":0}").andReturn().getResponse().getStatus()));
                assertThat(fs.get(0).get()).isEqualTo(200);
                assertThat(fs.get(1).get()).isEqualTo(200);
            } finally {
                pool.shutdown();
            }
            assertThat(orderStatus(o)).isEqualTo("CLOSED");
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> create = () -> postJson(O, "{\"custCd\":\"C-001\",\"custPoNo\":\"PO-SAME\",\"lines\":["
                    + line("FG-100", "1", "2026-11-30") + "]}").andReturn().getResponse().getStatus();
            List<Future<Integer>> fs = pool.invokeAll(List.of(create, create));
            assertThat(List.of(fs.get(0).get(), fs.get(1).get())).containsExactlyInAnyOrder(200, 400);
        } finally {
            pool.shutdown();
        }
        assertThat(accounts.query(comp, "select count(*) from sal_order where cust_po_no = 'PO-SAME'", Integer.class)).isEqualTo(1);
        // 채번은 결번 없이(실패한 쪽은 롤백)
        assertThat(accounts.query(comp, "select count(distinct order_no) = count(*) from sal_order", Boolean.class)).isTrue();
    }

    // ---------- 조회 ----------

    @Test
    void 목록은_열린_기간_366일_상한_검색어의_퍼센트는_글자로() throws Exception {
        order("PO-%1", line("FG-100", "1", "2026-11-30"));
        order("PO-X", line("FG-100", "1", "2026-11-30"));
        // 기간을 비우면 그쪽 끝이 없다(홈 링크 — docs/25 4.13). 둘 다 있을 때만 순서·366일 상한
        mvc.perform(get(O).with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.rows.length()").value(2));
        mvc.perform(get(O + "?from=2026-01-01&to=2027-06-30").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("RANGE_TOO_LONG"));
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        mvc.perform(get(O).param("from", today).param("to", today).param("keyword", "%").with(admin))
                .andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.rows[0].custPoNo").value("PO-%1"));
        mvc.perform(get(O + "?from=" + today + "&to=" + today + "&status=OPEN&custCd=c-001&itemCd=fg-100").with(admin))
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                .andExpect(jsonPath("$.data.more").value(false));
        mvc.perform(get(O + "?from=" + today + "&to=" + today + "&status=BAD").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("CODE"));
    }

    // ---------- A사로 B사 · 권한 (A·E) ----------

    @Test
    void B사는_A사_수주를_보지도_바꾸지도_못하고_A사_거래처_품목으로_수주할_수_없다() throws Exception {
        long o = order("PO-A", line("FG-100", "10", "2026-11-30"));
        long l = lineId(o, 10);
        String other = accounts.newComp("SB");
        long ou = accounts.newUser(other, "b1", PW);
        accounts.grant(other, ou, "SALES", "sal-order", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, ou, "b1");
        mvc.perform(get(O + "/" + o).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(O + "/" + o), "{\"custPoNo\":\"X\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(O + "/lines/" + l), "{\"qty\":\"1\",\"dueDate\":\"2026-11-30\",\"rowVer\":0}").with(b))
                .andExpect(status().isNotFound());
        mvc.perform(json(post(O + "/lines/" + l + "/cancel"), "{\"reason\":\"x\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(O + "/" + o + "/lines"), line("FG-100", "1", "2026-11-30")).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(O), "{\"custCd\":\"C-001\",\"lines\":[" + line("FG-100", "1", "2026-11-30") + "]}").with(b))
                .andExpect(jsonPath("$.errors[0].field").value("custCd"))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        accounts.exec(other, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C-B', 'B 고객', 'SALES', 't', 't')""", other);
        mvc.perform(json(post(O), "{\"custCd\":\"C-B\",\"lines\":[" + line("FG-100", "1", "2026-11-30") + "]}").with(b))
                .andExpect(jsonPath("$.errors[0].field").value("lines[0].itemCd"))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        // 직접 SQL(B사 범위): A사 수주에 행, A사 거래처로 수주
        long aItem = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG-100'", Long.class);
        long aCust = accounts.query(comp, "select cust_id from mst_cust where cust_cd = 'C-001'", Long.class);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into sal_order_line (comp_cd, order_id, line_no, item_id, qty, due_date, created_by, updated_by)
                values (?, ?, 99, ?, 1, '2026-11-30', 't', 't')""", other, o, aItem)))).contains("fk_sal_order_line_order_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into sal_order (comp_cd, order_no, cust_id, order_date, created_by, updated_by)
                values (?, 'X-1', ?, '2026-10-20', 't', 't')""", other, aCust)))).contains("fk_sal_order_cust_comp");
        assertThat(accounts.exec(other, "update sal_order_line set qty = 1 where line_id = ?", l)).isZero();
        assertThat(accounts.query(comp, "select qty from sal_order_line where line_id = ?", BigDecimal.class, l))
                .isEqualByComparingTo("10");
    }

    @Test
    void 조회_권한만_있으면_저장은_403() throws Exception {
        long viewer = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewer, "VIEW", "sal-order", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer");
        long o = order("PO-1", line("FG-100", "10", "2026-11-30"));
        mvc.perform(get(O + "/" + o).with(v)).andExpect(status().isOk());
        mvc.perform(json(post(O), "{\"custCd\":\"C-001\",\"lines\":[" + line("FG-100", "1", "2026-11-30") + "]}").with(v))
                .andExpect(status().isForbidden());
        mvc.perform(json(post(O + "/lines/" + lineId(o, 10) + "/close"), "{\"rowVer\":0}").with(v)).andExpect(status().isForbidden());
    }

    // ---------- 엑셀 올리기 ----------

    private byte[] fill(List<Object[]> rows) throws Exception {
        byte[] tpl = mvc.perform(post(IMP + "/templates/SAL_ORDER/file").with(csrf()).with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(tpl))) {
            Sheet s = wb.getSheetAt(0);
            int r = 1;
            for (Object[] values : rows) {
                Row row = s.createRow(r++);
                for (int c = 0; c < values.length; c++) {
                    if (values[c] instanceof Number n) {
                        row.createCell(c).setCellValue(n.doubleValue());
                    } else if (values[c] != null) {
                        row.createCell(c).setCellValue(String.valueOf(values[c]));
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private ResultActions upload(byte[] file, String mode) throws Exception {
        return mvc.perform(multipart(IMP + "/templates/SAL_ORDER/jobs").file(new MockMultipartFile("file", "orders.xlsx", XLSX, file))
                .param("mode", mode).with(csrf()).with(admin));
    }

    private ResultActions execute(long job) throws Exception {
        int ver = accounts.query(comp, "select row_ver from sys_import_job where job_id = ?", Integer.class, job);
        return mvc.perform(post(IMP + "/jobs/" + job + "/execute").with(csrf()).with(admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rowVer\":" + ver + ",\"skipErrors\":true}"));
    }

    private static long job(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.jobId")).longValue();
    }

    /** 열 순서: 거래처·발주·행·품목·수량·납기·수주일·비고 */
    private static Object[] row(String cust, String po, Object no, String item, Object qty, String due, String date) {
        return new Object[] {cust, po, no, item, qty, due, date, null};
    }

    @Test
    void 엑셀로_새_발주는_수주를_만들고_다시_올리면_변경_끝난_행_품목_수주일은_막는다() throws Exception {
        long j1 = job(upload(fill(List.of(
                row("C-001", "PO-X1", 10, "FG-100", 100, "2026-11-30", "2026-10-15"),
                row("C-001", "PO-X1", 20, "RM-KG", "1.5", "2026-12-15", "2026-10-15"),
                row("c-002", "PO-X2", 10, "fg-100", 7, "2026-11-30", null),
                row("C-002", "PO-X2", 20, "FG-100", 8, "2026-11-30", null),
                row("V-001", "PO-X3", 10, "FG-100", 1, "2026-11-30", null),            // 구매 거래처
                row("C-001", "PO-X4", 10, "FG-100", "1.5", "2026-11-30", null),        // EA 자릿수
                row("C-001", "PO-X1", 30, "FG-100", 1, "2026-11-30", "2026-10-16"))), "UPSERT")   // 같은 발주 다른 수주일
                .andExpect(jsonPath("$.data.newCount").value(4))
                .andExpect(jsonPath("$.data.errorCount").value(3)));
        execute(j1).andExpect(status().isOk()).andExpect(jsonPath("$.data.result.inserted").value(4))
                .andExpect(jsonPath("$.data.result.matched").value(true));
        long o1 = accounts.query(comp, "select order_id from sal_order where cust_po_no = 'PO-X1'", Long.class);
        assertThat(accounts.query(comp, "select order_date::text from sal_order where order_id = ?", String.class, o1)).isEqualTo("2026-10-15");
        assertThat(accounts.query(comp, "select count(*) from sal_order_line where order_id = ?", Integer.class, o1)).isEqualTo(2);
        assertThat(accounts.query(comp, "select count(*) from sal_order", Integer.class)).isEqualTo(2);

        // 두 번째: 수량 변경 · 같음 · 새 행 · 끝난 행 변경 · 품목 바꾸기 · 수주일 바꾸기
        postJson(O + "/lines/" + lineId(o1, 20) + "/close", "{\"rowVer\":0}").andExpect(status().isOk());
        long j2 = job(upload(fill(List.of(
                row("C-001", "PO-X1", 10, "FG-100", 120, "2026-11-30", null),
                row("C-002", "PO-X2", 10, "FG-100", 7, "2026-11-30", null),
                row("C-001", "PO-X1", 30, "FG-100", 3, "2027-01-05", null),
                row("C-001", "PO-X1", 20, "RM-KG", 9, "2026-12-15", null),
                row("C-002", "PO-X2", 20, "RM-KG", 8, "2026-11-30", null),          // 품목 바꾸기
                row("C-002", "PO-X2", 30, "FG-100", 1, "2026-11-30", "2026-01-01"))), "UPSERT")   // 있는 수주의 수주일
                .andExpect(jsonPath("$.data.changeCount").value(1))
                .andExpect(jsonPath("$.data.sameCount").value(1))
                .andExpect(jsonPath("$.data.newCount").value(1))
                .andExpect(jsonPath("$.data.errorCount").value(3)));
        mvc.perform(get(IMP + "/jobs/" + j2 + "/rows?status=ERROR").with(admin))
                .andExpect(jsonPath("$.list[*].errors[0].code").value(Matchers.hasItems("CLOSED", "FIXED")));
        execute(j2).andExpect(status().isOk()).andExpect(jsonPath("$.data.result.updated").value(1))
                .andExpect(jsonPath("$.data.result.inserted").value(1));
        assertThat(accounts.query(comp, "select qty from sal_order_line where order_id = ? and line_no = 10", BigDecimal.class, o1))
                .isEqualByComparingTo("120");
        assertThat(accounts.query(comp, "select qty from sal_order_line where order_id = ? and line_no = 20", BigDecimal.class, o1))
                .isEqualByComparingTo("1.5");
    }
}
