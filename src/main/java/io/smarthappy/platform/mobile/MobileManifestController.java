package io.smarthappy.platform.mobile;

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
 * 모바일 웹앱 PWA manifest (docs/74 M5). 셸용 {@code /manifest.webmanifest}(PwaController, start_url /main)와 따로 —
 * 휴대폰 홈 화면에 추가한 앱이 {@code /m} 으로 열리게. 내용은 클래스패스 {@code pwa/manifest-m.webmanifest} 그대로(앱 이름·아이콘, 업무 데이터 없음),
 * 로그인 전 공개(SecurityConfig — 브라우저가 manifest 를 쿠키 없이 받는다). 서비스 워커는 기존 /sw.js 그대로(정적 자원만 캐시)
 */
@RestController
public class MobileManifestController {

    public static final String PATH = "/m/manifest.webmanifest";
    static final MediaType WEB_MANIFEST = MediaType.valueOf("application/manifest+json");
    private static final byte[] MANIFEST = read("pwa/manifest-m.webmanifest");

    @GetMapping(PATH)
    @ProgAuthExempt(reason = "모바일 PWA manifest — 로그인 전 공개 정적 내용(앱 이름·아이콘), 업무 데이터 없음. /api 밖이라 가드 대상은 아니지만 뜻을 남긴다")
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
