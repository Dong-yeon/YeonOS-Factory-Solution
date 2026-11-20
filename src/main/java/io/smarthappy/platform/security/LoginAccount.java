package io.smarthappy.platform.security;

import lombok.Getter;
import lombok.Setter;

/** 로그인 판정용 계정 조회 결과 (UserAccountMapper.findForLogin) */
@Getter
@Setter
public class LoginAccount {
    private long userId;
    private String compCd;
    private String loginId;
    private String userNm;
    private String pwdHash;
    private String localeCd;
    private String lockYn;
    private String pwdChangeReqYn;
    private String useYn;
    private String compStatusCd;
    private String compUseYn;
    /** INTERNAL · SUPPLIER(협력사 포털, docs/42) */
    private String userType;
    private Long custId;
    /** 협력사 사용자의 소속 거래처 사용 여부(회사 사용자는 null) */
    private String custUseYn;

    /** 협력사 사용자는 소속 거래처도 사용 중이어야 한다 — 거래처를 사용 중지하면 그 협력사 계정이 모두 막힌다 */
    boolean usable() {
        return "Y".equals(useYn) && "ACTIVE".equals(compStatusCd) && "Y".equals(compUseYn)
                && (!LoginUser.SUPPLIER.equals(userType) || "Y".equals(custUseYn));
    }

    boolean locked() {
        return "Y".equals(lockYn);
    }
}
