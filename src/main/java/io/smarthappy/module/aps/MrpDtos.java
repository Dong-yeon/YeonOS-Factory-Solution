package io.smarthappy.module.aps;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** MRP 자재소요계획 DTO (docs/38 19장). 응답 수량은 글자(trim_scale) — 브라우저 Double 손실을 피한다 */
public final class MrpDtos {

    private MrpDtos() {
    }

    // ---------- 요청 ----------

    /** 새 MRP 계산 — 첫날은 공장 오늘(고정), 일수 기본 28(7~92) */
    public record MrpForm(String plantCd, Integer horizonDays, String mrpNm) {
    }

    // ---------- 계산 입력(매퍼) ----------

    @Getter
    @Setter
    public static class ItemSrc {
        private long itemId;
        private String itemCd;
        private String procType;
        private String unitCd;
    }

    @Getter
    @Setter
    public static class ParamSrc {
        private long itemId;
        private BigDecimal safetyQty;
        private String lotRule;
        private BigDecimal lotQty;
        private BigDecimal lotMult;
        private Integer makeLeadDays;
    }

    @Getter
    @Setter
    public static class WoSrc {
        private long woId;
        private String woNo;
        private long itemId;
        private LocalDate planStart;
        private LocalDate planEnd;
        private BigDecimal remainQty;
    }

    @Getter
    @Setter
    public static class MpsCellSrc {
        private long mpsCellId;
        private long itemId;
        private LocalDate bucketDate;
        private BigDecimal planQty;
    }

    @Getter
    @Setter
    public static class CustSrc {
        private long custId;
        private String custCd;
    }

    // ---------- 저장 ----------

    public record MrpInsert(String mrpNo, String plantCd, String mrpNm, LocalDate horizonStart, int horizonDays, Long mpsId, int outDays,
            int itemCnt, int orderCnt, int excCnt) {
    }

    public record ItemInsert(long itemId, int llc, String procType, String unitCd, String mpsYn, BigDecimal stockQty, BigDecimal safetyQty,
            String lotRule, BigDecimal lotQty, BigDecimal lotMult, BigDecimal moq, BigDecimal orderMult, int leadDays, String leadSrc,
            Long custId) {
    }

    public record ElemInsert(int elemSeq, long itemId, String dir, String kind, LocalDate elemDate, BigDecimal qty, String pastDueYn,
            LocalDate startDate, String lateYn, Integer parentSeq, Long refId, String refNo) {
    }

    public record ExcInsert(long itemId, String excCd, Integer elemSeq, LocalDate curDate, LocalDate sugDate, BigDecimal qty, String refNo) {
    }

    // ---------- 응답 ----------

    @Getter
    @Setter
    public static class MrpRow {
        private long mrpId;
        private String mrpNo;
        private String plantCd;
        private String mrpNm;
        private LocalDate horizonStart;
        private int horizonDays;
        private Long mpsId;
        private String mpsNo;
        private int outDays;
        private String status;
        private int itemCnt;
        private int orderCnt;
        private int excCnt;
        private OffsetDateTime calculatedAt;
        private String calculatedBy;
        private String discardReason;
        private int rowVer;
    }

    public record MrpList(List<MrpRow> list, boolean more) {
    }

    @Getter
    @Setter
    public static class ItemRow {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private int llc;
        private String procType;
        private String unitCd;
        private String mpsYn;
        private String stockQty;
        private String safetyQty;
        private String lotRule;
        private String lotQty;
        private String lotMult;
        private String moq;
        private String orderMult;
        private int leadDays;
        private String leadSrc;
        private String custCd;
        private int orderCnt;
        private String orderQty;
        private int excCnt;
    }

    @Getter
    @Setter
    public static class ExcRow {
        private long excId;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String excCd;
        private Integer elemSeq;
        private String elemKind;
        private LocalDate curDate;
        private LocalDate sugDate;
        private String qty;
        private String refNo;
    }

    public record MrpDetail(MrpRow mrp, List<ItemRow> items, List<ExcRow> excs, boolean excMore) {
    }

    /** 요소 한 줄(계획 주문·재고/소요 목록·페깅 사슬 공용) */
    @Getter
    @Setter
    public static class ElemRow {
        private int elemSeq;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String dir;
        private String kind;
        private LocalDate elemDate;
        private String qty;
        private String pastDueYn;
        private LocalDate startDate;
        private String lateYn;
        private Integer parentSeq;
        private Long refId;
        private String refNo;
        private String procType;
    }

    public record OrderList(List<ElemRow> list, boolean more) {
    }

    @Getter
    @Setter
    public static class PegRow {
        private int demandSeq;
        private int supplySeq;
        private String qty;
    }

    /** 재고/소요 목록 한 줄 — pab = 이 줄까지 예상 재고, pegs = 이 요소의 페깅 상대(상대 요소 번호·수량) */
    public record ListLine(ElemRow elem, String pab, List<PegLink> pegs) {
    }

    public record PegLink(int seq, String itemCd, String kind, LocalDate elemDate, String refNo, String qty) {
    }

    public record ItemList(ItemRow item, List<ListLine> lines) {
    }

    /** 페깅 사슬 마디 — parent = 사슬에서 아래(시작 쪽) 마디 번호(첫 마디 -1), qty = 아래 마디와 이은 수량 */
    public record PegNode(int idx, int parent, ElemRow elem, String qty) {
    }

    public record PegChain(List<PegNode> nodes, boolean more) {
    }
}
