package io.smarthappy.module.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 시스템관리 시험 공통 (docs/36). 회사마다:
 * admin = 관리자 그룹(ADMIN, admin_yn=Y — sys_auth_prog 행 없음) / hr = 부관리자(사용자·권한그룹 관리 + 거래처 조회) /
 * worker = 거래처 조회·저장만
 */
@AutoConfigureMockMvc
abstract class SysAdminTestSupport extends PostgresTestBase {

    static final String PW = "Quiet-river-77";
    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long adminId;
    long hrId;
    long workerId;
    RequestPostProcessor admin;
    RequestPostProcessor hr;
    RequestPostProcessor worker;

    void setUpCompany(String prefix) {
        comp = accounts.newComp(prefix);
        adminId = accounts.newUser(comp, "admin", PW);
        adminGroup(comp, "ADMIN", adminId);
        hrId = accounts.newUser(comp, "hr", PW);
        accounts.grant(comp, hrId, "HR", "sys-user", "SEARCH", "SAVE", "FN1", "FN2");
        accounts.grant(comp, hrId, "HR", "sys-auth-group", "SEARCH", "SAVE");
        accounts.grant(comp, hrId, "HR", "mst-cust", "SEARCH", "SAVE");
        workerId = accounts.newUser(comp, "worker", PW);
        accounts.grant(comp, workerId, "WORK", "mst-cust", "SEARCH", "SAVE");
        admin = accounts.as(comp, adminId, "admin");
        hr = accounts.as(comp, hrId, "hr");
        worker = accounts.as(comp, workerId, "worker");
    }

    /** 관리자 그룹(행 없이 전부)에 사용자를 넣는다 */
    void adminGroup(String compCd, String grp, long... userIds) {
        accounts.exec(compCd, """
                insert into sys_auth_group (comp_cd, auth_grp_cd, auth_grp_nm, admin_yn, created_by, updated_by)
                values (?, ?, '관리자', 'Y', 't', 't') on conflict do nothing""", compCd, grp);
        for (long id : userIds) {
            accounts.exec(compCd, "insert into sys_user_auth (comp_cd, user_id, auth_grp_cd, created_by) values (?, ?, ?, 't')",
                    compCd, id, grp);
        }
    }

    int userVer(long userId) {
        return accounts.query(comp, "select row_ver from sys_user where user_id = ?", Integer.class, userId);
    }

    int groupVer(String grp) {
        return accounts.query(comp, "select row_ver from sys_auth_group where auth_grp_cd = ?", Integer.class, grp);
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** 실제 로그인(폼) → 세션. 시도 제한에 안 걸리게 IP 를 바꾼다 */
    MockHttpSession login(String loginId, String password) throws Exception {
        String ip = "10.35." + (IP_SEQ.incrementAndGet() % 250 + 1) + ".9";
        MvcResult r = mvc.perform(post("/login").with(csrf()).with(req -> { req.setRemoteAddr(ip); return req; })
                        .param("compCd", comp).param("loginId", loginId).param("password", password))
                .andExpect(redirectedUrl("/main")).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    static String userBody(String loginId, String userNm, String groupsJson, String useYn, Integer rowVer) {
        return "{\"loginId\":" + (loginId == null ? "null" : "\"" + loginId + "\"") + ",\"userNm\":\"" + userNm
                + "\",\"email\":\"\",\"localeCd\":\"ko\",\"useYn\":\"" + useYn + "\",\"authGrpCds\":" + groupsJson
                + ",\"rowVer\":" + rowVer + "}";
    }
}
