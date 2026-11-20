package io.smarthappy.module.production;

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
import java.util.Locale;

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
 * 작업지시 엑셀 올리기 PRD_WO (docs/25 4.26, 11.4a 3번) — 새 지시(채번·기본 라우팅), 행 오류(공장·품목·수량 자릿수·날짜·우선순위·라우팅 없음),
 * 계획 지시 고치기(빈 칸은 그대로·라우팅 유지·공장/품목 고정), 발행된 지시·없는 번호는 오류, 그 사이 발행되면 갱신 안 됨,
 * A사로 B사, 조회 권한만이면 403.
 */
@AutoConfigureMockMvc
class WoImportTest extends PostgresTestBase {

    private static final String PW = "Willow-ember-36";
    private static final String IMP = "/api/platform/import";
    private static final String W = "/api/production/wos";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    String today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("WI");
        admin = user(comp, "admin1", "SEARCH", "SAVE");
        seed(comp);
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
    }

    /** 권한 그룹은 사용자마다 따로(같은 그룹이면 저장 권한을 함께 받는다) */
    private RequestPostProcessor user(String c, String loginId, String... actions) {
        long u = accounts.newUser(c, loginId, PW);
        accounts.grant(c, u, "G_" + loginId.toUpperCase(Locale.ROOT), "prd-wo", actions);
        return accounts.as(c, u, loginId);
    }

    /** 공장 P1·P2(사용 중지), 품목 FG(EA, P1 라우팅 확정)·KG1(KG)·NORT(라우팅 없음) */
    private void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.addPlant(c, "P2", "2공장");
        accounts.exec(c, "update sys_plant set use_yn = 'N' where plant_cd = 'P2'");
        for (String[] i : new String[][] {{"FG", "EA"}, {"KG1", "KG"}, {"NORT", "EA"}}) {
            accounts.exec(c, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                    values (?, ?, ?, 'FG', 'MAKE', ?, 't', 't')""", c, i[0], i[0] + " 품목", i[1]);
        }
        accounts.exec(c, "insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by) values (?, 'SPOT', '스폿', 'WELD', 't', 't')", c);
        for (String item : List.of("FG", "KG1")) {
            long rid = accounts.query(c, """
                    insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                    values (?, (select item_id from mst_item where item_cd = ?), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                    returning routing_id""", Long.class, c, item);
            accounts.exec(c, """
                    insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, std_ct_sec, created_by, updated_by)
                    values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'SPOT'), 30, 't', 't')""", c, rid);
            accounts.exec(c, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        }
    }

    /** 열 순서: 지시 번호·공장·품목·수량·시작·완료·우선·비고 */
    private static Object[] row(String no, String plant, String item, String qty, String start, String end, String prio, String remark) {
        return new Object[] {no, plant, item, qty, start, end, prio, remark};
    }

    private byte[] fill(RequestPostProcessor who, List<Object[]> rows) throws Exception {
        byte[] tpl = mvc.perform(post(IMP + "/templates/PRD_WO/file").with(csrf()).with(who))
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
        return mvc.perform(multipart(IMP + "/templates/PRD_WO/jobs").file(new MockMultipartFile("file", "wo.xlsx", XLSX, file))
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

    private String wo(String no) {
        return accounts.query(comp, """
                select w.status || '/' || trim_scale(w.plan_qty) || '/' || w.plan_start || '/' || w.plan_end || '/' || w.priority || '/'
                       || coalesce(w.remark, '') || '/' || r.rev
                  from prd_wo w join mst_routing r on r.routing_id = w.routing_id where w.wo_no = ?""", String.class, no);
    }

    private String woNo(String remark) {
        return accounts.query(comp, "select wo_no from prd_wo where remark = ?", String.class, remark);
    }

    @Test
    void 새_지시는_채번_기본_라우팅으로_들어가고_행_오류는_빠진다() throws Exception {
        long j = job(upload(admin, fill(admin, List.<Object[]>of(
                row(null, "p1", "fg", "100", today, null, null, "엑셀 1"),
                row(null, "P1", "KG1", "12.345", today, today, "1", "엑셀 2"),
                row(null, null, "FG", "1", today, null, null, "공장 없음"),
                row(null, "P9", "FG", "1", today, null, null, "없는 공장"),
                row(null, "P2", "FG", "1", today, null, null, "사용 중지 공장"),
                row(null, "P1", "NOPE", "1", today, null, null, "없는 품목"),
                row(null, "P1", "FG", "1.5", today, null, null, "EA 소수"),
                row(null, "P1", "FG", "1", "2026-02-30", null, null, "날짜"),
                row(null, "P1", "FG", "1", today, "2000-01-01", null, "완료가 앞"),
                row(null, "P1", "FG", "1", today, null, "10", "우선 범위"),
                row(null, "P1", "NORT", "1", today, null, null, "라우팅 없음"),
                row("WO-NOPE", "P1", "FG", "1", today, null, null, "없는 번호"))), "UPSERT")
                .andExpect(jsonPath("$.data.newCount").value(2))
                .andExpect(jsonPath("$.data.errorCount").value(10)));
        mvc.perform(get(IMP + "/jobs/" + j + "/rows?status=ERROR&size=50").with(admin))
                .andExpect(jsonPath("$.list[*].errors[0].code").value(Matchers.containsInAnyOrder("REQUIRED", "NO_SUCH_CODE", "INACTIVE",
                        "NO_SUCH_CODE", "SCALE", "FORMAT", "INVALID_RANGE", "RANGE", "NO_ROUTING", "NO_SUCH_CODE")));
        execute(comp, admin, j).andExpect(status().isOk()).andExpect(jsonPath("$.data.result.inserted").value(2));
        assertThat(woNo("엑셀 1")).matches("^WO\\d{4}-\\d{4}$");
        assertThat(wo(woNo("엑셀 1"))).isEqualTo("PLANNED/100/" + today + "/" + today + "/5/엑셀 1/A0");
        assertThat(wo(woNo("엑셀 2"))).isEqualTo("PLANNED/12.345/" + today + "/" + today + "/1/엑셀 2/A0");
        // 화면 목록에도 그대로
        mvc.perform(get(W).param("plantCd", "P1").param("from", today).param("to", today).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(2));
    }

    @Test
    void 계획_지시는_빈_칸_그대로_고치고_발행된_지시_없는_번호_파일_안_같은_키_공장_품목_바꾸기는_오류() throws Exception {
        execute(comp, admin, job(upload(admin, fill(admin, List.<Object[]>of(
                row(null, "P1", "FG", "100", today, null, "3", "계획"),
                row(null, "P1", "FG", "50", today, null, null, "발행할 것"))), "UPSERT"))).andExpect(status().isOk());
        String planned = woNo("계획");
        String released = woNo("발행할 것");
        long relId = accounts.query(comp, "select wo_id from prd_wo where wo_no = ?", Long.class, released);
        mvc.perform(post(W + "/" + relId + "/release").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON).content("{\"rowVer\":0}"))
                .andExpect(status().isOk());
        String end = java.time.LocalDate.parse(today).plusDays(3).toString();
        long j = job(upload(admin, fill(admin, List.<Object[]>of(
                row(planned.toLowerCase(Locale.ROOT), null, null, "120", null, end, null, null),   // 수량·완료일만, 나머지 그대로
                row(planned, "P1", "FG", "120", today, end, "3", "계획"),                              // 같은 번호 두 행 → 둘 다 FILE_DUPLICATE
                row(released, null, null, "60", null, null, null, null),                             // 발행된 지시
                row(woNo("계획") + "X", null, null, "1", null, null, null, null))), "UPSERT")           // 없는 번호
                .andExpect(jsonPath("$.data.errorCount").value(4)));
        mvc.perform(get(IMP + "/jobs/" + j + "/rows?status=ERROR&size=50").with(admin))
                .andExpect(jsonPath("$.list[*].errors[0].code").value(Matchers.containsInAnyOrder("FILE_DUPLICATE", "FILE_DUPLICATE",
                        "STATE", "NO_SUCH_CODE")));
        // 한 번만: 수량·완료일 고치기
        long j3 = job(upload(admin, fill(admin, List.<Object[]>of(row(planned, null, null, "120", null, end, null, null))), "UPSERT")
                .andExpect(jsonPath("$.data.changeCount").value(1)));
        execute(comp, admin, j3).andExpect(status().isOk()).andExpect(jsonPath("$.data.result.updated").value(1));
        assertThat(wo(planned)).isEqualTo("PLANNED/120/" + today + "/" + end + "/3/계획/A0");        // 우선·비고·라우팅 그대로
        // 공장·품목 바꾸기 → FIXED
        long j4 = job(upload(admin, fill(admin, List.<Object[]>of(row(planned, "P1", "KG1", null, null, null, null, null))), "UPSERT")
                .andExpect(jsonPath("$.data.errorCount").value(1)));
        mvc.perform(get(IMP + "/jobs/" + j4 + "/rows?status=ERROR").with(admin)).andExpect(jsonPath("$.list[0].errors[0].code").value("FIXED"));
        // 같은 내용 → 같음
        upload(admin, fill(admin, List.<Object[]>of(row(planned, null, null, "120", null, null, null, null))), "UPSERT")
                .andExpect(jsonPath("$.data.sameCount").value(1));
    }

    @Test
    void 미리보기_뒤_화면에서_발행되면_실행은_그_행을_갱신하지_않는다() throws Exception {
        execute(comp, admin, job(upload(admin, fill(admin, List.<Object[]>of(row(null, "P1", "FG", "100", today, null, null, "경합"))), "UPSERT")))
                .andExpect(status().isOk());
        String no = woNo("경합");
        long j = job(upload(admin, fill(admin, List.<Object[]>of(row(no, null, null, "80", null, null, null, null))), "UPSERT")
                .andExpect(jsonPath("$.data.changeCount").value(1)));
        long id = accounts.query(comp, "select wo_id from prd_wo where wo_no = ?", Long.class, no);
        mvc.perform(post(W + "/" + id + "/release").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON).content("{\"rowVer\":0}"))
                .andExpect(status().isOk());
        execute(comp, admin, j);
        assertThat(wo(no)).startsWith("RELEASED/100/");
    }

    @Test
    void B사는_A사_지시_번호를_모르고_조회_권한만이면_올리기_403() throws Exception {
        execute(comp, admin, job(upload(admin, fill(admin, List.<Object[]>of(row(null, "P1", "FG", "1", today, null, null, "A사"))), "UPSERT")))
                .andExpect(status().isOk());
        String aNo = woNo("A사");
        String other = accounts.newComp("WJ");
        RequestPostProcessor b = user(other, "admin1", "SEARCH", "SAVE");
        seed(other);
        long j = job(upload(b, fill(b, List.<Object[]>of(
                row(aNo, "P1", "FG", "5", today, null, null, "A사 번호로"),
                row(null, "P1", "FG", "5", today, null, null, "B사 새 지시"))), "UPSERT")
                .andExpect(jsonPath("$.data.errorCount").value(1))
                .andExpect(jsonPath("$.data.newCount").value(1)));
        execute(other, b, j).andExpect(status().isOk());
        assertThat(wo(aNo)).startsWith("PLANNED/1/");                                              // A사 지시 그대로
        assertThat(accounts.query(other, "select count(*) from prd_wo", Integer.class)).isEqualTo(1);
        RequestPostProcessor viewer = user(comp, "viewer1", "SEARCH");
        upload(viewer, fill(admin, List.<Object[]>of(row(null, "P1", "FG", "1", today, null, null, "x"))), "UPSERT")
                .andExpect(status().isForbidden());
    }
}
