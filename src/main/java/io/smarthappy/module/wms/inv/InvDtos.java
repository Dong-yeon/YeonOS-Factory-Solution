package io.smarthappy.module.wms.inv;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** 재고 원장·현재고 DTO (P4 W3, docs/30 4.3) */
public final class InvDtos {

    private InvDtos() {
    }

    /**
     * 원장 한 행 요청 — {@link InventoryService#post} 가 받는다(다른 모듈·2부 출처도 이 모양으로).
     * qty 는 부호 있는 기본 단위 수량(들어오면 +, 나가면 −). 이동·상태 바꿈은 같은 moveGrpId 의 두 행.
     */
    public record TrxReq(String trxType, long itemId, long nodeId, String stockStatus, BigDecimal qty, UUID moveGrpId,
            String srcType, Long srcId, String reasonCd, String remark, OffsetDateTime occurredAt, String entryMethod, Long performedBy,
            Long lotId, Long poLineId, BigDecimal poQty) {

        /** 발주 행 없는 행(구매 입고 RCV_PO 밖 전부) */
        public TrxReq(String trxType, long itemId, long nodeId, String stockStatus, BigDecimal qty, UUID moveGrpId, String srcType,
                Long srcId, String reasonCd, String remark, OffsetDateTime occurredAt, String entryMethod, Long performedBy, Long lotId) {
            this(trxType, itemId, nodeId, stockStatus, qty, moveGrpId, srcType, srcId, reasonCd, remark, occurredAt, entryMethod,
                    performedBy, lotId, null, null);
        }

        /** LOT 없는 행(1부 화면·실사) */
        public TrxReq(String trxType, long itemId, long nodeId, String stockStatus, BigDecimal qty, UUID moveGrpId, String srcType,
                Long srcId, String reasonCd, String remark, OffsetDateTime occurredAt, String entryMethod, Long performedBy) {
            this(trxType, itemId, nodeId, stockStatus, qty, moveGrpId, srcType, srcId, reasonCd, remark, occurredAt, entryMethod,
                    performedBy, null, null, null);
        }

        public TrxReq withLot(Long lot) {
            return new TrxReq(trxType, itemId, nodeId, stockStatus, qty, moveGrpId, srcType, srcId, reasonCd, remark, occurredAt,
                    entryMethod, performedBy, lot, poLineId, poQty);
        }

        /** 구매 입고 발주 행·발주 단위 수량(B4b) */
        public TrxReq withPo(Long poLine, BigDecimal poQ) {
            return new TrxReq(trxType, itemId, nodeId, stockStatus, qty, moveGrpId, srcType, srcId, reasonCd, remark, occurredAt,
                    entryMethod, performedBy, lotId, poLine, poQ);
        }
    }

    /** 게시 결과 — replay = 같은 clientReqId 로 이미 들어간 요청(처음 결과를 돌려줌) */
    /** warnings = 적치 검사 경고(W-12, 정책 WARN — 저장은 됨), 없으면 빈 목록 */
    public record PostResult(List<Long> trxIds, boolean replay, List<String> warnings) {
        public PostResult(List<Long> trxIds, boolean replay) {
            this(trxIds, replay, List.of());
        }

        public PostResult withWarnings(List<String> w) {
            return w == null || w.isEmpty() ? this : new PostResult(trxIds, replay, w);
        }
    }

    // ---------------------------------------------------------------- 화면 입력

    /** 재고 조정(증감). delta 부호 = 방향, 사유 코드 필수. occurredAt = 공장 현지 시각(yyyy-MM-ddTHH:mm), 비우면 지금 */
    public record AdjustForm(String plantCd, Long nodeId, Long itemId, String stockStatus, BigDecimal delta, String reasonCd,
            String remark, String occurredAt, Long performedBy, String entryMethod, String clientReqId, String lotNo) {
    }

    /** 이동(위치·상태). 같은 위치면 상태 바꿈, 다르면 이동 */
    public record MoveForm(String plantCd, Long itemId, Long fromNodeId, String fromStatus, Long toNodeId, String toStatus,
            BigDecimal qty, String remark, String occurredAt, Long performedBy, String entryMethod, String clientReqId, String lotNo) {
    }

    /** 반품 출고(R4-4) — 한 반품 지시를 칸 여러 개에서, 합 = 지시 수량(기본 단위) */
    public record RtnIssueForm(String returnNo, List<RtnIssueRow> rows, String remark, String occurredAt, String entryMethod,
            String clientReqId) {
    }

    public record RtnIssueRow(Long nodeId, String stockStatus, BigDecimal qty) {
    }

    /** 반품 출고 후보 재고 칸 */
    @Getter
    @Setter
    public static class RtnStock {
        private long nodeId;
        private String nodeCd;
        private String path;
        private String stockStatus;
        private BigDecimal qty;
    }

    public record ReverseForm(String reason) {
    }

    public record ScanForm(String plantCd, String text) {
    }

    public record ScanHit(String type, Long nodeId, String nodeCd, String path, Long itemId, String itemCd, String itemNm,
            String unitCd, boolean ok, String message, Long lotId, String lotNo) {

        public ScanHit(String type, Long nodeId, String nodeCd, String path, Long itemId, String itemCd, String itemNm, String unitCd,
                boolean ok, String message) {
            this(type, nodeId, nodeCd, path, itemId, itemCd, itemNm, unitCd, ok, message, null, null);
        }
    }

    /** LOT (P2 lot_master — 읽기만) */
    @Getter
    @Setter
    public static class LotOpt {
        private long lotId;
        private String lotNo;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String lotCtrlYn;
        private String holdYn;
    }

    // ---------------------------------------------------------------- 조회

    @Getter
    @Setter
    public static class StockRow {
        private long stockId;
        private String plantCd;
        private long nodeId;
        private String nodeCd;
        private String path;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String spec;
        private String unitCd;
        private Long lotId;
        private String lotNo;
        private String stockStatus;
        private BigDecimal qty;
        private long lastTrxId;
        private OffsetDateTime updatedAt;
    }

    @Getter
    @Setter
    public static class TrxRow {
        private long trxId;
        private Long lotId;
        private String lotNo;
        private String plantCd;
        private String trxType;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private long nodeId;
        private String nodeCd;
        private String path;
        private String stockStatus;
        private BigDecimal qty;
        /** uuid 글자 — MyBatis 에 uuid 타입 처리기가 없어 글자로 받는다 */
        private String moveGrpId;
        private String srcType;
        private Long srcId;
        private String reasonCd;
        private String remark;
        private OffsetDateTime occurredAt;
        private OffsetDateTime recordedAt;
        private String entryMethod;
        private String recordedBy;
        private Long performedBy;
        private String performedByNm;
        private Long reversalOfId;
        /** 이 행을 되돌린 역분개 id (있으면 이미 되돌림) */
        private Long reversedById;
        /** 멱등 키(구매 입고 되돌림 때 SRM 이력을 찾는 근거 RCV_PO:{요청 id}) */
        private String clientReqId;
        /** 구매 입고 발주 행·발주 단위 수량(B4b) */
        private Long poLineId;
        private BigDecimal poQty;
    }

    /** PDA 입고 위치 후보 원재료(rcvNodes) */
    @Getter
    @Setter
    public static class RcvNode extends NodeOpt {
        private boolean underScrap;
    }

    /**
     * PDA 입고 위치 후보(docs/30 12.12.15) — status = 받으면 들어갈 상태(위치 기본 상태 → 수입검사 QC 고정, 저장과 같은 판정),
     * reasonCd null = 받을 수 있음. STOCK_NOT_ALLOWED · STAGING_NOT_ALLOWED · SCRAP_LOCATION
     */
    public record RcvLoc(long nodeId, String nodeCd, String path, String status, String reasonCd) {
    }

    @Getter
    @Setter
    public static class NodeOpt {
        private long nodeId;
        private String nodeType;
        private String nodeCd;
        private String nodeNm;
        private String path;
        private String locUsage;
        private String defaultStatus;
        private String stockYn;
    }

    @Getter
    @Setter
    public static class ItemOpt {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String spec;
        private String unitCd;
        private String lotCtrlYn;
        private String useYn;
    }

    @Getter
    public static class StockSearch {
        private final String plantCd;
        private final Long nodeId;
        private final boolean withChildren;
        private final String itemCdLike;
        private final String itemNmLike;
        private final String stockStatus;
        private final boolean hideZero;
        private final PageQuery page;
        private final String lotNoLike;

        public StockSearch(String plantCd, Long nodeId, boolean withChildren, String itemCdLike, String itemNmLike, String stockStatus,
                boolean hideZero, PageQuery page, String lotNoLike) {
            this.lotNoLike = lotNoLike;
            this.plantCd = plantCd;
            this.nodeId = nodeId;
            this.withChildren = withChildren;
            this.itemCdLike = itemCdLike;
            this.itemNmLike = itemNmLike;
            this.stockStatus = stockStatus;
            this.hideZero = hideZero;
            this.page = page;
        }
    }

    @Getter
    public static class TrxSearch {
        private final OffsetDateTime fromAt;
        private final OffsetDateTime toAt;
        private final String plantCd;
        private final Long itemId;
        private final String itemCdLike;
        private final Long nodeId;
        private final String trxType;
        private final PageQuery page;

        public TrxSearch(OffsetDateTime fromAt, OffsetDateTime toAt, String plantCd, Long itemId, String itemCdLike, Long nodeId,
                String trxType, PageQuery page) {
            this.fromAt = fromAt;
            this.toAt = toAt;
            this.plantCd = plantCd;
            this.itemId = itemId;
            this.itemCdLike = itemCdLike;
            this.nodeId = nodeId;
            this.trxType = trxType;
            this.page = page;
        }
    }

    public record StockQuery(String plantCd, Long nodeId, Boolean withChildren, String itemCd, String itemNm, String stockStatus,
            Boolean hideZero, String lotNo) {
    }

    public record TrxQuery(LocalDate from, LocalDate to, String plantCd, Long itemId, String itemCd, Long nodeId, String trxType) {
    }

    /** 엑셀 — hb.grid 가 조회 조건을 평평하게(+ sort·columns) 보낸다 */
    public record StockExport(String plantCd, Long nodeId, Boolean withChildren, String itemCd, String itemNm, String stockStatus,
            Boolean hideZero, String lotNo, List<String> sort, List<String> columns) {

        StockQuery query() {
            return new StockQuery(plantCd, nodeId, withChildren, itemCd, itemNm, stockStatus, hideZero, lotNo);
        }
    }

    public record TrxExport(LocalDate from, LocalDate to, String plantCd, Long itemId, String itemCd, Long nodeId, String trxType,
            List<String> sort, List<String> columns) {

        TrxQuery query() {
            return new TrxQuery(from, to, plantCd, itemId, itemCd, nodeId, trxType);
        }
    }

    @Getter
    @Setter
    public static class Mismatch {
        private long itemId;
        private long nodeId;
        private Long lotId;
        private String stockStatus;
        private BigDecimal ledgerQty;
        private BigDecimal stockQty;
    }

    @Getter
    @Setter
    public static class ReconcileRow {
        private long runId;
        private OffsetDateTime runAt;
        private int keyCount;
        private int mismatchCount;
        private Integer missingReceipts;
        /** 자재 입고 원장 합이 입고 LOT 수량보다 많은 LOT 수(B4a) */
        private Integer rcvMatOver;
        /** SRM 발주 행 입고 수량 ≠ 원장 RCV_PO 합인 발주 행 수(B4b) */
        private Integer rcvPoMismatch;
        /** 품질 연동 공장의 QC 재고 LOT 중 판정이 끝났는데 남은 LOT 수(B3) */
        private Integer qcJudged;
        /** 무효 검사인데 되돌리지 않은 판정 원장이 남은 검사 수(B3) */
        private Integer qmsVoidLive;
        private String detail;
        private String runBy;
    }

    // ---------------------------------------------------------------- 자재 입고 (2부 B4a, docs/30 12.3)

    /**
     * 자재 입고. qty = 품목 기본 단위. clientReqId 필수(누른 순간 만든 id — 다시 보내면 처음 결과). expiry 는 라벨 글자로만 쓰고 저장하지 않는다(D-B9).
     * printerId 가 있으면 LOT 라벨을 인쇄 대기열에(tplId 비우면 기본 템플릿)
     */
    public record RcvForm(String plantCd, Long nodeId, Long itemId, BigDecimal qty, String stockStatus, String supplierCd, String supplierLotNo,
            String occurredAt, String remark, String entryMethod, String clientReqId, Long printerId, Long tplId, Integer copies,
            String expiry, Long poLineId, Long asnLineId) {
    }

    /**
     * 입고 화면 발주 행 선택지(B4b, SRM PoSupplyQuery 를 발주 행으로 합침).
     * @param openQty    열린 수량 — 발주 단위(입력 수량과 같은 단위, SRM 2부 R2 Supply.openPoQty)
     * @param unitCd     발주 단위
     * @param dueDate    가장 이른 약속일·납기일
     */
    public record PoLineOpt(long poLineId, String poNo, int lineNo, String supplierCd, java.time.LocalDate dueDate, BigDecimal openQty,
            String unitCd) {
    }

    /** @param labelJobs 인쇄 대기열에 넣은 건수(replay 면 0) */
    /** warnings = 적치 검사 경고(W-12, 정책 WARN — 저장은 됨), 없으면 빈 목록 */
    public record RcvResult(long trxId, String lotNo, boolean replay, int labelJobs, List<String> warnings) {
        public RcvResult(long trxId, String lotNo, boolean replay, int labelJobs) {
            this(trxId, lotNo, replay, labelJobs, List.of());
        }
    }

    /** 입고 화면 스캔 — GS1 이면 (01) GTIN → 품목(품목 속성 GTIN, D-B10), (10) → 공급사 LOT, (17) → 유통기한, (30)(37) → 수량 */
    public record RcvScan(String type, boolean ok, String message, Long nodeId, String path, Long itemId, String itemCd, String itemNm,
            String unitCd, String supplierLotNo, java.time.LocalDate expiry, BigDecimal qty, String asnNo) {
    }

    /** 인쇄 선택지(주소·본문 없이) */
    public record PrintOpt(long id, String cd, String nm) {
    }

    @Getter
    @Setter
    public static class RcvReplay {
        private long trxId;
        private String lotNo;
    }

    public enum StockSort implements SortField {
        ITEM_CD("itemCd"), PATH("path"), QTY("qty"), UPDATED_AT("updatedAt"), LOT_NO("lotNo");

        private final String field;

        StockSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public enum TrxSort implements SortField {
        OCCURRED_AT("occurredAt"), TRX_ID("trxId");

        private final String field;

        TrxSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }
}
