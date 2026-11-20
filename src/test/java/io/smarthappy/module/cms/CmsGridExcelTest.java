package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
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

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * C21 grid 엑셀 통일 1묶음 (docs/33 13.7) — 작업지시·점검 실행 grid 메뉴 엑셀: grid 와 같은 조건·정렬·보이는 열(요청 순서),
 * 화면이 꾸미는 칸은 사람이 읽는 열(우선순위 이름·예정 상태 이름·실시 시각 현지 글자), 열 없이 부르면 작업지시는 보고서형(C14) 그대로,
 * 허용 목록 밖 열·정렬 400, EXCEL 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class CmsGridExcelTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String M = "/api/cms/mwos";
    private static final String R = "/api/cms/chk-runs";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    LocalDate today;
    long prs1;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("GE");
        long u = accounts.newUser(comp, "admin1", PW);
        grant(comp, u);
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        prs1 = accounts.query(comp, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, 'P1', 'PRS-01', 'p', ?, 't', 't') returning equip_id",
                Long.class, comp, cls);
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    private void grant(String c, long u) {
        accounts.grant(c, u, "CMS", "cms-mwo", "SEARCH", "SAVE", "EXCEL");
        accounts.grant(c, u, "CMS", "cms-breakdown", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-chk-tmpl", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-pm-plan", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-chk-run", "SEARCH", "SAVE", "EXCEL");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private long create(String url, String body) throws Exception {
        String s = mvc.perform(json(post(url), body).with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Object v = JsonPath.read(s, "$.data");
        return v instanceof Number n ? n.longValue() : ((Number) ((Map<?, ?>) v).get("mwoId")).longValue();
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
                    cells.add(c == null ? "" : c.getCellType() == CellType.NUMERIC
                            ? (DateUtil.isCellDateFormatted(c) ? c.getLocalDateTimeCellValue().toLocalDate().toString() : String.valueOf((long) c.getNumericCellValue()))
                            : c.getStringCellValue());
                }
                out.add(cells);
            }
        }
        return out;
    }

    private String range() {
        return "\"from\":\"" + today.minusDays(7) + "\",\"to\":\"" + today + "\"";
    }

    @Test
    void 작업지시_grid엑셀은_정렬_보이는열_우선순위이름_열없이는_보고서형() throws Exception {
        create(M, "{\"mwoType\":\"CM\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"title\":\"베어링 교체\",\"priority\":1}");
        create(M, "{\"mwoType\":\"CM\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"title\":\"도장\",\"priority\":3}");
        create("/api/cms/breakdowns", "{\"targetCd\":\"PRS-01\",\"symptomText\":\"누유\"}");
        List<List<String>> s = sheet(admin, M, "{" + range() + ",\"mwoType\":\"CM\",\"sort\":[\"title,asc\"],\"columns\":[\"title\",\"priority\",\"status\"]}");
        assertThat(s.get(0)).containsExactly("제목", "우선순위", "상태");
        assertThat(s).hasSize(3);                                                    // 머리 + 개선 2(고장 빠짐)
        assertThat(s.get(1)).containsExactly("도장", "낮음", "접수");                     // 제목 오름차순, 우선순위 이름, 상태 코드 이름
        assertThat(s.get(2)).containsExactly("베어링 교체", "긴급", "접수");
        // 열 없이(툴바 "엑셀 내려받기") = 보고서형 전체 칸(C14) 그대로 — 21칸
        assertThat(sheet(admin, M, "{" + range() + "}").get(0)).hasSize(MwoService.COLUMNS.size());
        export(admin, M, "{" + range() + ",\"columns\":[\"workText\"]}").andExpect(status().isBadRequest());    // grid 열에 없는 칸
        export(admin, M, "{" + range() + ",\"columns\":[\"priorityNm\"]}").andExpect(status().isBadRequest());  // 엑셀 전용 이름
        export(admin, M, "{" + range() + ",\"columns\":[\"title\"],\"sort\":[\"workText,asc\"]}").andExpect(status().isBadRequest());
        export(admin, M, "{\"columns\":[\"title\"]}").andExpect(status().isBadRequest());                        // 기간 필수(grid 와 같다)
    }

    @Test
    void 점검실행_grid엑셀은_상태이름_실시시각_현지글자_조건정렬() throws Exception {
        long tmpl = create("/api/cms/chk-tmpls", "{\"tmplCd\":\"PRS-D\",\"tmplNm\":\"프레스 일상\"}");
        mvc.perform(json(put("/api/cms/chk-tmpls/" + tmpl + "/items?rowVer=0"), "[{\"seq\":1,\"itemNm\":\"클러치 소음\",\"judgeType\":\"OKNG\"}]").with(admin))
                .andExpect(status().isOk());
        long plan = create("/api/cms/pm-plans", "{\"planCd\":\"PM-D\",\"planNm\":\"일상\",\"targetCd\":\"PRS-01\",\"tmplCd\":\"PRS-D\",\"execMode\":\"CHECK\","
                + "\"calUnit\":\"DAY\",\"calInterval\":1,\"scheduleBasis\":\"FIXED\",\"anchorDate\":\"" + today.plusDays(30) + "\"}");
        LocalDate d1 = today.minusDays(1);
        LocalDate d3 = today.minusDays(3);
        long done = run(plan, tmpl, d1);
        accounts.exec(comp, "update cms_chk_run set status = 'DONE', result = 'OK', performed_at = ?::timestamptz where run_id = ?", d1 + "T09:05:00+09:00", done);
        run(plan, tmpl, d3);                                                          // 열림 + 예정일 지남 = 지남
        List<List<String>> s = sheet(admin, R, "{" + range() + ",\"sort\":[\"dueDate,desc\"],\"columns\":[\"dueDate\",\"state\",\"performedAt\",\"status\"]}");
        assertThat(s.get(0)).containsExactly("예정일", "예정 상태", "수행 시각", "상태");
        assertThat(s.get(1)).containsExactly(d1.toString(), "기한 안 완료", d1 + " 09:05", "완료");   // 공장 현지 시각 글자
        assertThat(s.get(2)).containsExactly(d3.toString(), "지남", "", "예정");
        assertThat(sheet(admin, R, "{" + range() + ",\"status\":\"OPEN\"}")).hasSize(2);
        export(admin, R, "{" + range() + ",\"columns\":[\"stateNm\"]}").andExpect(status().isBadRequest());
        export(admin, R, "{" + range() + ",\"sort\":[\"state,asc\"]}").andExpect(status().isBadRequest());     // 계산 값은 정렬 밖
        export(admin, R, "{" + range() + ",\"status\":\"NOPE\"}").andExpect(status().isBadRequest());
        export(admin, R, "{}").andExpect(status().isBadRequest());                                             // 기간 필수
    }

    private long run(long plan, long tmpl, LocalDate due) {
        return accounts.query(comp, """
                insert into cms_chk_run (comp_cd, plan_id, target_type, equip_id, tmpl_id, due_date, created_by, updated_by)
                values (?, ?, 'EQUIP', ?, ?, ?, 't', 't') returning run_id""", Long.class, comp, plan, prs1, tmpl, due);
    }

    @Test
    void EXCEL_권한없으면_403_B사는_A사_행을_못받는다() throws Exception {
        create(M, "{\"mwoType\":\"CM\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"title\":\"x\"}");
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", "cms-mwo", "SEARCH");
        accounts.grant(comp, viewer, "VIEW", "cms-chk-run", "SEARCH");
        RequestPostProcessor vw = accounts.as(comp, viewer, "viewer1");
        export(vw, M, "{" + range() + ",\"columns\":[\"title\"]}").andExpect(status().isForbidden());
        export(vw, R, "{" + range() + "}").andExpect(status().isForbidden());
        String other = accounts.newComp("GF");
        long bu = accounts.newUser(other, "admin1", PW);
        grant(other, bu);
        accounts.addPlant(other, "P1", "1공장");
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        assertThat(sheet(b, M, "{" + range() + ",\"columns\":[\"title\"]}")).hasSize(1);              // 머리만
        assertThat(sheet(b, R, "{" + range() + "}")).hasSize(1);
    }
}
