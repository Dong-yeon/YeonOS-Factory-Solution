package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.approval.ApprovalJobs;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA 2026-10-22 ④ — 결재 위임 시작 알림(docs/36 14.8)의 **회사 날짜 경계**와 대리·위임자 비활성.
 * 모듈 시험(ApprovalDelegateTest)은 서울 시간대·실제 시계로 "오늘/모레" 를 본다 — 여기서는 회사 시간대를 로스앤젤레스로 두고 시계를 자정 앞뒤에 고정한다.
 */
@AutoConfigureMockMvc
class ApprovalDelegateQaTest extends PostgresTestBase {

    static final String PW = "Amber-signal-36";
    static final String DELEG = "/api/platform/approvals/delegations";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    BizClock clock;

    @Autowired
    ApprovalJobs jobs;

    String comp;
    long bossId;
    long subId;
    RequestPostProcessor boss;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("QD");
        bossId = accounts.newUser(comp, "boss", PW);
        subId = accounts.newUser(comp, "sub", PW);
        boss = accounts.as(comp, bossId, "boss");
        accounts.exec(comp, "update sys_comp set time_zone = 'America/Los_Angeles' where comp_cd = ?", comp);
    }

    @AfterEach
    void reset() {
        clock.setClock(Clock.systemUTC());
    }

    private void at(String instant) {
        clock.setClock(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private ResultActions delegate(String from, String to) throws Exception {
        return mvc.perform(post(DELEG).with(csrf()).with(boss).contentType(MediaType.APPLICATION_JSON)
                .content("{\"delegateId\":" + subId + ",\"fromDate\":\"" + from + "\",\"toDate\":\"" + to + "\",\"reason\":\"출장\"}"));
    }

    private int notices(long userId) {
        return accounts.query(comp, "select count(*) from sys_notify where user_id = ? and event_cd = 'APR_DELEG_START'", Integer.class, userId);
    }

    @Test
    void 회사_시간대가_LA_면_서울_날짜가_바뀌어도_LA_자정까지는_내일_시작_위임을_알리지_않는다() throws Exception {
        // 2026-12-01T07:59Z = LA 11-30 23:59, 서울 12-01 16:59, UTC 12-01
        at("2026-12-01T07:59:00Z");
        // LA 오늘 = 11-30 → 11-29 시작은 과거, 12-01 시작은 미래(만들 때 알림 없음)
        delegate("2026-11-29", "2026-12-03").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("PAST"));
        delegate("2026-12-01", "2026-12-03").andExpect(status().isOk());
        assertThat(notices(subId)).isZero();
        assertThat(jobs.delegationStart().allSucceeded()).isTrue();
        assertThat(notices(subId)).isZero();
        // LA 자정을 넘긴 첫 실행 → 한 번
        at("2026-12-01T08:05:00Z");
        jobs.delegationStart();
        jobs.delegationStart();
        assertThat(notices(subId)).isEqualTo(1);
    }

    @Test
    void 회사_오늘_시작은_UTC_날짜가_어제여도_만들_때_바로_알린다() throws Exception {
        // 2026-12-02T03:00Z = LA 12-01 19:00 — 회사 오늘 = 12-01 (UTC 날짜 12-02 를 쓰면 "과거" 로 거부됐을 것)
        at("2026-12-02T03:00:00Z");
        delegate("2026-12-01", "2026-12-01").andExpect(status().isOk());
        assertThat(notices(subId)).isEqualTo(1);
    }

    @Test
    void 만든_뒤_대리가_사용_중지되면_위임이_끝나고_위임자_사용_중지는_위임_유지() throws Exception {
        at("2026-12-01T07:00:00Z");                             // LA 11-30
        delegate("2026-12-01", "2026-12-05").andExpect(status().isOk());
        // 대리 사용 중지(직접 SQL 경로도) → 끝나지 않은 위임은 트리거가 종료(DELEGATE_OFF, QA O-4 수정 — docs/36 16.6)
        accounts.setUserUse(comp, subId, "N");
        assertThat(accounts.query(comp, "select count(*) from apr_delegate where delegate_id = ? and revoked_at is null", Integer.class,
                subId)).isZero();
        assertThat(accounts.query(comp, "select revoke_cd from apr_delegate where delegate_id = ?", String.class, subId))
                .isEqualTo("DELEGATE_OFF");
        at("2026-12-01T09:00:00Z");                             // LA 12-01
        jobs.delegationStart();
        assertThat(notices(subId)).isZero();
        // 위임자가 사용 중지돼도 위임은 그대로 — 그 사람의 남은 결재를 대리가 처리할 길(의도)
        long sub2 = accounts.newUser(comp, "sub2", PW);
        accounts.exec(comp, """
                insert into apr_delegate (comp_cd, delegator_id, delegate_id, from_date, to_date, reason, created_by, updated_by)
                values (?, ?, ?, date '2026-12-01', date '2026-12-05', 'x', 't', 't')""", comp, bossId, sub2);
        accounts.setUserUse(comp, bossId, "N");
        assertThat(accounts.query(comp, "select count(*) from apr_delegate where delegator_id = ? and revoked_at is null", Integer.class,
                bossId)).isEqualTo(1);
    }
}
