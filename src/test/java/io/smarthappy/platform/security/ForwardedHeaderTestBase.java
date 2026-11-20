package io.smarthappy.platform.security;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 리버스 프록시 헤더(X-Forwarded-For/Proto) 테스트 공통 — MockMvc 는 Tomcat RemoteIpValve 를 거치지 않으므로
 * 실제 포트로 띄워 HTTP 로 부른다. 테스트 클라이언트는 127.0.0.1 에서 접속한다(= "프록시" 역할).
 */
abstract class ForwardedHeaderTestBase extends PostgresTestBase {

    static final String PW = "Correct-pw-1!";

    @LocalServerPort
    int port;

    @Autowired
    TestAccounts accounts;

    final HttpClient http = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(new CookieManager())
            .build();

    HttpResponse<String> getLogin(Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login")).GET();
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 로그인 시도 → 실패 코드(/login?error=xxx 의 xxx) */
    String loginError(String compCd, String password, String xForwardedFor) throws Exception {
        getLogin(Map.of());   // CSRF 쿠키 받기
        String token = ((CookieManager) http.cookieHandler().orElseThrow()).getCookieStore().getCookies().stream()
                .filter(c -> "XSRF-TOKEN".equals(c.getName())).map(HttpCookie::getValue).findFirst().orElseThrow();
        String form = Map.of("compCd", compCd, "loginId", "kim", "password", password).entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("X-XSRF-TOKEN", token)
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (xForwardedFor != null) {
            b.header("X-Forwarded-For", xForwardedFor);
        }
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        String location = res.headers().firstValue("Location").orElse("");
        int i = location.indexOf("error=");
        return i < 0 ? location : location.substring(i + "error=".length());
    }

    /** 이 회사의 로그인 실패 접속 로그에 남은 IP */
    List<String> failIps(String compCd) {
        String ips = accounts.query(compCd,
                "select string_agg(ip, ',' order by log_id) from sys_access_log where event_cd = 'LOGIN_FAIL'",
                String.class);
        return ips == null ? List.of() : List.of(ips.split(","));
    }
}
