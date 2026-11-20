package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.approval.ApprovalService;
import io.smarthappy.platform.approval.ApprovalService.LineSpec;
import io.smarthappy.platform.approval.ApprovalService.LineType;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.TestMessaging.TestDocType;

/**
 * 시스템관리 4c 결재 위임·대결 (docs/36 14.6, Q27) — 위임자 본인이 만들고 회수, 오늘(회사 날짜) 유효한 위임만 대결.
 * 정상 + 이상 사용(B 기간·사유, 자기 위임·겹침, E 요청자 대결·남의 위임 회수, F 낡은 버전, 끝난·예정·다른 유형, A사로 B사, 직접 SQL)
 */
class ApprovalDelegateTest extends SysAdminTestSupport {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String API = "/api/platform/approvals";

    @Autowired
    ApprovalService approvals;

    @Autowired
    TenantRunner runner;

    @Autowired
    io.smarthappy.platform.approval.ApprovalDelegateService delegates;

    @Autowired
    io.smarthappy.platform.approval.ApprovalJobs jobs;

    private int seq;

    @BeforeEach
    void setUp() {
        setUpCompany("AD");
    }

    private static LocalDate today() {
        return LocalDate.now(SEOUL);
    }

    private <T> T as(String compCd, long userId, String loginId, Supplier<T> work) {
        LoginUser user = new LoginUser(userId, compCd, loginId, loginId, "ko", false);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        try {
            return runner.call(compCd, work);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static String constraintOf(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            return PgErrors.constraint(e).orElse("(none) " + e.getMessage());
        }
        return "(no error)";
    }

    private ResultActions delegate(RequestPostProcessor who, Object delegateId, String docType, LocalDate from, LocalDate to,
            String reason) throws Exception {
        String body = "{\"delegateId\":" + delegateId + ",\"docType\":" + (docType == null ? "null" : "\"" + docType + "\"")
                + ",\"fromDate\":" + (from == null ? "null" : "\"" + from + "\"") + ",\"toDate\":"
                + (to == null ? "null" : "\"" + to + "\"") + ",\"reason\":" + (reason == null ? "null" : "\"" + reason + "\"") + "}";
        return mvc.perform(json(post(API + "/delegations"), body).with(who));
    }

    private long created(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data"))
                .longValue();
    }

    /** hr 이 worker 결재로 올린다 */
    private long submit(String title) {
        String grp = "ADG" + (++seq);
        accounts.exec(comp, """
                insert into sys_auth_group (comp_cd, auth_grp_cd, auth_grp_nm, use_yn, created_by, updated_by)
                values (?, ?, 'n', 'N', 't', 't')""", comp, grp);
        return as(comp, hrId, "hr", () -> approvals.submit(TestDocType.DOC_TYPE, grp, title,
                List.of(new LineSpec(workerId, LineType.APPROVE))));
    }

    private ResultActions act(RequestPostProcessor who, long apr, String action) throws Exception {
        return mvc.perform(post(API + "/" + apr + "/" + action).with(csrf()).with(who)
                .header("X-Idempotency-Key", "key-ad-" + apr + "-" + action + "-" + (++seq))
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"대신\"}"));
    }

    private int delegVer(long id) {
        return accounts.query(comp, "select row_ver from apr_delegate where deleg_id = ?", Integer.class, id);
    }

    // ================================================================== 정상

    @Test
    void 대리가_대결하면_실제_처리자와_원_결재자가_남는다() throws Exception {
        created(delegate(worker, adminId, null, today(), today().plusDays(3), "휴가"));
        mvc.perform(get(API + "/delegations").with(worker)).andExpect(jsonPath("$.list[0].state").value("ACTIVE"))
                .andExpect(jsonPath("$.list[0].mine").value(true)).andExpect(jsonPath("$.list[0].delegateNm").value("admin 사용자"));
        mvc.perform(get(API + "/delegations").with(admin)).andExpect(jsonPath("$.list[0].mine").value(false));
        long apr = submit("ZT 설비 구매");
        // 대리 알림함·내 차례(대결 표시)
        mvc.perform(get("/api/platform/inbox").with(admin)).andExpect(jsonPath("$.list[0].eventCd").value("APR_TODO"));
        mvc.perform(get(API + "/box").param("box", "TODO").with(admin)).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].myTurn").value(true)).andExpect(jsonPath("$.list[0].delegated").value(true));
        mvc.perform(get(API + "/box").param("box", "TODO").with(worker)).andExpect(jsonPath("$.list[0].delegated").value(false));
        act(admin, apr, "approve").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select status || ':' || (acted_by = ?)::text from apr_line where apr_id = ?",
                String.class, adminId, apr)).isEqualTo("APPROVED:true");
        assertThat(accounts.query(comp, "select actor_id || ':' || on_behalf_of from apr_history where apr_id = ? and action = 'APPROVE'",
                String.class, apr)).isEqualTo(adminId + ":" + workerId);
        mvc.perform(get(API + "/" + apr).with(worker)).andExpect(jsonPath("$.data.lines[0].actedByNm").value("admin 사용자"))
                .andExpect(jsonPath("$.data.history[1].onBehalfOf").value(workerId));
        // 끝난 뒤에도 대리의 지난 결재에 남는다(대결 이력)
        mvc.perform(get(API + "/box").param("box", "PASSED").with(admin)).andExpect(jsonPath("$.list[0].aprId").value(apr));
        mvc.perform(get(API + "/" + apr).with(admin)).andExpect(status().isOk());
        // 본인 처리는 acted_by = 본인, 대결 표시 없음
        long apr2 = submit("ZT 본인");
        act(worker, apr2, "reject").andExpect(status().isOk());
        mvc.perform(get(API + "/" + apr2).with(worker)).andExpect(jsonPath("$.data.lines[0].actedByNm").value(Matchers.nullValue()));
    }

    @Test
    void 회수하면_대결도_목록도_멈추고_다시_만들_수_있다() throws Exception {
        long d = created(delegate(worker, adminId, null, today(), today().plusDays(1), "출장"));
        long apr = submit("ZT 회수");
        // 남의 위임 회수 404(대리도 못 한다), 낡은 버전 409, 회수 2번은 멱등
        mvc.perform(json(post(API + "/delegations/" + d + "/revoke"), "{\"rowVer\":" + delegVer(d) + "}").with(admin))
                .andExpect(status().isNotFound());
        mvc.perform(json(post(API + "/delegations/" + d + "/revoke"), "{\"rowVer\":" + (delegVer(d) + 5) + "}").with(worker))
                .andExpect(status().isConflict());
        mvc.perform(json(post(API + "/delegations/" + d + "/revoke"), "{\"rowVer\":" + delegVer(d) + "}").with(worker))
                .andExpect(status().isOk());
        mvc.perform(json(post(API + "/delegations/" + d + "/revoke"), "{\"rowVer\":0}").with(worker)).andExpect(status().isOk());
        mvc.perform(get(API + "/delegations").with(worker)).andExpect(jsonPath("$.list[0].state").value("REVOKED"));
        mvc.perform(get(API + "/box").param("box", "TODO").with(admin)).andExpect(jsonPath("$.total").value(0));
        act(admin, apr, "approve").andExpect(status().isForbidden());
        mvc.perform(get(API + "/" + apr).with(admin)).andExpect(status().isNotFound());
        // 회수한 기간은 겹침에서 빠진다
        created(delegate(worker, hrId, null, today(), today().plusDays(1), "다시"));
    }

    private int startNotices(long userId) {
        return accounts.query(comp, "select count(*) from sys_notify where user_id = ? and event_cd = 'APR_DELEG_START'",
                Integer.class, userId);
    }

    @Test
    void 위임_시작_알림은_오늘_시작이면_만들_때_미래면_그날_한_번() throws Exception {
        // 대리(admin)가 받을 결재 2건 + 요청자가 대리인 결재는 세지 않는다
        submit("ZT 하나");
        submit("ZT 둘");
        // 오늘 시작 → 만들 때 바로, 지금 대신 처리할 결재 2건
        long d = created(delegate(worker, adminId, null, today(), today().plusDays(2), "휴가"));
        assertThat(startNotices(adminId)).isEqualTo(1);
        mvc.perform(get("/api/platform/inbox").with(admin))
                .andExpect(jsonPath("$.list[?(@.eventCd == 'APR_DELEG_START')].title")
                        .value(Matchers.contains("worker 사용자 님의 결재를 대신 처리합니다 — 지금 대신 처리할 결재 2건")))
                .andExpect(jsonPath("$.list[?(@.eventCd == 'APR_DELEG_START')].linkProg").value(Matchers.contains("sys-approval")));
        // 정기 작업을 몇 번 돌려도 같은 위임에 다시 알리지 않는다(멱등)
        assertThat(jobs.delegationStart().allSucceeded()).isTrue();
        jobs.delegationStart();
        assertThat(startNotices(adminId)).isEqualTo(1);
        mvc.perform(json(post(API + "/delegations/" + d + "/revoke"), "{\"rowVer\":" + delegVer(d) + "}").with(worker))
                .andExpect(status().isOk());

        // 미래 시작(모레) → 만들 때는 없음, 정기 작업도 오늘은 없음
        long later = created(delegate(worker, hrId, null, today().plusDays(2), today().plusDays(4), "출장"));
        jobs.delegationStart();
        assertThat(startNotices(hrId)).isZero();
        // 그날(회사 날짜) 정기 작업 → 한 번, 다시 돌려도 한 번. hr 은 요청자라 대신 처리할 결재 0건
        assertThat(runner.call(comp, () -> delegates.notifyStarted(today().plusDays(2)))).isEqualTo(1);
        assertThat(runner.call(comp, () -> delegates.notifyStarted(today().plusDays(3)))).isZero();
        assertThat(startNotices(hrId)).isEqualTo(1);
        assertThat(accounts.query(comp, "select title_args::text from sys_notify where user_id = ? and event_cd = 'APR_DELEG_START'",
                String.class, hrId)).contains("\"0\"");
        // 회수한 위임·기간이 끝난 위임은 알리지 않는다
        mvc.perform(json(post(API + "/delegations/" + later + "/revoke"), "{\"rowVer\":" + delegVer(later) + "}").with(worker));
        long other = accounts.newUser(comp, "other", PW);
        long oneDay = created(delegate(admin, other, null, today().plusDays(1), today().plusDays(1), "하루"));
        mvc.perform(json(post(API + "/delegations/" + oneDay + "/revoke"), "{\"rowVer\":" + delegVer(oneDay) + "}").with(admin))
                .andExpect(status().isOk());
        assertThat(runner.call(comp, () -> delegates.notifyStarted(today().plusDays(1)))).isZero();
        assertThat(runner.call(comp, () -> delegates.notifyStarted(today().plusDays(10)))).isZero();
        assertThat(startNotices(other)).isZero();
    }

    @Test
    void 위임_시작_알림은_자기_회사_위임만() throws Exception {
        created(delegate(worker, adminId, null, today().plusDays(1), today().plusDays(1), "A사"));
        String compA = comp;
        long aAdmin = adminId;
        setUpCompany("ADC");
        // B사 범위에서 내일 정기 작업 → A사 위임은 보이지 않는다
        assertThat(runner.call(comp, () -> delegates.notifyStarted(today().plusDays(1)))).isZero();
        assertThat(accounts.query(compA, "select count(*) from sys_notify where user_id = ? and event_cd = 'APR_DELEG_START'",
                Integer.class, aAdmin)).isZero();
        assertThat(runner.call(compA, () -> delegates.notifyStarted(today().plusDays(1)))).isEqualTo(1);
    }

    // ================================================================== 이상 사용

    @Test
    void 입력_검사_자기_과거_순서_길이_유형_사유_겹침() throws Exception {
        delegate(worker, workerId, null, today(), today(), "x").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SELF_DELEGATE"));
        delegate(worker, adminId, null, today().minusDays(1), today(), "x")
                .andExpect(jsonPath("$.errors[0].code").value("PAST"));
        delegate(worker, adminId, null, today().plusDays(2), today().plusDays(1), "x")
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_RANGE"));
        delegate(worker, adminId, null, today(), today().plusDays(366), "x")
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_RANGE"));
        delegate(worker, adminId, "NOPE_DOC", today(), today(), "x").andExpect(jsonPath("$.errors[0].code").value("CODE"));
        delegate(worker, adminId, null, today(), today(), "  ").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        delegate(worker, adminId, null, today(), today(), "\\u200B\\u00A0")   // 보이지 않는 글자뿐(QA R4-a)
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        delegate(worker, adminId, null, today(), today(), "줄\\n바꿈").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        delegate(worker, adminId, null, today(), today(), "x".repeat(501)).andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        delegate(worker, null, null, null, null, null).andExpect(jsonPath("$.errors.length()").value(4));
        delegate(worker, "\"abc\"", null, today(), today(), "x").andExpect(status().isBadRequest());
        // 겹침: 전체 ↔ 특정 유형도 겹친다, 다른 대리여도
        created(delegate(worker, adminId, TestDocType.DOC_TYPE, today().plusDays(5), today().plusDays(9), "유형"));
        delegate(worker, hrId, null, today().plusDays(9), today().plusDays(12), "전체")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("OVERLAP"));
        created(delegate(worker, hrId, null, today().plusDays(10), today().plusDays(12), "다음"));
        // 사용 중지된 사용자에게는 위임할 수 없다(후보에도 없다)
        long gone = accounts.newUser(comp, "gone", PW);
        accounts.exec(comp, "update sys_user set use_yn = 'N' where user_id = ?", gone);
        delegate(worker, gone, null, today(), today(), "x").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        mvc.perform(get(API + "/delegations/candidates").param("q", "").with(worker))
                .andExpect(jsonPath("$.list[*].loginId").value(Matchers.containsInAnyOrder("admin", "hr")));
        mvc.perform(get(API + "/delegations/candidates").param("q", "%").with(worker)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(API + "/delegations/candidates").param("q", "x".repeat(51)).with(worker)).andExpect(status().isBadRequest());
        mvc.perform(get(API + "/delegations/doc-types").with(worker))
                .andExpect(jsonPath("$.list[*].docType").value(Matchers.hasItem(TestDocType.DOC_TYPE)));
    }

    @Test
    void 요청자_대결_예정_끝난_다른_유형은_대결_못한다() throws Exception {
        // worker → hr 위임, 그런데 hr 이 요청자 — 자기 결재를 대결할 수 없다
        long d = created(delegate(worker, hrId, null, today(), today().plusDays(1), "x"));
        long apr = submit("ZT 요청자");
        act(hr, apr, "approve").andExpect(status().isForbidden());
        mvc.perform(get(API + "/box").param("box", "TODO").with(hr)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/platform/inbox").with(hr)).andExpect(jsonPath("$.list[*].eventCd").value(Matchers.not(Matchers.hasItem("APR_TODO"))));
        mvc.perform(json(post(API + "/delegations/" + d + "/revoke"), "{\"rowVer\":" + delegVer(d) + "}").with(worker));
        // 예정(내일부터) — 오늘은 대결 못 한다
        created(delegate(worker, adminId, null, today().plusDays(1), today().plusDays(2), "예정"));
        act(admin, apr, "approve").andExpect(status().isForbidden());
        // 다른 유형만 위임 — 대결 못 한다
        long other = accounts.newUser(comp, "other", PW);
        accounts.exec(comp, """
                insert into apr_delegate (comp_cd, delegator_id, delegate_id, doc_type, from_date, to_date, reason, created_by, updated_by)
                values (?, ?, ?, 'OTHER_DOC', ?, ?, 'x', 't', 't')""", comp, workerId, other, today(), today());
        act(accounts.as(comp, other, "other"), apr, "approve").andExpect(status().isForbidden());
        // 끝난 위임(어제까지, 직접 SQL — 화면은 과거 시작을 막는다) — 대결 못 한다, 상태 ENDED
        long late = accounts.newUser(comp, "late", PW);
        accounts.exec(comp, """
                insert into apr_delegate (comp_cd, delegator_id, delegate_id, from_date, to_date, reason, created_by, updated_by)
                values (?, ?, ?, ?, ?, 'x', 't', 't')""", comp, workerId, late, today().minusDays(30), today().minusDays(20));
        act(accounts.as(comp, late, "late"), apr, "approve").andExpect(status().isForbidden());
        mvc.perform(get(API + "/delegations").with(worker))
                .andExpect(jsonPath("$.list[?(@.delegateNm == 'late 사용자')].state").value(Matchers.contains("ENDED")));
        assertThat(accounts.query(comp, "select status from apr_line where apr_id = ?", String.class, apr)).isEqualTo("CURRENT");
        // 본인은 언제나 처리한다
        act(worker, apr, "approve").andExpect(status().isOk());
    }

    @Test
    void A사로_B사_위임() throws Exception {
        long aDeleg = created(delegate(worker, adminId, null, today(), today().plusDays(1), "A사"));
        long aApr = submit("A사 결재");
        String compA = comp;
        long aWorker = workerId;
        String compB = accounts.newComp("ADB");
        long bUser = accounts.newUser(compB, "buser", PW);
        long bOther = accounts.newUser(compB, "bother", PW);
        var b = accounts.as(compB, bUser, "buser");
        // B사 사용자가 A사 사용자에게 위임 → 없는 사용자
        delegate(b, aWorker, null, today(), today(), "x").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        mvc.perform(get(API + "/delegations").with(b)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(API + "/delegations/candidates").param("q", "").with(b))
                .andExpect(jsonPath("$.list[*].loginId").value(Matchers.contains("bother")));
        mvc.perform(json(post(API + "/delegations/" + aDeleg + "/revoke"), "{\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        // B사 위임으로 A사 결재를 대결할 수 없다(직접 SQL 로 A사 사용자 id 를 넣어도 트리거)
        assertThat(constraintOf(() -> accounts.exec(compB, """
                insert into apr_delegate (comp_cd, delegator_id, delegate_id, from_date, to_date, reason, created_by, updated_by)
                values (?, ?, ?, ?, ?, 'x', 't', 't')""", compB, aWorker, bUser, today(), today()))).isEqualTo("fk_apr_delegate_comp_user");
        created(delegate(b, bOther, null, today(), today(), "B사"));
        act(b, aApr, "approve").andExpect(status().isNotFound());
        assertThat(accounts.query(compA, "select revoked_at is null from apr_delegate where deleg_id = ?", Boolean.class, aDeleg)).isTrue();
        assertThat(accounts.query(compB, "select count(*) from apr_delegate where deleg_id = ?", Integer.class, aDeleg)).isZero();
    }

    @Test
    void 대리를_사용_중지하면_진행_중_위임이_끝나고_위임자에게_알린다() throws Exception {
        // 진행 중 1건(대결 가능) + 예정 1건(다른 유형) + 이미 끝난 1건(직접 SQL)
        long active = created(delegate(worker, hrId, null, today(), today().plusDays(2), "출장"));
        long later = created(delegate(worker, hrId, TestDocType.DOC_TYPE, today().plusDays(5), today().plusDays(6), "예정"));
        long ended = accounts.query(comp, """
                insert into apr_delegate (comp_cd, delegator_id, delegate_id, from_date, to_date, reason, created_by, updated_by)
                values (?, ?, ?, ?, ?, 'x', 't', 't') returning deleg_id""", Long.class, comp, adminId, hrId,
                today().minusDays(10), today().minusDays(5));
        long apr = submit("ZT 대리 결재");   // hr 이 요청자 — 대결 대상은 아니지만 결재함·알림 확인용
        mvc.perform(get("/api/admin/users/" + hrId).with(admin)).andExpect(jsonPath("$.data.user.activeDelegatedCnt").value(2));
        // 관리자가 hr 사용 중지(화면 저장)
        mvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/users/" + hrId),
                userBody(null, "hr 사용자", "[\"HR\"]", "N", userVer(hrId))).with(admin)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select string_agg(deleg_id || ':' || coalesce(revoke_cd, '-'), ',' order by deleg_id) from apr_delegate where delegate_id = ?",
                String.class, hrId)).isEqualTo(active + ":DELEGATE_OFF," + later + ":DELEGATE_OFF," + ended + ":-");
        // 위임자(worker)에게 끝남 알림 한 건씩, 끝난 위임의 위임자(admin)에게는 없음
        assertThat(accounts.query(comp, "select count(*) from sys_notify where user_id = ? and event_cd = 'APR_DELEG_ENDED'",
                Integer.class, workerId)).isEqualTo(2);
        assertThat(accounts.query(comp, "select count(*) from sys_notify where user_id = ? and event_cd = 'APR_DELEG_ENDED'",
                Integer.class, adminId)).isZero();
        mvc.perform(get(API + "/delegations").with(worker))
                .andExpect(jsonPath("$.list[?(@.delegId == " + active + ")].revokeCd").value(Matchers.contains("DELEGATE_OFF")))
                .andExpect(jsonPath("$.list[?(@.delegId == " + active + ")].state").value(Matchers.contains("REVOKED")));
        mvc.perform(get("/api/admin/users/" + hrId).with(admin)).andExpect(jsonPath("$.data.user.activeDelegatedCnt").value(0));
        // 다시 사용으로 바꿔도 위임은 되살아나지 않는다(위임자가 새로) — 다시 사용 중지해도 알림이 겹치지 않는다
        mvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/users/" + hrId),
                userBody(null, "hr 사용자", "[\"HR\"]", "Y", userVer(hrId))).with(admin)).andExpect(status().isOk());
        mvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/users/" + hrId),
                userBody(null, "hr 사용자", "[\"HR\"]", "N", userVer(hrId))).with(admin)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from sys_notify where user_id = ? and event_cd = 'APR_DELEG_ENDED'",
                Integer.class, workerId)).isEqualTo(2);
        assertThat(accounts.query(comp, "select status from apr_line where apr_id = ?", String.class, apr)).isEqualTo("CURRENT");
        // 위임자 본인 회수는 SELF
        long self = created(delegate(admin, workerId, null, today(), today(), "본인"));
        mvc.perform(json(post(API + "/delegations/" + self + "/revoke"), "{\"rowVer\":" + delegVer(self) + "}").with(admin))
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select revoke_cd from apr_delegate where deleg_id = ?", String.class, self)).isEqualTo("SELF");
    }

    @Test
    void 대리_사용_중지로_끝나는_위임은_자기_회사_것만() throws Exception {
        long aDeleg = created(delegate(worker, adminId, null, today(), today().plusDays(1), "A사"));
        String compA = comp;
        long aWorker = workerId;
        long aAdmin = adminId;
        setUpCompany("ADD");
        // B사 관리자는 A사 사용자를 사용 중지할 수 없다 → A사 위임 그대로
        mvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/users/" + aAdmin),
                userBody(null, "x", "[]", "N", 0)).with(admin)).andExpect(status().isNotFound());
        // B사 사용자 사용 중지가 A사 위임을 건드리지 않는다
        long bDeleg = created(delegate(worker, hrId, null, today(), today().plusDays(1), "B사"));
        mvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/users/" + hrId),
                userBody(null, "hr 사용자", "[\"HR\"]", "N", userVer(hrId))).with(admin)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select revoke_cd from apr_delegate where deleg_id = ?", String.class, bDeleg)).isEqualTo("DELEGATE_OFF");
        assertThat(accounts.query(compA, "select revoked_at is null from apr_delegate where deleg_id = ?", Boolean.class, aDeleg)).isTrue();
        assertThat(accounts.query(compA, "select count(*) from sys_notify where user_id = ? and event_cd = 'APR_DELEG_ENDED'",
                Integer.class, aWorker)).isZero();
    }

    @Test
    void 직접_SQL_고정_회수_삭제_겹침_자기() {
        long d = accounts.query(comp, """
                insert into apr_delegate (comp_cd, delegator_id, delegate_id, from_date, to_date, reason, created_by, updated_by)
                values (?, ?, ?, ?, ?, 'x', 't', 't') returning deleg_id""", Long.class, comp, workerId, adminId, today(), today());
        assertThat(constraintOf(() -> accounts.exec(comp, "update apr_delegate set to_date = to_date + 1 where deleg_id = ?", d)))
                .isEqualTo("ck_apr_delegate_fixed");
        assertThat(constraintOf(() -> accounts.exec(comp, "update apr_delegate set delegate_id = ? where deleg_id = ?", hrId, d)))
                .isEqualTo("ck_apr_delegate_fixed");
        assertThat(constraintOf(() -> accounts.exec(comp, """
                insert into apr_delegate (comp_cd, delegator_id, delegate_id, from_date, to_date, reason, created_by, updated_by)
                values (?, ?, ?, ?, ?, 'x', 't', 't')""", comp, workerId, hrId, today(), today()))).isEqualTo("ck_apr_delegate_overlap");
        assertThat(constraintOf(() -> accounts.exec(comp, """
                insert into apr_delegate (comp_cd, delegator_id, delegate_id, from_date, to_date, reason, created_by, updated_by)
                values (?, ?, ?, ?, ?, 'x', 't', 't')""", comp, hrId, hrId, today(), today()))).isEqualTo("ck_apr_delegate_self");
        assertThat(constraintOf(() -> accounts.exec(comp, """
                insert into apr_delegate (comp_cd, delegator_id, delegate_id, from_date, to_date, reason, created_by, updated_by)
                values (?, ?, ?, ?, ?, 'x', 't', 't')""", comp, hrId, adminId, today(), today().plusDays(366))))
                .isEqualTo("ck_apr_delegate_period");
        accounts.exec(comp, "update apr_delegate set revoked_at = now() where deleg_id = ?", d);
        assertThat(constraintOf(() -> accounts.exec(comp, "update apr_delegate set reason = 'y' where deleg_id = ?", d)))
                .isEqualTo("ck_apr_delegate_revoked");
        assertThat(constraintOf(() -> accounts.exec(comp, "delete from apr_delegate where deleg_id = ?", d))).startsWith("(none)");
        assertThat(accounts.query(comp, "select count(*) from apr_delegate where deleg_id = ?", Integer.class, d)).isOne();
        // 감사 이력이 남는다
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'apr_delegate' and row_key = ?",
                Integer.class, String.valueOf(d))).isEqualTo(2);
    }
}
