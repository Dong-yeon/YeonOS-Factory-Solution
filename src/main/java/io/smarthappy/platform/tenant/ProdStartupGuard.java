package io.smarthappy.platform.tenant;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.catalina.util.NetMaskSet;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 운영(prod) 기동 가드 (01 7.1, 9단계). 하나라도 어긋나면 기동을 중단한다 — "정상처럼 보이는 잘못된 운영"을 막는다.
 *
 * - 앱이 마이그레이션을 하지 않는다(spring.flyway.enabled=false) — 앱은 DB 소유자 자격증명을 갖지 않는다
 * - jar 가 기대하는 최신 스키마 버전 = DB 버전 (마이그레이션 전용 실행을 빼먹었거나, 옛 jar 로 새 DB 에 붙은 경우)
 * - 예시·개발용 비밀번호·개발 계정 설정이 남아 있지 않다
 * - 신뢰 프록시(X-Forwarded-*)를 공인 IP 까지 넓히지 않았다
 * - 운영 지표 끝점을 열었으면 관리 포트 분리·내부 주소·토큰·수집기 IP (docs/52 Y2)
 * - MFA 키(MFA_SECRET_KEY, 없으면 OPS_SECRET_KEY)가 있다 — 관리자 MFA 강제가 기본이라 키가 없으면 관리자 전원이 로그인하지 못한다(docs/36 14.4)
 * 앱 계정의 슈퍼유저·BYPASSRLS·소유자 롤 멤버 여부는 모든 프로필에서 DatabaseRoleGuard 가 본다.
 */
@Slf4j
@Component
@Profile("prod")
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@RequiredArgsConstructor
public class ProdStartupGuard implements ApplicationRunner {

    /** .env.example 등 예시 문서에 나오는 값 — 운영에 남아 있으면 안 된다 */
    static final Set<String> EXAMPLE_PASSWORDS = Set.of("change-me-owner", "change-me-app", "change-me-admin",
            "change-me", "change-me-ops-12chars", "password", "postgres", "test-only-pw");
    /** 예시 값 접두어 — .env.example 의 비밀 칸은 비우거나 이 접두어로만 쓴다(EnvExampleGuardTest, docs/67 F1) */
    static final String EXAMPLE_PREFIX = "change-me";

    /** 예시·흔한 기본값이면 true — 목록에 없는 새 예시 값도 접두어로 잡는다 */
    public static boolean isExampleSecret(String value) {
        if (value == null) {
            return false;
        }
        String s = value.strip();
        return EXAMPLE_PASSWORDS.contains(s) || s.toLowerCase(java.util.Locale.ROOT).startsWith(EXAMPLE_PREFIX);
    }

    private static final Pattern MIGRATION = Pattern.compile("^V(\\d+)__.+\\.sql$");

    private final JdbcTemplate jdbc;
    private final Environment env;

    @Override
    public void run(ApplicationArguments args) throws IOException {
        List<String> violations = check(env, expectedVersion(), dbVersion());
        if (!violations.isEmpty()) {
            throw new IllegalStateException("운영 기동 가드 위반으로 기동을 중단합니다:\n - " + String.join("\n - ", violations));
        }
        log.info("Prod startup guard OK (no app migration, schema v{}, no example secrets)", expectedVersion());
    }

    static List<String> check(Environment env, int expected, int actual) {
        List<String> v = new ArrayList<>();
        if (env.getProperty("spring.flyway.enabled", Boolean.class, true)) {
            v.add("운영에서 앱이 마이그레이션을 실행하도록 되어 있습니다(spring.flyway.enabled=true). "
                    + "마이그레이션은 전용 실행 모드(--spring.profiles.active=migrate)로만");
        }
        if (env.getProperty("spring.flyway.password") != null && !env.getProperty("spring.flyway.password").isBlank()) {
            v.add("운영 앱 프로세스에 DB 소유자 비밀번호(spring.flyway.password)가 있습니다 — 마이그레이션 프로세스에만 주세요");
        }
        if (expected != actual) {
            v.add("스키마 버전 불일치: 이 jar 는 v" + expected + ", DB 는 v" + actual
                    + " — 마이그레이션 전용 실행을 먼저 하거나 맞는 jar 를 배포하세요");
        }
        String pw = env.getProperty("spring.datasource.password");
        if (pw == null || pw.isBlank() || isExampleSecret(pw)) {
            v.add("DB 앱 계정 비밀번호가 비었거나 예시 값입니다");
        }
        String opsBoot = env.getProperty("happyboot.ops.bootstrap-password");
        if (opsBoot != null && !opsBoot.isBlank()) {
            v.add("운영 앱에 운영자 부트스트랩 비밀번호(OPS_BOOTSTRAP_PASSWORD)가 있습니다 — 마이그레이션 실행에만 주세요");
        }
        String dev = env.getProperty("happyboot.dev.admin-password");
        if (dev != null && !dev.isBlank()) {
            v.add("운영에 개발 계정 비밀번호(DEV_ADMIN_PASSWORD)가 설정되어 있습니다");
        }
        if (env.getProperty("happyboot.dev.login-prefill", Boolean.class, false)) {
            v.add("운영 로그인 화면에 회사·아이디 미리 채우기(happyboot.dev.login-prefill)가 켜져 있습니다");
        }
        if (!"true".equalsIgnoreCase(env.getProperty("server.servlet.session.cookie.secure"))) {
            v.add("세션 쿠키 secure 가 꺼져 있습니다(HTTPS 전용이어야 함)");
        }
        String mfaKey = env.getProperty("happyboot.mfa.secret-key");
        if (mfaKey == null || mfaKey.isBlank()) {
            mfaKey = env.getProperty("happyboot.ops.secret-key");
        }
        if (mfaKey == null || mfaKey.isBlank()) {
            v.add("MFA 키(MFA_SECRET_KEY 또는 OPS_SECRET_KEY)가 없습니다 — 관리자 MFA 가 기본이라 관리자가 로그인하지 못합니다");
        } else {
            try {
                new io.smarthappy.platform.ops.SecretBox(mfaKey);
            } catch (IllegalArgumentException e) {
                v.add("MFA 키(MFA_SECRET_KEY 또는 OPS_SECRET_KEY) 형식이 틀렸습니다(Base64 32바이트)");
            }
        }
        String proxies = env.getProperty("server.tomcat.remoteip.internal-proxies");
        if (proxies != null && !proxies.isBlank() && trustsPublicAddress(proxies)) {
            v.add("신뢰 프록시 범위(TRUSTED_PROXIES)가 공인 IP 까지 포함합니다 — 누구나 X-Forwarded-For 로 접속 IP 를 속일 수 있습니다");
        }
        checkMetrics(env, v);
        return v;
    }

    /**
     * 운영 지표 끝점(docs/52 Y2)을 열었으면: 관리 포트 분리 + 내부 주소 바인딩 + 토큰 + 수집기 허용 IP 가 공인 대역이 아님.
     * 지표에는 회사를 가로지르는 운영 정보(아웃박스 토픽·작업명·오류 수)가 있다 — 앱 포트(인터넷)로 나가면 안 된다.
     */
    static void checkMetrics(Environment env, List<String> v) {
        String access = env.getProperty("management.endpoint.prometheus.access", "none").trim();
        if (access.equalsIgnoreCase("none")) {
            return;
        }
        Integer mport = env.getProperty("management.server.port", Integer.class);
        int sport = env.getProperty("server.port", Integer.class, 8080);
        if (mport == null || mport <= 0 || mport == sport) {
            v.add("운영 지표를 열려면 관리 포트를 앱 포트와 다르게 나눠야 합니다(MANAGEMENT_SERVER_PORT)");
        }
        String addr = env.getProperty("management.server.address");
        if (addr == null || addr.isBlank() || !internalAddress(addr.trim())) {
            v.add("운영 지표의 관리 포트는 내부 주소(127.0.0.1·사설 IP)에만 바인딩해야 합니다(MANAGEMENT_SERVER_ADDRESS, 0.0.0.0·공인 IP 금지)");
        }
        String token = env.getProperty("happyboot.metrics.token");
        if (token == null || token.strip().length() < io.smarthappy.platform.metrics.MetricsProperties.MIN_TOKEN_LENGTH) {
            v.add("운영 지표 수집 토큰(METRICS_TOKEN)이 없거나 짧습니다(32자 이상)");
        } else if (isExampleSecret(token)) {
            v.add("운영 지표 수집 토큰(METRICS_TOKEN)이 예시 값입니다");
        }
        String ips = env.getProperty("happyboot.metrics.allowed-ips", "");
        java.util.Optional<io.smarthappy.platform.common.net.IpMatcher> m = io.smarthappy.platform.common.net.IpMatcher.parse(ips);
        if (m.isEmpty() || List.of("203.0.113.10", "198.51.100.10", "8.8.8.8", "2001:db8::1").stream().anyMatch(m.get()::matches)) {
            v.add("운영 지표 허용 IP(METRICS_ALLOWED_IPS)를 해석할 수 없거나 공인 IP 까지 포함합니다 — 수집기 주소만");
        }
    }

    /** IP 글자 그대로만(이름 조회 없음) — 루프백·사설(10/8·172.16/12·192.168/16)·IPv6 ULA(fc00::/7) */
    static boolean internalAddress(String addr) {
        if (!addr.matches("^[0-9.]+$") && !addr.contains(":")) {
            return false;   // 호스트 이름은 받지 않는다 — 무엇에 바인딩되는지 글자만으로 알 수 있어야 한다
        }
        try {
            InetAddress a = InetAddress.getByName(addr);
            if (a.isAnyLocalAddress()) {
                return false;
            }
            boolean ula = a.getAddress().length == 16 && (a.getAddress()[0] & 0xfe) == 0xfc;
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || ula;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * 신뢰 프록시 범위가 공인 주소(문서용 TEST-NET·공용 DNS)를 받아들이면 너무 넓다.
     * Tomcat RemoteIpValve 와 같은 규칙으로 해석한다 — '/' 가 있으면 CIDR 목록, 없으면 정규식.
     */
    static boolean trustsPublicAddress(String proxies) {
        List<String> samples = List.of("203.0.113.10", "198.51.100.10", "8.8.8.8", "2001:db8::1");
        try {
            if (proxies.indexOf('/') > 0) {
                NetMaskSet cidr = NetMaskSet.parse(proxies);
                for (String ip : samples) {
                    if (cidr.contains(InetAddress.getByName(ip))) {
                        return true;
                    }
                }
                return false;
            }
            Pattern p = Pattern.compile(proxies);
            return samples.stream().anyMatch(ip -> p.matcher(ip).matches());
        } catch (IllegalArgumentException | UnknownHostException e) {
            return true;   // 해석할 수 없는 값도 위반으로 본다
        }
    }

    /** jar 안 db/migration 의 가장 큰 V 번호 */
    static int expectedVersion() throws IOException {
        int max = 0;
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql")) {
            Matcher m = MIGRATION.matcher(r.getFilename() == null ? "" : r.getFilename());
            if (m.matches()) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        return max;
    }

    int dbVersion() {
        Integer v = jdbc.queryForObject("""
                select coalesce(max(version::int), 0) from flyway_schema_history
                 where success and version is not null""", Integer.class);
        return v == null ? 0 : v;
    }
}
