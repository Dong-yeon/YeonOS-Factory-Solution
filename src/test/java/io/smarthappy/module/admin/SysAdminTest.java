package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

/** 시스템관리 1단계 정상 흐름 (docs/36) — 사용자·권한그룹·메뉴, 관리자 그룹, 임시 비밀번호·변경 강제·세션 끊기 */
class SysAdminTest extends SysAdminTestSupport {

    @BeforeEach
    void setUp() {
        setUpCompany("SA");
    }

    // ---------- 관리자 그룹 (docs/36 D2) ----------

    @Test
    void 관리자_그룹은_권한_행_없이_모든_화면의_모든_액션을_가진다() throws Exception {
        assertThat(accounts.query(comp, "select count(*) from sys_auth_prog where auth_grp_cd = 'ADMIN'", Integer.class))
                .isZero();
        mvc.perform(get("/api/admin/users").with(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/master/customers").with(admin)).andExpect(status().isOk());
        mvc.perform(get("/page/sys-menu").with(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/platform/menu").with(admin)).andExpect(status().isOk());
        // 그룹 사용 중지면 관리자 권한도 없다
        long other = accounts.newUser(comp, "admin2", PW);
        adminGroup(comp, "ADMIN2", other);
        accounts.exec(comp, "update sys_auth_group set use_yn = 'N' where auth_grp_cd = 'ADMIN2'");
        mvc.perform(get("/api/admin/users").with(accounts.as(comp, other, "admin2"))).andExpect(status().isForbidden());
    }

    // ---------- 사용자 추가 → 임시 비밀번호 → 변경 강제 (D1) ----------

    @Test
    void 사용자_추가_임시_비밀번호로_로그인하면_변경_전에는_다른_기능이_막힌다() throws Exception {
        MvcResult r = mvc.perform(json(post("/api/admin/users"), userBody("newbie", "신입", "[\"WORK\"]", "Y", null))
                        .with(admin))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.loginId").value("newbie")).andReturn();
        String temp = JsonPath.read(r.getResponse().getContentAsString(), "$.data.tempPassword");
        assertThat(temp).hasSize(16);
        assertThat(accounts.query(comp, "select pwd_change_req_yn from sys_user where login_id = 'newbie'", String.class))
                .isEqualTo("Y");
        assertThat(accounts.query(comp, "select pwd_hash from sys_user where login_id = 'newbie'", String.class))
                .startsWith("{bcrypt}").doesNotContain(temp);

        MockHttpSession s = login("newbie", temp);
        mvc.perform(get("/main").session(s)).andExpect(redirectedUrl("/password-change"));
        mvc.perform(get("/page/mst-cust").session(s)).andExpect(redirectedUrl("/password-change"));
        mvc.perform(get("/api/master/customers").session(s)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(get("/api/platform/session").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pwdChangeRequired").value(true));
        mvc.perform(get("/password-change").session(s)).andExpect(status().isOk());

        // 틀린 현재 비밀번호 · 정책 위반 · 정상
        mvc.perform(json(post("/api/platform/password-change"), "{\"currentPassword\":\"wrong-one-123\",\"newPassword\":\"Calm-harbor-2026\"}")
                .session(s)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"));
        mvc.perform(json(post("/api/platform/password-change"), "{\"currentPassword\":\"" + temp + "\",\"newPassword\":\"short\"}")
                .session(s)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_POLICY"));
        mvc.perform(json(post("/api/platform/password-change"), "{\"currentPassword\":\"" + temp + "\",\"newPassword\":\"Calm-harbor-2026\"}")
                .session(s)).andExpect(status().isOk());

        // 같은 세션에서 바로 정상 사용
        mvc.perform(get("/api/master/customers").session(s)).andExpect(status().isOk());
        mvc.perform(get("/password-change").session(s)).andExpect(redirectedUrl("/main"));
        assertThat(accounts.query(comp, "select pwd_change_req_yn from sys_user where login_id = 'newbie'", String.class))
                .isEqualTo("N");
        login("newbie", "Calm-harbor-2026");
    }

    // ---------- 소속 변경은 다음 요청부터 ----------

    @Test
    void 소속을_바꾸면_그_사용자의_다음_요청부터_반영된다() throws Exception {
        mvc.perform(get("/api/master/customers").with(worker)).andExpect(status().isOk());
        mvc.perform(json(put("/api/admin/users/" + workerId), userBody(null, "작업자", "[]", "Y", userVer(workerId)))
                .with(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/master/customers").with(worker)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users/" + workerId).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authGrpCds.length()").value(0))
                .andExpect(jsonPath("$.data.manageable").value(true));
        // 감사: 소속 행 삭제가 남는다(행위자 = 관리자)
        assertThat(accounts.query(comp, """
                select actor from sys_audit_log where table_nm = 'sys_user_auth' and action = 'DELETE'""", String.class))
                .isEqualTo("u" + adminId + ":admin");
    }

    // ---------- 비밀번호 초기화 → 기존 세션 끊김 (D4) ----------

    @Test
    void 비밀번호_초기화는_기존_세션을_끊고_임시_비밀번호로만_로그인된다() throws Exception {
        MockHttpSession ws = login("worker", PW);
        mvc.perform(get("/api/master/customers").session(ws)).andExpect(status().isOk());
        Thread.sleep(5);   // 로그인 시각(ms) < 초기화 시각
        MvcResult r = mvc.perform(json(post("/api/admin/users/" + workerId + "/reset-password"),
                        "{\"rowVer\":" + userVer(workerId) + "}").with(admin))
                .andExpect(status().isOk()).andReturn();
        String temp = JsonPath.read(r.getResponse().getContentAsString(), "$.data.tempPassword");
        mvc.perform(get("/api/master/customers").session(ws)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        mvc.perform(post("/login").with(csrf()).param("compCd", comp).param("loginId", "worker").param("password", PW))
                .andExpect(redirectedUrl("/login?error=bad"));
        MockHttpSession ns = login("worker", temp);
        mvc.perform(get("/api/master/customers").session(ns)).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        // 접속 로그(대상)와 감사(해시는 가림)
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'PASSWORD_RESET'"
                + " and detail like 'user=" + workerId + ":%'", Integer.class)).isEqualTo(1);
        assertThat(accounts.query(comp, """
                select after_data->>'pwd_hash' from sys_audit_log
                 where table_nm = 'sys_user' and row_key = ? and action = 'UPDATE' order by audit_id desc limit 1""",
                String.class, String.valueOf(workerId))).isEqualTo("(changed)");
    }

    @Test
    void 초기화는_실패_잠금을_풀고_관리자_잠금은_그대로_둔다() throws Exception {
        accounts.exec(comp, "update sys_user set lock_yn = 'Y', locked_until = now() + interval '10 minutes', login_fail_cnt = 5 where user_id = ?", workerId);
        mvc.perform(json(post("/api/admin/users/" + workerId + "/reset-password"), "{\"rowVer\":" + userVer(workerId) + "}")
                .with(admin)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select lock_yn || login_fail_cnt from sys_user where user_id = ?", String.class, workerId))
                .isEqualTo("N0");
        accounts.exec(comp, "update sys_user set lock_yn = 'Y', locked_until = null where user_id = ?", workerId);
        mvc.perform(json(post("/api/admin/users/" + workerId + "/reset-password"), "{\"rowVer\":" + userVer(workerId) + "}")
                .with(admin)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select lock_yn from sys_user where user_id = ?", String.class, workerId)).isEqualTo("Y");
    }

    // ---------- 잠금·해제 (D3) ----------

    @Test
    void 관리자_잠금은_세션을_끊고_해제하면_다시_로그인된다() throws Exception {
        MockHttpSession ws = login("worker", PW);
        mvc.perform(json(post("/api/admin/users/" + workerId + "/lock"), "{\"rowVer\":" + userVer(workerId) + "}").with(admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/master/customers").session(ws)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/users").param("lockState", "MANUAL").with(admin))
                .andExpect(jsonPath("$.list.length()").value(1)).andExpect(jsonPath("$.list[0].loginId").value("worker"));
        mvc.perform(json(post("/api/admin/users/" + workerId + "/unlock"), "{\"rowVer\":" + userVer(workerId) + "}").with(admin))
                .andExpect(status().isOk());
        login("worker", PW);
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd in ('USER_LOCKED', 'USER_UNLOCKED')",
                Integer.class)).isEqualTo(2);
        // 잠금 칸은 감사 무시 컬럼이지만 row_ver 가 올라 행위자·시각이 남는다
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'sys_user' and row_key = ?"
                + " and actor = ?", Integer.class, String.valueOf(workerId), "u" + adminId + ":admin")).isEqualTo(2);
    }

    // ---------- 권한그룹 ----------

    @Test
    void 권한그룹을_만들고_권한_표를_저장하면_구성원에게_반영된다() throws Exception {
        mvc.perform(json(post("/api/admin/auth-groups"), "{\"authGrpCd\":\"qc\",\"authGrpNm\":\"품질\",\"sortNo\":5,"
                + "\"homeProgCd\":\"mst-cust\",\"adminYn\":\"N\",\"useYn\":\"Y\"}").with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("QC"));
        long q = accounts.newUser(comp, "qcuser", PW);
        mvc.perform(json(put("/api/admin/users/" + q), userBody(null, "품질담당", "[\"QC\"]", "Y", userVer(q))).with(admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/master/customers").with(accounts.as(comp, q, "qcuser"))).andExpect(status().isForbidden());

        mvc.perform(json(put("/api/admin/auth-groups/QC/perms?rowVer=" + groupVer("QC")),
                        "{\"items\":[{\"progCd\":\"mst-cust\",\"actions\":[\"SEARCH\",\"EXCEL\"]}]}").with(admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/master/customers").with(accounts.as(comp, q, "qcuser"))).andExpect(status().isOk());
        mvc.perform(get("/api/platform/home").with(accounts.as(comp, q, "qcuser"))).andExpect(jsonPath("$.data").value("mst-cust"));
        mvc.perform(get("/api/admin/auth-groups/QC").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.perms[?(@.progCd=='mst-cust')].granted[*]").value(org.hamcrest.Matchers.containsInAnyOrder("SEARCH", "EXCEL")))
                .andExpect(jsonPath("$.data.members[0].loginId").value("qcuser"));

        // 한 벌 저장: 빠진 화면 = 권한 없음
        mvc.perform(json(put("/api/admin/auth-groups/QC/perms?rowVer=" + groupVer("QC")), "{\"items\":[]}").with(admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/master/customers").with(accounts.as(comp, q, "qcuser"))).andExpect(status().isForbidden());
    }

    @Test
    void 권한_표는_화면이_정의한_액션만_받는다() throws Exception {
        mvc.perform(json(put("/api/admin/auth-groups/WORK/perms?rowVer=" + groupVer("WORK")),
                "{\"items\":[{\"progCd\":\"mst-cust\",\"actions\":[\"APPROVE\"]}]}").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/admin/auth-groups/WORK/perms?rowVer=" + groupVer("WORK")),
                "{\"items\":[{\"progCd\":\"no-such-prog\",\"actions\":[\"SEARCH\"]}]}").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/admin/auth-groups/WORK/perms?rowVer=" + groupVer("WORK")),
                "{\"items\":[{\"progCd\":\"mst-cust\",\"actions\":[\"SEARCH\"]},{\"progCd\":\"mst-cust\",\"actions\":[\"SAVE\"]}]}")
                .with(admin)).andExpect(status().isBadRequest());
        assertThat(accounts.query(comp, "select count(*) from sys_auth_prog where auth_grp_cd = 'WORK'", Integer.class)).isEqualTo(2);
    }

    // ---------- 메뉴 ----------

    @Test
    void 메뉴_폴더와_화면을_추가하고_옮기고_숨긴다() throws Exception {
        MvcResult f = mvc.perform(json(post("/api/admin/menus"), "{\"menuNm\":\"인사\",\"sortNo\":10,\"useYn\":\"Y\"}").with(admin))
                .andExpect(status().isOk()).andReturn();
        long folder = ((Number) JsonPath.read(f.getResponse().getContentAsString(), "$.data")).longValue();
        MvcResult p = mvc.perform(json(post("/api/admin/menus"), "{\"parentId\":" + folder
                + ",\"progCd\":\"sys-user\",\"sortNo\":1,\"useYn\":\"Y\"}").with(admin)).andExpect(status().isOk()).andReturn();
        long menu = ((Number) JsonPath.read(p.getResponse().getContentAsString(), "$.data")).longValue();
        mvc.perform(get("/api/platform/menu").with(admin))
                .andExpect(jsonPath("$.data[?(@.name=='인사')].children[0].progCd").value(org.hamcrest.Matchers.hasItem("sys-user")));
        mvc.perform(get("/api/admin/menus").with(admin)).andExpect(jsonPath("$.list[?(@.menuId==" + menu + ")].lvl")
                .value(org.hamcrest.Matchers.hasItem(2)));

        // 하위 폴더로 옮기기 → 숨김
        MvcResult f2 = mvc.perform(json(post("/api/admin/menus"), "{\"parentId\":" + folder + ",\"menuNm\":\"하위\",\"useYn\":\"Y\"}")
                .with(admin)).andReturn();
        long sub = ((Number) JsonPath.read(f2.getResponse().getContentAsString(), "$.data")).longValue();
        int ver = accounts.query(comp, "select row_ver from sys_menu where menu_id = ?", Integer.class, menu);
        mvc.perform(json(put("/api/admin/menus/" + menu), "{\"parentId\":" + sub + ",\"progCd\":\"sys-user\",\"useYn\":\"Y\",\"rowVer\":"
                + ver + "}").with(admin)).andExpect(status().isOk());
        ver = accounts.query(comp, "select row_ver from sys_menu where menu_id = ?", Integer.class, sub);
        mvc.perform(json(put("/api/admin/menus/" + sub), "{\"parentId\":" + folder + ",\"menuNm\":\"하위\",\"useYn\":\"N\",\"rowVer\":"
                + ver + "}").with(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/platform/menu").with(admin)).andExpect(jsonPath("$.data[?(@.name=='인사')]").isEmpty());
        mvc.perform(get("/api/admin/menus").with(admin)).andExpect(jsonPath("$.list[?(@.menuId==" + menu + ")].dim")
                .value(org.hamcrest.Matchers.hasItem(true)));
    }

    @Test
    void 사용자_목록_조회_조건() throws Exception {
        mvc.perform(get("/api/admin/users").param("keyword", "work").with(admin))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.list[0].groupNames").value("WORK 그룹"));
        mvc.perform(get("/api/admin/users").param("authGrpCd", "HR").with(admin)).andExpect(jsonPath("$.list[0].loginId").value("hr"));
        mvc.perform(get("/api/admin/users").param("keyword", "%").with(admin)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/admin/users").param("lockState", "BAD").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/users").param("sort", "pwdHash,asc").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/users/columns").with(admin)).andExpect(jsonPath("$.list[0].field").value("loginId"));
    }
}
