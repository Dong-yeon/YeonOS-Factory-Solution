package io.smarthappy.module.wms.label;

import java.util.Map;
import java.util.Set;

/**
 * 인쇄 작업 상태 3단 (docs/65 18.1) — **순수 함수**. 상태 = 사건 원장의 마지막 상태 사건(없으면 아웃박스에서 파생).
 * 서비스·에이전트 결과·사람 확정·확인 작업이 이 표 하나를 쓴다.
 */
public final class PrintStates {

    public static final String QUEUED = "QUEUED";
    public static final String CLAIMED = "CLAIMED";
    public static final String SENT = "SENT";
    public static final String PRINTED = "PRINTED";
    public static final String VERIFIED = "VERIFIED";
    public static final String PRINTER_ERROR = "PRINTER_ERROR";
    public static final String FAILED = "FAILED";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String CANCELLED = "CANCELLED";

    /** 상태를 바꾸지 않는 사건 */
    public static final String VERIFY_NG = "VERIFY_NG";
    public static final String WARN = "WARN";
    public static final String STALE = "STALE";

    public static final Set<String> STATES = Set.of(QUEUED, CLAIMED, SENT, PRINTED, VERIFIED, PRINTER_ERROR, FAILED, UNKNOWN, CANCELLED);
    /** 아직 끝나지 않은 상태(확인·lease 판정 대상) */
    public static final Set<String> OPEN = Set.of(QUEUED, CLAIMED, SENT, PRINTER_ERROR, UNKNOWN);
    public static final Set<String> FINAL = Set.of(VERIFIED, FAILED, CANCELLED);

    private static final Map<String, Set<String>> NEXT = Map.of(
            QUEUED, Set.of(CLAIMED, SENT, PRINTED, PRINTER_ERROR, FAILED, CANCELLED),
            CLAIMED, Set.of(SENT, PRINTED, PRINTER_ERROR, FAILED, UNKNOWN),
            SENT, Set.of(PRINTED, VERIFIED, PRINTER_ERROR, UNKNOWN),
            PRINTER_ERROR, Set.of(PRINTED, VERIFIED, FAILED, UNKNOWN, CANCELLED),
            PRINTED, Set.of(VERIFIED),
            UNKNOWN, Set.of(PRINTED, VERIFIED, FAILED, CANCELLED));

    private PrintStates() {
    }

    public static boolean isState(String cd) {
        return cd != null && STATES.contains(cd);
    }

    /** 지금 → 다음 이 허용되나. 같은 상태는 false(중복은 부른 쪽이 DUP 로) */
    public static boolean canMove(String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        Set<String> n = NEXT.get(from);
        return n != null && n.contains(to);
    }

    /** 상태 사건이 없을 때 — 설치형 아웃박스 상태에서 (NEW·SENDING → QUEUED, SENT → SENT, FAIL → FAILED, 수동 종결 → CANCELLED) */
    public static String fromOutbox(String outboxStatus) {
        if (outboxStatus == null) {
            return QUEUED;
        }
        return switch (outboxStatus) {
            case "SENT" -> SENT;
            case "FAIL" -> FAILED;
            case "CLOSED_MANUAL" -> CANCELLED;
            default -> QUEUED;
        };
    }
}
