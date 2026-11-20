package io.smarthappy.module.srm.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.qms.link.SupplierQualityQuery;
import io.smarthappy.module.qms.link.SupplierQualityQuery.NcrDetail;
import io.smarthappy.module.qms.link.SupplierQualityQuery.NcrPage;
import io.smarthappy.module.srm.notify.SrmMailbox;
import io.smarthappy.platform.messaging.OutboxRelay;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * SRM 2부 R4d 협력사 클레임 (docs/31 4.12.4, R4-6) — QMS 계약을 바꿔 끼워 SRM 이 계약 값을 어떻게 쓰는지 본다.
 * 근거 NCR 후보(NCR 로 묶기·다른 클레임 번호) → 만들기(금액·통화·NCR 검사·스냅샷) → 수정 → 보내기(통지 아웃박스) → 회신(미래 날짜 막힘) →
 * 반려 → 다시 회신 → 종결(APPROVE) → 고정, NCR 은 살아 있는 클레임 하나, A사로 B사, 권한.
 */
@AutoConfigureMockMvc
@SuppressWarnings("unchecked")
class ClaimTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String C = "/api/srm/claims";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @MockitoBean
    SupplierQualityQuery quality;

    @Autowired
    OutboxRelay relay;

    @Autowired
    SrmMailbox mailbox;

    String comp;
    RequestPostProcessor me;
    LocalDate today;
    OffsetDateTime closedAt;
    long vCust;
    long bolt;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("CL");
        long buyer = accounts.newUser(comp, "buyer1", PW);
        accounts.grant(comp, buyer, "PUR", "srm-claim", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, buyer, "PUR", "srm-notify", "SEARCH", "SAVE");
        me = accounts.as(comp, buyer, "buyer1");
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        closedAt = today.atTime(1, 0).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
        long[] s = seed(comp);
        vCust = s[0];
        bolt = s[1];
        // 101 닫힘(반품 줄 둘 4 + 2) / 104 처리 중 / 105 취소 / 103 다른 협력사
        when(quality.ncr(anyLong())).thenReturn(Optional.empty());
        when(quality.ncr(101L)).thenReturn(Optional.of(ncr(101, "CLOSED", vCust)));
        when(quality.ncr(104L)).thenReturn(Optional.of(ncr(104, "OPEN", vCust)));
        when(quality.ncr(105L)).thenReturn(Optional.of(ncr(105, "CANCELLED", vCust)));
        when(quality.ncr(103L)).thenReturn(Optional.of(ncr(103, "CLOSED", 999_999L)));
        when(quality.ncrsOfSupplier(anyLong(), any(), any())).thenReturn(NcrPage.EMPTY);
        when(quality.ncrsOfSupplier(eq(vCust), any(), any())).thenReturn(new NcrPage(List.of(ncr(101, "CLOSED", vCust), ncr(104, "OPEN", vCust)), false));
    }

    private long[] seed(String c) {
        long b = accounts.query(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't') returning item_id""", Long.class, c);
        long id = accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, email, created_by, updated_by)
                values (?, 'V100', 'V100 상사', 'PURCHASE', 'qa@v100.example', 't', 't') returning cust_id""", Long.class, c);
        accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, c);
        return new long[] {id, b};
    }

    private NcrDetail ncr(long id, String status, long cust) {
        return new NcrDetail(id, "NCR-" + id, status, "INSP", 500, "L500", "SL1", cust, bolt, "BOLT", new BigDecimal("10.5"), BigDecimal.ZERO,
                "EA", "D1", "녹", "녹 발생", "INS-1", "CLOSED".equals(status) ? closedAt : null, null);
    }


    private ResultActions send(RequestPostProcessor who, MockHttpServletRequestBuilder b, String json) throws Exception {
        return mvc.perform(b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json).with(who));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private String body(RequestPostProcessor who, String url) throws Exception {
        return mvc.perform(get(url).with(who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private int ver(long id) throws Exception {
        return JsonPath.read(body(me, C + "/" + id), "$.data.claim.rowVer");
    }

    private static String form(String action, String amt, String currency, String ncrIds) {
        return "{\"custCd\":\"V100\",\"actionCd\":\"" + action + "\",\"title\":\"녹 불량\",\"requestText\":\"원인과 대책 회신 바랍니다\","
                + "\"replyDue\":null,\"chargeAmt\":" + (amt == null ? "null" : "\"" + amt + "\"") + ",\"currencyCd\":"
                + (currency == null ? "null" : "\"" + currency + "\"") + ",\"ncrIds\":" + ncrIds + "}";
    }

    private ResultActions act(RequestPostProcessor who, long id, String action, String reason) throws Exception {
        return send(who, post(C + "/" + id + "/" + action), "{\"rowVer\":" + ver(id) + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}");
    }

    private ResultActions answer(long id, String on) throws Exception {
        return send(me, post(C + "/" + id + "/answer"), "{\"rowVer\":" + ver(id) + ",\"answerCause\":\"열처리 부족\",\"answerAction\":\"조건 변경\","
                + "\"answeredOn\":\"" + on + "\"}");
    }

    @Test
    void 후보_만들기_스냅샷_수정_보내기_통지_회신_반려_다시회신_종결_고정() throws Exception {
        // 후보: QMS ncrsOfSupplier 그대로 — 닫힌 NCR 과 처리 중(반품 없는) NCR 둘 다, 부르는 인자는 자기 협력사·기간, 다른 클레임 없음
        String cand = body(me, C + "/ncr-candidates?custCd=V100&from=" + today.minusDays(30) + "&to=" + today);
        verify(quality).ncrsOfSupplier(vCust, today.minusDays(30), today);
        assertThat((List<String>) JsonPath.read(cand, "$.data.rows[*].ncrNo")).containsExactly("NCR-101", "NCR-104");
        assertThat((List<String>) JsonPath.read(cand, "$.data.rows[*].status")).containsExactly("CLOSED", "OPEN");
        assertThat((String) JsonPath.read(cand, "$.data.rows[0].ncQty")).isEqualTo("10.5");
        assertThat((Object) JsonPath.read(cand, "$.data.rows[0].claimNo")).isNull();
        assertThat((Boolean) JsonPath.read(cand, "$.data.truncated")).isFalse();
        long c = id(send(me, post(C), form("BOTH", "150000", "KRW", "[101]")));
        String d = body(me, C + "/" + c);
        assertThat((String) JsonPath.read(d, "$.data.claim.claimNo")).startsWith("CL");
        assertThat((String) JsonPath.read(d, "$.data.claim.chargeAmt")).isEqualTo("150000");
        assertThat((String) JsonPath.read(d, "$.data.ncrs[0].ncrNo")).isEqualTo("NCR-101");
        assertThat((String) JsonPath.read(d, "$.data.ncrs[0].ncQty")).isEqualTo("10.5");
        assertThat((String) JsonPath.read(body(me, C + "/ncr-candidates?custCd=V100&from=" + today + "&to=" + today), "$.data.rows[0].claimNo"))
                .isEqualTo(JsonPath.read(d, "$.data.claim.claimNo"));
        // 수정: 처리 중 NCR 도 근거가 된다, 시정조치만으로 바꾸면 금액 없이
        send(me, put(C + "/" + c), form("CAPA", null, null, "[101, 104]").replace("}", ",\"rowVer\":" + ver(c) + "}")).andExpect(status().isOk());
        d = body(me, C + "/" + c);
        assertThat((List<?>) JsonPath.read(d, "$.data.ncrs")).hasSize(2);
        assertThat((Object) JsonPath.read(d, "$.data.claim.chargeAmt")).isNull();
        // 보내기 → 통지 한 건(아웃박스), 보낸 뒤 수정 SRM_STATE
        act(me, c, "send", null).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from sys_outbox where topic = 'SRM_CLAIM_SENT' and biz_key = ?", Integer.class, "CLM:" + c))
                .isEqualTo(1);
        assertThat(accounts.query(comp, "select payload ->> 'to' from sys_outbox where biz_key = ?", String.class, "CLM:" + c))
                .isEqualTo("qa@v100.example");
        send(me, put(C + "/" + c), form("CAPA", null, null, "[101]").replace("}", ",\"rowVer\":" + ver(c) + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SRM_STATE"));
        // 회신: 미래 날짜 막힘 → 오늘 → 회신 받음 → 반려(보냄) → 다시 회신 → 종결
        answer(c, today.plusDays(1).toString()).andExpect(jsonPath("$.errors[0].code").value("SRM_CLM_FUTURE"));
        act(me, c, "close", null).andExpect(status().isConflict());        // 회신 전 종결 불가
        answer(c, today.toString()).andExpect(status().isOk());
        assertThat((String) JsonPath.read(body(me, C + "/" + c), "$.data.claim.status")).isEqualTo("ANSWERED");
        act(me, c, "reject", null).andExpect(status().isOk());
        assertThat((String) JsonPath.read(body(me, C + "/" + c), "$.data.claim.status")).isEqualTo("SENT");
        answer(c, today.toString()).andExpect(status().isOk());
        act(me, c, "cancel", "x").andExpect(status().isConflict());        // 회신 받은 클레임은 취소 아니라 종결
        act(me, c, "close", null).andExpect(status().isOk());
        d = body(me, C + "/" + c);
        assertThat((String) JsonPath.read(d, "$.data.claim.status")).isEqualTo("CLOSED");
        assertThat((String) JsonPath.read(d, "$.data.claim.answerCause")).isEqualTo("열처리 부족");
        answer(c, today.toString()).andExpect(status().isConflict());
        act(me, c, "reject", null).andExpect(status().isConflict());
    }

    @Test
    void 입력검사_금액통화_NCR_중복_다른협력사_취소_없음_다른클레임_근거없이_보내기_취소사유() throws Exception {
        send(me, post(C), form("CAPA", "100", null, "[101]")).andExpect(jsonPath("$.errors[0].code").value("SRM_CLM_NO_CHARGE"));
        send(me, post(C), form("CHARGE", null, null, "[101]")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(Matchers.containsInAnyOrder("chargeAmt", "currencyCd")));
        send(me, post(C), form("CHARGE", "0", "KRW", "[101]")).andExpect(jsonPath("$.errors[0].field").value("chargeAmt"));
        send(me, post(C), form("CHARGE", "100", "XXX", "[101]")).andExpect(jsonPath("$.errors[0].field").value("currencyCd"))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        send(me, post(C), form("NOPE", null, null, "[]").replace("\"title\":\"녹 불량\"", "\"title\":\" \"")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(Matchers.hasItems("actionCd", "title")));
        // NCR: 같은 것 두 번(행 1) / 다른 협력사(2) / 취소(3) / 없음(4)
        send(me, post(C), form("CAPA", null, null, "[101, 101, 103, 105, 999]")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.rowKey == '1')].code").value(Matchers.contains("DUPLICATE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '2')].code").value(Matchers.contains("SRM_RTN_NCR_SUPPLIER")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '3')].code").value(Matchers.contains("SRM_CLM_NCR_CANCELLED")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '4')].code").value(Matchers.contains("NO_SUCH_CODE")));
        assertThat(accounts.query(comp, "select count(*) from srm_claim", Integer.class)).isZero();
        // NCR 은 살아 있는 클레임 하나 / 근거 없이 보내기 / 사유 없는 취소 / 취소하면 그 NCR 을 다시 쓸 수 있다
        long c1 = id(send(me, post(C), form("CAPA", null, null, "[101]")));
        send(me, post(C), form("CAPA", null, null, "[101]")).andExpect(jsonPath("$.errors[0].code").value("SRM_CLM_NCR_TAKEN"));
        long c2 = id(send(me, post(C), form("CAPA", null, null, "[]")));
        act(me, c2, "send", null).andExpect(jsonPath("$.errors[0].code").value("LINES_REQUIRED"));
        act(me, c1, "cancel", null).andExpect(jsonPath("$.errors[0].field").value("reason"));
        act(me, c1, "cancel", "중복").andExpect(status().isOk());
        send(me, put(C + "/" + c2), form("CAPA", null, null, "[101]").replace("}", ",\"rowVer\":" + ver(c2) + "}")).andExpect(status().isOk());
        // 후보 잘림 표시는 그대로 넘긴다
        when(quality.ncrsOfSupplier(eq(vCust), any(), any())).thenReturn(new NcrPage(List.of(ncr(101, "CLOSED", vCust)), true));
        assertThat((Boolean) JsonPath.read(body(me, C + "/ncr-candidates?custCd=V100&from=" + today + "&to=" + today), "$.data.truncated")).isTrue();
        // grid: 정렬 허용 목록, 열
        mvc.perform(get(C + "/grid?from=" + today + "&to=" + today + "&sort=chargeAmt,asc").with(me)).andExpect(status().isBadRequest());
        mvc.perform(get(C + "/grid?from=" + today + "&to=" + today + "&keyword=NCR-101").with(me)).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get(C + "/columns").with(me)).andExpect(jsonPath("$.list[*].field").value(Matchers.hasItems("claimNo", "status")));
    }

    @Test
    void A사로_B사_조회수정_막힘_권한() throws Exception {
        long c = id(send(me, post(C), form("CAPA", null, null, "[101]")));
        String other = accounts.newComp("CM");
        long bu = accounts.newUser(other, "buyer1", PW);
        accounts.grant(other, bu, "PUR", "srm-claim", "SEARCH", "SAVE", "APPROVE");
        RequestPostProcessor b = accounts.as(other, bu, "buyer1");
        seed(other);
        mvc.perform(get(C + "/" + c).with(b)).andExpect(status().isNotFound());
        send(b, put(C + "/" + c), form("CAPA", null, null, "[]").replace("}", ",\"rowVer\":0}")).andExpect(status().isNotFound());
        for (String a : List.of("send", "reject", "close", "cancel")) {
            send(b, post(C + "/" + c + "/" + a), "{\"rowVer\":0,\"reason\":\"x\"}").andExpect(status().isNotFound());
        }
        send(b, post(C + "/" + c + "/answer"), "{\"rowVer\":0,\"answerCause\":\"x\",\"answerAction\":\"x\",\"answeredOn\":\"" + today + "\"}")
                .andExpect(status().isNotFound());
        mvc.perform(get(C + "/grid?from=" + today + "&to=" + today).with(b)).andExpect(jsonPath("$.total").value(0));
        // B사 V100 은 다른 협력사 → A사 NCR 은 근거 불가
        send(b, post(C), form("CAPA", null, null, "[101]")).andExpect(jsonPath("$.errors[0].code").value("SRM_RTN_NCR_SUPPLIER"));
        // 권한: 저장만 → 종결 403, 조회만 → 만들기·보내기·회신 403
        long clerk = accounts.newUser(comp, "clerk1", PW);
        accounts.grant(comp, clerk, "CLERK", "srm-claim", "SEARCH", "SAVE");
        RequestPostProcessor k = accounts.as(comp, clerk, "clerk1");
        send(k, post(C + "/" + c + "/close"), "{\"rowVer\":" + ver(c) + "}").andExpect(status().isForbidden());
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", "srm-claim", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer1");
        mvc.perform(get(C + "/" + c).with(v)).andExpect(status().isOk());
        send(v, post(C), form("CAPA", null, null, "[101]")).andExpect(status().isForbidden());
        send(v, post(C + "/" + c + "/send"), "{\"rowVer\":0}").andExpect(status().isForbidden());
        send(v, post(C + "/" + c + "/answer"), "{\"rowVer\":0}").andExpect(status().isForbidden());
    }

    @Test
    void 클레임_통지_받는주소없으면_실패_이력에_클레임번호_주소고친뒤_다시보내기_작성중은_불가() throws Exception {
        accounts.exec(comp, "update mst_cust set email = null where cust_id = ?", vCust);
        long c = id(send(me, post(C), form("CAPA", null, null, "[101]")));
        String no = JsonPath.read(body(me, C + "/" + c), "$.data.claim.claimNo");
        act(me, c, "send", null).andExpect(status().isOk());
        relay.relay(comp);
        String n = body(me, "/api/srm/notices?from=" + today + "&to=" + today);
        assertThat((String) JsonPath.read(n, "$.data[0].topic")).isEqualTo("SRM_CLAIM_SENT");
        assertThat((String) JsonPath.read(n, "$.data[0].poNo")).isEqualTo(no);          // 번호 칸 = 클레임 번호
        assertThat((String) JsonPath.read(n, "$.data[0].status")).isEqualTo("FAIL");
        assertThat((String) JsonPath.read(n, "$.data[0].lastError")).isEqualTo("NO_RECIPIENT");
        long failed = ((Number) JsonPath.read(n, "$.data[0].outboxId")).longValue();
        accounts.exec(comp, "update mst_cust set email = 'qa2@v100.example' where cust_id = ?", vCust);
        send(me, post("/api/srm/notices/" + failed + "/resend"), "{}").andExpect(status().isOk());
        relay.relay(comp);
        n = body(me, "/api/srm/notices?from=" + today + "&to=" + today);
        assertThat((String) JsonPath.read(n, "$.data[0].status")).isEqualTo("SENT");
        assertThat(mailbox.list(comp)).anySatisfy(m -> assertThat(m.subject()).startsWith("[클레임] " + no));
        // 작성 중(통지할 것 없음)인 클레임의 통지는 다시 보낼 수 없다 — 실패 행을 직접 만들어 확인
        long d = id(send(me, post(C), form("CAPA", null, null, "[]")));
        accounts.exec(comp, "update sys_outbox set payload = jsonb_set(payload, '{claimId}', to_jsonb(?::bigint)) where outbox_id = ?", d, failed);
        send(me, post("/api/srm/notices/" + failed + "/resend"), "{}").andExpect(jsonPath("$.code").value("SRM_STATE"));
    }
}
