package io.smarthappy.module.plm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PLM 전자서명 연결 (docs/39 19장) — ECO 결재 승인(APPROVE·합의 REVIEW)·PPAP 판정 기록(AUTHOR)에 플랫폼 서명(docs/36 16장).
 * 토큰 → 결재 엔진 줄 단위 확장점(beforeAct)이 같은 트랜잭션에서 consume, 상세에 서명 + "서명 뒤 바뀜",
 * 정책 PLM_ESIGN_REQUIRED=Y 면 공통 결재함의 승인·합의도 줄마다 막힌다(docs/39 22장).
 */
@AutoConfigureMockMvc
class PlmEsignTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String ECO = "/api/plm/ecos";
    private static final String APR = "/api/platform/approvals";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long ownerId;
    long mgrId;
    long agreeId;
    RequestPostProcessor owner;
    RequestPostProcessor mgr;
    RequestPostProcessor agree;
    String today;

    @Autowired
    io.smarthappy.module.admin.IntegrityCheckService integrity;

    @Autowired
    io.smarthappy.platform.tenant.TenantRunner tenantRunner;

    /** 기록 무결성 점검의 서명 유형별 합계 [일치, 바뀜, 대상 없음, 계약 없음] */
    private java.util.List<String> esignSummary(String type) {
        return tenantRunner.call(comp, () -> integrity.run()).checks().stream().filter(c -> c.key().equals("ESIGN")).findFirst()
                .orElseThrow().items().stream().filter(i -> i.subject().equals(type)).findFirst().orElseThrow().args();
    }

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PES");
        ownerId = accounts.newUser(comp, "eng1", PW);
        mgrId = accounts.newUser(comp, "mgr1", PW);
        agreeId = accounts.newUser(comp, "qa1", PW);
        for (long u : new long[] {ownerId, mgrId, agreeId}) {
            accounts.grant(comp, u, "ENG", "plm-eco", "SEARCH", "SAVE", "APPROVE");
        }
        owner = accounts.as(comp, ownerId, "eng1");
        mgr = accounts.as(comp, mgrId, "mgr1");
        agree = accounts.as(comp, agreeId, "qa1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        seed(comp);
    }

    private void seed(String c) {
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'QMS_4M_REQUIRED', 'N', 't', 't')", c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', 'FG 품목', 'FG', 'MAKE', 'EA', 't', 't')""", c);
        accounts.exec(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C1', 'C1 고객', 'SALES', 't', 't')""", c);
    }

    private void policyY() {
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'PLM_ESIGN_REQUIRED', 'Y', 't', 't')", comp);
    }

    // ---------- 도우미

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private long id(String sql, Object... args) {
        return accounts.query(comp, sql, Long.class, args);
    }

    private int ecoVer(long ecoId) {
        return accounts.query(comp, "select row_ver from plm_eco where eco_id = ?", Integer.class, ecoId);
    }

    private static long data(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    /** ITEM 대상 ECO 상신 — 결재선: (합의 qa1) → 결재 mgr1 */
    private long submitted(boolean withAgree) throws Exception {
        long eco = data(mvc.perform(json(post(ECO), "{\"title\":\"서명 시험\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today + "\"}")
                .with(owner)));
        mvc.perform(json(put(ECO + "/" + eco + "/targets"), "{\"rowVer\":" + ecoVer(eco)
                + ",\"targets\":[{\"rowKey\":\"i\",\"targetType\":\"ITEM\",\"itemCd\":\"FG\"}]}").with(owner)).andExpect(status().isOk());
        String lines = (withAgree ? "{\"userId\":" + agreeId + ",\"lineType\":\"AGREE\"}," : "")
                + "{\"userId\":" + mgrId + ",\"lineType\":\"APPROVE\"}";
        mvc.perform(json(post(ECO + "/" + eco + "/submit"), "{\"rowVer\":" + ecoVer(eco) + ",\"lines\":[" + lines + "]}").with(owner))
                .andExpect(status().isOk());
        return eco;
    }

    private String token(MockHttpSession s, RequestPostProcessor who, String meaning, String type, long target) throws Exception {
        MvcResult r = mvc.perform(json(post("/api/platform/esign/token"), "{\"meaningCd\":\"" + meaning + "\",\"targetType\":\"" + type
                + "\",\"targetId\":" + target + ",\"password\":\"" + PW + "\"}").with(who).session(s))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.data.token");
    }

    private ResultActions approve(MockHttpSession s, RequestPostProcessor who, long eco, String token) throws Exception {
        return mvc.perform(json(post(ECO + "/" + eco + "/approve"), "{\"comment\":\"ok\"" + (token == null ? "" : ",\"esignToken\":\"" + token + "\"")
                + "}").with(who).session(s).header("X-Idempotency-Key", java.util.UUID.randomUUID().toString()));
    }

    /** 공통 결재함 API(docs/36 18.2) — 서명 토큰을 실어 보낸다 */
    private ResultActions approveCommon(MockHttpSession s, RequestPostProcessor who, long apr, String token) throws Exception {
        return mvc.perform(json(post(APR + "/" + apr + "/approve"), "{\"esignToken\":\"" + token + "\"}").with(who).session(s)
                .header("X-Idempotency-Key", java.util.UUID.randomUUID().toString()));
    }

    private String ecoStatus(long eco) {
        return accounts.query(comp, "select status from plm_eco where eco_id = ?", String.class, eco);
    }

    private int signRows(String type, long target) {
        return accounts.query(comp, "select count(*) from sys_esign where target_type = ? and target_id = ?", Integer.class, type, target);
    }

    // ---------- 시험

    @Test
    void 합의는_검토_결재는_승인_서명으로_승인되고_상세에_서명이_보인다() throws Exception {
        long eco = submitted(true);
        MockHttpSession sa = new MockHttpSession();
        approve(sa, agree, eco, token(sa, agree, "REVIEW", "plm_eco", eco)).andExpect(status().isOk());
        assertThat(ecoStatus(eco)).isEqualTo("PENDING");
        MockHttpSession sm = new MockHttpSession();
        approve(sm, mgr, eco, token(sm, mgr, "APPROVE", "plm_eco", eco)).andExpect(status().isOk());
        assertThat(ecoStatus(eco)).isEqualTo("APPROVED");
        mvc.perform(get(ECO + "/" + eco).with(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signs.length()").value(2))
                .andExpect(jsonPath("$.data.signs[0].meaningCd").value("REVIEW"))
                .andExpect(jsonPath("$.data.signs[0].signerNm").value("qa1 사용자"))
                .andExpect(jsonPath("$.data.signs[1].meaningCd").value("APPROVE"))
                .andExpect(jsonPath("$.data.signs[1].authMethod").value("PWD"))
                .andExpect(jsonPath("$.data.signs[1].changed").value(false));
        // 결재 이력의 의견도 그대로 남는다(서명은 결재와 별개 기록)
        assertThat(accounts.query(comp, "select count(*) from apr_line where apr_id = (select apr_id from plm_eco where eco_id = ?) and comment = 'ok'",
                Integer.class, eco)).isEqualTo(2);
        // 기록 무결성 점검(docs/52 Y1): 두 서명 모두 지금 ECO 내용과 같다(점검이 상세·결재와 같은 정규 글자로 다시 계산)
        assertThat(esignSummary("plm_eco")).isEqualTo(java.util.List.of("2", "0", "0", "0"));
    }

    @Test
    void 반려_뒤_내용을_바꾸면_이전_서명은_서명_뒤_바뀜() throws Exception {
        long eco = submitted(true);
        MockHttpSession sa = new MockHttpSession();
        approve(sa, agree, eco, token(sa, agree, "REVIEW", "plm_eco", eco)).andExpect(status().isOk());
        long apr = id("select apr_id from plm_eco where eco_id = ?", eco);
        mvc.perform(json(post(APR + "/" + apr + "/reject"), "{\"comment\":\"유효일 재검토\"}").with(mgr).header("X-Idempotency-Key", java.util.UUID.randomUUID().toString()))
                .andExpect(status().isOk());
        assertThat(ecoStatus(eco)).isEqualTo("DRAFT");
        mvc.perform(get(ECO + "/" + eco).with(owner)).andExpect(jsonPath("$.data.signs[0].changed").value(false));
        mvc.perform(json(put(ECO + "/" + eco), "{\"title\":\"서명 시험 2\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today
                + "\",\"rowVer\":" + ecoVer(eco) + "}").with(owner)).andExpect(status().isOk());
        mvc.perform(get(ECO + "/" + eco).with(owner)).andExpect(jsonPath("$.data.signs[0].changed").value(true));
        assertThat(esignSummary("plm_eco")).isEqualTo(java.util.List.of("0", "1", "0", "0"));   // 점검도 "서명 뒤 바뀜" 1
    }

    @Test
    void 정책_Y_면_서명_없는_승인은_PLM_API_도_공통_결재함의_중간_합의_줄도_거부() throws Exception {
        policyY();
        long eco = submitted(true);
        MockHttpSession sa = new MockHttpSession();
        approve(sa, agree, eco, null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLM_ESIGN_REQUIRED"));
        // 중간 합의 줄도 공통 결재함으로 서명 없이 지나가지 못한다(22장 — 결재 엔진 줄 단위 확장점 beforeAct, 19.3 S2 한계 해소)
        long apr = id("select apr_id from plm_eco where eco_id = ?", eco);
        mvc.perform(json(post(APR + "/" + apr + "/approve"), "{}").with(agree).header("X-Idempotency-Key", java.util.UUID.randomUUID().toString()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLM_ESIGN_REQUIRED"));
        // 그 처리 전체 롤백 — 합의 줄은 아직 차례, 결재 이력 없음
        assertThat(accounts.query(comp, "select status from apr_line where apr_id = ? and approver_id = ?", String.class, apr, agreeId))
                .isEqualTo("CURRENT");
        assertThat(accounts.query(comp, "select count(*) from apr_history where apr_id = ? and action = 'AGREE'", Integer.class, apr)).isZero();
        // 공통 결재함 API 에 서명 토큰을 실으면 통과(같은 세션) — 합의 = REVIEW
        approveCommon(sa, agree, apr, token(sa, agree, "REVIEW", "plm_eco", eco)).andExpect(status().isOk());
        // 마지막 결재 줄도 서명 없이는 거부 → 롤백(ECO 결재 중, 결재 줄 그대로)
        mvc.perform(json(post(APR + "/" + apr + "/approve"), "{}").with(mgr).header("X-Idempotency-Key", java.util.UUID.randomUUID().toString()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLM_ESIGN_REQUIRED"));
        assertThat(ecoStatus(eco)).isEqualTo("PENDING");
        assertThat(accounts.query(comp, "select status from apr_line where apr_id = ? and approver_id = ?", String.class, apr, mgrId))
                .isEqualTo("CURRENT");
        // 결재 줄에 검토(REVIEW) 토큰 → 의미가 달라 거부
        MockHttpSession sm = new MockHttpSession();
        approveCommon(sm, mgr, apr, token(sm, mgr, "REVIEW", "plm_eco", eco)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ESIGN_TOKEN"));
        // 서명하면 통과 — 서명 2건(REVIEW·APPROVE)
        approve(sm, mgr, eco, token(sm, mgr, "APPROVE", "plm_eco", eco)).andExpect(status().isOk());
        assertThat(ecoStatus(eco)).isEqualTo("APPROVED");
        assertThat(signRows("plm_eco", eco)).isEqualTo(2);
    }

    @Test
    void 결재함_상세는_정책_Y_일_때만_지금_차례_줄의_서명을_안내한다() throws Exception {
        long eco = submitted(true);
        long apr = id("select apr_id from plm_eco where eco_id = ?", eco);
        // 정책 N — 안내 없음(토큰 없이 처리 가능)
        mvc.perform(get(APR + "/" + apr).with(agree)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.esignApprove").doesNotExist()).andExpect(jsonPath("$.data.esignReject").doesNotExist());
        policyY();
        String no = accounts.query(comp, "select eco_no from plm_eco where eco_id = ?", String.class, eco);
        // 합의 줄 → REVIEW, 반려는 안내 없음
        mvc.perform(get(APR + "/" + apr).with(agree)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.esignApprove.meaningCd").value("REVIEW"))
                .andExpect(jsonPath("$.data.esignApprove.targetType").value("plm_eco"))
                .andExpect(jsonPath("$.data.esignApprove.targetId").value(eco))
                .andExpect(jsonPath("$.data.esignApprove.title").value(no + " 서명 시험"))
                .andExpect(jsonPath("$.data.esignReject").doesNotExist());
        // 안내대로 서명하면 결재함 API 로 통과 → 결재 줄은 APPROVE 안내
        MockHttpSession sa = new MockHttpSession();
        approveCommon(sa, agree, apr, token(sa, agree, "REVIEW", "plm_eco", eco)).andExpect(status().isOk());
        mvc.perform(get(APR + "/" + apr).with(mgr)).andExpect(jsonPath("$.data.esignApprove.meaningCd").value("APPROVE"));
        MockHttpSession sm = new MockHttpSession();
        approveCommon(sm, mgr, apr, token(sm, mgr, "APPROVE", "plm_eco", eco)).andExpect(status().isOk());
        assertThat(ecoStatus(eco)).isEqualTo("APPROVED");
        // 끝난 결재 — 안내 없음
        mvc.perform(get(APR + "/" + apr).with(mgr)).andExpect(jsonPath("$.data.esignApprove").doesNotExist());
    }

    @Test
    void 정책_Y_여도_반려는_서명_없이_된다() throws Exception {
        policyY();
        long eco = submitted(true);
        long apr = id("select apr_id from plm_eco where eco_id = ?", eco);
        mvc.perform(json(post(APR + "/" + apr + "/reject"), "{\"comment\":\"재검토\"}").with(agree)
                .header("X-Idempotency-Key", java.util.UUID.randomUUID().toString())).andExpect(status().isOk());
        assertThat(ecoStatus(eco)).isEqualTo("DRAFT");
        assertThat(signRows("plm_eco", eco)).isZero();
    }

    @Test
    void 정책_N_이면_서명_없이도_승인되고_서명_행은_없다() throws Exception {
        long eco = submitted(false);
        approve(new MockHttpSession(), mgr, eco, null).andExpect(status().isOk());
        assertThat(ecoStatus(eco)).isEqualTo("APPROVED");
        assertThat(signRows("plm_eco", eco)).isZero();
    }

    @Test
    void E_F_다른_ECO_다른_의미_다른_사람_재사용_토큰은_거부되고_결재는_그대로() throws Exception {
        long eco = submitted(false);
        long other = submitted(false);
        MockHttpSession sm = new MockHttpSession();
        // 다른 ECO 로 받은 토큰
        approve(sm, mgr, eco, token(sm, mgr, "APPROVE", "plm_eco", other)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ESIGN_TOKEN"));
        // 의미가 다름(결재 줄인데 REVIEW)
        approve(sm, mgr, eco, token(sm, mgr, "REVIEW", "plm_eco", eco)).andExpect(status().isForbidden());
        // 다른 사람(작성자)이 받은 토큰을 결재자 요청에 — 세션이 달라 토큰 없음과 같다
        MockHttpSession so = new MockHttpSession();
        String ownerToken = token(so, owner, "APPROVE", "plm_eco", eco);
        approve(sm, mgr, eco, ownerToken).andExpect(status().isForbidden());
        // 같은 세션이라도 사람이 다르면 거부
        approve(so, mgr, eco, ownerToken).andExpect(status().isForbidden());
        assertThat(ecoStatus(eco)).isEqualTo("PENDING");
        assertThat(signRows("plm_eco", eco)).isZero();
        // 내 차례가 아닌 사람이 자기 토큰으로 → 결재 엔진 거부, 서명 행도 롤백
        MockHttpSession sw = new MockHttpSession();
        approve(sw, owner, eco, token(sw, owner, "APPROVE", "plm_eco", eco)).andExpect(status().is4xxClientError());
        assertThat(signRows("plm_eco", eco)).isZero();
        // 정상 → 같은 토큰 재사용 거부(다른 ECO 에도)
        String t = token(sm, mgr, "APPROVE", "plm_eco", eco);
        approve(sm, mgr, eco, t).andExpect(status().isOk());
        approve(sm, mgr, other, t).andExpect(status().isForbidden());
        // 결재 중이 아닌 ECO
        approve(sm, mgr, eco, token(sm, mgr, "APPROVE", "plm_eco", eco)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLM_ECO_STATE"));
    }

    @Test
    void PPAP_판정은_작성_서명과_함께_기록되고_재제출하면_서명_뒤_바뀜() throws Exception {
        long eco = submitted(false);
        long ppap = data(mvc.perform(json(post(ECO + "/" + eco + "/ppap"), "{\"custCd\":\"C1\",\"submitLevel\":3}").with(owner)));
        accounts.exec(comp, "update plm_ppap_elem set status = 'DONE' where ppap_id = ?", ppap);
        int ver = accounts.query(comp, "select row_ver from plm_ppap where ppap_id = ?", Integer.class, ppap);
        mvc.perform(json(post("/api/plm/ppaps/" + ppap + "/submit"), "{\"rowVer\":" + ver + "}").with(owner)).andExpect(status().isOk());
        ver = accounts.query(comp, "select row_ver from plm_ppap where ppap_id = ?", Integer.class, ppap);
        policyY();
        String decide = "{\"rowVer\":" + ver + ",\"decision\":\"INTERIM\",\"decidedOn\":\"" + today + "\",\"note\":\"30일\"";
        // 정책 Y + 토큰 없음 → 거부, 패키지 그대로
        mvc.perform(json(post("/api/plm/ppaps/" + ppap + "/decide"), decide + "}").with(mgr))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PLM_ESIGN_REQUIRED"));
        assertThat(accounts.query(comp, "select status from plm_ppap where ppap_id = ?", String.class, ppap)).isEqualTo("SUBMITTED");
        // 의미가 다른 토큰(APPROVE) → 거부, 판정도 롤백
        MockHttpSession s = new MockHttpSession();
        mvc.perform(json(post("/api/plm/ppaps/" + ppap + "/decide"), decide + ",\"esignToken\":\"" + token(s, mgr, "APPROVE", "plm_ppap", ppap)
                + "\"}").with(mgr).session(s)).andExpect(status().isForbidden());
        assertThat(accounts.query(comp, "select status from plm_ppap where ppap_id = ?", String.class, ppap)).isEqualTo("SUBMITTED");
        // AUTHOR 토큰 → 판정 + 서명
        mvc.perform(json(post("/api/plm/ppaps/" + ppap + "/decide"), decide + ",\"esignToken\":\"" + token(s, mgr, "AUTHOR", "plm_ppap", ppap)
                + "\"}").with(mgr).session(s)).andExpect(status().isOk());
        mvc.perform(get(ECO + "/" + eco + "/ppap").with(owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ppap.status").value("INTERIM"))
                .andExpect(jsonPath("$.data.signs.length()").value(1))
                .andExpect(jsonPath("$.data.signs[0].meaningCd").value("AUTHOR"))
                .andExpect(jsonPath("$.data.signs[0].changed").value(false));
        // 재제출(작성 중으로) → 서명한 판정 내용과 달라짐
        ver = accounts.query(comp, "select row_ver from plm_ppap where ppap_id = ?", Integer.class, ppap);
        mvc.perform(json(post("/api/plm/ppaps/" + ppap + "/reopen"), "{\"rowVer\":" + ver + "}").with(owner)).andExpect(status().isOk());
        mvc.perform(get(ECO + "/" + eco + "/ppap").with(owner)).andExpect(jsonPath("$.data.signs[0].changed").value(true));
    }

    @Test
    void B_A사로_B사_ECO_에_서명할_수_없고_서명_행도_남지_않는다() throws Exception {
        long eco = submitted(false);
        String compB = accounts.newComp("PESB");
        long userB = accounts.newUser(compB, "mgrB", PW);
        accounts.grant(compB, userB, "ENG", "plm-eco", "SEARCH", "SAVE", "APPROVE");
        RequestPostProcessor b = accounts.as(compB, userB, "mgrB");
        MockHttpSession s = new MockHttpSession();
        String t = token(s, b, "APPROVE", "plm_eco", eco);
        approve(s, b, eco, t).andExpect(status().isNotFound());
        assertThat(accounts.query(compB, "select count(*) from sys_esign where target_id = ?", Integer.class, eco)).isZero();
        assertThat(signRows("plm_eco", eco)).isZero();
        // A사 상세의 서명 목록을 B사 사용자가 볼 수 없다
        mvc.perform(get(ECO + "/" + eco).with(b)).andExpect(status().isNotFound());
        assertThat(ecoStatus(eco)).isEqualTo("PENDING");
    }
}
