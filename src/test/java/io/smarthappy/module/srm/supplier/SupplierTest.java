package io.smarthappy.module.srm.supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * SRM S1 협력사 정보 (docs/31) — 등록은 구매 거래처만, 거래 상태 사유, 인증서(갱신 = 새 행·현재 판정·만료 경계),
 * 공급 품목(단위 자릿수·중복·사용 중지 품목), 단가(열린 이전 단가 자동 마감·겹침·고정 칸·동시 추가), A사로 B사, 권한, 멱등키.
 */
@AutoConfigureMockMvc
class SupplierTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String API = "/api/srm/suppliers";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long userId;
    RequestPostProcessor admin;
    LocalDate today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("SR");
        userId = accounts.newUser(comp, "buyer1", PW);
        accounts.grant(comp, userId, "PUR", "srm-supplier", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "buyer1");
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        item(comp, "BOLT-M8", "EA");
        item(comp, "RESIN-A", "KG");
    }

    private long cust(String c, String cd, String type) {
        return accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, ?, ?, ?, 't', 't') returning cust_id""", Long.class, c, cd, cd + " 상사", type);
    }

    private void item(String c, String cd, String unit) {
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, ?, ?, 'RM', 'BUY', ?, 'N', 't', 't')""", c, cd, cd + " 품목", unit);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(admin));
    }

    private ResultActions putJson(String url, String body) throws Exception {
        return mvc.perform(json(put(url), body).with(admin));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private long supplier(String cd) throws Exception {
        long id = cust(comp, cd, "PURCHASE");
        postJson(API, "{\"custId\":" + id + "}").andExpect(status().isOk());
        return id;
    }

    private static String d(LocalDate x) {
        return x.toString();
    }

    // ---------- 협력사 ----------

    @Test
    void 등록은_구매거래처만_중복_사용중지_상태사유_코드형식_직접SQL() throws Exception {
        long buy = cust(comp, "V100", "PURCHASE");
        long both = cust(comp, "V200", "BOTH");
        long sales = cust(comp, "C100", "SALES");
        long off = cust(comp, "V300", "PURCHASE");
        accounts.exec(comp, "update mst_cust set use_yn = 'N' where cust_id = ?", off);
        postJson(API, "{\"custId\":" + buy + ",\"buyerLoginId\":\"BUYER1\",\"countryCd\":\"kr\",\"payTermsCd\":\"net30\"}")
                .andExpect(status().isOk());
        mvc.perform(get(API + "/" + buy).with(admin)).andExpect(jsonPath("$.data.registered").value(true))
                .andExpect(jsonPath("$.data.currencyCd").value("KRW")).andExpect(jsonPath("$.data.tradeStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.countryCd").value("KR")).andExpect(jsonPath("$.data.buyerUserId").value(userId))
                .andExpect(jsonPath("$.data.certState").value("NONE"));
        postJson(API, "{\"custId\":" + buy + "}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        postJson(API, "{\"custId\":" + sales + "}").andExpect(jsonPath("$.errors[0].code").value("NOT_SUPPLIER_SRM"));
        postJson(API, "{\"custId\":" + off + "}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        postJson(API, "{\"custId\":999999999}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        postJson(API, "{}").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        // 판매 전용 거래처는 화면에서도 안 보이고 상세 404
        mvc.perform(get(API + "/" + sales).with(admin)).andExpect(status().isNotFound());
        mvc.perform(get(API).with(admin)).andExpect(jsonPath("$.data.length()").value(3));
        mvc.perform(get(API).param("registered", "N").with(admin)).andExpect(jsonPath("$.data.length()").value(2));
        // 칸 검사: 보류인데 사유 없음, 없는 상태·통화, 국가 형식, 미래 진단일, 없는 담당, 제어문자 — 끝까지 모아서
        postJson(API, "{\"custId\":" + both + ",\"tradeStatus\":\"HOLD\",\"currencyCd\":\"XYZ\",\"countryCd\":\"KOR\","
                + "\"controlCountryCd\":\"1A\",\"secAssessedOn\":\"" + d(today.plusDays(1)) + "\",\"buyerLoginId\":\"nobody\","
                + "\"remark\":\"a\\u0007b\"}")
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("statusReason", "currencyCd", "countryCd",
                        "controlCountryCd", "secAssessedOn", "buyerLoginId", "remark")));
        postJson(API, "{\"custId\":" + both + ",\"tradeStatus\":\"GONE\"}").andExpect(jsonPath("$.errors[0].code").value("CODE"));
        // 수정: 정지 + 사유, 옛 rowVer 는 409, 등록 안 된 거래처 수정은 404
        putJson(API + "/" + buy, "{\"tradeStatus\":\"BLOCKED\",\"statusReason\":\"품질 사고\",\"currencyCd\":\"KRW\",\"rowVer\":0}")
                .andExpect(status().isOk());
        putJson(API + "/" + buy, "{\"tradeStatus\":\"ACTIVE\",\"currencyCd\":\"KRW\",\"rowVer\":0}").andExpect(status().isConflict());
        putJson(API + "/" + both, "{\"currencyCd\":\"KRW\",\"rowVer\":0}").andExpect(status().isNotFound());
        mvc.perform(get(API).param("tradeStatus", "BLOCKED").with(admin)).andExpect(jsonPath("$.data.length()").value(1));
        // 회사 확장 결제 조건은 쓸 수 있다
        accounts.exec(comp, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by) values (?, 'PAY_TERMS', 'NET45', '45일', 't', 't')", comp);
        putJson(API + "/" + buy, "{\"tradeStatus\":\"BLOCKED\",\"statusReason\":\"품질 사고\",\"currencyCd\":\"KRW\",\"payTermsCd\":\"NET45\",\"rowVer\":1}")
                .andExpect(status().isOk());
        // 직접 SQL(앱 계정): 판매 거래처 등록·사유 없는 정지·삭제
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into srm_supplier (cust_id, comp_cd, created_by, updated_by) values (?, ?, 't', 't')", sales, comp))))
                .contains("ck_srm_supplier_cust_type");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_supplier set trade_status = 'HOLD', status_reason = '  ' where cust_id = ?", buy))))
                .contains("ck_srm_supplier_reason");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from srm_supplier where cust_id = ?", buy))))
                .contains("42501");
    }

    @Test
    void 구매_담당은_새로_고를_때만_사용_중이어야_하고_그대로_둔_기존_담당은_저장을_막지_않는다() throws Exception {
        long buy = cust(comp, "V100", "PURCHASE");
        long other = cust(comp, "V200", "PURCHASE");
        long locked = accounts.newUser(comp, "locked1", PW);
        accounts.exec(comp, "update sys_user set lock_yn = 'Y', locked_until = null where user_id = ?", locked);
        long off = accounts.newUser(comp, "off1", PW);
        accounts.setUserUse(comp, off, "N");
        long temp = accounts.newUser(comp, "temp1", PW);
        // 잠금(관리자)·사용 중지 → INACTIVE, 없는 아이디는 NO_SUCH_CODE 그대로
        postJson(API, "{\"custId\":" + buy + ",\"buyerLoginId\":\"locked1\"}")
                .andExpect(jsonPath("$.errors[0].field").value("buyerLoginId")).andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        postJson(API, "{\"custId\":" + buy + ",\"buyerLoginId\":\"off1\"}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        // 자동 해제 잠금(로그인 실패, locked_until 있음)은 사용 중으로 본다
        accounts.exec(comp, "update sys_user set lock_yn = 'Y', locked_until = now() + interval '15 minutes' where user_id = ?", temp);
        postJson(API, "{\"custId\":" + other + ",\"buyerLoginId\":\"temp1\"}").andExpect(status().isOk());
        // 담당을 정한 뒤 그 사람이 사용 중지돼도, 담당을 그대로 두고 다른 칸을 고치는 저장은 된다
        postJson(API, "{\"custId\":" + buy + ",\"buyerLoginId\":\"temp1\"}").andExpect(status().isOk());
        accounts.setUserUse(comp, temp, "N");
        putJson(API + "/" + buy, "{\"buyerLoginId\":\"temp1\",\"currencyCd\":\"KRW\",\"remark\":\"담당 그대로\",\"rowVer\":0}").andExpect(status().isOk());
        // 다른 사용 중지 계정으로 바꾸는 것은 거부, 사용 중 계정으로 바꾸는 것은 된다
        putJson(API + "/" + buy, "{\"buyerLoginId\":\"off1\",\"currencyCd\":\"KRW\",\"rowVer\":1}")
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        putJson(API + "/" + buy, "{\"buyerLoginId\":\"buyer1\",\"currencyCd\":\"KRW\",\"rowVer\":1}").andExpect(status().isOk());
        mvc.perform(get(API + "/" + buy).with(admin)).andExpect(jsonPath("$.data.buyerUserId").value(userId));
    }

    // ---------- 인증서 ----------

    @Test
    void 인증서는_갱신이_새행_유형마다_최신이_현재_만료경계와_임박정책() throws Exception {
        long v = supplier("V100");
        String c = API + "/" + v + "/certs";
        long iatfOld = id(postJson(c, "{\"certTypeCd\":\"iatf16949\",\"certNo\":\"IATF-001\",\"issuedOn\":\"2023-01-01\",\"expiresOn\":\""
                + d(today) + "\"}"));
        // 만료일 당일 = 아직 유효(임박)
        mvc.perform(get(API + "/" + v).with(admin)).andExpect(jsonPath("$.data.certState").value("DUE_SOON"));
        long sq = id(postJson(c, "{\"certTypeCd\":\"SQ\",\"issuedOn\":\"2024-01-01\",\"expiresOn\":\"" + d(today.minusDays(1)) + "\"}"));
        mvc.perform(get(API + "/" + v).with(admin)).andExpect(jsonPath("$.data.certState").value("EXPIRED"));
        // 갱신 = 새 행 → 옛 행은 현재 아님(이력), 판정은 새 행
        id(postJson(c, "{\"certTypeCd\":\"IATF16949\",\"certNo\":\"IATF-002\",\"issuedOn\":\"" + d(today) + "\",\"expiresOn\":\""
                + d(today.plusDays(61)) + "\"}"));
        putJson(c + "/" + sq, "{\"useYn\":\"N\",\"expiresOn\":\"" + d(today.minusDays(1)) + "\",\"rowVer\":0}").andExpect(status().isOk());
        mvc.perform(get(API + "/" + v).with(admin)).andExpect(jsonPath("$.data.certState").value("VALID"));
        mvc.perform(get(c).with(admin)).andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[?(@.certId == " + iatfOld + ")].current").value(false))
                .andExpect(jsonPath("$.data[?(@.certId == " + sq + ")].state").value("OFF"));
        // 임박 일수 정책 61 → 61일 남은 것도 임박
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_CERT_DUE_SOON_DAYS', '61', 't', 't')", comp);
        mvc.perform(get(API + "/" + v).with(admin)).andExpect(jsonPath("$.data.certState").value("DUE_SOON"));
        // 고정 칸·미래 발행·만료 < 발행·없는 유형, 회사 확장 유형은 가능
        putJson(c + "/" + iatfOld, "{\"certTypeCd\":\"SQ\",\"issuedOn\":\"2023-01-02\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors.length()").value(2)).andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        postJson(c, "{\"certTypeCd\":\"ISO9001\",\"issuedOn\":\"" + d(today.plusDays(1)) + "\"}").andExpect(jsonPath("$.errors[0].code").value("FUTURE"));
        postJson(c, "{\"certTypeCd\":\"ISO9001\",\"issuedOn\":\"2024-05-02\",\"expiresOn\":\"2024-05-01\"}")
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_RANGE"));
        postJson(c, "{\"certTypeCd\":\"MADEUP\",\"issuedOn\":\"2024-05-02\"}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        accounts.exec(comp, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by) values (?, 'SUPPLIER_CERT', 'STAR5', '5스타', 't', 't')", comp);
        id(postJson(c, "{\"certTypeCd\":\"STAR5\",\"issuedOn\":\"2024-05-02\"}"));
        // 직접 SQL: 유형 바꾸기·삭제 금지
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_supplier_cert set cert_type_cd = 'ISO9001' where cert_id = ?", iatfOld)))).contains("ck_srm_supplier_cert_fixed");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from srm_supplier_cert where cert_id = ?", sq))))
                .contains("42501");
        // 등록 안 된 거래처에는 인증서 없음(404)
        long notYet = cust(comp, "V900", "PURCHASE");
        postJson(API + "/" + notYet + "/certs", "{\"certTypeCd\":\"SQ\",\"issuedOn\":\"2024-01-01\"}").andExpect(status().isNotFound());
    }

    @Test
    void 인증서_판정_경계_순수함수() {
        LocalDate t = LocalDate.of(2026, 10, 20);
        assertThat(CertStatus.of(null, t, 60)).isEqualTo("VALID");
        assertThat(CertStatus.of(t.minusDays(1), t, 60)).isEqualTo("EXPIRED");
        assertThat(CertStatus.of(t, t, 60)).isEqualTo("DUE_SOON");
        assertThat(CertStatus.of(t.plusDays(60), t, 60)).isEqualTo("DUE_SOON");
        assertThat(CertStatus.of(t.plusDays(61), t, 60)).isEqualTo("VALID");
        assertThat(CertStatus.ofSupplier(0, null, t, 60)).isEqualTo("NONE");
        assertThat(CertStatus.ofSupplier(2, null, t, 60)).isEqualTo("VALID");
    }

    // ---------- 공급 품목 ----------

    @Test
    void 공급품목은_단위자릿수_범위_중복_품목고정_사용중지품목은_새참조와_다시사용_차단() throws Exception {
        long v = supplier("V100");
        String s = API + "/" + v + "/sources";
        postJson(s, "{\"itemCd\":\"BOLT-M8\",\"moq\":\"0.5\"}").andExpect(jsonPath("$.errors[0].code").value("SCALE"));
        long src = id(postJson(s, "{\"itemCd\":\"bolt-m8\",\"supplierItemCd\":\"SUP-8\",\"leadDays\":7,\"moq\":\"1000\",\"orderMult\":\"500\"}"));
        id(postJson(s, "{\"itemCd\":\"RESIN-A\",\"moq\":\"0.125\",\"priority\":2}"));
        mvc.perform(get(s).with(admin)).andExpect(jsonPath("$.data[0].moq").value("1000"))
                .andExpect(jsonPath("$.data[1].moq").value("0.125"));
        postJson(s, "{\"itemCd\":\"RESIN-A\",\"moq\":\"0.0001\"}").andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("SCALE")));
        postJson(s, "{\"itemCd\":\"BOLT-M8\"}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        postJson(s, "{\"itemCd\":\"NOPE\"}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        postJson(s, "{\"itemCd\":\"\"}").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        item(comp, "OLD-1", "EA");
        accounts.exec(comp, "update mst_item set use_yn = 'N' where item_cd = 'OLD-1'");
        postJson(s, "{\"itemCd\":\"OLD-1\"}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        postJson(s, "{\"itemCd\":\"RESIN-A\",\"leadDays\":366,\"priority\":0,\"moq\":\"-1\"}")
                .andExpect(jsonPath("$.errors.length()").value(3));
        // 품목은 고정, 사용 중지 → 품목 사용 중지 → 다시 사용은 막힘
        putJson(s + "/" + src, "{\"itemCd\":\"RESIN-A\",\"rowVer\":0}").andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        putJson(s + "/" + src, "{\"supplierItemCd\":\"SUP-8\",\"leadDays\":7,\"useYn\":\"N\",\"rowVer\":0}").andExpect(status().isOk());
        accounts.exec(comp, "update mst_item set use_yn = 'N' where item_cd = 'BOLT-M8'");
        putJson(s + "/" + src, "{\"useYn\":\"Y\",\"rowVer\":1}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_source set use_yn = 'Y' where source_id = ?", src)))).contains("fk_srm_source_item_active");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_source set item_id = (select item_id from mst_item where item_cd = 'RESIN-A') where source_id = ?", src))))
                .contains("ck_srm_source_fixed");
        mvc.perform(get(API + "/" + v).with(admin)).andExpect(jsonPath("$.data.sourceCnt").value(1));
        // 품목 찾기: 사용 중만
        mvc.perform(get(API + "/items").param("keyword", "m8").with(admin)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(API + "/items").param("keyword", "resin").with(admin)).andExpect(jsonPath("$.data[0].unitCd").value("KG"));
    }

    // ---------- 단가 ----------

    @Test
    void 새단가는_열린이전단가를_전날로_마감_한시단가_겹침은_거부_통화별_고정칸() throws Exception {
        long v = supplier("V100");
        String p = API + "/" + v + "/prices";
        long p1 = id(postJson(p, "{\"itemCd\":\"BOLT-M8\",\"unitPrice\":\"120\",\"validFrom\":\"2026-01-01\"}"));
        long p2 = id(postJson(p, "{\"itemCd\":\"BOLT-M8\",\"unitPrice\":\"130.5\",\"validFrom\":\"2026-07-01\"}"));
        assertThat(accounts.query(comp, "select valid_to from srm_price where price_id = ?", LocalDate.class, p1))
                .isEqualTo(LocalDate.parse("2026-06-30"));
        assertThat(accounts.query(comp, "select currency_cd from srm_price where price_id = ?", String.class, p2)).isEqualTo("KRW");
        // 한시 단가는 열린 단가를 자르지 않는다 → 겹침, 과거로 끼워 넣기도 겹침
        postJson(p, "{\"itemCd\":\"BOLT-M8\",\"unitPrice\":\"100\",\"validFrom\":\"2026-08-01\",\"validTo\":\"2026-08-31\"}")
                .andExpect(jsonPath("$.errors[0].code").value("PRICE_OVERLAP"));
        postJson(p, "{\"itemCd\":\"BOLT-M8\",\"unitPrice\":\"125\",\"validFrom\":\"2026-03-01\"}")
                .andExpect(jsonPath("$.errors[0].code").value("PRICE_OVERLAP"));
        assertThat(accounts.query(comp, "select valid_to from srm_price where price_id = ?", LocalDate.class, p1))
                .isEqualTo(LocalDate.parse("2026-06-30"));     // 실패한 요청의 마감도 롤백
        // 다른 통화는 같은 기간 가능, 큰 단가·소수 4자리 정확히
        id(postJson(p, "{\"itemCd\":\"BOLT-M8\",\"currencyCd\":\"usd\",\"unitPrice\":\"12345678901234.5678\",\"validFrom\":\"2026-01-01\"}"));
        mvc.perform(get(p).param("itemCd", "BOLT-M8").with(admin))
                .andExpect(jsonPath("$.data[?(@.currencyCd == 'USD')].unitPrice").value("12345678901234.5678"));
        // 칸 검사
        postJson(p, "{\"itemCd\":\"RESIN-A\",\"unitPrice\":\"0\",\"validFrom\":\"2026-01-01\"}").andExpect(jsonPath("$.errors[0].code").value("POSITIVE"));
        postJson(p, "{\"itemCd\":\"RESIN-A\",\"unitPrice\":\"1.23456\",\"validFrom\":\"2026-01-01\"}").andExpect(jsonPath("$.errors[0].code").value("SCALE"));
        postJson(p, "{\"itemCd\":\"RESIN-A\",\"unitPrice\":\"123456789012345\",\"validFrom\":\"2026-01-01\"}").andExpect(jsonPath("$.errors[0].code").value("INT_DIGITS"));
        postJson(p, "{\"itemCd\":\"RESIN-A\",\"unitPrice\":\"1e3\",\"validFrom\":\"2026-01-01\"}").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        postJson(p, "{\"itemCd\":\"RESIN-A\",\"unitPrice\":\"10\",\"validFrom\":\"2026-02-01\",\"validTo\":\"2026-01-31\"}")
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_RANGE"));
        postJson(p, "{\"itemCd\":\"RESIN-A\",\"currencyCd\":\"BTC\",\"unitPrice\":\"10\",\"validFrom\":\"2026-02-01\"}")
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        postJson(p, "{\"itemCd\":\"RESIN-A\",\"unitPrice\":\"10\"}").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        // 수정은 끝일·사용만: 단가 바꾸기 FIXED, 끝일 정하면 한시 단가 가능, 사용 중지 → 다시 사용은 겹치면 거부
        putJson(p + "/" + p2, "{\"unitPrice\":\"140\",\"rowVer\":0}").andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        putJson(p + "/" + p2, "{\"validTo\":\"2026-07-31\",\"rowVer\":0}").andExpect(status().isOk());
        long promo = id(postJson(p, "{\"itemCd\":\"BOLT-M8\",\"unitPrice\":\"100\",\"validFrom\":\"2026-08-01\",\"validTo\":\"2026-08-31\"}"));
        putJson(p + "/" + promo, "{\"validTo\":\"2026-08-31\",\"useYn\":\"N\",\"rowVer\":0}").andExpect(status().isOk());
        putJson(p + "/" + p2, "{\"validTo\":\"\",\"rowVer\":1}").andExpect(status().isOk());
        putJson(p + "/" + promo, "{\"validTo\":\"2026-08-31\",\"useYn\":\"Y\",\"rowVer\":1}")
                .andExpect(jsonPath("$.errors[0].code").value("PRICE_OVERLAP"));
        // 직접 SQL: 단가 바꾸기·겹치는 행 넣기
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_price set unit_price = 1 where price_id = ?", p1)))).contains("ck_srm_price_fixed");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, """
                insert into srm_price (comp_cd, cust_id, item_id, currency_cd, unit_price, valid_from, created_by, updated_by)
                select ?, ?, item_id, 'KRW', 1, '2026-12-01', 't', 't' from mst_item where item_cd = 'BOLT-M8'""", comp, v))))
                .contains("23P01");
        // 상태: 오늘 기준(과거 시작·끝 없는 단가 = 적용 중)
        long cur = id(postJson(p, "{\"itemCd\":\"RESIN-A\",\"unitPrice\":\"9.9\",\"validFrom\":\"2001-01-01\"}"));
        mvc.perform(get(p).param("itemCd", "RESIN-A").with(admin))
                .andExpect(jsonPath("$.data[?(@.priceId == " + cur + ")].state").value("CURRENT"));
    }

    @Test
    void 같은_단가를_동시에_두번_넣으면_하나만_성공() throws Exception {
        long v = supplier("V100");
        String body = "{\"itemCd\":\"RESIN-A\",\"unitPrice\":\"10\",\"validFrom\":\"2026-05-01\",\"validTo\":\"2026-05-31\"}";
        List<CompletableFuture<Integer>> runs = List.of(1, 2, 3).stream().map(i -> CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(json(post(API + "/" + v + "/prices"), body).with(admin)).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        })).toList();
        List<Integer> codes = runs.stream().map(CompletableFuture::join).toList();
        assertThat(codes).containsOnlyOnce(200);
        assertThat(accounts.query(comp, "select count(*) from srm_price where cust_id = ?", Integer.class, v)).isEqualTo(1);
    }

    // ---------- A사로 B사 · 권한 · 멱등 ----------

    @Test
    void B사로_A사_협력사_인증서_공급품목_단가_모두_막힘_직접SQL_다른회사참조_거부() throws Exception {
        long v = supplier("V100");
        long cert = id(postJson(API + "/" + v + "/certs", "{\"certTypeCd\":\"SQ\",\"issuedOn\":\"2024-01-01\"}"));
        long src = id(postJson(API + "/" + v + "/sources", "{\"itemCd\":\"BOLT-M8\"}"));
        long price = id(postJson(API + "/" + v + "/prices", "{\"itemCd\":\"BOLT-M8\",\"unitPrice\":\"1\",\"validFrom\":\"2026-01-01\"}"));
        String other = accounts.newComp("SB");
        long bu = accounts.newUser(other, "buyer1", PW);
        accounts.grant(other, bu, "PUR", "srm-supplier", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bu, "buyer1");
        long bv = cust(other, "V100", "PURCHASE");
        mvc.perform(json(post(API), "{\"custId\":" + bv + "}").with(b)).andExpect(status().isOk());
        // 목록·상세·수정·하위 목록
        mvc.perform(get(API).with(b)).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].custId").value(bv));
        mvc.perform(get(API + "/" + v).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(API + "/" + v), "{\"currencyCd\":\"KRW\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(API), "{\"custId\":" + v + "}").with(b)).andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        for (String sub : new String[] {"/certs", "/sources", "/prices"}) {
            mvc.perform(get(API + "/" + v + sub).with(b)).andExpect(status().isNotFound());
        }
        mvc.perform(json(put(API + "/" + v + "/certs/" + cert), "{\"useYn\":\"N\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        // 자기 협력사 경로에 A사 행 id → 404
        mvc.perform(json(put(API + "/" + bv + "/certs/" + cert), "{\"useYn\":\"N\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(API + "/" + bv + "/sources/" + src), "{\"useYn\":\"N\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(API + "/" + bv + "/prices/" + price), "{\"useYn\":\"N\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        // A사 품목 코드는 B사에 없다, A사 담당자 아이디도
        mvc.perform(json(post(API + "/" + bv + "/sources"), "{\"itemCd\":\"BOLT-M8\"}").with(b))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        mvc.perform(json(put(API + "/" + bv), "{\"currencyCd\":\"KRW\",\"buyerLoginId\":\"buyer1\",\"rowVer\":0}").with(b))
                .andExpect(status().isOk());
        assertThat(accounts.query(other, "select buyer_user_id from srm_supplier where cust_id = ?", Long.class, bv)).isEqualTo(bu);
        // 직접 SQL(B사 범위): A사 거래처를 협력사로·A사 협력사에 인증서·B사 협력사에 A사 품목
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other,
                "insert into srm_supplier (cust_id, comp_cd, created_by, updated_by) values (?, ?, 't', 't')", v, other))))
                .isPresent();
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into srm_supplier_cert (comp_cd, cust_id, cert_type_cd, issued_on, created_by, updated_by)
                values (?, ?, 'SQ', '2024-01-01', 't', 't')""", other, v)))).contains("fk_srm_supplier_cert_supplier_comp");
        long aItem = accounts.query(comp, "select item_id from mst_item where item_cd = 'BOLT-M8'", Long.class);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into srm_source (comp_cd, cust_id, item_id, created_by, updated_by) values (?, ?, ?, 't', 't')""",
                other, bv, aItem)))).contains("fk_srm_source_item_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other,
                "update srm_supplier set buyer_user_id = ? where cust_id = ?", userId, bv)))).contains("fk_srm_supplier_buyer_comp");
        // 다른 회사 comp_cd 로 넣기 → 트리거(RLS 로 상대 행이 안 보여 23503) 또는 RLS(42501) — 어느 쪽이든 거부
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(other,
                "insert into srm_supplier (cust_id, comp_cd, created_by, updated_by) values (?, ?, 't', 't')", v, comp))))
                .get().isIn("23503", "42501");
    }

    @Test
    void 조회권한만으로는_저장_403_권한없으면_조회도_403_같은_멱등키_두번은_409() throws Exception {
        long v = supplier("V100");
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "srm-supplier", "SEARCH");
        RequestPostProcessor r = accounts.as(comp, reader, "reader1");
        mvc.perform(get(API + "/" + v).with(r)).andExpect(status().isOk());
        mvc.perform(get(API + "/items").with(r)).andExpect(status().isOk());
        mvc.perform(json(put(API + "/" + v), "{\"currencyCd\":\"KRW\",\"rowVer\":0}").with(r)).andExpect(status().isForbidden());
        mvc.perform(json(post(API + "/" + v + "/certs"), "{\"certTypeCd\":\"SQ\",\"issuedOn\":\"2024-01-01\"}").with(r))
                .andExpect(status().isForbidden());
        mvc.perform(json(post(API + "/" + v + "/prices"), "{\"itemCd\":\"BOLT-M8\",\"unitPrice\":\"1\",\"validFrom\":\"2026-01-01\"}").with(r))
                .andExpect(status().isForbidden());
        long none = accounts.newUser(comp, "none1", PW);
        mvc.perform(get(API).with(accounts.as(comp, none, "none1"))).andExpect(status().isForbidden());
        // 멱등키: 같은 키로 인증서 두 번 → 두 번째 409, 행은 1개
        String body = "{\"certTypeCd\":\"SQ\",\"issuedOn\":\"2024-01-01\"}";
        mvc.perform(json(post(API + "/" + v + "/certs"), body).header("X-Idempotency-Key", "srm-cert-0001").with(admin))
                .andExpect(status().isOk());
        mvc.perform(json(post(API + "/" + v + "/certs"), body).header("X-Idempotency-Key", "srm-cert-0001").with(admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        assertThat(accounts.query(comp, "select count(*) from srm_supplier_cert where cust_id = ?", Integer.class, v)).isEqualTo(1);
        // 변경 감사: 단가 마감도 감사에 남는다
        id(postJson(API + "/" + v + "/prices", "{\"itemCd\":\"BOLT-M8\",\"unitPrice\":\"1\",\"validFrom\":\"2026-01-01\"}"));
        id(postJson(API + "/" + v + "/prices", "{\"itemCd\":\"BOLT-M8\",\"unitPrice\":\"2\",\"validFrom\":\"2026-02-01\"}"));
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'srm_price' and action = 'UPDATE'",
                Integer.class)).isEqualTo(1);
    }
}
