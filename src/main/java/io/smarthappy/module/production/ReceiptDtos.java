package io.smarthappy.module.production;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** 생산 입고 DTO (P2 7a, docs/25 4.9) — 수량은 응답에서 글자 */
public final class ReceiptDtos {

    private ReceiptDtos() {
    }

    /** 입고 대상 지시(진행·완료·마감) */
    @Getter
    @Setter
    public static class ReceiptWo {
        private long woId;
        private String woNo;
        private String plantCd;
        private String itemCd;
        private String itemNm;
        private String planQty;
        private String unitCd;
        private String status;
        private LocalDate planStart;
        private String lastGoodQty;
        private String receivedQty;

        /** 엑셀 숫자 칸(docs/25 4.40) */
        public java.math.BigDecimal getPlanQtyNum() {
            return MesExport.num(planQty);
        }

        public java.math.BigDecimal getLastGoodQtyNum() {
            return MesExport.num(lastGoodQty);
        }

        public java.math.BigDecimal getReceivedQtyNum() {
            return MesExport.num(receivedQty);
        }
    }

    /** 입고 대상 지시 grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum ReceiptWoSort implements io.smarthappy.platform.common.query.SortField {
        WO_NO("woNo"), ITEM_CD("itemCd"), PLAN_START("planStart"), STATUS("status");

        private final String field;

        ReceiptWoSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 입고 대상 grid 엑셀(docs/25 4.40) */
    public record ReceiptWoExport(String plantCd, String status, String keyword, List<String> sort, List<String> columns) {
    }

    public record ReceiptWoPage(List<ReceiptWo> list, Long total) {
    }

    /** 마지막 공정 산출 LOT — 만든 양·순입고·남음 */
    @Getter
    @Setter
    public static class ReceiptLot {
        private long lotId;
        private String lotNo;
        private String qtyCreated;
        private String receivedQty;
        private String remainQty;
        private String holdYn;
        private OffsetDateTime occurredAt;
    }

    @Getter
    @Setter
    public static class LocOption {
        private long nodeId;
        private String nodeCd;
        private String nodeNm;
        private String nodeType;
        private String parentCd;
        /** 받으면 들어갈 재고 상태(WMS 판정, docs/25 4.45) — 지시를 주지 않았거나 WMS 없음(NONE)이면 null */
        private String status;
        /** null = 받을 수 있음. NODE_INACTIVE·STOCK_NOT_ALLOWED(WMS ReceiptLocQuery 사유) */
        private String reasonCd;
    }

    @Getter
    @Setter
    public static class ReceiptRow {
        private long receiptId;
        private long lotId;
        private String lotNo;
        private long nodeId;
        private String nodeCd;
        private String qty;
        private String unitCd;
        private String entryMethod;
        private Long reversalOfId;
        private boolean reversed;
        private String reason;
        private OffsetDateTime occurredAt;
        private String recordedBy;
    }

    public record ReceiptView(ReceiptWo wo, List<ReceiptLot> lots, List<ReceiptRow> history) {
    }

    public record ReceiveForm(String clientReqId, Long woId, Long lotId, Long nodeId, String qty) {
    }

    public record CorrectForm(String reason) {
    }

    public record ReceiveReply(long receiptId, boolean replayed) {
    }

    /** 입고 행 원본(멱등 재전송·정정 거울) */
    @Getter
    @Setter
    public static class ReceiptRef {
        private long receiptId;
        private long woId;
        private long lotId;
        private long nodeId;
        private BigDecimal qty;
        private Long reversalOfId;
        private boolean reversed;
    }

    public record NewReceipt(String plantCd, long woId, long itemId, long lotId, long nodeId, BigDecimal qty, String unitCd,
            String clientReqId, String reason, String entryMethod, Long reversalOfId) {
    }
}
