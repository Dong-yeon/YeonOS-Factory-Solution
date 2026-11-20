package io.smarthappy.platform.messaging;

/** 경로 처리 결과 (docs/65 16.2) — status = DONE / STALE / REJECTED, code = 사유(없으면 null) */
public record InboundResult(String status, String code) {

    public static InboundResult done() {
        return new InboundResult("DONE", null);
    }

    public static InboundResult done(String code) {
        return new InboundResult("DONE", code);
    }

    /** 늦게 온 옛 상태 — 되돌리지 않고 기록만 */
    public static InboundResult stale(String code) {
        return new InboundResult("STALE", code);
    }

    /** 거부(남긴 사건은 커밋) — 되돌려야 하면 {@link InboundRoute.Reject} 를 던진다 */
    public static InboundResult rejected(String code) {
        return new InboundResult("REJECTED", code);
    }
}
