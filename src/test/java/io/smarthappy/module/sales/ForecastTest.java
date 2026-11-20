package io.smarthappy.module.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 내시 (P2 2단계, docs/25 4.3) — 받을 때마다 새 버전(이전 그대로·수정·삭제 차단), 버전 비교(늘어남·줄어듦·새로·사라짐),
 * 기간 시작 규칙(주 월요일·월 1일), 수량 0 허용·음수 거부, 같은 거래처 동시 수신 번호, 엑셀(파일 하나 = 거래처별 버전 하나), A사로 B사.
 */
@AutoConfigureMockMvc
class ForecastTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String F = "/api/sales/forecasts";
    private static final String IMP = "/api/platform/import";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long userId;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("FC");
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "SALES", "sal-forecast", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        for (String c : new String[] {"C-001", "C-002"}) {
            accounts.exec(comp, """
                    insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                    values (?, ?, ?, 'SALES', 't', 't')""", comp, c, c + " 상사");
        }
        for (String[] i : new String[][] {{"FG-1", "EA"}, {"FG-2", "EA"}, {"RM-1", "KG"}}) {
            accounts.exec(comp, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                    values (?, ?, ?, 'FG', 'MAKE', ?, 't', 't')""", comp, i[0], i[0] + " 품목", i[1]);
        }
    }

    private ResultActions receive(RequestPostProcessor who, String cust, String... lines) throws Exception {
        return mvc.perform(post(F).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON)
                .content("{\"custCd\":\"" + cust + "\",\"lines\":[" + String.join(",", lines) + "]}"));
    }

    private static String l(String item, String type, String start, String qty) {
        return "{\"itemCd\":\"" + item + "\",\"bucketType\":\"" + type + "\",\"bucketStart\":\"" + start + "\",\"qty\":\"" + qty + "\"}";
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    @Test
    void 받을_때마다_새_버전이고_비교는_늘어남_줄어듦_새로_사라짐() throws Exception {
        long r1 = id(receive(admin, "C-001",
                l("FG-1", "WEEK", "2026-11-02", "100"), l("FG-1", "WEEK", "2026-11-09", "200"),
                l("FG-2", "MONTH", "2026-12-01", "50"), l("RM-1", "DAY", "2026-11-03", "1.5")));
        long r2 = id(receive(admin, "C-001",
                l("FG-1", "WEEK", "2026-11-02", "120"), l("FG-1", "WEEK", "2026-11-09", "150"),
                l("FG-2", "MONTH", "2027-01-01", "60"), l("RM-1", "DAY", "2026-11-03", "1.5")));
        mvc.perform(get(F + "/revs?custCd=c-001").with(admin))
                .andExpect(jsonPath("$.data[*].revNo").value(Matchers.contains(2, 1)))
                .andExpect(jsonPath("$.data[0].source").value("MANUAL"))
                .andExpect(jsonPath("$.data[0].lineCnt").value(4))
                .andExpect(jsonPath("$.data[0].firstBucket").value("2026-11-02"))
                .andExpect(jsonPath("$.data[0].lastBucket").value("2027-01-01"));
        // 이전 버전은 그대로
        mvc.perform(get(F + "/revs/" + r1 + "/lines").with(admin))
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].itemCd").value("FG-1"))
                .andExpect(jsonPath("$.data[0].qty").value("100"));
        // 비교(앞 버전 자동): 120↑ 150↓ 12월 사라짐·1월 새로, RM-1 같음
        mvc.perform(get(F + "/compare?toRevId=" + r2).with(admin))
                .andExpect(jsonPath("$.data.from.revNo").value(1))
                .andExpect(jsonPath("$.data.rows.length()").value(5))
                .andExpect(jsonPath("$.data.rows[0].fromQty").value("100"))
                .andExpect(jsonPath("$.data.rows[0].toQty").value("120"))
                .andExpect(jsonPath("$.data.rows[2].bucketStart").value("2026-12-01"))
                .andExpect(jsonPath("$.data.rows[2].toQty").doesNotExist())
                .andExpect(jsonPath("$.data.rows[3].fromQty").doesNotExist())
                .andExpect(jsonPath("$.data.rows[4].fromQty").value("1.5"));
        // 첫 버전 비교 = 전부 새로
        mvc.perform(get(F + "/compare?toRevId=" + r1).with(admin))
                .andExpect(jsonPath("$.data.from").doesNotExist())
                .andExpect(jsonPath("$.data.rows.length()").value(4));
        // 다른 거래처 버전끼리는 비교하지 않는다
        long r3 = id(receive(admin, "C-002", l("FG-1", "DAY", "2026-11-03", "0")));
        mvc.perform(get(F + "/compare?toRevId=" + r3 + "&fromRevId=" + r1).with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("OTHER_CUST"));
        assertThat(accounts.query(comp, "select rev_no from sal_forecast_rev where rev_id = ?", Integer.class, r3)).isEqualTo(1);
    }

    @Test
    void 기간_시작_수량_중복_검증() throws Exception {
        receive(admin, "C-001",
                l("FG-1", "WEEK", "2026-11-03", "1"),        // 화요일
                l("FG-1", "MONTH", "2026-11-15", "1"),
                l("FG-1", "DAY", "2026-11-03", "-1"),
                l("FG-1", "DAY", "2026-11-04", "1.5"),       // EA 자릿수 0
                l("FG-1", "YEAR", "2026-11-05", "1"),
                l("FG-2", "DAY", "2026-11-06", "1e2"),
                l("FG-2", "DAY", "2026-11-07", "1"),
                l("fg-2", "day", "2026-11-07", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].code").value(Matchers.contains("WEEK_START", "MONTH_START", "NOT_NEGATIVE",
                        "SCALE", "CODE", "FORMAT", "DUPLICATE")));
        receive(admin, "C-001").andExpect(jsonPath("$.errors[0].code").value("NO_LINES"));
        assertThat(accounts.query(comp, "select count(*) from sal_forecast_rev", Integer.class)).isZero();
        // 0 은 의미 있는 값(수요 없음)
        receive(admin, "C-001", l("FG-1", "DAY", "2026-11-03", "0")).andExpect(status().isOk());
    }

    @Test
    void 버전과_행은_직접_SQL_로도_고치거나_지울_수_없다() throws Exception {
        long r1 = id(receive(admin, "C-001", l("FG-1", "WEEK", "2026-11-02", "100")));
        long line = accounts.query(comp, "select line_id from sal_forecast_line where rev_id = ?", Long.class, r1);
        for (String sql : new String[] {"update sal_forecast_line set qty = 1 where line_id = " + line,
            "delete from sal_forecast_line where line_id = " + line,
            "update sal_forecast_rev set rev_no = 9 where rev_id = " + r1,
            "delete from sal_forecast_rev where rev_id = " + r1}) {
            assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, sql)))).as(sql).contains("42501");
        }
        // 직접 넣는 행도 규칙대로: 화요일 주, 음수
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into sal_forecast_line (comp_cd, rev_id, item_id, bucket_type, bucket_start, qty)
                values (?, ?, (select item_id from mst_item where item_cd = 'FG-2'), 'WEEK', '2026-11-03', 1)""", comp, r1))))
                .contains("ck_sal_forecast_line_start");
    }

    @Test
    void 같은_거래처를_동시에_받아도_버전_번호가_겹치지_않는다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Callable<Integer> c = () -> receive(admin, "C-001", l("FG-1", "DAY", "2026-11-03", "1"))
                    .andReturn().getResponse().getStatus();
            List<Future<Integer>> fs = pool.invokeAll(List.of(c, c, c, c));
            for (Future<Integer> f : fs) {
                assertThat(f.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdown();
        }
        assertThat(accounts.query(comp, "select string_agg(rev_no::text, ',' order by rev_no) from sal_forecast_rev", String.class))
                .isEqualTo("1,2,3,4");
    }

    @Test
    void B사는_A사_내시를_볼_수도_A사_버전에_행을_넣을_수도_없다() throws Exception {
        long r1 = id(receive(admin, "C-001", l("FG-1", "WEEK", "2026-11-02", "100")));
        String other = accounts.newComp("FB");
        long ou = accounts.newUser(other, "b1", PW);
        accounts.grant(other, ou, "SALES", "sal-forecast", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, ou, "b1");
        mvc.perform(get(F + "/revs?custCd=C-001").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(F + "/revs/" + r1 + "/lines").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(F + "/compare?toRevId=" + r1).with(b)).andExpect(status().isNotFound());
        receive(b, "C-001", l("FG-1", "DAY", "2026-11-03", "1"))
                .andExpect(jsonPath("$.errors[0].field").value("custCd"))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        long aItem = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG-1'", Long.class);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into sal_forecast_line (comp_cd, rev_id, item_id, bucket_type, bucket_start, qty)
                values (?, ?, ?, 'DAY', '2026-11-03', 1)""", other, r1, aItem)))).contains("fk_sal_forecast_line_rev_comp");
        mvc.perform(get(F + "/customers").with(b)).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void 조회_권한만_있으면_수신은_403() throws Exception {
        long viewer = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewer, "VIEW", "sal-forecast", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer");
        receive(v, "C-001", l("FG-1", "DAY", "2026-11-03", "1")).andExpect(status().isForbidden());
        mvc.perform(get(F + "/revs?custCd=C-001").with(v)).andExpect(status().isOk());
    }

    // ---------- 엑셀 ----------

    private byte[] fill(List<Object[]> rows) throws Exception {
        byte[] tpl = mvc.perform(post(IMP + "/templates/SAL_FORECAST/file").with(csrf()).with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(tpl))) {
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
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private long upload(byte[] file, String name) throws Exception {
        MvcResult m = mvc.perform(multipart(IMP + "/templates/SAL_FORECAST/jobs")
                        .file(new MockMultipartFile("file", name, XLSX, file)).with(csrf()).with(admin))
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.jobId")).longValue();
    }

    private ResultActions execute(long job) throws Exception {
        int ver = accounts.query(comp, "select row_ver from sys_import_job where job_id = ?", Integer.class, job);
        return mvc.perform(post(IMP + "/jobs/" + job + "/execute").with(csrf()).with(admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rowVer\":" + ver + ",\"skipErrors\":true}"));
    }

    @Test
    void 엑셀_파일_하나는_거래처별_새_버전_하나이고_원본_작업을_가리킨다() throws Exception {
        long j1 = upload(fill(List.of(
                new Object[] {"C-001", "FG-1", "WEEK", "2026-11-02", 100, null},
                new Object[] {"C-001", "FG-1", "주", "2026-11-09", 200, "Y"},           // 코드 대신 이름
                new Object[] {"C-002", "FG-2", "MONTH", "2026-12-01", 50, null},
                new Object[] {"C-001", "FG-2", "WEEK", "2026-11-03", 1, null},         // 화요일
                new Object[] {"C-001", "FG-1", "WEEK", "2026-11-02", 5, null})),       // 같은 키(1행과)
                "w45.xlsx");
        mvc.perform(get(IMP + "/jobs/" + j1).with(admin))
                .andExpect(jsonPath("$.data.newCount").value(2))
                .andExpect(jsonPath("$.data.errorCount").value(3));
        execute(j1).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result.inserted").value(2))
                .andExpect(jsonPath("$.data.result.matched").value(true));
        assertThat(accounts.query(comp, "select count(*) from sal_forecast_rev where import_job_id = ? and source = 'FILE'",
                Integer.class, j1)).isEqualTo(2);
        mvc.perform(get(F + "/revs?custCd=C-001").with(admin))
                .andExpect(jsonPath("$.data[0].fileNm").value("w45.xlsx"))
                .andExpect(jsonPath("$.data[0].lineCnt").value(1));
        // 같은 내용을 다시 올려도 덮어쓰지 않고 2차
        long j2 = upload(fill(List.<Object[]>of(new Object[] {"C-001", "FG-1", "WEEK", "2026-11-09", 250, "Y"})), "w46.xlsx");
        execute(j2).andExpect(status().isOk());
        mvc.perform(get(F + "/revs?custCd=C-001").with(admin))
                .andExpect(jsonPath("$.data[*].revNo").value(Matchers.contains(2, 1)));
        assertThat(accounts.query(comp, "select qty::int from sal_forecast_line l join sal_forecast_rev r on r.rev_id = l.rev_id "
                + "where r.rev_no = 1 and r.cust_id = (select cust_id from mst_cust where cust_cd = 'C-001')", Integer.class)).isEqualTo(200);
        assertThat(accounts.query(comp, "select firm_yn from sal_forecast_line where qty = 250", String.class)).isEqualTo("Y");
    }
}
