package io.smarthappy.platform.ops;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP (RFC 6238: HMAC-SHA1, 30초, 6자리) — 운영자 MFA. 인증 앱(Google Authenticator 등)과 호환.
 * 외부 라이브러리 없이 표준 JDK 만 쓴다. 시계 오차는 앞뒤 1구간(±30초)까지 허용.
 * 같은 코드 재사용(재전송 공격)은 호출하는 쪽이 마지막 사용 구간을 기억해 막는다({@link #verify} 의 반환값).
 */
public final class Totp {

    static final int DIGITS = 6;
    static final int PERIOD_SECONDS = 30;
    static final int WINDOW = 1;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** 새 비밀 (160비트, Base32) */
    public static String newSecret() {
        byte[] b = new byte[20];
        RANDOM.nextBytes(b);
        return base32(b);
    }

    /** 인증 앱 등록 URI (QR 대신 화면에 문자열로 보여 주고 사용자가 직접 입력할 수도 있다) */
    public static String otpauthUri(String issuer, String account, String secret) {
        return "otpauth://totp/" + enc(issuer) + ":" + enc(account) + "?secret=" + secret + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    /**
     * 맞으면 일치한 시간 구간(step) 번호, 틀리면 -1. 형식이 틀린 코드(6자리 숫자 아님)는 계산 없이 -1
     *
     * @param lastUsedStep 이 운영자가 마지막으로 쓴 구간 — 그 이하 구간은 재사용으로 보고 거부
     */
    public static long verify(String secret, String code, Clock clock, long lastUsedStep) {
        if (code == null || !code.matches("^[0-9]{6}$") || secret == null) {
            return -1;
        }
        long now = clock.millis() / 1000 / PERIOD_SECONDS;
        for (long step = now - WINDOW; step <= now + WINDOW; step++) {
            if (step > lastUsedStep && constantTimeEquals(code(secret, step), code)) {
                return step;
            }
        }
        return -1;
    }

    static String code(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(unbase32(secret), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0x0f;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", bin % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }

    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return sb.toString();
    }

    static byte[] unbase32(String s) {
        String clean = s.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
        ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
        int buffer = 0;
        int bits = 0;
        for (char c : clean.toCharArray()) {
            int v = BASE32.indexOf(c);
            if (v < 0) {
                throw new IllegalArgumentException("Base32 아님");
            }
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.put((byte) ((buffer >> (bits - 8)) & 0xff));
                bits -= 8;
            }
        }
        return java.util.Arrays.copyOf(out.array(), out.position());
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }
}
