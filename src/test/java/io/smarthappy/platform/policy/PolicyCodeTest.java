package io.smarthappy.platform.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.MockMvc;

import io.smarthappy.platform.common.net.IpMatcher;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/** 정책·공통코드 (P0 5단계) */
@AutoConfigureMockMvc
class PolicyCodeTest extends PostgresTestBase {

    @Autowired
    PolicyService policies;

    @Autowired
    TenantRunner runner;

    @Autowired
    TestAccounts accounts;

    @Autowired
    MockMvc mvc;

    String comp;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PC");
    }

    // ---------- 정책 ----------

    @Test
    void 값이_없으면_기본값_회사값이_있으면_회사값() {
        assertThat(runner.call(comp, () -> policies.intValue(Policies.PWD_MIN_LENGTH))).isEqualTo(12);
        runner.run(comp, () -> policies.set(Policies.PWD_MIN_LENGTH, null, "16", "test"));
        assertThat(runner.call(comp, () -> policies.intValue(Policies.PWD_MIN_LENGTH))).isEqualTo(16);
    }

    @Test
    void 다른_회사_정책에는_영향이_없다() {
        String other = accounts.newComp("PO");
        runner.run(comp, () -> policies.set(Policies.PWD_COMPLEXITY_REQUIRED, null, "Y", "test"));
        assertThat(runner.call(other, () -> policies.bool(Policies.PWD_COMPLEXITY_REQUIRED))).isFalse();
    }

    @Test
    void 정의에_맞지_않는_값은_저장할_수_없다() {
        String[][] bad = {
                {Policies.PWD_MIN_LENGTH, "7"}, {Policies.PWD_MIN_LENGTH, "65"}, {Policies.PWD_MIN_LENGTH, "abc"},
                {Policies.PWD_MIN_LENGTH, "12.5"}, {Policies.PWD_MIN_LENGTH, ""},
                {Policies.PWD_COMPLEXITY_REQUIRED, "yes"}, {Policies.PWD_COMPLEXITY_REQUIRED, "y"},
                {Policies.AI_PROVIDER, "OPENAI"}, {Policies.AI_PROVIDER, "none"},
                {Policies.LOGIN_TRUSTED_IPS, "factory.example.com"}, {Policies.LOGIN_TRUSTED_IPS, "300.1.1.1"},
                {Policies.LOGIN_TRUSTED_IPS, "10.0.0.0/33"}, {Policies.LOGIN_TRUSTED_IPS, "1evil.com"},
                {Policies.AI_TRAINING_CONSENT, "line1\nline2"}, {Policies.AI_TRAINING_CONSENT, "x".repeat(2001)},
                {"NO_SUCH_POLICY", "Y"},
        };
        for (String[] b : bad) {
            assertThatThrownBy(() -> runner.run(comp, () -> policies.set(b[0], null, b[1], "test")))
                    .as("%s=%s", b[0], b[1])
                    .isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> runner.run(comp, () -> policies.set(null, null, "Y", "test")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 공장_단위를_허용하지_않는_정책은_공장값을_저장할_수_없다() {
        assertThatThrownBy(() -> runner.run(comp, () -> policies.set(Policies.PWD_MIN_LENGTH, "P1", "14", "test")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 앞뒤_공백은_정리해서_저장한다() {
        runner.run(comp, () -> policies.set(Policies.PWD_MIN_LENGTH, null, " 14 ", "test"));
        assertThat(runner.call(comp, () -> policies.value(Policies.PWD_MIN_LENGTH))).isEqualTo("14");
    }

    @Test
    void 신뢰_IP_목록_해석() {
        IpMatcher m = IpMatcher.parse("203.0.113.10, 198.51.100.0/24 ,2001:db8::/32").orElseThrow();
        assertThat(m.matches("203.0.113.10")).isTrue();
        assertThat(m.matches("203.0.113.11")).isFalse();
        assertThat(m.matches("198.51.100.250")).isTrue();
        assertThat(m.matches("198.51.101.1")).isFalse();
        assertThat(m.matches("2001:db8::1")).isTrue();
        assertThat(m.matches("example.com")).isFalse();
        assertThat(m.matches(null)).isFalse();
        assertThat(IpMatcher.parse(String.join(",", java.util.Collections.nCopies(51, "1.1.1.1")))).isEmpty();
    }

    @Test
    void 앱_계정은_정책_정의와_시스템_코드를_바꿀_수_없다() {
        assertThatThrownBy(() -> accounts.exec(comp, "update sys_policy_def set default_value = '4' where policy_key = 'PWD_MIN_LENGTH'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> accounts.exec(comp, "insert into sys_code (grp_cd, code, code_nm_key) values ('UNIT', 'X', 'code.UNIT.X')"))
                .isInstanceOf(DataAccessException.class);
    }

    // ---------- 공통코드 ----------

    @Test
    void 시스템_코드와_회사_코드를_함께_언어별로() throws Exception {
        long user = accounts.newUser(comp, "cd", "Pw-x-12345!");
        accounts.exec(comp, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, sort_no, created_by, updated_by) values (?, 'UNIT', 'PAIL', '페일', 99, 't', 't')", comp);
        // 시스템 단위는 V006·V013 에서 늘어날 수 있다 — 개수 대신 위치·내용으로 본다
        mvc.perform(get("/api/platform/codes").param("grp", "UNIT").with(accounts.as(comp, user, "cd")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("EA"))
                .andExpect(jsonPath("$.data[0].name").value("개"))
                .andExpect(jsonPath("$.data[-1].code").value("PAIL"))
                .andExpect(jsonPath("$.data[-1].name").value("페일"))
                .andExpect(jsonPath("$.data[-1].system").value(false));
        mvc.perform(get("/api/platform/codes").param("grp", "UNIT").with(accounts.as(comp, user, "cd", "en")))
                .andExpect(jsonPath("$.data[0].name").value("Each"));
    }

    @Test
    void 다른_회사_코드는_안보이고_없는_그룹은_404() throws Exception {
        String other = accounts.newComp("PX");
        accounts.exec(other, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by) values (?, 'UNIT', 'SECRET', '남의 단위', 't', 't')", other);
        long user = accounts.newUser(comp, "cd", "Pw-x-12345!");
        mvc.perform(get("/api/platform/codes").param("grp", "UNIT").with(accounts.as(comp, user, "cd")))
                .andExpect(jsonPath("$.data[?(@.code == 'SECRET')]").isEmpty())
                .andExpect(jsonPath("$.data[?(@.system == false)]").isEmpty());
        for (String bad : new String[] {"NO_SUCH", "unit", "", "UNIT' or '1'='1"}) {
            mvc.perform(get("/api/platform/codes").param("grp", bad).with(accounts.as(comp, user, "cd")))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/platform/codes").with(accounts.as(comp, user, "cd")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 회사_코드는_확장_허용_그룹에만_시스템_코드와_겹치지_않게() {
        assertThatThrownBy(() -> accounts.exec(comp, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by) values (?, 'COMP_STATUS', 'X', 'x', 't', 't')", comp))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> accounts.exec(comp, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, created_by, updated_by) values (?, 'UNIT', 'EA', '덮어쓰기', 't', 't')", comp))
                .isInstanceOf(DataAccessException.class);
    }
}
