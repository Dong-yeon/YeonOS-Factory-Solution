package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * C23 grid 엑셀 통일 3묶음 (docs/33 13.10) — 알람 정의·상태 사유·점검표 grid 메뉴 엑셀: grid 와 같은 조건·정렬·보이는 열(요청 순서),
 * 화면이 꾸미는 칸은 표시 글자(자동 신고·계획 = Y 만 글자, 대상 종류 = 이름), 허용 목록 밖 열·정렬·조건 400, EXCEL 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class CmsGridExcel3Test extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String D = "/api/cms/alarm-defs";
    private static final String R = "/api/cms/state-reasons";
    private static final String T = "/api/cms/chk-tmpls";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("GZ");
        long u = accounts.newUser(comp, "admin1", PW);
        grant(comp, u);
        admin = accounts.as(comp, u, "admin1");
        accounts.exec(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't')", comp);
        create(D, "{\"alarmCd\":\"E-B\",\"alarmNm\":\"유압 저하\",\"severity\":\"CRITICAL\",\"autoBmYn\":\"Y\"}");
        create(D, "{\"alarmCd\":\"E-A\",\"alarmNm\":\"온도 경고\",\"severity\":\"WARN\",\"autoBmYn\":\"N\"}");
        create(R, "{\"reasonCd\":\"PM-C\",\"reasonNm\":\"정기 보전\",\"stateCat\":\"SDT\",\"plannedYn\":\"Y\"}");
        create(R, "{\"reasonCd\":\"BRK-A\",\"reasonNm\":\"금형 고장\",\"stateCat\":\"UDT\",\"plannedYn\":\"N\"}");
        create(T, "{\"tmplCd\":\"TM-E\",\"tmplNm\":\"프레스 일상\",\"targetKind\":\"EQUIP\"}");
        create(T, "{\"tmplCd\":\"TM-M\",\"tmplNm\":\"금형 점검\",\"targetKind\":\"ASSET\"}");
    }

    private void grant(String c, long u) {
        accounts.grant(c, u, "CMS", "cms-alarm-def", "SEARCH", "SAVE", "EXCEL");
        accounts.grant(c, u, "CMS", "cms-state-reason", "SEARCH", "SAVE", "EXCEL");
        accounts.grant(c, u, "CMS", "cms-chk-tmpl", "SEARCH", "SAVE", "EXCEL");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void create(String url, String body) throws Exception {
        mvc.perform(json(post(url), body).with(admin)).andExpect(status().isOk());
    }

    private ResultActions export(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(json(post(url + "/export"), body).with(who));
    }

    private List<List<String>> sheet(RequestPostProcessor who, String url, String body) throws Exception {
        byte[] x = export(who, url, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<List<String>> out = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            Sheet s = wb.getSheetAt(0);
            int width = s.getRow(0).getLastCellNum();
            for (int i = 0; i <= s.getLastRowNum(); i++) {
                Row r = s.getRow(i);
                List<String> cells = new ArrayList<>();
                for (int j = 0; j < width; j++) {
                    Cell c = r.getCell(j);
                    cells.add(c == null ? "" : c.getCellType() == CellType.NUMERIC ? String.valueOf((long) c.getNumericCellValue()) : c.getStringCellValue());
                }
                out.add(cells);
            }
        }
        return out;
    }

    @Test
    void 알람정의_grid엑셀은_자동신고_표시글자_심각도이름_정렬_조건() throws Exception {
        List<List<String>> s = sheet(admin, D, "{\"sort\":[\"alarmCd,desc\"],\"columns\":[\"alarmCd\",\"severity\",\"autoBmYn\"]}");
        assertThat(s.get(0)).containsExactly("알람 코드", "심각도", "자동 고장 신고");
        assertThat(s.get(1).get(0)).isEqualTo("E-B");
        assertThat(s.get(1).get(2)).isEqualTo("자동 신고");                                   // Y = 화면과 같은 글자
        assertThat(s.get(2)).containsExactly("E-A", s.get(2).get(1), "");                   // N = 빈 칸
        assertThat(s.get(1).get(1)).isNotEqualTo("CRITICAL");                               // 코드 이름
        assertThat(sheet(admin, D, "{\"keyword\":\"e-a\",\"columns\":[\"alarmCd\"]}")).hasSize(2);
        export(admin, D, "{\"columns\":[\"autoBmNm\"]}").andExpect(status().isBadRequest());     // 엑셀 전용 이름
        export(admin, D, "{\"sort\":[\"remark,asc\"]}").andExpect(status().isBadRequest());
        export(admin, D, "{\"useYn\":\"X\"}").andExpect(status().isBadRequest());
    }

    @Test
    void 상태사유_grid엑셀은_계획_표시글자_분류이름_기본순서() throws Exception {
        List<List<String>> s = sheet(admin, R, "{\"columns\":[\"reasonCd\",\"plannedYn\"]}");
        assertThat(s.get(0)).containsExactly("코드", "계획 여부");
        assertThat(s.subList(1, s.size())).containsExactly(List.of("PM-C", "계획"), List.of("BRK-A", ""));   // 분류(SDT → UDT) → 순서 → 코드
        assertThat(sheet(admin, R, "{\"stateCat\":\"UDT\",\"columns\":[\"reasonCd\"]}")).hasSize(2);
        export(admin, R, "{\"columns\":[\"plannedNm\"]}").andExpect(status().isBadRequest());
        export(admin, R, "{\"stateCat\":\"NOPE\"}").andExpect(status().isBadRequest());
    }

    @Test
    void 점검표_grid엑셀은_대상종류_이름_정렬() throws Exception {
        List<List<String>> s = sheet(admin, T, "{\"sort\":[\"tmplCd,desc\"],\"columns\":[\"tmplCd\",\"targetKind\",\"itemCnt\"]}");
        assertThat(s.get(0)).containsExactly("코드", "대상 종류", "항목");
        assertThat(s.get(1).subList(0, 2)).containsExactly("TM-M", "금형·지그");
        assertThat(s.get(2).subList(0, 2)).containsExactly("TM-E", "설비");
        export(admin, T, "{\"columns\":[\"targetKindNm\"]}").andExpect(status().isBadRequest());
        export(admin, T, "{\"sort\":[\"itemCnt,desc\"]}").andExpect(status().isBadRequest());     // 계산 값은 정렬 밖
    }

    @Test
    void EXCEL_권한없으면_403_B사는_A사_행을_못받는다() throws Exception {
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", "cms-alarm-def", "SEARCH");
        accounts.grant(comp, viewer, "VIEW", "cms-state-reason", "SEARCH");
        accounts.grant(comp, viewer, "VIEW", "cms-chk-tmpl", "SEARCH");
        RequestPostProcessor vw = accounts.as(comp, viewer, "viewer1");
        export(vw, D, "{}").andExpect(status().isForbidden());
        export(vw, R, "{}").andExpect(status().isForbidden());
        export(vw, T, "{}").andExpect(status().isForbidden());
        String other = accounts.newComp("GW");
        long bu = accounts.newUser(other, "admin1", PW);
        grant(other, bu);
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        assertThat(sheet(b, D, "{}")).hasSize(1);
        assertThat(sheet(b, R, "{}")).hasSize(1);
        assertThat(sheet(b, T, "{}")).hasSize(1);
        assertThat(sheet(admin, T, "{}")).hasSize(3);
    }
}
