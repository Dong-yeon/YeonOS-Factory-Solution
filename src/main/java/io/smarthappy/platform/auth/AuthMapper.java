package io.smarthappy.platform.auth;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 권한·메뉴·프로그램 조회. 회사별 테이블은 RLS 범위 안(트랜잭션)에서 호출 */
@Mapper
public interface AuthMapper {

    /** 사용자가 가진 프로그램 액션 (사용 중인 권한그룹·프로그램, 프로그램이 정의한 액션만) */
    List<String> findActions(@Param("userId") long userId, @Param("progCd") String progCd);

    /** 사용자가 SEARCH 권한을 가진 프로그램 코드 */
    List<String> findSearchableProgs(@Param("userId") long userId);

    /** 역할별 홈 화면 (없으면 null) */
    String findHomeProg(@Param("userId") long userId);

    /** 사용 중인 프로그램 (전역) */
    ProgInfo findProg(@Param("progCd") String progCd);

    /** 회사 메뉴 전체 (사용 중인 것만, 프로그램이 사용중지면 제외) */
    List<MenuRow> findMenus();
}
