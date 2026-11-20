package io.smarthappy.module.master.classprop;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** 품목 클래스·클래스 속성 DTO (P1 7b, docs/22 5b) */
public final class ClassPropDtos {

    private ClassPropDtos() {
    }

    /** 클래스 목록(품목 클래스·설비 클래스 공용 모양) */
    @Getter
    @Setter
    public static class ClassRow {
        private long classId;
        private String classCd;
        private String classNm;
        private int sortNo;
        private String useYn;
        private String remark;
        private int propCnt;
        private int targetCnt;
        private int rowVer;
    }

    public record ClassForm(String classCd, String classNm, Integer sortNo, String useYn, String remark, Integer rowVer) {
    }

    @Getter
    @Setter
    public static class PropRow {
        private long propId;
        private String classType;
        private long classId;
        private String propCd;
        private String propNm;
        private String dataType;
        private String unitNm;
        private Integer scale;
        /** LIST 선택지(JSON 배열 글자) — 서비스가 목록으로 풀어 options 에 */
        private String optionsJson;
        private List<String> options;
        private String requiredYn;
        private String semanticId;
        private int sortNo;
        private String useYn;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    public record PropForm(String classType, Long classId, String propCd, String propNm, String dataType, String unitNm,
            Integer scale, List<String> options, String requiredYn, String semanticId, Integer sortNo, String useYn,
            Integer rowVer) {
    }

    public record PropValue(String classType, long classId, String propCd, String propNm, String dataType, String unitNm,
            Integer scale, String optionsJson, String requiredYn, String semanticId, int sortNo, String useYn,
            Integer rowVer) {
    }

    /** 대상(품목·설비)의 클래스·속성 값 저장 — 값은 형식대로의 JSON(문자열·숫자·true/false) */
    public record AttrsForm(Long classId, Map<String, Object> attrs, Integer rowVer) {
    }

    /** 대상 화면용: 클래스 + 지금 값 + 그 클래스의 속성 정의 */
    public record AttrsView(Long classId, Map<String, Object> attrs, List<PropRow> props, int rowVer) {
    }

    /** 클래스 그리드 정렬 허용 열(UI 단계 3 ①) — 기본은 기존 목록과 같은 순서 → 코드 */
    public enum ClassSort implements SortField {
        SORT_NO("sortNo"), CLASS_CD("classCd"), CLASS_NM("classNm"), PROP_CNT("propCnt"), TARGET_CNT("targetCnt");

        private final String field;

        ClassSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }
}
