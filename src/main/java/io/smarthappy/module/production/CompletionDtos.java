package io.smarthappy.module.production;

import java.math.BigDecimal;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** 공정 완료판정·재작업/수리 처리·지시 완료·마감 DTO (P2 7a, docs/25 4.9) */
public final class CompletionDtos {

    private CompletionDtos() {
    }

    /** 공정 하나의 상태 + 수량(hb_proc_qty) */
    @Getter
    @Setter
    public static class ProcQty {
        private long woProcId;
        private long woId;
        private int opSeq;
        private String procCd;
        private String procNm;
        private String status;
        private int rowVer;
        private String woStatus;
        private String plantCd;
        private String unitCd;
        private BigDecimal good;
        private BigDecimal scrap;
        private BigDecimal rework;
        private BigDecimal repair;
        private BigDecimal reworkDone;
        private BigDecimal repairDone;
        private BigDecimal dispGood;
        private BigDecimal dispScrap;
        /** 지시 공정 배정 설비(없으면 null) — 처리 LOT 의 설계 변경 표시(3c) */
        private Long equipId;
        /** 공정 기준정보 id·지시 품목 — 완료판정 경고 빈(CloseNotice) 문맥 */
        private long procId;
        private long itemId;
    }

    /**
     * 완료판정 미리보기 = 판정과 같은 계산. 수량은 글자(BigDecimal plain). problems = 막는 이유 코드
     * (STATE·PREV_OPEN·PENDING·NO_RESULT·BALANCE) — 비면 완료 가능. notices = 다른 모듈 경고(CloseNotice, 막지 않음 — 4.46)
     */
    public record CloseCheck(long woProcId, int opSeq, String procCd, String procNm, String status, int rowVer, boolean firstOp,
            Integer prevOpSeq, String prevStatus, String inputQty, String goodQty, String scrapQty, String reworkQty, String repairQty,
            String reworkWait, String repairWait, String dispGoodQty, String effGoodQty, String outputQty, String diffQty, int tolPct,
            String tolQty, String unitCd, List<String> problems, List<NoticeLine> notices) {
    }

    /** 완료판정 경고 한 줄 — message = 요청 로캘로 푼 문구, refType·refId = 참조(없으면 null) */
    public record NoticeLine(String code, String message, String refType, Long refId) {
    }

    /** 지시 하나의 공정별 판정 + 마지막 공정 양품·입고 */
    public record Completion(long woId, String woNo, String status, int rowVer, String unitCd, List<CloseCheck> procs,
            String lastGoodQty, String receivedQty, String unreceivedQty) {
    }

    public record CloseForm(String remark, Integer rowVer) {
    }

    public record ReworkForm(String clientReqId, String kind, String goodQty, String scrapQty, String reason) {
    }

    public record ReworkReply(long reworkId, String lotNo, boolean replayed) {
    }

    @Getter
    @Setter
    public static class ReworkRow {
        private long reworkId;
        private long woProcId;
        private String lotNo;
    }

    public record NewClose(long woId, long woProcId, BigDecimal inputQty, BigDecimal goodQty, BigDecimal scrapQty, BigDecimal reworkQty,
            BigDecimal repairQty, BigDecimal effGoodQty, BigDecimal diffQty, int tolPct, String firstOpYn, String remark) {
    }

    public record NewRework(long woId, long woProcId, String kind, BigDecimal qty, BigDecimal goodQty, BigDecimal scrapQty,
            String unitCd, Long lotId, String reason, String clientReqId) {
    }
}
