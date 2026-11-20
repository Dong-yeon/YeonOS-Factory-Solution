package io.smarthappy.module.master.bom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/** BOM 엑셀 올리기 이상 사용 (QA 세션, docs/qa/bom-3a.md) — 1,000행·같은 키 두 행·사용 중지·대체 그룹 열만·숫자 셀·다른 회사 품목 */
@AutoConfigureMockMvc
class BomImportAbuseTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String BASE = "/api/platform/import";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("BJ");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-bom", "SEARCH", "SAVE", "APPROVE", "DELETE", "EXCEL");
        accounts.grant(comp, userId, "MASTER", "mst-item", "SEARCH", "SAVE", "EXCEL");
        admin = accounts.as(comp, userId, "admin1");
        for (String[] it : new String[][] {{"FG1", "EA", "N"}, {"SA1", "EA", "N"}, {"SA2", "EA", "N"}, {"RM1", "KG", "Y"},
                {"OLD1", "EA", "N"}}) {
            item(it[0], it[1], it[2]);
        }
        accounts.exec(comp, "update mst_item set use_yn = 'N' where item_cd = 'OLD1'");
    }

    private void item(String cd, String unit, String lot) {
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn,
                                      created_by, updated_by)
                values (?, ?, ?, 'RM', 'BUY', ?, ?, 't', 't')""", comp, cd, cd + " 품목", unit, lot);
    }

    /** 템플릿에 행을 채운다. 값이 Number 면 숫자 셀, String 이면 글자 셀. 열 = 모품목·순번·자품목·소요량·단위·유형·손실률·고정·투입·공정·대체그룹·우선순위·비율 */
    private byte[] fill(List<Object[]> rows) throws Exception {
        byte[] tpl = mvc.perform(post(BASE + "/templates/MST_BOM/file").with(csrf()).with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(tpl))) {
            Sheet s = wb.getSheetAt(0);
            for (int i = 0; i < rows.size(); i++) {
                Row r = s.createRow(i + 1);
                Object[] row = rows.get(i);
                for (int c = 0; c < row.length; c++) {
                    if (row[c] instanceof Number n) {
                        r.createCell(c).setCellValue(n.doubleValue());
                    } else if (row[c] != null) {
                        r.createCell(c).setCellValue((String) row[c]);
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private ResultActions upload(byte[] file) throws Exception {
        return mvc.perform(multipart(BASE + "/templates/MST_BOM/jobs")
                .file(new MockMultipartFile("file", "bom.xlsx", XLSX, file)).with(csrf()).with(admin));
    }

    private long jobId(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.jobId")).longValue();
    }

    private ResultActions execute(long job) throws Exception {
        int ver = accounts.query(comp, "select row_ver from sys_import_job where job_id = ?", Integer.class, job);
        return mvc.perform(post(BASE + "/jobs/" + job + "/execute").with(csrf()).with(admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rowVer\":" + ver + ",\"skipErrors\":false}"));
    }

    private ResultActions rows(long job) throws Exception {
        return mvc.perform(get(BASE + "/jobs/" + job + "/rows").param("size", "100").with(admin));
    }

    @Test
    void 천행_모품목100x행10_검증과_실행_시간() throws Exception {
        List<String> parents = new ArrayList<>();
        StringBuilder values = new StringBuilder();
        for (int p = 0; p < 100; p++) {
            parents.add(String.format("P%03d", p));
        }
        for (int i = 0; i < 100; i++) {
            item(parents.get(i), "EA", "N");
        }
        for (int c = 0; c < 10; c++) {
            item("C" + c, "EA", "N");
        }
        List<Object[]> rows = new ArrayList<>();
        for (String p : parents) {
            for (int c = 0; c < 10; c++) {
                rows.add(new Object[] {p, (c + 1) * 10, "C" + c, 1.5, null});
            }
        }
        byte[] file = fill(rows);
        long t0 = System.nanoTime();
        long job = jobId(upload(file).andExpect(jsonPath("$.data.newCount").value(1000))
                .andExpect(jsonPath("$.data.errorCount").value(0)));
        long t1 = System.nanoTime();
        execute(job).andExpect(status().isOk()).andExpect(jsonPath("$.data.result.inserted").value(1000))
                .andExpect(jsonPath("$.data.result.matched").value(true));
        long t2 = System.nanoTime();
        System.out.printf("[QA] MST_BOM 1000행: 검증 %d ms, 실행 %d ms%n", (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000);
        assertThat(accounts.query(comp, "select count(*) from mst_bom where status = 'DRAFT'", Integer.class)).isEqualTo(100);
        assertThat(accounts.query(comp, "select count(*) from mst_bom_line", Integer.class)).isEqualTo(1000);
        assertThat((t2 - t0) / 1_000_000).as("1,000행 검증+실행 ms").isLessThan(60_000);
    }

    @Test
    void 같은_모품목_순번_두행은_둘다_FILE_DUPLICATE_소문자_10_0_도_같은키() throws Exception {
        long job = jobId(upload(fill(List.of(new Object[] {"FG1", "10", "SA1", "1"},
                new Object[] {"fg1", "10.0", "SA2", "1"})))
                .andExpect(jsonPath("$.data.newCount").value(0))
                .andExpect(jsonPath("$.data.errorCount").value(2)));
        rows(job).andExpect(jsonPath("$.list[0].errors[*].code", hasItem("FILE_DUPLICATE")))
                .andExpect(jsonPath("$.list[1].errors[*].code", hasItem("FILE_DUPLICATE")));
    }

    /** 관찰: 파일 안 순환 검사용 간선은 검증 통과 직후 쌓인다 — 뒤에서 FILE_DUPLICATE 로 빠지는 행의 간선도 남아 다음 행이 CYCLE 로 잘못 막힌다 */
    @Test
    void 관찰_중복키로_빠질_행의_간선이_남아_뒤_행이_CYCLE() throws Exception {
        long job = jobId(upload(fill(List.of(new Object[] {"FG1", "10", "SA1", "1"},
                new Object[] {"FG1", "10", "SA2", "1"},
                new Object[] {"SA1", "10", "FG1", "1"})))
                .andExpect(jsonPath("$.data.errorCount").value(3)));
        rows(job).andExpect(jsonPath("$.list[2].errors[0].code").value("CYCLE"));
    }

    @Test
    void 사용중지_자품목_모품목은_INACTIVE() throws Exception {
        long job = jobId(upload(fill(List.of(new Object[] {"FG1", "10", "OLD1", "1"},
                new Object[] {"OLD1", "10", "SA1", "1"})))
                .andExpect(jsonPath("$.data.errorCount").value(2)));
        rows(job).andExpect(jsonPath("$.list[0].errors[0].code").value("INACTIVE"))
                .andExpect(jsonPath("$.list[0].errors[0].field").value("itemCd"))
                .andExpect(jsonPath("$.list[1].errors[0].field").value("parentCd"));
    }

    @Test
    void 대체그룹_열만_채우면_우선순위_비율_REQUIRED_가상조립에_그룹은_CODE() throws Exception {
        long job = jobId(upload(fill(List.of(
                new Object[] {"FG1", "10", "SA1", "1", null, null, null, null, null, null, "g1"},
                new Object[] {"FG1", "20", "SA2", "1", null, "PHANTOM", null, null, null, null, "G1", "1", "100"})))
                .andExpect(jsonPath("$.data.errorCount").value(2)));
        rows(job).andExpect(jsonPath("$.list[0].errors[*].field", hasItem("altPriority")))
                .andExpect(jsonPath("$.list[0].errors[*].field", hasItem("altPct")))
                .andExpect(jsonPath("$.list[1].errors[*].field", hasItem("altGrp")));
    }

    @Test
    void 숫자셀_순번_수량_소수_1e_7_은_SCALE_큰수는_INT_DIGITS() throws Exception {
        long job = jobId(upload(fill(List.of(new Object[] {"FG1", 10, "SA1", 0.1},
                new Object[] {"FG1", 20.0, "SA2", 2.5},
                new Object[] {"FG1", 30, "RM1", 1e-7},
                new Object[] {"FG1", 40, "RM1", 1e15},
                new Object[] {"FG1", 50.5, "RM1", 1},
                new Object[] {"FG1", -10, "RM1", 1})))
                .andExpect(jsonPath("$.data.newCount").value(2))
                .andExpect(jsonPath("$.data.errorCount").value(4)));
        rows(job).andExpect(jsonPath("$.list[2].errors[0].code").value("SCALE"))
                .andExpect(jsonPath("$.list[3].errors[0].code").value("INT_DIGITS"))
                .andExpect(jsonPath("$.list[4].errors[0].field").value("lineNo"))
                .andExpect(jsonPath("$.list[5].errors[0].field").value("lineNo"));
        long ok = jobId(upload(fill(List.of(new Object[] {"FG1", 10, "SA1", 0.1},
                new Object[] {"FG1", 20.0, "SA2", 2.5}))));
        execute(ok).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select qty from mst_bom_line where line_no = 10", BigDecimal.class))
                .isEqualByComparingTo("0.1");                    // 0.1000000000000000055… 가 아니라 0.1
        assertThat(accounts.query(comp, "select qty from mst_bom_line where line_no = 20", BigDecimal.class))
                .isEqualByComparingTo("2.5");
    }

    @Test
    void 품목_올리기로_BOM에_쓰인_품목의_기본단위를_바꾸면_IN_USE() throws Exception {
        accounts.exec(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                select ?, item_id, 'A0', (now() at time zone 'Asia/Seoul')::date, 't', 't' from mst_item where item_cd = 'FG1'""", comp);
        byte[] tpl = mvc.perform(post(BASE + "/templates/MST_ITEM/file").with(csrf()).with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        byte[] file;
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(tpl))) {
            Sheet s = wb.getSheetAt(0);
            String[][] rows = {{"FG1", "FG1 품목", null, "RM", "BUY", "SET"},     // BOM 모품목 — 단위 변경 거부
                {"SA2", "SA2 품목", null, "RM", "BUY", "SET"}};                    // 안 쓰임 — 허용
            for (int i = 0; i < rows.length; i++) {
                Row r = s.createRow(i + 1);
                for (int c = 0; c < rows[i].length; c++) {
                    if (rows[i][c] != null) {
                        r.createCell(c).setCellValue(rows[i][c]);
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            file = out.toByteArray();
        }
        MvcResult m = mvc.perform(multipart(BASE + "/templates/MST_ITEM/jobs").param("mode", "UPSERT")
                .file(new MockMultipartFile("file", "item.xlsx", XLSX, file)).with(csrf()).with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.errorCount").value(1))
                .andExpect(jsonPath("$.data.changeCount").value(1)).andReturn();
        long job = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.jobId")).longValue();
        rows(job).andExpect(jsonPath("$.list[0].errors[0].field").value("unitCd"))
                .andExpect(jsonPath("$.list[0].errors[0].code").value("IN_USE"));
    }

    @Test
    void 다른_회사_품목코드는_모품목_자품목_모두_NOT_FOUND() throws Exception {
        String other = accounts.newComp("BJ");
        accounts.exec(other, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn,
                                      created_by, updated_by)
                values (?, 'XFG', 'B사 품목', 'RM', 'BUY', 'EA', 'N', 't', 't')""", other);
        long job = jobId(upload(fill(List.of(new Object[] {"XFG", "10", "SA1", "1"},
                new Object[] {"FG1", "10", "XFG", "1"})))
                .andExpect(jsonPath("$.data.errorCount").value(2)));
        rows(job).andExpect(jsonPath("$.list[0].errors[0].code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.list[0].errors[0].field").value("parentCd"))
                .andExpect(jsonPath("$.list[1].errors[0].code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.list[1].errors[0].field").value("itemCd"));
    }
}
