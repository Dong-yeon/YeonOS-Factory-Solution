package io.smarthappy.platform.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.common.web.ApiResponse;

/** 화면 JS 가 현재 사용자·회사를 확인하는 API */
@RestController
@lombok.RequiredArgsConstructor
public class SessionApiController {

    private final io.smarthappy.platform.time.BizClock clock;

    /** timeZone = 회사 시간대 — 화면은 일시를 브라우저 시간대가 아니라 이것으로 표시한다(docs/09 4.8) */
    public record SessionInfo(String compCd, String loginId, String userNm, String localeCd, boolean pwdChangeRequired,
            String timeZone) {
    }

    @GetMapping("/api/platform/session")
    @io.smarthappy.platform.auth.ProgAuthExempt(reason = "로그인 사용자 자신의 세션 정보")
    public ApiResponse<SessionInfo> session(@AuthenticationPrincipal LoginUser user) {
        return ApiResponse.ok(new SessionInfo(user.compCd(), user.loginId(), user.userNm(), user.localeCd(),
                user.pwdChangeRequired(), clock.zone().getId()));
    }
}
