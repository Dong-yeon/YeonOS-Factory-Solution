package io.smarthappy.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 가드: 화면 소스의 XSS·CSP 규칙 (docs/05 4.4). DB·Spring 없이 파일만 읽는다.
 * - 템플릿: 인라인 스크립트(src 없는 script), 인라인 style 블록·속성, on* 이벤트 속성, th:utext 금지
 *   → CSP(script-src 'self', style-src 'self') 를 켤 수 있게
 * - 우리 JS(static/js, vendor 제외): innerHTML·outerHTML·insertAdjacentHTML·document.write 금지 → textContent 로
 */
class TemplateSecurityGuardTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final Path JS = Path.of("src/main/resources/static/js");

    private static final List<Rule> TEMPLATE_RULES = List.of(
            new Rule("인라인 <script>(src 없음)", Pattern.compile("<script(?![^>]*\\bsrc\\s*=)[^>]*>\\s*\\S")),
            new Rule("<style> 블록", Pattern.compile("<style[\\s>]", Pattern.CASE_INSENSITIVE)),
            new Rule("style 속성", Pattern.compile("\\sstyle\\s*=", Pattern.CASE_INSENSITIVE)),
            new Rule("on* 이벤트 속성", Pattern.compile("\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE)),
            new Rule("th:utext (이스케이프 안 됨)", Pattern.compile("th:utext")),
            new Rule("javascript: URL", Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE)));

    private static final List<Rule> JS_RULES = List.of(
            new Rule("innerHTML/outerHTML 대입", Pattern.compile("\\.(inner|outer)HTML\\s*\\+?=")),
            new Rule("insertAdjacentHTML", Pattern.compile("insertAdjacentHTML")),
            new Rule("document.write", Pattern.compile("document\\.write")),
            new Rule("eval/new Function", Pattern.compile("\\beval\\s*\\(|new\\s+Function\\s*\\(")));

    record Rule(String name, Pattern pattern) {
    }

    @Test
    void 템플릿에_인라인_스크립트_스타일_이벤트속성_utext_가_없다() throws IOException {
        assertThat(violations(TEMPLATES, ".html", TEMPLATE_RULES)).isEmpty();
    }

    @Test
    void 우리_JS는_HTML_문자열을_DOM에_넣지_않는다() throws IOException {
        assertThat(violations(JS, ".js", JS_RULES)).isEmpty();
    }

    @Test
    void 검사기가_실제로_잡는다() {
        assertThat(match("<script>alert(1)</script>", TEMPLATE_RULES)).isNotEmpty();
        assertThat(match("<script th:src=\"@{/js/a.js}\"></script>", TEMPLATE_RULES)).isEmpty();
        assertThat(match("<div style=\"color:red\">", TEMPLATE_RULES)).isNotEmpty();
        assertThat(match("<button onclick=\"x()\">", TEMPLATE_RULES)).isNotEmpty();
        assertThat(match("<p th:utext=\"${x}\">", TEMPLATE_RULES)).isNotEmpty();
        assertThat(match("<meta name=\"viewport\" content=\"width=device-width\">", TEMPLATE_RULES)).isEmpty();
        assertThat(match("el.innerHTML = name;", JS_RULES)).isNotEmpty();
        assertThat(match("el.textContent = name;", JS_RULES)).isEmpty();
    }

    private static List<String> violations(Path dir, String ext, List<Rule> rules) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(ext))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/vendor/")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8)
                        .replaceAll("(?s)<!--.*?-->", "")          // HTML 주석 제외
                        .replaceAll("(?s)/\\*.*?\\*/", "");         // JS 블록 주석 제외
                for (String hit : match(src, rules)) {
                    found.add(f + ": " + hit);
                }
            }
        }
        return found;
    }

    private static List<String> match(String src, List<Rule> rules) {
        List<String> hits = new ArrayList<>();
        for (Rule r : rules) {
            Matcher m = r.pattern().matcher(src);
            if (m.find()) {
                hits.add(r.name() + " → " + m.group().trim());
            }
        }
        return hits;
    }
}
