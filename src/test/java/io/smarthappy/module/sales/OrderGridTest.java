package io.smarthappy.module.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 수주 주 목록 그리드 API (UI 단계 3 ① 묶음 3, 결정 docs/29 #45·#48) — `/api/sales/orders/grid` + `/columns`.
 * 기존 목록 API(500건 상한·납기 행 수)는 그대로, 새 API 는 같은 조건 + 페이지 경계·정렬 허용 열·건수, 조회 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class OrderGridTest extends PostgresTestBase {

    private static final String PW = "Harbor-violet-27";
    private static final String ORDERS = "/api/sales/orders";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("OG");
        admin = user(comp, "admin1", true);
        seed(comp, 5);
    }

    private RequestPostProcessor user(String c, String id, boolean search) {
        long u = accounts.newUser(c, id, PW);
        if (search) {
            accounts.grant(c, u, "SALES", "sal-order", "SEARCH");
        }
        return accounts.as(c, u, id);
    }

    /**
     * 거래처 C-A·C-B, 품목 FG. 수주 O-1..O-n(C-A, 수주일 10-0i, 행 하나 납기 11-(20-i)) — 마지막 하나 취소.
     * X_1(C-B, 수주일 10-09, 고객 발주 "PO_100%", 납기 11-01).
     */
    private void seed(String c, int n) {
        for (String cd : List.of("C-A", "C-B")) {
            accounts.exec(c, """
                    insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                    values (?, ?, ?, 'SALES', 't', 't')""", c, cd, cd + " 상사");
        }
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', 'FG 품목', 'FG', 'MAKE', 'EA', 't', 't')""", c);
        for (int i = 1; i <= n; i++) {
            order(c, "O-" + i, "C-A", null, "2026-10-0" + i, "2026-11-" + (20 - i));
        }
        order(c, "X_1", "C-B", "PO_100%", "2026-10-09", "2026-11-01");
        accounts.exec(c, "update sal_order_line set status = 'CANCELLED', cancel_reason = '시험' where order_id = (select order_id from sal_order where order_no = ?)", "O-" + n);
        accounts.exec(c, "update sal_order set status = 'CANCELLED' where order_no = ?", "O-" + n);
    }

    private void order(String c, String no, String cust, String po, String date, String due) {
        accounts.exec(c, """
                insert into sal_order (comp_cd, order_no, cust_id, cust_po_no, order_date, created_by, updated_by)
                select ?, ?, cust_id, ?, ?::date, 't', 't' from mst_cust where cust_cd = ?""", c, no, po, date, cust);
        accounts.exec(c, """
                insert into sal_order_line (comp_cd, order_id, line_no, item_id, qty, due_date, created_by, updated_by)
                select ?, o.order_id, 1, i.item_id, 10, ?::date, 't', 't' from sal_order o, mst_item i
                 where o.order_no = ? and i.item_cd = 'FG'""", c, due, no);
    }

    /** 조회 — 쿼리 문자열은 param 으로(정렬 값 "필드,방향" 의 쉼표가 목록 구분으로 나뉘지 않게, hb.grid 가 보내는 모양과 같음) */
    private MockHttpServletRequestBuilder req(String url) {
        int q = url.indexOf('?');
        MockHttpServletRequestBuilder b = get(q < 0 ? url : url.substring(0, q));
        if (q >= 0) {
            for (String kv : url.substring(q + 1).split("&")) {
                int e = kv.indexOf('=');
                b = b.param(kv.substring(0, e), kv.substring(e + 1));
            }
        }
        return b;
    }

    private MvcResult ok(RequestPostProcessor who, String url) throws Exception {
        MvcResult r = mvc.perform(req(url).with(who)).andReturn();
        assertThat(r.getResponse().getStatus()).as(url + " → " + r.getResponse().getContentAsString()).isEqualTo(200);
        return r;
    }

    private static List<String> field(MvcResult r, String path) throws Exception {
        return JsonPath.read(r.getResponse().getContentAsString(), path);
    }

    private static int total(MvcResult r) throws Exception {
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.total")).intValue();
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void 그리드는_수주일_최신_순으로_페이지를_나누고_경계에서_빠지거나_겹치지_않고_기존_목록과_같은_순서() throws Exception {
        MvcResult p1 = ok(admin, ORDERS + "/grid?page=1&size=2");
        assertThat(field(p1, "$.list[*].orderNo")).containsExactly("X_1", "O-5");
        assertThat(total(p1)).isEqualTo(6);
        assertThat(field(ok(admin, ORDERS + "/grid?page=2&size=2"), "$.list[*].orderNo")).containsExactly("O-4", "O-3");
        assertThat(field(ok(admin, ORDERS + "/grid?page=3&size=2"), "$.list[*].orderNo")).containsExactly("O-2", "O-1");
        assertThat(field(ok(admin, ORDERS + "/grid?page=4&size=2"), "$.list[*].orderNo")).isEmpty();
        assertThat(field(ok(admin, ORDERS + "/grid?size=100"), "$.list[*].orderNo"))
                .containsExactlyElementsOf(field(ok(admin, ORDERS), "$.data.rows[*].orderNo"));
        mvc.perform(req(ORDERS + "/grid?total=false").with(admin)).andExpect(jsonPath("$.total").doesNotExist());
    }

    @Test
    void 조건은_기존_목록과_같다_거래처_상태_기간_품목_납기_찾기() throws Exception {
        assertThat(total(ok(admin, ORDERS + "/grid?custCd=c-b"))).isEqualTo(1);                          // 코드는 대문자로
        assertThat(field(ok(admin, ORDERS + "/grid?status=CANCELLED"), "$.list[*].orderNo")).containsExactly("O-5");
        assertThat(field(ok(admin, ORDERS + "/grid?from=2026-10-02&to=2026-10-03"), "$.list[*].orderNo")).containsExactly("O-3", "O-2");
        assertThat(total(ok(admin, ORDERS + "/grid?itemCd=FG"))).isEqualTo(6);
        // 납기 조건 = 열린 행의 납기(홈 카드와 같음) — 취소한 O-5 의 행은 빠진다
        assertThat(field(ok(admin, ORDERS + "/grid?dueFrom=2026-11-15&dueTo=2026-11-17"), "$.list[*].orderNo")).containsExactly("O-4", "O-3");
        assertThat(field(ok(admin, ORDERS + "/grid?keyword=_"), "$.list[*].orderNo")).containsExactly("X_1");    // _ 는 글자
        assertThat(field(ok(admin, ORDERS + "/grid?keyword=100%"), "$.list[*].orderNo")).containsExactly("X_1"); // % 도 글자
        // 없는 거래처·품목 코드 = 빈 결과(기존과 같음)
        MvcResult none = ok(admin, ORDERS + "/grid?custCd=NOPE");
        assertThat(field(none, "$.list[*].orderNo")).isEmpty();
        assertThat(total(none)).isZero();
        assertThat(total(ok(admin, ORDERS + "/grid?itemCd=NOPE"))).isZero();
    }

    @Test
    void 정렬_허용_열과_열_정의() throws Exception {
        assertThat(field(ok(admin, ORDERS + "/grid?sort=orderNo,asc&size=2"), "$.list[*].orderNo")).containsExactly("O-1", "O-2");
        assertThat(field(ok(admin, ORDERS + "/grid?sort=custNm,desc&size=1"), "$.list[*].orderNo")).containsExactly("X_1");
        // 가까운 납기 — 열린 행이 없는(취소) 수주는 어느 방향이든 끝
        assertThat(field(ok(admin, ORDERS + "/grid?sort=nextDue,asc"), "$.list[*].orderNo")).containsExactly("X_1", "O-4", "O-3", "O-2", "O-1", "O-5");
        assertThat(field(ok(admin, ORDERS + "/grid?sort=nextDue,desc"), "$.list[*].orderNo")).endsWith("O-5");
        assertThat(field(ok(admin, ORDERS + "/grid?sort=status,desc&sort=orderNo,asc&size=2"), "$.list[*].orderNo")).containsExactly("O-1", "O-2");
        MvcResult cols = ok(admin, ORDERS + "/columns");
        assertThat(field(cols, "$.list[?(@.sortable == true)].field"))
                .containsExactlyInAnyOrder("orderNo", "custNm", "orderDate", "nextDue", "status");
        assertThat(field(cols, "$.list[?(@.field == 'orderNo')].title")).containsExactly("수주 번호");
    }

    // =====================================================================
    // 거부 · A사로 B사 · 권한
    // =====================================================================

    @Test
    void B_정렬_허용_밖_잘못된_방향_페이지_0_크기_초과_상태_기간_찾기어_길이는_400() throws Exception {
        for (String q : List.of("sort=remark,asc", "sort=order_no,asc", "sort=orderNo,up", "page=0", "size=0", "size=100000",
                "sort=orderNo,asc&sort=orderNo,desc", "status=NOPE", "from=2026-10-05&to=2026-10-01", "from=2025-01-01&to=2026-10-01",
                "dueFrom=2026-13-01", "keyword=" + "a".repeat(51))) {
            mvc.perform(req(ORDERS + "/grid?" + q).with(admin)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void A사로_B사_그리드는_자기_회사_것만_보이고_조회_권한이_없으면_403() throws Exception {
        String b = accounts.newComp("OB");
        RequestPostProcessor bAdmin = user(b, "admin1", true);
        seed(b, 2);
        MvcResult r = ok(bAdmin, ORDERS + "/grid");
        assertThat(field(r, "$.list[*].orderNo")).containsExactly("X_1", "O-2", "O-1");
        assertThat(total(r)).isEqualTo(3);
        assertThat(total(ok(admin, ORDERS + "/grid"))).isEqualTo(6);
        assertThat(field(ok(bAdmin, ORDERS + "/grid?keyword=O-4"), "$.list[*].orderNo")).isEmpty();          // A사 번호로 찾아도 없음
        // B사에만 있는 거래처 코드로 A사에서 조건 → 없는 코드와 같이 빈 결과
        accounts.exec(b, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'C-BONLY', 'B사', 'SALES', 't', 't')", b);
        assertThat(total(ok(admin, ORDERS + "/grid?custCd=C-BONLY"))).isZero();
        RequestPostProcessor noAuth = user(comp, "viewer", false);
        mvc.perform(get(ORDERS + "/grid").with(noAuth)).andExpect(status().isForbidden());
        mvc.perform(get(ORDERS + "/columns").with(noAuth)).andExpect(status().isForbidden());
    }
}
