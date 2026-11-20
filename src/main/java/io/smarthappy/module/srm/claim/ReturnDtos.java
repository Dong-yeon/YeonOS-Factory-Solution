package io.smarthappy.module.srm.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.SortField;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/** 반품 지시 DTO (SRM 2부 R4c, docs/31 4.12.4). 수량은 응답에서 글자(trim_scale), LOT 단위 */
public final class ReturnDtos {

    private ReturnDtos() {
    }

    @Getter
    @Setter
    public static class ReturnRow {
        private long returnId;
        private String returnNo;
        private long custId;
        private String custCd;
        private String custNm;
        private String plantCd;
        private String plantNm;
        private long ncrId;
        private String ncrNo;
        private int ncrLineNo;
        private long lotId;
        private String lotNo;
        private Long poLineId;
        private String poNo;
        private Integer poLineNo;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String qty;
        private String replaceYn;
        private String status;
        private OffsetDateTime confirmedAt;
        private String confirmedBy;
        private OffsetDateTime shippedAt;
        /** 같은 NCR 을 근거로 한 살아 있는(취소 아님) 클레임 — 상세에서만 채운다 */
        private List<LinkRow> claims;
        /** 출고 근거(WMS RTN_ISS:{요청 id}) — 출고됨일 때만 */
        private String shipRef;
        /** 교체품 대기 — 그 발주 행의 열린 수량(발주 − 취소 − 입고, 0 아래면 0)·발주 단위 */
        private String poOpenQty;
        private String poUnitCd;
        private String cancelReason;
        private String remark;
        private int rowVer;
        private OffsetDateTime createdAt;
        private String createdBy;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    /**
     * 반품 대상 한 줄 — QMS 계약 SupplierReturn + 이 줄의 살아 있는 반품 번호(있으면 새로 못 만든다).
     * 목록 표시용만(합을 내지 않는다 — QQ-3)
     */
    public record Candidate(long ncrId, String ncrNo, int lineNo, long lotId, String lotNo, String supplierLotNo, long itemId, String itemCd,
            String qty, String unitCd, String defectCd, String defectNm, OffsetDateTime closedAt, String returnNo) {
    }

    /** truncated = QMS 상한에서 잘렸음(기간을 줄여 다시) */
    public record CandidatePage(List<Candidate> rows, boolean truncated) {
    }

    /** 교체 요구 반품이 고를 발주 행 — 그 협력사·품목·단위의 받은 것이 있는 SRM 발주 행 */
    @Getter
    @Setter
    public static class PoLineOption {
        private long poLineId;
        private String poNo;
        private int lineNo;
        private LocalDate poDate;
        private String status;
        private String recvQty;
        private String unitCd;
    }

    @Getter
    @Setter
    public static class PoLineRef {
        private long poLineId;
        private long custId;
        private long itemId;
        private String unitCd;
        /** 발주 단위 → 기본 단위, 반품 단위 → 기본 단위(같으면 1, 환산표 없으면 null), 발주 단위 자릿수 (R4c-단위) */
        private BigDecimal poFactor;
        private BigDecimal retFactor;
        private Integer poScale;
    }

    @Getter
    @Setter
    public static class SupplierRef {
        private long custId;
        private String custCd;
        private String custNm;
    }

    @Getter
    @Setter
    public static class PlantOption {
        private String plantCd;
        private String plantNm;
    }

    public record ReturnForm(String custCd, String plantCd, Long ncrId, Integer lineNo, String qty, String replaceYn, Long poLineId,
            String remark, Integer rowVer) {
    }

    public record ActionForm(Integer rowVer, String reason) {
    }

    /** 검사를 지난 값 — 만들 때만 근거(NCR·LOT·품목)를 채운다 */
    record ReturnValue(long custId, String plantCd, long ncrId, String ncrNo, int lineNo, long lotId, String lotNo, long itemId, String unitCd,
            BigDecimal qty, String replaceYn, Long poLineId, String remark) {
    }

    /** 목록·grid 공통 조건(검증된 값) — 기간 = 만든 날(회사 날짜) */
    @Getter
    @AllArgsConstructor
    public static class ReturnCrit {
        private LocalDate from;
        private LocalDate to;
        private String zone;
        private String status;
        private String keywordLike;
    }

    /** grid 정렬 허용 목록 — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum ReturnSort implements SortField {
        RETURN_NO("returnNo"), SUPPLIER("custNm"), NCR_NO("ncrNo"), ITEM("itemCd"), CREATED_AT("createdAt"), STATUS("status");

        private final String field;

        ReturnSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record ReturnPage(List<ReturnRow> list, Long total) {
    }

    /**
     * 반품 ↔ 클레임 연결 한 줄(같은 근거 NCR) — 상세 화면이 그 번호로 상대 화면을 연다(셸 링크 조건 keyword·from·to).
     * createdOn = 만든 날(회사 날짜, 상대 목록의 기간 조건)
     */
    @Getter
    @Setter
    public static class LinkRow {
        private long id;
        private String no;
        private String status;
        private String ncrNo;
        private LocalDate createdOn;
    }
}
