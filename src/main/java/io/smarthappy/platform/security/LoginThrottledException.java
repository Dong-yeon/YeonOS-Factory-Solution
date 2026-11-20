package io.smarthappy.platform.security;

import org.springframework.security.core.AuthenticationException;

/** 같은 접속 주소의 로그인 실패가 너무 많아 잠시 차단됨 */
public class LoginThrottledException extends AuthenticationException {

    public LoginThrottledException() {
        super("throttled");
    }
}
