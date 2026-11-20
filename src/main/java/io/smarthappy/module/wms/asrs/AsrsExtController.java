package io.smarthappy.module.wms.asrs;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.module.wms.asrs.AsrsDtos.ExchangeIn;
import io.smarthappy.module.wms.asrs.AsrsDtos.ExchangeView;
import io.smarthappy.module.wms.asrs.AsrsDtos.SnapIn;
import io.smarthappy.module.wms.asrs.AsrsDtos.SnapView;
import io.smarthappy.module.wms.asrs.AsrsDtos.WesRequest;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.ErrorMessages;
import io.smarthappy.platform.common.web.HbErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 자동창고 장비 API (docs/65 23장) — `/ext/**`(장치 Bearer 키 → 그 회사 범위). 장치 = 그 키를 쓰는 자동창고 하나(WES 어댑터 또는 엣지).
 *
 * - POST /ext/v1/wes/callback  WES 콜백(한 건 또는 {callbacks:[…]}) → 저장 후 200 건별 STORED·DUP·CONFLICT·INVALID. 과부하만 429
 * - POST /ext/v1/wes/requests/claim {max} (SaaS 엣지) → WES 로 넘길 요청
 * - POST /ext/v1/wes/exchange {scanned, direction IN} → GO / REJECT
 * - POST /ext/v1/wes/snapshot {snapKey, partNo, lastPart, containers} → RECEIVED·DUP·DONE(대사, docs/65 25.3)
 */
@RestController
@RequiredArgsConstructor
public class AsrsExtController {

    static final String BASE = "/ext/v1/wes";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AsrsDeviceService device;
    private final AsrsReconService recon;
    private final ErrorMessages errors;

    @PostMapping(value = BASE + "/callback", consumes = "application/json")
    public ResponseEntity<ApiResponse<List<String>>> callback(@AuthenticationPrincipal TerminalPrincipal d, @RequestBody String body,
            HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException e) {
            throw new BusinessException(HbErrorCode.BAD_REQUEST);
        }
        if (root == null || !root.isObject()) {
            throw new BusinessException(HbErrorCode.BAD_REQUEST);
        }
        try {
            return ResponseEntity.ok(ApiResponse.ok(device.callback(d, root)));
        } catch (AsrsDeviceService.Overloaded o) {
            response.setHeader("Retry-After", String.valueOf(o.retryAfterSec()));
            errors.write(request, response, HbErrorCode.TOO_MANY_REQUESTS);
            return null;
        }
    }

    public record ClaimIn(Integer max) {
    }

    @PostMapping(BASE + "/requests/claim")
    public ApiResponse<List<WesRequest>> claim(@AuthenticationPrincipal TerminalPrincipal d, @RequestBody(required = false) ClaimIn in) {
        return ApiResponse.ok(device.claim(d, in == null ? null : in.max()));
    }

    @PostMapping(BASE + "/exchange")
    public ApiResponse<ExchangeView> exchange(@AuthenticationPrincipal TerminalPrincipal d, @RequestBody ExchangeIn in) {
        return ApiResponse.ok(device.exchange(d, in));
    }

    @PostMapping(BASE + "/snapshot")
    public ApiResponse<SnapView> snapshot(@AuthenticationPrincipal TerminalPrincipal d, @RequestBody SnapIn in) {
        return ApiResponse.ok(recon.snapshot(d, in));
    }
}
