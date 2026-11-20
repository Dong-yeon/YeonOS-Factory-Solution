package io.smarthappy.module.srm.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.SortField;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/** 협력사 클레임 DTO (SRM 2부 R4d, docs/31 4.12.4). 금액·수량은 응답에서 글자(trim_scale) */
public final class ClaimDtos {

    private ClaimDtos() {
    }

    @Getter
    @Setter
    public static class ClaimRow {
        private long claimId;
        private String claimNo;
        private long custId;
        private String custCd;
        private String custNm;
        private String actionCd;
        private String title;
        private String requestText;
        private LocalDate replyDue;
        private String chargeAmt;
        private String currencyCd;
        private String status;
        private OffsetDateTime sentAt;
        private String sentBy;
        private String answerCause;
        private String answerAction;
        private LocalDate answeredOn;
        /** 회신 경로 MANUAL(대신 입력)·PORTAL(협력사), null = 칸이 생기기 전 회신 */
        private String answerSource;
        private String answeredBy;
        private OffsetDateTime closedAt;
        private String closedBy;
        private String cancelReason;
        private int ncrCnt;
        private int rowVer;
        private OffsetDateTime createdAt;
        private String createdBy;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    @Getter
    @Setter
    public static class ClaimNcrRow {
        private long ncrId;
        private String ncrNo;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String ncQty;
        private String unitCd;
    }

    /** returns = 근거 NCR 이 같은 살아 있는(취소 아님) 반품 */
    public record ClaimDetail(ClaimRow claim, List<ClaimNcrRow> ncrs, List<ReturnDtos.LinkRow> returns) {
    }

    /** 근거 NCR 후보 — QMS 계약 ncrsOfSupplier 한 줄(취소 제외, 발견 시각 기준) + 이 NCR 의 살아 있는 클레임 번호 */
    public record NcrCandidate(long ncrId, String ncrNo, String status, String lotNo, String itemCd, String ncQty, String unitCd, String defectNm,
            OffsetDateTime occurredAt, String claimNo) {
    }

    public record NcrCandidatePage(List<NcrCandidate> rows, boolean truncated) {
    }

    @Getter
    @Setter
    public static class SupplierRef {
        private long custId;
        private String custCd;
        private String custNm;
    }

    public record ClaimForm(String custCd, String actionCd, String title, String requestText, String replyDue, String chargeAmt,
            String currencyCd, List<Long> ncrIds, Integer rowVer) {
    }

    public record AnswerForm(Integer rowVer, String answerCause, String answerAction, String answeredOn) {
    }

    public record ActionForm(Integer rowVer, String reason) {
    }

    record ClaimValue(long custId, String actionCd, String title, String requestText, LocalDate replyDue, BigDecimal chargeAmt,
            String currencyCd) {
    }

    /** 근거 NCR 스냅샷(QMS 계약 값) */
    record NcrValue(long ncrId, String ncrNo, long itemId, BigDecimal ncQty, String unitCd) {
    }

    /** 목록·grid 공통 조건 — 기간 = 만든 날(회사 날짜) */
    @Getter
    @AllArgsConstructor
    public static class ClaimCrit {
        private LocalDate from;
        private LocalDate to;
        private String zone;
        private String status;
        private String keywordLike;
    }

    public enum ClaimSort implements SortField {
        CLAIM_NO("claimNo"), SUPPLIER("custNm"), TITLE("title"), REPLY_DUE("replyDue"), CREATED_AT("createdAt"), STATUS("status");

        private final String field;

        ClaimSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record ClaimPage(List<ClaimRow> list, Long total) {
    }
}
