package io.smarthappy.platform.security;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import io.smarthappy.platform.config.HappyBootProperties;
import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.log.AccessLogService.Result;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.tenant.TenantRunner;
import lombok.extern.slf4j.Slf4j;

/**
 * 회사코드 + 로그인 ID + 비밀번호 인증.
 *
 * 원칙
 * - 회사·사용자 존재 여부를 응답으로 알려주지 않는다: 없는 회사/사용자/틀린 비밀번호는 모두 같은 오류, 같은 비용(bcrypt 1회)
 * - 잠금·사용중지 안내는 비밀번호가 맞았을 때만 (계정 존재를 흘리지 않기 위해)
 * - 계정 조회는 로그인 폼의 회사 범위로 TenantRunner 안에서 (RLS 적용 상태)
 * - 설치형(onprem)은 폼의 회사코드를 무시하고 설정값을 쓴다
 * - 계정 잠금(연속 실패)은 locked_until 에 자동 해제, 그와 별개로 접속 주소 기준 시도 제한(LoginAttemptLimiter)
 */
@Slf4j
@Component
public class HappyAuthenticationProvider implements AuthenticationProvider {

    static final String ROLE_USER = "ROLE_USER";
    /** BCrypt 는 72바이트를 넘는 비밀번호를 거부한다(예외) → 그 전에 일반 실패로 처리 */
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final int MAX_LOGIN_ID_LENGTH = 50;

    private final TenantRunner runner;
    private final UserAccountMapper mapper;
    private final PasswordEncoder encoder;
    private final HappyBootProperties props;
    private final LoginAttemptLimiter limiter;
    private final PolicyService policies;
    private final AccessLogService accessLog;
    private final String dummyHash;

    public HappyAuthenticationProvider(TenantRunner runner, UserAccountMapper mapper, PasswordEncoder encoder,
            HappyBootProperties props, LoginAttemptLimiter limiter, PolicyService policies, AccessLogService accessLog) {
        this.runner = runner;
        this.mapper = mapper;
        this.encoder = encoder;
        this.props = props;
        this.limiter = limiter;
        this.policies = policies;
        this.accessLog = accessLog;
        this.dummyHash = encoder.encode("timing-equalizer-not-a-real-password");
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        LoginDetails details = authentication.getDetails() instanceof LoginDetails d ? d : new LoginDetails(null, null);
        String compCd = props.deploy().onprem() ? props.deploy().compCd() : normalizeCompCd(details.compCd());
        String ip = details.remoteAddr();

        // 공장 NAT: 회사가 등록한 신뢰 IP 면 IP 단독 한도를 완화 (docs/05 G14)
        boolean trusted = isTrusted(compCd, ip);
        // 접속 주소 기준 차단은 비밀번호 확인·DB 조회보다 먼저 (차단 중 시도는 실패로 다시 세지 않음)
        if (limiter.isBlocked(ip, compCd, trusted)) {
            log.info("로그인 차단(시도 과다) compCd={} ip={}", compCd, ip);
            accessLog.record(AccessEvent.LOGIN_THROTTLED, compCd, null, loginIdOf(authentication), null, null,
                    Result.DENIED, null);
            throw new LoginThrottledException();
        }
        try {
            Authentication result = doAuthenticate(authentication, details, compCd);
            // 그 계정의 실패만 지운다 — 다른 계정에 대한 시도 한도는 남긴다(docs/67 F5)
            limiter.recordSuccess(ip, compCd, loginIdOf(authentication));
            return result;
        } catch (AuthenticationException e) {
            limiter.recordFailure(ip, compCd, loginIdOf(authentication), trusted);
            throw e;
        }
    }

    private boolean isTrusted(String compCd, String ip) {
        if (!TenantContext.isValidCompCd(compCd) || ip == null) {
            return false;
        }
        return runner.call(compCd, () -> policies.ipList(Policies.LOGIN_TRUSTED_IPS).matches(ip));
    }

    private Authentication doAuthenticate(Authentication authentication, LoginDetails details, String compCd) {
        String loginId = authentication.getName() == null ? "" : authentication.getName().trim();
        String password = authentication.getCredentials() == null ? "" : authentication.getCredentials().toString();

        if (!TenantContext.isValidCompCd(compCd) || loginId.isEmpty() || loginId.length() > MAX_LOGIN_ID_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            burnTime();
            throw bad(compCd, loginId, null, details, "입력 형식");
        }

        LoginAccount account = runner.call(compCd, () -> mapper.findForLogin(compCd, loginId));
        if (account == null) {
            burnTime();
            throw bad(compCd, loginId, null, details, "계정 없음");
        }

        if (!encoder.matches(password, account.getPwdHash())) {
            if (account.usable() && !account.locked()) {
                boolean lockedNow = runner.call(compCd, () -> {
                    mapper.recordLoginFailure(account.getUserId(), props.security().maxLoginFail(),
                            props.security().lockMinutes());
                    return "Y".equals(mapper.findLockYn(account.getUserId()));
                });
                if (lockedNow) {
                    accessLog.record(AccessEvent.LOGIN_LOCKED, compCd, account.getUserId(), account.getLoginId(), null,
                            null, Result.FAIL, null);
                }
            }
            throw bad(compCd, loginId, account.getUserId(), details, "비밀번호 불일치");
        }

        if (!account.usable()) {
            log.info("로그인 거부(사용중지·회사 비활성) compCd={} loginId={}", compCd, loginId);
            accessLog.record(AccessEvent.LOGIN_DENIED, compCd, account.getUserId(), account.getLoginId(), null, null,
                    Result.DENIED, "inactive");
            throw new DisabledException("inactive");
        }
        if (account.locked()) {
            log.info("로그인 거부(잠금) compCd={} loginId={}", compCd, loginId);
            accessLog.record(AccessEvent.LOGIN_DENIED, compCd, account.getUserId(), account.getLoginId(), null, null,
                    Result.DENIED, "locked");
            throw new LockedException("locked");
        }

        runner.run(compCd, () -> mapper.recordLoginSuccess(account.getUserId()));
        LoginUser user = new LoginUser(account.getUserId(), account.getCompCd(), account.getLoginId(),
                account.getUserNm(), account.getLocaleCd(), "Y".equals(account.getPwdChangeReqYn()),
                account.getUserType(), account.getCustId());
        log.info("로그인 성공 compCd={} loginId={} ip={}", compCd, account.getLoginId(), details.remoteAddr());
        accessLog.record(AccessEvent.LOGIN_OK, compCd, account.getUserId(), account.getLoginId(), null, null, Result.OK,
                null);
        return UsernamePasswordAuthenticationToken.authenticated(user, null, List.of(new SimpleGrantedAuthority(ROLE_USER)));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    /** 사용자 편의: 앞뒤 공백 제거 + 대문자 (회사코드는 대문자 규칙) */
    static String normalizeCompCd(String raw) {
        return raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String loginIdOf(Authentication authentication) {
        return authentication.getName() == null ? null : authentication.getName().trim();
    }

    private void burnTime() {
        encoder.matches("timing-equalizer", dummyHash);
    }

    private BadCredentialsException bad(String compCd, String loginId, Long userId, LoginDetails d, String reason) {
        log.info("로그인 실패({}) compCd={} loginId={} ip={}", reason, compCd,
                loginId.length() > 50 ? loginId.substring(0, 50) + "…" : loginId, d.remoteAddr());
        accessLog.record(AccessEvent.LOGIN_FAIL, compCd, userId, loginId, null, null, Result.FAIL, reason);
        return new BadCredentialsException("bad");
    }
}
