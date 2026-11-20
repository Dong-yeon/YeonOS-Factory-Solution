package io.smarthappy.module.home;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 홈 카드 설정 (docs/37 14.1 사용자 · 15장 역할별 기본) — sys_home_user_card · sys_home_grp_card. 회사 조건은 RLS */
@Mapper
public interface HomeMapper {

    List<PrefRow> findPrefs(@Param("userId") long userId);

    int deletePrefs(@Param("userId") long userId);

    int insertPrefs(@Param("userId") long userId, @Param("rows") List<PrefRow> rows, @Param("by") String by);

    /** 사용자의 역할별 기본 세트 — 세트가 있는 사용 중 그룹 중 첫 그룹(그룹 순서·코드 순, 역할별 홈과 같은 기준) */
    List<PrefRow> findGroupDefault(@Param("userId") long userId);

    // ---------- 관리 화면 home-card-mgt (15장) ----------

    List<GroupRow> findGroups();

    GroupRow findGroup(@Param("authGrpCd") String authGrpCd);

    List<PrefRow> findGroupCards(@Param("authGrpCd") String authGrpCd);

    /** 세트 판(版) — 행이 하나라도 바뀌면(지우고 다시 넣으면 id 가 바뀐다) 달라지는 지문. 동시 편집 409 용 */
    String groupVer(@Param("authGrpCd") String authGrpCd);

    /** 그룹이 조회 권한을 가진 화면 (사용 중 화면만) + 개인 화면(self_service, 모두 조회 가능) */
    List<String> groupSearchProgs(@Param("authGrpCd") String authGrpCd);

    /** 같은 그룹 세트 저장을 줄 세운다(트랜잭션 잠금 — 남의 표 sys_auth_group 에 잠금을 걸지 않으려고 권고 잠금) */
    String lockGroup(@Param("authGrpCd") String authGrpCd);

    int deleteGroupCards(@Param("authGrpCd") String authGrpCd);

    int insertGroupCards(@Param("authGrpCd") String authGrpCd, @Param("rows") List<PrefRow> rows, @Param("by") String by);

    // ---------- 공장 선택 (5단계, docs/37 17.2) ----------

    /** 사용 중 공장(순서·코드 순) — 회사 조건은 RLS */
    List<PlantRow> findPlants();

    /** 사용 중 공장 하나(없거나 사용 중지·다른 회사면 null) */
    PlantRow findPlant(@Param("plantCd") String plantCd);

    record PlantRow(String plantCd, String plantNm) {
    }

    record PrefRow(String cardCd, int sortNo, String hiddenYn) {
    }

    record GroupRow(String authGrpCd, String authGrpNm, int sortNo, String adminYn, String useYn, int cardCnt) {
    }
}
