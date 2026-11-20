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
 * UI 단계 3 ① 2묶음 (docs/31 5.2) — 납기 회신·평가 템플릿·협력사 평가·협력사 정보 grid API: 목록과 같은 조건, 서버 페이징 경계·전체 건수,
 * 정렬 허용 목록(모르는 열·4개 이상·쪽 크기 400), 계산 칸(회신 기한 지남·인증 판정)이 grid 에도, 열 정의, 권한(SEARCH), A사로 B사.
 */
@AutoConfigureMockMvc
class SrmGrid2Test extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String PO = "/api/srm/pos";
    private static final String ACK = "/api/srm/acks/pos";
    private static final String TMPL = "/api/srm/eval-tmpls";
    private static final String EVAL = "/api/srm/evals";
    private static final String SUP = "/api/srm/suppliers";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor me;
    LocalDate today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("SJ");
        me = buyer(comp, "buyer1");
        seed(comp);
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    private RequestPostProcessor buyer(String c, String login) {
        long u = accounts.newUser(c, login, PW);
        for (String p : List.of("srm-po", "srm-ack", "srm-eval-tmpl", "srm-eval", "srm-supplier")) {
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
                    insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                    values (?, ?, ? || ' 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, c, v, v);
            accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, c);
        }
        // 아직 협력사로 등록하지 않은 구매 거래처 하나
        accounts.exec(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'P900', 'P900 상사', 'PURCHASE', 't', 't')""", c);
    }

    private ResultActions postAs(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data")).longValue();
    }

    private int ver(String url, String path) throws Exception {
        return JsonPath.read(mvc.perform(get(url).with(me)).andReturn().getResponse().getContentAsString(), path);
    }

    private ResultActions grid(String api, boolean range, RequestPostProcessor who, String... params) throws Exception {
        MockHttpServletRequestBuilder b = get(api + "/grid");
        if (range) {
            b = b.param("from", today.minusDays(30).toString()).param("to", today.toString());
        }
        for (int i = 0; i < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return mvc.perform(b.with(who));
    }

    private static List<Object> col(ResultActions r, String field) throws Exception {
        return JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.list[*]." + field);
    }

    private List<Object> listed(String api, boolean range, String field, String... params) throws Exception {
        MockHttpServletRequestBuilder b = get(api);
        if (range) {
            b = b.param("from", today.minusDays(30).toString()).param("to", today.toString());
        }
        for (int i = 0; i < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return JsonPath.read(mvc.perform(b.with(me)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data[*]." + field);
    }

    /** 정렬 허용 목록 밖·SQL 조각·방향·4개·쪽 크기·쪽 번호 → 400 */
    private void rejects(String api, boolean range, String a, String b, String c, String d) throws Exception {
        grid(api, range, me, "sort", "updatedBy,asc").andExpect(status().isBadRequest());
        grid(api, range, me, "sort", "comp_cd;drop table x,asc").andExpect(status().isBadRequest());
        grid(api, range, me, "sort", a + ",up").andExpect(status().isBadRequest());
        grid(api, range, me, "sort", a + ",asc", "sort", a + ",desc").andExpect(status().isBadRequest());
        grid(api, range, me, "sort", a + ",asc", "sort", b + ",asc", "sort", c + ",asc", "sort", d + ",asc").andExpect(status().isBadRequest());
        grid(api, range, me, "size", "100000").andExpect(status().isBadRequest());
        grid(api, range, me, "page", "0").andExpect(status().isBadRequest());
        if (range) {
            mvc.perform(get(api + "/grid").with(me)).andExpect(status().isBadRequest());
        }
    }

    private long issuedPo(String cust) throws Exception {
        long po = id(postAs(me, PO, "{\"custCd\":\"" + cust + "\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"10\",\"unitPrice\":\"1\",\"dueDate\":\""
                + today.plusDays(10) + "\"}]}"));
        for (String a : List.of("confirm", "issue")) {
            postAs(me, PO + "/" + po + "/" + a, "{\"rowVer\":" + ver(PO + "/" + po, "$.data.po.rowVer") + "}").andExpect(status().isOk());
        }
        return po;
    }

    // ------------------------------------------------------------------ 납기 회신

    @Test
    void 납기회신_페이지경계_협력사정렬_회신상태_기한지남() throws Exception {
        long p1 = issuedPo("V200");
        long p2 = issuedPo("V100");
        long p3 = issuedPo("V300");
        // V100 발주는 거절로 기록 → 회신 상태 REJECTED. V300 발주는 발행을 8일 전으로(기한 기본 7일 지남, 회신 없음)
        postAs(me, ACK + "/" + p2, "{\"rev\":0,\"reject\":true,\"rejectReason\":\"ZT 거절\",\"lines\":[]}").andExpect(status().isOk());
        // V200 발주는 그대로 수락(약속 = 요구 납기·수량)
        long l1 = accounts.query(comp, "select po_line_id from srm_po_line where po_id = ?", Long.class, p1);
        postAs(me, ACK + "/" + p1, "{\"rev\":0,\"lines\":[{\"poLineId\":" + l1 + ",\"promisedDate\":\"" + today.plusDays(10)
                + "\",\"promisedQty\":\"10\"}]}").andExpect(status().isOk());
        accounts.exec(comp, "update srm_po set issued_at = now() - interval '8 days' where po_id = ?", p3);
        // 협력사 이름 오름차순 2개씩: V100·V200 / V300
        grid(ACK, true, me, "size", "2").andExpect(jsonPath("$.total").value(3));
        assertThat(col(grid(ACK, true, me, "size", "2", "page", "1", "sort", "custNm,asc"), "poId")).containsExactly((int) p2, (int) p1);
        assertThat(col(grid(ACK, true, me, "size", "2", "page", "2", "sort", "custNm,asc"), "poId")).containsExactly((int) p3);
        // 계산 칸이 grid 에도: 회신 상태·기한 지남
        grid(ACK, true, me, "sort", "custNm,asc").andExpect(jsonPath("$.list[0].ackStatus").value("REJECTED"))
                .andExpect(jsonPath("$.list[2].overdue").value(true)).andExpect(jsonPath("$.list[1].overdue").value(false));
        // 같은 조건: 회신 없음·거절 2건(V100 거절 + V300 없음)·회신 받음 1건(V200), 기본 정렬 = 목록 API 순서
        grid(ACK, true, me, "state", "NO_ACK").andExpect(jsonPath("$.total").value(2));
        grid(ACK, true, me, "state", "ACKED").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.list[0].poId").value(p1))
                .andExpect(jsonPath("$.list[0].ackStatus").value("ACCEPTED"));
        assertThat(col(grid(ACK, true, me, "size", "50"), "poId")).containsExactlyElementsOf(listed(ACK, true, "poId"));
        grid(ACK, true, me, "state", "NOPE").andExpect(status().isBadRequest());
        rejects(ACK, true, "poNo", "custNm", "ackDueDate", "status");
        mvc.perform(get(ACK + "/columns").with(me)).andExpect(jsonPath("$.list[*].field").value(hasItems("poNo", "ackStatus", "ackDueDate")));
    }

    // ------------------------------------------------------------------ 평가 템플릿 · 평가

    private long template(String cd) throws Exception {
        return id(postAs(me, TMPL, "{\"tmplCd\":\"" + cd + "\",\"tmplNm\":\"" + cd + " 평가\",\"validFrom\":\"2020-01-01\",\"items\":"
                + "[{\"sectionNm\":\"품질\",\"itemNm\":\"불량 대응\",\"maxScore\":\"10\",\"weight\":\"100\"}],"
                + "\"grades\":[{\"gradeCd\":\"A\",\"minScore\":\"90\"},{\"gradeCd\":\"D\",\"minScore\":\"0\"}]}"));
    }

    @Test
    void 평가템플릿_코드정렬_페이지경계_상태조건() throws Exception {
        for (String cd : List.of("T3", "T1", "T2")) {
            template(cd);
        }
        long t1 = accounts.query(comp, "select tmpl_id from srm_eval_tmpl where tmpl_cd = 'T1'", Long.class);
        postAs(me, TMPL + "/" + t1 + "/release", "{\"rowVer\":" + ver(TMPL + "/" + t1, "$.data.tmpl.rowVer") + "}").andExpect(status().isOk());
        grid(TMPL, false, me, "size", "2").andExpect(jsonPath("$.total").value(3));
        assertThat(col(grid(TMPL, false, me, "size", "2", "page", "1"), "tmplCd")).containsExactly("T1", "T2");
        assertThat(col(grid(TMPL, false, me, "size", "2", "page", "2"), "tmplCd")).containsExactly("T3");
        assertThat(col(grid(TMPL, false, me, "sort", "tmplCd,desc"), "tmplCd")).containsExactly("T3", "T2", "T1");
        grid(TMPL, false, me, "status", "RELEASED").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.list[0].tmplCd").value("T1"));
        assertThat(col(grid(TMPL, false, me, "size", "50"), "tmplId")).containsExactlyElementsOf(listed(TMPL, false, "tmplId"));
        rejects(TMPL, false, "tmplCd", "tmplNm", "status", "validFrom");
        mvc.perform(get(TMPL + "/columns").with(me)).andExpect(jsonPath("$.list[*].field").value(hasItems("tmplCd", "rev", "status", "weightSum")));
    }

    @Test
    void 협력사평가_기간끝_정렬_페이지경계_목록과_같은조건() throws Exception {
        long t = template("SQ");
        postAs(me, TMPL + "/" + t + "/release", "{\"rowVer\":" + ver(TMPL + "/" + t, "$.data.tmpl.rowVer") + "}").andExpect(status().isOk());
        // 기간 끝 −3(V100)·−1(V200)·−2(V300)
        int[] ends = {3, 1, 2};
        String[] custs = {"V100", "V200", "V300"};
        for (int i = 0; i < 3; i++) {
            postAs(me, EVAL, "{\"custCd\":\"" + custs[i] + "\",\"tmplCd\":\"SQ\",\"periodFrom\":\"" + today.minusDays(20) + "\",\"periodTo\":\""
                    + today.minusDays(ends[i]) + "\"}").andExpect(status().isOk());
        }
        grid(EVAL, true, me, "size", "2").andExpect(jsonPath("$.total").value(3));
        assertThat(col(grid(EVAL, true, me, "size", "2", "page", "1", "sort", "periodTo,asc"), "custCd")).containsExactly("V100", "V300");
        assertThat(col(grid(EVAL, true, me, "size", "2", "page", "2", "sort", "periodTo,asc"), "custCd")).containsExactly("V200");
        assertThat(col(grid(EVAL, true, me, "size", "50"), "evalId")).containsExactlyElementsOf(listed(EVAL, true, "evalId"));
        grid(EVAL, true, me, "status", "CONFIRMED").andExpect(jsonPath("$.total").value(0));
        grid(EVAL, true, me, "keyword", "V300").andExpect(jsonPath("$.total").value(1));
        rejects(EVAL, true, "evalNo", "custNm", "periodTo", "status");
        mvc.perform(get(EVAL + "/columns").with(me)).andExpect(jsonPath("$.list[*].field").value(hasItems("evalNo", "status", "totalScore", "gradeCd")));
    }

    // ------------------------------------------------------------------ 협력사 정보

    @Test
    void 협력사_등록조건_정렬_인증판정이_grid에도() throws Exception {
        // 등록 3 + 미등록 1 = 4. 코드 내림차순 2개씩: V300·V200 / V100·P900
        grid(SUP, false, me, "size", "2").andExpect(jsonPath("$.total").value(4));
        assertThat(col(grid(SUP, false, me, "size", "2", "page", "1", "sort", "custCd,desc"), "custCd")).containsExactly("V300", "V200");
        assertThat(col(grid(SUP, false, me, "size", "2", "page", "2", "sort", "custCd,desc"), "custCd")).containsExactly("V100", "P900");
        grid(SUP, false, me, "registered", "N").andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.list[0].custCd").value("P900"))
                .andExpect(jsonPath("$.list[0].registered").value(false));
        // 인증 판정은 자바 계산 — 등록 협력사는 필수 인증 없음(NONE), 미등록은 빈 값
        grid(SUP, false, me, "registered", "Y", "sort", "custCd,asc").andExpect(jsonPath("$.list[0].certState").value("NONE"));
        // 거래 상태 정렬: 미등록(거래 상태 없음)은 끝에
        assertThat(col(grid(SUP, false, me, "sort", "tradeStatus,asc", "sort", "custCd,asc"), "custCd")).containsExactly("V100", "V200", "V300", "P900");
        assertThat(col(grid(SUP, false, me, "size", "50"), "custCd")).containsExactlyElementsOf(listed(SUP, false, "custCd"));
        grid(SUP, false, me, "registered", "X").andExpect(status().isBadRequest());
        rejects(SUP, false, "custCd", "custNm", "tradeStatus", "buyerNm");
        // 인증 판정은 정렬 허용 목록 밖
        grid(SUP, false, me, "sort", "certState,asc").andExpect(status().isBadRequest());
        mvc.perform(get(SUP + "/columns").with(me)).andExpect(jsonPath("$.list[*].field").value(hasItems("custCd", "tradeStatus", "certState")));
    }

    // ------------------------------------------------------------------ 권한 · A사로 B사

    @Test
    void 권한없으면_403_B사는_A사_행을_못본다() throws Exception {
        issuedPo("V100");
        template("SQ");
        String other = accounts.newComp("SK");
        RequestPostProcessor b = buyer(other, "buyer1");
        grid(ACK, true, me).andExpect(jsonPath("$.total").value(1));
        grid(TMPL, false, me).andExpect(jsonPath("$.total").value(1));
        grid(SUP, false, me).andExpect(jsonPath("$.total").value(4));
        for (String[] e : List.of(new String[] {ACK, "Y"}, new String[] {TMPL, "N"}, new String[] {EVAL, "Y"}, new String[] {SUP, "N"})) {
            grid(e[0], "Y".equals(e[1]), b).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.list.length()").value(0));
        }
        // SEARCH 없는 사용자(다른 권한 그룹으로 다른 화면 권한만) → grid·columns 403
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "OTHER", "srm-pr", "SEARCH");
        RequestPostProcessor n = accounts.as(comp, none, "none1");
        for (String[] e : List.of(new String[] {ACK, "Y"}, new String[] {TMPL, "N"}, new String[] {EVAL, "Y"}, new String[] {SUP, "N"})) {
            grid(e[0], "Y".equals(e[1]), n).andExpect(status().isForbidden());
            mvc.perform(get(e[0] + "/columns").with(n)).andExpect(status().isForbidden());
        }
    }
}
