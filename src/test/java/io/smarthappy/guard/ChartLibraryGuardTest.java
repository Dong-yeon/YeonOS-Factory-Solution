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
 * 가드: 차트 라이브러리는 hb.chart 뒤에만 (docs/61 11장). 파일만 읽는다.
 * - Highcharts 는 상업 유료라 납품·SaaS 때 바꿀 수 있어야 한다 → 화면 JS·템플릿은 hb.chart.* 만 부르고
 *   Highcharts 전역·vendor-local 경로를 직접 쓰지 않는다(hb.chart.js 만 예외).
 * - hb.chart.js 는 styled mode 로만 그린다(아니면 Highcharts 가 SVG 에 &lt;style&gt; 을 넣어 CSP style-src 'self' 에 막힌다).
 * - hb.chart.js 가 싣는 버전 = 내려받기 스크립트가 받는 버전, vendor-local 은 .gitignore(파일 커밋 금지).
 */
class ChartLibraryGuardTest {

    private static final Path JS = Path.of("src/main/resources/static/js");
    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final Path HB_CHART = JS.resolve("common/hb.chart.js");
    private static final Path FETCH = Path.of("scripts/fetch-highcharts.mjs");

    /** 식별자 Highcharts(문자열 안 포함) — 주석은 지우고 본다 */
    static final Pattern LIB = Pattern.compile("\\bHighcharts\\b");
    static final Pattern VENDOR_LOCAL = Pattern.compile("vendor-local");

    @Test
    void 화면_JS_는_Highcharts_를_직접_부르지_않는다() throws IOException {
        List<String> bad = new ArrayList<>();
        try (Stream<Path> s = Files.walk(JS)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".js")).filter(p -> !p.equals(HB_CHART)).toList()) {
                String src = stripJsComments(Files.readString(f, StandardCharsets.UTF_8));
                for (Pattern p : List.of(LIB, VENDOR_LOCAL)) {
                    Matcher m = p.matcher(src);
                    if (m.find()) {
                        bad.add(JS.relativize(f) + "  " + m.group() + " → hb.chart.* 를 쓰세요");
                    }
                }
            }
        }
        assertThat(bad).isEmpty();
    }

    @Test
    void 템플릿은_차트_라이브러리를_직접_싣지_않는다() throws IOException {
        List<String> bad = new ArrayList<>();
        try (Stream<Path> s = Files.walk(TEMPLATES)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".html")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
                if (VENDOR_LOCAL.matcher(src).find() || Pattern.compile("(?i)highcharts").matcher(src).find()) {
                    bad.add(TEMPLATES.relativize(f) + " → hb.chart.js 만 싣는다(라이브러리는 hb.chart 가 싣는다)");
                }
            }
        }
        assertThat(bad).isEmpty();
    }

    @Test
    void hb_chart_는_styled_mode_이고_버전이_내려받기_스크립트와_같다() throws IOException {
        String chart = Files.readString(HB_CHART, StandardCharsets.UTF_8);
        assertThat(chart).contains("styledMode: true");
        assertThat(chart).doesNotContain("styledMode: false");
        String fetch = Files.readString(FETCH, StandardCharsets.UTF_8);
        String inChart = group(Pattern.compile("var HC_VERSION = '([^']+)'"), chart);
        String inFetch = group(Pattern.compile("const VERSION = '([^']+)'"), fetch);
        assertThat(inChart).as("hb.chart.js HC_VERSION").isNotNull().isEqualTo(inFetch);
        assertThat(fetch).as("버전 고정 + SHA-256 확인").contains("TARBALL_SHA256").containsPattern("'[0-9a-f]{64}'");
    }

    @Test
    void vendor_local_은_커밋하지_않는다() throws IOException {
        assertThat(Files.readAllLines(Path.of(".gitignore"), StandardCharsets.UTF_8))
                .anyMatch(l -> l.strip().equals("src/main/resources/static/vendor-local/"));
    }

    @Test
    void 검사기가_실제로_잡는다() {
        assertThat(LIB.matcher(stripJsComments("Highcharts.chart(el, {});")).find()).isTrue();
        assertThat(LIB.matcher(stripJsComments("var H = window['Highcharts'];")).find()).isTrue();
        assertThat(LIB.matcher(stripJsComments("/* Highcharts 는 hb.chart 안에서만 */ hb.chart.bar(el, o);")).find()).isFalse();
        assertThat(LIB.matcher(stripJsComments("// Highcharts 설명\nhb.chart.pareto(el, o);")).find()).isFalse();
        assertThat(LIB.matcher("myHighchartsLike()").find()).isFalse();
    }

    /** 블록·줄 주석 제거(주소 속 // 는 따옴표 안이라 남는다 — 이 가드에는 충분) */
    static String stripJsComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)(^|[^:'\"])//.*$", "$1");
    }

    private static String group(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }
}
