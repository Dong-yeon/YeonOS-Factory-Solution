package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.approval.ApprovalService;
import io.smarthappy.platform.approval.ApprovalService.LineSpec;
import io.smarthappy.platform.approval.ApprovalService.LineType;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.log.AccessLogService.Result;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.TestMessaging.TestDocType;

/**
 * 시스템관리 3단계 (docs/36 12장) — 접속·보안 로그, 변경 이력 모아 보기, 받은 의견함, 내 결재함.
 * 정상 흐름 + 이상 사용(A사로 B사, E 화면 권한 우회(Q17), B 기간·조건, F 낡은 버전, 멱등, 직접 SQL)
 */
class SysLogAdminTest extends SysAdminTestSupport {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    AccessLogService accessLog;

    @Autowired
    ApprovalService approvals;

    @Autowired
    TenantRunner runner;

    @BeforeEach
    void setUp() {
        setUpCompany("SL");
    }

    private static LocalDate today() {
        return LocalDate.now(SEOUL);
    }

    private static MockHttpServletRequestBuilder period(MockHttpServletRequestBuilder b) {
        return b.param("from", today().minusDays(1).toString()).param("to", today().toString());
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

    // ================================================================== 코드 = 이벤트 집합

    @Test
    void 접속_로그_이벤트_코드는_AccessEvent_와_같은_집합() {
        Set<String> codes = Set.of(accounts.query(comp, "select string_agg(code, ',') from sys_code where grp_cd = 'ACCESS_EVENT'",
                String.class).split(","));
        assertThat(codes).as("새 AccessEvent 를 넣으면 ACCESS_EVENT 코드·문구도(접속 로그 화면 조건·표시)")
                .isEqualTo(Arrays.stream(AccessEvent.values()).map(Enum::name).collect(Collectors.toSet()));
    }

    // ================================================================== 접속·보안 로그

    @Test
    void 접속_로그는_자기_회사만_조건_기간_권한() throws Exception {
        String compB = accounts.newComp("SLB");
        long bUser = accounts.newUser(compB, "worker", PW);
        accessLog.record(AccessEvent.LOGIN_FAIL, comp, workerId, "worker", null, null, Result.FAIL, "bad");
        accessLog.record(AccessEvent.GRANT_DENIED, comp, hrId, "hr", "sys-user", "SAVE", Result.DENIED, "GRANT_EXCEEDS");
        accessLog.record(AccessEvent.LOGIN_FAIL, compB, bUser, "worker", null, null, Result.FAIL, "B사 기록");
        mvc.perform(period(get("/api/admin/access-logs")).param("loginId", "worker").with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].detail").value("bad"));
        mvc.perform(period(get("/api/admin/access-logs")).param("grantOnly", "true").with(admin))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.list[0].eventCd").value("GRANT_DENIED"))
                .andExpect(jsonPath("$.list[0].progNm").value("사용자 관리"));
        mvc.perform(period(get("/api/admin/access-logs")).param("eventCd", "LOGIN_FAIL").param("resultCd", "FAIL").with(admin))
                .andExpect(jsonPath("$.list[*].detail").value(Matchers.not(Matchers.hasItem("B사 기록"))));
        // IP 앞부분·LIKE 특수 문자는 글자로
        mvc.perform(period(get("/api/admin/access-logs")).param("loginId", "%").with(admin)).andExpect(jsonPath("$.total").value(0));
        // B 기간: 없음·거꾸로·상한 초과, 없는 이벤트
        mvc.perform(get("/api/admin/access-logs").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/access-logs").param("from", today().toString()).param("to", today().minusDays(1).toString())
                .with(admin)).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mvc.perform(get("/api/admin/access-logs").param("from", today().minusDays(100).toString()).param("to", today().toString())
                .with(admin)).andExpect(jsonPath("$.code").value("RANGE_TOO_LONG"));
        mvc.perform(period(get("/api/admin/access-logs")).param("eventCd", "DROP TABLE").with(admin)).andExpect(status().isBadRequest());
        // E: 로그 화면 권한 없음
        mvc.perform(period(get("/api/admin/access-logs")).with(hr)).andExpect(status().isForbidden());
        mvc.perform(period(get("/api/admin/access-logs")).with(worker)).andExpect(status().isForbidden());
    }

    @Test
    void 보안_경고는_로그_담당에게_알림함으로_한_시간에_한_번() {
        String compB = accounts.newComp("SLB");
        long bAdmin = accounts.newUser(compB, "badmin", PW);
        adminGroup(compB, "ADMIN", bAdmin);
        accessLog.record(AccessEvent.TENANT_FORBIDDEN, comp, workerId, "worker", null, null, Result.DENIED, "GET /api/x");
        accessLog.record(AccessEvent.TENANT_FORBIDDEN, comp, workerId, "worker", null, null, Result.DENIED, "GET /api/y");
        assertThat(accounts.query(comp, """
                select count(*) from sys_notify where user_id = ? and event_cd = 'SECURITY_ALERT'""", Integer.class, adminId))
                .isEqualTo(1);
        assertThat(accounts.query(comp, """
                select link_params::text from sys_notify where user_id = ? and event_cd = 'SECURITY_ALERT'""", String.class, adminId))
                .contains("TENANT_FORBIDDEN");
        // 로그 화면을 못 보는 사람(부관리자·작업자)·다른 회사 관리자는 받지 않는다
        assertThat(accounts.query(comp, """
                select count(*) from sys_notify where user_id in (?, ?) and event_cd = 'SECURITY_ALERT'""", Integer.class, hrId, workerId))
                .isZero();
        assertThat(accounts.query(compB, "select count(*) from sys_notify where event_cd = 'SECURITY_ALERT'", Integer.class)).isZero();
        // 로그 화면 권한을 받은 보안 담당은 다음 이벤트부터 받는다
        accounts.grant(comp, workerId, "SEC", "sys-access-log", "SEARCH");
        accessLog.record(AccessEvent.TENANT_FORBIDDEN, comp, hrId, "hr", null, null, Result.DENIED, "GET /api/z");
        assertThat(accounts.query(comp, """
                select count(*) from sys_notify where user_id = ? and event_cd = 'SECURITY_ALERT'""", Integer.class, workerId))
                .isEqualTo(1);
    }

    // ================================================================== 변경 이력

    @Test
    void 변경_이력은_볼_수_있는_화면의_표만() throws Exception {
        // 사용자 이름 변경(sys_user — sys-user), 메뉴 추가(sys_menu — sys-menu: hr 권한 없음)
        mvc.perform(json(put("/api/admin/users/" + workerId), userBody(null, "작업자-새이름", "[\"WORK\"]", "Y", userVer(workerId)))
                .with(admin)).andExpect(status().isOk());
        long menuId = accounts.addMenu(comp, null, "ZT 폴더", null, 99);
        // 관리자: 표 선택지 전부, 이름 변경은 바뀐 칸만(버전·수정 시각 빼고)
        MvcResult r = mvc.perform(period(get("/api/admin/audit-logs")).param("tableNm", "sys_user").param("action", "UPDATE")
                .with(admin)).andExpect(jsonPath("$.list[0].tableProgNm").value("사용자 관리")).andReturn();
        long auditId = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.list[0].auditId")).longValue();
        mvc.perform(get("/api/admin/audit-logs/" + auditId).with(admin))
                .andExpect(jsonPath("$.data.changes[?(@.column=='user_nm')].after").value(Matchers.hasItem("작업자-새이름")))
                .andExpect(jsonPath("$.data.changes[?(@.column=='row_ver')]").isEmpty());
        mvc.perform(get("/api/admin/audit-logs/tables").with(admin))
                .andExpect(jsonPath("$.list[*].tableNm").value(Matchers.hasItems("sys_user", "sys_menu")));
        // 부관리자(사용자·권한그룹·거래처 화면만): 메뉴 표는 선택지에 없고, 고르면 403, 그 행 상세는 404
        mvc.perform(get("/api/admin/audit-logs/tables").with(hrSearch()))
                .andExpect(jsonPath("$.list[*].tableNm").value(Matchers.not(Matchers.hasItem("sys_menu"))))
                .andExpect(jsonPath("$.list[*].tableNm").value(Matchers.hasItem("sys_user")));
        mvc.perform(period(get("/api/admin/audit-logs")).param("tableNm", "sys_menu").with(hrSearch())).andExpect(status().isForbidden());
        long menuAudit = accounts.query(comp, """
                select max(audit_id) from sys_audit_log where table_nm = 'sys_menu' and row_key = ?""", Long.class, String.valueOf(menuId));
        mvc.perform(get("/api/admin/audit-logs/" + menuAudit).with(hrSearch())).andExpect(status().isNotFound());
        mvc.perform(period(get("/api/admin/audit-logs")).param("size", "500").with(hrSearch()))
                .andExpect(jsonPath("$.list[*].tableNm").value(Matchers.not(Matchers.hasItem("sys_menu"))));
        // 권한 변경만: 소속·권한 표·그룹·사용자 표만
        mvc.perform(period(get("/api/admin/audit-logs")).param("grantOnly", "true").param("size", "500").with(admin))
                .andExpect(jsonPath("$.list[*].tableNm").value(Matchers.everyItem(
                        Matchers.oneOf("sys_user_auth", "sys_auth_prog", "sys_auth_group", "sys_user"))));
        // 표 이름 형식(주입)·화면 권한 없음
        mvc.perform(period(get("/api/admin/audit-logs")).param("tableNm", "sys_user;drop").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(period(get("/api/admin/audit-logs")).with(worker)).andExpect(status().isForbidden());
    }

    /** 부관리자 + 변경 이력 화면 조회 권한 */
    private RequestPostProcessor hrSearch() {
        accounts.grant(comp, hrId, "HR", "sys-audit-log", "SEARCH");
        return hr;
    }

    @Test
    void 비밀번호_초기화_감사는_값_대신_changed() throws Exception {
        mvc.perform(json(post("/api/admin/users/" + workerId + "/reset-password"), "{\"rowVer\":" + userVer(workerId) + "}")
                .with(admin)).andExpect(status().isOk());
        long id = accounts.query(comp, """
                select max(audit_id) from sys_audit_log where table_nm = 'sys_user' and row_key = ? and action = 'UPDATE'""",
                Long.class, String.valueOf(workerId));
        mvc.perform(get("/api/admin/audit-logs/" + id).with(admin))
                .andExpect(jsonPath("$.data.changes[?(@.column=='pwd_hash')].after").value(Matchers.contains("(changed)")))
                .andExpect(jsonPath("$.data.changes[*].after").value(Matchers.not(Matchers.hasItem(Matchers.startsWith("{bcrypt}")))));
    }

    // ================================================================== 받은 의견

    private long sendFeedback(RequestPostProcessor who, String text) throws Exception {
        MvcResult r = mvc.perform(json(post("/api/platform/feedback"), "{\"progCd\":\"mst-cust\",\"content\":\"" + text + "\"}")
                .with(who)).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private int fbVer(String compCd, long id) {
        return accounts.query(compCd, "select row_ver from sys_feedback where feedback_id = ?", Integer.class, id);
    }

    @Test
    void 의견함_상태_전이와_처리_알림() throws Exception {
        long fb = sendFeedback(worker, "거래처 검색이 느려요");
        mvc.perform(period(get("/api/admin/feedback")).param("status", "NEW").with(admin))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.list[0].progNm").value("거래처관리"))
                .andExpect(jsonPath("$.list[0].loginId").value("worker"));
        mvc.perform(json(put("/api/admin/feedback/" + fb + "/status"), "{\"status\":\"SEEN\",\"rowVer\":" + fbVer(comp, fb) + "}")
                .with(admin)).andExpect(status().isOk());
        // F: 낡은 버전
        mvc.perform(json(put("/api/admin/feedback/" + fb + "/status"), "{\"status\":\"DONE\",\"rowVer\":0}").with(admin))
                .andExpect(status().isConflict());
        mvc.perform(json(put("/api/admin/feedback/" + fb + "/status"), "{\"status\":\"DONE\",\"rowVer\":" + fbVer(comp, fb) + "}")
                .with(admin)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/feedback/" + fb).with(admin)).andExpect(jsonPath("$.data.handledBy").value("admin"));
        // 보낸 사람 알림함 — 링크 = 의견을 보낸 화면
        mvc.perform(get("/api/platform/inbox").with(worker)).andExpect(jsonPath("$.list[0].eventCd").value("FEEDBACK_DONE"))
                .andExpect(jsonPath("$.list[0].linkProg").value("mst-cust"))
                .andExpect(jsonPath("$.list[0].title").value("보내신 의견을 처리했습니다: 거래처 검색이 느려요"));
        // 끝난 의견은 되돌릴 수 없다 / 같은 상태 다시 = 멱등 / 없는 상태
        mvc.perform(json(put("/api/admin/feedback/" + fb + "/status"), "{\"status\":\"NEW\",\"rowVer\":" + fbVer(comp, fb) + "}")
                .with(admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("STATE"));
        mvc.perform(json(put("/api/admin/feedback/" + fb + "/status"), "{\"status\":\"DONE\",\"rowVer\":" + fbVer(comp, fb) + "}")
                .with(admin)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from sys_notify where event_cd = 'FEEDBACK_DONE'", Integer.class)).isEqualTo(1);
        mvc.perform(json(put("/api/admin/feedback/" + fb + "/status"), "{\"status\":\"GONE\",\"rowVer\":1}").with(admin))
                .andExpect(status().isBadRequest());
        // E: 의견함 권한 없음
        mvc.perform(period(get("/api/admin/feedback")).with(worker)).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/admin/feedback/" + fb + "/status"), "{\"status\":\"SEEN\",\"rowVer\":1}").with(hr))
                .andExpect(status().isForbidden());
    }

    @Test
    void 직접_SQL_의견_내용은_고정_전이는_트리거() throws Exception {
        long fb = sendFeedback(worker, "원문");
        assertThat(constraintOf(() -> accounts.exec(comp, "update sys_feedback set content = '바꿈' where feedback_id = ?", fb)))
                .isEqualTo("ck_sys_feedback_fixed");
        accounts.exec(comp, "update sys_feedback set status = 'DONE', handled_by = 'x', handled_at = now() where feedback_id = ?", fb);
        assertThat(constraintOf(() -> accounts.exec(comp, "update sys_feedback set status = 'SEEN' where feedback_id = ?", fb)))
                .isEqualTo("ck_sys_feedback_transition");
        assertThat(constraintOf(() -> accounts.exec(comp, "update sys_feedback set handled_by = null where feedback_id = ?", fb)))
                .isEqualTo("ck_sys_feedback_handled");
    }

    // ================================================================== 내 결재함

    @Test
    void 내_결재함_차례_올린_것_지난_것과_처리() throws Exception {
        accounts.exec(comp, """
                insert into sys_auth_group (comp_cd, auth_grp_cd, auth_grp_nm, use_yn, created_by, updated_by)
                values (?, 'SLDOC', 'n', 'N', 't', 't')""", comp);
        long apr = as(comp, hrId, "hr", () -> approvals.submit(TestDocType.DOC_TYPE, "SLDOC", "ZT 설비 구매",
                List.of(new LineSpec(workerId, LineType.APPROVE), new LineSpec(adminId, LineType.REF))));
        mvc.perform(get("/api/platform/approvals/box").param("box", "TODO").with(worker))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.list[0].myTurn").value(true));
        mvc.perform(get("/api/platform/approvals/box").param("box", "REQUESTED").with(hr))
                .andExpect(jsonPath("$.list[0].aprId").value(apr)).andExpect(jsonPath("$.list[0].mine").value(true))
                .andExpect(jsonPath("$.list[0].myTurn").value(false));
        mvc.perform(get("/api/platform/approvals/box").param("box", "PASSED").with(admin))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.list[0].myLineType").value("REF"));
        mvc.perform(get("/api/platform/approvals/box").param("box", "TODO").with(hr)).andExpect(jsonPath("$.total").value(0));
        // 알림 링크 = 내 결재함
        mvc.perform(get("/api/platform/inbox").with(worker)).andExpect(jsonPath("$.list[0].linkProg").value("sys-approval"));
        // 처리 — 같은 키 재전송은 409
        mvc.perform(post("/api/platform/approvals/" + apr + "/approve").with(csrf()).with(worker).header("X-Idempotency-Key", "key-sl-0001")
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"ok\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/platform/approvals/" + apr + "/approve").with(csrf()).with(worker).header("X-Idempotency-Key", "key-sl-0001")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isConflict());
        mvc.perform(get("/api/platform/approvals/box").param("box", "PASSED").with(worker))
                .andExpect(jsonPath("$.list[0].status").value("APPROVED"));
        // 없는 함
        mvc.perform(get("/api/platform/approvals/box").param("box", "ALL").with(worker)).andExpect(status().isBadRequest());
        // 권한 행이 없는 새 사용자도 결재함은 열린다(개인 화면) — 자기 것이 없으니 비어 있다
        long nobody = accounts.newUser(comp, "nobody", PW);
        mvc.perform(get("/page/sys-approval").with(accounts.as(comp, nobody, "nobody"))).andExpect(status().isOk());
        mvc.perform(get("/api/platform/approvals/box").param("box", "PASSED").with(accounts.as(comp, nobody, "nobody")))
                .andExpect(jsonPath("$.total").value(0));
    }

    // ================================================================== A사로 B사

    @Test
    void A사로_B사_로그_감사_의견_결재함() throws Exception {
        long fb = sendFeedback(worker, "A사 의견");
        accessLog.record(AccessEvent.LOGIN_FAIL, comp, workerId, "worker", null, null, Result.FAIL, "A사 로그");
        mvc.perform(json(put("/api/admin/users/" + workerId), userBody(null, "A사 이름", "[\"WORK\"]", "Y", userVer(workerId)))
                .with(admin)).andExpect(status().isOk());
        long aAudit = accounts.query(comp, "select max(audit_id) from sys_audit_log where table_nm = 'sys_user'", Long.class);
        accounts.exec(comp, """
                insert into sys_auth_group (comp_cd, auth_grp_cd, auth_grp_nm, use_yn, created_by, updated_by)
                values (?, 'SLA', 'n', 'N', 't', 't')""", comp);
        long apr = as(comp, hrId, "hr", () -> approvals.submit(TestDocType.DOC_TYPE, "SLA", "A사 결재",
                List.of(new LineSpec(workerId, LineType.APPROVE))));

        String compB = accounts.newComp("SLB");
        long bAdmin = accounts.newUser(compB, "badmin", PW);
        adminGroup(compB, "ADMIN", bAdmin);
        var b = accounts.as(compB, bAdmin, "badmin");
        mvc.perform(period(get("/api/admin/access-logs")).param("size", "500").with(b))
                .andExpect(jsonPath("$.list[*].detail").value(Matchers.not(Matchers.hasItem("A사 로그"))));
        mvc.perform(period(get("/api/admin/audit-logs")).param("tableNm", "sys_user").param("size", "500").with(b))
                .andExpect(jsonPath("$.list[*].auditId").value(Matchers.not(Matchers.hasItem((int) aAudit))));
        mvc.perform(get("/api/admin/audit-logs/" + aAudit).with(b)).andExpect(status().isNotFound());
        mvc.perform(period(get("/api/admin/feedback")).with(b)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/admin/feedback/" + fb).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/admin/feedback/" + fb + "/status"), "{\"status\":\"DONE\",\"rowVer\":0}").with(b))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/platform/approvals/box").param("box", "PASSED").with(b)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/platform/approvals/" + apr).with(b)).andExpect(status().isNotFound());
        assertThat(accounts.query(comp, "select status from sys_feedback where feedback_id = ?", String.class, fb)).isEqualTo("NEW");
    }
}
