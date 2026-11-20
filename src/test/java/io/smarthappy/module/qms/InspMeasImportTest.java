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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

/**
 * 검사 실행 측정값 엑셀 올리기 (docs/29_P3 25장) — 화면 입력과 같은 검증·실행 경로, 원장 칸(entry_method·import_job_id·occurred_at),
 * 판정·SPC 자동 보류가 화면 입력과 같음, 행 오류 섞임(오류 제외 실행), 멱등, 상태(판정 뒤·검증 뒤 바뀜), 동시 실행, A사로 B사·권한·직접 SQL.
 */
class InspMeasImportTest extends QmsTestBase {

    static final String IMP = "/api/platform/import";
    static final String TPL = "QMS_INSP_MEAS";
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String S = "/api/qms/spc";
    static final String I = "/api/qms/insps";
    static final String G = "/api/qms/gauges";
    static final String[] BASE = {"9.98", "10.00", "10.02", "10.00", "10.00"};
    /** 평균 10.05 → WE1·WE2 (SpcEventTest 와 같은 값) */
    static final String[] HIGH = {"10.03", "10.05", "10.07", "10.05", "10.05"};
    static final DateTimeFormatter MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    static final DateTimeFormatter SEC = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    long gauge;
    long item;
    int lotSeq;

    @BeforeEach
    void importSetUp() throws Exception {
        accounts.grant(comp, userId, "QA", "qms-insp", "SEARCH", "SAVE", "DELETE");
        accounts.grant(comp, userId, "QA", "qms-gauge-calib", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "QA", "qms-spc", "SEARCH", "SAVE");
        gauge = gaugeAsset("GIMP", "CALIPER", "OK");
        long p = plan("FINAL", "FG1", null, null);
        lines(p, line("a", null, 10, fgChar1, "S5", "CALIPER"), line("b", null, 20, fgChar2, "S5", "VISUAL")).andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
        item = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG1'", Long.class);
    }

    long gaugeAsset(String cd, String cls, String result) throws Exception {
        accounts.exec(comp, """
                insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, gauge_class, calib_cycle_days, created_by, updated_by)
                values (?, ?, 'GAUGE', ?, ?, 365, 't', 't')""", comp, cd, cd, cls);
        long id = accounts.query(comp, "select asset_id from mst_phys_asset where asset_cd = ?", Long.class, cd);
        postJson(G + "/" + id + "/calibs", "{\"calibDate\":" + q(today.minusDays(10)) + ",\"result\":" + q(result) + ",\"agency\":\"INTERNAL\"}").andExpect(status().isOk());
        return id;
    }

    /** 검사 시작 시각을 지난 시각으로(시험 준비 — 검사 키는 가드가 막으므로 소유자 연결에서 트리거를 끄고) */
    void startAt(long insp, String expr) throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword());
                java.sql.Statement st = c.createStatement()) {
            st.execute("set session_replication_role = replica");
            st.execute("update qms_insp set occurred_at = " + expr + " where insp_id = " + insp);
        }
    }

    /** 측정 없는 처리 중 검사 → 검사 번호 */
    String newInsp() throws Exception {
        String no = "L-IMP-" + (++lotSeq);
        accounts.exec(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', 50, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG1'""", comp, no);
        long id = id(postJson(I, "{\"lotNo\":" + q(no) + ",\"inspType\":\"FINAL\"}"));
        return inspNo(id);
    }

    String inspNo(long id) {
        return accounts.query(comp, "select insp_no from qms_insp where insp_id = ?", String.class, id);
    }

    long inspId(String no) {
        return accounts.query(comp, "select insp_id from qms_insp where insp_no = ?", Long.class, no);
    }

    /** 화면 입력으로 계량 5 + 계수 OK 5 → 검사 id */
    long screenInsp(String... values) throws Exception {
        long id = inspId(newInsp());
        StringBuilder v = new StringBuilder();
        StringBuilder a = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            v.append(i == 0 ? "" : ",").append("{\"sampleNo\":").append(i + 1).append(",\"value\":").append(values[i])
                    .append(",\"gaugeAssetId\":").append(gauge).append("}");
            a.append(i == 0 ? "" : ",").append("{\"sampleNo\":").append(i + 1).append(",\"okYn\":\"Y\"}");
        }
        postJson(I + "/" + id + "/meas", "{\"inspLineId\":" + line(id, 10) + ",\"entries\":[" + v + "]}").andExpect(status().isOk());
        postJson(I + "/" + id + "/meas", "{\"inspLineId\":" + line(id, 20) + ",\"entries\":[" + a + "]}").andExpect(status().isOk());
        return id;
    }

    /** 같은 값을 파일 행으로(계량 5 + 계수 5) */
    List<Object[]> fileRows(String no, String... values) {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            rows.add(new Object[] {no, 10, i + 1, values[i], null, "GIMP", null});
        }
        for (int i = 0; i < values.length; i++) {
            rows.add(new Object[] {no, 20, i + 1, null, "Y", null, null});
        }
        return rows;
    }

    long line(long insp, int no) {
        return accounts.query(comp, "select insp_line_id from qms_insp_line where insp_id = ? and line_no = ?", Long.class, insp, no);
    }

    ResultActions judge(long insp) throws Exception {
        return postJson(I + "/" + insp + "/judge", "{\"rowVer\":" + accounts.query(comp, "select row_ver from qms_insp where insp_id = ?",
                Integer.class, insp) + "}");
    }

    byte[] fill(RequestPostProcessor who, List<Object[]> rows) throws Exception {
        byte[] tpl = mvc.perform(post(IMP + "/templates/" + TPL + "/file").with(csrf()).with(who))
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

    ResultActions upload(RequestPostProcessor who, byte[] file, String mode) throws Exception {
        return mvc.perform(multipart(IMP + "/templates/" + TPL + "/jobs").file(new MockMultipartFile("file", "meas.xlsx", XLSX, file))
                .param("mode", mode).with(csrf()).with(who));
    }

    long job(RequestPostProcessor who, List<Object[]> rows) throws Exception {
        return jobId(upload(who, fill(who, rows), "INSERT_ONLY"));
    }

    static long jobId(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.jobId")).longValue();
    }

    ResultActions execute(String c, RequestPostProcessor who, long job) throws Exception {
        int ver = accounts.query(c, "select row_ver from sys_import_job where job_id = ?", Integer.class, job);
        return mvc.perform(post(IMP + "/jobs/" + job + "/execute").with(csrf()).with(who)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rowVer\":" + ver + ",\"skipErrors\":true}"));
    }

    /** 오류 행: "열:코드" 목록(행 번호 순) */
    String errors(RequestPostProcessor who, long job) throws Exception {
        String body = mvc.perform(get(IMP + "/jobs/" + job + "/rows").param("status", "ERROR").param("size", "100").with(who))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Object> fields = JsonPath.read(body, "$.list[*].errors[0].field");
        List<Object> codes = JsonPath.read(body, "$.list[*].errors[0].code");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) {
            out.add(fields.get(i) + ":" + codes.get(i));
        }
        return String.join(",", out);
    }

    long liveMeas(long insp) {
        return accounts.query(comp, """
                select count(*) from qms_insp_meas m join qms_insp_line x on x.insp_line_id = m.insp_line_id
                 where x.insp_id = ? and m.void_of_id is null and not exists (select 1 from qms_insp_meas v where v.void_of_id = m.meas_id)""",
                Long.class, insp);
    }

    String spcHold(long insp) {
        return accounts.query(comp, """
                select l.hold_yn || '/' || (select count(*) from lot_hold h where h.lot_id = l.lot_id and h.source = 'QMS_SPC' and h.released_at is null)
                  from lot_master l join qms_insp i on i.lot_id = l.lot_id where i.insp_id = ?""", String.class, insp);
    }

    String rules(long insp) {
        return accounts.query(comp, "select coalesce(string_agg(rule_cd, ',' order by rule_cd), '') from qms_spc_event where insp_id = ?",
                String.class, insp);
    }

    String result(long insp) {
        return accounts.query(comp, "select result from qms_insp where insp_id = ?", String.class, insp);
    }

    @Test
    void 화면과_같은_값이면_판정_SPC_자동보류도_같다_한_파일_여러_검사_원장_칸() throws Exception {
        for (int i = 0; i < 20; i++) {
            judge(screenInsp(BASE)).andExpect(status().isOk());
        }
        id(postJson(S + "/limits", "{\"itemId\":" + item + ",\"charId\":" + fgChar1 + ",\"inspType\":\"FINAL\"}"));
        policy("QMS_SPC_AUTO_HOLD", "LIMIT");
        // 화면 입력 검사(기준)
        long screen = screenInsp(HIGH);
        judge(screen).andExpect(status().isOk());
        // 엑셀: 위반 검사 + 정상 검사를 한 파일에, 한 샘플은 측정 시각을 적음
        String high = newInsp();
        String base = newInsp();
        // 초까지(QA I-2 — 시작 비교가 초 단위라 "지금, 분까지" 는 같은 분에 만든 검사의 시작보다 이를 수 있다). 다음 초로 넘긴 뒤 적어
        // 화면 검사 측정(같은 초일 수 있음)보다 확실히 뒤 — 부분군 순서(min occurred_at)를 고정
        Thread.sleep(1100);
        String at = LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(SEC);
        List<Object[]> rows = new ArrayList<>(fileRows(high, HIGH));
        rows.get(0)[6] = at;
        rows.addAll(fileRows(base, BASE));
        long j = job(admin, rows);
        mvc.perform(get(IMP + "/jobs/" + j).with(admin)).andExpect(jsonPath("$.data.newCount").value(20))
                .andExpect(jsonPath("$.data.errorCount").value(0));
        execute(comp, admin, j).andExpect(status().isOk()).andExpect(jsonPath("$.data.result.inserted").value(20))
                .andExpect(jsonPath("$.data.result.matched").value(true));
        long hi = inspId(high);
        assertThat(liveMeas(hi)).isEqualTo(10);
        assertThat(accounts.query(comp, """
                select count(*) from qms_insp_meas m join qms_insp_line x on x.insp_line_id = m.insp_line_id
                 where x.insp_id in (?, ?) and m.entry_method = 'IMPORT' and m.import_job_id = ?""", Long.class, hi, inspId(base), j))
                .isEqualTo(20L);
        assertThat(accounts.query(comp, """
                select to_char(m.occurred_at at time zone 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS') from qms_insp_meas m
                  join qms_insp_line x on x.insp_line_id = m.insp_line_id where x.insp_id = ? and x.line_no = 10 and m.sample_no = 1""",
                String.class, hi)).isEqualTo(at);
        // 화면 입력은 그대로 KEY
        assertThat(accounts.query(comp, """
                select string_agg(distinct m.entry_method, ',') from qms_insp_meas m join qms_insp_line x on x.insp_line_id = m.insp_line_id
                 where x.insp_id = ?""", String.class, screen)).isEqualTo("KEY");
        // 상세 응답에도 입력 방법
        mvc.perform(get(I + "/" + hi).with(admin)).andExpect(jsonPath("$.data.meas[0].entryMethod").value("IMPORT"));
        // 판정은 사람이 — 올리기만으로는 판정·위반·보류 없음
        assertThat(result(hi)).isNull();
        assertThat(rules(hi)).isEmpty();
        judge(hi).andExpect(status().isOk());
        assertThat(result(hi)).isEqualTo(result(screen));
        // 부분군은 측정 시각(min occurred_at) 순 — 엑셀 검사는 화면 검사(같은 높은 값) 뒤라 연속 두 점째로 WE2(3 중 2)도 붙는다.
        // (I-2 전엔 적은 시각을 분으로 내려 엑셀 검사가 화면 검사보다 앞에 놓여 WE1 만이었다 — 순서 착시)
        assertThat(rules(screen)).isEqualTo("WE1");
        assertThat(rules(hi)).isEqualTo("WE1,WE2");
        assertThat(spcHold(hi)).isEqualTo(spcHold(screen)).isEqualTo("Y/1");
        long ba = inspId(base);
        judge(ba).andExpect(status().isOk());
        assertThat(rules(ba)).isEmpty();
        assertThat(spcHold(ba)).isEqualTo("N/0");
    }

    @Test
    void 행_오류_섞여도_오류_제외_실행_같은_파일_다시_올리면_들어가는_것_없음() throws Exception {
        String no = newInsp();
        long id = inspId(no);
        gaugeAsset("GMIC", "MICROMETER", "OK");
        gaugeAsset("GNG", "CALIPER", "NG");
        String future = LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusDays(1).format(MIN);
        List<Object[]> rows = List.of(
                new Object[] {no, 10, 1, "10.01", null, "GIMP", null},            // 정상
                new Object[] {no, 20, 1, null, "Y", null, null},                   // 정상(육안)
                new Object[] {no, 10, 9, "10.01", null, "GIMP", null},            // 샘플 범위 밖
                new Object[] {no, 10, 2, null, "Y", "GIMP", null},                 // 계량 행에 값 없음
                new Object[] {no, 20, 2, null, "X", null, null},                   // 계수 Y/N 아님
                new Object[] {no, 10, 3, "10.01", null, "GMIC", null},            // 계측기 클래스 다름
                new Object[] {no, 10, 4, "10.01", null, "GNG", null},             // 교정 NG 계측기
                new Object[] {no, 10, 5, "10.01", null, "GIMP", future},          // 미래 측정 시각
                new Object[] {no, 30, 1, "10.01", null, "GIMP", null},            // 없는 행 번호
                new Object[] {"IN0000-99999", 10, 1, "10.01", null, "GIMP", null},// 없는 검사
                new Object[] {no, 20, 3, null, "Y", "NOPE", null},                 // 없는 계측기 코드
                new Object[] {no, 10, 1, "10.02", null, "GIMP", null});           // 파일 안 같은 키
        long j = job(admin, rows);
        assertThat(errors(admin, j)).isEqualTo(String.join(",", "inspNo:FILE_DUPLICATE", "sampleNo:RANGE", "value:REQUIRED", "okYn:CODE",
                "gaugeCd:GAUGE_CLASS", "gaugeCd:GAUGE_BLOCKED", "measuredAt:FUTURE", "lineNo:NOT_FOUND", "inspNo:NOT_FOUND", "gaugeCd:NOT_FOUND",
                "inspNo:FILE_DUPLICATE"));
        execute(comp, admin, j).andExpect(status().isOk()).andExpect(jsonPath("$.data.result.inserted").value(1))
                .andExpect(jsonPath("$.data.result.matched").value(true));
        assertThat(liveMeas(id)).isEqualTo(1);
        // 화면에서도 같은 코드(규칙 한 벌)
        postJson(I + "/" + id + "/meas", "{\"inspLineId\":" + line(id, 10) + ",\"entries\":[{\"sampleNo\":3,\"value\":10.01,\"gaugeAssetId\":"
                + accounts.query(comp, "select asset_id from mst_phys_asset where asset_cd = 'GMIC'", Long.class) + "}]}")
                .andExpect(jsonPath("$.errors[0].code").value("GAUGE_CLASS"));
        // 멱등: 정상 두 행만 담은 파일을 두 번 — 둘째는 전부 이미 값이 있는 샘플(화면과 같은 SAMPLE_TAKEN), 들어가는 것 없음
        List<Object[]> ok = List.of(rows.get(0), rows.get(1));
        long j2 = job(admin, ok);
        assertThat(errors(admin, j2)).isEqualTo("sampleNo:SAMPLE_TAKEN");
        execute(comp, admin, j2).andExpect(jsonPath("$.data.result.inserted").value(1));
        long j3 = job(admin, ok);
        assertThat(errors(admin, j3)).isEqualTo("sampleNo:SAMPLE_TAKEN,sampleNo:SAMPLE_TAKEN");
        execute(comp, admin, j3).andExpect(jsonPath("$.data.result.inserted").value(0));
        // UPSERT 로 올려도 갱신 없음(템플릿이 기존 값을 오류로)
        long j4 = jobId(upload(admin, fill(admin, ok), "UPSERT"));
        mvc.perform(get(IMP + "/jobs/" + j4).with(admin)).andExpect(jsonPath("$.data.changeCount").value(0))
                .andExpect(jsonPath("$.data.errorCount").value(2));
        assertThat(liveMeas(id)).isEqualTo(2);
    }

    /**
     * QA I-1(2026-10-27 사용자 결정): 지난 측정 시각의 계측기 상태는 그 시각 기준 — 그때 기한 초과였던 계측기는 오늘 재교정했어도 거부, 지금 시각(비움)은 통과.
     * 화면 입력은 지금 시각이라 지금 상태(재교정 뒤 OK)로 통과 — 같은 checkEntry
     */
    @Test
    void 지난_측정은_그_시각의_교정_상태로_판정_그때_기한초과면_오늘_재교정해도_거부() throws Exception {
        accounts.exec(comp, """
                insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, gauge_class, calib_cycle_days, created_by, updated_by)
                values (?, 'GREC', 'GAUGE', 'GREC', 'CALIPER', 365, 't', 't')""", comp);
        long rec = accounts.query(comp, "select asset_id from mst_phys_asset where asset_cd = 'GREC'", Long.class);
        // 370일 전 교정(다음 교정일 = 5일 전) → 오늘 재교정
        postJson(G + "/" + rec + "/calibs", "{\"calibDate\":" + q(today.minusDays(370)) + ",\"result\":\"OK\",\"agency\":\"INTERNAL\"}").andExpect(status().isOk());
        postJson(G + "/" + rec + "/calibs", "{\"calibDate\":" + q(today) + ",\"result\":\"OK\",\"agency\":\"INTERNAL\"}").andExpect(status().isOk());
        String no = newInsp();
        long id = inspId(no);
        startAt(id, "now() - interval '3 days'");
        String past = LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusDays(2).format(MIN);
        long j = job(admin, List.of(new Object[] {no, 10, 1, "10.01", null, "GREC", past}, new Object[] {no, 10, 2, "10.01", null, "GREC", null}));
        assertThat(errors(admin, j)).isEqualTo("gaugeCd:GAUGE_BLOCKED");
        execute(comp, admin, j).andExpect(jsonPath("$.data.result.inserted").value(1));
        assertThat(accounts.query(comp, "select string_agg(m.sample_no::text, ',') from qms_insp_meas m join qms_insp_line x on x.insp_line_id = m.insp_line_id where x.insp_id = ?", String.class, id)).isEqualTo("2");
        postJson(I + "/" + id + "/meas", "{\"inspLineId\":" + line(id, 10) + ",\"entries\":[{\"sampleNo\":3,\"value\":10.01,\"gaugeAssetId\":" + rec + "}]}")
                .andExpect(status().isOk());
    }

    /**
     * 25.6-Q(2026-10-27 사용자 결정): 지난 측정 시각이 뒤에 들어온 NG 교정의 영향 구간(직전 유효 교정일 ~ NG 교정일, 영향 LOT 조회와 같은 경계)에 들면
     * 그 시각엔 OK 였어도 올리는 순간 GAUGE_BLOCKED. 구간 앞·NG 다음 날 이후 재교정 뒤는 통과, 화면 입력(지금 = 재교정 뒤)은 영향 없음.
     * 같은 날 NG → 재교정이면 그날은 화면 입력도 거부(QA G-1 결정 — NG 날 하루 전체)
     */
    @Test
    void NG_교정_영향_구간의_지난_측정은_거부_구간_밖과_재교정_뒤는_통과() throws Exception {
        accounts.exec(comp, """
                insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, gauge_class, calib_cycle_days, created_by, updated_by)
                values (?, 'GNGW', 'GAUGE', 'GNGW', 'CALIPER', 365, 't', 't')""", comp);
        long g = accounts.query(comp, "select asset_id from mst_phys_asset where asset_cd = 'GNGW'", Long.class);
        for (Object[] c : new Object[][] {{100, "OK"}, {30, "OK"}, {5, "NG"}, {2, "OK"}}) {
            postJson(G + "/" + g + "/calibs", "{\"calibDate\":" + q(today.minusDays((Integer) c[0])) + ",\"result\":" + q((String) c[1])
                    + ",\"agency\":\"INTERNAL\"}").andExpect(status().isOk());
        }
        String no = newInsp();
        long id = inspId(no);
        startAt(id, "now() - interval '40 days'");
        java.util.function.IntFunction<String> daysAgo = d -> LocalDateTime.now(ZoneId.of("Asia/Seoul")).toLocalDate().minusDays(d).atTime(10, 0).format(MIN);
        long j = job(admin, List.of(
                new Object[] {no, 10, 1, "10.01", null, "GNGW", daysAgo.apply(35)},    // 영향 구간 앞(직전 유효 교정 30일 전보다 앞) — 통과
                new Object[] {no, 10, 2, "10.01", null, "GNGW", daysAgo.apply(10)},    // 30일 전 OK ~ 5일 전 NG 사이 — 거부
                new Object[] {no, 10, 3, "10.01", null, "GNGW", daysAgo.apply(1)}));   // 2일 전 재교정 뒤 — 통과
        assertThat(errors(admin, j)).isEqualTo("gaugeCd:GAUGE_BLOCKED");
        execute(comp, admin, j).andExpect(jsonPath("$.data.result.inserted").value(2));
        assertThat(accounts.query(comp, "select string_agg(m.sample_no::text, ',' order by m.sample_no) from qms_insp_meas m "
                + "join qms_insp_line x on x.insp_line_id = m.insp_line_id where x.insp_id = ?", String.class, id)).isEqualTo("1,3");
        postJson(I + "/" + id + "/meas", "{\"inspLineId\":" + line(id, 10) + ",\"entries\":[{\"sampleNo\":4,\"value\":10.01,\"gaugeAssetId\":" + g + "}]}")
                .andExpect(status().isOk());
        // 같은 날 NG 뒤 재교정(교정일만 있어 시각 모름) — NG 날 하루 전체가 영향 구간(QA G-1 결정): 오늘 화면 입력도 거부
        accounts.exec(comp, """
                insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, gauge_class, calib_cycle_days, created_by, updated_by)
                values (?, 'GSAME', 'GAUGE', 'GSAME', 'CALIPER', 365, 't', 't')""", comp);
        long same = accounts.query(comp, "select asset_id from mst_phys_asset where asset_cd = 'GSAME'", Long.class);
        for (Object[] c : new Object[][] {{10, "OK"}, {0, "NG"}, {0, "OK"}}) {
            postJson(G + "/" + same + "/calibs", "{\"calibDate\":" + q(today.minusDays((Integer) c[0])) + ",\"result\":" + q((String) c[1])
                    + ",\"agency\":\"INTERNAL\"}").andExpect(status().isOk());
        }
        postJson(I + "/" + id + "/meas", "{\"inspLineId\":" + line(id, 10) + ",\"entries\":[{\"sampleNo\":5,\"value\":10.01,\"gaugeAssetId\":" + same + "}]}")
                .andExpect(jsonPath("$.errors[0].code").value("GAUGE_BLOCKED"));
    }

    /** QA I-2(2026-10-27 사용자 결정): 검사 시작 전 비교는 초 단위 — 같은 분 안이라도 시작 30초 전은 거부, 시작 10초 뒤는 통과 */
    @Test
    void 측정_시각은_초_단위로_검사_시작_전이면_같은_분이라도_거부() throws Exception {
        String no = newInsp();
        long id = inspId(no);
        startAt(id, "date_trunc('minute', now()) - interval '10 minutes' + interval '40 seconds'");
        String before = accounts.query(comp, "select to_char((occurred_at - interval '30 seconds') at time zone 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS') from qms_insp where insp_id = ?",
                String.class, id);
        String after = accounts.query(comp, "select to_char((occurred_at + interval '10 seconds') at time zone 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS') from qms_insp where insp_id = ?",
                String.class, id);
        assertThat(before.substring(0, 16)).as("같은 분").isEqualTo(after.substring(0, 16));
        long j = job(admin, List.of(new Object[] {no, 10, 1, "10.01", null, "GIMP", before}, new Object[] {no, 10, 2, "10.01", null, "GIMP", after}));
        assertThat(errors(admin, j)).isEqualTo("measuredAt:BEFORE_START");
        execute(comp, admin, j).andExpect(jsonPath("$.data.result.inserted").value(1));
        assertThat(accounts.query(comp, "select to_char(m.occurred_at at time zone 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS') from qms_insp_meas m "
                + "join qms_insp_line x on x.insp_line_id = m.insp_line_id where x.insp_id = ?",
                String.class, id)).isEqualTo(after);
    }

    @Test
    void 판정_뒤_검사는_오류_검증_뒤에_바뀌면_전체_롤백_409() throws Exception {
        long done = screenInsp(BASE);
        judge(done).andExpect(status().isOk());
        assertThat(errors(admin, job(admin, List.<Object[]>of(new Object[] {inspNo(done), 10, 1, "10.01", null, "GIMP", null}))))
                .isEqualTo("inspNo:STATE");
        // 미리보기 통과 → 다른 탭에서 같은 샘플 입력 → 실행 409, 다른 검사 몫도 하나도 안 들어감
        String a = newInsp();
        String b = newInsp();
        long j = job(admin, List.of(new Object[] {a, 10, 1, "10.01", null, "GIMP", null}, new Object[] {b, 10, 1, "10.01", null, "GIMP", null}));
        long bid = inspId(b);
        postJson(I + "/" + bid + "/meas", "{\"inspLineId\":" + line(bid, 10) + ",\"entries\":[{\"sampleNo\":1,\"value\":10.00,\"gaugeAssetId\":"
                + gauge + "}]}").andExpect(status().isOk());
        execute(comp, admin, j).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_INSP_IMPORT_STALE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(b)));
        assertThat(liveMeas(inspId(a))).isZero();
        assertThat(liveMeas(bid)).isEqualTo(1);
        // 미리보기 뒤 판정 → 409
        String c = newInsp();
        long cid = inspId(c);
        List<Object[]> rows = new ArrayList<>(fileRows(c, BASE));
        long j2 = job(admin, rows.subList(0, 1));
        postJson(I + "/" + cid + "/void", "{\"rowVer\":" + accounts.query(comp, "select row_ver from qms_insp where insp_id = ?", Integer.class, cid)
                + ",\"reason\":\"잘못 만듦\"}").andExpect(status().isOk());
        execute(comp, admin, j2).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_INSP_IMPORT_STALE"));
        assertThat(liveMeas(cid)).isZero();
    }

    @Test
    void D_검사_둘을_반대_순서로_담은_파일_둘_동시_실행도_교착_없음() throws Exception {
        String x = newInsp();
        String y = newInsp();
        long j1 = job(admin, List.of(new Object[] {y, 10, 1, "10.01", null, "GIMP", null}, new Object[] {x, 10, 1, "10.01", null, "GIMP", null}));
        long j2 = job(admin, List.of(new Object[] {x, 10, 2, "10.02", null, "GIMP", null}, new Object[] {y, 10, 2, "10.02", null, "GIMP", null}));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> f1 = pool.submit(() -> execute(comp, admin, j1).andReturn().getResponse().getStatus());
            Future<Integer> f2 = pool.submit(() -> execute(comp, admin, j2).andReturn().getResponse().getStatus());
            assertThat(f1.get(60, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(f2.get(60, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(liveMeas(inspId(x))).isEqualTo(2);
        assertThat(liveMeas(inspId(y))).isEqualTo(2);
    }

    @Test
    void 권한_A사로_B사_직접SQL() throws Exception {
        String no = newInsp();
        long id = inspId(no);
        List<Object[]> row = List.<Object[]>of(new Object[] {no, 10, 1, "10.01", null, "GIMP", null});
        // 조회만: 템플릿은 받지만 올리기 403
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "QV", "qms-insp", "SEARCH");
        RequestPostProcessor vw = accounts.as(comp, viewer, "viewer1");
        byte[] file = fill(vw, row);
        upload(vw, file, "INSERT_ONLY").andExpect(status().isForbidden());
        // A사로 B사: B사 사용자가 A사 검사 번호·A사 계측기 코드 → 찾을 수 없음, A사 측정값 그대로
        String other = accounts.newComp("QX");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-insp", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        long bj = jobId(upload(b, fill(b, row), "INSERT_ONLY"));
        assertThat(errors(b, bj)).isEqualTo("inspNo:NOT_FOUND");
        execute(other, b, bj).andExpect(jsonPath("$.data.result.inserted").value(0));
        assertThat(liveMeas(id)).isZero();
        // B사 작업은 A사 사용자가 못 봄
        mvc.perform(get(IMP + "/jobs/" + bj).with(admin)).andExpect(status().isNotFound());
        // 직접 SQL: 입력 방법·작업 짝, 다른 회사 작업, 원장 UPDATE
        long j = job(admin, row);
        execute(comp, admin, j).andExpect(status().isOk());
        long ln = line(id, 10);
        String ins = "insert into qms_insp_meas (comp_cd, insp_line_id, sample_no, value, gauge_asset_id, occurred_at, recorded_by, entry_method, import_job_id) "
                + "values (?, ?, ?, 10.0, ?, now(), 't', ?, ?)";
        constraint(comp, "ck_qms_insp_meas_entry_method", () -> accounts.exec(comp, ins, comp, ln, 2, gauge, "XX", null));
        constraint(comp, "ck_qms_insp_meas_import_job", () -> accounts.exec(comp, ins, comp, ln, 2, gauge, "IMPORT", null));
        constraint(comp, "ck_qms_insp_meas_import_job", () -> accounts.exec(comp, ins, comp, ln, 2, gauge, "KEY", j));
        constraint(comp, "fk_qms_insp_meas_import_job_comp", () -> accounts.exec(comp, ins, comp, ln, 2, gauge, "IMPORT", bj));
        sqlState("42501", () -> accounts.exec(comp, "update qms_insp_meas set entry_method = 'KEY', import_job_id = null where import_job_id = ?", j));
        long measId = accounts.query(comp, "select meas_id from qms_insp_meas where import_job_id = ?", Long.class, j);
        constraint(comp, "ck_qms_insp_meas_import_job", () -> accounts.exec(comp,
                "insert into qms_insp_meas (comp_cd, insp_line_id, sample_no, void_of_id, occurred_at, recorded_by, entry_method, import_job_id) "
                        + "values (?, ?, 1, ?, now(), 't', 'IMPORT', ?)", comp, ln, measId, j));
        // 정정(무효)은 화면 경로 그대로 — 무효 행은 KEY
        postJson(I + "/" + id + "/meas/void", "{\"measId\":" + measId + "}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select entry_method from qms_insp_meas where void_of_id = ?", String.class, measId)).isEqualTo("KEY");
    }
}
