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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;

/**
 * 부서장 (docs/36 9.7 D-1) — 부서 화면의 장 지정 + 결재선 기본값 UserDirectory.deptHeadOf.
 * 이상 사용: 사용 중지·잠긴 사람·협력사 사용자·다른 회사 사람을 장으로(API·직접 SQL), 장이 나중에 퇴사(건너뜀),
 * 사용 중지 부서(건너뜀), 본인이 장(상위로), 10단 끝까지, 권한 없는 저장, A사로 B사
 */
class DeptHeadTest extends SysAdminTestSupport {

    @Autowired
    UserDirectory users;

    @Autowired
    TenantRunner runner;

    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void setUp() {
        setUpCompany("DH");
    }

    long dept(Long parent, String cd, Long head) throws Exception {
        MvcResult r = mvc.perform(json(post("/api/admin/depts"), "{\"parentId\":" + parent + ",\"deptCd\":\"" + cd
                + "\",\"deptNm\":\"" + cd + "\",\"useYn\":\"Y\",\"headUserId\":" + head + "}").with(admin))
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data")).longValue();
    }

    int ver(long deptId) {
        return accounts.query(comp, "select row_ver from sys_dept where dept_id = ?", Integer.class, deptId);
    }

    String body(Long parent, String nm, String useYn, Long head, long deptId) {
        return "{\"parentId\":" + parent + ",\"deptNm\":\"" + nm + "\",\"useYn\":\"" + useYn + "\",\"headUserId\":" + head
                + ",\"rowVer\":" + ver(deptId) + "}";
    }

    long user(String login, Long deptId) {
        long id = accounts.newUser(comp, login, PW);
        if (deptId != null) {
            accounts.exec(comp, "update sys_user set dept_id = ? where user_id = ?", deptId, id);
        }
        return id;
    }

    void member(long userId, long deptId) {
        accounts.exec(comp, "update sys_user set dept_id = ? where user_id = ?", deptId, userId);
    }

    Long headOf(String c, long userId) {
        UserOption o = runner.call(c, () -> users.deptHeadOf(userId));
        return o == null ? null : o.getUserId();
    }

    Long headOf(long userId) {
        return headOf(comp, userId);
    }

    static String constraintOf(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            return PgErrors.constraint(e).orElse("(none) " + e.getMessage());
        }
        return "(no error)";
    }

    @Test
    void 부서장은_소속부터_위로_본인이_장이면_상위의_장() throws Exception {
        long ceo = user("ceo", null);
        long plantHead = user("plant", null);
        long root = dept(null, "HQ", ceo);
        long plant = dept(root, "PLANT", plantHead);
        long line = dept(plant, "LINE1", null);   // 장 없음 → 위로
        member(ceo, root);
        member(plantHead, plant);
        long op = user("op", line);

        assertThat(headOf(op)).isEqualTo(plantHead);            // 자기 부서 장 없음 → 상위(공장)의 장
        assertThat(headOf(plantHead)).isEqualTo(ceo);           // 본인이 장 → 상위 부서의 장(자기 결재 방지)
        assertThat(headOf(ceo)).isNull();                       // 최상위의 장 본인 → 끝까지 없음
        assertThat(headOf(user("nodept", null))).isNull();      // 소속 없음

        // 화면 목록: 장 이름·아이디·사용 중
        mvc.perform(get("/api/admin/depts").with(admin))
                .andExpect(jsonPath("$.list[?(@.deptId==" + plant + ")].headLoginId").value(Matchers.hasItem("plant")))
                .andExpect(jsonPath("$.list[?(@.deptId==" + plant + ")].headActive").value(Matchers.hasItem(true)));
        mvc.perform(get("/api/admin/depts/head-candidates").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].loginId").value(Matchers.hasItems("ceo", "plant", "op")));

        // 장 바꾸기·비우기
        mvc.perform(json(put("/api/admin/depts/" + line), body(plant, "LINE1", "Y", op, line)).with(admin)).andExpect(status().isOk());
        long op2 = user("op2", line);
        assertThat(headOf(op2)).isEqualTo(op);
        assertThat(headOf(op)).isEqualTo(plantHead);
        mvc.perform(json(put("/api/admin/depts/" + line), body(plant, "LINE1", "Y", null, line)).with(admin)).andExpect(status().isOk());
        assertThat(headOf(op2)).isEqualTo(plantHead);
    }

    @Test
    void 장이_퇴사_잠김이거나_부서가_사용중지면_건너뛰고_다른_칸_저장은_막지_않는다() throws Exception {
        long ceo = user("ceo", null);
        long boss = user("boss", null);
        long root = dept(null, "HQ", ceo);
        long team = dept(root, "TEAM", boss);
        long op = user("op", team);
        assertThat(headOf(op)).isEqualTo(boss);

        // 장이 나중에 사용 중지(퇴사) — 행은 그대로, 기본값은 상위로
        accounts.exec(comp, "update sys_user set use_yn = 'N' where user_id = ?", boss);
        assertThat(headOf(op)).isEqualTo(ceo);
        mvc.perform(get("/api/admin/depts").with(admin))
                .andExpect(jsonPath("$.list[?(@.deptId==" + team + ")].headActive").value(Matchers.hasItem(false)));
        // 장을 그대로 두고 이름만 바꾸는 저장은 된다(조용히 지우지도, 막지도 않는다)
        mvc.perform(json(put("/api/admin/depts/" + team), body(root, "팀 이름 바꿈", "Y", boss, team)).with(admin))
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select head_user_id from sys_dept where dept_id = ?", Long.class, team)).isEqualTo(boss);

        // 관리자 잠금(무기한)도 건너뜀, 시도 횟수 잠금(기한 있음)은 사용 중으로 본다(결재선과 같은 규칙)
        accounts.exec(comp, "update sys_user set use_yn = 'Y', lock_yn = 'Y', locked_until = null where user_id = ?", boss);
        assertThat(headOf(op)).isEqualTo(ceo);
        accounts.exec(comp, "update sys_user set locked_until = now() + interval '15 minutes' where user_id = ?", boss);
        assertThat(headOf(op)).isEqualTo(boss);
        accounts.exec(comp, "update sys_user set lock_yn = 'N', locked_until = null where user_id = ?", boss);

        // 부서 사용 중지 — 소속은 남지만(Q10) 그 부서의 장은 건너뜀
        mvc.perform(json(put("/api/admin/depts/" + team), body(root, "TEAM", "N", boss, team)).with(admin)).andExpect(status().isOk());
        assertThat(headOf(op)).isEqualTo(ceo);
    }

    @Test
    void 사용_중이_아닌_사람_협력사_다른_회사_사람은_장이_될_수_없다_API_와_직접_SQL() throws Exception {
        long root = dept(null, "HQ", null);
        long off = user("off", null);
        accounts.exec(comp, "update sys_user set use_yn = 'N' where user_id = ?", off);
        long cust = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'S1', '한빛부품', 'PURCHASE', 't', 't') returning cust_id""", Long.class, comp);
        long sup = accounts.query(comp, """
                insert into sys_user (comp_cd, login_id, user_nm, pwd_hash, user_type, cust_id, created_by, updated_by)
                values (?, 'sup1', '협력사', ?, 'SUPPLIER', ?, 't', 't') returning user_id""", Long.class, comp, encoder.encode(PW), cust);
        String compB = accounts.newComp("DHB");
        long bUser = accounts.newUser(compB, "buser", PW);

        int n = 0;
        for (long bad : new long[] {off, sup, bUser, 999_999_999L}) {
            n++;
            mvc.perform(json(put("/api/admin/depts/" + root), body(null, "HQ", "Y", bad, root)).with(admin))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("headUserId"));
            mvc.perform(json(post("/api/admin/depts"), "{\"deptCd\":\"N" + n + "\",\"deptNm\":\"n\",\"useYn\":\"Y\",\"headUserId\":"
                    + bad + "}").with(admin)).andExpect(status().isBadRequest());
        }
        assertThat(accounts.query(comp, "select count(*) from sys_dept where head_user_id is not null", Integer.class)).isZero();

        // 직접 SQL — 트리거가 최종
        assertThat(constraintOf(() -> accounts.exec(comp, "update sys_dept set head_user_id = ? where dept_id = ?", off, root)))
                .isEqualTo("ck_sys_dept_head_use");
        assertThat(constraintOf(() -> accounts.exec(comp, "update sys_dept set head_user_id = ? where dept_id = ?", sup, root)))
                .isEqualTo("fk_sys_dept_head_comp");
        assertThat(constraintOf(() -> accounts.exec(comp, "update sys_dept set head_user_id = ? where dept_id = ?", bUser, root)))
                .isEqualTo("fk_sys_dept_head_comp");
    }

    @Test
    void A사로_B사_조회는_null_B사_부서는_못_고친다() throws Exception {
        long aBoss = user("aboss", null);
        long aRoot = dept(null, "HQ", aBoss);
        long aOp = user("aop", aRoot);

        String compB = accounts.newComp("DHB");
        long bBoss = accounts.newUser(compB, "bboss", PW);
        long bDept = accounts.query(compB, """
                insert into sys_dept (comp_cd, dept_cd, dept_nm, head_user_id, created_by, updated_by)
                values (hb_current_comp(), 'BHQ', 'B사', ?, 't', 't') returning dept_id""", Long.class, bBoss);
        long bOp = accounts.newUser(compB, "bop", PW);
        accounts.exec(compB, "update sys_user set dept_id = ? where user_id = ?", bDept, bOp);

        assertThat(headOf(compB, bOp)).isEqualTo(bBoss);
        assertThat(headOf(comp, bOp)).isNull();                 // A사 범위에서 B사 사람 → 없음
        assertThat(headOf(compB, aOp)).isNull();
        // A사 관리자가 B사 부서 id 로 장 지정 → 404, B사 행 그대로
        mvc.perform(json(put("/api/admin/depts/" + bDept), "{\"deptNm\":\"x\",\"useYn\":\"Y\",\"headUserId\":" + aBoss + ",\"rowVer\":0}")
                .with(admin)).andExpect(status().isNotFound());
        assertThat(accounts.query(compB, "select head_user_id from sys_dept where dept_id = ?", Long.class, bDept)).isEqualTo(bBoss);
        // A사 후보 목록에 B사 사람 없음
        mvc.perform(get("/api/admin/depts/head-candidates").with(admin))
                .andExpect(jsonPath("$.data[*].loginId").value(Matchers.not(Matchers.hasItem("bboss"))));
    }

    @Test
    void 열단_끝까지_올라가고_권한_없으면_403() throws Exception {
        long top = user("top", null);
        Long parent = dept(null, "D1", top);
        for (int i = 2; i <= 10; i++) {
            parent = dept(parent, "D" + i, null);
        }
        long deepest = user("deep", parent);
        assertThat(headOf(deepest)).isEqualTo(top);   // 9단 위의 장까지

        RequestPostProcessor nobody = worker;          // sys-dept 권한 없음
        mvc.perform(get("/api/admin/depts/head-candidates").with(nobody)).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/admin/depts/" + parent), body(null, "x", "Y", workerId, parent)).with(nobody))
                .andExpect(status().isForbidden());
        assertThat(accounts.query(comp, "select head_user_id from sys_dept where dept_id = ?", Long.class, parent)).isNull();
    }
}
