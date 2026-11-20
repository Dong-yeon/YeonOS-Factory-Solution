package io.smarthappy.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 가드(줄이기만 하는 기준선): 사람이 적는 필수 글 칸의 빈 값 판정은 {@code VisibleText} 로 (QA R4-a, docs/qa/2026-11-02_invisible-reason-proposal.md).
 *
 * <p>{@code isBlank()}·{@code strip().isEmpty()} 는 U+200B·U+FEFF·U+00A0 같은 보이지 않는 글자를 글자로 센다. main 소스에서 아래 모양을 센다:
 * <ol>
 *   <li>이름에 reason·comment 가 든 값(코드 칸 {@code …Cd}·{@code …Code}·목록 {@code …s} 제외)에 바로 {@code .isBlank()}·{@code .isEmpty()}·{@code .strip().isEmpty()}</li>
 *   <li>모듈마다 따로 둔 판정 도우미 {@code static boolean blank(String …)}·{@code static String text(String …, String field, …)} 정의</li>
 * </ol>
 * 지금 있는 것은 기준선 파일({@code src/test/resources/guard/visible-text-baseline.txt}, 파일마다 건수)로 둔다 — 2단계 모듈 세션이
 * 자기 모듈을 {@code VisibleText} 로 바꾸면서 줄을 줄이거나 지운다. <b>새 파일·건수 늘어남은 실패</b>, 줄었는데 기준선을 그대로 두어도 실패(기준선이 늘 실제와 같게).
 * 코드·식별자 칸처럼 사람이 적는 글이 아닌 곳의 {@code blank()} 는 바꿔도 동작이 같다(보이지 않는 글자만 넣은 코드는 어차피 형식 오류) — 구분하지 않고 센다.
 */
class VisibleTextGuardTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path BASELINE = Path.of("src/test/resources/guard/visible-text-baseline.txt");

    static final Pattern DIRECT = Pattern.compile(
            "\\b\\w*(?i:reason|comment)(?!\\w*(?:Cd|Code|s)\\b)\\w*(?:\\(\\))?\\s*\\.\\s*(?:isBlank\\(\\)|isEmpty\\(\\)|strip\\(\\)\\s*\\.\\s*isEmpty\\(\\))");
    static final Pattern HELPER = Pattern.compile(
            "\\bstatic\\s+(?:boolean\\s+blank\\s*\\(\\s*String\\b|String\\s+text\\s*\\(\\s*String\\s+\\w+\\s*,\\s*String\\s+field\\b)");

    @Test
    void 보이지_않는_글자를_세는_판정은_기준선보다_늘지_않는다() throws IOException {
        Map<String, Integer> actual = scan();
        Map<String, Integer> baseline = readBaseline();
        List<String> grown = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        actual.forEach((file, n) -> {
            int allowed = baseline.getOrDefault(file, 0);
            if (n > allowed) {
                grown.add(file + "  " + allowed + " → " + n);
            }
        });
        baseline.forEach((file, allowed) -> {
            int n = actual.getOrDefault(file, 0);
            if (n < allowed) {
                stale.add(file + " " + n + "   (기준선 " + allowed + ")");
            }
        });
        assertThat(grown).as("필수 사유·설명 칸은 io.smarthappy.platform.common.text.VisibleText(isBlank·strip·reason·optional)로 판정하세요")
                .isEmpty();
        assertThat(stale).as("줄었습니다 — " + BASELINE + " 의 그 줄을 이 건수로 바꾸거나(0 이면 지우기) 하세요").isEmpty();
    }

    @Test
    void 검사기가_실제로_잡는다() {
        assertThat(DIRECT.matcher("if (comment == null || comment.isBlank()) {").find()).isTrue();
        assertThat(DIRECT.matcher("if (reason.isEmpty()) {").find()).isTrue();
        assertThat(DIRECT.matcher("in.reason().strip().isEmpty()").find()).isTrue();
        assertThat(DIRECT.matcher("if (blank(in.cancelReason())) {").find()).isFalse();   // 도우미 쪽에서 센다
        assertThat(DIRECT.matcher("req.reasonCd().isBlank()").find()).isFalse();            // 코드 칸
        assertThat(DIRECT.matcher("if (comments.isEmpty()) {").find()).isFalse();            // 목록
        assertThat(DIRECT.matcher("VisibleText.isBlank(comment)").find()).isFalse();
        assertThat(HELPER.matcher("    static boolean blank(String s) {").find()).isTrue();
        assertThat(HELPER.matcher("    private static String text(String v, String field, int max, boolean required, FieldErrors e) {").find())
                .isTrue();
        assertThat(HELPER.matcher("    static String text(String raw) {").find()).isFalse();
    }

    private static Map<String, Integer> scan() throws IOException {
        Map<String, Integer> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                int n = 0;
                for (String raw : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String line = raw.trim();
                    if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                        continue;
                    }
                    var d = DIRECT.matcher(line);
                    while (d.find()) {
                        n++;
                    }
                    if (HELPER.matcher(line).find()) {
                        n++;
                    }
                }
                if (n > 0) {
                    out.put(MAIN.relativize(f).toString().replace('\\', '/'), n);
                }
            }
        }
        return out;
    }

    private static Map<String, Integer> readBaseline() throws IOException {
        Map<String, Integer> out = new TreeMap<>();
        for (String raw : Files.readAllLines(BASELINE, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] p = line.split("\\s+");
            out.put(p[0], Integer.parseInt(p[1]));
        }
        return out;
    }
}
