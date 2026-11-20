package io.smarthappy.platform.push;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Web Push 암호화·서명 — JDK 만 (docs/45 9장 N5, 새 의존성 없음).
 * <ul>
 *   <li>본문 암호화 RFC 8291(aes128gcm): 받는 기기 공개 키(p256dh)·auth 비밀과 보낼 때마다 새로 만든 키 쌍으로 ECDH(P-256) → HKDF → AES-128-GCM.
 *       레코드 하나(rs 4096), 끝 구분 0x02. 시험 = RFC 8291 부록 A 벡터</li>
 *   <li>서버 인증 RFC 8292(VAPID): ES256 JWT(aud = 엔드포인트 출처, exp ≤ 24시간, sub = 연락처) → {@code Authorization: vapid t=…, k=…}</li>
 * </ul>
 * 키 모양: 공개 키 = 비압축 점 65바이트(0x04‖x‖y) base64url, 개인 키 = 스칼라 32바이트 base64url
 */
public final class WebPushCrypto {

    static final int RECORD_SIZE = 4096;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder D64 = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ECParameterSpec P256 = p256();

    private WebPushCrypto() {
    }

    public static String b64(byte[] b) {
        return B64.encodeToString(b);
    }

    /** base64url(패딩 있어도 됨) → 바이트, 틀리면 IllegalArgumentException */
    public static byte[] unb64(String s) {
        return D64.decode(s.strip().replace("=", ""));
    }

    // ------------------------------------------------------------------ 키

    public static KeyPair newKeyPair() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 비압축 점 65바이트 → 공개 키. 곡선 위 점이 아니면 IllegalArgumentException */
    public static ECPublicKey publicKey(byte[] raw) {
        if (raw == null || raw.length != 65 || raw[0] != 0x04) {
            throw new IllegalArgumentException("P-256 비압축 공개 키(65바이트)가 아님");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 33, 65));
        // JDK KeyFactory 는 곡선 위 점인지 보지 않는다(시험으로 확인) — 기기가 보낸 키로 ECDH 를 하므로 직접 검사(invalid curve 공격)
        if (!onCurve(x, y)) {
            throw new IllegalArgumentException("곡선 위 점이 아님");
        }
        try {
            ECPoint w = new ECPoint(x, y);
            return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("곡선 위 점이 아님", e);
        }
    }

    public static ECPrivateKey privateKey(byte[] d) {
        if (d == null || d.length != 32) {
            throw new IllegalArgumentException("P-256 개인 키(32바이트)가 아님");
        }
        try {
            return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, d), P256));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static byte[] raw(ECPublicKey k) {
        byte[] out = new byte[65];
        out[0] = 0x04;
        put32(k.getW().getAffineX(), out, 1);
        put32(k.getW().getAffineY(), out, 33);
        return out;
    }

    public static byte[] raw(ECPrivateKey k) {
        byte[] out = new byte[32];
        put32(k.getS(), out, 0);
        return out;
    }

    // ------------------------------------------------------------------ RFC 8291

    /** 보낼 본문 — 보낼 때마다 새 키 쌍·소금 */
    public static byte[] encrypt(byte[] uaPublic, byte[] authSecret, byte[] plaintext) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(uaPublic, authSecret, plaintext, newKeyPair(), salt);
    }

    /** 시험용으로 키 쌍·소금을 받는다(RFC 8291 부록 A) */
    static byte[] encrypt(byte[] uaPublic, byte[] authSecret, byte[] plaintext, KeyPair as, byte[] salt) {
        if (authSecret == null || authSecret.length != 16) {
            throw new IllegalArgumentException("auth 비밀은 16바이트");
        }
        if (plaintext.length > RECORD_SIZE - 17 - 86) {
            throw new IllegalArgumentException("본문이 너무 깁니다(레코드 하나)");
        }
        try {
            byte[] asPublic = raw((ECPublicKey) as.getPublic());
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(as.getPrivate());
            ka.doPhase(publicKey(uaPublic), true);
            byte[] ecdh = ka.generateSecret();

            byte[] prkKey = hmac(authSecret, ecdh);
            byte[] keyInfo = concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[] {0}, uaPublic, asPublic, new byte[] {1});
            byte[] ikm = hmac(prkKey, keyInfo);
            byte[] prk = hmac(salt, ikm);
            byte[] cek = Arrays.copyOf(hmac(prk, concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII), new byte[] {0, 1})), 16);
            byte[] nonce = Arrays.copyOf(hmac(prk, concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII), new byte[] {0, 1})), 12);

            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            byte[] ct = c.doFinal(concat(plaintext, new byte[] {2}));   // 마지막 레코드 구분 0x02, 덧붙임 없음

            ByteBuffer out = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length + ct.length);
            out.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic).put(ct);
            return out.array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Web Push 암호화 실패", e);
        }
    }

    // ------------------------------------------------------------------ RFC 8292

    /** {@code Authorization} 값 — aud = 엔드포인트 출처(스킴://호스트[:포트]), exp = 지금 + 초(≤ 24시간) */
    public static String vapidHeader(String audience, String subject, long expEpochSeconds, ECPublicKey pub, ECPrivateKey priv) {
        String header = b64("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = b64(("{\"aud\":\"" + jsonEscape(audience) + "\",\"exp\":" + expEpochSeconds + ",\"sub\":\""
                + jsonEscape(subject) + "\"}").getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        try {
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initSign(priv);
            s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return "vapid t=" + signingInput + "." + b64(s.sign()) + ", k=" + b64(raw(pub));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("VAPID 서명 실패", e);
        }
    }

    // ------------------------------------------------------------------ 도우미

    static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(key, "HmacSHA256"));
        return m.doFinal(data);
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    private static void put32(BigInteger v, byte[] out, int at) {
        byte[] b = v.toByteArray();
        int len = Math.min(b.length, 32);
        System.arraycopy(b, b.length - len, out, at + 32 - len, len);
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') {
                sb.append('\\').append(ch);
            } else if (ch < 0x20) {
                sb.append(String.format("\\u%04x", (int) ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** y² ≡ x³ + ax + b (mod p), 0 ≤ x, y < p */
    static boolean onCurve(BigInteger x, BigInteger y) {
        BigInteger p = ((java.security.spec.ECFieldFp) P256.getCurve().getField()).getP();
        if (x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0) {
            return false;
        }
        BigInteger rhs = x.pow(3).add(P256.getCurve().getA().multiply(x)).add(P256.getCurve().getB()).mod(p);
        return y.modPow(BigInteger.TWO, p).equals(rhs);
    }

    private static ECParameterSpec p256() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return ((ECPublicKey) g.generateKeyPair().getPublic()).getParams();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
