package io.smarthappy.module.srm.ack;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.SortField;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/** 납기 회신 DTO (SRM S5, docs/31 4.5). 수량은 응답에서 글자(trim_scale) */
public final class AckDtos {

    private AckDtos() {
    }

    /** 회신할 발주(목록·상세 머리) — 유효 회신 = 지금 개정의 가장 큰 순번 */
    @Getter
    @Setter
    public static class AckPoRow {
        private long poId;
        private String poNo;
        private int rev;
        private LocalDate poDate;
        private String custCd;
        private String custNm;
        private String status;
        private OffsetDateTime issuedAt;
        private String currencyCd;
        private String totalAmt;
        private int lineCnt;
        private int rowVer;
        private Integer ackSeq;
        private String ackStatus;
        private LocalDate ackOn;
        /** 회신 기한 = 발행일(회사 시간대) + 정책 SRM_ACK_DUE_DAYS */
        private LocalDate ackDueDate;
        /** 기한이 지났는데 유효 회신이 없거나 거절 */
        private boolean overdue;
    }

    @Getter
    @Setter
    public static class AckPoLine {
        private long poLineId;
        private int lineNo;
        private String itemCd;
        private String itemNm;
        private String supplierItemCd;
        private String unitCd;
        private String qty;
        /** 열린 수량 = 수량 − 취소 수량 (2부: − 입고) */
        private String openQty;
        private LocalDate dueDate;
        private String lineStatus;
        /** 유효 회신의 약속 합·첫 날·끝 날(없으면 null) */
        private String promisedQty;
        private LocalDate promisedFirst;
        private LocalDate promisedLast;
    }

    @Getter
    @Setter
    public static class AckRow {
        private long ackId;
        private int ackSeq;
        private int rev;
        private String source;
        private String ackStatus;
        private String rejectReason;
        private String ackByNm;
        private LocalDate ackOn;
        private String remark;
        private OffsetDateTime createdAt;
        private String createdBy;
        /** 지금 개정의 가장 큰 순번 */
        private boolean valid;
        /** 이전 개정에 대한 회신(유효 아님) */
        private boolean oldRev;
    }

    @Getter
    @Setter
    public static class AckLineRow {
        private long ackLineId;
        private long ackId;
        private long poLineId;
        private int lineNo;
        private String itemCd;
        private LocalDate promisedDate;
        private String promisedQty;
        private String remark;
    }

    public record AckDetail(AckPoRow po, List<AckPoLine> lines, List<AckRow> acks, List<AckLineRow> ackLines) {
    }

    /**
     * 회신 입력. rev = 화면이 본 개정(다르면 409 — 그 사이 개정됨), reject = 거절(사유 필수, 행 없음).
     * 행 = 발주 행 × 약속 납기 × 약속 수량(같은 발주 행 여러 줄 = 분할 납기)
     */
    public record AckForm(Integer rev, String ackOn, String ackByNm, Boolean reject, String rejectReason, String remark,
            List<AckLineForm> lines) {
    }

    public record AckLineForm(Long poLineId, String promisedDate, String promisedQty, String remark) {
    }

    public record AckLineValue(long poLineId, LocalDate promisedDate, BigDecimal promisedQty, String remark) {
    }

    /** 경고 — SHORT(약속 합 < 열린 수량, 약속 없는 행 포함) / LATE(요구 납기보다 늦은 약속) */
    public record Warning(int lineNo, String kind) {
    }

    public record AckResult(long ackId, int ackSeq, String ackStatus, List<Warning> warnings) {
    }

    /** 상태 계산 재료 — 열린 행마다 */
    @Getter
    @Setter
    public static class AckCheck {
        private int lineNo;
        private BigDecimal openQty;
        private BigDecimal promised;
        private LocalDate dueDate;
        private LocalDate lastDate;
    }

    /** 엑셀 회신용 발주 찾기 */
    @Getter
    @Setter
    public static class PoRef {
        private long poId;
        private String poNo;
        private int rev;
        private String status;
        private String custCd;
        private LocalDate poDate;
    }

    @Getter
    @Setter
    public static class PoLineRef {
        private long poLineId;
        private int lineNo;
        private String unitCd;
        private BigDecimal openQty;
        private String lineStatus;
    }

    /** 발주 진행 현황 한 줄 = 열린 발주 행 */
    @Getter
    @Setter
    public static class StatusRow {
        private long poLineId;
        private long poId;
        private String poNo;
        private int rev;
        private LocalDate poDate;
        private String custCd;
        private String custNm;
        private String status;
        private int lineNo;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String openQty;
        /** 입고 수량(발주 단위, 2부 R1) */
        private String recvQty;
        private LocalDate dueDate;
        private String ackStatus;
        private LocalDate ackDueDate;
        private String promisedQty;
        private LocalDate promisedFirst;
        private LocalDate promisedLast;
        /** 회신 기한이 지났는데 유효 회신 없음(또는 거절) */
        private boolean overdue;
        /** 약속 끝 날 − 요구 납기 (> 0 이면 늦음) */
        private Integer lateDays;
        /** 요구 납기 − 오늘 */
        private int dueInDays;
    }

    /** 목록·grid 공통 조건(검증된 값) — MyBatis OGNL 이 읽게 getter 클래스. zone·dueDays = 회신 기한 계산 */
    @Getter
    @AllArgsConstructor
    public static class AckCrit {
        private LocalDate from;
        private LocalDate to;
        private String state;
        private String keywordLike;
        private String zone;
        private int dueDays;
    }

    /** grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum AckSort implements SortField {
        PO_NO("poNo"), PO_DATE("poDate"), SUPPLIER("custNm"), ISSUED_AT("issuedAt"), ACK_DUE("ackDueDate"), ACK_STATUS("ackStatus"),
        STATUS("status");

        private final String field;

        AckSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** grid 응답 한 쪽 */
    public record AckPage(List<AckPoRow> list, Long total) {
    }

    /** 진행 현황 목록·grid 공통 조건(검증된 값) — today·zone·dueDays·soonDays = 회신 기한·늦음·임박 계산 */
    @Getter
    @AllArgsConstructor
    public static class StatusCrit {
        private LocalDate from;
        private LocalDate to;
        private String view;
        private String custCd;
        private String keywordLike;
        private LocalDate today;
        private String zone;
        private int dueDays;
        private int soonDays;
    }

    /** grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum StatusSort implements SortField {
        PO_NO("poNo"), SUPPLIER("custNm"), LINE_NO("lineNo"), ITEM("itemCd"), DUE_DATE("dueDate"), ACK_STATUS("ackStatus"), PROMISED_LAST("promisedLast"), LATE_DAYS("lateDays");

        private final String field;

        StatusSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** grid 응답 한 쪽 */
    public record StatusPage(List<StatusRow> list, Long total) {
    }
}
