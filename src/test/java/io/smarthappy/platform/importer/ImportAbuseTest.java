package io.smarthappy.platform.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
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

import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA P1 2단계 — 엑셀 올리기 이상 사용 추가 검증 (docs/qa/item-import-1-2.md).
 * ImportApiTest 에 없는 것: 파일 구조 이상(넓은 열·셀 순서·NUL·수식·병합·숨김·시트 순서·서식), 크기 증폭,
 * 흐름(작업 두 개 같은 코드·검증 뒤 단위 중지·권한 회수·취소), 변경 모드 빈 칸, 오류 엑셀 수식 주입, 감사 행위자.
 * 결함 재현 테스트 `결함n_…` — 개발 세션이 고친 뒤 @Disabled 를 풀고 옛 동작을 고정하던 `관찰_결함n_…` 을 지웠다(2026-10-20).
 */
@AutoConfigureMockMvc
class ImportAbuseTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String BASE = "/api/platform/import";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    /** 품목 템플릿 열 순서: 품목코드, 품목명, 규격, 유형, 조달, 단위, LOT, 순질량, HS, 비고, 사용 */
    private static final int COLS = 11;
    private static final String[] KEYS = {"itemCd", "itemNm", "spec", "itemType", "procType", "unitCd", "lotCtrlYn",
        "netMassKg", "hsCd", "remark", "useYn"};

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
        comp = accounts.newComp("IQ");
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-item", "SEARCH", "SAVE", "DELETE", "EXCEL");
        admin = accounts.as(comp, userId, "admin1");
    }

    // ---------- 도우미 ----------

    private byte[] template() throws Exception {
        return mvc.perform(post(BASE + "/templates/MST_ITEM/file").with(csrf()).with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    /** 템플릿 첫 시트에 행을 채운다. Number 는 숫자 셀(서식 없음) */
    private byte[] fill(List<Object[]> rows) throws Exception {
        return edit(wb -> {
            Sheet s = wb.getSheetAt(0);
            int r = 1;
            for (Object[] values : rows) {
                Row row = s.createRow(r++);
                for (int c = 0; c < values.length; c++) {
                    if (values[c] instanceof Number n) {
                        row.createCell(c).setCellValue(n.doubleValue());
                    } else if (values[c] != null) {
                        row.createCell(c).setCellValue(String.valueOf(values[c]));
                    }
                }
            }
        });
    }

    /** 템플릿을 열어 고친 뒤 바이트로 */
    private byte[] edit(Consumer<XSSFWorkbook> change) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(template()))) {
            change.accept(wb);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static Object[] item(String cd, String nm, Object mass) {
        Object[] r = new Object[COLS];
        r[0] = cd;
        r[1] = nm;
        r[3] = "FG";
        r[4] = "MAKE";
        r[5] = "EA";
        r[7] = mass;
        return r;
    }

    /** 템플릿의 첫 시트(xl/worksheets/sheet1.xml)를 직접 쓴 XML 로 바꾼다 — _meta·스타일은 템플릿 그대로 */
    private byte[] withSheetXml(String sheetXml) throws Exception {
        return replaceEntry(template(), "xl/worksheets/sheet1.xml", sheetXml.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] replaceEntry(byte[] zip, String name, byte[] content) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip));
                ZipOutputStream z = new ZipOutputStream(out)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                z.putNextEntry(new ZipEntry(e.getName()));
                z.write(e.getName().equals(name) ? content : in.readAllBytes());
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** 헤더(열 키 — 엔진은 키 이름도 받는다) + 데이터 행 XML. 행은 이미 만든 &lt;row&gt; 조각 */
    private static String sheet(String... rows) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"")
                .append(NS).append("\"><sheetData><row r=\"1\">");
        for (int i = 0; i < KEYS.length; i++) {
            b.append(c((char) ('A' + i) + "1", KEYS[i]));
        }
        b.append("</row>");
        for (String r : rows) {
            b.append(r);
        }
        return b.append("</sheetData></worksheet>").toString();
    }

    /** 인라인 문자열 셀 */
    private static String c(String ref, String text) {
        return "<c r=\"" + ref + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" + text.replace("&", "&amp;")
                .replace("<", "&lt;") + "</t></is></c>";
    }

    /** 정상 품목 한 행(n 행) — 코드 cd */
    private static String okRow(int n, String cd, String extraCells) {
        return "<row r=\"" + n + "\">" + c("A" + n, cd) + c("B" + n, "이름") + c("D" + n, "FG") + c("E" + n, "MAKE")
                + c("F" + n, "EA") + (extraCells == null ? "" : extraCells) + "</row>";
    }

    private ResultActions upload(String mode, byte[] file, String name, RequestPostProcessor who) throws Exception {
        var req = multipart(BASE + "/templates/MST_ITEM/jobs").file(new MockMultipartFile("file", name, XLSX, file));
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

    private ResultActions cancel(long jobId, int rowVer, RequestPostProcessor who) throws Exception {
        return mvc.perform(post(BASE + "/jobs/" + jobId + "/cancel").with(csrf()).with(who)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rowVer\":" + rowVer + "}"));
    }

    /** 작업의 n 번째(0부터) 행 */
    private String rowsJson(long jobId) throws Exception {
        return mvc.perform(get(BASE + "/jobs/" + jobId + "/rows").param("size", "500").with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static ExcelSheetReader.Sheet read(byte[] xlsx) throws Exception {
        Path p = Files.createTempFile("qa-import-", ".xlsx");
        try {
            Files.write(p, xlsx);
            return ExcelSheetReader.read(p, 50_000);
        } finally {
            Files.deleteIfExists(p);
        }
    }

    private int items() {
        return accounts.query(comp, "select count(*) from mst_item", Integer.class);
    }

    // ---------- 파일 구조 ----------


    @Test
    void 결함1_헤더_밖_열의_셀은_칸_목록을_늘리지_않는다() throws Exception {
        ExcelSheetReader.Sheet s = read(withSheetXml(sheet(okRow(2, "W1", c("XFD2", "x")))));
        assertThat(s.rows().get(0).cells().size()).isLessThanOrEqualTo(COLS);
    }


    @Test
    void 결함7_셀_순서와_관계없이_참조_위치의_열로_읽는다() throws Exception {
        String row = "<row r=\"2\">" + c("B2", "이름") + c("A2", "R1") + c("D2", "FG") + c("E2", "MAKE") + c("F2", "EA")
                + "</row>";
        ExcelSheetReader.Sheet s = read(withSheetXml(sheet(row)));
        assertThat(s.rows().get(0).cells().subList(0, 2)).containsExactly("R1", "이름");
    }


    @Test
    void 결함3_NUL_문자_셀은_행_오류_또는_제거() throws Exception {
        upload(null, withSheetXml(sheet("<row r=\"2\">" + c("A2", "N1") + c("B2", "이름_x0000_끝") + c("D2", "FG")
                + c("E2", "MAKE") + c("F2", "EA") + "</row>")), "nul.xlsx", admin)
                .andExpect(status().isOk());
    }

    @Test
    void 병합_셀은_왼쪽_위만_값이라_아래_행은_필수_오류() throws Exception {
        byte[] f = edit(wb -> {
            Sheet s = wb.getSheetAt(0);
            for (int r = 1; r <= 2; r++) {
                Row row = s.createRow(r);
                row.createCell(0).setCellValue("MG" + r);
                row.createCell(1).setCellValue("병합");
                row.createCell(4).setCellValue("MAKE");
                row.createCell(5).setCellValue("EA");
            }
            s.getRow(1).createCell(3).setCellValue("FG");
            s.addMergedRegion(new CellRangeAddress(1, 2, 3, 3));   // 유형 D2:D3 병합
        });
        String json = rowsJson(jobId(upload(null, f, "merge.xlsx", admin)
                .andExpect(jsonPath("$.data.newCount").value(1)).andExpect(jsonPath("$.data.errorCount").value(1))));
        assertThat((List<String>) JsonPath.read(json, "$.list[1].errors[*].code")).containsExactly("REQUIRED");
        assertThat((List<String>) JsonPath.read(json, "$.list[1].errors[*].field")).containsExactly("itemType");
    }

    @Test
    void 수식_셀은_저장된_계산값_계산값이_없으면_빈_칸() throws Exception {
        // 계산값 있음: =2+2.85 → 4.85
        byte[] f = edit(wb -> {
            Row row = wb.getSheetAt(0).createRow(1);
            Object[] v = item("F1", "수식", null);
            for (int i = 0; i < v.length; i++) {
                if (v[i] != null) {
                    row.createCell(i).setCellValue(String.valueOf(v[i]));
                }
            }
            row.createCell(7).setCellFormula("2+2.85");
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
        });
        String json = rowsJson(jobId(upload(null, f, "f.xlsx", admin).andExpect(jsonPath("$.data.newCount").value(1))));
        assertThat((String) JsonPath.read(json, "$.list[0].raw.netMassKg")).isEqualTo("4.85");
        // 계산값 없음(수식만 저장한 도구): 값 없음 → 순질량 비움으로 통과(수식 글자가 들어가지 않는다)
        byte[] g = edit(wb -> {
            Row row = wb.getSheetAt(0).createRow(1);
            Object[] v = item("F2", "수식", null);
            for (int i = 0; i < v.length; i++) {
                if (v[i] != null) {
                    row.createCell(i).setCellValue(String.valueOf(v[i]));
                }
            }
            row.createCell(7).setCellFormula("2+2.85");
        });
        String json2 = rowsJson(jobId(upload(null, g, "g.xlsx", admin)));
        assertThat(JsonPath.<Object>read(json2, "$.list[0].raw.netMassKg")).isIn("", null);
    }

    /** Excel 이 쓰는 모양 그대로: 수식 + 17자리 계산값(=0.1*3 → 0.30000000000000004, 화면 표시는 0.3) */
    private static String noiseRow() {
        return "<row r=\"2\">" + c("A2", "F3") + c("B2", "잡음") + c("D2", "FG") + c("E2", "MAKE") + c("F2", "EA")
                + "<c r=\"H2\"><f>0.1*3</f><v>0.30000000000000004</v></c></row>";
    }


    @Test
    void 결함8_숫자_셀은_유효숫자_15자리로_읽는다() throws Exception {
        upload(null, withSheetXml(sheet(noiseRow())), "noise.xlsx", admin).andExpect(jsonPath("$.data.newCount").value(1));
    }

    @Test
    void 날짜_서식_숫자와_텍스트로_저장된_숫자() throws Exception {
        byte[] f = edit(wb -> {
            Sheet s = wb.getSheetAt(0);
            CellStyle date = wb.createCellStyle();
            date.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));
            String[][] text = {{"D1", "4.85"}, {"D2", "\u00a04.85\u3000"}, {"D3", "1,000"}, {"D4", "4,85"}};
            int r = 1;
            for (String[] t : text) {
                Row row = s.createRow(r++);
                Object[] v = item(t[0], "텍스트 숫자", t[1]);
                for (int i = 0; i < v.length; i++) {
                    if (v[i] != null) {
                        row.createCell(i).setCellValue(String.valueOf(v[i]));
                    }
                }
            }
            // 날짜 서식 숫자: 이름 칸 → 날짜 글자, 순질량 칸 → 형식 오류
            Row row = s.createRow(r);
            Object[] v = item("D5", null, null);
            for (int i = 0; i < v.length; i++) {
                if (v[i] != null) {
                    row.createCell(i).setCellValue(String.valueOf(v[i]));
                }
            }
            Cell nm = row.createCell(1);
            nm.setCellValue(46027);   // 2026-01-05
            nm.setCellStyle(date);
            Cell mass = row.createCell(7);
            mass.setCellValue(46027);
            mass.setCellStyle(date);
        });
        String json = rowsJson(jobId(upload(null, f, "d.xlsx", admin)
                .andExpect(jsonPath("$.data.newCount").value(2)).andExpect(jsonPath("$.data.errorCount").value(3))));
        assertThat((List<String>) JsonPath.read(json, "$.list[2].errors[*].code")).containsExactly("FORMAT");   // 1,000
        assertThat((List<String>) JsonPath.read(json, "$.list[3].errors[*].code")).containsExactly("FORMAT");   // 4,85
        assertThat((String) JsonPath.read(json, "$.list[4].raw.itemNm")).isEqualTo("2026-01-05");
        assertThat((List<String>) JsonPath.read(json, "$.list[4].errors[*].field")).containsExactly("netMassKg");
    }


    @Test
    void 결함5_템플릿의_코드_열은_텍스트_서식() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(template()))) {
            Sheet s = wb.getSheetAt(0);
            for (int col : new int[] {0, 8}) {
                CellStyle st = s.getColumnStyle(col);
                assertThat(st == null ? null : st.getDataFormatString()).isEqualTo("@");
            }
        }
    }

    @Test
    void 숨긴_행과_숨긴_데이터_시트도_읽는다() throws Exception {
        byte[] f = edit(wb -> {
            Sheet s = wb.getSheetAt(0);
            for (int r = 1; r <= 2; r++) {
                Row row = s.createRow(r);
                Object[] v = item("HD" + r, "숨김", null);
                for (int i = 0; i < v.length; i++) {
                    if (v[i] != null) {
                        row.createCell(i).setCellValue(String.valueOf(v[i]));
                    }
                }
            }
            s.getRow(2).setZeroHeight(true);   // 필터·숨김 행
            wb.setSheetHidden(0, true);
            wb.setActiveSheet(1);
        });
        upload(null, f, "hidden.xlsx", admin).andExpect(jsonPath("$.data.newCount").value(2));
    }

    @Test
    void 안내_시트를_맨_앞으로_옮기면_필수_열_없음으로_거부() throws Exception {
        byte[] f = edit(wb -> wb.setSheetOrder(wb.getSheetName(1), 0));
        upload(null, f, "order.xlsx", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_HEADER"));
    }

    @Test
    void 열_순서를_바꾸고_영어_제목이나_키로_써도_열_이름으로_맞춘다_같은_열_두_번은_거부() throws Exception {
        String head = "<row r=\"1\">" + c("A1", "Unit") + c("B1", "procType") + c("C1", "품목 유형") + c("D1", "itemNm")
                + c("E1", "itemCd *") + "</row>";
        String data = "<row r=\"2\">" + c("A2", "kg") + c("B2", "BUY") + c("C2", "RM") + c("D2", "역순") + c("E2", "rev1")
                + "</row>";
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"" + NS + "\"><sheetData>" + head + data
                + "</sheetData></worksheet>";
        long job = jobId(upload(null, withSheetXml(xml), "rev.xlsx", admin).andExpect(jsonPath("$.data.newCount").value(1)));
        execute(job, rowVer(job), false, admin).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select item_type || '/' || proc_type || '/' || unit_cd from mst_item where item_cd = 'REV1'",
                String.class)).isEqualTo("RM/BUY/KG");
        String dup = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"" + NS + "\"><sheetData>"
                + head.replace("</row>", c("F1", "품목명") + "</row>") + data + "</sheetData></worksheet>";
        upload(null, withSheetXml(dup), "dup.xlsx", admin).andExpect(jsonPath("$.code").value("IMPORT_HEADER"));
    }

    @Test
    void 빈_행과_공백만_있는_행은_행_수에_세지_않는다() throws Exception {
        runner.run(comp, () -> policies.set(Policies.IMPORT_MAX_ROWS, null, "100", "t"));
        StringBuilder rows = new StringBuilder();
        int n = 2;
        for (int i = 0; i < 100; i++) {
            rows.append(okRow(n++, "B" + i, null));
            rows.append("<row r=\"").append(n).append("\">").append(c("B" + n, "   ")).append("</row>");
            n++;
        }
        upload(null, withSheetXml(sheet(rows.toString())), "blank.xlsx", admin)
                .andExpect(jsonPath("$.data.total").value(100));
    }

    @Test
    void 확장자만_바꾼_파일_손상된_xlsx_압축_폭탄은_400이고_작업은_없다() throws Exception {
        byte[] ok = fill(List.<Object[]>of(item("Z1", "정상", null)));
        // xls(OLE2) 시그니처
        byte[] ole = new byte[512];
        System.arraycopy(new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1},
                0, ole, 0, 8);
        upload(null, ole, "old.xlsx", admin).andExpect(status().isBadRequest());
        // PDF
        upload(null, "%PDF-1.4\n%\u00e2\u00e3\n".getBytes(StandardCharsets.ISO_8859_1), "doc.xlsx", admin)
                .andExpect(status().isBadRequest());
        // zip 이지만 xlsx 아님(docx 처럼 다른 OOXML·아무 zip)
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(zip)) {
            z.putNextEntry(new ZipEntry("word/document.xml"));
            z.write("<w/>".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        upload(null, zip.toByteArray(), "word.xlsx", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_FILE"));
        // 잘린 xlsx
        byte[] cut = java.util.Arrays.copyOf(ok, ok.length / 2);
        upload(null, cut, "cut.xlsx", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_FILE"));
        // 압축 폭탄 성격: 시트 XML 에 공백 20MB (압축 후 수십 KB, 비율 1% 미만)
        StringBuilder pad = new StringBuilder(20 * 1024 * 1024 + 1000);
        String head = sheet(okRow(2, "ZB1", null));
        int at = head.indexOf("</sheetData>");
        pad.append(head, 0, at);
        pad.append(" ".repeat(20 * 1024 * 1024));
        pad.append(head.substring(at));
        byte[] bomb = withSheetXml(pad.toString());
        assertThat(bomb.length).isLessThan(200 * 1024);
        upload(null, bomb, "bomb.xlsx", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMPORT_FILE"));
        assertThat(accounts.query(comp, "select count(*) from sys_import_job", Integer.class)).isZero();
    }

    @Test
    void 관찰_거부된_파일도_원본이_첨부로_남는다() throws Exception {
        int before = accounts.query(comp, "select count(*) from sys_file", Integer.class);
        byte[] f = edit(wb -> wb.getSheet("_meta").getRow(1).getCell(1).setCellValue("0"));
        upload(null, f, "old.xlsx", admin).andExpect(jsonPath("$.code").value("IMPORT_TEMPLATE"));
        upload(null, fill(List.of()), "empty.xlsx", admin).andExpect(jsonPath("$.code").value("IMPORT_EMPTY"));
        assertThat(accounts.query(comp, "select count(*) from sys_file", Integer.class)).isEqualTo(before + 2);
        assertThat(accounts.query(comp, "select count(*) from sys_import_job", Integer.class)).isZero();
    }


    @Test
    void 결함2_스테이징_원문은_칸마다_길이_상한() throws Exception {
        String longText = "가".repeat(32_767);
        byte[] f = fill(List.<Object[]>of(item("LG1", longText, null)));
        long job = jobId(upload(null, f, "long.xlsx", admin));
        long stored = accounts.query(comp, "select sum(octet_length(data::text)) from sys_import_row where job_id = ?",
                Long.class, job);
        assertThat(stored).isLessThan(10_000L);
    }

    // ---------- 흐름 ----------

    @Test
    void 작업_두_개가_같은_새_코드를_넣으면_뒤_작업은_전부_취소() throws Exception {
        long a = jobId(upload(null, fill(List.<Object[]>of(item("SAME1", "a", null))), "a.xlsx", admin));
        long b = jobId(upload(null, fill(List.<Object[]>of(item("OTHER1", "b", null), item("SAME1", "b", null))),
                "b.xlsx", admin).andExpect(jsonPath("$.data.newCount").value(2)));
        execute(a, rowVer(a), false, admin).andExpect(status().isOk());
        execute(b, rowVer(b), false, admin).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IMPORT_STALE"));
        assertThat(accounts.query(comp, "select count(*) from mst_item where item_cd = 'OTHER1'", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select item_nm from mst_item where item_cd = 'SAME1'", String.class)).isEqualTo("a");
        assertThat(accounts.query(comp, "select status from sys_import_job where job_id = ?", String.class, b))
                .isEqualTo("VALIDATED");
    }


    @Test
    void 결함9_검증_뒤_참조가_바뀌면_IMPORT_STALE() throws Exception {
        accounts.exec(comp, """
                insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, attrs, use_yn, created_by, updated_by)
                values (?, 'UNIT', 'QBX', '큐박스', '{"scale": 0}', 'Y', 't', 't')""", comp);
        Object[] r = item("U1", "박스 품목", null);
        r[5] = "QBX";
        long job = jobId(upload(null, fill(List.<Object[]>of(r)), "u.xlsx", admin));
        accounts.exec(comp, "update sys_comp_code set use_yn = 'N' where grp_cd = 'UNIT' and code = 'QBX'");
        execute(job, rowVer(job), false, admin).andExpect(jsonPath("$.code").value("IMPORT_STALE"));
    }

    @Test
    void 검증_뒤_저장_권한을_회수하면_실행_403_조회만_남으면_미리보기는_된다() throws Exception {
        long u = accounts.newUser(comp, "temp1", PW);
        accounts.grant(comp, u, "TEMP", "mst-item", "SEARCH", "SAVE");
        RequestPostProcessor t = accounts.as(comp, u, "temp1");
        long job = jobId(upload(null, fill(List.<Object[]>of(item("RV1", "회수", null))), "r.xlsx", t));
        accounts.exec(comp, """
                delete from sys_auth_prog where auth_grp_cd = 'TEMP' and prog_cd = 'mst-item' and action_cd = 'SAVE'""");
        mvc.perform(get(BASE + "/jobs/" + job).with(t)).andExpect(status().isOk());
        execute(job, rowVer(job), false, t).andExpect(status().isForbidden());
        cancel(job, rowVer(job), t).andExpect(status().isForbidden());
        assertThat(items()).isZero();
    }

    @Test
    void 관찰_저장만_있고_조회가_없으면_미리보기_없이_실행된다() throws Exception {
        long u = accounts.newUser(comp, "saver1", PW);
        accounts.grant(comp, u, "SAVER", "mst-item", "SAVE");
        RequestPostProcessor s = accounts.as(comp, u, "saver1");
        long job = jobId(upload(null, fill(List.<Object[]>of(item("SV1", "저장만", null))), "s.xlsx", s));
        mvc.perform(get(BASE + "/jobs/" + job).with(s)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/jobs/" + job + "/rows").with(s)).andExpect(status().isForbidden());
        execute(job, rowVer(job), false, s).andExpect(status().isOk());
        assertThat(items()).isEqualTo(1);
    }

    @Test
    void 남의_작업_취소와_B사_작업_id_추측은_404_취소는_한_번만() throws Exception {
        long job = jobId(upload(null, fill(List.<Object[]>of(item("CN1", "취소", null))), "c.xlsx", admin));
        String other = accounts.newComp("IQ");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "MASTER", "mst-item", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        long colleague = accounts.newUser(comp, "admin2", PW);
        accounts.grant(comp, colleague, "MASTER2", "mst-item", "SEARCH", "SAVE");
        RequestPostProcessor c = accounts.as(comp, colleague, "admin2");
        cancel(job, rowVer(job), b).andExpect(status().isNotFound());
        cancel(job, rowVer(job), c).andExpect(status().isNotFound());
        // B사가 자기 작업을 만든 뒤 앞뒤 번호를 찔러 본다
        long bJob = jobId(upload(null, fill(List.<Object[]>of(item("BJ1", "b", null))), "b.xlsx", b));
        for (long id : new long[] {job, bJob - 1, bJob + 1}) {
            mvc.perform(get(BASE + "/jobs/" + id).with(b)).andExpect(status().isNotFound());
        }
        cancel(job, rowVer(job), admin).andExpect(jsonPath("$.data.status").value("CANCELED"));
        cancel(job, rowVer(job), admin).andExpect(jsonPath("$.code").value("IMPORT_STATE"));
        cancel(job, rowVer(job) - 1, admin).andExpect(jsonPath("$.code").value("IMPORT_STATE"));
        execute(bJob, accounts.query(other, "select row_ver from sys_import_job where job_id = ?", Integer.class, bJob),
                false, b).andExpect(status().isOk());
        cancel(bJob, 1, b).andExpect(jsonPath("$.code").value("IMPORT_STATE"));   // 실행된 작업 취소
        assertThat(accounts.query(comp, "select count(*) from mst_item where item_cd = 'BJ1'", Integer.class)).isZero();
    }

    @Test
    void 행_목록_파라미터_조작은_400() throws Exception {
        long job = jobId(upload(null, fill(List.<Object[]>of(item("PG1", "p", null))), "p.xlsx", admin));
        for (String[] p : new String[][] {{"status", "new"}, {"status", "NEW' or '1'='1"}, {"size", "501"},
            {"size", "0"}, {"page", "0"}, {"page", "-1"}}) {
            mvc.perform(get(BASE + "/jobs/" + job + "/rows").param(p[0], p[1]).with(admin))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get(BASE + "/jobs/" + job + "/rows").param("page", String.valueOf(Integer.MAX_VALUE)).with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.list.length()").value(0));
        mvc.perform(get(BASE + "/jobs/abc").with(admin)).andExpect(status().isBadRequest());
        execute(job, rowVer(job), false, admin);
        mvc.perform(post(BASE + "/jobs/" + job + "/execute").with(csrf()).with(admin)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }

    // ---------- 변경 모드 · 감사 · 오류 엑셀 ----------


    @Test
    void 결함4_변경_모드의_빈_YN_칸은_기존_값을_유지() throws Exception {
        Object[] r = item("RE1", "중지 예정", null);
        r[6] = "N";
        long first = jobId(upload(null, fill(List.<Object[]>of(r)), "a.xlsx", admin));
        execute(first, rowVer(first), false, admin).andExpect(status().isOk());
        accounts.exec(comp, "update mst_item set use_yn = 'N', row_ver = row_ver + 1 where item_cd = 'RE1'");
        long job = jobId(upload("UPSERT", fill(List.<Object[]>of(item("RE1", "이름만 변경", null))), "b.xlsx", admin));
        execute(job, rowVer(job), false, admin).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select use_yn || lot_ctrl_yn from mst_item where item_cd = 'RE1'", String.class))
                .isEqualTo("NN");
    }

    @Test
    void 실행으로_바뀐_품목의_변경_이력_행위자는_올린_사람() throws Exception {
        long job = jobId(upload(null, fill(List.<Object[]>of(item("AU1", "감사", null))), "au.xlsx", admin));
        execute(job, rowVer(job), false, admin).andExpect(status().isOk());
        long id = accounts.query(comp, "select item_id from mst_item where item_cd = 'AU1'", Long.class);
        assertThat(accounts.query(comp, "select actor from sys_audit_log where table_nm = 'mst_item' and row_key = ?",
                String.class, String.valueOf(id))).isEqualTo("u" + userId + ":admin1");
        mvc.perform(get("/api/platform/audit").param("table", "mst_item").param("key", String.valueOf(id)).with(admin))
                .andExpect(jsonPath("$.list[0].action").value("INSERT"));
    }


    @Test
    void 결함6_오류_엑셀도_수식_주입_방지() throws Exception {
        long job = jobId(upload(null, fill(List.<Object[]>of(item("=HYPERLINK(\"http://x\",\"a\")", "수식", null))),
                "inj.xlsx", admin));
        byte[] x = mvc.perform(post(BASE + "/jobs/" + job + "/errors/export").with(csrf()).with(admin))
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            assertThat(wb.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()).startsWith("'=");
        }
    }

    @Test
    void 파일의_회사_코드처럼_보이는_열_이름_변형도_무시() throws Exception {
        String other = accounts.newComp("IQ");
        String head = "<row r=\"1\">" + c("A1", "itemCd") + c("B1", "itemNm") + c("C1", "itemType") + c("D1", "procType")
                + c("E1", "unitCd") + c("F1", "compCd") + c("G1", "COMP_CD") + c("H1", "회사코드") + "</row>";
        String data = "<row r=\"2\">" + c("A2", "CP1") + c("B2", "회사") + c("C2", "FG") + c("D2", "MAKE") + c("E2", "EA")
                + c("F2", other) + c("G2", other) + c("H2", other) + "</row>";
        long job = jobId(upload(null, withSheetXml("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"" + NS
                + "\"><sheetData>" + head + data + "</sheetData></worksheet>"), "cp.xlsx", admin)
                .andExpect(jsonPath("$.data.ignoredColumns.length()").value(3)));
        execute(job, rowVer(job), false, admin).andExpect(status().isOk());
        assertThat(accounts.query(other, "select count(*) from mst_item", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select comp_cd from mst_item where item_cd = 'CP1'", String.class)).isEqualTo(comp);
        assertThat(accounts.query(comp, "select count(*) from sys_import_row where comp_cd <> ?", Integer.class, comp)).isZero();
    }
}
