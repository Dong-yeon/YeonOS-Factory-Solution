package io.smarthappy.platform.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/** 접속·보안 로그 (P0 6단계): 분리 트랜잭션, 탐지 규칙, 회사 격리, 추가 전용 */
@AutoConfigureMockMvc
class AccessLogTest extends PostgresTestBase {

    private static final String PW = "Amber-lantern-58";
    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AccessLogService accessLog;

    String comp;
    long userId;
    RequestPostProcessor user;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("AL");
        userId = accounts.newUser(comp, "kim", PW);
        accounts.grant(comp, userId, "SELF", "sys-my-info", "SEARCH", "SAVE");
        user = accounts.as(comp, userId, "kim");
    }

    private List<Map<String, Object>> logs(String compCd) {
        return runner.call(compCd, () -> jdbc.queryForList(
                "select event_cd, result_cd, severity, client_type, login_id, prog_cd, action_cd, detail, user_id "
                        + "from sys_access_log order by log_id"));
    }

    private List<String> events(String compCd) {
        return logs(compCd).stream().map(r -> (String) r.get("event_cd")).toList();
    }

    private void login(String compCd, String loginId, String password) throws Exception {
        String ip = "10.88." + (IP_SEQ.incrementAndGet() / 250) + "." + (IP_SEQ.get() % 250 + 1);
        mvc.perform(post("/login").with(csrf()).with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }).param("compCd", compCd).param("loginId", loginId).param("password", password));
    }

    // ---------- 정상 ----------

    @Test
    void 로그인_성공과_실패가_남는다() throws Exception {
        login(comp, "kim", "wrong-password-1");
        login(comp, "kim", PW);
        assertThat(events(comp)).containsExactly("LOGIN_FAIL", "LOGIN_OK");
        Map<String, Object> ok = logs(comp).get(1);
        assertThat(ok.get("user_id")).isEqualTo(userId);
        assertThat(ok.get("client_type")).isEqualTo("WEB");
    }

    @Test
    void 연속_실패로_잠기면_LOGIN_LOCKED_WARN() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(comp, "kim", "wrong-password-" + i);
        }
        List<Map<String, Object>> rows = logs(comp);
        assertThat(rows).filteredOn(r -> "LOGIN_LOCKED".equals(r.get("event_cd"))).hasSize(1)
                .first().extracting(r -> r.get("severity")).isEqualTo("WARN");
        login(comp, "kim", PW);
        assertThat(events(comp)).last().isEqualTo("LOGIN_DENIED");
    }

    @Test
    void 화면_열기와_저장_기능이_남는다() throws Exception {
        mvc.perform(get("/page/sys-my-info").with(user)).andExpect(status().isOk());
        mvc.perform(post("/api/platform/my-info/locale").with(user).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"localeCd\":\"en\"}")).andExpect(status().isOk());
        List<Map<String, Object>> rows = logs(comp);
        assertThat(rows).extracting(r -> r.get("event_cd")).containsExactly("PAGE_VIEW", "ACTION");
        assertThat(rows.get(1)).containsEntry("prog_cd", "sys-my-info").containsEntry("action_cd", "SAVE")
                .containsEntry("result_cd", "OK");
    }

    @Test
    void 사무_세션은_헤더를_무시하고_WEB() throws Exception {
        // QA D-2(2026-10-27): 사무 사용자가 X-HB-Client 를 직접 보내도 단말로 기록되지 않는다
        for (String h : new String[] {"pop", "PDA", "API", "'; drop table x;--"}) {
            mvc.perform(get("/page/sys-my-info").with(user).header(AccessLogService.CLIENT_HEADER, h));
        }
        assertThat(logs(comp)).extracting(r -> r.get("client_type")).containsExactly("WEB", "WEB", "WEB", "WEB");
    }

    @Test
    void 로그인_전_사무_요청도_단말_헤더는_무시_API_만_인정() throws Exception {
        String ip = "10.87.1." + (IP_SEQ.incrementAndGet() % 250 + 1);
        mvc.perform(post("/login").with(csrf()).with(r -> { r.setRemoteAddr(ip); return r; })
                .header(AccessLogService.CLIENT_HEADER, "PDA").param("compCd", comp).param("loginId", "kim").param("password", "wrong-pw-1"));
        mvc.perform(post("/login").with(csrf()).with(r -> { r.setRemoteAddr(ip); return r; })
                .header(AccessLogService.CLIENT_HEADER, "API").param("compCd", comp).param("loginId", "kim").param("password", "wrong-pw-2"));
        assertThat(logs(comp)).extracting(r -> r.get("client_type")).containsExactly("WEB", "API");
    }

    // ---------- 이상 사용 ----------

    @Test
    void 실패한_저장도_본_트랜잭션과_별도로_FAIL_로_남는다() throws Exception {
        mvc.perform(post("/api/platform/my-info/password").with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"not-mine-123\",\"newPassword\":\"Quiet-river-77x\"}"))
                .andExpect(status().isBadRequest());
        assertThat(logs(comp)).extracting(r -> r.get("event_cd") + "/" + r.get("result_cd"))
                .containsExactly("ACTION/FAIL");
    }

    @Test
    void 권한_없는_API_반복은_ALERT_로_올라간다() throws Exception {
        long other = accounts.newUser(comp, "lee", PW);
        RequestPostProcessor noPerm = accounts.as(comp, other, "lee");
        for (int i = 0; i < AccessLogService.DENIED_ALERT_COUNT; i++) {
            mvc.perform(post("/api/platform/my-info/locale").with(noPerm).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"localeCd\":\"en\"}"))
                    .andExpect(status().isForbidden());
        }
        List<Map<String, Object>> rows = logs(comp);
        assertThat(rows).allMatch(r -> "PERM_DENIED".equals(r.get("event_cd")));
        assertThat(rows.get(0).get("severity")).isEqualTo("WARN");
        assertThat(rows.get(rows.size() - 1).get("severity")).isEqualTo("ALERT");
    }

    @Test
    void 없는_회사코드로_로그인을_반복해도_로그가_쌓이지_않는다() throws Exception {
        login("NOPE-ZZ9", "kim", "x-password-1");
        assertThat(ownerCount("select count(*) from sys_access_log where comp_cd = 'NOPE-ZZ9'")).isZero();
    }

    @Test
    void 로그인ID의_제어문자와_길이는_정리해서_남긴다() throws Exception {
        login(comp, "kim\r\nFAKE LOGIN_OK admin", "wrong-password-1");
        String loginId = (String) logs(comp).get(0).get("login_id");
        assertThat(loginId).doesNotContain("\r").doesNotContain("\n").hasSizeLessThanOrEqualTo(50);
    }

    @Test
    void 다른_회사_로그는_보이지_않는다() throws Exception {
        String other = accounts.newComp("AL");
        login(comp, "kim", PW);
        assertThat(runner.call(other, () -> jdbc.queryForObject(
                "select count(*) from sys_access_log where comp_cd = ?", Integer.class, comp))).isZero();
    }

    @Test
    void 앱_계정은_로그를_고치거나_지울_수_없다() {
        login0();
        assertThatThrownBy(() -> runner.run(comp, () -> jdbc.update("update sys_access_log set result_cd = 'OK'")))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> runner.run(comp, () -> jdbc.update("delete from sys_access_log")))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> runner.run(comp, () -> jdbc.update("delete from sys_audit_log")))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> runner.run(comp, () -> jdbc.update("truncate sys_audit_log")))
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void 다른_회사_범위_안에서_분리_트랜잭션을_열_수_없다() {
        String other = accounts.newComp("AL");
        assertThatThrownBy(() -> runner.run(comp, () -> runner.runIsolated(other, () -> { })))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 로그_기록_실패가_업무_요청을_깨지_않는다() {
        // 형식이 틀린 회사코드 → 기록하지 않고 조용히 넘어간다
        accessLog.record(AccessEvent.LOGIN_FAIL, "bad code!", null, "x", null, null, AccessLogService.Result.FAIL, null);
        accessLog.record(AccessEvent.LOGIN_FAIL, comp, null, "x".repeat(500), "p".repeat(500), null,
                AccessLogService.Result.FAIL, "d".repeat(5000));
        assertThat(logs(comp)).hasSize(1);
    }

    private void login0() {
        accessLog.record(AccessEvent.LOGIN_OK, comp, userId, "kim", null, null, AccessLogService.Result.OK, null);
    }

    private static int ownerCount(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword());
                Statement st = c.createStatement(); var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
