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
 * UI 단계 3 ① 3묶음 (docs/31 5.2) — 통지 이력·발주 진행 현황 grid API: 목록과 같은 조건(진행 현황은 보기·임박 일수 포함)·계산 칸,
 * 서버 페이징 경계·전체 건수, 정렬 허용 목록(400), 받는 주소 가림, 열 정의, 권한(SEARCH), A사로 B사.
 */
@AutoConfigureMockMvc
class SrmGrid3Test extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String PO = "/api/srm/pos";
    private static final String NOTICE = "/api/srm/notices";
    private static final String STATUS = "/api/srm/po-status";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor me;
    LocalDate today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("SN");
        me = buyer(comp, "buyer1");
        seed(comp);
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    private RequestPostProcessor buyer(String c, String login) {
        long u = accounts.newUser(c, login, PW);
        for (String p : List.of("srm-po", "srm-ack", "srm-notify", "srm-po-status")) {
            accounts.grant(c, u, "PUR", p, "SEARCH", "SAVE", "APPROVE");
        }
        return accounts.as(c, u, login);
    }

    private void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT-M8', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't')""", c);
        for (String v : List.of("V100", "V200", "V300")) {
            long id = accounts.query(c, """
                    insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, email, created_by, updated_by)
                    values (?, ?, ? || ' 상사', 'PURCHASE', lower(?) || '@example.com', 't', 't') returning cust_id""", Long.class, c, v, v, v);
            accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, c);
        }
    }

    private ResultActions postAs(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data")).longValue();
    }

    /** 발행 발주(행 1: 볼트 10, 요구 납기 = 오늘 + dueIn) */
    private long issuedPo(String cust, int dueIn) throws Exception {
        long po = id(postAs(me, PO, "{\"custCd\":\"" + cust + "\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"10\",\"unitPrice\":\"1\",\"dueDate\":\""
                + today.plusDays(dueIn) + "\"}]}"));
        for (String a : List.of("confirm", "issue")) {
            int v = JsonPath.read(mvc.perform(get(PO + "/" + po).with(me)).andReturn().getResponse().getContentAsString(), "$.data.po.rowVer");
            postAs(me, PO + "/" + po + "/" + a, "{\"rowVer\":" + v + "}").andExpect(status().isOk());
        }
        return po;
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

    private void rejects(String api, String a, String b, String c, String d) throws Exception {
        grid(api, me, "sort", "updatedBy,asc").andExpect(status().isBadRequest());
        grid(api, me, "sort", "comp_cd;drop table x,asc").andExpect(status().isBadRequest());
        grid(api, me, "sort", a + ",up").andExpect(status().isBadRequest());
        grid(api, me, "sort", a + ",asc", "sort", a + ",desc").andExpect(status().isBadRequest());
        grid(api, me, "sort", a + ",asc", "sort", b + ",asc", "sort", c + ",asc", "sort", d + ",asc").andExpect(status().isBadRequest());
        grid(api, me, "size", "100000").andExpect(status().isBadRequest());
        grid(api, me, "page", "0").andExpect(status().isBadRequest());
        mvc.perform(get(api + "/grid").with(me)).andExpect(status().isBadRequest());
    }

    @Test
    void 통지이력_페이지경계_번호정렬_주소가림_상태조건() throws Exception {
        for (String v : List.of("V200", "V100", "V300")) {
            issuedPo(v, 10);
        }
        // 발행마다 통지 1건(SRM_PO_ISSUED) — 발주 번호 오름차순 2개씩
        grid(NOTICE, me, "size", "2").andExpect(jsonPath("$.total").value(3));
        List<Object> all = col(grid(NOTICE, me, "sort", "poNo,asc"), "poNo");
        assertThat(col(grid(NOTICE, me, "size", "2", "page", "1", "sort", "poNo,asc"), "poNo")).containsExactlyElementsOf(all.subList(0, 2));
        assertThat(col(grid(NOTICE, me, "size", "2", "page", "2", "sort", "poNo,asc"), "poNo")).containsExactlyElementsOf(all.subList(2, 3));
        assertThat(col(grid(NOTICE, me, "sort", "custNm,asc"), "custNm")).containsExactly("V100 상사", "V200 상사", "V300 상사");
        // 받는 주소는 목록처럼 가린다(원래 주소가 응답에 없다)
        String body = grid(NOTICE, me).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("v100@example.com").doesNotContain("v200@example.com");
        // 주소 없는 통지 — grid 표시 칸은 null 이 아닌 빈 글자(hb.grid 가 format 을 불러 "(주소 없음)" 을 보이게)
        accounts.exec(comp, "update sys_outbox set payload = payload - 'to' where outbox_id = (select min(outbox_id) from sys_outbox where topic = 'SRM_PO_ISSUED')");
        long noAddr = accounts.query(comp, "select min(outbox_id) from sys_outbox where topic = 'SRM_PO_ISSUED'", Long.class);
        grid(NOTICE, me, "sort", "createdAt,asc").andExpect(jsonPath("$.list[?(@.outboxId == " + noAddr + ")].toAddrText").value(org.hamcrest.Matchers.contains("")));
        mvc.perform(get(NOTICE + "/columns").with(me)).andExpect(jsonPath("$.list[*].field").value(hasItems("toAddrText")));
        assertThat(col(grid(NOTICE, me, "size", "50"), "outboxId")).containsExactlyElementsOf(listed(NOTICE, "outboxId"));
        grid(NOTICE, me, "status", "FAIL").andExpect(jsonPath("$.total").value(0));
        grid(NOTICE, me, "status", "NOPE").andExpect(status().isBadRequest());
        rejects(NOTICE, "poNo", "createdAt", "custNm", "status");
        mvc.perform(get(NOTICE + "/columns").with(me)).andExpect(jsonPath("$.list[*].field").value(hasItems("createdAt", "poNo", "status", "attempts")));
    }

    @Test
    void 진행현황_요구납기정렬_보기_임박일수_늦음_계산칸() throws Exception {
        long p5 = issuedPo("V100", 5);
        issuedPo("V200", 2);
        long p9 = issuedPo("V300", 9);
        // V100 발주: 요구 납기 +5 인데 +8 로 약속 → 늦음 3일
        long l5 = accounts.query(comp, "select po_line_id from srm_po_line where po_id = ?", Long.class, p5);
        postAs(me, "/api/srm/acks/pos/" + p5, "{\"rev\":0,\"lines\":[{\"poLineId\":" + l5 + ",\"promisedDate\":\"" + today.plusDays(8)
                + "\",\"promisedQty\":\"10\"}]}").andExpect(status().isOk());
        // 기본 정렬 = 요구 납기 오름차순 → 2개씩: +2·+5 / +9
        grid(STATUS, me, "size", "2").andExpect(jsonPath("$.total").value(3));
        assertThat(col(grid(STATUS, me, "size", "2", "page", "1"), "dueInDays")).containsExactly(2, 5);
        assertThat(col(grid(STATUS, me, "size", "2", "page", "2"), "dueInDays")).containsExactly(9);
        assertThat(col(grid(STATUS, me, "size", "50"), "poLineId")).containsExactlyElementsOf(listed(STATUS, "poLineId"));
        // 계산 칸·보기: 늦음 = V100 하나(3일), 임박 3일 = +2 하나, 임박 6일 = +2·+5
        grid(STATUS, me, "view", "LATE").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.list[0].poId").value(p5))
                .andExpect(jsonPath("$.list[0].lateDays").value(3)).andExpect(jsonPath("$.list[0].ackStatus").value("ACCEPTED"));
        grid(STATUS, me, "view", "DUE_SOON", "soonDays", "3").andExpect(jsonPath("$.total").value(1));
        grid(STATUS, me, "view", "DUE_SOON", "soonDays", "6").andExpect(jsonPath("$.total").value(2));
        grid(STATUS, me, "view", "NO_ACK").andExpect(jsonPath("$.total").value(0));
        grid(STATUS, me, "custCd", "V300").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.list[0].poId").value(p9));
        // 늦은 일수 내림차순 — 약속 없는 행(늦은 일수 없음)은 끝에
        assertThat(col(grid(STATUS, me, "sort", "lateDays,desc", "sort", "dueDate,asc"), "dueInDays")).containsExactly(5, 2, 9);
        grid(STATUS, me, "view", "NOPE").andExpect(status().isBadRequest());
        grid(STATUS, me, "soonDays", "61").andExpect(status().isBadRequest());
        rejects(STATUS, "poNo", "dueDate", "custNm", "lateDays");
        mvc.perform(get(STATUS + "/columns").with(me)).andExpect(jsonPath("$.list[*].field").value(hasItems("poNo", "dueDate", "lateDays", "ackStatus")));
    }

    @Test
    void 권한없으면_403_B사는_A사_행을_못본다() throws Exception {
        issuedPo("V100", 5);
        String other = accounts.newComp("SO");
        RequestPostProcessor b = buyer(other, "buyer1");
        for (String api : List.of(NOTICE, STATUS)) {
            grid(api, me).andExpect(jsonPath("$.total").value(1));
            grid(api, b).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.list.length()").value(0));
        }
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "OTHER", "srm-pr", "SEARCH");
        RequestPostProcessor n = accounts.as(comp, none, "none1");
        for (String api : List.of(NOTICE, STATUS)) {
            grid(api, n).andExpect(status().isForbidden());
            mvc.perform(get(api + "/columns").with(n)).andExpect(status().isForbidden());
        }
    }
}
