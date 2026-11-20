package io.smarthappy.platform.common.web;

import java.util.ArrayList;
import java.util.List;

/**
 * 행·필드 검증 오류 모음. **검증을 끝까지 돌려 오류를 모은 뒤** 한 번에 400 으로 (첫 오류만 알리던 패턴 개선, docs/09 5.1).
 * 문구 키는 `valid.{code}` (REQUIRED·TOO_LONG·FORMAT·DUPLICATE·NOT_FOUND …), 인자는 길이 등 숫자만 — 사용자 입력 금지.
 */
public final class FieldErrors {

    public record FieldError(String rowKey, String field, String code, Object... args) {
    }

    private final List<FieldError> errors = new ArrayList<>();
    private final String rowKey;

    private FieldErrors(String rowKey, List<FieldError> shared) {
        this.rowKey = rowKey;
        if (shared != null) {
            this.errors.addAll(shared);
        }
    }

    public static FieldErrors create() {
        return new FieldErrors(null, null);
    }

    /** 같은 목록에 쌓되 행 키를 붙이는 보기 */
    public RowScope row(String key) {
        return new RowScope(key);
    }

    public FieldErrors add(String field, String code, Object... args) {
        errors.add(new FieldError(rowKey, field, code, args));
        return this;
    }

    public boolean isEmpty() {
        return errors.isEmpty();
    }

    public List<FieldError> list() {
        return List.copyOf(errors);
    }

    /** 오류가 있으면 400 INVALID_VALUE */
    public void throwIfAny() {
        if (!errors.isEmpty()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(list());
        }
    }

    public final class RowScope {
        private final String key;

        private RowScope(String key) {
            this.key = key;
        }

        public RowScope add(String field, String code, Object... args) {
            errors.add(new FieldError(key, field, code, args));
            return this;
        }
    }
}
