package io.smarthappy.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.ops.OpsConsoleService;
import io.smarthappy.platform.ops.OpsUser;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 시스템관리 4a 관리자 MFA (docs/36 14장) — 실제 폼 로그인으로 두 번째 단계까지.
 * 정상(등록·확인·복구 코드·초기화) + 이상 사용(게이트 우회, 재사용, 5회 잠금, 등록 바꿔치기, 부여 상한·자기 초기화, A사로 B사, 직접 SQL, 키 없음)
 * TOTP 코드는 시험이 RFC 6238 대로 따로 계산한다(서버 구현과 독립). 시계는 MfaService 에 고정 시각을 넣는다.
 */
@AutoConfigureMockMvc
class MfaTest extends PostgresTestBase {

    static final String PW = "Quiet-river-77";
    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    MfaService mfa;

    @Autowired
    TenantRunner runner;

    @Autowired
    PolicyService policies;

    @Autowired
    OpsConsoleService ops;

    String comp;
    long adminId;
    long admin2Id;
    long hrId;
    long workerId;
    /** 시험 시계 — 코드마다 구간을 넘긴다 */
    Instant now = Instant.parse("2026-10-21T00:00:00Z");

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("MF");
        adminId = accounts.newUser(comp, "admin", PW);
        admin2Id = accounts.newUser(comp, "admin2", PW);
        accounts.exec(comp, """
                insert into sys_auth_group (comp_cd, auth_grp_cd, auth_grp_nm, admin_yn, created_by, updated_by)
                values (?, 'ADMIN', '관리자', 'Y', 't', 't')""", comp);
        accounts.exec(comp, "insert into sys_user_auth (comp_cd, user_id, auth_grp_cd, created_by) values (?, ?, 'ADMIN', 't'), (?, ?, 'ADMIN', 't')",
                comp, adminId, comp, admin2Id);
        hrId = accounts.newUser(comp, "hr", PW);
        accounts.grant(comp, hrId, "HR", "sys-user", "SEARCH", "SAVE", "FN1", "FN2");
        workerId = accounts.newUser(comp, "worker", PW);
        accounts.grant(comp, workerId, "WORK", "mst-cust", "SEARCH", "SAVE");
        tick();
    }

    @AfterEach
    void resetClock() {
        mfa.setClock(Clock.systemUTC());
        SecurityContextHolder.clearContext();
    }

    /** 다음 30초 구간으로 */
    private void tick() {
        now = now.plusSeconds(30);
        mfa.setClock(Clock.fixed(now, ZoneOffset.UTC));
    }

    /** RFC 6238 — HMAC-SHA1, 30초, 6자리 (서버 Totp 와 독립 계산) */
    private String code(String base32Secret) throws Exception {
        String s = base32Secret.replace(" ", "").toUpperCase(Locale.ROOT);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : s.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.put((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(out.array(), "HmacSHA1"));
        byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(now.getEpochSecond() / 30).array());
        int o = h[h.length - 1] & 0x0f;
        int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
        return String.format(Locale.ROOT, "%06d", bin % 1_000_000);
    }

    private MockHttpSession login(String loginId) throws Exception {
        String ip = "10.36." + (IP_SEQ.incrementAndGet() % 250 + 1) + ".7";
        MvcResult r = mvc.perform(post("/login").with(csrf()).with(req -> { req.setRemoteAddr(ip); return req; })
                        .param("compCd", comp).param("loginId", loginId).param("password", PW))
                .andExpect(redirectedUrl("/main")).andReturn();
        return (MockHttpSession) r.getRequest().getSession(false);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** 등록까지 마친 세션과 비밀 */
    private record Enrolled(MockHttpSession session, String secret, List<String> recovery) {
    }

    private Enrolled enroll(String loginId) throws Exception {
        MockHttpSession s = login(loginId);
        MvcResult r = mvc.perform(json(post("/api/platform/mfa/enroll/start"), "{}").session(s)).andExpect(status().isOk()).andReturn();
        String secret = JsonPath.read(r.getResponse().getContentAsString(), "$.data.secret");
        MvcResult c = mvc.perform(json(post("/api/platform/mfa/enroll/confirm"), "{\"code\":\"" + code(secret) + "\"}").session(s))
                .andExpect(status().isOk()).andReturn();
        List<String> rec = JsonPath.read(c.getResponse().getContentAsString(), "$.data.recoveryCodes");
        tick();
        return new Enrolled(s, secret, rec);
    }

    private static String constraintOf(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            return PgErrors.constraint(e).orElse("(none) " + e.getMessage());
        }
        return "(no error)";
    }

    // ================================================================== 등록

    @Test
    void 관리자는_등록_전까지_MFA_화면만_열리고_등록하면_복구_코드_10개() throws Exception {
        MockHttpSession s = login("admin");
        mvc.perform(get("/main").session(s)).andExpect(redirectedUrl("/mfa"));
        mvc.perform(get("/api/platform/menu").session(s)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
        mvc.perform(get("/page/sys-user").session(s)).andExpect(redirectedUrl("/mfa"));
        mvc.perform(get("/mfa").session(s)).andExpect(status().isOk());
        MvcResult r = mvc.perform(json(post("/api/platform/mfa/enroll/start"), "{}").session(s)).andReturn();
        String secret = JsonPath.read(r.getResponse().getContentAsString(), "$.data.secret");
        assertThat((String) JsonPath.read(r.getResponse().getContentAsString(), "$.data.otpauthUri"))
                .startsWith("otpauth://totp/YeonOS:").contains("secret=" + secret);
        // 틀린 코드 → 400, 대기 그대로
        mvc.perform(json(post("/api/platform/mfa/enroll/confirm"), "{\"code\":\"000000\"}").session(s))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MFA_INVALID"));
        mvc.perform(get("/api/platform/menu").session(s)).andExpect(status().isForbidden());
        // CSRF 토큰 없는 확인은 거부
        mvc.perform(post("/api/platform/mfa/enroll/confirm").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code(secret) + "\"}").session(s)).andExpect(status().isForbidden());
        String sessionBefore = s.getId();
        MvcResult c = mvc.perform(json(post("/api/platform/mfa/enroll/confirm"), "{\"code\":\"" + code(secret) + "\"}").session(s))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.recoveryCodes.length()").value(10)).andReturn();
        String sessionAfter = c.getRequest().getSession(false).getId();
        mvc.perform(get("/api/platform/menu").session(s)).andExpect(status().isOk());
        assertThat(sessionAfter).as("두 번째 요소를 마치면 세션 ID 를 바꾼다").isNotNull().isNotEqualTo(sessionBefore);
        // DB 에는 원문이 없다: 비밀은 봉인, 복구 코드는 해시
        String sealed = accounts.query(comp, "select secret_enc from sys_user_mfa where user_id = ?", String.class, adminId);
        assertThat(sealed).doesNotContain(secret);
        List<String> rec = JsonPath.read(c.getResponse().getContentAsString(), "$.data.recoveryCodes");
        assertThat(accounts.query(comp, "select string_agg(code_hash, ',') from sys_user_mfa_recovery where user_id = ?",
                String.class, adminId)).doesNotContain(rec.get(0)).doesNotContain(rec.get(0).replace("-", ""));
        // 감사에도 비밀은 값 대신 (changed)
        assertThat(accounts.query(comp, """
                select string_agg(coalesce(after_data->>'secret_enc', ''), ',') from sys_audit_log where table_nm = 'sys_user_mfa'""",
                String.class)).doesNotContain(sealed);
        // 등록된 뒤에는 다시 시작할 수 없다(바꾸려면 초기화)
        mvc.perform(json(post("/api/platform/mfa/enroll/start"), "{}").session(s)).andExpect(status().isConflict());
    }

    @Test
    void 강제_대상이_아니면_내_정보에서_스스로_등록_화면을_열고_등록_뒤엔_다시_열리지_않는다() throws Exception {
        MockHttpSession s = login("worker");
        mvc.perform(get("/mfa").session(s)).andExpect(redirectedUrl("/main"));          // 대기 없음 + self 없음 = 셸로
        mvc.perform(get("/mfa").param("self", "1").session(s)).andExpect(status().isOk())
                .andExpect(model().attribute("state", "SELF")).andExpect(model().attribute("back", "/page/sys-my-info"));
        // 스스로 등록 화면을 열어도 로그인 대기가 생기지 않는다 — 다른 화면 그대로
        mvc.perform(get("/api/platform/menu").session(s)).andExpect(status().isOk());
        MvcResult r = mvc.perform(json(post("/api/platform/mfa/enroll/start"), "{}").session(s)).andExpect(status().isOk()).andReturn();
        String secret = JsonPath.read(r.getResponse().getContentAsString(), "$.data.secret");
        mvc.perform(json(post("/api/platform/mfa/enroll/confirm"), "{\"code\":\"" + code(secret) + "\"}").session(s))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.recoveryCodes.length()").value(10));
        mvc.perform(get("/mfa").param("self", "1").session(s)).andExpect(redirectedUrl("/page/sys-my-info"));   // 이미 등록
        assertThat(runner.call(comp, () -> mfa.isEnrolled(workerId))).isTrue();
        // 다음 로그인부터 확인을 묻는다
        tick();
        mvc.perform(get("/main").session(login("worker"))).andExpect(redirectedUrl("/mfa"));
    }

    @Test
    void 강제_대상_판정_관리자_지정_화면_권한_정책_끄기() throws Exception {
        // 작업자(지정 화면 권한 없음) → 바로 통과
        mvc.perform(get("/api/platform/menu").session(login("worker"))).andExpect(status().isOk());
        // 사용자 관리 권한(MFA_REQUIRED_PROGS 기본) → 등록 대기
        mvc.perform(get("/api/platform/menu").session(login("hr"))).andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
        // 회사가 두 정책을 끄면 관리자도 바로 통과
        accounts.exec(comp, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by)
                values (?, 'MFA_REQUIRED_FOR_ADMIN', 'N', 't', 't'), (?, 'MFA_REQUIRED_PROGS', 'x-none', 't', 't')""", comp, comp);
        mvc.perform(get("/api/platform/menu").session(login("admin"))).andExpect(status().isOk());
        mvc.perform(get("/api/platform/menu").session(login("hr"))).andExpect(status().isOk());
    }

    @Test
    void MFA_는_비밀번호_변경_강제보다_먼저() throws Exception {
        Enrolled e = enroll("admin");
        accounts.exec(comp, "update sys_user set pwd_change_req_yn = 'Y' where user_id = ?", adminId);
        MockHttpSession s = login("admin");
        // 임시 비밀번호만으로는 비밀번호 변경 API 도 열리지 않는다
        mvc.perform(json(post("/api/platform/password-change"), "{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"Brand-new-pass-81\"}")
                .session(s)).andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
        mvc.perform(json(post("/api/platform/mfa/verify"), "{\"code\":\"" + code(e.secret()) + "\"}").session(s))
                .andExpect(status().isOk());
        mvc.perform(get("/api/platform/menu").session(s)).andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    }

    // ================================================================== 확인

    @Test
    void 확인_재사용_거부_5회_잠금_복구_코드는_한_번() throws Exception {
        Enrolled e = enroll("admin");
        // 등록 때 쓴 구간의 코드는 다시 못 쓴다(재전송) — 같은 시각으로 되돌려 같은 코드를 만든다
        now = now.minusSeconds(30);
        mfa.setClock(Clock.fixed(now, ZoneOffset.UTC));
        String used = code(e.secret());
        MockHttpSession s = login("admin");
        mvc.perform(json(post("/api/platform/mfa/verify"), "{\"code\":\"" + used + "\"}").session(s))
                .andExpect(jsonPath("$.code").value("MFA_INVALID"));
        tick();
        tick();
        // CSRF 토큰 없는 확인은 거부(실패 수에도 안 들어간다)
        mvc.perform(post("/api/platform/mfa/verify").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"111111\"}")
                .session(s)).andExpect(status().isForbidden());
        // 확인(VERIFY) 대기 중에 새 등록으로 바꿔치기 불가
        mvc.perform(json(post("/api/platform/mfa/enroll/start"), "{}").session(s)).andExpect(status().isConflict());
        mvc.perform(json(post("/api/platform/mfa/enroll/confirm"), "{\"code\":\"123456\"}").session(s)).andExpect(status().isConflict());
        // 틀린 코드 3번 더(합 4) → 남은 횟수 1
        for (int i = 0; i < 3; i++) {
            mvc.perform(json(post("/api/platform/mfa/verify"), "{\"code\":\"99999" + i + "\"}").session(s))
                    .andExpect(jsonPath("$.code").value("MFA_INVALID"));
        }
        // 다섯 번째 → 잠김, 그 뒤 맞는 코드도 거부, 복구 코드도 거부
        mvc.perform(json(post("/api/platform/mfa/verify"), "{\"code\":\"abc\"}").session(s))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MFA_LOCKED"));
        mvc.perform(json(post("/api/platform/mfa/verify"), "{\"code\":\"" + code(e.secret()) + "\"}").session(s))
                .andExpect(jsonPath("$.code").value("MFA_LOCKED"));
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'MFA_LOCKED' and severity = 'ALERT'",
                Integer.class)).isEqualTo(1);
        // 15분 뒤 → 복구 코드로 통과, 같은 복구 코드는 다음 로그인에서 거부
        now = now.plusSeconds(16 * 60);
        mfa.setClock(Clock.fixed(now, ZoneOffset.UTC));
        mvc.perform(json(post("/api/platform/mfa/verify"), "{\"code\":\"" + e.recovery().get(0).toLowerCase(Locale.ROOT) + "\"}")
                .session(s)).andExpect(status().isOk());
        mvc.perform(get("/api/platform/menu").session(s)).andExpect(status().isOk());
        MockHttpSession s2 = login("admin");
        mvc.perform(json(post("/api/platform/mfa/verify"), "{\"code\":\"" + e.recovery().get(0) + "\"}").session(s2))
                .andExpect(jsonPath("$.code").value("MFA_INVALID"));
        mvc.perform(json(post("/api/platform/mfa/verify"), "{\"code\":\"" + e.recovery().get(1).replace("-", "") + "\"}").session(s2))
                .andExpect(status().isOk());
        mvc.perform(get("/api/platform/mfa/status").session(s2)).andExpect(jsonPath("$.data.recoveryLeft").value(8))
                .andExpect(jsonPath("$.data.enrolled").value(true));
        // 대기가 없는 세션에서 verify → 409
        mvc.perform(json(post("/api/platform/mfa/verify"), "{\"code\":\"" + code(e.secret()) + "\"}").session(s2))
                .andExpect(status().isConflict());
    }

    @Test
    void 복구_코드_다시_만들기는_지금_코드가_필요하고_이전_것은_무효() throws Exception {
        Enrolled e = enroll("admin");
        mvc.perform(json(post("/api/platform/mfa/recovery"), "{\"code\":\"000000\"}").session(e.session()))
                .andExpect(jsonPath("$.code").value("MFA_INVALID"));
        tick();
        MvcResult r = mvc.perform(json(post("/api/platform/mfa/recovery"), "{\"code\":\"" + code(e.secret()) + "\"}")
                .session(e.session())).andExpect(status().isOk()).andReturn();
        List<String> fresh = JsonPath.read(r.getResponse().getContentAsString(), "$.data.recoveryCodes");
        assertThat(fresh).hasSize(10).doesNotContainAnyElementsOf(e.recovery());
        MockHttpSession s = login("admin");
        mvc.perform(json(post("/api/platform/mfa/verify"), "{\"code\":\"" + e.recovery().get(2) + "\"}").session(s))
                .andExpect(jsonPath("$.code").value("MFA_INVALID"));
        mvc.perform(json(post("/api/platform/mfa/verify"), "{\"code\":\"" + fresh.get(0) + "\"}").session(s))
                .andExpect(status().isOk());
    }

    // ================================================================== 초기화

    @Test
    void 관리자_초기화는_부여_상한_자기_금지_세션_끊김_다음_로그인_재등록() throws Exception {
        Enrolled e = enroll("admin");
        Enrolled e2 = enroll("admin2");
        int ver = accounts.query(comp, "select row_ver from sys_user where user_id = ?", Integer.class, adminId);
        // 자기 자신 → 403 SELF_CHANGE, 사용자 관리만 가진 hr(관리자보다 작음) → 403 GRANT_EXCEEDS
        mvc.perform(json(post("/api/admin/users/" + admin2Id + "/reset-mfa"), "{\"rowVer\":1}").session(e2.session()))
                .andExpect(jsonPath("$.code").value("SELF_CHANGE"));
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'MFA_REQUIRED_PROGS', 'x-none', 't', 't')", comp);
        MockHttpSession hr = login("hr");
        mvc.perform(json(post("/api/admin/users/" + adminId + "/reset-mfa"), "{\"rowVer\":" + ver + "}").session(hr))
                .andExpect(jsonPath("$.code").value("GRANT_EXCEEDS"));
        // 다른 관리자 → 성공, 대상 세션 끊김, 다음 로그인 = 다시 등록
        mvc.perform(json(post("/api/admin/users/" + adminId + "/reset-mfa"), "{\"rowVer\":" + ver + "}").session(e2.session()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/platform/menu").session(e.session())).andExpect(status().isUnauthorized());
        MockHttpSession again = login("admin");
        mvc.perform(get("/api/platform/menu").session(again)).andExpect(jsonPath("$.code").value("MFA_REQUIRED"));
        assertThat(accounts.query(comp, "select count(*) from sys_user_mfa_recovery where user_id = ?", Integer.class, adminId)).isZero();
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'MFA_RESET'", Integer.class)).isEqualTo(1);
        // 등록 안 된 사람 초기화 → 409, 낡은 버전 → 409
        int wver = accounts.query(comp, "select row_ver from sys_user where user_id = ?", Integer.class, workerId);
        mvc.perform(json(post("/api/admin/users/" + workerId + "/reset-mfa"), "{\"rowVer\":" + wver + "}").session(e2.session()))
                .andExpect(jsonPath("$.code").value("MFA_STATE"));
    }

    @Test
    void 운영자_초기화는_USERS_지원_세션_안에서만() throws Exception {
        enroll("admin");
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new OpsUser(1, "opsmf", "운영자"), null, List.of(new SimpleGrantedAuthority("ROLE_OPS"))));
        assertThatThrownBy(() -> ops.resetUserMfa(comp, "admin", "127.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class, b -> assertThat(b.code()).isEqualTo(HbErrorCode.NO_PERMISSION));
        accounts.exec(comp, """
                insert into sys_support_grant (comp_cd, op_login_id, scope_cd, reason, from_at, to_at, approved_by, created_by, updated_by)
                values (?, 'opsmf', 'LOGS', '로그', now() - interval '1 minute', now() + interval '1 hour', ?, 't', 't')""", comp, admin2Id);
        assertThatThrownBy(() -> ops.resetUserMfa(comp, "admin", "127.0.0.1")).isInstanceOf(BusinessException.class);
        accounts.exec(comp, """
                insert into sys_support_grant (comp_cd, op_login_id, scope_cd, reason, from_at, to_at, approved_by, created_by, updated_by)
                values (?, 'opsmf', 'USERS', '관리자 휴대폰 분실', now() - interval '1 minute', now() + interval '1 hour', ?, 't', 't')""",
                comp, admin2Id);
        ops.resetUserMfa(comp, "admin", "127.0.0.1");
        assertThat(accounts.query(comp, "select count(*) from sys_user_mfa where user_id = ?", Integer.class, adminId)).isZero();
        assertThat(accounts.query(comp, """
                select actor from sys_audit_log where table_nm = 'sys_user_mfa' and action = 'DELETE' order by audit_id desc limit 1""",
                String.class)).startsWith("ops:");
        assertThatThrownBy(() -> ops.resetUserMfa(comp, "nobody", "127.0.0.1")).isInstanceOf(BusinessException.class);
    }

    // ================================================================== A사로 B사 · 직접 SQL · 키 없음

    @Test
    void A사로_B사와_직접_SQL() throws Exception {
        enroll("admin");
        String compB = accounts.newComp("MFB");
        long bAdmin = accounts.newUser(compB, "badmin", PW);
        accounts.exec(compB, """
                insert into sys_auth_group (comp_cd, auth_grp_cd, auth_grp_nm, admin_yn, created_by, updated_by)
                values (?, 'ADMIN', '관리자', 'Y', 't', 't')""", compB);
        accounts.exec(compB, "insert into sys_user_auth (comp_cd, user_id, auth_grp_cd, created_by) values (?, ?, 'ADMIN', 't')", compB, bAdmin);
        accounts.exec(compB, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'MFA_REQUIRED_FOR_ADMIN', 'N', 't', 't')", compB);
        // B사 관리자 API 로 A사 사용자 초기화 → 404, B사 범위에서 A사 MFA 행은 보이지 않는다
        mvc.perform(json(post("/api/admin/users/" + adminId + "/reset-mfa"), "{\"rowVer\":1}").with(accounts.as(compB, bAdmin, "badmin")))
                .andExpect(status().isNotFound());
        assertThat(accounts.query(compB, "select count(*) from sys_user_mfa", Integer.class)).isZero();
        assertThat(constraintOf(() -> accounts.exec(compB, """
                insert into sys_user_mfa (comp_cd, user_id, secret_enc, created_by, updated_by) values (?, ?, 'x', 't', 't')""",
                compB, adminId))).isEqualTo("fk_user_comp");
        // 복구 코드: 해시 바꾸기 불가, 사용 시각 되돌리기 불가, 등록 없이 enabled=Y 불가
        accounts.exec(comp, "update sys_user_mfa_recovery set used_at = now() where recovery_id = (select min(recovery_id) from sys_user_mfa_recovery)");
        assertThatThrownBy(() -> accounts.exec(comp, "update sys_user_mfa_recovery set code_hash = repeat('0', 64)"))
                .rootCause().hasMessageContaining("permission denied");
        assertThat(constraintOf(() -> accounts.exec(comp, """
                update sys_user_mfa_recovery set used_at = null where recovery_id = (select min(recovery_id) from sys_user_mfa_recovery)""")))
                .isEqualTo("ck_sys_user_mfa_recovery_once");
        assertThat(constraintOf(() -> accounts.exec(comp, "update sys_user_mfa set enrolled_at = null where user_id = ?", adminId)))
                .isEqualTo("ck_sys_user_mfa_enrolled");
    }

    @Test
    void 동시_추측은_5회를_넘지_못하고_잠금이_끝나면_처음부터() throws Exception {
        Enrolled e = enroll("admin");
        LoginUser me = new LoginUser(adminId, comp, "admin", "admin", "ko", false);
        // 연결 풀(10)보다 많게 — 요청이 연결을 겹쳐 쥐면 여기서 교착(풀 고갈)이 난다(docs/36 14.4.1 ②)
        int n = 30;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<HbErrorCode>> results = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                String guess = "guess-" + i;   // 맞을 수 없는 값 — 우연히 맞는 코드가 섞이지 않게
                results.add(pool.submit(() -> {
                    go.await();
                    // 웹 요청과 같은 모양: 회사 범위만 열고(필터) 트랜잭션 없이 서비스 호출
                    try (var scope = io.smarthappy.platform.tenant.TenantContext.openRequestScope(comp, "u" + adminId)) {
                        mfa.verify(me, guess);
                        return null;
                    } catch (BusinessException b) {
                        return b.code();
                    }
                }));
            }
            go.countDown();
            // 같은 때 다른 사용자의 API 요청이 정상 응답(풀이 바닥나지 않았다)
            mvc.perform(get("/api/platform/menu").with(accounts.as(comp, workerId, "worker"))).andExpect(status().isOk());
            int judged = 0;
            int locked = 0;
            for (var f : results) {
                HbErrorCode c = f.get(20, java.util.concurrent.TimeUnit.SECONDS);   // 교착이면 여기서 시간 초과
                if (c == HbErrorCode.MFA_INVALID) {
                    judged++;
                } else if (c == HbErrorCode.MFA_LOCKED) {
                    locked++;
                }
            }
            // 판정까지 간 시도 ≤ 5: 실패 4(MFA_INVALID) + 다섯 번째(판정 뒤 잠금) — 나머지는 판정 없이 잠금
            assertThat(judged).isEqualTo(MfaService.MAX_FAIL - 1);
            assertThat(locked).isEqualTo(n - judged);
        } finally {
            pool.shutdownNow();
        }
        // 회귀 막기: 바깥 트랜잭션 안에서 부르면(연결을 겹쳐 쥐는 모양) 거부
        assertThatThrownBy(() -> runner.call(comp, () -> { mfa.verify(me, "000000"); return null; }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(accounts.query(comp, "select fail_cnt from sys_user_mfa where user_id = ?", Integer.class, adminId))
                .isEqualTo(MfaService.MAX_FAIL);
        // 잠금 중에는 맞는 코드도 거부
        MockHttpSession s = login("admin");
        mvc.perform(json(post("/api/platform/mfa/verify"), "{\"code\":\"" + code(e.secret()) + "\"}").session(s))
                .andExpect(jsonPath("$.code").value("MFA_LOCKED"));
        // 잠금이 끝난 뒤 한 번 틀려도 다시 잠기지 않는다(남은 4) — 그 다음 맞는 코드로 통과
        now = now.plusSeconds(16 * 60);
        mfa.setClock(Clock.fixed(now, ZoneOffset.UTC));
        mvc.perform(json(post("/api/platform/mfa/verify"), "{\"code\":\"000001\"}").session(s))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MFA_INVALID"));
        assertThat(accounts.query(comp, "select fail_cnt || ':' || (locked_until is null)::text from sys_user_mfa where user_id = ?",
                String.class, adminId)).isEqualTo("1:true");
        tick();
        mvc.perform(json(post("/api/platform/mfa/verify"), "{\"code\":\"" + code(e.secret()) + "\"}").session(s))
                .andExpect(status().isOk());
    }

    @Test
    void 판정이_실패하면_로그인_세션을_버린다_fail_closed() {
        // 판정 중 예외(여기서는 회사 범위를 열 수 없는 값) — 인증이 저장된 세션이 대기 표시 없이 남지 않는다
        MockHttpSession session = new MockHttpSession();
        LoginUser bad = new LoginUser(adminId, "bad comp!", "admin", "admin", "ko", false);
        assertThatThrownBy(() -> mfa.onLogin(bad, session)).isInstanceOf(RuntimeException.class);
        assertThat(session.isInvalid()).isTrue();
        // 정상 판정: 등록 전 관리자 = ENROLL, 대상 아님 = 표시 없음
        MockHttpSession ok = new MockHttpSession();
        mfa.onLogin(new LoginUser(adminId, comp, "admin", "admin", "ko", false), ok);
        assertThat(ok.getAttribute(MfaService.SESSION_STATE)).isEqualTo(MfaService.ENROLL);
        MockHttpSession none = new MockHttpSession();
        mfa.onLogin(new LoginUser(workerId, comp, "worker", "worker", "ko", false), none);
        assertThat(none.getAttribute(MfaService.SESSION_STATE)).isNull();
    }

    @Test
    void 봉인은_사용자에_묶여_DB_에서_바꿔_넣으면_풀리지_않는다() throws Exception {
        Enrolled a = enroll("admin");
        enroll("admin2");
        // admin2 의 봉인을 admin 행에 넣는다(DB 를 고칠 수 있는 공격자) — admin 의 확인은 통과하지 못한다
        accounts.exec(comp, "update sys_user_mfa set secret_enc = (select secret_enc from sys_user_mfa where user_id = ?) where user_id = ?",
                admin2Id, adminId);
        MockHttpSession s = login("admin");
        mvc.perform(json(post("/api/platform/mfa/verify"), "{\"code\":\"" + code(a.secret()) + "\"}").session(s))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MFA_UNAVAILABLE"));
        mvc.perform(get("/api/platform/menu").session(s)).andExpect(status().isForbidden());
    }

    @Test
    void 키가_없으면_MFA_가_필요한_로그인은_열리지_않는다() {
        MfaService noKey = new MfaService(null, runner, policies, null, "", "");
        assertThat(noKey.available()).isFalse();
        LoginUser me = new LoginUser(adminId, comp, "admin", "admin", "ko", false);
        assertThatThrownBy(() -> noKey.startEnroll(me))
                .isInstanceOfSatisfying(BusinessException.class, b -> assertThat(b.code()).isEqualTo(HbErrorCode.MFA_UNAVAILABLE));
        assertThatThrownBy(() -> noKey.verify(me, "123456"))
                .isInstanceOfSatisfying(BusinessException.class, b -> assertThat(b.code()).isEqualTo(HbErrorCode.MFA_UNAVAILABLE));
        assertThat(new MfaService(null, runner, policies, null, "not-base64-32", "").available()).isFalse();
    }
}
