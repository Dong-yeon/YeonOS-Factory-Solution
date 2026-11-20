package io.smarthappy.module.admin;

import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** 시스템관리 1단계(docs/36) DTO — 사용자·권한그룹·메뉴 */
public final class AdminDtos {

    private AdminDtos() {
    }

    // ------------------------------------------------------------------ 사용자

    @Getter
    @Setter
    public static class UserRow {
        private long userId;
        private String loginId;
        private String userNm;
        private String email;
        private String localeCd;
        /** NONE · FAIL(실패 잠금, 자동 해제) · MANUAL(관리자 잠금) — 조회 때 계산 */
        private String lockState;
        private OffsetDateTime lockedUntil;
        private String pwdChangeReqYn;
        private OffsetDateTime lastLoginAt;
        private String groupNames;
        private Long deptId;
        private String deptNm;
        private String useYn;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
        /** MFA 등록 여부 (docs/36 14장) */
        private boolean mfaEnrolled;
        /** 이 사람이 대리인 끝나지 않은 위임 수 — 사용 중지하면 종료된다(docs/36 16.6, 확인 대화상자) */
        private int activeDelegatedCnt;
        /** INTERNAL · SUPPLIER(협력사 포털 사용자, docs/42) — 만든 뒤 바꾸지 않는다 */
        private String userType;
        private Long custId;
        private String custCd;
        private String custNm;
    }

    @Getter
    public static class UserSearch {
        private final String keywordLike;
        private final String authGrpCd;
        private final String useYn;
        private final String lockState;
        private final Long deptId;
        private final String userType;
        private final PageQuery page;

        public UserSearch(String keywordLike, String authGrpCd, String useYn, String lockState, Long deptId,
                String userType, PageQuery page) {
            this.keywordLike = keywordLike;
            this.authGrpCd = authGrpCd;
            this.useYn = useYn;
            this.lockState = lockState;
            this.deptId = deptId;
            this.userType = userType;
            this.page = page;
        }
    }

    /** 사용자 저장. 아이디는 추가 때만(바꾸지 않음), 비밀번호 칸은 없다 — 서버가 임시 비밀번호를 만든다(docs/36 Q2) */
    public record UserForm(String loginId, String userNm, String email, String localeCd, String useYn,
            List<String> authGrpCds, Long deptId, Integer rowVer, String userType, Long custId) {

        /** 회사 사용자 (엑셀 올리기 — 협력사 계정은 화면에서 한 명씩, docs/42 5장) */
        public UserForm(String loginId, String userNm, String email, String localeCd, String useYn,
                List<String> authGrpCds, Long deptId, Integer rowVer) {
            this(loginId, userNm, email, localeCd, useYn, authGrpCds, deptId, rowVer, null, null);
        }
    }

    /** 협력사 사용자 소속으로 고를 수 있는 거래처 — 같은 회사의 사용 중 구매·겸용 */
    public record SupplierChoice(long custId, String custCd, String custNm, String custType) {
    }

    /** 사용자 상세 + 권한그룹 선택지(내가 줄 수 있는지 포함) */
    public record UserDetail(UserRow user, List<String> authGrpCds, List<GroupChoice> groups, boolean self,
            boolean manageable) {
    }

    public record GroupChoice(String authGrpCd, String authGrpNm, String adminYn, String useYn, boolean grantable) {
    }

    /** 추가·초기화 결과: 임시 비밀번호는 이 응답에서 한 번만 */
    public record TempPassword(long userId, String loginId, String tempPassword, int rowVer) {
    }

    public record RowVerRequest(Integer rowVer) {
    }

    public enum UserSort implements SortField {
        LOGIN_ID("loginId"), USER_NM("userNm"), LAST_LOGIN_AT("lastLoginAt"), USE_YN("useYn"), UPDATED_AT("updatedAt");

        private final String field;

        UserSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    // ------------------------------------------------------------------ 권한그룹

    @Getter
    @Setter
    public static class GroupRow {
        private String authGrpCd;
        private String authGrpNm;
        private int sortNo;
        private String homeProgCd;
        private String adminYn;
        private String useYn;
        private int memberCnt;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
        /** 내가 이 그룹을 고칠 수 있는가(부여 상한) — 서비스가 채운다 */
        private boolean editable;
    }

    /** 권한그룹 grid 정렬 허용 목록(UI 단계 3 ①, docs/36 17.1) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum GroupSort implements SortField {
        AUTH_GRP_CD("authGrpCd"), AUTH_GRP_NM("authGrpNm"), SORT_NO("sortNo"), MEMBER_CNT("memberCnt"), USE_YN("useYn");

        private final String field;

        GroupSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 권한그룹 grid 한 쪽 */
    public record GroupPage(List<GroupRow> list, Long total) {
    }

    public record GroupForm(String authGrpCd, String authGrpNm, Integer sortNo, String homeProgCd, String adminYn,
            String useYn, Integer rowVer) {
    }

    /** 권한 표 한 행: 화면 하나와 이 그룹이 가진 액션 */
    public record PermRow(String progCd, String progNm, String moduleCd, List<String> actions, List<String> granted,
            List<String> grantable) {
    }

    public record Member(long userId, String loginId, String userNm, String useYn) {
    }

    public record GroupDetail(GroupRow group, List<PermRow> perms, List<Member> members) {
    }

    /** 권한 표 저장 — 화면별 액션 목록 한 벌(사용 중 화면만, 없는 화면 = 권한 없음) */
    public record PermItem(String progCd, List<String> actions) {
    }

    public record PermSave(List<PermItem> items) {
    }

    @Getter
    @Setter
    public static class ProgRow {
        private String progCd;
        private String progNmKey;
        private String moduleCd;
        /** sys_prog.actions 를 쉼표로 이은 것 */
        private String actionsCsv;
        private int sortNo;

        public List<String> actionList() {
            return actionsCsv == null || actionsCsv.isEmpty() ? List.of() : List.of(actionsCsv.split(","));
        }
    }

    @Getter
    @Setter
    public static class GrantRow {
        private String authGrpCd;
        private String progCd;
        private String actionCd;
    }

    // ------------------------------------------------------------------ 메뉴

    @Getter
    @Setter
    public static class MenuRow {
        private long menuId;
        private Long parentId;
        private String menuNm;
        private String menuNmKey;
        private String progCd;
        private String progNmKey;
        private String progUseYn;
        private int sortNo;
        private String useYn;
        private int rowVer;
    }

    /** 메뉴 트리 한 줄 (hb.tree 평면 행 + 레벨, 경로 순서) */
    public record MenuNode(long menuId, Long parentId, int lvl, String name, String menuNm, String menuNmKey,
            String progCd, int sortNo, String useYn, String kind, String kindNm, boolean dim, int rowVer) {
    }

    public record MenuForm(Long parentId, String menuNm, String progCd, Integer sortNo, String useYn, Integer rowVer) {
    }

    public record ProgChoice(String progCd, String name, String moduleCd) {
    }
}
