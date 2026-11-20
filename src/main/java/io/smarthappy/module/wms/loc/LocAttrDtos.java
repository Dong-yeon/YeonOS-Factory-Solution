package io.smarthappy.module.wms.loc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** 로케이션 속성·스캔 확인 DTO (P4 W1, docs/30) */
public final class LocAttrDtos {

    private LocAttrDtos() {
    }

    public record PlantRow(String plantCd, String plantNm) {
    }

    /**
     * 창고·로케이션 트리 한 행 — 장비 계층 노드 + 속성(행이 없으면 기본값, attrSet = false).
     * lvl 은 창고·로케이션만 센 깊이(구역 아래 창고도 1).
     */
    @Getter
    @Setter
    public static class LocRow {
        private long nodeId;
        private String plantCd;
        private Long parentId;
        private String nodeType;
        private String nodeCd;
        private String nodeNm;
        private String useYn;
        private int lvl;
        private String lvlMark;
        private String path;
        private int childCnt;
        private boolean attrSet;
        private String locUsage;
        private String stockYn;
        private int pickSeq;
        private String mixItemYn;
        private String mixLotYn;
        private String defaultStatus;
        /** LINESIDE 위치가 보급하는 LINE·CELL(docs/30 12.4.3, D-B12) */
        private Long feedNodeId;
        private String remark;
        /** 속성 행이 없으면 null — 저장 때 그대로 돌려보낸다(새로 만들기) */
        private Integer rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    /** 속성 저장. applyToChildren = 하위 창고·로케이션에 용도·혼적·기본 상태를 같이(재고 둠·피킹 순서·비고 제외) */
    public record AttrForm(String locUsage, String stockYn, Integer pickSeq, String mixItemYn, String mixLotYn,
            String defaultStatus, String remark, Integer rowVer, Boolean applyToChildren, Long feedNodeId) {
    }

    /** 보급 라인 선택지(LINE·CELL) */
    public record LineOpt(long nodeId, String nodeType, String nodeCd, String nodeNm, String path) {
    }

    public record SaveResult(int childrenApplied) {
    }

    /** 적치 검사(W-12) 품목군 선택지 */
    public record ClassOpt(long classId, String classCd, String classNm) {
    }

    /**
     * 위치의 허용 품목군(W-12) — own = 이 노드에 직접 둔 것. own 이 비면 inheritedFrom(상위 노드 코드)·inherited(품목군 코드)가 실효 목록,
     * 둘 다 비면 전부 허용
     */
    public record AllowView(List<Long> own, String inheritedFrom, List<String> inherited) {
    }

    /** 허용 품목군 저장 — 이 노드의 목록 전체(빈 목록 = 직접 목록 없음 → 상위 상속 또는 전부 허용) */
    public record AllowForm(List<Long> classIds) {
    }

    /** 하위 피킹 순서를 트리 순서대로 step 간격으로 다시 매긴다 */
    public record RenumberForm(Integer step) {
    }

    public record ExportRequest(String plantCd, Boolean withUnused, List<String> columns) {
    }

    /** 로케이션 라벨 인쇄 — 고른 노드(+ 하위), 사용 중 창고·로케이션만 */
    public record PrintRequest(String plantCd, List<Long> nodeIds, Boolean includeChildren, Long printerId, Long tplId,
            Integer copies) {
    }

    public record PrintResult(int jobs) {
    }

    public record ScanRequest(String plantCd, String text) {
    }

    /** 스캔 확인 결과 — 판별(순수 함수) + 존재 확인(DB) */
    public record ScanView(String type, String plantCd, String key, boolean bare, boolean ok, String reason, String message,
            Long nodeId, String path, String nodeNm, String nodeUseYn, String locUsage,
            Long itemId, String itemNm, String itemUseYn,
            String gtin, String sscc, String batch, LocalDate expiry, LocalDate prodDate, String serial, BigDecimal qty) {
    }

    /** 노드 찾기 결과 (코드 또는 id) */
    @Getter
    @Setter
    public static class NodeHit {
        private long nodeId;
        private String plantCd;
        private String nodeType;
        private String nodeCd;
        private String nodeNm;
        private String useYn;
        private String path;
        private boolean attrSet;
        private String locUsage;
        private Integer rowVer;
    }

    @Getter
    @Setter
    public static class ItemHit {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String useYn;
    }
}
