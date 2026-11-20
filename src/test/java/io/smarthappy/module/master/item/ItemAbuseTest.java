package io.smarthappy.module.master.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA P1 1단계 — 품목 마스터 이상 사용 추가 검증 (docs/qa/item-import-1-2.md).
 * ItemApiTest 에 없는 것: 코드 유니코드 대소문자·전각·보이지 않는 글자, 이름·규격·비고 경계, 순질량·HS 추가 경계,
 * 동시 저장(row_ver), 일괄 사용 중지 조작, 단위 환산 경계·B사, 변경 이력 B사, 권한 조합, 엑셀 = 목록 조건.
 * 결함 재현 테스트 `결함n_…` — 번호는 보고서와 같다. 고친 뒤 @Disabled 를 풀었다(2026-10-20).
 */
@AutoConfigureMockMvc
class ItemAbuseTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String BASE = "/api/master/items";
    private static final String PROG = "mst-item";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long userId;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("IQ");
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", PROG, "SEARCH", "SAVE", "DELETE", "EXCEL");
        admin = accounts.as(comp, userId, "admin1");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** JSON 문자열 이스케이프(따옴표·역슬래시·제어문자) */
    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') {
                b.append('\\').append(ch);
            } else if (ch < 0x20) {
                b.append(String.format("\\u%04x", (int) ch));
            } else {
                b.append(ch);
            }
        }
        return b.append('"').toString();
    }

    private static String body(String cd, String nm, String extra) {
        return "{\"itemCd\":" + q(cd) + ",\"itemNm\":" + q(nm) + ",\"itemType\":\"FG\",\"procType\":\"MAKE\","
                + "\"unitCd\":\"EA\"" + (extra == null ? "" : "," + extra) + "}";
    }

    private ResultActions create(RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(json(post(BASE), body).with(who));
    }

    private long createOk(String cd, String nm, String extra) throws Exception {
        MvcResult r = create(admin, body(cd, nm, extra)).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private String errorCode(ResultActions r) throws Exception {
        String s = r.andReturn().getResponse().getContentAsString();
        List<String> codes = JsonPath.read(s, "$.errors[*].code");
        return codes.isEmpty() ? null : codes.get(0);
    }

    private String cdOf(long id) {
        return accounts.query(comp, "select item_cd from mst_item where item_id = ?", String.class, id);
    }

    private RequestPostProcessor userWith(String loginId, String... actions) {
        long u = accounts.newUser(comp, loginId, PW);
        accounts.grant(comp, u, "G_" + loginId.toUpperCase(), PROG, actions);
        return accounts.as(comp, u, loginId);
    }

    // ---------- 품목 코드 ----------

    @Test
    void 코드_40자_경계와_경로처럼_보이는_코드() throws Exception {
        assertThat(cdOf(createOk("a".repeat(40), "40자", null))).isEqualTo("A".repeat(40));
        // 허용 글자 조합이라 통과 — 점·슬래시 연속, 끝 하이픈 (관찰: 파일 이름·URL 에 코드를 쓰는 곳이 생기면 주의)
        for (String cd : List.of("A/../B", "A..1", "A-", "0")) {
            createOk(cd, "허용", null);
        }
    }

    @Test
    void 관찰_유니코드_대문자_변환이_ASCII_코드를_만든다() throws Exception {
        // toUpperCase(ROOT): ß→SS, \ufb01(합자)→FI, \u0131(터키어 점 없는 i)→I — 입력과 다른 ASCII 코드로 저장되고 기존 코드와 겹친다
        assertThat(cdOf(createOk("ß1", "에스체트", null))).isEqualTo("SS1");
        assertThat(cdOf(createOk("\ufb011", "합자", null))).isEqualTo("FI1");
        assertThat(cdOf(createOk("\u01311", "터키어", null))).isEqualTo("I1");
        assertThat(errorCode(create(admin, body("fi1", "ASCII", null)))).isEqualTo("DUPLICATE");
    }

    @Test
    void 전각_보이지_않는_글자_NBSP_는_코드_형식_오류() throws Exception {
        for (String cd : List.of("ＡＢ１", "A\u200b1", "A1\u00a0", "\u00a0A1", "A\u00001", "K1".replace('K', '\u212a'),
                "A\t1")) {
            assertThat(create(admin, body(cd, "거부", null)).andReturn().getResponse().getStatus())
                    .as(cd.chars().mapToObj(ch -> String.format("U+%04X", ch)).toList().toString()).isEqualTo(400);
        }
        assertThat(accounts.query(comp, "select count(*) from mst_item", Integer.class)).isZero();
        // 앞뒤 공백·줄바꿈은 strip() 으로 정리되지만 NBSP(U+00A0) 는 Java 공백이 아니라 위에서 거부 — 엑셀 올리기는 NBSP 도 정리(관찰)
        assertThat(cdOf(createOk(" a1\n", "정리", null))).isEqualTo("A1");
    }

    @Test
    void 코드를_다른_품목의_코드로_바꾸면_고정_오류_대소문자만_바꾸면_그대로() throws Exception {   // docs/17 9장: 만든 뒤 코드 고정(예전 DUPLICATE)
        createOk("RN1", "하나", null);
        long id = createOk("rn2", "둘", null);
        assertThat(errorCode(mvc.perform(json(put(BASE + "/" + id), body("rn1", "둘", "\"rowVer\":0")).with(admin))))
                .isEqualTo("FIXED");
        mvc.perform(json(put(BASE + "/" + id), body("Rn2", "둘", "\"rowVer\":0")).with(admin)).andExpect(status().isOk());
        assertThat(cdOf(id)).isEqualTo("RN2");
    }

    // ---------- 이름·규격·비고 ----------

    @Test
    void 이름_규격_비고_길이_경계() throws Exception {
        createOk("LN1", "가".repeat(200), "\"spec\":" + q("s".repeat(200)) + ",\"remark\":" + q("r".repeat(1000)));
        ResultActions r = create(admin, body("LN2", "가".repeat(201), "\"spec\":" + q("s".repeat(201)) + ",\"remark\":"
                + q("r".repeat(1001))));
        String s = r.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(s, "$.errors[*].field")).containsExactlyInAnyOrder("itemNm", "spec", "remark");
        assertThat((List<String>) JsonPath.read(s, "$.errors[*].code")).containsOnly("TOO_LONG");
    }

    @Test
    void 관찰_이모지는_UTF16_길이로_세어_DB_보다_먼저_막힌다() throws Exception {
        createOk("EM1", "😀".repeat(100), null);    // 200 UTF-16 = 100 글자
        assertThat(errorCode(create(admin, body("EM2", "😀".repeat(101), null)))).isEqualTo("TOO_LONG");   // DB(200글자)엔 들어간다
    }

    @Test
    void 관찰_보이지_않는_글자만으로_된_이름도_저장된다() throws Exception {
        long id = createOk("ZW1", "\u200b\u200b", null);
        assertThat(accounts.query(comp, "select length(item_nm) from mst_item where item_id = ?", Integer.class, id))
                .isEqualTo(2);
    }


    @Test
    void 결함3_이름의_NUL_문자는_400_칸_오류() throws Exception {
        create(admin, body("NU1", "앞\u0000뒤", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("itemNm"));
    }

    // ---------- 순질량·HS ----------

    @Test
    void 순질량_추가_경계() throws Exception {
        String[][] cases = {{"+1", "FORMAT"}, {"-0", "POSITIVE"}, {"0.0000000", "POSITIVE"}, {"1" + "0".repeat(30), "FORMAT"},
            {"1" + "0".repeat(29), "INT_DIGITS"}, {"\u0663", "FORMAT"}, {"1.", "FORMAT"}, {".", "FORMAT"}, {"1e-7", "FORMAT"},
            {"0x10", "FORMAT"}, {"1_000", "FORMAT"}};
        int n = 0;
        for (String[] c : cases) {
            assertThat(errorCode(create(admin, body("MS" + n++, "질량", "\"netMassKg\":" + q(c[0])))))
                    .as(c[0]).isEqualTo(c[1]);
        }
        long id = createOk("MSOK", "앞자리 0", "\"netMassKg\":\"0000000001.500000\"");
        assertThat(accounts.query(comp, "select net_mass_kg::text from mst_item where item_id = ?", String.class, id))
                .isEqualTo("1.500000");
        // JSON 숫자: 지수 표기가 문자열로 넘어와도 500 이 아니다
        create(admin, body("MSJ1", "질량", "\"netMassKg\":1e-7")).andExpect(status().isBadRequest());
        create(admin, body("MSJ2", "질량", "\"netMassKg\":1e400")).andExpect(status().isBadRequest());
        create(admin, body("MSJ3", "질량", "\"netMassKg\":true")).andExpect(status().isBadRequest());
    }


    @Test
    void 결함10_순질량_열_정의는_저장_자릿수_6() throws Exception {
        mvc.perform(get(BASE + "/columns").with(admin))
                .andExpect(jsonPath("$.list[?(@.field == 'netMassKg')].scale").value(6));
    }

    @Test
    void HS_코드_추가_경계() throws Exception {
        String[] bad = {"87082", "87082910123", "８７０８２９", "87O829", "......", "8708_29", "87/0829"};
        int n = 0;
        for (String hs : bad) {
            assertThat(errorCode(create(admin, body("HB" + n++, "HS", "\"hsCd\":" + q(hs))))).as(hs).isEqualTo("FORMAT");
        }
        long a = createOk("HS6", "6자리", "\"hsCd\":\"870829\"");
        long b = createOk("HS10", "10자리", "\"hsCd\":\" 8708.29 10-00\\t\"");
        assertThat(accounts.query(comp, "select hs_cd from mst_item where item_id = ?", String.class, a)).isEqualTo("870829");
        assertThat(accounts.query(comp, "select hs_cd from mst_item where item_id = ?", String.class, b)).isEqualTo("8708291000");
    }

    // ---------- 동시성 ----------

    /** 두 요청을 같은 순간에 출발시킨다 */
    private List<Integer> race(Callable<Integer> a, Callable<Integer> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Integer> fa = pool.submit(() -> {
                go.await();
                return a.call();
            });
            Future<Integer> fb = pool.submit(() -> {
                go.await();
                return b.call();
            });
            go.countDown();
            return List.of(fa.get(), fb.get());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void 같은_품목을_같은_rowVer_로_동시에_저장하면_하나만() throws Exception {
        long id = createOk("CC1", "원래", null);
        for (int round = 0; round < 5; round++) {
            int ver = accounts.query(comp, "select row_ver from mst_item where item_id = ?", Integer.class, id);
            List<Integer> st = race(
                    () -> mvc.perform(json(put(BASE + "/" + id), body("CC1", "A" + 0, "\"rowVer\":" + ver)).with(admin))
                            .andReturn().getResponse().getStatus(),
                    () -> mvc.perform(json(put(BASE + "/" + id), body("CC1", "B" + 0, "\"rowVer\":" + ver)).with(admin))
                            .andReturn().getResponse().getStatus());
            assertThat(st).containsExactlyInAnyOrder(200, 409);
            assertThat(accounts.query(comp, "select row_ver from mst_item where item_id = ?", Integer.class, id))
                    .isEqualTo(ver + 1);
        }
    }

    @Test
    void 수정과_사용_중지가_동시에_오면_하나만() throws Exception {
        long id = createOk("CC2", "원래", null);
        List<Integer> st = race(
                () -> mvc.perform(json(put(BASE + "/" + id), body("CC2", "수정", "\"rowVer\":0")).with(admin))
                        .andReturn().getResponse().getStatus(),
                () -> mvc.perform(json(post(BASE + "/delete"), "{\"items\":[{\"id\":" + id + ",\"rowVer\":0}]}").with(admin))
                        .andReturn().getResponse().getStatus());
        assertThat(st).containsExactlyInAnyOrder(200, 409);
        assertThat(accounts.query(comp, "select row_ver from mst_item where item_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void 같은_새_코드를_동시에_등록하면_하나만_나머지는_칸_오류() throws Exception {
        for (int round = 0; round < 5; round++) {
            String cd = "NEW" + round;
            List<Integer> st = race(() -> create(admin, body(cd, "a", null)).andReturn().getResponse().getStatus(),
                    () -> create(admin, body(cd, "b", null)).andReturn().getResponse().getStatus());
            assertThat(st).containsExactlyInAnyOrder(200, 400);
        }
        assertThat(accounts.query(comp, "select count(*) from mst_item", Integer.class)).isEqualTo(5);
    }

    @Test
    void 단위_환산_저장과_품목_수정이_같은_rowVer_로_오면_하나만() throws Exception {
        long id = createOk("CV0", "환산", null);
        String conv = "{\"created\":[{\"rowKey\":\"n1\",\"fromUnit\":\"KG\",\"factor\":\"2\"}]}";
        List<Integer> st = race(
                () -> mvc.perform(json(post(BASE + "/" + id + "/unit-conv/batch").param("rowVer", "0"), conv).with(admin))
                        .andReturn().getResponse().getStatus(),
                () -> mvc.perform(json(put(BASE + "/" + id), body("CV0", "수정", "\"rowVer\":0")).with(admin))
                        .andReturn().getResponse().getStatus());
        assertThat(st).containsExactlyInAnyOrder(200, 409);
    }

    // ---------- 사용 중지 일괄 ----------

    @Test
    void 일괄_사용_중지_조작은_전부_취소() throws Exception {
        long a = createOk("DL1", "a", null);
        long b = createOk("DL2", "b", null);
        // 같은 id 두 번 → 두 번째가 rowVer 충돌 → 첫 번째도 롤백
        mvc.perform(json(post(BASE + "/delete"), "{\"items\":[{\"id\":" + a + ",\"rowVer\":0},{\"id\":" + a + ",\"rowVer\":0}]}")
                .with(admin)).andExpect(status().isConflict());
        // B사 품목 id 섞기 → 404, 내 것도 그대로
        String other = accounts.newComp("IQ");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "MASTER", PROG, "SEARCH", "SAVE", "DELETE");
        RequestPostProcessor bp = accounts.as(other, bUser, "admin1");
        long bItem = ((Number) JsonPath.read(create(bp, body("BX1", "b사", null)).andReturn().getResponse()
                .getContentAsString(), "$.data")).longValue();
        mvc.perform(json(post(BASE + "/delete"), "{\"items\":[{\"id\":" + b + ",\"rowVer\":0},{\"id\":" + bItem + ",\"rowVer\":0}]}")
                .with(admin)).andExpect(status().isNotFound());
        assertThat(accounts.query(comp, "select count(*) from mst_item where use_yn = 'Y'", Integer.class)).isEqualTo(2);
        assertThat(accounts.query(other, "select use_yn from mst_item where item_id = ?", String.class, bItem)).isEqualTo("Y");
        // 상한·형식
        StringBuilder many = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 1001; i++) {
            many.append(i == 0 ? "" : ",").append("{\"id\":").append(a).append(",\"rowVer\":0}");
        }
        mvc.perform(json(post(BASE + "/delete"), many.append("]}").toString()).with(admin)).andExpect(status().isBadRequest());
        for (String bad : List.of("{}", "{\"items\":[]}", "{\"items\":[null]}", "{\"items\":[{\"id\":" + a + "}]}",
                "{\"items\":[{\"id\":\"x\",\"rowVer\":0}]}")) {
            mvc.perform(json(post(BASE + "/delete"), bad).with(admin)).andExpect(status().isBadRequest());
        }
    }

    // ---------- 단위 환산 ----------

    @Test
    void 단위_환산_경계와_B사() throws Exception {
        long id = createOk("UC1", "환산", null);
        String[][] cases = {{"KG", "0", "factor", "POSITIVE"}, {"KG", "1234567890", "factor", "INT_DIGITS"},
            {"KG", "0.0000000001", "factor", "SCALE"}, {"kg", "1", "fromUnit", "CODE"}, {"EA", "1", "fromUnit", "CODE"},
            {"KG", "1e3", "factor", "FORMAT"}, {"", "1", "fromUnit", "REQUIRED"}};
        for (String[] c : cases) {
            String s = mvc.perform(json(post(BASE + "/" + id + "/unit-conv/batch").param("rowVer", "0"),
                    "{\"created\":[{\"rowKey\":\"n1\",\"fromUnit\":" + q(c[0]) + ",\"factor\":" + q(c[1]) + "}]}").with(admin))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat((List<String>) JsonPath.read(s, "$.errors[*].field")).as(c[1]).contains(c[2]);
            assertThat((List<String>) JsonPath.read(s, "$.errors[*].code")).as(c[1]).contains(c[3]);
        }
        // B사 회사 단위·B사 품목
        String other = accounts.newComp("IQ");
        accounts.exec(other, """
                insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, attrs, use_yn, created_by, updated_by)
                values (?, 'UNIT', 'BQX', 'B사 단위', '{"scale": 0}', 'Y', 't', 't')""", other);
        mvc.perform(json(post(BASE + "/" + id + "/unit-conv/batch").param("rowVer", "0"),
                "{\"created\":[{\"rowKey\":\"n1\",\"fromUnit\":\"BQX\",\"factor\":\"2\"}]}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("CODE"));
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "MASTER", PROG, "SEARCH", "SAVE");
        RequestPostProcessor bp = accounts.as(other, bUser, "admin1");
        mvc.perform(get(BASE + "/" + id + "/unit-conv").with(bp)).andExpect(status().isNotFound());
        mvc.perform(json(post(BASE + "/" + id + "/unit-conv/batch").param("rowVer", "0"),
                "{\"created\":[{\"rowKey\":\"n1\",\"fromUnit\":\"KG\",\"factor\":\"2\"}]}").with(bp))
                .andExpect(status().isNotFound());
        assertThat(accounts.query(comp, "select count(*) from mst_unit_conv", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select row_ver from mst_item where item_id = ?", Integer.class, id)).isZero();
    }

    // ---------- 변경 이력·권한 ----------

    @Test
    void 변경_이력은_B사에서_빈_목록이고_조작한_키_테이블은_400() throws Exception {
        long id = createOk("AH1", "이력", null);
        mvc.perform(json(put(BASE + "/" + id), body("AH1", "이력2", "\"rowVer\":0")).with(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/platform/audit").param("table", "mst_item").param("key", String.valueOf(id)).with(admin))
                .andExpect(jsonPath("$.list.length()").value(2));
        String other = accounts.newComp("IQ");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "MASTER", PROG, "SEARCH");
        RequestPostProcessor bp = accounts.as(other, bUser, "admin1");
        mvc.perform(get("/api/platform/audit").param("table", "mst_item").param("key", String.valueOf(id)).with(bp))
                .andExpect(status().isOk()).andExpect(jsonPath("$.list.length()").value(0));
        for (String[] p : new String[][] {{"mst_item", id + " or 1=1"}, {"mst_item", "%"}, {"sys_comp", "1"},
            {"MST_ITEM", String.valueOf(id)}}) {
            mvc.perform(get("/api/platform/audit").param("table", p[0]).param("key", p[1]).with(admin))
                    .andExpect(status().isBadRequest());
        }
        RequestPostProcessor noSearch = userWith("saver9", "SAVE");
        mvc.perform(get("/api/platform/audit").param("table", "mst_item").param("key", String.valueOf(id)).with(noSearch))
                .andExpect(status().isForbidden());
    }

    @Test
    void 권한_조합_저장만_엑셀만_삭제만() throws Exception {
        long id = createOk("PM1", "권한", null);
        RequestPostProcessor saver = userWith("saver1", "SAVE");
        create(saver, body("PM2", "저장만", null)).andExpect(status().isOk());   // 관찰: 목록은 못 보는데 등록·수정은 된다
        mvc.perform(get(BASE).with(saver)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/" + id).with(saver)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/columns").with(saver)).andExpect(status().isForbidden());
        mvc.perform(json(post(BASE + "/delete"), "{\"items\":[{\"id\":" + id + ",\"rowVer\":0}]}").with(saver))
                .andExpect(status().isForbidden());
        mvc.perform(json(post(BASE + "/export"), "{}").with(saver)).andExpect(status().isForbidden());

        RequestPostProcessor excel = userWith("excel1", "EXCEL");
        mvc.perform(get(BASE).with(excel)).andExpect(status().isForbidden());
        // 관찰: 조회 권한 없이 엑셀 권한만 있으면 목록 전체를 내려받는다(액션이 서로 독립)
        MvcResult r = mvc.perform(json(post(BASE + "/export"), "{}").with(excel)).andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()))) {
            assertThat(wb.getSheetAt(0).getLastRowNum()).isEqualTo(2);
        }

        RequestPostProcessor deleter = userWith("del1", "DELETE");
        mvc.perform(json(post(BASE + "/delete"), "{\"items\":[{\"id\":" + id + ",\"rowVer\":0}]}").with(deleter))
                .andExpect(status().isOk());
        mvc.perform(json(put(BASE + "/" + id), body("PM1", "x", "\"rowVer\":1")).with(deleter)).andExpect(status().isForbidden());
        // 화면 진입: 조회 없는 사용자는 화면이 안 열린다
        mvc.perform(get("/page/" + PROG).with(saver)).andExpect(status().is4xxClientError());
    }

    // ---------- 엑셀 = 목록 조건 ----------

    @Test
    void 엑셀은_목록과_같은_조건_같은_순서_수식처럼_보이는_이름은_글자로() throws Exception {
        createOk("EX1", "볼트", "\"spec\":\"M8 볼트\"");
        createOk("EX2", "너트", "\"spec\":\"M8 볼트용\"");    // 규격으로 걸림
        createOk("EX3", "=1+1", null);
        long d = createOk("EX4", "볼트 단종", null);
        mvc.perform(json(post(BASE + "/delete"), "{\"items\":[{\"id\":" + d + ",\"rowVer\":0}]}").with(admin))
                .andExpect(status().isOk());
        String[][] conds = {{"itemNm", "볼트", "useYn", "Y"}, {"itemNm", "볼트", "useYn", "N"}, {"itemCd", "ex", "useYn", ""}};
        for (String[] c : conds) {
            String list = mvc.perform(get(BASE).param(c[0], c[1]).param(c[2], c[3]).param("sort", "itemNm,desc")
                    .param("size", "100").with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<String> listCds = JsonPath.read(list, "$.list[*].itemCd");
            String req = "{\"" + c[0] + "\":" + q(c[1]) + ",\"" + c[2] + "\":" + q(c[3]) + ",\"sort\":[\"itemNm,desc\"]}";
            MvcResult r = mvc.perform(json(post(BASE + "/export"), req).with(admin)).andExpect(status().isOk()).andReturn();
            List<String> excelCds = new ArrayList<>();
            try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()))) {
                Sheet s = wb.getSheetAt(0);
                for (int i = 1; i <= s.getLastRowNum(); i++) {
                    excelCds.add(s.getRow(i).getCell(0).getStringCellValue());
                    if ("EX3".equals(s.getRow(i).getCell(0).getStringCellValue())) {
                        assertThat(s.getRow(i).getCell(1).getStringCellValue()).isEqualTo("'=1+1");
                    }
                }
            }
            assertThat(excelCds).as(String.join(",", c)).isEqualTo(listCds);
        }
        // 엑셀 열 조작: 화이트리스트 밖 열 이름
        mvc.perform(json(post(BASE + "/export"), "{\"columns\":[\"compCd\",\"rowVer\"]}").with(admin))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post(BASE + "/export"), "{\"sort\":[\"spec,asc\"]}").with(admin)).andExpect(status().isBadRequest());
    }
}
