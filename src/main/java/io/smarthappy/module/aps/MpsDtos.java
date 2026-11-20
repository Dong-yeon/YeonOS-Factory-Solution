package io.smarthappy.module.aps;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** MPS 기준생산계획 DTO (docs/38 16장). 응답 수량은 글자(trim_scale) — 브라우저 Double 손실을 피한다 */
public final class MpsDtos {

    private MpsDtos() {
    }

    // ---------- 요청 ----------

    /** MPS 대상 품목 등록·수정 — 수정 때 공장·품목은 바꿀 수 없다(무시). useYn 기본 Y. 안전재고·로트는 계획 기준정보(aps-item-param, docs/38 17장) */
    public record ItemForm(String plantCd, String itemCd, String remark, String useYn, Integer rowVer) {
    }

    /** 새 MPS 계산 — horizonStart 는 yyyy-MM-dd(비우면 공장 오늘), 일수 기본 28 */
    public record MpsForm(String plantCd, String horizonStart, Integer horizonDays, String mpsNm) {
    }

    /** 초안 칸 편집 — 바꿀 칸만 */
    public record CellsForm(Integer rowVer, List<CellEdit> cells) {
    }

    public record CellEdit(Long cellId, String planQty, String reason) {
    }

    public record RowVerForm(Integer rowVer) {
    }

    // ---------- 응답 ----------

    @Getter
    @Setter
    public static class ItemRow {
        private long mpsItemId;
        private String plantCd;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String safetyQty;
        private String lotRule;
        private String lotQty;
        private String lotMult;
        private String remark;
        private String useYn;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    @Getter
    @Setter
    public static class MpsRow {
        private long mpsId;
        private String mpsNo;
        private String plantCd;
        private String plantNm;
        private String mpsNm;
        private LocalDate horizonStart;
        private int horizonDays;
        private int fenceDays;
        private LocalDate fenceEnd;
        private Long baseMpsId;
        private String baseMpsNo;
        private String status;
        private int itemCnt;
        private OffsetDateTime calculatedAt;
        private String calculatedBy;
        private OffsetDateTime firmedAt;
        private String firmedBy;
        private OffsetDateTime supersededAt;
        private String discardReason;
        private int rowVer;
    }

    public record MpsList(List<MpsRow> rows, boolean more) {
    }

    /** 품목 행 — 계산 때 사본 */
    @Getter
    @Setter
    public static class RowSrc {
        private long mpsRowId;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String stockQty;
        private String safetyQty;
        private String lotRule;
        private String lotQty;
        private String lotMult;
    }

    @Getter
    @Setter
    public static class CellSrc {
        private long mpsCellId;
        private long mpsRowId;
        private LocalDate bucketDate;
        private String demandOrder;
        private String demandFcst;
        private String pastDueQty;
        private String supplyWo;
        private String propQty;
        private String baseQty;
        private String fencedYn;
        private String planQty;
        private String chgReason;
    }

    /** 칸 + 조회 때 계산한 예상 재고(pab) */
    public record CellView(long cellId, LocalDate date, String demandOrder, String demandFcst, String pastDueQty, String supplyWo,
            String propQty, String baseQty, String fencedYn, String planQty, String chgReason, String pab) {
    }

    public record RowView(long mpsRowId, long itemId, String itemCd, String itemNm, String unitCd, String stockQty, String safetyQty,
            String lotRule, String lotQty, String lotMult, List<CellView> cells) {
    }

    /** current = 이 공장의 현행 확정 MPS id(없으면 null) */
    public record MpsDetail(MpsRow mps, Long currentMpsId, List<RowView> rows) {
    }

    // ---------- 계산 입력·저장 (내부) ----------

    @Getter
    @Setter
    public static class MpsItemSrc {
        private long itemId;
        private String itemCd;
        private String unitCd;
        private BigDecimal safetyQty;
        private String lotRule;
        private BigDecimal lotQty;
        private BigDecimal lotMult;
    }

    @Getter
    @Setter
    public static class WoSupplySrc {
        private long itemId;
        private LocalDate planEnd;
        private BigDecimal remainQty;
    }

    @Getter
    @Setter
    public static class FirmedCellSrc {
        private long itemId;
        private LocalDate bucketDate;
        private BigDecimal planQty;
    }

    @Getter
    @Setter
    public static class ItemLookup {
        private long itemId;
        private String itemCd;
        private String unitCd;
        private String procType;
        private String useYn;
    }

    @Getter
    @Setter
    public static class CellLock {
        private long mpsCellId;
        private String baseQty;
        private String fencedYn;
        private String planQty;
        private String chgReason;
        private String unitCd;
    }

    public record MpsInsert(String mpsNo, String plantCd, String mpsNm, LocalDate horizonStart, int horizonDays, int fenceDays,
            LocalDate fenceEnd, Long baseMpsId, int itemCnt) {
    }

    public record RowInsert(long itemId, String unitCd, BigDecimal stockQty, BigDecimal safetyQty, String lotRule, BigDecimal lotQty,
            BigDecimal lotMult) {
    }

    public record CellInsert(long mpsRowId, LocalDate bucketDate, BigDecimal demandOrder, BigDecimal demandFcst, BigDecimal pastDueQty,
            BigDecimal supplyWo, BigDecimal propQty, BigDecimal baseQty, String fencedYn) {
    }
}
