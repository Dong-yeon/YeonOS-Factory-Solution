package io.smarthappy.module.aps;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** 계획 기준정보 (aps-item-param, docs/38 17장) 요청·응답 */
public final class ItemParamDtos {

    private ItemParamDtos() {
    }

    /**
     * 등록·수정 — 수정 때 공장·품목은 바꿀 수 없다(무시). 수량은 글자(서버 BigDecimal), 일수는 정수. lotRule = EX·FX·MIN(기본 EX), useYn 기본 Y
     */
    public record ParamForm(String plantCd, String itemCd, String safetyQty, String lotRule, String lotQty, String lotMult,
            Integer makeLeadDays, Integer fenceDays, String remark, String useYn, Integer rowVer) {
    }

    @Getter
    @Setter
    public static class ParamRow {
        private long paramId;
        private String plantCd;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String procType;
        private String unitCd;
        private String safetyQty;
        private String lotRule;
        private String lotQty;
        private String lotMult;
        private Integer makeLeadDays;
        private Integer fenceDays;
        private String remark;
        private String useYn;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    /** 조회 조건(검증 뒤) */
    public record ParamCrit(String plantCd, String keyword, String useYn) {
    }

    public record ParamPage(List<ParamRow> list, Long total) {
    }

    public enum ParamSort implements SortField {
        PLANT_CD("plantCd"), ITEM_CD("itemCd"), ITEM_NM("itemNm"), PROC_TYPE("procType"), UPDATED_AT("updatedAt");

        private final String field;

        ParamSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 저장 값(검증 뒤) */
    public record ParamValue(String plantCd, Long itemId, BigDecimal safetyQty, String lotRule, BigDecimal lotQty, BigDecimal lotMult,
            Integer makeLeadDays, Integer fenceDays, String remark, String useYn) {
    }
}
