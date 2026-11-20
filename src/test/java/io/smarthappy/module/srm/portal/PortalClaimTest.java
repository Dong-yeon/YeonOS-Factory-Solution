package io.smarthappy.module.srm.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 협력사 포털 P-4 클레임 회신 (docs/31 4.15): 보냄 상태만 회신(원인·대책 글), 경로 PORTAL·넣은 사람 기록, 우리 쪽 대신 입력은 MANUAL.
 * 이상 사용: 같은 요청 반복 409 · 회신 받음·종결·취소 409, 작성 중은 안 보임(404) · A 협력사로 B 협력사 404 · A사로 B사 404 ·
 * 포털 사용자 아님 403 · CSRF 403 · 글자 수 넘침·빈 칸 400 · 거래 정지여도 회신은 됨.
 */
@AutoConfigureMockMvc
class PortalClaimTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String API = "/api/portal/srm/claims";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    PasswordEncoder encoder;

    String comp;
    RequestPostProcessor buyer;
    RequestPostProcessor supA;
    RequestPostProcessor supB;
    long custA;
    long custB;
    String from;
    String to;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PK");
        long u = accounts.newUser(comp, "buyer1", PW);
        accounts.grant(comp, u, "PUR", "srm-claim", "SEARCH", "SAVE", "APPROVE");
        buyer = accounts.as(comp, u, "buyer1");
        seedItem(comp);
        custA = supplierCust(comp, "V100");
        custB = supplierCust(comp, "V200");
        supA = supplier(comp, "supa", custA);
        supB = supplier(comp, "supb", custB);
        from = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " - 30)::text", String.class);
        to = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + ")::text", String.class);
    }

    private void seedItem(String c) {
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't')""", c);
    }

    private long supplierCust(String c, String cd) {
        long id = accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, ?, ?, 'PURCHASE', 't', 't') returning cust_id""", Long.class, c, cd, cd + " 상사");
        accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, c);
        return id;
    }

    private RequestPostProcessor supplier(String c, String login, long custId) {
        long id = accounts.query(c, """
                insert into sys_user (comp_cd, login_id, user_nm, pwd_hash, user_type, cust_id, created_by, updated_by)
                values (?, ?, ?, ?, 'SUPPLIER', ?, 't', 't') returning user_id""", Long.class, c, login, login, encoder.encode(PW), custId);
        return SecurityMockMvcRequestPostProcessors.authentication(UsernamePasswordAuthenticationToken.authenticated(
                new LoginUser(id, c, login, login, "ko", false, LoginUser.SUPPLIER, custId), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    /** 클레임(근거 NCR 1) — sent 면 보냄으로 */
    private long claim(String c, long custId, String no, long ncrId, boolean sent) {
        long id = accounts.query(c, """
                insert into srm_claim (comp_cd, claim_no, cust_id, action_cd, title, request_text, reply_due, created_by, updated_by)
                values (?, ?, ?, 'CAPA', '녹 발생', '원인·대책을 회신해 주세요', current_date + 7, 't', 't') returning claim_id""", Long.class, c, no, custId);
        accounts.exec(c, """
                insert into srm_claim_ncr (comp_cd, claim_id, ncr_id, ncr_no, item_id, nc_qty, unit_cd, created_by, updated_by)
                select ?, ?, ?, ?, item_id, 5, 'EA', 't', 't' from mst_item where item_cd = 'BOLT'""", c, id, ncrId, "NCR-" + ncrId);
        if (sent) {
            accounts.exec(c, "update srm_claim set status = 'SENT', sent_at = now(), sent_by = 't' where claim_id = ?", id);
        }
        return id;
    }

    private int ver(long id) {
        return accounts.query(comp, "select row_ver from srm_claim where claim_id = ?", Integer.class, id);
    }

    private ResultActions answer(RequestPostProcessor who, long id, Integer rowVer, String cause, String action) throws Exception {
        String body = "{\"rowVer\":" + rowVer + ",\"answerCause\":" + (cause == null ? "null" : "\"" + cause + "\"") + ",\"answerAction\":"
                + (action == null ? "null" : "\"" + action + "\"") + "}";
        return mvc.perform(post(API + "/" + id + "/answer").with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String row(long id) {
        return accounts.query(comp, "select status || ':' || coalesce(answer_source, '-') || ':' || coalesce(answer_cause, '-') from srm_claim where claim_id = ?",
                String.class, id);
    }

    @Test
    void 보낸_클레임만_보이고_회신하면_PORTAL_로_기록_반려되면_다시_회신() throws Exception {
        long draft = claim(comp, custA, "CL-D", 1, false);
        long k = claim(comp, custA, "CL-1", 2, true);
        String list = mvc.perform(get(API + "?from=" + from + "&to=" + to).with(supA)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<Integer>) JsonPath.read(list, "$.data[*].claimId")).containsExactly((int) k);
        mvc.perform(get(API + "/" + draft).with(supA)).andExpect(status().isNotFound());
        mvc.perform(get(API + "/" + k).with(supA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.claim.requestText").value("원인·대책을 회신해 주세요"))
                .andExpect(jsonPath("$.data.ncrs[0].ncrNo").value("NCR-2"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("sentBy"))));
        answer(supA, k, ver(k), "도금 불량", "도금 조건 변경").andExpect(status().isOk());
        assertThat(row(k)).isEqualTo("ANSWERED:PORTAL:도금 불량");
        assertThat(accounts.query(comp, "select answered_by || ':' || (answered_on = " + TestAccounts.COMP_TODAY + ") from srm_claim where claim_id = ?",
                String.class, k)).contains("supa").endsWith(":true");
        // 내부 화면에서 경로가 보인다
        mvc.perform(get("/api/srm/claims/" + k).with(buyer)).andExpect(jsonPath("$.data.claim.answerSource").value("PORTAL"));
        // 같은 요청 반복 → 409(멱등 아님 — 이미 회신 받음), 내용 그대로
        answer(supA, k, ver(k) - 1, "다른 원인", "다른 대책").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SRM_STATE"));
        answer(supA, k, ver(k), "다른 원인", "다른 대책").andExpect(status().isConflict());
        assertThat(row(k)).isEqualTo("ANSWERED:PORTAL:도금 불량");
        // 우리 쪽 반려 → 보냄 → 다시 회신 가능
        mvc.perform(post("/api/srm/claims/" + k + "/reject").with(csrf()).with(buyer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowVer\":" + ver(k) + "}")).andExpect(status().isOk());
        answer(supA, k, ver(k), "열처리", "온도 관리").andExpect(status().isOk());
        assertThat(row(k)).isEqualTo("ANSWERED:PORTAL:열처리");
    }

    @Test
    void 대신_입력은_MANUAL_종결_취소_클레임은_409() throws Exception {
        long k = claim(comp, custA, "CL-1", 3, true);
        mvc.perform(post("/api/srm/claims/" + k + "/answer").with(csrf()).with(buyer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowVer\":" + ver(k) + ",\"answerCause\":\"전화로 받음\",\"answerAction\":\"교육\",\"answeredOn\":\"" + to + "\"}"))
                .andExpect(status().isOk());
        assertThat(row(k)).isEqualTo("ANSWERED:MANUAL:전화로 받음");
        mvc.perform(get(API + "/" + k).with(supA)).andExpect(jsonPath("$.data.claim.answerSource").value("MANUAL"));
        answer(supA, k, ver(k), "x", "y").andExpect(status().isConflict());                 // 회신 받음
        mvc.perform(post("/api/srm/claims/" + k + "/close").with(csrf()).with(buyer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowVer\":" + ver(k) + "}")).andExpect(status().isOk());
        answer(supA, k, ver(k), "x", "y").andExpect(status().isConflict());                 // 종결
        long c = claim(comp, custA, "CL-2", 4, true);
        accounts.exec(comp, "update srm_claim set status = 'CANCELLED', cancel_reason = 'x' where claim_id = ?", c);
        answer(supA, c, ver(c), "x", "y").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SRM_STATE"));
        assertThat(row(c)).isEqualTo("CANCELLED:-:-");
    }

    @Test
    void A협력사로_B협력사_A사로_B사_404_포털_아님_403_CSRF_403() throws Exception {
        long k = claim(comp, custA, "CL-1", 5, true);
        String l = mvc.perform(get(API + "?from=" + from + "&to=" + to).with(supB)).andReturn().getResponse().getContentAsString();
        assertThat(l).doesNotContain("\"claimId\":" + k);
        mvc.perform(get(API + "/" + k).with(supB)).andExpect(status().isNotFound());
        answer(supB, k, ver(k), "x", "y").andExpect(status().isNotFound());
        // 다른 회사 협력사
        String other = accounts.newComp("PL");
        seedItem(other);
        long oc = supplierCust(other, "V100");
        RequestPostProcessor o = supplier(other, "supa", oc);
        mvc.perform(get(API + "/" + k).with(o)).andExpect(status().isNotFound());
        answer(o, k, ver(k), "x", "y").andExpect(status().isNotFound());
        // 회사 사용자 → 포털 API 403, CSRF 없음 → 403
        answer(buyer, k, ver(k), "x", "y").andExpect(status().isForbidden());
        mvc.perform(post(API + "/" + k + "/answer").with(supA).contentType(MediaType.APPLICATION_JSON).content("{\"rowVer\":0}"))
                .andExpect(status().isForbidden());
        assertThat(row(k)).isEqualTo("SENT:-:-");
    }

    @Test
    void 글자_수_넘침_빈_칸_행_버전_없음은_400() throws Exception {
        long k = claim(comp, custA, "CL-1", 6, true);
        answer(supA, k, ver(k), "가".repeat(2001), "대책").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("answerCause"));
        answer(supA, k, ver(k), " ", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(Matchers.containsInAnyOrder("answerCause", "answerAction")));
        answer(supA, k, null, "a", "b").andExpect(status().isBadRequest());
        assertThat(row(k)).isEqualTo("SENT:-:-");
        answer(supA, k, ver(k), "가".repeat(2000), "대책").andExpect(status().isOk());       // 한도 끝(2000자)은 받는다
        assertThat(row(k)).startsWith("ANSWERED:PORTAL:");
    }

    /** 정책(docs/31 4.15, 상위 승인): 정지를 풀려면 품질 문제를 해결해야 하므로 클레임 회신은 막지 않는다 — 새 납기 회신·ASN 은 409 */
    @Test
    void 거래_정지_협력사도_클레임_회신은_200() throws Exception {
        long k = claim(comp, custA, "CL-B", 7, true);
        accounts.exec(comp, "update srm_supplier set trade_status = 'BLOCKED', status_reason = '품질' where cust_id = ?", custA);
        mvc.perform(get(API + "/" + k).with(supA)).andExpect(status().isOk());
        answer(supA, k, ver(k), "도금 불량", "도금 조건 변경").andExpect(status().isOk());
        assertThat(row(k)).isEqualTo("ANSWERED:PORTAL:도금 불량");
    }

    @Test
    void 화면과_메뉴() throws Exception {
        mvc.perform(get("/portal/claim").with(supA)).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("portal-claim.js")))
                .andExpect(content().string(Matchers.containsString("href=\"/portal/claim\"")));
        mvc.perform(get("/portal/claim").with(buyer)).andExpect(status().isForbidden());
    }
}
