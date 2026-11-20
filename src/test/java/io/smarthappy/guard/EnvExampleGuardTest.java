package io.smarthappy.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.smarthappy.platform.tenant.ProdStartupGuard;

/**
 * 가드: 예시 환경 파일(.env.example)에 실제 비밀값이 들어가지 않는다 (docs/67 F1·F12).
 * <ul>
 * <li>이름이 비밀(PASSWORD·SECRET·PRIVATE_KEY·TOKEN·CIPHER_PASS)인 칸은 <b>비었거나 {@code change-me} 로 시작</b>해야 한다
 *     — 그래야 운영 기동 가드({@link ProdStartupGuard#isExampleSecret})가 그 값을 예시로 알아보고 운영 기동을 막는다.</li>
 * <li>값에 따옴표·{@code $}·역슬래시를 쓰지 않는다 — compose 와 Spring(.properties)이 서로 다르게 읽어 값이 조용히 달라진다.</li>
 * </ul>
 * 이 시험이 깨지면 예시 파일의 값을 되돌리고, 실제 값은 커밋하지 않는 {@code .env} 에만 둔다.
 */
class EnvExampleGuardTest {

    static final List<Path> FILES = List.of(Path.of(".env.example"), Path.of("deploy/demo/.env.example"));

    /** 비밀로 보는 칸 이름 */
    static final Pattern SECRET_NAME = Pattern.compile(".*(PASSWORD|SECRET|PRIVATE_KEY|TOKEN|CIPHER_PASS).*");

    /** 공개하기로 정한 값 — 공개 데모 체험 계정(docs/23: 로그인 화면·README 에 공개) */
    static final Map<Path, Set<String>> PUBLIC_BY_DESIGN = Map.of(Path.of("deploy/demo/.env.example"), Set.of("DEMO_PASSWORD"));

    private static final Pattern LINE = Pattern.compile("^\\s*(?:export\\s+)?([A-Z][A-Z0-9_]*)=(.*)$");

    @Test
    void 예시_파일의_비밀_칸은_비었거나_예시_값이다() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path f : FILES) {
            problems.addAll(problems(f, Files.readAllLines(f, StandardCharsets.UTF_8)));
        }
        assertThat(problems).as("예시 파일에 실제 값처럼 보이는 비밀이 있습니다 — 값을 비우거나 change-me… 로").isEmpty();
    }

    @Test
    void 규칙_자체를_확인한다() {
        Path f = Path.of("x.env.example");
        assertThat(problems(f, List.of("DB_APP_PASSWORD=", "DB_APP_PASSWORD=change-me-app", "OPS_SECRET_KEY=",
                "HAPPYBOOT_PUSH_VAPID_PUBLIC_KEY=BAbc", "DB_PORT=5433", "# DB_APP_PASSWORD=whatever 주석"))).isEmpty();
        assertThat(problems(f, List.of("DEV_ADMIN_PASSWORD=Realistic123"))).hasSize(1);
        assertThat(problems(f, List.of("METRICS_TOKEN=abcdefabcdefabcdefabcdefabcdefab"))).hasSize(1);
        assertThat(problems(f, List.of("HAPPYBOOT_PUSH_VAPID_PRIVATE_KEY=xyz"))).hasSize(1);
        assertThat(problems(f, List.of("PGBACKREST_REPO1_CIPHER_PASS=xyz"))).hasSize(1);
        // 값을 다르게 읽는 글자
        assertThat(problems(f, List.of("DB_APP_PASSWORD=\"change-me\""))).hasSize(1);
        assertThat(problems(f, List.of("SMTP_HOST=a$b"))).hasSize(1);
        assertThat(problems(f, List.of("SMTP_HOST=a\\b"))).hasSize(1);
        // 공개하기로 정한 값은 파일·이름이 정확히 맞을 때만
        assertThat(problems(Path.of("deploy/demo/.env.example"), List.of("DEMO_PASSWORD=public-demo-pw"))).isEmpty();
        assertThat(problems(Path.of(".env.example"), List.of("DEMO_PASSWORD=public-demo-pw"))).hasSize(1);
    }

    @Test
    void 운영_가드는_예시_접두어를_모두_예시로_본다() {
        assertThat(ProdStartupGuard.isExampleSecret("change-me-app")).isTrue();
        assertThat(ProdStartupGuard.isExampleSecret(" Change-Me-새값 ")).isTrue();
        assertThat(ProdStartupGuard.isExampleSecret("postgres")).isTrue();
        assertThat(ProdStartupGuard.isExampleSecret("k2Jd9-random-value-xx")).isFalse();
        assertThat(ProdStartupGuard.isExampleSecret(null)).isFalse();
    }

    static List<String> problems(Path file, List<String> lines) {
        List<String> out = new ArrayList<>();
        Set<String> allowed = PUBLIC_BY_DESIGN.getOrDefault(file, Set.of());
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = LINE.matcher(lines.get(i));
            if (!m.matches()) {
                continue;   // 주석·빈 줄
            }
            String name = m.group(1);
            String value = m.group(2).strip();
            String where = file + ":" + (i + 1) + " " + name;   // 값은 메시지에 싣지 않는다
            if (value.indexOf('"') >= 0 || value.indexOf('\'') >= 0 || value.indexOf('$') >= 0 || value.indexOf('\\') >= 0) {
                out.add(where + " — 따옴표·$·역슬래시 금지(compose 와 Spring 이 다르게 읽음)");
                continue;
            }
            if (SECRET_NAME.matcher(name).matches() && !value.isEmpty() && !allowed.contains(name)
                    && !value.toLowerCase(java.util.Locale.ROOT).startsWith("change-me")) {
                out.add(where + " — 비밀 칸은 비우거나 change-me… 예시 값만");
            }
        }
        return out;
    }
}
