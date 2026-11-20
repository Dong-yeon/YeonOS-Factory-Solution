package io.smarthappy.platform.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/** 활용 일 집계(docs/11 D2) · 역할별 홈(D6) — P0 9단계 */
@AutoConfigureMockMvc
class UsageHomeTest extends PostgresTestBase {

    private static final String PW = "Cedar-signal-47";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    UsageDailyService usage;

    @Autowired
    PolicyService policies;

    @Autowired
    BizClock bizClock;

    @AfterEach
    void resetClock() {
        bizClock.setClock(Clock.systemUTC());
    }

    private void log(String comp, long userId, String event, String prog, String action, String result, int daysAgo) {
        accounts.exec(comp, """
                insert into sys_access_log (comp_cd, occurred_at, user_id, login_id, event_cd, prog_cd, action_cd, result_cd)
                values (?, now() - make_interval(days => ?), ?, 'u', ?, ?, ?, ?)""",
                comp, daysAgo, userId, event, prog, action, result);
    }

    @Test
    void 지난_날을_사용자_수와_건수로_집계하고_다시_돌려도_같다() {
        String comp = accounts.newComp("UD");
        log(comp, 1, "LOGIN_OK", null, null, "OK", 2);
        log(comp, 2, "LOGIN_OK", null, null, "OK", 2);
        log(comp, 1, "PAGE_VIEW", "mst-cust", null, "OK", 2);
        log(comp, 1, "PAGE_VIEW", "mst-cust", null, "OK", 2);
        log(comp, 1, "ACTION", "mst-cust", "SAVE", "FAIL", 2);   // 실패는 활용으로 세지 않는다
        log(comp, 1, "PERM_DENIED", "mst-cust", "SAVE", "DENIED", 2);
        log(comp, 1, "LOGIN_OK", null, null, "OK", 0);           // 오늘은 아직 확정하지 않는다
        int days = runner.call(comp, () -> usage.aggregatePending());
        assertThat(days).isPositive();
        assertThat(runner.call(comp, () -> usage.aggregatePending())).isZero();
        assertThat(accounts.query(comp, "select user_cnt || '/' || event_cnt from sys_usage_daily"
                + " where event_cd = 'LOGIN_OK' and usage_date = " + TestAccounts.COMP_TODAY + " - 2", String.class)).isEqualTo("2/2");
        assertThat(accounts.query(comp, """
                select event_cnt from sys_usage_daily where event_cd = 'PAGE_VIEW' and prog_cd = 'mst-cust'""",
                Integer.class)).isEqualTo(2);
        assertThat(accounts.query(comp, "select count(*) from sys_usage_daily where event_cd in ('ACTION', 'PERM_DENIED')",
                Integer.class)).isZero();
        assertThat(accounts.query(comp, "select count(*) from sys_usage_daily where usage_date >= " + TestAccounts.COMP_TODAY,
                Integer.class)).isZero();
        // 사용이 없던 날도 표시 행으로 처리됨 → 다음 실행이 다시 훑지 않는다
        assertThat(accounts.query(comp, "select count(*) from sys_usage_daily where event_cd = '_DAY'", Integer.class))
                .isEqualTo(days);
    }

    /** 정해진 시각의 로그(시계를 고정한 시험용) */
    private void logAt(String comp, long userId, String event, String occurredAtUtc) {
        accounts.exec(comp, """
                insert into sys_access_log (comp_cd, occurred_at, user_id, login_id, event_cd, result_cd)
                values (?, ?, ?, 'u', ?, 'OK')""", comp, OffsetDateTime.parse(occurredAtUtc), userId, event);
    }

    @Test
    void 집계는_다른_회사_로그를_섞지_않는다() {
        String a = accounts.newComp("UD");
        String b = accounts.newComp("UD");
        // 2일 전 — 1일 전이면 회사 자정 뒤 10분(확정 대기, KST 00:00~00:10 = UTC 15:00~15:10) 동안 아직 확정 전이라 시험이 깨졌다
        log(a, 1, "LOGIN_OK", null, null, "OK", 2);
        log(b, 9, "LOGIN_OK", null, null, "OK", 2);
        log(b, 8, "LOGIN_OK", null, null, "OK", 2);
        runner.call(a, () -> usage.aggregatePending());
        assertThat(accounts.query(a, "select user_cnt from sys_usage_daily where event_cd = 'LOGIN_OK'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void 회사_자정_뒤_10분은_어제를_확정하지_않고_그_뒤에_빠짐없이_집계한다() {
        String comp = accounts.newComp("UD");
        // 회사 시간대 Asia/Seoul: 10-22 23:00 KST(= 14:00Z) 로그는 10-22 날짜, 10-23 00:30 KST(= 15:30Z) 로그는 10-23 날짜
        logAt(comp, 1, "LOGIN_OK", "2026-10-22T14:00:00Z");
        logAt(comp, 2, "LOGIN_OK", "2026-10-22T15:30:00Z");
        // 10-23 00:05 KST — 10-22 은 아직 확정 전(10분 대기): 집계하지 않는다(누락이 아니라 다음 실행으로 미룸)
        bizClock.setClock(Clock.fixed(Instant.parse("2026-10-22T15:05:00Z"), ZoneOffset.UTC));
        runner.call(comp, () -> usage.aggregatePending());
        assertThat(accounts.query(comp, "select count(*) from sys_usage_daily where usage_date = date '2026-10-22'", Integer.class))
                .isZero();
        // 10-23 00:11 KST — 10-22 확정: UTC 날짜가 아니라 회사 날짜로 나눈다 → 23:00 KST 한 건만, 00:30 KST 는 10-23 몫
        bizClock.setClock(Clock.fixed(Instant.parse("2026-10-22T15:11:00Z"), ZoneOffset.UTC));
        runner.call(comp, () -> usage.aggregatePending());
        assertThat(accounts.query(comp, "select user_cnt || '/' || event_cnt from sys_usage_daily"
                + " where usage_date = date '2026-10-22' and event_cd = 'LOGIN_OK'", String.class)).isEqualTo("1/1");
        assertThat(accounts.query(comp, "select count(*) from sys_usage_daily where usage_date >= date '2026-10-23'", Integer.class))
                .isZero();
    }

    @Test
    void 조회_기간_조작은_400() throws Exception {
        String comp = accounts.newComp("UD");
        long admin = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, admin, "ADMIN", "sys-usage", "SEARCH");
        mvc.perform(get("/api/platform/usage").param("from", "2026-10-10").param("to", "2026-10-01")
                .with(accounts.as(comp, admin, "admin1"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/platform/usage").param("from", "2020-01-01").param("to", "2026-10-01")
                .with(accounts.as(comp, admin, "admin1"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/platform/usage").param("from", "x").param("to", "y")
                .with(accounts.as(comp, admin, "admin1"))).andExpect(status().isBadRequest());
    }

    @Test
    void 기간_검사는_구체_코드와_필드_오류로_알려_준다() throws Exception {
        String comp = accounts.newComp("UD");
        long admin = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, admin, "ADMIN", "sys-usage", "SEARCH");
        // 시작 > 종료 → INVALID_RANGE + to 필드 (화면이 컨트롤 아래에 표시)
        mvc.perform(get("/api/platform/usage").param("from", "2026-10-10").param("to", "2026-10-01")
                .with(accounts.as(comp, admin, "admin1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RANGE"))
                .andExpect(jsonPath("$.errors[0].field").value("to"));
        // 하나만 / 둘 다 없음 → INVALID_RANGE(REQUIRED)
        mvc.perform(get("/api/platform/usage").param("from", "2026-10-10").with(accounts.as(comp, admin, "admin1")))
                .andExpect(jsonPath("$.code").value("INVALID_RANGE")).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        mvc.perform(get("/api/platform/usage").with(accounts.as(comp, admin, "admin1")))
                .andExpect(jsonPath("$.code").value("INVALID_RANGE")).andExpect(jsonPath("$.errors[0].field").value("from"));
        // 366일까지 허용, 367일 → RANGE_TOO_LONG (일수·상한이 문구에)
        mvc.perform(get("/api/platform/usage").param("from", "2025-01-01").param("to", "2026-01-01")
                .with(accounts.as(comp, admin, "admin1"))).andExpect(status().isOk());
        mvc.perform(get("/api/platform/usage").param("from", "2025-01-01").param("to", "2026-01-02")
                .with(accounts.as(comp, admin, "admin1")))
                .andExpect(jsonPath("$.code").value("RANGE_TOO_LONG"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("367")));
        // 같은 날 하루 = 1일
        mvc.perform(get("/api/platform/usage").param("from", "2026-10-01").param("to", "2026-10-01")
                .with(accounts.as(comp, admin, "admin1"))).andExpect(status().isOk());
    }

    @Test
    void A사가_조회_기간_정책을_줄여도_B사에는_영향이_없다() throws Exception {
        String a = accounts.newComp("UD");
        String b = accounts.newComp("UD");
        long ua = accounts.newUser(a, "admin1", PW);
        long ub = accounts.newUser(b, "admin1", PW);
        accounts.grant(a, ua, "ADMIN", "sys-usage", "SEARCH");
        accounts.grant(b, ub, "ADMIN", "sys-usage", "SEARCH");
        runner.run(a, () -> policies.set("SEARCH_MAX_RANGE_DAYS", "*", "30", "test"));
        mvc.perform(get("/api/platform/usage").param("from", "2026-09-01").param("to", "2026-10-01")   // 31일
                .with(accounts.as(a, ua, "admin1"))).andExpect(jsonPath("$.code").value("RANGE_TOO_LONG"));
        mvc.perform(get("/api/platform/usage").param("from", "2026-09-02").param("to", "2026-10-01")   // 30일
                .with(accounts.as(a, ua, "admin1"))).andExpect(status().isOk());
        mvc.perform(get("/api/platform/usage").param("from", "2026-09-01").param("to", "2026-10-01")
                .with(accounts.as(b, ub, "admin1"))).andExpect(status().isOk());
    }

    @Test
    void 역할별_홈은_조회_권한이_있는_첫_그룹의_화면() throws Exception {
        String comp = accounts.newComp("UD");
        long u = accounts.newUser(comp, "worker", PW);
        mvc.perform(get("/api/platform/home").with(accounts.as(comp, u, "worker"))).andExpect(jsonPath("$.data").doesNotExist());
        accounts.grant(comp, u, "FIELD", "mst-cust", "SEARCH");
        accounts.exec(comp, "update sys_auth_group set home_prog_cd = 'mst-cust' where auth_grp_cd = 'FIELD'");
        mvc.perform(get("/api/platform/home").with(accounts.as(comp, u, "worker"))).andExpect(jsonPath("$.data").value("mst-cust"));
        // 홈으로 지정됐어도 조회 권한이 없으면 열지 않는다
        accounts.exec(comp, "delete from sys_auth_prog where auth_grp_cd = 'FIELD'");
        mvc.perform(get("/api/platform/home").with(accounts.as(comp, u, "worker"))).andExpect(jsonPath("$.data").doesNotExist());
        // 그룹 사용 안 함이면 그 홈도 없다
        accounts.grant(comp, u, "FIELD", "mst-cust", "SEARCH");
        accounts.exec(comp, "update sys_auth_group set use_yn = 'N' where auth_grp_cd = 'FIELD'");
        mvc.perform(get("/api/platform/home").with(accounts.as(comp, u, "worker"))).andExpect(jsonPath("$.data").doesNotExist());
    }
}
