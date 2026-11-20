package io.smarthappy.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 가드: 디자인 토큰 규칙 (docs/12 V1, docs/15 4.3·4.9). 파일만 읽는다.
 * - 색 값(#hex·rgb()·hsl())과 원시 토큰(var(--hb-p-*))은 tokens.css 에만 — 다른 CSS 는 의미·컴포넌트 토큰만.
 * - @media 폭·높이 숫자는 브레이크포인트 표(tokens.css 머리 주석)에 있는 값만 — 구간이 제각각 늘지 않게.
 * - CSS 를 쓰는 템플릿은 tokens.css 를 먼저 링크한다(토큰이 없으면 var() 가 빈 값이 돼 조용히 깨진다).
 * 예외: static/css/dev/ (local 프로필 전용 PoC 화면 — 운영 빌드에 노출되지 않음, DevPocProfileTest).
 */
class DesignTokenGuardTest {

    private static final Path CSS = Path.of("src/main/resources/static/css");
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final String TOKENS = "tokens.css";

    /** 선언 값 안의 색 — 선택자의 #id 와 구분하려고 ':' 뒤만 본다 */
    private static final Pattern RAW_COLOR = Pattern.compile(
            ":[^;{}]*?(#[0-9a-fA-F]{3,8}\\b|\\brgba?\\(|\\bhsla?\\()");
    private static final Pattern RAW_TOKEN = Pattern.compile("var\\(\\s*--hb-p-");
    /** @media 만 본다 — 하나의 @media 에 조건이 여럿이면 각각. @container(상세 패널 폭)는 컴포넌트 구간이라 따로(tokens.css 주석) */
    private static final Pattern MEDIA_RULE = Pattern.compile("@media[^{]*\\{");
    private static final Pattern MEDIA_NUMBER = Pattern.compile("\\((?:min|max)-(?:width|height)\\s*:\\s*(\\d+)px\\)");
    /** 글자 크기 px 리터럴 — 가장 작은 글자 토큰(--hb-fs-xs 11px)보다 작으면 거부(docs/40 D11) */
    private static final Pattern FONT_PX = Pattern.compile("font-size\\s*:\\s*(\\d+(?:\\.\\d+)?)px");
    private static final double MIN_FONT_PX = 11;
    /** tokens.css 머리 주석의 브레이크포인트 표와 같게 (경계 -1 값 포함) */
    private static final Set<Integer> BREAKPOINTS = Set.of(
            520, 599, 600, 720, 899, 900, 901, 1023, 1024, 1279, 1280, 1365, 1366, 1799, 1800, 1919, 1920);

    @Test
    void 색_값과_원시_토큰은_tokens_css_밖에서_쓰지_않는다() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path f : cssFiles()) {
            if (f.getFileName().toString().equals(TOKENS)) {
                continue;
            }
            String[] lines = strip(Files.readString(f, StandardCharsets.UTF_8)).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (RAW_COLOR.matcher(lines[i]).find() || RAW_TOKEN.matcher(lines[i]).find()) {
                    bad.add(CSS.relativize(f) + ":" + (i + 1) + "  " + lines[i].strip());
                }
            }
        }
        assertThat(bad).as("tokens.css 의 의미 토큰(--hb-*)으로 바꾸세요").isEmpty();
    }

    @Test
    void media_쿼리_숫자는_브레이크포인트_표에_있는_값만() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path f : cssFiles()) {
            Matcher rule = MEDIA_RULE.matcher(strip(Files.readString(f, StandardCharsets.UTF_8)));
            while (rule.find()) {
                Matcher m = MEDIA_NUMBER.matcher(rule.group());
                while (m.find()) {
                    if (!BREAKPOINTS.contains(Integer.parseInt(m.group(1)))) {
                        bad.add(CSS.relativize(f) + "  " + m.group());
                    }
                }
            }
        }
        assertThat(bad).as("새 구간이 필요하면 tokens.css 표·docs/15 4.2 를 먼저 고치고 이 목록에 추가").isEmpty();
    }

    @Test
    void 글자_크기는_가장_작은_토큰_11px_보다_작게_쓰지_않는다() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path f : cssFiles()) {
            String[] lines = strip(Files.readString(f, StandardCharsets.UTF_8)).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher m = FONT_PX.matcher(lines[i]);
                while (m.find()) {
                    if (Double.parseDouble(m.group(1)) < MIN_FONT_PX) {
                        bad.add(CSS.relativize(f) + ":" + (i + 1) + "  " + lines[i].strip());
                    }
                }
            }
        }
        assertThat(bad).as("var(--hb-fs-xs) 이상으로 — 차트 라벨·정렬 표시도 읽을 수 있어야 한다(docs/40 D11)").isEmpty();
    }

    @Test
    void 공통_CSS_를_쓰는_템플릿은_tokens_css_를_먼저_링크한다() throws IOException {
        Pattern link = Pattern.compile("@\\{/css/(tokens|page|shell|login)\\.css\\}");
        List<String> bad = new ArrayList<>();
        try (Stream<Path> s = Files.walk(TEMPLATES)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".html")).toList()) {
                Matcher m = link.matcher(Files.readString(f, StandardCharsets.UTF_8));
                List<String> order = new ArrayList<>();
                while (m.find()) {
                    order.add(m.group(1));
                }
                if (!order.isEmpty() && !order.get(0).equals("tokens")) {
                    bad.add(TEMPLATES.relativize(f) + "  " + order);
                }
            }
        }
        assertThat(bad).isEmpty();
    }

    @Test
    void 가드_자체_확인_색_원시토큰_media_숫자를_잡는다() {
        assertThat(RAW_COLOR.matcher(".a { color: #1f2937; }").find()).isTrue();
        assertThat(RAW_COLOR.matcher(".a { box-shadow: 0 1px rgba(0,0,0,.1); }").find()).isTrue();
        assertThat(RAW_COLOR.matcher("#hbHelp, .b { color: var(--hb-text); }").find()).isFalse();
        assertThat(RAW_TOKEN.matcher(".a { color: var(--hb-p-gray-900); }").find()).isTrue();
        Matcher rule = MEDIA_RULE.matcher("@media (max-width: 777px) { .a {} } @container detail (min-width: 560px) { .b {} }");
        assertThat(rule.find()).isTrue();
        Matcher m = MEDIA_NUMBER.matcher(rule.group());
        assertThat(m.find() && !BREAKPOINTS.contains(Integer.parseInt(m.group(1)))).isTrue();
        assertThat(rule.find()).as("@container 는 @media 규칙으로 세지 않는다").isFalse();
        Matcher fs = FONT_PX.matcher(".a { font-size: 10px; } .b { font-size: 10.5px; } .c { font-size: 12px; }");
        List<Double> sizes = new ArrayList<>();
        while (fs.find()) {
            sizes.add(Double.parseDouble(fs.group(1)));
        }
        assertThat(sizes).containsExactly(10.0, 10.5, 12.0);
        assertThat(FONT_PX.matcher(".a { font-size: var(--hb-fs-xs); }").find()).isFalse();
    }

    private static List<Path> cssFiles() throws IOException {
        try (Stream<Path> s = Files.walk(CSS)) {
            return s.filter(p -> p.toString().endsWith(".css"))
                    .filter(p -> !CSS.relativize(p).toString().replace('\\', '/').startsWith("dev/"))
                    .toList();
        }
    }

    /** 주석 제거(줄 수는 유지) */
    private static String strip(String css) {
        Matcher m = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(css);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group().replaceAll("[^\n]", " ")));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
