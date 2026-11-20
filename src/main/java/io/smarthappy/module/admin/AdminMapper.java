package io.smarthappy.module.admin;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.admin.AdminDtos.GrantRow;
import io.smarthappy.module.admin.AdminDtos.GroupForm;
import io.smarthappy.module.admin.AdminDtos.GroupRow;
import io.smarthappy.module.admin.AdminDtos.Member;
import io.smarthappy.module.admin.AdminDtos.MenuForm;
import io.smarthappy.module.admin.AdminDtos.MenuRow;
import io.smarthappy.module.admin.AdminDtos.ProgRow;
import io.smarthappy.module.admin.AdminDtos.UserForm;
import io.smarthappy.module.admin.AdminDtos.UserRow;
import io.smarthappy.module.admin.AdminDtos.UserSearch;

/** 시스템관리(docs/36). 모두 회사별 표(RLS) — 회사 범위 트랜잭션 안에서 */
@Mapper
public interface AdminMapper {

    // ---------- 권한 집합 (부여 상한, "prog:ACTION" 글자) ----------

    /** 행위자 실효 권한(사용 중 그룹·사용 중 화면·화면이 정의한 액션) — 관리자 분기 제외 */
    List<String> effectiveGrants(@Param("userId") long userId);

    /** 사용 중 관리자 그룹 소속 */
    boolean isActiveAdmin(@Param("userId") long userId);

    /** 그룹이 가진 권한(사용 중 화면·정의된 액션만) */
    List<String> groupGrants(@Param("authGrpCd") String authGrpCd);

    /** 사용자가 속한 모든 그룹(사용 여부 무관)의 권한 — 사용 중지 그룹이 다시 켜질 때를 대비해 넓게 */
    List<String> userGrants(@Param("userId") long userId);

    /** 사용자가 관리자 그룹(사용 여부 무관)에 속하는가 */
    boolean inAnyAdminGroup(@Param("userId") long userId);

    /** 쓸 수 있는 관리자 수: 사용 중·수동 잠금 아닌 사용자 × 사용 중 관리자 그룹 */
    int countUsableAdmins();

    /** 회사 단위로 관리자 관련 변경을 한 줄로 (마지막 관리자 경쟁) */
    int lockAdminChanges();

    // ---------- 사용자 ----------

    List<UserRow> findUserPage(@Param("q") UserSearch q);

    long countUsers(@Param("q") UserSearch q);

    UserRow findUser(@Param("userId") long userId);

    boolean existsLoginId(@Param("loginId") String loginId);

    long insertUser(@Param("f") UserForm f, @Param("pwdHash") String pwdHash, @Param("by") String by);

    /** 협력사 사용자 소속 선택지 (사용 중 구매·겸용, 50건) */
    List<AdminDtos.SupplierChoice> findSupplierChoices(@Param("keywordLike") String keywordLike);

    /** 소속 확인 — 사용 중지면 custType = INACTIVE, 없으면 null */
    AdminDtos.SupplierChoice findSupplierCust(@Param("custId") long custId);

    int updateUser(@Param("userId") long userId, @Param("f") UserForm f, @Param("by") String by);

    List<String> userGroupCds(@Param("userId") long userId);

    int insertUserAuth(@Param("userId") long userId, @Param("authGrpCd") String authGrpCd, @Param("by") String by);

    int deleteUserAuth(@Param("userId") long userId, @Param("authGrpCd") String authGrpCd);

    /** 소속 변경도 사용자의 변경으로 본다(버전 확인 + 증가 — 동시 편집 409) */
    int touchUser(@Param("userId") long userId, @Param("rowVer") int rowVer, @Param("by") String by);

    int resetPassword(@Param("userId") long userId, @Param("rowVer") int rowVer, @Param("pwdHash") String pwdHash,
            @Param("by") String by);

    int revokeSessions(@Param("userId") long userId, @Param("rowVer") int rowVer, @Param("by") String by);

    int lockUser(@Param("userId") long userId, @Param("rowVer") int rowVer, @Param("by") String by);

    int unlockUser(@Param("userId") long userId, @Param("rowVer") int rowVer, @Param("by") String by);

    // ---------- 권한그룹 ----------

    List<GroupRow> findGroups();

    /** grid(UI 단계 3 ①) — 검색어(코드·이름) + 페이지·정렬(허용 목록) */
    List<GroupRow> findGroupPage(@Param("keyword") String keyword, @Param("page") io.smarthappy.platform.common.query.PageQuery page);

    long countGroups(@Param("keyword") String keyword);

    GroupRow findGroup(@Param("authGrpCd") String authGrpCd);

    int insertGroup(@Param("f") GroupForm f, @Param("by") String by);

    int updateGroup(@Param("f") GroupForm f, @Param("by") String by);

    int touchGroup(@Param("authGrpCd") String authGrpCd, @Param("rowVer") int rowVer, @Param("by") String by);

    List<GrantRow> groupRows(@Param("authGrpCd") String authGrpCd);

    int insertGrant(@Param("authGrpCd") String authGrpCd, @Param("progCd") String progCd,
            @Param("actionCd") String actionCd, @Param("by") String by);

    int deleteGrant(@Param("authGrpCd") String authGrpCd, @Param("progCd") String progCd,
            @Param("actionCd") String actionCd);

    List<Member> groupMembers(@Param("authGrpCd") String authGrpCd);

    /** 사용 중 프로그램 (전역) */
    List<ProgRow> findProgs();

    // ---------- 메뉴 ----------

    List<MenuRow> findMenus();

    MenuRow findMenu(@Param("menuId") long menuId);

    long insertMenu(@Param("f") MenuForm f, @Param("by") String by);

    int updateMenu(@Param("menuId") long menuId, @Param("f") MenuForm f, @Param("by") String by);
}
