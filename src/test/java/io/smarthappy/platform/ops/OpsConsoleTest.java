package io.smarthappy.platform.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/** 운영자 콘솔 (P0 9단계): 허용 IP·비밀번호·TOTP·역할 분리·회사 생성·활용 집계(숫자만)·고객 승인 지원 세션 */
@AutoConfigureMockMvc
class OpsConsoleTest extends PostgresTestBase {

    private static final String OP_PW = "Granite-harbor-91x";
    private static final String USER_PW = "Willow-lantern-28";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    OpsAuthService auth;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    String op;

    @BeforeEach
    void setUp() throws Exception {
        op = "op" + SEQ.incrementAndGet() + "x";
        ownerUpdate("insert into sys_operator (login_id, op_nm, pwd_hash, created_by) values (?, '테스트 운영자', ?, 't')",
                op, encoder.encode(OP_PW));
        auth.setClock(Clock.systemUTC());
        clock = Clock.systemUTC();
    }

    @AfterEach
    void resetClock() {
        auth.setClock(Clock.systemUTC());
    }

    private static int ownerUpdate(String sql, Object... args) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword());
                PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps.executeUpdate();
        }
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions login(MockHttpSession s, String id, String pw) throws Exception {
        return mvc.perform(json(post("/api/ops/auth/login"), "{\"loginId\":\"" + id + "\",\"password\":\"" + pw + "\"}")
                .session(s));
    }

    /** 시험이 코드를 계산하는 시계 — 서버(auth)와 같은 시계를 써야 30초 구간 경계에서 엇갈리지 않는다 */
    private Clock clock = Clock.systemUTC();

    private String now(String secret) {
        return Totp.code(secret, clock.millis() / 1000 / 30);
    }

    /** 비밀번호 + TOTP 등록까지 끝낸 운영자 세션. 반환 = TOTP 비밀 */
    private String signIn(MockHttpSession s) throws Exception {
        MvcResult r = login(s, op, OP_PW).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mfa").value("ENROLL")).andReturn();
        String secret = JsonPath.read(r.getResponse().getContentAsString(), "$.data.secret");
        mvc.perform(json(post("/api/ops/auth/totp"), "{\"code\":\"" + now(secret) + "\"}").session(s))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.loginId").value(op));
        return secret;
    }

    private static RequestPostProcessor from(String ip) {
        return r -> {
            r.setRemoteAddr(ip);
            return r;
        };
    }

    // ---------- 인증 ----------

    @Test
    void 허용_IP_밖에서는_콘솔이_없는_것처럼_404() throws Exception {
        mvc.perform(get("/ops/login").with(from("8.8.8.8"))).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/ops/auth/login"), "{}").with(from("8.8.8.8"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/ops/companies").with(from("192.168.0.10"))).andExpect(status().isNotFound());
        // 허용 대역(10.99.0.0/16)은 통과 — 비로그인이라 401
        mvc.perform(get("/api/ops/companies").with(from("10.99.3.4"))).andExpect(status().isUnauthorized());
    }

    @Test
    void 비밀번호만으로는_콘솔을_쓸_수_없다() throws Exception {
        MockHttpSession s = new MockHttpSession();
        login(s, op, OP_PW).andExpect(status().isOk());
        mvc.perform(get("/api/ops/companies").session(s)).andExpect(status().isUnauthorized());
    }

    @Test
    void 등록_후_다음_로그인은_코드_확인_같은_코드_재사용은_거부() throws Exception {
        // 구간 한가운데로 시계 고정 — 등록과 재사용 시도 사이에 30초 구간이 넘어가 "같은 코드"가 새 코드가 되던 흔들림 방지
        clock = Clock.fixed(Instant.ofEpochSecond(System.currentTimeMillis() / 1000 / 30 * 30 + 15), ZoneOffset.UTC);
        auth.setClock(clock);
        MockHttpSession s1 = new MockHttpSession();
        String secret = signIn(s1);
        mvc.perform(get("/api/ops/companies").session(s1)).andExpect(status().isOk());

        MockHttpSession s2 = new MockHttpSession();
        login(s2, op, OP_PW).andExpect(jsonPath("$.data.mfa").value("VERIFY")).andExpect(jsonPath("$.data.secret").doesNotExist());
        // 등록 때 쓴 같은 구간의 코드 재사용
        mvc.perform(json(post("/api/ops/auth/totp"), "{\"code\":\"" + now(secret) + "\"}").session(s2))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("OPS_AUTH_FAILED"));
        // 30초 뒤 구간의 새 코드
        auth.setClock(Clock.offset(clock, Duration.ofSeconds(30)));
        String next = Totp.code(secret, clock.millis() / 1000 / 30 + 1);
        mvc.perform(json(post("/api/ops/auth/totp"), "{\"code\":\"" + next + "\"}").session(s2)).andExpect(status().isOk());
    }

    @Autowired
    io.smarthappy.platform.config.HappyBootProperties props;

    private static String ownerString(String sql, Object... args) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword());
                PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** 구간 한가운데로 고정한 시계(30초 경계 흔들림 방지) */
    private void fixClockMidStep() {
        clock = Clock.fixed(Instant.ofEpochSecond(System.currentTimeMillis() / 1000 / 30 * 30 + 15), ZoneOffset.UTC);
        auth.setClock(clock);
    }

    /** docs/67 F6 — 재사용 방지 기록이 메모리에서 사라져도(재기동·다른 인스턴스) 마지막 로그인에 쓴 코드는 다시 못 쓴다 */
    @Test
    void 재기동_뒤에도_마지막_로그인에_쓴_코드는_다시_못_쓴다() throws Exception {
        fixClockMidStep();
        String secret = signIn(new MockHttpSession());
        auth.forgetUsedSteps();   // 재기동
        MockHttpSession s2 = new MockHttpSession();
        login(s2, op, OP_PW).andExpect(jsonPath("$.data.mfa").value("VERIFY"));
        mvc.perform(json(post("/api/ops/auth/totp"), "{\"code\":\"" + now(secret) + "\"}").session(s2))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("OPS_AUTH_FAILED"));
        // 허용 오차 밖의 다음 코드는 통과
        auth.setClock(Clock.offset(clock, Duration.ofSeconds(90)));
        String later = Totp.code(secret, clock.millis() / 1000 / 30 + 3);
        mvc.perform(json(post("/api/ops/auth/totp"), "{\"code\":\"" + later + "\"}").session(s2)).andExpect(status().isOk());
    }

    /** docs/67 F6 — 봉인은 운영자 행에 묶인다: DB 에서 다른 운영자 행으로 옮겨 넣으면 그 운영자로는 풀리지 않는다 */
    @Test
    void 다른_운영자_행으로_옮긴_봉인으로는_로그인할_수_없다() throws Exception {
        fixClockMidStep();
        String secret = signIn(new MockHttpSession());
        String other = "op" + SEQ.incrementAndGet() + "y";
        ownerUpdate("insert into sys_operator (login_id, op_nm, pwd_hash, created_by) values (?, '다른 운영자', ?, 't')",
                other, encoder.encode(OP_PW));
        String moved = ownerString("select totp_secret_enc from sys_operator where login_id = ?", op);
        ownerUpdate("update sys_operator set totp_secret_enc = ?, totp_enabled_yn = 'Y' where login_id = ?", moved, other);
        auth.setClock(Clock.offset(clock, Duration.ofSeconds(90)));
        String code = Totp.code(secret, clock.millis() / 1000 / 30 + 3);
        MockHttpSession s = new MockHttpSession();
        login(s, other, OP_PW).andExpect(jsonPath("$.data.mfa").value("VERIFY"));
        mvc.perform(json(post("/api/ops/auth/totp"), "{\"code\":\"" + code + "\"}").session(s))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("OPS_AUTH_FAILED"));
        mvc.perform(get("/api/ops/companies").session(s)).andExpect(status().isUnauthorized());
        // 옮겨 넣은 값을 "고쳐" 다시 봉인하지도 않는다
        assertThat(ownerString("select totp_secret_enc from sys_operator where login_id = ?", other)).isEqualTo(moved);
        // 원래 운영자는 그대로 로그인된다
        MockHttpSession mine = new MockHttpSession();
        login(mine, op, OP_PW).andExpect(jsonPath("$.data.mfa").value("VERIFY"));
        mvc.perform(json(post("/api/ops/auth/totp"), "{\"code\":\"" + code + "\"}").session(mine)).andExpect(status().isOk());
    }

    /** docs/67 F6 — 예전 봉인(AAD 없음)은 한 번 열리고 그 자리에서 운영자 행에 묶여 다시 봉인된다(마이그레이션 없이 전환) */
    @Test
    void 예전_형식_봉인은_열리고_운영자_행에_묶어_다시_봉인된다() throws Exception {
        fixClockMidStep();
        String secret = signIn(new MockHttpSession());
        SecretBox box = new SecretBox(props.ops().secretKey());
        String legacy = box.seal(secret);
        ownerUpdate("update sys_operator set totp_secret_enc = ? where login_id = ?", legacy, op);
        auth.setClock(Clock.offset(clock, Duration.ofSeconds(90)));
        MockHttpSession s = new MockHttpSession();
        login(s, op, OP_PW).andExpect(jsonPath("$.data.mfa").value("VERIFY"));
        mvc.perform(json(post("/api/ops/auth/totp"), "{\"code\":\"" + Totp.code(secret, clock.millis() / 1000 / 30 + 3) + "\"}")
                .session(s)).andExpect(status().isOk());
        String now = ownerString("select totp_secret_enc from sys_operator where login_id = ?", op);
        String opId = ownerString("select op_id::text from sys_operator where login_id = ?", op);
        assertThat(now).isNotEqualTo(legacy);
        assertThat(box.open(now, OpsAuthService.aad(Long.parseLong(opId)))).isEqualTo(secret);
        assertThatThrownBy(() -> box.open(now)).as("AAD 없이는 더 안 열린다").isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 실패는_구분없이_같은_응답_5회면_잠금() throws Exception {
        MockHttpSession s = new MockHttpSession();
        login(s, "no-such-op", OP_PW).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("OPS_AUTH_FAILED"));
        for (int i = 0; i < 5; i++) {
            login(s, op, "wrong-password-" + i).andExpect(status().isUnauthorized());
        }
        login(s, op, OP_PW).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("OPS_AUTH_FAILED"));
        assertThat(jdbc.queryForObject("select count(*) from sys_ops_log where op_login_id = ? and result_cd = 'DENIED'",
                Integer.class, op)).isEqualTo(1);
    }

    /** n 건을 한꺼번에 — 돌려주는 값 = 예외 없이 끝난 수 */
    private int concurrently(int n, java.util.function.IntConsumer work) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Boolean>> fs = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                int k = i;
                fs.add(pool.submit(() -> {
                    go.await();
                    try {
                        work.accept(k);
                        return true;
                    } catch (io.smarthappy.platform.common.web.BusinessException e) {
                        return false;
                    }
                }));
            }
            go.countDown();
            // 같은 때 다른 요청이 정상(연결 풀이 바닥나지 않았다)
            login(new MockHttpSession(), "no-such-op", OP_PW).andExpect(status().isUnauthorized());
            int ok = 0;
            for (var f : fs) {
                ok += f.get(20, java.util.concurrent.TimeUnit.SECONDS) ? 1 : 0;   // 교착이면 여기서 시간 초과
            }
            return ok;
        } finally {
            pool.shutdownNow();
        }
    }

    private int opsLogCount(String result) {
        return jdbc.queryForObject("select count(*) from sys_ops_log where op_login_id = ? and result_cd = ?",
                Integer.class, op, result);
    }

    @Test
    void 동시_비밀번호_추측은_5회를_넘지_못하고_연결_풀도_멈추지_않는다() throws Exception {
        // 연결 풀(10)보다 많게 — 요청이 연결을 겹쳐 쥐면 교착(docs/36 14.4.1 ②와 같은 방식)
        int ok = concurrently(30, i -> auth.checkPassword(op, "wrong-password-" + i, "10.0.0.1"));
        assertThat(ok).isZero();
        assertThat(opsLogCount("FAIL")).as("비밀번호까지 비교한 시도").isEqualTo(5);
        assertThat(opsLogCount("DENIED")).as("잠겨서 판정 없이 거부").isEqualTo(25);
        assertThat(jdbc.queryForObject("select fail_cnt from sys_operator where login_id = ?", Integer.class, op)).isEqualTo(5);
        login(new MockHttpSession(), op, OP_PW).andExpect(status().isUnauthorized());
    }

    @Test
    void 동시_코드_추측도_5회를_넘지_못하고_비밀번호가_맞으면_선점분은_되돌린다() throws Exception {
        String secret = signIn(new MockHttpSession());
        long opId = jdbc.queryForObject("select op_id from sys_operator where login_id = ?", Long.class, op);
        // 비밀번호 4번 틀리고 맞으면 → 실패 4 그대로(잠기지 않음), 합계는 TOTP 까지 마쳐야 0
        for (int i = 0; i < 4; i++) {
            login(new MockHttpSession(), op, "wrong-" + i).andExpect(status().isUnauthorized());
        }
        login(new MockHttpSession(), op, OP_PW).andExpect(status().isOk()).andExpect(jsonPath("$.data.mfa").value("VERIFY"));
        assertThat(jdbc.queryForObject("select fail_cnt || ':' || (locked_until is null)::text from sys_operator where op_id = ?",
                String.class, opId)).isEqualTo("4:true");
        jdbc.update("update sys_operator set fail_cnt = 0 where op_id = ?", opId);
        // TOTP 동시 30건(맞을 수 없는 값) — 판정 5, 나머지 잠김
        int ok = concurrently(30, i -> auth.verifyTotp(opId, "guess-" + i, "10.0.0.1"));
        assertThat(ok).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sys_ops_log where op_login_id = ? and detail = 'totp'",
                Integer.class, op)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select fail_cnt from sys_operator where op_id = ?", Integer.class, opId)).isEqualTo(5);
        // 잠긴 동안 맞는 코드도 거부
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> auth.verifyTotp(opId, now(secret), "10.0.0.1")))
                .isInstanceOf(io.smarthappy.platform.common.web.BusinessException.class);
    }

    @Test
    void 틀린_코드_형식_오류_대기_없는_코드는_거부() throws Exception {
        MockHttpSession s = new MockHttpSession();
        mvc.perform(json(post("/api/ops/auth/totp"), "{\"code\":\"123456\"}").session(s)).andExpect(status().isUnauthorized());
        login(s, op, OP_PW);
        for (String bad : new String[] {"000000", "12345", "abcdef", "1234567", ""}) {
            mvc.perform(json(post("/api/ops/auth/totp"), "{\"code\":\"" + bad + "\"}").session(s))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/ops/companies").session(s)).andExpect(status().isUnauthorized());
    }

    @Test
    void 비활동_15분이면_세션_끝() throws Exception {
        MockHttpSession s = new MockHttpSession();
        signIn(s);
        s.setAttribute(OpsSecurityConfig.LAST_SEEN, System.currentTimeMillis() - Duration.ofMinutes(16).toMillis());
        mvc.perform(get("/api/ops/companies").session(s)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    @Test
    void 운영자와_회사_사용자는_서로의_화면을_쓸_수_없다() throws Exception {
        MockHttpSession s = new MockHttpSession();
        signIn(s);
        mvc.perform(get("/api/platform/menu").session(s)).andExpect(status().isForbidden());
        mvc.perform(get("/main").session(s)).andExpect(status().is4xxClientError());

        String comp = accounts.newComp("OS");
        long u = accounts.newUser(comp, "kim", USER_PW);
        mvc.perform(get("/api/ops/companies").with(accounts.as(comp, u, "kim"))).andExpect(status().isForbidden());
    }

    // ---------- 회사 ----------

    @Test
    void 회사_생성_후_초기_관리자가_바로_로그인하고_비밀번호_변경을_요구받는다() throws Exception {
        MockHttpSession s = new MockHttpSession();
        signIn(s);
        String comp = "NEW" + SEQ.incrementAndGet();
        MvcResult r = mvc.perform(json(post("/api/ops/companies"), "{\"compCd\":\"" + comp.toLowerCase()
                + "\",\"compNm\":\"신규정밀\",\"adminLoginId\":\"boss\",\"adminName\":\"김대표\"}").session(s))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.compCd").value(comp)).andReturn();
        String temp = JsonPath.read(r.getResponse().getContentAsString(), "$.data.tempPassword");
        assertThat(temp).hasSize(16);

        mvc.perform(post("/login").with(csrf()).with(from("10.55.0.1")).param("compCd", comp).param("loginId", "boss")
                .param("password", temp)).andExpect(redirectedUrl("/main"));
        assertThat(accounts.query(comp, "select pwd_change_req_yn from sys_user where login_id = 'boss'", String.class))
                .isEqualTo("Y");
        assertThat(accounts.query(comp, "select count(*) from sys_menu where prog_cd is not null", Integer.class))
                .isPositive();
        assertThat(accounts.query(comp, "select actor from sys_audit_log where table_nm = 'sys_comp' and action = 'INSERT'",
                String.class)).isEqualTo("ops:" + op);
        mvc.perform(get("/api/ops/companies").session(s)).andExpect(jsonPath("$.list[?(@.compCd=='" + comp + "')]").exists());
    }

    @Test
    void 회사_생성_입력_오류와_중복() throws Exception {
        MockHttpSession s = new MockHttpSession();
        signIn(s);
        mvc.perform(json(post("/api/ops/companies"), "{\"compCd\":\"a b\",\"compNm\":\"\",\"timeZone\":\"Mars/Base\","
                + "\"adminLoginId\":\"x\"}").session(s)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(4));
        String comp = accounts.newComp("OS");
        mvc.perform(json(post("/api/ops/companies"), "{\"compCd\":\"" + comp + "\",\"compNm\":\"중복\",\"adminLoginId\":\"boss\"}")
                .session(s)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
    }

    @Test
    void 회사_생성_때_사용_모듈을_고른다_필수_짝이_빠지면_회사를_만들지_않는다() throws Exception {
        MockHttpSession s = new MockHttpSession();
        signIn(s);
        // 품질만 켜고 생산·창고를 빼면 400 — 회사도 생기지 않는다(docs/80 2장)
        String bad = "NEWM" + SEQ.incrementAndGet();
        mvc.perform(json(post("/api/ops/companies"), "{\"compCd\":\"" + bad + "\",\"compNm\":\"모듈\",\"adminLoginId\":\"boss\","
                + "\"modules\":[\"MST\",\"QMS\"]}").session(s)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MODULE_DEPENDENCY")).andExpect(jsonPath("$.details.length()").value(2));
        assertThat(accounts.query(bad, "select count(*) from sys_comp", Integer.class)).isZero();
        // 기준정보·생산만 → 나머지 8개는 꺼짐 행
        String comp = "NEWM" + SEQ.incrementAndGet();
        mvc.perform(json(post("/api/ops/companies"), "{\"compCd\":\"" + comp + "\",\"compNm\":\"모듈\",\"adminLoginId\":\"boss\","
                + "\"modules\":[\"MST\",\"PRD\",\"SYS\"]}").session(s)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select string_agg(module_cd, ',' order by module_cd) from sys_comp_module where use_yn = 'Y'",
                String.class)).isEqualTo("MST,PRD");
        assertThat(accounts.query(comp, "select count(*) from sys_comp_module where use_yn = 'N'", Integer.class)).isEqualTo(8);
        // modules 를 안 보내면 행 없음 = 전부 켜짐(예전과 같음)
        String all = "NEWM" + SEQ.incrementAndGet();
        mvc.perform(json(post("/api/ops/companies"), "{\"compCd\":\"" + all + "\",\"compNm\":\"모듈\",\"adminLoginId\":\"boss\"}")
                .session(s)).andExpect(status().isOk());
        assertThat(accounts.query(all, "select count(*) from sys_comp_module", Integer.class)).isZero();
    }

    @Test
    void 관리자_잠금_해제는_운영_기록에_남고_그_회사_관리자만_본다() throws Exception {
        MockHttpSession s = new MockHttpSession();
        signIn(s);
        String comp = accounts.newComp("OS");
        long admin = accounts.newUser(comp, "admin1", USER_PW);
        accounts.grant(comp, admin, "ADMIN", "sys-support", "SEARCH", "SAVE");
        accounts.exec(comp, "update sys_user set lock_yn = 'Y', locked_until = null where user_id = ?", admin);
        mvc.perform(json(post("/api/ops/companies/" + comp + "/unlock"), "{\"loginId\":\"ADMIN1\"}").session(s))
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select lock_yn from sys_user where user_id = ?", String.class, admin)).isEqualTo("N");
        mvc.perform(get("/api/platform/support/ops-log").with(accounts.as(comp, admin, "admin1")))
                .andExpect(jsonPath("$.list[0].action").value("USER_UNLOCK"))
                .andExpect(jsonPath("$.list[0].opLoginId").value(op));
        String other = accounts.newComp("OS");
        long otherAdmin = accounts.newUser(other, "admin1", USER_PW);
        accounts.grant(other, otherAdmin, "ADMIN", "sys-support", "SEARCH");
        mvc.perform(get("/api/platform/support/ops-log").with(accounts.as(other, otherAdmin, "admin1")))
                .andExpect(jsonPath("$.list.length()").value(0));
        mvc.perform(json(post("/api/ops/companies/" + comp + "/unlock"), "{\"loginId\":\"nobody\"}").session(s))
                .andExpect(status().isNotFound());
    }

    // ---------- 활용 집계 (D3: 숫자만) ----------

    @Test
    void 운영자는_집계_숫자만_회사_관리자는_자기_회사만() throws Exception {
        String a = accounts.newComp("OS");
        String b = accounts.newComp("OS");
        String day = LocalDate.now().minusDays(1).toString();
        for (String c : new String[] {a, b}) {
            accounts.exec(c, """
                    insert into sys_usage_daily (comp_cd, usage_date, event_cd, prog_cd, client_type, user_cnt, event_cnt)
                    values (?, ?::date, 'LOGIN_OK', '', 'WEB', 3, 7), (?, ?::date, 'PAGE_VIEW', 'mst-cust', 'WEB', 2, 40)""",
                    c, day, c, day);
        }
        MockHttpSession s = new MockHttpSession();
        signIn(s);
        mvc.perform(get("/api/ops/companies/" + a + "/usage").param("from", day).param("to", day).session(s))
                .andExpect(jsonPath("$.data.days[0].users").value(3))
                .andExpect(jsonPath("$.data.days[0].pageViews").value(40))
                .andExpect(jsonPath("$.data.programs[0].progCd").value("mst-cust"))
                .andExpect(jsonPath("$.data.days[0].loginId").doesNotExist());
        long admin = accounts.newUser(a, "admin1", USER_PW);
        accounts.grant(a, admin, "ADMIN", "sys-usage", "SEARCH");
        // 회사 쪽 API 에 B사 코드를 넣을 방법은 없고, RLS 로 자기 회사 행만 — 운영자 정책(OPS)은 회사 요청에서 열리지 않는다
        assertThat(accounts.query(a, "select count(distinct comp_cd) from sys_usage_daily", Integer.class)).isEqualTo(1);
        mvc.perform(get("/api/platform/usage").param("from", day).param("to", day).with(accounts.as(a, admin, "admin1")))
                .andExpect(jsonPath("$.data.days[0].logins").value(7));
    }

    @Test
    void 운영자_범위로는_회사_업무_데이터가_보이지_않는다() {
        String comp = accounts.newComp("OS");
        accounts.newUser(comp, "kim", USER_PW);
        assertThat(runner.callOps("ops:test", () -> jdbc.queryForObject("select count(*) from sys_user", Integer.class)))
                .isZero();
        assertThat(runner.callOps("ops:test", () -> jdbc.queryForObject("select count(*) from sys_access_log", Integer.class)))
                .isZero();
        assertThat(runner.callOps("ops:test", () -> jdbc.queryForObject("select count(*) from sys_comp where comp_cd = ?",
                Integer.class, comp))).isEqualTo(1);
    }

    // ---------- 지원 세션 ----------

    @Test
    void 고객이_허락한_범위_기간_운영자만_열람() throws Exception {
        String comp = accounts.newComp("OS");
        long admin = accounts.newUser(comp, "admin1", USER_PW);
        accounts.grant(comp, admin, "ADMIN", "sys-support", "SEARCH", "SAVE");
        RequestPostProcessor ad = accounts.as(comp, admin, "admin1");
        MockHttpSession s = new MockHttpSession();
        signIn(s);
        String url = "/api/ops/companies/" + comp + "/support/";

        mvc.perform(get(url + "LOGS").session(s)).andExpect(status().isForbidden());
        MvcResult g = mvc.perform(json(post("/api/platform/support/grants"),
                "{\"opLoginId\":\"" + op + "\",\"scopeCd\":\"LOGS\",\"reason\":\"로그인 오류 문의 #12\",\"hours\":2}").with(ad))
                .andExpect(status().isOk()).andReturn();
        long grantId = ((Number) JsonPath.read(g.getResponse().getContentAsString(), "$.data")).longValue();
        mvc.perform(get(url + "LOGS").session(s)).andExpect(status().isOk());
        mvc.perform(get(url + "FEEDBACK").session(s)).andExpect(status().isForbidden());

        // 다른 운영자는 같은 허락으로 볼 수 없다
        String op2 = op + "b";
        ownerUpdate("insert into sys_operator (login_id, op_nm, pwd_hash, created_by) values (?, 'x', ?, 't')",
                op2, encoder.encode(OP_PW));
        String saved = op;
        op = op2;
        MockHttpSession s2 = new MockHttpSession();
        signIn(s2);
        op = saved;
        mvc.perform(get(url + "LOGS").session(s2)).andExpect(status().isForbidden());

        // 회수 → 거부, 운영 기록에 OK·DENIED 모두
        mvc.perform(post("/api/platform/support/grants/" + grantId + "/revoke").with(csrf()).with(ad)).andExpect(status().isOk());
        mvc.perform(get(url + "LOGS").session(s)).andExpect(status().isForbidden());
        mvc.perform(get("/api/platform/support/ops-log").with(ad))
                .andExpect(jsonPath("$.list[?(@.action=='SUPPORT_VIEW' && @.resultCd=='OK')]").exists())
                .andExpect(jsonPath("$.list[?(@.action=='SUPPORT_VIEW' && @.resultCd=='DENIED')]").exists());
    }

    @Test
    void 기간이_지난_허락은_무효() throws Exception {
        String comp = accounts.newComp("OS");
        long admin = accounts.newUser(comp, "admin1", USER_PW);
        accounts.exec(comp, """
                insert into sys_support_grant (comp_cd, op_login_id, scope_cd, reason, from_at, to_at, approved_by, created_by, updated_by)
                values (?, ?, 'LOGS', '지난 문의', now() - interval '3 days', now() - interval '1 day', ?, 't', 't')""",
                comp, op, admin);
        MockHttpSession s = new MockHttpSession();
        signIn(s);
        mvc.perform(get("/api/ops/companies/" + comp + "/support/LOGS").session(s)).andExpect(status().isForbidden());
    }

    @Test
    void 허락_입력_규칙() throws Exception {
        String comp = accounts.newComp("OS");
        long admin = accounts.newUser(comp, "admin1", USER_PW);
        accounts.grant(comp, admin, "ADMIN", "sys-support", "SEARCH", "SAVE");
        RequestPostProcessor ad = accounts.as(comp, admin, "admin1");
        mvc.perform(json(post("/api/platform/support/grants"),
                "{\"opLoginId\":\"nobody-op\",\"scopeCd\":\"ALL\",\"reason\":\" \",\"hours\":200}").with(ad))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(4));
        mvc.perform(json(post("/api/platform/support/grants"),   // 보이지 않는 글자뿐인 사유(QA R4-a)
                "{\"opLoginId\":\"" + op + "\",\"scopeCd\":\"LOGS\",\"reason\":\"\\u200B\\u3000\",\"hours\":2}").with(ad))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        assertThatThrownBy(() -> accounts.exec(comp, """
                insert into sys_support_grant (comp_cd, op_login_id, scope_cd, reason, from_at, to_at, approved_by, created_by, updated_by)
                values (?, ?, 'LOGS', '한 달', now(), now() + interval '30 days', ?, 't', 't')""", comp, op, admin))
                .rootCause().hasMessageContaining("ck_sys_support_grant_range");
        long reader = accounts.newUser(comp, "reader1", USER_PW);
        accounts.grant(comp, reader, "READER", "sys-support", "SEARCH");
        mvc.perform(json(post("/api/platform/support/grants"),
                "{\"opLoginId\":\"" + op + "\",\"scopeCd\":\"LOGS\",\"reason\":\"x\"}").with(accounts.as(comp, reader, "reader1")))
                .andExpect(status().isForbidden());
    }

    // ---------- DB 권한 ----------

    @Test
    void 앱_계정은_운영자를_만들거나_이름을_바꾸거나_운영_기록을_고칠_수_없다() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into sys_operator (login_id, op_nm, pwd_hash, created_by) values ('evil', 'x', 'x', 'x')"))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.update("update sys_operator set op_nm = 'x'")).rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.update("update sys_ops_log set result_cd = 'OK'")).rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> jdbc.update("delete from sys_ops_log")).rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void 부트스트랩은_운영자가_없을_때만_짧은_비밀번호는_거부() throws Exception {
        OpsBootstrap b = new OpsBootstrap(null, encoder);
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword())) {
            JdbcTemplate owner = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(c, true));
            assertThat(b.bootstrap(owner, "another", "Long-enough-pass-1")).isZero(); // 이미 운영자가 있음
            assertThatThrownBy(() -> b.bootstrap(owner, "another", "short")).isInstanceOf(IllegalStateException.class);
        }
    }
}
