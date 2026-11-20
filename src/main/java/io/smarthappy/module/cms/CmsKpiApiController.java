package io.smarthappy.module.cms;

import java.io.IOException;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.cms.CollectorDtos.PlantRow;
import io.smarthappy.module.cms.KpiService.DayKpi;
import io.smarthappy.module.cms.KpiService.KpiExport;
import io.smarthappy.module.cms.KpiService.KpiReport;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** CMS 2부 C9 API — 설비 KPI (cms-kpi, docs/33 11.8). 조회만 */
@RestController
@RequiredArgsConstructor
public class CmsKpiApiController {

    private static final String BASE = "/api/cms/kpi";

    private final KpiService kpi;
    private final StateTimelineService timeline;

    @GetMapping(BASE + "/plants")
    @ProgAuth(prog = KpiService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> plants() {
        return ApiResponse.ok(timeline.plants());
    }

    @GetMapping(BASE)
    @ProgAuth(prog = KpiService.PROG, action = Action.SEARCH)
    public ApiResponse<KpiReport> report(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String basis) {
        return ApiResponse.ok(kpi.report(plantCd, from, to, keyword, basis));
    }

    /** 일별 추이(C11) — 공장 합계 또는 설비 하나, 최대 31일 */
    @GetMapping(BASE + "/daily")
    @ProgAuth(prog = KpiService.PROG, action = Action.SEARCH)
    public ApiResponse<List<DayKpi>> daily(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String equipCd,
            @RequestParam(required = false) String basis) {
        return ApiResponse.ok(kpi.daily(plantCd, from, to, equipCd, basis));
    }

    /** 엑셀(C11) — 화면 표와 같은 조건·계산 */
    @PostMapping(BASE + "/export")
    @ProgAuth(prog = KpiService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) KpiExport req, HttpServletResponse response) throws IOException {
        kpi.export(req, response);
    }
}
