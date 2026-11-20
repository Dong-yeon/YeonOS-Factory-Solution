package io.smarthappy.platform.security;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 로그인·세션 확인용. 모든 호출은 TenantRunner(회사 범위 트랜잭션) 안에서 */
@Mapper
public interface UserAccountMapper {

    LoginAccount findForLogin(@Param("compCd") String compCd, @Param("loginId") String loginId);

    int recordLoginFailure(@Param("userId") long userId, @Param("maxLoginFail") int maxLoginFail,
            @Param("lockMinutes") int lockMinutes);

    /** 지금 유효한 잠금이면 Y */
    String findLockYn(@Param("userId") long userId);

    int recordLoginSuccess(@Param("userId") long userId);

    /**
     * 사용자 사용중·관리자 잠금 아님 + 회사 사용중 + 로그인 시각이 세션 무효 기준(관리자 비밀번호 초기화) 뒤면 1
     * (실패 잠금은 기존 세션을 끊지 않음). loginAt 이 null 이면 무효 기준은 보지 않는다
     */
    int countActive(@Param("userId") long userId, @Param("loginAt") java.time.OffsetDateTime loginAt);

    /** 비밀번호 변경용: 현재 해시 + 유효 잠금 여부(lock_yn 은 유효 잠금으로 계산됨) */
    LoginAccount findPasswordState(@Param("userId") long userId);

    int updatePassword(@Param("userId") long userId, @Param("pwdHash") String pwdHash);
}
