package io.smarthappy.platform.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 비밀값 읽기 함정 경고 (docs/67 F12) — 기동을 막지 않고 경고 로그만.
 * <ul>
 * <li>같은 {@code .env} 를 docker compose 는 compose 규칙(따옴표 제거·{@code $} 보간)으로, 앱은 Java properties 규칙
 *     (따옴표 그대로·{@code \} 이스케이프)으로 읽는다 → 따옴표·{@code $}·역슬래시·앞뒤 공백이 든 비밀번호는
 *     DB 컨테이너가 만든 값과 앱이 보내는 값이 <b>조용히</b> 달라진다(증상은 인증 실패 28P01, 원인은 안 보임).
 *     그런 글자가 든 비밀 설정의 <b>이름만</b> 알린다(값은 로그에 쓰지 않는다).</li>
 * <li>운영(prod)에서 작업 폴더에 {@code .env} 가 있으면 앱이 그 파일을 읽는다(application.yml 의 개발용 가져오기) —
 *     운영 비밀은 환경 파일·서비스 설정으로(deploy/README 1장). 끌지 말지는 정책 질의(docs/67 Q8).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EnvValueCheck implements ApplicationRunner {

    /** 살펴볼 비밀 설정(값이 아니라 이름만 경고) */
    static final List<String> SECRET_KEYS = List.of("spring.datasource.password", "spring.flyway.password", "spring.mail.password",
            "happyboot.dev.admin-password", "happyboot.ops.bootstrap-password", "happyboot.ops.secret-key", "happyboot.mfa.secret-key",
            "happyboot.metrics.token", "happyboot.push.vapid-private-key");

    private final Environment env;

    @Override
    public void run(ApplicationArguments args) {
        boolean prod = env.acceptsProfiles(Profiles.of("prod"));
        for (String w : warnings(env, Files.isRegularFile(Path.of(".env")), prod)) {
            log.warn("{}", w);
        }
    }

    static List<String> warnings(Environment env, boolean envFileHere, boolean prod) {
        List<String> out = new ArrayList<>();
        if (prod && envFileHere) {
            out.add("운영(prod)인데 작업 폴더에 .env 가 있어 앱이 그 값을 읽습니다 — 운영 비밀은 환경 파일·서비스 설정으로 옮기고 .env 를 지우세요"
                    + " (deploy/README 1장, docs/67 F12)");
        }
        for (String key : SECRET_KEYS) {
            String v;
            try {
                v = env.getProperty(key);
            } catch (RuntimeException e) {
                continue;   // 자리 표시자 해석 실패 등 — 여기서는 경고 대상 아님
            }
            if (v != null && !v.isEmpty() && risky(v)) {
                out.add("비밀 설정 " + key + " 값에 따옴표·$·역슬래시·앞뒤 공백이 있습니다 — docker compose 와 앱이 다르게 읽어 값이 달라질 수 있습니다."
                        + " 영문·숫자·-_ 만 쓰세요(openssl rand -hex 24) (docs/67 F12)");
            }
        }
        return out;
    }

    static boolean risky(String v) {
        return v.indexOf('"') >= 0 || v.indexOf('\'') >= 0 || v.indexOf('$') >= 0 || v.indexOf('\\') >= 0
                || !v.equals(v.strip());
    }
}
