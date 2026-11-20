package io.smarthappy.platform.security;

import java.io.Serializable;

/**
 * 로그인 사용자 (세션에 저장되는 principal).
 * 회사코드는 여기서만 나온다 — 프론트가 보낸 compCd 는 쓰지 않는다.
 * 협력사 포털 사용자(userType SUPPLIER, docs/42)는 소속 협력사 custId 도 여기서만 나온다.
 */
public record LoginUser(
        long userId,
        String compCd,
        String loginId,
        String userNm,
        String localeCd,
        boolean pwdChangeRequired,
        String userType,
        Long custId) implements Serializable {

    public static final String INTERNAL = "INTERNAL";
    public static final String SUPPLIER = "SUPPLIER";

    /** 회사 사용자 (시험·시스템 작업용 행위자 등 — 유형을 따로 갖지 않는 곳) */
    public LoginUser(long userId, String compCd, String loginId, String userNm, String localeCd,
            boolean pwdChangeRequired) {
        this(userId, compCd, loginId, userNm, localeCd, pwdChangeRequired, INTERNAL, null);
    }

    public LoginUser {
        userType = userType == null ? INTERNAL : userType;
    }

    /** 협력사 포털 사용자 — 포털 경로만 다닌다(PortalBoundaryFilter), 화면 권한(@ProgAuth)은 늘 거부 */
    public boolean supplier() {
        return SUPPLIER.equals(userType);
    }

    public LoginUser withLocale(String localeCd) {
        return new LoginUser(userId, compCd, loginId, userNm, localeCd, pwdChangeRequired, userType, custId);
    }

    public LoginUser withPwdChangeRequired(boolean pwdChangeRequired) {
        return new LoginUser(userId, compCd, loginId, userNm, localeCd, pwdChangeRequired, userType, custId);
    }
}
