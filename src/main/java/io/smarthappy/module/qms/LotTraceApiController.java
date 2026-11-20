package io.smarthappy.module.qms;

import java.io.IOException;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.qms.LotTraceDtos.TraceForm;
import io.smarthappy.module.qms.LotTraceDtos.TraceView;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** 출하 추적(리콜) API (docs/57 2.4) — 화면 qms-lot-trace: 조회 = SEARCH(LOT 번호 목록이 길어 POST, 읽기만), 엑셀 = EXCEL */
@RestController
@RequiredArgsConstructor
public class LotTraceApiController {

    private static final String T = "/api/qms/lot-trace";

    private final LotTraceService traces;

    @PostMapping(T)
    @ProgAuth(prog = LotTraceService.PROG, action = Action.SEARCH)
    public ApiResponse<TraceView> trace(@RequestBody TraceForm form) {
        return ApiResponse.ok(traces.trace(form));
    }

    @PostMapping(T + "/export")
    @ProgAuth(prog = LotTraceService.PROG, action = Action.EXCEL)
    public void export(@RequestBody TraceForm form, HttpServletResponse response) throws IOException {
        traces.export(form, response);
    }
}
