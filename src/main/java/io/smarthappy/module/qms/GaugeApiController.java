package io.smarthappy.module.qms;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.qms.GaugeDtos.CalibForm;
import io.smarthappy.module.qms.GaugeDtos.GaugeDetail;
import io.smarthappy.module.qms.GaugeDtos.GaugeExportRequest;
import io.smarthappy.module.qms.GaugeDtos.ProfileForm;
import io.smarthappy.module.qms.GaugeDtos.VoidRequest;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** 계측기 교정 API (P3 QMS Q2a, docs/29) — 교정 추가 = SAVE, 무효 = DELETE(사유 필수) */
@RestController
@RequiredArgsConstructor
public class GaugeApiController {

    private static final String G = "/api/qms/gauges";

    private final GaugeCalibService gauges;
    private final MessageSource messages;

    @GetMapping(G)
    @ProgAuth(prog = GaugeCalibService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> list(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) String gaugeClass, @RequestParam(required = false) String status,
            @RequestParam(required = false) Boolean withUnused, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total,
            HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        GaugeCalibService.Page p = gauges.list(keyword, gaugeClass, status, withUnused, page, size,
                sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(G + "/columns")
    @ProgAuth(prog = GaugeCalibService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = gauges.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(G + "/{assetId}")
    @ProgAuth(prog = GaugeCalibService.PROG, action = Action.SEARCH)
    public ApiResponse<GaugeDetail> detail(@PathVariable long assetId) {
        return ApiResponse.ok(gauges.detail(assetId));
    }

    @PutMapping(G + "/{assetId}")
    @ProgAuth(prog = GaugeCalibService.PROG, action = Action.SAVE)
    public ApiResponse<String> profile(@PathVariable long assetId, @RequestBody ProfileForm form) {
        gauges.updateProfile(assetId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(G + "/{assetId}/calibs")
    @ProgAuth(prog = GaugeCalibService.PROG, action = Action.SAVE)
    public ApiResponse<Long> addCalib(@PathVariable long assetId, @RequestBody CalibForm form) {
        return ApiResponse.ok(gauges.addCalib(assetId, form));
    }

    /** 교정 NG 영향 LOT (2부 Q9) */
    @GetMapping(G + "/{assetId}/calibs/{calibId}/impact")
    @ProgAuth(prog = GaugeCalibService.PROG, action = Action.SEARCH)
    public ApiResponse<io.smarthappy.module.qms.GaugeDtos.ImpactResult> impact(@PathVariable long assetId, @PathVariable long calibId) {
        return ApiResponse.ok(gauges.impact(assetId, calibId));
    }

    @PostMapping(G + "/calibs/{calibId}/void")
    @ProgAuth(prog = GaugeCalibService.PROG, action = Action.DELETE)
    public ApiResponse<String> voidCalib(@PathVariable long calibId, @RequestBody VoidRequest req) {
        gauges.voidCalib(calibId, req);
        return ApiResponse.ok("OK");
    }

    @PostMapping(G + "/export")
    @ProgAuth(prog = GaugeCalibService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) GaugeExportRequest req, HttpServletResponse response) throws IOException {
        gauges.export(req, response);
    }
}
