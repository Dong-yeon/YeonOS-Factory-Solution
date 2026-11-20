package io.smarthappy.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 가드: 적용된 Flyway 마이그레이션(V###)은 바뀌지 않는다 (CLAUDE.md DB 규칙, 사용자 결정 G-1 2026-11-01).
 * 주석 한 글자라도 바뀌면 Flyway 체크섬이 달라져 이미 그 파일을 돌린 DB(로컬·운영)가 기동 검증에서 실패한다 — #717 의 V172 주석 변경이 계기.
 * 목록 = src/test/resources/guard/applied-migrations.sha256(병합 세션이 재번호 때 줄을 덧붙인다). 해시는 BOM 을 빼고 CRLF → LF 로 맞춘 내용 —
 * Windows checkout(text=auto)에서도 같은 값. 하위 세션 임시 V9xxx·R__ 는 대상 아님(FlywayMigrationGuardTest 가 이름 규칙을 본다)
 */
class AppliedMigrationGuardTest {

    private static final Path DIR = Path.of("src/main/resources/db/migration");
    private static final Path LIST = Path.of("src/test/resources/guard/applied-migrations.sha256");
    private static final Pattern APPLIED = Pattern.compile("^V\\d{3}__.+\\.sql$");

    @Test
    void 적용된_마이그레이션은_바뀌지_않고_모두_목록에_있다() throws IOException {
        Map<String, String> listed = readList(Files.readAllLines(LIST, StandardCharsets.UTF_8));
        Map<String, String> actual = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(DIR)) {
            for (Path p : files.sorted().toList()) {
                String name = p.getFileName().toString();
                if (APPLIED.matcher(name).matches()) {
                    actual.put(name, sha256(Files.readAllBytes(p)));
                }
            }
        }
        assertThat(listed).isNotEmpty();
        assertThat(problems(listed, actual)).isEmpty();
    }

    @Test
    void 검사기가_실제로_잡는다() {
        String h = sha256("select 1;\n".getBytes(StandardCharsets.UTF_8));
        // 줄바꿈·BOM 차이는 같은 파일
        assertThat(sha256("select 1;\r\n".getBytes(StandardCharsets.UTF_8))).isEqualTo(h);
        assertThat(sha256(("﻿" + "select 1;\n").getBytes(StandardCharsets.UTF_8))).isEqualTo(h);
        // 주석 한 글자도 다른 파일
        assertThat(sha256("select 1; -- x\n".getBytes(StandardCharsets.UTF_8))).isNotEqualTo(h);

        assertThat(problems(Map.of("V001__a.sql", h), Map.of("V001__a.sql", h))).isEmpty();
        assertThat(problems(Map.of("V001__a.sql", h), Map.of("V001__a.sql", "0".repeat(64))))
                .anyMatch(p -> p.contains("바뀜") && p.contains("V001__a.sql"));
        assertThat(problems(Map.of("V001__a.sql", h), Map.of())).anyMatch(p -> p.contains("없어짐"));
        assertThat(problems(Map.of(), Map.of("V002__b.sql", h))).anyMatch(p -> p.contains("목록에 없음") && p.contains(h));
        assertThat(readList(List.of("# 주석", "", h + "  V003__c.sql"))).containsEntry("V003__c.sql", h);
    }

    static Map<String, String> readList(List<String> lines) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String line : lines) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) {
                continue;
            }
            String[] parts = s.split("\\s+", 2);
            m.put(parts[1], parts[0]);
        }
        return m;
    }

    static List<String> problems(Map<String, String> listed, Map<String, String> actual) {
        List<String> problems = new ArrayList<>();
        listed.forEach((name, hash) -> {
            String now = actual.get(name);
            if (now == null) {
                problems.add("적용된 마이그레이션이 없어짐: " + name);
            } else if (!now.equals(hash)) {
                problems.add("적용된 마이그레이션이 바뀜(새 V 파일로 고칠 것): " + name);
            }
        });
        actual.forEach((name, hash) -> {
            if (!listed.containsKey(name)) {
                problems.add("목록에 없음 — 재번호 때 " + LIST + " 에 줄 추가: " + hash + "  " + name);
            }
        });
        return problems;
    }

    /** BOM 을 빼고 CRLF → LF 로 맞춘 내용의 SHA-256(소문자 16진) */
    static String sha256(byte[] raw) {
        String s = new String(raw, StandardCharsets.UTF_8);
        if (s.startsWith("﻿")) {
            s = s.substring(1);
        }
        s = s.replace("\r\n", "\n");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
