package io.smarthappy.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.ops.SecretBox;
import io.smarthappy.platform.ops.Totp;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

/**
 * 회사 사용자 MFA(TOTP) — docs/36 14장. 운영자 콘솔과 같은 {@link Totp}·{@link SecretBox}.
 * <ul>
 * <li>로그인 성공 뒤 {@link #onLogin}: 등록한 사람 → VERIFY, 강제 대상인데 미등록 → ENROLL (세션 속성 {@link #SESSION_STATE}).
 *     {@code TenantRequestFilter} 가 그 동안 MFA 화면·API 만 연다.</li>
 * <li>강제 대상(Q23): 정책 MFA_REQUIRED_FOR_ADMIN = 사용 중 관리자 그룹 구성원, MFA_REQUIRED_PROGS = 그 화면에 권한 행이 있는 사람.</li>
 * <li>실패 5회 → 15분 잠금. 확인 전에 시도 1회분을 조건부 UPDATE 한 문장으로 먼저 차지해 커밋한다(동시 요청으로 5회를 넘겨
 *     추측하지 못하게). 확인 경로는 바깥 트랜잭션 없이 트랜잭션을 차례로 열어 요청당 연결을 하나만 쥔다(풀 고갈 DoS 방지).
 *     맞으면 0으로, 잠금이 끝난 뒤 첫 시도는 1부터. 같은 코드 재사용은 last_step 조건부 UPDATE 로 거부.</li>
 * <li>비밀은 SecretBox 로 "회사:사용자 id" 에 묶어 봉인(AAD) — DB 에서 다른 사용자 행으로 옮기면 풀리지 않는다.</li>
 * <li>{@link #onLogin} 은 판정 전에 VERIFY 를 먼저 걸고, 판정이 실패하면 세션을 무효화한다(fail-closed —
 *     Spring Security 는 성공 처리기 전에 인증을 세션에 저장한다).</li>
 * <li>복구 코드 10개 — 원문은 등록 응답에서 한 번만, DB 는 SHA-256(사용자 id:코드)만(M1).</li>
 * <li>키가 없으면 등록·확인 모두 503 MFA_UNAVAILABLE — MFA 가 필요한 로그인은 열리지 않는다(fail-closed).</li>
 * </ul>
 */
@Slf4j
@Service
public class MfaService {

    /** 세션 속성: 두 번째 인증 대기 상태 (VERIFY · ENROLL), 없으면 통과 */
    public static final String SESSION_STATE = MfaService.class.getName() + ".state";
    public static final String VERIFY = "VERIFY";
    public static final String ENROLL = "ENROLL";

    static final int MAX_FAIL = 5;
    static final int LOCK_MINUTES = 15;
    static final int RECOVERY_COUNT = 10;
    static final String ISSUER = "YeonOS";
    /** 헷갈리는 글자(0 O 1 I L) 뺀 대문자·숫자 — 5-5 형식 */
    private static final String RECOVERY_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final Pattern RECOVERY = Pattern.compile("^[A-Z0-9]{5}-?[A-Z0-9]{5}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Enrollment(String secret, String otpauthUri) {
    }

    public record Status(boolean available, boolean enrolled, boolean required, int recoveryLeft,
            OffsetDateTime enrolledAt) {
    }

    private final JdbcTemplate jdbc;
    private final TenantRunner runner;
    private final PolicyService policies;
    private final AccessLogService accessLog;
    private final SecretBox box;
    private Clock clock = Clock.systemUTC();

    public MfaService(JdbcTemplate jdbc, TenantRunner runner, PolicyService policies, AccessLogService accessLog,
            @Value("${happyboot.mfa.secret-key:}") String mfaKey, @Value("${happyboot.ops.secret-key:}") String opsKey) {
        String key = mfaKey == null || mfaKey.isBlank() ? opsKey : mfaKey;
        this.jdbc = jdbc;
        this.runner = runner;
        this.policies = policies;
        this.accessLog = accessLog;
        SecretBox b = null;
        if (key != null && !key.isBlank()) {
            try {
                b = new SecretBox(key);
            } catch (IllegalArgumentException e) {
                log.error("MFA 키 형식이 틀렸습니다(Base64 32바이트) — MFA 가 필요한 로그인은 막힌다");
            }
        } else {
            log.warn("MFA 키(MFA_SECRET_KEY·OPS_SECRET_KEY)가 없습니다 — MFA 가 필요한 로그인은 막힌다(docs/36 14.1)");
        }
        this.box = b;
    }

    /** 테스트용 시계 교체 */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    public boolean available() {
        return box != null;
    }

    // ------------------------------------------------------------------ 로그인 게이트

    /** 비밀번호 로그인 성공 직후(회사 범위 밖) — 필요한 두 번째 인증을 세션에 표시 */
    public void onLogin(LoginUser user, HttpSession session) {
        // 먼저 닫아 두고 판정 — 판정 중 예외면 인증이 저장된 세션을 버린다(대기 표시 없는 로그인 세션이 남지 않게)
        session.setAttribute(SESSION_STATE, VERIFY);
        String state;
        try {
            state = runner.call(user.compCd(), () -> {
                if (enrolled(user.userId())) {
                    return VERIFY;
                }
                return required(user.userId()) ? ENROLL : null;
            });
        } catch (RuntimeException e) {
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
                // 이미 무효
            }
            throw e;
        }
        if (state == null) {
            session.removeAttribute(SESSION_STATE);
        } else {
            session.setAttribute(SESSION_STATE, state);
        }
    }

    boolean enrolled(long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from sys_user_mfa where user_id = ? and enabled_yn = 'Y')", Boolean.class, userId));
    }

    /** 강제 대상인가 — 관리자 그룹(정책) 또는 지정 화면에 권한 행이 있음 */
    boolean required(long userId) {
        if (policies.bool(Policies.MFA_REQUIRED_FOR_ADMIN) && Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from sys_user_auth ua
                                 join sys_auth_group g on g.comp_cd = ua.comp_cd and g.auth_grp_cd = ua.auth_grp_cd
                                where ua.user_id = ? and g.use_yn = 'Y' and g.admin_yn = 'Y')""", Boolean.class, userId))) {
            return true;
        }
        String[] progs = progList(policies.value(Policies.MFA_REQUIRED_PROGS));
        return progs.length > 0 && Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from sys_user_auth ua
                                 join sys_auth_group g on g.comp_cd = ua.comp_cd and g.auth_grp_cd = ua.auth_grp_cd and g.use_yn = 'Y'
                                 join sys_auth_prog ap on ap.comp_cd = g.comp_cd and ap.auth_grp_cd = g.auth_grp_cd
                                where ua.user_id = ? and ap.prog_cd = any (?))""", Boolean.class, userId, progs));
    }

    static String[] progList(String csv) {
        return csv == null ? new String[0] : Arrays.stream(csv.split(",")).map(String::strip)
                .filter(p -> p.matches("^[a-z0-9][a-z0-9-]{1,49}$")).toArray(String[]::new);
    }

    // ------------------------------------------------------------------ 등록

    /** 새 비밀(등록 중 행). 이미 등록한 사람은 409 — 바꾸려면 초기화(관리자) 후 */
    @Transactional
    public Enrollment startEnroll(LoginUser me) {
        requireAvailable();
        if (enrolled(me.userId())) {
            throw new BusinessException(HbErrorCode.MFA_STATE);
        }
        String secret = Totp.newSecret();
        jdbc.update("""
                insert into sys_user_mfa (comp_cd, user_id, secret_enc, created_by, updated_by)
                values (hb_current_comp(), ?, ?, ?, ?)
                on conflict (comp_cd, user_id) do update
                   set secret_enc = excluded.secret_enc, fail_cnt = 0, locked_until = null, last_step = 0,
                       row_ver = sys_user_mfa.row_ver + 1, updated_at = now(), updated_by = excluded.updated_by
                 where sys_user_mfa.enabled_yn = 'N'""", me.userId(), box.seal(secret, aad(me)), actor(me), actor(me));
        return new Enrollment(secret, Totp.otpauthUri(ISSUER, me.compCd() + ":" + me.loginId(), secret));
    }

    /** 인증 앱의 첫 코드로 확인 → 사용 시작 + 복구 코드(원문은 이 응답에서만) */
    @Transactional
    public List<String> confirmEnroll(LoginUser me, String code) {
        requireAvailable();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select secret_enc, enabled_yn from sys_user_mfa where user_id = ?", me.userId());
        if (rows.isEmpty() || "Y".equals(rows.get(0).get("enabled_yn"))) {
            throw new BusinessException(HbErrorCode.MFA_STATE);
        }
        long step = Totp.verify(open(me, (String) rows.get(0).get("secret_enc")), code, clock, 0);
        if (step < 0) {
            throw new BusinessException(HbErrorCode.MFA_INVALID, MAX_FAIL);
        }
        // 동시 확인 두 건이면 한 건만 — 다른 건은 복구 코드를 다시 만들지 않는다(롤백)
        if (jdbc.update("""
                update sys_user_mfa set enabled_yn = 'Y', enrolled_at = now(), last_step = ?, fail_cnt = 0,
                       row_ver = row_ver + 1, updated_at = now(), updated_by = ?
                 where user_id = ? and enabled_yn = 'N'""", step, actor(me), me.userId()) != 1) {
            throw new BusinessException(HbErrorCode.MFA_STATE);
        }
        List<String> codes = newRecoveryCodes(me.userId());
        accessLog.recordCurrent(AccessEvent.MFA_ENROLLED, null, null, AccessLogService.Result.OK, null);
        return codes;
    }

    private List<String> newRecoveryCodes(long userId) {
        jdbc.update("delete from sys_user_mfa_recovery where user_id = ?", userId);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < RECOVERY_COUNT; i++) {
            String c = randomCode();
            out.add(c);
            jdbc.update("""
                    insert into sys_user_mfa_recovery (comp_cd, user_id, code_hash)
                    values (hb_current_comp(), ?, ?)""", userId, hash(userId, c));
        }
        return out;
    }

    /**
     * 지금 인증 코드로 확인한 뒤 복구 코드를 새로 만든다(이전 것은 모두 무효).
     * 바깥 트랜잭션 없음 — 확인과 같은 순서의 트랜잭션들(아래 check)
     */
    public List<String> regenerateRecovery(LoginUser me, String code) {
        check(me, code, false);
        return runner.call(me.compCd(), () -> newRecoveryCodes(me.userId()));
    }

    // ------------------------------------------------------------------ 확인

    /** 로그인 두 번째 단계 — TOTP 또는 복구 코드. 5회 실패면 잠금. 바깥 트랜잭션 없음(check) */
    public void verify(LoginUser me, String code) {
        check(me, code, true);
    }

    /**
     * 인증 코드 확인만(복구 코드 불가) — 전자서명 재인증(docs/36 16장). 실패 수·잠금은 확인과 같다. 바깥 트랜잭션 없음(check)
     */
    public void confirm(LoginUser me, String code) {
        check(me, code, false);
    }

    /** 등록해서 쓰는 중인가(회사 범위 트랜잭션 안) */
    public boolean isEnrolled(long userId) {
        return enrolled(userId);
    }

    private enum Outcome { TOTP, RECOVERY, WRONG }

    /**
     * 연결을 겹쳐 잡지 않는다(docs/36 14.4.1 ②) — 요청 하나가 연결 둘을 쥐면 동시 요청으로 풀이 바닥나 앱 전체가 멈춘다.
     * 바깥 트랜잭션 없이 차례로: ① 시도 선점(조건부 UPDATE 한 문장 — 잠금 확인·증가가 원자적) 커밋 → ② 판정 커밋 → ③ 접속 로그.
     * runner.call 은 열린 트랜잭션 안에서 부르면 거부한다 — 바깥에 @Transactional 을 거는 회귀를 막는다.
     */
    private void check(LoginUser me, String code, boolean allowRecovery) {
        requireAvailable();
        int fails = runner.call(me.compCd(), () -> claimAttempt(me));
        String c = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        Outcome out = runner.call(me.compCd(), () -> judge(me, c, allowRecovery));
        if (out == Outcome.TOTP || out == Outcome.RECOVERY) {
            accessLog.recordCurrent(AccessEvent.MFA_OK, null, null, AccessLogService.Result.OK,
                    out == Outcome.TOTP ? "totp" : "recovery");
            return;
        }
        if (fails >= MAX_FAIL) {
            accessLog.recordCurrent(AccessEvent.MFA_LOCKED, null, null, AccessLogService.Result.DENIED, null);
            throw new BusinessException(HbErrorCode.MFA_LOCKED, LOCK_MINUTES);
        }
        accessLog.recordCurrent(AccessEvent.MFA_FAIL, null, null, AccessLogService.Result.FAIL, null);
        throw new BusinessException(HbErrorCode.MFA_INVALID, MAX_FAIL - fails);
    }

    /**
     * 시도 1회분을 실패로 먼저 센다 — 조건부 UPDATE 한 문장(행 잠금 + 잠금 확인 + 증가). 동시 요청은 행 잠금으로 한 줄로 서고
     * 잠금이 걸린 뒤 온 것은 0건 → MFA_LOCKED. 잠금이 끝난 행은 1부터(해제 직후 한 번 틀려 다시 잠기지 않게).
     * 5번째 시도가 잠금 시각을 건다(맞으면 판정이 푼다). 시각은 서비스 시계(확인할 때와 같은 시계). 돌려주는 값 = 이번까지의 실패 수
     */
    private int claimAttempt(LoginUser me) {
        OffsetDateTime now = clock.instant().atOffset(java.time.ZoneOffset.UTC);
        OffsetDateTime lockEnd = now.plusMinutes(LOCK_MINUTES);
        List<Integer> n = jdbc.queryForList("""
                update sys_user_mfa
                   set fail_cnt = case when locked_until is not null then 1 else fail_cnt + 1 end,
                       locked_until = case when (case when locked_until is not null then 1 else fail_cnt + 1 end) >= ?
                                           then ? else null end
                 where user_id = ? and enabled_yn = 'Y' and (locked_until is null or locked_until <= ?)
                returning fail_cnt""", Integer.class, MAX_FAIL, lockEnd, me.userId(), now);
        if (!n.isEmpty()) {
            return n.get(0);
        }
        throw new BusinessException(enrolled(me.userId()) ? HbErrorCode.MFA_LOCKED : HbErrorCode.MFA_STATE, LOCK_MINUTES);
    }

    /** 판정 — 맞으면 실패 수·잠금을 풀고 재사용 방지 갱신. 쓰기는 맞을 때만 */
    private Outcome judge(LoginUser me, String c, boolean allowRecovery) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select secret_enc, last_step from sys_user_mfa where user_id = ? and enabled_yn = 'Y'", me.userId());
        if (rows.isEmpty()) {
            throw new BusinessException(HbErrorCode.MFA_STATE);
        }
        Map<String, Object> r = rows.get(0);
        long step = Totp.verify(open(me, (String) r.get("secret_enc")), c, clock, ((Number) r.get("last_step")).longValue());
        // 같은 구간을 동시에 두 번 써도 한 번만 통과(조건부 UPDATE)
        if (step >= 0 && jdbc.update("""
                update sys_user_mfa set last_step = ?, fail_cnt = 0, locked_until = null
                 where user_id = ? and last_step < ?""", step, me.userId(), step) == 1) {
            return Outcome.TOTP;
        }
        if (allowRecovery && RECOVERY.matcher(c).matches() && jdbc.update("""
                update sys_user_mfa_recovery set used_at = now()
                 where user_id = ? and code_hash = ? and used_at is null""", me.userId(), hash(me.userId(), c)) == 1) {
            jdbc.update("update sys_user_mfa set fail_cnt = 0, locked_until = null where user_id = ?", me.userId());
            return Outcome.RECOVERY;
        }
        return Outcome.WRONG;
    }

    // ------------------------------------------------------------------ 상태·초기화

    @Transactional(readOnly = true)
    public Status status(LoginUser me) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select enrolled_at from sys_user_mfa where user_id = ? and enabled_yn = 'Y'", me.userId());
        Integer left = jdbc.queryForObject(
                "select count(*) from sys_user_mfa_recovery where user_id = ? and used_at is null", Integer.class, me.userId());
        Object at = rows.isEmpty() ? null : rows.get(0).get("enrolled_at");
        return new Status(available(), !rows.isEmpty(), required(me.userId()), left == null ? 0 : left,
                at == null ? null : toInstant(at).atOffset(java.time.ZoneOffset.UTC));
    }

    /**
     * 다른 사용자의 MFA 를 지운다(관리자·운영자 — 권한 판정은 부르는 쪽). 다음 로그인 때 다시 등록해야 한다.
     * 회사 범위 트랜잭션 안. 지운 행이 있으면 true
     */
    @Transactional
    public boolean reset(long userId) {
        jdbc.update("delete from sys_user_mfa_recovery where user_id = ?", userId);
        return jdbc.update("delete from sys_user_mfa where user_id = ?", userId) > 0;
    }

    // ------------------------------------------------------------------

    private void requireAvailable() {
        if (box == null) {
            throw new BusinessException(HbErrorCode.MFA_UNAVAILABLE);
        }
    }

    /** 봉인 연결 데이터 — 회사·사용자에 묶는다 */
    private static String aad(LoginUser me) {
        return "sys_user_mfa:" + me.compCd() + ":" + me.userId();
    }

    /** 풀리지 않으면(키가 바뀜·다른 사용자 행에서 옮겨 옴) 503 — 통과시키지 않는다. 관리자 초기화 뒤 다시 등록 */
    private String open(LoginUser me, String sealed) {
        try {
            return box.open(sealed, aad(me));
        } catch (IllegalStateException e) {
            log.error("MFA 비밀을 풀 수 없습니다 compCd={} userId={} — 키 변경 또는 값 변조, 초기화 후 재등록 필요", me.compCd(), me.userId());
            throw new BusinessException(HbErrorCode.MFA_UNAVAILABLE);
        }
    }

    private static String actor(LoginUser me) {
        return "u" + me.userId() + ":" + me.loginId();
    }

    private static String randomCode() {
        StringBuilder b = new StringBuilder(11);
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                b.append('-');
            }
            b.append(RECOVERY_CHARS.charAt(RANDOM.nextInt(RECOVERY_CHARS.length())));
        }
        return b.toString();
    }

    /** 대시 없이 정규화한 코드의 SHA-256(사용자 id:코드) */
    static String hash(long userId, String code) {
        try {
            String norm = code.replace("-", "").toUpperCase(Locale.ROOT);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((userId + ":" + norm).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.time.Instant toInstant(Object v) {
        return v instanceof java.sql.Timestamp ts ? ts.toInstant() : ((OffsetDateTime) v).toInstant();
    }
}
