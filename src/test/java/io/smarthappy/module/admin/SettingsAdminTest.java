package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;

/**
 * 시스템관리 2단계 (docs/36 9장) — 부서·정책·회사 공통코드·채번·회사 정보.
 * 정상 흐름 + 이상 사용(E 보안 정책·시간대, A사로 B사, 직접 SQL, B 입력, F 낡은 버전)
 */
class SettingsAdminTest extends SysAdminTestSupport {

    @Autowired
    TenantRunner runner;

    @Autowired
    PolicyService policies;

    @Autowired
    NumberingService numbering;

    @BeforeEach
    void setUp() {
        setUpCompany("SC");
    }

    private long createDept(RequestPostProcessor who, Long parent, String cd, String nm) throws Exception {
        MvcResult r = mvc.perform(json(post("/api/admin/depts"), "{\"parentId\":" + parent + ",\"deptCd\":\"" + cd
                + "\",\"deptNm\":\"" + nm + "\",\"useYn\":\"Y\"}").with(who)).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private int deptVer(long id) {
        return accounts.query(comp, "select row_ver from sys_dept where dept_id = ?", Integer.class, id);
    }

    private static String constraintOf(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            return PgErrors.constraint(e).orElse("(none) " + e.getMessage());
        }
        return "(no error)";
    }

    // ================================================================== 부서

    @Test
    void 부서_트리_소속_사용중지는_새_소속만_막는다() throws Exception {
        long a = createDept(admin, null, "prod", "생산본부");
        long b = createDept(admin, a, "PRD-1", "생산1팀");
        mvc.perform(get("/api/admin/depts").with(admin))
                .andExpect(jsonPath("$.list[?(@.deptId==" + b + ")].path").value(Matchers.hasItem("PROD/PRD-1")))
                .andExpect(jsonPath("$.list[?(@.deptId==" + b + ")].lvl").value(Matchers.hasItem(2)));
        // 소속 지정 → 목록·부서 조건·인원
        mvc.perform(json(put("/api/admin/users/" + workerId), userBodyDept("작업자", "[\"WORK\"]", b, userVer(workerId))).with(admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/users").param("deptId", String.valueOf(b)).with(admin))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.list[0].deptNm").value("생산1팀"));
        // 사용 중 하위가 있으면 상위 사용 중지 거부, 자기 하위 아래로 이동 거부
        mvc.perform(json(put("/api/admin/depts/" + a), "{\"deptNm\":\"생산본부\",\"useYn\":\"N\",\"rowVer\":" + deptVer(a) + "}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("CHILDREN_ACTIVE"));
        mvc.perform(json(put("/api/admin/depts/" + a), "{\"parentId\":" + b + ",\"deptNm\":\"생산본부\",\"useYn\":\"Y\",\"rowVer\":"
                + deptVer(a) + "}").with(admin)).andExpect(jsonPath("$.errors[0].code").value("DEPT_CYCLE"));
        // 소속 사람이 있는 부서 사용 중지는 된다 — 사람은 그대로, 새 소속만 막힘, 선택지에서 빠짐
        mvc.perform(json(put("/api/admin/depts/" + b), "{\"parentId\":" + a + ",\"deptNm\":\"생산1팀\",\"useYn\":\"N\",\"rowVer\":"
                + deptVer(b) + "}").with(admin)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select dept_id from sys_user where user_id = ?", Long.class, workerId)).isEqualTo(b);
        mvc.perform(json(put("/api/admin/users/" + hrId), userBodyDept("인사", "[\"HR\"]", b, userVer(hrId))).with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("deptId"))
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        // 이미 소속된 사람은 다른 칸을 고쳐도 소속이 그대로 저장된다
        mvc.perform(json(put("/api/admin/users/" + workerId), userBodyDept("작업자2", "[\"WORK\"]", b, userVer(workerId))).with(admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/platform/depts").with(worker)).andExpect(status().isOk())
                .andExpect(jsonPath("$.list[*].deptCd").value(Matchers.contains("PROD")));
    }

    static String userBodyDept(String nm, String groups, Long dept, int rowVer) {
        return "{\"userNm\":\"" + nm + "\",\"localeCd\":\"ko\",\"useYn\":\"Y\",\"authGrpCds\":" + groups + ",\"deptId\":" + dept
                + ",\"rowVer\":" + rowVer + "}";
    }

    @Test
    void 직접_SQL_로도_부서_규칙은_막힌다() throws Exception {
        long a = createDept(admin, null, "A", "A");
        long b = createDept(admin, a, "B", "B");
        String compB = accounts.newComp("SCB");
        long bDept = accounts.query(compB, """
                insert into sys_dept (comp_cd, dept_cd, dept_nm, created_by, updated_by)
                values (hb_current_comp(), 'X', 'B사 부서', 't', 't') returning dept_id""", Long.class);
        long bUser = accounts.newUser(compB, "buser", PW);

        assertThat(constraintOf(() -> accounts.exec(comp, "update sys_dept set parent_id = ? where dept_id = ?", b, a)))
                .isEqualTo("ck_sys_dept_cycle");
        assertThat(constraintOf(() -> accounts.exec(comp, """
                insert into sys_dept (comp_cd, parent_id, dept_cd, dept_nm, created_by, updated_by)
                values (?, ?, 'Z', 'Z', 't', 't')""", comp, bDept))).isEqualTo("fk_sys_dept_parent_comp");
        long parent = b;
        for (int i = 3; i <= 10; i++) {
            parent = accounts.query(comp, """
                    insert into sys_dept (comp_cd, parent_id, dept_cd, dept_nm, created_by, updated_by)
                    values (?, ?, ?, 'd', 't', 't') returning dept_id""", Long.class, comp, parent, "L" + i);
        }
        long tenth = parent;
        assertThat(constraintOf(() -> accounts.exec(comp, """
                insert into sys_dept (comp_cd, parent_id, dept_cd, dept_nm, created_by, updated_by)
                values (?, ?, 'L11', 'd', 't', 't')""", comp, tenth))).isEqualTo("ck_sys_dept_depth");
        assertThat(constraintOf(() -> accounts.exec(comp, "update sys_dept set use_yn = 'N' where dept_id = ?", a)))
                .isEqualTo("ck_sys_dept_children_active");
        assertThat(constraintOf(() -> accounts.exec(comp, "delete from sys_dept where dept_id = ?", tenth)))
                .isEqualTo("ck_sys_dept_no_delete");
        // 사용자 소속: 다른 회사 부서·사용 중지 부서
        assertThat(constraintOf(() -> accounts.exec(comp, "update sys_user set dept_id = ? where user_id = ?", bDept, workerId)))
                .isEqualTo("fk_sys_user_dept_comp");
        accounts.exec(comp, "update sys_dept set use_yn = 'N' where dept_id = ?", tenth);
        assertThat(constraintOf(() -> accounts.exec(comp, "update sys_user set dept_id = ? where user_id = ?", tenth, workerId)))
                .isEqualTo("ck_sys_user_dept_use");
        // 사용 중지 부모 아래 하위를 다시 켜기
        long off = accounts.query(comp, """
                insert into sys_dept (comp_cd, dept_cd, dept_nm, use_yn, created_by, updated_by)
                values (?, 'OFF', 'o', 'N', 't', 't') returning dept_id""", Long.class, comp);
        long child = accounts.query(comp, """
                insert into sys_dept (comp_cd, dept_cd, dept_nm, use_yn, created_by, updated_by)
                values (?, 'ORPH', 'o', 'N', 't', 't') returning dept_id""", Long.class, comp);
        accounts.exec(comp, "update sys_dept set parent_id = ? where dept_id = ?", off, child);
        assertThat(constraintOf(() -> accounts.exec(comp, "update sys_dept set use_yn = 'Y' where dept_id = ?", child)))
                .isEqualTo("ck_sys_dept_parent_use");
        assertThat(bUser).isPositive();
    }

    // ================================================================== 정책

    @Test
    void 정책_저장_공장값_기본값으로_낡은_버전() throws Exception {
        mvc.perform(get("/api/admin/policies").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.list[?(@.policyKey=='GRID_PAGE_SIZE_MAX')].editable").value(Matchers.hasItem(true)))
                .andExpect(jsonPath("$.list[?(@.policyKey=='PWD_MIN_LENGTH')].security").value(Matchers.hasItem(true)));
        mvc.perform(json(put("/api/admin/policies/GRID_PAGE_SIZE_MAX"), "{\"value\":\" 200 \"}").with(admin)).andExpect(status().isOk());
        assertThat(runner.call(comp, () -> policies.intValue("GRID_PAGE_SIZE_MAX"))).isEqualTo(200);
        // 새 값인데 버전을 보냄 / 있는 값인데 버전 없음·낡음 → 409
        mvc.perform(json(put("/api/admin/policies/GRID_PAGE_SIZE_MAX"), "{\"value\":\"300\"}").with(admin)).andExpect(status().isConflict());
        mvc.perform(json(put("/api/admin/policies/GRID_PAGE_SIZE_MAX"), "{\"value\":\"300\",\"rowVer\":5}").with(admin))
                .andExpect(status().isConflict());
        mvc.perform(json(put("/api/admin/policies/GRID_PAGE_SIZE_MAX"), "{\"value\":\"300\",\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        // 범위 밖·형식 → 칸 오류
        mvc.perform(json(put("/api/admin/policies/GRID_PAGE_SIZE_MAX"), "{\"value\":\"9\",\"rowVer\":1}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("POLICY_VALUE"));
        mvc.perform(json(put("/api/admin/policies/GRID_PAGE_SIZE_MAX"), "{\"value\":\"abc\",\"rowVer\":1}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("POLICY_VALUE"));
        // 공장 값: 공장 범위 정책만, 있는 공장만
        accounts.addPlant(comp, "P1", "1공장");
        mvc.perform(json(put("/api/admin/policies/QUAL_GATE_MODE"), "{\"plantCd\":\"P1\",\"value\":\"BLOCK\"}").with(admin)).andExpect(status().isOk());
        assertThat(runner.call(comp, () -> policies.value("QUAL_GATE_MODE", "P1"))).isEqualTo("BLOCK");
        assertThat(runner.call(comp, () -> policies.value("QUAL_GATE_MODE"))).isEqualTo("WARN");
        mvc.perform(json(put("/api/admin/policies/QUAL_GATE_MODE"), "{\"plantCd\":\"P9\",\"value\":\"BLOCK\"}").with(admin))
                .andExpect(jsonPath("$.errors[0].field").value("plantCd"));
        mvc.perform(json(put("/api/admin/policies/GRID_PAGE_SIZE_MAX"), "{\"plantCd\":\"P1\",\"value\":\"100\"}").with(admin))
                .andExpect(jsonPath("$.errors[0].field").value("plantCd"));
        mvc.perform(json(put("/api/admin/policies/NO_SUCH_POLICY"), "{\"value\":\"1\"}").with(admin)).andExpect(status().isNotFound());
        // 기본값으로 = 행 삭제(감사 DELETE)
        mvc.perform(json(post("/api/admin/policies/GRID_PAGE_SIZE_MAX/reset"), "{\"rowVer\":0}").with(admin)).andExpect(status().isConflict());
        mvc.perform(json(post("/api/admin/policies/GRID_PAGE_SIZE_MAX/reset"), "{\"rowVer\":1}").with(admin)).andExpect(status().isOk());
        assertThat(runner.call(comp, () -> policies.intValue("GRID_PAGE_SIZE_MAX"))).isEqualTo(500);
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'sys_policy' and action = 'DELETE'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void 보안_정책은_관리자_그룹만_바꾼다() throws Exception {
        accounts.grant(comp, hrId, "HR", "sys-policy", "SEARCH", "SAVE");
        mvc.perform(get("/api/admin/policies").with(hr))
                .andExpect(jsonPath("$.list[?(@.policyKey=='PWD_MIN_LENGTH')].editable").value(Matchers.hasItem(false)));
        for (String key : new String[] {"PWD_MIN_LENGTH", "LOGIN_TRUSTED_IPS", "AI_EXTERNAL_API_ALLOWED", "FILE_ALLOWED_EXT",
            "APR_SELF_APPROVE_ALLOWED", "SRM_PR_SELF_APPROVE"}) {
            mvc.perform(json(put("/api/admin/policies/" + key), "{\"value\":\"8\"}").with(hr))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GRANT_EXCEEDS"));
        }
        mvc.perform(json(put("/api/admin/policies/GRID_PAGE_SIZE_MAX"), "{\"value\":\"100\"}").with(hr)).andExpect(status().isOk());
        mvc.perform(json(put("/api/admin/policies/PWD_MIN_LENGTH"), "{\"value\":\"16\"}").with(admin)).andExpect(status().isOk());
        mvc.perform(json(post("/api/admin/policies/PWD_MIN_LENGTH/reset"), "{\"rowVer\":0}").with(hr))
                .andExpect(jsonPath("$.code").value("GRANT_EXCEEDS"));
        assertThat(runner.call(comp, () -> policies.intValue("PWD_MIN_LENGTH"))).isEqualTo(16);
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'GRANT_DENIED'", Integer.class))
                .isEqualTo(7);
    }

    // ================================================================== 회사 공통코드

    @Test
    void 회사_코드_추가_수정_단위_자릿수는_늘리기만() throws Exception {
        mvc.perform(get("/api/admin/codes").with(admin)).andExpect(jsonPath("$.list[*].grpCd").value(Matchers.hasItem("UNIT")))
                .andExpect(jsonPath("$.list[*].grpCd").value(Matchers.not(Matchers.hasItem("CUST_TYPE"))));
        mvc.perform(json(post("/api/admin/codes/UNIT"), "{\"code\":\"ztbox\",\"codeNm\":\"상자\",\"sortNo\":50,\"scale\":0}").with(admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/platform/codes").param("grp", "UNIT").with(worker))
                .andExpect(jsonPath("$.data[?(@.code=='ZTBOX')].attrs.scale").value(Matchers.hasItem(0)));
        mvc.perform(json(put("/api/admin/codes/UNIT/ZTBOX"), "{\"codeNm\":\"박스\",\"scale\":2,\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        mvc.perform(json(put("/api/admin/codes/UNIT/ZTBOX"), "{\"codeNm\":\"박스\",\"scale\":1,\"rowVer\":1}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("SCALE_DOWN"));
        mvc.perform(json(put("/api/admin/codes/UNIT/ZTBOX"), "{\"codeNm\":\"박스\",\"scale\":3,\"rowVer\":0}").with(admin))
                .andExpect(status().isConflict());
        // 시스템 코드와 같은 코드·이미 있는 회사 코드 → DUPLICATE, 시스템 코드 수정 → 404, 확장 안 되는 그룹 → 404
        mvc.perform(json(post("/api/admin/codes/UNIT"), "{\"code\":\"EA\",\"codeNm\":\"개\",\"scale\":0}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        mvc.perform(json(post("/api/admin/codes/UNIT"), "{\"code\":\"ZTBOX\",\"codeNm\":\"또\",\"scale\":0}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        mvc.perform(json(put("/api/admin/codes/UNIT/EA"), "{\"codeNm\":\"바꿈\",\"scale\":0,\"rowVer\":0}").with(admin))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/codes/CUST_TYPE").with(admin)).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/admin/codes/DOWNTIME_REASON"), "{\"code\":\"한글\",\"codeNm\":\"\"}").with(admin))
                .andExpect(jsonPath("$.errors.length()").value(2));
        // 다른 그룹은 scale 칸을 무시하고 속성을 건드리지 않는다
        mvc.perform(json(post("/api/admin/codes/DOWNTIME_REASON"), "{\"code\":\"ZT-MOLD\",\"codeNm\":\"금형 교체\",\"scale\":3}").with(admin))
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select attrs::text from sys_comp_code where code = 'ZT-MOLD'", String.class)).isEqualTo("{}");
        mvc.perform(get("/api/admin/codes/UNIT").with(admin))
                .andExpect(jsonPath("$.list[?(@.code=='EA')].system").value(Matchers.hasItem(true)))
                .andExpect(jsonPath("$.list[?(@.code=='ZTBOX')].scale").value(Matchers.hasItem(2)));
    }

    // ================================================================== 채번

    @Test
    void 채번_규칙_미리보기는_번호를_쓰지_않고_자릿수_초과는_거부() throws Exception {
        mvc.perform(json(post("/api/admin/numbering"), "{\"ruleCd\":\"zt_test\",\"ruleNm\":\"시험\",\"pattern\":\"ZT{yyMM}-{seq:2}\","
                + "\"resetCycle\":\"MONTH\"}").with(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/numbering").with(admin))
                .andExpect(jsonPath("$.list[?(@.rule.ruleCd=='ZT_TEST')].nextPreview").value(Matchers.hasItem(Matchers.endsWith("-01"))));
        for (int i = 0; i < 3; i++) {
            mvc.perform(json(post("/api/admin/numbering/preview"), "{\"ruleCd\":\"ZT_TEST\",\"pattern\":\"ZT{yyMM}-{seq:2}\","
                    + "\"resetCycle\":\"MONTH\"}").with(admin)).andExpect(jsonPath("$.data.sample").value(Matchers.endsWith("-01")));
        }
        String first = runner.call(comp, () -> numbering.next("ZT_TEST"));
        assertThat(first).endsWith("-01");   // 미리보기 3번이 번호를 쓰지 않았다
        for (int i = 0; i < 9; i++) {
            runner.call(comp, () -> numbering.next("ZT_TEST"));
        }
        mvc.perform(get("/api/admin/numbering").with(admin))
                .andExpect(jsonPath("$.list[?(@.rule.ruleCd=='ZT_TEST')].lastNo").value(Matchers.hasItem(10)));
        // 이번 기간 10번까지 냈는데 {seq:1} → 거부, 주기를 바꾸면 미리보기가 기간 바뀜을 알린다
        mvc.perform(json(put("/api/admin/numbering/ZT_TEST"), "{\"ruleNm\":\"시험\",\"pattern\":\"ZT{yyMM}-{seq:1}\","
                + "\"resetCycle\":\"MONTH\",\"rowVer\":0}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("SEQ_TOO_SHORT"));
        mvc.perform(json(post("/api/admin/numbering/preview"), "{\"ruleCd\":\"ZT_TEST\",\"pattern\":\"ZT{yyMMdd}-{seq:3}\","
                + "\"resetCycle\":\"DAY\"}").with(admin)).andExpect(jsonPath("$.data.changesPeriod").value(true));
        mvc.perform(json(put("/api/admin/numbering/ZT_TEST"), "{\"ruleNm\":\"시험\",\"pattern\":\"ZT{yyMM}-{seq:4}\","
                + "\"resetCycle\":\"MONTH\",\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        assertThat(runner.call(comp, () -> numbering.next("ZT_TEST"))).endsWith("-0011");
        // 형식 오류: 월 초기화인데 월 없음, 순번 없음, 소문자 글자, 낡은 버전, 없는 규칙
        for (String bad : new String[] {"ZT-{seq:3}", "ZT{yyMM}", "zt{yyMM}-{seq:3}"}) {
            mvc.perform(json(put("/api/admin/numbering/ZT_TEST"), "{\"ruleNm\":\"시험\",\"pattern\":\"" + bad
                    + "\",\"resetCycle\":\"MONTH\",\"rowVer\":1}").with(admin)).andExpect(jsonPath("$.errors[0].code").value("PATTERN"));
        }
        mvc.perform(json(put("/api/admin/numbering/ZT_TEST"), "{\"ruleNm\":\"x\",\"pattern\":\"ZT{yyMM}-{seq:4}\","
                + "\"resetCycle\":\"MONTH\",\"rowVer\":0}").with(admin)).andExpect(status().isConflict());
        mvc.perform(json(put("/api/admin/numbering/NOPE"), "{\"ruleNm\":\"x\",\"pattern\":\"A{seq:2}\",\"resetCycle\":\"NONE\",\"rowVer\":0}")
                .with(admin)).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/admin/numbering"), "{\"ruleCd\":\"1BAD\",\"ruleNm\":\"x\",\"pattern\":\"A{seq:2}\",\"resetCycle\":\"NONE\"}")
                .with(admin)).andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
    }

    // ================================================================== 회사 정보

    @Test
    void 회사_정보와_시간대는_관리자만_확인과_함께() throws Exception {
        accounts.grant(comp, hrId, "HR", "sys-comp", "SEARCH", "SAVE");
        mvc.perform(get("/api/admin/company").with(hr)).andExpect(jsonPath("$.data.comp.compCd").value(comp))
                .andExpect(jsonPath("$.data.canChangeTimeZone").value(false));
        int v = accounts.query(comp, "select row_ver from sys_comp where comp_cd = ?", Integer.class, comp);
        String base = "\"compNm\":\"ZT 정밀\",\"bizNo\":\"123-45-67890\",\"localeCd\":\"ko\"";
        mvc.perform(json(put("/api/admin/company"), "{" + base + ",\"timeZone\":\"Asia/Seoul\",\"rowVer\":" + v + "}").with(hr))
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select comp_nm || biz_no || status_cd from sys_comp where comp_cd = ?", String.class, comp))
                .isEqualTo("ZT 정밀1234567890ACTIVE");
        // 시간대: 확인 없음 → 칸 오류, 부관리자 → 403, 관리자 + 확인 → 반영(업무 날짜 기준)
        mvc.perform(json(put("/api/admin/company"), "{" + base + ",\"timeZone\":\"Asia/Ho_Chi_Minh\",\"rowVer\":" + (v + 1) + "}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("CONFIRM_TZ"));
        mvc.perform(json(put("/api/admin/company"), "{" + base + ",\"timeZone\":\"Asia/Ho_Chi_Minh\",\"confirmTimeZone\":true,\"rowVer\":"
                + (v + 1) + "}").with(hr)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GRANT_EXCEEDS"));
        mvc.perform(json(put("/api/admin/company"), "{" + base + ",\"timeZone\":\"Mars/Base\",\"confirmTimeZone\":true,\"rowVer\":"
                + (v + 1) + "}").with(admin)).andExpect(jsonPath("$.errors[0].code").value("CODE"));
        mvc.perform(json(put("/api/admin/company"), "{" + base + ",\"timeZone\":\"Asia/Ho_Chi_Minh\",\"confirmTimeZone\":true,\"rowVer\":"
                + (v + 1) + "}").with(admin)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select time_zone from sys_comp where comp_cd = ?", String.class, comp)).isEqualTo("Asia/Ho_Chi_Minh");
        mvc.perform(json(put("/api/admin/company"), "{" + base + ",\"timeZone\":\"Asia/Ho_Chi_Minh\",\"rowVer\":" + v + "}").with(admin))
                .andExpect(status().isConflict());
        mvc.perform(json(put("/api/admin/company"), "{\"compNm\":\" \",\"bizNo\":\"12\",\"telNo\":\"<x>\",\"timeZone\":\"Asia/Seoul\","
                + "\"localeCd\":\"xx\",\"rowVer\":0}").with(admin)).andExpect(jsonPath("$.errors.length()").value(4));
    }

    // ================================================================== A사로 B사

    @Test
    void A사_관리자는_B사_부서_코드_규칙_정책을_볼_수도_바꿀_수도_없다() throws Exception {
        String compB = accounts.newComp("SCX");
        long bDept = accounts.query(compB, """
                insert into sys_dept (comp_cd, dept_cd, dept_nm, created_by, updated_by)
                values (hb_current_comp(), 'BD', 'B사 부서', 't', 't') returning dept_id""", Long.class);
        accounts.exec(compB, """
                insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by)
                values (hb_current_comp(), 'DOWNTIME_REASON', 'BONLY', 'B사', 't', 't')""");
        accounts.exec(compB, """
                insert into sys_numbering_rule (comp_cd, rule_cd, rule_nm, pattern, created_by, updated_by)
                values (hb_current_comp(), 'B_RULE', 'B', 'B{seq:3}', 't', 't')""");
        accounts.exec(compB, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by)
                values (hb_current_comp(), 'GRID_PAGE_SIZE_MAX', '77', 't', 't')""");

        mvc.perform(json(put("/api/admin/depts/" + bDept), "{\"deptNm\":\"탈취\",\"useYn\":\"N\",\"rowVer\":0}").with(admin))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/api/admin/depts"), "{\"parentId\":" + bDept + ",\"deptCd\":\"X\",\"deptNm\":\"x\"}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        mvc.perform(json(put("/api/admin/users/" + workerId), userBodyDept("작업자", "[\"WORK\"]", bDept, userVer(workerId))).with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        mvc.perform(get("/api/admin/depts").with(admin)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(json(put("/api/admin/codes/DOWNTIME_REASON/BONLY"), "{\"codeNm\":\"x\",\"rowVer\":0}").with(admin))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/codes/DOWNTIME_REASON").with(admin))
                .andExpect(jsonPath("$.list[?(@.code=='BONLY')]").isEmpty());
        mvc.perform(json(put("/api/admin/numbering/B_RULE"), "{\"ruleNm\":\"x\",\"pattern\":\"B{seq:3}\",\"resetCycle\":\"NONE\",\"rowVer\":0}")
                .with(admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/policies").with(admin))
                .andExpect(jsonPath("$.list[?(@.policyKey=='GRID_PAGE_SIZE_MAX')].values[*]").isEmpty());
        mvc.perform(json(post("/api/admin/policies/GRID_PAGE_SIZE_MAX/reset"), "{\"rowVer\":0}").with(admin))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/company").with(admin)).andExpect(jsonPath("$.data.comp.compCd").value(comp));

        assertThat(accounts.query(compB, "select dept_nm || use_yn from sys_dept where dept_id = ?", String.class, bDept)).isEqualTo("B사 부서Y");
        assertThat(accounts.query(compB, "select policy_value from sys_policy where policy_key = 'GRID_PAGE_SIZE_MAX'", String.class))
                .isEqualTo("77");
        assertThat(accounts.query(compB, "select count(*) from sys_numbering_rule where rule_nm = 'B'", Integer.class)).isEqualTo(1);
    }

    // ================================================================== E: 화면 권한·B 입력

    @Test
    void 권한이_없으면_403_입력_경계는_칸_오류() throws Exception {
        for (String url : new String[] {"/api/admin/depts", "/api/admin/policies", "/api/admin/codes", "/api/admin/numbering",
            "/api/admin/company"}) {
            mvc.perform(get(url).with(worker)).andExpect(status().isForbidden());
        }
        mvc.perform(json(post("/api/admin/depts"), "{\"deptCd\":\"한글\",\"deptNm\":\"\",\"sortNo\":-1,\"useYn\":\"Q\"}").with(admin))
                .andExpect(jsonPath("$.errors.length()").value(4));
        createDept(admin, null, "DUP", "중복");
        mvc.perform(json(post("/api/admin/depts"), "{\"deptCd\":\"dup\",\"deptNm\":\"또\"}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        mvc.perform(json(post("/api/admin/depts"), "{\"deptCd\":\"NUL\",\"deptNm\":\"a\\u0000b\"}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        // 부서 선택지 API 는 화면 권한과 무관(참조 데이터) — 로그인만
        mvc.perform(get("/api/platform/depts").with(worker)).andExpect(status().isOk());
        mvc.perform(get("/api/platform/depts")).andExpect(status().isUnauthorized());
    }
}
