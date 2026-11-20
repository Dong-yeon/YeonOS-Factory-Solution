package io.smarthappy.module.master.item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;

import org.apache.poi.ss.usermodel.CellType;
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

/** 품목관리 (P1 1단계, docs/17 3장) — 거래처관리와 같은 표준 테스트 + 단위(회사 코드)·순질량(10진) 이상 사용 */
@AutoConfigureMockMvc
class ItemApiTest extends PostgresTestBase {

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
        comp = accounts.newComp("IT");
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", PROG, "SEARCH", "SAVE", "DELETE", "EXCEL");
        admin = accounts.as(comp, userId, "admin1");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String body(String cd, String nm, String extra) {
        return "{\"itemCd\":\"" + cd + "\",\"itemNm\":\"" + nm + "\",\"itemType\":\"FG\",\"procType\":\"MAKE\","
                + "\"unitCd\":\"EA\"" + (extra == null ? "" : "," + extra) + "}";
    }

    private ResultActions create(RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(json(post(BASE), body).with(who));
    }

    private long createOk(String cd, String nm, String extra) throws Exception {
        MvcResult r = create(admin, body(cd, nm, extra)).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data")).longValue();
    }

    /** 회사 단위 추가 (회사 공통코드 — 관리 화면은 아직 없어 직접) */
    private void addCompUnit(String compCd, String code, String useYn) {
        accounts.exec(compCd, """
                insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, attrs, use_yn, created_by, updated_by)
                values (?, 'UNIT', ?, ?, '{"scale": 2}', ?, 't', 't')""", compCd, code, code + " 단위", useYn);
    }

    // ---------- 정상 ----------

    @Test
    void 등록_목록_상세_수정_사용중지() throws Exception {
        long id = createOk("84910-2s000", "프런트 사이드 멤버", "\"spec\":\"용접 ASSY\",\"netMassKg\":\"4.850\","
                + "\"hsCd\":\"8708.29-10\"");
        mvc.perform(get(BASE).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].itemCd").value("84910-2S000"))      // 대문자로 정리
                .andExpect(jsonPath("$.list[0].lotCtrlYn").value("Y"))              // 기본 LOT 관리
                .andExpect(jsonPath("$.list[0].hsCd").value("87082910"))   // 점·하이픈 제거
                .andExpect(jsonPath("$.list[0].useYn").value("Y"));
        assertThat(accounts.query(comp, "select net_mass_kg from mst_item where item_id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("4.85");
        mvc.perform(json(put(BASE + "/" + id), body("84910-2S000", "프런트 사이드 멤버 ASSY",
                "\"procType\":\"OUTSRC\",\"unitCd\":\"SET\",\"lotCtrlYn\":\"N\",\"netMassKg\":\"0.000001\",\"rowVer\":0")
                .replace("\"procType\":\"MAKE\",", "").replace("\"unitCd\":\"EA\",", "")).with(admin))
                .andExpect(status().isOk());
        mvc.perform(get(BASE + "/" + id).with(admin))
                .andExpect(jsonPath("$.data.itemNm").value("프런트 사이드 멤버 ASSY"))
                .andExpect(jsonPath("$.data.procType").value("OUTSRC"))
                .andExpect(jsonPath("$.data.unitCd").value("SET"))
                .andExpect(jsonPath("$.data.lotCtrlYn").value("N"))
                .andExpect(jsonPath("$.data.netMassKg").value(0.000001))
                .andExpect(jsonPath("$.data.rowVer").value(1));
        mvc.perform(json(post(BASE + "/delete"), "{\"items\":[{\"id\":" + id + ",\"rowVer\":1}]}").with(admin))
                .andExpect(status().isOk());
        mvc.perform(get(BASE).param("useYn", "Y").with(admin)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(BASE).param("useYn", "N").with(admin)).andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void 자동차_품번_형식_허용과_거부() throws Exception {
        for (String ok : List.of("84910-2S000", "AB.123/4", "RM-SPFC590-1.6", "X_1")) {
            createOk(ok, "허용", null);
        }
        for (String bad : List.of("-A1", "A B", "가나다", "A*1", "A".repeat(41))) {
            create(admin, body(bad, "거부", null)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("itemCd"));
        }
        create(admin, body("ab.123/4", "대소문자 중복", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
    }

    @Test
    void 열_정의에_우선순위와_채우기_열이_있다() throws Exception {
        mvc.perform(get(BASE + "/columns").with(admin))
                .andExpect(jsonPath("$.list[0].field").value("itemCd"))
                .andExpect(jsonPath("$.list[0].priority").value(1))
                .andExpect(jsonPath("$.list[1].flex").value(true))
                .andExpect(jsonPath("$.list[1].minWidth").value(120))
                .andExpect(jsonPath("$.list[8].field").value("netMassKg"))
                .andExpect(jsonPath("$.list[8].type").value("DEC"))
                .andExpect(jsonPath("$.list[8].priority").value(3));
        mvc.perform(get(BASE + "/columns").with(accounts.as(comp, userId, "admin1", "en")))
                .andExpect(jsonPath("$.list[0].title").value("Item code"));
    }

    @Test
    void 단위_공통코드는_소수_자릿수_속성을_함께_내려준다() throws Exception {
        addCompUnit(comp, "DRUM", "Y");
        mvc.perform(get("/api/platform/codes").param("grp", "UNIT").with(admin))
                .andExpect(jsonPath("$.data[?(@.code == 'EA')].attrs.scale").value(0))
                .andExpect(jsonPath("$.data[?(@.code == 'KG')].attrs.scale").value(3))
                .andExpect(jsonPath("$.data[?(@.code == 'DRUM')].attrs.scale").value(2))
                .andExpect(jsonPath("$.data[?(@.code == 'DRUM')].system").value(false));
        mvc.perform(get("/api/platform/codes").param("grp", "ITEM_TYPE").with(admin))
                .andExpect(jsonPath("$.data[*].code").value(containsInAnyOrder("FG", "SFG", "RM", "SUB", "CON")));
    }

    @Test
    void 검색은_이름_또는_규격이고_와일드카드는_글자() throws Exception {
        createOk("S1", "사이드 멤버", "\"spec\":\"SPFC590 1.6t\"");
        createOk("S2", "크로스 멤버", "\"spec\":\"100% 재활용\"");
        createOk("S_3", "브래킷", null);
        createOk("SX3", "너트", null);
        mvc.perform(get(BASE).param("itemNm", "spfc590").with(admin)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(BASE).param("itemNm", "%").with(admin)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(BASE).param("itemCd", "S_").with(admin)).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].itemCd").value("S_3"));
        mvc.perform(get(BASE).param("procType", "MAKE").param("itemType", "FG").with(admin))
                .andExpect(jsonPath("$.total").value(4));
        mvc.perform(get(BASE).param("procType", "BUY").with(admin)).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void 엑셀은_목록과_같은_조건이고_순질량은_숫자_셀() throws Exception {
        createOk("E1", "가", "\"netMassKg\":\"1.25\"");
        createOk("E2", "나", null);
        createOk("X9", "다", null);
        MvcResult r = mvc.perform(json(post(BASE + "/export"), "{\"itemCd\":\"E\",\"sort\":[\"itemCd,asc\"],"
                + "\"columns\":[\"itemCd\",\"itemType\",\"netMassKg\"]}").with(admin))
                .andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()))) {
            Sheet s = wb.getSheetAt(0);
            assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("품목코드");
            assertThat(s.getLastRowNum()).isEqualTo(2);
            assertThat(s.getRow(1).getCell(1).getStringCellValue()).isEqualTo("제품");
            assertThat(s.getRow(1).getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(s.getRow(1).getCell(2).getNumericCellValue()).isEqualTo(1.25);
        }
    }

    // ---------- 이상 사용: 검증 ----------

    @Test
    void 검증_오류는_한_번에_모두_필드별로() throws Exception {
        create(admin, "{\"itemCd\":\" \",\"itemNm\":\"" + "가".repeat(201) + "\",\"itemType\":\"PART\","
                + "\"procType\":\"\",\"unitCd\":\"PCS\",\"lotCtrlYn\":\"X\",\"netMassKg\":\"abc\",\"hsCd\":\"12\","
                + "\"useYn\":\"D\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("itemCd", "itemNm", "itemType",
                        "procType", "unitCd", "lotCtrlYn", "netMassKg", "hsCd", "useYn")));
        assertThat(accounts.query(comp, "select count(*) from mst_item", Integer.class)).isZero();
    }

    @Test
    void 순질량은_10진만_받고_반올림하지_않는다() throws Exception {
        String[][] cases = {
                {"\"0\"", "POSITIVE"}, {"\"-1\"", "POSITIVE"}, {"\"1e3\"", "FORMAT"}, {"\"1,000\"", "FORMAT"},
                {"\"NaN\"", "FORMAT"}, {"\"Infinity\"", "FORMAT"}, {"\" .5\"", "FORMAT"}, {"\"0.0000001\"", "SCALE"},
                {"\"1234567890\"", "INT_DIGITS"}, {"\"１２\"", "FORMAT"}};
        for (String[] c : cases) {
            create(admin, body("M" + Math.abs(c[0].hashCode()), "질량", "\"netMassKg\":" + c[0]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("netMassKg"))
                    .andExpect(jsonPath("$.errors[0].code").value(c[1]));
        }
        // 끝의 0 은 자릿수로 세지 않는다, JSON 숫자도 받는다, 상한 정확히
        createOk("OK1", "끝0", "\"netMassKg\":\"1.230000000\"");
        createOk("OK2", "숫자", "\"netMassKg\":2.5");
        createOk("OK3", "상한", "\"netMassKg\":\"999999999.999999\"");
        assertThat(accounts.query(comp, "select net_mass_kg::text from mst_item where item_cd = 'OK3'", String.class))
                .isEqualTo("999999999.999999");
        assertThat(accounts.query(comp, "select count(*) from mst_item", Integer.class)).isEqualTo(3);
    }

    @Test
    void 오래된_rowVer_수정은_409_rowVer_없으면_400() throws Exception {
        long id = createOk("V1", "버전", null);
        String b = body("V1", "A", "\"rowVer\":0");
        mvc.perform(json(put(BASE + "/" + id), b).with(admin)).andExpect(status().isOk());
        mvc.perform(json(put(BASE + "/" + id), b).with(admin)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mvc.perform(json(put(BASE + "/" + id), body("V1", "A", null)).with(admin)).andExpect(status().isBadRequest());
    }

    @Test
    void 정렬_파라미터_조작은_400() throws Exception {
        for (String bad : List.of("item_nm;drop table mst_item", "itemNm,sideways", "netMassKg,asc", "spec,asc")) {
            mvc.perform(get(BASE).param("sort", bad).with(admin)).andExpect(status().isBadRequest());
        }
        mvc.perform(get(BASE).param("itemType", "FG' or '1'='1").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(BASE).param("procType", "ALL").with(admin)).andExpect(status().isBadRequest());
    }

    @Test
    void 같은_멱등키로_두_번_등록하면_한_건만() throws Exception {
        mvc.perform(json(post(BASE), body("IDEM", "연타", null)).header("X-Idempotency-Key", "item-create-0001")
                .with(admin)).andExpect(status().isOk());
        mvc.perform(json(post(BASE), body("IDEM2", "연타", null)).header("X-Idempotency-Key", "item-create-0001")
                .with(admin)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        assertThat(accounts.query(comp, "select count(*) from mst_item", Integer.class)).isEqualTo(1);
    }

    // ---------- 이상 사용: 단위(회사 코드) ----------

    @Test
    void 이_회사가_추가한_단위는_쓰고_사용중지된_단위는_새로_못_고른다() throws Exception {
        addCompUnit(comp, "DRUM", "Y");
        addCompUnit(comp, "OLDPK", "N");
        MvcResult r = create(admin, body("U1", "드럼 품목", null).replace("\"unitCd\":\"EA\"", "\"unitCd\":\"DRUM\""))
                .andExpect(status().isOk()).andReturn();
        long id = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data")).longValue();
        create(admin, body("U2", "옛 포장", null).replace("\"unitCd\":\"EA\"", "\"unitCd\":\"OLDPK\""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("unitCd"));
        // 쓰던 단위가 나중에 사용 중지돼도 그 품목 수정(단위 그대로)은 된다
        accounts.exec(comp, "update sys_comp_code set use_yn = 'N' where grp_cd = 'UNIT' and code = 'DRUM'");
        mvc.perform(json(put(BASE + "/" + id), body("U1", "드럼 품목 수정", "\"rowVer\":0")
                .replace("\"unitCd\":\"EA\"", "\"unitCd\":\"DRUM\"")).with(admin)).andExpect(status().isOk());
    }

    @Test
    void B사가_추가한_단위를_A사_품목에_쓸_수_없다_API와_직접_SQL_모두() throws Exception {
        String other = accounts.newComp("IT");
        addCompUnit(other, "BSAUNIT", "Y");
        create(admin, body("X1", "남의 단위", null).replace("\"unitCd\":\"EA\"", "\"unitCd\":\"BSAUNIT\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("unitCd"))
                .andExpect(jsonPath("$.errors[0].code").value("CODE"));
        // 서비스를 우회한 직접 INSERT 도 DB 트리거가 막는다
        assertThatThrownBy(() -> accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'X2', '직접', 'FG', 'MAKE', 'BSAUNIT', 't', 't')""", comp))
                .rootCause().hasMessageContaining("unit BSAUNIT is not available");
    }

    // ---------- 이상 사용: 권한·회사 ----------

    @Test
    void 조회_권한만_있으면_등록_수정_삭제_엑셀_403() throws Exception {
        long id = createOk("R1", "읽기", null);
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", PROG, "SEARCH");
        RequestPostProcessor r = accounts.as(comp, reader, "reader1");
        mvc.perform(get(BASE).with(r)).andExpect(status().isOk());
        create(r, body("R2", "x", null)).andExpect(status().isForbidden());
        mvc.perform(json(put(BASE + "/" + id), body("R1", "x", "\"rowVer\":0")).with(r)).andExpect(status().isForbidden());
        mvc.perform(json(post(BASE + "/delete"), "{\"items\":[{\"id\":" + id + ",\"rowVer\":0}]}").with(r))
                .andExpect(status().isForbidden());
        mvc.perform(json(post(BASE + "/export"), "{}").with(r)).andExpect(status().isForbidden());
    }

    @Test
    void B사_사용자가_A사_품목을_조회_수정_삭제_엑셀() throws Exception {
        long id = createOk("A1", "A사품목", null);
        String other = accounts.newComp("IT");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "MASTER", PROG, "SEARCH", "SAVE", "DELETE", "EXCEL");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");

        mvc.perform(get(BASE).with(b)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(BASE + "/" + id).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(BASE + "/" + id), body("A1", "탈취", "\"rowVer\":0")).with(b))
                .andExpect(status().isNotFound());
        mvc.perform(json(post(BASE + "/delete"), "{\"items\":[{\"id\":" + id + ",\"rowVer\":0}]}").with(b))
                .andExpect(status().isNotFound());
        // B사에도 같은 품번은 따로 만들 수 있다 (회사별 유일)
        create(b, body("A1", "B사품목", null)).andExpect(status().isOk());
        MvcResult r = mvc.perform(json(post(BASE + "/export"), "{}").with(b)).andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()))) {
            assertThat(wb.getSheetAt(0).getLastRowNum()).isEqualTo(1);
            assertThat(wb.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()).isEqualTo("B사품목");
        }
        assertThat(accounts.query(comp, "select item_nm from mst_item where item_id = ?", String.class, id))
                .isEqualTo("A사품목");
    }

    @Test
    void 이름의_스크립트는_글자로_저장되고_변경_이력이_남는다() throws Exception {
        long id = createOk("H1", "<script>alert(1)</script>", null);
        mvc.perform(get(BASE + "/" + id).with(admin))
                .andExpect(jsonPath("$.data.itemNm").value("<script>alert(1)</script>"));
        mvc.perform(get("/api/platform/audit").param("table", "mst_item").param("key", String.valueOf(id)).with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.list[0].action").value("INSERT"));
    }
}
