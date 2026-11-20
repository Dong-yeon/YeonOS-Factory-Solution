package io.smarthappy.module.master.workforce;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.SortField;

import lombok.Getter;
import lombok.Setter;

/** 근무조·작업자·자격 DTO (P2 1단계, docs/25 4.2) */
public final class WorkforceDtos {

    private WorkforceDtos() {
    }

    // ------------------------------------------------------------------ 근무조

    @Getter
    @Setter
    public static class ShiftRow {
        private long shiftId;
        private String plantCd;
        private String shiftCd;
        private String shiftNm;
        private LocalTime startTime;
        private LocalTime endTime;
        private int dayOffset;
        private int breakMin;
        private int sortNo;
        private String useYn;
        private String remark;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    /** 시각은 "HH:mm" 글자 */
    public record ShiftForm(String plantCd, String shiftCd, String shiftNm, String startTime, String endTime,
            Integer dayOffset, Integer breakMin, Integer sortNo, String useYn, String remark, Integer rowVer) {
    }

    public record ShiftValue(String plantCd, String shiftCd, String shiftNm, LocalTime startTime, LocalTime endTime,
            int dayOffset, int breakMin, int sortNo, String useYn, String remark, Integer rowVer) {
    }

    /** 시각 → 작업일·근무조 (확인용) */
    public record WorkDayView(String plantCd, String zone, String localTime, LocalDate workDay, String shiftCd,
            String shiftNm) {
    }

    // ------------------------------------------------------------------ 자격

    @Getter
    @Setter
    public static class QualRow {
        private long qualId;
        private String qualCd;
        private String qualNm;
        private Integer validMonths;
        private int sortNo;
        private String useYn;
        private String remark;
        /** 지금 유효한 보유자 수 */
        private int holderCnt;
        /** 이 자격을 요구하는 사용 중 공정 수 */
        private int procCnt;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;

        /** 그리드 유효 개월 칸 — 만료 없음은 빈 글자(그리드는 null 칸을 format 없이 비운다, 화면이 "만료 없음" 을 붙임) */
        public String getValidText() {
            return validMonths == null ? "" : String.valueOf(validMonths);
        }
    }

    public record QualForm(String qualCd, String qualNm, Integer validMonths, Integer sortNo, String useYn,
            String remark, Integer rowVer) {
    }

    @Getter
    @Setter
    public static class ReqRow {
        private long reqId;
        private long procId;
        private String procCd;
        private String procNm;
        private String procUseYn;
        private long qualId;
        private String qualCd;
        private String qualNm;
        private String gateMode;
        private String useYn;
        private int rowVer;
    }

    /** 새 요구 = procCd + qualCd, 수정 = gateMode·useYn + rowVer */
    public record ReqForm(String procCd, String qualCd, String gateMode, String useYn, Integer rowVer) {
    }

    /** 공정 선택지 */
    @Getter
    @Setter
    public static class ProcOption {
        private String procCd;
        private String procNm;
    }

    // ------------------------------------------------------------------ 작업자

    @Getter
    @Setter
    public static class WorkerRow {
        private long workerId;
        private String workerCd;
        private String workerNm;
        private String plantCd;
        private String plantNm;
        private String deptNm;
        /** 배지 지정 여부(값·해시는 내보내지 않는다) */
        private boolean badgeSet;
        /** POP PIN 지정 여부 */
        private boolean pinSet;
        private Long userId;
        private String loginId;
        private String useYn;
        private String remark;
        /** 지금 유효한 자격 수 */
        private int qualCnt;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    public record WorkerForm(String workerCd, String workerNm, String plantCd, String deptNm, String loginId,
            String useYn, String remark, Integer rowVer) {
    }

    public record WorkerValue(String workerCd, String workerNm, String plantCd, String deptNm, Long userId,
            String useYn, String remark, Integer rowVer) {
    }

    /** 배지 지정 — badge 가 비면 지우기 */
    public record BadgeForm(String badge, Integer rowVer) {
    }

    public record PinForm(String pin, Integer rowVer) {
    }

    @Getter
    @Setter
    public static class WorkerQualRow {
        private long wqId;
        private long workerId;
        private long qualId;
        private String qualCd;
        private String qualNm;
        private LocalDate acquiredOn;
        private LocalDate expiresOn;
        private LocalDate revokedOn;
        private String certNo;
        private String remark;
        /** 오늘(회사 시간대) 기준 VALID / EXPIRED / REVOKED / NOT_YET — 서비스가 채운다 */
        private String state;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    /** 새 보유 = qualCd·acquiredOn(필수)·expiresOn(비면 유효 개월로), 수정 = expiresOn·revokedOn·certNo·remark + rowVer */
    public record WorkerQualForm(String qualCd, String acquiredOn, String expiresOn, String revokedOn, String certNo,
            String remark, Integer rowVer) {
    }

    public record WorkerQualValue(long qualId, LocalDate acquiredOn, LocalDate expiresOn, LocalDate revokedOn,
            String certNo, String remark, Integer rowVer) {
    }

    // ------------------------------------------------------------------ 게이트

    /** mode: OK / WARN / BLOCK. missing: 빠진 자격과 이유(NONE·EXPIRED·REVOKED·NOT_YET) */
    public record GateResult(String mode, String policyMode, List<GateMissing> missing) {
    }

    public record GateMissing(String qualCd, String qualNm, String gateMode, String reason) {
    }

    /** 게이트 계산용 — 요구 하나와 그 작업자의 보유 행들 */
    @Getter
    @Setter
    public static class GateReqRow {
        private long qualId;
        private String qualCd;
        private String qualNm;
        private String gateMode;
    }

    /** 작업자 그리드 정렬 허용 열(UI 단계 3 ①) */
    public enum WorkerSort implements SortField {
        WORKER_CD("workerCd"), WORKER_NM("workerNm"), PLANT("plantNm"), DEPT_NM("deptNm"), QUAL_CNT("qualCnt"),
        UPDATED_AT("updatedAt");

        private final String field;

        WorkerSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 근무조 그리드 정렬 허용 열 — 기본은 기존 목록과 같은 순서·시작·코드 */
    public enum ShiftSort implements SortField {
        SORT_NO("sortNo"), SHIFT_CD("shiftCd"), SHIFT_NM("shiftNm"), START_TIME("startTime");

        private final String field;

        ShiftSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 자격 그리드 정렬 허용 열(UI 단계 3 ①) — 기본은 기존 목록과 같은 순서 → 코드 */
    public enum QualSort implements SortField {
        SORT_NO("sortNo"), QUAL_CD("qualCd"), QUAL_NM("qualNm"), VALID_MONTHS("validText"), HOLDER_CNT("holderCnt"),
        PROC_CNT("procCnt");

        private final String field;

        QualSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }
}
