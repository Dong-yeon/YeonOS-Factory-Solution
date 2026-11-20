package io.smarthappy.platform.numbering;

import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NumberingMapper {

    /** 사용 중인 규칙: pattern, reset_cycle */
    Map<String, Object> findRule(@Param("ruleCd") String ruleCd);

    /** 원자적 증가 (insert … on conflict … do update … returning). 행 잠금은 호출 트랜잭션 끝까지 */
    long increment(@Param("compCd") String compCd, @Param("ruleCd") String ruleCd, @Param("periodKey") String periodKey);

    /** 없을 때만 만든다(기본 규칙). 있으면 — 꺼져 있어도 — 그대로 */
    int insertRuleIfAbsent(@Param("compCd") String compCd, @Param("ruleCd") String ruleCd, @Param("ruleNm") String ruleNm,
            @Param("pattern") String pattern, @Param("resetCycle") String resetCycle, @Param("actor") String actor);

    int upsertRule(@Param("compCd") String compCd, @Param("ruleCd") String ruleCd, @Param("ruleNm") String ruleNm,
            @Param("pattern") String pattern, @Param("resetCycle") String resetCycle, @Param("actor") String actor);
}
