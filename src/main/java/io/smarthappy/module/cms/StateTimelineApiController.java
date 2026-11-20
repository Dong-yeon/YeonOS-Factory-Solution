package io.smarthappy.module.cms;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.cms.CollectorDtos.PlantRow;
import io.smarthappy.module.cms.CollectorDtos.Timeline;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/** 설비 상태 타임라인 (사무 화면 cms-state) */
@RestController
@RequiredArgsConstructor
public class StateTimelineApiController {

    private static final String BASE = "/api/cms/state";

    private final StateTimelineService timeline;

    @GetMapping(BASE + "/plants")
    @ProgAuth(prog = StateTimelineService.PROG, action = Action.SEARCH)
    public ApiResponse<List<PlantRow>> plants() {
        return ApiResponse.ok(timeline.plants());
    }

    @GetMapping(BASE)
    @ProgAuth(prog = StateTimelineService.PROG, action = Action.SEARCH)
    public ApiResponse<Timeline> day(@RequestParam(required = false) String plantCd, @RequestParam(required = false) String date) {
        return ApiResponse.ok(timeline.day(plantCd, date));
    }
}
