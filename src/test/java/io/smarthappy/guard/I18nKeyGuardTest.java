package io.smarthappy.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.smarthappy.platform.common.web.HbErrorCode;

/**
 * 가드: 화면 문구 키 동기화 (docs/04 2.1-c). DB·Spring 없이 소스 파일만 읽는다.
 * - 모든 언어 파일의 키 집합이 같다
 * - 한 파일 안에 같은 키가 두 번 없다(Properties 는 뒤 값이 조용히 이겨 앞 화면 문구가 바뀐다)
 * - 템플릿 #{키}, JS hb.t('키'), 오류 코드, 마이그레이션의 prog./menu. 키가 기본 파일에 있다
 * - data-help-key 형식 {progCd}.{field}
 */
class I18nKeyGuardTest {

    private static final Path RES = Path.of("src/main/resources");
    private static final Pattern TEMPLATE_KEY = Pattern.compile("#\\{([a-zA-Z0-9]+(?:\\.[a-zA-Z0-9]+)+)[}(]");
    private static final Pattern JS_KEY = Pattern.compile("hb\\.t\\(\\s*'([^']+)'");
    private static final Pattern SQL_KEY = Pattern.compile("'((?:prog|menu|code|policy)\\.[a-zA-Z0-9._]+)'");
    private static final Pattern HELP_KEY_ATTR = Pattern.compile("data-help-key=\"([^\"]*)\"");
    private static final Pattern HELP_KEY_FORMAT = Pattern.compile("^[a-z0-9][a-z0-9-]{1,49}\\.[a-zA-Z0-9]+$");

    @Test
    void 언어별_키_집합이_같다() throws IOException {
        Set<String> ko = keys("messages.properties");
        Set<String> en = keys("messages_en.properties");
        assertThat(diff(ko, en)).as("영어 파일에 없는 키").isEmpty();
        assertThat(diff(en, ko)).as("기본(한국어) 파일에 없는 키").isEmpty();
    }

    @Test
    void 한_파일에_같은_키가_두_번_없다() throws IOException {
        for (String file : List.of("messages.properties", "messages_en.properties")) {
            Set<String> dup = new TreeSet<>();
            Properties p = new Properties() {
                @Override
                public synchronized Object put(Object key, Object value) {
                    if (containsKey(key)) {
                        dup.add((String) key);
                    }
                    return super.put(key, value);
                }
            };
            try (Reader r = Files.newBufferedReader(RES.resolve(file), StandardCharsets.UTF_8)) {
                p.load(r);
            }
            assertThat(p).as("파싱이 비어 있으면 가드가 무의미").isNotEmpty();
            assertThat(dup).as(file + " 의 중복 키 — 모듈별 키로 나눈다(예: valid.SAME_ITEM_BOM)").isEmpty();
        }
    }

    @Test
    void 쓰는_키는_모두_정의되어_있다() throws IOException {
        Set<String> ko = keys("messages.properties");
        Set<String> used = new TreeSet<>();
        used.addAll(scan(RES.resolve("templates"), ".html", TEMPLATE_KEY));
        used.addAll(scan(RES.resolve("static/js"), ".js", JS_KEY));
        used.addAll(scan(RES.resolve("db/migration"), ".sql", SQL_KEY));
        for (HbErrorCode c : HbErrorCode.values()) {
            used.add(c.messageKey());
        }
        assertThat(used).as("스캔이 비어 있으면 가드가 무의미").isNotEmpty();
        assertThat(diff(used, ko)).as("정의되지 않은 메시지 키").isEmpty();
    }

    @Test
    void JS_문구_키는_js_로_시작한다() throws IOException {
        Set<String> jsKeys = scan(RES.resolve("static/js"), ".js", JS_KEY);
        assertThat(jsKeys).allMatch(k -> k.startsWith("js."), "js.* 키만 /api/platform/messages 로 내려간다");
    }

    @Test
    void 도움말_키_형식() throws IOException {
        Set<String> helpKeys = scan(RES.resolve("templates"), ".html", HELP_KEY_ATTR);
        assertThat(helpKeys).isNotEmpty().allMatch(k -> HELP_KEY_FORMAT.matcher(k).matches(),
                "data-help-key 는 {progCd}.{field}");
    }

    private static Set<String> keys(String file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(RES.resolve(file), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return new TreeSet<>(p.stringPropertyNames());
    }

    private static Set<String> scan(Path dir, String ext, Pattern pattern) throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(ext)).toList()) {
                Matcher m = pattern.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    found.add(m.group(1));
                }
            }
        }
        return found;
    }

    private static Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> d = new TreeSet<>(a);
        d.removeAll(b);
        return d;
    }
}
