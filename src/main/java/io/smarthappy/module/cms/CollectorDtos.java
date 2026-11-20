package io.smarthappy.module.cms;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** 설비 상태·연결·Collector DTO (P2 6단계, docs/25 4.8) */
public final class CollectorDtos {

    private CollectorDtos() {
    }

    // ---------- 수신 ----------

    /** 수신 결과: 202 본문. rejected = 이벤트별 거부 사유 코드 */
    public record IngestResult(int accepted, int duplicates, List<Rejected> rejected) {
    }

    public record Rejected(String eventId, String code) {
    }

    @Getter
    @Setter
    public static class EquipRef {
        private long equipId;
        private String equipCd;
        private String plantCd;
    }

    @Getter
    @Setter
    public static class OpenInterval {
        private long stateLogId;
        private String stateCat;
        private Long reasonId;
        private OffsetDateTime startAt;
        private String packState;
    }

    @Getter
    @Setter
    public static class RunningProc {
        private long woProcId;
        private long woId;
        private Long equipId;
    }

    @Getter
    @Setter
    public static class ReasonRef {
        private long reasonId;
        private String stateCat;
    }

    /** packState = PackML 상태(엣지 경로, docs/65 16.6) — 수집기 경로는 null */
    public record NewInterval(long equipId, String stateCat, Long reasonId, Instant startAt, Long woId, long terminalId,
            String eventId, Instant occurredAt, String packState) {
    }

    /** 수집 COUNT 한 행 — metricCd GOOD/SCRAP, qty = 증가분 */
    public record NewCount(long equipId, String metricCd, BigDecimal qty, long terminalId, String eventId, Instant occurredAt) {
    }

    public record NewEnergy(String plantCd, String meterCd, Instant periodStart, Instant periodEnd, BigDecimal qty, String unitCd,
            long terminalId, String eventId, Instant occurredAt) {
    }

    // ---------- 타임라인(사무) ----------

    @Getter
    @Setter
    public static class EquipRow {
        private long equipId;
        private String equipCd;
        private String equipNm;
        private String nodeCd;
    }

    @Getter
    @Setter
    public static class IntervalRow {
        private long stateLogId;
        private long equipId;
        private String stateCat;
        private String reasonCd;
        private String reasonNm;
        private String plannedYn;
        private OffsetDateTime startAt;
        private OffsetDateTime endAt;
        private String woNo;
        private String source;
        private String packState;
    }

    @Getter
    @Setter
    public static class ConnRow {
        private long connLogId;
        private Long equipId;
        private String equipCd;
        private String terminalCd;
        private String conn;
        private Long seqFrom;
        private Long seqTo;
        private OffsetDateTime occurredAt;
    }

    @Getter
    @Setter
    public static class PlantRow {
        private String plantCd;
        private String plantNm;
    }

    /** 하루 타임라인: 공장 현지 [from, to) + 설비·구간·연결 로그 */
    public record Timeline(String plantCd, String zone, String day, OffsetDateTime from, OffsetDateTime to, List<EquipRow> equips,
            List<IntervalRow> intervals, List<ConnRow> conns) {
    }
}
