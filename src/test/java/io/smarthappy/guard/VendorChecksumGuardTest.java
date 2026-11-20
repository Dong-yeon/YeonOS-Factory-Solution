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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 가드: static/vendor 공급망 (docs/10 11.1 H5, docs/05 공급망). Gradle 체크섬 검증 대상이 아닌 브라우저 라이브러리를 대신 지킨다.
 * - SHA256SUMS 에 적힌 파일의 SHA-256 이 일치한다 (누가 고치거나 바꿔치면 실패)
 * - vendor 의 모든 파일이 목록에 있다 (README.md·SHA256SUMS 제외) — 목록 없이 추가된 파일 금지
 * - 라이브러리 폴더마다 라이선스 파일이 있다
 * - 상용 Kendo 폴더(vendor/kendo)는 없다
 */
class VendorChecksumGuardTest {

    private static final Path VENDOR = Path.of("src/main/resources/static/vendor");
    private static final Set<String> NOT_LISTED = Set.of("README.md", "SHA256SUMS");

    @Test
    void 체크섬이_일치하고_목록_밖_파일이_없다() throws IOException {
        List<String> problems = new ArrayList<>();
        Set<String> listed = new TreeSet<>();
        for (String line : Files.readAllLines(VENDOR.resolve("SHA256SUMS"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            // sha256sum 출력: "<hex> *<path>" 또는 "<hex>  <path>"
            String hash = line.substring(0, 64);
            String rel = line.substring(64).trim().replaceFirst("^\\*", "");
            listed.add(rel);
            Path f = VENDOR.resolve(rel);
            if (!Files.isRegularFile(f)) {
                problems.add("목록에 있는데 파일 없음: " + rel);
            } else if (!hash.equalsIgnoreCase(sha256(f))) {
                problems.add("체크섬 불일치: " + rel);
            }
        }
        try (Stream<Path> files = Files.walk(VENDOR)) {
            files.filter(Files::isRegularFile)
                    .map(p -> VENDOR.relativize(p).toString().replace('\\', '/'))
                    .filter(rel -> !NOT_LISTED.contains(rel))
                    .filter(rel -> !listed.contains(rel))
                    .forEach(rel -> problems.add("SHA256SUMS 에 없는 파일: " + rel));
        }
        assertThat(listed).as("목록이 비면 가드가 무의미").isNotEmpty();
        assertThat(problems).isEmpty();
    }

    @Test
    void 라이브러리마다_라이선스가_있고_상용_Kendo_는_없다() throws IOException {
        assertThat(VENDOR.resolve("kendo")).as("상용 Kendo 는 커밋 금지 (.gitignore)").doesNotExist();
        List<String> missing = new ArrayList<>();
        try (Stream<Path> libs = Files.list(VENDOR).filter(Files::isDirectory)) {
            for (Path lib : libs.toList()) {
                try (Stream<Path> versions = Files.list(lib).filter(Files::isDirectory)) {
                    for (Path v : versions.toList()) {
                        try (Stream<Path> fs = Files.list(v)) {
                            if (fs.noneMatch(p -> p.getFileName().toString().toUpperCase().startsWith("LICENSE"))) {
                                missing.add(VENDOR.relativize(v).toString());
                            }
                        }
                    }
                }
            }
        }
        assertThat(missing).as("LICENSE 없는 vendor 폴더").isEmpty();
    }

    private static String sha256(Path f) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
