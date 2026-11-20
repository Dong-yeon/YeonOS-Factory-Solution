package io.smarthappy.platform.user;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 사용자 고르기 — 모든 문장이 user_type = 'INTERNAL' (UserDirectory) */
@Mapper
public interface UserDirectoryMapper {

    List<UserOption> activeUsers();

    UserRef byLogin(@Param("loginId") String loginId);

    UserRef byId(@Param("userId") long userId);

    UserOption deptHeadOf(@Param("userId") long userId);

    List<UserOption> groupActionHolders(@Param("progCd") String progCd, @Param("actionCd") String actionCd);
}
