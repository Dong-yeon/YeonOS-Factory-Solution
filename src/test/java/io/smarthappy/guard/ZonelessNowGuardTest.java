package io.smarthappy.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 가드: "오늘"을 서버(JVM) 시간대로 판단하지 않는다 (docs/04 2.9). 회사 시간대는 BizClock.
 * - main 소스에서 시간대 없는 now()·new Date()·Calendar.getInstance() 금지 → 파일:줄 목록으로 실패
 * - 테스트 JVM 은 UTC 로 고정(build.gradle) — 개발 PC(KST)에서만 통과하는 날짜 버그를 드러낸다
 */
class ZonelessNowGuardTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(LocalDate|LocalDateTime|LocalTime|ZonedDateTime|OffsetDateTime|YearMonth|Year)\\.now\\(\\s*\\)"
                    + "|new\\s+(java\\.util\\.)?Date\\(\\s*\\)|Calendar\\.getInstance\\(\\s*\\)");

    @Test
    void 테스트_JVM_시간대는_UTC() {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("UTC");
    }

    @Test
    void main_소스에_시간대_없는_now가_없다() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                        continue;
                    }
                    if (FORBIDDEN.matcher(line).find()) {
                        hits.add(f + ":" + (i + 1) + "  " + line);
                    }
                }
            }
        }
        assertThat(hits).as("BizClock 또는 Clock 주입을 쓰세요").isEmpty();
    }

    @Test
    void 검사기가_실제로_잡는다() {
        assertThat(FORBIDDEN.matcher("var d = LocalDate.now();").find()).isTrue();
        assertThat(FORBIDDEN.matcher("var d = LocalDateTime.now( );").find()).isTrue();
        assertThat(FORBIDDEN.matcher("Date d = new Date();").find()).isTrue();
        assertThat(FORBIDDEN.matcher("LocalDate.now(clock)").find()).isFalse();
        assertThat(FORBIDDEN.matcher("ZonedDateTime.now(clock.withZone(zone()))").find()).isFalse();
    }
}
