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
 * 가드: GitHub 액션은 커밋 SHA 로 고정한다 (docs/05 #7, docs/52 Y10). 태그(@v4)는 액션 저장소 쪽에서 옮길 수 있어
 * 같은 워크플로가 다른 코드를 돌릴 수 있다 — 공급망 공격 경로. 저장소 안 로컬 액션(./)·도커 이미지(docker://)는 대상 밖.
 */
class WorkflowPinGuardTest {

    private static final Path DIR = Path.of(".github/workflows");
    static final Pattern USES = Pattern.compile("^\\s*-?\\s*uses:\\s*([^\\s#]+)");
    static final Pattern PINNED = Pattern.compile("^[\\w.-]+/[\\w./-]+@[0-9a-f]{40}$");

    @Test
    void 워크플로_액션은_모두_SHA_로_고정돼_있다() throws IOException {
        List<String> hits = new ArrayList<>();
        int seen = 0;
        try (Stream<Path> files = Files.list(DIR)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml")).toList()) {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = USES.matcher(lines.get(i));
                    if (!m.find()) {
                        continue;
                    }
                    seen++;
                    String ref = m.group(1);
                    if (!ref.startsWith("./") && !ref.startsWith("docker://") && !PINNED.matcher(ref).matches()) {
                        hits.add(f.getFileName() + ":" + (i + 1) + "  " + ref);
                    }
                }
            }
        }
        assertThat(seen).as("검사할 uses: 줄이 있어야 한다(경로 확인)").isPositive();
        assertThat(hits).as("액션을 커밋 SHA(40자) + 줄 끝 '# vX.Y.Z' 로 고정").isEmpty();
    }

    @Test
    void 검사기가_실제로_잡는다() {
        assertThat(PINNED.matcher("actions/checkout@v4").matches()).isFalse();
        assertThat(PINNED.matcher("actions/checkout@main").matches()).isFalse();
        assertThat(PINNED.matcher("actions/checkout@11d5960a326750d5838078e36cf38b85af677262").matches()).isTrue();
        assertThat(PINNED.matcher("gradle/actions/setup-gradle@748248ddd2a24f49513d8f472f81c3a07d4d50e1").matches()).isTrue();
        Matcher m = USES.matcher("      - uses: actions/setup-java@v4 # 주석");
        assertThat(m.find()).isTrue();
        assertThat(m.group(1)).isEqualTo("actions/setup-java@v4");
    }
}
