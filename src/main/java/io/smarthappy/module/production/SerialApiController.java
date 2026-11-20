package io.smarthappy.module.production;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.production.SerialDtos.CompareView;
import io.smarthappy.module.production.SerialDtos.CreateForm;
import io.smarthappy.module.production.SerialDtos.InstallForm;
import io.smarthappy.module.production.SerialDtos.RemoveForm;
import io.smarthappy.module.production.SerialDtos.ReplaceForm;
import io.smarthappy.module.production.SerialDtos.SerialDetail;
import io.smarthappy.module.production.SerialDtos.SerialExport;
import io.smarthappy.module.production.SerialDtos.SerialWo;
import io.smarthappy.module.production.SerialDtos.TraceExport;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.grid.ColumnSpec;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** 장비 시리얼 구성(as-built) API — docs/77 5.1 */
@RestController
@RequiredArgsConstructor
public class SerialApiController {

    private static final String S = "/api/production/serials";
    private static final String T = "/api/production/serial-trace";

    private final SerialService service;
    private final MessageSource messages;

    // ---------- 구성 (prd-serial) ----------

    @GetMapping(S + "/grid")
    @ProgAuth(prog = SerialService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> grid(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String keyword, @RequestParam(required = false) String itemCd,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        var p = service.grid(from, to, keyword, itemCd, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(S + "/columns")
    @ProgAuth(prog = SerialService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        return view(service.serialColumns(), locale);
    }

    @PostMapping(S + "/export")
    @ProgAuth(prog = SerialService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) SerialExport req, HttpServletResponse response) throws IOException {
        service.export(req, response);
    }

    @GetMapping(S + "/wos")
    @ProgAuth(prog = SerialService.PROG, action = Action.SEARCH)
    public ApiResponse<List<SerialWo>> wos(@RequestParam(required = false) String woNo) {
        return ApiResponse.ok(service.wos(woNo));
    }

    @PostMapping(S)
    @ProgAuth(prog = SerialService.PROG, action = Action.SAVE)
    public ApiResponse<List<Long>> create(@RequestBody CreateForm form) {
        return ApiResponse.ok(service.create(form));
    }

    @GetMapping(S + "/{serialId}")
    @ProgAuth(prog = SerialService.PROG, action = Action.SEARCH)
    public ApiResponse<SerialDetail> detail(@PathVariable long serialId) {
        return ApiResponse.ok(service.detail(serialId));
    }

    @GetMapping(S + "/{serialId}/compare")
    @ProgAuth(prog = SerialService.PROG, action = Action.SEARCH)
    public ApiResponse<CompareView> compare(@PathVariable long serialId) {
        return ApiResponse.ok(service.compare(serialId));
    }

    @PostMapping(S + "/{serialId}/parts")
    @ProgAuth(prog = SerialService.PROG, action = Action.SAVE)
    public ApiResponse<Long> install(@PathVariable long serialId, @RequestBody InstallForm form) {
        return ApiResponse.ok(service.install(serialId, form));
    }

    @PostMapping(S + "/{serialId}/parts/{partId}/remove")
    @ProgAuth(prog = SerialService.PROG, action = Action.SAVE)
    public ApiResponse<Long> remove(@PathVariable long serialId, @PathVariable long partId, @RequestBody RemoveForm form) {
        return ApiResponse.ok(service.remove(serialId, partId, form));
    }

    @PostMapping(S + "/{serialId}/parts/{partId}/replace")
    @ProgAuth(prog = SerialService.PROG, action = Action.SAVE)
    public ApiResponse<Long> replace(@PathVariable long serialId, @PathVariable long partId, @RequestBody ReplaceForm form) {
        return ApiResponse.ok(service.replace(serialId, partId, form));
    }

    // ---------- 역추적 (prd-serial-trace) ----------

    @GetMapping(T + "/grid")
    @ProgAuth(prog = SerialService.TRACE_PROG, action = Action.SEARCH)
    public ApiResponse<Boolean> trace(@RequestParam(required = false) String lotNo, @RequestParam(required = false) String itemCd,
            @RequestParam(required = false) String partSerialNo, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        var p = service.trace(lotNo, itemCd, partSerialNo, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", p.truncated(), p.list(), p.total());   // data = 줄 상한에 걸림
    }

    @GetMapping(T + "/columns")
    @ProgAuth(prog = SerialService.TRACE_PROG, action = Action.SEARCH)
    public ApiResponse<Void> traceColumns(Locale locale) {
        return view(service.traceColumns(), locale);
    }

    @PostMapping(T + "/export")
    @ProgAuth(prog = SerialService.TRACE_PROG, action = Action.EXCEL)
    public void traceExport(@RequestBody(required = false) TraceExport req, HttpServletResponse response) throws IOException {
        service.exportTrace(req, response);
    }

    private ApiResponse<Void> view(List<ColumnSpec> cols, Locale locale) {
        List<Map<String, Object>> v = cols.stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(v, v.size());
    }
}
