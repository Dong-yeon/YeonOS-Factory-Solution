package io.smarthappy.platform.esign;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.security.MfaService;
import io.smarthappy.platform.tenant.TenantRunner;
import jakarta.servlet.http.HttpSession;

/**
 * 전자서명 의미·재인증 (docs/36 16장, docs/35 A14) — 2단계 토큰.
 * <ol>
 * <li>{@link #issue}: 비밀번호(+ MFA 를 쓰는 사람은 인증 코드)를 다시 확인하고 5분·한 번 쓰는 서명 토큰을 <b>세션에만</b> 둔다.
 *     바깥 트랜잭션 없음 — 짧은 조회 트랜잭션 → 연결 없이 비밀번호 비교 → MFA 확인(그 자체가 순차 트랜잭션) → 접속 로그.
 *     요청당 연결 하나(SKILL §5). 세션당 5회 틀리면 그 세션을 끊는다(실패 닫힘, ALERT).</li>
 * <li>{@link #consume}: 업무 API 가 <b>업무 트랜잭션 안에서</b> 토큰을 꺼내(한 번만) 사용자·의미·대상·만료를 확인하고 서명 원장 행.
 *     업무가 롤백되면 서명 행도 없고 토큰은 이미 쓴 것으로 남는다(다시 서명).</li>
 * </ol>
 * 서명을 보여 주는 일({@link #list})은 모듈의 상세 API 몫 — 대상 화면 권한을 모듈이 판정하므로 공통 조회 API 는 두지 않는다.
 */
@Service
public class ESignService {

    static final int MAX_FAIL = 5;
    static final long TOKEN_SECONDS = 300;
    static final int MAX_TOKENS = 5;
    static final String SESSION_TOKENS = ESignService.class.getName() + ".tokens";
    static final String SESSION_FAILS = ESignService.class.getName() + ".fails";
    private static final Pattern TARGET_TYPE = Pattern.compile("^[a-z][a-z0-9_]{1,49}$");
    private static final Pattern MEANING = Pattern.compile("^[A-Z][A-Z0-9_]{1,19}$");
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 발급 순서 — 같은 시각에 받은 토큰도 "가장 오래된 것" 을 가린다 */
    private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();

    public record IssueForm(String meaningCd, String targetType, Long targetId, String password, String code) {
    }

    public record Issued(String token, long expiresInSec, String authMethod) {
    }

    public record Signature(long esignId, long userId, String signerNm, String meaningCd, String contentHash,
            String authMethod, OffsetDateTime signedAt) {
    }

    /** 세션에 둔 서명 토큰 — 사용자·의미·대상에 묶인다 */
    record Pending(long userId, String compCd, String meaningCd, String targetType, long targetId, String authMethod,
            Instant expiresAt, long seq) implements java.io.Serializable {
    }

    private final JdbcTemplate jdbc;
    private final TenantRunner runner;
    private final PasswordEncoder encoder;
    private final MfaService mfa;
    private final AccessLogService accessLog;
    private Clock clock = Clock.systemUTC();

    public ESignService(JdbcTemplate jdbc, TenantRunner runner, PasswordEncoder encoder, MfaService mfa,
            AccessLogService accessLog) {
        this.jdbc = jdbc;
        this.runner = runner;
        this.encoder = encoder;
        this.mfa = mfa;
        this.accessLog = accessLog;
    }

    /** 테스트용 시계 교체 */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    private record Me(String pwdHash, boolean mfaEnrolled, boolean meaningOk) {
    }

    /** 재인증 → 서명 토큰. 바깥 트랜잭션 없음(열린 트랜잭션 안에서 부르면 runner.call 이 거부) */
    public Issued issue(LoginUser me, HttpSession session, IssueForm f) {
        if (session == null || f == null) {
            throw new BusinessException(HbErrorCode.ESIGN_TOKEN);
        }
        String meaning = f.meaningCd() == null ? "" : f.meaningCd().strip();
        String type = f.targetType() == null ? "" : f.targetType().strip();
        if (!MEANING.matcher(meaning).matches() || !TARGET_TYPE.matcher(type).matches() || f.targetId() == null
                || f.targetId() <= 0) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        // ① 짧은 조회 트랜잭션: 비밀번호 해시·MFA 사용·의미 코드
        Me m = runner.call(me.compCd(), () -> jdbc.queryForObject("""
                select u.pwd_hash,
                       exists (select 1 from sys_user_mfa f where f.user_id = u.user_id and f.enabled_yn = 'Y'),
                       exists (select 1 from sys_code c where c.grp_cd = 'ESIGN_MEANING' and c.code = ? and c.use_yn = 'Y')
                  from sys_user u where u.user_id = ?""",
                (rs, i) -> new Me(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3)), meaning, me.userId()));
        if (m == null || !m.meaningOk()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        // ② 시도 1회분을 먼저 센다(같은 세션의 동시 요청이 5회를 넘지 못하게) → 연결 없이 비밀번호 비교
        AtomicInteger fails = fails(session);
        int attempt = fails.incrementAndGet();
        if (attempt > MAX_FAIL) {
            lockOut(session, type, f.targetId());
        }
        String pw = f.password() == null ? "" : f.password();
        if (pw.length() > 72 || !encoder.matches(pw, m.pwdHash())) {
            if (attempt >= MAX_FAIL) {
                lockOut(session, type, f.targetId());
            }
            accessLog.recordCurrent(AccessEvent.ESIGN_FAIL, null, null, AccessLogService.Result.FAIL,
                    type + ":" + f.targetId());
            throw new BusinessException(HbErrorCode.ESIGN_INVALID, MAX_FAIL - attempt);
        }
        fails.set(0);
        // ③ MFA 를 쓰는 사람은 인증 코드도(복구 코드 불가 — 실패 수·잠금은 MFA 규칙)
        String method = "PWD";
        if (m.mfaEnrolled()) {
            if (f.code() == null || f.code().isBlank()) {
                throw new BusinessException(HbErrorCode.ESIGN_MFA_REQUIRED);
            }
            mfa.confirm(me, f.code());
            method = "PWD_TOTP";
        }
        // ④ 토큰 — 세션에만, 만료된 것 정리, 최대 5개(넘으면 가장 오래된 것 하나만 — 여러 탭의 앞 대화상자를 살린다, QA O-2)
        Map<String, Pending> tokens = tokens(session);
        Instant now = clock.instant();
        tokens.values().removeIf(p -> !p.expiresAt().isAfter(now));
        while (tokens.size() >= MAX_TOKENS) {
            tokens.entrySet().stream().min(java.util.Map.Entry.comparingByValue(
                    java.util.Comparator.comparingLong(Pending::seq))).ifPresent(e -> tokens.remove(e.getKey()));
        }
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        tokens.put(token, new Pending(me.userId(), me.compCd(), meaning, type, f.targetId(), method,
                now.plusSeconds(TOKEN_SECONDS), SEQ.incrementAndGet()));
        return new Issued(token, TOKEN_SECONDS, method);
    }

    /** 세션을 끊는다 — 재인증을 5번 틀린 세션은 자리를 비운 화면·탈취로 본다 */
    private void lockOut(HttpSession session, String type, long targetId) {
        accessLog.recordCurrent(AccessEvent.ESIGN_LOCKED, null, null, AccessLogService.Result.DENIED, type + ":" + targetId);
        try {
            session.invalidate();
        } catch (IllegalStateException ignored) {
            // 이미 무효
        }
        throw new BusinessException(HbErrorCode.UNAUTHENTICATED);
    }

    /**
     * 업무 트랜잭션 안에서 서명 토큰을 쓴다(한 번만) → 서명 원장 행 id.
     * 토큰이 없거나 만료·다른 사람·다른 회사·다른 의미·다른 대상이면 403 ESIGN_TOKEN(구분하지 않는다).
     * @param contentHash {@link #hash} 로 만든 내용 해시(모듈이 정한 정규 글자)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long consume(HttpSession session, String token, String meaningCd, String targetType, long targetId,
            String contentHash) {
        if (contentHash == null || !HASH.matcher(contentHash).matches()) {
            throw new IllegalArgumentException("content hash must be lowercase SHA-256 hex");
        }
        LoginUser me = CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
        Pending p = null;
        if (session != null && token != null) {
            try {
                p = tokens(session).remove(token);
            } catch (BusinessException e) {
                p = null;   // 끊긴 세션 — 토큰 없음과 같다
            }
        }
        if (p == null || p.userId() != me.userId() || !p.compCd().equals(me.compCd()) || !p.meaningCd().equals(meaningCd)
                || !p.targetType().equals(targetType) || p.targetId() != targetId || !p.expiresAt().isAfter(clock.instant())) {
            throw new BusinessException(HbErrorCode.ESIGN_TOKEN);
        }
        Pending ok = p;
        return jdbc.queryForObject("""
                insert into sys_esign (comp_cd, user_id, signer_nm, meaning_cd, target_type, target_id, content_hash, auth_method)
                select hb_current_comp(), u.user_id, u.user_nm, ?, ?, ?, ?, ? from sys_user u where u.user_id = ?
                returning esign_id""", Long.class, ok.meaningCd(), ok.targetType(), ok.targetId(), contentHash,
                ok.authMethod(), me.userId());
    }

    /** 대상의 서명(오래된 것부터) — 모듈 상세 API 가 싣는다. 지금 내용 해시와 다르면 "서명 뒤 바뀜" 으로 보여 준다 */
    @Transactional(readOnly = true)
    public List<Signature> list(String targetType, long targetId) {
        return jdbc.query("""
                select esign_id, user_id, signer_nm, meaning_cd, content_hash, auth_method, signed_at
                  from sys_esign where target_type = ? and target_id = ? order by signed_at, esign_id""",
                (rs, i) -> new Signature(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getObject(7, OffsetDateTime.class)), targetType, targetId);
    }

    /** 정규 글자의 SHA-256(소문자 16진) — 모듈은 서명할 내용을 정해진 순서의 글자로 만들어 넘긴다 */
    public static String hash(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((canonical == null ? "" : canonical).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 끊긴 세션(같은 세션의 동시 요청이 이미 끊었음)은 401 — 실패 닫힘 */
    @SuppressWarnings("unchecked")
    private static Map<String, Pending> tokens(HttpSession session) {
        synchronized (ESignService.class) {
            try {
                return tokensOf(session);
            } catch (IllegalStateException e) {
                throw new BusinessException(HbErrorCode.UNAUTHENTICATED);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Pending> tokensOf(HttpSession session) {
        {
            Object o = session.getAttribute(SESSION_TOKENS);
            if (o instanceof Map<?, ?> map) {
                return (Map<String, Pending>) map;
            }
            Map<String, Pending> created = new ConcurrentHashMap<>();
            session.setAttribute(SESSION_TOKENS, created);
            return created;
        }
    }

    private static AtomicInteger fails(HttpSession session) {
        synchronized (ESignService.class) {
            try {
                return failsOf(session);
            } catch (IllegalStateException e) {
                throw new BusinessException(HbErrorCode.UNAUTHENTICATED);
            }
        }
    }

    private static AtomicInteger failsOf(HttpSession session) {
        {
            Object o = session.getAttribute(SESSION_FAILS);
            if (o instanceof AtomicInteger a) {
                return a;
            }
            AtomicInteger created = new AtomicInteger();
            session.setAttribute(SESSION_FAILS, created);
            return created;
        }
    }
}
