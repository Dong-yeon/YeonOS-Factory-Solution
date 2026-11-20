package io.smarthappy.module.master.ext;

import java.time.OffsetDateTime;

import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** 외부 시스템·외부 ID 매핑·코드 매핑 DTO (P1 7a, docs/22) */
public final class ExtDtos {

    private ExtDtos() {
    }

    @Getter
    @Setter
    public static class SystemRow {
        private long systemId;
        private String systemCd;
        private String systemNm;
        private String systemType;
        /** 연결 방식 DIRECT/EDGE/AGENT (docs/65 3장 — E4·E5 WES·Fleet 가 쓸 자리) */
        private String connMode;
        private Long custId;
        private String custCd;
        private String custNm;
        private String useYn;
        private String remark;
        private int mapCnt;
        private int codeMapCnt;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    /** connMode 생략 = 신규 DIRECT · 수정은 지금 값 그대로 */
    public record SystemForm(String systemCd, String systemNm, String systemType, String custCd, String useYn, String connMode,
            String remark, Integer rowVer) {
    }

    public record SystemValue(String systemCd, String systemNm, String systemType, Long custId, String useYn, String connMode,
            String remark, Integer rowVer) {
    }

    @Getter
    @Setter
    public static class CodeMapRow {
        private long codeMapId;
        private long systemId;
        private String codeType;
        private String extCode;
        private String intCode;
        private String useYn;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    public record CodeMapForm(String codeType, String extCode, String intCode, String useYn, Integer rowVer) {
    }

    @Getter
    @Setter
    public static class IdMapRow {
        private long mapId;
        private long systemId;
        private String systemCd;
        private String systemNm;
        private String systemType;
        private String entityType;
        private long internalId;
        private String externalId;
        private String externalNm;
        private String useYn;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    public record IdMapForm(String systemCd, String externalId, String externalNm, String useYn, Integer rowVer) {
    }

    public record IdMapValue(Long systemId, String entityType, Long internalId, String externalId, String externalNm,
            String useYn, Integer rowVer) {
    }

    /** 외부 시스템 그리드 정렬 허용 열(UI 단계 3 ①) — 기본은 기존 목록과 같은 유형 → 코드 */
    public enum SystemSort implements SortField {
        SYSTEM_TYPE("systemType"), SYSTEM_CD("systemCd"), SYSTEM_NM("systemNm"), MAP_CNT("mapCnt"), CODE_MAP_CNT("codeMapCnt");

        private final String field;

        SystemSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }
}
