package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA — 검사 실행 엑셀 올리기(V196, docs/29_P3 25장) 이상 사용. 모듈 시험(InspMeasImportTest)이 덮은 것(화면과 같은 판정·SPC·보류, 행 오류·멱등,
 * 미리보기 뒤 차례로 바뀜 409, 파일 둘 교착 없음, 권한·A사로 B사)은 다시 하지 않는다. 여기서는 화면·엑셀이 같은 샘플을 **동시에**(머리 잠금 안에서 겹침),
 * IMPORT_MAX_ROWS 경계, 회사 시간대 자정·검사 시작 직전, 지난 측정 시각의 계측기 상태. 분석 docs/qa/2026-10-27_qms-import-ocap-pda.md
 */
@AutoConfigureMockMvc
class InspImportQaTest extends PostgresTestBase {

    static final String PW = "Qa-insp-import-29";
    static final String IMP = "/api/platform/import";
    static final String TPL = "QMS_INSP_MEAS";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String I = "/api/qms/insps";
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final DateTimeFormatter MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    static final ExecutorService POOL = Executors.newFixedThreadPool(3);

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    RequestPostProcessor admin;
    long gauge;
    int lotSeq;

    @AfterAll
    static void stop() {
        POOL.shutdownNow();
    }

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("QI");
        long u = accounts.newUser(comp, "qi1", PW);
        accounts.grant(comp, u, "QA", "qms-insp-plan", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, u, "QA", "qms-insp", "SEARCH", "SAVE");
        accounts.grant(comp, u, "QA", "qms-gauge-calib", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "qi1");
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'QI-FG', 'QA 제품', 'FG', 'MAKE', 'EA', 't', 't')""", comp);
        long ch = accounts.query(comp, """
                insert into qms_char (comp_cd, char_type, item_id, char_no, char_nm, data_type, unit_nm, scale, lsl, nominal, usl, created_by, updated_by)
                select ?, 'PRODUCT', item_id, '1', '지름', 'VARIABLE', 'mm', 2, 9.95, 10.00, 10.05, 't', 't' from mst_item where item_cd = 'QI-FG'
                returning char_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into qms_sampling_plan (comp_cd, plan_cd, plan_nm, method, fixed_n, ac, re, created_by, updated_by)
                values (?, 'S5', '고정 5', 'FIXED', 5, 0, 1, 't', 't')""", comp);
        long plan = data(postJson("/api/qms/insp-plans", "{\"inspType\":\"FINAL\",\"itemCd\":\"QI-FG\"}"));
        mvc.perform(json(put("/api/qms/insp-plans/" + plan + "/lines?rowVer=" + planVer(plan)), "{\"lines\":[{\"rowKey\":\"a\",\"lineNo\":10,\"charId\":"
                + ch + ",\"samplingPlanCd\":\"S5\",\"gaugeClass\":\"CALIPER\",\"methodNm\":\"측정\",\"reactionPlan\":\"격리\"}]}").with(admin))
                .andExpect(status().isOk());
        postJson("/api/qms/insp-plans/" + plan + "/release", "{\"rowVer\":" + planVer(plan) + "}").andExpect(status().isOk());
        gauge = accounts.query(comp, """
                insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, gauge_class, calib_cycle_days, created_by, updated_by)
                values (?, 'QI-G', 'GAUGE', 'QA 캘리퍼', 'CALIPER', 365, 't', 't') returning asset_id""", Long.class, comp);
    }

    int planVer(long plan) {
        return accounts.query(comp, "select row_ver from qms_insp_plan where insp_plan_id = ?", Integer.class, plan);
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(admin));
    }

    static long data(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data")).longValue();
    }

    void calib(LocalDate on) throws Exception {
        postJson("/api/qms/gauges/" + gauge + "/calibs", "{\"calibDate\":\"" + on + "\",\"result\":\"OK\",\"agency\":\"INTERNAL\"}")
                .andExpect(status().isOk());
    }

    /** 측정 없는 처리 중 검사 → 검사 번호 */
    String newInsp() throws Exception {
        String lot = "L-QI-" + (++lotSeq);
        accounts.exec(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', 50, 'EA', now(), 't', 't' from mst_item where item_cd = 'QI-FG'""", comp, lot);
        long id = data(postJson(I, "{\"lotNo\":\"" + lot + "\",\"inspType\":\"FINAL\"}"));
        return accounts.query(comp, "select insp_no from qms_insp where insp_id = ?", String.class, id);
    }

    long inspId(String no) {
        return accounts.query(comp, "select insp_id from qms_insp where insp_no = ?", Long.class, no);
    }

    long lineId(long insp) {
        return accounts.query(comp, "select insp_line_id from qms_insp_line where insp_id = ? and line_no = 10", Long.class, insp);
    }

    /** 검사 시작 시각을 옮긴다(시험 준비 — 검사 머리 고정 가드를 replica 로 우회) */
    void startedAt(String no, String tsWithZone) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = c.createStatement()) {
            st.execute("set session_replication_role = replica");
            st.executeUpdate("update qms_insp set occurred_at = '" + tsWithZone + "'::timestamptz where insp_no = '" + no + "' and comp_cd = '" + comp + "'");
        }
    }

    byte[] fill(List<Object[]> rows) throws Exception {
        byte[] tpl = mvc.perform(post(IMP + "/templates/" + TPL + "/file").with(csrf()).with(admin))
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

    ResultActions upload(List<Object[]> rows) throws Exception {
        return mvc.perform(multipart(IMP + "/templates/" + TPL + "/jobs").file(new MockMultipartFile("file", "meas.xlsx", XLSX, fill(rows)))
                .param("mode", "INSERT_ONLY").with(csrf()).with(admin));
    }

    long job(List<Object[]> rows) throws Exception {
        MvcResult m = upload(rows).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.jobId")).longValue();
    }

    MvcResult execute(long job) throws Exception {
        int ver = accounts.query(comp, "select row_ver from sys_import_job where job_id = ?", Integer.class, job);
        return mvc.perform(post(IMP + "/jobs/" + job + "/execute").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowVer\":" + ver + ",\"skipErrors\":true}")).andReturn();
    }

    /** 오류 행 "열:코드"(행 번호 순) */
    String errors(long job) throws Exception {
        String body = mvc.perform(get(IMP + "/jobs/" + job + "/rows").param("status", "ERROR").param("size", "100").with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Object> f = JsonPath.read(body, "$.list[*].errors[0].field");
        List<Object> c = JsonPath.read(body, "$.list[*].errors[0].code");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < c.size(); i++) {
            out.add(f.get(i) + ":" + c.get(i));
        }
        return String.join(",", out);
    }

    /** 살아 있는 측정값 "샘플:입력방법" (샘플 순) */
    String live(long insp) {
        return accounts.query(comp, """
                select coalesce(string_agg(m.sample_no || ':' || m.entry_method, ',' order by m.sample_no, m.meas_id), '') from qms_insp_meas m
                  join qms_insp_line x on x.insp_line_id = m.insp_line_id
                 where x.insp_id = ? and m.void_of_id is null and not exists (select 1 from qms_insp_meas v where v.void_of_id = m.meas_id)""",
                String.class, insp);
    }

    int lockWaiters() {
        return jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()",
                Integer.class);
    }

    // ------------------------------------------------------------------ 시험

    /**
     * 화면 입력과 엑셀 실행이 같은 샘플을 동시에 — 시험이 검사 머리를 먼저 잠그고 두 요청이 모두 기다리는 것을 관찰한 뒤 놓는다.
     * 어느 쪽이 이기든 샘플 1 의 살아 있는 값은 하나, 엑셀이 지면 파일의 다른 샘플도 하나도 안 들어간다(전체 롤백, 작업은 다시 VALIDATED)
     */
    @Test
    void 화면과_엑셀이_같은_샘플을_동시에_넣어도_값은_하나_엑셀이_지면_전체_롤백() throws Exception {
        calib(LocalDate.now(KST).minusDays(5));
        String no = newInsp();
        long id = inspId(no);
        long j = job(List.of(new Object[] {no, 10, 1, "10.01", null, "QI-G", null}, new Object[] {no, 10, 2, "10.02", null, "QI-G", null}));
        assertThat(errors(j)).isEmpty();
        try (Connection hold = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = hold.createStatement()) {
            hold.setAutoCommit(false);
            st.execute("select 1 from qms_insp where insp_id = " + id + " for update");
            int before = lockWaiters();
            Future<Integer> screen = POOL.submit(() -> postJson(I + "/" + id + "/meas", "{\"inspLineId\":" + lineId(id)
                    + ",\"entries\":[{\"sampleNo\":1,\"value\":10.00,\"gaugeAssetId\":" + gauge + "}]}").andReturn().getResponse().getStatus());
            Future<MvcResult> excel = POOL.submit(() -> execute(j));
            for (int i = 0; i < 200 && lockWaiters() - before < 2; i++) {
                Thread.sleep(50);
            }
            assertThat(lockWaiters() - before).as("두 요청이 모두 검사 머리를 기다린다(모두 관찰한 뒤 놓음)").isGreaterThanOrEqualTo(2);
            hold.commit();
            int s = screen.get(30, TimeUnit.SECONDS);
            MvcResult e = excel.get(30, TimeUnit.SECONDS);
            int es = e.getResponse().getStatus();
            System.out.println("[QI-동시] screen=" + s + " excel=" + es + " " + e.getResponse().getContentAsString() + " live=" + live(id));
            assertThat(s == 200 ^ es == 200).as("하나만 성공").isTrue();
            if (s == 200) {
                assertThat(es).isEqualTo(409);
                assertThat((String) JsonPath.read(e.getResponse().getContentAsString(), "$.code")).isEqualTo("QMS_INSP_IMPORT_STALE");
                assertThat(live(id)).as("엑셀 몫(샘플 2)도 없음").isEqualTo("1:KEY");
                assertThat(accounts.query(comp, "select status from sys_import_job where job_id = ?", String.class, j)).isEqualTo("VALIDATED");
            } else {
                assertThat(s).isEqualTo(400);
                assertThat(live(id)).isEqualTo("1:IMPORT,2:IMPORT");
            }
        }
    }

    /** IMPORT_MAX_ROWS(하한 100) — 정확히 100 행은 받고 101 행은 거부, 원본·작업이 남지 않는다 */
    @Test
    void 최대_행수_경계_100은_받고_101은_거부() throws Exception {
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'IMPORT_MAX_ROWS', '100', 't', 't')",
                comp);
        String no = newInsp();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            rows.add(new Object[] {no, 10, i + 1, "10.00", null, "QI-G", null});
        }
        long jobs = accounts.query(comp, "select count(*) from sys_import_job", Long.class);
        MvcResult over = upload(rows).andReturn();
        System.out.println("[QI-최대] 101=" + over.getResponse().getStatus() + " " + over.getResponse().getContentAsString());
        assertThat(over.getResponse().getStatus()).isEqualTo(400);
        assertThat((String) JsonPath.read(over.getResponse().getContentAsString(), "$.code")).isEqualTo("IMPORT_TOO_MANY");
        assertThat(accounts.query(comp, "select count(*) from sys_import_job", Long.class)).isEqualTo(jobs);
        long j = job(rows.subList(0, 100));
        mvc.perform(get(IMP + "/jobs/" + j).with(admin)).andExpect(status().isOk())
                .andExpect(r -> assertThat((Integer) JsonPath.read(r.getResponse().getContentAsString(), "$.data.total")).isEqualTo(100));
    }

    /**
     * 회사 시간대(Asia/Seoul) 자정 — 검사 시작이 오늘 00:00:30(KST)이면 "오늘 00:00"·"오늘 00:00:10"(같은 분, 시작 전)·"어제 23:59" 는 BEFORE_START
     * (I-2 결정 2026-10-27: 초 단위 비교 — 관찰 때는 분 안이면 받았음), "오늘 00:00:40"(시작 10초 뒤)은 받고 회사 시간대로 저장
     */
    @Test
    void 회사_시간대_자정과_검사_시작_직전() throws Exception {
        calib(LocalDate.now(KST).minusDays(5));
        String no = newInsp();
        long id = inspId(no);
        LocalDate d = LocalDate.now(KST);
        startedAt(no, d + " 00:00:30+09");
        String midnight = d.atStartOfDay().format(MIN);
        String yesterday = d.atStartOfDay().minusMinutes(1).format(MIN);
        long j = job(List.of(new Object[] {no, 10, 1, "10.00", null, "QI-G", midnight}, new Object[] {no, 10, 2, "10.00", null, "QI-G", yesterday},
                new Object[] {no, 10, 3, "10.00", null, "QI-G", d + " 00:00:10"}, new Object[] {no, 10, 4, "10.00", null, "QI-G", d + " 00:00:40"}));
        assertThat(errors(j)).isEqualTo("measuredAt:BEFORE_START,measuredAt:BEFORE_START,measuredAt:BEFORE_START");
        assertThat(execute(j).getResponse().getStatus()).isEqualTo(200);
        assertThat(live(id)).isEqualTo("4:IMPORT");
        assertThat(accounts.query(comp, """
                select string_agg(to_char(m.occurred_at at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS'), ',' order by m.sample_no) from qms_insp_meas m
                 where m.insp_line_id = ?""", String.class, lineId(id)))
                .as("KST 00:00:40 = 전날 15:00:40 UTC").isEqualTo(d.minusDays(1) + " 15:00:40");
        assertThat(accounts.query(comp, "select count(*) from qms_insp_meas m join qms_insp i on true where m.insp_line_id = ? and i.insp_id = ? and m.occurred_at < i.occurred_at",
                Long.class, lineId(id), id)).as("검사 시작보다 이른 측정값 없음(I-2)").isZero();
    }

    /**
     * I-1(결정 2026-10-27, 관찰 때는 "오늘" 기준이라 받았음): 엑셀의 지난 측정 시각에 계측기 상태를 **측정 시각** 기준으로 본다. 어제(측정 시각)에는
     * 교정 기한이 지난(OVERDUE) 계측기면 오늘 다시 교정해 OK 여도 어제 잰 값은 거부. 오늘(재교정 뒤) 잰 값은 받는다
     */
    @Test
    void 지난_측정_시각의_계측기_상태는_측정_시각_기준() throws Exception {
        LocalDate d = LocalDate.now(KST);
        calib(d.minusDays(400));     // 다음 기한 = 35일 전 → 어제는 OVERDUE
        String no = newInsp();
        long id = inspId(no);
        startedAt(no, d.minusDays(3) + " 08:00:00+09");
        String yesterday = d.minusDays(1).atTime(10, 0).format(MIN);
        long blocked = job(List.<Object[]>of(new Object[] {no, 10, 1, "10.00", null, "QI-G", yesterday}));
        assertThat(errors(blocked)).as("재교정 전: 오늘도 OVERDUE 라 거부").isEqualTo("gaugeCd:GAUGE_BLOCKED");
        calib(d);                    // 오늘 재교정 OK
        long j = job(List.<Object[]>of(new Object[] {no, 10, 1, "10.00", null, "QI-G", yesterday}, new Object[] {no, 10, 2, "10.00", null, "QI-G", null}));
        assertThat(errors(j)).as("어제(OVERDUE 때) 잰 값은 재교정 뒤에도 거부").isEqualTo("gaugeCd:GAUGE_BLOCKED");
        assertThat(execute(j).getResponse().getStatus()).isEqualTo(200);
        assertThat(live(id)).as("오늘(비움 = 반영 시각) 잰 값만").isEqualTo("2:IMPORT");
        LocalDateTime at = LocalDateTime.parse(yesterday, MIN);
        assertThat(accounts.query(comp, "select count(*) from qms_insp_meas where insp_line_id = ? and occurred_at = ?::timestamptz", Long.class,
                lineId(id), at + "+09")).isZero();
    }
    long calibId(LocalDate on, String result) throws Exception {
        postJson("/api/qms/gauges/" + gauge + "/calibs", "{\"calibDate\":\"" + on + "\",\"result\":\"" + result + "\",\"agency\":\"INTERNAL\"}")
                .andExpect(status().isOk());
        return accounts.query(comp, "select max(calib_id) from qms_gauge_calib where asset_id = ?", Long.class, gauge);
    }

    /** NG 교정 영향 조회(impact)에 이 검사 LOT 이 나오는가 */
    boolean inImpact(long ngCalib, String inspNo) throws Exception {
        String body = mvc.perform(get("/api/qms/gauges/" + gauge + "/calibs/" + ngCalib + "/impact").with(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Object> nos = JsonPath.read(body, "$.data.rows[*].inspNo");
        return nos.contains(inspNo);
    }

    /**
     * 25.6 경계 ① 같은 날 NG 뒤 재교정(재현 R2, 2026-10-28): 관찰 때는 엑셀 판정이 재교정을 따라 받고 영향 조회(impact)엔 나와 경계가 어긋났다.
     * G-1 결정(2026-10-28 사용자, 안 가): 판정도 NG 날 하루 전체를 영향 구간으로 — 같은 날 재교정이 있어도 거부, 영향 조회와 같은 경계
     */
    @Test
    void 결정_G1_같은_날_NG_뒤_재교정이어도_NG_날_측정은_거부() throws Exception {
        LocalDate d = LocalDate.now(KST);
        calibId(d.minusDays(10), "OK");
        String no = newInsp();
        long id = inspId(no);
        startedAt(no, d.minusDays(2) + " 08:00:00+09");
        long ng = calibId(d.minusDays(1), "NG");
        calibId(d.minusDays(1), "OK");                 // 같은 날 NG 뒤 재교정
        String at = d.minusDays(1).atTime(15, 0).format(MIN);
        long j = job(List.<Object[]>of(new Object[] {no, 10, 1, "10.00", null, "QI-G", at}));
        assertThat(errors(j)).as("NG 날은 같은 날 재교정이 있어도 영향 구간").isEqualTo("gaugeCd:GAUGE_BLOCKED");
        assertThat(execute(j).getResponse().getStatus()).isEqualTo(200);
        assertThat(live(id)).as("들어간 값 없음").isEmpty();
        assertThat(inImpact(ng, no)).as("영향 조회에 나올 값도 없음 — 두 규칙 경계가 같다").isFalse();
        // 대조: 전날(NG 날 이전, 영향 구간 안) 측정은 거부
        String before = d.minusDays(2).atTime(10, 0).format(MIN);
        assertThat(errors(job(List.<Object[]>of(new Object[] {no, 10, 2, "10.00", null, "QI-G", before})))).isEqualTo("gaugeCd:GAUGE_BLOCKED");
    }

    /** 25.6 경계 ② 회사 시간대 자정 — NG(D-1) 다음 날(D) 재교정이면 D-1 23:59 는 영향 구간(거부), D 00:01 은 재교정 뒤(받음) */
    @Test
    void NG_영향_구간의_자정_경계는_회사_시간대_날짜() throws Exception {
        LocalDate d = LocalDate.now(KST);
        calibId(d.minusDays(10), "OK");
        String no = newInsp();
        startedAt(no, d.minusDays(2) + " 08:00:00+09");
        calibId(d.minusDays(1), "NG");
        calibId(d, "OK");
        long j = job(List.<Object[]>of(new Object[] {no, 10, 1, "10.00", null, "QI-G", d.minusDays(1).atTime(23, 59).format(MIN)},
                new Object[] {no, 10, 2, "10.00", null, "QI-G", d.atTime(0, 1).format(MIN)}));
        assertThat(errors(j)).isEqualTo("gaugeCd:GAUGE_BLOCKED");
        String body = mvc.perform(get(IMP + "/jobs/" + j + "/rows").param("status", "ERROR").with(admin)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(body, "$.list[*].key")).as("거부된 것은 23:59 행(샘플 1)").containsExactly(no + "|10|1");
    }
}
