package io.smarthappy.module.admin;

import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** 시스템관리 2단계(docs/36 9장) DTO — 부서·정책·회사 코드·채번·회사 정보 */
public final class SettingsDtos {

    private SettingsDtos() {
    }

    // ------------------------------------------------------------------ 부서

    @Getter
    @Setter
    public static class DeptRow {
        private long deptId;
        private Long parentId;
        private String deptCd;
        private String deptNm;
        private int sortNo;
        private String useYn;
        private int memberCnt;
        private int rowVer;
        private Long headUserId;
        private String headNm;
        private String headLoginId;
        private boolean headActive;
    }

    /** 트리 한 줄 (hb.tree 평면 행 + 레벨, 경로 순서) */
    /** headActive = 부서장이 사용 중(아니면 화면이 "사용 중지" 표시 — 결재선 기본값에서는 건너뛴다) */
    public record DeptNode(long deptId, Long parentId, int lvl, String deptCd, String deptNm, String path, int sortNo,
            String useYn, int memberCnt, boolean dim, int rowVer, Long headUserId, String headNm, String headLoginId,
            boolean headActive) {
    }

    /** headUserId = 부서장(선택, 사용 중 회사 사용자 — 바꿀 때만 검사) */
    public record DeptForm(Long parentId, String deptCd, String deptNm, Integer sortNo, String useYn, Integer rowVer,
            Long headUserId) {
    }

    /** 다른 화면의 부서 선택지 */
    public record DeptChoice(long deptId, String deptCd, String deptNm, String path) {
    }

    // ------------------------------------------------------------------ 정책

    @Getter
    @Setter
    public static class PolicyRow {
        private String policyKey;
        private String valueType;
        private String defaultValue;
        private String[] allowedValues;
        private Integer minValue;
        private Integer maxValue;
        private String plantScopeYn;
        private String descKey;
    }

    @Getter
    @Setter
    public static class PolicyValueRow {
        private String plantCd;
        private String policyKey;
        private String policyValue;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    /** 화면 한 줄: 정의 + 회사 값(없으면 null) + 공장 값들 */
    public record PolicyView(String policyKey, String category, String desc, String valueType, String defaultValue,
            List<String> allowedValues, Integer minValue, Integer maxValue, boolean plantScope, boolean security,
            boolean editable, List<PolicyValueRow> values) {
    }

    /**
     * 정책 grid 한 줄(UI 단계 3 ①, docs/36 17.2) — 표 칸(펼친 값) + 상세가 쓰는 정의 전체(view)
     * companyValue = 회사 값(없으면 null), companyText = 표 칸 글자(없으면 "기본값 x"), plantValues = "P1=x, P2=y"
     */
    public record PolicyGridRow(String policyKey, String category, String desc, String companyValue, String companyText,
            String plantValues, boolean changed, PolicyView view) {
    }

    /** 정책 grid 정렬 허용 목록 — 설명은 문구 키라 서버 글자(지금 언어)로 정렬 */
    public enum PolicySort implements io.smarthappy.platform.common.query.SortField {
        POLICY_KEY("policyKey"), CATEGORY("category"), DESC("desc");

        private final String field;

        PolicySort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 정책 grid 한 쪽 */
    public record PolicyPage(List<PolicyGridRow> list, Long total) {
    }

    /** 저장: plantCd 비우면 회사 전체. rowVer = 그 칸의 지금 버전(새 값이면 null) */
    public record PolicySave(String plantCd, String value, Integer rowVer) {
    }

    // ------------------------------------------------------------------ 회사 공통코드

    @Getter
    @Setter
    public static class CodeGroupRow {
        private String grpCd;
        private String grpNmKey;
        /** 회사 코드 최대 길이 — 그 코드를 담는 업무 칸 너비(docs/68, 기본 30) */
        private int codeMaxLen = 30;
    }

    @Getter
    @Setter
    public static class CompCodeRow {
        private String grpCd;
        private String code;
        private String codeNm;
        private String codeNmKey;
        private int sortNo;
        private String useYn;
        private boolean systemYn;
        private String attrs;
        private int rowVer;
        /** 시스템 코드를 회사가 숨길 수 있는지(회사 확장 그룹 + 분기에 안 쓰임, docs/68 Q4). 회사 코드는 false */
        private boolean hideable;
    }

    /** 시스템 코드의 useYn = 이 회사에서 선택 목록에 보이는지(회사 숨김이면 N), hideable = 숨기기·되살리기 가능(docs/68 Q4) */
    public record CodeView(String code, String name, int sortNo, String useYn, boolean system, Integer scale, int rowVer,
            boolean hideable) {
    }

    /** 시스템 코드 숨기기(N)·되살리기(Y) — 이 회사 선택 목록에서만(docs/68 Q4) */
    public record ShownForm(String useYn) {
    }

    /** 공통코드 grid 정렬 허용 목록(UI 단계 3 ①) — 이름은 시스템 코드가 문구 키라 SQL 로 정렬하지 않는다 */
    public enum CodeSort implements io.smarthappy.platform.common.query.SortField {
        CODE("code"), SORT_NO("sortNo"), USE_YN("useYn");

        private final String field;

        CodeSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 공통코드 grid 한 쪽 */
    public record CodePage(List<CodeView> list, Long total) {
    }

    public record CodeForm(String code, String codeNm, Integer sortNo, String useYn, Integer scale, Integer rowVer) {
    }

    // ------------------------------------------------------------------ 채번

    @Getter
    @Setter
    public static class RuleRow {
        private String ruleCd;
        private String ruleNm;
        private String pattern;
        private String resetCycle;
        private String useYn;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    /** 목록 한 줄 + 지금 기간 마지막 번호·다음 번호 미리보기(번호를 쓰지 않음) */
    public record RuleView(RuleRow rule, Long lastNo, String nextPreview, boolean needsPlant) {
    }

    /** 채번 grid 한 줄 — 목록(RuleView)을 펼친 모양(grid 열은 행의 첫 단계 필드만 읽는다) */
    public record RuleGridRow(String ruleCd, String ruleNm, String pattern, String resetCycle, String useYn, int rowVer,
            OffsetDateTime updatedAt, String updatedBy, Long lastNo, String nextPreview, boolean needsPlant) {
    }

    /** 채번 grid 정렬 허용 목록 — 마지막 번호·다음 번호는 계산 값이라 정렬하지 않는다 */
    public enum RuleSort implements io.smarthappy.platform.common.query.SortField {
        RULE_CD("ruleCd"), RULE_NM("ruleNm"), RESET_CYCLE("resetCycle"), USE_YN("useYn");

        private final String field;

        RuleSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 채번 grid 한 쪽 */
    public record RulePage(List<RuleGridRow> list, Long total) {
    }

    public record RuleForm(String ruleCd, String ruleNm, String pattern, String resetCycle, String useYn, Integer rowVer) {
    }

    /** 저장 전 미리보기 */
    public record PreviewRequest(String ruleCd, String pattern, String resetCycle) {
    }

    public record Preview(String sample, Long lastNo, boolean changesPeriod) {
    }

    // ------------------------------------------------------------------ 회사 정보

    @Getter
    @Setter
    public static class CompRow {
        private String compCd;
        private String compNm;
        private String compEnNm;
        private String bizNo;
        private String ceoNm;
        private String telNo;
        private String addr;
        private String timeZone;
        private String localeCd;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    /** 시간대를 바꿀 때는 confirmTimeZone = true 필수(작업일·채번 날짜 기준이 바뀜) */
    public record CompForm(String compNm, String compEnNm, String bizNo, String ceoNm, String telNo, String addr,
            String timeZone, String localeCd, Boolean confirmTimeZone, Integer rowVer) {
    }

    public record CompView(CompRow comp, boolean canChangeTimeZone) {
    }
}
