package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.common.db.PgErrors;

/**
 * 시스템관리 1단계 이상 사용 (docs/36 6장) — E 자기 권한·부여 상한·마지막 관리자, C 동시, A사로 B사, 직접 SQL, B 입력, F 낡은 버전
 */
class SysAdminAbuseTest extends SysAdminTestSupport {

    @BeforeEach
    void setUp() {
        setUpCompany("SB");
    }

    /** 직접 SQL 이 막힌 제약·트리거 이름 (메시지가 아니라 PgErrors — 서버 언어와 무관) */
    private static String constraintOf(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            return PgErrors.constraint(e).orElse("(none) " + e.getMessage());
        }
        return "(no error)";
    }

    private int denied() {
        return accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'GRANT_DENIED'", Integer.class);
    }

    // ---------- E: 자기 권한 ----------

    @Test
    void 자기_소속_사용중지_잠금_초기화는_거부된다() throws Exception {
        mvc.perform(json(put("/api/admin/users/" + adminId), userBody(null, "관리자", "[]", "Y", userVer(adminId))).with(admin))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SELF_CHANGE"));
        mvc.perform(json(put("/api/admin/users/" + adminId), userBody(null, "관리자", "[\"ADMIN\",\"HR\"]", "Y", userVer(adminId)))
                .with(admin)).andExpect(jsonPath("$.code").value("SELF_CHANGE"));
        mvc.perform(json(put("/api/admin/users/" + adminId), userBody(null, "관리자", "[\"ADMIN\"]", "N", userVer(adminId)))
                .with(admin)).andExpect(jsonPath("$.code").value("SELF_CHANGE"));
        mvc.perform(json(post("/api/admin/users/" + adminId + "/lock"), "{\"rowVer\":" + userVer(adminId) + "}").with(admin))
                .andExpect(jsonPath("$.code").value("SELF_CHANGE"));
        mvc.perform(json(post("/api/admin/users/" + adminId + "/reset-password"), "{\"rowVer\":" + userVer(adminId) + "}")
                .with(admin)).andExpect(jsonPath("$.code").value("SELF_CHANGE"));
        // 부관리자가 자기에게 관리자 그룹을 붙이는 것도(상승) 자기 변경으로 먼저 막힌다
        mvc.perform(json(put("/api/admin/users/" + hrId), userBody(null, "인사", "[\"HR\",\"ADMIN\"]", "Y", userVer(hrId))).with(hr))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SELF_CHANGE"));
        assertThat(denied()).isEqualTo(6);
        assertThat(accounts.query(comp, "select count(*) from sys_user_auth where user_id = ?", Integer.class, hrId)).isEqualTo(1);
        // 이름·메일 같은 자기 정보는 된다
        mvc.perform(json(put("/api/admin/users/" + adminId), userBody(null, "새이름", "[\"ADMIN\"]", "Y", userVer(adminId))).with(admin))
                .andExpect(status().isOk());
    }

    // ---------- E: 부여 상한 ----------

    @Test
    void 부관리자는_자기보다_큰_그룹을_주거나_큰_사용자를_다룰_수_없다() throws Exception {
        int before = denied();
        // 관리자 그룹 부여
        mvc.perform(json(put("/api/admin/users/" + workerId), userBody(null, "작업자", "[\"WORK\",\"ADMIN\"]", "Y", userVer(workerId)))
                .with(hr)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GRANT_EXCEEDS"));
        // 내가 없는 화면 권한(sys-menu)을 가진 그룹 부여
        accounts.grant(comp, adminId, "MENUMAN", "sys-menu", "SEARCH", "SAVE");
        mvc.perform(json(put("/api/admin/users/" + workerId), userBody(null, "작업자", "[\"WORK\",\"MENUMAN\"]", "Y", userVer(workerId)))
                .with(hr)).andExpect(jsonPath("$.code").value("GRANT_EXCEEDS"));
        // 새 사용자를 큰 그룹으로
        mvc.perform(json(post("/api/admin/users"), userBody("sneaky", "몰래", "[\"ADMIN\"]", "Y", null)).with(hr))
                .andExpect(jsonPath("$.code").value("GRANT_EXCEEDS"));
        assertThat(accounts.query(comp, "select count(*) from sys_user where login_id = 'sneaky'", Integer.class)).isZero();
        // 나보다 큰 사용자: 초기화(계정 탈취)·잠금·해제·수정
        mvc.perform(json(post("/api/admin/users/" + adminId + "/reset-password"), "{\"rowVer\":" + userVer(adminId) + "}")
                .with(hr)).andExpect(jsonPath("$.code").value("GRANT_EXCEEDS"));
        mvc.perform(json(post("/api/admin/users/" + adminId + "/lock"), "{\"rowVer\":" + userVer(adminId) + "}").with(hr))
                .andExpect(jsonPath("$.code").value("GRANT_EXCEEDS"));
        mvc.perform(json(put("/api/admin/users/" + adminId), userBody(null, "바꿈", "[\"ADMIN\"]", "N", userVer(adminId))).with(hr))
                .andExpect(jsonPath("$.code").value("GRANT_EXCEEDS"));
        // 그룹: 관리자 그룹 만들기·관리자 그룹 고치기·내 그룹에 없는 권한 추가
        mvc.perform(json(post("/api/admin/auth-groups"), "{\"authGrpCd\":\"BOSS\",\"authGrpNm\":\"보스\",\"adminYn\":\"Y\"}").with(hr))
                .andExpect(jsonPath("$.code").value("GRANT_EXCEEDS"));
        mvc.perform(json(put("/api/admin/auth-groups/ADMIN"), "{\"authGrpNm\":\"관리자\",\"adminYn\":\"N\",\"useYn\":\"Y\",\"rowVer\":"
                + groupVer("ADMIN") + "}").with(hr)).andExpect(jsonPath("$.code").value("GRANT_EXCEEDS"));
        mvc.perform(json(put("/api/admin/auth-groups/HR/perms?rowVer=" + groupVer("HR")),
                "{\"items\":[{\"progCd\":\"sys-user\",\"actions\":[\"SEARCH\",\"SAVE\",\"FN1\",\"FN2\"]},"
                        + "{\"progCd\":\"sys-auth-group\",\"actions\":[\"SEARCH\",\"SAVE\"]},"
                        + "{\"progCd\":\"sys-menu\",\"actions\":[\"SEARCH\",\"SAVE\"]}]}").with(hr))
                .andExpect(jsonPath("$.code").value("GRANT_EXCEEDS"));
        mvc.perform(json(put("/api/admin/auth-groups/MENUMAN/perms?rowVer=" + groupVer("MENUMAN")), "{\"items\":[]}").with(hr))
                .andExpect(jsonPath("$.code").value("GRANT_EXCEEDS"));   // 큰 그룹은 줄이기도 안 됨
        assertThat(denied() - before).isEqualTo(10);
        assertThat(accounts.query(comp, "select count(*) from sys_auth_prog where auth_grp_cd = 'HR'", Integer.class)).isEqualTo(8);
        assertThat(accounts.query(comp, "select admin_yn from sys_auth_group where auth_grp_cd = 'ADMIN'", String.class)).isEqualTo("Y");

        // 내 권한 안은 된다: 작업자 그룹 부여·작업자 초기화·작업자 그룹 권한 줄이기
        mvc.perform(json(put("/api/admin/users/" + workerId), userBody(null, "작업자", "[\"WORK\"]", "Y", userVer(workerId))).with(hr))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/admin/users/" + workerId + "/reset-password"), "{\"rowVer\":" + userVer(workerId) + "}").with(hr))
                .andExpect(status().isOk());
        mvc.perform(json(put("/api/admin/auth-groups/WORK/perms?rowVer=" + groupVer("WORK")),
                "{\"items\":[{\"progCd\":\"mst-cust\",\"actions\":[\"SEARCH\"]}]}").with(hr)).andExpect(status().isOk());
        // 화면 편의 값도 서버 판정과 같다
        mvc.perform(get("/api/admin/users/" + adminId).with(hr)).andExpect(jsonPath("$.data.manageable").value(false))
                .andExpect(jsonPath("$.data.groups[?(@.authGrpCd=='ADMIN')].grantable").value(org.hamcrest.Matchers.hasItem(false)));
    }

    @Test
    void 사용중지된_큰_그룹에_속한_사용자도_큰_사용자로_본다() throws Exception {
        long sleeper = accounts.newUser(comp, "sleeper", PW);
        adminGroup(comp, "OLDADMIN", sleeper);
        accounts.exec(comp, "update sys_auth_group set use_yn = 'N' where auth_grp_cd = 'OLDADMIN'");
        // 지금은 권한이 없지만 그룹이 다시 켜지면 관리자 — 부관리자가 비밀번호를 가로채면 안 된다
        mvc.perform(json(post("/api/admin/users/" + sleeper + "/reset-password"), "{\"rowVer\":" + userVer(sleeper) + "}").with(hr))
                .andExpect(jsonPath("$.code").value("GRANT_EXCEEDS"));
    }

    // ---------- E: 마지막 관리자 ----------

    @Test
    void 마지막_관리자를_없애는_변경은_409() throws Exception {
        mvc.perform(json(put("/api/admin/auth-groups/ADMIN"), "{\"authGrpNm\":\"관리자\",\"adminYn\":\"N\",\"useYn\":\"Y\",\"rowVer\":"
                + groupVer("ADMIN") + "}").with(admin)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMIN"));
        mvc.perform(json(put("/api/admin/auth-groups/ADMIN"), "{\"authGrpNm\":\"관리자\",\"adminYn\":\"Y\",\"useYn\":\"N\",\"rowVer\":"
                + groupVer("ADMIN") + "}").with(admin)).andExpect(jsonPath("$.code").value("LAST_ADMIN"));
        assertThat(accounts.query(comp, "select admin_yn || use_yn from sys_auth_group where auth_grp_cd = 'ADMIN'", String.class))
                .isEqualTo("YY");

        // 관리자가 둘이면 다른 관리자를 잠그고 끌 수 있다 — 그러면 남은 한 명은 다시 보호된다
        long a2 = accounts.newUser(comp, "admin2", PW);
        adminGroup(comp, "ADMIN", a2);
        mvc.perform(json(post("/api/admin/users/" + a2 + "/lock"), "{\"rowVer\":" + userVer(a2) + "}").with(admin))
                .andExpect(status().isOk());
        RequestPostProcessor second = accounts.as(comp, a2, "admin2");
        mvc.perform(get("/api/admin/users").with(second)).andExpect(status().isUnauthorized());   // 잠긴 관리자 세션은 끊김
        mvc.perform(json(put("/api/admin/auth-groups/ADMIN"), "{\"authGrpNm\":\"관리자\",\"adminYn\":\"N\",\"useYn\":\"Y\",\"rowVer\":"
                + groupVer("ADMIN") + "}").with(admin)).andExpect(jsonPath("$.code").value("LAST_ADMIN"));
    }

    // ---------- C: 동시 — 두 관리자가 서로를 잠금 ----------

    @Test
    void 두_관리자가_동시에_서로를_잠가도_관리자는_한_명_남는다() throws Exception {
        long a2 = accounts.newUser(comp, "admin2", PW);
        adminGroup(comp, "ADMIN", a2);
        RequestPostProcessor second = accounts.as(comp, a2, "admin2");
        int v1 = userVer(adminId);
        int v2 = userVer(a2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            results.add(pool.submit(() -> {
                go.await();
                return mvc.perform(json(post("/api/admin/users/" + a2 + "/lock"), "{\"rowVer\":" + v2 + "}").with(admin))
                        .andReturn().getResponse().getStatus();
            }));
            results.add(pool.submit(() -> {
                go.await();
                return mvc.perform(json(post("/api/admin/users/" + adminId + "/lock"), "{\"rowVer\":" + v1 + "}").with(second))
                        .andReturn().getResponse().getStatus();
            }));
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : results) {
                codes.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(codes).contains(200);
            assertThat(codes.stream().filter(c -> c == 200).count()).isEqualTo(1);   // 다른 쪽은 409(LAST_ADMIN) 또는 401(먼저 잠김)
        } finally {
            pool.shutdownNow();
        }
        assertThat(accounts.query(comp, """
                select count(*) from sys_user where lock_yn = 'Y' and locked_until is null""", Integer.class)).isEqualTo(1);
    }

    // ---------- E: 권한 조합 ----------

    @Test
    void 액션별_권한이_없으면_403() throws Exception {
        mvc.perform(get("/api/admin/users").with(worker)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/menus").with(hr)).andExpect(status().isForbidden());
        long viewer = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewer, "VIEW", "sys-user", "SEARCH", "SAVE");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer");
        mvc.perform(get("/api/admin/users").with(v)).andExpect(status().isOk());
        mvc.perform(json(post("/api/admin/users/" + workerId + "/reset-password"), "{\"rowVer\":" + userVer(workerId) + "}").with(v))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_PERMISSION"));
        mvc.perform(json(post("/api/admin/users/" + workerId + "/unlock"), "{\"rowVer\":" + userVer(workerId) + "}").with(v))
                .andExpect(jsonPath("$.code").value("NO_PERMISSION"));
        // 화면 버튼도 권한에 맞게 (판정은 서버)
        String html = mvc.perform(get("/page/sys-user").with(v)).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("id=\"saveBtn\"").doesNotContain("id=\"resetBtn\"").doesNotContain("id=\"lockBtn\"");
    }

    // ---------- A사로 B사 ----------

    @Test
    void A사_관리자는_B사_사용자_그룹_메뉴를_볼_수도_바꿀_수도_없다() throws Exception {
        String compB = accounts.newComp("SBB");
        long bUser = accounts.newUser(compB, "buser", PW);
        accounts.grant(compB, bUser, "BONLY", "mst-cust", "SEARCH");
        long bFolder = accounts.addMenu(compB, null, "B폴더", null, 1);

        mvc.perform(get("/api/admin/users/" + bUser).with(admin)).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/admin/users/" + bUser), userBody(null, "탈취", "[]", "N", 0)).with(admin))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/api/admin/users/" + bUser + "/reset-password"), "{\"rowVer\":0}").with(admin))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/api/admin/users/" + bUser + "/lock"), "{\"rowVer\":0}").with(admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/auth-groups/BONLY").with(admin)).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/admin/auth-groups/BONLY/perms?rowVer=0"), "{\"items\":[]}").with(admin))
                .andExpect(status().isNotFound());
        mvc.perform(json(put("/api/admin/menus/" + bFolder), "{\"menuNm\":\"탈취\",\"useYn\":\"N\",\"rowVer\":0}").with(admin))
                .andExpect(status().isNotFound());
        // 내 사용자에 B사 그룹 코드 · 내 메뉴를 B사 폴더 아래로
        mvc.perform(json(put("/api/admin/users/" + workerId), userBody(null, "작업자", "[\"BONLY\"]", "Y", userVer(workerId)))
                .with(admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("authGrpCds"));
        mvc.perform(json(post("/api/admin/menus"), "{\"parentId\":" + bFolder + ",\"menuNm\":\"x\",\"useYn\":\"Y\"}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        mvc.perform(get("/api/admin/users").param("keyword", "buser").with(admin)).andExpect(jsonPath("$.total").value(0));

        assertThat(accounts.query(compB, "select user_nm || use_yn || lock_yn from sys_user where user_id = ?", String.class, bUser))
                .isEqualTo("buser 사용자YN");
        assertThat(accounts.query(compB, "select count(*) from sys_auth_prog where auth_grp_cd = 'BONLY'", Integer.class)).isEqualTo(1);
    }

    // ---------- 직접 SQL ----------

    @Test
    void 직접_SQL_로도_다른_회사_소속_메뉴_순환_깊이_폴더_규칙은_막힌다() {
        String compB = accounts.newComp("SBC");
        long bUser = accounts.newUser(compB, "buser", PW);
        long bFolder = accounts.addMenu(compB, null, "B폴더", null, 1);
        // 다른 회사 사용자를 내 그룹에 (FK 는 RLS 를 무시 → 트리거)
        assertThat(constraintOf(() -> accounts.exec(comp,
                "insert into sys_user_auth (comp_cd, user_id, auth_grp_cd, created_by) values (?, ?, 'WORK', 'x')", comp, bUser))).isEqualTo("fk_sys_user_auth_user_comp");
        assertThat(constraintOf(() -> accounts.addMenu(comp, bFolder, "x", null, 1))).isEqualTo("fk_sys_menu_parent_comp");

        long a = accounts.addMenu(comp, null, "A", null, 1);
        long b = accounts.addMenu(comp, a, "B", null, 1);
        long c = accounts.addMenu(comp, b, "C", null, 1);
        assertThat(constraintOf(() -> accounts.exec(comp, "update sys_menu set parent_id = ? where menu_id = ?", c, a))).isEqualTo("ck_sys_menu_cycle");
        long d = accounts.addMenu(comp, c, "D", null, 1);
        long e = accounts.addMenu(comp, d, "E", null, 1);   // 5단
        assertThat(constraintOf(() -> accounts.addMenu(comp, e, "F", null, 1))).isEqualTo("ck_sys_menu_depth");
        long other = accounts.addMenu(comp, null, "X", null, 1);
        long otherChild = accounts.addMenu(comp, other, "Y", null, 1);
        // 하위 높이까지: 2단짜리 X 를 4단 D 아래로 → 6단
        assertThat(constraintOf(() -> accounts.exec(comp, "update sys_menu set parent_id = ? where menu_id = ?", d, other))).isEqualTo("ck_sys_menu_depth");
        long prog = accounts.addMenu(comp, a, null, "mst-cust", 1);
        assertThat(constraintOf(() -> accounts.addMenu(comp, prog, "under-prog", null, 1))).isEqualTo("ck_sys_menu_parent_folder");
        assertThat(constraintOf(() -> accounts.exec(comp, "update sys_menu set prog_cd = 'mst-cust' where menu_id = ?", other))).isEqualTo("ck_sys_menu_has_children");
        assertThat(otherChild).isPositive();
        // 관리자 표시 값은 Y/N 만
        assertThat(constraintOf(() -> accounts.exec(comp, "update sys_auth_group set admin_yn = 'X' where auth_grp_cd = 'WORK'"))).isEqualTo("ck_sys_auth_group_admin_yn");
    }

    @Test
    void 화면_API_는_트리거_제약을_칸_오류로_돌려준다() throws Exception {
        long a = accounts.addMenu(comp, null, "A", null, 1);
        long b = accounts.addMenu(comp, a, "B", null, 1);
        int ver = accounts.query(comp, "select row_ver from sys_menu where menu_id = ?", Integer.class, a);
        mvc.perform(json(put("/api/admin/menus/" + a), "{\"parentId\":" + b + ",\"menuNm\":\"A\",\"useYn\":\"Y\",\"rowVer\":" + ver + "}")
                .with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("parentId")).andExpect(jsonPath("$.errors[0].code").value("MENU_CYCLE"));
        mvc.perform(json(put("/api/admin/menus/" + a), "{\"parentId\":" + a + ",\"menuNm\":\"A\",\"useYn\":\"Y\",\"rowVer\":" + ver + "}")
                .with(admin)).andExpect(jsonPath("$.errors[0].code").value("MENU_CYCLE"));
        // 폴더를 화면으로(종류 고정)·화면 메뉴 아래 넣기
        mvc.perform(json(put("/api/admin/menus/" + a), "{\"progCd\":\"mst-cust\",\"menuNm\":\"A\",\"useYn\":\"Y\",\"rowVer\":" + ver + "}")
                .with(admin)).andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        long prog = accounts.addMenu(comp, a, null, "mst-cust", 1);
        mvc.perform(json(post("/api/admin/menus"), "{\"parentId\":" + prog + ",\"menuNm\":\"x\",\"useYn\":\"Y\"}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("MENU_PARENT"));
    }

    // ---------- B: 입력 경계 ----------

    @Test
    void 사용자_입력_경계() throws Exception {
        mvc.perform(json(post("/api/admin/users"), userBody("한글아이디", "x", "[]", "Y", null)).with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[?(@.field=='loginId')].code").value(org.hamcrest.Matchers.hasItem("FORMAT")));
        mvc.perform(json(post("/api/admin/users"), userBody("a", "x", "[]", "Y", null)).with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        mvc.perform(json(post("/api/admin/users"), userBody("x".repeat(51), "x", "[]", "Y", null)).with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        mvc.perform(json(post("/api/admin/users"), userBody("WORKER", "x", "[]", "Y", null)).with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));   // 대소문자 무시 유일
        mvc.perform(json(post("/api/admin/users"), userBody("ok.user", "a\\u0000b", "[]", "Y", null)).with(admin))
                .andExpect(jsonPath("$.errors[?(@.field=='userNm')].code").value(org.hamcrest.Matchers.hasItem("FORMAT")));
        mvc.perform(json(post("/api/admin/users"), "{\"loginId\":\"ok.user\",\"userNm\":\" \",\"email\":\"bad\",\"localeCd\":\"xx\","
                + "\"useYn\":\"Q\",\"authGrpCds\":[\"NOPE\"]}").with(admin))
                .andExpect(jsonPath("$.errors.length()").value(5));
        mvc.perform(json(post("/api/admin/users"), "{\"loginId\":\"ok.user\",\"userNm\":\"x\",\"authGrpCds\":[null]}").with(admin))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/admin/auth-groups"), "{\"authGrpCd\":\"한\",\"authGrpNm\":\"\",\"sortNo\":-1,\"homeProgCd\":\"nope\"}")
                .with(admin)).andExpect(jsonPath("$.errors.length()").value(4));
        mvc.perform(json(post("/api/admin/auth-groups"), "{\"authGrpCd\":\"work\",\"authGrpNm\":\"중복\"}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
    }

    // ---------- F: 낡은 화면 ----------

    @Test
    void 낡은_버전으로_저장하면_409() throws Exception {
        int ver = userVer(workerId);
        mvc.perform(json(put("/api/admin/users/" + workerId), userBody(null, "첫번째", "[\"WORK\"]", "Y", ver)).with(admin))
                .andExpect(status().isOk());
        mvc.perform(json(put("/api/admin/users/" + workerId), userBody(null, "두번째", "[\"WORK\"]", "Y", ver)).with(admin))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/api/admin/users/" + workerId + "/lock"), "{\"rowVer\":" + ver + "}").with(admin))
                .andExpect(status().isConflict());
        int gv = groupVer("WORK");
        mvc.perform(json(put("/api/admin/auth-groups/WORK/perms?rowVer=" + gv), "{\"items\":[]}").with(admin)).andExpect(status().isOk());
        mvc.perform(json(put("/api/admin/auth-groups/WORK/perms?rowVer=" + gv),
                "{\"items\":[{\"progCd\":\"mst-cust\",\"actions\":[\"SEARCH\"]}]}").with(admin)).andExpect(status().isConflict());
        assertThat(accounts.query(comp, "select user_nm from sys_user where user_id = ?", String.class, workerId)).isEqualTo("첫번째");
    }

    // ---------- I: 변경 강제 중 우회 ----------

    @Test
    void 변경_강제_중에는_내_정보_비밀번호_API_와_관리_API_도_막힌다() throws Exception {
        accounts.grant(comp, workerId, "WORK", "sys-my-info", "SEARCH", "SAVE");
        accounts.exec(comp, "update sys_user set pwd_change_req_yn = 'Y' where user_id = ?", workerId);
        var s = login("worker", PW);
        mvc.perform(json(post("/api/platform/my-info/password"), "{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"Calm-harbor-2026\"}")
                .session(s)).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(get("/api/platform/menu").session(s)).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(get("/page/sys-my-info").session(s)).andExpect(status().is3xxRedirection());
        // 같은 비밀번호로는 바꿀 수 없다
        mvc.perform(json(post("/api/platform/password-change"), "{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"" + PW + "\"}")
                .session(s)).andExpect(jsonPath("$.code").value("PASSWORD_POLICY"));
        // 변경 강제가 아닌 사용자에게 변경 화면은 그냥 셸로
        mvc.perform(get("/password-change").session(login("hr", PW))).andExpect(status().is3xxRedirection());
    }
}
