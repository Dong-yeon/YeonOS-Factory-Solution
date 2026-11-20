package io.smarthappy.platform.ops;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.config.HappyBootProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * 운영자 인증 — 비밀번호 + TOTP(필수). 회사 사용자 로그인과 완전히 별개(05 D4).
 *
 * - 실패 응답은 하나(OPS_AUTH_FAILED): 없는 계정·틀린 비밀번호·잠김·틀린 코드를 구분해 알리지 않는다
 * - 비밀번호·코드 실패 합계 5회 → 15분 잠금. 확인 전에 시도를 조건부 UPDATE 로 선점(동시 요청으로 5회 초과 불가)
 * - 인증 경로는 바깥 트랜잭션 없이 짧은 트랜잭션을 차례로 — 요청당 DB 연결 하나(겹쳐 쥐면 동시 요청으로 풀이 바닥난다)
 * - TOTP 를 아직 등록하지 않았으면 로그인 전에 등록부터(비밀은 세션에만 두었다가 확인 코드가 맞으면 암호화 저장)
 * - 같은 코드 재사용 거부: 운영자별 마지막 사용 구간(인스턴스 메모리 — 다중 인스턴스면 30초 창 안에서 다른 인스턴스 재사용 가능, 운영 규모상 허용)
 */
@Slf4j
@Service
public class OpsAuthService {

    static final int MAX_FAIL = 5;
    static final int LOCK_MINUTES = 15;
    static final String ISSUER = "YeonOS Ops";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final HappyBootProperties props;
    private final OpsLogService opsLog;
    private final String dummyHash;
    private final Map<Long, Long> lastStep = new ConcurrentHashMap<>();
    /** 짧은 트랜잭션 하나씩 — 인증 경로는 바깥 트랜잭션 없이 이것을 차례로 연다(요청당 연결 하나) */
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private Clock clock = Clock.systemUTC();

    public OpsAuthService(JdbcTemplate jdbc, PasswordEncoder encoder, HappyBootProperties props, OpsLogService opsLog,
            org.springframework.transaction.PlatformTransactionManager tm) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(tm);
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.props = props;
        this.opsLog = opsLog;
        this.dummyHash = encoder.encode("ops-timing-equalizer");
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** 시험용 — 재기동처럼 메모리의 재사용 방지 기록을 비운다 */
    void forgetUsedSteps() {
        lastStep.clear();
    }

    public record Operator(long opId, String loginId, String opNm, boolean totpEnabled) {
    }

    /**
     * 1단계: 비밀번호. 맞으면 운영자(아직 로그인 아님 — MFA 남음).
     * 바깥 트랜잭션 없음(요청당 연결 하나, docs/36 14.4.1 ②와 같은 방식): ① 조회 ② 시도 선점(조건부 UPDATE 한 문장) ③ 비밀번호 비교는
     * 연결 없이 ④ 맞으면 선점분 되돌림 ⑤ 운영 기록. 동시 요청이 5회를 넘겨 추측하지 못하고, 느린 해시 비교 동안 연결을 쥐지 않는다.
     */
    public Operator checkPassword(String loginId, String password, String ip) {
        String id = loginId == null ? "" : loginId.strip().toLowerCase(java.util.Locale.ROOT);
        List<Map<String, Object>> rows = id.isEmpty() || id.length() > 50 ? List.of() : tx.execute(st -> jdbc.queryForList("""
                select op_id, login_id, op_nm, pwd_hash, totp_enabled_yn from sys_operator where login_id = ? and use_yn = 'Y'""",
                id));
        if (rows.isEmpty() || password == null || password.length() > 72) {
            encoder.matches("x", dummyHash);
            opsLog.record(id, "OPS_LOGIN", null, "FAIL", ip, "no account");
            throw failed();
        }
        Map<String, Object> r = rows.get(0);
        long opId = ((Number) r.get("op_id")).longValue();
        if (!claimAttempt(opId)) {
            encoder.matches(password, (String) r.get("pwd_hash"));   // 잠김도 같은 시간 — 잠김 여부를 응답 시간으로 알리지 않는다
            opsLog.record(id, "OPS_LOGIN", null, "DENIED", ip, "locked");
            throw failed();
        }
        if (!encoder.matches(password, (String) r.get("pwd_hash"))) {
            opsLog.record(id, "OPS_LOGIN", null, "FAIL", ip, "password");
            throw failed();
        }
        refundAttempt(opId);   // 비밀번호는 맞았다 — 실패 합계는 TOTP 까지 마쳐야 0 으로(예전과 같다)
        return new Operator(opId, (String) r.get("login_id"), (String) r.get("op_nm"), "Y".equals(r.get("totp_enabled_yn")));
    }

    /** 2단계: 등록된 TOTP 확인 → 로그인 완료. 바깥 트랜잭션 없음 — 선점 → 판정(연결 없이) → 성공 기록 → 운영 기록 */
    public Operator verifyTotp(long opId, String code, String ip) {
        List<Map<String, Object>> rows = tx.execute(st -> jdbc.queryForList("""
                select login_id, op_nm, totp_secret_enc, last_login_at from sys_operator
                 where op_id = ? and use_yn = 'Y' and totp_enabled_yn = 'Y'""", opId));
        if (rows.isEmpty()) {
            throw failed();
        }
        Map<String, Object> r = rows.get(0);
        String id = (String) r.get("login_id");
        if (!claimAttempt(opId)) {
            opsLog.record(id, "OPS_LOGIN", null, "DENIED", ip, "locked");
            throw failed();
        }
        String secret;
        try {
            secret = openSecret(opId, (String) r.get("totp_secret_enc"));
        } catch (IllegalStateException e) {
            // 다른 운영자 행에서 옮겨 온 봉인·바뀐 키·손상 — 구분하지 않고 일반 실패(선점한 1회는 실패로 남는다)
            opsLog.record(id, "OPS_LOGIN", null, "FAIL", ip, "secret");
            throw failed();
        }
        long step = Totp.verify(secret, code, clock, lastStep.getOrDefault(opId, dbFloor(r.get("last_login_at"))));
        if (step < 0) {
            opsLog.record(id, "OPS_LOGIN", null, "FAIL", ip, "totp");
            throw failed();
        }
        lastStep.put(opId, step);
        success(opId, id, ip);
        return new Operator(opId, id, (String) r.get("op_nm"), true);
    }

    /** TOTP 등록 시작 — 비밀은 반환만(호출하는 쪽이 세션에 둔다), DB 에는 아직 쓰지 않는다 */
    public String newEnrollSecret() {
        box(); // 키가 없으면 여기서 실패
        return Totp.newSecret();
    }

    public String otpauthUri(String loginId, String secret) {
        return Totp.otpauthUri(ISSUER, loginId, secret);
    }

    /** TOTP 등록 확인 — 코드가 맞으면 암호화해 저장하고 로그인 완료. 바깥 트랜잭션 없음(위와 같은 순서) */
    public Operator confirmEnroll(long opId, String secret, String code, String ip) {
        Map<String, Object> r = tx.execute(st -> jdbc.queryForMap("""
                select login_id, op_nm, totp_enabled_yn from sys_operator where op_id = ? and use_yn = 'Y'""", opId));
        String id = (String) r.get("login_id");
        if ("Y".equals(r.get("totp_enabled_yn"))) {
            // 이미 등록된 운영자가 세션 조작으로 재등록을 시도 — 기존 MFA 를 덮어쓰지 않는다
            opsLog.record(id, "OPS_MFA_ENROLL", null, "DENIED", ip, "already enrolled");
            throw failed();
        }
        if (!claimAttempt(opId)) {
            opsLog.record(id, "OPS_LOGIN", null, "DENIED", ip, "locked");
            throw failed();
        }
        long step = Totp.verify(secret, code, clock, -1);
        if (step < 0) {
            opsLog.record(id, "OPS_LOGIN", null, "FAIL", ip, "enroll");
            throw failed();
        }
        String sealed = box().seal(secret, aad(opId));
        // 동시 확인 두 건이면 한 건만 — 등록되지 않은 행만 바꾼다
        Integer n = tx.execute(st -> jdbc.update("""
                update sys_operator set totp_secret_enc = ?, totp_enabled_yn = 'Y', updated_at = now()
                 where op_id = ? and totp_enabled_yn = 'N'""", sealed, opId));
        if (n == null || n != 1) {
            opsLog.record(id, "OPS_MFA_ENROLL", null, "DENIED", ip, "already enrolled");
            throw failed();
        }
        lastStep.put(opId, step);
        opsLog.record(id, "OPS_MFA_ENROLL", null, "OK", ip, null);
        success(opId, id, ip);
        return new Operator(opId, id, (String) r.get("op_nm"), true);
    }

    /**
     * 시도 1회분을 실패로 먼저 센다 — 조건부 UPDATE 한 문장(행 잠금 + 잠금 확인 + 증가, 자기 트랜잭션으로 커밋).
     * 잠금이 걸려 있으면 0건 → false. 잠금이 끝난 행은 1부터(해제 직후 한 번 틀려 다시 잠기지 않게). 5번째가 잠금을 건다.
     */
    private boolean claimAttempt(long opId) {
        Integer n = tx.execute(st -> jdbc.update("""
                update sys_operator
                   set fail_cnt = case when locked_until is not null then 1 else fail_cnt + 1 end,
                       locked_until = case when (case when locked_until is not null then 1 else fail_cnt + 1 end) >= ?
                                           then now() + make_interval(mins => ?) else null end,
                       updated_at = now()
                 where op_id = ? and (locked_until is null or locked_until <= now())""", MAX_FAIL, LOCK_MINUTES, opId));
        return n != null && n == 1;
    }

    /** 맞은 시도의 선점분을 되돌린다 — 남는 실패 수가 5 미만이면 잠금도 푼다(그 사이 다른 시도가 건 잠금 포함, 실패 수가 기준) */
    private void refundAttempt(long opId) {
        tx.execute(st -> jdbc.update("""
                update sys_operator
                   set fail_cnt = greatest(fail_cnt - 1, 0),
                       locked_until = case when greatest(fail_cnt - 1, 0) >= ? then locked_until else null end
                 where op_id = ?""", MAX_FAIL, opId));
    }

    private void success(long opId, String id, String ip) {
        tx.execute(st -> jdbc.update("""
                update sys_operator set fail_cnt = 0, locked_until = null, last_login_at = now(), last_login_ip = ?,
                       updated_at = now()
                 where op_id = ?""", ip, opId));
        opsLog.record(id, "OPS_LOGIN", null, "OK", ip, null);
    }

    /** 봉인을 운영자 행에 묶는다 — DB 에서 다른 운영자 행으로 옮겨 넣으면 풀리지 않는다(회사 MFA 와 같은 방식, docs/67 F6) */
    static String aad(long opId) {
        return "sys_operator:" + opId;
    }

    /**
     * AAD 로 연다. 예전 봉인(AAD 없음)은 한 번 열리면 그 자리에서 AAD 로 다시 봉인한다(마이그레이션 없이 점진 전환 —
     * 값이 그 사이 바뀌었으면 덮어쓰지 않는다). 둘 다 안 되면 키가 바뀌었거나 값이 손상된 것.
     */
    private String openSecret(long opId, String sealed) {
        SecretBox box = box();
        try {
            return box.open(sealed, aad(opId));
        } catch (IllegalStateException bound) {
            String plain = box.open(sealed);   // 옛 형식 — 실패하면 여기서 예외
            String resealed = box.seal(plain, aad(opId));
            tx.execute(st -> jdbc.update("""
                    update sys_operator set totp_secret_enc = ?, updated_at = now() where op_id = ? and totp_secret_enc = ?""",
                    resealed, opId, sealed));
            return plain;
        }
    }

    /**
     * 재사용 방지 기록이 메모리에 없을 때(재기동 뒤·다른 인스턴스) — 마지막 로그인 시각의 구간 + 허용 오차까지는 이미 쓴 것으로 본다.
     * 그 로그인에 쓰인 코드(앞뒤 1구간 안)를 재기동 직후 다시 대지 못하게(docs/67 F6). 대신 재기동 직후 1분 안 재로그인은 다음 코드를 기다린다.
     */
    static long dbFloor(Object lastLoginAt) {
        java.time.Instant at = switch (lastLoginAt) {
            case null -> null;
            case java.sql.Timestamp t -> t.toInstant();
            case java.time.OffsetDateTime o -> o.toInstant();
            case java.time.Instant i -> i;
            default -> null;
        };
        return at == null ? -1L : at.getEpochSecond() / Totp.PERIOD_SECONDS + Totp.WINDOW;
    }

    private SecretBox box() {
        if (!props.ops().enabled()) {
            throw new BusinessException(HbErrorCode.OPS_DISABLED);
        }
        return new SecretBox(props.ops().secretKey());
    }

    private static BusinessException failed() {
        return new BusinessException(HbErrorCode.OPS_AUTH_FAILED);
    }
}
