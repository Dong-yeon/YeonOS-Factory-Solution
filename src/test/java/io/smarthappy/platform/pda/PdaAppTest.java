package io.smarthappy.platform.pda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.util.FileSystemUtils;

import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 현장 단말 APK 다운로드 (docs/50 4장 D4) — 고정 파일 두 개, 서버가 계산한 크기·SHA-256, release.json 허용 칸·검사,
 * 공개 버전 JSON 세 칸, 권한(로그인·화면·포털 경계), 링크·경로 조작, 바꿔치기, A사로 B사(전역 파일·접속 로그는 회사별).
 */
@AutoConfigureMockMvc
class PdaAppTest extends PostgresTestBase {

    /** application-test.yml happyboot.pda.dir */
    static final Path DIR = Path.of("build/test-pda");
    static final String API = "/api/platform/pda-app";
    static final String OK_RELEASE = """
            {"version":"1.2.0","minVersion":"1.1.0","releasedAt":"2026-11-01","notesKo":"스캔 수정\\n둘째 줄","notesEn":"Scan fix",
             "sha256":"0000","path":"/etc/passwd"}""";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    PasswordEncoder encoder;

    String comp;
    long admin;
    long plain;
    byte[] apk = "PK-fake-apk-bytes-1".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() throws Exception {
        FileSystemUtils.deleteRecursively(DIR);
        Files.createDirectories(DIR);
        comp = accounts.newComp("PDA");
        admin = accounts.newUser(comp, "adm", "Quiet-river-77");
        plain = accounts.newUser(comp, "plain", "Quiet-river-77");
        accounts.grant(comp, admin, "PDAADM", PdaAppService.PROG, "SEARCH");
    }

    @AfterEach
    void clean() throws Exception {
        FileSystemUtils.deleteRecursively(DIR);
    }

    private void place(String release, byte[] bytes) throws Exception {
        if (release != null) {
            Files.writeString(DIR.resolve(PdaAppService.RELEASE), release);
        }
        if (bytes != null) {
            Files.write(DIR.resolve(PdaAppService.APK), bytes);
        }
    }

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private RequestPostProcessor adm() {
        return accounts.as(comp, admin, "adm");
    }

    private int downloadLogs(String compCd) {
        return accounts.query(compCd, """
                select count(*)::int from sys_access_log where event_cd = 'FILE_DOWNLOAD' and prog_cd = 'sys-pda-app'""",
                Integer.class);
    }

    @Test
    void 정상_서버가_계산한_크기_해시_내려받기_접속_로그() throws Exception {
        place(OK_RELEASE, apk);
        mvc.perform(get(API).with(adm())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("OK"))
                .andExpect(jsonPath("$.data.version").value("1.2.0"))
                .andExpect(jsonPath("$.data.minVersion").value("1.1.0"))
                .andExpect(jsonPath("$.data.releasedAt").value("2026-11-01"))
                .andExpect(jsonPath("$.data.notes").value("스캔 수정\n둘째 줄"))
                .andExpect(jsonPath("$.data.sizeBytes").value(apk.length))
                .andExpect(jsonPath("$.data.sha256").value(sha(apk)))   // release.json 의 "sha256" 은 무시
                .andExpect(jsonPath("$.data.path").doesNotExist());
        mvc.perform(get(API).with(accounts.as(comp, admin, "adm", "en")))
                .andExpect(jsonPath("$.data.notes").value("Scan fix"));

        int before = downloadLogs(comp);
        byte[] got = mvc.perform(get(API + "/download").with(adm())).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", PdaAppController.APK_TYPE))
                .andExpect(header().string("Content-Length", String.valueOf(apk.length)))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("yeonos-pda-1.2.0.apk")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(got).isEqualTo(apk);
        assertThat(downloadLogs(comp)).isEqualTo(before + 1);   // 한 번 받으면 정확히 +1
        assertThat(accounts.query(comp, """
                select detail from sys_access_log where event_cd = 'FILE_DOWNLOAD' and prog_cd = 'sys-pda-app'
                 order by log_id desc limit 1""", String.class))
                .isEqualTo("pda-apk version=1.2.0 sha256=" + sha(apk).substring(0, 12));
    }

    @Test
    void 공개_버전_JSON_은_로그인_없이_세_칸만() throws Exception {
        place(OK_RELEASE, apk);
        mvc.perform(get("/pda/version.json")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.version").value("1.2.0"))
                .andExpect(jsonPath("$.minVersion").value("1.1.0"))
                .andExpect(jsonPath("$.releasedAt").value("2026-11-01"))
                .andExpect(jsonPath("$.notesKo").doesNotExist())
                .andExpect(jsonPath("$.sha256").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist())
                .andExpect(jsonPath("$.length()").value(3));
        // APK 가 없어도 버전 확인은 된다(껍데기는 최소 버전만 본다)
        Files.delete(DIR.resolve(PdaAppService.APK));
        mvc.perform(get("/pda/version.json")).andExpect(jsonPath("$.minVersion").value("1.1.0"));
        // release.json 이 틀리면 빈 값 — 옛 값·틀린 값을 내보내지 않는다
        place("{\"version\":\"1.2\",\"minVersion\":\"1.1.0\",\"releasedAt\":\"2026-11-01\"}", null);
        mvc.perform(get("/pda/version.json")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 파일이_없으면_안내만_내려받기_409() throws Exception {
        mvc.perform(get(API).with(adm())).andExpect(jsonPath("$.data.state").value("MISSING"))
                .andExpect(jsonPath("$.data.sha256").doesNotExist());
        mvc.perform(get(API + "/download").with(adm())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PDA_APP_NOT_READY"));
        mvc.perform(get("/pda/version.json")).andExpect(jsonPath("$.length()").value(0));
        // release.json 만 있고 APK 없음 → 버전은 보이되 내려받기 막힘
        place(OK_RELEASE, null);
        mvc.perform(get(API).with(adm())).andExpect(jsonPath("$.data.state").value("MISSING"))
                .andExpect(jsonPath("$.data.version").value("1.2.0"));
        mvc.perform(get(API + "/download").with(adm())).andExpect(status().isConflict());
        // APK 만 있고 release.json 없음 → 막힘
        Files.delete(DIR.resolve(PdaAppService.RELEASE));
        place(null, apk);
        mvc.perform(get(API + "/download").with(adm())).andExpect(status().isConflict());
        assertThat(downloadLogs(comp)).isZero();
    }

    @Test
    void release_json_이_틀리면_INVALID_내려받기_막힘() throws Exception {
        place(null, apk);
        List<String> bad = List.of(
                "not json",
                "[]",
                "{\"version\":\"1.2.0\",\"minVersion\":\"1.1.0\"}",                                   // 배포일 없음
                "{\"version\":\"1.2\",\"minVersion\":\"1.1.0\",\"releasedAt\":\"2026-11-01\"}",          // semver 아님
                "{\"version\":\"v1.2.0\",\"minVersion\":\"1.1.0\",\"releasedAt\":\"2026-11-01\"}",
                "{\"version\":\"01.2.0\",\"minVersion\":\"1.1.0\",\"releasedAt\":\"2026-11-01\"}",       // 앞자리 0
                "{\"version\":\"1.2.0\",\"minVersion\":\"1.10.0\",\"releasedAt\":\"2026-11-01\"}",       // 최소 > 버전(숫자 비교)
                "{\"version\":\"1.2.0\",\"minVersion\":\"1.1.0\",\"releasedAt\":\"2026-13-01\"}",
                "{\"version\":1,\"minVersion\":\"1.1.0\",\"releasedAt\":\"2026-11-01\"}",
                "{\"version\":\"1.2.0\",\"minVersion\":\"1.1.0\",\"releasedAt\":\"2026-11-01\",\"notesKo\":\"" + "가".repeat(501) + "\"}",
                "{\"version\":\"1.2.0\",\"minVersion\":\"1.1.0\",\"releasedAt\":\"2026-11-01\",\"notesKo\":7}",
                "{\"version\":\"1.2.0\",\"minVersion\":\"1.1.0\",\"releasedAt\":\"2026-11-01\",\"notesEn\":\"" + "x".repeat(9000) + "\"}");
        for (String r : bad) {
            place(r, null);
            mvc.perform(get(API).with(adm())).andExpect(jsonPath("$.data.state").value("INVALID"))
                    .andExpect(jsonPath("$.data.version").doesNotExist());
            mvc.perform(get(API + "/download").with(adm())).andExpect(status().isConflict());
        }
        // 제어 문자·방향 문자는 지우고 받는다(줄바꿈은 남김)
        place("{\"version\":\"1.2.0\",\"minVersion\":\"1.2.0\",\"releasedAt\":\"2026-11-01\",\"notesKo\":\"a\\u0000b\\u202Ec\\u0007\\nd\"}", null);
        mvc.perform(get(API).with(adm())).andExpect(jsonPath("$.data.state").value("OK"))
                .andExpect(jsonPath("$.data.notes").value("abc\nd"));
    }

    @Test
    void 권한_로그인_없음_401_화면_권한_없음_403_협력사_403() throws Exception {
        place(OK_RELEASE, apk);
        mvc.perform(get(API)).andExpect(status().isUnauthorized());
        mvc.perform(get(API + "/download")).andExpect(status().isUnauthorized());
        mvc.perform(get(API).with(accounts.as(comp, plain, "plain"))).andExpect(status().isForbidden());
        mvc.perform(get(API + "/download").with(accounts.as(comp, plain, "plain"))).andExpect(status().isForbidden());

        long cust = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'S1', '한빛부품', 'PURCHASE', 't', 't') returning cust_id""", Long.class, comp);
        long sup = accounts.query(comp, """
                insert into sys_user (comp_cd, login_id, user_nm, pwd_hash, user_type, cust_id, created_by, updated_by)
                values (?, 'sup1', '협력사', ?, 'SUPPLIER', ?, 't', 't') returning user_id""", Long.class,
                comp, encoder.encode("Quiet-river-77"), cust);
        // 협력사 사용자는 권한그룹에 들 수 없다(DB 트리거) — 화면 권한 이전에 포털 경계에서 막힌다
        RequestPostProcessor supplier = SecurityMockMvcRequestPostProcessors.authentication(
                UsernamePasswordAuthenticationToken.authenticated(new LoginUser(sup, comp, "sup1", "sup1", "ko", false,
                        LoginUser.SUPPLIER, cust), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        mvc.perform(get(API + "/download").with(supplier)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PORTAL_FORBIDDEN"));
        assertThat(downloadLogs(comp)).isZero();
    }

    @Test
    void 경로_조작_파라미터는_무시되고_링크로_밖을_가리키면_거부() throws Exception {
        place(OK_RELEASE, apk);
        Path outside = Files.createTempFile("pda-outside", ".apk");
        try {
            Files.writeString(outside, "SECRET-outside");
            byte[] got = mvc.perform(get(API + "/download").param("file", "../../" + outside.getFileName())
                            .param("name", outside.toString()).with(adm()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertThat(got).isEqualTo(apk);

            Files.delete(DIR.resolve(PdaAppService.APK));
            linkOrSkip(DIR.resolve(PdaAppService.APK), outside.toAbsolutePath());
            mvc.perform(get(API).with(adm())).andExpect(jsonPath("$.data.state").value("MISSING"));
            mvc.perform(get(API + "/download").with(adm())).andExpect(status().isConflict());

            // release.json 이 링크여도 읽지 않는다
            Files.delete(DIR.resolve(PdaAppService.APK));
            place(null, apk);
            Path rel = Files.createTempFile("pda-release", ".json");
            Files.writeString(rel, OK_RELEASE);
            Files.delete(DIR.resolve(PdaAppService.RELEASE));
            linkOrSkip(DIR.resolve(PdaAppService.RELEASE), rel.toAbsolutePath());
            mvc.perform(get(API).with(adm())).andExpect(jsonPath("$.data.state").value("MISSING"))
                    .andExpect(jsonPath("$.data.version").doesNotExist());
            mvc.perform(get("/pda/version.json")).andExpect(jsonPath("$.length()").value(0));
            Files.delete(rel);
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    /**
     * 심볼릭 링크를 만들 수 없는 환경(개발자 모드·관리자 권한 없는 Windows)에서는 링크 부분만 건너뛴다(중단 = skipped).
     * 경로 조작 파라미터 검사는 그 앞에서 이미 끝났다. Linux CI 에서는 항상 끝까지 돈다.
     */
    private static void linkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (FileSystemException | UnsupportedOperationException e) {
            Assumptions.abort("심볼릭 링크를 만들 수 없는 환경 — 링크 거부 검사 건너뜀: " + e.getMessage());
        }
    }

    @Test
    void 파일을_바꿔치기하면_해시를_다시_계산한다() throws Exception {
        place(OK_RELEASE, apk);
        mvc.perform(get(API).with(adm())).andExpect(jsonPath("$.data.sha256").value(sha(apk)));
        byte[] other = "PK-fake-apk-bytes-2".getBytes(StandardCharsets.UTF_8);   // 같은 길이
        Path f = DIR.resolve(PdaAppService.APK);
        Files.write(f, other);
        Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        mvc.perform(get(API).with(adm())).andExpect(jsonPath("$.data.sha256").value(sha(other)));
        byte[] got = mvc.perform(get(API + "/download").with(adm())).andReturn().getResponse().getContentAsByteArray();
        assertThat(got).isEqualTo(other);
    }

    @Test
    void A사로_B사_전역_파일은_같고_접속_로그는_자기_회사에만() throws Exception {
        place(OK_RELEASE, apk);
        String other = accounts.newComp("PDB");
        long bAdmin = accounts.newUser(other, "badm", "Quiet-river-77");
        mvc.perform(get(API + "/download").with(accounts.as(other, bAdmin, "badm"))).andExpect(status().isForbidden());
        accounts.grant(other, bAdmin, "PDAADM", PdaAppService.PROG, "SEARCH");
        int a = downloadLogs(comp);
        byte[] got = mvc.perform(get(API + "/download").with(accounts.as(other, bAdmin, "badm")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(got).isEqualTo(apk);
        assertThat(downloadLogs(other)).isEqualTo(1);
        assertThat(downloadLogs(comp)).isEqualTo(a);   // A사 기록에는 안 보인다(RLS)
    }

    @Test
    void semver_비교는_숫자로() {
        assertThat(PdaAppService.compare("1.10.0", "1.9.9")).isPositive();
        assertThat(PdaAppService.compare("1.2.0", "1.2.0")).isZero();
        assertThat(PdaAppService.compare("0.9.12", "1.0.0")).isNegative();
    }
}
