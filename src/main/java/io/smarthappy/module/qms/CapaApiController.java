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

import io.smarthappy.module.qms.CapaDtos.CancelForm;
import io.smarthappy.module.qms.CapaDtos.CapaDetail;
import io.smarthappy.module.qms.CapaDtos.CapaExportRequest;
import io.smarthappy.module.qms.CapaDtos.CapaForm;
import io.smarthappy.module.qms.CapaDtos.CsrDetail;
import io.smarthappy.module.qms.CapaDtos.CsrForm;
import io.smarthappy.module.qms.CapaDtos.CsrPage;
import io.smarthappy.module.qms.CapaDtos.CsrRow;
import io.smarthappy.module.qms.DocDtos.ActionForm;
import io.smarthappy.module.qms.DocDtos.SubmitForm;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.user.UserOption;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import io.smarthappy.platform.excel.GridExcel;

/**
 * 시정조치(8D)·고객 요구사항(CSR) API (P3 QMS Q4, docs/29) — 8D 저장·단계·종결 상신·취소 = SAVE, 결재 없는 종결 = APPROVE.
 * 결재 승인·반려·회수는 공통 결재 API(/api/platform/approvals)
 */
@RestController
@RequiredArgsConstructor
public class CapaApiController {

    private static final String A = "/api/qms/capas";
    private static final String S = "/api/qms/csrs";

    private final CapaService capas;
    private final CsrService csrs;
    private final DocService docs;
    private final MessageSource messages;
    private final GridExcel gridExcel;

    @GetMapping(A)
    @ProgAuth(prog = CapaService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> list(@RequestParam(required = false) String keyword, @RequestParam(required = false) String status,
            @RequestParam(required = false) String source, @RequestParam(required = false) String dueState,
            @RequestParam(required = false) Boolean withClosed, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        CapaService.Page p = capas.list(keyword, status, source, dueState, withClosed, page, size, sort == null ? null : List.of(sort),
                total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(A + "/columns")
    @ProgAuth(prog = CapaService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> columns(Locale locale) {
        List<Map<String, Object>> cols = capas.columns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** 담당자·결재선 후보 — 관리문서와 같은 목록 */
    @GetMapping(A + "/users")
    @ProgAuth(prog = CapaService.PROG, action = Action.SEARCH)
    public ApiResponse<List<UserOption>> users() {
        return ApiResponse.ok(docs.users());
    }

    @GetMapping(A + "/{capaId}")
    @ProgAuth(prog = CapaService.PROG, action = Action.SEARCH)
    public ApiResponse<CapaDetail> detail(@PathVariable long capaId) {
        return ApiResponse.ok(capas.detail(capaId));
    }

    @PostMapping(A)
    @ProgAuth(prog = CapaService.PROG, action = Action.SAVE)
    public ApiResponse<Long> create(@RequestBody CapaForm form) {
        return ApiResponse.ok(capas.create(form));
    }

    @PutMapping(A + "/{capaId}")
    @ProgAuth(prog = CapaService.PROG, action = Action.SAVE)
    public ApiResponse<String> update(@PathVariable long capaId, @RequestBody CapaForm form) {
        capas.update(capaId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(A + "/{capaId}/advance")
    @ProgAuth(prog = CapaService.PROG, action = Action.SAVE)
    public ApiResponse<String> advance(@PathVariable long capaId, @RequestBody ActionForm form) {
        capas.advance(capaId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(A + "/{capaId}/submit")
    @ProgAuth(prog = CapaService.PROG, action = Action.SAVE)
    public ApiResponse<Long> submit(@PathVariable long capaId, @RequestBody SubmitForm form) {
        return ApiResponse.ok(capas.submitClose(capaId, form));
    }

    @PostMapping(A + "/{capaId}/close")
    @ProgAuth(prog = CapaService.PROG, action = Action.APPROVE)
    public ApiResponse<String> close(@PathVariable long capaId, @RequestBody ActionForm form) {
        capas.close(capaId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(A + "/{capaId}/cancel")
    @ProgAuth(prog = CapaService.PROG, action = Action.SAVE)
    public ApiResponse<String> cancel(@PathVariable long capaId, @RequestBody CancelForm form) {
        capas.cancel(capaId, form);
        return ApiResponse.ok("OK");
    }

    @PostMapping(A + "/export")
    @ProgAuth(prog = CapaService.PROG, action = Action.EXCEL)
    public void export(@RequestBody(required = false) CapaExportRequest req, HttpServletResponse response) throws IOException {
        capas.export(req, response);
    }

    // ------------------------------------------------------------------ CSR

    @GetMapping(S)
    @ProgAuth(prog = CsrService.PROG, action = Action.SEARCH)
    public ApiResponse<List<CsrRow>> csrList(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean withUnused) {
        return ApiResponse.ok(csrs.list(keyword, withUnused));
    }

    /** 그리드(UI 단계 3) — 목록과 같은 조건 + page·size·sort. sort 는 원래 값 그대로(List 로 받으면 스프링이 쉼표로 쪼갠다, 결정 #47) */
    @GetMapping(S + "/grid")
    @ProgAuth(prog = CsrService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> csrGrid(@RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean withUnused, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, @RequestParam(required = false) Boolean total, HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        CsrPage p = csrs.grid(keyword, withUnused, page, size, sort == null ? null : List.of(sort), total);
        return new ApiResponse<>("OK", null, p.list(), p.total());
    }

    @GetMapping(S + "/columns")
    @ProgAuth(prog = CsrService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> csrColumns(Locale locale) {
        List<Map<String, Object>> cols = csrs.gridColumns().stream().map(c -> c.toView(messages, locale)).toList();
        return ApiResponse.list(cols, cols.size());
    }

    /** grid 메뉴 엑셀(docs/73) — grid 와 같은 서비스 메서드·같은 조건·정렬, 보이는 열만 */
    @PostMapping(S + "/export")
    @ProgAuth(prog = CsrService.PROG, action = Action.EXCEL)
    public void csrExport(@RequestBody(required = false) Map<String, Object> body, HttpServletResponse response) throws IOException {
        gridExcel.export(response, body, CsrService.PROG, csrs.gridColumns(), q -> {
            var p = csrs.grid(q.str("keyword"), q.bool("withUnused"), q.page(), q.size(), q.sort(), q.total());
            return GridExcel.Slice.of(p.list(), p.total());
        });
    }

    @GetMapping(S + "/{csrId}")
    @ProgAuth(prog = CsrService.PROG, action = Action.SEARCH)
    public ApiResponse<CsrDetail> csrDetail(@PathVariable long csrId) {
        return ApiResponse.ok(csrs.detail(csrId));
    }

    @PostMapping(S)
    @ProgAuth(prog = CsrService.PROG, action = Action.SAVE)
    public ApiResponse<Long> csrCreate(@RequestBody CsrForm form) {
        return ApiResponse.ok(csrs.create(form));
    }

    @PutMapping(S + "/{csrId}")
    @ProgAuth(prog = CsrService.PROG, action = Action.SAVE)
    public ApiResponse<String> csrUpdate(@PathVariable long csrId, @RequestBody CsrForm form) {
        csrs.update(csrId, form);
        return ApiResponse.ok("OK");
    }
}
