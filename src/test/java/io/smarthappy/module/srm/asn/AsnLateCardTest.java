package io.smarthappy.module.srm.asn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.home.HomeService;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 홈 카드 "늦은 ASN" (docs/37 23장) — 숫자 = ASN 목록 "늦은 ASN 만" + 링크 기간과 같은 건수, 창 밖·오늘 도착은 보조 숫자,
 * 작성 중·취소는 세지 않음, 항목 링크 = 그 ASN 이 든 목록, 권한 R1, A사로 B사. ASN 은 화면과 같은 API 흐름(작성 → 보냄)으로 만든다.
 */
@AutoConfigureMockMvc
class AsnLateCardTest extends PostgresTestBase {

    private static final String PW = "Harbor-plume-58";
    private static final String PO = "/api/srm/pos";
    private static final String ASN = "/api/srm/asns";
    private static final String CARDS = "/api/home/cards";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor me;
    LocalDate today;
    long poLine;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("AL");
        me = buyer(comp, "buyer1");
        seed(comp);
        today = LocalDate.parse(accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class));
        poLine = issued(comp, me);
    }

    private RequestPostProcessor buyer(String c, String id) {
        long u = accounts.newUser(c, id, PW);
        accounts.grant(c, u, "PUR", HomeService.PROG, "SEARCH");
        accounts.grant(c, u, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, u, "PUR", "srm-asn", "SEARCH", "SAVE");
        return accounts.as(c, u, id);
    }

    private void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT-M8', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't')""", c);
        long id = accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'V100', 'V100 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, c);
        accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, c);
    }

    private ResultActions send(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data")).longValue();
    }

    /** 발행된 발주 행 하나(BOLT 100 EA) */
    private long issued(String c, RequestPostProcessor who) throws Exception {
        long id = id(send(who, PO, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"100\","
                + "\"unitPrice\":\"1\",\"dueDate\":\"" + today.plusDays(20) + "\"}]}"));
        for (String a : List.of("confirm", "issue")) {
            int v = JsonPath.read(mvc.perform(get(PO + "/" + id).with(who)).andReturn().getResponse().getContentAsString(), "$.data.po.rowVer");
            send(who, PO + "/" + id + "/" + a, "{\"rowVer\":" + v + "}").andExpect(status().isOk());
        }
        return accounts.query(c, "select po_line_id from srm_po_line where po_id = ?", Long.class, id);
    }

    /** ASN 하나(10 EA) — eta 일 전에 보내고(출하 = eta − 1), action: null = 작성 중, "send" = 보냄, "cancel" = 보낸 뒤 취소. 번호를 돌려준다 */
    private String asn(String c, RequestPostProcessor who, long line, LocalDate eta, String action) throws Exception {
        long a = id(send(who, ASN, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"shipDate\":\"" + eta.minusDays(1) + "\",\"etaDate\":\"" + eta
                + "\",\"lines\":[{\"poLineId\":" + line + ",\"qty\":\"10\"}]}"));
        for (String act : action == null ? List.<String>of() : "cancel".equals(action) ? List.of("send", "cancel") : List.of("send")) {
            int v = JsonPath.read(mvc.perform(get(ASN + "/" + a).with(who)).andReturn().getResponse().getContentAsString(), "$.data.asn.rowVer");
            send(who, ASN + "/" + a + "/" + act, "{\"rowVer\":" + v + ("cancel".equals(act) ? ",\"reason\":\"시험 취소\"" : "") + "}")
                    .andExpect(status().isOk());
        }
        return accounts.query(c, "select asn_no from srm_asn where asn_id = ?", String.class, a);
    }

    private String home(RequestPostProcessor who) throws Exception {
        return mvc.perform(get(CARDS).with(who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String card(String code) {
        return "$.data.cards[?(@.code == '" + code + "')]";
    }

    private static long num(String json) {
        List<Object> v = JsonPath.read(json, card("srm-asn-late") + ".count");
        return ((Number) v.get(0)).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> link(String json, String path) {
        List<Map<String, String>> v = JsonPath.read(json, card("srm-asn-late") + path);
        return v.get(0);
    }

    /** 링크 조건 그대로 ASN 목록 API → 번호들 */
    private List<String> list(Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder r = get(ASN).with(me);
        params.forEach(r::param);
        return JsonPath.read(mvc.perform(r).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data[*].asnNo");
    }

    @Test
    void 숫자는_늦은_ASN_목록과_같고_창밖_오늘도착은_보조숫자_작성중_취소는_세지않는다() throws Exception {
        String late = asn(comp, me, poLine, today.minusDays(2), "send");          // 늦음 — 센다
        asn(comp, me, poLine, today, "send");                                    // 오늘 도착 예정 — 아직 안 늦음
        String old = asn(comp, me, poLine, today.minusDays(400), "send");         // 1년 넘게 늦음 — 창 밖 보조 숫자
        asn(comp, me, poLine, today.minusDays(3), null);                         // 작성 중 — 제외
        asn(comp, me, poLine, today.minusDays(3), "cancel");                     // 취소 — 제외
        String h = home(me);
        assertThat(num(h)).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(h, card("srm-asn-late") + ".tone")).containsExactly("ALERT");
        assertThat(JsonPath.<List<Integer>>read(h, card("srm-asn-late") + ".facts[0].value")).as("오늘 도착 예정").containsExactly(1);
        assertThat(JsonPath.<List<Integer>>read(h, card("srm-asn-late") + ".facts[1].value")).as("창 밖").containsExactly(1);
        // 숫자 링크 = "늦은 ASN 만" + 기간(목록 상한 창 ~ 오늘) → 목록 = 카드 숫자
        Map<String, String> l = link(h, ".link");
        assertThat(l).containsEntry("late", "true").containsEntry("to", today.toString());
        assertThat(list(l)).as("목록 = 카드 숫자").containsExactly(late);
        // 항목: 오래 늦은 것부터, 링크 = 그 ASN 이 든 목록
        assertThat(JsonPath.<List<String>>read(h, card("srm-asn-late") + ".items[*].title")).containsExactly(old, late);
        assertThat(list(link(h, ".items[0].link"))).contains(old);
        assertThat(list(link(h, ".items[1].link"))).contains(late);
    }

    @Test
    void 늦은_ASN_이_없으면_숫자_0_링크_없음() throws Exception {
        asn(comp, me, poLine, today.plusDays(2), "send");
        String h = home(me);
        assertThat(num(h)).isZero();
        assertThat(JsonPath.<List<Object>>read(h, card("srm-asn-late") + ".link")).containsExactly((Object) null);
    }

    @Test
    void ASN_화면_권한이_있을_때만_A사로_B사_건수는_섞이지_않는다() throws Exception {
        asn(comp, me, poLine, today.minusDays(1), "send");
        long u = accounts.newUser(comp, "wh1", PW);
        accounts.grant(comp, u, "WH", HomeService.PROG, "SEARCH");
        assertThat(JsonPath.<List<String>>read(home(accounts.as(comp, u, "wh1")), "$.data.cards[*].code")).doesNotContain("srm-asn-late");
        mvc.perform(get(CARDS + "/srm-asn-late").with(accounts.as(comp, u, "wh1"))).andExpect(status().isNotFound());
        // B사: 늦은 ASN 2개 — A사 숫자는 그대로 1
        String other = accounts.newComp("AL");
        RequestPostProcessor b = buyer(other, "buyer1");
        seed(other);
        long bLine = issued(other, b);
        asn(other, b, bLine, today.minusDays(1), "send");
        asn(other, b, bLine, today.minusDays(5), "send");
        assertThat(num(home(b))).isEqualTo(2);
        assertThat(num(home(me))).isEqualTo(1);
    }
}
