package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.web.FieldErrors;

/**
 * AQL 표 엑셀 올리기 (docs/29 D3 — 시드 없이 회사가 올림). 표 1(로트 → 문자)·표 2(문자 × AQL → n·Ac·Re),
 * 구간 겹침·1회 샘플링 규칙·AQL 수치 정규화 키(0.65 = 0.650)·이름으로 쓴 코드·새것+변경, 올린 표로 샘플링 계획·판정.
 * 정수 칸 쉼표는 천 단위 자리일 때만(hb.numbers GROUPED — docs/29_P3 41장): "0,5"·"1,23" 이 5·123 으로 조용히 바뀌지 않게.
 */
class QmsAqlImportTest extends QmsTestBase {

    private static final String BASE = "/api/platform/import";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private byte[] fill(String code, String[]... rows) throws Exception {
        byte[] tpl = mvc.perform(post(BASE + "/templates/" + code + "/file").with(csrf()).with(admin))
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

    private ResultActions upload(String code, String mode, byte[] file) throws Exception {
        var req = multipart(BASE + "/templates/" + code + "/jobs").file(new MockMultipartFile("file", "aql.xlsx", XLSX, file));
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

    @Test
    void 표_두개를_올리면_그_표준으로_AQL_샘플링_계획을_만들고_판정한다() throws Exception {
        execute(jobId(upload("QMS_AQL_TABLE", null, fill("QMS_AQL_TABLE",
                new String[] {"new1", "II", "1", "10", "a"},
                new String[] {"NEW1", "일반 II", "11", "100", "B"},          // 이름으로 쓴 코드
                new String[] {"NEW1", "II", "101", null, "C"}))                 // 끝 없음
                .andExpect(jsonPath("$.data.newCount").value(3)).andExpect(jsonPath("$.data.errorCount").value(0))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.result.matched").value(true));
        execute(jobId(upload("QMS_AQL_PLAN", null, fill("QMS_AQL_PLAN",
                new String[] {"NEW1", "NORMAL", "A", "0.65", "2", "0", "1"},
                new String[] {"NEW1", "보통 검사", "B", "0.650", "13", "0", "1"},
                new String[] {"NEW1", "NORMAL", "C", "0.65", "50", "1", "2"},
                new String[] {"NEW1", "REDUCED", "C", "0.65", "20", "0", "2"}))       // 수월한 검사는 간격 허용
                .andExpect(jsonPath("$.data.newCount").value(4)).andExpect(jsonPath("$.data.errorCount").value(0))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/qms/aql").param("stdCd", "NEW1").with(admin)).andExpect(jsonPath("$.data.letters.length()").value(3))
                .andExpect(jsonPath("$.data.letters[0].codeLetter").value("A")).andExpect(jsonPath("$.data.rows.length()").value(4));
        long sp = id(postJson(SP, "{\"planCd\":\"N065\",\"planNm\":\"AQL 0.65\",\"method\":\"AQL\",\"stdCd\":\"NEW1\",\"inspLevel\":\"II\","
                + "\"severity\":\"NORMAL\",\"aql\":\"0.65\"}"));
        postJson(SP + "/preview", "{\"samplingPlanId\":" + sp + ",\"lotQty\":1000,\"defects\":1}")
                .andExpect(jsonPath("$.data.codeLetter").value("C")).andExpect(jsonPath("$.data.sampleSize").value(50))
                .andExpect(jsonPath("$.data.verdict").value("ACCEPT"));
        postJson(SP + "/preview", "{\"form\":{\"method\":\"AQL\",\"stdCd\":\"NEW1\",\"inspLevel\":\"II\",\"severity\":\"REDUCED\","
                + "\"aql\":\"0.65\"},\"lotQty\":1000,\"defects\":1}").andExpect(jsonPath("$.data.verdict").value("ACCEPT_TO_NORMAL"));
    }

    @Test
    void 표1_구간_겹침_끝이_시작보다_작음_형식은_행_오류() throws Exception {
        // 기존 FAKE II: 1-8, 9-50, 51-500, 501-
        long job = jobId(upload("QMS_AQL_TABLE", null, fill("QMS_AQL_TABLE",
                new String[] {"FAKE", "II", "600", "700", "E"},               // 기존 501~ 와 겹침
                new String[] {"NEW2", "II", "1", "100", "A"},
                new String[] {"NEW2", "II", "50", "200", "B"},                // 파일 앞 행과 겹침
                new String[] {"NEW2", "II", "300", "200", "C"},               // 끝 < 시작
                new String[] {"NEW2", "IV", "1.5", null, "AB"},               // 수준·정수·문자 형식
                new String[] {"NEW2", "II", "1", "100", "A"}))                // 파일 안 같은 키 → 2행·6행 모두 FILE_DUPLICATE
                .andExpect(jsonPath("$.data.newCount").value(0)).andExpect(jsonPath("$.data.errorCount").value(6)));
        mvc.perform(get(BASE + "/jobs/" + job + "/rows").with(admin))
                .andExpect(jsonPath("$.list[0].errors[0].code").value("AQL_OVERLAP"))
                .andExpect(jsonPath("$.list[2].errors[0].code").value("AQL_OVERLAP"))
                .andExpect(jsonPath("$.list[3].errors[0].code").value("LOT_RANGE"))
                .andExpect(jsonPath("$.list[4].errors.length()").value(3));
        // 기존 구간을 바꾸는 행(같은 키)은 자기 자신과 겹침으로 보지 않는다 — 새것+변경
        long change = jobId(upload("QMS_AQL_TABLE", "UPSERT", fill("QMS_AQL_TABLE",
                new String[] {"FAKE", "II", "501", "100000", "D"}, new String[] {"FAKE", "II", "9", "50", "B"}))
                .andExpect(jsonPath("$.data.changeCount").value(1)).andExpect(jsonPath("$.data.sameCount").value(1))
                .andExpect(jsonPath("$.data.errorCount").value(0)));
        execute(change).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select lot_to from qms_aql_table where std_cd = 'FAKE' and lot_from = 501", Long.class))
                .isEqualTo(100000L);
    }

    @Test
    void 표2_Ac_Re_규칙과_AQL_수치_키() throws Exception {
        long job = jobId(upload("QMS_AQL_PLAN", "UPSERT", fill("QMS_AQL_PLAN",
                new String[] {"FAKE", "NORMAL", "A", "1", "2", "0", "1"},           // 기존 1.0 과 같은 줄 → 같음
                new String[] {"FAKE", "NORMAL", "B", "1.00", "9", "0", "1"},        // 기존 n 8 → 변경
                new String[] {"FAKE", "NORMAL", "E", "1.0", "10", "1", "3"},        // 보통인데 간격
                new String[] {"FAKE", "TIGHTENED", "E", "1.0", "5", "2", "6"},      // Re > n
                new String[] {"FAKE", "NORMAL", "F", "0", "-1", "x", null},         // AQL 0·n 음수·Ac 형식·Re 없음
                new String[] {"FAKE", "NORMAL", "E", "1.0000", "10", "0", "1"}))    // 파일 안 3행과 같은 키(1.0 = 1.0000)
                .andExpect(jsonPath("$.data.sameCount").value(1)).andExpect(jsonPath("$.data.changeCount").value(1))
                .andExpect(jsonPath("$.data.errorCount").value(4)));
        mvc.perform(get(BASE + "/jobs/" + job + "/rows").with(admin))
                .andExpect(jsonPath("$.list[2].errors[0].code").value("AC_RE_GAP"))
                .andExpect(jsonPath("$.list[3].errors[0].code").value("AC_RE"))
                .andExpect(jsonPath("$.list[4].errors.length()").value(4));
    }

    @Test
    void 정수_칸_쉼표는_천_단위_자리일_때만_받고_아니면_형식_오류() throws Exception {
        for (String ok : new String[] {"1,000", "12,345", "1,000,000", "1,000.0", "281.0", "281"}) {
            FieldErrors e = FieldErrors.create();
            assertThat(AqlTableImportTemplate.whole(ok, "lotTo", true, e)).as(ok).isEqualTo(Long.parseLong(ok.replace(",", "").replace(".0", "")));
            assertThat(e.isEmpty()).as(ok).isTrue();
        }
        for (String bad : new String[] {"0,5", "1,23", "012,345", "1,0000", ",100", "100,", "1,,000", "1 000", "1.000,5"}) {
            FieldErrors e = FieldErrors.create();
            assertThat(AqlTableImportTemplate.whole(bad, "lotTo", true, e)).as(bad).isNull();
            assertThat(e.list()).as(bad).extracting(FieldErrors.FieldError::code).containsExactly("FORMAT");
        }
        // 올리기 화면 경로: 표 1 끝값·표 2 표본 수
        long job = jobId(upload("QMS_AQL_TABLE", null, fill("QMS_AQL_TABLE",
                new String[] {"CMA", "II", "1", "1,000", "A"},
                new String[] {"CMA", "III", "1", "1,23", "B"},
                new String[] {"CMA", "I", "0,5", null, "C"}))
                .andExpect(jsonPath("$.data.newCount").value(1)).andExpect(jsonPath("$.data.errorCount").value(2)));
        mvc.perform(get(BASE + "/jobs/" + job + "/rows").with(admin))
                .andExpect(jsonPath("$.list[1].errors[0].code").value("FORMAT"))
                .andExpect(jsonPath("$.list[2].errors[0].code").value("FORMAT"));
        jobId(upload("QMS_AQL_PLAN", null, fill("QMS_AQL_PLAN",
                new String[] {"FAKE", "NORMAL", "Q", "1.0", "1,25", "0", "1"}))
                .andExpect(jsonPath("$.data.errorCount").value(1)));
    }
}
