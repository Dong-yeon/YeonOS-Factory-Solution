package io.smarthappy.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 가드: 업무 모듈은 sys_user 를 직접 골라 쓰지 않는다 — 담당자·소유자·결재자 고르기와 아이디 확인은 공통 사용자 고르기
 * {@code UserDirectory}(회사 사용자 INTERNAL 만, docs/42 15장)로. 협력사 포털 사용자가 후보·담당자로 끼어드는 길을 막는다.
 * 본다: 모듈 매퍼 XML·모듈 자바의 {@code from sys_user}(SQL) · {@code .from(SYS_USER)}·{@code = SYS_USER;}(jOOQ).
 * 허용: 저장된 id 의 이름을 보여 주는 조인({@code join sys_user}) — 고르기가 아니다. 플랫폼·시스템관리(사용자 관리 자체)는 범위 밖.
 * 예외는 {@link #ALLOWED} 에 파일과 근거.
 */
class UserPickGuardTest {

    private static final Path MAPPERS = Path.of("src/main/resources/mapper");
    private static final Path MODULES = Path.of("src/main/java/io/smarthappy/module");
    /** 사용자 관리 자체(시스템관리)와 플랫폼은 범위 밖 */
    private static final List<String> OUT_OF_SCOPE = List.of("platform/", "admin/");
    static final Pattern SQL_FROM = Pattern.compile("(?i)\\bfrom\\s+sys_user\\b");
    static final Pattern JOOQ_FROM = Pattern.compile("\\.from\\(\\s*SYS_USER\\b|=\\s*SYS_USER\\s*;");
    /** 파일(각 뿌리 기준 상대 경로) → 근거. 새 항목은 고르기가 아님을 설명할 수 있을 때만 */
    static final Map<String, String> ALLOWED = Map.of(
            "cms/CmsRiskMapper.xml", "재평가로 항목을 이어받을 때 기존 담당자 id 를 사용 중이면 그대로 — 새로 고르는 것이 아니다(담당자는 고를 때 이미 공통 고르기로 확인)",
            "pms/PmsDemoSeeder.java", "local·demo 시드: 데모 회사 관리자 한 명을 행위자로 — 업무 고르기 아님",
            "cms/CmsDemoSeeder.java", "local·demo 시드: 데모 회사 관리자 한 명을 행위자로 — 업무 고르기 아님(PmsDemoSeeder 와 같음)",
            "qms/QmsDemoSeeder.java", "local 시드: 데모 회사 관리자 한 명을 행위자로(PmsDemoSeeder 와 같음) — 업무 고르기 아님",
            "demo/DemoAccountSeeder.java", "demo 프로필 체험 계정 만들기 — 계정 자체를 다룸",
            "demo/DemoSeedSupport.java", "local·demo 시드 공통: 데모 회사 관리자·시드 행위자 계정을 찾거나 만든다 — 업무 고르기 아님(각 모듈 데모 시더가 쓰던 것을 모음)");

    @Test
    void 업무_모듈은_sys_user_를_직접_골라_쓰지_않는다() throws IOException {
        List<String> hits = new ArrayList<>();
        hits.addAll(violations(MAPPERS, ".xml", SQL_FROM));
        hits.addAll(violations(MODULES, ".java", SQL_FROM));
        hits.addAll(violations(MODULES, ".java", JOOQ_FROM));
        assertThat(hits).as("공통 사용자 고르기 UserDirectory 를 쓴다(회사 사용자만) — 이름 표시는 join sys_user 로").isEmpty();
    }

    @Test
    void 허용_목록은_실제로_있는_파일만() {
        for (String f : ALLOWED.keySet()) {
            boolean exists = Files.exists(MAPPERS.resolve(f)) || Files.exists(MODULES.resolve(f));
            assertThat(exists).as("허용 목록에 없는 파일이 남았다(지웠으면 목록에서도): " + f).isTrue();
        }
    }

    @Test
    void 가드_자체_시험(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("qms"));
        Files.writeString(dir.resolve("qms/Bad.xml"), "<select>select user_id from sys_user where use_yn = 'Y'</select>",
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("qms/Display.xml"),
                "<select>select l.seq, u.user_nm from apr_line l join sys_user u on u.user_id = l.approver_id</select>"
                        + "<select>select 1 from sys_user_auth</select>", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("qms/Jooq.java"), "var u = SYS_USER; dsl.selectFrom(x).from(SYS_USER);",
                StandardCharsets.UTF_8);
        assertThat(violations(dir, ".xml", SQL_FROM)).containsExactly("qms/Bad.xml");
        assertThat(violations(dir, ".java", JOOQ_FROM)).containsExactly("qms/Jooq.java");
    }

    static List<String> violations(Path root, String suffix, Pattern pattern) throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(suffix)).sorted().toList()) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                if (OUT_OF_SCOPE.stream().anyMatch(rel::startsWith) || ALLOWED.containsKey(rel)) {
                    continue;
                }
                String text = Files.readString(f, StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
                if (pattern.matcher(text).find()) {
                    out.add(rel);
                }
            }
        }
        return out;
    }
}
