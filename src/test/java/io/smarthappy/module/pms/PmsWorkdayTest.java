package io.smarthappy.module.pms;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 근무일 입력 보조 GET /api/pms/workdays (docs/32 4.25, 사용자 결정 P-13) — 회사 휴일 달력(주간 쉬는 날 + 회사 휴일)으로 근무일 n 번째 날·
 * 공수 → 근무일(하루 시간 정책, 올림)·두 날짜 사이 근무일 수, 입력 검사, 권한, A사로 B사(다른 회사 휴일이 섞이지 않음). 날짜는 고정(2026-03-02 월).
 */
@AutoConfigureMockMvc
class PmsWorkdayTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String W = "/api/pms/workdays";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor pm;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PW");
        long u = accounts.newUser(comp, "pm1", PW);
        accounts.grant(comp, u, "PMS", "pms-project", "SEARCH");
        pm = accounts.as(comp, u, "pm1");
        weeklyOff(comp, "SAT_SUN");
        holiday(comp, "2026-03-04");        // 수요일 회사 휴일
    }

    private void weeklyOff(String c, String v) {
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'WEEKLY_OFF', ?, 't', 't')", c, v);
    }

    private void holiday(String c, String day) {
        accounts.exec(c, "insert into mst_work_cal (comp_cd, cal_date, day_type, reason, created_by, updated_by) values (?, ?::date, 'OFF', '휴무', 't', 't')",
                c, day);
    }

    private ResultActions calc(RequestPostProcessor who, String... kv) throws Exception {
        var r = get(W);
        for (int i = 0; i < kv.length; i += 2) {
            r = r.param(kv[i], kv[i + 1]);
        }
        return mvc.perform(r.with(who));
    }

    @Test
    void 근무일_n번째_날은_주말과_회사휴일을_건너뛴다() throws Exception {
        // 월 3/2 부터 5 근무일 = 월·화·(수 휴일)·목·금·(주말)·월 → 3/9
        calc(pm, "start", "2026-03-02", "days", "5").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.end").value("2026-03-09")).andExpect(jsonPath("$.data.workdays").value(5));
        // 1일 = 시작일이 근무일이면 그날
        calc(pm, "start", "2026-03-02", "days", "1").andExpect(jsonPath("$.data.end").value("2026-03-02"));
        // 시작일이 토요일이면 첫 근무일(월)이 1일째
        calc(pm, "start", "2026-03-07", "days", "1").andExpect(jsonPath("$.data.end").value("2026-03-09"));
        // 두 날짜 사이 근무일 수(양 끝 포함): 3/2~3/15 = 10 평일 - 휴일 1 = 9
        calc(pm, "start", "2026-03-02", "end", "2026-03-15").andExpect(jsonPath("$.data.workdays").value(9))
                .andExpect(jsonPath("$.data.end").value("2026-03-15"));
        // 달력이 해를 넘어도(청크 경계) 이어서 센다: 1년치 근무일 260 번째 날은 이듬해
        calc(pm, "start", "2026-03-02", "days", "260").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.end").value(org.hamcrest.Matchers.startsWith("2027-")));
    }

    @Test
    void 계획공수는_하루시간으로_나눠_올림한_근무일() throws Exception {
        // 기본 하루 8시간: 20시간 → 3일 → 월·화·목(수 휴일) = 3/5
        calc(pm, "start", "2026-03-02", "hours", "20").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.days").value(3)).andExpect(jsonPath("$.data.hoursPerDay").value(8))
                .andExpect(jsonPath("$.data.end").value("2026-03-05"));
        // 0.5시간도 최소 1일
        calc(pm, "start", "2026-03-02", "hours", "0.5").andExpect(jsonPath("$.data.days").value(1));
        // 회사 정책 하루 10시간 → 20시간 = 2일 = 3/3
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'PMS_HOURS_PER_DAY', '10', 't', 't')",
                comp);
        calc(pm, "start", "2026-03-02", "hours", "20").andExpect(jsonPath("$.data.days").value(2))
                .andExpect(jsonPath("$.data.end").value("2026-03-03"));
    }

    @Test
    void 입력검사_하나만_범위_날짜() throws Exception {
        calc(pm, "start", "2026-03-02").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("PMS_WORKDAY_ONE_OF"));
        calc(pm, "start", "2026-03-02", "days", "3", "hours", "8").andExpect(jsonPath("$.errors[0].code").value("PMS_WORKDAY_ONE_OF"));
        calc(pm, "start", "2026-03-02", "days", "0").andExpect(jsonPath("$.errors[0].field").value("days"))
                .andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        calc(pm, "start", "2026-03-02", "days", "1001").andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        calc(pm, "start", "2026-03-02", "hours", "-1").andExpect(jsonPath("$.errors[0].field").value("hours"));
        calc(pm, "start", "2026-03-02", "end", "2026-03-01").andExpect(jsonPath("$.errors[0].code").value("PMS_END_BEFORE_START"));
        calc(pm, "start", "2026-03-02", "end", "2040-01-01").andExpect(jsonPath("$.errors[0].code").value("RANGE_TOO_LONG"));
        calc(pm, "start", "2026-02-30", "days", "1").andExpect(jsonPath("$.errors[0].field").value("start"));
        calc(pm, "days", "1").andExpect(jsonPath("$.errors[0].field").value("start"));
        // 달력이 전부 쉬는 날이면(3650일 안에 근무일 없음) 오류 — 무한히 찾지 않는다
        accounts.exec(comp, """
                insert into mst_work_cal (comp_cd, cal_date, day_type, reason, created_by, updated_by)
                select ?, d::date, 'OFF', '장기 휴무', 't', 't' from generate_series('2026-03-02'::date, '2036-03-31'::date, '1 day') d
                on conflict do nothing""", comp);
        calc(pm, "start", "2026-03-02", "days", "1").andExpect(jsonPath("$.errors[0].code").value("PMS_WORKDAY_NONE"));
    }

    @Test
    void 권한_A사로_B사() throws Exception {
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "NONE", "home-main", "SEARCH");
        calc(accounts.as(comp, none, "none1"), "start", "2026-03-02", "days", "1").andExpect(status().isForbidden());
        // B사: 주간 쉬는 날 없음, 화요일만 B사 휴일 — A사 수요일 휴일·주말이 섞이지 않는다
        String b = accounts.newComp("PX");
        long bu = accounts.newUser(b, "pm1", PW);
        accounts.grant(b, bu, "PMS", "pms-project", "SEARCH");
        holiday(b, "2026-03-03");
        RequestPostProcessor bpm = accounts.as(b, bu, "pm1");
        calc(bpm, "start", "2026-03-02", "days", "3").andExpect(jsonPath("$.data.end").value("2026-03-05"));   // 월·(화 휴일)·수·목
        // A사 결과도 B사 휴일과 무관
        calc(pm, "start", "2026-03-02", "days", "3").andExpect(jsonPath("$.data.end").value("2026-03-05"));    // 월·화·(수 휴일)·목
        calc(pm, "start", "2026-03-02", "end", "2026-03-06").andExpect(jsonPath("$.data.workdays").value(4));
        calc(bpm, "start", "2026-03-02", "end", "2026-03-06").andExpect(jsonPath("$.data.workdays").value(4));
    }
}
