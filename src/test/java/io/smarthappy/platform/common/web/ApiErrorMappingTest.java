package io.smarthappy.platform.common.web;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/** 오류 응답 매핑: RLS 위반 → 403, 회사 범위 없음 → 401, 그 밖의 DB 오류 → 500 (SQL 비노출) */
@AutoConfigureMockMvc
class ApiErrorMappingTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    RequestPostProcessor user;

    @BeforeEach
    void login() {
        String comp = accounts.newComp("EM");
        long userId = accounts.newUser(comp, "probe", "probe-pw-1!");
        user = accounts.as(comp, userId, "probe");
    }

    @Test
    void RLS_위반은_403() throws Exception {
        mvc.perform(post("/test-probe/rls-violation").with(user).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.result").value("ERROR"))
                .andExpect(jsonPath("$.code").value("TENANT_FORBIDDEN"))
                .andExpect(content().string(not(containsString("insert into"))))
                .andExpect(content().string(not(containsString("row-level"))));
    }

    @Test
    void 자기_행을_다른_회사로_바꾸는_update도_403() throws Exception {
        mvc.perform(post("/test-probe/rls-violation-update").with(user).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_FORBIDDEN"));
    }

    @Test
    void 회사_범위가_없는_스레드에서_회사_필요_기능을_부르면_401() throws Exception {
        mvc.perform(get("/test-probe/tenant-required-offthread").with(user))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    @Test
    void 일반_DB_오류는_500이고_SQL을_노출하지_않는다() throws Exception {
        mvc.perform(get("/test-probe/db-error").with(user))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DB_ERROR"))
                .andExpect(content().string(not(containsString("no_such_table"))))
                .andExpect(content().string(not(containsString("select"))));
    }

    @Test
    void DB_제약_위반은_의미있는_코드로_바뀐다() throws Exception {
        mvc.perform(post("/test-probe/duplicate").with(user).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE"))
                .andExpect(content().string(not(containsString("pk_sys_plant"))));
        mvc.perform(post("/test-probe/fk").with(user).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IN_USE"));
        mvc.perform(post("/test-probe/check").with(user).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VALUE"));
        mvc.perform(post("/test-probe/too-long").with(user).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VALUE"));
    }

    @Test
    void 오류_문구는_사용자_언어를_따른다_브라우저_언어보다_우선() throws Exception {
        String comp = accounts.newComp("EN");
        long id = accounts.newUser(comp, "eng", "probe-pw-1!");
        mvc.perform(get("/test-probe/db-error").with(accounts.as(comp, id, "eng", "en")).header("Accept-Language", "ko"))
                .andExpect(jsonPath("$.message").value(containsString("Please try again")));
        mvc.perform(get("/test-probe/db-error").with(user).header("Accept-Language", "en"))
                .andExpect(jsonPath("$.message").value(containsString("잠시 후")));
    }

    @Test
    void 권한부족_42501이라도_RLS가_아니면_403이_아니라_500() throws Exception {
        mvc.perform(post("/test-probe/ddl").with(user).with(csrf()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("DB_ERROR"));
    }
}
