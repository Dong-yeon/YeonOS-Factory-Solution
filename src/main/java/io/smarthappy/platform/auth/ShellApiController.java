package io.smarthappy.platform.auth;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.TreeMap;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.config.I18nConfig;
import lombok.RequiredArgsConstructor;

/** 화면 셸이 쓰는 API — 로그인한 사용자 누구나 */
@RestController
@RequiredArgsConstructor
public class ShellApiController {

    private final MenuService menus;
    private final Environment env;

    @GetMapping("/api/platform/home")
    @ProgAuthExempt(reason = "자기 권한그룹의 홈 화면 코드만 (조회 권한 있는 것만, 판정은 MenuService)")
    public ApiResponse<String> home() {
        return ApiResponse.ok(menus.home());
    }

    @GetMapping("/api/platform/menu")
    @ProgAuthExempt(reason = "자기 권한으로 볼 수 있는 메뉴만 돌려준다 (판정은 MenuService)")
    public ApiResponse<List<MenuService.MenuNode>> menu(Locale locale) {
        return ApiResponse.ok(menus.myMenus(locale));
    }

    /** 화면 JS 문구 (js.* 키만) */
    @GetMapping("/api/platform/messages")
    @ProgAuthExempt(reason = "화면 문구는 권한과 무관한 공개 정보")
    public ApiResponse<Map<String, String>> messages(Locale locale) {
        // JDK ResourceBundle 은 기동 동안 캐시한다. 문구 캐시 시간을 둔 프로필(local)에선 서버 문구처럼 바로 반영되게 비운다.
        if (env.containsProperty("spring.messages.cache-duration")) {
            ResourceBundle.clearCache(getClass().getClassLoader());
        }
        Locale loc = I18nConfig.supported(locale);
        Map<String, String> fromSrc = fromDevDir(loc);
        if (fromSrc != null) {
            return ApiResponse.ok(fromSrc);
        }
        ResourceBundle bundle = ResourceBundle.getBundle("messages", loc,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        Map<String, String> js = new TreeMap<>();
        for (String key : bundle.keySet()) {
            if (isJsKey(key)) {
                js.put(key, bundle.getString(key));
            }
        }
        return ApiResponse.ok(js);
    }

    private static boolean isJsKey(String key) {
        return key.startsWith("js.") || key.startsWith("help.");
    }

    /**
     * local 전용(happyboot.dev.messages-dir): 화면 JS 를 src 에서 읽는 프로필이면 문구도 같은 src 의 properties 에서 —
     * 빌드된 classpath 문구와 어긋나 새 키가 키 글자 그대로 보이는 일을 막는다. 설정이 없거나 파일이 없으면 null(classpath)
     */
    private Map<String, String> fromDevDir(Locale loc) {
        String dir = env.getProperty("happyboot.dev.messages-dir");
        if (dir == null || dir.isBlank()) {
            return null;
        }
        Path base = Path.of(dir, "messages.properties");
        if (!Files.isRegularFile(base)) {
            return null;
        }
        Properties p = new Properties();
        try {
            load(p, base);
            Path lang = Path.of(dir, "messages_" + loc.getLanguage() + ".properties");
            if (Files.isRegularFile(lang)) {
                load(p, lang);   // 언어 파일이 기본 파일을 덮는다(ResourceBundle 부모 사슬과 같다)
            }
        } catch (IOException e) {
            return null;
        }
        Map<String, String> js = new TreeMap<>();
        p.stringPropertyNames().stream().filter(ShellApiController::isJsKey).forEach(k -> js.put(k, p.getProperty(k)));
        return js;
    }

    private static void load(Properties p, Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
    }
}
