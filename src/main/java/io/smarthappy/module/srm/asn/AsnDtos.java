package io.smarthappy.module.srm.asn;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.SortField;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/** 사전 납품 통지 DTO (SRM 2부 R3a, docs/31 4.11). 수량은 응답에서 글자(trim_scale), 발주 단위 */
public final class AsnDtos {

    private AsnDtos() {
    }

    @Getter
    @Setter
    public static class AsnRow {
        private long asnId;
        private String asnNo;
        private long custId;
        private String custCd;
        private String custNm;
        private String plantCd;
        private String plantNm;
        private LocalDate shipDate;
        private LocalDate etaDate;
        private String supplierRef;
        private String carrierMemo;
        private String source;
        private String status;
        private OffsetDateTime sentAt;
        private String sentBy;
        private String cancelReason;
        private String remark;
        private int lineCnt;
        private int openLineCnt;
        /** 도착 예정일이 지났는데 남은 행이 있음(확정·입고 중) */
        private boolean late;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    @Getter
    @Setter
    public static class AsnLineRow {
        private long asnLineId;
        private int lineNo;
        private long poLineId;
        private long poId;
        private String poNo;
        private int poLineNo;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String qty;
        private String recvQty;
        private String remainQty;
        private String supplierLotNo;
        private LocalDate mfgDate;
        private LocalDate expiryDate;
        private Integer packCnt;
        private String remark;
    }

    public record AsnDetail(AsnRow asn, List<AsnLineRow> lines) {
    }

    /** 고를 수 있는 발주 행 — 그 협력사·공장의 발행된 원천 SRM 발주의 열린 행. 통지 가능 = 받을 수 있는 양 − 다른 살아 있는 ASN 남은 양 */
    @Getter
    @Setter
    public static class PoLineOption {
        private long poLineId;
        private String poNo;
        private int lineNo;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String openQty;
        private String notifiedQty;
        private String notifiableQty;
        private LocalDate dueDate;
    }

    @Getter
    @Setter
    public static class PoLineRef {
        private long poLineId;
        private long itemId;
        private String unitCd;
        private long custId;
        private String plantCd;
        private String status;
        private String source;
        private String lineStatus;
        private Integer unitScale;
        /** 엑셀 검사용(findPoLineByNo 만 채움) — 받을 수 있는 양 − 다른 살아 있는 ASN 남은 양 */
        private java.math.BigDecimal notifiable;
    }

    @Getter
    @Setter
    public static class SupplierRef {
        private long custId;
        private String custCd;
        private String custNm;
        private String tradeStatus;
    }

    @Getter
    @Setter
    public static class PlantOption {
        private String plantCd;
        private String plantNm;
    }

    /** 확정 한도 판정 — 발주 행마다 받을 수 있는 양 − 다른 살아 있는 ASN 남은 양(allowed) 과 이 ASN 합(mine) */
    @Getter
    @Setter
    public static class LimitRow {
        private long poLineId;
        private java.math.BigDecimal allowed;
        private java.math.BigDecimal mine;
    }

    public record AsnLineForm(Long poLineId, String qty, String supplierLotNo, String mfgDate, String expiryDate, String packCnt,
            String remark) {
    }

    public record AsnForm(String custCd, String plantCd, String shipDate, String etaDate, String supplierRef, String carrierMemo,
            String remark, Integer rowVer, List<AsnLineForm> lines) {
    }

    public record ActionForm(Integer rowVer, String reason) {
    }

    /** 검사를 지난 값 */
    record AsnValue(long custId, String plantCd, LocalDate shipDate, LocalDate etaDate, String supplierRef, String carrierMemo,
            String remark) {
    }

    record AsnLineValue(long poLineId, long itemId, String unitCd, java.math.BigDecimal qty, String supplierLotNo, LocalDate mfgDate,
            LocalDate expiryDate, Integer packCnt, String remark) {
    }

    /** 목록·grid 공통 조건(검증된 값) — MyBatis OGNL 이 읽게 getter 클래스. today = 늦음 판정 기준(회사 날짜) */
    @Getter
    @AllArgsConstructor
    public static class AsnCrit {
        private LocalDate from;
        private LocalDate to;
        private String status;
        private String keywordLike;
        private boolean late;
        private LocalDate today;
    }

    /** grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum AsnSort implements SortField {
        ASN_NO("asnNo"), SUPPLIER("custNm"), PLANT("plantCd"), SHIP_DATE("shipDate"), ETA_DATE("etaDate"),
        OPEN_LINES("openLineCnt"), STATUS("status");

        private final String field;

        AsnSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** grid 응답 한 쪽 */
    public record AsnPage(List<AsnRow> list, Long total) {
    }
}
