package io.smarthappy.module.production;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.production.LotReportDtos.LotReport;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/** LOT 역추적 보고서 (화면 prd-lot-report, P2 7b) — 조회만 */
@RestController
@RequiredArgsConstructor
public class LotReportApiController {

    private final LotReportService service;

    @GetMapping("/api/production/lot-report")
    @ProgAuth(prog = LotReportService.PROG, action = Action.SEARCH)
    public ApiResponse<LotReport> report(@RequestParam(required = false) String lotNo) {
        return ApiResponse.ok(service.report(lotNo));
    }
}
