package io.smarthappy.module.production;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** 사무 실적 조회 (prd-result, docs/25 4.16) DTO — 수량은 BigDecimal(엑셀 숫자 칸), 화면은 같은 값을 글자로 받는다 */
public final class ResultViewDtos {

    private ResultViewDtos() {
    }

    /** 조회 조건(검증 뒤) — view 는 DETAIL·WO·PROC·WORKER·SHIFT */
    public record ResultQuery(String view, LocalDate from, LocalDate to, String plantCd, String shiftCd, String keyword, Long itemId,
            String procCd, Long workerId) {
    }

    /**
     * 한 줄 — 보기마다 쓰는 칸만 찬다. 상세 = 원래 실적 한 건(정정되면 corrected·정정 시각·사유),
     * 묶음 = 정정 안 된 실적의 합(cnt 건)
     */
    @Getter
    @Setter
    public static class ResultLine {
        private Long resultId;
        private LocalDate workDay;
        private String shiftCd;
        private String woNo;
        private Integer opSeq;
        private String procCd;
        private String procNm;
        private String itemCd;
        private String itemNm;
        private String equipCd;
        private String workerCd;
        private String workerNm;
        private String lotNo;
        private Long cnt;
        private BigDecimal goodQty;
        private BigDecimal scrapQty;
        private BigDecimal reworkQty;
        private BigDecimal repairQty;
        private String unitCd;
        private BigDecimal defectRate;
        private OffsetDateTime occurredAt;
        private String recordedBy;
        private boolean corrected;
        private OffsetDateTime correctedAt;
        private String correctReason;
        /** 정정을 승인한 감독자(POP 감독자 승인, docs/25 4.39) — 승인 없이 한 정정이면 null */
        private String approverCd;
        private String approverNm;

        // 화면은 글자(엑셀 셀은 위 숫자) — 응답 수량은 글자 규칙(7c ErpRow 와 같이)
        public String getGoodQtyText() {
            return plain(goodQty);
        }

        public String getScrapQtyText() {
            return plain(scrapQty);
        }

        public String getReworkQtyText() {
            return plain(reworkQty);
        }

        public String getRepairQtyText() {
            return plain(repairQty);
        }

        public String getDefectRateText() {
            return defectRate == null ? null : defectRate.toPlainString();
        }
    }

    static String plain(BigDecimal v) {
        return v == null ? null : v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
    }

    /** 불량 유형 합계 — 종류(SCRAP·REWORK·REPAIR) × 유형 × 단위 */
    @Getter
    @Setter
    public static class DefectSum {
        private String kind;
        private String defectCd;
        private String defectNm;
        private BigDecimal qty;
        private String unitCd;

        public String getQtyText() {
            return plain(qty);
        }
    }

    public record ResultView(String view, List<ResultLine> rows, long total, boolean truncated, List<DefectSum> defects) {
    }

    /**
     * 그리드 정렬 화이트리스트 (docs/40 §10.2 D1) — 보기마다 쓸 수 있는 값은 서비스 SORTS 가 고른다(그 보기에 없는 칸은 400).
     * enum 이름 = 매퍼 choose 키(문자열 치환 없음)
     */
    public enum ResultSort implements SortField {
        WORK_DAY("workDay"), SHIFT_CD("shiftCd"), WO_NO("woNo"), OP_SEQ("opSeq"), PROC_CD("procCd"), ITEM_CD("itemCd"),
        EQUIP_CD("equipCd"), WORKER_CD("workerCd"), LOT_NO("lotNo"), OCCURRED_AT("occurredAt"), CNT("cnt"), GOOD_QTY("goodQty"),
        SCRAP_QTY("scrapQty"), REWORK_QTY("reworkQty"), REPAIR_QTY("repairQty"), UNIT_CD("unitCd"), DEFECT_RATE("defectRate");

        private final String field;

        ResultSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 엑셀 — 마지막으로 조회한 조건 */
    public record ResultExportForm(String view, LocalDate from, LocalDate to, String plantCd, String shiftCd, String keyword, String itemCd,
            String procCd, String workerCd) {
    }
}
