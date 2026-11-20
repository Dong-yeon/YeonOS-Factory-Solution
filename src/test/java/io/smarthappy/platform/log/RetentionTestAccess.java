package io.smarthappy.platform.log;

import java.util.Set;

/** 다른 패키지 시험이 RetentionTest 의 감사 예외 목록(근거 포함 원본)을 읽게 — IntegrityCheckTest 가 런타임 목록과 맞춘다 */
public final class RetentionTestAccess {

    private RetentionTestAccess() {
    }

    public static Set<String> auditExempt() {
        return RetentionTest.AUDIT_EXEMPT.keySet();
    }
}
