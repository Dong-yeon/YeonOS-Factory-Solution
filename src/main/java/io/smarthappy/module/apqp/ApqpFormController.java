package io.smarthappy.module.apqp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * P6 APQP A4 — PFMEA·관리계획서 엑셀 양식 내려받기(docs/41 12장). PRINT = 고객·심사에 내는 문서 반출(X4, 조회와 따로).
 * A3-3(11.8) — 같은 양식을 QMS 관리문서(작성 중 리비전)로 올리기: PRINT + QMS 문서 SAVE(서비스에서)
 */
@RestController
@RequiredArgsConstructor
public class ApqpFormController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ApqpFormService forms;
    private final ApqpDocService docs;
    private final FmeaTypeGuard guard;

    @PostMapping("/api/apqp/fmeas/{id}/excel")
    @ProgAuth(prog = FmeaService.PROG, action = Action.PRINT)
    public void fmea(@PathVariable long id, HttpServletResponse response) throws IOException {
        guard.fmea(FmeaService.PFMEA, id);
        send(forms.fmea(id), response);
    }

    @PostMapping("/api/apqp/cps/{id}/excel")
    @ProgAuth(prog = CpService.PROG, action = Action.PRINT)
    public void cp(@PathVariable long id, HttpServletResponse response) throws IOException {
        send(forms.cp(id), response);
    }

    @GetMapping("/api/apqp/fmeas/{id}/qms-doc")
    @ProgAuth(prog = FmeaService.PROG, action = Action.SEARCH)
    public ApiResponse<ApqpDocService.DocState> fmeaDoc(@PathVariable long id) {
        guard.fmea(FmeaService.PFMEA, id);
        return ApiResponse.ok(docs.state(ApqpDocService.FMEA, id));
    }

    @PostMapping("/api/apqp/fmeas/{id}/qms-doc")
    @ProgAuth(prog = FmeaService.PROG, action = Action.PRINT)
    public ApiResponse<ApqpDocService.DocView> publishFmea(@PathVariable long id) {
        guard.fmea(FmeaService.PFMEA, id);
        return ApiResponse.ok(docs.publish(ApqpDocService.FMEA, id));
    }

    @GetMapping("/api/apqp/cps/{id}/qms-doc")
    @ProgAuth(prog = CpService.PROG, action = Action.SEARCH)
    public ApiResponse<ApqpDocService.DocState> cpDoc(@PathVariable long id) {
        return ApiResponse.ok(docs.state(ApqpDocService.CP, id));
    }

    @PostMapping("/api/apqp/cps/{id}/qms-doc")
    @ProgAuth(prog = CpService.PROG, action = Action.PRINT)
    public ApiResponse<ApqpDocService.DocView> publishCp(@PathVariable long id) {
        return ApiResponse.ok(docs.publish(ApqpDocService.CP, id));
    }

    static void send(ApqpFormService.Form f, HttpServletResponse response) throws IOException {
        response.setContentType(XLSX);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(f.fileName(), StandardCharsets.UTF_8).build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        response.setContentLength(f.content().length);
        response.getOutputStream().write(f.content());
    }
}
