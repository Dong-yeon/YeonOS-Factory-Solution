package io.smarthappy.platform.file;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.smarthappy.platform.auth.ProgAuthExempt;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.file.FileService.Download;
import io.smarthappy.platform.file.FileService.FileView;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 첨부 API. 권한은 고정 화면이 아니라 대상 화면(targetType) 기준이라 FileService 가 판정한다.
 * 내려받기는 항상 attachment + 서버가 정한 Content-Type (브라우저가 내용을 해석해 실행하지 않게).
 */
@RestController
@RequiredArgsConstructor
public class FileApiController {

    private static final String EXEMPT = "대상 화면(targetType) 기준 권한을 FileService 가 판정 — 조회 SEARCH, 올리기·삭제 SAVE";

    private final FileService service;

    @PostMapping("/api/platform/files")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<FileView> upload(@RequestParam String targetType, @RequestParam String targetId,
            @RequestParam(required = false) MultipartFile file) {
        return ApiResponse.ok(service.upload(targetType, targetId, file).view());
    }

    @GetMapping("/api/platform/files")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<Void> list(@RequestParam String targetType, @RequestParam String targetId) {
        List<FileView> files = service.list(targetType, targetId).stream().map(FileService.FileInfo::view).toList();
        return ApiResponse.list(files, files.size());
    }

    @GetMapping("/api/platform/files/{fileId}")
    @ProgAuthExempt(reason = EXEMPT)
    public void download(@PathVariable long fileId, HttpServletResponse response) throws IOException {
        Download d = service.download(fileId);
        try (InputStream in = d.content()) {
            response.setContentType(d.info().contentType());
            response.setContentLengthLong(d.info().sizeBytes());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename(d.info().origName(), StandardCharsets.UTF_8).build().toString());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
            OutputStream out = response.getOutputStream();
            in.transferTo(out);
        }
    }

    @PostMapping("/api/platform/files/{fileId}/delete")
    @ProgAuthExempt(reason = EXEMPT)
    public ApiResponse<String> delete(@PathVariable long fileId) {
        service.delete(fileId);
        return ApiResponse.ok("OK");
    }
}
