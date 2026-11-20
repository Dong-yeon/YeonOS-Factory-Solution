package io.smarthappy.module.sales;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** 수주·내시 DTO (P2 2단계, docs/25 4.3). 응답 수량은 글자(trim_scale) — 브라우저 JSON.parse 의 Double 손실을 피한다(7단계 QA #2) */
public final class SalesDtos {

    private SalesDtos() {
    }

    // ------------------------------------------------------------------ 공통 선택지

    @Getter
    @Setter
    public static class CustOption {
        private long custId;
        private String custCd;
        private String custNm;
    }

    /** 거래처 참조(검증용 — 판매 여부·사용 여부 구분) */
    @Getter
    @Setter
    public static class CustRef {
        private long custId;
        private String custCd;
        private String custType;
        private String useYn;
    }

    @Getter
    @Setter
    public static class ItemOption {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String useYn;
    }

    // ------------------------------------------------------------------ 수주

    @Getter
    @Setter
    public static class OrderRow {
        private long orderId;
        private String orderNo;
        private long custId;
        private String custCd;
        private String custNm;
        private String custPoNo;
        private LocalDate orderDate;
        private String status;
        private String remark;
        private int lineCnt;
        private int openCnt;
        private LocalDate nextDue;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    @Getter
    @Setter
    public static class LineRow {
        private long lineId;
        private long orderId;
        private int lineNo;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String qty;
        private LocalDate dueDate;
        private String status;
        private String cancelReason;
        private String remark;
        /** 출하 수량 투영(docs/25 4.22) · 출하로 자동 마감이면 Y */
        private String shippedQty;
        private String doneByShipYn;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    /** dueLineCnt = 납기 조건이 있을 때 조건에 맞는 열린 행 수(홈 카드 sal-due-soon 과 같은 단위), 없으면 null */
    public record OrderList(List<OrderRow> rows, boolean more, Long dueLineCnt) {
    }

    public record OrderDetail(OrderRow order, List<LineRow> lines) {
    }

    /** 수량은 글자로 받는다(JSON 숫자 → Double 반올림을 피함, 7단계 QA #2 교훈) */
    public record LineForm(Integer lineNo, String itemCd, String qty, String dueDate, String remark, Integer rowVer) {
    }

    public record OrderForm(String custCd, String custPoNo, String orderDate, String remark, List<LineForm> lines,
            Integer rowVer) {
    }

    public record CloseForm(String reason, Integer rowVer) {
    }

    public record OrderValue(Long custId, String custPoNo, LocalDate orderDate, String remark) {
    }

    public record LineValue(Integer lineNo, Long itemId, BigDecimal qty, LocalDate dueDate, String remark) {
    }

    // ------------------------------------------------------------------ 내시

    @Getter
    @Setter
    public static class RevRow {
        private long revId;
        private long custId;
        private String custCd;
        private String custNm;
        private int revNo;
        private OffsetDateTime receivedAt;
        private String source;
        private Long importJobId;
        private String fileNm;
        private String remark;
        private String createdBy;
        private int lineCnt;
        private LocalDate firstBucket;
        private LocalDate lastBucket;
    }

    @Getter
    @Setter
    public static class FcstLineRow {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String bucketType;
        private LocalDate bucketStart;
        private String qty;
        private String firmYn;
    }

    /** 비교 한 줄 — 한쪽에만 있으면 그쪽 수량만(다른 쪽 null) */
    @Getter
    @Setter
    public static class CompareRow {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String bucketType;
        private LocalDate bucketStart;
        private String fromQty;
        private String toQty;
        private String fromFirm;
        private String toFirm;
    }

    public record FcstLineForm(String itemCd, String bucketType, String bucketStart, String qty, String firmYn) {
    }

    public record FcstForm(String custCd, String remark, List<FcstLineForm> lines) {
    }

    public record FcstLineValue(long itemId, String bucketType, LocalDate bucketStart, BigDecimal qty, String firmYn) {
    }

    public record CompareView(RevRow from, RevRow to, List<CompareRow> rows) {
    }

    /** 수주 그리드 정렬 허용 열(UI 단계 3 ①) — 기본은 기존 목록과 같은 수주일 최신 */
    public enum OrderSort implements SortField {
        ORDER_DATE("orderDate"), ORDER_NO("orderNo"), CUST("custNm"), NEXT_DUE("nextDue"), STATUS("status");

        private final String field;

        OrderSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 내시 버전 그리드 정렬 허용 열(UI 단계 3 ①) — 기본은 기존 목록과 같은 최신 버전 */
    public enum RevSort implements SortField {
        REV_NO("revNo"), RECEIVED_AT("receivedAt");

        private final String field;

        RevSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }
}
