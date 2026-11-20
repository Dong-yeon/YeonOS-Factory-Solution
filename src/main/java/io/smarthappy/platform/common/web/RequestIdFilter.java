package io.smarthappy.platform.common.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 요청 ID (docs/52 Y2) — 로그 줄마다 reqId(MDC)를 달고 응답 헤더 X-Request-Id 로 돌려준다. 사용자가 "이 오류" 를 알려 줄 때
 * 이 값 하나로 그 요청의 로그(회사 compCd·사용자 userId 와 함께)를 찾는다.
 * 앞단 프록시가 붙인 X-Request-Id 는 형식(영문·숫자·._- 8~64자)이 맞을 때만 이어 쓴다 — 로그 줄 위조(줄바꿈·제어문자)를 막는다.
 * 보안 필터보다 먼저(가장 앞) — 로그인 실패·403 로그에도 붙는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "reqId";
    static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = incoming != null && VALID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }
}
