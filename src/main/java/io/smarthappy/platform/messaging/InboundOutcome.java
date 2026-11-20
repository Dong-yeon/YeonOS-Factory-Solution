package io.smarthappy.platform.messaging;

import java.util.Set;

import tools.jackson.databind.JsonNode;

/**
 * "완료" ≠ "성공" (docs/65 5.2-4) — 상대가 보낸 상태와 오류 목록으로 결과를 정한다. 순수 함수.
 *
 * - 성공 상태(DONE·FINISHED·COMPLETED·OK·SUCCESS)라도 오류 배열이 비어 있지 않으면 FAILED (VDA 5050 FINISHED + errors, 현장 WES 실측)
 * - 상태 코드를 범위(예: {@code >= 5})로 묶지 않는다 — 아는 글자만 성공으로 본다. 모르는 상태 = UNKNOWN(사람이 본다)
 */
public final class InboundOutcome {

    static final Set<String> SUCCESS = Set.of("DONE", "FINISHED", "COMPLETED", "OK", "SUCCESS");
    static final Set<String> FAILURE = Set.of("FAILED", "FAIL", "ERROR", "ABORTED");
    static final Set<String> REJECT = Set.of("REJECTED", "DENIED");

    private InboundOutcome() {
    }

    /** @return DONE / FAILED / REJECTED / UNKNOWN */
    public static String of(String status, JsonNode errors) {
        String s = status == null ? "" : status.strip().toUpperCase(java.util.Locale.ROOT);
        boolean hasErrors = errors != null && !errors.isNull() && !errors.isMissingNode()
                && (errors.isArray() ? !errors.isEmpty() : !InboundKeys.canonical(errors).isEmpty());
        if (REJECT.contains(s)) {
            return "REJECTED";
        }
        if (FAILURE.contains(s)) {
            return "FAILED";
        }
        if (SUCCESS.contains(s)) {
            return hasErrors ? "FAILED" : "DONE";
        }
        return "UNKNOWN";
    }
}
