package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * C15 (docs/33 13장) — 설비 예비품 엑셀: 연결 표(목록과 같은 조건)·사용 이력(고른 연결 + 기간). 코드는 이름, 수량은 숫자 칸.
 * 권한(EXCEL), 기간 필수, A사로 B사.
 */
@AutoConfigureMockMvc
class CmsSpareExcelTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String S = "/api/cms/spares";
    private static final String M = "/api/cms/mwos";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    String today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("SX");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "CMS", "cms-spare", "SEARCH", "SAVE", "EXCEL");
        accounts.grant(comp, u, "CMS", "cms-mwo", "SEARCH", "SAVE", "APPROVE", "DELETE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long press = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', '프레스류', 't', 't') returning class_id",
                Long.class, comp);
        accounts.exec(comp, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, 'P1', 'PRS-01', '프레스', ?, 't', 't')",
                comp, press);
        accounts.exec(comp, "insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by) values (?, 'BRG-6204', '베어링', 'CON', 'BUY', 'EA', 't', 't')", comp);
        accounts.exec(comp, "insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by) values (?, 'OIL-68', '유압유', 'CON', 'BUY', 'L', 't', 't')", comp);
        today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
    }

    private ResultActions postJson(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        Object v = JsonPath.read(m.getResponse().getContentAsString(), "$.data");
        return v instanceof Number n ? n.longValue() : ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.mwoId")).longValue();
    }

    /** 머리 줄 제외. 숫자 칸은 "N:값" */
    private List<List<String>> rows(RequestPostProcessor who, String url, String body, List<ColumnSpec> cols) throws Exception {
        byte[] x = postJson(who, url, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        List<List<String>> out = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            Sheet sh = wb.getSheetAt(0);
            for (int i = 1; i <= sh.getLastRowNum(); i++) {
                Row r = sh.getRow(i);
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < cols.size(); c++) {
                    Cell cell = r.getCell(c);
                    cells.add(cell == null ? "" : cell.getCellType() == CellType.NUMERIC ? "N:" + cell.getNumericCellValue() : cell.getStringCellValue());
                }
                out.add(cells);
            }
        }
        return out;
    }

    private static int col(List<ColumnSpec> cols, String field) {
        for (int i = 0; i < cols.size(); i++) {
            if (cols.get(i).field().equals(field)) {
                return i;
            }
        }
        throw new IllegalArgumentException(field);
    }

    @Test
    void 연결표와_사용이력_엑셀은_조회와_같은_조건_코드는_이름_수량은_숫자() throws Exception {
        List<ColumnSpec> sc = SpareService.SPARE_COLUMNS;
        List<ColumnSpec> uc = SpareService.USAGE_COLUMNS;
        id(postJson(admin, S, "{\"targetKind\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"itemCd\":\"BRG-6204\",\"installQty\":\"2\",\"keySpareYn\":\"Y\"}"));
        id(postJson(admin, S, "{\"targetKind\":\"CLASS\",\"targetCd\":\"PRESS\",\"itemCd\":\"OIL-68\",\"installQty\":\"20.5\",\"keySpareYn\":\"N\"}"));
        List<List<String>> all = rows(admin, S + "/export", "{}", sc);
        assertThat(all).hasSize(2);
        List<String> eq = all.stream().filter(r -> r.get(col(sc, "itemCd")).equals("BRG-6204")).findFirst().orElseThrow();
        assertThat(eq.get(col(sc, "targetKind"))).isEqualTo("설비");
        assertThat(eq.get(col(sc, "keySpareYn"))).isEqualTo("핵심");
        assertThat(eq.get(col(sc, "installQty"))).isEqualTo("N:2.0");
        List<String> cl = all.stream().filter(r -> r.get(col(sc, "itemCd")).equals("OIL-68")).findFirst().orElseThrow();
        assertThat(cl.get(col(sc, "targetKind"))).isEqualTo("설비 클래스");
        assertThat(cl.get(col(sc, "installQty"))).isEqualTo("N:20.5");
        assertThat(cl.get(col(sc, "unitCd"))).isEqualTo("L");
        // 목록과 같은 조건
        assertThat(rows(admin, S + "/export", "{\"targetKind\":\"CLASS\"}", sc)).hasSize(1);
        assertThat(rows(admin, S + "/export", "{\"keyword\":\"brg\"}", sc)).hasSize(1);
        // 사용 이력: 작업지시 줄 2개(베어링 1·유압유 3.25) → 설비 + 품목 조건으로 1줄
        long m = id(postJson(admin, M, "{\"mwoType\":\"CM\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"title\":\"정비\"}"));
        id(postJson(admin, M + "/" + m + "/parts", "{\"itemCd\":\"BRG-6204\",\"qty\":\"1\"}"));
        id(postJson(admin, M + "/" + m + "/parts", "{\"itemCd\":\"OIL-68\",\"qty\":\"3.25\"}"));
        String range = "\"from\":\"" + today + "\",\"to\":\"" + today + "\"";
        List<List<String>> used = rows(admin, S + "/usage/export", "{" + range + ",\"equipCd\":\"PRS-01\",\"itemCd\":\"OIL-68\"}", uc);
        assertThat(used).hasSize(1);
        assertThat(used.get(0).get(col(uc, "qty"))).isEqualTo("N:3.25");
        assertThat(used.get(0).get(col(uc, "mwoType"))).isEqualTo("개선");
        assertThat(used.get(0).get(col(uc, "createdBy"))).isEqualTo("admin1");
        assertThat(used.get(0).get(col(uc, "usedAtText"))).startsWith(today);   // 완료 전 = 기록 시각(잠정 사용일, docs/33 16.1)
        assertThat(rows(admin, S + "/usage/export", "{" + range + ",\"classCd\":\"PRESS\"}", uc)).hasSize(2);
    }

    @Test
    void 조회만_권한은_403_사용이력_기간없으면_400_B사는_A사_행을_못본다() throws Exception {
        id(postJson(admin, S, "{\"targetKind\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"itemCd\":\"BRG-6204\",\"installQty\":\"2\",\"keySpareYn\":\"Y\"}"));
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", "cms-spare", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer1");
        postJson(v, S + "/export", "{}").andExpect(status().isForbidden());
        postJson(v, S + "/usage/export", "{\"from\":\"" + today + "\",\"to\":\"" + today + "\"}").andExpect(status().isForbidden());
        postJson(admin, S + "/usage/export", "{}").andExpect(status().isBadRequest());
        postJson(admin, S + "/export", "{\"useYn\":\"X\"}").andExpect(status().isBadRequest());
        String other = accounts.newComp("SY");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "CMS", "cms-spare", "SEARCH", "EXCEL");
        assertThat(rows(accounts.as(other, bu, "admin1"), S + "/export", "{\"useYn\":\"\"}", SpareService.SPARE_COLUMNS)).isEmpty();
    }
}
