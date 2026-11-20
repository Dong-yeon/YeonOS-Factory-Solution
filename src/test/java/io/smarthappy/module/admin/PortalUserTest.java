package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.approval.ApprovalDelegateService;
import io.smarthappy.platform.approval.ApprovalService;
import io.smarthappy.platform.approval.ApprovalService.LineSpec;
import io.smarthappy.platform.approval.ApprovalService.LineType;
import io.smarthappy.platform.auth.AuthMapper;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.notify.NotificationService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import io.smarthappy.support.TestMessaging.TestDocType;
import io.smarthappy.support.web.PortalProbeController;

/**
 * 협력사 포털 사용자 — 플랫폼 (docs/42 7장 1). 정상 + 이상 사용:
 * E 권한(포털 밖·개인 화면·회사 사용자의 포털, 두 겹 막기, A사로 B사), F 흐름 우회(유형·소속 바꾸기 — 직접 SQL 포함),
 * D 기준정보 변경(거래처 사용 중지 → 세션 끊김), B 입력(유형·소속 칸)
 */
class PortalUserTest extends SysAdminTestSupport {

    private static final AtomicInteger IP = new AtomicInteger();

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    TenantRunner runner;

    @Autowired
    PermissionService permissions;

    @Autowired
    AuthMapper authMapper;

    @Autowired
    NotificationService notifications;

    @Autowired
    ApprovalService approvals;

    @Autowired
    ApprovalDelegateService delegates;

    @Autowired
    PortalProbeController probe;

    @Autowired
    UserAdminApiController userApi;

    @Autowired
    UserDirectory users;

    long s1;        // 구매 거래처
    long s2;        // 겸용
    long sales;     // 판매 전용
    long stopped;   // 사용 중지 구매 거래처
    long supId;     // s1 소속 협력사 사용자
    RequestPostProcessor sup;

    @BeforeEach
    void setUp() {
        setUpCompany("PU");
        s1 = cust(comp, "S1", "PURCHASE");
        s2 = cust(comp, "S2", "BOTH");
        sales = cust(comp, "C3", "SALES");
        stopped = cust(comp, "S4", "PURCHASE");
        accounts.exec(comp, "update mst_cust set use_yn = 'N' where cust_id = ?", stopped);
        supId = supplier(comp, "sup1", s1);
        sup = supplierAuth(comp, supId, "sup1", s1);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private long cust(String compCd, String cd, String type) {
        return accounts.query(compCd, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, ?, ?, ?, 't', 't') returning cust_id""", Long.class, compCd, cd, cd + " 상사", type);
    }

    private long supplier(String compCd, String login, long custId) {
        return accounts.query(compCd, """
                insert into sys_user (comp_cd, login_id, user_nm, pwd_hash, user_type, cust_id, created_by, updated_by)
                values (?, ?, ?, ?, 'SUPPLIER', ?, 't', 't') returning user_id""", Long.class,
                compCd, login, login + " 협력사", encoder.encode(PW), custId);
    }

    private static LoginUser supplierUser(String compCd, long id, String login, long custId) {
        return new LoginUser(id, compCd, login, login, "ko", false, LoginUser.SUPPLIER, custId);
    }

    private static RequestPostProcessor supplierAuth(String compCd, long id, String login, long custId) {
        return SecurityMockMvcRequestPostProcessors.authentication(UsernamePasswordAuthenticationToken.authenticated(
                supplierUser(compCd, id, login, custId), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private <T> T as(LoginUser user, Supplier<T> work) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        try {
            return runner.call(user.compCd(), work);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private MvcResult formLogin(String compCd, String loginId, String password) throws Exception {
        String ip = "10.42." + (IP.incrementAndGet() % 250 + 1) + ".7";
        return mvc.perform(post("/login").with(csrf()).with(r -> { r.setRemoteAddr(ip); return r; })
                .param("compCd", compCd).param("loginId", loginId).param("password", password)).andReturn();
    }

    /** DB 제약·트리거가 이 이름으로 막았나 (이름은 메시지가 아니라 PG 오류 정보에 실린다) */
    private static void assertConstraint(ThrowingCallable call, String name) {
        Throwable t = catchThrowable(call);
        assertThat(t).as("막혀야 한다: " + name).isNotNull();
        assertThat(PgErrors.constraint(t)).as(String.valueOf(t)).contains(name);
    }

    private String selfServiceProg() {
        return accounts.query(comp, "select min(prog_cd) from sys_prog where self_service_yn = 'Y' and use_yn = 'Y'", String.class);
    }

    // ---------- DB 규칙 (트리거·제약 — 서비스를 거치지 않은 직접 SQL) ----------

    @Test
    void 유형과_소속은_만든_뒤_바꿀_수_없다_직접_SQL_도() {
        assertConstraint(() -> accounts.exec(comp, "update sys_user set user_type = 'INTERNAL', cust_id = null where user_id = ?", supId), "ck_sys_user_type_fixed");
        assertConstraint(() -> accounts.exec(comp, "update sys_user set cust_id = ? where user_id = ?", s2, supId), "ck_sys_user_type_fixed");
        assertConstraint(() -> accounts.exec(comp, "update sys_user set user_type = 'SUPPLIER', cust_id = ? where user_id = ?", s1, workerId), "ck_sys_user_type_fixed");
        // 다른 칸은 그대로 바꿀 수 있다(트리거는 유형·소속 칸에만)
        assertThat(accounts.exec(comp, "update sys_user set user_nm = '새이름' where user_id = ?", supId)).isEqualTo(1);
    }

    @Test
    void 협력사_사용자는_같은_회사의_사용_중_구매_겸용_거래처_소속만() {
        String other = accounts.newComp("PV");
        long otherCust = cust(other, "S1", "PURCHASE");
        assertThat(supplier(comp, "sup-both", s2)).isPositive();
        assertConstraint(() -> supplier(comp, "sup-sales", sales), "fk_sys_user_cust_comp");
        assertConstraint(() -> supplier(comp, "sup-stop", stopped), "fk_sys_user_cust_comp");
        // A사로 B사: 다른 회사 거래처 id 를 넣어도(FK 는 RLS 를 무시) 트리거가 막는다
        assertConstraint(() -> supplier(comp, "sup-other", otherCust), "fk_sys_user_cust_comp");
        assertConstraint(() -> accounts.exec(comp, """
                insert into sys_user (comp_cd, login_id, user_nm, pwd_hash, user_type, created_by, updated_by)
                values (?, 'sup-none', 'x', ?, 'SUPPLIER', 't', 't')""", comp, encoder.encode(PW)), "ck_sys_user_type_cust");
        assertConstraint(() -> accounts.exec(comp, """
                insert into sys_user (comp_cd, login_id, user_nm, pwd_hash, cust_id, created_by, updated_by)
                values (?, 'int-cust', 'x', ?, ?, 't', 't')""", comp, encoder.encode(PW), s1), "ck_sys_user_type_cust");
    }

    @Test
    void 협력사_사용자는_권한그룹_결재_요청자_위임에_못_들어간다_직접_SQL() {
        assertConstraint(() -> accounts.exec(comp,
                "insert into sys_user_auth (comp_cd, user_id, auth_grp_cd, created_by) values (?, ?, 'ADMIN', 't')", comp, supId), "ck_internal_user_only");
        assertConstraint(() -> accounts.exec(comp, """
                insert into apr_request (comp_cd, doc_type, biz_key, title, requester_id, created_by, updated_by)
                values (?, 'TEST_DOC', 'X1', 'x', ?, 't', 't')""", comp, supId), "ck_internal_user_only");
        assertConstraint(() -> accounts.exec(comp, """
                insert into apr_delegate (comp_cd, delegator_id, delegate_id, from_date, to_date, reason, created_by, updated_by)
                values (?, ?, ?, current_date, current_date + 3, 'x', 't', 't')""", comp, adminId, supId), "ck_internal_user_only");
    }

    // ---------- 로그인·경계 ----------

    @Test
    void 협력사_사용자는_같은_로그인으로_들어와_포털로_가고_포털만_열린다() throws Exception {
        MvcResult r = formLogin(comp, "sup1", PW);
        assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/portal");
        MockHttpSession s = (MockHttpSession) r.getRequest().getSession(false);
        mvc.perform(get(PortalProbeController.ME).session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.custId").value(s1)).andExpect(jsonPath("$.compCd").value(comp));
        mvc.perform(get("/api/platform/session").session(s)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/users").session(s)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PORTAL_FORBIDDEN"));
        mvc.perform(get("/main").session(s)).andExpect(redirectedUrl("/portal"));
        mvc.perform(get("/page/sys-user").session(s)).andExpect(redirectedUrl("/portal"));
    }

    @Test
    void 협력사_사용자는_내부_API_개인_화면_홈_모두_막힌다() throws Exception {
        for (String url : List.of("/api/admin/users", "/api/platform/inbox", "/api/home/cards", "/api/platform/approvals/inbox",
                "/api/platform/my-info", "/api/portalx", "/api/platform/menus")) {
            mvc.perform(get(url).with(sup)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PORTAL_FORBIDDEN"));
        }
        mvc.perform(post("/api/admin/users").with(sup).with(csrf())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PORTAL_FORBIDDEN"));
        mvc.perform(get("/portalx").with(sup)).andExpect(redirectedUrl("/portal"));
    }

    @Test
    void 회사_사용자는_포털_API_화면을_쓰지_않는다() throws Exception {
        mvc.perform(get(PortalProbeController.ME).with(admin)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PORTAL_FORBIDDEN"));
        mvc.perform(get("/portal").with(admin)).andExpect(status().isForbidden());
        mvc.perform(get("/portal/po").with(worker)).andExpect(status().isForbidden());
    }

    @Test
    void 두_겹_막기_필터를_지나도_화면_권한은_없고_포털_API_는_회사_사용자를_거부() {
        String self = selfServiceProg();
        LoginUser supUser = supplierUser(comp, supId, "sup1", s1);
        LoginUser workerUser = new LoginUser(workerId, comp, "worker", "worker", "ko", false);
        // 개인 화면: 회사 사용자는 모든 액션, 협력사 사용자는 없음 — PermissionService 와 AuthMapper(질의) 둘 다
        assertThat(as(workerUser, () -> permissions.actions(self))).isNotEmpty();
        assertThat(as(supUser, () -> permissions.actions(self))).isEmpty();
        assertThat(runner.call(comp, () -> authMapper.findActions(supId, self))).isEmpty();
        assertThat(runner.call(comp, () -> authMapper.findSearchableProgs(supId))).isEmpty();
        // @ProgAuth 메서드를 직접 불러도(필터 밖) 거부
        assertThatThrownBy(() -> as(supUser, () -> userApi.groups())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(HbErrorCode.NO_PERMISSION));
        // @PortalApi 메서드: 회사 사용자 거부, 협력사 사용자 통과
        assertThatThrownBy(() -> as(workerUser, () -> probe.me())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(HbErrorCode.PORTAL_FORBIDDEN));
        assertThat(as(supUser, () -> probe.me())).containsEntry("custId", s1);
    }

    @Test
    void 거래처를_사용_중지하면_로그인한_협력사_세션도_다음_요청에서_끊기고_다시_로그인_못_한다() throws Exception {
        MockHttpSession s = (MockHttpSession) formLogin(comp, "sup1", PW).getRequest().getSession(false);
        mvc.perform(get(PortalProbeController.ME).session(s)).andExpect(status().isOk());
        accounts.exec(comp, "update mst_cust set use_yn = 'N' where cust_id = ?", s1);
        mvc.perform(get(PortalProbeController.ME).session(s)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        assertThat(formLogin(comp, "sup1", PW).getResponse().getRedirectedUrl()).isEqualTo("/login?error=inactive");
        // 회사 사용자는 거래처와 무관
        mvc.perform(get("/api/admin/users").with(admin)).andExpect(status().isOk());
    }

    @Test
    void 비밀번호_변경_강제도_같고_바꾼_뒤에도_협력사_사용자로_남는다() throws Exception {
        accounts.exec(comp, "update sys_user set pwd_change_req_yn = 'Y' where user_id = ?", supId);
        MvcResult r = formLogin(comp, "sup1", PW);
        assertThat(r.getResponse().getRedirectedUrl()).isEqualTo("/portal");
        MockHttpSession s = (MockHttpSession) r.getRequest().getSession(false);
        mvc.perform(get(PortalProbeController.ME).session(s)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
        mvc.perform(json(post("/api/platform/password-change"), "{\"currentPassword\":\"" + PW
                + "\",\"newPassword\":\"Amber-meadow-5310\"}").session(s)).andExpect(status().isOk());
        // 세션 principal 을 새로 만들 때 유형·소속이 빠지면 회사 사용자가 된다 — 그대로인지
        mvc.perform(get(PortalProbeController.ME).session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.custId").value(s1));
        mvc.perform(get("/api/admin/users").session(s)).andExpect(jsonPath("$.code").value("PORTAL_FORBIDDEN"));
    }

    // ---------- 결재·위임·알림 대상에서 빠진다 ----------

    @Test
    void 결재선_위임_후보_알림_대상에_협력사_사용자가_없다() {
        LoginUser adminUser = new LoginUser(adminId, comp, "admin", "admin", "ko", false);
        assertThatThrownBy(() -> as(adminUser, () -> approvals.submit(TestDocType.DOC_TYPE, "PORTAL1", "x",
                List.of(new LineSpec(supId, LineType.APPROVE))))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> as(adminUser, () -> approvals.submit(TestDocType.DOC_TYPE, "PORTAL2", "x",
                List.of(new LineSpec(workerId, LineType.APPROVE), new LineSpec(supId, LineType.REF))))).isInstanceOf(BusinessException.class);
        assertThat(as(adminUser, () -> delegates.candidates("sup"))).isEmpty();
        assertThatThrownBy(() -> as(adminUser, () -> delegates.create(new ApprovalDelegateService.DelegateForm(supId, null,
                LocalDate.now().plusDays(1), LocalDate.now().plusDays(3), "휴가"))))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.fieldErrors()).anySatisfy(
                        f -> assertThat(f.field()).isEqualTo("delegateId")));
        String self = selfServiceProg();
        assertThat(runner.call(comp, () -> notifications.searchers(self))).contains(adminId, workerId).doesNotContain(supId);
    }

    // ---------- 사용자 관리 화면 ----------

    @Test
    void 관리자가_협력사_사용자를_만든다_유형_소속_고정_권한그룹_없음() throws Exception {
        mvc.perform(get("/api/admin/users/suppliers").with(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.list[*].custId", Matchers.containsInAnyOrder((int) s1, (int) s2)));
        mvc.perform(get("/api/admin/users/suppliers").param("keyword", "s2").with(hr))
                .andExpect(jsonPath("$.list[*].custId", Matchers.contains((int) s2)));
        mvc.perform(json(post("/api/admin/users"), "{\"loginId\":\"kim.sup\",\"userNm\":\"김협력\",\"userType\":\"SUPPLIER\","
                + "\"custId\":" + s2 + ",\"authGrpCds\":[]}").with(hr)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tempPassword").isNotEmpty());
        long id = accounts.query(comp, "select user_id from sys_user where login_id = 'kim.sup'", Long.class);
        assertThat(accounts.query(comp, "select user_type || ':' || cust_id || ':' || pwd_change_req_yn from sys_user where user_id = ?",
                String.class, id)).isEqualTo("SUPPLIER:" + s2 + ":Y");
        mvc.perform(get("/api/admin/users/" + id).with(hr)).andExpect(jsonPath("$.data.user.userType").value("SUPPLIER"))
                .andExpect(jsonPath("$.data.user.custNm").value("S2 상사"));
        mvc.perform(get("/api/admin/users").param("userType", "SUPPLIER").with(hr))
                .andExpect(jsonPath("$.list[*].loginId", Matchers.containsInAnyOrder("sup1", "kim.sup")));
        mvc.perform(get("/api/admin/users").param("userType", "ADMIN").with(hr)).andExpect(status().isBadRequest());

        // 수정: 이름은 바뀌고, 유형·소속을 다르게 보내면 조용히 무시하지 않고 FIXED
        mvc.perform(json(put("/api/admin/users/" + id), "{\"userNm\":\"김협력2\",\"useYn\":\"Y\",\"authGrpCds\":[],"
                + "\"userType\":\"SUPPLIER\",\"custId\":" + s2 + ",\"rowVer\":" + userVer(id) + "}").with(hr)).andExpect(status().isOk());
        mvc.perform(json(put("/api/admin/users/" + id), "{\"userNm\":\"x\",\"useYn\":\"Y\",\"authGrpCds\":[],"
                + "\"userType\":\"INTERNAL\",\"rowVer\":" + userVer(id) + "}").with(hr)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("userType")).andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        mvc.perform(json(put("/api/admin/users/" + id), "{\"userNm\":\"x\",\"useYn\":\"Y\",\"authGrpCds\":[],"
                + "\"custId\":" + s1 + ",\"rowVer\":" + userVer(id) + "}").with(hr)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("custId")).andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        // 화면은 그룹 칸을 숨기지만 API 로 넣으면 거부
        mvc.perform(json(put("/api/admin/users/" + id), "{\"userNm\":\"x\",\"useYn\":\"Y\",\"authGrpCds\":[\"WORK\"],"
                + "\"rowVer\":" + userVer(id) + "}").with(hr)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SUPPLIER_NO_GROUP"));
        mvc.perform(json(put("/api/admin/users/" + workerId), "{\"userNm\":\"작업자\",\"useYn\":\"Y\",\"authGrpCds\":[\"WORK\"],"
                + "\"userType\":\"SUPPLIER\",\"custId\":" + s1 + ",\"rowVer\":" + userVer(workerId) + "}").with(hr))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        assertThat(accounts.query(comp, "select user_nm || ':' || user_type || ':' || cust_id from sys_user where user_id = ?",
                String.class, id)).isEqualTo("김협력2:SUPPLIER:" + s2);
        // 비밀번호 초기화·잠금은 회사 사용자와 같다
        mvc.perform(json(post("/api/admin/users/" + id + "/lock"), "{\"rowVer\":" + userVer(id) + "}").with(hr)).andExpect(status().isOk());
    }

    @Test
    void 협력사_사용자_추가_입력_오류() throws Exception {
        String other = accounts.newComp("PW");
        long otherCust = cust(other, "S9", "PURCHASE");
        String[][] cases = {
                {"\"userType\":\"SUPPLIER\"", "custId", "REQUIRED"},
                {"\"userType\":\"SUPPLIER\",\"custId\":" + sales, "custId", "NOT_SUPPLIER_CUST"},
                {"\"userType\":\"SUPPLIER\",\"custId\":" + stopped, "custId", "INACTIVE"},
                {"\"userType\":\"SUPPLIER\",\"custId\":" + otherCust, "custId", "NO_SUCH_CODE"},   // A사로 B사
                {"\"userType\":\"SUPPLIER\",\"custId\":" + s1 + ",\"authGrpCds\":[\"WORK\"]", "authGrpCds", "SUPPLIER_NO_GROUP"},
                {"\"userType\":\"ADMIN\"", "userType", "CODE"},
                {"\"custId\":" + s1, "custId", "CODE"},
        };
        int n = 0;
        for (String[] c : cases) {
            mvc.perform(json(post("/api/admin/users"), "{\"loginId\":\"bad" + (n++) + "\",\"userNm\":\"x\"," + c[0] + "}").with(hr))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value(c[1]))
                    .andExpect(jsonPath("$.errors[0].code").value(c[2]));
        }
        assertThat(accounts.query(comp, "select count(*) from sys_user where login_id like 'bad%'", Integer.class)).isZero();
    }

    @Test
    void A사_관리자는_B사_협력사_사용자를_못_본다() throws Exception {
        String other = accounts.newComp("PX");
        long otherAdmin = accounts.newUser(other, "admin", PW);
        adminGroup(other, "ADMIN", otherAdmin);
        mvc.perform(get("/api/admin/users/" + supId).with(accounts.as(other, otherAdmin, "admin"))).andExpect(status().isNotFound());
        // B사 협력사 사용자 세션의 포털 요청은 B사 범위로만 — A사 거래처 id 를 알아도 자기 소속만 나온다
        long otherCust = cust(other, "S1", "PURCHASE");
        long otherSup = supplier(other, "sup1", otherCust);
        mvc.perform(get(PortalProbeController.ME).with(supplierAuth(other, otherSup, "sup1", otherCust)))
                .andExpect(jsonPath("$.compCd").value(other)).andExpect(jsonPath("$.custId").value(otherCust));
    }

    @Test
    void 세션_principal_을_바꿔도_유형과_소속이_남는다() {
        LoginUser u = supplierUser(comp, supId, "sup1", s1);
        assertThat(u.withLocale("en").supplier()).isTrue();
        assertThat(u.withLocale("en").custId()).isEqualTo(s1);
        assertThat(u.withPwdChangeRequired(false).custId()).isEqualTo(s1);
        assertThat(new LoginUser(1, comp, "a", "a", "ko", false).userType()).isEqualTo(LoginUser.INTERNAL);
    }

    // ---------- P4 접속 로그 사용자 유형 ----------

    @Test
    void 접속_로그를_사용자_유형으로_거른다() throws Exception {
        formLogin(comp, "sup1", PW);
        formLogin(comp, "worker", PW);
        formLogin(comp, "nobody", PW);   // 사용자 없는 로그인 실패 — 유형 빈칸
        String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        String yesterday = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1).toString();
        mvc.perform(get("/api/admin/access-logs").param("from", yesterday).param("to", today).param("userType", "SUPPLIER")
                .param("size", "200").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.list[*].loginId", Matchers.everyItem(Matchers.is("sup1"))))
                .andExpect(jsonPath("$.list[*].userType", Matchers.everyItem(Matchers.is("SUPPLIER"))))
                .andExpect(jsonPath("$.list[*].eventCd", Matchers.hasItem("LOGIN_OK")));
        mvc.perform(get("/api/admin/access-logs").param("from", yesterday).param("to", today).param("userType", "INTERNAL")
                .param("size", "200").with(admin))
                .andExpect(jsonPath("$.list[*].loginId", Matchers.allOf(Matchers.hasItem("worker"),
                        Matchers.not(Matchers.hasItem("sup1")), Matchers.not(Matchers.hasItem("nobody")))));
        // 거르지 않으면 셋 다, 사용자 없는 행은 유형 빈칸
        mvc.perform(get("/api/admin/access-logs").param("from", yesterday).param("to", today).param("loginId", "nobody").with(admin))
                .andExpect(jsonPath("$.list[0].userType").doesNotExist());
        mvc.perform(get("/api/admin/access-logs").param("from", yesterday).param("to", today).param("userType", "ADMIN").with(admin))
                .andExpect(status().isBadRequest());
    }

    // ---------- 공통 사용자 고르기 UserDirectory (docs/42 15장) ----------

    @Test
    void 공통_사용자_고르기는_사용_중_회사_사용자만() throws Exception {
        long locked = accounts.newUser(comp, "locked", PW);
        accounts.exec(comp, "update sys_user set lock_yn = 'Y', locked_until = null where user_id = ?", locked);
        accounts.grant(comp, workerId, "BUYER", "srm-po", "SEARCH", "APPROVE");
        assertThat(runner.call(comp, () -> users.activeUsers())).extracting(UserOption::getLoginId)
                .contains("admin", "hr", "worker").doesNotContain("sup1", "locked");
        // 아이디로: 협력사는 없는 사람, 잠긴 사람은 있되 active 아님(바꾸지 않은 담당자 유지용)
        assertThat(runner.call(comp, () -> users.byLogin("SUP1"))).isNull();
        assertThat(runner.call(comp, () -> users.byLogin("locked")).active()).isFalse();
        assertThat(runner.call(comp, () -> users.activeByLogin("locked"))).isNull();
        assertThat(runner.call(comp, () -> users.activeByLogin(" Worker ")).getUserId()).isEqualTo(workerId);
        assertThat(runner.call(comp, () -> users.isActive(supId))).isFalse();
        assertThat(runner.call(comp, () -> users.isActive(workerId))).isTrue();
        assertThat(runner.call(comp, () -> users.groupActionHolders("srm-po", "APPROVE")))
                .extracting(UserOption::getLoginId).containsExactly("worker");
        // A사로 B사: 다른 회사 사용자는 아이디·id 로도 없음
        String other = accounts.newComp("PY");
        long otherUser = accounts.newUser(other, "worker", PW);
        assertThat(runner.call(comp, () -> users.isActive(otherUser))).isFalse();
        assertThat(runner.call(other, () -> users.activeByLogin("worker")).getUserId()).isEqualTo(otherUser);
    }

    @Test
    void 모듈의_담당자_선택지에_협력사_계정이_없다() throws Exception {
        mvc.perform(get("/api/pms/projects/users").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].loginId", Matchers.allOf(Matchers.hasItem("worker"), Matchers.not(Matchers.hasItem("sup1")))));
        mvc.perform(get("/api/plm/ecos/users").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].loginId", Matchers.not(Matchers.hasItem("sup1"))));
    }
}
