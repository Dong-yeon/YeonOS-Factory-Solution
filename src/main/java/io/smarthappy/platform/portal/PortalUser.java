package io.smarthappy.platform.portal;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;

/**
 * 지금 요청의 협력사 포털 사용자 (docs/42 4장). 포털 서비스는 소속 협력사를 여기서만 얻는다 — 요청 값·메서드 인자로 받지 않는다.
 * 회사 범위(compCd)는 TenantRequestFilter 가 이미 연 그대로(RLS), 협력사 범위(custId)는 포털 질의마다 조건으로 건다.
 */
public record PortalUser(long userId, String compCd, String loginId, long custId) {

    /** 협력사 사용자가 아니면(회사 사용자·로그인 없음) 403 PORTAL_FORBIDDEN */
    public static PortalUser current() {
        LoginUser u = CurrentUser.get().orElse(null);
        if (u == null || !u.supplier() || u.custId() == null) {
            throw new BusinessException(HbErrorCode.PORTAL_FORBIDDEN);
        }
        return new PortalUser(u.userId(), u.compCd(), u.loginId(), u.custId());
    }
}
