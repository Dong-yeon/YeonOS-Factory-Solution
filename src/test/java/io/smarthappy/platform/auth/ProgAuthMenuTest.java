package io.smarthappy.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/** 화면 진입·메뉴·@ProgAuth·언어 (P0 4단계) — 정상 흐름 + 이상 사용 */
@AutoConfigureMockMvc
class ProgAuthMenuTest extends PostgresTestBase {

    private static final String PROG = "sys-my-info";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long userId;
    RequestPostProcessor user;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PM");
        userId = accounts.newUser(comp, "lee", "Pw-12345!");
        user = accounts.as(comp, userId, "lee");
    }

    // ---------- 화면 진입 ----------

    @Test
    void 조회권한이_있으면_화면이_열리고_저장권한이_없으면_저장버튼이_없다() throws Exception {
        accounts.grant(comp, userId, "VIEWER", PROG, "SEARCH");
        mvc.perform(get("/page/" + PROG).with(user))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("내 정보")))
                .andExpect(content().string(not(containsString("id=\"saveBtn\""))));
    }

    @Test
    void 저장권한까지_있으면_저장버튼이_보인다() throws Exception {
        accounts.grant(comp, userId, "EDITOR", PROG, "SEARCH", "SAVE");
        mvc.perform(get("/page/" + PROG).with(user))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"saveBtn\"")));
    }

    @Test
    void 조회권한이_없으면_403_화면() throws Exception {
        mvc.perform(get("/page/" + PROG).with(user))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("권한 없음")));
    }

    @Test
    void 없는_화면이나_형식이_틀린_코드는_404_화면() throws Exception {
        accounts.grant(comp, userId, "VIEWER", PROG, "SEARCH");
        for (String bad : new String[] {"no-such-prog", "SYS-MY-INFO", "x", "a".repeat(60), "sys_my_info",
                "sys-my-info.html", "-sys"}) {
            mvc.perform(get("/page/{p}", bad).with(user))
                    .andExpect(status().isNotFound());
        }
        // 경로 조작(..)은 Spring Security 방화벽이 먼저 거부(400) — 화면이 렌더링되지 않으면 된다
        for (String traversal : new String[] {"/page/../login", "/page/sys-my-info/../../main", "/page/%2e%2e"}) {
            mvc.perform(get(traversal).with(user))
                    .andExpect(status().is4xxClientError())
                    .andExpect(content().string(not(containsString("id=\"saveBtn\""))));
        }
    }

    @Test
    void 비로그인_화면_진입은_로그인으로() throws Exception {
        mvc.perform(get("/page/" + PROG)).andExpect(status().is3xxRedirection());
    }

    // ---------- @ProgAuth ----------

    @Test
    void 저장권한이_있으면_언어를_바꾸고_다음_요청부터_영어() throws Exception {
        accounts.grant(comp, userId, "EDITOR", PROG, "SEARCH", "SAVE");
        var session = new org.springframework.mock.web.MockHttpSession();
        mvc.perform(post("/api/platform/my-info/locale").session(session).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"localeCd\":\"en\"}"))
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select locale_cd from sys_user where user_id = ?", String.class, userId))
                .isEqualTo("en");
        // 세션 principal 이 바뀌었으므로 같은 세션의 다음 요청은 영어
        mvc.perform(get("/page/" + PROG).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("My info")));
        mvc.perform(post("/api/platform/my-info/locale").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"localeCd\":\"xx\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VALUE"))
                .andExpect(jsonPath("$.message").value("The input value is not valid."));
    }

    @Test
    void 저장권한이_없으면_API는_403이고_값이_바뀌지_않는다() throws Exception {
        accounts.grant(comp, userId, "VIEWER", PROG, "SEARCH");
        mvc.perform(post("/api/platform/my-info/locale").with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"localeCd\":\"en\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NO_PERMISSION"));
        assertThat(accounts.query(comp, "select locale_cd from sys_user where user_id = ?", String.class, userId))
                .isEqualTo("ko");
    }

    @Test
    void 이상한_요청본문은_400() throws Exception {
        accounts.grant(comp, userId, "EDITOR", PROG, "SEARCH", "SAVE");
        mvc.perform(post("/api/platform/my-info/locale").with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(post("/api/platform/my-info/locale").with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VALUE"));
        mvc.perform(post("/api/platform/my-info/locale").with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"localeCd\":\"<script>\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("<script>"))));
    }

    @Test
    void 프로그램이_정의하지_않은_액션은_줘도_효과가_없다() throws Exception {
        accounts.grant(comp, userId, "ODD", PROG, "SEARCH", "DELETE"); // sys-my-info 액션은 SEARCH,SAVE
        mvc.perform(get("/page/" + PROG).with(user))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("id=\"saveBtn\""))));
    }

    @Test
    void 권한그룹을_사용중지하면_권한이_사라진다() throws Exception {
        accounts.grant(comp, userId, "VIEWER", PROG, "SEARCH");
        accounts.exec(comp, "update sys_auth_group set use_yn = 'N' where auth_grp_cd = 'VIEWER'");
        mvc.perform(get("/page/" + PROG).with(user)).andExpect(status().isForbidden());
    }

    @Test
    void 권한을_주면_다음_요청부터_바로_반영된다() throws Exception {
        mvc.perform(get("/page/" + PROG).with(user)).andExpect(status().isForbidden());
        accounts.grant(comp, userId, "VIEWER", PROG, "SEARCH");
        mvc.perform(get("/page/" + PROG).with(user)).andExpect(status().isOk());
    }

    @Test
    void 다른_회사_사용자를_내_회사_권한그룹에_넣을_수_없다() {
        String other = accounts.newComp("PO");
        accounts.grant(other, accounts.newUser(other, "boss", "Pw-12345!"), "ADMIN", PROG, "SEARCH", "SAVE");
        assertThatThrownBy(() -> accounts.exec(other,
                "insert into sys_user_auth (comp_cd, user_id, auth_grp_cd, created_by) values (?, ?, 'ADMIN', 't')",
                other, userId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void 앱_계정은_전역_프로그램_목록을_바꿀_수_없다() {
        assertThatThrownBy(() -> accounts.exec(comp, "update sys_prog set view_path = 'login' where prog_cd = ?", PROG))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> accounts.exec(comp,
                "insert into sys_prog (prog_cd, prog_nm_key, view_path, module_cd) values ('evil', 'prog.x', 'login', 'X')"))
                .isInstanceOf(DataAccessException.class);
    }

    // ---------- 메뉴 ----------

    @Test
    void 메뉴는_조회권한_있는_화면과_그_폴더만() throws Exception {
        accounts.grant(comp, userId, "VIEWER", PROG, "SEARCH");
        long sys = accounts.addMenu(comp, null, "시스템", null, 1);
        accounts.addMenu(comp, sys, null, PROG, 1);
        long emptyFolder = accounts.addMenu(comp, null, "빈 폴더", null, 2);
        accounts.addMenu(comp, emptyFolder, "하위 빈 폴더", null, 1);

        mvc.perform(get("/api/platform/menu").with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("시스템"))
                .andExpect(jsonPath("$.data[0].children[0].progCd").value(PROG))
                .andExpect(jsonPath("$.data[0].children[0].name").value("내 정보"));
    }

    @Test
    void 권한이_없으면_메뉴가_비고_다른_회사_메뉴는_안보인다() throws Exception {
        String other = accounts.newComp("PX");
        long otherUser = accounts.newUser(other, "xx", "Pw-12345!");
        accounts.grant(other, otherUser, "ADMIN", PROG, "SEARCH");
        long f = accounts.addMenu(other, null, "남의 회사 메뉴", null, 1);
        accounts.addMenu(other, f, null, PROG, 1);

        mvc.perform(get("/api/platform/menu").with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void 순환은_DB_가_막고_사용중지_폴더_아래는_조용히_빠진다() throws Exception {
        accounts.grant(comp, userId, "VIEWER", PROG, "SEARCH");
        long a = accounts.addMenu(comp, null, "A", null, 1);
        long b = accounts.addMenu(comp, a, "B", null, 1);
        accounts.addMenu(comp, b, null, PROG, 1);
        // A↔B 순환: 예전에는 저장되고 표시에서만 빠졌다 → 시스템관리 1단계(docs/36 D5)부터 트리거가 거부
        assertThatThrownBy(() -> accounts.exec(comp, "update sys_menu set parent_id = ? where menu_id = ?", b, a))
                .isInstanceOf(DataAccessException.class);
        long off = accounts.addMenu(comp, null, "꺼진 폴더", null, 2);
        accounts.addMenu(comp, off, "꺼진 폴더의 화면", PROG, 1);
        accounts.exec(comp, "update sys_menu set use_yn = 'N' where menu_id = ?", off);

        mvc.perform(get("/api/platform/menu").with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("A"));
    }

    @Test
    void 메뉴_이름은_가공없이_데이터로_내려간다_화면이_textContent_로_표시() throws Exception {
        accounts.grant(comp, userId, "VIEWER", PROG, "SEARCH");
        long f = accounts.addMenu(comp, null, "<img src=x onerror=alert(1)>", null, 1);
        accounts.addMenu(comp, f, null, PROG, 1);
        mvc.perform(get("/api/platform/menu").with(user))
                .andExpect(jsonPath("$.data[0].name").value("<img src=x onerror=alert(1)>"));
    }

    @Test
    void 다른_회사_폴더를_부모로_지정할_수_없다() {
        String other = accounts.newComp("PY");
        long otherFolder = accounts.addMenu(other, null, "남의 폴더", null, 1);
        assertThatThrownBy(() -> accounts.addMenu(comp, otherFolder, "끼워넣기", null, 1))
                .isInstanceOf(DataAccessException.class);
    }

    // ---------- 기타 API ----------

    @Test
    void 화면문구_API는_언어별_js_키만() throws Exception {
        mvc.perform(get("/api/platform/messages").with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data['js.shell.closeTab']").value("탭 닫기"))
                .andExpect(jsonPath("$.data['login.title']").doesNotExist());
    }

    @Test
    void 없는_API는_404_잘못된_방식은_405_JSON() throws Exception {
        mvc.perform(get("/api/platform/no-such").with(user))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/platform/my-info/locale").with(user))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
