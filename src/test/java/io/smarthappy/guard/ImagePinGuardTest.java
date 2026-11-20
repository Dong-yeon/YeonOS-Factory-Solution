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
 * 가드: 배포 묶음(deploy/)의 컨테이너 이미지는 태그 + 다이제스트로 고정하고, 데모 compose 의 모든 서비스는 강화 설정을 받는다
 * (docs/67 F10 — Actions 를 커밋 SHA 로 고정하는 WorkflowPinGuardTest 와 같은 결).
 * <ul>
 * <li>{@code image:} · Dockerfile {@code FROM} · 스크립트 기본 이미지({@code IMAGE="${...:-이미지}"})는 {@code 이름:태그@sha256:64자}</li>
 * <li>이 저장소가 직접 빌드하는 이미지(compose {@code build:} 와 같은 블록의 {@code image:})만 예외</li>
 * <li>demo compose 의 서비스마다 {@code <<: *hardening} 또는 {@code <<: *app}(그 안에 hardening) — no-new-privileges + cap_drop ALL</li>
 * </ul>
 */
class ImagePinGuardTest {

    static final Path DEPLOY = Path.of("deploy");
    static final Pattern PINNED = Pattern.compile("^[a-z0-9./_-]+:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}$");
    /** 직접 빌드하는 이미지 이름 */
    static final Set<String> BUILT_HERE = Set.of("yeonos-demo:current");

    private static final Pattern IMAGE = Pattern.compile("^\\s*image:\\s*(\\S+)");
    private static final Pattern FROM = Pattern.compile("^\\s*FROM\\s+(?:--platform=\\S+\\s+)?(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_DEFAULT = Pattern.compile("IMAGE=\"\\$\\{[A-Z_]+:-([^}]+)}\"");

    @Test
    void 배포_묶음의_이미지는_다이제스트로_고정() throws IOException {
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(DEPLOY)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString();
                boolean compose = name.endsWith(".yml") || name.endsWith(".yaml");
                boolean docker = name.equals("Dockerfile") || name.endsWith(".Dockerfile");
                boolean script = name.endsWith(".sh") || name.endsWith(".ps1");
                if (!compose && !docker && !script) {
                    continue;
                }
                problems.addAll(problems(f, Files.readAllLines(f, StandardCharsets.UTF_8), compose, docker, script));
            }
        }
        assertThat(problems).as("태그만 쓴 이미지 — `docker buildx imagetools inspect 이미지:태그` 의 Digest 를 붙이세요").isEmpty();
    }

    @Test
    void 데모_compose_의_서비스는_모두_강화_설정을_받는다() throws IOException {
        List<String> lines = Files.readAllLines(DEPLOY.resolve("demo/compose.yml"), StandardCharsets.UTF_8);
        String all = String.join("\n", lines);
        assertThat(all).contains("x-hardening: &hardening").contains("no-new-privileges:true").contains("cap_drop: [ALL]");
        assertThat(block(lines, "x-app")).as("x-app 은 hardening 을 받는다").contains("<<: *hardening");
        assertThat(unhardenedServices(lines)).isEmpty();
    }

    @Test
    void 규칙_자체를_확인한다() {
        Path f = Path.of("deploy/x/compose.yml");
        assertThat(problems(f, List.of("    image: postgres:16-alpine@sha256:" + "a".repeat(64)), true, false, false)).isEmpty();
        assertThat(problems(f, List.of("    image: postgres:16-alpine"), true, false, false)).hasSize(1);
        assertThat(problems(f, List.of("    image: postgres@sha256:" + "a".repeat(64)), true, false, false)).as("태그도 남긴다").hasSize(1);
        assertThat(problems(f, List.of("  build: .", "  image: yeonos-demo:current"), true, false, false)).isEmpty();
        assertThat(problems(Path.of("Dockerfile"), List.of("FROM eclipse-temurin:25-jre"), false, true, false)).hasSize(1);
        assertThat(problems(Path.of("r.sh"), List.of("PGIMAGE=\"${PGIMAGE:-postgres:16-alpine}\""), false, false, true)).hasSize(1);
        assertThat(problems(Path.of("r.sh"), List.of("PGIMAGE=\"${PGIMAGE:-postgres:16-alpine@sha256:" + "b".repeat(64) + "}\""),
                false, false, true)).isEmpty();
        assertThat(unhardenedServices(List.of("services:", "  db:", "    image: x", "  app:", "    <<: *app", "networks:"))).containsExactly("db");
    }

    static List<String> problems(Path f, List<String> lines, boolean compose, boolean docker, boolean script) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.strip().startsWith("#")) {
                continue;
            }
            String ref = null;
            Matcher m;
            if (compose && (m = IMAGE.matcher(line)).find()) {
                ref = m.group(1).replace("\"", "");
            } else if (docker && (m = FROM.matcher(line)).find()) {
                ref = m.group(1);
            } else if (script && (m = SCRIPT_DEFAULT.matcher(line)).find()) {
                ref = m.group(1).strip();
            }
            if (ref == null || ref.contains("${") || BUILT_HERE.contains(ref) || PINNED.matcher(ref).matches()) {
                continue;
            }
            out.add(f + ":" + (i + 1) + " " + ref);
        }
        return out;
    }

    /** services: 아래 서비스 중 hardening 을 받지 않는 이름 */
    static List<String> unhardenedServices(List<String> lines) {
        List<String> out = new ArrayList<>();
        boolean in = false;
        String current = null;
        boolean ok = false;
        Pattern service = Pattern.compile("^  ([A-Za-z0-9_-]+):\\s*$");
        for (String line : lines) {
            if (line.matches("^services:\\s*$")) {
                in = true;
                continue;
            }
            if (!in) {
                continue;
            }
            if (line.matches("^\\S.*")) {   // 다음 최상위 키 — services 끝
                break;
            }
            Matcher svc = service.matcher(line);
            if (svc.matches()) {
                if (current != null && !ok) {
                    out.add(current);
                }
                current = svc.group(1);
                ok = false;
            } else if (line.contains("<<: *hardening") || line.contains("<<: *app")) {
                ok = true;
            }
        }
        if (current != null && !ok) {
            out.add(current);
        }
        return out;
    }

    private static String block(List<String> lines, String key) {
        StringBuilder b = new StringBuilder();
        boolean in = false;
        for (String line : lines) {
            if (line.startsWith(key + ":")) {
                in = true;
                continue;
            }
            if (in && line.matches("^\\S.*")) {
                break;
            }
            if (in) {
                b.append(line).append('\n');
            }
        }
        return b.toString();
    }
}
