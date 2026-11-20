package io.smarthappy.module.master.bom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
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

/** BOM 엑셀 올리기 (P1 3a, docs/18 §10.1) — 작성 중 BOM 만 대상, 확정본 불변, 파일 안·DB 구조 순환, 새것+변경, 검증 뒤 확정되면 전체 취소 */
@AutoConfigureMockMvc
class BomImportTest extends PostgresTestBase {

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
        comp = accounts.newComp("BI");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-bom", "SEARCH", "SAVE", "APPROVE", "DELETE", "EXCEL");
        admin = accounts.as(comp, userId, "admin1");
        for (String[] it : new String[][] {{"FG1", "EA", "N"}, {"SA1", "EA", "N"}, {"SA2", "EA", "N"}, {"RM1", "KG", "Y"}}) {
            accounts.exec(comp, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn,
                                          created_by, updated_by)
                    values (?, ?, ?, 'RM', 'BUY', ?, ?, 't', 't')""", comp, it[0], it[0] + " 품목", it[1], it[2]);
        }
    }

    // ---------- 도우미 ----------

    /** 템플릿을 받아 행을 채운다: {모품목, 순번, 자품목, 소요량, 단위} */
    private byte[] fill(String[]... rows) throws Exception {
        byte[] tpl = mvc.perform(post(BASE + "/templates/MST_BOM/file").with(csrf()).with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(tpl))) {
            Sheet s = wb.getSheetAt(0);
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
            return out.toByteArray();
        }
    }

    private ResultActions upload(String mode, byte[] file) throws Exception {
        var req = multipart(BASE + "/templates/MST_BOM/jobs").file(new MockMultipartFile("file", "bom.xlsx", XLSX, file));
        if (mode != null) {
            req.param("mode", mode);
        }
        return mvc.perform(req.with(csrf()).with(admin));
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

    private long draftWith(String parent, String child) {
        long bom = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                select ?, item_id, 'A0', (now() at time zone 'Asia/Seoul')::date, 't', 't' from mst_item where item_cd = ? returning bom_id""",
                Long.class, comp, parent);
        accounts.exec(comp, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, issue_method, created_by, updated_by)
                select ?, ?, 10, item_id, 1, unit_cd, 'BACKFLUSH', 't', 't' from mst_item where item_cd = ?""",
                comp, bom, child);
        return bom;
    }

    private void releaseBySql(long bom) {
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", bom);
    }

    // ---------- 테스트 ----------

    @Test
    void 여러_모품목을_한_파일로_올리면_작성중_BOM이_만들어지고_대사가_맞다() throws Exception {
        long job = jobId(upload(null, fill(new String[] {"fg1", "10", "SA1", "2", null},
                new String[] {"FG1", "20", "RM1", "1.5", "kg"},
                new String[] {"SA1", "10.0", "RM1", "0.25", null}))
                .andExpect(jsonPath("$.data.newCount").value(3))
                .andExpect(jsonPath("$.data.errorCount").value(0)));
        assertThat(accounts.query(comp, "select count(*) from mst_bom", Integer.class)).isZero();   // 미리보기는 쓰지 않는다
        execute(job).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.inserted").value(3))
                .andExpect(jsonPath("$.data.result.matched").value(true));
        assertThat(accounts.query(comp, "select string_agg(rev || status, ',') from mst_bom", String.class))
                .isEqualTo("A0DRAFT,A0DRAFT");
        assertThat(accounts.query(comp, """
                select l.issue_method from mst_bom_line l join mst_item c on c.item_id = l.item_id
                 join mst_bom b on b.bom_id = l.bom_id join mst_item p on p.item_id = b.item_id
                 where p.item_cd = 'FG1' and c.item_cd = 'RM1'""", String.class)).isEqualTo("MANUAL");   // LOT 관리 기본
        assertThat(accounts.query(comp, "select qty from mst_bom_line where qty < 1", BigDecimal.class))
                .isEqualByComparingTo("0.25");
    }

    @Test
    void 파일_안_순환과_DB_구조_순환과_자기자신을_행_오류로() throws Exception {
        releaseBySql(draftWith("SA2", "RM1"));                  // DB: SA2 ← RM1
        upload(null, fill(new String[] {"FG1", "10", "SA1", "1", null},
                new String[] {"SA1", "10", "FG1", "1", null},     // 파일 1행과 순환
                new String[] {"FG1", "20", "FG1", "1", null},     // 자기 자신
                new String[] {"RM1", "10", "SA2", "1", null},     // DB 구조와 순환
                new String[] {"NOPE", "10", "SA1", "1", null}))
                .andExpect(jsonPath("$.data.newCount").value(1))
                .andExpect(jsonPath("$.data.errorCount").value(4));
        long job = jobId(upload(null, fill(new String[] {"FG1", "10", "SA1", "1", null},
                new String[] {"SA1", "10", "FG1", "1", null})));
        mvc.perform(get(BASE + "/jobs/" + job + "/rows").with(admin))
                .andExpect(jsonPath("$.list[1].status").value("ERROR"))
                .andExpect(jsonPath("$.list[1].errors[0].code").value("CYCLE"));
    }

    @Test
    void 확정본은_건드리지_않고_새_작성중_리비전에_들어간다() throws Exception {
        long a0 = draftWith("FG1", "SA1");
        releaseBySql(a0);
        long job = jobId(upload("UPSERT", fill(new String[] {"FG1", "10", "SA2", "3", null}))
                .andExpect(jsonPath("$.data.newCount").value(1)));
        execute(job).andExpect(status().isOk());
        assertThat(accounts.query(comp, """
                select c.item_cd from mst_bom_line l join mst_item c on c.item_id = l.item_id where l.bom_id = ?""",
                String.class, a0)).isEqualTo("SA1");
        assertThat(accounts.query(comp, "select rev || ':' || status from mst_bom where bom_id <> ?", String.class, a0))
                .isEqualTo("A1:DRAFT");
    }

    @Test
    void 새것_변경_모드는_작성중_행을_고치고_같은_행은_그대로() throws Exception {
        long bom = draftWith("FG1", "SA1");
        int verBefore = accounts.query(comp, "select row_ver from mst_bom where bom_id = ?", Integer.class, bom);
        upload("INSERT_ONLY", fill(new String[] {"FG1", "10", "SA1", "3", null}))
                .andExpect(jsonPath("$.data.errorCount").value(1));
        upload("UPSERT", fill(new String[] {"FG1", "10", "SA1", "1", null}))
                .andExpect(jsonPath("$.data.sameCount").value(1));
        long job = jobId(upload("UPSERT", fill(new String[] {"FG1", "10", "SA1", "3", null},
                new String[] {"FG1", "20", "SA2", "1", null}))
                .andExpect(jsonPath("$.data.changeCount").value(1))
                .andExpect(jsonPath("$.data.newCount").value(1)));
        execute(job).andExpect(status().isOk()).andExpect(jsonPath("$.data.result.updated").value(1));
        assertThat(accounts.query(comp, "select qty from mst_bom_line where bom_id = ? and line_no = 10", BigDecimal.class, bom))
                .isEqualByComparingTo("3");
        // 머리 버전이 올라 화면에서 동시에 고치던 사람은 409
        assertThat(accounts.query(comp, "select row_ver from mst_bom where bom_id = ?", Integer.class, bom))
                .isGreaterThan(verBefore);
    }

    @Test
    void 검증_뒤_그_작성중_BOM이_확정되면_전부_취소() throws Exception {
        long bom = draftWith("FG1", "SA1");
        long job = jobId(upload(null, fill(new String[] {"FG1", "20", "SA2", "1", null})));
        releaseBySql(bom);
        execute(job).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOM_NOT_DRAFT"));
        assertThat(accounts.query(comp, "select count(*) from mst_bom_line", Integer.class)).isEqualTo(1);
        assertThat(accounts.query(comp, "select count(*) from mst_bom", Integer.class)).isEqualTo(1);
        assertThat(accounts.query(comp, "select status from sys_import_job where job_id = ?", String.class, job))
                .isEqualTo("VALIDATED");
    }

    @Test
    void 템플릿_열은_모품목_순번_자품목_소요량이_필수() throws Exception {
        assertThat(new BomImportTemplate(null, null, null, null, null).columns().stream()
                .filter(c -> c.required()).map(c -> c.key()).toList())
                .isEqualTo(List.of("parentCd", "lineNo", "itemCd", "qty"));
    }
}
