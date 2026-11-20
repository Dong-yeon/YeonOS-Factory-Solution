package io.smarthappy.module.master.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.text.Normalizer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA P1 6단계 — 특성 마스터·불량 유형·용어 사전 이상 사용 (docs/qa/char-6.md). QualityTest 에 없는 것만:
 * 경계값·유니코드, 계량↔계수 전환, 사용 중지 참조(특별특성 회사 코드·품목·공정), 번호 유일 경계, LIKE 특수문자·정렬, 엑셀 조건,
 * 동시성, 직접 SQL, A사로 B사(모든 API·참조), 권한 조합·변경 이력.
 *
 * - {@code 결함n_…} = 고친 뒤의 기대 동작. 개발 세션이 고치고 @Disabled 를 풀었다(2026-10-20, V029).
 *   옛 동작을 고정하던 {@code 관찰_결함n_재현_…} 은 지웠다. 결함 #2(화면 select)는 브라우저로 확인.
 */
@AutoConfigureMockMvc
class QualityAbuseTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String C = "/api/master/chars";
    private static final String D = "/api/master/defects";
    private static final String T = "/api/master/terms";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    long adminId;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("QQ");
        adminId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, adminId, "MASTER", "mst-char", "SEARCH", "SAVE", "EXCEL");
        accounts.grant(comp, adminId, "MASTER", "mst-defect", "SEARCH", "SAVE");
        accounts.grant(comp, adminId, "MASTER", "mst-term", "SEARCH", "SAVE");
        admin = accounts.as(comp, adminId, "admin1");
        seed(comp);
    }

    private void seed(String c) {
        for (String it : new String[] {"FG1", "FG2"}) {
            accounts.exec(c, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                    values (?, ?, ?, 'FG', 'MAKE', 'EA', 't', 't')""", c, it, it + " 품목");
        }
        accounts.exec(c, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'SPOT', '스폿 용접', 'WELD', 't', 't'), (?, 'DRAW', '드로잉', 'PRESS', 't', 't')""", c, c);
    }

    // ------------------------------------------------------------------ 도우미

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

    /** JSON 문자열 값 (탭·따옴표·역슬래시 이스케이프, 그 밖 유니코드는 그대로) */
    private static String js(String v) {
        return v == null ? "null" : "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\t", "\\t").replace("\n", "\\n") + "\"";
    }

    private ResultActions charWith(String extra) throws Exception {
        return postJson(C, "{\"itemCd\":\"FG1\",\"charNm\":\"홀 지름\",\"lsl\":\"1\"," + extra + "}");
    }

    private ResultActions spec(String no, int scale, String lsl, String nominal, String usl) throws Exception {
        return postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"" + no + "\",\"charNm\":\"x\",\"scale\":" + scale + ",\"lsl\":" + js(lsl)
                + ",\"nominal\":" + js(nominal) + ",\"usl\":" + js(usl) + "}");
    }

    private String errCode(ResultActions r) throws Exception {
        return JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.errors[0].code");
    }

    private int rowVer(long charId) {
        return accounts.query(comp, "select row_ver from qms_char where char_id = ?", Integer.class, charId);
    }

    private ResultActions alias(String term, String alias) throws Exception {
        return postJson(T + "/aliases", "{\"termCd\":\"" + term + "\",\"alias\":" + js(alias) + "}");
    }

    private RequestPostProcessor otherCompUser(String other) {
        long b = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, b, "MASTER", "mst-char", "SEARCH", "SAVE", "EXCEL");
        accounts.grant(other, b, "MASTER", "mst-defect", "SEARCH", "SAVE");
        accounts.grant(other, b, "MASTER", "mst-term", "SEARCH", "SAVE");
        return accounts.as(other, b, "admin1");
    }

    private CompletableFuture<Void> holdTx(Runnable work, CountDownLatch done, long holdMs) {
        return CompletableFuture.runAsync(() -> runner.run(comp, () -> {
            work.run();
            done.countDown();
            try {
                Thread.sleep(holdMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    private CompletableFuture<MvcResult> async(MockHttpServletRequestBuilder req) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(req).andReturn();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // =====================================================================
    // 1. 특성 경계값 — 번호·이름
    // =====================================================================

    @Test
    void 번호_길이20_통과_21_형식오류_전각숫자_공백포함_형식오류_앞뒤공백은_제거() throws Exception {
        id(charWith("\"charNo\":\"" + "A".repeat(20) + "\""));
        assertThat(errCode(charWith("\"charNo\":\"" + "A".repeat(21) + "\""))).isEqualTo("FORMAT");
        assertThat(errCode(charWith("\"charNo\":\"１２\""))).isEqualTo("FORMAT");              // 전각 숫자
        assertThat(errCode(charWith("\"charNo\":\"1 2\""))).isEqualTo("FORMAT");
        assertThat(errCode(charWith("\"charNo\":\"-1\""))).isEqualTo("FORMAT");               // 첫 글자 기호
        long c = id(charWith("\"charNo\":\" 7a \""));
        mvc.perform(get(C + "/" + c).with(admin)).andExpect(jsonPath("$.data.charNo").value("7A"));
        assertThat(errCode(charWith("\"charNo\":\"\\u3000\""))).isEqualTo("REQUIRED");         // 전각 공백만
    }

    @Test
    void 관찰_번호의_비ASCII_글자가_대문자_변환으로_ASCII_가_되어_저장된다() throws Exception {
        // 'ı'(터키어 점 없는 i)·'ß'·'ﬁ' 는 Locale.ROOT 대문자에서 I·SS·FI 가 된다 — 입력과 다른 번호가 조용히 저장
        long c1 = id(charWith("\"charNo\":\"ı1\""));
        long c2 = id(charWith("\"charNo\":\"ß\""));
        long c3 = id(charWith("\"charNo\":\"ﬁ2\""));
        assertThat(accounts.query(comp, "select string_agg(char_no, ',' order by char_id) from qms_char where char_id in (?, ?, ?)",
                String.class, c1, c2, c3)).isEqualTo("I1,SS,FI2");
    }

    @Test
    void 이름_길이200_통과_201_TOO_LONG_전각공백만_REQUIRED() throws Exception {
        id(postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"1\",\"charNm\":\"" + "가".repeat(200) + "\",\"lsl\":\"1\"}"));
        postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"2\",\"charNm\":\"" + "가".repeat(201) + "\",\"lsl\":\"1\"}")
                .andExpect(jsonPath("$.errors[0].field").value("charNm")).andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"3\",\"charNm\":\"\u3000\u3000\",\"lsl\":\"1\"}")
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"4\",\"charNm\":\"\\t\",\"lsl\":\"1\"}")
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
    }

    @Test
    void 관찰_이름_NBSP_폭없는공백만이면_보이지_않는_이름이_저장된다() throws Exception {
        // Java isBlank·strip 은 NBSP(U+00A0)·ZWSP(U+200B) 를 공백으로 보지 않고, DB CHECK(btrim)도 ASCII 공백만 지운다
        long c1 = id(postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"1\",\"charNm\":\"\u00A0\",\"lsl\":\"1\"}"));
        long c2 = id(postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"2\",\"charNm\":\"\u200B\",\"lsl\":\"1\"}"));
        assertThat(accounts.query(comp, "select count(*) from qms_char where char_id in (?, ?)", Integer.class, c1, c2)).isEqualTo(2);
    }

    @Test
    void 관찰_이름_길이는_UTF16_단위라_이모지는_DB_한도의_절반에서_TOO_LONG() throws Exception {
        // DB varchar(200) 는 코드포인트 200자를 받지만 서버는 String.length()(UTF-16) 로 센다 — 더 엄격한 쪽이라 안전
        String emoji = "\uD83D\uDD27".repeat(101);    // 🔧 101자 = UTF-16 202
        postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"1\",\"charNm\":\"" + emoji + "\",\"lsl\":\"1\"}")
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        id(postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"1\",\"charNm\":\"" + "\uD83D\uDD27".repeat(100) + "\",\"lsl\":\"1\"}"));
    }

    // =====================================================================
    // 2. 특성 경계값 — 수치·자릿수
    // =====================================================================

    @Test
    void 음수_0_규격과_규격글자() throws Exception {
        long n = id(spec("1", 2, "-5", "-3", "-1"));
        long z = id(spec("2", 2, "0", null, null));
        long z2 = id(spec("3", 1, "-0.0", null, "0.5"));
        mvc.perform(get(C + "/" + n).with(admin)).andExpect(jsonPath("$.data.specText").value("-5.00 ~ -1.00"));
        mvc.perform(get(C + "/" + z).with(admin)).andExpect(jsonPath("$.data.specText").value("≥ 0.00"));
        mvc.perform(get(C + "/" + z2).with(admin)).andExpect(jsonPath("$.data.specText").value("0.0 ~ 0.5"));
        assertThat(accounts.query(comp, "select lsl::text from qms_char where char_id = ?", String.class, z2)).isEqualTo("0.000000");
    }

    @Test
    void 아주_큰값_정수12자리_통과_13자리_INT_DIGITS_지수_점시작_전각_FORMAT() throws Exception {
        long big = id(spec("1", 6, "-999999999999.999999", null, "999999999999.999999"));
        mvc.perform(get(C + "/" + big).with(admin))
                .andExpect(jsonPath("$.data.specText").value("-999999999999.999999 ~ 999999999999.999999"));
        assertThat(errCode(spec("2", 2, "1000000000000", null, null))).isEqualTo("INT_DIGITS");
        assertThat(errCode(spec("2", 2, "1e3", null, null))).isEqualTo("FORMAT");
        assertThat(errCode(spec("2", 2, "1E+3", null, null))).isEqualTo("FORMAT");
        assertThat(errCode(spec("2", 2, ".5", null, null))).isEqualTo("FORMAT");
        assertThat(errCode(spec("2", 2, "5.", null, null))).isEqualTo("FORMAT");
        assertThat(errCode(spec("2", 2, "+5", null, null))).isEqualTo("FORMAT");
        assertThat(errCode(spec("2", 2, "１０", null, null))).isEqualTo("FORMAT");
        assertThat(errCode(spec("2", 2, "1,000", null, null))).isEqualTo("FORMAT");
        assertThat(errCode(spec("2", 2, "NaN", null, null))).isEqualTo("FORMAT");
        id(spec("3", 2, " 5 ", null, null));                                                // 앞뒤 공백은 제거
    }

    @Test
    void 자릿수_0과_6_경계_뒤쪽0은_자릿수로_세지_않는다() throws Exception {
        long s0 = id(spec("1", 0, "10", null, "20"));
        mvc.perform(get(C + "/" + s0).with(admin)).andExpect(jsonPath("$.data.specText").value("10 ~ 20"));
        assertThat(errCode(spec("2", 0, "10.5", null, null))).isEqualTo("SCALE_SHORT");
        id(spec("3", 1, "10.50", null, null));                                              // 10.50 = 10.5 → 자릿수 1 로 충분
        long s6 = id(spec("4", 6, "0.000001", null, null));
        mvc.perform(get(C + "/" + s6).with(admin)).andExpect(jsonPath("$.data.specText").value("≥ 0.000001"));
        spec("5", 6, "0.0000001", null, null).andExpect(jsonPath("$.errors[0].field").value("lsl"))
                .andExpect(jsonPath("$.errors[0].code").value("SCALE"));
        assertThat(errCode(spec("5", -1, "1", null, null))).isEqualTo("CODE");
        assertThat(errCode(spec("5", 7, "1", null, null))).isEqualTo("CODE");
    }

    @Test
    void 자릿수_정수칸에_아주큰수_문자_배열은_400_500아님_소수는_잘림() throws Exception {
        for (String scale : new String[] {"99999999999", "\"x\"", "[2]"}) {
            postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\",\"scale\":" + scale + "}")
                    .andExpect(status().isBadRequest());
        }
        // 관찰: 소수 2.5 는 Jackson 이 2 로 잘라 받는다(오류 없음) — 화면은 정수만 보내 무해
        long cut = id(postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"3\",\"charNm\":\"x\",\"lsl\":\"1\",\"scale\":2.5}"));
        assertThat(accounts.query(comp, "select scale from qms_char where char_id = ?", Integer.class, cut)).isEqualTo(2);
        // 수치 칸에 JSON 숫자를 보내도 받는다(문자열로 바뀜)
        long c = id(postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"2\",\"charNm\":\"x\",\"lsl\":9.95,\"usl\":10.05}"));
        mvc.perform(get(C + "/" + c).with(admin)).andExpect(jsonPath("$.data.specText").value("9.95 ~ 10.05"));
    }

    // =====================================================================
    // 3. 계량 ↔ 계수 전환
    // =====================================================================

    @Test
    void 계량에서_계수로_수치는_지워지고_다시_계량은_규격필수_감사3건() throws Exception {
        long c = id(postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"1\",\"charNm\":\"x\",\"unitNm\":\"mm\",\"scale\":3,"
                + "\"lsl\":\"9.95\",\"nominal\":\"10\",\"usl\":\"10.05\"}"));
        // 계수로 바꾸며 수치를 그대로 보내면 알린다(조용히 버리지 않음)
        putJson(C + "/" + c, "{\"charNo\":\"1\",\"charNm\":\"x\",\"dataType\":\"ATTRIBUTE\",\"lsl\":\"9.95\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[0].code").value("ATTR_NO_SPEC"));
        putJson(C + "/" + c, "{\"charNo\":\"1\",\"charNm\":\"x\",\"dataType\":\"ATTRIBUTE\",\"unitNm\":\"mm\",\"scale\":3,"
                + "\"lsl\":\"\",\"nominal\":null,\"rowVer\":0}").andExpect(status().isOk());
        mvc.perform(get(C + "/" + c).with(admin)).andExpect(jsonPath("$.data.specText").value("OK/NG"))
                .andExpect(jsonPath("$.data.lsl").isEmpty()).andExpect(jsonPath("$.data.nominal").isEmpty())
                .andExpect(jsonPath("$.data.usl").isEmpty())
                // 관찰: 계수인데 단위·자릿수가 남는다(화면도 숨기지 않음) — 측정 화면이 계수에 자릿수를 쓰지 않으면 무해
                .andExpect(jsonPath("$.data.unitNm").value("mm")).andExpect(jsonPath("$.data.scale").value(3));
        putJson(C + "/" + c, "{\"charNo\":\"1\",\"charNm\":\"x\",\"dataType\":\"VARIABLE\",\"rowVer\":1}")
                .andExpect(jsonPath("$.errors[0].code").value("SPEC_LIMIT"));
        putJson(C + "/" + c, "{\"charNo\":\"1\",\"charNm\":\"x\",\"dataType\":\"VARIABLE\",\"scale\":3,\"usl\":\"0.3\",\"rowVer\":1}")
                .andExpect(status().isOk());
        mvc.perform(get(C + "/" + c).with(admin)).andExpect(jsonPath("$.data.specText").value("≤ 0.300"));
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'qms_char' and row_key = ?",
                Long.class, String.valueOf(c))).isEqualTo(3);
        // 데이터 유형 소문자·알 수 없는 값
        putJson(C + "/" + c, "{\"charNo\":\"1\",\"charNm\":\"x\",\"dataType\":\"attribute\",\"rowVer\":2}")
                .andExpect(jsonPath("$.errors[0].code").value("CODE"));
    }

    // =====================================================================
    // 4. 사용 중지된 참조 — 특별특성 회사 코드·품목·공정
    // =====================================================================

    private long charWithCompSpecial() throws Exception {
        accounts.exec(comp, """
                insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by)
                values (?, 'SPECIAL_CHAR', 'HMC-S', 'Ⓢ(현대)', 't', 't')""", comp);
        long c = id(postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\",\"specialCls\":\"HMC-S\"}"));
        accounts.exec(comp, "update sys_comp_code set use_yn = 'N' where grp_cd = 'SPECIAL_CHAR' and code = 'HMC-S'");
        return c;
    }

    @Test
    void 결함1_사용중지된_특별특성코드를_그대로_두고_다른칸_수정_사용중지는_된다() throws Exception {
        long c = charWithCompSpecial();
        putJson(C + "/" + c, "{\"charNo\":\"1\",\"charNm\":\"이름 변경\",\"lsl\":\"1\",\"specialCls\":\"HMC-S\",\"rowVer\":0}")
                .andExpect(status().isOk());
        putJson(C + "/" + c, "{\"charNo\":\"1\",\"charNm\":\"이름 변경\",\"lsl\":\"1\",\"specialCls\":\"HMC-S\",\"useYn\":\"N\",\"rowVer\":1}")
                .andExpect(status().isOk());
        // 새로 그 코드를 고르는 것은 계속 막는다
        postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"2\",\"charNm\":\"x\",\"lsl\":\"1\",\"specialCls\":\"HMC-S\"}")
                .andExpect(jsonPath("$.errors[0].field").value("specialCls"));
    }


    @Test
    void 사용중지_품목_공정의_특성도_다른칸_수정_사용중지_가능() throws Exception {
        long c = id(postJson(C, "{\"itemCd\":\"FG1\",\"procCd\":\"DRAW\",\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\"}"));
        long p = id(postJson(C, "{\"charType\":\"PROCESS\",\"procCd\":\"SPOT\",\"charNo\":\"1\",\"charNm\":\"전류\",\"lsl\":\"8\"}"));
        accounts.exec(comp, "update mst_item set use_yn = 'N' where item_cd = 'FG1'");
        accounts.exec(comp, "update mst_proc set use_yn = 'N' where proc_cd in ('DRAW', 'SPOT')");
        putJson(C + "/" + c, "{\"itemCd\":\"FG1\",\"procCd\":\"DRAW\",\"charNo\":\"1\",\"charNm\":\"y\",\"lsl\":\"2\",\"rowVer\":0}")
                .andExpect(status().isOk());
        putJson(C + "/" + p, "{\"procCd\":\"spot\",\"charNo\":\"1\",\"charNm\":\"y\",\"lsl\":\"8\",\"useYn\":\"N\",\"rowVer\":0}")
                .andExpect(status().isOk());
        // 제품 특성의 공정을 다른 사용 중지 공정으로 바꾸기는 INACTIVE, 새 특성은 사용 중지 품목·공정 불가
        putJson(C + "/" + c, "{\"procCd\":\"SPOT\",\"charNo\":\"1\",\"charNm\":\"y\",\"lsl\":\"2\",\"rowVer\":1}")
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"9\",\"charNm\":\"x\",\"lsl\":\"1\"}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        postJson(C, "{\"charType\":\"PROCESS\",\"procCd\":\"SPOT\",\"charNo\":\"9\",\"charNm\":\"x\",\"lsl\":\"1\"}")
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
    }

    @Test
    void 결함3_사용중지_품목_공정의_특성은_다시_사용할_수_없다() throws Exception {
        long c = id(postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\",\"useYn\":\"N\"}"));
        long p = id(postJson(C, "{\"charType\":\"PROCESS\",\"procCd\":\"SPOT\",\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\",\"useYn\":\"N\"}"));
        accounts.exec(comp, "update mst_item set use_yn = 'N' where item_cd = 'FG1'");
        accounts.exec(comp, "update mst_proc set use_yn = 'N' where proc_cd = 'SPOT'");
        putJson(C + "/" + c, "{\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\",\"useYn\":\"Y\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[0].field").value("itemCd")).andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        putJson(C + "/" + p, "{\"procCd\":\"SPOT\",\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\",\"useYn\":\"Y\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[0].field").value("procCd")).andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
    }


    // =====================================================================
    // 5. 번호 유일 경계 — 공정 특성(품목 있음/없음) ↔ 제품 특성
    // =====================================================================

    @Test
    void 결함4_품목있는_공정특성_번호는_공정안에서_유일_제품특성_번호와_별개() throws Exception {
        id(postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"1\",\"charNm\":\"홀 지름\",\"lsl\":\"1\"}"));
        id(postJson(C, "{\"charType\":\"PROCESS\",\"itemCd\":\"FG1\",\"procCd\":\"SPOT\",\"charNo\":\"1\",\"charNm\":\"FG1 용접 전류\",\"lsl\":\"8\"}"));
        id(postJson(C, "{\"charType\":\"PROCESS\",\"itemCd\":\"FG1\",\"procCd\":\"DRAW\",\"charNo\":\"1\",\"charNm\":\"FG1 쿠션 압력\",\"lsl\":\"8\"}"));
        postJson(C, "{\"charType\":\"PROCESS\",\"itemCd\":\"FG1\",\"procCd\":\"SPOT\",\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"8\"}")
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
    }


    // =====================================================================
    // 6. 정렬·검색 (LIKE 특수문자)
    // =====================================================================

    @Test
    void 검색어의_퍼센트_밑줄_역슬래시는_글자_그대로() throws Exception {
        String[] names = {"100% 검사", "A_B 간격", "경로 C:\\X", "100 검사", "AXB 간격"};
        for (int i = 0; i < names.length; i++) {
            id(postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"" + (i + 1) + "\",\"charNm\":" + js(names[i]) + ",\"lsl\":\"1\"}"));
        }
        mvc.perform(get(C).param("keyword", "%").with(admin)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(C).param("keyword", "_").with(admin)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(C).param("keyword", "\\").with(admin)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(C).param("keyword", "a_b").with(admin)).andExpect(jsonPath("$.list[0].charNm").value("A_B 간격"));
        mvc.perform(get(C).param("itemCd", "F_1").with(admin)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(C).param("itemCd", "%").with(admin)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(C).param("keyword", "가".repeat(201)).with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(C).param("charType", "LOT").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(C).param("sort", "charNm,desc").with(admin))
                .andExpect(jsonPath("$.list[0].charNm").value("경로 C:\\X"));
        mvc.perform(get(C).param("sort", "remark,asc").with(admin)).andExpect(status().isBadRequest());
    }

    @Test
    void 관찰_번호정렬은_정렬순서_다음_글자순_1_10_2() throws Exception {
        for (String no : new String[] {"2", "10", "1"}) {
            id(postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"" + no + "\",\"charNm\":\"x\",\"lsl\":\"1\"}"));
        }
        mvc.perform(get(C).param("itemCd", "FG1").with(admin)).andExpect(jsonPath("$.list[*].charNo").value(contains("1", "10", "2")));
    }

    // =====================================================================
    // 7. 엑셀 내보내기 — 같은 조건
    // =====================================================================

    private Sheet[] export(String body) throws Exception {
        MvcResult r = postJson(C + "/export", body).andExpect(status().isOk()).andReturn();
        XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()));
        return new Sheet[] {wb.getSheetAt(0), wb.getSheetAt(wb.getNumberOfSheets() - 1)};
    }

    @Test
    void 엑셀은_목록과_같은_조건_공정_유형_사용중지포함_키워드특수문자() throws Exception {
        id(postJson(C, "{\"itemCd\":\"FG1\",\"procCd\":\"DRAW\",\"charNo\":\"1\",\"charNm\":\"100% 두께\",\"lsl\":\"1\"}"));
        id(postJson(C, "{\"itemCd\":\"FG1\",\"procCd\":\"DRAW\",\"charNo\":\"2\",\"charNm\":\"100 두께\",\"lsl\":\"1\",\"useYn\":\"N\"}"));
        id(postJson(C, "{\"charType\":\"PROCESS\",\"procCd\":\"DRAW\",\"charNo\":\"1\",\"charNm\":\"100% 압력\",\"lsl\":\"1\"}"));
        id(postJson(C, "{\"itemCd\":\"FG2\",\"charNo\":\"1\",\"charNm\":\"100% 폭\",\"lsl\":\"1\"}"));
        String[][] cases = {
                {"{\"procCd\":\"DRAW\"}", "2"},
                {"{\"procCd\":\"DRAW\",\"withUnused\":true}", "3"},
                {"{\"procCd\":\"DRAW\",\"charType\":\"PROCESS\"}", "1"},
                {"{\"keyword\":\"%\"}", "3"},
                {"{\"procCd\":\"NOPE\"}", "0"}};
        for (String[] k : cases) {
            Sheet[] s = export(k[0]);
            assertThat(s[0].getLastRowNum()).as(k[0]).isEqualTo(Integer.parseInt(k[1]));
            assertThat(s[1].getRow(3).getCell(1).getStringCellValue()).as(k[0]).isEqualTo(k[1]);
        }
        // 목록과 같은 순서(기본 정렬)
        Sheet[] all = export("{\"withUnused\":true}");
        assertThat(all[0].getLastRowNum()).isEqualTo(4);
        assertThat(all[0].getRow(1).getCell(0) == null ? "" : all[0].getRow(1).getCell(0).getStringCellValue()).isEmpty();            // 품목 없는 공정 특성이 먼저(coalesce '')
    }

    @Test
    void 결함5_엑셀_출력정보의_조회조건에_공정_유형_사용중지포함이_적힌다() throws Exception {
        Sheet[] s = export("{\"procCd\":\"DRAW\",\"charType\":\"PROCESS\",\"withUnused\":true}");
        assertThat(s[1].getRow(4).getCell(1).getStringCellValue()).contains("procCd", "charType", "withUnused");
    }


    // =====================================================================
    // 8. 동시성
    // =====================================================================

    @Test
    void 동시_수정_같은_버전_뒤쪽은_기다린뒤_409() throws Exception {
        long c = id(postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\"}"));
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> first = holdTx(() -> jdbc.update(
                "update qms_char set char_nm = 'first', row_ver = row_ver + 1 where char_id = ? and row_ver = 0", c), locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcResult> second = async(json(put(C + "/" + c),
                "{\"charNo\":\"1\",\"charNm\":\"second\",\"lsl\":\"1\",\"rowVer\":0}").with(admin));
        first.get(10, TimeUnit.SECONDS);
        assertThat(second.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        assertThat(accounts.query(comp, "select char_nm from qms_char where char_id = ?", String.class, c)).isEqualTo("first");
        assertThat(rowVer(c)).isEqualTo(1);
    }

    @Test
    void 동시_같은번호_특성_생성_뒤쪽은_기다린뒤_DUPLICATE_칸오류() throws Exception {
        long item = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG1'", Long.class);
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> first = holdTx(() -> jdbc.update("""
                insert into qms_char (comp_cd, char_type, item_id, char_no, char_nm, lsl, created_by, updated_by)
                values (?, 'PRODUCT', ?, '7', 'x', 1, 't', 't')""", comp, item), locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcResult> second = async(json(post(C), "{\"itemCd\":\"FG1\",\"charNo\":\"7\",\"charNm\":\"y\",\"lsl\":\"1\"}")
                .with(admin));
        first.get(10, TimeUnit.SECONDS);
        MvcResult r = second.get(10, TimeUnit.SECONDS);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("DUPLICATE", "charNo");
    }

    @Test
    void 동시_같은_동의어를_다른_용어에_추가_뒤쪽은_기다린뒤_DUPLICATE() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> first = holdTx(() -> jdbc.update("""
                insert into mst_term_alias (comp_cd, term_cd, alias, created_by, updated_by)
                values (?, 'CYCLE_TIME', '쇼트', 't', 't')""", comp), locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcResult> second = async(json(post(T + "/aliases"), "{\"termCd\":\"OEE\",\"alias\":\" 쇼트 \"}").with(admin));
        first.get(10, TimeUnit.SECONDS);
        MvcResult r = second.get(10, TimeUnit.SECONDS);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("DUPLICATE");
    }

    @Test
    void 동시_불량유형_같은코드_생성_뒤쪽은_DUPLICATE() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> first = holdTx(() -> jdbc.update("""
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'BURR', '버', 'APPEARANCE', 't', 't')""", comp), locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcResult> second = async(json(post(D), "{\"defectCd\":\"burr\",\"defectNm\":\"버\",\"catCd\":\"APPEARANCE\"}")
                .with(admin));
        first.get(10, TimeUnit.SECONDS);
        MvcResult r = second.get(10, TimeUnit.SECONDS);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("DUPLICATE");
    }

    // =====================================================================
    // 9. 불량 유형
    // =====================================================================

    @Test
    void 불량유형_상위바꾸면_목록위치_이동_정렬_경계_사용중지_다시사용() throws Exception {
        long a = id(postJson(D, "{\"defectCd\":\"A1\",\"defectNm\":\"a\",\"catCd\":\"OTHER\",\"sortNo\":1}"));
        id(postJson(D, "{\"defectCd\":\"B1\",\"defectNm\":\"b\",\"catCd\":\"WELD\",\"sortNo\":20}"));
        id(postJson(D, "{\"defectCd\":\"B2\",\"defectNm\":\"b\",\"catCd\":\"WELD\",\"sortNo\":10}"));
        id(postJson(D, "{\"defectCd\":\"B0\",\"defectNm\":\"b\",\"catCd\":\"WELD\",\"sortNo\":10}"));
        mvc.perform(get(D).with(admin)).andExpect(jsonPath("$.data[*].defectCd").value(contains("B0", "B2", "B1", "A1")));
        putJson(D + "/" + a, "{\"defectNm\":\"a\",\"catCd\":\"APPEARANCE\",\"sortNo\":1,\"rowVer\":0}").andExpect(status().isOk());
        mvc.perform(get(D).with(admin)).andExpect(jsonPath("$.data[*].defectCd").value(contains("A1", "B0", "B2", "B1")));
        putJson(D + "/" + a, "{\"defectNm\":\"a\",\"catCd\":\"APPEARANCE\",\"useYn\":\"N\",\"rowVer\":1}").andExpect(status().isOk());
        putJson(D + "/" + a, "{\"defectNm\":\"a\",\"catCd\":\"APPEARANCE\",\"useYn\":\"Y\",\"rowVer\":2}").andExpect(status().isOk());
        putJson(D + "/" + a, "{\"defectNm\":\"a\",\"catCd\":\"APPEARANCE\",\"rowVer\":2}").andExpect(status().isConflict());
        // 경계
        assertThat(errCode(postJson(D, "{\"defectCd\":\"S1\",\"defectNm\":\"x\",\"catCd\":\"WELD\",\"sortNo\":-1}"))).isEqualTo("CODE");
        assertThat(errCode(postJson(D, "{\"defectCd\":\"S1\",\"defectNm\":\"x\",\"catCd\":\"WELD\",\"sortNo\":100000}"))).isEqualTo("CODE");
        assertThat(errCode(postJson(D, "{\"defectCd\":\"S1\",\"defectNm\":\"x\",\"catCd\":\"weld\"}"))).isEqualTo("CODE");
        assertThat(errCode(postJson(D, "{\"defectCd\":\"S1\",\"defectNm\":\"" + "가".repeat(101) + "\",\"catCd\":\"WELD\"}")))
                .isEqualTo("TOO_LONG");
        id(postJson(D, "{\"defectCd\":\"" + "Z".repeat(20) + "\",\"defectNm\":\"" + "가".repeat(100) + "\",\"catCd\":\"WELD\"}"));
        assertThat(errCode(postJson(D, "{\"defectCd\":\"" + "Z".repeat(21) + "\",\"defectNm\":\"x\",\"catCd\":\"WELD\"}"))).isEqualTo("FORMAT");
        assertThat(errCode(postJson(D, "{\"defectCd\":\"A.B\",\"defectNm\":\"x\",\"catCd\":\"WELD\"}"))).isEqualTo("FORMAT");
        mvc.perform(get(D).param("useYn", "y").with(admin)).andExpect(status().isBadRequest());
        putJson(D + "/999999999", "{\"defectNm\":\"a\",\"catCd\":\"WELD\",\"rowVer\":0}").andExpect(status().isNotFound());
    }

    // =====================================================================
    // 10. 용어 사전 — 동의어 정규화 경계
    // =====================================================================

    @Test
    void 동의어_탭_줄바꿈_앞뒤전각공백은_같은말_대소문자무시() throws Exception {
        id(alias("DEFECT_RATE", "B급률"));
        assertThat(errCode(alias("DEFECT_RATE", "\tB급률\n"))).isEqualTo("DUPLICATE");
        assertThat(errCode(alias("DEFECT_RATE", "\u3000B급률\u3000"))).isEqualTo("DUPLICATE");      // strip 은 전각 공백도 지움
        id(alias("CYCLE_TIME", "택트 타임"));
        assertThat(errCode(alias("CYCLE_TIME", "택트\t타임"))).isEqualTo("DUPLICATE");
        assertThat(errCode(alias("CYCLE_TIME", "택트 \n 타임"))).isEqualTo("DUPLICATE");
        id(alias("OEE", "Oee Total"));
        assertThat(errCode(alias("OEE", "OEE TOTAL"))).isEqualTo("DUPLICATE");
        assertThat(errCode(alias("OEE", "\u3000"))).isEqualTo("REQUIRED");
    }

    @Test
    void 동의어_터키어_i_대소문자_DB_lower_는_로캘_영향없이_같은말() throws Exception {
        // Java 는 대소문자를 바꾸지 않고 DB lower() 로만 비교 — 서버 기본 로캘(tr)과 무관. "I" ↔ "i" 는 같은 말
        id(alias("OEE", "LINE OEE"));
        assertThat(errCode(alias("OEE", "line oee"))).isEqualTo("DUPLICATE");
        // 점 있는 대문자 İ(U+0130)·점 없는 ı(U+0131) 는 DB lower 에서 i 와 다른 글자 → 별개 동의어
        id(alias("OEE", "İSG"));
        id(alias("OEE", "ısg"));
        assertThat(accounts.query(comp, "select count(*) from mst_term_alias where term_cd = 'OEE'", Integer.class)).isEqualTo(3);
    }

    @Test
    void 결함6_가운데_전각공백_NBSP_NFD_한글도_같은말로_DUPLICATE() throws Exception {
        id(alias("DEFECT_RATE", "B 급률"));
        assertThat(errCode(alias("DEFECT_RATE", "B\u3000급률"))).isEqualTo("DUPLICATE");
        assertThat(errCode(alias("DEFECT_RATE", "B\u00A0급률"))).isEqualTo("DUPLICATE");
        id(alias("SCRAP_QTY", "폐기"));
        assertThat(errCode(alias("SCRAP_QTY", Normalizer.normalize("폐기", Normalizer.Form.NFD)))).isEqualTo("DUPLICATE");
        assertThat(errCode(alias("SCRAP_QTY", "\u00A0"))).isEqualTo("REQUIRED");
    }


    @Test
    void 동의어_길이100_언어코드_경계() throws Exception {
        id(alias("OEE", "가".repeat(100)));
        assertThat(errCode(alias("OEE", "나".repeat(101)))).isEqualTo("TOO_LONG");
        // 관찰: 이모지 51자(UTF-16 102)는 DB 한도(100자) 안이지만 서버가 TOO_LONG — 더 엄격한 쪽
        assertThat(errCode(alias("OEE", "\uD83D\uDD27".repeat(51)))).isEqualTo("TOO_LONG");
        for (String lang : new String[] {"KO", "ko ", "jp", "ko-KR"}) {
            postJson(T + "/aliases", "{\"termCd\":\"OEE\",\"alias\":\"x" + lang.strip() + "\",\"lang\":" + js(lang) + "}")
                    .andExpect(jsonPath("$.errors[0].field").value("lang"));
        }
        long en = id(postJson(T + "/aliases", "{\"termCd\":\"OEE\",\"alias\":\"overall eff\",\"lang\":\"en\"}"));
        // 언어가 달라도 같은 말은 하나(한 말이 두 용어를 가리키지 않게)
        postJson(T + "/aliases", "{\"termCd\":\"OEE\",\"alias\":\"Overall Eff\",\"lang\":\"vi\"}")
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        // 언어만 바꾸기
        putJson(T + "/aliases/" + en, "{\"alias\":\"overall eff\",\"lang\":\"vi\",\"rowVer\":0}").andExpect(status().isOk());
        mvc.perform(get(T + "/NOPE/aliases").with(admin)).andExpect(status().isNotFound());
        mvc.perform(get(T + "/oee/aliases").with(admin)).andExpect(status().isNotFound());              // 용어 코드는 대문자 그대로
    }

    @Test
    void 결함7_사용중지한_동의어의_말은_다른_용어에_다시_쓸_수_있다() throws Exception {
        long a = id(alias("SCRAP_QTY", "B급"));                                              // 잘못 붙임
        putJson(T + "/aliases/" + a, "{\"alias\":\"B급\",\"useYn\":\"N\",\"rowVer\":0}").andExpect(status().isOk());
        id(alias("DEFECT_RATE", "B급"));
        // 옛 것을 다시 사용하려 하면 그때 DUPLICATE
        putJson(T + "/aliases/" + a, "{\"alias\":\"B급\",\"useYn\":\"Y\",\"rowVer\":1}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
    }


    // =====================================================================
    // 11. 직접 SQL 우회 (앱 계정)
    // =====================================================================

    @Test
    void 직접SQL_회사바꾸기_다른회사로_넣기_전역표_쓰기_자르기는_42501() throws Exception {
        long c = id(postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\"}"));
        long d = id(postJson(D, "{\"defectCd\":\"A1\",\"defectNm\":\"a\",\"catCd\":\"OTHER\"}"));
        long a = id(alias("OEE", "종합효율"));
        String other = accounts.newComp("QQ");
        for (String sql : new String[] {
                "update qms_char set comp_cd = '" + other + "' where char_id = " + c,
                "update qms_defect set comp_cd = '" + other + "' where defect_id = " + d,
                "update mst_term_alias set comp_cd = '" + other + "' where alias_id = " + a,
                "insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by) values ('" + other
                        + "', 'X', 'x', 'OTHER', 't', 't')",
                "insert into sys_term (term_cd, category, term_ko, term_en, def_ko, def_en) values ('X', 'KPI', 'x', 'x', 'x', 'x')",
                "delete from sys_term where term_cd = 'OEE'",
                "delete from mst_term_alias where alias_id = " + a,
                "truncate qms_char",
                "update sys_code set use_yn = 'N' where grp_cd = 'DEFECT_CAT' and code = 'WELD'"}) {
            assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, sql)))).as(sql).contains("42501");
        }
    }

    @Test
    void 직접SQL_특별특성을_사용중지_코드로_바꾸기_계수에_자릿수범위_번호형식은_DB가_막는다() throws Exception {
        long c = id(postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\"}"));
        accounts.exec(comp, """
                insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, use_yn, created_by, updated_by)
                values (?, 'SPECIAL_CHAR', 'OLD-S', 'old', 'N', 't', 't')""", comp);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update qms_char set special_cls = 'OLD-S' where char_id = ?", c)))).contains("fk_qms_char_special");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update qms_char set scale = 7 where char_id = ?", c)))).contains("ck_qms_char_scale");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update qms_char set char_no = 'a1' where char_id = ?", c)))).contains("ck_qms_char_no");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update qms_char set char_nm = '   ' where char_id = ?", c)))).contains("ck_qms_char_nm");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update qms_char set lsl = 5, usl = 5 where char_id = ?", c)))).contains("ck_qms_char_spec");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into mst_term_alias (comp_cd, term_cd, alias, created_by, updated_by) values (?, 'OEE', '  ', 't', 't')", comp))))
                .contains("ck_mst_term_alias_alias");
    }

    @Test
    void 관찰_직접SQL_유형_품목_바꾸기와_보이지않는_동의어는_DB가_막지_않는다() throws Exception {
        // 서버는 유형·품목을 FIXED 로 막지만 DB 는 회사 일치만 본다(지금은 특성을 참조하는 표가 없어 영향 없음 — P3 측정값 전에 판단)
        long c = id(postJson(C, "{\"itemCd\":\"FG1\",\"procCd\":\"DRAW\",\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\"}"));
        assertThat(accounts.exec(comp, "update qms_char set char_type = 'PROCESS' where char_id = ?", c)).isEqualTo(1);
        assertThat(accounts.exec(comp, "update qms_char set item_id = (select item_id from mst_item where item_cd = 'FG2') where char_id = ?",
                c)).isEqualTo(1);
        // 전각 공백·탭 동의어(서버라면 REQUIRED·정규화) — DB CHECK 는 ASCII 공백만 btrim, 유일 인덱스도 btrim 만
        assertThat(accounts.exec(comp,
                "insert into mst_term_alias (comp_cd, term_cd, alias, created_by, updated_by) values (?, 'OEE', '\u3000', 't', 't')", comp))
                .isEqualTo(1);
        id(alias("OEE", "쇼트"));
        assertThat(accounts.exec(comp,
                "insert into mst_term_alias (comp_cd, term_cd, alias, created_by, updated_by) values (?, 'OEE', E'\\t쇼트', 't', 't')", comp))
                .isEqualTo(1);
    }

    // =====================================================================
    // 12. A사로 B사 — 모든 API·참조
    // =====================================================================

    @Test
    void B사로_A사의_특성_불량_동의어_전부_404_0건_A사_전용_품목_공정_코드는_NOT_FOUND() throws Exception {
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'A-ONLY', 'A사 품목', 'FG', 'MAKE', 'EA', 't', 't')""", comp);
        accounts.exec(comp, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'A-PROC', 'A사 공정', 'WELD', 't', 't')""", comp);
        long ac = id(postJson(C, "{\"itemCd\":\"A-ONLY\",\"procCd\":\"A-PROC\",\"charNo\":\"1\",\"charNm\":\"A사 특성\",\"lsl\":\"1\"}"));
        long ad = id(postJson(D, "{\"defectCd\":\"A-DEF\",\"defectNm\":\"A사 불량\",\"catCd\":\"OTHER\"}"));
        long aa = id(alias("OEE", "A사 종합효율"));

        String other = accounts.newComp("QQ");
        seed(other);
        RequestPostProcessor bu = otherCompUser(other);
        // 특성
        mvc.perform(get(C).param("withUnused", "true").with(bu)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(C).param("keyword", "A사").with(bu)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(C).param("procCd", "A-PROC").with(bu)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(C + "/" + ac).with(bu)).andExpect(status().isNotFound());
        mvc.perform(json(put(C + "/" + ac), "{\"charNo\":\"1\",\"charNm\":\"탈취\",\"lsl\":\"1\",\"rowVer\":0}").with(bu))
                .andExpect(status().isNotFound());
        mvc.perform(get(C + "/item").param("itemCd", "A-ONLY").with(bu)).andExpect(status().isNotFound());
        mvc.perform(get(C + "/procs").with(bu)).andExpect(jsonPath("$.data[*].procCd").value(not(hasItem("A-PROC"))));
        mvc.perform(json(post(C), "{\"itemCd\":\"A-ONLY\",\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\"}").with(bu))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        mvc.perform(json(post(C), "{\"charType\":\"PROCESS\",\"procCd\":\"A-PROC\",\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\"}").with(bu))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        // 다른 회사가 같은 품목코드(FG1)를 써도 자기 품목으로만 붙는다
        long bc = id(mvc.perform(json(post(C), "{\"itemCd\":\"FG1\",\"charNo\":\"1\",\"charNm\":\"B\",\"lsl\":\"1\"}").with(bu)));
        assertThat(accounts.query(other, "select i.comp_cd from qms_char c join mst_item i on i.item_id = c.item_id where c.char_id = ?",
                String.class, bc)).isEqualTo(other);
        MvcResult ex = mvc.perform(json(post(C + "/export"), "{\"withUnused\":true}").with(bu)).andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(ex.getResponse().getContentAsByteArray()))) {
            assertThat(wb.getSheetAt(0).getLastRowNum()).isEqualTo(1);
            assertThat(wb.getSheetAt(0).getRow(1).getCell(4).getStringCellValue()).isEqualTo("B");
        }
        // 변경 이력: A사 특성 키 → B사에게 0건
        mvc.perform(get("/api/platform/audit").param("table", "qms_char").param("key", String.valueOf(ac)).with(bu))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/platform/audit").param("table", "qms_char").param("key", String.valueOf(ac)).with(admin))
                .andExpect(jsonPath("$.total").value(1));
        // 불량 유형
        mvc.perform(get(D).with(bu)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(json(put(D + "/" + ad), "{\"defectNm\":\"탈취\",\"catCd\":\"OTHER\",\"rowVer\":0}").with(bu)).andExpect(status().isNotFound());
        mvc.perform(json(post(D), "{\"defectCd\":\"A-DEF\",\"defectNm\":\"B사 불량\",\"catCd\":\"WELD\"}").with(bu)).andExpect(status().isOk());
        mvc.perform(get("/api/platform/audit").param("table", "qms_defect").param("key", String.valueOf(ad)).with(bu))
                .andExpect(jsonPath("$.total").value(0));
        // 용어: 동의어 수는 자기 회사만
        mvc.perform(get(T).with(bu)).andExpect(jsonPath("$.data[?(@.termCd=='OEE')].aliasCnt").value(hasItem(0)));
        mvc.perform(get(T + "/OEE/aliases").with(bu)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(json(put(T + "/aliases/" + aa), "{\"alias\":\"탈취\",\"useYn\":\"N\",\"rowVer\":0}").with(bu)).andExpect(status().isNotFound());
        // A사 데이터는 그대로
        assertThat(accounts.query(comp, "select char_nm from qms_char where char_id = ?", String.class, ac)).isEqualTo("A사 특성");
        assertThat(accounts.query(comp, "select defect_nm from qms_defect where defect_id = ?", String.class, ad)).isEqualTo("A사 불량");
        assertThat(accounts.query(comp, "select use_yn from mst_term_alias where alias_id = ?", String.class, aa)).isEqualTo("Y");
    }

    @Test
    void B사_특별특성_조회조건에_A사_회사코드_0건_A사_코드로_수정도_불가() throws Exception {
        accounts.exec(comp, """
                insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by)
                values (?, 'SPECIAL_CHAR', 'HMC-S', 'Ⓢ', 't', 't')""", comp);
        id(postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\",\"specialCls\":\"HMC-S\"}"));
        String other = accounts.newComp("QQ");
        seed(other);
        RequestPostProcessor bu = otherCompUser(other);
        long bc = id(mvc.perform(json(post(C), "{\"itemCd\":\"FG1\",\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\"}").with(bu)));
        mvc.perform(get(C).param("specialCls", "HMC-S").with(bu)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(json(put(C + "/" + bc), "{\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\",\"specialCls\":\"HMC-S\",\"rowVer\":0}").with(bu))
                .andExpect(jsonPath("$.errors[0].field").value("specialCls"));
        mvc.perform(get("/api/platform/codes").param("grp", "SPECIAL_CHAR").with(bu))
                .andExpect(jsonPath("$..code").value(not(hasItem("HMC-S"))));
    }

    // =====================================================================
    // 13. 권한 조합·변경 이력
    // =====================================================================

    @Test
    void 권한_조합_엑셀만_저장만_다른화면권한으로_변경이력() throws Exception {
        long c = id(postJson(C, "{\"itemCd\":\"FG1\",\"charNo\":\"1\",\"charNm\":\"x\",\"lsl\":\"1\"}"));
        long d = id(postJson(D, "{\"defectCd\":\"A1\",\"defectNm\":\"a\",\"catCd\":\"OTHER\"}"));
        long a = id(alias("OEE", "종합효율"));

        long u1 = accounts.newUser(comp, "excel1", PW);                // 조회 + 엑셀(저장 없음)
        accounts.grant(comp, u1, "R1", "mst-char", "SEARCH", "EXCEL");
        RequestPostProcessor r1 = accounts.as(comp, u1, "excel1");
        mvc.perform(json(post(C + "/export"), "{}").with(r1)).andExpect(status().isOk());
        mvc.perform(json(put(C + "/" + c), "{\"charNo\":\"1\",\"charNm\":\"y\",\"lsl\":\"1\",\"rowVer\":0}").with(r1))
                .andExpect(status().isForbidden());

        long u2 = accounts.newUser(comp, "save1", PW);                 // 저장만(조회 없음)
        accounts.grant(comp, u2, "R2", "mst-char", "SAVE");
        RequestPostProcessor r2 = accounts.as(comp, u2, "save1");
        mvc.perform(get(C).with(r2)).andExpect(status().isForbidden());
        mvc.perform(get(C + "/" + c).with(r2)).andExpect(status().isForbidden());
        mvc.perform(get(C + "/procs").with(r2)).andExpect(status().isForbidden());
        int saveOnly = mvc.perform(json(put(C + "/" + c), "{\"charNo\":\"1\",\"charNm\":\"저장만\",\"lsl\":\"1\",\"rowVer\":0}").with(r2))
                .andReturn().getResponse().getStatus();
        assertThat(saveOnly).as("저장만 있는 사용자의 수정(관찰: SAVE 가 SEARCH 를 포함하지 않아도 저장은 된다)").isEqualTo(200);

        long u3 = accounts.newUser(comp, "defect1", PW);               // 불량 유형만
        accounts.grant(comp, u3, "R3", "mst-defect", "SEARCH");
        RequestPostProcessor r3 = accounts.as(comp, u3, "defect1");
        mvc.perform(get("/api/platform/audit").param("table", "qms_char").param("key", String.valueOf(c)).with(r3))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/audit").param("table", "mst_term_alias").param("key", String.valueOf(a)).with(r3))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/audit").param("table", "qms_defect").param("key", String.valueOf(d)).with(r3))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(json(put(D + "/" + d), "{\"defectNm\":\"b\",\"catCd\":\"OTHER\",\"rowVer\":0}").with(r3)).andExpect(status().isForbidden());
        mvc.perform(get(T).with(r3)).andExpect(status().isForbidden());
        mvc.perform(get(C + "/item").param("itemCd", "FG1").with(r3)).andExpect(status().isForbidden());
        // 전역 표(sys_term)는 변경 이력 대상 아님
        mvc.perform(get("/api/platform/audit").param("table", "sys_term").param("key", "OEE").with(admin)).andExpect(status().isBadRequest());
        // 변경 이력 내용: 바뀐 칸이 before/after 에 남는다
        putJson(D + "/" + d, "{\"defectNm\":\"a\",\"catCd\":\"WELD\",\"rowVer\":0}").andExpect(status().isOk());
        mvc.perform(get("/api/platform/audit").param("table", "qms_defect").param("key", String.valueOf(d)).with(admin))
                .andExpect(jsonPath("$.list[0].action").value("UPDATE"))
                .andExpect(jsonPath("$.list[0].after").value(org.hamcrest.Matchers.containsString("WELD")));
    }
}
