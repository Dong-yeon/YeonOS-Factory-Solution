package io.smarthappy.module.cms.edge;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

/** 장비 연동 E0 DTO (docs/65 16장) */
public final class EdgeDtos {

    private EdgeDtos() {
    }

    // ---------- 연동 마스터 ----------

    @Getter
    @Setter
    public static class LinkRef {
        private long equipId;
        private String equipCd;
        private String equipNm;
        private String plantCd;
        private String connMode;
        private long terminalId;
        private String terminalCd;
        private String adapterUrl;
        private int hbSec;
        private String writeEnabledYn;
        private String useYn;
        private OffsetDateTime lastSeenAt;
        private Long lastSeq;
        private String packState;
        private String packMode;
        private OffsetDateTime packAt;
        private OffsetDateTime lostAt;
        private Integer rowVer;
    }

    /** 화면 목록 한 줄 — judge = 판정(EquipLinkJudge), machineryState = OPC UA 상위 상태 */
    @Getter
    @Setter
    public static class LinkRow extends LinkRef {
        private String nodeCd;
        private String judge;
        private String machineryState;
        private String stateCat;
        private int openCmdCnt;
        private int openAlarmCnt;
    }

    /** 연동 저장(없으면 만든다) — rowVer null = 새로 */
    public record LinkForm(String connMode, String terminalCd, String adapterUrl, Integer hbSec, String writeEnabledYn, String useYn,
            String remark, Integer rowVer) {
    }

    @Getter
    @Setter
    public static class TagRow {
        private long tagId;
        private long equipId;
        private String tagCd;
        private String tagNm;
        private String dataType;
        private BigDecimal minValue;
        private BigDecimal maxValue;
        private String unit;
        /** 쓸 수 있는 PackML 상태 — 쉼표 글자(비면 상태 무관) */
        private String allowedStates;
        private String writableYn;
        private String useYn;
        private int rowVer;
    }

    public record TagForm(Long tagId, String tagCd, String tagNm, String dataType, String minValue, String maxValue, String unit,
            List<String> allowedStates, String writableYn, String useYn, Integer rowVer) {
    }

    // ---------- 명령 ----------

    public record CmdForm(String tagCd, String value, String reason) {
    }

    @Getter
    @Setter
    public static class CmdRow {
        private long cmdId;
        private UUID cmdKey;
        private long equipId;
        private String equipCd;
        private String tagCd;
        private String cmdValue;
        private String reason;
        private String connMode;
        private long terminalId;
        private String terminalCd;
        private String status;
        private String resultCd;
        private String readback;
        private OffsetDateTime requestedAt;
        private String requestedBy;
        private OffsetDateTime expiresAt;
        private OffsetDateTime claimedAt;
        private OffsetDateTime leaseUntil;
        private OffsetDateTime finishedAt;
        private String dataType;
        private String adapterUrl;
        private List<StepRow> steps;
    }

    @Getter
    @Setter
    public static class StepRow {
        private long stepId;
        private long cmdId;
        private String phase;
        private String okYn;
        private String stepValue;
        private String detail;
        private String src;
        private OffsetDateTime occurredAt;
        private OffsetDateTime recordedAt;
        private String recordedBy;
    }

    /** 엣지가 가져가는 명령 한 건 */
    public record ClaimedCmd(UUID cmdKey, String equipCd, String tagCd, String dataType, String value, OffsetDateTime leaseUntil,
            OffsetDateTime expiresAt) {
    }

    // ---------- 사건·원문 ----------

    @Getter
    @Setter
    public static class LogRow {
        private long logId;
        private Long equipId;
        private String equipCd;
        private String terminalCd;
        private String eventCd;
        private Long seqFrom;
        private Long seqTo;
        private String detail;
        private Long inboundId;
        private OffsetDateTime occurredAt;
        private OffsetDateTime recordedAt;
    }

    @Getter
    @Setter
    public static class ConsRow {
        private long equipId;
        private String consCd;
        private String levelCd;
        private OffsetDateTime changedAt;
    }

    @Getter
    @Setter
    public static class SessionRow {
        private long terminalId;
        private String bootId;
        private OffsetDateTime startedAt;
        private OffsetDateTime lastHbAt;
        private Long skewMs;
        private OffsetDateTime skewLoggedAt;
    }

    @Getter
    @Setter
    public static class TerminalOpt {
        private long terminalId;
        private String terminalCd;
        private String terminalNm;
        private String plantCd;
    }

    // ---------- 엣지 응답 ----------

    /** 메시지 한 건 결과 — result = STORED / DUP / CONFLICT / INVALID:{코드} */
    public record ItemResult(int idx, String result) {
    }

    public record MessagesResult(int stored, int duplicates, int conflicts, int invalid, List<ItemResult> items) {
    }

    public record HeartbeatResult(OffsetDateTime serverTime, boolean birth, List<String> unknown) {
    }
}
