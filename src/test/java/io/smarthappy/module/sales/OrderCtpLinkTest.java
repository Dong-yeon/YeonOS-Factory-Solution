package io.smarthappy.module.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 수주 화면 [납기 답변] — APS CTP 계약(`POST /api/aps/ctp`, docs/38 15.4)을 영업이 HTTP 로만 부른다(결정 docs/29 #52 뒤 상위 지시).
 * 버튼 보일지 = `/api/sales/orders/ctp-available`(APS 생산 계획 조회 권한), 화면이 보내는 본문(수주 행의 품목·수량 글자·납기)이
 * 그대로 답을 받는다, 저장 없음(수주·지시 그대로), APS 권한 없으면 APS 가 403, A사로 B사.
 */
@AutoConfigureMockMvc
class OrderCtpLinkTest extends PostgresTestBase {

    private static final String PW = "Harbor-violet-27";
    private static final String ORDERS = "/api/sales/orders";
    private static final String CTP = "/api/aps/ctp";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor sales;
    RequestPostProcessor both;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("SC");
        sales = user(comp, "sales1", false);
        both = user(comp, "both1", true);
        seed(comp);
    }

    private RequestPostProcessor user(String c, String id, boolean aps) {
        long u = accounts.newUser(c, id, PW);
        accounts.grant(c, u, "SALES", "sal-order", "SEARCH", "SAVE");
        if (aps) {
            accounts.grant(c, u, "PLAN", "aps-plan", "SEARCH");
        }
        return accounts.as(c, u, id);
    }

    /**
     * 공장 P1(주간 08~17), 작업장 L1(설비 M1), 공정 절단, 품목 FG(절단 L1 1시간/개, 확정 라우팅), 거래처 C-A,
     * 수주 O-1 행 1 = FG 3개, 납기 = 회사 오늘 + 20일
     */
    private void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, """
                insert into mst_shift (comp_cd, plant_cd, shift_cd, shift_nm, start_time, end_time, created_by, updated_by)
                values (?, 'P1', 'D', '주간', '08:00', '17:00', 't', 't')""", c);
        long area = accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', 'AREA', 'A1', 'A1 구역', 't', 't') returning node_id""", Long.class, c);
        long line = accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, 'LINE', 'L1', 'L1 작업장', 't', 't') returning node_id""", Long.class, c, area);
        long cls = accounts.query(c, """
                insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by)
                values (?, 'PRESS', '프레스', 't', 't') returning class_id""", Long.class, c);
        accounts.exec(c, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, node_id, created_by, updated_by)
                values (?, 'P1', 'M1', 'M1 프레스', ?, ?, 't', 't')""", c, cls, line);
        accounts.exec(c, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, outsrc_yn, created_by, updated_by)
                values (?, 'CUT', '절단', 'WELD', 'N', 't', 't')""", c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', 'FG 품목', 'FG', 'MAKE', 'EA', 't', 't')""", c);
        long rid = accounts.query(c, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                returning routing_id""", Long.class, c);
        accounts.exec(c, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'CUT'), ?, 3600, 't', 't')""", c, rid, line);
        accounts.exec(c, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        accounts.exec(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C-A', 'C-A 상사', 'SALES', 't', 't')""", c);
        accounts.exec(c, """
                insert into sal_order (comp_cd, order_no, cust_id, order_date, created_by, updated_by)
                select ?, 'O-1', cust_id, """ + TestAccounts.COMP_TODAY + ", 't', 't' from mst_cust where cust_cd = 'C-A'", c);
        accounts.exec(c, """
                insert into sal_order_line (comp_cd, order_id, line_no, item_id, qty, due_date, created_by, updated_by)
                select ?, o.order_id, 1, i.item_id, 3, """ + TestAccounts.COMP_TODAY + """
                 + 20, 't', 't' from sal_order o, mst_item i where o.order_no = 'O-1' and i.item_cd = 'FG'""", c);
    }

    private ResultActions ctp(RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(post(CTP).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    /** 화면이 보내는 본문 = 수주 상세 API 의 행 값 그대로(qty 글자·dueDate) */
    private String screenBody(RequestPostProcessor who) throws Exception {
        String list = mvc.perform(get(ORDERS + "/grid").with(who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long orderId = ((Number) JsonPath.read(list, "$.list[0].orderId")).longValue();
        String detail = mvc.perform(get(ORDERS + "/" + orderId).with(who)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        Map<String, Object> line = JsonPath.read(detail, "$.data.lines[0]");
        return "{\"plantCd\":\"P1\",\"itemCd\":\"" + line.get("itemCd") + "\",\"qty\":\"" + line.get("qty") + "\",\"wantDate\":\""
                + line.get("dueDate") + "\"}";
    }

    private String snapshot() {
        return accounts.query(comp, """
                select (select string_agg(order_id || ':' || row_ver || ':' || status, ',') from sal_order)
                    || '|' || (select string_agg(line_id || ':' || row_ver || ':' || qty || ':' || status, ',') from sal_order_line)
                    || '|' || (select count(*) from prd_wo) || '|' || (select count(*) from aps_plan)""", String.class);
    }

    @Test
    void 버튼은_APS_생산_계획_조회_권한이_있을_때만() throws Exception {
        mvc.perform(get(ORDERS + "/ctp-available").with(sales)).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(false));
        mvc.perform(get(ORDERS + "/ctp-available").with(both)).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        // 수주 조회 권한조차 없으면 이 끝점도 403(수주 화면 밖에서 APS 권한을 떠보지 못하게)
        long n = accounts.newUser(comp, "none1", PW);
        mvc.perform(get(ORDERS + "/ctp-available").with(accounts.as(comp, n, "none1"))).andExpect(status().isForbidden());
    }

    @Test
    void 화면이_보내는_수주_행_본문으로_끝_시각을_받고_수주와_지시는_그대로() throws Exception {
        String body = screenBody(both);
        String before = snapshot();
        ctp(both, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.itemCd").value("FG"))
                .andExpect(jsonPath("$.data.endAt").isNotEmpty())
                .andExpect(jsonPath("$.data.lateYn").value("N"))                // 3시간 일 — 20일 뒤 납기 안
                .andExpect(jsonPath("$.data.ops[0].procCd").value("CUT"))
                .andExpect(jsonPath("$.data.excCd").doesNotExist());
        assertThat(snapshot()).isEqualTo(before);
        // 납기가 이미 지난 행이면 늦음
        ctp(both, body.replaceAll("\"wantDate\":\"[0-9-]+\"", "\"wantDate\":\"2000-01-01\"")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lateYn").value("Y"));
    }

    @Test
    void APS_권한이_없으면_APS_가_403_공장_목록도_403() throws Exception {
        String body = screenBody(sales);
        ctp(sales, body).andExpect(status().isForbidden());
        mvc.perform(get("/api/aps/plans/plants").with(sales)).andExpect(status().isForbidden());
        mvc.perform(get("/api/aps/plans/plants").with(both)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].plantCd").value("P1"));
    }

    @Test
    void A사로_B사_B사_품목_코드로_물으면_없는_품목() throws Exception {
        String b = accounts.newComp("SD");
        RequestPostProcessor bBoth = user(b, "both1", true);
        accounts.addPlant(b, "P1", "B사 공장");
        // A사 수주 행 본문(A사 품목 FG)을 B사 사용자가 보내면 — B사에는 FG 가 없다
        ctp(bBoth, screenBody(both)).andExpect(status().isBadRequest());
        mvc.perform(get(ORDERS + "/ctp-available").with(bBoth)).andExpect(jsonPath("$.data").value(true));
    }
}
