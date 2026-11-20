package io.smarthappy.platform.security;

import java.io.Serializable;

import jakarta.servlet.http.HttpServletRequest;

/** 로그인 폼의 회사코드와 접속 주소 (Spring Security authentication details) */
public record LoginDetails(String compCd, String remoteAddr) implements Serializable {

    static LoginDetails from(HttpServletRequest request) {
        return new LoginDetails(request.getParameter("compCd"), request.getRemoteAddr());
    }
}
