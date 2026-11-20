package io.smarthappy.module.wms.label;

import java.time.OffsetDateTime;

import lombok.Getter;
import lombok.Setter;

/** 인쇄 상태 3단·프린터 상태 DTO (docs/65 18.1) */
public final class PrintTrackDtos {

    private PrintTrackDtos() {
    }

    @Getter
    @Setter
    public static class JobState {
        private long jobId;
        private long printerId;
        private String labelType;
        private String bizKey;
        private int copies;
        private String labelValues;
        private String stateCd;
    }

    @Getter
    @Setter
    public static class EventRow {
        private long eventId;
        private long jobId;
        private String eventCd;
        private String reasonCd;
        private String detail;
        private String src;
        private Long terminalId;
        private OffsetDateTime occurredAt;
        private OffsetDateTime recordedAt;
        private String entryMethod;
        private String recordedBy;
    }

    @Getter
    @Setter
    public static class PendingJob {
        private long jobId;
        private int copies;
        private String zpl;
        private String stateCd;
        private OffsetDateTime sentAt;
    }

    @Getter
    @Setter
    public static class PrinterStatusRow {
        private long printerId;
        private String stateCd;
        private String errors;
        private String warnings;
        private Long odometer;
        private Long odometerBase;
        private String src;
        private String detail;
        private OffsetDateTime checkedAt;
        private OffsetDateTime changedAt;
    }

    @Getter
    @Setter
    public static class ProbeTarget {
        private long printerId;
        private String host;
        private int port;
    }

    /** 화면 검증 입력 */
    public record VerifyForm(String scanned) {
    }

    public record VerifyResult(boolean ok, String stateCd) {
    }

    /** 사람 확정: PRINTED(찍힌 것 확인) / CANCELLED(안 찍힘 — 닫기) */
    public record ResolveForm(String stateCd, String reason) {
    }

    /** 확인 한 번의 결과 요약 */
    public record ApplyResult(String printerState, int printed, int errors) {
    }

    // ------------------------------------------------------------------ 에이전트 큐 (docs/65 19.2)

    @Getter
    @Setter
    public static class DispatchRow {
        private long jobId;
        private long printerId;
        private long terminalId;
        private String stage;
        private int claimCnt;
        private OffsetDateTime claimedAt;
        private OffsetDateTime leaseUntil;
        private OffsetDateTime closedAt;
    }

    /** 에이전트에게 주는 작업 — ZPL 그대로(에이전트가 부수만큼 보낸다), 장 수 = 부수 × 서식 */
    @Getter
    @Setter
    public static class ClaimedJob {
        private long jobId;
        private long printerId;
        private String printerCd;
        private String host;
        private int port;
        private int copies;
        private String zpl;
        private String zplSha256;
        private String labelType;
        private String bizKey;
        private OffsetDateTime leaseUntil;
        private int labels;
    }

    @Getter
    @Setter
    public static class AgentPrinter {
        private long printerId;
        private String printerCd;
        private String printerNm;
        private String host;
        private int port;
        private int dpi;
    }

    /** 에이전트 결과 보고 */
    public record AgentResult(String state, String reasonCd, String detail, String occurredAt) {
    }

    public record AgentResultView(long jobId, String stateCd, String outcome) {
    }

    /** 하트비트 프린터 한 대 */
    public record AgentPrinterState(Long printerId, Boolean online, java.util.List<String> errors, java.util.List<String> warnings,
            Long odometer) {
    }

    public record AgentHeartbeat(String bootId, java.util.List<AgentPrinterState> printers) {
    }

    public record AgentHeartbeatView(int queued, java.util.List<AgentPrinter> printers, java.util.List<Long> unknown) {
    }

    /** 다른 프린터로 넘기기 */
    public record MoveForm(Long printerId, String reason) {
    }
}
