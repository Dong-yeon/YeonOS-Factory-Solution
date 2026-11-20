package io.smarthappy.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/**
 * local 프로필: 화면 JS 를 src 에서 읽으므로 JS 문구도 src 의 properties 에서 (2026-10-22 "모두 접기" 키 노출 보고).
 * 빌드된 classpath 문구가 옛것이어도 src 에 새 키가 있으면 바로 나온다.
 */
class ShellMessagesDevDirTest {

    @TempDir
    Path dir;

    private Map<String, String> messages(MockEnvironment env, Locale locale) {
        return new ShellApiController(null, env).messages(locale).data();
    }

    @Test
    void 개발_문구_폴더가_있으면_src_의_js_문구를_언어_파일이_덮는다() throws Exception {
        Files.writeString(dir.resolve("messages.properties"), "js.new.key=새 문구\njs.both=기본\nserver.only=안 보냄\nhelp.x=도움\n",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("messages_en.properties"), "js.both=English\n", StandardCharsets.UTF_8);
        MockEnvironment env = new MockEnvironment().withProperty("happyboot.dev.messages-dir", dir.toString());
        assertThat(messages(env, Locale.KOREAN)).containsEntry("js.new.key", "새 문구").containsEntry("js.both", "기본")
                .containsEntry("help.x", "도움").doesNotContainKey("server.only");
        assertThat(messages(env, Locale.ENGLISH)).containsEntry("js.both", "English").containsEntry("js.new.key", "새 문구");
    }

    @Test
    void 설정이_없거나_폴더에_파일이_없으면_classpath_문구() {
        assertThat(messages(new MockEnvironment(), Locale.KOREAN)).containsEntry("js.shell.foldAll", "모두 접기");
        MockEnvironment empty = new MockEnvironment().withProperty("happyboot.dev.messages-dir", dir.toString());
        assertThat(messages(empty, Locale.KOREAN)).containsKey("js.shell.menuSearch");
    }
}
