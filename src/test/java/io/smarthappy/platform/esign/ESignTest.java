package io.smarthappy.platform.esign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.IllegalTransactionStateException;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.security.MfaService;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 시스템관리 5단계 전자서명 재인증 (docs/36 16장) — 토큰 발급(재인증) → 업무 트랜잭션 안에서 서명 원장.
 * 정상 + 이상 사용(B 입력, E 다른 사람·다른 대상 토큰, F 재사용·만료, 5회 실패 세션 끊기, 동시 대입·연결 풀, MFA, A사로 B사, 직접 SQL)
 */
@AutoConfigureMockMvc
class ESignTest extends PostgresTestBase {

    static final String PW = "Quiet-river-77";
    static final String API = "/api/platform/esign";
    static final String HASH = ESignService.hash("MRB-1|SCRAP|10");

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    ESignService esign;

    @Autowired
    MfaService mfa;

    @Autowired
    TenantRunner runner;

    String comp;
    long qaId;
    long otherId;
    Instant now = Instant.parse("2026-10-22T00:00:00Z");

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("ES");
        qaId = accounts.newUser(comp, "qa", PW);
        otherId = accounts.newUser(comp, "other", PW);
    }

    @AfterEach
    void reset() {
        esign.setClock(Clock.systemUTC());
        ReflectionTestUtils.invokeMethod(mfa, "setClock", Clock.systemUTC());
        SecurityContextHolder.clearContext();
    }

    private ResultActions token(MockHttpSession s, long userId, String login, String meaning, String type, long target,
            String pw, String code) throws Exception {
        String body = "{\"meaningCd\":\"" + meaning + "\",\"targetType\":\"" + type + "\",\"targetId\":" + target
                + ",\"password\":\"" + pw + "\",\"code\":" + (code == null ? "null" : "\"" + code + "\"") + "}";
        return mvc.perform(post(API + "/token").with(csrf()).with(accounts.as(comp, userId, login)).session(s)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String issued(ResultActions r) throws Exception {
        return JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.token");
    }

    /** 업무 트랜잭션 안에서 consume (모듈 API 가 하는 일) */
    private <T> T as(String compCd, long userId, String login, Supplier<T> work) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new LoginUser(userId, compCd, login, login, "ko", false), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        try {
            return runner.call(compCd, work);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private HbErrorCode codeOf(Runnable r) {
        try {
            r.run();
        } catch (BusinessException e) {
            return e.code();
        }
        return null;
    }

    private static String constraintOf(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            return PgErrors.constraint(e).orElse("(none) " + e.getMessage());
        }
        return "(no error)";
    }

    private int accessCount(String event) {
        return accounts.query(comp, "select count(*) from sys_access_log where event_cd = ?", Integer.class, event);
    }

    // ================================================================== 정상

    @Test
    void 재인증_토큰으로_업무_트랜잭션_안에서_서명하면_원장에_이름_의미_해시가_남는다() throws Exception {
        MockHttpSession s = new MockHttpSession();
        mvc.perform(get(API + "/requirement").with(accounts.as(comp, qaId, "qa"))).andExpect(jsonPath("$.data.mfa").value(false));
        String t = issued(token(s, qaId, "qa", "APPROVE", "qms_mrb", 7, PW, null)
                .andExpect(jsonPath("$.data.authMethod").value("PWD")).andExpect(jsonPath("$.data.expiresInSec").value(300)));
        long id = as(comp, qaId, "qa", () -> esign.consume(s, t, "APPROVE", "qms_mrb", 7, HASH));
        assertThat(accounts.query(comp, "select signer_nm || '|' || meaning_cd || '|' || auth_method || '|' || content_hash from sys_esign where esign_id = ?",
                String.class, id)).isEqualTo("qa 사용자|APPROVE|PWD|" + HASH);
        List<ESignService.Signature> list = as(comp, qaId, "qa", () -> esign.list("qms_mrb", 7));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).signerNm()).isEqualTo("qa 사용자");
        // 이름이 바뀌어도 서명의 이름은 그대로(스냅샷)
        accounts.exec(comp, "update sys_user set user_nm = '새 이름' where user_id = ?", qaId);
        assertThat(as(comp, qaId, "qa", () -> esign.list("qms_mrb", 7)).get(0).signerNm()).isEqualTo("qa 사용자");
        // 같은 토큰은 한 번만
        assertThat(codeOf(() -> as(comp, qaId, "qa", () -> esign.consume(s, t, "APPROVE", "qms_mrb", 7, HASH))))
                .isEqualTo(HbErrorCode.ESIGN_TOKEN);
        // 업무 트랜잭션 밖에서 consume 은 프로그래밍 오류
        String t2 = issued(token(s, qaId, "qa", "REVIEW", "qms_mrb", 7, PW, null));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new LoginUser(qaId, comp, "qa", "qa", "ko", false), null, List.of()));
        assertThatThrownBy(() -> esign.consume(s, t2, "REVIEW", "qms_mrb", 7, HASH)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(ESignService.hash("a")).isEqualTo("ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb");
    }

    // ================================================================== 이상 사용

    @Test
    void 토큰은_사람_의미_대상_만료에_묶인다() throws Exception {
        MockHttpSession s = new MockHttpSession();
        esign.setClock(Clock.fixed(now, ZoneOffset.UTC));
        String t = issued(token(s, qaId, "qa", "APPROVE", "qms_mrb", 7, PW, null));
        // 다른 의미·다른 대상·다른 사람 — 토큰은 그 자리에서 버려진다(구분 없이 403)
        assertThat(codeOf(() -> as(comp, qaId, "qa", () -> esign.consume(s, t, "REVIEW", "qms_mrb", 7, HASH)))).isEqualTo(HbErrorCode.ESIGN_TOKEN);
        String t2 = issued(token(s, qaId, "qa", "APPROVE", "qms_mrb", 7, PW, null));
        assertThat(codeOf(() -> as(comp, qaId, "qa", () -> esign.consume(s, t2, "APPROVE", "qms_mrb", 8, HASH)))).isEqualTo(HbErrorCode.ESIGN_TOKEN);
        String t3 = issued(token(s, qaId, "qa", "APPROVE", "qms_mrb", 7, PW, null));
        assertThat(codeOf(() -> as(comp, otherId, "other", () -> esign.consume(s, t3, "APPROVE", "qms_mrb", 7, HASH)))).isEqualTo(HbErrorCode.ESIGN_TOKEN);
        assertThat(codeOf(() -> as(comp, qaId, "qa", () -> esign.consume(s, t3, "APPROVE", "qms_mrb", 7, HASH)))).isEqualTo(HbErrorCode.ESIGN_TOKEN);
        // 다른 세션의 토큰은 없다
        String t4 = issued(token(s, qaId, "qa", "APPROVE", "qms_mrb", 7, PW, null));
        assertThat(codeOf(() -> as(comp, qaId, "qa", () -> esign.consume(new MockHttpSession(), t4, "APPROVE", "qms_mrb", 7, HASH))))
                .isEqualTo(HbErrorCode.ESIGN_TOKEN);
        // 5분 지나면 만료
        String t5 = issued(token(s, qaId, "qa", "APPROVE", "qms_mrb", 7, PW, null));
        esign.setClock(Clock.fixed(now.plusSeconds(301), ZoneOffset.UTC));
        assertThat(codeOf(() -> as(comp, qaId, "qa", () -> esign.consume(s, t5, "APPROVE", "qms_mrb", 7, HASH)))).isEqualTo(HbErrorCode.ESIGN_TOKEN);
        assertThat(accounts.query(comp, "select count(*) from sys_esign", Integer.class)).isZero();
        // B 입력: 없는 의미·대상 형식·id·해시 형식
        token(s, qaId, "qa", "DELETE", "qms_mrb", 7, PW, null).andExpect(status().isBadRequest());
        token(s, qaId, "qa", "APPROVE", "QMS-MRB", 7, PW, null).andExpect(status().isBadRequest());
        token(s, qaId, "qa", "APPROVE", "qms_mrb", 0, PW, null).andExpect(status().isBadRequest());
        String t6 = issued(token(s, qaId, "qa", "APPROVE", "qms_mrb", 7, PW, null));
        assertThatThrownBy(() -> as(comp, qaId, "qa", () -> esign.consume(s, t6, "APPROVE", "qms_mrb", 7, "ABC")))
                .isInstanceOf(IllegalArgumentException.class);
        // 세션 없이 / CSRF 없이
        mvc.perform(post(API + "/token").with(csrf()).with(accounts.as(comp, qaId, "qa")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"meaningCd\":\"APPROVE\",\"targetType\":\"qms_mrb\",\"targetId\":7,\"password\":\"" + PW + "\"}"))
                .andExpect(status().isOk());   // MockMvc 는 세션을 새로 만든다 — 토큰은 그 세션에만
        mvc.perform(post(API + "/token").with(accounts.as(comp, qaId, "qa")).session(s).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
    }

    @Test
    void 토큰은_세션당_5개_넘으면_가장_오래된_하나만_지운다() throws Exception {
        MockHttpSession s = new MockHttpSession();
        esign.setClock(Clock.fixed(now, ZoneOffset.UTC));   // 같은 시각에 받아도 발급 순서로 가린다
        List<String> toks = new ArrayList<>();
        for (long t = 1; t <= 7; t++) {
            toks.add(issued(token(s, qaId, "qa", "APPROVE", "qms_mrb", t, PW, null)));
        }
        // 7개 받음 → 1·2 번째만 사라지고 3~7 은 쓸 수 있다
        for (int i = 0; i < 2; i++) {
            int k = i;
            assertThat(codeOf(() -> as(comp, qaId, "qa", () -> esign.consume(s, toks.get(k), "APPROVE", "qms_mrb", k + 1, HASH))))
                    .isEqualTo(HbErrorCode.ESIGN_TOKEN);
        }
        for (int i = 2; i < 7; i++) {
            int k = i;
            assertThat(as(comp, qaId, "qa", () -> esign.consume(s, toks.get(k), "APPROVE", "qms_mrb", k + 1, HASH))).isPositive();
        }
    }

    @Test
    void 비밀번호를_5번_틀리면_세션을_끊는다() throws Exception {
        MockHttpSession s = new MockHttpSession();
        for (int i = 4; i >= 1; i--) {
            token(s, qaId, "qa", "APPROVE", "qms_mrb", 7, "wrong-" + i, null).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ESIGN_INVALID"));
        }
        // 맞으면 실패 수는 0 으로 — 다시 4번까지 틀릴 수 있다
        issued(token(s, qaId, "qa", "APPROVE", "qms_mrb", 7, PW, null));
        for (int i = 0; i < 4; i++) {
            token(s, qaId, "qa", "APPROVE", "qms_mrb", 7, "wrong-" + i, null).andExpect(status().isBadRequest());
        }
        token(s, qaId, "qa", "APPROVE", "qms_mrb", 7, "wrong-5", null).andExpect(status().isUnauthorized());
        assertThat(s.isInvalid()).isTrue();
        assertThat(accessCount("ESIGN_FAIL")).isEqualTo(8);
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'ESIGN_LOCKED' and severity = 'ALERT'",
                Integer.class)).isEqualTo(1);
        // 계정은 잠그지 않는다(본인 업무는 다시 로그인해 계속)
        assertThat(accounts.query(comp, "select lock_yn from sys_user where user_id = ?", String.class, qaId)).isEqualTo("N");
    }

    @Test
    void 같은_세션_동시_대입도_5회를_넘지_못하고_연결_풀도_멈추지_않는다() throws Exception {
        MockHttpSession s = new MockHttpSession();
        LoginUser me = new LoginUser(qaId, comp, "qa", "qa", "ko", false);
        int n = 30;   // 연결 풀(10)보다 많게
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<HbErrorCode>> fs = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                int k = i;
                fs.add(pool.submit(() -> {
                    go.await();
                    SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                            me, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
                    try (var scope = TenantContext.openRequestScope(comp, "u" + qaId)) {
                        esign.issue(me, s, new ESignService.IssueForm("APPROVE", "qms_mrb", 7L, "wrong-" + k, null));
                        return null;
                    } catch (BusinessException e) {
                        return e.code();
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            go.countDown();
            mvc.perform(get(API + "/requirement").with(accounts.as(comp, otherId, "other"))).andExpect(status().isOk());
            int invalid = 0;
            for (var f : fs) {
                if (f.get(20, TimeUnit.SECONDS) == HbErrorCode.ESIGN_INVALID) {   // 교착이면 시간 초과
                    invalid++;
                }
            }
            // 비밀번호까지 비교한 틀린 시도 ≤ 4(다섯 번째는 세션을 끊는다), 나머지는 비교 없이 끊김
            assertThat(invalid).isLessThanOrEqualTo(ESignService.MAX_FAIL - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(s.isInvalid()).isTrue();
        assertThat(accessCount("ESIGN_FAIL")).isLessThanOrEqualTo(4);
    }

    @Test
    void MFA_를_쓰는_사람은_인증_코드도_복구_코드는_안_된다() throws Exception {
        LoginUser me = new LoginUser(qaId, comp, "qa", "qa", "ko", false);
        ReflectionTestUtils.invokeMethod(mfa, "setClock", Clock.fixed(now, ZoneOffset.UTC));
        String secret = as(comp, qaId, "qa", () -> mfa.startEnroll(me).secret());
        List<String> recovery = new ArrayList<>();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(me, null, List.of()));
        String first = totp(secret, now);
        recovery.addAll(runner.call(comp, () -> mfa.confirmEnroll(me, first)));
        SecurityContextHolder.clearContext();
        Instant later = now.plusSeconds(60);
        ReflectionTestUtils.invokeMethod(mfa, "setClock", Clock.fixed(later, ZoneOffset.UTC));

        MockHttpSession s = new MockHttpSession();
        mvc.perform(get(API + "/requirement").with(accounts.as(comp, qaId, "qa"))).andExpect(jsonPath("$.data.mfa").value(true));
        token(s, qaId, "qa", "APPROVE", "qms_mrb", 7, PW, null).andExpect(jsonPath("$.code").value("ESIGN_MFA_REQUIRED"));
        token(s, qaId, "qa", "APPROVE", "qms_mrb", 7, PW, recovery.get(0)).andExpect(jsonPath("$.code").value("MFA_INVALID"));
        String t = issued(token(s, qaId, "qa", "APPROVE", "qms_mrb", 7, PW, totp(secret, later))
                .andExpect(jsonPath("$.data.authMethod").value("PWD_TOTP")));
        long id = as(comp, qaId, "qa", () -> esign.consume(s, t, "APPROVE", "qms_mrb", 7, HASH));
        assertThat(accounts.query(comp, "select auth_method from sys_esign where esign_id = ?", String.class, id)).isEqualTo("PWD_TOTP");
        // 같은 구간 코드 재사용 거부
        token(s, qaId, "qa", "APPROVE", "qms_mrb", 7, PW, totp(secret, later)).andExpect(jsonPath("$.code").value("MFA_INVALID"));
        // 비밀번호가 틀리면 인증 코드까지 가지 않는다
        token(s, qaId, "qa", "APPROVE", "qms_mrb", 7, "wrong", totp(secret, later)).andExpect(jsonPath("$.code").value("ESIGN_INVALID"));
    }

    @Test
    void A사로_B사와_직접_SQL() throws Exception {
        MockHttpSession s = new MockHttpSession();
        String t = issued(token(s, qaId, "qa", "APPROVE", "qms_mrb", 7, PW, null));
        long id = as(comp, qaId, "qa", () -> esign.consume(s, t, "APPROVE", "qms_mrb", 7, HASH));
        String compB = accounts.newComp("ESB");
        long bUser = accounts.newUser(compB, "bqa", PW);
        // B사 사용자가 같은 세션(탈취 가정)으로 A사 토큰을 써도 거부, B사 범위에서 A사 서명은 보이지 않는다
        String t2 = issued(token(s, qaId, "qa", "APPROVE", "qms_mrb", 7, PW, null));
        assertThat(codeOf(() -> as(compB, qaId, "qa", () -> esign.consume(s, t2, "APPROVE", "qms_mrb", 7, HASH))))
                .isEqualTo(HbErrorCode.ESIGN_TOKEN);
        assertThat(as(compB, bUser, "bqa", () -> esign.list("qms_mrb", 7))).isEmpty();
        assertThat(accounts.query(compB, "select count(*) from sys_esign", Integer.class)).isZero();
        assertThat(constraintOf(() -> accounts.exec(compB, """
                insert into sys_esign (comp_cd, user_id, signer_nm, meaning_cd, target_type, target_id, content_hash, auth_method)
                values (?, ?, 'x', 'APPROVE', 'qms_mrb', 7, ?, 'PWD')""", compB, qaId, HASH))).isEqualTo("fk_user_comp");
        // 직접 SQL: 고치기·지우기 불가, 없는 의미 코드·형식
        assertThatThrownBy(() -> accounts.exec(comp, "update sys_esign set content_hash = ? where esign_id = ?", HASH, id))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> accounts.exec(comp, "delete from sys_esign where esign_id = ?", id))
                .rootCause().hasMessageContaining("permission denied");
        assertThat(constraintOf(() -> accounts.exec(comp, """
                insert into sys_esign (comp_cd, user_id, signer_nm, meaning_cd, target_type, target_id, content_hash, auth_method)
                values (?, ?, 'x', 'DELETE', 'qms_mrb', 7, ?, 'PWD')""", comp, qaId, HASH))).isEqualTo("ck_sys_esign_meaning_code");
        assertThat(constraintOf(() -> accounts.exec(comp, """
                insert into sys_esign (comp_cd, user_id, signer_nm, meaning_cd, target_type, target_id, content_hash, auth_method)
                values (?, ?, 'x', 'APPROVE', 'qms_mrb', 7, 'ABC', 'PWD')""", comp, qaId))).isEqualTo("ck_sys_esign_hash");
        assertThat(accounts.query(comp, "select count(*) from sys_esign", Integer.class)).isOne();
    }

    /** RFC 6238 — 서버 Totp 와 독립 계산 */
    private static String totp(String base32Secret, Instant at) throws Exception {
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
        byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(at.getEpochSecond() / 30).array());
        int o = h[h.length - 1] & 0x0f;
        int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
        return String.format(Locale.ROOT, "%06d", bin % 1_000_000);
    }
}
