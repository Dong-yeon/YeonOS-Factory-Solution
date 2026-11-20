package io.smarthappy.module.production.erp;

import java.io.IOException;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.production.erp.ErpDtos.ExportForm;
import io.smarthappy.module.production.erp.ErpDtos.Preview;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** ERP 업로드용 엑셀 (화면 prd-erp-export, P2 7c) — 미리보기 조회 + 엑셀 */
@RestController
@RequiredArgsConstructor
public class ErpExportApiController {

    static final String BASE = "/api/production/erp-export";

    private final ErpExportService service;

    @GetMapping(BASE)
    @ProgAuth(prog = ErpExportService.PROG, action = Action.SEARCH)
    public ApiResponse<Preview> preview(@RequestParam(required = false) String kind,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(service.preview(kind, from, to));
    }

    @PostMapping(BASE + "/export")
    @ProgAuth(prog = ErpExportService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) ExportForm in, HttpServletResponse response) throws IOException {
        service.export(in, response);
    }
}
