package io.smarthappy.module.pop;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.workforce.WorkerService;
import io.smarthappy.module.pop.PopDtos.ActivateForm;
import io.smarthappy.module.pop.PopDtos.ApprovalForm;
import io.smarthappy.module.pop.PopDtos.ApprovalReply;
import io.smarthappy.module.pop.PopDtos.PopApproval;
import io.smarthappy.module.pop.PopDtos.BadgeForm;
import io.smarthappy.module.pop.PopDtos.BadgeResult;
import io.smarthappy.module.pop.PopDtos.KioskExitForm;
import io.smarthappy.module.pop.PopDtos.KioskExitReply;
import io.smarthappy.module.pop.PopDtos.KioskUnlockFailedForm;
import io.smarthappy.module.pop.PopDtos.PopWorker;
import io.smarthappy.module.pop.PopDtos.TerminalAuthRow;
import io.smarthappy.module.pop.PopDtos.WorkerAuthRow;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginAttemptLimiter;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * POP 단말 인증·작업자 배지 전환 (docs/25 4.6, 01 D4).
 *
 * - 활성화: 회사 코드 + 등록 코드 → 단말 키(32바이트) 발급. 등록 코드는 그 자리에서 지운다. 틀리면 IP 기준 시도 제한
 * - 매 요청: 쿠키 {@code 회사.단말id.키} → 그 회사 범위(RLS)에서 키 해시 비교(회사 넘는 조회 없음)
 * - 작업자: 배지(+공장 정책이면 PIN) → 단말 세션의 "지금 작업자". 비활동이면 작업자만 내려간다
 * - 감독자 승인(docs/25 4.37): 감독자 배지 + PIN → 단말 세션에 2분·한 번 쓰는 승인 토큰. 정정 서비스가 확인·원장에 남긴다
 */
@Service
public class PopAuthService {

    public static final String COOKIE = "HB_TERMINAL";
    /** 쿠키 수명(일) — 브라우저 상한 400일 */
    static final int COOKIE_DAYS = 400;
    static final String WORKER_ATTR = PopAuthService.class.getName() + ".worker";
    static final int PIN_LOCK_AT = 5;
    static final int PIN_LOCK_MINUTES = 15;
    /** 단말마다 1분에 틀린 배지 10번 → 1분 막음 */
    static final int BADGE_MAX_FAIL = 10;
    static final Duration BADGE_WINDOW = Duration.ofMinutes(1);
    static final String PIN_POLICY = "POP_PIN_REQUIRED";
    static final String IDLE_POLICY = "POP_WORKER_IDLE_MIN";
    static final String APPROVER_QUAL_POLICY = "POP_APPROVER_QUAL";
    static final String APPROVAL_ATTR = PopAuthService.class.getName() + ".approval";
    static final Duration APPROVAL_TTL = Duration.ofMinutes(2);
    /** 키오스크 빠져나온 뒤 화면이 다시 잠글 때까지(docs/25 4.52 — 정책으로 바꿀지는 사용자 질의) */
    static final Duration KIOSK_RELOCK = Duration.ofMinutes(10);
    static final Set<String> APPROVAL_ACTIONS = Set.of("RESULT_CORRECT", "PARAM_CORRECT");
    /** 풀기 실패 알림 사유(KS9) — 늦은 응답 / 기기가 kiosk(false) 거절 */
    static final Set<String> UNLOCK_FAIL_REASONS = Set.of("stale", "refused");
    /** exitRef 모양 = {@link #exitRef()} 가 만드는 base64url 22자 */
    static final Pattern EXIT_REF = Pattern.compile("^[A-Za-z0-9_-]{22}$");
    /** 같은 exitRef 두 번째 알림은 아무것도 하지 않는다 — sys_inbox 소비자 */
    static final String UNLOCK_FAIL_CONSUMER = "POP_KIOSK_UNLOCK_FAIL";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_THROTTLE_KEYS = 10_000;

    private final PopMapper mapper;
    private final TenantRunner runner;
    private final LoginAttemptLimiter limiter;
    private final AccessLogService accessLog;
    private final PolicyService policies;
    private final PasswordEncoder passwordEncoder;
    private final BizClock bizClock;
    private final InboxService inbox;
    private final Map<Long, Window> badgeFails = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    public PopAuthService(PopMapper mapper, TenantRunner runner, LoginAttemptLimiter limiter, AccessLogService accessLog,
            PolicyService policies, PasswordEncoder passwordEncoder, BizClock bizClock, InboxService inbox) {
        this.mapper = mapper;
        this.runner = runner;
        this.limiter = limiter;
        this.accessLog = accessLog;
        this.policies = policies;
        this.passwordEncoder = passwordEncoder;
        this.bizClock = bizClock;
        this.inbox = inbox;
    }

    /** 테스트용 시계 교체 (비활동·배지 시도 창) */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    private record Window(Instant until, int count) {
    }

    private record Issued(long terminalId, String terminalCd, String key) {
    }

    // ------------------------------------------------------------------ 활성화

    /** 등록 코드로 단말 키를 받는다 → 쿠키 값 {@code 회사.단말id.키} */
    public String activate(ActivateForm in, String ip) {
        String compCd = in == null || in.compCd() == null ? "" : in.compCd().strip().toUpperCase(Locale.ROOT);
        String code = in == null ? null : TerminalService.normalizeCode(in.code());
        if (!TenantContext.isValidCompCd(compCd)) {
            limiter.recordFailure(ip, compCd);
            throw new BusinessException(HbErrorCode.TERMINAL_ACTIVATION_FAILED);
        }
        if (limiter.isBlocked(ip, compCd)) {
            accessLog.record(AccessEvent.TERMINAL_DENIED, compCd, null, null, null, "ACTIVATE",
                    AccessLogService.Result.DENIED, "throttled");
            throw new BusinessException(HbErrorCode.TOO_MANY_REQUESTS);
        }
        Issued issued = code == null ? null : runner.callAs(compCd, "pop:activate", () -> {
            Long id = mapper.findByActivation(TerminalService.activationHash(code));
            if (id == null) {
                return null;
            }
            TerminalAuthRow row = mapper.findAuth(id);
            if (row == null || !usable(row)) {
                return null;
            }
            String key = newKey();
            mapper.activate(id, TerminalService.sha256(key));
            return new Issued(id, row.getTerminalCd(), key);
        });
        if (issued == null) {
            limiter.recordFailure(ip, compCd);
            accessLog.record(AccessEvent.TERMINAL_DENIED, compCd, null, null, null, "ACTIVATE",
                    AccessLogService.Result.FAIL, null);
            throw new BusinessException(HbErrorCode.TERMINAL_ACTIVATION_FAILED);
        }
        limiter.recordSuccess(ip, compCd);
        accessLog.record(AccessEvent.TERMINAL_ACTIVATED, compCd, null, "t" + issued.terminalId() + ":" + issued.terminalCd(),
                null, "ACTIVATE", AccessLogService.Result.OK, null);
        return compCd + "." + issued.terminalId() + "." + issued.key();
    }

    // ------------------------------------------------------------------ 매 요청

    /** 단말 쿠키 → 단말. 형식·키·폐기·회사·공장 중 하나라도 아니면 빈 값 (요청 범위 밖에서 부른다) */
    public Optional<TerminalPrincipal> authenticate(String cookieValue) {
        return authenticate(cookieValue, false);
    }

    /** 수집 장치(Bearer) — 유형 COLLECTOR 만. POP 쿠키 인증은 COLLECTOR 를 받지 않는다 */
    public Optional<TerminalPrincipal> authenticateDevice(String bearer) {
        return authenticate(bearer, true);
    }

    private Optional<TerminalPrincipal> authenticate(String cookieValue, boolean device) {
        if (cookieValue == null || cookieValue.length() > 200) {
            return Optional.empty();
        }
        String[] parts = cookieValue.split("\\.", -1);
        if (parts.length != 3 || !TenantContext.isValidCompCd(parts[0]) || parts[2].isEmpty()) {
            return Optional.empty();
        }
        long id;
        try {
            id = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        String compCd = parts[0];
        byte[] presented = TerminalService.sha256(parts[2]).getBytes(StandardCharsets.US_ASCII);
        return Optional.ofNullable(runner.call(compCd, () -> {
            TerminalAuthRow row = mapper.findAuth(id);
            if (row == null || row.getKeyHash() == null
                    || !MessageDigest.isEqual(presented, row.getKeyHash().getBytes(StandardCharsets.US_ASCII))
                    || !usable(row) || TerminalService.COLLECTOR.equals(row.getTerminalType()) != device) {
                return null;
            }
            mapper.touch(id);
            return new TerminalPrincipal(compCd, row.getTerminalId(), row.getTerminalCd(), row.getTerminalNm(),
                    row.getPlantCd(), row.getNodeId(), row.getNodeCd(), row.getNodeNm(), row.getTerminalType());
        }));
    }

    /** 잘못된 쿠키 기록 — 회사 코드 형식이 맞을 때만(있는 회사만 실제로 남는다) */
    public void recordDenied(String cookieValue) {
        recordDenied(cookieValue, "COOKIE");
    }

    /** @param via COOKIE(POP 단말) / BEARER(수집 장치) */
    public void recordDenied(String value, String via) {
        String compCd = value == null ? null : value.split("\\.", 2)[0];
        accessLog.record(AccessEvent.TERMINAL_DENIED, compCd, null, null, null, via, AccessLogService.Result.DENIED, null);
    }

    private static boolean usable(TerminalAuthRow row) {
        return "ACTIVE".equals(row.getCompStatus()) && "Y".equals(row.getPlantUseYn());
    }

    // ------------------------------------------------------------------ 작업자

    /** 이 단말의 지금 작업자 (없거나 비활동으로 내려갔으면 null). 부를 때마다 비활동 시각을 늦춘다 */
    @Transactional(readOnly = true)
    public PopWorker currentWorker(HttpServletRequest request, TerminalPrincipal t) {
        HttpSession session = request.getSession(false);
        if (session == null || !(session.getAttribute(WORKER_ATTR) instanceof PopWorker w) || w.terminalId() != t.terminalId()) {
            return null;
        }
        long now = clock.millis();
        long idle = Duration.ofMinutes(policies.intValue(IDLE_POLICY)).toMillis();
        WorkerAuthRow row = now - w.lastSeen() > idle ? null : mapper.findWorker(w.workerId());
        if (row == null || !"Y".equals(row.getUseYn())) {
            session.removeAttribute(WORKER_ATTR);
            return null;
        }
        PopWorker touched = new PopWorker(w.terminalId(), w.workerId(), row.getWorkerCd(), row.getWorkerNm(), w.since(), now);
        session.setAttribute(WORKER_ATTR, touched);
        return touched;
    }

    public boolean pinRequired(String plantCd) {
        return "Y".equals(policies.value(PIN_POLICY, plantCd));
    }

    /**
     * 배지(+PIN) → 작업자 전환. PIN 이 필요한데 비어 있으면 needPin(작업자는 아직 안 바뀜).
     * 바깥 트랜잭션 없음 — 짧은 트랜잭션을 차례로(조회 → PIN 선점 → 연결 없이 비교 → 맞으면 실패 수 0, {@link #checkPin})
     */
    public BadgeResult badge(HttpServletRequest request, TerminalPrincipal t, BadgeForm in) {
        if (throttled(t.terminalId())) {
            log(t, AccessEvent.BADGE_THROTTLED, null, AccessLogService.Result.DENIED);
            throw new BusinessException(HbErrorCode.TOO_MANY_REQUESTS);
        }
        String badge = in == null || in.badge() == null ? "" : in.badge().strip();
        boolean wellFormed = WorkerService.BADGE.matcher(badge).matches();
        Found f = runner.call(t.compCd(), () -> new Found(
                wellFormed ? mapper.findWorkerByBadge(WorkerService.badgeHash(t.compCd(), badge)) : null, pinRequired(t.plantCd())));
        WorkerAuthRow w = f.worker();
        if (w == null || !"Y".equals(w.getUseYn())) {
            badgeFailed(t.terminalId());
            log(t, AccessEvent.BADGE_FAIL, null, AccessLogService.Result.FAIL);
            throw new BusinessException(HbErrorCode.BADGE_UNKNOWN);
        }
        if (f.pinRequired()) {
            if (w.getPinHash() == null) {
                log(t, AccessEvent.BADGE_FAIL, w.getWorkerCd(), AccessLogService.Result.DENIED);
                throw new BusinessException(HbErrorCode.PIN_NOT_SET);
            }
            String pin = in.pin() == null ? "" : in.pin().strip();
            if (pin.isEmpty()) {
                if (lockedNow(w)) {
                    log(t, AccessEvent.BADGE_FAIL, w.getWorkerCd(), AccessLogService.Result.DENIED);
                    throw new BusinessException(HbErrorCode.PIN_LOCKED, PIN_LOCK_MINUTES);
                }
                return new BadgeResult(true, w.getWorkerCd(), w.getWorkerNm());
            }
            Pin p = checkPin(t.compCd(), w, pin);
            switch (p.check()) {
                case LOCKED -> {
                    log(t, AccessEvent.BADGE_FAIL, w.getWorkerCd(), AccessLogService.Result.DENIED);
                    throw new BusinessException(HbErrorCode.PIN_LOCKED, PIN_LOCK_MINUTES);
                }
                case JUST_LOCKED -> {
                    log(t, AccessEvent.PIN_LOCKED, w.getWorkerCd(), AccessLogService.Result.DENIED);
                    throw new BusinessException(HbErrorCode.PIN_LOCKED, PIN_LOCK_MINUTES);
                }
                case WRONG -> {
                    log(t, AccessEvent.BADGE_FAIL, w.getWorkerCd(), AccessLogService.Result.FAIL);
                    throw new BusinessException(HbErrorCode.PIN_WRONG, p.left());
                }
                case OK -> {
                }
            }
        }
        // 작업자가 바뀌면 세션 ID 도 바꾼다(고정 방지)
        HttpSession session = request.getSession(false);
        if (session != null) {
            request.changeSessionId();
        }
        long now = clock.millis();
        HttpSession fresh = request.getSession(true);
        fresh.setAttribute(WORKER_ATTR, new PopWorker(t.terminalId(), w.getWorkerId(), w.getWorkerCd(), w.getWorkerNm(), now, now));
        fresh.removeAttribute(APPROVAL_ATTR);   // 앞 작업자가 받은 승인은 버린다
        badgeFails.remove(t.terminalId());
        log(t, AccessEvent.BADGE_OK, w.getWorkerCd(), AccessLogService.Result.OK);
        return new BadgeResult(false, w.getWorkerCd(), w.getWorkerNm());
    }

    /**
     * 감독자 승인 (docs/25 4.37) — 배지 + PIN 으로 감독자를 확인하고 단말 세션에 승인 토큰(2분, 그 동작·그 대상 하나)을 둔다.
     * 감독자 = 정책 POP_APPROVER_QUAL 자격을 오늘(공장 날짜) 유효하게 가진 사용 중 작업자, 지금 작업자 본인은 안 된다(P3).
     * 틀린·감독자 아닌 배지는 배지 로그인과 같은 단말 제한에, 틀린 PIN 은 그 작업자의 PIN 잠금(5회 → 15분)에 함께 센다(P4).
     * 바깥 트랜잭션 없음 — 배지 로그인과 같은 순서({@link #checkPin})
     */
    public ApprovalReply approve(HttpServletRequest request, TerminalPrincipal t, PopWorker me, ApprovalForm in) {
        if (me == null) {
            throw new BusinessException(HbErrorCode.POP_NO_WORKER);
        }
        if (in == null || in.action() == null || !APPROVAL_ACTIONS.contains(in.action())) {
            throw OrderService.fieldError("action", "CODE");
        }
        if (in.targetId() == null) {
            throw OrderService.fieldError("targetId", "REQUIRED");
        }
        WorkerAuthRow w = supervisor(t, in.badge(), in.pin(), me.workerId(), why -> approvalLog(t, why[0], why[1]));
        PopApproval a = new PopApproval(UUID.randomUUID().toString(), t.terminalId(), me.workerId(), w.getWorkerId(),
                w.getWorkerCd(), w.getWorkerNm(), in.action(), in.targetId(), clock.millis());
        request.getSession(true).setAttribute(APPROVAL_ATTR, a);   // 한 세션에 하나 — 새 승인이 앞 것을 덮는다
        return new ApprovalReply(a.token(), a.approverCd(), a.approverNm(), (int) APPROVAL_TTL.toSeconds());
    }

    /**
     * 키오스크 빠져나오기 (docs/25 4.52, docs/50 K4·K5·Q8) — 감독자 배지 + PIN 을 서버가 확인하고 접속 로그 POP_KIOSK_EXIT 를 남긴다.
     * 화면은 성공 응답을 받은 뒤에만 껍데기 다리 YeonShell.kiosk(false) 를 부른다(K6). 서버는 잠금 상태를 갖지 않는다 —
     * 다시 잠그기는 화면 몫(relockAfterSec 뒤·작업자 바뀜·홈 다시 열림), 승인 없이.
     * 승인(4.37)과 달리 지금 작업자가 없어도 되고, 지금 작업자가 감독자 본인이어도 된다(자기 동작을 승인하는 것이 아니라서).
     * 실패 카운터는 승인·배지 로그인과 같다(P4). 바깥 트랜잭션 없음({@link #checkPin})
     */
    public KioskExitReply kioskExit(TerminalPrincipal t, KioskExitForm in) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        WorkerAuthRow w = supervisor(t, in.badge(), in.pin(), null, why -> kioskLog(t, AccessEvent.POP_KIOSK_EXIT_FAIL, why[0],
                AccessLogService.Result.FAIL, "reason=" + why[1]));
        String ref = exitRef();
        kioskLog(t, AccessEvent.POP_KIOSK_EXIT, w.getWorkerCd(), AccessLogService.Result.OK, "ref=" + ref);
        return new KioskExitReply(w.getWorkerCd(), w.getWorkerNm(), (int) KIOSK_RELOCK.toSeconds(), ref);
    }

    /**
     * 키오스크 풀기 실패 알림 (KS9, docs/50 7.2·docs/25 4.54) — 서버는 POP_KIOSK_EXIT OK 로 적었는데 화면이 풀지 못함
     * (늦은 응답 stale · 기기가 kiosk(false) 거절 refused). 같은 단말이 최근 1시간 안에 받은 exitRef 만(다른 단말·다른 회사·모르는 값 = 404),
     * 같은 exitRef 두 번째는 아무것도 하지 않는다(sys_inbox 멱등 — 동시 두 번도 기록 하나). 기록 = 접속 로그 POP_KIOSK_UNLOCK_FAIL(FAIL).
     * 작업자 로그인은 필요 없다(빠져나오기와 같이 배지 화면에서도)
     */
    public void kioskUnlockFailed(TerminalPrincipal t, KioskUnlockFailedForm in) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.reason() == null || !UNLOCK_FAIL_REASONS.contains(in.reason())) {
            throw OrderService.fieldError("reason", "CODE");
        }
        if (in.exitRef() == null || in.exitRef().isEmpty()) {
            throw OrderService.fieldError("exitRef", "REQUIRED");
        }
        if (!EXIT_REF.matcher(in.exitRef()).matches()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);   // 우리가 준 모양이 아니면 찾을 것도 없다
        }
        String ref = in.exitRef();
        String exitDetail = runner.call(t.compCd(), () -> {
            String d = mapper.findKioskExitDetail(t.actor(), " ref=" + ref);
            if (d == null) {
                throw new BusinessException(HbErrorCode.NOT_FOUND);
            }
            return inbox.firstTime(UNLOCK_FAIL_CONSUMER, ref) ? d : null;
        });
        if (exitDetail != null) {
            String approver = exitDetail.startsWith("worker=") ? exitDetail.substring(7, exitDetail.indexOf(' ')) : null;
            kioskLog(t, AccessEvent.POP_KIOSK_UNLOCK_FAIL, approver, AccessLogService.Result.FAIL, "ref=" + ref + " reason=" + in.reason());
        }
    }

    /** 128비트 임의 값, base64url 22자 — 접속 로그 detail 에 그대로(사유 글이 아니라 서버가 만든 값) */
    static String exitRef() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private void kioskLog(TerminalPrincipal t, AccessEvent event, String workerCd, AccessLogService.Result result, String extra) {
        String detail = (workerCd == null ? "" : "worker=" + workerCd) + (extra == null ? "" : (workerCd == null ? "" : " ") + extra);
        accessLog.record(event, t.compCd(), null, t.actor(), "pop", "KIOSK", result, detail.isEmpty() ? null : detail);
    }

    /**
     * 감독자 확인(4.37 승인·4.52 키오스크 빠져나오기 공용) — 배지 + PIN, 정책 POP_APPROVER_QUAL 자격(오늘·공장 날짜), 사용 중 작업자.
     * excludeId = 감독자가 될 수 없는 작업자(승인의 요청 작업자 — P3), null 이면 없음. 실패는 fail({작업자 코드, 사유}) 로 기록한 뒤 예외.
     * 틀린·감독자 아닌 배지는 단말 배지 제한에, 틀린 PIN 은 그 작업자 PIN 잠금(5회 → 15분)에 함께 센다 — 어느 경로로도 따로 맞혀 보지 못하게(P4)
     */
    private WorkerAuthRow supervisor(TerminalPrincipal t, String badgeRaw, String pinRaw, Long excludeId,
            java.util.function.Consumer<String[]> fail) {
        if (throttled(t.terminalId())) {
            log(t, AccessEvent.BADGE_THROTTLED, null, AccessLogService.Result.DENIED);
            throw new BusinessException(HbErrorCode.TOO_MANY_REQUESTS);
        }
        String badge = badgeRaw == null ? "" : badgeRaw.strip();
        boolean wellFormed = WorkerService.BADGE.matcher(badge).matches();
        Approver f = runner.call(t.compCd(), () -> {
            WorkerAuthRow row = wellFormed ? mapper.findWorkerByBadge(WorkerService.badgeHash(t.compCd(), badge)) : null;
            if (row == null || !"Y".equals(row.getUseYn()) || (excludeId != null && row.getWorkerId() == excludeId)) {
                return new Approver(row, false);
            }
            String qual = policies.value(APPROVER_QUAL_POLICY, null);
            LocalDate today = LocalDate.ofInstant(clock.instant(), bizClock.zone(t.plantCd()));
            return new Approver(row, qual != null && !qual.isBlank() && mapper.approverQualified(row.getWorkerId(), qual.strip(), today));
        });
        WorkerAuthRow w = f.worker();
        if (w == null || !"Y".equals(w.getUseYn())) {
            badgeFailed(t.terminalId());
            fail.accept(new String[] {null, "badge"});
            throw new BusinessException(HbErrorCode.BADGE_UNKNOWN);
        }
        if (excludeId != null && w.getWorkerId() == excludeId) {
            fail.accept(new String[] {w.getWorkerCd(), "self"});
            throw new BusinessException(HbErrorCode.POP_APPROVER_SELF);
        }
        if (!f.qualified()) {
            badgeFailed(t.terminalId());
            fail.accept(new String[] {w.getWorkerCd(), "qual"});
            throw new BusinessException(HbErrorCode.POP_APPROVER_NOT_QUALIFIED);
        }
        if (w.getPinHash() == null) {
            fail.accept(new String[] {w.getWorkerCd(), "pin-not-set"});
            throw new BusinessException(HbErrorCode.PIN_NOT_SET);
        }
        String pin = pinRaw == null ? "" : pinRaw.strip();
        Pin p = checkPin(t.compCd(), w, pin);
        switch (p.check()) {
            case LOCKED -> {
                fail.accept(new String[] {w.getWorkerCd(), "locked"});
                throw new BusinessException(HbErrorCode.PIN_LOCKED, PIN_LOCK_MINUTES);
            }
            case JUST_LOCKED -> {
                fail.accept(new String[] {w.getWorkerCd(), "pin"});
                log(t, AccessEvent.PIN_LOCKED, w.getWorkerCd(), AccessLogService.Result.DENIED);
                throw new BusinessException(HbErrorCode.PIN_LOCKED, PIN_LOCK_MINUTES);
            }
            case WRONG -> {
                fail.accept(new String[] {w.getWorkerCd(), "pin"});
                throw new BusinessException(HbErrorCode.PIN_WRONG, p.left());
            }
            case OK -> {
            }
        }
        return w;
    }

    private record Found(WorkerAuthRow worker, boolean pinRequired) {
    }

    private record Approver(WorkerAuthRow worker, boolean qualified) {
    }

    private enum PinCheck { OK, WRONG, JUST_LOCKED, LOCKED }

    private record Pin(PinCheck check, int left) {
    }

    private boolean lockedNow(WorkerAuthRow w) {
        OffsetDateTime until = w.getPinLockedUntil();
        return until != null && until.toInstant().isAfter(clock.instant());
    }

    /**
     * PIN 확인 — 연결을 겹쳐 잡지 않는다(요청 하나가 연결 둘을 쥐면 단말 여러 대의 동시 시도로 풀이 바닥나 앱 전체가 멈춘다, docs/25 4.42).
     * 바깥 트랜잭션 없이 차례로: ① 시도 선점(조건부 UPDATE 한 문장 — 잠금 확인·증가가 원자적) 커밋 → ② bcrypt 비교(연결 없이)
     * → ③ 맞으면 실패 수 0 커밋(그사이 다른 선점이 끼어들었으면 자기 몫만 − 1, 잠금 그대로 — {@code pinOk}). 잠겨 있어도 비교는 한다(응답 시간으로 잠김을 알리지 않게).
     * runner.call 은 열린 트랜잭션 안에서 부르면 거부한다 — 바깥에 @Transactional 을 거는 회귀를 막는다
     */
    private Pin checkPin(String compCd, WorkerAuthRow w, String pin) {
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        Integer fails = runner.call(compCd, () -> mapper.pinClaim(w.getWorkerId(), PIN_LOCK_AT, now, now.plusMinutes(PIN_LOCK_MINUTES)));
        boolean wellFormed = WorkerService.PIN.matcher(pin).matches();
        boolean match = passwordEncoder.matches(wellFormed ? pin : "", w.getPinHash()) && wellFormed;
        if (fails == null) {
            return new Pin(PinCheck.LOCKED, 0);
        }
        if (match) {
            // 그사이 다른 단말의 틀린 시도가 선점됐으면 자기 몫만 되돌린다(남의 실패·그사이 걸린 잠금은 그대로 — QA O-1). 로그인은 성공
            runner.call(compCd, () -> mapper.pinOk(w.getWorkerId(), fails));
            return new Pin(PinCheck.OK, 0);
        }
        int left = PIN_LOCK_AT - fails;
        return left <= 0 ? new Pin(PinCheck.JUST_LOCKED, 0) : new Pin(PinCheck.WRONG, left);
    }

    /**
     * 정정 요청이 가져온 토큰과 맞는 승인(같은 단말·요청 작업자·동작·대상, 2분 안). 없거나 하나라도 다르면 null —
     * 토큰은 지우지 않는다(정정이 성공한 뒤 {@link #clearApproval}). 같은 대상의 승인 원장은 유일이라 두 번 쓰이지 않는다
     */
    public PopApproval approval(HttpServletRequest request, TerminalPrincipal t, PopWorker me, String action, long targetId, String token) {
        HttpSession session = request.getSession(false);
        if (session == null || token == null || me == null || !(session.getAttribute(APPROVAL_ATTR) instanceof PopApproval a)) {
            return null;
        }
        boolean fresh = clock.millis() - a.issuedAt() <= APPROVAL_TTL.toMillis();
        return fresh && a.token().equals(token) && a.terminalId() == t.terminalId() && a.requesterId() == me.workerId()
                && a.action().equals(action) && a.targetId() == targetId ? a : null;
    }

    public void clearApproval(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(APPROVAL_ATTR);
        }
    }

    public void workerLogout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(WORKER_ATTR);
            session.removeAttribute(APPROVAL_ATTR);
        }
    }

    // ------------------------------------------------------------------ 내부

    private void log(TerminalPrincipal t, AccessEvent event, String workerCd, AccessLogService.Result result) {
        accessLog.record(event, t.compCd(), null, t.actor(), "pop", "BADGE", result, workerCd == null ? null : "worker=" + workerCd);
    }

    private void approvalLog(TerminalPrincipal t, String workerCd, String why) {
        accessLog.record(AccessEvent.POP_APPROVAL_FAIL, t.compCd(), null, t.actor(), "pop", "APPROVAL", AccessLogService.Result.FAIL,
                (workerCd == null ? "" : "worker=" + workerCd + " ") + "reason=" + why);
    }

    private boolean throttled(long terminalId) {
        Window w = badgeFails.get(terminalId);
        return w != null && w.until().isAfter(clock.instant()) && w.count() >= BADGE_MAX_FAIL;
    }

    private void badgeFailed(long terminalId) {
        Instant now = clock.instant();
        if (badgeFails.size() > MAX_THROTTLE_KEYS) {
            for (Iterator<Window> it = badgeFails.values().iterator(); it.hasNext();) {
                if (!it.next().until().isAfter(now)) {
                    it.remove();
                }
            }
        }
        badgeFails.compute(terminalId, (k, w) -> w == null || !w.until().isAfter(now) ? new Window(now.plus(BADGE_WINDOW), 1)
                : new Window(w.until(), w.count() + 1));
    }

    static String newKey() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
