package io.smarthappy.platform.common.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** 오류 코드 → 응답 본문. 컨트롤러 단계(ApiExceptionHandler)와 보안 필터 단계가 같은 형식을 쓴다 */
@Component
@RequiredArgsConstructor
public class ErrorMessages {

    private final MessageSource messages;

    public ApiError body(HbErrorCode code, Locale locale, Object... args) {
        String text = messages.getMessage(code.messageKey(), args, code.messageKey(), locale);
        return ApiError.of(code.name(), text);
    }

    public ResponseEntity<ApiError> response(HbErrorCode code, Object... args) {
        return ResponseEntity.status(code.status()).body(body(code, LocaleContextHolder.getLocale(), args));
    }

    public ResponseEntity<ApiError> response(BusinessException e) {
        Locale locale = LocaleContextHolder.getLocale();
        ApiError base = body(e.code(), locale, e.args());
        java.util.List<String> details = e.details().stream()
                .map(d -> messages.getMessage(d.messageKey(), d.args(), d.messageKey(), locale)).toList();
        java.util.List<ApiError.FieldErrorView> errors = e.fieldErrors().stream()
                .map(f -> new ApiError.FieldErrorView(f.rowKey(), f.field(), f.code(),
                        messages.getMessage("valid." + f.code(), f.args(), f.code(), locale))).toList();
        return ResponseEntity.status(e.code().status()).body(ApiError.of(base.code(), base.message(), details, errors));
    }

    /** 필터 단계(컨트롤러 밖) 응답 — 로케일은 요청 기준(로그인 전이면 Accept-Language) */
    public void write(HttpServletRequest request, HttpServletResponse response, HbErrorCode code) throws IOException {
        ApiError body = body(code, request.getLocale());
        response.setStatus(code.status().value());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"result\":\"ERROR\",\"code\":\"" + body.code() + "\",\"message\":\""
                + jsonEscape(body.message()) + "\"}");
    }

    private static String jsonEscape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '<' -> b.append("\\u003c");
                case '>' -> b.append("\\u003e");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}
