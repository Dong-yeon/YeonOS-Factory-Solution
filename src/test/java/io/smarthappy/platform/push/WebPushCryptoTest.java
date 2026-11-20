package io.smarthappy.platform.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Web Push 암호화·서명 단위 시험 (docs/45 9장 N5) — DB·스프링 없음.
 * RFC 8291 부록 A 시험 벡터(공개된 시험용 키 — 실제 키 아님), 받는 쪽 복호화 왕복, VAPID JWT 서명 검증·모양
 */
class WebPushCryptoTest {

    // RFC 8291 Appendix A
    static final String PLAINTEXT = "When I grow up, I want to be a watermelon";
    static final String AS_PUBLIC = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    static final String UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
    static final String BODY = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    @Test
    void RFC_8291_부록_A_벡터와_같다() {
        KeyPair as = new KeyPair(WebPushCrypto.publicKey(WebPushCrypto.unb64(AS_PUBLIC)),
                WebPushCrypto.privateKey(WebPushCrypto.unb64(AS_PRIVATE)));
        byte[] body = WebPushCrypto.encrypt(WebPushCrypto.unb64(UA_PUBLIC), WebPushCrypto.unb64(AUTH),
                PLAINTEXT.getBytes(StandardCharsets.UTF_8), as, WebPushCrypto.unb64(SALT));
        assertThat(WebPushCrypto.b64(body)).isEqualTo(BODY);
    }

    @Test
    void 받는_쪽이_자기_개인_키로_풀면_원문() throws Exception {
        KeyPair ua = WebPushCrypto.newKeyPair();
        byte[] auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);
        byte[] uaPub = WebPushCrypto.raw((ECPublicKey) ua.getPublic());
        byte[] msg = "{\"t\":\"결재 요청\",\"u\":\"/main\"}".getBytes(StandardCharsets.UTF_8);
        byte[] body = WebPushCrypto.encrypt(uaPub, auth, msg);
        assertThat(decrypt(body, (ECPrivateKey) ua.getPrivate(), uaPub, auth)).isEqualTo(msg);
        // 매번 새 키·소금 — 같은 원문도 다른 본문
        assertThat(WebPushCrypto.encrypt(uaPub, auth, msg)).isNotEqualTo(body);
    }

    @Test
    void 키_모양이_틀리면_거부() {
        assertThatThrownBy(() -> WebPushCrypto.publicKey(new byte[64])).isInstanceOf(IllegalArgumentException.class);
        byte[] offCurve = new byte[65];
        offCurve[0] = 4;
        offCurve[64] = 1;
        assertThatThrownBy(() -> WebPushCrypto.publicKey(offCurve)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebPushCrypto.privateKey(new byte[31])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebPushCrypto.encrypt(WebPushCrypto.unb64(UA_PUBLIC), new byte[15], new byte[1]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebPushCrypto.encrypt(WebPushCrypto.unb64(UA_PUBLIC), new byte[16], new byte[5000]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WebPushCrypto.unb64("not*base64")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void VAPID_헤더는_ES256_서명이_맞고_aud_exp_sub_를_싣는다() throws Exception {
        KeyPair kp = WebPushCrypto.newKeyPair();
        ECPublicKey pub = (ECPublicKey) kp.getPublic();
        String h = WebPushCrypto.vapidHeader("https://fcm.googleapis.com", "mailto:ops@example.test", 1_900_000_000L, pub,
                (ECPrivateKey) kp.getPrivate());
        assertThat(h).startsWith("vapid t=").contains(", k=" + WebPushCrypto.b64(WebPushCrypto.raw(pub)));
        String jwt = h.substring("vapid t=".length(), h.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        JsonNode head = JsonMapper.builder().build().readTree(WebPushCrypto.unb64(parts[0]));
        JsonNode claims = JsonMapper.builder().build().readTree(WebPushCrypto.unb64(parts[1]));
        assertThat(head.get("alg").asString()).isEqualTo("ES256");
        assertThat(claims.get("aud").asString()).isEqualTo("https://fcm.googleapis.com");
        assertThat(claims.get("exp").asLong()).isEqualTo(1_900_000_000L);
        assertThat(claims.get("sub").asString()).isEqualTo("mailto:ops@example.test");
        Signature v = Signature.getInstance("SHA256withECDSAinP1363Format");
        v.initVerify(pub);
        v.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(v.verify(WebPushCrypto.unb64(parts[2]))).isTrue();
        // 따옴표가 든 연락처도 JSON 이 깨지지 않는다
        String h2 = WebPushCrypto.vapidHeader("https://a", "mailto:\"x\"@y", 1L, pub, (ECPrivateKey) kp.getPrivate());
        String c2 = h2.substring("vapid t=".length()).split("\\.")[1];
        assertThat(JsonMapper.builder().build().readTree(WebPushCrypto.unb64(c2)).get("sub").asString()).isEqualTo("mailto:\"x\"@y");
    }

    @Test
    void 키_왕복() {
        KeyPair kp = WebPushCrypto.newKeyPair();
        byte[] pub = WebPushCrypto.raw((ECPublicKey) kp.getPublic());
        byte[] priv = WebPushCrypto.raw((ECPrivateKey) kp.getPrivate());
        assertThat(WebPushCrypto.raw(WebPushCrypto.publicKey(pub))).isEqualTo(pub);
        assertThat(WebPushCrypto.raw(WebPushCrypto.privateKey(priv))).isEqualTo(priv);
    }

    /** RFC 8291 받는 쪽(브라우저가 하는 일) — 시험에서만 */
    static byte[] decrypt(byte[] body, ECPrivateKey uaPriv, byte[] uaPub, byte[] auth) throws Exception {
        ByteBuffer in = ByteBuffer.wrap(body);
        byte[] salt = new byte[16];
        in.get(salt);
        assertThat(in.getInt()).isEqualTo(WebPushCrypto.RECORD_SIZE);
        byte[] asPub = new byte[in.get()];
        in.get(asPub);
        byte[] ct = new byte[in.remaining()];
        in.get(ct);
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(uaPriv);
        ka.doPhase(WebPushCrypto.publicKey(asPub), true);
        byte[] prkKey = WebPushCrypto.hmac(auth, ka.generateSecret());
        byte[] ikm = WebPushCrypto.hmac(prkKey, WebPushCrypto.concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[] {0}, uaPub,
                asPub, new byte[] {1}));
        byte[] prk = WebPushCrypto.hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(WebPushCrypto.hmac(prk, WebPushCrypto.concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII),
                new byte[] {0, 1})), 16);
        byte[] nonce = Arrays.copyOf(WebPushCrypto.hmac(prk, WebPushCrypto.concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII),
                new byte[] {0, 1})), 12);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = c.doFinal(ct);
        assertThat(padded[padded.length - 1]).isEqualTo((byte) 2);
        return Arrays.copyOf(padded, padded.length - 1);
    }
}
