package io.smarthappy.module.plm;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PLM 주 목록 엑셀 (docs/39 25장) — 지시·요청 POST …/export: grid 와 같은 조건·정렬(허용 밖 400)·보이는 열 순서·코드 이름,
 * EXCEL 권한 없으면 403, 없는 품목 코드 = 머리만, A사로 B사.
 */
@AutoConfigureMockMvc
class PlmListExcelTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String ECO = "/api/plm/ecos";
    private static final String ECR = "/api/plm/ecrs";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor me;
    RequestPostProcessor viewer;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PLX");
        long u = accounts.newUser(comp, "eng1", PW);
        accounts.grant(comp, u, "ENG", "plm-eco", "SEARCH", "EXCEL");
        accounts.grant(comp, u, "ENG2", "plm-ecr", "SEARCH", "EXCEL");
        me = accounts.as(comp, u, "eng1");
        long v = accounts.newUser(comp, "view1", PW);
        accounts.grant(comp, v, "VIEW", "plm-eco", "SEARCH");
        accounts.grant(comp, v, "VIEW2", "plm-ecr", "SEARCH");
        viewer = accounts.as(comp, v, "view1");
        String[] t = {"다", "가", "마", "나", "라"};
        for (int i = 0; i < t.length; i++) {
            accounts.exec(comp, """
                    insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, created_by, updated_by)
                    values (?, ?, ?, 'QUALITY', (now() at time zone 'Asia/Seoul')::date, ?, 't', 't')""", comp, "ECO-" + (i + 1), "지시 " + t[i], u);
            accounts.exec(comp, """
                    insert into plm_ecr (comp_cd, ecr_no, title, reason_cd, requester_id, created_by, updated_by)
                    values (?, ?, ?, 'QUALITY', ?, 't', 't')""", comp, "ECR-" + (i + 1), "요청 " + t[i], u);
        }
        accounts.exec(comp, "update plm_eco set status = 'CANCELLED', cancel_reason = '시험' where eco_no = 'ECO-3'");
    }

    private ResultActions export(RequestPostProcessor who, String base, String body) throws Exception {
        return mvc.perform(post(base + "/export").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    /** 첫 시트 → 행별 글자 */
    private List<List<String>> sheet(RequestPostProcessor who, String base, String body) throws Exception {
        byte[] x = export(who, base, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
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
    void 지시_엑셀은_grid_조건_정렬_보이는_열_순서와_코드_이름() throws Exception {
        List<List<String>> s = sheet(me, ECO, "{\"sort\":[\"title,asc\"],\"columns\":[\"title\",\"ecoNo\",\"status\"]}");
        assertThat(s.get(0)).containsExactly("제목", "지시 번호", "상태");
        assertThat(s.subList(1, s.size()).stream().map(r -> r.get(1)).toList()).containsExactly("ECO-2", "ECO-4", "ECO-1", "ECO-5", "ECO-3");
        assertThat(s.get(5)).containsExactly("지시 마", "ECO-3", "취소");
        // 조건 = grid 와 같은 상태·검색어
        assertThat(sheet(me, ECO, "{\"status\":\"CANCELLED\",\"columns\":[\"ecoNo\"]}")).hasSize(2);
        assertThat(sheet(me, ECO, "{\"keyword\":\"지시 가\",\"columns\":[\"ecoNo\"]}").get(1)).containsExactly("ECO-2");
        // 기본 정렬 = 번호 내림, 열 지정 없으면 grid 열 전부
        List<List<String>> all = sheet(me, ECO, "{}");
        assertThat(all.get(0)).hasSize(EcoService.GRID_COLUMNS.size());
        assertThat(all.get(1).get(0)).isEqualTo("ECO-5");
        // 없는 품목 코드 — grid 처럼 빈 결과(머리만)
        assertThat(sheet(me, ECO, "{\"itemCd\":\"NOPE\"}")).hasSize(1);
    }

    @Test
    void 요청_엑셀도_같은_규칙() throws Exception {
        List<List<String>> s = sheet(me, ECR, "{\"sort\":[\"ecrNo,asc\"],\"columns\":[\"ecrNo\",\"status\"]}");
        assertThat(s).hasSize(6);
        assertThat(s.get(1)).containsExactly("ECR-1", "열림");
    }

    @Test
    void E_허용_밖_정렬_열_값은_400_EXCEL_권한_없으면_403_A사로_B사는_빈_파일() throws Exception {
        export(me, ECO, "{\"sort\":[\"targetCnt,asc\"]}").andExpect(status().isBadRequest());
        export(me, ECO, "{\"sort\":[\"title,asc\",\"ecoNo,asc\",\"status,asc\",\"ownerNm,asc\"]}").andExpect(status().isBadRequest());
        export(me, ECO, "{\"columns\":[\"description\"]}").andExpect(status().isBadRequest());
        export(me, ECO, "{\"status\":\"NOPE\"}").andExpect(status().isBadRequest());
        export(me, ECR, "{\"sort\":[\"requesterNm,asc\"]}").andExpect(status().isBadRequest());
        export(viewer, ECO, "{}").andExpect(status().isForbidden());
        export(viewer, ECR, "{}").andExpect(status().isForbidden());
        // B사 사용자 — 자기 회사 행만(A사 지시 0건)
        String compB = accounts.newComp("PLXB");
        long b = accounts.newUser(compB, "engB", PW);
        accounts.grant(compB, b, "ENG", "plm-eco", "SEARCH", "EXCEL");
        assertThat(sheet(accounts.as(compB, b, "engB"), ECO, "{}")).hasSize(1);
    }
}
