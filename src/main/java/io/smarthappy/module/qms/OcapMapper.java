package io.smarthappy.module.qms;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.qms.OcapDtos.OcapForm;
import io.smarthappy.module.qms.OcapDtos.OcapRow;
import io.smarthappy.module.qms.OcapDtos.PickRow;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface OcapMapper {

    /** rule = null 이면 전체, "*" 이면 공통(rule_cd null)만 */
    List<OcapRow> findPage(@Param("rule") String rule, @Param("useYn") String useYn, @Param("kw") String keywordLike, @Param("page") PageQuery page);

    long count(@Param("rule") String rule, @Param("useYn") String useYn, @Param("kw") String keywordLike);

    OcapRow findById(@Param("id") long id);

    long insert(@Param("f") OcapForm f, @Param("by") String by);

    int update(@Param("id") long id, @Param("f") OcapForm f, @Param("by") String by);

    /** 고르기 목록 — 그 규칙 + 공통의 사용 중 항목, 공통 먼저·순서대로 */
    List<OcapRow> findOptions(@Param("rule") String rule);

    /** 고르는 순간 사본을 뜰 항목 — FOR SHARE(동시 수정과 줄 세움, OC-5). 없는·다른 회사 id 는 빠진다 */
    List<OcapRow> lockForPick(@Param("ids") List<Long> ids);

    int insertPicks(@Param("actId") long actId, @Param("rows") List<OcapRow> rows, @Param("by") String by);

    List<PickRow> findPicksOfEvent(@Param("eventId") long eventId);
}
