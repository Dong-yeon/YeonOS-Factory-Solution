package io.smarthappy.platform.portal;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.security.PortalBoundaryFilter;
import lombok.RequiredArgsConstructor;

/** 포털 셸 API (docs/42 7장 2) — 협력사 사용자 자신의 것만 */
@RestController
@RequiredArgsConstructor
public class PortalApiController {

    private final PortalService service;
    private final AccessLogService accessLog;

    public record PasswordRequest(String currentPassword, String newPassword) {
    }

    @PostMapping(PortalBoundaryFilter.API + "/me/password")
    @PortalApi
    public ApiResponse<String> changePassword(@RequestBody PasswordRequest req) {
        if (req == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        service.changePassword(req.currentPassword(), req.newPassword());
        accessLog.recordCurrent(AccessEvent.PASSWORD_CHANGED, "portal", null, AccessLogService.Result.OK, null);
        return ApiResponse.ok("OK");
    }
}
