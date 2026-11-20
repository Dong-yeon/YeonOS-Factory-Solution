package io.smarthappy.platform.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/** 변경 감사 트리거 hb_audit (P0 6단계) */
@AutoConfigureMockMvc
class AuditTriggerTest extends PostgresTestBase {

    private static final String PW = "Copper-meadow-41";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    long userId;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("AU");
        userId = accounts.newUser(comp, "choi", PW);
    }

    private List<Map<String, Object>> audits(String table) {
        return runner.call(comp, () -> jdbc.queryForList("""
                select action, row_key, actor, before_data::text as before_data, after_data::text as after_data
                  from sys_audit_log where table_nm = ? order by audit_id""", table));
    }

    @Test
    void 등록_수정_삭제가_전후_값과_함께_남는다() {
        accounts.exec(comp, "insert into sys_auth_group (comp_cd, auth_grp_cd, auth_grp_nm, created_by, updated_by) "
                + "values (?, 'QC', '품질', 't', 't')", comp);
        accounts.exec(comp, "update sys_auth_group set auth_grp_nm = '품질팀' where auth_grp_cd = 'QC'");
        accounts.exec(comp, "delete from sys_auth_group where auth_grp_cd = 'QC'");
        List<Map<String, Object>> rows = audits("sys_auth_group");
        assertThat(rows).extracting(r -> r.get("action")).containsExactly("INSERT", "UPDATE", "DELETE");
        assertThat(rows.get(1).get("row_key")).isEqualTo(comp + "|QC");
        assertThat((String) rows.get(1).get("before_data")).contains("품질");
        assertThat((String) rows.get(1).get("after_data")).contains("품질팀");
        assertThat(rows.get(0).get("actor")).isEqualTo("SYSTEM");
    }

    @Test
    void 웹_요청의_변경은_로그인_사용자가_행위자다() throws Exception {
        accounts.grant(comp, userId, "SELF", "sys-my-info", "SEARCH", "SAVE");
        mvc.perform(post("/api/platform/my-info/locale").with(accounts.as(comp, userId, "choi")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"localeCd\":\"en\"}")).andExpect(status().isOk());
        List<Map<String, Object>> rows = audits("sys_user");
        assertThat(rows).last().satisfies(r -> {
            assertThat(r.get("action")).isEqualTo("UPDATE");
            assertThat(r.get("actor")).isEqualTo("u" + userId + ":choi");
        });
    }

    @Test
    void 비밀번호_해시는_값_대신_changed_로_남는다() throws Exception {
        accounts.grant(comp, userId, "SELF", "sys-my-info", "SEARCH", "SAVE");
        mvc.perform(post("/api/platform/my-info/password").with(accounts.as(comp, userId, "choi")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"Velvet-harbor-63\"}"))
                .andExpect(status().isOk());
        List<Map<String, Object>> rows = audits("sys_user");
        assertThat(rows).allSatisfy(r -> {
            assertThat(String.valueOf(r.get("before_data"))).doesNotContain("{bcrypt}").doesNotContain("$2a$");
            assertThat(String.valueOf(r.get("after_data"))).doesNotContain("{bcrypt}").doesNotContain("$2a$");
        });
        assertThat((String) rows.get(rows.size() - 1).get("after_data")).contains("\"pwd_hash\": \"(changed)\"");
    }

    @Test
    void 로그인_실패_기록처럼_무시_컬럼만_바뀌면_남기지_않는다() {
        int before = audits("sys_user").size();
        accounts.exec(comp, "update sys_user set login_fail_cnt = login_fail_cnt + 1, updated_at = now() where user_id = ?",
                userId);
        assertThat(audits("sys_user")).hasSize(before);
    }

    @Test
    void 다른_회사_감사로그는_보이지_않는다() {
        String other = accounts.newComp("AU");
        assertThat(runner.call(other, () -> jdbc.queryForObject(
                "select count(*) from sys_audit_log where comp_cd = ?", Integer.class, comp))).isZero();
        assertThat(runner.call(comp, () -> jdbc.queryForObject(
                "select count(*) from sys_audit_log where comp_cd = ?", Integer.class, comp))).isPositive();
    }
}
