package io.smarthappy.module.master.classprop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
 * QA P1 7b — 품목 클래스·클래스 속성·attrs jsonb 이상 사용 (docs/qa/ext-class-7.md). ClassPropTest 에 없는 것만:
 * 값의 경계·형태(큰 수·지수·중첩·배열·키 대소문자·많은 키·큰 값·보이지 않는 문자), 속성 정의 변경 뒤 기존 값(사용 중지·선택지 삭제·자릿수),
 * 클래스 바꾸기(품목·설비), 동시성, 직접 SQL, A사로 B사, 권한 조합·변경 이력.
 *
 * - {@code 결함n_…} = 고친 뒤의 기대 동작. 개발 세션이 고치고 @Disabled 를 풀었다(2026-10-20, V031) — 옛 동작을 고정하던 관찰_결함n 은 지웠다.
 */
@AutoConfigureMockMvc
class ClassPropAbuseTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String P = "/api/master/class-props";
    private static final String I = "/api/master/items";
    private static final String E = "/api/master/equips";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    RequestPostProcessor admin;
    long rm1;
    long coil;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("CQ");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-class-prop", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "MASTER", "mst-item", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "MASTER", "mst-equip", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        rm1 = item(comp, "RM1");
        coil = id(postJson(P + "/item-classes", "{\"classCd\":\"COIL\",\"classNm\":\"코일\"}"));
        prop(coil, "{\"propCd\":\"T\",\"propNm\":\"두께\",\"dataType\":\"NUM\",\"scale\":6}");
        prop(coil, "{\"propCd\":\"N0\",\"propNm\":\"정수\",\"dataType\":\"NUM\",\"scale\":0}");
        prop(coil, "{\"propCd\":\"G\",\"propNm\":\"강종\",\"dataType\":\"LIST\",\"options\":[\"A\",\"B\"]}");
        prop(coil, "{\"propCd\":\"M\",\"propNm\":\"제철소\",\"dataType\":\"TEXT\"}");
        prop(coil, "{\"propCd\":\"D\",\"propNm\":\"제조일\",\"dataType\":\"DATE\"}");
    }

    // ---------- 도우미 ----------

    private long item(String c, String cd) {
        return accounts.query(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, ?, ?, 'RM', 'BUY', 'KG', 't', 't') returning item_id""", Long.class, c, cd, cd + " 품목");
    }

    private long prop(long classId, String body) throws Exception {
        return prop("ITEM", classId, body);
    }

    private long prop(String type, long classId, String body) throws Exception {
        return id(postJson(P, body.replaceFirst("\\{", "{\"classType\":\"" + type + "\",\"classId\":" + classId + ",")));
    }

    private long propId(String cd) {
        return accounts.query(comp, "select prop_id from mst_class_prop where prop_cd = ? and class_id = ?", Long.class, cd, coil);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8));
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

    private int itemVer(long itemId) {
        return accounts.query(comp, "select row_ver from mst_item where item_id = ?", Integer.class, itemId);
    }

    private ResultActions save(String attrs) throws Exception {
        return save(rm1, coil, attrs);
    }

    private ResultActions save(long itemId, Long classId, String attrs) throws Exception {
        return putJson(I + "/" + itemId + "/attrs",
                "{\"classId\":" + classId + ",\"attrs\":" + attrs + ",\"rowVer\":" + itemVer(itemId) + "}");
    }

    private String attrsDb() {
        return accounts.query(comp, "select attrs::text from mst_item where item_id = ?", String.class, rm1);
    }

    private String attrsResponse() throws Exception {
        return mvc.perform(get(I + "/" + rm1 + "/attrs").with(admin)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 화면(hb.attrs)이 받은 값을 칸에 넣었다가 다시 보낼 때의 글자 — JS String(number) 와 같은 최단 표기 */
    private static String asBrowserText(Object v) {
        return v instanceof Number n ? new BigDecimal(Double.toString(n.doubleValue())).stripTrailingZeros().toPlainString()
                : String.valueOf(v);
    }

    private String sqlError(String compCd, String sql, Object... args) {
        Throwable t = catchThrowable(() -> accounts.exec(compCd, sql, args));
        return t == null ? "OK" : PgErrors.sqlState(t).orElse("?") + " " + PgErrors.constraint(t).orElse("-");
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
    // 1. 값의 형태 — 숫자
    // =====================================================================

    @Test
    void NUM_지수_중첩_불리언_전각_아랍숫자_큰정수_빈칸() throws Exception {
        save("{\"N0\":1e3}").andExpect(status().isOk());                       // JSON 지수 표기 숫자 = 1000
        assertThat(attrsDb()).isEqualTo("{\"N0\": 1000}");
        save("{\"N0\":1.0}").andExpect(status().isOk());                       // 끝 0 → 정수 자릿수 0 통과
        assertThat(attrsDb()).isEqualTo("{\"N0\": 1}");
        save("{\"N0\":-0}").andExpect(status().isOk());
        save("{\"N0\":\" 12 \"}").andExpect(status().isOk());
        assertThat(attrsDb()).isEqualTo("{\"N0\": 12}");
        save("{\"N0\":1e400}").andExpect(jsonPath("$.errors[0].code").value("INT_DIGITS"));   // BigDecimal 로 읽어 크기 그대로(결함 #2 수정 뒤)
        for (String bad : new String[] {"\"1e3\"", "{\"a\":1}", "[1]", "true", "\"١٢\"", "\"１２\"", "\"1,000\"", "\"NaN\""}) {
            save("{\"N0\":" + bad + "}").andExpect(jsonPath("$.errors[0].field").value("attrs.N0"))
                    .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        }
        save("{\"N0\":12345678901234567890}").andExpect(jsonPath("$.errors[0].code").value("INT_DIGITS"));
        save("{\"N0\":999999999999999}").andExpect(status().isOk());           // 15자리 경계
        save("{\"N0\":1000000000000000}").andExpect(jsonPath("$.errors[0].code").value("INT_DIGITS"));
        save("{\"T\":\"0.000001\"}").andExpect(status().isOk());
        save("{\"T\":\"0.0000001\"}").andExpect(jsonPath("$.errors[0].code").value("SCALE"));
        save("{\"N0\":\"\",\"T\":null}").andExpect(status().isOk());           // 빈 값 = 키 없음
        assertThat(attrsDb()).isEqualTo("{}");
    }

    /** QA O5(결함 #2 로 해결): 아주 작은 JSON 숫자(1e-400)는 BigDecimal 로 읽혀 자릿수 오류 — 0 으로 조용히 저장되지 않는다 */
    @Test
    void 아주작은_JSON숫자는_자릿수_오류() throws Exception {
        save("{\"T\":1e-400}").andExpect(jsonPath("$.errors[0].code").value("SCALE"));
    }

    // ---------- 결함2: NUM 정밀도 ----------

    /** 결함2: JSON 숫자로 보낸 값이 Double 로 반올림되어 저장된다. 조회 응답도 Double — 화면을 거쳐 다시 저장하면 값이 바뀐다 */
    @Test
    void 결함2_NUM_값은_요청_조회_재저장에서_자릿수를_잃지_않는다() throws Exception {
        save("{\"T\":123456789012.123456}").andExpect(status().isOk());
        assertThat(attrsDb()).isEqualTo("{\"T\": 123456789012.123456}");
        save("{\"T\":\"999999999999999.999999\"}").andExpect(status().isOk());
        Object shown = JsonPath.read(attrsResponse(), "$.data.attrs.T");
        save("{\"T\":\"" + asBrowserText(shown) + "\"}").andExpect(status().isOk());   // 화면에서 다른 칸만 고쳐 저장
        assertThat(attrsDb()).isEqualTo("{\"T\": 999999999999999.999999}");
    }


    // ---------- 결함1: 사용 중지 NUM 속성 ----------

    /** 결함1: 사용 중지된 NUM 속성 값이 0.0005·12345678.5 처럼 Double 표기가 지수형이면, 그대로 다시 보내도 INACTIVE — 그 품목의 속성 저장이 전부 막힌다 */
    @Test
    void 결함1_사용중지_NUM_속성값을_그대로_보내면_저장된다() throws Exception {
        save("{\"T\":\"0.0005\",\"N0\":\"12\"}").andExpect(status().isOk());
        accounts.exec(comp, "update mst_class_prop set use_yn = 'N' where prop_id = ?", propId("T"));
        Object shown = JsonPath.read(attrsResponse(), "$.data.attrs.T");
        save("{\"T\":\"" + asBrowserText(shown) + "\",\"N0\":\"13\"}").andExpect(status().isOk());
        save("{\"T\":0.0005,\"N0\":\"14\"}").andExpect(status().isOk());
        assertThat(attrsDb()).isEqualTo("{\"T\": 0.0005, \"N0\": 14}");
    }


    /** 관찰: 사용 중지 속성은 "그대로 두는 것만" 인데, 키를 빼고 보내면 값이 지워진다(화면은 항상 보내므로 API 만) */
    @Test
    void 관찰_사용중지_속성값은_키를_빼면_지워진다() throws Exception {
        save("{\"M\":\"가상제철\",\"N0\":\"1\"}").andExpect(status().isOk());
        accounts.exec(comp, "update mst_class_prop set use_yn = 'N' where prop_id = ?", propId("M"));
        save("{\"M\":\"다른제철\",\"N0\":\"1\"}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        save("{\"N0\":\"2\"}").andExpect(status().isOk());
        assertThat(attrsDb()).isEqualTo("{\"N0\": 2}");
    }

    // =====================================================================
    // 2. 값의 형태 — 글자·목록·날짜·키
    // =====================================================================

    @Test
    void TEXT_길이_UTF16_목록_공백정리_대소문자_날짜_키대소문자_빈키_중복키() throws Exception {
        save("{\"M\":\"" + "가".repeat(500) + "\"}").andExpect(status().isOk());
        save("{\"M\":\"" + "가".repeat(501) + "\"}").andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        save("{\"M\":\"" + "😀".repeat(250) + "\"}").andExpect(status().isOk());       // UTF-16 500
        save("{\"M\":\"" + "😀".repeat(251) + "\"}").andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        save("{\"M\":\"" + "a".repeat(1_000_000) + "\"}").andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        save("{\"M\":[\"a\"]}").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        save("{\"M\":12}").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        save("{\"M\":{\"x\":\"y\"}}").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        save("{\"G\":\" A \"}").andExpect(status().isOk());
        assertThat(attrsDb()).isEqualTo("{\"G\": \"A\"}");
        save("{\"G\":\"a\"}").andExpect(jsonPath("$.errors[0].code").value("CODE"));
        save("{\"G\":[\"A\"]}").andExpect(jsonPath("$.errors[0].code").value("CODE"));
        for (String d : new String[] {"2026-02-30", "20260101", "+2026-01-01", "2026-1-1", "1899-12-31", "2200-01-01"}) {
            save("{\"D\":\"" + d + "\"}").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        }
        save("{\"D\":20260101}").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        save("{\"D\":\" 2026-02-28 \"}").andExpect(status().isOk());
        save("{\"t\":\"1\"}").andExpect(jsonPath("$.errors[0].field").value("attrs.t"))
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_PROP"));           // 키는 대문자 정리 안 함(DB 와 같음)
        save("{\"\":\"1\"}").andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_PROP"));
        save("{\"M\":\"x\",\"M\":\"y\"}").andExpect(status().isOk());                     // 같은 키 둘 = 뒤 값
        assertThat(attrsDb()).isEqualTo("{\"M\": \"y\"}");
        putJson(I + "/" + rm1 + "/attrs", "{\"classId\":" + coil + ",\"attrs\":[1],\"rowVer\":" + itemVer(rm1) + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        putJson(I + "/" + rm1 + "/attrs", "{\"classId\":" + coil + ",\"attrs\":\"x\",\"rowVer\":" + itemVer(rm1) + "}")
                .andExpect(status().isBadRequest());
    }

    /** 관찰: 정의에 없는 키 10,000개 → 칸 오류 10,000개(약 750KB 응답). 상한 없음 */
    @Test
    void 관찰_정의없는_키_만개는_칸오류_만개() throws Exception {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 10_000; i++) {
            sb.append(i == 0 ? "" : ",").append("\"K").append(i).append("\":1");
        }
        MvcResult r = save(sb.append("}").toString()).andExpect(status().isBadRequest()).andReturn();
        assertThat((Integer) JsonPath.read(r.getResponse().getContentAsString(), "$.errors.length()")).isEqualTo(10_000);
        assertThat(r.getResponse().getContentAsString().length()).isGreaterThan(500_000);
    }

    // ---------- 결함9: 제어 문자 ----------

    /** 결함9: TEXT 속성에 제어 문자(U+0001)가 저장된다 — 품목 이름 등은 FORMAT 으로 막는 문자. NUL 은 칸 없는 400 */
    @Test
    void 결함9_TEXT속성_제어문자는_칸오류() throws Exception {
        save("{\"M\":\"a\\u0001b\"}").andExpect(jsonPath("$.errors[0].field").value("attrs.M"))
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        save("{\"M\":\"a\\u0000b\"}").andExpect(jsonPath("$.errors[0].field").value("attrs.M"));
    }


    /** QA O4(개발 세션이 결함 #9 와 함께 정리): NBSP·폭 없는 공백만인 TEXT 는 빈 값 → 필수면 REQUIRED. 직접 SQL 의 탭만은 아직 통과 */
    @Test
    void 보이지않는_문자만인_TEXT는_빈값이라_필수() throws Exception {
        long req = prop(coil, "{\"propCd\":\"REQ\",\"propNm\":\"필수\",\"dataType\":\"TEXT\",\"requiredYn\":\"Y\"}");
        save("{\"REQ\":\"   \"}").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        save("{\"REQ\":\"\\u00a0\"}").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        save("{\"REQ\":\"\\u200b\"}").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        save("{\"REQ\":\"x\"}").andExpect(status().isOk());                            // 클래스를 붙여 두고 직접 SQL
        assertThat(sqlError(comp, "update mst_item set attrs = jsonb_build_object('REQ', chr(9)) where item_id = ?", rm1))
                .isEqualTo("OK");                                                          // DB btrim 은 ASCII 공백만
        assertThat(req).isPositive();
    }

    // =====================================================================
    // 3. 속성 정의 변경 뒤 기존 값
    // =====================================================================

    /**
     * 결함4: 값이 있는 상태에서 선택지를 지우거나 자릿수를 줄이면, 그 품목은 **다른 속성만 고쳐도** 저장할 수 없다
     * (서비스 CODE·SCALE + 트리거가 attrs 전체를 다시 검사). "새 참조만 차단" 원칙과 다르다 — 바뀌지 않은 값은 통과해야.
     */
    @Test
    void 결함4_정의변경_뒤_바뀌지않은_값은_그대로_다른속성_저장가능() throws Exception {
        save("{\"G\":\"B\",\"T\":\"1.234\",\"M\":\"x\"}").andExpect(status().isOk());
        putJson(P + "/" + propId("G"), "{\"propNm\":\"강종\",\"options\":[\"A\"],\"rowVer\":0}").andExpect(status().isOk());
        putJson(P + "/" + propId("T"), "{\"propNm\":\"두께\",\"scale\":1,\"rowVer\":0}").andExpect(status().isOk());
        save("{\"G\":\"B\",\"T\":\"1.234\",\"M\":\"y\"}").andExpect(status().isOk());
        save("{\"G\":\"B\",\"T\":\"1.25\",\"M\":\"y\"}").andExpect(jsonPath("$.errors[0].code").value("SCALE"));   // 바꾸면 새 규칙
        assertThat(sqlError(comp, "update mst_item set attrs = jsonb_set(attrs, '{M}', '\"q\"') where item_id = ?", rm1)).isEqualTo("OK");
    }


    @Test
    void 필수를_나중에_켜기_끄기_사용중지_다시사용() throws Exception {
        save("{\"M\":\"x\"}").andExpect(status().isOk());
        long n0 = propId("N0");
        putJson(P + "/" + n0, "{\"propNm\":\"정수\",\"scale\":0,\"requiredYn\":\"Y\",\"rowVer\":0}").andExpect(status().isOk());
        save("{\"M\":\"y\"}").andExpect(jsonPath("$.errors[0].field").value("attrs.N0")).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        putJson(P + "/" + n0, "{\"propNm\":\"정수\",\"scale\":0,\"requiredYn\":\"Y\",\"useYn\":\"N\",\"rowVer\":1}").andExpect(status().isOk());
        save("{\"M\":\"y\"}").andExpect(status().isOk());                            // 사용 중지 필수는 검사 밖
        putJson(P + "/" + n0, "{\"propNm\":\"정수\",\"scale\":0,\"requiredYn\":\"Y\",\"useYn\":\"Y\",\"rowVer\":2}").andExpect(status().isOk());
        save("{\"M\":\"z\"}").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        // 직접 SQL 로 필수 값을 null 로
        save("{\"M\":\"z\",\"N0\":1}").andExpect(status().isOk());
        assertThat(sqlError(comp, "update mst_item set attrs = attrs || '{\"N0\": null}' where item_id = ?", rm1))
                .isEqualTo("23514 ck_attrs_required");
        // 형식·코드 고정, 자릿수 7·선택지 51개
        putJson(P + "/" + n0, "{\"propNm\":\"정수\",\"dataType\":\"TEXT\",\"rowVer\":3}").andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        putJson(P + "/" + propId("T"), "{\"propNm\":\"두께\",\"scale\":7,\"rowVer\":0}").andExpect(jsonPath("$.errors[0].field").value("scale"));
        StringBuilder opts = new StringBuilder("[");
        for (int i = 0; i < 51; i++) {
            opts.append(i == 0 ? "" : ",").append("\"O").append(i).append('"');
        }
        putJson(P + "/" + propId("G"), "{\"propNm\":\"강종\",\"options\":" + opts + "],\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[0].code").value("TOO_MANY"));
        putJson(P + "/" + propId("G"), "{\"propNm\":\"강종\",\"options\":[\"" + "가".repeat(101) + "\"],\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[0].code").value("TOO_MANY"));
    }

    // =====================================================================
    // 4. 클래스 바꾸기
    // =====================================================================

    @Test
    void 품목_클래스바꾸기_같은코드_다른형식_옛키_사용중지클래스_그대로저장() throws Exception {
        long bolt = id(postJson(P + "/item-classes", "{\"classCd\":\"BOLT\",\"classNm\":\"볼트\"}"));
        prop(bolt, "{\"propCd\":\"T\",\"propNm\":\"규격\",\"dataType\":\"TEXT\"}");     // 같은 코드, 다른 형식
        save("{\"T\":\"1.5\",\"M\":\"x\"}").andExpect(status().isOk());
        save(rm1, bolt, "{\"T\":1.5}").andExpect(jsonPath("$.errors[0].field").value("attrs.T"))
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        save(rm1, bolt, "{\"T\":\"M8\",\"M\":\"x\"}").andExpect(jsonPath("$.errors[0].field").value("attrs.M"))
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_PROP"));
        save(rm1, bolt, "{\"T\":\"M8\"}").andExpect(status().isOk());
        assertThat(attrsDb()).isEqualTo("{\"T\": \"M8\"}");
        // 지금 클래스를 사용 중지해도 값 저장은 계속(새 참조만 차단)
        putJson(P + "/item-classes/" + bolt, "{\"classNm\":\"볼트\",\"useYn\":\"N\",\"rowVer\":0}").andExpect(status().isOk());
        save(rm1, bolt, "{\"T\":\"M10\"}").andExpect(status().isOk());
        long rm2 = item(comp, "RM2");
        save(rm2, bolt, "{}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        // 클래스 비우기 + 빈 값만
        save(rm1, null, "{\"T\":\"\",\"X\":null}").andExpect(status().isOk());
        assertThat(attrsDb()).isEqualTo("{}");
        save(rm1, null, "{\"T\":0}").andExpect(jsonPath("$.errors[0].code").value("NO_CLASS"));
        // 다른 회사 클래스가 아닌 "설비 클래스 id" 를 품목 클래스로
        accounts.addPlant(comp, "P1", "1공장");
        long press = accounts.query(comp, """
                insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by)
                values (?, 'PRESS9', '프레스', 't', 't') returning class_id""", Long.class, comp);
        if (accounts.query(comp, "select count(*) from mst_item_class where class_id = ?", Integer.class, press) == 0) {
            save(rm1, press, "{}").andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        }
    }

    // ---------- 결함3: 설비 클래스 바꾸기 교착 ----------

    private long[] equipWithRequired() throws Exception {
        accounts.addPlant(comp, "P1", "1공장");
        long press = accounts.query(comp, """
                insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by)
                values (?, 'PRESS', '프레스', 't', 't') returning class_id""", Long.class, comp);
        long weld = accounts.query(comp, """
                insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by)
                values (?, 'WELD', '용접기', 't', 't') returning class_id""", Long.class, comp);
        prop("EQUIP", press, "{\"propCd\":\"TON\",\"propNm\":\"톤수\",\"dataType\":\"NUM\",\"scale\":0,\"requiredYn\":\"Y\"}");
        long eq = accounts.query(comp, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                values (?, 'P1', 'PR-1', '프레스 1', ?, 't', 't') returning equip_id""", Long.class, comp, press);
        putJson(E + "/" + eq + "/attrs", "{\"attrs\":{\"TON\":\"1200\"},\"rowVer\":" + equipVer(eq) + "}").andExpect(status().isOk());
        return new long[] {eq, press, weld};
    }

    private int equipVer(long eq) {
        return accounts.query(comp, "select row_ver from mst_equip where equip_id = ?", Integer.class, eq);
    }

    /** 결함3: 필수 속성 값이 있는 설비는 그 속성이 없는 클래스로 옮길 수 없다 — 클래스 변경은 ATTRS_CLASS, 값 비우기는 REQUIRED */
    @Test
    void 결함3_필수속성이_있는_설비도_다른클래스로_옮길수있다() throws Exception {
        long[] x = equipWithRequired();
        putJson(E + "/" + x[0] + "/attrs", "{\"classId\":" + x[2] + ",\"attrs\":{},\"rowVer\":" + equipVer(x[0]) + "}")
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select class_id || '/' || attrs::text from mst_equip where equip_id = ?", String.class, x[0]))
                .isEqualTo(x[2] + "/{}");
    }


    // =====================================================================
    // 5. 동시성
    // =====================================================================

    @Test
    void 같은_rowVer_품목기본저장_뒤_속성저장은_409() throws Exception {
        int v = itemVer(rm1);
        putJson(I + "/" + rm1, "{\"itemCd\":\"RM1\",\"itemNm\":\"바꿈\",\"itemType\":\"RM\",\"procType\":\"BUY\",\"unitCd\":\"KG\",\"rowVer\":" + v + "}")
                .andExpect(status().isOk());
        putJson(I + "/" + rm1 + "/attrs", "{\"classId\":" + coil + ",\"attrs\":{\"M\":\"x\"},\"rowVer\":" + v + "}")
                .andExpect(status().isConflict());
        putJson(P + "/" + propId("M"), "{\"propNm\":\"a\",\"rowVer\":0}").andExpect(status().isOk());
        putJson(P + "/" + propId("M"), "{\"propNm\":\"b\",\"rowVer\":0}").andExpect(status().isConflict());
    }

    @Test
    void 동시_같은_품목_속성저장_뒤쪽은_기다린뒤_409() throws Exception {
        int v = itemVer(rm1);
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> first = holdTx(() -> jdbc.update(
                "update mst_item set item_class_id = ?, attrs = '{\"M\":\"first\"}', row_ver = row_ver + 1 where item_id = ? and row_ver = ?",
                coil, rm1, v), locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcResult> second = async(json(put(I + "/" + rm1 + "/attrs"),
                "{\"classId\":" + coil + ",\"attrs\":{\"M\":\"second\"},\"rowVer\":" + v + "}").with(admin));
        first.get(10, TimeUnit.SECONDS);
        assertThat(second.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        assertThat(attrsDb()).isEqualTo("{\"M\": \"first\"}");
    }

    /** 관찰: 정의 변경(선택지 B 삭제)이 커밋 전일 때 값 저장은 기다리지 않고 옛 정의로 통과 — 커밋 뒤 새 정의에 맞지 않는 값이 남는다 */
    @Test
    void 관찰_정의변경_커밋전_값저장은_기다리지않고_옛정의로_통과() throws Exception {
        long g = propId("G");
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> first = holdTx(() -> jdbc.update(
                "update mst_class_prop set options = '[\"A\"]', row_ver = row_ver + 1 where prop_id = ?", g), locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        long t0 = System.nanoTime();
        MvcResult r = async(json(put(I + "/" + rm1 + "/attrs"),
                "{\"classId\":" + coil + ",\"attrs\":{\"G\":\"B\"},\"rowVer\":" + itemVer(rm1) + "}").with(admin)).get(10, TimeUnit.SECONDS);
        long waitedMs = (System.nanoTime() - t0) / 1_000_000;
        first.get(10, TimeUnit.SECONDS);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(waitedMs).isLessThan(1400);
        assertThat(attrsDb()).isEqualTo("{\"G\": \"B\"}");
        assertThat(accounts.query(comp, "select options::text from mst_class_prop where prop_id = ?", String.class, g)).isEqualTo("[\"A\"]");
    }

    // =====================================================================
    // 6. 직접 SQL (앱 계정)
    // =====================================================================

    @Test
    void 직접SQL_형식우회_시도() throws Exception {
        save("{\"M\":\"x\"}").andExpect(status().isOk());
        assertThat(sqlError(comp, "update mst_item set attrs = attrs || '{\"T\": 1e20}' where item_id = ?", rm1)).isEqualTo("23514 ck_attrs_value");
        assertThat(sqlError(comp, "update mst_item set attrs = attrs || '{\"T\": 0.00000001}' where item_id = ?", rm1)).isEqualTo("23514 ck_attrs_value");
        assertThat(sqlError(comp, "update mst_item set attrs = attrs || '{\"T\": \"1.5\"}' where item_id = ?", rm1)).isEqualTo("23514 ck_attrs_value");
        assertThat(sqlError(comp, "update mst_item set attrs = attrs || '{\"M\": {\"a\": 1}}' where item_id = ?", rm1)).isEqualTo("23514 ck_attrs_value");
        assertThat(sqlError(comp, "update mst_item set attrs = attrs || '{\"M\": \"   \"}' where item_id = ?", rm1)).isEqualTo("23514 ck_attrs_value");
        assertThat(sqlError(comp, "update mst_item set attrs = attrs || '{\"G\": \"a\"}' where item_id = ?", rm1)).isEqualTo("23514 ck_attrs_value");
        assertThat(sqlError(comp, "update mst_item set attrs = attrs || '{\"m\": \"x\"}' where item_id = ?", rm1)).isEqualTo("23514 ck_attrs_unknown");
        // 없는 날짜도 제약 이름 있는 오류로(QA O9 → V031)
        assertThat(sqlError(comp, "update mst_item set attrs = attrs || '{\"D\": \"2026-13-01\"}' where item_id = ?", rm1))
                .isEqualTo("23514 ck_attrs_value");
        assertThat(sqlError(comp, "update mst_item set attrs = attrs || '{\"D\": \"2026-02-30\"}' where item_id = ?", rm1))
                .isEqualTo("23514 ck_attrs_value");
        assertThat(sqlError(comp, "update mst_item set attrs = 'null' where item_id = ?", rm1)).startsWith("23514");
        assertThat(sqlError(comp, "update mst_item set attrs = attrs || '{\"M\": null}' where item_id = ?", rm1)).isEqualTo("OK");   // null = 값 없음
        // 정의 쪽
        long g = propId("G");
        assertThat(sqlError(comp, "update mst_class_prop set options = '[1,2]' where prop_id = ?", g)).isEqualTo("23514 ck_mst_class_prop_options");
        assertThat(sqlError(comp, "update mst_class_prop set options = '[\"A\",\" \"]' where prop_id = ?", g)).isEqualTo("23514 ck_mst_class_prop_options");
        assertThat(sqlError(comp, "update mst_class_prop set options = '{\"a\":1}' where prop_id = ?", g))
                .isEqualTo("22023 -");                     // 트리거가 CHECK 보다 먼저 배열로 펼치다 실패 — 제약 이름 없는 오류(직접 SQL 만)
        assertThat(sqlError(comp, "update mst_class_prop set options = '[\"A\",\"A\"]' where prop_id = ?", g)).isEqualTo("OK");   // 중복은 서비스만 정리
        assertThat(sqlError(comp, "update mst_class_prop set class_type = 'EQUIP' where prop_id = ?", g)).isEqualTo("55000 ck_mst_class_prop_fixed");
        assertThat(sqlError(comp, "update mst_class_prop set prop_cd = 'G2' where prop_id = ?", g)).isEqualTo("55000 ck_mst_class_prop_fixed");
        assertThat(sqlError(comp, "update mst_class_prop set scale = 2 where prop_id = ?", g)).isEqualTo("23514 ck_mst_class_prop_scale");
        assertThat(sqlError(comp, "update mst_item_class set comp_cd = 'ZZ' where class_id = ?", coil)).startsWith("42501");
        assertThat(sqlError(comp, "delete from mst_item_class where class_id = ?", coil)).startsWith("42501");
        assertThat(sqlError(comp, "truncate mst_class_prop")).startsWith("42501");
    }

    // =====================================================================
    // 7. A사로 B사
    // =====================================================================

    @Test
    void B사는_A사_클래스_속성_값을_모든_API와_직접SQL로_못본다_못쓴다() throws Exception {
        save("{\"M\":\"A사 값\"}").andExpect(status().isOk());
        accounts.addPlant(comp, "P1", "1공장");
        long aPress = accounts.query(comp, """
                insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by)
                values (?, 'PRESS', '프레스', 't', 't') returning class_id""", Long.class, comp);
        long aEquip = accounts.query(comp, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                values (?, 'P1', 'PR-1', '프레스 1', ?, 't', 't') returning equip_id""", Long.class, comp, aPress);
        long gProp = propId("G");

        String other = accounts.newComp("CQ");
        long b = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, b, "MASTER", "mst-class-prop", "SEARCH", "SAVE");
        accounts.grant(other, b, "MASTER", "mst-item", "SEARCH", "SAVE");
        accounts.grant(other, b, "MASTER", "mst-equip", "SEARCH", "SAVE");
        RequestPostProcessor bu = accounts.as(other, b, "admin1");
        long bItem = item(other, "RM1");
        accounts.addPlant(other, "P1", "1공장");
        long bPress = accounts.query(other, """
                insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by)
                values (?, 'PRESS', '프레스', 't', 't') returning class_id""", Long.class, other);
        long bEquip = accounts.query(other, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                values (?, 'P1', 'PR-1', '프레스 1', ?, 't', 't') returning equip_id""", Long.class, other, bPress);

        mvc.perform(json(put(I + "/" + rm1 + "/attrs"), "{\"classId\":" + coil + ",\"attrs\":{\"M\":\"B사\"},\"rowVer\":" + itemVer(rm1) + "}")
                .with(bu)).andExpect(status().isNotFound());
        mvc.perform(json(put(P + "/" + gProp), "{\"propNm\":\"x\",\"options\":[\"Z\"],\"rowVer\":0}").with(bu)).andExpect(status().isNotFound());
        mvc.perform(json(put(P + "/item-classes/" + coil), "{\"classNm\":\"x\",\"useYn\":\"N\",\"rowVer\":0}").with(bu))
                .andExpect(status().isNotFound());
        mvc.perform(get(I + "/class-props").param("classId", String.valueOf(coil)).with(bu)).andExpect(status().isNotFound());
        mvc.perform(get(I + "/classes").with(bu)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(P + "/classes").param("classType", "EQUIP").with(bu)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(E + "/" + aEquip + "/attrs").with(bu)).andExpect(status().isNotFound());
        mvc.perform(json(put(E + "/" + aEquip + "/attrs"), "{\"attrs\":{},\"rowVer\":0}").with(bu)).andExpect(status().isNotFound());
        mvc.perform(json(post(P), "{\"classType\":\"EQUIP\",\"classId\":" + aPress + ",\"propCd\":\"X\",\"propNm\":\"x\",\"dataType\":\"TEXT\"}")
                .with(bu)).andExpect(status().isNotFound());
        mvc.perform(get(P).param("classType", "EQUIP").param("classId", String.valueOf(aPress)).with(bu)).andExpect(status().isNotFound());
        // 변경 이력: B사가 A사 키로 → 0건, A사는 보인다
        mvc.perform(get("/api/platform/audit").param("table", "mst_class_prop").param("key", String.valueOf(gProp)).with(bu))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/platform/audit").param("table", "mst_item").param("key", String.valueOf(rm1)).with(bu))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/platform/audit").param("table", "mst_class_prop").param("key", String.valueOf(gProp)).with(admin))
                .andExpect(jsonPath("$.total").value(1));
        // 직접 SQL (B사 범위)
        assertThat(accounts.exec(other, "update mst_item set attrs = '{}' where item_id = ?", rm1)).isZero();
        assertThat(accounts.exec(other, "update mst_class_prop set use_yn = 'N' where prop_id = ?", gProp)).isZero();
        assertThat(sqlError(other, "update mst_equip set class_id = ? where equip_id = ?", aPress, bEquip)).isEqualTo("23503 fk_mst_equip_class_comp");
        assertThat(sqlError(other, """
                insert into mst_class_prop (comp_cd, class_type, class_id, prop_cd, prop_nm, data_type, created_by, updated_by)
                values (?, 'EQUIP', ?, 'X', 'x', 'TEXT', 't', 't')""", other, aPress)).isEqualTo("23503 fk_mst_class_prop_class");
        assertThat(sqlError(other, """
                insert into mst_class_prop (comp_cd, class_type, class_id, prop_cd, prop_nm, data_type, created_by, updated_by)
                values (?, 'ITEM', ?, 'X', 'x', 'TEXT', 't', 't')""", comp, coil)).isEqualTo("23503 fk_mst_class_prop_class");   // A사 comp_cd 로 써도 트리거가 먼저(B사 범위에선 A사 클래스가 안 보임)
        assertThat(sqlError(other, "update mst_item set item_class_id = ?, attrs = '{\"M\":\"x\"}' where item_id = ?", coil, bItem))
                .isEqualTo("23503 fk_mst_item_class_comp");
        // A사 데이터 그대로
        assertThat(attrsDb()).isEqualTo("{\"M\": \"A사 값\"}");
        assertThat(accounts.query(comp, "select use_yn || options::text from mst_class_prop where prop_id = ?", String.class, gProp))
                .isEqualTo("Y[\"A\", \"B\"]");
    }

    // =====================================================================
    // 8. 권한 조합 · 변경 이력
    // =====================================================================

    @Test
    void 권한조합_변경이력() throws Exception {
        save("{\"M\":\"전\"}").andExpect(status().isOk());
        save("{\"M\":\"후\"}").andExpect(status().isOk());
        // 품목 attrs 변경은 품목 이력에 남는다(전·후 attrs)
        MvcResult h = mvc.perform(get("/api/platform/audit").param("table", "mst_item").param("key", String.valueOf(rm1)).with(admin))
                .andExpect(status().isOk()).andReturn();
        String body = h.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("후").contains("전").contains("u").contains("admin1");
        // 품목 저장만(속성 정의 화면 권한 없음): 속성 저장 200, 정의 이력 403
        long itemOnly = accounts.newUser(comp, "item1", PW);
        accounts.grant(comp, itemOnly, "ITEMONLY", "mst-item", "SEARCH", "SAVE");
        RequestPostProcessor io = accounts.as(comp, itemOnly, "item1");
        mvc.perform(json(put(I + "/" + rm1 + "/attrs"), "{\"classId\":" + coil + ",\"attrs\":{\"M\":\"품목권한\"},\"rowVer\":" + itemVer(rm1) + "}")
                .with(io)).andExpect(status().isOk());
        mvc.perform(get("/api/platform/audit").param("table", "mst_class_prop").param("key", String.valueOf(propId("M"))).with(io))
                .andExpect(status().isForbidden());
        mvc.perform(json(post(P + "/item-classes"), "{\"classCd\":\"X\",\"classNm\":\"x\"}").with(io)).andExpect(status().isForbidden());
        // 정의 화면 조회만: 정의 추가 403, 품목 속성 저장 403
        long defRead = accounts.newUser(comp, "def1", PW);
        accounts.grant(comp, defRead, "DEFREAD", "mst-class-prop", "SEARCH");
        RequestPostProcessor dr = accounts.as(comp, defRead, "def1");
        mvc.perform(json(post(P), "{\"classType\":\"ITEM\",\"classId\":" + coil + ",\"propCd\":\"X\",\"propNm\":\"x\",\"dataType\":\"TEXT\"}")
                .with(dr)).andExpect(status().isForbidden());
        mvc.perform(json(put(I + "/" + rm1 + "/attrs"), "{\"classId\":" + coil + ",\"attrs\":{},\"rowVer\":" + itemVer(rm1) + "}")
                .with(dr)).andExpect(status().isForbidden());
        mvc.perform(get(P + "/classes").param("classType", "ITEM").with(dr)).andExpect(status().isOk());
        // 설비 조회만: 설비 속성 보기 200, 저장 403
        accounts.addPlant(comp, "P1", "1공장");
        long press = accounts.query(comp, """
                insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by)
                values (?, 'PRESS', '프레스', 't', 't') returning class_id""", Long.class, comp);
        long eq = accounts.query(comp, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                values (?, 'P1', 'PR-1', '프레스 1', ?, 't', 't') returning equip_id""", Long.class, comp, press);
        long eqRead = accounts.newUser(comp, "eq1", PW);
        accounts.grant(comp, eqRead, "EQREAD", "mst-equip", "SEARCH");
        RequestPostProcessor er = accounts.as(comp, eqRead, "eq1");
        mvc.perform(get(E + "/" + eq + "/attrs").with(er)).andExpect(status().isOk());
        mvc.perform(json(put(E + "/" + eq + "/attrs"), "{\"attrs\":{},\"rowVer\":0}").with(er)).andExpect(status().isForbidden());
        mvc.perform(get(I + "/" + rm1 + "/attrs").with(er)).andExpect(status().isForbidden());
    }

    /** 관찰: 정의 화면 "저장만"(조회 없음) 권한으로 속성 정의 추가 가능 — 6단계 QA 관찰(SAVE ⊃ SEARCH?)과 같은 플랫폼 결정 */
    @Test
    void 관찰_정의화면_저장만_권한으로_정의추가() throws Exception {
        long saveOnly = accounts.newUser(comp, "save1", PW);
        accounts.grant(comp, saveOnly, "SAVEONLY", "mst-class-prop", "SAVE");
        RequestPostProcessor so = accounts.as(comp, saveOnly, "save1");
        mvc.perform(json(post(P), "{\"classType\":\"ITEM\",\"classId\":" + coil + ",\"propCd\":\"X\",\"propNm\":\"x\",\"dataType\":\"TEXT\"}")
                .with(so)).andExpect(status().isOk());
        mvc.perform(get(P + "/classes").param("classType", "ITEM").with(so)).andExpect(status().isForbidden());
    }
}
