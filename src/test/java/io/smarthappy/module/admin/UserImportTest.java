package io.smarthappy.module.admin;

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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

/**
 * 시스템관리 4b 사용자 엑셀 올리기 (docs/36 Q26) — 비밀번호 칸 없음, 그룹은 새 사용자만, 부여 상한·자기·마지막 관리자.
 * 정상 + 이상 사용(E 부여 상한, 마지막 관리자, A사로 B사, B 입력)
 */
class UserImportTest extends SysAdminTestSupport {

    private static final String BASE = "/api/platform/import";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @BeforeEach
    void setUp() {
        setUpCompany("UI");
        accounts.exec(comp, """
                insert into sys_dept (comp_cd, dept_cd, dept_nm, created_by, updated_by) values (?, 'PRD-1', '생산1팀', 't', 't'),
                       (?, 'OLD', '없어진 팀', 't', 't')""", comp, comp);
        accounts.exec(comp, "update sys_dept set use_yn = 'N' where dept_cd = 'OLD'");
    }

    private byte[] fill(RequestPostProcessor who, List<String[]> rows) throws Exception {
        byte[] tpl = mvc.perform(post(BASE + "/templates/SYS_USER/file").with(csrf()).with(who))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(tpl))) {
            Sheet s = wb.getSheetAt(0);
            int r = 1;
            for (String[] values : rows) {
                Row row = s.createRow(r++);
                for (int c = 0; c < values.length; c++) {
                    if (values[c] != null) {
                        row.createCell(c).setCellValue(values[c]);
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private long upload(RequestPostProcessor who, List<String[]> rows) throws Exception {
        ResultActions r = mvc.perform(multipart(BASE + "/templates/SYS_USER/jobs")
                .file(new MockMultipartFile("file", "users.xlsx", XLSX, fill(who, rows))).param("mode", "UPSERT")
                .with(csrf()).with(who));
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.jobId")).longValue();
    }

    private ResultActions execute(RequestPostProcessor who, long job, boolean skip) throws Exception {
        int ver = accounts.query(comp, "select row_ver from sys_import_job where job_id = ?", Integer.class, job);
        return mvc.perform(post(BASE + "/jobs/" + job + "/execute").with(csrf()).with(who)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rowVer\":" + ver + ",\"skipErrors\":" + skip + "}"));
    }

    /** loginId, userNm, email, deptCd, localeCd, authGrpCds, useYn */
    private static String[] u(String id, String nm, String email, String dept, String locale, String groups, String use) {
        return new String[] {id, nm, email, dept, locale, groups, use};
    }

    private List<String> errorCodes(RequestPostProcessor who, long job) throws Exception {
        return JsonPath.read(mvc.perform(get(BASE + "/jobs/" + job + "/rows").param("status", "ERROR").with(who))
                .andReturn().getResponse().getContentAsString(), "$.list[*].errors[*].code");
    }

    @Test
    void 새_사용자는_비밀번호_모르는_채로_변경_강제_그룹은_새_사용자만() throws Exception {
        long job = upload(admin, List.of(
                u("kim.mj", "김민지", "minji@example.com", "prd-1", "en", "WORK, HR", null),
                u("worker", "작업자-새이름", null, "PRD-1", null, null, null)));
        mvc.perform(get(BASE + "/jobs/" + job).with(admin)).andExpect(jsonPath("$.data.errorCount").value(0))
                .andExpect(jsonPath("$.data.newCount").value(1)).andExpect(jsonPath("$.data.changeCount").value(1));
        execute(admin, job, false).andExpect(status().isOk());
        long id = accounts.query(comp, "select user_id from sys_user where login_id = 'kim.mj'", Long.class);
        assertThat(accounts.query(comp, "select pwd_change_req_yn || ':' || locale_cd || ':' || (dept_id is not null)::text from sys_user where user_id = ?",
                String.class, id)).isEqualTo("Y:en:true");
        assertThat(accounts.query(comp, "select string_agg(auth_grp_cd, ',' order by auth_grp_cd) from sys_user_auth where user_id = ?",
                String.class, id)).isEqualTo("HR,WORK");
        // 기존 사용자: 이름·부서만, 비운 언어·사용은 그대로, 소속 그대로
        assertThat(accounts.query(comp, "select user_nm || ':' || locale_cd || ':' || use_yn from sys_user where user_id = ?",
                String.class, workerId)).isEqualTo("작업자-새이름:ko:Y");
        // 결과·작업 행 어디에도 비밀번호가 없다 — 넘겨줄 때 화면 초기화로 임시 비밀번호
        mvc.perform(json(post("/api/admin/users/" + id + "/reset-password"), "{\"rowVer\":" + userVer(id) + "}").with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.tempPassword").isNotEmpty());
    }

    @Test
    void 기존_사용자_그룹_부여_상한_자기_사용중지_없는_부서는_행_오류() throws Exception {
        // hr(사용자·권한그룹·거래처 화면만)이 올린다: 관리자 그룹 부여·관리자 변경·자기 사용 중지
        long job = upload(hr, List.of(
                u("new1", "신규", null, null, null, "ADMIN", null),
                u("admin", "관리자 이름", null, null, null, null, null),
                u("hr", "인사", null, null, null, null, "N"),
                u("worker", "작업자", null, null, null, "HR", null),
                u("new2", "신규2", "bad-mail", "NOPE", "jp", "NOSUCH", "maybe"),
                u("new3", "신규3", null, "OLD", null, null, null),
                u("한글아이디", "x", null, null, null, null, null)));
        assertThat(errorCodes(hr, job)).contains("GRANT_EXCEEDS", "SELF_CHANGE", "NEW_ONLY", "FORMAT", "NO_SUCH_CODE",
                "CODE", "INACTIVE");
        mvc.perform(get(BASE + "/jobs/" + job).with(hr)).andExpect(jsonPath("$.data.errorCount").value(7));
        // 오류 행이 있으면 전체 실행 거부, 건너뛰기로 실행해도 아무것도 바뀌지 않는다(오류 행만 있음)
        execute(hr, job, false).andExpect(status().is4xxClientError());
        execute(hr, job, true);
        assertThat(accounts.query(comp, "select count(*) from sys_user where login_id in ('new1', 'new2', 'new3')", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select user_nm from sys_user where user_id = ?", String.class, adminId)).isEqualTo("admin 사용자");
    }

    @Test
    void 다른_관리자_사용중지는_되고_자기_사용중지는_행_오류() throws Exception {
        long admin2 = accounts.newUser(comp, "admin2", PW);
        adminGroup(comp, "ADMIN", admin2);
        long ok = upload(admin, List.of(u("admin2", "관리자2", null, null, null, null, "N"), u("z1", "제트", null, null, null, null, null)));
        execute(admin, ok, false).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select use_yn from sys_user where user_id = ?", String.class, admin2)).isEqualTo("N");
        // 남은 관리자는 자기 자신 — 자기 사용 중지는 행 오류(마지막 관리자 검사는 실행 때 한 번 더 — 행 오류를 건너뛰는 경로 방어)
        long self = upload(admin, List.<String[]>of(u("admin", "관리자", null, null, null, null, "N")));
        assertThat(errorCodes(admin, self)).contains("SELF_CHANGE");
        execute(admin, self, true);
        assertThat(accounts.query(comp, "select use_yn from sys_user where user_id = ?", String.class, adminId)).isEqualTo("Y");
    }

    @Test
    void 올린_뒤_실행_전에_권한이_바뀌면_실행_때_다시_막는다() throws Exception {
        // ① hr 이 WORK 그룹으로 새 사용자 + 작업자 이름 변경을 올림(검증 통과)
        long job = upload(hr, List.of(u("zt.new", "제트", null, null, null, "WORK", null), u("worker", "작업자2", null, null, null, null, null)));
        mvc.perform(get(BASE + "/jobs/" + job).with(hr)).andExpect(jsonPath("$.data.errorCount").value(0));
        // 실행 전에 대상(worker)이 관리자 그룹을 받는다 — sys_user.row_ver 는 그대로
        int ver = userVer(workerId);
        adminGroup(comp, "ADMIN", workerId);
        assertThat(userVer(workerId)).isEqualTo(ver);
        execute(hr, job, false).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GRANT_EXCEEDS"));
        assertThat(accounts.query(comp, "select count(*) from sys_user where login_id = 'zt.new'", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select user_nm from sys_user where user_id = ?", String.class, workerId)).isEqualTo("worker 사용자");
        // ② 새 사용자만 올린 뒤, 그 그룹이 관리자 그룹으로 바뀜 → 실행 거부
        long job2 = upload(hr, List.<String[]>of(u("zt.new2", "제트2", null, null, null, "WORK", null)));
        accounts.exec(comp, "update sys_auth_group set admin_yn = 'Y' where auth_grp_cd = 'WORK'");
        execute(hr, job2, false).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GRANT_EXCEEDS"));
        accounts.exec(comp, "update sys_auth_group set admin_yn = 'N' where auth_grp_cd = 'WORK'");
        // ③ 올린 사람 권한이 줄면(그룹 화면 권한 행 삭제) → 실행 거부, 아무것도 안 바뀜
        long job3 = upload(hr, List.<String[]>of(u("zt.new3", "제트3", null, null, null, "WORK", null)));
        accounts.exec(comp, "delete from sys_auth_prog where auth_grp_cd = 'HR' and prog_cd = 'mst-cust'");
        execute(hr, job3, false).andExpect(status().isForbidden());
        assertThat(accounts.query(comp, "select count(*) from sys_user where login_id like 'zt.new%'", Integer.class)).isZero();
    }

    @Test
    void 엑셀로_대리를_사용_중지해도_진행_중_위임이_끝나고_위임자에게_알린다() throws Exception {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        long d = accounts.query(comp, """
                insert into apr_delegate (comp_cd, delegator_id, delegate_id, from_date, to_date, reason, created_by, updated_by)
                values (?, ?, ?, ?, ?, '출장', 't', 't') returning deleg_id""", Long.class, comp, hrId, workerId, today, today.plusDays(1));
        long job = upload(admin, List.<String[]>of(u("worker", "작업자", null, null, null, null, "N")));
        execute(admin, job, false).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select revoke_cd from apr_delegate where deleg_id = ?", String.class, d)).isEqualTo("DELEGATE_OFF");
        assertThat(accounts.query(comp, "select count(*) from sys_notify where user_id = ? and event_cd = 'APR_DELEG_ENDED'",
                Integer.class, hrId)).isEqualTo(1);
    }

    @Test
    void 권한_없음과_A사로_B사() throws Exception {
        // 작업자(사용자 관리 권한 없음): 템플릿·올리기 403
        mvc.perform(post(BASE + "/templates/SYS_USER/file").with(csrf()).with(worker)).andExpect(status().isForbidden());
        // B사 관리자가 A사와 같은 아이디를 올리면 B사 안의 새 사용자 — A사 사용자는 그대로
        String compA = comp;
        long adminA = adminId;
        setUpCompany("UIB");
        long job = upload(admin, List.<String[]>of(u("worker", "B사 작업자", null, null, null, null, null)));
        mvc.perform(get(BASE + "/jobs/" + job).with(admin)).andExpect(jsonPath("$.data.changeCount").value(1));
        execute(admin, job, false).andExpect(status().isOk());
        assertThat(accounts.query(compA, "select user_nm from sys_user where login_id = 'worker'", String.class)).isEqualTo("worker 사용자");
        assertThat(accounts.query(comp, "select user_nm from sys_user where login_id = 'worker'", String.class)).isEqualTo("B사 작업자");
        mvc.perform(get(BASE + "/jobs/" + job).with(accounts.as(compA, adminA, "admin"))).andExpect(status().isNotFound());
    }

    @Test
    void 협력사_포털_사용자는_엑셀로_바꾸지_않는다() throws Exception {
        long cust = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'S1', '협력상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into sys_user (comp_cd, login_id, user_nm, pwd_hash, user_type, cust_id, created_by, updated_by)
                values (?, 'sup1', '협력', '{noop}x', 'SUPPLIER', ?, 't', 't')""", comp, cust);
        // 대소문자만 다른 아이디로도 — 권한그룹·부서를 넣어 협력사 계정을 회사 조직에 끌어들이려는 행
        long job = upload(admin, List.<String[]>of(u("SUP1", "바꾼 이름", null, "PRD-1", null, "WORK", null)));
        assertThat(errorCodes(admin, job)).contains("SUPPLIER_USER");
        execute(admin, job, true);
        assertThat(accounts.query(comp, "select user_nm || ':' || (dept_id is null)::text from sys_user where login_id = 'sup1'",
                String.class)).isEqualTo("협력:true");
        assertThat(accounts.query(comp, "select count(*) from sys_user where lower(login_id) = 'sup1'", Integer.class)).isEqualTo(1);
    }
}
