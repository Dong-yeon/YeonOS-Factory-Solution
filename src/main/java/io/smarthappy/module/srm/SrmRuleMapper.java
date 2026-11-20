package io.smarthappy.module.srm;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 채번 규칙 행이 있는지 (사용 여부 무관) — {@link SrmNumbers} 가 기본 규칙을 만들지 정할 때만 */
@Mapper
public interface SrmRuleMapper {

    @Select("select exists (select 1 from sys_numbering_rule where rule_cd = #{ruleCd})")
    boolean exists(@Param("ruleCd") String ruleCd);
}
