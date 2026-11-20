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

import io.smarthappy.module.qms.MsaDtos.ActionForm;
import io.smarthappy.module.qms.MsaDtos.Choice;
import io.smarthappy.module.qms.MsaDtos.StudyDetail;
import io.smarthappy.module.qms.MsaDtos.StudyExportRequest;
import io.smarthappy.module.qms.MsaDtos.StudyForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** MSA GRR 연구 API (P3 QMS Q2b, docs/29) — 확정 = APPROVE, 취소 = SAVE */
@RestController
@RequiredArgsConstructor
public class MsaApiController {

    private static final String M = "/api/qms/msa";

    private final MsaService msa;
    private final MessageSource messages;

    @GetMapping(M)
    @ProgAuth(prog = MsaService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> list(@RequestParam(required = false) String keyword, @RequestParam(required = false) String method,
            @RequestParam(required = false) String status, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        MsaService.Page p = msa.list(keyword, method, status, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(M + "/columns")
    @ProgAuth(prog = MsaService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = msa.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    @GetMapping(M + "/gauges")
    @ProgAuth(prog = MsaService.PROG, action = Action.SEARCH)
    public ApiResponse<List<Choice>> gauges() {
        return ApiResponse.ok(msa.gauges());
    }

    @GetMapping(M + "/chars")
    @ProgAuth(prog = MsaService.PROG, action = Action.SEARCH)
    public ApiResponse<List<Choice>> chars() {
        return ApiResponse.ok(msa.chars());
    }

    @GetMapping(M + "/{id}")
    @ProgAuth(prog = MsaService.PROG, action = Action.SEARCH)
    public ApiResponse<StudyDetail> detail(@PathVariable long id) {
        return ApiResponse.ok(msa.detail(id));
    }

    @PostMapping(M)
    @ProgAuth(prog = MsaService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody StudyForm form) {
        return ApiResponse.ok(msa.create(form));
    }

    @PutMapping(M + "/{id}")
    @ProgAuth(prog = MsaService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long id, @RequestBody StudyForm form) {
        msa.update(id, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(M + "/{id}/complete")
    @ProgAuth(prog = MsaService.PROG, action = Action.APPROVE)
    public ApiResponse<String> complete(@PathVariable long id, @RequestBody ActionForm req) {
        msa.complete(id, req);
        return ApiResponse.ok("OK");
    }

    @PostMapping(M + "/{id}/cancel")
    @ProgAuth(prog = MsaService.PROG, action = Action.SAVE)
    public ApiResponse<String> cancel(@PathVariable long id, @RequestBody ActionForm req) {
        msa.cancel(id, req);
        return ApiResponse.ok("OK");
    }

    @PostMapping(M + "/export")
    @ProgAuth(prog = MsaService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) StudyExportRequest req, HttpServletResponse response) throws IOException {
        msa.export(req, response);
    }
}
