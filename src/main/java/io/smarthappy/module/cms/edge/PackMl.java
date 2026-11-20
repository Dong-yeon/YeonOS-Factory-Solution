package io.smarthappy.module.cms.edge;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 장비 상태 모델 (docs/65 5.4·16.5·16.6) — PackML(ISA-TR88.00.02, OPC 30050) 17개 상태 중 8개 부분집합과 전이 허용표,
 * 그리고 CMS 상태 원장 분류(SEMI E10)·OPC UA MachineryItemState(OPC 40001-1) 대응. 순수 함수.
 *
 * PackML 의 "~ing" 상태(Resetting·Holding·Unholding·Suspending·Stopping·Aborting·Clearing·Completing)는 빼고 그 전이를 바로 잇는다.
 */
public final class PackMl {

    public static final Set<String> STATES = Set.of("IDLE", "STARTING", "EXECUTE", "HELD", "SUSPENDED", "STOPPED", "ABORTED", "COMPLETE");
    public static final Set<String> MODES = Set.of("PRODUCTION", "MAINTENANCE", "MANUAL");

    /** 지금 → 갈 수 있는 다음(같은 상태는 늘 허용) */
    static final Map<String, Set<String>> NEXT = Map.of(
            "IDLE", Set.of("STARTING", "STOPPED", "ABORTED"),
            "STARTING", Set.of("EXECUTE", "HELD", "STOPPED", "ABORTED"),
            "EXECUTE", Set.of("HELD", "SUSPENDED", "STOPPED", "ABORTED", "COMPLETE"),
            "HELD", Set.of("EXECUTE", "STOPPED", "ABORTED"),
            "SUSPENDED", Set.of("EXECUTE", "STOPPED", "ABORTED"),
            "STOPPED", Set.of("IDLE", "ABORTED"),         // Reset
            "ABORTED", Set.of("STOPPED"),                 // Clear
            "COMPLETE", Set.of("IDLE", "STOPPED", "ABORTED"));

    private PackMl() {
    }

    /** 대문자로 정규화한 상태, 모르는 값이면 null */
    public static String state(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip().toUpperCase(Locale.ROOT);
        return STATES.contains(s) ? s : null;
    }

    /** 모드 — 없으면 PRODUCTION, 모르는 값이면 null */
    public static String mode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "PRODUCTION";
        }
        String s = raw.strip().toUpperCase(Locale.ROOT);
        return MODES.contains(s) ? s : null;
    }

    /** 전이 허용 — 이전 상태가 없으면(첫 상태) 허용 */
    public static boolean allowed(String from, String to) {
        if (from == null || from.equals(to)) {
            return true;
        }
        Set<String> next = NEXT.get(from);
        return next != null && next.contains(to);
    }

    /** CMS 상태 원장 분류(SEMI E10) — docs/65 16.6 표 */
    public static String stateCat(String state, String mode) {
        if ("ABORTED".equals(state)) {
            return "UDT";
        }
        if ("MAINTENANCE".equals(mode)) {
            return "SDT";
        }
        if ("MANUAL".equals(mode)) {
            return "ENG";
        }
        return switch (state) {
            case "EXECUTE" -> "PRD";
            case "HELD" -> "UDT";
            default -> "SBY";     // IDLE·STARTING·COMPLETE·STOPPED·SUSPENDED
        };
    }

    /** OPC UA MachineryItemState(상위 집계) — null(모름·끊김) = NotAvailable */
    public static String machineryItemState(String state, String mode) {
        if (state == null) {
            return "NotAvailable";
        }
        if ("ABORTED".equals(state) || "MAINTENANCE".equals(mode)) {
            return "OutOfService";
        }
        return "EXECUTE".equals(state) && "PRODUCTION".equals(mode) ? "Executing" : "NotExecuting";
    }
}
