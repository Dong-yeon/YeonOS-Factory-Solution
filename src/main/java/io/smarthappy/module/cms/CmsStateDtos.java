package io.smarthappy.module.cms;

import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** CMS 2부 C7 DTO — 상태 사유 관리·비가동 사유 검토 (docs/33 11.2 C7). 시각은 응답에서 설비 공장 시간대 */
public final class CmsStateDtos {

    private CmsStateDtos() {
    }

    /** 상태 사유 (MES 표 cms_state_reason — 화면·API 만 CMS, Q1) */
    @Getter
    @Setter
    public static class ReasonRow {
        /** 엑셀 계획 칸(C23) — 화면과 같은 표시 글자, 서비스가 채운다 */
        private String plannedNm;
        private long reasonId;
        private String reasonCd;
        private String reasonNm;
        private String stateCat;
        private String plannedYn;
        private int sortNo;
        private String useYn;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    public record ReasonForm(String reasonCd, String reasonNm, String stateCat, String plannedYn, Integer sortNo, String useYn,
            Integer rowVer) {
    }

    /** 검토 표 한 줄 = 상태 구간 + 유효 사후 사유 + 유효 작업지시 연결 */
    @Getter
    @Setter
    public static class ReviewRow {
        private long stateLogId;
        private long equipId;
        private String equipCd;
        private String equipNm;
        private String plantCd;
        /** 원장 분류 */
        private String stateCat;
        /** 실효 분류 = 사후 사유가 있으면 그 분류(UDT ↔ SDT 재분류), 없으면 원장 분류 */
        private String effCat;
        private String logReasonCd;
        private String logReasonNm;
        private Long noteId;
        private String noteReasonCd;
        private String noteReasonNm;
        private String notePlannedYn;
        private String noteText;
        private String noteBy;
        private OffsetDateTime noteAt;
        private OffsetDateTime startAt;
        private OffsetDateTime endAt;
        private String woNo;
        private String source;
        private Long linkId;
        private Long mwoId;
        private String mwoNo;
        private String mwoStatus;
        private OffsetDateTime mwoDownStartAt;
        private OffsetDateTime mwoDownEndAt;

        /** 닫힌 구간 길이(분, 내림). 열린 구간은 null */
        public Long getMinutes() {
            return endAt == null ? null : java.time.Duration.between(startAt, endAt).toMinutes();
        }

        /** 엑셀(C21) — 공장 현지 글자 */
        public String getStartAtText() {
            return CmsSupport.hm(startAt);
        }

        public String getEndAtText() {
            return CmsSupport.hm(endAt);
        }
    }

    /** 사후 사유 이력(무효 포함) */
    @Getter
    @Setter
    public static class NoteRow {
        private long noteId;
        private String reasonCd;
        private String reasonNm;
        private String stateCat;
        private String noteText;
        private OffsetDateTime recordedAt;
        private String recordedBy;
        private String voidYn;
        private String voidReason;
        private String voidedBy;
        private OffsetDateTime voidedAt;
    }

    /** 작업지시 연결 이력(무효 포함) */
    @Getter
    @Setter
    public static class LinkRow {
        private long linkId;
        private long mwoId;
        private String mwoNo;
        private String title;
        private String status;
        private OffsetDateTime downStartAt;
        private OffsetDateTime downEndAt;
        private OffsetDateTime recordedAt;
        private String recordedBy;
        private String voidYn;
        private String voidReason;
        private String voidedBy;
        private OffsetDateTime voidedAt;
    }

    public record ReviewDetail(ReviewRow row, String timeZone, List<NoteRow> notes, List<LinkRow> links) {
    }

    /** 연결 후보: 같은 설비의 고장(BM) 작업지시, 취소 제외, 구간 앞뒤 하루 안에 접수 */
    @Getter
    @Setter
    public static class MwoOption {
        private long mwoId;
        private String mwoNo;
        private String title;
        private String status;
        private OffsetDateTime reportedAt;
        private OffsetDateTime downStartAt;
        private OffsetDateTime downEndAt;
    }

    public record NoteForm(String reasonCd, String noteText) {
    }

    public record LinkForm(Long mwoId) {
    }

    /** 구간 잠금·검사용 */
    @Getter
    @Setter
    public static class IntervalRef {
        private long stateLogId;
        private long equipId;
        private String plantCd;
        private String stateCat;
        private OffsetDateTime startAt;
        private OffsetDateTime endAt;
    }

    /** 보전 작업지시 상세(C11): 연결된 수집 정지 구간 */
    @Getter
    @Setter
    public static class MwoDownRow {
        private long stateLogId;
        private String stateCat;
        private String effCat;
        private OffsetDateTime startAt;
        private OffsetDateTime endAt;
        private String reasonCd;
        private String reasonNm;

        public Long getMinutes() {
            return endAt == null ? null : java.time.Duration.between(startAt, endAt).toMinutes();
        }
    }

    /** 보전 작업지시 상세(C11): 이 작업지시를 만든 알람 */
    @Getter
    @Setter
    public static class MwoAlarmRow {
        private long alarmId;
        private String alarmCd;
        private String alarmText;
        private OffsetDateTime raisedAt;
        private OffsetDateTime clearedAt;
    }

    public record MwoDowntime(String timeZone, List<MwoDownRow> intervals, List<MwoAlarmRow> alarms, Long collectedMinutes) {
    }

    /** 상태 사유 grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum ReasonSort implements io.smarthappy.platform.common.query.SortField {
        STATE_CAT("stateCat"), REASON_CD("reasonCd"), REASON_NM("reasonNm"), PLANNED("plannedYn"), SORT_NO("sortNo"), USE_YN("useYn");

        private final String field;

        ReasonSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record ReasonPage(List<ReasonRow> list, Long total) {
    }

    /** 상태 검토 grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum ReviewSort implements io.smarthappy.platform.common.query.SortField {
        START_AT("startAt"), EQUIP_CD("equipCd"), EFF_CAT("effCat"), END_AT("endAt"), MWO_NO("mwoNo");

        private final String field;

        ReviewSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record ReviewPage(List<ReviewRow> list, Long total) {
    }

    /** 상태 검토 엑셀(C21) — grid 조건(공장·기간 필수) + 정렬 + 보이는 열 */
    public record ReviewExport(String plantCd, String from, String to, String keyword, String effCat, String pending, List<String> sort,
            List<String> columns, String reasonCd) {
    }

    /** 상태 사유 엑셀(C23) — grid 와 같은 조건·정렬·보이는 열 */
    public record ReasonExport(String keyword, String stateCat, String useYn, List<String> sort, List<String> columns) {
    }
}
