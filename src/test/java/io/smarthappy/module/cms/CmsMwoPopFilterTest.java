package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.module.cms.link.BreakdownReport;
import io.smarthappy.module.cms.link.BreakdownReport.Report;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 작업지시 "POP 신고" 필터 (docs/33 13.9) — 목록·grid·두 엑셀이 같은 조건(source=POP → POP 고장 신고만), 모르는 값 400,
 * 보고서형 엑셀 신고자 칸 = 작업자(POP 단말)(화면 상세와 같은 글자), A사로 B사.
 */
@AutoConfigureMockMvc
class CmsMwoPopFilterTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String M = "/api/cms/mwos";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    ObjectProvider<BreakdownReport> link;

    String comp;
    RequestPostProcessor admin;
    String from;
    String to;
    long prs1;
    long w1;
    long t1;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("PF");
        long u = accounts.newUser(comp, "admin1", PW);
        grant(comp, u);
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        prs1 = accounts.query(comp, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, 'P1', 'PRS-01', 'p', ?, 't', 't') returning equip_id",
                Long.class, comp, cls);
        w1 = accounts.query(comp, "insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, use_yn, created_by, updated_by) values (?, 'W-001', '박현장', 'P1', 'Y', 't', 't') returning worker_id",
                Long.class, comp);
        t1 = accounts.query(comp, "insert into sys_terminal (comp_cd, terminal_cd, terminal_nm, plant_cd, created_by, updated_by) values (?, 'TAB-01', 'TAB-01', 'P1', 't', 't') returning terminal_id",
                Long.class, comp);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        from = today.minusDays(7).toString();
        to = today.toString();
        // 사무 계정 신고 1 + 개선 1 + POP 신고 1
        create("/api/cms/breakdowns", "{\"targetCd\":\"PRS-01\",\"symptomText\":\"사무실 신고\"}");
        create(M, "{\"mwoType\":\"CM\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"title\":\"도장\"}");
        runner.callAs(comp, "t" + t1 + ":TAB-01", () -> BreakdownReport.of(link).report(new Report(prs1, null, "현장 누유", w1, t1, "req-pop-0001")));
    }

    private void grant(String c, long u) {
        accounts.grant(c, u, "CMS", "cms-mwo", "SEARCH", "SAVE", "EXCEL");
        accounts.grant(c, u, "CMS", "cms-breakdown", "SEARCH", "SAVE");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void create(String url, String body) throws Exception {
        mvc.perform(json(post(url), body).with(admin)).andExpect(status().isOk());
    }

    private List<List<String>> sheet(RequestPostProcessor who, String body) throws Exception {
        byte[] x = mvc.perform(json(post(M + "/export"), body).with(who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<List<String>> out = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            Sheet s = wb.getSheetAt(0);
            for (int i = 0; i <= s.getLastRowNum(); i++) {
                Row r = s.getRow(i);
                List<String> cells = new ArrayList<>();
                for (int j = 0; j < r.getLastCellNum(); j++) {
                    cells.add(r.getCell(j) == null ? "" : r.getCell(j).toString());
                }
                out.add(cells);
            }
        }
        return out;
    }

    @Test
    void POP신고_필터는_목록_grid_두엑셀이_같은조건_신고자는_작업자_POP단말() throws Exception {
        mvc.perform(get(M).param("from", from).param("to", to).with(admin)).andExpect(jsonPath("$.data.length()").value(3));
        mvc.perform(get(M).param("from", from).param("to", to).param("source", "POP").with(admin))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].reportedWorkerNm").value("박현장"));
        mvc.perform(get(M + "/grid").param("from", from).param("to", to).param("source", "POP").with(admin))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.list[0].mwoType").value("BM"));
        mvc.perform(get(M + "/grid").param("from", from).param("to", to).param("source", "POP").param("mwoType", "CM").with(admin))
                .andExpect(jsonPath("$.total").value(0));
        // grid 메뉴 엑셀 = 머리 + 1
        assertThat(sheet(admin, "{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"source\":\"POP\",\"columns\":[\"mwoType\",\"title\"]}")).hasSize(2);
        // 보고서형 엑셀: 신고자 칸 = 화면 상세와 같은 글자 — POP = "박현장(POP TAB-01)", 사무 = 사용자 이름, 자동(개선은 사무 등록) 그대로
        List<List<String>> all = sheet(admin, "{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}");
        int col = all.get(0).indexOf("신고자");
        assertThat(col).isGreaterThanOrEqualTo(0);
        assertThat(all.subList(1, all.size())).extracting(r -> r.get(col)).contains("박현장(POP TAB-01)");
        List<List<String>> pop = sheet(admin, "{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"source\":\"POP\"}");
        assertThat(pop).hasSize(2);
        assertThat(pop.get(1).get(col)).isEqualTo("박현장(POP TAB-01)");
    }

    @Test
    void 모르는_경로는_400_B사는_A사_POP신고를_못본다() throws Exception {
        mvc.perform(get(M).param("from", from).param("to", to).param("source", "OFFICE").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(M + "/grid").param("from", from).param("to", to).param("source", "pop").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(json(post(M + "/export"), "{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"source\":\"X\"}").with(admin))
                .andExpect(status().isBadRequest());
        String other = accounts.newComp("PG");
        long bu = accounts.newUser(other, "admin1", PW);
        grant(other, bu);
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        mvc.perform(get(M).param("from", from).param("to", to).param("source", "POP").with(b)).andExpect(jsonPath("$.data.length()").value(0));
        assertThat(sheet(b, "{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"source\":\"POP\"}")).hasSize(1);
    }
}
