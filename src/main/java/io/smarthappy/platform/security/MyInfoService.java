package io.smarthappy.platform.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.config.HappyBootProperties;
import lombok.RequiredArgsConstructor;

/** 내 정보 변경 */
@Service
@RequiredArgsConstructor
public class MyInfoService {

    private final JdbcTemplate jdbc;
    private final UserAccountMapper accounts;
    private final PasswordEncoder encoder;
    private final PasswordPolicyService passwordPolicy;
    private final HappyBootProperties props;

    /** 언어 변경. 시스템 필드라 row_ver 는 올리지 않는다 (관리자 편집과 충돌하지 않게) */
    @Transactional
    public void changeLocale(long userId, String localeCd) {
        jdbc.update("update sys_user set locale_cd = ?, updated_at = now(), updated_by = 'SELF' where user_id = ?",
                localeCd, userId);
    }

    /**
     * 비밀번호 변경 (docs/05 D9).
     * - 현재 비밀번호가 틀리면 로그인 실패처럼 센다(세션을 가진 사람의 무차별 대입 방지). 잠긴 상태면 확인하지 않고 거부
     * - 새 비밀번호는 정책 검사 + 현재와 같으면 거부. 위반 항목은 모두 details 로
     * BusinessException 이 나도 실패 횟수 기록은 남아야 하므로 noRollbackFor
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public void changePassword(LoginUser user, String currentPassword, String newPassword) {
        LoginAccount state = accounts.findPasswordState(user.userId());
        if (state == null || state.locked() || currentPassword == null
                || currentPassword.getBytes(StandardCharsets.UTF_8).length > PasswordPolicyService.MAX_BYTES) {
            throw new BusinessException(HbErrorCode.PASSWORD_MISMATCH);
        }
        if (!encoder.matches(currentPassword, state.getPwdHash())) {
            accounts.recordLoginFailure(user.userId(), props.security().maxLoginFail(), props.security().lockMinutes());
            throw new BusinessException(HbErrorCode.PASSWORD_MISMATCH);
        }
        List<BusinessException.Detail> details = new ArrayList<>();
        for (PasswordPolicyService.Violation v : passwordPolicy.check(newPassword, user.compCd(), user.loginId())) {
            details.add(new BusinessException.Detail(v.messageKey(), v.args()));
        }
        if (details.isEmpty() && encoder.matches(newPassword, state.getPwdHash())) {
            details.add(new BusinessException.Detail("pwd.sameAsCurrent"));
        }
        if (!details.isEmpty()) {
            throw new BusinessException(HbErrorCode.PASSWORD_POLICY, details);
        }
        accounts.updatePassword(user.userId(), encoder.encode(newPassword));
    }
}
