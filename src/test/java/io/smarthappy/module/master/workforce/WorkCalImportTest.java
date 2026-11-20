package io.smarthappy.module.master.workforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hamcrest.Matchers;
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

/**
 * 공장 휴일 달력 엑셀 올리기 MST_WORK_CAL (docs/25 11.4a 1번) — 새 예외(회사·공장·근무조, 소문자 코드), 행 오류(날짜·공장·근무조·구분·사유),
 * 파일 안 같은 키, 다시 올리면 같음·변경(구분·사유만), 사용 중지한 키는 새로, 새 것만 모드의 있는 키, A사로 B사, 조회 권한만이면 403.
 */
@AutoConfigureMockMvc
class WorkCalImportTest extends PostgresTestBase {

    private static final String PW = "Amber-harbor-58";
    private static final String IMP = "/api/platform/import";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("WCI");
        admin = user(comp, "admin1", "SEARCH", "SAVE");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, """
                insert into mst_shift (comp_cd, plant_cd, shift_cd, shift_nm, start_time, end_time, use_yn, created_by, updated_by)
                values (?, 'P1', 'D', '주간', '08:00', '20:00', 'Y', 't', 't'), (?, 'P1', 'X', '옛 조', '06:00', '07:00', 'N', 't', 't')""",
                comp, comp);
    }

    /** 권한 그룹은 사용자마다 따로(같은 그룹이면 저장 권한을 함께 받는다) */
    private RequestPostProcessor user(String c, String loginId, String... actions) {
        long u = accounts.newUser(c, loginId, PW);
        accounts.grant(c, u, "G_" + loginId.toUpperCase(java.util.Locale.ROOT), WorkCalService.PROG, actions);
        return accounts.as(c, u, loginId);
    }

    /** 열 순서: 날짜·공장·근무조·구분·사유 */
    private static Object[] row(String date, String plant, String shift, String type, String reason) {
        return new Object[] {date, plant, shift, type, reason};
    }

    private byte[] fill(RequestPostProcessor who, List<Object[]> rows) throws Exception {
        byte[] tpl = mvc.perform(post(IMP + "/templates/MST_WORK_CAL/file").with(csrf()).with(who))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(tpl))) {
            Sheet s = wb.getSheetAt(0);
            int r = 1;
            for (Object[] values : rows) {
                Row row = s.createRow(r++);
                for (int c = 0; c < values.length; c++) {
                    if (values[c] != null) {
                        row.createCell(c).setCellValue(String.valueOf(values[c]));
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private ResultActions upload(RequestPostProcessor who, byte[] file, String mode) throws Exception {
        return mvc.perform(multipart(IMP + "/templates/MST_WORK_CAL/jobs").file(new MockMultipartFile("file", "cal.xlsx", XLSX, file))
                .param("mode", mode).with(csrf()).with(who));
    }

    private ResultActions execute(String c, RequestPostProcessor who, long job) throws Exception {
        int ver = accounts.query(c, "select row_ver from sys_import_job where job_id = ?", Integer.class, job);
        return mvc.perform(post(IMP + "/jobs/" + job + "/execute").with(csrf()).with(who)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rowVer\":" + ver + ",\"skipErrors\":true}"));
    }

    private static long job(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.jobId")).longValue();
    }

    private String cal(String date, String plant, String shift) {
        List<String> v = accounts.query(comp, """
                select coalesce(string_agg(day_type || '/' || reason, ','), '') from mst_work_cal
                 where use_yn = 'Y' and cal_date = ?::date and plant_cd is not distinct from ? and shift_cd is not distinct from ?""",
                String.class, date, plant, shift).lines().toList();
        return v.isEmpty() ? "" : v.get(0);
    }

    @Test
    void 새_예외는_회사_공장_근무조로_들어가고_행_오류와_파일_안_같은_키는_빠진다() throws Exception {
        long j = job(upload(admin, fill(admin, List.<Object[]>of(
                row("2026-12-25", null, null, "OFF", "성탄절"),                  // 회사 공통
                row("2026-12-31", "p1", null, "off", " 연말 휴무 "),              // 소문자·공백 정리
                row("2026-12-26", "P1", "d", "WORK", "특근"),                    // 근무조
                row("2026-13-01", null, null, "OFF", "날짜 틀림"),
                row("1999-12-31", null, null, "OFF", "범위 밖"),
                row("2026-12-24", "P9", null, "OFF", "없는 공장"),
                row("2026-12-24", null, "D", "OFF", "공장 없는 근무조"),
                row("2026-12-24", "P1", "X", "OFF", "사용 중지 근무조"),
                row("2026-12-24", "P1", "Z", "OFF", "없는 근무조"),
                row("2026-12-24", "P1", null, "HOLIDAY", "구분 틀림"),
                row("2026-12-24", "P1", null, "OFF", null),
                row("2027-01-01", null, null, "OFF", "신정"),
                row("2027-01-01", null, null, "WORK", "같은 키 두 번"))), "UPSERT")
                .andExpect(jsonPath("$.data.newCount").value(3))
                .andExpect(jsonPath("$.data.errorCount").value(10)));
        mvc.perform(get(IMP + "/jobs/" + j + "/rows?status=ERROR&size=50").with(admin))
                .andExpect(jsonPath("$.list[*].errors[*].code").value(Matchers.containsInAnyOrder("FORMAT", "FORMAT", "NO_SUCH_CODE",
                        "SHIFT_NEEDS_PLANT", "INACTIVE", "NO_SUCH_CODE", "CODE", "REQUIRED", "FILE_DUPLICATE", "FILE_DUPLICATE")));
        execute(comp, admin, j).andExpect(status().isOk()).andExpect(jsonPath("$.data.result.inserted").value(3));
        assertThat(cal("2026-12-25", null, null)).isEqualTo("OFF/성탄절");
        assertThat(cal("2026-12-31", "P1", null)).isEqualTo("OFF/연말 휴무");
        assertThat(cal("2026-12-26", "P1", "D")).isEqualTo("WORK/특근");
        assertThat(cal("2027-01-01", null, null)).isEmpty();
        // 화면 달 조회에도 그대로(읽기 계약 WorkCalendar 층)
        mvc.perform(get("/api/master/work-cal?month=2026-12&plantCd=P1").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(3));
    }

    @Test
    void 다시_올리면_같음_변경은_구분_사유만_사용_중지한_키는_새로_새_것만_모드는_있는_키_오류() throws Exception {
        execute(comp, admin, job(upload(admin, fill(admin, List.<Object[]>of(
                row("2026-12-25", null, null, "OFF", "성탄절"),
                row("2026-12-31", "P1", null, "OFF", "연말"))), "UPSERT"))).andExpect(status().isOk());
        long stopped = accounts.query(comp, "select cal_id from mst_work_cal where plant_cd = 'P1'", Long.class);
        accounts.exec(comp, "update mst_work_cal set use_yn = 'N', row_ver = row_ver + 1 where cal_id = ?", stopped);
        long j = job(upload(admin, fill(admin, List.<Object[]>of(
                row("2026-12-25", null, null, "OFF", "성탄절"),                    // 같음
                row("2026-12-25", "P1", null, "WORK", "1공장은 일함"),             // 새 키(공장 층)
                row("2026-12-31", "P1", null, "WORK", "연말 특근"))), "UPSERT")   // 사용 중지한 키 → 새로
                .andExpect(jsonPath("$.data.sameCount").value(1))
                .andExpect(jsonPath("$.data.newCount").value(2)));
        execute(comp, admin, j).andExpect(status().isOk()).andExpect(jsonPath("$.data.result.inserted").value(2));
        assertThat(cal("2026-12-31", "P1", null)).isEqualTo("WORK/연말 특근");
        assertThat(accounts.query(comp, "select count(*) from mst_work_cal where plant_cd = 'P1' and cal_date = date '2026-12-31'",
                Integer.class)).isEqualTo(2);                                           // 옛 행은 사용 중지 그대로
        // 변경: 구분·사유만 바뀐다(대상은 키)
        long j2 = job(upload(admin, fill(admin, List.<Object[]>of(row("2026-12-25", null, null, "WORK", "성탄절 근무"))), "UPSERT")
                .andExpect(jsonPath("$.data.changeCount").value(1)));
        execute(comp, admin, j2).andExpect(status().isOk()).andExpect(jsonPath("$.data.result.updated").value(1));
        assertThat(cal("2026-12-25", null, null)).isEqualTo("WORK/성탄절 근무");
        // 새 것만 모드: 있는 키는 오류
        upload(admin, fill(admin, List.<Object[]>of(row("2026-12-25", null, null, "OFF", "다시"))), "INSERT_ONLY")
                .andExpect(jsonPath("$.data.errorCount").value(1));
    }

    @Test
    void B사는_A사_공장_코드와_예외를_모르고_조회_권한만이면_올리기_403() throws Exception {
        execute(comp, admin, job(upload(admin, fill(admin, List.<Object[]>of(row("2026-12-25", "P1", null, "OFF", "A사"))), "UPSERT")))
                .andExpect(status().isOk());
        String other = accounts.newComp("WCJ");
        RequestPostProcessor b = user(other, "admin1", "SEARCH", "SAVE");
        long j = job(upload(b, fill(b, List.<Object[]>of(
                row("2026-12-25", "P1", null, "OFF", "A사 공장"),                    // B사에 P1 없음
                row("2026-12-25", null, null, "OFF", "B사 공통"))), "UPSERT")
                .andExpect(jsonPath("$.data.errorCount").value(1))
                .andExpect(jsonPath("$.data.newCount").value(1)));                   // A사 행은 B사의 "있는 키"가 아니다
        execute(other, b, j).andExpect(status().isOk());
        assertThat(accounts.query(other, "select count(*) from mst_work_cal", Integer.class)).isEqualTo(1);
        assertThat(accounts.query(comp, "select count(*) from mst_work_cal", Integer.class)).isEqualTo(1);
        RequestPostProcessor viewer = user(comp, "viewer1", "SEARCH");
        upload(viewer, fill(admin, List.<Object[]>of(row("2026-12-26", null, null, "OFF", "x"))), "UPSERT").andExpect(status().isForbidden());
    }
}
