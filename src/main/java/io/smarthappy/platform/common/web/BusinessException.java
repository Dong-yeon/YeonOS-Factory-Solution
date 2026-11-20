package io.smarthappy.platform.common.web;

import java.util.List;

/** 업무 규칙 위반. 문구는 오류 코드의 메시지 키로만 (사용자 입력을 문구에 넣지 않는다) */
public class BusinessException extends RuntimeException {

    /** 세부 항목 (메시지 키 + 인자) — 응답 details 로 번역되어 나간다 */
    public record Detail(String messageKey, Object... args) {
    }

    private final HbErrorCode code;
    private final transient Object[] args;
    private final transient List<Detail> details;
    private transient List<FieldErrors.FieldError> fieldErrors = List.of();

    public BusinessException(HbErrorCode code, Object... args) {
        this(code, List.of(), args);
    }

    public BusinessException(HbErrorCode code, List<Detail> details, Object... args) {
        super(code.name());
        this.code = code;
        this.args = args;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public HbErrorCode code() {
        return code;
    }

    public Object[] args() {
        return args;
    }

    /** 행·필드 검증 오류를 붙인다 (응답 errors) */
    public BusinessException withFieldErrors(List<FieldErrors.FieldError> errors) {
        this.fieldErrors = errors == null ? List.of() : List.copyOf(errors);
        return this;
    }

    public List<FieldErrors.FieldError> fieldErrors() {
        return fieldErrors;
    }

    public List<Detail> details() {
        return details;
    }
}
