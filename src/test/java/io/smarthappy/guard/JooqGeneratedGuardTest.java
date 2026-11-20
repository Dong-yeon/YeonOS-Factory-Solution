package io.smarthappy.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.jooq.JooqCodegen;

/**
 * 가드: 커밋된 jOOQ 생성물(src/main/jooq)이 지금 Flyway 스키마와 같다 (docs/43).
 * 시험 DB(모든 V 파일을 올린 것)로 생성기를 다시 돌려 파일 목록·내용을 비교한다 — 어긋나면 ./gradlew jooqGenerate 후 커밋
 */
class JooqGeneratedGuardTest extends PostgresTestBase {

    private static Map<String, String> read(Path root) throws IOException {
        Map<String, String> m = new TreeMap<>();
        if (!Files.exists(root)) {
            return m;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                m.put(root.relativize(f).toString().replace('\\', '/'),
                        Files.readString(f, StandardCharsets.UTF_8).replace("\r\n", "\n"));
            }
        }
        return m;
    }

    @Test
    void 생성물이_스키마와_같다(@TempDir Path tmp) throws Exception {
        JooqCodegen.generate(jdbcUrl(), OWNER_USER, testPassword(), tmp);
        Map<String, String> fresh = read(tmp);
        Map<String, String> committed = read(Path.of(JooqCodegen.TARGET_DIR));
        assertThat(fresh).as("생성물이 비었다 — 생성 대상(INCLUDES)·스키마 확인").isNotEmpty();
        assertThat(committed.keySet()).as("생성 파일 목록이 다르다 — ./gradlew jooqGenerate 후 커밋").isEqualTo(fresh.keySet());
        for (Map.Entry<String, String> e : fresh.entrySet()) {
            assertThat(committed.get(e.getKey())).as(e.getKey() + " 가 스키마와 다르다 — ./gradlew jooqGenerate 후 커밋")
                    .isEqualTo(e.getValue());
        }
    }
}
