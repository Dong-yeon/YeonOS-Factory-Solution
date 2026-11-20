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
 * 가드: 버튼 툴팁 기본(docs/36 20장). DB·Spring 없이 파일만 읽는다.
 * - 공통 조각(fragments/page)·셸(main.html)·포털이 hb.tip.js(hb.js 바로 뒤)와 tip.css 를 싣는다 → 모든 화면 버튼에 툴팁
 * - 아이콘만 있는 버튼(요소는 있고 보이는 글자 없음 — 속이 아예 빈 버튼은 JS 가 글자를 채우므로 제외)은 이름이 있어야 한다: aria-label·aria-labelledby·data-tip-key·title 중 하나
 *   (hb.tip 이 data-tip-key·title 로 aria-label 을 붙인다 — 그 둘도 없으면 화면 읽기 프로그램이 "버튼"만 읽는다)
 */
class ButtonTipGuardTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    private static final Pattern BUTTON = Pattern.compile("<button\\b([^>]*)>(.*?)</button>", Pattern.DOTALL);
    /** 버튼 안 글자를 내는 것: th:text 가 붙은 요소, 태그 밖 글자 */
    private static final Pattern TH_TEXT = Pattern.compile("\\bth:(u?text|value)\\s*=");
    private static final Pattern NAMED = Pattern.compile(
            "(?:\\s|^)(?:th:)?(?:aria-label|aria-labelledby|data-tip-key|title)\\s*=|th:attr\\s*=\\s*\"[^\"]*\\b(?:aria-label|title)\\s*=");

    @Test
    void 공통_조각_셸_포털은_hb_tip_을_싣는다() throws IOException {
        for (String t : List.of("fragments/page.html", "main.html", "portal/layout.html")) {
            String src = Files.readString(TEMPLATES.resolve(t), StandardCharsets.UTF_8);
            assertThat(src).as(t + " — tip.css").contains("@{/css/tip.css}");
            int hb = src.indexOf("@{/js/common/hb.js}");
            int tip = src.indexOf("@{/js/common/hb.tip.js}");
            assertThat(hb).as(t + " — hb.js").isPositive();
            assertThat(tip).as(t + " — hb.tip.js 는 hb.js 뒤(화면 JS 보다 앞)").isGreaterThan(hb);
        }
    }

    @Test
    void 아이콘만_있는_버튼은_이름이_있다() throws IOException {
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".html")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
                bad.addAll(unnamed(src).stream().map(b -> TEMPLATES.relativize(f) + "  " + b).toList());
            }
        }
        assertThat(bad).as("아이콘만 있는 버튼에 th:aria-label(또는 data-tip-key)을 다세요 — docs/36 20장 T4").isEmpty();
    }

    @Test
    void 가드_자체_확인() {
        assertThat(unnamed("<button type=\"button\"><span class=\"hb-icon\" data-icon=\"x\" aria-hidden=\"true\"></span></button>")).hasSize(1);
        assertThat(unnamed("<button type=\"button\" th:aria-label=\"#{a}\"><span class=\"hb-icon\"></span></button>")).isEmpty();
        assertThat(unnamed("<button type=\"button\" th:attr=\"aria-label=#{a},title=#{a}\"><span></span></button>")).isEmpty();
        assertThat(unnamed("<button type=\"button\" data-tip-key=\"js.tip.x\"><span class=\"hb-icon\"></span></button>")).isEmpty();
        assertThat(unnamed("<button type=\"button\" th:text=\"#{common.save}\">저장</button>")).isEmpty();
        assertThat(unnamed("<button type=\"button\" th:text=\"#{common.save}\"></button>")).isEmpty();
        assertThat(unnamed("<button type=\"button\"><span class=\"hb-icon\"></span> 조회</button>")).isEmpty();
        assertThat(unnamed("<button type=\"button\"><span th:text=\"#{a}\"></span></button>")).isEmpty();
        assertThat(unnamed("<button type=\"button\" data-x-title=\"a\"><span></span></button>")).hasSize(1);
        assertThat(unnamed("<button type=\"button\" id=\"rejectedBtn\" hidden></button>")).as("JS 가 글자를 채우는 빈 버튼").isEmpty();
    }

    private static List<String> unnamed(String src) {
        List<String> out = new ArrayList<>();
        Matcher m = BUTTON.matcher(src);
        while (m.find()) {
            String attrs = m.group(1);
            String inner = m.group(2);
            if (TH_TEXT.matcher(attrs).find() || TH_TEXT.matcher(inner).find()) {
                continue;
            }
            if (inner.isBlank()) {
                continue;                   // 속이 빈 버튼 = 글자를 화면 JS 가 채운다(POP 반려 수 등) — 아이콘 버튼이 아니다
            }
            String visible = inner.replaceAll("(?s)<[^>]*>", "").strip();
            if (!visible.isEmpty() || NAMED.matcher(attrs).find()) {
                continue;
            }
            out.add(m.group().replaceAll("\\s+", " "));
        }
        return out;
    }
}
