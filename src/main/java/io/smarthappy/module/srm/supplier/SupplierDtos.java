package io.smarthappy.module.srm.supplier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.SortField;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/** 협력사 정보 DTO (SRM S1, docs/31 4장). 수량·단가는 응답에서 글자(trim_scale) — 자바스크립트 숫자로 정밀도를 잃지 않게 */
public final class SupplierDtos {

    private SupplierDtos() {
    }

    // ------------------------------------------------------------------ 협력사

    /** 구매 거래처 + 협력사 확장(등록 전이면 registered = false, 확장 칸 비어 있음) */
    @Getter
    @Setter
    public static class SupplierRow {
        private long custId;
        private String custCd;
        private String custNm;
        private String custType;
        private String custUseYn;
        private String bizNo;
        private String tel;
        private String email;
        private boolean registered;
        private Long buyerUserId;
        private String buyerLoginId;
        private String buyerNm;
        private String payTermsCd;
        private String currencyCd;
        private String countryCd;
        private String controlCountryCd;
        private String tradeStatus;
        private String statusReason;
        private LocalDate secAssessedOn;
        private String remark;
        private Integer rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
        /** 현재 인증서(유형별 최신 사용 행) 수와 그중 가장 이른 만료일 → 서비스가 certState 계산 */
        private int certCnt;
        private LocalDate certMinExpires;
        private String certState;
        private int sourceCnt;
    }

    /** 신규는 custId 필수, 수정은 rowVer 필수 */
    public record SupplierForm(Long custId, String buyerLoginId, String payTermsCd, String currencyCd, String countryCd,
            String controlCountryCd, String tradeStatus, String statusReason, String secAssessedOn, String remark,
            Integer rowVer) {
    }

    public record SupplierValue(long custId, Long buyerUserId, String payTermsCd, String currencyCd, String countryCd,
            String controlCountryCd, String tradeStatus, String statusReason, LocalDate secAssessedOn, String remark,
            Integer rowVer) {
    }

    // ------------------------------------------------------------------ 인증서

    @Getter
    @Setter
    public static class CertRow {
        private long certId;
        private long custId;
        private String certTypeCd;
        private String certNo;
        private String issuerNm;
        private LocalDate issuedOn;
        private LocalDate expiresOn;
        private String useYn;
        private String remark;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
        /** 같은 유형의 사용 중 행 중 가장 최근(발행일) 행인지 — 판정은 이 행으로 */
        private boolean current;
        private String state;
    }

    public record CertForm(String certTypeCd, String certNo, String issuerNm, String issuedOn, String expiresOn,
            String useYn, String remark, Integer rowVer) {
    }

    public record CertValue(String certTypeCd, String certNo, String issuerNm, LocalDate issuedOn, LocalDate expiresOn,
            String useYn, String remark, Integer rowVer) {
    }

    // ------------------------------------------------------------------ 공급 품목

    @Getter
    @Setter
    public static class SourceRow {
        private long sourceId;
        private long custId;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String itemUseYn;
        private String supplierItemCd;
        private String supplierItemNm;
        private int leadDays;
        private String moq;
        private String orderMult;
        private int priority;
        private String useYn;
        private String remark;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    public record SourceForm(String itemCd, String supplierItemCd, String supplierItemNm, Integer leadDays, String moq,
            String orderMult, Integer priority, String useYn, String remark, Integer rowVer) {
    }

    public record SourceValue(long itemId, String supplierItemCd, String supplierItemNm, int leadDays, BigDecimal moq,
            BigDecimal orderMult, int priority, String useYn, String remark, Integer rowVer) {
    }

    // ------------------------------------------------------------------ 단가

    @Getter
    @Setter
    public static class PriceRow {
        private long priceId;
        private long custId;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String currencyCd;
        private String unitPrice;
        private LocalDate validFrom;
        private LocalDate validTo;
        private String useYn;
        private String remark;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
        /** 오늘 기준 CURRENT / FUTURE / PAST, 사용 중지면 OFF */
        private String state;
    }

    public record PriceForm(String itemCd, String currencyCd, String unitPrice, String validFrom, String validTo,
            String useYn, String remark, Integer rowVer) {
    }

    // ------------------------------------------------------------------ 선택지

    @Getter
    @Setter
    public static class ItemOption {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String useYn;
    }

    /** 목록·grid 공통 조건(검증된 값) — MyBatis OGNL 이 읽게 getter 클래스. registered = Y / N / null(전체) */
    @Getter
    @AllArgsConstructor
    public static class SupplierCrit {
        private String keywordLike;
        private String tradeStatus;
        private String registered;
    }

    /** grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키. 인증 판정은 자바 계산이라 정렬 없음 */
    public enum SupplierSort implements SortField {
        CUST_CD("custCd"), CUST_NM("custNm"), TRADE_STATUS("tradeStatus"), SOURCE_CNT("sourceCnt"), BUYER("buyerNm");

        private final String field;

        SupplierSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** grid 응답 한 쪽 */
    public record SupplierPage(List<SupplierRow> list, Long total) {
    }

    /**
     * 협력사 상세 품질 요약 카드 (최근 {@code days} 일, 회사 날짜) — 입고 PPM 은 평가 RECV_PPM 과 같은 계산(QMS 계약 receiptQuality,
     * Σ불량 ÷ Σ검사 × 10⁶). ppmNote: NONE(검사 0·QMS 없음) / UNITS(단위가 둘 이상이라 더할 수 없음) / null(계산됨).
     * openNcrCnt = 그 기간에 발견된 처리 중(OPEN·PENDING) NCR(QMS 계약 ncrsOfSupplier — 잘렸으면 ncrTruncated).
     * openClaimCnt = 작성 중·보냄·회신 받음 클레임, returnsToShipCnt = 확정된(출고 대기) 반품 — 기간 없이 지금 열린 것
     */
    public record QualitySummary(LocalDate from, LocalDate to, String inspectedQty, String unitCd, String ppm, String ppmNote, int inspectionCnt,
            int ngCnt, int openNcrCnt, boolean ncrTruncated, int openClaimCnt, int returnsToShipCnt) {
    }
}
