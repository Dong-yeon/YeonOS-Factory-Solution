package io.smarthappy.module.master.equip;

import java.time.OffsetDateTime;

import io.smarthappy.platform.common.query.SortField;

import lombok.Getter;
import lombok.Setter;

/** 공장·장비 계층 DTO (P1 4a, docs/19) */
public final class EquipDtos {

    private EquipDtos() {
    }

    @Getter
    @Setter
    public static class PlantRow {
        private String plantCd;
        private String plantNm;
        private String addr;
        private String timeZone;
        private int sortNo;
        private String useYn;
        private int nodeCnt;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    public record PlantForm(String plantCd, String plantNm, String addr, String timeZone, Integer sortNo, String useYn,
            Integer rowVer) {
    }

    /** 트리 한 행 — 서버가 경로 순서(순서 → 코드)로 보낸다. lvl 1 = 공장 바로 아래 */
    @Getter
    @Setter
    public static class NodeRow {
        private long nodeId;
        private String plantCd;
        private Long parentId;
        private String nodeType;
        private String nodeCd;
        private String nodeNm;
        private int sortNo;
        private String useYn;
        private String remark;
        private int rowVer;
        private int lvl;
        private String lvlMark;
        /** 공장/구역/라인… 코드 경로 (예: P1/BODY/L2) — 나중 UNS 토픽과 같은 모양 */
        private String path;
        private int childCnt;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    public record NodeForm(String plantCd, Long parentId, String nodeType, String nodeCd, String nodeNm, Integer sortNo,
            String useYn, String remark, Integer rowVer) {
    }

    public record ExportRequest(String plantCd, Boolean withUnused, java.util.List<String> columns) {
    }

    /** 공장 그리드 정렬 허용 열(UI 단계 3 ①) */
    public enum PlantSort implements SortField {
        SORT_NO("sortNo"), PLANT_CD("plantCd"), PLANT_NM("plantNm"), NODE_CNT("nodeCnt");

        private final String field;

        PlantSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }
}
