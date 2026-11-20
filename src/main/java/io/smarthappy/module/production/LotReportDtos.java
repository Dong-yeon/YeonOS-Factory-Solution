package io.smarthappy.module.production;

import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.module.production.LotDtos.LotRow;
import io.smarthappy.module.production.LotDtos.TraceView;
import lombok.Getter;
import lombok.Setter;

/** LOT 역추적 보고서 (P2 7b, docs/25 4.10) — 수량은 글자 */
public final class LotReportDtos {

    private LotReportDtos() {
    }

    /** 절 하나: 행 + 상한을 넘어 잘렸는지 */
    public record Section<T>(List<T> rows, boolean truncated) {
    }

    public record LotReport(LotRow lot, String receivedQty, TraceView back, TraceView fwd, Section<ProdRow> production,
            Section<ParamRow> params, Section<CloseRow> closes, Section<StateRow> states, Section<RcvRow> receipts,
            OffsetDateTime generatedAt, String generatedBy, String zone) {
    }

    /** 생산 이력 — 실적(RESULT) 또는 재작업·수리 처리(REWORK·REPAIR) */
    @Getter
    @Setter
    public static class ProdRow {
        private String kind;
        private long id;
        private String lotNo;
        private String woNo;
        private Integer opSeq;
        private String procCd;
        private String procNm;
        private String goodQty;
        private String scrapQty;
        private String reworkQty;
        private String repairQty;
        private String unitCd;
        private String workerCd;
        private String workerNm;
        private String terminalCd;
        private String equipCd;
        private String workDay;
        private String shiftCd;
        private String entryMethod;
        private Long reversalOfId;
        private boolean reversed;
        private String reason;
        private OffsetDateTime occurredAt;
        private String recordedBy;
    }

    @Getter
    @Setter
    public static class ParamRow {
        private long actualId;
        private String woNo;
        private int opSeq;
        private String procCd;
        private String paramCd;
        private String paramNm;
        private String value;
        private String lsl;
        private String usl;
        private String unitNm;
        private String judge;
        private String source;
        private String entryMethod;
        private Long reversalOfId;
        private boolean reversed;
        private String equipCd;
        private OffsetDateTime occurredAt;
    }

    @Getter
    @Setter
    public static class CloseRow {
        private String woNo;
        private int opSeq;
        private String procCd;
        private String procNm;
        private String inputQty;
        private String goodQty;
        private String scrapQty;
        private String reworkQty;
        private String repairQty;
        private String effGoodQty;
        private String diffQty;
        private int tolPct;
        private String firstOpYn;
        private OffsetDateTime occurredAt;
        private String recordedBy;
    }

    @Getter
    @Setter
    public static class StateRow {
        private String woNo;
        private String equipCd;
        private String stateCat;
        private String reasonCd;
        private String reasonNm;
        private OffsetDateTime startAt;
        private OffsetDateTime endAt;
    }

    @Getter
    @Setter
    public static class RcvRow {
        private long receiptId;
        private String nodeCd;
        private String qty;
        private String unitCd;
        private String entryMethod;
        private Long reversalOfId;
        private String reason;
        private OffsetDateTime occurredAt;
        private String recordedBy;
    }
}
