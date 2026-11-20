package io.smarthappy.platform.auth;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import lombok.RequiredArgsConstructor;

/**
 * 프로그램 액션 권한 판정. 같은 요청 안에서는 프로그램별로 한 번만 조회한다(요청 속성 캐시).
 * 권한을 바꾸면 다음 요청부터 반영된다(세션에 권한을 저장하지 않음).
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private static final String CACHE_PREFIX = PermissionService.class.getName() + ".";

    private final AuthMapper mapper;

    /**
     * 로그인 사용자가 가진 액션. 로그인 사용자가 없으면 빈 집합.
     * 협력사 포털 사용자도 빈 집합 — 화면 권한(@ProgAuth)은 늘 거부, 포털은 @PortalApi 로만 (docs/42 3장 두 겹 막기)
     */
    @Transactional(readOnly = true)
    public Set<Action> actions(String progCd) {
        LoginUser user = CurrentUser.get().orElse(null);
        if (user == null || progCd == null || user.supplier()) {
            return EnumSet.noneOf(Action.class);
        }
        RequestAttributes req = RequestContextHolder.getRequestAttributes();
        String key = CACHE_PREFIX + progCd;
        if (req != null && req.getAttribute(key, RequestAttributes.SCOPE_REQUEST) instanceof Set<?> cached) {
            @SuppressWarnings("unchecked")
            Set<Action> hit = (Set<Action>) cached;
            return hit;
        }
        Set<Action> result = EnumSet.noneOf(Action.class);
        for (String a : mapper.findActions(user.userId(), progCd)) {
            result.add(Action.valueOf(a));
        }
        if (req != null) {
            req.setAttribute(key, result, RequestAttributes.SCOPE_REQUEST);
        }
        return result;
    }

    /** 권한이 없으면 403 NO_PERMISSION */
    public void require(String progCd, Action action) {
        if (!actions(progCd).contains(action)) {
            throw new BusinessException(HbErrorCode.NO_PERMISSION);
        }
    }
}
