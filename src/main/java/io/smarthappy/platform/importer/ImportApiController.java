package io.smarthappy.platform.importer;

import java.io.IOException;
import java.util.Locale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.smarthappy.platform.auth.ProgAuthExempt;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.importer.ImportService.JobView;
import io.smarthappy.platform.importer.ImportService.RowPage;
import io.smarthappy.platform.importer.ImportService.TemplateView;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 엑셀 올리기 API (P1 2단계, docs/17 3b). 권한은 템플릿의 대상 화면 기준이라 ImportService 가 확인한다
 * (조회 = 템플릿·미리보기·오류 엑셀, 저장 = 올리기·실행·취소). 작업은 올린 사람만 본다.
 */
@RestController
@RequiredArgsConstructor
public class ImportApiController {

    private static final String BASE = "/api/platform/import";
    private static final String EXEMPT = "대상 화면이 템플릿마다 달라 ImportService 가 그 화면의 SEARCH/SAVE 를 확인";

    private final ImportService service;

    public record ExecuteRequest(Integer rowVer, Boolean skipErrors) {
    }

    @GetMapping(BASE + "/templates/{code}")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<TemplateView> template(@PathVariable String code, Locale locale) {
        return ApiResponse.ok(service.templateInfo(code, locale));
    }

    /** 빈 템플릿 파일 (hb.download 가 POST 로 받는다) */
    @PostMapping(BASE + "/templates/{code}/file")
    @ProgAuthExempt(reason = EXEMPT)
    public void templateFile(@PathVariable String code, Locale locale, HttpServletResponse response) throws IOException {
        service.templateFile(code, locale, response);
    }

    @PostMapping(BASE + "/templates/{code}/jobs")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<JobView> upload(@PathVariable String code, @RequestParam(required = false) String mode,
            @RequestParam(required = false) MultipartFile file, Locale locale) {
        return ApiResponse.ok(service.upload(code, mode, file, locale));
    }

    @GetMapping(BASE + "/jobs/{jobId}")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<JobView> job(@PathVariable long jobId, Locale locale) {
        return ApiResponse.ok(service.job(jobId, locale));
    }

    @GetMapping(BASE + "/jobs/{jobId}/rows")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Void> rows(@PathVariable long jobId, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size, Locale locale) {
        RowPage p = service.rows(jobId, status, page, size, locale);
        return ApiResponse.list(p.list(), p.total());
    }

    @PostMapping(BASE + "/jobs/{jobId}/errors/export")
    @ProgAuthExempt(reason = EXEMPT)
    public void errors(@PathVariable long jobId, Locale locale, HttpServletResponse response) throws IOException {
        service.errorReport(jobId, locale, response);
    }

    @PostMapping(BASE + "/jobs/{jobId}/execute")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<JobView> execute(@PathVariable long jobId, @RequestBody ExecuteRequest req, Locale locale) {
        return ApiResponse.ok(service.execute(jobId, req == null ? null : req.rowVer(),
                req != null && Boolean.TRUE.equals(req.skipErrors()), locale));
    }

    @PostMapping(BASE + "/jobs/{jobId}/cancel")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<JobView> cancel(@PathVariable long jobId, @RequestBody ExecuteRequest req, Locale locale) {
        return ApiResponse.ok(service.cancel(jobId, req == null ? null : req.rowVer(), locale));
    }
}
