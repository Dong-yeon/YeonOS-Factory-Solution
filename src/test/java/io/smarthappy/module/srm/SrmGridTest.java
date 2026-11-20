package io.smarthappy.module.srm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

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

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * UI 단계 3 ① (docs/31 5.2) — 구매요청·발주·ASN grid API: 목록과 같은 조건, 서버 페이징 경계·전체 건수, 정렬 허용 목록
 * (모르는 열·4개 이상·쪽 크기 400, 정렬 값 하나 = 쉼표째 한 키), 열 정의, 권한(SEARCH), A사로 B사. 기존 목록 API 는 그대로.
 */
@AutoConfigureMockMvc
class SrmGridTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String PR = "/api/srm/prs";
    private static final String PO = "/api/srm/pos";
    private static final String ASN = "/api/srm/asns";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor me;
    LocalDate today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("SG");
        me = buyer(comp, "buyer1");
        seed(comp);
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    private RequestPostProcessor buyer(String c, String login) {
        long u = accounts.newUser(c, login, PW);
        accounts.grant(c, u, "PUR", "srm-pr", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, u, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, u, "PUR", "srm-asn", "SEARCH", "SAVE");
        return accounts.as(c, u, login);
    }

    private void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT-M8', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't')""", c);
        for (String v : List.of("V100", "V200")) {
            long id = accounts.query(c, """
                    insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                    values (?, ?, ? || ' 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, c, v, v);
            accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, c);
        }
    }

    private ResultActions postAs(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data")).longValue();
    }

    private ResultActions grid(String api, RequestPostProcessor who, String... params) throws Exception {
        MockHttpServletRequestBuilder b = get(api + "/grid").param("from", today.minusDays(7).toString()).param("to", today.toString());
        for (int i = 0; i < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return mvc.perform(b.with(who));
    }

    private static List<Object> col(ResultActions r, String field) throws Exception {
        return JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.list[*]." + field);
    }

    private List<Object> listed(String api, String field) throws Exception {
        return JsonPath.read(mvc.perform(get(api).param("from", today.minusDays(7).toString()).param("to", today.toString()).with(me))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data[*]." + field);
    }

    /** 정렬 허용 목록 밖·4개·쪽 크기·쪽 번호 → 400, 기간 없음 → 400 */
    private void rejects(String api, String a, String b, String c, String d) throws Exception {
        grid(api, me, "sort", "updatedBy,asc").andExpect(status().isBadRequest());
        grid(api, me, "sort", "comp_cd;drop table x,asc").andExpect(status().isBadRequest());
        grid(api, me, "sort", a + ",up").andExpect(status().isBadRequest());
        grid(api, me, "sort", a + ",asc", "sort", b + ",asc", "sort", c + ",asc", "sort", d + ",asc").andExpect(status().isBadRequest());
        grid(api, me, "size", "100000").andExpect(status().isBadRequest());
        grid(api, me, "page", "0").andExpect(status().isBadRequest());
        mvc.perform(get(api + "/grid").with(me)).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ 구매요청

    @Test
    void 구매요청_페이지경계_정렬_허용목록_목록과_같은조건() throws Exception {
        // 필요일 +3·+1·+5·+2·+4 → 필요일 오름차순 2개씩: 1쪽 +1·+2, 2쪽 +3·+4, 3쪽 +5, 4쪽 없음
        for (int d : List.of(3, 1, 5, 2, 4)) {
            postAs(me, PR, "{\"plantCd\":\"P1\",\"needDate\":\"" + today.plusDays(d) + "\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"" + d + "\"}]}")
                    .andExpect(status().isOk());
        }
        grid(PR, me, "size", "2", "sort", "needDate,asc").andExpect(jsonPath("$.total").value(5));
        assertThat(col(grid(PR, me, "size", "2", "page", "1", "sort", "needDate,asc"), "needDate"))
                .containsExactly(today.plusDays(1).toString(), today.plusDays(2).toString());
        assertThat(col(grid(PR, me, "size", "2", "page", "2", "sort", "needDate,asc"), "needDate"))
                .containsExactly(today.plusDays(3).toString(), today.plusDays(4).toString());
        assertThat(col(grid(PR, me, "size", "2", "page", "3", "sort", "needDate,asc"), "needDate")).containsExactly(today.plusDays(5).toString());
        assertThat(col(grid(PR, me, "size", "2", "page", "4", "sort", "needDate,asc"), "needDate")).isEmpty();
        // 정렬 값 하나("필드,방향")가 쉼표에서 쪼개지지 않는다 — 번호 내림차순 = 만든 반대 순서
        List<Object> byNoDesc = col(grid(PR, me, "sort", "prNo,desc"), "prNo");
        assertThat(byNoDesc).hasSize(5).isSortedAccordingTo((x, y) -> ((String) y).compareTo((String) x));
        // 기본 정렬 = 요청일 내림차순(같으면 id 내림차순) — 목록 API 와 같은 순서
        assertThat(col(grid(PR, me, "size", "50"), "prNo")).containsExactlyElementsOf(listed(PR, "prNo"));
        // 같은 조건: 상태 REQUESTED 0건, 검색어
        grid(PR, me, "status", "REQUESTED").andExpect(jsonPath("$.total").value(0));
        grid(PR, me, "keyword", "BOLT").andExpect(jsonPath("$.total").value(5));
        grid(PR, me, "status", "NOPE").andExpect(status().isBadRequest());
        rejects(PR, "prNo", "prDate", "needDate", "status");
        mvc.perform(get(PR + "/columns").with(me)).andExpect(jsonPath("$.list[*].field").value(hasItems("prNo", "status", "lineCnt")));
    }

    // ------------------------------------------------------------------ 발주

    @Test
    void 발주_합계정렬_페이지경계_목록과_같은조건() throws Exception {
        // 수량(= 합계, 단가 1) 30·10·50 → 합계 내림차순 2개씩: 1쪽 50·30, 2쪽 10
        for (String q : List.of("30", "10", "50")) {
            postAs(me, PO, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"" + q
                    + "\",\"unitPrice\":\"1\",\"dueDate\":\"" + today.plusDays(10) + "\"}]}").andExpect(status().isOk());
        }
        postAs(me, PO, "{\"custCd\":\"V200\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"7\",\"unitPrice\":\"1\",\"dueDate\":\""
                + today.plusDays(10) + "\"}]}").andExpect(status().isOk());
        grid(PO, me, "custCd", "V100", "size", "2").andExpect(jsonPath("$.total").value(3));
        assertThat(col(grid(PO, me, "custCd", "V100", "size", "2", "page", "1", "sort", "totalAmt,desc"), "totalAmt")).containsExactly("50", "30");
        assertThat(col(grid(PO, me, "custCd", "V100", "size", "2", "page", "2", "sort", "totalAmt,desc"), "totalAmt")).containsExactly("10");
        // 둘째 키: 협력사 오름차순 → 같으면 합계 오름차순
        assertThat(col(grid(PO, me, "sort", "custNm,asc", "sort", "totalAmt,asc"), "totalAmt")).containsExactly("10", "30", "50", "7");
        assertThat(col(grid(PO, me, "size", "50"), "poNo")).containsExactlyElementsOf(listed(PO, "poNo"));
        grid(PO, me, "status", "ISSUED").andExpect(jsonPath("$.total").value(0));
        rejects(PO, "poNo", "poDate", "custNm", "status");
        mvc.perform(get(PO + "/columns").with(me)).andExpect(jsonPath("$.list[*].field").value(hasItems("poNo", "totalAmt", "currencyCd", "status")));
    }

    // ------------------------------------------------------------------ ASN

    private long issuedLine() throws Exception {
        long po = id(postAs(me, PO, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"100\",\"unitPrice\":\"1\",\"dueDate\":\""
                + today.plusDays(10) + "\"}]}"));
        for (String a : List.of("confirm", "issue")) {
            int v = JsonPath.read(mvc.perform(get(PO + "/" + po).with(me)).andReturn().getResponse().getContentAsString(), "$.data.po.rowVer");
            postAs(me, PO + "/" + po + "/" + a, "{\"rowVer\":" + v + "}").andExpect(status().isOk());
        }
        return accounts.query(comp, "select po_line_id from srm_po_line where po_id = ?", Long.class, po);
    }

    private long asn(long poLine, LocalDate ship, LocalDate eta, String qty) throws Exception {
        return id(postAs(me, ASN, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"shipDate\":\"" + ship + "\",\"etaDate\":\"" + eta
                + "\",\"lines\":[{\"poLineId\":" + poLine + ",\"qty\":\"" + qty + "\"}]}"));
    }

    @Test
    void ASN_도착예정일_정렬_늦은ASN만_페이지경계() throws Exception {
        long l = issuedLine();
        asn(l, today, today.plusDays(3), "10");
        asn(l, today, today.plusDays(1), "10");
        long late = asn(l, today.minusDays(5), today.minusDays(1), "10");
        int v = JsonPath.read(mvc.perform(get(ASN + "/" + late).with(me)).andReturn().getResponse().getContentAsString(), "$.data.asn.rowVer");
        postAs(me, ASN + "/" + late + "/send", "{\"rowVer\":" + v + "}").andExpect(status().isOk());
        // 도착 예정일 오름차순 2개씩: 1쪽 −1·+1, 2쪽 +3
        grid(ASN, me, "size", "2").andExpect(jsonPath("$.total").value(3));
        assertThat(col(grid(ASN, me, "size", "2", "page", "1", "sort", "etaDate,asc"), "etaDate"))
                .containsExactly(today.minusDays(1).toString(), today.plusDays(1).toString());
        assertThat(col(grid(ASN, me, "size", "2", "page", "2", "sort", "etaDate,asc"), "etaDate")).containsExactly(today.plusDays(3).toString());
        // 늦은 ASN 만 — 목록과 같은 판정(확정 + 남은 행 + 도착 예정일 지남)
        grid(ASN, me, "late", "true").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.list[0].asnId").value(late))
                .andExpect(jsonPath("$.list[0].late").value(true));
        assertThat(col(grid(ASN, me, "size", "50"), "asnNo")).containsExactlyElementsOf(listed(ASN, "asnNo"));
        grid(ASN, me, "status", "SENT").andExpect(jsonPath("$.total").value(1));
        rejects(ASN, "asnNo", "etaDate", "shipDate", "status");
        mvc.perform(get(ASN + "/columns").with(me)).andExpect(jsonPath("$.list[*].field").value(hasItems("asnNo", "etaDate", "openLineCnt", "status")));
    }

    // ------------------------------------------------------------------ 권한 · A사로 B사

    @Test
    void 권한없으면_403_B사는_A사_행을_못본다() throws Exception {
        postAs(me, PR, "{\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"1\"}]}").andExpect(status().isOk());
        postAs(me, PO, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"1\",\"unitPrice\":\"1\",\"dueDate\":\""
                + today.plusDays(10) + "\"}]}").andExpect(status().isOk());
        asn(issuedLine(), today, today.plusDays(2), "5");
        String other = accounts.newComp("SH");
        RequestPostProcessor b = buyer(other, "buyer1");
        for (String api : List.of(PR, PO, ASN)) {
            grid(api, me).andExpect(jsonPath("$.total").value(api.equals(PO) ? 2 : 1));
            grid(api, b).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.list.length()").value(0));
        }
        // SEARCH 없는 사용자(다른 화면 권한만) → grid·columns 403
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "OTHER", "srm-supplier", "SEARCH");
        RequestPostProcessor n = accounts.as(comp, none, "none1");
        for (String api : List.of(PR, PO, ASN)) {
            grid(api, n).andExpect(status().isForbidden());
            mvc.perform(get(api + "/columns").with(n)).andExpect(status().isForbidden());
        }
    }
}
