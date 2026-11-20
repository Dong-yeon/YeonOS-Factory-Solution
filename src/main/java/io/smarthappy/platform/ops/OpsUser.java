package io.smarthappy.platform.ops;

import java.io.Serializable;
import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 로그인한 운영자 (회사 사용자 LoginUser 와 별개 principal, 권한 ROLE_OPS) */
public record OpsUser(long opId, String loginId, String opNm) implements Serializable {

    public static Optional<OpsUser> current() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof OpsUser u ? Optional.of(u) : Optional.empty();
    }

    /** 감사·DB 행위자 표기 */
    public String actor() {
        return "ops:" + loginId;
    }
}
