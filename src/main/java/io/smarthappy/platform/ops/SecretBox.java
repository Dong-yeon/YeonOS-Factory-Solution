package io.smarthappy.platform.ops;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 작은 비밀(운영자 TOTP 비밀 등)을 DB 에 암호화해 둔다 — AES-256-GCM, 키는 환경변수(OPS_SECRET_KEY, Base64 32바이트).
 * DB 만 털려서는 MFA 비밀을 얻지 못하게(키는 DB·백업과 다른 곳에). 형식: Base64(iv 12 || 암호문+태그)
 */
public final class SecretBox {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec key;

    public SecretBox(String base64Key) {
        byte[] k = base64Key == null ? new byte[0] : Base64.getDecoder().decode(base64Key.trim());
        if (k.length != 32) {
            throw new IllegalArgumentException("OPS_SECRET_KEY 는 Base64 로 32바이트여야 합니다");
        }
        this.key = new SecretKeySpec(k, "AES");
    }

    public String seal(String plain) {
        return seal(plain, null);
    }

    /**
     * 연결 데이터(AAD)와 묶어 봉인 — 같은 AAD 로만 풀린다. 예: 사용자 MFA 비밀은 "회사:사용자 id" 에 묶어
     * DB 에서 다른 사용자의 행으로 옮겨 넣으면 풀리지 않게 한다(docs/36 14.4 검토).
     */
    public String seal(String plain, String aad) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            if (aad != null) {
                c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            }
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 키가 다르거나 내용이 바뀌었으면 예외 (GCM 태그 검증) */
    public String open(String sealed) {
        return open(sealed, null);
    }

    /** {@link #seal(String, String)} 와 같은 AAD 로만 풀린다 */
    public String open(String sealed, String aad) {
        try {
            byte[] all = Base64.getDecoder().decode(sealed);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            if (aad != null) {
                c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            }
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("비밀을 풀 수 없습니다 (키가 바뀌었거나 값이 손상됨)");
        }
    }
}
