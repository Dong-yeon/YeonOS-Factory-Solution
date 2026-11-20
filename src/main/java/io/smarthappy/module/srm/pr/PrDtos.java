package io.smarthappy.module.srm.pr;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.SortField;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/** 구매요청 DTO (SRM S2, docs/31 4장). 수량은 응답에서 글자(trim_scale) */
public final class PrDtos {

    private PrDtos() {
    }

    @Getter
    @Setter
    public static class PrRow {
        private long prId;
        private String prNo;
        private LocalDate prDate;
        private String plantCd;
        private String plantNm;
        private long reqUserId;
        private String reqLoginId;
        private String reqUserNm;
        private String deptNm;
        private LocalDate needDate;
        private String reason;
        private String status;
        private Long decidedBy;
        private String decidedByNm;
        private OffsetDateTime decidedAt;
        private String rejectReason;
        private String cancelReason;
        private int lineCnt;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    @Getter
    @Setter
    public static class PrLineRow {
        private long prLineId;
        private long prId;
        private int lineNo;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String itemUseYn;
        private String qty;
        private String unitCd;
        private LocalDate needDate;
        private Long prefCustId;
        private String prefCustCd;
        private String prefCustNm;
        private String remark;
        private String lineStatus;
    }

    public record PrDetail(PrRow pr, List<PrLineRow> lines) {
    }

    /** 머리 + 행 "한 벌" — 행 id 가 있으면 수정, 없으면 추가, 빠진 기존 행은 삭제(작성 중만) */
    public record PrForm(String plantCd, String deptNm, String needDate, String reason, Integer rowVer,
            List<PrLineForm> lines) {
    }

    public record PrLineForm(Long prLineId, String itemCd, String qty, String needDate, String prefCustCd, String remark) {
    }

    public record PrValue(String plantCd, String deptNm, LocalDate needDate, String reason, Integer rowVer) {
    }

    public record PrLineValue(Long prLineId, long itemId, BigDecimal qty, String unitCd, LocalDate needDate, Long prefCustId,
            String remark) {
    }

    /** 상태 전이(요청·회수·승인·반려·취소·마감·다시 작성) */
    public record ActionForm(Integer rowVer, String reason) {
    }

    // ------------------------------------------------------------------ 선택지

    @Getter
    @Setter
    public static class ItemRef {
        private long itemId;
        private String itemCd;
        private String unitCd;
        private String useYn;
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
        private String useYn;
    }

    /** 목록·grid 공통 조건(검증된 값) — MyBatis OGNL 이 읽게 getter 클래스 */
    @Getter
    @AllArgsConstructor
    public static class PrCrit {
        private LocalDate from;
        private LocalDate to;
        private String status;
        private String keywordLike;
        private String plantCd;
    }

    /** grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum PrSort implements SortField {
        PR_NO("prNo"), PR_DATE("prDate"), PLANT("plantNm"), REQUESTER("reqUserNm"), NEED_DATE("needDate"), LINE_CNT("lineCnt"),
        STATUS("status");

        private final String field;

        PrSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** grid 응답 한 쪽 */
    public record PrPage(List<PrRow> list, Long total) {
    }
}
