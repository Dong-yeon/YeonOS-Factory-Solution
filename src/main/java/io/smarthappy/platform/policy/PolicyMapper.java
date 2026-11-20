package io.smarthappy.platform.policy;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PolicyMapper {

    PolicyDef findDef(@Param("policyKey") String policyKey);

    /** 공장 값이 있으면 공장 값, 없으면 회사 값(plant_cd = '*'), 둘 다 없으면 null */
    String findValue(@Param("policyKey") String policyKey, @Param("plantCd") String plantCd);

    int upsert(@Param("compCd") String compCd, @Param("plantCd") String plantCd, @Param("policyKey") String policyKey,
            @Param("value") String value, @Param("actor") String actor);
}
