package io.smarthappy.module.production.erp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** ERP 연계·내보내기 (P2 7c, docs/25 4.11) */
public final class ErpDtos {

    private ErpDtos() {
    }

    /** ERP 로 가는 한 줄 — 지시 실적(RESULT) 또는 입고·정정(RECEIPT). 엑셀 셀은 숫자(qty), 화면은 글자(qtyText) */
    @Getter
    @Setter
    public static class ErpRow {
        private String bizKey;
        private LocalDate day;
        private OffsetDateTime occurredAt;
        private String woNo;
        private String itemCd;
        private String itemNm;
        private String erpItemCd;
        private BigDecimal qty;
        private BigDecimal scrapQty;
        private String unitCd;
        private String whCd;
        private String lotNo;
        private String entryMethod;

        public String getQtyText() {
            return plain(qty);
        }

        public String getScrapQtyText() {
            return plain(scrapQty);
        }

        static String plain(BigDecimal v) {
            return v == null ? null : v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
        }
    }

    @Getter
    @Setter
    public static class ExportLog {
        private long exportId;
        private String kind;
        private LocalDate fromDay;
        private LocalDate toDay;
        private int rowCnt;
        private OffsetDateTime exportedAt;
        private String exportedBy;
    }

    /** 미리보기: 행(상한까지) + 전체 건수 + 겹치는 기간의 이전 내보내기(D20) + 매핑 없는 행 수 + 현재 정책 */
    public record Preview(List<ErpRow> rows, long total, boolean truncated, long unmapped, List<ExportLog> exports,
            String erpSystem) {
    }

    public record ExportForm(String kind, LocalDate from, LocalDate to) {
    }
}
