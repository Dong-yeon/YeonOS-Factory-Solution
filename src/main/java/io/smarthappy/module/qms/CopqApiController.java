package io.smarthappy.module.qms;

import java.io.IOException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.qms.CopqDtos.CopqView;
import io.smarthappy.module.qms.CopqDtos.CostForm;
import io.smarthappy.module.qms.CopqDtos.ExportForm;
import io.smarthappy.module.qms.CopqDtos.RowVer;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** COPQ(품질 비용) API (docs/29_P3 29장) — 화면 qms-copq: 월별 집계 = SEARCH, 수동 비용 등록·수정 = SAVE, 삭제 = DELETE, 엑셀 = EXCEL(34장) */
@RestController
@RequiredArgsConstructor
public class CopqApiController {

    private static final String C = "/api/qms/copq";

    private final CopqService copq;
    private final CopqExportService exports;

    /** fromMonth·toMonth = YYYY-MM(양끝 포함, 24개월까지), 비우면 이번 달 */
    @GetMapping(C)
    @ProgAuth(prog = CopqService.PROG, action = Action.SEARCH)
    public ApiResponse<CopqView> summary(@RequestParam(required = false) String fromMonth, @RequestParam(required = false) String toMonth) {
        return ApiResponse.ok(copq.summary(fromMonth, toMonth));
    }

    /** 엑셀 — 조회와 같은 달 범위(본문 fromMonth·toMonth), 원장 줄 + 수동 행 한 시트 */
    @PostMapping(C + "/export")
    @ProgAuth(prog = CopqService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) ExportForm form, HttpServletResponse response) throws IOException {
        exports.export(form, response);
    }

    @PostMapping(C + "/costs")
    @ProgAuth(prog = CopqService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody CostForm form) {
        return ApiResponse.ok(copq.createCost(form));
    }

    @PutMapping(C + "/costs/{costId}")
    @ProgAuth(prog = CopqService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long costId, @RequestBody CostForm form) {
        copq.updateCost(costId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(C + "/costs/{costId}/delete")
    @ProgAuth(prog = CopqService.PROG, action = Action.DELETE)
    public ApiResponse<String> delete(@PathVariable long costId, @RequestBody RowVer body) {
        copq.deleteCost(costId, body == null ? null : body.rowVer());
        return ApiResponse.ok("OK");
    }
}
