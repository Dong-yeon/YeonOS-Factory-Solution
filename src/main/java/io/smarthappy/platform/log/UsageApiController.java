package io.smarthappy.platform.log;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/** 회사 관리자의 활용 현황 (sys-usage) — 자기 회사 집계만 (RLS) */
@RestController
@RequiredArgsConstructor
public class UsageApiController {

    /** 이 화면의 기간 상한 — 실제 상한은 회사 정책 SEARCH_MAX_RANGE_DAYS 와 비교한 작은 값 (docs/16 I4) */
    static final int MAX_DAYS = UsageDailyService.MAX_RANGE_DAYS;

    private final UsageDailyService usage;
    private final DateRanges ranges;

    @GetMapping("/api/platform/usage")
    @ProgAuth(prog = "sys-usage", action = Action.SEARCH)
    public ApiResponse<Map<String, Object>> usage(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String comp = TenantContext.requireCompCd();
        ranges.check(from, to, MAX_DAYS);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", usage.summary(comp, from, to));
        out.put("programs", usage.topPrograms(comp, from, to));
        return ApiResponse.ok(out);
    }
}
