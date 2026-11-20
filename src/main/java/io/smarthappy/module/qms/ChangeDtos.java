package io.smarthappy.module.qms;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.web.ApiError.FieldErrorView;
import lombok.Getter;
import lombok.Setter;

/** P3 QMS 2부 Q5 4M 변경 DTO (docs/29_P3 11장·12.1) */
public final class ChangeDtos {

    private ChangeDtos() {
    }

    /** 목록 한 줄 — ECO 상태·제목은 PLM 계약(EcoLookup)으로 채운다 */
    @Getter
    @Setter
    public static class ChangeRow {
        private long ecoId;
        private String ecoNo;
        private String m4Text;
        private String beforeDesc;
        private String afterDesc;
        private int charCnt;
        private OffsetDateTime updatedAt;
        private String updatedBy;
        // 계약에서
        private String ecoTitle;
        private String ecoStatus;
        private String changeKind;
        private LocalDate effectiveDate;

        public List<String> getM4Types() {
            return split(m4Text);
        }
    }

    @Getter
    @Setter
    public static class ChangeHead {
        private long ecoId;
        private String ecoNo;
        private String m4Text;
        private String beforeDesc;
        private String afterDesc;
        private String reasonDesc;
        private String impactDesc;
        private String revertCond;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;

        public List<String> getM4Types() {
            return split(m4Text);
        }
    }

    static List<String> split(String text) {
        return text == null || text.isEmpty() ? List.of() : List.of(text.split(","));
    }

    @Getter
    @Setter
    public static class CharRow {
        private int lineNo;
        /** CHAR | PLAN */
        private String refType;
        private Long charId;
        private Long inspPlanId;
        private String refLabel;
        private String refNm;
        private String impactDesc;
    }

    /** ECO 머리(계약 ① 요약, 화면 읽기 전용) */
    public record EcoHead(long ecoId, String ecoNo, String title, String changeKind, String status, LocalDate effectiveDate,
            LocalDate markUntil, String custApprovalStatus, int equipCnt, int procCnt) {
    }

    /**
     * @param editable ECO 작성 중 + 저장 권한은 화면이 본다
     * @param required 정책 QMS_4M_REQUIRED + 변경 종류 PROCESS_4M·TEMP — ECO 상신 때 4M 세부가 필요
     * @param issues   지금 상신하면 나올 QMS 칸 오류(미리보기, 문구 포함)
     */
    public record ChangeDetail(EcoHead eco, ChangeHead change, List<CharRow> chars, boolean editable, boolean required,
            List<FieldErrorView> issues) {
    }

    /** 영향 행 고르기 후보 — ECO 대상 품목·공정의 특성, 그 품목의 검사계획(작성 중·확정) */
    @Getter
    @Setter
    public static class Choice {
        private String refType;
        private long refId;
        private String refLabel;
        private String refNm;
    }

    public record CharForm(String refType, Long refId, String impactDesc) {
    }

    public record ChangeForm(List<String> m4Types, String beforeDesc, String afterDesc, String reasonDesc, String impactDesc,
            String revertCond, List<CharForm> chars, Integer rowVer) {
    }

    public record ChangeValue(String[] m4Types, String beforeDesc, String afterDesc, String reasonDesc, String impactDesc,
            String revertCond, Integer rowVer) {
    }

    public record CharValue(int lineNo, Long charId, Long inspPlanId, String impactDesc) {
    }

    public record DeleteForm(Integer rowVer) {
    }

    /** 그리드 정렬 허용 — field = 그리드 열, 이름 = 매퍼 choose 키 */
    public enum ChangeSort implements io.smarthappy.platform.common.query.SortField {
        ECO_NO("ecoNo"), UPDATED_AT("updatedAt");

        private final String field;

        ChangeSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record ChangePage(List<ChangeRow> list, Long total) {
    }
}
