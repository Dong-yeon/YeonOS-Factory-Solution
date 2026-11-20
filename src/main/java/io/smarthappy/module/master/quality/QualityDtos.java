package io.smarthappy.module.master.quality;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** 특성 마스터·불량 유형·용어 사전 DTO (P1 6단계, docs/21) */
public final class QualityDtos {

    private QualityDtos() {
    }

    // ------------------------------------------------------------------ 특성

    @Getter
    @Setter
    public static class CharRow {
        private long charId;
        private String charType;
        private Long itemId;
        private String itemCd;
        private String itemNm;
        private Long procId;
        private String procCd;
        private String procNm;
        private String charNo;
        private String charNm;
        private String dataType;
        /** 측정 단위 코드(MEAS_UNIT, docs/68 Q2) — 비면 unitNm 글자만(옛 행) */
        private String unitCd;
        private String unitNm;
        private int scale;
        private BigDecimal nominal;
        private BigDecimal lsl;
        private BigDecimal usl;
        /** 목록용 규격 글자 (예: "9.95 ~ 10.05 mm", "≥ 440 MPa") — 서비스가 만든다 */
        private String specText;
        private String specialCls;
        private String source;
        private String drawingNo;
        private String drawingRev;
        private int sortNo;
        private String useYn;
        private String remark;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    public record CharForm(String charType, String itemCd, String procCd, String charNo, String charNm, String dataType,
            String unitCd, String unitNm, Integer scale, String nominal, String lsl, String usl, String specialCls, String source,
            String drawingNo, String drawingRev, Integer sortNo, String useYn, String remark, Integer rowVer) {
    }

    public record CharValue(String charType, Long itemId, Long procId, String charNo, String charNm, String dataType,
            String unitCd, String unitNm, int scale, BigDecimal nominal, BigDecimal lsl, BigDecimal usl, String specialCls, String source,
            String drawingNo, String drawingRev, int sortNo, String useYn, String remark, Integer rowVer) {
    }

    @Getter
    public static class CharSearch {
        private final String itemCdLike;
        private final String keywordLike;
        private final Long procId;
        private final String charType;
        private final String specialCls;
        private final boolean withUnused;
        private final PageQuery page;

        public CharSearch(String itemCdLike, String keywordLike, Long procId, String charType, String specialCls,
                boolean withUnused, PageQuery page) {
            this.itemCdLike = itemCdLike;
            this.keywordLike = keywordLike;
            this.procId = procId;
            this.charType = charType;
            this.specialCls = specialCls;
            this.withUnused = withUnused;
            this.page = page;
        }
    }

    public record CharExportRequest(String itemCd, String keyword, String procCd, String charType, String specialCls,
            Boolean withUnused, List<String> sort, List<String> columns) {
    }

    /** 불량 유형 그리드 정렬 허용 열(UI 단계 3 ①) — CAT 은 표준 상위 순서(DEFECT_CAT sort_no) */
    public enum DefectSort implements SortField {
        CAT("catCd"), DEFECT_CD("defectCd"), DEFECT_NM("defectNm"), SORT_NO("sortNo"), UPDATED_AT("updatedAt");

        private final String field;

        DefectSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 용어 그리드 정렬 허용 열 — SORT_NO 는 표준 순서(sys_term.sort_no) */
    public enum TermSort implements SortField {
        SORT_NO("sortNo"), CATEGORY("category"), TERM_CD("termCd"), TERM_KO("termKo"), TERM_EN("termEn"), ALIAS_CNT("aliasCnt");

        private final String field;

        TermSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public enum CharSort implements SortField {
        ITEM_CD("itemCd"), CHAR_NO("charNo"), CHAR_NM("charNm"), SPECIAL_CLS("specialCls"), UPDATED_AT("updatedAt");

        private final String field;

        CharSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    @Getter
    @Setter
    public static class ItemLookup {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String spec;
        private String useYn;
    }

    @Getter
    @Setter
    public static class ProcLookup {
        private long procId;
        private String procCd;
        private String procNm;
        private String useYn;
    }

    // ------------------------------------------------------------------ 불량 유형

    @Getter
    @Setter
    public static class DefectRow {
        private long defectId;
        private String defectCd;
        private String defectNm;
        private String catCd;
        private int sortNo;
        private String useYn;
        private String remark;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    public record DefectForm(String defectCd, String defectNm, String catCd, Integer sortNo, String useYn, String remark,
            Integer rowVer) {
    }

    // ------------------------------------------------------------------ 용어 사전

    @Getter
    @Setter
    public static class TermRow {
        private String termCd;
        private String category;
        private String termKo;
        private String termEn;
        private String defKo;
        private String defEn;
        private int aliasCnt;
    }

    @Getter
    @Setter
    public static class AliasRow {
        private long aliasId;
        private String termCd;
        private String alias;
        private String lang;
        private String useYn;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    public record AliasForm(String termCd, String alias, String lang, String useYn, Integer rowVer) {
    }
}
