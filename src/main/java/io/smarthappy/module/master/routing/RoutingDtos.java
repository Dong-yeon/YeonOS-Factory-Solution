package io.smarthappy.module.master.routing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** 공정·라우팅 DTO (P1 5단계, docs/20) */
public final class RoutingDtos {

    private RoutingDtos() {
    }

    // ------------------------------------------------------------------ 공정

    @Getter
    @Setter
    public static class ProcRow {
        private long procId;
        private String procCd;
        private String procNm;
        private String procCat;
        private String outsrcYn;
        private int sortNo;
        private String useYn;
        private String remark;
        /** 이 공정을 쓰는 확정 라우팅 수 */
        private int routingCnt;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    public record ProcForm(String procCd, String procNm, String procCat, String outsrcYn, Integer sortNo, String useYn,
            String remark, Integer rowVer) {
    }

    // ------------------------------------------------------------------ 라우팅 머리

    @Getter
    @Setter
    public static class RoutingRow {
        private long routingId;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String itemUseYn;
        private String plantCd;
        private String plantNm;
        private int altNo;
        private String rev;
        private String status;
        private LocalDate validFrom;
        private LocalDate validTo;
        private String changeNo;
        private String changeReason;
        private String remark;
        private OffsetDateTime releasedAt;
        private String releasedBy;
        private int stepCnt;
        /** 단계 표준 사이클타임 합(초/사이클) — 목록 참고용 */
        private BigDecimal totalCtSec;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    @Getter
    @Setter
    public static class StepRow {
        private long stepId;
        private long routingId;
        private int opSeq;
        private long procId;
        private String procCd;
        private String procNm;
        private String procCat;
        private String outsrcYn;
        private String procUseYn;
        private Long nodeId;
        private String nodeCd;
        private String nodeNm;
        private String nodeType;
        private String nodeUseYn;
        private String nodePath;
        private BigDecimal stdCtSec;
        private int unitsPerCycle;
        private BigDecimal setupMin;
        private String remark;
        private int rowVer;
        private List<ParamRow> params;
    }

    @Getter
    @Setter
    public static class ParamRow {
        private long paramId;
        private long stepId;
        private String paramCd;
        private String paramNm;
        /** 공학 단위 코드(MEAS_UNIT, docs/68 Q2) — 비면 unitNm 글자만(옛 행) */
        private String unitCd;
        private String unitNm;
        private BigDecimal targetVal;
        private BigDecimal lsl;
        private BigDecimal usl;
        private int sortNo;
        private int rowVer;
    }

    /** BOM 연결 점검 — 지금 유효한 확정 BOM(대체 1) 행 중 공정 순번이 이 라우팅에 없는 행 */
    @Getter
    @Setter
    public static class BomOpRow {
        private String bomRev;
        private int lineNo;
        private String itemCd;
        private String itemNm;
        private int opSeq;
    }

    public record RoutingDetail(RoutingRow routing, List<StepRow> steps, List<BomOpRow> unmatchedBomOps) {
    }

    @Getter
    public static class RoutingSearch {
        private final String itemCdLike;
        private final String itemNmLike;
        private final String plantCd;
        private final String status;
        private final boolean withObsolete;
        private final PageQuery page;

        public RoutingSearch(String itemCdLike, String itemNmLike, String plantCd, String status, boolean withObsolete,
                PageQuery page) {
            this.itemCdLike = itemCdLike;
            this.itemNmLike = itemNmLike;
            this.plantCd = plantCd;
            this.status = status;
            this.withObsolete = withObsolete;
            this.page = page;
        }
    }

    public record HeaderForm(String itemCd, String plantCd, Integer altNo, String rev, String validFrom, String changeNo,
            String changeReason, String remark, Integer rowVer) {
    }

    public record HeaderValue(long itemId, String plantCd, int altNo, String rev, LocalDate validFrom, String changeNo,
            String changeReason, String remark, Integer rowVer) {
    }

    // ------------------------------------------------------------------ 구조 저장 (단계 + 파라미터 한 벌)

    /** 단계: stepId 가 있으면 기존 단계 수정, 없으면 추가. 목록에 없는 기존 단계는 삭제 */
    public record StepItem(String rowKey, Long stepId, Integer opSeq, String procCd, Long nodeId, String stdCtSec,
            Integer unitsPerCycle, String setupMin, String remark, List<ParamItem> params) {
    }

    public record ParamItem(String rowKey, Long paramId, String paramCd, String paramNm, String unitCd, String unitNm, String targetVal,
            String lsl, String usl, Integer sortNo) {
    }

    public record StructureForm(List<StepItem> steps) {
    }

    public record StepValue(String rowKey, Long stepId, int opSeq, long procId, Long nodeId, BigDecimal stdCtSec,
            int unitsPerCycle, BigDecimal setupMin, String remark, List<ParamValue> params) {
    }

    public record ParamValue(String rowKey, Long paramId, String paramCd, String paramNm, String unitCd, String unitNm,
            BigDecimal targetVal, BigDecimal lsl, BigDecimal usl, int sortNo) {
    }

    public record SaveResult(int steps, int params) {
    }

    /** 상태 동작(확정·폐기·새 리비전) */
    public record ActionRequest(Integer rowVer, String validFrom, String rev) {
    }

    // ------------------------------------------------------------------ 찾기

    @Getter
    @Setter
    public static class ItemLookup {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String spec;
        private String unitCd;
        private String useYn;
    }

    /** 작업장 선택지 — 공장의 사용 중 LINE·CELL */
    @Getter
    @Setter
    public static class PlaceRow {
        private long nodeId;
        private String plantCd;
        private String nodeCd;
        private String nodeNm;
        private String nodeType;
        private String useYn;
        private String path;
    }

    public record ExportRequest(String itemCd, String itemNm, String plantCd, String status, Boolean withObsolete,
            List<String> sort, List<String> columns) {
    }

    /** 공정 그리드 정렬 허용 열(UI 단계 3 ①) */
    public enum ProcSort implements SortField {
        SORT_NO("sortNo"), PROC_CD("procCd"), PROC_NM("procNm"), PROC_CAT("procCat"), ROUTING_CNT("routingCnt"),
        UPDATED_AT("updatedAt");

        private final String field;

        ProcSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public enum RoutingSort implements SortField {
        ITEM_CD("itemCd"), PLANT_CD("plantCd"), REV("rev"), STATUS("status"), VALID_FROM("validFrom"),
        UPDATED_AT("updatedAt");

        private final String field;

        RoutingSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }
}
