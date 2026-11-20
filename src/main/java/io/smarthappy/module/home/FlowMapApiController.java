package io.smarthappy.module.home;

import java.util.List;
import java.util.Locale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/** 업무 흐름 지도 API (home-flow-map, docs/46 12장) — 조회만 */
@RestController
@RequiredArgsConstructor
public class FlowMapApiController {

    private final FlowMapService service;

    /** 사용 중 화면 전부의 이름 + 내가 열 수 있는지(조회 권한) */
    @GetMapping("/api/home/flow-map/screens")
    @ProgAuth(prog = FlowMapService.PROG, action = Action.SEARCH)
    public ApiResponse<List<FlowMapService.Screen>> screens(Locale locale) {
        return ApiResponse.ok(service.screens(locale));
    }
}
