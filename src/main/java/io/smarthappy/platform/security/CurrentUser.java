package io.smarthappy.platform.security;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 현재 요청의 로그인 사용자 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<LoginUser> get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof LoginUser u ? Optional.of(u) : Optional.empty();
    }
}
