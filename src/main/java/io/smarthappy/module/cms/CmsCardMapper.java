package io.smarthappy.module.cms;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.cms.CmsCardDtos.ChkSum;
import io.smarthappy.module.cms.CmsCardDtos.EquipOption;
import io.smarthappy.module.cms.CmsCardDtos.HistRow;
import io.smarthappy.module.cms.CmsCardDtos.MwoSum;
import io.smarthappy.module.cms.CmsCardDtos.PartSum;
import io.smarthappy.module.cms.CmsCounterDtos.CounterRow;
import io.smarthappy.platform.common.query.PageQuery;

/** CMS C17 — 설비 이력 카드 (docs/33 13.5). 읽기만 */
@Mapper
public interface CmsCardMapper {

    List<EquipOption> findEquips(@Param("keyword") String keyword, @Param("plantCd") String plantCd, @Param("limit") int limit);

    EquipOption findEquip(@Param("equipCd") String equipCd);

    MwoSum mwoSum(@Param("equipId") long equipId, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    ChkSum chkSum(@Param("equipId") long equipId, @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate,
            @Param("today") LocalDate today);

    PartSum partSum(@Param("equipId") long equipId, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    long resetCount(@Param("equipId") long equipId, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    List<CounterRow> findCounters(@Param("equipId") long equipId);

    /** 이력 한 표 — kind 없으면 네 종류 모두. zone = 공장 시간대(점검 예정일 0시) */
    List<HistRow> findHistPage(@Param("equipId") long equipId, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate, @Param("zone") String zone, @Param("kind") String kind,
            @Param("page") PageQuery page);

    long countHist(@Param("equipId") long equipId, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
            @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate, @Param("zone") String zone, @Param("kind") String kind);
}
