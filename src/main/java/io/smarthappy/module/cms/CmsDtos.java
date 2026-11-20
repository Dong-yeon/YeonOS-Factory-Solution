package io.smarthappy.module.cms;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import io.smarthappy.platform.common.query.SortField;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/** CMS 1부 C1 DTO — 고장 코드·설비 프로필·보전 작업지시 (docs/33) */
public final class CmsDtos {

    private CmsDtos() {
    }

    // ------------------------------------------------------------------ 공통 선택지

    public record Option(String code, String name) {
    }

    public record IdOption(long id, String code, String name) {
    }

    /** 작업지시 대상(설비 또는 금형·지그) 찾기 결과 */
    @Getter
    @Setter
    public static class TargetRow {
        private String targetType;
        private long id;
        private String code;
        private String name;
        private String plantCd;
        private Long classId;
        private String classNm;
        private String assetType;
        private String locationCd;
        private String locationNm;
        private String useYn;
    }

    // ------------------------------------------------------------------ 고장 코드

    @Getter
    @Setter
    public static class FailCodeRow {
        private long failId;
        private String codeType;
        private String failCd;
        private String failNm;
        private String failCat;
        private Long equipClassId;
        private String equipClassCd;
        private String equipClassNm;
        private int sortNo;
        private String useYn;
        private String remark;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    public record FailCodeForm(String codeType, String failCd, String failNm, String failCat, String equipClassCd,
            Integer sortNo, String useYn, String remark, Integer rowVer) {
    }

    public record FailCodeValue(String codeType, String failCd, String failNm, String failCat, Long equipClassId,
            int sortNo, String useYn, String remark, Integer rowVer) {
    }

    // ------------------------------------------------------------------ 설비 프로필

    @Getter
    @Setter
    public static class ProfileRow {
        private long equipId;
        private String equipCd;
        private String equipNm;
        private String plantCd;
        private String classCd;
        private String classNm;
        private String locationCd;
        private String equipUseYn;
        /** 프로필 행이 없으면 null(= C·N·담당 없음) */
        private Integer rowVer;
        private String criticality;
        private String keyEquipYn;
        /** 엑셀(C16)만 — 핵심 설비 예/아니오(사용자 언어). 공통 YN 문구(사용/미사용)는 뜻이 달라 안 쓴다 */
        private String keyEquipNm;
        private Long ownerUserId;
        private String ownerLoginId;
        private String ownerNm;
        /** 개당 이론 사이클(초) — C9 수집 기준 성능·OEE, 비면 그 설비 수집 기준 빈칸 */
        private java.math.BigDecimal idealCtSec;
        private String remark;
        /** 끝나지 않은 작업지시 수 */
        private int openMwoCnt;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    /** idealCtSec = 개당 이론 초 글자(비우면 없음, 0 초과·소수 3자리·정수 7자리) */
    public record ProfileForm(String criticality, String keyEquipYn, String ownerLoginId, String idealCtSec, String remark, Integer rowVer) {
    }

    /** 설비에 지금 설치된 금형·지그·게이지 (P1 설치 이력 읽기) */
    public record InstalledAsset(String assetCd, String assetNm, String assetType, OffsetDateTime installedAt) {
    }

    // ------------------------------------------------------------------ 보전 작업지시

    @Getter
    @Setter
    public static class MwoRow {
        private long mwoId;
        private String mwoNo;
        /** 엑셀(C21 grid 메뉴)만 — 우선순위 이름(사용자 언어) */
        private String priorityNm;
        /** POP 고장 신고(C20) — 작업자·단말·요청 id(사무 계정 신고면 null) */
        private Long reportedWorkerId;
        private String reportedWorkerCd;
        private String reportedWorkerNm;
        private Long reportedTerminalId;
        private String reportedTerminalCd;
        private String reportReqId;
        private String mwoType;
        private String plantCd;
        private String timeZone;
        private String targetType;
        private Long equipId;
        private Long assetId;
        private String targetCd;
        private String targetNm;
        private Long targetClassId;
        private String title;
        private int priority;
        private String status;
        private OffsetDateTime reportedAt;
        private Long requestedBy;
        private String requestedLoginId;
        private String requestedNm;
        /** 엑셀 신고자 칸(POP 신고 = 작업자(POP 단말)) — 서비스가 채운다 */
        private String requesterText;
        private Long symptomId;
        private String symptomCd;
        private String symptomNm;
        private String symptomText;
        private Long assigneeId;
        private String assigneeLoginId;
        private String assigneeNm;
        private LocalDate dueDate;
        private OffsetDateTime startedAt;
        private OffsetDateTime finishedAt;
        private String downYn;
        private OffsetDateTime downStartAt;
        private OffsetDateTime downEndAt;
        private Long causeId;
        private String causeCd;
        private String causeNm;
        private Long actionId;
        private String actionCd;
        private String actionNm;
        private String workText;
        /** ISO 14224 칸(G3, docs/33 16.6) — 공통코드 CMS_FAIL_MECH·CMS_FAIL_DETECT·CMS_FAIL_IMPACT, 고장 수리만·선택 */
        private String failMechCd;
        private String failDetectCd;
        private String failImpactCd;
        private String cancelReason;
        private OffsetDateTime closedAt;
        private String closedLoginId;
        private Long parentMwoId;
        private String parentMwoNo;
        private Long pmPlanId;
        private LocalDate dueRefDate;
        private java.math.BigDecimal dueRefUsage;
        /** 점검 NG 에서 만든 후속이면 출처 점검 실행 */
        private Long srcRunId;
        /** 공수 합(분) */
        private int laborMin;
        /** 기한이 지났는데 끝나지 않음 */
        private boolean overdue;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;

        // 엑셀(C14) — 시각은 조회 때 대상 공장 시간대로 바꿔 두었으므로(decorate) 그 현지 시각 글자로 낸다
        private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        private static String local(OffsetDateTime t) {
            return t == null ? null : t.format(LOCAL);
        }

        public String getReportedAtText() {
            return local(reportedAt);
        }

        public String getStartedAtText() {
            return local(startedAt);
        }

        public String getFinishedAtText() {
            return local(finishedAt);
        }

        /** 설비 정지 분(닫힌 구간만, 내림) */
        public Long getDownMinutes() {
            return downStartAt == null || downEndAt == null ? null : java.time.Duration.between(downStartAt, downEndAt).toMinutes();
        }

        public String getSymptomLabel() {
            return symptomNm != null ? symptomNm : symptomText;
        }
    }

    /** POP 사진 대상(C25) — 작업지시 번호로 잠근 행 */
    @Getter
    @Setter
    public static class PopPhotoTarget {
        private long mwoId;
        private String mwoType;
        private String status;
        private Long reportedTerminalId;
        private String reportReqId;
    }

    /** 작업지시 목록·grid·엑셀 공통 조건(검증된 값) — MyBatis OGNL 이 읽게 getter 클래스 */
    @Getter
    @AllArgsConstructor
    public static class MwoCrit {
        private OffsetDateTime from;
        private OffsetDateTime to;
        private List<String> statuses;
        private String mwoType;
        private String plantCd;
        private Long equipId;
        private Long assigneeId;
        private Long requestedBy;
        private String keyword;
        /** POP 고장 신고만(작업자 배지 — report_req_id 있음) */
        private boolean popOnly;
    }

    /** grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum MwoSort implements SortField {
        MWO_NO("mwoNo"), MWO_TYPE("mwoType"), TARGET_CD("targetCd"), TITLE("title"), PRIORITY("priority"), STATUS("status"),
        REPORTED_AT("reportedAtText"), DUE_DATE("dueDate");

        private final String field;

        MwoSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** grid 응답 한 쪽 */
    public record MwoPage(List<MwoRow> list, Long total) {
    }

    /** 엑셀 조건 — 목록 조회와 같다 */
    /** 작업지시 엑셀 — columns 가 있으면 grid 메뉴 엑셀(C21: 정렬·보이는 열), 없으면 보고서형 전체 칸(C14, 목록 순서) */
    public record MwoExport(String from, String to, String status, String mwoType, String plantCd, String keyword, String scope,
            String source, List<String> sort, List<String> columns) {
    }

    /**
     * 등록·수정 공통 입력. 시각은 대상 공장 현지 "yyyy-MM-ddTHH:mm"(비면 지금 — 등록·착수·완료 때만).
     * 대상·유형은 등록 때만 쓴다(수정 때 다르면 FIXED).
     */
    public record MwoForm(String mwoType, String targetType, String targetCd, String title, Integer priority,
            String symptomCd, String symptomText, String reportedAt, String assigneeLoginId, String dueDate,
            String downYn, String downStartAt, String downEndAt, String startedAt, String finishedAt,
            String causeCd, String actionCd, String workText, Long parentMwoId, Integer rowVer,
            String failMechCd, String failDetectCd, String failImpactCd) {

        /** ISO 14224 칸(G3) 없이 — 시더·POP 신고 등 그 칸을 모르는 호출부 */
        public MwoForm(String mwoType, String targetType, String targetCd, String title, Integer priority,
                String symptomCd, String symptomText, String reportedAt, String assigneeLoginId, String dueDate,
                String downYn, String downStartAt, String downEndAt, String startedAt, String finishedAt,
                String causeCd, String actionCd, String workText, Long parentMwoId, Integer rowVer) {
            this(mwoType, targetType, targetCd, title, priority, symptomCd, symptomText, reportedAt, assigneeLoginId, dueDate, downYn, downStartAt,
                    downEndAt, startedAt, finishedAt, causeCd, actionCd, workText, parentMwoId, rowVer, null, null, null);
        }
    }

    /** 상태 전이 입력: 접수(담당)·착수(시각)·취소(사유)·확인 */
    public record TransitionForm(Integer rowVer, String assigneeLoginId, String startedAt, String reason) {
    }

    public record Created(long mwoId, String mwoNo) {
    }

    @Getter
    @Setter
    public static class LaborRow {
        private long laborId;
        private long userId;
        private String loginId;
        private String userNm;
        private LocalDate workDate;
        private int minutes;
        private String remark;
        private String createdBy;
    }

    public record LaborForm(String loginId, String workDate, Integer minutes, String remark) {
    }

    /** 고장 코드 grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum FailCodeSort implements SortField {
        CODE_TYPE("codeType"), FAIL_CD("failCd"), FAIL_NM("failNm"), FAIL_CAT("failCat"), EQUIP_CLASS("equipClassCd"), SORT_NO("sortNo"), USE_YN("useYn");

        private final String field;

        FailCodeSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record FailCodePage(List<FailCodeRow> list, Long total) {
    }

    /** 고장 코드 엑셀(C16) — grid 조건 + 정렬 + 보이는 열 */
    public record FailCodeExport(String codeType, String useYn, String keyword, List<String> sort, List<String> columns) {
    }

    /** 설비 프로필 grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum ProfileSort implements SortField {
        PLANT_CD("plantCd"), EQUIP_CD("equipCd"), EQUIP_NM("equipNm"), CLASS_CD("classCd"), LOCATION_CD("locationCd"), CRITICALITY("criticality"), KEY_EQUIP("keyEquipYn"), OWNER_NM("ownerNm");

        private final String field;

        ProfileSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record ProfilePage(List<ProfileRow> list, Long total) {
    }

    /** 설비 프로필 엑셀(C16) — grid 조건 + 정렬 + 보이는 열 */
    public record ProfileExport(String plantCd, String keyword, String criticality, String keyOnly, List<String> sort, List<String> columns) {
    }
}
