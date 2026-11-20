package io.smarthappy.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 가드: Flyway 파일 이름·번호 (docs/04 2.1-d, 4장 6번).
 * 병렬 세션이 같은 번호를 잡으면 git 은 파일명이 달라 충돌로 못 잡는다 → 여기서 잡는다.
 * 하위 세션의 임시 번호(V9000~V9999, docs/29 2.2)는 4자리로 허용 — 병합 때 다음 세 자리 번호로 이름만 바뀐다.
 */
class FlywayMigrationGuardTest {

    private static final Path DIR = Path.of("src/main/resources/db/migration");
    // 하위 세션 임시 번호 V9xxx(docs/29 2.2) — 병합 때 상위 세션이 V### 로 이름만 바꾼다
    private static final Pattern VERSIONED = Pattern.compile("^V(\\d{3}|9\\d{3})__([a-z0-9]+(?:_[a-z0-9]+)*)\\.sql$");
    private static final Pattern REPEATABLE = Pattern.compile("^R__([a-z0-9]+(?:_[a-z0-9]+)*)\\.sql$");

    @Test
    void 마이그레이션_파일_규칙() throws IOException {
        List<String> names;
        try (Stream<Path> files = Files.list(DIR)) {
            names = files.map(p -> p.getFileName().toString()).sorted().toList();
        }
        assertThat(names).isNotEmpty();
        assertThat(problems(names)).isEmpty();
    }

    @Test
    void 규칙_검사기가_실제로_잡는다() {
        assertThat(problems(List.of("V001__a.sql", "V001__b.sql"))).anyMatch(p -> p.contains("중복"));
        assertThat(problems(List.of("V1__a.sql"))).anyMatch(p -> p.contains("이름 규칙"));
        assertThat(problems(List.of("V002__Add-Table.sql"))).anyMatch(p -> p.contains("이름 규칙"));
        assertThat(problems(List.of("v003__a.sql"))).anyMatch(p -> p.contains("이름 규칙"));
        assertThat(problems(List.of("V001__a.sql", "R__seed_menu.sql"))).isEmpty();
        assertThat(problems(List.of("V036__a.sql", "V9800__home.sql"))).isEmpty();
        assertThat(problems(List.of("V1000__a.sql"))).anyMatch(p -> p.contains("이름 규칙"));
        assertThat(problems(List.of("V9101__a.sql", "V9101__b.sql"))).anyMatch(p -> p.contains("중복"));
    }

    static List<String> problems(List<String> names) {
        List<String> problems = new ArrayList<>();
        Map<String, String> byVersion = new HashMap<>();
        for (String name : names) {
            Matcher v = VERSIONED.matcher(name);
            if (v.matches()) {
                String prev = byVersion.put(v.group(1), name);
                if (prev != null) {
                    problems.add("버전 번호 중복 V" + v.group(1) + ": " + prev + ", " + name);
                }
            } else if (!REPEATABLE.matcher(name).matches()) {
                problems.add("이름 규칙 위반(V###__소문자_설명.sql 또는 R__소문자_설명.sql): " + name);
            }
        }
        return problems;
    }
}
