package io.smarthappy.platform.push;

import java.net.IDN;
import java.net.URI;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Web Push 설정 (docs/45 9장 N6·N7) — happyboot.push.*. VAPID 키는 환경 변수로만(저장소·문서·DB 에 없음).
 * 공개·개인 키가 둘 다 비면 꺼짐, 하나만 있거나 모양이 틀리거나 짝이 안 맞으면 기동 중단(조용히 꺼지지 않게).
 *
 * 허용 호스트(SSRF 막기): 엔드포인트는 기기가 보낸 주소라 서버가 그곳으로 POST 한다 — https·기본 포트·허용 목록 호스트만.
 * "*.example.com" 은 하위 도메인만(example.com 자체 아님). IP 주소·사용자 정보(user@)·포트 지정은 거부
 */
@Component
public class PushSettings {

    private final ECPublicKey publicKey;
    private final ECPrivateKey privateKey;
    private final String subject;
    private final List<String> allowedHosts;

    public PushSettings(@Value("${happyboot.push.vapid-public-key:}") String pub, @Value("${happyboot.push.vapid-private-key:}") String priv,
            @Value("${happyboot.push.subject:}") String subject, @Value("${happyboot.push.allowed-hosts:}") String hosts) {
        String p = pub == null ? "" : pub.strip();
        String k = priv == null ? "" : priv.strip();
        this.allowedHosts = Arrays.stream((hosts == null ? "" : hosts).split(",")).map(h -> h.strip().toLowerCase(Locale.ROOT))
                .filter(h -> !h.isEmpty()).toList();
        if (p.isEmpty() && k.isEmpty()) {
            this.publicKey = null;
            this.privateKey = null;
            this.subject = "";
            return;
        }
        if (p.isEmpty() || k.isEmpty()) {
            throw new IllegalStateException("happyboot.push: VAPID 공개 키와 개인 키는 둘 다 주거나 둘 다 비운다");
        }
        try {
            this.publicKey = WebPushCrypto.publicKey(WebPushCrypto.unb64(p));
            this.privateKey = WebPushCrypto.privateKey(WebPushCrypto.unb64(k));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("happyboot.push: VAPID 키 모양이 틀림(공개 65바이트·개인 32바이트 base64url)", e);
        }
        if (!matches(publicKey, privateKey)) {
            throw new IllegalStateException("happyboot.push: VAPID 공개 키와 개인 키가 짝이 아님");
        }
        String s = subject == null ? "" : subject.strip();
        if (!s.matches("^(mailto:[^\\s\"<>]{3,200}|https://[^\\s\"<>]{3,200})$")) {
            throw new IllegalStateException("happyboot.push.subject 는 mailto:주소 또는 https://주소 (RFC 8292)");
        }
        this.subject = s;
    }

    public boolean enabled() {
        return publicKey != null;
    }

    /** 브라우저 pushManager.subscribe 의 applicationServerKey (base64url) — 꺼져 있으면 null */
    public String publicKeyB64() {
        return publicKey == null ? null : WebPushCrypto.b64(WebPushCrypto.raw(publicKey));
    }

    ECPublicKey publicKey() {
        return publicKey;
    }

    ECPrivateKey privateKey() {
        return privateKey;
    }

    String subject() {
        return subject;
    }

    /** 허용된 푸시 서비스 주소인가 — 구독 때와 보낼 때 */
    public boolean allowedEndpoint(String endpoint) {
        if (endpoint == null || endpoint.length() > 1000) {
            return false;
        }
        URI u;
        try {
            u = new URI(endpoint);
        } catch (Exception e) {
            return false;
        }
        if (!"https".equals(u.getScheme()) || u.getRawUserInfo() != null || u.getPort() != -1 || u.getHost() == null
                || u.getRawFragment() != null) {
            return false;
        }
        String host;
        try {
            host = IDN.toASCII(u.getHost()).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host.endsWith(".")) {
            return false;
        }
        for (String a : allowedHosts) {
            if (a.startsWith("*.") ? host.endsWith(a.substring(1)) && host.length() > a.length() - 1 : host.equals(a)) {
                return true;
            }
        }
        return false;
    }

    /** VAPID aud — 엔드포인트의 출처 */
    static String audience(String endpoint) {
        URI u = URI.create(endpoint);
        return u.getScheme() + "://" + u.getHost();
    }

    private static boolean matches(ECPublicKey pub, ECPrivateKey priv) {
        try {
            String h = WebPushCrypto.vapidHeader("https://check", "mailto:check@example.test", 1L, pub, priv);
            String jwt = h.substring("vapid t=".length(), h.indexOf(", k="));
            int dot = jwt.lastIndexOf('.');
            java.security.Signature v = java.security.Signature.getInstance("SHA256withECDSAinP1363Format");
            v.initVerify(pub);
            v.update(jwt.substring(0, dot).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return v.verify(WebPushCrypto.unb64(jwt.substring(dot + 1)));
        } catch (java.security.GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }
}
