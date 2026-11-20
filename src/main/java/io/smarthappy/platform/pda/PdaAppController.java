package io.smarthappy.platform.pda;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.log.AccessLogService.Result;
import io.smarthappy.platform.pda.PdaAppService.Download;
import io.smarthappy.platform.pda.PdaAppService.Info;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 현장 단말 APK (docs/50 3·4장) — 공개 최소 버전 JSON 하나 + 로그인한 관리자의 다운로드 화면 API.
 * 다운로드는 파라미터가 없다(고정 파일 하나, F1).
 */
@RestController
@RequiredArgsConstructor
public class PdaAppController {

    static final String APK_TYPE = "application/vnd.android.package-archive";

    private final PdaAppService service;
    private final AccessLogService accessLog;

    /** 껍데기가 로그인 화면을 열기 전에 부른다(V1) — 세 칸만, 캐시 없음 */
    @GetMapping(value = "/pda/version.json", produces = "application/json")
    public ResponseEntity<Map<String, Object>> version() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.publicVersion());
    }

    @GetMapping("/api/platform/pda-app")
    @ProgAuth(prog = PdaAppService.PROG, action = Action.SEARCH)
    public ApiResponse<Map<String, Object>> info(Locale locale) {
        Info info = service.info();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", info.state().name());
        if (info.release() != null) {
            var r = info.release();
            out.put("version", r.version());
            out.put("minVersion", r.minVersion());
            out.put("releasedAt", r.releasedAt().toString());
            out.put("notes", "en".equals(locale.getLanguage()) && r.notesEn() != null ? r.notesEn() : r.notesKo());
        }
        out.put("sizeBytes", info.sizeBytes());
        out.put("sha256", info.sha256());
        return ApiResponse.ok(out);
    }

    @GetMapping("/api/platform/pda-app/download")
    @ProgAuth(prog = PdaAppService.PROG, action = Action.SEARCH)
    public void download(HttpServletResponse response) throws IOException {
        Download d = service.download();
        try (InputStream in = d.content()) {
            response.setContentType(APK_TYPE);
            response.setContentLengthLong(d.sizeBytes());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                    .filename("yeonos-pda-" + d.release().version() + ".apk", StandardCharsets.UTF_8).build().toString());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
            response.setHeader("X-Content-Type-Options", "nosniff");
            accessLog.recordCurrent(AccessEvent.FILE_DOWNLOAD, PdaAppService.PROG, Action.SEARCH.name(), Result.OK,
                    "pda-apk version=" + d.release().version() + " sha256=" + d.sha256().substring(0, 12));
            in.transferTo(response.getOutputStream());
        }
    }
}
