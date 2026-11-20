package io.smarthappy.module.master.bom;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** BOM 관리 DTO (P1 3a, docs/18) */
public final class BomDtos {

    private BomDtos() {
    }

    /** 머리 (목록·상세·엑셀) */
    @Getter
    @Setter
    public static class BomRow {
        private long bomId;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String itemUnit;
        private int altNo;
        private String rev;
        private String status;
        private LocalDate validFrom;
        private LocalDate validTo;
        private BigDecimal baseQty;
        private String changeNo;
        private String changeReason;
        private String remark;
        private OffsetDateTime releasedAt;
        private String releasedBy;
        private int lineCnt;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    /** 행 (자품목 정보 포함) */
    @Getter
    @Setter
    public static class LineRow {
        private long lineId;
        private long bomId;
        private int lineNo;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String itemUnit;
        private String itemUseYn;
        private String lotCtrlYn;
        private String lineType;
        private BigDecimal qty;
        private String unitCd;
        private BigDecimal scrapPct;
        private String fixedYn;
        private String issueMethod;
        private Integer opSeq;
        private String altGrp;
        private Integer altPriority;
        private BigDecimal altPct;
        private String position;
        private String remark;
        private int rowVer;
    }

    /** 품목 찾기 (BOM 화면 권한으로 — 품목관리 권한이 없어도 BOM 은 만든다) */
    @Getter
    @Setter
    public static class ItemLookup {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String spec;
        private String itemType;
        private String unitCd;
        private String lotCtrlYn;
        private String useYn;
    }

    /** 품목별 단위 환산 */
    @Getter
    @Setter
    public static class ConvRow {
        private long convId;
        private long itemId;
        private String fromUnit;
        private String toUnit;
        private BigDecimal factor;
        private int rowVer;
    }

    @Getter
    public static class BomSearch {
        private final String itemCdLike;
        private final String itemNmLike;
        private final String status;
        private final boolean withObsolete;
        private final PageQuery page;

        public BomSearch(String itemCdLike, String itemNmLike, String status, boolean withObsolete, PageQuery page) {
            this.itemCdLike = itemCdLike;
            this.itemNmLike = itemNmLike;
            this.status = status;
            this.withObsolete = withObsolete;
            this.page = page;
        }
    }

    /** 머리 저장 — 수치·날짜는 글자로 받아 서버가 해석(반올림 없음) */
    public record HeaderForm(String itemCd, Integer altNo, String rev, String baseQty, String validFrom, String changeNo,
            String changeReason, String remark, Integer rowVer) {
    }

    /** 검증된 머리 값 */
    public record HeaderValue(long itemId, int altNo, String rev, BigDecimal baseQty, LocalDate validFrom,
            String changeNo, String changeReason, String remark, Integer rowVer) {
    }

    public record LineItem(String rowKey, Long lineId, Integer rowVer, Integer lineNo, String itemCd, String lineType,
            String qty, String unitCd, String scrapPct, String fixedYn, String issueMethod, Integer opSeq,
            String altGrp, Integer altPriority, String altPct, String position, String remark) {
    }

    /** 검증된 행 값 */
    public record LineValue(String rowKey, Long lineId, Integer rowVer, int lineNo, long itemId, String lineType,
            BigDecimal qty, String unitCd, BigDecimal scrapPct, String fixedYn, String issueMethod, Integer opSeq,
            String altGrp, Integer altPriority, BigDecimal altPct, String position, String remark) {
    }

    public record LineBatch(List<LineItem> created, List<LineItem> updated, List<LineItem> deleted) {
    }

    public record CreatedKey(String rowKey, long id) {
    }

    public record BatchResult(List<CreatedKey> created, int updated, int deleted) {
    }

    public record BomDetail(BomRow bom, List<LineRow> lines) {
    }

    /** 상태 동작(확정·폐기·새 리비전): 머리 rowVer + (새 리비전) 시작일·리비전(비우면 자동) */
    public record ActionRequest(Integer rowVer, String validFrom, String rev) {
    }

    /** 일괄 확정 한 건 — 화면이 본 머리 버전(rowVer)과 함께 (G5, docs/60 R1) */
    public record ReleaseItem(Long bomId, Integer rowVer) {
    }

    public record ReleaseBatch(List<ReleaseItem> items) {
    }

    /** 일괄 확정 결과 한 건: ok 또는 실패 코드·문구(그 건만 되돌림) */
    public record ReleaseOutcome(long bomId, String itemCd, String rev, boolean ok, String code, String message) {
    }

    public record ConvItem(String rowKey, Long convId, Integer rowVer, String fromUnit, String factor) {
    }

    public record ConvBatch(List<ConvItem> created, List<ConvItem> updated, List<ConvItem> deleted) {
    }

    public record ExportRequest(String itemCd, String itemNm, String status, Boolean withObsolete, List<String> sort,
            List<String> columns) {
    }

    public enum BomSort implements SortField {
        ITEM_CD("itemCd"), REV("rev"), STATUS("status"), VALID_FROM("validFrom"), UPDATED_AT("updatedAt");

        private final String field;

        BomSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }
}
