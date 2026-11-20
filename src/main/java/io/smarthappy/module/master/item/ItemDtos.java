package io.smarthappy.module.master.item;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** 품목관리 DTO (P1 1단계, docs/17 3장) */
public final class ItemDtos {

    private ItemDtos() {
    }

    /** 목록·상세·엑셀 행 */
    @Getter
    @Setter
    public static class ItemRow {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String spec;
        private String itemType;
        private String procType;
        private String unitCd;
        private String lotCtrlYn;
        private BigDecimal netMassKg;
        private String hsCd;
        private String remark;
        private String useYn;
        private BigDecimal stdPrice;
        /** ERP 품번 — 사용 중 ERP 시스템(코드 순 첫째)의 매핑, 없으면 null (docs/17 9장, 저장은 mst_ext_id_map) */
        private String erpItemCd;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    /** 검색 조건 (like 값은 이스케이프된 것) */
    @Getter
    public static class ItemSearch {
        private final String itemCdLike;
        private final String itemNmLike;
        private final String itemType;
        private final String procType;
        private final String useYn;
        private final PageQuery page;

        public ItemSearch(String itemCdLike, String itemNmLike, String itemType, String procType, String useYn,
                PageQuery page) {
            this.itemCdLike = itemCdLike;
            this.itemNmLike = itemNmLike;
            this.itemType = itemType;
            this.procType = procType;
            this.useYn = useYn;
            this.page = page;
        }
    }

    /**
     * 저장 요청. 순질량·표준단가는 글자로 받는다(화면 hb.number 의 10진 문자열 — 부동소수를 거치지 않게).
     * itemCd 는 정책 ITEM_CD_MODE=AUTO 신규면 비운다(서버 채번), 수정에서는 지금 코드(비우면 그대로 — 바꾸면 FIXED).
     * erpItemCd 는 신규 때만 — 품목과 한 트랜잭션으로 ERP 품번 매핑을 붙인다(수정은 외부 품번 API, docs/17 9장)
     */
    public record ItemForm(String itemCd, String itemNm, String spec, String itemType, String procType, String unitCd,
            String lotCtrlYn, String netMassKg, String hsCd, String remark, String useYn, String stdPrice, Integer rowVer,
            String erpItemCd) {

        /** ERP 품번 없이 (기존 호출 — 데모 흐름·엑셀 올리기) */
        public ItemForm(String itemCd, String itemNm, String spec, String itemType, String procType, String unitCd,
                String lotCtrlYn, String netMassKg, String hsCd, String remark, String useYn, String stdPrice, Integer rowVer) {
            this(itemCd, itemNm, spec, itemType, procType, unitCd, lotCtrlYn, netMassKg, hsCd, remark, useYn, stdPrice, rowVer,
                    null);
        }
    }

    /** 검증을 통과한 값 (DB 에 그대로) */
    public record ItemValue(String itemCd, String itemNm, String spec, String itemType, String procType, String unitCd,
            String lotCtrlYn, BigDecimal netMassKg, String hsCd, String remark, String useYn, BigDecimal stdPrice, Integer rowVer) {

        /** AUTO 채번 뒤 코드만 바꾼 값 */
        public ItemValue withItemCd(String cd) {
            return new ItemValue(cd, itemNm, spec, itemType, procType, unitCd, lotCtrlYn, netMassKg, hsCd, remark, useYn, stdPrice,
                    rowVer);
        }
    }

    public record IdVer(Long id, Integer rowVer) {
    }

    public record DeleteRequest(List<IdVer> items) {
    }

    public record ExportRequest(String itemCd, String itemNm, String itemType, String procType, String useYn,
            List<String> sort, List<String> columns) {
    }

    /** 정렬 화이트리스트 — 매퍼 <choose> 가 enum 이름으로 열을 고른다 */
    public enum ItemSort implements SortField {
        ITEM_CD("itemCd"), ITEM_NM("itemNm"), ITEM_TYPE("itemType"), PROC_TYPE("procType"), USE_YN("useYn"),
        UPDATED_AT("updatedAt");

        private final String field;

        ItemSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }
}
