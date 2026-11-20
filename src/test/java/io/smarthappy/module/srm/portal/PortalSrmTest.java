package io.smarthappy.module.srm.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 협력사 포털 SRM 업무 (docs/42 7장 3, docs/31 4.13 P-1·P-2): 발주 보기·납기 회신, ASN 입력·확정·취소, 납품서 인쇄 화면.
 * 이상 사용: A 협력사로 B 협력사(같은 회사 다른 협력사 id = 404, 목록에 없음) · A사로 B사(다른 회사 = RLS 404) · 경계(회사 사용자 ↔ 포털,
 * 협력사 → 내부 API) · 거래 정지(조회만) · 거래 회사가 넣은 ASN 은 보기만 · 남의 발주 행을 내 ASN 에 · 한도 · CSRF.
 */
@AutoConfigureMockMvc
class PortalSrmTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String API = "/api/portal/srm";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    PasswordEncoder encoder;

    String comp;
    RequestPostProcessor admin;
    RequestPostProcessor supA;
    RequestPostProcessor supB;
    long custA;
    long custB;
    long poA;
    long poB;
    long lineA1;
    long lineA2;
    long lineB1;
    String from;
    String to;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PS");
        long u = accounts.newUser(comp, "buyer1", PW);
        accounts.grant(comp, u, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, u, "PUR", "srm-asn", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, u, "PUR", "srm-ack", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "buyer1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", comp);
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't'), (?, 'NUT', '너트', 'RM', 'BUY', 'EA', 'N', 't', 't')""", comp, comp);
        custA = supplierCust(comp, "V100", "가람부품");
        custB = supplierCust(comp, "V200", "나래상사");
        poA = issued("V100", "BOLT", "100", "NUT", "50");
        poB = issued("V200", "BOLT", "30", null, null);
        lineA1 = line(poA, 1);
        lineA2 = line(poA, 2);
        lineB1 = line(poB, 1);
        supA = supplier(comp, "supa", custA);
        supB = supplier(comp, "supb", custB);
        from = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " - 30)::text", String.class);
        to = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " + 30)::text", String.class);
    }

    private long supplierCust(String c, String cd, String nm) {
        long id = accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, ?, ?, 'PURCHASE', 't', 't') returning cust_id""", Long.class, c, cd, nm);
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

    private ResultActions send(RequestPostProcessor who, MockHttpServletRequestBuilder b, String json) throws Exception {
        return mvc.perform(b.with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String body(RequestPostProcessor who, String url) throws Exception {
        return mvc.perform(get(url).with(who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private long issued(String cust, String item1, String qty1, String item2, String qty2) throws Exception {
        String due = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " + 20)::text", String.class);
        String lines = "{\"itemCd\":\"" + item1 + "\",\"qty\":\"" + qty1 + "\",\"unitPrice\":\"10\",\"dueDate\":\"" + due + "\"}"
                + (item2 == null ? "" : ",{\"itemCd\":\"" + item2 + "\",\"qty\":\"" + qty2 + "\",\"unitPrice\":\"5\",\"dueDate\":\"" + due + "\"}");
        String res = send(admin, post("/api/srm/pos"), "{\"custCd\":\"" + cust + "\",\"plantCd\":\"P1\",\"lines\":[" + lines + "]}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(res, "$.data")).longValue();
        for (String act : new String[] {"confirm", "issue"}) {
            int rv = JsonPath.read(body(admin, "/api/srm/pos/" + id), "$.data.po.rowVer");
            send(admin, post("/api/srm/pos/" + id + "/" + act), "{\"rowVer\":" + rv + "}").andExpect(status().isOk());
        }
        return id;
    }

    private long line(long po, int no) {
        return accounts.query(comp, "select po_line_id from srm_po_line where po_id = ? and line_no = ?", Long.class, po, no);
    }

    private String today(int plus) {
        return accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " + " + plus + ")::text", String.class);
    }

    private String asnForm(String lines, Integer rowVer) {
        return "{\"plantCd\":\"P1\",\"shipDate\":\"" + today(0) + "\",\"etaDate\":\"" + today(2) + "\",\"supplierRef\":\"DN-1\",\"rowVer\":" + rowVer
                + ",\"lines\":[" + lines + "]}";
    }

    private static String asnLine(long poLine, String qty, String lot) {
        return "{\"poLineId\":" + poLine + ",\"qty\":\"" + qty + "\",\"supplierLotNo\":" + (lot == null ? "null" : "\"" + lot + "\"") + "}";
    }

    private int asnVer(RequestPostProcessor who, long id) throws Exception {
        return JsonPath.read(body(who, API + "/asns/" + id), "$.data.asn.rowVer");
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void 내_발행된_발주만_보이고_회신은_출처_PORTAL_나눠_약속_뒤_거절로_다시() throws Exception {
        String list = body(supA, API + "/pos?from=" + from + "&to=" + to);
        assertThat((List<Integer>) JsonPath.read(list, "$.data[*].poId")).containsExactly((int) poA);
        String d = body(supA, API + "/pos/" + poA);
        assertThat((Integer) JsonPath.read(d, "$.data.lines.length()")).isEqualTo(2);
        assertThat((String) JsonPath.read(d, "$.data.lines[0].openQty")).isEqualTo("100");
        assertThat((Boolean) JsonPath.read(d, "$.data.po.ackable")).isTrue();
        assertThat(d).doesNotContain("createdBy").doesNotContain("buyer").doesNotContain("erpPoNo");      // 내부 칸 없음
        // 볼트 60 + 40 나눠, 너트 50 → 전부 약속(수락)
        String due1 = today(10);
        String due2 = today(15);
        send(supA, post(API + "/pos/" + poA + "/ack"), "{\"rev\":0,\"ackByNm\":\"김납품\",\"lines\":["
                + "{\"poLineId\":" + lineA1 + ",\"promisedDate\":\"" + due1 + "\",\"promisedQty\":\"60\"},"
                + "{\"poLineId\":" + lineA1 + ",\"promisedDate\":\"" + due2 + "\",\"promisedQty\":\"40\"},"
                + "{\"poLineId\":" + lineA2 + ",\"promisedDate\":\"" + due1 + "\",\"promisedQty\":\"50\"}]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ackSeq").value(1)).andExpect(jsonPath("$.data.ackStatus").value("ACCEPTED"));
        assertThat(accounts.query(comp, "select source || ':' || ack_status || ':' || created_by from srm_po_ack where po_id = ?", String.class, poA))
                .startsWith("PORTAL:ACCEPTED:");
        assertThat(accounts.query(comp, "select status from srm_po where po_id = ?", String.class, poA)).isEqualTo("ACKED");
        d = body(supA, API + "/pos/" + poA);
        assertThat((String) JsonPath.read(d, "$.data.lines[0].promisedQty")).isEqualTo("100");
        assertThat((String) JsonPath.read(d, "$.data.acks[0].source")).isEqualTo("PORTAL");
        // 내부 화면(납기 회신)에서도 보인다
        assertThat(body(admin, "/api/srm/acks/pos/" + poA)).contains("\"source\":\"PORTAL\"");
        // 다시 회신 = 거절(사유 필수) → 새 순번
        send(supA, post(API + "/pos/" + poA + "/ack"), "{\"rev\":0,\"reject\":true}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("rejectReason"));
        send(supA, post(API + "/pos/" + poA + "/ack"), "{\"rev\":0,\"reject\":true,\"rejectReason\":\"원자재 부족\",\"lines\":[]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ackSeq").value(2)).andExpect(jsonPath("$.data.ackStatus").value("REJECTED"));
        // 낡은 개정 → 409
        send(supA, post(API + "/pos/" + poA + "/ack"), "{\"rev\":5,\"reject\":true,\"rejectReason\":\"x\"}").andExpect(status().isConflict());
    }

    @Test
    void ASN_만들고_고치고_확정_납품서_인쇄_취소() throws Exception {
        assertThat(body(supA, API + "/asn-plants")).contains("\"plantCd\":\"P1\"");
        String opts = body(supA, API + "/asn-po-lines?plantCd=P1");
        assertThat((List<Integer>) JsonPath.read(opts, "$.data[*].poLineId")).containsExactlyInAnyOrder((int) lineA1, (int) lineA2);
        long id = ((Number) JsonPath.read(send(supA, post(API + "/asns"), asnForm(asnLine(lineA1, "30", "L1"), null))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data")).longValue();
        assertThat(accounts.query(comp, "select source || ':' || status || ':' || cust_id from srm_asn where asn_id = ?", String.class, id))
                .isEqualTo("PORTAL:DRAFT:" + custA);
        // 고치기: 볼트 LOT 둘로 + 너트
        send(supA, put(API + "/asns/" + id), asnForm(asnLine(lineA1, "30", "L1") + "," + asnLine(lineA1, "20", "L2") + "," + asnLine(lineA2, "50", null),
                asnVer(supA, id))).andExpect(status().isOk());
        assertThat((Integer) JsonPath.read(body(supA, API + "/asns/" + id), "$.data.lines.length()")).isEqualTo(3);
        send(supA, post(API + "/asns/" + id + "/send"), "{\"rowVer\":" + asnVer(supA, id) + "}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select status from srm_asn where asn_id = ?", String.class, id)).isEqualTo("SENT");
        // 확정 뒤 고치기 → 409
        send(supA, put(API + "/asns/" + id), asnForm(asnLine(lineA1, "1", "L9"), asnVer(supA, id))).andExpect(status().isConflict());
        // 목록·납품서 화면
        assertThat(body(supA, API + "/asns?from=" + from + "&to=" + to)).contains("\"asnId\":" + id);
        mvc.perform(get("/portal/asn-print").param("asnId", String.valueOf(id)).with(supA)).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("portal-asn-print.js")));
        // 내부 ASN 화면에서도 보인다
        assertThat(body(admin, "/api/srm/asns/" + id)).contains("\"source\":\"PORTAL\"");
        // 취소: 사유 필수
        send(supA, post(API + "/asns/" + id + "/cancel"), "{\"rowVer\":" + asnVer(supA, id) + "}").andExpect(status().isBadRequest());
        send(supA, post(API + "/asns/" + id + "/cancel"), "{\"rowVer\":" + asnVer(supA, id) + ",\"reason\":\"차량 고장\"}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select status from srm_asn where asn_id = ?", String.class, id)).isEqualTo("CANCELLED");
    }

    @Test
    void 화면과_메뉴_협력사만() throws Exception {
        mvc.perform(get("/portal/po").with(supA)).andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("href=\"/portal/asn\"")))
                .andExpect(content().string(Matchers.containsString("portal-po.js")))
                .andExpect(content().string(Matchers.containsString("가람부품")));
        mvc.perform(get("/portal/asn").with(supA)).andExpect(status().isOk());
        mvc.perform(get("/portal").with(supA)).andExpect(content().string(Matchers.containsString("href=\"/portal/po\"")));
        // 회사 사용자 → 포털 화면·API 403, 협력사 → 내부 API 403
        mvc.perform(get("/portal/po").with(admin)).andExpect(status().isForbidden());
        mvc.perform(get(API + "/pos?from=" + from + "&to=" + to).with(admin)).andExpect(status().isForbidden());
        mvc.perform(get("/api/srm/pos/" + poA).with(supA)).andExpect(status().isForbidden());
        mvc.perform(get("/api/srm/asns/1").with(supA)).andExpect(status().isForbidden());
        // CSRF 없이 회신 → 403
        mvc.perform(post(API + "/pos/" + poA + "/ack").with(supA).contentType(MediaType.APPLICATION_JSON).content("{\"rev\":0}"))
                .andExpect(status().isForbidden());
    }

    // =====================================================================
    // 이상 사용
    // =====================================================================

    @Test
    void A협력사로_B협력사_발주_ASN_id_는_없는_것_남의_발주_행은_내_ASN_에_못_넣는다() throws Exception {
        assertThat(body(supB, API + "/pos?from=" + from + "&to=" + to)).doesNotContain("\"poId\":" + poA);
        mvc.perform(get(API + "/pos/" + poA).with(supB)).andExpect(status().isNotFound());
        send(supB, post(API + "/pos/" + poA + "/ack"), "{\"rev\":0,\"reject\":true,\"rejectReason\":\"x\"}").andExpect(status().isNotFound());
        assertThat(accounts.query(comp, "select count(*) from srm_po_ack where po_id = ?", Integer.class, poA)).isZero();
        // A 의 ASN
        long id = ((Number) JsonPath.read(send(supA, post(API + "/asns"), asnForm(asnLine(lineA1, "10", null), null))
                .andReturn().getResponse().getContentAsString(), "$.data")).longValue();
        mvc.perform(get(API + "/asns/" + id).with(supB)).andExpect(status().isNotFound());
        mvc.perform(get(API + "/asn-po-lines?plantCd=P1&asnId=" + id).with(supB)).andExpect(status().isNotFound());
        send(supB, put(API + "/asns/" + id), asnForm(asnLine(lineB1, "1", null), 0)).andExpect(status().isNotFound());
        send(supB, post(API + "/asns/" + id + "/send"), "{\"rowVer\":0}").andExpect(status().isNotFound());
        send(supB, post(API + "/asns/" + id + "/cancel"), "{\"rowVer\":0,\"reason\":\"x\"}").andExpect(status().isNotFound());
        assertThat(body(supB, API + "/asns?from=" + from + "&to=" + to)).doesNotContain("\"asnId\":" + id);
        assertThat(body(supB, API + "/asn-po-lines?plantCd=P1")).doesNotContain("\"poLineId\":" + lineA1);
        // B 가 A 의 발주 행으로 자기 ASN → 행 오류, 아무것도 안 생김
        send(supB, post(API + "/asns"), asnForm(asnLine(lineA1, "5", null), null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SRM_OTHER_SUPPLIER"));
        // B 가 A 의 ASN 에 대고 회신·조회한 흔적 없음
        assertThat(accounts.query(comp, "select count(*) from srm_asn where cust_id = ?", Integer.class, custB)).isZero();
        assertThat(accounts.query(comp, "select status || ':' || row_ver from srm_asn where asn_id = ?", String.class, id)).isEqualTo("DRAFT:0");
    }

    @Test
    void A사로_B사_다른_회사_협력사는_RLS_로_없는_것() throws Exception {
        String other = accounts.newComp("PU");
        long otherCust = supplierCust(other, "V100", "다른회사 가람");
        RequestPostProcessor o = supplier(other, "supa", otherCust);
        assertThat(body(o, API + "/pos?from=" + from + "&to=" + to)).doesNotContain("\"poId\":" + poA);
        mvc.perform(get(API + "/pos/" + poA).with(o)).andExpect(status().isNotFound());
        send(o, post(API + "/pos/" + poA + "/ack"), "{\"rev\":0,\"reject\":true,\"rejectReason\":\"x\"}").andExpect(status().isNotFound());
        send(o, post(API + "/asns"), asnForm(asnLine(lineA1, "5", null), null)).andExpect(status().isBadRequest());
        assertThat(accounts.query(comp, "select count(*) from srm_po_ack", Integer.class)).isZero();
    }

    @Test
    void 거래_정지면_조회만_새_회신_ASN_은_409_취소는_된다() throws Exception {
        long id = ((Number) JsonPath.read(send(supA, post(API + "/asns"), asnForm(asnLine(lineA1, "10", null), null))
                .andReturn().getResponse().getContentAsString(), "$.data")).longValue();
        accounts.exec(comp, "update srm_supplier set trade_status = 'BLOCKED', status_reason = '품질 문제' where cust_id = ?", custA);
        assertThat(body(supA, API + "/pos?from=" + from + "&to=" + to)).contains("\"poId\":" + poA);
        body(supA, API + "/pos/" + poA);
        send(supA, post(API + "/pos/" + poA + "/ack"), "{\"rev\":0,\"reject\":true,\"rejectReason\":\"x\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SRM_PORTAL_BLOCKED"));
        send(supA, post(API + "/asns"), asnForm(asnLine(lineA1, "10", null), null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SRM_PORTAL_BLOCKED"));
        send(supA, put(API + "/asns/" + id), asnForm(asnLine(lineA1, "11", null), asnVer(supA, id))).andExpect(status().isConflict());
        send(supA, post(API + "/asns/" + id + "/send"), "{\"rowVer\":" + asnVer(supA, id) + "}").andExpect(status().isConflict());
        send(supA, post(API + "/asns/" + id + "/cancel"), "{\"rowVer\":" + asnVer(supA, id) + ",\"reason\":\"거래 정지\"}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from srm_po_ack", Integer.class)).isZero();
    }

    @Test
    void 거래_회사가_넣은_ASN_은_보기만_한도_넘는_확정은_409() throws Exception {
        // 우리 직원이 대신 넣은 A 의 ASN(MANUAL)
        long internal = ((Number) JsonPath.read(send(admin, post("/api/srm/asns"), "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"shipDate\":\"" + today(0)
                + "\",\"etaDate\":\"" + today(2) + "\",\"lines\":[" + asnLine(lineA2, "5", null) + "]}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data")).longValue();
        assertThat(body(supA, API + "/asns/" + internal)).contains("\"source\":\"MANUAL\"");
        send(supA, put(API + "/asns/" + internal), asnForm(asnLine(lineA2, "6", null), asnVer(supA, internal))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SRM_PORTAL_ASN_INTERNAL"));
        send(supA, post(API + "/asns/" + internal + "/send"), "{\"rowVer\":" + asnVer(supA, internal) + "}").andExpect(status().isConflict());
        send(supA, post(API + "/asns/" + internal + "/cancel"), "{\"rowVer\":" + asnVer(supA, internal) + ",\"reason\":\"x\"}")
                .andExpect(status().isConflict());
        assertThat(accounts.query(comp, "select status from srm_asn where asn_id = ?", String.class, internal)).isEqualTo("DRAFT");
        // 한도: 볼트 100 (+ 초과 허용 0%) 에 150 → 확정 400 행 오류, 그대로
        long id = ((Number) JsonPath.read(send(supA, post(API + "/asns"), asnForm(asnLine(lineA1, "150", null), null))
                .andReturn().getResponse().getContentAsString(), "$.data")).longValue();
        send(supA, post(API + "/asns/" + id + "/send"), "{\"rowVer\":" + asnVer(supA, id) + "}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SRM_ASN_OVER"));
        assertThat(accounts.query(comp, "select status from srm_asn where asn_id = ?", String.class, id)).isEqualTo("DRAFT");
        // 요청 값으로 협력사·출처를 바꾸려 해도(모르는 칸) 로그인 소속·PORTAL
        long id2 = ((Number) JsonPath.read(send(supA, post(API + "/asns"), "{\"custCd\":\"V200\",\"source\":\"MANUAL\",\"plantCd\":\"P1\",\"shipDate\":\""
                + today(0) + "\",\"etaDate\":\"" + today(2) + "\",\"lines\":[" + asnLine(lineA2, "1", "X") + "]}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data")).longValue();
        assertThat(accounts.query(comp, "select cust_id || ':' || source from srm_asn where asn_id = ?", String.class, id2)).isEqualTo(custA + ":PORTAL");
    }
}
