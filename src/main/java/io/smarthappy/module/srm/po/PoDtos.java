package io.smarthappy.module.srm.po;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.SortField;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/** 발주 DTO (SRM S3, docs/31 4장). 수량·단가·금액은 응답에서 글자(trim_scale) */
public final class PoDtos {

    private PoDtos() {
    }

    @Getter
    @Setter
    public static class PoRow {
        private long poId;
        private String poNo;
        private int rev;
        private LocalDate poDate;
        private long custId;
        private String custCd;
        private String custNm;
        private String tradeStatus;
        private String plantCd;
        private String plantNm;
        private Long whId;
        private String whCd;
        private String whNm;
        private String currencyCd;
        private String payTermsCd;
        private long buyerUserId;
        private String buyerNm;
        private String status;
        private Long aprRequestId;
        private OffsetDateTime issuedAt;
        private String issuedBy;
        private String cancelReason;
        private String totalAmt;
        private String source;
        /** ERP 가 주인인 발주(source = ERP)의 ERP 발주 번호 — 외부 ID 매핑(S3b) */
        private String erpPoNo;
        private String remark;
        private int lineCnt;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
        /** 지금 합계·정책으로 결재가 필요한지(작성 중일 때 화면이 "결재 상신" / "확정" 을 고른다) */
        private boolean approvalRequired;
        /** 결재 중일 때: 지금 내 차례인지 / 내가 상신했는지(화면이 승인·반려 또는 회수 버튼을 고른다 — 판정은 결재 API) */
        private boolean myTurn;
        private boolean myRequest;
    }

    @Getter
    @Setter
    public static class PoLineRow {
        private long poLineId;
        private long poId;
        private int lineNo;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String itemUseYn;
        private String supplierItemCd;
        private String unitCd;
        private String qty;
        private String unitPrice;
        private String amt;
        private LocalDate dueDate;
        private Long prLineId;
        private String prNo;
        private String cancelQty;
        private String lineStatus;
        private String remark;
        /** 요구 납기 < 발주일 + 리드타임 */
        private boolean leadShort;
        /** 수량 < 최소 발주 */
        private boolean moqShort;
        /** 수량이 발주 배수가 아님 */
        private boolean multOff;
    }

    @Getter
    @Setter
    public static class RevRow {
        private int rev;
        private OffsetDateTime issuedAt;
        private String issuedBy;
    }

    public record PoDetail(PoRow po, List<PoLineRow> lines, List<RevRow> revs) {
    }

    /** 신규는 custCd 필수(이후 고정), 행 "한 벌" — 행 id 가 있으면 수정, 없으면 추가, 빠진 기존 행은 삭제(작성 중만) */
    public record PoForm(String custCd, String plantCd, String whCd, String payTermsCd, String remark, Integer rowVer,
            List<PoLineForm> lines) {
    }

    /** 단가를 비우면 발주일 기준 단가표(srm_price) 값 */
    public record PoLineForm(Long poLineId, String itemCd, String qty, String unitPrice, String dueDate, Long prLineId,
            String remark) {
    }

    public record PoValue(String plantCd, Long whId, String payTermsCd, String remark, BigDecimal totalAmt, Integer rowVer) {
    }

    public record PoLineValue(Long poLineId, long itemId, String itemNm, String supplierItemCd, String unitCd, BigDecimal qty,
            BigDecimal unitPrice, BigDecimal amt, LocalDate dueDate, Long prLineId, String remark) {
    }

    /** 결재 상신 — 결재자 로그인 아이디 순서대로(모두 APPROVE 줄) */
    public record SubmitForm(Integer rowVer, List<String> approvers) {
    }

    public record ActionForm(Integer rowVer, String reason) {
    }

    // ------------------------------------------------------------------ 선택지·참조

    @Getter
    @Setter
    public static class SupplierRef {
        private long custId;
        private String custCd;
        private String custNm;
        private String tradeStatus;
        private String currencyCd;
        private String payTermsCd;
    }

    @Getter
    @Setter
    public static class ItemRef {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String useYn;
        private String supplierItemCd;
    }

    @Getter
    @Setter
    public static class PrLineRef {
        private long prLineId;
        private long prId;
        private String prNo;
        private String prStatus;
        private String plantCd;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String qty;
        private String orderedQty;
        private String remainQty;
        private LocalDate needDate;
        private String lineStatus;
        private String prefCustCd;
    }

    @Getter
    @Setter
    public static class WhOption {
        private long nodeId;
        private String nodeCd;
        private String nodeNm;
    }

    /** 목록·grid 공통 조건(검증된 값) — MyBatis OGNL 이 읽게 getter 클래스 */
    @Getter
    @AllArgsConstructor
    public static class PoCrit {
        private LocalDate from;
        private LocalDate to;
        private String status;
        private String keywordLike;
        private String custCd;
    }

    /** grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum PoSort implements SortField {
        PO_NO("poNo"), PO_DATE("poDate"), SUPPLIER("custNm"), TOTAL("totalAmt"), LINE_CNT("lineCnt"), STATUS("status");

        private final String field;

        PoSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** grid 응답 한 쪽 */
    public record PoPage(List<PoRow> list, Long total) {
    }
}
