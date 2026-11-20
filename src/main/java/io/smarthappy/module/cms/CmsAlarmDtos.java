package io.smarthappy.module.cms;

import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** CMS 2부 C8 DTO — 알람 정의·알람 원장 (docs/33 11.6). 시각은 응답에서 설비 공장 시간대 */
public final class CmsAlarmDtos {

    private CmsAlarmDtos() {
    }

    @Getter
    @Setter
    public static class DefRow {
        /** 엑셀 자동 신고 칸(C23) — 화면과 같은 표시 글자, 서비스가 채운다 */
        private String autoBmNm;
        private long defId;
        private String alarmCd;
        private Long equipClassId;
        private String classCd;
        private String classNm;
        private String alarmNm;
        private String severity;
        private Long symptomId;
        private String symptomCd;
        private String symptomNm;
        private String autoBmYn;
        private String useYn;
        private String remark;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    public record DefForm(String alarmCd, String classCd, String alarmNm, String severity, String symptomCd, String autoBmYn, String useYn,
            String remark, Integer rowVer) {
    }

    /** 알람 이력 한 줄 + 정의(조회 때 — 클래스가 맞는 정의 먼저) + 반복 창 안 발생 수 */
    @Getter
    @Setter
    public static class AlarmRow {
        private long alarmId;
        private long equipId;
        private String equipCd;
        private String equipNm;
        private String plantCd;
        private String alarmCd;
        private String alarmText;
        private String alarmNm;
        private String severity;
        private OffsetDateTime raisedAt;
        private OffsetDateTime clearedAt;
        private String clearAdjustedYn;
        private String terminalCd;
        private Long mwoId;
        private String mwoNo;
        private String mwoStatus;
        private int windowCount;
        private boolean repeat;

        /** 해제까지 분(내림), 열린 알람은 null */
        public Long getMinutes() {
            return clearedAt == null ? null : java.time.Duration.between(raisedAt, clearedAt).toMinutes();
        }

        /** 엑셀(C21) — 공장 현지 글자 */
        public String getRaisedAtText() {
            return CmsSupport.hm(raisedAt);
        }

        public String getClearedAtText() {
            return CmsSupport.hm(clearedAt);
        }

        /** 엑셀(C21)만 — 반복 표시(사용자 언어, 반복 아니면 null) */
        private String repeatNm;
    }

    /** 상위 알람: 기간 안 발생 수·활성 시간(기간으로 자름, 열린 알람은 지금까지) */
    @Getter
    @Setter
    public static class TopRow {
        private long equipId;
        private String equipCd;
        private String equipNm;
        private String alarmCd;
        private String alarmNm;
        private String severity;
        private long raiseCount;
        private long activeMinutes;
        private long openCount;
    }

    /** 수집 때 설비 정보 */
    @Getter
    @Setter
    public static class EquipRef {
        private long equipId;
        private String equipCd;
        private String plantCd;
        private Long classId;
    }

    /** 알람 정의 grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum DefSort implements io.smarthappy.platform.common.query.SortField {
        ALARM_CD("alarmCd"), CLASS_CD("classCd"), ALARM_NM("alarmNm"), SEVERITY("severity"), AUTO_BM("autoBmYn"), USE_YN("useYn");

        private final String field;

        DefSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record DefPage(List<DefRow> list, Long total) {
    }

    /** 알람 이력 grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum AlarmSort implements io.smarthappy.platform.common.query.SortField {
        RAISED_AT("raisedAt"), EQUIP_CD("equipCd"), ALARM_CD("alarmCd"), ALARM_NM("alarmNm"), SEVERITY("severity"), CLEARED_AT("clearedAt");

        private final String field;

        AlarmSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record AlarmPage(List<AlarmRow> list, Long total) {
    }

    /** 알람 이력 엑셀(C21) — grid 조건(공장·기간 필수) + 정렬 + 보이는 열 */
    public record AlarmExport(String plantCd, String from, String to, String keyword, String openOnly, List<String> sort, List<String> columns) {
    }

    /** 알람 정의 엑셀(C23) — grid 와 같은 조건·정렬·보이는 열 */
    public record DefExport(String keyword, String classCd, String useYn, List<String> sort, List<String> columns) {
    }
}
