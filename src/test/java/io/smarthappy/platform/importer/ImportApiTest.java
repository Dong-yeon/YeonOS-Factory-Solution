package io.smarthappy.platform.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 엑셀 올리기 (P1 2단계, docs/17 3b): 템플릿 받기 → 채워 올리기 → 미리보기(본 테이블 쓰기 0) → 실행 → 대사.
 * 이상 사용: 다른·옛 템플릿, 필수 열 없음, 회사 코드 열, 파일 내 중복, 연타 실행, 검증 뒤 변경, A사로 B사 작업, 권한.
 */
@AutoConfigureMockMvc
class ImportApiTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String BASE = "/api/platform/import";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    /** 품목 템플릿 열 순서: 품목코드, 품목명, 규격, 유형, 조달, 단위, LOT, 순질량, HS, 비고, 사용 */
    private static final int COLS = 13;   // 품목 템플릿 열 수(표준단가 docs/29_P3 29장 → 끝 열 ERP 품번 docs/17 9장, 버전 2)

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    PolicyService policies;

    String comp;
    long userId;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("IM");
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-item", "SEARCH", "SAVE", "DELETE", "EXCEL");
        accounts.grant(comp, userId, "MASTER", "mst-cust", "SEARCH", "SAVE", "DELETE", "EXCEL");
        admin = accounts.as(comp, userId, "admin1");
    }

    // ---------- 도우미 ----------

    private byte[] template(String code, RequestPostProcessor who) throws Exception {
        return mvc.perform(post(BASE + "/templates/" + code + "/file").with(csrf()).with(who))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    /** 템플릿을 받아 첫 시트에 행을 채운다. 행 값이 Number 면 숫자 셀 */
    private byte[] fill(String code, List<Object[]> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(template(code, admin)))) {
            Sheet s = wb.getSheetAt(0);
            CellStyle oneDecimal = wb.createCellStyle();
            oneDecimal.setDataFormat(wb.createDataFormat().getFormat("0.0"));   // 서식은 소수 1자리 — 값은 그대로여야
            int r = 1;
            for (Object[] values : rows) {
                Row row = s.createRow(r++);
                for (int c = 0; c < values.length; c++) {
                    if (values[c] instanceof Number n) {
                        Cell cell = row.createCell(c);
                        cell.setCellValue(n.doubleValue());
                        cell.setCellStyle(oneDecimal);
                    } else if (values[c] != null) {
                        row.createCell(c).setCellValue(String.valueOf(values[c]));
                    }
                }
            }
            return bytes(wb);
        }
    }

    private static byte[] bytes(XSSFWorkbook wb) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }

    private static Object[] item(String cd, String nm, String type, String proc, String unit, Object mass) {
        Object[] r = new Object[COLS];
        r[0] = cd;
        r[1] = nm;
        r[3] = type;
        r[4] = proc;
        r[5] = unit;
        r[7] = mass;
        return r;
    }

    private ResultActions upload(String code, String mode, byte[] file, String name, RequestPostProcessor who)
            throws Exception {
        var req = multipart(BASE + "/templates/" + code + "/jobs").file(new MockMultipartFile("file", name, XLSX, file));
        if (mode != null) {
            req.param("mode", mode);
        }
        return mvc.perform(req.with(csrf()).with(who));
    }

    private long jobId(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.jobId")).longValue();
    }

    private int rowVer(long jobId) {
        return accounts.query(comp, "select row_ver from sys_import_job where job_id = ?", Integer.class, jobId);
    }

    private ResultActions execute(long jobId, int rowVer, boolean skip, RequestPostProcessor who) throws Exception {
        return mvc.perform(post(BASE + "/jobs/" + jobId + "/execute").with(csrf()).with(who)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rowVer\":" + rowVer + ",\"skipErrors\":" + skip + "}"));
    }

    private int items() {
        return accounts.query(comp, "select count(*) from mst_item", Integer.class);
    }

    // ---------- ERP 주인 필드 (P1 7a, docs/22) ----------

    @Test
    void 정책_PULL_이면_올리기도_새_품목과_ERP주인칸_변경은_행오류_MES칸만_바뀐다() throws Exception {
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG1', '제품1', 'FG', 'MAKE', 'EA', 't', 't'), (?, 'FG2', '제품2', 'FG', 'MAKE', 'EA', 't', 't')""",
                comp, comp);
        accounts.exec(comp, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by)
                values (?, 'ERP_ITEM_SYNC_MODE', 'PULL', 't', 't')""", comp);
        Object[] mesOnly = item("FG2", "제품2", "FG", "MAKE", "EA", 1.5);       // 순질량만 바꿈(MES 칸)
        long job = jobId(upload("MST_ITEM", "UPSERT", fill("MST_ITEM", List.of(
                item("NEW1", "새 품목", "FG", "MAKE", "EA", null),
                item("FG1", "이름 바꿈", "FG", "MAKE", "KG", null),
                mesOnly)), "pull.xlsx", admin)
                .andExpect(jsonPath("$.data.errorCount").value(2))
                .andExpect(jsonPath("$.data.changeCount").value(1)));
        mvc.perform(get(BASE + "/jobs/" + job + "/rows").param("status", "ERROR").with(admin))
                .andExpect(jsonPath("$.list[*].errors[*].code").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("ERP_OWNED"))));
        execute(job, rowVer(job), true, admin).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select item_nm || '/' || net_mass_kg from mst_item where item_cd = 'FG2'", String.class))
                .isEqualTo("제품2/1.500000");
        assertThat(accounts.query(comp, "select item_nm from mst_item where item_cd = 'FG1'", String.class)).isEqualTo("제품1");
        assertThat(items()).isEqualTo(2);
    }

    // ---------- 정상 ----------

    @Test
    void 템플릿을_받아_채워_올리면_미리보기에서는_본_테이블에_쓰지_않고_실행하면_대사까지() throws Exception {
        byte[] file = fill("MST_ITEM", List.of(
                item("84910-2s000", "프런트 사이드 멤버", "FG", "MAKE", "EA", 4.85),
                item("RM-SPFC590", "냉연강판", "원자재", "구매", "kg", null),   // 코드 대신 이름
                item("NUT-M8", "용접 너트", "sub", "buy", "개", "0.006")));
        int outboxBefore = accounts.query(comp, "select count(*) from sys_outbox", Integer.class);
        long job = jobId(upload("MST_ITEM", null, file, "items.xlsx", admin)
                .andExpect(jsonPath("$.data.status").value("VALIDATED"))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.newCount").value(3))
                .andExpect(jsonPath("$.data.errorCount").value(0))
                .andExpect(jsonPath("$.data.mode").value("INSERT_ONLY")));
        // 미리보기 단계: 본 테이블·아웃박스 0, 원본은 첨부로 보관
        assertThat(items()).isZero();
        assertThat(accounts.query(comp, "select count(*) from sys_outbox", Integer.class)).isEqualTo(outboxBefore);
        assertThat(accounts.query(comp, """
                select count(*) from sys_file f join sys_import_job j on j.file_id = f.file_id where j.job_id = ?""",
                Integer.class, job)).isEqualTo(1);
        mvc.perform(get(BASE + "/jobs/" + job + "/rows").with(admin))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.list[0].rowNo").value(2))           // 엑셀 행 번호(1행은 헤더)
                .andExpect(jsonPath("$.list[0].key").value("84910-2S000"))
                .andExpect(jsonPath("$.list[1].raw.itemType").value("원자재"));

        execute(job, rowVer(job), false, admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.result.inserted").value(3))
                .andExpect(jsonPath("$.data.result.before").value(0))
                .andExpect(jsonPath("$.data.result.after").value(3))
                .andExpect(jsonPath("$.data.result.matched").value(true));
        assertThat(accounts.query(comp, "select item_type || '/' || proc_type || '/' || unit_cd from mst_item where item_cd = 'RM-SPFC590'",
                String.class)).isEqualTo("RM/BUY/KG");
        // 숫자 셀 서식(0.0)과 상관없이 저장된 값 그대로
        assertThat(accounts.query(comp, "select net_mass_kg from mst_item where item_cd = '84910-2S000'", BigDecimal.class))
                .isEqualByComparingTo("4.85");
        // 반영은 감사된다(행위자 = 올린 사용자)
        assertThat(accounts.query(comp, """
                select count(*) from sys_audit_log where table_nm = 'mst_item' and action = 'INSERT'""", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void 새것_변경_모드는_변경_같음을_나누고_파일에_없는_열은_그대로_둔다() throws Exception {
        long first = jobId(upload("MST_ITEM", null, fill("MST_ITEM", List.of(
                item("A1", "가", "FG", "MAKE", "EA", "1.5"), item("A2", "나", "FG", "MAKE", "EA", "2.5"))), "a.xlsx", admin));
        execute(first, rowVer(first), false, admin).andExpect(status().isOk());

        // 품목코드·명·유형·조달·단위 열만 있는 파일 — 순질량 열이 없다
        byte[] partial;
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(template("MST_ITEM", admin)))) {
            Sheet s = wb.getSheetAt(0);
            for (int c = COLS - 1; c >= 5 + 1; c--) {
                s.getRow(0).removeCell(s.getRow(0).getCell(c));
            }
            s.getRow(0).removeCell(s.getRow(0).getCell(2));
            String[][] rows = {{"A1", "가(변경)", null, "FG", "MAKE", "EA"}, {"A2", "나", null, "FG", "MAKE", "EA"},
                {"A3", "다", null, "FG", "MAKE", "EA"}};
            for (int i = 0; i < rows.length; i++) {
                Row r = s.createRow(i + 1);
                for (int c = 0; c < rows[i].length; c++) {
                    if (rows[i][c] != null) {
                        r.createCell(c).setCellValue(rows[i][c]);
                    }
                }
            }
            partial = bytes(wb);
        }
        // 새 것만 모드: 이미 있는 코드는 오류
        upload("MST_ITEM", "INSERT_ONLY", partial, "p.xlsx", admin).andExpect(jsonPath("$.data.errorCount").value(2))
                .andExpect(jsonPath("$.data.newCount").value(1));
        long job = jobId(upload("MST_ITEM", "UPSERT", partial, "p.xlsx", admin)
                .andExpect(jsonPath("$.data.changeCount").value(1))
                .andExpect(jsonPath("$.data.sameCount").value(1))
                .andExpect(jsonPath("$.data.newCount").value(1))
                .andExpect(jsonPath("$.data.columns.length()").value(5)));
        execute(job, rowVer(job), false, admin).andExpect(jsonPath("$.data.result.updated").value(1))
                .andExpect(jsonPath("$.data.result.inserted").value(1));
        assertThat(accounts.query(comp, "select item_nm || ':' || net_mass_kg from mst_item where item_cd = 'A1'", String.class))
                .isEqualTo("가(변경):1.500000");   // 순질량 열이 없었으므로 그대로
        assertThat(accounts.query(comp, "select row_ver from mst_item where item_cd = 'A2'", Integer.class)).isZero();
    }

    @Test
    void 오류_행은_이유와_함께_보이고_제외를_고르지_않으면_실행하지_않는다() throws Exception {
        byte[] file = fill("MST_ITEM", List.of(
                item("OK-1", "정상", "FG", "MAKE", "EA", null),
                item("dup", "중복1", "FG", "MAKE", "EA", null),
                item("DUP", "중복2", "FG", "MAKE", "EA", null),
                item("BAD UNIT", "형식", "FG", "MAKE", "PCS", "abc"),
                item("NEG", "음수", "XX", "MAKE", "EA", "-1")));
        long job = jobId(upload("MST_ITEM", null, file, "e.xlsx", admin)
                .andExpect(jsonPath("$.data.newCount").value(1))
                .andExpect(jsonPath("$.data.errorCount").value(4)));
        mvc.perform(get(BASE + "/jobs/" + job + "/rows").param("status", "ERROR").with(admin))
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.list[0].errors[0].code").value("FILE_DUPLICATE"))
                .andExpect(jsonPath("$.list[2].errors[*].field").value(hasItem("unitCd")))
                .andExpect(jsonPath("$.list[2].errors[*].title").value(hasItem("단위")))
                .andExpect(jsonPath("$.list[3].errors[*].code").value(hasItem("POSITIVE")));
        execute(job, rowVer(job), false, admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_HAS_ERRORS"));
        assertThat(items()).isZero();
        // 오류 엑셀: 행 번호 + 올린 글자 + 오류 내용
        MvcResult r = mvc.perform(post(BASE + "/jobs/" + job + "/errors/export").with(csrf()).with(admin))
                .andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()))) {
            Sheet s = wb.getSheetAt(0);
            assertThat(s.getLastRowNum()).isEqualTo(4);
            assertThat(s.getRow(3).getCell(0).getNumericCellValue()).isEqualTo(5);
            assertThat(s.getRow(3).getCell(1).getStringCellValue()).isEqualTo("BAD UNIT");
            assertThat(s.getRow(3).getCell(COLS + 1).getStringCellValue()).contains("단위");
        }
        execute(job, rowVer(job), true, admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.inserted").value(1))
                .andExpect(jsonPath("$.data.result.errorsExcluded").value(4))
                .andExpect(jsonPath("$.data.result.matched").value(true));
        assertThat(items()).isEqualTo(1);
    }

    @Test
    void 거래처도_같은_엔진으로_검증식과_구분_이름() throws Exception {
        Object[][] rows = {{"C-1", "한빛정밀", "매출처", "124-81-00998", null, null, null, null, null, "사용"},
            {"C-2", "잘못된번호", "SALES", "1234567890", null, null, null, null, null, null},
            {"C-3", "구분오류", "VIP", null, null, null, "not-mail", null, null, "미사용"}};
        long job = jobId(upload("MST_CUST", null, fill("MST_CUST", List.of(rows)), "c.xlsx", admin)
                .andExpect(jsonPath("$.data.newCount").value(1))
                .andExpect(jsonPath("$.data.errorCount").value(2)));
        execute(job, rowVer(job), true, admin).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select cust_type || '/' || biz_no || '/' || use_yn from mst_cust", String.class))
                .isEqualTo("SALES/1248100998/Y");
    }

    // ---------- 이상 사용: 파일 ----------

    @Test
    void 다른_템플릿_옛_버전_메타_없음_필수_열_없음_빈_파일은_거부() throws Exception {
        // 거래처 템플릿을 품목에
        upload("MST_ITEM", null, fill("MST_CUST", List.<Object[]>of(new Object[] {"C1", "x", "SALES"})), "x.xlsx", admin)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IMPORT_TEMPLATE"));
        // 옛 버전
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(fill("MST_ITEM",
                List.<Object[]>of(item("V1", "x", "FG", "MAKE", "EA", null)))))) {
            wb.getSheet("_meta").getRow(1).getCell(1).setCellValue("0");
            upload("MST_ITEM", null, bytes(wb), "old.xlsx", admin).andExpect(jsonPath("$.code").value("IMPORT_TEMPLATE"));
        }
        // 직접 만든 엑셀(메타 없음)
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Row h = wb.createSheet("data").createRow(0);
            String[] heads = {"품목코드", "품목명", "품목 유형", "조달", "단위"};
            for (int i = 0; i < heads.length; i++) {
                h.createCell(i).setCellValue(heads[i]);
            }
            upload("MST_ITEM", null, bytes(wb), "mine.xlsx", admin).andExpect(jsonPath("$.code").value("IMPORT_TEMPLATE"));
        }
        // 필수 열(단위) 지움
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(fill("MST_ITEM",
                List.<Object[]>of(item("H1", "x", "FG", "MAKE", "EA", null)))))) {
            wb.getSheetAt(0).getRow(0).getCell(5).setCellValue("");
            upload("MST_ITEM", null, bytes(wb), "h.xlsx", admin).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("IMPORT_HEADER"))
                    .andExpect(jsonPath("$.message").value(containsString("단위")));
        }
        upload("MST_ITEM", null, fill("MST_ITEM", List.of()), "empty.xlsx", admin)
                .andExpect(jsonPath("$.code").value("IMPORT_EMPTY"));
        // 확장자만 xlsx 인 다른 내용 / xlsx 아님
        upload("MST_ITEM", null, "a,b,c\n".getBytes(), "fake.xlsx", admin).andExpect(status().isBadRequest());
        upload("MST_ITEM", null, "a,b,c\n".getBytes(), "items.csv", admin).andExpect(jsonPath("$.code").value("IMPORT_FILE"));
        assertThat(accounts.query(comp, "select count(*) from sys_import_job", Integer.class)).isZero();
    }

    @Test
    void 회사코드_열은_무시하고_로그인_회사로만_들어간다() throws Exception {
        String other = accounts.newComp("IM");
        byte[] file;
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(fill("MST_ITEM",
                List.<Object[]>of(item("CC1", "회사열", "FG", "MAKE", "EA", null)))))) {
            Sheet s = wb.getSheetAt(0);
            s.getRow(0).createCell(COLS).setCellValue("comp_cd");
            s.getRow(1).createCell(COLS).setCellValue(other);
            file = bytes(wb);
        }
        long job = jobId(upload("MST_ITEM", null, file, "c.xlsx", admin)
                .andExpect(jsonPath("$.data.ignoredColumns[0]").value("comp_cd")));
        execute(job, rowVer(job), false, admin).andExpect(status().isOk());
        assertThat(items()).isEqualTo(1);
        assertThat(accounts.query(other, "select count(*) from mst_item", Integer.class)).isZero();
    }

    @Test
    void 행_수_상한을_넘으면_거부() throws Exception {
        runner.run(comp, () -> policies.set(Policies.IMPORT_MAX_ROWS, null, "100", "t"));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            rows.add(item("L" + i, "많음", "FG", "MAKE", "EA", null));
        }
        upload("MST_ITEM", null, fill("MST_ITEM", rows), "many.xlsx", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_TOO_MANY"));
        upload("MST_ITEM", null, fill("MST_ITEM", rows.subList(0, 100)), "ok.xlsx", admin).andExpect(status().isOk());
    }

    // ---------- 이상 사용: 실행 ----------

    @Test
    void 실행을_동시에_두_번_눌러도_한_번만() throws Exception {
        long job = jobId(upload("MST_ITEM", null, fill("MST_ITEM", List.<Object[]>of(
                item("T1", "연타", "FG", "MAKE", "EA", null))), "t.xlsx", admin));
        int ver = rowVer(job);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> call = () -> execute(job, ver, false, admin).andReturn().getResponse().getStatus();
            Future<Integer> a = pool.submit(call);
            Future<Integer> b = pool.submit(call);
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdown();
        }
        assertThat(items()).isEqualTo(1);
        execute(job, ver + 1, false, admin).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IMPORT_STATE"));
    }

    @Test
    void 검증_뒤_누가_고치면_전부_취소하고_다시_올리라고() throws Exception {
        long first = jobId(upload("MST_ITEM", null, fill("MST_ITEM", List.<Object[]>of(
                item("S1", "원래", "FG", "MAKE", "EA", null))), "a.xlsx", admin));
        execute(first, rowVer(first), false, admin).andExpect(status().isOk());
        long job = jobId(upload("MST_ITEM", "UPSERT", fill("MST_ITEM", List.of(
                item("S2", "새것", "FG", "MAKE", "EA", null), item("S1", "엑셀로 변경", "FG", "MAKE", "EA", null))),
                "b.xlsx", admin));
        // 그 사이 화면에서 S1 수정
        long id = accounts.query(comp, "select item_id from mst_item where item_cd = 'S1'", Long.class);
        mvc.perform(put("/api/master/items/" + id).with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemCd\":\"S1\",\"itemNm\":\"화면에서 변경\",\"itemType\":\"FG\",\"procType\":\"MAKE\","
                        + "\"unitCd\":\"EA\",\"rowVer\":0}")).andExpect(status().isOk());
        execute(job, rowVer(job), false, admin).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_STALE"));
        // 전부 아니면 전무: 새 행 S2 도 들어가지 않고, 작업은 그대로(취소 가능)
        assertThat(accounts.query(comp, "select count(*) from mst_item where item_cd = 'S2'", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select item_nm from mst_item where item_cd = 'S1'", String.class)).isEqualTo("화면에서 변경");
        assertThat(accounts.query(comp, "select status from sys_import_job where job_id = ?", String.class, job))
                .isEqualTo("VALIDATED");
        mvc.perform(post(BASE + "/jobs/" + job + "/cancel").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowVer\":" + rowVer(job) + "}")).andExpect(jsonPath("$.data.status").value("CANCELED"));
        execute(job, rowVer(job), false, admin).andExpect(jsonPath("$.code").value("IMPORT_STATE"));
    }

    // ---------- 이상 사용: 권한·회사 ----------

    @Test
    void B사_사용자와_같은_회사_다른_사람은_작업을_볼_수도_실행할_수도_없다() throws Exception {
        long job = jobId(upload("MST_ITEM", null, fill("MST_ITEM", List.<Object[]>of(
                item("P1", "비밀", "FG", "MAKE", "EA", null))), "p.xlsx", admin));
        String other = accounts.newComp("IM");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "MASTER", "mst-item", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        long colleague = accounts.newUser(comp, "admin2", PW);
        accounts.grant(comp, colleague, "MASTER2", "mst-item", "SEARCH", "SAVE");
        RequestPostProcessor c = accounts.as(comp, colleague, "admin2");
        for (RequestPostProcessor who : List.of(b, c)) {
            mvc.perform(get(BASE + "/jobs/" + job).with(who)).andExpect(status().isNotFound());
            mvc.perform(get(BASE + "/jobs/" + job + "/rows").with(who)).andExpect(status().isNotFound());
            mvc.perform(post(BASE + "/jobs/" + job + "/errors/export").with(csrf()).with(who)).andExpect(status().isNotFound());
            execute(job, rowVer(job), true, who).andExpect(status().isNotFound());
        }
        assertThat(items()).isZero();
        assertThat(accounts.query(other, "select count(*) from sys_import_row", Integer.class)).isZero();
    }

    @Test
    void 조회_권한만_있으면_템플릿은_받고_올리기는_403_권한_없으면_템플릿도_403() throws Exception {
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "mst-item", "SEARCH");
        RequestPostProcessor r = accounts.as(comp, reader, "reader1");
        template("MST_ITEM", r);
        upload("MST_ITEM", null, fill("MST_ITEM", List.<Object[]>of(item("R1", "x", "FG", "MAKE", "EA", null))),
                "r.xlsx", r).andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/templates/MST_CUST/file").with(csrf()).with(r)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/templates/NO_SUCH").with(r)).andExpect(status().isNotFound());
        upload("MST_ITEM", "DELETE_ALL", new byte[] {1}, "x.xlsx", admin).andExpect(status().isBadRequest());
    }

    // ---------- 실측 (수동: ./gradlew test --tests '*ImportApiTest*' -Dhb.perf=true) ----------

    @Test
    @EnabledIfSystemProperty(named = "hb.perf", matches = "true")
    void 오만_행_실측() throws Exception {
        int n = 50_000;
        List<Object[]> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            rows.add(item(String.format("PERF-%06d", i), "실측 품목 " + i, i % 2 == 0 ? "FG" : "RM", "MAKE",
                    i % 2 == 0 ? "EA" : "KG", i % 3 == 0 ? null : 1.25 + i % 7));
        }
        long t0 = System.nanoTime();
        byte[] file = fill("MST_ITEM", rows);
        long t1 = System.nanoTime();
        long job = jobId(upload("MST_ITEM", null, file, "perf.xlsx", admin).andExpect(jsonPath("$.data.newCount").value(n)));
        long t2 = System.nanoTime();
        execute(job, rowVer(job), false, admin).andExpect(jsonPath("$.data.result.matched").value(true));
        long t3 = System.nanoTime();
        assertThat(items()).isEqualTo(n);
        System.out.printf("[PERF] 50000 rows file=%dKB make=%dms upload+validate=%dms execute=%dms%n",
                file.length / 1024, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000);
    }
}
