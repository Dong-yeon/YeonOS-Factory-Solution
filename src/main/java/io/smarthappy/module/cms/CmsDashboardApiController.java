package io.smarthappy.module.cms;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.cms.CollectorDtos.PlantRow;
import io.smarthappy.module.cms.DashboardService.Dashboard;
import io.smarthappy.module.cms.FailAnalysisService.FailMwos;
import io.smarthappy.module.cms.FailAnalysisService.FailReport;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/**
 * 설비·생산 대시보드 API (docs/51 C2, docs/33 16.4). 조회만. 권한 = 대시보드 화면(cms-dashboard) SEARCH — 화면 권한과 같아야
 * 화면만 받은 사람이 빈 화면을 보지 않는다(2026-11-09 cms-kpi 에서 옮김, 그 전에는 이 API 를 쓰는 화면이 없었다).
 * 공장 목록은 /api/cms/kpi/plants(cms-kpi) 대신 화면이 /api/cms/dashboard/plants 로. 드릴다운은 화면이 기존 목록 화면(각자 권한)을 같은 조건으로 연다
 */
@RestController
@RequiredArgsConstructor
public class CmsDashboardApiController {

    private final DashboardService dashboard;
    private final FailAnalysisService fails;
    private final StateTimelineService timeline;

    @GetMapping("/api/cms/dashboard")
    @ProgAuth(prog = DashboardService.PROG, action = Action.SEARCH)
    public ApiResponse<Dashboard> load(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String basis) {
        return ApiResponse.ok(dashboard.load(plantCd, from, to, keyword, basis));
    }

    /** 공장 목록 — 설비 KPI 화면과 같은 목록(권한만 이 화면) */
    @GetMapping("/api/cms/dashboard/plants")
    @ProgAuth(prog = DashboardService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> plants() {
        return ApiResponse.ok(timeline.plants());
    }

    /** 고장 분석(G1·G2) — 파레토·설비별·담당별 분해·반복 고장 */
    @GetMapping("/api/cms/dashboard/failures")
    @ProgAuth(prog = DashboardService.PROG, action = Action.SEARCH)
    public ApiResponse<FailReport> failures(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String classCd, @RequestParam(required = false) String axis) {
        return ApiResponse.ok(fails.load(plantCd, from, to, keyword, classCd, axis));
    }

    /** 고장 분석 드릴다운 — 막대 하나의 작업지시(같은 조건 + 축 키, '-' = 값 없음) */
    @GetMapping("/api/cms/dashboard/failures/mwos")
    @ProgAuth(prog = DashboardService.PROG, action = Action.SEARCH)
    public ApiResponse<FailMwos> failureMwos(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String classCd, @RequestParam(required = false) String axis, @RequestParam(required = false) String key) {
        return ApiResponse.ok(fails.mwos(plantCd, from, to, keyword, classCd, axis, key));
    }
}
