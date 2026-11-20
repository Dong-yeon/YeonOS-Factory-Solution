package io.smarthappy.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** docs/67 F12 — 비밀값 읽기 함정 경고(값은 메시지에 싣지 않는다) */
class EnvValueCheckTest {

    @Test
    void 따옴표_달러_역슬래시_앞뒤_공백이_든_비밀은_이름만_경고() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.password", "pa$$word")
                .withProperty("spring.flyway.password", "\"quoted\"")
                .withProperty("happyboot.mfa.secret-key", "abc\\def")
                .withProperty("spring.mail.password", "trailing ")
                .withProperty("happyboot.metrics.token", "fine-hex-0123456789abcdef");
        List<String> w = EnvValueCheck.warnings(env, false, false);
        assertThat(w).hasSize(4)
                .anyMatch(s -> s.contains("spring.datasource.password"))
                .anyMatch(s -> s.contains("spring.flyway.password"))
                .anyMatch(s -> s.contains("happyboot.mfa.secret-key"))
                .anyMatch(s -> s.contains("spring.mail.password"))
                .noneMatch(s -> s.contains("happyboot.metrics.token"));
        // 값 자체는 어디에도 나오지 않는다
        assertThat(String.join("\n", w)).doesNotContain("pa$$word").doesNotContain("quoted").doesNotContain("abc\\def");
    }

    @Test
    void 평범한_값_빈_값_Base64_키는_조용하다() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.password", "k2Jd9_random-0123")
                .withProperty("happyboot.ops.secret-key", "dGVzdC1vbmx5LW9wcy1rZXktMzItYnl0ZXMtLS0tLS0=")
                .withProperty("spring.mail.password", "");
        assertThat(EnvValueCheck.warnings(env, false, false)).isEmpty();
    }

    @Test
    void 운영에서_작업_폴더_env_가_있으면_경고_개발은_조용() {
        MockEnvironment env = new MockEnvironment();
        assertThat(EnvValueCheck.warnings(env, true, true)).singleElement().asString().contains(".env");
        assertThat(EnvValueCheck.warnings(env, true, false)).isEmpty();
        assertThat(EnvValueCheck.warnings(env, false, true)).isEmpty();
    }
}
