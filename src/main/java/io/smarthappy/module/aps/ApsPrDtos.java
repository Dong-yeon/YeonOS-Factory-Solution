package io.smarthappy.module.aps;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** 부족 → 구매 요청 초안 (docs/38 17.4) 요청·응답 */
public final class ApsPrDtos {

    private ApsPrDtos() {
    }

    /** 고른 자재 줄(aps_plan_mat id) 1~200 */
    public record PrPickForm(List<Long> matIds) {
    }

    /**
     * 요청 줄 하나(품목마다) — 수량은 글자. needDate = 가장 이른 필요 시각의 공장 날짜, orderBy = needDate − 리드타임(표시만),
     * late = 발주 필요일이 공장 오늘보다 앞, noSource = 주 공급원 없음
     */
    public record PrLine(long itemId, String itemCd, String itemNm, String unitCd, String shortQty, String moq, String orderMult,
            String reqQty, LocalDate needDate, int leadDays, LocalDate orderBy, boolean late, String supplierCd, String supplierNm,
            boolean noSource, List<Long> matIds, List<String> woNos) {
    }

    /** 미리보기 — errors 는 줄마다(rowKey = 자재 줄 id, code) — 만들기는 errors 가 있으면 400 */
    public record PrPreview(long planId, String planNo, String plantCd, LocalDate needDate, List<PrLine> lines, List<PickError> errors) {
    }

    public record PickError(long matId, String code) {
    }

    public record PrCreated(long prId, String prNo, int lineCnt) {
    }

    // ---------- 내부 ----------

    @Getter
    @Setter
    public static class PlanHead {
        private long planId;
        private String planNo;
        private String plantCd;
        private String status;
        private String timeZone;
        private boolean purged;
    }

    @Getter
    @Setter
    public static class MatSrc {
        private long planMatId;
        private long woId;
        private String woNo;
        private int opSeq;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String procType;
        private String itemUseYn;
        private String unitCd;
        private BigDecimal shortQty;
        private OffsetDateTime needAt;
        private boolean linked;
    }

    /** 주 공급원(srm_source — 읽기만) */
    @Getter
    @Setter
    public static class SourceSrc {
        private long itemId;
        private long custId;
        private String custCd;
        private String custNm;
        private int leadDays;
        private BigDecimal moq;
        private BigDecimal orderMult;
    }

    @Getter
    @Setter
    public static class PrLineRef {
        private long prLineId;
        private long itemId;
        private int lineNo;
    }

    public record LinkInsert(long planMatId, long woId, int opSeq, long itemId, BigDecimal shortQty, long prLineId) {
    }
}
