package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** COPQ(품질 비용) DTO — docs/29_P3 29장. 금액은 회사 기준 통화, 관리용 추정(회계 원장 아님) */
public final class CopqDtos {

    private CopqDtos() {
    }

    /**
     * 원장에서 뽑은 비용 줄 — 월·구분·출처·품목·적용 단가별 수량 합. 단가가 없으면(NO_PRICE), 생산 줄의 단위가 품목 기본 단위와 다르면(UNIT)
     * 금액을 비우고 reason 을 단다(0원으로 세지 않는다 — CQ-4). 생산 줄은 정정한 달에 음수일 수 있다(29.4)
     */
    @Getter
    @Setter
    public static class LineRow {
        private String month;
        private String category;
        private String source;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private BigDecimal stdPrice;
        private BigDecimal qty;
        private int lineCnt;
        private BigDecimal amount;
        private String reason;
        /** NCR 줄에 묶인 NCR 번호(쉼표, 번호 순 — 36장). 생산 줄은 null */
        private String ncrNos;
    }

    /** 품목의 그 달 말 표준단가(29.4) — month = YYYY-MM, stdPrice null = 그때 단가 없음 */
    @Getter
    @Setter
    public static class ItemPrice {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String month;
        private BigDecimal stdPrice;
    }

    /** 수동 비용 행 */
    @Getter
    @Setter
    public static class CostRow {
        private long costId;
        private String costMonth;
        private String category;
        private String costItem;
        private BigDecimal amount;
        private String note;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    /** 수동 비용 저장 — 금액은 글자(10진 문자열)로 받는다 */
    public record CostForm(String costMonth, String category, String costItem, String amount, String note, Integer rowVer) {
    }

    /** 검증을 통과한 값 — costMonth 는 그 달 1일 */
    public record CostValue(java.time.LocalDate costMonth, String category, String costItem, BigDecimal amount, String note, Integer rowVer) {
    }

    public record RowVer(Integer rowVer) {
    }

    /**
     * 월별 합계 — PAF 4구분. 구분 칸이 null = 그 달 그 구분에 줄은 있는데 단가 있는 금액이 하나도 없음(0원과 다름). total 도 같은 규칙.
     * unpricedCnt = 단가가 없어 금액을 못 낸 원장 처리 행 수
     */
    public record MonthRow(String month, BigDecimal prevention, BigDecimal appraisal, BigDecimal internal, BigDecimal external,
            BigDecimal total, int unpricedCnt) {
    }

    public record CopqView(String fromMonth, String toMonth, List<MonthRow> months, MonthRow sum, List<LineRow> lines, List<CostRow> costs) {
    }

    /** 엑셀 요청 — 조회와 같은 달 범위 */
    public record ExportForm(String fromMonth, String toMonth) {
    }

    /**
     * 엑셀 한 줄(34장) — 원장 줄(kind LEDGER)과 수동 행(MANUAL)을 한 시트에. 원장 줄은 품목·수량·단가·건수·reason, 수동 행은 costItem·note 를 채운다.
     * amount null = 단가 없음·단위 다름(0원과 다름 — 화면과 같은 규칙)
     */
    @Getter
    @Setter
    public static class ExportRow {
        private String kind;
        private String month;
        private String category;
        private String source;
        private String itemCd;
        private String itemNm;
        private BigDecimal qty;
        private String unitCd;
        private BigDecimal stdPrice;
        private Integer lineCnt;
        private BigDecimal amount;
        private String reason;
        private String ncrNos;
        private String costItem;
        private String note;
    }
}
