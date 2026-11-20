package io.smarthappy.platform.common.web;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * API 오류 응답 본문: {"result":"ERROR","code":"...","message":"...","details":[...],"errors":[...]} (01 설계 5.7)
 * details 는 여러 항목을 함께 알려야 할 때만 (예: 비밀번호 정책 위반 항목들). 사용자 입력은 넣지 않는다.
 * errors 는 행·필드 단위 검증 오류 (폼 필드·일괄 저장 행 → 화면이 해당 칸에 표시, docs/09 5.1)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String result, String code, String message, List<String> details, List<FieldErrorView> errors) {

    /** rowKey = 일괄 저장의 클라이언트 행 키(폼이면 null), code = 오류 종류(REQUIRED 등), message = 번역된 문구 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldErrorView(String rowKey, String field, String code, String message) {
    }

    public static ApiError of(String code, String message) {
        return new ApiError("ERROR", code, message, null, null);
    }

    public static ApiError of(String code, String message, List<String> details) {
        return new ApiError("ERROR", code, message, details == null || details.isEmpty() ? null : details, null);
    }

    public static ApiError of(String code, String message, List<String> details, List<FieldErrorView> errors) {
        return new ApiError("ERROR", code, message, details == null || details.isEmpty() ? null : details,
                errors == null || errors.isEmpty() ? null : errors);
    }
}
