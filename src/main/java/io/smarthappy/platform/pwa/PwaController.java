package io.smarthappy.platform.pwa;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.auth.ProgAuthExempt;

/**
 * PWA manifest (docs/45 M-1). 정적 파일로 두면 Spring 기본 형식 표에 {@code .webmanifest} 가 없어 {@code application/octet-stream} 으로
 * 나간다(2.2 P2 실측) — 형식을 정해 내려 주려고 여기서 낸다. 내용은 클래스패스 {@code pwa/manifest.webmanifest} 그대로(업무 데이터 없음),
 * 로그인 전 공개(SecurityConfig). 캐시: 브라우저가 매번 다시 확인(no-cache) — 아이콘·이름을 바꾸면 바로 반영
 */
@RestController
public class PwaController {

    static final MediaType WEB_MANIFEST = MediaType.valueOf("application/manifest+json");
    private static final byte[] MANIFEST = read("pwa/manifest.webmanifest");

    @GetMapping("/manifest.webmanifest")
    @ProgAuthExempt(reason = "PWA manifest — 로그인 전 공개 정적 내용(앱 이름·아이콘), 업무 데이터 없음. /api 밖이라 가드 대상은 아니지만 뜻을 남긴다")
    public ResponseEntity<byte[]> manifest() {
        return ResponseEntity.ok().contentType(WEB_MANIFEST).cacheControl(CacheControl.noCache()).body(MANIFEST);
    }

    private static byte[] read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("PWA manifest 없음: " + path, e);
        }
    }
}
