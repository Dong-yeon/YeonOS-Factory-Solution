package io.smarthappy.module.admin;

import java.util.HashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 부여 상한 — 가진 권한보다 큰 권한은 줄 수 없다 (docs/36 4.1, 이상 사용 E).
 * 권한 집합 = "prog:ACTION" 글자 집합 + 관리자 여부(관리자 = 전부). 포함 검사 A ⊆ B:
 * A 가 관리자면 B 도 관리자여야 하고, B 가 관리자면 항상 참, 아니면 글자 집합 포함.
 * 회사 범위 트랜잭션 안에서만 부른다(서비스 @Transactional).
 */
@Component
@RequiredArgsConstructor
public class GrantGuard {

    private final AdminMapper mapper;
    private final AccessLogService accessLog;

    public record Perms(boolean admin, Set<String> keys) {

        public boolean within(Perms other) {
            if (other.admin) {
                return true;
            }
            return !admin && other.keys.containsAll(keys);
        }
    }

    public LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }

    /** 행위자 실효 권한 (사용 중 그룹만 — 지금 쓸 수 있는 것) */
    public Perms mine() {
        long id = me().userId();
        return new Perms(mapper.isActiveAdmin(id), new HashSet<>(mapper.effectiveGrants(id)));
    }

    /** 그룹이 가진 권한 (그룹 사용 여부 무관 — 다시 켜질 수 있다) */
    public Perms ofGroup(String authGrpCd, String adminYn) {
        return new Perms("Y".equals(adminYn), new HashSet<>(mapper.groupGrants(authGrpCd)));
    }

    /** 사용자가 가질 수 있는 권한 (소속 그룹 전부, 사용 여부 무관) */
    public Perms ofUser(long userId) {
        return new Perms(mapper.inAnyAdminGroup(userId), new HashSet<>(mapper.userGrants(userId)));
    }

    /** target ⊆ mine 이 아니면 403 GRANT_EXCEEDS + 보안 로그 */
    public void requireWithin(Perms target, Perms mine, String what) {
        if (!target.within(mine)) {
            deny(HbErrorCode.GRANT_EXCEEDS, what);
        }
    }

    public void deny(HbErrorCode code, String what) {
        accessLog.recordCurrent(AccessEvent.GRANT_DENIED, null, null, AccessLogService.Result.DENIED,
                code.name() + " " + what);
        throw new BusinessException(code);
    }

    /** 관리자 관련 변경 전: 회사 단위 잠금 + 지금 쓸 수 있는 관리자 수 */
    public int lockAndCountAdmins() {
        mapper.lockAdminChanges();
        return mapper.countUsableAdmins();
    }

    /** 변경 뒤: 관리자가 있었는데 0명이 되면 409 (트랜잭션째 롤백) */
    public void requireAdminLeft(int before) {
        if (before > 0 && mapper.countUsableAdmins() == 0) {
            throw new BusinessException(HbErrorCode.LAST_ADMIN);
        }
    }
}
