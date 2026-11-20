package io.smarthappy.platform.excel;

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

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * grid 엑셀 공통 도우미(GridExcel, docs/73 3장) — 대표 화면 불량 유형(mst-defect)으로 본다:
 * 목록과 같은 조건·정렬·건수, 쪽을 넘겨 읽어도 겹침·빠짐 없음, 보이는 열(요청 순서)·코드명·출력 정보 시트,
 * 허용 목록 밖 열·정렬·조건·형 400, EXCEL 권한 없으면 403, A사 파일에 B사 행 없음.
 */
@AutoConfigureMockMvc
class GridExcelTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String D = "/api/master/defects";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("GX");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "ADM", "mst-defect", "SEARCH", "SAVE", "EXCEL");
        accounts.grant(comp, u, "ADM", "sys-policy", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        // 쪽 크기 상한을 가장 작게 — 25행이 3쪽으로 나뉘어 읽힌다
        mvc.perform(put("/api/admin/policies/GRID_PAGE_SIZE_MAX").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"10\"}")).andExpect(status().isOk());
        for (int i = 1; i <= 25; i++) {
            String cat = i % 2 == 0 ? "WELD" : "APPEARANCE";
            create(admin, String.format("{\"defectCd\":\"D%02d\",\"defectNm\":\"%s %d\",\"catCd\":\"%s\"}", i, i % 2 == 0 ? "용접" : "외관", i, cat));
        }
    }

    private void create(RequestPostProcessor who, String body) throws Exception {
        mvc.perform(post(D).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    private ResultActions export(RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(post(D + "/export").with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private List<List<String>> sheet(RequestPostProcessor who, String body, int index) throws Exception {
        byte[] x = export(who, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<List<String>> out = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            Sheet s = wb.getSheetAt(index);
            for (int i = 0; i <= s.getLastRowNum(); i++) {
                Row r = s.getRow(i);
                List<String> cells = new ArrayList<>();
                for (int j = 0; j < r.getLastCellNum(); j++) {
                    Cell c = r.getCell(j);
                    cells.add(c == null ? "" : c.getCellType() == CellType.NUMERIC ? String.valueOf((long) c.getNumericCellValue()) : c.getStringCellValue());
                }
                out.add(cells);
            }
        }
        return out;
    }

    private int gridTotal(String query) throws Exception {
        String json = mvc.perform(get(D + "/grid" + query).with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(json, "$.total")).intValue();
    }

    @Test
    void 쪽을_넘겨_읽어도_모든_행이_한_번씩_정렬대로() throws Exception {
        List<List<String>> s = sheet(admin, "{\"sort\":[\"defectCd,desc\"],\"columns\":[\"defectCd\",\"defectNm\"]}", 0);
        assertThat(s.get(0)).containsExactly("불량 코드", "불량명");
        List<String> codes = s.subList(1, s.size()).stream().map(r -> r.get(0)).toList();
        assertThat(codes).hasSize(25).doesNotHaveDuplicates();
        assertThat(codes.get(0)).isEqualTo("D25");
        assertThat(codes.get(24)).isEqualTo("D01");
        assertThat(codes).isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    void 조건은_목록과_같은_행_수() throws Exception {
        assertThat(sheet(admin, "{}", 0)).hasSize(1 + gridTotal(""));
        assertThat(gridTotal("?keyword=용접")).isEqualTo(12);
        assertThat(sheet(admin, "{\"keyword\":\"용접\"}", 0)).hasSize(1 + 12);
        assertThat(sheet(admin, "{\"keyword\":\" d0 \",\"useYn\":\"Y\"}", 0)).hasSize(1 + gridTotal("?keyword=d0&useYn=Y"));
        assertThat(sheet(admin, "{\"keyword\":\"없는말\"}", 0)).hasSize(1);            // 0행 = 머리 줄만
        assertThat(sheet(admin, "{\"useYn\":\"\",\"keyword\":null}", 0)).hasSize(26);  // 빈 조건 = 조건 없음(목록과 같음)
    }

    @Test
    void 코드는_이름으로_출력정보_시트에_조건_이름과_건수() throws Exception {
        List<List<String>> s = sheet(admin, "{\"keyword\":\"D02\",\"columns\":[\"catCd\",\"defectCd\",\"useYn\"]}", 0);
        assertThat(s).hasSize(2);
        assertThat(s.get(1).get(0)).isNotEqualTo("WELD").isNotBlank();      // DEFECT_CAT 코드명
        assertThat(s.get(1).get(1)).isEqualTo("D02");
        assertThat(s.get(1).get(2)).isEqualTo("사용");
        List<List<String>> meta = sheet(admin, "{\"keyword\":\"D02\"}", 1);
        assertThat(meta.stream().map(r -> r.get(1)).toList()).contains("admin1", "1", "keyword");
    }

    @Test
    void 허용_목록_밖_열_정렬_조건_형은_400이고_파일이_아니다() throws Exception {
        export(admin, "{\"columns\":[\"defectId\"]}").andExpect(status().isBadRequest());           // 열 정의에 없는 필드
        export(admin, "{\"columns\":[\"defectCd\",\"defectCd\"]}").andExpect(status().isBadRequest());
        export(admin, "{\"sort\":[\"remark,asc\"]}").andExpect(status().isBadRequest());
        export(admin, "{\"sort\":[\"defectCd,asc;drop table x\"]}").andExpect(status().isBadRequest());
        export(admin, "{\"useYn\":\"X\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result").value("ERROR"));
        export(admin, "{\"keyword\":{\"a\":1}}").andExpect(status().isBadRequest());
        export(admin, "{\"keyword\":[[\"a\"]]}").andExpect(status().isBadRequest());
        assertThat(sheet(admin, "{\"keyword\":[\"a\",\"b\"]}", 0)).hasSize(1);            // 배열 = "a,b" 한 글자 조건(목록 쿼리와 같음) → 0행
        // 본문의 page·size 는 무시 — 큰 size 로 상한을 넘기지 못한다
        assertThat(sheet(admin, "{\"page\":3,\"size\":100000}", 0)).hasSize(26);
    }

    @Test
    void 건수_상한을_넘으면_파일을_만들지_않는다() throws Exception {
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) "
                + "values (?, 'EXCEL_EXPORT_MAX_ROWS', '100', 't', 't')", comp);
        for (int i = 26; i <= 101; i++) {
            accounts.exec(comp, "insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by) "
                    + "values (?, ?, 'x', 'WELD', 't', 't')", comp, "D" + i);
        }
        export(admin, "{}").andExpect(status().is4xxClientError()).andExpect(jsonPath("$.code").value("TOO_MANY_ROWS"));
        assertThat(sheet(admin, "{\"keyword\":\"용접\"}", 0)).hasSize(13);
    }

    @Test
    void EXCEL_권한_없으면_403() throws Exception {
        long v = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, v, "VIEW", "mst-defect", "SEARCH", "SAVE");
        RequestPostProcessor viewer = accounts.as(comp, v, "viewer1");
        mvc.perform(get(D + "/grid").with(viewer)).andExpect(status().isOk());          // 조회는 된다
        export(viewer, "{}").andExpect(status().isForbidden()).andExpect(jsonPath("$.result").value("ERROR"));
        mvc.perform(post(D + "/export").contentType(MediaType.APPLICATION_JSON).content("{}").with(admin))
                .andExpect(status().isForbidden());                                       // CSRF 없이
    }

    @Test
    void A사_파일에_B사_행이_없다() throws Exception {
        String other = accounts.newComp("GY");
        long u = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, u, "ADM", "mst-defect", "SEARCH", "SAVE", "EXCEL");
        RequestPostProcessor b = accounts.as(other, u, "admin1");
        create(b, "{\"defectCd\":\"B-ONLY\",\"defectNm\":\"B사 불량\",\"catCd\":\"WELD\"}");
        List<String> a = sheet(admin, "{\"columns\":[\"defectCd\"]}", 0).stream().map(r -> r.get(0)).toList();
        assertThat(a).hasSize(26).doesNotContain("B-ONLY");
        assertThat(sheet(b, "{\"columns\":[\"defectCd\"]}", 0).stream().map(r -> r.get(0)).toList()).containsExactly("불량 코드", "B-ONLY");
        // 본문에 회사코드를 넣어도 소용없다(조건 이름이 아님 — 무시)
        assertThat(sheet(b, "{\"compCd\":\"" + comp + "\",\"columns\":[\"defectCd\"]}", 0)).hasSize(2);
    }
}
