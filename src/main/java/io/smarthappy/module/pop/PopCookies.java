package io.smarthappy.module.pop;

import java.time.Duration;

import org.springframework.http.ResponseCookie;

/** 단말 쿠키 HB_TERMINAL — HttpOnly·SameSite=Strict·경로 /·https 면 Secure (docs/25 4.6) */
final class PopCookies {

    private PopCookies() {
    }

    static String issue(String value, boolean secure) {
        return ResponseCookie.from(PopAuthService.COOKIE, value).httpOnly(true).secure(secure).sameSite("Strict").path("/")
                .maxAge(Duration.ofDays(PopAuthService.COOKIE_DAYS)).build().toString();
    }

    static String clear(boolean secure) {
        return ResponseCookie.from(PopAuthService.COOKIE, "").httpOnly(true).secure(secure).sameSite("Strict").path("/")
                .maxAge(0).build().toString();
    }
}
