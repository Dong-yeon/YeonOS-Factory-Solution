package io.smarthappy.module.wms.labeler;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.module.wms.labeler.LabelerDtos.Answer;
import io.smarthappy.module.wms.labeler.LabelerDtos.DeviceEventIn;
import io.smarthappy.module.wms.labeler.LabelerDtos.DeviceEventView;
import io.smarthappy.module.wms.labeler.LabelerDtos.PrefetchIn;
import io.smarthappy.module.wms.labeler.LabelerDtos.PrefetchItem;
import io.smarthappy.module.wms.labeler.LabelerDtos.ReleaseIn;
import io.smarthappy.module.wms.labeler.LabelerDtos.ReleaseView;
import io.smarthappy.module.wms.labeler.LabelerDtos.RequestIn;
import io.smarthappy.module.wms.labeler.LabelerDtos.ResultIn;
import io.smarthappy.module.wms.labeler.LabelerDtos.ResultView;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/**
 * 오토라벨러 장비 API (docs/65 21장) — `/ext/**` 체인(장치 Bearer 키 → 그 회사 범위). 장치 = 그 키를 쓰는 라벨러 하나.
 *
 * - POST /ext/v1/labeler/request  {requestId, scanned, ageMs?, answeredLocally?, action?, sscc?, reasonCd?} → 답 PRINT|SKIP|REJECT
 * - POST /ext/v1/labeler/result   {requestId, phase PRINTED|APPLIED|VERIFY|REJECTED, scanned?, reasonCd?} → MOVED|DUP|STALE
 * - POST /ext/v1/labeler/prefetch {max} (SaaS 엣지) → SSCC 를 붙인 박스 데이터
 * - POST /ext/v1/labeler/release  {boxCds} → 결번
 * - POST /ext/v1/labeler/event    {type ERROR|STATE|REJECT_FULL, code, detail} → 리젝트 만재면 지시(WAIT|ALARM|STOP)
 */
@RestController
@RequiredArgsConstructor
public class LabelerExtController {

    static final String BASE = "/ext/v1/labeler";

    private final LabelerDeviceService device;

    @PostMapping(BASE + "/request")
    public ApiResponse<Answer> request(@AuthenticationPrincipal TerminalPrincipal d, @RequestBody RequestIn in) {
        long start = System.nanoTime();
        return ApiResponse.ok(device.request(d, in, start));
    }

    @PostMapping(BASE + "/result")
    public ApiResponse<ResultView> result(@AuthenticationPrincipal TerminalPrincipal d, @RequestBody ResultIn in) {
        return ApiResponse.ok(device.result(d, in));
    }

    @PostMapping(BASE + "/prefetch")
    public ApiResponse<List<PrefetchItem>> prefetch(@AuthenticationPrincipal TerminalPrincipal d,
            @RequestBody(required = false) PrefetchIn in) {
        return ApiResponse.ok(device.prefetch(d, in == null ? null : in.max()));
    }

    @PostMapping(BASE + "/release")
    public ApiResponse<ReleaseView> release(@AuthenticationPrincipal TerminalPrincipal d, @RequestBody ReleaseIn in) {
        return ApiResponse.ok(device.release(d, in == null ? null : in.boxCds()));
    }

    @PostMapping(BASE + "/event")
    public ApiResponse<DeviceEventView> event(@AuthenticationPrincipal TerminalPrincipal d, @RequestBody DeviceEventIn in) {
        return ApiResponse.ok(device.deviceEvent(d, in));
    }
}
